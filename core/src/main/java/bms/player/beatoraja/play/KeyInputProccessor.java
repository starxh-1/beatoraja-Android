package bms.player.beatoraja.play;

import static bms.player.beatoraja.skin.SkinProperty.*;

import java.util.logging.Logger;

import bms.model.BMSModel;
import bms.model.TimeLine;
import bms.player.beatoraja.BMSPlayerMode;
import bms.player.beatoraja.MainController;
import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import bms.player.beatoraja.input.KeyInputLog;
import bms.player.beatoraja.skin.SkinPropertyMapper;
import java.util.concurrent.locks.LockSupport;

/**
 * キー入力処理用スレッド
 *
 * @author exch
 */
class KeyInputProccessor {

	private final BMSPlayer player;

	private JudgeThread judge;

	private long prevtime = -1;
	private int[] scratch;
	private int[] scratchKey;

	private final LaneProperty laneProperty;

	//キービーム判定同期用
	private boolean isJudgeStarted = false;

	//キービーム停止用
	private boolean keyBeamStop = false;

	public KeyInputProccessor(BMSPlayer player, LaneProperty laneProperty) {
		this.player = player;
		this.laneProperty = laneProperty;
		this.scratch = new int[laneProperty.getScratchKeyAssign().length];
		this.scratchKey = new int[laneProperty.getScratchKeyAssign().length];
	}

	public void startJudge(BMSModel model, KeyInputLog[] keylog, long milliMarginTime) {
		judge = new JudgeThread(model.getAllTimeLines(), keylog, milliMarginTime);
		judge.start();
		isJudgeStarted = true;
	}

	public void input() {
		final MainController main = player.main;
		final long now = player.timer.getNowTime();
		final BMSPlayerInputProcessor input = main.getInputProcessor();
		final long[] auto_presstime = player.getJudgeManager().getAutoPresstime();

		final int[] laneoffset = laneProperty.getLaneSkinOffset();
		for (int lane = 0; lane < laneoffset.length; lane++) {
			// キービームフラグON/OFF
			final int offset = laneoffset[lane];
			boolean pressed = false;
			boolean scratch = false;
			if(!keyBeamStop) {
				for (int key : laneProperty.getLaneKeyAssign()[lane]) {
					if (input.getKeyState(key) || auto_presstime[key] != Long.MIN_VALUE) {
						pressed = true;
						if(laneProperty.getLaneScratchAssign()[lane] != -1
								&& scratchKey[laneProperty.getLaneScratchAssign()[lane]] != key) {
							scratch = true;
							scratchKey[laneProperty.getLaneScratchAssign()[lane]] = key;
						}
					}
				}
			}
			final int timerOn = SkinPropertyMapper.keyOnTimerId(laneProperty.getLanePlayer()[lane], offset);
			final int timerOff = SkinPropertyMapper.keyOffTimerId(laneProperty.getLanePlayer()[lane], offset);
			if (pressed) {
				if (!player.timer.isTimerOn(timerOn) || scratch) {
					player.timer.setTimerOn(timerOn);
					player.timer.setTimerOff(timerOff);
				}
			} else {
				if (player.timer.isTimerOn(timerOn)) {
					player.timer.setTimerOn(timerOff);
					player.timer.setTimerOff(timerOn);
				}
			}
		}

		if(prevtime >= 0) {
			final long deltatime = now - prevtime;
			for (int s = 0; s < scratch.length; s++) {
				scratch[s] += s % 2 == 0 ? 2160 - deltatime : deltatime;
				final int key0 = laneProperty.getScratchKeyAssign()[s][1];
				final int key1 = laneProperty.getScratchKeyAssign()[s][0];
				if (input.getKeyState(key0) || auto_presstime[key0] != Long.MIN_VALUE) {
					scratch[s] += deltatime * 2;
				} else if (input.getKeyState(key1) || auto_presstime[key1] != Long.MIN_VALUE) {
					scratch[s] += 2160 - deltatime * 2;
				}
				scratch[s] %= 2160;

				main.getOffset(OFFSET_SCRATCHANGLE_1P + s).r = scratch[s] / 6;
			}
		}
		prevtime = now;
	}

	// キービームフラグON 判定同期用
	public void inputKeyOn(int lane) {
		final int offset = laneProperty.getLaneSkinOffset()[lane];
		if(!keyBeamStop) {
			final int timerOn = SkinPropertyMapper.keyOnTimerId(laneProperty.getLanePlayer()[lane], offset);
			final int timerOff = SkinPropertyMapper.keyOffTimerId(laneProperty.getLanePlayer()[lane], offset);
			if (!player.timer.isTimerOn(timerOn) || laneProperty.getLaneScratchAssign()[lane] != -1) {
				player.timer.setTimerOn(timerOn);
				player.timer.setTimerOff(timerOff);
			}
		}
	}

	public void stopJudge() {
		if (judge != null) {
			keyBeamStop = true;
			isJudgeStarted = false;
			judge.stop = true;
			judge = null;
		}
	}

	public void setKeyBeamStop(boolean inputStop) {
		this.keyBeamStop = inputStop;
	}

	/**
	 * プレイログからのキー自動入力、判定処理用スレッド
	 */
	class JudgeThread extends Thread {

		// TODO 判定処理スレッドはJudgeManagerに渡した方がいいかも

		private final TimeLine[] timelines;
		private volatile boolean stop = false;
		/**
		 * 自動入力するキー入力ログ
		 */
		private final KeyInputLog[] keylog;
		private final long microMarginTime;

		public JudgeThread(TimeLine[] timelines, KeyInputLog[] keylog, long milliMarginTime) {
			this.timelines = timelines;
			this.keylog = keylog;
			this.microMarginTime = milliMarginTime * 1000;
		}

		@Override
		public void run() {

			int index = 0;

			long frametime = 1;
			final BMSPlayerInputProcessor input = player.main.getInputProcessor();
			final JudgeManager judge = player.getJudgeManager();
			final long lasttime = timelines[timelines.length - 1].getMicroTime() + player.getMaxTailMs() * 1000;

			// 硬编码 1000Hz 轮询频率（Endless Dream upstream 修复）
			final long pollIntervalNs = 1_000_000L; // 1000Hz = 1ms
			long nextPollTime = System.nanoTime();

			long prevtime = -1;
			long prevClockTime = -1;
			while (!stop) {
				final long clockTime = player.timer.getNowMicroTime();
				if (prevClockTime != -1) {
					advancePlayTimer(clockTime - prevClockTime);
				}
				prevClockTime = clockTime;

				// 实时读取播放时间。TIMER_PLAY 的变速补偿由本线程以 1000Hz 写入，
				// 避免渲染帧率决定判定时钟的步进。
				final long mtime = player.timer.getNowMicroTime(TIMER_PLAY);

				// リプレイデータ再生
				if (keylog != null) {
					while (index < keylog.length && keylog[index].getTime() + microMarginTime <= mtime) {
						final KeyInputLog key = keylog[index];
						input.setKeyState(key.getKeycode(), key.isPressed(), key.getTime() + microMarginTime);
						index++;
					}
				}

				judge.update(mtime);

				if (prevtime != -1) {
					final long nowtime = mtime - prevtime;
					frametime = nowtime < frametime ? frametime : nowtime;
				}

				prevtime = mtime;

				if (mtime >= lasttime) {
					break;
				}

				// 精确休眠到下一轮询周期（1000Hz），避免 CPU 空转
				nextPollTime += pollIntervalNs;
				final long sleepNs = nextPollTime - System.nanoTime();
				if (sleepNs > 50000) {
					LockSupport.parkNanos(sleepNs);
				}
			}

			if (keylog != null) {
				input.resetAllKeyState();
			}

			Logger.getGlobal().info("入力パフォーマンス(max ms) : " + frametime);
		}

		private void advancePlayTimer(long elapsedMicrotime) {
			if (elapsedMicrotime <= 0
					|| player.getState() != BMSPlayer.STATE_PLAY
					|| !player.timer.isTimerOn(TIMER_PLAY)) {
				return;
			}
			final int speed = player.getPlaySpeed();
			final long deltaPlay = elapsedMicrotime * (100L - speed) / 100L;
			if (deltaPlay != 0) {
				player.timer.addMicroTimer(TIMER_PLAY, deltaPlay);
			}
		}
	}
}
