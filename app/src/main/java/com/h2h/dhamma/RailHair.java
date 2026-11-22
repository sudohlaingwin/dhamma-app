package com.h2h.dhamma;

// v15.60 (slice 2020): listened-progress hairline fraction for
// the Main rail cards — the Talks-row rule (v13.97, slice 1092)
// as a pure function, so the sim and WgTest pin the same numbers
// the Activity paints. 0 = hidden: the talk was never
// meaningfully heard (spot at or below 5s, the family rule — a
// share, a mark and a line all agree on 5s) or no duration was
// ever stored (a duration of 0 or less can never paint a
// fraction, the talkDurGet rule). Otherwise the spot over the
// duration, clamped to 1 and never below a 3% sliver, so a
// barely-started talk still reads as a visible gold dash — the
// Talks-row sliver, verbatim. No Android imports: pure ints and
// floats only, so WgTest calls it with no runtime.
public class RailHair {
    public static final int MIN_POS_MS = 5000;
    public static final float MIN_SLIVER = 0.03f;

    public static float fraction(int posMs, int durMs) {
        if (posMs <= MIN_POS_MS) return 0f;
        if (durMs <= 0) return 0f;
        float f = (float) posMs / (float) durMs;
        if (f > 1f) f = 1f;
        if (f < MIN_SLIVER) f = MIN_SLIVER;
        return f;
    }
}
