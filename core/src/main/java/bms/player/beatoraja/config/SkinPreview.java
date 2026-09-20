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
	 * 钳制退场动画时，{@code scene} 末尾至少留出多少毫秒。
	 *
	 * <p>两个用途：① 皮肤声明的 {@code fadeout} 就是退场时长，正常情况下够用，但它可能缺失
	 * （= 0）而皮肤仍然有退场动画，所以再兜一个下限；② 在算出"退场开始时刻"之后再多让出
	 * 这一段，因为实测皮肤常把退场动画的首帧放在声明值之前 200ms 左右。</p>
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
	 * 预览的动画时间：用 **state 自己的时钟**，但钳制在皮肤的"稳态显示窗口"内。
	 *
	 * <p>为什么需要钳制（2026-09-20 实机踩到，症状是"预览只显示一次然后永久黑屏"）：
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
	 * <p><b>为什么不能改用"从 0 重新计时"的独立时钟</b>（2026-09-20 的另一半教训）：
	 * 判定图 / combo 数字的 dst 挂着 {@code timer}（{@code TIMER_JUDGE_xP} / {@code TIMER_COMBO_xP}），
	 * {@code prepareRegion()} 里做的是 {@code time -= timer.get(state)}，而 timer 值也是在
	 * state 时钟的时基上打的时间戳。独立时钟比 state 时钟小，差值就成了负数，对象被判成
	 * "还没开始"（{@code starttime > time}）而整帧不画 —— 实测症状是"判定跟 combo 都不见了"。
	 * 所以这里只做**钳制**（= 同一个时基上取 min），绝不换时基。</p>
	 *
	 * <p>钳制点是"退场动画开始之前"：{@code scene - max(fadeout, 500) - 500}。
	 * 对 play / result 皮肤没有影响 —— 它们的 {@code scene = 3600000}（1 小时，约定俗成的
	 * "没有退场动画"），钳制点约 3599000ms，进界面后一小时才会碰到。</p>
	 */
	private long resolvePreviewTime(Skin previewSkin, long stateTimeMs) {
		final int scene = previewSkin.getScene();
		if (scene <= 0 || scene == Skin.SCENE_UNSPECIFIED) {
			return stateTimeMs;
		}
		// 退场动画由皮肤自己声明在 scene 末尾（fadeout 就是它声明的时长），再留一段余量
		final long exitStart = scene - Math.max(previewSkin.getFadeout(), PREVIEW_TAIL_MARGIN_MS);
		final long span = Math.max(1, exitStart - PREVIEW_TAIL_MARGIN_MS);
		return Math.min(stateTimeMs, span);
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
			// 用 state 时钟、并钳制在稳态显示窗口内（见 resolvePreviewTime）。
			// 这里只能传"同一时基上的 min"，不能换成从 0 起的独立时钟：
			// dst 挂 timer 的判定 / combo 会因差值为负而整帧不画。
			final long stateTime = configuration.timer.getNowTime();
			previewSkin.drawAllObjectsSafely(previewBatch, configuration, resolvePreviewTime(previewSkin, stateTime));
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
