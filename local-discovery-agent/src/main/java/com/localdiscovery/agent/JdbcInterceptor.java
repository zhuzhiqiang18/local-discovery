package com.localdiscovery.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * 拦截 JDBC 查询相关方法
 *
 * 三个入口：
 *   1. Connection.prepareStatement(sql, ...) → 记录 SQL
 *   2. PreparedStatement.setXxx(i, v) → 记录参数
 *   3. Statement.executeQuery(...) → 判断是否转发到 Archery
 *
 * Advice 类只依赖 JDK 类型 + SqlBridge 静态方法，避免 ClassLoader 隔离问题
 */
public class JdbcInterceptor {

    /**
     * Connection.prepareStatement(String sql[, ...]) 出口：记录 SQL 到桥接器
     */
    public static class PrepareStatementAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.Argument(0) String sql,
                                  @Advice.Return java.sql.PreparedStatement stmt) {
            if (sql != null && stmt != null && ArcheryConfig.isEnabled()) {
                SqlBridge.recordSql(stmt, sql);
            }
        }
    }

    /**
     * PreparedStatement.setXxx(int, Object) 入口：记录参数到桥接器
     * 只处理签名为 (int, ?) 的 setter，主流驱动的 setInt/setLong/setString/... 都符合
     */
    public static class SetParamAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(@Advice.This java.sql.PreparedStatement stmt,
                                   @Advice.Argument(0) int index,
                                   @Advice.Argument(value = 1, typing = Assigner.Typing.DYNAMIC) Object value) {
            if (ArcheryConfig.isEnabled()) {
                SqlBridge.recordParam(stmt, index, value);
            }
        }
    }

    /**
     * PreparedStatement.executeQuery() 无参版本
     * SQL 已在 prepareStatement 时记录到桥接器
     */
    public static class ExecuteQueryPreparedAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean onEnter(@Advice.This java.sql.Statement stmt) {
            return SqlBridge.shouldForward(stmt, null);
        }

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(
                @Advice.This java.sql.Statement stmt,
                @Advice.Enter boolean shouldForward,
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) java.sql.ResultSet result) {
            if (shouldForward) {
                java.sql.ResultSet forwarded = SqlBridge.forward(stmt, null);
                if (forwarded != null) {
                    result = forwarded;
                }
            }
        }
    }

    /**
     * Statement.executeQuery(String sql) 版本
     * SQL 直接从参数拿
     */
    public static class ExecuteQueryStatementAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean onEnter(@Advice.This java.sql.Statement stmt,
                                      @Advice.Argument(0) String sql) {
            return SqlBridge.shouldForward(stmt, sql);
        }

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(
                @Advice.This java.sql.Statement stmt,
                @Advice.Argument(0) String sql,
                @Advice.Enter boolean shouldForward,
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) java.sql.ResultSet result) {
            if (shouldForward) {
                java.sql.ResultSet forwarded = SqlBridge.forward(stmt, sql);
                if (forwarded != null) {
                    result = forwarded;
                }
            }
        }
    }

    /**
     * PreparedStatement.execute() —— MyBatis / Hibernate 走的路径
     * execute() 返回 boolean（true 表示"有 ResultSet"）
     * 命中后：跳过原方法，把 Archery ResultSet 暂存，返回 true；后续 getResultSet() 取出
     */
    public static class ExecutePreparedAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean onEnter(@Advice.This java.sql.Statement stmt) {
            if (!SqlBridge.shouldForward(stmt, null)) return false;
            java.sql.ResultSet rs = SqlBridge.forward(stmt, null);
            if (rs == null) return false;
            SqlBridge.stashResultSet(stmt, rs);
            return true; // skipOn=OnNonDefaultValue → 跳过原方法
        }

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(
                @Advice.Enter boolean forwarded,
                @Advice.Return(readOnly = false) boolean result) {
            if (forwarded) {
                result = true; // 告诉 MyBatis：有 ResultSet，来 getResultSet() 取
            }
        }
    }

    /**
     * Statement.execute(String sql) —— 无占位符版本
     */
    public static class ExecuteStatementAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean onEnter(@Advice.This java.sql.Statement stmt,
                                      @Advice.Argument(0) String sql) {
            if (!SqlBridge.shouldForward(stmt, sql)) return false;
            java.sql.ResultSet rs = SqlBridge.forward(stmt, sql);
            if (rs == null) return false;
            SqlBridge.stashResultSet(stmt, rs);
            return true;
        }

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(
                @Advice.Enter boolean forwarded,
                @Advice.Return(readOnly = false) boolean result) {
            if (forwarded) {
                result = true;
            }
        }
    }

    /**
     * Statement.getResultSet() —— execute() 后取结果的入口
     * 若有暂存的 Archery ResultSet，直接跳过原方法返回它
     */
    public static class GetResultSetAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static java.sql.ResultSet onEnter(@Advice.This java.sql.Statement stmt) {
            return SqlBridge.consumePendingResultSet(stmt);
        }

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(
                @Advice.Enter java.sql.ResultSet pending,
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) java.sql.ResultSet result) {
            if (pending != null) {
                result = pending;
            }
        }
    }
}
