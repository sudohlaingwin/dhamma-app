package com.h2h.dhamma;

// v15.70 (slice 2080): "Share talk" on the New talks row menu -
// the share verb the New talks row menu lacked that the History
// row (v13.77, menu_share), the Talks chooser long-press dialog
// (v7.82, share first) and the library row menu (v9.55,
// dl_menu_share) all have. A listener who had just found a new
// talk could play from it, queue it, star it or open its
// sayadaw's talks from the row menu, but never share it - the
// way to share was Main + search or the chooser. The 2071 header
// names share explicitly as a future verb on this same menu.
// Pure string + map math only: the Activity owns the dlMap read,
// the menu offer and the share sheet; the core never touches
// Android, assets, prefs, or the queue.
public class NewShare {
    // The row offers the share verb only while it resolves to a
    // stream URL - a blank URL offers nothing, never a dead tap.
    static boolean canOffer(String streamUrl) {
        return streamUrl != null && streamUrl.length() > 0;
    }
    // The shared text - the History share shape (v13.77): the
    // sayadaw's name, an em dash, the title, the stream URL on
    // its own line, then the dhammadownload.com credit the
    // caller passes in (share_source). Null-safe on every input:
    // a blank sayadaw drops the "name - " prefix, a null title
    // reads as empty, a null URL or source reads as empty.
    static String shareText(String sd, String title, String streamUrl, String source) {
        String s = "";
        if (sd != null && sd.length() > 0) s = sd + " \u2014 ";
        if (title != null) s += title;
        s += "\n" + (streamUrl != null ? streamUrl : "") + (source != null ? source : "");
        return s;
    }
    // The row's stream URL - the histStreamUrlFor shape (v13.77)
    // over the dlMap the Activity parses once: a URL-form row
    // shares its own URL; a path-form row (a downloaded file)
    // resolves through the dlMap reverse lookup with the
    // exactly-one-claimant rule (v8.44 - a dual-claimed file
    // names no talk rather than the wrong one), so a deleted
    // file with no claimant resolves to "" and offers no share,
    // never a guessed link. Null-safe on every input.
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
}
