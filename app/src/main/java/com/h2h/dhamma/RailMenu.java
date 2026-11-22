package com.h2h.dhamma;

// v15.54 (slice 1987): long-press quick-actions on the Main
// "Recently played" rail cards — the rail had tap only since
// v15.43 (slice 1930); a listener who wanted to share a
// just-heard talk, copy its link, star it, or open its
// sayadaw's full list had to open the Room first (or find the
// talk again in Talks / the library). Pure offer + text rules
// only: the Activity owns the dialog, the clipboard write, the
// fav toggle, the share sheet and the Talks open; the core
// never touches Android, prefs, or the queue.
// v15.54 (slice 1988): the Main "Downloaded" rail reuses this
// core unchanged — its cards carry the same HistEntry + Sayadaw
// captures as the Recent rail, so the same four verbs apply.
// v15.54 (slice 1989): the Main "Bookmarks" rail reuses this
// core unchanged — its cards carry a BmRail.Card (a HistEntry +
// a mark minute), and the four verbs read the talk (c.e), so the
// same offer + text rules apply.
// v15.55 (slice 1990): the Main "Favourite talks" rail reuses
// this core unchanged — its cards carry a FavTalkRail.Card (a
// HistEntry + a saved spot), and the four verbs read the talk
// (c.e), so the same offer + text rules apply. Every card here
// is starred by definition, so the fav verb always offers the
// remove variant.
// v15.55 (slice 1991): the Main "New talks" rail reuses
// this core unchanged — its cards carry a NewTalkRail.Card (a
// HistEntry, spot 0 — the 1921 gate only ever picks talks nobody
// started), and the four verbs read the talk (c.e), so the same
// offer + text rules apply. The fav verb usually offers the add
// variant here.
// v15.55 (slice 1992): the Main "Bookmarks" rail appends a fifth
// verb after keys() — bm_menu_remove, the destructive "Remove
// this bookmark" — because only bm cards carry a mark minute to
// delete. The core stays unchanged: the four shared verbs keep
// their order (share / copy / fav / go) and the remove verb sits
// last, with its own confirm (the library delete's shape).
// v15.55 (slice 1993): the Main "Bookmarks" rail appends a sixth
// verb after menu_share — bm_menu_share_minute, "Share this
// bookmark" — because only bm cards carry a mark minute to share
// at. The text is BmShare.shareAtMinuteText (the card's own
// "12:34 · title" rendering in the chooser share shape); the
// core stays unchanged.
public class RailMenu {
    // The offer order, fixed: share first (muscle memory from
    // the chooser/drawer dialogs, where share is always first),
    // the copy-link twin beside it, the favourite toggle, then
    // the navigation verb last. The go verb is offered only
    // while the sayadaw carries an original-site page (the
    // GoSayadaw gate, 1944/1985); the copy verb only while the
    // card's url is worth copying (the RoomCopyLink gate, 1975).
    static java.util.List<String> keys(boolean isFav, boolean canGo,
            boolean canCopy) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        out.add("menu_share");
        if (canCopy) out.add("dl_menu_copy_link");
        out.add(isFav ? "menu_fav_remove" : "menu_fav_add");
        if (canGo) out.add("menu_go_sayadaw");
        return out;
    }

    // Favourite state read through the canonical identity (the
    // v13.65 rule — Fx.toggleFavTalk banks identityUrl, so a
    // legacy http url must read as a favourite too). Null-safe
    // on every input: a null set, a null url, or an url with no
    // identity all read as not-favourited, never a crash.
    static boolean isFav(java.util.Set<String> favTalks, String url) {
        if (favTalks == null || url == null) return false;
        String id = SiteHttp.identityUrl(url);
        if (id == null) return false;
        return favTalks.contains(id);
    }

    // The text share: the Talks chooser's shape verbatim —
    // sayadaw + em dash + title, newline, the talk's own
    // original link, then the share_source credit suffix (which
    // carries its own leading newline). Null-safe on every slot
    // so a blank title or a missing name can never crash the
    // sheet; the values pass through untouched otherwise, so
    // what shares is exactly what the card shows.
    static String shareText(String sayadawName, String title,
            String url, String source) {
        String n = sayadawName == null ? "" : sayadawName;
        String t = title == null ? "" : title;
        String u = url == null ? "" : url;
        String s = source == null ? "" : source;
        return n + " \u2014 " + t + "\n" + u + s;
    }
}
