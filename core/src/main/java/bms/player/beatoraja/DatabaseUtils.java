package bms.player.beatoraja;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

public class DatabaseUtils {

    /**
     * 每个数据库 URL 对应一个长生命周期的共享连接。
     * 在 Android (SQLDroid) 环境下，每次 DriverManager.getConnection() 都会
     * 调用 android.database.sqlite.SQLiteDatabase.openDatabase()，其内部
     * 执行 PRAGMA journal_mode 时如果文件锁尚未释放就会抛出
     * SQLiteDatabaseLockedException。
     * 通过复用同一个连接，避免反复 open/close 带来的锁竞争。
     */
    private static final ConcurrentHashMap<String, Connection> sharedConnections = new ConcurrentHashMap<>();
    private static final Object connLock = new Object();

    /**
     * 获取或创建指定 URL 的共享连接（仅 SQLDroid / Android 使用）。
     */
    private static Connection getOrCreateSharedConnection(String url) throws SQLException {
        Connection conn = sharedConnections.get(url);
        if (conn != null) {
            try {
                if (!conn.isClosed()) return conn;
            } catch (SQLException ignore) { /* connection broken, recreate */ }
        }
        synchronized (connLock) {
            conn = sharedConnections.get(url);
            if (conn != null) {
                try {
                    if (!conn.isClosed()) return conn;
                } catch (SQLException ignore) {}
            }
            conn = DriverManager.getConnection(url);
            try { conn.createStatement().execute("PRAGMA busy_timeout = 15000"); } catch (SQLException ignore) {}
            try { conn.createStatement().execute("PRAGMA journal_mode = WAL"); } catch (SQLException ignore) {}
            sharedConnections.put(url, conn);
            return conn;
        }
    }

    /**
     * 创建一个代理 Connection，将所有调用委托给 real，但：
     *  - close()  → 不关闭底层连接，只回滚未提交事务并重置 autoCommit
     *  - isClosed() → 始终返回 false
     */
    private static Connection wrapNonClosing(Connection real) {
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[]{ Connection.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "close":
                        // 不关闭共享连接；仅清理事务状态
                        try {
                            if (!real.getAutoCommit()) {
                                try { real.rollback(); } catch (SQLException ignore) {}
                                real.setAutoCommit(true);
                            }
                        } catch (SQLException ignore) {}
                        return null;
                    case "isClosed":
                        return false;
                    default:
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                }
            }
        );
    }

    public static DataSource getDataSource(String path) throws ClassNotFoundException {
        try {
            Class.forName("org.sqldroid.SQLDroidDriver");
            final String url = "jdbc:sqldroid:" + path;
            // Android (SQLDroid): 复用单一共享连接，避免反复 openDatabase()
            // 导致 PRAGMA journal_mode 时的 SQLITE_BUSY 锁竞争。
            return new DataSource() {
                @Override
                public Connection getConnection() throws SQLException {
                    return wrapNonClosing(getOrCreateSharedConnection(url));
                }

                @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
                @Override public PrintWriter getLogWriter() throws SQLException { return null; }
                @Override public void setLogWriter(PrintWriter out) throws SQLException {}
                @Override public void setLoginTimeout(int seconds) throws SQLException {}
                @Override public int getLoginTimeout() throws SQLException { return 0; }
                @Override public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
                @Override public <T> T unwrap(Class<T> iface) throws SQLException { return null; }
                @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return false; }
            };
        } catch (ClassNotFoundException e) {
            Class.forName("org.sqlite.JDBC");
            // Use reflection to avoid Android compilation issues
            try {
                Class<?> sqliteConfigClass = Class.forName("org.sqlite.SQLiteConfig");
                Object conf = sqliteConfigClass.getDeclaredConstructor().newInstance();
                sqliteConfigClass.getMethod("setSharedCache", boolean.class).invoke(conf, true);
                Class<?> synchronousModeClass = Class.forName("org.sqlite.SQLiteConfig$SynchronousMode");
                Object off = synchronousModeClass.getField("OFF").get(null);
                sqliteConfigClass.getMethod("setSynchronous", synchronousModeClass).invoke(conf, off);
                // Set journal mode to WAL
                Class<?> journalModeClass = Class.forName("org.sqlite.SQLiteConfig$JournalMode");
                Object wal = journalModeClass.getField("WAL").get(null);
                sqliteConfigClass.getMethod("setJournalMode", journalModeClass).invoke(conf, wal);
                Class<?> sqliteDataSourceClass = Class.forName("org.sqlite.SQLiteDataSource");
                Object ds = sqliteDataSourceClass.getDeclaredConstructor(sqliteConfigClass).newInstance(conf);
                sqliteDataSourceClass.getMethod("setUrl", String.class).invoke(ds, "jdbc:sqlite:" + path);
                return (DataSource) ds;
            } catch (Exception ex) {
                throw new ClassNotFoundException("SQLite JDBC not properly configured", ex);
            }
        }
    }
}
