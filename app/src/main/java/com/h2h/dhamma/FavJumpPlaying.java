package com.h2h.dhamma;

// v15.67 (slice 2060): "Jump to the playing talk" on the
// Favourites screen — the Talks chooser's JumpPlaying (v15.55,
// slice 1994) + the library's DlJumpPlaying (v15.57, slice 2002),
// on the third list surface. The Favourites rows carry the
// sounding gold mark since slice 2042, but a listener who scrolled
// away from the marked row had no visible way back: this screen
// has no jump of any kind, and the title is off-screen exactly
// when the jump is needed. Pure index logic only: the Activity
// owns the button, the scroll listener, the glide and the fade;
// the core never touches Android, prefs, or the queue.
public class FavJumpPlaying {
    // The shown-list index of the sounding talk: the first row
    // whose talk matches ANY of the three sounding identities —
    // the itemRow mark rule (v15.64, slice 2042) verbatim, minus
    // the text: a Room session names the talk by URL (sameTalk,
    // so http/https twins are one talk, the v13.59 rule), a
    // library session names it by the dlMap reverse URL (sameTalk)
    // or directly by path (exact equality — a path-form row IS the
    // file, playRecent v12.82). -1 while the talk has ended (the
    // v11.15 gate: an ended talk is not playing, so its mark lifts
    // and there is nothing to jump to), while no identity is live,
    // or while no shown row matches. Null rows, null entries and
    // null URLs never match; the first match wins, so a talk listed
    // once (one row per talk on this screen) jumps to its one row.
    static int findShownIndex(java.util.List<FavTalkRail.Card> shown,
            String roomUrl, String libUrl, String libPath, boolean ended) {
        if (ended) return -1;
        if (shown == null) return -1;
        boolean hasRoom = roomUrl != null && roomUrl.length() > 0;
        boolean hasLibUrl = libUrl != null && libUrl.length() > 0;
        boolean hasLibPath = libPath != null && libPath.length() > 0;
        if (!hasRoom && !hasLibUrl && !hasLibPath) return -1;
        for (int i = 0; i < shown.size(); i++) {
            FavTalkRail.Card c = shown.get(i);
            if (c == null || c.e == null || c.e.url == null) continue;
            if (hasRoom && SiteHttp.sameTalk(c.e.url, roomUrl)) return i;
            if (hasLibUrl && SiteHttp.sameTalk(c.e.url, libUrl)) return i;
            if (hasLibPath && c.e.url.equals(libPath)) return i;
        }
        return -1;
    }
}
