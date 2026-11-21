package com.h2h.dhamma;

// v15.46 (slice 1945): "Add to Home screen" — the info
// sheet's launcher shortcut to this sayadaw's talks. A
// favourite sayadaw opens in one tap from the launcher,
// without opening the app first — the Android-standard
// pinned shortcut (API 26+), the same "add to home"
// Chrome and launchers offer for a page. Pure id/gate
// math only: the Activity owns the ShortcutManager, the
// intent and the pin request; the core never touches
// Android, the catalog, or any string the user sees.
public class HomeShortcut {
    // A stable shortcut id from the sayadaw's page URL (the
    // catalog identity — names can repeat across traditions,
    // page URLs cannot): "sayadaw_" + 8 hex digits of the
    // URL's hashCode, so re-pinning the same sayadaw
    // replaces the same shortcut. Null/empty URL -> "" (no
    // id, no pin — the gate refuses it first).
    static String idFor(String pageUrl) {
        if (pageUrl == null || pageUrl.length() == 0) return "";
        return "sayadaw_" + String.format("%08x", Integer.valueOf(pageUrl.hashCode()));
    }

    // The sheet row shows only while pinning is possible on
    // this device (API 26+ and the launcher supports pinned
    // shortcuts) AND the sayadaw has a page URL — the
    // showPageRow gate (slice 1943): a shortcut without a
    // page has nowhere to open.
    static boolean canShow(int sdkInt, boolean pinSupported, String pageUrl) {
        return sdkInt >= 26 && pinSupported && SayadawInfo.showPageRow(pageUrl);
    }

    // Already pinned while this id sits in the launcher's
    // pinned set (null-safe) — the row hides then, so the
    // sheet never offers a pin that is already on the home
    // screen.
    static boolean isPinned(java.util.Set<String> pinnedIds, String id) {
        return id != null && id.length() > 0 && pinnedIds != null && pinnedIds.contains(id);
    }
}
