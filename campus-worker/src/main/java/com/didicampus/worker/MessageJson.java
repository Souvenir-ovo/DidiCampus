package com.didicampus.worker;

final class MessageJson {

    private MessageJson() {
    }

    static long longValue(String json, String field) {
        int start = valueStart(json, field);
        boolean quoted = start < json.length() && json.charAt(start) == '"';
        if (quoted) {
            start++;
        }
        int end = start;
        while (end < json.length()
                && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) {
            throw new IllegalArgumentException("missing numeric value, field=" + field);
        }
        if (quoted && (end >= json.length() || json.charAt(end) != '"')) {
            throw new IllegalArgumentException("unclosed quoted number, field=" + field);
        }
        return Long.parseLong(json.substring(start, end));
    }

    private static int valueStart(String json, String field) {
        String needle = "\"" + field + "\":";
        int offset = json.indexOf(needle);
        if (offset < 0) {
            throw new IllegalArgumentException("field not found, field=" + field);
        }
        int index = offset + needle.length();
        while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
            index++;
        }
        return index;
    }
}
