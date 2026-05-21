package bms.player.beatoraja;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Logger;
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

	public void updateTableData(String[] urls) {
		Arrays.stream(urls).parallel().forEach(url -> {
            TableAccessor tr = new DifficultyTableAccessor(tabledir, url);
            TableData td = tr.read();
            if(td != null) {
                write(td);
            }
		});		
	}

	public void loadNewTableData(String[] urls) {
		Set<String> localTables = getLocalTableFilenames();
		Arrays.stream(urls).parallel().forEach(url -> {
			if (localTables.contains(getFileName(url) + ".bmt")) {
				return;
			}
            TableAccessor tr = new DifficultyTableAccessor(tabledir, url);
            TableData td = tr.read();
            if(td != null) {
                write(td);
            }
		});		
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
	 * @return 全てのキャッシュされた難易度表データ
	 */
	public TableData[] readAll() {
		File dir = new File(tabledir);
		File[] files = dir.listFiles();
		if (files == null) return new TableData[0];
		List<TableData> result = new ArrayList<>();
		for (File f : files) {
			TableData td = TableData.read(f);
			if (td != null) result.add(td);
		}
		return result.toArray(new TableData[0]);
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

	private String getFileName(String name) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(name.getBytes());
			return BMSDecoder.convertHexString(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
			return null;
		}

	}

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
