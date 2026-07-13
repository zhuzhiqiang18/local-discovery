package com.localdiscovery.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器
 * 只支持解析 Archery 查询响应必要的 Object/Array/String/Number/Boolean/null 结构
 * 无外部依赖
 */
class ArcheryJson {

    private final String src;
    private int pos;

    private ArcheryJson(String src) {
        this.src = src;
    }

    static Object parse(String json) {
        ArcheryJson p = new ArcheryJson(json);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asArray(Object v) {
        return v instanceof List ? (List<Object>) v : null;
    }

    private Object readValue() {
        skipWs();
        char c = peek();
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't', 'f' -> readBool();
            case 'n' -> readNull();
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> obj = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') { pos++; return obj; }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            expect(':');
            Object val = readValue();
            obj.put(key, val);
            skipWs();
            char c = src.charAt(pos++);
            if (c == '}') return obj;
            if (c != ',') throw new RuntimeException("期望 ',' 或 '}' 位置 " + pos);
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> arr = new ArrayList<>();
        skipWs();
        if (peek() == ']') { pos++; return arr; }
        while (true) {
            arr.add(readValue());
            skipWs();
            char c = src.charAt(pos++);
            if (c == ']') return arr;
            if (c != ',') throw new RuntimeException("期望 ',' 或 ']' 位置 " + pos);
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        String hex = src.substring(pos, pos + 4);
                        pos += 4;
                        sb.append((char) Integer.parseInt(hex, 16));
                    }
                    default -> sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw new RuntimeException("字符串未闭合");
    }

    private Boolean readBool() {
        if (src.startsWith("true", pos))  { pos += 4; return Boolean.TRUE; }
        if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
        throw new RuntimeException("非法 bool 位置 " + pos);
    }

    private Object readNull() {
        if (src.startsWith("null", pos)) { pos += 4; return null; }
        throw new RuntimeException("非法 null 位置 " + pos);
    }

    private Object readNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        while (pos < src.length() && "0123456789.eE+-".indexOf(src.charAt(pos)) >= 0) pos++;
        String num = src.substring(start, pos);
        if (num.contains(".") || num.contains("e") || num.contains("E")) {
            return Double.parseDouble(num);
        }
        try {
            return Long.parseLong(num);
        } catch (NumberFormatException e) {
            return Double.parseDouble(num);
        }
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw new RuntimeException("期望 '" + c + "' 位置 " + pos);
        }
        pos++;
    }

    private char peek() {
        return src.charAt(pos);
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }
}
