package com.h2h.dhamma;

// v15.74 (slice 2102): "Play all" on the History screen —
// the chooser's v13.76 Play All, now on the last list
// surface that lacked it: the recent talks used to play one
// row tap at a time (playRecent) or as an in-order run from a
// row onward (histPlayFromHere, v15.62) or shuffled
// (shufflePlayHs, 2099); one tap here queues the SHOWN run
// in order and opens the Room on the first talk. Pure order
// math only: the Activity owns the list, the queue write and
// the Room open; the core never touches Android, prefs, or
// the queue. The run is a COPY of the shown run, in order —
// the input list is never mutated, null entries never play.
// Filtering stays in the handler — it plays first, then
// skips leading dead items (the v12.79 leg), the sounding
// talk (the stay-put branch) and empty lines, so the run in
// order is the run.
public class HistPlayAll {
    // The shown run's entries in order: a fresh list, the
    // input never mutated, null entries dropped (a null
    // entry has no talk to queue and no row to open). A null
    // input reads as an empty run. A one-entry run plays
    // itself.
    static java.util.List<Prefs.HistEntry> run(
            java.util.List<Prefs.HistEntry> entries) {
        java.util.List<Prefs.HistEntry> out = new java.util.ArrayList<Prefs.HistEntry>();
        if (entries == null) return out;
        for (Prefs.HistEntry e : entries) {
            if (e == null) continue;
            out.add(e);
        }
        return out;
    }
}
