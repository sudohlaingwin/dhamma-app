package com.h2h.dhamma;

// v15.45 (slice 1938): Downloaded empty-state Browse button —
// the empty library's way forward. Pure core (no Android):
// the button shows only for a truly empty library whose
// permission is held and with nothing downloading — the same
// three facts the empty word's own branch reads (a missing
// permission shows the permission line instead, and an
// in-progress download shows the ⏳ line; both keep the button
// hidden). A no-match search or an empty filter set never
// reaches this gate: those run the shownDl-empty branch, which
// hides the button outright.
public class DlEmptyCta {
    public static boolean visible(boolean libraryEmpty, boolean permissionHeld, boolean downloading) {
        return libraryEmpty && permissionHeld && !downloading;
    }
}
