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
        try {
            for(Table table : tables) {
                // 检查表是否存在
                boolean tableExists = qr.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?;",
                    new ResultSetHandler<Boolean>() {
                        @Override
                        public Boolean handle(ResultSet rs) throws SQLException {
                            return rs.next();
                        }
                    }, table.getName());

                if (!tableExists) {
                    // 创建新表
                    createTable(qr, table);
                } else {
                    // 表已存在，检查是否需要修复（特别是针对之前错误的 score 表）
                    List<Column> existingColumns = qr.query("PRAGMA table_info('" + table.getName() + "');", columnhandler);
                    boolean hasPk = false;
                    boolean needsRecreate = false;

                    // 特殊逻辑：如果是 score 表且缺少关键列 mode，或者完全没有主键，则需要重建
                    if (table.getName().equals("score")) {
                        boolean hasMode = false;
                        for (Column c : existingColumns) {
                            if (c.getName().equalsIgnoreCase("mode")) hasMode = true;
                            if (c.getPk() == 1) hasPk = true;
                        }
                        if (!hasMode || !hasPk) {
                            needsRecreate = true;
                        }
                    }

                    if (needsRecreate) {
                        java.util.logging.Logger.getGlobal().info("Fixing broken table schema for: " + table.getName());
                        qr.update("ALTER TABLE [" + table.getName() + "] RENAME TO [" + table.getName() + "_old]");
                        createTable(qr, table);
                        try {
                            // 尝试迁移数据（忽略不匹配的列）
                            StringBuilder cols = new StringBuilder();
                            boolean first = true;
                            for (Column c : table.getColumn()) {
                                for (Column existing : existingColumns) {
                                    if (existing.getName().equalsIgnoreCase(c.getName())) {
                                        if (!first) cols.append(",");
                                        cols.append("[").append(c.getName()).append("]");
                                        first = false;
                                        break;
                                    }
                                }
                            }
                            if (cols.length() > 0) {
                                qr.update("INSERT OR IGNORE INTO [" + table.getName() + "] (" + cols + ") SELECT " + cols + " FROM [" + table.getName() + "_old]");
                            }
                            qr.update("DROP TABLE [" + table.getName() + "_old]");
                        } catch (Exception e) {
                            java.util.logging.Logger.getGlobal().warning("Failed to migrate data for " + table.getName() + ": " + e.getMessage());
                        }
                    } else {
                        // 检查并添加缺失的列
                        List<Column> adds = new ArrayList<>(Arrays.asList(table.getColumn()));
                        for (Column existing : existingColumns) {
                            adds.removeIf(a -> a.getName().equalsIgnoreCase(existing.getName()));
                        }
                        for (Column add : adds) {
                            qr.update("ALTER TABLE [" + table.getName() + "] ADD COLUMN [" + add.getName() + "] " + add.getType()
                                + (add.getNotnull() == 1 ? " NOT NULL" : "")
                                + (add.getDefaultval() != null && !add.getDefaultval().isEmpty() ? " DEFAULT " + add.getDefaultval() : ""));
                        }
                    }
                }
            }
        } catch (Exception e) {
            java.util.logging.Logger.getGlobal().severe("Database validation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createTable(QueryRunner qr, Table table) throws SQLException {
        StringBuilder sql = new StringBuilder("CREATE TABLE [" + table.getName() + "] (");
        List<Column> pk = new ArrayList<>();
        boolean comma = false;
        for (Column column : table.getColumn()) {
            sql.append(comma ? "," : "").append('[').append(column.getName()).append("] ").append(column.getType())
                .append(column.getNotnull() == 1 ? " NOT NULL" : "")
                .append(column.getDefaultval() != null && !column.getDefaultval().isEmpty() ? " DEFAULT " + column.getDefaultval() : "");
            comma = true;
            if (column.getPk() == 1) {
                pk.add(column);
            }
        }

        if (!pk.isEmpty()) {
            sql.append(",PRIMARY KEY(");
            comma = false;
            for (Column column : pk) {
                sql.append(comma ? "," : "").append("[").append(column.getName()).append("]");
                comma = true;
            }
            sql.append(")");
        }
        sql.append(");");
        qr.update(sql.toString());
        java.util.logging.Logger.getGlobal().info("Table Created: " + table.getName());
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
