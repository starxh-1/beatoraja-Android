package bms.player.beatoraja;

import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;

import bms.player.beatoraja.song.SongData;

/**
 * スコアデータベースアクセサ
 *
 * @author exch
 */
public class ScoreDatabaseAccessor extends SQLiteDatabaseAccessor {

	private final QueryRunner qr;
	private final ReentrantLock dblock = new ReentrantLock();

	private static final long READ_TIMEOUT_MILLIS = 500;

	private final ResultSetHandler<List<PlayerInformation>> infoHandler = new AndroidBeanListHandler<>(PlayerInformation.class);
	private final ResultSetHandler<List<ScoreData>> scoreHandler = new AndroidBeanListHandler<>(ScoreData.class);
	private final ResultSetHandler<List<PlayerData>> playerHandler = new AndroidBeanListHandler<>(PlayerData.class);

	public ScoreDatabaseAccessor(String path) throws ClassNotFoundException {
		super(new Table("info",
				new Column("id", "TEXT",1,1),
				new Column("name", "TEXT",1,0),
				new Column("rank", "TEXT")
				),
				new Table("player",
						new Column("date", "INTEGER",0,1),
						new Column("playcount", "INTEGER"),
						new Column("clear", "INTEGER"),
						new Column("epg", "INTEGER"),
						new Column("lpg", "INTEGER"),
						new Column("egr", "INTEGER"),
						new Column("lgr", "INTEGER"),
						new Column("egd", "INTEGER"),
						new Column("lgd", "INTEGER"),
						new Column("ebd", "INTEGER"),
						new Column("lbd", "INTEGER"),
						new Column("epr", "INTEGER"),
						new Column("lpr", "INTEGER"),
						new Column("ems", "INTEGER"),
						new Column("lms", "INTEGER"),
						new Column("playtime", "INTEGER"),
						new Column("maxcombo", "INTEGER")
						),
				new Table("score",
						new Column("sha256", "TEXT", 1, 1),
						new Column("mode", "INTEGER",0,1),
						new Column("clear", "INTEGER"),
						new Column("epg", "INTEGER"),
						new Column("lpg", "INTEGER"),
						new Column("egr", "INTEGER"),
						new Column("lgr", "INTEGER"),
						new Column("egd", "INTEGER"),
						new Column("lgd", "INTEGER"),
						new Column("ebd", "INTEGER"),
						new Column("lbd", "INTEGER"),
						new Column("epr", "INTEGER"),
						new Column("lpr", "INTEGER"),
						new Column("ems", "INTEGER"),
						new Column("lms", "INTEGER"),
						new Column("notes", "INTEGER"),
						new Column("combo", "INTEGER"),
						new Column("minbp", "INTEGER"),
						new Column("avgjudge", "INTEGER", 1, 0, String.valueOf(Integer.MAX_VALUE)),
						new Column("playcount", "INTEGER"),
						new Column("clearcount", "INTEGER"),
						new Column("trophy", "TEXT"),
						new Column("ghost", "TEXT"),
						new Column("option", "INTEGER"),
						new Column("seed", "INTEGER"),
						new Column("random", "INTEGER"),
						new Column("date", "INTEGER"),
						new Column("state", "INTEGER"),
						new Column("scorehash", "TEXT")
						));
		qr = new QueryRunner(DatabaseUtils.getDataSource(path));
	}

	public void createTable() {
		try {
			validate(qr);
			if(this.getPlayerDatas(1).length == 0) {
				this.insert(qr, "player", new PlayerData());
			}
		} catch (SQLException e) {
			Logger.getGlobal().severe("スコアデータベース初期化中の例外:" + e.getMessage());
		}
	}

	public PlayerInformation getInformation() {
		if (!lockForRead("getInformation")) {
			return null;
		}
		try {
			List<PlayerInformation> info =  qr.query("SELECT * FROM info", infoHandler);
			if (info.size() > 0) {
				return info.get(0);
			}
		} catch (Exception e) {
			Logger.getGlobal().severe("スコア取得時の例外:" + e.getMessage());
		} finally {
			dblock.unlock();
		}
		return null;
	}

	public void setInformation(PlayerInformation info) {
		dblock.lock();
		try {
			qr.update("DELETE FROM info");
			insert(qr, "info", info);
		} catch (Exception e) {
			Logger.getGlobal().severe("スコア更新時の例外:" + e.getMessage());
		} finally {
			dblock.unlock();
		}
	}

	public ScoreData getScoreData(String hash, int mode) {
		// [DEBUG PROBE] DB 锁等待：getScoreData
		// bms.player.beatoraja.result.debug.ResultFreezeDiagnostics.probeDBLockWait("getScoreData");
		if (!lockForRead("getScoreData")) {
			// [DEBUG PROBE] DB 读超时返回 null
			// bms.player.beatoraja.result.debug.ResultFreezeDiagnostics.log("getScoreData:TIMEOUT returning null");
			return null;
		}
		// [DEBUG PROBE] DB 锁获取：getScoreData
		// bms.player.beatoraja.result.debug.ResultFreezeDiagnostics.probeDBLockAcquired("getScoreData");
		try {
			List<ScoreData> score = Validatable.removeInvalidElements(qr.query("SELECT * FROM score WHERE sha256 = '" + hash + "' AND mode = " + mode, scoreHandler));
			if (score.size() > 0) {
				ScoreData sc = null;
				for (ScoreData s : score) {
					if (sc == null || s.getClear() > sc.getClear()) {
						sc = s;
					}
				}
				return sc;
			}
		} catch (Exception e) {
			Logger.getGlobal().severe("スコア取得時の例外:" + e.getMessage());
		} finally {
			dblock.unlock();
		}
		return null;
	}

	/**
	 * プレイヤースコアデータを取得する
	 */
	public void getScoreDatas(ScoreDataCollector collector, SongData[] songs, int mode) {
		if (!lockForRead("getScoreDatas")) {
			for (SongData song : songs) {
				collector.collect(song, null);
			}
			return;
		}
		try {
			StringBuilder str = new StringBuilder(songs.length * 68);
			getScoreDatas(collector, songs, mode, str, true);
			str.setLength(0);
			getScoreDatas(collector, songs, 0, str, false);
		} finally {
			dblock.unlock();
		}
	}

	private void getScoreDatas(ScoreDataCollector collector, SongData[] songs, int mode, StringBuilder str, boolean hasln) {
		try {
			for (SongData song : songs) {
				if((hasln && song.hasUndefinedLongNote()) || (!hasln && !song.hasUndefinedLongNote())) {
					if (str.length() > 0) {
						str.append(',');
					}
					str.append('\'').append(song.getSha256()).append('\'');
				}
			}

			List<ScoreData> scores = Validatable.removeInvalidElements(qr
					.query("SELECT * FROM score WHERE sha256 IN (" + str.toString() + ") AND mode = " + mode, scoreHandler));
			for(SongData song : songs) {
				if((hasln && song.hasUndefinedLongNote()) || (!hasln && !song.hasUndefinedLongNote())) {
					boolean b = true;
					for (ScoreData score : scores) {
						if(song.getSha256().equals(score.getSha256())) {
							collector.collect(song, score);
							b = false;
							break;
						}
					}
					if(b) {
						collector.collect(song, null);
					}
				}
			}
		} catch (Exception e) {
			Logger.getGlobal().severe("スコア取得時の例外:" + e.getMessage());
		}
	}

	public List<ScoreData> getScoreDatas(String sql) {
		if (!lockForRead("getScoreDatas(sql)")) {
			return null;
		}
		try {
			return Validatable.removeInvalidElements(qr.query("SELECT * FROM score WHERE " + sql, scoreHandler));
		} catch (Exception e) {
			Logger.getGlobal().severe("スコア取得時の例外:" + e.getMessage());
		} finally {
			dblock.unlock();
		}
		return null;
	}

	public void setScoreData(ScoreData score) {
		setScoreData(new ScoreData[] { score });
	}

	public void setScoreData(ScoreData[] scores) {
		// [DEBUG PROBE] DB 锁等待：setScoreData
		// bms.player.beatoraja.result.debug.ResultFreezeDiagnostics.probeDBLockWait("setScoreData");
		dblock.lock();
		// [DEBUG PROBE] DB 锁获取：setScoreData
		// bms.player.beatoraja.result.debug.ResultFreezeDiagnostics.probeDBLockAcquired("setScoreData");
		try (Connection con = qr.getDataSource().getConnection()) {
			con.setAutoCommit(false);
			for (ScoreData score : scores) {
				this.insert(qr, con, "score", score);
			}
			con.commit();
		} catch (Exception e) {
			Logger.getGlobal().severe("スコア更新時の例外:" + e.getMessage());
		} finally {
			dblock.unlock();
		}
	}

	public void setScoreData(Map<String, Map<String, Object>> map) {
		dblock.lock();
		try (Connection con = qr.getDataSource().getConnection()) {
			con.setAutoCommit(false);
			for (String hash : map.keySet()) {
				Map<String, Object> values = map.get(hash);
				String vs = "";
				for (String key : values.keySet()) {
					vs += key + " = " + values.get(key) + ",";
				}
				if (vs.length() > 0) {
					vs = vs.substring(0, vs.length() - 1) + " ";
					qr.update(con, "UPDATE score SET " + vs + "WHERE sha256 = '" + hash + "'");
				}
			}
			con.commit();
		} catch (Exception e) {
			Logger.getGlobal().severe("スコア更新時の例外:" + e.getMessage());
		} finally {
			dblock.unlock();
		}
	}

	public void deleteScoreData(String sha256, int mode) {
		dblock.lock();
		try {
			qr.update("DELETE FROM score WHERE sha256 = ? and mode = ?", sha256, mode);
		} catch (SQLException e) {
			e.printStackTrace();
		} finally {
			dblock.unlock();
		}
	}

	/**
	 * プレイヤーデータを取得する
	 *
	 * @return プレイヤーデータ
	 */
	public PlayerData getPlayerData() {
		PlayerData[] pd = getPlayerDatas(1);
		if (pd.length > 0) {
			return pd[0];
		}
		return null;
	}

	public PlayerData[] getPlayerDatas(int count) {
		if (!lockForRead("getPlayerDatas")) {
			return new PlayerData[0];
		}
		try {
			List<PlayerData> pd = qr
					.query("SELECT * FROM player ORDER BY date DESC" + (count > 0 ? " limit " + count : ""), playerHandler);
			return pd.toArray(new PlayerData[0]);
		} catch (Exception e) {
			Logger.getGlobal().severe("プレイヤーデータ取得時の例外:" + e.getMessage());
		} finally {
			dblock.unlock();
		}
		return new PlayerData[0];
	}

	/**
	 * プレイヤーデータを設定する
	 *
	 * @param pd プレイヤーデータ
	 */
	public void setPlayerData(PlayerData pd) {
		// [DEBUG PROBE] DB 锁等待：setPlayerData
		// bms.player.beatoraja.result.debug.ResultFreezeDiagnostics.probeDBLockWait("setPlayerData");
		dblock.lock();
		// [DEBUG PROBE] DB 锁获取：setPlayerData
		// bms.player.beatoraja.result.debug.ResultFreezeDiagnostics.probeDBLockAcquired("setPlayerData");
		try (Connection con = qr.getDataSource().getConnection()) {
			con.setAutoCommit(false);
			Calendar cal = Calendar.getInstance(TimeZone.getDefault());
			cal.set(Calendar.HOUR_OF_DAY, 0);
			cal.set(Calendar.MINUTE, 0);
			cal.set(Calendar.SECOND, 0);
			cal.set(Calendar.MILLISECOND, 0);
			long unixtime = cal.getTimeInMillis() / 1000L;
			pd.setDate(unixtime);
			this.insert(qr, con, "player", pd);
			con.commit();
		} catch (Exception e) {
			Logger.getGlobal().severe("スコア更新時の例外:" + e.getMessage());
		} finally {
			dblock.unlock();
		}
	}

	private boolean lockForRead(String operation) {
		try {
			if (!dblock.tryLock(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
				Logger.getGlobal().warning("ScoreDatabaseAccessor." + operation + " DB lock timeout after " + READ_TIMEOUT_MILLIS + "ms, skipping");
				return false;
			}
			return true;
		} catch (InterruptedException e) {
			Logger.getGlobal().warning("ScoreDatabaseAccessor." + operation + " interrupted while waiting for DB lock");
			Thread.currentThread().interrupt();
			return false;
		}
	}

	public interface ScoreDataCollector {

		public void collect(SongData hash, ScoreData score);
	}
}
