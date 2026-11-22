package com.h2h.dhamma;

// v15.65 (slice 2051): Sort on the Bookmarks screen — the pure
// order for the full list of saved minutes. BmAll.pickAll returns
// the store order only (most-recent first): a listener with dozens
// of marks could find one talk by name since slice 2049 (search)
// but could not browse them oldest-first, or see one sayadaw's
// marks together. The sort is the Downloaded three-state cycle
// (v1.47/v4.24, dlSortMode): 0 most-recent first (today's order,
// the default), 1 oldest first, 2 sayadaw A-Z. Pure selection
// only: the Activity owns the button, the label, the persisted
// mode and the re-sort call; the input list is never mutated.
public class BmSort {
    static final int RECENT = 0;
    static final int OLDEST = 1;
    static final int SAYADAW = 2;

    // The sorted list, in the chosen order, in a fresh copy. A null
    // input list reads as empty; null rows and null entries never
    // reach the list. Mode 0 keeps the store order (most-recent
    // first); mode 1 reverses it (oldest first); mode 2 is a stable
    // sort by sayadaw name, so marks of one sayadaw sit together
    // and keep their store order within the sayadaw — a talk with
    // three marks stays three rows in a row. An unknown mode reads
    // as mode 0, never as a surprise order. A null or blank sayadaw
    // sorts as the empty string, ahead of every name.
    static java.util.List<BmAll.Row> sort(
            java.util.List<BmAll.Row> rows, int mode) {
        java.util.List<BmAll.Row> out =
                new java.util.ArrayList<BmAll.Row>();
        if (rows == null) return out;
        for (BmAll.Row r : rows) {
            if (r == null || r.e == null) continue;
            out.add(r);
        }
        if (mode == OLDEST) {
            java.util.Collections.reverse(out);
            return out;
        }
        if (mode == SAYADAW) {
            java.util.Collections.sort(out, new java.util.Comparator<BmAll.Row>() {
                @Override public int compare(BmAll.Row a, BmAll.Row b) {
                    String sa = a.e.sayadaw == null ? "" : a.e.sayadaw;
                    String sb = b.e.sayadaw == null ? "" : b.e.sayadaw;
                    return sa.compareToIgnoreCase(sb);
                }
            });
            return out;
        }
        return out;
    }
}
