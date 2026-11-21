package com.h2h.dhamma;

// v15.48 (slice 1953): "Queue shown listened talks next" — the
// head-insert twin of the slice-1952 append verb and the third
// member of the v6.71 queue-next pair (star queues the starred
// next, new queues the unlistened next, this queues the listened
// next): the pure pick + head-insert for putting exactly the
// listened files among the shown rows right after the file now
// playing. The library could append the listened subset (1952)
// and head-insert the whole shown set (v6.68) or its starred /
// unlistened subsets (v6.71), but putting just the shown
// listened files next meant appending the subset and then moving
// each file to the top one by one. Pure set math + one list
// splice only: the Activity owns the prefs read, the menu offer,
// the queue write and the toast; the core never touches Android,
// prefs, or the queue. Membership is the rows' own listened
// cache (libListenedFiles, the v6.94 rule), so the offer gate
// and the dispatch handler — both calling pick — can never
// disagree about a file marked, played or queued since the menu
// opened, and the two twins (1952 append, this head-insert)
// share one membership rule by delegating to DlQueueListened.
public class DlQueueNextListened {
    // One shown file is queueable-next when its name sits in the
    // listened cache, its path is not the sounding path, and its
    // path is not already queued — the v6.71 gate, identical to
    // the 1952 append gate by delegation, so the twins can never
    // disagree. The sounding test compares PATHS (the v6.40
    // rule): a same-named file in another folder is a different
    // file, so it must still queue — the 1951 name rule would
    // wrongly skip it. Null-safe on every input: a null/blank
    // name or path, a null listened set and a null queued
    // collection all read as not-queueable / not queued, never a
    // crash.
    public static boolean isQueueable(String name, String path, java.util.Set<String> listenedNames, String soundingPath, java.util.Collection<String> queuedPaths) {
        return DlQueueListened.isQueueable(name, path, listenedNames, soundingPath, queuedPaths);
    }

    // The queueable paths, in display order: every shown
    // name/path pair that passes isQueueable. Delegates to
    // DlQueueListened.pick — same list, same order, same
    // truncation and null-safety — so the offer and the handler
    // share one pick and the twins share one membership rule.
    public static java.util.List<String> pick(java.util.List<String> shownNames, java.util.List<String> shownPaths, java.util.Set<String> listenedNames, String soundingPath, java.util.Collection<String> queuedPaths) {
        return DlQueueListened.pick(shownNames, shownPaths, listenedNames, soundingPath, queuedPaths);
    }

    // Head-insert the picks: the kept picks land at index 0 of
    // the live queue, in display order, before any older queue
    // (index 0 plays next, the same head the v6.68/v6.71 verbs
    // use), without stopping playback. A pick already in the
    // queue stays where it is (like every other bulk queue verb)
    // and is not counted; a null/blank pick is skipped; a pick
    // repeated inside the picks list is kept once. Returns the
    // count newly inserted. A null queue or null picks is a
    // no-op returning 0; the picks list is never mutated.
    public static int insertHead(java.util.List<String> queue, java.util.List<String> picks) {
        if (queue == null || picks == null) return 0;
        java.util.List<String> kept = new java.util.ArrayList<String>();
        for (String u : picks) {
            if (u == null || u.length() == 0) continue;
            if (queue.contains(u)) continue;
            if (kept.contains(u)) continue;
            kept.add(u);
        }
        if (!kept.isEmpty()) queue.addAll(0, kept);
        return kept.size();
    }
}
