package com.h2h.dhamma;

// v15.47 (slice 1950): "Swipe a Downloaded row" — the first gesture
// on the library shelf: a row dragged left marks (or un-marks) that
// one file listened, a row dragged right queues it next, with the
// gold underlay + action label revealed under the card as it
// travels. The shelf is a ListView, so there is no ItemTouchHelper
// — the gesture math lives here, pure and Android-free: the
// activity owns the touch stream, the prefs, the queue and the
// toasts, and this core only answers "is this a swipe, which way,
// how far, does it commit". Thresholds are passed in (slop from
// ViewConfiguration, the 96dp floor from the activity's density)
// so the core never reads a resource.
public class DlSwipe {
    public static final int LEFT = -1;
    public static final int RIGHT = 1;
    public static final int NONE = 0;

    // Horizontal dominance: the drag is a swipe, not a scroll — past
    // touch slop, and more than twice the vertical travel. A
    // vertical scroll never arms (its |dy| dominates), a diagonal
    // never arms, and sub-slop jitter never arms.
    public static boolean dominant(float dx, float dy, float slop) {
        return Math.abs(dx) > slop && Math.abs(dx) > 2f * Math.abs(dy);
    }

    // Which way the finger went: negative is left (mark), positive
    // is right (queue next), zero is no direction at all.
    public static int direction(float dx) {
        if (dx < 0) return LEFT;
        if (dx > 0) return RIGHT;
        return NONE;
    }

    // Commit: past a third of the row, never less than the caller's
    // floor (96dp in px) — on a narrow row the floor wins, so a
    // short flick can never fire by accident.
    public static boolean commit(float dx, float widthPx, float minCommitPx) {
        float need = Math.max(minCommitPx, widthPx / 3f);
        return Math.abs(dx) >= need;
    }

    // The live drag caps at 40% of the row either way — the card
    // never travels off screen, and the underlay label stays
    // readable at full travel.
    public static float clamp(float dx, float widthPx) {
        float lim = widthPx * 0.4f;
        if (dx < -lim) return -lim;
        if (dx > lim) return lim;
        return dx;
    }

    // One-way clamp: once a direction is armed the card only ever
    // travels that way — a finger that reverses past centre parks
    // the card at 0 instead of dragging it the other way.
    public static float clampDir(float dx, int dir, float widthPx) {
        float c = clamp(dx, widthPx);
        if (dir == LEFT) return Math.min(0f, c);
        if (dir == RIGHT) return Math.max(0f, c);
        return 0f;
    }

    // Queue-next is offered on any non-playing, not-yet-queued row
    // — the row menu's own gate (menu_play_next_short), as pure
    // math. The mark gate lives in DlMark.canOffer (it needs the
    // dlMap link); this core never re-implements it.
    public static boolean offerRight(boolean playing, boolean queued) {
        return !playing && !queued;
    }

    // The label the underlay shows on the revealed side: LEFT
    // flips mark/un-mark on the row's listened state (the same
    // cache the row's own check renders from), RIGHT is always
    // the play-next word. No new Lang key — all three words
    // already ship.
    public static String labelKey(int dir, boolean listened) {
        if (dir == LEFT) return listened ? "dl_menu_unmark_one" : "dl_menu_mark_one";
        if (dir == RIGHT) return "menu_play_next_short";
        return null;
    }
}
