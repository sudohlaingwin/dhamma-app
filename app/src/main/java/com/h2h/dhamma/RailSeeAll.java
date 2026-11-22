package com.h2h.dhamma;

// v15.62 (slice 2031): "See all" on the Main rail headers — the
// Spotify "See all" affordance: a rail shows the first few cards and
// its header opens the full list. The kernel is shared by all five
// rails; this slice wires only the Recently-played rail — its
// destination, HistoryActivity, holds the stats header (total hours,
// day streak, top sayadaw) plus the full 30-row list, and is
// otherwise unreachable from anywhere in the app (no Intent to it
// exists outside its own file). Pure affordance + guard only: the
// Activity owns the tap and the Intent.
public class RailSeeAll {
    // The rail-tap clock, shared with the card taps (the 700ms
    // double-tap guard of the Talks row-tap v12.28, via
    // openRecentTalk1930): a rapid double-tap must never stack two
    // History screens.
    static final long DEBOUNCE_MS = 700;

    // The 700ms guard, the openRecentTalk1930 shape: a tap is
    // allowed only when the window since the last accepted rail tap
    // has elapsed. The Activity updates its clock only on allow, so
    // a denied tap never consumes the window.
    static boolean allowTap(long lastMs, long now) {
        return now - lastMs >= DEBOUNCE_MS;
    }

    // The header affordance: the rail's own label words plus the
    // History row's "›" chevron (HistoryActivity's go chevron) — the
    // tap target reads as a link, not a plain heading. Null-safe:
    // a null base paints no words, never "null ›".
    static String label(String base) {
        if (base == null) return "";
        return base + " \u203a";
    }
}
