package com.h2h.dhamma;

// v15.65 (slice 2048): Search on the Favourites screen — the pure
// filter for the full list of starred talks. The rail shows 5
// cards while the listener's stars can run to dozens, and no
// screen anywhere let them find one by title or sayadaw without
// scrolling: the Main global search (v15.62) searches the whole
// catalog and plays the result, while this list is the
// starred-only context. A card matches when its title OR its
// sayadaw name matches the query under the ONE rule
// (Fx.titleMatches — NFC + digit folding + the v13.60 Zawgyi
// reading, so a Zawgyi-typed query finds a Unicode title, and a
// digit-folded query finds a digit-folded title). A blank query
// matches everything — no filter. Pure selection only: the
// Activity owns the field, the watcher, the clear ✕ and the
// empty line. The input list is never mutated.
public class FavSearch {
    // True when this card's talk matches the query — its title or
    // its sayadaw name under the ONE rule. Null/blank titles and
    // sayadaws match nothing (titleMatches fails closed on null),
    // and a blank query matches everything (no filter).
    static boolean matches(String title, String sayadaw, String query) {
        if (query == null || query.trim().length() == 0) return true;
        String q = query.trim();
        return Fx.titleMatches(title, q) || Fx.titleMatches(sayadaw, q);
    }

    // The filtered list, in screen order. Null cards and null
    // entries never reach the list; a null input list reads as
    // empty. The returned list is a fresh copy — the input is
    // never mutated, so the Activity's unfiltered pick stays
    // intact for the next keystroke.
    static java.util.List<FavTalkRail.Card> filter(
            java.util.List<FavTalkRail.Card> cards, String query) {
        java.util.List<FavTalkRail.Card> out =
                new java.util.ArrayList<FavTalkRail.Card>();
        if (cards == null) return out;
        for (FavTalkRail.Card c : cards) {
            if (c == null || c.e == null) continue;
            if (matches(c.e.title, c.e.sayadaw, query)) out.add(c);
        }
        return out;
    }
}
