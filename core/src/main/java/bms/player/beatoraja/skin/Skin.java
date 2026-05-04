package bms.player.beatoraja.skin;

import bms.player.beatoraja.MainController;
import bms.player.beatoraja.MainState;
import bms.player.beatoraja.Resolution;
import bms.player.beatoraja.ShaderManager;
import bms.player.beatoraja.SkinConfig.Offset;
import bms.player.beatoraja.skin.SkinObject.SkinOffset;
import bms.player.beatoraja.skin.property.BooleanProperty;

import bms.player.beatoraja.skin.property.TimerProperty;
import bms.player.beatoraja.skin.property.TimerPropertyFactory;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.IntIntMap;
import com.badlogic.gdx.utils.IntMap;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.logging.Logger;


/**
 * スキン
 *
 * @author exch
 */
public class Skin {

	public final SkinHeader header;
	/**
	 * 幅
	 */
	private final float width;
	/**
	 * 高さ
	 */
	private final float height;
	/**
	 * 元データからの幅比率
	 */
	private final float dw;
	/**
	 * 元データからの高さ比率
	 */
	private final float dh;

	/**
	 * 登録されているスキンオブジェクト
	 */
	private Array<SkinObject> objects = new Array<SkinObject>();
	private SkinObject[] objectarray = new SkinObject[0];
	/**
	 * 除外されているスキンオブジェクト
	 */
	private Array<SkinObject> removes = new Array<SkinObject>();
	/**
	 * 入力受付開始時間(ms)
	 */
	private int input;
	/**
	 * シーンの時間(ms)
	 */
	private int scene = 3600000 * 24;
	/**
	 * シーン以降準備開始からシーン移行までの時間(ms)
	 */
	private int fadeout;

	private IntIntMap option = new IntIntMap();

	private IntMap<Offset> offset = new IntMap<Offset>();

	private final IntMap<CustomEvent> customEvents = new IntMap<CustomEvent>();
	private final IntMap<CustomTimer> customTimers = new IntMap<CustomTimer>();

	/**
	 * デバッグ用
	 */
	public final Map<Class, long[]> tempmap;
	public final Map<Class, long[]> pcntmap;
	private final Map<Class, Queue<Long>> avemPrepare;
	private final Map<Class, Queue<Long>> avemDraw;
	public long pcntPrepare;
	public long pcntDraw;

	public Skin(SkinHeader header) {
		this.header = header;
		Resolution org = header.getSourceResolution();
		Resolution dst = header.getDestinationResolution();
		width = dst.width;
		height = dst.height;
		dw = ((float)dst.width) / org.width;
		dh = ((float)dst.height) / org.height;

		if(MainController.debug) {
			tempmap = new HashMap<>(32);
			pcntmap = new HashMap<>(32);
			avemPrepare = new HashMap<>(32);
			avemDraw = new HashMap<>(32);
		} else {
			tempmap = null;
			pcntmap = null;
			avemPrepare = null;
			avemDraw = null;
		}
	}

	public void add(SkinObject object) {
		objects.add(object);
	}

	public void setDestination(SkinObject object, long time, float x, float y, float w, float h, int acc, int a,
			int r, int g, int b, int blend, int filter, int angle, int center, int loop, int timer, int op1, int op2,
			int op3, int[] offset) {
		object.setDestination(time, x * dw, y * dh, w * dw, h * dh, acc, a, r, g, b, blend, filter, angle, center,
				loop, timer > 0 ? TimerPropertyFactory.getTimerProperty(timer) : null, op1, op2, op3, offset);
	}

	public void setDestination(SkinObject object, long time, float x, float y, float w, float h, int acc, int a,
			int r, int g, int b, int blend, int filter, int angle, int center, int loop, TimerProperty timer, int[] op) {
		object.setDestination(time, x * dw, y * dh, w * dw, h * dh, acc, a, r, g, b, blend, filter, angle, center,
				loop, timer, op);
	}

	public void setDestination(SkinObject object, long time, float x, float y, float w, float h, int acc, int a,
			int r, int g, int b, int blend, int filter, int angle, int center, int loop, TimerProperty timer, BooleanProperty draw) {
		object.setDestination(time, x * dw, y * dh, w * dw, h * dh, acc, a, r, g, b, blend, filter, angle, center,
				loop, timer, draw);
	}

	public void addNumber(SkinNumber number, long time, float x, float y, float w, float h, int acc, int a, int r,
			int g, int b, int blend, int filter, int angle, int center, int loop, TimerProperty timer, int op1, int op2, int op3, int offset) {
		number.setDestination(time, x * dw, y * dh, w * dw, h * dh, acc, a, r, g, b, blend, filter, angle, center,
				loop, timer, op1, op2, op3, offset);
		objects.add(number);
	}

	public SkinImage addImage(TextureRegion tr, long time, float x, float y, float w, float h, int acc, int a,
			int r, int g, int b, int blend, int filter, int angle, int center, int loop, TimerProperty timer, int op1, int op2,
			int op3, int offset) {
		SkinImage si = new SkinImage(tr);
		si.setDestination(time, x * dw, y * dh, w * dw, h * dh, acc, a, r, g, b, blend, filter, angle, center, loop,
				timer, op1, op2, op3, offset);
		objects.add(si);
		return si;
	}

	public void setMouseRect(SkinObject object, float x, float y, float w, float h) {
		object.setMouseRect(x * dw, y * dh, w * dw, h * dh);
	}

	public SkinObject[] getAllSkinObjects() {
		return objects.toArray(SkinObject.class);
	}

	public void removeSkinObject(SkinObject obj) {
		objects.removeValue(obj, true);
	}

	public void prepare(MainState state) {
		for(SkinObject obj : objects) {
			if(!obj.validate()) {
				removes.add(obj);
			} else {
				Array<BooleanProperty> bp = new Array<BooleanProperty>();
				for(BooleanProperty op : obj.getDrawCondition()) {
					if(op.isStatic(state)) {
						if(!op.get(state)) {
							removes.add(obj);
						}
					} else {
						bp.add(op);
					}
				}
				obj.setDrawCondition(bp.toArray(BooleanProperty.class));

				IntArray l = new IntArray();
				for(int op : obj.getOption()) {
					if(op > 0) {
						final int value = option.get(op, -1);
						if(value != 1) {
							removes.add(obj);
						}
					} else {
						final int value = option.get(-op, -1);
						if(value != 0) {
							removes.add(obj);
						}
					}
				}
				obj.setOption(l.toArray());
			}

		}
		Logger.getGlobal().info("描画されないことが確定しているSkinObject削除 : " + removes.size + " / " + objects.size);
		objects.removeAll(removes, true);
		objectarray = objects.toArray(SkinObject.class);
		option.clear();

		for(SkinObject obj : objects) {
			obj.load();
		}

		if(MainController.debug) {
			for (SkinObject obj : objects) {
				if (!tempmap.containsKey(obj.getClass())) {
					tempmap.put(obj.getClass(), new long[] { 1, 0, 0, 0, 0, 0, 0});
				} else {
					tempmap.get(obj.getClass())[0]++;
				}
			}
			tempmap.forEach((k,v)-> {
				pcntmap.put(k, Arrays.copyOf(v, 7));
				Queue<Long> q1 = new ArrayDeque<>(1010);
				Queue<Long> q2 = new ArrayDeque<>(1010);
				for (int i = 0; i < 1000; i++) {
					q1.add(0L);
					q2.add(0L);
				}
				avemDraw.put(k, q1);
				avemPrepare.put(k, q2);
			});

		}

		prepareduration = state.main.getConfig().getPrepareFramePerSecond() > 0 ? 1000000 / state.main.getConfig().getPrepareFramePerSecond() : 1;
		nextpreparetime = -1;
	}

	private SkinObjectRenderer renderer;

	private long nextpreparetime;
	private long prepareduration;

	public void drawAllObjects(SpriteBatch sprite, MainState state) {
		if(renderer == null) {
			SkinOffset offsetAll = getOffsetAll(state);
			Matrix4 transform = new Matrix4();
			if(offsetAll != null) {
				transform.setToTranslation(width * offsetAll.x / 100, height * offsetAll.y / 100, 0).scale((offsetAll.w + 100) / 100f, (offsetAll.h + 100) / 100f, 1);
			} else {
				transform.idt();
			}
			sprite.setTransformMatrix(transform);
			boolean isDefaultSkin = header != null && header.getPath() != null &&
				(header.getPath().toString().contains("skin/default/") ||
				 header.getPath().toString().contains("skin\\default\\"));
			renderer = new SkinObjectRenderer(sprite, isDefaultSkin);
			renderer.setViewport(0, 0, width, height);
		}

		final long microtime = state.timer.getNowMicroTime();

		if (MainController.debug) {
			if (nextpreparetime <= microtime) {
				tempmap.forEach((c,l) -> Arrays.fill(l, 1, 6, 0L));
				final long time = state.timer.getNowTime();
				var startPrepare = System.nanoTime();
				for (SkinObject obj : objectarray) {
					var objPrepare = System.nanoTime();
					try {
						obj.prepare(time, state);
						tempmap.get(obj.getClass())[1] += (System.nanoTime() - objPrepare);
					} catch (Throwable e) {
						Logger.getGlobal().severe("SkinObject prepare failed (debug mode): " + e.getMessage());
						e.printStackTrace();
					}
				}
				pcntPrepare = (System.nanoTime() - startPrepare) / 1000;
				nextpreparetime += ((microtime - nextpreparetime) / prepareduration + 1) * prepareduration;
			}

			var startDraw = System.nanoTime();
			for (SkinObject obj : objectarray) {
				if (obj.draw) {
					var objDraw = System.nanoTime();
					try {
						obj.draw(renderer);
						tempmap.get(obj.getClass())[4] += (System.nanoTime() - objDraw);
					} catch (Throwable e) {
						Logger.getGlobal().severe("SkinObject draw failed (debug mode): " + e.getMessage());
						e.printStackTrace();
					}
				}
			}
			tempmap.forEach((k, v) -> {
				avemPrepare.get(k).add(v[1]);
				pcntmap.get(k)[1] = v[1];
				pcntmap.get(k)[2] += v[1] - avemPrepare.get(k).poll();
				pcntmap.get(k)[3] = Math.max(pcntmap.get(k)[3], v[1]);
				avemDraw.get(k).add(v[4]);
				pcntmap.get(k)[4] = v[4];
				pcntmap.get(k)[5] += v[4] - avemDraw.get(k).poll();
				pcntmap.get(k)[6] = Math.max(pcntmap.get(k)[6], v[4]);
			});
			pcntDraw = (System.nanoTime() - startDraw) / 1000;

		} else {
			if (nextpreparetime <= microtime) {
				final long time = state.timer.getNowTime();
				for (SkinObject obj : objectarray) {
					try {
						obj.prepare(time, state);
					} catch (Throwable e) {
						Logger.getGlobal().severe("SkinObject prepare failed: " + e.getMessage());
						e.printStackTrace();
					}
				}

				nextpreparetime += ((microtime - nextpreparetime) / prepareduration + 1) * prepareduration;
			}

			for (SkinObject obj : objectarray) {
				if (obj.draw) {
					try {
						obj.draw(renderer);
					} catch (Throwable e) {
						Logger.getGlobal().severe("SkinObject draw failed: " + e.getMessage());
						e.printStackTrace();
					}
				}
			}
		}
	}

	public void mousePressed(MainState state, int button, int x, int y) {
		for (int i = objectarray.length - 1; i >= 0; i--) {
			final SkinObject obj = objectarray[i];
			if (obj.draw && obj.mousePressed(state, button, x, y)) {
				break;
			}
		}
	}

	public void mouseDragged(MainState state, int button, int x, int y) {
		for (int i = objectarray.length - 1; i >= 0; i--) {
			final SkinObject obj = objectarray[i];
			if (obj instanceof SkinSlider && obj.draw && obj.mousePressed(state, button, x, y)) {
				break;
			}
		}
	}

	public void dispose() {
		for (SkinObject obj : objects) {
			if(!obj.isDisposed()) {
				obj.dispose();
			}
		}
		for (SkinObject obj : removes) {
			if(!obj.isDisposed()) {
				obj.dispose();
			}
		}
	}

	public int getFadeout() {
		return fadeout;
	}

	public void setFadeout(int fadeout) {
		this.fadeout = fadeout;
	}

	public int getInput() {
		return input;
	}

	public void setInput(int input) {
		this.input = input;
	}

	public int getScene() {
		return scene;
	}

	public void setScene(int scene) {
		this.scene = scene;
	}

	public IntIntMap getOption() {
		return option;
	}

	public void setOption(IntIntMap option) {
		this.option = option;
	}

	public IntMap<Offset> getOffset() {
		return offset;
	}

	public void setOffset(IntMap<Offset> offset) {
		this.offset = offset;
	}

	/**
	 * 全てのオフセット設定を統合した SkinOffset を返す
	 * @param state MainState
	 * @return 統合されたオフセット（設定がない場合は null）
	 */
	private SkinObject.SkinOffset getOffsetAll(MainState state) {
		if (offset.isEmpty()) {
			return null;
		}

		SkinObject.SkinOffset result = new SkinObject.SkinOffset();
		result.x = 0;
		result.y = 0;
		result.w = 0;
		result.h = 0;
		result.r = 0;
		result.a = 0;

		for (IntMap.Entry<Offset> e : offset.entries()) {
			Offset off = e.value;
			if (off != null) {
				result.x += off.x;
				result.y += off.y;
				result.w += off.w;
				result.h += off.h;
				result.r += off.r;
				result.a += off.a;
			}
		}

		return result;
	}

	public float getWidth() {
		return width;
	}

	public float getHeight() {
		return height;
	}

	public double getScaleX() {
		return dw;
	}

	public double getScaleY() {
		return dh;
	}

	public static final class SkinObjectRenderer {

		private final SpriteBatch sprite;

		private final ShaderProgram[] shaders = new ShaderProgram[6];

		private int current;

		private int blend;

		private int type;

		/** 追踪当前 GL 激活的 blend 状态，避免不必要的 setBlendFunction 调用 */
		private int activeBlend = 0;

		public static final int TYPE_NORMAL = 0;
		public static final int TYPE_LINEAR = 1;
		public static final int TYPE_BILINEAR = 2;
		public static final int TYPE_FFMPEG = 3;
		public static final int TYPE_LAYER = 4;
		public static final int TYPE_DISTANCE_FIELD = 5;

		private final Color color = new Color(Color.WHITE);
		private boolean colorSet = false;

		private Color orgcolor;

		/** 仅对 default skin (skin/default/*) 启用 Nearest 过滤以降低 GPU 负载 */
		private final boolean useNearestFilter;

		/** 可视区域裁剪矩形，用于跳过长视口外的元素绘制 */
		private final Rectangle viewport = new Rectangle();

		/** ThreadLocal 存储当前 viewport，供 SkinObject.prepare() 读取用于裁剪 */
		private static final ThreadLocal<Rectangle> currentViewport = ThreadLocal.withInitial(() -> new Rectangle());

		public SkinObjectRenderer(SpriteBatch sprite, boolean useNearestFilter) {
			this.sprite = sprite;
			this.useNearestFilter = useNearestFilter;
			shaders[TYPE_BILINEAR] = ShaderManager.getShader("bilinear");
			shaders[TYPE_FFMPEG] = ShaderManager.getShader("ffmpeg");
			shaders[TYPE_LAYER] = ShaderManager.getShader("layer");
			shaders[TYPE_DISTANCE_FIELD] = ShaderManager.getShader("distance_field");

			sprite.setShader(shaders[current]);
			sprite.setColor(Color.WHITE);
			viewport.set(0, 0, 1920, 1080);
		}

		public Rectangle getViewport() {
			return viewport;
		}

		public void setViewport(float x, float y, float width, float height) {
			viewport.set(x, y, width, height);
			currentViewport.get().set(viewport);
		}

		public static Rectangle getCurrentViewport() {
			return currentViewport.get();
		}

		public void draw(BitmapFont font, String s, float x, float y, Color c) {
			for (TextureRegion region : font.getRegions()) {
				setFilter(region);
			}
			preDraw();
			font.setColor(c);
			font.draw(sprite, s, x, y);
			postDraw();
		}

		public void draw(BitmapFont font, GlyphLayout layout, float x, float y) {
			draw(font, layout, x, y, null);
		}

		public void draw(BitmapFont font, GlyphLayout layout, float x, float y, Consumer<ShaderProgram> shaderVariableSetter) {
			for (TextureRegion region : font.getRegions()) {
				setFilter(region);
			}
			preDraw(shaderVariableSetter);
			font.draw(sprite, layout, x, y);
			postDraw();
		}

		public void draw(Texture image, float x, float y, float w, float h) {
			setFilter(image);
			preDraw();
			sprite.draw(image, x, y, w, h);
			postDraw();
		}

		public void draw(TextureRegion image, float x, float y, float w, float h) {
			setFilter(image);
			preDraw();
			// x,yが*.5の際に(Windowsのみ)TextureRegionがずれるため、暫定対処
			sprite.draw(image,  x + 0.01f, y + 0.01f, w, h);
			postDraw();
		}

		public void draw(TextureRegion image, float x, float y, float w, float h, float cx, float cy, float angle) {
			setFilter(image);
			preDraw();
			// x,yが*.5の際に(Windowsのみ)TextureRegionがずれるため、暫定対処
			sprite.draw(image, x + 0.01f, y + 0.01f, cx * w, cy * h, w, h, 1, 1, angle);
			postDraw();
		}

		private void setFilter(TextureRegion image) {
			setFilter(image.getTexture());
		}

		private void setFilter(Texture image) {
			// 优化：仅在需要 Linear 过滤且尚未设置时才调用 setFilter
			// 跳过冗余的 bind() + glTexParameteri() GL 调用
			if(type == TYPE_LINEAR || type == TYPE_FFMPEG || type == TYPE_DISTANCE_FIELD) {
				if (image.getMinFilter() != TextureFilter.Linear || image.getMagFilter() != TextureFilter.Linear) {
					image.setFilter(TextureFilter.Linear, TextureFilter.Linear);
				}
			}
		}

		private void preDraw() {
			preDraw(null);
		}

		private void preDraw(Consumer<ShaderProgram> shaderVariableSetter) {
			if(shaders[current] != shaders[type]) {
				sprite.setShader(shaders[type]);
				current = type;
			}

			if (shaders[type] != null && shaderVariableSetter != null) {
				// シェーダの変数を変更する場合はバッチを切る
				// （shader.begin() - end() で囲うのは正しく動作しないため不可）
				sprite.flush();
				shaderVariableSetter.accept(shaders[type]);
			}

			// 优化：仅当 blend 状态真正变化时才调用 setBlendFunction
			if (blend != activeBlend) {
				switch (blend) {
					case 2:
						sprite.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
						break;
					case 3:
						// TODO 減算描画は難しいか？
						Gdx.gl.glBlendEquation(GL20.GL_FUNC_SUBTRACT);
						sprite.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
						Gdx.gl.glBlendEquation(GL20.GL_FUNC_ADD);
						break;
					case 4:
						// Multiplicative blending: result = dest_color * src_color
						// GL_ZERO and GL_SRC_COLOR are fully supported on OpenGL ES 2.0+
						sprite.setBlendFunction(GL20.GL_ZERO, GL20.GL_SRC_COLOR);
						break;
					case 9:
						// Inversion blending: result = (1 - dest_color) * src_color
						// GL_ONE_MINUS_DST_COLOR and GL_ZERO are fully supported on OpenGL ES 2.0+
						sprite.setBlendFunction(GL20.GL_ONE_MINUS_DST_COLOR, GL20.GL_ZERO);
						break;
					default:
						sprite.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
						break;
				}
				activeBlend = blend;
			}

			// Only apply color if it was explicitly set for this object
			// This prevents color leakage from previous objects (like white titlegradation textures)
			if(colorSet) {
				orgcolor = sprite.getColor();
				sprite.setColor(color);
			} else {
				orgcolor = null;
			}
		}

		private void postDraw() {
			if(orgcolor != null) {
				sprite.setColor(orgcolor);
			}

			// 优化：不再在 postDraw 中重置 blend 状态，改为在下次 preDraw 时按需设置
			// 这样连续使用相同 blend 模式的对象之间不会触发多余的 flush

			// Reset color state after each draw to prevent color leakage between objects
			colorSet = false;
			color.set(Color.WHITE);
		}

		public int getType() {
			return type;
		}

		public void setType(int type) {
			this.type = type;
		}

		public int getBlend() {
			return blend;
		}

		public void setBlend(int blend) {
			this.blend = blend;
		}

		public Color getColor() {
			return color;
		}

		public void setColor(Color color) {
			this.color.set(color);
			this.colorSet = true;
		}

		public void setColor(float r, float g, float b, float a) {
			this.color.set(r,g,b,a);
			this.colorSet = true;
		}
	}

	public void addCustomEvent(CustomEvent event) {
		customEvents.put(event.getId(), event);
	}

	public void executeCustomEvent(MainState state, int id, int arg1, int arg2) {
		if (customEvents.containsKey(id)) {
			customEvents.get(id).execute(state, arg1, arg2);
		}
	}

	public void addCustomTimer(CustomTimer timer) {
		customTimers.put(timer.getId(), timer);
	}

	/**
	 * カスタムタイマーの値を設定する。
	 * 能動的・受動的にかかわらず、取得の度にタイマーの値を再計算しないため、
	 * 同一フレームでの値は一意であることが保証される。
	 * @param id カスタムタイマーID
	 * @return タイマーの値 (micro sec)
	 */
	public long getMicroCustomTimer(int id) {
		if (customTimers.containsKey(id)) {
			return customTimers.get(id).getMicroTimer();
		} else {
			return Long.MIN_VALUE;
		}
	}

	/**
	 * (受動的な)カスタムタイマーの値を設定する。
	 * タイマーが存在しない場合は追加する。
	 * @param id カスタムタイマーID
	 * @param time タイマーの値 (micro sec)
	 */
	public void setMicroCustomTimer(int id, long time) {
		if (customTimers.containsKey(id)) {
			customTimers.get(id).setMicroTimer(time);
		} else {
			CustomTimer timer = new CustomTimer(id, null);
			timer.setMicroTimer(time);
			customTimers.put(id, timer);
		}
	}

	/**
	 * ユーザー定義のオブジェクトを1フレームに1回ずつ更新する。
	 * 更新順: タイマー -> イベント
	 * それぞれ ID が小さい順
	 * @param state MainState
	 */
	public void updateCustomObjects(MainState state) {
		for (IntMap.Entry<CustomTimer> timer : customTimers) {
			timer.value.update(state);
		}
		for (IntMap.Entry<CustomEvent> event : customEvents) {
			event.value.update(state);
		}
	}
}
