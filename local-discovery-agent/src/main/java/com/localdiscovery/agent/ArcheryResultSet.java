package com.localdiscovery.agent;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用 Archery 返回结果伪造一个 ResultSet
 *
 * 通过 JDK 动态代理实现，只处理 MyBatis / 常规 JDBC 用户实际会调用的方法：
 *   next / close / wasNull / findColumn
 *   getObject / getString / getInt / getLong / getDouble / getFloat / getShort / getByte
 *   getBoolean / getBigDecimal / getBytes / getDate / getTime / getTimestamp
 *   getStatement / getMetaData / getRow / getType / getConcurrency
 *
 * 其他方法默认抛 SQLFeatureNotSupportedException
 */
public class ArcheryResultSet {

    public static ResultSet create(Statement stmt, ArcheryClient.QueryResult data) {
        Handler h = new Handler(stmt, data);
        return (ResultSet) Proxy.newProxyInstance(
                ArcheryResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                h);
    }

    /**
     * 把 Archery 的 column_type 字符串（MySQL FIELD_TYPE 名）映射到 java.sql.Types
     * 参考 Archery 源码 sql/engines/mysql.py 的类型定义
     */
    static int mapArcheryType(String t) {
        if (t == null || t.isEmpty()) return Types.VARCHAR;
        switch (t.toUpperCase()) {
            case "TINY":       return Types.TINYINT;
            case "SHORT":      return Types.SMALLINT;
            case "INT24":
            case "LONG":       return Types.INTEGER;
            case "LONGLONG":   return Types.BIGINT;
            case "FLOAT":      return Types.REAL;
            case "DOUBLE":     return Types.DOUBLE;
            case "DECIMAL":
            case "NEWDECIMAL": return Types.DECIMAL;
            case "BIT":        return Types.BIT;
            case "DATE":       return Types.DATE;
            case "TIME":       return Types.TIME;
            case "DATETIME":
            case "TIMESTAMP":  return Types.TIMESTAMP;
            case "YEAR":       return Types.SMALLINT;
            case "BLOB":
            case "TINY_BLOB":
            case "MEDIUM_BLOB":
            case "LONG_BLOB":  return Types.BLOB;
            case "JSON":       return Types.LONGVARCHAR;
            case "LONGTEXT":
            case "MEDIUMTEXT":
            case "TEXT":
            case "TINYTEXT":   return Types.LONGVARCHAR;
            case "STRING":
            case "VAR_STRING":
            case "VARCHAR":
            default:           return Types.VARCHAR;
        }
    }

    static String mapArcheryClassName(String t) {
        if (t == null || t.isEmpty()) return "java.lang.String";
        switch (t.toUpperCase()) {
            case "TINY":       return "java.lang.Byte";
            case "SHORT":
            case "YEAR":       return "java.lang.Short";
            case "INT24":
            case "LONG":       return "java.lang.Integer";
            case "LONGLONG":   return "java.lang.Long";
            case "FLOAT":      return "java.lang.Float";
            case "DOUBLE":     return "java.lang.Double";
            case "DECIMAL":
            case "NEWDECIMAL": return "java.math.BigDecimal";
            case "BIT":        return "java.lang.Boolean";
            case "DATE":       return "java.sql.Date";
            case "TIME":       return "java.sql.Time";
            case "DATETIME":
            case "TIMESTAMP":  return "java.sql.Timestamp";
            case "BLOB":
            case "TINY_BLOB":
            case "MEDIUM_BLOB":
            case "LONG_BLOB":  return "[B";
            case "LONGTEXT":
            case "MEDIUMTEXT":
            case "TEXT":
            case "TINYTEXT":
            case "JSON":       return "java.lang.String";
            default:           return "java.lang.String";
        }
    }

    private static class Handler implements InvocationHandler {
        private final Statement stmt;
        private final List<String> columns;
        private final List<String> columnTypes;
        private final List<List<Object>> rows;
        private final Map<String, Integer> nameIndex;
        private final ResultSetMetaData metaData;

        private int cursor = 0;      // 1-based，0 表示 before-first
        private boolean closed = false;
        private boolean lastWasNull = false;

        Handler(Statement stmt, ArcheryClient.QueryResult data) {
            this.stmt = stmt;
            this.columns = data.columns;
            this.columnTypes = data.columnTypes;
            this.rows = data.rows;
            this.nameIndex = new HashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                // 大小写不敏感（MyBatis 常用小写映射）
                nameIndex.putIfAbsent(columns.get(i).toLowerCase(), i);
                nameIndex.putIfAbsent(columns.get(i), i);
            }
            this.metaData = ArcheryResultSetMetaData.create(columns, columnTypes);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "toString": return "ArcheryResultSet(cols=" + columns.size() + ", rows=" + rows.size() + ")";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals":   return proxy == args[0];

                case "next":     return doNext();
                case "close":    closed = true; return null;
                case "isClosed": return closed;
                case "wasNull":  return lastWasNull;
                case "getRow":   return cursor;
                case "getStatement":  return stmt;
                case "getMetaData":   return metaData;
                case "getFetchSize":  return rows.size();
                case "setFetchSize":  return null;
                case "getFetchDirection": return ResultSet.FETCH_FORWARD;
                case "setFetchDirection": return null;
                case "getType":       return ResultSet.TYPE_FORWARD_ONLY;
                case "getConcurrency": return ResultSet.CONCUR_READ_ONLY;
                case "getHoldability": return ResultSet.CLOSE_CURSORS_AT_COMMIT;
                case "isBeforeFirst": return cursor == 0 && !rows.isEmpty();
                case "isAfterLast":   return cursor > rows.size();
                case "isFirst":       return cursor == 1;
                case "isLast":        return cursor == rows.size() && !rows.isEmpty();
                case "clearWarnings": return null;
                case "getWarnings":   return null;
                case "findColumn":    return resolveIndex((String) args[0]) + 1;

                case "unwrap":        return proxy;
                case "isWrapperFor":  return false;
            }

            // getXxx(int) / getXxx(String)
            if (name.startsWith("get") && args != null && args.length >= 1) {
                Object raw = rawValue(args[0]);
                lastWasNull = raw == null;
                return convert(raw, method.getReturnType(), name, args);
            }

            // update* / insert* / delete* / refresh 等更新类方法
            if (name.startsWith("update") || name.equals("insertRow") || name.equals("deleteRow")
                    || name.equals("refreshRow") || name.equals("moveToInsertRow")
                    || name.equals("moveToCurrentRow") || name.equals("cancelRowUpdates")) {
                throw new SQLFeatureNotSupportedException("Archery 结果集只读");
            }

            throw new SQLFeatureNotSupportedException("ArcheryResultSet 不支持: " + name);
        }

        private boolean doNext() {
            if (cursor < rows.size()) {
                cursor++;
                return true;
            }
            cursor = rows.size() + 1;
            return false;
        }

        private Object rawValue(Object indexOrLabel) throws SQLException {
            if (cursor < 1 || cursor > rows.size()) {
                throw new SQLException("游标不在有效行");
            }
            int idx;
            if (indexOrLabel instanceof Number n) {
                idx = n.intValue() - 1;
            } else {
                idx = resolveIndex(indexOrLabel.toString());
            }
            if (idx < 0 || idx >= columns.size()) {
                throw new SQLException("列索引越界: " + indexOrLabel);
            }
            List<Object> row = rows.get(cursor - 1);
            return idx < row.size() ? row.get(idx) : null;
        }

        private int resolveIndex(String label) throws SQLException {
            Integer i = nameIndex.get(label);
            if (i == null) i = nameIndex.get(label.toLowerCase());
            if (i == null) throw new SQLException("未知列: " + label);
            return i;
        }

        private Object convert(Object raw, Class<?> targetType, String methodName, Object[] args) throws SQLException {
            if (raw == null) {
                if (targetType.isPrimitive()) {
                    if (targetType == boolean.class) return false;
                    if (targetType == char.class)    return '\0';
                    return 0;
                }
                return null;
            }

            // getObject(i, Class<T>) / getObject(label, Class<T>)
            if (methodName.equals("getObject") && args.length >= 2 && args[1] instanceof Class<?> c) {
                return convertTo(raw, c);
            }
            if (methodName.equals("getObject")) return raw;
            if (methodName.equals("getString"))  return raw.toString();
            if (methodName.equals("getBoolean")) return toBoolean(raw);
            if (methodName.equals("getByte"))    return (byte) toLong(raw);
            if (methodName.equals("getShort"))   return (short) toLong(raw);
            if (methodName.equals("getInt"))     return (int) toLong(raw);
            if (methodName.equals("getLong"))    return toLong(raw);
            if (methodName.equals("getFloat"))   return (float) toDouble(raw);
            if (methodName.equals("getDouble"))  return toDouble(raw);
            if (methodName.equals("getBigDecimal")) return toBigDecimal(raw);
            if (methodName.equals("getBytes"))   return raw.toString().getBytes();
            if (methodName.equals("getDate"))    return toSqlDate(raw);
            if (methodName.equals("getTime"))    return toSqlTime(raw);
            if (methodName.equals("getTimestamp")) return toTimestamp(raw);
            if (methodName.equals("getNString"))  return raw.toString();
            if (methodName.equals("getCharacterStream") || methodName.equals("getNCharacterStream")) {
                return new java.io.StringReader(raw.toString());
            }
            if (methodName.equals("getAsciiStream") || methodName.equals("getBinaryStream")
                    || methodName.equals("getUnicodeStream")) {
                return new java.io.ByteArrayInputStream(raw.toString().getBytes());
            }
            return raw;
        }

        @SuppressWarnings("unchecked")
        private static <T> T convertTo(Object raw, Class<T> c) {
            if (c.isInstance(raw)) return (T) raw;
            if (c == String.class)  return (T) raw.toString();
            if (c == Long.class)    return (T) Long.valueOf(toLong(raw));
            if (c == Integer.class) return (T) Integer.valueOf((int) toLong(raw));
            if (c == Short.class)   return (T) Short.valueOf((short) toLong(raw));
            if (c == Byte.class)    return (T) Byte.valueOf((byte) toLong(raw));
            if (c == Double.class)  return (T) Double.valueOf(toDouble(raw));
            if (c == Float.class)   return (T) Float.valueOf((float) toDouble(raw));
            if (c == Boolean.class) return (T) toBoolean(raw);
            if (c == BigDecimal.class) return (T) toBigDecimal(raw);
            if (c == java.sql.Date.class)      return (T) toSqlDate(raw);
            if (c == java.sql.Time.class)      return (T) toSqlTime(raw);
            if (c == java.sql.Timestamp.class) return (T) toTimestamp(raw);
            if (c == LocalDate.class)     return (T) LocalDate.parse(raw.toString().substring(0, 10));
            if (c == LocalDateTime.class) return (T) toLocalDateTime(raw);
            return (T) raw;
        }

        private static long toLong(Object raw) {
            if (raw instanceof Number n) return n.longValue();
            String s = raw.toString().trim();
            if (s.isEmpty()) return 0;
            if (s.equalsIgnoreCase("true"))  return 1;
            if (s.equalsIgnoreCase("false")) return 0;
            return (long) Double.parseDouble(s);
        }

        private static double toDouble(Object raw) {
            if (raw instanceof Number n) return n.doubleValue();
            String s = raw.toString().trim();
            return s.isEmpty() ? 0 : Double.parseDouble(s);
        }

        private static Boolean toBoolean(Object raw) {
            if (raw instanceof Boolean b) return b;
            if (raw instanceof Number n)  return n.intValue() != 0;
            String s = raw.toString().trim();
            return s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("y");
        }

        private static BigDecimal toBigDecimal(Object raw) {
            if (raw instanceof BigDecimal b) return b;
            if (raw instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
            return new BigDecimal(raw.toString().trim());
        }

        private static final DateTimeFormatter[] DATETIME_FORMATS = new DateTimeFormatter[]{
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
        };

        private static LocalDateTime toLocalDateTime(Object raw) {
            String s = raw.toString().trim();
            for (DateTimeFormatter f : DATETIME_FORMATS) {
                try { return LocalDateTime.parse(s, f); } catch (Exception ignore) {}
            }
            if (s.length() >= 10) {
                return LocalDate.parse(s.substring(0, 10)).atStartOfDay();
            }
            throw new IllegalArgumentException("无法解析日期时间: " + s);
        }

        private static Timestamp toTimestamp(Object raw) {
            if (raw instanceof Timestamp t) return t;
            if (raw instanceof Number n) return new Timestamp(n.longValue());
            return Timestamp.valueOf(toLocalDateTime(raw));
        }

        private static java.sql.Date toSqlDate(Object raw) {
            if (raw instanceof java.sql.Date d) return d;
            String s = raw.toString().trim();
            if (s.length() >= 10) return java.sql.Date.valueOf(s.substring(0, 10));
            throw new IllegalArgumentException("无法解析日期: " + s);
        }

        private static java.sql.Time toSqlTime(Object raw) {
            if (raw instanceof java.sql.Time t) return t;
            return java.sql.Time.valueOf(raw.toString().trim());
        }
    }

    /** ResultSetMetaData 的动态代理 */
    static class ArcheryResultSetMetaData {

        static ResultSetMetaData create(List<String> columns, List<String> columnTypes) {
            InvocationHandler h = (proxy, method, args) -> {
                String name = method.getName();
                int idx = (args != null && args.length >= 1 && args[0] instanceof Integer)
                        ? ((Integer) args[0]) - 1 : -1;
                String type = (idx >= 0 && idx < columnTypes.size()) ? columnTypes.get(idx) : "";
                switch (name) {
                    case "toString":      return "ArcheryResultSetMetaData(" + columns.size() + ")";
                    case "hashCode":      return System.identityHashCode(proxy);
                    case "equals":        return proxy == args[0];
                    case "getColumnCount": return columns.size();
                    case "isAutoIncrement": case "isCaseSensitive": case "isCurrency":
                    case "isReadOnly":     case "isWritable":       case "isDefinitelyWritable": return false;
                    case "isSigned":       return isNumericType(type);
                    case "isSearchable":   return true;
                    case "isNullable":     return ResultSetMetaData.columnNullableUnknown;
                    case "getColumnDisplaySize": return 255;
                    case "getPrecision":   return 0;
                    case "getScale":       return 0;
                    case "getCatalogName": case "getSchemaName": case "getTableName": return "";
                    case "getColumnClassName": return mapArcheryClassName(type);
                    case "getColumnType":  return mapArcheryType(type);
                    case "getColumnTypeName": return type == null || type.isEmpty() ? "VARCHAR" : type.toUpperCase();
                    case "getColumnName":  case "getColumnLabel":
                        return idx >= 0 && idx < columns.size() ? columns.get(idx) : "";
                    case "unwrap":         return proxy;
                    case "isWrapperFor":   return false;
                }
                throw new SQLFeatureNotSupportedException("ArcheryResultSetMetaData 不支持: " + name);
            };
            return (ResultSetMetaData) Proxy.newProxyInstance(
                    ArcheryResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSetMetaData.class},
                    h);
        }

        private static boolean isNumericType(String t) {
            if (t == null) return false;
            switch (t.toUpperCase()) {
                case "TINY": case "SHORT": case "INT24": case "LONG": case "LONGLONG":
                case "FLOAT": case "DOUBLE": case "DECIMAL": case "NEWDECIMAL": case "YEAR":
                    return true;
                default:
                    return false;
            }
        }
    }
}
