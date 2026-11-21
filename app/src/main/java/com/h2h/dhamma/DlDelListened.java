package com.h2h.dhamma;

// v15.47 (slice 1951): "Delete shown listened talks" — the third
// member of the subset-delete family (v6.39 delete shown, v6.94
// delete shown star / new): the pure pick for deleting exactly the
// listened files among the shown rows. The library could delete a
// narrowed set and its starred / unlistened subsets, but freeing
// space for just the finished talks meant deleting the whole
// narrowed set and re-downloading the rest, or deleting file by
// file. Pure set math only: the Activity owns the prefs read, the
// menu offer, the confirm dialog, the deletion and the toast; the
// core never touches Android, prefs, or the dlMap. Membership is
// the rows' own listened cache (libListenedFiles, the v6.94 rule),
// so the offer gate and the dispatch handler — both calling pick —
// can never disagree about a file marked since the menu opened.
public class DlDelListened {
    // The file name of a sounding path: the last '/' segment. A
    // null or empty path reads as no sounding file (""), so the
    // victim test below never skips a real file by accident.
    public static String soundName(String soundingPath) {
        if (soundingPath == null || soundingPath.length() == 0) return "";
        int slash = soundingPath.lastIndexOf('/');
        return slash < 0 ? soundingPath : soundingPath.substring(slash + 1);
    }

    // One shown file is a victim when its name sits in the listened
    // cache and it is not the sounding file (the v6.39/v6.94 rule:
    // the playing file is in use, so it is never deleted — a talk
    // that started playing while the dialog was open is re-checked
    // at confirm time through the same test). Null-safe on every
    // input: a null/blank name, a null listened set and a null or
    // empty sounding name all read as not-a-victim, never a crash.
    public static boolean isVictim(String name, java.util.Set<String> listenedNames, String soundingName) {
        if (name == null || name.length() == 0) return false;
        if (listenedNames == null || !listenedNames.contains(name)) return false;
        if (soundingName != null && soundingName.length() > 0 && soundingName.equals(name)) return false;
        return true;
    }

    // The victims, in display order: every shown name that passes
    // isVictim, with the sounding path resolved to its name once
    // here. A null shown list reads as no victims; the inputs are
    // never mutated, and duplicates are kept (the caller de-dupes
    // by file, like the v6.94 confirm loop).
    public static java.util.List<String> pick(java.util.List<String> shownNames, java.util.Set<String> listenedNames, String soundingPath) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (shownNames == null) return out;
        String sound = soundName(soundingPath);
        for (String n : shownNames) {
            if (isVictim(n, listenedNames, sound)) out.add(n);
        }
        return out;
    }
}
