package bms.player.beatoraja.song;

/**
 * 楽曲データベースへのアクセスインターフェイス
 * 
 * @author exch
 */
public interface SongDatabaseAccessor {

	/**
	 * 楽曲を取得する
	 * 
	 * @param key
	 *            属性
	 * @param value
	 *            属性値
	 * @return 検索結果
	 */
	public SongData[] getSongDatas(String key, String value);

	/**
	 * MD5/SHA256で指定した楽曲をまとめて取得する
	 * 
	 * @param hashes
	 *            楽曲のハッシュ
	 * @return
	 */
	public SongData[] getSongDatas(String[] hashes);

	/**
	 * スコアデータベース、スコアログデータベース、譜面情報データベースを跨いでSQLで問い合わせを行う
	 * 
	 * @param sql
	 *            SQL
	 * @param score
	 *            スコアデータベースのパス
	 * @param scorelog
	 *            スコアログデータベースのパス
	 * @return
	 */
	public SongData[] getSongDatas(String sql, String score, String scorelog);

	public void setSongDatas(SongData[] songs);

	public SongData[] getSongDatasByText(String text);

	/**
	 * 楽曲を取得する
	 * 
	 * @param key
	 *            属性
	 * @param value
	 *            属性値
	 * @return 検索結果
	 */
	public FolderData[] getFolderDatas(String key, String value);

	/**
	 * データベースを更新する
	 *
	 * @param updatepath
	 *            更新するフォルダのパス。全更新する場合はnull
	 * @param updateAll
	 *            更新の必要がないものも更新するかどうか
	 */
	public void updateSongDatas(String updatepath, String[] bmsroot, boolean updateAll);

	/**
	 * データベースを更新する（進捗報告付き）
	 *
	 * @param updatepath
	 *            更新するフォルダのパス。全更新する場合はnull
	 * @param updateAll
	 *            更新の必要がないものも更新するかどうか
	 * @param progress
	 *            進捗コールバック。スキャン開始時と各ファイル完了時に呼び出される
	 */
	public void updateSongDatas(String updatepath, String[] bmsroot, boolean updateAll, SongScanProgress progress);

	/**
	 * Get the BMS root directories used by this accessor.
	 * @return Array of BMS root paths
	 */
	public String[] getBmsRoot();

	/**
	 * 歌曲扫描进度回调接口
	 */
	public interface SongScanProgress {
		/**
		 * 每完成一个文件扫描时调用
		 * @param scanned 已扫描的文件数
		 * @param total 预估总文件数（-1表示未知）
		 */
		void onFileScanned(int scanned, int total);
	}
}
