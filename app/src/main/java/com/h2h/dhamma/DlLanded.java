package com.h2h.dhamma;

// v13.69 (audit-1369 F13): the ONE shared definition of "this URL is
// downloaded", used by the landed leg of all five dlQueued prunes
// (Room, Talks, Downloaded, Main, Settings).
//
// Before this, the five prunes encoded three different definitions:
// Downloaded dropped on a bare isFile() of the dlMap name (a 0-byte
// file counted as landed, a legacy file under a candidate name never
// did, no in-progress exclusion); Room dropped on its cache-only
// isDownloadedFile (length-gated since v11.90); Talks dropped on
// isTalkDownloaded (length-gated since v13.66, F7); Main and Settings
// had no landed leg at all. The outcome was identical everywhere - a
// mark drops once its job is gone - but the legs disagreed about what
// "downloaded" means, so a future leg change could strand marks on
// one screen.
//
// The shared definition: a non-empty file (length() > 0) under
// Downloads/Dhamma that belongs to this URL - the dlMap name for the
// URL, or one of its filename candidates (raw basename, stripped,
// %XX-decoded, stripped + ".mp3", the Room fileNameCandidates order)
// where a candidate counts only when no OTHER URL's dlMap entry
// claims that name - and never a live download's destination.
// The mapped branch trusts dlMap, like Room/Talks always did - no
// claim guard there.
//
// Pure predicate, no state: it reads dlMap fresh from prefs on every
// call (never Prefs.dlMapCached - the prunes hold DlLock, and the
// DlLock contract forbids a holder calling the Prefs caches) and
// never writes. Callers pass their own live-job destination names
// (inProgressNames; null means no exclusion, the old Downloaded leg's
// behaviour on a failed DownloadManager query) and their live source
// URLs, whose dlMap names join the exclusion - a Wi-Fi-waiting job
// exposes no destination yet, so without the source half its mapped
// name would read as landed (Room inProgressDlFilenames / Talks
// talkRefreshInProgressDlNames already fold this in; the Library /
// Main / Settings prunes only ever built the destination half).
public final class DlLanded {
    private DlLanded() {}

    // v14.89 (slice 1747): the shared prune leg is the LAST raw dlMap
    // lookup pair in the downloaded-mark class — the Room (v14.87) and
    // Talks chooser (v14.88) twins already fixed theirs. Same
    // provenance, same three-form rule: dlMap keys are written under
    // the URL form current at download time, and the same talk is
    // spelled both ways in the wild: a file downloaded by the
    // delivered v13.57 build over the http fallback keeps its legacy
    // http key forever (the v13.66 migration does NOT migrate dlMap keys),
    // while newer downloads record the canonical https key (v13.59),
    // and one catalog page carries absolute-http mp3 hrefs the parser
    // keeps as-is (Dr Nandamalabhivamsa, 15 hrefs, 1737) —
    // so a talk downloaded under one scheme and listed under the other
    // missed its mapped name here AND had its basename blocked by the
    // twin's own claim below: all five prunes (Room, Talks, Library,
    // Main, Settings) kept the queue hourglass for a file already on
    // disk, and every batch verb skipped the talk as "already queued".
    // The mapped lookup now tries the raw URL, then its canonical
    // https form, then its downgraded http form (raw first, so a talk
    // downloaded twice under both schemes keeps the file its current
    // spelling recorded); the claim guard below treats the scheme-twin
    // as the same talk, not another talk. The live-source exclusion
    // leg reads through the same helper, so a Wi-Fi-waiting job whose
    // source is spelled under the other scheme from its dlMap key
    // still excludes its mapped name (without it the file would read
    // as landed while the job is still live). The v8.40/v8.50 rules
    // are unchanged: a name claimed by a DIFFERENT talk's key still
    // blocks the basename fallback. The enqueue-filename twins
    // (AutoDownloadJob mappedFileName68 / fileClaimedByOther68, Fx
    // stripDownloadFileName) and the three prune no-live-job legs
    // (DownloadedActivity mapped1019, Main mapped1020, Settings
    // mapped1021) keep their raw lookups for now — their slices
    // follow.
    // The on-disk filename that makes this URL downloaded, or null.
    public static String downloadedFileName(android.content.Context ctx, String url,
            java.util.Set<String> inProgressNames, java.util.Set<String> liveSourceUrls) {
        if (ctx == null || url == null) return null;
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma");
            org.json.JSONObject map = new org.json.JSONObject(
                    ctx.getSharedPreferences("dhamma", android.content.Context.MODE_PRIVATE)
                            .getString("dlMap", "{}"));
            java.util.Set<String> excluded = new java.util.HashSet<String>();
            if (inProgressNames != null) excluded.addAll(inProgressNames);
            if (liveSourceUrls != null) {
                for (String s : liveSourceUrls) {
                    String m = mappedName(map, s);
                    if (m != null && m.length() > 0) excluded.add(m);
                }
            }
            String mapped = mappedName(map, url);
            if (mapped != null && mapped.length() == 0) mapped = null;
            if (mapped != null && !excluded.contains(mapped)) {
                java.io.File f = new java.io.File(dir, mapped);
                if (f.isFile() && f.length() > 0) return mapped;
            }
            for (String name : fileNameCandidates(url)) {
                if (name == null || name.length() == 0) continue;
                if (name.equals(mapped)) continue;
                if (excluded.contains(name)) continue;
                if (isClaimedByOther(map, name, url)) continue;
                java.io.File f = new java.io.File(dir, name);
                if (f.isFile() && f.length() > 0) return name;
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static boolean isDownloaded(android.content.Context ctx, String url,
            java.util.Set<String> inProgressNames, java.util.Set<String> liveSourceUrls) {
        return downloadedFileName(ctx, url, inProgressNames, liveSourceUrls) != null;
    }

    // The dlMap name for a URL under any of its spellings: raw first,
    // then its canonical https form, then its downgraded http form —
    // the Room v14.87 / Talks v14.88 three-form rule in shared form.
    // Returns null when no form has an entry; an empty entry is
    // returned as-is (callers treat "" as absent).
    // v14.90 (slice 1748): package-private since this slice — the
    // enqueue-filename twins read through it too
    // (AutoDownloadJob.mappedFileName68, Fx.stripDownloadFileName),
    // so the three-form rule lives in exactly one place; the
    // Map overload below serves downloadFileNameFor68's HashMap form.
    static String mappedName(org.json.JSONObject map, String url) {
        if (map == null || url == null) return null;
        if (map.has(url)) return map.optString(url, "");
        String canon = SiteHttp.identityUrl(url);
        if (canon != null && !canon.equals(url) && map.has(canon)) return map.optString(canon, "");
        String down = SiteHttp.downgrade(url);
        if (down != null && !down.equals(url) && map.has(down)) return map.optString(down, "");
        return null;
    }

    // v14.90 (slice 1748): the Map<String, String> form of the same
    // three-form rule, for AutoDownloadJob.downloadFileNameFor68,
    // which builds its dlMap as a HashMap from the stored JSON.
    // Same order (raw, canonical, downgrade), same empty-entry
    // passthrough, same null when no form matches.
    static String mappedName(java.util.Map<String, String> map, String url) {
        if (map == null || url == null) return null;
        if (map.containsKey(url)) return map.get(url);
        String canon = SiteHttp.identityUrl(url);
        if (canon != null && !canon.equals(url) && map.containsKey(canon)) return map.get(canon);
        String down = SiteHttp.downgrade(url);
        if (down != null && !down.equals(url) && map.containsKey(down)) return map.get(down);
        return null;
    }

    // Room fileClaimedByOther shape, over the already-read map.
    private static boolean isClaimedByOther(org.json.JSONObject map, String filename, String url) {
        try {
            String self = SiteHttp.identityUrl(url);
            java.util.Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!SiteHttp.identityUrl(k).equals(self) && filename.equals(map.optString(k, ""))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // Room fileNameCandidates, verbatim order: raw basename, stripped,
    // %XX-decoded, stripped + ".mp3" when it lacks .mp3.
    private static String[] fileNameCandidates(String url) {
        String base = url.substring(url.lastIndexOf('/') + 1);
        String stripped = base;
        int q = stripped.indexOf('?');
        if (q >= 0) stripped = stripped.substring(0, q);
        int h = stripped.indexOf('#');
        if (h >= 0) stripped = stripped.substring(0, h);
        String decoded = stripped;
        try {
            decoded = java.net.URLDecoder.decode(stripped.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {}
        String dest16 = null;
        if (stripped.length() > 0 && !stripped.toLowerCase().endsWith(".mp3"))
            dest16 = stripped + ".mp3";
        if (decoded.equals(stripped) && stripped.equals(base))
            return dest16 == null ? new String[]{base} : new String[]{base, dest16};
        if (decoded.equals(stripped))
            return dest16 == null ? new String[]{base, stripped} : new String[]{base, stripped, dest16};
        return dest16 == null ? new String[]{base, stripped, decoded} : new String[]{base, stripped, decoded, dest16};
    }
}
