package com.h2h.dhamma;

// v15.76 (slice 2113): "Share playlist" — the pure share for
// a whole playlist. The lists screen could create, rename,
// delete and open a list, but never send one: every share
// verb in the app shares ONE talk (History v13.77, the Talks
// chooser v7.82, the library v9.55, New talks v15.70,
// Favourites v15.71, Bookmarks v15.71), and the Downloaded
// share-shown family shares a shown SET, never a named list.
// The text is the History shape per talk (BmTalkShare /
// FavShare: sayadaw, em dash, title, stream URL on its own
// line, then the share_source credit the caller passes in),
// numbered in list order (the Downloaded share-shown
// family's numbering), headed by the list's own name. A
// path-form ITEM url resolves through the dlMap
// exactly-one-claimant rule (the BmTalkShare.resolve
// mirror — a dual-claimed file names no talk rather than
// the wrong one); talks that resolve to nothing are skipped,
// not shared as bare titles. Pure string + map math only:
// the Activity owns the dlMap read, the menu offer and the
// share sheet; the core never touches Android, assets,
// prefs, or the queue.
public class PlShare {
    // The talk's stream URL — the BmTalkShare.resolve
    // mirror: a URL-form row shares its own URL; a
    // path-form row (a downloaded file) resolves through
    // the dlMap reverse lookup with the exactly-one-claimant
    // rule (v8.44 — a dual-claimed file names no talk
    // rather than the wrong one), so a deleted file with no
    // claimant resolves to "" and offers no share, never a
    // guessed link. Null-safe on every input.
    static String resolve(String url, java.util.Map<String, String> dlMap) {
        if (url == null || url.length() == 0) return "";
        if (!url.startsWith("/")) return url;
        if (dlMap == null) return "";
        String base = url.substring(url.lastIndexOf('/') + 1);
        String hit = null;
        int matches = 0;
        for (java.util.Map.Entry<String, String> e : dlMap.entrySet()) {
            if (base.equals(e.getValue())) { hit = e.getKey(); matches++; }
        }
        return matches == 1 ? hit : "";
    }

    // How many of the list's talks resolve to a stream URL —
    // the shareable count. A null list reads as nothing
    // shareable; the inputs are never mutated.
    static int countShareable(java.util.List<QueueEntry> items,
            java.util.Map<String, String> dlMap) {
        if (items == null) return 0;
        int n = 0;
        for (QueueEntry q : items) {
            if (q == null) continue;
            if (resolve(q.url, dlMap).length() > 0) n++;
        }
        return n;
    }

    // The list offers the share verb only while it has a
    // real name and at least one resolvable talk — a blank
    // name or an empty / unresolvable list offers nothing,
    // never a dead tap, never a header promising talks that
    // do not follow.
    static boolean canShare(String name, java.util.List<QueueEntry> items,
            java.util.Map<String, String> dlMap) {
        if (name == null || name.trim().length() == 0) return false;
        return countShareable(items, dlMap) > 0;
    }

    // The shared text — the list's own name as the header,
    // then one numbered line per shareable talk in list
    // order (sayadaw, em dash, title, stream URL on its own
    // line), then the dhammadownload.com credit the caller
    // passes in (share_source). Null-safe on every input: a
    // blank sayadaw drops the "name - " prefix, a null title
    // reads as empty, a null source reads as empty. A blank
    // name or no shareable talk reads as "" (never a header
    // promising talks that do not follow).
    static String shareText(String name, java.util.List<QueueEntry> items,
            java.util.Map<String, String> dlMap, String source) {
        if (name == null || name.trim().length() == 0) return "";
        if (items == null) return "";
        String s = name;
        int n = 0;
        for (QueueEntry q : items) {
            if (q == null) continue;
            String url = resolve(q.url, dlMap);
            if (url.length() == 0) continue;
            n++;
            s += "\n" + n + ". ";
            if (q.sayadaw != null && q.sayadaw.length() > 0) s += q.sayadaw + " \u2014 ";
            if (q.title != null) s += q.title;
            s += "\n" + url;
        }
        if (n == 0) return "";
        if (source != null) s += source;
        return s;
    }
}
