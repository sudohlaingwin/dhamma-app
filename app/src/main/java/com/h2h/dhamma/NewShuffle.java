package com.h2h.dhamma;

// v15.68 (slice 2066): "Shuffle play" on the New talks screen —
// the chooser's v13.93 shuffle-play, the second of the three
// full-list screens to get it. The new talks used to play in
// catalogue order only (Play-all-new, v15.65, slice 2047); a
// shuffled run is the streaming verb the screen was missing —
// Spotify's shuffle is the defining control of a "new releases"
// list, and re-hearing the fresh arrivals in random order meant
// shuffling the queue by hand in the Room drawer. Pure order math
// only: the Activity owns the list, the queue write and the Room
// open; the core never touches Android, prefs, or the queue. The
// shuffle is a Fisher-Yates pass (Collections.shuffle) over a COPY
// of the shown run — the input list is never mutated, null cards
// never shuffle, and a null Random reads as a fresh one (the
// handler passes a fresh Random per tap, so two taps never repeat
// the same order by construction; the harness passes a seeded one,
// so the order is reproducible there). Filtering stays in
// NewPlayAll.lines — the handler shuffles first, then lines()
// dedupes / skips sounding / skips already-queued in the shuffled
// order, so the shuffled run is the run.
public class NewShuffle {
    // The shown cards in shuffled order: a fresh list, the input
    // never mutated, null cards dropped (a null card has no talk
    // to queue and no row to open). A null input reads as an empty
    // run; a null Random reads as a fresh one. A one-card run
    // shuffles to itself.
    static java.util.List<NewTalkRail.Card> shuffleCards(
            java.util.List<NewTalkRail.Card> cards, java.util.Random rnd) {
        java.util.List<NewTalkRail.Card> out = new java.util.ArrayList<NewTalkRail.Card>();
        if (cards == null) return out;
        for (NewTalkRail.Card c : cards) {
            if (c == null) continue;
            out.add(c);
        }
        java.util.Collections.shuffle(out, rnd == null ? new java.util.Random() : rnd);
        return out;
    }
}
