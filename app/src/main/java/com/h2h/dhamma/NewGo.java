package com.h2h.dhamma;

// v15.70 (slice 2079): "Go to sayadaw's talks" on the New talks
// row menu — the navigation verb the New talks row menu lacked
// that the library row (1944), the History row (1995, HistGo),
// the Room drawer foreign row (1985), the Main rail menus
// (1987-1991), the Bookmarks row (2077, BmGo) and the Favourites
// row (2078, FavGo) all have. A listener who had just found a
// new talk could play from it, queue it, or star it from the row
// menu, but never open that sayadaw's full Talks list — the row
// names its sayadaw and the bundled catalog resolves it to a page
// (catalogFor, the cached canonicalized read the play verbs
// already pay for). Pure gate math only: the Activity owns the
// catalog read, the menu offer and the Talks open; the core never
// touches Android, assets, prefs, or the queue.
public class NewGo {
    // The New talks row menu offers the navigation verb only
    // while the row names a sayadaw AND that sayadaw resolves
    // to a catalog page — the showPageRow gate (slice 1943) on
    // the entry's page slot, through GoSayadaw.canGo. Null-safe
    // on every input: a null/empty sayadaw, a null/short entry,
    // a null/empty page and the literal "null" string all read
    // as no-go, never a crash, never a dead tap.
    static boolean canOffer(String sd, String[] cat) {
        return sd != null && sd.length() > 0 && GoSayadaw.canGo(cat);
    }
}
