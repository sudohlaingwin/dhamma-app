package com.h2h.dhamma;

// v15.65 (slice 2049): Search on the Bookmarks screen — the pure
// filter for the full list of saved minutes. The rail shows 5
// cards while the listener's marks can run to dozens, and no
// screen anywhere let them find one marked talk by title or
// sayadaw without scrolling: the Main global search (v15.62)
// searches the whole catalog and plays the result, while this
// list is the marks-only context — a talk with three marks is
// three rows, each jumping to its own minute. A row matches when
// its talk's title OR its sayadaw name matches the query under
// the ONE rule (Fx.titleMatches — NFC + digit folding + the
// v13.60 Zawgyi reading, so a Zawgyi-typed query finds a Unicode
// title, and a digit-folded query finds a digit-folded title).
// A match keeps ALL marks of the matching talk, since the filter
// is per-row on the row's talk. A blank query matches everything
// — no filter. Pure selection only: the Activity owns the field,
// the watcher, the clear ✕ and the empty line. The input list is
// never mutated.
public class BmSearch {
    // True when this row's talk matches the query — its title or
    // its sayadaw name under the ONE rule. Null/blank titles and
    // sayadaws match nothing (titleMatches fails closed on null),
    // and a blank query matches everything (no filter).
    static boolean matches(String title, String sayadaw, String query) {
        if (query == null || query.trim().length() == 0) return true;
        String q = query.trim();
        return Fx.titleMatches(title, q) || Fx.titleMatches(sayadaw, q);
    }

    // The filtered list, in screen order. Null rows and null
    // entries never reach the list; a null input list reads as
    // empty. The returned list is a fresh copy — the input is
    // never mutated, so the Activity's unfiltered pick stays
    // intact for the next keystroke.
    static java.util.List<BmAll.Row> filter(
            java.util.List<BmAll.Row> rows, String query) {
        java.util.List<BmAll.Row> out =
                new java.util.ArrayList<BmAll.Row>();
        if (rows == null) return out;
        for (BmAll.Row r : rows) {
            if (r == null || r.e == null) continue;
            if (matches(r.e.title, r.e.sayadaw, query)) out.add(r);
        }
        return out;
    }
}
