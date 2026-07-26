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
public final class MusicSelector extends MainState {

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

		// search text field
		if (getSkin() instanceof MusicSelectSkin) {
			Rectangle searchRegion = ((MusicSelectSkin) getSkin()).getSearchTextRegion();
			if (searchRegion != null && (getStage() == null ||
					(search != null && !searchRegion.equals(search.getSearchBounds())))) {
				if (search != null) {
					search.dispose();
				}
				search = new SearchTextField(this, resource.getConfig().getResolution());
				setStage(search);
			}
		}

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

	public void prepare() {
		preview.start((String)null);

		// 关键修复：返回选曲界面时强制更新分数缓存
		// 统一调用 scorecache.update() 重新从数据库读取最新状态，确保 EX Score 不为 0
		if (playedsong != null) {
			scorecache.clear();
			ScoreData sd = main.getPlayDataAccessor().readScoreData(playedsong.getSha256(), playedsong.hasUndefinedLongNote(), config.getLnmode());
			Logger.getGlobal().info("prepare: playedsong=" + playedsong.getTitle() + ", sd=" + (sd != null ? "notNull" : "null") + (sd != null ? ", exscore=" + sd.getExscore() : ""));
			if (sd != null) {
				for (Bar b : manager.currentsongs) {
					if (b instanceof SongBar sb && sb.getSongData() != null
							&& sb.getSongData().getSha256().equals(playedsong.getSha256())) {
						sb.setScore(sd);
						getScoreDataProperty().update(sd);
						Logger.getGlobal().info("prepare: setScore to SongBar done, exscore=" + sd.getExscore() + ", property exscore=" + getScoreDataProperty().getNowEXScore());
						break;
					}
				}
			}
			playedsong = null;
		}
		if (playedcourse != null) {
			scorecache.clear();
			for (SongData song : playedcourse.getSong()) {
				main.getPlayDataAccessor().readScoreData(song.getSha256(), song.hasUndefinedLongNote(), config.getLnmode());
			}
			playedcourse = null;
		}

		final BMSPlayerInputProcessor input = main.getInputProcessor();
		PlayModeConfig pc = (config.getMusicselectinput() == 0 ? config.getMode7()
				: (config.getMusicselectinput() == 1 ? config.getMode9() : config.getMode14()));
		input.setKeyboardConfig(pc.getKeyboardConfig());
		input.setControllerConfig(pc.getController());
		input.setMidiConfig(pc.getMidiConfig());
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
