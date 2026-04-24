package bms.player.beatoraja;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;

/**
 * SQLiteデータベースアクセス用抽象クラス
 *
 * @author exch
 */
public abstract class SQLiteDatabaseAccessor {

    private final ResultSetHandler<List<Column>> columnhandler = new AndroidBeanListHandler<>(Column.class);

    private final Table[] tables;

    public SQLiteDatabaseAccessor(Table... tables) {
        this.tables = tables;
    }

    /**
     * 指定のカラムを持つテーブルを作成する。 テーブルやカラムが存在しない場合、作成する。
     *
     * @param qr
     * QueryRunner
     * @throws SQLException
     */
    public void validate(QueryRunner qr) throws SQLException {
        // 1. 屏蔽安卓不支持的日志模式
        try {
            qr.update("PRAGMA journal_mode=WAL");
        } catch (Exception e) {
            // 安卓环境下不支持此 PRAGMA 写法，直接忽略
        }

        // 2. 暴力建表：强制创建 UI 层最依赖的五张核心表，防止空指针闪退
        try {
            qr.update("CREATE TABLE IF NOT EXISTS song (md5 TEXT PRIMARY KEY, sha256 TEXT, title TEXT, subtitle TEXT, genre TEXT, artist TEXT, subartist TEXT, path TEXT, folder TEXT, stagefile TEXT, banner TEXT, backbmp TEXT, parent TEXT, level INTEGER, difficulty INTEGER, maxbpm REAL, minbpm REAL, mode INTEGER, judge INTEGER, feature INTEGER, content INTEGER, date INTEGER, favorite INTEGER, notes INTEGER, adddate INTEGER, preview TEXT, length INTEGER, charthash TEXT)");
            System.out.println("Table Created: song");
            java.util.logging.Logger.getGlobal().info("Table Created: song");

            qr.update("CREATE TABLE IF NOT EXISTS folder (path TEXT PRIMARY KEY, name TEXT, parent TEXT)");
            System.out.println("Table Created: folder");
            java.util.logging.Logger.getGlobal().info("Table Created: folder");

            qr.update("CREATE TABLE IF NOT EXISTS information (sha256 TEXT PRIMARY KEY, mode INTEGER, level INTEGER, clear INTEGER, epclear INTEGER, bpclear INTEGER, noplay INTEGER, failed INTEGER, assist INTEGER, easy INTEGER, normal INTEGER, hard INTEGER, exhard INTEGER, fc INTEGER, perfect INTEGER)");
            System.out.println("Table Created: information");
            java.util.logging.Logger.getGlobal().info("Table Created: information");

            qr.update("CREATE TABLE IF NOT EXISTS score (sha256 TEXT, playcount INTEGER, clear INTEGER, score INTEGER, exscore INTEGER, maxcombo INTEGER, minbp INTEGER, perfect INTEGER, great INTEGER, good INTEGER, bad INTEGER, poor INTEGER, totalnotes INTEGER, fast INTEGER, slow INTEGER, date INTEGER, log TEXT, hash TEXT)");
            System.out.println("Table Created: score");
            java.util.logging.Logger.getGlobal().info("Table Created: score");

            qr.update("CREATE TABLE IF NOT EXISTS scorelog (sha256 TEXT, date INTEGER, clear INTEGER, score INTEGER, exscore INTEGER, maxcombo INTEGER, minbp INTEGER, perfect INTEGER, great INTEGER, good INTEGER, bad INTEGER, poor INTEGER, totalnotes INTEGER, fast INTEGER, slow INTEGER, option INTEGER, option2 INTEGER)");
            System.out.println("Table Created: scorelog");
            java.util.logging.Logger.getGlobal().info("Table Created: scorelog");
        } catch (Exception e) {
            System.err.println("Exception during table creation: " + e.getMessage());
            e.printStackTrace();
        }

        // 3. 恢复原本的动态建表与列更新逻辑（用 try-catch 保护，防止个别 SQL 导致崩溃）
        try {
            for(Table table : tables) {
                List<Column> pk = new ArrayList<Column>();
                if (qr.query("SELECT * FROM sqlite_master WHERE name = ? and type='table';", new AndroidBeanListHandler<>(Map.class), table.getName())
                    .size() == 0) {
                    StringBuilder sql = new StringBuilder("CREATE TABLE [" + table.getName() + "] (");
                    boolean comma = false;
                    for (Column column : table.getColumn()) {
                        sql.append(comma ? "," : "").append('[').append(column.getName()).append("] ").append(column.getType())
                            .append(column.getNotnull() == 1 ? " NOT NULL" : "").append(column.getDefaultval() != null && column.getDefaultval().length() > 0 ? " DEFAULT " + column.getDefaultval() : "");
                        comma = true;
                        if (column.getPk() == 1) {
                            pk.add(column);
                        }
                    }

                    if (pk.size() > 0) {
                        sql.append(",PRIMARY KEY(");
                        comma = false;
                        for (Column column : pk) {
                            sql.append(comma ? "," : "").append(column.getName());
                            comma = true;
                        }
                        sql.append(")");
                    }
                    sql.append(");");
                    qr.update(sql.toString());
                }

                List<Column> adds = new ArrayList<Column>(Arrays.asList(table.getColumn()));
                for (Column songcolumn : qr.query("PRAGMA table_info('" + table.getName() + "');",
                    columnhandler)) {
                    final String name = (String) songcolumn.getName();
                    for (int i = 0; i < adds.size(); i++) {
                        if (adds.get(i).getName().equals(name)) {
                            adds.remove(i);
                            break;
                        }
                    }
                }
                for (Column add : adds) {
                    qr.update("ALTER TABLE " + table.getName() + " ADD COLUMN [" + add.getName() + "] " + add.getType()
                        + (add.getNotnull() == 1 ? " NOT NULL" : "") + (add.getDefaultval() != null && add.getDefaultval().length() > 0 ? " DEFAULT " + add.getDefaultval() : ""));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected void insert(QueryRunner qr, String tablename,
                          Object entity) throws SQLException {
        insert(qr, null, tablename, entity);
    }

    protected void insert(QueryRunner qr, Connection con, String tablename,
                          Object entity) throws SQLException {
        Column[] columns = null;
        for(Table table : tables) {
            if(table.getName().equals(tablename)) {
                columns = table.getColumn();
                break;
            }
        }
        if(columns == null) {
            return;
        }

        StringBuilder sql = new StringBuilder("INSERT OR REPLACE INTO " + tablename + " (");
        boolean comma = false;
        for (Column column : columns) {
            sql.append(comma ? "," : "").append(column.getName());
            comma = true;
        }
        sql.append(") VALUES(");

        Object[] params = new Object[columns.length];
        comma = false;
        for (int i = 0; i < columns.length; i++) {
            sql.append(comma ? ",?" : "?");
            comma = true;

            try {
                params[i] = getPropertyValue(entity, columns[i].getName());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        sql.append(");");

        if(con != null) {
            qr.update(con, sql.toString(), params);
        } else {
            qr.update(sql.toString(), params);
        }
    }

    private Object getPropertyValue(Object bean, String propertyName) throws Exception {
        String methodName = "get" + propertyName.substring(0, 1).toUpperCase() + propertyName.substring(1);
        try {
            Method method = bean.getClass().getMethod(methodName);
            return method.invoke(bean);
        } catch (NoSuchMethodException e) {
            // Try is... for booleans
            String isMethodName = "is" + propertyName.substring(0, 1).toUpperCase() + propertyName.substring(1);
            try {
                Method method = bean.getClass().getMethod(isMethodName);
                return method.invoke(bean);
            } catch (NoSuchMethodException e2) {
                // Fallback to field
                Field field = bean.getClass().getDeclaredField(propertyName);
                field.setAccessible(true);
                return field.get(bean);
            }
        }
    }

    public static class AndroidBeanListHandler<T> implements ResultSetHandler<List<T>> {
        private final Class<? extends T> type;

        public AndroidBeanListHandler(Class<? extends T> type) {
            this.type = type;
        }

        @Override
        public List<T> handle(ResultSet rs) throws SQLException {
            List<T> list = new ArrayList<>();
            ResultSetMetaData rsmd = rs.getMetaData();
            int cols = rsmd.getColumnCount();

            while (rs.next()) {
                try {
                    T bean;
                    if (type == Map.class) {
                        bean = (T) new HashMap<String, Object>();
                    } else {
                        bean = type.getDeclaredConstructor().newInstance();
                    }
                    for (int i = 1; i <= cols; i++) {
                        String columnName = rsmd.getColumnLabel(i);
                        if (columnName == null || columnName.isEmpty()) {
                            columnName = rsmd.getColumnName(i);
                        }
                        Object value = rs.getObject(i);
                        if (bean instanceof Map) {
                            ((Map<String, Object>) bean).put(columnName, value);
                        } else {
                            setProperty(bean, columnName, value);
                        }
                    }
                    list.add(bean);
                } catch (Exception e) {
                    throw new SQLException(e);
                }
            }
            return list;
        }

        private void setProperty(Object bean, String propertyName, Object value) throws Exception {
            if (value == null) return;
            String methodName = "set" + propertyName.substring(0, 1).toUpperCase() + propertyName.substring(1);
            Method[] methods = bean.getClass().getMethods();
            for (Method method : methods) {
                if (method.getName().equals(methodName) && method.getParameterTypes().length == 1) {
                    Class<?> paramType = method.getParameterTypes()[0];
                    method.invoke(bean, convertValue(value, paramType));
                    return;
                }
            }
            // Fallback to field
            try {
                Field field = bean.getClass().getDeclaredField(propertyName);
                field.setAccessible(true);
                field.set(bean, convertValue(value, field.getType()));
            } catch (NoSuchFieldException e) {
                // Ignore if neither method nor field exists
            }
        }

        private Object convertValue(Object value, Class<?> targetType) {
            if (value == null) return null;
            if (targetType.isInstance(value)) return value;
            if (targetType == int.class || targetType == Integer.class) {
                if (value instanceof Number) return ((Number) value).intValue();
                return Integer.parseInt(value.toString());
            }
            if (targetType == long.class || targetType == Long.class) {
                if (value instanceof Number) return ((Number) value).longValue();
                return Long.parseLong(value.toString());
            }
            if (targetType == double.class || targetType == Double.class) {
                if (value instanceof Number) return ((Number) value).doubleValue();
                return Double.parseDouble(value.toString());
            }
            if (targetType == float.class || targetType == Float.class) {
                if (value instanceof Number) return ((Number) value).floatValue();
                return Float.parseFloat(value.toString());
            }
            if (targetType == boolean.class || targetType == Boolean.class) {
                if (value instanceof Boolean) return value;
                if (value instanceof Number) return ((Number) value).intValue() != 0;
                return Boolean.parseBoolean(value.toString());
            }
            return value;
        }
    }

    /**
     * SQLiteテーブル
     *
     * @author exch
     */
    public static class Table {

        /**
         * テーブル名
         */
        private String name;

        /**
         * カラム
         */
        private Column[] column;

        public Table(String name, Column... column) {
            this.name = name;
            this.column = column;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Column[] getColumn() {
            return column;
        }

        public void setColumn(Column[] column) {
            this.column = column;
        }
    }

    /**
     * SQLiteカラム
     *
     * @author exch
     */
    public static class Column {

        /**
         * カラム名
         */
        private String name;
        /**
         * 値の型式
         */
        private String type;

        /**
         * NOT NULL = 1
         */
        private int notnull;

        /**
         * PRIMAL KEY = 1
         */
        private int pk;

        private String defaultval;

        public Column() {

        }

        public Column(String name, String type) {
            this(name, type, 0, 0);
        }

        public Column(String name, String type, int notnull, int pk) {
            this.name = name;
            this.type = type;
            this.notnull = notnull;
            this.pk = pk;
        }

        public Column(String name, String type, int notnull, int pk, String defaultval) {
            this.name = name;
            this.type = type;
            this.notnull = notnull;
            this.pk = pk;
            this.setDefaultval(defaultval);
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public int getNotnull() {
            return notnull;
        }

        public void setNotnull(int notnull) {
            this.notnull = notnull;
        }

        public int getPk() {
            return pk;
        }

        public void setPk(int pk) {
            this.pk = pk;
        }

        public String getDefaultval() {
            return defaultval;
        }

        public void setDefaultval(String defaultval) {
            this.defaultval = defaultval;
        }
    }
}
