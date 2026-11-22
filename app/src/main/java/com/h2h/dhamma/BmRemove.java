package com.h2h.dhamma;

// v15.55 (slice 1992): "Remove this bookmark" — the Bookmarks rail
// quick-actions menu's destructive verb (the scout-closed candidate
// from slice 1989). The rail had the four-verb menu since v15.54
// (slice 1989) but no way to drop a mark from Main: the only delete
// lived in the Room bookmark list's ✕ (v12.64), so a listener who
// had finished with a marked minute had to open the Room, open the
// list, and find the row again. Pure text only: the Activity owns
// the menu offer, the confirm dialog, the Prefs.bookmarkRemoveBoth
// call, the rail refresh and the bm_deleted toast; the core never
// touches Android, prefs, or the queue.
public class BmRemove {
    // The confirm message: the card's own "12:34 · title" rendering
    // (BmRail.fmtTime, M:SS / H:MM:SS past the hour) — the message
    // names the exact minute, so a talk with several marks never
    // deletes the wrong one. Blank title falls back to the url (the
    // menu title's own fallback); a null url falls back to "" so a
    // blank card can never crash the dialog. The title passes
    // through untouched otherwise, so what the confirm shows is
    // exactly what the card shows.
    static String confirmMessage(int posMs, String title, String url) {
        String t = title != null && title.length() > 0 ? title
                : (url == null ? "" : url);
        return BmRail.fmtTime(posMs) + " \u00b7 " + t;
    }
}
