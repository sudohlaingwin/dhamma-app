package com.h2h.dhamma;

// v15.74 (slice 2103): "Jump to the playing talk" on the
// Playlist detail screen — the Talks chooser's JumpPlaying
// (v15.55, slice 1994) + the library's DlJumpPlaying (v15.57,
// slice 2002) + Favourites' FavJumpPlaying (v15.67, slice
// 2060) + New talks' NewJumpPlaying (v15.67, slice 2061) +
// Bookmarks' BmJumpPlaying (v15.67, slice 2062) + History's
// HistJumpPlaying (v15.67, slice 2063), on the seventh and
// last list surface. The detail rows carry the sounding gold
// mark since v13.55, but a listener who scrolled away from
// the marked row had no visible way back: this screen has no
// jump of any kind, and the header is off-screen exactly when
// the jump is needed. Pure index logic only: the Activity
// owns the button, the scroll listener, the glide and the
// fade; the core never touches Android, prefs, or the queue.
public class PlJumpPlaying {
    // The shown-list index of the sounding talk: the first
    // row whose talk matches EITHER sounding identity — the
    // itemRow mark rule (v13.55, the Talks v4.89/v11.07
    // rules) verbatim, minus the text: a Room session names
    // the talk by URL (sameTalk, so http/https twins are one
    // talk, the v13.59 rule), a library session names it by
    // the dlMap reverse URL (sameTalk, exactly-one claimant,
    // v8.44). NO path leg: playlist items are stream-form by
    // construction — Prefs.playlistAdd canonicalises through
    // streamUrlFor at file time (the v12.79 both-forms rule),
    // so a path filed from the Room or the library lands as
    // its stream URL and a path identity would have nothing
    // to match. -1 while the talk has ended (the v11.15
    // gate: an ended talk is not playing, so its mark lifts
    // and there is nothing to jump to), while no identity is
    // live, or while no shown row matches. Null rows, null
    // entries and null URLs never match; the first match
    // wins — a talk sits once per list (playlistAdd refuses
    // a repeat), so the first match is its one row.
    static int findShownIndex(java.util.List<QueueEntry> shown,
            String roomUrl, String libUrl, boolean ended) {
        if (ended) return -1;
        if (shown == null) return -1;
        boolean hasRoom = roomUrl != null && roomUrl.length() > 0;
        boolean hasLibUrl = libUrl != null && libUrl.length() > 0;
        if (!hasRoom && !hasLibUrl) return -1;
        for (int i = 0; i < shown.size(); i++) {
            QueueEntry qe = shown.get(i);
            if (qe == null || qe.url == null) continue;
            if (hasRoom && SiteHttp.sameTalk(qe.url, roomUrl)) return i;
            if (hasLibUrl && SiteHttp.sameTalk(qe.url, libUrl)) return i;
        }
        return -1;
    }
}
