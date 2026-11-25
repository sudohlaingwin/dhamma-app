package com.h2h.dhamma;

// v15.49 (slice 1962): "Bookmarks" rail on Main — the Spotify
// "Your Library" / YouTube Music "Listen again" rail for marked
// minutes: the listener's bookmarked talks as tappable cards
// below the downloaded rail (1960), one tap back into the Room at
// the marked minute. The store is Prefs.bookmarks64 (v12.64 —
// "url\tposMs" lines, most-recent first, both stream + file
// forms); the talk's title + sayadaw come from Prefs.statsRecent
// (a mark can only be added while a talk sounds, and sounding
// talks are recorded there, canonicalised to the stream URL by
// statsAdd, so the join is on talk identity — a file-form twin
// line finds no recent match while its stream twin does). Pure
// selection only: the Activity owns the catalog lookup, the
// sounding skip's live URL, the resume-spot write and the Room
// open.
public class BmRail {
    public static final int MAX_CARDS = 5;

    public static class Card {
        public final Prefs.HistEntry e;
        public final int posMs;
        public Card(Prefs.HistEntry e, int posMs) {
            this.e = e;
            this.posMs = posMs;
        }
    }

    static java.util.List<Card> pick(
            java.util.List<String> bookmarkLines,
            java.util.List<Prefs.HistEntry> recent,
            java.util.Set<String> catalogNames,
            String soundingUrl) {
        java.util.List<Card> out = new java.util.ArrayList<Card>();
        if (bookmarkLines == null || recent == null || catalogNames == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (String line : bookmarkLines) {
            if (out.size() >= MAX_CARDS) break;
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
            if (soundingUrl != null && SiteHttp.sameTalk(url, soundingUrl)) continue;
            String id = SiteHttp.identityUrl(hit.url);
            if (id == null) continue;
            if (seen.contains(id)) continue;
            seen.add(id);
            out.add(new Card(hit, pos));
        }
        return out;
    }

    // v15.95 (slice 2230): the total behind See-all — the pick's gates WITHOUT the 5-card cap and WITHOUT the sounding skip, so the header count never flickers when a talk starts or stops. The line order is the store order, most-recent first — a count does not care about order, and the dedup by canonical identity makes the total order-independent. A null list reads 0; null lines, lines without a tab, empty URLs, unparseable or non-positive minutes, lines with no recent match, empty titles, missing or not-in-catalog sayadaws and duplicate identities are skipped. No cap, no sounding parameter at all — the sounding talk stays counted, as the full list shows it.
    static int countAll2230(
            java.util.List<String> bookmarkLines,
            java.util.List<Prefs.HistEntry> recent,
            java.util.Set<String> catalogNames) {
        int total = 0;
        if (bookmarkLines == null || recent == null || catalogNames == null) return 0;
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
            if (seen.contains(id)) continue;
            seen.add(id);
            total++;
        }
        return total;
    }

    // RoomActivity.formatTime verbatim (M:SS, H:MM:SS past the hour).
    static String fmtTime(int ms) {
        int total = Math.max(0, ms / 1000);
        if (total >= 3600)
            return (total / 3600) + ":" + String.format(java.util.Locale.US, "%02d", (total % 3600) / 60)
                    + ":" + String.format(java.util.Locale.US, "%02d", total % 60);
        return (total / 60) + ":" + String.format(java.util.Locale.US, "%02d", total % 60);
    }
}
