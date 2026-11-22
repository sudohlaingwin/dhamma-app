package com.h2h.dhamma;

// v15.55 (slice 1994): "Jump to the playing talk" — the Talks
// list's floating gold ▶ button that glides to the sounding
// talk when it has scrolled off-screen. The jump already existed
// three ways, all hidden or conditional — the title long-press
// (v4.15), the fresh-open landing (v3.91), the return auto-glide
// (v12.54, only on return and only when the playing URL changed)
// — but while browsing with a live session, a listener who
// scrolled away from the playing row had no visible way back:
// the title is off-screen exactly when the jump is needed, the
// same visible, reachable-form class as the 1940 back-to-top
// button. Pure index logic only: the Activity owns the button,
// the scroll listener, the glide and the fade; the core never
// touches Android, prefs, or the queue.
public class JumpPlaying {
    // The full-list index of the live talk: a sameTalk scan
    // over the talk URLs (http/https twins are one talk, the
    // v13.59 rule), so a TLS-downgraded session still finds
    // its row. -1 while the live URL is empty or no row
    // matches it; null entries never match.
    static int findFullIndex(java.util.List<String> urls, String liveUrl) {
        if (urls == null || liveUrl == null || liveUrl.length() == 0) return -1;
        for (int i = 0; i < urls.size(); i++) {
            String u = urls.get(i);
            if (u != null && SiteHttp.sameTalk(u, liveUrl)) return i;
        }
        return -1;
    }

    // The display position of a full-list index: the v12.54
    // precedence — a search/filter narrows the shown list, so
    // a shown talk glides to its display position; -1 when
    // the talk is filtered out of the shown list (the tap
    // clears search/filter first, then the full index means
    // something again) or the index itself is -1.
    static int displayIndex(java.util.List<Integer> visibleHits, int fullIdx) {
        if (fullIdx < 0) return -1;
        if (visibleHits == null) return fullIdx;
        return visibleHits.indexOf(Integer.valueOf(fullIdx));
    }

    // True only when the target is filtered OUT of the shown
    // list — the tap's clear decision (the v12.54 rule): a
    // shown target keeps the view, a hidden one clears
    // search/filter first. Never true without a filter view
    // or without a target.
    static boolean needsClear(java.util.List<Integer> visibleHits, int fullIdx) {
        return visibleHits != null && fullIdx >= 0
                && !visibleHits.contains(Integer.valueOf(fullIdx));
    }

    // True while the target row sits outside the visible
    // window (above or below it) — the button's only show
    // rule. The window is inclusive at both ends; -1 (no
    // target) never shows. The caller passes a laid-out
    // window (last >= first); an unlaid-out window never
    // reaches this call.
    static boolean wantShown(int target, int first, int last) {
        return target >= 0 && (target < first || target > last);
    }
}
