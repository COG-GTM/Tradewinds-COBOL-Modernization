package com.ibm.cics.cip.bank.core.screens;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of one screen turn: what the COBOL program sent to the terminal (SEND MAP / SEND TEXT /
 * SEND CONTROL) and how it returned (RETURN TRANSID ... COMMAREA). Field values are the symbolic map
 * output fields ({@code xxxO}) that were set (not LOW-VALUES), in BMS order, truncated to the BMS LENGTH.
 */
public record ScreenOutput(
        BmsMap map,
        Integer receivedCount,
        SendMode sendMode,
        boolean cursor,
        Map<String, String> fields,
        String sendText,
        boolean sendControlErase,
        String returnTransid,
        boolean immediate,
        int commareaLength,
        Map<String, String> commarea) {

    /** SEND-ERASE / SEND-DATAONLY / SEND-DATAONLY-ALARM switches of the SEND-MAP paragraphs. */
    public enum SendMode { ERASE, DATAONLY, DATAONLY_ALARM }

    public String message() {
        return field("MESSAGE");
    }

    public String field(String name) {
        String v = fields.get(name);
        return v == null ? null : rtrim(v);
    }

    /** Legacy-harness style trace of the terminal interaction (matches baseline transcript {@code screen}). */
    public List<List<String>> events() {
        List<List<String>> ev = new ArrayList<>();
        if (receivedCount != null) {
            ev.add(List.of("RECEIVE_MAP", map.name(), map.mapset(), String.valueOf(receivedCount)));
        }
        if (sendMode != null) {
            StringBuilder opts = new StringBuilder(sendMode == SendMode.ERASE ? "ERASE " : "DATAONLY ");
            if (cursor) {
                opts.append("CURSOR ");
            }
            if (sendMode == SendMode.DATAONLY_ALARM) {
                opts.append("ALARM ");
            }
            opts.append("FREEKB ");
            ev.add(List.of("SEND_MAP", map.name(), map.mapset(), opts.toString()));
            fields.forEach((k, v) -> ev.add(List.of("FIELD", k, rtrim(v))));
        }
        if (sendText != null) {
            ev.add(List.of("SEND_TEXT", "ERASE", sendText));
        }
        if (sendControlErase) {
            ev.add(List.of("SEND_CONTROL", "ERASE"));
        }
        if (returnTransid != null) {
            ev.add(List.of("RETURN_TRANSID", returnTransid, immediate ? "IMMEDIATE" : "",
                    String.valueOf(commareaLength)));
        }
        return ev;
    }

    static String rtrim(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\0')) {
            end--;
        }
        return s.substring(0, end);
    }

    public static Builder builder(BmsMap map) {
        return new Builder(map);
    }

    /** Mutable symbolic map + EXEC CICS outcome, filled in by the screen paragraphs. */
    public static final class Builder {
        private final BmsMap map;
        private final Map<String, String> values = new LinkedHashMap<>();
        private final Map<String, String> commarea = new LinkedHashMap<>();
        private Integer receivedCount;
        private SendMode sendMode;
        private boolean cursor;
        private String sendText;
        private boolean sendControlErase;
        private String returnTransid;
        private boolean immediate;
        private int commareaLength;

        private Builder(BmsMap map) {
            this.map = map;
        }

        /** EXEC CICS RECEIVE MAP: the received fields overlay the output map. */
        public Builder receive(ScreenInput in) {
            receivedCount = in.receivedCount();
            in.fields().forEach((k, v) -> {
                if (v != null && !v.isEmpty() && map.fields().containsKey(k)) {
                    set(k, v);
                }
            });
            return this;
        }

        /** MOVE value TO xxxO (alphanumeric MOVE: truncated to the BMS field length). */
        public Builder set(String field, String value) {
            int len = map.length(field);
            String v = value == null ? "" : value;
            values.put(field, v.length() > len ? v.substring(0, len) : v);
            return this;
        }

        /** MOVE SPACES TO xxxO. */
        public Builder spaces(String... fields) {
            for (String f : fields) {
                set(f, "");
            }
            return this;
        }

        public Builder message(String message) {
            return set("MESSAGE", message);
        }

        public String get(String field) {
            return values.get(field);
        }

        public Builder send(SendMode mode, boolean withCursor) {
            this.sendMode = mode;
            this.cursor = withCursor;
            return this;
        }

        public Builder sendText(String text) {
            this.sendText = text;
            return this;
        }

        public Builder sendControlErase() {
            this.sendControlErase = true;
            return this;
        }

        /** EXEC CICS RETURN TRANSID(own transid) COMMAREA(...) LENGTH(map commarea length). */
        public Builder returnToSelf() {
            return returnTransid(map.transid(), false, map.commareaLength());
        }

        public Builder returnTransid(String transid, boolean isImmediate, int length) {
            this.returnTransid = transid;
            this.immediate = isImmediate;
            this.commareaLength = length;
            return this;
        }

        public Builder state(String key, String value) {
            commarea.put(key, value);
            return this;
        }

        public Builder state(Map<String, String> state) {
            commarea.putAll(state);
            return this;
        }

        public ScreenOutput build() {
            Map<String, String> ordered = new LinkedHashMap<>();
            for (String f : map.fields().keySet()) {
                if (values.containsKey(f)) {
                    ordered.put(f, values.get(f));
                }
            }
            return new ScreenOutput(map, receivedCount, sendMode, cursor, Collections.unmodifiableMap(ordered),
                    sendText, sendControlErase, returnTransid, immediate, commareaLength,
                    Collections.unmodifiableMap(new LinkedHashMap<>(commarea)));
        }
    }
}
