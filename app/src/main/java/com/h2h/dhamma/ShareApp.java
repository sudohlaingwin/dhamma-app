package com.h2h.dhamma;

// v15.47 (slice 1948): "Share this app" — the Settings -> About row
// shares the app's public home through the system share sheet, so
// the listener can hand the app to a friend in one tap. The public
// home is the GitHub repo: it is where the in-app updater reads
// releases, so the link a friend opens always lands on the newest
// published build, never a stale APK. Pure text composition: no
// Android import, no Lang — the Activity supplies the display name
// (the manifest label) and the chooser title.
public class ShareApp {
    public static final String REPO_URL = "https://github.com/sudohlaingwin/dhamma-app";

    public static String shareText(String appName) {
        if (appName == null || appName.trim().length() == 0) return REPO_URL;
        return appName.trim() + "\n" + REPO_URL;
    }
}
