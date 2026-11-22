package com.h2h.dhamma;

// v15.57 (slice 2002): "Jump to the playing file" index logic for
// the Downloaded library list — the library mirror of the Talks
// chooser's JumpPlaying (v15.55, slice 1994). The library list
// had no floating, always-reachable jump control: the header
// long-press (v4.15) and the fresh-open landing (v3.95) both jump
// to the playing file, but the header is off-screen exactly when
// the jump is needed, so a listener who scrolled away from the
// playing row had no visible way back. Pure index logic only: the
// Activity owns the button, the scroll listener, the glide and
// the fade; the core never touches Android, prefs, or the queue.
// Identity here is the bare absolute file path (exact String
// equality): the library queue holds paths, never URLs, so there
// are no http/https twins to normalise — the sameTalk scan
// belongs to the chooser's URL world, not this one. The shelf
// index and the display index are separate values because shownDl
// order differs from entries order under sort, and the queue-only
// view shows libQueueCache order — one index can never serve both
// the paint and the tap.
public class DlJumpPlaying {
    // The position of the live file in a path list (shelf or
    // display), or -1 — the v13.13 tap-time re-resolution shape
    // over exact paths. Null/empty live path never matches;
    // null entries never match.
    static int findIndex(java.util.List<String> paths, String livePath) {
        if (paths == null || livePath == null || livePath.length() == 0) return -1;
        for (int i = 0; i < paths.size(); i++) {
            String p = paths.get(i);
            if (p != null && p.equals(livePath)) return i;
        }
        return -1;
    }

    // True only when the target exists on the shelf but not in
    // the shown list — the tap's clear decision (the v12.54
    // rule): a shown target keeps the view, a hidden one clears
    // search/filter (and exits the queue-only view, where the
    // playing file is never queued — v4.58) first. Never true
    // without a shelf target.
    static boolean needsClear(int displayIdx, int shelfIdx) {
        return shelfIdx >= 0 && displayIdx < 0;
    }

    // True while the target row sits outside the visible
    // window (above or below it) — the button's only show rule
    // in the shown view. The window is inclusive at both ends;
    // -1 (no target) never shows. The caller passes a laid-out
    // window (last >= first); an unlaid-out window never
    // reaches this call.
    static boolean wantShown(int target, int first, int last) {
        return target >= 0 && (target < first || target > last);
    }

    // The paint decision: shown while a live shelf file exists
    // AND its row is either not in the shown list at all
    // (filtered out, or hidden by the queue-only view — the
    // tap clears first) or off-screen in the shown view. Never
    // shown without a shelf target (a streamed Room-session
    // talk has no shelf row) or while the row sits on-screen.
    static boolean showFor(int displayIdx, int shelfIdx, int first, int last) {
        return shelfIdx >= 0 && (displayIdx < 0 || wantShown(displayIdx, first, last));
    }
}
