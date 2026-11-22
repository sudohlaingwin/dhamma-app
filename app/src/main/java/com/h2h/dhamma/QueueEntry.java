package com.h2h.dhamma;

// v7.07: ONE queued-talk record for the cross-sayadaw queue piece
// (15:48 v2 design contract, step (a)). Plain Java, no Android
// imports, so the format can be tested standalone.
//
// v2 line: "url \t sayadaw \t title", newline-joined exactly like
// today's v1 lines (Prefs.saveQueue). Fields are MINIMAL on purpose:
// url is the playback identity (unchanged); sayadaw names whose Room
// session owns the talk; title is the row text when the talk is not
// in the current list. Group and portrait are NOT stored — both
// resolve from the bundled catalog by sayadaw name at display time,
// so a catalog refresh never leaves stale data in queues.
//
// v1 compatibility is by construction: a line with no tab is a v1
// bare URL and keeps today's behaviour exactly. A v2 line whose
// sayadaw field is empty is treated as v1 (same rule as the design).
// An unparseable line (empty line, empty URL) returns null and is
// skipped, never fatal — the talkPosMap rule (v5.34).
//
// v15.66 (slice 2053): v3 line — "url \t sayadaw \t title \t posMs".
// A v3 line carries the minute (ms) the talk must START at: the
// "Play all marks" run on the Bookmarks screen queues one line per
// saved minute, and the Room pop (slice 2054) honours the line's
// minute as an explicit seek WITHOUT writing it into talkPos —
// pre-seeding talkPos would clobber the listener's real resume
// spots, and a v2 line has nowhere to carry the minute (the Room
// pop plays at the saved spot, never at a bookmark's minute).
// This slice lands ONLY the format, pure and inert: no writer
// emits v3 lines yet and the Room does not read posMs yet, so
// nothing in the app changes behaviour.
// This run lands ONLY the record type + parser. No writer emits v2
// lines yet, and the Room filter still applies the v1 keep-rule
// (URL must be in the loaded list), so no foreign row can survive
// into a queue before the session handover (step (b)) exists. The
// (d) widening (keep a v2 line on a non-empty sayadaw field) lands
// together with (b), never alone.
public class QueueEntry {
    public final String url;
    public final String sayadaw;
    public final String title;
    // v15.66 (slice 2053): start minute in ms, -1 = none (every v1/v2
    // line). A v3 line's minute is the ONLY start position the Room
    // pop honours from the line itself; it never overwrites the
    // saved resume spot (talkPos).
    public final int posMs;

    public QueueEntry(String url, String sayadaw, String title) {
        this(url, sayadaw, title, -1);
    }

    public QueueEntry(String url, String sayadaw, String title, int posMs) {
        this.url = url == null ? "" : url;
        this.sayadaw = sayadaw == null ? "" : sayadaw;
        this.title = title == null ? "" : title;
        this.posMs = posMs < 0 ? -1 : posMs;
    }

    // True when this record carries a foreign owner (a v2 line with
    // a non-empty sayadaw field). The (d) filter rule and the (b)
    // handover key off this; today nothing widens on it yet.
    public boolean isV2() {
        return sayadaw.length() > 0;
    }

    // v15.66 (slice 2053): true when this record carries a start
    // minute (a v3 line). The Room pop keys off this; today
    // nothing reads it yet.
    public boolean isV3() {
        return posMs >= 0;
    }

    // Parse one stored line. Null = skip the line, never fatal.
    public static QueueEntry parse(String line) {
        if (line == null || line.length() == 0) return null;
        int tab = line.indexOf('\t');
        if (tab < 0) return new QueueEntry(line, "", "");
        String url = line.substring(0, tab);
        if (url.length() == 0) return null;
        String rest = line.substring(tab + 1);
        int tab2 = rest.indexOf('\t');
        if (tab2 < 0) return new QueueEntry(url, rest, "");
        // v15.66 (slice 2053): a v3 line carries a 4th tab field, the
        // start minute in ms. It reads ONLY when the field is
        // digits-only and fits an int — anything else (a title that
        // merely contains a tab, a non-numeric tail, an overflow)
        // keeps the old v2 reading: title = the full remainder,
        // posMs = -1. A writer never emits tabs in a title
        // (format() strips them), so a digits-only 4th field can
        // only come from a real v3 writer.
        String tail = rest.substring(tab2 + 1);
        int tab3 = tail.indexOf('\t');
        if (tab3 >= 0) {
            String fourth = tail.substring(tab3 + 1);
            if (isDigits(fourth)) {
                try {
                    int ms = Integer.parseInt(fourth);
                    return new QueueEntry(url, rest.substring(0, tab2),
                            tail.substring(0, tab3), ms);
                } catch (NumberFormatException ignored) {
                    // Overflow: fall through to the v2 reading below.
                }
            }
        }
        // Title is the remainder: a writer never emits tabs in a
        // title (format() strips them), so extra tabs here mean a
        // malformed line — keep the text rather than dropping it.
        return new QueueEntry(url, rest.substring(0, tab2),
                rest.substring(tab2 + 1));
    }

    // Digits-only, non-empty: the v3 minute's shape. A sign, a
    // space, a decimal point or an empty field all read as "not a
    // minute" — the caller keeps the v2 reading, never a guess.
    private static boolean isDigits(String s) {
        if (s == null || s.length() == 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    // Format one record for storage. A same-sayadaw record formats
    // back to the bare v1 URL, byte-identical to today's writer.
    // Titles are stripped of tabs/newlines on write so a stored
    // line can never break parsing.
    public static String format(QueueEntry e) {
        if (e == null || e.url.length() == 0) return "";
        if (e.sayadaw.length() == 0) return e.url;
        // v15.66 (slice 2053): a minute rides only a v2-framed line —
        // a bare v1 URL has nowhere to carry it, so the minute
        // drops rather than inventing a shape. A v3 record formats
        // with its 4th field; v1/v2 output stays byte-identical.
        if (e.posMs < 0) return e.url + "\t" + e.sayadaw + "\t" + strip(e.title);
        return e.url + "\t" + e.sayadaw + "\t" + strip(e.title) + "\t" + e.posMs;
    }

    private static String strip(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') continue;
            sb.append(c);
        }
        return sb.toString();
    }
}
