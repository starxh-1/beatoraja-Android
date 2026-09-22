package bms.player.beatoraja.play;

import static bms.player.beatoraja.skin.SkinProperty.TIMER_PLAY;

import java.util.Comparator;

import com.badlogic.gdx.utils.Array;

import bms.model.BMSModel;
import bms.model.Note;
import bms.model.TimeLine;
import bms.player.beatoraja.Config;
import bms.player.beatoraja.audio.AudioDriver;

/**
 * キー音処理用クラス
 * 
 * @author exch
 */
public class KeySoundProcessor {

	private final BMSPlayer player;
	
	private final AudioDriver audio;
	/**
	 * BGレーン再生用スレッド
	 */
	private AutoplayThread autoThread;

	/**
	 * 復帰再生用音源の生成待ちの上限(ms)。これを超えたら待たずにプレイを始める。
	 * 「BGMが少し遅れて鳴る」より「プレイを始められない」ほうが問題なので、待ちは必ず打ち切る。
	 */
	private static final long BG_RESUME_PREPARE_TIMEOUT_MS = 5000;

	public KeySoundProcessor(BMSPlayer player) {
		this.player = player;
		audio = player.main.getAudioProcessor();
	}

	/**
	 * 練習モードで開始位置が確定した時点で呼ぶ。開始位置を跨いでまだ鳴っている長いBGM
	 * (例: 途中まで鳴っているBGMを 0:50 から練習するケース)の復帰再生用に、
	 * 「開始位置以降」だけの音源を事前生成しておく(再生はしない)。
	 *
	 * <p>この生成は「PCMデコード → 一時WAV書き出し → nativeでの再デコード」を含み、
	 * 長いBGMでは1秒以上かかる。プレイ開始後に初めて生成すると、生成が終わった時点で
	 * ようやく鳴り始めるためBGMが譜面から1秒前後遅れて鳴る(2回目以降はキャッシュが
	 * 効くので遅れない)。ここで生成だけ済ませ、{@link #startBGPlay} ではその音源を鳴らす。
	 *
	 * <p>同じ model・同じ開始位置で既に事前生成が走っている場合
	 * ({@link #prepareBGPlayInPracticeMenu})は作り直さずそれを使う。作り直すと生成が
	 * 二重に走るだけで、待ち時間が増える。
	 */
	public void prepareBGPlay(BMSModel model, long starttime) {
		prepareBGPlay(model, starttime, false);
	}

	/**
	 * 練習メニューを表示している間に、開始位置が変わるたびに呼ぶ。同じ開始位置向けの
	 * 復帰再生用音源を先に作っておくことで、開始時に STATE_READY で待たされる時間を
	 * ほぼ無くす(長い曲では生成に2〜4秒かかることがある)。
	 *
	 * <p>この時点の model はまだ PracticeModifier で加工されていない。加工されると
	 * 「開始位置より前」のノートはレーンを問わず BG レーンへ移され、復帰再生の対象になる。
	 * どのノートが移されるか加工前には分からないので、全レーンを対象に生成しておく。
	 * 余分に作った分は再生されないだけで害は無い(生成物のキャッシュキーは
	 * 「音源パス + 音源内オフセット + 長さ」で、加工の影響を受けない)。
	 */
	public void prepareBGPlayInPracticeMenu(BMSModel model, long starttime) {
		prepareBGPlay(model, starttime, true);
	}

	/**
	 * 復帰再生用音源の事前生成を開始する。
	 *
	 * @param allLanes
	 *            全レーンを生成対象にするか。練習メニュー表示中(＝加工前の model)は true、
	 *            開始位置確定後(＝加工後)は BG レーンだけで足りるので false。
	 */
	private void prepareBGPlay(BMSModel model, long starttime, boolean allLanes) {
		final AutoplayThread t = autoThread;
		if (t != null && t.acceptStart(model, starttime)) {
			// 同じ model・同じ開始位置で既に事前生成済み(または生成中)。作り直さない
			return;
		}
		stopBGPlay();
		autoThread = new AutoplayThread(model, starttime, true, allLanes);
		autoThread.start();
	}

	public void startBGPlay(BMSModel model, long starttime) {
		final AutoplayThread t = autoThread;
		if (t != null && t.acceptStart(model, starttime)) {
			// 事前生成済み(または生成中)のスレッドをそのまま使う。生成中なら生成が終わり次第鳴らす。
			t.startPlay();
			return;
		}
		// 事前生成スレッドが使い回せない場合は止めてから作り直す(合図待ちのまま残さない)
		stopBGPlay();
		autoThread = new AutoplayThread(model, starttime, false, false);
		autoThread.start();
	}

	/**
	 * 復帰再生用音源の生成が完了しているか。事前生成していない場合は true(プレイ開始を待たせない)。
	 */
	public boolean isBGResumePrepared() {
		final AutoplayThread t = autoThread;
		if (t == null || t.prepared) {
			return true;
		}
		// 生成が異常に長引いた場合は待たない(遅れて鳴るのは許容するが、開始できないのは許容しない)
		return System.nanoTime() - t.createdNanos > BG_RESUME_PREPARE_TIMEOUT_MS * 1000000L;
	}

	public void stopBGPlay() {
		final AutoplayThread t = autoThread;
		if (t != null) {
			t.requestStop();
		}
	}

	/**
	 * BGレーン再生用スレッド
	 *
	 * @author exch
	 */
	class AutoplayThread extends Thread {

		/**
		 * {@link #startPlay()} の合図を待ってから再生を始めるか。
		 * 練習モードでは開始位置確定時にこのモードで起動し、プレイ開始前に生成を終わらせる。
		 */
		private final boolean prepareOnly;

		/**
		 * 生成時に全レーンを対象にするか(練習メニュー表示中の事前生成)。
		 * {@link #prepareBGPlayInPracticeMenu} を参照。
		 */
		private final boolean allLanes;

		/** 対象モデル。事前生成済みスレッドを使い回せるかの判定に使う */
		private final BMSModel model;

		private volatile boolean stop = false;
		/** 復帰再生用音源の生成が完了したか */
		volatile boolean prepared = false;
		/** 再生開始の合図が来たか */
		private volatile boolean playing = false;
		private final Object startSignal = new Object();

		private final long starttime;
		/** 生成に使うタイムライン。スレッド生成時(加工前)のスナップショット */
		final TimeLine[] timelines;

		/**
		 * 再生に使うタイムライン。{@link #startPlay()} で「加工後」の model から取り直す。
		 *
		 * <p>事前生成は加工前の model で走るが、PracticeModifier は開始位置より前の
		 * 可視ノートを BG レーンへ移す。したがって加工前の BG レーンだけを再生対象にすると、
		 * それらの音が鳴らない。再生直前に取り直すことで一致させる。
		 */
		private volatile TimeLine[] playTimelines;

		/** スレッド生成時刻(単調時間)。生成待ちの打ち切り判定に使う */
		volatile long createdNanos = System.nanoTime();

		public AutoplayThread(BMSModel model, long starttime, boolean prepareOnly, boolean allLanes) {
			this.model = model;
			this.starttime = starttime;
			this.prepareOnly = prepareOnly;
			this.allLanes = allLanes;
			Array<TimeLine> tls = new Array<TimeLine>();
			for(TimeLine tl : model.getAllTimeLines()) {
				// 事前生成(全レーン)では、可視ノートも加工後に BG レーンへ移り得るので対象に含める
				if(tl.getBackGroundNotes().length > 0 || (allLanes && tl.existNote())) {
					tls.add(tl);
				}
			}
			timelines = tls.toArray(TimeLine.class);
		}

		/** このスレッドを再生開始の合図に使えるか(事前生成待ちの間のみtrue) */
		boolean acceptStart(BMSModel model, long starttime) {
			return prepareOnly && !playing && !stop && this.starttime == starttime && this.model == model;
		}

		/** 再生開始の合図。生成中でも、生成が終わり次第再生を始める */
		void startPlay() {
			// 再生対象は加工後の model から取り直す(playTimelines の説明を参照)。
			// 生成対象(wav ID と音源内オフセット)は加工の影響を受けないので、
			// この時点で生成が終わっていなくても生成物はそのまま使える。
			final BMSModel m = this.model;
			if (m != null) {
				Array<TimeLine> tls = new Array<TimeLine>();
				for (TimeLine tl : m.getAllTimeLines()) {
					if (tl.getBackGroundNotes().length > 0) {
						tls.add(tl);
					}
				}
				playTimelines = tls.toArray(TimeLine.class);
			}
			synchronized (startSignal) {
				playing = true;
				startSignal.notifyAll();
			}
		}

		void requestStop() {
			stop = true;
			synchronized (startSignal) {
				startSignal.notifyAll();
			}
		}

		@Override
		public void run() {
			final Config config = player.resource.getConfig();
			final java.util.logging.Logger log = java.util.logging.Logger.getGlobal();
			log.info("[BGRESUME] start. starttime=" + starttime + "us(" + (starttime / 1000)
					+ "ms), timelines=" + timelines.length + (prepareOnly ? " prepareOnly=true" : "")
					+ (allLanes ? " allLanes=true" : ""));

			// 開始位置より前のBGノートは原則スキップする。ただし「開始位置を跨いでまだ鳴っている」長い
			// BGM(例: 0:40 から 0:58 まで鳴る音源を 0:50 から練習するケース)は、本来の経過時間ぶんだけ
			// 進めた位置から復帰再生する。これをしないと冒頭の数秒が無音になる。
			// 生成(重い処理)はプレイ開始前に済ませておき、プレイ開始時は生成済みのものを鳴らす。
			try {
				if (allLanes) {
					prepareForPracticeMenu(config, timelines);
				} else {
					resumeBeforeStart(false, config, timelines);
				}
			} catch (Exception e) {
				log.warning("[BGRESUME] prepare failed: " + e);
			} finally {
				// 生成に失敗してもプレイ開始を待たせない(無音/遅れは許容し、ハングは許容しない)
				prepared = true;
			}
			if (stop) {
				return;
			}
			if (prepareOnly) {
				// プレイ開始の合図を待つ(wait(timeout)なので stop でも抜けられる)
				synchronized (startSignal) {
					while (!playing && !stop) {
						try {
							startSignal.wait(50);
						} catch (InterruptedException e) {
						}
					}
				}
				if (stop) {
					return;
				}
			}

			final TimeLine[] tls = playTimelines != null ? playTimelines : timelines;
			int p = 0;
			try {
				p = resumeBeforeStart(true, config, tls);
			} catch (Exception e) {
				log.warning("[BGRESUME] resume failed: " + e);
			}
			final long lasttime = tls.length > 0 ?
					tls[tls.length - 1].getMicroTime() + player.getMaxTailMs() * 1000 : 0;

			while (!stop) {
				final long time = player.timer.getNowMicroTime(TIMER_PLAY);
				// BGレーン再生
				while (p < tls.length && tls[p].getMicroTime() <= time) {
					for (Note n : tls[p].getBackGroundNotes()) {
						audio.play(n, config.getAudioConfig().getBgvolume(), 0);
					}
					p++;
				}
				if (p < tls.length) {
					try {
						final long sleeptime = tls[p].getMicroTime() - time;
						if (sleeptime > 0) {
							sleep(sleeptime / 1000);
						}
					} catch (InterruptedException e) {
					}
				}
				if (time >= lasttime) {
					break;
				}
			}
		}

		/**
		 * 練習メニュー中の事前生成。全レーンを走査して復帰再生の対象を集め、生成する。
		 *
		 * <p>全レーンを見るのは、この時点の model がまだ PracticeModifier で加工されて
		 * いないため。加工されると開始位置より前のノートはレーンを問わず BG レーンへ
		 * 移され、復帰再生の対象になる。余分に作った分は再生されないだけで害は無い
		 * (キャッシュキーは音源パスと音源内オフセットだけで決まり、加工の影響を受けない)。
		 *
		 * <p>生成は短い音源から順に行う。{@link AudioDriver#prepareOffsetSound} は
		 * ドライバのロックを取るため、長い音源(1秒以上かかる)を先に作ると、その間
		 * メニューの操作音が一切鳴らなくなる。長さ順に片付ければロック保持は短く済む。
		 */
		private void prepareForPracticeMenu(Config config, TimeLine[] tls) {
			final long beginNanos = System.nanoTime();
			final Array<ResumeCandidate> candidates = new Array<ResumeCandidate>();
			int p = 0;
			int skippedTl = 0;
			while (p < tls.length && tls[p].getMicroTime() < starttime) {
				if (stop) {
					// 開始位置が変えられて作り直された等。途中で切り上げる
					break;
				}
				final TimeLine tl = tls[p];
				final long elapsed = starttime - tl.getMicroTime();
				for (int i = 0; i < tl.getLaneCount(); i++) {
					addCandidate(candidates, tl.getNote(i), elapsed);
				}
				for (Note n : tl.getBackGroundNotes()) {
					addCandidate(candidates, n, elapsed);
				}
				p++;
				skippedTl++;
			}
			candidates.sort(new Comparator<ResumeCandidate>() {
				@Override
				public int compare(ResumeCandidate a, ResumeCandidate b) {
					return Long.compare(a.length, b.length);
				}
			});
			int done = 0;
			for (ResumeCandidate c : candidates) {
				if (stop) {
					break;
				}
				if (audio.prepareOffsetSound(c.note, c.elapsed)) {
					done++;
				}
			}
			java.util.logging.Logger.getGlobal().info("[BGRESUME] prepared. skippedTimelines=" + skippedTl
					+ " candidates=" + candidates.size + " prepared=" + done + " nextIndex=" + p
					+ " elapsed=" + (System.nanoTime() - beginNanos) / 1000000 + "ms");
		}

		/** 開始位置を跨いでまだ鳴っているノートなら候補に加える。 */
		private void addCandidate(Array<ResumeCandidate> candidates, Note n, long elapsed) {
			if (n == null) {
				return;
			}
			final long length = audio.getSoundLengthMicros(n);
			if (length > 0 && elapsed < length) {
				candidates.add(new ResumeCandidate(n, elapsed, length));
			}
		}

		/**
		 * 開始位置より前のBGノートを処理し、通常再生を始める位置(タイムラインのインデックス)を返す。
		 *
		 * @param play
		 *            true なら復帰再生する。false なら復帰再生用音源の生成のみ行う
		 * @param tls
		 *            対象のタイムライン
		 */
		private int resumeBeforeStart(boolean play, Config config, TimeLine[] tls) {
			final long beginNanos = System.nanoTime();
			final float bgvolume = config.getAudioConfig().getBgvolume();
			int p = 0;
			int skippedTl = 0;
			int candidates = 0;
			int done = 0;
			while (p < tls.length && tls[p].getMicroTime() < starttime) {
				if (stop) {
					// 開始位置が変えられて作り直された等。途中で切り上げる
					break;
				}
				final TimeLine tl = tls[p];
				final long elapsed = starttime - tl.getMicroTime();
				for (Note n : tl.getBackGroundNotes()) {
					final long length = audio.getSoundLengthMicros(n);
					if (length > 0 && elapsed < length) {
						candidates++;
						if (play) {
							// 実際に鳴らせたものだけ数える(getSoundLengthMicros が長さを返しても
							// 音源の生成に失敗すれば無音になるため、それを「復帰成功」と数えない)
							if (audio.play(n, bgvolume, 0, elapsed)) {
								done++;
							}
						} else if (audio.prepareOffsetSound(n, elapsed)) {
							done++;
						}
					}
				}
				p++;
				skippedTl++;
			}
			java.util.logging.Logger.getGlobal().info("[BGRESUME] " + (play ? "done." : "prepared.")
					+ " skippedTimelines=" + skippedTl + " candidates=" + candidates
					+ (play ? " resumed=" : " prepared=") + done + " nextIndex=" + p
					+ " elapsed=" + (System.nanoTime() - beginNanos) / 1000000 + "ms");
			return p;
		}
	}

	/** 復帰再生の対象になったノート。生成順の並べ替えに使う */
	static class ResumeCandidate {
		final Note note;
		final long elapsed;
		final long length;

		ResumeCandidate(Note note, long elapsed, long length) {
			this.note = note;
			this.elapsed = elapsed;
			this.length = length;
		}
	}
}
