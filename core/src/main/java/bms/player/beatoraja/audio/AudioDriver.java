package bms.player.beatoraja.audio;

import bms.model.BMSModel;
import bms.model.Note;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import java.util.ArrayList;
import java.util.List;

import bms.player.beatoraja.FileCache;
import com.badlogic.gdx.utils.Disposable;

/**
 * 各種音源再生インターフェイス
 *
 * @author exch
 */
public interface AudioDriver extends Disposable {

	/**
	 * 指定したパスの音源を鳴らす
	 *
	 * @param path
	 *            音源のファイルパス
	 * @param loop
	 *            ループ再生するかどうか
	 */
	public void play(String path, float volume, boolean loop);

	/**
	 * 指定したパスの音源のボリュームを設定する
	 * @param path
	 *            音源のファイルパス
	 * @param volume
	 *            ボリューム
	 */
	public void setVolume(String path, float volume);

	/**
	 * 指定したパスの音源の非ループ再生完了時に呼ばれるリスナーを登録する。
	 * listener が非 null なら登録、null なら解除。実装側で対応していない場合は何もしない。
	 * リスナーは audio コールバックスレッドや executor スレッドから呼ばれる可能性があるため、
	 * 呼び出し側はスレッドセーフに処理する必要がある。
	 */
	default void setOnCompletionListener(String path, Runnable listener) {
		// デフォルトは未実装
	}

	/**
	 * 指定したパスの音源がなっている場合はtrueを返す
	 *
	 * @param path
	 *            音源のファイルパス
	 */
	public boolean isPlaying(String path);

	/**
	 * 指定したパスの音源がなっている場合は止める
	 *
	 * @param path
	 *            音源のファイルパス
	 */
	public void stop(String path);

	/**
	 * 指定したパスの音源を開放する
	 *
	 * @param path
	 *            音源のファイルパス
	 */
	public void dispose(String path);

	/**
	 * BMSの音源データを読み込む
	 *
	 * @param model
	 *            BMSモデル
	 */
	public void setModel(BMSModel model);

	/**
	 * 判定に対応した追加キー音を定義する
	 * @param judge 判定
	 * @param fast EARLYの場合はtrue
	 * @param path 音源パス。nullの場合は定義しない
	 */
	public void setAdditionalKeySound(int judge, boolean fast, String path);

	/**
	 * BMSの音源データ読み込みを中止する
	 */
	public void abort();

	/**
	 * 音源の読み込み状況を返す
	 *
	 * @return 音源の読み込み状況(0.0 - 1.0)
	 */
	public float getProgress();

	/**
	 * 指定したNoteの音を鳴らす
	 *
	 * @param n
	 *            Note
	 * @param volume
	 *            ボリューム(0.0 - 1.0)
	 * @param pitch
	 *            ピッチ変化(-12 - 12)
	 */
	public void play(Note n, float volume, int pitch);

	/**
	 * 指定したNoteの音を、音源の途中から鳴らす。
	 *
	 * <p>練習モードで開始位置を跨ぐ長いBGMを復帰させる用途を想定している。
	 * {@code offsetMicros} は「そのNoteが本来鳴り始めた位置」からの経過時間(us)。
	 * {@code offsetMicros <= 0} の場合は {@link #play(Note, float, int)} と同じ。
	 *
	 * @param n
	 *            Note
	 * @param volume
	 *            ボリューム(0.0 - 1.0)
	 * @param pitch
	 *            ピッチ変化(-12 - 12)
	 * @param offsetMicros
	 *            音源先頭からのオフセット(us)
	 * @return 途中からの再生を実際に開始できた場合はtrue。false の場合は無音になる
	 *         (音源の生成に失敗した、オフセット位置が音源の長さを超えている等)
	 */
	public boolean play(Note n, float volume, int pitch, long offsetMicros);

	/**
	 * 指定したNoteの音源の長さ(us)を返す。判定できない場合は0以下を返す。
	 *
	 * @param n
	 *            Note
	 * @return 音源の長さ(us)。不明な場合は0以下
	 */
	public long getSoundLengthMicros(Note n);

	/**
	 * 指定したNoteの「offsetMicros 以降」の音源を事前に生成する。再生はしない。
	 *
	 * <p>{@link #play(Note, float, int, long)} は音源の生成が終わってから鳴り始めるため、
	 * 長いBGMでは生成(PCMデコード + スライス音源の作成)に1秒以上かかることがあり、
	 * その分だけ鳴り始めが遅れる。プレイ開始前にこれを呼んで生成だけ済ませておけば、
	 * {@link #play(Note, float, int, long)} は即座に鳴り始める。
	 *
	 * @param n
	 *            Note
	 * @param offsetMicros
	 *            音源先頭からのオフセット(us)
	 * @return 音源が生成できた(または既に生成済みだった)場合はtrue
	 */
	public boolean prepareOffsetSound(Note n, long offsetMicros);

	public void play(int judge, boolean fast);
	/**
	 * 指定したNoteの音を止める。nullの場合は再生されている音を全て止める
	 *
	 * @param n
	 *            Note
	 */
	public void stop(Note n);

	/**
	 * 指定したパスの音源のボリュームを設定する
	 * @param n
	 *            Note
	 * @param volume
	 *            ボリューム
	 */
	public void setVolume(Note n, float volume);

	/**
	 * 全体のピッチを変更する。可能な場合は再生中の音のピッチも変更する
	 *
	 * @param pitch ピッチ(0.5 - 2.0)
	 */
	public void setGlobalPitch(float pitch);

	/**
	 * 全体のピッチを取得する
	 * @return ピッチ(0.5 - 2.0)
	 */
	public float getGlobalPitch();

	/**
	 * 古い音源リソースを開放する
	 */
	public void disposeOld();

	/**
	 * 指定されたパスから対応している音源ファイルのパスを全て取得する
	 * @param path 指定されたパス
	 * @return 音源ファイルのパス (FileHandle 形式)
	 */
	public static FileHandle[] getPaths(String path) {
		final String[] exts = { ".wav", ".flac", ".ogg", ".mp3"};

		List<FileHandle> result = new ArrayList<FileHandle>();
		final int index = path.lastIndexOf('.');
		final String name = (index < 0) ? path : path.substring(0, index);
		final String ext = (index < 0) ? "" : path.substring(index);

		// 在 Android 上，如果路径是相对路径，优先尝试 internal
		boolean isRelative = !path.startsWith("/") && !path.contains(":");

		if (com.badlogic.gdx.Gdx.app.getType() == com.badlogic.gdx.Application.ApplicationType.Android && isRelative) {
			tryGetPath(path, true, result);
			for (String _ext : exts) {
				if (!_ext.equalsIgnoreCase(ext)) {
					tryGetPath(name + _ext, true, result);
				}
			}
		}

		// 尝试绝对路径
		tryGetPath(path, false, result);
		for (String _ext : exts) {
			if (!_ext.equalsIgnoreCase(ext)) {
				tryGetPath(name + _ext, false, result);
			}
		}

		return result.toArray(new FileHandle[result.size()]);
	}

	private static void tryGetPath(String path, boolean internal, List<FileHandle> result) {
		FileHandle fh = internal ? Gdx.files.internal(path) : Gdx.files.absolute(path);
		if (FileCache.exists(fh)) {
			// 避免重复添加
			for (FileHandle existing : result) {
				if (existing.path().equals(fh.path()) && existing.type() == fh.type()) return;
			}
			result.add(fh);
		}
	}
}
