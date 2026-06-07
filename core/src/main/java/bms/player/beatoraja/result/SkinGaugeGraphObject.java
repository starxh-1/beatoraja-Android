package bms.player.beatoraja.result;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.*;

import bms.player.beatoraja.*;
import bms.player.beatoraja.play.GrooveGauge.Gauge;
import bms.player.beatoraja.skin.Skin.SkinObjectRenderer;
import bms.player.beatoraja.skin.SkinObject;

/**
 * ゲージ遷移描画オブジェクト
 *
 * @author exch
 */
public class SkinGaugeGraphObject extends SkinObject {

	/**
	 * 背景テクスチャ
	 */
	private TextureRegion backtex;
	/**
	 * グラフテクスチャ
	 */
	private TextureRegion shapetex;
	/**
	 * ゲージ描画を完了するまでの時間(ms)
	 */
	private int delay = 1500;
	/**
	 * グラフ線の太さ
	 */
	private int lineWidth = 2;

	/**
	 * ボーダー下背景色
	 */
	private final Color[] graphcolor = new Color[6];
	/**
	 * ボーダー下グラフ色
	 */
	private final Color[] graphline = new Color[6];
	/**
	 * ボーダー上背景色
	 */
	private final Color[] borderline = new Color[6];
	/**
	 * ボーダー上グラフ色
	 */
	private final Color[] bordercolor = new Color[6];

	private final int[] typetable = {0,1,2,3,4,5,3,4,5,3};

	private int currentType = -1;
	private int color;
	private FloatArray gaugehistory;
	private IntArray section;
	private Gauge gg;

	private float render;
	private boolean redraw;

	/**
	 * テクスチャ再構築がバックグラウンドスレッドで進行中の間は true。
	 * 同時に複数の再構築を起動しないためのガード。
	 */
	private volatile boolean rebuildPending = false;
	/**
	 * dispose 後に遅れて完了したワーカーが GL テクスチャを再作成しないためのガード。
	 */
	private volatile boolean disposed = false;

	public SkinGaugeGraphObject() {
		this(new Color[][]{{Color.valueOf("ff0000"),Color.valueOf("440000"),Color.valueOf("ff00ff"),Color.valueOf("440044")},
			{Color.valueOf("ff0000"),Color.valueOf("440000"),Color.valueOf("00ffff"),Color.valueOf("004444")},
			{Color.valueOf("ff0000"),Color.valueOf("440000"),Color.valueOf("00ff00"),Color.valueOf("004400")},
			{Color.valueOf("ff0000"),Color.valueOf("440000")},
			{Color.valueOf("ffff00"),Color.valueOf("444400")},
			{Color.valueOf("cccccc"),Color.valueOf("444444")}
			});
	}

	public SkinGaugeGraphObject(Color[][] colors) {
		for(int i = 0;i < 6;i++) {
			if(colors.length > i) {
				borderline[i] = colors[i].length > 0 && colors[i][0] != null ? colors[i][0] : Color.valueOf("000000");
				bordercolor[i] = colors[i].length > 1 && colors[i][1] != null ? colors[i][1] : Color.valueOf("000000");
				graphline[i] = colors[i].length > 2 && colors[i][2] != null ? colors[i][2] : Color.valueOf("000000");
				graphcolor[i] = colors[i].length > 3 && colors[i][3] != null ? colors[i][3] : Color.valueOf("000000");
			} else {
				graphline[i] = graphcolor[i] = borderline[i] = bordercolor[i] = Color.valueOf("000000");
			}
		}
	}

	public SkinGaugeGraphObject(String assistClearBGColor, String assistAndEasyFailBGColor, String grooveFailBGColor, String grooveClearAndHardBGColor, String exHardBGColor, String hazardBGColor,
	String assistClearLineColor, String assistAndEasyFailLineColor, String grooveFailLineColor, String grooveClearAndHardLineColor, String exHardLineColor, String hazardLineColor,
	String borderlineColor, String borderColor) {
		graphcolor[0] = Color.valueOf(assistClearBGColor);
		graphcolor[1] = Color.valueOf(assistAndEasyFailBGColor);
		graphcolor[2] = Color.valueOf(grooveFailBGColor);
		bordercolor[3] = Color.valueOf(grooveClearAndHardBGColor);
		bordercolor[4] = Color.valueOf(exHardBGColor);
		bordercolor[5] = Color.valueOf(hazardBGColor);
		graphline[0] = Color.valueOf(assistClearLineColor);
		graphline[1] = Color.valueOf(assistAndEasyFailLineColor);
		graphline[2] = Color.valueOf(grooveFailLineColor);
		borderline[3] = Color.valueOf(grooveClearAndHardLineColor);
		borderline[4] = Color.valueOf(exHardLineColor);
		borderline[5] = Color.valueOf(hazardLineColor);

		for(int i = 0;i < 3;i++) {
			borderline[i] = Color.valueOf(borderlineColor);
			bordercolor[i] = Color.valueOf(borderColor);
		}
		for(int i = 3;i < 6;i++) {
			graphline[i] = borderline[i];
			graphcolor[i] = bordercolor[i];
		}
	}

	public void prepare(long time, MainState state) {
		render = time >= delay ? 1.0f : (float) time / delay;

		// FIX: super.prepare() を先に呼んで region を確定させる。
		// 旧実装では rebuild チェックの後に呼んでいたため、初フレームで
		// region.width/height が 0 のまま scheduleRebuild() が起動され、
		// 1×1 のテクスチャが作られていた（次フレームで再構築され無駄が発生）。
		super.prepare(time, state);

		final PlayerResource resource = state.resource;
		int type = resource.getGrooveGauge().getType();
		if(state instanceof AbstractResult) {
			type = ((AbstractResult) state).gaugeType;
		}

		boolean needRebuild = redraw;
		if(currentType != type) {
			redraw = true;
			currentType = type;
			gaugehistory = resource.getGauge()[currentType];
			section = new IntArray();
			if (state instanceof CourseResult) {
				gaugehistory = new FloatArray();
				for (FloatArray[] l : resource.getCourseGauge()) {
					gaugehistory.addAll(l[currentType]);
					section.add((section.size > 0 ? section.get(section.size - 1) : 0) + l[currentType].size);
				}
			}
			gg = resource.getGrooveGauge().getGauge(currentType);
			needRebuild = true;
		}
		// Check if dimensions have changed - need to rebuild in that case too
		final int rw = Math.max(1, (int) region.width);
		final int rh = Math.max(1, (int) region.height);
		if(shapetex != null && (shapetex.getTexture().getWidth() != rw || shapetex.getTexture().getHeight() != rh)) {
			needRebuild = true;
		}

		// FIX: テクスチャ再構築をバックグラウンドスレッドに逃がす
		// 旧実装は GL Thread 上で new Pixmap + new Texture + O(song_length) の
		// fillRectangle ループを実行しており、結果画面で初回描画が長時間止まっていた。
		// Pixmap のラスタライズは CPU バウンドなのでワーカで実行し、
		// Texture 上伝（GL 呼び出し）のみを Gdx.app.postRunnable で GL Thread に戻す。
		if(needRebuild && !rebuildPending) {
			scheduleRebuild();
		}
	}

	/**
	 * 現在の gaugehistory / dimension / type を固定し、バックグラウンドで
	 * Pixmap を構築 → GL Thread で Texture にアップロードする。
	 */
	private void scheduleRebuild() {
		final int capturedType = currentType;
		final FloatArray capturedHistory = gaugehistory;
		final IntArray capturedSection = section;
		final Gauge capturedGauge = gg;
		final int w = Math.max(1, (int) region.width);
		final int h = Math.max(1, (int) region.height);
		final int c = typetable[capturedType];
		rebuildPending = true;
		new Thread(() -> {
			Pixmap back = null;
			Pixmap shape = null;
			try {
				back = buildBackPixmap(w, h, capturedGauge, c);
				shape = buildShapePixmap(w, h, capturedHistory, capturedSection, capturedGauge, c);
			} catch (Throwable t) {
				t.printStackTrace();
				if (back != null) {
					back.dispose();
				}
				if (shape != null) {
					shape.dispose();
				}
				Gdx.app.postRunnable(() -> {
					redraw = true;
					rebuildPending = false;
				});
				return;
			}
			final Pixmap finalBack = back;
			final Pixmap finalShape = shape;
			Gdx.app.postRunnable(() -> {
				try {
					// Result を離れた、gauge type が変わった、または skin の寸法が
					// 変わった場合、このワーカーの結果は既に古いので破棄する。
					if (disposed || capturedType != currentType
							|| w != Math.max(1, (int) region.width)
							|| h != Math.max(1, (int) region.height)) {
						finalBack.dispose();
						finalShape.dispose();
						redraw = !disposed;
						return;
					}
					// 旧テクスチャを破棄
					if (backtex != null) {
						backtex.getTexture().dispose();
						backtex = null;
					}
					if (shapetex != null) {
						shapetex.getTexture().dispose();
						shapetex = null;
					}
					// GL Thread 上で Texture を生成（唯一の GL 呼び出し）
					if (finalBack != null) {
						backtex = new TextureRegion(new Texture(finalBack));
						finalBack.dispose();
					}
					if (finalShape != null) {
						shapetex = new TextureRegion(new Texture(finalShape));
						finalShape.dispose();
					}
					redraw = false;
				} finally {
					rebuildPending = false;
				}
			});
		}, "GaugeGraphRebuildThread").start();
	}

	/**
	 * 背景 Pixmap を生成。バックグラウンドスレッドから呼ばれる。
	 */
	private Pixmap buildBackPixmap(int w, int h, Gauge gauge, int c) {
		Pixmap shape = new Pixmap(w, h, Pixmap.Format.RGBA8888);
		final float border = gauge.getProperty().border;
		final float max = gauge.getProperty().max;
		shape.setColor(graphcolor[c]);
		shape.fill();
		shape.setColor(bordercolor[c]);
		fillRectangle(shape, 0, (int) (h * border / max), w,
				(int) (h * (max - border) / max));
		return shape;
	}

	/**
	 * ゲージ推移線の Pixmap を生成。バックグラウンドスレッドから呼ばれる。
	 */
	private Pixmap buildShapePixmap(int w, int h, FloatArray history, IntArray sectionArr, Gauge gauge, int c) {
		Pixmap shape = new Pixmap(w, h, Pixmap.Format.RGBA8888);
		final float border = gauge.getProperty().border;
		final float max = gauge.getProperty().max;
		final int size = history.size;
		Float f1 = null;
		float lastGauge = -1;
		int lastX = -1;
		int lastY = -1;

		for (int i = 0; i < size; i++) {
			if (sectionArr.contains(i)) {
				shape.setColor(Color.valueOf("ffffff"));
				shape.drawLine((int) (w * (i - 1) / size), 0,
						(int) (w * (i - 1) / size), h);
			}
			Float f2 = history.get(i);
			if (f1 != null) {
				final int x1 = (int) (w * (i - 1) / size);
				final int y1 = (int) ((f1 / max) * (h - lineWidth));
				final int x2 = (int) (w * i / size);
				final int y2 = (int) ((f2 / max) * (h - lineWidth));
				final int yb = (int) ((border / max) * (h - lineWidth));
				lastGauge = f2;
				lastX = x2;
				lastY = y2;
				if (f1 < border) {
					if (f2 < border) {
						shape.setColor(graphline[c]);
						fillRectangle(shape, x1, Math.min(y1, y2), lineWidth, Math.abs(y2 - y1) + lineWidth);
						fillRectangle(shape, x1, y2, x2 - x1, lineWidth);
					} else {
						shape.setColor(graphline[c]);
						fillRectangle(shape, x1, y1, lineWidth, yb - y1 + lineWidth);
						shape.setColor(borderline[c]);
						fillRectangle(shape, x1, yb, x2 - x1, y2 - yb + lineWidth);
					}
				} else {
					if (f2 >= border) {
						shape.setColor(borderline[c]);
						fillRectangle(shape, x1, Math.min(y1, y2), lineWidth, Math.abs(y2 - y1) + lineWidth);
						fillRectangle(shape, x1, y2, x2 - x1, lineWidth);
					} else {
						shape.setColor(borderline[c]);
						fillRectangle(shape, x1, yb, lineWidth, y1 - yb + lineWidth);
						shape.setColor(graphline[c]);
						// Gauge が border を上から下へ跨ぐ場合、旧実装は
						// y2 - yb を高さとして渡し、負の height を native Pixmap に
						// 入れていた。端末によってはここで長時間停止する。
						fillRectangle(shape, x1, Math.min(y2, yb), x2 - x1,
								getGaugeSpan(y2, yb, lineWidth));
					}
				}
			}
			f1 = f2;
		}

		if (lastGauge != -1) {
			shape.setColor(lastGauge < border ? graphline[c] : borderline[c]);
			fillRectangle(shape, lastX, lastY, w - lastX, lineWidth);
		}
		return shape;
	}

	static int getGaugeSpan(int from, int to, int thickness) {
		return Math.abs(to - from) + Math.max(1, thickness);
	}

	/**
	 * Native Pixmap に負の幅・高さを渡さない。
	 * 0 幅/高さの区間は同じピクセル列に複数サンプルが収まる場合に発生するため、
	 * 描画せず次の区間に任せる。
	 */
	private static void fillRectangle(Pixmap pixmap, int x, int y, int width, int height) {
		if (width < 0) {
			x += width;
			width = -width;
		}
		if (height < 0) {
			y += height;
			height = -height;
		}
		if (width == 0 || height == 0) {
			return;
		}
		pixmap.fillRectangle(x, y, width, height);
	}

	@Override
	public void draw(SkinObjectRenderer sprite) {
		// 非同期再構築中はテクスチャがまだないためスキップ。
		// 旧実装は「prepare() で必ずテクスチャ完成 → draw()」だったので
		// ヌルになるタイミングは初フレームを含め一瞬しかない。
		if (backtex == null || shapetex == null) {
			return;
		}
		draw(sprite, backtex, region.x, region.y + region.height, region.width, -region.height);
		// setRegionにfloatを渡すと表示がおかしくなる
		shapetex.setRegion(0, 0, (int)(region.width * render), (int)region.height);
		draw(sprite, shapetex, region.x, region.y + region.height, (int)(region.width * render), -region.height);
	}

	public int getDelay() {
		return delay;
	}

	public void setDelay(int delay) {
		this.delay = delay;
	}

	public int getLineWidth() {
		return lineWidth;
	}

	public void setLineWidth(int lineWidth) {
		this.lineWidth = lineWidth;
	}

	@Override
	public void dispose() {
		disposed = true;
		redraw = false;
		if (shapetex != null) {
			shapetex.getTexture().dispose();
			shapetex = null;
		}
		if (backtex != null) {
			backtex.getTexture().dispose();
			backtex = null;
		}
	}
}
