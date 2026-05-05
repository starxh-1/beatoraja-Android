package bms.player.beatoraja.song;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import bms.player.beatoraja.DatabaseUtils;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;
import java.util.stream.Stream;

import bms.player.beatoraja.SQLiteDatabaseAccessor.AndroidBeanListHandler;
import bms.player.beatoraja.SQLiteDatabaseAccessor;
import bms.player.beatoraja.Validatable;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;


import bms.model.*;

/**
 * 楽曲データベースへのアクセスクラス
 *
 * @author exch
 */
public class SQLiteSongDatabaseAccessor extends SQLiteDatabaseAccessor implements SongDatabaseAccessor {

	private final Path root;
	private final String[] bmsroot;

	private final ResultSetHandler<List<SongData>> songhandler = new AndroidBeanListHandler<>(SongData.class);
	private final ResultSetHandler<List<FolderData>> folderhandler = new AndroidBeanListHandler<>(FolderData.class);

	private final QueryRunner qr;

	private List<SongDatabaseAccessorPlugin> plugins = new ArrayList();

	public SQLiteSongDatabaseAccessor(String filepath, String[] bmsroot) throws ClassNotFoundException {
		super(new Table("folder",
				new Column("title", "TEXT"),
				new Column("subtitle", "TEXT"),
				new Column("command", "TEXT"),
				new Column("path", "TEXT", 0, 1),
				new Column("banner", "TEXT"),
				new Column("parent", "TEXT"),
				new Column("type", "INTEGER"),
				new Column("date", "INTEGER"),
				new Column("adddate", "INTEGER"),
				new Column("max", "INTEGER")
				),
				new Table("song",
						new Column("md5", "TEXT", 1, 0),
						new Column("sha256", "TEXT", 1, 0),
						new Column("title", "TEXT"),
						new Column("subtitle", "TEXT"),
						new Column("genre", "TEXT"),
						new Column("artist", "TEXT"),
						new Column("subartist", "TEXT"),
						new Column("tag", "TEXT"),
						new Column("path", "TEXT", 0, 1),
						new Column("folder", "TEXT"),
						new Column("stagefile", "TEXT"),
						new Column("banner", "TEXT"),
						new Column("backbmp", "TEXT"),
						new Column("preview", "TEXT"),
						new Column("parent", "TEXT"),
						new Column("level", "INTEGER"),
						new Column("difficulty", "INTEGER"),
						new Column("maxbpm", "INTEGER"),
						new Column("minbpm", "INTEGER"),
						new Column("length", "INTEGER"),
						new Column("mode", "INTEGER"),
						new Column("judge", "INTEGER"),
						new Column("feature", "INTEGER"),
						new Column("content", "INTEGER"),
						new Column("date", "INTEGER"),
						new Column("favorite", "INTEGER"),
						new Column("adddate", "INTEGER"),
						new Column("notes", "INTEGER"),
						new Column("charthash", "TEXT")
						));

		qr = new QueryRunner(DatabaseUtils.getDataSource(filepath));
		root = Paths.get(".");
		this.bmsroot = bmsroot;
		createTable();
	}

	@Override
	public String[] getBmsRoot() {
		return bmsroot;
	}

	public void addPlugin(SongDatabaseAccessorPlugin plugin) {
		plugins.add(plugin);
	}

	/**
	 * 楽曲データベースを初期テーブルを作成する。 すでに初期テーブルを作成している場合は何もしない。
	 */
	private void createTable() {
		try {
			Logger.getGlobal().info("createTable: Starting database initialization/validation");
			// songテーブル作成(存在しない場合)
			validate(qr);
			Logger.getGlobal().info("createTable: validate completed");

			if(qr.query("PRAGMA TABLE_INFO(song)", new AndroidBeanListHandler<>(Map.class)).stream().anyMatch(m -> ((Map<String, Object>)m).get("name").equals("sha256") && (int)(((Map<String, Object>)m).get("pk")) == 1)) {
				Logger.getGlobal().info("createTable: Migrating old schema to new primary key (sha256)");
				qr.update("ALTER TABLE [song] RENAME TO [old_song]");
				validate(qr);
				qr.update("INSERT INTO song SELECT "
						+ "md5, sha256, title, subtitle, genre, artist, subartist, tag, path,"
						+ "folder, stagefile, banner, backbmp, preview, parent, level, difficulty,"
						+ "maxbpm, minbpm, length, mode, judge, feature, content,"
						+ "date, favorite, notes, adddate, charthash "
						+ "FROM old_song GROUP BY path HAVING MAX(adddate)");
				qr.update("DROP TABLE old_song");
				Logger.getGlobal().info("createTable: Schema migration completed");
			} else {
				Logger.getGlobal().info("createTable: Schema already up to date, no migration needed");
			}
			Logger.getGlobal().info("createTable: Database initialization completed successfully");
		} catch (SQLException e) {
			Logger.getGlobal().severe("================================================================================");
			Logger.getGlobal().severe("Exception during song database initialization: " + e.getMessage());
			Logger.getGlobal().severe("Full stack trace:");
			e.printStackTrace();
			Logger.getGlobal().severe("================================================================================");
		}
	}


	/**
	 * 楽曲を取得する
	 *
	 * @param key
	 *            属性
	 * @param value
	 *            属性値
	 * @return 検索結果
	 */
	public synchronized SongData[] getSongDatas(String key, String value) {
		int retries = 5;
		for (int i = 0; i < retries; i++) {
			try {
				String sql = "SELECT * FROM song WHERE " + key + " = ?";
				Gdx.app.log("SQLDebug", "Executing: " + sql + ", params=[" + value + "]");
				final List<SongData> m = qr.query(sql, songhandler, value);
				Gdx.app.log("SQLDebug", "Result: " + m.size() + " song(s) found");
				return Validatable.removeInvalidElements(m).toArray(new SongData[m.size()]);
			} catch (SQLException e) {
				if (e.getMessage() != null && e.getMessage().contains("database is locked") && i < retries - 1) {
					try {
						Thread.sleep(100);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
					}
				} else {
					e.printStackTrace();
					Logger.getGlobal().severe("song.db更新時の例外:" + e.getMessage());
					break;
				}
			} catch (Exception e) {
				e.printStackTrace();
				Logger.getGlobal().severe("song.db更新時の例外:" + e.getMessage());
				break;
			}
		}
		return SongData.EMPTY;
	}

	/**
	 * MD5/SHA256で指定した楽曲をまとめて取得する
	 *
	 * @param hashes
	 *            楽曲のMD5/SHA256
	 * @return 取得した楽曲
	 */
	public synchronized SongData[] getSongDatas(String[] hashes) {
		int retries = 5;
		for (int i = 0; i < retries; i++) {
			try {
				StringBuilder md5str = new StringBuilder();
				StringBuilder sha256str = new StringBuilder();
				for (String hash : hashes) {
					if (hash.length() > 32) {
						if (sha256str.length() > 0) {
							sha256str.append(',');
						}
						sha256str.append('\'').append(hash).append('\'');
					} else {
						if (md5str.length() > 0) {
							md5str.append(',');
						}
						md5str.append('\'').append(hash).append('\'');
					}
				}
				List<SongData> m = qr.query("SELECT * FROM song WHERE md5 IN (" + md5str.toString() + ") OR sha256 IN ("
						+ sha256str.toString() + ")", songhandler);

				// 検索並び順保持
				List<SongData> sorted = m.stream().sorted((a, b) -> {
					int aIndexSha256 = -1,aIndexMd5 = -1,bIndexSha256 = -1,bIndexMd5 = -1;
					for(int j = 0;j < hashes.length;j++) {
						if(hashes[j].equals(a.getSha256())) aIndexSha256 = j;
						if(hashes[j].equals(a.getMd5())) aIndexMd5 = j;
						if(hashes[j].equals(b.getSha256())) bIndexSha256 = j;
						if(hashes[j].equals(b.getMd5())) bIndexMd5 = j;
					}
				    int aIndex = Math.min((aIndexSha256 == -1 ? Integer.MAX_VALUE : aIndexSha256), (aIndexMd5 == -1 ? Integer.MAX_VALUE : aIndexMd5));
				    int bIndex = Math.min((bIndexSha256 == -1 ? Integer.MAX_VALUE : bIndexSha256), (bIndexMd5 == -1 ? Integer.MAX_VALUE : bIndexMd5));
				    return bIndex - aIndex;
	            }).collect(Collectors.toList());

				SongData[] validated = Validatable.removeInvalidElements(sorted).toArray(new SongData[m.size()]);
				return validated;
			} catch (SQLException e) {
				if (e.getMessage() != null && e.getMessage().contains("database is locked") && i < retries - 1) {
					try {
						Thread.sleep(100);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
					}
				} else {
					e.printStackTrace();
					Logger.getGlobal().severe("song.db更新時の例外:" + e.getMessage());
					break;
				}
			} catch (Exception e) {
				e.printStackTrace();
				Logger.getGlobal().severe("song.db更新時の例外:" + e.getMessage());
				break;
			}
		}

		return SongData.EMPTY;
	}

	public synchronized SongData[] getSongDatas(String sql, String score, String scorelog, String info) {
		try (Statement stmt = qr.getDataSource().getConnection().createStatement()) {
			stmt.execute("ATTACH DATABASE '" + score + "' as scoredb");
			stmt.execute("ATTACH DATABASE '" + scorelog + "' as scorelogdb");
			List<SongData> m;

			if(info != null) {
				stmt.execute("ATTACH DATABASE '" + info + "' as infodb");
				String s = "SELECT DISTINCT md5, song.sha256 AS sha256, title, subtitle, genre, artist, subartist,path,folder,stagefile,banner,backbmp,parent,song.level AS level,song.difficulty AS difficulty,"
						+ "maxbpm,minbpm,song.mode AS mode, song.judge AS judge, song.feature AS feature, song.content AS content, song.date AS date, favorite, song.notes AS notes, adddate, preview, length, charthash"
						+ " FROM song INNER JOIN (information LEFT OUTER JOIN (score LEFT OUTER JOIN scorelog ON score.sha256 = scorelog.sha256) ON information.sha256 = score.sha256) "
						+ "ON song.sha256 = information.sha256 WHERE " + sql;
				ResultSet rs = stmt.executeQuery(s);
				m = songhandler.handle(rs);
//				System.out.println(s + " -> result : " + m.size());
				stmt.execute("DETACH DATABASE infodb");
			} else {
				String s = "SELECT DISTINCT md5, song.sha256 AS sha256, title, subtitle, genre, artist, subartist,path,folder,stagefile,banner,backbmp,parent,song.level AS level,song.difficulty AS difficulty,"
						+ "maxbpm,minbpm,song.mode AS mode, song.judge AS judge, song.feature AS feature, song.content AS content, song.date AS date, favorite, song.notes AS notes, adddate, preview, length, charthash"
						+ " FROM song LEFT OUTER JOIN (score LEFT OUTER JOIN scorelog ON score.sha256 = scorelog.sha256) ON song.sha256 = score.sha256 WHERE " + sql;
				ResultSet rs = stmt.executeQuery(s);
				m = songhandler.handle(rs);
			}
			stmt.execute("DETACH DATABASE scorelogdb");
			stmt.execute("DETACH DATABASE scoredb");
			return Validatable.removeInvalidElements(m).toArray(new SongData[m.size()]);
		} catch(Throwable e) {
			e.printStackTrace();
		}

		return SongData.EMPTY;

	}

	public synchronized SongData[] getSongDatasByText(String text) {
		int retries = 5;
		for (int i = 0; i < retries; i++) {
			try {
				List<SongData> m = qr.query(
						"SELECT * FROM song WHERE rtrim(title||' '||subtitle||' '||artist||' '||subartist||' '||genre) LIKE ?"
								+ " GROUP BY sha256",songhandler, "%" + text + "%");
				return Validatable.removeInvalidElements(m).toArray(new SongData[m.size()]);
			} catch (SQLException e) {
				if (e.getMessage() != null && e.getMessage().contains("database is locked") && i < retries - 1) {
					try {
						Thread.sleep(100);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
					}
				} else {
					e.printStackTrace();
					Logger.getGlobal().severe("song.db更新時の例外:" + e.getMessage());
					break;
				}
			} catch (Exception e) {
				e.printStackTrace();
				Logger.getGlobal().severe("song.db更新時の例外:" + e.getMessage());
				break;
			}
		}

		return SongData.EMPTY;
	}

	/**
	 * 楽曲を取得する
	 *
	 * @param key
	 *            属性
	 * @param value
	 *            属性値
	 * @return 検索結果
	 */
	public synchronized FolderData[] getFolderDatas(String key, String value) {
		int retries = 5;
		for (int i = 0; i < retries; i++) {
			try {
				String sql = "SELECT * FROM folder WHERE " + key + " = ?";
				Gdx.app.log("SQLDebug", "Executing: " + sql + ", params=[" + value + "]");
				final List<FolderData> m = qr.query(sql, folderhandler, value);
				Gdx.app.log("SQLDebug", "Result: " + m.size() + " folder(s) found");
				return m.toArray(new FolderData[m.size()]);
			} catch (SQLException e) {
				if (e.getMessage() != null && e.getMessage().contains("database is locked") && i < retries - 1) {
					try {
						Thread.sleep(100);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
					}
				} else {
					e.printStackTrace();
					Logger.getGlobal().severe("song.db更新時の例外:" + e.getMessage());
					break;
				}
			} catch (Exception e) {
				e.printStackTrace();
				Logger.getGlobal().severe("song.db更新時の例外:" + e.getMessage());
				break;
			}
		}

		return FolderData.EMPTY;
	}

	/**
	 * 楽曲を更新する
	 *
	 * @param songs 更新する楽曲
	 */
	public void setSongDatas(SongData[] songs) {
		try (Connection conn = qr.getDataSource().getConnection()){
			conn.setAutoCommit(false);

			for (SongData sd : songs) {
				this.insert(qr, conn, "song", sd);
			}
			conn.commit();
			conn.close();
		} catch (Exception e) {
			Logger.getGlobal().severe("song.db更新時の例外:" + e.getMessage());
		}
	}

	/**
	 * データベースを更新する
	 *
	 * @param path
	 *            LR2のルートパス
	 */
	public void updateSongDatas(String path, String[] bmsroot, boolean updateAll, SongInformationAccessor info) {
		if(bmsroot == null || bmsroot.length == 0) {
			Logger.getGlobal().warning("楽曲ルートフォルダが登録されていません");
			return;
		}
		Logger.getGlobal().info("開始扫描BMS文件夹: " + Arrays.toString(bmsroot));
		SongDatabaseUpdater updater = new SongDatabaseUpdater(updateAll, bmsroot, info);

		List<FileHandle> handles = new ArrayList<>();
		if (path != null) {
			handles.add(getFileHandle(path));
		} else {
			for (String root : bmsroot) {
				handles.add(getFileHandle(root));
			}
		}
		updater.updateSongDatas(handles);
	}

	private FileHandle getFileHandle(String pathStr) {
		pathStr = pathStr.replace('\\', '/');
		FileHandle fh = Gdx.files.absolute(pathStr);
		if (!fh.exists()) {
			fh = Gdx.files.internal(pathStr);
		}
		return fh;
	}

	/**
	 * song database更新用クラス
	 *
	 * @author exch
	 */
	class SongDatabaseUpdater {

		private final boolean updateAll;
		private final String[] bmsroot;

		private SongInformationAccessor info;

		public SongDatabaseUpdater(boolean updateAll, String[] bmsroot, SongInformationAccessor info) {
			this.updateAll = updateAll;
			this.bmsroot = bmsroot;
			this.info = info;
		}

		/**
		 * データベースを更新する
		 *
		 * @param handles
		 *            更新するディレクトリ(ルートディレクトリでなくても可)
		 */
		public void updateSongDatas(List<FileHandle> handles) {
			long time = System.currentTimeMillis();
			SongDatabaseUpdaterProperty property = new SongDatabaseUpdaterProperty(Calendar.getInstance().getTimeInMillis() / 1000, info);
			property.count.set(0);
			if(info != null) {
				info.startUpdate();
			}
			try (Connection conn = qr.getDataSource().getConnection()) {
				property.conn = conn;
				conn.setAutoCommit(false);
				// 楽曲のタグ,FAVORITEの保持
				for (SongData record : qr.query(conn, "SELECT sha256, tag, favorite FROM song", songhandler)) {
					if (record.getTag().length() > 0) {
						property.tags.put(record.getSha256(), record.getTag());
					}
					if (record.getFavorite() > 0) {
						property.favorites.put(record.getSha256(), record.getFavorite());
					}
				}
				if(updateAll) {
					qr.update(conn, "DELETE FROM folder");
					qr.update(conn, "DELETE FROM song");
				} else {
					// ルートディレクトリに含まれないフォルダの削除
					StringBuilder dsql = new StringBuilder();
					Object[] param = new String[bmsroot.length];
					for (int i = 0; i < bmsroot.length; i++) {
						dsql.append("path NOT LIKE ?");
						param[i] = bmsroot[i].replace('\\', '/') + "%";
						if (i < bmsroot.length - 1) {
							dsql.append(" AND ");
						}
					}

					qr.update(conn,
							"DELETE FROM folder WHERE path NOT LIKE 'LR2files%' AND path NOT LIKE '%.lr2folder' AND ("
									+ dsql.toString() + ")", param);
					qr.update(conn, "DELETE FROM song WHERE (" + dsql.toString() + ")", param);
				}

				for (FileHandle fh : handles) {
					try {
						BMSFolder folder = new BMSFolder(fh, bmsroot);
						folder.processDirectory(property);
					} catch (IOException | SQLException | IllegalArgumentException | ReflectiveOperationException e) {
						Logger.getGlobal().severe("楽曲データベース更新時の例外:" + e.getMessage());
					}
				}
				conn.commit();
			} catch (Exception e) {
				Logger.getGlobal().severe("楽曲データベース更新時の例外:" + e.getMessage());
				e.printStackTrace();
			}

			if(info != null) {
				info.endUpdate();
			}
			long nowtime = System.currentTimeMillis();
			Logger.getGlobal().info("楽曲更新完了 : Time - " + (nowtime - time) + " 1曲あたりの時間 - "
					+ (property.count.get() > 0 ? (nowtime - time) / property.count.get() : "不明"));
		}

	}

	private 	class BMSFolder {

		public final FileHandle path;
		public boolean updateFolder = true;
		private boolean txt = false;
		private final List<FileHandle> bmsfiles = new ArrayList<FileHandle>();
		private final List<BMSFolder> dirs = new ArrayList<BMSFolder>();
		private String previewpath = null;
		private final String[] bmsroot;

		public BMSFolder(FileHandle path, String[] bmsroot) {
			this.path = path;
			this.bmsroot = bmsroot;
		}

		private void processDirectory(SongDatabaseUpdaterProperty property)
				throws IOException, SQLException, ReflectiveOperationException, IllegalArgumentException, InvocationTargetException {
			Logger.getGlobal().info("处理文件夹: " + path.path() + ", 存在: " + path.exists());
			final List<SongData> records = qr.query(property.conn, "SELECT path, date, preview FROM song WHERE folder = ?", songhandler,
					SongUtils.crc32(path.path(), bmsroot, root.toString()));
			final List<FolderData> folders = qr.query(property.conn, "SELECT path,date FROM folder WHERE parent = ?",
					folderhandler, SongUtils.crc32(path.path(), bmsroot, root.toString()));

			for (FileHandle p : path.list()) {
				if(p.isDirectory()) {
					dirs.add(new BMSFolder(p, bmsroot));
				} else {
					final String s = p.name().toLowerCase();
					if (!txt && s.endsWith(".txt")) {
						txt = true;
					}
					if (previewpath == null) {
						if(s.startsWith("preview") && (s.endsWith(".wav") ||
														s.endsWith(".ogg") ||
														s.endsWith(".mp3") ||
														s.endsWith(".flac"))) {
							previewpath = p.name();
						}
					}
					if (s.endsWith(".bms") || s.endsWith(".bme") || s.endsWith(".bml") || s.endsWith(".pms")
							|| s.endsWith(".bmson")) {
						bmsfiles.add(p);
					}
				}
			}

			final boolean containsBMS = bmsfiles.size() > 0;
			property.count.addAndGet(this.processBMSFolder(records, property));

			final int len = folders.size();
			dirs.forEach(bf -> {
				final String s = bf.path.path().replace('\\', '/') + "/";
				for (int i = 0; i < len;i++) {
					final FolderData record = folders.get(i);
					if (record != null && record.getPath().equals(s)) {
						folders.set(i, null);
						if (record.getDate() == bf.path.lastModified() / 1000) {
							bf.updateFolder = false;
						}
						break;
					}
				}
			});

			if(!containsBMS) {
				dirs.forEach(bf -> {
					try {
						bf.processDirectory(property);
					} catch (IOException | SQLException | IllegalArgumentException | ReflectiveOperationException e) {
						Logger.getGlobal().severe("楽曲データベース更新時の例外:" + e.getMessage());
					}
				});
			}

			// folderテーブルの更新
			if (updateFolder) {
				final String s = path.path().replace('\\', '/') + "/";
				FileHandle parentpath = path.parent();

				FolderData folder = new FolderData();
				folder.setTitle(path.name());
				folder.setPath(s);
				folder.setParent(SongUtils.crc32(parentpath.path() , bmsroot, root.toString()));
				folder.setDate((int) (path.lastModified() / 1000));
				folder.setAdddate((int) property.updatetime);

				SQLiteSongDatabaseAccessor.this.insert(qr, property.conn, "folder", folder);
			}
			// ディレクトリ内に存在しないフォルダレコードを削除
			folders.forEach(folder -> {
				if (folder != null) {
					try {
						qr.update(property.conn, "DELETE FROM folder WHERE path LIKE ?", folder.getPath() + "%");
						qr.update(property.conn, "DELETE FROM song WHERE path LIKE ?", folder.getPath() + "%");
					} catch (SQLException e) {
						Logger.getGlobal().severe("ディレクトリ内に存在しないフォルダレコード削除の例外:" + e.getMessage());
					}
				}
			});
		}

		private int processBMSFolder(List<SongData> records, SongDatabaseUpdaterProperty property) {
			Logger.getGlobal().info("处理BMS文件夹: " + path.path() + ", 找到 " + bmsfiles.size() + " 个BMS文件");
			int count = 0;
			BMSDecoder bmsdecoder = null;
			BMSONDecoder bmsondecoder = null;
			final int len = records.size();
			for (FileHandle path : bmsfiles) {
				long lastModifiedTime = path.lastModified() / 1000;
				boolean update = true;
				final String pathname = path.path().replace('\\', '/');
				for (int i = 0;i < len;i++) {
					final SongData record = records.get(i);
					if (record != null && record.getPath().equals(pathname)) {
						records.set(i, null);
						if (record.getDate() == lastModifiedTime) {
							update = false;

							String oldpp = record.getPreview() == null ? "" : record.getPreview();
							String newpp = previewpath == null ? "" : previewpath;
							if (!oldpp.equals(newpp)) {
								try {
									qr.update(property.conn, "UPDATE song SET preview=? WHERE path=?", newpp, pathname);
								} catch (SQLException e) {
									Logger.getGlobal().warning("Error while updating preview at " + pathname + ": " + e.getMessage());
								}
							}
						}
						break;
					}
				}
				if (!update) {
					continue;
				}
			 BMSModel model = null;
				if (pathname.toLowerCase().endsWith(".bmson")) {
					if (bmsondecoder == null) {
						bmsondecoder = new BMSONDecoder(BMSModel.LNTYPE_LONGNOTE);
					}
					try {
						model = bmsondecoder.decode(path);
					} catch (Exception e) {
						Logger.getGlobal().severe("Error while decoding bmson at path: " + pathname + e.getMessage());
					}
				} else {
					if (bmsdecoder == null) {
						bmsdecoder = new BMSDecoder(BMSModel.LNTYPE_LONGNOTE);
					}
					try {
						model = bmsdecoder.decode(path);
					} catch (Exception e) {
						Logger.getGlobal().severe("Error while decoding bms at path: " + pathname + e.getMessage());
					}
				}

				if (model == null) {
					continue;
				}
				final SongData sd = new SongData(model, txt);
				if (sd.getNotes() != 0 || model.getWavList().length != 0) {
					if (sd.getDifficulty() == 0) {
						final String fulltitle = (sd.getTitle() + sd.getSubtitle()).toLowerCase();
						final String diffname = (sd.getSubtitle()).toLowerCase();
						if (diffname.contains("beginner")) {
							sd.setDifficulty(1);
						} else if (diffname.contains("normal")) {
							sd.setDifficulty(2);
						} else if (diffname.contains("hyper")) {
							sd.setDifficulty(3);
						} else if (diffname.contains("another")) {
							sd.setDifficulty(4);
						} else if (diffname.contains("insane") || diffname.contains("leggendaria")) {
							sd.setDifficulty(5);
						} else {
							if (fulltitle.contains("beginner")) {
								sd.setDifficulty(1);
							} else if (fulltitle.contains("normal")) {
								sd.setDifficulty(2);
							} else if (fulltitle.contains("hyper")) {
								sd.setDifficulty(3);
							} else if (fulltitle.contains("another")) {
								sd.setDifficulty(4);
							} else if (fulltitle.contains("insane") || fulltitle.contains("leggendaria")) {
								sd.setDifficulty(5);
							} else {
								if (sd.getNotes() < 250) {
									sd.setDifficulty(1);
								} else if (sd.getNotes() < 600) {
									sd.setDifficulty(2);
								} else if (sd.getNotes() < 1000) {
									sd.setDifficulty(3);
								} else if (sd.getNotes() < 2000) {
									sd.setDifficulty(4);
								} else {
									sd.setDifficulty(5);
								}
							}
						}
					}
					if((sd.getPreview() == null || sd.getPreview().length() == 0) && previewpath != null) {
						sd.setPreview(previewpath);
					}
					final String tag = property.tags.get(sd.getSha256());
					final Integer favorite = property.favorites.get(sd.getSha256());

					for(SongDatabaseAccessorPlugin plugin : plugins) {
						plugin.update(model, sd);
					}

					sd.setTag(tag != null ? tag : "");
					sd.setPath(pathname);
					sd.setFolder(SongUtils.crc32(path.parent().path(), bmsroot, root.toString()));
					sd.setParent(SongUtils.crc32(path.parent().parent().path(), bmsroot, root.toString()));
					sd.setDate((int) lastModifiedTime);
					sd.setFavorite(favorite != null ? favorite.intValue() : 0);
					sd.setAdddate((int) property.updatetime);
					try {
						SQLiteSongDatabaseAccessor.this.insert(qr, property.conn, "song", sd);
					} catch (SQLException e) {
						e.printStackTrace();
					}
					if(property.info != null) {
						property.info.update(model);
					}
					count++;
				} else {
					try {
						qr.update(property.conn, "DELETE FROM song WHERE path = ?", pathname);
					} catch (SQLException e) {
						e.printStackTrace();
					}
				}
			}
			// ディレクトリ内のファイルに存在しないレコードを削除
			records.parallelStream().filter(Objects::nonNull).forEach(record -> {
				try {
					qr.update(property.conn, "DELETE FROM song WHERE path = ?", record.getPath());
				} catch (SQLException e) {
					e.printStackTrace();
				}
			});

			return count;
		}
	}

	private static class SongDatabaseUpdaterProperty {
		private final Map<String, String> tags = new HashMap<String, String>();
		private final Map<String, Integer> favorites = new HashMap<String, Integer>();
		private final SongInformationAccessor info;
		private final long updatetime;
		private final AtomicInteger count = new AtomicInteger();
		private Connection conn;

		public SongDatabaseUpdaterProperty(long updatetime, SongInformationAccessor info) {
			this.updatetime = updatetime;
			this.info = info;
		}

	}

	public static interface SongDatabaseAccessorPlugin {

		public void update(BMSModel model, SongData song);
	}

	/**
	 * Update song database by scanning the given paths recursively using LibGDX FileHandle API.
	 *
	 * @param paths Array of root paths to scan for BMS files
	 */
	public void updateSongDatas(String[] paths) {
		long startTime = System.currentTimeMillis();
		AtomicInteger fileCount = new AtomicInteger(0);
		BMSDecoder decoder = new BMSDecoder(BMSModel.LNTYPE_LONGNOTE);

		try (Connection conn = qr.getDataSource().getConnection()) {
			// Force table validation/creation before scanning to ensure song table exists
			Logger.getGlobal().info("updateSongDatas: Validating database tables before scanning...");
			validate(qr);
			Logger.getGlobal().info("updateSongDatas: Table validation completed");

			conn.setAutoCommit(false);

			for (String path : paths) {
				FileHandle root = Gdx.files.absolute(path);
				if (root.exists()) {
					scanFolderRecursively(root, decoder, conn, fileCount);
				} else {
					Logger.getGlobal().warning("Path does not exist: " + path);
				}
			}

			conn.commit();
			long endTime = System.currentTimeMillis();
			Logger.getGlobal().info("updateSongDatas completed: " + fileCount.get() + " files processed in "
					+ (endTime - startTime) + "ms");
		} catch (SQLException e) {
			Logger.getGlobal().severe("================================================================================");
			Logger.getGlobal().severe("Database error during updateSongDatas: " + e.getMessage());
			Logger.getGlobal().severe("Full stack trace:");
			e.printStackTrace();
			Logger.getGlobal().severe("================================================================================");
		} catch (Exception e) {
			Logger.getGlobal().severe("================================================================================");
			Logger.getGlobal().severe("Unexpected error during updateSongDatas: " + e.getMessage());
			Logger.getGlobal().severe("Full stack trace:");
			e.printStackTrace();
			Logger.getGlobal().severe("================================================================================");
		}
	}

	/**
	 * Recursively scan a folder for BMS files using LibGDX FileHandle API.
	 *
	 * @param folder The folder to scan
	 * @return List of BMS files found
	 */
	private void scanFolderRecursively(FileHandle folder, BMSDecoder decoder, Connection conn, AtomicInteger fileCount) {
		if (!folder.exists()) {
			return;
		}

		FileHandle[] children = folder.list();
		if (children == null) {
			return;
		}

		for (FileHandle child : children) {
			if (child.isDirectory()) {
				scanFolderRecursively(child, decoder, conn, fileCount);
			} else {
				String fileName = child.name().toLowerCase();
				if (isBmsFile(fileName)) {
					processBmsFile(child, decoder, conn, fileCount);
				}
			}
		}
	}

	/**
	 * Check if file extension matches BMS file types: .bms, .bme, .bml, .pms.
	 *
	 * @param fileName Name of the file to check
	 * @return true if it's a BMS file
	 */
	private boolean isBmsFile(String fileName) {
		return fileName.endsWith(".bms") || fileName.endsWith(".bme") ||
				fileName.endsWith(".bml") || fileName.endsWith(".pms");
	}

	/**
	 * Process a single BMS file: parse it, map to SongData, insert into database.
	 *
	 * @param file The BMS file to process
	 * @param decoder BMS decoder instance
	 * @param conn Database connection
	 * @param fileCount Counter for processed files
	 */
	private void processBmsFile(FileHandle file, BMSDecoder decoder, Connection conn, AtomicInteger fileCount) {
		try {
			// Guard against null file
			if (file == null || !file.exists()) {
				Logger.getGlobal().warning("BMS file does not exist: " + (file != null ? file.path() : "null"));
				return;
			}

			BMSModel model = decoder.decode(file);
			if (model == null) {
				Logger.getGlobal().warning("Failed to parse BMS file: " + file.path());
				return;
			}

			SongData songData = new SongData(model, false);
			String pathName = file.path().replace('\\', '/');
			songData.setPath(pathName);

			// Get parent folder path CRC with null checks
			if (file.parent() != null) {
				songData.setFolder(SongUtils.crc32(file.parent().path(), null, root.toString()));
				if (file.parent().parent() != null) {
					songData.setParent(SongUtils.crc32(file.parent().parent().path(), null, root.toString()));
				} else {
					songData.setParent("");
				}
			} else {
				songData.setFolder("");
				songData.setParent("");
			}

			songData.setDate((int) (file.lastModified() / 1000));
			long currentTime = System.currentTimeMillis() / 1000;
			songData.setAdddate((int) currentTime);

			// Apply any plugins
			for (SongDatabaseAccessorPlugin plugin : plugins) {
				plugin.update(model, songData);
			}

			try {
				if (conn != null) {
					insert(qr, conn, "song", songData);
				} else {
					insert(qr, "song", songData);
				}
			} catch (SQLException e) {
				Logger.getGlobal().severe("================================================================================");
				Logger.getGlobal().severe("SQL INSERT failed for BMS file: " + file.path());
				Logger.getGlobal().severe("Error: " + e.getMessage());
				Logger.getGlobal().severe("Song data: path=" + songData.getPath() + ", md5=" + songData.getMd5() + ", sha256=" + songData.getSha256());
				Logger.getGlobal().severe("Full stack trace:");
				e.printStackTrace();
				Logger.getGlobal().severe("================================================================================");
				throw e;
			}

			fileCount.incrementAndGet();

			if (fileCount.get() % 100 == 0) {
				Logger.getGlobal().info("Processed " + fileCount.get() + " BMS files...");
			}
		} catch (Exception e) {
			Logger.getGlobal().severe("================================================================================");
			Logger.getGlobal().severe("Error processing BMS file " + file.path() + ": " + e.getMessage());
			Logger.getGlobal().severe("Full stack trace:");
			e.printStackTrace();
			Logger.getGlobal().severe("================================================================================");
		}
	}
}
