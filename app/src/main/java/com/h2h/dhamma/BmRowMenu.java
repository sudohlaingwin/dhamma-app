package com.h2h.dhamma;

// v15.69 (slice 2072): Row long-press menu on the Bookmarks
// screen — the pure offer. The Bookmarks screen had search,
// sort, play-all, shuffle, jump, mark and strip, but no row menu
// at all: a listener could drop a mark only by leaving the
// screen (the Room bookmark list's ✕, v12.64, or the Bookmarks
// rail menu, v15.55 slice 1992), and nothing started a run
// mid-list. The menu opens with the two verbs the screen needs:
// "Play from here" (the chooser's v15.45 verb, History's v15.62
// mirror, Favourites' v15.68 mirror, New talks' v15.69 mirror)
// and "Delete bookmark" (the rail's own verb, v15.55 slice 1992
// — the bm_menu_remove key, the confirm naming the exact minute
// so a talk with several marks never deletes the wrong one).
// The offer is pure selection only: the Activity owns the dialog,
// the queue write, the confirm, the Prefs.bookmarkRemoveBoth call
// and the Room open. "Play from here" needs the row's RESOLVED
// stream URL (streamUrlFor — a path-form row resolves through the
// dlMap exactly-one-claimant rule, v8.44, or names no talk) with
// a sayadaw that is not sounding (the BmPlayAll sounding rule —
// Room URL / library reverse URL; the third identity of the 2041
// mark rule, the library path leg, lives in the RESOLUTION, not
// in this offer: a path-form row that sounds but cannot resolve
// has a blank resolved URL and offers nothing to play, while a
// path-form row that resolves sounds via the reverse-lookup URL
// leg, so the offer and the gold mark never disagree). "Delete
// bookmark" is always offered — the row IS a bookmark by
// construction — so an opened menu is never empty.
// v15.83 (slice 2159): "Queue from here" joins the menu through a separate pure leg — BmRowMenu.queueFromHereKey, NOT inside menuKeys: the gate is EXACTLY the play-from-here gate (canPlayFromHere — a resolved stream URL with a sayadaw, not sounding), which IS a pure function of the args menuKeys already carries — but the 2075/2077/2084/2086/2090/2093/2132/2135/2141 precedent inserts post-hoc keys in the Activity so the pinned menus stand, and the position is right after the queue key, not before remove (the queue verbs sit together — the chooser's order: queue add/remove, queue-from-here, play-from-here). The Activity inserts queueFromHereKey's result right after menu_queue_add / menu_queue_remove, so the display order reads Share, Minute, Copy, File, PlayFromHere, Queue, QueueFromHere, Go, Download, PlAdd, Mark, Remove. menuKeys itself is untouched: its pinned menus stand as written.
public class BmRowMenu {
    // True when this mark can start a run from here: a resolved
    // stream URL with a sayadaw, not sounding. Null/blank names
    // no talk.
    static boolean canPlayFromHere(String url, String sayadaw,
            String soundingUrl, String soundingLibUrl) {
        if (url == null || url.length() == 0) return false;
        if (url.startsWith("/")) return false;
        if (sayadaw == null || sayadaw.length() == 0) return false;
        if (BmPlayAll.isSounding(url, soundingUrl, soundingLibUrl)) return false;
        return true;
    }

    // v15.83 (slice 2159): True when this mark can queue the run below it: the gate is EXACTLY the play-from-here gate (canPlayFromHere — a resolved stream URL with a sayadaw, not sounding), because the run starts at the anchor; rows below that cannot queue are skipped by the helper, not by the offer. Null/blank reads as no-offer, never a crash.
    static boolean canQueueFromHere(String url, String sayadaw,
            String soundingUrl, String soundingLibUrl) {
        return canPlayFromHere(url, sayadaw, soundingUrl, soundingLibUrl);
    }

    // v15.83 (slice 2159): The queue-from-here key for this row, or null when it offers none: the menu_queue_from_here key the Activity inserts right after the queue key (menu_queue_add / menu_queue_remove). menuKeys itself carries NO queue-from-here key (see the header note) — its pinned menus stand as written.
    static String queueFromHereKey(String url, String sayadaw,
            String soundingUrl, String soundingLibUrl) {
        if (!canQueueFromHere(url, sayadaw, soundingUrl, soundingLibUrl)) return null;
        return "menu_queue_from_here";
    }

    // The menu's keys, in display order: "Play from here" first
    // when offered, "Delete bookmark" always. A fresh list per
    // call, never a shared one. The menu is never empty.
    static java.util.List<String> menuKeys(String url, String sayadaw,
            String soundingUrl, String soundingLibUrl) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (canPlayFromHere(url, sayadaw, soundingUrl, soundingLibUrl))
            out.add("menu_play_from_here");
        out.add("bm_menu_remove");
        return out;
    }
}
