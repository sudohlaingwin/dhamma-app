package com.h2h.dhamma;

// v15.63 (slice 2036): "See all" on the Main "New talks" rail —
// the rail's full list. NewTalkRail.pick caps at 5 cards; this
// screen lists EVERY new talk, in the rail's own order (catalogue
// order across sayadaws, page order within one sayadaw). Same
// entries (each dotted sayadaw's cached [title, url] pairs in page
// order plus that page's new-set), same 1921 gate (in the new-set,
// not finished, not started, not the sounding talk), same fail-closed
// parsing, same dedupe by identity URL — the ONLY difference is no
// cap. A new talk with no cached title has no row, the same aged-out
// edge as the 1962 rail. Pure selection only: the Activity owns the
// catalog pass, the cache-file reads, the sounding skip's live URL
// and the Room open.
public class NewAll {
    static java.util.List<NewTalkRail.Card> pickAll(
            java.util.List<NewTalkRail.Entry> entries,
            java.util.Set<String> playedIds,
            java.util.Set<String> resumeKeys,
            String soundingUrl) {
        java.util.List<NewTalkRail.Card> out = new java.util.ArrayList<NewTalkRail.Card>();
        if (entries == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (NewTalkRail.Entry en : entries) {
            if (en == null) continue;
            if (en.sayadawName == null || en.sayadawName.length() == 0) continue;
            if (en.newIds == null || en.newIds.isEmpty()) continue;
            if (en.pairs == null) continue;
            for (String[] p : en.pairs) {
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
                out.add(new NewTalkRail.Card(new Prefs.HistEntry(p[1], en.sayadawName,
                        p[0].trim(), 0, 0)));
            }
        }
        return out;
    }
}
