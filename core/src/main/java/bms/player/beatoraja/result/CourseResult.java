package bms.player.beatoraja.result;

import static bms.player.beatoraja.ClearType.*;
import static bms.player.beatoraja.skin.SkinProperty.*;
import static bms.player.beatoraja.SystemSoundManager.SoundType.*;

import java.util.*;
import java.util.logging.Logger;

import bms.player.beatoraja.input.KeyCommand;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.FloatArray;

import bms.model.BMSModel;
import bms.player.beatoraja.*;
import bms.player.beatoraja.MainController.IRStatus;
import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import bms.player.beatoraja.input.KeyBoardInputProcesseor.ControlKeys;
import bms.player.beatoraja.ir.*;
import bms.player.beatoraja.skin.SkinType;
import bms.player.beatoraja.skin.property.EventFactory.EventType;

/**
 * コースリザルト
 *
 * @author exch
 */
public class CourseResult extends AbstractResult {

	private List<IRSendStatus> irSendStatus = new ArrayList<IRSendStatus>();

	private ResultKeyProperty property;

	public CourseResult(MainController main) {
		super(main);
	}

	public void create() {
		Gdx.graphics.setContinuousRendering(true);
		for(int i = 0;i < REPLAY_SIZE;i++) {
			saveReplay[i] = main.getPlayDataAccessor().existsReplayData(resource.getCourseBMSModels(),
					resource.getPlayerConfig().getLnmode(), i ,resource.getConstraint()) ? ReplayStatus.EXIST : ReplayStatus.NOT_EXIST ;
		}

		for(int i = resource.getCourseGauge().size;i < resource.getCourseBMSModels().length;i++) {
			FloatArray[] list = new FloatArray[resource.getGrooveGauge().getGaugeTypeLength()];
			for(int type = 0; type < list.length; type++) {
				list[type] = new FloatArray();
				for(int l = 0;l < (resource.getCourseBMSModels()[i].getLastNoteTime() + 500) / 500;l++) {
					list[type].add(0f);
				}
			}
			resource.getCourseGauge().add(list);
		}

		property = ResultKeyProperty.get(resource.getBMSModel().getMode());
		if(property == null) {
			property = ResultKeyProperty.BEAT_7K;
		}

		updateScoreDatabase();

		// FIX: リプレイの自動保存は updateScoreDatabase() 内の CourseOldScoreLoadThread で
		// oldscore 読み込み完了後に非同期実行する（GL Thread を DB 待ちでブロックしないため）

		gaugeType = resource.getGrooveGauge().getType();

		loadSkin(SkinType.COURSE_RESULT);
	}

	public void prepare() {
		state = STATE_OFFLINE;
		final PlayerConfig config = resource.getPlayerConfig();
		final ScoreData newscore = getNewScore();

		ranking = resource.getRankingData() != null && resource.getCourseBMSModels() != null ? resource.getRankingData() : new RankingData();
		rankingOffset = 0;
		final IRStatus[] ir = main.getIRStatus();
		if (ir.length > 0 && resource.getPlayMode().mode == BMSPlayerMode.Mode.PLAY) {
			state = STATE_IR_PROCESSING;

			boolean uln = false;
			for(BMSModel model : resource.getCourseBMSModels()) {
				if(model.containsUndefinedLongNote()) {
					uln = true;
					break;
				}
			}
			final int lnmode = uln ? config.getLnmode() : 0;

        	for(IRStatus irc : ir) {
    			boolean send = resource.isUpdateCourseScore() && resource.getCourseData().isRelease();
    			switch(irc.config.getIrsend()) {
	    			case IRConfig.IR_SEND_ALWAYS -> {}
	    			case IRConfig.IR_SEND_COMPLETE_SONG -> {
	//    				FloatArray gauge = resource.getGauge()[resource.getGrooveGauge().getType()];
	//    				send &= gauge.get(gauge.size - 1) > 0.0;
	    			}
	    			case IRConfig.IR_SEND_UPDATE_SCORE -> {
	//    				send &= (newscore.getExscore() > oldscore.getExscore() || newscore.getClear() > oldscore.getClear()
	//					|| newscore.getCombo() > oldscore.getCombo() || newscore.getMinbp() < oldscore.getMinbp());
	    			}
    			}

    			if(send) {
    				irSendStatus.add(new IRSendStatus(irc.connection, resource.getCourseData(), lnmode, newscore));
    			}
        	}

			Thread irprocess = new Thread(() -> {
				int irsend = 0;
				boolean succeed = true;
				List<IRSendStatus> removeIrSendStatus = new ArrayList<>();

				for (IRSendStatus irc : irSendStatus) {
					try {
						if (irsend == 0) {
							Gdx.app.postRunnable(() -> timer.switchTimer(TIMER_IR_CONNECT_BEGIN, true));
						}
						irsend++;
						succeed &= irc.send();
						if (irc.retry < 0 || irc.retry > main.getConfig().getIrSendCount()) {
							removeIrSendStatus.add(irc);
						}
					} catch (Exception e) {
						Logger.getGlobal().warning("IR送信時の例外:" + e.getMessage());
						e.printStackTrace();
						// remove from queue
						removeIrSendStatus.add(irc);
					}
				}
				irSendStatus.removeAll(removeIrSendStatus);

				if (irsend > 0) {
					final boolean irSucceed = succeed;
					Gdx.app.postRunnable(() -> timer.switchTimer(irSucceed ? TIMER_IR_CONNECT_SUCCESS : TIMER_IR_CONNECT_FAIL, true));
					try {
						IRResponse<bms.player.beatoraja.ir.IRScoreData[]> response = ir[0].connection.getCoursePlayData(null, new IRCourseData(resource.getCourseData(), lnmode));
						if (response.isSucceeded()) {
							ranking.updateScore(ir[0].player, main.getRivalDataAccessor(), response.getData(), newscore.getExscore() > oldscore.getExscore() ? newscore : oldscore);
							Gdx.app.postRunnable(() -> {
								rankingOffset = ranking.getRank() > 10 ? ranking.getRank() - 5 : 0;
							});
							Logger.getGlobal().info("IRからのスコア取得成功 : " + response.getMessage());
						} else {
							Logger.getGlobal().warning("IRからのスコア取得失敗 : " + response.getMessage());
						}
					} catch (Exception e) {
						Logger.getGlobal().warning("IRからのスコア取得時例外:" + e.getMessage());
						e.printStackTrace();
					}
				}
				Gdx.app.postRunnable(() -> state = STATE_IR_FINISHED);
			});
			irprocess.start();
		}

		play(newscore.getClear() != Failed.id ? (getSound(COURSE_CLEAR) != null ? COURSE_CLEAR : RESULT_CLEAR)
				: (getSound(COURSE_FAIL) != null ? COURSE_FAIL : RESULT_FAIL), resource.getConfig().getAudioConfig().isLoopCourseResultSound());
	}

	public void shutdown() {
		stop(getSound(COURSE_CLEAR) != null ? COURSE_CLEAR : RESULT_CLEAR);
		stop(getSound(COURSE_FAIL) != null ? COURSE_FAIL : RESULT_FAIL);
		stop(getSound(COURSE_CLOSE) != null ? COURSE_CLOSE : RESULT_CLOSE);
	}

	public void render() {
		long time = timer.getNowTime();
		timer.switchTimer(TIMER_RESULTGRAPH_BEGIN, true);
		timer.switchTimer(TIMER_RESULTGRAPH_END, true);
		timer.switchTimer(TIMER_RESULT_UPDATESCORE, true);

		if(time > getSkin().getInput()){
			timer.switchTimer(TIMER_STARTINPUT, true);
		}

		if (timer.isTimerOn(TIMER_FADEOUT)) {
			if (timer.getNowTime(TIMER_FADEOUT) > getSkin().getFadeout()) {
				resource.getPlayerConfig().setGauge(resource.getOrgGaugeOption());
				main.changeState(MainStateType.MUSICSELECT);
			}
		} else if (time > getSkin().getScene()) {
			timer.switchTimer(TIMER_FADEOUT, true);
			if(getSound(COURSE_CLOSE) != null || getSound(RESULT_CLOSE) != null) {
				stop(getSound(COURSE_CLEAR) != null ? COURSE_CLEAR : RESULT_CLEAR);
				stop(getSound(COURSE_FAIL) != null ? COURSE_FAIL : RESULT_FAIL);
				play(getSound(COURSE_CLOSE) != null ? COURSE_CLOSE : RESULT_CLOSE);
			}
		}

	}

	public void input() {
		super.input();
		final BMSPlayerInputProcessor inputProcessor = main.getInputProcessor();

		if (!timer.isTimerOn(TIMER_FADEOUT) && timer.isTimerOn(TIMER_STARTINPUT)) {
			boolean ok = false;
			for (int i = 0; i < property.getAssignLength(); i++) {
				if (property.getAssign(i) == ResultKeyProperty.ResultKey.CHANGE_GRAPH && inputProcessor.getKeyState(i) && inputProcessor.resetKeyChangedTime(i)) {
					gaugeType = (gaugeType - 5) % 3 + 6;
				} else if (property.getAssign(i) != null && inputProcessor.getKeyState(i) && inputProcessor.resetKeyChangedTime(i)) {
					ok = true;
				}
			}

			if (inputProcessor.isControlKeyPressed(ControlKeys.ESCAPE) || inputProcessor.isControlKeyPressed(ControlKeys.ENTER)) {
				ok = true;
			}

			if (resource.getScoreData() == null || ok) {
				if (((CourseResultSkin) getSkin()).getRankTime() != 0 && !timer.isTimerOn(TIMER_RESULT_UPDATESCORE)) {
					timer.switchTimer(TIMER_RESULT_UPDATESCORE, true);
				} else if (state == STATE_OFFLINE || state == STATE_IR_FINISHED){
					timer.switchTimer(TIMER_FADEOUT, true);
					if(getSound(COURSE_CLOSE) != null || getSound(RESULT_CLOSE) != null) {
						stop(getSound(COURSE_CLEAR) != null ? COURSE_CLEAR : RESULT_CLEAR);
						stop(getSound(COURSE_FAIL) != null ? COURSE_FAIL : RESULT_FAIL);
						play(getSound(COURSE_CLOSE) != null ? COURSE_CLOSE : RESULT_CLOSE);
					}
				}
			}

			if(inputProcessor.isControlKeyPressed(ControlKeys.NUM1)) {
				saveReplayData(0);
			} else if(inputProcessor.isControlKeyPressed(ControlKeys.NUM2)) {
				saveReplayData(1);
			} else if(inputProcessor.isControlKeyPressed(ControlKeys.NUM3)) {
				saveReplayData(2);
			} else if(inputProcessor.isControlKeyPressed(ControlKeys.NUM4)) {
				saveReplayData(3);
			}

			if(inputProcessor.isActivated(KeyCommand.OPEN_IR)) {
				this.executeEvent(EventType.open_ir);
			}
		}
	}

	public void updateScoreDatabase() {
		final PlayerConfig config = resource.getPlayerConfig();
		BMSModel[] models = resource.getCourseBMSModels();
		final ScoreData newscore = getNewScore();
		if (newscore == null) {
			return;
		}
		boolean dp = false;
		for (BMSModel model : models) {
			dp |= model.getMode().player == 2;
		}
		newscore.setCombo(resource.getMaxcombo());
		newscore.setAvgjudge(newscore.getTotalDuration() / newscore.getNotes());
		int random = 0;
		if (config.getRandom() > 0
				|| (dp && (config.getRandom2() > 0 || config.getDoubleoption() > 0))) {
			random = 2;
		}
		if (config.getRandom() == 1
				&& (!dp || (config.getRandom2() == 1 && config.getDoubleoption() == 1))) {
			random = 1;
		}

		// FIX: GL Thread 立即返回默认值，DB 读改为异步
		// 否则 GL Thread 在 SQLite 锁上阻塞时会卡住首帧渲染
		oldscore = new ScoreData();
		final int courseTotalNotes = Arrays.asList(resource.getCourseData().getSong()).stream().mapToInt(sd -> sd.getNotes()).sum();
		final int targetExscore = resource.getTargetScoreData() != null ? resource.getTargetScoreData().getExscore() : 0;
		getScoreDataProperty().setTargetScore(0, targetExscore, courseTotalNotes);
		getScoreDataProperty().update(newscore);

		final boolean isPlayMode = resource.getPlayMode().mode == BMSPlayerMode.Mode.PLAY;
		final int randomToSave = random;
		final int lnModeToSave = config.getLnmode();
		final bms.player.beatoraja.CourseData.CourseDataConstraint[] constraintToSave = resource.getConstraint();
		final boolean isUpdateCourseScore = resource.isUpdateCourseScore();
		final ScoreData newscoreFinal = newscore;
		final BMSModel[] modelsFinal = models;

		// FIX: 异步加载旧分，GL Thread 立即继续
		new Thread(() -> {
			try {
				final ScoreData oldsc = main.getPlayDataAccessor().readScoreData(modelsFinal,
						lnModeToSave, randomToSave, constraintToSave);
				oldscore = oldsc != null ? oldsc : new ScoreData();
				getScoreDataProperty().setTargetScore(oldscore.getExscore(), targetExscore, courseTotalNotes);

				// replay 自动保存：需要在 oldscore 加载完成后才能正确判断
				if (isPlayMode) {
					for (int i = 0; i < REPLAY_SIZE; i++) {
						if (MusicResult.ReplayAutoSaveConstraint.get(resource.getPlayerConfig().getAutoSaveReplay()[i]).isQualified(oldscore, newscoreFinal)) {
							saveReplayData(i);
						}
					}
				}
			} catch (Exception e) {
				Logger.getGlobal().severe("Failed to load old course score: " + e.getMessage());
				e.printStackTrace();
			}
		}, "CourseOldScoreLoadThread").start();

		if (isPlayMode) {
			final ScoreData scoreToSave = newscore;
			final BMSModel[] modelsToSave = models;
			final int randomForWrite = randomToSave;

			// 写分线程：异步执行，与读线程先后顺序无关，
			// 避免与 CourseOldScoreLoadThread 在同一 SQLite 文件上锁竞争
			new Thread(() -> {
				try {
					main.getPlayDataAccessor().writeScoreData(scoreToSave, modelsToSave, lnModeToSave, randomForWrite, constraintToSave, isUpdateCourseScore);
					Logger.getGlobal().info("Course score database update completed ");
				} catch (Exception e) {
					Logger.getGlobal().severe("Failed to save course score: " + e.getMessage());
					e.printStackTrace();
				}
			}, "CourseScoreWriteThread").start();
		} else {
			Logger.getGlobal().info("プレイモードが" + resource.getPlayMode().mode.name() + "のため、スコア登録はされません");
		}
	}

	public int getJudgeCount(int judge, boolean fast) {
		final ScoreData score = resource.getCourseScoreData();
		return score != null ? score.getJudgeCount(judge, fast) : 0;
	}

	@Override
	public void dispose() {
		super.dispose();
	}

	public void saveReplayData(int index) {
		if (resource.getPlayMode().mode == BMSPlayerMode.Mode.PLAY && resource.getCourseScoreData() != null) {
			if (saveReplay[index] != ReplayStatus.SAVED && resource.isUpdateCourseScore()) {
				// 保存されているリプレイデータがない場合は、EASY以上で自動保存
				final ReplayData[] rd = resource.getCourseReplay();
				for(int i = 0; i < rd.length; i++) {
					rd[i].gauge = resource.getPlayerConfig().getGauge();
				}
				final BMSModel[] models = resource.getCourseBMSModels();
				final int lnMode = resource.getPlayerConfig().getLnmode();
				final int replayIndex = index;
				final bms.player.beatoraja.CourseData.CourseDataConstraint[] constraint = resource.getConstraint();

				new Thread(() -> {
					try {
						main.getPlayDataAccessor().wrireReplayData(rd, models, lnMode, replayIndex, constraint);
						saveReplay[replayIndex] = ReplayStatus.SAVED;
						Logger.getGlobal().info("Course replay data saved: index " + replayIndex);
					} catch (Exception e) {
						Logger.getGlobal().severe("Failed to save course replay data: " + e.getMessage());
						e.printStackTrace();
					}
				}, "CourseReplayWriteThread").start();
			}
		}
	}

	public ScoreData getNewScore() {
		return resource.getCourseScoreData();
	}

	static class IRSendStatus {
		public final IRConnection ir;
		public final CourseData course;
		public final int lnmode;
		public final ScoreData score;
		public int retry = 0;

		public IRSendStatus(IRConnection ir, CourseData course, int lnmode, ScoreData score) {
			this.ir = ir;
			this.course = course;
			this.lnmode = lnmode;
			this.score = score;
		}

		public boolean send() {
			Logger.getGlobal().info("IRへスコア送信中 : " + course.getName());
            IRResponse<Object> send1 = ir.sendCoursePlayData(new IRCourseData(course, lnmode), new bms.player.beatoraja.ir.IRScoreData(score));
            if(send1.isSucceeded()) {
                Logger.getGlobal().info("IRスコア送信完了 : " + course.getName());
                retry = -255;
                return true;
            } else {
                Logger.getGlobal().warning("IRスコア送信失敗 : " + send1.getMessage());
                retry++;
                return false;
            }

		}
	}
}
