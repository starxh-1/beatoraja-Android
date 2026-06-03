package bms.player.beatoraja.skin;

import java.util.Optional;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.Pixmap.Blending;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.*;

import bms.player.beatoraja.MainState;
import bms.player.beatoraja.PlayerResource;
import bms.player.beatoraja.play.BMSPlayer;
import bms.player.beatoraja.result.MusicResult;
import bms.player.beatoraja.result.AbstractResult.TimingDistribution;
import bms.player.beatoraja.skin.Skin.SkinObjectRenderer;

/**
 * 判定タイミング分布のグラフ
 *
 * @author keh
 */
public class SkinTimingDistributionGraph extends SkinObject {

	private TextureRegion tex = null;
	private Pixmap shape = null;

	/** 非同期テクスチャ構築中フラグ */
	private volatile boolean rebuildPending = false;

	private final int gx;
	private final int c;
	private final boolean drawAverage;
	private final boolean drawDev;
	private int max = 10;
	private Color[] JColor;
	private Color graphColor;
	private Color averageColor;
	private Color devColor;

	private MusicResult state;

	public SkinTimingDistributionGraph(int width, int lineWidth,
			String graphColor, String averageColor, String devColor, String PGColor, String GRColor, String GDColor, String BDColor,
			String PRColor,
			int drawAverage, int drawDev) {
		int w = 1 < width ? width : 1;
		int lw = MathUtils.clamp(lineWidth, 1, width);
		this.gx = w / lw;
		this.c = gx / 2;
		this.graphColor = Color.valueOf(SkinTimingVisualizer.colorStringValidation(graphColor));
		this.averageColor = Color.valueOf(SkinTimingVisualizer.colorStringValidation(averageColor));
		this.devColor = Color.valueOf(SkinTimingVisualizer.colorStringValidation(devColor));
		JColor = new Color[] {
				Color.valueOf(SkinTimingVisualizer.colorStringValidation(PGColor)),
				Color.valueOf(SkinTimingVisualizer.colorStringValidation(GRColor)),
				Color.valueOf(SkinTimingVisualizer.colorStringValidation(GDColor)),
				Color.valueOf(SkinTimingVisualizer.colorStringValidation(BDColor)),
				Color.valueOf(SkinTimingVisualizer.colorStringValidation(PRColor))
		};
		this.drawAverage = (drawAverage == 1);
		this.drawDev = (drawDev == 1);
	}

	public void prepare(long time, MainState state) {
		if(!(state instanceof MusicResult)) {
			draw = false;
			return;
		}
		this.state = (MusicResult) state;
		super.prepare(time, state);
		
	}
	
	public void draw(SkinObjectRenderer sprite) {
		// FIX: Texture生成を非同期化。
		// 旧実装は draw() 内で Pixmap 構築 + Texture 生成を同期実行しており、
		// GL Thread をブロックする原因となっていた。
		if (tex == null && !rebuildPending) {
			scheduleAsyncBuild();
		}

		if (tex != null) {
			draw(sprite, tex);
		}
		// else: 非同期構築中はスキップ
	}

	/**
	 * バックグラウンドスレッドで Pixmap を構築し、GL Thread で Texture にアップロードする。
	 */
	private void scheduleAsyncBuild() {
		final TimingDistribution td = state.getTimingDistribution();
		final int[] dist = td.getTimingDistribution();
		final int center = td.getArrayCenter();
		final int[][] judgeArea = SkinTimingVisualizer.getJudgeArea(state.resource);

		// max を計算（GL Thread で安全）
		for (int d : dist) {
			if (max < d) {
				max = (d / 10) * 10 + 10;
			}
		}
		final int capturedMax = max;
		final int capturedGx = gx;
		final int capturedC = c;
		final Color[] capturedJColor = JColor;
		final Color capturedGraphColor = graphColor;
		final Color capturedAverageColor = averageColor;
		final Color capturedDevColor = devColor;
		final boolean capturedDrawAverage = drawAverage;
		final boolean capturedDrawDev = drawDev;
		final float tdAverage = td.getAverage();
		final float tdStdDev = td.getStdDev();

		rebuildPending = true;
		new Thread(() -> {
			Pixmap pixmap = null;
			try {
				pixmap = new Pixmap(capturedGx, capturedMax, Pixmap.Format.RGBA8888);
				//グラフエリア描画
				pixmap.setColor(capturedJColor[0]);
				pixmap.fillRectangle(capturedC, 0, 1, capturedMax);
				int beforex1 = capturedC;
				int beforex2 = capturedC + 1;
				for (int i = 0; i < capturedJColor.length; i++) {
					pixmap.setColor(capturedJColor[i]);
					int x1 = capturedC + MathUtils.clamp(judgeArea[i][0], -capturedC, capturedC);
					int x2 = capturedC + MathUtils.clamp(judgeArea[i][1], -capturedC, capturedC) + 1;

					if (beforex1 > x1) {
						pixmap.fillRectangle(x1, 0, Math.abs(x1 - beforex1), capturedMax);
						beforex1 = x1;
					}
					if (x2 > beforex2) {
						pixmap.fillRectangle(beforex2, 0, Math.abs(x2 - beforex2), capturedMax);
						beforex2 = x2;
					}
				}

				pixmap.setColor(0f, 0f, 0f, 0.25f);
				for(int x = capturedC % 10; x < capturedC * 2 + 1; x += 10) {
					pixmap.drawLine(x, 0, x, 1);
				}

				if (capturedDrawAverage && tdAverage != Float.MAX_VALUE) {
					int avg = Math.round(tdAverage);
					pixmap.setColor(capturedAverageColor);
					pixmap.drawLine(capturedC + avg, 0, capturedC + avg, capturedMax);
				}

				if (capturedDrawDev && tdStdDev != -1.0f) {
					int avg = Math.round(tdAverage);
					int dev = Math.round(tdStdDev);
					pixmap.setColor(capturedDevColor);
					pixmap.drawLine(capturedC + avg + dev, 0, capturedC + avg + dev, capturedMax);
					pixmap.drawLine(capturedC + avg - dev, 0, capturedC + avg - dev, capturedMax);
				}

				pixmap.setColor(capturedGraphColor);
				for (int i = -capturedC; i < capturedGx - capturedC; i++) {
					if (-center < i && i < center) {
						pixmap.fillRectangle(capturedC + i, capturedMax - dist[center + i], 1, dist[center + i]);
					}
				}
			} catch (Throwable t) {
				t.printStackTrace();
				if (pixmap != null) pixmap.dispose();
				Gdx.app.postRunnable(() -> rebuildPending = false);
				return;
			}

			final Pixmap finalPixmap = pixmap;
			Gdx.app.postRunnable(() -> {
				try {
					tex = new TextureRegion(new Texture(finalPixmap));
					finalPixmap.dispose();
				} finally {
					rebuildPending = false;
				}
			});
		}, "TimingDistGraphRebuildThread").start();
	}

	@Override
	public void dispose() {
		Optional.ofNullable(tex).ifPresent(t -> t.getTexture().dispose());
		Optional.ofNullable(shape).ifPresent(Pixmap::dispose);
	}

}
