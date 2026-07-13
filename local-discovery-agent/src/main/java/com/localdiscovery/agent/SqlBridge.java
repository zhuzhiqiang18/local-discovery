package com.localdiscovery.agent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * SQL 拦截桥接器
 *
 * 只提供 JDK 类型的静态方法，供 ByteBuddy Advice 内联调用，
 * 避免 Advice 内直接引用 Agent 内部类（inline 后类加载问题）。
 *
 * 职责：
 *   1. 记录 Connection.prepareStatement(sql) 传入的 SQL
 *   2. 记录 PreparedStatement.setXxx(i, v) 的参数
 *   3. executeQuery 时判断是否转发、拼接最终 SQL、调用 ArcheryClient、返回代理 ResultSet
 *   4. ThreadLocal 重入保护，防止包装类嵌套触发
 */
public class SqlBridge {

    /** SQL 白名单：以下语句本地伪造返回，不发 Archery，避免连接池探活刷屏 */
    private static final Pattern LOCAL_ECHO = Pattern.compile(
            "^\\s*(select\\s+1|select\\s+'?x'?|/\\*\\s*ping\\s*\\*/.*|select\\s+@@\\w+|show\\s+.*)\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** 只处理 SELECT / SHOW / DESC / EXPLAIN */
    private static final Pattern READ_ONLY = Pattern.compile(
            "^\\s*(select|show|desc|describe|explain|with)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * MySQL 保留字兜底表：业务代码里裸写、部分 MySQL 版本会报语法错。
     * 转发前统一给这些标识符套反引号（跳过字符串/注释/已加反引号的位置）。
     * 全部小写比较，忽略大小写。
     */
    private static final Set<String> RESERVED_IDENTIFIERS = new HashSet<>(java.util.Arrays.asList(
            "cover", "rank", "system", "grouping", "recursive", "over", "window",
            "lead", "lag", "cume_dist", "dense_rank", "percent_rank", "row_number",
            "range", "rows", "groups", "unbounded"
    ));

    /** statement -> 原始 SQL */
    private static final Map<Statement, String> SQL_BY_STMT =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** statement -> 参数下标(1-based) -> 值 */
    private static final Map<Statement, TreeMap<Integer, Object>> PARAMS_BY_STMT =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** statement -> 已转发但还没被 getResultSet() 取走的 ResultSet（MyBatis 走 execute+getResultSet 的路径用） */
    private static final Map<Statement, ResultSet> PENDING_RS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final ThreadLocal<Boolean> IN_FORWARD = ThreadLocal.withInitial(() -> false);

    // ================== 记录钩子（供 Advice 调用） ==================

    public static void recordSql(Statement stmt, String sql) {
        if (stmt == null || sql == null) return;
        SQL_BY_STMT.put(stmt, sql);
    }

    public static void recordParam(PreparedStatement stmt, int index, Object value) {
        if (stmt == null) return;
        PARAMS_BY_STMT.computeIfAbsent(stmt, k -> new TreeMap<>()).put(index, value);
    }

    public static void clear(Statement stmt) {
        if (stmt == null) return;
        SQL_BY_STMT.remove(stmt);
        PARAMS_BY_STMT.remove(stmt);
        PENDING_RS.remove(stmt);
    }

    /** execute() 转发成功后，把 ResultSet 暂存，等 getResultSet() 来取 */
    public static void stashResultSet(Statement stmt, ResultSet rs) {
        if (stmt == null || rs == null) return;
        PENDING_RS.put(stmt, rs);
    }

    /** getResultSet() 消费暂存的 ResultSet；返回 null 表示当前 stmt 没有转发结果，走原方法 */
    public static ResultSet consumePendingResultSet(Statement stmt) {
        if (stmt == null) return null;
        return PENDING_RS.remove(stmt);
    }

    // ================== 决策 & 执行 ==================

    /**
     * 是否应该拦截这次 executeQuery
     *
     * 条件：
     *   - Archery 启用
     *   - 不在本次转发嵌套内
     *   - SQL 非空且属于只读语义
     *   - 非探活白名单
     */
    public static boolean shouldForward(Statement stmt, String rawSqlOrNull) {
        if (!ArcheryConfig.isEnabled()) return false;
        if (Boolean.TRUE.equals(IN_FORWARD.get())) return false;
        String sql = rawSqlOrNull != null ? rawSqlOrNull : SQL_BY_STMT.get(stmt);
        if (sql == null || sql.isBlank()) return false;
        if (LOCAL_ECHO.matcher(sql).matches()) return false;
        if (!READ_ONLY.matcher(sql).find()) return false;
        return true;
    }

    /**
     * 执行 Archery 查询并返回代理 ResultSet
     * 调用前必须已通过 shouldForward 判定
     * 失败时返回 null，让上层 Advice 放行原方法
     */
    public static ResultSet forward(Statement stmt, String rawSqlOrNull) {
        IN_FORWARD.set(Boolean.TRUE);
        try {
            String rawSql = rawSqlOrNull != null ? rawSqlOrNull : SQL_BY_STMT.get(stmt);
            if (rawSql == null) return null;

            String finalSql = rawSql;
            Map<Integer, Object> params = PARAMS_BY_STMT.get(stmt);
            if (params != null && !params.isEmpty()) {
                finalSql = fillParams(rawSql, params);
            }
            finalSql = quoteReservedIdentifiers(finalSql);
            System.out.println("[LocalDiscovery] Archery 转发: " + trim(finalSql, 200));

            ArcheryClient.QueryResult r = ArcheryClient.query(finalSql);
            if (r.isError()) {
                System.err.println("[LocalDiscovery] Archery 查询失败: " + r.error
                        + " | sql=" + trim(finalSql, 200));
                return null;
            }
            return ArcheryResultSet.create(stmt, r);
        } catch (Throwable t) {
            System.err.println("[LocalDiscovery] Archery 转发异常: " + t.getMessage());
            return null;
        } finally {
            IN_FORWARD.set(Boolean.FALSE);
        }
    }

    /**
     * 给 SQL 里裸出现的保留字标识符套反引号
     * 跳过：字符串字面量、行/块注释、已在反引号内的标识符
     * 遇到 identifier 紧跟 '(' 视为函数调用不加反引号
     */
    static String quoteReservedIdentifiers(String sql) {
        if (sql == null || sql.isEmpty()) return sql;
        StringBuilder out = new StringBuilder(sql.length() + 16);
        int n = sql.length();
        for (int i = 0; i < n; ) {
            char c = sql.charAt(i);
            if (c == '`') {
                int end = sql.indexOf('`', i + 1);
                if (end < 0) { out.append(sql, i, n); return out.toString(); }
                out.append(sql, i, end + 1);
                i = end + 1;
                continue;
            }
            if (c == '\'' || c == '"') {
                int end = findClosingQuote(sql, i, c);
                out.append(sql, i, end + 1);
                i = end + 1;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int nl = sql.indexOf('\n', i);
                if (nl < 0) { out.append(sql, i, n); return out.toString(); }
                out.append(sql, i, nl);
                i = nl;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int close = sql.indexOf("*/", i + 2);
                if (close < 0) { out.append(sql, i, n); return out.toString(); }
                out.append(sql, i, close + 2);
                i = close + 2;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
                String word = sql.substring(start, i);
                int look = i;
                while (look < n && Character.isWhitespace(sql.charAt(look))) look++;
                boolean isFunctionCall = look < n && sql.charAt(look) == '(';
                if (!isFunctionCall && RESERVED_IDENTIFIERS.contains(word.toLowerCase())) {
                    out.append('`').append(word).append('`');
                } else {
                    out.append(word);
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * 按 ? 占位符顺序填参数
     * 忽略字符串字面量和 -- / /* 注释中的 ?
     */
    static String fillParams(String sql, Map<Integer, Object> params) {
        StringBuilder out = new StringBuilder(sql.length() + params.size() * 8);
        int paramIdx = 1;
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            // 单/双引号字符串直接透传
            if (c == '\'' || c == '"') {
                int end = findClosingQuote(sql, i, c);
                out.append(sql, i, end + 1);
                i = end;
                continue;
            }
            // 行注释 --
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int nl = sql.indexOf('\n', i);
                if (nl < 0) { out.append(sql.substring(i)); return out.toString(); }
                out.append(sql, i, nl);
                i = nl - 1;
                continue;
            }
            // 块注释
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int close = sql.indexOf("*/", i + 2);
                if (close < 0) { out.append(sql.substring(i)); return out.toString(); }
                out.append(sql, i, close + 2);
                i = close + 1;
                continue;
            }
            if (c == '?') {
                Object v = params.get(paramIdx++);
                out.append(literal(v));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int findClosingQuote(String sql, int start, char quote) {
        int n = sql.length();
        for (int i = start + 1; i < n; i++) {
            char c = sql.charAt(i);
            if (c == '\\' && i + 1 < n) { i++; continue; }
            if (c == quote) {
                // SQL 里连续两个引号表示转义
                if (i + 1 < n && sql.charAt(i + 1) == quote) { i++; continue; }
                return i;
            }
        }
        return n - 1;
    }

    private static String literal(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof byte[] bytes) {
            StringBuilder sb = new StringBuilder("X'");
            for (byte b : bytes) sb.append(String.format("%02X", b & 0xFF));
            return sb.append('\'').toString();
        }
        String s;
        if (v instanceof java.sql.Timestamp || v instanceof java.util.Date
                || v instanceof java.time.temporal.Temporal) {
            s = v.toString();
        } else {
            s = v.toString();
        }
        return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
