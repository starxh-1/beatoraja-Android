package bms.player.beatoraja.play.bga;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.ColorUtil;
import org.jcodec.scale.Transform;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.logging.Logger;

/**
 * JCodec 纯 Java 软解码视频处理器 —— .mpg / .avi 的优雅降级方案。
 *
 * <h3>设计目标</h3>
 * <ul>
 *   <li>在 Android 上避免 FFmpeg JNI 冲突，使用纯 Java 解码器支持早期 BMS 视频</li>
 *   <li>格式支持：MPEG-PS (.mpg/.mpeg/.m1v/.m2v)、AVI (.avi)</li>
 *   <li>当 gdx-video (MediaCodec) 无法处理这些旧格式时，作为备用播放器</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <pre>
 *   [解码线程]                          [GL 主线程]
 *   FrameGrab.getNativeFrame()          getFrame(time)
 *        │                                   │
 *   Picture → RGB Transform             检查 newFrameReady
 *        │                                   │
 *   写入 decodeBuffer (synchronized)    读取 decodeBuffer → Pixmap → Texture
 *        │                                   │
 *   Thread.sleep(frameInterval)         SpriteBatch 绘制
 * </pre>
 *
 * <h3>内存管理</h3>
 * <ul>
 *   <li>Pixmap / Texture / ByteBuffer 全部复用，不逐帧 new</li>
 *   <li>RGB Transform 和 Picture 复用，减少 GC 压力</li>
 *   <li>退出时必须调用 dispose() 停止线程并释放 GPU 资源</li>
 * </ul>
 */
public class JCodecVideoProcessor implements MovieProcessor {

	private static final Logger LOG = Logger.getGlobal();

	/** 默认帧率（BMS BGA 通常为 24~30fps） */
	private static final int DEFAULT_FPS = 30;

	/**
	 * JCodec 能作为 Fallback 处理的旧格式后缀。
	 *
	 * <p>注意：JCodec 0.2.5 的 FrameGrab 不支持 MPEG Program Stream 容器
	 * （.mpg/.mpeg/.m1v/.m2v 会报 "MPEG PS is temporarily unsupported"），
	 * 这些格式交由 gdx-video（Android MediaCodec）处理，MediaCodec 对 MPEG-1/2 有良好的硬解支持。
	 * JCodec 仅处理 AVI 容器（MediaCodec 对 AVI 支持较差）。</p>
	 */
	private static final String[] JCODEC_EXTENSIONS = {"avi"};

	// ─── 文件路径 ───
	private String filepath;

	// ─── 状态标志（volatile 保证跨线程可见性） ───
	private volatile boolean playing = false;
	private volatile boolean disposed = false;
	private volatile boolean loop = false;

	// ─── GL 线程持有的可复用资源 ───
	private Texture texture;
	private Pixmap pixmap;

	// ─── 解码线程 → GL 线程 的帧交换缓冲区 ───
	private ByteBuffer decodeBuffer;          // DirectByteBuffer，容量 = w*h*4 (RGBA)
	private final Object bufferLock = new Object();
	private volatile boolean newFrameReady = false;
	private volatile int videoWidth = 0;
	private volatile int videoHeight = 0;

	// ─── 解码线程 ───
	private Thread decodeThread;

	public JCodecVideoProcessor() {
	}

	/**
	 * 判断给定路径是否属于 JCodec 能处理的旧视频格式。
	 * 供 {@link BGAProcessor} 工厂方法调用，以决定是否使用本处理器。
	 */
	public static boolean isJCodecFormat(String path) {
		if (path == null) return false;
		String lower = path.toLowerCase();
		for (String ext : JCODEC_EXTENSIONS) {
			if (lower.endsWith("." + ext)) return true;
		}
		return false;
	}

	/**
	 * 仅存储文件路径。实际解码在 {@link #play} 时才启动。
	 * 本方法可安全地从后台线程调用（setModel 在后台线程运行）。
	 */
	public void create(String filepath) {
		this.filepath = filepath;
	}

	// ═══════════════════════════════════════════════════════
	//  MovieProcessor 接口实现
	// ═══════════════════════════════════════════════════════

	/**
	 * 由 GL 主线程在每帧渲染时调用。
	 * 检查解码线程是否产出了新帧，如果有则更新 Texture（OpenGL 纹理上传必须在 GL 线程）。
	 */
	@Override
	public void update(long time) {
		// JCodec 使用后台线程解码，主线程只需检查 newFrameReady
		if (newFrameReady) {
			synchronized (bufferLock) {
				if (newFrameReady && videoWidth > 0 && videoHeight > 0) {
					uploadFrameToTexture();
					newFrameReady = false;
				}
			}
		}
	}

	@Override
	public Texture getFrame() {
		return texture;
	}

	@Override
	public void play(long time, boolean loop) {
		if (disposed || filepath == null) return;
		this.loop = loop;

		// 避免重复启动
		if (decodeThread != null && decodeThread.isAlive()) return;

		playing = true;
		decodeThread = new Thread(this::decodeLoop, "JCodec-" + Integer.toHexString(filepath.hashCode()));
		decodeThread.setDaemon(true);
		decodeThread.start();
	}

	@Override
	public void stop() {
		playing = false;
		interruptDecodeThread();
	}

	/**
	 * 切后台/锁屏时暂停解码线程，降低 CPU 占用。
	 */
	@Override
	public void pause() {
		playing = false;
		// 不 interrupt，线程会在下一次 sleep 循环中自然退出
	}

	/**
	 * 切回前台时恢复播放。
	 * JCodec 不支持精确的 seek-resume，因此从头重新开始解码。
	 * 对于 BMS BGA 短循环视频，这是可接受的降级行为。
	 */
	@Override
	public void resume() {
		if (disposed || filepath == null) return;
		if (decodeThread != null && decodeThread.isAlive()) return;
		playing = true;
		decodeThread = new Thread(this::decodeLoop, "JCodec-" + Integer.toHexString(filepath.hashCode()));
		decodeThread.setDaemon(true);
		decodeThread.start();
	}

	/**
	 * 严格释放所有资源：停止线程 → dispose Texture/Pixmap → 释放 ByteBuffer。
	 * 任何异常均被捕获，绝不让释放过程导致游戏崩溃。
	 */
	@Override
	public void dispose() {
		disposed = true;
		playing = false;

		interruptDecodeThread();
		if (decodeThread != null) {
			try {
				decodeThread.join(1500);
			} catch (InterruptedException ignored) {
			}
			decodeThread = null;
		}

		if (texture != null) {
			try {
				texture.dispose();
			} catch (Exception e) {
				LOG.warning("JCodecVideoProcessor texture dispose error: " + e.getMessage());
			}
			texture = null;
		}
		if (pixmap != null) {
			try {
				pixmap.dispose();
			} catch (Exception e) {
				LOG.warning("JCodecVideoProcessor pixmap dispose error: " + e.getMessage());
			}
			pixmap = null;
		}
		decodeBuffer = null;
	}

	/**
	 * JCodec 解码输出标准 RGB 纹理，使用 Linear 滤镜即可，无需 FFmpeg YUV→RGB Shader。
	 */
	@Override
	public int getRenderType() {
		return 1; // SkinObjectRenderer.TYPE_LINEAR
	}

	// ═══════════════════════════════════════════════════════
	//  解码线程核心逻辑
	// ═══════════════════════════════════════════════════════

	private void decodeLoop() {
		SeekableByteChannel channel = null;
		try {
			channel = NIOUtils.readableChannel(new File(filepath));
			FrameGrab grab = FrameGrab.createFrameGrab(channel);

			long frameIntervalMs = 1000L / DEFAULT_FPS;

			// 复用的 RGB 转换对象（GC 防护）
			Transform transform = null;
			Picture rgbPic = null;

			while (playing && !disposed) {
				Picture nativeFrame;
				try {
					nativeFrame = grab.getNativeFrame();
				} catch (Exception e) {
					LOG.warning("JCodec getNativeFrame error: " + e.getMessage());
					break;
				}

				if (nativeFrame == null) {
					// 视频播放完毕
					if (loop) {
						// 重新打开文件从头播放（JCodec 的 seek 对 MPEG-PS 不够可靠）
						NIOUtils.closeQuietly(channel);
						channel = NIOUtils.readableChannel(new File(filepath));
						grab = FrameGrab.createFrameGrab(channel);
						transform = null;
						rgbPic = null;
						continue;
					}
					break;
				}

				int w = nativeFrame.getWidth();
				int h = nativeFrame.getHeight();
				if (w <= 0 || h <= 0) continue;

				// 惰性初始化 RGB 转换器（复用，避免每帧 new）
				if (transform == null) {
					transform = ColorUtil.getTransform(nativeFrame.getColor(), ColorSpace.RGB);
					rgbPic = Picture.create(w, h, ColorSpace.RGB);
				}

				// YUV → RGB 颜色空间转换
				transform.transform(nativeFrame, rgbPic);

				// 提取 RGB 三个平面的数据（JCodec 0.2.5 的 Picture 使用 byte[] 存储）
				byte[] rPlane = rgbPic.getPlaneData(0);
				byte[] gPlane = rgbPic.getPlaneData(1);
				byte[] bPlane = rgbPic.getPlaneData(2);
				int pixelCount = w * h;
				int bufferSize = pixelCount * 4; // RGBA8888

				synchronized (bufferLock) {
					// 复用 DirectByteBuffer，仅在尺寸不足时重新分配
					if (decodeBuffer == null || decodeBuffer.capacity() < bufferSize) {
						decodeBuffer = ByteBuffer.allocateDirect(bufferSize);
					}
					decodeBuffer.position(0);

					// 将 RGB 平面数据交织为 RGBA8888 字节流
					// byte 在 Java 中是有符号的 (-128~127)，但底层位模式等价于无符号 0~255，
					// ByteBuffer.put(byte) 直接写入原始字节，OpenGL 按 unsigned 解释，无需转换。
					for (int i = 0; i < pixelCount; i++) {
						decodeBuffer.put(rPlane[i]);
						decodeBuffer.put(gPlane[i]);
						decodeBuffer.put(bPlane[i]);
						decodeBuffer.put((byte) 0xFF); // Alpha = 不透明
					}
					decodeBuffer.flip();

					videoWidth = w;
					videoHeight = h;
					newFrameReady = true;
				}

				// 帧率控制：休眠以匹配目标 FPS，避免解码过快浪费 CPU
				try {
					Thread.sleep(frameIntervalMs);
				} catch (InterruptedException e) {
					break;
				}
			}
		} catch (Exception e) {
			LOG.warning("JCodec decode failed for " + filepath + ": " + e.getMessage());
		} finally {
			NIOUtils.closeQuietly(channel);
		}
		playing = false;
	}

	// ═══════════════════════════════════════════════════════
	//  GL 线程：Pixmap → Texture 上传
	// ═══════════════════════════════════════════════════════

	/**
	 * 将解码缓冲区的 RGBA 数据上传到 OpenGL Texture。
	 * 必须在 GL 主线程调用（由 getFrame 保证）。
	 *
	 * <p>等价于用户要求的 {@code GLUtils.texImage2D} 操作，
	 * 但使用 LibGDX 标准 API（内部调用 glTexSubImage2D）以保证跨平台兼容。</p>
	 */
	private void uploadFrameToTexture() {
		// 复用 Pixmap：仅在分辨率变化时重建
		if (pixmap == null || pixmap.getWidth() != videoWidth || pixmap.getHeight() != videoHeight) {
			if (pixmap != null) pixmap.dispose();
			pixmap = new Pixmap(videoWidth, videoHeight, Pixmap.Format.RGBA8888);
		}

		// 将 decodeBuffer 拷贝到 Pixmap 的 native buffer
		ByteBuffer pixels = pixmap.getPixels();
		pixels.position(0);
		decodeBuffer.position(0);
		pixels.put(decodeBuffer);
		pixels.position(0);

		// 复用 Texture：仅在分辨率变化时重建，否则就地更新纹理数据
		if (texture == null || texture.getWidth() != videoWidth || texture.getHeight() != videoHeight) {
			if (texture != null) texture.dispose();
			texture = new Texture(pixmap);
		} else {
			// glTexSubImage2D —— 就地更新，不重建 GPU 纹理对象
			texture.draw(pixmap, 0, 0);
		}
	}

	// ═══════════════════════════════════════════════════════
	//  工具方法
	// ═══════════════════════════════════════════════════════

	/**
	 * 将 int 像素值钳位到 [0, 255] 并转为 byte。
	 * 保留备用：JCodec 0.2.5 的 Picture 已使用 byte[] 存储，当前解码循环直接写入原始字节。
	 * 若未来版本回退到 int[] 存储或需要额外的色域钳位，可重新启用。
	 */
	@SuppressWarnings("unused")
	private static byte clampToByte(int value) {
		return (byte) (value < 0 ? 0 : (value > 255 ? 255 : value));
	}

	private void interruptDecodeThread() {
		if (decodeThread != null) {
			try {
				decodeThread.interrupt();
			} catch (Exception ignored) {
			}
		}
	}
}
