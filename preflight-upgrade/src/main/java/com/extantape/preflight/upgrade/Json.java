package com.extantape.preflight.upgrade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer, so the tool needs no libraries. Objects read as {@code LinkedHashMap},
 * arrays as {@code ArrayList}, whole numbers as {@code Long}, other numbers as {@code Double}.
 */
final class Json {
    private Json() {}

    static final class ParseException extends Exception {
        ParseException(String message) {
            super(message);
        }
    }

    // ---- reading

    static Object parse(String text) throws ParseException {
        Reader reader = new Reader(text);
        reader.skipSpace();
        Object value = reader.value(0);
        reader.skipSpace();
        if (reader.pos != text.length()) {
            throw reader.error("unexpected text after the end");
        }
        return value;
    }

    private static final class Reader {
        private static final int MAX_DEPTH = 64;
        private final String text;
        private int pos;

        Reader(String text) {
            this.text = text;
        }

        ParseException error(String what) {
            int line = 1;
            int column = 1;
            for (int i = 0; i < Math.min(pos, text.length()); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
            }
            return new ParseException(what + " at line " + line + ", column " + column);
        }

        void skipSpace() {
            while (pos < text.length() && " \t\r\n".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
        }

        Object value(int depth) throws ParseException {
            if (depth > MAX_DEPTH) {
                throw error("nested too deeply");
            }
            if (pos >= text.length()) {
                throw error("unexpected end");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw error("unexpected '" + c + "'");
                }
            };
        }

        private Object literal(String word, Object value) throws ParseException {
            if (!text.startsWith(word, pos)) {
                throw error("expected " + word);
            }
            pos += word.length();
            return value;
        }

        private Map<String, Object> object(int depth) throws ParseException {
            Map<String, Object> out = new LinkedHashMap<>();
            pos++;
            skipSpace();
            if (peek() == '}') {
                pos++;
                return out;
            }
            while (true) {
                skipSpace();
                if (peek() != '"') {
                    throw error("expected a name in quotes");
                }
                String key = string();
                skipSpace();
                expect(':');
                skipSpace();
                if (out.containsKey(key)) {
                    throw error("\"" + key + "\" appears twice");
                }
                out.put(key, value(depth + 1));
                skipSpace();
                char next = peek();
                pos++;
                if (next == '}') {
                    return out;
                }
                if (next != ',') {
                    pos--;
                    throw error("expected , or }");
                }
            }
        }

        private List<Object> array(int depth) throws ParseException {
            List<Object> out = new ArrayList<>();
            pos++;
            skipSpace();
            if (peek() == ']') {
                pos++;
                return out;
            }
            while (true) {
                skipSpace();
                out.add(value(depth + 1));
                skipSpace();
                char next = peek();
                pos++;
                if (next == ']') {
                    return out;
                }
                if (next != ',') {
                    pos--;
                    throw error("expected , or ]");
                }
            }
        }

        private String string() throws ParseException {
            pos++;
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    pos--;
                    throw error("control character in a string");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= text.length()) {
                    break;
                }
                char e = text.charAt(pos++);
                switch (e) {
                    case '"', '\\', '/' -> out.append(e);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw error("short \\u escape");
                        }
                        try {
                            out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("bad \\u escape");
                        }
                        pos += 4;
                    }
                    default -> {
                        pos--;
                        throw error("bad escape \\" + e);
                    }
                }
            }
            throw error("string never closed");
        }

        private Object number() throws ParseException {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < text.length() && "0123456789.eE+-".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            String digits = text.substring(start, pos);
            try {
                if (digits.matches("-?(0|[1-9][0-9]*)")) {
                    return Long.parseLong(digits);
                }
                if (digits.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
                    return Double.parseDouble(digits);
                }
            } catch (NumberFormatException e) {
                // falls through to the error below
            }
            pos = start;
            throw error("bad number " + digits);
        }

        private char peek() throws ParseException {
            if (pos >= text.length()) {
                throw error("unexpected end");
            }
            return text.charAt(pos);
        }

        private void expect(char c) throws ParseException {
            if (peek() != c) {
                throw error("expected " + c);
            }
            pos++;
        }
    }

    // ---- writing

    /** Pretty-prints maps, lists, strings, numbers, booleans and null. */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.append('\n').toString();
    }

    private static void write(Object value, StringBuilder out, int indent) {
        switch (value) {
            case null -> out.append("null");
            case String s -> quote(s, out);
            case Boolean b -> out.append(b);
            case Integer i -> out.append(i);
            case Long l -> out.append(l);
            case Double d -> out.append(d.isNaN() || d.isInfinite() ? "null" : d.toString());
            case Map<?, ?> map -> {
                if (map.isEmpty()) {
                    out.append("{}");
                    return;
                }
                out.append("{\n");
                int i = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    pad(out, indent + 1);
                    quote(String.valueOf(entry.getKey()), out);
                    out.append(": ");
                    write(entry.getValue(), out, indent + 1);
                    out.append(++i < map.size() ? ",\n" : "\n");
                }
                pad(out, indent);
                out.append('}');
            }
            case List<?> list -> {
                if (list.isEmpty()) {
                    out.append("[]");
                    return;
                }
                boolean simple = list.stream().allMatch(item -> !(item instanceof Map) && !(item instanceof List));
                if (simple) {
                    out.append('[');
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) {
                            out.append(", ");
                        }
                        write(list.get(i), out, indent);
                    }
                    out.append(']');
                    return;
                }
                out.append("[\n");
                for (int i = 0; i < list.size(); i++) {
                    pad(out, indent + 1);
                    write(list.get(i), out, indent + 1);
                    out.append(i + 1 < list.size() ? ",\n" : "\n");
                }
                pad(out, indent);
                out.append(']');
            }
            default -> throw new IllegalArgumentException("Can't write " + value.getClass().getSimpleName() + " as JSON");
        }
    }

    private static void pad(StringBuilder out, int indent) {
        out.append("  ".repeat(indent));
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ---- typed access, for readers of plan and rules files

    static Map<String, Object> object(Object value, String what) throws ParseException {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        throw new ParseException(what + " should be an object");
    }

    static List<Object> list(Map<String, Object> object, String key, String what) throws ParseException {
        Object value = object.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        throw new ParseException(what + "." + key + " should be a list");
    }

    static String string(Map<String, Object> object, String key, String what) throws ParseException {
        Object value = object.get(key);
        if (value instanceof String s) {
            return s;
        }
        throw new ParseException(what + "." + key + " should be text");
    }

    static String optionalString(Map<String, Object> object, String key, String what) throws ParseException {
        Object value = object.get(key);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new ParseException(what + "." + key + " should be text");
    }

    static long number(Map<String, Object> object, String key, String what) throws ParseException {
        if (object.get(key) instanceof Long l) {
            return l;
        }
        throw new ParseException(what + "." + key + " should be a whole number");
    }

    static List<String> strings(Map<String, Object> object, String key, String what) throws ParseException {
        List<String> out = new ArrayList<>();
        for (Object item : list(object, key, what)) {
            if (!(item instanceof String s)) {
                throw new ParseException(what + "." + key + " should be a list of text");
            }
            out.add(s);
        }
        return out;
    }
}
