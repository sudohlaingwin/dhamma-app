package com.h2h.dhamma;

// v15.44 (slice 1936): Main grid list view — the grid's alternate
// presentation. Until this slice the home grid had exactly one
// shape (4 columns of photo cards, 11sp names, 2-line truncation);
// every streaming library (Spotify / YouTube Music) ships a
// list/grid toggle, and the list row shows the full sayadaw name
// on one line at +2sp with the group beside it — larger, older-
// eyes-friendly text for the listeners this app serves. Pure
// geometry only: columns, spacings and text sizes for the two
// modes. The Activity owns the pref, the button, the GridView
// calls and the scroll save/restore; the core never touches
// Android.
public class GridViewMode {
    // Adapter view types: 0 = grid card (row_sayadaw_h), 1 =
    // list row (row_sayadaw_list). Distinct types keep GridView
    // recycling inside one layout across a mode flip.
    static final int TYPE_GRID = 0;
    static final int TYPE_LIST = 1;
    static final int TYPE_COUNT = 2;

    // The adapter view type for a mode.
    static int viewType(boolean list) {
        return list ? TYPE_LIST : TYPE_GRID;
    }

    // GridView columns: 4 photo cards across, 1 full-width row.
    static int columns(boolean list) {
        return list ? 1 : 4;
    }

    // GridView spacings in dp: the grid breathes 12/12, the list
    // has no horizontal gap and an 8dp row gap.
    static int hSpacingDp(boolean list) {
        return list ? 0 : 12;
    }

    static int vSpacingDp(boolean list) {
        return list ? 8 : 12;
    }

    // List rows read larger: the full name fits on one line at
    // +2sp, the group line at +1sp (the grid's base is the Prefs
    // nameSize/groupSize the listener already chose).
    static float nameSizeSp(boolean list, float base) {
        return list ? base + 2f : base;
    }

    static float groupSizeSp(boolean list, float base) {
        return list ? base + 1f : base;
    }
}
