package com.h2h.dhamma;

// v15.68 (slice 2068): Sort on the History screen — the pure
// order for the recent-plays list. The stats store returns the
// recent list most-recent first (the add writes the newest
// listen at index 0): a listener with dozens of listens could
// find one talk by name since slice 2050 (search) but could not
// browse them oldest-first, or see one sayadaw's listens
// together. The sort is the Downloaded three-state cycle (v1.47/v4.24, dlSortMode,
// itself the Bookmarks shape, v15.65 slice 2051): 0 most-recent
// first (today's order, the default), 1 oldest first, 2 sayadaw
// A-Z. Pure selection only: the Activity owns the button, the
// label, the persisted mode and the re-sort call; the input list
// is never mutated.
public class HistSort {
    static final int RECENT = 0;
    static final int OLDEST = 1;
    static final int SAYADAW = 2;

    // The sorted list, in the chosen order, in a fresh copy. A null
    // input list reads as empty; null rows never reach the list.
    // Mode 0 keeps the store order (most-recent first); mode 1
    // reverses it (oldest first); mode 2 is a stable sort by
    // sayadaw name, so listens of one sayadaw sit together and
    // keep their store order within the sayadaw — a talk listened
    // to twice stays two rows in a row. An unknown mode reads as
    // mode 0, never as a surprise order. A null or blank sayadaw
    // sorts as the empty string, ahead of every name.
    static java.util.List<Prefs.HistEntry> sort(
            java.util.List<Prefs.HistEntry> rows, int mode) {
        java.util.List<Prefs.HistEntry> out =
                new java.util.ArrayList<Prefs.HistEntry>();
        if (rows == null) return out;
        for (Prefs.HistEntry r : rows) {
            if (r == null) continue;
            out.add(r);
        }
        if (mode == OLDEST) {
            java.util.Collections.reverse(out);
            return out;
        }
        if (mode == SAYADAW) {
            java.util.Collections.sort(out, new java.util.Comparator<Prefs.HistEntry>() {
                @Override public int compare(Prefs.HistEntry a, Prefs.HistEntry b) {
                    String sa = a.sayadaw == null ? "" : a.sayadaw;
                    String sb = b.sayadaw == null ? "" : b.sayadaw;
                    return sa.compareToIgnoreCase(sb);
                }
            });
            return out;
        }
        return out;
    }
}
