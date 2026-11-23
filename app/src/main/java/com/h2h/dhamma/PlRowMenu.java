package com.h2h.dhamma;

// v15.74 (slice 2105): Row long-press menu on the Playlist detail
// screen — the pure offer. The Playlist detail screen had Play
// All, Shuffle, jump, back-to-top, a row tap (playFrom), a
// per-row remove, a strip, the v13.55 sounding gold mark and the
// empty state, but no row menu at all: a long-press on a detail
// row returned false, so a listener deep in a playlist could only
// play a talk or drop it — the menu opens with the two verbs the
// screen needs: "Play from here" (the chooser's v15.45 verb,
// History's v15.62 mirror, Favourites' v15.68 mirror, New talks'
// v15.69 mirror, Bookmarks' v15.69 mirror) and "Remove from list"
// (the screen's own removal verb, the per-row remove). The offer
// is pure selection only: the Activity owns the dialog, the queue
// write, the store remove and the Room open. "Play from here"
// needs a stream URL with a sayadaw that is not sounding (the
// FavPlayAll sounding rule — Room URL / library reverse URL; the
// ended gate: an ended talk is not playing, so its row offers
// play-from-here again — the PlJumpPlaying ended-param shape).
// A path-form row is a downloaded file, so it offers remove only,
// like History's menu. "Remove from list" is always offered —
// the row IS in this list by construction — so an opened menu is
// never empty.
public class PlRowMenu {
    // True when this row's talk is the sounding one — a Room
    // session (lastUrl) or a library session (dlMap reverse
    // lookup). An ended talk is not playing (the v11.15 gate),
    // so it never reads as sounding. Null/blank sounding URLs
    // name no talk.
    static boolean isSounding(String url, String soundingUrl,
            String soundingLibUrl, boolean ended) {
        if (ended) return false;
        if (url == null || url.length() == 0) return false;
        if (soundingUrl != null && soundingUrl.length() > 0
                && SiteHttp.sameTalk(url, soundingUrl)) return true;
        if (soundingLibUrl != null && soundingLibUrl.length() > 0
                && SiteHttp.sameTalk(url, soundingLibUrl)) return true;
        return false;
    }

    // True when this row can start a run from here: a stream URL
    // with a sayadaw, not sounding. Null/blank names no talk.
    static boolean canPlayFromHere(String url, String sayadaw,
            String soundingUrl, String soundingLibUrl, boolean ended) {
        if (url == null || url.length() == 0) return false;
        if (url.startsWith("/")) return false;
        if (sayadaw == null || sayadaw.length() == 0) return false;
        if (isSounding(url, soundingUrl, soundingLibUrl, ended)) return false;
        return true;
    }

    // The menu's keys, in display order: "Play from here" first
    // when offered, "Remove from list" always. A fresh list per
    // call, never a shared one. The menu is never empty.
    static java.util.List<String> menuKeys(String url, String sayadaw,
            String soundingUrl, String soundingLibUrl, boolean ended) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (canPlayFromHere(url, sayadaw, soundingUrl, soundingLibUrl, ended))
            out.add("menu_play_from_here");
        out.add("menu_pl_remove");
        return out;
    }
}
