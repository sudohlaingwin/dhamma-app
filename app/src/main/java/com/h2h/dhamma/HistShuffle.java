package com.h2h.dhamma;

// v15.73 (slice 2099): "Shuffle play" on the History screen —
// the chooser's v13.93 shuffle-play, now on the last list
// surface that lacked it: the recent talks used to play one
// row tap at a time (playRecent) or as an in-order run from a
// row onward (histPlayFromHere, v15.62); a shuffled run is
// the streaming verb the screen was missing — Spotify's
// shuffle is the defining control of a recently-played list,
// and re-hearing the recent run in random order meant
// shuffling the queue by hand in the Room drawer. Pure order
// math only: the Activity owns the list, the queue write and
// the Room open; the core never touches Android, prefs, or
// the queue. The shuffle is a Fisher-Yates pass
// (Collections.shuffle) over a COPY of the shown run — the
// input list is never mutated, null entries never shuffle,
// and a null Random reads as a fresh one (the handler passes
// a fresh Random per tap, so two taps never repeat the same
// order by construction; the harness passes a seeded one, so
// the order is reproducible there). Filtering stays in the
// handler — it shuffles first, then skips leading dead items
// (the v12.79 leg), the sounding talk (the stay-put branch)
// and empty lines, so the shuffled run is the run.
public class HistShuffle {
    // The shown run's entries in shuffled order: a fresh list,
    // the input never mutated, null entries dropped (a null
    // entry has no talk to queue and no row to open). A null
    // input reads as an empty run; a null Random reads as a
    // fresh one. A one-entry run shuffles to itself.
    static java.util.List<Prefs.HistEntry> shuffleEntries(
            java.util.List<Prefs.HistEntry> entries, java.util.Random rnd) {
        java.util.List<Prefs.HistEntry> out = new java.util.ArrayList<Prefs.HistEntry>();
        if (entries == null) return out;
        for (Prefs.HistEntry e : entries) {
            if (e == null) continue;
            out.add(e);
        }
        java.util.Collections.shuffle(out, rnd == null ? new java.util.Random() : rnd);
        return out;
    }
}
