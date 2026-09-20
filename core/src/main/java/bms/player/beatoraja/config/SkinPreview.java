package bms.player.beatoraja.config;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.logging.Logger;

import bms.player.beatoraja.MainState;
import bms.player.beatoraja.skin.Skin;
import bms.player.beatoraja.skin.Skin.SkinObjectRenderer;
import bms.player.beatoraja.skin.SkinObject;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.BufferUtils;

/**
 * 皮肤选择界面（SKIN SELECT）上的「实时预览」对象。
 *
 * <p>把皮肤选择界面当前选中的那张皮肤，用一个离屏 FrameBuffer 按它自己的分辨率
 * 渲染一遍，再把结果纹理贴到界面上指定的位置。因为每帧都重新渲染，"实时"体现在
 * 两点：① 切换皮肤立刻反映；② 右侧那些自定义参数（Layout / Lane Size / Scratch Side …）
 * 一改，预览里的轨道布局就跟着变。</p>
 *
 * <p>它本身是一个 {@link SkinObject}（由皮肤里的 {@code skin.skinpreview} 声明 id、
 * 由 {@code skin.destination} 给它 dst），所以位置、缩放、甚至动效都交给皮肤控制。</p>
 */
public class SkinPreview extends SkinObject {

	/**
	 * 查询 GL 状态用的复用缓冲（与 {@code BGAProcessor.renderBGAToFramebuffer} 同样的做法）。
	 */
	private static final IntBuffer viewportBuffer = BufferUtils.newIntBuffer(16);
	private static final FloatBuffer clearColorBuffer = BufferUtils.newFloatBuffer(4);

	/** 预览的宿主状态；第一次 prepare 时从 state 抓到。 */
	private SkinConfiguration configuration;
	/** 离屏渲染专用的批次，与外层绘制批次互不干扰。 */
	private SpriteBatch previewBatch;
	private FrameBuffer frameBuffer;
	private TextureRegion frameRegion;
	private Skin lastSkin;
	private int bufferWidth;
	private int bufferHeight;
	/**
	 * 渲染一旦抛异常就永久停用本预览。
	 *
	 * <p>预览是在"没有谱面数据"的环境里渲染真实 play 皮肤，某些对象可能因为拿不到数据
	 * 而稳定失败（例如依赖 BMSModel 的元素）。这类失败不会自愈，每帧重试只是白白浪费
	 * 掉帧时间，所以第一次失败后直接放弃，并允许 {@code lastSkin} 变化时重新启用。</p>
	 */
	private boolean disabled;

	/**
	 * 预览自己的动画时钟起点（{@link System#nanoTime()}）。
	 *
	 * <p>预览皮肤实例一变（切皮肤，或改 Layout / Lane Size 这类参数触发重建，
	 * 见 {@code SkinConfiguration.loadSelectedSkinPreview()} 每次都会 new 一份）就重置 ——
	 * 也就是"每换一张皮肤，入场动画重播一次"，与真机上刚切进这个界面时一样。</p>
	 *
	 * <p>不能直接用 {@code state.timer}：那是"进入皮肤选择界面以来的时长"，只会一直涨，
	 * 而 select / decide / result 这类皮肤会在 {@code scene} 末尾播退场动画
	 * （典型是 {@code id = -110} 的全屏黑图拉到 a=255），于是预览会被退场动画永久盖住。
	 * 见 {@link #resolvePreviewTime}。</p>
	 */
	private long clockStartNanos;

	/**
	 * 钳制退场动画时至少给 {@code scene} 末尾留出多少毫秒。
	 *
	 * <p>皮肤声明的 {@code fadeout} 就是退场时长，正常情况下够用；但它可能缺失（= 0）而皮肤
	 * 仍然有退场动画，所以再兜一个下限。</p>
	 */
	private static final long PREVIEW_TAIL_MARGIN_MS = 500;

	@Override
	public void prepare(long time, MainState state) {
		if (configuration == null && state instanceof SkinConfiguration) {
			configuration = (SkinConfiguration) state;
		}
		super.prepare(time, state);
	}

	@Override
	public void draw(SkinObjectRenderer renderer) {
		if (!draw || configuration == null) {
			return;
		}

		Skin previewSkin = configuration.getSelectedSkin();
		if (previewSkin == null) {
			return;
		}
		if (previewSkin != lastSkin) {
			lastSkin = previewSkin;
			disabled = false;
			// 新皮肤从 0 开始播它自己的入场动画（和真机上刚切进这个界面时一样）
			clockStartNanos = System.nanoTime();
		}
		if (disabled) {
			return;
		}

		// 外层正在这个 batch 上绘制皮肤。要往 FBO 里画别的东西，必须先把当前批次的
		// 内容吐出去并结束批次；画完再重新 begin，让调用方（Skin.drawAllObjects）
		// 后续的对象继续沿用同一个批次。
		SpriteBatch currentBatch = renderer.getSpriteBatch();
		currentBatch.flush();
		currentBatch.end();
		try {
			renderPreview(previewSkin);
		} catch (Throwable e) {
			disabled = true;
			Logger.getGlobal().warning("皮肤预览渲染失败，已停用本预览 : " + e);
		} finally {
			// 预览用的是另一个 SpriteBatch，它直接改写了 GL 的 blend 状态，而外层
			// SkinObjectRenderer 只会"在状态自认为变化时"才重设 blend —— 它缓存的
			// activeBlend 因此失真（例如预览前刚设过加法混合，之后的对象就不会再设，
			// 混出来的效果不对）。复位一次，让下一个对象按需重新设置。
			renderer.reset();
			currentBatch.begin();
		}

		draw(renderer, frameRegion);
	}

	/**
	 * 预览的动画时钟：从本次加载起算，并**钳制在皮肤的"稳态显示窗口"内**。
	 *
	 * <p>为什么需要它（2026-09-20 实机踩到，症状是"预览只显示一次然后永久黑屏"）：
	 * 预览的 state 是 {@code SkinConfiguration}，{@code TimerManager.getNowTime()} 返回
	 * **"进入皮肤选择界面以来的毫秒数"**，只会一直涨。而 m-select / Luxe_Flat 这类皮肤的
	 * 屏幕末尾有退场动画，典型写法是：</p>
	 *
	 * <pre>
	 * {id = -110, loop = skin.scene, dst = {                          -- -110 = IMAGE_BLACK
	 *     {time = skin.scene - 200, x = 0, y = 0, w = 1920, h = 1080, a = 0},
	 *     {time = skin.scene, a = 255}}}
	 * </pre>
	 *
	 * <p>{@code -110} 是全屏黑图，最后一帧 {@code a = 255}；又因为 {@code loop == 最后一帧的
	 * time}，{@code SkinObject.prepareRegion()} 会走 {@code if (lasttime == dstloop) time = dstloop;}
	 * 这条分支 —— 时间一旦超过它就是"钉在最后一帧"，所以进入界面约 2.5 秒后，预览被这张
	 * 不透明黑图彻底盖住，切别的皮肤再切回来也不会恢复（计时器不会回退）。</p>
	 *
	 * <p>所以把时钟限制在 {@code [0, scene - max(fadeout, 500)]}：入场动画照播（切一次皮肤播一次），
	 * 播完停在"皮肤正常显示时的样子"，永不进入退场动画。</p>
	 *
	 * <p>{@code scene} 未声明（= {@link Skin#SCENE_UNSPECIFIED}）的皮肤（play 系列大多如此）
	 * **不做任何限制**，等于保持改动前的行为。</p>
	 */
	private long resolvePreviewTime(Skin previewSkin, long elapsedMs) {
		final int scene = previewSkin.getScene();
		if (scene <= 0 || scene >= Skin.SCENE_UNSPECIFIED) {
			return elapsedMs;
		}
		long span = scene - Math.max(previewSkin.getFadeout(), PREVIEW_TAIL_MARGIN_MS);
		if (span <= 0) {
			span = scene / 2;
		}
		return Math.min(elapsedMs, span);
	}

	private void renderPreview(Skin previewSkin) {
		int width = Math.max(1, Math.round(previewSkin.getWidth()));
		int height = Math.max(1, Math.round(previewSkin.getHeight()));
		ensureFrameBuffer(width, height);

		// 预览批次是长驻对象，投影/变换矩阵在渲染期间会被改写，先留存后还原。
		Matrix4 projection = new Matrix4(previewBatch.getProjectionMatrix());
		Matrix4 transform = new Matrix4(previewBatch.getTransformMatrix());

		// ── GL 状态保护（这是"有预览时皮肤选择界面被粗暴拉伸"的根因）──
		// FrameBuffer.begin()/end() 都会改写 GL 视口，而 end() 是写死的
		// glViewport(0, 0, backBufferW, backBufferH) —— 也就是"整个 surface"。
		// MainController.render() 每帧设的等比视口（pillarbox / letterbox，或
		// stretchFullscreen 时的全屏）会被这一步冲掉，于是预览之后画的所有内容都
		// 按 fullscreen 视口线性铺开，看起来就是分辨率被拉伸。
		// 因为预览是在皮肤绘制中途插进去的（Skin.drawAllObjects 的循环里），
		// 必须自己存/还原，不能指望调用方重设。clearColor 同理
		// （KeyConfiguration.render() 里那句重复设 clearColor 就是被同类问题咬过）。
		Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewportBuffer);
		final int prevX = viewportBuffer.get(0);
		final int prevY = viewportBuffer.get(1);
		final int prevW = viewportBuffer.get(2);
		final int prevH = viewportBuffer.get(3);
		Gdx.gl.glGetFloatv(GL20.GL_COLOR_CLEAR_VALUE, clearColorBuffer);
		final float prevCr = clearColorBuffer.get(0);
		final float prevCg = clearColorBuffer.get(1);
		final float prevCb = clearColorBuffer.get(2);
		final float prevCa = clearColorBuffer.get(3);

		frameBuffer.begin();
		Gdx.gl.glClearColor(0f, 0f, 0f, 0f);
		Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
		previewBatch.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0, width, height));
		previewBatch.setTransformMatrix(new Matrix4());
		previewBatch.begin();
		try {
			try {
				previewSkin.updateCustomObjects(configuration);
			} catch (Throwable e) {
				// 自定义计时器/事件通常绑定具体游戏状态（谱面、分数…），在预览环境里失败是
				// 预期内的；只跳过它们，别让静态元素（背景、轨道、判定线）跟着一起消失。
			}
			// 用"本次加载起算"的时钟，并钳制在稳态显示窗口内（见 resolvePreviewTime）
			final long elapsedMs = (System.nanoTime() - clockStartNanos) / 1000000L;
			previewSkin.drawAllObjectsSafely(previewBatch, configuration, resolvePreviewTime(previewSkin, elapsedMs));
		} finally {
			previewBatch.end();
			frameBuffer.end();
			// 还原外层这一帧的等比视口与清屏色（顺序无所谓，两者互不影响）
			Gdx.gl.glViewport(prevX, prevY, prevW, prevH);
			Gdx.gl.glClearColor(prevCr, prevCg, prevCb, prevCa);
			previewBatch.setProjectionMatrix(projection);
			previewBatch.setTransformMatrix(transform);
		}
	}

	private void ensureFrameBuffer(int width, int height) {
		if (previewBatch == null) {
			previewBatch = new SpriteBatch();
		}
		if (frameBuffer != null && bufferWidth == width && bufferHeight == height) {
			return;
		}

		if (frameBuffer != null) {
			frameBuffer.dispose();
		}
		bufferWidth = width;
		bufferHeight = height;
		frameBuffer = new FrameBuffer(Pixmap.Format.RGBA8888, bufferWidth, bufferHeight, false);
		Texture texture = frameBuffer.getColorBufferTexture();
		// LibGDX 的 FBO 纹理是上下翻转的，取区域时翻回来，否则预览会倒着显示。
		frameRegion = new TextureRegion(texture);
		frameRegion.flip(false, true);
	}

	@Override
	public void dispose() {
		if (frameBuffer != null) {
			frameBuffer.dispose();
			frameBuffer = null;
		}
		if (previewBatch != null) {
			previewBatch.dispose();
			previewBatch = null;
		}
	}
}
