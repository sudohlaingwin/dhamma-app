package com.h2h.dhamma;

// v15.77 (slice 2120): Sort on the Playlist lists screen — the pure
// order for the user's own lists. Prefs.playlistNames returns the
// store order only (creation order: the seeded morning / bedtime
// lists first, then each created list in the order it was made):
// a listener with a dozen lists could rename / copy / share /
// delete them but could not browse them newest-first, or see
// them in name order. The sort is the Downloaded three-state
// cycle (v1.47/v4.24, dlSortMode): 0 creation order (today's
// order, the default), 1 newest first, 2 name A-Z. Pure
// selection only: the Activity owns the button, the label, the
// persisted mode and the re-sort call; the input list is never
// mutated.
public class PlSort {
    static final int CREATED = 0;
    static final int NEWEST = 1;
    static final int NAME = 2;

    // The sorted list, in the chosen order, in a fresh copy. A
    // null input list reads as empty; null and blank names never
    // reach the list. Mode 0 keeps the store order (creation
    // order — the seeded lists first, then each created list);
    // mode 1 reverses it (newest first); mode 2 is a stable
    // sort by list name, so lists of one name order sit together
    // and keep their store order within the name. An unknown
    // mode reads as mode 0, never as a surprise order.
    static java.util.List<String> sort(
            java.util.List<String> names, int mode) {
        java.util.List<String> out =
                new java.util.ArrayList<String>();
        if (names == null) return out;
        for (String n : names) {
            if (n == null || n.length() == 0) continue;
            out.add(n);
        }
        if (mode == NEWEST) {
            java.util.Collections.reverse(out);
            return out;
        }
        if (mode == NAME) {
            java.util.Collections.sort(out, new java.util.Comparator<String>() {
                @Override public int compare(String a, String b) {
                    return a.compareToIgnoreCase(b);
                }
            });
            return out;
        }
        return out;
    }
}
