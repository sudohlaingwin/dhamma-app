package com.h2h.dhamma;

// v15.74 (slice 2101): "Add to playlist" on the Talks chooser dialog —
// the playlist verb the chooser dialog lacked that the Room
// already carries as a dialog (showPlaylistAddDialog, v12.65–
// v12.80) — a dialog that finally has a live entry point since
// the Room queue bar (2096); the row menus carry the verb on
// six surfaces (Favourites 2091, New talks 2092, Bookmarks
// 2093, History 2095, Room bar 2096, Downloaded library 2097)
// but a listener browsing a sayadaw's talks could never file
// one in their own lists (morning / bedtime — the seed lists
// Prefs seeds on first read, premium backlog #2) without
// opening the Room first. The Room's flow is mirrored, not
// copied: the Activity owns the dialog and the store calls
// (Prefs.playlistNames / playlistAdd / playlistCreate); the
// core owns only the pure decisions — the offer gate, the
// new-list row test, the v12.80 result-toast mapping and the
// create-failure adjudication — so the two surfaces can never
// disagree on what a result means. Pure string math only: the
// core never touches Android, assets, prefs, or the queue.
public class ChooserPlAdd {
    // The dialog offers the add verb only while it resolves to a
    // stream URL - a blank URL offers nothing, never a dead tap
    // (the chooser's share gate, same non-blank URL the share, the
    // copy and the file all read).
    static boolean canOffer(String streamUrl) {
        return streamUrl != null && streamUrl.length() > 0;
    }
    // The tap is on the "new list" row exactly when it is the
    // last row (names.size() — the Activity appends the pl_new
    // label after the names, so the row index is the count).
    static boolean isNewRow(int which, int namesSize) {
        return which == namesSize;
    }
    // The toast for a playlistAdd result (Prefs.playlistAdd
    // codes, the v12.80 mapping the Room's dialog uses): 0
    // added, 1 already in the list, -2 full (200 talks),
    // anything else a create failure.
    static String toastKey(int r) {
        if (r == 0) return "pl_added";
        if (r == 1) return "pl_exists";
        if (r == -2) return "pl_full";
        return "pl_no_create";
    }
    // The create-result adjudication (the Room's v12.80 leg):
    // a create that succeeded proceeds to the add (null); a
    // create that failed on a repeat name still adds to the
    // existing list (null — create is a no-op for an existing
    // name); a create that failed on a genuinely new name at
    // the 20-list cap toasts pl_limit, any other create
    // failure pl_no_create — neither falls through to the add.
    static String limitToastKey(boolean created, boolean nameHeld, int heldSize) {
        if (created) return null;
        if (nameHeld) return null;
        return heldSize >= 20 ? "pl_limit" : "pl_no_create";
    }
}
