package com.h2h.dhamma;

// v15.47 (slice 1952): "Queue shown listened talks" — the
// queue-side twin of the slice-1951 delete-listened verb and the
// third member of the v6.40 queue-shown pair (star queues the
// starred, new queues the unlistened, this queues the listened):
// the pure pick for queueing exactly the listened files among
// the shown rows. The library could queue a narrowed set (v6.38)
// and its starred / unlistened subsets (v6.40), but re-hearing
// just the finished talks meant queueing the whole shown set
// and taking the unlistened files too, or queueing them one by
// one. Pure set math only: the Activity owns the prefs read, the
// menu offer, the queue write and the toast; the core never
// touches Android, prefs, or the queue. Membership is the rows'
// own listened cache (libListenedFiles, the v6.94 rule), so the
// offer gate and the dispatch handler — both calling pick — can
// never disagree about a file marked, played or queued since the
// menu opened.
public class DlQueueListened {
    // One shown file is queueable when its name sits in the
    // listened cache, its path is not the sounding path, and its
    // path is not already queued. The sounding test compares
    // PATHS (the v6.40 rule): a same-named file in another
    // folder is a different file, so it must still queue — the
    // 1951 name rule would wrongly skip it. Null-safe on every
    // input: a null/blank name or path, a null listened set and a
    // null queued collection all read as not-queueable / not
    // queued, never a crash.
    public static boolean isQueueable(String name, String path, java.util.Set<String> listenedNames, String soundingPath, java.util.Collection<String> queuedPaths) {
        if (name == null || name.length() == 0) return false;
        if (path == null || path.length() == 0) return false;
        if (listenedNames == null || !listenedNames.contains(name)) return false;
        if (soundingPath != null && soundingPath.length() > 0 && soundingPath.equals(path)) return false;
        if (queuedPaths != null && queuedPaths.contains(path)) return false;
        return true;
    }

    // The queueable paths, in display order: every shown
    // name/path pair that passes isQueueable. The two lists are
    // parallel (names[i] is the file at paths[i]) and truncated
    // to the shorter, so a ragged caller can never mis-pair a
    // name with another file's path. A null list on either side
    // reads as nothing queueable; the inputs are never mutated,
    // and duplicates are kept (the caller de-dupes against the
    // live queue, like the v6.40 loop).
    public static java.util.List<String> pick(java.util.List<String> shownNames, java.util.List<String> shownPaths, java.util.Set<String> listenedNames, String soundingPath, java.util.Collection<String> queuedPaths) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (shownNames == null || shownPaths == null) return out;
        int n = Math.min(shownNames.size(), shownPaths.size());
        for (int i = 0; i < n; i++) {
            String name = shownNames.get(i);
            String path = shownPaths.get(i);
            if (isQueueable(name, path, listenedNames, soundingPath, queuedPaths)) out.add(path);
        }
        return out;
    }
}
