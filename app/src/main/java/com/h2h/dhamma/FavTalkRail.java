package com.h2h.dhamma;

// v15.50 (slice 1964): "Favourite talks" rail on Main — the
// Spotify "Liked Songs" / YouTube Music "Your favourites" shelf
// for starred talks: the listener's ★-favourited talks as
// tappable cards below the bookmarks rail (1962), one tap back
// into the Room at the saved spot. The store is Prefs "favTalks"
// (a StringSet of canonical https URLs, unordered — so the rail
// order is the statsRecent order, most-recently-played first); the
// talk's title + sayadaw come from Prefs.statsRecent (a starred
// talk with no play record has no title to show and no card, the
// same aged-out edge as the 1962 rail). The saved spot rides in
// posById, keyed by identity URL. Pure selection only: the
// Activity owns the catalog lookup, the sounding skip's live URL,
// the spot map and the Room open.
public class FavTalkRail {
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
            java.util.Set<String> favUrls,
            java.util.List<Prefs.HistEntry> recent,
            java.util.Set<String> catalogNames,
            String soundingUrl,
            java.util.Map<String, Integer> posById) {
        java.util.List<Card> out = new java.util.ArrayList<Card>();
        if (favUrls == null || recent == null || catalogNames == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Prefs.HistEntry h : recent) {
            if (out.size() >= MAX_CARDS) break;
            if (h == null) continue;
            if (h.url == null || h.url.length() == 0) continue;
            boolean starred = false;
            for (String f : favUrls) {
                if (f == null) continue;
                if (SiteHttp.sameTalk(f, h.url)) { starred = true; break; }
            }
            if (!starred) continue;
            if (h.title == null || h.title.trim().length() == 0) continue;
            if (h.sayadaw == null || h.sayadaw.length() == 0) continue;
            if (!catalogNames.contains(h.sayadaw)) continue;
            if (soundingUrl != null && SiteHttp.sameTalk(h.url, soundingUrl)) continue;
            String id = SiteHttp.identityUrl(h.url);
            if (id == null) continue;
            if (seen.contains(id)) continue;
            seen.add(id);
            int pos = 0;
            if (posById != null) {
                Integer p = posById.get(id);
                if (p != null) pos = Math.max(0, p.intValue());
            }
            out.add(new Card(h, pos));
        }
        return out;
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
