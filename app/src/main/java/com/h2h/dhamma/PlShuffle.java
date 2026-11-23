package com.h2h.dhamma;

// v15.73 (slice 2098): "Shuffle play" on the Playlist detail
// screen — the chooser's v13.93 shuffle-play, now on the
// playlist: the open list used to play in stored order only
// (Play All, playFrom); a shuffled run is the streaming verb
// the screen was missing — Spotify's shuffle is the defining
// control of a playlist detail screen, and re-hearing the
// listener's own morning / bedtime list in random order meant
// shuffling the queue by hand in the Room drawer. Pure order
// math only: the Activity owns the list, the queue write and
// the Room open; the core never touches Android, prefs, or
// the queue. The shuffle is a Fisher-Yates pass
// (Collections.shuffle) over a COPY of the open list — the
// input list is never mutated, null entries never shuffle,
// and a null Random reads as a fresh one (the handler passes
// a fresh Random per tap, so two taps never repeat the same
// order by construction; the harness passes a seeded one, so
// the order is reproducible there). Filtering stays in the
// handler — it shuffles first, then skips leading dead items
// (the v12.79 leg), the sounding talk (the stay-put branch)
// and empty lines, so the shuffled run is the run.
public class PlShuffle {
    // The open list's entries in shuffled order: a fresh list,
    // the input never mutated, null entries dropped (a null
    // entry has no talk to queue and no row to open). A null
    // input reads as an empty run; a null Random reads as a
    // fresh one. A one-entry run shuffles to itself.
    static java.util.List<QueueEntry> shuffleEntries(
            java.util.List<QueueEntry> entries, java.util.Random rnd) {
        java.util.List<QueueEntry> out = new java.util.ArrayList<QueueEntry>();
        if (entries == null) return out;
        for (QueueEntry e : entries) {
            if (e == null) continue;
            out.add(e);
        }
        java.util.Collections.shuffle(out, rnd == null ? new java.util.Random() : rnd);
        return out;
    }
}
