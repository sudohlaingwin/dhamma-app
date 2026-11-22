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
