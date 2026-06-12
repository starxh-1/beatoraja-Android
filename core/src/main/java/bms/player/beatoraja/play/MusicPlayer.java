package bms.player.beatoraja.play;

import java.io.File;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Array;

import bms.model.BMSModel;
import bms.model.Note;
import bms.model.TimeLine;
import bms.player.beatoraja.BMSPlayerMode;
import bms.player.beatoraja.MainController;
import bms.player.beatoraja.MainState;
import bms.player.beatoraja.PixmapResourcePool;
import bms.player.beatoraja.audio.AudioDriver;
import bms.player.beatoraja.select.BarManager;
import bms.player.beatoraja.select.bar.Bar;
import bms.player.beatoraja.select.bar.SongBar;
import bms.player.beatoraja.song.SongData;
import com.starxh.beatoraja.AudioSpectrumManager;
import com.starxh.beatoraja.AudioSpectrumProvider;

/**
 * 选曲界面中的 Music Player 状态 —— 跑当前选中歌曲的 BMS autoplay,只播放 BG 音轨,
 * 不渲染 BGA / 判定画面 / lane 视觉,改画歌曲列表 + 频谱 + 进度条 + 播放控制按钮。
 *
 * 复用 {@link MainState} 生命周期;BG 音轨调度逻辑仿
 * {@link KeySoundProcessor.AutoplayThread} 在内嵌 BGAutoplayThread 里复刻,隔离。
 */
public class MusicPlayer extends MainState {

	private BarManager barManager;
	private SongData currentSong;
	private BMSModel currentModel;
	private BGAutoplayThread bgThread;
	private AutoAdvanceThread advanceThread;
	private long playStartTimeMs;
	private long totalDurationMs;
	private Texture stagefile;
	private Pixmap stagefilePixmap;
	private BitmapFont font;
	private int skinW;
	private int skinH;

	// 控制按钮布局(屏幕底部)
	private static final float BTN_SIZE = 96f;
	private static final float BTN_MARGIN_BOTTOM = 48f;
	private static final float BTN_GAP = 32f;

	// 频谱显示区域 (屏幕坐标,左下原点)
	private static final float SPEC_X = 1840f;
	private static final float SPEC_Y = 540f;
	private static final float SPEC_W = 540f;
	private static final float SPEC_H = 200f;
	private static final int SPEC_BANDS = 32;

	// 歌曲列表区域
	private static final int LIST_VISIBLE = 10; // 上下各 4 条,中间 1 条
	private static final float LIST_LINE_H = 56f;
	private static final float LIST_TOP_Y = 0f; // 从屏幕顶部 0 起(屏幕坐标)
	private static final float LIST_LEFT_X = 36f;
	private static final float LIST_RIGHT_X = 336f; // 频谱从 X=360 开始,留 24px 间距
	private static final int LIST_TAP_THRESHOLD = 20; // 像素:低于此值视为 tap,否则视为 drag

	// 列表触摸状态
	private boolean listDragging = false;
	private int listDragStartY = 0;
	private float listDragOffset = 0f;
	private int listTouchedBarIndex = -1;

	// 后台切换歌曲时的并发守卫,防止 AutoAdvanceThread 重复进入
	private volatile boolean isTransitioning = false;

	// 后台过渡时旧 stagefile 无法立即 dispose(需要 GL 线程),先暂存,等 render() 再清理
	private Texture stagefileToDispose = null;

	// 播放模式 (顺序 / 随机 / 单曲循环)
	private enum PlayMode { SEQUENCE, RANDOM, LOOP_ONE }
	private volatile PlayMode playMode = PlayMode.SEQUENCE;

	// 频谱渲染
	private ShapeRenderer shapeRenderer;
	private final float[] specBands = new float[SPEC_BANDS];
	private final float[] specTopValues = new float[SPEC_BANDS];
	private static final float SPEC_FALL_SPEED = 0.02f;

	// 舞台图(放在屏幕正中央,4:3 横向)
	private static final float STAGEFILE_W = 480f;
	private static final float STAGEFILE_H = 320f;

	public MusicPlayer(MainController main) {
		super(main);
	}

	@Override
	public void create() {
		this.skinW = resource.getConfig().getResolution().width;
		this.skinH = resource.getConfig().getResolution().height;

		// 确保 audio 状态干净:闪退后 dispose() 可能未执行,
		// 残留 note 会导致新 setModel 与旧音频状态竞争。
		AudioDriver audioDriver = main.getAudioProcessor();
		if (audioDriver != null) {
			audioDriver.stop((Note) null);
		}

		this.barManager = main.getMusicSelector().getBarManager();
		Bar bar = barManager.getSelected();
		if (!(bar instanceof SongBar)) {
			main.changeState(MainStateType.MUSICSELECT);
			return;
		}

		this.currentSong = ((SongBar) bar).getSongData();
		this.currentModel = resource.loadBMSModel(
				Gdx.files.absolute(currentSong.getPath()),
				resource.getPlayerConfig().getLnmode());
		if (this.currentModel == null) {
			main.changeState(MainStateType.MUSICSELECT);
			return;
		}
		// 把模型的 WAV 列表灌进 AudioDriver,否则 audio.play(note) 会数组越界
		main.getAudioProcessor().setModel(currentModel);

		// 资源状态对齐 autoplay
		resource.setPlayMode(BMSPlayerMode.AUTOPLAY);

		// 舞台图
		loadStagefile();

		// 字体
		this.font = main.getSystemFont18();
		if (this.font == null) {
			this.font = new BitmapFont();
		}
		this.font.setColor(Color.WHITE);

		// 总时长:仿 BMSPlayer 公式 model.getLastTime() + max(5000, songdata.getTail())
		// model.getLastTime() 已是毫秒,等价于 autoplay 模式的 lastTimeMs
		final int lastTimeMs = currentModel.getLastTime();
		int tail = currentSong.getTail();
		if (tail <= 0) {
			tail = 5000;
		}
		this.totalDurationMs = lastTimeMs + tail;

		// 启动 BG 自动播放线程
		this.playStartTimeMs = System.currentTimeMillis();
		this.bgThread = new BGAutoplayThread(currentModel, main, playStartTimeMs);
		this.bgThread.start();

		// 启动自动切歌线程(等待 totalDurationMs 后触发,不依赖 GL 线程,后台也能正常切歌)
		this.advanceThread = new AutoAdvanceThread();
		this.advanceThread.start();

		// 启动 ShapeRenderer
		if (this.shapeRenderer == null) {
			this.shapeRenderer = new ShapeRenderer();
		}

		// 启动后开启持续渲染(选曲界面默认是关的),并把 FPS 限制到 60
		Gdx.graphics.setContinuousRendering(true);
		Gdx.graphics.setForegroundFPS(60);
	}

	private void loadStagefile() {
		String path = currentSong.getStagefile();
		if (path == null || path.isEmpty()) {
			// 没舞台图时退化到 banner
			path = currentSong.getBanner();
		}
		if (path == null || path.isEmpty()) return;
		File bmsFile = new File(currentSong.getPath());
		File coverFile = new File(bmsFile.getParentFile(), path);
		if (!coverFile.exists()) return;
		this.stagefilePixmap = PixmapResourcePool.loadPicture(coverFile.getAbsolutePath());
		if (this.stagefilePixmap != null) {
			this.stagefile = new Texture(stagefilePixmap);
		}
	}

	@Override
	public void render() {
		SpriteBatch batch = main.getSpriteBatch();
		if (batch == null) return;

		// 后台过渡时跳过了 GL 操作,stagefile 被标记为 null 且旧纹理暂存在
		// stagefileToDispose;这里的 render() 肯定在 GL 线程上,所以一次清理 + 重新加载。
		if (stagefileToDispose != null) {
			stagefileToDispose.dispose();
			stagefileToDispose = null;
		}
		if (stagefile == null && currentSong != null) {
			loadStagefile();
		}

		// 1. 背景:深色
		drawBackground(batch);
		// 2. 歌曲列表(顶部)
		drawSongList(batch);
		// 3. 舞台图(中央)
		drawStagefile(batch);
		// 4. 频谱(中部)
		drawSpectrum(batch);
		// 5. 进度条 + 时间文字
		drawProgressBar(batch);
		// 6. 播放控制按钮(底部)
		drawControlButtons(batch);
	}

	private void drawBackground(SpriteBatch batch) {
		batch.begin();
		batch.setColor(0.05f, 0.06f, 0.10f, 1f);
		Pixmap pm = ensureSolidColorPixmap(0.05f, 0.06f, 0.10f, 1f);
		Texture tex = ensureSolidColorTexture(pm);
		batch.draw(tex, 0, 0, skinW, skinH);
		batch.end();
	}

	private static Pixmap bgPixmap;
	private static Texture bgTexture;
	private static Pixmap solidColorPixmap;
	private static Texture solidColorTexture;

	private static synchronized Pixmap ensureSolidColorPixmap(float r, float g, float b, float a) {
		if (solidColorPixmap == null) {
			solidColorPixmap = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
		}
		solidColorPixmap.setColor(r, g, b, a);
		solidColorPixmap.fill();
		return solidColorPixmap;
	}

	private static synchronized Texture ensureSolidColorTexture(Pixmap pm) {
		if (solidColorTexture == null) {
			solidColorTexture = new Texture(pm);
		}
		return solidColorTexture;
	}

	private void drawSongList(SpriteBatch batch) {
		if (font == null) return;
		Bar[] bars = barManager.getBarList();
		if (bars == null || bars.length == 0) return;
		int sel = barManager.getSelectedIndex();
		int half = LIST_VISIBLE / 2;

		// 第一行的基线 y(libGDX 坐标,自下而上)
		float baseX = LIST_LEFT_X;
		float topRowBaselineY = skinH - LIST_TOP_Y - LIST_LINE_H * 0.5f;

		batch.begin();
		for (int row = 0; row < LIST_VISIBLE; row++) {
			int idx = (sel + row - half + bars.length) % bars.length;
			Bar b = bars[idx];
			String title = b == null ? "" : (b.getTitle() == null ? "" : b.getTitle());

			// 随拖拽偏移整体平移:手指下滑(gdx_y 减小)→ listDragOffset > 0 → 行向下移
			float y = topRowBaselineY - row * LIST_LINE_H - listDragOffset;

			if (row == half) {
				// 当前曲目:高亮背景
				batch.setColor(0.20f, 0.30f, 0.55f, 0.9f);
				Pixmap pm = ensureSolidColorPixmap(0.20f, 0.30f, 0.55f, 0.9f);
				Texture tex = ensureSolidColorTexture(pm);
				batch.draw(tex,
						LIST_LEFT_X - 12f,
						y - LIST_LINE_H * 0.5f + 4f,
						LIST_RIGHT_X - LIST_LEFT_X + 24f,
						LIST_LINE_H - 8f);
				font.setColor(1f, 0.95f, 0.55f, 1f);
			} else {
				float fade = 1f - Math.abs(row - half) * 0.12f;
				if (fade < 0.3f) fade = 0.3f;
				font.setColor(fade, fade, fade, 1f);
			}
			font.draw(batch, title, baseX, y);
		}
		batch.end();
	}

	private void drawStagefile(SpriteBatch batch) {
		if (stagefile == null) return;
		float x = (skinW - STAGEFILE_W) / 2f;
		float y = (skinH - STAGEFILE_H) / 2f;
		batch.begin();
		batch.setColor(1, 1, 1, 1);
		batch.draw(stagefile, x, y, STAGEFILE_W, STAGEFILE_H);
		batch.end();
	}

	private void drawSpectrum(SpriteBatch batch) {
		// 1. 取频谱(64 段 = 32 左 + 32 右,合并成 32 段单声道)
		AudioSpectrumProvider provider = AudioSpectrumManager.getGlobalProvider();
		float[] raw = provider == null ? null : provider.getSpectrumMagnitudes();
		if (raw == null || raw.length < 64) {
			// 退化:全 0
			for (int i = 0; i < SPEC_BANDS; i++) {
				specBands[i] = 0f;
			}
		} else {
			for (int i = 0; i < SPEC_BANDS; i++) {
				float left = raw[i];
				float right = raw[32 + i];
				float v = (left + right) * 0.5f;
				if (v < 0f) v = 0f;
				if (v > 1f) v = 1f;
				specBands[i] = v;
			}
		}

		// 2. 顶部 peak 衰减
		for (int i = 0; i < SPEC_BANDS; i++) {
			if (specBands[i] > specTopValues[i]) {
				specTopValues[i] = specBands[i];
			} else {
				specTopValues[i] -= SPEC_FALL_SPEED;
				if (specTopValues[i] < 0) specTopValues[i] = 0;
			}
		}

		// 3. 边框
		if (shapeRenderer == null) {
			shapeRenderer = new ShapeRenderer();
		}
		Gdx.gl.glEnable(Gdx.gl.GL_BLEND);
		Gdx.gl.glBlendFunc(Gdx.gl.GL_SRC_ALPHA, Gdx.gl.GL_ONE_MINUS_SRC_ALPHA);
		shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);

		// 背景框
		shapeRenderer.setColor(0f, 0f, 0f, 0.6f);
		shapeRenderer.rect(SPEC_X, SPEC_Y, SPEC_W, SPEC_H);

		// 频谱条
		float bandW = SPEC_W / SPEC_BANDS;
		float barThickness = bandW * 0.7f;
		for (int i = 0; i < SPEC_BANDS; i++) {
			float x = SPEC_X + i * bandW;
			float v = specBands[i];
			float top = specTopValues[i];
			float barHeight = v * (SPEC_H - 4f);
			float topY = top * (SPEC_H - 4f);
			barHeight = Math.min(barHeight, SPEC_H - 4f);

			// 主条:蓝绿渐变(按频率从低到高,颜色从青到紫)
			float hue = (float) i / SPEC_BANDS;
			shapeRenderer.setColor(0.3f + hue * 0.4f, 0.7f, 1f - hue * 0.5f, 0.85f);
			shapeRenderer.rect(x + (bandW - barThickness) / 2f, SPEC_Y + 2f,
					barThickness, barHeight);

			// 顶部峰值
			if (topY > 2f) {
				shapeRenderer.setColor(1f, 1f, 1f, 0.9f);
				shapeRenderer.rect(x + (bandW - barThickness) / 2f, SPEC_Y + 2f + topY - 2f,
						barThickness, 2f);
			}
		}
		shapeRenderer.end();
	}

	private void drawProgressBar(SpriteBatch batch) {
		long currentMs = getCurrentPlaybackMs();
		float progress = totalDurationMs > 0
				? Math.min(1f, (float) currentMs / totalDurationMs)
				: 0f;
		float barX = 48f;
		float barY = BTN_MARGIN_BOTTOM + BTN_SIZE + 32f;
		float barW = skinW - 96f;
		float barH = 8f;

		batch.begin();
		// 底色
		batch.setColor(0.20f, 0.20f, 0.25f, 1f);
		Pixmap pm = ensureSolidColorPixmap(0.20f, 0.20f, 0.25f, 1f);
		batch.draw(ensureSolidColorTexture(pm), barX, barY, barW, barH);
		// 进度
		batch.setColor(0.95f, 0.90f, 0.40f, 1f);
		pm = ensureSolidColorPixmap(0.95f, 0.90f, 0.40f, 1f);
		batch.draw(ensureSolidColorTexture(pm), barX, barY, barW * progress, barH);
		batch.end();

		// 时间文字
		if (font != null) {
			batch.begin();
			font.setColor(0.8f, 0.8f, 0.8f, 1f);
			String timeText = formatTime(currentMs) + " / " + formatTime(totalDurationMs);
			font.draw(batch, timeText, barX, barY - 8f);
			batch.end();
		}
	}

	private void drawControlButtons(SpriteBatch batch) {
		// 4 个按钮:上一首 / 下一首 / 模式 / 退出
		float totalW = BTN_SIZE * 4 + BTN_GAP * 3;
		float startX = (skinW - totalW) / 2f;
		float y = BTN_MARGIN_BOTTOM;

		batch.begin();
		batch.setColor(0.20f, 0.22f, 0.30f, 0.9f);
		Pixmap pm = ensureSolidColorPixmap(0.20f, 0.22f, 0.30f, 0.9f);
		Texture tex = ensureSolidColorTexture(pm);
		for (int i = 0; i < 4; i++) {
			float x = startX + i * (BTN_SIZE + BTN_GAP);
			batch.draw(tex, x, y, BTN_SIZE, BTN_SIZE);
		}
		batch.end();

		// 按钮文字
		if (font != null) {
			batch.begin();
			font.setColor(1, 1, 1, 1);
			String[] labels = {"PREV", "NEXT", modeLabel(playMode), "EXIT"};
			for (int i = 0; i < 4; i++) {
				float x = startX + i * (BTN_SIZE + BTN_GAP);
				font.draw(batch, labels[i], x + 12f, y + BTN_SIZE / 2f);
			}
			batch.end();
		}
	}

	private static String modeLabel(PlayMode m) {
		switch (m) {
			case SEQUENCE: return "SEQ";
			case RANDOM:   return "RND";
			case LOOP_ONE: return "LOOP";
			default: return "?";
		}
	}

	@Override
	public void input() {
		// 物理键盘 / Android BACK 键
		if (Gdx.input.isKeyJustPressed(Input.Keys.LEFT)) {
			playPrev();
		} else if (Gdx.input.isKeyJustPressed(Input.Keys.RIGHT)) {
			playNext();
		} else if (Gdx.input.isKeyJustPressed(Input.Keys.M)) {
			cyclePlayMode();
		} else if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
			// libGDX Android 后端会把 BACK 重映射成 ESCAPE 派发 (见 logcat "BACK detected, remapping to ESCAPE"),
			// 所以这里 catch ESC 就同时 catch 了 Android BACK。
			// 行为:等价于点击 EXIT 按钮(切回选曲界面)
			main.changeState(MainStateType.MUSICSELECT);
			return;
		}

		// 列表触屏:滑动 / 点击
		handleListTouch();

		// 触屏:4 个按钮区域(列表已开始拖拽时不响应按钮,避免和列表冲突)
		if (!listDragging && Gdx.input.justTouched()) {
			int gx = main.getInputProcessor().getMouseX();
			int gy = main.getInputProcessor().getMouseY();
			int idx = hitTestControlButton(gx, gy);
			if (idx >= 0) {
				switch (idx) {
					case 0: playPrev(); break;
					case 1: playNext(); break;
					case 2: cyclePlayMode(); break;
					case 3: main.changeState(MainStateType.MUSICSELECT); break;
				}
			}
		}
	}

	private void handleListTouch() {
		int gx = Gdx.input.getX();
		int gy = skinH - Gdx.input.getY(); // 转 libGDX Y
		boolean touched = Gdx.input.isTouched() || Gdx.input.justTouched();

		if (touched) {
			if (!listDragging) {
				if (isInListArea(gx, gy)) {
					listDragging = true;
					listDragStartY = gy;
					listTouchedBarIndex = computeBarIndexAtTouch(gy);
				}
			} else {
				// 手指向下滑(gdx_y 减小) -> listDragOffset > 0
				listDragOffset = listDragStartY - gy;
			}
		} else {
			if (listDragging) {
				if (Math.abs(listDragOffset) < LIST_TAP_THRESHOLD) {
					// 视为 tap:选中并开始播放
					if (listTouchedBarIndex >= 0) {
						barManager.setSelectedIndex(listTouchedBarIndex);
						loadAndPlaySelected();
					}
				} else {
					// 视为 drag:按行换 selectedindex
					// 渲染方向(手指下滑 → 行下移)走的是 convention 1(content follows finger),
					// 手指下滑时进入视野的是上方(LOWER 索引)的曲子,snap 必须与之一致:
					// listDragOffset > 0 → newSel 减小。
					Bar[] bars = barManager.getBarList();
					if (bars != null && bars.length > 0) {
						int deltaSel = -Math.round(listDragOffset / LIST_LINE_H);
						int cur = barManager.getSelectedIndex();
						int n = bars.length;
						int newSel = ((cur + deltaSel) % n + n) % n;
						barManager.setSelectedIndex(newSel);
					}
				}
				listDragging = false;
				listDragOffset = 0f;
			}
		}
	}

	private boolean isInListArea(int gx, int gy) {
		if (gx < LIST_LEFT_X - 24f || gx > LIST_RIGHT_X + 24f) return false;
		float listTopY = skinH - LIST_TOP_Y;
		float listBottomY = listTopY - LIST_VISIBLE * LIST_LINE_H;
		return gy >= listBottomY && gy <= listTopY;
	}

	private int computeBarIndexAtTouch(int gy) {
		Bar[] bars = barManager.getBarList();
		if (bars == null || bars.length == 0) return -1;
		int sel = barManager.getSelectedIndex();
		int half = LIST_VISIBLE / 2;
		// 绘制公式:row r 中心 y = baseRow0Y - r * LIST_LINE_H - listDragOffset
		// (见 drawSongList) —— 反推 row 应该用 (baseRow0Y - listDragOffset - gy) / LIST_LINE_H
		float baseRow0Y = skinH - LIST_TOP_Y - LIST_LINE_H * 0.5f;
		int row = Math.round((baseRow0Y - listDragOffset - gy) / LIST_LINE_H);
		if (row < 0 || row >= LIST_VISIBLE) return -1;
		int idx = (sel + row - half + bars.length) % bars.length;
		return idx;
	}

	private void loadAndPlaySelected() {
		Bar next = barManager.getSelected();
		if (next instanceof SongBar) {
			shutdownResources();
			create();
		}
	}

	private int hitTestControlButton(int gx, int gy) {
		float totalW = BTN_SIZE * 4 + BTN_GAP * 3;
		float startX = (skinW - totalW) / 2f;
		float y = BTN_MARGIN_BOTTOM;
		for (int i = 0; i < 4; i++) {
			float x = startX + i * (BTN_SIZE + BTN_GAP);
			if (gx >= x && gx <= x + BTN_SIZE && gy >= y && gy <= y + BTN_SIZE) {
				return i;
			}
		}
		return -1;
	}

	private void playNext() {
		advanceByMode(true);
	}

	private void playPrev() {
		advanceByMode(false);
	}

	/**
	 * 按当前 playMode 推进到下一首/上一首(用于手动 PREV/NEXT 按钮和歌曲结束的自动切歌)。
	 *  - LOOP_ONE:不切歌,直接重新播放当前曲目
	 *  - RANDOM:随机选一首跟当前不同的;若曲目数 <= 1 则保持当前
	 *  - SEQUENCE:走 BarManager 默认行为(folder 内循环)
	 */
	private void advanceByMode(boolean forward) {
		switch (playMode) {
			case LOOP_ONE:
				loadAndPlaySelected();
				return;
			case RANDOM: {
				Bar[] bars = barManager.getBarList();
				if (bars == null || bars.length <= 1) {
					loadAndPlaySelected();
					return;
				}
				int cur = barManager.getSelectedIndex();
				java.util.Random rng = new java.util.Random();
				int newIdx = cur;
				int safety = 16;
				while (newIdx == cur && safety-- > 0) {
					newIdx = rng.nextInt(bars.length);
				}
				barManager.setSelectedIndex(newIdx);
				loadAndPlaySelected();
				return;
			}
			case SEQUENCE:
			default:
				barManager.move(forward);
				loadAndPlaySelected();
				return;
		}
	}

	private void cyclePlayMode() {
		switch (playMode) {
			case SEQUENCE: playMode = PlayMode.RANDOM; break;
			case RANDOM:   playMode = PlayMode.LOOP_ONE; break;
			case LOOP_ONE: playMode = PlayMode.SEQUENCE; break;
		}
	}

	private long getCurrentPlaybackMs() {
		return System.currentTimeMillis() - playStartTimeMs;
	}

	private static String formatTime(long ms) {
		if (ms < 0) ms = 0;
		long sec = ms / 1000;
		long min = sec / 60;
		sec = sec % 60;
		return String.format("%d:%02d", min, sec);
	}

	@Override
	public void shutdown() {
		shutdownResources();
	}

	/**
	 * Android 屏幕关掉 (Activity.onPause/onStop) 时 libGDX 会调到这里。
	 * 父类 MainState.pause()/resume() 是空实现,默认会让 render() 停止被调用。
	 * 这里保持 BG 自动播放线程不受影响 —— 它跑在自己线程上,基于 wall time
	 * (System.currentTimeMillis() - wallStartMs) 推进,Activity 生命周期无关。
	 * Oboe 音频流也由 AAudio 单独驱动,只要进程不被打死就会继续播。
	 * 这样锁屏 / 屏幕关 / 应用切到其他 activity 短暂遮挡时音乐不会中断。
	 *
	 * 注意:真正的"应用彻底后台" (Android 进程被杀) 需要 ForegroundService + mediaPlayback
	 * 才能继续播 —— 那需要改 AndroidManifest 启动前台服务和 Oboe Usage::Game → Media。
	 * 单纯 override pause/resume 只能保证 standby / 短时切换应用 不断音。
	 */
	@Override
	public void pause() {
	}

	@Override
	public void resume() {
		// 从 standby / 切回前台 时,libGDX 会关掉持续渲染和 FPS 限制,这里重新打开
		if (Gdx.graphics != null) {
			Gdx.graphics.setContinuousRendering(true);
			Gdx.graphics.setForegroundFPS(60);
		}
		// GL 上下文在 onStop 后被重建,所有 Texture 引用都已失效 —— 重新加载舞台图
		if (currentSong != null && stagefile == null) {
			loadStagefile();
		}
		// MainController.resume() 重新生成了 systemfont18,旧引用指向已 dispose 的对象;
		// 重新拿一次,否则 font.draw() 引用失效纹理会导致渲染缺失
		if (main != null) {
			BitmapFont fresh = main.getSystemFont18();
			if (fresh != null) {
				this.font = fresh;
			}
		}
		if (this.font != null) {
			this.font.setColor(Color.WHITE);
		}
	}

	private void shutdownResources() {
		if (advanceThread != null) {
			advanceThread.stop = true;
			advanceThread = null;
		}
		if (bgThread != null) {
			bgThread.stop = true;
			try {
				bgThread.join(500);
			} catch (InterruptedException ignored) {
			}
			bgThread = null;
		}
		// 强制停止上一首所有 K/BG 音轨 —— 已经在 Oboe 缓冲里排队的 note 不停的话,
		// 切歌后会跟新歌重叠。必须在 setModel(newModel) 之前调用,否则 wavmap 已替换。
		// 注意:此方法仅应该在用户主动切换(手动 PREV/NEXT/选歌)时调用,自动切歌不走这里,
		// 由 AutoAdvanceThread 等 tail 结束后直接 transitionToNextInBackground,
		// 不调 stop,让最后一条 note 自然播完。
		if (main != null) {
			AudioDriver audio = main.getAudioProcessor();
			if (audio != null) {
				audio.stop((Note) null);
			}
		}
		if (stagefile != null) {
			stagefile.dispose();
			stagefile = null;
		}
		// stagefilePixmap 已经转 Texture,不需要单独 dispose
		stagefilePixmap = null;
		// 复位频谱
		for (int i = 0; i < SPEC_BANDS; i++) {
			specBands[i] = 0f;
			specTopValues[i] = 0f;
		}
		// 退出时恢复无限速(0 表示不限制),由下一状态自行决定
		Gdx.graphics.setForegroundFPS(0);
	}

	@Override
	public void dispose() {
		shutdownResources();
		if (font != null && font != main.getSystemFont18()) {
			font.dispose();
		}
		font = null;
		if (shapeRenderer != null) {
			shapeRenderer.dispose();
			shapeRenderer = null;
		}
		super.dispose();
	}

	/**
	 * BG 音轨自动播放线程 —— 仿 {@link KeySoundProcessor.AutoplayThread},但持有自己的时钟(基于 wall time)。
	 * 播完所有 timeline 后自然退出,不触发任何回调 —— 切歌完全由 AutoAdvanceThread 在 totalDurationMs 触发,
	 * 中间 tail 静音期留给最后一条 note 自然播完,避免 audio.stop() 掐音效。
	 */
	private static class BGAutoplayThread extends Thread {
		private final BMSModel model;
		private final MainController main;
		private volatile boolean stop = false;
		private final long wallStartMs;

		BGAutoplayThread(BMSModel model, MainController main, long wallStartMs) {
			this.model = model;
			this.main = main;
			this.wallStartMs = wallStartMs;
		}

		@Override
		public void run() {
			AudioDriver audio = main.getAudioProcessor();
			float vol = main.getPlayerResource().getConfig().getAudioConfig().getBgvolume();

			Array<TimeLine> tls = new Array<>();
			for (TimeLine tl : model.getAllTimeLines()) {
				if (tl.getBackGroundNotes().length > 0 || hasKeyNote(tl)) {
					tls.add(tl);
				}
			}
			TimeLine[] timelines = tls.toArray(TimeLine.class);

			int p = 0;
			while (!stop) {
				long nowMs = System.currentTimeMillis() - wallStartMs;
				long timeMicros = nowMs * 1000L;
				while (p < timelines.length && timelines[p].getMicroTime() <= timeMicros) {
					TimeLine tl = timelines[p];
					for (Note n : tl.getBackGroundNotes()) {
						audio.play(n, vol, 0);
					}
					for (int lane = 0; lane < tl.getLaneCount(); lane++) {
						Note n = tl.getNote(lane);
						if (n != null) {
							audio.play(n, vol, 0);
						}
					}
					p++;
				}
				if (p >= timelines.length) {
					// 所有 note 已播完 → 线程自然退出,切歌等待 AutoAdvanceThread 触发
					break;
				}
				long nextMicros = timelines[p].getMicroTime();
				long sleepMs = Math.max(1, (nextMicros - timeMicros) / 1000L);
				if (sleepMs > 5) sleepMs = 5;
				try {
					sleep(sleepMs);
				} catch (InterruptedException ignored) {
				}
			}
		}

		private static boolean hasKeyNote(TimeLine tl) {
			for (int i = 0; i < tl.getLaneCount(); i++) {
				if (tl.getNote(i) != null) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * 监听播放进度,到 totalDurationMs(最后一条 note + tail)后自动切到下一首。
	 * 不依赖 Gdx.app.postRunnable,后台锁屏时也能直接完成。
	 * transitionToNextInBackground() 内部有 synchronized + isTransitioning 守卫,重复调用安全。
	 */
	private class AutoAdvanceThread extends Thread {
		volatile boolean stop = false;

		@Override
		public void run() {
			while (!stop) {
				long elapsed = getCurrentPlaybackMs();
				if (elapsed >= totalDurationMs) {
					transitionToNextInBackground();
					break;
				}
				try {
					sleep(200);
				} catch (InterruptedException ignored) {
				}
			}
		}
	}

	/**
	 * BGAutoplayThread 曲终后由 AutoAdvanceThread 调用的自动切歌入口。
	 *
	 * 与 {@link #loadAndPlaySelected()} 的区别:loadAndPlaySelected 在 GL 线程上调用,
	 * 包含完整的 shutdownResources + create(含 stagefile Texture 上传、audio.stop() 等);
	 * 而本方法可以工作在任何线程,跳过所有 GL 依赖和 audio.stop(),只做:
	 * 推进曲目 → 加载 BMSModel → 设新音频模型 → 启新线程。
	 * GL 相关的 stagefile 清理/加载推迟到下一个 render() 统一处理。
	 * 不调 audio.stop()——触发时已在 tail 之后,最后一条 note 应已自然播完。
	 *
	 * @return true 表示成功切换;false 表示被并发守卫拦截(重复调用)
	 */
	private synchronized boolean transitionToNextInBackground() {
		if (isTransitioning) return false;
		isTransitioning = true;
		try {
			// 1. 停掉 advanceThread(防止它重复进入本方法)
			if (advanceThread != null) {
				advanceThread.stop = true;
				advanceThread = null;
			}

			// 2. 按 playMode 推进到下一首(与 advanceByMode 同逻辑)
			// 注意:先推进歌曲再清理 stagefile,确保 render() 如果在此期间运行
			// 看到的是已更新的 currentSong,从而 loadStagefile() 加载正确的封面。
			switch (playMode) {
				case LOOP_ONE:
					// 保持当前曲目,直接重启
					break;
				case RANDOM: {
					Bar[] bars = barManager.getBarList();
					if (bars != null && bars.length > 1) {
						java.util.Random rng = new java.util.Random();
						int cur = barManager.getSelectedIndex();
						int newIdx = cur;
						for (int safety = 16; safety > 0 && newIdx == cur; safety--) {
							newIdx = rng.nextInt(bars.length);
						}
						barManager.setSelectedIndex(newIdx);
					}
					break;
				}
				case SEQUENCE:
				default:
					barManager.move(true);
					break;
			}

			// 4. 检查新曲目是否有效
			Bar bar = barManager.getSelected();
			if (!(bar instanceof SongBar)) {
				Gdx.app.postRunnable(() -> main.changeState(MainStateType.MUSICSELECT));
				return true;
			}

			// 5. currentSong 已更新,在此之后清理 stagefile,保证 render() 若同时运行
			// 看到的是新 currentSong,loadStagefile() 会加载正确封面。
			this.currentSong = ((SongBar) bar).getSongData();
			if (this.stagefile != null) {
				this.stagefileToDispose = this.stagefile;
				this.stagefile = null;
				this.stagefilePixmap = null;
			}

			// 6. 加载新 BMSModel(纯文件 I/O + 解析,不需要 GL)
			this.currentModel = resource.loadBMSModel(
					Gdx.files.absolute(currentSong.getPath()),
					resource.getPlayerConfig().getLnmode());
			if (currentModel == null) {
				Gdx.app.postRunnable(() -> main.changeState(MainStateType.MUSICSELECT));
				return true;
			}

			// 7. 设置新音频模型(不调 audio.stop(),最后一条 note 会自然播完)
			main.getAudioProcessor().setModel(currentModel);
			resource.setPlayMode(BMSPlayerMode.AUTOPLAY);

			// 8. 计算新时长
			int tail = currentSong.getTail();
			if (tail <= 0) tail = 5000;
			this.totalDurationMs = currentModel.getLastTime() + tail;

			// 9. 启动新线程
			this.playStartTimeMs = System.currentTimeMillis();
			this.bgThread = new BGAutoplayThread(currentModel, main,
					playStartTimeMs);
			this.bgThread.start();
			this.advanceThread = new AutoAdvanceThread();
			this.advanceThread.start();

			// 10. 复位频谱(纯内存,无 GL)
			for (int i = 0; i < SPEC_BANDS; i++) {
				specBands[i] = 0f;
				specTopValues[i] = 0f;
			}
			return true;
		} finally {
			isTransitioning = false;
		}
	}
}
