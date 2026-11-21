package com.h2h.dhamma;

// v15.51 (slice 1974): "Shuffle-play shown listened talks" — the
// listened member of the Downloaded shuffle-shown family (v6.48
// shuffles the whole shown set, v6.79 shuffles the shown star /
// shown unlistened subsets, this shuffles the shown listened
// subset): the pure pick for shuffle-playing exactly the listened
// files among the shown rows. The library could shuffle-play a
// narrowed set (v6.48) and its starred / unlistened subsets
// (v6.79), but re-hearing just the finished talks in random order
// meant shuffling the whole shown set and skipping the
// unlistened files, or queueing the subset and shuffling the
// queue by hand. Pure set math only: the Activity owns the prefs
// read, the menu offer, the queue write, the Room open and the
// toast; the core never touches Android, prefs, or the queue.
// Membership is the rows' own listened cache (libListenedFiles,
// the v6.94 rule), so the offer gate and the dispatch handler —
// both calling this core — can never disagree about a file
// marked since the menu opened. Like the v6.47/v6.74 play pick
// (1967), the sounding file is NOT excluded from pick: the
// v6.48/v6.79 shuffle handler keeps it as the subset's first
// file and takes the stay-put branch (head-queue the rest behind
// it, no restart), so the core hands the handler the full
// listened subset and the handler applies the sounding rule; the
// offer gate's hasPlayable applies it for the menu.
public class DlShuffleListened {
    // One shown file is a listened member when its name sits in
    // the listened cache and its path is usable. The sounding
    // test is NOT here (see the class note): the handler needs
    // the sounding file in the subset for its stay-put branch.
    // Null-safe on every input: a null/blank name or path and a
    // null listened set all read as not-a-member, never a crash.
    public static boolean isListened(String name, String path, java.util.Set<String> listenedNames) {
        if (name == null || name.length() == 0) return false;
        if (path == null || path.length() == 0) return false;
        if (listenedNames == null || !listenedNames.contains(name)) return false;
        return true;
    }

    // The listened paths among the shown rows, in display order:
    // every shown name/path pair that passes isListened. The two
    // lists are parallel (names[i] is the file at paths[i]) and
    // truncated to the shorter, so a ragged caller can never
    // mis-pair a name with another file's path. A null list on
    // either side reads as nothing listened; the inputs are never
    // mutated, and duplicates are kept (the caller de-dupes
    // against the live queue, like the v6.79 rest loop).
    public static java.util.List<String> pick(java.util.List<String> shownNames, java.util.List<String> shownPaths, java.util.Set<String> listenedNames) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (shownNames == null || shownPaths == null) return out;
        int n = Math.min(shownNames.size(), shownPaths.size());
        for (int i = 0; i < n; i++) {
            String name = shownNames.get(i);
            String path = shownPaths.get(i);
            if (isListened(name, path, listenedNames)) out.add(path);
        }
        return out;
    }

    // The offer gate: at least one listened file other than the
    // file still sounding — the v6.79 gate, so the verb never
    // offers when its only member is already playing. A null or
    // empty sounding path means nothing sounds, so every
    // listened file is playable.
    public static boolean hasPlayable(java.util.List<String> shownNames, java.util.List<String> shownPaths, java.util.Set<String> listenedNames, String soundingPath) {
        if (shownNames == null || shownPaths == null) return false;
        int n = Math.min(shownNames.size(), shownPaths.size());
        for (int i = 0; i < n; i++) {
            String name = shownNames.get(i);
            String path = shownPaths.get(i);
            if (!isListened(name, path, listenedNames)) continue;
            if (soundingPath != null && soundingPath.length() > 0 && soundingPath.equals(path)) continue;
            return true;
        }
        return false;
    }
}
