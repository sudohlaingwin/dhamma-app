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

    public QueueEntry(String url, String sayadaw, String title) {
        this.url = url == null ? "" : url;
        this.sayadaw = sayadaw == null ? "" : sayadaw;
        this.title = title == null ? "" : title;
    }

    // True when this record carries a foreign owner (a v2 line with
    // a non-empty sayadaw field). The (d) filter rule and the (b)
    // handover key off this; today nothing widens on it yet.
    public boolean isV2() {
        return sayadaw.length() > 0;
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
        // Title is the remainder: a writer never emits tabs in a
        // title (format() strips them), so extra tabs here mean a
        // malformed line — keep the text rather than dropping it.
        return new QueueEntry(url, rest.substring(0, tab2),
                rest.substring(tab2 + 1));
    }

    // Format one record for storage. A same-sayadaw record formats
    // back to the bare v1 URL, byte-identical to today's writer.
    // Titles are stripped of tabs/newlines on write so a stored
    // line can never break parsing.
    public static String format(QueueEntry e) {
        if (e == null || e.url.length() == 0) return "";
        if (e.sayadaw.length() == 0) return e.url;
        return e.url + "\t" + e.sayadaw + "\t" + strip(e.title);
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
