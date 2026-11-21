package com.h2h.dhamma;

// v15.44 (slice 1935): "Choose your altar" pick — the dialog <->
// pref bridge for the Room's shrine photo. Until this slice the
// photo was a fresh random roll on every Room open and every talk
// change (v12.91), so a listener who loved one altar could never
// keep it. The six shipped photos (assets/room/altar_ai.jpg +
// altar_t1..t5) are genuinely different scenes, and RoomView.
// THEMES already names thirty altar moods — PHOTO_THEMES below
// maps each photo to the theme name that matches what the asset
// actually shows (matched by reading the six shipped images this
// slice: 0 gold shrine, 1 white marble in morning mist, 2 green
// jade, 3 dark bronze at sunset, 4 crystal glowing silver-blue
// at night, 5 gold in red + marigolds at festival lights). Pure
// selection only: the Activity owns the pref, the dialog and the
// Room open; the core never touches Android or the assets.
public class AltarPick {
    // No photo fixed: the Room keeps its random roll (the v12.91
    // behaviour). Stored in Prefs as -1.
    static final int RANDOM = -1;

    // Photo index (0..5, RoomView.PHOTO_COUNT) -> the THEMES index
    // whose name describes that photo. The names are content —
    // Burmese in every language, like sayadaw names — read at
    // runtime from RoomView.THEMES, never copied here.
    static final int[] PHOTO_THEMES = {0, 10, 12, 14, 20, 26};

    // The dialog choice for a stored fixed photo: -1 -> choice 0
    // (random), a photo 0..5 -> choice photo+1, anything else ->
    // choice 0 (fail closed to random, never to a guessed photo).
    static int choiceForFixed(int fixed) {
        if (fixed < 0 || fixed >= PHOTO_THEMES.length) return 0;
        return fixed + 1;
    }

    // The stored photo for a dialog choice: 0 -> -1 (random),
    // 1..6 -> photo choice-1, anything else -> -1.
    static int photoForChoice(int choice) {
        if (choice < 1 || choice > PHOTO_THEMES.length) return RANDOM;
        return choice - 1;
    }

    // Whether a stored value names a real photo (0..5). The single
    // gate every caller uses before trusting a stored photo.
    static boolean isFixed(int fixed) {
        return fixed >= 0 && fixed < PHOTO_THEMES.length;
    }

    // The photo a name belongs to: themeNames is RoomView.THEMES'
    // names in order; a photo outside 0..5, a null/short array or
    // a missing name yields null, never a guess.
    static String nameForPhoto(String[] themeNames, int photo) {
        if (themeNames == null || photo < 0 || photo >= PHOTO_THEMES.length) return null;
        int ti = PHOTO_THEMES[photo];
        if (ti < 0 || ti >= themeNames.length) return null;
        String n = themeNames[ti];
        return (n == null || n.length() == 0) ? null : n;
    }

    // The photo the Room shows: a fixed 0..5 wins, anything else
    // keeps the roll the caller already took (the v12.91 random).
    static int effectivePhoto(int fixed, int rolled) {
        return isFixed(fixed) ? fixed : rolled;
    }
}
