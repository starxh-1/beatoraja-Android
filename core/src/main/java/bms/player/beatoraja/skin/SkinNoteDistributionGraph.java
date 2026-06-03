package bms.player.beatoraja.skin;

import bms.model.*;
import bms.player.beatoraja.MainState;
import bms.player.beatoraja.play.BMSPlayer;
import bms.player.beatoraja.skin.Skin.SkinObjectRenderer;
import bms.player.beatoraja.song.SongData;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Rectangle;

/**
 * ノーツ分布を表示するグラフ
 * 
 * @author exch
 */
public final class SkinNoteDistributionGraph extends SkinObject {

	private MainState state;

	// TODO 各Textureを1枚にまとめてblBindTextureの回数を削減する
	private TextureRegion backtex;
	private TextureRegion shapetex;
	private TextureRegion cursortex;
	
	private Pixmap back = null;
	private Pixmap shape = null;
	private Pixmap cursor = null;

	private BMSModel model;
	private SongData current;
	private int[][] data = new int[0][0];

	private static final Color[][] JGRAPH = {
			{ Color.valueOf("44ff44"), Color.valueOf("228822"), Color.valueOf("ff4444"), Color.valueOf("4444ff"), Color.valueOf("222288"), Color.valueOf("cccccc"),
					Color.valueOf("880000") },
			{ Color.valueOf("555555"), Color.valueOf("0088ff"), Color.valueOf("00ff88"), Color.valueOf("ffff00"),
					Color.valueOf("ff8800"), Color.valueOf("ff0000") },
			{ Color.valueOf("555555"), Color.valueOf("44ff44"), Color.valueOf("0088ff"), Color.valueOf("0066cc"),
					Color.valueOf("004488"), Color.valueOf("002244"), Color.valueOf("ff8800"), Color.valueOf("cc6600"),
					Color.valueOf("884400"), Color.valueOf("442200") } };

	private static final Color[][] pmsGraphColor = {
					{ Color.valueOf("44ff44"), Color.valueOf("228822"), Color.valueOf("ff4444"), Color.valueOf("4444ff"), Color.valueOf("222288"), Color.valueOf("cccccc"),
							Color.valueOf("880000") },
					{ Color.valueOf("555555"), Color.valueOf("ff5eb0"), Color.valueOf("ffbe32"), Color.valueOf("dc463c"),
							Color.valueOf("6cc6ff"), Color.valueOf("6cc6ff") },
					{ Color.valueOf("555555"), Color.valueOf("ff5eb0"), Color.valueOf("0088ff"), Color.valueOf("0066cc"),
							Color.valueOf("004488"), Color.valueOf("002244"), Color.valueOf("ff8800"), Color.valueOf("cc6600"),
							Color.valueOf("884400"), Color.valueOf("442200") } };

	private Pixmap[] chips;

	private int max = 20;

	private int type;

	public static final int TYPE_NORMAL = 0;
	public static final int TYPE_JUDGE = 1;
	public static final int TYPE_EARLYLATE = 2;
	
	private static final int[] DATA_LENGTH = {7, 6, 10};

	private boolean isBackTexOff = false;
	private int delay = 500;
	private boolean isOrderReverse = false;
	private boolean isNoGap = false;
	private boolean isNoGapX = false;

	/*
	 * 処理済みノート数 プレイ時は処理済みノート数に変化があった時だけ更新する
	 */
	private int pastNotes = 0;
	private long notesLastUpdateTime;
	private long cursorLastUpdateTime;
	
	private int starttime;
	private int endtime;
	private float freq;
	private float render;

	private static final Color TRANSPARENT_COLOR = Color.valueOf("00000000");

	/**
	 * 非同期テクスチャ再構築中フラグ。
	 * バックグラウンドスレッドで Pixmap 構築が進行中の間は true。
	 */
	private volatile boolean rebuildPending = false;

	public SkinNoteDistributionGraph() {
		this(TYPE_NORMAL, 500, 0, 0, 0, 0);
	}

	public SkinNoteDistributionGraph(int type, int delay, int backTexOff, int orderReverse, int noGap, int noGapX) {
		this(null, type, delay, backTexOff, orderReverse, noGap, noGapX);
	}
	
	public SkinNoteDistributionGraph(Pixmap[] chips, int type, int delay, int backTexOff, int orderReverse, int noGap, int noGapX) {
		this.chips = chips;
		this.type = type;
		this.isBackTexOff = backTexOff == 1;
		this.delay = delay;
		this.isOrderReverse = orderReverse == 1;
		this.isNoGap = noGap == 1;
		this.isNoGapX = noGapX == 1;
		pastNotes = 0;
	}
	
	public void prepare(long time, MainState state) {
		prepare(time, state, null, -1, -1, -1);
	}

	public void prepare(long time, MainState state, Rectangle r, int starttime, int endtime, float freq) {
		super.prepare(time, state);			
		if(r != null) {
			region.set(r);
			draw = true;
		}
		this.state = state;
		this.starttime = starttime;
		this.endtime = endtime;
		this.freq = freq;
		render = time >= delay ? 1.0f : (float) time / delay;
	}

	public void draw(SkinObjectRenderer sprite) {

		final SongData song = state.resource.getSongdata();
		final BMSModel model = song != null ? song.getBMSModel() : null;

		// TODO スキン定義側で分岐できないか？
		if(chips == null) {
			Color[] graphcolor = type != TYPE_NORMAL && model != null && model.getMode() == Mode.POPN_9K  ?
					pmsGraphColor[type] : JGRAPH[type];
			chips = new Pixmap[graphcolor.length];
			for(int i = 0;i < graphcolor.length;i++) {
				chips[i] = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
				chips[i].drawPixel(0, 0, Color.toIntBits(255, (int)(graphcolor[i].b * 255), (int)(graphcolor[i].g * 255), (int)(graphcolor[i].r * 255)));
			}
		}

		// FIX: テクスチャ初期化を非同期化。
		// 旧実装は draw() 内で updateGraph()→updateTexture(true) を同期実行し、
		// 3つの Pixmap(w×h) + 2つの Texture + 大量 drawPixmap を GL Thread で行っていた。
		// これにより GL Thread が数百ms〜数秒ブロックされ、SkinGaugeGraphObject の
		// postRunnable も遅延して result 画面の gauge 表示が30秒遅れる原因となっていた。
		// Pixmap ラスタライズは CPU バウンドなのでワーカスレッドで実行し、
		// Texture 上伝（GL 呼び出し）のみを Gdx.app.postRunnable で GL Thread に戻す。
		if(shapetex == null || song != current || (this.model == null && model != null)) {
			current = song;
			this.model = model;
			if(type == TYPE_NORMAL && song != null && song.getInformation() != null) {
				updateGraphData(song.getInformation().getDistributionValues());
			} else {
				updateGraphData();
			}
			if (!rebuildPending) {
				scheduleAsyncRebuild();
			}
		}

		// BMSPlayer のみ同期パス: リアルタイム判定更新
		// 初期非同期ビルド完了後のみ実行（rebuildPending == false でガード）
		if(model != null && state instanceof BMSPlayer && !rebuildPending && backtex != null && shapetex != null) {
			if(System.currentTimeMillis() > notesLastUpdateTime + 750) {
				if(type != TYPE_NORMAL && pastNotes != ((BMSPlayer)state).getPastNotes()) {
					pastNotes = ((BMSPlayer)state).getPastNotes();
					updateData();
					updateTexture(false);
				}
				notesLastUpdateTime = System.currentTimeMillis();
			}
			if(System.currentTimeMillis() > cursorLastUpdateTime + 50) {
				final int oldw = cursor != null ? cursor.getWidth() : 0;
				final int oldh = cursor != null ? cursor.getHeight() : 0;
				final int w = data.length * 5;
				final int h = max * 5;
				cursor.setColor(TRANSPARENT_COLOR);
				cursor.fill();
				// スタートカーソル描画
				if (starttime >= 0) {
					int dx = (int) (starttime * w / (data.length * 1000));
					cursor.setColor(Color.toIntBits(255, 128, 255, 128));
					cursor.fillRectangle(dx, 0, 3, h);
				}
				// エンドカーソル描画
				if (endtime >= 0) {
					int dx = (int) (endtime * w / (data.length * 1000));
					cursor.setColor(Color.toIntBits(255, 128, 128, 255));
					cursor.fillRectangle(dx, 0, 3, h);
				}
				// 現在カーソル描画
				if (state instanceof BMSPlayer && state.timer.isTimerOn(SkinProperty.TIMER_PLAY)) {
					float currenttime = state.timer.getNowTime(SkinProperty.TIMER_PLAY);
					if (freq > 0) {
						currenttime *= freq;
					}
					int dx = (int) (currenttime * w / (data.length * 1000));
					cursor.setColor(Color.toIntBits(255, 255, 255, 255));
					cursor.fillRectangle(dx, 0, 3, h);
				}

				if(cursortex == null) {
					cursortex = new TextureRegion(new Texture(cursor));
				} else if(oldw != w || oldh != h) {
					cursortex.getTexture().dispose();
					cursortex = new TextureRegion(new Texture(cursor));
				} else {
					cursortex.getTexture().draw(cursor, 0, 0);
				}
				cursorLastUpdateTime = System.currentTimeMillis();
			}
			draw(sprite, backtex, region.x, region.y + region.height, region.width, -region.height);
			shapetex.setRegionWidth((int) (shapetex.getTexture().getWidth() * render));
			draw(sprite, shapetex, region.x, region.y + region.height, region.width * render, -region.height);
			draw(sprite, cursortex, region.x, region.y + region.height, region.width, -region.height);
		} else if(backtex != null && shapetex != null) {
			// 非同期ビルド完了後: テクスチャ描画
			draw(sprite, backtex, region.x, region.y + region.height, region.width, -region.height);
			shapetex.setRegionWidth((int) (shapetex.getTexture().getWidth() * render));
			draw(sprite, shapetex, region.x, region.y + region.height, region.width * render, -region.height);
		}
		// else: 非同期再構築中はテクスチャがまだないためスキップ

	}
	
	public void draw(SkinObjectRenderer sprite, long time, MainState state, Rectangle r, int starttime, int endtime, float freq) {
		prepare(time, state, r, starttime, endtime, freq);
		if(draw) {
			draw(sprite);
		}
	}

	/**
	 * データのみ更新（テクスチャ構築はしない）。
	 * updateGraphData() の後、scheduleAsyncRebuild() で非同期にテクスチャを構築する。
	 */
	private void updateGraphData(int[][] distribution) {
		data = distribution;
		max = 20;
		for(int i = 0;i < distribution.length;i++) {
			int count = 0;
			for(int j = 0;j < distribution[0].length;j++) {
				count += distribution[i][j];
			}
			if (max < count) {
				max = Math.min((count / 10) * 10 + 10, 100);
			}
		}
	}

	/**
	 * データのみ更新（テクスチャ構築はしない）。
	 */
	private void updateGraphData() {
		if (model == null) {
			data = new int[0][DATA_LENGTH[type]];
		} else {
			data = new int[model.getLastTime() / 1000 + 1][DATA_LENGTH[type]];
			updateData();
		}
	}

	/**
	 * バックグラウンドスレッドで Pixmap を構築し、GL Thread で Texture にアップロードする。
	 * SkinGaugeGraphObject と同じ非同期パターン。
	 */
	private void scheduleAsyncRebuild() {
		// 現在の状態をキャプチャ（不変スナップショット）
		final int[][] capturedData = new int[data.length][];
		for (int i = 0; i < data.length; i++) {
			capturedData[i] = Arrays.copyOf(data[i], data[i].length);
		}
		final int capturedMax = max;
		final Pixmap[] capturedChips = chips;
		final boolean capturedIsBackTexOff = isBackTexOff;
		final boolean capturedIsOrderReverse = isOrderReverse;
		final boolean capturedIsNoGap = isNoGap;
		final boolean capturedIsNoGapX = isNoGapX;
		final int capturedType = type;

		rebuildPending = true;
		new Thread(() -> {
			Pixmap newBack = null;
			Pixmap newShape = null;
			Pixmap newCursor = null;
			try {
				final int w = capturedData.length * 5;
				final int h = capturedMax * 5;
				if (w <= 0 || h <= 0) {
					Gdx.app.postRunnable(() -> rebuildPending = false);
					return;
				}

				// --- back Pixmap ---
				newBack = new Pixmap(w, h, Pixmap.Format.RGBA8888);
				if (!capturedIsBackTexOff) {
					newBack.setColor(0, 0, 0, 0.8f);
					newBack.fill();
					for (int i = 10; i < capturedMax; i += 10) {
						newBack.setColor(0.007f * i, 0.007f * i, 0, 1.0f);
						newBack.fillRectangle(0, i * 5, capturedData.length * 5, 50);
					}
					for (int i = 0; i < capturedData.length; i++) {
						if (i % 60 == 0) {
							newBack.setColor(0.25f, 0.25f, 0.25f, 1.0f);
							newBack.drawLine(i * 5, 0, i * 5, capturedMax * 5);
						} else if (i % 10 == 0) {
							newBack.setColor(0.125f, 0.125f, 0.125f, 1.0f);
							newBack.drawLine(i * 5, 0, i * 5, capturedMax * 5);
						}
					}
				} else {
					newBack.setColor(TRANSPARENT_COLOR);
					newBack.fill();
				}

				// --- shape Pixmap ---
				newShape = new Pixmap(w, h, Pixmap.Format.RGBA8888);
				newShape.setColor(TRANSPARENT_COLOR);
				newShape.fill();
				for (int i = 0; i < capturedData.length; i++) {
					final int[] n = capturedData[i];
					if (!capturedIsOrderReverse) {
						for (int j = 0, k = n[0], index = 0; j < capturedMax && index < n.length;) {
							if (k > 0) {
								k--;
								newShape.drawPixmap(capturedChips[index], 0, 0, 1, 1, i * 5, j * 5, 4 + (capturedIsNoGapX ? 1 : 0), 4 + (capturedIsNoGap ? 1 : 0));
								j++;
							} else {
								index++;
								if (index == n.length) break;
								k = n[index];
							}
						}
					} else {
						for (int j = 0, k = n[n.length - 1], index = n.length - 1; j < capturedMax && index < n.length;) {
							if (k > 0) {
								k--;
								newShape.drawPixmap(capturedChips[index], 0, 0, 1, 1, i * 5, j * 5, 4 + (capturedIsNoGapX ? 1 : 0), 4 + (capturedIsNoGap ? 1 : 0));
								j++;
							} else {
								index--;
								if (index < 0) break;
								k = n[index];
							}
						}
					}
				}

				// --- cursor Pixmap (空、BMSPlayer が後で更新) ---
				newCursor = new Pixmap(w, h, Pixmap.Format.RGBA8888);
				newCursor.setColor(TRANSPARENT_COLOR);
				newCursor.fill();

			} catch (Throwable t) {
				t.printStackTrace();
				if (newBack != null) newBack.dispose();
				if (newShape != null) newShape.dispose();
				if (newCursor != null) newCursor.dispose();
				Gdx.app.postRunnable(() -> rebuildPending = false);
				return;
			}

			final Pixmap finalBack = newBack;
			final Pixmap finalShape = newShape;
			final Pixmap finalCursor = newCursor;
			Gdx.app.postRunnable(() -> {
				try {
					// 旧テクスチャを破棄
					if (backtex != null) { backtex.getTexture().dispose(); backtex = null; }
					if (shapetex != null) { shapetex.getTexture().dispose(); shapetex = null; }
					if (cursortex != null) { cursortex.getTexture().dispose(); cursortex = null; }
					// 旧 Pixmap を破棄
					if (back != null) back.dispose();
					if (shape != null) shape.dispose();
					if (cursor != null) cursor.dispose();
					// GL Thread で Texture を生成（唯一の GL 呼び出し）
					back = finalBack;
					shape = finalShape;
					cursor = finalCursor;
					backtex = new TextureRegion(new Texture(back));
					shapetex = new TextureRegion(new Texture(shape));
				} finally {
					rebuildPending = false;
				}
			});
		}, "NoteDistGraphRebuildThread").start();
	}
	
	private void updateData() {
		int pos = -1;
		int count = 0;
		max = 20;
		for(int[] d : data) {
			Arrays.fill(d, 0);				
		}

		final Mode mode = model.getMode();
		// #LNMODE is explicitly set to 1 (LN)
		// or #LNMODE is undefined and getLntype (which reflects playconfig) is LN (0)
		final boolean ignoreLNEnd = model.getLnmode() == 1 || (model.getLnmode() == 0 && model.getLntype() == BMSModel.LNTYPE_LONGNOTE);
		for (TimeLine tl : model.getAllTimeLines()) {
			final int index = tl.getTime() / 1000;
			if(index >= data.length) {
				break;
			}
			if (index != pos) {
				if (max < count) {
					max = Math.min((count / 10) * 10 + 10, 100);
				}
				pos = index;
				count = type == TYPE_NORMAL ? data[index][1] + data[index][4] : 0;
			}
			for (int i = 0; i < mode.key; i++) {
				Note n = tl.getNote(i);
				if (n != null) {
					final int st = n.getState();
					final int t = n.getPlayTime();
					switch (type) {
					case TYPE_NORMAL:
						if (n instanceof NormalNote) {
							data[index][mode.isScratchKey(i) ? 2 : 5]++;
							count++;
						} else if (n instanceof LongNote) {
							if(!((LongNote)n).isEnd()) {
								for(int lnindex = index;lnindex <= ((LongNote)n).getPair().getTime() / 1000;lnindex++) {
									data[lnindex][mode.isScratchKey(i) ? 1 : 4]++;
								}
								count++;
							}
							if((ignoreLNEnd && ((LongNote) n).isEnd())) {
								data[index][mode.isScratchKey(i) ? 0 : 3]++;
								data[index][mode.isScratchKey(i) ? 1 : 4]--;									
							}
						} else if (n instanceof MineNote) {
							data[index][6]++;
							count++;
						}
						break;
					case TYPE_JUDGE:
						if (n instanceof MineNote || (ignoreLNEnd && n instanceof LongNote && ((LongNote) n).isEnd())) {
							break;
						}
						data[index][st]++;
						count++;
						break;
					case TYPE_EARLYLATE:
						if (n instanceof MineNote || (ignoreLNEnd && n instanceof LongNote && ((LongNote) n).isEnd())) {
							break;
						}
						if (st <= 1) {
							data[index][st]++;
						} else {
							data[index][t >= 0 ? st : st + 4]++;
						}
						count++;
						break;
					}							 
				}
			}
		}

	}
	
	private void updateTexture(boolean updateall) {
		final int oldw = shape != null ? shape.getWidth() : 0;
		final int oldh = shape != null ? shape.getHeight() : 0;
		final int w = data.length * 5;
		final int h = max * 5;
		boolean refresh = false;
		if(shape == null) {
			back = new Pixmap(w, h, Pixmap.Format.RGBA8888);									
			shape = new Pixmap(w, h, Pixmap.Format.RGBA8888);
			cursor = new Pixmap(w, h, Pixmap.Format.RGBA8888);
			refresh = true;
		} else if(oldw != w || oldh != h) {
			back.dispose();				
			shape.dispose();
			cursor.dispose();
			back = new Pixmap(w, h, Pixmap.Format.RGBA8888);									
			shape = new Pixmap(w, h, Pixmap.Format.RGBA8888);						
			cursor = new Pixmap(w, h, Pixmap.Format.RGBA8888);						
			refresh = true;
		} else if(updateall){
			back.setColor(TRANSPARENT_COLOR);
			back.fill();
			shape.setColor(TRANSPARENT_COLOR);
			shape.fill();
			cursor.setColor(TRANSPARENT_COLOR);
			cursor.fill();
			refresh = true;
		}

		int start = 0;
		int end = data.length;
		if(updateall) {
			if(!isBackTexOff) {
				back.setColor(0, 0, 0, 0.8f);
				back.fill();

				for (int i = 10; i < max; i += 10) {
					back.setColor(0.007f * i, 0.007f * i, 0, 1.0f);
					back.fillRectangle(0, i * 5, data.length * 5, 50);
				}

				for (int i = 0; i < data.length; i++) {
					// x軸補助線描画
					if (i % 60 == 0) {
						back.setColor(0.25f, 0.25f, 0.25f, 1.0f);
						back.drawLine(i * 5, 0, i * 5, max * 5);
					} else if (i % 10 == 0) {
						back.setColor(0.125f, 0.125f, 0.125f, 1.0f);
						back.drawLine(i * 5, 0, i * 5, max * 5);
					}
				}
			} else if(!refresh){
				for (int i = 0; i < data.length; i++) {
					if(data[i][0] > 0) {
						start = Math.max(0, i - 2);
						end = Math.min(data.length, i + 3);
						break;
					}
				}				
			}
			
			if(backtex == null) {
				backtex = new TextureRegion(new Texture(back));			
			} else if(oldw != w || oldh != h) {
				backtex.getTexture().dispose();
				backtex = new TextureRegion(new Texture(back));
			} else {
				backtex.getTexture().draw(back, 0, 0);
			}
			
		}

		for (int i = start; i < end; i++) {
			final int[] n = data[i];
			if(!isOrderReverse) {
				for (int j = 0, k = n[0], index = 0; j < max && index < n.length;) {
					if (k > 0) {
						k--;
						shape.drawPixmap(chips[index], 0, 0, 1, 1, i * 5, j * 5, 4 + (isNoGapX ? 1 : 0), 4 + (isNoGap ? 1 : 0));
						j++;
					} else {
						index++;
						if (index == n.length) {
							break;
						}
						k = n[index];
					}
				}
			} else {
				for (int j = 0, k = n[n.length - 1], index = n.length - 1; j < max && index < n.length;) {
					if (k > 0) {
						k--;
						shape.drawPixmap(chips[index], 0, 0, 1, 1, i * 5, j * 5, 4 + (isNoGapX ? 1 : 0), 4 + (isNoGap ? 1 : 0));
						j++;
					} else {
						index--;
						if (index < 0) {
							break;
						}
						k = n[index];
					}
				}
			}
		}
		
		if(shapetex == null) {
			shapetex = new TextureRegion(new Texture(shape));
		} else if(oldw != w || oldh != h) {
			shapetex.getTexture().dispose();
			shapetex = new TextureRegion(new Texture(shape));
		} else {
			shapetex.getTexture().draw(shape, 0, 0);
		}
	}

	@Override
	public void dispose() {
		Optional.ofNullable(backtex).ifPresent(t -> t.getTexture().dispose());
		backtex = null;
		Optional.ofNullable(shapetex).ifPresent(t -> t.getTexture().dispose());
		shapetex = null;
		Optional.ofNullable(cursortex).ifPresent(t -> t.getTexture().dispose());
		cursortex = null;
		Optional.ofNullable(chips).ifPresent(t -> Stream.of(t).forEach(Pixmap::dispose));
		setDisposed();
	}

}
