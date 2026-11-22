package com.h2h.dhamma;

// v15.68 (slice 2065): "Shuffle play" on the Favourites screen —
// the chooser's v13.93 shuffle-play, the first of the three
// full-list screens to get it. The starred talks used to play in
// screen order only (Play-all, v15.64, slice 2044); a shuffled run
// is the streaming verb the screen was missing — Spotify's shuffle
// is the defining control of a starred list, and re-hearing the
// stars in random order meant shuffling the queue by hand in the
// Room drawer. Pure order math only: the Activity owns the list,
// the queue write and the Room open; the core never touches
// Android, prefs, or the queue. The shuffle is a Fisher-Yates pass
// (Collections.shuffle) over a COPY of the shown run — the input
// list is never mutated, null cards never shuffle, and a null
// Random reads as a fresh one (the handler passes a fresh Random
// per tap, so two taps never repeat the same order by
// construction; the harness passes a seeded one, so the order is
// reproducible there). Filtering stays in FavPlayAll.lines — the
// handler shuffles first, then lines() dedupes / skips sounding /
// skips already-queued in the shuffled order, so the shuffled run
// is the run.
public class FavShuffle {
    // The shown cards in shuffled order: a fresh list, the input
    // never mutated, null cards dropped (a null card has no talk
    // to queue and no row to open). A null input reads as an empty
    // run; a null Random reads as a fresh one. A one-card run
    // shuffles to itself.
    static java.util.List<FavTalkRail.Card> shuffleCards(
            java.util.List<FavTalkRail.Card> cards, java.util.Random rnd) {
        java.util.List<FavTalkRail.Card> out = new java.util.ArrayList<FavTalkRail.Card>();
        if (cards == null) return out;
        for (FavTalkRail.Card c : cards) {
            if (c == null) continue;
            out.add(c);
        }
        java.util.Collections.shuffle(out, rnd == null ? new java.util.Random() : rnd);
        return out;
    }
}
