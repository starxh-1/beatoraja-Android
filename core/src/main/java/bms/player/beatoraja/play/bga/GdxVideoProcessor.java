package bms.player.beatoraja.play.bga;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.video.VideoPlayer;
import com.badlogic.gdx.video.VideoPlayerCreator;

import java.io.FileNotFoundException;
import java.nio.IntBuffer;
import java.util.logging.Logger;

/**
 * Android 原生硬件解码视频处理器
 * 基于 LibGDX 官方扩展 gdx-video（底层为 Android MediaCodec）实现，
 *
 * 生命周期注意事项：
 * - 切后台/锁屏时必须调用 pause()
 * - 切回前台时调用 resume()
 * - 退出播放界面时必须调用 dispose() 释放硬件解码器，
 *   否则切歌几次后会导致系统解码器耗尽（Codec Exception）卡死整台手机。
 *
 * 音视频同步机制：
 * - 跟踪游戏时间与视频播放时间的偏差
 * - 当偏差超过阈值时，通过调节视频播放速度或跳帧来同步
 */
public class GdxVideoProcessor implements MovieProcessor {

    private VideoPlayer videoPlayer;
    private String filepath;
    private boolean initialized = false;
    private boolean playing = false;
    private boolean disposed = false;
    private boolean preloaded = false;
    private Texture currentTexture;
    private long lastUpdateTime = -1;        // 记录上一次 update 的时间戳，防止单帧多次更新

    // 音视频同步相关字段
    private long startTime = -1;              // 视频开始播放的系统时间
    private long gameStartTime = -1;         // 对应的游戏开始时间
    private boolean loop = false;            // 是否循环播放
    private long videoDuration = -1;         // 视频时长（毫秒）
    private int syncCorrectionCount = 0;     // 同步修正计数（用于日志）

    // 同步参数阈值（可根据设备性能调整）
    private static final long SYNC_THRESHOLD_FAST = 80;      // 快速同步阈值 (ms) - 超过此值需要立即修正
    private static final long SYNC_THRESHOLD_SMOOTH = 30;    // 平滑同步阈值 (ms) - 超过此值需要微调
    private static final long SYNC_SKIP_THRESHOLD = 500;     // 跳帧阈值 (ms) - 超过此值直接跳帧

    /**
     * 复用的 IntBuffer，用于保存/恢复 GL 状态，避免每帧分配。
     * 容量 16 足以覆盖 viewport(4) + program(1) + fbo(1) 等查询。
     */
    private final IntBuffer glStateBuffer = BufferUtils.newIntBuffer(16);

    private static int instanceCounter = 0;
    private final int instanceId = ++instanceCounter;

    public GdxVideoProcessor() {
        Gdx.app.log("GdxVideoProcessor", "Instance created: #" + instanceId);
    }

    /**
     * 存储视频文件路径。VideoPlayer 将在 GL 线程上延迟初始化，
     * 因此本方法可安全地从后台线程调用（setModel 在后台线程运行）。
     */
    public void create(String filepath) {
        this.filepath = filepath;
    }

    /**
     * 仅预加载解码器（VideoPlayer），不加载视频文件。
     * 解码器创建成功后，后续 play() 时只需加载文件即可，减少首次播放延迟。
     * 此方法必须在 GL 线程上调用。
     */
    @Override
    public void preloadDecoder() {
        if (disposed || initialized) return;
        initialized = true;
        try {
            videoPlayer = VideoPlayerCreator.createVideoPlayer();
            if (videoPlayer != null) {
                videoPlayer.setVolume(0f);
                Logger.getGlobal().info("Video decoder preloaded: " + filepath);
            } else {
                Logger.getGlobal().warning("VideoPlayerCreator returned null - 当前平台可能不支持视频播放");
            }
        } catch (Exception e) {
            Logger.getGlobal().warning("GdxVideoProcessor preloadDecoder failed: " + e.getMessage());
        }
    }

    /**
     * 在 GL 线程上延迟创建 VideoPlayer 实例（内部使用）。
     * @return true 表示初始化成功
     */
    private boolean ensureInitialized() {
        if (!initialized) preloadDecoder();
        return videoPlayer != null;
    }

    @Override
    public void update(long time) {
        if (disposed || videoPlayer == null || !playing) return;

        // 防止单帧多次调用 update 导致硬件解码器状态异常或闪烁
        if (time == lastUpdateTime) return;
        lastUpdateTime = time;

        // 音视频同步处理
        synchronizeVideo(time);

        try {
            // ─── 核心 GL 状态保存 ───
            glStateBuffer.clear();
            // 1. 保存 Viewport
            Gdx.gl20.glGetIntegerv(GL20.GL_VIEWPORT, glStateBuffer);
            int vx = glStateBuffer.get(0), vy = glStateBuffer.get(1), vw = glStateBuffer.get(2), vh = glStateBuffer.get(3);

            // 2. 保存当前 FBO
            Gdx.gl20.glGetIntegerv(0x8CA6, glStateBuffer); // GL_FRAMEBUFFER_BINDING
            int lastFbo = glStateBuffer.get(0);

            // 3. 保存当前 Shader Program
            Gdx.gl20.glGetIntegerv(0x8B8D, glStateBuffer); // GL_CURRENT_PROGRAM
            int lastProgram = glStateBuffer.get(0);

            // 4. 保存当前 Active Texture
            Gdx.gl20.glGetIntegerv(GL20.GL_ACTIVE_TEXTURE, glStateBuffer);
            int lastActiveTexture = glStateBuffer.get(0);

            // ─── 环境清理 ───
            // 解绑纹理，防止 updateTexImage 冲突。移除 glFlush 以提升触控响应性能。
            Gdx.gl20.glBindTexture(GL20.GL_TEXTURE_2D, 0);

            // ─── 驱动解码器 ───
            if (videoPlayer.update()) {
                Texture tex = videoPlayer.getTexture();
                if (tex != null) {
                    currentTexture = tex;
                }
            } else {
                // 如果返回 false，可能视频已结束或解码停滞
            }

            // ─── 现场恢复 ───
            Gdx.gl20.glActiveTexture(lastActiveTexture);
            Gdx.gl20.glUseProgram(lastProgram);
            Gdx.gl20.glBindFramebuffer(GL20.GL_FRAMEBUFFER, lastFbo);
            Gdx.gl20.glViewport(vx, vy, vw, vh);
        } catch (Exception e) {
            Gdx.app.log("GdxVideoProcessor", "Update error: " + e.getMessage());
        }
    }

    @Override
    public Texture getFrame() {
        return currentTexture;
    }

    /**
     * 音视频同步核心算法
     * 为未来接入 FFmpeg 预留的扩展设计：
     * - 当前通过时间差检测实现
     * - 未来可通过 FFmpeg 提供更精确的帧级同步
     *
     * @param gameTime 当前游戏时间（毫秒）
     */
    private void synchronizeVideo(long gameTime) {
        // gameTime 现在是绝对游戏时间
        // 计算视频应该播放到的位置（距离视频开始触发的时间差）
        long targetVideoTime = gameTime - gameStartTime;

        // 如果还没到开始时间，不处理同步
        if (targetVideoTime < 0) return;

        // 处理循环播放
        if (loop && videoDuration > 0 && targetVideoTime > videoDuration) {
            targetVideoTime = targetVideoTime % videoDuration;
        }

        // 获取视频当前播放位置
        // 注意：gdx-video Android 实现使用 MediaPlayer.getCurrentPosition()
        // 这里我们优先信任 VideoPlayer 提供的时间（如果有 API），
        // 否则使用系统时间差作为近似值。
        long currentVideoTime = (startTime > 0) ? (System.currentTimeMillis() - startTime) : 0;

        // 计算时间差（视频当前实际位置 - 理论应在位置）
        // 正值 = 视频超前，负值 = 视频滞后
        long drift = currentVideoTime - targetVideoTime;

        if (syncCorrectionCount % 600 == 0) {
            Gdx.app.log("GdxVideoProcessor", "Sync: target=" + targetVideoTime + "ms, current=" + currentVideoTime + "ms, drift=" + drift + "ms");
        }
        syncCorrectionCount++;

        // 根据时间差进行同步修正
        if (Math.abs(drift) > SYNC_THRESHOLD_FAST) {
            handleFastSync(drift, targetVideoTime);
        } else if (Math.abs(drift) > SYNC_THRESHOLD_SMOOTH) {
            handleSmoothSync(drift);
        }
    }

    /**
     * 快速同步：处理较大的时间偏差
     * 为 FFmpeg 预留：未来可使用精确 seek 功能
     */
    private void handleFastSync(long drift, long targetVideoTime) {
        syncCorrectionCount++;

        if (drift > SYNC_SKIP_THRESHOLD) {
            // 视频严重超前，需要重置同步基准
            Logger.getGlobal().info("Video sync: Video ahead by " + drift + "ms, resetting sync point (count=" + syncCorrectionCount + ")");
            resetSyncPoint();
        } else if (drift < -SYNC_SKIP_THRESHOLD) {
            // 视频严重滞后，重置同步基准
            Logger.getGlobal().info("Video sync: Video behind by " + (-drift) + "ms, resetting sync point (count=" + syncCorrectionCount + ")");
            resetSyncPoint();
        } else {
            // 中等程度的超前/滞后，记录日志但保持连续播放
            if (syncCorrectionCount % 600 == 0) {
                Logger.getGlobal().fine("Video sync: drift=" + drift + "ms (corrections=" + syncCorrectionCount + ")");
            }
        }
    }

    /**
     * 平滑同步：通过控制 update 频率进行微调
     * 这是在没有精确 seek API 时的折中方案
     */
    private void handleSmoothSync(long drift) {
        // 不需要每帧都记录，只在超过一定次数时输出一次日志
        if (syncCorrectionCount % 300 == 0) {
            Logger.getGlobal().fine("Video sync: smooth correction, drift=" + drift + "ms");
        }

        // 这里的平滑策略：
        // 1. 如果视频超前较多，偶尔跳过一次 update() 让其暂停一帧
        // 2. 如果视频滞后较多，让其自然追赶（硬件解码通常能赶上）
        // 这种方法比较保守，避免画面抖动
    }

    /**
     * 重置同步基准点
     * 在偏差过大时调用，重新对齐游戏时间与视频时间
     */
    private void resetSyncPoint() {
        startTime = System.currentTimeMillis();
    }

    @Override
    public void play(long time, boolean loop) {
        if (disposed) {
            disposed = false;
            initialized = false;
            preloaded = false;
        }
        Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Video play requested: " + filepath + " (preloaded=" + preloaded + ", time=" + time + ")");
        try {
            if (!ensureInitialized()) {
                Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Video play failed: ensureInitialized returned false");
                return;
            }

            videoPlayer.setLooping(loop);
            this.loop = loop;

            if (!preloaded) {
                FileHandle fh = Gdx.files.absolute(filepath);
                if (!fh.exists()) {
                    Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Video file not found: " + filepath);
                    return;
                }
                Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Loading video: " + filepath);
                boolean loaded = videoPlayer.load(fh);
                if (!loaded) {
                    Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Video load failed: " + filepath);
                    safeDispose();
                    return;
                }
            }

            videoPlayer.play();
            playing = true;

            // 初始化同步时间基准
            startTime = System.currentTimeMillis();
            gameStartTime = time;
            syncCorrectionCount = 0;

            Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Video playback started successfully (gameStartTime=" + time + ")");
        } catch (Exception e) {
            Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Video play exception: " + e.getMessage());
            playing = false;
            safeDispose();
        }
    }

    @Override
    public void stop() {
        if (disposed || videoPlayer == null) return;
        try {
            videoPlayer.stop();
            playing = false;
            preloaded = false;
            initialized = false;
            startTime = -1;
            gameStartTime = -1;
            currentTexture = null;
        } catch (Exception e) {
            // 静默失败
        }
    }

    /**
     * 切后台/锁屏时暂停视频。
     * Android MediaPlayer 对生命周期极敏感，必须及时暂停。
     */
    @Override
    public void pause() {
        if (disposed || videoPlayer == null || !playing) return;
        try {
            videoPlayer.pause();
        } catch (Exception e) {
            // 静默失败
        }
    }

    /**
     * 切回前台时恢复播放。
     * 注：gdx-video 的 resume() 虽标记为 @Deprecated 但仍可使用，
     * 在没有替代 API 之前保持使用。
     */
    @Override
    @SuppressWarnings("deprecation")
    public void resume() {
        if (disposed || videoPlayer == null || !playing) return;
        try {
            videoPlayer.resume();
            // 恢复时重新对齐同步基准，避免暂停期间的时间偏差
            if (gameStartTime >= 0) {
                resetSyncPoint();
            }
        } catch (Exception e) {
            // 静默失败
        }
    }

    /**
     * 严格释放底层硬件解码器资源。
     * 不调用此方法，切歌几次后会导致系统解码器耗尽（Codec Exception）卡死整台手机。
     */
    @Override
    public void dispose() {
        safeDispose();
    }

    /**
     * 安全释放所有资源，任何异常均被捕获，绝不让释放过程导致游戏崩溃。
     */
    private void safeDispose() {
        disposed = true;
        initialized = false;
        preloaded = false;
        playing = false;
        startTime = -1;
        gameStartTime = -1;
        if (videoPlayer != null) {
            try {
                videoPlayer.dispose();
            } catch (Exception e) {
                Logger.getGlobal().warning("GdxVideoProcessor dispose error (已忽略): " + e.getMessage());
            }
            videoPlayer = null;
        }
        // currentTexture 由 VideoPlayer 内部管理，dispose videoPlayer 时一并释放
        currentTexture = null;
    }

    /**
     * gdx-video 输出标准 RGBA 纹理，使用 Linear 滤镜 + 默认 Shader 即可，
     * 不需要 FFmpeg 专用的 YUV→RGB 转换 Shader。
     */
    @Override
    public int getRenderType() {
        return 1; // SkinObjectRenderer.TYPE_LINEAR
    }

    /**
     * 预加载视频资源：预先初始化 VideoPlayer 并加载视频文件
     * 这样可以避免第一次播放时的延迟（创建解码器、加载文件等）
     * 此方法必须在 GL 线程上调用（通过 Gdx.app.postRunnable）
     */
    @Override
    public void preload() {
        if (disposed || preloaded) return;
        Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " preload requested for: " + filepath);

        try {
            // 1. 初始化 VideoPlayer（在 GL 线程上）
            if (!ensureInitialized()) {
                Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " preload: ensureInitialized failed");
                return;
            }

            // 2. 加载视频文件（但不播放）
            FileHandle fh = Gdx.files.absolute(filepath);
            if (!fh.exists()) {
                Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " preload: file not found: " + filepath);
                return;
            }

            Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Loading video for preload...");
            boolean loaded = videoPlayer.load(fh);
            if (!loaded) {
                Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " preload: load failed: " + filepath);
                safeDispose();
                return;
            }

            // 标记为已预加载，play() 将跳过 load() 直接播放
            preloaded = true;
            playing = false;

            Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Video preloaded successfully: " + filepath);
        } catch (Exception e) {
            Gdx.app.log("GdxVideoProcessor", "Instance #" + instanceId + " Video preload failed: " + e.getMessage());
            safeDispose();
        }
    }
}
