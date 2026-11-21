package com.h2h.dhamma;

// v15.43 (slice 1932): "Continue listening" pick — the one-tap
// return to this sayadaw's in-progress talk. The resume strip
// (v4.60) covers one talk, the dead session's, globally; the
// Recently-played rail (1930) covers the last five across
// sayadaws; this picks the most recently listened talk ON THIS
// PAGE that still has a saved spot. Pure index math only: the
// Activity owns the list, the resume map and the Room open.
public class ContinuePick {
    // The row ↺ rule's threshold (TalksActivity getView): a spot
    // at or under 5s is a fresh start, not a return.
    static final int MIN_RESUME_MS = 5000;

    // The page index of the talk to continue, or -1 when none.
    // `recent` is most-recent-first (Prefs.statsRecent); `pageIds`
    // is parallel to the page list (SiteHttp.identityUrl per talk,
    // null where a talk has none); `resumeMsById` maps identityUrl
    // to the saved spot in ms; `playedIds` holds the finished
    // talks' identityUrls. Skips null entries, blank urls, urls
    // not on this page, the sounding talk (either form), finished
    // talks, and talks with no saved spot over the threshold.
    // The first surviving entry wins; the input lists are never
    // mutated.
    static int pick(
            java.util.List<Prefs.HistEntry> recent,
            java.util.List<String> pageIds,
            java.util.Map<String, Integer> resumeMsById,
            java.util.Set<String> playedIds,
            String soundingUrl,
            String soundingLibUrl) {
        if (recent == null || pageIds == null || pageIds.isEmpty()) return -1;
        for (Prefs.HistEntry e : recent) {
            if (e == null) continue;
            if (e.url == null || e.url.length() == 0) continue;
            String id = SiteHttp.identityUrl(e.url);
            if (id == null) continue;
            int idx = pageIds.indexOf(id);
            if (idx < 0) continue;
            if (soundingUrl != null && soundingUrl.length() > 0
                    && SiteHttp.sameTalk(e.url, soundingUrl)) continue;
            if (soundingLibUrl != null && soundingLibUrl.length() > 0
                    && SiteHttp.sameTalk(e.url, soundingLibUrl)) continue;
            if (playedIds != null && playedIds.contains(id)) continue;
            Integer ms = resumeMsById == null ? null : resumeMsById.get(id);
            if (ms == null || ms.intValue() <= MIN_RESUME_MS) continue;
            return idx;
        }
        return -1;
    }
}
