package com.h2h.dhamma;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

// v13.57 (offline resilience — the 2026-11-10 Redmi Note 14 Pro
// incident: his phone could not reach dhammadownload.com at all —
// talk-list fetch failed with the generic toast and an empty list,
// and no sayadaw photo loaded either, while the site was up from
// here. A failed load used to leave the listener with nothing: a
// generic "check the internet" toast and an empty list, even though
// the app had loaded this sayadaw's list fine before. This helper
// keeps the last successfully loaded list per sayadaw on the phone
// (titles + mp3 URLs only, no audio) so a failed load can show it
// instead of an empty screen — downloaded talks in it still play
// offline — and it names the real failure reason (DNS / timeout /
// connect / server) instead of the one generic message.
//
// v14.12 (slice 1293 — 1290 NEW 2): the cache moves off
// SharedPreferences into one file per sayadaw, and the 500 cap is
// gone. The cap truncated 13 pages (measured live in slice 1290:
// Nandamalabhivamsa 3,101 talks, Sitagu 2,020, Mrauk-U 1,862 — the
// saved offline fallback silently kept only the first 500, with no
// indication). Raising the cap inside prefs would not fix the real
// cost: the cache lived as one big string per sayadaw in the shared
// "dhamma" prefs file (Prefs uses the same file), so every save
// rewrote the WHOLE prefs XML — settings plus every cached list —
// and every app start parsed that whole XML on first access. A
// 3,101-talk list is a ~750 KB string (measured live in sim1557);
// all 13 over-500 lists together would be ~3.7 MB of prefs. Now each
// sayadaw gets its own file under getFilesDir()/talkcache/ — a save
// writes only that sayadaw's file (temp file + rename, so a torn
// write never leaves a half file behind), a load reads only that
// file, and there is no cap at all. Legacy prefs copies (v13.57–
// v14.11) are migrated lazily on first load, then removed. The
// public API is unchanged, so the Talks/Room call sites are
// untouched. The _ts timestamp is dropped with the prefs copy —
// it was write-only (no reader anywhere, 997/1153) and the file's
// lastModified carries the same information if it is ever needed.
public class TalkListCache {

    private static String keyFor(String pageUrl) {
        return "talkcache_" + String.format("%08x", pageUrl == null ? 0 : pageUrl.hashCode());
    }

    private static File cacheFile(Context c, String pageUrl) {
        File dir = new File(c.getFilesDir(), "talkcache");
        return new File(dir, keyFor(pageUrl) + ".txt");
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace("\u0001", "").replace("\u0002", "")
                .replace("\n", " ").replace("\r", " ").trim();
    }

    // Pure core (no Context): build the stored string from pairs.
    // Titles and URLs joined by \u0001, entries by \u0002 — the
    // same format the prefs copy used, so a migrated list is
    // byte-identical to the file a fresh save writes.
    static String buildRaw(List<String[]> pairs) {
        StringBuilder sb = new StringBuilder();
        for (String[] p : pairs) {
            if (p == null || p.length < 2) continue;
            String t = clean(p[0]);
            String u = clean(p[1]);
            if (u.length() == 0) continue;
            if (sb.length() > 0) sb.append('\u0002');
            sb.append(t).append('\u0001').append(u);
        }
        return sb.toString();
    }

    // Pure core: parse the stored string back into pairs. Malformed
    // entries are skipped, never fatal — a torn or hand-edited file
    // still yields the good talks.
    static List<String[]> parseRaw(String raw) {
        List<String[]> out = new ArrayList<String[]>();
        if (raw == null || raw.length() == 0) return out;
        for (String entry : raw.split("\u0002")) {
            int sep = entry.indexOf('\u0001');
            if (sep <= 0) continue;
            String t = entry.substring(0, sep);
            String u = entry.substring(sep + 1);
            if (u.length() == 0) continue;
            out.add(new String[]{t, u});
        }
        return out;
    }

    // Save a successfully loaded list. Titles and URLs only.
    public static void save(Context c, String pageUrl, List<String[]> pairs) {
        if (c == null || pageUrl == null || pairs == null || pairs.isEmpty()) return;
        try {
            String raw = buildRaw(pairs);
            if (raw.length() == 0) return;
            File f = cacheFile(c, pageUrl);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            File tmp = new File(dir, f.getName() + ".tmp");
            FileOutputStream os = new FileOutputStream(tmp);
            try { os.write(raw.getBytes("UTF-8")); } finally { os.close(); }
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
            // Drop any legacy prefs copy for this sayadaw, so the
            // old multi-MB string does not linger in the "dhamma"
            // prefs file after the file is in place (best-effort).
            try {
                SharedPreferences sp = c.getSharedPreferences("dhamma", Context.MODE_PRIVATE);
                sp.edit().remove(keyFor(pageUrl)).remove(keyFor(pageUrl) + "_ts").apply();
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    // Load the last saved list for this sayadaw, or null when none
    // was ever saved (first visit) or it cannot be read.
    public static List<String[]> load(Context c, String pageUrl) {
        if (c == null || pageUrl == null) return null;
        try {
            File f = cacheFile(c, pageUrl);
            if (f.exists()) {
                FileInputStream is = new FileInputStream(f);
                try {
                    byte[] bytes = new byte[(int) f.length()];
                    int off = 0, n;
                    while (off < bytes.length && (n = is.read(bytes, off, bytes.length - off)) >= 0) off += n;
                    List<String[]> out = parseRaw(new String(bytes, 0, off, "UTF-8"));
                    if (!out.isEmpty()) return out;
                } finally { is.close(); }
            }
            // Legacy copy (v13.57–v14.11) in the "dhamma" prefs file:
            // read it once, migrate it to a file, and drop the prefs
            // keys — the next load takes the file path above.
            SharedPreferences sp = c.getSharedPreferences("dhamma", Context.MODE_PRIVATE);
            String raw = sp.getString(keyFor(pageUrl), null);
            if (raw == null || raw.length() == 0) return null;
            List<String[]> out = parseRaw(raw);
            if (out.isEmpty()) return null;
            save(c, pageUrl, out);
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    // v15.41 (slice 1920): newly-added talks — pure core: the
    // identity URLs in the fresh list that the saved list does
    // not have. A null old list is a first visit, so nothing is
    // new (the whole list is a first sight, not an arrival);
    // a null fresh list is empty. Identity-compared, so an
    // http/https respelling of the same talk never reads new.
    static java.util.Set<String> diffNewIds(java.util.List<String[]> oldPairs,
            java.util.List<String> freshUrls) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        if (oldPairs == null || freshUrls == null) return out;
        java.util.Set<String> oldIds = new java.util.HashSet<String>();
        for (String[] p : oldPairs) {
            if (p != null && p.length > 1 && p[1] != null) oldIds.add(SiteHttp.identityUrl(p[1]));
        }
        for (String u : freshUrls) {
            String id = SiteHttp.identityUrl(u);
            if (id != null && !oldIds.contains(id)) out.add(id);
        }
        return out;
    }

    // v15.41 (slice 1920): the new-talk set store — same key as
    // the list cache plus "_new", in the shared "dhamma" prefs
    // file beside favTalks/playedTalks. Survives rotation and
    // restarts; replaced on every successful load, never cleared
    // on a failed one (no fresh list to compare against).
    // v15.41 (slice 1921): Main-grid dot gate — pure core: true
    // iff some identity URL in the new-set is in neither the
    // played set (finished) nor the resume-key set (saved spot
    // over 5s, i.e. started). The same gate as the Talks row
    // (1920), lifted to the card level: a card dots while its
    // sayadaw still has new talks nobody started or finished.
    // Null-safe on all three sets; the resume keys already
    // carry both the raw and the identity form (v13.65), and
    // the played set holds identity URLs (Talks row, v5.49),
    // so plain membership tests are exact.
    static boolean hasUnseenNew(java.util.Set<String> newIds,
            java.util.Set<String> playedIds, java.util.Set<String> resumeKeys) {
        if (newIds == null || newIds.isEmpty()) return false;
        for (String id : newIds) {
            if (id == null) continue;
            if (playedIds != null && playedIds.contains(id)) continue;
            if (resumeKeys != null && resumeKeys.contains(id)) continue;
            return true;
        }
        return false;
    }

    // v15.42 (slice 1923): Downloaded new-file diff — pure
    // core: the current scan's paths minus the saved seen-set.
    // A null seen set is a first visit, so nothing is new (the
    // whole shelf is a first sight, not an arrival); a null
    // current list is empty. Paths are compared exactly —
    // absolute file paths, no URL identity: a re-download
    // lands on the same path, so it is not new; a deleted-
    // then-redownloaded file left the seen-set when its leave
    // saved, so it dots again. Null members are skipped, so a
    // corrupt entry can never dot or crash the diff.
    static java.util.Set<String> diffDlNew(java.util.Set<String> seen,
            java.util.Set<String> current) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        if (seen == null || current == null) return out;
        for (String p : current) {
            if (p != null && !seen.contains(p)) out.add(p);
        }
        return out;
    }

    public static void saveNewSet(Context c, String pageUrl, java.util.Set<String> ids) {
        if (c == null || pageUrl == null) return;
        try {
            SharedPreferences sp = c.getSharedPreferences("dhamma", Context.MODE_PRIVATE);
            java.util.Set<String> keep = ids == null
                    ? new java.util.HashSet<String>() : new java.util.HashSet<String>(ids);
            sp.edit().putStringSet(keyFor(pageUrl) + "_new", keep).apply();
        } catch (Exception ignored) {}
    }

    public static java.util.Set<String> loadNewSet(Context c, String pageUrl) {
        if (c == null || pageUrl == null) return new java.util.HashSet<String>();
        try {
            SharedPreferences sp = c.getSharedPreferences("dhamma", Context.MODE_PRIVATE);
            java.util.Set<String> s = sp.getStringSet(keyFor(pageUrl) + "_new", null);
            return s == null ? new java.util.HashSet<String>() : new java.util.HashSet<String>(s);
        } catch (Exception ignored) {
            return new java.util.HashSet<String>();
        }
    }

    // The Lang key for the real failure reason. Walks one cause level
    // because HttpURLConnection sometimes wraps the root failure.
    // A certificate failure is its own key: his Redmi's live symptom
    // (2026-11-10 22:58) is exactly a cert error in Chrome while the
    // cert verifies valid from here — wrong phone date/time or TLS
    // interception on his network — so the app must name it and say
    // what to check, not hide it behind the generic message.
    // HTTP errors and anything unclassified fall back to the existing
    // generic long message, so no failure ever shows a blank reason.
    public static String reasonKey(Throwable e, int httpCode) {
        if (httpCode >= 400) return "talks_fetch_failed_long";
        Throwable t = e;
        for (int depth = 0; depth < 2 && t != null; depth++) {
            if (t instanceof javax.net.ssl.SSLException) return "fetch_reason_cert";
            if (t instanceof java.security.cert.CertificateException) return "fetch_reason_cert";
            if (t instanceof java.net.UnknownHostException) return "fetch_reason_dns";
            if (t instanceof java.net.SocketTimeoutException) return "fetch_reason_timeout";
            if (t instanceof java.net.ConnectException) return "fetch_reason_connect";
            t = t.getCause();
        }
        return "talks_fetch_failed_long";
    }
}
