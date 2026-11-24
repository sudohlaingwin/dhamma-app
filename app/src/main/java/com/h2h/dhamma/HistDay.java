package com.h2h.dhamma;

// v15.84 (slice 2163): the listened-day label for History rows —
// the REC line's own stamp (Prefs.HistEntry.lastMs, refreshed on
// every re-listen, v12.82) rendered as the day it was heard, so a
// row says WHEN a talk was listened to as well as how long. The
// label is day-first ("24.11.2026") — the Burmese reading order —
// with no leading zeros and no month names, so it needs no Lang
// key in any language; the Activity converts the digits to
// Myanmar digits in Burmese UI (the num() rule —
// Prefs.statsDigitsPure). The device's own calendar day is the
// label's day (a listener in Bangkok reads Bangkok's day). A
// pre-timestamp stamp (lastMs <= 0) reads "" — a legacy row keeps
// exactly its old sub-line, never a "1.1.1970" ghost.
public class HistDay {

    // v15.84 (slice 2163): "d.M.yyyy" for a real stamp, "" for a
    // pre-timestamp one (lastMs <= 0). No leading zeros (5.1.2027,
    // not 05.01.2027); the year stays, so two Novembers never
    // collide. The stamp's own day — no relative "today" word,
    // which would need a Lang key in all twelve languages.
    public static String label(long lastMs) {
        if (lastMs <= 0) return "";
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(lastMs);
        return c.get(java.util.Calendar.DAY_OF_MONTH) + "."
                + (c.get(java.util.Calendar.MONTH) + 1) + "."
                + c.get(java.util.Calendar.YEAR);
    }
}
