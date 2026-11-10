package com.h2h.dhamma;

import android.content.Context;
import android.content.SharedPreferences;
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
public class TalkListCache {
    private static final int MAX_ENTRIES = 500;

    private static String keyFor(String pageUrl) {
        return "talkcache_" + String.format("%08x", pageUrl == null ? 0 : pageUrl.hashCode());
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace("\u0001", "").replace("\u0002", "")
                .replace("\n", " ").replace("\r", " ").trim();
    }

    // Save a successfully loaded list. Titles and URLs only.
    public static void save(Context c, String pageUrl, List<String[]> pairs) {
        if (c == null || pageUrl == null || pairs == null || pairs.isEmpty()) return;
        try {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (String[] p : pairs) {
                if (p == null || p.length < 2) continue;
                String t = clean(p[0]);
                String u = clean(p[1]);
                if (u.length() == 0) continue;
                if (sb.length() > 0) sb.append('\u0002');
                sb.append(t).append('\u0001').append(u);
                if (++n >= MAX_ENTRIES) break;
            }
            if (sb.length() == 0) return;
            SharedPreferences sp = c.getSharedPreferences("dhamma", Context.MODE_PRIVATE);
            sp.edit().putString(keyFor(pageUrl), sb.toString())
                    .putLong(keyFor(pageUrl) + "_ts", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {}
    }

    // Load the last saved list for this sayadaw, or null when none
    // was ever saved (first visit) or it cannot be read.
    public static List<String[]> load(Context c, String pageUrl) {
        if (c == null || pageUrl == null) return null;
        try {
            SharedPreferences sp = c.getSharedPreferences("dhamma", Context.MODE_PRIVATE);
            String raw = sp.getString(keyFor(pageUrl), null);
            if (raw == null || raw.length() == 0) return null;
            List<String[]> out = new ArrayList<String[]>();
            for (String entry : raw.split("\u0002")) {
                int sep = entry.indexOf('\u0001');
                if (sep <= 0) continue;
                String t = entry.substring(0, sep);
                String u = entry.substring(sep + 1);
                if (u.length() == 0) continue;
                out.add(new String[]{t, u});
            }
            return out.isEmpty() ? null : out;
        } catch (Exception ignored) {
            return null;
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
