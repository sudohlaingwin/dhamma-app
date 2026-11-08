package com.h2h.dhamma;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

// v12.68: Wi-Fi auto-download — premium backlog #5 (user order
// 2026-11-06): new talks from ★-favourited sayadaws download
// themselves on Wi-Fi. A periodic JobScheduler job (6h, unmetered
// only, persisted across reboots) checks each favourite's page
// with the chooser's own parser (TalksActivity.parseTalkList),
// diffs against the seen set in Prefs (autoDlKnown67), and
// enqueues only talks that appeared AFTER the first (seed) check
// — at most 5 per sayadaw, 20 per run — via DownloadManager with
// NETWORK_WIFI and the chooser's destination naming + dlMap write,
// so a just-enqueued file is never missed and never doubled.
// The job never spends mobile data: the scheduler constraint is
// NETWORK_TYPE_UNMETERED AND the run re-checks Wi-Fi itself
// (Fx.stripIsWifi, the VPN-aware rule) before fetching anything.
// One bad sayadaw page never kills the run (per-sayadaw
// try/catch). No notification, no toast — the last-check status
// lives only on the Settings screen. No new UI anywhere else.
public class AutoDownloadJob extends JobService {
    static final int JOB_ID_AUTO_DL68 = 6801;
    static final int JOB_ID_AUTO_DL_SEED68 = 6802;
    private static final long PERIOD_MS68 = 6L * 60L * 60L * 1000L;
    private static final long SEED_DEADLINE_MS68 = 60L * 1000L;

    // v12.75: in-process run guard — the periodic job (6801) and the
    // one-off seed job (6802) are different ids and can fire at the
    // same time; without this, two runs can sit inside
    // checkSayadaw68 for the same sayadaw at once, both diff against
    // a known set that predates either run's write, and enqueue the
    // same new talk twice (two DownloadManager jobs, one destination
    // file). A second run that finds one already in flight simply
    // finishes at once — the periodic job re-fires on its own rhythm
    // and a skipped seed is covered by it (the next run seeds the
    // page instead). Static because JobScheduler may build a fresh
    // AutoDownloadJob instance per job.
    private static boolean sRunInFlight68 = false;

    // v13.20: system-stop flag — onStopJob fires when the job's
    // constraints are lost (Wi-Fi gone), but returning false
    // never reached the worker thread, so the run kept fetching
    // every remaining sayadaw page over mobile data. Static
    // like the run guard (a fresh instance may serve the job);
    // cleared when a run ends, never at its start, so a stop
    // that lands before the worker thread starts is honoured
    // too. The loop checks it beside the toggle + Wi-Fi
    // re-checks below.
    private static volatile boolean sStopRequested68 = false;

    // v12.75: schedule-once — if the periodic job is already
    // pending, leave it alone. AOSP replaces a same-id job on
    // re-schedule and re-anchors its 6h window at now, so the old
    // "idempotent overwrite" really RESTARTED the window on every
    // Main launch: a user who opens the app at least once every 6h
    // never got a periodic check at all. The first schedule stands
    // and the 6h rhythm is kept; toggle-ON and reset-all inherit
    // the guard through schedule(). A disabled toggle schedules
    // nothing (an existing job is left alone — the toggle-off path
    // cancels it explicitly).
    public static void schedule(Context c) {
        try {
            if (c == null || !Prefs.autoDlEnabled(c)) return;
            JobScheduler js = (JobScheduler)
                    c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (js.getPendingJob(JOB_ID_AUTO_DL68) != null) return;
            JobInfo info = new JobInfo.Builder(JOB_ID_AUTO_DL68,
                    new ComponentName(c, AutoDownloadJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPeriodic(PERIOD_MS68)
                    .setPersisted(true)
                    .build();
            js.schedule(info);
        } catch (Exception ignored) {}
    }

    // First check soon after the toggle is flipped on: the periodic
    // job (same id, scheduled above) keeps the 6h rhythm, and a
    // separate one-off job runs the seed check within ~1 minute.
    // A one-off job MUST carry its own id — reusing 6801 would
    // overwrite the periodic schedule. The override deadline can
    // fire the job even off Wi-Fi (that is what "override" means),
    // so the deadline is only a prompt: the run itself re-checks
    // Wi-Fi before fetching, and a mobile-data firing simply does
    // nothing — no download can ever escape the re-check.
    public static void scheduleSoon(Context c) {
        try {
            schedule(c);
            if (c == null || !Prefs.autoDlEnabled(c)) return;
            JobScheduler js = (JobScheduler)
                    c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo info = new JobInfo.Builder(JOB_ID_AUTO_DL_SEED68,
                    new ComponentName(c, AutoDownloadJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setMinimumLatency(0)
                    .setOverrideDeadline(SEED_DEADLINE_MS68)
                    .build();
            js.schedule(info);
        } catch (Exception ignored) {}
    }

    public static void cancel(Context c) {
        try {
            if (c == null) return;
            JobScheduler js = (JobScheduler)
                    c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            js.cancel(JOB_ID_AUTO_DL68);
            js.cancel(JOB_ID_AUTO_DL_SEED68);
        } catch (Exception ignored) {}
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (!Prefs.autoDlEnabled(this)) {
            jobFinished(params, false);
            return false;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try { doWork68(); }
                catch (Exception ignored) {}
                finally { jobFinished(params, false); }
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // v13.20: signal the worker thread to end — returning
        // false alone never reached it, so a run whose
        // constraints were lost kept fetching pages. The loop
        // below checks this flag beside the toggle + Wi-Fi
        // re-checks and breaks out, still writing the last-run
        // stamp with what the partial run downloaded.
        // Never reschedule: the periodic job re-fires on its own
        // rhythm, and a stopped one-off seed is covered by it.
        sStopRequested68 = true;
        return false;
    }

    private void doWork68() {
        synchronized (AutoDownloadJob.class) {
            if (sRunInFlight68) return;
            sRunInFlight68 = true;
        }
        try {
            doWorkInner68();
        } finally {
            synchronized (AutoDownloadJob.class) {
                sRunInFlight68 = false;
                sStopRequested68 = false; // v13.20: next run starts clean
            }
        }
    }

    private void doWorkInner68() {
        if (!Prefs.autoDlEnabled(this)) return;
        // The hard Wi-Fi guard — the scheduler constraint alone is
        // not trusted (the seed job's override deadline can fire it
        // off Wi-Fi): nothing is fetched or enqueued unless the
        // phone is on Wi-Fi right now.
        if (!Fx.stripIsWifi(this)) return;
        java.util.Set<String> favs = Prefs.favNames(this);
        long now = System.currentTimeMillis();
        if (favs == null || favs.isEmpty()) {
            Prefs.autoDlSetLastRun(this, now, 0);
            return;
        }
        // Stable order, so the 20-per-run cap always favours the
        // same sayadaws instead of HashSet order.
        java.util.List<String> names =
                new java.util.ArrayList<String>(favs);
        java.util.Collections.sort(names);
        // v13.29 (A9): rotate the start — the old code always began
        // at the alphabetically-first favourite, so once the first
        // few filled the 20-per-run cap the tail favourites were
        // never checked, run after run. Each run now resumes after
        // the last favourite the previous run checked (wrap-around),
        // so every favourite gets its turn within a few runs. The
        // order inside a run stays alphabetical and the caps are
        // unchanged; seeding is unaffected (a seed returns 0, so
        // the loop still visits every name). The resume point is
        // persisted even on an early break (cap / toggle-off /
        // Wi-Fi loss / system stop) — the next run continues after
        // the last name actually checked, never restarts from the
        // top.
        int start68 = startIndex68(names, Prefs.autoDlResumeAfter(this));
        String lastChecked68 = null;
        java.util.Set<String> liveSources = liveDlSources68();
        java.util.Set<String> queued = readDlQueued68();
        pruneStaleDlQueued68(liveSources, queued);
        int totalNew = 0;
        for (int k = 0; k < names.size(); k++) {
            String name = names.get((start68 + k) % names.size());
            if (totalNew >= Prefs.AUTO_DL_MAX_TOTAL) break;
            // v12.75: re-check the toggle every sayadaw — flipping
            // it OFF mid-run used to leave the thread fetching and
            // enqueueing the remaining favourites even though the
            // user had just switched auto-download off. Breaking out
            // still writes the last-run stamp below with what this
            // partial run actually downloaded.
            if (!Prefs.autoDlEnabled(this)) break;
            // v13.20: same re-check for Wi-Fi + the system stop
            // flag — the run-start guard alone let a mid-run
            // Wi-Fi loss ride the remaining page fetches on
            // mobile data (enqueues were always safe: the
            // DownloadManager request is NETWORK_WIFI, so the
            // leak was HTML pages only), and a stop for lost
            // constraints never ended the run either.
            if (sStopRequested68 || !Fx.stripIsWifi(this)) break;
            lastChecked68 = name;
            try {
                totalNew += checkSayadaw68(name,
                        Prefs.AUTO_DL_MAX_TOTAL - totalNew,
                        liveSources, queued);
            } catch (Exception ignored) {}
        }
        if (lastChecked68 != null) Prefs.autoDlSetResumeAfter(this, lastChecked68);
        Prefs.autoDlSetLastRun(this, System.currentTimeMillis(), totalNew);
    }

    // v13.29 (A9): first index whose name sorts AFTER `after` —
    // the next run starts there, wrapping to 0 when `after` is
    // the last name (or was removed and nothing sorts after it).
    private static int startIndex68(java.util.List<String> names, String after) {
        if (after == null || after.length() == 0) return 0;
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).compareTo(after) > 0) return i;
        }
        return 0;
    }

    // One favourite sayadaw: fetch + parse, seed or diff, enqueue.
    // Returns how many new talks were enqueued (0 on a seed run,
    // a failed fetch, or no new talks). Never throws — the caller
    // catches per sayadaw anyway.
    private int checkSayadaw68(String name, int budget,
            java.util.Set<String> liveSources,
            java.util.Set<String> queued) {
        if (name == null || name.length() == 0 || budget <= 0) return 0;
        String page = Prefs.pageUrlForSayadaw(this, name);
        if (page.length() == 0) return 0;
        String html = fetchPageHtml68(page);
        if (html == null) return 0;
        java.util.List<TalksActivity.Talk> talks;
        try {
            talks = TalksActivity.parseTalkList(page, html);
        } catch (Exception ignored) { return 0; }
        if (talks == null) return 0;
        java.util.List<String> fresh = new java.util.ArrayList<String>();
        java.util.Map<String, String> titles =
                new java.util.HashMap<String, String>();
        for (TalksActivity.Talk t : talks) {
            if (t == null || t.url == null || t.url.length() == 0) continue;
            fresh.add(t.url);
            if (!titles.containsKey(t.url))
                titles.put(t.url, t.title == null ? "" : t.title);
        }
        // v12.76: an empty fresh list must NOT seed — a 200 page
        // with no mp3 links is an error / maintenance page (or a
        // genuinely empty one), not an observed catalogue: seeding
        // it marks the sayadaw done with zero known talks, so the
        // next run diffs the whole real catalogue against an empty
        // known set and downloads the back catalogue 5 per run.
        // Returning without seeding leaves the next run to seed
        // from the real catalogue instead; a genuinely empty page
        // just re-checks (fetch only) each run until a talk appears.
        if (fresh.isEmpty()) return 0;
        // Seed run: record everything seen, enqueue nothing.
        if (!Prefs.autoDlIsSeeded(this, page)) {
            Prefs.autoDlAddKnown(this, page, fresh, fresh); // catalogue == the seeded list
            Prefs.autoDlMarkSeeded(this, page);
            return 0;
        }
        java.util.Set<String> known = Prefs.autoDlKnownFor(this, page);
        java.util.Set<String> skip = new java.util.HashSet<String>();
        for (String u : fresh) {
            if (isDownloadedOrQueued68(u, liveSources, queued)) skip.add(u);
        }
        java.util.List<String> toDl =
                Prefs.autoDlDiffPure(fresh, known, skip);
        if (toDl.size() > budget) toDl = toDl.subList(0, budget);
        // Only what actually landed becomes known: the enqueued
        // talks, plus the ones skipped as already downloaded. A
        // talk cut off by the per-sayadaw / total cap, or one whose
        // enqueue just failed, stays UNKNOWN, so the next run picks
        // it up — the cap is a rate limit, not a "first 5 only"
        // filter, and a failed enqueue must be retried, never
        // forgotten.
        java.util.List<String> mark = new java.util.ArrayList<String>();
        for (String u : fresh) if (skip.contains(u)) mark.add(u);
        int n = 0;
        for (String u : toDl) {
            if (enqueueTalk68(u, titles.get(u), name)) {
                n++;
                mark.add(u);
                queued.add(u);
                liveSources.add(u);
            }
        }
        // v12.77: pass the full fresh list as the catalogue, so
        // addKnown prunes known URLs no longer on the page (dead
        // lines stop eating cap room and evicting live history).
        // Only `mark` becomes known — capped-out / failed talks
        // stay unknown, exactly as before.
        Prefs.autoDlAddKnown(this, page, mark, fresh);
        return n;
    }

    // Fetch a sayadaw page — the chooser's own fetch shape
    // (TalksActivity.loadTalks): same timeouts, same UA, same
    // charset re-decode. Null on any failure; the caller skips
    // that sayadaw.
    private String fetchPageHtml68(String pageUrl) {
        java.net.HttpURLConnection conn = null;
        java.io.InputStream in = null;
        try {
            conn = (java.net.HttpURLConnection)
                    new java.net.URL(pageUrl).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            in = conn.getInputStream();
            java.io.ByteArrayOutputStream baos =
                    new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
            byte[] bytes = baos.toByteArray();
            String html = new String(bytes, "UTF-8");
            java.util.regex.Matcher cm = java.util.regex.Pattern.compile(
                    "charset=[\"']?([A-Za-z0-9_-]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
            if (cm.find()) {
                try { html = new String(bytes, cm.group(1)); }
                catch (Exception ignored) {}
            }
            return html;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (in != null) { try { in.close(); } catch (Exception ignored) {} }
            if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
        }
    }

    // Enqueue one talk on Wi-Fi only — the chooser's own enqueue
    // shape (TalksActivity :1868 + recordTalkDownload): same
    // NETWORK_WIFI, same Downloads/Dhamma destination naming, and
    // the dlMap/dlSayadaw/dlQueued writes AFTER dm.enqueue, so a
    // just-enqueued file can never be missed by the next check.
    private boolean enqueueTalk68(String url, String title, String sayadawName) {
        if (url == null || url.length() == 0) return false;
        String file = downloadFileNameFor68(url);
        try {
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    getSystemService(Context.DOWNLOAD_SERVICE);
            android.app.DownloadManager.Request req =
                    new android.app.DownloadManager.Request(
                            android.net.Uri.parse(url));
            req.setTitle(title != null && title.length() > 0 ? title : url);
            req.setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setAllowedNetworkTypes(
                    android.app.DownloadManager.Request.NETWORK_WIFI);
            req.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                    "Dhamma/" + file);
            dm.enqueue(req);
            recordDownload68(url, file, sayadawName);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void recordDownload68(String url, String file, String sayadawName) {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONObject map = new org.json.JSONObject(
                    sp.getString("dlMap", "{}"));
            map.put(url, file);
            sp.edit().putString("dlMap", map.toString()).apply();
            if (sayadawName != null && sayadawName.length() > 0) {
                try {
                    org.json.JSONObject sm = new org.json.JSONObject(
                            sp.getString("dlSayadaw", "{}"));
                    sm.put(url, sayadawName);
                    sp.edit().putString("dlSayadaw", sm.toString()).apply();
                } catch (Exception ignored) {}
            }
            org.json.JSONArray queued = new org.json.JSONArray(
                    sp.getString("dlQueued", "[]"));
            boolean found = false;
            for (int i = 0; i < queued.length(); i++) {
                if (url.equals(queued.optString(i))) { found = true; break; }
            }
            if (!found) queued.put(url);
            sp.edit().putString("dlQueued", queued.toString()).apply();
        } catch (Exception ignored) {}
    }

    // Is this talk already on the phone (file exists) or already
    // on its way (a live DownloadManager job, or a dlQueued mark)?
    // Mirrors TalksActivity.isTalkDownloaded + talkIsDlQueued: the
    // mapped name wins, else any candidate basename in
    // Downloads/Dhamma counts unless another URL's download claims
    // that name.
    private boolean isDownloadedOrQueued68(String url,
            java.util.Set<String> liveSources,
            java.util.Set<String> queued) {
        if (url == null) return false;
        try {
            if (liveSources != null && liveSources.contains(url)) return true;
            if (queued != null && queued.contains(url)) return true;
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS),
                    "Dhamma");
            String mapped = mappedFileName68(url);
            if (mapped != null && new java.io.File(dir, mapped).exists())
                return true;
            for (String name : fileNameCandidates68(url)) {
                if (fileClaimedByOther68(name, url)) continue;
                if (new java.io.File(dir, name).exists()) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // Candidate basenames for a URL — the chooser's isTalkDownloaded
    // set: raw basename, query/fragment-stripped, %XX-decoded, and
    // the stripped + ".mp3" enqueue-destination form (v12.16).
    static java.util.List<String> fileNameCandidates68(String url) {
        java.util.List<String> cands = new java.util.ArrayList<String>();
        String base = url.substring(url.lastIndexOf('/') + 1);
        String stripped = base;
        int q = stripped.indexOf('?');
        if (q >= 0) stripped = stripped.substring(0, q);
        int h = stripped.indexOf('#');
        if (h >= 0) stripped = stripped.substring(0, h);
        String decoded = stripped;
        try {
            decoded = java.net.URLDecoder.decode(
                    stripped.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {}
        cands.add(base);
        if (!stripped.equals(base)) cands.add(stripped);
        if (!decoded.equals(stripped) && !decoded.equals(base))
            cands.add(decoded);
        if (stripped.length() > 0
                && !stripped.toLowerCase().endsWith(".mp3"))
            cands.add(stripped + ".mp3");
        return cands;
    }

    private String mappedFileName68(String url) {
        try {
            org.json.JSONObject map = new org.json.JSONObject(
                    getSharedPreferences("dhamma", MODE_PRIVATE)
                            .getString("dlMap", "{}"));
            if (map.has(url)) return map.getString(url);
        } catch (Exception ignored) {}
        return null;
    }

    private boolean fileClaimedByOther68(String filename, String url) {
        try {
            org.json.JSONObject map = new org.json.JSONObject(
                    getSharedPreferences("dhamma", MODE_PRIVATE)
                            .getString("dlMap", "{}"));
            java.util.Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!k.equals(url) && filename.equals(map.optString(k, "")))
                    return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // Filename for a NEW download — the chooser's
    // talkDownloadFileNameFor rule in job form: the mapped name
    // wins, else the basename stripped of ?query / #fragment with
    // .mp3 guaranteed, and a basename another URL's download
    // claims takes the 8-hex MD5 suffix (v8.40 claim-alone rule)
    // instead of overwriting that talk's file.
    static String downloadFileNameFor68(String url,
            java.util.Map<String, String> dlMap) {
        String mapped = dlMap == null ? null : dlMap.get(url);
        if (mapped != null) return mapped;
        String base = url.substring(url.lastIndexOf('/') + 1);
        int q = base.indexOf('?');
        if (q >= 0) base = base.substring(0, q);
        int hh = base.indexOf('#');
        if (hh >= 0) base = base.substring(0, hh);
        if (!base.toLowerCase().endsWith(".mp3")) base = base + ".mp3";
        if (dlMap != null) {
            for (java.util.Map.Entry<String, String> e : dlMap.entrySet()) {
                if (!e.getKey().equals(url)
                        && base.equals(e.getValue())) {
                    String hash = "";
                    try {
                        java.security.MessageDigest md =
                                java.security.MessageDigest.getInstance("MD5");
                        byte[] d = md.digest(url.getBytes("UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 4; i++)
                            sb.append(String.format("%02x", d[i]));
                        hash = sb.toString();
                    } catch (Exception ignored) {
                        hash = String.format("%08x", url.hashCode());
                    }
                    if (base.toLowerCase().endsWith(".mp3"))
                        return base.substring(0, base.length() - 4)
                                + "_" + hash + base.substring(base.length() - 4);
                    return base + "_" + hash;
                }
            }
        }
        return base;
    }

    private String downloadFileNameFor68(String url) {
        java.util.Map<String, String> dlMap =
                new java.util.HashMap<String, String>();
        try {
            org.json.JSONObject map = new org.json.JSONObject(
                    getSharedPreferences("dhamma", MODE_PRIVATE)
                            .getString("dlMap", "{}"));
            java.util.Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                dlMap.put(k, map.optString(k, ""));
            }
        } catch (Exception ignored) {}
        return downloadFileNameFor68(url, dlMap);
    }

    // Source URLs of live DownloadManager jobs (running / paused /
    // pending) — one query per run, shared by the skip check and
    // the stale-queue prune below.
    private java.util.Set<String> liveDlSources68() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        try {
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    getSystemService(Context.DOWNLOAD_SERVICE);
            android.database.Cursor c;
            try {
                c = dm.query(new android.app.DownloadManager.Query());
            } catch (Exception ignored) {
                return out;
            }
            if (c == null) return out;
            try {
                int statusCol = c.getColumnIndex(
                        android.app.DownloadManager.COLUMN_STATUS);
                int srcCol = c.getColumnIndex(
                        android.app.DownloadManager.COLUMN_URI);
                while (c.moveToNext()) {
                    int st = statusCol >= 0 ? c.getInt(statusCol) : -1;
                    if (st != android.app.DownloadManager.STATUS_RUNNING
                            && st != android.app.DownloadManager.STATUS_PAUSED
                            && st != android.app.DownloadManager.STATUS_PENDING)
                        continue;
                    if (srcCol >= 0) {
                        String src = c.getString(srcCol);
                        if (src != null && src.length() > 0) out.add(src);
                    }
                }
            } finally { c.close(); }
        } catch (Exception ignored) {}
        return out;
    }

    private java.util.Set<String> readDlQueued68() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        try {
            org.json.JSONArray qa = new org.json.JSONArray(
                    getSharedPreferences("dhamma", MODE_PRIVATE)
                            .getString("dlQueued", "[]"));
            for (int i = 0; i < qa.length(); i++) {
                String u = qa.optString(i);
                if (u != null && u.length() > 0) out.add(u);
            }
        } catch (Exception ignored) {}
        return out;
    }

    // Drop dlQueued marks with no live job behind them — the
    // Talks/Main/Settings prune shape, run here so a failed job's
    // stale mark can never block that talk's auto-download forever.
    // Only writes when something drops.
    private void pruneStaleDlQueued68(java.util.Set<String> liveSources,
            java.util.Set<String> queued) {
        try {
            if (queued == null || queued.isEmpty()) return;
            boolean changed = false;
            java.util.Iterator<String> it = queued.iterator();
            while (it.hasNext()) {
                String u = it.next();
                // A live job keeps its mark; anything else is stale
                // — a completed download's mark is dropped too, like
                // the Talks/Main/Settings prune (a finished file is
                // still skipped by the file-exists leg, never by a
                // lingering queue mark).
                if (liveSources != null && liveSources.contains(u)) continue;
                it.remove();
                changed = true;
            }
            if (changed) {
                org.json.JSONArray out = new org.json.JSONArray();
                for (String u : queued) out.put(u);
                getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                        .putString("dlQueued", out.toString()).apply();
            }
        } catch (Exception ignored) {}
    }
}
