package bms.player.beatoraja.audio;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import bms.player.beatoraja.Config;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxRuntimeException;

/**
 * libGDX Sound(OpenAL)サウンドドライバ
 *
 * @author exch
 */
public class GdxSoundDriver extends AbstractAudioDriver<Sound> {

	private SoundMixer mixer;

	private final boolean soundthread = false;

	private SoundInstance[] sounds = new SoundInstance[256];
	private int soundPos = 0;

	public GdxSoundDriver(Config config) {
		super(config.getSongResourceGen());
		channels = 2;

		int sampleRate = config.getAudioConfig().getSampleRate();
		if (sampleRate == 0) {
			String prop = System.getProperty("beatoraja.audio.sampleRate");
			if (prop != null) sampleRate = Integer.parseInt(prop);
		}
		if (sampleRate == 0) sampleRate = 44100;
		setSampleRate(sampleRate);

		// 前回起動時の一時WAV(スライスPCM用)が残っていれば消す
		cleanupTempWavDir();

		for (int i = 0; i < sounds.length; i++) {
			sounds[i] = new SoundInstance();
		}

		if(soundthread) {
			mixer = new SoundMixer();
			mixer.start();
		}
	}

	@Override
	protected Sound getKeySound(File path) {
		for(FileHandle handle : AudioDriver.getPaths(path.getPath())) {
			final String filename = handle.path();
			final int dotIndex = filename.lastIndexOf('.');
			if (dotIndex < 0) continue;

			final String ext = filename.substring(dotIndex).toLowerCase(Locale.ROOT);
			final Sound sound = getKeySound(handle, ext);
			if(sound != null) {
				return sound;
			}
		}
		return null;
	}

	private Sound getKeySound(FileHandle handle, String ext) {
		switch (ext) {
			case ".wav":
				// Android 优化：WAV 文件直接使用 libGDX 原生加载
				return getKeySound(handle);
			case ".ogg":
			case ".mp3":
			case ".flac":
				return getKeySound(handle);
		}
		return null;

	}

	private Sound getKeySound(FileHandle handle) {
		try {
			return Gdx.audio.newSound(handle);
		} catch (GdxRuntimeException e) {
			Logger.getGlobal().warning("音源ファイル読み込み失敗" + e.getMessage());
		}
		return null;
	}

	/**
	 * スライス済みPCM(音切り・音源の途中再生)から Sound を生成する。
	 *
	 * <p><b>Oboe バックエンドでは仮想ファイルが使えない。</b> {@code OboeAudio.newSound(FileHandle)} は
	 * {@code handle.read()} を一切見ず、{@code handle.path()} をそのままネイティブの
	 * {@code createSoundpoolFromPath()} に渡す(OpenAL ならストリームを読むので動いていた)。
	 * 旧実装の {@code FileHandleStream("tempwav.wav")} は相対パスなので、
	 * {@code Could not open file:tempwav.wav: No such file or directory} で必ず失敗していた
	 * → 音切り(BMSONのslice)が全滅し、練習開始位置を跨ぐ長いBGMの復帰再生も無音になっていた。</p>
	 *
	 * <p>ここでは PCM を実ファイル(WAV)に書き出してから渡す。ネイティブ側は
	 * {@code createSoundpoolFromPath} の中でファイル全体をデコードし終えてから
	 * メモリ上の soundpool(「fully loaded 16Bit PCM」)を作るため、戻った時点で実ファイルは不要。
	 * よって成否に関わらずその場で削除する(音切りが多い譜面でディスクを圧迫しない)。</p>
	 */
	@Override
	protected Sound getKeySound(final PCM pcm) {
		final FileHandle wav = writeTempWav(pcm);
		if (wav == null) {
			return null;
		}
		try {
			return Gdx.audio.newSound(Gdx.files.absolute(wav.file().getAbsolutePath()));
		} catch (GdxRuntimeException e) {
			Logger.getGlobal().warning("音源ファイル読み込み失敗" + e.getMessage());
			return null;
		} finally {
			wav.delete();
		}
	}

	/** 一時WAVの連番。並列デコード中に名前が衝突しないよう分ける。 */
	private final java.util.concurrent.atomic.AtomicInteger tempWavSeq = new java.util.concurrent.atomic.AtomicInteger();

	/** 一時WAVを置くディレクトリ({@code Gdx.files.local} 相対)。 */
	private static final String TEMP_WAV_DIR = "tempwav";

	/**
	 * 前回起動時の一時WAVが残っていたら消す(プロセスが落ちると dispose されず残るため)。
	 */
	private void cleanupTempWavDir() {
		try {
			final FileHandle dir = Gdx.files.local(TEMP_WAV_DIR);
			if (dir.exists()) {
				dir.deleteDirectory();
			}
		} catch (Throwable e) {
			Logger.getGlobal().warning("一時WAVディレクトリの削除に失敗: " + e);
		}
	}

	/**
	 * PCM(スライス済み)を 16bit WAV として実ファイルに書き出す。
	 *
	 * @return 書き出したファイル。失敗時は null
	 */
	private FileHandle writeTempWav(PCM pcm) {
		FileHandle handle = null;
		try {
			handle = Gdx.files.local(TEMP_WAV_DIR + "/" + tempWavSeq.incrementAndGet() + ".wav");
			final WavFileInputStream in = new WavFileInputStream(pcm);
			final OutputStream os = handle.write(false);
			try {
				final byte[] buf = new byte[32 * 1024];
				int read;
				while ((read = in.read(buf, 0, buf.length)) > 0) {
					os.write(buf, 0, read);
				}
			} finally {
				os.close();
			}
			return handle;
		} catch (Throwable e) {
			Logger.getGlobal().warning("音源(wav)ファイルスライシング失敗。" + e);
			if (handle != null) {
				handle.delete();
			}
			return null;
		}
	}

	private Object lock = new Object();

	// ===== OnCompletion listener support for OpenAL Sound (no native isPlaying) =====
	private final Map<String, Sound> pathSoundMap = new ConcurrentHashMap<>();
	private final Map<String, Float> pathDurationSec = new ConcurrentHashMap<>();
	private final Map<String, ScheduledFuture<?>> pendingCompletions = new ConcurrentHashMap<>();
	private final ScheduledExecutorService completionExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "GdxSoundDriver-Completion");
		t.setDaemon(true);
		return t;
	});

	@Override
	protected void onSoundLoaded(String path, Sound audio) {
		if (path != null && audio != null) {
			pathSoundMap.put(path, audio);
		}
	}

	@Override
	protected void onCompletionListenerChanged(String path, Runnable listener) {
		ScheduledFuture<?> prev = pendingCompletions.remove(path);
		if (prev != null) {
			prev.cancel(false);
		}
	}

	private float getOrComputeDurationSec(String path) {
		Float cached = pathDurationSec.get(path);
		if (cached != null) return cached;
		try {
			PCM pcm = PCM.load(path, GdxSoundDriver.this);
			if (pcm != null && pcm.sampleRate > 0 && pcm.channels > 0) {
				float sec = (float) pcm.len / pcm.sampleRate / pcm.channels;
				pathDurationSec.put(path, sec);
				return sec;
			}
		} catch (Throwable t) {
			Logger.getGlobal().warning("Failed to compute duration for " + path + ": " + t.getMessage());
		}
		// Fallback: long enough that loop BGM (no listener) never fires spuriously
		pathDurationSec.put(path, 600f);
		return 600f;
	}

	private void scheduleCompletionIfRegistered(Sound sound) {
		String foundPath = null;
		for (Map.Entry<String, Sound> e : pathSoundMap.entrySet()) {
			if (e.getValue() == sound) {
				foundPath = e.getKey();
				break;
			}
		}
		if (foundPath == null || !hasCompletionListener(foundPath)) {
			return;
		}
		final String matchingPath = foundPath;
		float dur = getOrComputeDurationSec(matchingPath);
		ScheduledFuture<?> task = completionExecutor.schedule(() -> {
			if (hasCompletionListener(matchingPath)) {
				fireCompletion(matchingPath);
			}
		}, (long)(dur * 1000), TimeUnit.MILLISECONDS);
		pendingCompletions.put(matchingPath, task);
	}

	@Override
	protected void play(Sound pcm, int channel, float volume, float pitch) {
		if(soundthread) {
			mixer.put(pcm, channel, volume, getGlobalPitch() * pitch);
		} else {
			synchronized (lock) {
				sounds[soundPos].sound = pcm;
				sounds[soundPos].id = pcm.play(volume, getGlobalPitch() * pitch, 0);
				sounds[soundPos].channel = channel;
				soundPos = (soundPos + 1) % sounds.length;
			}
		}
	}

	@Override
	protected void play(AudioElement<Sound> id, float volume, boolean loop) {
		if(soundthread) {
			mixer.put(id.audio, volume, loop);
		} else {
			synchronized (lock) {
				if(loop) {
					id.id = id.audio.loop(volume);
				} else {
					id.id = id.audio.play(volume);
					// Schedule completion callback for non-looping playback if a listener is registered
					scheduleCompletionIfRegistered(id.audio);
				}
			}
		}
	}

	@Override
	protected void setVolume(AudioElement<Sound> id, float volume) {
		id.audio.setVolume(id.id, volume);
	}

	@Override
	protected boolean isPlaying(Sound id) {
		// TODO 未実装'(Soundにはplay中かどうかを判断するメソッドがない)
		return true;
	}

	@Override
	protected void stop(Sound id) {
		if (soundthread) {
			mixer.stop(id, 0);
		} else {
			synchronized (lock) {
				id.stop();
			}
		}
	}

	@Override
	protected void stop(Sound id, int channel) {
		if (soundthread) {
			mixer.stop(id, channel);
		} else {
			for (int i = 0; i < sounds.length; i++) {
				if (sounds[i].sound == id && sounds[i].channel == channel) {
					synchronized (lock) {
						sounds[i].sound.stop(sounds[i].id);
						sounds[i].sound = null;
					}
				}
			}
		}
	}

	@Override
	protected void setVolume(Sound id, int channel, float volume) {
		if (soundthread) {
			mixer.setVolume(id, channel, volume);
		} else {
			for (int i = 0; i < sounds.length; i++) {
				if (sounds[i].sound == id && sounds[i].channel == channel) {
					synchronized (lock) {
						sounds[i].sound.setVolume(sounds[i].id, volume);
					}
				}
			}
		}
	}

	@Override
	protected void disposeKeySound(Sound pcm) {
		pcm.dispose();
	}

	class SoundMixer extends Thread {

		private Sound[] sound = new Sound[256];
		private float[] volume = new float[256];
		private float[] pitch = new float[256];
		private int[] channels = new int[256];
		private long[] ids = new long[256];
		private boolean[] loops = new boolean[256];
		private int cpos;
		private int pos;

		public synchronized void put(Sound sound, int channel, float volume, float pitch) {
			this.sound[cpos] = sound;
			this.volume[cpos] = volume;
			this.pitch[cpos] = pitch;
			this.channels[cpos] = channel;
			this.loops[cpos] = false;
			cpos = (cpos + 1) % this.sound.length;
		}

		public synchronized void put(Sound sound, float volume, boolean loop) {
			this.sound[cpos] = sound;
			this.volume[cpos] = volume;
			this.pitch[cpos] = 0;
			this.channels[cpos] = 0;
			this.loops[cpos] = loop;
			cpos = (cpos + 1) % this.sound.length;
		}

		public synchronized void stop(Sound snd, int channel) {
			for (int i = 0; i < sound.length; i++) {
				if (sound[i] == snd && this.channels[i] == channel) {
					sound[i].stop(ids[i]);
					sound[i] = null;
				}
			}
		}

		public synchronized void setVolume(Sound snd, int channel, float volume) {
			for (int i = 0; i < sound.length; i++) {
				if (sound[i] == snd && this.channels[i] == channel) {
					sound[i].setVolume(ids[i], volume);
				}
			}
		}

		public void run() {
			for(;;) {
				if(pos != cpos) {
					if(loops[pos]) {
						ids[pos] = sound[pos].loop(this.volume[pos], getGlobalPitch() * this.pitch[pos], 0);
					} else {
						ids[pos] = sound[pos].play(this.volume[pos], getGlobalPitch() * this.pitch[pos], 0);
					}
					pos = (pos + 1) % this.sound.length;
				} else {
					try {
						sleep(1);
					} catch (InterruptedException e) {
					}
				}
			}
		}
	}

	private static class SoundInstance {
		public Sound sound;
		public long id = -1;
		public int channel = -1;
	}

	static class WavFileInputStream extends InputStream {

		private int pos = 0;
		private int mark = 0;
		private final byte[] header;
		private final PCM pcm;

		public WavFileInputStream(PCM pcm) {
			header = new byte[44];

			final int sampleRate = pcm.sampleRate;
			final int channels = pcm.channels;
			this.pcm = pcm;
			final long totalDataLen = pcm.len * 2 + 36;
			final long bitrate = sampleRate * channels * 16;

			header[0] = 'R';
			header[1] = 'I';
			header[2] = 'F';
			header[3] = 'F';
			header[4] = (byte) (totalDataLen & 0xff);
			header[5] = (byte) ((totalDataLen >> 8) & 0xff);
			header[6] = (byte) ((totalDataLen >> 16) & 0xff);
			header[7] = (byte) ((totalDataLen >> 24) & 0xff);
			header[8] = 'W';
			header[9] = 'A';
			header[10] = 'V';
			header[11] = 'E';
			header[12] = 'f';
			header[13] = 'm';
			header[14] = 't';
			header[15] = ' ';
			header[16] = 16;
			header[17] = 0;
			header[18] = 0;
			header[19] = 0;
			header[20] = 1;
			header[21] = 0;
			header[22] = (byte) channels;
			header[23] = 0;
			header[24] = (byte) (sampleRate & 0xff);
			header[25] = (byte) ((sampleRate >> 8) & 0xff);
			header[26] = (byte) ((sampleRate >> 16) & 0xff);
			header[27] = (byte) ((sampleRate >> 24) & 0xff);
			header[28] = (byte) ((bitrate / 8) & 0xff);
			header[29] = (byte) (((bitrate / 8) >> 8) & 0xff);
			header[30] = (byte) (((bitrate / 8) >> 16) & 0xff);
			header[31] = (byte) (((bitrate / 8) >> 24) & 0xff);
			header[32] = (byte) ((channels * 16) / 8);
			header[33] = 0;
			header[34] = 16;
			header[35] = 0;
			header[36] = 'd';
			header[37] = 'a';
			header[38] = 't';
			header[39] = 'a';
			header[40] = (byte) ((pcm.len * 2) & 0xff);
			header[41] = (byte) (((pcm.len * 2) >> 8) & 0xff);
			header[42] = (byte) (((pcm.len * 2) >> 16) & 0xff);
			header[43] = (byte) (((pcm.len * 2) >> 24) & 0xff);
		}

		@Override
		public int available() {
			return 44 + pcm.len * 2 - pos;
		}

		@Override
		public synchronized void mark(int readlimit) {
			mark = pos;
		}

		@Override
		public synchronized void reset() {
			pos = mark;
		}

		@Override
		public long skip(long n) {
			if (n < 0) {
				return 0;
			}
			if (44 + pcm.len * 2 - pos < n) {
				pos = 44 + pcm.len * 2;
				return 44 + pcm.len * 2 - pos;
			}
			pos += n;
			return n;
		}

		@Override
		public boolean markSupported() {
			return true;
		}

		@Override
		public int read() {
			final int result = byteAt(pos);
			if (result >= 0) {
				pos++;
			}
			return result;
		}

		/**
		 * 指定バイト位置の値を返す(範囲外は -1)。{@link #read()} と
		 * {@link #read(byte[], int, int)} の共通ロジック。
		 */
		private int byteAt(int p) {
			if (p < 44) {
				return 0x00ff & header[p];
			}
			if (p < 44 + pcm.len * 2) {
				int result = -1;
				if(pcm instanceof ShortPCM) {
					short s = ((short[])pcm.sample)[(p - 44) / 2 + pcm.start];
					if (p % 2 == 0) {
						result = (s & 0x00ff);
					} else {
						result = ((s & 0xff00) >>> 8);
					}
				} else if(pcm instanceof ShortDirectPCM) {
					result = ((ByteBuffer)pcm.sample).get(p - 44 + pcm.start * 2) & 0xff;
				} else if(pcm instanceof FloatPCM) {
					short s = (short) (((float[])pcm.sample)[(p - 44) / 2 + pcm.start] * Short.MAX_VALUE);
					if (p % 2 == 0) {
						result = (s & 0x00ff);
					} else {
						result = ((s & 0xff00) >>> 8);
					}
				} else if(pcm instanceof BytePCM) {
					result = p % 2 != 0 ? (((byte[])pcm.sample)[(p - 44) / 2 + pcm.start]) & 0x000000ff : 0;
				}
				return result;
			}
			return -1;
		}

		/**
		 * まとめ読み。{@link java.io.InputStream} の既定実装は 1 バイトずつ read() を呼ぶため、
		 * 数MBの音源をファイルに書き出す時に極端に遅い。ここでは同じ規則で直接バッファへ書く。
		 */
		@Override
		public synchronized int read(byte[] b, int off, int len) {
			if (b == null) {
				throw new NullPointerException();
			}
			if (off < 0 || len < 0 || len > b.length - off) {
				throw new IndexOutOfBoundsException();
			}
			final int total = 44 + pcm.len * 2;
			if (pos >= total) {
				return -1;
			}
			if (len == 0) {
				return 0;
			}
			final int n = Math.min(len, total - pos);
			final int end = pos + n;
			int i = pos;
			int o = off;
			// WAVヘッダ部
			while (i < end && i < 44) {
				b[o++] = header[i++];
			}
			// サンプルデータ部。ShortPCM が最も一般的なので配列から直接 2 バイトずつ書く。
			int d = i - 44;
			if (i < end && pcm instanceof ShortPCM) {
				final short[] sample = (short[]) pcm.sample;
				while (i < end) {
					final short s = sample[pcm.start + (d >> 1)];
					b[o++] = (d & 1) == 0 ? (byte) s : (byte) (s >> 8);
					d++;
					i++;
				}
			} else {
				while (i < end) {
					b[o++] = (byte) byteAt(i++);
				}
			}
			pos = end;
			return n;
		}
	}
}
