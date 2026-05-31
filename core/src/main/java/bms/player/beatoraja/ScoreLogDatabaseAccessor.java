package bms.player.beatoraja;

import java.sql.SQLException;

import org.apache.commons.dbutils.QueryRunner;

/**
 * @deprecated Use {@link ScoreDatabaseAccessor#setScoreLog(ScoreDatabaseAccessor.ScoreLog)} instead.
 * Score log functionality is now merged into the unified ScoreDatabaseAccessor.
 */
@Deprecated
public class ScoreLogDatabaseAccessor extends SQLiteDatabaseAccessor {

	private final QueryRunner qr;

	public ScoreLogDatabaseAccessor(String path) throws ClassNotFoundException {
		super(	new Table("scorelog",
						new Column("sha256", "TEXT", 1, 0),
						new Column("mode", "INTEGER"),
						new Column("clear", "INTEGER"),
						new Column("oldclear", "INTEGER"),
						new Column("score", "INTEGER"),
						new Column("oldscore", "INTEGER"),
						new Column("combo", "INTEGER"),
						new Column("oldcombo", "INTEGER"),
						new Column("minbp", "INTEGER"),
						new Column("oldminbp", "INTEGER"),
						new Column("date", "INTEGER")
						));

		qr = new QueryRunner(DatabaseUtils.getDataSource(path));

		try {
			this.validate(qr);
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	public void setScoreLog(ScoreDatabaseAccessor.ScoreLog log) {
		try {
			this.insert(qr, "scorelog", log);
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}
}
