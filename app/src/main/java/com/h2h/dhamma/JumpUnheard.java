package com.h2h.dhamma;

// v15.58 (slice 2005): "Jump to the first unheard talk" pick —
// the Talks list's floating gold ↓ button. The Continue row
// (1932) returns to the one in-progress talk and the Play-new
// row (1933) plays only ●-dotted new arrivals, but a listener
// who finished the first talks and started none of the rest had
// no visible way to the next fresh talk: the "New" filter
// narrows the list instead of gliding within it. This picks
// that talk: the first shown row that is neither finished, nor
// started past 5s, nor sounding — the 1920 dot gate (in the
// new-set, unfinished, unstarted, not sounding) minus the
// new-set membership, so the dot, the sheet count and this
// jump can never disagree about what "unheard" means, and an
// older unheard talk counts just like a new arrival. Pure index
// math only: the Activity owns the list, the resume map and the
// glide.
public class JumpUnheard {
    // The display position of the first unheard talk, in display
    // order, or -1 when none. `displayIds` is parallel to the
    // shown list (SiteHttp.identityUrl per talk, null where a
    // talk has none); `playedIds` holds the finished talks'
    // identityUrls (the raw contains, the PlayNewPick shape —
    // the cache is identity-normalized upstream, the DlMark
    // rule, so a legacy http twin banked in the set does not
    // match an https id, exactly as the row's own tick renders
    // it); `resumeMsById` maps identityUrl to the saved spot in
    // ms. Skips null ids, finished talks, started talks (resume
    // over PlayNewPick.MIN_RESUME_MS, 5000 — the getView ↺
    // threshold verbatim), and the sounding talk (either form).
    // The input lists are never mutated.
    static int firstIndex(java.util.List<String> displayIds,
            java.util.Set<String> playedIds,
            java.util.Map<String, Integer> resumeMsById,
            String soundingUrl, String soundingLibUrl) {
        if (displayIds == null) return -1;
        for (int i = 0; i < displayIds.size(); i++) {
            String id = displayIds.get(i);
            if (id == null) continue;
            if (playedIds != null && playedIds.contains(id)) continue;
            Integer ms = resumeMsById == null ? null : resumeMsById.get(id);
            if (ms != null && ms.intValue() > PlayNewPick.MIN_RESUME_MS) continue;
            if (soundingUrl != null && soundingUrl.length() > 0
                    && SiteHttp.sameTalk(id, soundingUrl)) continue;
            if (soundingLibUrl != null && soundingLibUrl.length() > 0
                    && SiteHttp.sameTalk(id, soundingLibUrl)) continue;
            return i;
        }
        return -1;
    }
}
