package com.h2h.dhamma;

// v15.69 (slice 2071): Row long-press menu on the New talks
// screen — the pure offer. The New talks screen had search,
// sort, play-all, shuffle, jump, mark and strip, but no row menu
// at all: a listener could star a new talk only by leaving the
// screen, and nothing started a run mid-list. The menu opens with
// the two verbs the screen needs: "Play from here" (the chooser's
// v15.45 verb, History's v15.62 mirror, Favourites' v15.68 mirror)
// and the state-dependent star verb — "Add to favourites" when
// the talk is unstarred, "Remove from favourites" when it is
// starred (the chooser 1969 / library / History 1267 shape). The
// offer is pure selection only: the Activity owns the dialog, the
// queue write, the star flip and the Room open. "Play from here"
// needs a stream URL with a sayadaw that is not sounding (the
// NewPlayAll sounding rule — all THREE identities of the 2043
// mark rule, so the button and the gold mark never disagree
// about what sounds); a path-form row is a downloaded file, so
// it offers the star verb only, like History's menu. The star
// verb is always offered on a row with a URL — the row's
// starred state is the Activity's to read (readFavTalks +
// sameTalk), so the core takes it as a plain boolean. A null or
// blank URL names no talk at all, so the offer is empty — the
// Activity never opens the menu on such a row, so an opened
// menu is never empty.
// v15.84 (slice 2162): "Queue from here" joins the menu through a separate pure leg — NewRowMenu.queueFromHereKey, NOT inside menuKeys: the gate is EXACTLY the play-from-here gate (canPlayFromHere — a stream URL with a sayadaw, not sounding), which IS a pure function of the args menuKeys already carries — but the 2074/2079/2080/2081/2087/2092/2136/2138 precedent inserts post-hoc keys in the Activity so the pinned menus stand, and the position is right after the queue key, not before the star (the queue verbs sit together — the chooser's order: queue add/remove, queue-from-here, play-from-here). The Activity inserts queueFromHereKey's result right after menu_queue_add / menu_queue_remove, so the display order reads Share, Copy, File, Queue, QueueFromHere, PlayFromHere, Go, Download, PlAdd, Star, Mark. menuKeys itself is untouched: its pinned menus stand as written.
public class NewRowMenu {
    // True when this card can start a run from here: a stream URL
    // with a sayadaw, not sounding. Null/blank names no talk.
    static boolean canPlayFromHere(String url, String sayadaw,
            String soundingUrl, String soundingLibUrl,
            String soundingLibPath) {
        if (url == null || url.length() == 0) return false;
        if (url.startsWith("/")) return false;
        if (sayadaw == null || sayadaw.length() == 0) return false;
        if (NewPlayAll.isSounding(url, soundingUrl, soundingLibUrl,
                soundingLibPath)) return false;
        return true;
    }

    // v15.84 (slice 2162): True when this card can queue the run below it: the gate is EXACTLY the play-from-here gate (canPlayFromHere — a stream URL with a sayadaw, not sounding), because the run starts at the anchor; rows below that cannot queue are skipped by the helper's pick, not by the offer. Null/blank reads as no-offer, never a crash.
    static boolean canQueueFromHere(String url, String sayadaw,
            String soundingUrl, String soundingLibUrl,
            String soundingLibPath) {
        return canPlayFromHere(url, sayadaw, soundingUrl, soundingLibUrl,
                soundingLibPath);
    }

    // v15.84 (slice 2162): The queue-from-here key for this row, or null when it offers none: the menu_queue_from_here key the Activity inserts right after the queue key (menu_queue_add / menu_queue_remove). menuKeys itself carries NO queue-from-here key (see the header note) — its pinned menus stand as written.
    static String queueFromHereKey(String url, String sayadaw,
            String soundingUrl, String soundingLibUrl,
            String soundingLibPath) {
        if (!canQueueFromHere(url, sayadaw, soundingUrl, soundingLibUrl,
                soundingLibPath)) return null;
        return "menu_queue_from_here";
    }

    // The menu's keys, in display order: "Play from here" first
    // when offered, then the star verb for the row's current state
    // — "Add to favourites" when unstarred, "Remove from
    // favourites" when starred. A fresh list per call, never a
    // shared one. Empty only for a null/blank URL, which the
    // Activity never opens a menu on.
    static java.util.List<String> menuKeys(String url, String sayadaw,
            String soundingUrl, String soundingLibUrl,
            String soundingLibPath, boolean starred) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (url == null || url.length() == 0) return out;
        if (canPlayFromHere(url, sayadaw, soundingUrl, soundingLibUrl,
                soundingLibPath))
            out.add("menu_play_from_here");
        out.add(starred ? "menu_fav_remove" : "menu_fav_add");
        return out;
    }
}
