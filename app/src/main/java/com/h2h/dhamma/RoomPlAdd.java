package com.h2h.dhamma;

// v15.73 (slice 2096): "Add to playlist" on the Room queue bar —
// the playlist verb the Room's queue bar lacked that the Room
// already carries as a dialog (showPlaylistAddDialog, v12.65–
// v12.80) — a dialog with no entry point anywhere in the app
// (defined once, called zero times; the pl_add Lang key is used
// nowhere in RoomActivity — its two "pl_add" hits are the
// pl_added result toasts inside the dialog itself), so a listener
// hearing a talk right now could file it in one of their own
// lists only by leaving the Room for a row menu — never from
// the Room itself. The dialog owns the choose-list + new-list
// flow and the store calls (Prefs.playlistNames / playlistAdd /
// playlistCreate); the core owns only the pure decisions — the
// button label key, the tap gate (the sounding talk must exist)
// and the no-talk toast key — so the Room and the four row-menu
// offers can never disagree on what the verb means. Pure
// string math only: the core never touches Android, assets,
// prefs, or the queue.
public class RoomPlAdd {
    // The button's label key — the same pl_add the four
    // row-menu offers use, so the verb reads identically
    // everywhere (Lang.t carries the ➕ in the value).
    static String labelKey() {
        return "pl_add";
    }
    // The tap files the sounding talk only while it exists — a
    // blank URL or a blank sayadaw files nothing, never a dead
    // tap (the dialog's own guard, mirrored here so the tap
    // decides before the dialog opens).
    static boolean canOffer(String url, String sayadaw) {
        return url != null && url.length() > 0
                && sayadaw != null && sayadaw.length() > 0;
    }
    // The toast when the tap has no sounding talk to file.
    static String guardKey() {
        return "no_talk_yet";
    }
}
