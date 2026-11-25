package com.h2h.dhamma;

// v15.43 (slice 1930): "Recently played" rail on Main — the
// Spotify "Jump back in" / YouTube Music "Listen again" rail:
// the last few talks the listener actually played, one tap back
// into the Room at the saved spot. Prefs.statsRecent already
// banks them (most-recent-first) for the dormant History screen;
// Main never showed them. Pure selection only: the Activity owns
// the catalog lookup, the sounding skip's live URL and the Room
// open.
public class RecentRail {
    public static final int MAX_CARDS = 5;

    static java.util.List<Prefs.HistEntry> pick(
            java.util.List<Prefs.HistEntry> recent,
            java.util.Set<String> catalogNames,
            String soundingUrl) {
        java.util.List<Prefs.HistEntry> out =
                new java.util.ArrayList<Prefs.HistEntry>();
        if (recent == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Prefs.HistEntry e : recent) {
            if (out.size() >= MAX_CARDS) break;
            if (e == null) continue;
            if (e.url == null || e.url.length() == 0) continue;
            if (e.title == null || e.title.trim().length() == 0) continue;
            if (e.sayadaw == null || e.sayadaw.length() == 0) continue;
            if (catalogNames == null || !catalogNames.contains(e.sayadaw)) continue;
            String id = SiteHttp.identityUrl(e.url);
            if (id == null) continue;
            if (seen.contains(id)) continue;
            if (soundingUrl != null && SiteHttp.sameTalk(e.url, soundingUrl)) continue;
            seen.add(id);
            out.add(e);
        }
        return out;
    }

    // v15.95 (slice 2228): the total behind See-all — the pick's gates WITHOUT the 5-card cap and WITHOUT the sounding skip, so the header count never flickers when a talk starts or stops and matches what History opens. A null list reads 0; null entries, empty url/title/sayadaw, not-in-catalog names and duplicate identities (the pick's own identity rule) are skipped. No cap, no sounding parameter at all — the sounding talk stays counted, as History shows it.
    static int countAll2228(
            java.util.List<Prefs.HistEntry> recent,
            java.util.Set<String> catalogNames) {
        int total = 0;
        if (recent == null) return 0;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Prefs.HistEntry e : recent) {
            if (e == null) continue;
            if (e.url == null || e.url.length() == 0) continue;
            if (e.title == null || e.title.trim().length() == 0) continue;
            if (e.sayadaw == null || e.sayadaw.length() == 0) continue;
            if (catalogNames == null || !catalogNames.contains(e.sayadaw)) continue;
            String id = SiteHttp.identityUrl(e.url);
            if (id == null) continue;
            if (seen.contains(id)) continue;
            seen.add(id);
            total++;
        }
        return total;
    }
}
