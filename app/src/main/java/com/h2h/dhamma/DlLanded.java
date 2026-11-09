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
                    String m = map.has(s) ? map.optString(s, "") : null;
                    if (m != null && m.length() > 0) excluded.add(m);
                }
            }
            String mapped = map.has(url) ? map.optString(url, "") : null;
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

    // Room fileClaimedByOther shape, over the already-read map.
    private static boolean isClaimedByOther(org.json.JSONObject map, String filename, String url) {
        try {
            java.util.Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!k.equals(url) && filename.equals(map.optString(k, ""))) return true;
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
