package bms.player.beatoraja;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import bms.model.BMSDecoder;
import bms.model.Mode;
import bms.player.beatoraja.CourseData.TrophyData;
import bms.player.beatoraja.song.SongData;
import bms.table.Course.Trophy;
import bms.table.*;

/**
 * 難易度表データアクセス用クラス
 * 
 * @author exch
 */
public class TableDataAccessor {
	
	private final String tabledir;

	public TableDataAccessor(String tabledir) {
		this.tabledir = tabledir;
	}

	/**
	 * 全ての難易度表を更新する。
	 * <p>
	 * 本 fork：**逐サイト串行**（一つずつ処理する）。上流は {@code Arrays.stream(urls).parallel()}
	 * で全サイトを一括（同時）に取りに行くが、それだと各サイトへリクエストが一斉に飛ぶので、
	 * 一个ずつ順番に回す。交棒の間隔（≒200ms）は呼び出し側（Android 設定画面の「全部更新」）が持つ。
	 */
	public void updateTableData(String[] urls) {
		for (String url : urls) {
			TableAccessor tr = new DifficultyTableAccessor(tabledir, url);
			TableData td = tr.read();
			if(td != null) {
				write(td);
			}
		}
	}

	/**
	 * ローカルにキャッシュが無い難易度表だけを取り込む。処理順・粒度は
	 * {@link #updateTableData(String[])} と同じで、こちらも**逐サイト串行**。
	 */
	public void loadNewTableData(String[] urls) {
		Set<String> localTables = getLocalTableFilenames();
		for (String url : urls) {
			if (localTables != null && localTables.contains(getFileName(url) + ".bmt")) {
				continue;
			}
			TableAccessor tr = new DifficultyTableAccessor(tabledir, url);
			TableData td = tr.read();
			if(td != null) {
				write(td);
			}
		}
	}

	private Set<String> getLocalTableFilenames() {
		File dir = new File(tabledir);
		File[] files = dir.listFiles();
		if (files == null) return null;
		Set<String> result = new HashSet<>();
		for (File f : files) {
			String name = f.getName();
			if (name.toLowerCase().endsWith(".bmt")) {
				result.add(name);
			}
		}
		return result;
	}

	public HashMap<String,String> readLocalTableNames(String[] urls) {
		HashMap<String,String> fileNameToTableNameMap = new HashMap<>();
		File dir = new File(tabledir);
		File[] files = dir.listFiles();
		if (files == null) return null;
		for (File f : files) {
			String fileName = f.getName();
			if (!fileName.endsWith(".bmt")) continue;
			TableData td = TableData.read(f);
			if (td == null) continue;
			fileNameToTableNameMap.put(fileName, td.getName());
		}
		HashMap<String,String> urlToTableNameMap = new HashMap<>();
		for (String url : urls) {
			urlToTableNameMap.put(url, fileNameToTableNameMap.get(getFileName(url) + ".bmt"));
		}
		return urlToTableNameMap;
	}
	
	/**
	 * 難易度表データをキャッシュする
	 * 
	 * @param td 難易度表データ
	 */
	public void write(TableData td) {
		TableData.write(new File(tabledir, getFileName(td.getUrl()) + ".bmt"), td);
	}

	public void write(TableData td, String filename) {
		TableData.write(new File(tabledir, filename), td);
	}

	/**
	 * 全てのキャッシュされた難易度表データを読み込む
	 *
	 * <p><b>同じ表のキャッシュが複数ファイルで存在しうる</b>：ファイル名は URL の
	 * SHA-256 なので、更新時に URL の綴りが 1 文字でも変わると<b>別ファイルが増え、
	 * 古いファイルは残る</b>（実際 {@code http://} → {@code http:/} の差で発生し、
	 * 選曲画面に同じ表が 2 つ並んだ）。{@link #getCacheFileName(String)} で正規化して
	 * 再発は防いでいるが、既に増えてしまった分はここで落とす。</p>
	 *
	 * <p>重複の判定は<b>表の名前</b>（ユーザーが見て「同じ表」と分かる単位）で行い、
	 * {@code lastModified} が新しい方を残す。</p>
	 *
	 * @return 全てのキャッシュされた難易度表データ（表名で重複を除去したもの）
	 */
	public TableData[] readAll() {
		File dir = new File(tabledir);
		File[] files = dir.listFiles();
		if (files == null) return new TableData[0];
		Map<String, TableData> byName = new LinkedHashMap<>();	// 表名 -> 採用したキャッシュ
		Map<String, File> byNameFile = new HashMap<>();		// 表名 -> 採用したファイル
		for (File f : files) {
			TableData td = TableData.read(f);
			if (td == null) continue;
			File prev = byNameFile.get(td.getName());
			if (prev == null || f.lastModified() > prev.lastModified()) {
				if (prev != null) {
					Logger.getGlobal().warning("難易度表キャッシュの重複を無視 : " + prev.getName()
							+ " (新しい " + f.getName() + " を採用 / " + td.getName() + ")");
				}
				byName.put(td.getName(), td);
				byNameFile.put(td.getName(), f);
			} else {
				Logger.getGlobal().warning("難易度表キャッシュの重複を無視 : " + f.getName()
						+ " (採用済み " + prev.getName() + " / " + td.getName() + ")");
			}
		}
		return byName.values().toArray(new TableData[0]);
	}

	/**
	 * 指定のキャッシュされた難易度表データを読み込む
	 * 
	 * @param url 難易度表URL
	 * @return キャッシュされた難易度表データ。存在しない場合はnull
	 */
	public TableData readCache(String url) {
		String targetName = getFileName(url) + ".bmt";
		File dir = new File(tabledir);
		File[] files = dir.listFiles();
		if (files == null) return null;
		for (File f : files) {
			if (f.getName().equals(targetName)) {
				return TableData.read(f);
			}
		}
		return null;
	}
	
	public TableData read(String filename) {
		return TableData.read(new File(tabledir, filename));
	}

	/**
	 * 難易度表キャッシュのファイル名（{@code sha256(URL) + ".bmt"}）。
	 *
	 * <p>🔴 キャッシュのファイル名は<b>この 1 箇所だけで決める</b>（Android 設定画面も
	 * これを使う）。別々に計算すると、URL の綴りが 1 文字違うだけで別ファイルが増え、
	 * 同じ表が選曲画面に 2 つ並ぶ（{@code http://} と {@code http:/} の差で実際に起きた）。</p>
	 *
	 * @param url 難易度表 URL
	 * @return キャッシュのファイル名
	 */
	public static String getCacheFileName(String url) {
		return getFileName(url) + ".bmt";
	}

	private static String getFileName(String name) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			// UTF-8 を明示（プラットフォーム既定の文字コードに依存させない）
			digest.update(canonicalizeUrl(name).getBytes(StandardCharsets.UTF_8));
			return BMSDecoder.convertHexString(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * URL の綴りの揺れを吸収する（同じ表が別ファイルに分裂するのを防ぐ）。
	 *
	 * <p>手入力・貼り付けで {@code http://host/path} の {@code //} が 1 本に潰れることが
	 * あり（{@code http:/host/path}）、この 2 つは別の文字列だが<b>同じ表</b>を指す。
	 * Java の {@link java.net.URL} は後者を host なしとして解釈するのに、本 fork の
	 * Android 実機ではどちらの綴りでも取得できてしまい、キャッシュが 2 つに分かれていた。</p>
	 *
	 * <p>揺れの吸収は<b>ここだけ</b>で行う。{@code TableData.getUrl()}（＝設定・スキンと
	 * 突き合わせる値）は生の文字列のまま保持すること。</p>
	 */
	private static String canonicalizeUrl(String url) {
		if (url == null) return "";
		return SCHEME_WITHOUT_SLASHES.matcher(url.trim()).replaceFirst("$1://");
	}

	/** スキーム直後の {@code //} が 1 本に潰れている URL（{@code http:/x} / {@code https:/x}）。 */
	private static final Pattern SCHEME_WITHOUT_SLASHES = Pattern.compile("(?i)^(https?):/(?!/)");

	public static abstract class TableAccessor {

		public final String name;

		public TableAccessor(String name) {
			this.name = name;
		}

		public abstract TableData read();
		public abstract void write(TableData td);
	}

	public static class DifficultyTableAccessor extends TableAccessor {

		private String tabledir;
		private String url;

		public DifficultyTableAccessor(String tabledir, String url) {
			super(url);
			this.tabledir = tabledir;
			this.url = url;
		}

		@Override
		public TableData read() {
			DifficultyTableParser dtp = new DifficultyTableParser();
			DifficultyTable dt = new DifficultyTable();
			if (url.endsWith(".json")) {
				dt.setHeadURL(url);
			} else {
				dt.setSourceURL(url);
			}
			try {
				dtp.decode(true, dt);
				TableData td = new TableData();
				td.setUrl(url);
				td.setName(dt.getName());
				td.setTag(dt.getTag());
				Mode defaultMode = dt.getMode() != null ? Mode.getMode(dt.getMode()) : null;
				td.setFolder(Stream.of(dt.getLevelDescription()).map(lv -> {
					TableData.TableFolder tde = new TableData.TableFolder();
					tde.setName(td.getTag() + lv);
					tde.setSong(Stream.of(dt.getElements()).filter(dte -> lv.equals(dte.getLevel()))
							.map(dte -> toSongData(dte, defaultMode)).toArray(SongData[]::new));
					return tde;
				}).toArray(TableData.TableFolder[]::new));

				if (dt.getCourse() != null && dt.getCourse().length > 0) {
					td.setCourse(Stream.of(dt.getCourse()).flatMap(courses -> Stream.of(courses)).map(g -> {
						CourseData cd = new CourseData();
						cd.setName(g.getName());
						cd.setSong(Stream.of(g.getCharts()).map(chart -> toSongData(chart, defaultMode))
								.toArray(SongData[]::new));

						cd.setConstraint(Stream.of(g.getConstraint()).map(c -> CourseData.CourseDataConstraint.getValue(c))
								.filter(Objects::nonNull).toArray(CourseData.CourseDataConstraint[]::new));

						if (g.getTrophy() != null) {
							cd.setTrophy(Stream.of(g.getTrophy()).map(trophy -> {
								TrophyData t = new TrophyData();
								t.setName(trophy.getName());
								t.setMissrate((float) trophy.getMissrate());
								t.setScorerate((float) trophy.getScorerate());
								return t;
							}).toArray(TrophyData[]::new));
						}
						return cd;
					}).toArray(CourseData[]::new));
				}
				if(td == null || !td.validate()) {
					throw new RuntimeException("難易度表の値が不正です");
				}
				return td;
			} catch (Throwable e) {
				e.printStackTrace();
				Logger.getGlobal().warning("難易度表 - "+url+" の読み込み失敗。");
			}
			return null;
		}

		@Override
		public void write(TableData td) {
			new TableDataAccessor(tabledir).write(td);
		}
	}
	
	private static SongData toSongData(BMSTableElement te, Mode defaultMode) {
		SongData song = new SongData();
		if(te.getMD5() != null) {
			song.setMd5(te.getMD5().toLowerCase());
		}
		if(te.getSHA256() != null) {
			song.setSha256(te.getSHA256().toLowerCase());
		}
		song.setTitle(te.getTitle());
		song.setArtist(te.getArtist());
		Mode mode = te.getMode() != null ? Mode.getMode(te.getMode()) : null;
		song.setMode(mode != null ? mode.id : (defaultMode != null ? defaultMode.id : 0));
		song.setUrl(te.getURL());
		song.setIpfs(te.getIPFS());
		song.setOrg_md5(te.getParentHash());
		if(te instanceof DifficultyTableElement) {
			DifficultyTableElement dte = (DifficultyTableElement) te;
			song.setAppendurl(dte.getAppendURL());
			song.setAppendIpfs(dte.getAppendIPFS());
		}
		
		return song;
	}
}
