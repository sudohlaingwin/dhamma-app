package com.h2h.dhamma;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * v1.15: offline library — lists the .mp3 files the app downloaded into
 * Downloads/Dhamma and plays them through the existing AudioService, so
 * downloaded talks work with no internet and keep background playback.
 */
public class DownloadedActivity extends Activity {

    private static class Entry {
        File file;
        String title;
        // v11.89: size + date captured at scan time — getView used to stat() every
        // row on every bind and the 1s tick re-statted the playing row every second,
        // for values already known here (the footer sums them, the sort uses them).
        long len;
        long mod;
        Entry(File f) {
            file = f;
            len = f.length();
            mod = f.lastModified();
            String n = f.getName();
            if (n.toLowerCase().endsWith(".mp3")) n = n.substring(0, n.length() - 4);
            // v1.48: downloaded filenames are URL basenames, so a talk
            // used to show as "Mogok%20Sayadaw..." — decode %XX (a
            // literal '+' is kept as '+' instead of becoming a space).
            try { n = java.net.URLDecoder.decode(n.replace("+", "%2B"), "UTF-8"); }
            catch (Exception ignored) {}
            title = n.replace('_', ' ').replace('-', ' ').trim();
            // v1.90: a collision-suffixed download (see RoomActivity's
            // downloadFileNameFor, "01_9f3c2a1b.mp3") reads as its
            // plain talk name here — the hash is a storage detail,
            // and the date/size beside the row already tells two
            // same-named files apart. Only an exact trailing
            // _8-hex-hex suffix is stripped, so a real title ending
            // that way is the one rare casualty.
            title = title.replaceAll(" [0-9a-f]{8}$", "");
            if (title.length() == 0) title = f.getName();
        }
    }

    private List<Entry> entries = new ArrayList<Entry>();
    private List<Entry> shownDl = new ArrayList<Entry>();
    private String dlQuery = "";
    // v2.03: row-bind cache — getView used to read SharedPreferences
    // (isNight + fontSize) for every row on every scroll bind.
    // applyTheme() already runs on create and on return and rebinds,
    // so cache here; defaults are the normal-font day values.
    private boolean cachedDlNight = false;
    private int cachedDlFont = 1;
    // v11.45: cached UI language for the return dip, mirroring Main
    // v3.84 / Room v3.86 — null until applyTheme first runs, so a
    // first onResume never dips; see the onResume block below.
    private String cachedDlLang = null;
    // v2.02: the empty view's original "no downloads yet" text,
    // captured on first use — the filtered-empty word below
    // overwrites it, and it must come back when the filter clears.
    private String origEmptyText = null;
    private ListView dlListView;
    // v1.55: scroll position to restore once after a rotation (the
    // search text survived since v1.51, the scroll did not).
    private int savedDlScroll = -1;
    private DlAdapter adapter;
    private int dlSortMode = 0;
    // v4.28: favourites-only shelf filter — the mirror of Main's
    // v4.19 grid filter. The library gained ★ row marks in v4.24
    // but no way to view only ★ talks, so a listener with many
    // downloads scrolled the whole shelf to find them. No new
    // view: the search field's long-press toggles it (below).
    // v4.95: library filter views — the chooser gained filter
    // views in v4.94 (★ only / unlistened only) but the library
    // still had only its ★ toggle (v4.28), so a listener working
    // through downloads had no way to view just the talks not
    // heard yet. dlFilterMode 0 = all, 1 = ★ only, 2 =
    // unlistened only (a file whose name is not in the v4.24
    // libListenedFiles cache, i.e. its URL is not in
    // playedTalks — the same rule the chooser's mode 2 uses).
    // The long-press gesture cycles 0 -> 1 -> 2 -> 0; every other
    // consumer (search, sort, Play All, Random, Next/Prev) reads
    // shownDl, so they follow the filter with no further change.
    private int dlFilterMode = 0;

    // v4.95: true when every shelf file is fully listened — the
    // loadList guard and the cycle toast share it, so a mode-2
    // view with nothing to show falls back / says so instead of
    // sitting on a blank shelf. Files with no dlMap entry are in
    // neither mark set and count as unlistened, like a chooser
    // URL absent from playedTalks.
    private boolean allDlListened() {
        if (entries.isEmpty()) return false;
        for (Entry e : entries)
            if (!libListenedFiles.contains(e.file.getName())) return false;
        return true;
    }
    // v4.24: per-load mark cache for the library rows — the filenames
    // whose talk is a favourite (★) or fully listened (✓), plus the
    // one file carrying a saved resume position. Built once per
    // loadList from the Room's dlMap reverse lookup + favTalks /
    // playedTalks sets, so getView never parses JSON per row (the
    // v1.57 per-bind cost class) and the playing-row in-place clock
    // keeps overwriting only the subtitle, never the title marks.
    private java.util.Set<String> libFavFiles = new java.util.HashSet<String>();
    private java.util.Set<String> libListenedFiles = new java.util.HashSet<String>();
    // v7.45: tick snapshots of the fav/played URL sets behind the
    // mark cache above (null = not yet adopted). The cache is
    // rebuilt only on loadList, while the 1s tick rebinds only
    // playing state, so a favourite toggled on this screen's own
    // strip (v4.75 syncs the strip only, never the cache) or on
    // the notification card while the library sat foreground
    // (the shade never pauses it, so no return-refresh fires)
    // left shelf rows showing the old star until the next load.
    // The tick compares cheap set copies each second and
    // rebuilds + rebinds only on a real change, so steady
    // ticks still never touch the adapter.
    private java.util.Set<String> dlMarkFav45 = null;
    private java.util.Set<String> dlMarkPlayed45 = null;
    private String libResumePath = null;
    private int libResumeMs = 0;
    // v10.09: download-complete listener fields (Main v10.08 /
    // Talks v10.07 / Room v4.05 pattern) — the library is the last
    // playback screen without one. Card-only hook: the in-app
    // surfaces are already live while foreground (v4.00 dir
    // signature reloads the list on a landed file, v4.27 progress
    // scan refreshes the footer, strip ticks every second), and
    // onResume already reloads + refreshes the card — so this
    // receiver sends NOTHING in-app and never calls loadList.
    private android.content.BroadcastReceiver libDlCompleteReceiver = null;
    private boolean libDlCompleteRegistered = false;
    // v5.35: per-talk resume spots for every shelf file (path -> ms),
    // built once per load in refreshLibMarkCache below, so getView
    // never parses prefs/JSON per row (the v2.03 cost class).
    private java.util.Map<String, Integer> libResumeMap =
            new java.util.HashMap<String, Integer>();
    // v5.47: per-load sayadaw-name cache for the library rows —
    // filename -> recorded sayadaw from the v5.45 dlSayadaw map
    // (via the dlMap reverse lookup already built here), so
    // getView and the playing-row tick never parse JSON per row
    // (the v1.57/v2.03 cost class). A file with no record
    // simply has no entry, and its row keeps today's subtitle
    // exactly — never a guessed name.
    private java.util.Map<String, String> libSayadawMap =
            new java.util.HashMap<String, String>();

    private String dlSortLabel() {
        if (dlSortMode == 1) return Lang.t(DownloadedActivity.this, "sort_az");
        if (dlSortMode == 2) return Lang.t(DownloadedActivity.this, "sort_size");
        return Lang.t(DownloadedActivity.this, "sort_recent");
    }

    // v4.28: search-hint star prefix while the favourites-only
    // v9.91: the filter-prefix hint used to replay a base text
    // captured ONCE on first use (stripping a stale prefix if a
    // previous call already set one) — so a language picked in
    // Settings while this screen sat in the back stack left the
    // hint in the OLD language on every later loadList, while
    // applyTheme refreshed the subtitle/buttons/empty text from
    // Lang on return. The base is now read fresh from Lang on
    // every call, like Main's refreshFavHint: the Burmese base
    // is the same Lang string onCreate already set, so Burmese
    // display is byte-identical, and the capture (plus its dead
    // Burmese "အသစ် " legacy branch) is gone. A null field
    // (before onCreate assigns it) simply no-ops — the flag's
    // own guard in loadList runs after the field exists.
    private void refreshDlFavHint() {
        if (dlSearchField == null) return;
        String base = Lang.t(DownloadedActivity.this, "dl_search_hint");
        dlSearchField.setHint(dlFilterMode == 1 ? "★ " + base
                : dlFilterMode == 2 ? Lang.t(DownloadedActivity.this, "dl_hint_new_prefix") + base : base);
    }

    private void refreshLibMarkCache() {
        libFavFiles = new java.util.HashSet<String>();
        libListenedFiles = new java.util.HashSet<String>();
        libResumePath = null;
        libResumeMs = 0;
        libResumeMap = new java.util.HashMap<String, Integer>();
        libSayadawMap = new java.util.HashMap<String, String>();
        try {
            android.content.SharedPreferences pp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            java.util.Set<String> favs = pp.getStringSet("favTalks", null);
            java.util.Set<String> played = pp.getStringSet("playedTalks", null);
            java.util.Map<String, String> fileToUrl = new java.util.HashMap<String, String>();
            // v8.43: count dlMap claimants per filename — a dual-claimed
            // file (two talks mapped to one name, creatable by the
            // delivered v8.39, v8.40 stops new ones) has no trustworthy
            // reverse lookup: last-wins here used to award the
            // winning claimant's ★/✓/sayadaw/resume to audio that may
            // be the other talk's, the display twin of the v8.42
            // natural-end marking fix. Ambiguous files earn none of
            // those below instead of a wrong one; single-claim files
            // behave exactly as before.
            java.util.Map<String, Integer> fileClaimCount = new java.util.HashMap<String, Integer>();
            org.json.JSONObject map =
                    new org.json.JSONObject(pp.getString("dlMap", "{}"));
            java.util.Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String u = keys.next();
                String fn = map.optString(u, "");
                if (fn.length() > 0) {
                    fileToUrl.put(fn, u);
                    Integer c = fileClaimCount.get(fn);
                    fileClaimCount.put(fn, c == null ? 1 : c + 1);
                }
            }
            org.json.JSONObject sdMap =
                    new org.json.JSONObject(pp.getString("dlSayadaw", "{}"));
            for (Entry e : entries) {
                String u = fileToUrl.get(e.file.getName());
                if (u == null) continue;
                // v8.43: ambiguous file — no ★/✓/sayadaw from a
                // last-wins claimant (see above).
                if (fileClaimCount.get(e.file.getName()) != null
                        && fileClaimCount.get(e.file.getName()) > 1) continue;
                if (favs != null && favs.contains(u)) libFavFiles.add(e.file.getName());
                if (played != null && played.contains(u)) libListenedFiles.add(e.file.getName());
                String sd = sdMap.optString(u, "");
                if (sd.length() > 0) libSayadawMap.put(e.file.getName(), sd);
            }
            String savedUrl = pp.getString("lastPosUrl", "");
            int savedPos = pp.getInt("lastPos", 0);
            if (savedUrl.length() > 0 && savedPos > 5000) {
                for (Entry e : entries) {
                    // v8.43: the reverse-lookup branch only — a direct
                    // path-form match is the file itself and stays,
                    // but a dual-claimed file's last-wins URL must not
                    // re-point the global resume spot at wrong audio.
                    if (e.file.getAbsolutePath().equals(savedUrl)
                            || (fileClaimCount.get(e.file.getName()) == null
                                || fileClaimCount.get(e.file.getName()) <= 1)
                               && savedUrl.equals(fileToUrl.get(e.file.getName()))) {
                        libResumePath = e.file.getAbsolutePath();
                        libResumeMs = savedPos;
                        break;
                    }
                }
            }
            // v5.35: per-talk resume for the whole shelf, not just
            // the most recent talk — v5.34's map holds a spot per
            // talk (written by the service under both forms), but
            // the library only ever read the ONE global spot above,
            // so a half-heard file stopped being the latest talk
            // restarted from 0 on tap and showed no resume hint.
            // Parse the map once here (key "talkPosMap", Prefs'
            // TALK_POS_KEY), match each shelf file by its path
            // form, else by its stream form through the dlMap
            // reverse lookup already built above (a spot saved
            // while streaming, before this file was downloaded,
            // exists only under the stream URL). Same >5s rule
            // as every other resume read. The global spot wins
            // on overlay, so this map always agrees with what
            // savedPosForFile below actually plays.
            String raw = pp.getString("talkPosMap", "");
            if (raw.length() > 0) {
                java.util.Map<String, Integer> byKey =
                        new java.util.HashMap<String, Integer>();
                for (String line : raw.split("\n")) {
                    int t = line.indexOf('\t');
                    if (t <= 0) continue;
                    try {
                        int p = Integer.parseInt(line.substring(t + 1));
                        if (p > 5000) byKey.put(line.substring(0, t), p);
                    } catch (Exception ignored) {}
                }
                for (Entry e : entries) {
                    String p = e.file.getAbsolutePath();
                    Integer v = byKey.get(p);
                    if (v == null) {
                        // v8.43: path-form spots are the file itself
                        // and stay; stream-form spots via the reverse
                        // lookup are skipped for dual-claimed files.
                        if (fileClaimCount.get(e.file.getName()) == null
                                || fileClaimCount.get(e.file.getName()) <= 1) {
                            String u = fileToUrl.get(e.file.getName());
                            if (u != null) v = byKey.get(u);
                        }
                    }
                    if (v != null) libResumeMap.put(p, v);
                }
            }
            if (libResumePath != null)
                libResumeMap.put(libResumePath, libResumeMs);
        } catch (Exception ignored) {}
    }

    // v4.57: downloaded-library hand queue — the last playback
    // surface without queue building. The Room drawer (v4.32)
    // and the Talks chooser (v4.47) both build a hand queue, but
    // a listener lining up downloaded talks for offline play had
    // only list order + shuffle: no play-next, no chosen order.
    // One shared queue of file paths (Prefs queue_library, see
    // Prefs.saveQueueLib), cached here like the ★/✓ mark sets so
    // getView never reads prefs per row (the v2.03 cost class).
    // The cache is the truth every queue site reads: menu, row
    // marks, Next, auto-advance and row-tap trim. Refresh prunes
    // against entries (the full shelf, not the filtered shown
    // list, so a search filter can never wipe the queue) and
    // writes back only when pruning actually changed something.
    private java.util.ArrayList<String> libQueueCache = new java.util.ArrayList<String>();
    // v12.08: last-synced snapshot of the shared library queue -- the
    // Room v12.03 / Talks v12.04 idiom, for the library funnel below.
    // Updated wherever libQueueCache is rebuilt from disk
    // (refreshLibQueueCache) and after every funnel write (the written
    // list IS the last persisted state). The removed set is the snapshot
    // minus the caller's list: entries THIS window removed since the
    // last save, whose removal has not persisted yet, so the fresh saved
    // read still holds them.
    private java.util.ArrayList<String> lastSyncedLibQueue08 = new java.util.ArrayList<String>();
    // v10.19: paused-card download-mark sync, Downloaded prune-drop leg
    // of v10.06/v10.14/v10.15/v10.16/v10.17/v10.18 — the library had NO
    // dlQueued prune at all (Room v10.16 / Talks v10.17 each prune on
    // their rescans), and its v10.09 receiver covers only a job that
    // LANDS, so a FAILED or system-cancelled job left its entry (and
    // the paused card's hourglass) behind until some Room/Talks
    // rescan happened to prune it. Mirrors the Talks v7.77/v10.17
    // rule exactly: drop once the mapped file has landed in
    // Downloads/Dhamma, or once no live DownloadManager job owns the
    // URL (PENDING/RUNNING/PAUSED, or unknown status, app-scoped by
    // source URL or a Downloads/Dhamma destination). A failed query
    // keeps everything, like the Room/Talks rule, so a transient read
    // failure never drops a live queue. Returns the dropped URLs
    // (empty when nothing dropped or on any failure); writes back
    // only when something actually dropped. v12.14: the three ports
    // that made the mirror exact — null-cursor fail-closed (Talks
    // v11.76), %XX decode (Room v11.81 / Talks v11.86), map-less drop
    // (Room v11.85 / Talks v11.86).
    private java.util.Set<String> pruneLibQueuedDownloads1019() {
        java.util.Set<String> dropped1019 = new java.util.HashSet<String>();
        try {
            android.content.SharedPreferences sp1019 =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONArray arr1019 = new org.json.JSONArray(
                    sp1019.getString("dlQueued", "[]"));
            if (arr1019.length() == 0) return dropped1019;
            java.util.Set<String> queued1019 = new java.util.HashSet<String>();
            for (int i1019 = 0; i1019 < arr1019.length(); i1019++)
                queued1019.add(arr1019.optString(i1019));
            org.json.JSONObject map1019 = new org.json.JSONObject(
                    sp1019.getString("dlMap", "{}"));
            java.util.Set<String> activeSources1019 = new java.util.HashSet<String>();
            java.util.Set<String> activeNames1019 = null;
            try {
                android.app.DownloadManager dm1019 = (android.app.DownloadManager)
                        getSystemService(android.content.Context.DOWNLOAD_SERVICE);
                android.database.Cursor c1019 = dm1019.query(
                        new android.app.DownloadManager.Query());
                // v12.14: the set is created only after a non-null cursor is
                // confirmed (Talks v11.76 / Main v11.78 port) — a null cursor
                // used to leave an empty non-null set, so the drop loop below
                // wiped every live queued entry as "not active", although the
                // comment above promises a failed query keeps everything.
                if (c1019 != null) {
                    activeNames1019 = new java.util.HashSet<String>();
                    try {
                        int uriCol1019 = c1019.getColumnIndex(
                                android.app.DownloadManager.COLUMN_LOCAL_URI);
                        int srcCol1019 = c1019.getColumnIndex(
                                android.app.DownloadManager.COLUMN_URI);
                        int statusCol1019 = c1019.getColumnIndex(
                                android.app.DownloadManager.COLUMN_STATUS);
                        while (c1019.moveToNext()) {
                            int st1019 = statusCol1019 >= 0 ? c1019.getInt(statusCol1019) : -1;
                            if (st1019 >= 0
                                    && st1019 != android.app.DownloadManager.STATUS_RUNNING
                                    && st1019 != android.app.DownloadManager.STATUS_PAUSED
                                    && st1019 != android.app.DownloadManager.STATUS_PENDING) continue;
                            String src1019 = srcCol1019 >= 0 ? c1019.getString(srcCol1019) : null;
                            String uri1019 = uriCol1019 >= 0 ? c1019.getString(uriCol1019) : null;
                            if (src1019 != null && !queued1019.contains(src1019)
                                    && (uri1019 == null || !uri1019.contains("Dhamma/"))) continue;
                            if (src1019 == null && uri1019 != null && !uri1019.contains("Dhamma/")) continue;
                            if (src1019 != null) activeSources1019.add(src1019);
                            if (uri1019 != null) {
                                // v12.14: decode before the basename (Room v11.81 /
                                // Talks v11.86 port) — COLUMN_LOCAL_URI is a file://
                                // URI, so a filename carrying a %XX escape arrives
                                // here as %25XX and never matched the dlMap name:
                                // the prune dropped the live job (M2) and a re-tap
                                // could double-enqueue.
                                String dec1019 = android.net.Uri.decode(uri1019);
                                if (dec1019 == null) dec1019 = uri1019;
                                int slash1019 = dec1019.lastIndexOf('/');
                                activeNames1019.add(slash1019 >= 0 ? dec1019.substring(slash1019 + 1) : dec1019);
                            }
                        }
                    } finally { c1019.close(); }
                }
            } catch (Exception ignored) { activeNames1019 = null; }
            java.io.File dir1019 = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma");
            java.util.Iterator<String> it1019 = queued1019.iterator();
            while (it1019.hasNext()) {
                String url1019 = it1019.next();
                String mapped1019 = map1019.has(url1019)
                        ? map1019.optString(url1019, "") : null;
                if (mapped1019 != null && mapped1019.length() == 0) mapped1019 = null;
                boolean landed1019 = mapped1019 != null
                        && new java.io.File(dir1019, mapped1019).isFile();
                if (landed1019) { it1019.remove(); dropped1019.add(url1019); continue; }
                if (activeSources1019.contains(url1019)) continue;
                if (activeNames1019 != null) {
                    if (mapped1019 != null) {
                        if (!activeNames1019.contains(mapped1019)) {
                            it1019.remove(); dropped1019.add(url1019);
                        }
                    } else {
                        // v12.14: map-less branch (Room v11.85 / Talks v11.86
                        // port) — a queued URL with no dlMap entry could never
                        // be pruned above, so its entry leaked forever. Both
                        // enqueue sites record the map first, so a map-less
                        // entry is a legacy remnant (or a torn write) whose job
                        // is dead; drop it when none of its filename candidates
                        // names a live job. A live job's candidates always match,
                        // and a failed query keeps everything (activeNames null
                        // skips this whole block).
                        boolean anyActive14 = false;
                        for (String cand14 : fileNameCandidates14(url1019)) {
                            if (cand14 != null && activeNames1019.contains(cand14)) { anyActive14 = true; break; }
                        }
                        if (!anyActive14) { it1019.remove(); dropped1019.add(url1019); }
                    }
                }
            }
            if (!dropped1019.isEmpty()) {
                org.json.JSONArray out1019 = new org.json.JSONArray();
                for (String u1019 : queued1019) out1019.put(u1019);
                sp1019.edit().putString("dlQueued", out1019.toString()).apply();
            }
        } catch (Exception ignored) { return new java.util.HashSet<String>(); }
        return dropped1019;
    }

    // v12.14: filename candidates for a queued URL (Room fileNameCandidates,
    // v11.85) — the raw basename, the query/fragment-stripped basename, and
    // the percent-decoded basename — used by the map-less prune branch above.
    // v12.16: the enqueue destination form appended last (stripped +
    // ".mp3" when it lacks .mp3) — a wrapper-URL live job lands under
    // that name; see Room fileNameCandidates.
    private static String[] fileNameCandidates14(String url) {
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

    private void refreshLibQueueCache() {
        java.util.ArrayList<String> q = new java.util.ArrayList<String>();
        String saved = Prefs.savedQueueLib(DownloadedActivity.this);
        java.util.Set<String> live = new java.util.HashSet<String>();
        for (Entry e : entries) live.add(e.file.getAbsolutePath());
        if (saved != null && saved.length() > 0) {
            for (String u : saved.split("\n")) {
                if (u.length() == 0 || q.contains(u)) continue;
                if (live.contains(u)) q.add(u);
            }
        }
        boolean changed = q.size() != libQueueCache.size();
        if (!changed) {
            for (int i = 0; i < q.size(); i++)
                if (!q.get(i).equals(libQueueCache.get(i))) { changed = true; break; }
        }
        // v5.75: the write-back above used to compare the pruned
        // queue only against the MEMORY cache, never against the
        // disk key — so a queue that pruned to EMPTY on a fresh
        // start (every queued file deleted outside the app, or
        // the whole shelf gone) compared equal (empty vs empty)
        // and never wrote, leaving the ghosts on disk forever.
        // The library's own strip filters by isFile and read
        // true, but the raw-key readers did not: the plain
        // notification card's v5.43 ◈ waiting count counts
        // every non-empty line of queue_library without a file
        // check, and every strip's v4.77 ◈ button shows on a
        // non-empty key — the same ghost class the Room queue
        // (v5.73) and the Talks chooser (v5.74) already closed.
        // The write now also fires when the pruned join differs
        // from the disk string (all-stale, dedup, stray empty
        // lines all self-heal on the first library load, menu
        // or delete). Steady state writes nothing: cache equals
        // q and disk equals the pruned join, so no call site
        // gains a per-tick write — and none of them is the 1s
        // tick anyway (load tail, queue-menu, single delete,
        // queue-view open).
        StringBuilder prunedSb = new StringBuilder();
        for (String u : q) {
            if (prunedSb.length() > 0) prunedSb.append("\n");
            prunedSb.append(u);
        }
        String disk = saved == null ? "" : saved;
        boolean diskDiffers = !prunedSb.toString().equals(disk);
        libQueueCache = q;
        lastSyncedLibQueue08 = new java.util.ArrayList<String>(q); // v12.08 snapshot
        if (changed || diskDiffers) Prefs.saveQueueLib(DownloadedActivity.this, q);
    }
    // v12.08: LCS moved set, the Room v12.05 / Talks v12.05 helper on
    // bare paths -- entries whose relative order changed between the
    // last-synced snapshot and the caller's list count as moved by this
    // call (plus entries new to this call, absent from the snapshot).
    // A single verb moves at most one entry, so the LCS is unique
    // except for a pure adjacent swap, where either member may count
    // as moved -- that tie is harmless (sim1208 L1/L5): when the saved
    // order is unchanged both branches reproduce the caller order
    // exactly, and when it changed it is a genuine two-window
    // conflict. Runs on the main thread per save, O(n*m) on
    // queue-sized lists.
    private java.util.Set<String> movedLibUrls08(java.util.ArrayList<String> snapshotUrls08,
                                                 java.util.ArrayList<String> callerUrls08) {
        java.util.Set<String> snapSet08 = new java.util.HashSet<String>(snapshotUrls08);
        java.util.Set<String> callerSet08 = new java.util.HashSet<String>(callerUrls08);
        java.util.ArrayList<String> a08 = new java.util.ArrayList<String>();
        java.util.HashSet<String> seenA08 = new java.util.HashSet<String>();
        for (String u08 : snapshotUrls08)
            if (callerSet08.contains(u08) && seenA08.add(u08)) a08.add(u08);
        java.util.ArrayList<String> b08 = new java.util.ArrayList<String>();
        java.util.HashSet<String> seenB08 = new java.util.HashSet<String>();
        for (String u08 : callerUrls08)
            if (snapSet08.contains(u08) && seenB08.add(u08)) b08.add(u08);
        int n08 = a08.size(), m08 = b08.size();
        int[][] dp08 = new int[n08 + 1][m08 + 1];
        for (int i08 = n08 - 1; i08 >= 0; i08--)
            for (int j08 = m08 - 1; j08 >= 0; j08--)
                dp08[i08][j08] = a08.get(i08).equals(b08.get(j08))
                        ? dp08[i08 + 1][j08 + 1] + 1
                        : Math.max(dp08[i08 + 1][j08], dp08[i08][j08 + 1]);
        java.util.Set<String> lcs08 = new java.util.HashSet<String>();
        int i08 = 0, j08 = 0;
        while (i08 < n08 && j08 < m08) {
            if (a08.get(i08).equals(b08.get(j08))) { lcs08.add(a08.get(i08)); i08++; j08++; }
            else if (dp08[i08 + 1][j08] >= dp08[i08][j08 + 1]) i08++;
            else j08++;
        }
        java.util.Set<String> moved08 = new java.util.HashSet<String>();
        for (String u08 : callerUrls08) if (!snapSet08.contains(u08)) moved08.add(u08);
        for (String u08 : b08) if (!lcs08.contains(u08)) moved08.add(u08);
        return moved08;
    }

    // v12.08: library funnel parity with Room saveQueue(keep02, adopt03)
    // (v12.02/03/05) and Talks saveTalkQueueKeepingForeign (v12.04/05).
    // keep08 is the set of paths this call just added, not in the saved
    // read yet (the dialog tail computes it as the post-verb minus
    // pre-verb diff); every other caller passes null (take / trim /
    // move add nothing new, so nothing of theirs is kept). adopt08 is
    // always true here -- there is no handover/replace-the-key path on
    // the library key; the parameter stays for symmetry with the Room
    // funnel and to make a future replace caller choose explicitly.
    // The signature itself changed (no no-arg overload) so a future
    // edit that adds a queue entry must choose a keep explicitly
    // instead of silently losing the addition to the drop.
    private void saveLibQueue(java.util.ArrayList<String> q,
                              java.util.Set<String> keep08, boolean adopt08) {
        // v12.08: capture the caller's pre-mutation order + moved set
        // BEFORE the drop/adopt below mutates q -- the LCS compares the
        // last synced snapshot against what THIS call is about to save.
        java.util.Set<String> moved08 = null;
        java.util.Set<String> callerSet08 = new java.util.HashSet<String>(q);
        if (adopt08) moved08 = movedLibUrls08(lastSyncedLibQueue08,
                new java.util.ArrayList<String>(q));
        // v12.08: (1) re-read prefs and drop entries the other window
        // already popped -- the v12.00 audit's slice-B NOTE-2, now in
        // the library funnel: two DownloadedActivity instances can be
        // alive at once (standard launchMode, no singleTop) and in
        // split-screen both are resumed, so the service's advance (or
        // the other window's remove/clear) leaves this window's
        // libQueueCache stale, and any verb here used to write the
        // stale list straight back, resurrecting the popped head (the
        // v11.98 Talks-funnel / v12.00 library-dialog class). The drop
        // mirrors the v11.98 rule: an entry absent from the fresh saved
        // read is gone -- popped, cleared or pruned elsewhere -- unless
        // this call just added it (keep08). Runs even when the saved
        // read is empty (a cleared queue must not resurrect wholesale);
        // library entries are bare file paths (no v2 lines), so the
        // fresh set is a plain newline split, exactly like the v12.00
        // dialog prune and the v12.01 D1 take prune. A read failure
        // keeps today's behaviour.
        // (2) ADOPT entries added elsewhere -- the v12.02 note's
        // rotation corner, now in the library: a window whose cache
        // went stale (split-screen with no sync since the other
        // window's add -- the 1s tick never refreshes libQueueCache)
        // used to persist its stale list and wipe the addition. The
        // adoption below merges saved entries absent from memory back
        // in, EXCEPT the removed set -- paths in the
        // lastSyncedLibQueue08 snapshot but no longer in memory, i.e.
        // entries THIS window removed since the last save, whose
        // removal has not persisted yet so the fresh saved read still
        // holds them (adopting them would undo the caller's own
        // removal). A removal older than the last save already landed
        // in the store, so it cannot be adopted back, and a re-add
        // elsewhere AFTER the removal persisted is a deliberate new add
        // that IS adopted (last writer wins per entry). A clear-all
        // wipes only what this window knew: a concurrent add elsewhere
        // survives, the same rule as any other entry this window had
        // not seen (sim1208 L2). EXCEPT ghosts: a saved path whose
        // file is gone is never adopted back -- the library has no
        // talks list to filter through, so the isFile check is the
        // filter, the same ghost rule refreshLibQueueCache already
        // applies at load (sim1208 L11). An adopted entry slots into
        // its saved-order position (before the first in-memory entry
        // that follows it in the saved order), never at the tail -- a
        // play-next insert elsewhere keeps its "next" meaning.
        // (3) ORDER ADOPTION -- the v12.05 rule, now in the library:
        // a reorder made in the other window used to be reverted by
        // this window's stale order written back. Entries this call did
        // NOT move (not in moved08, and present in the caller's list at
        // entry -- adopted entries are pinned at the position the adopt
        // step just gave them, which already is their saved position)
        // are re-sorted to the fresh saved order; entries this call
        // moved, just added (keeps), or with no saved rank stay pinned
        // at their current slots. A no-edit save (e.g. a no-touch
        // dialog verb's shared tail) now adopts the other window's
        // reorder instead of reverting it (sim1208 L5). Steady states
        // are unchanged -- when the saved order equals this window's,
        // the sort is a no-op (sim1208 L10).
        try {
            String saved08 = Prefs.savedQueueLib(DownloadedActivity.this);
            java.util.Set<String> savedPaths08 = new java.util.HashSet<String>();
            java.util.ArrayList<String> savedList08 = new java.util.ArrayList<String>();
            if (saved08 != null && saved08.length() > 0) {
                for (String p08 : saved08.split("\n")) {
                    if (p08.length() > 0) { savedPaths08.add(p08); savedList08.add(p08); }
                }
            }
            java.util.Set<String> removed08 = new java.util.HashSet<String>();
            for (String p08 : lastSyncedLibQueue08) {
                if (p08.length() > 0 && !callerSet08.contains(p08)) removed08.add(p08);
            }
            for (int i08 = q.size() - 1; i08 >= 0; i08--) {
                String p08 = q.get(i08);
                if (p08.length() == 0) continue;
                if (keep08 != null && keep08.contains(p08)) continue;
                if (!savedPaths08.contains(p08)) q.remove(i08);
            }
            if (adopt08) {
                java.util.Map<String, Integer> order08 = new java.util.HashMap<String, Integer>();
                for (int i08 = 0; i08 < savedList08.size(); i08++) {
                    String p08 = savedList08.get(i08);
                    if (p08.length() > 0 && !order08.containsKey(p08)) order08.put(p08, Integer.valueOf(i08));
                }
                for (String p08 : savedList08) {
                    if (p08.length() == 0) continue;
                    if (q.contains(p08)) continue;
                    if (removed08.contains(p08)) continue;
                    if (!new java.io.File(p08).isFile()) continue;
                    int at08 = q.size();
                    Integer pos08 = order08.get(p08);
                    if (pos08 != null) {
                        for (int j08 = 0; j08 < q.size(); j08++) {
                            Integer pj08 = order08.get(q.get(j08));
                            if (pj08 != null && pj08.intValue() > pos08.intValue()) { at08 = j08; break; }
                        }
                    }
                    q.add(at08, p08);
                }
                if (moved08 != null) {
                    java.util.Map<String, Integer> rank08 = new java.util.HashMap<String, Integer>();
                    for (int i08 = 0; i08 < savedList08.size(); i08++) {
                        String p08 = savedList08.get(i08);
                        if (p08.length() > 0 && !rank08.containsKey(p08)) rank08.put(p08, Integer.valueOf(i08));
                    }
                    java.util.ArrayList<String> merged08 = new java.util.ArrayList<String>();
                    for (int i08 = 0; i08 < q.size(); i08++) merged08.add(null);
                    java.util.ArrayList<String> rest08 = new java.util.ArrayList<String>();
                    for (int i08 = 0; i08 < q.size(); i08++) {
                        String p08 = q.get(i08);
                        if (p08.length() == 0 || !callerSet08.contains(p08) || moved08.contains(p08) || !rank08.containsKey(p08)) merged08.set(i08, p08);
                        else rest08.add(p08);
                    }
                    java.util.Collections.sort(rest08, new java.util.Comparator<String>() {
                        @Override public int compare(String a, String b) {
                            Integer ra = rank08.get(a);
                            Integer rb = rank08.get(b);
                            int ia = ra == null ? Integer.MAX_VALUE : ra.intValue();
                            int ib = rb == null ? Integer.MAX_VALUE : rb.intValue();
                            return ia < ib ? -1 : (ia > ib ? 1 : 0);
                        }
                    });
                    int r08 = 0;
                    for (int i08 = 0; i08 < merged08.size(); i08++)
                        if (merged08.get(i08) == null) merged08.set(i08, rest08.get(r08++));
                    q.clear();
                    q.addAll(merged08);
                }
            }
        } catch (Exception ignored) {}
        Prefs.saveQueueLib(DownloadedActivity.this, q);
        libQueueCache = new java.util.ArrayList<String>(q);
        lastSyncedLibQueue08 = new java.util.ArrayList<String>(q); // v12.08 snapshot
        if (adapter != null) adapter.notifyDataSetChanged();
        // v10.05: same paused-card ◈ sync as Room.saveQueue — the card reads this shared
        // library key at rebuild time, so every library queue edit (row menu, queue-all,
        // clear, reorder, pick-up/drop) rebuilds the card at once; no-op with no track.
        if (service != null) service.refreshNotification();
        else if (AudioService.liveSession) {
            // v10.28: unbound leg of the v10.05 library queue-count sync — same two
            // windows as the Room leg above (before onServiceConnected fires,
            // after service death), so a library queue add/remove in a fast-opened
            // library left a PAUSED card showing the old ◈ count until the next
            // tap. Same non-global rule, mirrored: the card reads the shared
            // library key only for a library session, so the no-bind
            // ACTION_REFRESH_NOTIFICATION intent (v10.03 shape, liveSession-guarded,
            // never starts a dead service) fires only when the sounding session
            // IS a library session — the isLibSession prefs shape read fresh
            // (lastKind lib and a non-empty lastLibPath). A library queue edit
            // while a Room session sounds changes no card number and must not
            // re-post the card. The handler stopSelfs when trackless and a
            // rebuild with no count change paints the identical card. Bound path
            // byte-identical.
            try {
                android.content.SharedPreferences rsp =
                        getSharedPreferences("dhamma", MODE_PRIVATE);
                if ("lib".equals(rsp.getString("lastKind", ""))
                        && rsp.getString("lastLibPath", "").length() > 0) {
                    android.content.Intent rsv = new android.content.Intent(DownloadedActivity.this, AudioService.class);
                    rsv.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                    startService(rsv);
                }
            } catch (Exception ignored) {}
        }
    }

    // v10.23: paused-card star sync, Downloaded bulk star/un-star menu leg of v10.02/v10.22 —
    // the four shown-set fav verbs (v6.42/v6.43/v7.05) write favTalks for every shown file,
    // including the sounding one (no sounding-file skip, like v7.04), but never rebuilt the
    // card, so a PAUSED sounding talk starred/unstarred in bulk kept its old star on the card
    // (title mark for a library session, v5.98/v8.49; title mark + fav action for a Room
    // session) until the next tap — rebuilds run every 15s only while playing, never paused.
    // One shared guard, v10.02 shape: the caller collects exactly the URLs/paths it really
    // flipped (a dual-claimed or already-starred file flips nothing and is never collected),
    // and this rebuilds at once only when a flipped talk IS the sounding one — bound path
    // compares the service track in both its forms (file path for a library/offline Room
    // session, stream URL for a Room session); unbound path sends the no-bind
    // ACTION_REFRESH_NOTIFICATION intent (v10.09/v10.18 shape: liveSession guard, never starts
    // a dead service) gated on prefs fresh at tap time (flipped == lastUrl, or dlMap[flipped]
    // file == lastLibPath file). refreshNotification is a no-op with no track, and a rebuild
    // with no star change paints the identical card. No new UI, no toast.
    private void refreshCardIfFlipped1023(java.util.Set<String> flippedUrls, java.util.Set<String> flippedPaths) {
        if ((flippedUrls == null || flippedUrls.isEmpty()) && (flippedPaths == null || flippedPaths.isEmpty())) return;
        try {
            if (service != null) {
                String svc = service.getCurrentUrl();
                if (svc != null && (flippedPaths.contains(svc) || flippedUrls.contains(svc))) service.refreshNotification();
                return;
            }
            if (!AudioService.liveSession) return;
            android.content.SharedPreferences rsp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String lastUrl = rsp.getString("lastUrl", "");
            String libPath = rsp.getString("lastLibPath", "");
            org.json.JSONObject map =
                    new org.json.JSONObject(rsp.getString("dlMap", "{}"));
            boolean cardTalk = false;
            for (String u : flippedUrls) {
                if (u.equals(lastUrl)) { cardTalk = true; break; }
                if (libPath.length() > 0) {
                    String fn = map.optString(u, "");
                    if (fn.length() > 0 && fn.equals(new java.io.File(libPath).getName())) { cardTalk = true; break; }
                }
            }
            if (cardTalk) {
                android.content.Intent rsv = new android.content.Intent(DownloadedActivity.this, AudioService.class);
                rsv.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                startService(rsv);
            }
        } catch (Exception ignored) {}
    }
    // v5.78: pick-up/drop queue move, ported from the Talks
    // chooser's v5.77 verb (itself from the Room drawer's
    // v4.92) — the last of the three queue surfaces to gain
    // it, completing the set. The file path the listener
    // picked up with the ⋮ menu's "နေရာရွှေ့မယ်", waiting
    // for the drop tap on another queued row. v5.79: the arm
    // now survives rotation via onSaveInstanceState; every
    // use still re-validates against libQueueCache, and the row-
    // tap interception below runs before trimLibQueueAhead
    // and play, so a drop never also plays the tapped file
    // or trims the queue. Unlike the Room, there is no
    // drawer close to cancel on — the arm survives a strip
    // or preview tap and is validated on every use instead,
    // so a stale arm can never drop a ghost; a preview tap
    // while armed still plays (deliberately untouched — it
    // is a play surface, not a row), and any trim it does
    // only makes the next drop tap clear the stale arm.
    private String moveHeldPath = null;
    // v4.58: queue-only view on the downloaded library — the
    // mirror of the Room drawer's v4.55 view and the Talks
    // chooser's v4.56 view, completing the set: the library
    // gained queue building (v4.57) but a hand-built queue still
    // read only as ◈ marks scattered through the shelf, never as
    // the queue itself in play order. libQueueOnly lists just the
    // queued files, in queue order, with the ◈ mark agreeing with
    // the row position like the other two views. libEntryAtDisplay
    // maps a display position back to a shelf Entry for every
    // consumer (adapter, tap, long-press, ⋮ menu), so all verbs act
    // on the right file; a stale slot (queue path gone from the
    // shelf) maps to null, which every consumer guards. heal
    // drops the view the moment the queue empties, mirroring the
    // chooser's getCount self-heal (saveLibQueue already notifies,
    // so the heal runs on the next layout).
    private boolean libQueueOnly = false;
    private Entry libEntryAtDisplay(int displayPos) {
        if (!libQueueOnly) {
            if (displayPos < 0 || displayPos >= shownDl.size()) return null;
            return shownDl.get(displayPos);
        }
        if (displayPos < 0 || displayPos >= libQueueCache.size()) return null;
        String p = libQueueCache.get(displayPos);
        for (int i = 0; i < entries.size(); i++)
            if (entries.get(i).file.getAbsolutePath().equals(p)) return entries.get(i);
        return null;
    }
    private int libDisplayCount() {
        return libQueueOnly ? libQueueCache.size() : shownDl.size();
    }
    private void healLibQueueOnly() {
        if (libQueueOnly && libQueueCache.isEmpty()) libQueueOnly = false;
    }
    // v10.85: the header play buttons (Random, Play All, shuffled
    // Play All) resolved their start file from shownDl even in
    // queue-only view, where the screen shows libQueueCache order
    // through libEntryAtDisplay — so Play All in the queue view
    // played the first shelf file (possibly not queued at all)
    // instead of the queue head, and Random picked from the shelf
    // instead of the queue. These resolve through the display
    // mapping instead: shelf order normally, queue order (first
    // non-stale slot) in queue-only view. Null = nothing to play.
    private Entry firstDisplayEntry() {
        if (!libQueueOnly) return shownDl.isEmpty() ? null : shownDl.get(0);
        for (int i = 0; i < libQueueCache.size(); i++) {
            Entry e = libEntryAtDisplay(i);
            if (e != null) return e;
        }
        return null;
    }
    // v11.51: Play All / Random / shuffled Play All used to
    // re-prepare the file that was already sounding -- play()
    // resets the player unconditionally, so the talk cut out
    // with a re-buffer gap while tapping the same row only
    // toggled it. A pick that IS the current track stays on it:
    // sounding (playing, or buffering and about to sound) = keep
    // playing, no re-prepare; paused / held / failed = resume(),
    // whose own branches cover the retry and the v11.46 gate.
    // Called before any queue trim / trail clear (a pick that
    // changes no track must touch neither, like the row-tap
    // toggle branch). Returns true when handled.
    private boolean replayCurrentLibFile(Entry e) {
        if (service == null) return false;
        String path = e.file.getAbsolutePath();
        if (!path.equals(service.getCurrentUrl())) return false;
        saveLastLibrary(path, e.title);
        if (!service.isPlaying() && !service.isBuffering()) service.resume();
        if (adapter != null) adapter.notifyDataSetChanged();
        glideToDlPath(path);
        return true;
    }
    private Entry randomDisplayEntry() {
        if (!libQueueOnly)
            return shownDl.isEmpty() ? null : shownDl.get((int) (Math.random() * shownDl.size()));
        java.util.ArrayList<Entry> q = new java.util.ArrayList<Entry>();
        for (int i = 0; i < libQueueCache.size(); i++) {
            Entry e = libEntryAtDisplay(i);
            if (e != null) q.add(e);
        }
        return q.isEmpty() ? null : q.get((int) (Math.random() * q.size()));
    }
    // v4.77: open the library queue-only view for a strip queue
    // tap (ACTION_QUEUE, fresh library or onNewIntent). The queue
    // is the hand queue pruned against this shelf
    // (refreshLibQueueCache, same load point as loadList's own
    // prune), so opening needs the shelf: a caller arriving
    // before the first load keeps pendingDlQueue for the
    // loadList tail instead of calling here, and the tail flush
    // calls here with the shelf ready. An empty queue toasts
    // instead of opening an empty list (queue-only getCount
    // self-heals to the full shelf, which would read as the
    // wrong screen); the row menu still opens the full shelf
    // for queueing. Setting libQueueOnly before notify + jump
    // to the head keeps the queue view from the first frame,
    // mirroring the menu's own enter path (same toast, same
    // head jump, same footer rebind).
    private void openLibQueueView() {
        if (!dlListLoaded) { pendingDlQueue = true; return; }
        pendingDlQueue = false;
        refreshLibQueueCache();
        if (libQueueCache.isEmpty()) {
            Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "queue_empty"), Toast.LENGTH_SHORT).show();
            return;
        }
        // v5.88: an all-playing queue toasts like an empty one —
        // the Room drawer's v5.87 gate, mirrored here. The
        // strips (v5.80), this screen's footer (v5.81) and the
        // card (v5.43) already count waiting files only, but
        // this entry gate counted the raw pruned queue, so a
        // service-restart play still saved in the queue (it
        // never passed the row-tap trim) opened a queue-only
        // view whose only row was the file already playing —
        // the ◈ button promised a queue and delivered the
        // current file. Playing identity is the v5.81 footer
        // rule itself (lastKind "lib" + lastLibPath from prefs,
        // which works before the service binds), so the gate
        // agrees with the footer count on this same screen by
        // construction; a Room session (lastKind "room") or a
        // normal play (already trimmed) changes nothing, and
        // a queue with any waiting file still opens exactly
        // as today. Rows, marks and the queue itself are
        // untouched; only the entry gate is waiting-only now.
        // (This also makes the v5.80 strip comment true: it
        // claimed an all-playing queue "toasts empty on open",
        // which the raw gate never did until now.)
        String playLib88 = "";
        try {
            android.content.SharedPreferences lsp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if ("lib".equals(lsp.getString("lastKind", "")))
                playLib88 = lsp.getString("lastLibPath", "");
        } catch (Exception ignored) {}
        boolean anyWaiting88 = false;
        for (String p : libQueueCache)
            if (!p.equals(playLib88)) { anyWaiting88 = true; break; }
        if (!anyWaiting88) {
            Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "queue_empty"), Toast.LENGTH_SHORT).show();
            return;
        }
        libQueueOnly = true;
        lastDlAnimPos = -1;
        if (dlListView != null) dlListView.setSelection(0);
        if (adapter != null) adapter.notifyDataSetChanged();
        updateTotal();
        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "queue_only_view"), Toast.LENGTH_SHORT).show();
    }
    // v4.57: consume the queue head for Next / auto-advance —
    // returns the queued Entry to play, or null when the queue
    // is empty or holds only deleted files (stale heads drop
    // silently, like the v4.37 restore filter). The consumed
    // head is already removed + persisted on return, so Next
    // and auto-advance share exactly one consumption path.
    // v7.80: the sounding file is skipped, never popped — the
    // Room's v5.85 rule and the v7.79 background library
    // advance, restated on the foreground consumer, which never
    // got it. A service-restart play can leave the sounding
    // file saved in the queue (the v5.82 row-mark comment says
    // so outright), and the old loop popped head-first: with a
    // queue of [sounding, next], a manual Next replayed the
    // sounding file instead of moving on, and a natural end
    // replayed the just-ended file announced as "နောက်: "
    // instead of advancing. Skipping keeps the sounding entry
    // queued (never popped, like the v7.79 background path),
    // so a sounding-only queue falls through to the plain
    // list/shuffle advance at both call sites, and a later
    // waiting head is popped in order. A null/unbound service
    // has no sounding identity, so the old behaviour holds
    // exactly there; stale (shelf-gone) heads still drop.
    private Entry takeLibQueueHead() {
        String sounding80 = (service != null) ? service.getCurrentUrl() : null;
        // v12.01 D1: drop cache entries the advance already popped — the v12.00
        // audit's slice-C C1, confirmed: the library sits back-stacked with a
        // frozen libQueueCache ([A,B,C], A sounding); a natural end or headset
        // Next pops A from a fresh prefs read and plays B, but a notification /
        // strip Next sends ACTION_NEXT directly to this activity, bypassing the
        // topLib routing guard, and onNewIntent calls handleLibNext BEFORE
        // onResume's loadList refresh — the take then skipped only the sounding
        // B, matched stale head A, saved [B,C] and played A again. Prune here,
        // at take time, before the scan: an entry absent from the fresh saved
        // read is gone (popped, cleared or pruned elsewhere). No keep-exemption
        // is needed — the take adds nothing. Runs even when the fresh read is
        // empty. Library entries are bare file paths (no v2 lines), so the
        // fresh set is a plain newline split, exactly like the v12.00 dialog
        // prune. The take's own saveLibQueue tail below persists the pruned
        // cache. Covers both callers (handleLibNext and the 1s tick loop);
        // main-thread callbacks cannot interleave between a cache add and its
        // save, so a just-added entry can never be dropped here.
        try {
            String savedLib01 = Prefs.savedQueueLib(DownloadedActivity.this);
            java.util.Set<String> savedLibPaths01 = new java.util.HashSet<String>();
            if (savedLib01 != null && savedLib01.length() > 0) {
                for (String p01 : savedLib01.split("\n")) {
                    if (p01.length() > 0) savedLibPaths01.add(p01);
                }
            }
            for (int i01 = libQueueCache.size() - 1; i01 >= 0; i01--) {
                if (!savedLibPaths01.contains(libQueueCache.get(i01))) libQueueCache.remove(i01);
            }
        } catch (Exception ignored) {}
        Entry found80 = null;
        for (int i = 0; i < libQueueCache.size(); ) {
            String head = libQueueCache.get(i);
            if (sounding80 != null && sounding80.length() > 0 && head.equals(sounding80)) { i++; continue; }
            Entry match80 = null;
            for (Entry e : entries)
                if (e.file.getAbsolutePath().equals(head)) { match80 = e; break; }
            if (match80 != null) { found80 = match80; libQueueCache.remove(i); break; }
            libQueueCache.remove(i);
        }
        saveLibQueue(libQueueCache, null, true);
        return found80;
    }
    // v4.57: jumping to a queued file drops the queue ahead of
    // it — the v4.43 Room-drawer rule, mirrored here. Tapping a
    // queued file used to leave the earlier queued files in
    // place, so they came back on the next Next / auto-advance
    // even though the listener had already skipped past them.
    private void trimLibQueueAhead(String path) {
        int idx = libQueueCache.indexOf(path);
        if (idx < 0) return;
        for (int i = idx; i >= 0; i--) libQueueCache.remove(i);
        saveLibQueue(libQueueCache, null, true);
    }
    private android.widget.Button sortBtn;
    private android.widget.EditText dlSearchField;
    private AudioService service;
    private boolean bound;
    // v1.62: a library row tapped before the service finished binding
    // used to toast "service not ready" and drop the tap (the Room got
    // the same queue fix in v1.48, the library never did). Queue it
    // and play it the moment the service connects.
    private String pendingDlPath = null;
    private String pendingDlTitle = null;
    // v4.17: a row long-pressed before the bind restarts from 0
    // (see the row long-press below), not from the saved spot —
    // the flag rides the same queue + rotation round-trip as the
    // path itself, and delete-all clears it with the path.
    private boolean pendingDlRestart = false;
    // v2.03: notification / headset Next for a library session.
    public static final String ACTION_NEXT = "com.h2h.dhamma.LIB_NEXT";
    private boolean pendingDlNext = false;
    // v4.23: Previous parity — same route, same queueing, for
    // the library session (see handleLibPrev below).
    public static final String ACTION_PREV = "com.h2h.dhamma.LIB_PREV";
    private boolean pendingDlPrev = false;
    // v4.77: strip queue action for a library session — the mirror
    // of Room's ACTION_QUEUE (v4.76), which the strips could never
    // use for lib because they hid the queue button for lib
    // sessions. A strip queue tap now sends this to the library
    // instead of the Room; openLibQueueView below opens the
    // library's own queue-only view (v4.58).
    public static final String ACTION_QUEUE = "com.h2h.dhamma.LIB_QUEUE";
    private boolean pendingDlQueue = false;
    // v7.79: foreground flag for the service's background library
    // auto-advance (the mirror of RoomActivity.topRoomSayadaw,
    // v7.74) — while the library sits foreground its 1s poll owns
    // the end (advance toast, glide, row fade), so the service
    // must not pre-empt it; cleared on pause, so a backgrounded
    // library lets the service advance instead of stopping until
    // the listener returns to the shelf.
    public static volatile boolean topLib = false;
    // v1.94: jump target from Main's Mp3 Player nav (lib session) or
    // the notification card tap — the library used to open at the
    // top of the list even though the listener came to resume one
    // specific file. Applied once, on a fresh open only: rotation
    // keeps its own saved scroll position (v1.55) instead.
    private String jumpPath = null;
    // v4.16: shuffle-continuous mode for the offline library
    // (Spotify shuffle, session-only — it never persists, so a
    // fresh open always starts in shown order again). Flipped by
    // a Random long-press, armed by a Play All long-press; only
    // the auto-advance pick changes, never the shown order.
    private boolean dlShuffle = false;
    private boolean jumpDone = false;
    // v4.63: auto-play target from a dead-library continue strip
    // (Main / Talks / this screen's own strip) — the Room strip
    // resumes with autoUrl, but a library strip tap used to only
    // glide to the file and wait for a second tap on its row.
    // Applied once, against the loaded shelf, like jumpPath:
    // the bound path plays it from its saved spot (the row
    // tap's own savedPosForFile rule), the unbound path queues
    // it through the existing pendingDlPath bind flush, and a
    // file that is gone simply ends the attempt instead of
    // re-firing on every return reload.
    private String autoPlayPath = null;
    private boolean autoPlayDone = false;
    // v3.95: fresh-open glide to the playing file — armed only on a
    // fresh creation (see onCreate), consumed on the first attempt
    // once the service is bound, so rotations and back-stack
    // returns never re-glide (their scroll rules win instead).
    private boolean dlFreshGlidePending = false;
    // v3.96: library return-glide to a changed playing file — armed
    // only by onResume after a real pause (see there), anchored by
    // onPause on the track playing when the library was left, so a
    // plain return with the same file still playing never yanks a
    // browsed position (the v1.72 keep-scroll rule still owns it).
    private boolean dlReturnGlidePending = false;
    private String lastSeenDlPlayingPath = null;
    // v2.12: cascade only the very first load of a fresh open —
    // loadList() also runs on every onResume, and a rotation
    // recreates the screen, so the flag is armed only when there
    // is no saved state and consumed by the first loadList() call
    // (a permission-pending open keeps it until the granted load).
    private boolean dlFirstLoad = false;
    // v11.73: first-load-only flag for the filter-restore guard in loadList below —
    // dlFirstLoad cannot scope it (it is false on rotation restores, armed only with
    // no saved state), so this per-instance flag is consumed by the first loadList.
    private boolean dlFilterGuardDone73 = false;
    // v2.49: library filter-change entrance guard — starts at MAX so
    // the first load's own cascade (v2.12 staggerChildren) is never
    // doubled by the per-bind animation below; only a real search
    // keystroke that changes the shown set resets it to -1.
    private int lastDlAnimPos = Integer.MAX_VALUE;
    private String lastDlShownKey = null;
    private boolean dlSearchKeystroke = false;
    private boolean dlSearchJumpTop = false; // v4.21: set below, consumed after notify
    // v2.54: sort-change entrance flag — set only by the sort tap below.
    private boolean dlSortChange = false;
    // v2.55: delete-change entrance flag — set only by a confirmed
    // single-row delete below (delete-all never sets it: an emptied
    // list has no rows to pour; its empty view fades via dlEmptyFade
    // since v2.59, instead of staying instant as noted here before).
    // v2.94: exception — a PARTIAL delete-all (some files deleted,
    // some remaining) now arms it too, so the surviving rows pour.
    private boolean dlDeleteChange = false;
    // v2.57: return-change entrance flag — armed only by onResume
    // after a real pause (see onPause/onResume below), never by a
    // fresh open or a rotation (both are new instances, so the
    // pause marker is false there).
    private boolean dlReturnChange = false;
    private boolean dlWasPaused = false;
    // v8.55: share-chooser suppress, mirroring Room suppressBgPause /
    // Talks suppressSharePause — the library's four share choosers
    // (shown set, shown favourites, shown unplayed, single talk)
    // used to open with no suppress at all, so with background
    // playback off the library's onPause instant-pause stopped the
    // playing file the moment the chooser opened, and the 600ms
    // delayed check would have paused it anyway. Set before every
    // createChooser below, cleared on resume, read in onPause.
    private boolean suppressDlSharePause = false;
    // v11.92: same class as the share chooser above — the runtime
    // permission dialog is a transient system activity too
    // (GrantPermissionsActivity takes focus, so onPause runs while
    // the listener is only answering the prompt). With background
    // playback off, that pause used to stop the talk for no visible
    // reason. Set inside requestReadPermission() (its only callers
    // are onCreate and the empty-text retry), cleared on resume
    // like the share flag, so a real leave after the dialog
    // still pauses.
    private boolean suppressPermPause92 = false;
    // v4.00: live library refresh — the foreground twin of the v2.57
    // return pour. A download that finished while this screen sat
    // in the back stack appeared on the onResume reload, but one
    // that finished while the library sat OPEN never appeared
    // until the screen was left and re-entered: the 1s tick only
    // re-bound playing state, never re-scanned Downloads/Dhamma.
    // The tick now watches a directory signature (sorted .mp3
    // names + lengths); a signature that differs from the last
    // loadList and then holds steady for two consecutive ticks
    // reloads the list, so a still-growing partial download can
    // never trigger a reload storm, and the new rows pour in
    // with the library cascade's own fade + rise (dlLiveArrival
    // branch in applyDlFilter, same guard as search/sort/delete/
    // return). A removal (another app deleting a file) reloads
    // the same way once its signature holds steady. The search
    // text, sort and scroll position survive, because loadList
    // keeps them; the fresh-open glide and jump flags are already
    // consumed by then, so a live reload can never re-glide or
    // re-jump. Visual + list only; playback, download and
    // delete paths untouched.
    private String dlDirSig = null;
    // v4.27: in-progress download scan (DownloadManager) — this
    // app's jobs only, so other apps' downloads never count.
    // Cached fields; updateTotal reads them, never the manager.
    private int dlProgCount = 0;
    private String dlProgFirstTitle = "";
    private int dlProgFirstPct = -1;
    private String dlProgSig = "";
    private String dlDirPendingSig = null;
    private int dlDirPendingTicks = 0;
    private boolean dlLiveArrival = false;
    // v2.59: delete-to-empty empty-state fade flag — set only by a
    // confirmed delete (single row, or delete-all) that empties the
    // whole library; consumed by applyDlFilter's empty-view block.
    private boolean dlEmptyFade = false;
    // v2.81: library-footer fade-out in flight — set while the total
    // fades out on emptying (see updateTotal); cleared by its end
    // action or by the next non-empty update, which restores the
    // view instead of letting the fade hide a fresh total.
    private boolean totalFadingOut = false;
    // v2.83: permission-grant empty-view fade flag — set only by
    // the granted branch of onRequestPermissionsResult below, and
    // consumed by the loadList() it triggers (see there).
    private boolean dlPermGrantFade = false;
    // v2.25: playing-row pulse — at most one row is ever playing,
    // so a single animator + its view is enough; getView cancels
    // it whenever that view is rebound non-active or recycled.
    private android.animation.ObjectAnimator dlPulse = null;
    private android.view.View dlPulseView = null;
    // v3.18: pulse-start settle flag — mirrors the Room drawer's
    // v3.17 settle. An icon caught mid-stop-fade (alpha < 1) must
    // glide to 1 first, not jump to the pulse loop's first keyframe
    // (exactly 1f) in one frame. While settling, dlPulseView
    // already points at the icon, so a rebind of the still-playing
    // row (dlPulseView == state) leaves it alone and the settle's
    // end action starts the loop; a pause or a leave clears the
    // flag with the view.
    private boolean dlPulseSettling = false;
    // v2.05: has loadList() actually populated the list at least once?
    // A jump delivered before that (notification tap racing the first
    // load, or a tap while the list is still empty) must stay pending
    // for the next loadList instead of being consumed by applyJumpOnce
    // while entries are still empty — see below.
    private boolean dlListLoaded = false;

    private void applyJumpOnce() {
        if (jumpDone || jumpPath == null || dlListView == null) return;
        // v11.43: queue-only survives a no-scroll jump — the Talks
        // v11.42 defect class on the library surface. The old code
        // exited queue-only FIRST, so a jump to a deleted/absent
        // file, or a jump delivered before the list loaded (target
        // not yet known to be absent, just not loaded), silently
        // dropped the queue-only view even though nothing
        // scrolled. Exit only once a real row is found: the
        // exit is still required before setSelection, because a
        // shelf index is not a queue index — it just moves to
        // after the target lookup, beside the scroll itself.
        for (int i = 0; i < shownDl.size(); i++)
            if (shownDl.get(i).file.getAbsolutePath().equals(jumpPath)) {
                if (libQueueOnly) {
                    libQueueOnly = false;
                    if (adapter != null) adapter.notifyDataSetChanged();
                }
                jumpDone = true;
                dlListView.setSelection(i);
                return;
            }
        // v2.03: jump surviving a filter — the target exists in the
        // library but the typed search hides it from the shown list,
        // so the old code scrolled nowhere. Clearing the search
        // rebuilds the full list through the watcher (synchronous),
        // then the scroll lands.
        boolean inLib = false;
        for (Entry e : entries)
            if (e.file.getAbsolutePath().equals(jumpPath)) { inLib = true; break; }
        if (inLib && dlSearchField != null) {
            dlSearchField.setText("");
            for (int i = 0; i < shownDl.size(); i++)
                if (shownDl.get(i).file.getAbsolutePath().equals(jumpPath)) {
                    if (libQueueOnly) {
                        libQueueOnly = false;
                        if (adapter != null) adapter.notifyDataSetChanged();
                    }
                    jumpDone = true;
                    dlListView.setSelection(i);
                    return;
                }
        }
        // v2.05: only give up once the list has actually loaded. The
        // old code marked the jump done up front, so a jump delivered
        // while entries were still empty (target not yet known to be
        // absent — just not loaded) was consumed and never landed,
        // even though the next loadList would have found it. A jump
        // to a file that is provably absent after a load still ends
        // here, so a deleted file never re-jumps on every return.
        if (dlListLoaded) jumpDone = true;
    }

    // v4.63: play the dead-library continue target once, against
    // the loaded shelf. The bound path is the row tap's own play
    // (saveLastLibrary anchor + saved-spot resume + glide); the
    // unbound path queues through pendingDlPath, which the bind
    // callback already plays from the saved spot and glides to.
    // A target already playing just glides (a live session never
    // reaches here, but a double flush must not restart it), and
    // a target absent from the shelf ends the attempt — this tail
    // runs on every return reload, so an unconsumed target would
    // otherwise replay the file on every return.
    private void applyAutoPlayOnce() {
        if (autoPlayDone || autoPlayPath == null) return;
        Entry target = null;
        for (Entry e : entries)
            if (e.file.getAbsolutePath().equals(autoPlayPath)) { target = e; break; }
        if (target == null) {
            if (dlListLoaded) autoPlayDone = true;
            return;
        }
        autoPlayDone = true;
        if (service != null) {
            if (autoPlayPath.equals(service.getCurrentUrl())) {
                glideToDlPath(autoPlayPath);
                return;
            }
            saveLastLibrary(autoPlayPath, target.title);
            service.play(autoPlayPath, target.title, savedPosForFile(autoPlayPath));
            if (adapter != null) adapter.notifyDataSetChanged();
            glideToDlPath(autoPlayPath);
        } else {
            pendingDlPath = autoPlayPath;
            pendingDlTitle = target.title;
            pendingDlRestart = false;
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        // v2.05: keep the new intent as this activity's intent, like
        // the Room's onNewIntent has since v1.70 — without it, a Next
        // queued here (service not bound yet, or list not loaded)
        // vanished on rotation: the recreated activity re-read the
        // OLD intent, which carries no ACTION_NEXT, and the pending
        // flag lived only in memory. The bundle save/restore below
        // covers the same case; setIntent keeps the two in step.
        if (intent != null) setIntent(intent);
        // v1.94: SINGLE_TOP reuse (notification tap) delivers the new
        // jump target here, not through onCreate — apply it against
        // the already-loaded list instead of ignoring it.
        if (intent != null && intent.getStringExtra("jumpPath") != null) {
            jumpPath = intent.getStringExtra("jumpPath");
            jumpDone = false;
            applyJumpOnce();
        }
        // v4.63: same SINGLE_TOP delivery as jumpPath, for a
        // continue tap that lands on an already-open library.
        if (intent != null && intent.getStringExtra("autoPlayPath") != null) {
            autoPlayPath = intent.getStringExtra("autoPlayPath");
            autoPlayDone = false;
            applyAutoPlayOnce();
        }
        if (intent != null && ACTION_NEXT.equals(intent.getAction())) handleLibNext();
        if (intent != null && ACTION_PREV.equals(intent.getAction())) handleLibPrev(); // v4.23
        if (intent != null && ACTION_QUEUE.equals(intent.getAction())) openLibQueueView(); // v4.77
    }

    // v1.93: the library session anchor Main's Mp3 Player nav
    // returns to (v1.92 fixed only the notification tap — Main
    // still refused a library session, lastPage being empty).
    // Written on every file play below; Room's saveLastTalk
    // writes lastKind="room", so the newest session always wins.
    private void saveLastLibrary(String path, String title) {
        getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                .putString("lastKind", "lib")
                .putString("lastLibPath", path)
                .putString("lastLibTitle", title).apply();
    }

    // v2.03: notification / headset Next for a library session —
    // advance past the service's current file in the shown list
    // (falling back to the full list when a search hides it),
    // wrapping like auto-advance, honouring the saved spot like a
    // row tap. Not bound / list not loaded yet = remember it; the
    // bind callback and loadList both flush it, so no tap is lost.
    // v2.95: premium play-start glide — auto-advance (v2.63) and
    // notification Next (v2.63) already glide to the new file, but
    // play-all and random-play started playback with no scroll at
    // all, so a random pick off-screen left nothing visibly
    // following the music (its pulse row sat unseen). Glide to the
    // started file's row in the shown list; a filtered-out file
    // has no visible row, so nothing scrolls. Visual only.
    // v4.16: shuffle mark — the Random button's own text gains
    // or loses a 🔀 prefix with dlShuffle, so the mode reads on
    // the button itself (no new view). Called by both long-press
    // handlers below; a plain tap never touches the mark.
    private void applyShuffleMark() {
        android.view.View b = findViewById(R.id.btnDlRandom);
        if (!(b instanceof android.widget.TextView)) return;
        android.widget.TextView tb = (android.widget.TextView) b;
        String t = tb.getText().toString();
        if (dlShuffle) { if (!t.startsWith("🔀 ")) tb.setText("🔀 " + t); }
        else if (t.startsWith("🔀 ")) tb.setText(t.substring("🔀 ".length()));
    }

    private void glideToDlPath(String path) {
        if (dlListView == null || path == null) return;
        // v4.58: in queue-only view the scroll target is the queue
        // index, not the shelf index; a file outside the queue
        // (the playing file is never queued) has no row to glide
        // to, so nothing scrolls.
        if (libQueueOnly) {
            int qi = libQueueCache.indexOf(path);
            if (qi >= 0) dlListView.smoothScrollToPosition(qi);
            return;
        }
        for (int i = 0; i < shownDl.size(); i++)
            if (shownDl.get(i).file.getAbsolutePath().equals(path)) {
                dlListView.smoothScrollToPosition(i);
                return;
            }
    }

    // v3.95: fresh-open glide to the playing file — the mirror of
    // the Talks fresh-open auto-scroll (v3.91) on the library
    // surface. A fresh library open from Settings carries no
    // jumpPath (unlike Main's Player nav and the notification
    // tap, both v1.94), so it used to land at the top while the
    // pulsing playing row + its v3.93 live position sat screens
    // away in a long library. Called from loadList's tail and
    // from the bind callback, whichever lands last: the first
    // call runs before the bind and waits (service null, flag
    // kept), the bind call glides once the track is known.
    // Guards: a jump target always wins (its own landing already
    // ran in loadList, so the glide is cancelled, never stacked);
    // a stopped session has no track (hasTrack, same guard as
    // v3.93) and consumes the flag; a streamed Room talk is no
    // downloaded file, so no row exists and the flag is consumed;
    // an already-visible row never scrolls (visible range checked
    // inside the post, after layout, like v3.94's return scroll);
    // the glide is posted, so it runs after this load's layout
    // pass and after the v2.12 first-load cascade's own post.
    // Rotation and back-stack returns never reach this: the flag
    // is armed only on a fresh creation and consumed on the
    // first attempt, while the v1.72 keep-scroll rule still owns
    // every return. Visual only.
    private void tryDlFreshGlide() {
        if (!dlFreshGlidePending || dlListView == null || !dlListLoaded) return;
        // A typed search narrows the shown list, so a glide now
        // could land on a filtered row or consume the flag on a
        // hidden track — wait (flag kept) for the next loadList,
        // which the search watcher already triggers on clear.
        if (dlSearchField != null && dlSearchField.getText().length() > 0) return;
        if (jumpPath != null) { dlFreshGlidePending = false; return; }
        if (service == null) return;
        if (!service.hasTrack()) { dlFreshGlidePending = false; return; }
        final String cur = service.getCurrentUrl();
        if (cur == null || cur.length() == 0) { dlFreshGlidePending = false; return; }
        for (int i = 0; i < shownDl.size(); i++) {
            if (shownDl.get(i).file.getAbsolutePath().equals(cur)) {
                final int idx = i;
                dlFreshGlidePending = false;
                dlListView.post(new Runnable() {
                    @Override public void run() {
                        if (dlListView == null) return;
                        int first = dlListView.getFirstVisiblePosition();
                        int last = dlListView.getLastVisiblePosition();
                        if (idx >= first && idx <= last) return;
                        glideToDlPath(cur);
                    }
                });
                return;
            }
        }
        dlFreshGlidePending = false;
    }

    // v3.96: library return-glide to the playing file — the mirror
    // of the Talks return-scroll (v3.94) on the library surface.
    // The v1.72 keep-scroll rule owns every library return, so
    // after changing tracks in the Room (Next, drawer pick, or a
    // background auto-advance while this screen sat in the back
    // stack) and pressing back, the library still sat at its
    // pre-leave position with the sounding file — pulsing row +
    // v3.93 live position — possibly screens away, while the
    // v3.95 fresh-open glide only ever ran on a fresh creation.
    // On a back-stack return only (flag armed in onResume after a
    // real pause, never on creation or rotation), when the service
    // track differs from the track onPause anchored on leaving,
    // the list now glides to it instead of sitting at the old
    // position. Guards, mirroring v3.94/v3.95: a jump target always
    // wins; a stopped session has no track; a streamed Room talk
    // is no downloaded file, so no row exists and the anchor is
    // left alone (a later library play still glides on its return);
    // an already-visible row never scrolls (visible range checked
    // inside the post, after layout and after any theme-dip
    // rebind); an unchanged return leaves the browsed position
    // exactly alone, so keep-scroll is untouched for the common
    // case. The glide is ListView's own smooth scroll, posted so
    // it runs after this return's layout pass. Visual only; the
    // keep-scroll restore, sort, search, delete and download
    // reload paths are untouched, and no view is added.
    private void tryDlReturnGlide() {
        if (!dlReturnGlidePending || dlListView == null || !dlListLoaded) return;
        dlReturnGlidePending = false;
        if (jumpPath != null) return;
        if (service == null || !service.hasTrack()) return;
        final String cur = service.getCurrentUrl();
        if (cur == null || cur.length() == 0) return;
        if (cur.equals(lastSeenDlPlayingPath)) return;
        // v11.43: same queue-only guard as applyJumpOnce above —
        // the old code exited queue-only here, before the shelf
        // lookup, so a return whose playing file was deleted
        // mid-absence, or hidden by a search/filter, dropped the
        // queue-only view with no glide at all (the anchor is
        // deliberately left alone on a miss, so the next return
        // retries — only the view loss was wrong). Find the row
        // first; exit only when a real shelf row will scroll.
        // The playing file is never queued, so a queue-only row
        // for it cannot exist (v4.58) — the exit is still needed,
        // it just moves inside the found branch.
        for (int i = 0; i < shownDl.size(); i++) {
            if (shownDl.get(i).file.getAbsolutePath().equals(cur)) {
                if (libQueueOnly) {
                    libQueueOnly = false;
                    if (adapter != null) adapter.notifyDataSetChanged();
                }
                lastSeenDlPlayingPath = cur;
                dlListView.post(new Runnable() {
                    @Override public void run() {
                        if (dlListView == null) return;
                        int first = dlListView.getFirstVisiblePosition();
                        int last = dlListView.getLastVisiblePosition();
                        int idx = -1;
                        for (int j = 0; j < shownDl.size(); j++)
                            if (shownDl.get(j).file.getAbsolutePath().equals(cur)) { idx = j; break; }
                        if (idx < 0 || (idx >= first && idx <= last)) return;
                        glideToDlPath(cur);
                    }
                });
                return;
            }
        }
    }

    private void handleLibNext() {
        // v2.05: only a missing service queues the tap. The old
        // condition also queued it when both lists were empty — a
        // genuinely empty library then kept a stale pending Next
        // that fired on the next loadList, e.g. after an unrelated
        // download, advancing to a file the listener never asked
        // for. With nothing to advance to, drop it instead.
        if (service == null) {
            pendingDlNext = true;
            return;
        }
        if (shownDl.isEmpty() && entries.isEmpty()) {
            // ...except while the list has never loaded (permission
            // still pending, first load not run): then the empty
            // lists prove nothing, and the bind/loadList flush must
            // still get its chance — keep the tap queued for it.
            if (!dlListLoaded) {
                pendingDlNext = true;
                return;
            }
            pendingDlNext = false;
            return;
        }
        pendingDlNext = false;
        // v4.57: the hand queue outranks list order — a manual /
        // headset / notification / mini-player Next plays the
        // queued head first (consumed by takeLibQueueHead), like
        // the Room's queued-head-first Next (v4.32). A queue of
        // only deleted files falls through to list order below.
        // v11.73: skip-dead-files — takeLibQueueHead matches against entries (the last
        // scan), so a queued file deleted after it used to be played dead here; the
        // poll's v11.66 loop already keeps taking until a live head. Same shape: dead
        // heads are dropped by the take itself, never anchored, never played.
        Entry qe = null;
        boolean droppedDeadQe73 = false;
        while (true) {
            Entry cand73 = takeLibQueueHead();
            if (cand73 == null) break;
            if (cand73.file.exists()) { qe = cand73; break; }
            droppedDeadQe73 = true;
        }
        if (droppedDeadQe73)
            Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "dl_file_gone"), Toast.LENGTH_SHORT).show();
        if (qe != null) {
            // v8.12: a manual queue-head Next is a heard-history
            // step too — v8.11 recorded only auto-advances, so a
            // hand-queue jump (heard A, Next to queued D) left
            // Previous falling back to D's shelf-neighbour, a file
            // never heard, instead of A; and when a trail already
            // existed (auto-advance to A), the jump's missing step
            // made the consume side clear the whole trail on the
            // cur != libTrailNext mismatch, losing the chain
            // behind A as well. Record the jump like the poll's
            // queue-head auto-advance does, but only when the file
            // being left is a live shelf file — a Room URL sounding
            // here must never enter the library trail (the poll's
            // foreignEnd75 guard gives it that guarantee; this
            // branch needs the explicit check). takeLibQueueHead
            // already skips the sounding file, and the helper's
            // same-file skip covers the no-track case. (v8.14:
            // the list-order branch below now records too — the
            // v8.12 "needs no record" claim held for one Previous
            // press only; see the v8.14 block below.) Deliberately
            // untouched: row taps (a tap is a fresh pick, not a
            // Next step), the consume side, and the service
            // background advance.
            String curBeforeQueueNext = service.getCurrentUrl();
            saveLastLibrary(qe.file.getAbsolutePath(), qe.title);
            service.play(qe.file.getAbsolutePath(), qe.title,
                    savedPosForFile(qe.file.getAbsolutePath()));
            if (curBeforeQueueNext != null && curBeforeQueueNext.length() > 0) {
                boolean liveShelf = false;
                for (Entry e : entries)
                    if (e.file.getAbsolutePath().equals(curBeforeQueueNext)) { liveShelf = true; break; }
                if (liveShelf) noteLibTrail11(curBeforeQueueNext, qe.file.getAbsolutePath());
            }
            // v11.17: a queued play is a branch, not the redo target
            // — it invalidates the redo stack (Room's forward
            // pop-or-clear), even though the queue outranked it.
            clearLibRedo17();
            if (adapter != null) adapter.notifyDataSetChanged();
            // v11.73: queue-aware follow — glideToDlPath scrolls to the queue row in
            // queue-only view (or no-ops when the played file has no queue row), instead
            // of a shelf index into a queue-ordered list.
            glideToDlPath(qe.file.getAbsolutePath());
            return;
        }
        // v11.17: redo — after a trail-Previous, Next returns to the
        // file just left (Room v8.26 parity), outranked only by the
        // hand queue above and outranking shuffle/list-next below.
        // Popping consumes only the top, so repeated Next walks
        // the whole back-step chain; the play records its own
        // trail step, so a later Previous walks back again. A
        // stack whose trailNext no longer names the sounding file
        // is not trusted — it falls through to list order, which
        // clears it as a branching play below.
        String redoCur17 = service.getCurrentUrl();
        Entry redo17 = popLibRedo17(redoCur17);
        // v11.73: skip-dead-files — the poll's playLibRedoAdvance17 already refuses a redo
        // target deleted after the last scan (v11.66); this manual branch never did. The
        // pop already consumed the stack entry, so fall through to list order below.
        if (redo17 != null && !redo17.file.exists()) {
            Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "dl_file_gone"), Toast.LENGTH_SHORT).show();
            redo17 = null;
        }
        if (redo17 != null) {
            saveLastLibrary(redo17.file.getAbsolutePath(), redo17.title);
            service.play(redo17.file.getAbsolutePath(), redo17.title,
                    savedPosForFile(redo17.file.getAbsolutePath()));
            if (redoCur17 != null && redoCur17.length() > 0)
                noteLibTrail11(redoCur17, redo17.file.getAbsolutePath());
            if (adapter != null) adapter.notifyDataSetChanged();
            // v11.73: queue-aware follow — glideToDlPath scrolls to the queue row in
            // queue-only view (or no-ops when the played file has no queue row), instead
            // of a shelf index into a queue-ordered list.
            glideToDlPath(redo17.file.getAbsolutePath());
            return;
        }
        String cur = service.getCurrentUrl();
        java.util.List<Entry> list = shownDl;
        int idx = -1;
        for (int i = 0; i < list.size(); i++)
            if (list.get(i).file.getAbsolutePath().equals(cur)) { idx = i; break; }
        if (idx < 0) {
            list = entries;
            for (int i = 0; i < list.size(); i++)
                if (list.get(i).file.getAbsolutePath().equals(cur)) { idx = i; break; }
        }
        if (list.isEmpty()) return;
        // v2.05: a manual Next used to die silently when the current
        // file was gone (deleted externally while playing) — the
        // tap cleared its pending flag and advanced nothing. Start
        // the list over instead, like the wrap rule for a found
        // track; a found track still advances to the next file.
        // v10.90: a found track with shuffle on advances to a
        // random shown file instead of list-next — the Room's
        // manual Next has played its shuffle pick since v4.36
        // and this screen's own auto-advance poll honours
        // effective shuffle (either flag, v7.33), but this manual
        // branch never checked either flag, so strip/headset/
        // notification Next in the library ignored a shuffle the
        // listener had just turned on. Queue-head above still
        // outranks it, the deleted-current restart and the
        // one-file replay are untouched, and the trail record +
        // glide below are generic over the picked entry.
        Entry e;
        if (idx < 0) e = list.get(0);
        else if (list.size() > 1
                && (dlShuffle || Prefs.shuffleOn(DownloadedActivity.this))) {
            int pick;
            do { pick = (int) (Math.random() * list.size()); } while (pick == idx);
            e = list.get(pick);
        } else e = list.get((idx + 1) % list.size());
        // v11.73: skip-dead-files — the pick above comes from the last scan, so a file
        // deleted after it used to be anchored and played dead (generic "check internet"
        // toast, no advance). Walk the same order the single pick implies — forward with
        // wrap, or a shuffled other file — for the first file still on disk; if every
        // candidate is gone, say so once and rescan instead of playing dead.
        if (!e.file.exists()) {
            java.util.ArrayList<Integer> cand73 = new java.util.ArrayList<Integer>();
            boolean effShuffle73 = list.size() > 1
                    && (dlShuffle || Prefs.shuffleOn(DownloadedActivity.this));
            if (effShuffle73) {
                for (int ci = 0; ci < list.size(); ci++) if (ci != idx) cand73.add(ci);
                java.util.Collections.shuffle(cand73);
            } else if (idx < 0) {
                for (int ci = 0; ci < list.size(); ci++) cand73.add(ci);
            } else {
                for (int ci = idx + 1; ci < list.size(); ci++) cand73.add(ci);
                for (int ci = 0; ci < idx; ci++) cand73.add(ci);
            }
            Entry live73 = null;
            for (int ci : cand73) {
                if (list.get(ci).file.exists()) { live73 = list.get(ci); break; }
            }
            if (live73 == null) {
                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "dl_file_gone"), Toast.LENGTH_SHORT).show();
                loadList();
                return;
            }
            e = live73;
        }
        saveLastLibrary(e.file.getAbsolutePath(), e.title);
        service.play(e.file.getAbsolutePath(), e.title,
                savedPosForFile(e.file.getAbsolutePath()));
        // v8.14: a manual list-order Next is a heard-history step
        // too — the v8.12 claim that it needs no record held for
        // ONE Previous press only. Chain: heard A, queue-jump to
        // D (trail [A], next D, v8.12), then list Next to E (D's
        // shelf successor). Without a record, Previous from E
        // mismatches (E != D), clears the whole trail, and falls
        // back to D by shelf luck — then the second Previous
        // walks to D's shelf predecessor, a file never heard,
        // instead of A. Recording the list step (trail [A, D],
        // next E) makes both presses walk true heard order, and
        // the same holds under a filtered/reordered shown list,
        // where the shelf-neighbour fallback is not the heard
        // file at all. Guarded exactly like the queue branch:
        // only a live shelf file is recorded, so a Room URL
        // sounding here, a deleted cur (idx < 0), or no track
        // never enters the trail; the helper's same-file skip
        // covers the one-file wrap. Row taps stay fresh picks
        // (v8.13 clear), auto-advances still record their own
        // way, Previous/consume untouched.
        if (cur != null && cur.length() > 0) {
            boolean liveShelf = false;
            for (Entry en : entries)
                if (en.file.getAbsolutePath().equals(cur)) { liveShelf = true; break; }
            if (liveShelf) noteLibTrail11(cur, e.file.getAbsolutePath());
        }
        // v11.17: a list/shuffle play is a branch — it invalidates
        // the redo stack (a redo that survived to here was either
        // untrusted by the gate or pruned, never the target).
        clearLibRedo17();
        if (adapter != null) adapter.notifyDataSetChanged();
        // v2.63: same follow as the auto-advance above — a headset /
        // notification Next used to switch the playing file while the
        // list stayed wherever it was, so the new file's pulse row
        // could sit off-screen unseen. Glide to it when it is in the
        // shown list (a fallback-to-full-list Next whose file is
        // filtered out has no visible row to glide to). Visual only.
        // v11.73: queue-aware follow — glideToDlPath scrolls to the queue row in
        // queue-only view (or no-ops when the played file has no queue row), instead
        // of a shelf index into a queue-ordered list.
        glideToDlPath(e.file.getAbsolutePath());
    }

    // v8.11: record one auto-advance step for Previous — the
    // activity-side copy of AudioService.noteLibTrail11 (same keys,
    // same cap, same same-file skip). Only auto-advances record:
    // manual plays leave the trail alone, and the consume side
    // below invalidates it on any manual move.
    private void noteLibTrail11(String ended, String next) {
        try {
            if (ended == null || ended.length() == 0
                    || next == null || next.length() == 0
                    || ended.equals(next)) return;
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String raw = sp.getString("libPrevTrail", "");
            java.util.ArrayList<String> trail = new java.util.ArrayList<String>();
            if (raw != null && raw.length() > 0) {
                String[] parts = raw.split("\\n");
                for (int i = 0; i < parts.length; i++)
                    if (parts[i] != null && parts[i].length() > 0) trail.add(parts[i]);
            }
            trail.add(ended);
            while (trail.size() > 50) trail.remove(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < trail.size(); i++) {
                if (i > 0) sb.append("\n");
                sb.append(trail.get(i));
            }
            sp.edit().putString("libPrevTrail", sb.toString())
                    .putString("libTrailNext", next).apply();
        } catch (Exception ignored) {}
    }

    // v8.13: clear the library heard-history trail on a genuine manual
    // pick (row tap / long-press restart / random / play-all /
    // shuffled play-all / queue-dialog plays, and their pre-bind
    // queued form at the bind flush). v8.11 recorded only advances
    // and left manual picks to the consume side's mismatch clear —
    // but a pick that re-plays exactly libTrailNext (favourite
    // re-picked after another file, or a session resumed onto it)
    // passed the cur == libTrailNext guard, so the next Previous
    // walked a stale chain (and a later auto-advance record then
    // stacked fresh entries on top of the stale prefix, so the
    // stale entry resurfaced on the second Previous). Clearing
    // eagerly makes every manual pick a chain break, exactly as
    // the v8.11 design intended; toggle taps (same file) and
    // repeat-one replays never reach here, auto-advances still
    // record instead, Next/Prev keep their own rules, and the
    // strip continue tap (applyAutoPlayOnce) is deliberately
    // excluded — it resumes the session the trail belongs to.
    private void clearLibTrail13() {
        try {
            getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                    .remove("libPrevTrail").remove("libTrailNext").remove("libRedoTrail").apply();
        } catch (Exception ignored) {}
    }

    // v11.17: library redo stack — the Room got forward/redo in
    // v8.26, but the library's trail-Previous never had one: after
    // stepping back, Next played list-next/shuffle instead of the
    // file just left, and the step after that cleared the trail, so
    // the chain was lost. libRedoTrail is the redo stack (newline-
    // joined paths, oldest first, cap 50, same shape as
    // libPrevTrail): every trail-Previous pushes the file being
    // left, every redo-Next pops the top and plays it, and any
    // other fresh play clears the stack (a branch invalidates the
    // redo, exactly like the Room's forward pop-or-clear). A redo
    // play records its own trail step (noteLibTrail11), so a later
    // Previous walks back to the back-stepped-to file again. The
    // stack is only consulted while libTrailNext still names the
    // sounding file — the same trust guard consumeLibTrailPrev
    // uses — so a stale stack from an old session can never hijack
    // a fresh session's Next; manual picks clear it eagerly via
    // clearLibTrail13 above, and the strip-continue resume
    // (excluded from that clear, v8.13) keeps its session's redo
    // intact. Peek helpers never write prefs (the 1s tick must
    // never write); only a real pop persists a prune.
    private java.util.ArrayList<String> loadLibRedo17() {
        java.util.ArrayList<String> stack = new java.util.ArrayList<String>();
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String raw = sp.getString("libRedoTrail", "");
            if (raw != null && raw.length() > 0) {
                String[] parts = raw.split("\\n");
                for (int i = 0; i < parts.length; i++)
                    if (parts[i] != null && parts[i].length() > 0) stack.add(parts[i]);
            }
        } catch (Exception ignored) {}
        return stack;
    }
    private void saveLibRedo17(java.util.ArrayList<String> stack) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < stack.size(); i++) {
                if (i > 0) sb.append("\n");
                sb.append(stack.get(i));
            }
            getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                    .putString("libRedoTrail", sb.toString()).apply();
        } catch (Exception ignored) {}
    }
    private void clearLibRedo17() {
        try {
            getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                    .remove("libRedoTrail").apply();
        } catch (Exception ignored) {}
    }
    private void pushLibRedo17(String path) {
        try {
            if (path == null || path.length() == 0) return;
            java.util.ArrayList<String> stack = loadLibRedo17();
            if (!stack.isEmpty() && stack.get(stack.size() - 1).equals(path)) return;
            stack.add(path);
            while (stack.size() > 50) stack.remove(0);
            saveLibRedo17(stack);
        } catch (Exception ignored) {}
    }
    private Entry libRedoTopEntry17(String cur) {
        try {
            if (cur == null || cur.length() == 0) return null;
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String next = sp.getString("libTrailNext", "");
            if (next == null || !next.equals(cur)) return null;
            java.util.ArrayList<String> stack = loadLibRedo17();
            for (int i = stack.size() - 1; i >= 0; i--) {
                String p = stack.get(i);
                for (Entry e : entries)
                    if (e.file.getAbsolutePath().equals(p)) {
                        if (p.equals(cur)) return null;
                        return e;
                    }
            }
            return null;
        } catch (Exception ignored) { return null; }
    }
    private Entry popLibRedo17(String cur) {
        try {
            if (cur == null || cur.length() == 0) return null;
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String next = sp.getString("libTrailNext", "");
            if (next == null || !next.equals(cur)) return null;
            java.util.ArrayList<String> stack = loadLibRedo17();
            boolean changed = false;
            while (!stack.isEmpty()) {
                String p = stack.get(stack.size() - 1);
                Entry found = null;
                for (Entry e : entries)
                    if (e.file.getAbsolutePath().equals(p)) { found = e; break; }
                if (found == null) { stack.remove(stack.size() - 1); changed = true; continue; }
                if (p.equals(cur)) { if (changed) saveLibRedo17(stack); return null; }
                stack.remove(stack.size() - 1);
                saveLibRedo17(stack);
                return found;
            }
            if (changed) saveLibRedo17(stack);
            return null;
        } catch (Exception ignored) { return null; }
    }
    private java.util.ArrayList<Entry> libRedoPreviewEntries17(String cur) {
        java.util.ArrayList<Entry> out = new java.util.ArrayList<Entry>();
        try {
            if (cur == null || cur.length() == 0) return out;
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String next = sp.getString("libTrailNext", "");
            if (next == null || !next.equals(cur)) return out;
            java.util.ArrayList<String> stack = loadLibRedo17();
            boolean firstLive = true;
            for (int i = stack.size() - 1; i >= 0; i--) {
                String p = stack.get(i);
                Entry found = null;
                for (Entry e : entries)
                    if (e.file.getAbsolutePath().equals(p)) { found = e; break; }
                if (found == null) continue;
                if (firstLive) {
                    if (p.equals(cur)) return new java.util.ArrayList<Entry>();
                    firstLive = false;
                }
                out.add(found);
            }
        } catch (Exception ignored) {}
        return out;
    }
    // v11.17: poll auto-advance redo play — the same redo target
    // handleLibNext plays, for a file that ends naturally after a
    // back-step (Room parity: its background advance consults
    // forward via peekNextIndex). Same neighbours as the list
    // advance below it (2-arg play, announcement, glide, trail
    // record); only the top is consumed, so a chained advance
    // keeps walking the redo stack. False = no valid redo, and
    // the caller falls through to list/shuffle order.
    private boolean playLibRedoAdvance17(String cur) {
        try {
            Entry redo = popLibRedo17(cur);
            if (redo == null || service == null) return false;
            // v11.66: skip-dead-files — pop matches against entries (the last scan), so a
            // redo target deleted after it must not be played dead. The pop already
            // consumed the stack entry, so returning false lets the poll's list loop
            // pick the next live file instead of dying on the generic failure toast.
            if (!redo.file.exists()) {
                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "dl_file_gone"), Toast.LENGTH_SHORT).show();
                return false;
            }
            saveLastLibrary(redo.file.getAbsolutePath(), redo.title);
            // v12.20: resume the pick's saved spot (Room-advance
            // parity) — the 2-arg play restarted a half-heard file at 0.
            service.play(redo.file.getAbsolutePath(), redo.title,
                    savedPosForFile(redo.file.getAbsolutePath()));
            if (cur != null && cur.length() > 0)
                noteLibTrail11(cur, redo.file.getAbsolutePath());
            String sd = libSayadawMap.get(redo.file.getName());
            String sdSuffix = (sd != null && sd.length() > 0) ? " · " + sd : "";
            Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "next_prefix") + (libFavFiles.contains(redo.file.getName()) ? "★ " : "") + redo.title + sdSuffix, Toast.LENGTH_SHORT).show();
            // v11.73: queue-aware follow — glideToDlPath scrolls to the queue row in
            // queue-only view (or no-ops when the played file has no queue row), instead
            // of a shelf index into a queue-ordered list.
            glideToDlPath(redo.file.getAbsolutePath());
            return true;
        } catch (Exception ignored) { return false; }
    }

    // v8.11: consume one heard-history step for Previous, or null
    // to fall back to list order. The trail is only trusted while
    // the sounding file is still the file the last recorded
    // advance started — a manual play since then clears it, so a
    // stale chain can never redirect a fresh session's Previous.
    // Stale tail entries (file deleted since the advance) are
    // dropped until a live shelf file remains; the consumed entry
    // becomes the new trailNext so repeated presses walk the
    // chain in heard order.
    private String consumeLibTrailPrev(String cur) {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String next = sp.getString("libTrailNext", "");
            String raw = sp.getString("libPrevTrail", "");
            if (cur == null || cur.length() == 0
                    || next == null || !cur.equals(next)
                    || raw == null || raw.length() == 0) {
                if ((raw != null && raw.length() > 0)
                        || (next != null && next.length() > 0))
                    sp.edit().remove("libPrevTrail").remove("libTrailNext").apply();
                return null;
            }
            String[] parts = raw.split("\\n");
            java.util.ArrayList<String> trail = new java.util.ArrayList<String>();
            for (int i = 0; i < parts.length; i++)
                if (parts[i] != null && parts[i].length() > 0) trail.add(parts[i]);
            String hit = null;
            while (!trail.isEmpty()) {
                String cand = trail.remove(trail.size() - 1);
                // v11.73: skip-dead-files — entries is the last scan, so a trail file deleted
                // after it still matched here and Previous played it dead. A live step must
                // still be on disk; dead candidates keep dropping like stale ones.
                boolean live = false;
                for (Entry e : entries)
                    if (e.file.getAbsolutePath().equals(cand) && e.file.exists()) { live = true; break; }
                if (live) { hit = cand; break; }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < trail.size(); i++) {
                if (i > 0) sb.append("\n");
                sb.append(trail.get(i));
            }
            android.content.SharedPreferences.Editor ed = sp.edit();
            if (hit != null) {
                ed.putString("libPrevTrail", sb.toString());
                ed.putString("libTrailNext", hit);
            } else {
                ed.remove("libPrevTrail");
                ed.remove("libTrailNext");
            }
            ed.apply();
            return hit;
        } catch (Exception ignored) { return null; }
    }

    // v4.23: Previous parity — the mirror of handleLibNext
    // above, stepping BACK with the same wrap (first file wraps
    // to the last), the same saved-spot resume, the same
    // follow-glide, and the same queueing when unbound or
    // unloaded. Same streaming convention as the Room's
    // handlePrev: more than 3s into a file, Previous restarts
    // that file (a plain seek to 0, play state untouched)
    // instead of skipping. A current file that vanished
    // (deleted externally) steps to the LAST file — the
    // backward mirror of Next's restart-at-first.
    private void handleLibPrev() {
        if (service == null) {
            pendingDlPrev = true;
            return;
        }
        if (shownDl.isEmpty() && entries.isEmpty()) {
            if (!dlListLoaded) {
                pendingDlPrev = true;
                return;
            }
            pendingDlPrev = false;
            return;
        }
        pendingDlPrev = false;
        // v11.89: an ended track must not take the restart branch — after a natural
        // end with autoplay off, the position sits at the full duration (>3000), so
        // ⏮ used to seek to 0 and park the finished file paused, its ended state
        // consumed and the poll never advancing. An ended track falls through to the
        // previous-file walk below, like Next advances after an end.
        if (!service.hasTrackEnded() && service.getEffectivePosition() > 3000) { if (service.isBuffering()) service.restartFromStart(); else service.seekTo(0); return; }
        String cur = service.getCurrentUrl();
        // v8.11: heard-history Previous — when the sounding file is
        // still the file the last recorded auto-advance started
        // (libTrailNext), walk the trail instead of the shelf:
        // after a shuffle pick or a queue-head jump the
        // shelf-neighbour is a file never heard, while the trail
        // tail is the file actually heard before. Repeated presses
        // walk the chain (the consumed entry becomes the new
        // trailNext). Any manual move since the advance clears
        // the trail and falls through to list order below, so
        // Previous after a manual play behaves exactly as before.
        String trailPrev = consumeLibTrailPrev(cur);
        if (trailPrev != null) {
            // v11.17: record the redo step — the file being left is
            // what the next Next must return to (Room v8.26); cur
            // is still the pre-step file here, consume only moved
            // the trail pointers, and the play below has not run.
            pushLibRedo17(cur);
            String tTitle = null;
            for (Entry e : entries)
                if (e.file.getAbsolutePath().equals(trailPrev)) { tTitle = e.title; break; }
            if (tTitle == null) {
                String fn = new java.io.File(trailPrev).getName();
                if (fn.toLowerCase().endsWith(".mp3")) fn = fn.substring(0, fn.length() - 4);
                tTitle = fn;
            }
            saveLastLibrary(trailPrev, tTitle);
            service.play(trailPrev, tTitle, savedPosForFile(trailPrev));
            if (adapter != null) adapter.notifyDataSetChanged();
            // v11.73: queue-aware follow — glideToDlPath scrolls to the queue row in
            // queue-only view (or no-ops when the played file has no queue row), instead
            // of a shelf index into a queue-ordered list.
            glideToDlPath(trailPrev);
            return;
        }
        java.util.List<Entry> list = shownDl;
        int idx = -1;
        for (int i = 0; i < list.size(); i++)
            if (list.get(i).file.getAbsolutePath().equals(cur)) { idx = i; break; }
        if (idx < 0) {
            list = entries;
            for (int i = 0; i < list.size(); i++)
                if (list.get(i).file.getAbsolutePath().equals(cur)) { idx = i; break; }
        }
        if (list.isEmpty()) return;
        Entry e = idx < 0 ? list.get(list.size() - 1)
                : list.get((idx - 1 + list.size()) % list.size());
        // v11.73: skip-dead-files — same class as the Next list branch: walk backward
        // from the pick (wrapping, like Previous itself) for the first file still on disk.
        if (!e.file.exists()) {
            java.util.ArrayList<Integer> cand73 = new java.util.ArrayList<Integer>();
            if (idx < 0) {
                for (int ci = list.size() - 1; ci >= 0; ci--) cand73.add(ci);
            } else {
                for (int ci = idx - 1; ci >= 0; ci--) cand73.add(ci);
                for (int ci = list.size() - 1; ci > idx; ci--) cand73.add(ci);
            }
            Entry live73 = null;
            for (int ci : cand73) {
                if (list.get(ci).file.exists()) { live73 = list.get(ci); break; }
            }
            if (live73 == null) {
                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "dl_file_gone"), Toast.LENGTH_SHORT).show();
                loadList();
                return;
            }
            e = live73;
        }
        saveLastLibrary(e.file.getAbsolutePath(), e.title);
        service.play(e.file.getAbsolutePath(), e.title,
                savedPosForFile(e.file.getAbsolutePath()));
        // v11.18: record the fallback step, Room v8.17 parity — the
        // trail branch above pushes redo, but this branch recorded
        // nothing, so a second Previous walked shelf neighbours
        // instead of the talk actually heard before. Recording the
        // step (live-shelf files only, idx >= 0, like the Next
        // branches' liveShelf guard) makes the next Previous walk
        // back to the file just left. Clear any stale redo first:
        // consume's invalid branch wipes the trail keys but leaves
        // libRedoTrail behind, and this record writes a fresh
        // libTrailNext that would otherwise make the redo gate
        // trust that stale stack. A deleted-current fallback
        // (idx < 0) keeps today's behaviour exactly.
        if (idx >= 0) {
            clearLibRedo17();
            noteLibTrail11(cur, e.file.getAbsolutePath());
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        // v11.73: queue-aware follow — glideToDlPath scrolls to the queue row in
        // queue-only view (or no-ops when the played file has no queue row), instead
        // of a shelf index into a queue-ordered list.
        glideToDlPath(e.file.getAbsolutePath());
    }

    // v1.93: saved resume position for a library file — the
    // service's saved spot counts when it names this file's path
    // OR the file's original talk URL (found through the v1.90
    // dlMap reverse lookup). A talk streamed from the Room and
    // then tapped in the library used to restart from 0, because
    // the old check only matched the file path, while the Room's
    // savedPosFor already matched both forms. Same >5s rule.
    private int savedPosForFile(String path) {
        try {
            android.content.SharedPreferences pp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String savedUrl = pp.getString("lastPosUrl", "");
            int savedPos = pp.getInt("lastPos", 0);
            org.json.JSONObject map =
                    new org.json.JSONObject(pp.getString("dlMap", "{}"));
            // v5.35: the global spot must not gate the per-talk
            // fallback below — an explicit stop clears the global
            // spot entirely, but other talks' map spots survive
            // it, and a cleared global used to return 0 here
            // before the fallback was ever reached. So the global
            // match is now a guarded block, not early returns.
            if (savedUrl.length() > 0 && savedPos > 5000) {
                if (path.equals(savedUrl)) return savedPos;
                java.util.Iterator<String> keys = map.keys();
                while (keys.hasNext()) {
                    String u = keys.next();
                    if (u.equals(savedUrl)
                            && path.endsWith("/" + map.optString(u, "")))
                        return savedPos;
                }
            }
            // v5.35: per-talk fallback — the global spot above
            // only ever covered the most recent talk, so any
            // other half-heard file restarted from 0 even though
            // v5.34's map holds its spot (the service writes it
            // under both forms on every checkpoint/pause/seek).
            // Same >5s rule as the Room's savedPosFor. The
            // stream-form lookup covers a spot saved while
            // streaming, before this file was downloaded.
            int per = Prefs.talkPosGet(DownloadedActivity.this, path);
            if (per > 5000) return per;
            // v8.44: the stream-form fallback below used to take the
            // FIRST dlMap claimant whose filename matches this
            // file, so a dual-claimed file (two talks mapped to
            // one name, creatable by the delivered v8.39, v8.40
            // stops new ones) resumed at the OTHER talk's saved
            // spot — a wrong jump inside this audio, the playback
            // twin of the v8.42/v8.43 display/mark fixes. Count
            // claimants for this file's name first (same exactly-
            // one rule): ambiguous files skip the stream-form
            // fallback and start from 0 instead of a wrong spot;
            // single-claim files behave exactly as before. The
            // global branch above is already safe (it matches
            // u.equals(savedUrl) exactly) and is untouched.
            String base844 = path.substring(path.lastIndexOf('/') + 1);
            int claim844 = 0;
            java.util.Iterator<String> keysC = map.keys();
            while (keysC.hasNext()) {
                String uc = keysC.next();
                String fnc = map.optString(uc, "");
                if (fnc.length() > 0 && fnc.equals(base844)) claim844++;
            }
            if (claim844 == 1) {
                java.util.Iterator<String> keys2 = map.keys();
                while (keys2.hasNext()) {
                    String u = keys2.next();
                    if (path.endsWith("/" + map.optString(u, ""))) {
                        int per2 = Prefs.talkPosGet(DownloadedActivity.this, u);
                        if (per2 > 5000) return per2;
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }
    // v1.47: last shown playback state — the list only rebinds when it
    // changes (below), instead of every second.
    private boolean lastDlPlaying = false;
    // v1.87: same for the buffering-active state (see the row icon
    // below) — a hold/un-hold tap while buffering changes the icon
    // without changing isPlaying() or the track, so the old rebind
    // condition never refreshed the row after that tap.
    private boolean lastDlActive = false;
    private String lastDlTrack = null;
    // v1.85: which ended file the refresh already handled — the
    // Room got this marker in v1.69, the library never did. Without
    // it, an ended file that left the shown list (a search typed
    // after it ended, or autoplay off at the time) and later came
    // back into the list re-triggered auto-advance long after the
    // end, as if it had just finished. A no-match or autoplay-off
    // end is consumed at once (marked handled); any new play clears
    // the flag via the service's reset completion flag below, so a
    // replayed file still ends handled again.
    private String dlEndedHandledFor = null;
    // v3.93: library playing-row clock — same H:MM:SS shape as the
    // Room's formatTime (v1.47), local so this file needs no import.
    private static String fmtDlTime(int ms) {
        int total = Math.max(0, ms / 1000);
        if (total >= 3600)
            return (total / 3600) + ":" + String.format("%02d", (total % 3600) / 60)
                    + ":" + String.format("%02d", total % 60);
        return (total / 60) + ":" + String.format("%02d", total % 60);
    }

    private boolean dlResumeStripMode = false;
    // v6.02: the sayadaw whose portrait the library strip last
    // showed (null = photo hidden) — Main's v6.00 / Talks'
    // v6.01 strip-photo guard, mirrored, so the 1s tick never
    // re-loads a face.
    private String dlStripPhotoName = null;

    // v6.02: sayadaw portrait on the library strip — Main's v6.00
    // and Talks' v6.01 mirror, completing portrait parity across
    // all three mini-player surfaces. The library strip was the
    // last text-only one: a listener browsing downloads while a
    // talk played saw only title over name. The portrait
    // resolves through AudioService's once-per-process catalog
    // map (the library holds no catalog list), shown through
    // ImageLoader.load so a never-cached portrait reads as that
    // sayadaw's own gold initial circle until its download lands,
    // exactly like a grid row. A library session with no recorded
    // sayadaw keeps today's text-only strip, never a guessed face.
    // Called at every strip visibility edge below, so a hidden
    // strip never leaves a stale face behind, and a session switch
    // lands the new face on the same tick.
    private void syncDlStripPhoto(String name) {
        android.view.View pv = findViewById(R.id.nowStripPhoto);
        if (pv == null) return;
        if (name == null || name.length() == 0) {
            dlStripPhotoName = null;
            pv.setVisibility(android.view.View.GONE);
            return;
        }
        pv.setVisibility(android.view.View.VISIBLE);
        if (name.equals(dlStripPhotoName)) return;
        dlStripPhotoName = name;
        String url = AudioService.stripPhotoUrlForSayadaw(DownloadedActivity.this, name);
        ImageLoader.load(DownloadedActivity.this, url, name, (CircleImageView) pv);
    }

    // v4.40: real mini-player on the library, the same strip as
    // Main (v4.15/v4.30) and Talks (v4.31) — the library was the
    // last playback surface without one, so a listener browsing
    // downloads had only the notification card for pause/next.
    // Same live rule (liveSession + lastKind/lastTitle prefs),
    // same title-over-sub split, same ★/😴/finished suffixes,
    // same font-size steps (14/16/20 + 12/14/16, v4.39 pattern)
    // and same service-tick progress — but NO ⏳ download query:
    // this screen's own 1s tick already shows live download
    // progress in the rows/footer (v4.27), and the tick stays a
    // cheap prefs/statics read. Tap glides to the playing file
    // when the session is a library one (the file is already on
    // this screen — no navigation), or returns to the Room for a
    // Room session, exactly like Main's strip tap; ⏯ toggles via
    // the service intent with no bind claim; Next routes like
    // Main's Next (lib -> this screen's ACTION_NEXT, room ->
    // Room's ACTION_NEXT); long-press stops the session outright.
    private void syncDlStrip() {
        android.view.View strip = findViewById(R.id.nowStrip);
        if (strip == null) return;
        boolean night = cachedDlNight;
        int navText = night ? android.graphics.Color.WHITE
                : android.graphics.Color.parseColor("#3B2314");
        int gold = android.graphics.Color.parseColor("#C9A227");
        // v13.03: real liquid glass — GlassBlur blurs the content behind this surface.
        GlassBlur.attach(strip, night);
        android.widget.TextView tx = findViewById(R.id.nowStripText);
        android.widget.TextView tg = findViewById(R.id.nowStripToggle);
        android.widget.TextView sb = findViewById(R.id.nowStripSub);
        android.widget.TextView nx = findViewById(R.id.nowStripNext);
        if (tx != null) tx.setTextColor(navText);
        // v10.94: strip glyphs follow navText, not forced white —
        // day mode paints the strip nav_glass (translucent white)
        // over the cream background, so pure-white glyphs were
        // invisible in day mode on all four strips (night is
        // unchanged: navText IS white on the dark glass).
        if (tg != null) tg.setTextColor(navText);
        if (sb != null) sb.setTextColor(navText);
        if (nx != null) nx.setTextColor(navText);
        android.widget.TextView pv = findViewById(R.id.nowStripPrev);
        if (pv != null) pv.setTextColor(navText);

        int fs = Prefs.fontSize(this);
        if (tx != null) tx.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fs == 0 ? 14 : (fs == 1 ? 16 : 20));
        if (sb != null) sb.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fs == 0 ? 12 : (fs == 1 ? 14 : 16));
        // v10.39: bound-but-trackless leg of the live rule — the
        // v10.38 audit's named candidate, confirmed real. liveSession
        // alone cannot tell a live strip from a dead one in two
        // windows: (a) an explicit stop while this screen is bound
        // (a stopSelf cannot destroy a bound service, so onDestroy
        // never runs and liveSession stays true with no track), and
        // (b) a trackless rebind after a service death (a fresh
        // service's onCreate sets liveSession=true before any track
        // exists). In both, the old live branch painted a dead strip
        // from stale prefs (🎵 title + ▶, 0 progress): the first ▶
        // tap was a dead ACTION_TOGGLE (trackless → stopSelf), and
        // only a second tap, after the strip had flipped to resume
        // mode on its own, actually resumed. A bound service with
        // no track now takes the resume branch below at once: a ↺
        // strip when a >5s spot survives (one tap resumes), hidden
        // when the stop cleared the spot. Paused sessions are
        // untouched (play() sets the track synchronously, so a
        // paused talk still hasTrack), and the pre-bind window is
        // unchanged (service null → the old rule until the bind
        // lands, at most one tick). No new UI, no toast.
        if (!AudioService.liveSession || (service != null && !service.hasTrack())) {
            // v4.62: continue-listening strip on the library — the
            // mirror of Main's v4.60 strip and the Talks chooser's
            // v4.61 strip, completing dead-session resume parity
            // across all three mini-player surfaces. A reboot or
            // system kill used to leave the library with no strip
            // at all even though a saved Room spot (>5s, same
            // threshold) was waiting, so a listener browsing
            // downloads had to leave for the nav Player to re-find
            // the talk. When the last session was a Room talk with
            // such a spot (talk URL, or its downloaded file via
            // the dlMap suffix match), the strip now shows it
            // instead of vanishing: "↺ <title>" over "<sayadaw>
            // · mm:ss မှ ဆက်နားထောင်ရန်", with the favourite ★
            // when it applies; tap and ▶ both re-open the Room,
            // which resumes from that spot via its autoUrl +
            // saved-position path. A library session keeps the old
            // behaviour (strip hidden, the library's own row
            // resume untouched), and an explicit stop already
            // clears the spot (v1.57), so a stopped talk never
            // shows here. Next is hidden in this mode (advancing
            // needs a live track) and progress reads empty (no
            // duration survives a dead session).
            dlResumeStripMode = false;
            android.content.SharedPreferences rsp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String rKind = rsp.getString("lastKind", "");
            String rName = rsp.getString("lastName", "");
            String rTitle = rsp.getString("lastTitle", "");
            String rPage = rsp.getString("lastPage", "");
            String rUrl = rsp.getString("lastUrl", "");
            int rPos = rsp.getInt("lastPos", 0);
            String rSavedUrl = rsp.getString("lastPosUrl", "");
            boolean rMatch = false;
            if (rUrl.length() > 0 && rSavedUrl.length() > 0) {
                if (rSavedUrl.equals(rUrl)) rMatch = true;
                else {
                    try {
                        org.json.JSONObject map = new org.json.JSONObject(rsp.getString("dlMap", "{}"));
                        String fn = map.optString(rUrl, "");
                        if (fn.length() > 0 && rSavedUrl.endsWith("/" + fn)) rMatch = true;
                    } catch (Exception ignored) {}
                }
            }
            // v4.63: continue-listening strip for a dead LIBRARY
            // session — the mirror of the v4.62 Room strip, and
            // the last dead-session gap: a reboot or system kill
            // used to leave every strip hidden after a downloaded
            // file was paused, even though its saved spot (>5s,
            // the same threshold) was waiting, so the listener
            // had to find the file's row by hand on every surface.
            // Same rule as the library's own row resume (path, or
            // its Room URL via the dlMap reverse lookup), same
            // presentation (↺ title over the offline sub line +
            // mm:ss resume cue, ★ when it applies, ▶, Next
            // hidden, progress empty). Tap and ▶ play the file
            // from the spot (see the strip tap below); long-press
            // dismisses the spot like the Room strip's.
            if ("lib".equals(rKind)) {
                String lTitle = rsp.getString("lastLibTitle", "");
                String lPath = rsp.getString("lastLibPath", "");
                boolean lMatch = false;
                if (lPath.length() > 0 && rPos > 5000 && rSavedUrl.length() > 0
                        && new java.io.File(lPath).isFile()) {
                    if (rSavedUrl.equals(lPath)) lMatch = true;
                    else {
                        try {
                            org.json.JSONObject map = new org.json.JSONObject(rsp.getString("dlMap", "{}"));
                            java.util.Iterator<String> keys = map.keys();
                            String fn = new java.io.File(lPath).getName();
                            while (keys.hasNext()) {
                                String u = keys.next();
                                if (u.equals(rSavedUrl) && fn.equals(map.optString(u, ""))) { lMatch = true; break; }
                            }
                        } catch (Exception ignored) {}
                    }
                }
                if (lTitle.length() == 0 || !lMatch) {
                    syncDlStripPhoto(null);
                    strip.setVisibility(android.view.View.GONE);
                    return;
                }
                // v8.45: the dead-library strip's star no longer takes
                // the first favourited dlMap claimant for the file's
                // name — the v8.44 ambiguity class, display-only here:
                // a dual-claimed file (two talks, one filename,
                // creatable by delivered v8.39; v8.40 stops new ones)
                // showed ★ when the OTHER talk was favourited and
                // this strip's talk was not. The strip's own talk is
                // already identified above (lMatch): rSavedUrl is
                // either the stream URL (a dlMap key) or the file
                // path itself. Stream form: check that URL only.
                // Path form: count claimants for the filename and
                // check only when exactly one claims it; >1 claims
                // shows no star rather than a possibly wrong one.
                // Single-claim and no-claim files behave exactly as
                // before (the old loop found the same claimant).
                boolean lFav = false;
                try {
                    java.util.Set<String> ft = rsp.getStringSet("favTalks", null);
                    if (ft != null) {
                        if (ft.contains(rSavedUrl)) lFav = true;
                        else if (rSavedUrl.equals(lPath)) {
                            String fn = new java.io.File(lPath).getName();
                            org.json.JSONObject map = new org.json.JSONObject(rsp.getString("dlMap", "{}"));
                            java.util.Iterator<String> keys = map.keys();
                            int nClaim = 0;
                            String onlyClaim = null;
                            while (keys.hasNext()) {
                                String u = keys.next();
                                if (fn.equals(map.optString(u, ""))) { nClaim++; onlyClaim = u; }
                            }
                            if (nClaim == 1 && onlyClaim != null && ft.contains(onlyClaim)) lFav = true;
                        }
                    }
                } catch (Exception ignored) {}
                int lSec = rPos / 1000;
                String lTime = Fx.stripResumeTime(lSec);
                // v8.52: Main's v7.94 resume mark, mirrored — the proven file is offline, like Main's lib branch.
                if (tx != null) tx.setText("↺ " + lTitle + (lFav ? " ★" : "") + (Fx.stripInProgressDlFilenames(DownloadedActivity.this, rsp).contains(new java.io.File(lPath).getName()) ? " ⏳" : " ⬇"));
                // v5.46: a dead library strip names its recorded
                // sayadaw like the live strip since v5.45, in the
                // Room strip's "name · mm:ss မှ ဆက်နားထောင်ရန်"
                // shape; an unrecorded download keeps today's
                // generic line, never a guessed name.
                String libSd = Fx.stripLibSayadaw(DownloadedActivity.this, rsp);
                if (sb != null) sb.setText(libSd != null ? libSd + " · " + lTime + Lang.t(DownloadedActivity.this, "resume_from") : Lang.t(DownloadedActivity.this, "downloaded_talk") + " · " + lTime + Lang.t(DownloadedActivity.this, "resume_from"));
                if (tg != null) tg.setText("▶");
                if (nx != null) nx.setVisibility(android.view.View.GONE);
                // v7.35: Previous hides with Next in resume mode (Main/Talks v7.31) —
                // stepping back needs a live track exactly like advancing does, and
                // this strip never hid ⏮ here, so a ⏮ tap on a ↺ strip openly
                // ACTION_PREV-ed into a different talk instead of resuming this one.
                android.view.View pv0 = findViewById(R.id.nowStripPrev);
                if (pv0 != null) pv0.setVisibility(android.view.View.GONE);
                android.widget.ProgressBar lPb = findViewById(R.id.nowStripProgress);
                if (lPb != null) lPb.setProgress(0);
                dlResumeStripMode = true;
                syncDlStripPhoto(libSd);
                strip.setVisibility(android.view.View.VISIBLE);
                return;
            }
            if (!"room".equals(rKind) || rName.length() == 0 || rTitle.length() == 0
                    || rPage.length() == 0 || rPos <= 5000 || !rMatch) {
                syncDlStripPhoto(null);
                strip.setVisibility(android.view.View.GONE);
                return;
            }
            boolean rFav = false;
            try {
                java.util.Set<String> ft = rsp.getStringSet("favTalks", null);
                if (ft != null) rFav = ft.contains(rUrl);
            } catch (Exception ignored) {}
            int rSec = rPos / 1000;
            String rTime = Fx.stripResumeTime(rSec);
            // v8.52: Main's v7.94 resume download mark, mirrored — a ↺ strip here never said whether resuming streams or plays offline.
            if (tx != null) tx.setText("↺ " + rTitle + (rFav ? " ★" : "") + Fx.stripResumeDownloadMark(DownloadedActivity.this, rsp, rUrl));
            if (sb != null) sb.setText(rName + " · " + rTime + Lang.t(DownloadedActivity.this, "resume_from"));
            if (tg != null) tg.setText("▶");
            if (nx != null) nx.setVisibility(android.view.View.GONE);
                // v7.35: same Previous hide as the library resume branch above.
                android.view.View pv0 = findViewById(R.id.nowStripPrev);
                if (pv0 != null) pv0.setVisibility(android.view.View.GONE);
            android.widget.ProgressBar rPb = findViewById(R.id.nowStripProgress);
            if (rPb != null) rPb.setProgress(0);
            dlResumeStripMode = true;
            syncDlStripPhoto(rName);
            strip.setVisibility(android.view.View.VISIBLE);
            return;
        }
        dlResumeStripMode = false;
        if (nx != null) nx.setVisibility(android.view.View.VISIBLE);
        // v7.35: Previous returns with Next on a live session (hidden only by the
        // resume branches above, never by layout).
        android.view.View pv0 = findViewById(R.id.nowStripPrev);
        if (pv0 != null) pv0.setVisibility(android.view.View.VISIBLE);
        android.content.SharedPreferences sp =
                getSharedPreferences("dhamma", MODE_PRIVATE);
        String kind = sp.getString("lastKind", "");
        String text = null;
        String subText = null;
        if ("room".equals(kind)) {
            String name = sp.getString("lastName", "");
            String title = sp.getString("lastTitle", "");
            if (name.length() > 0 && title.length() > 0) {
                text = "🎵 " + title;
                subText = name;
            }
        } else if ("lib".equals(kind)) {
            String title = sp.getString("lastLibTitle", "");
            if (title.length() > 0) {
                text = "🎵 " + title;
                // v5.45: a library session names its sayadaw when
                // one was recorded at download time, like a Room
                // session does above; an unrecorded download keeps
                // today's generic line, never a guessed name.
                String libSd = Fx.stripLibSayadaw(DownloadedActivity.this, sp);
                subText = libSd != null ? libSd : Lang.t(DownloadedActivity.this, "downloaded_talk");
            }
        }
        if (text == null) { syncDlStripPhoto(null); strip.setVisibility(android.view.View.GONE); return; }
        boolean stripFav = false;
        try {
            java.util.Set<String> ft = sp.getStringSet("favTalks", null);
            if (ft != null) {
                if ("room".equals(kind)) {
                    stripFav = ft.contains(sp.getString("lastUrl", ""));
                } else if ("lib".equals(kind)) {
                    String lp = sp.getString("lastLibPath", "");
                    if (lp.length() > 0) {
                        // v8.46: the live library strip's star no longer takes
                        // the first favourited dlMap claimant for the file's
                        // name — the v8.45 ambiguity class, display-only here
                        // and the last first-claimant star loop in this file:
                        // a dual-claimed file (two talks, one filename,
                        // creatable by delivered v8.39; v8.40 stops new ones)
                        // showed \u2605 when the OTHER talk was favourited
                        // and this strip's talk was not, while the strip's
                        // own toggle (Fx.stripFavUrl, v8.41 exactly-one)
                        // would have done nothing on it — display and
                        // toggle disagreed. Count claimants for the filename
                        // and star only when exactly one claims it and it
                        // is favourited; >1 claims shows no star rather than
                        // a possibly wrong one. Single-claim and no-claim
                        // files behave exactly as before (the old loop
                        // found the same claimant).
                        String fn = new java.io.File(lp).getName();
                        org.json.JSONObject map =
                                new org.json.JSONObject(sp.getString("dlMap", "{}"));
                        java.util.Iterator<String> keys = map.keys();
                        int nClaim46 = 0;
                        String onlyClaim46 = null;
                        while (keys.hasNext()) {
                            String u = keys.next();
                            if (fn.equals(map.optString(u, ""))) { nClaim46++; onlyClaim46 = u; }
                        }
                        if (nClaim46 == 1 && onlyClaim46 != null && ft.contains(onlyClaim46)) stripFav = true;
                    }
                }
            }
        } catch (Exception ignored) {}
        if (stripFav) text += " ★";
        // v4.72: the strip's sleep mark gains the live countdown —
        // the Room line and the Settings sleep row already showed
        // it, but every mini-player only said 😴, so a listener
        // off the Room could not tell when playback would stop.
        // Running: "😴 M:SS"; pause-suspended: "😴 ⏸ M:SS"
        // (Room's order); no remaining: the bare mark, as before.
        // No bind: the service's liveSleepRemainingMs static,
        // refreshed by its own tick + every sleep edge.
        if (AudioService.liveSleepActive) {
            long sr = AudioService.liveSleepRemainingMs;
            // v4.82: the one-shot has no countdown — name it instead.
            if (AudioService.liveSleepEndOfTalk) text += AudioService.livePlaying
                    ? " 😴 " + Lang.t(DownloadedActivity.this, "sleep_end")
                    : " 😴 ⏸ " + Lang.t(DownloadedActivity.this, "sleep_end");
            else if (sr > 0) text += AudioService.livePlaying
                    ? " 😴 " + AudioService.sleepClock(sr)
                    : " 😴 ⏸ " + AudioService.sleepClock(sr);
            else text += " 😴";
        }
        // v5.67: the library strip names downloads in progress
        // (" ⏳ N") — the mirror of Main's v4.27 strip suffix.
        // A listener in the offline shelf saw only ★/😴/◈ here
        // while a download ran, even though the footer below
        // already counts ⏳ (v4.27) — the strip is what the eye
        // lands on first, and it is the surface with the Next
        // button, so the pending cue belongs on it too. Same
        // app-scoped rule as Main (Dhamma destination or a
        // dlMap source URL), same order (★/😴/⏳/◈), same 1s
        // tick refresh; zero adds nothing, resume mode returns
        // above, no new view. Suffix only.
        try {
            java.util.Set<String> dlUrls = null;
            try {
                org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
                dlUrls = new java.util.HashSet<String>();
                java.util.Iterator<String> keys = map.keys();
                while (keys.hasNext()) dlUrls.add(keys.next());
            } catch (Exception ignored) {}
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            // v12.70: filter in the provider — the v11.76 Talks shape; this runs
            // on the 1s strip tick and used to pull every download on the main
            // thread and discard the finished ones in Java; the status check
            // below stays as a guard.
            android.database.Cursor c = dm.query(new android.app.DownloadManager.Query()
                    .setFilterByStatus(android.app.DownloadManager.STATUS_RUNNING
                            | android.app.DownloadManager.STATUS_PAUSED
                            | android.app.DownloadManager.STATUS_PENDING));
            int progN = 0;
            if (c != null) {
                try {
                    int statusCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS);
                    int uriCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_LOCAL_URI);
                    int srcCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_URI);
                    while (c.moveToNext()) {
                        int st = statusCol >= 0 ? c.getInt(statusCol) : -1;
                        if (st != android.app.DownloadManager.STATUS_RUNNING
                                && st != android.app.DownloadManager.STATUS_PAUSED
                                && st != android.app.DownloadManager.STATUS_PENDING) continue;
                        String local = uriCol >= 0 ? c.getString(uriCol) : null;
                        String src = srcCol >= 0 ? c.getString(srcCol) : null;
                        if ((local != null && local.contains("Dhamma/"))
                                || (src != null && dlUrls != null && dlUrls.contains(src))) progN++;
                    }
                } finally { c.close(); }
            }
            if (progN > 0) text += " ⏳ " + progN;
        } catch (Exception ignored) {}
        // v4.68: the library strip reads the hand queue — the
        // mirror of Main's v4.67 strip suffix and the library
        // footer's v4.66 count. Same counting rule as Main, so
        // every strip agrees: Room sessions count this sayadaw's
        // saved queue (Prefs.savedQueue, the store the Room
        // restores from), library sessions count saved queue paths
        // that still exist as files (the shelf's stale-drop rule,
        // so a deleted file never inflates it). Suffix only, no
        // new view; the 1s tick re-reads it. Resume mode is
        // untouched (it returns above, Next hidden there);
        // zero/empty adds nothing.
        try {
            // v5.80: the strip's ◈ count skips the talk now playing —
            // the plain card's v5.43 waiting count already does, but
            // the strips counted the raw saved queue, so a playing
            // talk still saved (a service-restart play that never
            // passed the Room/library pop) read as one waiting on
            // every strip and zero on the card. Room skips lastUrl,
            // library skips lastLibPath — prefs the strip already
            // reads, so no new read and no tick cost. The ◈
            // button's visibility rule (saved queue non-empty,
            // v4.77) is untouched; an all-playing queue toasts
            // empty on open via the v5.88 gate (v5.87's Room
            // gate, mirrored for lib) — the raw gate before
            // v5.88 opened a one-row playing-only view instead.
            String curUrl = sp.getString("lastUrl", "");
            String curLib = sp.getString("lastLibPath", "");
            int qN = 0;
            if ("room".equals(kind)) {
                String saved = Prefs.savedQueue(DownloadedActivity.this,
                        sp.getString("lastName", ""));
                // v12.69: the drawer-header rule as a pure core —
                // dedup + skip the sounding talk (sim1272: a
                // repeated URL counted twice here and once in the
                // drawer header, v11.04). Ghosts are shed from
                // the store at list load (Room peek / chooser
                // prune), so this count agrees with the drawer
                // on a clean store.
                qN = Prefs.waitingQueueCountPure(saved, curUrl);
            } else if ("lib".equals(kind)) {
                String saved = Prefs.savedQueueLib(DownloadedActivity.this);
                if (saved.length() > 0) {
                    for (String line : saved.split("\n"))
                        if (line.trim().length() > 0
                                && new java.io.File(line.trim()).isFile()
                                && !line.trim().equals(curLib)) qN++;
                }
            }
            if (qN > 0) text += " · ◈ " + qN;
        } catch (Exception ignored) {}
        if (AudioService.liveEnded) text += " · " + Lang.t(DownloadedActivity.this, "notif_state_ended");
        if (tx != null) tx.setText(text);
        // v5.32: live clock on the strip — source line + elapsed/total.
        android.widget.ProgressBar pb = findViewById(R.id.nowStripProgress);
        if (subText != null) subText += Fx.stripClockSuffixForStrip(pb, AudioService.livePositionMs, AudioService.liveDurationMs, Prefs.stripClockRemaining(DownloadedActivity.this));
        if (sb != null && subText != null) sb.setText(subText);
        Fx.syncStripProgress(pb); // v7.93: skips while a mini-seek drag is in flight
        if (tg != null) tg.setText(AudioService.livePlaying ? "\u23F8\uFE0E" : "▶");
        // v6.02: live strip's portrait — Room names lastName, library
        // names its recorded sayadaw (Fx.stripLibSayadaw, null when
        // unrecorded); both resolve through syncDlStripPhoto's
        // catalog-map lookup, never a guessed face.
        syncDlStripPhoto("room".equals(kind) ? sp.getString("lastName", "")
                : ("lib".equals(kind) ? Fx.stripLibSayadaw(DownloadedActivity.this, sp) : null));
        strip.setVisibility(android.view.View.VISIBLE);
    }

    // v4.86: queue preview above the library strip — the mirror of
    // the Room's v4.83 preview + v4.84 row menu + v4.85 header.
    // The library footer already names a single next file
    // ("နောက်: …") but it is text only: no tap to play, no queue
    // menu, no count, and a hand queue reads only as ◈ marks
    // scattered through the shelf. Now up to 3 following talks
    // sit between the list and the strip, in true play order:
    // the pruned hand queue first (queue order, like Next's
    // queued-head-first rule, v4.57), else shelf order after the
    // playing file with wrap (shownDl first, entries fallback —
    // exactly Next's list pick, so the preview can never promise
    // a different next talk than the ▶ button plays). Hidden
    // for Room sessions (the Room itself owns that queue's
    // preview), while shuffle is on with no hand queue (the
    // order is unknowable — the Room's v4.83 rule), and whenever
    // nothing follows. Rows mirror the Room preview's marks
    // (★, ◈N) and gestures (tap plays like a row tap, with the
    // same queue-ahead trim + saved-spot resume; long-press
    // opens that file's own ⋮ queue menu via its display
    // position, so a filtered-out file simply gets no menu
    // instead of a wrong one). The gold header counts the whole
    // upcoming set and opens the queue-only view for a hand
    // queue, or glides the list to the next file in list mode.
    // The queue is pruned in memory only — the 1s tick must
    // never write prefs — and a signature owns rebuilds, so
    // steady rows are never rewritten on the tick.
    private String libQueuePreviewSig = null;
    // v4.90: the library row's now-playing path, set by the queue
    // preview refresh below (same tick, same gate) so getView
    // never reads prefs per row (the v2.03 cost class).
    private String libRowPlayingPath = null;
    private void refreshLibQueuePreview() {
        android.widget.LinearLayout box = findViewById(R.id.dlQueuePreview);
        libRowPlayingPath = null;
        if (box == null) return;
        java.util.ArrayList<Entry> rows = new java.util.ArrayList<Entry>();
        java.util.ArrayList<Integer> qMarks = new java.util.ArrayList<Integer>();
        java.util.ArrayList<Integer> shownPoss = new java.util.ArrayList<Integer>();
        int upcoming = 0;
        boolean hasHandQueue = false;
        // v10.43: bound-trackless stop — liveSession stays true but the mirror is false, so the service-death fallback never marks a stopped file as playing (bound path was already null-safe via getCurrentUrl).
        if (dlListLoaded && AudioService.liveSession && AudioService.liveHasTrack) {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if ("lib".equals(sp.getString("lastKind", ""))) {
                String cur = service != null ? service.getCurrentUrl()
                        : sp.getString("lastLibPath", "");
                if (cur != null && cur.length() > 0) {
                    // v4.90: the sounding file for the row mark —
                    // same gate as this preview (live lib session),
                    // so a streamed talk can never match a shelf
                    // path and a dead session clears the mark.
                    libRowPlayingPath = cur;
                    java.util.Set<String> live = new java.util.HashSet<String>();
                    for (Entry e : entries) live.add(e.file.getAbsolutePath());
                    java.util.ArrayList<String> q = new java.util.ArrayList<String>();
                    String saved = Prefs.savedQueueLib(DownloadedActivity.this);
                    if (saved != null) {
                        for (String u : saved.split("\n")) {
                            if (u.length() == 0 || q.contains(u)) continue;
                            if (live.contains(u) && !u.equals(cur)) q.add(u);
                        }
                    }
                    // v11.17: redo rows are computed before the pick
                    // so the chain below can outrank list order (and
                    // shuffle) while still losing to the hand queue.
                    java.util.ArrayList<Entry> redoRows17 = libRedoPreviewEntries17(cur);
                    if (!q.isEmpty()) {
                        hasHandQueue = true;
                        upcoming = q.size();
                        for (int qi = 0; qi < q.size() && rows.size() < 3; qi++) {
                            String p = q.get(qi);
                            for (Entry e : entries) {
                                if (e.file.getAbsolutePath().equals(p)) {
                                    rows.add(e);
                                    qMarks.add(qi + 1);
                                    int spos = -1;
                                    if (libQueueOnly) spos = qi;
                                    else for (int si = 0; si < shownDl.size(); si++)
                                        if (shownDl.get(si).file.getAbsolutePath().equals(p)) { spos = si; break; }
                                    shownPoss.add(spos);
                                    break;
                                }
                            }
                        }
                    } else if (!redoRows17.isEmpty()) {
                        // v11.17: redo outranks list order and shuffle
                        // (Room's forward-before-shuffle rule), so the
                        // rows name exactly what the ▶ button plays;
                        // upcoming counts the whole redo chain, like
                        // the hand queue counts its whole queue above.
                        upcoming = redoRows17.size();
                        for (int ri = 0; ri < redoRows17.size() && rows.size() < 3; ri++) {
                            Entry e = redoRows17.get(ri);
                            rows.add(e);
                            qMarks.add(0);
                            int spos = -1;
                            // v12.89: the long-press position is a
                            // DISPLAY position (sim1310 B) — the queue
                            // index in queue-only view, -1 when the
                            // row is not queued, mirroring the hand
                            // branch above.
                            if (libQueueOnly) spos = libQueueCache.indexOf(e.file.getAbsolutePath());
                            else for (int si = 0; si < shownDl.size(); si++)
                                if (shownDl.get(si).file.getAbsolutePath().equals(e.file.getAbsolutePath())) { spos = si; break; }
                            shownPoss.add(spos);
                        }
                    // v12.24: same effective-shuffle fix as the footer Up Next line
                    // below — this branch read only the session-only dlShuffle, so a
                    // GLOBAL shuffle (Room button / Settings, Prefs.shuffleOn) left
                    // the preview showing deterministic list-next rows (and an
                    // "upcoming N" count) while Next and auto-advance played random
                    // (v7.33 poll / v10.90 Next: either flag shuffles). A live queued
                    // head or a redo still outranks shuffle above, exactly as today.
                    } else if (!(dlShuffle || Prefs.shuffleOn(DownloadedActivity.this))) {
                        java.util.List<Entry> list = shownDl;
                        int idx = -1;
                        for (int i = 0; i < list.size(); i++)
                            if (list.get(i).file.getAbsolutePath().equals(cur)) { idx = i; break; }
                        if (idx < 0) {
                            list = entries;
                            for (int i = 0; i < list.size(); i++)
                                if (list.get(i).file.getAbsolutePath().equals(cur)) { idx = i; break; }
                        }
                        if (idx >= 0 && list.size() > 1) {
                            upcoming = list.size() - 1;
                            for (int step = 1; step < list.size() && rows.size() < 3; step++) {
                                Entry e = list.get((idx + step) % list.size());
                                if (e.file.getAbsolutePath().equals(cur)) continue;
                                rows.add(e);
                                qMarks.add(0);
                                int spos = -1;
                                // v12.89: display position, as in the
                                // redo branch above (sim1310 B).
                                if (libQueueOnly) spos = libQueueCache.indexOf(e.file.getAbsolutePath());
                                else for (int si = 0; si < shownDl.size(); si++)
                                    if (shownDl.get(si).file.getAbsolutePath().equals(e.file.getAbsolutePath())) { spos = si; break; }
                                shownPoss.add(spos);
                            }
                        }
                    }
                }
            }
        }
        // v11.09: a Room session playing a downloaded file marks
        // its shelf row too — the v11.07 chooser mirror. The mark
        // above only covers lib sessions (lastKind lib), so a
        // talk sounding from its Room while the shelf sat open
        // read as an ordinary row here even though the strip
        // named it and the row's own live clock (v3.93) already
        // followed it by path. The Room plays the offline file
        // when one exists, so the service URL is the shelf path
        // itself: an exact path match against entries, never a
        // stream URL (which can never equal a path) and never a
        // dlMap guess, so no dual-claim ambiguity exists on this
        // side. Preview rows/upcoming stay lib-gated above —
        // only the row mark extends, and the 1s tick's
        // track-change rebind moves it at once on advance or
        // stop. A paused Room session keeps the mark, like a
        // paused lib session; a stopped/dead session clears it
        // through the liveHasTrack guard, like the lib path.
        if (libRowPlayingPath == null && dlListLoaded
                && AudioService.liveSession && AudioService.liveHasTrack
                && service != null) {
            String svcCur = service.getCurrentUrl();
            if (svcCur != null && svcCur.startsWith("/")) {
                for (Entry e : entries) {
                    if (e.file.getAbsolutePath().equals(svcCur)) {
                        libRowPlayingPath = svcCur;
                        break;
                    }
                }
            }
        }
        String headerText = Lang.t(DownloadedActivity.this, "dl_queue_head_prefix") + upcoming + Lang.t(DownloadedActivity.this, "dl_queue_head_suffix");
        StringBuilder sig = new StringBuilder();
        java.util.ArrayList<String> texts = new java.util.ArrayList<String>();
        // v5.48: preview rows name their sayadaw like the shelf rows
        // since v5.47 — the preview mixes talks from many sayadaws,
        // so a title-only row read anonymously when two sayadaws
        // share a talk title. The recorded name comes from the
        // per-load libSayadawMap (no JSON per row, the v1.57/v2.03
        // cost class); a file with no record keeps today's row
        // exactly, never a guessed name. The signature below
        // already covers the row text, so a name appearing after
        // a fresh loadList rebuilds the preview on the next tick.
        // v5.50: preview rows gain the ✓ finished mark the shelf
        // rows carry since v4.24 — an upcoming file already heard
        // to the end read exactly like an unheard one here, even
        // though the shelf below it carried ✓. From the per-load
        // libListenedFiles cache (the shelf's own check, no new
        // read), placed ★ ✓ ◈ in the shelf's order ahead of the
        // sayadaw name, so a finished upcoming file gains ✓ at
        // exactly the same moment its shelf row does. Preview rows
        // never carry the playing file (hand queue filters it,
        // list mode skips it), so no playing exclusion is needed,
        // and the signature below already covers the row text.
        for (int k = 0; k < rows.size(); k++) {
            Entry e = rows.get(k);
            String sd = libSayadawMap.get(e.file.getName());
            // v5.60: preview rows gain the ↺ resume mark the shelf
            // rows carry since v5.35 — an upcoming half-heard file
            // read exactly like a fresh one here, even though its
            // preview tap resumes from the saved spot (the click
            // handler below plays with savedPosForFile) and its
            // shelf row below says "· mm:ss မှ ဆက်". From the
            // per-load libResumeMap (the shelf's own cache, no
            // new read, same >5s rule by construction), placed
            // after the title like the Talks/Room title marks.
            // Preview rows never carry the playing file, so no
            // playing exclusion is needed, and the signature
            // below already covers the row text, so a spot
            // appearing after a fresh load rebuilds the preview
            // on the next tick.
            Integer rp = libResumeMap.get(e.file.getAbsolutePath());
            String resumeMark = rp != null ? " ↺ " + fmtDlTime(rp) : "";
            String row = "▸ " + (libFavFiles.contains(e.file.getName()) ? "★ " : "")
                    + (libListenedFiles.contains(e.file.getName()) ? "✓ " : "")
                    + (qMarks.get(k) > 0 ? "◈" + qMarks.get(k) + " " : "")
                    + (sd != null && sd.length() > 0 ? sd + " · " : "") + e.title + resumeMark;
            texts.add(row);
            sig.append(e.file.getAbsolutePath()).append('|').append(row).append('\n');
        }
        sig.append("H:").append(headerText).append('\n');
        String sigStr = sig.toString();
        if (sigStr.equals(libQueuePreviewSig == null ? "" : libQueuePreviewSig)
                && box.getChildCount() == rows.size() + 1) {
            int wantVis = rows.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE;
            if (box.getVisibility() != wantVis) box.setVisibility(wantVis);
            return;
        }
        libQueuePreviewSig = sigStr;
        box.removeAllViews();
        if (rows.isEmpty()) {
            if (box.getVisibility() != android.view.View.GONE) box.setVisibility(android.view.View.GONE);
            return;
        }
        if (box.getVisibility() != android.view.View.VISIBLE) box.setVisibility(android.view.View.VISIBLE);
        float dens = getResources().getDisplayMetrics().density;
        int pad = (int) (10 * dens + 0.5f);
        int gap = (int) (4 * dens + 0.5f);
        int f = Prefs.fontSize(this);
        float sz = f == 0 ? 13 : (f == 1 ? 15 : 17);
        boolean night = cachedDlNight;
        int rowText = night ? android.graphics.Color.WHITE
                : android.graphics.Color.parseColor("#3B2314");
        final boolean handQueue = hasHandQueue;
        android.widget.TextView header = new android.widget.TextView(this);
        header.setText(headerText);
        header.setTextColor(android.graphics.Color.parseColor("#C9A227"));
        header.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sz);
        header.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.setMaxLines(1);
        header.setPadding(pad, 0, pad, pad);
        header.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        header.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                Fx.tap(DownloadedActivity.this);
                if (handQueue) openLibQueueView();
                else if (!rows.isEmpty()) glideToDlPath(rows.get(0).file.getAbsolutePath());
            }
        });
        Fx.press(header);
        box.addView(header);
        for (int k = 0; k < rows.size(); k++) {
            final Entry e = rows.get(k);
            final int shownPos = shownPoss.get(k);
            android.widget.TextView row = new android.widget.TextView(this);
            row.setText(texts.get(k));
            row.setTextColor(rowText);
            row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sz);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setMaxLines(1);
            row.setBackgroundResource(night ? R.drawable.glass_btn_dark : R.drawable.glass_btn);
            row.setPadding(pad, pad, pad, pad);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            if (k > 0) lp.topMargin = gap;
            row.setLayoutParams(lp);
            row.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    Fx.tap(DownloadedActivity.this);
                    Fx.morph(v);
                    String path = e.file.getAbsolutePath();
                    // v12.34: same deleted-after-scan guard as the
                    // shelf row tap (v11.65) — a file deleted after
                    // the scan (Files app, cleanup) used to reach
                    // service.play here with the generic "check
                    // internet" toast, leave the dead file as the
                    // last-library anchor, and trim the queue ahead
                    // of a file that would never play. Bound taps
                    // only, like v11.65: the pre-bind queue keeps
                    // its own flush check (v1.92). Toast the
                    // accurate line and rescan so the stale row
                    // disappears.
                    if (service != null && !e.file.exists()) {
                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "dl_file_gone"), Toast.LENGTH_SHORT).show();
                        loadList();
                        return;
                    }
                    if (service == null || !path.equals(service.getCurrentUrl()))
                        trimLibQueueAhead(path);
                    if (service == null) {
                        pendingDlPath = path;
                        pendingDlTitle = e.title;
                        pendingDlRestart = false;
                        return;
                    }
                    if (path.equals(service.getCurrentUrl())) {
                        service.toggle();
                        saveLastLibrary(path, e.title);
                    } else {
                        saveLastLibrary(path, e.title);
                        clearLibTrail13(); // v8.13
                        service.play(path, e.title, savedPosForFile(path));
                    }
                    if (adapter != null) adapter.notifyDataSetChanged();
                    glideToDlPath(path);
                }
            });
            if (shownPos >= 0) {
                row.setOnLongClickListener(new android.view.View.OnLongClickListener() {
                    @Override public boolean onLongClick(android.view.View v) {
                        showLibQueueMenu(shownPos);
                        return true;
                    }
                });
            }
            Fx.press(row);
            box.addView(row);
        }
    }

    private void wireDlStrip() {
        android.view.View nowStrip = findViewById(R.id.nowStrip);
        if (nowStrip != null) {
            Fx.press(nowStrip);
            // v4.73: the strip's progress bar seeks (see Fx.miniSeekBar).
            Fx.miniSeekBar(DownloadedActivity.this, nowStrip);
            // v5.33: tap the strip's sub line (sayadaw + clock) flips
            // the shared clock mode elapsed/total <-> remaining and
            // re-syncs at once (the 1s tick would catch it anyway).
            // The sub view consumes the tap, so it no longer opens
            // the player — the title line still does. Its long-press
            // forwards to the strip, so stop/dismiss is unchanged.
            // Resume strips show no clock (they return before the
            // live branch), so a flip there is invisible until the
            // next live session, which then shows the chosen mode.
            android.widget.TextView stripSub = findViewById(R.id.nowStripSub);
            if (stripSub != null) {
                stripSub.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Prefs.setStripClockRemaining(DownloadedActivity.this, !Prefs.stripClockRemaining(DownloadedActivity.this));
                        Fx.tap(DownloadedActivity.this);
                        syncDlStrip();
                    }
                });
                stripSub.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) { return nowStrip.performLongClick(); }
                });
            }
            nowStrip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        String lp = sp.getString("lastLibPath", "");
                        if (lp.length() > 0 && new java.io.File(lp).isFile()) {
                            Fx.tap(DownloadedActivity.this);
                            Fx.morph(v);
                            // v4.63: in dead-library resume mode
                            // the tap plays the file from its saved
                            // spot (same path the Main/Talks strips
                            // take through autoPlayPath) instead of
                            // merely gliding to its row — the live
                            // session below still only glides,
                            // because that track is already playing.
                            // v12.58: live recheck, not just the cached flag — dlResumeStripMode
                            // refreshes on the 1s tick, so a session dying <1s before this tap left
                            // the flag false and the tap merely glided to the row without auto-playing
                            // the saved spot (one extra tap to resume). The strip's own branch test
                            // is !liveSession || !liveHasTrack, so re-checking the same statics here
                            // can only route a genuinely dead session to resume, never a live one.
                            // (Talks v12.57 fixed the same site; Settings/Downloaded mirrors are the
                            // cross-screen sweep that run named.)
                            if (dlResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                                autoPlayPath = lp;
                                autoPlayDone = false;
                                jumpPath = lp;
                                jumpDone = false;
                                hideKeyboard(v);
                                applyJumpOnce();
                                applyAutoPlayOnce();
                                return;
                            }
                            hideKeyboard(v);
                            glideToDlPath(lp);
                            return;
                        }
                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "no_file_playing"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "choose_first"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(DownloadedActivity.this, RoomActivity.class);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.putExtra("autoUrl", sp.getString("lastUrl", ""));
                    i.putExtra("autoTitle", sp.getString("lastTitle", ""));
                    Fx.tap(DownloadedActivity.this);
                    Fx.morph(v);
                    Fx.go(DownloadedActivity.this, i);
                }
            });
            nowStrip.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    // v4.62: in resume mode there is no session to
                    // stop — a long-press instead dismisses the
                    // saved spot (lastPos only; the talk itself is
                    // untouched, so reopening it starts from the
                    // beginning) and the next sync hides the strip.
                    // v12.58: same live recheck as the transport buttons (v11.89) — the cached
                    // flag can lag a just-died session by up to one 1s tick, and a stale-false
                    // flag here ACTION_STOPs a trackless service (phantom "talk_stopped" toast,
                    // saved spot NOT dismissed) instead of dismissing it. (Talks v12.57 fixed
                    // the same site; Settings/Downloaded mirrors are the cross-screen sweep.)
                    if (dlResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        // v5.34: same as Main's dismiss — the
                        // per-talk spot goes with the global one.
                        String dismissUrl = getSharedPreferences("dhamma", MODE_PRIVATE)
                                .getString("lastPosUrl", "");
                        getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                                .putInt("lastPos", 0).putString("lastPosUrl", "").apply();
                        if (dismissUrl.length() > 0) Prefs.talkPosRemoveBoth(DownloadedActivity.this, dismissUrl);
                        Fx.tap(DownloadedActivity.this);
                        Fx.morph(v);
                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "hist_cleared"), Toast.LENGTH_SHORT).show();
                        syncDlStrip();
                        return true;
                    }
                    Intent svc = new Intent(DownloadedActivity.this, AudioService.class);
                    svc.setAction(AudioService.ACTION_STOP);
                    startService(svc);
                    Fx.tap(DownloadedActivity.this);
                    Fx.morph(v);
                    Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "talk_stopped"), Toast.LENGTH_SHORT).show();
                    return true;
                }
            });
        }
        android.view.View nowToggle = findViewById(R.id.nowStripToggle);
        if (nowToggle != null) {
            nowToggle.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // v4.62: in resume mode there is no live track
                    // to toggle — the ▶ button re-opens the Room
                    // like the strip tap, which resumes from the
                    // saved spot.
                    // v11.89: same live recheck as Settings v11.88 — the cached flag can lag
                    // a just-died session by up to one 1s tick, and a stale-false flag here
                    // ACTION_TOGGLEd a trackless service (dead tap, tap twice to resume).
                    if (dlResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    Intent svc = new Intent(DownloadedActivity.this, AudioService.class);
                    svc.setAction(AudioService.ACTION_TOGGLE);
                    startService(svc);
                    Fx.tap(DownloadedActivity.this);
                    Fx.morph(v);
                    hideKeyboard(v);
                }
            });
            nowToggle.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    // v4.62: resume mode has no session to stop —
                    // hand the long-press to the strip, which
                    // dismisses the saved spot instead of toasting
                    // a phantom stop.
                    // v12.58: same live recheck as the Toggle click (v11.89) — v11.89 fixed
                    // the click but missed this long-press, while Settings v11.90 fixed both;
                    // a stale-false flag here ACTION_STOPs a trackless service instead of
                    // delegating to the strip dismiss (which the DB fix above makes live-aware).
                    if (dlResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) return strip2.performLongClick();
                        return true;
                    }
                    Intent svc = new Intent(DownloadedActivity.this, AudioService.class);
                    svc.setAction(AudioService.ACTION_STOP);
                    startService(svc);
                    Fx.tap(DownloadedActivity.this);
                    Fx.morph(v);
                    Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "talk_stopped"), Toast.LENGTH_SHORT).show();
                    return true;
                }
            });
        }
        android.view.View nowNext = findViewById(R.id.nowStripNext);
        if (nowNext != null) {
            Fx.press(nowNext);
            nowNext.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // v7.35: same resume-mode guard as Main/Talks v7.34 — Toggle and
                    // Forward already resume the ↺ talk here, but Next had no guard:
                    // a tick-window ⏭ tap ACTION_NEXT-ed into a trackless Room (or
                    // a library ACTION_NEXT with no live file) instead of resuming
                    // the saved talk like every other strip transport button.
                    // v11.89: live recheck as well (Settings v11.88) — the cached flag
                    // can lag a just-died session by up to one 1s tick, and a stale-false
                    // flag here played file #1 (or opened a trackless Room) instead of
                    // resuming the ↺ talk.
                    if (dlResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    Fx.tap(DownloadedActivity.this);
                    Fx.morph(v);
                    hideKeyboard(v);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        Intent li = new Intent(DownloadedActivity.this, DownloadedActivity.class);
                        li.setAction(ACTION_NEXT);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        Fx.go(DownloadedActivity.this, li);
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "choose_first"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(DownloadedActivity.this, RoomActivity.class);
                    i.setAction(RoomActivity.ACTION_NEXT);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.go(DownloadedActivity.this, i);
                }
            });
        }

        android.view.View nowPrev = findViewById(R.id.nowStripPrev);
        if (nowPrev != null) {
            Fx.press(nowPrev);
            nowPrev.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // v7.35: belt-and-braces for the tick window between a session
                    // dying and the next sync hiding this button (Main v7.31) — a
                    // resume-mode ⏮ tap resumes the ↺ talk like ▶/⏩ do, instead
                    // of ACTION_PREV-ing into a different talk.
                    // v11.89: live recheck as well (Settings v11.88) — the cached flag
                    // can lag a just-died session by up to one 1s tick, and a stale-false
                    // flag here played the LAST file (or opened a trackless Room).
                    if (dlResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    Fx.tap(DownloadedActivity.this);
                    Fx.morph(v);
                    hideKeyboard(v);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        Intent li = new Intent(DownloadedActivity.this, DownloadedActivity.class);
                        li.setAction(ACTION_PREV);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        Fx.go(DownloadedActivity.this, li);
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "choose_first"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(DownloadedActivity.this, RoomActivity.class);
                    i.setAction(RoomActivity.ACTION_PREV);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.go(DownloadedActivity.this, i);
                }
            });
        }
    }

    private Handler refreshHandler = new Handler();
    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            // v7.45: rebind the shelf rows when a row mark changes
            // under the library — the Room drawer's v7.43 gap and
            // the chooser's v7.44 gap, restated here (see the
            // dlMarkFav45/dlMarkPlayed45 field note). First tick
            // adopts the sets silently; a later difference
            // rebuilds the filename cache and rebinds once.
            // The tick's own end block writes playedTalks and
            // already adds the file to libListenedFiles, so its
            // change surfaces here at most one tick later as a
            // harmless second rebind, never a missed mark.
            try {
                if (entries != null && adapter != null) {
                    android.content.SharedPreferences pp45 =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    java.util.Set<String> fav45 = pp45.getStringSet("favTalks", null);
                    java.util.Set<String> played45 = pp45.getStringSet("playedTalks", null);
                    java.util.Set<String> favCopy45 = fav45 == null
                            ? new java.util.HashSet<String>()
                            : new java.util.HashSet<String>(fav45);
                    java.util.Set<String> playedCopy45 = played45 == null
                            ? new java.util.HashSet<String>()
                            : new java.util.HashSet<String>(played45);
                    if (dlMarkFav45 == null || dlMarkPlayed45 == null) {
                        dlMarkFav45 = favCopy45;
                        dlMarkPlayed45 = playedCopy45;
                    } else if (!favCopy45.equals(dlMarkFav45)
                            || !playedCopy45.equals(dlMarkPlayed45)) {
                        dlMarkFav45 = favCopy45;
                        dlMarkPlayed45 = playedCopy45;
                        refreshLibMarkCache();
                        // v7.56: filter membership follows the same
                        // external mark change — the v7.45 rebind
                        // above fixed the row marks but never
                        // re-ran the filter, so a starred-only
                        // shelf kept an unfavourited file and a
                        // new-only shelf kept a finished one (and
                        // a landed download stayed out of the
                        // starred view) until the next load, sort
                        // or search. The Talks chooser closed this
                        // same gap in v7.55; here, only while a
                        // filter view is active (mode 0 membership
                        // is unchanged by a mark), re-run
                        // applyDlFilter on the freshly rebuilt
                        // mark caches instead of a bare notify —
                        // it notifies itself, keeps the current
                        // search narrowing, shows the existing
                        // empty word when the view empties, and
                        // never re-pours (no pour flag is armed
                        // on this path) or jumps scroll (no jump
                        // flag set). The mode itself is kept, so
                        // the filter button and hint stay true.
                        if (dlFilterMode != 0) {
                            try { applyDlFilter(); }
                            catch (Exception ignored2) { adapter.notifyDataSetChanged(); }
                        } else {
                            adapter.notifyDataSetChanged();
                        }
                        // v7.50: the footer reads the same caches
                        // (libFavFiles/libListenedFiles/libResumeMap)
                        // the rows just rebound from, but nothing
                        // refreshed it on this path — its star /
                        // finished / half-heard counts and its Up
                        // Next marks kept the old values until the
                        // next load, return, track change or
                        // download-progress tick. Refresh it here,
                        // change-only like the rebind, so steady
                        // ticks still never touch it; updateTotal
                        // owns its own fade guards, as the
                        // track-change and progress callers do.
                        try { updateTotal(); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            syncDlStrip(); // v4.40: mini-player rides the existing 1s tick (no new tick)
            refreshLibQueuePreview(); // v4.86: queue preview rides the same tick
            // v1.40: offline continuous play — when a downloaded file
            // ends, move to the next file in the shown list.
            // v1.55: end is the service's real completion flag, not
            // position-near-duration — the old guess admitted a false
            // trigger: pausing a file in its last second jumped to
            // the next file. A manual pause now stays put.
            if (service != null && service.hasTrack()) {
                boolean playing = service.isPlaying();
                // v1.85: while anything plays, its end is not handled
                // yet — the same clear-while-playing rule the Room's
                // poll uses, so replaying a file whose earlier end
                // was consumed still advances when it ends again.
                if (playing) dlEndedHandledFor = null;
                // v1.84: a file ending while the library sat in the
                // back stack used to be missed forever — the 1s
                // refresh stops onPause, so the dlWasPlaying edge
                // (true only on the tick right after playing) was
                // already false on return, and the real completion
                // flag sat unconsumed. The flag alone is the trigger
                // now (a manual pause never sets it, a stop clears
                // it, and play() resets it, so advancing once is
                // exact); the Room's poll already worked this way.
                // v1.85: but the flag alone is NOT once-only here —
                // the Room guards it with endedHandledFor (v1.69)
                // and the library never did, so a stale end
                // re-fired when its file re-entered the shown list.
                if (!playing && service.hasTrackEnded()) {
                    String cur = service.getCurrentUrl();
                    if (cur != null && !cur.equals(dlEndedHandledFor)) {
                        // v7.75: a Room session's end is never the
                        // library's to handle — the service either
                        // advances it itself or defers it to the
                        // Room poll (sleep-end-of-talk, shuffle,
                        // data-gate, autoplay-off, repeat-off at
                        // last), and this poll used to fire on ANY
                        // ended track while the shelf sat
                        // foreground: a Room talk ending here
                        // consumed the Room's sleep one-shot (so
                        // the Room later advanced instead of
                        // stopping), and a Room-downloaded file
                        // ending here could even be replayed and
                        // re-anchored as a library session under
                        // the shared repeat-one pref, or eat a
                        // waiting library queue head. Every
                        // library play writes lastKind="lib"
                        // (saveLastLibrary) and every Room play
                        // writes "room" (saveLastTalk), so the
                        // anchor separates the sessions exactly.
                        // A foreign end is marked handled here
                        // (never re-fired) and left for its own
                        // screen; the played ✓ below stays
                        // unguarded — the service already marks
                        // every natural end, and the set-add is
                        // idempotent on the same key.
                        boolean foreignEnd75 = !"lib".equals(
                                getSharedPreferences("dhamma", MODE_PRIVATE)
                                        .getString("lastKind", ""));
                        if (foreignEnd75) dlEndedHandledFor = cur;
                        // v4.29: a file listened to the end here
                        // never earned its ✓ — playedTalks was only
                        // ever written by the Room's end block,
                        // keyed by stream URL, so offline-only
                        // listening stayed unmarked on this shelf
                        // and in the Room drawer (same key). Reverse
                        // the dlMap (filename -> URL, the strip's
                        // own lookup) and mark it once per end,
                        // inside this guard; the cache gains the
                        // file name at once, so the track-change
                        // rebind shows ✓ without a reload. A file
                        // with no dlMap entry simply has no URL.
                        // v8.42: exactly-one match, never the first
                        // match — the v8.41 strip rule, completing
                        // the played-mark side it deferred. The
                        // service mark above is fixed the same way,
                        // so an ambiguous file cannot regain a
                        // wrong ✓ through this block either; it
                        // simply earns no ✓ (and no cache entry)
                        // until its claim is unambiguous again.
                        try {
                            String fn = new java.io.File(cur).getName();
                            org.json.JSONObject map = new org.json.JSONObject(
                                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlMap", "{}"));
                            java.util.Iterator<String> keys = map.keys();
                            String hit = null;
                            int matches = 0;
                            while (keys.hasNext()) {
                                String u = keys.next();
                                if (fn.equals(map.optString(u, ""))) { hit = u; matches++; }
                            }
                            if (matches == 1) {
                                android.content.SharedPreferences pp =
                                        getSharedPreferences("dhamma", MODE_PRIVATE);
                                java.util.Set<String> played = new java.util.HashSet<String>(
                                        pp.getStringSet("playedTalks", new java.util.HashSet<String>()));
                                if (played.add(hit)) {
                                    pp.edit().putStringSet("playedTalks", played).apply();
                                    libListenedFiles.add(fn);
                                }
                            }
                        } catch (Exception ignored) {}
                        boolean advanced = false;
                        boolean sleepStopped = false;
                        // v4.82: one-shot "stop at end of talk" — the
                        // file keeps its ✓ above, the flag is
                        // consumed once, and nothing advances; the
                        // stop toast below is suppressed with it.
                        if (service != null && service.isSleepEndOfTalk() && !foreignEnd75) {
                            service.consumeSleepEndOfTalk();
                            dlEndedHandledFor = cur;
                            Toast.makeText(DownloadedActivity.this,
                                    Lang.t(DownloadedActivity.this, "sleep_end_stopped"),
                                    Toast.LENGTH_LONG).show();
                            sleepStopped = true;
                        }
                        // v11.77: a bedtime stop is pending (minute timer fired mid-fade and this
                        // file ended before the fade-end pause) — nothing advances or replays; the
                        // end stays handled, like the one-shot stop above.
                        boolean sleepStopPending77 = service != null && service.isSleepStopPending() && !foreignEnd75;
                        // v7.33: repeat-one for the library — the Room's
                        // v4.35 rule, mirrored here. The library strip's
                        // repeat button (v5.27) writes Prefs.repeatMode
                        // and its glyph reads it, but this poll never
                        // read the pref at all: with repeat-one on, a
                        // downloaded file ending advanced to the next
                        // file instead of replaying, so the strip lied.
                        // Repeat-one now outranks autoplay and the hand
                        // queue exactly like the Room (sleep-end-of-talk
                        // above still outranks it), restarting this file
                        // from 0 via the 2-arg play. Guarded to a real
                        // shelf file: a Room stream ending while this
                        // screen is foreground must never be re-recorded
                        // as a library session by saveLastLibrary.
                        if (!sleepStopped && !sleepStopPending77 && !foreignEnd75 && Prefs.repeatMode(DownloadedActivity.this) == 2) {
                            Entry rep = null;
                            for (Entry e : entries)
                                if (e.file.getAbsolutePath().equals(cur)) { rep = e; break; }
                            // v11.66: skip-dead-files — rep comes from entries (the last scan), so
                            // a file deleted after it must not be replayed dead: the old code
                            // anchored it and died on the generic "check internet" toast. Say so
                            // once and fall through to the autoplay / list logic below (which now
                            // skips dead picks itself); autoplay-off still ends in the consume
                            // block's finished toast, which is true — the file did finish.
                            if (rep != null && rep.file.exists()) {
                                saveLastLibrary(rep.file.getAbsolutePath(), rep.title);
                                service.play(rep.file.getAbsolutePath(), rep.title);
                                dlEndedHandledFor = cur;
                                // v8.69: the repeat-one replay toast names its sayadaw — the
                                // library mixes downloads from many sayadaws, so a title-only
                                // announcement read anonymously when two sayadaws share a talk
                                // title, the same gap class the v8.67 auto-advance toasts closed
                                // in this very poll block. Same per-load libSayadawMap, same
                                // title · sayadaw order; an unrecorded or v8.43-ambiguous file
                                // keeps today's toast byte-identical — never a guessed name.
                                String repSd69 = libSayadawMap.get(rep.file.getName());
                                String repSdSuffix69 = (repSd69 != null && repSd69.length() > 0)
                                        ? " · " + repSd69 : "";
                                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "replay_prefix") + (libFavFiles.contains(rep.file.getName()) ? "★ " : "") + rep.title + repSdSuffix69, Toast.LENGTH_SHORT).show();
                                advanced = true;
                            } else if (rep != null) {
                                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "dl_file_gone"), Toast.LENGTH_SHORT).show();
                            }
                        }
                        if (!sleepStopped && !sleepStopPending77 && !advanced && !foreignEnd75 && Prefs.autoplay(DownloadedActivity.this)) {
                            // v4.57: the hand queue outranks
                            // shuffle + list order on natural end
                            // too — the Room's queued-head-first
                            // advance (v4.32), mirrored here. A
                            // queued file plays with the same
                            // announcement + glide as the list
                            // advance below; autoplay-off still
                            // stops (this block is autoplay-only),
                            // and an empty queue falls through to
                            // the shuffle / list-next loop.
                            // v11.66: skip-dead-files — takeLibQueueHead matches against entries
                            // (the last scan), so a queued file deleted after it used to be played
                            // dead. Dead heads are already popped by the take, so keep taking
                            // until a live head or an empty queue; say so once if any were dropped.
                            Entry qn = null;
                            boolean droppedDeadQn66 = false;
                            while (true) {
                                Entry cand66 = takeLibQueueHead();
                                if (cand66 == null) break;
                                if (cand66.file.exists()) { qn = cand66; break; }
                                droppedDeadQn66 = true;
                            }
                            if (droppedDeadQn66)
                                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "dl_file_gone"), Toast.LENGTH_SHORT).show();
                            if (qn != null) {
                                saveLastLibrary(qn.file.getAbsolutePath(), qn.title);
                                // v12.20: resume the pick's saved spot
                                // (see the redo branch above).
                                service.play(qn.file.getAbsolutePath(), qn.title,
                                        savedPosForFile(qn.file.getAbsolutePath()));
                                noteLibTrail11(cur, qn.file.getAbsolutePath());
                                // v11.17: a queued advance is a branch — it
                                // invalidates the redo stack, like the
                                // manual queue-head Next above.
                                clearLibRedo17();
                                // v5.97: the queued-head auto-advance toast gains its ★ — the Room's
                                // auto-advance toast has carried ★ since v4.14, but this mirror toast
                                // named the just-started file bare, so a favourited file read exactly
                                // like any other. Same cache as the footer (v4.24) and rows (v1.93),
                                // so the toast agrees with both by construction; ◈ deliberately
                                // absent — the file just started playing, and a playing file never
                                // carries ◈ (v5.96 rule, same as the Room toast).
                                // v8.67: the queued-head auto-advance toast names its sayadaw — the
                                // library mixes downloads from many sayadaws, so a title-only
                                // announcement read anonymously when two sayadaws share a talk
                                // title, the same gap class the v8.66 footer line closed; the
                                // announcement's whole purpose (v4.17) is to tell a listener
                                // looking away what started playing. From the per-load
                                // libSayadawMap (same cache as the footer), placed after the
                                // title, matching the footer/Next-preview title · sayadaw
                                // order; an unrecorded or v8.43-ambiguous file keeps today's
                                // toast byte-identical — never a guessed name.
                                String qnSd67 = libSayadawMap.get(qn.file.getName());
                                String qnSdSuffix67 = (qnSd67 != null && qnSd67.length() > 0)
                                        ? " · " + qnSd67 : "";
                                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "next_prefix") + (libFavFiles.contains(qn.file.getName()) ? "★ " : "") + qn.title + qnSdSuffix67, Toast.LENGTH_SHORT).show();
                                // v11.73: queue-aware follow — glideToDlPath scrolls to the queue row in
                                // queue-only view (or no-ops when the played file has no queue row), instead
                                // of a shelf index into a queue-ordered list.
                                glideToDlPath(qn.file.getAbsolutePath());
                                advanced = true;
                            } else if (playLibRedoAdvance17(cur)) {
                                // v11.17: a file ending naturally after a
                                // back-step redoes to the file just left
                                // (Room parity: forward outranks shuffle
                                // and list-next, and outranks the
                                // repeat-off stop inside the loop below).
                                advanced = true;
                            } else
                            for (int i = 0; i < shownDl.size(); i++) {
                                if (shownDl.get(i).file.getAbsolutePath().equals(cur)) {
                                    // v1.81: the last file used to stop
                                    // dead — the Room's continuous play
                                    // has wrapped to the first talk since
                                    // v1.43, the library never did. Wrap
                                    // the same way (a one-file list replays).
                                    // v4.16: shuffle-continuous — with
                                    // dlShuffle on, auto-advance picks
                                    // a random shown file other than
                                    // the one that just ended (a
                                    // one-file list replays, exactly
                                    // like the ordered wrap below).
                                    // v7.33: repeat-off + strip shuffle for the
                                    // library — the Room's v4.35/v4.36 rules,
                                    // mirrored here. The strip shuffle button
                                    // (v5.27) toggles Prefs.shuffleOn, but this
                                    // loop only ever read the session-only
                                    // dlShuffle, so a strip-shuffle advance
                                    // walked list order; and repeat-off was
                                    // ignored entirely, so the last file
                                    // wrapped instead of stopping. Effective
                                    // shuffle is now either flag; repeat-off
                                    // stops at the last shown file (the
                                    // break leaves advanced false, so the
                                    // consume block below toasts "ပြီးပါပြီ"
                                    // exactly like autoplay-off), unless a
                                    // hand queue was already consumed above
                                    // or shuffle is on — the Room's two
                                    // exceptions, in the same order.
                                    boolean effShuffle33 = dlShuffle || Prefs.shuffleOn(DownloadedActivity.this);
                                    if (Prefs.repeatMode(DownloadedActivity.this) == 0 && !effShuffle33 && i == shownDl.size() - 1) break;
                                    // v11.66: skip-dead-files — shownDl is the last scan, so a file
                                    // deleted after it (split-screen cleanup, external delete) used
                                    // to be played dead here: generic "check internet" toast plus a
                                    // dead last-library anchor. Build the candidate order today's
                                    // single pick used (ordered: forward with repeat-all wrap, a
                                    // one-file list replays; shuffle: a random other file, shuffled
                                    // order so the first live pick keeps today's distribution) and
                                    // take the first file that still exists. If every candidate is
                                    // gone, say so once, mark the end handled, and stop — never
                                    // anchor or play dead, and never refire on the next tick.
                                    java.util.ArrayList<Integer> candIdx66 = new java.util.ArrayList<Integer>();
                                    if (effShuffle33 && shownDl.size() > 1) {
                                        for (int ci = 0; ci < shownDl.size(); ci++) if (ci != i) candIdx66.add(ci);
                                        java.util.Collections.shuffle(candIdx66);
                                    } else if (shownDl.size() == 1) {
                                        candIdx66.add(0);
                                    } else {
                                        for (int ci = i + 1; ci < shownDl.size(); ci++) candIdx66.add(ci);
                                        if (Prefs.repeatMode(DownloadedActivity.this) != 0)
                                            for (int ci = 0; ci < i; ci++) candIdx66.add(ci);
                                    }
                                    Entry next = null;
                                    int nextIdx = -1;
                                    boolean skippedDead66 = false;
                                    for (int ci : candIdx66) {
                                        if (shownDl.get(ci).file.exists()) { next = shownDl.get(ci); nextIdx = ci; break; }
                                        skippedDead66 = true;
                                    }
                                    if (next == null) {
                                        if (skippedDead66) {
                                            Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "dl_file_gone"), Toast.LENGTH_SHORT).show();
                                            dlEndedHandledFor = cur;
                                            advanced = true;
                                        }
                                        break;
                                    }
                                    // v1.93: an auto-advanced file is a
                                    // library session too — record it,
                                    // or Main's Mp3 Player nav points
                                    // at the previous session instead.
                                    saveLastLibrary(next.file.getAbsolutePath(), next.title);
                                    // v12.20: resume the pick's saved spot
                                    // (see the redo branch above).
                                    service.play(next.file.getAbsolutePath(), next.title,
                                            savedPosForFile(next.file.getAbsolutePath()));
                                    noteLibTrail11(cur, next.file.getAbsolutePath());
                                    // v11.17: a list/shuffle advance is a
                                    // branch — it invalidates any redo
                                    // stack the gate above declined.
                                    clearLibRedo17();
                                    // v4.17: auto-advance announces the
                                    // next file — the Room got this
                                    // announcement in v4.14, but a
                                    // library file ending used to
                                    // switch tracks with only the
                                    // row pulse + glide to say so, so
                                    // a listener looking away never
                                    // learned what started playing.
                                    // Same "နောက်: " text, shuffle
                                    // picks included (next is already
                                    // the picked file here).
                                    // v5.97: the list/shuffle auto-advance toast gains its ★, same mirror
                                    // gap as the queued-head toast above — the Room's toast (v4.14)
                                    // names a favourite, this one did not. Same libFavFiles cache;
                                    // ◈ deliberately absent (playing file, v5.96 rule).
                                    // v8.67: the list/shuffle auto-advance toast names its sayadaw —
                                    // same gap class as the queued-head toast above (v8.66 footer
                                    // series): a title-only announcement on a multi-sayadaw shelf
                                    // reads anonymously. Same per-load libSayadawMap, same
                                    // title · sayadaw order, same byte-identical fallback for
                                    // unrecorded/ambiguous files.
                                    String nxSd67 = libSayadawMap.get(next.file.getName());
                                    String nxSdSuffix67 = (nxSd67 != null && nxSd67.length() > 0)
                                            ? " · " + nxSd67 : "";
                                    Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "next_prefix") + (libFavFiles.contains(next.file.getName()) ? "★ " : "") + next.title + nxSdSuffix67, Toast.LENGTH_SHORT).show();
                                    // v2.63: the list glides to the new file — the Room
                                    // got this follow on every talk change (v2.32),
                                    // but a library auto-advance used to switch the
                                    // playing file with no scroll at all, so when the
                                    // next file sat off-screen nothing visibly
                                    // followed the music. smoothScrollToPosition
                                    // glides to the same row the Room glides to;
                                    // the rebind below still only fires on a real
                                    // track change, and a filtered-out file never
                                    // reaches this branch, so there is nothing to
                                    // glide to. Visual only.
                                    // v11.73: queue-aware follow (see the Next/Previous branches) —
                                    // glideToDlPath scrolls to the queue row in queue-only view.
                                    glideToDlPath(next.file.getAbsolutePath());
                                    advanced = true;
                                    break;
                                }
                            }
                        }
                        // No match (file filtered out of the shown
                        // list) or autoplay off: consume the end now
                        // instead of leaving it to fire later when
                        // the file happens to reappear in the list.
                        if (!advanced && !sleepStopped && !foreignEnd75) {
                            dlEndedHandledFor = cur;
                            // v12.39: the footer's ◈ count goes stale when this tick's queue take
                            // shrank the queue and nothing played — the v12.01 D1 prune (another
                            // window popped the head, split-screen) and the v11.66 dead-head drop
                            // (a queued file deleted after the last scan) both persist through
                            // saveLibQueue, which never rebinds the footer, and the tick only
                            // refreshes it on a track change (v4.66) — but a repeat-off last-file
                            // end plays nothing, so the footer kept naming the dropped heads
                            // ("· ◈ N") until the next load or track change. Rebind it here, at
                            // the exact no-advance point, the v4.66 convention (queue verbs with
                            // no play rebind the footer at once); one DownloadManager query per
                            // ended-without-advance talk, and a no-op value when the queue did
                            // not change.
                            try { updateTotal(); } catch (Exception ignored) {}
                            // v4.29: autoplay-off ends here stopped
                            // silently — the Room got its finished
                            // toast above and this screen's only end
                            // notice was the advance announcement
                            // (v4.17). Toast only when the ended
                            // file is in the shown list: a filtered-
                            // out file has no visible title to name
                            // (its path would lie), so it stays
                            // silent like before.
                            for (int i = 0; i < shownDl.size(); i++) {
                                if (shownDl.get(i).file.getAbsolutePath().equals(cur)) {
                                    // v8.69: the finished toast names its sayadaw too — same
                                    // poll block, same multi-sayadaw shelf, same gap class as
                                    // the replay toast above; the listener looking away learns
                                    // whose talk ended, not just its title. Same per-load
                                    // libSayadawMap, same title · sayadaw order, same
                                    // byte-identical fallback for unrecorded/ambiguous files.
                                    String finSd69 = libSayadawMap.get(shownDl.get(i).file.getName());
                                    String finSdSuffix69 = (finSd69 != null && finSd69.length() > 0)
                                            ? " · " + finSd69 : "";
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_finished_prefix") + shownDl.get(i).title + finSdSuffix69,
                                            Toast.LENGTH_SHORT).show();
                                    break;
                                }
                            }
                        }
                    }
                }
            } else if (service != null && !service.hasTrackEnded()) {
                // A new play resets the service flag, so a replayed
                // file ends handled again instead of inheriting the
                // previous end's consumed marker.
                // v1.86: but not while the service is merely unbound
                // yet (the first tick after a rotation) — clearing
                // there would wipe the just-restored marker before
                // the bind can confirm the flag is really gone.
                dlEndedHandledFor = null;
            }
            // v1.47: rebind only when the playing state or track changed
            // — the old every-second rebind flickered rows and fought
            // with scrolling while a file played.
            boolean curPlaying = service != null && service.hasTrack() && service.isPlaying();
            String curTrack = service != null ? service.getCurrentUrl() : null;
            boolean trackChanged = curTrack == null ? lastDlTrack != null : !curTrack.equals(lastDlTrack);
            // v1.87: the buffering-active state too (see the row icon
            // below) — a hold/un-hold tap while buffering flips the
            // icon without touching isPlaying() or the track.
            boolean curActive = service != null && service.hasTrack()
                    && (service.isPlaying()
                            || (service.isBuffering() && !service.isHoldPaused()));
            if (adapter != null && (curPlaying != lastDlPlaying || trackChanged
                    || curActive != lastDlActive)) {
                adapter.notifyDataSetChanged();
                lastDlPlaying = curPlaying;
                lastDlTrack = curTrack;
                lastDlActive = curActive;
            }
            // v4.66: a Next landing while the shelf sits open used
            // to leave the footer's Up Next line pointing at the
            // old track until the next load — the tick already
            // detects the track change above, so the footer
            // rebinds on that signal alone (one DownloadManager
            // query per track change, never per tick).
            if (trackChanged) {
                try { updateTotal(); } catch (Exception ignored) {}
            }
            // v3.93: library playing-row live position — the playing
            // row pulsed (v2.25) but never said where in the file
            // the listener was, so a long downloaded talk gave no
            // sense of progress without leaving for the Room. The
            // row's existing size+date subtitle now carries
            // "elapsed / total" while its file is the service track
            // (playing or paused — a paused row still shows where
            // it stopped). Updated IN PLACE on the visible row only,
            // never via notifyDataSetChanged, so the v1.47
            // no-flicker rule survives: no rebind, no scroll fight,
            // and a scrolled-off playing row is simply skipped.
            // Guards: duration 0 (buffering/unprepared) leaves the
            // plain subtitle; a filtered-out track has no row to
            // update; any state/track change rebinds through
            // getView above, which restores the plain subtitle, and
            // the next tick re-applies the clock to the new row —
            // so a stale clock can live at most one tick, and a
            // stopped session rebinds via the playing-state change.
            // No new view, no new button: the subtitle line is
            // reused, and the Room/Main/Talks screens are untouched.
            if (service != null && service.hasTrack() && dlListView != null) {
                String cur = service.getCurrentUrl();
                int dur = service.getDuration();
                if (cur != null && dur > 0) {
                    // v12.89: one display-position resolution for the
                    // live clock (sim1310 A) — the ListView shows the
                    // shelf in shelf view but libQueueCache order in
                    // queue-only view, so the shelf index is a display
                    // position only in shelf view. In queue-only view
                    // the sounding file's queue index is the row (no
                    // row when it is not queued — a queue-head play
                    // pops it), and the file need not be in shownDl
                    // at all (a search can filter it out while the
                    // queue view still shows it); the N/M comes from
                    // the same list as the position. A stale queue
                    // slot maps to null and writes nothing, like
                    // every other libEntryAtDisplay consumer.
                    int dispPos = -1;
                    int dispTotal = shownDl.size();
                    Entry tickEntry = null;
                    if (libQueueOnly) {
                        int qi = libQueueCache.indexOf(cur);
                        if (qi >= 0) {
                            dispPos = qi;
                            dispTotal = libQueueCache.size();
                            tickEntry = libEntryAtDisplay(qi);
                        }
                    } else {
                        for (int i = 0; i < shownDl.size(); i++) {
                            if (shownDl.get(i).file.getAbsolutePath().equals(cur)) {
                                dispPos = i;
                                tickEntry = shownDl.get(i);
                                break;
                            }
                        }
                    }
                    if (tickEntry != null) {
                            android.view.View row = dlListView.getChildAt(
                                    dispPos - dlListView.getFirstVisiblePosition());
                            if (row != null) {
                                android.widget.TextView sz =
                                        row.findViewById(R.id.dlSize);
                                if (sz != null) {
                                    double mb = tickEntry.len / 1048576.0;
                                    // v4.12: the playing subtitle also
                                    // carries the time left ("-mm:ss",
                                    // language-neutral like the
                                    // elapsed/total pair it extends),
                                    // so a long offline talk reads
                                    // its remaining length without
                                    // leaving the library for the
                                    // Room. Same in-place update, same
                                    // no-rebind rule as the v3.93 clock.
                                    // v4.15: the playing subtitle also
                                    // carries its queue position
                                    // ("N / M" in the shown list), so
                                    // continuous play reads where in
                                    // the queue the file sits without
                                    // counting rows. Same in-place
                                    // update, same no-rebind rule as
                                    // the v3.93 clock it extends.
                                    // v4.25: the playing subtitle also
                                    // carries the live sleep countdown
                                    // ("· 😴 mm:ss", "· 😴 ⏸ mm:ss"
                                    // suspended, "· 😴 ရပ်တော့မယ်"
                                    // fading) — the library is the
                                    // third playback surface after
                                    // the Room and Main, and its
                                    // listener otherwise had no
                                    // bedtime cue. Same in-place
                                    // 1s tick, same no-rebind rule;
                                    // a rebind restores the plain
                                    // subtitle and the next tick
                                    // re-adds the suffix, exactly
                                    // like the v3.93 clock it extends.
                                    // The suspended read carries the
                                    // Settings guard, so a remainder
                                    // orphaned by a turned-off setting
                                    // never shows as live.
                                    String sleepSuf = "";
                                    try {
                                        long lRem = service.getSleepRemainingMs();
                                        long lSusp = service.getSleepSuspendedMs();
                                        if (service.isSleepFading()) sleepSuf = " · 😴 " + Lang.t(DownloadedActivity.this, "notif_sleep_fading");
                                        else if (lRem > 0) sleepSuf = " · 😴 " + fmtDlTime((int) lRem);
                                        else if (lSusp > 0 && Prefs.sleepMinutes(DownloadedActivity.this) > 0)
                                            sleepSuf = " · 😴 ⏸ " + fmtDlTime((int) lSusp);
                                    } catch (Exception ignored) {}
                                    // v4.29: a finished file used to
                                    // keep its live clock line
                                    // (total / total) with a ▶ icon,
                                    // reading exactly like a paused
                                    // file — the in-place tick suffix
                                    // names the end, same mechanism
                                    // as the sleep suffix above. A
                                    // replay clears the service flag
                                    // before the next tick.
                                    String endedSuf = service.hasTrackEnded() ? " · " + Lang.t(DownloadedActivity.this, "notif_state_ended") : "";
                                    String tickSd = libSayadawMap.get(tickEntry.file.getName());
                                    sz.setText((tickSd != null ? tickSd + " · " : "") + String.format("%.1f MB · %s · %s / %s · −%s · %d/%d%s", mb,
                                            android.text.format.DateFormat.format("dd/MM/yyyy",
                                                    tickEntry.mod),
                                            fmtDlTime(service.getPosition()), fmtDlTime(dur),
                                            fmtDlTime(Math.max(0, dur - service.getPosition())),
                                            dispPos + 1, dispTotal, sleepSuf + endedSuf));
                                }
                            }
                    }
                }
            }
            // v4.00: live library refresh (see the field note) — a
            // directory signature that holds steady for two ticks
            // reloads the list with a pour; a merely growing file
            // never fires, and the first tick just adopts the
            // current signature without reloading.
            try {
                String sig = dlDirSignature();
                if (dlDirSig == null) { dlDirSig = sig; dlDirPendingSig = null; }
                else if (sig.equals(dlDirSig)) { dlDirPendingSig = null; dlDirPendingTicks = 0; }
                else if (sig.equals(dlDirPendingSig)) {
                    if (++dlDirPendingTicks >= 1) {
                        dlDirPendingSig = null; dlDirPendingTicks = 0;
                        dlLiveArrival = true;
                        loadList();
                    }
                } else { dlDirPendingSig = sig; dlDirPendingTicks = 0; }
            } catch (Exception ignored) {}
            // v4.27: live download progress — the tick rescans
            // and refreshes the footer only when the in-progress
            // set changed, so percents tick up without touching
            // the delete/return fade guards in updateTotal.
            try { if (scanDlProgress()) updateTotal(); } catch (Exception ignored) {}
            refreshHandler.postDelayed(this, 1000);
        }
    };

    // v4.00: sorted ".mp3 name:length" signature of Downloads/Dhamma.
    private String dlDirSignature() {
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma");
            java.io.File[] files = dir.isDirectory() ? dir.listFiles() : null;
            if (files == null) return "";
            java.util.List<java.io.File> mp3s = new java.util.ArrayList<java.io.File>();
            for (java.io.File f : files)
                if (f.isFile() && f.getName().toLowerCase().endsWith(".mp3")) mp3s.add(f);
            java.util.Collections.sort(mp3s, new java.util.Comparator<java.io.File>() {
                @Override public int compare(java.io.File a, java.io.File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            StringBuilder sb = new StringBuilder();
            for (java.io.File f : mp3s) sb.append(f.getName()).append(':').append(f.length()).append(';');
            return sb.toString();
        } catch (Exception ignored) { return ""; }
    }

    // v4.27: scan DownloadManager for this app's in-progress
    // downloads (RUNNING/PAUSED/PENDING, destination under
    // Downloads/Dhamma or a source URL in the Room's dlMap).
    // Returns true when the summary changed, so the 1s tick
    // refreshes the footer only then; updateTotal rescans too,
    // so a loadList pass always draws a fresh footer.
    private boolean scanDlProgress() {
        int count = 0;
        String firstTitle = "";
        int firstPct = -1;
        try {
            java.util.Set<String> dlUrls = null;
            try {
                org.json.JSONObject map = new org.json.JSONObject(
                        getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlMap", "{}"));
                dlUrls = new java.util.HashSet<String>();
                java.util.Iterator<String> keys = map.keys();
                while (keys.hasNext()) dlUrls.add(keys.next());
            } catch (Exception ignored) {}
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            // v12.70: filter in the provider — the v11.76 Talks shape; this runs
            // on the library's 1s refresh tick and used to pull every download on
            // the main thread and discard the finished ones in Java; the status
            // check below stays as a guard.
            android.database.Cursor c = dm.query(new android.app.DownloadManager.Query()
                    .setFilterByStatus(android.app.DownloadManager.STATUS_RUNNING
                            | android.app.DownloadManager.STATUS_PAUSED
                            | android.app.DownloadManager.STATUS_PENDING));
            if (c != null) {
                try {
                    int statusCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS);
                    int uriCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_LOCAL_URI);
                    int srcCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_URI);
                    int titleCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_TITLE);
                    int doneCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                    int totalCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                    while (c.moveToNext()) {
                        int st = statusCol >= 0 ? c.getInt(statusCol) : -1;
                        if (st != android.app.DownloadManager.STATUS_RUNNING
                                && st != android.app.DownloadManager.STATUS_PAUSED
                                && st != android.app.DownloadManager.STATUS_PENDING) continue;
                        String local = uriCol >= 0 ? c.getString(uriCol) : null;
                        String src = srcCol >= 0 ? c.getString(srcCol) : null;
                        boolean ours = (local != null && local.contains("Dhamma/"))
                                || (src != null && dlUrls != null && dlUrls.contains(src));
                        if (!ours) continue;
                        count++;
                        if (firstTitle.length() == 0) {
                            firstTitle = titleCol >= 0 && c.getString(titleCol) != null ? c.getString(titleCol) : "";
                            long done = doneCol >= 0 ? c.getLong(doneCol) : -1;
                            long tot = totalCol >= 0 ? c.getLong(totalCol) : -1;
                            if (done >= 0 && tot > 0) firstPct = (int) (done * 100 / tot);
                        }
                    }
                } finally { c.close(); }
            }
        } catch (Exception ignored) {}
        String sig = count + "|" + firstTitle + "|" + firstPct;
        boolean changed = !sig.equals(dlProgSig);
        dlProgSig = sig;
        dlProgCount = count;
        dlProgFirstTitle = firstTitle;
        dlProgFirstPct = firstPct;
        return changed;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            service = ((AudioService.LocalBinder) b).getService();
            // v1.62: play a row queued before the bind completed.
            // v1.92: only if the file is still there — a file
            // deleted between the tap and the bind used to reach
            // play() as a ghost path and fail with just an error
            // toast, while the rotation-restore path (v1.67) already
            // checked existence. Say the file is gone instead.
            if (pendingDlPath != null) {
                String p = pendingDlPath;
                String t = pendingDlTitle;
                pendingDlPath = null;
                pendingDlTitle = null;
                boolean restart = pendingDlRestart;
                pendingDlRestart = false;
                // v1.93: a tap queued before the bind used to play
                // from 0 even when the file had a saved spot — the
                // row-tap resume (v1.90) only ran on the bound path.
                // Same saved spot + session anchor as a live tap.
                if (new File(p).exists()) {
                    saveLastLibrary(p, t);
                    // v8.13: a queued manual pick clears the trail too, except a queued
                    // continue tap (autoPlayDone + path == autoPlayPath), which resumes
                    // the same session the trail belongs to, so its chain stays valid.
                    if (!(autoPlayDone && p.equals(autoPlayPath))) clearLibTrail13();
                    service.play(p, t, restart ? 0 : savedPosForFile(p));
                    // v2.95: a play-all/random tap queued before the
                    // bind used to start with no glide either — the
                    // bound path above glides, so the queued path
                    // must too (a queued row tap glides to its own
                    // already-visible row, which is a no-op scroll).
                    glideToDlPath(p);
                }
                else Toast.makeText(DownloadedActivity.this,
                        Lang.t(DownloadedActivity.this, "dl_file_gone"), Toast.LENGTH_SHORT).show();
                if (adapter != null) adapter.notifyDataSetChanged();
            }
            if (pendingDlNext) handleLibNext();
            if (pendingDlPrev) handleLibPrev(); // v4.23
            // v1.80: same live sync as the Room's bind callback —
            // a speed/sleep change made in Settings while a library
            // file played never reached the current file (the Room
            // has synced since v1.58, the library never did).
            syncLiveSettings();
            // v3.95: the bind-side retry of the fresh-open glide
            // (the loadList-side call ran before the bind).
            tryDlFreshGlide();
        }
        @Override public void onServiceDisconnected(ComponentName n) { service = null; }
    };

    // v1.80: mirror of the Room's syncLiveSettings (same rules: speed
    // applies paused too, the sleep timer only touches a playing
    // file, a changed sleep setting restarts the countdown, an
    // unchanged one never restarts it).
    private void syncLiveSettings() {
        if (service == null) return;
        service.setSpeed(Prefs.defaultSpeed(this));
        if (!service.isPlaying()) return;
        int mins = Prefs.sleepMinutes(this);
        long remaining = service.getSleepRemainingMs();
        // v4.82: same one-shot guard as the Room sync — a minute
        // pref changed since arming cancels it, an unchanged pref
        // is a routine return and leaves it alone.
        if (service.isSleepEndOfTalk() && mins != service.getSleepEndArmedPref())
            service.cancelSleepEndOfTalk();
        if (!service.isSleepEndOfTalk() && mins > 0 && (remaining == 0 || mins != service.getSleepSetMinutes()))
            service.setSleepMinutes(mins);
        else if (!service.isSleepEndOfTalk() && mins == 0 && remaining > 0) service.setSleepMinutes(0);
    }

    // v1.51: rotating the phone used to wipe the library search text
    // (the sort choice already survived via Prefs since v1.47).
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("dlQuery",
                dlSearchField != null ? dlSearchField.getText().toString() : dlQuery);
        // v4.95: the library filter mode survives rotation — the
        // mirror of Main's v4.26 favOnly save. The loadList
        // guard clears it again if its set emptied, and its hint
        // refresh follows the flag either way. The old boolean
        // key is honoured once for a restore saved by v4.94.
        outState.putInt("dlFilterMode", dlFilterMode);
        // v4.58: queue-only view survives rotation like the
        // chooser's flag (v4.56); the first loadList's queue
        // prune + the adapter's getCount heal drop it again if
        // the queue did not survive the rebuild.
        outState.putBoolean("libQueueOnly", libQueueOnly);
        if (dlListView != null) outState.putInt("dlScrollPos", dlListView.getFirstVisiblePosition());
        // v1.67: a row tap queued before the bind used to vanish on
        // rotation — same lost-queue class as the Room's pending tap.
        if (pendingDlPath != null) {
            outState.putString("pendingDlPath", pendingDlPath);
            outState.putString("pendingDlTitle", pendingDlTitle);
            outState.putBoolean("pendingDlRestart", pendingDlRestart);
        }
        // v1.86: a consumed end must survive rotation — the old code
        // lost the marker, so with autoplay newly turned on in
        // Settings a long-finished file advanced again on return as
        // if it had just ended (the service flag itself survives).
        if (dlEndedHandledFor != null) outState.putString("dlEndedHandledFor", dlEndedHandledFor);
        // v2.05: a queued library Next + an unlanded jump must survive
        // rotation too — the Room has saved pendingNext since v1.76,
        // the library saved only pendingDlPath since v1.67, so a Next
        // queued via onNewIntent (or a jump not yet landed) vanished
        // on rotation, while a fresh-open Next re-fired (see onCreate).
        outState.putBoolean("pendingDlNext", pendingDlNext);
        outState.putBoolean("pendingDlPrev", pendingDlPrev); // v4.23: same rotation rule as Next
        outState.putBoolean("pendingDlQueue", pendingDlQueue); // v4.77
        if (jumpPath != null) {
            outState.putString("jumpPath", jumpPath);
            outState.putBoolean("jumpDone", jumpDone);
        }
        // v4.63: an unplayed continue target survives rotation
        // through the same bundle rule as jumpPath; a played one
        // stays done, so a rotation never replays it.
        if (autoPlayPath != null) {
            outState.putString("autoPlayPath", autoPlayPath);
            outState.putBoolean("autoPlayDone", autoPlayDone);
        }
        // v5.79: the armed pick-up/drop move survives rotation
        // with the shelf — the bind and the drop tap both
        // re-validate against libQueueCache, so a held file
        // deleted mid-rotation simply never paints or drops.
        if (moveHeldPath != null) outState.putString("moveHeldPath", moveHeldPath);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_downloaded);
        // v1.94: fresh open only — a rotation recreates the screen
        // with a saved scroll position, which must win over a jump.
        if (savedInstanceState == null) jumpPath = getIntent().getStringExtra("jumpPath");
        // v4.63: fresh open only, like jumpPath — a rotation
        // restores its own state below instead of re-firing.
        if (savedInstanceState == null) autoPlayPath = getIntent().getStringExtra("autoPlayPath");
        // v2.12: arm the first-load cascade (see dlFirstLoad).
        dlFirstLoad = (savedInstanceState == null);
        // v3.95: arm the fresh-open playing-file glide (see
        // tryDlFreshGlide) — a rotation recreates with its own
        // saved scroll position, which must win over a glide.
        dlFreshGlidePending = (savedInstanceState == null);
        // v2.05: the Room's v1.76 rotation rule, applied here — the
        // old unconditional arm re-read the same ACTION_NEXT intent
        // after a rotation and advanced AGAIN, skipping a talk, even
        // though the Next had already been handled. The intent only
        // arms the flag on first creation; a recreation restores the
        // flag from the bundle instead, so an already-handled Next
        // (pendingDlNext false) never re-fires while a still-queued
        // one (true) still survives to the bind/loadList flush.
        // A jump not yet landed is restored the same way — a done
        // jump stays done, so the rotation scroll position still
        // wins over it (see applyJumpOnce).
        if (savedInstanceState != null) {
            pendingDlNext = savedInstanceState.getBoolean("pendingDlNext", false);
            pendingDlPrev = savedInstanceState.getBoolean("pendingDlPrev", false); // v4.23
            pendingDlQueue = savedInstanceState.getBoolean("pendingDlQueue", false); // v4.77
            String savedJump = savedInstanceState.getString("jumpPath");
            if (savedJump != null) {
                jumpPath = savedJump;
                jumpDone = savedInstanceState.getBoolean("jumpDone", false);
            }
            String savedAuto = savedInstanceState.getString("autoPlayPath");
            if (savedAuto != null) {
                autoPlayPath = savedAuto;
                autoPlayDone = savedInstanceState.getBoolean("autoPlayDone", false);
            }
            // v5.79: restore the armed pick-up/drop move (see
            // onSaveInstanceState); loadList's cache refresh
            // validates it before any row binds or drops.
            String savedMove = savedInstanceState.getString("moveHeldPath");
            if (savedMove != null) moveHeldPath = savedMove;
        } else if (ACTION_NEXT.equals(getIntent().getAction())) {
            pendingDlNext = true;
        } else if (ACTION_PREV.equals(getIntent().getAction())) { // v4.23
            pendingDlPrev = true;
        } else if (ACTION_QUEUE.equals(getIntent().getAction())) { // v4.77
            pendingDlQueue = true;
        }
        // v1.90: keep-screen-on was honoured on the Room and the
        // Talks chooser but never here — a long downloaded talk
        // could dim the screen mid-listen despite the setting.
        if (Prefs.keepScreenOn(this))
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyTheme();
        sortBtn = findViewById(R.id.btnDlSort);
        // v1.47: the sort choice survives reopening the library.
        dlSortMode = Prefs.dlSortMode(this);
        sortBtn.setText(dlSortLabel());
        sortBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v2.41: header click beat — sort always flips, so
                // it always morphs (see the other header buttons).
                // v2.90: premium sort-label transition — mirroring
                // the Settings dip series (v2.85 font-size / v2.86
                // default-speed / v2.87 sleep / v2.88 notification /
                // v2.89 toggles). The label used to flip (A-Z <->
                // နောက်ဆုံးဒေါင်းလုဒ်) in one frame while only the
                // tapped button morphed. It now dips with the
                // existing Fx.themeDip (140ms down to 0.55, swap at
                // the dip, 260ms rise to exactly alpha 1), so the
                // new sort order lands softly. The dip runs on the
                // button ROW, not the button itself, so its alpha
                // channel never cancels the morph's scale channel
                // on the tapped button (themeDip cancels its own
                // view's animator). The pref is set before the dip
                // and the swap reads live dlSortMode, so a rapid re-tap
                // still lands on the correct label; loadList() runs
                // at once (it only reads dlSortMode, like the Settings
                // syncLiveService pattern).
                Fx.morph(v);
                // v4.22: sorting reorders the visible list in
                // place, so the search keyboard hides with it —
                // the reordered results are what the tap was for.
                hideKeyboard(v);
                // v4.24: the sort button now cycles three states —
                // latest-download first, A-Z, largest file first —
                // instead of two, so a listener hunting a long talk
                // by size stops re-sorting by hand. Same dip + label
                // + reload + glide as before, only the state advances.
                dlSortMode = (dlSortMode + 1) % 3;
                Prefs.setDlSortMode(DownloadedActivity.this, dlSortMode);
                Fx.themeDip(findViewById(R.id.dlBtnRow), new Runnable() { @Override public void run() { sortBtn.setText(dlSortLabel()); } });
                dlSortChange = true; // v2.54: sort-change entrance (see applyDlFilter)
                // v4.58: a sort redefines the shown order, so it
                // exits queue-only view first (queue order wins
                // over sort while the view is on).
                if (libQueueOnly) libQueueOnly = false;
                loadList();
                // v2.97: premium sort-change track glide — every
                // library play path already glides its row into
                // view (auto-advance / Next v2.63, play-all /
                // random / queued v2.95, row tap v2.96), but a
                // sort tap reordered the whole list and left the
                // current track's pulsing row wherever the new
                // order put it — off-screen for a long library,
                // while the rows poured in around it (v2.54).
                // After the reload, glide to the current track's
                // new position in the shown list, posted so it
                // runs after this load's layout pass (same post
                // pattern as the v2.12 cascade). A filtered-out
                // track has no visible row, so glideToDlPath
                // no-ops; no track yet means no glide at all.
                // Visual only.
                if (service != null && service.getCurrentUrl() != null
                        && service.getCurrentUrl().length() > 0
                        && dlListView != null) {
                    final String curPath = service.getCurrentUrl();
                    dlListView.post(new Runnable() {
                        @Override public void run() { glideToDlPath(curPath); }
                    });
                }
            }
        });
        // v4.14: long-press sort resets straight to the default
        // order (latest download first) instead of cycling — a tap
        // only toggles A-Z <-> latest, so returning to the default
        // from A-Z took exactly one tap too, but only if the user
        // knew which state they were in; the long-press is explicit.
        // Same dip + label + reload + current-track glide as the
        // tap, only when a change actually happens; an already-
        // default list just blips. No new view, no new button.
        sortBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (dlSortMode == 0) { Fx.tap(DownloadedActivity.this); return true; }
                Fx.morph(v);
                // v4.22: a real sort reset reorders in place like
                // the tap, so the keyboard hides too (the already-
                // latest blip above changes nothing, so it keeps it).
                hideKeyboard(v);
                dlSortMode = 0;
                Prefs.setDlSortMode(DownloadedActivity.this, dlSortMode);
                Fx.themeDip(findViewById(R.id.dlBtnRow), new Runnable() { @Override public void run() { sortBtn.setText(dlSortLabel()); } });
                dlSortChange = true;
                // v4.58: sort reset exits queue-only view, same as
                // the sort tap above.
                if (libQueueOnly) libQueueOnly = false;
                loadList();
                if (service != null && service.getCurrentUrl() != null
                        && service.getCurrentUrl().length() > 0
                        && dlListView != null) {
                    final String curPath = service.getCurrentUrl();
                    dlListView.post(new Runnable() {
                        @Override public void run() { glideToDlPath(curPath); }
                    });
                }
                return true;
            }
        });
        // v1.23: play a random downloaded talk (offline, no choosing needed)
        // v4.16: shuffle mark lives on the Random button itself
        // (🔀 prefix, see applyShuffleMark) — no new view, and the
        // button-text refresh sites only set size/colour, never
        // text, so the mark survives a refresh.
        findViewById(R.id.btnDlRandom).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Entry rand0 = randomDisplayEntry();
                if (rand0 == null) {
                    // v2.02: this toast used to claim "no downloads
                    // yet" even when downloads existed but the search
                    // filter matched none — the same filtered-empty
                    // lie the empty view just fixed. Name the case
                    // that is actually true.
                    Toast.makeText(DownloadedActivity.this, entries.isEmpty()
                            ? Lang.t(DownloadedActivity.this, "dl_none_yet")
                            : Lang.t(DownloadedActivity.this, "no_search_match"), Toast.LENGTH_SHORT).show();
                    return;
                }
                // v2.41: header click beat — the tap is accepted
                // (queued pre-bind included, like a row tap), so it
                // morphs; only the empty-list refusal above skips it.
                // v4.22: random play starts in place, so the search
                // keyboard hides with it (the empty refusal above
                // keeps it, so a new query can be typed at once).
                hideKeyboard(v);
                Fx.morph(v);
                // v1.63: queue like a row tap (v1.62) instead of
                // toasting "service not ready" and dropping the tap —
                // the bind usually lands a moment later.
                Entry e = rand0;
                if (replayCurrentLibFile(e)) return;
                // v11.65: same deleted-after-scan guard as the row tap
                // — a stale Random pick used to die with the generic
                // "check internet" toast and anchor a dead file.
                if (!e.file.exists()) {
                    Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "dl_file_gone"), Toast.LENGTH_SHORT).show();
                    loadList();
                    return;
                }
                if (libQueueOnly) trimLibQueueAhead(e.file.getAbsolutePath());
                if (service == null) {
                    pendingDlPath = e.file.getAbsolutePath();
                    pendingDlTitle = e.title;
                    pendingDlRestart = false;
                    return;
                }
                // v1.93: random play honours the picked file's saved
                // spot like a row tap (v1.90) — it used to restart
                // from 0 while the same file tapped as a row resumed.
                saveLastLibrary(e.file.getAbsolutePath(), e.title);
                clearLibTrail13(); // v8.13
                service.play(e.file.getAbsolutePath(), e.title,
                        savedPosForFile(e.file.getAbsolutePath()));
                adapter.notifyDataSetChanged();
                glideToDlPath(e.file.getAbsolutePath()); // v2.95
            }
        });
        // v4.16: long-press Random toggles shuffle-continuous
        // (dlShuffle, session-only) — auto-advance then picks a
        // random shown file instead of the next one in order.
        // Tap still plays one random file at once, mode untouched.
        findViewById(R.id.btnDlRandom).setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                dlShuffle = !dlShuffle;
                applyShuffleMark();
                Fx.morph(v);
                Toast.makeText(DownloadedActivity.this, dlShuffle ? Lang.t(DownloadedActivity.this, "shuffle_cont_on") : Lang.t(DownloadedActivity.this, "shuffle_cont_off"), Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        // v1.42: play the whole shown list in order from the first file
        // (offline continuous play, v1.40, advances to the next one).
        findViewById(R.id.btnDlPlayAll).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Entry first0 = firstDisplayEntry();
                if (first0 == null) {
                    // v2.02: same filtered-empty wording fix as the
                    // random button above — "no downloads yet" lied
                    // when the filter, not the folder, was empty.
                    Toast.makeText(DownloadedActivity.this, entries.isEmpty()
                            ? Lang.t(DownloadedActivity.this, "dl_none_yet")
                            : Lang.t(DownloadedActivity.this, "no_search_match"), Toast.LENGTH_SHORT).show();
                    return;
                }
                // v2.41: header click beat — same accepted-tap rule
                // as random-play above (queued pre-bind morphs too);
                // only the empty-list refusal above skips it.
                // v4.22: play-all starts in place, same keyboard
                // rule as random-play (refusal keeps the keyboard).
                hideKeyboard(v);
                Fx.morph(v);
                // v1.63: same queue fix as random-play above — a tap
                // before the bind used to be dropped with a toast.
                Entry e = first0;
                if (replayCurrentLibFile(e)) return;
                // v11.65: same deleted-after-scan guard as the row tap
                // — a stale Play All pick used to die with the generic
                // "check internet" toast and anchor a dead file.
                if (!e.file.exists()) {
                    Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "dl_file_gone"), Toast.LENGTH_SHORT).show();
                    loadList();
                    return;
                }
                if (libQueueOnly) trimLibQueueAhead(e.file.getAbsolutePath());
                if (service == null) {
                    pendingDlPath = e.file.getAbsolutePath();
                    pendingDlTitle = e.title;
                    pendingDlRestart = false;
                    return;
                }
                // v1.93: play-all honours the first file's saved spot
                // like a row tap — same restart-from-0 gap as random.
                saveLastLibrary(e.file.getAbsolutePath(), e.title);
                clearLibTrail13(); // v8.13
                service.play(e.file.getAbsolutePath(), e.title,
                        savedPosForFile(e.file.getAbsolutePath()));
                adapter.notifyDataSetChanged();
                glideToDlPath(e.file.getAbsolutePath()); // v2.95
            }
        });
        // v4.16: long-press Play All starts a shuffled play-all
        // — shuffle mode on (mark on the Random button) and the
        // first file picked at random; auto-advance then keeps
        // picking at random until the mode is toggled off. Tap
        // still plays the shown list in order from the first file.
        findViewById(R.id.btnDlPlayAll).setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Entry shuf0 = randomDisplayEntry();
                if (shuf0 == null) {
                    Toast.makeText(DownloadedActivity.this, entries.isEmpty()
                            ? Lang.t(DownloadedActivity.this, "dl_none_yet")
                            : Lang.t(DownloadedActivity.this, "no_search_match"), Toast.LENGTH_SHORT).show();
                    return true;
                }
                // v4.22: shuffled play-all starts playback in
                // place, so the search keyboard hides with it
                // (the empty refusal above keeps it for retyping).
                hideKeyboard(v);
                Fx.morph(v);
                dlShuffle = true;
                applyShuffleMark();
                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "shuffle_cont_play"), Toast.LENGTH_SHORT).show();
                Entry e = shuf0;
                if (replayCurrentLibFile(e)) return true;
                // v11.65: same deleted-after-scan guard as the row tap
                // — a stale shuffled pick used to die with the generic
                // "check internet" toast and anchor a dead file.
                if (!e.file.exists()) {
                    Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "dl_file_gone"), Toast.LENGTH_SHORT).show();
                    loadList();
                    return true;
                }
                if (libQueueOnly) trimLibQueueAhead(e.file.getAbsolutePath());
                if (service == null) {
                    pendingDlPath = e.file.getAbsolutePath();
                    pendingDlTitle = e.title;
                    pendingDlRestart = false;
                    return true;
                }
                saveLastLibrary(e.file.getAbsolutePath(), e.title);
                clearLibTrail13(); // v8.13
                service.play(e.file.getAbsolutePath(), e.title,
                        savedPosForFile(e.file.getAbsolutePath()));
                adapter.notifyDataSetChanged();
                glideToDlPath(e.file.getAbsolutePath());
                return true;
            }
        });
        // v1.30: delete the whole offline library at once (confirm
        // first; pauses playback if the playing file is being deleted)
        findViewById(R.id.btnDlDeleteAll).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (entries.isEmpty()) {
                    Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "nothing_to_delete"), Toast.LENGTH_SHORT).show();
                    return;
                }
                // v4.28: the last v4.22 keyboard gap in the library
                // — confirming delete-all empties the whole list,
                // so a keyboard left up would cover the empty word
                // that follows the dialog. The nothing-to-delete
                // refusal above keeps it (a refusal, like the
                // v4.22 empty-list refusals); a real opener hides
                // it before the dialog shows.
                hideKeyboard(v);
                // v2.41: header click beat — the tap succeeded in
                // opening the confirm dialog, so it morphs now
                // (cancelling the dialog leaves the beat, as with a
                // Settings toggle tap); only the nothing-to-delete
                // refusal above skips it.
                Fx.morph(v);
                new android.app.AlertDialog.Builder(DownloadedActivity.this)
                        .setTitle(Lang.t(DownloadedActivity.this, "del_all_title"))
                        .setMessage(Lang.t(DownloadedActivity.this, "del_all_msg_head") + entries.size() + Lang.t(DownloadedActivity.this, "del_all_msg_tail"))
                        .setPositiveButton(Lang.t(DownloadedActivity.this, "del_all_btn"), new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int which) {
                                // v2.93: premium delete-all list fade-out —
                                // mirroring the v2.92 single-row fade-out.
                                // Confirming delete-all used to vanish every
                                // row in the same frame as the dialog close
                                // (the empty word then faded in via v2.59,
                                // so the exit moved in a different rhythm
                                // from the entrance that followed it). The
                                // whole list now fades to 0 over 200ms
                                // (decelerate, alpha-only) and only then
                                // are the files removed and the list
                                // refreshed, so the empty-word fade follows
                                // the list fade. The finish path runs once
                                // on end OR cancel (a detach mid-fade must
                                // never swallow the deletion), restores
                                // alpha to 1 before the refresh so remaining
                                // rows after a partial failure never rebind
                                // faded, and re-enables the list it disabled
                                // for the fade so no row tap can start a
                                // file mid-fade that is about to be deleted.
                                // A detached list (rotation during the
                                // dialog) deletes instantly, as in v2.92.
                                // Visual only; counts, toasts and the
                                // partial-failure path are unchanged.
                                final Runnable doDeleteAll = new Runnable() {
                                    @Override public void run() {
                                int count = entries.size();
                                // v7.87: delete-all used to delete only landed
                                // files, leaving live DownloadManager jobs
                                // running — a Wi-Fi-waiting or mid-download
                                // job landed afterwards and repopulated
                                // the library the confirm dialog had just
                                // emptied, so "all deleted" was untrue
                                // whenever a batch was still in flight.
                                // Cancel this app's live jobs first (the
                                // same app scope scanDlProgress uses:
                                // a Downloads/Dhamma destination, or a
                                // source URL in dlQueued/dlMap), then
                                // delete the landed files below. The
                                // cancelled URLs drop from dlQueued at
                                // once so the batch verbs stop skipping
                                // them as "already queued"; their dlMap
                                // entries stay, exactly like a failed
                                // job's, so a re-download reuses the
                                // mapped filename.
                                // v10.18: paused-card download-mark sync, Downloaded
                                // delete-all cancel-drop leg of v10.06/v10.14/
                                // v10.15/v10.16/v10.17 — cancelLiveDownloads()
                                // below cancels live jobs and drops their URLs
                                // from dlQueued at once, with no card refresh
                                // in this path (v10.09 covers only a job that
                                // LANDS). A batch cancelled here while its
                                // talk sits PAUSED kept its hourglass on the
                                // card until the next tap (rebuilds run every
                                // 15s only while playing, never while paused).
                                // Snapshot dlQueued first, then send the
                                // no-bind refresh intent (v10.09/v10.15 shape:
                                // liveSession guard, never starts a dead
                                // service) only when a dropped URL is the
                                // sounding talk — room: dropped == lastUrl;
                                // library: dlMap[dropped] file == lastLibPath
                                // file, read fresh (dlMap entries stay after
                                // a cancel, so the guard still resolves).
                                java.util.Set<String> queuedBefore1018 = new java.util.HashSet<String>();
                                try {
                                    org.json.JSONArray qb1018 = new org.json.JSONArray(
                                            getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlQueued", "[]"));
                                    for (int i1018 = 0; i1018 < qb1018.length(); i1018++)
                                        queuedBefore1018.add(qb1018.optString(i1018));
                                } catch (Exception ignored) {}
                                cancelLiveDownloads();
                                if (AudioService.liveSession) {
                                    try {
                                        android.content.SharedPreferences rsp1018 =
                                                getSharedPreferences("dhamma", MODE_PRIVATE);
                                        org.json.JSONArray qa1018 = new org.json.JSONArray(rsp1018.getString("dlQueued", "[]"));
                                        java.util.Set<String> queuedAfter1018 = new java.util.HashSet<String>();
                                        for (int i1018 = 0; i1018 < qa1018.length(); i1018++)
                                            queuedAfter1018.add(qa1018.optString(i1018));
                                        String lastUrl1018 = rsp1018.getString("lastUrl", "");
                                        String libPath1018 = rsp1018.getString("lastLibPath", "");
                                        org.json.JSONObject map1018 = new org.json.JSONObject(rsp1018.getString("dlMap", "{}"));
                                        boolean cardTalk1018 = false;
                                        for (String dropped1018 : queuedBefore1018) {
                                            if (queuedAfter1018.contains(dropped1018)) continue;
                                            if (dropped1018.equals(lastUrl1018)) { cardTalk1018 = true; break; }
                                            if (libPath1018.length() > 0) {
                                                String fn1018 = map1018.optString(dropped1018, "");
                                                if (fn1018.length() > 0 && fn1018.equals(new java.io.File(libPath1018).getName())) { cardTalk1018 = true; break; }
                                            }
                                        }
                                        if (cardTalk1018) {
                                            android.content.Intent rsv1018 = new android.content.Intent(DownloadedActivity.this, AudioService.class);
                                            rsv1018.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                                            startService(rsv1018);
                                        }
                                    } catch (Exception ignored) {}
                                }
                                java.util.Set<String> deletedNames = new java.util.HashSet<String>();
                                // v11.73: unbound-window mirror (see single delete) — the sounding path is
                                // read once before the loop; the service state cannot change mid-loop.
                                String sounding73 = soundingLibPath();
                                for (Entry e : new ArrayList<Entry>(entries)) {
                                    // v1.56: a pause left a stale track pointing
                                    // at the deleted file — end the session
                                    // cleanly instead (see stopAndClear).
                                    if (sounding73.length() > 0 && e.file.getAbsolutePath().equals(sounding73)) {
                                        if (service != null) service.stopAndClear();
                                        else {
                                            android.content.Intent stop73 = new android.content.Intent(DownloadedActivity.this, AudioService.class);
                                            stop73.setAction(AudioService.ACTION_STOP);
                                            startService(stop73);
                                        }
                                    }
                                    // v1.98: only a file actually deleted
                                    // earns a map prune below — a failed
                                    // delete must keep its map entry.
                                    if (e.file.delete()) deletedNames.add(e.file.getName());
                                }
                                pruneDlMapForFilenames(deletedNames);
                                // v1.63: delete-all deletes every file, so a
                                // tap queued before the bind (v1.62) now
                                // points at a deleted file — clear it, or
                                // the bind would play (and fail) a ghost.
                                pendingDlPath = null;
                                pendingDlTitle = null;
                                pendingDlRestart = false;
                                // v2.59: delete-all empty-state fade — only
                                // when every file really deleted (a partial
                                // failure leaves rows, so no fade is armed);
                                // loadList + applyDlFilter below show the
                                // empty view with the fade (see loadList).
                                dlEmptyFade = (count > 0 && deletedNames.size() == count);
                                // v2.94: premium partial delete-all row pour —
                                // the v2.55 pour never covered delete-all
                                // (an emptied list has no rows to pour, and
                                // its empty view fades via dlEmptyFade), but
                                // a delete-all that only partly succeeds
                                // leaves rows behind, and those rows used to
                                // rebind instantly after the v2.93 list
                                // fade-out, flatter than the single-delete
                                // pour. Arming dlDeleteChange here (only
                                // when some files really deleted and some
                                // remain) makes the survivors pour in via
                                // the existing v2.55 guard + key check and
                                // fades the footer via the v2.56 guard
                                // (loadList calls updateTotal before
                                // applyDlFilter consumes the flag). A
                                // full success keeps the empty-fade path
                                // untouched, and an all-failed delete
                                // arms nothing, so its unchanged rows
                                // never replay. Visual only.
                                if (deletedNames.size() > 0 && deletedNames.size() < count)
                                    dlDeleteChange = true;
                                loadList();
                                // v8.72: delete-all used to toast the ATTEMPTED count
                                // (entries.size()), so a partial failure — some files
                                // undeletable, their rows still listed — still claimed
                                // every talk deleted. Single delete (v2.92) and the
                                // subset deletes already report truthfully (actual
                                // deleted count, "ဖျက်မရပါ" when none); delete-all
                                // now matches them: the real deleted count, or the
                                // failure toast when nothing went. The rows on
                                // screen already showed the truth; only the toast lied.
                                if (deletedNames.isEmpty())
                                    Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "delete_failed"), Toast.LENGTH_SHORT).show();
                                else
                                    Toast.makeText(DownloadedActivity.this, deletedNames.size() + Lang.t(DownloadedActivity.this, "deleted_suffix"), Toast.LENGTH_SHORT).show();
                                    }
                                };
                                if (dlListView != null && dlListView.getVisibility() == android.view.View.VISIBLE
                                        && dlListView.getWindowToken() != null) {
                                    dlListView.setEnabled(false);
                                    android.animation.ObjectAnimator fade =
                                            android.animation.ObjectAnimator.ofFloat(dlListView, "alpha", 0f);
                                    fade.setDuration(200);
                                    fade.setInterpolator(new android.view.animation.DecelerateInterpolator());
                                    fade.addListener(new android.animation.AnimatorListenerAdapter() {
                                        boolean done = false;
                                        void finish() {
                                            if (done) return;
                                            done = true;
                                            dlListView.setAlpha(1f);
                                            dlListView.setEnabled(true);
                                            doDeleteAll.run();
                                        }
                                        @Override public void onAnimationEnd(android.animation.Animator a) { finish(); }
                                        @Override public void onAnimationCancel(android.animation.Animator a) { finish(); }
                                    });
                                    fade.start();
                                } else {
                                    doDeleteAll.run();
                                }
                            }
                        })
                        .setNegativeButton(Lang.t(DownloadedActivity.this, "del_btn_no"), null)
                        .show();
            }
        });
        // v1.21: search the offline library by file title
        final android.widget.EditText dlSearch = findViewById(R.id.searchDl);
        dlSearchField = dlSearch;
        dlSearch.setHint(Lang.t(DownloadedActivity.this, "dl_search_hint"));
        // v4.20: keyboard Search key dismisses the keyboard — same
        // missing editor action as the Main search: the Search key
        // did nothing, and the live filter already ran per keystroke.
        dlSearch.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        dlSearch.setSingleLine(true);
        dlSearch.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(android.widget.TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                        || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                    return true;
                }
                return false;
            }
        });
        // v1.86: restore the consumed-end marker (see onSaveInstanceState).
        if (savedInstanceState != null) {
            String handled = savedInstanceState.getString("dlEndedHandledFor", "");
            if (handled.length() > 0) dlEndedHandledFor = handled;
        }
        // v1.67: restore a tap queued before the bind (see
        // onSaveInstanceState) — only if the file is still there, so
        // the bind never plays a file deleted mid-rotation.
        if (savedInstanceState != null) {
            String pp = savedInstanceState.getString("pendingDlPath", "");
            if (pp.length() > 0 && new File(pp).exists()) {
                pendingDlPath = pp;
                pendingDlTitle = savedInstanceState.getString("pendingDlTitle", "");
                pendingDlRestart = savedInstanceState.getBoolean("pendingDlRestart", false);
            }
        }
        // v1.51: restore the pre-rotation search text (see
        // onSaveInstanceState) — its listener re-runs the filter.
        if (savedInstanceState != null) {
            // v4.95: restore the library filter mode beside the
            // query (saved above); the first loadList's cache guard
            // clears it again if its set emptied, and its hint
            // refresh follows the flag either way.
            dlFilterMode = savedInstanceState.getInt("dlFilterMode", 0);
            libQueueOnly = savedInstanceState.getBoolean("libQueueOnly", false);
            String savedQ = savedInstanceState.getString("dlQuery", "");
            dlQuery = savedQ.trim().toLowerCase();
            // v13.16: the box text alone used to lie — setText fires
            // before the watcher is attached (:5065), and the watcher
            // is the only dlQuery writer, so a rotated search showed
            // the query while the list stayed unfiltered. The filter
            // query is restored here, in the watcher's exact
            // normalization; the first loadList filters with it.
            if (savedQ.length() > 0) dlSearch.setText(savedQ);
            // v1.55: the scroll restore is consumed by the first
            // loadList() after creation (see loadList), so a later
            // sort/delete never jumps back to the old position.
            savedDlScroll = savedInstanceState.getInt("dlScrollPos", -1);
        }
        // v11.73: permission-denied retry — the empty word itself re-asks for the audio
        // permission (no new element). A denial used to strand the screen on a lie with
        // no in-screen way back; only a recreation re-prompted. The tap no-ops whenever
        // the permission is already held, so the truly-empty / no-match words are inert.
        android.view.View emptyTap73 = findViewById(R.id.emptyText);
        if (emptyTap73 != null) {
            emptyTap73.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!hasReadPermission()) requestReadPermission();
                }
            });
        }
        // v1.38: one-tap clear for the library search
        findViewById(R.id.btnClearDlSearch).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v2.41: header click beat — morphs only when there
                // is actually a search to clear, so tapping an
                // already-empty search never reads as a change.
                if (dlSearch.getText().length() > 0) Fx.morph(v);
                dlSearch.setText("");
            }
        });
        // v4.19: long-press the ✕ resets the whole library view —
        // tap (v2.41) only clears the query, so a listener who had
        // also switched the sort to A-Z still sat in a filtered,
        // re-sorted list with no one-gesture way home. The mirror
        // of Main's Home-nav long-press reset-browse (v4.18):
        // query cleared AND sort back to latest-download, via the
        // sort long-press's own reset steps (v4.14). An already
        // default view just blips.
        findViewById(R.id.btnClearDlSearch).setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Fx.tap(DownloadedActivity.this);
                Fx.morph(v);
                // v4.21: a reset that leaves you mid-list is only half
                // a reset — Main's reset-browse (v4.18) scrolls to the
                // top, but this one re-sorted + cleared and stayed
                // wherever the list sat. A real change (sort or query)
                // now posts back to the top after the reload + filter;
                // an already-default view still just blips, per v4.19.
                boolean viewChanged = dlSortMode != 0
                        || (dlSearch != null && dlSearch.getText().length() > 0)
                        || dlFilterMode != 0;
                // v4.95: the full reset also clears the library
                // filter view — the mirror of Main's Home-nav
                // reset (v4.19), so a reset always returns to the
                // full shelf, never to a filtered view the listener
                // forgot was on. The hint follows the mode.
                boolean filterWas = dlFilterMode != 0;
                if (dlFilterMode != 0) { dlFilterMode = 0; refreshDlFavHint(); }
                if (dlSortMode != 0) {
                    dlSortMode = 0;
                    Prefs.setDlSortMode(DownloadedActivity.this, dlSortMode);
                    dlSortChange = true;
                    if (sortBtn != null) sortBtn.setText(dlSortLabel());
                    loadList();
                }
                if (dlSearch != null && dlSearch.getText().length() > 0) dlSearch.setText("");
                // A filter-view reset with no sort change and no query
                // reaches neither reload path above, so re-filter
                // directly — otherwise the filtered shelf would sit
                // there after its own reset. Harmless when another
                // path already re-filtered (a second rebuild of the
                // same shown set, no pour, no scroll).
                // v12.37: the footer follows the filter reset too (see the
                // filter-cycle note above) — without this the Up Next
                // line kept naming the filtered next after the reset.
                if (filterWas) { applyDlFilter(); updateTotal(); }
                if (viewChanged && dlListView != null)
                    dlListView.post(new Runnable() { @Override public void run() { dlListView.setSelection(0); } });
                return true;
            }
        });
        // v4.18: long-press the library search field itself clears
        // the query — the mirror of Main's search long-press
        // (v4.17). The ✕ button above still works; this is the
        // gesture for a thumb already on the keyboard row, and an
        // empty query just blips. The field's watcher re-runs the
        // filter, like a typed edit.
        if (dlSearch != null) dlSearch.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Fx.tap(DownloadedActivity.this);
                if (dlSearch.getText().length() > 0) dlSearch.setText("");
                else {
                    // v4.95: long-press an EMPTY library search
                    // cycles the filter views — အားလုံး -> ★ သာ
                    // -> အသစ်သာ -> အားလုံး — the mirror of the
                    // chooser's v4.94 filter button. A non-empty
                    // query still clears (v4.18), so the two
                    // gestures never collide: clearing needs text,
                    // filtering needs none. The filter runs through
                    // applyDlFilter (never a reload, so the scroll
                    // position and sort survive it), the hint gains
                    // its prefix, and the keyboard hides — the
                    // list just changed underneath it (v4.22 rule),
                    // and no retype is imminent. An empty set just
                    // says so; the filter still engages, and the
                    // empty view carries its own word (below).
                    dlFilterMode = (dlFilterMode + 1) % 3;
                    // v4.58: the filter view redefines the
                    // shown list, so it exits queue-only first.
                    if (libQueueOnly) libQueueOnly = false;
                    if (dlFilterMode == 1 && libFavFiles.isEmpty()) {
                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "no_fav_talk"), Toast.LENGTH_SHORT).show();
                    } else if (dlFilterMode == 2 && allDlListened()) {
                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "all_listened"), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(DownloadedActivity.this, dlFilterMode == 1 ? Lang.t(DownloadedActivity.this, "filter_fav_only") : dlFilterMode == 2 ? Lang.t(DownloadedActivity.this, "filter_new_only") : Lang.t(DownloadedActivity.this, "filter_all"), Toast.LENGTH_SHORT).show();
                    }
                    hideKeyboard(v);
                    applyDlFilter();
                    refreshDlFavHint();
                    // v12.37: the footer follows the filter too —
                    // updateTotal's Up Next line reads shownDl, so a
                    // filter change that never re-ran it left the
                    // footer naming a filtered-out file (or, cycling
                    // back to all, the filtered next) while Next
                    // played the shown list. No flag is armed here,
                    // so the footer just sets its text, no fade.
                    updateTotal();
                }
                return true;
            }
        });
        // v2.11: press feedback on the library header buttons —
        // the last header surface v2.08 missed (rows got it then).
        // Visual only; clicks still work (Fx.press returns false).
        if (sortBtn != null) Fx.press(sortBtn);
        for (int id : new int[]{R.id.btnDlRandom, R.id.btnDlPlayAll,
                R.id.btnDlDeleteAll, R.id.btnClearDlSearch}) {
            View b = findViewById(id);
            if (b != null) Fx.press(b);
        }
        // v4.13: tap the library header to glide back to the top
        // (same tap-to-top as Main's app name, v4.12, and the Talks
        // title, v4.12 — on the existing header, no new view).
        // A long offline list is the same reach-the-top problem
        // the other two screens already solved this way.
        View dlHeaderView = findViewById(R.id.dlHeader);
        if (dlHeaderView != null) dlHeaderView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v4.21: the header tap-to-top was the one header tap
                // with no beat at all — Main's app name and the Talks
                // title both blip on the same gesture (v4.12), so a
                // library header tap read as dead. Tap-only beat,
                // like those two titles; no morph on a header.
                Fx.tap(DownloadedActivity.this);
                // v4.28: the header tap was also the one list-moving
                // library gesture the v4.22 keyboard rule missed —
                // Main's app-name tap (v4.22) hides it, but this tap
                // scrolled under an open keyboard that kept covering
                // the list it had just moved. Hide it with the scroll.
                hideKeyboard(v);
                if (dlListView != null) dlListView.smoothScrollToPosition(0);
            }
        });
        // v4.15: long-press the library header glides back to the
        // playing file (tap still goes to the top, v4.13) — the
        // play-path glides (v2.63/v2.95/v2.96) only fire on a play
        // start, so after scrolling away the playing row was a
        // hunt. A track outside the shown (filtered) list, or no
        // track at all, toasts instead of gliding nowhere.
        if (dlHeaderView != null) dlHeaderView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (service != null && service.hasTrack()) {
                    String cur = service.getCurrentUrl();
                    if (cur != null) {
                        for (int i = 0; i < shownDl.size(); i++) {
                            if (shownDl.get(i).file.getAbsolutePath().equals(cur)) {
                                // v4.21: the glide path was silent — every
                                // other scroll-to-playing long-press blips
                                // on success (Main title v4.18, Talks title
                                // v4.15, drawer title v4.15); the no-track
                                // toast below stays silent, like theirs.
                                Fx.tap(DownloadedActivity.this);
                                // v4.28: same v4.22 keyboard rule as the
                                // header tap above — a successful glide
                                // moves the list, so the keyboard hides
                                // with it; the no-track toast below is a
                                // refusal and keeps the keyboard, like
                                // the v4.22 empty-list refusals.
                                hideKeyboard(v);
                                glideToDlPath(cur);
                                return true;
                            }
                        }
                    }
                }
                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "no_file_playing"), Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        // v2.19: premium library header entrance — the header used
        // to sit flat while the rows cascaded below it (v2.12), the
        // same top-dead pattern the Talks title (v2.16), Main header
        // (v2.17) and Main nav (v2.18) had before their entrances:
        // on a fresh open only, header, subtitle, button rows and
        // search row now fade + rise in order via the existing
        // Fx.entrance() family, ahead of the row cascade that follows
        // once the list loads. Rotations (saved state) and back-stack
        // returns never replay it, and applyTheme() touches only
        // colour/size, never alpha/translation, so the entrance ends
        // cleanly at full opacity. The two button rows are existing
        // layouts given ids only — no view added.
        if (savedInstanceState == null)
            Fx.entrance(findViewById(R.id.dlHeader), findViewById(R.id.dlSubtitle),
                    findViewById(R.id.dlBtnRow), findViewById(R.id.btnDlPlayAll),
                    findViewById(R.id.btnDlDeleteAll), findViewById(R.id.dlSearchRow));
        dlSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                dlQuery = s.toString().trim().toLowerCase();
                // v4.58: a typed search redefines the shown list,
                // so it exits queue-only view first — the queue
                // order is not a search result.
                if (libQueueOnly) libQueueOnly = false;
                dlSearchKeystroke = true;
                applyDlFilter();
                dlSearchKeystroke = false;
                // v12.37: the footer follows the search too — a
                // keystroke that narrows the shown list used to leave
                // the Up Next line naming a filtered-out file until
                // the next track change. One DM query per keystroke,
                // the same price the 1s tick already pays.
                updateTotal();
            }
        });
        adapter = new DlAdapter();
        dlListView = findViewById(R.id.list);
        dlListView.setAdapter(adapter);
        wireDlStrip(); // v4.40: real mini-player on the library
        dlListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                // v4.22: a row tap plays in place (no navigation
                // to auto-hide the keyboard), so a search keyboard
                // stayed up over the list after the pick — hide
                // it first, queued pre-bind taps included.
                hideKeyboard(v);
                // v2.40: library-row click beat — the row squashed under
                // the finger (v2.08 press) but a tap landing on it had
                // no beat of its own, unlike nav (v2.36), Settings
                // choices (v2.37), Talks rows (v2.38) and Main cards
                // (v2.39), so playing a downloaded talk felt flatter
                // than every other tap. It now pops with the shared
                // Fx.morph beat (v2.09); the press squash's UP
                // spring-back is replaced by the morph exactly as on
                // nav, and the beat reads fully because the tap plays
                // in place with no navigation covering it. The queued
                // pre-bind path morphs too — the tap is accepted and
                // plays the moment the service connects (v1.62).
                // v4.20: the library rows were morph-only — the Talks
                // rows blip + morph on tap, but playing a downloaded
                // talk played no blip at all (and the library has no
                // start bell to cover it, unlike the Room). Tap first,
                // then the morph below, like the Talks rows.
                Fx.tap(DownloadedActivity.this);
                Fx.morph(v);
                // v4.58: queue-only mapping — a stale queue slot
                // is a no-op tap, never a shelf-index crash.
                Entry e = libEntryAtDisplay(pos);
                if (e == null) return;
                // v5.78: drop tap for an armed pick-up/drop move
                // — runs before the trim + play below, so a drop
                // never also plays the tapped file. The drop
                // inserts the held path immediately BEFORE the
                // tapped row's path (index measured after the
                // removal), so it reads as "put it in front of
                // this one" in full-shelf, queue-only, filter
                // and search views alike, since all resolve
                // through libEntryAtDisplay. The footer rebinds
                // here (the queue head can move); the queue
                // preview above the strip refreshes on the next
                // 1s tick, like every other queue change.
                if (moveHeldPath != null) {
                    String tappedPath = e.file.getAbsolutePath();
                    if (!libQueueCache.contains(moveHeldPath)) {
                        moveHeldPath = null;
                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return;
                    }
                    if (tappedPath.equals(moveHeldPath)) {
                        moveHeldPath = null;
                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "move_cancelled"), Toast.LENGTH_SHORT).show();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return;
                    }
                    // v11.22: a held file that started playing since
                    // the arm (strip/library Next consumed the queue
                    // head) must not drop anywhere — the arm dies
                    // with a queue_changed toast.
                    // v11.28: the drop leg was the last bound-only
                    // sounding check in the move family — v11.27
                    // mirrored the arm and the up/down/top/end
                    // verbs, but this guard still read
                    // service.getCurrentUrl(), so unbound (bind
                    // pending/dropped, liveSession true) a sounding
                    // held file still in the cache passed it and
                    // the drop re-inserted it before the tapped
                    // row, replaying it after the next file.
                    // soundingLibPath() is the bound currentUrl
                    // when bound, so this changes only the
                    // unbound window, like v11.27.
                    if (moveHeldPath.equals(soundingLibPath())) {
                        moveHeldPath = null;
                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return;
                    }
                    if (!libQueueCache.contains(tappedPath)) {
                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "queue_tap_only"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String heldTitle = "";
                    for (int i = 0; i < entries.size(); i++)
                        if (entries.get(i).file.getAbsolutePath().equals(moveHeldPath)) { heldTitle = entries.get(i).title; break; }
                    java.util.ArrayList<String> q2 = new java.util.ArrayList<String>(libQueueCache);
                    q2.remove(moveHeldPath);
                    q2.add(q2.indexOf(tappedPath), moveHeldPath);
                    moveHeldPath = null;
                    saveLibQueue(q2, null, true);
                    updateTotal();
                    Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "moved_item") + heldTitle, Toast.LENGTH_SHORT).show();
                    return;
                }
                // v4.57: jumping to a queued file drops the queue
                // ahead of it (trimLibQueueAhead, the v4.43 Room
                // rule) — except the playing row's toggle tap,
                // which changes no track and must not touch the
                // queue. The pending pre-bind tap trims too: it
                // plays on bind, so it is a jump like any other.
                // v11.65: a row whose file was deleted after the scan
                // (Files app, cleanup) must not reach service.play —
                // the bound path had no exists check, so it died
                // with the generic "check internet" toast and left
                // the dead file as the last-library anchor (the
                // queued pre-bind path already toasts dl_file_gone
                // at flush). Toast the accurate line and rescan so
                // the stale row disappears; the pre-bind queue keeps
                // its own flush check, so this guards bound taps only.
                if (service != null && !e.file.exists()) {
                    Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "dl_file_gone"), Toast.LENGTH_SHORT).show();
                    loadList();
                    return;
                }
                if (service == null || !e.file.getAbsolutePath().equals(service.getCurrentUrl()))
                    trimLibQueueAhead(e.file.getAbsolutePath());
                if (service == null) {
                    // v1.62: queue instead of dropping the tap (see
                    // pendingDlPath above) — the bind usually lands
                    // a moment later, and the tap used to be lost.
                    pendingDlPath = e.file.getAbsolutePath();
                    pendingDlTitle = e.title;
                    pendingDlRestart = false;
                    return;
                }
                String path = e.file.getAbsolutePath();
                if (path.equals(service.getCurrentUrl())) {
                    service.toggle();
                    saveLastLibrary(path, e.title);
                } else {
                    // v1.90: same resume rule as the Room's list tap —
                    // a file paused earlier (then stopped, or after
                    // an app restart) used to restart from 0 even
                    // though its saved position was still in prefs.
                    // v1.93: via savedPosForFile, so a spot saved
                    // under the talk's original URL (streamed from
                    // the Room) counts too, not just the file path.
                    saveLastLibrary(path, e.title);
                    clearLibTrail13(); // v8.13
                    service.play(path, e.title, savedPosForFile(path));
                }
                adapter.notifyDataSetChanged();
                // v2.96: premium row-tap play glide — every other
                // library play-start path already glides its row
                // into view (auto-advance / notification Next
                // v2.63, play-all / random / queued pre-bind
                // v2.95), but a direct row tap played with no
                // scroll at all, so a row tapped while only half
                // visible at the list's edge stayed half-cut
                // under its new pulse, unlike every other start.
                // The tapped row now glides into view with the
                // same glideToDlPath; a fully visible row is a
                // no-op scroll, and the toggle branch glides too
                // so a half-visible playing row always reads
                // whole. Visual only.
                glideToDlPath(path);
            }
        });
        // v4.17: long-press a library row restarts that file from
        // the beginning — a tap resumes from the saved spot
        // (v1.90), so replaying a favourite passage from 0 meant
        // seeking by hand. Same morph + glide as the tap; the
        // playing row's long-press seeks to 0 and resumes if
        // paused, mirroring the Room Play long-press (v4.13).
        // Before the service binds, the restart queues like a tap
        // (pendingDlRestart) instead of dropping.
        dlListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                // v4.20: the restart long-press was morph-only, like
                // the row tap above — it now blips too, so restarting
                // a file sounds like every other long-press.
                // v4.22: same in-place keyboard rule as the row tap.
                hideKeyboard(v);
                Fx.tap(DownloadedActivity.this);
                Fx.morph(v);
                // v4.58: queue-only mapping, same stale-slot guard
                // as the row tap above.
                Entry e = libEntryAtDisplay(pos);
                if (e == null) return true;
                if (service == null) {
                    pendingDlPath = e.file.getAbsolutePath();
                    pendingDlTitle = e.title;
                    pendingDlRestart = true;
                    return true;
                }
                String path = e.file.getAbsolutePath();
                if (path.equals(service.getCurrentUrl())) {
                    // v10.54: a long-press restart while this file was still
                    // buffering used to seekTo(0) + resume() — but seekTo drops
                    // a 0 while unprepared (the v1.69 ms>0 guard, so the stored
                    // resume spot survives) and resume() no-ops on a buffering,
                    // unheld player, so the file opened at its saved spot
                    // instead of from the beginning. The Room's long-press
                    // already restarts a buffering talk through a fresh play
                    // (v4.13); this mirror missed that branch. Restart through
                    // a fresh play here too — play() re-prepares with a 0 seek.
                    if (service.isBuffering()) service.play(path, e.title, 0);
                    else {
                        service.seekTo(0);
                        if (!service.isPlaying()) service.resume();
                    }
                    saveLastLibrary(path, e.title);
                } else {
                    // v11.65: same deleted-after-scan guard as the row
                    // tap — a stale restart pick used to die with the
                    // generic "check internet" toast and anchor a dead
                    // file. The playing-file branch above needs none:
                    // a sounding file exists by definition.
                    if (!e.file.exists()) {
                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "dl_file_gone"), Toast.LENGTH_SHORT).show();
                        loadList();
                        return true;
                    }
                    saveLastLibrary(path, e.title);
                    clearLibTrail13(); // v8.13
                    service.play(path, e.title, 0);
                }
                adapter.notifyDataSetChanged();
                glideToDlPath(path);
                return true;
            }
        });
        if (hasReadPermission()) {
            loadList();
        } else {
            requestReadPermission();
        }
        Intent svc = new Intent(this, AudioService.class);
        startService(svc);
        bound = bindService(svc, connection, Context.BIND_AUTO_CREATE);
        refreshHandler.post(refreshRunnable);
    }

    // v1.60: theme was only applied in onCreate — changing day/night
    // in Settings while the library sat in the back stack left stale
    // colours (and stale row colours) until the screen was recreated.
    @Override
    protected void onResume() {
        super.onResume();
        // v1.81: count this screen as on top (see AppForeground).
        AppForeground.activityResumed();
        // v8.55: the share chooser is gone by now — a later onPause
        // is a real leave again (Room/Talks suppress pattern).
        suppressDlSharePause = false;
        // v11.92: same — the permission dialog is gone by now.
        suppressPermPause92 = false;
        // v7.79: publish this screen as the top library (see topLib).
        topLib = true;
        // v1.90: same live keep-screen-on read as the Room/Talks
        // onResume — a Settings toggle made while this screen sat
        // in the back stack applies on return, not on recreation.
        if (Prefs.keepScreenOn(this))
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // v3.01: premium library return theme/font dip, mirroring
        // Main v2.98 / Talks v2.99 / Room v3.00 — changing Day/Night
        // or the font size in Settings and coming back to the
        // library used to recolour + resize the whole screen in one
        // frame, while the other screens dip. A real change since
        // applyTheme last ran now dips with the existing
        // Fx.themeDip (140ms down to 0.55, swap at the dip, 260ms
        // rise to exactly alpha 1). Guards: the onCreate pass just
        // set cachedDlNight/cachedDlFont, so the first onResume and
        // rotations never dip (no diff); a return with nothing
        // changed stays instant, so back-stack navigation never dips
        // pointlessly; the dip's swap is applyTheme itself plus the
        // search-box sizing, and it rebinds the rows — the row bind
        // reads the CACHED theme, so without the rebind the rows
        // would keep the old colours until the next scroll bind;
        // applyTheme touches only colour/size, never alpha or
        // translation, so it never fights the fresh-open header
        // entrance (v2.19) or the return-change row pour (v2.57),
        // both of which live on children while the dip lives on
        // the root; the dip's root.animate().cancel() touches no
        // child animation.
        android.view.View dlRoot = findViewById(R.id.root);
        // v11.45: same language-dip gap as the Talks chooser —
        // applyTheme below always re-read the labels, so nothing
        // went stale, but a language pick swapped instantly while
        // Main/Room dipped. Now a language change dips too,
        // mirroring Main v3.84 / Room v3.86; the null guard keeps
        // the first onResume and rotations dip-free, and the dip's
        // swap is applyTheme itself, which re-syncs cachedDlLang.
        if ((Prefs.isNight(this) != cachedDlNight
                || Prefs.fontSize(this) != cachedDlFont
                || (cachedDlLang != null && !Prefs.lang(this).equals(cachedDlLang))) && dlRoot != null) {
            Fx.themeDip(dlRoot, new Runnable() {
                @Override public void run() {
                    applyTheme();
                    applyDlSearchSize();
                    if (adapter != null) adapter.notifyDataSetChanged();
                }
            });
        } else {
            applyTheme();
            applyDlSearchSize();
        }
        // v1.62: re-read the folder on return — a download that
        // finished while this screen sat in the back stack (or a
        // permission granted later in system settings) never appeared
        // until a sort/search forced a reload. The search text, sort
        // choice and scroll position survive loadList (see v1.51/55).
        // v1.72: that reload used to jump the list back to the top on
        // every return — it ran right after onCreate and undid the
        // v1.55 rotation scroll restore too. Keep the position across
        // the reload (guarded, so a shrunk list never over-scrolls).
        int keepScroll = dlListView != null ? dlListView.getFirstVisiblePosition() : -1;
        // v1.74: same notification rebuild as the Room's onResume —
        // a notifications toggle made in Settings while a file sat
        // paused left the old card until the next play/pause tap.
        if (service != null) service.refreshNotification();
        else if (AudioService.liveSession) {
            // v10.32: unbound leg of the v1.74 onResume card refresh — the
            // Room's v10.31 leg closed its own onResume gap, but the
            // library's copy is bound-only with NO bind-callback
            // refresh at all (its onServiceConnected syncs settings
            // and glides, never rebuilds the card), so the refresh
            // was missed in BOTH unbound windows: a fresh open's
            // onResume runs before onServiceConnected fires, and
            // the library lives on after onServiceDisconnected
            // (service death, no rebind until recreation) — a
            // Settings notification toggle made in either window
            // left the old card until the next tap, and the
            // v10.19 receiver leg never fires here (no download
            // completion on a plain return). The no-bind intent
            // is the v10.03 shape, liveSession-guarded, never
            // starts a dead service; the handler stopSelfs when
            // trackless, and a rebuild with no toggle change
            // paints the identical card. No new UI, no toast.
            try {
                android.content.Intent rsv1032 = new android.content.Intent(DownloadedActivity.this, AudioService.class);
                rsv1032.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                startService(rsv1032);
            } catch (Exception ignored) {}
        }
        // v1.80: same live Settings sync as the Room's onResume —
        // the bind callback above covers a fresh bind, this covers
        // returning from Settings with the service already bound.
        syncLiveSettings();
        // v2.57: arm the return-change entrance only on a real
        // return (paused earlier, list already loaded once) — the
        // onCreate-then-onResume pass has dlWasPaused false, so a
        // fresh open keeps its own first-load cascade (v2.12).
        if (dlWasPaused && dlListLoaded) { dlReturnChange = true; dlReturnGlidePending = true; }
        dlWasPaused = false;
        loadList();
        if (keepScroll > 0 && keepScroll < libDisplayCount() && dlListView != null)
            dlListView.setSelection(keepScroll);
        if (adapter != null) adapter.notifyDataSetChanged();
        // v3.96: return-glide (see tryDlReturnGlide) — runs after the
        // keep-scroll restore above, so it only overrides that
        // position when the playing file actually changed.
        tryDlReturnGlide();
        // v1.71: restart the 1s refresh (see onPause) — remove first
        // so the onCreate post plus this one never double up the loop.
        refreshHandler.removeCallbacks(refreshRunnable);
        refreshHandler.post(refreshRunnable);
        // v10.09: listen for download completions while foreground —
        // card-only hook, the Main v10.08 leg for this screen. Audit
        // result: the list, footer and strip are already live here
        // (v4.00 signature reload, v4.27 progress scan, 1s tick),
        // so the ONLY stale surface was the shade/lock-screen card
        // of a PAUSED sounding talk — the service's 15s card chain
        // re-posts only while playing or buffering (v1.83), never
        // while paused, so a download landing for the paused talk
        // left the card on ⏳ until the next tap (same class as the
        // Main/Talks/Room paused-card staleness). Any completion
        // fires it (the broadcast is device-wide), but the card send
        // is gated on the finished job being the sounding talk, so
        // unrelated system downloads never re-post the card and
        // never start a dead service (liveSession guard, v10.03
        // shape). No toast — the card's own mark flipping ⏳→⬇
        // is the feedback. No loadList here: the v4.00 tick reloads
        // the list on its own within ~2s of the landing.
        if (!libDlCompleteRegistered) {
            if (libDlCompleteReceiver == null) {
                libDlCompleteReceiver = new android.content.BroadcastReceiver() {
                    @Override public void onReceive(android.content.Context c, android.content.Intent in) {
                        // v12.30: a FAILED job used to die silently here — this receiver
                        // never toasted at all (its own comment said "No toast") and the
                        // list simply never gained the file (the v4.00 tick reloads on its
                        // own within ~2s), so a listener who queued a download (Wi-Fi wait,
                        // then a network drop mid-file) saw nothing land and heard no word
                        // why (same hole Talks v12.29 closed on its screen, Room v12.30 on
                        // its own). A failure now toasts "download_failed" + the talk title,
                        // the same string shape as the enqueue catch. Separate try, FIRST in
                        // onReceive: the v10.19 prune leg below is liveSession-gated and the
                        // card-sync block returns early on !liveSession, but a download can
                        // fail while nothing plays, so the toast is not gated on a live
                        // session. Gated on the source URL being a talk this app queued
                        // (dlMap — no talk list lives on this screen), so an unrelated system
                        // download failing while this screen is open never toasts here; the
                        // prune below touches dlQueued only, never dlMap, so the gate reads
                        // true whenever this block runs. Title falls back COLUMN_TITLE -> URL.
                        // The broadcast fires once per job, so the toast fires once. Success
                        // toasts nothing — the row appearing in the list is the feedback.
                        try {
                            long failId = in.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                            if (failId >= 0) {
                                android.app.DownloadManager dmFail = (android.app.DownloadManager)
                                        getSystemService(android.content.Context.DOWNLOAD_SERVICE);
                                android.database.Cursor curFail = dmFail.query(
                                        new android.app.DownloadManager.Query().setFilterById(failId));
                                if (curFail != null) {
                                    try {
                                        if (curFail.moveToFirst()) {
                                            int stColFail = curFail.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS);
                                            if (stColFail >= 0 && curFail.getInt(stColFail) == android.app.DownloadManager.STATUS_FAILED) {
                                                int srcColFail = curFail.getColumnIndex(android.app.DownloadManager.COLUMN_URI);
                                                String failUrl = srcColFail >= 0 ? curFail.getString(srcColFail) : null;
                                                if (failUrl != null && failUrl.length() > 0) {
                                                    boolean oursFail = false;
                                                    try {
                                                        org.json.JSONObject mapFail = new org.json.JSONObject(
                                                                getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlMap", "{}"));
                                                        oursFail = mapFail.has(failUrl);
                                                    } catch (Exception ignored) {}
                                                    if (oursFail) {
                                                        String failTitle = null;
                                                        int titleColFail = curFail.getColumnIndex(android.app.DownloadManager.COLUMN_TITLE);
                                                        if (titleColFail >= 0) failTitle = curFail.getString(titleColFail);
                                                        if (failTitle == null || failTitle.length() == 0) failTitle = failUrl;
                                                        android.widget.Toast.makeText(DownloadedActivity.this,
                                                                Lang.t(DownloadedActivity.this, "download_failed") + failTitle,
                                                                android.widget.Toast.LENGTH_LONG).show();
                                                    }
                                                }
                                            }
                                        }
                                    } finally { curFail.close(); }
                                }
                            }
                        } catch (Exception ignored) {}
                        // v10.19: paused-card download-mark sync, Downloaded
                        // prune-drop leg (see pruneLibQueuedDownloads1019
                        // above) — this receiver fires for FAILED jobs
                        // too, but the v10.09 leg below covers only a
                        // job that LANDS, so a failed job's entry (and
                        // a PAUSED sounding talk's hourglass) survived
                        // until some Room/Talks rescan pruned it.
                        // Prune first, then refresh the card only when
                        // a dropped URL is the sounding talk (v10.17/
                        // v10.18 guard shape, read fresh). A landed
                        // job may repaint twice (this prune + the
                        // v10.09 leg below); both paint the identical
                        // card. liveSession guard: never starts a dead
                        // service. No loadList, no toast here.
                        if (AudioService.liveSession) {
                            try {
                                java.util.Set<String> dropped1019 = pruneLibQueuedDownloads1019();
                                if (!dropped1019.isEmpty()) {
                                    android.content.SharedPreferences rsp1019 =
                                            getSharedPreferences("dhamma", MODE_PRIVATE);
                                    String lastUrl1019 = rsp1019.getString("lastUrl", "");
                                    String libPath1019 = rsp1019.getString("lastLibPath", "");
                                    org.json.JSONObject map1019 = new org.json.JSONObject(rsp1019.getString("dlMap", "{}"));
                                    boolean cardTalk1019 = false;
                                    for (String droppedUrl1019 : dropped1019) {
                                        if (droppedUrl1019.equals(lastUrl1019)) { cardTalk1019 = true; break; }
                                        if (libPath1019.length() > 0) {
                                            String fn1019 = map1019.optString(droppedUrl1019, "");
                                            if (fn1019.length() > 0 && fn1019.equals(new java.io.File(libPath1019).getName())) { cardTalk1019 = true; break; }
                                        }
                                    }
                                    if (cardTalk1019) {
                                        android.content.Intent rsv1019 = new android.content.Intent(DownloadedActivity.this, AudioService.class);
                                        rsv1019.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                                        startService(rsv1019);
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                        // Card sync: the finished job's source URL is
                        // the DownloadManager COLUMN_URI (fallback:
                        // the landed Dhamma/ basename reverse-mapped
                        // through dlMap — no talk list on this screen,
                        // so no mapped-name fallback like Talks has).
                        // Guard reads prefs fresh at fire time, NOT
                        // any onCreate snapshot: room = source ==
                        // lastUrl; library = dlMap[source] file ==
                        // lastLibPath file (v10.03 shape).
                        try {
                            if (!AudioService.liveSession) return;
                            String srcUrl = null;
                            long doneId = in.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                            if (doneId >= 0) {
                                android.app.DownloadManager dmDone = (android.app.DownloadManager)
                                        getSystemService(android.content.Context.DOWNLOAD_SERVICE);
                                android.database.Cursor cur = dmDone.query(
                                        new android.app.DownloadManager.Query().setFilterById(doneId));
                                if (cur != null) {
                                    try {
                                        if (cur.moveToFirst()) {
                                            int srcCol = cur.getColumnIndex(android.app.DownloadManager.COLUMN_URI);
                                            int uriCol = cur.getColumnIndex(android.app.DownloadManager.COLUMN_LOCAL_URI);
                                            srcUrl = srcCol >= 0 ? cur.getString(srcCol) : null;
                                            if (srcUrl == null || srcUrl.length() == 0) {
                                                String localDone = uriCol >= 0 ? cur.getString(uriCol) : null;
                                                if (localDone != null && localDone.contains("Dhamma/")) {
                                                    String landed = localDone.substring(localDone.lastIndexOf('/') + 1);
                                                    android.content.SharedPreferences rsp0 =
                                                            getSharedPreferences("dhamma", MODE_PRIVATE);
                                                    org.json.JSONObject map0 = new org.json.JSONObject(rsp0.getString("dlMap", "{}"));
                                                    java.util.Iterator<String> keys0 = map0.keys();
                                                    // v11.83: exactly-one claimant, never the
                                                    // first match (the v8.43–v8.50 rule) — a
                                                    // dual-claimed file used to reverse-map to
                                                    // the wrong talk here. Ambiguous means no
                                                    // card refresh for this completion, never
                                                    // a wrong one.
                                                    int claimB7 = 0;
                                                    while (keys0.hasNext()) {
                                                        String k = keys0.next();
                                                        if (landed.equals(map0.optString(k, ""))) { srcUrl = k; claimB7++; }
                                                    }
                                                    if (claimB7 != 1) srcUrl = null;
                                                }
                                            }
                                        }
                                    } finally { cur.close(); }
                                }
                            }
                            if (srcUrl == null || srcUrl.length() == 0) return;
                            android.content.SharedPreferences rsp =
                                    getSharedPreferences("dhamma", MODE_PRIVATE);
                            boolean cardTalk = srcUrl.equals(rsp.getString("lastUrl", ""));
                            if (!cardTalk) {
                                String libPath = rsp.getString("lastLibPath", "");
                                if (libPath.length() > 0) {
                                    String fn = new org.json.JSONObject(rsp.getString("dlMap", "{}")).optString(srcUrl, "");
                                    cardTalk = fn.length() > 0 && fn.equals(new java.io.File(libPath).getName());
                                }
                            }
                            if (cardTalk) {
                                android.content.Intent rsv = new android.content.Intent(DownloadedActivity.this, AudioService.class);
                                rsv.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                                startService(rsv);
                            }
                        } catch (Exception ignored) {}
                    }
                };
            }
            try {
                android.content.IntentFilter f = new android.content.IntentFilter(
                        android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE);
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(libDlCompleteReceiver, f, android.content.Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(libDlCompleteReceiver, f);
                }
                libDlCompleteRegistered = true;
            } catch (Exception ignored) {}
        }
    }

    // v1.71: same class as the Room's poll — the 1s refresh ran even
    // with the library in the back stack (only onDestroy stopped it).
    @Override
    protected void onPause() {
        super.onPause();
        // v7.79: no library is on top any more (see topLib) — the
        // service may advance a library session again, and the
        // poll re-syncs on return if it did.
        topLib = false;
        // v3.96: anchor the return-glide on the track playing when
        // the library is left — a track change landing between the
        // last 1s poll tick and this pause (e.g. a row tap followed
        // at once by the Player nav) must still count as the
        // leave-time track, or the return would glide to a file
        // the library already showed when it was left.
        if (service != null && service.hasTrack()) {
            String anchor = service.getCurrentUrl();
            if (anchor != null && anchor.length() > 0) lastSeenDlPlayingPath = anchor;
        }
        // v2.57: mark a real pause for the return-change entrance
        // (see onResume) — a rotation recreates the activity, so
        // this marker never survives into a fresh instance.
        dlWasPaused = true;
        // v1.81: report the leave + schedule the background check
        // (see AppForeground) — the immediate guard below already
        // pauses on Home from here; the check covers the rest.
        AppForeground.activityPaused();
        // v8.55: a share chooser gets the Room/Talks 10s delay, not
        // 600ms — a slow share pick must not pause mid-pick, while
        // leaving through a target app still pauses within 10s.
        // v11.92: same 10s delay for the permission dialog.
        if (suppressDlSharePause || suppressPermPause92) AppForeground.scheduleBackgroundCheck(this, 10000);
        else AppForeground.scheduleBackgroundCheck(this);
        refreshHandler.removeCallbacks(refreshRunnable);
        // v10.09: stop listening while hidden — onResume re-registers.
        if (libDlCompleteRegistered && libDlCompleteReceiver != null) {
            try { unregisterReceiver(libDlCompleteReceiver); } catch (Exception ignored) {}
            libDlCompleteRegistered = false;
        }
        // v2.25: stop the playing-row pulse with the poll — a
        // detached view must never keep breathing, and onResume's
        // reload rebinds the playing row, which restarts it.
        if (dlPulse != null) dlPulse.cancel();
        dlPulse = null;
        // v3.14: onPause still resets instantly (like the Room's
        // v3.13 reset) but cancels a running stop-fade first, so no
        // mid-fade value is ever written after the reset.
        // v3.18: also clears a running pulse-start settle, so its
        // end action never starts a loop on a detached view.
        // v3.19: view + flag cleared BEFORE the cancel, or a
        // cancelled settle's end action fires with its guards
        // still passing and starts a loop on the detached view.
        if (dlPulseView != null) { android.view.View pvPause = dlPulseView; dlPulseView = null; dlPulseSettling = false; pvPause.animate().cancel(); pvPause.setAlpha(1f); }
        // v1.73: background playback off pauses here too — the Room
        // has had this guard since v1.71, but leaving the library
        // kept playing even with the setting off. Same guard: not on
        // finish, not on rotation.
        // v1.75: same buffering-leave gap as the Room — a file
        // still preparing when the library is left with
        // background playback off used to start playing anyway.
        // v8.55: ...unless the pause is only the share chooser
        // opening (see suppressDlSharePause) — a transient sheet is
        // not leaving the library, and background-off must not stop
        // the file the listener is about to share (Room v1.74 rule).
        // v11.92: same for the permission dialog (see
        // suppressPermPause92) — answering the prompt is not leaving.
        // v10.67: same toggle as the Room leave guard — a held
        // buffering file already opens paused, so the guard's pause()
        // would un-park it on leaving the library. Skip held talks.
        if (!suppressDlSharePause && !suppressPermPause92 && !Prefs.backgroundEnabled(this) && service != null
                && (service.isPlaying() || service.isBuffering()) && !service.isHoldPaused()
                && !isFinishing() && !isChangingConfigurations()) {
            service.pause();
        }
    }

    // v1.94: system Back faded nowhere on this screen, while Talks
    // and Room have faded since v1.77 — same soft fade here.
    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.nav_in, R.anim.nav_out);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // v13.08: detach the liquid-glass surfaces — the drawable is
        // the view's background and its callback pins the dead view +
        // its destroyed activity in GlassBlur's static map on every
        // rotation (the v13.05 weak view ref did not fix this).
        GlassBlur.detach(findViewById(R.id.nowStrip));
        GlassBlur.detach(findViewById(R.id.searchDl));
        refreshHandler.removeCallbacks(refreshRunnable);
        // v2.25: same pulse stop as onPause (destroy without pause
        // must not leak the infinite animator either).
        if (dlPulse != null) dlPulse.cancel();
        dlPulse = null;
        // v3.14: cancel a running stop-fade with the pulse, so a
        // destroy mid-fade never writes to a detached view.
        // v3.18: also clears a running pulse-start settle.
        // v3.19: view + flag cleared BEFORE the cancel, same
        // cancelled-settle end-action guard as onPause.
        if (dlPulseView != null) { android.view.View pvDestroy = dlPulseView; dlPulseView = null; dlPulseSettling = false; pvDestroy.animate().cancel(); }
        // v1.85: same guard as the Room's onDestroy — unbinding after
        // the service already died (onServiceDisconnected does not
        // clear bound) threw instead of ending quietly.
        if (bound) {
            try { unbindService(connection); } catch (Exception ignored) {}
            bound = false;
        }
    }

    private boolean hasReadPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestReadPermission() {
        // v11.92: arm the pause suppress before the system dialog
        // opens (see suppressPermPause92) — both callers funnel here.
        suppressPermPause92 = true;
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{android.Manifest.permission.READ_MEDIA_AUDIO}, 2);
        } else {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 2);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == 2) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                // v2.83: arm the grant fade (see loadList) before
                // the reload, so the permission-denied empty word
                // (faded in by v2.64) can fade out instead of
                // snapping away when the files appear.
                dlPermGrantFade = true;
                loadList();
            } else {
                Toast.makeText(this, Lang.t(this, "dl_perm_need"), Toast.LENGTH_LONG).show();
                // v2.64: premium permission-denied empty-state fade —
                // search (v2.51) and delete-to-empty (v2.59) faded
                // the empty word, but a denied audio-permission
                // answer still snapped it VISIBLE in one frame
                // beside the permission toast, the last empty-view
                // path without motion. A GONE -> VISIBLE empty view
                // now fades in with the existing Fx.fadeIn (250ms,
                // alpha-only); an already-visible view just resets
                // to full alpha, so a repeated denial never
                // flickers. Toast text and the view itself are
                // untouched; no new element. Visual only.
                // v11.73: name the real problem — the word used to stay the layout default
                // ("nothing downloaded yet") on a denial, a lie on a phone that has downloads.
                // applyDlFilter's empty branch below keeps this text on every reload while the
                // permission is missing; the tap retry is armed in onCreate.
                android.widget.TextView emptyDeny = findViewById(R.id.emptyText);
                if (emptyDeny != null) emptyDeny.setText(Lang.t(this, "dl_perm_need"));
                if (emptyDeny != null) {
                    if (emptyDeny.getVisibility() != android.view.View.VISIBLE) {
                        emptyDeny.setVisibility(android.view.View.VISIBLE);
                        Fx.fadeIn(emptyDeny, 250);
                    } else if (emptyDeny.getAlpha() >= 0.999f) {
                        // v3.62: route through Fx.cancelFade (see
                        // Fx.fadeOut guard) — a raw cancel here could
                        // let a running fadeOut's end action hide the
                        // word the denial just showed.
                        Fx.cancelFade(emptyDeny);
                        emptyDeny.setAlpha(1f);
                    } else {
                        // v3.56: premium denied-empty re-appearance continuity — the
                        // v3.53 Talks/Room retry fix, applied to the one empty-view
                        // show branch it never reached. The v2.64 already-visible
                        // branch above cancelled whatever fade was running and set
                        // alpha straight to 1, but a denial landing inside the
                        // 200ms v2.80/v2.83 empty-view fade-out still finds the
                        // view VISIBLE (fadeOut only goes GONE at the end), so a
                        // half-faded empty word snapped back to full in one
                        // frame. A settled word (alpha ~1, a repeated denial)
                        // keeps the old instant reset with no pointless fade,
                        // while a mid-fade word now glides back to 1 over
                        // 250ms (decelerate) on the same ViewPropertyAnimator
                        // channel Fx.fadeOut uses, so a later fade-out cancels
                        // the glide and fades from its current alpha, and a
                        // further denial re-glides from wherever it sits.
                        // No end action, so a cancel can never strand the
                        // view; the GONE branch, the fade-in duration and
                        // the toast are untouched. Visual only.
                        // v3.62: route through Fx.cancelFade so the
                        // glide really runs from the mid-fade alpha
                        // instead of 1->1 on a view a fired fadeOut
                        // end action just hid.
                        Fx.cancelFade(emptyDeny);
                        emptyDeny.animate().alpha(1f).setDuration(250)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    }
                }
            }
        }
    }

    // v11.55: filenames in Downloads/Dhamma currently owned by a
    // live DownloadManager job (RUNNING/PAUSED/PENDING), so the
    // library lists only finished files. Destination match first
    // (the local URI, percent-decoded, under Dhamma/); jobs whose
    // local URI is not a plain file path fall back to the Room's
    // dlMap source-URL -> filename mapping, the same two legs
    // scanDlProgress uses to count "ours" jobs.
    private java.util.Set<String> inProgressDlFilenames() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        try {
            java.util.Map<String, String> dlMap = new java.util.HashMap<String, String>();
            try {
                org.json.JSONObject map = new org.json.JSONObject(
                        getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlMap", "{}"));
                java.util.Iterator<String> keys = map.keys();
                while (keys.hasNext()) {
                    String u = keys.next();
                    dlMap.put(u, map.optString(u, ""));
                }
            } catch (Exception ignored) {}
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            android.database.Cursor c = dm.query(new android.app.DownloadManager.Query());
            if (c != null) {
                try {
                    int statusCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS);
                    int uriCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_LOCAL_URI);
                    int srcCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_URI);
                    while (c.moveToNext()) {
                        int st = statusCol >= 0 ? c.getInt(statusCol) : -1;
                        if (st != android.app.DownloadManager.STATUS_RUNNING
                                && st != android.app.DownloadManager.STATUS_PAUSED
                                && st != android.app.DownloadManager.STATUS_PENDING) continue;
                        String local = uriCol >= 0 ? c.getString(uriCol) : null;
                        String src = srcCol >= 0 ? c.getString(srcCol) : null;
                        boolean added = false;
                        if (local != null) {
                            String dec = android.net.Uri.decode(local);
                            if (dec != null && dec.contains("Dhamma/")) {
                                int slash = dec.lastIndexOf('/');
                                if (slash >= 0 && slash + 1 < dec.length()) {
                                    out.add(dec.substring(slash + 1));
                                    added = true;
                                }
                            }
                        }
                        if (!added && src != null && dlMap.containsKey(src)) {
                            String fn = dlMap.get(src);
                            if (fn != null && fn.length() > 0) out.add(fn);
                        }
                    }
                } finally { c.close(); }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void loadList() {
        entries.clear();
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "Dhamma");
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                List<File> mp3s = new ArrayList<File>();
                // v11.55: a talk still downloading is NOT a downloaded
                // talk yet — DownloadManager writes straight into
                // Downloads/Dhamma, so the partial file used to sit
                // in this list as if finished: tapping it played a
                // truncated file that then "ended" early (and could
                // auto-advance + earn a listened mark), its partial
                // bytes inflated the footer total, and Play All /
                // Random / Next could all pick it. In-progress
                // jobs (RUNNING/PAUSED/PENDING) are now excluded
                // here; the footer still names them via the v4.27
                // ⏳ line, and the tick's directory signature
                // still includes their growing lengths, so the
                // finished file appears on the first reload after
                // the download lands. A DM query failure fails
                // open (empty set = the old behaviour), never an
                // empty library.
                java.util.Set<String> inProg = inProgressDlFilenames();
                for (File f : files) {
                    if (f.isFile() && f.getName().toLowerCase().endsWith(".mp3")
                            && !inProg.contains(f.getName())) mp3s.add(f);
                }
                Collections.sort(mp3s, new Comparator<File>() {
                    @Override public int compare(File a, File b) {
                        return Long.compare(b.lastModified(), a.lastModified());
                    }
                });
                for (File f : mp3s) entries.add(new Entry(f));
            }
        }
        if (dlSortMode == 1) {
            Collections.sort(entries, new Comparator<Entry>() {
                @Override public int compare(Entry a, Entry b) { return a.title.compareToIgnoreCase(b.title); }
            });
        } else if (dlSortMode == 2) {
            Collections.sort(entries, new Comparator<Entry>() {
                @Override public int compare(Entry a, Entry b) {
                    int bySize = Long.compare(b.len, a.len);
                    return bySize != 0 ? bySize : a.title.compareToIgnoreCase(b.title);
                }
            });
        }
        // v2.59: when a delete-all armed dlEmptyFade, leave the empty
        // view GONE here so applyDlFilter below still sees it as
        // GONE -> VISIBLE and fades it in; it shows it itself.
        android.view.View emptyV = findViewById(R.id.emptyText);
        // v2.64: a permission-denied fade (above) running when a
        // reload lands must not strand the view half-transparent:
        // cancel it and reset alpha before this direct set, the
        // same hiding hygiene as v2.51. The dlEmptyFade path
        // below is unaffected — applyDlFilter starts its own fade.
        // v2.83: premium permission-grant empty-view fade-out,
        // the last path in the empty-view motion series without
        // one (search fade-out since v2.80, fade-ins since
        // v2.51/v2.59/v2.64). Granting the audio permission after
        // a denial used to snap the denied empty word GONE in
        // this direct set, in the same frame the files appeared,
        // while a search match fades it out (v2.80). When the
        // grant flag is armed and the reload really found files
        // under a visible empty word, leave the view alone here
        // so applyDlFilter's else branch fades it out with the
        // existing Fx.fadeOut (200ms, alpha-only); fadeOut
        // cancels any running denied fade-in itself, so a
        // grant landing mid-fade never strands it. Every other
        // path keeps the direct set: first loads, rotations,
        // sort/delete/return reloads never arm the flag and
        // never fade anything, and a grant that finds no files
        // still shows the word instantly as before. The flag
        // is consumed on every loadList pass, so a later reload
        // can never inherit it.
        boolean permGrantFade = dlPermGrantFade;
        dlPermGrantFade = false;
        if (permGrantFade && !entries.isEmpty()
                && emptyV.getVisibility() == View.VISIBLE) {
            // leave visible for applyDlFilter's v2.80 fade-out
        } else if (!dlEmptyFade && emptyV.getVisibility() == View.VISIBLE
                && emptyV.getAlpha() < 0.999f) {
            // v3.61: premium loadList empty-view re-appearance continuity — the
            // v3.58 show-branch fix applied to the reload path it never reached.
            // A reload (sort tap, return, onResume, a download finishing) landing
            // inside the 200ms v2.80 fade-out used to hit the else branch below,
            // which cancelled the fade and reset alpha straight to 1 before
            // applyDlFilter ever saw the mid-fade state — so the v3.58 glide
            // branches could never fire on a reload, and the word either snapped
            // back to full (still empty) or vanished mid-fade (rows matched).
            // A mid-fade word is now left alone here, exactly like the v2.83
            // grant path above, and applyDlFilter decides from its real state:
            // a still-empty word glides back to 1 via the v3.58 branch, a
            // matched-again word fades out afresh from its current alpha via
            // Fx.fadeOut (which cancels and restarts from that alpha, so no cut).
            // Settled words (alpha ~1, first loads, rotations, unchanged reloads)
            // keep the old instant reset with no pointless fade, and dlEmptyFade
            // still forces GONE below so its fade-in branch still sees a
            // GONE -> VISIBLE word. Visual only; visibility/text behaviour
            // of every other path is untouched.
        } else {
            emptyV.animate().cancel();
            emptyV.setAlpha(1f);
            if (dlEmptyFade) emptyV.setVisibility(View.GONE);
            else emptyV.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        }
        refreshLibMarkCache();
        // v7.45: adopt the tick snapshots on every load, so a
        // loadList that itself picked up a changed mark set
        // never earns a redundant tick rebind right after it.
        try {
            android.content.SharedPreferences pp45 =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            java.util.Set<String> fav45 = pp45.getStringSet("favTalks", null);
            java.util.Set<String> played45 = pp45.getStringSet("playedTalks", null);
            dlMarkFav45 = fav45 == null
                    ? new java.util.HashSet<String>()
                    : new java.util.HashSet<String>(fav45);
            dlMarkPlayed45 = played45 == null
                    ? new java.util.HashSet<String>()
                    : new java.util.HashSet<String>(played45);
        } catch (Exception ignored) {}
        refreshLibQueueCache(); // v4.57: prune the hand queue against the fresh shelf, same load point as the ★/✓ marks.
        // v4.95: rotation-restore guard for the library filter
        // view — the mirror of Main's v4.26 guard. The mode is
        // restored in onCreate before the cache exists, so the
        // check belongs here, on the first rebuilt cache: a
        // restored mode whose set emptied (favourites cleared,
        // everything listened, or files deleted while this screen
        // was dead) falls back to the full shelf instead of a
        // blank filtered one, and the hint follows the mode
        // either way.
        // v11.73: first load only — this guard used to run on EVERY loadList, so a
        // star-only / unlistened-only filter the listener deliberately engaged on an
        // empty set (see the cycle handler: "the filter still engages") was silently
        // cleared by the next sort/delete/return/rotation reload. The guard's own
        // purpose is the restore case (a set emptied while this screen was dead), which
        // only the first loadList of this instance can see; later loads leave the
        // listener's choice alone.
        if (!dlFilterGuardDone73) {
            dlFilterGuardDone73 = true;
            if (dlFilterMode == 1 && libFavFiles.isEmpty()) dlFilterMode = 0;
            if (dlFilterMode == 2 && allDlListened()) dlFilterMode = 0;
        }
        refreshDlFavHint();
        // v11.89: refresh the in-progress count on this load, not only on the
        // 1s tick — the empty view below reads dlProgCount, so a fresh open
        // onto an in-progress download used to say "nothing downloaded yet"
        // while the footer already showed ⏳.
        try { scanDlProgress(); } catch (Exception ignored) {}
        // v12.37: filter first, footer second — updateTotal's Up Next
        // line reads shownDl, but this used to run before applyDlFilter
        // rebuilt it, so a sort tap named the next file under the OLD
        // order, a return reload skipped a just-landed file, and a
        // delete named the deleted file itself, while Next already
        // played the fresh list (the tick's mark-adoption path at
        // v7.45 already runs them in this order). The delete/return
        // flags are snapshotted across applyDlFilter (which consumes
        // them for the row pour) and restored for updateTotal's fade
        // guard, then cleared — the v2.56/v2.58 footer fade still
        // fires exactly when it did, and no stale flag survives.
        boolean delCh37 = dlDeleteChange, retCh37 = dlReturnChange;
        applyDlFilter();
        dlDeleteChange = delCh37; dlReturnChange = retCh37;
        updateTotal();
        dlDeleteChange = false; dlReturnChange = false;
        // v1.55: first load after a rotation jumps back to the saved
        // scroll position instead of the top (consumed here, so later
        // loadList calls from sort/delete keep their own position).
        if (savedDlScroll >= 0 && savedDlScroll < libDisplayCount() && dlListView != null)
            dlListView.setSelection(savedDlScroll);
        savedDlScroll = -1;
        // v2.05: the list is populated from here on — a jump that
        // could not land before now knows its target is really
        // absent (see applyJumpOnce), instead of merely not loaded.
        dlListLoaded = true;
        // v2.12: premium library cascade — the last list surface
        // without one (Main grid since v2.07, Talks since v2.07,
        // Room sheet since v2.10). Same 45ms-step fade + rise via
        // Fx.staggerChildren, posted so it runs after this load's
        // layout pass; rows scrolled into view later are untouched,
        // and the flag is consumed here so onResume's reloads,
        // sort/delete reloads and rotations never replay it.
        if (dlFirstLoad) {
            dlFirstLoad = false;
            if (dlListView != null && !shownDl.isEmpty())
                Fx.staggerChildren(dlListView);
        }
        // v1.94: jump to the file the listener came to resume (see
        // jumpPath) — after the rotation restore above, so a fresh
        // open lands on the file and a rotation never re-jumps.
        applyJumpOnce();
        // v4.63: dead-library continue — the strip tap's
        // autoPlayPath plays the file from its saved spot on
        // this same load tail, so the listener lands listening,
        // not merely scrolled to the row.
        applyAutoPlayOnce();
        // v3.95: fresh-open glide to the playing file (no-op
        // before the bind — the bind callback retries it).
        tryDlFreshGlide();
        if (pendingDlNext) handleLibNext();
        if (pendingDlPrev) handleLibPrev(); // v4.23
        if (pendingDlQueue) openLibQueueView(); // v4.77
        // v4.00: adopt the directory signature this load just
        // scanned, so the tick's live refresh never re-fires for
        // a change this load already showed (sort/delete/return
        // reloads included) — only a genuinely newer change, held
        // steady for two ticks, reloads again.
        dlDirSig = dlDirSignature();
        dlDirPendingSig = null;
        dlDirPendingTicks = 0;
    }

    private void applyDlFilter() {
        shownDl.clear();
        // v1.51: space-insensitive match, same as the main list (v1.47)
        // and the chooser (v1.48) — typing a title without its spaces
        // now finds it here too.
        // v1.91: NFC-normalise both sides, like the main list
        // (v1.90) — Myanmar stacking/keystroke variants that look
        // identical but differ in code-point order now match here
        // too; space-insensitive match unchanged.
        // v10.71: digit-fold both sides (Fx.foldDigits) — a downloaded
        // title numbered in Myanmar digits now matches a Latin-digit
        // keystroke and vice versa, like the Main and Talks searches.
        // v10.72: flat match strips all whitespace (Fx.stripWhitespace),
        // not just the plain space — a non-breaking space in a title,
        // sayadaw name or pasted query used to survive the flat strip.
        String q = Fx.foldDigits(java.text.Normalizer.normalize(dlQuery,
                java.text.Normalizer.Form.NFC).toLowerCase());
        String qFlat = Fx.stripWhitespace(q);
        for (Entry e : entries) {
            // v4.95: library filter views — mode 1 keeps only ★
            // files, mode 2 keeps only unlistened files, both from
            // the v4.24 per-load mark cache instead of re-parsing
            // prefs per row. A typed query still narrows within
            // the view, exactly like Main's v4.19 filter; sort,
            // Play All, Random and the Next/Prev stepping all read
            // shownDl, so they follow the filter with no further
            // change.
            if (dlFilterMode == 1 && !libFavFiles.contains(e.file.getName())) continue;
            if (dlFilterMode == 2 && libListenedFiles.contains(e.file.getName())) continue;
            String t = Fx.foldDigits(java.text.Normalizer.normalize(e.title,
                    java.text.Normalizer.Form.NFC).toLowerCase());
            // v8.04: search also matches the sayadaw name — every
            // row visibly shows it in its subtitle (libSayadawMap,
            // built per load by refreshLibMarkCache before this
            // filter runs), yet the match above tested the title
            // only, so typing a sayadaw's name found nothing on
            // the shelf even though Main (name + group) and the
            // Talks chooser (title) both search what their rows
            // show. Same NFC + lowercase + space-insensitive
            // rules as the title test; a file with no recorded
            // sayadaw simply falls back to title-only, and the
            // filter modes, sort, Play All, Random and Next/Prev
            // all read shownDl, so they follow with no change.
            boolean sdMatch = false;
            if (q.length() > 0) {
                String sd = libSayadawMap.get(e.file.getName());
                if (sd != null && sd.length() > 0) {
                    String sdn = Fx.foldDigits(java.text.Normalizer.normalize(sd,
                            java.text.Normalizer.Form.NFC).toLowerCase());
                    sdMatch = sdn.contains(q)
                            || Fx.stripWhitespace(sdn).contains(qFlat);
                }
            }
            if (q.length() == 0 || t.contains(q)
                    || Fx.stripWhitespace(t).contains(qFlat) || sdMatch) shownDl.add(e);
        }
        // v2.49: premium filter-change entrance — the first library
        // load pours in (v2.12 cascade), but every search after it
        // used to snap the new results in instantly, the exact gap
        // Main's grid closed in v2.48. A real keystroke (this flag
        // is set only by the search watcher, and dlListLoaded is
        // only true after the first loadList) that actually changes
        // the shown set now resets the per-bind guard, so the new
        // rows pour in with the same fade + rise as the first load.
        // A keystroke that leaves the set unchanged never resets it,
        // so fast typing does not flicker; rotations restore before
        // the first load (dlListLoaded false) and sort/delete reloads
        // call this directly without the flag, so neither re-pours.
        // Visual only.
        if (dlSearchKeystroke && dlListLoaded && adapter != null) {
            StringBuilder kb = new StringBuilder();
            kb.append(shownDl.size());
            for (int i = 0; i < shownDl.size() && i < 3; i++) kb.append('|').append(shownDl.get(i).title);
            String key = kb.toString();
            if (!key.equals(lastDlShownKey)) {
                lastDlAnimPos = -1;
                lastDlShownKey = key;
                // v4.21: same top-of-results rule as Main's grid
                // (v4.21) — a keystroke that changes the shown set
                // returns the list to the top, so the first matches
                // never sit above the viewport. Consumed after the
                // notify below; sort/delete/return reloads never set
                // the flag, so their scroll positions stay put.
                dlSearchJumpTop = true;
            }
        }
        // v2.54: premium sort-change entrance — the search pour (v2.49)
        // closed the search gap, but a sort tap reordered the whole
        // list and the rows still snapped to their new places in one
        // frame, flatter than every other list change. A sort tap
        // that actually changes the shown order now resets the same
        // per-bind guard, so the reordered rows pour in with the
        // library cascade's own fade + rise (45ms steps, 300ms,
        // decelerate, via the getView guard below). The key is
        // order-sensitive (size + first 3 titles in order), so a
        // sort that leaves the order unchanged (one row, or an
        // already-sorted list) never replays; the flag is consumed
        // on this pass either way, so a later delete/onResume
        // reload can never inherit it, and an empty list never
        // leaves the guard armed for a later bind. Rotations
        // restore before the first load (dlListLoaded false) and
        // delete reloads never set the flag, so neither re-pours.
        // Visual only.
        if (dlSortChange) {
            dlSortChange = false;
            if (dlListLoaded && adapter != null && !shownDl.isEmpty()) {
                StringBuilder kb = new StringBuilder();
                kb.append(shownDl.size());
                for (int i = 0; i < shownDl.size() && i < 3; i++) kb.append('|').append(shownDl.get(i).title);
                String key = kb.toString();
                if (!key.equals(lastDlShownKey)) {
                    lastDlAnimPos = -1;
                    lastDlShownKey = key;
                }
            }
        }
        // v2.55: premium delete-change entrance — search (v2.49) and
        // sort (v2.54) pours closed their gaps, but a confirmed
        // single delete still snapped the remaining rows up into
        // their new places in one frame, flatter than every other
        // list change. A delete that actually shrinks the shown list
        // now resets the same per-bind guard, so the remaining rows
        // pour in with the library cascade's own fade + rise (45ms
        // steps, 300ms, decelerate, via the getView guard below).
        // The key is size + first 3 titles, so a delete that leaves
        // the shown list unchanged (impossible from a shown row's
        // own button, but guarded anyway) never replays; the flag
        // is consumed on this pass either way, so a later onResume
        // reload can never inherit it, and an emptied list never
        // leaves the guard armed for a later bind. Delete-all never
        // sets the flag (no rows left to pour; its empty view stays
        // instant per v2.51), and rotations restore before the
        // first load (dlListLoaded false), so neither re-pours.
        // Visual only.
        if (dlDeleteChange) {
            dlDeleteChange = false;
            if (dlListLoaded && adapter != null && !shownDl.isEmpty()) {
                StringBuilder kb = new StringBuilder();
                kb.append(shownDl.size());
                for (int i = 0; i < shownDl.size() && i < 3; i++) kb.append('|').append(shownDl.get(i).title);
                String key = kb.toString();
                if (!key.equals(lastDlShownKey)) {
                    lastDlAnimPos = -1;
                    lastDlShownKey = key;
                }
            }
        }
        // v2.57: premium return-change entrance — search (v2.49),
        // sort (v2.54) and delete (v2.55) pours closed their gaps,
        // but the onResume reload (v1.62) still snapped in a changed
        // list: a download that finished while this screen sat in
        // the back stack appeared at the top in one frame, flatter
        // than every other list change. A real return whose reload
        // actually changes the shown set now resets the same
        // per-bind guard, so the changed rows pour in with the
        // library cascade's own fade + rise (45ms steps, 300ms,
        // decelerate, via the getView guard below). The key is
        // size + first 3 titles, so a return that leaves the list
        // unchanged (Settings, Room play, plain back-stack return)
        // never replays; the flag is consumed on this pass either
        // way, so a later sort/delete reload can never inherit it,
        // and an emptied list never leaves the guard armed for a
        // later bind. Fresh opens (dlWasPaused false) and rotations
        // (new instance, marker false) never arm it. Visual only.
        if (dlReturnChange) {
            dlReturnChange = false;
            if (dlListLoaded && adapter != null && !shownDl.isEmpty()) {
                StringBuilder kb = new StringBuilder();
                kb.append(shownDl.size());
                for (int i = 0; i < shownDl.size() && i < 3; i++) kb.append('|').append(shownDl.get(i).title);
                String key = kb.toString();
                if (!key.equals(lastDlShownKey)) {
                    lastDlAnimPos = -1;
                    lastDlShownKey = key;
                }
            }
        }
        // v4.00: live-arrival entrance — the tick's live refresh
        // (see the field note) resets the same per-bind guard, so
        // a download that lands while the library is open pours
        // in with the library cascade's own fade + rise, exactly
        // like the v2.57 return pour for a back-stack download.
        // The key is size + first 3 titles, so a filtered-out
        // arrival (search active, new file not matching) leaves
        // the shown set unchanged and never re-pours; the flag
        // is consumed on this pass either way, so a later
        // sort/delete reload can never inherit it, and an
        // emptied list never leaves the guard armed for a later
        // bind. Visual only.
        if (dlLiveArrival) {
            dlLiveArrival = false;
            if (dlListLoaded && adapter != null && !shownDl.isEmpty()) {
                StringBuilder kb = new StringBuilder();
                kb.append(shownDl.size());
                for (int i = 0; i < shownDl.size() && i < 3; i++) kb.append('|').append(shownDl.get(i).title);
                String key = kb.toString();
                if (!key.equals(lastDlShownKey)) {
                    lastDlAnimPos = -1;
                    lastDlShownKey = key;
                }
            }
        }
        // v1.55: CRASH FIX — restoring a saved search in onCreate fires
        // this filter before the adapter exists (it is created later in
        // onCreate), so rotating with a library search typed crashed on
        // a null adapter. The list rebinds anyway on the first loadList.
        if (adapter != null) adapter.notifyDataSetChanged();
        if (dlSearchJumpTop) {
            dlSearchJumpTop = false;
            if (dlListView != null && dlListView.getFirstVisiblePosition() > 0)
                dlListView.setSelection(0);
        }
        // v2.02: a search matching nothing used to leave a blank
        // list with no word — loadList only shows the empty view
        // when there are no downloads at all, so a filtered-empty
        // list read as a broken list. Reuse the existing empty view
        // (text only, no new element): the no-match word while the
        // filter matches nothing, the original text when there are
        // truly no downloads, hidden as soon as a row matches again
        // (clearing the search or typing a matching letter restores
        // the list through this same path).
        android.widget.TextView empty = findViewById(R.id.emptyText);
        if (empty != null) {
            // v2.59: premium delete-to-empty empty-state fade —
            // search (v2.51) faded the empty word on a no-match
            // keystroke, but a confirmed delete that emptied the
            // library still snapped it in instantly (v2.55 noted
            // delete-all's empty view stayed instant, and the
            // single-delete path showed it directly too). The
            // delete handlers above arm dlEmptyFade only when the
            // list really emptied, so the GONE -> VISIBLE empty
            // view now fades in with the same 250ms Fx.fadeIn.
            // Consumed here on every pass, so a later reload can
            // never inherit it; first loads, rotations, sort and
            // unchanged reloads never set it and stay instant.
            boolean fadeEmpty = dlEmptyFade;
            dlEmptyFade = false;
            if (origEmptyText == null) origEmptyText = Lang.t(DownloadedActivity.this, "dl_empty");
            // v2.51: premium empty-state fade — v2.49 made the rows
            // pour in on a filter change, but the empty word that
            // replaces them still snapped in instantly: a search
            // matching nothing jumped from pouring rows to a flat
            // "ရှာမတွေ့ပါ" line in one frame. On a real search
            // keystroke after the first load, a GONE -> VISIBLE
            // empty view now fades in with the existing Fx.fadeIn
            // (250ms, decelerate, alpha-only), choreographed with
            // the rows vanishing beneath it. Only that path fades:
            // an already-visible view just swaps its text (no
            // flicker on fast typing), first loads, rotations,
            // sort/delete reloads and the truly-empty library stay
            // instant as before, and hiding cancels any running
            // fade so a later show never strands at half alpha.
            // Visual only; no new element, text unchanged.
            boolean wasGone = empty.getVisibility() != android.view.View.VISIBLE;
            if (entries.isEmpty()) {
                // v11.73: a missing audio permission is not an empty library — the denial
                // branch above sets this text, but the next loadList used to overwrite it
                // with "nothing downloaded yet". Keep the permission line while unreadable.
                // v11.89: an empty shelf with a download in progress is not an empty
                // library either — say so (the footer already showed ⏳ while this word
                // said "nothing downloaded yet"). dlProgCount is refreshed by loadList
                // above, so a fresh open reads it correctly.
                empty.setText(!hasReadPermission()
                        ? Lang.t(DownloadedActivity.this, "dl_perm_need")
                        : dlProgCount > 0
                        ? "⏳ " + dlProgCount + Lang.t(DownloadedActivity.this, "dl_downloading_suffix")
                        : origEmptyText);
                if (wasGone && (dlSearchKeystroke || fadeEmpty) && dlListLoaded) {
                    empty.setVisibility(android.view.View.VISIBLE);
                    Fx.fadeIn(empty, 250);
                } else if (empty.getAlpha() >= 0.999f) {
                    // v2.80: a fade-out running (below) must not
                    // finish under a freshly shown view — cancel
                    // it and restore full alpha, the same hygiene
                    // as the v2.64 permission-denied show branch.
                    // A settled word keeps this instant reset.
                    empty.animate().cancel();
                    empty.setAlpha(1f);
                    empty.setVisibility(android.view.View.VISIBLE);
                } else {
                    // v3.58: premium filter-empty re-appearance continuity — the
                    // v3.56 denied-empty fix, applied to the two applyDlFilter
                    // show branches it never reached. A show landing inside the
                    // 200ms v2.80 fade-out (fast retype to a non-matching
                    // letter while the word is still fading out) found the view
                    // still VISIBLE and snapped its half-faded word straight to
                    // alpha 1 in one frame — the same snap v3.56 fixed for the
                    // denied branch. A settled word keeps the instant reset
                    // above; a mid-fade word now glides back to 1 over 250ms
                    // (decelerate) on the same channel Fx.fadeOut uses, so a
                    // later fade-out cancels the glide and fades from its
                    // current alpha. No end action, so a cancel can never
                    // strand the view; text, visibility and the fade-in
                    // branch above are untouched. Visual only.
                    // v3.62: route through Fx.cancelFade so the glide
                    // really runs from the mid-fade alpha instead of
                    // 1->1 on a view a fired fadeOut end action hid.
                    Fx.cancelFade(empty);
                    empty.animate().alpha(1f).setDuration(250)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    empty.setVisibility(android.view.View.VISIBLE);
                }
            } else if (shownDl.isEmpty()) {
                // v4.95: a filtered shelf with no file in its set
                // says so in its own word, not the search no-match
                // word — a typed query still earns the no-match
                // word, since the query (not the filter) is what
                // failed then. The truly-empty library branch above
                // keeps its own text; this branch only runs with
                // files on the shelf but none shown.
                empty.setText(dlFilterMode == 1 && dlQuery.length() == 0
                        ? Lang.t(DownloadedActivity.this, "no_fav_talk")
                        : dlFilterMode == 2 && dlQuery.length() == 0
                        ? Lang.t(DownloadedActivity.this, "all_listened")
                        : Lang.t(DownloadedActivity.this, "no_search_match"));
                if (wasGone && (dlSearchKeystroke || fadeEmpty) && dlListLoaded) {
                    empty.setVisibility(android.view.View.VISIBLE);
                    Fx.fadeIn(empty, 250);
                } else if (empty.getAlpha() >= 0.999f) {
                    // v2.80: same fade-out cancel as above.
                    // A settled word keeps this instant reset.
                    empty.animate().cancel();
                    empty.setAlpha(1f);
                    empty.setVisibility(android.view.View.VISIBLE);
                } else {
                    // v3.58: same filter-empty re-appearance continuity as the
                    // truly-empty branch above (the v3.56 denied-empty rule) —
                    // a no-match show landing inside the 200ms v2.80 fade-out
                    // glides back to 1 over 250ms instead of snapping.
                    // No end action, so a cancel can never strand the view;
                    // text, visibility and the fade-in branch above are
                    // untouched. Visual only.
                    // v3.62: route through Fx.cancelFade (same guard as
                    // the truly-empty branch above) so the glide really
                    // runs from the mid-fade alpha.
                    Fx.cancelFade(empty);
                    empty.animate().alpha(1f).setDuration(250)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    empty.setVisibility(android.view.View.VISIBLE);
                }
            } else {
                // v2.80: premium library empty-view fade-out,
                // mirroring the v2.78 Room / v2.79 Talks retry
                // fade-outs — the empty word used to snap GONE
                // the instant rows matched again (clearing the
                // search, typing a matching letter), while every
                // empty-view show already fades in (v2.51 search,
                // v2.59 delete-to-empty, v2.64 permission-denied).
                // A visible view now fades to 0 over 200ms
                // (decelerate, alpha-only, existing Fx.fadeOut)
                // and only then goes GONE at full alpha; an
                // already-hidden view stays hidden with no
                // animation, so first loads and rotations never
                // fade anything. A reload landing mid-fade is
                // already covered — loadList cancels the view's
                // animation and resets alpha before its direct
                // set — and a show landing mid-fade cancels it
                // in the branches above, so no path strands the
                // view half-transparent or hides a fresh show.
                // Visual only; text and filter behaviour untouched.
                if (empty.getVisibility() == android.view.View.VISIBLE) {
                    Fx.fadeOut(empty, 200);
                } else {
                    empty.animate().cancel();
                    empty.setAlpha(1f);
                    empty.setVisibility(android.view.View.GONE);
                }
            }
        }
    }

    // v1.19: footer total — count + combined size of the offline library
    // v2.56: premium delete-change footer fade — the rows pour in
    // on a confirmed single delete (v2.55), but the footer total
    // above them used to snap to its new count/MB in the same frame,
    // so the header of the list changed in a different rhythm from
    // the rows below it. When the delete handler has armed
    // dlDeleteChange (armed before applyDlFilter for the row pour,
    // re-armed for this call after the filter consumes it — v12.37)
    // and the text really changes,
    // the new total fades in with the existing Fx.fadeIn (250ms,
    // alpha-only, same as the v2.51 empty-state fade). Every other
    // update (first load, sort, search, unchanged onResume) stays
    // instant and resets alpha to 1, so a fade interrupted by a
    // reload never strands the footer half-faded. Visual only;
    // text unchanged.
    // v2.58: premium return-change footer fade — the rows pour in
    // on a real return-change (v2.57), but the footer total above
    // them still snapped to its new count/MB in the same frame, so
    // the list's header changed in a different rhythm from the rows
    // below it. v12.37: the call now runs AFTER applyDlFilter (the
    // footer reads the fresh shown list), and the caller snapshots
    // the delete/return flags across the filter and restores them
    // for this call, so the same text-change guard still covers
    // it: a return whose reload really changes the total fades it
    // in with the same 250ms alpha-only fade, while a return that
    // leaves the total unchanged stays instant (the guard fails,
    // and the row pour's own key check stays the arbiter for the
    // rows). (v2.81 supersedes the emptied case: an emptied list now
    // fades the footer out — see the v2.81 block on updateTotal.)
    // v2.81: premium library-footer fade-out — the mirror of the
    // v2.56/v2.58 fade-ins. A changed total already fades in, but
    // the emptying case was left instant: deleting the last file
    // snapped "စုစုပေါင်း N ပုဒ် · X MB" to blank in the same frame
    // the empty word faded in (v2.59/v2.80), so the footer's exit
    // moved in a different rhythm from the empty view's entrance.
    // A non-empty -> empty total now keeps its old text and fades
    // to 0 over 200ms (decelerate, alpha-only, same rhythm as the
    // v2.80 empty-view fade-out), and only then clears its text
    // and goes GONE at full alpha, so a later total starts clean.
    // An already-empty total stays instant (first loads, rotations
    // and unchanged reloads never fade anything, since oldText is
    // already ""); a reload landing mid-fade leaves the fade to
    // finish (the list is still empty, so its end state is still
    // right); a non-empty reload cancels the fade, restores
    // VISIBLE + full alpha and sets the new total, so the fade
    // can never hide a fresh total. Visual only; text unchanged.
    // v4.22: hide the library search keyboard — same gap as
    // Main's v4.22 sites: only the IME Search action (v4.20)
    // ever hid it, so every in-place play/sort action left it
    // covering the list it had just changed. Refusals (empty
    // list) and retype-imminent actions (clear-search, Random
    // shuffle toggle, row delete) deliberately keep it.
    private void hideKeyboard(android.view.View v) {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Exception ignored) {}
    }

    private void updateTotal() {
        scanDlProgress();
        long total = 0;
        for (Entry e : entries) total += e.file.length();
        TextView tv = findViewById(R.id.totalText);
        if (tv != null) {
            String oldText = tv.getText() == null ? "" : tv.getText().toString();
            // v4.24: the footer also counts favourites ("· ★ M"),
            // so the shelf's total reads like the Room's queue
            // counts do. Shown only when at least one file is a
            // favourite; an empty cache (before the first loadList
            // pass) simply adds nothing.
            int favCount = 0;
            for (Entry e : entries)
                if (libFavFiles.contains(e.file.getName())) favCount++;
            // v4.27: the footer names downloads in progress — a
            // count ("· ⏳ N") plus the first job's title and
            // percent, so a Wi-Fi queue no longer reads as
            // nothing happening. An empty shelf with a live
            // download shows the progress line instead of "",
            // so the footer becomes visible for it; the empty
            // fade-out path still runs when nothing is queued.
            String dlProgSuffix = "";
            if (dlProgCount > 0) {
                dlProgSuffix = " · ⏳ " + dlProgCount;
                if (dlProgFirstTitle.length() > 0)
                    dlProgSuffix += " · " + dlProgFirstTitle
                            + (dlProgFirstPct >= 0 ? " " + dlProgFirstPct + "%" : "");
            }
            // v4.66: the footer reads the hand queue — the mirror of
            // the Talks chooser header's v4.65 ◈ count + Up Next
            // line and the Room drawer header's v4.32 count. The
            // shelf's total used to show count + MB + ★ + ⏳ but
            // never the queue, so a queue built from this shelf's
            // own ⋮ menus never read in its own footer and the
            // next file stayed invisible until Next was tapped.
            // The count comes from libQueueCache, which loadList,
            // the ⋮ menu and the delete paths already prune
            // against the full shelf — it is deliberately NOT
            // refreshed here, because an updateTotal call before
            // the first loadList (empty entries) would prune a
            // real queue to nothing and write the wipe back.
            // Zero keeps the plain total, like the ★/⏳ guards.
            // The Up Next line names the file a Next tap would
            // play — the queued head first (skipping the playing
            // file itself, which a direct row tap can leave in
            // the queue even though the menu never queues the
            // playing file), else the list-next after the
            // sounding file, wrapping — only while a live
            // library session sounds a file on this shelf (a
            // stream URL from a Room session can never match a
            // file path, so a Room session leaves the footer
            // count-only, like the chooser's foreign-session
            // state) and only while the track has not ended.
            // With shuffle on and no queue, Next is random, so
            // naming a deterministic file would be false — the
            // line is skipped there, while a queued head stays
            // deterministic because the queue outranks shuffle
            // on Next and auto-advance alike (v4.57). The footer
            // refreshes on load, on return (loadList runs on
            // every onResume), on ⋮ menu actions (below) and on
            // track changes (tick branch), never on the 1s tick
            // itself, which deliberately skips the footer except
            // for download-progress changes.
            // v5.65: the footer also counts finished talks (" · ✓ M")
            // — the mirror of the Room drawer header's v5.64 count.
            // The shelf total already read count + MB + ★ (v4.24)
            // + ⏳ (v4.27) + ◈ (v4.66), but never how many saved
            // files had been heard to the end, so progress through
            // the offline shelf meant counting ✓ marks down the
            // rows. From libListenedFiles, the exact per-load
            // cache the shelf rows themselves read for their ✓
            // (same dlMap lookup, same file-name membership), so
            // the count matches the visible marks by construction;
            // zero keeps the plain total, like the ★/⏳/◈ guards,
            // and it sits after ◈ (last among the counts, like
            // the Room header) before the Up Next line. No new
            // read: the cache is refreshed by loadList before
            // updateTotal, and the footer already refreshes on
            // load, return, ⋮ actions and track changes, so the
            // count steps when a finish lands on a track change
            // or a return — the same cadence the ★ count has.
            String playedSuffix = "";
            int playedCount = 0;
            for (Entry e : entries)
                if (libListenedFiles.contains(e.file.getName())) playedCount++;
            if (playedCount > 0) playedSuffix = " · ✓ " + playedCount;
            // v5.71: the footer also counts half-heard files (" · ↺ M")
            // — the mirror of the Room drawer header's v5.69 count
            // and the Talks chooser header's v5.70 count. The shelf
            // total already read count + MB + ★ (v4.24) + ⏳ (v4.27)
            // + ◈ (v4.66) + ✓ (v5.65), but never how many saved
            // files were stopped mid-way, even though the shelf
            // rows themselves carry the resume hint ("· mm:ss မှ
            // ဆက်" on the size line since v5.35, preview rows ↺
            // since v5.60) — progress through the offline shelf
            // meant counting hints down the rows. From
            // libResumeMap, the exact per-load cache the shelf
            // rows read for their hint (>5s by construction, the
            // global spot overlaid, same path-form fallback), so
            // the count matches the visible hints by construction;
            // zero keeps the plain total, like the ★/⏳/◈/✓ guards,
            // and it sits after ✓ (last among the counts, like the
            // Room and chooser headers) before the Up Next line.
            // No playing-file exclusion, unlike the chooser's
            // v5.70 rule: the library's hint lives on the size
            // line, which the live clock only temporarily
            // overwrites while a file plays, and the footer
            // counts follow the ★/✓ convention of counting the
            // full shelf regardless of playback state. No new
            // read: the cache is refreshed by loadList before
            // updateTotal, and the footer already refreshes on
            // load, return, ⋮ actions and track changes, so the
            // count steps when a spot lands or a file finishes.
            String resumeSuffix = "";
            int resumeCount = 0;
            for (Entry e : entries)
                if (libResumeMap.containsKey(e.file.getAbsolutePath())) resumeCount++;
            if (resumeCount > 0) resumeSuffix = " · ↺ " + resumeCount;
            String queueSuffix = "";
            String upNextLine = "";
            // v5.81: the footer's ◈ count skips the file now
            // playing — the strips (v5.80), the card (v5.43) and
            // this screen's own Up Next line (v4.66, playing file
            // skipped) already count waiting files only, but the
            // footer counted the raw libQueueCache, so a
            // service-restart play still saved in the queue read
            // as one waiting here and zero there. Same rule as
            // the strips: the session's lastLibPath for a library
            // session, read from prefs (works before the service
            // binds, unlike getCurrentUrl below); a normal play
            // (already trimmed by trimLibQueueAhead) changes
            // nothing. Zero keeps the plain total, like every
            // guard in this footer.
            if (libQueueCache != null && !libQueueCache.isEmpty()) {
                int waitQ = libQueueCache.size();
                try {
                    android.content.SharedPreferences fsp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    if ("lib".equals(fsp.getString("lastKind", ""))) {
                        String curLib = fsp.getString("lastLibPath", "");
                        if (curLib.length() > 0 && libQueueCache.contains(curLib)) waitQ--;
                    }
                } catch (Exception ignored) {}
                if (waitQ > 0) queueSuffix = " · ◈ " + waitQ;
            }
            if (!entries.isEmpty() && entries.size() > 1 && service != null
                    && service.hasTrack() && !service.hasTrackEnded()) {
                String curPath = service.getCurrentUrl();
                // v12.34: the Up Next list follows handleLibNext's
                // list — shownDl first (the filtered list), entries
                // only when the sounding file is not shown — so a
                // search filter no longer leaves the footer naming
                // a filtered-out file while Next plays the next
                // shown one. The queued-head lookup below stays on
                // entries and the redo below stays filter-free:
                // both outrank list order on Next regardless of
                // the filter, so naming them stays correct. The
                // exact shape of the preview's list-mode branch
                // and handleLibNext's own fallback.
                java.util.List<Entry> nxList = shownDl;
                int playIdx = -1;
                if (curPath != null)
                    for (int i = 0; i < nxList.size(); i++)
                        if (nxList.get(i).file.getAbsolutePath().equals(curPath)) { playIdx = i; break; }
                if (playIdx < 0 && curPath != null) {
                    nxList = entries;
                    for (int i = 0; i < nxList.size(); i++)
                        if (nxList.get(i).file.getAbsolutePath().equals(curPath)) { playIdx = i; break; }
                }
                if (playIdx >= 0) {
                    Entry nx = null;
                    if (libQueueCache != null) {
                        for (String qp : libQueueCache) {
                            if (qp.equals(curPath)) continue;
                            for (int i = 0; i < entries.size(); i++)
                                if (entries.get(i).file.getAbsolutePath().equals(qp)) { nx = entries.get(i); break; }
                            if (nx != null) break;
                        }
                    }
                    // v11.17: redo outranks list-next (and shuffle,
                    // which the fallback below already hides) so the
                    // footer line names what the ▶ button plays.
                    if (nx == null) {
                        Entry redoNx17 = libRedoTopEntry17(curPath);
                        if (redoNx17 != null) nx = redoNx17;
                    }
                    // v12.24: effective shuffle, not the session flag alone — the line
                    // above used to read only dlShuffle, so a GLOBAL shuffle (Room
                    // button / Settings, Prefs.shuffleOn) left it naming a deterministic
                    // list-next file while Next and auto-advance played random (the
                    // v7.33 poll / v10.90 Next rule: either flag shuffles). The old
                    // queue-emptiness sub-condition lied a second way: a queue of only
                    // dead heads (deleted after the scan) left nx null with a non-empty
                    // queue, so the line named list-next while Next played random even
                    // for the session flag. nx == null already means "no live queued
                    // head", so the sub-condition is redundant once the effective
                    // shuffle is used; a live queued head or a redo still names
                    // above, exactly as today.
                    boolean effShuffle24 = dlShuffle || Prefs.shuffleOn(DownloadedActivity.this);
                    // v12.34: list-next from the same list the
                    // playIdx search used, with the preview's
                    // list.size() > 1 rule — a one-file shown list
                    // replays the same file on Next, so no line is
                    // named there, matching the one-file shelf
                    // convention.
                    if (nx == null && !effShuffle24 && nxList.size() > 1)
                        nx = nxList.get((playIdx + 1) % nxList.size());
                    if (nx != null) {
                        // v5.62: the library footer's Up Next line
                        // gains the ✓ finished and ↺ resume marks
                        // its own preview rows carry (v5.50 ✓, v5.60
                        // ↺) — the line naming the imminent file
                        // read a finished or half-heard file exactly
                        // like an unheard one. From the per-load
                        // caches the preview already uses
                        // (libListenedFiles, libResumeMap — >5s by
                        // construction), no new read, and the footer
                        // refreshes on track changes, so the marks
                        // follow a finish or a spot landing.
                        // v5.96: the library footer's Up Next line
                        // gains its ◈ queue position — the Room
                        // drawer header's line got it in v5.94 and
                        // the Talks chooser header's line in v5.95,
                        // but this mirror line still named the
                        // queued head with ★, ✓, ↺ only, while
                        // every row naming that file carries its
                        // ◈ waiting number (library rows v5.82).
                        // Same waiting-number rule as the rows:
                        // position in libQueueCache with the
                        // playing file skipped (the v5.82 rule —
                        // a service-restart play can still sit
                        // saved in the queue), so the number
                        // agrees with the row marks; an unqueued
                        // list-next fallback is not in the queue
                        // and reads exactly as today. First suffix
                        // mark, ahead of ★, matching the Room and
                        // chooser headers' v5.94/v5.95 order. No
                        // new read: libQueueCache and curPath are
                        // already in scope above.
                        String nxQueueMark = "";
                        try {
                            if (libQueueCache != null) {
                                int nxShown = 0;
                                boolean nxFound = false;
                                for (String qp : libQueueCache) {
                                    if (curPath != null && qp.equals(curPath)) continue;
                                    nxShown++;
                                    if (qp.equals(nx.file.getAbsolutePath())) { nxFound = true; break; }
                                }
                                if (nxFound) nxQueueMark = " ◈" + nxShown;
                            }
                        } catch (Exception ignored) {}
                        Integer nxRp = libResumeMap.get(nx.file.getAbsolutePath());
                        // v8.66: the footer Up Next line names its sayadaw — the
                        // library mixes downloads from many sayadaws, so a
                        // title-only line read anonymously when two sayadaws
                        // share a talk title, the same gap class the
                        // v8.62/v8.63/v8.64/v8.65 series closed on the Room
                        // drawer header, Next/Previous previews and chooser
                        // header. The preview rows directly above this line
                        // already name their sayadaw (v5.48); this line
                        // never did. From the per-load libSayadawMap (no
                        // JSON per refresh, the v1.57/v2.03 cost class),
                        // placed after the title and before the marks,
                        // matching the Next preview's title · sayadaw +
                        // marks order; a file with no record (unrecorded
                        // download, or a v8.43 ambiguous dual-claimed
                        // file, which the cache deliberately leaves
                        // unnamed) keeps today's line byte-identical —
                        // never a guessed name.
                        String nxSd66 = libSayadawMap.get(nx.file.getName());
                        String nxSdSuffix66 = (nxSd66 != null && nxSd66.length() > 0)
                                ? " · " + nxSd66 : "";
                        upNextLine = "\n" + Lang.t(DownloadedActivity.this, "next_prefix") + nx.title + nxSdSuffix66 + nxQueueMark
                                + (libFavFiles.contains(nx.file.getName()) ? " ★" : "")
                                + (libListenedFiles.contains(nx.file.getName()) ? " ✓" : "")
                                + (nxRp != null ? " ↺ " + fmtDlTime(nxRp) : "");
                    }
                }
            }
            String newText = entries.isEmpty()
                    ? (dlProgCount > 0 ? "⏳ " + dlProgCount + Lang.t(DownloadedActivity.this, "notif_queue_suffix") + Lang.t(DownloadedActivity.this, "dl_downloading_suffix")
                            + (dlProgFirstTitle.length() > 0 ? " · " + dlProgFirstTitle
                                    + (dlProgFirstPct >= 0 ? " " + dlProgFirstPct + "%" : "") : "")
                            : "")
                    : Lang.t(DownloadedActivity.this, "dl_total_prefix") + entries.size() + Lang.t(DownloadedActivity.this, "notif_queue_suffix") + " · "
                    + String.format("%.1f MB", total / 1048576.0)
                    + (favCount > 0 ? " · ★ " + favCount : "")
                    + dlProgSuffix
                    + queueSuffix
                    + playedSuffix
                    + resumeSuffix
                    + upNextLine;
            if (newText.isEmpty() && !oldText.isEmpty()
                    && tv.getVisibility() == android.view.View.VISIBLE) {
                if (!totalFadingOut) {
                    totalFadingOut = true;
                    tv.animate().cancel();
                    tv.animate().alpha(0f).setDuration(200)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .withEndAction(new Runnable() {
                                @Override public void run() {
                                    if (!totalFadingOut) return;
                                    totalFadingOut = false;
                                    tv.setText("");
                                    tv.setVisibility(android.view.View.GONE);
                                    tv.setAlpha(1f);
                                }
                            }).start();
                }
            } else {
                // v3.59: premium footer-total re-appearance continuity — the v3.58
                // empty-view fix, applied to the footer total it never reached. A
                // reload landing inside the 200ms v2.81 fade-out (a queued download
                // finishing just after the last file was deleted, picked up by the
                // 1s refresh) used to cancel the fade and then snap the half-faded
                // total straight to alpha 1 in one frame whenever the v2.56/v2.58
                // fade guard did not fire (the reload armed neither dlDeleteChange
                // nor dlReturnChange, so the else branch below reset directly) —
                // the same snap class v3.52 fixed inside Fx.fadeIn and v3.58 fixed
                // on the empty view, but on this view's own animator channel. A
                // settled total (alpha ~1, e.g. a reload at the very start of the
                // fade) keeps the old instant reset with no pointless fade, while
                // a mid-fade total now glides back to 1 over 250ms (decelerate)
                // on the same ViewPropertyAnimator channel the fade-out uses, so
                // a later emptying cancels the glide and fades from its current
                // alpha; no end action, so a cancel can never strand the total.
                // The delete/return fade path above is untouched — Fx.fadeIn
                // already flows from the current alpha there since v3.52 — and
                // the text swap stays instant, as in v3.58. Visual only.
                boolean wasFadingOut = false;
                if (totalFadingOut) {
                    totalFadingOut = false;
                    tv.animate().cancel();
                    wasFadingOut = true;
                }
                if (!newText.isEmpty() && tv.getVisibility() != android.view.View.VISIBLE)
                    tv.setVisibility(android.view.View.VISIBLE);
                tv.setText(newText);
                if ((dlDeleteChange || dlReturnChange) && !newText.isEmpty() && !newText.equals(oldText))
                    Fx.fadeIn(tv, 250);
                else if (wasFadingOut && !newText.isEmpty() && tv.getAlpha() < 0.999f) {
                    tv.animate().alpha(1f).setDuration(250)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                } else {
                    tv.animate().cancel();
                    tv.setAlpha(1f);
                }
            }
            // v1.96: the footer total never followed the font size
            // either (no layout size, so it sat at the default) —
            // same steps as the row meta line, one step up.
            int f = Prefs.fontSize(this);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, f == 0 ? 13 : (f == 1 ? 14 : 17));
        }
    }

    // v3.01: the v1.94 search-box sizing, extracted unchanged so
    // both the instant path and the themeDip swap in onResume
    // apply it — the swap must size the box at the dip, not leave
    // it resizing instantly while the rest of the screen dips.
    private void applyDlSearchSize() {
        if (dlSearchField != null) {
            int f = Prefs.fontSize(this);
            dlSearchField.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    f == 0 ? 14 : (f == 1 ? 16 : 20));
        }
    }

    // v1.98: this screen's header/subtitle/search/empty lines never
    // followed night mode or the font size — the root went dark
    // (v1.60) but the header stayed maroon on dark, the subtitle /
    // empty / total stayed muted, and the search box kept its dark
    // ink text, so typed text was invisible at night and the header
    // read low-contrast. Colour every existing header line for the
    // theme (gold header + grey notes at night, maroon + muted by
    // day) and scale header / subtitle / empty / action buttons
    // with the Settings font size (header 20/24/30, subtitle
    // 12/13/16, empty 14/15/18, buttons 14/16/18); rows and the
    // footer total already scaled since v1.96. All views are
    // looked up here (not via fields) because onCreate calls this
    // before the search field is assigned.
    private void applyTheme() {
        boolean night = Prefs.isNight(this);
        cachedDlNight = night;
        Fx.applySystemBars(this, night); // v13.07: bars follow the theme
        cachedDlFont = Prefs.fontSize(this);
        cachedDlLang = Prefs.lang(this);
        View root = findViewById(R.id.root);
        if (root != null) root.setBackgroundResource(night ? R.drawable.bg_gradient_night : R.drawable.bg_gradient_day);
        int f = Prefs.fontSize(this);
        TextView header = findViewById(R.id.dlHeader);
        if (header != null) {
            // v9.92: the library header was layout Burmese in all 12 languages
            // (applyTheme set only colour/size) while the subtitle/buttons below
            // it went Lang-driven in v9.89 — set it from the EXISTING key
            // "downloaded", whose Burmese column is byte-equal to the layout
            // text, so Burmese display is unchanged and a language change in
            // Settings now re-reads the header on return via applyTheme.
            header.setText(Lang.t(this, "downloaded"));
            header.setTextColor(night ? android.graphics.Color.parseColor("#C9A227")
                    : android.graphics.Color.parseColor("#7A2E1F"));
            header.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    f == 0 ? 20 : (f == 1 ? 24 : 30));
        }
        int muted = night ? android.graphics.Color.parseColor("#AAAAAA")
                : android.graphics.Color.parseColor("#7A6A58");
        TextView subtitle = findViewById(R.id.dlSubtitle);
        if (subtitle != null) {
            subtitle.setText(Lang.t(this, "dl_subtitle"));
            subtitle.setTextColor(muted);
            subtitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    f == 0 ? 12 : (f == 1 ? 13 : 16));
        }
        TextView empty = findViewById(R.id.emptyText);
        if (empty != null) {
            empty.setTextColor(muted);
            empty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    f == 0 ? 14 : (f == 1 ? 15 : 18));
        }
        TextView total = findViewById(R.id.totalText);
        if (total != null) total.setTextColor(muted);
        android.widget.EditText search = findViewById(R.id.searchDl);
        if (search != null) {
            // v1.99: the search box kept the default light-theme
            // EditText look at night while the main screen's box has
            // used the themed rounded drawables since v1.17 — same
            // drawables here, so the box reads as one element in
            // both themes instead of a leftover light widget.
            // v13.03: real liquid glass — GlassBlur blurs the content behind this surface.
            GlassBlur.attachSearch(search, night);
            search.setTextColor(night ? android.graphics.Color.WHITE
                    : android.graphics.Color.parseColor("#3B2314"));
            search.setHintTextColor(muted);
        }
        // v2.01: the list divider kept the light theme's default dark
        // line in both themes — the Talks list got a themed divider
        // in v2.00 and this one never did. Same colours (dark grey
        // at night, surface by day), re-applied on return with the
        // rest; the layout already reserves the 1dp height.
        android.widget.ListView dlList = findViewById(R.id.list);
        if (dlList != null) dlList.setDivider(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        float btn = f == 0 ? 14 : (f == 1 ? 16 : 18);
        for (int id : new int[]{R.id.btnDlSort, R.id.btnDlRandom, R.id.btnDlPlayAll,
                R.id.btnDlDeleteAll, R.id.btnClearDlSearch}) {
            android.widget.Button b = findViewById(id);
            if (b != null) {
                b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, btn);
                // v2.00: these action buttons kept the default
                // light-theme face at night — readable, but the
                // only light widgets on a dark screen, same class
                // as the Settings action buttons fixed in v1.99.
                // Same unselected choice-button colours (dark face
                // + white text at night, surface face + ink text
                // by day); re-applied on return with the rest.
                b.setBackgroundResource(night ? R.drawable.glass_btn_night : R.drawable.glass_btn);
                b.setTextColor(night
                        ? android.graphics.Color.WHITE
                        : android.graphics.Color.parseColor("#3B2314"));
            }
        }
        // v9.89: the three action buttons + the truly-empty message were
        // layout Burmese in all 12 languages (never set from Lang) —
        // set them here, where applyTheme already runs on create and
        // on every return (before loadList re-applies the filter, so
        // the empty cache refresh below cannot clobber a filter word).
        // The Random text reset must re-apply the session shuffle
        // mark, or a return with shuffle on would lose its 🔀 prefix.
        android.widget.Button rb = findViewById(R.id.btnDlRandom);
        if (rb != null) rb.setText(Lang.t(this, "dl_random_btn"));
        android.widget.Button pb = findViewById(R.id.btnDlPlayAll);
        if (pb != null) pb.setText(Lang.t(this, "dl_play_all_btn"));
        android.widget.Button db = findViewById(R.id.btnDlDeleteAll);
        if (db != null) db.setText(Lang.t(this, "dl_delete_all_btn"));
        // v9.96: the sort button was set only at onCreate, on sort
        // tap and on clear/reset -- never by applyTheme(), so a
        // language picked in Settings while this screen sat in the
        // back stack left it in the OLD language until the next
        // sort tap (same class as the v9.94 Talks filter button).
        // Same EXISTING dlSortLabel() keys the tap path uses, so
        // Burmese display is byte-identical; the first-create pass
        // is safe because dlSortMode defaults to 0 (sort_recent),
        // matching the layout default text.
        android.widget.Button sort = findViewById(R.id.btnDlSort);
        if (sort != null) sort.setText(dlSortLabel());
        applyShuffleMark();
        origEmptyText = Lang.t(this, "dl_empty");
    }

    // v1.98: prune the Room's URL->filename map ("dlMap", v1.90) for
    // files that no longer exist — deleting a downloaded talk left
    // its map entry claiming that filename forever, so the map only
    // grew and a later download could inherit a deleted file's
    // hash-suffixed name instead of its plain one. Only entries
    // naming a file actually deleted here are removed; entries for
    // files the library does not list (e.g. legacy extension-less
    // downloads) are untouched.
    // v7.87: cancel this app's live DownloadManager jobs (PENDING,
    // RUNNING or PAUSED only — a finished job's lingering row is
    // never removed, and a row with an unreadable status is left
    // alone rather than cancelled on a guess) and drop their source
    // URLs from dlQueued. App scope mirrors scanDlProgress: a
    // Downloads/Dhamma destination, or a source URL this app queued
    // (dlQueued) or mapped (dlMap). Every step is guarded, so a
    // DownloadManager failure here never blocks the file deletion
    // that follows; the Room/Talks prunes would drop the queue
    // entries on their next rescan anyway, this just makes the
    // delete-all itself total at once.
    private void cancelLiveDownloads() {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            java.util.Set<String> queued = new java.util.HashSet<String>();
            try {
                org.json.JSONArray qa = new org.json.JSONArray(sp.getString("dlQueued", "[]"));
                for (int i = 0; i < qa.length(); i++) queued.add(qa.optString(i));
            } catch (Exception ignored) {}
            java.util.Set<String> mapped = new java.util.HashSet<String>();
            try {
                org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
                java.util.Iterator<String> keys = map.keys();
                while (keys.hasNext()) mapped.add(keys.next());
            } catch (Exception ignored) {}
            java.util.Set<String> cancelledSrc = new java.util.HashSet<String>();
            try {
                android.app.DownloadManager dm = (android.app.DownloadManager)
                        getSystemService(android.content.Context.DOWNLOAD_SERVICE);
                android.database.Cursor c = dm.query(new android.app.DownloadManager.Query());
                if (c != null) {
                    try {
                        int idCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_ID);
                        int statusCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS);
                        int uriCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_LOCAL_URI);
                        int srcCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_URI);
                        while (c.moveToNext()) {
                            int st = statusCol >= 0 ? c.getInt(statusCol) : -1;
                            if (st != android.app.DownloadManager.STATUS_RUNNING
                                    && st != android.app.DownloadManager.STATUS_PAUSED
                                    && st != android.app.DownloadManager.STATUS_PENDING) continue;
                            String local = uriCol >= 0 ? c.getString(uriCol) : null;
                            String src = srcCol >= 0 ? c.getString(srcCol) : null;
                            boolean ours = (local != null && local.contains("Dhamma/"))
                                    || (src != null && (queued.contains(src) || mapped.contains(src)));
                            if (!ours) continue;
                            if (idCol >= 0) {
                                try { dm.remove(c.getLong(idCol)); } catch (Exception ignored) {}
                                if (src != null) cancelledSrc.add(src);
                            }
                        }
                    } finally { c.close(); }
                }
            } catch (Exception ignored) {}
            if (!cancelledSrc.isEmpty()) {
                try {
                    org.json.JSONArray qa = new org.json.JSONArray(sp.getString("dlQueued", "[]"));
                    org.json.JSONArray out = new org.json.JSONArray();
                    for (int i = 0; i < qa.length(); i++) {
                        String u = qa.optString(i);
                        if (!cancelledSrc.contains(u)) out.put(u);
                    }
                    sp.edit().putString("dlQueued", out.toString()).apply();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void pruneDlMapForFilenames(java.util.Set<String> deletedNames) {
        if (deletedNames == null || deletedNames.isEmpty()) return;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONObject map =
                    new org.json.JSONObject(sp.getString("dlMap", "{}"));
            java.util.Iterator<String> keys = map.keys();
            java.util.List<String> drop = new java.util.ArrayList<String>();
            while (keys.hasNext()) {
                String k = keys.next();
                if (deletedNames.contains(map.optString(k, ""))) drop.add(k);
            }
            // v8.29: a deleted file used to orphan its resume spot —
            // the spot was saved under the file-path form (offline
            // pause/checkpoint), and deleting the file pruned the
            // dlMap entry that linked the two forms, so the
            // service's finish-clear (which resolves the saved
            // form through that link) could never match the later
            // streamed finish: the finished talk kept its resume
            // bar, its per-talk spot and its half-heard count, and
            // replaying it resumed at the old pause minute instead
            // of starting from the beginning. Every delete path
            // (single, all, subsets) funnels through here, so the
            // translation happens here, while the map still links
            // the forms: a path-form spot is re-pointed at its
            // stream URL (progress preserved, and a later stream
            // finish now clears it by exact match), a stream spot
            // is never overwritten by an older path spot (the
            // stream entry wins when both exist), and a path
            // entry with no stream form left is dropped with the
            // file instead of lingering under the cap or wrongly
            // resuming a later talk that reuses the filename. A
            // global spot naming a deleted file with no stream
            // form is cleared, not left pointing at a phantom
            // minute. Played/favourite marks are untouched — only
            // resume spots move, never history.
            for (String name : deletedNames) {
                String path = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma/" + name)
                        .getAbsolutePath();
                // v8.50: exactly-one claimant, never the first
                // match — the Prefs fix above, at the delete
                // re-point. A dual-claimed file's old first-match
                // re-pointed its path-form spot (and the global
                // spot, when it named the deleted file) at the
                // OTHER talk's stream URL, so the wrong talk
                // kept a resume minute for audio it never
                // played — the delete-time twin of the write/
                // clear bug. An ambiguous file now re-points
                // nowhere: its path spot is dropped with the
                // file and a global spot naming it is cleared,
                // exactly like a file with no dlMap entry. The
                // drop list above already removes every
                // claimant, so no claim survives either way.
                String streamUrl = null;
                int claim850 = 0;
                java.util.Iterator<String> k2 = map.keys();
                while (k2.hasNext()) {
                    String k = k2.next();
                    if (name.equals(map.optString(k, ""))) { streamUrl = k; claim850++; }
                }
                if (claim850 != 1) streamUrl = null;
                int pathPos = Prefs.talkPosGet(DownloadedActivity.this, path);
                if (pathPos > 0) {
                    // v12.20: only a >5s spot migrates to the stream
                    // form — every reader requires >5000, so a
                    // 1-5000ms spot stored here was dead weight in
                    // the 100-line cap. The path entry is still
                    // dropped either way.
                    if (streamUrl != null && pathPos > 5000 && Prefs.talkPosGet(DownloadedActivity.this, streamUrl) == 0)
                        Prefs.talkPosPut(DownloadedActivity.this, streamUrl, pathPos);
                    Prefs.talkPosRemove(DownloadedActivity.this, path);
                }
                String savedUrl = sp.getString("lastPosUrl", "");
                if (path.equals(savedUrl)) {
                    if (streamUrl != null)
                        sp.edit().putString("lastPosUrl", streamUrl).apply();
                    else
                        sp.edit().putInt("lastPos", 0).putString("lastPosUrl", "").apply();
                }
            }
            if (!drop.isEmpty()) {
                for (String k : drop) map.remove(k);
                sp.edit().putString("dlMap", map.toString()).apply();
                // v11.83: the delete orphaned these URLs' dlQueued
                // entries — no prune leg ever drops an unmapped,
                // jobless entry (every leg keeps it: the mapped
                // check needs a dlMap name), so without this the
                // talk showed ⏳ forever and every enqueue toasted
                // "already queued": it could never be re-downloaded
                // short of clearing prefs. The file is gone and its
                // job is done, so the queue entry is meaningless.
                try {
                    org.json.JSONArray qa = new org.json.JSONArray(sp.getString("dlQueued", "[]"));
                    org.json.JSONArray out = new org.json.JSONArray();
                    boolean qChanged = false;
                    for (int i = 0; i < qa.length(); i++) {
                        String u = qa.optString(i);
                        if (drop.contains(u)) { qChanged = true; continue; }
                        out.put(u);
                    }
                    if (qChanged) sp.edit().putString("dlQueued", out.toString()).apply();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    // v4.57: the library's own queue menu — the Talks chooser's
    // showTalkQueueMenu (v4.47–v4.53) ported to downloaded
    // files. Identity is the file path (not a talk URL), the
    // list is the shown shelf for from-here and the full entries
    // shelf for whole-list, and the playing file is the
    // service's current path (a Room stream URL can never equal
    // a file path, so no kind check is needed). Every mutation
    // goes through saveLibQueue, so ◈ marks, Next, auto-advance
    // and the row-tap trim all see it at once.
    // v4.59: the favourites / unlistened bulk verbs ARE ported
    // now — v4.57 declined them as having no file-level meaning,
    // but the v4.24 per-load mark caches (libFavFiles /
    // libListenedFiles, filenames via the dlMap reverse lookup)
    // give exactly that meaning, and the rows already show the
    // ★/✓ marks, so one tap queues the shelf's favourites or
    // its not-yet-listened files in shelf order, skipping the
    // playing file and already-queued paths like the chooser.
    // v11.24: sounding library path with the unbound window covered -- the row menu's
    // queue-membership reads all used bound-only identity (service != null ?
    // getCurrentUrl() : "\\0"), so in the fast-open window before onServiceConnected
    // (or after a service death) a live library session's sounding file read as
    // not-playing: the menu offered it play-next / add / from-here / shown-set /
    // queue-all, and the v11.12/v11.14 tap-time re-checks read the same bound-only
    // cur and could not catch it either -- the sounding file landed in the queue
    // and replayed after the next talk, exactly the defect those runs fixed for the
    // bound case. The mirror is the v10.43 row-mark gate itself (liveSession +
    // liveHasTrack + lastKind "lib" -> lastLibPath), so the menu can never disagree
    // with the row's own playing mark; a Room session, a stopped session, or a
    // bound trackless service all read "" here, and "" equals no shelf path, so
    // every skip comparison keeps its old meaning on those paths.
    private String soundingLibPath() {
        if (service != null) {
            String c = service.getCurrentUrl();
            return c == null ? "" : c;
        }
        if (!AudioService.liveSession || !AudioService.liveHasTrack) return "";
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if ("lib".equals(sp.getString("lastKind", ""))) {
                String lp = sp.getString("lastLibPath", "");
                return lp == null ? "" : lp;
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void showLibQueueMenu(final int shownPos) {
        if (shownPos < 0 || shownPos >= libDisplayCount()) return;
        Fx.tap(DownloadedActivity.this);
        final Entry t = libEntryAtDisplay(shownPos);
        if (t == null) {
            healLibQueueOnly();
            if (adapter != null) adapter.notifyDataSetChanged();
            return;
        }
        final String path = t.file.getAbsolutePath();
        // v6.53: this row's shareable identity — the library mirror
        // of the chooser's v6.52 single-talk share and the drawer's
        // trailing share. The library's row menu had no single-file
        // share at all (its long-press restarts the file, and only
        // the 2+-linked shown set could be shared, v6.46), so a
        // listener opening the ⋮ menu found queue, delete, mark,
        // play and shown-set verbs but no way to share the file
        // itself. Unlike the chooser's unconditional offer, this
        // one is link-gated: a file's original link comes only
        // from the dlMap reverse lookup by filename (the v6.46
        // rule), and a file with no dlMap entry has no link to
        // share — offering it would mean a title-only share or a
        // guessed URL, both worse than no offer (the v1.47 /
        // stripShareText lesson). The sayadaw, when one was
        // recorded at download time, comes from dlSayadaw for
        // that URL (v5.45); an older download keeps the
        // title-only format, exactly like stripShareText. The
        // offer and the handler below read these same finals,
        // so they can never disagree, and the playing file is
        // included (link-gated only, like the chooser's playing
        // rows): the menu row is the file itself, and the strip
        // share already covers the sounding session.
        final String shareUrl653;
        final String shareSayadaw653;
        {
            String u653 = null;
            String sd653 = null;
            try {
                android.content.SharedPreferences sp653 =
                        getSharedPreferences("dhamma", MODE_PRIVATE);
                org.json.JSONObject map653 =
                        new org.json.JSONObject(sp653.getString("dlMap", "{}"));
                java.util.Iterator<String> keys653 = map653.keys();
                // v8.42: exactly-one match, never the first match —
                // the v8.41 strip-share rule, extended to the
                // library row's own share/favourite/copy verbs.
                // A dual-claimed file's old first-match link let
                // the row share (and copy, and star-toggle) name
                // the WRONG talk; every one of those offers is
                // gated on shareUrl653 != null below, so an
                // ambiguous file now simply shows none of them
                // instead of a wrong one. Zero matches still
                // resolves to null, as before.
                String hit653 = null;
                int matches653 = 0;
                while (keys653.hasNext()) {
                    String k653 = keys653.next();
                    if (t.file.getName().equals(map653.optString(k653, ""))) { hit653 = k653; matches653++; }
                }
                u653 = matches653 == 1 ? hit653 : null;
                if (u653 != null) {
                    org.json.JSONObject sdMap653 =
                            new org.json.JSONObject(sp653.getString("dlSayadaw", "{}"));
                    String n653 = sdMap653.optString(u653, "");
                    if (n653.length() > 0) sd653 = n653;
                }
            } catch (Exception ignored) {}
            shareUrl653 = u653;
            shareSayadaw653 = sd653;
        }
        refreshLibQueueCache();
        final java.util.ArrayList<String> queue = new java.util.ArrayList<String>(libQueueCache);
        boolean playing = path.equals(soundingLibPath()); // v11.24: unbound-window mirror, not bound-only
        final boolean queued = !playing && queue.contains(path);
        java.util.ArrayList<String> items = new java.util.ArrayList<String>();
        // v13.15: key list parallel to items — dialogs dispatch on keys,
        // not on re-evaluated labels (language-proof).
        java.util.ArrayList<String> itemKeys = new java.util.ArrayList<String>();
        if (!playing && !queued) { items.add(Lang.t(DownloadedActivity.this, "menu_play_next_short")); itemKeys.add("menu_play_next_short"); items.add(Lang.t(DownloadedActivity.this, "menu_queue_add")); itemKeys.add("menu_queue_add"); }
        if (!playing) { items.add(Lang.t(DownloadedActivity.this, "menu_queue_from_here")); itemKeys.add("menu_queue_from_here"); }
        // v6.38: queue only the shown files — the shown-set base
        // verb the library's ⋮ menu still lacked (the Talks
        // chooser gained it as v6.26, the Room drawer as v6.14).
        // The library's bulk verbs below read the full shelf
        // (entries), so a listener who had narrowed the library
        // — a search in the box, or a ★-only / unlistened-only
        // filter — and wanted just those files queued had to
        // tap the first shown row's "ဒီကနေ အားလုံး တန်းစီ",
        // which queues from that row down, not the shown set
        // from any row. Offered from ANY row only while narrowed
        // (a search, or any filter mode) and never in queue-only
        // view — the queue-only view already IS the queue, so
        // queueing it is a no-op that would only toast
        // "ရှိပြီးသားပါ". The handler below reads the same
        // display positions the rows themselves render
        // (libEntryAtDisplay over libDisplayCount, shownDl in
        // the normal view), in display order, skipping the
        // playing file and already-queued paths like every
        // other queue verb here.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)
                && libDisplayCount() > 0) items.add(Lang.t(DownloadedActivity.this, "dl_menu_queue_shown")); itemKeys.add("dl_menu_queue_shown");
        // v6.68: queue only the shown files NEXT — the head-insert
        // twin of the v6.38 append above, and the library mirror
        // of the Talks chooser's v6.66 verb and the Room
        // drawer's v6.67 verb. A listener who had narrowed the
        // library — a search in the box, or a ★-only /
        // unlistened-only filter — and liked exactly the files
        // on screen could append them (v6.38) or play them at
        // once (which stops the current file), but putting
        // them right after the file now playing meant
        // appending and then moving each file to the top one
        // by one. Offered from ANY row only while narrowed
        // and never in queue-only view (the queue-only view
        // already IS the queue), only when the existing queue
        // is non-empty (with an empty queue a head insert is
        // identical to the v6.38 append, so that verb already
        // covers it), and only when at least one shown file
        // is actually queueable (not the playing file, not
        // already queued), so it never appears on the full
        // list and never offers a guaranteed no-op. The
        // handler below reads the same display positions the
        // rows themselves render, in display order.
        boolean hasQueueableShown68 = false;
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)
                && !queue.isEmpty()) {
            String cur68 = soundingLibPath(); // v11.24: unbound-window mirror
            for (int i = 0; i < libDisplayCount(); i++) {
                Entry se68 = libEntryAtDisplay(i);
                if (se68 == null) continue;
                String u68 = se68.file.getAbsolutePath();
                if (u68.equals(cur68)) continue;
                if (queue.contains(u68)) continue;
                hasQueueableShown68 = true; break;
            }
        }
        if (hasQueueableShown68) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_queue_next_shown")); itemKeys.add("dl_menu_queue_next_shown"); }
        // v6.39: delete only the shown files — the shown-set
        // delete verb the library's ⋮ menu still lacked (the
        // Talks chooser gained it as v6.37; the library had
        // only per-row delete and delete-ALL). A listener who
        // had narrowed the library — a search in the box, or a
        // ★-only / unlistened-only filter — and wanted just
        // those saved files off the phone had to delete them
        // row by row, or wipe the whole library. Offered from
        // ANY row only while narrowed and never in queue-only
        // view (the queue-only view is a play order, not a
        // shelf to prune), and only when at least one shown
        // file is not the file still playing — so it never
        // appears on the full list, never offers an empty set,
        // and never offers a view whose only shown file is the
        // one in use. The handler below re-checks at confirm
        // time and skips the playing file again there.
        boolean hasDeletableShown = false;
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            String cur0 = soundingLibPath(); // v11.25: unbound-window mirror, like the queue gates since v11.24 — a bound-only read counted the sounding file as deletable while unbound
            for (int i = 0; i < libDisplayCount(); i++) {
                Entry de = libEntryAtDisplay(i);
                if (de != null && !de.file.getAbsolutePath().equals(cur0)) { hasDeletableShown = true; break; }
            }
        }
        if (hasDeletableShown) { items.add(Lang.t(DownloadedActivity.this, "dl_del_shown")); itemKeys.add("dl_del_shown"); }
        // v6.94: delete only the shown subset's downloaded files (starred, or
        // not-yet-heard) — the library mirrors of the Talks chooser's v6.93 pair,
        // and the subset delete twins of the library's own v6.39 shown-set delete.
        // Audit first confirmed the gap: the library could delete a narrowed set's
        // saved files in one tap (v6.39) and could play/share/copy just the shown
        // ★ / shown unlistened subset (v6.74/v6.90/v6.92), but freeing space for
        // just that subset meant deleting the whole narrowed set and
        // re-downloading the rest, or deleting file by file. Same gates as v6.39,
        // applied per subset: offered from ANY row only while narrowed and never
        // in queue-only view (the queue-only view is a play order, not a shelf to
        // prune), and only when at least one shown subset file is not the file
        // still playing — one deletable subset file is enough to offer (a delete
        // is not a 2+ bulk play, unlike v6.90/v6.92's gates). Membership is the
        // rows' own caches (★ in libFavFiles; unlistened = absent from
        // libListenedFiles, the v6.74 rule), so the offer and the handler below
        // can never disagree. Labels carry no 🗑 prefix, like the library's own
        // v6.39 shown-set delete (the chooser's v6.93 pair has one).
        boolean hasDlFavShown94 = false, hasDlNewShown94 = false;
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            String cur94 = soundingLibPath(); // v11.25: unbound-window mirror, like cur0 above
            for (int i = 0; i < libDisplayCount(); i++) {
                Entry se94 = libEntryAtDisplay(i);
                if (se94 == null) continue;
                if (se94.file.getAbsolutePath().equals(cur94)) continue;
                if (libFavFiles.contains(se94.file.getName())) hasDlFavShown94 = true;
                if (!libListenedFiles.contains(se94.file.getName())) hasDlNewShown94 = true;
                if (hasDlFavShown94 && hasDlNewShown94) break;
            }
        }
        if (hasDlFavShown94) { items.add(Lang.t(DownloadedActivity.this, "dl_del_shown_star")); itemKeys.add("dl_del_shown_star"); }
        if (hasDlNewShown94) { items.add(Lang.t(DownloadedActivity.this, "dl_del_shown_new")); itemKeys.add("dl_del_shown_new"); }
        // v6.40: shown-set ★ / unlistened queue verbs — the
        // shown-set variants of the v4.59 bulk verbs below,
        // which read the full shelf (entries). A listener who
        // had narrowed the library — a search in the box, or a
        // ★-only / unlistened-only filter — and wanted just
        // the shown favourites (or just the shown not-yet-
        // listened files) queued had to use "ပြထားတာ တန်းစီ"
        // (v6.38) and take everything shown, or the full-shelf
        // bulk verbs and take files outside the narrowed view.
        // Offered from ANY row only while narrowed and never
        // in queue-only view (the queue-only view already IS
        // the queue, so queueing a slice of it is a no-op
        // that would only toast "ရှိပြီးသားပါ"); each verb is
        // offered only when at least one shown file carries
        // its mark, so neither ever offers an empty set. The
        // handlers below read the same display positions the
        // rows themselves render, in display order, with the
        // same skip rules as every other queue verb here
        // (playing file, already queued).
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            boolean hasShownFav = false, hasShownNew = false;
            for (int i = 0; i < libDisplayCount(); i++) {
                Entry se = libEntryAtDisplay(i);
                if (se == null) continue;
                if (libFavFiles.contains(se.file.getName())) hasShownFav = true;
                if (!libListenedFiles.contains(se.file.getName())) hasShownNew = true;
                if (hasShownFav && hasShownNew) break;
            }
            if (hasShownFav) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_queue_shown_star")); itemKeys.add("dl_menu_queue_shown_star"); }
            if (hasShownNew) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_queue_shown_new")); itemKeys.add("dl_menu_queue_shown_new"); }
        }
        // v6.71: queue only the shown favourites / shown not-yet-
        // listened files NEXT — the head-insert twins of the v6.40
        // append pair above, and the library mirrors of the Talks
        // chooser's v6.69 pair and the Room drawer's v6.70 pair.
        // Audit first confirmed the gap: the library could append
        // a shown ★ / unlistened subset (v6.40) and head-insert
        // the whole shown set (v6.68), but putting just the shown
        // ★ (or just the shown not-yet-heard) files right after
        // the file now playing meant appending the subset and
        // then moving each file to the top one by one. Offered
        // from ANY row only while narrowed and never in
        // queue-only view (the queue-only view already IS the
        // queue), only when the existing queue is non-empty
        // (with an empty queue a head insert is identical to
        // the v6.40 append), and only when at least one shown
        // file is actually queueable (not the playing file, not
        // already queued) AND carries the mark (★ in
        // libFavFiles; unlistened = absent from
        // libListenedFiles), so neither ever appears on the full
        // list and neither ever offers a guaranteed no-op. The
        // handlers below read the same display positions the
        // rows themselves render, in display order.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)
                && !queue.isEmpty()) {
            boolean hasNextFavShown71 = false, hasNextNewShown71 = false;
            String cur71 = soundingLibPath(); // v11.24: unbound-window mirror
            for (int i = 0; i < libDisplayCount(); i++) {
                Entry se71 = libEntryAtDisplay(i);
                if (se71 == null) continue;
                String u71 = se71.file.getAbsolutePath();
                if (u71.equals(cur71)) continue;
                if (queue.contains(u71)) continue;
                if (libFavFiles.contains(se71.file.getName())) hasNextFavShown71 = true;
                if (!libListenedFiles.contains(se71.file.getName())) hasNextNewShown71 = true;
                if (hasNextFavShown71 && hasNextNewShown71) break;
            }
            if (hasNextFavShown71) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_queue_next_shown_star")); itemKeys.add("dl_menu_queue_next_shown_star"); }
            if (hasNextNewShown71) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_queue_next_shown_new")); itemKeys.add("dl_menu_queue_next_shown_new"); }
        }
        // v6.41: remove only the shown files from the queue —
        // the shown-set un-queue verb the library's ⋮ menu
        // still lacked (the Talks chooser gained it as v6.29;
        // the library had only per-row "တန်းစီမှ ထုတ်" and
        // clear-ALL). A listener who had narrowed the library
        // — a search in the box, or a ★-only / unlistened-only
        // filter — and wanted just those files out of the
        // queue had to un-queue them row by row, or wipe the
        // whole queue. Offered from ANY row only while
        // narrowed and never in queue-only view (the
        // queue-only view already IS the queue, so removing
        // its shown set is just "တန်းစီအားလုံး ရှင်း"),
        // and only when at least one shown file is actually
        // queued (the playing file never queues, so it is
        // skipped like every other queue verb here), so it
        // never offers an empty set. The handler below reads
        // the same display positions the rows themselves
        // render, in display order; queued files outside the
        // shown set stay queued.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            boolean hasQueuedShown = false;
            String cur41 = service != null ? service.getCurrentUrl() : "\0";
            for (int i = 0; i < libDisplayCount(); i++) {
                Entry se = libEntryAtDisplay(i);
                if (se == null) continue;
                String u41 = se.file.getAbsolutePath();
                if (u41.equals(cur41)) continue;
                if (queue.contains(u41)) { hasQueuedShown = true; break; }
            }
            if (hasQueuedShown) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_unqueue_shown")); itemKeys.add("dl_menu_unqueue_shown"); }
        }
        // v7.00: remove only the shown ★ / shown not-yet-
        // listened files from the queue — the subset un-queue
        // pair the library's ⋮ menu still lacked (the audit
        // for the v6.99 run confirmed it). The library could
        // append a shown ★ / unlistened subset (v6.40), put
        // it next (v6.71), and remove the whole shown set
        // (v6.41), but taking just the shown ★ files — or
        // just the shown not-yet-heard files — out of the
        // queue meant removing the whole shown set (which
        // also drops non-starred / already-heard files) or
        // un-queueing row by row. Unlike the v6.97/v6.99
        // mark pair, each subset verb here is genuinely
        // narrower than the shown-set verb: queued files
        // outside the subset stay queued. Offered from ANY
        // row only while narrowed and never in queue-only
        // view (same gates as v6.41), and only when at
        // least one shown file is actually queued AND
        // carries the mark (★ in libFavFiles; unlistened =
        // absent from libListenedFiles, the same marks the
        // v6.40/v6.71 queue verbs use), so neither ever
        // offers an empty set. The handlers below read the
        // same display positions the rows themselves render,
        // in display order.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            boolean hasQueuedFavShown00 = false, hasQueuedNewShown00 = false;
            String cur00 = service != null ? service.getCurrentUrl() : "\0";
            for (int i = 0; i < libDisplayCount(); i++) {
                Entry se00 = libEntryAtDisplay(i);
                if (se00 == null) continue;
                String u00 = se00.file.getAbsolutePath();
                if (u00.equals(cur00)) continue;
                if (!queue.contains(u00)) continue;
                if (libFavFiles.contains(se00.file.getName())) hasQueuedFavShown00 = true;
                if (!libListenedFiles.contains(se00.file.getName())) hasQueuedNewShown00 = true;
                if (hasQueuedFavShown00 && hasQueuedNewShown00) break;
            }
            if (hasQueuedFavShown00) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_unqueue_shown_star")); itemKeys.add("dl_menu_unqueue_shown_star"); }
            if (hasQueuedNewShown00) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_unqueue_shown_new")); itemKeys.add("dl_menu_unqueue_shown_new"); }
        }
        // v6.42: favourite only the shown files — the shown-set
        // favourite verb the library's ⋮ menu still lacked (the
        // Room drawer gained it as v6.15; the library had only
        // the ★-only view and the shown-set queue/delete verbs).
        // A listener who had narrowed the library — a search in
        // the box, or an unlistened-only filter — and wanted just
        // those files starred had to star them talk by talk in
        // the Room/chooser, or nowhere: the library has no
        // per-row star toggle. Offered from ANY row only while
        // narrowed and never in queue-only view (the queue-only
        // view is a play order, not a shelf to star), and only
        // when at least one shown file resolves through the
        // dlMap to a source URL not already in favTalks — so it
        // never appears on the full list, never offers an empty
        // set, and never offers a ★-only view (whose rows are
        // all starred already) or a file with no dlMap entry
        // (no source URL, nothing to star). The handler below
        // reads the same display positions the rows themselves
        // render, in display order, and the shared saveLibQueue
        // + updateTotal tail rebinds the rows so the ★ marks
        // move at once.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            boolean hasNonFav642 = false;
            try {
                android.content.SharedPreferences sp642 =
                        getSharedPreferences("dhamma", MODE_PRIVATE);
                java.util.Set<String> ft642 = sp642.getStringSet("favTalks", null);
                org.json.JSONObject map642 =
                        new org.json.JSONObject(sp642.getString("dlMap", "{}"));
                // v8.47: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                // filename resolves to no URL, never the first claimant.
                java.util.HashMap<String, String> fn642 = new java.util.HashMap<String, String>();
                java.util.HashMap<String, Integer> cnt642 = new java.util.HashMap<String, Integer>();
                java.util.Iterator<String> keys642 = map642.keys();
                while (keys642.hasNext()) {
                    String u642 = keys642.next();
                    String f642 = map642.optString(u642, "");
                    if (f642.length() > 0) { fn642.put(f642, u642); Integer c642 = cnt642.get(f642); cnt642.put(f642, c642 == null ? 1 : c642 + 1); }
                }
                for (String k642 : cnt642.keySet()) if (cnt642.get(k642) > 1) fn642.remove(k642);
                for (int i = 0; i < libDisplayCount(); i++) {
                    Entry se642 = libEntryAtDisplay(i);
                    if (se642 == null) continue;
                    String u642 = fn642.get(se642.file.getName());
                    if (u642 != null && (ft642 == null || !ft642.contains(u642))) { hasNonFav642 = true; break; }
                }
            } catch (Exception ignored) {}
            if (hasNonFav642) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_fav_shown")); itemKeys.add("dl_menu_fav_shown"); }
        }
        // v6.43: un-favourite only the shown files — the shown-set
        // un-star verb the library's ⋮ menu still lacked (the
        // Talks chooser gained it as v6.28, the Room drawer as
        // v6.16; the library gained the star side as v6.42 but
        // no way back). A listener in a ★-only view (where v6.42
        // never appears, since every row is already starred) or
        // over a searched subset with some starred rows had no
        // one-tap path to clear just those stars: un-starring
        // row by row in the Room/chooser, or clearing ALL
        // favourites in Settings (which also wipes other
        // sayadaws' stars). Offered from ANY row only while
        // narrowed (a search, or any filter mode — including
        // the ★-only view itself, the chooser's v6.28 rule) and
        // never in queue-only view (a play order, not a shelf
        // to un-star), and only when at least one shown file
        // carries the ★ mark (libFavFiles, the rows' own mark
        // cache), so it never appears on the full list and never
        // offers an empty set. The handler below removes the
        // shown files' source URLs from favTalks through the
        // dlMap (the v6.42 reverse lookup) and re-pours the
        // filter, so a ★-only view drops the un-starred rows
        // at once instead of leaving them visible without
        // their ★, like the chooser's rebuildFilter.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            boolean hasFav643 = false;
            for (int i = 0; i < libDisplayCount(); i++) {
                Entry se643 = libEntryAtDisplay(i);
                if (se643 != null && libFavFiles.contains(se643.file.getName())) { hasFav643 = true; break; }
            }
            if (hasFav643) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_unfav_shown")); itemKeys.add("dl_menu_unfav_shown"); }
        }
        // v7.05: star / un-star only the shown not-yet-
        // listened files — the library mirror of the Talks
        // chooser's v7.04 pair, and the subset twins of the
        // library's own v6.42/v6.43 shown-set favourite pair.
        // Audit first confirmed the gap: the library could star
        // a narrowed set in one tap (v6.42) and clear just its
        // shown stars (v6.43), but starring just the fresh
        // files of a mixed heard + fresh view meant starring
        // the whole shown set (also starring already-heard
        // files, polluting the ★ filter) or starring file by
        // file in the Room/chooser; clearing just the fresh
        // stars back meant un-starring the whole shown set
        // (also clearing heard favourites they keep) or file
        // by file. Same gates as v6.42/v6.43, applied per
        // subset: offered from ANY row only while narrowed
        // (a search, or any filter mode) and never in
        // queue-only view (a play order, not a shelf to star),
        // and only when at least one shown file is
        // not-yet-listened (absent from libListenedFiles, the
        // rows' own ✓ cache) AND carries the slice's mark —
        // star needs a dlMap source URL not already in
        // favTalks (v6.42's resolvability rule, so a file with
        // no dlMap entry never offers a no-op), un-star needs
        // the ★ mark itself (libFavFiles, v6.43's rule). No
        // playing-file skip, like the chooser's v7.04 pair
        // (starring the sounding file is what the strip star
        // does). The handlers below read the same display
        // positions the rows themselves render, in display
        // order, and the shared tail below rebinds the rows
        // so the ★ marks move at once.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            boolean hasNonFavNew705 = false, hasFavNew705 = false;
            try {
                android.content.SharedPreferences sp705 =
                        getSharedPreferences("dhamma", MODE_PRIVATE);
                java.util.Set<String> ft705 = sp705.getStringSet("favTalks", null);
                org.json.JSONObject map705 =
                        new org.json.JSONObject(sp705.getString("dlMap", "{}"));
                // v8.47: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                // filename resolves to no URL, never the first claimant.
                java.util.HashMap<String, String> fn705 = new java.util.HashMap<String, String>();
                java.util.HashMap<String, Integer> cnt705 = new java.util.HashMap<String, Integer>();
                java.util.Iterator<String> keys705 = map705.keys();
                while (keys705.hasNext()) {
                    String u705 = keys705.next();
                    String f705 = map705.optString(u705, "");
                    if (f705.length() > 0) { fn705.put(f705, u705); Integer c705 = cnt705.get(f705); cnt705.put(f705, c705 == null ? 1 : c705 + 1); }
                }
                for (String k705 : cnt705.keySet()) if (cnt705.get(k705) > 1) fn705.remove(k705);
                for (int i = 0; i < libDisplayCount(); i++) {
                    Entry se705 = libEntryAtDisplay(i);
                    if (se705 == null) continue;
                    if (libListenedFiles.contains(se705.file.getName())) continue;
                    if (libFavFiles.contains(se705.file.getName())) { hasFavNew705 = true; continue; }
                    String u705 = fn705.get(se705.file.getName());
                    if (u705 != null && (ft705 == null || !ft705.contains(u705))) hasNonFavNew705 = true;
                    if (hasNonFavNew705 && hasFavNew705) break;
                }
            } catch (Exception ignored) {}
            if (hasNonFavNew705) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_fav_shown_new")); itemKeys.add("dl_menu_fav_shown_new"); }
            if (hasFavNew705) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_unfav_shown_new")); itemKeys.add("dl_menu_unfav_shown_new"); }
        }
        // v6.44: mark only the shown files listened — the
        // shown-set mark verb the library's ⋮ menu still
        // lacked (the Talks chooser gained it as v6.30, the
        // Room drawer as v6.18; the library had only the
        // shown-set queue/delete/star/un-star verbs). A
        // listener who had narrowed the library — a search in
        // the box, or a ★-only / unlistened-only filter — and
        // wanted just those files marked ✓ had to play each
        // one to the end, or mark them talk by talk in the
        // Room/chooser. Offered from ANY row only while
        // narrowed and never in queue-only view (the
        // queue-only view is a play order, not a shelf to
        // mark), and only when at least one shown file
        // resolves through the dlMap to a source URL not
        // already in playedTalks and is not the file still
        // playing (it earns its ✓ by finishing, like the
        // chooser's v6.30 skip) — so it never appears on the
        // full list, never offers an empty set, and never
        // offers a played-only view (whose rows are all ✓
        // already) or a file with no dlMap entry (no source
        // URL, nothing to mark). The handler below adds the
        // shown files' source URLs to playedTalks and
        // re-pours the filter, so an unlistened-only view
        // drops the marked rows at once (its empty view
        // explains itself); neither the queue, the shelf
        // order nor any saved resume position is mutated.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            boolean hasUnplayed644 = false;
            try {
                android.content.SharedPreferences sp644 =
                        getSharedPreferences("dhamma", MODE_PRIVATE);
                java.util.Set<String> pt644 = sp644.getStringSet("playedTalks", null);
                org.json.JSONObject map644 =
                        new org.json.JSONObject(sp644.getString("dlMap", "{}"));
                // v8.47: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                // filename resolves to no URL, never the first claimant.
                java.util.HashMap<String, String> fn644 = new java.util.HashMap<String, String>();
                java.util.HashMap<String, Integer> cnt644 = new java.util.HashMap<String, Integer>();
                java.util.Iterator<String> keys644 = map644.keys();
                while (keys644.hasNext()) {
                    String u644 = keys644.next();
                    String f644 = map644.optString(u644, "");
                    if (f644.length() > 0) { fn644.put(f644, u644); Integer c644 = cnt644.get(f644); cnt644.put(f644, c644 == null ? 1 : c644 + 1); }
                }
                for (String k644 : cnt644.keySet()) if (cnt644.get(k644) > 1) fn644.remove(k644);
                String cur644 = service != null ? service.getCurrentUrl() : "\0";
                for (int i = 0; i < libDisplayCount(); i++) {
                    Entry se644 = libEntryAtDisplay(i);
                    if (se644 == null) continue;
                    if (se644.file.getAbsolutePath().equals(cur644)) continue;
                    String u644 = fn644.get(se644.file.getName());
                    if (u644 != null && (pt644 == null || !pt644.contains(u644))) { hasUnplayed644 = true; break; }
                }
            } catch (Exception ignored) {}
            if (hasUnplayed644) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_mark_shown")); itemKeys.add("dl_menu_mark_shown"); }
        // v6.97: mark only the shown ★ files listened — the
        // library mirror of the Talks chooser's v6.95 ★ mark,
        // and the subset twin of the library's own v6.44
        // shown-set mark. Audit first confirmed the gap: the
        // library could mark a narrowed set listened in one
        // tap (v6.44) and could mark just the shown ★ set in
        // the chooser (v6.95), but marking just the shown ★
        // files here meant marking the whole narrowed set
        // (which also marks non-starred files) or marking
        // file by file. Offered from ANY row only while
        // narrowed and never in queue-only view (the same
        // gates as v6.44), and only when at least one shown
        // file is starred (in libFavFiles), resolves through
        // the dlMap to a source URL not already in
        // playedTalks, and is not the file still playing (it
        // earns its ✓ by finishing, like v6.44) — so it never
        // appears on the full list, never offers a no-op set,
        // and never offers a ★-only view whose starred rows
        // are all ✓ already. The handler below is v6.44's
        // shape with the ★ filter added, so an
        // unlistened-only view drops the marked rows at once.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            boolean hasMarkFavShown697 = false;
            try {
                android.content.SharedPreferences sp697 =
                        getSharedPreferences("dhamma", MODE_PRIVATE);
                java.util.Set<String> pt697 = sp697.getStringSet("playedTalks", null);
                org.json.JSONObject map697 =
                        new org.json.JSONObject(sp697.getString("dlMap", "{}"));
                // v8.47: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                // filename resolves to no URL, never the first claimant.
                java.util.HashMap<String, String> fn697 = new java.util.HashMap<String, String>();
                java.util.HashMap<String, Integer> cnt697 = new java.util.HashMap<String, Integer>();
                java.util.Iterator<String> keys697 = map697.keys();
                while (keys697.hasNext()) {
                    String u697 = keys697.next();
                    String f697 = map697.optString(u697, "");
                    if (f697.length() > 0) { fn697.put(f697, u697); Integer c697 = cnt697.get(f697); cnt697.put(f697, c697 == null ? 1 : c697 + 1); }
                }
                for (String k697 : cnt697.keySet()) if (cnt697.get(k697) > 1) fn697.remove(k697);
                String cur697 = service != null ? service.getCurrentUrl() : "\0";
                for (int i = 0; i < libDisplayCount(); i++) {
                    Entry se697 = libEntryAtDisplay(i);
                    if (se697 == null) continue;
                    if (se697.file.getAbsolutePath().equals(cur697)) continue;
                    if (!libFavFiles.contains(se697.file.getName())) continue;
                    String u697 = fn697.get(se697.file.getName());
                    if (u697 != null && (pt697 == null || !pt697.contains(u697))) { hasMarkFavShown697 = true; break; }
                }
            } catch (Exception ignored) {}
            if (hasMarkFavShown697) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_mark_shown_star")); itemKeys.add("dl_menu_mark_shown_star"); }
        }
        }
        // v6.98: un-mark only the shown ★ files listened — the
        // library mirror of the Talks chooser's v6.96 ★
        // un-mark, and the complement of the library's own
        // v6.97 ★ mark. Audit first confirmed the gap: the
        // library could mark just the shown ★ files listened
        // in one tap (v6.97), but un-marking those starred
        // files again took un-marking the whole narrowed set
        // (v6.45, which also clears non-starred marks), or
        // clearing ALL listened marks in Settings (which also
        // wipes other sayadaws' marks). Offered from ANY row
        // only while narrowed and never in queue-only view
        // (the same gates as v6.44/v6.97), and only when at
        // least one shown file is starred (in libFavFiles),
        // resolves through the dlMap to a source URL already
        // in playedTalks, and is not the file still playing
        // (it keeps its ✓ while it plays, like v6.45) — so it
        // never appears on the full list, never offers a
        // no-op set, and never offers a ★-only view whose
        // starred rows are all unplayed. The handler below
        // is v6.45's shape with the ★ filter added, so the
        // rows stay visible with their ✓ removed at once.
        // Family note: the "အသစ်" twin of this verb needs no
        // new item — v6.45 already un-marks all played shown
        // files, which is exactly the un-mark mirror of an
        // unplayed-only mark; with this ★ item the library
        // subset un-mark family is complete.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            boolean hasUnmarkFavShown698 = false;
            try {
                android.content.SharedPreferences sp698 =
                        getSharedPreferences("dhamma", MODE_PRIVATE);
                java.util.Set<String> pt698 = sp698.getStringSet("playedTalks", null);
                org.json.JSONObject map698 =
                        new org.json.JSONObject(sp698.getString("dlMap", "{}"));
                // v8.47: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                // filename resolves to no URL, never the first claimant.
                java.util.HashMap<String, String> fn698 = new java.util.HashMap<String, String>();
                java.util.HashMap<String, Integer> cnt698 = new java.util.HashMap<String, Integer>();
                java.util.Iterator<String> keys698 = map698.keys();
                while (keys698.hasNext()) {
                    String u698 = keys698.next();
                    String f698 = map698.optString(u698, "");
                    if (f698.length() > 0) { fn698.put(f698, u698); Integer c698 = cnt698.get(f698); cnt698.put(f698, c698 == null ? 1 : c698 + 1); }
                }
                for (String k698 : cnt698.keySet()) if (cnt698.get(k698) > 1) fn698.remove(k698);
                String cur698 = service != null ? service.getCurrentUrl() : "\0";
                for (int i = 0; i < libDisplayCount(); i++) {
                    Entry se698 = libEntryAtDisplay(i);
                    if (se698 == null) continue;
                    if (se698.file.getAbsolutePath().equals(cur698)) continue;
                    if (!libFavFiles.contains(se698.file.getName())) continue;
                    String u698 = fn698.get(se698.file.getName());
                    if (u698 != null && pt698 != null && pt698.contains(u698)) { hasUnmarkFavShown698 = true; break; }
                }
            } catch (Exception ignored) {}
            if (hasUnmarkFavShown698) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_unmark_shown_star")); itemKeys.add("dl_menu_unmark_shown_star"); }
        }
        // v6.99: mark only the shown အသစ် files listened — the
        // library mirror of the Talks chooser's v6.95 အသစ်
        // mark, and the subset twin of the library's own
        // v6.44 shown-set mark. Audit first confirmed the
        // gap: the library menu carries the ★ side of the
        // subset mark pair (v6.97) and every other library
        // subset family carries both labels (queue,
        // queue-next, delete, share, copy-link, play,
        // shuffle, reverse all have ★ and အသစ် twins), but
        // a listener scanning the menu for the အသစ် twin
        // of the ★ mark found nothing — they had to know
        // the plain shown-set mark (v6.44) means the same
        // thing. Offered from ANY row only while narrowed
        // and never in queue-only view (the same gates as
        // v6.44/v6.97), and only when at least one shown
        // file resolves through the dlMap to a source URL
        // not already in playedTalks and is not the file
        // still playing (it earns its ✓ by finishing, like
        // v6.44) — so it never appears on the full list
        // and never offers a no-op set. Stated plainly:
        // the marked set is v6.44's set (all unplayed shown
        // files), exactly as the chooser's v6.95 အသစ် mark
        // matches its v6.30 shown-set mark; the gain is
        // menu parity with the chooser and with the ★/အသစ်
        // pairs in every other library subset family, not a
        // new set. The handler below is v6.44's shape with
        // the subset label, so an unlistened-only view
        // drops the marked rows at once.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            boolean hasMarkNewShown699 = false;
            try {
                android.content.SharedPreferences sp699 =
                        getSharedPreferences("dhamma", MODE_PRIVATE);
                java.util.Set<String> pt699 = sp699.getStringSet("playedTalks", null);
                org.json.JSONObject map699 =
                        new org.json.JSONObject(sp699.getString("dlMap", "{}"));
                // v8.47: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                // filename resolves to no URL, never the first claimant.
                java.util.HashMap<String, String> fn699 = new java.util.HashMap<String, String>();
                java.util.HashMap<String, Integer> cnt699 = new java.util.HashMap<String, Integer>();
                java.util.Iterator<String> keys699 = map699.keys();
                while (keys699.hasNext()) {
                    String u699 = keys699.next();
                    String f699 = map699.optString(u699, "");
                    if (f699.length() > 0) { fn699.put(f699, u699); Integer c699 = cnt699.get(f699); cnt699.put(f699, c699 == null ? 1 : c699 + 1); }
                }
                for (String k699 : cnt699.keySet()) if (cnt699.get(k699) > 1) fn699.remove(k699);
                String cur699 = service != null ? service.getCurrentUrl() : "\0";
                for (int i = 0; i < libDisplayCount(); i++) {
                    Entry se699 = libEntryAtDisplay(i);
                    if (se699 == null) continue;
                    if (se699.file.getAbsolutePath().equals(cur699)) continue;
                    String u699 = fn699.get(se699.file.getName());
                    if (u699 != null && (pt699 == null || !pt699.contains(u699))) { hasMarkNewShown699 = true; break; }
                }
            } catch (Exception ignored) {}
            if (hasMarkNewShown699) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_mark_shown_new")); itemKeys.add("dl_menu_mark_shown_new"); }
        }
        // v6.45: un-mark only the shown files listened — the
        // shown-set un-mark verb the library's ⋮ menu still
        // lacked (the Talks chooser gained it as v6.31, the
        // Room drawer as v6.19; the library gained the mark
        // side as v6.44 but no way back). A listener who had
        // narrowed the library — a search in the box, or a
        // ★-only filter — and wanted just those files' ✓
        // cleared had to un-mark them talk by talk in the
        // Room/chooser, or clear ALL listened marks in
        // Settings (which also wipes other sayadaws' marks).
        // Offered from ANY row only while narrowed and never
        // in queue-only view (a play order, not a shelf to
        // un-mark), and only when at least one shown file
        // resolves through the dlMap to a source URL already
        // in playedTalks and is not the file still playing
        // (it keeps its ✓ while it plays, like the chooser's
        // v6.31 skip) — so it never appears on the full list
        // and never offers an empty set. The library has no
        // played-only view (modes are ★-only and
        // unlistened-only, and the latter never shows a ✓
        // row), so the verb is offered in search and ★-only
        // views, where the rows stay visible with their ✓
        // removed. The handler below removes the shown
        // files' source URLs from playedTalks and re-pours
        // the filter, so the marks move at once; neither the
        // queue, the shelf order nor any saved resume
        // position is mutated.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            boolean hasPlayed645 = false;
            try {
                android.content.SharedPreferences sp645 =
                        getSharedPreferences("dhamma", MODE_PRIVATE);
                java.util.Set<String> pt645 = sp645.getStringSet("playedTalks", null);
                org.json.JSONObject map645 =
                        new org.json.JSONObject(sp645.getString("dlMap", "{}"));
                // v8.47: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                // filename resolves to no URL, never the first claimant.
                java.util.HashMap<String, String> fn645 = new java.util.HashMap<String, String>();
                java.util.HashMap<String, Integer> cnt645 = new java.util.HashMap<String, Integer>();
                java.util.Iterator<String> keys645 = map645.keys();
                while (keys645.hasNext()) {
                    String u645 = keys645.next();
                    String f645 = map645.optString(u645, "");
                    if (f645.length() > 0) { fn645.put(f645, u645); Integer c645 = cnt645.get(f645); cnt645.put(f645, c645 == null ? 1 : c645 + 1); }
                }
                for (String k645 : cnt645.keySet()) if (cnt645.get(k645) > 1) fn645.remove(k645);
                String cur645 = service != null ? service.getCurrentUrl() : "\0";
                for (int i = 0; i < libDisplayCount(); i++) {
                    Entry se645 = libEntryAtDisplay(i);
                    if (se645 == null) continue;
                    if (se645.file.getAbsolutePath().equals(cur645)) continue;
                    String u645 = fn645.get(se645.file.getName());
                    if (u645 != null && pt645 != null && pt645.contains(u645)) { hasPlayed645 = true; break; }
                }
            } catch (Exception ignored) {}
            if (hasPlayed645) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_unmark_shown")); itemKeys.add("dl_menu_unmark_shown"); }
        }
        // v6.46: share only the shown files — the shown-set
        // share verb the library's ⋮ menu still lacked (the
        // Talks chooser gained it as v6.35, the Room drawer as
        // v6.24; the library had only the mini-player share of
        // the single sounding talk, v4.78). A listener who had
        // narrowed the library — a search in the box, or a
        // ★-only / unlistened-only filter — and wanted just
        // that hand-picked set sent to a friend had to share
        // file by file: no bulk share existed here at all.
        // Offered from ANY row only while narrowed and never
        // in queue-only view (a play order, not a picked set),
        // and only when at least 2 shown files resolve through
        // the dlMap to a source URL — sharing a single shown
        // file is exactly the per-talk share again, and a file
        // with no dlMap entry has no original link to send,
        // so it never counts toward the 2. The handler below
        // builds one message in display order (decoded file
        // titles + original dhammadownload.com links, library
        // header with the sent size (v11.40), dhammadownload.com
        // credit) and opens the system share sheet; a share
        // mutates nothing, so the shared save + rebind tail
        // below is a no-op rewrite.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            int linked646 = 0;
            try {
                android.content.SharedPreferences sp646 =
                        getSharedPreferences("dhamma", MODE_PRIVATE);
                org.json.JSONObject map646 =
                        new org.json.JSONObject(sp646.getString("dlMap", "{}"));
                // v8.48: exactly-one claimant only, so the gate and its handler agree.
                java.util.HashSet<String> names646 = new java.util.HashSet<String>();
                java.util.HashMap<String, Integer> cnt646 = new java.util.HashMap<String, Integer>();
                java.util.Iterator<String> keys646 = map646.keys();
                while (keys646.hasNext()) {
                    String f646 = map646.optString(keys646.next(), "");
                    if (f646.length() > 0) { names646.add(f646); Integer c646 = cnt646.get(f646); cnt646.put(f646, c646 == null ? 1 : c646 + 1); }
                }
                for (String k646 : cnt646.keySet()) if (cnt646.get(k646) > 1) names646.remove(k646);
                for (int i = 0; i < libDisplayCount(); i++) {
                    Entry se646 = libEntryAtDisplay(i);
                    if (se646 != null && names646.contains(se646.file.getName())) {
                        linked646++;
                        if (linked646 >= 2) break;
                    }
                }
            } catch (Exception ignored) {}
            if (linked646 >= 2) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_share_shown")); itemKeys.add("dl_menu_share_shown"); }
        }
        // v6.90: share only the shown subset (starred, or
        // not-yet-heard) — the library mirrors of the Talks
        // chooser's v6.89 pair, and the subset share twins of
        // the library's own v6.46 shown-set share. Audit first
        // confirmed the gap: the library could share a narrowed
        // set in one tap (v6.46) and could play just the shown
        // favourites (or just the shown not-yet-heard) files
        // (v6.74), but sharing just that subset meant sharing
        // the whole narrowed set or sharing file by file. Same
        // gates as v6.46, applied per subset: offered from ANY
        // row only while narrowed and never in queue-only view
        // (a play order, not a picked set), and only when at
        // least 2 shown subset files resolve through the dlMap
        // to a source URL — a file with no dlMap entry has no
        // original link to send, so it never counts toward the
        // 2, and the playing file stays in the set on purpose,
        // like v6.46 — a share list is not a play order.
        // Membership is the rows' own caches (★ in
        // libFavFiles; unlistened = absent from
        // libListenedFiles, the v6.74 rule), so the offer and
        // the handler below can never disagree.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            int linkedFav90 = 0, linkedNew90 = 0;
            try {
                android.content.SharedPreferences sp90 =
                        getSharedPreferences("dhamma", MODE_PRIVATE);
                org.json.JSONObject map90 =
                        new org.json.JSONObject(sp90.getString("dlMap", "{}"));
                // v8.48: exactly-one claimant only, so the gate and its handler agree.
                java.util.HashSet<String> names90 = new java.util.HashSet<String>();
                java.util.HashMap<String, Integer> cnt90 = new java.util.HashMap<String, Integer>();
                java.util.Iterator<String> keys90 = map90.keys();
                while (keys90.hasNext()) {
                    String f90 = map90.optString(keys90.next(), "");
                    if (f90.length() > 0) { names90.add(f90); Integer c90 = cnt90.get(f90); cnt90.put(f90, c90 == null ? 1 : c90 + 1); }
                }
                for (String k90 : cnt90.keySet()) if (cnt90.get(k90) > 1) names90.remove(k90);
                for (int i = 0; i < libDisplayCount(); i++) {
                    Entry se90 = libEntryAtDisplay(i);
                    if (se90 == null || !names90.contains(se90.file.getName())) continue;
                    if (libFavFiles.contains(se90.file.getName())) linkedFav90++;
                    if (!libListenedFiles.contains(se90.file.getName())) linkedNew90++;
                }
            } catch (Exception ignored) {}
            if (linkedFav90 >= 2) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_share_shown_star")); itemKeys.add("dl_menu_share_shown_star"); }
            if (linkedNew90 >= 2) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_share_shown_new")); itemKeys.add("dl_menu_share_shown_new"); }
        }
        // v6.92: copy only the shown subset's links (starred, or
        // not-yet-heard) — the library mirrors of the Talks
        // chooser's v6.91 pair, and the copy twins of the
        // library's own v6.90 subset shares. Audit first
        // confirmed the gap: the library could share just the
        // shown subset (v6.90) and could copy the whole shown
        // set (v6.49), but copying just that subset meant
        // copying the whole narrowed set and trimming it, or
        // copying file by file. Same gates as v6.90, applied
        // per subset: offered from ANY row only while narrowed
        // and never in queue-only view (a play order, not a
        // picked set), and only when at least 2 shown subset
        // files resolve through the dlMap to a source URL — a
        // file with no dlMap entry has no original link to
        // copy, so it never counts toward the 2, and the
        // playing file stays in the set on purpose, like v6.46
        // — a link list is not a play order. Membership is the
        // rows' own caches (★ in libFavFiles; unlistened =
        // absent from libListenedFiles, the v6.74 rule), so the
        // offer and the handler below can never disagree.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            int linkedFav92 = 0, linkedNew92 = 0;
            try {
                android.content.SharedPreferences sp92 =
                        getSharedPreferences("dhamma", MODE_PRIVATE);
                org.json.JSONObject map92 =
                        new org.json.JSONObject(sp92.getString("dlMap", "{}"));
                // v8.48: exactly-one claimant only, so the gate and its handler agree.
                java.util.HashSet<String> names92 = new java.util.HashSet<String>();
                java.util.HashMap<String, Integer> cnt92 = new java.util.HashMap<String, Integer>();
                java.util.Iterator<String> keys92 = map92.keys();
                while (keys92.hasNext()) {
                    String f92 = map92.optString(keys92.next(), "");
                    if (f92.length() > 0) { names92.add(f92); Integer c92 = cnt92.get(f92); cnt92.put(f92, c92 == null ? 1 : c92 + 1); }
                }
                for (String k92 : cnt92.keySet()) if (cnt92.get(k92) > 1) names92.remove(k92);
                for (int i = 0; i < libDisplayCount(); i++) {
                    Entry se92 = libEntryAtDisplay(i);
                    if (se92 == null || !names92.contains(se92.file.getName())) continue;
                    if (libFavFiles.contains(se92.file.getName())) linkedFav92++;
                    if (!libListenedFiles.contains(se92.file.getName())) linkedNew92++;
                }
            } catch (Exception ignored) {}
            if (linkedFav92 >= 2) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_copy_shown_star")); itemKeys.add("dl_menu_copy_shown_star"); }
            if (linkedNew92 >= 2) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_copy_shown_new")); itemKeys.add("dl_menu_copy_shown_new"); }
        }
        // v6.47: play only the shown files from the start —
        // the shown-set play verb the library's ⋮ menu still
        // lacked (the Talks chooser gained it as v6.32; the
        // library had queue/star/mark/share for a narrowed set
        // but no way to HEAR it straight through — the nearest
        // path was the first shown row's "ဒီကနေ အားလုံး
        // တန်းစီ" plus a tap, which queues from that row down,
        // not the shown set from any row). Offered from ANY row
        // only while narrowed and never in queue-only view (a
        // play order, not a picked set), and only when at least
        // one shown file is not the file still playing — so it
        // never appears on the full list and never offers a set
        // whose only file is already sounding. The handler
        // below plays the first shown file with row-tap
        // semantics and head-queues the rest in display order.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            String cur647 = service != null ? service.getCurrentUrl() : "\0";
            boolean hasNonPlaying647 = false;
            for (int i = 0; i < libDisplayCount(); i++) {
                Entry se647 = libEntryAtDisplay(i);
                if (se647 != null && !se647.file.getAbsolutePath().equals(cur647)) { hasNonPlaying647 = true; break; }
            }
            if (hasNonPlaying647) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_play_shown")); itemKeys.add("dl_menu_play_shown"); }
        }
        // v6.74: play only the shown favourites / shown not-yet-
        // listened files from the first of the subset — the
        // library mirrors of the Talks chooser's v6.72 pair
        // and the Room drawer's v6.73 pair, and the play
        // twins of the library's own v6.40 queue pair. Audit
        // first confirmed the gap: the library could queue
        // just the shown ★ (or just the shown not-yet-heard)
        // files (v6.40), queue them NEXT (v6.71) and play the
        // whole shown set (v6.47), but playing just the subset
        // meant playing the whole shown set and skipping, or
        // queueing the subset and then tapping its first row.
        // Offered from ANY row only while narrowed and never
        // in queue-only view (a play order, not a picked
        // set), and only when the subset holds a file other
        // than the file still playing — v6.47's gate applied
        // per subset, so a lone non-playing subset file is
        // still offered (forward play of one file is a real
        // play, not a repeat of another verb), while a subset
        // whose only member is already sounding has nothing
        // to play. Marks are the rows' own caches (★ in
        // libFavFiles; unlistened = absent from
        // libListenedFiles), so the offer and the handler
        // below can never disagree. The handler is v6.47's
        // handler run on the filtered subset, in display
        // order.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            String cur674 = service != null ? service.getCurrentUrl() : "\0";
            boolean hasPlayFav674 = false, hasPlayNew674 = false;
            for (int i = 0; i < libDisplayCount(); i++) {
                Entry se674 = libEntryAtDisplay(i);
                if (se674 == null) continue;
                if (se674.file.getAbsolutePath().equals(cur674)) continue;
                if (libFavFiles.contains(se674.file.getName())) hasPlayFav674 = true;
                if (!libListenedFiles.contains(se674.file.getName())) hasPlayNew674 = true;
                if (hasPlayFav674 && hasPlayNew674) break;
            }
            if (hasPlayFav674) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_play_shown_star")); itemKeys.add("dl_menu_play_shown_star"); }
            if (hasPlayNew674) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_play_shown_new")); itemKeys.add("dl_menu_play_shown_new"); }
        }
        // v6.77: play the hand queue from its head — the
        // library mirror of the Room drawer's v6.75 and the
        // Talks chooser's v6.76, the queue verb the library's
        // ⋮ menu still lacked. A listener who lined up
        // downloaded files for a trip could queue, queue-next,
        // shuffle/reverse and clear the queue, and could play
        // shown sets and shown subsets (v6.47/v6.74), but
        // starting the hand-built queue itself took opening
        // "တန်းစီထားတာပဲ ပြပါ" and tapping its first row.
        // Offered from ANY row only in the normal view (never
        // in queue-only view, where tapping the first row
        // already starts the queue) and only when the queue
        // holds a file still on the shelf — a stale path (a
        // file deleted outside the app) is skipped, never
        // guessed — and only when that head is not the file
        // still sounding (the live-session check, not just
        // the last URL, so a stopped earlier file sitting at
        // the head still offers, like v6.76's gate).
        {
            if (!libQueueOnly && !queue.isEmpty()) {
                String cur677 = service != null ? service.getCurrentUrl() : "\0";
                String head677 = null;
                int count677 = 0;
                for (String p677 : queue) {
                    boolean onShelf677 = false;
                    for (Entry e677 : entries)
                        if (e677.file.getAbsolutePath().equals(p677)) { onShelf677 = true; break; }
                    if (!onShelf677) continue;
                    count677++;
                    if (head677 == null) head677 = p677;
                }
                boolean headPlaying677 = head677 != null && head677.equals(cur677)
                        && service != null && service.hasTrack();
                if (head677 != null && !headPlaying677)
                    items.add(Lang.t(DownloadedActivity.this, "dl_menu_play_queued"));
                    itemKeys.add("dl_menu_play_queued");
            }
        }
        // v6.88: shuffle-play / reverse-play the hand queue —
        // the library mirrors of the Talks chooser's v6.87 pair,
        // the play twins of the library's own v6.77 forward play
        // and of the reorder verbs "တန်းစီ ကျပန်း ပြန်စီ" /
        // "တန်းစီ ပြောင်းပြန် စီ". Same gates as v6.87, in
        // library terms: never in queue-only view (there the
        // queue is already on screen and its first row is the
        // forward play), only for a queue holding 2+ files still
        // on the shelf (a stale path is skipped, never counted),
        // and only when one of them is not the file actually
        // sounding (the live-session check, so a stopped earlier
        // file still offers, like v6.77's gate).
        {
            if (!libQueueOnly) {
                String cur88 = service != null ? service.getCurrentUrl() : "\0";
                boolean sounding88 = service != null && service.hasTrack();
                int shelf88 = 0;
                boolean hasNonPlaying88 = false;
                for (String p88 : queue) {
                    boolean onShelf88 = false;
                    for (Entry e88 : entries)
                        if (e88.file.getAbsolutePath().equals(p88)) { onShelf88 = true; break; }
                    if (!onShelf88) continue;
                    shelf88++;
                    if (!(sounding88 && p88.equals(cur88))) hasNonPlaying88 = true;
                }
                if (shelf88 >= 2 && hasNonPlaying88) {
                    items.add(Lang.t(DownloadedActivity.this, "dl_menu_shuffle_queued"));
                    itemKeys.add("dl_menu_shuffle_queued");
                    items.add(Lang.t(DownloadedActivity.this, "dl_menu_reverse_queued"));
                    itemKeys.add("dl_menu_reverse_queued");
                }
            }
        }
        // v6.48: shuffle-play and reverse-play only the shown
        // files — the library mirror of the Room drawer's
        // v6.22/v6.23 and the Talks chooser's v6.33/v6.34, the
        // two order variants v6.47's forward play still
        // lacked. A narrowed library (a search, or any filter
        // mode) is a hand-picked set, and a listener who wants
        // that set shuffled or backwards had to tap the first
        // row and queue the rest by hand, or reorder the hand
        // queue itself after queueing it. Same narrowed-only,
        // never-queue-only gate as v6.47, plus the chooser's
        // 2+ rule (shuffling or reversing a single file is
        // just v6.47 again) and v6.47's non-playing rule, so
        // the offers never appear on the full list and never
        // offer a set whose only file is already sounding.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)
                && libDisplayCount() >= 2) {
            String cur648 = service != null ? service.getCurrentUrl() : "\0";
            boolean hasNonPlaying648 = false;
            for (int i = 0; i < libDisplayCount(); i++) {
                Entry se648 = libEntryAtDisplay(i);
                if (se648 != null && !se648.file.getAbsolutePath().equals(cur648)) { hasNonPlaying648 = true; break; }
            }
            if (hasNonPlaying648) {
                items.add(Lang.t(DownloadedActivity.this, "dl_menu_shuffle_shown"));
                itemKeys.add("dl_menu_shuffle_shown");
                items.add(Lang.t(DownloadedActivity.this, "dl_menu_reverse_shown"));
                itemKeys.add("dl_menu_reverse_shown");
            }
        }
        // v6.79: shuffle-play only the shown favourites /
        // shown not-yet-listened files — the library mirror
        // of the Talks chooser's v6.78 pair, and the shuffle
        // twins of the library's own v6.74 subset forward-play
        // pair. Audit first confirmed the gap: the library
        // could shuffle the whole narrowed set (v6.48) and
        // forward-play just the shown subset (v6.74), but
        // shuffling just that subset meant shuffling the whole
        // shown set and skipping, or queueing the subset and
        // shuffling the queue by hand. Same narrowed-only,
        // never-queue-only gate as v6.48, plus the chooser's
        // 2+ rule per subset (shuffling a single file is just
        // v6.74 again) and v6.48's non-playing rule per subset,
        // so the offers never appear on the full list and never
        // offer a subset whose only file is already sounding.
        // The playing file stays in the pool on purpose, like
        // v6.48/v6.78 — a shuffle landing on it does not reopen
        // the Room and the rest simply head-queue behind it.
        // Marks are the rows' own caches (favourite in
        // libFavFiles; unlistened = absent from
        // libListenedFiles), so the offer and the handler below
        // can never disagree.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            String cur679g = service != null ? service.getCurrentUrl() : "\0";
            int fav679g = 0, new679g = 0;
            boolean favNonPlaying679g = false, newNonPlaying679g = false;
            for (int i = 0; i < libDisplayCount(); i++) {
                Entry se679g = libEntryAtDisplay(i);
                if (se679g == null) continue;
                boolean playing679g = se679g.file.getAbsolutePath().equals(cur679g);
                if (libFavFiles.contains(se679g.file.getName())) {
                    fav679g++;
                    if (!playing679g) favNonPlaying679g = true;
                }
                if (!libListenedFiles.contains(se679g.file.getName())) {
                    new679g++;
                    if (!playing679g) newNonPlaying679g = true;
                }
            }
            if (fav679g >= 2 && favNonPlaying679g) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_shuffle_shown_star")); itemKeys.add("dl_menu_shuffle_shown_star"); }
            if (new679g >= 2 && newNonPlaying679g) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_shuffle_shown_new")); itemKeys.add("dl_menu_shuffle_shown_new"); }
        }
        // v6.82: reverse-play only the shown favourites /
        // shown not-yet-listened files — the library mirror
        // of the Talks chooser's v6.81 pair, and the reverse
        // twins of the library's own v6.74 subset forward-play
        // pair and v6.79 subset shuffle pair. Audit first
        // confirmed the gap: the library could reverse the
        // whole narrowed set (v6.48) and forward-play or
        // shuffle just the shown subset (v6.74/v6.79), but
        // reversing just that subset meant reversing the
        // whole shown set and skipping, or queueing the
        // subset and reversing the queue by hand. Same
        // narrowed-only, never-queue-only gate as v6.79,
        // plus the 2+ rule per subset (reversing a single
        // file is just v6.74 again) and v6.48's non-playing
        // rule per subset, so the offers never appear on
        // the full list and never offer a subset whose only
        // file is already sounding. The playing file stays
        // in the pool on purpose, like v6.48/v6.79 — a
        // reverse landing on it does not reopen the Room
        // and the rest simply head-queue behind it. Marks
        // are the rows' own caches (favourite in
        // libFavFiles; unlistened = absent from
        // libListenedFiles), so the offer and the handler
        // below can never disagree.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            String cur682g = soundingLibPath(); // v11.24: unbound-window mirror
            int fav682g = 0, new682g = 0;
            boolean favNonPlaying682g = false, newNonPlaying682g = false;
            for (int i = 0; i < libDisplayCount(); i++) {
                Entry se682g = libEntryAtDisplay(i);
                if (se682g == null) continue;
                boolean playing682g = se682g.file.getAbsolutePath().equals(cur682g);
                if (libFavFiles.contains(se682g.file.getName())) {
                    fav682g++;
                    if (!playing682g) favNonPlaying682g = true;
                }
                if (!libListenedFiles.contains(se682g.file.getName())) {
                    new682g++;
                    if (!playing682g) newNonPlaying682g = true;
                }
            }
            if (fav682g >= 2 && favNonPlaying682g) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_reverse_shown_star")); itemKeys.add("dl_menu_reverse_shown_star"); }
            if (new682g >= 2 && newNonPlaying682g) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_reverse_shown_new")); itemKeys.add("dl_menu_reverse_shown_new"); }
        }
        // v6.49: copy only the shown files' links — the library
        // mirror of the Talks chooser's v6.36 and the Room
        // drawer's v6.25, and the second share-family verb in
        // the library's shown-set family (share v6.46). The
        // library could share a narrowed set in one tap, but a
        // listener who wanted just those links pasted elsewhere
        // (a note, another app without the share sheet) had to
        // copy them one by one — the chooser and the drawer
        // already had the verb, the library did not. Offered
        // from ANY row only while narrowed (a search, or any
        // filter mode) and never in queue-only view (a play
        // order, not a picked set), and only when at least 2
        // shown files resolve through the dlMap to a source
        // URL — copying a single shown file's link is exactly
        // the per-talk copy again, and a file with no dlMap
        // entry has no original link to copy, so it never
        // counts toward the 2 (the v6.46 share gate, verbatim).
        // The playing file stays in the set on purpose, like
        // v6.46 — a link list is not a play order, so
        // excluding it would silently drop a picked file.
        if (!libQueueOnly && ((dlQuery != null && !dlQuery.isEmpty()) || dlFilterMode != 0)) {
            int linked649 = 0;
            try {
                android.content.SharedPreferences sp649 =
                        getSharedPreferences("dhamma", MODE_PRIVATE);
                org.json.JSONObject map649 =
                        new org.json.JSONObject(sp649.getString("dlMap", "{}"));
                // v8.48: exactly-one claimant only, so the gate and its handler agree.
                java.util.HashSet<String> names649 = new java.util.HashSet<String>();
                java.util.HashMap<String, Integer> cnt649 = new java.util.HashMap<String, Integer>();
                java.util.Iterator<String> keys649 = map649.keys();
                while (keys649.hasNext()) {
                    String f649 = map649.optString(keys649.next(), "");
                    if (f649.length() > 0) { names649.add(f649); Integer c649 = cnt649.get(f649); cnt649.put(f649, c649 == null ? 1 : c649 + 1); }
                }
                for (String k649 : cnt649.keySet()) if (cnt649.get(k649) > 1) names649.remove(k649);
                for (int i = 0; i < libDisplayCount(); i++) {
                    Entry se649 = libEntryAtDisplay(i);
                    if (se649 != null && names649.contains(se649.file.getName())) {
                        linked649++;
                        if (linked649 >= 2) break;
                    }
                }
            } catch (Exception ignored) {}
            if (linked649 >= 2) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_copy_shown")); itemKeys.add("dl_menu_copy_shown"); }
        }
        // v4.59: bulk queue verbs, mirroring the chooser's v4.49 /
        // v4.50 items — offered from any row whenever the shelf
        // holds such a file (favourite = in libFavFiles, unlistened
        // = not in libListenedFiles, both per-load caches from the
        // dlMap reverse lookup, so the offer and the handler agree).
        boolean hasFavFile = false;
        for (int i = 0; i < entries.size(); i++)
            if (libFavFiles.contains(entries.get(i).file.getName())) { hasFavFile = true; break; }
        if (hasFavFile) { items.add(Lang.t(DownloadedActivity.this, "dl_queue_all_star")); itemKeys.add("dl_queue_all_star"); }
        boolean hasUnplayedFile = false;
        for (int i = 0; i < entries.size(); i++)
            if (!libListenedFiles.contains(entries.get(i).file.getName())) { hasUnplayedFile = true; break; }
        if (hasUnplayedFile) { items.add(Lang.t(DownloadedActivity.this, "dl_queue_all_unplayed")); itemKeys.add("dl_queue_all_unplayed"); }
        boolean hasQueueable = false;
        for (int i = 0; i < entries.size(); i++)
            if (!entries.get(i).file.getAbsolutePath().equals(soundingLibPath())) { hasQueueable = true; break; } // v11.24: unbound-window mirror
        if (hasQueueable) { items.add(Lang.t(DownloadedActivity.this, "dl_queue_all_list")); itemKeys.add("dl_queue_all_list"); }
        if (queued) { items.add(Lang.t(DownloadedActivity.this, "menu_queue_remove")); itemKeys.add("menu_queue_remove"); }
        if (queued) {
            int qpos = queue.indexOf(path);
            if (qpos > 0) { items.add(Lang.t(DownloadedActivity.this, "menu_move_up")); itemKeys.add("menu_move_up"); }
            if (qpos >= 0 && qpos < queue.size() - 1) { items.add(Lang.t(DownloadedActivity.this, "menu_move_down")); itemKeys.add("menu_move_down"); }
            if (qpos > 0) { items.add(Lang.t(DownloadedActivity.this, "menu_move_top")); itemKeys.add("menu_move_top"); }
            if (qpos >= 0 && qpos < queue.size() - 1) { items.add(Lang.t(DownloadedActivity.this, "menu_move_end")); itemKeys.add("menu_move_end"); }
            // v5.78: pick-up/drop move, the last Room-drawer
            // reorder verb the library lacked (v4.92 there,
            // v5.77 in the chooser) — the one-step and jump
            // verbs above move a file one slot or to an end,
            // so placing a file in the MIDDLE of a long queue
            // still took repeated taps. Arming from any queued
            // row's ⋮ menu lets the next tap on another queued
            // row drop it there (before that row); the held row
            // reads ✋ + gold wash until the drop, like the
            // chooser and drawer.
            items.add(Lang.t(DownloadedActivity.this, "menu_move"));
            itemKeys.add("menu_move");
        }
        if (queued && queue.size() > 1) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_reorder_shuffle")); itemKeys.add("dl_menu_reorder_shuffle"); }
        if (queued && queue.size() > 1) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_reorder_reverse")); itemKeys.add("dl_menu_reorder_reverse"); }
        if (queue.size() > 1) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_clear_queue")); itemKeys.add("dl_menu_clear_queue"); }
        // v4.58: queue-only view toggle, mirroring the Talks
        // chooser's v4.56 menu — offered whenever a queue exists
        // (enter) or while the view is on (exit).
        if (!libQueueOnly && !queue.isEmpty()) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_show_queue_only")); itemKeys.add("dl_menu_show_queue_only"); }
        if (libQueueOnly) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_show_all_again")); itemKeys.add("dl_menu_show_all_again"); }
        // v6.53: share this one file — ahead of the single-file favourite (v7.90) and copy, like the
        // chooser's v6.52 share and the drawer's trailing share,
        // so the shown-set share above stays the set verb and
        // this one stays the single-file verb. Link-gated only
        // (see the shareUrl653 block above): a file with no
        // dlMap entry simply shows no share entry, never a
        // title-only share and never a guessed link.
        if (shareUrl653 != null) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_share")); itemKeys.add("dl_menu_share"); }
        // v7.90: favourite this one file — the library mirror of the
        // Talks chooser's v7.88 and the Room drawer's v7.89 single-talk
        // verbs, and the last mirror surface those runs flagged: the
        // library's row menu had shown-set star/un-star verbs (v6.42/
        // v6.43, narrowed views only) and the strip star for the
        // sounding file, but no per-row toggle — a listener in the
        // full library who wanted just this file starred had to play
        // it first or star it in the Room/chooser. Offered right
        // after share, like the chooser/drawer dialogs, and gated on
        // the same shareUrl653 final as share/copy (see the v6.53
        // block): a file with no dlMap entry has no source URL, so
        // nothing to star — it simply shows no entry, never a guessed
        // link. The label reads libFavFiles, the same cache the row's
        // own ★ mark renders from, so it can never disagree with the
        // visible mark; the playing file is included, like share and
        // copy beside it: starring is not a play order.
        if (shareUrl653 != null) { items.add(libFavFiles.contains(t.file.getName())
                ? Lang.t(DownloadedActivity.this, "menu_fav_remove")
                : Lang.t(DownloadedActivity.this, "menu_fav_add")); itemKeys.add(libFavFiles.contains(t.file.getName())
                ? "menu_fav_remove"
                : "menu_fav_add"); }
        // v6.56: copy this one file's link — the library mirror
        // of the chooser's v6.54 and the drawer's v6.55 single-talk
        // copies, and the last mirror surface those runs flagged:
        // the library could share one file (v6.53) and copy a
        // narrowed set's links (v6.49), but a listener who wanted
        // just this file's link pasted elsewhere (a note, another
        // app without the share sheet) had to share it through
        // the sheet and copy from there, or narrow the library
        // to it — which the shown-set copy never offers for a
        // lone file at all. Last in the menu after 'မျှဝေမယ်',
        // so the shown-set copy above stays the set verb and
        // this one stays the single-file verb; link-gated on
        // the same shareUrl653 final as the share entry (see
        // the shareUrl653 block above), so the offer and the
        // handler below can never disagree — a file with no
        // dlMap entry simply shows no copy entry, never a bare
        // title, never a guessed link — and the playing file
        // is included, like the share beside it: a link is not
        // a play order.
        if (shareUrl653 != null) { items.add(Lang.t(DownloadedActivity.this, "dl_menu_copy_link")); itemKeys.add("dl_menu_copy_link"); }
        if (items.isEmpty()) {
            Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "file_playing"), Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] opts = items.toArray(new String[0]);
        final String[] optKeys = itemKeys.toArray(new String[0]);
        final java.util.ArrayList<String> q = queue;
        new android.app.AlertDialog.Builder(DownloadedActivity.this)
                .setTitle(t.title)
                .setItems(opts, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        String choice = opts[which];
                        final String choiceKey = optKeys[which];
                        String cur = soundingLibPath(); // v11.24: unbound-window mirror, so the v11.12/v11.14 re-checks and every bulk skip see the sounding file even with no bind
                        // v12.00: drop entries the library advance already popped --
                        // the v11.98 Talks-funnel rule, applied to this dialog's
                        // frozen snapshot. The menu copied libQueueCache at open
                        // (:6805), but the 1s tick pops the head on a natural end
                        // (takeLibQueueHead at :3262) while the dialog sits open,
                        // and headset/notification Next does the same through
                        // handleLibNext -- so q can still hold a head the service
                        // already popped and persisted without. Every branch below
                        // falls through to the shared saveLibQueue(q) tail (:10407),
                        // which writes verbatim, so a bulk verb tapped after the
                        // pop resurrected the sounding talk and it replayed after
                        // the next talk. Prune here, at tap time, before any branch
                        // adds anything: an entry absent from the fresh saved read
                        // is gone -- popped, cleared or pruned elsewhere. Runs even
                        // when the fresh read is empty (a cleared/consumed queue
                        // must not resurrect wholesale); the single-path and bulk
                        // adds below all happen after this prune, so they survive
                        // to the tail untouched. Mutates only the in-memory
                        // snapshot, so an early-return branch still persists
                        // nothing. Library entries are bare file paths (no v2
                        // lines), so the fresh set is a plain newline split.
                        try {
                            String savedLib00 = Prefs.savedQueueLib(DownloadedActivity.this);
                            java.util.Set<String> savedLibPaths00 = new java.util.HashSet<String>();
                            if (savedLib00 != null && savedLib00.length() > 0) {
                                for (String p00 : savedLib00.split("\n")) {
                                    if (p00.length() > 0) savedLibPaths00.add(p00);
                                }
                            }
                            for (int i00 = q.size() - 1; i00 >= 0; i00--) {
                                if (!savedLibPaths00.contains(q.get(i00))) q.remove(i00);
                            }
                        } catch (Exception ignored) {}
                        // v12.08: pre-verb snapshot for the funnel keep --
                        // the shared saveLibQueue tail below now drops any
                        // entry absent from the fresh saved read, so the
                        // paths THIS verb adds (single play-next / add,
                        // bulk queue-shown / from-here / next-shown and
                        // their star/new twins) must be passed as the
                        // keep set, or the funnel would drop them as
                        // stale at once (sim1208 L14). Captured AFTER
                        // the v12.00 prune above, so the baseline is
                        // exactly the saved-read entries; the diff at
                        // the tail is the verb's own adds, branch-agnostic
                        // (removes / reorders / no-touch verbs add
                        // nothing, so their keep is empty). Early-return
                        // branches never reach the tail, so nothing is
                        // persisted for them.
                        final java.util.ArrayList<String> preQ08 = new java.util.ArrayList<String>(q);
                        // v8.70: single-file queue toasts name the sayadaw too -- the same
                        // anonymous-title gap the v8.67/v8.69 poll toasts closed: on a
                        // multi-sayadaw shelf a title alone does not say whose talk was
                        // just queued next / appended. Same per-load libSayadawMap, same
                        // title · sayadaw order; unrecorded or ambiguous files keep
                        // today's toast byte-identical, never a guessed name. The bulk
                        // queue toasts below stay count-only (no single title to name).
                        String qSd70 = libSayadawMap.get(t.file.getName());
                        String qSdSuffix70 = (qSd70 != null && qSd70.length() > 0)
                                ? " · " + qSd70 : "";
                        if ("menu_play_next_short".equals(choiceKey)) {
                            // v11.12: tap-time sounding re-check — the row menu's
                            // gate (playing, at build time) excluded the sounding
                            // file, but the dialog can stay open across a library
                            // auto-advance, so this file can BE sounding by tap time.
                            // cur is already read fresh at tap time above for the
                            // other verbs; this verb ignored it. Leave the queue
                            // copy untouched on a hit (the shared save tail below
                            // is skipped by the return, so nothing is persisted).
                            if (path.equals(cur)) {
                                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                return;
                            }
                            q.remove(path);
                            q.add(0, path);
                            Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "queue_next_prefix") + t.title + qSdSuffix70, Toast.LENGTH_SHORT).show();
                        } else if ("menu_queue_add".equals(choiceKey)) {
                            // v11.14: tap-time sounding re-check — the mirror of the v11.12
                            // play-next leg above, which this append verb never got: the
                            // offer gate ran at menu-build time (!playing), but the dialog
                            // can sit open across a library auto-advance, and the advance
                            // falls through to list-next when the queue has no live head,
                            // so this very file can BE sounding by tap time. Appending it
                            // then leaves the sounding file in the queue, and after the
                            // next file ends the advance pops it and plays it a second
                            // time. The bulk verbs and play-next already re-read cur at
                            // tap time; this verb now does the same, and a hit toasts
                            // "queue changed" and returns before the shared save tail,
                            // so the queue copy is never persisted.
                            if (path.equals(cur)) {
                                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (!q.contains(path)) q.add(path);
                            Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "added_to_queue") + ": " + t.title + qSdSuffix70, Toast.LENGTH_SHORT).show();
                        } else if ("menu_queue_from_here".equals(choiceKey)) {
                            int added = 0;
                            // v4.58: iterate display positions through
                            // the mapping, so in queue-only view the
                            // verb reads queue order (already-queued
                            // rows skip, like the full view) instead
                            // of the shelf order underneath it.
                            for (int i = shownPos; i < libDisplayCount(); i++) {
                                Entry de = libEntryAtDisplay(i);
                                if (de == null) continue;
                                String u = de.file.getAbsolutePath();
                                if (u.equals(cur)) continue;
                                if (!q.contains(u)) { q.add(u); added++; }
                            }
                            Toast.makeText(DownloadedActivity.this, added > 0
                                    ? Lang.t(DownloadedActivity.this, "queue_plain_prefix") + added + Lang.t(DownloadedActivity.this, "queue_added_suffix")
                                    : Lang.t(DownloadedActivity.this, "already_in_queue"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_queue_shown".equals(choiceKey)) {
                            // v6.38: queue the shown display in
                            // order — same skip rules as the
                            // from-here verb above (playing file,
                            // already queued) and the same count
                            // toast; the shared saveLibQueue +
                            // updateTotal tail below moves the ◈
                            // marks and the footer at once.
                            int added = 0;
                            for (int i = 0; i < libDisplayCount(); i++) {
                                Entry de = libEntryAtDisplay(i);
                                if (de == null) continue;
                                String u = de.file.getAbsolutePath();
                                if (u.equals(cur)) continue;
                                if (!q.contains(u)) { q.add(u); added++; }
                            }
                            Toast.makeText(DownloadedActivity.this, added > 0
                                    ? Lang.t(DownloadedActivity.this, "queue_shown_prefix") + added + Lang.t(DownloadedActivity.this, "queue_added_suffix")
                                    : Lang.t(DownloadedActivity.this, "already_in_queue"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_queue_next_shown".equals(choiceKey)) {
                            // v6.68: queue only the shown files NEXT
                            // — the head-insert twin of the v6.38
                            // append above, mirroring the chooser's
                            // v6.66 and the drawer's v6.67. New shown
                            // paths, in display order, go to the
                            // head of the queue (index 0 plays
                            // next, the same head the single-file
                            // "နောက်မှာ ဖွင့်" uses), so the set
                            // plays straight after the current
                            // file and before any older queue,
                            // without stopping playback. The
                            // playing file never re-queues and
                            // already-queued paths stay where
                            // they are (like every other bulk
                            // queue verb), so the already-queued
                            // toast covers the nothing-new case;
                            // the shared saveLibQueue + updateTotal
                            // tail below moves the ◈ marks and
                            // the footer at once.
                            java.util.ArrayList<String> next68 = new java.util.ArrayList<String>();
                            for (int i = 0; i < libDisplayCount(); i++) {
                                Entry de68 = libEntryAtDisplay(i);
                                if (de68 == null) continue;
                                String u68 = de68.file.getAbsolutePath();
                                if (u68.equals(cur)) continue;
                                if (q.contains(u68)) continue;
                                next68.add(u68);
                            }
                            if (!next68.isEmpty()) q.addAll(0, next68);
                            Toast.makeText(DownloadedActivity.this, !next68.isEmpty()
                                    ? Lang.t(DownloadedActivity.this, "queue_next_shown_prefix") + next68.size() + Lang.t(DownloadedActivity.this, "queue_next_suffix")
                                    : Lang.t(DownloadedActivity.this, "already_in_queue"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_del_shown".equals(choiceKey)) {
                            // v6.39: delete only the shown files,
                            // in display order — the offer above
                            // already excluded the playing file,
                            // but the count here is recomputed
                            // for the confirm message and the
                            // candidate list is recomputed again
                            // at confirm time, so a talk that
                            // started playing while the dialog
                            // was open is never deleted (its
                            // file is in use) and a file gone
                            // since the dialog opened is never
                            // counted as deleted. Only files
                            // actually deleted earn a dlMap
                            // prune (the v1.98 rule — a failed
                            // delete keeps its map entry), a
                            // queued tap on a deleted file is
                            // cleared (the v1.63 rule), and a
                            // held pick-up/drop arm on a deleted
                            // file is dropped (its next use
                            // would clear it anyway). The
                            // loadList tail prunes the hand
                            // queue against the fresh shelf,
                            // refreshes the ★/✓ marks and the
                            // footer, and reapplies the same
                            // search/filter — a narrowed view
                            // that empties explains itself with
                            // the existing empty view.
                            int offerCount = 0;
                            for (int i = 0; i < libDisplayCount(); i++) {
                                Entry de = libEntryAtDisplay(i);
                                if (de == null) continue;
                                if (de.file.getAbsolutePath().equals(cur)) continue;
                                offerCount++;
                            }
                            if (offerCount == 0) {
                                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "nothing_to_delete"), Toast.LENGTH_SHORT).show();
                            } else {
                                new android.app.AlertDialog.Builder(DownloadedActivity.this)
                                        .setTitle(Lang.t(DownloadedActivity.this, "del_shown_title"))
                                        .setMessage(Lang.t(DownloadedActivity.this, "del_shown_msg_head") + offerCount + Lang.t(DownloadedActivity.this, "del_shown_msg_tail"))
                                        .setPositiveButton(Lang.t(DownloadedActivity.this, "del_shown_btn"), new android.content.DialogInterface.OnClickListener() {
                                            @Override public void onClick(android.content.DialogInterface d2, int which2) {
                                                // v11.25: confirm-time sounding re-check is the unbound-window mirror, not the bound-only read — v11.24 fixed the menu-build gates and the tap-time count but left this pair bound-only, so with the service unbound (bind pending/dropped while the session sounds) the confirm loop deleted the sounding file even though the confirm count had excluded it.
                                                String cur2 = soundingLibPath();
                                                java.util.Set<String> deletedNames = new java.util.HashSet<String>();
                                                for (int i = 0; i < libDisplayCount(); i++) {
                                                    Entry de = libEntryAtDisplay(i);
                                                    if (de == null) continue;
                                                    if (de.file.getAbsolutePath().equals(cur2)) continue;
                                                    if (de.file.delete()) {
                                                        deletedNames.add(de.file.getName());
                                                        if (pendingDlPath != null && pendingDlPath.equals(de.file.getAbsolutePath())) {
                                                            pendingDlPath = null;
                                                            pendingDlTitle = null;
                                                            pendingDlRestart = false;
                                                        }
                                                        if (moveHeldPath != null && moveHeldPath.equals(de.file.getAbsolutePath()))
                                                            moveHeldPath = null;
                                                    }
                                                }
                                                if (deletedNames.isEmpty()) {
                                                    Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "delete_failed"), Toast.LENGTH_SHORT).show();
                                                } else {
                                                    pruneDlMapForFilenames(deletedNames);
                                                    loadList();
                                                    Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "shown_deleted_prefix") + deletedNames.size() + Lang.t(DownloadedActivity.this, "deleted_suffix"), Toast.LENGTH_SHORT).show();
                                                }
                                            }
                                        })
                                        .setNegativeButton(Lang.t(DownloadedActivity.this, "del_btn_no"), null)
                                        .show();
                            }
                        } else if ("dl_del_shown_star".equals(choiceKey)
                                || "dl_del_shown_new".equals(choiceKey)) {
                            // v6.94: delete only the shown subset's files, in display order —
                            // the library mirrors of the chooser's v6.93 pair, same mechanics
                            // as the library's own v6.39 (confirm first, a delete is
                            // destructive; the count is recomputed for the confirm message
                            // and the candidate list is recomputed again at confirm time, so
                            // a file that started playing while the dialog was open is never
                            // deleted and a file gone since the dialog opened is never
                            // counted; only files actually deleted earn a dlMap prune, the
                            // v1.98 rule; a queued tap on a deleted file is cleared, the
                            // v1.63 rule, and a held pick-up/drop arm on a deleted file is
                            // dropped; the loadList tail prunes the hand queue against the
                            // fresh shelf, refreshes the ★/✓ marks and the footer, and
                            // reapplies the same search/filter). The only difference from
                            // v6.39 is the subset filter, read from the rows' own caches
                            // (★ in libFavFiles; unlistened = absent from libListenedFiles),
                            // so offer and handler can never disagree about a file starred
                            // or marked since the menu opened; the playing file stays
                            // excluded on purpose, like v6.39.
                            final boolean fav94 = "dl_del_shown_star".equals(choiceKey);
                            final String tag94 = fav94 ? Lang.t(DownloadedActivity.this, "dl_tag_shown_star") : Lang.t(DownloadedActivity.this, "dl_tag_shown_new");
                            int offerCount94 = 0;
                            for (int i = 0; i < libDisplayCount(); i++) {
                                Entry de94 = libEntryAtDisplay(i);
                                if (de94 == null) continue;
                                if (fav94) { if (!libFavFiles.contains(de94.file.getName())) continue; }
                                else { if (libListenedFiles.contains(de94.file.getName())) continue; }
                                if (de94.file.getAbsolutePath().equals(cur)) continue;
                                offerCount94++;
                            }
                            if (offerCount94 == 0) {
                                Toast.makeText(DownloadedActivity.this, tag94 + Lang.t(DownloadedActivity.this, "dl_nothing_to_delete_suffix"), Toast.LENGTH_SHORT).show();
                            } else {
                                new android.app.AlertDialog.Builder(DownloadedActivity.this)
                                        .setTitle(tag94 + Lang.t(DownloadedActivity.this, "dl_subset_del_title_suffix"))
                                        .setMessage(tag94 + Lang.t(DownloadedActivity.this, "dl_subset_del_msg_mid") + offerCount94 + Lang.t(DownloadedActivity.this, "dl_subset_del_msg_suffix"))
                                        .setPositiveButton(tag94 + Lang.t(DownloadedActivity.this, "dl_subset_del_btn_suffix"), new android.content.DialogInterface.OnClickListener() {
                                            @Override public void onClick(android.content.DialogInterface d2, int which2) {
                                                // v11.25: same unbound-window mirror as the v6.39 confirm above.
                                                String cur2 = soundingLibPath();
                                                java.util.Set<String> deletedNames = new java.util.HashSet<String>();
                                                for (int i = 0; i < libDisplayCount(); i++) {
                                                    Entry de = libEntryAtDisplay(i);
                                                    if (de == null) continue;
                                                    if (fav94) { if (!libFavFiles.contains(de.file.getName())) continue; }
                                                    else { if (libListenedFiles.contains(de.file.getName())) continue; }
                                                    if (de.file.getAbsolutePath().equals(cur2)) continue;
                                                    if (de.file.delete()) {
                                                        deletedNames.add(de.file.getName());
                                                        if (pendingDlPath != null && pendingDlPath.equals(de.file.getAbsolutePath())) {
                                                            pendingDlPath = null;
                                                            pendingDlTitle = null;
                                                            pendingDlRestart = false;
                                                        }
                                                        if (moveHeldPath != null && moveHeldPath.equals(de.file.getAbsolutePath()))
                                                            moveHeldPath = null;
                                                    }
                                                }
                                                if (deletedNames.isEmpty()) {
                                                    Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "delete_failed"), Toast.LENGTH_SHORT).show();
                                                } else {
                                                    pruneDlMapForFilenames(deletedNames);
                                                    loadList();
                                                    Toast.makeText(DownloadedActivity.this, tag94 + " " + deletedNames.size() + Lang.t(DownloadedActivity.this, "deleted_suffix"), Toast.LENGTH_SHORT).show();
                                                }
                                            }
                                        })
                                        .setNegativeButton(Lang.t(DownloadedActivity.this, "del_btn_no"), null)
                                        .show();
                            }
                        } else if ("dl_queue_all_star".equals(choiceKey)) {
                            // v4.59: queue the shelf's favourite files
                            // in shelf order — same skip rules as the
                            // chooser's verb (playing file, already
                            // queued) and the same count toast.
                            int added = 0;
                            for (int i = 0; i < entries.size(); i++) {
                                Entry be = entries.get(i);
                                if (!libFavFiles.contains(be.file.getName())) continue;
                                String u = be.file.getAbsolutePath();
                                if (u.equals(cur)) continue;
                                if (!q.contains(u)) { q.add(u); added++; }
                            }
                            Toast.makeText(DownloadedActivity.this, added > 0
                                    ? Lang.t(DownloadedActivity.this, "queue_star_prefix") + added + Lang.t(DownloadedActivity.this, "queue_added_suffix")
                                    : Lang.t(DownloadedActivity.this, "already_in_queue"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_queue_shown_star".equals(choiceKey)) {
                            // v6.40: queue only the shown favourite
                            // files, in display order — the shown-set
                            // variant of "★ အားလုံး တန်းစီ" below;
                            // same skip rules (playing file,
                            // already queued) and the same count
                            // toast, via the shared saveLibQueue +
                            // updateTotal tail below.
                            int added = 0;
                            for (int i = 0; i < libDisplayCount(); i++) {
                                Entry de = libEntryAtDisplay(i);
                                if (de == null) continue;
                                if (!libFavFiles.contains(de.file.getName())) continue;
                                String u = de.file.getAbsolutePath();
                                if (u.equals(cur)) continue;
                                if (!q.contains(u)) { q.add(u); added++; }
                            }
                            Toast.makeText(DownloadedActivity.this, added > 0
                                    ? Lang.t(DownloadedActivity.this, "queue_shown_star_prefix") + added + Lang.t(DownloadedActivity.this, "queue_added_suffix")
                                    : Lang.t(DownloadedActivity.this, "already_in_queue"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_queue_shown_new".equals(choiceKey)) {
                            // v6.40: queue only the shown not-yet-
                            // listened files, in display order — the
                            // shown-set variant of "မနားထောင်ရသေး
                            // တာ အားလုံး တန်းစီ" below; a file with
                            // no dlMap entry has no ✓ either, so it
                            // counts as unlistened, like that verb.
                            int added = 0;
                            for (int i = 0; i < libDisplayCount(); i++) {
                                Entry de = libEntryAtDisplay(i);
                                if (de == null) continue;
                                if (libListenedFiles.contains(de.file.getName())) continue;
                                String u = de.file.getAbsolutePath();
                                if (u.equals(cur)) continue;
                                if (!q.contains(u)) { q.add(u); added++; }
                            }
                            Toast.makeText(DownloadedActivity.this, added > 0
                                    ? Lang.t(DownloadedActivity.this, "queue_shown_new_prefix") + added + Lang.t(DownloadedActivity.this, "queue_added_suffix")
                                    : Lang.t(DownloadedActivity.this, "already_in_queue"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_queue_next_shown_star".equals(choiceKey)) {
                            // v6.71: queue only the shown favourite
                            // files NEXT — the head-insert twin of
                            // the v6.40 append above, mirroring the
                            // chooser's v6.69 and the drawer's v6.70.
                            // New shown ★ paths, in display order,
                            // go to the head of the queue (index 0
                            // plays next, the same head the v6.68
                            // verb uses), so the subset plays
                            // straight after the current file and
                            // before any older queue, without
                            // stopping playback. The playing file
                            // never re-queues and already-queued
                            // paths stay where they are (like every
                            // other bulk queue verb), so the
                            // already-queued toast covers the
                            // nothing-new case; the shared
                            // saveLibQueue + updateTotal tail below
                            // moves the ◈ marks and the footer at
                            // once.
                            java.util.ArrayList<String> next71 = new java.util.ArrayList<String>();
                            for (int i = 0; i < libDisplayCount(); i++) {
                                Entry de71 = libEntryAtDisplay(i);
                                if (de71 == null) continue;
                                if (!libFavFiles.contains(de71.file.getName())) continue;
                                String u71 = de71.file.getAbsolutePath();
                                if (u71.equals(cur)) continue;
                                if (q.contains(u71)) continue;
                                next71.add(u71);
                            }
                            if (!next71.isEmpty()) q.addAll(0, next71);
                            Toast.makeText(DownloadedActivity.this, !next71.isEmpty()
                                    ? Lang.t(DownloadedActivity.this, "queue_next_shown_star_prefix") + next71.size() + Lang.t(DownloadedActivity.this, "queue_next_suffix")
                                    : Lang.t(DownloadedActivity.this, "already_in_queue"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_queue_next_shown_new".equals(choiceKey)) {
                            // v6.71: queue only the shown not-yet-
                            // listened files NEXT — the head-insert
                            // twin of the v6.40 append above, same
                            // head and skip rules as the ★ twin: a
                            // file with no dlMap entry has no ✓
                            // either, so it counts as unlistened,
                            // like the v6.40 verb; the playing file
                            // never re-queues and already-queued
                            // paths stay where they are, so the
                            // already-queued toast covers the
                            // nothing-new case; the shared tail
                            // below saves and refreshes.
                            java.util.ArrayList<String> next71b = new java.util.ArrayList<String>();
                            for (int i = 0; i < libDisplayCount(); i++) {
                                Entry de71b = libEntryAtDisplay(i);
                                if (de71b == null) continue;
                                if (libListenedFiles.contains(de71b.file.getName())) continue;
                                String u71b = de71b.file.getAbsolutePath();
                                if (u71b.equals(cur)) continue;
                                if (q.contains(u71b)) continue;
                                next71b.add(u71b);
                            }
                            if (!next71b.isEmpty()) q.addAll(0, next71b);
                            Toast.makeText(DownloadedActivity.this, !next71b.isEmpty()
                                    ? Lang.t(DownloadedActivity.this, "queue_next_shown_new_prefix") + next71b.size() + Lang.t(DownloadedActivity.this, "queue_next_suffix")
                                    : Lang.t(DownloadedActivity.this, "already_in_queue"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_unqueue_shown".equals(choiceKey)) {
                            // v6.41: un-queue only the shown set,
                            // in display order — the shown-set
                            // counterpart of per-row "တန်းစီမှ
                            // ထုတ်" above and the mirror of the
                            // chooser's v6.29 verb. The playing
                            // file is skipped (it never queues)
                            // and files not queued are skipped,
                            // so the count is newly removed files
                            // only; the shared saveLibQueue +
                            // updateTotal tail below moves the ◈
                            // marks and the footer at once, and
                            // queued files outside the shown set
                            // stay queued.
                            int removed = 0;
                            for (int i = 0; i < libDisplayCount(); i++) {
                                Entry de = libEntryAtDisplay(i);
                                if (de == null) continue;
                                String u = de.file.getAbsolutePath();
                                if (u.equals(cur)) continue;
                                if (q.remove(u)) removed++;
                            }
                            Toast.makeText(DownloadedActivity.this, removed > 0
                                    ? Lang.t(DownloadedActivity.this, "queue_remove_shown_prefix") + removed + Lang.t(DownloadedActivity.this, "removed_suffix")
                                    : Lang.t(DownloadedActivity.this, "not_in_queue"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_unqueue_shown_star".equals(choiceKey)) {
                            // v7.00: un-queue only the shown ★
                            // files, in display order — the
                            // subset twin of the v6.41 shown-set
                            // verb above, with the ★ filter the
                            // v6.40/v6.71 queue verbs use
                            // (libFavFiles). The playing file is
                            // skipped (it never queues) and files
                            // not queued are skipped, so the count
                            // is newly removed files only; the
                            // shared saveLibQueue + updateTotal
                            // tail below moves the ◈ marks and
                            // the footer at once, and queued files
                            // outside the subset stay queued.
                            int removed00 = 0;
                            for (int i = 0; i < libDisplayCount(); i++) {
                                Entry de00 = libEntryAtDisplay(i);
                                if (de00 == null) continue;
                                if (!libFavFiles.contains(de00.file.getName())) continue;
                                String u00 = de00.file.getAbsolutePath();
                                if (u00.equals(cur)) continue;
                                if (q.remove(u00)) removed00++;
                            }
                            Toast.makeText(DownloadedActivity.this, removed00 > 0
                                    ? Lang.t(DownloadedActivity.this, "queue_remove_shown_star_prefix") + removed00 + Lang.t(DownloadedActivity.this, "removed_suffix")
                                    : Lang.t(DownloadedActivity.this, "not_in_queue"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_unqueue_shown_new".equals(choiceKey)) {
                            // v7.00: un-queue only the shown
                            // not-yet-listened files, in display
                            // order — the အသစ် twin of the ★
                            // verb above, with the unlistened
                            // filter the v6.40/v6.71 queue verbs
                            // use (absent from libListenedFiles).
                            // Same skip rules and shared tail:
                            // playing file skipped, unqueued
                            // files skipped, count is newly
                            // removed only, queued files outside
                            // the subset stay queued.
                            int removed00b = 0;
                            for (int i = 0; i < libDisplayCount(); i++) {
                                Entry de00b = libEntryAtDisplay(i);
                                if (de00b == null) continue;
                                if (libListenedFiles.contains(de00b.file.getName())) continue;
                                String u00b = de00b.file.getAbsolutePath();
                                if (u00b.equals(cur)) continue;
                                if (q.remove(u00b)) removed00b++;
                            }
                            Toast.makeText(DownloadedActivity.this, removed00b > 0
                                    ? Lang.t(DownloadedActivity.this, "queue_remove_shown_new_prefix") + removed00b + Lang.t(DownloadedActivity.this, "removed_suffix")
                                    : Lang.t(DownloadedActivity.this, "not_in_queue"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_fav_shown".equals(choiceKey)) {
                            // v6.42: star only the shown files, in
                            // display order — the same display
                            // positions the offer gate reads
                            // (libEntryAtDisplay over
                            // libDisplayCount). Files resolve to
                            // their source URL through the dlMap
                            // (the strip's reverse lookup); a file
                            // with no dlMap entry has no URL and
                            // is skipped, and already-starred URLs
                            // are skipped, so the count is newly
                            // starred files only. libFavFiles is
                            // updated in step so the rows rebind
                            // with their ★ marks at once via the
                            // shared tail below; neither the queue
                            // nor the shelf order is mutated.
                            int added = 0;
                            java.util.Set<String> flippedUrls1023 = new java.util.HashSet<String>();
                            java.util.Set<String> flippedPaths1023 = new java.util.HashSet<String>();
                            try {
                                android.content.SharedPreferences sp642 =
                                        getSharedPreferences("dhamma", MODE_PRIVATE);
                                java.util.Set<String> cur642 = sp642.getStringSet("favTalks", new java.util.HashSet<String>());
                                java.util.Set<String> next642 = new java.util.HashSet<String>(cur642);
                                org.json.JSONObject map642 =
                                        new org.json.JSONObject(sp642.getString("dlMap", "{}"));
                                // v8.47: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                                // filename resolves to no URL, never the first claimant.
                                java.util.HashMap<String, String> fn642 = new java.util.HashMap<String, String>();
                                java.util.HashMap<String, Integer> cnt642 = new java.util.HashMap<String, Integer>();
                                java.util.Iterator<String> keys642 = map642.keys();
                                while (keys642.hasNext()) {
                                    String u642 = keys642.next();
                                    String f642 = map642.optString(u642, "");
                                    if (f642.length() > 0) { fn642.put(f642, u642); Integer c642 = cnt642.get(f642); cnt642.put(f642, c642 == null ? 1 : c642 + 1); }
                                }
                                for (String k642 : cnt642.keySet()) if (cnt642.get(k642) > 1) fn642.remove(k642);
                                for (int i = 0; i < libDisplayCount(); i++) {
                                    Entry de642 = libEntryAtDisplay(i);
                                    if (de642 == null) continue;
                                    String u642 = fn642.get(de642.file.getName());
                                    if (u642 != null && next642.add(u642)) { added++; libFavFiles.add(de642.file.getName()); flippedUrls1023.add(u642); flippedPaths1023.add(de642.file.getAbsolutePath()); }
                                }
                                sp642.edit().putStringSet("favTalks", next642).apply();
                            } catch (Exception ignored) {}
                            // v10.23: bulk star leg — rebuild the paused card at once if the sounding file was starred.
                            refreshCardIfFlipped1023(flippedUrls1023, flippedPaths1023);
                            Toast.makeText(DownloadedActivity.this, added > 0
                                    ? Lang.t(DownloadedActivity.this, "fav_shown_prefix") + added + Lang.t(DownloadedActivity.this, "queue_added_suffix")
                                    : Lang.t(DownloadedActivity.this, "fav_already_starred"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_unfav_shown".equals(choiceKey)) {
                            // v6.43: un-star only the shown files, in
                            // display order — the same display
                            // positions the offer gate reads
                            // (libEntryAtDisplay over
                            // libDisplayCount). Files resolve to
                            // their source URL through the dlMap
                            // (the v6.42 reverse lookup); a file
                            // with no dlMap entry has no URL and
                            // is skipped, and already-unstarred
                            // URLs are skipped, so the count is
                            // newly un-starred files only.
                            // libFavFiles drops in step and
                            // applyDlFilter re-pours before the
                            // toast, so a ★-only view drops the
                            // un-starred rows at once (its empty
                            // view explains itself) and the
                            // shared tail below moves the footer
                            // ★ count; neither the queue nor the
                            // shelf order is mutated.
                            int removed = 0;
                            java.util.Set<String> flippedUrls1023 = new java.util.HashSet<String>();
                            java.util.Set<String> flippedPaths1023 = new java.util.HashSet<String>();
                            try {
                                android.content.SharedPreferences sp643 =
                                        getSharedPreferences("dhamma", MODE_PRIVATE);
                                java.util.Set<String> cur643 = sp643.getStringSet("favTalks", new java.util.HashSet<String>());
                                java.util.Set<String> next643 = new java.util.HashSet<String>(cur643);
                                org.json.JSONObject map643 =
                                        new org.json.JSONObject(sp643.getString("dlMap", "{}"));
                                // v8.47: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                                // filename resolves to no URL, never the first claimant.
                                java.util.HashMap<String, String> fn643 = new java.util.HashMap<String, String>();
                                java.util.HashMap<String, Integer> cnt643 = new java.util.HashMap<String, Integer>();
                                java.util.Iterator<String> keys643 = map643.keys();
                                while (keys643.hasNext()) {
                                    String u643 = keys643.next();
                                    String f643 = map643.optString(u643, "");
                                    if (f643.length() > 0) { fn643.put(f643, u643); Integer c643 = cnt643.get(f643); cnt643.put(f643, c643 == null ? 1 : c643 + 1); }
                                }
                                for (String k643 : cnt643.keySet()) if (cnt643.get(k643) > 1) fn643.remove(k643);
                                for (int i = 0; i < libDisplayCount(); i++) {
                                    Entry de643 = libEntryAtDisplay(i);
                                    if (de643 == null) continue;
                                    String u643 = fn643.get(de643.file.getName());
                                    if (u643 != null && next643.remove(u643)) { removed++; libFavFiles.remove(de643.file.getName()); flippedUrls1023.add(u643); flippedPaths1023.add(de643.file.getAbsolutePath()); }
                                }
                                sp643.edit().putStringSet("favTalks", next643).apply();
                            } catch (Exception ignored) {}
                            // v10.23: bulk un-star leg — rebuild the paused card at once if the sounding file was unstarred.
                            refreshCardIfFlipped1023(flippedUrls1023, flippedPaths1023);
                            applyDlFilter();
                            Toast.makeText(DownloadedActivity.this, removed > 0
                                    ? Lang.t(DownloadedActivity.this, "fav_remove_shown_prefix") + removed + Lang.t(DownloadedActivity.this, "removed_suffix")
                                    : Lang.t(DownloadedActivity.this, "not_in_fav"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_fav_shown_new".equals(choiceKey)) {
                            // v7.05: star only the shown not-yet-
                            // listened files, in display order —
                            // the library mirror of the chooser's
                            // v7.04 verb, v6.42's shape with the
                            // unlistened filter (absent from
                            // libListenedFiles) added. Files
                            // resolve through the dlMap; a file
                            // with no dlMap entry is skipped and
                            // already-starred URLs are skipped, so
                            // the count is newly starred fresh
                            // files only. No playing-file skip,
                            // like v7.04 and v6.42. libFavFiles
                            // grows in step so the rows rebind
                            // with their ★ marks at once via the
                            // shared tail below; neither the queue
                            // nor the shelf order is mutated.
                            int added705 = 0;
                            java.util.Set<String> flippedUrls1023 = new java.util.HashSet<String>();
                            java.util.Set<String> flippedPaths1023 = new java.util.HashSet<String>();
                            try {
                                android.content.SharedPreferences sp705 =
                                        getSharedPreferences("dhamma", MODE_PRIVATE);
                                java.util.Set<String> cur705 = sp705.getStringSet("favTalks", new java.util.HashSet<String>());
                                java.util.Set<String> next705 = new java.util.HashSet<String>(cur705);
                                org.json.JSONObject map705 =
                                        new org.json.JSONObject(sp705.getString("dlMap", "{}"));
                                // v8.47: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                                // filename resolves to no URL, never the first claimant.
                                java.util.HashMap<String, String> fn705 = new java.util.HashMap<String, String>();
                                java.util.HashMap<String, Integer> cnt705 = new java.util.HashMap<String, Integer>();
                                java.util.Iterator<String> keys705 = map705.keys();
                                while (keys705.hasNext()) {
                                    String u705 = keys705.next();
                                    String f705 = map705.optString(u705, "");
                                    if (f705.length() > 0) { fn705.put(f705, u705); Integer c705 = cnt705.get(f705); cnt705.put(f705, c705 == null ? 1 : c705 + 1); }
                                }
                                for (String k705 : cnt705.keySet()) if (cnt705.get(k705) > 1) fn705.remove(k705);
                                for (int i = 0; i < libDisplayCount(); i++) {
                                    Entry de705 = libEntryAtDisplay(i);
                                    if (de705 == null) continue;
                                    if (libListenedFiles.contains(de705.file.getName())) continue;
                                    String u705 = fn705.get(de705.file.getName());
                                    if (u705 != null && next705.add(u705)) { added705++; libFavFiles.add(de705.file.getName()); flippedUrls1023.add(u705); flippedPaths1023.add(de705.file.getAbsolutePath()); }
                                }
                                sp705.edit().putStringSet("favTalks", next705).apply();
                            } catch (Exception ignored) {}
                            // v10.23: bulk star-new leg — rebuild the paused card at once if the sounding file was starred.
                            refreshCardIfFlipped1023(flippedUrls1023, flippedPaths1023);
                            Toast.makeText(DownloadedActivity.this, added705 > 0
                                    ? Lang.t(DownloadedActivity.this, "fav_shown_new_prefix") + added705 + Lang.t(DownloadedActivity.this, "queue_added_suffix")
                                    : Lang.t(DownloadedActivity.this, "fav_already_starred"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_unfav_shown_new".equals(choiceKey)) {
                            // v7.05: un-star only the shown starred
                            // not-yet-listened files, in display
                            // order — the library mirror of the
                            // chooser's v7.04 verb, v6.43's shape
                            // with the unlistened filter (absent
                            // from libListenedFiles) added. Files
                            // resolve through the dlMap; a file
                            // with no dlMap entry is skipped and
                            // already-unstarred URLs are skipped,
                            // so the count is newly un-starred
                            // fresh files only. libFavFiles drops
                            // in step and applyDlFilter re-pours
                            // before the toast, so a ★-only view
                            // drops the un-starred rows at once
                            // (its empty view explains itself) and
                            // the shared tail below moves the
                            // footer ★ count; neither the queue
                            // nor the shelf order is mutated.
                            int removed705 = 0;
                            java.util.Set<String> flippedUrls1023 = new java.util.HashSet<String>();
                            java.util.Set<String> flippedPaths1023 = new java.util.HashSet<String>();
                            try {
                                android.content.SharedPreferences sp705b =
                                        getSharedPreferences("dhamma", MODE_PRIVATE);
                                java.util.Set<String> cur705b = sp705b.getStringSet("favTalks", new java.util.HashSet<String>());
                                java.util.Set<String> next705b = new java.util.HashSet<String>(cur705b);
                                org.json.JSONObject map705b =
                                        new org.json.JSONObject(sp705b.getString("dlMap", "{}"));
                                // v8.47: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                                // filename resolves to no URL, never the first claimant.
                                java.util.HashMap<String, String> fn705b = new java.util.HashMap<String, String>();
                                java.util.HashMap<String, Integer> cnt705b = new java.util.HashMap<String, Integer>();
                                java.util.Iterator<String> keys705b = map705b.keys();
                                while (keys705b.hasNext()) {
                                    String u705b = keys705b.next();
                                    String f705b = map705b.optString(u705b, "");
                                    if (f705b.length() > 0) { fn705b.put(f705b, u705b); Integer c705b = cnt705b.get(f705b); cnt705b.put(f705b, c705b == null ? 1 : c705b + 1); }
                                }
                                for (String k705b : cnt705b.keySet()) if (cnt705b.get(k705b) > 1) fn705b.remove(k705b);
                                for (int i = 0; i < libDisplayCount(); i++) {
                                    Entry de705b = libEntryAtDisplay(i);
                                    if (de705b == null) continue;
                                    if (libListenedFiles.contains(de705b.file.getName())) continue;
                                    String u705b = fn705b.get(de705b.file.getName());
                                    if (u705b != null && next705b.remove(u705b)) { removed705++; libFavFiles.remove(de705b.file.getName()); flippedUrls1023.add(u705b); flippedPaths1023.add(de705b.file.getAbsolutePath()); }
                                }
                                sp705b.edit().putStringSet("favTalks", next705b).apply();
                            } catch (Exception ignored) {}
                            // v10.23: bulk un-star-new leg — rebuild the paused card at once if the sounding file was unstarred.
                            refreshCardIfFlipped1023(flippedUrls1023, flippedPaths1023);
                            applyDlFilter();
                            Toast.makeText(DownloadedActivity.this, removed705 > 0
                                    ? Lang.t(DownloadedActivity.this, "fav_remove_shown_new_prefix") + removed705 + Lang.t(DownloadedActivity.this, "removed_suffix")
                                    : Lang.t(DownloadedActivity.this, "not_in_fav"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_mark_shown".equals(choiceKey)) {
                            // v6.44: mark only the shown files
                            // listened, in display order — the
                            // same display positions the offer
                            // gate reads (libEntryAtDisplay over
                            // libDisplayCount). Files resolve to
                            // their source URL through the dlMap
                            // (the v6.42 reverse lookup); a file
                            // with no dlMap entry has no URL and
                            // is skipped, the playing file is
                            // skipped by path (it earns its ✓ by
                            // finishing, like the chooser's v6.30
                            // skip), and already-played URLs are
                            // skipped, so the count is newly
                            // marked files only. libListenedFiles
                            // grows in step so the rows rebind
                            // with their ✓ marks at once, and
                            // applyDlFilter re-pours before the
                            // toast, so an unlistened-only view
                            // drops the marked rows at once (its
                            // empty view explains itself); neither
                            // the queue, the shelf order nor any
                            // saved resume position is mutated.
                            int added = 0;
                            try {
                                android.content.SharedPreferences sp644 =
                                        getSharedPreferences("dhamma", MODE_PRIVATE);
                                java.util.Set<String> cur644 = sp644.getStringSet("playedTalks", new java.util.HashSet<String>());
                                java.util.Set<String> next644 = new java.util.HashSet<String>(cur644);
                                org.json.JSONObject map644 =
                                        new org.json.JSONObject(sp644.getString("dlMap", "{}"));
                                // v8.47: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                                // filename resolves to no URL, never the first claimant.
                                java.util.HashMap<String, String> fn644 = new java.util.HashMap<String, String>();
                                java.util.HashMap<String, Integer> cnt644 = new java.util.HashMap<String, Integer>();
                                java.util.Iterator<String> keys644 = map644.keys();
                                while (keys644.hasNext()) {
                                    String u644 = keys644.next();
                                    String f644 = map644.optString(u644, "");
                                    if (f644.length() > 0) { fn644.put(f644, u644); Integer c644 = cnt644.get(f644); cnt644.put(f644, c644 == null ? 1 : c644 + 1); }
                                }
                                for (String k644 : cnt644.keySet()) if (cnt644.get(k644) > 1) fn644.remove(k644);
                                String curPath644 = service != null ? service.getCurrentUrl() : "\0";
                                for (int i = 0; i < libDisplayCount(); i++) {
                                    Entry de644 = libEntryAtDisplay(i);
                                    if (de644 == null) continue;
                                    if (de644.file.getAbsolutePath().equals(curPath644)) continue;
                                    String u644 = fn644.get(de644.file.getName());
                                    if (u644 != null && next644.add(u644)) { added++; libListenedFiles.add(de644.file.getName()); }
                                }
                                sp644.edit().putStringSet("playedTalks", next644).apply();
                            } catch (Exception ignored) {}
                            applyDlFilter();
                            Toast.makeText(DownloadedActivity.this, added > 0
                                    ? Lang.t(DownloadedActivity.this, "shown_plain_prefix") + added + Lang.t(DownloadedActivity.this, "marked_count_suffix")
                                    : Lang.t(DownloadedActivity.this, "already_listened"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_mark_shown_star".equals(choiceKey)) {
                            // v6.97: mark only the shown ★ files
                            // listened, in display order — the
                            // same display positions the offer
                            // gate reads, v6.44's shape with the
                            // ★ filter (libFavFiles) added. Files
                            // resolve through the dlMap, the
                            // playing file and already-played
                            // URLs are skipped, so the count is
                            // newly marked ★ files only;
                            // libListenedFiles grows in step and
                            // applyDlFilter re-pours before the
                            // toast, so an unlistened-only view
                            // drops the marked rows at once.
                            // Neither the queue, the shelf order
                            // nor any saved resume position is
                            // mutated.
                            int added697 = 0;
                            try {
                                android.content.SharedPreferences sp697 =
                                        getSharedPreferences("dhamma", MODE_PRIVATE);
                                java.util.Set<String> cur697 = sp697.getStringSet("playedTalks", new java.util.HashSet<String>());
                                java.util.Set<String> next697 = new java.util.HashSet<String>(cur697);
                                org.json.JSONObject map697 =
                                        new org.json.JSONObject(sp697.getString("dlMap", "{}"));
                                // v8.47: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                                // filename resolves to no URL, never the first claimant.
                                java.util.HashMap<String, String> fn697 = new java.util.HashMap<String, String>();
                                java.util.HashMap<String, Integer> cnt697 = new java.util.HashMap<String, Integer>();
                                java.util.Iterator<String> keys697 = map697.keys();
                                while (keys697.hasNext()) {
                                    String u697 = keys697.next();
                                    String f697 = map697.optString(u697, "");
                                    if (f697.length() > 0) { fn697.put(f697, u697); Integer c697 = cnt697.get(f697); cnt697.put(f697, c697 == null ? 1 : c697 + 1); }
                                }
                                for (String k697 : cnt697.keySet()) if (cnt697.get(k697) > 1) fn697.remove(k697);
                                String curPath697 = service != null ? service.getCurrentUrl() : "\0";
                                for (int i = 0; i < libDisplayCount(); i++) {
                                    Entry de697 = libEntryAtDisplay(i);
                                    if (de697 == null) continue;
                                    if (de697.file.getAbsolutePath().equals(curPath697)) continue;
                                    if (!libFavFiles.contains(de697.file.getName())) continue;
                                    String u697 = fn697.get(de697.file.getName());
                                    if (u697 != null && next697.add(u697)) { added697++; libListenedFiles.add(de697.file.getName()); }
                                }
                                sp697.edit().putStringSet("playedTalks", next697).apply();
                            } catch (Exception ignored) {}
                            applyDlFilter();
                            Toast.makeText(DownloadedActivity.this, added697 > 0
                                    ? Lang.t(DownloadedActivity.this, "shown_star_prefix") + added697 + Lang.t(DownloadedActivity.this, "marked_count_suffix")
                                    : Lang.t(DownloadedActivity.this, "already_listened"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_mark_shown_new".equals(choiceKey)) {
                            // v6.99: mark only the shown အသစ်
                            // files listened, in display order
                            // — the same display positions the
                            // offer gate reads, v6.44's shape
                            // with the subset label (the
                            // library mirror of the chooser's
                            // v6.95 အသစ် mark). Files resolve
                            // through the dlMap, the playing
                            // file and already-played URLs are
                            // skipped, so the count is newly
                            // marked အသစ် files only;
                            // libListenedFiles grows in step and
                            // applyDlFilter re-pours before the
                            // toast, so an unlistened-only view
                            // drops the marked rows at once.
                            // Neither the queue, the shelf order
                            // nor any saved resume position is
                            // mutated.
                            int added699 = 0;
                            try {
                                android.content.SharedPreferences sp699 =
                                        getSharedPreferences("dhamma", MODE_PRIVATE);
                                java.util.Set<String> cur699 = sp699.getStringSet("playedTalks", new java.util.HashSet<String>());
                                java.util.Set<String> next699 = new java.util.HashSet<String>(cur699);
                                org.json.JSONObject map699 =
                                        new org.json.JSONObject(sp699.getString("dlMap", "{}"));
                                // v8.47: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                                // filename resolves to no URL, never the first claimant.
                                java.util.HashMap<String, String> fn699 = new java.util.HashMap<String, String>();
                                java.util.HashMap<String, Integer> cnt699 = new java.util.HashMap<String, Integer>();
                                java.util.Iterator<String> keys699 = map699.keys();
                                while (keys699.hasNext()) {
                                    String u699 = keys699.next();
                                    String f699 = map699.optString(u699, "");
                                    if (f699.length() > 0) { fn699.put(f699, u699); Integer c699 = cnt699.get(f699); cnt699.put(f699, c699 == null ? 1 : c699 + 1); }
                                }
                                for (String k699 : cnt699.keySet()) if (cnt699.get(k699) > 1) fn699.remove(k699);
                                String curPath699 = service != null ? service.getCurrentUrl() : "\0";
                                for (int i = 0; i < libDisplayCount(); i++) {
                                    Entry de699 = libEntryAtDisplay(i);
                                    if (de699 == null) continue;
                                    if (de699.file.getAbsolutePath().equals(curPath699)) continue;
                                    String u699 = fn699.get(de699.file.getName());
                                    if (u699 != null && next699.add(u699)) { added699++; libListenedFiles.add(de699.file.getName()); }
                                }
                                sp699.edit().putStringSet("playedTalks", next699).apply();
                            } catch (Exception ignored) {}
                            applyDlFilter();
                            Toast.makeText(DownloadedActivity.this, added699 > 0
                                    ? Lang.t(DownloadedActivity.this, "shown_new_prefix") + added699 + Lang.t(DownloadedActivity.this, "marked_count_suffix")
                                    : Lang.t(DownloadedActivity.this, "already_listened"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_unmark_shown_star".equals(choiceKey)) {
                            // v6.98: un-mark only the shown ★ files
                            // listened, in display order — the
                            // same display positions the offer
                            // gate reads, v6.45's shape with the
                            // ★ filter (libFavFiles) added. Files
                            // resolve through the dlMap, the
                            // playing file and not-yet-played
                            // URLs are skipped, so the count is
                            // newly un-marked ★ files only;
                            // libListenedFiles drops in step and
                            // applyDlFilter re-pours before the
                            // toast, so the rows rebind without
                            // their ✓ marks at once. Neither the
                            // queue, the shelf order nor any
                            // saved resume position is mutated.
                            int removed698 = 0;
                            try {
                                android.content.SharedPreferences sp698 =
                                        getSharedPreferences("dhamma", MODE_PRIVATE);
                                java.util.Set<String> cur698 = sp698.getStringSet("playedTalks", new java.util.HashSet<String>());
                                java.util.Set<String> next698 = new java.util.HashSet<String>(cur698);
                                org.json.JSONObject map698 =
                                        new org.json.JSONObject(sp698.getString("dlMap", "{}"));
                                // v8.47: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                                // filename resolves to no URL, never the first claimant.
                                java.util.HashMap<String, String> fn698 = new java.util.HashMap<String, String>();
                                java.util.HashMap<String, Integer> cnt698 = new java.util.HashMap<String, Integer>();
                                java.util.Iterator<String> keys698 = map698.keys();
                                while (keys698.hasNext()) {
                                    String u698 = keys698.next();
                                    String f698 = map698.optString(u698, "");
                                    if (f698.length() > 0) { fn698.put(f698, u698); Integer c698 = cnt698.get(f698); cnt698.put(f698, c698 == null ? 1 : c698 + 1); }
                                }
                                for (String k698 : cnt698.keySet()) if (cnt698.get(k698) > 1) fn698.remove(k698);
                                String curPath698 = service != null ? service.getCurrentUrl() : "\0";
                                for (int i = 0; i < libDisplayCount(); i++) {
                                    Entry de698 = libEntryAtDisplay(i);
                                    if (de698 == null) continue;
                                    if (de698.file.getAbsolutePath().equals(curPath698)) continue;
                                    if (!libFavFiles.contains(de698.file.getName())) continue;
                                    String u698 = fn698.get(de698.file.getName());
                                    if (u698 != null && next698.remove(u698)) { removed698++; libListenedFiles.remove(de698.file.getName()); }
                                }
                                sp698.edit().putStringSet("playedTalks", next698).apply();
                            } catch (Exception ignored) {}
                            applyDlFilter();
                            Toast.makeText(DownloadedActivity.this, removed698 > 0
                                    ? Lang.t(DownloadedActivity.this, "shown_star_prefix") + removed698 + Lang.t(DownloadedActivity.this, "unmarked_count_suffix")
                                    : Lang.t(DownloadedActivity.this, "nothing_to_unmark"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_unmark_shown".equals(choiceKey)) {
                            // v6.45: un-mark only the shown files
                            // listened, in display order — the
                            // same display positions the offer
                            // gate reads (libEntryAtDisplay over
                            // libDisplayCount). Files resolve to
                            // their source URL through the dlMap
                            // (the v6.42 reverse lookup); a file
                            // with no dlMap entry has no URL and
                            // is skipped, the playing file is
                            // skipped by path (it keeps its ✓
                            // while it plays, like the chooser's
                            // v6.31 skip), and not-yet-played URLs
                            // are skipped, so the count is newly
                            // un-marked files only. libListenedFiles
                            // drops in step so the rows rebind
                            // without their ✓ marks at once, and
                            // applyDlFilter re-pours before the
                            // toast; neither the queue, the shelf
                            // order nor any saved resume position
                            // is mutated.
                            int removed = 0;
                            try {
                                android.content.SharedPreferences sp645 =
                                        getSharedPreferences("dhamma", MODE_PRIVATE);
                                java.util.Set<String> cur645 = sp645.getStringSet("playedTalks", new java.util.HashSet<String>());
                                java.util.Set<String> next645 = new java.util.HashSet<String>(cur645);
                                org.json.JSONObject map645 =
                                        new org.json.JSONObject(sp645.getString("dlMap", "{}"));
                                // v8.47: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                                // filename resolves to no URL, never the first claimant.
                                java.util.HashMap<String, String> fn645 = new java.util.HashMap<String, String>();
                                java.util.HashMap<String, Integer> cnt645 = new java.util.HashMap<String, Integer>();
                                java.util.Iterator<String> keys645 = map645.keys();
                                while (keys645.hasNext()) {
                                    String u645 = keys645.next();
                                    String f645 = map645.optString(u645, "");
                                    if (f645.length() > 0) { fn645.put(f645, u645); Integer c645 = cnt645.get(f645); cnt645.put(f645, c645 == null ? 1 : c645 + 1); }
                                }
                                for (String k645 : cnt645.keySet()) if (cnt645.get(k645) > 1) fn645.remove(k645);
                                String curPath645 = service != null ? service.getCurrentUrl() : "\0";
                                for (int i = 0; i < libDisplayCount(); i++) {
                                    Entry de645 = libEntryAtDisplay(i);
                                    if (de645 == null) continue;
                                    if (de645.file.getAbsolutePath().equals(curPath645)) continue;
                                    String u645 = fn645.get(de645.file.getName());
                                    if (u645 != null && next645.remove(u645)) { removed++; libListenedFiles.remove(de645.file.getName()); }
                                }
                                sp645.edit().putStringSet("playedTalks", next645).apply();
                            } catch (Exception ignored) {}
                            applyDlFilter();
                            Toast.makeText(DownloadedActivity.this, removed > 0
                                    ? Lang.t(DownloadedActivity.this, "shown_plain_prefix") + removed + Lang.t(DownloadedActivity.this, "unmarked_count_suffix")
                                    : Lang.t(DownloadedActivity.this, "nothing_to_unmark"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_share_shown".equals(choiceKey)) {
                            // v6.46: share the shown set as one
                            // message — the same display positions
                            // the offer gate reads
                            // (libEntryAtDisplay over
                            // libDisplayCount), in display order.
                            // Each shown file contributes its
                            // numbered decoded title + original
                            // link through the dlMap reverse
                            // lookup (the v6.42 map); files with
                            // no dlMap entry are skipped rather
                            // than shared as bare titles, and
                            // nothing is sent unless at least 2
                            // link-bearing files survive, so a
                            // narrowed set whose links mostly
                            // predate the dlMap never sends a
                            // header promising more talks than
                            // follow. The header names the
                            // library, not one sayadaw — shown
                            // files can come from many sayadaws
                            // — with the sent size (v11.40: the
                            // old shown size overstated whenever
                            // a shown file had no dlMap link),
                            // plus the dhammadownload.com
                            // credit. A share
                            // mutates nothing, so the shared
                            // save + rebind tail below is a
                            // no-op rewrite; the mini-player
                            // share (v4.78) already opens a sheet
                            // from this screen with no pause
                            // guard, so none is added here.
                            // v11.40: header is built AFTER the body count is known (see the n646 >= 2 branch) — the old header used libDisplayCount(), so a narrowed set with link-less files (pre-dlMap or dual-claimed) promised more talks than the body listed. The header now names the sent count, like the v6.90 subset headers.
                            StringBuilder sb646 = new StringBuilder();
                            int n646 = 0;
                            try {
                                android.content.SharedPreferences sp646 =
                                        getSharedPreferences("dhamma", MODE_PRIVATE);
                                org.json.JSONObject map646 =
                                        new org.json.JSONObject(sp646.getString("dlMap", "{}"));
                                // v8.48: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                                // filename resolves to no URL, never the first claimant, so a
                                // shared/copied list never names the wrong talk's link.
                                java.util.HashMap<String, String> fn646 = new java.util.HashMap<String, String>();
                                java.util.HashMap<String, Integer> cnt646 = new java.util.HashMap<String, Integer>();
                                java.util.Iterator<String> keys646 = map646.keys();
                                while (keys646.hasNext()) {
                                    String u646 = keys646.next();
                                    String f646 = map646.optString(u646, "");
                                    if (f646.length() > 0) { fn646.put(f646, u646); Integer c646 = cnt646.get(f646); cnt646.put(f646, c646 == null ? 1 : c646 + 1); }
                                }
                                for (String k646 : cnt646.keySet()) if (cnt646.get(k646) > 1) fn646.remove(k646);
                                for (int i = 0; i < libDisplayCount(); i++) {
                                    Entry de646 = libEntryAtDisplay(i);
                                    if (de646 == null) continue;
                                    String u646 = fn646.get(de646.file.getName());
                                    if (u646 == null) continue;
                                    n646++;
                                    sb646.append("\n").append(n646).append(". ")
                                            .append(de646.title == null ? "" : de646.title)
                                            .append("\n").append(u646);
                                }
                            } catch (Exception ignored) {}
                            if (n646 >= 2) {
                                StringBuilder out646 = new StringBuilder();
                                out646.append(Lang.t(DownloadedActivity.this, "dl_footer_downloaded") + Lang.t(DownloadedActivity.this, "dl_footer_shown")).append(n646).append(Lang.t(DownloadedActivity.this, "notif_queue_suffix") + "\n");
                                out646.append(sb646);
                                out646.append(Lang.t(DownloadedActivity.this, "share_source"));
                                Intent send646 = new Intent(Intent.ACTION_SEND);
                                send646.setType("text/plain");
                                send646.putExtra(Intent.EXTRA_TEXT, out646.toString());
                                // v8.55: share chooser — suppress the background-off pause.
                                suppressDlSharePause = true;
                                startActivity(Intent.createChooser(send646, Lang.t(DownloadedActivity.this, "share_shown")));
                            } else {
                                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "share_link_missing"), Toast.LENGTH_SHORT).show();
                            }
                        } else if ("dl_menu_share_shown_star".equals(choiceKey)) {
                            // v6.90: share only the shown ★ subset as one message — the
                            // subset twin of v6.46's shown-set
                            // share, run on the shown subset
                            // only. Same display positions the
                            // offer gate reads
                            // (libEntryAtDisplay over
                            // libDisplayCount), in display order,
                            // each subset file contributing its
                            // numbered decoded title + original
                            // link through the dlMap reverse
                            // lookup (the v6.42 map); files with
                            // no dlMap entry are skipped rather
                            // than shared as bare titles, and
                            // nothing is sent unless at least 2
                            // link-bearing subset files survive,
                            // so a narrowed subset whose links
                            // mostly predate the dlMap never sends
                            // a header promising more talks than
                            // follow. The header names the
                            // library with the surviving subset
                            // size (counted first, like the
                            // chooser's v6.89, not the full shown
                            // size), plus the dhammadownload.com
                            // credit. The playing file stays in
                            // the set on purpose, like v6.46 — a
                            // share list is not a play order. A
                            // share mutates nothing, so the shared
                            // save + rebind tail below is a no-op
                            // rewrite; the mini-player share
                            // (v4.78) already opens a sheet from
                            // this screen with no pause guard, so
                            // none is added here.
                            int c90 = 0;
                            try {
                                android.content.SharedPreferences sp90 =
                                        getSharedPreferences("dhamma", MODE_PRIVATE);
                                org.json.JSONObject map90 =
                                        new org.json.JSONObject(sp90.getString("dlMap", "{}"));
                                // v8.48: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                                // filename resolves to no URL, never the first claimant, so a
                                // shared/copied list never names the wrong talk's link.
                                java.util.HashMap<String, String> fn90 = new java.util.HashMap<String, String>();
                                java.util.HashMap<String, Integer> cnt90 = new java.util.HashMap<String, Integer>();
                                java.util.Iterator<String> keys90 = map90.keys();
                                while (keys90.hasNext()) {
                                    String u90 = keys90.next();
                                    String f90 = map90.optString(u90, "");
                                    if (f90.length() > 0) { fn90.put(f90, u90); Integer cc90 = cnt90.get(f90); cnt90.put(f90, cc90 == null ? 1 : cc90 + 1); }
                                }
                                for (String k90 : cnt90.keySet()) if (cnt90.get(k90) > 1) fn90.remove(k90);
                                for (int i = 0; i < libDisplayCount(); i++) {
                                    Entry de90 = libEntryAtDisplay(i);
                                    if (de90 == null) continue;
                                    if (!libFavFiles.contains(de90.file.getName())) continue;
                                    if (fn90.get(de90.file.getName()) == null) continue;
                                    c90++;
                                }
                                if (c90 >= 2) {
                                    StringBuilder sb90 = new StringBuilder();
                                    sb90.append(Lang.t(DownloadedActivity.this, "dl_footer_downloaded")).append(Lang.t(DownloadedActivity.this, "dl_tag_shown_star")).append(" ").append(c90).append(Lang.t(DownloadedActivity.this, "notif_queue_suffix") + "\n");
                                    int n90 = 0;
                                    for (int i = 0; i < libDisplayCount(); i++) {
                                        Entry de90 = libEntryAtDisplay(i);
                                        if (de90 == null) continue;
                                        if (!libFavFiles.contains(de90.file.getName())) continue;
                                        String u90 = fn90.get(de90.file.getName());
                                        if (u90 == null) continue;
                                        n90++;
                                        sb90.append("\n").append(n90).append(". ")
                                                .append(de90.title == null ? "" : de90.title)
                                                .append("\n").append(u90);
                                    }
                                    if (n90 >= 2) {
                                        sb90.append(Lang.t(DownloadedActivity.this, "share_source"));
                                        Intent send90 = new Intent(Intent.ACTION_SEND);
                                        send90.setType("text/plain");
                                        send90.putExtra(Intent.EXTRA_TEXT, sb90.toString());
                                        // v8.55: share chooser — suppress the background-off pause.
                                        suppressDlSharePause = true;
                                        startActivity(Intent.createChooser(send90, Lang.t(DownloadedActivity.this, "share_shown_star")));
                                    } else {
                                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "share_link_missing"), Toast.LENGTH_SHORT).show();
                                    }
                                } else {
                                    Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "share_link_missing"), Toast.LENGTH_SHORT).show();
                                }
                            } catch (Exception ignored) {}
                        } else if ("dl_menu_share_shown_new".equals(choiceKey)) {
                            // v6.90: share only the shown not-yet-heard subset as one message — the
                            // subset twin of v6.46's shown-set
                            // share, run on the shown subset
                            // only. Same display positions the
                            // offer gate reads
                            // (libEntryAtDisplay over
                            // libDisplayCount), in display order,
                            // each subset file contributing its
                            // numbered decoded title + original
                            // link through the dlMap reverse
                            // lookup (the v6.42 map); files with
                            // no dlMap entry are skipped rather
                            // than shared as bare titles, and
                            // nothing is sent unless at least 2
                            // link-bearing subset files survive,
                            // so a narrowed subset whose links
                            // mostly predate the dlMap never sends
                            // a header promising more talks than
                            // follow. The header names the
                            // library with the surviving subset
                            // size (counted first, like the
                            // chooser's v6.89, not the full shown
                            // size), plus the dhammadownload.com
                            // credit. The playing file stays in
                            // the set on purpose, like v6.46 — a
                            // share list is not a play order. A
                            // share mutates nothing, so the shared
                            // save + rebind tail below is a no-op
                            // rewrite; the mini-player share
                            // (v4.78) already opens a sheet from
                            // this screen with no pause guard, so
                            // none is added here.
                            int c90 = 0;
                            try {
                                android.content.SharedPreferences sp90 =
                                        getSharedPreferences("dhamma", MODE_PRIVATE);
                                org.json.JSONObject map90 =
                                        new org.json.JSONObject(sp90.getString("dlMap", "{}"));
                                // v8.48: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                                // filename resolves to no URL, never the first claimant, so a
                                // shared/copied list never names the wrong talk's link.
                                java.util.HashMap<String, String> fn90 = new java.util.HashMap<String, String>();
                                java.util.HashMap<String, Integer> cnt90 = new java.util.HashMap<String, Integer>();
                                java.util.Iterator<String> keys90 = map90.keys();
                                while (keys90.hasNext()) {
                                    String u90 = keys90.next();
                                    String f90 = map90.optString(u90, "");
                                    if (f90.length() > 0) { fn90.put(f90, u90); Integer cc90 = cnt90.get(f90); cnt90.put(f90, cc90 == null ? 1 : cc90 + 1); }
                                }
                                for (String k90 : cnt90.keySet()) if (cnt90.get(k90) > 1) fn90.remove(k90);
                                for (int i = 0; i < libDisplayCount(); i++) {
                                    Entry de90 = libEntryAtDisplay(i);
                                    if (de90 == null) continue;
                                    if (libListenedFiles.contains(de90.file.getName())) continue;
                                    if (fn90.get(de90.file.getName()) == null) continue;
                                    c90++;
                                }
                                if (c90 >= 2) {
                                    StringBuilder sb90 = new StringBuilder();
                                    sb90.append(Lang.t(DownloadedActivity.this, "dl_footer_downloaded")).append(Lang.t(DownloadedActivity.this, "dl_tag_shown_new")).append(" ").append(c90).append(Lang.t(DownloadedActivity.this, "notif_queue_suffix") + "\n");
                                    int n90 = 0;
                                    for (int i = 0; i < libDisplayCount(); i++) {
                                        Entry de90 = libEntryAtDisplay(i);
                                        if (de90 == null) continue;
                                        if (libListenedFiles.contains(de90.file.getName())) continue;
                                        String u90 = fn90.get(de90.file.getName());
                                        if (u90 == null) continue;
                                        n90++;
                                        sb90.append("\n").append(n90).append(". ")
                                                .append(de90.title == null ? "" : de90.title)
                                                .append("\n").append(u90);
                                    }
                                    if (n90 >= 2) {
                                        sb90.append(Lang.t(DownloadedActivity.this, "share_source"));
                                        Intent send90 = new Intent(Intent.ACTION_SEND);
                                        send90.setType("text/plain");
                                        send90.putExtra(Intent.EXTRA_TEXT, sb90.toString());
                                        // v8.55: share chooser — suppress the background-off pause.
                                        suppressDlSharePause = true;
                                        startActivity(Intent.createChooser(send90, Lang.t(DownloadedActivity.this, "share_shown_new")));
                                    } else {
                                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "share_link_missing"), Toast.LENGTH_SHORT).show();
                                    }
                                } else {
                                    Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "share_link_missing"), Toast.LENGTH_SHORT).show();
                                }
                            } catch (Exception ignored) {}
                        } else if ("dl_menu_copy_shown_star".equals(choiceKey)) {
                            // v6.92: copy only the shown ★ subset's links as one
                            // text — the copy twin of v6.90's subset share, run
                            // on the shown subset only. Same display positions
                            // the offer gate reads (libEntryAtDisplay over
                            // libDisplayCount), in display order, each subset
                            // file contributing its numbered decoded title +
                            // original link through the dlMap reverse lookup
                            // (the v6.42 map); files with no dlMap entry are
                            // skipped rather than copied as bare titles, and
                            // nothing is copied unless at least 2 link-bearing
                            // subset files survive, so a narrowed subset whose
                            // links mostly predate the dlMap never copies a
                            // header promising more talks than follow. The
                            // header names the library with the surviving
                            // subset size (counted first, like v6.90), plus
                            // the dhammadownload.com credit. The playing file
                            // stays in the set on purpose, like v6.46 — a link
                            // list is not a play order. A copy mutates
                            // nothing, so the shared save + rebind tail below
                            // is a no-op rewrite; it does not set any pause
                            // guard (no sheet is left to return from).
                            int c92 = 0;
                            try {
                                android.content.SharedPreferences sp92 =
                                        getSharedPreferences("dhamma", MODE_PRIVATE);
                                org.json.JSONObject map92 =
                                        new org.json.JSONObject(sp92.getString("dlMap", "{}"));
                                // v8.48: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                                // filename resolves to no URL, never the first claimant, so a
                                // shared/copied list never names the wrong talk's link.
                                java.util.HashMap<String, String> fn92 = new java.util.HashMap<String, String>();
                                java.util.HashMap<String, Integer> cnt92 = new java.util.HashMap<String, Integer>();
                                java.util.Iterator<String> keys92 = map92.keys();
                                while (keys92.hasNext()) {
                                    String u92 = keys92.next();
                                    String f92 = map92.optString(u92, "");
                                    if (f92.length() > 0) { fn92.put(f92, u92); Integer cc92 = cnt92.get(f92); cnt92.put(f92, cc92 == null ? 1 : cc92 + 1); }
                                }
                                for (String k92 : cnt92.keySet()) if (cnt92.get(k92) > 1) fn92.remove(k92);
                                for (int i = 0; i < libDisplayCount(); i++) {
                                    Entry de92 = libEntryAtDisplay(i);
                                    if (de92 == null) continue;
                                    if (!libFavFiles.contains(de92.file.getName())) continue;
                                    if (fn92.get(de92.file.getName()) == null) continue;
                                    c92++;
                                }
                                if (c92 >= 2) {
                                    StringBuilder sb92 = new StringBuilder();
                                    sb92.append(Lang.t(DownloadedActivity.this, "dl_footer_downloaded")).append(Lang.t(DownloadedActivity.this, "dl_tag_shown_star")).append(" ").append(c92).append(Lang.t(DownloadedActivity.this, "notif_queue_suffix") + "\n");
                                    int n92 = 0;
                                    for (int i = 0; i < libDisplayCount(); i++) {
                                        Entry de92 = libEntryAtDisplay(i);
                                        if (de92 == null) continue;
                                        if (!libFavFiles.contains(de92.file.getName())) continue;
                                        String u92 = fn92.get(de92.file.getName());
                                        if (u92 == null) continue;
                                        n92++;
                                        sb92.append("\n").append(n92).append(". ")
                                                .append(de92.title == null ? "" : de92.title)
                                                .append("\n").append(u92);
                                    }
                                    if (n92 >= 2) {
                                        sb92.append(Lang.t(DownloadedActivity.this, "share_source"));
                                        try {
                                            android.content.ClipboardManager cm92 =
                                                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                            if (cm92 != null)
                                                cm92.setPrimaryClip(android.content.ClipData.newPlainText(
                                                        Lang.t(DownloadedActivity.this, "clip_shown_star"), sb92.toString()));
                                            Toast.makeText(DownloadedActivity.this,
                                                    Lang.t(DownloadedActivity.this, "dl_shown_star_prefix") + n92 + Lang.t(DownloadedActivity.this, "dl_copy_count_suffix"),
                                                    Toast.LENGTH_SHORT).show();
                                        } catch (Exception ignored) {}
                                    } else {
                                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "copy_link_missing"), Toast.LENGTH_SHORT).show();
                                    }
                                } else {
                                    Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "copy_link_missing"), Toast.LENGTH_SHORT).show();
                                }
                            } catch (Exception ignored) {}
                        } else if ("dl_menu_copy_shown_new".equals(choiceKey)) {
                            // v6.92: copy only the shown not-yet-heard subset's
                            // links as one text — the copy twin of v6.90's
                            // subset share, run on the shown subset only. Same
                            // display positions the offer gate reads, in
                            // display order, each subset file contributing its
                            // numbered decoded title + original link through
                            // the dlMap reverse lookup; files with no dlMap
                            // entry are skipped, and nothing is copied unless
                            // at least 2 link-bearing subset files survive.
                            // The header names the library with the surviving
                            // subset size (counted first, like v6.90), plus
                            // the dhammadownload.com credit. The playing file
                            // stays in the set on purpose, like v6.46. A copy
                            // mutates nothing, so the shared save + rebind
                            // tail below is a no-op rewrite; no pause guard.
                            int c92n = 0;
                            try {
                                android.content.SharedPreferences sp92 =
                                        getSharedPreferences("dhamma", MODE_PRIVATE);
                                org.json.JSONObject map92 =
                                        new org.json.JSONObject(sp92.getString("dlMap", "{}"));
                                // v8.48: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                                // filename resolves to no URL, never the first claimant, so a
                                // shared/copied list never names the wrong talk's link.
                                java.util.HashMap<String, String> fn92 = new java.util.HashMap<String, String>();
                                java.util.HashMap<String, Integer> cnt92 = new java.util.HashMap<String, Integer>();
                                java.util.Iterator<String> keys92 = map92.keys();
                                while (keys92.hasNext()) {
                                    String u92 = keys92.next();
                                    String f92 = map92.optString(u92, "");
                                    if (f92.length() > 0) { fn92.put(f92, u92); Integer cc92 = cnt92.get(f92); cnt92.put(f92, cc92 == null ? 1 : cc92 + 1); }
                                }
                                for (String k92 : cnt92.keySet()) if (cnt92.get(k92) > 1) fn92.remove(k92);
                                for (int i = 0; i < libDisplayCount(); i++) {
                                    Entry de92 = libEntryAtDisplay(i);
                                    if (de92 == null) continue;
                                    if (libListenedFiles.contains(de92.file.getName())) continue;
                                    if (fn92.get(de92.file.getName()) == null) continue;
                                    c92n++;
                                }
                                if (c92n >= 2) {
                                    StringBuilder sb92 = new StringBuilder();
                                    sb92.append(Lang.t(DownloadedActivity.this, "dl_footer_downloaded")).append(Lang.t(DownloadedActivity.this, "dl_tag_shown_new")).append(" ").append(c92n).append(Lang.t(DownloadedActivity.this, "notif_queue_suffix") + "\n");
                                    int n92 = 0;
                                    for (int i = 0; i < libDisplayCount(); i++) {
                                        Entry de92 = libEntryAtDisplay(i);
                                        if (de92 == null) continue;
                                        if (libListenedFiles.contains(de92.file.getName())) continue;
                                        String u92 = fn92.get(de92.file.getName());
                                        if (u92 == null) continue;
                                        n92++;
                                        sb92.append("\n").append(n92).append(". ")
                                                .append(de92.title == null ? "" : de92.title)
                                                .append("\n").append(u92);
                                    }
                                    if (n92 >= 2) {
                                        sb92.append(Lang.t(DownloadedActivity.this, "share_source"));
                                        try {
                                            android.content.ClipboardManager cm92 =
                                                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                            if (cm92 != null)
                                                cm92.setPrimaryClip(android.content.ClipData.newPlainText(
                                                        Lang.t(DownloadedActivity.this, "clip_shown_new"), sb92.toString()));
                                            Toast.makeText(DownloadedActivity.this,
                                                    Lang.t(DownloadedActivity.this, "dl_shown_new_prefix") + n92 + Lang.t(DownloadedActivity.this, "dl_copy_count_suffix"),
                                                    Toast.LENGTH_SHORT).show();
                                        } catch (Exception ignored) {}
                                    } else {
                                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "copy_link_missing"), Toast.LENGTH_SHORT).show();
                                    }
                                } else {
                                    Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "copy_link_missing"), Toast.LENGTH_SHORT).show();
                                }
                            } catch (Exception ignored) {}
                        } else if ("dl_menu_copy_shown".equals(choiceKey)) {
                            // v6.49: copy the shown set as one
                            // text — the v6.46 share message
                            // verbatim, on the clipboard instead
                            // of the share sheet. Same display
                            // positions the offer gate reads
                            // (libEntryAtDisplay over
                            // libDisplayCount), in display order,
                            // each shown file contributing its
                            // numbered decoded title + original
                            // link through the dlMap reverse
                            // lookup (the v6.42 map); files with
                            // no dlMap entry are skipped rather
                            // than copied as bare titles, and
                            // nothing is copied unless at least 2
                            // link-bearing files survive, so a
                            // narrowed set whose links mostly
                            // predate the dlMap never copies a
                            // header promising more talks than
                            // follow. The header names the
                            // library with the sent size (v11.40,
                            // like v6.46), plus the
                            // dhammadownload.com credit. A copy
                            // mutates nothing, so
                            // the shared save + rebind tail below
                            // is a no-op rewrite, like v6.46; it
                            // does not set any pause guard (no
                            // sheet is left to return from). The
                            // toast names the copied count, since
                            // Android's own clipboard notice may
                            // not show the content.
                            // v11.40: same header-count fix as the v6.46 share above — header built after the body count is known, so the clipboard text, the toast count (n649) and the body can never disagree.
                            StringBuilder sb649 = new StringBuilder();
                            int n649 = 0;
                            try {
                                android.content.SharedPreferences sp649 =
                                        getSharedPreferences("dhamma", MODE_PRIVATE);
                                org.json.JSONObject map649 =
                                        new org.json.JSONObject(sp649.getString("dlMap", "{}"));
                                // v8.48: exactly-one claimant only (v8.41/v8.43 rule) — a dual-claimed
                                // filename resolves to no URL, never the first claimant, so a
                                // shared/copied list never names the wrong talk's link.
                                java.util.HashMap<String, String> fn649 = new java.util.HashMap<String, String>();
                                java.util.HashMap<String, Integer> cnt649 = new java.util.HashMap<String, Integer>();
                                java.util.Iterator<String> keys649 = map649.keys();
                                while (keys649.hasNext()) {
                                    String u649 = keys649.next();
                                    String f649 = map649.optString(u649, "");
                                    if (f649.length() > 0) { fn649.put(f649, u649); Integer c649 = cnt649.get(f649); cnt649.put(f649, c649 == null ? 1 : c649 + 1); }
                                }
                                for (String k649 : cnt649.keySet()) if (cnt649.get(k649) > 1) fn649.remove(k649);
                                for (int i = 0; i < libDisplayCount(); i++) {
                                    Entry de649 = libEntryAtDisplay(i);
                                    if (de649 == null) continue;
                                    String u649 = fn649.get(de649.file.getName());
                                    if (u649 == null) continue;
                                    n649++;
                                    sb649.append("\n").append(n649).append(". ")
                                            .append(de649.title == null ? "" : de649.title)
                                            .append("\n").append(u649);
                                }
                            } catch (Exception ignored) {}
                            if (n649 >= 2) {
                                StringBuilder out649 = new StringBuilder();
                                out649.append(Lang.t(DownloadedActivity.this, "dl_footer_downloaded") + Lang.t(DownloadedActivity.this, "dl_footer_shown")).append(n649).append(Lang.t(DownloadedActivity.this, "notif_queue_suffix") + "\n");
                                out649.append(sb649);
                                out649.append(Lang.t(DownloadedActivity.this, "share_source"));
                                try {
                                    android.content.ClipboardManager cm649 =
                                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                    if (cm649 != null)
                                        cm649.setPrimaryClip(android.content.ClipData.newPlainText(
                                                Lang.t(DownloadedActivity.this, "clip_shown"), out649.toString()));
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_shown_prefix") + n649 + Lang.t(DownloadedActivity.this, "dl_copy_count_suffix"),
                                            Toast.LENGTH_SHORT).show();
                                } catch (Exception ignored) {}
                            } else {
                                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "copy_link_missing"), Toast.LENGTH_SHORT).show();
                            }
                        } else if ("dl_menu_play_shown".equals(choiceKey)) {
                            // v6.47: play only the shown set from
                            // its first file, in display order —
                            // the same display positions the offer
                            // gate reads (libEntryAtDisplay over
                            // libDisplayCount), exactly like the
                            // rows render. The first shown file
                            // plays with row-tap semantics
                            // (saveLastLibrary anchor, saved-spot
                            // resume, glide to its row; an unbound
                            // service queues through pendingDlPath
                            // like a row tap before the bind, so
                            // the tap is never dropped); the rest
                            // are queued at the HEAD of the queue
                            // in display order, skipping
                            // already-queued paths and the playing
                            // file, so the set plays straight
                            // through before any older queue. Two
                            // orderings matter, as in the chooser's
                            // v6.32: the first file is dropped from
                            // the queue BEFORE play starts — a
                            // queued first file would otherwise
                            // replay after the set — and the shared
                            // tail below saves the new queue, so
                            // Next / auto-advance see it at once.
                            // If the first shown file is the one
                            // already playing, nothing restarts —
                            // the rest are simply head-queued
                            // behind it, like v6.32's stay-put
                            // branch. The queue-ahead trim a row
                            // tap does is deliberately NOT applied:
                            // this is the chooser verb, and older
                            // queued files simply wait behind the
                            // set. Neither the shelf order nor
                            // files outside the shown set are
                            // touched, and saved resume positions
                            // are untouched. The play toast counts
                            // playable files only (v6.32's count
                            // rule): the playing file is in the set
                            // but never counts as a file about to
                            // play.
                            java.util.ArrayList<Entry> shown647 = new java.util.ArrayList<Entry>();
                            for (int i = 0; i < libDisplayCount(); i++) {
                                Entry de647 = libEntryAtDisplay(i);
                                if (de647 != null) shown647.add(de647);
                            }
                            if (!shown647.isEmpty()) {
                                Entry first647 = shown647.get(0);
                                String firstPath647 = first647.file.getAbsolutePath();
                                boolean firstPlaying647 = firstPath647.equals(cur);
                                int playable647 = 0;
                                for (Entry e647 : shown647)
                                    if (!e647.file.getAbsolutePath().equals(cur)) playable647++;
                                java.util.ArrayList<String> rest647 = new java.util.ArrayList<String>();
                                for (int k647 = 1; k647 < shown647.size(); k647++) {
                                    String u647 = shown647.get(k647).file.getAbsolutePath();
                                    if (u647.equals(cur)) continue;
                                    if (q.contains(u647)) continue;
                                    rest647.add(u647);
                                }
                                q.remove(firstPath647);
                                if (!rest647.isEmpty()) q.addAll(0, rest647);
                                if (firstPlaying647) {
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_shown_prefix") + rest647.size() + Lang.t(DownloadedActivity.this, "dl_queued_count_suffix"),
                                            Toast.LENGTH_SHORT).show();
                                } else if (service == null) {
                                    pendingDlPath = firstPath647;
                                    pendingDlTitle = first647.title;
                                    pendingDlRestart = false;
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_shown_prefix") + Lang.t(DownloadedActivity.this, "dl_play_start_mid") + playable647 + Lang.t(DownloadedActivity.this, "dl_count_close"),
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    saveLastLibrary(firstPath647, first647.title);
                                    clearLibTrail13(); // v8.13
                                    service.play(firstPath647, first647.title, savedPosForFile(firstPath647));
                                    if (adapter != null) adapter.notifyDataSetChanged();
                                    glideToDlPath(firstPath647);
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_shown_prefix") + Lang.t(DownloadedActivity.this, "dl_play_start_mid") + playable647 + Lang.t(DownloadedActivity.this, "dl_count_close"),
                                            Toast.LENGTH_SHORT).show();
                                }
                            }
                        } else if ("dl_menu_play_shown_star".equals(choiceKey)
                                || "dl_menu_play_shown_new".equals(choiceKey)) {
                            // v6.74: play only the shown ★ / shown
                            // unlistened subset from its first
                            // file — v6.47's handler run on the
                            // filtered subset, in display order,
                            // the library mirror of the chooser's
                            // v6.72 and the drawer's v6.73. The
                            // subset's first file plays with
                            // row-tap semantics (saveLastLibrary
                            // anchor, saved-spot resume, glide to
                            // its row; an unbound service queues
                            // through pendingDlPath like a row tap
                            // before the bind, so the tap is never
                            // dropped); the rest are queued at
                            // the HEAD in display order, skipping
                            // already-queued paths and the playing
                            // file, so the subset plays straight
                            // through before any older queue. Same
                            // two orderings as v6.47: the first
                            // file is dropped from the queue
                            // BEFORE play starts, and the shared
                            // tail below saves the new queue, so
                            // Next / auto-advance see it at once.
                            // If the subset's first file is the
                            // one already playing, nothing
                            // restarts — the rest are simply
                            // head-queued behind it, like v6.47's
                            // stay-put branch. The queue-ahead
                            // trim a row tap does is deliberately
                            // NOT applied, the shelf order and
                            // files outside the subset are
                            // untouched, saved resume positions
                            // are untouched, and the play toast
                            // counts playable files only (the
                            // playing file is in the subset but
                            // never counts as a file about to
                            // play). The subset filter uses the
                            // same row-mark caches as the offer
                            // gate (★ in libFavFiles; unlistened
                            // = absent from libListenedFiles).
                            boolean fav674 = "dl_menu_play_shown_star".equals(choiceKey);
                            String tag674 = fav674 ? Lang.t(DownloadedActivity.this, "dl_tag_shown_star") : Lang.t(DownloadedActivity.this, "dl_tag_shown_new");
                            java.util.ArrayList<Entry> subset674 = new java.util.ArrayList<Entry>();
                            for (int i = 0; i < libDisplayCount(); i++) {
                                Entry de674 = libEntryAtDisplay(i);
                                if (de674 == null) continue;
                                if (fav674) {
                                    if (libFavFiles.contains(de674.file.getName())) subset674.add(de674);
                                } else {
                                    if (!libListenedFiles.contains(de674.file.getName())) subset674.add(de674);
                                }
                            }
                            if (!subset674.isEmpty()) {
                                Entry first674 = subset674.get(0);
                                String firstPath674 = first674.file.getAbsolutePath();
                                boolean firstPlaying674 = firstPath674.equals(cur);
                                int playable674 = 0;
                                for (Entry e674 : subset674)
                                    if (!e674.file.getAbsolutePath().equals(cur)) playable674++;
                                java.util.ArrayList<String> rest674 = new java.util.ArrayList<String>();
                                for (int k674 = 1; k674 < subset674.size(); k674++) {
                                    String u674 = subset674.get(k674).file.getAbsolutePath();
                                    if (u674.equals(cur)) continue;
                                    if (q.contains(u674)) continue;
                                    rest674.add(u674);
                                }
                                q.remove(firstPath674);
                                if (!rest674.isEmpty()) q.addAll(0, rest674);
                                if (firstPlaying674) {
                                    Toast.makeText(DownloadedActivity.this,
                                            tag674 + " " + rest674.size() + Lang.t(DownloadedActivity.this, "dl_queued_count_suffix"),
                                            Toast.LENGTH_SHORT).show();
                                } else if (service == null) {
                                    pendingDlPath = firstPath674;
                                    pendingDlTitle = first674.title;
                                    pendingDlRestart = false;
                                    Toast.makeText(DownloadedActivity.this,
                                            tag674 + " " + Lang.t(DownloadedActivity.this, "dl_play_start_mid") + playable674 + Lang.t(DownloadedActivity.this, "dl_count_close"),
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    saveLastLibrary(firstPath674, first674.title);
                                    clearLibTrail13(); // v8.13
                                    service.play(firstPath674, first674.title, savedPosForFile(firstPath674));
                                    if (adapter != null) adapter.notifyDataSetChanged();
                                    glideToDlPath(firstPath674);
                                    Toast.makeText(DownloadedActivity.this,
                                            tag674 + " " + Lang.t(DownloadedActivity.this, "dl_play_start_mid") + playable674 + Lang.t(DownloadedActivity.this, "dl_count_close"),
                                            Toast.LENGTH_SHORT).show();
                                }
                            }
                        } else if ("dl_menu_play_queued".equals(choiceKey)) {
                            // v6.77: play the hand queue from its
                            // head — the library mirror of the
                            // Room drawer's v6.75 and the chooser's
                            // v6.76. The head is the first queued
                            // path still on the shelf (a stale path
                            // is skipped, never played); the count
                            // names only shelf-resident queued
                            // files. The head is dropped from the
                            // queue BEFORE play starts — a queued
                            // head would otherwise replay on Next
                            // — so Next / auto-advance walk the
                            // rest of the queue in order via
                            // takeLibQueueHead, exactly like the
                            // v6.47 ordering (no queue-ahead trim
                            // is needed: the head is first, and
                            // stale paths ahead of it drop
                            // silently in takeLibQueueHead). The
                            // head plays with row-tap semantics
                            // (saveLastLibrary anchor, saved-spot
                            // resume, glide to its row; an unbound
                            // service queues through pendingDlPath
                            // like a row tap before the bind, so
                            // the tap is never dropped). If the
                            // head is the file already sounding
                            // (a stale menu), nothing restarts —
                            // the rest are simply left queued
                            // behind it, like v6.47's stay-put
                            // branch. The shared tail below saves
                            // the trimmed queue and refreshes the
                            // footer at once; neither the shelf
                            // order nor saved resume positions are
                            // touched.
                            Entry head677 = null;
                            int count677 = 0;
                            for (String p677 : q) {
                                Entry found677 = null;
                                for (Entry e677 : entries)
                                    if (e677.file.getAbsolutePath().equals(p677)) { found677 = e677; break; }
                                if (found677 == null) continue;
                                count677++;
                                if (head677 == null) head677 = found677;
                            }
                            if (head677 != null) {
                                String headPath677 = head677.file.getAbsolutePath();
                                boolean headPlaying677 = headPath677.equals(cur)
                                        && service != null && service.hasTrack();
                                q.remove(headPath677);
                                if (headPlaying677) {
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_queue_prefix") + (count677 - 1) + Lang.t(DownloadedActivity.this, "dl_queued_count_suffix"),
                                            Toast.LENGTH_SHORT).show();
                                } else if (service == null) {
                                    pendingDlPath = headPath677;
                                    pendingDlTitle = head677.title;
                                    pendingDlRestart = false;
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_queue_prefix") + Lang.t(DownloadedActivity.this, "dl_play_start_mid") + count677 + Lang.t(DownloadedActivity.this, "dl_count_close"),
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    saveLastLibrary(headPath677, head677.title);
                                    clearLibTrail13(); // v8.13
                                    service.play(headPath677, head677.title, savedPosForFile(headPath677));
                                    if (adapter != null) adapter.notifyDataSetChanged();
                                    glideToDlPath(headPath677);
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_queue_prefix") + Lang.t(DownloadedActivity.this, "dl_play_start_mid") + count677 + Lang.t(DownloadedActivity.this, "dl_count_close"),
                                            Toast.LENGTH_SHORT).show();
                                }
                            }
                        } else if ("dl_menu_shuffle_queued".equals(choiceKey)) {
                            // v6.88: shuffle-play the hand queue —
                            // the library mirror of the Talks
                            // chooser's v6.87 shuffle branch and
                            // the play twin of the library's own
                            // v6.77 forward play. Same gap as the
                            // chooser's: a listener who built a
                            // queue for a long listen and wanted
                            // it shuffled had to reorder it first
                            // ("တန်းစီ ကျပန်း ပြန်စီ") and then
                            // play from the head — and the reorder
                            // verb mutates the stored order even
                            // when the listener only wanted one
                            // shuffled listen. The shuffled order
                            // is built from the shelf-resident
                            // queued paths only (v6.77's rule — a
                            // stale path is skipped, never
                            // guessed); the shuffled first file
                            // plays with row-tap semantics like
                            // v6.77 (saveLastLibrary anchor,
                            // saved-spot resume, glide to its row;
                            // an unbound service queues through
                            // pendingDlPath like a row tap), and
                            // the rest replace the queue, so Next
                            // / auto-advance walk the shuffled
                            // order via takeLibQueueHead — and no
                            // stale path survives the rewrite, since
                            // the shared tail below saves exactly
                            // this list. If the shuffle lands on
                            // the file already sounding, nothing
                            // restarts — the rest are simply left
                            // queued behind it, like v6.77's
                            // stay-put branch. The play toast
                            // counts playable files only (v6.32's
                            // count rule, via the library's
                            // live-session check, not just the
                            // last path).
                            java.util.ArrayList<Entry> order88 = new java.util.ArrayList<Entry>();
                            for (String p88 : q) {
                                Entry found88 = null;
                                for (Entry e88 : entries)
                                    if (e88.file.getAbsolutePath().equals(p88)) { found88 = e88; break; }
                                if (found88 != null) order88.add(found88);
                            }
                            java.util.Collections.shuffle(order88);
                            if (!order88.isEmpty()) {
                                Entry first88 = order88.get(0);
                                String firstPath88 = first88.file.getAbsolutePath();
                                boolean firstPlaying88 = firstPath88.equals(cur)
                                        && service != null && service.hasTrack();
                                int playable88 = 0;
                                for (Entry e88 : order88)
                                    if (!(e88.file.getAbsolutePath().equals(cur)
                                            && service != null && service.hasTrack())) playable88++;
                                java.util.ArrayList<String> rest88 = new java.util.ArrayList<String>();
                                for (int k88 = 1; k88 < order88.size(); k88++)
                                    rest88.add(order88.get(k88).file.getAbsolutePath());
                                q.clear();
                                q.addAll(rest88);
                                if (firstPlaying88) {
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_queue_prefix") + rest88.size() + Lang.t(DownloadedActivity.this, "dl_shuffle_queued_suffix"),
                                            Toast.LENGTH_SHORT).show();
                                } else if (service == null) {
                                    pendingDlPath = firstPath88;
                                    pendingDlTitle = first88.title;
                                    pendingDlRestart = false;
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_queue_prefix") + Lang.t(DownloadedActivity.this, "dl_play_shuffle_mid") + playable88 + Lang.t(DownloadedActivity.this, "dl_count_close"),
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    saveLastLibrary(firstPath88, first88.title);
                                    clearLibTrail13(); // v8.13
                                    service.play(firstPath88, first88.title, savedPosForFile(firstPath88));
                                    if (adapter != null) adapter.notifyDataSetChanged();
                                    glideToDlPath(firstPath88);
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_queue_prefix") + Lang.t(DownloadedActivity.this, "dl_play_shuffle_mid") + playable88 + Lang.t(DownloadedActivity.this, "dl_count_close"),
                                            Toast.LENGTH_SHORT).show();
                                }
                            }
                        } else if ("dl_menu_reverse_queued".equals(choiceKey)) {
                            // v6.88: reverse-play the hand queue —
                            // the reverse twin of the shuffle branch
                            // above and the play twin of the reorder
                            // verb "တန်းစီ ပြောင်းပြန် စီ". Same
                            // shelf-resident order, same rest
                            // replaces the queue, same stay-put
                            // branch when the reversed first file
                            // is the one already sounding, same
                            // playable-only toast count.
                            java.util.ArrayList<Entry> order88r = new java.util.ArrayList<Entry>();
                            for (String p88r : q) {
                                Entry found88r = null;
                                for (Entry e88r : entries)
                                    if (e88r.file.getAbsolutePath().equals(p88r)) { found88r = e88r; break; }
                                if (found88r != null) order88r.add(found88r);
                            }
                            java.util.Collections.reverse(order88r);
                            if (!order88r.isEmpty()) {
                                Entry first88r = order88r.get(0);
                                String firstPath88r = first88r.file.getAbsolutePath();
                                boolean firstPlaying88r = firstPath88r.equals(cur)
                                        && service != null && service.hasTrack();
                                int playable88r = 0;
                                for (Entry e88r : order88r)
                                    if (!(e88r.file.getAbsolutePath().equals(cur)
                                            && service != null && service.hasTrack())) playable88r++;
                                java.util.ArrayList<String> rest88r = new java.util.ArrayList<String>();
                                for (int k88r = 1; k88r < order88r.size(); k88r++)
                                    rest88r.add(order88r.get(k88r).file.getAbsolutePath());
                                q.clear();
                                q.addAll(rest88r);
                                if (firstPlaying88r) {
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_queue_prefix") + rest88r.size() + Lang.t(DownloadedActivity.this, "dl_reverse_queued_suffix"),
                                            Toast.LENGTH_SHORT).show();
                                } else if (service == null) {
                                    pendingDlPath = firstPath88r;
                                    pendingDlTitle = first88r.title;
                                    pendingDlRestart = false;
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_queue_prefix") + Lang.t(DownloadedActivity.this, "dl_play_reverse_mid") + playable88r + Lang.t(DownloadedActivity.this, "dl_count_close"),
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    saveLastLibrary(firstPath88r, first88r.title);
                                    clearLibTrail13(); // v8.13
                                    service.play(firstPath88r, first88r.title, savedPosForFile(firstPath88r));
                                    if (adapter != null) adapter.notifyDataSetChanged();
                                    glideToDlPath(firstPath88r);
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_queue_prefix") + Lang.t(DownloadedActivity.this, "dl_play_reverse_mid") + playable88r + Lang.t(DownloadedActivity.this, "dl_count_close"),
                                            Toast.LENGTH_SHORT).show();
                                }
                            }
                        } else if ("dl_menu_shuffle_shown".equals(choiceKey)) {
                            // v6.48: shuffle-play only the shown
                            // files — the library mirror of the
                            // chooser's v6.33. Same display
                            // positions the offer gate reads,
                            // copied before shuffling so the
                            // shelf itself is never reordered;
                            // the shuffled first file plays with
                            // row-tap semantics like v6.47, and
                            // the rest are queued at the HEAD in
                            // shuffled order, skipping
                            // already-queued paths and the
                            // playing file, so the set plays
                            // shuffled straight through before
                            // any older queue. Same two orderings
                            // as v6.47: the first file is dropped
                            // from the queue BEFORE play, and the
                            // shared tail below saves the new
                            // queue, so Next / auto-advance see
                            // it at once. If the shuffle lands on
                            // the file already playing, nothing
                            // restarts — the rest are simply
                            // head-queued behind it, like v6.47's
                            // stay-put branch. The queue-ahead
                            // trim a row tap does is deliberately
                            // NOT applied, and the play toast
                            // counts playable files only.
                            java.util.ArrayList<Entry> shown648 = new java.util.ArrayList<Entry>();
                            for (int i = 0; i < libDisplayCount(); i++) {
                                Entry de648 = libEntryAtDisplay(i);
                                if (de648 != null) shown648.add(de648);
                            }
                            java.util.Collections.shuffle(shown648);
                            if (!shown648.isEmpty()) {
                                Entry first648 = shown648.get(0);
                                String firstPath648 = first648.file.getAbsolutePath();
                                boolean firstPlaying648 = firstPath648.equals(cur);
                                int playable648 = 0;
                                for (Entry e648 : shown648)
                                    if (!e648.file.getAbsolutePath().equals(cur)) playable648++;
                                java.util.ArrayList<String> rest648 = new java.util.ArrayList<String>();
                                for (int k648 = 1; k648 < shown648.size(); k648++) {
                                    String u648 = shown648.get(k648).file.getAbsolutePath();
                                    if (u648.equals(cur)) continue;
                                    if (q.contains(u648)) continue;
                                    rest648.add(u648);
                                }
                                q.remove(firstPath648);
                                if (!rest648.isEmpty()) q.addAll(0, rest648);
                                if (firstPlaying648) {
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_shown_prefix") + rest648.size() + Lang.t(DownloadedActivity.this, "dl_shuffle_queued_suffix"),
                                            Toast.LENGTH_SHORT).show();
                                } else if (service == null) {
                                    pendingDlPath = firstPath648;
                                    pendingDlTitle = first648.title;
                                    pendingDlRestart = false;
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_shown_prefix") + Lang.t(DownloadedActivity.this, "dl_play_shuffle_mid") + playable648 + Lang.t(DownloadedActivity.this, "dl_count_close"),
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    saveLastLibrary(firstPath648, first648.title);
                                    clearLibTrail13(); // v8.13
                                    service.play(firstPath648, first648.title, savedPosForFile(firstPath648));
                                    if (adapter != null) adapter.notifyDataSetChanged();
                                    glideToDlPath(firstPath648);
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_shown_prefix") + Lang.t(DownloadedActivity.this, "dl_play_shuffle_mid") + playable648 + Lang.t(DownloadedActivity.this, "dl_count_close"),
                                            Toast.LENGTH_SHORT).show();
                                }
                            }
                        } else if ("dl_menu_shuffle_shown_star".equals(choiceKey)
                                || "dl_menu_shuffle_shown_new".equals(choiceKey)) {
                            // v6.79: shuffle-play only the shown
                            // favourite / shown unlistened subset
                            // — v6.48's shuffle handler run on the
                            // v6.74-filtered subset, the library
                            // mirror of the chooser's v6.78. Same
                            // display positions the rows render,
                            // copied before shuffling so the shelf
                            // itself is never reordered; the
                            // shuffled first file plays with
                            // row-tap semantics like v6.47, and
                            // the rest are queued at the HEAD in
                            // shuffled order, skipping
                            // already-queued paths and the
                            // playing file, so the subset plays
                            // shuffled straight through before
                            // any older queue. Same two orderings
                            // as v6.47: the first file is dropped
                            // from the queue BEFORE play, and the
                            // shared tail below saves the new
                            // queue, so Next / auto-advance see
                            // it at once. If the shuffle lands on
                            // the file already playing, nothing
                            // restarts — the rest are simply
                            // head-queued behind it, like v6.48's
                            // stay-put branch. The queue-ahead
                            // trim a row tap does is deliberately
                            // NOT applied, files outside the subset
                            // are untouched, saved resume
                            // positions are untouched, and the play
                            // toast counts playable files only.
                            boolean fav679 = "dl_menu_shuffle_shown_star".equals(choiceKey);
                            String tag679 = fav679 ? Lang.t(DownloadedActivity.this, "dl_tag_shown_star") : Lang.t(DownloadedActivity.this, "dl_tag_shown_new");
                            java.util.ArrayList<Entry> subset679 = new java.util.ArrayList<Entry>();
                            for (int i = 0; i < libDisplayCount(); i++) {
                                Entry de679 = libEntryAtDisplay(i);
                                if (de679 == null) continue;
                                if (fav679) {
                                    if (libFavFiles.contains(de679.file.getName())) subset679.add(de679);
                                } else {
                                    if (!libListenedFiles.contains(de679.file.getName())) subset679.add(de679);
                                }
                            }
                            java.util.Collections.shuffle(subset679);
                            if (!subset679.isEmpty()) {
                                Entry first679 = subset679.get(0);
                                String firstPath679 = first679.file.getAbsolutePath();
                                boolean firstPlaying679 = firstPath679.equals(cur);
                                int playable679 = 0;
                                for (Entry e679 : subset679)
                                    if (!e679.file.getAbsolutePath().equals(cur)) playable679++;
                                java.util.ArrayList<String> rest679 = new java.util.ArrayList<String>();
                                for (int k679 = 1; k679 < subset679.size(); k679++) {
                                    String u679 = subset679.get(k679).file.getAbsolutePath();
                                    if (u679.equals(cur)) continue;
                                    if (q.contains(u679)) continue;
                                    rest679.add(u679);
                                }
                                q.remove(firstPath679);
                                if (!rest679.isEmpty()) q.addAll(0, rest679);
                                if (firstPlaying679) {
                                    Toast.makeText(DownloadedActivity.this,
                                            tag679 + " " + rest679.size() + Lang.t(DownloadedActivity.this, "dl_shuffle_queued_suffix"),
                                            Toast.LENGTH_SHORT).show();
                                } else if (service == null) {
                                    pendingDlPath = firstPath679;
                                    pendingDlTitle = first679.title;
                                    pendingDlRestart = false;
                                    Toast.makeText(DownloadedActivity.this,
                                            tag679 + " " + Lang.t(DownloadedActivity.this, "dl_play_shuffle_mid") + playable679 + Lang.t(DownloadedActivity.this, "dl_count_close"),
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    saveLastLibrary(firstPath679, first679.title);
                                    clearLibTrail13(); // v8.13
                                    service.play(firstPath679, first679.title, savedPosForFile(firstPath679));
                                    if (adapter != null) adapter.notifyDataSetChanged();
                                    glideToDlPath(firstPath679);
                                    Toast.makeText(DownloadedActivity.this,
                                            tag679 + " " + Lang.t(DownloadedActivity.this, "dl_play_shuffle_mid") + playable679 + Lang.t(DownloadedActivity.this, "dl_count_close"),
                                            Toast.LENGTH_SHORT).show();
                                }
                            }
                        } else if ("dl_menu_reverse_shown_star".equals(choiceKey)
                                || "dl_menu_reverse_shown_new".equals(choiceKey)) {
                            // v6.82: reverse-play only the shown
                            // favourite / shown unlistened subset
                            // — v6.48's reverse handler run on the
                            // v6.74-filtered subset, the library
                            // mirror of the chooser's v6.81. Same
                            // display positions the rows render,
                            // copied then reversed so the shelf
                            // itself is never reordered; the
                            // reversed first file plays with
                            // row-tap semantics like v6.47, and
                            // the rest are queued at the HEAD in
                            // reversed order, skipping
                            // already-queued paths and the
                            // playing file, so the subset plays
                            // backwards straight through before
                            // any older queue. Same two orderings
                            // as v6.47: the first file is dropped
                            // from the queue BEFORE play, and the
                            // shared tail below saves the new
                            // queue, so Next / auto-advance see
                            // it at once. If the reverse lands on
                            // the file already playing, nothing
                            // restarts — the rest are simply
                            // head-queued behind it, like v6.48's
                            // stay-put branch. The queue-ahead
                            // trim a row tap does is deliberately
                            // NOT applied, files outside the subset
                            // are untouched, saved resume
                            // positions are untouched, and the play
                            // toast counts playable files only.
                            boolean fav682 = "dl_menu_reverse_shown_star".equals(choiceKey);
                            String tag682 = fav682 ? Lang.t(DownloadedActivity.this, "dl_tag_shown_star") : Lang.t(DownloadedActivity.this, "dl_tag_shown_new");
                            java.util.ArrayList<Entry> subset682 = new java.util.ArrayList<Entry>();
                            for (int i = 0; i < libDisplayCount(); i++) {
                                Entry de682 = libEntryAtDisplay(i);
                                if (de682 == null) continue;
                                if (fav682) {
                                    if (libFavFiles.contains(de682.file.getName())) subset682.add(de682);
                                } else {
                                    if (!libListenedFiles.contains(de682.file.getName())) subset682.add(de682);
                                }
                            }
                            java.util.Collections.reverse(subset682);
                            if (!subset682.isEmpty()) {
                                Entry first682 = subset682.get(0);
                                String firstPath682 = first682.file.getAbsolutePath();
                                boolean firstPlaying682 = firstPath682.equals(cur);
                                int playable682 = 0;
                                for (Entry e682 : subset682)
                                    if (!e682.file.getAbsolutePath().equals(cur)) playable682++;
                                java.util.ArrayList<String> rest682 = new java.util.ArrayList<String>();
                                for (int k682 = 1; k682 < subset682.size(); k682++) {
                                    String u682 = subset682.get(k682).file.getAbsolutePath();
                                    if (u682.equals(cur)) continue;
                                    if (q.contains(u682)) continue;
                                    rest682.add(u682);
                                }
                                q.remove(firstPath682);
                                if (!rest682.isEmpty()) q.addAll(0, rest682);
                                if (firstPlaying682) {
                                    Toast.makeText(DownloadedActivity.this,
                                            tag682 + " " + rest682.size() + Lang.t(DownloadedActivity.this, "dl_reverse_queued_suffix"),
                                            Toast.LENGTH_SHORT).show();
                                } else if (service == null) {
                                    pendingDlPath = firstPath682;
                                    pendingDlTitle = first682.title;
                                    pendingDlRestart = false;
                                    Toast.makeText(DownloadedActivity.this,
                                            tag682 + " " + Lang.t(DownloadedActivity.this, "dl_play_reverse_mid") + playable682 + Lang.t(DownloadedActivity.this, "dl_count_close"),
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    saveLastLibrary(firstPath682, first682.title);
                                    clearLibTrail13(); // v8.13
                                    service.play(firstPath682, first682.title, savedPosForFile(firstPath682));
                                    if (adapter != null) adapter.notifyDataSetChanged();
                                    glideToDlPath(firstPath682);
                                    Toast.makeText(DownloadedActivity.this,
                                            tag682 + " " + Lang.t(DownloadedActivity.this, "dl_play_reverse_mid") + playable682 + Lang.t(DownloadedActivity.this, "dl_count_close"),
                                            Toast.LENGTH_SHORT).show();
                                }
                            }
                        } else if ("dl_menu_reverse_shown".equals(choiceKey)) {
                            // v6.48: reverse-play only the shown
                            // files — v6.47's forward play with
                            // the order reversed, the library
                            // mirror of the chooser's v6.34.
                            // Same display positions the rows
                            // render, copied and reversed so the
                            // shelf itself is never reordered;
                            // the reversed first file plays with
                            // row-tap semantics like v6.47, and
                            // the rest are queued at the HEAD in
                            // reversed order, skipping
                            // already-queued paths and the
                            // playing file. Same two orderings
                            // as v6.47 (first file dropped from
                            // the queue BEFORE play, shared tail
                            // saves the new queue) and the same
                            // stay-put branch when the reversed
                            // first file is already playing.
                            // Neither the shelf order nor files
                            // outside the shown set are touched,
                            // saved resume positions are
                            // untouched, and the play toast
                            // counts playable files only.
                            java.util.ArrayList<Entry> shown648r = new java.util.ArrayList<Entry>();
                            for (int i = 0; i < libDisplayCount(); i++) {
                                Entry de648r = libEntryAtDisplay(i);
                                if (de648r != null) shown648r.add(de648r);
                            }
                            java.util.Collections.reverse(shown648r);
                            if (!shown648r.isEmpty()) {
                                Entry first648r = shown648r.get(0);
                                String firstPath648r = first648r.file.getAbsolutePath();
                                boolean firstPlaying648r = firstPath648r.equals(cur);
                                int playable648r = 0;
                                for (Entry e648r : shown648r)
                                    if (!e648r.file.getAbsolutePath().equals(cur)) playable648r++;
                                java.util.ArrayList<String> rest648r = new java.util.ArrayList<String>();
                                for (int k648r = 1; k648r < shown648r.size(); k648r++) {
                                    String u648r = shown648r.get(k648r).file.getAbsolutePath();
                                    if (u648r.equals(cur)) continue;
                                    if (q.contains(u648r)) continue;
                                    rest648r.add(u648r);
                                }
                                q.remove(firstPath648r);
                                if (!rest648r.isEmpty()) q.addAll(0, rest648r);
                                if (firstPlaying648r) {
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_shown_prefix") + rest648r.size() + Lang.t(DownloadedActivity.this, "dl_reverse_queued_suffix"),
                                            Toast.LENGTH_SHORT).show();
                                } else if (service == null) {
                                    pendingDlPath = firstPath648r;
                                    pendingDlTitle = first648r.title;
                                    pendingDlRestart = false;
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_shown_prefix") + Lang.t(DownloadedActivity.this, "dl_play_reverse_mid") + playable648r + Lang.t(DownloadedActivity.this, "dl_count_close"),
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    saveLastLibrary(firstPath648r, first648r.title);
                                    clearLibTrail13(); // v8.13
                                    service.play(firstPath648r, first648r.title, savedPosForFile(firstPath648r));
                                    if (adapter != null) adapter.notifyDataSetChanged();
                                    glideToDlPath(firstPath648r);
                                    Toast.makeText(DownloadedActivity.this,
                                            Lang.t(DownloadedActivity.this, "dl_shown_prefix") + Lang.t(DownloadedActivity.this, "dl_play_reverse_mid") + playable648r + Lang.t(DownloadedActivity.this, "dl_count_close"),
                                            Toast.LENGTH_SHORT).show();
                                }
                            }
                        } else if ("dl_queue_all_unplayed".equals(choiceKey)) {
                            // v4.59: queue the shelf's not-yet-listened
                            // files in shelf order — a file with no
                            // dlMap entry has no ✓ either, so it
                            // counts as unlistened, like the chooser
                            // counts a talk absent from playedTalks.
                            int added = 0;
                            for (int i = 0; i < entries.size(); i++) {
                                Entry be = entries.get(i);
                                if (libListenedFiles.contains(be.file.getName())) continue;
                                String u = be.file.getAbsolutePath();
                                if (u.equals(cur)) continue;
                                if (!q.contains(u)) { q.add(u); added++; }
                            }
                            Toast.makeText(DownloadedActivity.this, added > 0
                                    ? Lang.t(DownloadedActivity.this, "queue_new_prefix") + added + Lang.t(DownloadedActivity.this, "queue_added_suffix")
                                    : Lang.t(DownloadedActivity.this, "already_in_queue"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_queue_all_list".equals(choiceKey)) {
                            int added = 0;
                            for (int i = 0; i < entries.size(); i++) {
                                String u = entries.get(i).file.getAbsolutePath();
                                if (u.equals(cur)) continue;
                                if (!q.contains(u)) { q.add(u); added++; }
                            }
                            Toast.makeText(DownloadedActivity.this, added > 0
                                    ? Lang.t(DownloadedActivity.this, "queue_plain_prefix") + added + Lang.t(DownloadedActivity.this, "queue_added_suffix")
                                    : Lang.t(DownloadedActivity.this, "already_in_queue"), Toast.LENGTH_SHORT).show();
                        } else if ("menu_queue_remove".equals(choiceKey)) {
                            q.remove(path);
                            // v7.85: a menu remove of the HELD file consumes the
                            // armed pick-up/drop move — the drop handler's
                            // stale-arm toast used to be the only cleanup, so
                            // the ✋ mark lingered after the file had already
                            // left the queue. A remove of any other row
                            // leaves the arm alone (the held file is
                            // still queued, so a drop stays meaningful).
                            if (path.equals(moveHeldPath)) {
                                moveHeldPath = null;
                                if (adapter != null) adapter.notifyDataSetChanged();
                            }
                            Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "removed_from_queue"), Toast.LENGTH_SHORT).show();
                        } else if ("menu_move_up".equals(choiceKey) || "menu_move_down".equals(choiceKey)) {
                            // v11.22: tap-time sounding re-check (v11.12
                            // class) — offered while waiting, sounding
                            // by tap time; the shared save tail below
                            // must never run for it.
                            // v11.27: bound-only read replaced by the
                            // tap-time cur mirror (v11.24) — unbound,
                            // the old check passed a sounding file.
                            if (path.equals(cur)) {
                                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                return;
                            }
                            int qi = q.indexOf(path);
                            int nj = "menu_move_up".equals(choiceKey) ? qi - 1 : qi + 1;
                            if (qi >= 0 && nj >= 0 && nj < q.size()) {
                                String tmp = q.get(qi);
                                q.set(qi, q.get(nj));
                                q.set(nj, tmp);
                                if (path.equals(moveHeldPath)) {
                                    moveHeldPath = null;
                                    if (adapter != null) adapter.notifyDataSetChanged();
                                }
                                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "moved_in_queue"), Toast.LENGTH_SHORT).show();
                            }
                        } else if ("menu_move_top".equals(choiceKey) || "menu_move_end".equals(choiceKey)) {
                            // v11.22: same tap-time sounding re-check
                            // as the one-step verbs above.
                            // v11.27: cur mirror, not the bound-only
                            // read — unbound, a head-consumed file
                            // (now sounding, no longer queued) was
                            // re-added here by the unconditional
                            // q.add below and replayed later.
                            if (path.equals(cur)) {
                                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                return;
                            }
                            q.remove(path);
                            if ("menu_move_top".equals(choiceKey)) q.add(0, path);
                            else q.add(path);
                            // v7.85: the library half of the v7.84 end-gap
                            // audit — the end itself was never missing
                            // here (this very verb reaches it, unlike
                            // the chooser's drop-only move), but the
                            // armed pick-up/drop move SURVIVED a menu
                            // move of its own held file: the ✋ mark
                            // stayed and the next drop tap moved the
                            // file a second time, after the listener
                            // had already finished moving it. A menu
                            // reorder of the held file now consumes
                            // the arm at once; shuffle / reverse below
                            // deliberately keep it (the held file is
                            // still queued, so a drop stays valid).
                            if (path.equals(moveHeldPath)) {
                                moveHeldPath = null;
                                if (adapter != null) adapter.notifyDataSetChanged();
                            }
                            Toast.makeText(DownloadedActivity.this,
                                    "menu_move_top".equals(choiceKey)
                                            ? Lang.t(DownloadedActivity.this, "moved_to_top")
                                            : Lang.t(DownloadedActivity.this, "moved_to_end"), Toast.LENGTH_SHORT).show();
                        } else if ("menu_move".equals(choiceKey)) {
                            // v11.22: same tap-time sounding re-check —
                            // never arm the file now playing.
                            // v11.27: cur mirror, like the two move
                            // verbs above (the drop's cache guard
                            // already killed a stale arm, this
                            // closes the arm itself unbound).
                            if (path.equals(cur)) {
                                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                return;
                            }
                            // v5.78: arm the pick-up/drop move — the
                            // queue itself is untouched, so the
                            // shared save + rebind below is a
                            // no-op rewrite that paints the ✋ mark
                            // on the held row at once.
                            moveHeldPath = path;
                            // v7.85: the arm toast now names the menu end-move
                            // (long-press the held row → "တန်းစီအောက်ဆုံးသို့ ရွှေ့"),
                            // the library's route to the very end that the
                            // chooser gained as a gesture in v7.84.
                            Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "queue_pick_target"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_reorder_shuffle".equals(choiceKey)) {
                            java.util.Collections.shuffle(q);
                            Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "queue_shuffled"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_reorder_reverse".equals(choiceKey)) {
                            java.util.Collections.reverse(q);
                            Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "queue_reversed"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_clear_queue".equals(choiceKey)) {
                            q.clear();
                            // v7.85: an emptied queue leaves no held file to
                            // drop — clear the arm here instead of letting
                            // the next drop tap stale-clear it with a toast.
                            if (moveHeldPath != null) {
                                moveHeldPath = null;
                                if (adapter != null) adapter.notifyDataSetChanged();
                            }
                            Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "queue_cleared"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_show_queue_only".equals(choiceKey)) {
                            // v4.58: enter queue-only view — the queue
                            // itself is untouched, rows re-pour once
                            // in the new view, and the list jumps to
                            // the queue head, mirroring the chooser.
                            // v5.89: this ⋮-menu entry is waiting-only
                            // now, closing the last gap v5.88 named:
                            // it used to set the flag directly,
                            // bypassing openLibQueueView's v5.88 gate,
                            // so a service-restart play still saved
                            // in the queue opened a queue-only view
                            // whose only row was the file already
                            // playing — the menu promised a queue
                            // and delivered the current file.
                            // Playing identity is the same v5.81
                            // footer rule the v5.88 gate uses
                            // (lastKind "lib" + lastLibPath from
                            // prefs), so the menu gate agrees with
                            // the footer count and with the ◈ gate
                            // on this same screen by construction;
                            // a Room session (lastKind "room"), a
                            // normal play (already trimmed) or a
                            // queue with any waiting file changes
                            // nothing. The menu OFFER stays raw
                            // (non-empty queue), like the ◈ button
                            // visibility v5.88 deliberately left —
                            // an all-playing tap toasts instead of
                            // opening, same as the ◈ gate.
                            String playLib89 = "";
                            try {
                                android.content.SharedPreferences lsp89 =
                                        getSharedPreferences("dhamma", MODE_PRIVATE);
                                if ("lib".equals(lsp89.getString("lastKind", "")))
                                    playLib89 = lsp89.getString("lastLibPath", "");
                            } catch (Exception ignored) {}
                            boolean anyWaiting89 = false;
                            for (String p89 : q)
                                if (!p89.equals(playLib89)) { anyWaiting89 = true; break; }
                            if (!anyWaiting89) {
                                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "queue_empty"), Toast.LENGTH_SHORT).show();
                            } else {
                                libQueueOnly = true;
                                lastDlAnimPos = -1;
                                if (dlListView != null) dlListView.setSelection(0);
                                Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "queue_only_view"), Toast.LENGTH_SHORT).show();
                            }
                        } else if ("dl_menu_show_all_again".equals(choiceKey)) {
                            // v4.58: exit queue-only view — position
                            // is deliberately kept, so the listener
                            // lands back in the full shelf where the
                            // queue view left them, not at the top.
                            libQueueOnly = false;
                            Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "list_all_back"), Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_share".equals(choiceKey)) {
                            // v6.53: share this one file — the
                            // stripShareText format for this row's
                            // own file (sayadaw when recorded, else
                            // title-only; original dhammadownload.com
                            // link from the dlMap reverse lookup;
                            // dhammadownload.com credit) with the
                            // same chooser title as the chooser and
                            // drawer single shares. No pause guard:
                            // the strip share opens a sheet from
                            // this screen with none (v6.46 note),
                            // and this sheet is the same kind. A
                            // share mutates nothing, so the shared
                            // saveLibQueue + updateTotal tail below
                            // is a no-op rewrite, like v6.35.
                            Intent send653 = new Intent(Intent.ACTION_SEND);
                            send653.setType("text/plain");
                            send653.putExtra(Intent.EXTRA_TEXT,
                                    (shareSayadaw653 != null ? shareSayadaw653 + " — " : "")
                                    + (t.title == null ? "" : t.title)
                                    + "\n" + shareUrl653
                                    + Lang.t(DownloadedActivity.this, "share_source"));
                            // v8.55: share chooser — suppress the background-off pause.
                            suppressDlSharePause = true;
                            startActivity(Intent.createChooser(send653, Lang.t(DownloadedActivity.this, "share_talk")));
                        } else if ("menu_fav_add".equals(choiceKey) || "menu_fav_remove".equals(choiceKey)) {
                            // v7.90: toggle this one file's star by its
                            // dlMap source URL (the v6.53 shareUrl653
                            // final), not by the label built above —
                            // if the set changed between the menu
                            // opening and the tap, the toast still
                            // reports the true new state, like the
                            // chooser's v7.88 handler. libFavFiles
                            // moves in step so the row's ★ mark
                            // rebinds at once via the shared tail
                            // below (saveLibQueue notifies) and the
                            // footer ★ count rebinds with it
                            // (updateTotal); a removal in a ★-only
                            // view re-pours the filter first, like
                            // the v6.43 shown-set un-star, so the row
                            // drops at once instead of lingering
                            // without its ★. Neither the queue nor
                            // the shelf order is mutated.
                            boolean added90 = shareUrl653 != null
                                    && Fx.toggleFavTalk(DownloadedActivity.this, shareUrl653);
                            // v10.24: paused-card star sync, single-file menu leg of v10.02/v10.23 —
                            // the old bound-only check compared just the file path, so it missed
                            // two cases: a Room session sounding this same talk (service track is
                            // the stream URL, not the file path) and an unbound activity (flip
                            // before bind completes). Route through refreshCardIfFlipped1023 with
                            // singleton URL + path sets instead: bound path matches either form,
                            // unbound path sends the no-bind ACTION_REFRESH_NOTIFICATION intent
                            // (liveSession guard, never starts a dead service) gated on prefs fresh
                            // at tap time. Gated on shareUrl653 != null: a null URL flips nothing.
                            // refreshNotification is a no-op with no track, and a rebuild with no
                            // star change paints the identical card. No new UI, no toast change.
                            if (shareUrl653 != null) {
                                java.util.Set<String> flippedUrls1024 = new java.util.HashSet<String>();
                                flippedUrls1024.add(shareUrl653);
                                java.util.Set<String> flippedPaths1024 = new java.util.HashSet<String>();
                                flippedPaths1024.add(t.file.getAbsolutePath());
                                refreshCardIfFlipped1023(flippedUrls1024, flippedPaths1024);
                            }
                            if (added90) libFavFiles.add(t.file.getName());
                            else libFavFiles.remove(t.file.getName());
                            if (!added90 && dlFilterMode == 1) applyDlFilter();
                            android.widget.Toast.makeText(DownloadedActivity.this,
                                    Lang.t(DownloadedActivity.this, added90 ? "fav_added" : "fav_removed"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_copy_link".equals(choiceKey)) {
                            // v6.56: copy this one file's own
                            // original link — shareUrl653 verbatim
                            // (the dlMap reverse lookup, never a
                            // bare title, never a guessed link),
                            // with no title and no credit line,
                            // like the chooser's v6.54 and the
                            // drawer's v6.55 single copies: a
                            // clipboard link pastes cleanly into
                            // a note or another app. No pause
                            // guard (no sheet is left to return
                            // from), and the toast names the file
                            // since Android's own clipboard
                            // notice may not show the content.
                            // A copy mutates nothing, so the
                            // shared saveLibQueue + updateTotal
                            // tail below is a no-op rewrite,
                            // like v6.53.
                            try {
                                android.content.ClipboardManager cm656 =
                                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                if (cm656 != null && shareUrl653 != null)
                                    cm656.setPrimaryClip(android.content.ClipData.newPlainText(
                                            Lang.t(DownloadedActivity.this, "clip_talk"), shareUrl653));
                                Toast.makeText(DownloadedActivity.this,
                                        Lang.t(DownloadedActivity.this, "link_copied_prefix") + t.title,
                                        Toast.LENGTH_SHORT).show();
                            } catch (Exception ignored) {}
                        }
                        java.util.Set<String> addedKeep08 = new java.util.HashSet<String>();
                        for (String p08 : q) if (!preQ08.contains(p08)) addedKeep08.add(p08);
                        saveLibQueue(q, addedKeep08, true);
                        // v4.66: a queue built from this menu used to
                        // leave the footer stale until the next
                        // loadList — the same class v4.65 fixed on
                        // the chooser header — so the footer rebinds
                        // here, with the fresh cache, at once.
                        updateTotal();
                    }
                }).show();
    }

    private class DlAdapter extends BaseAdapter {
        @Override public int getCount() {
            // v4.58: queue-only self-heal, mirroring the chooser's
            // getCount — any path that emptied the queue drops the
            // view on the next layout/notify instead of leaving an
            // empty list behind.
            healLibQueueOnly();
            return libDisplayCount();
        }
        @Override public Object getItem(int p) { return libEntryAtDisplay(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(DownloadedActivity.this)
                        .inflate(R.layout.row_downloaded, parent, false);
                Fx.press(v); // v2.08: press feedback on library rows
            }
            // v2.92: final row handle for the delete fade-out below —
            // getView's v is reassigned in the null branch, so it
            // cannot be captured by the delete dialog's listener.
            final View rowView = v;
            // v3.48: premium recycled-row squash hygiene, completing
            // the delivered v3.47 fix — Main grid, Talks list and
            // Room drawer all cancel a stale row animator on rebind,
            // but this library adapter never got it: its v3.40
            // branch only clears the pour view Animation, leaving
            // the v2.08 press squash (a ViewPropertyAnimator scale,
            // 0.96 on DOWN, spring-back to 1 over 180ms on UP/CANCEL)
            // untouched. A library row recycled mid-spring-back
            // (finger lifts into a fling inside that 180ms window)
            // used to carry a transient scale onto the new file:
            // the new row appeared slightly squashed or overshooting
            // past 1, then sprang to 1 on content that was never
            // pressed. A rebind now cancels any stale row animator
            // and restores scale 1. This never breaks the squash
            // itself: Fx.press re-arms only on touch events, which
            // arrive after bind, never during it; the entrance pour
            // below is a view Animation this cancel does not touch;
            // the delete fade is an ObjectAnimator on alpha, a
            // different channel this cancel cannot stop, and the
            // dialog blocks input while it runs so no rebind can
            // land mid-fade anyway; the pulse lives on the state
            // icon child, not the row; the delete morph lives on
            // the button child. Visual only; the bind below is
            // untouched.
            v.animate().cancel();
            Fx.cancelPress(v); // v3.90: press squash is an ObjectAnimator now — the cancel above cannot stop it.
            v.setScaleX(1f);
            v.setScaleY(1f);
            // v4.58: queue-only mapping — a stale queue slot binds
            // nothing rather than crashing on a shelf lookup.
            Entry e = libEntryAtDisplay(pos);
            // v11.73: ...but "binds nothing" returned the recycled view untouched, so a
            // stale slot showed another file's title/size/marks while its delete/menu
            // buttons still captured that other file — the confirm dialog named the file,
            // but the row itself lied. Clear the recycled content and disarm both
            // buttons; the normal bind below re-enables them, and the next tick prune
            // drops the slot.
            if (e == null) {
                android.widget.TextView t73 = v.findViewById(R.id.dlTitle);
                if (t73 != null) t73.setText("");
                android.widget.TextView s73 = v.findViewById(R.id.dlSize);
                if (s73 != null) s73.setText("");
                android.widget.TextView st73 = v.findViewById(R.id.dlState);
                if (st73 != null) st73.setText("");
                android.view.View ph73 = v.findViewById(R.id.dlPhoto);
                if (ph73 != null) ph73.setVisibility(android.view.View.GONE);
                android.view.View q73 = v.findViewById(R.id.queueMenu);
                if (q73 != null) { q73.setOnClickListener(null); q73.setEnabled(false); }
                android.view.View d73 = v.findViewById(R.id.btnDlDelete);
                if (d73 != null) { d73.setOnClickListener(null); d73.setEnabled(false); }
                return v;
            }
            boolean night = cachedDlNight;
            // v1.99: the row background never followed night mode — the
            // layout pins it to white @color/card, while the title
            // above is set white at night, so night titles were
            // invisible on their own white cards (same class as the
            // v1.98 header fix, which never reached the rows). Same
            // card drawables as the main grid's rows.
            android.view.View card = v.findViewById(R.id.cardInner);
            if (card != null) card.setBackgroundResource(night ? R.drawable.card_bg_night : R.drawable.card_bg);
            // v5.78: held-row mark + wash for an armed pick-up/
            // drop move — the picked-up file reads ✋ ahead of
            // its ◈ number plus a gold wash below, mirroring
            // the chooser's v5.77 held row, and only while it
            // is still queued: a queue cleared or reordered
            // from another menu drops the mark on the next
            // rebind instead of highlighting a stale row. The
            // wash sits on the row root, so the glass card
            // itself is untouched, and resets to TRANSPARENT
            // on every bind so it never leaks onto a recycled
            // row, like the chooser.
            boolean held = moveHeldPath != null
                    && moveHeldPath.equals(e.file.getAbsolutePath())
                    && libQueueCache.contains(e.file.getAbsolutePath());
            v.setBackgroundColor(held ? 0x33C9A227 : android.graphics.Color.TRANSPARENT);
            TextView title = v.findViewById(R.id.dlTitle);
            // v4.90: the playing row finally says so in its title
            // — the Talks chooser's v4.89 mirror. The state icon
            // already flipped ▶/⏸ and pulsed (v1.87/v2.25), but
            // the title itself read exactly like every other
            // row, so a listener browsing the shelf could not
            // spot the file the strip is naming without reading
            // the small icon. The sounding file (live lib
            // session, libRowPlayingPath from the preview
            // refresh) now carries "▶ " ahead of its title in
            // header gold; it never also carries a ◈ mark
            // (v4.57 rule, kept — the playing file is never
            // queued). Mark only: tap still re-opens the Room,
            // and a recycled row rebinds both text and colour
            // here so neither leaks. The 1s tick's v1.47
            // track/playing rebind moves the mark at once on
            // auto-advance or strip Next while browsing.
            // v11.15: an ended file is not playing — same v4.29
            // class as the Main grid mark: the shelf row kept its
            // ▶ + gold after an autoplay-off end (the library
            // tick rebinds on the playing flip, so the stale mark
            // re-appeared within a second of the end), and the
            // v11.08 ✓ suppression kept the finished file from
            // showing finished. The queue-preview/upcoming
            // machinery above is untouched: only this row mark
            // (and its ✓ suppression) follows the ended state.
            boolean rowPlaying = !AudioService.liveEnded
                    && libRowPlayingPath != null
                    && e.file.getAbsolutePath().equals(libRowPlayingPath);
            // v5.82: the hand-queue position mark skips the file
            // now playing — the footer (v5.81), strips (v5.80)
            // and card (v5.43) already count waiting files only,
            // but this mark numbered the raw libQueueCache, so a
            // service-restart play still saved in the queue pushed
            // every later mark one too high, and the playing file
            // itself wrongly carried a ◈ mark (the v4.57 comment's
            // "never in the queue" holds for Next/tap trims, not
            // for a restart play). libRowPlayingPath is this
            // bind's own playing identity (the ▶ row), so a
            // normal play (already popped) changes nothing, and
            // the playing row shows no mark in any view;
            // queue-only view keeps the raw number, matching the
            // Room drawer and chooser queue-only rows.
            String libQMark = "";
            int lqpos = libQueueCache.indexOf(e.file.getAbsolutePath());
            if (lqpos >= 0 && !rowPlaying) {
                int shown = lqpos + 1;
                if (!libQueueOnly && libRowPlayingPath != null && libRowPlayingPath.length() > 0) {
                    int playPos = libQueueCache.indexOf(libRowPlayingPath);
                    if (playPos >= 0 && playPos < lqpos) shown--;
                }
                libQMark = "◈" + shown + " ";
            }
            // v4.24: the row title carries the Room drawer's own
            // marks — ★ for a favourite talk, ✓ for a fully
            // listened one — so the offline shelf reads the same
            // way the queue does. Cache-built per load (above), so
            // a rebind costs two set lookups, never JSON or disk.
            // v11.08: the playing row never carries ✓ — the Room
            // drawer and Talks chooser playing rows suppress it
            // (v4.89/v11.07: a sounding talk's live position is
            // on the strip, not a finished mark), but this shelf
            // row kept it, so a replayed finished file read as
            // both playing and finished at once. ★ stays, like
            // both mirrors; the 1s tick already rebinds on a
            // track change, so the mark moves with the ▶.
            title.setText((rowPlaying ? "▶ " : "")
                    + (libFavFiles.contains(e.file.getName()) ? "★ " : "")
                    + (!rowPlaying && libListenedFiles.contains(e.file.getName()) ? "✓ " : "")
                    // v4.57: hand-queue position mark, the Talks
                    // chooser's ◈N (v4.48) mirrored here — the
                    // playing file never shows one (v5.82: the
                    // suppression is explicit now, since a
                    // service-restart play can still sit saved in
                    // the queue even though Next/tap trims it).
                    + (held ? "✋ " : "")
                    + libQMark
                    + e.title);
            title.setTextColor(rowPlaying
                    ? android.graphics.Color.parseColor("#C9A227")
                    : (night ? android.graphics.Color.WHITE
                    : android.graphics.Color.parseColor("#3B2314")));
            int f = cachedDlFont;
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, f == 0 ? 14 : (f == 1 ? 16 : 20));
            double mb = e.len / 1048576.0;
            TextView size = v.findViewById(R.id.dlSize);
            // v1.42: the download date beside the size, so the listener
            // can tell new downloads from old ones at a glance.
            // v4.24: the one file carrying a saved position also says
            // where it resumes ("· 12:34 မှ ဆက်") — the tap already
            // resumed from that spot since v1.90, but the row never
            // said so, so a resumed file read exactly like a fresh
            // one. The playing row's live clock overwrites this line
            // while it plays (v3.93) and the rebind restores it after.
            // v5.35: every half-heard file says where it resumes,
            // not just the one global-spot file — the map above
            // is the per-load cache, so this bind reads no prefs
            // and no JSON. The playing row is NOT excluded here
            // (unlike the Talks/Room title marks): this size line
            // IS where a paused file's position shows, and its
            // live clock overwrites the line while it plays, so
            // the hint only ever reads on a stopped row.
            Integer resumeMs =
                    libResumeMap.get(e.file.getAbsolutePath());
            String resumeHint = resumeMs != null
                    ? " · " + fmtDlTime(resumeMs) + Lang.t(DownloadedActivity.this, "dl_resume_suffix") : "";
            String rowSd = libSayadawMap.get(e.file.getName());
            // v6.05: shelf-row portrait — same resolution as the
            // library strip (v6.02): the once-per-process catalog
            // map, so this bind reads no JSON and no prefs per row
            // (the v1.57/v2.03 cost class); a not-yet-cached
            // portrait reads as that sayadaw's own gold initial
            // circle until its download lands, exactly like a grid
            // row, and a file with no recorded sayadaw hides the
            // view instead of showing a guessed face. Visibility
            // is reset on every bind so a recycled row never
            // carries the previous file's face onto a file with
            // no sayadaw, and ImageLoader's own tag/fade hygiene
            // covers a row recycled mid-download.
            android.view.View dlPhoto = v.findViewById(R.id.dlPhoto);
            if (dlPhoto != null) {
                if (rowSd == null) {
                    dlPhoto.setVisibility(android.view.View.GONE);
                } else {
                    dlPhoto.setVisibility(android.view.View.VISIBLE);
                    ImageLoader.load(DownloadedActivity.this,
                            AudioService.stripPhotoUrlForSayadaw(
                                    DownloadedActivity.this, rowSd),
                            rowSd, (CircleImageView) dlPhoto);
                }
            }
            // v12.46: the date is the scan-captured e.mod, completing
            // v11.89 — getView used to re-stat() the file on every
            // bind, so a file whose mtime moved with the same length
            // (identical-bytes re-download, or a touch by another
            // app) showed the NEW date in its OLD sort slot while
            // the playing row's tick line (captured mod) showed the
            // old one — the same file read two dates, and the date
            // contradicted the order. The v4.00 signature is
            // name:length only, so an mtime-only change fires no
            // reload; the captured value is the truth until a real
            // (length-changing) reload re-captures it.
            size.setText((rowSd != null ? rowSd + " · " : "")
                    + String.format("%.1f MB · %s", mb,
                    android.text.format.DateFormat.format("dd/MM/yyyy", e.mod)) + resumeHint);
            size.setTextColor(night ? android.graphics.Color.parseColor("#AAAAAA")
                    : android.graphics.Color.parseColor("#7A6A58"));
            // v1.96: the size+date line stayed at a fixed 13sp while
            // the row title followed the font size — on ကြီး the
            // date/size read smaller than the title above it.
            size.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, f == 0 ? 12 : (f == 1 ? 13 : 16));
            TextView state = v.findViewById(R.id.dlState);
            // v1.87: the row icon used to follow isPlaying() only —
            // the same label lie the Room and the notification card
            // fixed in v1.79: a file still buffering read ▶, but
            // tapping it HELD the incoming file paused. A buffering
            // file that will play is active (⏸); only a held one
            // reads ▶, matching the Room button and the card.
            boolean active = service != null
                    && (service.isPlaying()
                            || (service.isBuffering() && !service.isHoldPaused()))
                    && e.file.getAbsolutePath().equals(service.getCurrentUrl());
            state.setText(active ? "⏸" : "▶");
            // v2.01: the state icon sat at a fixed 22sp while the row
            // title (14/16/20) and size line (12/13/16) beside it
            // followed the font size — oversized on သေး, undersized
            // on ကြီး. Same step shape as the rows, one step up.
            state.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    f == 0 ? 18 : (f == 1 ? 22 : 26));
            // v1.99: the state icon kept its layout maroon at night —
            // near-invisible on the dark card above (same row fix).
            // Gold at night, maroon by day, like the main grid's star.
            state.setTextColor(night ? android.graphics.Color.parseColor("#C9A227")
                    : android.graphics.Color.parseColor("#7A2E1F"));
            // v2.25: playing-row pulse — the library's playing row
            // used to flip ▶/⏸ statically while the Room's playing
            // control breathes (v2.07 glow + v2.20 pulse), so the one
            // other surface showing a live playing state felt dead.
            // The ⏸ icon now breathes 1.00→0.55 on a 900ms reverse
            // loop (same language as the Room glow, text-safe: no
            // scale, so the row never shifts). The 1s poll rebinds
            // only on a playing/active change, so a pause or track
            // change reaches this branch and stops the pulse; a
            // recycled view is caught the same way, since its next
            // bind runs here with a different active flag. A rebind
            // of the still-playing row leaves the running pulse
            // alone instead of restarting it mid-breath.
            if (active) {
                if (dlPulseView != state) {
                    if (dlPulse != null) dlPulse.cancel();
                    // v3.14: premium library pulse-stop fade, mirroring
                    // the Room drawer's v3.13 fade — the old row's ⏸
                    // icon used to snap back to alpha 1 from wherever
                    // it sat mid-breath (as low as 0.55) the moment
                    // the track changed, a visible pop beside the new
                    // row's smooth pulse start. It now fades back to
                    // 1 over 200ms (decelerate, alpha-only, icon
                    // alone, never the row), so the breath hands over
                    // instead of cutting. A quick A->B->A handover
                    // first cancels any fade still running on the new
                    // pulse icon before the pulse starts (below), so
                    // the fade (ending at 1) never fights the pulse
                    // (1->0.55). A fade running on an icon recycled
                    // to another row simply settles to 1 within
                    // 200ms, which is the resting alpha of every
                    // non-playing icon anyway.
                    // v3.19: same ordering fix as the Room
                    // drawer's handover — clear a running
                    // pulse-start settle BEFORE cancelling the
                    // old icon's animator, or the cancelled
                    // settle's end action fires with its guards
                    // still passing and starts an infinite loop
                    // on the stopped icon, which the new icon's
                    // own settle guard (dlPulse != null) then
                    // refuses to replace.
                    dlPulseSettling = false;
                    if (dlPulseView != null) {
                        dlPulseView.animate().cancel();
                        dlPulseView.animate().alpha(1f).setDuration(200)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    }
                    state.animate().cancel();
                    dlPulseView = state;
                    // v3.18: premium library pulse-start settle,
                    // mirroring the Room drawer's v3.17 settle — a
                    // quick A->B->A file change leaves the
                    // returning icon mid-stop-fade back to 1 (the
                    // v3.14 fade runs 200ms), and the handover
                    // above cancels that fade and used to start
                    // the pulse loop whose first keyframe is
                    // exactly 1f, so the icon snapped from
                    // wherever the fade had reached straight to 1
                    // in one frame. The icon now settles instead:
                    // if it sits below 1 it glides to 1 over 150ms
                    // (decelerate) and the loop starts from the
                    // settle's end action; an icon already at 1
                    // starts its loop at once as before, so the
                    // common path gains no delay. Guards mirroring
                    // v3.17: a rebind of the still-playing row
                    // while settling does nothing (dlPulseView ==
                    // state), a pause during the settle clears
                    // the flag and still fades the icon softly to
                    // 1, a recycled view or a leave clears the
                    // settling flag with the view, and a second
                    // handover mid-settle cancels the old settle
                    // and either settles or starts the new icon
                    // directly with the flag cleared.
                    if (state.getAlpha() < 0.999f) {
                        dlPulseSettling = true;
                        final android.view.View settleIcon = state;
                        state.animate().alpha(1f).setDuration(150)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                .withEndAction(new Runnable() {
                                    @Override public void run() {
                                        if (!dlPulseSettling || dlPulseView != settleIcon || dlPulse != null) return;
                                        dlPulseSettling = false;
                                        dlPulse = android.animation.ObjectAnimator.ofFloat(settleIcon, "alpha", 1f, 0.55f);
                                        dlPulse.setDuration(900);
                                        dlPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                                        dlPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                                        dlPulse.start();
                                    }
                                }).start();
                    } else {
                        dlPulseSettling = false;
                        dlPulse = android.animation.ObjectAnimator.ofFloat(state, "alpha", 1f, 0.55f);
                        dlPulse.setDuration(900);
                        dlPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                        dlPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                        dlPulse.start();
                    }
                }
            } else if (dlPulseView == state) {
                if (dlPulse != null) dlPulse.cancel();
                dlPulse = null;
                dlPulseView = null;
                // v3.18: a pause during a pulse-start settle
                // cancels the settle too (same animator
                // channel) and clears its flag, so the
                // settle's end action never starts a loop
                // for a paused row; the fade below still
                // lands the icon softly at 1.
                dlPulseSettling = false;
                // v3.14: same pulse-stop fade as the track change
                // above — pausing mid-breath used to snap the icon
                // to 1 in one rebind while the Room's play glow
                // (v2.07) and wave ripple (v2.76) both settle softly.
                // Runs once: dlPulseView is null after this, so a
                // later rebind never restarts it, and a quick
                // resume cancels it via the handover guard above.
                state.animate().cancel();
                state.animate().alpha(1f).setDuration(200)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            }
            // v1.99: the per-row delete button kept its default size
            // while every other action button scaled in v1.98 — same
            // 14/16/18 action-button step as the header buttons.
            // v4.57: per-row queue menu button, mirroring the
            // Talks chooser's ⋮ (v4.47) — the library rows could
            // only play, restart or delete a file, never queue
            // one, so offline queue-building meant no queue at
            // all. Not focusable, so row taps keep working; the
            // delete button and the restart long-press are
            // untouched.
            android.widget.Button qmBtn = v.findViewById(R.id.queueMenu);
            if (qmBtn != null) {
                final int qpos = pos;
                qmBtn.setEnabled(true); // v11.73: re-arm after the stale-slot disarm above.
                Fx.press(qmBtn);
                // v11.93: the ⋮ glyph sat at a fixed 20sp while the
                // ▶/⏸ state icon beside it followed the font size
                // (18/22/26) — same glyph step, so it scales with it.
                qmBtn.setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_SP, f == 0 ? 18 : (f == 1 ? 22 : 26));
                qmBtn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        Fx.morph(view);
                        showLibQueueMenu(qpos);
                    }
                });
            }
            android.widget.Button delBtn = v.findViewById(R.id.btnDlDelete);
            if (delBtn != null) {
                delBtn.setEnabled(true); // v11.73: re-arm after the stale-slot disarm above.
                delBtn.setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_SP, f == 0 ? 14 : (f == 1 ? 16 : 18));
                // v2.00: the per-row delete button kept the default
                // light-theme face at night — a light widget on the
                // dark card above, same class as the header buttons
                // themed just above. Same colours as those buttons.
                delBtn.setBackgroundResource(night ? R.drawable.glass_btn_night : R.drawable.glass_btn);
                delBtn.setTextColor(night
                        ? android.graphics.Color.WHITE
                        : android.graphics.Color.parseColor("#3B2314"));
                // v2.11: the per-row delete button sits inside a row
                // that already squashes (v2.08), but a touch on the
                // button itself never reached the row's listener —
                // give the button its own press feedback.
                Fx.press(delBtn);
            }
            // v2.49: filter-change entrance — rows bound past the
            // guard fade + rise in 45ms steps, the library cascade's
            // own language (v2.12). The guard starts at MAX so the
            // first load is untouched, and only a set-changing search
            // keystroke (v2.49), an order-changing sort tap (v2.54),
            // a confirmed single delete (v2.55) or a set-changing
            // real return (v2.57) resets it (see applyDlFilter), so
            // scrolling, rotations, delete-all and unchanged
            // onResume reloads never re-pour.
            if (pos > lastDlAnimPos) {
                lastDlAnimPos = pos;
                float dy = 24f * v.getResources().getDisplayMetrics().density;
                android.view.animation.AnimationSet set = new android.view.animation.AnimationSet(true);
                android.view.animation.AlphaAnimation fa = new android.view.animation.AlphaAnimation(0f, 1f);
                fa.setDuration(300);
                android.view.animation.TranslateAnimation ta = new android.view.animation.TranslateAnimation(0f, 0f, dy, 0f);
                ta.setDuration(300);
                set.addAnimation(fa); set.addAnimation(ta);
                set.setStartOffset(Math.min(pos, 10) * 45);
                set.setInterpolator(new android.view.animation.DecelerateInterpolator());
                v.startAnimation(set);
            } else {
                // v3.40: same recycled-row pour hygiene as the Main
                // grid and Talks list — a view recycled mid-pour
                // keeps its old fade + rise running when rebound
                // to a position already past the guard, so the new
                // library row pours late on content that never
                // poured. Clear any stale animation here; settled
                // rows and the row's ViewPropertyAnimator effects
                // (press squash, delete fade) are untouched.
                // Visual only.
                v.clearAnimation();
            }
            // v1.19: delete this downloaded file (confirm first; pause if playing)
            v.findViewById(R.id.btnDlDelete).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    // v3.02: premium single-delete click beat — the
                    // per-row delete opens a confirm dialog like
                    // delete-all, but only delete-all morphed on
                    // dialog-open (v2.41); this one had only the
                    // press squash (v2.11). Same shared morph beat
                    // before the dialog, so the tap reads as
                    // accepted; the confirm path (v2.92 fade-out,
                    // disable, deletion) is untouched.
                    Fx.morph(view);
                    new android.app.AlertDialog.Builder(DownloadedActivity.this)
                            .setTitle(Lang.t(DownloadedActivity.this, "del_one_title"))
                            .setMessage(e.title)
                            .setPositiveButton(Lang.t(DownloadedActivity.this, "del_one_btn"), new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int which) {
                                // v2.92: premium single-row delete fade-out —
                                // the confirmed row used to vanish in the
                                // same frame as the dialog close while
                                // the rows below poured in (v2.55), so
                                // the exit moved in a different rhythm
                                // from the entrance. The row now fades
                                // to 0 over 200ms (decelerate, alpha-only)
                                // and only then is the file removed and
                                // the list refreshed, so the pour follows
                                // the fade. The dialog blocks list input
                                // while open, so the captured rowView
                                // still represents this entry; the
                                // delete button is disabled at once so
                                // a second tap cannot double-delete
                                // mid-fade. The finish path runs once
                                // on end OR cancel (a detach mid-fade
                                // must never swallow the deletion), and
                                // restores alpha to 1 before the refresh
                                // so the recycled view never rebinds
                                // faded for another row. A detached row
                                // (rotation during the dialog) deletes
                                // instantly with no animation.
                                view.setEnabled(false);
                                final Runnable doDelete = new Runnable() {
                                    @Override public void run() {
                                    // v1.56: same stale-track fix as delete-all —
                                    // deleting the playing file ends its session.
                                    // v11.73: unbound-window mirror — with no bind (fresh-open window, or
                                    // service death with no rebind until recreation) the sounding file is
                                    // read via soundingLibPath and stopped with the ACTION_STOP intent,
                                    // the strip stop button's unbound verb. Without it the deleted talk
                                    // kept playing on its open file descriptor.
                                    String sounding73 = soundingLibPath();
                                    if (sounding73.length() > 0 && e.file.getAbsolutePath().equals(sounding73)) {
                                        if (service != null) service.stopAndClear();
                                        else {
                                            android.content.Intent stop73 = new android.content.Intent(DownloadedActivity.this, AudioService.class);
                                            stop73.setAction(AudioService.ACTION_STOP);
                                            startService(stop73);
                                        }
                                    }
                                    // v1.63: same for a queued tap — a deleted
                                    // file must never reach the bind handler.
                                    if (pendingDlPath != null && pendingDlPath.equals(e.file.getAbsolutePath())) {
                                        pendingDlPath = null;
                                        pendingDlTitle = null;
                                        pendingDlRestart = false;
                                    }
                                    if (e.file.delete()) {
                                        // v1.98: drop this file's map
                                        // entry with it (see prune).
                                        java.util.Set<String> one = new java.util.HashSet<String>();
                                        one.add(e.file.getName());
                                        pruneDlMapForFilenames(one);
                                        // v12.53: remove by path, not by
                                        // object — `e` is the Entry the
                                        // row bound when the dialog
                                        // opened, but any loadList() in
                                        // between (onResume on a Home +
                                        // return while the dialog sat
                                        // open, or the 1s tick when a
                                        // download landed) rebuilds
                                        // `entries` with fresh Entry
                                        // objects, and Entry has no
                                        // equals(), so entries.remove(e)
                                        // was an identity remove that
                                        // silently no-opped: the file
                                        // was gone from disk and the
                                        // toast said deleted, yet the
                                        // row stayed listed (footer
                                        // count one too high; deleting
                                        // the last file never showed
                                        // the empty view). The path is
                                        // unique per file in this
                                        // folder, so it names exactly
                                        // this row; a path already
                                        // pruned by a rescan simply
                                        // matches nothing.
                                        String delPath53 = e.file.getAbsolutePath();
                                        for (int ri53 = entries.size() - 1; ri53 >= 0; ri53--)
                                            if (entries.get(ri53).file.getAbsolutePath().equals(delPath53)) entries.remove(ri53);
                                        // v4.58: a single delete goes
                                        // through applyDlFilter, not
                                        // loadList, so prune the hand
                                        // queue here too — otherwise
                                        // the deleted path would sit
                                        // as a stale queue-only slot
                                        // until the next full load.
                                        refreshLibQueueCache();
                                        // v2.59: a single delete that empties
                                        // the library arms the empty-state
                                        // fade; applyDlFilter below shows
                                        // the empty view (the old direct
                                        // show here would have made it
                                        // appear instantly, before the fade
                                        // could run). A non-emptying delete
                                        // leaves the flag clear, as before.
                                        if (entries.isEmpty()) dlEmptyFade = true;
                                        // v2.56: arm the delete flag BEFORE
                                        // updateTotal so the footer total
                                        // fades with the row pour below;
                                        // applyDlFilter consumes it after.
                                        // v12.37: filter first, footer
                                        // second — updateTotal's Up Next
                                        // line reads shownDl, which still
                                        // held the deleted file here, so
                                        // the footer named a deleted file
                                        // as next until a track change.
                                        // The flag is re-armed for the
                                        // footer fade guard after the
                                        // filter consumes it, then
                                        // cleared, like loadList above.
                                        dlDeleteChange = true; // v2.55: delete-change entrance (see applyDlFilter)
                                        applyDlFilter();
                                        dlDeleteChange = true;
                                        updateTotal();
                                        dlDeleteChange = false;
                                        // v11.89: adopt the directory signature this delete just made —
                                        // a single delete mutates the shelf without loadList, so the tick
                                        // used to see a changed signature and fire a redundant full rescan
                                        // that re-poured the whole list ~2s after every delete. Delete-all
                                        // is immune (it calls loadList, which adopts it there).
                                        dlDirSig = dlDirSignature();
                                        dlDirPendingSig = null;
                                        dlDirPendingTicks = 0;
                                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "dl_deleted_done"), Toast.LENGTH_SHORT).show();
                                    } else {
                                        view.setEnabled(true); // v2.92: failed delete keeps its row usable
                                        Toast.makeText(DownloadedActivity.this, Lang.t(DownloadedActivity.this, "delete_failed"), Toast.LENGTH_SHORT).show();
                                    }
                                    }
                                };
                                if (rowView.getWindowToken() != null) {
                                    android.animation.ObjectAnimator fade =
                                            android.animation.ObjectAnimator.ofFloat(rowView, "alpha", 0f);
                                    fade.setDuration(200);
                                    fade.setInterpolator(new android.view.animation.DecelerateInterpolator());
                                    fade.addListener(new android.animation.AnimatorListenerAdapter() {
                                        boolean done = false;
                                        void finish() {
                                            if (done) return;
                                            done = true;
                                            rowView.setAlpha(1f);
                                            doDelete.run();
                                        }
                                        @Override public void onAnimationEnd(android.animation.Animator a) { finish(); }
                                        @Override public void onAnimationCancel(android.animation.Animator a) { finish(); }
                                    });
                                    fade.start();
                                } else {
                                    doDelete.run();
                                }
                                }
                            })
                            .setNegativeButton(Lang.t(DownloadedActivity.this, "del_btn_no"), null)
                            .show();
                }
            });
            return v;
        }
    }
}
