package com.h2h.dhamma;

// v15.67 (slice 2063): "Jump to the playing talk" on the
// History screen — the Talks chooser's JumpPlaying (v15.55,
// slice 1994) + the library's DlJumpPlaying (v15.57, slice 2002)
// + Favourites' FavJumpPlaying (v15.67, slice 2060) + New talks'
// NewJumpPlaying (v15.67, slice 2061) + Bookmarks' BmJumpPlaying
// (v15.67, slice 2062), on the sixth and last list surface.
// The History rows carry the sounding gold mark since slice
// v13.55, but a listener who scrolled away from the marked row
// had no visible way back: this screen has no jump of any kind,
// and the stat cards are off-screen exactly when the jump is
// needed. Pure index logic only: the Activity owns the button,
// the scroll listener, the glide and the fade; the core never
// touches Android, prefs, or the queue.
public class HistJumpPlaying {
    // The shown-list index of the sounding talk: the first row
    // whose talk matches ANY of the three sounding identities —
    // the itemRow mark rule (v13.55, the Talks v4.89/v11.07
    // rules) verbatim, minus the text: a Room session names the
    // talk by URL (sameTalk, so http/https twins are one talk,
    // the v13.59 rule), a library session names it by the dlMap
    // reverse URL (sameTalk, exactly-one claimant, v8.44) or
    // directly by path (exact equality — a path-form row IS the
    // file, playRecent v12.82). -1 while the talk has ended (the
    // v11.15 gate: an ended talk is not playing, so its mark lifts
    // and there is nothing to jump to), while no identity is live,
    // or while no shown row matches. Null rows and null URLs never
    // match; the first match wins, so on this screen — where a
    // talk listened to twice is two rows, each jumping to its own
    // saved spot — the jump lands on the FIRST SHOWN LISTEN of
    // the sounding talk, in display order (most recent first).
    static int findShownIndex(java.util.List<Prefs.HistEntry> shown,
            String roomUrl, String libUrl, String libPath, boolean ended) {
        if (ended) return -1;
        if (shown == null) return -1;
        boolean hasRoom = roomUrl != null && roomUrl.length() > 0;
        boolean hasLibUrl = libUrl != null && libUrl.length() > 0;
        boolean hasLibPath = libPath != null && libPath.length() > 0;
        if (!hasRoom && !hasLibUrl && !hasLibPath) return -1;
        for (int i = 0; i < shown.size(); i++) {
            Prefs.HistEntry r = shown.get(i);
            if (r == null || r.url == null) continue;
            if (hasRoom && SiteHttp.sameTalk(r.url, roomUrl)) return i;
            if (hasLibUrl && SiteHttp.sameTalk(r.url, libUrl)) return i;
            if (hasLibPath && r.url.equals(libPath)) return i;
        }
        return -1;
    }
}
