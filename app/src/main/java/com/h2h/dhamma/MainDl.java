package com.h2h.dhamma;

// v15.56 (slice 1997): Main-local download helpers' pure core —
// the "Download" verb on the Main "New talks" rail cards (piece
// 3/5) reads through here. MainActivity owns the Android side
// (DlLanded.isDownloaded for the landed check, the DownloadManager
// enqueue, the DlLock record, and the dlQueued JSON parse below);
// this core owns the two rules a JVM test can pin without a
// Context or disk:
//  - canOffer: the v11.70 offer gate — neither downloaded nor
//    already queued.
//  - isQueued: queued membership over the parsed dlQueued list.
//    The list form is deliberate, not incidental: the parse
//    (new org.json.JSONArray) lives in MainActivity because
//    org.json constructors are stubs on the plain JVM — probed
//    first-hand this run (RuntimeException "Stub!" from
//    org.json.JSONArray.<init> under android.jar), so a
//    JSON-string core could never return true in WgTest, and the
//    first version of this core did exactly that (9 false
//    negatives, fixed here). Deliberately NOT the Talks/History
//    raw-equals shape (talkIsDlQueued / histIsDlQueued compare the
//    stored entry with == on the raw string): the record banks the
//    canonical https form (v13.71) while a card URL can be spelled
//    http (the 1737 absolute-http hrefs), so a raw-equals scan
//    would miss a queued entry spelled under the other scheme and
//    double-enqueue. The sameTalk scan covers both directions, so
//    the offer and the tap can never disagree about a queued talk.
public final class MainDl {
    private MainDl() {}

    static boolean canOffer(boolean downloaded, boolean queued) {
        return !downloaded && !queued;
    }

    static boolean isQueued(java.util.List<String> queued, String url) {
        if (queued == null || url == null || url.length() == 0) return false;
        for (String e : queued) {
            if (e != null && e.length() > 0 && SiteHttp.sameTalk(e, url)) return true;
        }
        return false;
    }
}
