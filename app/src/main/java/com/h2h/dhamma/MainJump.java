package com.h2h.dhamma;

// v15.57 (slice 2004): "Jump to the sounding sayadaw" — the Main
// grid's floating gold ▶ button that glides to the sounding
// sayadaw's card when it has scrolled off-screen. The jump
// already existed as a hidden gesture — the title long-press
// (v4.18) — but while browsing with a live session, a listener
// who scrolled away from the sounding card had no visible way
// back: the same visible, reachable-form class as the Talks
// jump-to-playing (1994) and the library jump-to-playing (2002).
// Pure index logic only: the Activity owns the button, the scroll
// listener, the glide and the fade; the core never touches
// Android, prefs, or the queue. The window rule is the REUSED
// JumpPlaying.wantShown (target off-screen, above or below);
// this core is only the name lookup, because the grid renders
// `shown` directly — no full/display split, no needsClear.
public class MainJump {
    // The shown-list index of the sounding sayadaw: an exact-name
    // scan over the grid's shown names (the v4.18 long-press rule
    // verbatim — .equals, first match wins). -1 while the name is
    // null or empty or no card matches it; null entries never
    // match. Case-sensitive: catalog names are exact, and a
    // different-case name is a different sayadaw; a name with a
    // trailing space is a different name too.
    static int findIndex(java.util.List<String> names, String sounding) {
        if (names == null || sounding == null || sounding.length() == 0) return -1;
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (n != null && n.equals(sounding)) return i;
        }
        return -1;
    }
}
