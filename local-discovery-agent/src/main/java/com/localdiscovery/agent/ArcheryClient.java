package com.localdiscovery.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Archery 查询客户端
 *
 * POST {baseUrl}/query/  (application/x-www-form-urlencoded)
 * body: instance_name=xxx&db_name=xxx&sql_content=xxx&limit_num=1000
 *
 * 响应形如：
 *   {"status":0, "msg":"ok", "data":{
 *      "column_list":["id","name"],
 *      "column_type":["LONG","VAR_STRING"],
 *      "rows":[[1,"a"],[2,"b"]]}}
 * status != 0 表示错误，msg 是错误消息。
 *
 * 纯 JDK HttpURLConnection 实现，与 RegistryClient 保持同风格。
 */
public class ArcheryClient {

    /** 查询结果 */
    public static class QueryResult {
        public final List<String> columns;
        public final List<String> columnTypes;
        public final List<List<Object>> rows;
        public final String error;

        public QueryResult(List<String> columns, List<String> columnTypes, List<List<Object>> rows) {
            this.columns = columns;
            this.columnTypes = columnTypes;
            this.rows = rows;
            this.error = null;
        }

        public QueryResult(String error) {
            this.columns = List.of();
            this.columnTypes = List.of();
            this.rows = List.of();
            this.error = error;
        }

        public boolean isError() { return error != null; }
    }

    public static QueryResult query(String sql) {
        if (!ArcheryConfig.isEnabled()) {
            return new QueryResult("Archery 未启用");
        }
        return doQuery(ArcheryConfig.getBaseUrl(), ArcheryConfig.getInstance(),
                ArcheryConfig.getDatabase(), sql, ArcheryConfig.getRowLimit());
    }

    /**
     * 把当前请求转成可复现的 curl 命令，便于人工排查
     * 敏感 Cookie 完整打印（本地开发工具，非生产环境）
     */
    private static String buildCurl(String baseUrl, Map<String, String> headers,
                                    String csrf, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("curl -X POST '").append(baseUrl).append("/query/'");
        sb.append(" \\\n  -H 'Content-Type: application/x-www-form-urlencoded; charset=UTF-8'");
        sb.append(" \\\n  -H 'Accept: application/json, text/javascript, */*; q=0.01'");
        sb.append(" \\\n  -H 'X-Requested-With: XMLHttpRequest'");
        sb.append(" \\\n  -H 'Origin: ").append(baseUrl).append("'");
        sb.append(" \\\n  -H 'Referer: ").append(baseUrl).append("/sqlquery/'");
        if (csrf != null && !csrf.isEmpty()) {
            sb.append(" \\\n  -H 'X-CSRFToken: ").append(csrf).append("'");
        }
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if ("cookie".equalsIgnoreCase(h.getKey())) {
                sb.append(" \\\n  -b '").append(h.getValue()).append("'");
            } else {
                sb.append(" \\\n  -H '").append(h.getKey()).append(": ").append(h.getValue()).append("'");
            }
        }
        sb.append(" \\\n  --data-raw '").append(body).append("'");
        return sb.toString();
    }

    private static QueryResult doQuery(String baseUrl, String instance, String db, String sql, int limit) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(baseUrl + "/query/").toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(false);  // 别跟随重定向，Archery 未登录会 302 到 /login/
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);
            // Archery 是 Django，接的是表单，不是 JSON
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01");
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            // 明确关掉 gzip：APISIX 会强制 gzip 输出，HttpURLConnection 在我们手动设过 header 后不会自动解压
            conn.setRequestProperty("Accept-Encoding", "identity");
            // HTTPS 下 Django CSRF middleware 强制校验 Referer
            conn.setRequestProperty("Referer", baseUrl + "/sqlquery/");
            conn.setRequestProperty("Origin", baseUrl);

            // 应用用户配置的 headers（Cookie 等）
            String cookie = null;
            for (Map.Entry<String, String> h : ArcheryConfig.getHeaders().entrySet()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
                if ("cookie".equalsIgnoreCase(h.getKey())) cookie = h.getValue();
            }
            // 从 Cookie 里提取 csrftoken 注入 X-CSRFToken，保证与 cookie 一致
            String csrf = extractCookieValue(cookie, "csrftoken");
            if (csrf != null && !csrf.isEmpty()) {
                conn.setRequestProperty("X-CSRFToken", csrf);
            }

            String body = "instance_name=" + enc(instance)
                    + "&db_name=" + enc(db)
                    + "&schema_name="
                    + "&tb_name="
                    + "&sql_content=" + enc(sql)
                    + "&limit_num=" + limit;

            // 完整 curl 复现命令，直接贴 shell 就能跑
            System.out.println("[LocalDiscovery] Archery curl:\n"
                    + buildCurl(baseUrl, ArcheryConfig.getHeaders(), csrf, body));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String response = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("[LocalDiscovery] Archery 响应 HTTP " + code
                    + " len=" + response.length()
                    + " body=" + trim(response, 500));
            if (code < 200 || code >= 300) {
                String loc = conn.getHeaderField("Location");
                return new QueryResult("HTTP " + code
                        + (loc != null ? " -> " + loc : "")
                        + ": " + trim(response, 500));
            }
            if (response.isEmpty()) {
                return new QueryResult("HTTP " + code + " 响应空（Cookie/CSRF 校验失败？确认 sessionid 未过期，csrftoken 与 Cookie 一致）");
            }
            return parse(response);
        } catch (IOException e) {
            return new QueryResult("请求异常: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** 从 Cookie 头字符串里提取指定名字的值：如 "a=1; b=2; csrftoken=xxx" -> "xxx" */
    static String extractCookieValue(String cookieHeader, String name) {
        if (cookieHeader == null || cookieHeader.isBlank()) return null;
        for (String part : cookieHeader.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).equals(name)) {
                return p.substring(eq + 1);
            }
        }
        return null;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    @SuppressWarnings("unchecked")
    private static QueryResult parse(String response) {
        try {
            Object root = ArcheryJson.parse(response);
            Map<String, Object> obj = ArcheryJson.asObject(root);
            if (obj == null) return new QueryResult("响应不是 JSON 对象");

            // Archery: status=0 成功；非 0 或缺失则从 msg/error 取错误
            Object status = obj.getOrDefault("status", obj.get("code"));
            if (status != null && !"0".equals(status.toString()) && !"200".equals(status.toString())) {
                Object msg = obj.getOrDefault("msg", obj.getOrDefault("message", obj.get("error")));
                return new QueryResult("Archery: " + (msg == null ? response : msg));
            }

            Object data = obj.get("data");
            Map<String, Object> d = ArcheryJson.asObject(data);
            if (d == null) return new QueryResult("响应缺少 data");

            // data.error 非空也是错误（Archery 会把 SQL 语法错等挂到这里）
            Object dataError = d.get("error");
            if (dataError != null && !dataError.toString().isBlank()) {
                return new QueryResult("Archery: " + dataError);
            }

            List<Object> colsRaw = ArcheryJson.asArray(d.get("column_list"));
            List<Object> typesRaw = ArcheryJson.asArray(d.get("column_type"));
            List<Object> rowsRaw = ArcheryJson.asArray(d.get("rows"));
            if (rowsRaw == null) rowsRaw = ArcheryJson.asArray(d.get("list"));  // 兼容
            if (colsRaw == null || rowsRaw == null) {
                return new QueryResult("响应缺少 column_list 或 rows");
            }
            List<String> columns = new ArrayList<>(colsRaw.size());
            for (Object c : colsRaw) columns.add(c == null ? "" : c.toString());

            List<String> columnTypes = new ArrayList<>(columns.size());
            if (typesRaw != null) {
                for (Object t : typesRaw) columnTypes.add(t == null ? "" : t.toString());
            }
            while (columnTypes.size() < columns.size()) columnTypes.add("");

            List<List<Object>> rows = new ArrayList<>(rowsRaw.size());
            for (Object r : rowsRaw) {
                if (r instanceof List) rows.add((List<Object>) r);
            }

            // Archery 拿的是 MySQL FIELD_TYPE 名，字符集信息丢失：
            // longtext/mediumtext/text 会被误标为 BLOB。用实际返回值反推。
            reviseTypesByValue(columnTypes, rows);

            return new QueryResult(columns, columnTypes, rows);
        } catch (Exception e) {
            return new QueryResult("解析响应失败: " + e.getMessage());
        }
    }

    /**
     * 修正 BLOB 类字段：若首个非 null 值是 String，说明实际是 text/longtext，改为 LONGVARCHAR
     * MySQL 二进制协议里 text 系列的 field type 与 blob 相同，Archery 无法区分
     */
    private static void reviseTypesByValue(List<String> columnTypes, List<List<Object>> rows) {
        for (int col = 0; col < columnTypes.size(); col++) {
            String t = columnTypes.get(col);
            if (t == null) continue;
            String tu = t.toUpperCase();
            boolean isBlobLike = tu.equals("BLOB") || tu.equals("TINY_BLOB")
                    || tu.equals("MEDIUM_BLOB") || tu.equals("LONG_BLOB");
            if (!isBlobLike) continue;
            for (List<Object> row : rows) {
                if (col >= row.size()) continue;
                Object v = row.get(col);
                if (v == null) continue;
                if (v instanceof String) {
                    // 实际是文本类字段
                    columnTypes.set(col, "LONGTEXT");
                }
                break;  // 首个非 null 定型即止
            }
        }
    }
}
