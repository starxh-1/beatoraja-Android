package bms.player.beatoraja;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import bms.player.beatoraja.skin.SkinProperty;

public class TimerManagerTest {

	@Test
	public void setMainStateInitializesTimersAsOff() {
		TimerManager timer = new TimerManager();

		timer.setMainState(null);

		assertFalse(timer.isTimerOn(SkinProperty.TIMER_PLAY));
	}

	@Test
	public void addMicroTimerAppliesAtomicDelta() {
		TimerManager timer = new TimerManager();
		timer.setMicroTimer(SkinProperty.TIMER_PLAY, 1000);

		long updated = timer.addMicroTimer(SkinProperty.TIMER_PLAY, -250);

		assertEquals(750, updated);
		assertEquals(750, timer.getMicroTimer(SkinProperty.TIMER_PLAY));
	}

	@Test
	public void addMicroTimerKeepsInactiveTimerOff() {
		TimerManager timer = new TimerManager();
		timer.setMainState(null);

		long updated = timer.addMicroTimer(SkinProperty.TIMER_PLAY, 1000);

		assertEquals(Long.MIN_VALUE, updated);
		assertFalse(timer.isTimerOn(SkinProperty.TIMER_PLAY));
	}

	@Test
	public void addMicroTimerIsSafeForConcurrentWriters() throws InterruptedException {
		TimerManager timer = new TimerManager();
		timer.setMicroTimer(SkinProperty.TIMER_PLAY, 0);
		Thread[] threads = new Thread[4];

		for (int i = 0; i < threads.length; i++) {
			threads[i] = new Thread(() -> {
				for (int j = 0; j < 10000; j++) {
					timer.addMicroTimer(SkinProperty.TIMER_PLAY, 1);
				}
			});
			threads[i].start();
		}

		for (Thread thread : threads) {
			thread.join();
		}

		assertEquals(40000, timer.getMicroTimer(SkinProperty.TIMER_PLAY));
	}
}
