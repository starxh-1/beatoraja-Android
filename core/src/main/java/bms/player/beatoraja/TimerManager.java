package bms.player.beatoraja;

import java.util.concurrent.atomic.AtomicLongArray;

import bms.player.beatoraja.skin.SkinProperty;

public class TimerManager {

    private static final long INVALID_TIMER = Long.MIN_VALUE;

	/**
	 * 状態の開始時間
	 */
	private volatile long starttime;
	private long nowmicrotime;

	public static final int timerCount = SkinProperty.TIMER_MAX + 1;
	private final AtomicLongArray timer = new AtomicLongArray(timerCount);

	private volatile MainState current;

	public long getStartTime() {
		return starttime / 1000000;
	}

	public long getStartMicroTime() {
		return starttime / 1000;
	}

	public long getNowTime() {
		return getNowMicroTime() / 1000;
	}

	public long getNowTime(int id) {
		if(isTimerOn(id)) {
			return (getNowMicroTime() - getMicroTimer(id)) / 1000;
		}
		return 0;
	}

	/**
	 * 实时返回当前微秒时间。不再依赖帧率，直接从 System.nanoTime() 计算。
	 */
	public long getNowMicroTime() {
		return (System.nanoTime() - starttime) / 1000;
	}

	public long getNowMicroTime(int id) {
		if(isTimerOn(id)) {
			return getNowMicroTime() - getMicroTimer(id);
		}
		return 0;
	}

	public long getTimer(int id) {
		return getMicroTimer(id) / 1000;
	}

	public long getMicroTimer(int id) {
		if (id >= 0 && id < timerCount) {
			return timer.get(id);
		} else {
			return current.getSkin().getMicroCustomTimer(id);
		}
	}

	public boolean isTimerOn(int id) {
		return getMicroTimer(id) != Long.MIN_VALUE;
	}

	public void setTimerOn(int id) {
		setMicroTimer(id, getNowMicroTime());
	}

	public void setTimerOff(int id) {
		setMicroTimer(id, Long.MIN_VALUE);
	}

	public void setMicroTimer(int id, long microtime) {
		if (id >= 0 && id < timerCount) {
			timer.set(id, microtime);
		} else {
			current.getSkin().setMicroCustomTimer(id, microtime);
		}
	}

	public long addMicroTimer(int id, long deltaMicrotime) {
		if (id >= 0 && id < timerCount) {
			while (true) {
				final long current = timer.get(id);
				if (current == INVALID_TIMER) {
					return INVALID_TIMER;
				}
				final long updated = current + deltaMicrotime;
				if (timer.compareAndSet(id, current, updated)) {
					return updated;
				}
			}
		}
		final long microtime = current.getSkin().getMicroCustomTimer(id) + deltaMicrotime;
		current.getSkin().setMicroCustomTimer(id, microtime);
		return microtime;
	}

	public void switchTimer(int id, boolean on) {
		if(on) {
			if(getMicroTimer(id) == Long.MIN_VALUE) {
				setMicroTimer(id, getNowMicroTime());
			}
		} else {
			setMicroTimer(id, Long.MIN_VALUE);
		}
	}

	public MainState getMainState() {
		return current;
	}

	public void setMainState(MainState current) {
		this.current = current;

		for (int i = 0; i < timerCount; i++) {
			timer.set(i, INVALID_TIMER);
		}
		starttime = System.nanoTime();
		nowmicrotime = 0;
	}

	public void update() {
		nowmicrotime = ((System.nanoTime() - starttime) / 1000);
	}
}
