package com.ibm.cics.cip.bank.core.screens;

import java.util.Map;

/**
 * One pseudo-conversational turn: the AID key pressed, the received BMS map fields (by BMS field name)
 * and the COMMAREA carried from the previous turn. An empty/null COMMAREA means {@code EIBCALEN = ZERO}
 * (first display).
 */
public record ScreenInput(AidKey aid, Map<String, String> fields, Map<String, String> commarea) {

    public ScreenInput {
        aid = aid == null ? AidKey.ENTER : aid;
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        commarea = commarea == null ? null : Map.copyOf(commarea);
    }

    /** First display ({@code EIBCALEN = ZERO}). */
    public static ScreenInput firstDisplay() {
        return new ScreenInput(AidKey.ENTER, Map.of(), null);
    }

    public static ScreenInput of(AidKey aid, Map<String, String> fields) {
        return new ScreenInput(aid, fields, Map.of());
    }

    public boolean firstTime() {
        return commarea == null;
    }

    /** Field value as received ({@code xxxI}); empty string when not transmitted. */
    public String field(String name) {
        String v = fields.get(name);
        return v == null ? "" : v;
    }

    /** {@code xxxL = ZERO OR xxxI = LOW-VALUES}. */
    public boolean missing(String name) {
        return field(name).isEmpty();
    }

    public String state(String key) {
        return commarea == null ? "" : commarea.getOrDefault(key, "");
    }

    /** Number of fields actually transmitted (the RECEIVE MAP length reported by the legacy harness). */
    public int receivedCount() {
        return (int) fields.values().stream().filter(v -> v != null && !v.isEmpty()).count();
    }
}
