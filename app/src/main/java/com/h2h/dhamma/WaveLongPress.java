package com.h2h.dhamma;

// v15.49 (slice 1963): wave long-press — press-and-hold the wave
// marks the pressed minute instead of seeking there. Premium
// backlog #1 (user order 2026-11-06): v12.64 built the store
// (Prefs.bookmarks64), the gold wave pins and the list dialog;
// slice 1961 reconnected the add/list verbs to the Room drawer
// sounding-row dialog and slice 1962 put the marks on Main — but
// the only way to ADD a mark was still the dialog verb at the
// LIVE minute. A listener who wants to mark a passage ahead of
// (or behind) the playhead had no way to say WHERE. The wave is
// the timeline: hold it at a spot and the mark drops there. Pure
// selection only: the View owns the hold timing, the Activity
// owns the service reads, the prefs write and the toast.
public class WaveLongPress {
    // The finger's x on a bar of the given width/range, as ms —
    // the same fraction scrubTo uses, clamped, so a press past
    // either edge still names a minute inside the talk. No range
    // or no width names 0:00 (the offer then refuses it).
    public static int msForX(float x, int width, int max) {
        if (width <= 0 || max <= 0) return 0;
        float frac = Math.max(0f, Math.min(1f, x / width));
        return Math.round(frac * max);
    }

    // The finger moved past touch slop — a scrub, not a hold.
    // Either axis past slop ends the hold (the Android slop
    // idiom: a hold is a still finger, a drag in any direction
    // is a scrub).
    public static boolean movedBeyondSlop(float dx, float dy, int slop) {
        if (slop < 0) slop = 0;
        return Math.abs(dx) > slop || Math.abs(dy) > slop;
    }

    // A mark can be added at the pressed minute only while a
    // track is loaded with a real duration and the minute is past
    // 0:00 — the onBookmarkAdd guard (BookmarkMenu.offerAdd)
    // with the pressed ms in place of the live position.
    public static boolean offerAdd(int durMs, int ms) {
        return durMs > 0 && ms > 0;
    }
}
