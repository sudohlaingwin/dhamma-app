package com.h2h.dhamma;

// v15.46 (slice 1944): "Go to sayadaw's talks" — the
// Downloaded row menu's first navigation verb. A downloaded
// talk's sayadaw (recorded at download time, resolved through
// the bundled catalog to {group, page, photo} by the v7.08
// resolver) opens that sayadaw's Talks list inside the app,
// so a listener who liked a downloaded talk reaches the full
// catalogue without going back to Main and searching. Pure
// entry math only: the Activity owns the catalog read, the
// menu offer and the Talks open; the core never touches
// Android, assets, or the catalog.
public class GoSayadaw {
    // The entry can open the Talks list only while it carries
    // a page URL — the showPageRow gate (slice 1943) on the
    // entry's page slot (index 1). Null-safe on every input:
    // a null entry, a short entry, a null/empty page, and the
    // literal "null" string all read as no-go, never a crash,
    // never a dead tap.
    static boolean canGo(String[] cat) {
        return cat != null && cat.length >= 2 && cat[1] != null
                && cat[1].length() > 0 && !"null".equals(cat[1]);
    }

    // The page URL, or "" when the entry carries none — the
    // Talks intent's "url" extra.
    static String page(String[] cat) {
        if (cat == null || cat.length < 2 || cat[1] == null) return "";
        return cat[1];
    }

    // The group/tradition, or "" — the Talks intent's "group"
    // extra (Main always sends one; a missing group hides the
    // info sheet's group line, slice 1941).
    static String group(String[] cat) {
        if (cat == null || cat.length < 1 || cat[0] == null) return "";
        return cat[0];
    }

    // The photo URL, or "" — the Talks intent's "photo" extra.
    static String photo(String[] cat) {
        if (cat == null || cat.length < 3 || cat[2] == null) return "";
        return cat[2];
    }
}
