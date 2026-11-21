package com.h2h.dhamma;

// v15.43 (slice 1931): Talks list sort — the display order of a
// talk list. The site lists dated pages newest-first and series
// pages in series order (001, 002, ...); the toggle reverses it,
// so a listener can work through a sayadaw's talks from the
// beginning, or jump a series to its newest lesson. Pure index
// math only: the Activity owns the list, the pref and the button.
public class TalkSort {
    // Display order for a list of `count` talks in site order:
    // site order 0..n-1, or reversed n-1..0 when oldest-first.
    // Count <= 0 picks an empty list, never null.
    static java.util.ArrayList<Integer> fullOrder(int count, boolean oldestFirst) {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<Integer>();
        if (count <= 0) return out;
        if (!oldestFirst) {
            for (int i = 0; i < count; i++) out.add(Integer.valueOf(i));
        } else {
            for (int i = count - 1; i >= 0; i--) out.add(Integer.valueOf(i));
        }
        return out;
    }

    // A narrowed set (search/filter hits, site order) in display
    // order: the same order, or a reversed COPY when oldest-first.
    // Null stays null (no narrowing active); the input list is
    // never mutated, so the caller's search/filter hits keep
    // their site order for the next rebuild.
    static java.util.ArrayList<Integer> apply(
            java.util.ArrayList<Integer> hits, boolean oldestFirst) {
        if (hits == null) return null;
        java.util.ArrayList<Integer> out =
                new java.util.ArrayList<Integer>(hits);
        if (oldestFirst) java.util.Collections.reverse(out);
        return out;
    }
}
