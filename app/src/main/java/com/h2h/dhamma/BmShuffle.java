package com.h2h.dhamma;

// v15.68 (slice 2067): "Shuffle play" on the Bookmarks screen —
// the chooser's v13.93 shuffle-play, the third of the three
// full-list screens to get it. The marks used to play in screen
// order only (Play-all-marks, v15.66, slice 2055); a shuffled run
// is the streaming verb the screen was missing — Spotify's shuffle
// is the defining control of a starred list, and re-hearing the
// saved minutes in random order meant shuffling the queue by hand
// in the Room drawer. A talk with three marks is three rows, so
// the shuffled unit is the mark: marks play in random order, each
// at its own minute. Pure order math only: the Activity owns the
// list, the queue write and the Room open; the core never touches
// Android, prefs, or the queue. The shuffle is a Fisher-Yates pass
// (Collections.shuffle) over a COPY of the shown run — the input
// list is never mutated, null rows never shuffle, and a null
// Random reads as a fresh one (the handler passes a fresh Random
// per tap, so two taps never repeat the same order by
// construction; the harness passes a seeded one, so the order is
// reproducible there). Filtering stays in BmPlayAll.lines — the
// handler shuffles first, then lines() dedupes / skips sounding /
// skips already-queued in the shuffled order, so the shuffled run
// is the run.
public class BmShuffle {
    // The shown rows in shuffled order: a fresh list, the input
    // never mutated, null rows dropped (a null row has no mark to
    // queue and no row to open). A null input reads as an empty
    // run; a null Random reads as a fresh one. A one-row run
    // shuffles to itself.
    static java.util.List<BmAll.Row> shuffleRows(
            java.util.List<BmAll.Row> rows, java.util.Random rnd) {
        java.util.List<BmAll.Row> out = new java.util.ArrayList<BmAll.Row>();
        if (rows == null) return out;
        for (BmAll.Row r : rows) {
            if (r == null) continue;
            out.add(r);
        }
        java.util.Collections.shuffle(out, rnd == null ? new java.util.Random() : rnd);
        return out;
    }
}
