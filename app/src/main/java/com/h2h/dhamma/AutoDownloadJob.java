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
// (Fx.stripIsWifi, the VPN-aware rule) before fetching anything,
// and the fetch itself is bound to the Wi-Fi network (v14.10,
// audit-1367 L2), so a Wi-Fi loss mid-fetch fails the fetch
// instead of finishing it over mobile data.
// One bad sayadaw page never kills the run (per-sayadaw
// try/catch). No in-app toast — the last-check status lives only on
// the Settings screen; the system still posts one DownloadManager
// "Download complete" notification per landed talk, because the
// request uses VISIBILITY_VISIBLE_NOTIFY_COMPLETED (v13.71,
// audit-1367 F6: the old "No notification" line was wrong about
// that. VISIBILITY_HIDDEN would need the DOWNLOAD_WITHOUT_NOTIFICATION
// permission, which the manifest does not declare, so the flag stays
// and the comment now says so). No new UI anywhere else.
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

    // v14.13 (audit-1367 L6): the connection the worker thread is
    // currently reading from, so onStopJob can end a blocked fetch
    // at once instead of waiting out its 30s read timeout (see
    // onStopJob). Volatile: written by the worker, read by the
    // main thread. Only ever one worker thread (sRunInFlight68),
    // so the field can only name this run's fetch; cleared in
    // fetchPageHtml68's finally, and only when it still names
    // that fetch, so a stale reference can never disconnect a
    // later run's connection.
    private static volatile java.net.HttpURLConnection sActiveFetch68 = null;

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
    // v14.11 (audit-1367 L3): the guard now compares the pending
    // job's interval first. A persisted job survives app updates,
    // so if a future version ever changed PERIOD_MS68, the old
    // schedule-once return would keep the OLD rhythm forever —
    // the new period would never take effect on an updating
    // device. Only a period mismatch cancels + rebuilds; a
    // matching interval still leaves the job (and its window)
    // untouched, so the v12.75 behaviour is unchanged in the
    // common case. getIntervalMillis() is API 24+ (minSdk 24)
    // and returns 0 for a non-periodic job, which also mismatches
    // and rebuilds — the fail-safe direction.
    public static void schedule(Context c) {
        try {
            if (c == null || !Prefs.autoDlEnabled(c)) return;
            JobScheduler js = (JobScheduler)
                    c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo pending = js.getPendingJob(JOB_ID_AUTO_DL68);
            if (pending != null) {
                if (pending.getIntervalMillis() == PERIOD_MS68) return;
                js.cancel(JOB_ID_AUTO_DL68);
            }
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
                    // v13.71 (audit-1367 F5): the seed survives a reboot
                    // now — without setPersisted the one-off seed vanished
                    // on a reboot inside its ~1-minute window (or a process
                    // death mid-seed-run) and the next periodic run, up to
                    // 6h later, was the only cover. RECEIVE_BOOT_COMPLETED
                    // is already declared for the persisted periodic job.
                    .setPersisted(true)
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
        // v14.13 (audit-1367 L6): end a blocked fetch at once —
        // the worker can be sitting in in.read() with a 30s
        // timeout, and the flag alone only reaches it between
        // sayadaws. disconnect() from this thread makes the
        // read throw, the fetch returns null, the sayadaw is
        // skipped, and the loop breaks on the flag — so
        // jobFinished lands promptly instead of up to 30s
        // later. Null (no fetch in flight) or already finished
        // (field cleared) is a no-op; a double disconnect is
        // harmless, and the fetch's own finally disconnects
        // again the same way.
        java.net.HttpURLConnection active68 = sActiveFetch68;
        if (active68 != null) {
            try { active68.disconnect(); } catch (Exception ignored) {}
        }
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
        // v14.21 (audit-1369 L1): expected total bytes per source URL, from
        // one bulk DownloadManager query (see expectedSizes68) -- the
        // scan-time write point for download integrity. Built once per run,
        // next to liveDlSources68; the two predicates below read it, never
        // query. A truncated partial from a dead job then reads as NOT
        // downloaded, so the next run re-offers it instead of skipping it
        // (and marking it known) forever.
        java.util.Map<String, Long> expected68 = expectedSizes68();
        // v13.68 (audit-1369 F11): the snapshot read + the stale-prune
        // write-back are ONE critical section - a UI prune dropping B
        // between the read and the write-back used to be resurrected
        // by the prune's stale-snapshot write, and a UI record landing
        // between them was lost the same way. pruneStaleDlQueued68
        // re-enters the same lock below (synchronized is re-entrant).
        java.util.Set<String> queued;
        synchronized (DlLock.LOCK) {
            queued = readDlQueued68();
            pruneStaleDlQueued68(liveSources, queued);
        }
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
            // v14.08 (audit-1367 L1): refresh the queue snapshot per
            // sayadaw — the run snapshots dlQueued once at the top, so
            // a foreground Talks/Room/strip enqueue landing after the
            // snapshot but before this sayadaw's check was invisible
            // to the job, and the job could enqueue the same talk a
            // second time (one duplicate DownloadManager job for the
            // same destination file). The Room gate's already-queued
            // check reads dlQueued only (roomIsDlQueued + the gate's
            // qa73 read), so the window is real for the Room path;
            // the Talks path was already shielded by the file-exists
            // leg. Re-reading fresh under DlLock and MERGING
            // (add-only) can only add skips: a mark pruned elsewhere
            // since the snapshot stays in the set for this run, so
            // the worst case is a one-run delay before that talk is
            // re-offered, never a lost download. The job's own
            // enqueues below keep adding to the same set, so the
            // merge never drops one.
            synchronized (DlLock.LOCK) {
                queued.addAll(readDlQueued68());
            }
            lastChecked68 = name;
            try {
                totalNew += checkSayadaw68(name,
                        Prefs.AUTO_DL_MAX_TOTAL - totalNew,
                        liveSources, queued, expected68);
            } catch (Exception ignored) {}
            // v14.13 (audit-1367 L8): persist the resume point per
            // sayadaw, not only at run end — a process death
            // mid-run used to lose the whole run's rotation
            // progress, so the next run re-fetched every sayadaw
            // already checked (cheap pure diffs, but wasted page
            // fetches). One tiny apply per sayadaw; the end-of-run
            // write below stays as the final word, same value.
            // The name counts as checked exactly as before
            // (lastChecked68 is assigned before the call), so a
            // sayadaw whose check threw is still recorded.
            Prefs.autoDlSetResumeAfter(this, name);
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
            java.util.Set<String> queued,
            java.util.Map<String, Long> expected68) {
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
            if (isDownloadedOrQueued68(u, liveSources, queued, expected68)) skip.add(u);
        }
        // v13.71 (audit-1367 F2 repair): un-know the talks a previous
        // run stranded — known, still on the page, never landed (not
        // in skip), but with a dlMap entry proving an enqueue once
        // happened. The old rule marked a talk known the moment
        // dm.enqueue() succeeded, so a download that then failed (404,
        // server drop, storage) stayed known forever: the prune drops
        // its queue mark, the file never lands, and the diff below
        // still excludes it via `known`. Dropping those URLs here
        // re-offers them this run. Seeded-only talks have no dlMap
        // entry and are untouched; a talk deleted in-app has none
        // either (delete prunes dlMap), so an intentional delete is
        // never re-offered.
        java.util.Set<String> dlMapKeys = dlMapKeys68();
        java.util.List<String> stuck = new java.util.ArrayList<String>();
        for (String u : fresh) {
            if (known.contains(u) && !skip.contains(u)
                    && dlMapKeys.contains(u)) stuck.add(u);
        }
        if (!stuck.isEmpty()) {
            Prefs.autoDlDropKnown(this, page, stuck);
            known.removeAll(stuck);
        }
        java.util.List<String> toDl =
                Prefs.autoDlDiffPure(fresh, known, skip);
        if (toDl.size() > budget) toDl = toDl.subList(0, budget);
        // v13.71 (audit-1367 F2): only what has verifiably LANDED
        // becomes known now. `skip` mixes landed with in-flight (a
        // live job or a queue mark), and the old code marked every
        // skipped URL known — plus every URL the moment dm.enqueue()
        // succeeded — so a download that failed after enqueue stayed
        // known forever (see the repair above). Landed is a non-zero
        // file on disk with no live job and no queue mark behind it:
        // a partial file still downloading is already non-zero, so
        // the live/queued exclusion is what keeps a mid-download
        // file from reading as landed. An enqueued talk stays unknown
        // until a later run sees it landed; a failed one is simply
        // not in skip and not known, so the next run re-offers it.
        // A talk cut off by the per-sayadaw / total cap, or one whose
        // enqueue just failed, stays UNKNOWN, so the next run picks
        // it up — the cap is a rate limit, not a "first 5 only"
        // filter, and a failed enqueue must be retried, never
        // forgotten.
        java.util.List<String> mark = new java.util.ArrayList<String>();
        for (String u : fresh) if (isLandedFile68(u, liveSources, queued, expected68)) mark.add(u);
        int n = 0;
        for (String u : toDl) {
            if (enqueueTalk68(u, titles.get(u), name)) {
                n++;
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
    // v14.10 (audit-1367 L2): the socket is BOUND to the Wi-Fi
    // network (Fx.stripWifiNetwork + Network.openConnection),
    // not opened on the default route. The pre-fetch re-check
    // (:241) only guarded the fetch START — once a plain
    // connection was open, a Wi-Fi loss mid-fetch let the socket
    // finish over mobile data (one small HTML page; the v13.20
    // comment already owned that leak). A bound socket dies
    // with its network instead: Wi-Fi gone mid-fetch fails the
    // fetch (null here, sayadaw skipped this run, retried next
    // run) and no byte ever rides mobile data. Null network =
    // no fetch at all — fail-closed, same as the re-check.
    private String fetchPageHtml68(String pageUrl) {
        java.net.HttpURLConnection conn = null;
        java.io.InputStream in = null;
        try {
            android.net.Network wifi = Fx.stripWifiNetwork(this);
            if (wifi == null) return null;
            conn = (java.net.HttpURLConnection)
                    wifi.openConnection(new java.net.URL(pageUrl));
            sActiveFetch68 = conn; // v14.13 (audit-1367 L6): onStopJob disconnects this
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
            if (sActiveFetch68 == conn) sActiveFetch68 = null; // v14.13 (audit-1367 L6)
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
        // v13.68 (audit-1369 F11): dlMap/dlSayadaw/dlQueued read-modify-write - serialize on DlLock.LOCK (see DlLock).
        synchronized (DlLock.LOCK) {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONObject map = new org.json.JSONObject(
                    sp.getString("dlMap", "{}"));
            map.put(url, file);
            String sayStr70 = null;
            if (sayadawName != null && sayadawName.length() > 0) {
                try {
                    org.json.JSONObject sm = new org.json.JSONObject(
                            sp.getString("dlSayadaw", "{}"));
                    sm.put(url, sayadawName);
                    sayStr70 = sm.toString();
                } catch (Exception ignored) {}
            }
            org.json.JSONArray queued = new org.json.JSONArray(
                    sp.getString("dlQueued", "[]"));
            boolean found = false;
            for (int i = 0; i < queued.length(); i++) {
                if (url.equals(queued.optString(i))) { found = true; break; }
            }
            if (!found) queued.put(url);
            // v13.65 (audit-1370 C4): dlMap + dlQueued land in ONE apply() —
            // the Fx.stripEnqueueDownload shape. Three separate applies used
            // to tear: a death between them left the hourglass mark without
            // its filename (or the filename without its mark), and the two
            // disagreed until a prune.
            // v13.70 (audit-1369 F10): dlSayadaw joins that same apply now -
            // the C4 pass left it in its own apply, so a death between the
            // two still tore the record (filename + mark without its
            // sayadaw; invisible, every reader falls back, but torn all
            // the same). The sayadaw string is built first, in its own
            // try exactly as before, so a corrupt dlSayadaw JSON still
            // cannot block the record.
            android.content.SharedPreferences.Editor ed70 = sp.edit()
                    .putString("dlMap", map.toString())
                    .putString("dlQueued", queued.toString());
            if (sayStr70 != null) ed70.putString("dlSayadaw", sayStr70);
            ed70.apply();
        } catch (Exception ignored) {}
        }
    }

    // Is this talk already on the phone (file exists) or already
    // on its way (a live DownloadManager job, or a dlQueued mark)?
    // Mirrors TalksActivity.isTalkDownloaded + talkIsDlQueued: the
    // mapped name wins, else any candidate basename in
    // Downloads/Dhamma counts unless another URL's download claims
    // that name.
    private boolean isDownloadedOrQueued68(String url,
            java.util.Set<String> liveSources,
            java.util.Set<String> queued,
            java.util.Map<String, Long> expected68) {
        if (url == null) return false;
        try {
            if (liveSources != null && liveSources.contains(url)) return true;
            if (queued != null && queued.contains(url)) return true;
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS),
                    "Dhamma");
            String mapped = mappedFileName68(url);
            // v13.66 (audit-1369 F8 length-gate pass): a 0-byte file is not
            // downloaded — the old exists-only check skipped the URL on every
            // later auto-download run, so a failed download that left a dead
            // file was NEVER re-downloaded. This is the one 0-byte site that
            // blocked recovery; with the gate the next run re-enqueues it.
            // v14.21 (audit-1369 L1): completeness on top of the length gate --
            // a non-empty file shorter than its expected total is a truncated
            // partial, not a download; isCompleteFile68 fails open when the
            // total is unknown, so today’s behaviour is kept there.
            if (mapped != null && isCompleteFile68(new java.io.File(dir, mapped), url, expected68))
                return true;
            // v13.66: same length gate as the mapped branch above (audit-1369 F8).
            for (String name : fileNameCandidates68(url)) {
                if (fileClaimedByOther68(name, url)) continue;
                if (isCompleteFile68(new java.io.File(dir, name), url, expected68)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // v13.71 (audit-1367 F2): has this talk verifiably landed — a
    // non-zero file on disk with no live DownloadManager job and no
    // queue mark behind it? The file legs mirror
    // isDownloadedOrQueued68 (mapped name wins, then unclaimed
    // candidates, both length-gated v13.66 F8); the live/queued
    // exclusion is what separates a finished file from a partial one
    // still downloading (a partial is already non-zero). A URL that
    // only ever seeded is never landed here — it stays known from
    // the seed, untouched. v14.21 (audit-1369 L1): the file legs also
    // require completeness now -- a truncated partial is not landed,
    // so it is neither marked known nor skipped, and the next run
    // re-offers it.
    private boolean isLandedFile68(String url,
            java.util.Set<String> liveSources,
            java.util.Set<String> queued,
            java.util.Map<String, Long> expected68) {
        if (url == null) return false;
        try {
            if (liveSources != null && liveSources.contains(url)) return false;
            if (queued != null && queued.contains(url)) return false;
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS),
                    "Dhamma");
            String mapped = mappedFileName68(url);
            if (mapped != null && isCompleteFile68(new java.io.File(dir, mapped), url, expected68))
                return true;
            for (String name : fileNameCandidates68(url)) {
                if (fileClaimedByOther68(name, url)) continue;
                if (isCompleteFile68(new java.io.File(dir, name), url, expected68)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // v13.71 (audit-1367 F2 repair): the URLs this app has ever
    // enqueued (dlMap keys), read once per sayadaw check — the
    // discriminator between "seeded, never enqueued" (no entry)
    // and "enqueued once, never landed" (entry, no file).
    private java.util.Set<String> dlMapKeys68() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        try {
            org.json.JSONObject map = new org.json.JSONObject(
                    getSharedPreferences("dhamma", MODE_PRIVATE)
                            .getString("dlMap", "{}"));
            java.util.Iterator<String> keys = map.keys();
            while (keys.hasNext()) out.add(keys.next());
        } catch (Exception ignored) {}
        return out;
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

    // v14.21 (audit-1369 L1): expected total bytes per source URL, from ONE
    // bulk DownloadManager query (no status filter) -- the scan-time write
    // point for download integrity, chosen over failure-receiver persist
    // (all five activity receivers are dynamic, so they never fire when the
    // app is dead) and over the three progress polls (live rows only, and
    // they never see the background job). Latest row per URL wins, by
    // COLUMN_ID: a re-download attempt supersedes the older row, and a
    // failed re-attempt can only have truncated the file the older row
    // completed. A row with an unknown total (chunked, -1) carries no
    // signal and is skipped, so the older known total stands; a URL with
    // no row at all is absent from the map. Any query failure returns an
    // empty map -- every consumer fails open to the length-only behaviour.
    // Built once per job run in doWorkInner68; the predicates read it,
    // never query. No prefs, no DlLock: a read-only DM query on the job
    // thread, next to liveDlSources68 which already pays its own
    // unfiltered query on the same thread.
    private java.util.Map<String, Long> expectedSizes68() {
        java.util.Map<String, Long> out = new java.util.HashMap<String, Long>();
        java.util.Map<String, Long> ids = new java.util.HashMap<String, Long>();
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
                int idCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_ID);
                int srcCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_URI);
                int totalCol = c.getColumnIndex(
                        android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                while (c.moveToNext()) {
                    if (srcCol < 0 || totalCol < 0) continue;
                    String src = c.getString(srcCol);
                    if (src == null || src.length() == 0) continue;
                    long total = c.getLong(totalCol);
                    if (total <= 0) continue;
                    long id = idCol >= 0 ? c.getLong(idCol) : -1;
                    Long prevId = ids.get(src);
                    if (prevId == null || id >= prevId) {
                        ids.put(src, id);
                        out.put(src, total);
                    }
                }
            } finally { c.close(); }
        } catch (Exception ignored) {}
        return out;
    }

    // v14.21 (audit-1369 L1): is this on-disk file complete, or a truncated
    // partial from a dead job? A non-empty file shorter than the expected
    // total of its latest DownloadManager row is a partial: not downloaded.
    // Unknown total (no row, chunked, row gone) fails open -- the length-only
    // behaviour. A file longer than expected also passes: only the short
    // case is a truncation signal. Static: the same test the Room/Talks/
    // library length gates will take when the L1 completeness pass reaches
    // them; no state, no prefs.
    private static boolean isCompleteFile68(java.io.File f, String url,
            java.util.Map<String, Long> expected) {
        if (f == null || !f.isFile() || f.length() <= 0) return false;
        if (expected == null) return true;
        Long total = expected.get(url);
        if (total == null || total.longValue() <= 0) return true;
        return f.length() >= total.longValue();
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
        // v13.68 (audit-1369 F11): dlMap/dlSayadaw/dlQueued read-modify-write - serialize on DlLock.LOCK (see DlLock).
        synchronized (DlLock.LOCK) {
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
}
