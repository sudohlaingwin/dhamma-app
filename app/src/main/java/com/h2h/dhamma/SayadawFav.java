package com.h2h.dhamma;

// v15.45 (slice 1939): the Talks header sayadaw-favourite star
// — the sayadaw-favourite toggle for the Talks screen. Until
// this slice the only way to favourite a sayadaw was Main's
// hidden grid-card long-press, so a listener browsing a
// sayadaw's talks had to leave the screen to star them; every
// streaming library puts Follow / heart on the artist page
// itself, and this header already counts that sayadaw's
// favourited talks without offering the sayadaw favourite.
// Pure set logic only: the glyph, the membership read and the
// flip. The Activity owns the prefs read/write, the button,
// the toast and the colour; the core never touches Android.
public class SayadawFav {
    // The glyphs: filled gold star while favourited, hollow
    // star while not — the same ★/☆ pair the fav_added /
    // fav_removed toasts already carry.
    static final String GLYPH_FAV = "\u2605";
    static final String GLYPH_PLAIN = "\u2606";

    // The star glyph for a favourite state.
    static String glyph(boolean fav) {
        return fav ? GLYPH_FAV : GLYPH_PLAIN;
    }

    // Null-safe membership: a null set, a null name or an empty
    // name never reads favourited.
    static boolean isFav(java.util.Set<String> favs, String name) {
        if (favs == null || name == null || name.length() == 0) return false;
        return favs.contains(name);
    }

    // Flip the name in the set and return the new state: true
    // when the name was added, false when it was removed. A
    // null set, a null name or an empty name is a false no-op
    // that touches nothing.
    static boolean toggle(java.util.Set<String> favs, String name) {
        if (favs == null || name == null || name.length() == 0) return false;
        if (favs.contains(name)) {
            favs.remove(name);
            return false;
        }
        favs.add(name);
        return true;
    }
}
