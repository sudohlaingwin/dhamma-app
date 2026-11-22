package com.h2h.dhamma;

// v15.63 (slice 2037): the Bookmarks mini-player strip's resume
// decision, as a pure core — History's v13.54 strip sync computes
// these two matches inline; they are factored out here so WgTest
// can pin the REAL logic (the 2028 TalkIndex precedent). The strip
// sync in BookmarksActivity keeps History's branch bodies verbatim;
// only the two match computations delegate here. Both fail closed
// on nulls, empties and a null map: a match never names the wrong
// talk (the v8.44 exactly-one-claimant spirit — the room leg looks
// a filename up by URL, the library leg matches a filename back to
// its one talk through sameTalk).
// The core takes the download map as a plain Map, not the JSON
// string: the JVM test harness cannot construct org.json (the
// SDK android.jar stubs it — "Stub!"), so a JSON-taking core
// could never be pinned by WgTest; the sync parses the map once
// (dlMapNow2037) with the platform parser and hands it over.
// On-device behaviour is identical — the same JSONObject lookup,
// parsed once per tick instead of History's twice.
public class StripResume {

    // The saved spot belongs to the last Room talk: the saved URL
    // IS the last URL (sameTalk — an http/https twin still matches),
    // or the last URL's dlMap filename ends the saved path (a
    // path-form saved spot from a downloaded play). The v13.62
    // legacy-http leg: a legacy http dlMap key misses the raw
    // lookup, so the identity form is tried too.
    public static boolean roomMatch(String savedUrl, String lastUrl,
            java.util.Map<String, String> dlMap) {
        if (savedUrl == null || savedUrl.length() == 0) return false;
        if (lastUrl == null || lastUrl.length() == 0) return false;
        if (SiteHttp.sameTalk(savedUrl, lastUrl)) return true;
        String fn = dlMap == null ? null : dlMap.get(lastUrl);
        if (fn == null || fn.length() == 0)
            fn = dlMap == null ? null
                    : dlMap.get(SiteHttp.identityUrl(lastUrl));
        return fn != null && fn.length() > 0 && savedUrl.endsWith("/" + fn);
    }

    // The saved spot belongs to the last library file: the saved
    // path IS the file, or a dlMap URL whose filename is this
    // file's name and whose talk is the saved one (sameTalk, so an
    // http/https twin still matches). The v14.01 file gate (the
    // file exists, non-0-byte or in progress) stays in the sync —
    // this is the URL half only.
    public static boolean libMatch(String savedUrl, String lastLibPath,
            java.util.Map<String, String> dlMap) {
        if (savedUrl == null || savedUrl.length() == 0) return false;
        if (lastLibPath == null || lastLibPath.length() == 0) return false;
        if (savedUrl.equals(lastLibPath)) return true;
        if (dlMap == null) return false;
        String fn = new java.io.File(lastLibPath).getName();
        for (java.util.Map.Entry<String, String> e : dlMap.entrySet()) {
            if (SiteHttp.sameTalk(e.getKey(), savedUrl)
                    && fn.equals(e.getValue())) return true;
        }
        return false;
    }
}
