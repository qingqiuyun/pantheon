package world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器（自包含，不依赖 Jackson）。
 * 足以解析本项目的逻辑源码 / manifest / C 的 tick 响应。
 * parse(s) -> Object: Map<String,Object>(对象) | List<Object>(数组) | String | Double | Boolean | null
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; this.i = 0; }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.skipWs();
        Object v = j.parseValue();
        j.skipWs();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object o) {
        return (List<Object>) o;
    }

    public static double asDouble(Object o) {
        if (o instanceof Double) return (Double) o;
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) return Double.parseDouble((String) o);
        throw new RuntimeException("不是数字: " + o);
    }

    public static String asString(Object o) {
        return (String) o;
    }

    private void skipWs() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private Object parseValue() {
        skipWs();
        char c = s.charAt(i);
        if (c == '{') return parseObject();
        if (c == '[') return parseArray();
        if (c == '"') return parseString();
        if (c == 't' || c == 'f') return parseBool();
        if (c == 'n') { i += 4; return null; }
        return parseNumber();
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        skipWs();
        if (peek() == '}') { i++; return m; }
        while (true) {
            skipWs();
            String key = parseString();
            skipWs();
            i++; // :
            Object val = parseValue();
            m.put(key, val);
            skipWs();
            char c = s.charAt(i);
            if (c == ',') { i++; continue; }
            if (c == '}') { i++; break; }
            throw new RuntimeException("JSON 对象格式错误 @ " + i);
        }
        return m;
    }

    private List<Object> parseArray() {
        List<Object> a = new ArrayList<>();
        i++; // [
        skipWs();
        if (peek() == ']') { i++; return a; }
        while (true) {
            Object val = parseValue();
            a.add(val);
            skipWs();
            char c = s.charAt(i);
            if (c == ',') { i++; continue; }
            if (c == ']') { i++; break; }
            throw new RuntimeException("JSON 数组格式错误 @ " + i);
        }
        return a;
    }

    private String parseString() {
        i++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        String hex = s.substring(i, i + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                        break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Object parseNumber() {
        int start = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                    || c == '+' || c == '-') i++;
            else break;
        }
        return Double.parseDouble(s.substring(start, i));
    }

    private Boolean parseBool() {
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        i += 5;
        return Boolean.FALSE;
    }

    private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }
}
