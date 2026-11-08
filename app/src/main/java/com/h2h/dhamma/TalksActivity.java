package com.h2h.dhamma;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// v1.51: this screen is ONLY a list for choosing a talk — nothing else.
// No play-all, no filters, no row buttons, no marks.
// Tapping a row opens that talk in the Buddha-altar Room player.
// (v4.93: a title search box joins the header — the one browse aid
// a long sayadaw list was missing; rows themselves stay tap /
// long-press-share / ⋮ queue-menu only.)
public class TalksActivity extends Activity {

    public static class Talk {
        String title, url;
        Talk(String t, String u) { title = t; url = u; }
    }

    private List<Talk> talks = new ArrayList<Talk>();
    private int lastAnimPos = -1;
    private TalkAdapter adapter;
    // v4.48: chooser queue marks — the Room drawer rows carry
    // their hand-queue position (◈N, v4.32) but the chooser rows
    // that build the queue (⋮ menu, v4.47) showed nothing, so a
    // listener browsing Talks could not tell queued talks from
    // unqueued ones without opening each menu. The cache below
    // mirrors the menu's own prefs read (same per-sayadaw key,
    // filtered to this list, deduped, order preserved); the
    // playing talk never shows a mark, mirroring the drawer.
    private java.util.ArrayList<String> talkQueue = new java.util.ArrayList<String>();
    // v12.04: snapshot of talkQueue as last rebuilt from prefs — the
    // Room's lastQueueUrls03 (v12.03), in chooser form: the removed
    // set for the funnel's adoption below is this snapshot minus the
    // list the caller is about to save, so an entry THIS window
    // removed (still in the saved read because the removal has not
    // persisted yet) is never re-adopted. Updated in
    // refreshQueueMarks, the one place talkQueue is rebuilt from
    // prefs (1s tick, list load, return).
    private java.util.ArrayList<String> lastSyncedTalkQueue04 = new java.util.ArrayList<String>();
    private String talkPlayingUrl = "";
    // v11.06: the sounding talk's stream URL when the session is a
    // downloaded-library one (lastKind lib) — the Room-session
    // talkPlayingUrl above stays empty for library sessions, so a
    // chooser row for the sounding file kept its stale ↺ resume
    // mark (its checkpoint spot, not its live position) while it
    // played. Resolved in refreshQueueMarks from lastLibPath via
    // the dlMap exactly-one-claimant rule (v8.44), never per bind,
    // so a dual-claimed file resolves to no URL rather than the
    // wrong talk's; used ONLY to suppress that row's ↺ mark, like
    // the playing row's own suppression — no ▶/gold change.
    private String talkPlayingLibUrl = "";
    // v5.77: pick-up/drop queue move, ported from the Room drawer's
    // v4.92 verb — the URL of the talk the listener picked up with
    // the ⋮ menu's "နေရာရွှေ့မယ်", waiting for the drop tap on
    // another queued row. v5.79: the arm now survives rotation
    // via onSaveInstanceState (validated against talkQueue on
    // restore, like every other use); every use re-validates
    // against talkQueue,
    // and the row-click interception below runs before openRoom,
    // so a drop never also plays the tapped talk. Unlike the Room,
    // there is no drawer close to cancel on — the arm survives a
    // Room visit (the queue re-syncs on return) and is validated
    // on every use instead, so a stale arm can never drop a ghost.
    private String moveHeldUrl = null;
    // v5.49: chooser row marks — the Room drawer rows carry ★/⬇/⏳/✓
    // (v1.44/v4.24) but the chooser rows showed only ▶/◈/↺, so a
    // listener browsing Talks could not spot a favourited, saved
    // or finished talk without opening it — even though this screen
    // already filters by ★ and ⬇. The caches below mirror the
    // drawer's in-memory sets: fav/played/queued-downloaded come
    // from prefs (cheap, refreshed on the tick path), while the
    // downloaded set needs file checks, so it scans once per list
    // load/return and at most every 30s on the tick (the Room's
    // rescan cadence) — never per bind, where a JSON parse + stat
    // per row would jank a fling. A scan that changes the set
    // rebinds at once, so a file landing while browsing flips
    // ⏳→⬇ without a return. Rows read the caches only, so a
    // bind stays prefs-free. Mark order mirrors the drawer:
    // ▶ + ◈ + ★ + ⬇/⏳ + ✓ + title + ↺; the playing row keeps
    // ★/⬇ like the drawer but never ✓ (its live position is on
    // the strip) and never ◈/↺ (existing rules, kept).
    private java.util.Set<String> talkFavCache = new java.util.HashSet<String>();
    private java.util.Set<String> talkPlayedCache = new java.util.HashSet<String>();
    private java.util.Set<String> talkDlCache = new java.util.HashSet<String>();
    // v5.59: the filename isTalkDownloaded matched per URL, filled
    // during the same scan that builds talkDlCache above — so the
    // ↺ hint can look a talk's spot up under its downloaded-file
    // form from the cache alone, never per bind, mirroring the
    // Room drawer's downloadedFiles set (v1.57 rule).
    private java.util.Map<String, String> talkDlFileCache = new java.util.HashMap<String, String>();
    private java.util.Set<String> talkDlQueuedCache = new java.util.HashSet<String>();
    private long talkDlCacheAt = 0;
    // v11.57: filenames owned by a live DownloadManager job (RUNNING/
    // PAUSED/PENDING) — the Talks mirror of the Room's v11.56
    // inProgressDlFiles and the library's v11.55 rule. DownloadManager
    // writes straight into Downloads/Dhamma, so a partial file used
    // to pass isTalkDownloaded's exists-check: the header gained a
    // false ⬇ count, the downloaded-only filter admitted a talk
    // still downloading, and worst, pruneTalkQueuedDownloads dropped
    // the live job's shared dlQueued entry early (the downloaded
    // check ran before the live-job guard), so the Room lost its ⏳
    // mark mid-download too. Refreshed on the same 30s gate as the
    // downloaded scan, forced at prune start, and gated at header
    // start; a failed query leaves the previous set (fail open, like
    // the Room/library rule, never an empty downloaded list).
    // v11.86: a NULL cursor is a failed query too — it used to fall
    // through to the unconditional assign below and store the empty
    // set, wiping the live-job names for 30s (false ⬇, inflated
    // header count, prune dropping the live ⏳).
    private java.util.Set<String> talkInProgressDlNames = new java.util.HashSet<String>();
    private long talkInProgressDlAt = 0;
    // v12.28: row-tap stack guard — uptime of the last tap-driven
    // openRoom; a second row tap inside 700ms is dropped.
    private long lastRowTapOpenMs = 0;
    private void talkRefreshInProgressDlNames(boolean force) {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            if (!force && now - talkInProgressDlAt < 30000) return;
            java.util.Set<String> out = new java.util.HashSet<String>();
            boolean cursorOk86 = false;
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
                // v12.85: filter in the provider, like the v11.76 strip scans — this runs
                // on the 30s gate the 1s strip tick drives and used to pull every download
                // on the main thread; the status check below stays as a guard.
                android.database.Cursor c = dm.query(new android.app.DownloadManager.Query()
                        .setFilterByStatus(android.app.DownloadManager.STATUS_RUNNING
                                | android.app.DownloadManager.STATUS_PAUSED
                                | android.app.DownloadManager.STATUS_PENDING));
                if (c != null) {
                    cursorOk86 = true;
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
            } catch (Exception ignored) { return; }
            if (!cursorOk86) return; // v11.86: null cursor keeps the previous set (see header)
            talkInProgressDlNames = out;
            talkInProgressDlAt = now;
        } catch (Exception ignored) {}
    }
    // v10.07: download-complete receiver, the Talks leg of the Room's v4.05 /
    // v10.06 hook — the chooser enqueues downloads (single, batch, all)
    // but never listened for their completion, so a download landing
    // while Talks sat foreground left its rows on ⏳ until the next
    // return (onResume's refreshTalkRowMarks) and a paused card on ⏳
    // until the next tap (rebuilds run every 15s only while playing).
    // Registered in onResume, unregistered in onPause, Room pattern.
    private android.content.BroadcastReceiver talksDlCompleteReceiver = null;
    private boolean talksDlCompleteRegistered = false;
    // v7.77: prune dead download-queue entries on this screen, not just in the
    // Room — the Room's pruneQueuedDownloads (v4.01, v7.20 rule) ran only on the
    // Room's creation + 30s rescan, while the chooser's rows and every batch
    // verb (header download-all, queue-★, download-★, download-shown) read
    // dlQueued raw: a DownloadManager job that failed, was cancelled in the
    // system Downloads app, or whose row was cleared left its entry behind,
    // so the chooser row showed ⏳ forever and every batch verb skipped the
    // talk as "already queued" (or toasted "ဒေါင်းလုဒ်တန်းစီနေပါပြီ" with
    // nothing pending) — the talk could never be re-downloaded from here
    // until that sayadaw's Room happened to be opened. The prune mirrors the
    // Room's rule exactly: a queued URL drops once its file has landed
    // (isTalkDownloaded, map + candidates + claim guard) or once no live
    // DownloadManager job owns it (PENDING/RUNNING/PAUSED, or unknown
    // status, app-scoped by source URL or a Downloads/Dhamma destination,
    // the v7.20 scope — a finished job's lingering row and another app's
    // same-basename download never keep an entry alive). One Talks-only
    // guard on top of the Room's rule: a live job whose source URL is the
    // queued URL keeps it even when the job exposes no destination name
    // yet, so a Wi-Fi-waiting job is never pruned on a thin row. A failed
    // query (activeNames null) keeps everything, like the Room's rule, so a
    // transient read failure never drops a live queue. Runs only on the
    // same gate as the downloaded-file scan below (forced, or 30s elapsed),
    // never per bind and never on the 1s strip tick, so scrolling and the
    // strip stay cheap; the caller rebinds when it drops anything.
    private boolean pruneTalkQueuedDownloads() {
        talkRefreshInProgressDlNames(true); // v11.57: fresh set before the downloaded-drop below
        try {
            org.json.JSONArray arr = new org.json.JSONArray(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlQueued", "[]"));
            if (arr.length() == 0) return false;
            java.util.Set<String> queued = new java.util.HashSet<String>();
            for (int i = 0; i < arr.length(); i++) queued.add(arr.optString(i));
            java.util.Set<String> activeNames = null;
            java.util.Set<String> activeSources = new java.util.HashSet<String>();
            try {
                android.app.DownloadManager dm = (android.app.DownloadManager)
                        getSystemService(android.content.Context.DOWNLOAD_SERVICE);
                // v12.85: filter in the provider, like the v11.76 strip scans — this runs
                // on the 30s gate the 1s strip tick drives and used to pull every download
                // on the main thread; the status check below stays as a guard.
                android.database.Cursor c = dm.query(new android.app.DownloadManager.Query()
                        .setFilterByStatus(android.app.DownloadManager.STATUS_RUNNING
                                | android.app.DownloadManager.STATUS_PAUSED
                                | android.app.DownloadManager.STATUS_PENDING));
                // v11.76: the set is created only after a non-null cursor is confirmed —
                // a null cursor used to leave an empty non-null set, so the prune loop
                // below dropped every live queued entry as "not active" (the comment
                // above already promised a failed query keeps everything).
                if (c != null) {
                    activeNames = new java.util.HashSet<String>();
                    try {
                        int uriCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_LOCAL_URI);
                        int srcCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_URI);
                        int statusCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS);
                        while (c.moveToNext()) {
                            int st = statusCol >= 0 ? c.getInt(statusCol) : -1;
                            if (st >= 0
                                    && st != android.app.DownloadManager.STATUS_RUNNING
                                    && st != android.app.DownloadManager.STATUS_PAUSED
                                    && st != android.app.DownloadManager.STATUS_PENDING) continue;
                            String src = srcCol >= 0 ? c.getString(srcCol) : null;
                            String uri = uriCol >= 0 ? c.getString(uriCol) : null;
                            if (src != null && !queued.contains(src)
                                    && (uri == null || !uri.contains("Dhamma/"))) continue;
                            if (src == null && uri != null && !uri.contains("Dhamma/")) continue;
                            if (src != null) activeSources.add(src);
                            if (uri != null) {
                                // v11.86: decode before the basename, Room v11.81 port —
                                // COLUMN_LOCAL_URI is a file:// URI, so a filename carrying
                                // a %XX escape arrives here as %25XX and never matched the
                                // dlMap name: the prune dropped the live ⏳ and a re-tap
                                // could double-enqueue. talkRefreshInProgressDlNames above
                                // already decodes; this was the one site that forgot.
                                String dec86 = android.net.Uri.decode(uri);
                                if (dec86 == null) dec86 = uri;
                                int slash = dec86.lastIndexOf('/');
                                activeNames.add(slash >= 0 ? dec86.substring(slash + 1) : dec86);
                            }
                        }
                    } finally { c.close(); }
                }
            } catch (Exception ignored) { activeNames = null; }
            boolean changed = false;
            java.util.Iterator<String> it = queued.iterator();
            while (it.hasNext()) {
                String url = it.next();
                if (isTalkDownloaded(url)) { it.remove(); changed = true; continue; }
                if (activeSources.contains(url)) continue;
                if (activeNames != null) {
                    String mapped = talkMappedFileName(url);
                    if (mapped != null && !activeNames.contains(mapped)) { it.remove(); changed = true; }
                    else if (mapped == null) {
                        // v11.86: Room v11.85 port — a queued URL with no dlMap entry
                        // could never be pruned above, so its ⏳ stuck forever and every
                        // batch verb skipped it as "already queued". Both enqueue sites
                        // record the map first, so a map-less entry is a legacy remnant
                        // (or a torn write — recordTalkDownload writes dlMap/dlSayadaw/
                        // dlQueued as three separate apply() calls) whose job is dead;
                        // drop it when none of its filename candidates names a live job.
                        // A live job's candidates always match, and a failed query keeps
                        // everything (activeNames null skips this whole block).
                        boolean anyActive86 = false;
                        String fn86 = talkDownloadFileNameFor(url);
                        if (fn86 != null && activeNames.contains(fn86)) anyActive86 = true;
                        if (!anyActive86) {
                            String base86 = url.substring(url.lastIndexOf('/') + 1);
                            int q86 = base86.indexOf('?');
                            if (q86 >= 0) base86 = base86.substring(0, q86);
                            int h86 = base86.indexOf('#');
                            if (h86 >= 0) base86 = base86.substring(0, h86);
                            if (base86.length() > 0 && activeNames.contains(base86)) anyActive86 = true;
                            if (!anyActive86) {
                                String dec86 = android.net.Uri.decode(base86);
                                if (dec86 != null && dec86.length() > 0 && activeNames.contains(dec86)) anyActive86 = true;
                            }
                            if (!anyActive86) {
                                // v12.16: the plain enqueue destination
                                // form — fn86 above already returns
                                // stripped + ".mp3", but when that name is
                                // claimed by another URL fn86 carries this
                                // URL's hash suffix instead, so the live
                                // job's plain "play.php.mp3" missed every
                                // check. The plain form is a live job's
                                // name for this URL too; the non-empty
                                // guard mirrors Room fileNameCandidates.
                                String dest16 = base86;
                                if (dest16.length() > 0 && !dest16.toLowerCase().endsWith(".mp3"))
                                    dest16 = dest16 + ".mp3";
                                if (activeNames.contains(dest16)) anyActive86 = true;
                            }
                        }
                        if (!anyActive86) { it.remove(); changed = true; }
                    }
                }
            }
            if (changed) {
                org.json.JSONArray out = new org.json.JSONArray();
                for (String u : queued) out.put(u);
                getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                        .putString("dlQueued", out.toString()).apply();
            }
            return changed;
        } catch (Exception ignored) { return false; }
    }

    private void refreshTalkRowMarks(boolean forceDl) {
        // v7.77: prune before the queued cache below is rebuilt, so the
        // cache, the rows and the batch verbs all read the pruned set
        // in this same call; a drop rebinds at the end of this method.
        boolean pruned77 = false;
        // v10.17: paused-card download-mark sync, Talks prune-drop leg of
        // v10.06/v10.14/v10.15/v10.16 — the prune below drops dead queue
        // entries and the rows lose their hourglass at once, but no prune
        // path refreshed the card: the v10.07 receiver covers only the
        // finished job's own URL, and v10.16 covers only the Room. A job
        // that FAILS or is cancelled/cleared still gets pruned here (tick
        // 30s gate, return, or completion rescan) while a PAUSED sounding
        // talk keeps its hourglass on the card until the next tap
        // (rebuilds run every 15s only while playing, never while
        // paused). Snapshot dlQueued before the prune (only when the
        // prune will run) and send the no-bind refresh intent (v10.15
        // shape) only when a dropped URL is the sounding talk — room:
        // dropped == lastUrl; library: dlMap[dropped] file ==
        // lastLibPath file — read fresh, never talkPlayingUrl. No
        // lastName guard: dlQueued and this prune are global, so the
        // sounding entry can drop from any sayadaw's chooser; the
        // sounding-URL guard alone decides, and a rebuild with no mark
        // change paints the identical card.
        java.util.Set<String> queuedBefore1017 = null;
        try {
            long now77 = android.os.SystemClock.uptimeMillis();
            if (forceDl || now77 - talkDlCacheAt >= 30000) {
                queuedBefore1017 = new java.util.HashSet<String>();
                org.json.JSONArray qb1017 = new org.json.JSONArray(
                        getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlQueued", "[]"));
                for (int i1017 = 0; i1017 < qb1017.length(); i1017++)
                    queuedBefore1017.add(qb1017.optString(i1017));
                pruned77 = pruneTalkQueuedDownloads();
            }
        } catch (Exception ignored) {}
        if (pruned77 && queuedBefore1017 != null && AudioService.liveSession) {
            try {
                android.content.SharedPreferences rsp1017 =
                        getSharedPreferences("dhamma", MODE_PRIVATE);
                org.json.JSONArray qa1017 = new org.json.JSONArray(rsp1017.getString("dlQueued", "[]"));
                java.util.Set<String> queuedAfter1017 = new java.util.HashSet<String>();
                for (int i1017 = 0; i1017 < qa1017.length(); i1017++)
                    queuedAfter1017.add(qa1017.optString(i1017));
                String lastUrl1017 = rsp1017.getString("lastUrl", "");
                String libPath1017 = rsp1017.getString("lastLibPath", "");
                org.json.JSONObject map1017 = new org.json.JSONObject(rsp1017.getString("dlMap", "{}"));
                boolean cardTalk1017 = false;
                for (String dropped1017 : queuedBefore1017) {
                    if (queuedAfter1017.contains(dropped1017)) continue;
                    if (dropped1017.equals(lastUrl1017)) { cardTalk1017 = true; break; }
                    if (libPath1017.length() > 0) {
                        String fn1017 = map1017.optString(dropped1017, "");
                        if (fn1017.length() > 0 && fn1017.equals(new java.io.File(libPath1017).getName())) { cardTalk1017 = true; break; }
                    }
                }
                if (cardTalk1017) {
                    android.content.Intent rsv1017 = new android.content.Intent(TalksActivity.this, AudioService.class);
                    rsv1017.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                    startService(rsv1017);
                }
            } catch (Exception ignored) {}
        }
        try {
            java.util.Set<String> ft = getSharedPreferences("dhamma", MODE_PRIVATE)
                    .getStringSet("favTalks", null);
            talkFavCache = ft != null ? new java.util.HashSet<String>(ft) : new java.util.HashSet<String>();
        } catch (Exception ignored) { talkFavCache = new java.util.HashSet<String>(); }
        try {
            java.util.Set<String> pl = getSharedPreferences("dhamma", MODE_PRIVATE)
                    .getStringSet("playedTalks", null);
            talkPlayedCache = pl != null ? new java.util.HashSet<String>(pl) : new java.util.HashSet<String>();
        } catch (Exception ignored) { talkPlayedCache = new java.util.HashSet<String>(); }
        try {
            java.util.Set<String> q = new java.util.HashSet<String>();
            org.json.JSONArray arr = new org.json.JSONArray(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlQueued", "[]"));
            for (int i = 0; i < arr.length(); i++) q.add(arr.optString(i));
            talkDlQueuedCache = q;
        } catch (Exception ignored) { talkDlQueuedCache = new java.util.HashSet<String>(); }
        long now = android.os.SystemClock.uptimeMillis();
        if (forceDl || now - talkDlCacheAt >= 30000) {
            talkDlCacheAt = now;
            talkRefreshInProgressDlNames(false); // v11.57: prune already forced it on this gate
            talkDlFileCache.clear(); // v5.59: rebuilt by the scan below, like talkDlCache itself
            java.util.Set<String> dl = new java.util.HashSet<String>();
            if (talks != null) for (Talk t : talks) {
                try { if (isTalkDownloaded(t.url)) dl.add(t.url); } catch (Exception ignored) {}
            }
            boolean changed = !dl.equals(talkDlCache);
            talkDlCache = dl;
            if (changed && adapter != null) adapter.notifyDataSetChanged();
        }
        // v7.77: a prune above changed the queued cache the rows bind
        // against, but the scan branch above only rebinds on a
        // downloaded-set change — a dropped ⏳ would otherwise sit
        // until the next rebind. The queued cache was already rebuilt
        // from the pruned prefs above, so this rebind reads it fresh.
        if (pruned77 && adapter != null) adapter.notifyDataSetChanged();
    }
    // v4.89: last playing URL the rows were bound against — the
    // tick detects a change here and rebinds once (below).
    private String talkRowPlayingUrl = null;
    // v11.06: same change detection for the library-session
    // sounding URL, so a library advance while browsing rebinds
    // the rows and moves the ↺ suppression with it.
    private String talkRowPlayingLibUrl = null;
    // v11.15: ended state the rows were last bound against —
    // the v4.89 tick detects playing-URL changes only, so a
    // natural end (same URL, liveEnded flipped) would otherwise
    // leave the ▶ on the ended row until the next rebind.
    private boolean talkRowEnded = false;
    // v7.39: line-aware -- a saved line is parsed and matched by its
    // URL, so a v2-spelled line (handover-carried, owned by another
    // sayadaw) for a talk that IS in this list still earns its
    // mark, like the Room's v7.38 queueLineIndexForUrl fix. talkQueue
    // stays bare URLs, so every downstream contains/indexOf site
    // (row marks, move, header) is unchanged.
    private void refreshQueueMarks() {
        java.util.ArrayList<String> q = new java.util.ArrayList<String>();
        String saved = Prefs.savedQueue(TalksActivity.this, sayadawName);
        if (saved != null && saved.length() > 0) {
            for (String line : saved.split("\n")) {
                if (line.length() == 0) continue;
                QueueEntry e = QueueEntry.parse(line);
                String u = e == null ? "" : e.url;
                if (u.length() == 0 || q.contains(u)) continue;
                for (int i = 0; i < talks.size(); i++)
                    if (talks.get(i).url.equals(u)) { q.add(u); break; }
            }
        }
        talkQueue = q;
        lastSyncedTalkQueue04 = new java.util.ArrayList<String>(q); // v12.04 snapshot
        talkPlayingUrl = "";
        talkPlayingLibUrl = "";
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            // v10.43: bound-trackless stop — liveSession stays true but the mirror is false, so the stopped talk is never the playing talk.
            if (AudioService.liveSession && AudioService.liveHasTrack && "room".equals(sp.getString("lastKind", ""))
                    && sayadawName != null && sayadawName.equals(sp.getString("lastName", "")))
                talkPlayingUrl = sp.getString("lastUrl", "");
            // v11.06: library-session sounding talk, for the ↺
            // suppression in the row bind — same live guards, but
            // lastKind lib and the sounding file is lastLibPath,
            // so its stream URL comes from the dlMap reverse
            // lookup, exactly-one claimant only (v8.44 rule: a
            // dual-claimed file names no talk rather than the
            // wrong one). A file with no dlMap entry keeps the
            // old behaviour (no suppression) rather than a
            // guessed match.
            if (AudioService.liveSession && AudioService.liveHasTrack && "lib".equals(sp.getString("lastKind", ""))) {
                String libPath = sp.getString("lastLibPath", "");
                if (libPath.length() > 0) {
                    String base = libPath.substring(libPath.lastIndexOf('/') + 1);
                    org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
                    java.util.Iterator<String> keys = map.keys();
                    String hit = null;
                    int matches = 0;
                    while (keys.hasNext()) {
                        String k = keys.next();
                        if (base.equals(map.optString(k, ""))) { hit = k; matches++; }
                    }
                    if (matches == 1) talkPlayingLibUrl = hit;
                }
            }
        } catch (Exception ignored) {}
    }
    // v4.87: queue preview above the Talks strip — the chooser
    // mirror of the Room's v4.83 preview + v4.84 row menu + v4.85
    // header and the library's v4.86 preview. The chooser header
    // (v4.65) and strip (v4.68) already count the hand queue and
    // name the single Up Next talk, but both are text only: no
    // tap to play, no queue menu, no count of the whole upcoming
    // set, and a hand queue still reads only as ◈ marks scattered
    // through the list. Now up to 3 following talks sit between
    // the list and the strip, in true play order: the pruned hand
    // queue first (queue order, playing talk skipped like the
    // library's cur-skip, since a direct row tap can leave it
    // queued), else list order after the playing talk with wrap
    // (exactly the strip Next's list pick, so the preview never
    // promises a different next talk than ⏭ plays). Hidden for
    // any non-Room session, for a Room session of another
    // sayadaw, when the playing talk is not in this list, while
    // shuffle is on with no hand queue (order unknowable — the
    // Room's v4.83 rule), and whenever nothing follows. Rows
    // mirror the library preview's marks (★, ◈N) and gestures
    // (tap opens the talk in the Room like a list row tap;
    // long-press opens that talk's own ⋮ queue menu, v4.47).
    // The gold header counts the whole upcoming set (v12.85: the
    // "opens the queue-only view" claim is deleted with the view —
    // the v7.32 preview removal had already left it without an
    // entry point). The queue cache is refreshed
    // here (same prefs read as the header), never written — the
    // 1s strip tick must never write prefs — and a signature
    // owns rebuilds, so steady rows are never rewritten on the
    // tick. Called from the strip tick's head (so every early
    // return path still refreshes it) and from applyTheme (so a
    // load or a return paints it before the first tick).
    // v7.09: save the chooser's in-list queue (talkQueue) without
    // dropping v2 lines the Room keeps: any saved v2 line whose URL
    // is not already in the list rides verbatim, appended after the
    // in-list lines. The cross-sayadaw verb appends at the tail, so
    // the common path keeps exact order; a foreign line a Room
    // drawer move left mid-queue moves to the tail here instead of
    // surviving in place — the price of the chooser never knowing
    // foreign positions, and still strictly better than dropping it.
    // v10.98 helpers: URL-aware membership over stored queue lines
    // (bare v1 lines and v2 "url⇥sayadaw⇥title" lines alike).
    private static boolean outContainsUrl(java.util.ArrayList<String> out, String url) {
        if (url == null) return false;
        for (String line : out) {
            QueueEntry e = QueueEntry.parse(line);
            if (e != null && e.url.equals(url)) return true;
        }
        return false;
    }
    private static int indexOfUrl(java.util.ArrayList<String> out, String url) {
        if (url == null) return -1;
        for (int i = 0; i < out.size(); i++) {
            QueueEntry e = QueueEntry.parse(out.get(i));
            if (e != null && e.url.equals(url)) return i;
        }
        return -1;
    }
    // v12.05: the caller's moved set -- the Room's movedQueueUrls05,
    // restated for the chooser's bare-URL lists: entries whose
    // relative order changed between the last synced snapshot
    // (lastSyncedTalkQueue04) and the caller's list, via longest
    // common subsequence over the entries present in both (deduped,
    // first occurrence); entries new to the caller count as moved.
    // Same tie note as the Room: a pure adjacent swap may count
    // either member as moved, harmless because the carried foreign
    // lines are pinned at their carried position (sim1205 T4b) and
    // because both branches reproduce the caller order exactly when
    // the saved order is unchanged.
    private java.util.Set<String> movedTalkUrls05(java.util.ArrayList<String> snapshotUrls05,
                                                  java.util.ArrayList<String> callerUrls05) {
        java.util.Set<String> snapSet05 = new java.util.HashSet<String>(snapshotUrls05);
        java.util.Set<String> callerSet05 = new java.util.HashSet<String>(callerUrls05);
        java.util.ArrayList<String> a05 = new java.util.ArrayList<String>();
        java.util.HashSet<String> seenA05 = new java.util.HashSet<String>();
        for (String u05 : snapshotUrls05)
            if (callerSet05.contains(u05) && seenA05.add(u05)) a05.add(u05);
        java.util.ArrayList<String> b05 = new java.util.ArrayList<String>();
        java.util.HashSet<String> seenB05 = new java.util.HashSet<String>();
        for (String u05 : callerUrls05)
            if (snapSet05.contains(u05) && seenB05.add(u05)) b05.add(u05);
        int n05 = a05.size(), m05 = b05.size();
        int[][] dp05 = new int[n05 + 1][m05 + 1];
        for (int i05 = n05 - 1; i05 >= 0; i05--)
            for (int j05 = m05 - 1; j05 >= 0; j05--)
                dp05[i05][j05] = a05.get(i05).equals(b05.get(j05))
                        ? dp05[i05 + 1][j05 + 1] + 1
                        : Math.max(dp05[i05 + 1][j05], dp05[i05][j05 + 1]);
        java.util.Set<String> lcs05 = new java.util.HashSet<String>();
        int i05 = 0, j05 = 0;
        while (i05 < n05 && j05 < m05) {
            if (a05.get(i05).equals(b05.get(j05))) { lcs05.add(a05.get(i05)); i05++; j05++; }
            else if (dp05[i05 + 1][j05] >= dp05[i05][j05 + 1]) i05++;
            else j05++;
        }
        java.util.Set<String> moved05 = new java.util.HashSet<String>();
        for (String u05 : callerUrls05) if (!snapSet05.contains(u05)) moved05.add(u05);
        for (String u05 : b05) if (!lcs05.contains(u05)) moved05.add(u05);
        return moved05;
    }
    // v11.98: keepUrl98 is the one entry this call just added
    // (long-press add / play-next) — it is not in the saved read
    // yet and must survive the stale drop below; every other
    // caller passes null (remove / move / move-to-end / prune
    // add nothing new, so nothing of theirs is kept).
    // v13.04: the Set overload — the bulk "queue all from here"
    // verb adds several URLs at once, and every one of them
    // must survive the same drop, so it passes them all.
    private void saveTalkQueueKeepingForeign(java.util.ArrayList<String> inList, String keepUrl98) {
        saveTalkQueueKeepingForeign(inList, keepUrl98 == null ? null
                : java.util.Collections.singleton(keepUrl98));
    }
    private void saveTalkQueueKeepingForeign(java.util.ArrayList<String> inList, java.util.Set<String> keepUrls98) {
        // v12.05: capture the caller's pre-mutation order + moved set
        // BEFORE the drop/adopt below -- inList itself is never
        // mutated by this funnel (only the out copy is), but the
        // capture must still precede the adopt, which is what the
        // order pass compares against.
        java.util.ArrayList<String> callerUrls05 = new java.util.ArrayList<String>();
        java.util.Set<String> callerSet05 = new java.util.HashSet<String>();
        for (String l05 : inList) {
            QueueEntry e05 = QueueEntry.parse(l05);
            if (e05 != null && e05.url.length() > 0) { callerUrls05.add(e05.url); callerSet05.add(e05.url); }
        }
        java.util.Set<String> moved05 = movedTalkUrls05(lastSyncedTalkQueue04, callerUrls05);
        java.util.ArrayList<String> out = new java.util.ArrayList<String>(inList);
        try {
            String saved = Prefs.savedQueue(TalksActivity.this, sayadawName);
            // v11.98: drop entries the service already popped —
            // the funnel re-read prefs but only ever ADDED the
            // carried foreign lines, so a talk the background
            // advance popped (AudioService advanceQueueHead63
            // saves the shortened queue) stayed in the caller's
            // stale in-memory talkQueue and was written straight
            // back: the sounding talk replayed after the next
            // talk and inflated the ◈ count. The window is the
            // <1s between the pop and the 1s tick's
            // refreshQueueMarks — a strip-Next-then-immediate
            // edit, or a natural end while a long-press dialog
            // sits open. The drop mirrors the existing
            // foreign-line rule below (a v2 line whose URL is
            // in-list but not in inList is dropped, never
            // resurrected): an entry absent from the fresh
            // saved read is gone — popped, cleared or pruned
            // elsewhere — unless this call just added it
            // (keepUrls98). Runs even when the saved read is
            // empty (a cleared queue must not resurrect
            // wholesale); foreign v2 lines are never in out
            // at entry (talkQueue holds bare URLs, the carried
            // lines re-enter from the saved read below), so
            // the drop cannot touch them, and a dropped
            // predecessor simply falls back to the one before
            // it in the anchor search, as v10.98 designed.
            java.util.Set<String> savedUrls98 = new java.util.HashSet<String>();
            if (saved != null && saved.length() > 0) {
                for (String line98 : saved.split("\n")) {
                    QueueEntry e98 = QueueEntry.parse(line98);
                    if (e98 != null && e98.url.length() > 0) savedUrls98.add(e98.url);
                }
            }
            for (int i98 = out.size() - 1; i98 >= 0; i98--) {
                QueueEntry e98 = QueueEntry.parse(out.get(i98));
                String u98 = e98 == null ? "" : e98.url;
                if (u98.length() == 0) continue;
                if (keepUrls98 != null && keepUrls98.contains(u98)) continue;
                if (!savedUrls98.contains(u98)) out.remove(i98);
            }
            if (saved != null && saved.length() > 0) {
                // v10.98: anchor-preserving merge -- the old loop
                // appended every carried v2 line at the tail, so a
                // foreign talk the Room drawer had moved MID-queue
                // jumped to the end on the next Talks edit (add,
                // remove, play-next, reorder): the queue the Room
                // then played no longer matched the order the
                // listener had set. Carried lines now keep their
                // saved position, anchored after the nearest
                // preceding saved line that survives in out (a
                // removed predecessor falls back to the one before
                // it; no surviving predecessor means the head, in
                // saved order). New in-list additions therefore
                // land behind everything already queued, FIFO,
                // exactly as the Room drawer's own append does.
                // The v7.39 in-place replacement runs first, so
                // anchors resolve through v2 spellings too.
                java.util.ArrayList<String> savedLines = new java.util.ArrayList<String>();
                for (String line : saved.split("\n"))
                    if (line.length() > 0) savedLines.add(line);
                // v7.39: a foreign-owned v2 line whose URL is now
                // in-list (refreshQueueMarks holds it bare) must
                // keep its v2 spelling on save, in place -- the
                // old skip dropped it to bare and changed the
                // Room's foreign identity for that talk. A
                // same-owner v2 line normalises to bare (the
                // QueueEntry.format rule), so it is skipped.
                for (String line : savedLines) {
                    QueueEntry e = QueueEntry.parse(line);
                    if (e == null || !e.isV2()) continue;
                    if (sayadawName != null && !e.sayadaw.equals(sayadawName)
                            && out.contains(e.url))
                        out.set(out.indexOf(e.url), line);
                }
                // v12.04: ADOPT entries added elsewhere + carry
                // foreign lines, in ONE pass over the saved lines
                // (the v12.00 audit's slice-B untraced item, now
                // traced): split-screen Talks long-press dialog vs
                // Room drawer on the same per-sayadaw key. The
                // Room funnel gained adoption in v12.03, but this
                // funnel only dropped + carried: a Room drawer ADD
                // of an in-list talk (bare line) made while this
                // chooser's talkQueue was stale -- the <1s before
                // the 1s tick's refreshQueueMarks -- was silently
                // wiped by the next chooser verb (add / remove /
                // play-next / move), and the prune caller wiped
                // one the same way. An in-list line absent from
                // out is now adopted at its anchor position --
                // EXCEPT the removed set (lastSyncedTalkQueue04
                // minus inList: entries THIS window removed, whose
                // removal has not persisted yet, so the fresh
                // saved read still holds them; adopting them
                // would undo the caller's own removal -- the
                // v10.98 "removed by the caller" case, now exactly
                // that half). A removal older than the last save
                // already landed, so a re-add elsewhere after it
                // persisted IS adopted (last writer wins). One
                // pass in saved order with ONE afterCount map, so
                // an adopted line and a carried foreign line after
                // the same anchor keep their saved order (two
                // separate passes would swap them). The ORDER
                // adoption below (v12.05) then re-sorts this
                // window's own unmoved entries to the saved order;
                // only entries this call moved stay pinned.
                // Unparseable lines are still skipped, as before.
                java.util.Set<String> removed04 = new java.util.HashSet<String>();
                for (String u04 : lastSyncedTalkQueue04)
                    if (u04 != null && u04.length() > 0 && !inList.contains(u04)) removed04.add(u04);
                java.util.HashMap<String, Integer> afterCount = new java.util.HashMap<String, Integer>();
                for (int si = 0; si < savedLines.size(); si++) {
                    QueueEntry e = QueueEntry.parse(savedLines.get(si));
                    if (e == null || e.url.length() == 0) continue;
                    if (outContainsUrl(out, e.url)) continue;
                    boolean inLoadedList = false;
                    if (talks != null)
                        for (int ti = 0; ti < talks.size(); ti++)
                            if (talks.get(ti).url != null && talks.get(ti).url.equals(e.url)) { inLoadedList = true; break; }
                    if (inLoadedList) {
                        if (removed04.contains(e.url)) continue;
                    } else if (!e.isV2()) continue;
                    String anchor = null;
                    for (int pj = si - 1; pj >= 0; pj--) {
                        QueueEntry pe = QueueEntry.parse(savedLines.get(pj));
                        if (pe == null) continue;
                        if (outContainsUrl(out, pe.url)) { anchor = pe.url; break; }
                    }
                    int pos;
                    if (anchor == null) {
                        Integer c = afterCount.get("");
                        pos = c == null ? 0 : c.intValue();
                        afterCount.put("", Integer.valueOf(pos + 1));
                    } else {
                        int ai = indexOfUrl(out, anchor);
                        if (ai < 0) continue;
                        Integer c = afterCount.get(anchor);
                        int k = c == null ? 0 : c.intValue();
                        pos = ai + 1 + k;
                        afterCount.put(anchor, Integer.valueOf(k + 1));
                    }
                    if (pos > out.size()) pos = out.size();
                    out.add(pos, savedLines.get(si));
                }
                // v12.05: ORDER ADOPTION -- the v12.04 note's accepted
                // limit, now closed (the Room funnel's twin, same
                // rule): a Room drawer reorder made inside the <1s
                // window used to be reverted by this chooser's stale
                // order written back. Entries this call did NOT move
                // (not in moved05, and present in the caller's list
                // at entry -- adopted / carried lines are pinned at
                // the position the pass above just gave them, which
                // already is their saved position) are re-sorted to
                // the fresh saved order; entries this call moved,
                // just added (keepUrls98), or with no saved rank stay
                // pinned at their current slots. The next 1s tick's
                // refreshQueueMarks rebuilds talkQueue from the
                // persisted key, so the chooser's in-memory order
                // converges to the merged order within a second.
                {
                    java.util.Map<String, Integer> rank05 = new java.util.HashMap<String, Integer>();
                    for (int i05 = 0; i05 < savedLines.size(); i05++) {
                        QueueEntry e05 = QueueEntry.parse(savedLines.get(i05));
                        if (e05 != null && e05.url.length() > 0 && !rank05.containsKey(e05.url)) rank05.put(e05.url, Integer.valueOf(i05));
                    }
                    java.util.ArrayList<String> merged05 = new java.util.ArrayList<String>();
                    for (int i05 = 0; i05 < out.size(); i05++) merged05.add(null);
                    java.util.ArrayList<String> rest05 = new java.util.ArrayList<String>();
                    for (int i05 = 0; i05 < out.size(); i05++) {
                        String line05 = out.get(i05);
                        QueueEntry e05 = QueueEntry.parse(line05);
                        String u05 = e05 == null ? "" : e05.url;
                        if (u05.length() == 0 || !callerSet05.contains(u05) || moved05.contains(u05) || !rank05.containsKey(u05)) merged05.set(i05, line05);
                        else rest05.add(line05);
                    }
                    java.util.Collections.sort(rest05, new java.util.Comparator<String>() {
                        @Override public int compare(String a, String b) {
                            QueueEntry ea = QueueEntry.parse(a);
                            QueueEntry eb = QueueEntry.parse(b);
                            Integer ra = ea == null ? null : rank05.get(ea.url);
                            Integer rb = eb == null ? null : rank05.get(eb.url);
                            int ia = ra == null ? Integer.MAX_VALUE : ra.intValue();
                            int ib = rb == null ? Integer.MAX_VALUE : rb.intValue();
                            return ia < ib ? -1 : (ia > ib ? 1 : 0);
                        }
                    });
                    int r05 = 0;
                    for (int i05 = 0; i05 < merged05.size(); i05++)
                        if (merged05.get(i05) == null) merged05.set(i05, rest05.get(r05++));
                    out.clear();
                    out.addAll(merged05);
                }
            }
        } catch (Exception ignored) {}
        Prefs.saveQueue(TalksActivity.this, sayadawName, out);
        // v10.12: paused-card queue-count sync, Talks leg of v10.05 — every
        // chooser queue edit writes through this one funnel (⋮ add/remove,
        // play-next, long-press reorder drops, stale prune), but none of
        // them refreshed the notification card, so a PAUSED sounding talk
        // from this sayadaw kept its old ◈ waiting count until the next
        // tap: the card reads this same savedQueue key at rebuild time
        // and rebuilds run only every 15s while playing, never while
        // paused. Talks never binds, so the refresh goes as the no-bind
        // ACTION_REFRESH_NOTIFICATION intent (v10.03 fav-star shape),
        // gated on AudioService.liveSession (never starts a dead
        // service, v10.10 shape) plus prefs lastName == this sayadaw
        // (the card's ◈ count reads savedQueue(lastName), so an edit
        // to any other sayadaw's queue can never change it); the
        // handler itself stopSelfs when trackless, and a rebuild with
        // an unchanged count (pure reorder) paints the identical card.
        try {
            if (AudioService.liveSession && sayadawName != null
                    && sayadawName.equals(getSharedPreferences("dhamma", MODE_PRIVATE).getString("lastName", ""))) {
                android.content.Intent rsv = new android.content.Intent(TalksActivity.this, AudioService.class);
                rsv.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                startService(rsv);
            }
        } catch (Exception ignored) {}
    }
    // v7.22: the chooser's foreign queue head — the Room's
    // firstWaitingForeignEntry, restated on the saved key: the
    // first line that is not the sounding talk is the waiting
    // head, and it is foreign exactly when it is a v2 line owned
    // by another sayadaw. Reads the same prefs key the Room
    // saves to, in the same order, so it names the same head
    // handleNext's tryForeignHandover would play — never a
    // second, reordered opinion. Relies on talkPlayingUrl, which
    // refreshQueueMarks already computed (same reliance as the
    // header's nxTalk and the preview itself); empty means no
    // live Room session for this sayadaw, so no head either.
    // Stale lines ahead of it behave exactly as in the Room:
    // the first waiting line, stale or not, decides — the Room
    // prunes stale lines on its own peek, and this chooser read
    // follows the same first-line rule until that prune lands.
    private QueueEntry foreignQueueHead() {
        if (talkPlayingUrl == null || talkPlayingUrl.length() == 0) return null;
        try {
            String saved = Prefs.savedQueue(TalksActivity.this, sayadawName);
            if (saved == null || saved.length() == 0) return null;
            for (String line : saved.split("\n")) {
                if (line.length() == 0) continue;
                QueueEntry e = QueueEntry.parse(line);
                if (e == null) continue;
                if (e.url.equals(talkPlayingUrl)) continue;
                if (e.isV2() && sayadawName != null && !e.sayadaw.equals(sayadawName)) return e;
                return null;
            }
        } catch (Exception ignored) {}
        return null;
    }
    // v11.76: openRoomSession deleted — dead since v7.32 removed the foreign
    // preview rows it served; no caller remained, and it duplicated the strip
    // tap's Room-open path (including the stale autoTitle extra) while
    // silently returning for library sessions.
    // v7.32: showForeignPreviewMenu deleted — preview rows removed by user order, no caller remains.
    private void refreshTalksQueuePreview() {
        // v7.32 (user order 2026-11-01 ~19:36 +07): queue preview UI removed —
        // the container is gone from the layout, so this method now only
        // refreshes the chooser row marks that used to ride along with it.
        refreshQueueMarks();
        refreshTalkRowMarks(false);
    }
    // v4.71: offline check for the chooser header's Up Next mark —
    // the Room's isDownloadedFile (v1.90 map + v1.82 candidates +
    // claim guard), restated here because TalksActivity never
    // owned a downloaded set: the mapped name from the "dlMap"
    // prefs wins, else any candidate basename in Downloads/Dhamma
    // counts unless another URL's download claims that name.
    // File-exists checks only, once per header refresh.
    private String talkMappedFileName(String url) {
        try {
            org.json.JSONObject map = new org.json.JSONObject(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlMap", "{}"));
            if (map.has(url)) return map.getString(url);
        } catch (Exception ignored) {}
        return null;
    }
    private boolean talkFileClaimedByOther(String filename, String url) {
        try {
            org.json.JSONObject map = new org.json.JSONObject(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlMap", "{}"));
            java.util.Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!k.equals(url) && filename.equals(map.optString(k, ""))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // v11.70: filename for a NEW download enqueued from the chooser --
    // the Room's downloadFileNameFor rule in Talks form. The mapped
    // name wins, else the basename stripped of any ?query / #fragment
    // with .mp3 guaranteed (v1.95), and a basename another URL's
    // download claims takes the 8-hex MD5 suffix (v8.40 claim-alone
    // rule, v1.94 shape) instead of overwriting that talk's file --
    // so a chooser-enqueued file lands under exactly the name every
    // offline check already recognises.
    private String talkDownloadFileNameFor(String url) {
        String mapped = talkMappedFileName(url);
        if (mapped != null) return mapped;
        String base = url.substring(url.lastIndexOf('/') + 1);
        int q = base.indexOf('?');
        if (q >= 0) base = base.substring(0, q);
        int hh = base.indexOf('#');
        if (hh >= 0) base = base.substring(0, hh);
        if (!base.toLowerCase().endsWith(".mp3")) base = base + ".mp3";
        if (talkFileClaimedByOther(base, url)) {
            String hash = "";
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] d = md.digest(url.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i]));
                hash = sb.toString();
            } catch (Exception ignored) { hash = String.format("%08x", url.hashCode()); }
            if (base.toLowerCase().endsWith(".mp3"))
                return base.substring(0, base.length() - 4) + "_" + hash + base.substring(base.length() - 4);
            return base + "_" + hash;
        }
        return base;
    }

    // v11.70: is this URL already queued for download -- fresh prefs
    // read at offer/tap time, not the cached set, so a job queued
    // from the Room a second earlier still blocks a double enqueue
    // (the Room gate's v10.73 guard, in chooser form).
    private boolean talkIsDlQueued(String url) {
        if (url == null) return false;
        try {
            org.json.JSONArray qa = new org.json.JSONArray(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlQueued", "[]"));
            for (int i = 0; i < qa.length(); i++)
                if (url.equals(qa.optString(i))) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // v11.70: record a chooser-enqueued download -- dlMap (the name
    // above), dlSayadaw (this chooser knows it, like the Room's
    // recordDownloadFile) and the dlQueued mark, written together so
    // no later check can see a queue entry without its filename.
    private void recordTalkDownload(String url, String file) {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
            map.put(url, file);
            sp.edit().putString("dlMap", map.toString()).apply();
            if (sayadawName != null && sayadawName.length() > 0) {
                try {
                    org.json.JSONObject sm = new org.json.JSONObject(sp.getString("dlSayadaw", "{}"));
                    sm.put(url, sayadawName);
                    sp.edit().putString("dlSayadaw", sm.toString()).apply();
                } catch (Exception ignored) {}
            }
            org.json.JSONArray queued = new org.json.JSONArray(sp.getString("dlQueued", "[]"));
            boolean found = false;
            for (int i = 0; i < queued.length(); i++) {
                if (url.equals(queued.optString(i))) { found = true; break; }
            }
            if (!found) queued.put(url);
            sp.edit().putString("dlQueued", queued.toString()).apply();
        } catch (Exception ignored) {}
    }

    private boolean isTalkDownloaded(String url) {
        try {
            java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma");
            String mapped = talkMappedFileName(url);
            if (mapped != null && new java.io.File(dir, mapped).exists()
                    && !talkInProgressDlNames.contains(mapped)) {
                talkDlFileCache.put(url, mapped); // v5.59: remember the match for the ↺ hint
                return true;
            }
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
            java.util.ArrayList<String> cands = new java.util.ArrayList<String>();
            cands.add(base);
            if (!stripped.equals(base)) cands.add(stripped);
            if (!decoded.equals(stripped) && !decoded.equals(base)) cands.add(decoded);
            // v12.16: the enqueue destination form (stripped + ".mp3",
            // Room fileNameCandidates) — a legacy unmapped wrapper-URL
            // download landed as "play.php.mp3"; without it the row kept
            // its hourglass after the file had landed. The claim guard
            // in the loop below still applies.
            if (stripped.length() > 0 && !stripped.toLowerCase().endsWith(".mp3"))
                cands.add(stripped + ".mp3");
            for (String name : cands)
                if (!talkFileClaimedByOther(name, url) && !talkInProgressDlNames.contains(name)
                        && new java.io.File(dir, name).exists()) {
                    talkDlFileCache.put(url, name); // v5.59: remember the match for the ↺ hint
                    return true;
                }
        } catch (Exception ignored) {}
        return false;
    }
    // v12.85: the v4.56 queue-only view is deleted — it was
    // unreachable in live use (no entry point since the v7.32
    // queue-preview removal; the flag was set true only by the
    // rotation restore of a state nothing could produce), and
    // restoring an entry would resurrect the removed preview UI.
    // The hand queue itself (talkQueue) is untouched: the ◈ marks,
    // queue verbs and the Room drawer keep working, and the list
    // is always the full list (search/filter intersect it).
    // v4.93: talk search — the chooser never searched its own
    // talks, so finding one title in a long sayadaw list meant
    // scrolling the whole list. searchHits holds talk indices in
    // full-list order whose titles contain the query; null means
    // no filter. A search applies to the full list
    // (rebuildSearch), so the filter composes with the full list
    // only and every display-position consumer below — tap,
    // long-press share, ⋮ menu via getView — keeps working through
    // the single talkIndexAtDisplay mapping.
    private String searchQuery = "";
    private java.util.ArrayList<Integer> searchHits = null;
    private android.widget.EditText searchBox = null;
    // v4.94: chooser filter views — the chooser gained search
    // (v4.93) but no way to view just ★ talks or just the
    // unlistened ones, while the library has a ★-only filter
    // (v4.28) and the chooser already queues both subsets
    // (v4.49/v4.50). filterMode 0 = all, 1 = ★ only, 2 =
    // unlistened only; v5.13 adds 3 = downloaded only, so an
    // offline listener can view just the saved talks for this
    // sayadaw without opening the global library. filterHits holds
    // the mode's talk indices
    // (null when mode 0), and visibleHits is the single display
    // list every consumer reads — search ∩ filter when both are
    // on, else whichever one is on, else null for the full list.
    // A filter tap applies to the full list like typing does,
    // and row numbers stay full-list numbers like
    // search rows, so every verb still acts on the right talk.
    private int filterMode = 0;
    private java.util.ArrayList<Integer> filterHits = null;
    private java.util.ArrayList<Integer> visibleHits = null;
    private android.widget.TextView filterBtn = null;
    private void rebuildVisible() {
        if (searchHits != null && filterHits != null) {
            java.util.ArrayList<Integer> both = new java.util.ArrayList<Integer>();
            for (Integer i : searchHits) if (filterHits.contains(i)) both.add(i);
            visibleHits = both;
        } else if (searchHits != null) visibleHits = searchHits;
        else if (filterHits != null) visibleHits = filterHits;
        else visibleHits = null;
        android.view.View empty = findViewById(R.id.talkSearchEmpty);
        if (empty != null) {
            // v9.98: the empty line kept its layout 15sp in every
            // font setting while the search row above it (v9.97)
            // and the rows below it already scale -- size it here,
            // inside rebuildVisible(), which applyTheme() calls on
            // create and on every return and which search/filter
            // changes call too, so one site covers all paths.
            // Step 14/15/18 = the exact Downloaded empty-text step;
            // normal font yields exactly the layout 15sp, so
            // default display is unchanged. Runs even while hidden.
            if (empty instanceof android.widget.TextView) {
                int ef = Prefs.fontSize(TalksActivity.this);
                ((android.widget.TextView) empty).setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_SP,
                        ef == 0 ? 14 : (ef == 1 ? 15 : 18));
                // v11.53: the empty line kept its layout muted brown
                // in both themes, while Downloaded's empty line
                // switches to #AAAAAA at night — same night value
                // here, day value exactly the layout colour. Set
                // beside the size, inside rebuildVisible(), which
                // applyTheme() calls on create and every return,
                // so a theme change in Settings lands on return.
                ((android.widget.TextView) empty).setTextColor(
                        Prefs.isNight(TalksActivity.this)
                                ? android.graphics.Color.parseColor("#AAAAAA")
                                : android.graphics.Color.parseColor("#7A6A58"));
            }
            boolean show = visibleHits != null && visibleHits.isEmpty() && !talks.isEmpty();
            empty.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show && empty instanceof android.widget.TextView) {
                ((android.widget.TextView) empty).setText(
                        searchQuery != null && searchQuery.length() > 0
                                ? Lang.t(TalksActivity.this, "empty_no_search")
                                : (filterMode == 1
                                        ? Lang.t(TalksActivity.this, "empty_no_fav")
                                        : (filterMode == 3
                                                ? Lang.t(TalksActivity.this, "empty_no_downloaded")
                                                : Lang.t(TalksActivity.this, "all_listened"))));
            }
        }
    }
    private void rebuildFilter() {
        if (filterMode == 0) {
            filterHits = null;
        } else {
            java.util.ArrayList<Integer> hits = new java.util.ArrayList<Integer>();
            java.util.Set<String> ft = null;
            java.util.Set<String> pl = null;
            try {
                android.content.SharedPreferences sp =
                        getSharedPreferences("dhamma", MODE_PRIVATE);
                ft = sp.getStringSet("favTalks", null);
                pl = sp.getStringSet("playedTalks", null);
            } catch (Exception ignored) {}
            for (int i = 0; i < talks.size(); i++) {
                String u = talks.get(i).url;
                if (filterMode == 1) {
                    if (ft != null && ft.contains(u)) hits.add(i);
                } else if (filterMode == 3) {
                    // v5.13: downloaded only — reuses the chooser
                    // header's isTalkDownloaded (dlMap + candidates
                    // + claim guard), so a download landed from the
                    // Room is picked up by the on-return rebuild.
                    if (isTalkDownloaded(u)) hits.add(i);
                } else {
                    if (pl == null || !pl.contains(u)) hits.add(i);
                }
            }
            filterHits = hits;
        }
        rebuildVisible();
    }
    private void refreshFilterBtn() {
        if (filterBtn == null) return;
        if (filterMode == 1) {
            filterBtn.setText(Lang.t(TalksActivity.this, "filter_btn_star"));
            filterBtn.setTextColor(android.graphics.Color.parseColor("#C9A227"));
        } else if (filterMode == 2) {
            filterBtn.setText(Lang.t(TalksActivity.this, "filter_btn_new"));
            filterBtn.setTextColor(android.graphics.Color.parseColor("#C9A227"));
        } else if (filterMode == 3) {
            filterBtn.setText(Lang.t(TalksActivity.this, "filter_btn_dl"));
            filterBtn.setTextColor(android.graphics.Color.parseColor("#C9A227"));
        } else {
            filterBtn.setText(Lang.t(TalksActivity.this, "repeat_all"));
            // v11.53: the inactive filter label kept day muted brown
            // (#7A6A58) at night too, while every other muted text
            // on the themed screens switches to #AAAAAA at night —
            // same night value, day value exactly the layout colour.
            filterBtn.setTextColor(Prefs.isNight(TalksActivity.this)
                    ? android.graphics.Color.parseColor("#AAAAAA")
                    : android.graphics.Color.parseColor("#7A6A58"));
        }
    }
    private void clearFilter() {
        if (filterMode == 0) return;
        filterMode = 0;
        filterHits = null;
        refreshFilterBtn();
        rebuildVisible();
        if (adapter != null) adapter.notifyDataSetChanged();
    }
    private void rebuildSearch() {
        if (searchQuery == null || searchQuery.length() == 0) {
            searchHits = null;
        } else {
            java.util.ArrayList<Integer> hits = new java.util.ArrayList<Integer>();
            for (int i = 0; i < talks.size(); i++)
                // v5.58: space-insensitive title match (Fx.titleMatches)
                // — the plain contains above used to miss a title
                // typed without its spaces, while Main (v1.47) and
                // the library (v1.51) already found it.
                if (talks.get(i).title != null && Fx.titleMatches(talks.get(i).title, searchQuery)) hits.add(i);
            searchHits = hits;
        }
        rebuildVisible();
        android.view.View clear = findViewById(R.id.talkSearchClear);
        if (clear != null) clear.setVisibility(
                searchQuery != null && searchQuery.length() > 0 ? View.VISIBLE : View.GONE);
    }
    private void clearSearch() {
        // Scroll-to-talk paths (title long-press, return glide,
        // preview-header tap) index the FULL list, so they clear
        // the filter first instead of scrolling to a filtered
        // position that means something else. Setting the box text
        // fires the watcher, which rebuilds + rebinds; the direct
        // rebuild below covers the box being null (pre-wire).
        if (searchBox != null) searchBox.setText("");
        searchQuery = "";
        rebuildSearch();
        if (adapter != null) adapter.notifyDataSetChanged();
    }
    private int talkIndexAtDisplay(int displayPos) {
        if (visibleHits != null) {
            if (displayPos < 0 || displayPos >= visibleHits.size()) return -1;
            return visibleHits.get(displayPos);
        }
        return displayPos;
    }
    // v2.03: row-bind cache — getView used to read SharedPreferences
    // (fontSize twice + isNight) for every row on every scroll bind.
    // applyTheme() already runs on create and on return and rebinds,
    // so cache here; defaults are the normal-font day values.
    private int cachedFont = 1;
    private boolean cachedNight = false;
    // v11.45: cached UI language for the return dip, mirroring Main
    // v3.84 / Room v3.86 — null until applyTheme first runs, so a
    // first onResume never dips; see the onResume block below.
    private String cachedLang = null;
    private ListView talkList;
    private ProgressBar progress;
    // v4.14: share-chooser guard for the long-press share above —
    // mirrors the Room's suppressBgPause (see there).
    private boolean suppressSharePause = false;
    private String sayadawName, pageUrl;
    private String autoUrl;
    // v1.83: generation guard for the loader — tapping retry while a
    // load is still in flight used to start a second thread, and the
    // older, slower one finishing last wiped the newer list (and
    // flashed a stale error + retry button over a good load). Only
    // the newest load may touch the list.
    private int loadSeq = 0;
    // v12.56: clean-empty-load flag (see the onCreate restore below) —
    // a load that lands with ferr == null and zero talks sets it, a
    // new load clears it; onSaveInstanceState carries it, so a rotation
    // after a clean empty load restores the empty state instead of
    // refetching the same page and re-toasting "no talks".
    private boolean talkCleanEmpty = false;
    // v3.91: first-successful-load gate for the playing-talk
    // auto-scroll below — a retry after a failed load still
    // counts as its first success, but a later reload never
    // re-yanks a list the user has already scrolled.
    private boolean scrolledToPlaying = false;
    // v3.94: return-scroll guards — the first onResume belongs to
    // this instance's own creation (and to every rotation, which
    // recreates the instance), so only a later onResume is a
    // back-stack return worth scrolling on. lastSeenPlayingUrl
    // remembers the playing talk this list last anchored on, so a
    // plain home-and-back return with the same talk still playing
    // never yanks a list the user was browsing.
    private boolean resumedOnce = false;
    private String lastSeenPlayingUrl = null;
    // v2.79: the Talks retry fade-out animator (see hideTalksRetry)
    // — kept in a field so a failure landing mid-fade, or a leave
    // mid-fade, can cancel or finish it instead of fighting it.
    private android.animation.ObjectAnimator talksRetryFade;
    // v11.10: direction of the fade above -- true only while a
    // hide fade (alpha -> 0) runs; the v3.53 show glide reuses
    // the same field, and onPause must not GONE a button that
    // was gliding back to visible (see onPause below).
    private boolean talksRetryFadeHiding = false;
    // v4.31: mini-player tick (Main's 1s strip tick, ported) — the
    // chooser never bound to the service, so the strip re-syncs
    // from prefs + the service's live statics once a second.
    private android.os.Handler stripHandler = new android.os.Handler();
    private Runnable stripTick;
    // v6.01: the sayadaw whose portrait the chooser strip last
    // showed (null = photo hidden) — Main's v6.00 strip-photo
    // guard, mirrored, so the 1s tick never re-loads a face.
    private String talksStripPhotoName = null;
    // v4.61: resume-strip mode (Main's v4.60 continue strip,
    // mirrored) — true while the chooser strip shows a dead
    // session's saved spot instead of a live track, so the
    // strip/toggle long-presses dismiss the spot instead of
    // stopping a session that no longer exists, and the ▶
    // button re-opens the Room instead of toggling nothing.
    private boolean talksResumeStripMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_talks);
        if (Prefs.keepScreenOn(this))
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sayadawName = getIntent().getStringExtra("name");
        pageUrl = getIntent().getStringExtra("url");
        autoUrl = getIntent().getStringExtra("autoUrl");
        ((TextView) findViewById(R.id.title)).setText(sayadawName);
        // v6.04: chooser header portrait — Main passes this sayadaw's
        // photo in the intent (same extra Room gets), so no new
        // parse; a missing/empty extra falls back to the catalog
        // map (v6.01 delegate), and an empty URL still lands the
        // sayadaw's own gold initial circle via ImageLoader, never
        // a guessed face. Loaded once in onCreate — the chooser's
        // sayadaw never changes, so the 1s strip tick stays out of
        // it, and a rotation rebind hits the portrait cache.
        try {
            String headerPhoto = getIntent().getStringExtra("photo");
            if (headerPhoto == null || headerPhoto.length() == 0)
                headerPhoto = AudioService.stripPhotoUrlForSayadaw(TalksActivity.this, sayadawName);
            android.view.View hp = findViewById(R.id.talksHeaderPhoto);
            if (hp != null) ImageLoader.load(TalksActivity.this,
                    headerPhoto == null ? "" : headerPhoto, sayadawName,
                    (CircleImageView) hp);
        } catch (Exception ignored) {}
        // v2.16: premium title entrance — the sayadaw-name title used
        // to appear instantly while its rows poured in below (v2.07
        // cascade), so the screen opened top-dead. On a fresh open
        // only, the title now fades + rises in first (same Fx
        // entrance family as the Room panel since v2.15), ahead of
        // the row cascade that follows once the list loads.
        // Rotations (saved state) never replay it, and applyTheme()
        // below touches only colour/size, never alpha/translation,
        // so the entrance ends cleanly at full opacity.
        if (savedInstanceState == null) Fx.entrance(findViewById(R.id.talksHeaderRow)); // v6.04: photo + title rise together
        // v4.12: tap the sayadaw-name title to glide back to the top
        // of a long talk list (same tap-to-top as Main's app name).
        // No new view, no mark, rows stay number + title only; an
        // empty or not-yet-loaded list is a no-op, and the tap blip
        // is the shared Fx.tap every other tap already uses.
        findViewById(R.id.title).setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                Fx.tap(TalksActivity.this);
                if (talkList != null) talkList.smoothScrollToPosition(0);
            }
        });
        // v4.15: long-press the sayadaw title jumps back to the
        // playing talk (tap still goes to the top, v4.12) — the
        // fresh-open landing (v3.91) only fires once, so after
        // scrolling away the playing row was unreachable without
        // hunting. Guards mirror the fresh-open match exactly
        // (live Room session + this sayadaw + URL in this list);
        // anything else toasts instead of scrolling nowhere.
        findViewById(R.id.title).setOnLongClickListener(new android.view.View.OnLongClickListener() {
            @Override public boolean onLongClick(android.view.View v) {
                // v10.43: bound-trackless stop — liveSession stays true but the mirror is false, so toast instead of scrolling to a stopped talk.
                if (talkList == null || talks.isEmpty() || !AudioService.liveSession || !AudioService.liveHasTrack) {
                    android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "no_talk_playing"), android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
                try {
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    if (!"room".equals(sp.getString("lastKind", ""))
                            || sayadawName == null
                            || !sayadawName.equals(sp.getString("lastName", ""))) {
                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "no_talk_playing"), android.widget.Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    String liveUrl = sp.getString("lastUrl", "");
                    for (int i = 0; i < talks.size(); i++) {
                        if (talks.get(i).url.equals(liveUrl)) {
                            Fx.tap(TalksActivity.this);
                            clearSearch(); // v4.93: glide target is a full-list index
                            clearFilter(); // v4.94: same rule for the filter view
                            // v12.85: glide straight to the full-list index — the
                            // queue-only exit is deleted (no such view any more).
                            talkList.smoothScrollToPosition(i);
                            return true;
                        }
                    }
                } catch (Exception ignored) {}
                android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "no_talk_playing"), android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        applyTheme();
        wireNowStrip(); // v4.31: real mini-player below the list
        progress = findViewById(R.id.progress);
        ListView list = findViewById(R.id.list);
        talkList = list;
        adapter = new TalkAdapter();
        list.setAdapter(adapter);
        wireSearchBox(); // v4.93: talk title search under the header
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Fx.tap(TalksActivity.this);
                // v5.77: drop tap for an armed pick-up/drop move —
                // intercepted BEFORE the morph/play path, so a drop
                // never also plays the tapped talk, mirroring the
                // Room drawer's v4.92 interception. Tapping the held
                // row itself cancels; tapping a non-queued row keeps
                // the arm and says which rows are droppable; a held
                // URL gone from the queue (cleared or removed from
                // another menu, or re-synced away on return) clears
                // the stale arm instead of dropping a ghost. The
                // drop inserts the held URL immediately BEFORE the
                // tapped row's URL (index measured after the
                // removal), so the result reads exactly as "put it
                // in front of this one" in full-list, filter and
                // search views alike, since all resolve
                // through talkIndexAtDisplay. The header's Up Next
                // line rebinds here too (the queue head can move);
                // the queue preview above the strip refreshes on
                // the next 1s tick, like every other queue change.
                if (moveHeldUrl != null) {
                    final int dropIdx = talkIndexAtDisplay(pos);
                    if (dropIdx < 0 || dropIdx >= talks.size()) return;
                    if (!talkQueue.contains(moveHeldUrl)) {
                        moveHeldUrl = null;
                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return;
                    }
                    String tappedUrl = talks.get(dropIdx).url;
                    if (tappedUrl.equals(moveHeldUrl)) {
                        moveHeldUrl = null;
                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "move_cancelled"), android.widget.Toast.LENGTH_SHORT).show();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return;
                    }
                    // v11.22: a held talk that started playing since
                    // the arm (strip Next, auto-advance, a Room
                    // started elsewhere) must not drop anywhere —
                    // the arm dies with a queue_changed toast.
                    if ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && moveHeldUrl.equals(talkPlayingUrl))
                            || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && moveHeldUrl.equals(talkPlayingLibUrl))) {
                        moveHeldUrl = null;
                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return;
                    }
                    if (!talkQueue.contains(tappedUrl)) {
                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_tap_only"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int heldIdx = -1;
                    for (int i = 0; i < talks.size(); i++)
                        if (talks.get(i).url.equals(moveHeldUrl)) { heldIdx = i; break; }
                    String heldTitle = heldIdx >= 0 ? talks.get(heldIdx).title : "";
                    talkQueue.remove(moveHeldUrl);
                    talkQueue.add(talkQueue.indexOf(tappedUrl), moveHeldUrl);
                    moveHeldUrl = null;
                    saveTalkQueueKeepingForeign(talkQueue, (String) null);
                    refreshQueueMarks();
                    if (!talks.isEmpty() && sayadawName != null) refreshFavHeader();
                    android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "moved_item") + heldTitle, android.widget.Toast.LENGTH_SHORT).show();
                    if (adapter != null) adapter.notifyDataSetChanged();
                    return;
                }
                // v2.38: talk-row click beat — the row squashed under
                // the finger (v2.08 press) but a tap landing on it had
                // no beat of its own, unlike nav (v2.36), Settings
                // choices (v2.37) and the favourite star (v2.28), so
                // the app's most-tapped action — opening a talk —
                // felt flatter than every other tap. It now pops with
                // the shared Fx.morph beat (v2.09) before openRoom's
                // Fx.go navigation: the press squash's UP spring-back
                // is replaced by the morph exactly as on nav, and the
                // beat reads through the transition because the
                // chooser stays in the back stack while it fades out
                // (nav_out 250ms) under the Room's fade-in (300ms).
                // openRoom guards the index, so a valid row always
                // navigates; programmatic auto-opens (no tap) skip it.
                // v12.28: row-tap stack prevention — a rapid
                // double-tap (or two rows tapped in quick succession)
                // fired openRoom twice while the first Room was still
                // starting (nav_out 250ms + nav_in 300ms), stacking
                // two RoomActivity instances: Back from the top Room
                // showed the second Room instead of the list, and
                // each Room could start its own playback session.
                // The strip's Now/Next/Prev taps already reuse the
                // Room with CLEAR_TOP|SINGLE_TOP (v11.86); the row
                // path has no such flag, so the guard is a 700ms
                // window on the tap site only — a dropped tap is just
                // retapped, the window self-heals, and programmatic
                // auto-opens (rotation restore, fresh-load auto-open)
                // call openRoom directly and never pass it. An
                // invalid tap never consumes the window (index
                // resolved first); a move-held drop tap returns above
                // and never reaches it.
                int ti28 = talkIndexAtDisplay(pos);
                if (ti28 < 0 || ti28 >= talks.size()) return;
                long now28 = android.os.SystemClock.uptimeMillis();
                if (now28 - lastRowTapOpenMs < 700) return;
                lastRowTapOpenMs = now28;
                Fx.morph(v);
                // pos is a display position — it maps back to a talk
                // index first (talkIndexAtDisplay).
                openRoom(ti28);
            }
        });
        // v4.14: long-press a talk row to share it (sayadaw + title
        // + original dhammadownload.com link, same text and chooser
        // title as the Room drawer's long-press share) — the chooser
        // previously had no share path at all, only the Room drawer
        // did, so sharing a talk meant opening it first. No new
        // button: rows stay number + title only. suppressSharePause
        // mirrors the Room's suppressBgPause exactly (10s delayed
        // background check while the chooser is up, cleared on
        // return), so a slow share pick never reads as leaving
        // under background-playback-off.
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                // v4.56: display position → talk index (see tap).
                int ti = talkIndexAtDisplay(pos);
                if (ti < 0 || ti >= talks.size()) return false;
                Talk t = talks.get(ti);
                // v4.20: the chooser share was silent, like the Room
                // drawer's long-press share — every other long-press
                // blips, so this one blips before the chooser opens.
                Fx.tap(TalksActivity.this);
                // v7.84: move-to-end parity with the Room drawer
                // (v6.86) — the v7.83 chooser move could only drop
                // BEFORE a tapped row, so a talk could never reach
                // the very end from the browsing screen (tap held
                // = cancel, long-press while armed = share). While
                // a move is armed, long-pressing the HELD row now
                // drops it at the end instead: cancel still exists
                // via tapping the held row, and share stays one
                // long-press away on any unarmed row. talkQueue
                // holds bare URLs here, so remove + append is the
                // whole move (no v2 line to preserve, unlike the
                // Room's foreign lines); a stale arm clears with
                // the same "queue changed" toast the dialog gates
                // use, never a ghost drop.
                if (moveHeldUrl != null && t.url != null && t.url.equals(moveHeldUrl)) {
                    // v11.22: a held talk that started playing since
                    // the arm must not move to the end either — the
                    // arm dies with a queue_changed toast, like the
                    // stale-arm branch below it.
                    if ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && moveHeldUrl.equals(talkPlayingUrl))
                            || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && moveHeldUrl.equals(talkPlayingLibUrl))) {
                        moveHeldUrl = null;
                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return true;
                    }
                    if (!talkQueue.contains(moveHeldUrl)) {
                        moveHeldUrl = null;
                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return true;
                    }
                    String movedUrl86 = moveHeldUrl;
                    talkQueue.remove(moveHeldUrl);
                    talkQueue.add(moveHeldUrl);
                    moveHeldUrl = null;
                    saveTalkQueueKeepingForeign(talkQueue, (String) null);
                    // v11.86: the funnel's anchor-preserving carry (v10.98) re-inserts
                    // a waiting foreign line right after its nearest surviving
                    // predecessor — when that anchor is the moved talk, the foreign line
                    // lands AFTER it in the saved queue, so "moved to end" was not the
                    // end and the Room played the foreign talk next. Move the held line
                    // to the very end of the saved queue, after every carried foreign
                    // line. Count-preserving pure reorder; the funnel's card refresh
                    // above still reads true.
                    if (movedUrl86 != null) {
                        try {
                            String savedQ86 = Prefs.savedQueue(TalksActivity.this, sayadawName);
                            if (savedQ86 != null && savedQ86.length() > 0) {
                                java.util.ArrayList<String> lines86 = new java.util.ArrayList<String>();
                                for (String ln86 : savedQ86.split("\n"))
                                    if (ln86.length() > 0) lines86.add(ln86);
                                int mvIdx86 = -1;
                                for (int i86 = 0; i86 < lines86.size(); i86++) {
                                    QueueEntry e86 = QueueEntry.parse(lines86.get(i86));
                                    if (e86 != null && e86.url.equals(movedUrl86)) { mvIdx86 = i86; break; }
                                }
                                if (mvIdx86 >= 0 && mvIdx86 < lines86.size() - 1) {
                                    String mv86 = lines86.remove(mvIdx86);
                                    lines86.add(mv86);
                                    Prefs.saveQueue(TalksActivity.this, sayadawName, lines86);
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    refreshQueueMarks();
                    if (!talks.isEmpty() && sayadawName != null) refreshFavHeader();
                    if (adapter != null) adapter.notifyDataSetChanged();
                    android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "moved_to_end"), android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
                // v7.82: chooser long-press queue dialog — the
                // browsing-side half of the v7.81 builder gap.
                // The Room drawer got its builder back in v7.81,
                // but a listener browsing Talks still could only
                // share from a long-press: the chooser ⋮ (v4.47)
                // is gone, the preview menu with it, and no other
                // chooser path writes the hand queue, so a queue
                // could only be built after opening the Room.
                // This restores building WITHOUT resurrecting any
                // removed item: no ⋮ menu, no new view, no new
                // button — the existing long-press gesture opens
                // an AlertDialog in the v7.81 Room shape, share
                // first so muscle memory is preserved. Add
                // appends the bare URL (in-list lines are bare by
                // convention, so marks, download-queue and the
                // foreign-keeping save all read it); remove is the
                // chooser's missing single-remove (only the Room
                // drawer had one since v7.81); play-next inserts
                // before the first waiting entry (v7.16 shape,
                // target computed after removal, append when
                // nothing waits) and is offered only while a Room
                // session of this sayadaw is actually playing.
                // The sounding talk keeps the direct share below
                // (adding it would recreate the v5.82
                // sounding-in-queue state), and an armed
                // pick-up/drop move keeps its old share path —
                // its drop targets are taps, not dialogs.
                // talkIndexAtDisplay already resolves full-list,
                // search/filter rows alike.
                // v11.22: gate also excludes the library-sounding talk
                // (talkPlayingLibUrl, the dlMap reverse lookup) — the
                // old gate read only talkPlayingUrl, which stays empty
                // for library sessions, so a downloaded file now
                // playing offered queue-add/play-next/move for itself
                // (add/play-next were saved only by their tap-time
                // re-checks; move had none). A sounding talk now
                // falls through to the direct-share path, like a
                // Room session's sounding talk already did.
                if (moveHeldUrl == null && t.url != null
                        && (talkPlayingUrl == null || !t.url.equals(talkPlayingUrl))
                        && (talkPlayingLibUrl == null || !t.url.equals(talkPlayingLibUrl))) {
                    final Talk dt = t;
                    java.util.ArrayList<String> dOpts = new java.util.ArrayList<String>();
                    dOpts.add(Lang.t(TalksActivity.this, "menu_share"));
                    // v7.88: per-talk favourite from the browsing screen — the
                    // same builder gap as the v7.81/v7.82 queue builders:
                    // favTalks could only ever be toggled for the SOUNDING
                    // talk (strip star, notification card, Room altar
                    // long-press) or in bulk in the library, so a listener
                    // browsing Talks could never favourite a talk it had
                    // not played yet, even though the ★ marks, the header
                    // ★ count and the download-favourites verbs all read
                    // this set. The verb sits in the existing dialog (no
                    // new view, no ⋮ resurrected), share still first, and
                    // the state is read fresh here so a strip-star toggle
                    // a second earlier never shows a stale label. The
                    // sounding talk keeps its direct share below (its
                    // star lives on the strip) and an armed move keeps
                    // its old share path, as before.
                    boolean dFav = false;
                    try {
                        java.util.Set<String> ft88 = getSharedPreferences("dhamma", MODE_PRIVATE).getStringSet("favTalks", null);
                        dFav = ft88 != null && dt.url != null && ft88.contains(dt.url);
                    } catch (Exception ignored) {}
                    dOpts.add(dFav ? Lang.t(TalksActivity.this, "menu_fav_remove") : Lang.t(TalksActivity.this, "menu_fav_add"));
                    final boolean dQueued = talkQueue.contains(dt.url);
                    if (dQueued) dOpts.add(Lang.t(TalksActivity.this, "menu_queue_remove"));
                    else dOpts.add(Lang.t(TalksActivity.this, "menu_queue_add"));
                    // v13.04: "queue all from here" — the Downloaded
                    // library's bulk verb (menu_queue_from_here),
                    // missing from this dialog since the v7.32 ⋮
                    // removal (the v7.82 rebuild restored add /
                    // remove / move / play-next but not this one):
                    // queues this talk and every shown talk below
                    // it, in display order, so a listener can queue
                    // a whole run without opening the Room first.
                    // Offered on any non-sounding row (the dialog
                    // gate already excludes the sounding talk);
                    // already-queued rows below are skipped by the
                    // handler, like the library verb.
                    dOpts.add(Lang.t(TalksActivity.this, "menu_queue_from_here"));
                    // v7.83: re-arm the dead pick-up/drop move — the
                    // chooser ⋮ removal deleted its only setter, so
                    // Talks moveHeldUrl could only ever be restored
                    // by rotation, never armed: the v5.77 drop tap,
                    // the ✋ held-row mark and the save/restore were
                    // all dead code. A queued row now offers "move"
                    // here (no ⋮ resurrected, no new view): arming
                    // paints the ✋ mark via notifyDataSetChanged,
                    // and the existing drop tap moves it before the
                    // tapped queued row, exactly as the Room drawer
                    // does — so a queue can now be reordered from
                    // the browsing screen, not only after opening
                    // the Room. Only a queued, non-sounding row
                    // offers it (the dialog gate already excludes
                    // the sounding talk, and an unqueued row has
                    // nothing to pick up); while armed, long-press
                    // keeps its old share path, as before.
                    if (dQueued) dOpts.add(Lang.t(TalksActivity.this, "menu_move"));
                    String dFw = null;
                    if (talkPlayingUrl != null && talkPlayingUrl.length() > 0) {
                        for (String q : talkQueue)
                            if (!q.equals(talkPlayingUrl)) { dFw = q; break; }
                    }
                    // v11.64: foreign-aware play-next offer — the header
                    // already names a waiting foreign head as Up Next
                    // (v7.22), but this gate read only the in-list
                    // queue, so a queued talk that was first in-list
                    // waiting got NO play-next offer while a foreign
                    // talk was truly next. A waiting foreign head
                    // (not this row itself, the v7.39 in-list-foreign
                    // corner keeps the old gate) now offers it too.
                    QueueEntry dFxGate = foreignQueueHead();
                    if (talkPlayingUrl != null && talkPlayingUrl.length() > 0
                            && (!dQueued || (dFxGate != null && !dFxGate.url.equals(dt.url))
                            || (dFw != null && !dFw.equals(dt.url))))
                        dOpts.add(dQueued ? Lang.t(TalksActivity.this, "menu_play_next") : Lang.t(TalksActivity.this, "menu_add_next"));
                    // v11.70: per-talk download from the browsing screen --
                    // the only download verb left in the app was the Room
                    // data-gate Wi-Fi button (mobile data + data saver
                    // only), so on Wi-Fi a talk could not be downloaded
                    // at all. This restores it WITHOUT resurrecting any
                    // removed item -- no new view, no new button, no row
                    // menu, no header button -- the existing long-press
                    // dialog (v7.82/v7.88 shape) gains one option, the
                    // existing dl_btn_download key, offered only for a
                    // talk that is neither downloaded nor already queued.
                    if (!isTalkDownloaded(dt.url) && !talkIsDlQueued(dt.url))
                        dOpts.add(Lang.t(TalksActivity.this, "dl_btn_download"));
                    final String[] dOptArr = dOpts.toArray(new String[0]);
                    new android.app.AlertDialog.Builder(TalksActivity.this)
                            .setTitle(dt.title != null && dt.title.length() > 0 ? dt.title : dt.url)
                            .setItems(dOptArr, new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int which) {
                                    if (dOptArr[which].equals(Lang.t(TalksActivity.this, "menu_share"))) {
                                        suppressSharePause = true;
                                        Intent send = new Intent(Intent.ACTION_SEND);
                                        send.setType("text/plain");
                                        send.putExtra(Intent.EXTRA_TEXT, (sayadawName == null ? "" : sayadawName) + " — " + dt.title
                                                + "\n" + dt.url + Lang.t(TalksActivity.this, "share_source"));
                                        startActivity(Intent.createChooser(send, Lang.t(TalksActivity.this, "share_talk")));
                                    } else if (dOptArr[which].equals(Lang.t(TalksActivity.this, "menu_fav_add")) || dOptArr[which].equals(Lang.t(TalksActivity.this, "menu_fav_remove"))) {
                                        // v7.88: toggle by URL, not by the label built
                                        // above — if the set changed between the
                                        // dialog opening and the tap, the toast
                                        // still reports the true new state. The
                                        // row marks (★) and the header ★ count
                                        // refresh from the same fresh read.
                                        boolean added88 = Fx.toggleFavTalk(TalksActivity.this, dt.url);
                                        // v10.03: paused-card star sync, Talks leg of v10.02 —
                                        // the card reads favTalks at rebuild and rebuilds only
                                        // every 15s while playing (never while paused), so a
                                        // paused card kept the old star after a fav flip here.
                                        // Talks never binds, so the flip reaches the service
                                        // as ACTION_REFRESH_NOTIFICATION (same no-bind shape
                                        // as the strip's ACTION_STOP / ACTION_TOGGLE). Guard
                                        // reads prefs fresh at tap time, NOT talkPlayingUrl
                                        // (onCreate snapshot): room = flipped url == lastUrl;
                                        // library = dlMap[flipped url] file == lastLibPath file.
                                        // The dialog gate excludes the onCreate-time sounding
                                        // talk, so this fires only when the sounding talk
                                        // changed after Talks opened (strip Next, auto-advance,
                                        // Room started elsewhere) — the stale case. A flip for
                                        // any other talk sends nothing.
                                        try {
                                            android.content.SharedPreferences rsp =
                                                    getSharedPreferences("dhamma", MODE_PRIVATE);
                                            boolean cardTalk = dt.url != null && dt.url.equals(rsp.getString("lastUrl", ""));
                                            if (!cardTalk && dt.url != null) {
                                                String libPath = rsp.getString("lastLibPath", "");
                                                if (libPath.length() > 0) {
                                                    String fn = new org.json.JSONObject(rsp.getString("dlMap", "{}")).optString(dt.url, "");
                                                    cardTalk = fn.length() > 0 && fn.equals(new java.io.File(libPath).getName());
                                                }
                                            }
                                            if (cardTalk) {
                                                android.content.Intent rsv = new android.content.Intent(TalksActivity.this, AudioService.class);
                                                rsv.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                                                startService(rsv);
                                            }
                                        } catch (Exception ignored) {}
                                        refreshTalkRowMarks(true);
                                        if (!talks.isEmpty() && sayadawName != null) refreshFavHeader();
                                        // v10.88: the menu's own mark refresh above reloads
                                        // talkFavCache synchronously, so the 1s tick's
                                        // change detector (v7.55) snapshots the NEW cache
                                        // and sees no change — an unstarred talk stayed
                                        // visible in the ★-only view (mark gone, row kept)
                                        // until a return, a filter tap or a load, while
                                        // a notification-card flip heals via that same
                                        // tick because Talks never refreshed its cache
                                        // first. Rebuild the cached filter here, before
                                        // the notify, so the row leaves the view at once
                                        // (and a filter emptied this way shows the existing
                                        // empty word, exactly as the tick rebuild does);
                                        // mode 0 is a no-op rebuild, and the Room drawer's
                                        // live filter needs no such call.
                                        if (filterMode != 0) rebuildFilter();
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, added88 ? "fav_added" : "fav_removed"), android.widget.Toast.LENGTH_SHORT).show();
                                    } else if (dOptArr[which].equals(Lang.t(TalksActivity.this, "menu_queue_remove"))) {
                                        if (!talkQueue.contains(dt.url)) {
                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        talkQueue.remove(dt.url);
                                        saveTalkQueueKeepingForeign(talkQueue, (String) null);
                                        refreshQueueMarks();
                                        if (!talks.isEmpty() && sayadawName != null) refreshFavHeader();
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "removed_from_queue"), android.widget.Toast.LENGTH_SHORT).show();
                                    } else if (dOptArr[which].equals(Lang.t(TalksActivity.this, "menu_queue_add"))) {
                                        // v11.14: tap-time sounding re-check — the mirror of the v11.12
                                        // play-next leg in the else branch below, which this append
                                        // verb never got: the offer gate ran at dialog-build time,
                                        // but the 1s tick refreshes talkPlayingUrl / talkPlayingLibUrl
                                        // while the dialog sits open, so a row offered while waiting
                                        // can be sounding by tap time (strip Next, auto-advance, a
                                        // Room started elsewhere). Appending it then leaves the
                                        // sounding talk in the queue to replay after the next talk.
                                        // A hit toasts "queue changed" and returns before any
                                        // mutation, exactly like the play-next leg.
                                        if ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && dt.url.equals(talkPlayingUrl))
                                                || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && dt.url.equals(talkPlayingLibUrl))) {
                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        if (talkQueue.contains(dt.url)) {
                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        talkQueue.add(dt.url);
                                        saveTalkQueueKeepingForeign(talkQueue, dt.url);
                                        refreshQueueMarks();
                                        if (!talks.isEmpty() && sayadawName != null) refreshFavHeader();
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "added_to_queue"), android.widget.Toast.LENGTH_SHORT).show();
                                    } else if (dOptArr[which].equals(Lang.t(TalksActivity.this, "menu_queue_from_here"))) {
                                        // v13.04: queue this talk and every shown talk
                                        // below it, in display order (visibleHits when a
                                        // search/filter is active, else the full list —
                                        // the same mapping talkIndexAtDisplay uses, so
                                        // a narrowed list queues exactly what is on
                                        // screen). The library verb's rules: skip the
                                        // sounding talk (re-checked at tap time, the
                                        // v11.14 window — the dialog can sit open
                                        // across an auto-advance) and already-queued
                                        // talks (they stay where they are); the keep
                                        // set carries every new URL through the
                                        // funnel's stale drop, or the funnel would
                                        // drop them as not-yet-saved.
                                        int added04 = 0;
                                        java.util.Set<String> keep04 = new java.util.HashSet<String>();
                                        int dispCount04 = visibleHits != null ? visibleHits.size() : talks.size();
                                        for (int dp04 = pos; dp04 < dispCount04; dp04++) {
                                            int ti04 = talkIndexAtDisplay(dp04);
                                            if (ti04 < 0 || ti04 >= talks.size()) continue;
                                            Talk t04 = talks.get(ti04);
                                            if (t04 == null || t04.url == null || t04.url.length() == 0) continue;
                                            if ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && t04.url.equals(talkPlayingUrl))
                                                    || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && t04.url.equals(talkPlayingLibUrl))) continue;
                                            if (talkQueue.contains(t04.url)) continue;
                                            talkQueue.add(t04.url);
                                            keep04.add(t04.url);
                                            added04++;
                                        }
                                        saveTalkQueueKeepingForeign(talkQueue, keep04);
                                        refreshQueueMarks();
                                        if (!talks.isEmpty() && sayadawName != null) refreshFavHeader();
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        android.widget.Toast.makeText(TalksActivity.this, added04 > 0
                                                ? Lang.t(TalksActivity.this, "queue_plain_prefix") + added04 + Lang.t(TalksActivity.this, "queue_added_suffix")
                                                : Lang.t(TalksActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
                                    } else if (dOptArr[which].equals(Lang.t(TalksActivity.this, "dl_btn_download"))) {
                                        // v11.70: tap-time re-checks, the v11.14/v11.12
                                        // pattern -- the offer gate ran at dialog-build
                                        // time, but a Wi-Fi job can land, or another
                                        // path can queue this talk, while the dialog
                                        // sits open. A landed file refreshes the marks
                                        // and toasts its downloaded line instead of
                                        // enqueueing a second job for a file already
                                        // on the phone; a queued talk toasts
                                        // "already queued" (the Room gate's v10.73
                                        // guard, in chooser form).
                                        if (isTalkDownloaded(dt.url)) {
                                            refreshTalkRowMarks(true);
                                            if (adapter != null) adapter.notifyDataSetChanged();
                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "downloaded_talk"), android.widget.Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        if (talkIsDlQueued(dt.url)) {
                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "dl_already_queued"), android.widget.Toast.LENGTH_LONG).show();
                                            return;
                                        }
                                        String file70 = talkDownloadFileNameFor(dt.url);
                                        try {
                                            android.app.DownloadManager dm70 = (android.app.DownloadManager)
                                                    getSystemService(android.content.Context.DOWNLOAD_SERVICE);
                                            android.app.DownloadManager.Request req70 =
                                                    new android.app.DownloadManager.Request(android.net.Uri.parse(dt.url));
                                            req70.setTitle(dt.title);
                                            req70.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                                            // Wi-Fi-only, exactly like the Room gate
                                            // and the strip enqueue -- on Wi-Fi it
                                            // starts at once, on mobile data it waits
                                            // for Wi-Fi, so a tap here can never
                                            // silently spend mobile data.
                                            req70.setAllowedNetworkTypes(android.app.DownloadManager.Request.NETWORK_WIFI);
                                            req70.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "Dhamma/" + file70);
                                            dm70.enqueue(req70);
                                            recordTalkDownload(dt.url, file70);
                                        } catch (Exception e) {
                                            android.widget.Toast.makeText(TalksActivity.this,
                                                    Lang.t(TalksActivity.this, "download_failed") + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                                            return;
                                        }
                                        // The hourglass at once: refreshTalkRowMarks(true)
                                        // rebuilds the queued cache from the prefs
                                        // just written and rebinds (the v4.01 Room
                                        // shape); the prune inside it keeps the
                                        // fresh entry -- the job just enqueued is
                                        // PENDING with this URL as its source, so
                                        // the live-source leg holds it.
                                        refreshTalkRowMarks(true);
                                        if (!talks.isEmpty() && sayadawName != null) refreshFavHeader();
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        if (Fx.stripIsWifi(TalksActivity.this))
                                            android.widget.Toast.makeText(TalksActivity.this,
                                                    "\u2B07 " + (dt.title != null && dt.title.length() > 0 ? dt.title : dt.url) + Lang.t(TalksActivity.this, "dl_downloading_suffix"),
                                                    android.widget.Toast.LENGTH_SHORT).show();
                                        else
                                            android.widget.Toast.makeText(TalksActivity.this,
                                                    Lang.t(TalksActivity.this, "dl_room_wifi_queued"), android.widget.Toast.LENGTH_LONG).show();
                                    } else if (dOptArr[which].equals(Lang.t(TalksActivity.this, "menu_move"))) {
                                        // v11.22: tap-time sounding re-check, the
                                        // move branch's missing v11.12/v11.14 leg —
                                        // both sounding forms (Room URL and
                                        // library reverse-lookup URL), before any
                                        // arm, exactly like its neighbouring verbs.
                                        if ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && dt.url.equals(talkPlayingUrl))
                                                || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && dt.url.equals(talkPlayingLibUrl))) {
                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        if (!talkQueue.contains(dt.url)) {
                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        moveHeldUrl = dt.url;
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "talks_move_prefix") + (dt.title != null && dt.title.length() > 0 ? dt.title : dt.url) + Lang.t(TalksActivity.this, "talks_move_suffix"), android.widget.Toast.LENGTH_SHORT).show();
                                    } else {
                                        // v11.12: tap-time sounding re-check — the chooser
                                        // dialog's gate ran at open time against the then
                                        // sounding talk, but the 1s tick refreshes
                                        // talkPlayingUrl / talkPlayingLibUrl while the dialog
                                        // sits open, so a row offered while waiting can be
                                        // sounding by tap time (strip Next, auto-advance, a
                                        // Room started elsewhere). Re-read the tick-fresh
                                        // pair here, before any mutation, and leave the
                                        // queue untouched on a hit — the Room drawer's
                                        // v11.12 tap-time legs, in chooser form (Talks never
                                        // binds, so the bound-service check is unavailable).
                                        if ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && dt.url.equals(talkPlayingUrl))
                                                || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && dt.url.equals(talkPlayingLibUrl))) {
                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        // v11.86: still-playing re-check — the gate offered
                                        // play-next while a Room session was sounding, but the
                                        // session can stop (notification stop, service death)
                                        // while the dialog sits open: with talkPlayingUrl empty,
                                        // fw below stays null and target falls through to
                                        // talkQueue.size(), so the talk landed LAST while the
                                        // toast still claimed "set as next" (and the header's
                                        // nxTalk never named it, since it too needs a live
                                        // talkPlayingUrl). Leave the queue untouched on a stale
                                        // dialog, like the sounding re-check above.
                                        if (talkPlayingUrl == null || talkPlayingUrl.length() == 0) {
                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        // play-next: already the first waiting entry
                                        // means nothing to move; otherwise remove
                                        // first (already-queued case) and insert
                                        // before the first waiting entry, or append.
                                        // v11.64: foreign-aware — a waiting foreign
                                        // head (v7.08) is truly next, so a first
                                        // in-list waiting row is NOT already next
                                        // while one waits (unless this row IS that
                                        // head's URL, the v7.39 in-list-foreign
                                        // corner, where the old answer stands).
                                        // Captured before any mutation: the funnel
                                        // below re-spells and re-anchors lines.
                                        QueueEntry dFxHead = foreignQueueHead();
                                        String fwBefore = null;
                                        if (talkPlayingUrl != null && talkPlayingUrl.length() > 0) {
                                            for (String q : talkQueue)
                                                if (!q.equals(talkPlayingUrl)) { fwBefore = q; break; }
                                        }
                                        if (fwBefore != null && fwBefore.equals(dt.url)
                                                && (dFxHead == null || dFxHead.url.equals(dt.url))) {
                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "already_next"), android.widget.Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        boolean wasQueued = talkQueue.contains(dt.url);
                                        if (wasQueued) talkQueue.remove(dt.url);
                                        String fw = null;
                                        if (talkPlayingUrl != null && talkPlayingUrl.length() > 0) {
                                            for (String q : talkQueue)
                                                if (!q.equals(talkPlayingUrl)) { fw = q; break; }
                                        }
                                        int target = talkQueue.size();
                                        if (fw != null) {
                                            int ti2 = talkQueue.indexOf(fw);
                                            if (ti2 >= 0) target = ti2;
                                        }
                                        talkQueue.add(target, dt.url);
                                        saveTalkQueueKeepingForeign(talkQueue, dt.url);
                                        // v11.64: the funnel anchors a waiting
                                        // foreign head ahead of every in-list line,
                                        // so dt landed BEHIND it even though the
                                        // toast below says "set as next" — move
                                        // dt's saved line immediately before the
                                        // head's line, so the Room's first-waiting
                                        // scan (sounding skipped, foreign head
                                        // first) really names dt next. No-op when
                                        // dt already precedes it (anchor behind an
                                        // in-list line) or when dt IS the head's
                                        // URL (v7.39 corner). Count-preserving
                                        // pure reorder, so the funnel's card
                                        // refresh above still reads true.
                                        if (dFxHead != null && !dFxHead.url.equals(dt.url)) {
                                            try {
                                                String savedQ64 = Prefs.savedQueue(TalksActivity.this, sayadawName);
                                                if (savedQ64 != null && savedQ64.length() > 0) {
                                                    java.util.ArrayList<String> lines64 = new java.util.ArrayList<String>();
                                                    for (String ln64 : savedQ64.split("\n"))
                                                        if (ln64.length() > 0) lines64.add(ln64);
                                                    int fxIdx64 = -1, dtIdx64 = -1;
                                                    for (int i64 = 0; i64 < lines64.size(); i64++) {
                                                        QueueEntry e64 = QueueEntry.parse(lines64.get(i64));
                                                        if (e64 == null) continue;
                                                        if (fxIdx64 < 0 && e64.url.equals(dFxHead.url)) fxIdx64 = i64;
                                                        if (dtIdx64 < 0 && e64.url.equals(dt.url)) dtIdx64 = i64;
                                                    }
                                                    if (fxIdx64 >= 0 && dtIdx64 > fxIdx64) {
                                                        String mv64 = lines64.remove(dtIdx64);
                                                        lines64.add(fxIdx64, mv64);
                                                        Prefs.saveQueue(TalksActivity.this, sayadawName, lines64);
                                                    }
                                                }
                                            } catch (Exception ignored) {}
                                        }
                                        refreshQueueMarks();
                                        if (!talks.isEmpty() && sayadawName != null) refreshFavHeader();
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "set_as_next"), android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                }
                            })
                            .show();
                    return true;
                }
                suppressSharePause = true;
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, (sayadawName == null ? "" : sayadawName) + " — " + t.title
                        + "\n" + t.url + Lang.t(TalksActivity.this, "share_source"));
                startActivity(Intent.createChooser(send, Lang.t(TalksActivity.this, "share_talk")));
                return true;
            }
        });
        findViewById(R.id.btnRetry).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v2.84: retry click beat — the last tappable control
                // without one (nav v2.36, Settings v2.37, rows v2.38,
                // grid cards v2.39, library buttons, Room controls
                // all morph already). It pops with the shared
                // Fx.morph beat before loadTalks fades it out
                // (v2.79): morph runs on the scale channel and the
                // fade on its own alpha animator, so neither
                // cancels the other, and the beat reads in the
                // 200ms before the button is gone.
                Fx.morph(v);
                loadTalks();
            }
        });
        // v4.18: long-press the retry button opens this sayadaw's
        // original dhammadownload.com page in the browser (tap
        // still reloads the list) — the button only exists after
        // a failed load, exactly when the listener needs to check
        // whether the site itself is down instead of retrying
        // blind. No page URL, or no browser to take it, toasts
        // instead of crashing.
        findViewById(R.id.btnRetry).setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Fx.tap(TalksActivity.this);
                if (pageUrl == null || pageUrl.length() == 0) {
                    android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "no_orig_page"), android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(pageUrl)));
                } catch (Exception e) {
                    android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "open_failed"), android.widget.Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        // v2.28: press feedback on the retry button — the v2.08 /
        // v2.11 press passes covered rows, cards, Room controls and
        // Settings buttons, but this button (and the Room's) never
        // got one, so a retry tap after a failed load felt dead.
        Fx.press(findViewById(R.id.btnRetry));
        // v1.53: rotating the phone used to wipe the loaded list and
        // refetch it over the network (plus re-fire the resume
        // auto-open after back + rotate, since the intent still carries
        // autoUrl). Restore the list instead; only refetch when there
        // is nothing to restore (first open, or a rotation mid-load).
        boolean restored = false;
        if (savedInstanceState != null && savedInstanceState.containsKey("talkTitles")) {
            ArrayList<String> rt = savedInstanceState.getStringArrayList("talkTitles");
            ArrayList<String> ru = savedInstanceState.getStringArrayList("talkUrls");
            if (rt != null && ru != null && !rt.isEmpty() && rt.size() == ru.size()) {
                for (int i = 0; i < rt.size(); i++) talks.add(new Talk(rt.get(i), ru.get(i)));
                // restored rows must not replay the fade+rise animation
                lastAnimPos = talks.size() - 1;
                refreshQueueMarks();
                // v5.79: restore the armed pick-up/drop move with
                // the queue; the rebind below paints the ✋ mark,
                // and the bind's own queue-membership guard is
                // the second check.
                String mh = savedInstanceState.getString("moveHeldUrl");
                if (mh != null && talkQueue.contains(mh)) moveHeldUrl = mh;
                refreshTalkRowMarks(true); // v5.49: row marks ready before the rebind below
                // v4.93: restore the search query too — setting the
                // box text fires the watcher, which rebuilds the
                // filter before the rebind below; a restored search
                // skips the scroll restore (positions are filtered).
                // v4.94: restore the filter FIRST, so the search
                // watcher's rebuild already intersects with it.
                int savedFilter = savedInstanceState.getInt("talkFilterMode", 0);
                if (savedFilter != 0) {
                    // v11.83: restore the filter only when it still
                    // matches at least one talk — the Room drawer's
                    // filterOk check (process death + favourites /
                    // played cleared in Settings left a filter whose
                    // set was empty; the list itself was right,
                    // only the mode stuck).
                    boolean filterOk = false;
                    try {
                        android.content.SharedPreferences spf =
                                getSharedPreferences("dhamma", MODE_PRIVATE);
                        java.util.Set<String> ft = spf.getStringSet("favTalks", null);
                        java.util.Set<String> pl = spf.getStringSet("playedTalks", null);
                        for (int i = 0; i < talks.size(); i++) {
                            String u = talks.get(i).url;
                            if (savedFilter == 3) { if (isTalkDownloaded(u)) { filterOk = true; break; } }
                            else if (savedFilter == 1) { if (ft != null && ft.contains(u)) { filterOk = true; break; } }
                            else if (pl == null || !pl.contains(u)) { filterOk = true; break; }
                        }
                    } catch (Exception ignored) {}
                    if (filterOk) {
                        filterMode = savedFilter;
                        android.view.View sRowF = findViewById(R.id.talkSearchRow);
                        if (sRowF != null) sRowF.setVisibility(View.VISIBLE);
                        refreshFilterBtn();
                        rebuildFilter();
                    }
                }
                String savedSearch = savedInstanceState.getString("talkSearch", "");
                if (savedSearch != null && savedSearch.length() > 0) {
                    android.view.View sRow = findViewById(R.id.talkSearchRow);
                    if (sRow != null) sRow.setVisibility(View.VISIBLE);
                    if (searchBox != null) searchBox.setText(savedSearch);
                    else { searchQuery = savedSearch; rebuildSearch(); }
                }
                adapter.notifyDataSetChanged();
                // v4.24: rotation restore re-applies the title count
                // (same rule as the fresh-load site above) instead
                // of dropping back to the plain name.
                if (sayadawName != null) refreshFavHeader();
                progress.setVisibility(View.GONE);
                findViewById(R.id.btnRetry).setVisibility(View.GONE);
                // v1.54: rotation used to drop the listener back to the
                // top of a long talk list — keep the scroll position too.
                // v12.85: the saved position is a full-list position
                // (no queue-only view any more), so clamp to the
                // full list's length.
                int scrollPos = savedInstanceState.getInt("scrollPos", 0);
                int scrollLimit = talks.size();
                // v4.93: a restored search starts at the top of its
                // results — the saved position means the full list.
                // v4.94: same rule for a restored filter view.
                if (searchQuery.length() == 0 && filterMode == 0 && scrollPos > 0 && scrollPos < scrollLimit) list.setSelection(scrollPos);
                restored = true;
                // v12.54: restore the return-scroll anchor saved above,
                // so a rotation does not re-glide to the same talk.
                String savedAnchor = savedInstanceState.getString("lastSeenPlayingUrl");
                if (savedAnchor != null) lastSeenPlayingUrl = savedAnchor;
                if (autoUrl != null && autoUrl.length() > 0
                        && !savedInstanceState.getBoolean("autoDone", false)) {
                    String want = autoUrl;
                    autoUrl = null;
                    boolean found = false;
                    for (int i = 0; i < talks.size(); i++) {
                        if (talks.get(i).url.equals(want)) { openRoom(i); found = true; break; }
                    }
                    // v1.82: a saved talk that left the site's list
                    // used to land silently on the list with no word
                    // about why nothing opened — say so instead.
                    if (!found)
                        android.widget.Toast.makeText(TalksActivity.this,
                                Lang.t(TalksActivity.this, "talk_no_longer_in_list"), android.widget.Toast.LENGTH_SHORT).show();
                }
            } else if (savedInstanceState.getBoolean("talkCleanEmpty", false)
                    && rt != null && ru != null && rt.isEmpty() && ru.isEmpty()) {
                // v12.56: restore a clean empty load — the page fetched
                // fine and this sayadaw simply has no talks listed, so
                // there is no list to restore and the non-empty gate
                // above fails; without this branch every rotation
                // refetched the same page and re-toasted "no talks"
                // (the same futile loop v12.31 closed for the retry
                // button). The restored state matches the fresh
                // empty landing exactly: spinner gone, retry gone,
                // search row stays GONE (its layout default), plain
                // sayadaw name (no count), no toast. The flag is
                // kept on this instance too, so a second rotation
                // restores the same way; autoUrl is deliberately
                // untouched — the fresh-load path (v10.84/v1.88)
                // keeps it for a retry/re-entry and never toasts
                // "no longer in list" on an empty load, and this
                // branch must match it, so no auto-open is attempted.
                talkCleanEmpty = true;
                progress.setVisibility(View.GONE);
                findViewById(R.id.btnRetry).setVisibility(View.GONE);
                restored = true;
            }
        }
        if (!restored) loadTalks();
    }

    // v1.53: keep the loaded talk list across rotation (see onCreate).
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        ArrayList<String> titles = new ArrayList<String>();
        ArrayList<String> urls = new ArrayList<String>();
        for (Talk t : talks) { titles.add(t.title); urls.add(t.url); }
        outState.putStringArrayList("talkTitles", titles);
        outState.putStringArrayList("talkUrls", urls);
        // v1.54: keep the scroll position across rotation (see onCreate).
        if (talkList != null) outState.putInt("scrollPos", talkList.getFirstVisiblePosition());
        // autoUrl is null once consumed — saving that stops the
        // rotation-recreated activity from re-opening the Room.
        outState.putBoolean("autoDone", autoUrl == null);
        // v12.56: keep the clean-empty flag too — without it a rotation
        // after a clean empty load sees only an empty saved list, the
        // restore gate fails, and onCreate refetches + re-toasts.
        outState.putBoolean("talkCleanEmpty", talkCleanEmpty);
        // v5.79: the armed pick-up/drop move survives rotation
        // with the queue — restored only when still queued.
        if (moveHeldUrl != null) outState.putString("moveHeldUrl", moveHeldUrl);
        // v4.93: keep the search query across rotation.
        outState.putString("talkSearch", searchQuery);
        // v4.94: keep the filter mode across rotation.
        outState.putInt("talkFilterMode", filterMode);
        // v12.54: keep the return-scroll anchor too — without it a
        // rotation reset lastSeenPlayingUrl to null and the next
        // return re-glided to the same talk (v3.94 anchor rule).
        if (lastSeenPlayingUrl != null) outState.putString("lastSeenPlayingUrl", lastSeenPlayingUrl);
    }

    // v1.81: same background check as the main list (see
    // AppForeground) — a Home press from the chooser used to keep
    // playing even with background-playback-off.
    @Override
    protected void onPause() {
        super.onPause();
        stopTalksStripTick(); // v4.31: never tick a detached strip
        // v10.07: stop listening while hidden — onResume re-registers.
        if (talksDlCompleteRegistered && talksDlCompleteReceiver != null) {
            try { unregisterReceiver(talksDlCompleteReceiver); } catch (Exception ignored) {}
            talksDlCompleteRegistered = false;
        }
        AppForeground.activityPaused();
        // v4.14: share-chooser case gets the Room's 10s delay (see
        // suppressSharePause above); every other leave keeps 600ms.
        if (suppressSharePause) AppForeground.scheduleBackgroundCheck(this, 10000);
        else AppForeground.scheduleBackgroundCheck(this);
        // v2.79: same for a mid-fade-out Talks retry — a leave
        // during the 200ms fade must finish it to its intended
        // GONE state at full alpha, not strand it half-faded; a
        // steady visible retry (a real failure, no fade running)
        // is left alone, so returning to the chooser still offers
        // the retry. Every destroy path passes through onPause,
        // so no separate onDestroy finish is needed.
        if (talksRetryFade != null) {
            boolean wasHiding = talksRetryFadeHiding;
            talksRetryFade.cancel(); talksRetryFade = null; talksRetryFadeHiding = false;
            android.view.View retryPause = findViewById(R.id.btnRetry);
            if (retryPause != null) {
                if (wasHiding) retryPause.setVisibility(View.GONE);
                retryPause.setAlpha(1f);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // v13.08: detach the liquid-glass surfaces — the drawable is
        // the view's background and its callback pins the dead view +
        // its destroyed activity in GlassBlur's static map on every
        // rotation (the v13.05 weak view ref did not fix this).
        GlassBlur.detach(searchBox);
        GlassBlur.detach(findViewById(R.id.nowStrip));
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.nav_in, R.anim.nav_out);
    }

    // Open this talk in the Buddha-altar Room player.
    private void openRoom(int idx) {
        if (idx < 0 || idx >= talks.size()) return;
        Talk t = talks.get(idx);
        Intent i = new Intent(TalksActivity.this, RoomActivity.class);
        i.putExtra("name", sayadawName);
        i.putExtra("group", getIntent().getStringExtra("group"));
        i.putExtra("url", pageUrl);
        i.putExtra("photo", getIntent().getStringExtra("photo"));
        i.putExtra("autoUrl", t.url);
        i.putExtra("autoTitle", t.title);
        Fx.go(TalksActivity.this, i);
    }

    private void applyTheme() {
        boolean night = Prefs.isNight(this);
        cachedNight = night;
        cachedFont = Prefs.fontSize(this);
        Fx.applySystemBars(this, night); // v13.07: bars follow the theme
        cachedLang = Prefs.lang(this);
        View root = findViewById(R.id.root);
        if (root != null) root.setBackgroundResource(night ? R.drawable.bg_gradient_night : R.drawable.bg_gradient_day);
        TextView title = findViewById(R.id.title);
        title.setTextColor(night
                ? android.graphics.Color.parseColor("#C9A227")
                : android.graphics.Color.parseColor("#7A2E1F"));
        int f = Prefs.fontSize(this);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, f == 0 ? 18 : (f == 1 ? 20 : 26));
        // v1.98: the retry button (shown only on load failure) kept
        // its default size while the title beside it scaled — same
        // button step as Settings (14/16/18), re-applied on return
        // with the rest of applyTheme().
        // v2.00: the same button kept the default light-theme face
        // at night — the only light widget on this dark screen,
        // same class as the Settings action buttons fixed in v1.99.
        // Same unselected choice-button colours (dark face + white
        // text at night, surface face + ink text by day). The list
        // divider had the same night gap in the other direction:
        // the layout pins it to light @color/surface, a bright cream
        // stripe between every row on the dark root — dark grey at
        // night, surface by day, re-applied on return with the rest.
        android.widget.Button retry = findViewById(R.id.btnRetry);
        if (retry != null) {
            // v9.90: the chooser retry button was the last retry label still
            // layout-only (Room's roomRetry already reads Lang "retry" via
            // applyLangLabels) — now it follows the UI language too, via
            // the EXISTING key, whose Burmese column is byte-identical to
            // the layout text, so Burmese display is unchanged. applyTheme
            // runs on create and on return, so a language picked in
            // Settings shows here on return.
            retry.setText(Lang.t(this, "retry"));
            retry.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    f == 0 ? 14 : (f == 1 ? 16 : 18));
            retry.setBackgroundResource(night ? R.drawable.glass_btn_night : R.drawable.glass_btn);
            retry.setTextColor(night
                    ? android.graphics.Color.WHITE
                    : android.graphics.Color.parseColor("#3B2314"));
        }
        android.widget.ListView list = findViewById(R.id.list);
        if (list != null) list.setDivider(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        // v4.93: search box follows the theme like Main's — dark face
        // + white text at night, surface face + ink text by day.
        if (searchBox != null) {
            // v13.03: real liquid glass — GlassBlur blurs the content behind this surface.
            GlassBlur.attachSearch(searchBox, night);
            searchBox.setTextColor(night
                    ? android.graphics.Color.WHITE
                    : android.graphics.Color.parseColor("#3B2314"));
            // v9.91: the search hint was set only in wireSearchBox
            // (onCreate), so a language picked in Settings while
            // this chooser sat in the back stack (Main > Talks >
            // Room > Settings > back) left the hint in the OLD
            // language while applyTheme refreshed everything else
            // on return. Same Lang key as wireSearchBox, so the
            // hint at open is byte-identical; on return it now
            // follows the new language like the retry button.
            searchBox.setHint(Lang.t(TalksActivity.this, "talks_search_hint"));
            // v11.53: the search hint kept its layout muted brown
            // (#7A6A58) in both themes, while Main's hint and the
            // Downloaded search hint switch to #AAAAAA at night —
            // on the near-black night search face the day brown
            // read dim for older eyes. Same night value as Main /
            // Downloaded; day value is exactly the layout colour.
            searchBox.setHintTextColor(night
                    ? android.graphics.Color.parseColor("#AAAAAA")
                    : android.graphics.Color.parseColor("#7A6A58"));
            // v9.94: the filter button was set only at wire-up, on tap,
            // on clear and on rotation restore -- never by applyTheme(),
            // so a language picked in Settings while this chooser sat
            // in the back stack left the button in the OLD language
            // until the next tap (same class as the v9.91 search hint).
            // Same EXISTING keys refreshFilterBtn already uses, so
            // Burmese display is byte-identical; the null guard inside
            // makes the pre-wire first-create pass a safe no-op.
            refreshFilterBtn();
            // v9.97: the search box + filter button kept their layout
            // sizes (16sp / 14sp) in every font setting -- Main's and
            // Downloaded's search boxes both follow Prefs.fontSize and
            // the Talks rows do, but applyTheme() never sized this
            // row, so a large-text listener returning from Settings
            // got a small search row on a grown screen (same
            // back-stack-stale class as v9.91/v9.94/v9.96, font-size
            // dimension). Box 14/16/20 = the exact Main/Downloaded
            // search step; filter 12/14/16 = the Talks row-sub step.
            // Normal font yields exactly the layout sizes, so
            // default display is unchanged. Runs on create and on
            // every return, inside the existing searchBox null guard
            // (filterBtn has its own guard: it is wired with the box).
            searchBox.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    f == 0 ? 14 : (f == 1 ? 16 : 20));
            if (filterBtn != null) filterBtn.setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_SP,
                    f == 0 ? 12 : (f == 1 ? 14 : 16));
        }
        // v9.95: the search-empty view text is set only inside
        // rebuildVisible(), on search/filter changes -- never by
        // applyTheme(), so a language picked in Settings while
        // this chooser sat in the back stack left an already-visible
        // empty view (no search hits, or an empty filter) in the
        // OLD language until the next tap (same class as the v9.91
        // search hint and v9.94 filter button). Same EXISTING keys
        // rebuildVisible already uses, so Burmese display is
        // byte-identical; recomputing visibleHits from the unchanged
        // search/filter hits is idempotent, and with talks still
        // unloaded it is a safe no-op (show needs a non-empty list).
        rebuildVisible();
        refreshQueueMarks();
        if (adapter != null) adapter.notifyDataSetChanged();
        refreshTalksQueuePreview(); // v4.87: load/return paints the preview before the first tick
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v1.81: count this screen as on top (see AppForeground).
        AppForeground.activityResumed();
        // v4.14: the share chooser is gone by now — a later onPause
        // is a real leave again (Room's suppressBgPause pattern).
        suppressSharePause = false;
        // v1.60: same keep-screen-on gap as the Room — it was only read
        // in onCreate, so a Settings toggle made while this screen sat
        // in the back stack did nothing until recreation.
        if (Prefs.keepScreenOn(this))
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // v2.99: premium return theme/font dip — the Main screen got
        // this in v2.98, but the Talks chooser kept swapping
        // instantly: changing Day/Night or the font size in
        // Settings and coming back here recoloured + resized the
        // whole chooser in one frame, while the toggle tap dips.
        // A real change since this screen last applied its theme
        // now dips with the existing Fx.themeDip (140ms down to
        // 0.55, swap at the dip, 260ms rise to exactly alpha 1),
        // mirroring Main. Guards: the onCreate pass just set
        // cachedNight/cachedFont, so the first onResume and
        // rotations never dip (no diff); a return with nothing
        // changed stays instant, so back-stack navigation never
        // dips pointlessly; the dip's swap is applyTheme itself,
        // which rebinds the list — no second call is needed.
        // The title entrance (v2.16) is fresh-open only and
        // applyTheme touches only colour/size, never alpha, so
        // the two never fight.
        View root = findViewById(R.id.root);
        // v11.45: a language pick in Settings used to swap this
        // screen's labels instantly on return (applyTheme always
        // runs below, so nothing went stale) while Main/Room dipped
        // — now a language change dips like a theme/font change,
        // mirroring Main v3.84 / Room v3.86. The null guard keeps
        // the first onResume and rotations dip-free, and the dip's
        // swap is applyTheme itself, which re-syncs cachedLang.
        boolean themeChanged = Prefs.isNight(this) != cachedNight
                || Prefs.fontSize(this) != cachedFont
                || (cachedLang != null && !Prefs.lang(this).equals(cachedLang));
        if (themeChanged && root != null) {
            Fx.themeDip(root, new Runnable() {
                @Override public void run() { applyTheme(); }
            });
        } else {
            applyTheme();
        }
        // v3.94: Talks return-scroll to the playing talk — the
        // v3.91 fresh-open auto-scroll's own comment records that
        // a back-stack return never reloads, so it never scrolled:
        // listen in the Room, tap Next (or pick another talk from
        // the drawer) a few times, press back, and the chooser
        // still sat at the position it had before the Room opened,
        // with the talk actually sounding possibly screens away.
        // On a back-stack return only (resumedOnce — creation and
        // rotation restores are skipped, so the v1.54 rotation
        // scroll position is never yanked), when a live Room
        // session for THIS sayadaw is playing a talk this list
        // has not anchored on yet, the list now glides to it
        // instead of sitting at the old position. Guards, mirroring
        // v3.91: lastKind must be "room" (a library session must
        // never scroll this sayadaw's list), the sayadaw name must
        // match, and a stopped session (liveSession false) never
        // scrolls. The match is the stream URL itself, so a
        // downloaded talk matches too. Only a changed talk
        // scrolls (lastSeenPlayingUrl) — returning with the same
        // talk still playing leaves a browsed position alone — and
        // only when the talk is off-screen (visible-range check
        // read fresh inside the post, after any theme-dip rebind),
        // so a return to an already-visible playing row does
        // nothing at all. The glide is ListView's own smooth
        // scroll, posted so it runs after layout; no new UI
        // element, no mark, rows stay number + title only, and
        // load, retry and tap behaviour are untouched.
        if (!resumedOnce) {
            resumedOnce = true;
        } else {
            scrollToPlayingOnReturn();
        }
        // v4.26: the chooser header counts this sayadaw's
        // favourited talks ("name (24) · ★ M"), mirroring
        // the Room drawer header's queue count - a return from
        // the Room after an altar long-press favour used to leave
        // the old count until a reload. Rebuilt from the name
        // every time, never from the view's own text, so counts
        // can never stack; rows carry their marks (v5.49).
        // v12.31: marks BEFORE header on return — the old order
        // (header first, forced rescan after) left the header's
        // ⬇ count one download behind whenever a download landed
        // while this screen sat paused: the header's isTalkDown-
        // loaded loop excludes filenames still in the 30s-gated
        // in-progress set, and only the forced rescan refreshes
        // that set, so a file that landed during the pause read
        // as not-downloaded in the header while the rows (fixed
        // by that same rescan) already showed ⬇ — and the 1s
        // tick never healed it, because its change detector
        // snapshots caches the forced rescan already updated,
        // so it saw no change and never re-refreshed the header.
        // Every other call site (receiver, load-finish, enqueue)
        // already runs marks first; this return was the one that
        // did not. The ↺ guard reads the same fresh cache now.
        refreshTalkRowMarks(true); // v5.49: a return can change ★/✓/⬇ sets (Room favour, finish, download)
        refreshFavHeader();
        // v10.33: unconditional return card refresh, Talks leg of the v1.74
        // family -- Talks never binds (its own comments say so), so it
        // has no bound refreshNotification like the Room / library
        // onResume paths, only the gated v10.17 prune-drop leg inside
        // refreshTalkRowMarks above. Same stale-paused-card class as
        // Main: a card-relevant change made while the chooser sat in
        // the back stack through a path that missed its funnel leg
        // left the card stale until the next tap. The no-bind intent
        // is the v10.03 shape, liveSession-guarded, never starts a
        // dead service; the handler stopSelfs when trackless and a
        // rebuild with no change paints the identical card. No new UI,
        // no toast.
        try {
            if (AudioService.liveSession) {
                android.content.Intent rsv1033 = new android.content.Intent(TalksActivity.this, AudioService.class);
                rsv1033.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                startService(rsv1033);
            }
        } catch (Exception ignored) {}
        // v4.94: a return from the Room can change ★ / ✓ sets
        // (favourited or finished there); v5.13: a download landed
        // there changes the downloaded set the same way, so a live
        // filter view
        // re-indexes against the current sets instead of showing
        // stale rows; mode 0 is a no-op rebuild.
        rebuildFilter();
        if (filterMode != 0 && adapter != null) adapter.notifyDataSetChanged();
        startTalksStripTick(); // v4.31: mini-player live state
        // v10.07: listen for download completions while foreground —
        // the rescan below flips this list's rows ⏳→⬇ (or drops a dead
        // ⏳ on failure, via the prune inside refreshTalkRowMarks) at
        // once instead of waiting for the next return, and the guarded
        // refresh rebuilds a paused card whose ⬇/⏳ mark just changed
        // (same class as the v10.02/v10.03/v10.05/v10.06 paused-card
        // syncs; the Room leg already does this for its own screen).
        // Any completion fires it (the broadcast is device-wide), but
        // the rescan is a pure diff and the card send is gated below
        // on the finished job being the sounding talk, so unrelated
        // system downloads never re-post the card and never start a
        // dead service (liveSession guard, v10.03 shape).
        if (!talksDlCompleteRegistered) {
            if (talksDlCompleteReceiver == null) {
                talksDlCompleteReceiver = new android.content.BroadcastReceiver() {
                    @Override public void onReceive(android.content.Context c, android.content.Intent in) {
                        refreshTalkRowMarks(true);
                        if (talks != null && !talks.isEmpty() && sayadawName != null) refreshFavHeader();
                        // Card sync: the finished job's source URL is
                        // the DownloadManager COLUMN_URI (fallback:
                        // the landed Dhamma/ basename reverse-mapped
                        // through dlMap, or this list's mapped name).
                        // Guard reads prefs fresh at fire time, NOT
                        // talkPlayingUrl (onCreate snapshot): room =
                        // source == lastUrl; library = dlMap[source]
                        // file == lastLibPath file (v10.03 shape).
                        // v12.29: a FAILED job used to die silently — the
                        // rescan above drops its ⏳ via the prune and the
                        // card-sync block below returns early when nothing
                        // is playing, so a listener who queued a download
                        // (Wi-Fi wait, then a network drop mid-file) saw
                        // the hourglass vanish with no file and no word
                        // why. A failure now toasts "download_failed" +
                        // the talk title, the same string shape as the
                        // enqueue catch. Separate try, BEFORE the
                        // card-sync block: that block returns early on
                        // !liveSession, but a download can fail while
                        // nothing plays — the toast must not be gated on
                        // a live session. Gated on the source URL being
                        // a talk this app queued (dlMap, or this list),
                        // so an unrelated system download failing while
                        // this screen is open never toasts here; the
                        // prune above touches dlQueued only, never dlMap,
                        // so the dlMap gate still reads true after the
                        // rescan. The broadcast fires once per job, so
                        // the toast fires once. Success toasts nothing —
                        // the ⏳→⬇ row flip is the feedback, like the
                        // library receiver's own "no toast" note.
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
                                                    String failTitle = null;
                                                    int titleColFail = curFail.getColumnIndex(android.app.DownloadManager.COLUMN_TITLE);
                                                    if (titleColFail >= 0) failTitle = curFail.getString(titleColFail);
                                                    if (talks != null) {
                                                        for (Talk t : talks) {
                                                            if (t != null && failUrl.equals(t.url)) {
                                                                oursFail = true;
                                                                if ((failTitle == null || failTitle.length() == 0) && t.title != null && t.title.length() > 0) failTitle = t.title;
                                                                break;
                                                            }
                                                        }
                                                    }
                                                    if (oursFail) {
                                                        if (failTitle == null || failTitle.length() == 0) failTitle = failUrl;
                                                        android.widget.Toast.makeText(TalksActivity.this,
                                                                Lang.t(TalksActivity.this, "download_failed") + failTitle,
                                                                android.widget.Toast.LENGTH_LONG).show();
                                                    }
                                                }
                                            }
                                        }
                                    } finally { curFail.close(); }
                                }
                            }
                        } catch (Exception ignored) {}
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
                                                    while (keys0.hasNext()) {
                                                        String k = keys0.next();
                                                        if (landed.equals(map0.optString(k, ""))) { srcUrl = k; break; }
                                                    }
                                                    if (srcUrl == null && talks != null) {
                                                        for (Talk t : talks) {
                                                            if (t != null && landed.equals(talkMappedFileName(t.url))) { srcUrl = t.url; break; }
                                                        }
                                                    }
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
                                android.content.Intent rsv = new android.content.Intent(TalksActivity.this, AudioService.class);
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
                    registerReceiver(talksDlCompleteReceiver, f, android.content.Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(talksDlCompleteReceiver, f);
                }
                talksDlCompleteRegistered = true;
            } catch (Exception ignored) {}
        }
    }

    private void refreshFavHeader() {
        talkRefreshInProgressDlNames(false); // v11.57: header ⬇ count reads isTalkDownloaded fresh
        if (talks == null || talks.isEmpty() || sayadawName == null) return;
        android.widget.TextView ht = findViewById(R.id.title);
        if (ht == null) return;
        int fc = 0;
        try {
            java.util.Set<String> ft = getSharedPreferences("dhamma", MODE_PRIVATE).getStringSet("favTalks", null);
            if (ft != null) for (Talk t : talks) if (ft.contains(t.url)) fc++;
        } catch (Exception ignored) {}
        // v5.66: the chooser header also counts offline talks
        // ("· ⬇ M") and finished talks ("· ✓ M") — the two
        // shares the Room drawer header already carries
        // (v4.24 ⬇, v5.64 ✓) and the library footer mirrors
        // (v5.65 ✓), but this header never did: it read only
        // ★ (v4.26), ⏳ (v4.27) and ◈ (v4.65), so a listener
        // browsing a sayadaw's list could not see how much of
        // it was already saved or already heard without
        // counting row marks. Same order as the Room header
        // (⬇ before ★, ✓ last after ◈); zero keeps the plain
        // counts, like the ★/⏳/◈ guards. Sources match the
        // header's own fresh reads, not the row caches: ⬇ is
        // the per-talk isTalkDownloaded check the Up Next
        // line below already uses, ✓ is the playedTalks set
        // the Up Next line below also reads fresh — so the
        // counts agree with the marks by construction, and
        // the header still rebuilds from the name every time,
        // never from the view's own text, so counts never
        // stack. Rows stay number + title only.
        int dlCount = 0;
        for (Talk t : talks) try { if (isTalkDownloaded(t.url)) dlCount++; } catch (Exception ignored) {}
        int playedCount = 0;
        try {
            java.util.Set<String> pl = getSharedPreferences("dhamma", MODE_PRIVATE).getStringSet("playedTalks", null);
            if (pl != null) for (Talk t : talks) if (pl.contains(t.url)) playedCount++;
        } catch (Exception ignored) {}
        // v5.70: the chooser header also counts half-heard talks
        // ("· ↺ M") — the mirror of the Room drawer header's
        // v5.69 count. This header already carried ⬇ (v5.66)
        // and ✓ (v5.66) but never how many of the sayadaw's
        // talks were stopped mid-way, even though the chooser
        // rows themselves carry ↺ marks (v5.34/v5.59) — a
        // listener browsing the list could not see the count
        // without counting marks down the rows. Same rule as
        // the row marks: saved spot over 5s under the stream
        // URL, or under the downloaded-file path when already
        // offline (v5.59 path-form fallback, with the
        // talkDlCache membership guard so a stale filename
        // cache entry can never mark a deleted file); the
        // playing row is excluded, so the count always agrees
        // with the ↺ marks on the rows. One parse via
        // talkPosKeysOver, not a talkPosGet per talk (v1.57
        // jank class); the ⬇ loop above already refreshed the
        // filename cache, so no filesystem work here. Zero
        // keeps the plain line, like the ★/⬇/✓ guards, and
        // it sits after ✓ (last among the counts, like the
        // Room header) before the Up Next line.
        int resumeCount = 0;
        try {
            java.util.Set<String> resumeKeys =
                    Prefs.talkPosKeysOver(TalksActivity.this, 5000);
            if (!resumeKeys.isEmpty()) {
                for (Talk t : talks) {
                    // v11.76: exclude the library-playing talk too — during a library
                    // session its URL lives in talkPlayingLibUrl, not talkPlayingUrl,
                    // so the sounding talk used to count here while its row shows ▶
                    // and never ↺, putting the header one above the row marks.
                    if (t.url.equals(talkPlayingUrl)
                            || (talkPlayingLibUrl.length() > 0 && t.url.equals(talkPlayingLibUrl))) continue;
                    if (resumeKeys.contains(t.url)) { resumeCount++; continue; }
                    if (talkDlCache.contains(t.url)) {
                        String dlFn = talkDlFileCache.get(t.url);
                        if (dlFn != null) {
                            String dlPath = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma/" + dlFn).getAbsolutePath();
                            if (resumeKeys.contains(dlPath)) resumeCount++;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        // v4.71: resolve the Up Next talk BEFORE the download
        // query below, so the same single query can also capture
        // that talk's live ⏳ percent (v4.65 resolved it after the
        // query, when the percent was already out of reach). Same
        // resolution as v4.65: queued head first (the playing talk
        // is never queued, v4.56), else the forward-history top
        // (v12.21, the Room's peekNextIndex order), else list-next
        // after the sounding talk, wrapping; null unless a live
        // Room session for THIS sayadaw sounds a talk in this list.
        Talk nxTalk = null;
        if (talkPlayingUrl != null && talkPlayingUrl.length() > 0 && talks.size() > 1) {
            int playIdx = -1;
            for (int i = 0; i < talks.size(); i++)
                if (talks.get(i).url.equals(talkPlayingUrl)) { playIdx = i; break; }
            if (playIdx >= 0) {
                if (talkQueue != null) {
                    for (String qu : talkQueue) {
                        // v5.85: skip the talk now playing — the
                        // v4.56 "never queued" claim holds for plays
                        // through the Room, but a service-restart
                        // play resumes from prefs without playIndex
                        // dropping it, so the saved queue can still
                        // hold the sounding talk (the v5.81 ◈ count
                        // and the v4.87 preview on this very screen
                        // already skip it). Before this, the scan
                        // took it as the queued head and the Up Next
                        // line named the talk already playing.
                        // Skipping falls through to the next queued
                        // head, or to the shuffle-gated list-next
                        // fallback below — the same next talk the
                        // Room's fixed peekNextIndex now returns.
                        if (talkPlayingUrl != null && qu.equals(talkPlayingUrl)) continue;
                        for (int i = 0; i < talks.size(); i++)
                            if (talks.get(i).url.equals(qu)) { nxTalk = talks.get(i); break; }
                        if (nxTalk != null) break;
                    }
                }
                // v12.21: forward step — the Room's peekNextIndex (v8.26) plays the
                // forward-history top after the hand queue and before shuffle/list-next,
                // so after a Previous the Room's Next replays the back-stepped-to talk.
                // This header resolved queue head -> list-next only, so in that window
                // it named list-next while ⏭ played the forward talk — the same promise
                // class as the v5.84 shuffle gap above (which this same header fixed for
                // shuffle but never for forward). Read-only over the persisted
                // forwardHistoryRaw (written on every Room mutation, v8.27, and by the
                // service's background advance when it consumes a step): the topmost
                // entry in this list that is not the sounding talk — the Room's own
                // forwardTopIndex26 shape, minus the prune, because the Room owns the
                // stack and a stale top here is merely skipped, never written away (a
                // stack entry for another sayadaw's Room can never match this list, so
                // the scan falls through exactly like an empty stack). Outranks shuffle
                // exactly like the Room's peek, so the v5.84 gate below still guards
                // only the list-next fallback; the ⏳ query and counts below are
                // untouched (nxTalk non-null feeds them the same way).
                if (nxTalk == null) {
                    try {
                        String fwdRaw21 = getSharedPreferences("dhamma", MODE_PRIVATE)
                                .getString("forwardHistoryRaw", "");
                        if (fwdRaw21 != null && fwdRaw21.length() > 0) {
                            String[] fwdParts21 = fwdRaw21.split("\n");
                            for (int fi21 = fwdParts21.length - 1; fi21 >= 0; fi21--) {
                                String fu21 = fwdParts21[fi21];
                                if (fu21 == null || fu21.length() == 0) continue;
                                if (fu21.equals(talkPlayingUrl)) continue;
                                for (int i = 0; i < talks.size(); i++)
                                    if (talks.get(i).url.equals(fu21)) { nxTalk = talks.get(i); break; }
                                if (nxTalk != null) break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
                // v5.84: no list-next fallback while shuffle is on with
                // no queued head — the Room's real Next then plays a
                // cached random pick (peekNextIndex, v4.36), which
                // this chooser cannot know, so naming list-next
                // promised a talk ⏭ would not play. The library
                // footer already suppresses exactly this way, and
                // this screen's own queue preview hides in the same
                // state (v4.87, "order unknowable"). A queued head
                // above still outranks shuffle, as in the Room, so
                // only the fallback is gated; counts and the ⏳
                // query are untouched (nxTalk null skips both).
                if (nxTalk == null && !Prefs.shuffleOn(TalksActivity.this))
                    nxTalk = talks.get((playIdx + 1) % talks.size());
            }
        }
        // v4.27: the chooser header counts this sayadaw's talks
        // still downloading ("· ⏳ M") — the Room drawer
        // already marks queued rows, but a listener browsing
        // this sayadaw's list had no pending cue at all.
        // Source-URL match against the loaded talks, so only
        // this sayadaw's jobs count; rebuilt from the name
        // every time, like the ★ count, so it never stacks.
        // v8.64: resolve the foreign head BEFORE the DownloadManager
        // query so its live percent can be captured in that same
        // single query — the foreign URL is not in this sayadaw's
        // talkUrls, so the query below would otherwise never match
        // it. Read-only: foreignQueueHead scans the saved queue and
        // returns the first waiting foreign line, or null; the
        // branch below reuses this value unchanged.
        QueueEntry fxHead = foreignQueueHead();
        int fxPct = -1;
        int progCount = 0;
        int nxPct = -1;
        try {
            java.util.Set<String> talkUrls = new java.util.HashSet<String>();
            for (Talk t : talks) talkUrls.add(t.url);
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            // v11.76: filter in the provider — this used to pull every download
            // and discard the finished ones in Java; the status check below
            // stays as a guard.
            android.database.Cursor c = dm.query(new android.app.DownloadManager.Query()
                    .setFilterByStatus(android.app.DownloadManager.STATUS_RUNNING
                            | android.app.DownloadManager.STATUS_PAUSED
                            | android.app.DownloadManager.STATUS_PENDING));
            if (c != null) {
                try {
                    int statusCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS);
                    int srcCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_URI);
                    int soFarCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                    int totalCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                    while (c.moveToNext()) {
                        int st = statusCol >= 0 ? c.getInt(statusCol) : -1;
                        if (st != android.app.DownloadManager.STATUS_RUNNING
                                && st != android.app.DownloadManager.STATUS_PAUSED
                                && st != android.app.DownloadManager.STATUS_PENDING) continue;
                        String src = srcCol >= 0 ? c.getString(srcCol) : null;
                        if (src != null && talkUrls.contains(src)) progCount++;
                        // v4.71: the Up Next talk's live percent, from
                        // this same query — no second query, and -1
                        // stays -1 when the talk is not downloading
                        // (saved talks read ⬇ instead, below).
                        if (nxTalk != null && src != null && src.equals(nxTalk.url)
                                && soFarCol >= 0 && totalCol >= 0) {
                            long soFar = c.getLong(soFarCol);
                            long total = c.getLong(totalCol);
                            if (total > 0 && soFar >= 0) nxPct = (int) (soFar * 100 / total);
                        }
                        // v8.64: same percent for the foreign head (its
                        // URL is not in talkUrls, so it is never counted
                        // in progCount above — only its own percent here).
                        if (fxHead != null && src != null && src.equals(fxHead.url)
                                && soFarCol >= 0 && totalCol >= 0) {
                            long soFar = c.getLong(soFarCol);
                            long total = c.getLong(totalCol);
                            if (total > 0 && soFar >= 0) fxPct = (int) (soFar * 100 / total);
                        }
                    }
                } finally { c.close(); }
            }
        } catch (Exception ignored) {}
        // v4.65: the chooser header reads the hand queue — the Room
        // drawer header has carried "· ◈ N" plus an Up Next line
        // since v4.32/v4.13, but a listener browsing this sayadaw's
        // talks saw only the ★/⏳ counts: a queue built from the
        // chooser's own ⋮ menus never read in its own header, and
        // the next talk was invisible until the Room opened. The
        // ◈ count comes from talkQueue, which every refreshFavHeader
        // call site refreshes first (load, rotation restore,
        // applyTheme on return, queue menu below), so it can never
        // show a stale or foreign-sayadaw queue; zero keeps the
        // plain counts, like the ★/⏳ guards above. The Up Next
        // line names the talk a Next tap would play — the queued
        // head first (the playing talk is never queued, v4.56,
        // except a service-restart play — v5.85 skips it above),
        // else the list-next after the sounding talk, wrapping —
        // only while a live Room session for THIS sayadaw sounds
        // a talk in this list (talkPlayingUrl, set by the same
        // refreshQueueMarks); a library session or another
        // sayadaw's session leaves the header count-only, like
        // the drawer's count-only state with no current talk.
        // Rebuilt from the name every time, never from the view's
        // own text, so counts can never stack; rows stay number
        // + title only. The header refreshes on load, return and
        // queue-menu actions — not on the strip's 1s tick, which
        // deliberately skips the DownloadManager query above —
        // so a Next landing while the chooser sits on top reads
        // on the next return, like the ★ count since v4.26.
        String header = sayadawName + " (" + talks.size() + ")"
                + (dlCount > 0 ? " · ⬇ " + dlCount : "")
                + (fc > 0 ? " · ★ " + fc : "")
                + (progCount > 0 ? " · ⏳ " + progCount : "");
        // v5.81: the header's ◈ count skips the talk now playing
        // — the strips (v5.80), the card (v5.43) and this screen's
        // own queue preview (v4.87, playing talk skipped) already
        // count waiting talks only, but the header counted the raw
        // talkQueue, so a service-restart play still saved in the
        // queue read as one waiting here and zero there. talkPlayingUrl
        // is the playing identity refreshQueueMarks already computed
        // for this header (live Room session for this sayadaw, else
        // empty, so a foreign session changes nothing); zero keeps
        // the plain counts, like every guard above.
        int waitQ = 0;
        if (talkQueue != null) {
            waitQ = talkQueue.size();
            if (talkPlayingUrl != null && talkPlayingUrl.length() > 0
                    && talkQueue.contains(talkPlayingUrl)) waitQ--;
            // v11.76: same skip for the library-playing talk — it stays queued
            // (a library play never pops the Room queue), so without this the
            // header ◈ read one above the row marks; the inequality guard keeps
            // a talk that is somehow both forms from being subtracted twice.
            if (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0
                    && !talkPlayingLibUrl.equals(talkPlayingUrl)
                    && talkQueue.contains(talkPlayingLibUrl)) waitQ--;
        }
        // v7.42: header ◈ count foreign-aware -- talkQueue holds
        // in-list URLs only (v7.39), so a foreign v2 line waiting
        // under this key (handover-carried, owned by another
        // sayadaw, v7.08) never counted here, while this screen's
        // own strip (v7.39) counts every parsed saved line and
        // the Main/Settings strips + plain card count the same
        // way -- one screen showed two ◈ numbers whenever a
        // foreign talk waited. Add only that missing class:
        // foreign-owned v2 lines whose URL is not already in
        // talkQueue (an in-list foreign-owned line is already
        // counted above, so it is never double-counted) and is
        // not the playing talk, deduped by URL. Stale bare
        // ghosts stay uncounted exactly as before (the v5.74
        // prune, not this count, owns them); same-owner v2
        // lines are untouched too. No layout change, no new UI.
        try {
            String savedQ42 = Prefs.savedQueue(TalksActivity.this, sayadawName);
            if (savedQ42 != null && savedQ42.length() > 0) {
                java.util.Set<String> seenQ42 = new java.util.HashSet<String>();
                for (String line42 : savedQ42.split("\n")) {
                    if (line42.length() == 0) continue;
                    QueueEntry e42 = QueueEntry.parse(line42);
                    if (e42 == null || !e42.isV2()) continue;
                    if (sayadawName != null && e42.sayadaw.equals(sayadawName)) continue;
                    String u42 = e42.url;
                    if (u42.length() == 0 || !seenQ42.add(u42)) continue;
                    if (talkQueue != null && talkQueue.contains(u42)) continue;
                    if (talkPlayingUrl != null && talkPlayingUrl.length() > 0
                            && u42.equals(talkPlayingUrl)) continue;
                    // v11.76: foreign line that is the library-sounding talk skips too.
                    if (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0
                            && u42.equals(talkPlayingLibUrl)) continue;
                    waitQ++;
                }
            }
        } catch (Exception ignored) {}
        if (waitQ > 0) header += " · ◈ " + waitQ;
        if (playedCount > 0) header += " · ✓ " + playedCount;
        if (resumeCount > 0) header += " · ↺ " + resumeCount;
        // v7.22: a foreign queue head outranks the in-list next —
        // the Room's handleNext hands over to it first (v7.08),
        // but this header resolved next from talkQueue only
        // (in-list URLs), so it named the in-list queued head —
        // or the list-next fallback — while ⏭ would play another
        // sayadaw's talk. The Room header's own plain form
        // (title · sayadaw, no in-list marks — the talk has no
        // row here), and the in-list line below runs unchanged
        // when no foreign head waits.
        if (fxHead != null) {
            // v8.64: the foreign head no longer reads bare (title +
            // sayadaw only, v7.22) while the in-list next talk below
            // carries its full mark set — the Room drawer header
            // already fixed this same gap in v8.62, and this chooser
            // header is its mirror: a foreign head is still the talk
            // a Next tap will play (handover), so it hid exactly the
            // same surprises (downloaded read as stream, finished or
            // half-heard read as unheard). Same mark order as the
            // in-list branch and Room v8.62 (◈, then ⬇/⏳, ★, ✓, ↺);
            // same sources as the in-list branch (fresh fav/played
            // sets, isTalkDownloaded + talkDlFileCache for the >5s
            // stream-form spot + downloaded-file-form fallback).
            // ◈: foreignQueueHead returns only when the head is the
            // FIRST waiting saved line (any waiting in-list line
            // ahead of it makes it return null), so its waiting
            // ordinal is 1 — recomputed here from the saved order
            // (playing filtered) rather than assumed, and omitted
            // if the rescan somehow misses it.
            String fxMark = "";
            try {
                String savedQ64 = Prefs.savedQueue(TalksActivity.this, sayadawName);
                if (savedQ64 != null && savedQ64.length() > 0) {
                    int fxOrd64 = 0;
                    boolean fxFound64 = false;
                    for (String line64 : savedQ64.split("\n")) {
                        if (line64.length() == 0) continue;
                        QueueEntry e64 = QueueEntry.parse(line64);
                        if (e64 == null) continue;
                        if (talkPlayingUrl != null && talkPlayingUrl.length() > 0
                                && e64.url.equals(talkPlayingUrl)) continue;
                        fxOrd64++;
                        if (e64.url.equals(fxHead.url)) { fxFound64 = true; break; }
                    }
                    if (fxFound64) fxMark = " ◈" + fxOrd64;
                }
            } catch (Exception ignored) {}
            boolean fxDl = isTalkDownloaded(fxHead.url);
            if (fxDl) fxMark += " ⬇";
            else if (fxPct >= 0) fxMark += " ⏳" + fxPct + "%";
            try {
                java.util.Set<String> ft64 = getSharedPreferences("dhamma", MODE_PRIVATE)
                        .getStringSet("favTalks", null);
                if (ft64 != null && ft64.contains(fxHead.url)) fxMark += " ★";
            } catch (Exception ignored) {}
            try {
                java.util.Set<String> pl64 = getSharedPreferences("dhamma", MODE_PRIVATE)
                        .getStringSet("playedTalks", null);
                if (pl64 != null && pl64.contains(fxHead.url)) fxMark += " ✓";
            } catch (Exception ignored) {}
            int fxRp = Prefs.talkPosGet(TalksActivity.this, fxHead.url);
            if (fxRp <= 5000 && fxDl) {
                String fxDlFn = talkDlFileCache.get(fxHead.url);
                if (fxDlFn != null) {
                    String fxDlPath = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma/" + fxDlFn).getAbsolutePath();
                    fxRp = Prefs.talkPosGet(TalksActivity.this, fxDlPath);
                }
            }
            if (fxRp > 5000) fxMark += " ↺ " + Fx.fmtTime(fxRp);
            header += "\n" + Lang.t(TalksActivity.this, "next_prefix") + (fxHead.title.length() > 0 ? fxHead.title : fxHead.url)
                    + " · " + fxHead.sayadaw + fxMark;
        } else if (nxTalk != null) {
            // v4.71: the Up Next line gains the offline marks the
            // Room surfaces both carry — ⬇ when the named talk is
            // already saved offline (Room drawer header v4.17,
            // Room strip v4.70), else its live ⏳% while
            // downloading (drawer v4.24, strip v4.70) — so a
            // listener browsing the chooser sees at a glance
            // whether tapping Next streams, plays offline, or is
            // worth waiting out, without opening the Room. Same
            // mark order as the Room header (offline/download
            // mark, then ★); the percent is the snapshot this
            // header refresh captured, like the ★ count since
            // v4.26 — the header refreshes on load, return and
            // queue-menu actions, not on the strip's 1s tick.
            Talk nx = nxTalk;
            boolean nxFav = false;
            try {
                java.util.Set<String> ft2 = getSharedPreferences("dhamma", MODE_PRIVATE)
                        .getStringSet("favTalks", null);
                if (ft2 != null) nxFav = ft2.contains(nx.url);
            } catch (Exception ignored) {}
            // v5.62: the chooser header's Up Next line gains the
            // ✓ finished and ↺ resume marks its own preview rows
            // carry (v5.50 ✓, v5.61 ↺) — the line naming the
            // imminent talk read a finished or half-heard talk
            // exactly like an unheard one. Played is read fresh
            // here like the header's own ★ count (not the row
            // cache, which this function runs before on return);
            // the resume read mirrors the preview's >5s rule and
            // path-form fallback, with the downloaded boolean
            // captured from the ⬇ check so a stale cache
            // filename can never mark a deleted file.
            boolean nxDl = isTalkDownloaded(nx.url);
            // v5.95: the chooser header's Up Next line gains its ◈
            // queue position — the Room drawer header's line got
            // it in v5.94, but this mirror line still named the
            // queued head with ⬇/⏳%, ★, ✓, ↺ only, while every
            // row naming that talk carries its ◈ waiting number
            // (chooser rows v4.48, preview rows v4.87). Same
            // waiting-number rule as the preview rows: position
            // in talkQueue with the playing talk filtered out
            // (v5.85 already skips it when resolving the head),
            // so the number agrees with the preview and the
            // drawer rows; an unqueued list-next fallback is not
            // in talkQueue and reads exactly as today. First
            // suffix mark, ahead of ⬇/⏳, matching the Room
            // header's v5.94 order. No new read: talkQueue is
            // the refreshed cache the head resolution above
            // already used.
            String nxQueueMark = "";
            try {
                if (talkQueue != null) {
                    int nxShown = 0;
                    boolean nxFound = false;
                    for (String qu : talkQueue) {
                        if (talkPlayingUrl != null && qu.equals(talkPlayingUrl)) continue;
                        nxShown++;
                        if (qu.equals(nx.url)) { nxFound = true; break; }
                    }
                    if (nxFound) nxQueueMark = " ◈" + nxShown;
                }
            } catch (Exception ignored) {}
            String nxMark = nxQueueMark;
            if (nxDl) nxMark += " ⬇";
            else if (nxPct >= 0) nxMark += " ⏳" + nxPct + "%";
            boolean nxPlayed = false;
            try {
                java.util.Set<String> pl2 = getSharedPreferences("dhamma", MODE_PRIVATE)
                        .getStringSet("playedTalks", null);
                if (pl2 != null) nxPlayed = pl2.contains(nx.url);
            } catch (Exception ignored) {}
            int nxRp = Prefs.talkPosGet(TalksActivity.this, nx.url);
            if (nxRp <= 5000 && nxDl) {
                String nxDlFn = talkDlFileCache.get(nx.url);
                if (nxDlFn != null) {
                    String nxDlPath = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma/" + nxDlFn).getAbsolutePath();
                    nxRp = Prefs.talkPosGet(TalksActivity.this, nxDlPath);
                }
            }
            header += "\n" + Lang.t(TalksActivity.this, "next_prefix") + nx.title + nxMark + (nxFav ? " ★" : "")
                    + (nxPlayed ? " ✓" : "")
                    + (nxRp > 5000 ? " ↺ " + Fx.fmtTime(nxRp) : "");
        }
        ht.setText(header);
    }

    private void scrollToPlayingOnReturn() {
        if (talkList == null || talks.isEmpty()) return;
        // v11.42 (v12.85: the queue-only exit is deleted with the
        // view): the clears below run only once the scroll is
        // certain — after the live-session/sayadaw/same-talk guards
        // pass and the target index is found (title long-press
        // pattern). A no-scroll return leaves the view untouched.
        // v10.43: bound-trackless stop — liveSession stays true but the mirror is false, so never scroll to a stopped talk.
        if (!AudioService.liveSession || !AudioService.liveHasTrack) return;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (!"room".equals(sp.getString("lastKind", ""))) return;
            if (sayadawName == null || !sayadawName.equals(sp.getString("lastName", ""))) return;
            final String liveUrl = sp.getString("lastUrl", "");
            if (liveUrl.length() == 0 || liveUrl.equals(lastSeenPlayingUrl)) return;
            int idx = -1;
            for (int i = 0; i < talks.size(); i++) {
                if (talks.get(i).url.equals(liveUrl)) { idx = i; break; }
            }
            if (idx < 0) return;
            // v12.54: the clear used to run BEFORE the visibility
            // check below, so a return where the newly playing talk
            // was already on screen never scrolled yet still wiped
            // the search box and reset the filter — the v11.42
            // comment claimed the clears "already waited for the
            // same certainty" as the exit that used to run here
            // (deleted with the queue-only view, v12.85). Resolve
            // the target to a DISPLAY position
            // in the currently shown list first (visibleHits when a
            // search/filter is active, else the full-list index —
            // the same precedence getCount/talkIndexAtDisplay use):
            // a shown target scrolls to its display position with
            // the view untouched; only a target filtered OUT of the
            // shown list clears search/filter first, so the full-
            // list index below means something again.
            final int target;
            if (visibleHits != null) {
                int disp = visibleHits.indexOf(Integer.valueOf(idx));
                if (disp < 0) {
                    clearSearch(); // v4.93: the glide target is a full-list index
                    clearFilter(); // v4.94: same rule for the filter view
                    target = idx;
                } else {
                    target = disp;
                }
            } else {
                target = idx;
            }
            lastSeenPlayingUrl = liveUrl;
            talkList.post(new Runnable() {
                @Override public void run() {
                    if (talkList == null) return;
                    int first = talkList.getFirstVisiblePosition();
                    int last = talkList.getLastVisiblePosition();
                    if (target < first || target > last)
                        talkList.smoothScrollToPosition(target);
                }
            });
        } catch (Exception ignored) {}
    }

    // v4.31: REAL MINI-PLAYER on Talks — transformation piece 2
    // (Main got its real mini-player in v4.30; the chooser had
    // none, so a listener browsing a sayadaw's talks could not
    // pause, skip or see progress without leaving the list).
    // Same strip as Main's syncNowStrip, same live rule
    // (liveSession + saved lastKind/lastName/lastTitle), same
    // title-over-sayadaw layout, same live gold progress from
    // the service's 1s position tick, same ⏸/▶ glyph, same
    // ★/😴/finished suffixes on the title line. Tap returns to
    // the player, toggle plays/pauses in place, Next routes
    // exactly like the notification's Next, long-press stops.
    // GONE while nothing plays, so the chooser stays list-only
    // then; rows stay number + title only throughout. One
    // deliberate omission: Main's ⏳ download-count query is
    // NOT repeated here — the chooser header already counts
    // this sayadaw's pending downloads (v4.27), and the tick
    // stays a cheap prefs/statics read.
    // v6.01: sayadaw portrait on the chooser strip — Main's v6.00
    // mirror. The chooser strip was the last text-only mini-player
    // after Main gained its face: a listener browsing one sayadaw's
    // talks while another's talk played saw only title over name.
    // The portrait resolves through AudioService's once-per-process
    // catalog map (the chooser holds no catalog list), shown through
    // ImageLoader.load so a never-cached portrait reads as that
    // sayadaw's own gold initial circle until its download lands,
    // exactly like a grid row. A library session with no recorded
    // sayadaw keeps today's text-only strip, never a guessed face.
    // Called at every strip visibility edge below, so a hidden
    // strip never leaves a stale face behind, and a session switch
    // lands the new face on the same tick.
    private void syncTalksStripPhoto(String name) {
        android.view.View pv = findViewById(R.id.nowStripPhoto);
        if (pv == null) return;
        if (name == null || name.length() == 0) {
            talksStripPhotoName = null;
            pv.setVisibility(android.view.View.GONE);
            return;
        }
        pv.setVisibility(android.view.View.VISIBLE);
        if (name.equals(talksStripPhotoName)) return;
        talksStripPhotoName = name;
        String url = AudioService.stripPhotoUrlForSayadaw(TalksActivity.this, name);
        ImageLoader.load(TalksActivity.this, url, name, (CircleImageView) pv);
    }

    private void syncTalksStrip() {
        // v7.44: rebind the rows when a row mark changes under
        // the chooser — the Room drawer's v7.43 gap, restated
        // here. The tick already refreshes every mark cache
        // each second (refreshTalksQueuePreview -> refresh-
        // QueueMarks + refreshTalkRowMarks), and the strip's
        // own star re-reads favTalks fresh (v4.75), but the
        // rows bind ONLY from those caches, and the tick
        // rebinds only when the playing URL changes (v4.89)
        // or the downloaded set changes (inside refreshTalk-
        // RowMarks). So a favourite toggled on the notifi-
        // cation card, a talk finishing, a download queueing
        // or a queue edit made while this chooser sat fore-
        // ground (the shade never pauses it, so no return-
        // refresh fires) updated the caches and the strip
        // star at once, yet the visible rows kept their old
        // ★/✓/⏳/◈ until the next load, return or menu
        // action. Snapshot the caches + queue order before
        // the refresh and notify once on any change, merged
        // into the v4.89 playing-change notify below, so
        // steady ticks still never touch the adapter and
        // scrolling and the pour stay undisturbed. Prefs
        // reads only, no layout/ban change; the downloaded
        // scan keeps its 30s throttle inside refreshTalk-
        // RowMarks, so a landing download still surfaces
        // at most one scan late, as before.
        java.util.Set<String> marksFav44 = new java.util.HashSet<String>(talkFavCache);
        java.util.Set<String> marksPlayed44 = new java.util.HashSet<String>(talkPlayedCache);
        java.util.Set<String> marksDlQueued44 = new java.util.HashSet<String>(talkDlQueuedCache);
        java.util.Set<String> marksDl44 = new java.util.HashSet<String>(talkDlCache);
        java.util.ArrayList<String> marksQueue44 = new java.util.ArrayList<String>(talkQueue);
        refreshTalksQueuePreview(); // v4.87: tick head — every early return below still refreshes it
        boolean marksChanged44 = !marksFav44.equals(talkFavCache)
                || !marksPlayed44.equals(talkPlayedCache)
                || !marksDlQueued44.equals(talkDlQueuedCache)
                || !marksDl44.equals(talkDlCache)
                || !marksQueue44.equals(talkQueue);
        // v4.89: rebind the rows exactly when the playing talk
        // changes under the chooser — auto-advance while
        // browsing, or a Next from the strip, moves talkPlaying-
        // Url (refreshQueueMarks, via the preview call above),
        // but nothing rebinds the list on the tick, so the old
        // row would keep its ▶ + gold until the next load,
        // return or menu action. A change-only notify moves
        // the mark at once; steady ticks never touch the
        // adapter, so scrolling and the pour are undisturbed,
        // and the list needs no special case here.
        if (adapter != null && (marksChanged44 || !talkPlayingUrl.equals(talkRowPlayingUrl == null ? "" : talkRowPlayingUrl)
                || !talkPlayingLibUrl.equals(talkRowPlayingLibUrl == null ? "" : talkRowPlayingLibUrl)
                || AudioService.liveEnded != talkRowEnded)) {
            talkRowPlayingUrl = talkPlayingUrl;
            talkRowPlayingLibUrl = talkPlayingLibUrl;
            talkRowEnded = AudioService.liveEnded;
            // v7.55: the filter view follows the same mark change
            // -- v7.44 rebinds the rows and v7.51 refreshes the
            // header when a mark changes under the chooser, but
            // filterHits is built only on load, return, a filter
            // tap or a fresh list (rebuildFilter call sites), so
            // a filtered view kept its old membership: a talk
            // unfavourited on the notification card stayed
            // visible in the ★-only view (mark gone, row kept),
            // a talk that finished stayed in the unlistened view
            // (✓ mark, wrong view), and a download that landed
            // stayed out of the downloaded-only view. Rebuild
            // the filter here, before the single notify, only
            // on a real mark change and only while a filter is
            // active (mode 0 is a no-op rebuild anyway); steady
            // ticks and playing-only changes never touch it, the
            // search intersection survives through rebuild-
            // Visible, and a filter emptied this way shows the
            // existing empty word instead of stale rows. The
            // downloaded scan's 30s throttle still applies, so a
            // landing download surfaces at most one scan late,
            // exactly like its row mark in v7.44.
            if (marksChanged44 && filterMode != 0) {
                try { rebuildFilter(); } catch (Exception ignored) {}
            }
            adapter.notifyDataSetChanged();
            // v7.51: the header counts follow the same change --
            // v7.44 rebinds the rows when a mark changes under
            // the chooser, but the header (refreshFavHeader:
            // counts + Up Next line) refreshes only on load,
            // return and queue-menu actions, never on this
            // tick -- so a talk finishing (service marks it
            // played whatever screen is foreground), a favourite
            // toggled on the notification card, or a download
            // landing while browsing left the rows correct and
            // the header's counts one event behind until the
            // next return. Same change-only branch, so steady
            // ticks still never touch the header (its Download-
            // Manager query is exactly why the tick skipped it);
            // talkQueue/talkPlayingUrl are already fresh here
            // (refreshTalksQueuePreview above, reads only), and
            // refreshFavHeader only sets the title text, rebuilt
            // from the name every time, so counts never stack.
            try { refreshFavHeader(); } catch (Exception ignored) {}
        }
        android.view.View strip = findViewById(R.id.nowStrip);
        if (strip == null) return;
        boolean night = cachedNight;
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

        // v4.39: same mini-player font-size gap as Main's strip —
        // the chooser strip pinned title/sub at 14/12sp in XML
        // while Talks rows (14/16/20) and the Room surfaces scale.
        // Title uses the Talks-row title step (14/16/20), sub one
        // step below (12/14/16); glyphs stay 22sp like the nav
        // icons, re-applied on the strip's own tick so a Settings
        // change lands on return without recreating the screen.
        int fs = Prefs.fontSize(this);
        if (tx != null) tx.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fs == 0 ? 14 : (fs == 1 ? 16 : 20));
        if (sb != null) sb.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fs == 0 ? 12 : (fs == 1 ? 14 : 16));
        // v10.40: bound-trackless leg of the live rule (the
        // v10.39 audit's named candidate, confirmed for the
        // no-bind strips): liveSession alone stays true when
        // a bound Room/Library activity in the back stack
        // keeps a stopped service alive (stopSelf cannot
        // destroy a bound service, so onDestroy never runs).
        // A no-bind screen cannot call hasTrack(), so it
        // reads the liveHasTrack mirror instead; a trackless
        // live flag now takes the resume branch below at
        // once (hidden when the stop cleared the spot, a
        // one-tap resume strip when a spot survives), never
        // a dead live strip whose first play tap stopSelfs.
        // Paused/buffering sessions are untouched (play()
        // sets the track synchronously). No new UI.
        if (!AudioService.liveSession || !AudioService.liveHasTrack) {
            // v4.61: continue-listening strip on the chooser, the
            // mirror of Main's v4.60 strip — a dead session used
            // to leave the chooser with no mini-player at all even
            // though a saved spot (>5s, the same threshold) was
            // waiting, so a listener who opened a sayadaw's talks
            // after a reboot or a system kill had to leave the
            // list to find the talk again. Same rule as Main
            // (Room talk only, saved URL matching the last talk
            // directly or via its downloaded file, spot >5s) and
            // same presentation: ↺ title over sayadaw · mm:ss
            // resume cue, ▶ re-opening the Room, Next hidden
            // (advancing needs a live track), progress empty (no
            // duration survives a dead session). Global like the
            // live strip — it shows whichever sayadaw's talk was
            // playing, not only this chooser's — and a library
            // session keeps the old behaviour (strip hidden, the
            // library's own row resume untouched). An explicit
            // stop already clears the spot (v1.57), so a stopped
            // talk never shows here.
            talksResumeStripMode = false;
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
            // session — the mirror of the v4.61 Room strip: a
            // reboot or system kill used to leave the chooser with
            // no strip at all after a downloaded file was paused,
            // even though its saved spot (>5s, same threshold) was
            // waiting. Same match rule as the library's own row
            // resume (file path, or its Room URL via the dlMap
            // reverse lookup), same presentation (↺ title over
            // the offline sub line + mm:ss resume cue, ★ when it
            // applies, ▶, Next hidden, progress empty). Tap and ▶
            // open the library with autoPlayPath, so the file
            // resumes from the spot in one tap; the live lib tap
            // below still only glides, because that track is
            // already playing.
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
                    syncTalksStripPhoto(null);
                    strip.setVisibility(android.view.View.GONE);
                    return;
                }
                boolean lFav = false;
                try {
                    java.util.Set<String> ft = rsp.getStringSet("favTalks", null);
                    if (ft != null) {
                        String fn = new java.io.File(lPath).getName();
                        org.json.JSONObject map = new org.json.JSONObject(rsp.getString("dlMap", "{}"));
                        java.util.Iterator<String> keys = map.keys();
                        // v8.49: exactly-one claimant, never the first favourited one —
                        // a dual-claimed file showed ★ when the OTHER talk was favourited
                        // (v8.46 library-strip class, here on the Main/Talks/Settings strips).
                        int nClaim49 = 0;
                        String onlyClaim49 = null;
                        while (keys.hasNext()) {
                            String u = keys.next();
                            if (fn.equals(map.optString(u, ""))) { nClaim49++; onlyClaim49 = u; }
                        }
                        if (nClaim49 == 1 && onlyClaim49 != null && ft.contains(onlyClaim49)) lFav = true;
                    }
                } catch (Exception ignored) {}
                int lSec = rPos / 1000;
                String lTime = Fx.stripResumeTime(lSec);
                // v8.52: Main's v7.94 resume mark, mirrored — the proven file is offline, like Main's lib branch.
                if (tx != null) tx.setText("↺ " + lTitle + (lFav ? " ★" : "") + (Fx.stripInProgressDlFilenames(TalksActivity.this, rsp).contains(new java.io.File(lPath).getName()) ? " ⏳" : " ⬇"));
                // v5.46: a dead library strip names its recorded
                // sayadaw like the live strip since v5.45, in the
                // Room strip's "name · mm:ss မှ ဆက်နားထောင်ရန်"
                // shape; an unrecorded download keeps today's
                // generic line, never a guessed name.
                String libSd = Fx.stripLibSayadaw(TalksActivity.this, rsp);
                if (sb != null) sb.setText(libSd != null ? libSd + " · " + lTime + Lang.t(TalksActivity.this, "resume_from") : Lang.t(TalksActivity.this, "downloaded_talk") + " · " + lTime + Lang.t(TalksActivity.this, "resume_from"));
                if (tg != null) tg.setText("▶");
                if (nx != null) nx.setVisibility(android.view.View.GONE);
                // v7.31: Previous hides with Next in resume mode
                // (Main's mirror fix) — stepping back needs a
                // live track exactly like advancing does, and
                // this handler had no resume branch, so a ⏮ tap
                // on a ↺ strip ACTION_PREV-ed into a different
                // talk instead of resuming this one.
                android.view.View pv0 = findViewById(R.id.nowStripPrev);
                if (pv0 != null) pv0.setVisibility(android.view.View.GONE);
                android.widget.ProgressBar lPb = findViewById(R.id.nowStripProgress);
                if (lPb != null) lPb.setProgress(0);
                talksResumeStripMode = true;
                syncTalksStripPhoto(libSd);
                strip.setVisibility(android.view.View.VISIBLE);
                return;
            }
            if (!"room".equals(rKind) || rName.length() == 0 || rTitle.length() == 0
                    || rPage.length() == 0 || rPos <= 5000 || !rMatch) {
                syncTalksStripPhoto(null);
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
            if (tx != null) tx.setText("↺ " + rTitle + (rFav ? " ★" : "") + Fx.stripResumeDownloadMark(TalksActivity.this, rsp, rUrl));
            if (sb != null) sb.setText(rName + " · " + rTime + Lang.t(TalksActivity.this, "resume_from"));
            if (tg != null) tg.setText("▶");
            if (nx != null) nx.setVisibility(android.view.View.GONE);
            // v7.31: same Previous hide as the library resume
            // branch above — a ↺ strip never shows ⏮.
            android.view.View pv0 = findViewById(R.id.nowStripPrev);
            if (pv0 != null) pv0.setVisibility(android.view.View.GONE);
            android.widget.ProgressBar rPb = findViewById(R.id.nowStripProgress);
            if (rPb != null) rPb.setProgress(0);
            talksResumeStripMode = true;
            syncTalksStripPhoto(rName);
            strip.setVisibility(android.view.View.VISIBLE);
            return;
        }
        talksResumeStripMode = false;
        if (nx != null) nx.setVisibility(android.view.View.VISIBLE);
        // v7.31: Previous returns with Next on a live session.
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
                String libSd = Fx.stripLibSayadaw(TalksActivity.this, sp);
                subText = libSd != null ? libSd : Lang.t(TalksActivity.this, "downloaded_talk");
            }
        }
        if (text == null) { syncTalksStripPhoto(null); strip.setVisibility(android.view.View.GONE); return; }
        boolean stripFav = false;
        try {
            java.util.Set<String> ft = sp.getStringSet("favTalks", null);
            if (ft != null) {
                if ("room".equals(kind)) {
                    stripFav = ft.contains(sp.getString("lastUrl", ""));
                } else if ("lib".equals(kind)) {
                    String lp = sp.getString("lastLibPath", "");
                    if (lp.length() > 0) {
                        String fn = new java.io.File(lp).getName();
                        org.json.JSONObject map =
                                new org.json.JSONObject(sp.getString("dlMap", "{}"));
                        java.util.Iterator<String> keys = map.keys();
                        // v8.49: exactly-one claimant, never the first favourited one —
                        // a dual-claimed file showed ★ when the OTHER talk was favourited
                        // (v8.46 library-strip class, here on the Main/Talks/Settings strips).
                        int nClaim49 = 0;
                        String onlyClaim49 = null;
                        while (keys.hasNext()) {
                            String u = keys.next();
                            if (fn.equals(map.optString(u, ""))) { nClaim49++; onlyClaim49 = u; }
                        }
                        if (nClaim49 == 1 && onlyClaim49 != null && ft.contains(onlyClaim49)) stripFav = true;
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
                    ? " 😴 " + Lang.t(TalksActivity.this, "sleep_end")
                    : " 😴 ⏸ " + Lang.t(TalksActivity.this, "sleep_end");
            else if (sr > 0) text += AudioService.livePlaying
                    ? " 😴 " + AudioService.sleepClock(sr)
                    : " 😴 ⏸ " + AudioService.sleepClock(sr);
            else text += " 😴";
        }
        // v5.67: the chooser strip names downloads in progress
        // (" ⏳ N") — the mirror of Main's v4.27 strip suffix.
        // A listener browsing a sayadaw's talks saw only ★/😴/◈
        // here while a download ran (the drawer rows and the
        // library footer are both a screen away), and the
        // strip's own ⬇ button marks only the sounding talk's
        // queued state, never the app-wide count. Same
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
            // v11.76: filter in the provider — this runs on the 1s strip tick and
            // used to pull every download on the main thread and discard the
            // finished ones in Java; the status check below stays as a guard.
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
        // v4.68: the chooser strip reads the hand queue — the
        // mirror of Main's v4.67 strip suffix. A listener browsing
        // a sayadaw's talks saw only ★/😴 here and had no cue that
        // the strip's own Next button would play a hand-queued talk
        // first (Room/library ACTION_NEXT consume the queue head),
        // not the list-next talk. Same counting rule as Main, so
        // every strip agrees: Room sessions count this sayadaw's
        // saved queue (Prefs.savedQueue, the store the Room
        // restores from), library sessions count saved queue paths
        // that still exist as files. Suffix only, no new view; the
        // 1s tick re-reads it. Resume mode is untouched (it returns
        // above, Next hidden there); zero/empty adds nothing.
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
            // v4.77) is untouched; an all-playing queue still
            // toasts empty on open, as today.
            String curUrl = sp.getString("lastUrl", "");
            String curLib = sp.getString("lastLibPath", "");
            int qN = 0;
            if ("room".equals(kind)) {
                String saved = Prefs.savedQueue(TalksActivity.this,
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
                String saved = Prefs.savedQueueLib(TalksActivity.this);
                if (saved.length() > 0) {
                    for (String line : saved.split("\n"))
                        if (line.trim().length() > 0
                                && new java.io.File(line.trim()).isFile()
                                && !line.trim().equals(curLib)) qN++;
                }
            }
            if (qN > 0) text += " · ◈ " + qN;
        } catch (Exception ignored) {}
        if (AudioService.liveEnded) text += " · " + Lang.t(TalksActivity.this, "notif_state_ended");
        if (tx != null) tx.setText(text);
        // v5.32: live clock on the strip — sayadaw name + elapsed/total.
        android.widget.ProgressBar pb = findViewById(R.id.nowStripProgress);
        if (subText != null) subText += Fx.stripClockSuffixForStrip(pb, AudioService.livePositionMs, AudioService.liveDurationMs, Prefs.stripClockRemaining(TalksActivity.this));
        if (sb != null && subText != null) sb.setText(subText);
        Fx.syncStripProgress(pb); // v7.93: skips while a mini-seek drag is in flight
        if (tg != null) tg.setText(AudioService.livePlaying ? "\u23F8\uFE0E" : "▶");
        // v6.01: live strip's portrait — Room names lastName, library
        // names its recorded sayadaw (Fx.stripLibSayadaw, null when
        // unrecorded); both resolve through syncTalksStripPhoto's
        // exact-name catalog match, never a guessed face.
        syncTalksStripPhoto("room".equals(kind) ? sp.getString("lastName", "")
                : ("lib".equals(kind) ? Fx.stripLibSayadaw(TalksActivity.this, sp) : null));
        strip.setVisibility(android.view.View.VISIBLE);
    }

    private void startTalksStripTick() {
        if (stripTick != null) return;
        stripTick = new Runnable() {
            @Override public void run() {
                if (stripTick == null) return;
                // v11.76: re-post in finally — a throw inside syncTalksStrip used
                // to kill the 1s tick outright, freezing strip title/clock/
                // progress/glyph until the next onPause/onResume cycle.
                try { syncTalksStrip(); }
                finally { stripHandler.postDelayed(this, 1000); }
            }
        };
        stripHandler.post(stripTick);
    }

    private void stopTalksStripTick() {
        if (stripTick != null) {
            stripHandler.removeCallbacks(stripTick);
            stripTick = null;
        }
    }

    private void wireNowStrip() {
        android.view.View nowStrip = findViewById(R.id.nowStrip);
        if (nowStrip != null) {
            Fx.press(nowStrip);
            // v4.73: the strip's progress bar seeks (see Fx.miniSeekBar).
            Fx.miniSeekBar(TalksActivity.this, nowStrip);
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
                        Prefs.setStripClockRemaining(TalksActivity.this, !Prefs.stripClockRemaining(TalksActivity.this));
                        Fx.tap(TalksActivity.this);
                        syncTalksStrip();
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
                            Fx.tap(TalksActivity.this);
                            Fx.morph(v);
                            Intent li = new Intent(TalksActivity.this, DownloadedActivity.class);
                            li.putExtra("jumpPath", lp);
                            // v4.63: in dead-library resume mode
                            // the tap also auto-plays the file from
                            // its saved spot; a live session never
                            // passes this flag, so it still only
                            // glides to the playing file.
                            // v12.57: live recheck, not just the cached
                            // flag — talksResumeStripMode refreshes on
                            // the 1s tick, so a session dying <1s before
                            // this tap left the flag false and the tap
                            // glided to the library WITHOUT auto-playing
                            // (one extra tap to resume — the same ≤1s
                            // stale-flag class v12.55 closed for the
                            // four transport buttons). The strip's own
                            // branch test is !liveSession ||
                            // !liveHasTrack, so re-checking the same
                            // statics here can only auto-play a
                            // genuinely dead session, never a live one.
                            if (talksResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) li.putExtra("autoPlayPath", lp);
                            Fx.go(TalksActivity.this, li);
                            return;
                        }
                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(TalksActivity.this, RoomActivity.class);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.putExtra("autoUrl", sp.getString("lastUrl", ""));
                    i.putExtra("autoTitle", sp.getString("lastTitle", ""));
                    // v11.86: reuse the Room already in the stack, like the Next/Prev
                    // buttons in this same method — the Room header opens Talks without
                    // finishing the Room, so this tap used to push a SECOND Room for the
                    // same talk on top, and Back then showed the first Room again instead
                    // of the list. onNewIntent's no-action tap path (v8.09) handles the
                    // re-delivery: same page returns, the poll already syncs the row.
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.tap(TalksActivity.this);
                    Fx.morph(v);
                    Fx.go(TalksActivity.this, i);
                }
            });
            nowStrip.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    // v4.61: resume mode has no session to stop —
                    // dismiss the saved spot instead (lastPos
                    // only; the talk itself is untouched, so
                    // reopening it starts from the beginning)
                    // and the next tick hides the strip.
                    // v12.57: live recheck, not just the cached flag —
                    // talksResumeStripMode refreshes on the 1s tick, so a
                    // session dying <1s before this long-press left the
                    // flag false and the press ACTION_STOPped a trackless
                    // service (phantom "talk_stopped" toast, saved spot
                    // NOT dismissed — the same ≤1s stale-flag class
                    // v12.55 closed for the Toggle long-press). The
                    // strip's own branch test is !liveSession ||
                    // !liveHasTrack, so re-checking the same statics here
                    // can only dismiss a genuinely dead session, never
                    // stop a live one.
                    if (talksResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        // v5.34: same as Main's dismiss — the
                        // per-talk spot goes with the global one.
                        String dismissUrl = getSharedPreferences("dhamma", MODE_PRIVATE)
                                .getString("lastPosUrl", "");
                        getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                                .putInt("lastPos", 0).putString("lastPosUrl", "").apply();
                        if (dismissUrl.length() > 0) Prefs.talkPosRemoveBoth(TalksActivity.this, dismissUrl);
                        Fx.tap(TalksActivity.this);
                        Fx.morph(v);
                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "hist_cleared"), android.widget.Toast.LENGTH_SHORT).show();
                        syncTalksStrip();
                        // v12.41: the row's ↺ mark reads the spot
                        // live on every bind, but the 1s tick's
                        // change detector snapshots only the fav/
                        // played/download/queue caches plus the
                        // playing URL and liveEnded — a dismiss
                        // moves none of those, so without this
                        // rebind the dismissed talk kept its
                        // "↺ mm:ss" while tapping it now restarts
                        // from 0. Same notify every other Talks
                        // mutation site uses.
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return true;
                    }
                    Intent svc = new Intent(TalksActivity.this, AudioService.class);
                    svc.setAction(AudioService.ACTION_STOP);
                    startService(svc);
                    Fx.tap(TalksActivity.this);
                    Fx.morph(v);
                    android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "talk_stopped"), android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
            });
        }
        android.view.View nowToggle = findViewById(R.id.nowStripToggle);
        if (nowToggle != null) nowToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v4.61: resume mode has no live track to toggle
                // — the ▶ button re-opens the Room like the
                // strip tap, which resumes from the spot.
                // v12.55: live recheck, not just the cached flag — talksResumeStripMode
                // refreshes on the 1s tick, so a session dying <1s before this tap left
                // the flag false and the tap ACTION_TOGGLEd a trackless service (dead
                // tap, tap-twice-to-resume — the exact bug Settings v11.90 fixed for
                // its Toggle). The strip's own branch test is !liveSession ||
                // !liveHasTrack, so re-checking the same statics here can only route a
                // genuinely dead session to resume, never a live one.
                if (talksResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                    android.view.View strip2 = findViewById(R.id.nowStrip);
                    if (strip2 != null) strip2.performClick();
                    return;
                }
                Intent svc = new Intent(TalksActivity.this, AudioService.class);
                svc.setAction(AudioService.ACTION_TOGGLE);
                startService(svc);
                Fx.tap(TalksActivity.this);
                Fx.morph(v);
            }
        });
        if (nowToggle != null) nowToggle.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                // v4.61: resume mode has no session to stop —
                // hand the long-press to the strip, which
                // dismisses the saved spot instead.
                // v12.55: same live recheck as the click above — a stale-false flag here
                // ACTION_STOPs a trackless service instead of dismissing the saved spot
                // (Settings v11.90 fixed the same site for its Toggle long-press).
                if (talksResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                    android.view.View strip2 = findViewById(R.id.nowStrip);
                    if (strip2 != null) return strip2.performLongClick();
                    return true;
                }
                Intent svc = new Intent(TalksActivity.this, AudioService.class);
                svc.setAction(AudioService.ACTION_STOP);
                startService(svc);
                Fx.tap(TalksActivity.this);
                Fx.morph(v);
                android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "talk_stopped"), android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        android.view.View nowNext = findViewById(R.id.nowStripNext);
        if (nowNext != null) {
            Fx.press(nowNext);
            nowNext.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // v7.34: same v7.31 resume-mode guard as Prev —
                    // Prev/Toggle/Forward all resume the ↺ talk in
                    // resume mode, but Next had no guard: a tick-
                    // window ⏭ tap ACTION_NEXT-ed into a Room with
                    // no live track, where handleNext finds no
                    // current talk and stays silent instead of
                    // resuming the saved talk like every other
                    // strip transport button. Now it resumes via
                    // the strip tap, exactly like ⏮/⏩/▶ do.
                    // v12.55: live recheck, not just the cached flag (Settings v11.88) —
                    // talksResumeStripMode refreshes on the 1s tick, so a session dying
                    // <1s before this tap left the flag false and the tap ACTION_NEXT-ed
                    // into a trackless Room instead of resuming the ↺ talk.
                    if (talksResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    Fx.tap(TalksActivity.this);
                    Fx.morph(v);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        Intent li = new Intent(TalksActivity.this, DownloadedActivity.class);
                        li.setAction(DownloadedActivity.ACTION_NEXT);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        Fx.go(TalksActivity.this, li);
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(TalksActivity.this, RoomActivity.class);
                    i.setAction(RoomActivity.ACTION_NEXT);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.go(TalksActivity.this, i);
                }
            });
        }

        android.view.View nowPrev = findViewById(R.id.nowStripPrev);
        if (nowPrev != null) {
            Fx.press(nowPrev);
            nowPrev.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // v7.31: belt-and-braces for the tick window
                    // between a session dying and the next sync
                    // hiding this button — a resume-mode ⏮ tap
                    // resumes the ↺ talk like ▶/⏩ do, instead
                    // of ACTION_PREV-ing into a different talk.
                    // v12.55: same live recheck as Next above (Settings v11.88) — the cached
                    // flag can lag a just-died session by up to one 1s tick, and a stale-false
                    // flag here ACTION_PREVs into a trackless Room instead of resuming the
                    // ↺ talk.
                    if (talksResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    Fx.tap(TalksActivity.this);
                    Fx.morph(v);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        Intent li = new Intent(TalksActivity.this, DownloadedActivity.class);
                        li.setAction(DownloadedActivity.ACTION_PREV);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        Fx.go(TalksActivity.this, li);
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(TalksActivity.this, RoomActivity.class);
                    i.setAction(RoomActivity.ACTION_PREV);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.go(TalksActivity.this, i);
                }
            });
        }
    }

    // v4.93: wire the talk search box — typed text filters rows by
    // title substring (Myanmar text has no case to fold); the box
    // stays GONE until a list lands, so loading/empty states are
    // untouched. Typing filters the full list, and the
    // pour guard is pinned so results appear instantly instead of
    // re-cascading on every keystroke. Search action on the keyboard
    // just dismisses it; clearing restores the full list.
    private void wireSearchBox() {
        android.view.View row = findViewById(R.id.talkSearchRow);
        searchBox = (android.widget.EditText) findViewById(R.id.talkSearch);
        if (searchBox == null) return;
        searchBox.setHint(Lang.t(TalksActivity.this, "talks_search_hint"));
        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                searchQuery = s.toString().trim();
                lastAnimPos = Integer.MAX_VALUE;
                rebuildSearch();
                if (adapter != null) adapter.notifyDataSetChanged();
            }
        });
        searchBox.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(android.widget.TextView v, int actionId, android.view.KeyEvent e) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                    return true;
                }
                return false;
            }
        });
        android.view.View clear = findViewById(R.id.talkSearchClear);
        if (clear != null) clear.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { clearSearch(); }
        });
        // v4.94: filter button — cycles all → ★ only → unlistened
        // only; v5.13 adds downloaded only as the fourth step.
        // A filtered view applies to the full list, and rows
        // rebind without the pour (same lastAnimPos pin
        // as the search watcher).
        filterBtn = (android.widget.TextView) findViewById(R.id.talkFilter);
        if (filterBtn != null) {
            refreshFilterBtn();
            filterBtn.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    Fx.tap(TalksActivity.this);
                    filterMode = (filterMode + 1) % 4;
                    lastAnimPos = Integer.MAX_VALUE;
                    refreshFilterBtn();
                    rebuildFilter();
                    if (adapter != null) adapter.notifyDataSetChanged();
                    android.widget.Toast.makeText(TalksActivity.this,
                            filterMode == 1 ? Lang.t(TalksActivity.this, "filter_starred_only")
                                    : (filterMode == 2 ? Lang.t(TalksActivity.this, "filter_unlistened_only")
                                            : (filterMode == 3 ? Lang.t(TalksActivity.this, "filter_downloaded_only")
                                                    : Lang.t(TalksActivity.this, "filter_talks_all"))),
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }
        // applyTheme() already ran before this wire on first create,
        // so theme the box here too (night would otherwise wait for
        // a return to restyle it).
        boolean night = Prefs.isNight(this);
        // v13.03: real liquid glass — GlassBlur blurs the content behind this surface.
            GlassBlur.attachSearch(searchBox, night);
        searchBox.setTextColor(night
                ? android.graphics.Color.WHITE
                : android.graphics.Color.parseColor("#3B2314"));
        // v9.97: size here too -- applyTheme() already ran before this
        // wire (see its comment above), so without this a fresh open
        // at a non-normal font size would keep the layout sizes until
        // the first return. Same steps as the applyTheme() block above.
        int wf = Prefs.fontSize(TalksActivity.this);
        searchBox.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                wf == 0 ? 14 : (wf == 1 ? 16 : 20));
        if (filterBtn != null) filterBtn.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                wf == 0 ? 12 : (wf == 1 ? 14 : 16));
    }

    private class TalkAdapter extends BaseAdapter {
        @Override public int getCount() {
            if (visibleHits != null) return visibleHits.size();
            return talks.size();
        }
        @Override public Object getItem(int p) {
            int ti = talkIndexAtDisplay(p);
            return ti >= 0 && ti < talks.size() ? talks.get(ti) : null;
        }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int pos, View v, ViewGroup parent) {
            // pos is a DISPLAY position — every identity check
            // below uses talkIdx. A stale slot keeps its
            // old content instead of crashing (getCount already
            // skipped it, so this only fires on a mid-rebind race).
            final int talkIdx = talkIndexAtDisplay(pos);
            // v11.76: inflate BEFORE the stale-index guard — the guard used to
            // return the recycled view, which is null on first bind, and the
            // ListView NPEs on a null getView return. A stale slot now returns
            // a fresh blank row (rebound on the next pass) instead of crashing.
            if (v == null) {
                v = LayoutInflater.from(TalksActivity.this).inflate(R.layout.row_talk, parent, false);
                Fx.press(v); // v2.08: press feedback on talk rows
            }
            if (talkIdx < 0 || talkIdx >= talks.size()) return v;
            // v3.47: premium recycled-row squash hygiene, the one
            // channel the delivered v3.40 pour hygiene explicitly
            // left untouched — same fix as the Main grid in this
            // version. The v2.08 press squash is a
            // ViewPropertyAnimator scale (0.96 on DOWN, spring-back
            // to 1 over 180ms on UP/CANCEL), not the entrance view
            // Animation v3.40 clears, so a row recycled
            // mid-spring-back (finger lifts into a fling inside
            // that 180ms window) used to carry a transient scale
            // onto the new talk: the new row appeared slightly
            // squashed or overshooting past 1, then sprang to 1
            // on content that was never pressed. A rebind now
            // cancels any stale row animator and restores scale 1.
            // This never breaks the squash itself: Fx.press
            // re-arms only on touch events, which arrive after
            // bind, never during it, and the entrance pour below
            // is a view Animation this cancel does not touch.
            // Visual only; the bind below is untouched.
            v.animate().cancel();
            Fx.cancelPress(v); // v3.90: press squash is an ObjectAnimator now — the cancel above cannot stop it.
            v.setScaleX(1f);
            v.setScaleY(1f);
            // v1.96: the row number stayed at a fixed 14sp while the
            // title beside it followed the font size — on ကြီး the
            // numbers read a size smaller than the titles they count.
            TextView indexView = v.findViewById(R.id.index);
            // v12.85: the row number is the list number (the
            // queue-only view is deleted); a queued row still
            // carries its ◈N mark below.
            indexView.setText((talkIdx + 1) + ".");
            int idxF = cachedFont;
            indexView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, idxF == 0 ? 12 : (idxF == 1 ? 14 : 16));
            TextView titleView = v.findViewById(R.id.talkTitle);
            // v4.48: queued rows carry their queue position
            // ("◈1 ") like the Room drawer rows — mark only,
            // the playing talk never shows one, and a recycled
            // row rebinds its mark here so it cannot leak.
            String qMark = "";
            int qpos = talkQueue.indexOf(talks.get(talkIdx).url);
            // v11.07: a library-sounding row is a playing row too,
            // so it never carries a ◈ mark either (same v4.48 rule
            // as the Room playing row; the v11.06 ↺ suppression
            // below already proved the lib identity).
            if (qpos >= 0 && !(talkPlayingUrl.length() > 0
                    && talks.get(talkIdx).url.equals(talkPlayingUrl))
                    && !(talkPlayingLibUrl.length() > 0
                    && talks.get(talkIdx).url.equals(talkPlayingLibUrl))) {
                // v5.82: the position skips the talk now playing —
                // the header (v5.81), strips (v5.80) and card
                // (v5.43) already count waiting talks only, but
                // this mark numbered the raw talkQueue, so a
                // service-restart play still saved in the queue
                // pushed every later mark one too high (next talk
                // read ◈2 against a "· ◈ 1" header and a ◈1
                // preview). talkPlayingUrl is refreshQueueMarks'
                // own identity, so a normal play (already popped)
                // changes nothing. (v12.85: the queue-only view that
                // used to keep the raw number is deleted, so the
                // skip always applies now.)
                int shown = qpos + 1;
                if (talkPlayingUrl.length() > 0) {
                    int playPos = talkQueue.indexOf(talkPlayingUrl);
                    if (playPos >= 0 && playPos < qpos) shown--;
                }
                // v11.76: same skip for the library-playing talk — without it a
                // queued talk after a library-sounding one read ◈ one too high
                // (the v5.82 bug, lib form); the inequality guard keeps a talk
                // that is somehow both forms from being subtracted twice.
                if (talkPlayingLibUrl.length() > 0
                        && !talkPlayingLibUrl.equals(talkPlayingUrl)) {
                    int playPosLib = talkQueue.indexOf(talkPlayingLibUrl);
                    if (playPosLib >= 0 && playPosLib < qpos) shown--;
                }
                qMark = "◈" + shown + " ";
            }
            // v5.77: held-row mark for an armed pick-up/drop move
            // — the picked-up talk reads ✋ ahead of its ◈ number
            // plus a gold wash below, mirroring the Room drawer's
            // v4.92 held row, and only while it is still queued:
            // a queue cleared or reordered from another menu drops
            // the mark on the next rebind instead of highlighting
            // a stale row.
            boolean held = moveHeldUrl != null && moveHeldUrl.equals(talks.get(talkIdx).url)
                    && talkQueue.contains(talks.get(talkIdx).url);
            // v4.89: the playing row finally says so — the Room
            // drawer pulses its playing title (v2.27), the Main
            // grid breathes on the sounding sayadaw and the
            // library breathes on its playing file, but a
            // chooser row for the sounding talk read exactly
            // like every other row: no ▶, no gold, nothing, so
            // a listener browsing the list could not spot the
            // talk the strip is naming without remembering its
            // title. The playing talk (live Room session for
            // this sayadaw, talkPlayingUrl from refreshQueue-
            // Marks) now carries "▶ " ahead of its title in
            // gold, the header gold; it never also carries a
            // ◈ mark (v4.48 rule, kept). Mark only, like the
            // queue marks — tap still opens the Room, and a
            // recycled row rebinds both text and colour here
            // so neither can leak onto the next talk.
            // v11.15: an ended talk is not playing — same v4.29
            // class as the Main grid mark: the chooser row kept
            // its ▶ + gold after an autoplay-off end (and kept
            // suppressing its ✓, so a finished talk read as
            // playing and unfinished at once). Gating both legs
            // here also lifts the ↺ suppression and the ✓
            // suppression for the ended row, which is exactly
            // right: its saved spot was cleared at completion.
            boolean rowPlaying = !AudioService.liveEnded
                    && talkPlayingUrl.length() > 0
                    && talks.get(talkIdx).url.equals(talkPlayingUrl);
            // v5.34: per-talk resume mark — the Room drawer rows
            // carry it, so the chooser rows do too: a half-heard
            // talk reads "↺ mm:ss" after its title, and tapping it
            // resumes there (Room's savedPosFor). The playing row
            // never shows one (its live position is on the strip),
            // and a recycled row rebinds the mark here so it cannot
            // leak. Prefs only, no filesystem, like the ◈ mark above.
            // v5.59: path-form fallback, mirroring the drawer —
            // a spot held under the downloaded-file form only
            // (v5.36) left the row unmarked while the tap still
            // resumed; the scan's filename cache gives the path
            // form with no bind-time filesystem work, and the
            // talkDlCache membership guard keeps a stale cache
            // entry from marking a deleted file.
            String resumeMark = "";
            // v11.06: a library-sounding row is a playing row for
            // ↺ purposes too — its checkpoint spot is stale the
            // moment it plays, so it must not read as half-heard
            // while it sounds. Row-session ▶/gold stay untouched.
            boolean rowPlayingLib = !AudioService.liveEnded
                    && talkPlayingLibUrl.length() > 0
                    && talks.get(talkIdx).url.equals(talkPlayingLibUrl);
            if (!rowPlaying && !rowPlayingLib) {
                String rUrl = talks.get(talkIdx).url;
                int rp = Prefs.talkPosGet(TalksActivity.this, rUrl);
                if (rp <= 5000 && talkDlCache.contains(rUrl)) {
                    String dlFn = talkDlFileCache.get(rUrl);
                    if (dlFn != null) {
                        String dlPath = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma/" + dlFn).getAbsolutePath();
                        rp = Prefs.talkPosGet(TalksActivity.this, dlPath);
                    }
                }
                if (rp > 5000) resumeMark = " ↺ " + Fx.fmtTime(rp);
            }
            // v5.49: drawer marks on chooser rows (see the cache
            // comment above refreshTalkRowMarks) — ★/⬇/⏳/✓ in
            // drawer order, from the caches only, never per-bind
            // prefs or file reads. ⬇ wins over ⏳, like the
            // drawer (a saved talk is not still downloading).
            String rowUrl = talks.get(talkIdx).url;
            String favMark = talkFavCache.contains(rowUrl) ? "★ " : "";
            String offMark = "";
            if (talkDlCache.contains(rowUrl)) offMark = "⬇ ";
            else if (talkDlQueuedCache.contains(rowUrl)) offMark = "⏳ ";
            // v11.07: a library-sounding row reads as the playing
            // row — ▶ + gold + no ✓, exactly like a Room playing
            // row (v4.89). Before this, v11.06 only suppressed its
            // ↺ mark, so a talk sounding from the downloaded
            // library looked like every other row in its own
            // chooser while the strip named it. The tick already
            // rebinds on a talkPlayingLibUrl change (v11.06), so
            // a library advance moves the ▶ at once, and the
            // exactly-one-claimant rule keeps a dual-claimed file
            // unmarked rather than marking the wrong talk.
            String playedMark = (!rowPlaying && !rowPlayingLib && talkPlayedCache.contains(rowUrl)) ? "✓ " : "";
            titleView.setText((held ? "✋ " : "") + ((rowPlaying || rowPlayingLib) ? "▶ " : "") + qMark + favMark + offMark + playedMark + talks.get(talkIdx).title + resumeMark);
            // v1.52: rows fade + rise in once when first shown
            if (pos > lastAnimPos) {
                lastAnimPos = pos;
                android.view.animation.AnimationSet set = new android.view.animation.AnimationSet(true);
                android.view.animation.AlphaAnimation fa = new android.view.animation.AlphaAnimation(0f, 1f);
                fa.setDuration(300);
                // v3.54: premium cascade rise density — the v1.52/v2.07
                // pour above rose a raw 24 PIXELS, so on a dense phone
                // (~3x) it read as ~8dp while every other pour in the
                // app rises a full 24dp (Fx.entrance, Fx.staggerChildren,
                // the library cascade's dy = 24f * density) — the Talks
                // rows poured noticeably flatter than the Room drawer
                // rows and the library rows beside them. The rise is
                // now 24dp like the rest, so the cascade reads the same
                // on any screen. Duration, stagger, fade and the v3.40
                // recycled-row hygiene are untouched; the settled state
                // is still exactly alpha 1 / offset 0. Visual only.
                android.view.animation.TranslateAnimation ta = new android.view.animation.TranslateAnimation(0, 0, 24f * v.getResources().getDisplayMetrics().density, 0);
                ta.setDuration(300);
                set.addAnimation(fa); set.addAnimation(ta);
                // v2.07: staggered cascade — rows pour in one after
                // another instead of all at once, with a soft ease.
                set.setStartOffset(Math.min(pos, 10) * 45);
                set.setInterpolator(new android.view.animation.DecelerateInterpolator());
                v.startAnimation(set);
            } else {
                // v3.40: same recycled-row pour hygiene as the Main
                // grid — a view recycled mid-pour keeps its old
                // fade + rise running when rebound to a position
                // already past the guard, so the new row pours late
                // on content that never poured. Clear any stale
                // animation here; settled rows and the v2.08 press
                // squash (ViewPropertyAnimator) are untouched.
                // Visual only.
                v.clearAnimation();
            }
            int tf = cachedFont;
            titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tf == 0 ? 14 : (tf == 1 ? 16 : 20));
            titleView.setTextColor((rowPlaying || rowPlayingLib)
                    ? android.graphics.Color.parseColor("#C9A227")
                    : (cachedNight
                    ? android.graphics.Color.WHITE
                    : android.graphics.Color.parseColor("#3B2314")));
            android.view.View card = v.findViewById(R.id.cardInner);
            if (card != null) card.setBackgroundResource(cachedNight
                    ? R.drawable.card_bg_night : R.drawable.card_bg);
            // v5.77: held-row wash (see the ✋ mark above) — on the
            // row root, so the glass card itself is untouched, and
            // reset to TRANSPARENT on every bind, so the wash can
            // never leak onto a recycled row, like the drawer.
            v.setBackgroundColor(held ? 0x33C9A227 : android.graphics.Color.TRANSPARENT);
            // v4.47: queue menu button — the chooser's rows could only
            // be tapped (open in Room) or long-pressed (share); a queue
            // could only be built inside the Room drawer. The ⋮ opens
            // the browse-to-queue menu (play next / add / from here /
            // remove / clear) writing the same per-sayadaw prefs key
            // the Room restores, so a queue built here is waiting when
            // the Room opens. Recycled rows rebind the listener with
            // their own position on every bind, like the drawer row.
            // v7.32: row ⋮ removed by user order — no queue-menu binding here anymore.
            return v;
        }
    }

    // v7.32: showTalkQueueMenu deleted — row ⋮ + preview removed by user order, no caller remains.

    // v1.58: same title-entity fix as the Room loader — titles used
    // to show raw &quot; / &#123; codes (only &amp; / &nbsp; decoded).
    // v12.68: the talk-list parse, extracted verbatim from
    // loadTalks so the Wi-Fi auto-download job can read a
    // sayadaw's page with the exact same rules (same regex, same
    // title fallback, same skips) — the chooser now calls this
    // instead of inlining the loop, behaviour unchanged. Throws
    // on a malformed page URL, exactly like the inline code did,
    // so the caller's catch still reports the load failure.
    public static java.util.List<Talk> parseTalkList(String pageUrl, String html)
            throws Exception {
        java.util.List<Talk> out = new java.util.ArrayList<Talk>();
        URL base = new URL(pageUrl);
        // v1.88: single-quoted hrefs too — same empty-list
        // miss as the Room loader (see there).
        // v1.90: unquoted hrefs too — valid HTML and
        // common on this old site, but the old pattern
        // required quotes, so such a page came back as
        // an empty list with a retry that could never
        // succeed. Groups 1-3 are the quoted/unquoted
        // href alternatives, group 4 the link text.
        Pattern pat = Pattern.compile("<a[^>]*href\\s*=\\s*(?:\"([^\"]*\\.mp3[^\"]*)\"|'([^']*\\.mp3[^']*)'|([^\\s>]*\\.mp3[^\\s>]*))[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = pat.matcher(html);
        Map<String, String> seen = new LinkedHashMap<String, String>();
        // v12.85: URLs whose stored title is the filename fallback (see below).
        java.util.Set<String> seenFallback = new java.util.HashSet<String>();
        while (m.find()) {
            String href = m.group(1) != null ? m.group(1)
                    : (m.group(2) != null ? m.group(2) : m.group(3));
            href = href.trim();
            // v11.83: a line-wrapped href (newline/tab
            // inside the quoted value) would poison every
            // newline-joined store the URL enters (hand
            // queue, handover map, trails) — the fragments
            // fail every restore filter and the talk
            // silently drops. Skip it, like any
            // unparseable href.
            if (href.indexOf('\n') >= 0 || href.indexOf('\r') >= 0
                    || href.indexOf('\t') >= 0) continue;
            String title = cleanTitle(m.group(4));
            // v12.85: remember whether this title is the filename
            // fallback, so a later duplicate link with a real title
            // can replace it (see the dedup below).
            boolean titleFallback = title.length() == 0;
            // v1.81: a link with no text used to show its
            // raw filename as the title — "Mogok%20Sayadaw
            // ..." instead of a readable name. Decode the
            // %XX escapes (and drop any query string) so
            // the fallback reads like a title, not a URL.
            if (title.length() == 0) title = decodePct(href.substring(href.lastIndexOf('/') + 1));
            // v1.78: one bad link used to kill the whole
            // list — a single href with a space or other
            // illegal character threw out of this loop
            // into the outer catch, so the sayadaw's
            // entire list failed with a retry button
            // instead of showing its good talks. Skip
            // just that link; the rest still load.
            // v1.80: but a literal space and an &amp;
            // escaped ampersand are not bad links — the
            // first threw in URL() so the talk silently
            // vanished, the second 404s as a raw href.
            // Normalise both before resolving (same fix
            // as the Room loader); truly illegal hrefs
            // still skip below.
            String abs;
            try {
                abs = new URL(base, href.replace("&amp;", "&")
                        .replace(" ", "%20")).toString();
            }
            catch (Exception ignored) { continue; }
            // v12.85: a duplicate URL keeps the first title — unless
            // the first was the filename fallback and this one is a
            // real title, which replaces it (a page listing the same
            // mp3 first as a bare link, later with its real title,
            // used to keep the filename as the row title).
            if (!seen.containsKey(abs)) {
                seen.put(abs, title);
                if (titleFallback) seenFallback.add(abs);
            } else if (!titleFallback && seenFallback.contains(abs)) {
                seen.put(abs, title);
                seenFallback.remove(abs);
            }
        }
        for (Map.Entry<String, String> e : seen.entrySet()) out.add(new Talk(e.getValue(), e.getKey()));
        return out;
    }

    private static String cleanTitle(String raw) {
        String title = raw.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'")
                // v1.90: named entities beyond the five hardcoded ones
                // used to render literally in titles (&rsquo; etc. on
                // this old site) — map the common punctuation ones
                // before the numeric decode and the final &amp; pass.
                .replace("&rsquo;", "'").replace("&lsquo;", "'")
                .replace("&rdquo;", "\"").replace("&ldquo;", "\"")
                .replace("&mdash;", "—").replace("&ndash;", "–")
                .replace("&hellip;", "…");
        try {
            Matcher nm = Pattern.compile("&#(\\d+);|&#x([0-9a-fA-F]+);").matcher(title);
            StringBuffer sb = new StringBuffer();
            while (nm.find()) {
                // v1.95: one bad code used to kill the whole decode
                // — an overflowing number (&#9999999999;) throws in
                // parseInt and the outer catch dropped every other
                // &#...; in the title too, like the v1.79 surrogate
                // case. Parse per code, so only that code is lost.
                String rep = "";
                try {
                    int code = nm.group(1) != null
                            ? Integer.parseInt(nm.group(1))
                            : Integer.parseInt(nm.group(2), 16);
                    // v1.79: a surrogate code point (&#55296; etc) used
                    // to throw inside toChars and drop the WHOLE entity
                    // decode — the title kept every raw &#...; code.
                    // Skip just that code; the rest still decode.
                    rep = (code > 0 && code <= 0x10FFFF
                            && (code < 0xD800 || code > 0xDFFF))
                            ? new String(Character.toChars(code)) : "";
                } catch (Exception ignored) {}
                nm.appendReplacement(sb, Matcher.quoteReplacement(rep));
            }
            nm.appendTail(sb);
            title = sb.toString();
        } catch (Exception ignored) {}
        title = title.replace("&amp;", "&");
        return title.replaceAll("\\s+", " ").trim();
    }

    // v1.81: %XX-decode a filename for use as a fallback title —
    // only %XX sequences are touched, so a literal '+' stays '+'
    // (URLDecoder would wrongly turn it into a space).
    private static String decodePct(String s) {
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < s.length();) {
                char c = s.charAt(i);
                if (c == '%' && i + 2 < s.length()) {
                    int hi = Character.digit(s.charAt(i + 1), 16);
                    int lo = Character.digit(s.charAt(i + 2), 16);
                    if (hi >= 0 && lo >= 0) {
                        baos.write(hi * 16 + lo);
                        i += 3;
                        continue;
                    }
                }
                baos.write(String.valueOf(c).getBytes("UTF-8"));
                i++;
            }
            return new String(baos.toByteArray(), "UTF-8");
        } catch (Exception ignored) { return s; }
    }

    // v2.79: premium Talks retry fade-out — the mirror of the
    // Room's v2.78 fade-out. The Talks retry used to snap GONE the
    // instant a new load started (a retry tap), a hard pop while
    // its appearance already fades in (v2.60) and every other Talks
    // change already moves. A visible retry now fades to 0 over
    // 200ms (decelerate, alpha-only) and only then goes GONE at
    // full alpha, so a later show starts clean. An already-hidden
    // button stays hidden with no animation (the layout default is
    // GONE, so a fresh open never fades anything). Alpha runs on
    // its own ObjectAnimator, not the ViewPropertyAnimator channel,
    // so the v2.28 press squash's scale spring-back on the same
    // button runs untouched beside it, and a failure arriving
    // mid-fade cancels this animator (see the show branch) instead
    // of fighting it. Visual only; text, tap and load behaviour
    // untouched.
    private void hideTalksRetry() {
        android.view.View retry = findViewById(R.id.btnRetry);
        if (retry == null) return;
        if (talksRetryFade != null) { talksRetryFade.cancel(); talksRetryFade = null; talksRetryFadeHiding = false; }
        if (retry.getVisibility() != View.VISIBLE) {
            retry.animate().cancel();
            retry.setAlpha(1f);
            retry.setVisibility(View.GONE);
            return;
        }
        talksRetryFadeHiding = true;
        talksRetryFade = android.animation.ObjectAnimator.ofFloat(retry, "alpha", retry.getAlpha(), 0f);
        talksRetryFade.setDuration(200);
        talksRetryFade.setInterpolator(new android.view.animation.DecelerateInterpolator());
        // v3.64: premium retry-hide restart guard — the hide end
        // action used to run unconditionally, while the show
        // branches already guard theirs on identity (rsFade
        // above): a new failure landing inside this 200ms fade
        // cancels the hide, and under the v3.19 behaviour the
        // cancelled hide's end action then hid the button the
        // show had just revealed (GONE + alpha 1 underneath a
        // fresh fade-in) — the same hole v3.62 closed for
        // Fx.fadeOut and v3.63 closed for themeDip, on the one
        // retry fade it never reached. Capturing this fade and
        // returning unless the field still holds it closes it:
        // a synchronous cancel fires while the field still
        // matches (the show then sets VISIBLE and fades in
        // over it, as before), while a late end landing after
        // the show assigned a new fade is dropped. Natural
        // completion is unchanged (field still matches, the
        // button still goes GONE at full alpha). Visual only.
        final android.animation.ObjectAnimator hideFade = talksRetryFade;
        talksRetryFade.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (talksRetryFade != hideFade) return;
                talksRetryFade = null; talksRetryFadeHiding = false;
                android.view.View r = findViewById(R.id.btnRetry);
                if (r != null) { r.setVisibility(View.GONE); r.setAlpha(1f); }
            }
        });
        talksRetryFade.start();
    }

    private void loadTalks() {
        final int mySeq = ++loadSeq;
        // v12.56: a new load invalidates the clean-empty state — the
        // flag is only re-set by this load's own completion below.
        talkCleanEmpty = false;
        // v2.62: premium loading-progress fade — the spinner used
        // to snap VISIBLE on load start and snap GONE on completion,
        // a hard pop while every other Talks change already moves
        // (v2.07 row cascade, v2.16 title entrance, v2.28 retry
        // press, v2.60 retry fade). A GONE -> VISIBLE spinner now
        // fades in with the existing Fx.fadeIn (250ms, decelerate,
        // alpha-only); an already-visible spinner (a retry tap
        // mid-fade-out, or a reload while one runs) just cancels
        // and resets to full alpha, so it never flickers.
        // The restore path above still hides it instantly — no
        // fade can be running on a fresh activity.
        // v3.55: premium spinner re-appearance continuity — the
        // already-visible branch above was the last show branch
        // in the Talks retry lifecycle that still snapped: it
        // cancelled whatever fade was running and set alpha
        // straight to 1, so a second retry tap inside the
        // 250ms fade-in window (the retry button is still
        // VISIBLE while its own 200ms hide fade runs) popped
        // a half-faded spinner back to full in one frame — the
        // same snap class v3.52 fixed inside Fx.fadeIn and
        // v3.53 fixed on the retry buttons, but this branch
        // bypassed both by resetting directly. It now splits
        // settled vs in-flight the v3.37/v3.49/v3.53 way: a
        // settled spinner (alpha ~1, a reload while one runs)
        // keeps the old instant reset with no pointless fade,
        // while a mid-fade spinner glides back to 1 over
        // 250ms (decelerate) on the same ViewPropertyAnimator
        // channel Fx.fadeIn/Fx.fadeOut use, so a completion
        // fade-out landing mid-glide cancels it and fades from
        // its current alpha, and a further retry re-glides
        // from wherever it sits. No end action, so a cancel
        // can never strand the spinner; the GONE branch, the
        // fade-in duration and the hide path are untouched.
        // Visual only; load, retry and completion behaviour
        // untouched.
        if (progress.getVisibility() != View.VISIBLE) {
            progress.setVisibility(View.VISIBLE);
            Fx.fadeIn(progress, 250);
        } else if (progress.getAlpha() >= 0.999f) {
            // v3.62: route the cancel through Fx.cancelFade — a raw
            // cancel here could let a running completion fadeOut's
            // end action hide the spinner (GONE) for the whole retry
            // load; the guard keeps it visible at full alpha.
            Fx.cancelFade(progress);
            progress.setAlpha(1f);
        } else {
            Fx.cancelFade(progress);
            progress.animate().alpha(1f).setDuration(250)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
        // v2.79: the retry exit is a fade now (see hideTalksRetry),
        // the mirror of the Room's v2.78 fade-out — the old snap
        // here was the last hard pop in the Talks retry lifecycle
        // (its appearance already fades in since v2.60).
        hideTalksRetry();
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Talk> found = new ArrayList<Talk>();
                String err = null;
                // v1.58: disconnect in a finally — v1.57 only released
                // on the success path, so a failed load leaked its socket.
                HttpURLConnection conn = null;
                InputStream in = null;
                try {
                    conn = (HttpURLConnection) new URL(pageUrl).openConnection();
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                    in = conn.getInputStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
                    byte[] bytes = baos.toByteArray();
                    String html = new String(bytes, "UTF-8");
                    // v1.87: the standard meta form quotes the value
                    // (charset="utf-8"), and the old pattern required
                    // the value to start right after '=' — a quoted
                    // charset never matched, so a non-UTF-8 page kept
                    // its garbled first-decode titles. Allow the quote.
                    Matcher cm = Pattern.compile("charset=[\"']?([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(html);
                    if (cm.find()) {
                        try { html = new String(bytes, cm.group(1)); } catch (Exception ignored) {}
                    }
                    found.addAll(parseTalkList(pageUrl, html));
                } catch (Exception e) {
                    // v1.92: a null/empty message used to leave err
                    // null, so the failure fell through to the
                    // "no talks found" toast and hid that the load
                    // itself had failed (the retry button still
                    // showed only because the list was empty). A
                    // failure always carries a message now, so the
                    // failure toast + retry are the ones shown.
                    err = e.getMessage();
                    if (err == null || err.length() == 0) err = Lang.t(TalksActivity.this, "internet_error");
                } finally {
                    // v1.66: a read dying mid-stream used to leak the
                    // stream itself — the connection was disconnected
                    // (v1.58) but the stream was only closed on the
                    // success path, the same class ImageLoader fixed
                    // in v1.65. Close it here too.
                    if (in != null) { try { in.close(); } catch (Exception ignored) {} }
                    if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
                }
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // v1.63: a load finishing after back-press used
                        // to touch this dead screen (views + toasts on
                        // a finished activity) — drop it silently.
                        if (isFinishing() || isDestroyed()) return;
                        // v1.83: a stale load (see loadSeq) must not
                        // overwrite a newer load's list.
                        if (mySeq != loadSeq) return;
                        // v2.62: completion fades the spinner out
                        // (250ms, then GONE + alpha restored, same
                        // rhythm as the row pour below), instead of
                        // snapping it away in the frame the rows
                        // appear. A stale load returns above, so
                        // only the owning load hides it; a retry
                        // tap mid-fade-out cancels via the show
                        // branch in loadTalks, and the end action
                        // never runs on a cancelled animator.
                        Fx.fadeOut(progress, 250);
                        talks.clear();
                        talks.addAll(found);
                        // v12.56: remember a clean empty landing — a
                        // page fetched fine with zero talks listed.
                        // A failed load leaves it false (rotation
                        // refetches, as today), and a non-empty load
                        // restores from its list anyway; only the
                        // clean-empty case needs the flag, because
                        // the restore gate requires a non-empty list.
                        talkCleanEmpty = (ferr == null && found.isEmpty());
                        // v1.89: a fresh list must animate in once
                        // (v1.52) — the old code kept lastAnimPos
                        // from the previous list, so after a reload
                        // every row sat at/below the old end and no
                        // row animated at all. The restored path
                        // above still pins it to size-1 on purpose.
                        lastAnimPos = -1;
                        refreshQueueMarks();
                        // v5.74: the prune above used to die in memory — refreshQueueMarks
                        // already drops saved queue URLs gone from this fresh list, so the
                        // chooser header and its own ◈ marks read true, but the disk key
                        // kept the ghosts, and every raw-queue reader (all four mini-player
                        // strips v4.67/v4.68, the plain notification card) kept counting
                        // them until the Room happened to open and its v5.73 peek prune
                        // rewrote the key. A fresh list landing is exactly when staleness
                        // is observed, so the prune is written back here — load point only,
                        // never from refreshQueueMarks itself, which the 1s strip tick calls
                        // (tick must never write prefs). Fires only when a raw entry is
                        // genuinely absent from the pruned cache (dedup alone never writes,
                        // an empty/failed list never prunes: talks non-empty is required),
                        // and Prefs.saveQueue guards a null/empty sayadaw name. Play order
                        // is unchanged — the pruned cache is the same list the header
                        // already counts — only the disk readers change, from inflated
                        // to true, within a tick of the list landing.
                        // v12.69 (sim1272 case 2): the v5.74 loop skipped EVERY v2 line
                        // (the v7.09 blanket rule), but the Room's filter (v11.75) drops
                        // a same-owner v2 line that left the list — so a same-owner v2
                        // ghost survived every chooser visit and kept inflating the
                        // strips/card until the Room's peek happened to run. The gate
                        // is now the shared pure core: a v2 line is stale only when
                        // its owner IS this sayadaw; a foreign v2 line still rides
                        // (the v7.09 verb's line), and the sounding talk is skipped,
                        // never pruned (Room peek v5.86 — only playIndex drops a
                        // played talk). The write still funnels through
                        // saveTalkQueueKeepingForeign, with its card refresh.
                        if (!talks.isEmpty()) {
                            try {
                                String rawQ = Prefs.savedQueue(TalksActivity.this, sayadawName);
                                if (rawQ != null && rawQ.length() > 0 && talkQueue != null) {
                                    java.util.HashSet<String> inList69 =
                                            new java.util.HashSet<String>(talkQueue);
                                    if (Prefs.queueHasStaleLinePure(rawQ, inList69,
                                            sayadawName, talkPlayingUrl))
                                        saveTalkQueueKeepingForeign(talkQueue, (String) null);
                                }
                            } catch (Exception ignored) {}
                        }
                        refreshTalkRowMarks(true); // v5.49: row marks ready before the rebind below
                        // v4.93: a landed list reveals the search box
                        // (empty loads keep it hidden, like the rows).
                        android.view.View sRow = findViewById(R.id.talkSearchRow);
                        if (sRow != null) sRow.setVisibility(found.isEmpty() ? View.GONE : View.VISIBLE);
                        rebuildSearch();
                        // v4.94: a fresh list re-indexes the filter
                        // too — old indices would mis-map onto the
                        // new list; the mode itself is preserved.
                        rebuildFilter();
                        adapter.notifyDataSetChanged();
                        // v4.24: the sayadaw title carries the talk
                        // count ("name (24)") once a list lands, so
                        // a long list's size reads without counting
                        // rows. Built from the intent name every
                        // time, never from the view's own text, so
                        // a reload or rotation can never stack
                        // "(24) (24)". Empty loads keep the plain
                        // name beside the retry button.
                        if (!talks.isEmpty() && sayadawName != null) refreshFavHeader();
                        // v1.56: a failed/empty load used to show only the
                        // retry button with no word about what happened —
                        // the Room already toasts its failures, so does
                        // the chooser now (transient toast, no new UI).
                        if (ferr != null)
                            android.widget.Toast.makeText(TalksActivity.this,
                                    Lang.t(TalksActivity.this, "talks_fetch_failed_long"),
                                    android.widget.Toast.LENGTH_LONG).show();
                        else if (found.isEmpty())
                            android.widget.Toast.makeText(TalksActivity.this,
                                    Lang.t(TalksActivity.this, "talks_none_for_sayadaw"),
                                    android.widget.Toast.LENGTH_LONG).show();
                        // v2.60: premium retry-button fade — the
                        // button used to snap VISIBLE the instant a
                        // load failed or came back empty, a hard
                        // pop beside the failure toast while every
                        // other Talks change already moves (v2.07
                        // row cascade, v2.16 title entrance, v2.28
                        // retry press). A GONE -> VISIBLE retry now
                        // fades in with the existing Fx.fadeIn
                        // (250ms, decelerate, alpha-only, same as
                        // the v2.51 library empty-state fade), so
                        // the failure lands softly. An already
                        // visible button just resets to full alpha
                        // (no flicker on a repeated failure), and
                        // loadTalks hides it with the fade cancelled
                        // above, so a retry tap mid-fade never
                        // strands it. Visual only; text, tap and
                        // the failure toasts are untouched.
                        // v12.31: retry only on a real failure — a
                        // clean empty load (page fetched fine, this
                        // sayadaw simply has no talks listed) used to
                        // show the retry button beside the "no talks"
                        // toast, so every retry tap re-fetched the
                        // same page and re-toasted the same message in
                        // a loop that could never succeed. Retry is a
                        // failure-recovery control; the Room already
                        // shows its retry only when ferr != null
                        // (v2.61). The two toasts above are untouched,
                        // and re-entry still reloads (a sayadaw who
                        // gains talks later shows them on reopen).
                        if (ferr != null) {
                            android.view.View retryShow = findViewById(R.id.btnRetry);
                            if (retryShow != null) {
                                // v2.79: a failure landing mid-fade-out
                                // cancels it first, so the fade can
                                // never finish and set GONE under a
                                // freshly shown button.
                                if (talksRetryFade != null) { talksRetryFade.cancel(); talksRetryFade = null; talksRetryFadeHiding = false; }
                                if (retryShow.getVisibility() != View.VISIBLE) {
                                    retryShow.setVisibility(View.VISIBLE);
                                    Fx.fadeIn(retryShow, 250);
                                } else if (retryShow.getAlpha() < 0.999f) {
                                    // v3.53: premium retry re-appearance continuity — the v2.79
                                    // cancel above left the button half-faded (a failure landing
                                    // inside the 200ms fade-out), and this branch then snapped
                                    // it straight to alpha 1 in one frame — the same snap class
                                    // v3.52 fixed for Fx.fadeIn, but on this button's own
                                    // ObjectAnimator alpha channel, so the v2.28 press squash's
                                    // scale spring-back beside it is untouched. A mid-fade button
                                    // now glides back to 1 over 250ms (decelerate); the fade-in
                                    // reuses talksRetryFade, so a new load's hide cancels it and
                                    // fades out from its current alpha, and a second failure
                                    // re-glides from wherever it sits. Its end action only nulls
                                    // the field (guarded on identity), never hides or re-alphas,
                                    // so a cancel can never strand the button. A settled button
                                    // (alpha ~1, a repeated failure) keeps the old instant reset
                                    // below, with no pointless fade.
                                    final android.view.View rs = retryShow;
                                    talksRetryFade = android.animation.ObjectAnimator.ofFloat(rs, "alpha", rs.getAlpha(), 1f);
                                    talksRetryFade.setDuration(250);
                                    talksRetryFade.setInterpolator(new android.view.animation.DecelerateInterpolator());
                                    final android.animation.ObjectAnimator rsFade = talksRetryFade;
                                    talksRetryFade.addListener(new android.animation.AnimatorListenerAdapter() {
                                        @Override public void onAnimationEnd(android.animation.Animator a) {
                                            if (talksRetryFade == rsFade) talksRetryFade = null;
                                        }
                                    });
                                    talksRetryFade.start();
                                } else {
                                    retryShow.animate().cancel();
                                    retryShow.setAlpha(1f);
                                }
                            }
                        }
                        boolean autoOpened = false;
                        if (autoUrl != null && autoUrl.length() > 0) {
                            String want = autoUrl;
                            // v10.84: same class as the Room fix —
                            // a failed or empty load used to consume
                            // the auto talk while suppressing its
                            // "not in list" toast, so a retry that
                            // then succeeded never auto-opened it.
                            // Only a clean, non-empty load consumes
                            // it now; a failed/empty load keeps it
                            // for the retry. (talks already holds
                            // the landed list here, so it is the
                            // clean-load test; the boolean below
                            // shadows the outer list name, hence
                            // talks, not found, is used.)
                            if (ferr == null && !talks.isEmpty()) autoUrl = null;
                            boolean found = false;
                            for (int i = 0; i < talks.size(); i++) {
                                if (talks.get(i).url.equals(want)) { autoOpened = true; openRoom(i); found = true; break; }
                            }
                            // v1.82: same silent miss as the restored
                            // path above — the saved talk is gone
                            // from the site's list, so say so.
                            // v1.88: but only on a clean, non-empty
                            // load — the Room got this guard in
                            // v1.72, the chooser's fresh-load path
                            // never did, so a FAILED load toasted
                            // both "load failed" and the lie that the
                            // talk had left the list (and an empty
                            // load doubled its own message). The
                            // failure/empty toasts above already
                            // cover those cases.
                            if (!found && ferr == null && !talks.isEmpty())
                                android.widget.Toast.makeText(TalksActivity.this,
                                        Lang.t(TalksActivity.this, "talk_no_longer_in_list"), android.widget.Toast.LENGTH_SHORT).show();
                        }
                        // v3.91: Talks auto-scroll to playing talk on
                        // fresh open (expanded-loop backlog item 1) —
                        // opening a sayadaw's list while one of their
                        // talks plays used to land at the top, so a
                        // long list hid the talk that was actually
                        // sounding. On the first successful load of
                        // this instance (fresh open, or a retry after
                        // a failed load — the list was empty then, so
                        // nothing is yanked), a live Room session for
                        // THIS sayadaw now lands the list on its talk
                        // instead. Guards: a live session alone is
                        // not enough — the service also stays alive
                        // for library plays, so the lastKind="room"
                        // anchor (written by every Room play in
                        // saveLastTalk, overwritten by every library
                        // play) must agree, and the sayadaw name must
                        // match, so another sayadaw's list never
                        // scrolls and a stopped session (service
                        // destroyed, liveSession false) never
                        // scrolls either. The match is the stream URL
                        // itself, so a downloaded talk matches too
                        // (its Room play saved t.url, never the file
                        // path). setSelection, not a glide: the list
                        // was never visible (spinner fading out), so
                        // the jump is invisible exactly the way
                        // v3.87's fresh-open rule keeps it, and the
                        // v2.07 row cascade still pours the visible
                        // rows in place around the playing row, which
                        // lands first-visible — the visible signal,
                        // with NO new UI element and NO mark added
                        // (the Talks list-only spec is untouched: rows
                        // stay number + title, no buttons, no marks).
                        // A rotation restore never reaches here (its
                        // list is restored, not loaded), a back-stack
                        // return never reloads, and an auto-opened
                        // Room skips it (we are navigating away).
                        // Visual only; load, retry and tap behaviour
                        // untouched.
                        if (!scrolledToPlaying && ferr == null && !autoOpened && !talks.isEmpty()) {
                            scrolledToPlaying = true;
                            // v10.43: bound-trackless stop — liveSession stays true but the mirror is false, so never land on a stopped talk.
                            if (AudioService.liveSession && AudioService.liveHasTrack) {
                                try {
                                    android.content.SharedPreferences sp =
                                            getSharedPreferences("dhamma", MODE_PRIVATE);
                                    if ("room".equals(sp.getString("lastKind", ""))
                                            && sayadawName != null
                                            && sayadawName.equals(sp.getString("lastName", ""))) {
                                        String liveUrl = sp.getString("lastUrl", "");
                                        if (liveUrl.length() > 0) {
                                            for (int i = 0; i < talks.size(); i++) {
                                                if (talks.get(i).url.equals(liveUrl)) {
                                                    // v12.85: a talk index is only a display
                                                    // position in the full list, so never
                                                    // trust it (the queue-only exit that
                                                    // used to run here is deleted).
                                                    talkList.setSelection(i);
                                                    // v3.94: anchor the return-scroll on
                                                    // this talk, so a later return with
                                                    // the same talk still playing does
                                                    // not yank the list.
                                                    lastSeenPlayingUrl = liveUrl;
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                });
            }
        }).start();
    }
}
