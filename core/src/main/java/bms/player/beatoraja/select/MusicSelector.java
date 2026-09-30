package bms.player.beatoraja.select;

import static bms.player.beatoraja.skin.SkinProperty.*;
import static bms.player.beatoraja.SystemSoundManager.SoundType.*;

import java.io.File;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.*;

import bms.model.BMSModel;
import bms.model.Mode;
import bms.player.beatoraja.*;
import bms.player.beatoraja.Config.SongPreview;
import bms.player.beatoraja.ScoreDatabaseAccessor.ScoreDataCollector;
import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import bms.player.beatoraja.input.KeyCommand;
import bms.player.beatoraja.input.KeyBoardInputProcesseor.ControlKeys;
import bms.player.beatoraja.ir.*;
import bms.player.beatoraja.select.bar.*;
import bms.player.beatoraja.skin.Skin;
import bms.player.beatoraja.skin.SkinLoader;
import bms.player.beatoraja.skin.SkinType;
import bms.player.beatoraja.skin.property.EventFactory.EventType;
import bms.player.beatoraja.song.SongData;
import bms.player.beatoraja.song.SongDatabaseAccessor;

/**
 * 選曲部分。 楽曲一覧とカーソルが指す楽曲のステータスを表示し、選択した楽曲を 曲決定部分に渡す。
 *
 * @author exch
 */
public final class MusicSelector extends MainState implements BarRenderSource {

	// TODO　ミラーランダム段位のスコア表示

	private int selectedreplay;

	/**
	 * 楽曲DBアクセサ
	 */
	private SongDatabaseAccessor songdb;

	public static final Mode[] MODE = { null, Mode.BEAT_7K, Mode.BEAT_14K, Mode.POPN_9K, Mode.BEAT_5K, Mode.BEAT_10K, Mode.KEYBOARD_24K, Mode.KEYBOARD_24K_DOUBLE };

	/**
	 * 保存可能な最大リプレイ数
	 */
	public static final int REPLAY = 4;

	private PlayerConfig config;

	/**
	 * 楽曲プレビュー処理
	 */
	private PreviewMusicProcessor preview;

	/**
	 * 楽曲バー描画用
	 */
	private BarRenderer bar;

	private final BarManager manager = new BarManager(this);

	private MusicSelectInputProcessor musicinput;

	private SearchTextField search;

	/**
	 * 搜索框（原生 EditText）是按哪个皮肤实例创建的。{@code loadSkin()} 每次都会
	 * new 出一个新的 Skin 对象，用它识别"皮肤换了"。
	 */
	private Skin searchFieldSkin;

	/**
	 * 楽曲が選択されてからbmsを読み込むまでの時間(ms)
	 */
	private final int notesGraphDuration = 350;
	/**
	 * 楽曲が選択されてからプレビュー曲を再生するまでの時間(ms)
	 */
	private final int previewDuration = 400;

	private final int rankingDuration = 5000;
	private final int rankingReloadDuration = 10 * 60 * 1000;

	private long currentRankingDuration = -1;

	private boolean showNoteGraph = false;
	private boolean songUpdated;

	private ScoreDataCache scorecache;
	private ScoreDataCache rivalcache;

	private RankingData currentir;
	/**
	 * ランキング表示位置
	 */
	protected int rankingOffset = 0;

	private PlayerInformation rival;

	private int panelstate;

	private BMSPlayerMode play = null;

	private SongData playedsong = null;
	private CourseData playedcourse = null;

	private PixmapResourcePool banners;

	private PixmapResourcePool stagefiles;


	public MusicSelector(MainController main, boolean songUpdated) {
		super(main);
		this.songUpdated = songUpdated;
		this.config = main.getPlayerResource().getPlayerConfig();

		songdb = main.getSongDatabase();

		final PlayDataAccessor pda = main.getPlayDataAccessor();

		scorecache = new ScoreDataCache() {
			@Override
			protected ScoreData readScoreDatasFromSource(SongData song, int lnmode) {
				return pda.readScoreData(song.getSha256(), song.hasUndefinedLongNote(), lnmode);
			}

			@Override
			protected void readScoreDatasFromSource(ScoreDataCollector collector, SongData[] songs, int lnmode) {
				pda.readScoreDatas(collector, songs, lnmode);
			}
		};

		bar = new BarRenderer(this, manager);
		banners = new PixmapResourcePool(resource.getConfig().getBannerPixmapGen());
		stagefiles = new PixmapResourcePool(resource.getConfig().getStagefilePixmapGen());
		musicinput = new MusicSelectInputProcessor(this);

		// 避免在构造函数中直接调用扫描，移到 create 方法中异步处理
		// 防止构造函数中的同步操作导致 UI 阻塞
	}

	public void setRival(PlayerInformation rival) {
		final RivalDataAccessor rivals = main.getRivalDataAccessor();
		final int index = IntStream.range(0, rivals.getRivalCount()).filter(i -> rival == rivals.getRivalInformation(i)).findFirst().orElse(-1);
		this.rival = index != -1 ? rivals.getRivalInformation(index) : null;
		rivalcache = index != -1 ? rivals.getRivalScoreDataCache(index) : null;
		manager.updateBar();
		Logger.getGlobal().info("Rival変更:" + (rival != null ? rival.getName() : "なし"));
	}

	public PlayerInformation getRival() {
		return rival;
	}

	public ScoreDataCache getScoreDataCache() {
		return scorecache;
	}

	public ScoreDataCache getRivalScoreDataCache() {
		return rivalcache;
	}

	public void create() {
		// 菜单界面关闭持续渲染，降低功耗
		Gdx.graphics.setContinuousRendering(false);

		main.getSoundManager().shuffle();

		play = null;
		showNoteGraph = false;
		resource.setPlayerData(main.getPlayDataAccessor().readPlayerData());
		refreshTodayPlayerData();
		if (playedsong != null) {
			scorecache.update(playedsong, config.getLnmode());
			playedsong = null;
		}
		if (playedcourse != null) {
			for (SongData sd : playedcourse.getSong()) {
				scorecache.update(sd, config.getLnmode());
			}
			playedcourse = null;
		}

		preview = new PreviewMusicProcessor(main.getAudioProcessor(), resource.getConfig());
		preview.setDefault(getSound(SELECT));

		final BMSPlayerInputProcessor input = main.getInputProcessor();
		PlayModeConfig pc = (config.getMusicselectinput() == 0 ? config.getMode7()
				: (config.getMusicselectinput() == 1 ? config.getMode9() : config.getMode14()));
		input.setKeyboardConfig(pc.getKeyboardConfig());
		input.setControllerConfig(pc.getController());
		input.setMidiConfig(pc.getMidiConfig());

		loadSkin(SkinType.MUSIC_SELECT);

		// search text field（按当前皮肤同步原生输入框；
		// 真正的逻辑在 syncSearchTextField()，换皮肤时由 setSkin() 触发，这里幂等）
		syncSearchTextField();

		if (manager.getSelected() == null) {
			// 避免直接同步调用，先显示缓存的内容，防止阻塞
			manager.updateBar();
		}


		// Auto-scan on entry: matches upstream MusicSelector.java:136-138 —
		// scan only when no launcher pre-scan ran AND the user enabled the toggle.
		// MainController.updateSong(...) spawns a worker thread internally, so this
		// call is non-blocking even though we run it from the GL thread here.
		if (!songUpdated && main.getPlayerResource().getConfig().isUpdatesong()) {
			main.updateSong(null);
		}
	}

	/**
	 * 换皮肤时重建原生搜索框。位置由皮肤决定，而且 {@code MainController.changeState}
	 * 对 MUSICSELECT 只在首次进入时调 {@link #create()}、之后只 {@code loadSkin()} ——
	 * 不同步的话，旧皮肤的原生 EditText 会留在旧皮肤的位置上（实测：LR2 皮肤 →
	 * VibeCity，"search song" 提示文字停在 MISS COUNT 旁边）。
	 */
	@Override
	public void setSkin(Skin skin) {
		super.setSkin(skin);
		syncSearchTextField();
	}

	/** 按当前皮肤同步原生搜索框：换皮肤必重建；新皮肤没有搜索框区域则移除。 */
	private void syncSearchTextField() {
		Skin skin = getSkin();
		if (!(skin instanceof MusicSelectSkin) || searchFieldSkin == skin) {
			return;    // 每帧都可能走到这里（prepare/各处触发），同一皮肤只同步一次
		}
		searchFieldSkin = skin;

		if (search != null) {
			// 皮肤换了：旧输入框的位置/存在性都不可信，直接重建。
			// MainController.render() 与输入注册对 null stage 均有判断，置 null 是安全的。
			search.dispose();
			search = null;
			setStage(null);
		}
		Rectangle searchRegion = ((MusicSelectSkin) skin).getSearchTextRegion();
		if (searchRegion != null) {
			search = new SearchTextField(this, resource.getConfig().getResolution());
			setStage(search);
		}

		// 🔴 InputMultiplexer 只在 MainController.changeState 里构建，而这里换上的是
		// 一个全新的 Stage 对象 —— 不刷新的话新搜索框收不到任何触摸（表现为
		// 「换完皮肤后点搜索框没反应」，要等下一次状态切换才恢复）。
		// 在状态切换路径上会多刷一次，幂等、无副作用。
		main.refreshInputProcessor();
	}

	public void prepare() {
		preview.start((String)null);

		// 返回选曲界面时刷新「上一曲」影响到的全部数据（成绩 / 回放 / 玩家数据 / 文件夹灯）。
		// 上游是在 create() 里做这些的 —— 上游每次进 MUSICSELECT 都会走一遍 create()；
		// 本 fork 为了避开冷启动的重 IO 把 create() 限成"只首次执行"
		// （见 MainController.changeState 的 selectorInitialized），
		// 于是打完一首歌回来，选曲界面的成绩、リプレイ标记、玩家数据面板全都还是旧的。
		// prepare() 每次进入 MUSICSELECT 都会执行（MainController.changeState 末尾），
		// 所以刷新放在这里。
		refreshAfterPlay();

		final BMSPlayerInputProcessor input = main.getInputProcessor();
		PlayModeConfig pc = (config.getMusicselectinput() == 0 ? config.getMode7()
				: (config.getMusicselectinput() == 1 ? config.getMode9() : config.getMode14()));
		input.setKeyboardConfig(pc.getKeyboardConfig());
		input.setControllerConfig(pc.getController());
		input.setMidiConfig(pc.getMidiConfig());
	}

	/**
	 * 游玩 / 结算结束、回到选曲界面时，把「上一曲」影响到的东西全部重读一遍。
	 *
	 * <p>为什么需要：上游 {@code MusicSelector.create()} 里做的三件事 ——
	 * {@code resource.setPlayerData(readPlayerData())}、{@code scorecache.update(playedsong)}、
	 * {@code manager.updateBar()} —— 在本 fork 里<b>都不会再执行</b>（create() 被限成只跑一次，
	 * 而 updateBar() 又被包进 {@code if (manager.getSelected() == null)}）。结果是：</p>
	 * <ul>
	 *   <li>皮肤 {@code player_*} 属性（play count / clear / perfect~poor / notes / playtime）
	 *       整个会话都不再动 —— 它们读 {@code resource.getPlayerData()}，而这个对象只在
	 *       create() 里被换过一次；</li>
	 *   <li>选曲界面的リプレイ图标（{@code BooleanPropertyFactory} 的
	 *       {@code OPTION_REPLAYDATA*}/{@code NO_REPLAYDATA*} 读
	 *       {@link SelectableBar#existsReplay(int)}）只在 {@code BarManager} 加载文件夹时
	 *       写过一次，刚存下的回放不会亮；</li>
	 *   <li>bar 上的灯 / 奖杯 / 分数读数取自 {@code bar.getScore()} 这个<b>引用</b>，
	 *       光更新缓存不换引用是看不见变化的。</li>
	 * </ul>
	 *
	 * <p>开销控制：GL 线程上只做「一次 player 查询 + 上一曲（或上一组课目）的成绩查询 +
	 * 4 次回放文件存在检查」，<b>不做</b>文件夹枚举与聚合；文件夹灯的聚合查询丢到后台线程。
	 * 注意不要在这里 {@code scorecache.clear()} —— 清空后当前列表每个 bar 都得重新查一次
	 * DB，那才是 ANR 的来源。</p>
	 */
	private void refreshAfterPlay() {
		final PlayDataAccessor pda = main.getPlayDataAccessor();

		// (1) 玩家累计数据（player_*）与本日分（player_today_*）：两者必须同一次读，
		//     否则面板上的「累计」和「今日」会来自不同时间点
		try {
			final PlayerData pd = pda.readPlayerData();
			if (pd != null) {
				resource.setPlayerData(pd);
			}
			resource.setTodayPlayerData(pda.readTodayPlayerData());
		} catch (Throwable t) {
			Logger.getGlobal().warning("選曲画面: プレイヤーデータの更新に失敗 : " + t);
		}

		final SongData played = playedsong;
		final CourseData course = playedcourse;
		playedsong = null;
		playedcourse = null;
		if (played == null && course == null) {
			// 没打过歌（例如从 CONFIG / 皮肤选择返回）：上面的玩家数据刷新就够了
			return;
		}
		Logger.getGlobal().info("選曲画面: プレイ後リフレッシュ (" + (played != null ? played.getTitle() : "COURSE") + ")");

		// (2) 成绩缓存：只更新上一曲 / 上一组课目，不动其它条目
		if (played != null) {
			scorecache.update(played, config.getLnmode());
		}
		if (course != null) {
			for (SongData sd : course.getSong()) {
				scorecache.update(sd, config.getLnmode());
			}
		}

		// (3) 推给当前列表里对应的 bar：成绩引用 + 回放存在标志
		final Bar[] bars = manager.getBarList();
		if (bars != null) {
			for (Bar b : bars) {
				if (b instanceof SongBar sb && sb.getSongData() != null) {
					final SongData sd = sb.getSongData();
					if (!isPlayed(sd, played, course)) {
						continue;
					}
					sb.setScore(scorecache.readScoreData(sd, config.getLnmode()));
					for (int i = 0; i < REPLAY; i++) {
						sb.setExistsReplay(i, pda.existsReplayData(sd.getSha256(), sd.hasUndefinedLongNote(),
								config.getLnmode(), i));
					}
				} else if (course != null && b instanceof GradeBar gb && gb.existsAllSongs()) {
					refreshCourseBar(gb, pda);
				}
			}
		}

		// (4) 曲目情报面板（score / exscore / play count / miss count …）跟着选中的 bar 走
		if (bars != null && bars.length > 0) {
			final Bar selected = manager.getSelected();
			if (selected != null) {
				getScoreDataProperty().update(selected.getScore(), selected.getRivalScore());
			}
		}

		// (5) 文件夹灯：聚合查询必须在后台线程（和 BarManager 加载文件夹时同一套做法）
		if (resource.getConfig().isFolderlamp() && bars != null) {
			final Array<Bar> folders = new Array<Bar>();
			for (Bar b : bars) {
				if (b instanceof DirectoryBar) {
					folders.add(b);
				}
			}
			if (folders.size > 0) {
				new Thread(() -> {
					for (Bar b : folders) {
						try {
							((DirectoryBar) b).updateFolderStatus();
						} catch (Throwable t) {
							// 单个文件夹失败不影响其它
						}
					}
				}, "folder-lamp-refresh").start();
			}
		}
	}

	/**
	 * 本日分のプレイヤーデータを読み直して {@code resource} に載せる。
	 *
	 * <p>{@link PlayDataAccessor#readTodayPlayerData()} は長らく<b>呼び出し元が無い
	 * 死んだコード</b>だった（上游本家にも呼び出し元が無い）。ここが唯一の呼び出し元で、
	 * スキンの {@code player_today_*} 属性（id 334-337 / 344-348）に値を供給する。
	 * 「今日ノート数」のような当日分の表示はこれで初めて動く。</p>
	 *
	 * <p>失敗しても選曲画面は出したいので例外は握る（値は 0 のままになるだけ）。</p>
	 */
	private void refreshTodayPlayerData() {
		try {
			resource.setTodayPlayerData(main.getPlayDataAccessor().readTodayPlayerData());
		} catch (Throwable t) {
			Logger.getGlobal().warning("選曲画面: 本日分プレイヤーデータの更新に失敗 : " + t);
		}
	}

	/** {@code sd} 是否就是刚打过的那首歌，或刚打过的那组课目里的曲目。 */
	private boolean isPlayed(SongData sd, SongData played, CourseData course) {
		if (sd == null || sd.getSha256() == null) {
			return false;
		}
		if (played != null && sd.getSha256().equals(played.getSha256())) {
			return true;
		}
		if (course != null) {
			for (SongData c : course.getSong()) {
				if (sd.getSha256().equals(c.getSha256())) {
					return true;
				}
			}
		}
		return false;
	}

	/** 段位 / 课程 bar：NORMAL / MIRROR / RANDOM 三份成绩与回放标志一起重算。 */
	private void refreshCourseBar(GradeBar gb, PlayDataAccessor pda) {
		final SongData[] songs = gb.getSongDatas();
		final String[] hash = new String[songs.length];
		boolean ln = false;
		for (int j = 0; j < songs.length; j++) {
			hash[j] = songs[j].getSha256();
			ln |= songs[j].hasUndefinedLongNote();
		}
		final CourseData.CourseDataConstraint[] constraint = gb.getCourseData().getConstraint();
		gb.setScore(pda.readScoreData(hash, ln, config.getLnmode(), 0, constraint));
		gb.setMirrorScore(pda.readScoreData(hash, ln, config.getLnmode(), 1, constraint));
		gb.setRandomScore(pda.readScoreData(hash, ln, config.getLnmode(), 2, constraint));
		for (int i = 0; i < REPLAY; i++) {
			gb.setExistsReplay(i, pda.existsReplayData(hash, ln, config.getLnmode(), i, constraint));
		}
	}

	public void render() {
		final Bar current = manager.getSelected();
        if(timer.getNowTime() > getSkin().getInput()){
        	timer.switchTimer(TIMER_STARTINPUT, true);
        }
		if(timer.getNowTime(TIMER_SONGBAR_CHANGE) < 0) {
			timer.setTimerOn(TIMER_SONGBAR_CHANGE);
		}
		// draw song information
		resource.setSongdata(current instanceof SongBar ? ((SongBar) current).getSongData() : null);
		resource.setCourseData(current instanceof GradeBar ? ((GradeBar) current).getCourseData() : null);

		// preview music
		if (current instanceof SongBar && resource.getConfig().getSongPreview() != SongPreview.NONE) {
			final SongData song = resource.getSongdata();
			if (song != preview.getSongData() && timer.getNowTime() > timer.getTimer(TIMER_SONGBAR_CHANGE) + previewDuration
					&& play == null) {
				this.preview.start(song);
			}
		}

		// read bms information
		if (timer.getNowTime() > timer.getTimer(TIMER_SONGBAR_CHANGE) + notesGraphDuration && !showNoteGraph && play == null) {
			if (current instanceof SongBar && ((SongBar) current).existsSong()) {
				SongData song = resource.getSongdata();
					new Thread(() ->  {
						song.setBMSModel(resource.loadBMSModel(Gdx.files.absolute(((SongBar) current).getSongData().getPath()),
								config.getLnmode()));
					}).start();;
			}
			showNoteGraph = true;
		}
		// get ir ranking
		if (currentRankingDuration != -1 && timer.getNowTime() > timer.getTimer(TIMER_SONGBAR_CHANGE) + currentRankingDuration) {
			currentRankingDuration = -1;
			if (current instanceof SongBar && ((SongBar) current).existsSong() && play == null) {
				SongData song = ((SongBar) current).getSongData();
				RankingData irc = main.getRankingDataCache().get(song, config.getLnmode());
				if(irc == null) {
					irc = new RankingData();
					main.getRankingDataCache().put(song, config.getLnmode(), irc);
				}
				irc.load(this, song);
	            currentir = irc;
			}
			if (current instanceof GradeBar && ((GradeBar) current).existsAllSongs() && play == null) {
				CourseData course = ((GradeBar) current).getCourseData();
				RankingData irc = main.getRankingDataCache().get(course, config.getLnmode());
				if(irc == null) {
					irc = new RankingData();
					main.getRankingDataCache().put(course, config.getLnmode(), irc);
				}
				irc.load(this, course);
	            currentir = irc;
			}
		}
		final int irstate = currentir != null ? currentir.getState() : -1;
		timer.switchTimer(TIMER_IR_CONNECT_BEGIN, irstate == RankingData.ACCESS);
		timer.switchTimer(TIMER_IR_CONNECT_SUCCESS, irstate == RankingData.FINISH);
		timer.switchTimer(TIMER_IR_CONNECT_FAIL, irstate == RankingData.FAIL);

		if (play != null) {
			if (current instanceof SongBar) {
				SongData song = ((SongBar) current).getSongData();
				if (((SongBar) current).existsSong()) {
					readChart(song, current);
				} else if (song.getIpfs() != null && main.getMusicDownloadProcessor() != null
						&& main.getMusicDownloadProcessor().isAlive()) {
					execute(MusicSelectCommand.DOWNLOAD_IPFS);
				} else {
	                executeEvent(EventType.open_download_site);
				}
			} else if (current instanceof ExecutableBar) {
				readChart(((ExecutableBar) current).getSongData(), current);
			}else if (current instanceof GradeBar) {
				if (play.mode == BMSPlayerMode.Mode.PRACTICE) {
					play = BMSPlayerMode.PLAY;
				}
				readCourse(play);
			} else if (current instanceof RandomCourseBar) {
				if (play.mode == BMSPlayerMode.Mode.PRACTICE) {
					play = BMSPlayerMode.PLAY;
				}
				readRandomCourse(play);
			} else if (current instanceof DirectoryBar) {
				if(play.mode == BMSPlayerMode.Mode.AUTOPLAY) {
					final String[] paths = Stream.of(((DirectoryBar) current).getChildren())
						.filter(bar -> (bar instanceof SongBar && ((SongBar) bar).getSongData() != null && ((SongBar) bar).getSongData().getPath() != null))
						.map(bar -> ((SongBar) bar).getSongData().getPath()).toArray(String[]::new);
					if(paths.length > 0) {
						resource.clear();
					FileHandle[] fhs = new FileHandle[paths.length];
					for(int i=0;i<paths.length;i++) fhs[i] = Gdx.files.absolute(paths[i]);
					resource.setAutoPlaySongs(fhs, false);
						if(resource.nextSong()) {
							main.changeState(MainStateType.DECIDE);
						}
					}
				}
			}
			play = null;
		}

	}

	public void input() {
		final BMSPlayerInputProcessor input = main.getInputProcessor();

		if (input.getControlKeyState(ControlKeys.NUM6)) {
			main.changeState(MainStateType.CONFIG);
		} else if (input.isActivated(KeyCommand.OPEN_SKIN_CONFIGURATION)) {
			main.changeState(MainStateType.SKINCONFIG);
		}

		// Debug: check F2 state before musicinput processes it (non-consuming check)
		if (input.getControlKeyState(bms.player.beatoraja.input.KeyBoardInputProcesseor.ControlKeys.F2)) {
			Gdx.app.log("MusicSelector", "F2 state is active before musicinput.input()");
		}

		musicinput.input();
	}

	public void shutdown() {
		preview.stop();
		stop(SELECT);
		if (search != null) {
			search.unfocus(this);
		}

		// 进入 play 前立即清空 select 阶段的皮肤纹理 Pixmap,
		// 避免长时间浏览选曲界面后 SkinLoader.resource 堆积大量 banner / stagefile / 皮肤图片。
		// SkinLoader.resource 默认 maxgen=1, 连调两次 disposeOld 强制清空所有条目
		// (已被上传到 GPU 的 Texture 独立于 Pixmap, 不受此处清理影响)
		SkinLoader.getResource().disposeOld();
		SkinLoader.getResource().disposeOld();
	}

	public void select(Bar current) {
		if (current instanceof DirectoryBar dirbar) {
			if (manager.updateBar(dirbar)) {
				play(FOLDER_OPEN);
			}
			execute(MusicSelectCommand.RESET_REPLAY);
		} else {
			play = BMSPlayerMode.PLAY;
		}
	}

	public int getSelectedReplay() {
		return  selectedreplay;
	}

	public void setSelectedReplay(int index) {
		selectedreplay = index;
	}

	public void execute(MusicSelectCommand command) {
		command.function.accept(this);
	}

	private void readChart(SongData song, Bar current) {
		resource.clear();
		if (resource.setBMSFile(Gdx.files.absolute(song.getPath()), play)) {
			// TODO 表名、フォルダ名をPlayerResource上でも重複実施している
			final Queue<DirectoryBar> dir = manager.getDirectory();
			if(dir.size > 0 && !(dir.last() instanceof SameFolderBar)) {
				Array<String> urls = new Array<String>(resource.getConfig().getTableURL());

				boolean isdtable = false;
				for (DirectoryBar bar : dir) {
					if (bar instanceof TableBar) {
						String currenturl = ((TableBar) bar).getUrl();
						if (currenturl != null && urls.contains(currenturl, false)) {
							isdtable = true;
							resource.setTablename(bar.getTitle());
						}
					}
					if (bar instanceof HashBar && isdtable) {
						resource.setTablelevel(bar.getTitle());
						break;
					}
				}
			}

			if(main.getIRStatus().length > 0 && currentir == null) {
				currentir = new RankingData();
				main.getRankingDataCache().put(song, config.getLnmode(), currentir);
			}
			resource.setRankingData(currentir);
			ScoreData rival = current.getRivalScore();
			resource.setRivalScoreData(rival);
			ReplayData chartOption = null;
			ReplayData replay;
			switch(ChartReplicationMode.get(config.getChartReplicationMode())) {
			case NONE:
				// TODO 通常オプションもここに入れて渡す？
				break;
			case RIVALCHART:
				if(rival != null) {
					chartOption = new ReplayData();
					chartOption.randomoption = rival.getOption() % 10;
					chartOption.randomoption2 = (rival.getOption() / 10) % 10;
					chartOption.doubleoption = rival.getOption() / 100;
					chartOption.randomoptionseed = rival.getSeed() % (65536 * 256);
					chartOption.randomoption2seed = rival.getSeed() / (65536 * 256);

				}
				break;
			case RIVALOPTION:
				if(rival != null) {
					chartOption = new ReplayData();
					chartOption.randomoption = rival.getOption() % 10;
					chartOption.randomoption2 = (rival.getOption() / 10) % 10;
					chartOption.doubleoption = rival.getOption() / 100;
				}
				break;
			case REPLAYCHART:
				replay = main.getPlayDataAccessor().readReplayData(resource.getBMSModel(), config.getLnmode(), play.id);
				if (replay != null) {
					chartOption = new ReplayData();
					chartOption.randomoption = replay.randomoption;
					chartOption.randomoptionseed = replay.randomoptionseed;
					chartOption.randomoption2 = replay.randomoption2;
					chartOption.randomoption2seed = replay.randomoption2seed;
					chartOption.doubleoption = replay.doubleoption;
					chartOption.rand = replay.rand;
				}
				break;
			case REPLAYOPTION:
				replay = main.getPlayDataAccessor().readReplayData(resource.getBMSModel(), config.getLnmode(), play.id);
				if (replay != null) {
					chartOption = new ReplayData();
					chartOption.randomoption = replay.randomoption;
					chartOption.randomoption2 = replay.randomoption2;
					chartOption.doubleoption = replay.doubleoption;
				}
				break;
			}
			resource.setChartOption(chartOption);

			playedsong = song;
			main.changeState(MainStateType.DECIDE);
		} else {
			main.getMessageRenderer().addMessage("Failed to loading BMS : Song not found, or Song has error", 1200, Color.RED, 1);
		}
	}

	private void readCourse(BMSPlayerMode mode) {
		final GradeBar gradeBar = (GradeBar) manager.getSelected();
		if (!gradeBar.existsAllSongs()) {
			Logger.getGlobal().info("段位の楽曲が揃っていません");
			return;
		}

		if (!_readCourse(mode, gradeBar)) {
			main.getMessageRenderer().addMessage("Failed to loading Course : Some of songs not found", 1200, Color.RED, 1);
			Logger.getGlobal().info("段位の楽曲が揃っていません");
		}
	}

	private void readRandomCourse(BMSPlayerMode mode) {
		final RandomCourseBar randomCourseBar = (RandomCourseBar) manager.getSelected();
		if (!randomCourseBar.existsAllSongs()) {
			Logger.getGlobal().info("ランダムコースの楽曲が揃っていません");
			return;
		}

		randomCourseBar.getCourseData().lotterySongDatas(main);
		final GradeBar gradeBar = new GradeBar(randomCourseBar.getCourseData().createCourseData());
		if (!gradeBar.existsAllSongs()) {
			main.getMessageRenderer().addMessage("Failed to loading Random Course : Some of songs not found", 1200, Color.RED, 1);
			Logger.getGlobal().info("ランダムコースの楽曲が揃っていません");
			return;
		}

		if (_readCourse(mode, gradeBar)) {
			manager.addRandomCourse(gradeBar, manager.getDirectoryString());
			manager.updateBar();
			manager.setSelected(gradeBar);
		} else {
			main.getMessageRenderer().addMessage("Failed to loading Random Course : Some of songs not found", 1200, Color.RED, 1);
			Logger.getGlobal().info("ランダムコースの楽曲が揃っていません");
		}
	}

	private boolean _readCourse(BMSPlayerMode mode, GradeBar gradeBar) {
		resource.clear();
		final SongData[] songs = gradeBar.getSongDatas();
		final String[] files = Stream.of(songs).map(song -> song.getPath()).toArray(String[]::new);
		FileHandle[] fhs = new FileHandle[files.length];
		for(int i=0;i<files.length;i++) fhs[i] = Gdx.files.absolute(files[i]);
		if (resource.setCourseBMSFiles(fhs)) {
			if (mode.mode == BMSPlayerMode.Mode.PLAY || mode.mode == BMSPlayerMode.Mode.AUTOPLAY) {
				for (CourseData.CourseDataConstraint constraint : gradeBar.getCourseData().getConstraint()) {
					switch (constraint) {
						case CLASS:
							config.setRandom(0);
							config.setRandom2(0);
							config.setDoubleoption(0);
							break;
						case MIRROR:
							if (config.getRandom() == 1) {
								config.setRandom2(1);
								config.setDoubleoption(1);
							} else {
								config.setRandom(0);
								config.setRandom2(0);
								config.setDoubleoption(0);
							}
							break;
						case RANDOM:
							if (config.getRandom() > 5) {
								config.setRandom(0);
							}
							if (config.getRandom2() > 5) {
								config.setRandom2(0);
							}
							break;
						case LN:
							config.setLnmode(0);
							break;
						case CN:
							config.setLnmode(1);
							break;
						case HCN:
							config.setLnmode(2);
							break;
						default:
							break;
					}
				}
			}
			gradeBar.getCourseData().setSong(resource.getCourseBMSModels());
			resource.setCourseData(gradeBar.getCourseData());
			resource.setBMSFile(Gdx.files.absolute(files[0]), mode);
			playedcourse = gradeBar.getCourseData();

			if(main.getIRStatus().length > 0 && currentir == null) {
				currentir = new RankingData();
				main.getRankingDataCache().put(gradeBar.getCourseData(), config.getLnmode(), currentir);
			}

			RankingData songrank = main.getRankingDataCache().get(songs[0], config.getLnmode());
			if(main.getIRStatus().length > 0 && songrank == null) {
				songrank = new RankingData();
				main.getRankingDataCache().put(songs[0], config.getLnmode(), songrank);
			}
			resource.setRankingData(songrank);
			resource.setRivalScoreData(null);
			resource.setChartOption(null);

			main.changeState(MainStateType.DECIDE);
			return true;
		}
		return false;
	}

	public int getSort() {
		return config.getSort();
	}

	public void setSort(int sort) {
		config.setSort(sort);
		config.setSortid(BarSorter.defaultSorter[sort].name());
	}

	public void dispose() {
		super.dispose();
		bar.dispose();
		banners.dispose();
		stagefiles.dispose();
		if (search != null) {
			search.dispose();
			search = null;
		}
	}

	public int getPanelState() {
		return panelstate;
	}

	public void setPanelState(int panelstate) {
		if (this.panelstate != panelstate) {
			if (this.panelstate != 0) {
				timer.setTimerOn(TIMER_PANEL1_OFF + this.panelstate - 1);
				timer.setTimerOff(TIMER_PANEL1_ON + this.panelstate - 1);
			}
			if (panelstate != 0) {
				timer.setTimerOn(TIMER_PANEL1_ON + panelstate - 1);
				timer.setTimerOff(TIMER_PANEL1_OFF + panelstate - 1);
			}
		}
		this.panelstate = panelstate;
	}

	public SongDatabaseAccessor getSongDatabase() {
		return songdb;
	}

	public boolean existsConstraint(CourseData.CourseDataConstraint constraint) {
		CourseData.CourseDataConstraint[] cons;
		if ((manager.getSelected() instanceof GradeBar)) {
			cons = ((GradeBar) manager.getSelected()).getCourseData().getConstraint();
		} else if (manager.getSelected() instanceof RandomCourseBar) {
			cons = ((RandomCourseBar) manager.getSelected()).getCourseData().getConstraint();
		} else {
			return false;
		}

		for (CourseData.CourseDataConstraint con : cons) {
			if(con == constraint) {
				return true;
			}
		}
		return false;
	}

	public Bar getSelectedBar() {
		return manager.getSelected();
	}

	public BarRenderer getBarRender() {
		return bar;
	}

	public BarManager getBarManager() {
		return manager;
	}

	public PixmapResourcePool getBannerResource() {
		return banners;
	}
	public PixmapResourcePool getStagefileResource() {
		return stagefiles;
	}

	public void selectedBarMoved() {
		execute(MusicSelectCommand.RESET_REPLAY);
		loadSelectedSongImages();

		timer.setTimerOn(TIMER_SONGBAR_CHANGE);
		if(preview.getSongData() != null && (!(manager.getSelected() instanceof SongBar) ||
				((SongBar) manager.getSelected()).getSongData().getFolder().equals(preview.getSongData().getFolder()) == false))
		preview.start((String)null);
		showNoteGraph = false;

		final Bar current = manager.getSelected();
		if(main.getIRStatus().length > 0) {
			if(current instanceof SongBar && ((SongBar) current).existsSong()) {
				currentir = main.getRankingDataCache().get(((SongBar) current).getSongData(), config.getLnmode());
				currentRankingDuration = (currentir != null ? Math.max(rankingReloadDuration - (System.currentTimeMillis() - currentir.getLastUpdateTime()) ,0) : 0) + rankingDuration;
			} else if(current instanceof GradeBar && ((GradeBar) current).existsAllSongs()) {
				currentir = main.getRankingDataCache().get(((GradeBar) current).getCourseData(), config.getLnmode());
				currentRankingDuration = (currentir != null ? Math.max(rankingReloadDuration - (System.currentTimeMillis() - currentir.getLastUpdateTime()) ,0) : 0) + rankingDuration;
			} else {
				currentir = null;
				currentRankingDuration = -1;
			}
		} else {
			currentir = null;
			currentRankingDuration = -1;
		}
	}

	public void loadSelectedSongImages() {
		// banner
		// stagefile
		final Bar current = manager.getSelected();
		// 防御：bmsresource 在某些边界场景下可能为 null（例如扫描触发 UI 刷新过早、PlayResource 尚未完全初始化）
		// 直接 return 避免 NPE 闪退
		final BMSResource bmsResource = resource.getBMSResource();
		if (bmsResource == null) {
			// 防御性 return, bmsresource 永不为 null (构造时即创建), 这里只在遗留 bug 触发时命中。
			// 加 warning 以便真机复现时定位真实触发场景 (current 类型, state 切换等)。
			Logger.getGlobal().warning("loadSelectedSongImages: BMSResource null, current=" +
					(current != null ? current.getClass().getSimpleName() : "null"));
			return;
		}
		bmsResource.setBanner(
				current instanceof SongBar ? ((SongBar) current).getBanner() : null);
		bmsResource.setStagefile(
				current instanceof SongBar ? ((SongBar) current).getStagefile() : null);
	}

	public void selectSong(BMSPlayerMode mode) {
		play = mode;
	}

	public PlayConfig getSelectedBarPlayConfig() {
		Bar current = manager.getSelected();
		PlayConfig pc = null;
		if (current instanceof SongBar && ((SongBar)current).existsSong()) {
			SongBar song = (SongBar) current;
			pc = main.getPlayerConfig().getPlayConfig(song.getSongData().getMode()).getPlayconfig();
		} else if(current instanceof GradeBar && ((GradeBar)current).existsAllSongs()) {
			GradeBar grade = (GradeBar)current;
			for(SongData song : grade.getSongDatas()) {
				PlayConfig pc2 = main.getPlayerConfig().getPlayConfig(song.getMode()).getPlayconfig();
				if(pc == null) {
					pc = pc2;
				}
				if(pc != pc2) {
					pc = null;
					break;
				}
			}
		} else {
			pc = main.getPlayerConfig().getPlayConfig(config.getMode()).getPlayconfig();
		}
		return pc;
	}

	public RankingData getCurrentRankingData() {
		return currentir;
	}

	public long getCurrentRankingDuration() {
		return currentRankingDuration;
	}

	public int getRankingOffset() {
		return rankingOffset;
	}

	public float getRankingPosition() {
		final int rankingMax = currentir != null ? Math.max(1, currentir.getTotalPlayer()) : 1;
		return (float)rankingOffset / rankingMax;
	}

	public void setRankingPosition(float value) {
		if (value >= 0 && value < 1) {
			final int rankingMax = currentir != null ? Math.max(1, currentir.getTotalPlayer()) : 1;
			rankingOffset = (int) (rankingMax * value);
		}
	}

	public enum ChartReplicationMode {
		NONE, RIVALCHART, RIVALOPTION, REPLAYCHART, REPLAYOPTION;

		public static final ChartReplicationMode[] allMode = {NONE, RIVALCHART, RIVALOPTION};

		public static ChartReplicationMode get(String name) {
			for(ChartReplicationMode mode : allMode) {
				if(mode.name().equals(name)) {
					return mode;
				}
			}
			return NONE;
		}

	}
}
