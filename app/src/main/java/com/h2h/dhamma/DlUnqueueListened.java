package com.h2h.dhamma;

// v15.50 (slice 1968): "Remove shown listened talks from queue" —
// the fourth member of the Downloaded unqueue family (v6.41
// un-queues the whole shown set, v7.00 un-queues the shown star /
// shown unlistened subsets, this un-queues the shown listened
// subset): the pure pick for removing exactly the listened files
// among the shown rows from the live queue. The library could
// un-queue a narrowed set (v6.41) and its starred / unlistened
// subsets (v7.00), and could queue (1952), queue-next (1953)
// and play (1967) just the listened files — but clearing just
// the finished talks out of the queue meant removing the whole
// shown set (which also drops unlistened files still waiting) or
// un-queueing row by row. Pure set math only: the Activity owns
// the prefs read, the menu offer, the queue write and the toast;
// the core never touches Android, prefs, or the queue.
// Membership is the rows' own listened cache (libListenedFiles,
// the v6.94 rule) AND the live queue, so the offer gate and the
// dispatch handler — both calling this core — can never
// disagree about a file marked, played or queued since the menu
// opened. Unlike the queue pick (1952), the queue test is
// INVERTED here: only a file already in the queue can be
// removed. The sounding test is belt-and-braces (the playing
// file never queues) and mirrors the v7.00 handler's explicit
// cur skip.
public class DlUnqueueListened {
    // One shown file is removable when its name sits in the
    // listened cache, its path is usable, its path IS in the
    // live queue, and its path is not the sounding path. The
    // sounding test compares PATHS (the v6.40 rule): a
    // same-named file in another folder is a different file, so
    // it must still un-queue — the 1951 name rule would wrongly
    // skip it. Null-safe on every input: a null/blank name or
    // path, a null listened set and a null queued collection all
    // read as not-removable / not queued, never a crash.
    public static boolean isRemovable(String name, String path, java.util.Set<String> listenedNames, String soundingPath, java.util.Collection<String> queuedPaths) {
        if (name == null || name.length() == 0) return false;
        if (path == null || path.length() == 0) return false;
        if (listenedNames == null || !listenedNames.contains(name)) return false;
        if (queuedPaths == null || !queuedPaths.contains(path)) return false;
        if (soundingPath != null && soundingPath.length() > 0 && soundingPath.equals(path)) return false;
        return true;
    }

    // The removable paths among the shown rows, in display
    // order: every shown name/path pair that passes
    // isRemovable. The two lists are parallel (names[i] is the
    // file at paths[i]) and truncated to the shorter, so a ragged
    // caller can never mis-pair a name with another file's path.
    // A null list on either side, a null listened set or a null
    // queue all read as nothing removable; the inputs are never
    // mutated, and duplicates are kept (the caller removes one
    // queue occurrence per duplicate entry, so the count stays
    // exact).
    public static java.util.List<String> pick(java.util.List<String> shownNames, java.util.List<String> shownPaths, java.util.Set<String> listenedNames, String soundingPath, java.util.Collection<String> queuedPaths) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (shownNames == null || shownPaths == null) return out;
        int n = Math.min(shownNames.size(), shownPaths.size());
        for (int i = 0; i < n; i++) {
            String name = shownNames.get(i);
            String path = shownPaths.get(i);
            if (isRemovable(name, path, listenedNames, soundingPath, queuedPaths)) out.add(path);
        }
        return out;
    }

    // The offer gate: at least one removable file — the v7.00
    // gate, so the verb never offers when the shown listened
    // files are all unqueued already. A null or empty sounding
    // path means nothing sounds, so every listened + queued file
    // is removable.
    public static boolean hasRemovable(java.util.List<String> shownNames, java.util.List<String> shownPaths, java.util.Set<String> listenedNames, String soundingPath, java.util.Collection<String> queuedPaths) {
        return !pick(shownNames, shownPaths, listenedNames, soundingPath, queuedPaths).isEmpty();
    }
}
