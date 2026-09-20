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
			previewSkin.drawAllObjectsSafely(previewBatch, configuration);
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
