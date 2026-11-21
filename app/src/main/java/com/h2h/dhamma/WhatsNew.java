package com.h2h.dhamma;

// v15.43 (slice 1927): "What's new" — the once-per-version release
// note every major streaming app shows on first launch after an
// update (Spotify / YouTube Music / Apple Music all announce the
// new version). Pure gate + keys only; MainActivity owns the dialog
// and the prefs write, so this file is unit-testable with no
// Android runtime (WgTest section 18).
public class WhatsNew {
    // Prefs key (SharedPreferences "dhamma"): the versionCode the
    // note was last shown for. 0 / absent = a fresh install, or a
    // build from before this feature — seed silently, never show.
    public static final String PREF_KEY = "whatsNewSeenCode";

    // Stable Lang keys for the dialog chrome; the body is
    // per-version ("whatsnew_body_<code>") so each shipped batch
    // writes its own note and an old body never shows under a new
    // version.
    public static final String TITLE_KEY = "whatsnew_title";
    public static final String OK_KEY = "whatsnew_ok";

    // Show only on a real upgrade: a stored code exists (not a
    // fresh install) and is older than the running build. Same
    // version, a downgrade, or an unknown running code never show.
    public static boolean shouldShow(int storedCode, int currentCode) {
        return storedCode > 0 && currentCode > 0 && storedCode < currentCode;
    }

    public static String bodyKey(int versionCode) {
        return "whatsnew_body_" + versionCode;
    }
}
