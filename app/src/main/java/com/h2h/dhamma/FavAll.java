package com.h2h.dhamma;

// v15.63 (slice 2035): "See all" on the Main "Favourite talks" rail —
// the rail's full list. FavTalkRail.pick caps at 5 cards; this screen
// lists EVERY starred talk, in the rail's own most-recently-played
// order. Same store (Prefs "favTalks", canonical https URLs,
// unordered), same join (statsRecent for title + sayadaw, catalog
// sayadaw), same fail-closed parsing, same dedupe by identity URL,
// same spot from the posById map — the ONLY difference is no cap.
// A starred talk with no play record has no row, the same aged-out
// edge as the 1964 rail.
// v15.64 (slice 2042): the sounding skip is REVERSED here —
// 2035 copied the rail's "never the sounding talk" rule, but the
// rail hides the playing talk's card while the full list must SHOW
// it: the playing-row mark (v13.55 mirror, this slice) has no row
// to mark otherwise, and in the common case (Room session, the
// sounding talk starred) the skip fired on exactly the row the
// mark would mark. The rail keeps its skip (FavTalkRail.pick untouched
// — the 1991/2017 rail rule stands on rails); only this
// full-list pick lists the sounding talk, marked "\u25b6 " in gold.
// The soundingUrl parameter is gone with the skip.
// Pure selection only: the Activity owns the catalog lookup, the
// spot map and the Room open.
public class FavAll {
    static java.util.List<FavTalkRail.Card> pickAll(
            java.util.Set<String> favUrls,
            java.util.List<Prefs.HistEntry> recent,
            java.util.Set<String> catalogNames,
            java.util.Map<String, Integer> posById) {
        java.util.List<FavTalkRail.Card> out = new java.util.ArrayList<FavTalkRail.Card>();
        if (favUrls == null || recent == null || catalogNames == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Prefs.HistEntry h : recent) {
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
            String id = SiteHttp.identityUrl(h.url);
            if (id == null) continue;
            if (seen.contains(id)) continue;
            seen.add(id);
            int pos = 0;
            if (posById != null) {
                Integer p = posById.get(id);
                if (p != null) pos = Math.max(0, p.intValue());
            }
            out.add(new FavTalkRail.Card(h, pos));
        }
        return out;
    }
}
