package bms.player.beatoraja.play;

import static bms.player.beatoraja.CourseData.CourseDataConstraint.*;
import static bms.player.beatoraja.skin.SkinProperty.*;
import static bms.player.beatoraja.SystemSoundManager.SoundType.*;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Rectangle;

import bms.model.*;
import bms.player.beatoraja.*;
import bms.player.beatoraja.FileCache;
import bms.player.beatoraja.AudioConfig.FrequencyType;
import bms.player.beatoraja.input.*;
import bms.player.beatoraja.pattern.*;
import bms.player.beatoraja.pattern.LaneShuffleModifier.*;
import bms.player.beatoraja.audio.PCM;
import bms.player.beatoraja.play.PracticeConfiguration.PracticeProperty;
import bms.player.beatoraja.play.bga.BGAProcessor;
import bms.player.beatoraja.skin.Skin;
import bms.player.beatoraja.skin.SkinType;

/**
 * BMSプレイヤー本体
 *
 * @author exch
 */
public class BMSPlayer extends MainState implements PlayStateValues {

	private BMSModel model;

	private LaneRenderer lanerender;
	private LaneProperty laneProperty;
	private JudgeManager judge;

	private BGAProcessor bga;

	private GrooveGauge gauge;

	private int playtime;

	/**
	 * 最后一个音符结束时间（不含音频尾部），用于 finish 显示判定
	 */
	private int lastNoteEndTime;

	/**
	 * 最后一个音符结束后的音频尾部时长（ms）
	 */
	private int maxTailMs;

	public int getMaxTailMs() {
		return maxTailMs;
	}

	/**
	 * キー入力用スレッド
	 */
	private KeyInputProccessor keyinput;
	private ControlInputProcessor control;

	/**
	 * 触摸按键映射（Android平台）
	 */
	private PlayTouchKeyMapper touchKeyMapper;

	public PlayTouchKeyMapper getTouchKeyMapper() {
		return touchKeyMapper;
	}

	private KeySoundProcessor keysound;

	private int assist = 0;

	private ReplayData playinfo = new ReplayData();
	/**
	 * リプレイデータ
	 */
	private ReplayData replay = null;

	private final FloatArray[] gaugelog;

	private volatile int playspeed = 100;

	/**
	 * リプレイHS保存用 STATE READY時に保存
	 */
	private PlayConfig replayConfig;


	private volatile int state = STATE_PRELOAD;

	public static final int STATE_PRELOAD = 0;
	public static final int STATE_PRACTICE = 1;
	public static final int STATE_PRACTICE_FINISHED = 2;
	public static final int STATE_READY = 3;
	public static final int STATE_PLAY = 4;
	public static final int STATE_FAILED = 5;
	public static final int STATE_FINISHED = 6;

	private long prevtime;

	private PracticeConfiguration practice = new PracticeConfiguration();
	private long starttimeoffset;

	/**
	 * 練習メニュー中の復帰再生用音源の事前生成: 直前フレームで見ていた開始位置(ms)。
	 * 右/左キーを押しっぱなしにすると連続で変わるため、「変化が止まった」の判定に使う。
	 */
	private int practicePregenWatched = Integer.MIN_VALUE;
	/**
	 * 練習メニュー中の復帰再生用音源の事前生成: 開始位置が最後に変化した時刻(us)。
	 */
	private long practicePregenChangedMicro = 0;
	/**
	 * 練習メニュー中の復帰再生用音源の事前生成: 既に生成を開始した開始位置(ms)。
	 * 同じ位置で何度も起動しないための目印。
	 */
	private int practicePregenDoneFor = Integer.MIN_VALUE;

	/**
	 * 練習メニューで開始位置の変化が止まったと見なすまでの時間(us)。
	 * 押しっぱなしで連続して変わる間は生成を始めない(生成は数秒かかるため、
	 * 途中の位置で走らせても捨てるだけになる)。
	 */
	private static final long PRACTICE_PREGEN_SETTLE_US = 500000;

	private float adjustedVolume = -1.f;

	private RhythmTimerProcessor rhythm;
	private long startpressedtime;
	private boolean bgaPrepared = false;

	public BMSPlayer(MainController main, PlayerResource resource) {
		super(main);
		this.model = resource.getBMSModel();
		BMSPlayerMode autoplay = resource.getPlayMode();
		PlayerConfig config = resource.getPlayerConfig();

		playinfo.randomoption = config.getRandom();
		playinfo.randomoption2 = config.getRandom2();
		playinfo.doubleoption = config.getDoubleoption();

		ReplayData HSReplay = null;

		if(resource.getChartOption() != null) {
			ReplayData chartOption = resource.getChartOption();
			playinfo.randomoption = chartOption.randomoption;
			playinfo.randomoptionseed = chartOption.randomoptionseed;
			playinfo.randomoption2 = chartOption.randomoption2;
			playinfo.randomoption2seed = chartOption.randomoption2seed;
			playinfo.doubleoption = chartOption.doubleoption;
			playinfo.rand = chartOption.rand;
		}

		if (autoplay.mode == BMSPlayerMode.Mode.REPLAY) {
			if (resource.getCourseBMSModels() != null) {
				// コースモードのリプレイ読み込み
				if (resource.getCourseReplay().length == 0) {
					// コースモード1曲目の処理
					ReplayData[] replays = main.getPlayDataAccessor().readReplayData(resource.getCourseBMSModels(),
							config.getLnmode(), autoplay.id, resource.getConstraint());
					if (replays != null) {
						for (ReplayData rd : replays) {
							resource.addCourseReplay(rd);
						}
						replay = replays[0];
					} else {
						Logger.getGlobal().info("リプレイデータを読み込めなかったため、通常プレイモードに移行");
						autoplay = BMSPlayerMode.PLAY;
						resource.setPlayMode(autoplay);
					}
				} else {
					// 2曲目以降の処理
					for (int i = 0; i < resource.getCourseBMSModels().length; i++) {
						if (resource.getCourseBMSModels()[i].getMD5().equals(resource.getBMSModel().getMD5())) {
							replay = resource.getCourseReplay()[i];
						}
					}
				}
			} else {
				// 1曲モードのリプレイ読み込み
				replay = main.getPlayDataAccessor().readReplayData(model, config.getLnmode(), autoplay.id);
				if (replay != null) {
					boolean isReplayPatternPlay = false;
					if(main.getInputProcessor().getKeyState(1)) {
						//保存された譜面オプション/Random Seedから譜面再現
						Logger.getGlobal().info("リプレイ再現モード : 譜面");
						playinfo.randomoption = replay.randomoption;
						playinfo.randomoptionseed = replay.randomoptionseed;
						playinfo.randomoption2 = replay.randomoption2;
						playinfo.randomoption2seed = replay.randomoption2seed;
						playinfo.doubleoption = replay.doubleoption;
						playinfo.rand = replay.rand;
						isReplayPatternPlay = true;
					} else if(main.getInputProcessor().getKeyState(2)) {
						//保存された譜面オプションログから譜面オプション再現
						Logger.getGlobal().info("リプレイ再現モード : オプション");
						playinfo.randomoption = replay.randomoption;
						playinfo.randomoption2 = replay.randomoption2;
						playinfo.doubleoption = replay.doubleoption;
						isReplayPatternPlay = true;
					}
					if(main.getInputProcessor().getKeyState(4)) {
						//保存されたHSオプションログからHSオプション再現
						Logger.getGlobal().info("リプレイ再現モード : ハイスピード");
						HSReplay = replay;
						isReplayPatternPlay = true;
					}
					if(isReplayPatternPlay) {
						replay = null;
						autoplay = BMSPlayerMode.PLAY;
						resource.setPlayMode(autoplay);
					}
				} else {
					Logger.getGlobal().info("リプレイデータを読み込めなかったため、通常プレイモードに移行");
					autoplay = BMSPlayerMode.PLAY;
					resource.setPlayMode(autoplay);
				}
			}
		}

		boolean score = true;

		// RANDOM構文処理
		if (model.getRandom() != null && model.getRandom().length > 0) {
			if (autoplay.mode == BMSPlayerMode.Mode.REPLAY) {
				playinfo.rand = replay.rand;
			} else if (resource.getReplayData().randomoptionseed != -1) {
				// この処理はMusicResult、QuickRetry時にのみ通る
				playinfo.rand = resource.getReplayData().rand;
			}

			if(playinfo.rand != null && playinfo.rand.length > 0) {
				model = resource.loadBMSModel(playinfo.rand);
				// 暫定処置
				BMSModelUtils.setStartNoteTime(model, 1000);
				BMSPlayerRule.validate(model);
			}
			playinfo.rand = model.getRandom();
			Logger.getGlobal().info("譜面分岐 : " + Arrays.toString(playinfo.rand));
		}
		// 通常プレイの場合は最後のノーツ、オートプレイの場合はBG/BGAを含めた最後のノーツ
		final int lastEventTime = model.getLastTime();
		final int lastNoteTime = model.getLastNoteTime();
		final int lastTimeMs = autoplay.mode == BMSPlayerMode.Mode.AUTOPLAY
				? lastEventTime : lastNoteTime;

		// 计算音频尾部时长（统一以 lastNoteTime 为基准，避免模式切换导致 tail 计算偏移）
		maxTailMs = resource.getSongdata().getTail();
		if (maxTailMs <= 0) {
			maxTailMs = 0;
			long startTime = System.currentTimeMillis();
			final String[] wavlist = model.getWavList();
			final java.io.File bmsDir = new java.io.File(model.getPath()).getParentFile();
			// 记录每个 wavid 最后一次出现的时间。使用数组代替 HashMap 减少 GC 压力（避免数万次 Integer 装箱）
			final int[] lastOccurrenceArray = new int[wavlist.length];
			java.util.Arrays.fill(lastOccurrenceArray, -1);

			for (TimeLine tl : model.getAllTimeLines()) {
				final int time = tl.getTime();
				// 演奏通道
				for (int lane = 0; lane < model.getMode().key; lane++) {
					Note n = tl.getNote(lane);
					if (n == null) n = tl.getHiddenNote(lane);
					if (n != null && n.getWav() >= 0 && n.getWav() < lastOccurrenceArray.length) {
						lastOccurrenceArray[n.getWav()] = time;
					}
				}
				// BGM通道
				for (Note n : tl.getBackGroundNotes()) {
					if (n != null && n.getWav() >= 0 && n.getWav() < lastOccurrenceArray.length) {
						lastOccurrenceArray[n.getWav()] = time;
					}
				}
			}

			// 1. 一次性建立目录索引，避免数千次 File.exists() 系统调用导致 I/O 阻塞
			//    音频位于子目录（如 audio/bgm.ogg）时才惰性补一次有界深度递归扫描
			final PCM.AudioFileIndex audioFileIndex = new PCM.AudioFileIndex(bmsDir);

			// 2. 收集所有唯一的音符及其最后出现时间，并按时间降序排列
			// 优先检查结尾音符可以更早更新 maxTailMs，从而让后续音符触发启发式跳过
			final List<int[]> sortedWavs = new ArrayList<>();
			for (int wavid = 0; wavid < lastOccurrenceArray.length; wavid++) {
				if (lastOccurrenceArray[wavid] != -1 && wavlist[wavid] != null) {
					sortedWavs.add(new int[]{wavid, lastOccurrenceArray[wavid]});
				}
			}
			sortedWavs.sort((a, b) -> Integer.compare(b[1], a[1]));

			int checkCount = 0;
			int uniqueWavs = sortedWavs.size();

			for (int[] entry : sortedWavs) {
				final int wavid = entry[0];
				final int lastTime = entry[1];

				// 3. 启发式跳过逻辑
				// 如果该音符即便按极低码率（8 bytes/ms，即 8KB/s）计算出的理论最大长度
				// 都不足以超过当前的 maxTailMs 结束位置，则完全无需打开该文件。
				// 这能过滤掉 3000 个 keysound 中 90% 以上的小文件。
				// wavlist 已由 BMSDecoder 归一化为相对路径（如 audio/bgm.ogg），交给索引解析
				java.io.File audioFile = audioFileIndex.resolve(wavlist[wavid]);

				if (audioFile != null) {
					long fileSize = audioFile.length();
					// 如果该音符最后出现时间 + 理论最大时长(fileSize/8) <= 当前已探测到的最晚结束时间
					// 则此文件绝不可能贡献新的 maxTail，直接跳过。
					if (lastTime + (fileSize / 8) <= lastNoteTime + maxTailMs) {
						continue;
					}

					// 确实有可能更新 maxTail，再进行昂贵的 Header 解析
					int dur = bms.player.beatoraja.audio.PCM.getWavDurationMs(audioFile.getPath());
					checkCount++;
					if (dur > 0) {
						final int tailEnd = lastTime + dur;
						if (tailEnd > lastNoteTime) {
							maxTailMs = Math.max(maxTailMs, tailEnd - lastNoteTime);
						}
					}
				}
			}

			resource.getSongdata().setTail(maxTailMs);
			main.getSongDatabase().updateSongTail(model.getSHA256(), maxTailMs);
			Logger.getGlobal().info(String.format("Audio tail check finished: %d ms, unique wavs: %d, checked: %d, maxTail: %d ms",
					System.currentTimeMillis() - startTime, uniqueWavs, checkCount, maxTailMs));
		} else {
			Logger.getGlobal().info("Audio tail loaded from database: " + maxTailMs + " ms");
		}
		lastNoteEndTime = lastTimeMs;
		// 确保播放时长同时覆盖音频尾部和所有 BGA/BGM 事件
		playtime = Math.max(lastEventTime + 1000, lastNoteTime + maxTailMs);
		Logger.getGlobal().info("[BMSPlayer] Calculated playtime: " + playtime + " ms, maxTailMs: " + maxTailMs + ", lastNoteTime: " + lastNoteTime + ", lastEventTime: " + lastEventTime);

		if (autoplay.mode == BMSPlayerMode.Mode.PLAY || autoplay.mode == BMSPlayerMode.Mode.AUTOPLAY) {
			if (config.isBpmguide() && (model.getMinBPM() < model.getMaxBPM())) {
				// BPM変化がなければBPMガイドなし
				assist = Math.max(assist, 1);
				score = false;
			}

			if (config.isCustomJudge() &&
					(config.getKeyJudgeWindowRatePerfectGreat() > 100 || config.getKeyJudgeWindowRateGreat() > 100 || config.getKeyJudgeWindowRateGood() > 100
					|| config.getScratchJudgeWindowRatePerfectGreat() > 100 || config.getScratchJudgeWindowRateGreat() > 100 || config.getScratchJudgeWindowRateGood() > 100)) {
				assist = Math.max(assist, 2);
				score = false;
			}

			Array<PatternModifier> mods = new Array<PatternModifier>();

			if(config.getScrollMode() > 0) {
				mods.add(new ScrollSpeedModifier(config.getScrollMode() - 1, config.getScrollSection(), config.getScrollRate()));
			}
			if(config.getLongnoteMode() > 0) {
				mods.add(new LongNoteModifier(config.getLongnoteMode() - 1, config.getLongnoteRate()));
			}
			if(config.getMineMode() > 0) {
				mods.add(new MineNoteModifier(config.getMineMode() - 1));
			}
			if(config.getExtranoteDepth() > 0) {
				mods.add(new ExtraNoteModifier(config.getExtranoteType(), config.getExtranoteDepth(), config.isExtranoteScratch()));
			}

			for(PatternModifier mod : mods) {
				mod.modify(model);
				if(mod.getAssistLevel() != PatternModifier.AssistLevel.NONE) {
					assist = Math.max(assist, mod.getAssistLevel() == PatternModifier.AssistLevel.ASSIST ? 2 : 1);
					score = false;
				}
			}

			if (playinfo.doubleoption >= 2) {
				if(model.getMode() == Mode.BEAT_5K || model.getMode() == Mode.BEAT_7K || model.getMode() == Mode.KEYBOARD_24K) {
					switch (model.getMode()) {
						case BEAT_5K -> model.setMode(Mode.BEAT_10K);
						case BEAT_7K -> model.setMode(Mode.BEAT_14K);
						case KEYBOARD_24K -> model.setMode(Mode.KEYBOARD_24K_DOUBLE);
					}
					LaneShuffleModifier mod = new PlayerBattleModifier();
					mod.modify(model);
					if(playinfo.doubleoption == 3) {
						PatternModifier as = new AutoplayModifier(model.getMode().scratchKey);
						as.modify(model);
					}
					assist = Math.max(assist, 1);
					score = false;
					Logger.getGlobal().info("譜面オプション : BATTLE (L-ASSIST)");
				} else {
					// SPでなければBATTLEは未適用
					playinfo.doubleoption = 0;
				}
			}
		}

		Logger.getGlobal().info("譜面オプション設定");
		if (replay != null && replay.pattern != null) {
			// リプレイ譜面再現(PatternModifyLog使用。旧verとの互換性維持用)
			if(replay.sevenToNinePattern > 0 && model.getMode() == Mode.BEAT_7K) {
				model.setMode(Mode.POPN_9K);
			}
			PatternModifier.modify(model, Arrays.asList(replay.pattern));
			Logger.getGlobal().info("リプレイデータから譜面再現 : PatternModifyLog");
		} else if (autoplay.mode != BMSPlayerMode.Mode.PRACTICE) {

			// リプレイデータからのoption/seed再現
			ReplayData rd = null;
			if(replay != null) {
				rd = replay;
				Logger.getGlobal().info("リプレイデータから譜面再現 : option/seed");
			} else if(resource.getReplayData().randomoptionseed != -1) {
				rd = resource.getReplayData();
				Logger.getGlobal().info("前回プレイ時の譜面再現");
			}
			if (rd != null) {
				if(rd.sevenToNinePattern > 0 && model.getMode() == Mode.BEAT_7K) {
					model.setMode(Mode.POPN_9K);
				}
				playinfo.randomoption = rd.randomoption;
				playinfo.randomoptionseed = rd.randomoptionseed;
				playinfo.randomoption2 = rd.randomoption2;
				playinfo.randomoption2seed = rd.randomoption2seed;
				playinfo.doubleoption = rd.doubleoption;
			}

			Array<PatternModifier> mods = new Array<PatternModifier>();
			// DP譜面オプション
			if(model.getMode().player == 2) {
				if (playinfo.doubleoption == 1) {
					mods.add(new PlayerFlipModifier());
				}
				Logger.getGlobal().info("譜面オプション(DP) :  " + playinfo.doubleoption);

				PatternModifier pm = PatternModifier.create(playinfo.randomoption2, 1, model.getMode(), config);
				if(playinfo.randomoption2seed != -1) {
					pm.setSeed(playinfo.randomoption2seed);
				} else {
					playinfo.randomoption2seed = pm.getSeed();
				}
				mods.add(pm);
				Logger.getGlobal().info("譜面オプション(2P) :  " + playinfo.randomoption2 + ", Seed : " + playinfo.randomoption2seed);
			}

			// SP譜面オプション
			PatternModifier pm = PatternModifier.create(playinfo.randomoption, 0, model.getMode(), config);
			if(playinfo.randomoptionseed != -1) {
				pm.setSeed(playinfo.randomoptionseed);
			} else {
				playinfo.randomoptionseed = pm.getSeed();
			}
			mods.add(pm);
			Logger.getGlobal().info("譜面オプション(1P) :  " + playinfo.randomoption + ", Seed : " + playinfo.randomoptionseed);

			if (config.getSevenToNinePattern() >= 1 && model.getMode() == Mode.BEAT_7K) {
				//7to9
				ModeModifier mod = new ModeModifier(Mode.BEAT_7K, Mode.POPN_9K, config);
				mods.add(mod);
			}

			int[][] patternArray = new int[model.getMode().player][];

			for(PatternModifier mod : mods) {
				mod.modify(model);
				if(mod.getAssistLevel() != PatternModifier.AssistLevel.NONE) {
					Logger.getGlobal().info("アシスト譜面オプションが選択されました");
					assist = Math.max(assist, mod.getAssistLevel() == PatternModifier.AssistLevel.ASSIST ? 2 : 1);
					score = false;
				}

				if (mod instanceof LaneShuffleModifier lmod){
					if(lmod.isToDisplay()){
						patternArray[lmod.player] = lmod.getRandomPattern(model.getMode());
					}
				}
			}
//			playinfo.pattern = pattern.toArray(new PatternModifyLog[pattern.size()]);
			playinfo.laneShufflePattern = patternArray;

		}

		if(HSReplay != null && HSReplay.config != null) {
			//保存されたHSオプションログからHSオプション再現
			config.getPlayConfig(model.getMode()).setPlayconfig(HSReplay.config);
		}

		Logger.getGlobal().info("ゲージ設定");
		if(replay != null) {
			for(int count = (main.getInputProcessor().getKeyState(5) ? 1 : 0) + (main.getInputProcessor().getKeyState(3) ? 2 : 0);count > 0; count--) {
				if (replay.gauge != GrooveGauge.HAZARD || replay.gauge != GrooveGauge.EXHARDCLASS) {
					replay.gauge++;
				}
			}
		}
		if(replay != null && main.getInputProcessor().getKeyState(5)) {
		}
		// プレイゲージ、初期値設定
		gauge = GrooveGauge.create(model, replay != null ? replay.gauge : config.getGauge(), resource);
		// ゲージログ初期化
		gaugelog = new FloatArray[gauge.getGaugeTypeLength()];
		for(int i = 0; i < gaugelog.length; i++) {
			gaugelog[i] = new FloatArray(playtime / 500 + 2);
		}

		Logger.getGlobal().info("アシストレベル : " + assist + " - スコア保存 : " + score);

		resource.setUpdateScore(score);
		resource.setUpdateCourseScore(resource.isUpdateCourseScore() && score);
		final int difficulty = resource.getSongdata() != null ? resource.getSongdata().getDifficulty() : 0;
		resource.getSongdata().setBMSModel(model);
		resource.getSongdata().setDifficulty(difficulty);
	}

	public SkinType getSkinType() {
		for(SkinType type : SkinType.values()) {
			if(type.getMode() == model.getMode()) {
				return type;
			}
		}
		return null;
	}

	public void create() {
		// 不再调用 System.gc()：双重调用是民间偏方,无证据表明 ART 上叠加有效;
		// 强制 Full GC 反而会引发长 STW 暂停,正是它想避免的帧率抖动来源。
		// 真实需求"减少 GC 停顿"应通过对象池/复用对象解决,而非手动触发 GC。

		// 游戏界面开启持续渲染，确保流畅帧率
		Gdx.graphics.setContinuousRendering(true);

		FileCache.clear();
		final BMSPlayerMode autoplay = resource.getPlayMode();
		laneProperty = new LaneProperty(model.getMode());
		keysound = new KeySoundProcessor(this);
		judge = new JudgeManager(this);
		control = new ControlInputProcessor(this, autoplay);
		keyinput = new KeyInputProccessor(this, laneProperty);
		PlayerConfig config = resource.getPlayerConfig();

		// 先初始化 lanerender，这样在加载皮肤时 SkinNote 准備時就能找到它
		final BMSPlayerInputProcessor input = main.getInputProcessor();
		if(autoplay.mode == BMSPlayerMode.Mode.PLAY || autoplay.mode == BMSPlayerMode.Mode.PRACTICE) {
			input.setPlayConfig(config.getPlayConfig(model.getMode()));
		} else if (autoplay.mode == BMSPlayerMode.Mode.AUTOPLAY || autoplay.mode == BMSPlayerMode.Mode.REPLAY) {
			input.setEnable(false);
		}
		lanerender = new LaneRenderer(this, model);

		// 初始化触摸按键映射（仅Android平台）
		Gdx.app.log("BMSPlayer", "Platform type: " + com.badlogic.gdx.Gdx.app.getType());
		if (com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android) {
			try {
				Gdx.app.log("BMSPlayer", "Initializing touch key mapper for Android...");
				touchKeyMapper = new PlayTouchKeyMapper(
					this,
					main.getConfig().getResolution(),
					input,
					null
				);
				// 注入 LaneProperty（需要等 laneProperty 初始化后才能设置）
				touchKeyMapper.setLaneProperty(laneProperty);
				// 根据全局设置启用/禁用触摸按键
				touchKeyMapper.setEnabled(main.getConfig().isShowTouchKey());
				Gdx.app.log("BMSPlayer", "Touch key mapper initialized successfully, enabled: " + touchKeyMapper.isEnabled());
			} catch (Exception e) {
				Gdx.app.log("BMSPlayer", "Failed to initialize touch key mapper: " + e.getMessage());
				e.printStackTrace();
			}
		} else {
			Gdx.app.log("BMSPlayer", "Not Android platform, skipping touch key mapper initialization");
		}

		loadSkin(getSkinType());
		syncSkinDerivedState();

		final SystemSoundManager.SoundType[] guideses = {GUIDESE_PG,GUIDESE_GR,GUIDESE_GD,GUIDESE_BD,GUIDESE_PR,GUIDESE_MS};
		for(int i = 0;i < 6;i++) {
			if(config.isGuideSE()) {
				File[] paths = main.getSoundManager().getSoundPaths(guideses[i]);
				if(paths.length > 0) {
					main.getAudioProcessor().setAdditionalKeySound(i, true, paths[0].getPath());
					main.getAudioProcessor().setAdditionalKeySound(i, false, paths[0].getPath());
				}
			} else {
				main.getAudioProcessor().setAdditionalKeySound(i, true, null);
				main.getAudioProcessor().setAdditionalKeySound(i, false, null);
			}
		}
		for (CourseData.CourseDataConstraint i : resource.getConstraint()) {
			if (i == NO_SPEED) {
				control.setEnableControl(false);
				break;
			}
		}

		judge.init(model, resource);

		rhythm = new RhythmTimerProcessor(model,
				(getSkin() instanceof PlaySkin) ? ((PlaySkin) getSkin()).getNoteExpansionRate()[0] != 100 || ((PlaySkin) getSkin()).getNoteExpansionRate()[1] != 100 : false);

		bga = resource.getBGAManager();

		ScoreData score = main.getPlayDataAccessor().readScoreData(model, config.getLnmode());
		Logger.getGlobal().info("スコアデータベースからスコア取得");
		if (score == null) {
			score = new ScoreData();
		}

		if (autoplay.mode == BMSPlayerMode.Mode.PRACTICE) {
			getScoreDataProperty().setTargetScore(0, null, 0, null, model.getTotalNotes());
			practice.create(model, this.main);
			// 新しい練習セッションなので事前生成の状態を捨てる
			resetPracticePregen();
			state = STATE_PRACTICE;
		} else {

			if(resource.getRivalScoreData() == null || resource.getCourseBMSModels() != null) {
				ScoreData targetScore = TargetProperty.getTargetProperty(config.getTargetid()).getTarget(main);
				resource.setTargetScoreData(targetScore);
			} else {
				resource.setTargetScoreData(resource.getRivalScoreData());
			}
			getScoreDataProperty().setTargetScore(score.getExscore(), score.decodeGhost(), resource.getTargetScoreData() != null ? resource.getTargetScoreData().getExscore() : 0 , null, model.getTotalNotes());
		}
	}

	/**
	 * 皮肤被「不切状态地热替换」之后的补做（{@link MainState#onSkinReloaded()}，
	 * 调用方 = 皮肤调整窗口 {@code FloatingMenu.reloadCurrentSkin()}）。
	 *
	 * <p>🔴 存在的理由：下面的状态都是<b>从皮肤派生、但存在皮肤之外</b>的对象里，
	 * {@code setSkin()} / {@code skin.prepare()} 一个都不会碰，所以「换皮肤」时能自动跟上，
	 * 「切 Layout」时就会留在旧布局上 —— 必须显式再同步一次。</p>
	 */
	@Override
	public void onSkinReloaded() {
		syncSkinDerivedState();
	}

	/**
	 * 把「当前皮肤」里派生出来的游玩态设置同步给持有它们的对象。
	 *
	 * <p>{@link #create()} 里加载完皮肤后会调一次；皮肤热重载（skin adjust）后还要再调一次。</p>
	 *
	 * <ul>
	 *   <li><b>{@code BGAProcessor.isPortrait}</b>：竖屏下 BGA 要旋转 270°。而
	 *       {@code LaneRenderer} 在触摸皮肤上会把 BGA 帧<b>按轨道区域裁一块当轨道背景</b>
	 *       （lane_darkness）→ 这个标志不同步，表现就是「lane 那一块显示错乱」，
	 *       且必须重进 PLAY 才恢复。</li>
	 *   <li><b>{@code PlayTouchKeyMapper} 的按键区域</b>：它的 render 每帧会重算，
	 *       这里只是让它立刻按新皮肤重算一次（换 Layout 时轨道方向整个变了）。</li>
	 * </ul>
	 *
	 * <p>⚠️ 故意<b>不</b>重建 {@code rhythm}：音符扩张率确实也是皮肤参数，但
	 * {@link RhythmTimerProcessor} 内部带着正在跑的推进状态（sections / rhythmtimer），
	 * 重建会把节拍计时重置；而扩张率跟 Layout 无关，换皮肤时它本来就是新文件、无影响。</p>
	 */
	private void syncSkinDerivedState() {
		// 根据皮肤配置检测是否为竖屏模式，并同步给 BGA 处理器
		if (resource.getBGAManager() != null) {
			boolean isPortrait = false;
			Skin currentSkin = getSkin();
			if (currentSkin != null && currentSkin.header != null) {
				for (bms.player.beatoraja.skin.SkinHeader.CustomOption co : currentSkin.header.getCustomOptions()) {
					if (co.name.equals("Layout") && co.getSelectedOption() == 1101) {
						isPortrait = true;
						break;
					}
				}
			}
			if (!isPortrait && currentSkin != null && currentSkin.getOption() != null) {
				for (com.badlogic.gdx.utils.IntIntMap.Entry e : currentSkin.getOption()) {
					if (e.value == 1101) {
						isPortrait = true;
						break;
					}
				}
			}
			Gdx.app.log("BMSPlayer", "Portrait detection: isPortrait=" + isPortrait);
			resource.getBGAManager().setPortrait(isPortrait);
		}

		// 根据 skin 的 laneregion 更新触摸按键区域（仅 Android 平台）
		if (touchKeyMapper != null) {
			touchKeyMapper.updateRegionsFromSkin();
		}
	}

	/**
	 * 練習メニュー中の復帰再生用音源の事前生成を進める。
	 *
	 * <p>練習では開始位置を跨いでまだ鳴っている長いBGM(例: 0:40 から 0:58 まで鳴る音源を
	 * 0:50 から練習するケース)の復帰再生用音源を作る必要がある。この生成は
	 * 「PCMデコード → 一時WAV書き出し → nativeでの再デコード」で、長い曲では2〜4秒かかる。
	 * 開始(READY)と同時に始めると、その間 STATE_PLAY に入れない(＝開始が待たされる)。
	 * ここで開始位置が確定した時点で先に作り始めておき、開始時は生成済みのものを鳴らす。
	 *
	 * <p>開始位置は右/左キーの押しっぱなしで連続して変わるので、変化が
	 * {@link #PRACTICE_PREGEN_SETTLE_US} 止まってから起動する(途中の位置で走らせても
	 * 捨てるだけ)。また再生速度(freq)を変えると model の時間軸そのものが変わるため、
	 * その時は事前生成しない(開始時に作り直す)。
	 */
	private void updatePracticePregen(long micronow) {
		final PracticeProperty property = practice.getPracticeProperty();
		if (!resource.mediaLoadFinished() || property.freq != 100) {
			return;
		}
		if (property.starttime != practicePregenWatched) {
			practicePregenWatched = property.starttime;
			practicePregenChangedMicro = micronow;
			return;
		}
		// practicePregenChangedMicro == 0 は「この練習セッションでまだ一度も開始位置を
		// いじっていない」＝復元された既定値のまま。この場合は調整中ではないので即座に始める
		if ((practicePregenChangedMicro != 0
				&& micronow - practicePregenChangedMicro < PRACTICE_PREGEN_SETTLE_US)
				|| practicePregenDoneFor == practicePregenWatched) {
			return;
		}
		practicePregenDoneFor = practicePregenWatched;
		// 開始位置の計算は STATE_PRACTICE の開始処理と揃えること
		final long pregenStarttime = (practicePregenWatched > 1000 ? practicePregenWatched - 1000 : 0) * 1000L;
		Logger.getGlobal().info("[BGRESUME] practice menu pregen. starttime=" + pregenStarttime + "us");
		keysound.prepareBGPlayInPracticeMenu(model, pregenStarttime);
	}

	/**
	 * 練習メニューの事前生成状態を捨てる(新しい曲・新しい練習セッション用)。
	 *
	 * <p>「まだ一度も開始位置をいじっていない」状態として現在値を引き継ぐ。
	 * 練習設定ファイルから復元された既定の開始位置はそのまま使われることが多く、
	 * その場合に 500ms 待ってから作り始める理由が無いため。
	 */
	private void resetPracticePregen() {
		practicePregenWatched = practice.getPracticeProperty().starttime;
		practicePregenChangedMicro = 0;
		practicePregenDoneFor = Integer.MIN_VALUE;
	}

	@Override
	public void render() {
		final PlaySkin skin = (PlaySkin) getSkin();
		if(skin == null) {
			main.changeState(MainStateType.MUSICSELECT);
			return;
		}
		final BMSPlayerMode autoplay = resource.getPlayMode();
		final BMSPlayerInputProcessor input = main.getInputProcessor();
		final PlayerConfig config = resource.getPlayerConfig();

		final long micronow = timer.getNowMicroTime();

		if(micronow > skin.getInput() * 1000){
			timer.switchTimer(TIMER_STARTINPUT, true);
		}
		if(input.startPressed() || input.isSelectPressed()){
			startpressedtime = micronow;
		}

		// 注意：触摸按键のレンダリング已移至MainController中进行，确保在皮肤之后绘制


		switch (state) {
		// 楽曲ロード
			case STATE_PRELOAD -> {
				if(config.isChartPreview()) {
					if(timer.isTimerOn(141) && micronow > startpressedtime) {
						timer.setTimerOff(141);
						lanerender.init(model);
					} else if(!timer.isTimerOn(141) && micronow == startpressedtime){
						timer.setMicroTimer(141, micronow - starttimeoffset * 1000);
					}
				}

				if (resource.mediaLoadFinished() && !bgaPrepared) {
					bga.prepare(this);
					bgaPrepared = true;
				}

				if (resource.mediaLoadFinished() && bgaPrepared && micronow > (skin.getLoadstart() + skin.getLoadend()) * 1000
						&& micronow - startpressedtime > 1000000) {
					if(config.isChartPreview()) {
						timer.setTimerOff(141);
						lanerender.init(model);
					}
					// 不再调用 System.gc()：强制 GC 之前/之后对比 freeMemory 会把软引用、终结器队列对象
					// 一起回收,得到的 "disposed" 数字虚高,污染了真实内存观测。这里只输出当前 freeMemory。
					final long mem = Runtime.getRuntime().freeMemory();
					Logger.getGlobal().info("current free memory : " + (mem / (1024 * 1024)) + "MB");
					state = STATE_READY;
					timer.setTimerOn(TIMER_READY);
					play(PLAY_READY);
					Logger.getGlobal().info("STATE_READYに移行");
				}
				if(!timer.isTimerOn(TIMER_PM_CHARA_1P_NEUTRAL) || !timer.isTimerOn(TIMER_PM_CHARA_2P_NEUTRAL)){
					timer.setTimerOn(TIMER_PM_CHARA_1P_NEUTRAL);
					timer.setTimerOn(TIMER_PM_CHARA_2P_NEUTRAL);
				}
			}
			// practice mode
			case STATE_PRACTICE -> {
				if (timer.isTimerOn(TIMER_PLAY)) {
					resource.reloadBMSFile();
					model = resource.getBMSModel();
					resource.getSongdata().setBMSModel(model);
					lanerender.init(model);
					keyinput.setKeyBeamStop(false);
					// 曲を読み直すと model が別インスタンスになる。事前生成中の復帰再生音源は
					// その model 前提なので捨てて、新しい model で作り直させる
					keysound.stopBGPlay();
					resetPracticePregen();
					timer.setTimerOff(TIMER_PLAY);
					timer.setTimerOff(TIMER_RHYTHM);
					timer.setTimerOff(TIMER_FAILED);
					timer.setTimerOff(TIMER_FADEOUT);
					timer.setTimerOff(TIMER_ENDOFNOTE_1P);

					for(int i = TIMER_PM_CHARA_1P_NEUTRAL; i <= TIMER_PM_CHARA_DANCE; i++) timer.setTimerOff(i);
				}
				if(!timer.isTimerOn(TIMER_PM_CHARA_1P_NEUTRAL) || !timer.isTimerOn(TIMER_PM_CHARA_2P_NEUTRAL)){
					timer.setTimerOn(TIMER_PM_CHARA_1P_NEUTRAL);
					timer.setTimerOn(TIMER_PM_CHARA_2P_NEUTRAL);
				}
				control.setEnableControl(false);
				control.setEnableCursor(false);
				practice.processInput(input);
				// 開始位置を跨ぐ長いBGMの復帰再生用音源は、ここで先に作り始めておく。
				// 開始(READY)と同時に作り始めると、長い曲では生成に2〜4秒かかり、
				// その間 STATE_PLAY に入れない(＝開始が待たされる)。
				updatePracticePregen(micronow);

				if (input.getKeyState(0) && resource.mediaLoadFinished() &&  micronow > (skin.getLoadstart() + skin.getLoadend()) * 1000
						&& micronow - startpressedtime > 1000000) {
					PracticeProperty property = practice.getPracticeProperty();
					control.setEnableControl(true);
					control.setEnableCursor(true);
					if (property.freq != 100) {
						BMSModelUtils.changeFrequency(model, property.freq / 100f);
						if (main.getConfig().getAudioConfig().getFreqOption() == FrequencyType.FREQUENCY) {
							main.getAudioProcessor().setGlobalPitch(property.freq / 100f);
						}
					}
					model.setTotal(property.total);
					PracticeModifier pm = new PracticeModifier(property.starttime * 100 / property.freq,
							property.endtime * 100 / property.freq);
					pm.modify(model);
					if (model.getMode().player == 2) {
						if (property.doubleop == 1) {
							new PlayerFlipModifier().modify(model);
						}
						PatternModifier.create(property.random2, 1, model.getMode(), config).modify(model);
					}
					PatternModifier.create(property.random, 0, model.getMode(), config).modify(model);

					gauge = practice.getGauge(model);
					model.setJudgerank(property.judgerank);
					lanerender.init(model);
					judge.init(model, resource);
					skin.pomyu.init();
					starttimeoffset = (property.starttime > 1000 ? property.starttime - 1000 : 0) * 100 / property.freq;
					// Practice模式也使用动态tail：最后一note结束后留500ms（若maxTailMs > 500则用maxTailMs）
					int practiceTail = Math.max(5000, maxTailMs);
					playtime = (property.endtime + practiceTail) * 100 / property.freq;
					bga.prepare(this);
					// 開始位置を跨ぐ長いBGMの復帰再生用音源を、プレイ開始前に生成しておく。
					// 生成には1秒以上かかることがあり、プレイ開始後にやるとBGMが譜面から遅れて鳴る。
					// STATE_READY はこの生成が終わるまで STATE_PLAY へ進まない。
					keysound.prepareBGPlay(model, starttimeoffset * 1000);
					state = STATE_READY;
					timer.setTimerOn(TIMER_READY);
					play(PLAY_READY);
					Logger.getGlobal().info("STATE_READYに移行");
				}
			}
			// practice終了
			case STATE_PRACTICE_FINISHED -> {
				// 練習メニューから抜ける経路(STARTせずにESCAPEした場合もここへ来る)。
				// 事前生成スレッドが再生開始の合図待ちのまま残らないよう止める
				keysound.stopBGPlay();
				if (timer.getNowTime(TIMER_FADEOUT) > skin.getFadeout()) {
					input.setEnable(true);
					input.setStartTime(0);
					main.changeState(MainStateType.MUSICSELECT);
				}
			}
			// GET READY
			case STATE_READY -> {
				if (timer.getNowTime(TIMER_READY) > skin.getPlaystart()) {
					// 練習モードの復帰再生用音源の生成が終わるまではプレイを始めない。生成は開始位置が
					// 確定した時点(prepareBGPlay)から並行して走っているので、通常ここでは待たない。
					if (!keysound.isBGResumePrepared()) {
						break;
					}
					replayConfig = lanerender.getPlayConfig().clone();
					state = STATE_PLAY;
					timer.setMicroTimer(TIMER_PLAY, micronow - starttimeoffset * 1000);
					timer.setMicroTimer(TIMER_RHYTHM, micronow - starttimeoffset * 1000);

					input.setStartTime(micronow + timer.getStartMicroTime() - starttimeoffset * 1000);
					input.setKeyLogMarginTime(resource.getMarginTime());
					keyinput.startJudge(model, replay != null ? replay.keylog : null, resource.getMarginTime());
					keysound.startBGPlay(model, starttimeoffset * 1000);
					Logger.getGlobal().info("STATE_PLAYに移行");
				}
			}
			// プレイ
			case STATE_PLAY -> {
				final long deltatime = micronow - prevtime;
				PracticeProperty property = practice.getPracticeProperty();

				rhythm.update(this, deltatime, lanerender.getNowBPM(), property.freq);

				final long ptime = timer.getNowTime(TIMER_PLAY);
				float g = gauge.getValue();
				for(int i = 0; i < gaugelog.length; i++) {
					int idx = (int) (ptime / 500);
					if (gaugelog[i].size <= idx) {
						gaugelog[i].ensureCapacity(idx + 1);
						gaugelog[i].items[gaugelog[i].size++] = gauge.getValue(i);
					}
				}
				timer.switchTimer(TIMER_GAUGE_MAX_1P, gauge.getGauge().isMax());

				skin.pomyu.updateTimer(this);

				// System.out.println("playing time : " + time);
				if (playtime < ptime) {
					state = STATE_FINISHED;
					if (autoplay.mode == BMSPlayerMode.Mode.PRACTICE && resource.mediaLoadFinished()) {
						// 練習はここから STATE_PRACTICE へ戻る経路で stop((Note)null) を通らない。
						// 復帰再生した長いBGMはスライス音源で wavmap/soundmap に無いため、
						// ここで止めないと練習メニューに戻っても鳴り続ける(音源ファイル末尾まで)。
						main.getAudioProcessor().stop((Note) null);
					}
					if (resource.getPlayMode().mode == BMSPlayerMode.Mode.AUTOPLAY) {
						timer.setTimerOn(TIMER_FADEOUT);
					} else {
						timer.switchTimer(TIMER_ENDOFNOTE_1P, true);
						for(int i = TIMER_PM_CHARA_1P_NEUTRAL; i <= TIMER_PM_CHARA_2P_BAD; i++) {
							timer.setTimerOff(i);
						}
						timer.setTimerOff(TIMER_PM_CHARA_DANCE);
					}
					Logger.getGlobal().info("STATE_FINISHEDに移行");
				} else if(lastNoteEndTime < ptime) {
					if(!timer.isTimerOn(TIMER_PLAY_NOTE_END)) {
						timer.setTimerOn(TIMER_PLAY_NOTE_END);
					}
					timer.switchTimer(TIMER_ENDOFNOTE_1P, true);
				}
				// stage failed判定
				if (config.getGaugeAutoShift() == PlayerConfig.GAUGEAUTOSHIFT_BESTCLEAR || config.getGaugeAutoShift() == PlayerConfig.GAUGEAUTOSHIFT_SELECT_TO_UNDER) {
					final int len = config.getGaugeAutoShift() == PlayerConfig.GAUGEAUTOSHIFT_BESTCLEAR
							? (gauge.getType() >= GrooveGauge.CLASS ? GrooveGauge.EXHARDCLASS + 1 : GrooveGauge.HAZARD + 1)
							: (gauge.isCourseGauge() ? Math.min(Math.max(config.getGauge(), GrooveGauge.NORMAL) + GrooveGauge.CLASS - GrooveGauge.NORMAL, GrooveGauge.EXHARDCLASS) + 1 : config.getGauge() + 1);
					int type = gauge.isCourseGauge() ? GrooveGauge.CLASS
							: gauge.getType() < config.getBottomShiftableGauge() ? gauge.getType() : config.getBottomShiftableGauge();
					for (int i = type; i < len; i++) {
						if (gauge.getGauge(i).getValue() > 0f && gauge.getGauge(i).isQualified()) {
							type = i;
						}
					}
					gauge.setType(type);
				} else if (g == 0) {
					switch(config.getGaugeAutoShift()) {
					case PlayerConfig.GAUGEAUTOSHIFT_NONE:
						// FAILED移行
						state = STATE_FAILED;
						timer.setTimerOn(TIMER_FAILED);
						if (resource.mediaLoadFinished()) {
							main.getAudioProcessor().stop((Note) null);
						}
						play(PLAY_STOP);
						Logger.getGlobal().info("STATE_FAILEDに移行");
						break;
					case PlayerConfig.GAUGEAUTOSHIFT_CONTINUE:
						break;
					case PlayerConfig.GAUGEAUTOSHIFT_SURVIVAL_TO_GROOVE:
						if(!gauge.isCourseGauge()) {
							// GAS処理
							gauge.setType(GrooveGauge.NORMAL);
						}
						break;
					}
				}
			}
			// 閉店処理
			case STATE_FAILED -> {
				keyinput.stopJudge();
				keysound.stopBGPlay();
				if ((input.startPressed() ^ input.isSelectPressed()) && resource.getCourseBMSModels() == null
						&& autoplay.mode == BMSPlayerMode.Mode.PLAY) {
					if (!resource.isUpdateScore()) {
						resource.getReplayData().randomoptionseed = -1;
						Logger.getGlobal().info("アシストモード時は同じ譜面でリプレイできません");
					} else if (input.startPressed()) {
						resource.getReplayData().randomoptionseed = -1;
						Logger.getGlobal().info("オプションを変更せずリプレイ");
					} else {
						resource.setScoreData(createScoreData());
						Logger.getGlobal().info("同じ譜面でリプレイ");
					}
					saveConfig();
					resource.reloadBMSFile();
					main.changeState(MainStateType.PLAY);
				} else if (timer.getNowTime(TIMER_FAILED) > skin.getClose()) {
					main.getAudioProcessor().setGlobalPitch(1f);
					if (resource.mediaLoadFinished()) {
						resource.getBGAManager().stop();
					}
					if (autoplay.mode == BMSPlayerMode.Mode.PLAY || autoplay.mode == BMSPlayerMode.Mode.REPLAY) {
						resource.setScoreData(createScoreData());
					}
					resource.setCombo(judge.getCourseCombo());
					resource.setMaxcombo(judge.getCourseMaxcombo());
					saveConfig();
					if (timer.isTimerOn(TIMER_PLAY)) {
						for (long l = timer.getTimer(TIMER_FAILED) - timer.getTimer(TIMER_PLAY); l < playtime + 500; l += 500) {
							for(int i = 0; i < gaugelog.length; i++) {
								int idx = (int) (l / 500);
								if (gaugelog[i].size <= idx) {
									gaugelog[i].ensureCapacity(idx + 1);
									gaugelog[i].items[gaugelog[i].size++] = 0f;
								}
							}
						}
					}
					resource.setGauge(gaugelog);
					resource.setGrooveGauge(gauge);
					resource.setAssist(assist);
					input.setEnable(true);
					input.setStartTime(0);
					if (autoplay.mode == BMSPlayerMode.Mode.PRACTICE) {
						state = STATE_PRACTICE;
					} else if (resource.getScoreData() != null) {
						main.changeState(MainStateType.RESULT);
					} else {
						main.changeState(MainStateType.MUSICSELECT);
					}
				}
			}
			// 完奏処理
			case STATE_FINISHED -> {
				keyinput.stopJudge();
				keysound.stopBGPlay();
				if (timer.getNowTime(TIMER_PLAY_NOTE_END) > 0 || timer.getNowTime(TIMER_ENDOFNOTE_1P) > 0) {
					timer.switchTimer(TIMER_FADEOUT, true);
				}
				if (timer.getNowTime(TIMER_FADEOUT) > skin.getFadeout()) {
					// [DEBUG PROBE] BMSPlayer→RESULT 过渡开始
				// bms.player.beatoraja.result.debug.ResultFreezeDiagnostics.log("bmsp:transition start");
					main.getAudioProcessor().setGlobalPitch(1f);
					resource.getBGAManager().stop();
					// [DEBUG PROBE] BGA 已停止
				// bms.player.beatoraja.result.debug.ResultFreezeDiagnostics.log("bmsp:bga stopped");

					if (autoplay.mode == BMSPlayerMode.Mode.PLAY || autoplay.mode == BMSPlayerMode.Mode.REPLAY) {
						resource.setScoreData(createScoreData());
					}
					resource.setCombo(judge.getCourseCombo());
					resource.setMaxcombo(judge.getCourseMaxcombo());
					saveConfig();
					// [DEBUG PROBE] Config 已保存
				// bms.player.beatoraja.result.debug.ResultFreezeDiagnostics.log("bmsp:config saved");
					resource.setGauge(gaugelog);
					resource.setGrooveGauge(gauge);
					resource.setAssist(assist);
					input.setEnable(true);
					input.setStartTime(0);
					if (autoplay.mode == BMSPlayerMode.Mode.PRACTICE) {
						state = STATE_PRACTICE;
					} else if (resource.getScoreData() != null) {
						Logger.getGlobal().info("\"score\": " + resource.getScoreData());
						// [DEBUG PROBE] 调用 changeState(RESULT)
					// bms.player.beatoraja.result.debug.ResultFreezeDiagnostics.log("bmsp:changeState RESULT");
						main.changeState(MainStateType.RESULT);
						// [DEBUG PROBE] changeState 返回
					// bms.player.beatoraja.result.debug.ResultFreezeDiagnostics.log("bmsp:changeState returned");
					} else {
						if (resource.mediaLoadFinished()) {
							main.getAudioProcessor().stop((Note) null);
						}
						if (resource.getCourseBMSModels() != null && resource.nextCourse()) {
							main.changeState(MainStateType.PLAY);
						} else if(resource.nextSong()){
							main.changeState(MainStateType.DECIDE);
						} else {
							main.changeState(MainStateType.MUSICSELECT);
						}
					}
				}
			}
		}

		prevtime = micronow;
	}

	public void setPlaySpeed(int playspeed) {
		this.playspeed = playspeed;
		if (main.getConfig().getAudioConfig().getFastForward() == FrequencyType.FREQUENCY) {
			main.getAudioProcessor().setGlobalPitch(playspeed / 100f);
		}
	}

	public int getPlaySpeed() {
		return playspeed;
	}

	public float getAdjustedVolume() {
		return adjustedVolume;
	}

	public void input() {
		control.input();
		keyinput.input();
	}

	public KeyInputProccessor getKeyinput() {
		return keyinput;
	}

	public int getState() {
		return state;
	}

	public LaneRenderer getLanerender() {
		return lanerender;
	}

	public LaneProperty getLaneProperty() {
		return laneProperty;
	}

	private void saveConfig() {
		for (CourseData.CourseDataConstraint c : resource.getConstraint()) {
			if (c == NO_SPEED) {
				return;
			}
		}
		PlayConfig pc = resource.getPlayerConfig().getPlayConfig(model.getMode()).getPlayconfig();
		if (pc.getFixhispeed() != PlayConfig.FIX_HISPEED_OFF) {
			pc.setDuration(lanerender.getDuration());
		} else {
			pc.setHispeed(lanerender.getHispeed());
		}
		pc.setLanecover(lanerender.getLanecover());
		pc.setLift(lanerender.getLiftRegion());
		pc.setHidden(lanerender.getHiddenCover());
	}

	public ScoreData createScoreData() {
		final PlayerConfig config = resource.getPlayerConfig();
		ScoreData score = judge.getScoreData();
		if (resource.getCourseBMSModels() == null
				&& (score.getEpg() + score.getLpg() + score.getEgr() + score.getLgr() + score.getEgd() + score.getLgd() + score.getEbd() + score.getLbd() == 0)) {
			return null;
		}

		ClearType clear = ClearType.Failed;
		if (state != STATE_FAILED && gauge.isQualified()) {
			if (assist > 0) {
				if(resource.getCourseBMSModels() == null) clear = assist == 1 ? ClearType.LightAssistEasy : ClearType.AssistEasy;
			} else {
				if (judge.getPastNotes() == judge.getCombo()) {
					if (judge.getJudgeCount(2) == 0) {
						if (judge.getJudgeCount(1) == 0) {
							clear = ClearType.Max;
						} else {
							clear = ClearType.Perfect;
						}
					} else {
						clear = ClearType.FullCombo;
					}
				} else if (resource.getCourseBMSModels() == null) {
					clear = gauge.getClearType();
				}
			}
		}
		score.setClear(clear.id);
		score.setGauge(gauge.isTypeChanged() ? -1 : gauge.getType());
		score.setOption(playinfo.randomoption + (model.getMode().player == 2
				? (playinfo.randomoption2 * 10 + playinfo.doubleoption * 100) : 0));
		score.setSeed((model.getMode().player == 2 ? playinfo.randomoption2seed * 65536 * 256 : 0) + playinfo.randomoptionseed);
		score.encodeGhost(judge.getGhost());
		// リプレイデータ保存。スコア保存されない場合はリプレイ保存しない
		final ReplayData replay = resource.getReplayData();
		replay.player = main.getPlayerConfig().getName();
		replay.sha256 = model.getSHA256();
		replay.mode = config.getLnmode();
		replay.date = Calendar.getInstance().getTimeInMillis() / 1000;
		replay.keylog = main.getInputProcessor().getKeyInputLog();
//		replay.pattern = playinfo.pattern;
		replay.laneShufflePattern = playinfo.laneShufflePattern;
		replay.rand = playinfo.rand;
		replay.gauge = config.getGauge();
		replay.sevenToNinePattern = config.getSevenToNinePattern();
		replay.randomoption = playinfo.randomoption;
		replay.randomoptionseed = playinfo.randomoptionseed;
		replay.randomoption2 = playinfo.randomoption2;
		replay.randomoption2seed = playinfo.randomoption2seed;
		replay.doubleoption = playinfo.doubleoption;
		replay.config = replayConfig;

		score.setPassnotes(judge.getPastNotes());
		score.setMinbp(score.getEbd() + score.getLbd() + score.getEpr() + score.getLpr() + score.getEms() + score.getLms() + resource.getSongdata().getNotes() - judge.getPastNotes());

		long count = 0;
		long avgduration = 0;
		final int lanes = model.getMode().key;
		for (TimeLine tl : model.getAllTimeLines()) {
			for (int i = 0; i < lanes; i++) {
				Note n = tl.getNote(i);
				if (n != null && (n instanceof NormalNote || (n instanceof LongNote ln &&
						!(((model.getLntype() == BMSModel.LNTYPE_LONGNOTE && ln.getType() == LongNote.TYPE_UNDEFINED)
								|| ln.getType() == LongNote.TYPE_LONGNOTE)
								&& ((LongNote) n).isEnd())))) {
					int state = n.getState();
					long time = n.getMicroPlayTime();
					avgduration += state >= 1 && state <= 4 ? Math.abs(time) : 1000000;
					count++;
//					System.out.println(time);
				}
			}
		}
		score.setTotalDuration(avgduration);
		score.setAvgjudge(avgduration / count);
//		System.out.println(avgduration + " / " + count + " = " + score.getAvgjudge());

		score.setDeviceType(main.getInputProcessor().getDeviceType());
		score.setSkin(getSkin().header.getName());
		return score;
	}

	public void stopPlay() {
		// ESCAPE 时立即关闭 FINISH 动画计时器，避免在 fadeout 期间继续弹出 FINISH 字样
		timer.setTimerOff(TIMER_PLAY_NOTE_END);
		if (state == STATE_PRACTICE) {
			practice.saveProperty();
			timer.setTimerOn(TIMER_FADEOUT);
			state = STATE_PRACTICE_FINISHED;
			return;
		}
		if (state == STATE_PRELOAD || state == STATE_READY) {
			timer.setTimerOn(TIMER_FADEOUT);
			state = STATE_PRACTICE_FINISHED;
			return;
		}
		if (timer.isTimerOn(TIMER_FAILED) || timer.isTimerOn(TIMER_FADEOUT)) {
			return;
		}
		// AUTOPLAY按ESCAPE时，直接中止，不显示failed/finish
		if (resource.getPlayMode().mode == BMSPlayerMode.Mode.AUTOPLAY) {
			if (resource.mediaLoadFinished()) {
				main.getAudioProcessor().stop((Note) null);
			}
			state = STATE_FINISHED;
			timer.setTimerOn(TIMER_FADEOUT);
			Logger.getGlobal().info("AUTOPLAY ESCAPE: FINISHED→MUSICSELECTに移行");
			return;
		}
		if (state != STATE_FINISHED &&
				judge.getPastNotes() == resource.getSongdata().getNotes()) {
			state = STATE_FINISHED;
			// ESCAPE 提前跳过尾段时，不再重新打开 FINISH 动画计时器（与入口处的 setTimerOff 互为冗余保险）
			timer.setTimerOff(TIMER_PLAY_NOTE_END);
			timer.switchTimer(TIMER_ENDOFNOTE_1P, true);
			Logger.getGlobal().info("STATE_FINISHEDに移行");
		} else if(state == STATE_FINISHED && !timer.isTimerOn(TIMER_FADEOUT)) {
			timer.setTimerOn(TIMER_FADEOUT);
		} else if(state != STATE_FINISHED) {
			state = STATE_FAILED;
			timer.setTimerOn(TIMER_FAILED);
			if (resource.mediaLoadFinished()) {
				main.getAudioProcessor().stop((Note) null);
			}
			play(PLAY_STOP);
			Logger.getGlobal().info("STATE_FAILEDに移行");
		}
	}

	@Override
	public void dispose() {
		super.dispose();
		// BGレーン再生スレッドを止める(復帰再生用音源の生成待ちのまま残さない)
		if (keysound != null) {
			keysound.stopBGPlay();
		}
		lanerender.dispose();
		practice.dispose();
		// 释放触摸按键映射资源
		if (touchKeyMapper != null) {
			touchKeyMapper.dispose();
			touchKeyMapper = null;
		}
		// 退出游戏界面，关闭持续渲染，降低功耗
		Gdx.graphics.setContinuousRendering(false);
		Logger.getGlobal().info("システム描画のリソース解放");
	}

	/**
	 * 切后台/锁屏时暂停 BGA 视频播放。
	 * Android MediaPlayer 对生命周期极敏感，不暂停会导致解码器资源泄漏。
	 */
	@Override
	public void pause() {
		super.pause();
		if (bga != null) {
			bga.pauseAll();
		}
	}

	/**
	 * 切回前台时恢复 BGA 视频播放。
	 */
	@Override
	public void resume() {
		super.resume();
		if (bga != null) {
			bga.resumeAll();
		}
	}

	public PracticeConfiguration getPracticeConfiguration() {
		return practice;
	}

	/** 叠加层用的一次性矩形（避免每帧分配） */
	private final Rectangle practiceOverlayRect = new Rectangle();

	/**
	 * 练习模式参数面板的叠加绘制，由 MainController 在皮肤全部绘制完成之后调用。
	 *
	 * <p>只对触摸版皮肤生效（{@link PracticeConfiguration#isOverlayOnTop}）：它的轨道背景是
	 * 一整块不透明矩形，面板画在 BGA 层会被整块盖住。其他皮肤仍按上游做法由
	 * {@link SkinBGA} 画在 BGA 层（在轨道/notes 之下）。</p>
	 *
	 * <p>区域取整个皮肤范围：触摸版皮肤的 BGA 层本来也是铺满全屏的
	 * （portrait 分支与 landscape 分支都是 0,0,header.w,header.h），所以位置一致。</p>
	 */
	public void drawPracticeOverlay(Skin.SkinObjectRenderer sprite) {
		if (resource == null || resource.getPlayMode() == null
				|| resource.getPlayMode().mode != BMSPlayerMode.Mode.PRACTICE) {
			return;
		}
		// 只在练习配置与演奏过程中叠加。失败 / 通关 / 练习结束后的收尾动画期间不画：
		// 面板画在皮肤之上，会盖住 stage failed、stage clear 这些收尾动画
		// （其他皮肤的面板在 BGA 层，天然会被收尾层盖住，所以只有这里的叠加层需要门控）。
		if (state == STATE_FAILED || state == STATE_FINISHED || state == STATE_PRACTICE_FINISHED) {
			return;
		}
		final Skin skin = getSkin();
		if (!practice.isOverlayOnTop(skin)) {
			return;
		}
		practiceOverlayRect.set(0, 0,
				skin != null ? skin.getWidth() : 1920, skin != null ? skin.getHeight() : 1080);
		practice.draw(practiceOverlayRect, sprite, timer.getNowTime(), this);
	}

	public int getJudgeCount(int judge, boolean fast) {
		return this.judge.getJudgeCount(judge, fast);
	}

	public JudgeManager getJudgeManager() {
		return judge;
	}

	public ReplayData getOptionInformation() {
		return playinfo;
	}

	public void update(int judge, long time) {
		if (this.judge.getCombo() == 0) {
			bga.setMisslayerTme(time);
		}
		gauge.update(judge);
		// System.out.println("Now count : " + notes + " - " + totalnotes);

		//フルコン判定
		timer.switchTimer(TIMER_FULLCOMBO_1P, this.judge.getPastNotes() == resource.getSongdata().getNotes()
				&& this.judge.getPastNotes() == this.judge.getCombo());

		getScoreDataProperty().update(this.judge.getScoreData(), this.judge.getPastNotes());

		timer.switchTimer(TIMER_SCORE_A, getScoreDataProperty().qualifyRank(18));
		timer.switchTimer(TIMER_SCORE_AA, getScoreDataProperty().qualifyRank(21));
		timer.switchTimer(TIMER_SCORE_AAA, getScoreDataProperty().qualifyRank(24));
		timer.switchTimer(TIMER_SCORE_BEST, this.judge.getScoreData().getExscore() >= getScoreDataProperty().getBestScore());
		timer.switchTimer(TIMER_SCORE_TARGET, this.judge.getScoreData().getExscore() >= getScoreDataProperty().getRivalScore());

		((PlaySkin)getSkin()).pomyu.PMcharaJudge = judge + 1;
	}

	public GrooveGauge getGauge() {
		return gauge;
	}

	// ── PlayStateValues 的其余三项：真游玩就是直接转发给 JudgeManager / GrooveGauge。
	//    抽这层接口是为了让皮肤预览也能显示判定与量表（预览里没有真正的 play 会话），
	//    取值与改动前完全一致。见 PlayStateValues 的说明。──

	@Override
	public PlayStateValues getPlayStateValues() {
		return this;
	}

	@Override
	public int getNowJudge(int player) {
		return judge.getNowJudge(player);
	}

	@Override
	public int getNowCombo(int player) {
		return judge.getNowCombo(player);
	}

	public boolean isNoteEnd() {
		return judge.getPastNotes() == resource.getSongdata().getNotes();
	}

	public int getPastNotes() {
		return judge.getPastNotes();
	}

	public int getPlaytime() {
		return playtime;
	}

	public Mode getMode() {
		return model.getMode();
	}

	public long getNowQuarterNoteTime() {
		return rhythm != null ? rhythm.getNowQuarterNoteTime() : 0;
	}
}
