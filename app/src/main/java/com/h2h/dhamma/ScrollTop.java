package com.h2h.dhamma;

// v15.45 (slice 1940): the Talks "back to top" button — a
// floating gold ↑ that appears once the listener has scrolled
// deep into a long talk list and glides back to the top on one
// tap. The title tap (v4.12) already jumps to the top, but the
// title is off-screen exactly when the jump is needed, so deep
// in a 200-talk list the listener had to drag all the way back
// by hand; every long-list streaming screen (YouTube Music,
// Google Play) floats this button instead. Pure threshold logic
// only: the Activity owns the button, the scroll listener, the
// glide and the fade; the core never touches Android.
public class ScrollTop {
    // The first visible row at which the button appears: eight
    // rows is about one screen of talk rows, so the button never
    // shows on a short list or a small scroll, and a list that
    // never reaches eight rows never grows one.
    static final int THRESHOLD = 8;

    // True once the list has scrolled past the threshold row.
    // A negative or zero first-visible row (empty list, no
    // layout yet) never shows the button.
    static boolean visible(int firstVisible) {
        return firstVisible >= THRESHOLD;
    }
}
