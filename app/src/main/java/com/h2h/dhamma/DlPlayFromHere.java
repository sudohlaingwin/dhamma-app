package com.h2h.dhamma;

// v15.57 (slice 2001): "Play from here" pick for the Downloaded
// library row menu — the library mirror of the Talks chooser's
// PlayFromHere (v15.45, slice 1942). Queue-from-here (v13.04)
// queues this file and every shown file below it but never starts
// playback, and a row tap plays one file without queueing the run
// below it, so starting a run mid-shelf took two gestures in two
// places. This picks the run: the display positions from the
// anchor file onward, in display order, minus the sounding file
// and minus already-queued files (they stay where they are). The
// anchor itself is in the pick unless it sounds or is already
// queued — the Activity still plays it either way (service.play
// on the anchor path; the anchor itself is never left in the
// queue, the row-tap v4.57 rule), so a queued anchor plays from
// its queue spot and a sounding anchor is never re-played. Pure
// index math only: the Activity owns the list, the queue and the
// play. Identity here is the bare file path (exact String
// equality): the library queue holds absolute paths, never URLs,
// so there are no http/https twins to normalise — the sameTalk
// scan belongs to the chooser's URL world, not this one.
public class DlPlayFromHere {
    // The display positions to queue-and-play, in display order,
    // from the anchor onward, or an empty list when none.
    // `displayPaths` is parallel to the shown list
    // (absolute file path per entry, null where an entry has
    // none); `anchorPath` is the long-pressed file's path;
    // `queuedPaths` holds the hand queue's paths (exact
    // equality — a queued path is the same file). Skips null
    // paths, the sounding file and already-queued files. An
    // anchor that is not in the list picks nothing (the
    // Activity falls back to the anchor alone). The input lists
    // are never mutated.
    static java.util.List<Integer> pick(
            java.util.List<String> displayPaths,
            String anchorPath,
            java.util.Set<String> queuedPaths,
            String soundingPath) {
        java.util.List<Integer> out = new java.util.ArrayList<Integer>();
        int start = anchorIndex(displayPaths, anchorPath);
        if (start < 0) return out;
        for (int i = start; i < displayPaths.size(); i++) {
            String p = displayPaths.get(i);
            if (p == null) continue;
            if (queuedPaths != null && queuedPaths.contains(p)) continue;
            if (soundingPath != null && soundingPath.length() > 0
                    && p.equals(soundingPath)) continue;
            out.add(Integer.valueOf(i));
        }
        return out;
    }

    // The anchor's position in the display list, or -1 — the
    // v13.13 tap-time re-resolution (the dialog can sit open
    // across a filter rebuild, so the captured position may
    // have moved). Identity comparison: the list and the anchor
    // are both absolute file paths (exact equality).
    static int anchorIndex(java.util.List<String> displayPaths, String anchorPath) {
        if (displayPaths == null || anchorPath == null || anchorPath.length() == 0) return -1;
        for (int i = 0; i < displayPaths.size(); i++) {
            String p = displayPaths.get(i);
            if (p != null && p.equals(anchorPath)) return i;
        }
        return -1;
    }
}
