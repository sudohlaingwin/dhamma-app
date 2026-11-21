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
}
