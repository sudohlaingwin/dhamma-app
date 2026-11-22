package com.h2h.dhamma;

// v15.68 (slice 2069): Row long-press menu on the Favourites
// screen — the pure offer. The Favourites screen had search,
// sort, play-all, shuffle, jump, mark and strip, but no row menu
// at all: a listener could un-star a talk only by leaving the
// screen, and nothing started a run mid-list. The menu opens with
// the two verbs the screen needs: "Play from here" (the chooser's
// v15.45 verb, History's v15.62 mirror) and "Remove from
// favourites" (the screen's own removal verb). The offer is pure
// selection only: the Activity owns the dialog, the queue write,
// the star flip and the Room open. "Play from here" needs a stream
// URL with a sayadaw that is not sounding (either session form —
// the FavPlayAll sounding rule); a path-form row is a downloaded
// file, so it offers remove only, like History's menu. "Remove
// from favourites" is always offered — the row IS a favourite by
// construction; the handler re-checks the live star at tap time,
// so a dialog left open across an un-star elsewhere can never
// re-add it.
public class FavRowMenu {
    // True when this card can start a run from here: a stream URL
    // with a sayadaw, not sounding. Null/blank names no talk.
    static boolean canPlayFromHere(String url, String sayadaw,
            String soundingUrl, String soundingLibUrl) {
        if (url == null || url.length() == 0) return false;
        if (url.startsWith("/")) return false;
        if (sayadaw == null || sayadaw.length() == 0) return false;
        if (FavPlayAll.isSounding(url, soundingUrl, soundingLibUrl)) return false;
        return true;
    }

    // The menu's keys, in display order: "Play from here" first
    // when offered, "Remove from favourites" always. A fresh list
    // per call, never a shared one.
    static java.util.List<String> menuKeys(String url, String sayadaw,
            String soundingUrl, String soundingLibUrl) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (canPlayFromHere(url, sayadaw, soundingUrl, soundingLibUrl))
            out.add("menu_play_from_here");
        out.add("menu_fav_remove");
        return out;
    }
}
