package com.h2h.dhamma;

// v15.75 (slice 2111): Lists-mode long-press menu on the
// Playlist screen — the pure offer. The lists screen had
// New (create), Delete (confirm) and tap-to-open, but no
// rename anywhere in the app: a long-press on a list row
// jumped straight to the delete confirm, so a listener who
// only wanted to fix a typo had to delete the list and
// re-add every talk. The menu opens with the three verbs the
// screen needs: "Rename" first (the constructive verb, the
// PlRowMenu order), "Copy" second (a whole-list copy — the
// v15.76 slice 2114 verb), and "Delete" last (the screen's
// own delete verb, the confirmDeleteList flow). The offer is
// pure selection only: the Activity owns the dialog, the
// rename dialog, the store rename and the delete confirm.
// A blank name names no list, so it offers nothing — the
// menu is never empty on a real row (names come from
// Prefs.playlistNames, always clean and non-empty).
public class PlListMenu {
    // True when this name can be renamed: a real list name.
    // Null, blank, or whitespace-only names name no list.
    // (Prefs.playlistClean is private, so the check is the
    // same trim rule inline: leading/trailing whitespace
    // covers spaces, tabs and newlines.)
    static boolean canRename(String name) {
        if (name == null) return false;
        return name.trim().length() > 0;
    }

    // The menu's keys, in display order: "Rename" first,
    // "Copy" second, "Delete" last. A fresh list per call,
    // never a shared
    // one. Empty when the name names no list.
    static java.util.List<String> menuKeys(String name) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (!canRename(name)) return out;
        out.add("menu_pl_rename");
        out.add("menu_pl_copy");
        out.add("pl_delete_btn");
        return out;
    }
}
