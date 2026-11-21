package com.h2h.dhamma;

// v15.50 (slice 1966): "New talks" rail on Main — the Spotify
// "New releases" / YouTube Music "New" shelf for talks that
// arrived since the last visit: the newest unplayed talks across
// all sayadaws as tappable cards below the favourite-talks rail
// (1964), one tap straight into the Room at the start. The data
// source is the 1920 new-set (TalkListCache.loadNewSet — the
// identity URLs in the fresh list that the saved list does not
// have) joined to each page's cached list for titles, in
// catalogue order across sayadaws and page order (site order —
// newest-first on dated pages, verified slice 1931) within one
// sayadaw. The gate is the 1921 rule verbatim: in the new-set,
// not finished (playedTalks), not started (a saved spot over 5s),
// not the sounding talk. A new talk with no cached title has no
// card, never a title-less card (the 1962 aged-out edge). Pure
// selection only: the Activity owns the catalog pass, the
// cache-file reads, the sounding skip's live URL and the Room
// open. The new-set contract is identity keys only (diffNewIds
// stores identityUrl) — an http-form set never matches.
public class NewTalkRail {
    public static final int MAX_CARDS = 5;

    // One sayadaw's contribution: the cached [title, url] pairs in
    // page order plus that page's new-set. The Activity builds
    // these only for dotted sayadaws (the 1921 gate at sayadaw
    // level), so undotted sayadaws pay no cache-file read.
    public static class Entry {
        public final String sayadawName;
        public final java.util.Set<String> newIds;
        public final java.util.List<String[]> pairs;
        public Entry(String sayadawName, java.util.Set<String> newIds,
                java.util.List<String[]> pairs) {
            this.sayadawName = sayadawName == null ? "" : sayadawName;
            this.newIds = newIds;
            this.pairs = pairs;
        }
    }

    public static class Card {
        public final Prefs.HistEntry e;
        public Card(Prefs.HistEntry e) {
            this.e = e;
        }
    }

    static java.util.List<Card> pick(
            java.util.List<Entry> entries,
            java.util.Set<String> playedIds,
            java.util.Set<String> resumeKeys,
            String soundingUrl) {
        java.util.List<Card> out = new java.util.ArrayList<Card>();
        if (entries == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Entry en : entries) {
            if (out.size() >= MAX_CARDS) break;
            if (en == null) continue;
            if (en.sayadawName == null || en.sayadawName.length() == 0) continue;
            if (en.newIds == null || en.newIds.isEmpty()) continue;
            if (en.pairs == null) continue;
            for (String[] p : en.pairs) {
                if (out.size() >= MAX_CARDS) break;
                if (p == null || p.length < 2) continue;
                if (p[0] == null || p[0].trim().length() == 0) continue;
                if (p[1] == null || p[1].length() == 0) continue;
                String id = SiteHttp.identityUrl(p[1]);
                if (id == null) continue;
                if (!en.newIds.contains(id)) continue;
                if (playedIds != null && playedIds.contains(id)) continue;
                if (resumeKeys != null && resumeKeys.contains(id)) continue;
                if (soundingUrl != null && SiteHttp.sameTalk(p[1], soundingUrl)) continue;
                if (seen.contains(id)) continue;
                seen.add(id);
                out.add(new Card(new Prefs.HistEntry(p[1], en.sayadawName,
                        p[0].trim(), 0, 0)));
            }
        }
        return out;
    }
}
