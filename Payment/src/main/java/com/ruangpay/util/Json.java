package com.ruangpay.util;

import java.util.*;

public final class Json {
    private Json() {}

    public static Map<String, Object> parseObject(String text) {
        Object value = new Parser(text).parseValue();
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("JSON object diperlukan");
        @SuppressWarnings("unchecked") Map<String,Object> result = (Map<String,Object>) map;
        return result;
    }

    public static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + escape(s) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?,?> map) {
            StringJoiner j = new StringJoiner(",", "{", "}");
            map.forEach((k,v) -> j.add(stringify(String.valueOf(k)) + ":" + stringify(v)));
            return j.toString();
        }
        if (value instanceof Collection<?> collection) {
            StringJoiner j = new StringJoiner(",", "[", "]");
            collection.forEach(v -> j.add(stringify(v)));
            return j.toString();
        }
        return stringify(value.toString());
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                .replace("\r", "\\r").replace("\t", "\\t");
    }

    private static final class Parser {
        private final String s; private int i;
        Parser(String s) { this.s = s == null ? "" : s; }
        Object parseValue() {
            ws(); if (i >= s.length()) throw new IllegalArgumentException("JSON kosong");
            return switch (s.charAt(i)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", true);
                case 'f' -> literal("false", false);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }
        private Map<String,Object> object() {
            i++; Map<String,Object> map = new LinkedHashMap<>(); ws();
            if (peek('}')) { i++; return map; }
            while (true) {
                ws(); String key = string(); ws(); expect(':');
                map.put(key, parseValue()); ws();
                if (peek('}')) { i++; return map; }
                expect(',');
            }
        }
        private List<Object> array() {
            i++; List<Object> list = new ArrayList<>(); ws();
            if (peek(']')) { i++; return list; }
            while (true) {
                list.add(parseValue()); ws();
                if (peek(']')) { i++; return list; }
                expect(',');
            }
        }
        private String string() {
            expect('"'); StringBuilder out = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return out.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    out.append(switch (e) {
                        case '"','\\','/' -> e; case 'b' -> '\b'; case 'f' -> '\f';
                        case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                        default -> throw new IllegalArgumentException("Escape JSON tidak valid");
                    });
                } else out.append(c);
            }
            throw new IllegalArgumentException("String JSON tidak selesai");
        }
        private Object number() {
            int start = i;
            while (i < s.length() && "-+0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(start, i);
            try { return n.contains(".") ? Double.parseDouble(n) : Long.parseLong(n); }
            catch (Exception e) { throw new IllegalArgumentException("Nilai JSON tidak valid"); }
        }
        private Object literal(String token, Object value) {
            if (!s.startsWith(token, i)) throw new IllegalArgumentException("Literal JSON tidak valid");
            i += token.length(); return value;
        }
        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }
        private void expect(char c) {
            ws(); if (!peek(c)) throw new IllegalArgumentException("Diharapkan '" + c + "'");
            i++;
        }
    }
}
