package com.h2h.dhamma;

// v15.44 (slice 1933): "Play new" pick — the one-tap play of this
// sayadaw's new (●-dotted) talks. Play All (v13.76) queues the
// whole shown list and Shuffle (v13.93) shuffles it, but the dot
// family (1920-1926) marked new talks without ever offering to
// play just them: a listener with three new talks still had to
// Play All from the top (replaying finished talks first) or tap
// each dotted row by hand. Spotify / YouTube Music podcast pages
// have the same verb ("play new episodes"); this picks exactly
// the talks wearing the dot, in shown order. Pure index math
// only: the Activity owns the list, the resume map and the Room
// open.
public class PlayNewPick {
    // The row ↺ rule's threshold (TalksActivity getView, 1920):
    // a spot at or under 5s is a fresh start — and a dotted talk
    // is by definition unstarted, so a resume over the threshold
    // removes it from the pick.
    static final int MIN_RESUME_MS = 5000;

    // The display positions of the talks to play, in display
    // order, or an empty list when none. `displayIds` is parallel
    // to the shown list (SiteHttp.identityUrl per talk, null where
    // a talk has none); `newIds` is the page's 1920 new-set;
    // `playedIds` holds the finished talks' identityUrls;
    // `resumeMsById` maps identityUrl to the saved spot in ms.
    // Skips null ids, ids outside the new-set, finished talks,
    // started talks (resume over the threshold), and the sounding
    // talk (either form). The input lists are never mutated.
    static java.util.List<Integer> pick(
            java.util.List<String> displayIds,
            java.util.Set<String> newIds,
            java.util.Set<String> playedIds,
            java.util.Map<String, Integer> resumeMsById,
            String soundingUrl,
            String soundingLibUrl) {
        java.util.List<Integer> out = new java.util.ArrayList<Integer>();
        if (displayIds == null || newIds == null || newIds.isEmpty()) return out;
        for (int i = 0; i < displayIds.size(); i++) {
            String id = displayIds.get(i);
            if (id == null) continue;
            if (!newIds.contains(id)) continue;
            if (playedIds != null && playedIds.contains(id)) continue;
            Integer ms = resumeMsById == null ? null : resumeMsById.get(id);
            if (ms != null && ms.intValue() > MIN_RESUME_MS) continue;
            if (soundingUrl != null && soundingUrl.length() > 0
                    && SiteHttp.sameTalk(id, soundingUrl)) continue;
            if (soundingLibUrl != null && soundingLibUrl.length() > 0
                    && SiteHttp.sameTalk(id, soundingLibUrl)) continue;
            out.add(Integer.valueOf(i));
        }
        return out;
    }
}
