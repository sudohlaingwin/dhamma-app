package com.h2h.dhamma;

// v15.66 (slice 2056): Sort on the Favourites screen — the pure
// order for the full list of starred talks. FavAll.pickAll returns
// the rail's own order only (most-recently-played first): a listener
// with dozens of starred talks could find one by name since slice
// 2048 (search) but could not browse them oldest-first, or see one
// sayadaw's starred talks together. The sort is the Bookmarks
// three-state cycle (v15.65, slice 2051, itself the Downloaded
// dlSortMode shape, v1.47/v4.24): 0 most-recent first (today's
// order, the default), 1 oldest first, 2 sayadaw A-Z. Pure
// selection only: the Activity owns the button, the label, the
// persisted mode and the re-sort call; the input list is never
// mutated.
public class FavSort {
    static final int RECENT = 0;
    static final int OLDEST = 1;
    static final int SAYADAW = 2;

    // The sorted list, in the chosen order, in a fresh copy. A null
    // input list reads as empty; null rows and null entries never
    // reach the list. Mode 0 keeps the pick order (most-recently
    // played first); mode 1 reverses it (oldest first); mode 2 is a
    // stable sort by sayadaw name, so starred talks of one sayadaw
    // sit together and keep their pick order within the sayadaw. An
    // unknown mode reads as mode 0, never as a surprise order. A
    // null or blank sayadaw sorts as the empty string, ahead of
    // every name.
    static java.util.List<FavTalkRail.Card> sort(
            java.util.List<FavTalkRail.Card> cards, int mode) {
        java.util.List<FavTalkRail.Card> out =
                new java.util.ArrayList<FavTalkRail.Card>();
        if (cards == null) return out;
        for (FavTalkRail.Card c : cards) {
            if (c == null || c.e == null) continue;
            out.add(c);
        }
        if (mode == OLDEST) {
            java.util.Collections.reverse(out);
            return out;
        }
        if (mode == SAYADAW) {
            java.util.Collections.sort(out, new java.util.Comparator<FavTalkRail.Card>() {
                @Override public int compare(FavTalkRail.Card a, FavTalkRail.Card b) {
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
