package com.h2h.dhamma;

// v15.51 (slice 1970): "Add widget to Home screen" — the pure gate
// for the Settings Appearance row that asks the launcher to pin the
// Dhamma widget (Play/Pause/Next + current talk, v12.67) to the home
// screen. Pinning needs API 26+ (AppWidgetManager.requestPinAppWidget
// does not exist below Oreo) AND a launcher that supports pinned
// widgets; anything else hides the row, so the row never offers a
// pin the device cannot place. There is no "already pinned" state
// to read back — the framework offers no query for it — so unlike
// the 1945 shortcut pin the row never hides for being pinned.
public final class WidgetPin {
    private WidgetPin() {}

    public static final int MIN_SDK = 26;

    // API 26+ AND the launcher supports pinned widgets.
    public static boolean canPin(int sdkInt, boolean supported) {
        return sdkInt >= MIN_SDK && supported;
    }

    // The Settings row label.
    public static String labelKey() {
        return "settings_add_widget";
    }
}
