package com.h2h.dhamma;

// v15.62 (slice 2033): "See all" on the Main "Bookmarks" rail —
// the rail's full list. BmRail.pick caps at 5 cards and shows one
// card per talk (its latest mark); this screen lists EVERY mark:
// a talk with three marks is three rows, each jumping to its own
// minute. Same store (Prefs.bookmarks64, "url\tposMs" lines,
// most-recent first), same join (statsRecent for title + sayadaw,
// catalog sayadaw), same fail-closed parsing — the ONLY
// differences are no cap and the dedupe key: (identity + posMs),
// so http/https twin lines for the same minute still collapse to
// one row while two different minutes stay two.
// v15.64 (slice 2041): the sounding skip is REVERSED here —
// 2033 copied the rail's "never the sounding talk" rule, but the
// rail hides the playing talk's card while the full list must SHOW
// it: the playing-row mark (v13.55 mirror, this slice) has no row
// to mark otherwise, and in the common case (Room session, a mark
// on the sounding talk) the skip fired on exactly the rows the
// mark would mark. The rail keeps its skip (BmRail.pick untouched
// — the 1991/2017 rail rule stands on rails); only this full-list
// pick lists the sounding talk's marks, marked "\u25b6 " in gold.
// The soundingUrl parameter is gone with the skip.
// Pure selection only: the Activity owns the catalog lookup, the
// resume-spot write and the Room open.
public class BmAll {
    public static class Row {
        public final Prefs.HistEntry e;
        public final int posMs;
        public Row(Prefs.HistEntry e, int posMs) {
            this.e = e;
            this.posMs = posMs;
        }
    }

    static java.util.List<Row> pickAll(
            java.util.List<String> bookmarkLines,
            java.util.List<Prefs.HistEntry> recent,
            java.util.Set<String> catalogNames) {
        java.util.List<Row> out = new java.util.ArrayList<Row>();
        if (bookmarkLines == null || recent == null || catalogNames == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (String line : bookmarkLines) {
            if (line == null) continue;
            int t = line.indexOf('\t');
            if (t <= 0) continue;
            String url = line.substring(0, t);
            if (url.length() == 0) continue;
            int pos;
            try {
                pos = Integer.parseInt(line.substring(t + 1));
            } catch (Exception ignored) { continue; }
            if (pos <= 0) continue;
            Prefs.HistEntry hit = null;
            for (Prefs.HistEntry h : recent) {
                if (h == null) continue;
                if (h.url == null || h.url.length() == 0) continue;
                if (!SiteHttp.sameTalk(url, h.url)) continue;
                hit = h;
                break;
            }
            if (hit == null) continue;
            if (hit.title == null || hit.title.trim().length() == 0) continue;
            if (hit.sayadaw == null || hit.sayadaw.length() == 0) continue;
            if (!catalogNames.contains(hit.sayadaw)) continue;
            String id = SiteHttp.identityUrl(hit.url);
            if (id == null) continue;
            String key = id + ":" + pos;
            if (seen.contains(key)) continue;
            seen.add(key);
            out.add(new Row(hit, pos));
        }
        return out;
    }
}
