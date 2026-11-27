package com.famspace.unlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JSON tree that keeps everything exactly as it was written:
 *
 *  * the order of the keys,
 *  * the raw text of every number,
 *  * the raw text of every string that is not changed.
 *
 * The app parses its answers field by field, so a rewritten answer has to look
 * exactly like the original one apart from the values that were changed.
 * A plain JSONObject would reorder the keys and reformat the numbers, so this
 * class is used for the encrypted answers.
 *
 * The nodes are:
 *   Map&lt;String, Json&gt;   an object
 *   List&lt;Json&gt;          an array
 *   String                 a string (the decoded value)
 *   Long / Double          a number (the decoded value)
 *   Boolean                true / false
 *   null                   null
 */
public final class Json {

    /** the raw text of the node, as it was in the source */
    private String raw;
    /** the decoded value */
    private Object value;

    private Json(String raw, Object value) {
        this.raw = raw;
        this.value = value;
    }

    public static Json parse(String text) {
        P p = new P(text);
        p.ws();
        Json node = p.value();
        p.ws();
        return node;
    }

    /** the raw text of this node */
    public String raw() {
        return raw;
    }

    /** the decoded value: Map, List, String, Long, Double, Boolean or null */
    public Object value() {
        return value;
    }

    /** the string value of a node, or null */
    public String asString() {
        return value instanceof String ? (String) value : null;
    }

    /** the number value of a node, or null */
    public Number asNumber() {
        return value instanceof Number ? (Number) value : null;
    }

    public boolean isObject() {
        return value instanceof Map;
    }

    public boolean isArray() {
        return value instanceof List;
    }

    /** the members of an object node, in the order of the source */
    @SuppressWarnings("unchecked")
    public Map<String, Json> members() {
        return value instanceof Map ? (Map<String, Json>) value : null;
    }

    /** the elements of an array node */
    @SuppressWarnings("unchecked")
    public List<Json> items() {
        return value instanceof List ? (List<Json>) value : null;
    }

    /** replaces the raw text (and the decoded value) of a leaf node */
    public void set(String raw, Object value) {
        this.raw = raw;
        this.value = value;
    }

    /** serialises the tree; unchanged nodes keep their original text */
    public String write() {
        StringBuilder sb = new StringBuilder();
        write(sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void write(StringBuilder sb) {
        if (value instanceof Map) {
            Map<String, Json> m = (Map<String, Json>) value;
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Json> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(e.getKey())).append(':');
                e.getValue().write(sb);
            }
            sb.append('}');
            return;
        }
        if (value instanceof List) {
            List<Json> l = (List<Json>) value;
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                l.get(i).write(sb);
            }
            sb.append(']');
            return;
        }
        sb.append(raw);
    }

    /** quotes and escapes a string the way JSON wants it */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ------------------------------------------------------------- the parser

    private static final class P {
        private final String s;
        private int i;

        P(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        Json value() {
            ws();
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            char c = s.charAt(i);
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            if (c == '"') {
                return string();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return new Json("true", Boolean.TRUE);
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return new Json("false", Boolean.FALSE);
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return new Json("null", null);
            }
            return number();
        }

        Json object() {
            int start = i;
            i++;                                  // {
            Map<String, Json> m = new LinkedHashMap<String, Json>();
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return new Json(s.substring(start, i), m);
            }
            while (true) {
                ws();
                Json key = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') {
                    throw new IllegalArgumentException("expected ':' at " + i);
                }
                i++;
                Json v = value();
                m.put(key.asString(), v);
                ws();
                if (i >= s.length()) {
                    throw new IllegalArgumentException("unexpected end of object");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == '}') {
                    i++;
                    break;
                }
                throw new IllegalArgumentException("expected ',' or '}' at " + i);
            }
            return new Json(s.substring(start, i), m);
        }

        Json array() {
            int start = i;
            i++;                                  // [
            List<Json> l = new ArrayList<Json>();
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return new Json(s.substring(start, i), l);
            }
            while (true) {
                l.add(value());
                ws();
                if (i >= s.length()) {
                    throw new IllegalArgumentException("unexpected end of array");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == ']') {
                    i++;
                    break;
                }
                throw new IllegalArgumentException("expected ',' or ']' at " + i);
            }
            return new Json(s.substring(start, i), l);
        }

        Json string() {
            int start = i;
            if (i >= s.length() || s.charAt(i) != '"') {
                throw new IllegalArgumentException("expected a string at " + i);
            }
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    break;
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        sb.append(e);
                }
            }
            return new Json(s.substring(start, i), sb.toString());
        }

        Json number() {
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
                i++;
            }
            boolean decimal = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    decimal = decimal || c == '.' || c == 'e' || c == 'E';
                    i++;
                } else {
                    break;
                }
            }
            String text = s.substring(start, i);
            if (text.isEmpty()) {
                throw new IllegalArgumentException("expected a number at " + start);
            }
            Object v;
            if (decimal) {
                v = Double.valueOf(text);
            } else {
                try {
                    v = Long.valueOf(text);
                } catch (NumberFormatException e) {
                    v = Double.valueOf(text);
                }
            }
            return new Json(text, v);
        }
    }
}
