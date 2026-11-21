package com.h2h.dhamma;

// v15.49 (slice 1960): "Recently downloaded" rail on Main — the
// Spotify "Your Library" / YouTube Music downloads shelf on the
// home screen: the newest finished downloads, one tap back into
// the Room (which plays the downloaded file, v1.57, resuming from
// the saved spot). Until this slice the home screen showed only
// the recently PLAYED rail (1930); a batch downloaded for offline
// listening — before a trip, on Wi-Fi — was reachable only through
// the Downloaded screen. Pure selection only: the Activity owns
// the directory scan, the prefs reads and the Room open. The order
// is the library's own latest-download-first order (dlSortMode 0):
// file lastModified desc. A file counts only when it is a finished
// .mp3 (length > 0), its name is claimed in dlMap (stream URL ->
// filename) so the Room can open it by stream URL, its dlSayadaw
// owner is in the catalog (the photo lookup), it is not still
// queued (dlQueued — the Main-pruned live-job set, so a partial
// file can never be picked, the v12.33 rule), and it is not the
// sounding talk. Duplicates by talk identity collapse to the
// newest file. Titles follow the library Entry rule
// (DownloadedActivity.Entry v1.48/v1.90).
public class DlRecentRail {
    public static final int MAX_CARDS = 5;

    public static class FileRow {
        public final String name;
        public final long mod;
        public final long len;
        public FileRow(String name, long mod, long len) {
            this.name = name == null ? "" : name;
            this.mod = mod;
            this.len = len;
        }
    }

    // The library Entry title rule: strip .mp3, URL-decode %XX (a
    // literal '+' stays '+'), '_'/'-' -> ' ', strip a trailing
    // " _8-hex" collision suffix, trim; empty falls back to the
    // raw name, like Entry does.
    public static String titleFor(String fileName) {
        if (fileName == null) return "";
        String n = fileName;
        if (n.toLowerCase().endsWith(".mp3")) n = n.substring(0, n.length() - 4);
        try { n = java.net.URLDecoder.decode(n.replace("+", "%2B"), "UTF-8"); }
        catch (Exception ignored) {}
        n = n.replace('_', ' ').replace('-', ' ').trim();
        n = n.replaceAll(" [0-9a-f]{8}$", "");
        if (n.length() == 0) n = fileName;
        return n;
    }

    static String canonical(String url) {
        return SiteHttp.identityUrl(url);
    }

    public static java.util.List<Prefs.HistEntry> pick(
            java.util.List<FileRow> files,
            java.util.Map<String, String> dlMap,
            java.util.Map<String, String> dlSayadaw,
            java.util.Set<String> queuedUrls,
            java.util.Set<String> catalogNames,
            String soundingUrl) {
        java.util.List<Prefs.HistEntry> out =
                new java.util.ArrayList<Prefs.HistEntry>();
        if (files == null || dlMap == null || dlSayadaw == null) return out;
        // Reverse map, filename -> canonical stream URL. Two URLs
        // can never claim one filename (the collision suffix), but
        // the core stays deterministic anyway: dlMap keys in URL
        // order, first claim wins.
        java.util.List<String> urls =
                new java.util.ArrayList<String>(dlMap.keySet());
        java.util.Collections.sort(urls);
        java.util.Map<String, String> byName =
                new java.util.HashMap<String, String>();
        for (String u : urls) {
            if (u == null) continue;
            String f = dlMap.get(u);
            if (f == null || f.length() == 0) continue;
            if (!byName.containsKey(f)) byName.put(f, canonical(u));
        }
        // Owners by canonical URL too, so a pre-v13.59 http-form
        // dlSayadaw key still resolves (same URL-order rule).
        java.util.List<String> ownerKeys =
                new java.util.ArrayList<String>(dlSayadaw.keySet());
        java.util.Collections.sort(ownerKeys);
        java.util.Map<String, String> owners =
                new java.util.HashMap<String, String>();
        for (String k : ownerKeys) {
            if (k == null) continue;
            String c = canonical(k);
            if (c != null && !owners.containsKey(c)) owners.put(c, dlSayadaw.get(k));
        }
        java.util.Set<String> queued = new java.util.HashSet<String>();
        if (queuedUrls != null) for (String q : queuedUrls) {
            String c = canonical(q);
            if (c != null) queued.add(c);
        }
        // Newest first, stable on ties (the scan order, like the
        // library's stable lastModified-desc sort).
        java.util.List<FileRow> sorted =
                new java.util.ArrayList<FileRow>(files);
        java.util.Collections.sort(sorted, new java.util.Comparator<FileRow>() {
            @Override public int compare(FileRow a, FileRow b) {
                return Long.compare(b.mod, a.mod);
            }
        });
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (FileRow f : sorted) {
            if (out.size() >= MAX_CARDS) break;
            if (f == null) continue;
            if (f.name == null || f.name.length() == 0) continue;
            if (!f.name.toLowerCase().endsWith(".mp3")) continue;
            if (f.len <= 0) continue;
            String url = byName.get(f.name);
            if (url == null || url.length() == 0) continue;
            if (queued.contains(url)) continue;
            String id = canonical(url);
            if (id == null) continue;
            if (seen.contains(id)) continue;
            if (soundingUrl != null && SiteHttp.sameTalk(url, soundingUrl)) continue;
            String sayadaw = owners.get(url);
            if (sayadaw == null) sayadaw = owners.get(id);
            if (sayadaw == null || sayadaw.length() == 0) continue;
            if (catalogNames == null || !catalogNames.contains(sayadaw)) continue;
            String title = titleFor(f.name);
            if (title == null || title.trim().length() == 0) continue;
            seen.add(id);
            out.add(new Prefs.HistEntry(url, sayadaw, title, 0, f.mod));
        }
        return out;
    }
}
