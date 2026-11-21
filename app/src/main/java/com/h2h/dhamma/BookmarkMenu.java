package com.h2h.dhamma;

// v15.49 (slice 1961): bookmark-menu offer gates for the Room
// drawer sounding-row long-press dialog — premium backlog #1
// (user order 2026-11-06) restored to a working entry. v12.64
// built the store (Prefs.bookmarks64), the gold wave pins and
// the list dialog, but the clock-row add/list buttons were
// removed 2026-10-31–11-02 and onBookmarkAdd / showBookmarkList
// have had no caller since, so a listener could see pins but
// never add a mark or open the list. These two predicates are
// the only new logic: the dialog offers "Mark this minute" only
// while a track sounds with a real duration and a position past
// 0:00 (the onBookmarkAdd guard verbatim — the offer never
// promises what the tap refuses), and "Bookmarks" only while
// this talk already has marks (otherwise the dialog would only
// toast bm_none). Pure selection only: the Activity owns the
// service reads, the prefs read and the dialog.
public class BookmarkMenu {
    // A mark can only be added while a track is sounding with a
    // real duration and a position past 0:00 — onBookmarkAdd
    // refuses anything else with the no_talk_yet toast.
    public static boolean offerAdd(long durMs, long posMs) {
        return durMs > 0 && posMs > 0;
    }

    // The list dialog only opens while the sounding talk already
    // has marks — with none it would only toast bm_none.
    public static boolean offerList(int markCount) {
        return markCount > 0;
    }
}
