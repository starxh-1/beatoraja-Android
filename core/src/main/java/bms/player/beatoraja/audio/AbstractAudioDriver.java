package bms.player.beatoraja.audio;

import bms.model.*;
import bms.player.beatoraja.ResourcePool;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.*;

/**
 * 抽象オーディオドライバー
 *
 * @author exch
 *
 * @param <T>
 *            音源データ
 */
public abstract class AbstractAudioDriver<T> implements AudioDriver {

	/**
	 * 効果音マップ
	 */
	private ObjectMap<String, AudioElement<T>> soundmap = new ObjectMap<String, AudioElement<T>>();

	/**
	 * 加载失败的音频缓存，避免重复尝试不存在的文件。
	 */
	private java.util.Set<String> failedLoads = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

	/**
	 * キー音マップ(音切りなし)
	 */
	private T[] wavmap = (T[]) new Object[0];
	/**
	 * キー音マップ(音切りあり)
	 */
	private SliceWav<T>[][] slicesound = new SliceWav[0][0];

	private T[][] additionalKeySounds = (T[][]) new Object[6][2];
	/**
	 * キー音読み込み進捗状況
	 */
	private AtomicInteger progress = new AtomicInteger();
	/**
	 * NoteMap Size
	 */
	private int noteMapSize = 0;
	/**
	 * キー音ボリューム
	 */
	private float volume = 1.0f;
	/**
	 * 音源全体のピッチ
	 */
	private float globalPitch = 1.0f;
	/**
	 * オーディオキャッシュデータ
	 */
	private final AudioCache cache;

	private int sampleRate;
	int channels;

	/**
	 * 上一次加载成功的歌曲 MD5，用于优化重复加载。
	 */
	private String lastModelMD5 = "";

	/**
	 * 復帰再生(オフセット再生)用の音源パス。WAV ID を添字とする。{@link #setModel} で構築する。
	 *
	 * <p>volatile なのは {@link #getSoundLengthMicros} が事前生成スレッドから
	 * <b>モニタを取らずに</b>読むため。書き込み側は setModel の synchronized 区間。
	 */
	private volatile String[] wavPaths = new String[0];

	/**
	 * 音源の長さ(us)のキャッシュ。WAV ID をキーとする。
	 */
	private final java.util.Map<Integer, Long> wavLengthCache = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * 同時に復帰再生を追跡できる最大数。これを超えた分は追跡しない(再生自体は継続する)。
	 * 練習モードの開始位置を跨ぐ長いBG音は通常0〜数個なので十分。
	 */
	private static final int RESUME_TRACK_MAX = 16;

	/**
	 * 現在復帰再生(オフセット再生)中の音の数。通常0。
	 * 0の間は {@link #play0} / {@link #stop0} が配列を一切触らないので、
	 * 毎ノート呼ばれる経路にオーバーヘッドが乗らない(キーのオートボクシングも無し)。
	 */
	private int resumeCount = 0;
	/**
	 * 復帰再生中の音のWAV ID。{@link #resumeWavs} と同じ添字。
	 */
	private final int[] resumeIds = new int[RESUME_TRACK_MAX];
	/**
	 * 復帰再生中の音。{@link #resumeIds} と同じ添字。
	 */
	@SuppressWarnings("unchecked")
	private final T[] resumeWavs = (T[]) new Object[RESUME_TRACK_MAX];

	public AbstractAudioDriver(int maxgen) {
		cache = new AudioCache(Math.max(maxgen, 1));
	}
	/**
	 * パスで指定された効果音ファイルの音源データを取得する
	 *
	 * @param p
	 *            音源データのパス
	 * @return 音源データ
	 */
	protected abstract T getKeySound(File p);

	/**
	 * PCMオブジェクトで指定されたキー音の音源データを取得する
	 *
	 * @param pcm
	 * @return
	 */
	protected abstract T getKeySound(PCM pcm);

	/**
	 * 音源データを開放する
	 *
	 * @param pcm
	 *            開放する音源データ
	 */
	protected abstract void disposeKeySound(T pcm);

	/**
	 * キー音を再生する
	 *
	 * @param wav
	 *            音源データ
	 * @param channel
	 *            チャンネル番号(0-)
	 * @param volume
	 *            ボリューム(0.0-1.0)
	 * @param pitch
	 *            ピッチ(0.5 - 2.0)
	 */
	protected abstract void play(T wav, int channel, float volume, float pitch);

	/**
	 * 効果音を再生する
	 *
	 * @param id
	 *            音源データ
	 * @param volume
	 *            ボリューム(0.0-1.0)
	 * @param loop
	 *            ループ再生するかどうか
	 */
	protected abstract void play(AudioElement<T> id, float volume, boolean loop);

	/**
	 * 効果音のボリュームを設定する。再生中も可能
	 *
	 * @param id
	 *            音源データ
	 * @param volume
	 *            ボリューム(0.0-1.0)
	 */
	protected abstract void setVolume(AudioElement<T> id, float volume);

	/**
	 * 音源データが再生されていれば停止する
	 *
	 * @param id
	 *            音源データ
	 */
	protected abstract boolean isPlaying(T id);

	/**
	 * 音源データが再生されていれば停止する
	 *
	 * @param id
	 *            音源データ
	 */
	protected abstract void stop(T id);

	/**
	 * 音源データが再生されていれば停止する
	 *
	 * @param id
	 *            音源データ
	 * @param channel
	 *            チャンネル番号(0-)
	 */
	protected abstract void stop(T id, int channel);

	/**
	 * 音源データが再生されていればボリュームを設定する。
	 *
	 * @param id
	 *            音源データ
	 * @param channel
	 *            チャンネル番号(0-)
	 * @param volume
	 *            ボリューム(0.0-1.0)
	 */
	protected abstract void setVolume(T id, int channel, float volume);

	public void play(String p, float volume, boolean loop) {
		final AudioElement<T> sound = getSound(p);
		if (sound != null) {
			play(sound, volume, loop);
		}
	}

	private AudioElement<T> getSound(String p) {
		if (p == null || p.length() == 0 || failedLoads.contains(p)) {
			return null;
		}
		AudioElement<T> sound = soundmap.get(p);
		if (!soundmap.containsKey(p)) {
			try {
				T audio = getKeySound(new File(p));
				if (audio != null) {
					sound = new AudioElement(audio);
					soundmap.put(p, sound);
					onSoundLoaded(p, audio);
				} else {
					failedLoads.add(p);
					sound = null;
				}
			} catch (Exception e) {
				Logger.getGlobal().warning("音源読み込み失敗。" + e.getMessage());
				failedLoads.add(p);
				sound = null;
			}
		}
		return sound;
	}

	/** Hook for subclasses to react to a sound being first loaded. Default no-op. */
	protected void onSoundLoaded(String path, T audio) {
		// default no-op
	}

	/** Per-path completion listeners. Protected so subclasses can access via fireCompletion. */
	private final java.util.Map<String, Runnable> completionListeners = new java.util.concurrent.ConcurrentHashMap<>();

	@Override
	public void setOnCompletionListener(String path, Runnable listener) {
		if (path == null || path.length() == 0) {
			return;
		}
		if (listener == null) {
			completionListeners.remove(path);
		} else {
			completionListeners.put(path, listener);
		}
		onCompletionListenerChanged(path, listener);
	}

	/** Hook for subclasses to react to listener changes (e.g. cancel scheduled task). Default no-op. */
	protected void onCompletionListenerChanged(String path, Runnable listener) {
		// default no-op
	}

	/** Subclasses call this when non-looping playback finishes for path. */
	protected final void fireCompletion(String path) {
		if (path == null) return;
		Runnable r = completionListeners.get(path);
		if (r != null) {
			try {
				r.run();
			} catch (Throwable t) {
				Logger.getGlobal().warning("Completion listener for " + path + " threw: " + t.getMessage());
			}
		}
	}

	/** True if a completion listener is registered for this path. */
	protected final boolean hasCompletionListener(String path) {
		return path != null && completionListeners.containsKey(path);
	}

	public void setVolume(String p, float volume) {
		if (p == null || p.length() == 0) {
			return;
		}
		AudioElement<T> sound = soundmap.get(p);
		if (sound != null) {
			setVolume(sound, volume);
		}
	}

	public boolean isPlaying(String p) {
		if (p == null || p.length() == 0) {
			return false;
		}
		AudioElement<T> sound = soundmap.get(p);
		if (sound != null) {
			return isPlaying(sound.audio);
		}
		return false;
	}

	public int getSampleRate() {
		return sampleRate;
	}

	protected void setSampleRate(int sampleRate) {
		this.sampleRate = sampleRate;
	}

	public void stop(String p) {
		if (p == null || p.length() == 0) {
			return;
		}
		AudioElement<T> sound = soundmap.get(p);
		if (sound != null) {
			stop(sound.audio);
		}
	}

	public void dispose(String p) {
		if (p == null || p.length() == 0) {
			return;
		}
		AudioElement<T> sound = soundmap.get(p);
		if (sound != null) {
			soundmap.remove(p);
			disposeKeySound(sound.audio);
		}
	}

	/**
	 * BMSの音源データを読み込む
	 *
	 * <p><b>为什么这个方法被拆成"锁外解码 + 短临界区替换":</b>
	 * {@code play(Note,float,int)} / {@code stop(Note)} 是 synchronized —— 它们和本方法抢
	 * 同一把 monitor。旧实现让本方法在<b>持有 monitor 的状态下</b>完成全部并行解码,于是
	 * 解码的几百毫秒~数秒里,任何想发声的线程都被堵在这里,音符整批迟到。这正是
	 * "BGAutoplay starved for 1771ms" 的来源:不是线程没被调度,是它卡在 monitor 上。
	 *
	 * <p>新的结构是:
	 * <ol>
	 *   <li>阶段 A:先短暂持锁做旧 PCM 释放(必须与后面的解码隔开,否则峰值内存翻倍),
	 *       然后<b>不持锁</b>做并行解码 —— 结果写进<b>局部数组</b>,字段在此期间完全不变,
	 *       播放线程看到的始终是上一份完整可用的 wavmap;</li>
	 *   <li>阶段 B:极短的临界区,只做数组引用替换。</li>
	 * </ol>
	 * 这样 monitor 的持有时间从"整场解码"降到"一次引用赋值"。
	 *
	 * @param model
	 */
	public void setModel(BMSModel model) {
		Logger.getGlobal().info("音源ファイル読み込み開始。");
		final long startNanos = System.nanoTime();

		// 如果是同一首歌，跳过缓存释放以加速加载（Retry 优化）
		final boolean modelChanged = model.getMD5() == null || !model.getMD5().equals(lastModelMD5);

		String[] wavlist = model.getWavList();
		final int wavcount = wavlist.length;
		boolean use_defaultsound = false;

		progress = new AtomicInteger();
		noteMapSize = 0;
		// BMS格納ディレクトリ
		File dpath = new File(model.getPath()).getParentFile();

		if (model.getVolwav() > 0 && model.getVolwav() < 100) {
			volume = model.getVolwav() / 100f;
		} else {
			volume = 1.0f;
		}

		IntMap<List<Note>> notemap = new IntMap<List<Note>>();
		final int lanes = model.getMode().key;
		for (TimeLine tl : model.getAllTimeLines()) {
			for (int i = 0; i < lanes; i++) {
				final Note n = tl.getNote(i);
				if (n != null) {
					// 地雷ノートに音が定義されていない場合のみ、本体側で音の定義を追加
					if (!use_defaultsound && n instanceof MineNote && n.getWav() == wavcount) {
						use_defaultsound = true;
					}
					addNoteList(notemap, n);
					for (Note ln : n.getLayeredNotes()) {
						addNoteList(notemap, ln);
					}
				}
				if (tl.getHiddenNote(i) != null) {
					addNoteList(notemap, tl.getHiddenNote(i));
				}
			}
			for (Note n : tl.getBackGroundNotes()) {
				addNoteList(notemap, n);
			}
		}
		final int size = use_defaultsound ? wavcount + 1 : wavcount;
		// ★ 解码期间绝不改字段:全部写进局部数组,保证对外仍是"上一份完整的 wavmap"
		@SuppressWarnings("unchecked")
		final T[] newWavmap = (T[]) new Object[size];
		@SuppressWarnings("unchecked")
		final Array<SliceWav<T>>[] newSlicesound = (Array<SliceWav<T>>[]) new Array[size];
		// 復帰再生(オフセット再生)用に、WAV ID -> 解決済みパス を控えておく
		final String[] newWavPaths = new String[size];
		noteMapSize = notemap.size;
		Map<Integer, List<Note>> map = new HashMap<>();
		notemap.iterator().forEachRemaining(m -> map.put(m.key, m.value));

		// ---- 阶段 A 前半:短暂持锁。旧 PCM 必须先释放,否则跟新解码叠加会导致峰值内存翻倍 ----
		if (modelChanged) {
			synchronized (this) {
				lastModelMD5 = model.getMD5() != null ? model.getMD5() : "";
				cache.disposeOld();
			}
		}

		// ---- 阶段 A 后半:不持锁的并行解码(耗时主体,数百个音源文件的解压) ----
		map.entrySet().parallelStream().forEach(waventry -> {
			final int wavid = waventry.getKey();
			if (progress.get() >= noteMapSize) {
				return;
			}
			if (wavid < 0) {
				return;
			}
			try {
				String p = null;
				if (wavid < wavcount) {
					File rawPath = new File(dpath, wavlist[wavid]);
					FileHandle[] paths = AudioDriver.getPaths(rawPath.getPath());
					if (paths.length > 0) {
						p = paths[0].path();
					} else {
						p = rawPath.getAbsolutePath();
					}
				} else {
					p = new File("defaultsound/landmine.wav").getAbsolutePath();
				}
				if (wavid < newWavPaths.length) {
					newWavPaths[wavid] = p;
				}
				for (Note note : waventry.getValue()) {
					// 音切りあり・なし両方のデータが必要になるケースがある
					if (note.getMicroStarttime() == 0 && note.getMicroDuration() == 0) {
						// 音切りなしのケース
						newWavmap[wavid] = cache.get(new AudioKey(p, note));
						if (newWavmap[wavid] == null) {
							break;
						}
					} else {
						// 音切りありのケース
						boolean b = true;
						if (newSlicesound[note.getWav()] == null) {
							newSlicesound[note.getWav()] = new Array<SliceWav<T>>();
						}
						for (SliceWav<T> slice : newSlicesound[note.getWav()]) {
							if (slice.starttime == note.getMicroStarttime() && slice.duration == note.getMicroDuration()) {
								b = false;
								break;
							}
						}
						if (b) {
							T sliceaudio = cache.get(new AudioKey(p, note));
							if (sliceaudio != null) {
								newSlicesound[note.getWav()].add(new SliceWav<T>(note, sliceaudio));
							} else {
								return;
							}
						}
					}
				}
			} catch (Exception e) {
				Logger.getGlobal().warning(e.getMessage());
			}
			progress.incrementAndGet();
		});

		final SliceWav<T>[][] newSlicesoundArray = new SliceWav[size][];
		for (int i = 0; i < size; i++) {
			if (newSlicesound[i] != null) {
				newSlicesoundArray[i] = newSlicesound[i].toArray(SliceWav.class);
			} else {
				newSlicesoundArray[i] = new SliceWav[0];
			}
		}

		// ---- 阶段 B:极短临界区,只换引用。CRITICAL_SECTION 之外的时间都不阻塞发声 ----
		synchronized (this) {
			wavmap = newWavmap;
			slicesound = newSlicesoundArray;
			wavPaths = newWavPaths;
			wavLengthCache.clear();
			clearResumeSounds();
		}

		final int prevsize = cache.size();
		// 必须持锁:disposeOld() 会真正释放 PCM。锁外执行的话,play() 可能正拿着
		// 某个即将被释放的对象在发声 —— 又是 use-after-free。
		synchronized (this) {
			cache.disposeOld();
		}
		final long elapsedMs = (System.nanoTime() - startNanos) / 1000000L;
		Logger.getGlobal().info("音源ファイル読み込み完了。音源数:" + size + " 所要 " + elapsedMs
				+ "ms, AudioCache容量 : " + cache.size() + " 開放 : " + (prevsize - cache.size()));

		progress.set(noteMapSize);
	}

	public void setAdditionalKeySound(int judge, boolean fast, String p) {
		if(judge < 0 || judge >= additionalKeySounds.length) {
			return;
		}

		final AudioElement<T> sound = getSound(p);
		additionalKeySounds[judge][fast ? 0 : 1] = sound != null ? sound.audio : null;
	}

	private void addNoteList(IntMap<List<Note>> notemap, Note n) {
		if (n.getWav() < 0) {
			return;
		}
		List<Note> notes = notemap.get(n.getWav());
		if (notes == null) {
			notes = new ArrayList<Note>();
			notemap.put(n.getWav(), notes);
		}

		for (Note note : notes) {
			if (n.getMicroStarttime() == note.getMicroStarttime() && n.getMicroDuration() == note.getMicroDuration()) {
				return;
			}
		}
		notes.add(n);
	}

	public void abort() {
		progress.set(noteMapSize);
	}

	/**
	 * キー音再生。
	 *
	 * synchronized の理由:{@link #play0} / {@link #stop(Note)} は wavmap / slicesound を
	 * 素読みするのに対し、{@link #setModel} はそれらを丸ごと差し替えて古い PCM を解放する。
	 * 無保護だと「再生スレッドが古い PCM を掴んだ直後に setModel が native 側を解放」が起き、
	 * Oboe のような native 音源では use-after-free (SIGSEGV) になる。
	 * setModel と同じモニタを取ることでこの競合を構造的に潰す。
	 */
	public synchronized void play(Note n, float volume, int pitch) {
		play0(n, this.volume * volume, pitch);
		for (Note ln : n.getLayeredNotes()) {
			play0(ln, this.volume * volume, pitch);
		}
	}

	/**
	 * キー音再生(音源の途中から)。練習モードで開始位置を跨ぐ長いBGMを復帰させる用途。
	 * synchronized の理由は {@link #play(Note, float, int)} と同じ。
	 */
	@Override
	public synchronized boolean play(Note n, float volume, int pitch, long offsetMicros) {
		if (n == null) {
			return false;
		}
		if (offsetMicros <= 0) {
			// オフセットなしは通常再生に委譲
			play0(n, this.volume * volume, pitch);
			for (Note ln : n.getLayeredNotes()) {
				play0(ln, this.volume * volume, pitch);
			}
			return true;
		}
		// レイヤー音も含めて1つでも鳴らせたかを返す(診断ログの resumed 数を実際に合わせるため)
		boolean played = playFromOffset(n, this.volume * volume, pitch, offsetMicros);
		for (Note ln : n.getLayeredNotes()) {
			played |= playFromOffset(ln, this.volume * volume, pitch, offsetMicros);
		}
		return played;
	}

	/**
	 * 指定したNoteの音源の長さ(us)を返す。判定できない場合は0以下。
	 *
	 * <p>音切りNoteはその指定長、それ以外はファイル全体の長さ。ファイル長は
	 * {@link PCM#getWavDurationMs(String)} でヘッダのみ解析して求める(デコードしない)。
	 * 結果は WAV ID 単位でキャッシュする。
	 */
	@Override
	public long getSoundLengthMicros(Note n) {
		if (n == null) {
			return -1;
		}
		if (n.getMicroDuration() > 0) {
			return n.getMicroDuration();
		}
		final int id = n.getWav();
		final String[] paths = wavPaths;
		if (id < 0) {
			// WAV 未割り当て(レイヤー/地雷など)。頻出するので警告は出さない
			return -1;
		}
		if (id >= paths.length) {
			// [BGRESUME] 診断: wavPaths の構築漏れ(本来起きない)
			if (lenDiagCount < 8) {
				lenDiagCount++;
				Logger.getGlobal().warning("[BGRESUME] getSoundLengthMicros: wav=" + id
						+ " out of range (wavPaths.length=" + paths.length + ") -> -1");
			}
			return -1;
		}
		final Long cached = wavLengthCache.get(id);
		if (cached != null) {
			return cached;
		}
		final String path = paths[id];
		final long length = path != null ? PCM.getWavDurationMs(path) * 1000L : -1;
		if (length <= 0 && lenDiagCount < 8) {
			lenDiagCount++;
			Logger.getGlobal().warning("[BGRESUME] getSoundLengthMicros: wav=" + id + " path=" + path
					+ " -> length=" + length + "us (ヘッダ解析失敗 or パス解決失敗)");
		}
		wavLengthCache.put(id, length);
		return length;
	}

	/** [BGRESUME] 診断ログの出力回数を抑えるためのカウンタ(復帰再生の調査用)。 */
	private int lenDiagCount = 0;

	/**
	 * 音源の途中からキー音を鳴らす。
	 *
	 * <p>wavmap のSoundは先頭からしか鳴らせない(libGDXのSoundにseekが無い)ため、
	 * PCMをスライスして「offsetMicros 以降」だけのSoundを生成し、それを鳴らす。
	 * 生成物は (path, start, duration) をキーに {@link AudioCache} へ載るので、
	 * 同じ位置からの復帰再生は2回目以降デコード済みのものが使い回される。
	 *
	 * @return 実際に再生を開始できた場合はtrue。音源の生成に失敗した場合はfalse
	 */
	private final boolean playFromOffset(Note n, float volume, int pitchShift, long offsetMicros) {
		try {
			final int id = n.getWav();
			final T sound = getOffsetSound(n, offsetMicros);
			if (sound == null) {
				return false;
			}
			final int channel = channel(id, pitchShift);
			final float pitch = pitchShift != 0 ? (float) Math.pow(2.0, pitchShift / 12.0) : 1.0f;
			// 通常再生と同じく「同じチャンネルの前の音を止めてから鳴らす」
			stop(sound, channel);
			// 同じWAV IDの復帰再生が残っていれば止める。
			// 起点前に同じ長いBGMが複数回発音されている場合、後の発音が前の発音を止めるのが
			// 通常再生(play0)の挙動なので、それに合わせて二重再生を防ぐ。
			if (resumeCount > 0) {
				stopResumeSound(id);
			}
			// 通常再生が同じWAV IDを鳴らした時に重ならないよう記録しておく
			addResumeSound(id, sound);
			Logger.getGlobal().info("[BGRESUME] playFromOffset: wav=" + id + " offset=" + offsetMicros
					+ "us channel=" + channel);
			play(sound, channel, volume, pitch);
			return true;
		} catch (Exception e) {
			// stderr はログに出ないので logcat へ流す(復帰再生は失敗しても無音になるだけで気付きにくい)
			Logger.getGlobal().warning("[BGRESUME] playFromOffset failed: " + e);
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * 「offsetMicros 以降」の音源(Sound)を取得する。キャッシュに無ければ生成する。
	 *
	 * <p>再生はしないので、{@link #prepareOffsetSound} からも使える。
	 * 呼び出し元はこのドライバのモニタを保持していること
	 * ({@link #play(Note, float, int, long)} / {@link #prepareOffsetSound} は synchronized)。
	 *
	 * @return 音源。生成できない場合(音源がオフセット位置より短い、パス解決失敗など)はnull
	 */
	private T getOffsetSound(Note n, long offsetMicros) {
		final int id = n.getWav();
		final String[] paths = wavPaths;
		if (id < 0) {
			return null;
		}
		if (id >= paths.length) {
			Logger.getGlobal().warning("[BGRESUME] offset sound: wav=" + id + " out of range");
			return null;
		}
		final String path = paths[id];
		if (path == null) {
			Logger.getGlobal().warning("[BGRESUME] offset sound: wav=" + id + " path is null");
			return null;
		}
		// 音切りNoteはスライス済み音源を鳴らすので、その先頭からの相対位置に読み替える
		final long noteStart = n.getMicroStarttime();
		final long noteDuration = n.getMicroDuration();
		final long sliceStart = noteStart + offsetMicros;
		final long sliceDuration = noteDuration > 0 ? Math.max(noteDuration - offsetMicros, 0) : 0;
		if (noteDuration > 0 && sliceDuration <= 0) {
			return null; // 既に鳴り終わっている
		}
		final AudioKey key = new AudioKey(path, sliceStart, sliceDuration);
		final boolean cached = cache.exists(key);
		final T sound = cache.get(key);
		if (sound == null) {
			Logger.getGlobal().warning("[BGRESUME] offset sound: slice(" + sliceStart + "," + sliceDuration
					+ ") の生成に失敗 wav=" + id + " path=" + path);
			return null; // 音源がオフセット位置より短い(既に鳴り終わっている)
		}
		if (!cached) {
			// 生成は重い処理なので、どの音源をどの位置で生成したかだけ残す(キャッシュヒット時は出さない)
			Logger.getGlobal().info("[BGRESUME] offset sound generated: wav=" + id + " sliceStart=" + sliceStart
					+ "us sliceDuration=" + sliceDuration + "us");
		}
		return sound;
	}

	/**
	 * 「offsetMicros 以降」の音源を事前に生成する(再生はしない)。
	 *
	 * <p>練習モードで開始位置を跨ぐ長いBGMを、プレイ開始と同時に正しい位置で鳴らすために使う。
	 * 生成をプレイ開始後に初めて行うと、生成が終わった時点でようやく鳴り始めるため
	 * BGMが譜面から遅れて鳴る(2回目以降はキャッシュが効くので遅れない)。
	 * synchronized の理由は {@link #play(Note, float, int, long)} と同じ。
	 */
	@Override
	public synchronized boolean prepareOffsetSound(Note n, long offsetMicros) {
		if (n == null || offsetMicros <= 0) {
			return false;
		}
		final boolean prepared = getOffsetSound(n, offsetMicros) != null;
		for (Note ln : n.getLayeredNotes()) {
			getOffsetSound(ln, offsetMicros);
		}
		return prepared;
	}

	/**
	 * 復帰再生中の音を登録する。呼び出し元は synchronized 文脈。
	 */
	private void addResumeSound(int id, T sound) {
		for (int i = 0; i < resumeCount; i++) {
			if (resumeIds[i] == id) {
				resumeWavs[i] = sound;
				return;
			}
		}
		if (resumeCount < RESUME_TRACK_MAX) {
			resumeIds[resumeCount] = id;
			resumeWavs[resumeCount] = sound;
			resumeCount++;
		}
	}

	/**
	 * 指定WAV IDの復帰再生中の音を止める。呼び出し元は synchronized 文脈。
	 */
	private void stopResumeSound(int id) {
		for (int i = 0; i < resumeCount; i++) {
			if (resumeIds[i] == id) {
				final T sound = resumeWavs[i];
				resumeCount--;
				resumeIds[i] = resumeIds[resumeCount];
				resumeWavs[i] = resumeWavs[resumeCount];
				resumeIds[resumeCount] = -1;
				resumeWavs[resumeCount] = null;
				if (sound != null) {
					stop(sound);
				}
				return;
			}
		}
	}

	/**
	 * 復帰再生中の音を全て止める。呼び出し元は synchronized 文脈。
	 */
	private void stopAllResumeSounds() {
		for (int i = 0; i < resumeCount; i++) {
			final T sound = resumeWavs[i];
			if (sound != null) {
				stop(sound);
			}
			resumeWavs[i] = null;
			resumeIds[i] = -1;
		}
		resumeCount = 0;
	}

	/**
	 * 復帰再生中の追跡情報を捨てる(音は止めない)。呼び出し元は synchronized 文脈。
	 */
	private void clearResumeSounds() {
		for (int i = 0; i < resumeCount; i++) {
			resumeWavs[i] = null;
			resumeIds[i] = -1;
		}
		resumeCount = 0;
	}

	public void play(int judge, boolean fast) {
		if(judge < 0 || judge >= additionalKeySounds.length) {
			return;
		}

		final T sound = additionalKeySounds[judge][fast ? 0 : 1];
		if (sound != null) {
			final int channel = (65536 + judge) * 256;
			stop(sound, channel);
			play(sound, channel, volume, 1.0f);
		}

	}

	private int channel(int id, int pitch) {
		return id * 256 + pitch + 128;
	}

	private final void play0(Note n, float volume, int pitchShift) {
		try {
			final int id = n.getWav();
			if (id < 0) {
				return;
			}
			final int channel = channel(id, pitchShift);
			// 同じWAV IDの復帰再生が残っていれば止める(通常再生と重ならないように)
			if (resumeCount > 0) {
				stopResumeSound(id);
			}
			final float pitch = pitchShift != 0 ? (float)Math.pow(2.0, pitchShift / 12.0) : 1.0f;
			final long starttime = n.getMicroStarttime();
			final long duration = n.getMicroDuration();
			if (starttime == 0 && duration == 0) {
				final T wav = (T) wavmap[id];
				if (wav != null) {
					stop(wav, channel);
					play(wav, channel, volume, pitch);
				}
			} else {
				for (SliceWav<T> slice : slicesound[id]) {
					if (slice.starttime == starttime && slice.duration == duration) {
						stop(slice.wav, channel);
						play(slice.wav, channel, volume, pitch);
						// System.out.println("slice WAV play - ID:" + id +
						// " start:" + starttime + " duration:" + duration);
						break;
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * キー音停止。synchronized の理由は {@link #play(Note, float, int)} と同じ。
	 * 特に {@code stop(null)} は wavmap / slicesound を全走査するため、
	 * {@link #setModel} の差し替えと並走すると解放済み PCM を触る危険が最も高い。
	 */
	public synchronized void stop(Note n) {
		try {
			if (n == null) {
				for (T s : wavmap) {
					if (s != null) {
						stop(s);
					}
				}
				for (SliceWav<T>[] slices : slicesound) {
					for (SliceWav<T> slice : slices) {
						stop(slice.wav);
					}
				}
				// 復帰再生中の音も止める(練習をやり直す時に長いBGMが鳴り続けないように)
				stopAllResumeSounds();
			} else {
				stop0(n);
				for (Note ln : n.getLayeredNotes()) {
					stop0(ln);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private final void stop0(Note n) {
		final int id = n.getWav();
		final int channel = channel(id, 0);
		if (id < 0) {
			return;
		}
		// 復帰再生中の音もWAV IDで止める
		if (resumeCount > 0) {
			stopResumeSound(id);
		}
		final long starttime = n.getMicroStarttime();
		final long duration = n.getMicroDuration();
		if (starttime == 0 && duration == 0) {
			final T sound = (T) wavmap[id];
			if (sound != null) {
				stop(sound, channel);
			}
		} else {
			for (SliceWav<T> slice : slicesound[id]) {
				if (slice.starttime == starttime && slice.duration == duration) {
					stop((T) slice.wav, channel);
					break;
				}
			}
		}
	}

	public void setVolume(Note n, float volume) {
		try {
			if (n == null) {
				return;
			} else {
				setVolume0(n, volume);
				for (Note ln : n.getLayeredNotes()) {
					setVolume0(ln, volume);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private final void setVolume0(Note n, float volume) {
		final int id = n.getWav();
		final int channel = channel(id, 0);
		if (id < 0) {
			return;
		}
		final long starttime = n.getMicroStarttime();
		final long duration = n.getMicroDuration();
		if (starttime == 0 && duration == 0) {
			final T sound = (T) wavmap[id];
			if (sound != null) {
				setVolume(sound, channel, volume);
			}
		} else {
			for (SliceWav<T> slice : slicesound[id]) {
				if (slice.starttime == starttime && slice.duration == duration) {
					setVolume((T) slice.wav, channel, volume);
					break;
				}
			}
		}
	}

	public void setGlobalPitch(float pitch) {
		this.globalPitch = pitch;
	}

	public float getGlobalPitch() {
		return this.globalPitch;
	}

	public float getProgress() {
		return (float)progress.get() / (float)noteMapSize;
	}

	/**
	 * 古い音源を解放する。
	 *
	 * <p>synchronized なのは {@link #clearResumeSounds} のため。復帰再生の追跡配列
	 * ({@code resumeIds}/{@code resumeWavs}/{@code resumeCount})は
	 * {@link #play0}/{@link #stop0} から<b>モニタ保持下で</b>読まれるので、
	 * ここも同じモニタを取らないと「解放済みSoundを stop する」窓ができる。
	 */
	public synchronized void disposeOld() {
		cache.disposeOld();
		// 復帰再生中の参照は、プール側の解放に合わせて手放す(古いSoundを掴み続けない)
		clearResumeSounds();
	}
	/**
	 * リソースを開放する
	 */
	public void dispose() {
		for (AudioElement<T> sound : soundmap.values()) {
			if (sound != null) {
				disposeKeySound(sound.audio);
			}
		}
		soundmap.clear();
		clearResumeSounds();
	}

	/**
	 * 音切りデータ
	 *
	 * @author exch
	 *
	 * @param <T>
	 */
	static class SliceWav<T> {
		public final long starttime;
		public final long duration;
		public final T wav;

		public long playid = -1;

		public SliceWav(Note note, T wav) {
			this.starttime = note.getMicroStarttime();
			this.duration = note.getMicroDuration();
			this.wav = wav;
		}
	}

	class AudioCache extends ResourcePool<AudioKey, T> {

		public AudioCache(int maxgen) {
			super(maxgen);
		}

		private ObjectMap<String, PCM> pcmMap = new ObjectMap<String, PCM>();

		private T loadSlice(AudioKey key) {
            PCM wav = null;
            synchronized(pcmMap) {
                wav = pcmMap.get(key.path);
                if (wav == null) {
                    wav = PCM.load(key.path, AbstractAudioDriver.this);
                    if(wav != null) {
                        pcmMap.put(key.path, wav);
                    }
                }
            }

            if (wav != null) {
                try {
                    final PCM slicewav = wav.slice(key.start, key.duration);
                    return slicewav != null ? getKeySound(slicewav) : null;
                    // System.out.println("WAV slicing - Name:"
                    // + name + " ID:" + note.getWav() +
                    // " start:" + note.getStarttime() +
                    // " duration:" + note.getDuration());
                } catch (Throwable e) {
                    Logger.getGlobal().warning("音源(wav)ファイルスライシング失敗。" + e.getMessage());
                    e.printStackTrace();
                }
            }

            return null;
        }

		@Override
		protected T load(AudioKey key) {
		    Logger.getGlobal().fine("音源ファイルを読み込む中：" + key.path);

		    T sound = key.start == 0 && key.duration == 0
                    ? getKeySound(new File(key.path)) // 音切りなしのケース
                    : loadSlice(key);

		    if (sound == null) {
                Logger.getGlobal().warning("音源ファイル読み込み失敗：" + key.path);
            }
			return sound;
		}


		@Override
		public synchronized void disposeOld() {
			pcmMap.clear();
			super.disposeOld();
		}


		@Override
		protected void dispose(T resource) {
			disposeKeySound(resource);
		}
	}

	static class AudioElement<T> {
		public long id;
		/**
		 * 音源データ
		 */
		public final T audio;

		public AudioElement(T audio) {
			this.audio = audio;
		}
	}

	/**
	 * AudioCache Key
	 *
	 * @author exch
	 */
	private static class AudioKey {
		/**
		 * Audio File path
		 */
		public final String path;
		/**
		 * Audio start time(us)
		 */
		public final long start;
		/**
		 * Audio duration(us)
		 */
		public final long duration;

		public AudioKey(String path, Note n) {
			this.path = path;
			this.start = n.getMicroStarttime();
			this.duration = n.getMicroDuration();
		}

		/**
		 * 復帰再生(オフセット再生)用。Note を介さずに位置を直接指定する。
		 */
		public AudioKey(String path, long start, long duration) {
			this.path = path;
			this.start = start;
			this.duration = duration;
		}

		public boolean equals(Object o) {
			if (o instanceof AudioKey) {
				final AudioKey key = (AudioKey) o;
				return path.equals(key.path) && start == key.start && duration == key.duration;
			}
			return false;
		}

		public int hashCode() {
			return java.util.Objects.hash(path, start, duration);
		}
	}
}
