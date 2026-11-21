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
    // v14.23 (slice 1677): key of the visible set the rows last
    // poured for — the change-only guard for the chooser pour
    // (see maybePourOnListChange below). Null until the first
    // chip tap / search keystroke, so that first change always
    // pours; a rotation restores it null too, but the restore
    // flag (below) keeps the restored rows still regardless.
    private String lastVisibleKey = null;
    // v15.18 (slice 1781): old display positions for the chooser rank-close glide (see captureTalkSetGlide) — talk index -> old display position, armed by a filter/search set change, cleared by the posted glide runnable; a newer set change re-arms it first.
    private java.util.HashMap<Integer, Integer> talkSetGlidePos = null;
    // v14.23 (slice 1677): rotation-restore flag — while this is
    // up, the search watcher rebuilds + rebinds but never pours
    // (Main's restoringSearch, same shape). Set only around the
    // saved-query setText in the onCreate restore branch; a real
    // keystroke never sees it up.
    private boolean restoringTalkSearch = false;
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
    // v14.79 (slice 1737): READ the set in canonical form —
    // every write funnel banks the https identity (Fx.toggleFavTalk
    // v13.65, the v13.66 lazy-once migration heals stored rows in
    // place), so a raw contains() misses an http-spelled talk
    // and its star never appears on the chooser row, in the
    // ★-only filter, on the header count, on the Up Next /
    // foreign-head lines, or in the row-menu label — even though
    // the toast says it was favourited. The http form genuinely
    // reaches this chooser: one catalog page carries 15 absolute
    // http mp3 hrefs (Dr Nandamalabhivamsa), which parseTalkList
    // keeps as-is. All nine read sites below compare
    // SiteHttp.identityUrl() of the talk URL — identity for https,
    // so https talks read exactly as today. The cached set itself
    // stays raw: the 1s tick's v7.55 change detector snapshots
    // this cache and .equals-compares it after refreshTalkRowMarks,
    // and canonicalising the cache would churn that detector.
    // The Room twin is v14.78 (slice 1736).
    // v14.84 (slice 1742): the played twin of the v14.79 star
    // fix — every playedTalks read in this chooser compared
    // the raw talk URL while the set holds canonical https
    // (the v13.66 migration heals stored rows in place; the
    // v13.59 service stream leg and the v13.79 fallback bank
    // canonical; the Room end block banks canonical since
    // v14.83), so a talk finishing while its list URL was
    // http-spelled (absolute-http href, list parsed over the
    // v13.57 http retry, foreign queue-line talk) lost its
    // tick on the chooser row, the header count, the Up Next
    // / foreign-head lines, the "New" filter and its rotation
    // restore — while its star stayed right on the same row.
    // All six read sites now compare SiteHttp.identityUrl()
    // — identity for https, so https talks read exactly as
    // today. The cached set itself stays raw, same reason as
    // the star note above (the v7.55 .equals detector).
    // Remaining raw planters live in other activities — the
    // Downloaded end block + mark-shown verbs and the
    // AudioService library-file leg bank dlMap keys, legacy
    // http for pre-v13.67 downloads — their slices follow; a
    // tick planted from the Library still misses here until
    // then.
    private java.util.Set<String> talkPlayedCache = new java.util.HashSet<String>();
    // v15.41 (slice 1920): newly-added talks — the identity URLs
    // that arrived since the previous successful load of this
    // sayadaw (TalkListCache new-set, replaced per load).
    // getView paints a ● before the title while the talk is
    // still unstarted and unlistened; loaded in onCreate + at
    // each landing, so the rotation-restore path needs none.
    private java.util.Set<String> newTalkUrls = new java.util.HashSet<String>();
    // v13.97 (slice 1092): per-talk total durations (url -> ms),
    // one parse per refreshTalkRowMarks below, so getView paints
    // the hairline from a map lookup, never talkDurGet per row.
    private java.util.Map<String, Integer> talkDurCache = new java.util.HashMap<String, Integer>();
    private java.util.Set<String> talkDlCache = new java.util.HashSet<String>();
    // v14.47 (slice 1704): the just-landed talk melts in — the
    // downloaded-set scan in refreshTalkRowMarks below used to end
    // in a bare notifyDataSetChanged, so a file landing while the
    // chooser sat open flipped the row's ⏳→⬇ mark in one frame
    // while every other chooser arrival already has a beat (list
    // pour v14.23, landing rows v14.45, search-row fades v14.44,
    // Room drop landing v14.46). dlLandFadeUrl holds the first
    // newly-landed URL (talk order) until fadeLandedTalkRow
    // consumes it; talkDlCachePrimed stays false until the first
    // scan fills the cache, so a load or rotation restore — which
    // also fills it — never arms a fade.
    private String dlLandFadeUrl = null;
    private boolean talkDlCachePrimed = false;
    // v5.59: the filename isTalkDownloaded matched per URL, filled
    // during the same scan that builds talkDlCache above — so the
    // ↺ hint can look a talk's spot up under its downloaded-file
    // form from the cache alone, never per bind, mirroring the
    // Room drawer's downloadedFiles set (v1.57 rule).
    private java.util.Map<String, String> talkDlFileCache = new java.util.HashMap<String, String>();
    private java.util.Set<String> talkDlQueuedCache = new java.util.HashSet<String>();
    // v14.08 (slice 1103): live download percents for the chooser rows, identity URL -> pct — the Room drawer rows (v4.24), the Room title (v4.27) and the Downloaded rows (v4.27) all name the number, but the chooser rows read a bare ⏳ until the file lands. Refreshed by refreshTalkDlProgress at the tail of refreshTalkRowMarks (every 1s tick via refreshTalksQueuePreview, plus every load/return/menu call site), so a percent moves within a tick; the tick snapshots it with the other mark caches and rebinds on a change. Empty while nothing is queued, so the getView lookup below never inherits a stale number.
    private java.util.Map<String, Integer> talkDlProgress = new java.util.HashMap<String, Integer>();
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
    // v13.76 (slice 1068): Play All double-tap guard — uptime of the
    // last Play All tap; a second tap inside 700ms is dropped, the
    // v12.28 row-tap shape, so a double-tap cannot stack a second
    // Room while the first is still starting.
    private long lastPlayAllMs = 0;
    // v15.45 (slice 1942): Play-from-here double-tap guard
    // — uptime of the last Play-from-here tap; a second tap
    // inside 700ms is dropped, the v12.28 row-tap shape, so
    // a double-tap cannot stack a second Room while the
    // first is still starting.
    private long lastPlayFromHereMs = 0;
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
                            // v13.62 (F-1024-A-1): the DM job's source URL is
                            // the transport form while dlMap keys are https —
                            // compare by identity (v13.60 DownloadedActivity shape).
                            if (!added && src != null && dlMap.containsKey(SiteHttp.identityUrl(src))) {
                                String fn = dlMap.get(SiteHttp.identityUrl(src));
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
    // (shared DlLanded definition: non-empty file, map + candidates +
    // claim guard, never a live job's destination) or once no live
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
        // v13.68 (audit-1369 F11): dlMap/dlSayadaw/dlQueued read-modify-write - serialize on DlLock.LOCK (see DlLock).
        synchronized (DlLock.LOCK) {
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
                            // v13.59 (887 #5/F945-3): the DM source
                            // may be the http form of a queued https URL.
                            if (src != null && !queued.contains(src)
                                    && !queued.contains(SiteHttp.identityUrl(src))
                                    && (uri == null || !uri.contains("Dhamma/"))) continue;
                            if (src == null && uri != null && !uri.contains("Dhamma/")) continue;
                            if (src != null) activeSources.add(SiteHttp.identityUrl(src));
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
                // v13.69 (audit-1369 F13): the landed leg is the shared
                // DlLanded definition now - the same non-empty-file /
                // mapped-or-candidate / claim-guarded check Room,
                // Library, Main and Settings use, with this prune's
                // freshly refreshed in-progress names as the exclusion
                // (the same set isTalkDownloaded excluded on) plus the
                // prune's live sources. isTalkDownloaded stays for the
                // scan, the rows and the strips; the prune no longer
                // warms talkDlFileCache - the 30s scan below rebuilds
                // it on the same gate.
                if (DlLanded.isDownloaded(this, url, talkInProgressDlNames, activeSources)) { it.remove(); changed = true; continue; }
                // v14.91 (slice 1749): the prune's live-source leg reads
                // the canonical form — activeSources holds identityUrl(src)
                // (v13.59), but the lookup read the raw queued url, so an
                // http-spelled queued talk missed its live job entirely:
                // a Wi-Fi-waiting job (no destination name yet) then had
                // its hourglass pruned while still live, and a re-tap
                // could double-enqueue. Same provenance as the mapped
                // twins (legacy http dlMap keys, v13.57 fallback, never migrated
                // by v13.66; canonical https keys, v13.59;
                // absolute-http hrefs kept as-is, 1737): one talk, two
                // spellings. For an https queued url this is identical
                // (identityUrl is the identity on https); for a non-site
                // url it is unchanged too.
                if (activeSources.contains(SiteHttp.identityUrl(url))) continue;
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
                    if (SiteHttp.sameTalk(dropped1017, lastUrl1017)) { cardTalk1017 = true; break; }
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
        // v13.97 (slice 1092): durations for the listened-progress
        // hairline — one parse for the whole list (Prefs.talkDurMap,
        // the talkPosKeysOver shape), refreshed with the other row
        // caches so a return from a just-finished listen repaints
        // the fraction at once; getView reads this map only, never
        // talkDurGet per row.
        talkDurCache = Prefs.talkDurMap(TalksActivity.this);
        try {
            java.util.Set<String> q = new java.util.HashSet<String>();
            org.json.JSONArray arr = new org.json.JSONArray(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlQueued", "[]"));
            for (int i = 0; i < arr.length(); i++) q.add(SiteHttp.identityUrl(arr.optString(i)));
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
            // v14.47 (slice 1704): additions only, primed only — a
            // removal (deleted file) flips a mark too but is not a
            // landing, and the first scan is a load/restore fill,
            // not a landing. talkDlCache still holds the OLD set
            // here, so the difference is exact; the fade arms only
            // when the set really changed, and the helper fades
            // the first landed URL in talk order (one row per
            // scan — several landings in one tick melt the first,
            // the rest already read ⬇ on the same rebind).
            java.util.Set<String> landed = null;
            if (talkDlCachePrimed && changed) {
                landed = new java.util.HashSet<String>(dl);
                landed.removeAll(talkDlCache);
            }
            talkDlCache = dl;
            talkDlCachePrimed = true;
            if (changed && adapter != null) adapter.notifyDataSetChanged();
            if (landed != null && !landed.isEmpty() && talks != null) {
                String first = null;
                for (Talk t : talks) {
                    if (t != null && landed.contains(t.url)) { first = t.url; break; }
                }
                if (first != null) { dlLandFadeUrl = first; fadeLandedTalkRow(); }
            }
        }
        // v7.77: a prune above changed the queued cache the rows bind
        // against, but the scan branch above only rebinds on a
        // downloaded-set change — a dropped ⏳ would otherwise sit
        // until the next rebind. The queued cache was already rebuilt
        // from the pruned prefs above, so this rebind reads it fresh.
        if (pruned77 && adapter != null) adapter.notifyDataSetChanged();
        refreshTalkDlProgress();
    }

    // v14.08 (slice 1103): refresh the chooser rows' live download percents — Fx.dlProgressByUrl pays one filtered DownloadManager query, folded onto talkDlQueuedCache (fresh from refreshTalkRowMarks above, so a just-queued download starts ticking at once). Empty queue pays no query: the map clears, so a later queue never inherits an old download's number (the Room prune's rule). A failed query returns an empty map, so the rows fall back to the bare ⏳ rather than a stale number.
    private void refreshTalkDlProgress() {
        try {
            if (talkDlQueuedCache == null || talkDlQueuedCache.isEmpty()) {
                if (!talkDlProgress.isEmpty()) talkDlProgress.clear();
                return;
            }
            java.util.Map<String, Integer> next = Fx.dlProgressByUrl(TalksActivity.this,
                    getSharedPreferences("dhamma", MODE_PRIVATE), talkDlQueuedCache);
            if (next == null) next = new java.util.HashMap<String, Integer>();
            talkDlProgress = next;
        } catch (Exception ignored) {}
    }
    // v14.47 (slice 1704): melt the just-landed talk's row in at
    // its display position — the v14.46 Room drop-landing shape
    // (post after the rebind, resolve fresh, fade 250ms). The
    // display position resolves through visibleHits, so a search
    // or filter narrows it and a landed talk filtered out of the
    // shown list has no row (-1); a scrolled-out row (getChildAt
    // null) is skipped, never scrolled to. Consume-once: a second
    // run without a new landing is a no-op. Guards: the activity
    // may be finishing/destroyed by the time the post runs, and a
    // return theme-dip swap (v2.99) rebinds after this post — the
    // v14.47 getView hygiene below restores row alpha 1 on any
    // rebind, so a fade cut short by it can never strand the row
    // half-transparent. The 1s tick's percent rebind is the same
    // class: a landing leaves the queued set, so no percent rebind
    // follows it, and any other row's rebind only cancels this
    // fade early, never strands it.
    private void fadeLandedTalkRow() {
        final String url = dlLandFadeUrl;
        dlLandFadeUrl = null;
        if (url == null || talkList == null) return;
        talkList.post(new Runnable() {
            @Override public void run() {
                if (talkList == null || isFinishing() || isDestroyed()) return;
                int ti = -1;
                if (talks != null) for (int i = 0; i < talks.size(); i++) {
                    if (talks.get(i) != null && SiteHttp.sameTalk(talks.get(i).url, url)) { ti = i; break; }
                }
                if (ti < 0) return;
                int disp = (visibleHits != null) ? visibleHits.indexOf(Integer.valueOf(ti)) : ti;
                if (disp < 0) return;
                int first = talkList.getFirstVisiblePosition();
                android.view.View row = talkList.getChildAt(disp - first);
                if (row == null) return;
                Fx.fadeIn(row, 250);
            }
        });
    }
    // v14.48 (slice 1705): melt the newly-sounding talk's row in at
    // its display position — the v14.47 download-landing shape
    // (post after the rebind, resolve fresh, fade 250ms). The
    // display position resolves through visibleHits, so a search
    // or filter narrows it and a sounding talk filtered out of the
    // shown list has no row (-1); a scrolled-out row (getChildAt
    // null) is skipped, never scrolled to — the return glide
    // (scrollToPlayingOnReturn) already scrolls to an off-screen
    // sounding row on its own, and only fires when the row is
    // off-screen, so the two never fight: a row the glide moves
    // was skipped here, a row on screen never glides. Consume-once:
    // a second run without a new sounding change is a no-op.
    // Guards: the activity may be finishing/destroyed by the time
    // the post runs, and a return theme-dip swap (v2.99) rebinds
    // after this post — the v14.47 getView hygiene restores row
    // alpha 1 on any rebind, so a fade cut short by it can never
    // strand the row half-transparent. Fx.fadeIn is restart-safe
    // (v3.52: a second fade flows from the current alpha), so a
    // download-landing melt landing on the same row in the same
    // tick just restarts the glide and still ends at alpha 1.
    private void fadeSoundingTalkRow() {
        final String url = soundingFadeUrl;
        soundingFadeUrl = null;
        if (url == null || talkList == null) return;
        talkList.post(new Runnable() {
            @Override public void run() {
                if (talkList == null || isFinishing() || isDestroyed()) return;
                int ti = -1;
                if (talks != null) for (int i = 0; i < talks.size(); i++) {
                    if (talks.get(i) != null && SiteHttp.sameTalk(talks.get(i).url, url)) { ti = i; break; }
                }
                if (ti < 0) return;
                int disp = (visibleHits != null) ? visibleHits.indexOf(Integer.valueOf(ti)) : ti;
                if (disp < 0) return;
                int first = talkList.getFirstVisiblePosition();
                android.view.View row = talkList.getChildAt(disp - first);
                if (row == null) return;
                Fx.fadeIn(row, 250);
            }
        });
    }
    // v14.60 (slice 1717): melt the just-stopped row in at its
    // display position — the fadeSoundingTalkRow shape above,
    // restated for the DEPARTING talk. The posted runnable runs
    // after the branch's rebind, resolves the talk index with
    // sameTalk and the display position through visibleHits (a
    // search/filter narrows it, a filtered-out departing talk
    // has no row), and fades 250ms (the v14.48 rhythm); a
    // scrolled-out row is skipped, never scrolled to. The
    // v14.47 getView hygiene restores row alpha 1 on any rebind,
    // so a fade cut short by a tick percent rebind, a return
    // theme-dip swap or a filter/search change can never strand
    // the row half-transparent; Fx.fadeIn is restart-safe
    // (v3.52), so a sounding/landing melt on the same row in the
    // same tick just restarts the glide. The whole-row fade keeps
    // the listened title dim intact (per-bind, v14.47 note).
    // Consume-once: the field is nulled here.
    private void fadePrevSoundTalkRow() {
        final String url = prevSoundFadeUrl;
        prevSoundFadeUrl = null;
        if (url == null || talkList == null) return;
        talkList.post(new Runnable() {
            @Override public void run() {
                if (talkList == null || isFinishing() || isDestroyed()) return;
                int ti = -1;
                if (talks != null) for (int i = 0; i < talks.size(); i++) {
                    if (talks.get(i) != null && SiteHttp.sameTalk(talks.get(i).url, url)) { ti = i; break; }
                }
                if (ti < 0) return;
                int disp = (visibleHits != null) ? visibleHits.indexOf(Integer.valueOf(ti)) : ti;
                if (disp < 0) return;
                int first = talkList.getFirstVisiblePosition();
                android.view.View row = talkList.getChildAt(disp - first);
                if (row == null) return;
                Fx.fadeIn(row, 250);
            }
        });
    }
    // v14.55 (slice 1712): melt the new Up Next row in at its display
    // position — the fadeSoundingTalkRow shape above, restated for the
    // ▸ row (v13.85). The posted runnable runs after the branch's
    // rebind, resolves the talk index with sameTalk and the display
    // position through visibleHits (a search/filter narrows it, a
    // filtered-out ▸ talk has no row), and fades 250ms (the v14.48
    // rhythm); a scrolled-out row is skipped, never scrolled to. The
    // v14.47 getView hygiene restores row alpha 1 on any rebind, so a
    // fade cut short by a tick percent rebind, a return theme-dip swap
    // or a filter/search change can never strand the row
    // half-transparent; Fx.fadeIn is restart-safe (v3.52), so a
    // sounding/landing melt on the same row in the same tick just
    // restarts the glide. Consume-once: the field is nulled here.
    private void fadeUpNextTalkRow() {
        final String url = upNextFadeUrl;
        upNextFadeUrl = null;
        if (url == null || talkList == null) return;
        talkList.post(new Runnable() {
            @Override public void run() {
                if (talkList == null || isFinishing() || isDestroyed()) return;
                int ti = -1;
                if (talks != null) for (int i = 0; i < talks.size(); i++) {
                    if (talks.get(i) != null && SiteHttp.sameTalk(talks.get(i).url, url)) { ti = i; break; }
                }
                if (ti < 0) return;
                int disp = (visibleHits != null) ? visibleHits.indexOf(Integer.valueOf(ti)) : ti;
                if (disp < 0) return;
                int first = talkList.getFirstVisiblePosition();
                android.view.View row = talkList.getChildAt(disp - first);
                if (row == null) return;
                Fx.fadeIn(row, 250);
            }
        });
    }
    // v14.75 (slice 1733): melt the just-marked row in at its display
    // position — the fadeLandedTalkRow shape (post after the rebind,
    // resolve fresh, fade 250ms). The display position resolves
    // through visibleHits, so a search or filter narrows it; a row
    // filtered out of the shown list has no row (-1) — the star-only
    // unstar case, where rebuildFilter already dropped the row
    // before the rebind, so nothing fades and the row just leaves.
    // A scrolled-out row is skipped, never scrolled to. The v14.47
    // getView hygiene restores row alpha 1 on any rebind, so a fade
    // cut short by a tick percent rebind, a return theme-dip swap
    // or a filter/search change can never strand the row
    // half-transparent; Fx.fadeIn is restart-safe (v3.52), so a
    // landing/sounding melt on the same row in the same tick just
    // restarts the glide. The 1s tick cannot double-melt: the menu
    // branches reload the mark caches synchronously
    // (refreshTalkRowMarks / refreshQueueMarks), so the tick
    // snapshots the new state and sees no change, and the tick
    // arms no melt for a pure mark change anyway. Consume-once:
    // the field is nulled here.
    private void fadeMenuMarkedTalkRow() {
        final String url = menuMarkFadeUrl75;
        menuMarkFadeUrl75 = null;
        // v15.41 (slice 1912): the fav-burst arm — consumed here,
        // at entry, with the URL field above, so a later menu
        // action can never inherit a stale burst (the queue
        // branches leave it null and never burst).
        final Boolean burstFav41 = menuMarkBurstFav41;
        menuMarkBurstFav41 = null;
        if (url == null || talkList == null) return;
        talkList.post(new Runnable() {
            @Override public void run() {
                if (talkList == null || isFinishing() || isDestroyed()) return;
                int ti = -1;
                if (talks != null) for (int i = 0; i < talks.size(); i++) {
                    if (talks.get(i) != null && SiteHttp.sameTalk(talks.get(i).url, url)) { ti = i; break; }
                }
                if (ti < 0) return;
                int disp = (visibleHits != null) ? visibleHits.indexOf(Integer.valueOf(ti)) : ti;
                if (disp < 0) return;
                int first = talkList.getFirstVisiblePosition();
                android.view.View row = talkList.getChildAt(disp - first);
                if (row == null) return;
                Fx.fadeIn(row, 250);
                if (burstFav41 != null) {
                    final android.view.View burstRow41 = row;
                    final boolean burstNowFav41 = burstFav41.booleanValue();
                    row.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (burstRow41 == null || !burstRow41.isShown() || isFinishing() || isDestroyed()) return;
                            showTalksFavBurst(burstNowFav41, burstRow41);
                        }
                    }, 160);
                }
            }
        });
    }

    // v15.41 (slice 1912): the Talks long-press favourite burst —
    // the Main-grid 1877 shape at the marked row's centre (48sp
    // gold U+2605 on add, muted U+2606 on remove; 180ms grow to
    // 1.15x, 420ms shrink + fade; gen-guarded, restart-safe).
    // Fired only from fadeMenuMarkedTalkRow when the fav branch
    // armed menuMarkBurstFav41; the queue branches share that
    // helper and never arm it, so they never burst.
    private void showTalksFavBurst(boolean nowFav, android.view.View row) {
        try {
            android.view.View content = findViewById(android.R.id.content);
            if (content == null || !(content instanceof android.view.ViewGroup) || row == null) return;
            if (talksFavBurstView == null) {
                android.widget.TextView tv = new android.widget.TextView(this);
                tv.setVisibility(View.GONE);
                tv.setClickable(false);
                tv.setFocusable(false);
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 48);
                tv.setShadowLayer(6f, 0f, 2f, 0x99000000);
                ((android.view.ViewGroup) content).addView(tv,
                        new android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
                talksFavBurstView = tv;
            }
            final android.widget.TextView tv = talksFavBurstView;
            tv.animate().cancel();
            talksFavBurstGen++;
            final int myGen = talksFavBurstGen;
            tv.setText(nowFav ? "\u2605" : "\u2606");
            tv.setTextColor(nowFav ? 0xFFC9A227 : 0xFFAAAAAA);
            tv.setVisibility(View.VISIBLE);
            tv.measure(android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
                       android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED));
            int w = tv.getMeasuredWidth();
            int h = tv.getMeasuredHeight();
            tv.setPivotX(w / 2f);
            tv.setPivotY(h / 2f);
            int[] rowLoc = new int[2];
            int[] contentLoc = new int[2];
            row.getLocationOnScreen(rowLoc);
            content.getLocationOnScreen(contentLoc);
            float cx = (rowLoc[0] - contentLoc[0]) + row.getWidth() / 2f;
            float cy = (rowLoc[1] - contentLoc[1]) + row.getHeight() / 2f;
            if (content.getWidth() > 0) cx = Math.max(w / 2f, Math.min(content.getWidth() - w / 2f, cx));
            if (content.getHeight() > 0) cy = Math.max(h / 2f, Math.min(content.getHeight() - h / 2f, cy));
            tv.setX(cx - w / 2f);
            tv.setY(cy - h / 2f);
            tv.setScaleX(0.5f);
            tv.setScaleY(0.5f);
            tv.setAlpha(1f);
            tv.animate().scaleX(1.15f).scaleY(1.15f).setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            if (myGen != talksFavBurstGen) return;
                            tv.animate().scaleX(1f).scaleY(1f).alpha(0f).setDuration(420)
                                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                    .withEndAction(new Runnable() {
                                        @Override public void run() {
                                            if (myGen != talksFavBurstGen) return;
                                            tv.setVisibility(View.GONE);
                                            tv.setAlpha(1f);
                                        }
                                    }).start();
                        }
                    }).start();
        } catch (Exception ignored) {}
    }
    // v14.97 (slice 1755): the changed-mark set for the sync melt —
    // pure (only SiteHttp.identityUrl + java.util), so the sim
    // transcribes it directly. Null prefs/cache sets read as empty;
    // null entries are skipped. Canonicalised both sides, so a
    // stored http row against an https cache entry is the SAME
    // talk and arms no melt — the v7.44 detector's raw .equals may
    // still fire its rebind on that spelling split, but with no
    // visible mark change there is nothing to melt.
    static java.util.Set<String> syncMarksChanged97(
            java.util.Set<String> beforeFav, java.util.Set<String> afterFav,
            java.util.Set<String> beforePlayed, java.util.Set<String> afterPlayed,
            java.util.Set<String> beforeQueued, java.util.Set<String> afterQueued) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        java.util.Set<String> bf = new java.util.HashSet<String>();
        if (beforeFav != null) for (String u : beforeFav) {
            String id = SiteHttp.identityUrl(u);
            if (id != null) bf.add(id);
        }
        java.util.Set<String> af = new java.util.HashSet<String>();
        if (afterFav != null) for (String u : afterFav) {
            String id = SiteHttp.identityUrl(u);
            if (id != null) af.add(id);
        }
        for (String id : bf) if (!af.contains(id)) out.add(id);
        for (String id : af) if (!bf.contains(id)) out.add(id);
        java.util.Set<String> bp = new java.util.HashSet<String>();
        if (beforePlayed != null) for (String u : beforePlayed) {
            String id = SiteHttp.identityUrl(u);
            if (id != null) bp.add(id);
        }
        java.util.Set<String> ap = new java.util.HashSet<String>();
        if (afterPlayed != null) for (String u : afterPlayed) {
            String id = SiteHttp.identityUrl(u);
            if (id != null) ap.add(id);
        }
        for (String id : bp) if (!ap.contains(id)) out.add(id);
        for (String id : ap) if (!bp.contains(id)) out.add(id);
        // v15.28 (slice 1802): the queued pair — a download queueing
        // (⏳ appearing) is the last mark arrival the diff missed:
        // the tick rebinds on a queued-set change (marksDlQueued44
        // in marksChanged44) but the diff was fav/played only, so
        // the row snapped its ⏳ in one frame while ★/✓ beside it
        // melted (v14.97). Membership only, never the live percents
        // — a 42% -> 43% tick diffs to nothing and arms no melt.
        java.util.Set<String> bq = new java.util.HashSet<String>();
        if (beforeQueued != null) for (String u : beforeQueued) {
            String id = SiteHttp.identityUrl(u);
            if (id != null) bq.add(id);
        }
        java.util.Set<String> aq = new java.util.HashSet<String>();
        if (afterQueued != null) for (String u : afterQueued) {
            String id = SiteHttp.identityUrl(u);
            if (id != null) aq.add(id);
        }
        for (String id : bq) if (!aq.contains(id)) out.add(id);
        for (String id : aq) if (!bq.contains(id)) out.add(id);
        return out;
    }
    // v14.97 (slice 1755): melt the rows whose ★/✓ just changed
    // under the open chooser — the fadeMenuMarkedTalkRow shape
    // verbatim, over a set: posted after the tick branch's rebind
    // so the position->child mapping is read fresh; resolved at
    // fade time through visibleHits (a search/filter narrows it,
    // a filtered-out row reads -1); only while the activity lives;
    // a scrolled-out row is skipped (getChildAt null) rather than
    // scrolled to; a ★-only filter unstar drops the row in the
    // branch's rebuildFilter before the posted resolve and reads
    // -1, so it fades nothing — the row just leaves, as it does
    // today (the v14.75 case). Fade 250ms via Fx.fadeIn. The
    // v14.47 getView hygiene restores row alpha 1 on any rebind,
    // so a fade cut short by a tick percent rebind, a return
    // theme-dip swap or a filter/search change can never strand
    // the row half-transparent; Fx.fadeIn is restart-safe (v3.52),
    // so a sounding/landing melt on the same row in the same tick
    // just restarts the glide and still ends at alpha 1. The
    // sounding row melts whole-row like the v14.75 case: its gold
    // lives on the card background (v13.83) and its title pulse on
    // the title view, so neither fights the row fade; a ✓ gain
    // also dims the title to 0.6 on the title view, a separate
    // channel. Consume-once: the field is nulled here. Visible
    // rows only bound the melt — a mass change melts at most the
    // rows on screen.
    private void fadeSyncMarkedTalkRows97() {
        if (syncMarksFadeUrls97 == null || syncMarksFadeUrls97.isEmpty()
                || talkList == null) {
            syncMarksFadeUrls97 = null;
            return;
        }
        final java.util.Set<String> urls =
                new java.util.HashSet<String>(syncMarksFadeUrls97);
        syncMarksFadeUrls97 = null;
        talkList.post(new Runnable() {
            @Override public void run() {
                if (talkList == null || isFinishing() || isDestroyed()) return;
                int first = talkList.getFirstVisiblePosition();
                for (String url : urls) {
                    int ti = -1;
                    if (talks != null) for (int i = 0; i < talks.size(); i++) {
                        if (talks.get(i) != null && SiteHttp.sameTalk(talks.get(i).url, url)) { ti = i; break; }
                    }
                    if (ti < 0) continue;
                    int disp = (visibleHits != null) ? visibleHits.indexOf(Integer.valueOf(ti)) : ti;
                    if (disp < 0) continue;
                    android.view.View row = talkList.getChildAt(disp - first);
                    if (row != null) Fx.fadeIn(row, 250);
                }
            }
        });
    }
    // v14.56 (slice 1713): the header's live-percent strip — the
    // header carries the Up Next talk's LIVE download percent
    // ("⏳42%"), and the branch's own progress diff can move that
    // number on consecutive ticks while a download runs, so a raw
    // text compare would re-fade the header on every percent tick
    // (the drawer-header-line flicker class, slice 1712 dead end).
    // The arm compares with the live percent removed, so only a
    // structural change arms: a new Up Next title, a count change
    // (★/⬇/✓/↺/◈/⏳-count), a ⬇ landing. A pure percent tick
    // (42% -> 43%) reads equal — a missed melt, never a shimmer.
    // The "· ⏳ M" count has no percent and survives the strip, so
    // a download starting or finishing still arms through the count.
    private static String stripHeaderLivePct(String s) {
        if (s == null) return "";
        return s.replaceAll("⏳[0-9]+%", "");
    }
    // v4.89: last playing URL the rows were bound against — the
    // tick detects a change here and rebinds once (below).
    private String talkRowPlayingUrl = null;
    // v11.06: same change detection for the library-session
    // sounding URL, so a library advance while browsing rebinds
    // the rows and moves the ↺ suppression with it.
    private String talkRowPlayingLibUrl = null;
    // v14.48 (slice 1705): the newly-sounding talk's URL the melt
    // below fades in — the Room drawer's v3.24 twin, restated for
    // the chooser. talkSoundPrimed stays false until the first tick
    // evaluation adopts the trackers, so a load or rotation restore
    // never arms a fade on top of the list pour (v14.23); a sounding
    // change discovered on a back-stack return (primed, same
    // instance) melts the row as the return beat, like the v14.47
    // download landing. Consumed by fadeSoundingTalkRow, which
    // nulls it — a second tick without a new sounding change is a
    // no-op — and the download-landing arm (dlLandFadeUrl) is a
    // separate field, so the two melts never overwrite each other.
    private String soundingFadeUrl = null;
    private boolean talkSoundPrimed = false;
    // v14.60 (slice 1717): the just-stopped talk's URL the melt
    // below fades in — the v14.48 sounding-row melt's twin for the
    // DEPARTING row. talkSoundPrimed (same first-tick adoption as
    // the sounding arm) stays false until the first tick evaluation
    // adopts the trackers, so a load or rotation restore never arms
    // a fade on top of the list pour (v14.23); a sounding change
    // discovered on a back-stack return melts the row as the return
    // beat, like the sounding arm. Consumed by fadePrevSoundTalkRow,
    // which nulls it — a second tick without a new departing change
    // is a no-op — and the sounding (soundingFadeUrl), Up Next
    // (upNextFadeUrl) and download-landing (dlLandFadeUrl) arms are
    // separate fields, so the melts never overwrite each other. The
    // departing row is never the new sounding or Up Next row in the
    // same tick (repeat-one re-sounds the same URL, so no change
    // arms at all), so the two fades never land on the same view.
    private String prevSoundFadeUrl = null;
    // v14.55 (slice 1712): the Up Next talk's URL the melt below fades
    // in — the chooser's ▸ row (v13.85), the v14.48 sounding-row melt's
    // twin, restated for the imminent talk. The ▸ used to march to its
    // new row in one frame on the tick's bare rebind — auto-advance /
    // strip Next / notification Next while browsing, or a queue change
    // from another screen — while the sounding row beside it already
    // melts (v14.48) and the header line above already names it, so
    // the row and the line moved a beat apart. upNextPrimed stays
    // false until the first tick evaluation, so a load or rotation
    // restore never arms a fade on top of the list pour (v14.23); a
    // change discovered on a back-stack return melts the row as the
    // return beat, like the sounding arm. Consumed by
    // fadeUpNextTalkRow, which nulls it — a second tick without a new
    // Up Next change is a no-op — and the sounding (soundingFadeUrl)
    // and download-landing (dlLandFadeUrl) arms are separate fields,
    // so the three melts never overwrite each other. No separate
    // tracker field: the arm compares the fresh value against the
    // snapshot taken in the branch just before refreshFavHeader
    // rewrites it — the field always holds the last value a rebind
    // could have bound (menu actions rebind with it at once), so a
    // menu-driven ▸ move can never arm a stale melt on a later tick.
    // The two refresh-without-rebind paths (list landing and mode-0
    // return) only ever leave the field AHEAD of the rows, so a later
    // branch run corrects the rows with no melt — a missed melt,
    // never a shimmer. A removal (fresh value empty — foreign head
    // waits, nothing resolves) arms nothing, like the sounding arm's
    // stopped case.
    private String upNextFadeUrl = null;
    private boolean upNextPrimed = false;
    // v14.75 (slice 1733): the row-menu mark flip's melt URL — the
    // row-menu favourite toggle and the queue add/remove verbs used
    // to end in a bare notifyDataSetChanged, so the mark snapped
    // onto the row in one frame while every other chooser arrival
    // already has a beat (list pour v14.23, landing rows v14.47,
    // sounding row v14.48, Up Next row v14.55, stopped row v14.60).
    // menuMarkFadeUrl75 holds the marked talk's URL until
    // fadeMenuMarkedTalkRow consumes it; a second menu action
    // without a new mark is a no-op. Not the header: the v14.56
    // boundary stands — menu actions never fade the header, only
    // the row. Not queue-from-here (many rows; the Up Next melt
    // owns the head change), not play-next (v14.55 owns the Up
    // Next row). The download verb JOINS the melt since v15.28
    // (slice 1802): the old exclusion ("the tick owns the mark
    // and v14.47 covers the landing") was stale — the tick
    // rebinds on a queued-set change but never melted the ⏳-gain,
    // and v14.47 covers only the landing (⬇), never the queueing.
    private String menuMarkFadeUrl75 = null;
    // v15.41 (slice 1912): the long-press favourite burst — the
    // Main grid fav tap bursts (1877) and the Room altar
    // long-press bursts (1844), but this screen's dialog fav
    // toggle only toasted + faded the row. menuMarkBurstFav41
    // carries the toggle's new state (null = no burst — the
    // queue branches sharing the helper below must never
    // burst); talksFavBurstView / talksFavBurstGen are the
    // burst view + generation guard, the Main 1877 shape.
    private Boolean menuMarkBurstFav41 = null;
    private android.widget.TextView talksFavBurstView = null;
    private int talksFavBurstGen = 0;
    // v14.97 (slice 1755): the tick-detected external ★/✓ change's
    // melt set — the Room drawer's v14.96 sync-melt field, restated
    // for the chooser. syncTalksStrip diffs the fav/played caches
    // (syncMarksChanged97) and banks the changed URLs here; the
    // branch's fadeSyncMarkedTalkRows97 consumes them. The menu path
    // never writes it (menu branches reload the caches synchronously,
    // so the next tick snapshots the new state and sees no change —
    // v10.88 / v14.75). v15.28 (slice 1802): the queued pair joins
    // the same diff — a ⏳ gained or lost under the open chooser
    // melts through the same helper; the live percents are never
    // an input, so a percent tick arms nothing. The return path
    // writes it too:
    // v14.98 (slice 1756) — onResume diffs its pre-return snapshots
    // against the refreshed caches and banks the changed URLs here;
    // the tick arm still reads only what its own tick found, because
    // onResume refreshes the caches before any tick runs. The melt
    // arms only in the tick's sync path and the onResume return path,
    // never in getView (scroll-recycle would arm it on every scroll).
    // syncMarksPrimed97 is the first-tick adoption, set at the tail
    // of syncTalksStrip beside the sibling primes, so a load /
    // rotation restore never melts on top of the list pour (v14.23).
    private java.util.Set<String> syncMarksFadeUrls97 = null;
    private boolean syncMarksPrimed97 = false;
    // v14.56 (slice 1713) / v15.31 (slice 1824): the chooser
    // header's melt prime — the header (R.id.title: sayadaw name +
    // counts + Up Next line) is one TextView, written at the end of
    // refreshFavHeader. The melt arm used to live in the 1s tick's
    // change branch and only fired on tick-detected changes, so a
    // Play All / Shuffle tap, a menu action, a download landing or
    // a return swapped the header in one frame while the ▸ row
    // (v14.55) and the sounding row (v14.48) melt beside it; since
    // slice 1824 the arm lives inside refreshFavHeader itself and
    // fires on every refresh. headerFadePrimed stays false until the
    // first successful paint, so a load or rotation restore never
    // arms a fade on top of the list pour (v14.23); the tick branch
    // no longer primes or arms it.
    private boolean headerFadePrimed = false;
    // v11.15: ended state the rows were last bound against —
    // the v4.89 tick detects playing-URL changes only, so a
    // natural end (same URL, liveEnded flipped) would otherwise
    // leave the ▶ on the ended row until the next rebind.
    private boolean talkRowEnded = false;
    // v14.05 (slice 1100): failed state the rows were last bound
    // against — the v11.15 twin for the failure class: a stream
    // failing (same URL, liveTrackError flipped) would otherwise
    // leave the ▶ on the failed row until the next rebind, while
    // the strip already said ⚠.
    private boolean talkRowFailed = false;
    private boolean talkRowBuffering = false;
    // v15.29 (slice 1813): playing state the rows were last bound
    // against — the v11.15 twin for the pause class: the tick
    // rebinds on URL/mark/ended/failed/buffering flips only, so a
    // pure pause/play flip (same URL) would otherwise leave the
    // sounding row's EQ bars (below) dancing after a pause until
    // the next rebind, while the strip already showed ▶.
    private boolean talkRowPlaying = false;
    // v13.85 (slice 1077): the Up Next talk's URL the rows were last bound against — the chooser's Up Next ▸ mark (Room v4.13, in the chooser rows). Written only by refreshFavHeader, alongside the header line it mirrors (queued head / forward top / shuffle-gated list-next; empty when a foreign head waits or nothing resolves), and read only by TalkAdapter.getView; the tick's change branch refreshes the header BEFORE the rebind below, so a row never binds against a stale next talk. Cleared at the top of every header refresh, so an early return can never leave a stale crown.
    private String upNextRowUrl = "";
    // v13.89 (slice 1081): chooser header photo ring — the last photo
    // surface naming a sounding sayadaw without the gold language
    // (Room header got its ring in v13.88, slice 1080; the shelf chip
    // in v13.87; the grid card in v13.86). talksHeaderPhotoRing is the
    // 60dp frame around the 56dp portrait; its background is the shelf
    // ring drawable while a talk in THIS list sounds, nothing otherwise.
    // lastChooserHeaderRing is the change-only state, like the Room's
    // lastHeaderRing — refreshFavHeader runs on every load, return,
    // queue action and change-only tick, so a steady tick never re-sets
    // the background. The ring follows the rows' own sounding rule
    // (rowPlaying / rowPlayingLib, v4.89/v11.07): a Room session for
    // this sayadaw (talkPlayingUrl, already lastName-gated in
    // refreshQueueMarks) or a library session sounding an in-list file
    // (talkPlayingLibUrl, dlMap exactly-one claimant) — paused still
    // rings (v3.99), ended never rings, a foreign session never rings
    // (its URL matches no talk in this list), a dual-claimed library
    // file resolves to no URL and never rings. Background only — the
    // entrance animates the whole header row (v2.16/v6.04), the title's
    // tap-to-top/long-press and the photo's ImageLoader bind are
    // untouched.
    private android.view.View talksHeaderPhotoRing = null;
    // v15.34 (slice 1845): sounding header ring breath state —
    // the fields the helpers below own. chooserRingBreath is
    // the running loop (null when still); chooserRingBreathBg
    // is the background drawable it writes (the photo is a
    // child of the ring frame, so the breath animates the
    // drawable's alpha, never the view's — the portrait stays
    // solid); chooserRingBreathPending marks a delayed start
    // still waiting out the 250ms crossfade;
    // chooserRingBreathGen vetoes a stale delayed start after
    // a state flip or an onPause cancel.
    private android.animation.ValueAnimator chooserRingBreath = null;
    private android.graphics.drawable.Drawable chooserRingBreathBg = null;
    private boolean chooserRingBreathPending = false;
    private int chooserRingBreathGen = 0;
    // v15.30 (slice 1816): shared-element open from the Main grid
    // — the grid photo grows into this chooser's header portrait.
    // True only when the intent carries sharedPhoto (set by
    // Fx.goSharedNamed on the grid tap); plain opens keep the
    // stock nav fade and the header entrance. Read in onCreate;
    // the window transitions and the entrance skip below both
    // follow it.
    private boolean sharedTalksPhotoOpen = false;
    private boolean lastChooserHeaderRing = false;
    // v14.07 (slice 1102): failed state the chooser header ring was
    // last bound against — the ring is three-state now (gold / red /
    // none), so the change-only memory needs the failure bit beside
    // the ringing bit, like the Room header ring (v14.05).
    private boolean lastChooserHeaderRingFailed = false;
    // v7.39: line-aware -- a saved line is parsed and matched by its
    // URL, so a v2-spelled line (handover-carried, owned by another
    // sayadaw) for a talk that IS in this list still earns its
    // mark, like the Room's v7.38 queueLineIndexForUrl fix. talkQueue
    // stays bare URLs, so every downstream contains/indexOf site
    // (row marks, move, header) is unchanged.
    // v13.71 (slice 1057): talkQueue holds parsed saved-line URLs — a
    // legacy http line for a talk the fresh list spells https — so raw
    // contains/remove/indexOf split one talk into two identities: the
    // remove/move/add gates toast "queue changed", the add verb plants
    // a twin, and the move-drop indexOf(-1) would crash. Every
    // talkQueue membership site goes through these sameTalk-aware
    // helpers; listHasTalk parses v2 lines too (funnel inList).
    private boolean talkQueueHas(String url) {
        if (url == null) return false;
        for (String q : talkQueue) if (SiteHttp.sameTalk(q, url)) return true;
        return false;
    }
    private int talkQueueIndexOf(String url) {
        if (url == null) return -1;
        for (int i = 0; i < talkQueue.size(); i++)
            if (SiteHttp.sameTalk(talkQueue.get(i), url)) return i;
        return -1;
    }
    private boolean talkQueueRemove(String url) {
        int i = talkQueueIndexOf(url);
        if (i < 0) return false;
        talkQueue.remove(i);
        return true;
    }
    private static boolean listHasTalk(java.util.ArrayList<String> lines, String url) {
        if (url == null) return false;
        for (String line : lines) {
            QueueEntry e = QueueEntry.parse(line);
            if (e != null && SiteHttp.sameTalk(e.url, url)) return true;
        }
        return false;
    }
    private void refreshQueueMarks() {
        java.util.ArrayList<String> q = new java.util.ArrayList<String>();
        String saved = Prefs.savedQueue(TalksActivity.this, sayadawName);
        if (saved != null && saved.length() > 0) {
            for (String line : saved.split("\n")) {
                if (line.length() == 0) continue;
                QueueEntry e = QueueEntry.parse(line);
                String u = e == null ? "" : e.url;
                if (u.length() == 0 || listHasTalk(q, u)) continue;
                for (int i = 0; i < talks.size(); i++)
                    if (SiteHttp.sameTalk(talks.get(i).url, u)) { q.add(u); break; }
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
            if (e != null && SiteHttp.sameTalk(e.url, url)) return true;
        }
        return false;
    }
    private static int indexOfUrl(java.util.ArrayList<String> out, String url) {
        if (url == null) return -1;
        for (int i = 0; i < out.size(); i++) {
            QueueEntry e = QueueEntry.parse(out.get(i));
            if (e != null && SiteHttp.sameTalk(e.url, url)) return i;
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
                            && outContainsUrl(out, e.url))
                        out.set(indexOfUrl(out, e.url), line);
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
                    if (u04 != null && u04.length() > 0 && !listHasTalk(inList, u04)) removed04.add(SiteHttp.identityUrl(u04));
                java.util.HashMap<String, Integer> afterCount = new java.util.HashMap<String, Integer>();
                for (int si = 0; si < savedLines.size(); si++) {
                    QueueEntry e = QueueEntry.parse(savedLines.get(si));
                    if (e == null || e.url.length() == 0) continue;
                    if (outContainsUrl(out, e.url)) continue;
                    boolean inLoadedList = false;
                    if (talks != null)
                        for (int ti = 0; ti < talks.size(); ti++)
                            if (talks.get(ti).url != null && SiteHttp.sameTalk(talks.get(ti).url, e.url)) { inLoadedList = true; break; }
                    if (inLoadedList) {
                        if (removed04.contains(SiteHttp.identityUrl(e.url))) continue;
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
                if (SiteHttp.sameTalk(e.url, talkPlayingUrl)) continue;
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
        refreshContinueRow(); // v15.43 (slice 1932): the Continue row follows the row marks (load / return / 1s tick), with the played + download caches fresh
        refreshPlayNewRow(); // v15.44 (slice 1933): the Play-new row follows the same funnel (load / return / 1s tick), with the played + download caches fresh
    }
    // v4.71: offline check for the chooser header's Up Next mark —
    // the Room's isDownloadedFile (v1.90 map + v1.82 candidates +
    // claim guard), restated here because TalksActivity never
    // owned a downloaded set: the mapped name from the "dlMap"
    // prefs wins, else any candidate basename in Downloads/Dhamma
    // counts unless another URL's download claims that name.
    // File-exists checks only, once per header refresh.
    // v14.88 (slice 1746): the chooser's downloaded ⬇ now actually
    // appears for a scheme-mismatched download — the downloaded twin
    // of the Room v14.87 fix (same provenance, same three-form rule):
    // dlMap keys are written under the URL form current at download
    // time, and the same talk is spelled both ways in the wild: a file
    // downloaded by the delivered v13.57 build over the http fallback
    // keeps its legacy http key forever (the v13.66 migration
    // does NOT migrate dlMap keys), while newer downloads record the
    // canonical https key (v13.59), and one catalog page carries
    // absolute-http mp3 hrefs the parser keeps as-is (Dr Nandamalabhivamsa,
    // 15 hrefs, 1737) — so a talk downloaded under one scheme and listed under
    // the other missed its mapped name here AND had its basename blocked
    // by the twin's own claim below: the chooser row, the header Up Next
    // mark and the downloaded-only filter all lost their ⬇, the prune's
    // downloaded leg kept the queue hourglass, and a chooser re-download
    // minted a hash-suffixed duplicate. The mapped lookup now tries the
    // raw URL, then its canonical https form, then its downgraded http
    // form (raw first, so a talk downloaded twice under both schemes
    // keeps the file its current spelling recorded); the claim guard
    // below treats the scheme-twin as the same talk, not another talk,
    // so a re-download reuses the existing file instead of minting a
    // hash-suffixed duplicate (talkDownloadFileNameFor reads the guard
    // directly). The v8.40/v8.50 rules are unchanged: a name claimed by
    // a DIFFERENT talk's key still forces the hash suffix and still
    // blocks the basename fallback. The shared DlLanded prune leg keeps
    // its raw lookups for now — its slice follows.
    private String talkMappedFileName(String url) {
        try {
            org.json.JSONObject map = new org.json.JSONObject(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlMap", "{}"));
            if (map.has(url)) return map.getString(url);
            String canon = SiteHttp.identityUrl(url);
            if (canon != null && !canon.equals(url) && map.has(canon)) return map.getString(canon);
            String down = SiteHttp.downgrade(url);
            if (down != null && !down.equals(url) && map.has(down)) return map.getString(down);
        } catch (Exception ignored) {}
        return null;
    }
    private boolean talkFileClaimedByOther(String filename, String url) {
        try {
            org.json.JSONObject map = new org.json.JSONObject(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlMap", "{}"));
            String self = SiteHttp.identityUrl(url);
            java.util.Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!SiteHttp.identityUrl(k).equals(self) && filename.equals(map.optString(k, ""))) return true;
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
    // v13.70 (audit-1369 F10): all three keys now land in ONE apply
    // below, so the guarantee above holds for dlSayadaw too - the C4
    // pass (v13.65) had joined dlMap + dlQueued only, leaving
    // dlSayadaw in its own apply.
    private void recordTalkDownload(String url, String file) {
        // v13.68 (audit-1369 F11): dlMap/dlSayadaw/dlQueued read-modify-write - serialize on DlLock.LOCK (see DlLock).
        synchronized (DlLock.LOCK) {
        try {
            // v13.71 (slice 1057): bank the canonical (https) form —
            // the Fx.stripEnqueueDownload shape (v13.67); a
            // sounding-URL caller must never plant an http
            // dlMap/dlSayadaw/dlQueued row.
            url = SiteHttp.identityUrl(url);
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
            map.put(url, file);
            String sayStr70 = null;
            if (sayadawName != null && sayadawName.length() > 0) {
                try {
                    org.json.JSONObject sm = new org.json.JSONObject(sp.getString("dlSayadaw", "{}"));
                    sm.put(url, sayadawName);
                    sayStr70 = sm.toString();
                } catch (Exception ignored) {}
            }
            org.json.JSONArray queued = new org.json.JSONArray(sp.getString("dlQueued", "[]"));
            boolean found = false;
            for (int i = 0; i < queued.length(); i++) {
                if (SiteHttp.sameTalk(url, queued.optString(i))) { found = true; break; }
            }
            if (!found) queued.put(url);
            // v13.65 (audit-1370 C4): dlMap + dlQueued land in ONE apply() —
            // the Fx.stripEnqueueDownload shape. Three separate applies used
            // to tear: a death between them left the hourglass mark without
            // its filename (or the filename without its mark), and the two
            // disagreed until a prune.
            // (F10, see the header above): dlSayadaw joins that same apply
            // now - the sayadaw string is built first, in its own try
            // exactly as before, so a corrupt dlSayadaw JSON still cannot
            // block the record.
            android.content.SharedPreferences.Editor ed70 = sp.edit()
                    .putString("dlMap", map.toString())
                    .putString("dlQueued", queued.toString());
            if (sayStr70 != null) ed70.putString("dlSayadaw", sayStr70);
            ed70.apply();
        } catch (Exception ignored) {}
        }
    }

    private boolean isTalkDownloaded(String url) {
        try {
            java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma");
            String mapped = talkMappedFileName(url);
            // v13.66 (audit-1369 F7 length-gate pass): a 0-byte file is not
            // downloaded — the chooser used to show the download mark for a dead
            // file, and the Talks prune's downloaded leg then dropped its queue
            // mark, so the talk read as "downloaded" while Room and the service
            // both refused it offline. The in-progress exclusion stays first.
            if (mapped != null && new java.io.File(dir, mapped).exists()
                    && new java.io.File(dir, mapped).length() > 0
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
            // v13.66: same length gate as the mapped branch above (audit-1369 F7).
            for (String name : cands)
                if (!talkFileClaimedByOther(name, url) && !talkInProgressDlNames.contains(name)
                        && new java.io.File(dir, name).exists()
                        && new java.io.File(dir, name).length() > 0) {
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
    // v13.95 (slice 1090): visible chooser filter chips — the four
    // TextViews of talkFilterRow, painted by refreshTalkFilterChips.
    // Null until onCreate wires them (applyTheme and the first
    // loadList both run before that point, so every painter
    // null-guards).
    private android.widget.TextView chipTalkAll = null;
    private android.widget.TextView chipTalkStar = null;
    private android.widget.TextView chipTalkNew = null;
    private android.widget.TextView chipTalkDl = null;

    // v13.76 (slice 1068): chooser Play All button — null until
    // onCreate wires it (applyTheme and the first load both run
    // before that point, so every painter null-guards).
    private android.widget.Button btnTalkPlayAll = null;
    // v13.93 (slice 1088): Shuffle button beside it + the row
    // that owns the shared visibility gate + its own 700ms
    // debounce clock (Play All's lastPlayAllMs stays untouched).
    private android.widget.Button btnTalkShuffle = null;
    // v15.43 (slice 1931): sort button beside Play All /
    // Shuffle — reverses the shown list between the site
    // order and oldest-first. Shares the Play row's
    // GONE-until-a-list-lands gate.
    private android.widget.Button btnTalkSort = null;
    // v15.45 (slice 1939): Talks header star — the
    // sayadaw-favourite toggle for this screen. The set
    // is the shared "favs" string set Main owns; the
    // button is the 48dp star at the header row's end;
    // talkHeaderFav is the last-painted state for the
    // painter's change gate.
    private android.widget.TextView talkFavBtn = null;
    private java.util.Set<String> talkFavSet = new java.util.HashSet<String>();
    private boolean talkHeaderFav = false;
    // v15.45 (slice 1941): Talks header info button — the
    // info glyph at the header row's end beside the star,
    // opening the sayadaw info sheet. talkInfoShown is
    // the last-painted visibility for the painter's change
    // gate; talkInfoPhotoUrl is the header's resolved
    // photo URL, reused by the sheet; sayadawGroup is the
    // intent's group extra, empty when the opener did not
    // send one.
    private android.widget.TextView talkInfoBtn = null;
    private boolean talkInfoShown = false;
    private String talkInfoPhotoUrl = "";
    private String sayadawGroup = "";
    // v15.53 (slice 1980): Talks header share button — the
    // visible sayadaw-share verb for this screen (the Room
    // header long-press, v4.16, shares the same text, but a
    // hidden gesture is not a visible verb). The button is
    // the 48dp ↗ at the header row's end beside the info
    // glyph; talkShareShown is the last-painted visibility
    // for the painter's change gate.
    private android.widget.TextView talkShareBtn = null;
    private boolean talkShareShown = false;
    // v15.45 (slice 1940): Talks back-to-top button — the
    // floating gold ↑ over the list's bottom-end corner;
    // talkTopShown is the last-painted state for the
    // painter's change gate.
    private android.view.View talkTopBtn = null;
    private boolean talkTopShown = false;
    // v15.43 (slice 1932): "Continue listening" row — the
    // one-tap return to this sayadaw's in-progress talk.
    // Shares no gate with the Play row: it hides while a
    // search or filter narrows the list, and it appears
    // only when such a talk exists.
    private android.view.View talkContinueRow = null;
    private android.widget.TextView talkContinueTitle = null;
    private String continuePickKey = "";
    private int continuePickIdx = -1;
    // v15.44 (slice 1933): "Play new" row — one tap queues +
    // plays only this sayadaw's new (●-dotted) talks. Shares
    // no gate with the Play row: it hides while a search or
    // filter narrows the list, and it appears only when such
    // talks exist. Its own 700ms debounce clock (Play All's
    // lastPlayAllMs stays untouched); the count word is parsed
    // once from queue_added_suffix (see
    // playNewCountWordFromSuffix).
    private android.view.View talkPlayNewRow = null;
    private android.widget.TextView talkPlayNewTitle = null;
    private String playNewPickKey = "";
    private String playNewCountWord = null;
    private long lastPlayNewMs = 0;
    private android.view.View talkPlayRow = null;
    // v14.45 (slice 1700): skip-first-fade flag — a rotation
    // restore must snap the Play All row back (the v1.53 rule,
    // same shape as Main's shelfSkipFirstPour): the restore
    // branch sets this before its refreshTalkPlayAllRow() call
    // and the function below consumes it. A fresh open / retry
    // never sets it, so the list landing fades.
    private boolean talkPlayRowSkipFirstFade = false;
    private long lastShuffleMs = 0;
    private void rebuildVisible() {
        if (searchHits != null && filterHits != null) {
            java.util.ArrayList<Integer> both = new java.util.ArrayList<Integer>();
            for (Integer i : searchHits) if (filterHits.contains(i)) both.add(i);
            visibleHits = both;
        } else if (searchHits != null) visibleHits = searchHits;
        else if (filterHits != null) visibleHits = filterHits;
        else visibleHits = null;
        // v15.43 (slice 1931): list sort — the display order
        // is the site order, or reversed (oldest first)
        // while the sort is on. Applied here, the single
        // funnel every search/filter/load path already runs
        // through, so taps, long-press, queue-from-here,
        // Play All and the glides all follow the shown
        // order with no other change. A null set (no
        // narrowing) becomes the full reversed index list
        // while sorted, so getCount/talkIndexAtDisplay keep
        // reading visibleHits; the 1s tick's reverse
        // lookups and the empty-state gate are
        // order-blind, so a full reversed list is safe.
        if (Prefs.talksSortOldest(TalksActivity.this)) {
            visibleHits = visibleHits == null
                    ? TalkSort.fullOrder(talks == null ? 0 : talks.size(), true)
                    : TalkSort.apply(visibleHits, true);
        }
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
            // v14.44 (slice 1699): the empty line fades in/out instead
            // of snapping — the chooser's last un-choreographed
            // visibility edge (the library's empty line got its fade
            // in v3.58, the shelf its fade-out in v14.38). Edge-only:
            // a steady show across keystrokes/filter taps never
            // re-animates, and a re-appear landing mid-fade-out
            // glides back from its current alpha (Fx.fadeIn flows
            // from it, v3.52) while the gen guard strands nothing
            // hidden. The text swap stays instant (the v3.58
            // footer-total rule) — only the visibility melts.
            if (show) {
                if (empty.getVisibility() != android.view.View.VISIBLE) {
                    empty.setVisibility(android.view.View.VISIBLE);
                    Fx.fadeIn(empty, 250);
                } else if (empty.getAlpha() < 0.999f) {
                    Fx.fadeIn(empty, 250);
                }
            } else if (empty.getVisibility() == android.view.View.VISIBLE) {
                Fx.fadeOut(empty, 200);
            }
        }
        // v15.45 (slice 1940): a rebuild can land the list deep
        // without a scroll event (load / search / filter / sort)
        // — repaint the back-to-top button from the new position.
        paintTalkTopBtnFromList();
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
                    if (ft != null && ft.contains(SiteHttp.identityUrl(u))) hits.add(i);
                } else if (filterMode == 3) {
                    // v5.13: downloaded only — reuses the chooser
                    // header's isTalkDownloaded (dlMap + candidates
                    // + claim guard), so a download landed from the
                    // Room is picked up by the on-return rebuild.
                    if (isTalkDownloaded(u)) hits.add(i);
                } else {
                    if (pl == null || !pl.contains(SiteHttp.identityUrl(u))) hits.add(i);
                }
            }
            filterHits = hits;
        }
        rebuildVisible();
    }
    // v13.95 (slice 1090): paint the visible chooser filter chips —
    // the four filter views (v4.94 + v5.13) lived behind one cycling
    // button in the search row: undiscoverable, and the active mode
    // showed only as that button's label. Four direct chips now
    // (All / ★ / New / Downloaded), same modes, same Lang keys the
    // button already used (repeat_all / filter_btn_star /
    // filter_btn_new / filter_btn_dl) — no new key, no Burmese
    // literal in code (the layout initial texts are the same
    // shipped values, asserted by the patch). Active chip reads
    // gold + bold, the others muted — the library chips' own
    // language (DownloadedActivity refreshDlFilterChips); size
    // follows the font setting on the chooser's old filter step
    // (12/14/16). Called from applyTheme (create + return), from
    // the chip wiring below, from clearFilter and from the rotation
    // restore, so every mode change — chip tap, clear, restore —
    // repaints the chips with no per-site call. Null-safe: applyTheme
    // runs before onCreate wires the chips, and the first loadList
    // runs before them too. No mode guard: a language change must
    // repaint even when the mode is unchanged, like the old button.
    // v14.39 (slice 1694): two rhythms, one painter — a chip tap
    // melts the colours (refreshTalkFilterChipsAnimated, 250ms
    // Fx.crossfadeTextColor, the v14.34 sleep-text rhythm) and the
    // weight beside them (v15.38, slice 1894: 90ms dip, swap, 180ms
    // rise — the weight IS the selection state, so its flip melts
    // with the colour instead of snapping under the chip's Fx.press
    // squash), while create / theme / language / rotation /
    // clearFilter still land instantly through Fx.setTextColorNow,
    // which cancels any in-flight melt and records the target so
    // the helper's map can never disagree with the view.
    private void refreshTalkFilterChips() {
        paintTalkFilterChips(false);
    }

    // v14.39 (slice 1694): chip-tap entry — same painter, colours + weight melt.
    private void refreshTalkFilterChipsAnimated() {
        paintTalkFilterChips(true);
    }

    private void paintTalkFilterChips(boolean animate) {
        if (chipTalkAll == null || chipTalkStar == null || chipTalkNew == null || chipTalkDl == null) return;
        int f = Prefs.fontSize(TalksActivity.this);
        float sz = f == 0 ? 12 : (f == 1 ? 14 : 16);
        int gold = android.graphics.Color.parseColor("#C9A227");
        // v11.53: the inactive filter label kept day muted brown
        // (#7A6A58) at night too, while every other muted text
        // on the themed screens switches to #AAAAAA at night —
        // same night value, day value exactly the layout colour.
        int muted = Prefs.isNight(TalksActivity.this)
                ? android.graphics.Color.parseColor("#AAAAAA")
                : android.graphics.Color.parseColor("#7A6A58");
        chipTalkAll.setText(Lang.t(TalksActivity.this, "repeat_all"));
        chipTalkStar.setText(Lang.t(TalksActivity.this, "filter_btn_star"));
        chipTalkNew.setText(Lang.t(TalksActivity.this, "filter_btn_new"));
        chipTalkDl.setText(Lang.t(TalksActivity.this, "filter_btn_dl"));
        chipTalkAll.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sz);
        chipTalkStar.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sz);
        chipTalkNew.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sz);
        chipTalkDl.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sz);
        paintChipColor(chipTalkAll, filterMode == 0 ? gold : muted, animate);
        paintChipColor(chipTalkStar, filterMode == 1 ? gold : muted, animate);
        paintChipColor(chipTalkNew, filterMode == 2 ? gold : muted, animate);
        paintChipColor(chipTalkDl, filterMode == 3 ? gold : muted, animate);
        paintChipWeight(chipTalkAll, filterMode == 0, animate);
        paintChipWeight(chipTalkStar, filterMode == 1, animate);
        paintChipWeight(chipTalkNew, filterMode == 2, animate);
        paintChipWeight(chipTalkDl, filterMode == 3, animate);
    }

    // v14.39 (slice 1694): one writer for a chip's colour, in both
    // rhythms — melt on tap, instant elsewhere (see Fx.setTextColorNow
    // for why the instant path must go through the helper too).
    private void paintChipColor(android.widget.TextView chip, int color, boolean animate) {
        if (animate) Fx.crossfadeTextColor(chip, color);
        else Fx.setTextColorNow(chip, color);
    }

    // v15.38 (slice 1894): one writer for a chip's weight, in both
    // rhythms — melt on tap, instant elsewhere (see
    // Fx.setChipWeightNow for why the instant path must go through
    // the helper too). Mirrors paintChipColor (v14.39).
    private void paintChipWeight(android.widget.TextView chip, boolean bold, boolean animate) {
        if (animate) Fx.meltChipWeight(chip, bold);
        else Fx.setChipWeightNow(chip, bold);
    }

    // v14.23 (slice 1677): chooser list-change pour — the first
    // grid load pours (v2.07) and Main's search pours on a
    // keystroke that changes the set (v2.48), but this chooser's
    // filter-chip tap and search keystroke both pinned lastAnimPos
    // to MAX (v13.95), so the new rows snapped in instantly while
    // every other list change in the app pours. visibleKey is the
    // order-sensitive key of the visible set — size + every display
    // talk index, the Main v11.69 full key, so a tail-only swap
    // ({A,B,C,D,E} -> {A,B,C,D,F}) never reads as unchanged; the
    // helper resets the guard only when the key actually moved,
    // so a chip tap on an unchanged set (every talk already
    // starred) or a keystroke that leaves the set alone never
    // replays, and fast typing does not flicker. A rotation
    // restore never pours: restoringTalkSearch is up while the
    // saved query goes back into the box, and the guard stays at
    // its restored pin (v1.53) instead. The pour itself is the
    // existing getView cascade (300ms fade + 24dp rise, 45ms
    // stagger) — no new animation, no new view.
    private String visibleKey() {
        StringBuilder kb = new StringBuilder();
        int n = visibleHits == null ? talks.size() : visibleHits.size();
        kb.append(n);
        for (int i = 0; i < n; i++) kb.append('|').append(talkIndexAtDisplay(i));
        return kb.toString();
    }
    private void maybePourOnListChange() {
        if (restoringTalkSearch) return;
        String key = visibleKey();
        if (!key.equals(lastVisibleKey)) {
            lastAnimPos = -1;
            lastVisibleKey = key;
        } else {
            lastAnimPos = Integer.MAX_VALUE;
        }
    }

    // v15.18 (slice 1781): rank-close glide for filter/search set changes — the chooser's v14.23 list-change pour re-lays every visible row out at its NEW top and then pours it from +24dp, so a surviving row that moved N slots teleported N x rowHeight - 24dp in the first frame after the rebind (the Library's v15.16 defect, same shape, identity by full-list talk index instead of file path). Capture the visible rows' old tops + display positions BEFORE the rebuild below moves them (talkIndexAtDisplay maps each visible child exactly as laid out, while visibleHits still holds the OLD set); the posted runnable after the rebind parks each moved survivor at its old top and glides it home 250ms via Fx.glideHomeY. The glide is symmetric: a narrowing change moves survivors UP (oldPos > pos) and a widening change (clearing a filter/search) moves them DOWN (oldPos < pos) — both glide, shift != 0; rows that did not move and brand-new rows pour exactly as before. A newer set change arming its own map first makes an older post stand down (identity guard), so stale tops can never re-park a row.
    private java.util.HashMap<Integer, Integer> captureTalkSetGlide() {
        final java.util.HashMap<Integer, Integer> tops82 = new java.util.HashMap<Integer, Integer>();
        talkSetGlidePos = new java.util.HashMap<Integer, Integer>();
        if (talkList != null) {
            int first82 = talkList.getFirstVisiblePosition();
            for (int i82 = 0; i82 < talkList.getChildCount(); i82++) {
                android.view.View ch82 = talkList.getChildAt(i82);
                if (ch82 == null) continue;
                int ti82 = talkIndexAtDisplay(first82 + i82);
                if (ti82 < 0) continue;
                tops82.put(Integer.valueOf(ti82), Integer.valueOf(ch82.getTop()));
                talkSetGlidePos.put(Integer.valueOf(ti82), Integer.valueOf(first82 + i82));
            }
        }
        return tops82;
    }
    private void postTalkSetGlide(final java.util.HashMap<Integer, Integer> tops82) {
        if (talkList == null || tops82 == null) return;
        final java.util.HashMap<Integer, Integer> pos82 = talkSetGlidePos;
        talkList.post(new Runnable() {
            @Override public void run() {
                // A newer set change armed its own map first — stand down without gliding or clearing (its own post owns the glide now); the getView pour-skip already reads the newer map.
                if (talkList == null || talkSetGlidePos != pos82) return;
                int first82 = talkList.getFirstVisiblePosition();
                for (int i82 = 0; i82 < talkList.getChildCount(); i82++) {
                    android.view.View ch82 = talkList.getChildAt(i82);
                    if (ch82 == null) continue;
                    int ti82 = talkIndexAtDisplay(first82 + i82);
                    if (ti82 < 0) continue;
                    Integer old82 = tops82.get(Integer.valueOf(ti82));
                    if (old82 == null) continue;
                    float shift82 = old82.intValue() - ch82.getTop();
                    if (shift82 != 0) Fx.glideHomeY(ch82, shift82, 250);
                }
                if (talkSetGlidePos == pos82) talkSetGlidePos = null;
            }
        });
    }

    // v15.43 (slice 1931): Talks list sort toggle + painter —
    // the toggle body sits beside the filter-mode setter
    // because it runs the same v15.18 sequence (capture ->
    // rebuild -> pour check -> rebind -> glide).

    // v15.43 (slice 1931): Talks list sort toggle — one tap
    // reverses the shown list between the site order and
    // oldest-first (verified live: dated pages list newest-first,
    // series pages in series order — the reverse is useful on
    // both). The pref persists, so one tap survives reopening
    // the chooser. The sequence is the v15.18 filter-change
    // sequence verbatim (capture -> rebuild -> pour check ->
    // rebind -> glide): a pure reorder changes visibleKey, so
    // the pour arms, and the getView pour-skip lets the glide
    // own every moved row — the list glides into its new order
    // instead of teleporting. The button melts gold<->muted on
    // the tap (the 1926 ● idiom); Play All follows the shown
    // order (it iterates display positions), so oldest-first
    // Play All plays oldest first.
    private void toggleTalkSort(android.view.View v) {
        boolean oldest = !Prefs.talksSortOldest(TalksActivity.this);
        Prefs.setTalksSortOldest(TalksActivity.this, oldest);
        paintTalkSortBtn(true);
        final java.util.HashMap<Integer, Integer> tops31 = captureTalkSetGlide();
        rebuildVisible();
        maybePourOnListChange();
        if (adapter != null) adapter.notifyDataSetChanged();
        postTalkSetGlide(tops31);
    }

    // v15.43 (slice 1931): the sort button's state colour — gold
    // #C9A227 while oldest-first is on, the night-aware muted
    // pair (#AAAAAA night / #7A6A58 day) while off. The 1926 ●
    // painter shape: animated melt on the toggle, instant stamp
    // everywhere else (create / return / theme / restore, via
    // refreshTalkPlayAllRow below).
    private void paintTalkSortBtn(boolean animate) {
        if (btnTalkSort == null) return;
        int color = Prefs.talksSortOldest(TalksActivity.this)
                ? android.graphics.Color.parseColor("#C9A227")
                : (cachedNight ? android.graphics.Color.parseColor("#AAAAAA") : android.graphics.Color.parseColor("#7A6A58"));
        if (animate) Fx.crossfadeTextColor(btnTalkSort, color);
        else Fx.setTextColorNow(btnTalkSort, color);
    }

    // v15.45 (slice 1939): the header star toggle — favourites
    // THIS sayadaw (the name in the header), not a talk: the
    // Talks long-press dialog's fav verb is per-talk (favTalks),
    // while this flips the shared "favs" string set Main reads
    // (v1.53 reload, v7.29 staleness guard) and the shelf /
    // auto-download / fav-new row read, so the toggle lands
    // everywhere at once on return. The set is re-read fresh
    // from prefs before mutating (the v12.45 lesson: a stale
    // in-memory copy would re-poison a set Settings cleared
    // while this screen sat paused). Fx.tap beat at the click
    // site, morph + the fav_added/fav_removed toast here — the
    // Main long-press beat, with the same keys.
    private void toggleTalkFav(android.view.View v) {
        if (sayadawName == null || sayadawName.length() == 0) return;
        try {
            java.util.Set<String> f = getSharedPreferences("dhamma", MODE_PRIVATE).getStringSet("favs", null);
            talkFavSet = f == null ? new java.util.HashSet<String>() : new java.util.HashSet<String>(f);
        } catch (Exception ignored) {}
        boolean nowFav = SayadawFav.toggle(talkFavSet, sayadawName);
        getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                .putStringSet("favs", new java.util.HashSet<String>(talkFavSet)).apply();
        if (v != null) Fx.morph(v);
        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, nowFav ? "fav_added" : "fav_removed"), android.widget.Toast.LENGTH_SHORT).show();
        paintTalkFavBtn(true);
    }

    // v15.45 (slice 1939): the header star's state — gold
    // #C9A227 while this sayadaw is favourited, the night-aware
    // muted pair (#AAAAAA night / #7A6A58 day) while not: the
    // paintTalkSortBtn language above. The state is re-read
    // fresh from prefs (like refreshFavHeader's own favTalks
    // read), so an external change heals on the next header
    // refresh; the change gate (last-painted state + glyph)
    // keeps steady load/return/tick refreshes from re-touching
    // the star. Animated melt on the toggle, instant stamp
    // everywhere else (create / return / theme / restore, via
    // refreshFavHeader below).
    private void paintTalkFavBtn(boolean animate) {
        if (talkFavBtn == null) return;
        boolean fav = false;
        try {
            java.util.Set<String> f = getSharedPreferences("dhamma", MODE_PRIVATE).getStringSet("favs", null);
            fav = SayadawFav.isFav(f, sayadawName);
        } catch (Exception ignored) {}
        String glyph = SayadawFav.glyph(fav);
        CharSequence cur = talkFavBtn.getText();
        if (fav == talkHeaderFav && glyph.equals(cur == null ? "" : cur.toString())) return;
        talkHeaderFav = fav;
        talkFavBtn.setText(glyph);
        int color = fav ? android.graphics.Color.parseColor("#C9A227")
                : (cachedNight ? android.graphics.Color.parseColor("#AAAAAA") : android.graphics.Color.parseColor("#7A6A58"));
        if (animate) Fx.crossfadeTextColor(talkFavBtn, color);
        else Fx.setTextColorNow(talkFavBtn, color);
    }

    // v15.45 (slice 1941): the info button's visibility —
    // GONE until a list lands, VISIBLE after. The counts the
    // sheet shows need the loaded list (total) and the
    // new-set (new count), so a loading/failed screen never
    // grows a button that would open a zero-count sheet; the
    // search row gates on the same landing. Change-gated on
    // talkInfoShown, with the 180ms melt the back-to-top
    // button uses (slice 1940), so steady load/return/tick
    // refreshes never re-touch it.
    private void paintTalkInfoBtn() {
        if (talkInfoBtn == null) return;
        boolean want = talks != null && !talks.isEmpty()
                && sayadawName != null && sayadawName.length() > 0;
        if (want == talkInfoShown) return;
        talkInfoShown = want;
        if (want) {
            talkInfoBtn.setVisibility(android.view.View.VISIBLE);
            Fx.fadeIn(talkInfoBtn, 180);
        } else {
            Fx.fadeOut(talkInfoBtn, 180);
        }
    }

    // v15.53 (slice 1980): the share button's visibility —
    // GONE until a list lands AND the sayadaw has an original
    // page to share (the SayadawShare gate — the same rule the
    // info sheet's original-page row reads, so the button and
    // the sheet can never disagree about whether a page exists).
    // Change-gated on talkShareShown, with the 180ms melt the
    // info button uses (slice 1941), so steady load/return/tick
    // refreshes never re-touch it.
    private void paintTalkShareBtn() {
        if (talkShareBtn == null) return;
        boolean want = talks != null && !talks.isEmpty()
                && sayadawName != null && sayadawName.length() > 0
                && SayadawShare.canOffer(pageUrl);
        if (want == talkShareShown) return;
        talkShareShown = want;
        if (want) {
            talkShareBtn.setVisibility(android.view.View.VISIBLE);
            Fx.fadeIn(talkShareBtn, 180);
        } else {
            Fx.fadeOut(talkShareBtn, 180);
        }
    }

    // v15.53 (slice 1980): share this sayadaw — the Talks
    // header's first share verb: the sayadaw's name + original
    // dhammadownload.com page + credit through the system share
    // sheet, the Room header long-press's (v4.16) text through
    // the SayadawShare core. The tap-time gate re-check (the
    // 1952 lesson) reads the page fresh — a Room/notification
    // open can carry no page, and the painter's gate is a
    // snapshot from the last refresh, not a promise. No page
    // toasts no_sayadaw_page (LONG, the v4.16 shape); a share
    // mutates nothing, and suppressSharePause mirrors the row
    // share: opening the chooser must not count as leaving
    // under background-off.
    private void shareSayadaw() {
        if (!SayadawShare.canOffer(pageUrl)) {
            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "no_sayadaw_page"), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        suppressSharePause = true;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, SayadawShare.shareText(sayadawName, pageUrl, Lang.t(TalksActivity.this, "share_source")));
        startActivity(Intent.createChooser(send, Lang.t(TalksActivity.this, "share_sayadaw")));
    }

    // v15.45 (slice 1941): the sayadaw info sheet — the
    // about card for the sayadaw in this header: photo, name,
    // group, total talks, and the new-talk count. The photo
    // is the header's own resolved URL (talkInfoPhotoUrl),
    // so the sheet's portrait can never disagree with the
    // header's; the group is the intent's group extra (Main
    // always sends it) and hides when empty; the total is
    // the full loaded list (never the filtered view); the new
    // count is the Play-new gate over the full list (see
    // computeInfoNewCount), so it counts the whole sayadaw
    // even under a search or filter. The header's
    // downloaded/finished/half-heard counts are deliberately
    // NOT repeated here — the sheet shows only what no
    // surface shows. No toast; two new keys (open_orig_page,
    // the page row's label, v15.46 (slice 1943), and
    // menu_add_home, the home-screen row's label, v15.46
    // (slice 1945) — each column a verified substring, see
    // slices 1943/1945's notes). The other lines reuse
    // room_count_prefix / pl_talks / dl_hint_new_prefix and
    // the close button is dlg_close.
    private void showSayadawInfo() {
        if (talks == null || talks.isEmpty() || sayadawName == null || sayadawName.length() == 0) return;
        int total41 = talks.size();
        int new41 = computeInfoNewCount();
        android.view.View v = getLayoutInflater().inflate(R.layout.dialog_sayadaw_info, null);
        com.h2h.dhamma.CircleImageView pv = (com.h2h.dhamma.CircleImageView) v.findViewById(R.id.infoPhoto);
        if (pv != null) ImageLoader.load(TalksActivity.this,
                talkInfoPhotoUrl == null ? "" : talkInfoPhotoUrl, sayadawName, pv);
        android.widget.TextView nameTv = (android.widget.TextView) v.findViewById(R.id.infoName);
        if (nameTv != null) nameTv.setText(sayadawName);
        android.widget.TextView groupTv = (android.widget.TextView) v.findViewById(R.id.infoGroup);
        if (groupTv != null) {
            if (SayadawInfo.showGroup(sayadawGroup)) groupTv.setText(sayadawGroup);
            else groupTv.setVisibility(android.view.View.GONE);
        }
        android.widget.TextView totalTv = (android.widget.TextView) v.findViewById(R.id.infoTotal);
        if (totalTv != null) totalTv.setText(Lang.t(TalksActivity.this, "room_count_prefix") + total41 + " " + Lang.t(TalksActivity.this, "pl_talks"));
        android.widget.TextView newTv = (android.widget.TextView) v.findViewById(R.id.infoNew);
        if (newTv != null) {
            if (SayadawInfo.showNewLine(new41)) newTv.setText("\u25CF " + Lang.t(TalksActivity.this, "dl_hint_new_prefix") + new41 + " " + Lang.t(TalksActivity.this, "pl_talks"));
            else newTv.setVisibility(android.view.View.GONE);
        }
        // v15.46 (slice 1943): the original-page row — a gold
        // tappable link under the counts; hidden while the
        // sayadaw has no page URL (showPageRow), opening the
        // page in the browser on tap (the retry long-press's
        // ACTION_VIEW + toasts, v4.18).
        android.widget.TextView pageTv = (android.widget.TextView) v.findViewById(R.id.infoPage);
        if (pageTv != null) {
            if (SayadawInfo.showPageRow(pageUrl)) {
                pageTv.setText("\uD83C\uDF10 " + Lang.t(TalksActivity.this, "open_orig_page"));
                pageTv.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override public void onClick(android.view.View pv) {
                        Fx.tap(TalksActivity.this);
                        Fx.morph(pv);
                        if (pageUrl == null || pageUrl.length() == 0) {
                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "no_orig_page"), android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(pageUrl)));
                        } catch (Exception e) {
                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "open_failed"), android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            } else pageTv.setVisibility(android.view.View.GONE);
        }
        // v15.46 (slice 1945): the "add to home screen" row —
        // a gold tappable row under the original-page row; a
        // launcher shortcut straight to this sayadaw's talks,
        // so a favourite sayadaw opens in one tap without
        // opening the app first. Hidden while pinning is
        // impossible on this device (API < 26 or the launcher
        // refuses pins), while the sayadaw has no page URL
        // (the showPageRow gate, slice 1943), or while this
        // sayadaw is already pinned. Tap re-checks the gate
        // fresh (the sheet can sit open; offer and tap can
        // never disagree) and asks the launcher to pin; the
        // system confirmation dialog is the feedback, so no
        // toast — the 1940 precedent.
        android.widget.TextView homeTv = (android.widget.TextView) v.findViewById(R.id.infoHome);
        if (homeTv != null) {
            if (HomeShortcut.canShow(android.os.Build.VERSION.SDK_INT, isPinShortcutSupported45(), pageUrl)
                    && !isSayadawPinned45()) {
                homeTv.setText("\u2302 " + Lang.t(TalksActivity.this, "menu_add_home"));
                homeTv.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override public void onClick(android.view.View hv) {
                        Fx.tap(TalksActivity.this);
                        Fx.morph(hv);
                        pinSayadawShortcut45();
                    }
                });
            } else homeTv.setVisibility(android.view.View.GONE);
        }
        new android.app.AlertDialog.Builder(TalksActivity.this)
                .setView(v)
                .setPositiveButton(Lang.t(TalksActivity.this, "dlg_close"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) { d.dismiss(); }
                })
                .create().show();
    }

    // v15.46 (slice 1945): the launcher pin helpers for the
    // info sheet's home-screen row — the ShortcutManager is
    // read fresh at each call (the sheet can sit open while
    // the launcher changes), and every failure fails closed
    // to "cannot pin" instead of crashing an old device.
    private boolean isPinShortcutSupported45() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 26) return false;
            android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
            return sm != null && sm.isRequestPinShortcutSupported();
        } catch (Exception ignored) { return false; }
    }

    private boolean isSayadawPinned45() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 26) return false;
            android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
            if (sm == null) return false;
            java.util.Set<String> pinned = new java.util.HashSet<String>();
            for (android.content.pm.ShortcutInfo si : sm.getPinnedShortcuts()) {
                if (si != null && si.getId() != null) pinned.add(si.getId());
            }
            return HomeShortcut.isPinned(pinned, HomeShortcut.idFor(pageUrl));
        } catch (Exception ignored) { return false; }
    }

    private void pinSayadawShortcut45() {
        try {
            if (sayadawName == null || sayadawName.length() == 0) return;
            if (android.os.Build.VERSION.SDK_INT < 26) return;
            android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
            if (sm == null) return;
            if (!HomeShortcut.canShow(android.os.Build.VERSION.SDK_INT, sm.isRequestPinShortcutSupported(), pageUrl)) return;
            if (isSayadawPinned45()) return;
            android.content.Intent si45 = new android.content.Intent(TalksActivity.this, TalksActivity.class);
            si45.putExtra("name", sayadawName);
            si45.putExtra("group", sayadawGroup);
            si45.putExtra("url", pageUrl);
            si45.putExtra("photo", talkInfoPhotoUrl);
            si45.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            android.content.pm.ShortcutInfo info45 = new android.content.pm.ShortcutInfo.Builder(TalksActivity.this, HomeShortcut.idFor(pageUrl))
                    .setShortLabel(sayadawName)
                    .setLongLabel(sayadawName)
                    .setIcon(android.graphics.drawable.Icon.createWithResource(TalksActivity.this, R.mipmap.ic_launcher))
                    .setIntent(si45)
                    .build();
            sm.requestPinShortcut(info45, null);
        } catch (Exception e) {
            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "open_failed"), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // v15.45 (slice 1941): the sheet's new-talk count — the
    // Play-new gate (slice 1933) over the FULL loaded list,
    // not the shown/filtered view: computePlayNewIdxs returns
    // empty under a search or filter (visibleHits != null),
    // but the sheet counts the whole sayadaw either way, so
    // it rebuilds the id list + resume map here in list
    // order. Same inputs as the Play-new row (new-set,
    // played cache, resume spots via resumeMsForContinue,
    // both sounding URLs), so the count, the dots and the
    // Play-new row always agree.
    private int computeInfoNewCount() {
        if (talks == null || talks.isEmpty()) return 0;
        java.util.List<String> ids41 = new java.util.ArrayList<String>();
        for (Talk t : talks) ids41.add(t == null ? null : SiteHttp.identityUrl(t.url));
        java.util.Map<String, Integer> resumeById41 = new java.util.HashMap<String, Integer>();
        if (newTalkUrls != null) {
            for (int i = 0; i < talks.size(); i++) {
                String id41 = ids41.get(i);
                if (id41 == null || !newTalkUrls.contains(id41) || resumeById41.containsKey(id41)) continue;
                resumeById41.put(id41, Integer.valueOf(resumeMsForContinue(talks.get(i))));
            }
        }
        return SayadawInfo.unseenNewCount(ids41, newTalkUrls, talkPlayedCache, resumeById41, talkPlayingUrl, talkPlayingLibUrl);
    }

    // v15.45 (slice 1940): wire the back-to-top button — the
    // scroll listener paints on every scroll, the tap blips +
    // morphs + glides to the top (the same v4.12 glide the
    // title tap already uses). A missing list or button simply
    // never wires; the pull-to-refresh touch listener owns a
    // different callback, so the two never fight.
    private void wireTalkTopBtn() {
        talkTopBtn = findViewById(R.id.talkTopBtn);
        if (talkTopBtn != null) talkTopBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                Fx.tap(TalksActivity.this);
                Fx.morph(v);
                if (talkList != null) talkList.smoothScrollToPosition(0);
            }
        });
        if (talkList != null) talkList.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int state) {}
            @Override public void onScroll(android.widget.AbsListView v, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                paintTalkTopBtn(firstVisibleItem);
            }
        });
    }

    // v15.45 (slice 1940): the back-to-top state — shown once
    // the first visible row passes ScrollTop's threshold, hidden
    // at the top. The change gate keeps steady scrolls and the
    // rebuild/restore repaints from re-touching the button; the
    // show/hide melts (Fx.fadeIn/fadeOut, 180ms) instead of
    // snapping, and a cancelled fade never strands it
    // half-transparent (the v2.62 gen guard).
    private void paintTalkTopBtn(int firstVisible) {
        if (talkTopBtn == null) return;
        boolean want = ScrollTop.visible(firstVisible);
        if (want == talkTopShown) return;
        talkTopShown = want;
        if (want) {
            talkTopBtn.setVisibility(android.view.View.VISIBLE);
            Fx.fadeIn(talkTopBtn, 180);
        } else {
            Fx.fadeOut(talkTopBtn, 180);
        }
    }

    // v15.45 (slice 1940): repaint from the list's own position
    // after a scroll-less change — a rebuild (load / search /
    // filter / sort) or a rotation restore can land the list
    // deep without a scroll event, so read the position after
    // layout and paint from it. Posted, because the first
    // visible row is only valid once the list has laid out.
    private void paintTalkTopBtnFromList() {
        if (talkList == null) return;
        talkList.post(new Runnable() {
            @Override public void run() {
                if (talkList == null) return;
                paintTalkTopBtn(talkList.getFirstVisiblePosition());
            }
        });
    }

    // v15.43 (slice 1932): Continue row refresh + resume-spot
    // helper — the refresh body sits beside the filter-mode
    // setter because it reads the same visibleHits gate.

    // v15.43 (slice 1932): "Continue listening" row — the one-tap
    // return to this sayadaw's in-progress talk (saved spot over
    // 5s, not finished, not the sounding talk). Rides
    // refreshTalksQueuePreview (load / return / 1s tick), after
    // refreshTalkRowMarks, so the played + download caches are
    // fresh. Hidden while a search or filter narrows the list
    // (visibleHits != null — the picked talk may be hidden);
    // change-gated on identityUrl + pos, so steady ticks touch
    // no view. Shows with the Play row's fade idiom (fadeIn 250
    // / fadeOut 200). Tap shares the row tap's 700ms guard and
    // openRoom flight (see the wiring below); the Room resumes
    // from the saved spot (v1.56 re-entry), so this is a return,
    // never a replay.
    private void refreshContinueRow() {
        int idx = -1;
        int posMs = 0;
        String key32 = "";
        if (talks != null && !talks.isEmpty() && visibleHits == null) {
            java.util.List<Prefs.HistEntry> recent = Prefs.statsRecent(TalksActivity.this);
            java.util.List<String> pageIds = new java.util.ArrayList<String>();
            for (Talk t : talks) pageIds.add(t == null ? null : SiteHttp.identityUrl(t.url));
            java.util.Map<String, Integer> resumeById = new java.util.HashMap<String, Integer>();
            if (recent != null) {
                for (Prefs.HistEntry e : recent) {
                    if (e == null || e.url == null || e.url.length() == 0) continue;
                    String id = SiteHttp.identityUrl(e.url);
                    if (id == null || resumeById.containsKey(id)) continue;
                    int pi = pageIds.indexOf(id);
                    if (pi < 0) continue;
                    resumeById.put(id, Integer.valueOf(resumeMsForContinue(talks.get(pi))));
                }
            }
            idx = ContinuePick.pick(recent, pageIds, resumeById, talkPlayedCache, talkPlayingUrl, talkPlayingLibUrl);
            if (idx >= 0) {
                String id32 = pageIds.get(idx);
                Integer ms32 = id32 == null ? null : resumeById.get(id32);
                posMs = ms32 == null ? 0 : ms32.intValue();
                key32 = id32 + "|" + posMs;
            }
        }
        if (key32.equals(continuePickKey)) return;
        continuePickKey = key32;
        continuePickIdx = idx;
        if (talkContinueRow == null) return;
        if (idx < 0) {
            if (talkContinueRow.getVisibility() == android.view.View.VISIBLE) Fx.fadeOut(talkContinueRow, 200);
            return;
        }
        Talk t = talks.get(idx);
        if (talkContinueTitle != null && t != null)
            talkContinueTitle.setText(t.title + " · " + Fx.fmtTime(posMs));
        if (talkContinueRow.getVisibility() != android.view.View.VISIBLE) {
            talkContinueRow.setVisibility(android.view.View.VISIBLE);
            Fx.fadeIn(talkContinueRow, 250);
        } else if (talkContinueRow.getAlpha() < 0.999f) {
            Fx.fadeIn(talkContinueRow, 250);
        }
    }

    // v15.43 (slice 1932): the saved spot for the Continue row —
    // the getView ↺ rule's two legs verbatim (stream form, then
    // the downloaded-file form when the talk is downloaded): a
    // spot held under the downloaded form only (v5.36) must still
    // offer the return, and the talkDlCache membership guard keeps
    // a stale cache entry from resuming a deleted file.
    private int resumeMsForContinue(Talk t) {
        if (t == null || t.url == null) return 0;
        int rp = Prefs.talkPosGet(TalksActivity.this, t.url);
        if (rp <= 5000 && talkDlCache.contains(t.url)) {
            String dlFn = talkDlFileCache.get(t.url);
            if (dlFn != null) {
                String dlPath = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma/" + dlFn).getAbsolutePath();
                rp = Prefs.talkPosGet(TalksActivity.this, dlPath);
            }
        }
        return rp;
    }


    // v15.44 (slice 1933): "Play new" row — one tap queues +
    // plays only this sayadaw's new (●-dotted) talks, in shown
    // order. Rides refreshTalksQueuePreview (load / return / 1s
    // tick), after refreshContinueRow, so the played + download
    // caches are fresh. Hidden while a search or filter narrows
    // the list (visibleHits != null — a picked talk may be
    // hidden); change-gated on the joined picked identities, so
    // steady ticks touch no view. Shows with the Play row's fade
    // idiom (fadeIn 250 / fadeOut 200). The title names the
    // count ("N <word>") from queue_added_suffix's own word,
    // parsed at runtime (see playNewCountWordFromSuffix); the
    // tap re-resolves the pick fresh (see playNewShown), so a
    // state shift between refresh and tap can never queue a
    // stale set.
    private java.util.List<Integer> computePlayNewIdxs() {
        java.util.List<Integer> out = new java.util.ArrayList<Integer>();
        if (talks == null || talks.isEmpty() || visibleHits != null) return out;
        int dispCount33 = talks.size();
        java.util.List<String> dispIds33 = new java.util.ArrayList<String>();
        for (int d33 = 0; d33 < dispCount33; d33++) {
            int ti33 = talkIndexAtDisplay(d33);
            Talk t33 = (ti33 < 0 || ti33 >= talks.size()) ? null : talks.get(ti33);
            dispIds33.add(t33 == null ? null : SiteHttp.identityUrl(t33.url));
        }
        java.util.Map<String, Integer> resumeById33 = new java.util.HashMap<String, Integer>();
        if (newTalkUrls != null) {
            for (int d33 = 0; d33 < dispCount33; d33++) {
                String id33 = dispIds33.get(d33);
                if (id33 == null || !newTalkUrls.contains(id33)
                        || resumeById33.containsKey(id33)) continue;
                int ti33 = talkIndexAtDisplay(d33);
                if (ti33 < 0 || ti33 >= talks.size()) continue;
                resumeById33.put(id33, Integer.valueOf(resumeMsForContinue(talks.get(ti33))));
            }
        }
        java.util.List<Integer> dispPicked33 = PlayNewPick.pick(dispIds33, newTalkUrls,
                talkPlayedCache, resumeById33, talkPlayingUrl, talkPlayingLibUrl);
        for (Integer dp33 : dispPicked33) {
            int ti33 = talkIndexAtDisplay(dp33.intValue());
            if (ti33 >= 0 && ti33 < talks.size()) out.add(Integer.valueOf(ti33));
        }
        return out;
    }

    private void refreshPlayNewRow() {
        java.util.List<Integer> picked = computePlayNewIdxs();
        StringBuilder kb33 = new StringBuilder();
        for (Integer ti33 : picked) {
            Talk t33 = talks.get(ti33.intValue());
            if (kb33.length() > 0) kb33.append(",");
            kb33.append(t33 == null ? "?" : SiteHttp.identityUrl(t33.url));
        }
        String key33 = kb33.toString();
        if (key33.equals(playNewPickKey)) return;
        playNewPickKey = key33;
        if (talkPlayNewRow == null) return;
        if (picked.isEmpty()) {
            if (talkPlayNewRow.getVisibility() == android.view.View.VISIBLE) Fx.fadeOut(talkPlayNewRow, 200);
            return;
        }
        if (playNewCountWord == null) playNewCountWord = playNewCountWordFromSuffix();
        if (talkPlayNewTitle != null)
            talkPlayNewTitle.setText(picked.size() + " " + playNewCountWord);
        if (talkPlayNewRow.getVisibility() != android.view.View.VISIBLE) {
            talkPlayNewRow.setVisibility(android.view.View.VISIBLE);
            Fx.fadeIn(talkPlayNewRow, 250);
        } else if (talkPlayNewRow.getAlpha() < 0.999f) {
            Fx.fadeIn(talkPlayNewRow, 250);
        }
    }

    // v15.44 (slice 1933): the count word for the Play-new title
    // — queue_added_suffix's own Burmese (" <word> <word>")
    // parsed at runtime: the middle space-separated token.
    // Never hand-typed; a suffix that does not parse yields ""
    // (the title shows a bare number). Parsed once and cached.
    private String playNewCountWordFromSuffix() {
        try {
            String s = Lang.t(TalksActivity.this, "queue_added_suffix");
            if (s == null) return "";
            String[] parts = s.split(" ");
            if (parts.length == 3 && parts[1] != null && parts[1].length() > 0) return parts[1];
        } catch (Exception ignored) {}
        return "";
    }

    // v15.44 (slice 1933): play the picked new talks — the
    // playAllShown shape (v13.76) restricted to the pick: same
    // guards (empty list / no shown talk refuse with the same
    // words), same 700ms debounce on its own clock (an invalid
    // tap never consumes the window, v12.28 rule), same skips
    // (sounding + already-queued), same keep-set through the
    // funnel, same count toast, same openRoom(first picked)
    // unless it already sounds. The pick is re-resolved fresh
    // here (see refreshPlayNewRow), so the tap never trusts a
    // stale refresh.
    private void playNewShown(android.view.View v) {
        if (talks == null || talks.isEmpty()) {
            android.widget.Toast.makeText(TalksActivity.this,
                    Lang.t(TalksActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<Integer> picked33 = computePlayNewIdxs();
        if (picked33.isEmpty()) {
            android.widget.Toast.makeText(TalksActivity.this,
                    Lang.t(TalksActivity.this, "all_listened"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        int firstTi33 = picked33.get(0).intValue();
        if (firstTi33 < 0 || firstTi33 >= talks.size()) {
            android.widget.Toast.makeText(TalksActivity.this,
                    Lang.t(TalksActivity.this, "no_search_match"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Talk first33 = talks.get(firstTi33);
        boolean firstSounds33 = first33 != null && first33.url != null
                && ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && SiteHttp.sameTalk(first33.url, talkPlayingUrl))
                        || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(first33.url, talkPlayingLibUrl)));
        long now33 = android.os.SystemClock.uptimeMillis();
        if (now33 - lastPlayNewMs < 700) return;
        lastPlayNewMs = now33;
        int added33 = 0;
        java.util.Set<String> keep33 = new java.util.HashSet<String>();
        for (Integer tiBox33 : picked33) {
            int ti33 = tiBox33.intValue();
            if (ti33 < 0 || ti33 >= talks.size()) continue;
            Talk t33 = talks.get(ti33);
            if (t33 == null || t33.url == null || t33.url.length() == 0) continue;
            if ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && SiteHttp.sameTalk(t33.url, talkPlayingUrl))
                    || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(t33.url, talkPlayingLibUrl))) continue;
            if (talkQueueHas(t33.url)) continue;
            talkQueue.add(t33.url);
            keep33.add(t33.url);
            added33++;
        }
        saveTalkQueueKeepingForeign(talkQueue, keep33);
        refreshQueueMarks();
        if (!talks.isEmpty() && sayadawName != null) refreshFavHeader();
        if (adapter != null) adapter.notifyDataSetChanged();
        android.widget.Toast.makeText(TalksActivity.this, added33 > 0
                ? Lang.t(TalksActivity.this, "queue_plain_prefix") + added33 + Lang.t(TalksActivity.this, "queue_added_suffix")
                : Lang.t(TalksActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
        Fx.morph(v);
        if (!firstSounds33) openRoom(firstTi33);
    }

    // v15.45 (slice 1942): play from here — the row menu's first
    // play verb. Queue-from-here (v13.04) queues this talk and
    // every shown talk below it but never starts playback, and a
    // row tap plays one talk without queueing the run below it,
    // so starting a run mid-list took two gestures in two places
    // (long-press, queue, dismiss, find the row again and tap)
    // — in a 3,101-talk list the row is gone by the time the
    // dialog closes. One tap here queues the run AND opens the
    // Room on the anchor talk, so the run plays from here
    // onward. The playAllShown shape: same guards (empty list /
    // no shown talk refuse with the same words), same 700ms
    // debounce on its own clock (an invalid tap never consumes
    // the window, v12.28 rule), same skips (sounding +
    // already-queued, via the PlayFromHere pick), same keep-set
    // through the funnel, same count toast, same openRoom(anchor)
    // unless it already sounds. The anchor index is re-resolved
    // from dt at tap time (the v13.13 rule — the dialog can sit
    // open across a filter rebuild, so the captured position may
    // have moved); an anchor filtered out while the dialog sat
    // open falls back to the anchor alone (the v13.04 fallback
    // shape), never a stale range. Works under search/filter
    // (the queue-from-here visibleHits mapping), unlike the
    // Play-new row, which refuses under visibleHits.
    private void playFromHere(Talk dt) {
        if (talks == null || talks.isEmpty() || dt == null || dt.url == null || dt.url.length() == 0) {
            android.widget.Toast.makeText(TalksActivity.this,
                    Lang.t(TalksActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        int dispCount42 = visibleHits != null ? visibleHits.size() : talks.size();
        if (dispCount42 <= 0) {
            android.widget.Toast.makeText(TalksActivity.this,
                    Lang.t(TalksActivity.this, "no_search_match"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // The anchor's talk index, re-resolved from dt (never
        // the captured long-press position — v13.13).
        int anchorTi42 = -1;
        for (int i42 = 0; i42 < talks.size(); i42++) {
            Talk t42 = talks.get(i42);
            if (t42 != null && t42.url != null && SiteHttp.sameTalk(t42.url, dt.url)) { anchorTi42 = i42; break; }
        }
        if (anchorTi42 < 0 || anchorTi42 >= talks.size()) {
            android.widget.Toast.makeText(TalksActivity.this,
                    Lang.t(TalksActivity.this, "no_search_match"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Talk anchor42 = talks.get(anchorTi42);
        boolean anchorSounds42 = anchor42 != null && anchor42.url != null
                && ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && SiteHttp.sameTalk(anchor42.url, talkPlayingUrl))
                        || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(anchor42.url, talkPlayingLibUrl)));
        long now42 = android.os.SystemClock.uptimeMillis();
        if (now42 - lastPlayFromHereMs < 700) return;
        lastPlayFromHereMs = now42;
        // Display-order identity list (the queue-from-here
        // mapping — visibleHits when a search/filter is active,
        // else the full list), the queued set as identities, and
        // the pick: the run from the anchor onward, minus the
        // sounding talk and minus already-queued talks.
        java.util.List<String> dispIds42 = new java.util.ArrayList<String>();
        for (int d42 = 0; d42 < dispCount42; d42++) {
            int ti42 = talkIndexAtDisplay(d42);
            Talk t42 = (ti42 < 0 || ti42 >= talks.size()) ? null : talks.get(ti42);
            dispIds42.add(t42 == null ? null : SiteHttp.identityUrl(t42.url));
        }
        java.util.Set<String> queuedIds42 = new java.util.HashSet<String>();
        for (String q42 : talkQueue) queuedIds42.add(SiteHttp.identityUrl(q42));
        java.util.List<Integer> picked42 = PlayFromHere.pick(dispIds42,
                SiteHttp.identityUrl(dt.url), queuedIds42, talkPlayingUrl, talkPlayingLibUrl);
        int start42 = PlayFromHere.anchorIndex(dispIds42, SiteHttp.identityUrl(dt.url));
        int added42 = 0;
        java.util.Set<String> keep42 = new java.util.HashSet<String>();
        if (start42 < 0) {
            // Anchor filtered out while the dialog sat open:
            // queue just the anchor itself, never a stale range
            // (the v13.04 fallback shape).
            if (!anchorSounds42 && !talkQueueHas(dt.url)) {
                talkQueue.add(dt.url);
                keep42.add(dt.url);
                added42++;
            }
        } else for (Integer dpBox42 : picked42) {
            int dp42 = dpBox42.intValue();
            int ti42 = talkIndexAtDisplay(dp42);
            if (ti42 < 0 || ti42 >= talks.size()) continue;
            Talk t42 = talks.get(ti42);
            if (t42 == null || t42.url == null || t42.url.length() == 0) continue;
            if (talkQueueHas(t42.url)) continue;
            talkQueue.add(t42.url);
            keep42.add(t42.url);
            added42++;
        }
        saveTalkQueueKeepingForeign(talkQueue, keep42);
        refreshQueueMarks();
        if (!talks.isEmpty() && sayadawName != null) refreshFavHeader();
        if (adapter != null) adapter.notifyDataSetChanged();
        android.widget.Toast.makeText(TalksActivity.this, added42 > 0
                ? Lang.t(TalksActivity.this, "queue_plain_prefix") + added42 + Lang.t(TalksActivity.this, "queue_added_suffix")
                : Lang.t(TalksActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
        if (!anchorSounds42) openRoom(anchorTi42);
    }

    // v13.95 (slice 1090): one direct-set path for the filter mode —
    // the cycling button's click body, extracted verbatim (dedent
    // 20sp -> 8sp) so a chip tap can never disagree with it: same
    // toast keys, same rebuild + rebind. A tap on the already-active
    // chip just returns (the caller taps first); the old cycle's
    // increment could never land on the same mode. The cycling
    // button itself is gone — the chips are the only filter control.
    // v14.23 (slice 1677): the old body pinned lastAnimPos to MAX
    // here, so a chip tap snapped the new rows in; the rows now
    // pour when the filtered set actually changes (helper above),
    // the same set-changed rule as Main's search (v2.48).
    private void setTalkFilterMode(int mode) {
        if (mode == filterMode) return;
        filterMode = mode;
        refreshTalkFilterChipsAnimated(); // v14.39 (slice 1694): chip tap melts the colours + weight — the rows pour below (v14.23) while the tapped chip warms to gold and firms to bold; the instant painter stays for create/theme/restore/clearFilter.
        // v15.18 (slice 1781): capture BEFORE rebuildFilter + the rebind below move the rows — the posted glide after the notify parks each moved survivor at its old top (see captureTalkSetGlide).
        final java.util.HashMap<Integer, Integer> tops82 = captureTalkSetGlide();
        rebuildFilter();
        maybePourOnListChange();
        if (adapter != null) adapter.notifyDataSetChanged();
        postTalkSetGlide(tops82);
        android.widget.Toast.makeText(TalksActivity.this,
                filterMode == 1 ? Lang.t(TalksActivity.this, "filter_starred_only")
                        : (filterMode == 2 ? Lang.t(TalksActivity.this, "filter_unlistened_only")
                                : (filterMode == 3 ? Lang.t(TalksActivity.this, "filter_downloaded_only")
                                        : Lang.t(TalksActivity.this, "filter_talks_all"))),
                android.widget.Toast.LENGTH_SHORT).show();
    }
    private void clearFilter() {
        if (filterMode == 0) return;
        filterMode = 0;
        filterHits = null;
        refreshTalkFilterChips();
        // v15.18 (slice 1781): capture BEFORE rebuildVisible + the rebind below move the rows — a widening change moves survivors DOWN and glides them down home (see captureTalkSetGlide).
        final java.util.HashMap<Integer, Integer> tops82 = captureTalkSetGlide();
        rebuildVisible();
        maybePourOnListChange(); // v14.23: same set-changed pour as a chip tap
        if (adapter != null) adapter.notifyDataSetChanged();
        postTalkSetGlide(tops82);
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
        // v14.44 (slice 1699): the ✕ fades with the same edge-only
        // rhythm as the empty line above — it used to pop in on the
        // first keystroke and vanish on the last delete in one frame.
        // rebuildSearch runs on every keystroke, so only a real
        // visibility flip animates; a re-appear mid-fade-out glides
        // back from its current alpha (Fx.fadeIn, v3.52) and the
        // gen guard strands nothing hidden.
        if (clear != null) {
            boolean showClear = searchQuery != null && searchQuery.length() > 0;
            if (showClear) {
                if (clear.getVisibility() != android.view.View.VISIBLE) {
                    clear.setVisibility(android.view.View.VISIBLE);
                    Fx.fadeIn(clear, 250);
                } else if (clear.getAlpha() < 0.999f) {
                    Fx.fadeIn(clear, 250);
                }
            } else if (clear.getVisibility() == android.view.View.VISIBLE) {
                Fx.fadeOut(clear, 200);
            }
        }
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
    // v15.34 (slice 1841): pull-to-refresh state — the header
    // views, the finger tracking, the armed/refreshing flags
    // and the one height animator (settle + collapse share it,
    // identity-guarded like talksRetryFade above).
    private View pullHeader;
    private ProgressBar pullSpinner;
    private TextView pullLabel;
    private float pullStartY = -1f;
    private float pullLastY = -1f;
    private boolean pullTracking = false;
    private boolean pullArmed = false;
    private boolean pullRefreshing = false;
    private android.animation.ValueAnimator pullHeightAnim;
    // v4.14: share-chooser guard for the long-press share above —
    // mirrors the Room's suppressBgPause (see there).
    private boolean suppressSharePause = false;
    private String sayadawName, pageUrl;
    private String autoUrl;
    // v13.24 (T2): a load landing while this screen is paused (Home
    // press) must not toast or auto-open over the launcher — the v1.63
    // guard only drops finished/destroyed landings. The landing data
    // still lands (list, marks, title, retry button — its own views),
    // but the user-facing effects are stashed here and flushed from
    // onResume, when the user is looking at this screen again.
    // pendingLandingFerr null = clean load; pendingLandingEmpty = the
    // clean load came back with zero talks. landingDeferred clears
    // before the flush fires, so a second onResume never re-fires.
    // A rotation loses the stash (instance state) — the clean-empty
    // toast is lost, but the failure toast is re-shown by the
    // talkLoadFailed restore and autoUrl stays unconsumed, so the
    // restored path still auto-opens; the list state itself (empty +
    // retry) carries the failure.
    private boolean landingDeferred = false;
    private String pendingLandingFerr = null;
    private boolean pendingLandingEmpty = false;
    // v13.57 (offline resilience): the stashed failure reason key and
    // cached-list flag ride with the deferred landing above, so the
    // onResume flush shows the same reason + cached note the landing
    // would have shown while resumed.
    private String pendingLandingReason = null;
    private boolean pendingLandingCached = false;
    // v13.24 (T2): per-activity resumed flag for the landing above —
    // Activity has no isResumed() (Fragment-only), and the global
    // AppForeground.isForeground() counts ALL screens, so a Room on
    // top would read true while this chooser sits paused behind it.
    // Set in onResume, cleared in onPause; UI thread only.
    private boolean talksResumed = false;
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
    // v13.64 (F-971-A-2): last-load failure state, so a rotation
    // after a failed load restores the retry button + reason toast
    // instead of showing the cached list as a live one.
    private boolean talkLoadFailed = false;
    private String talkLoadReason = null;
    private boolean talkLoadCached = false;
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
    private boolean lastTalksStripPhotoRing = false;
    // v15.35 (slice 1848): sounding strip ring breath state —
    // the fields the helpers below own. talksStripPhotoRingBreath is
    // the running loop (null when still); talksStripPhotoRingBreathBg
    // is the background drawable it writes (the photo is a
    // child of the ring frame, so the breath animates the
    // drawable's alpha, never the view's — the portrait stays
    // solid); talksStripPhotoRingBreathPending marks a delayed start
    // still waiting out the 250ms crossfade;
    // talksStripPhotoRingBreathGen vetoes a stale delayed start after
    // a state flip or an onPause cancel.
    private android.animation.ValueAnimator talksStripPhotoRingBreath = null;
    private android.graphics.drawable.Drawable talksStripPhotoRingBreathBg = null;
    private boolean talksStripPhotoRingBreathPending = false;
    private int talksStripPhotoRingBreathGen = 0;
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
        // v15.45 (slice 1941): the sayadaw's group rides
        // the same intent Main sends (name/group/url/photo)
        // — the info sheet names the tradition; an open
        // without the extra (Room/notification) leaves it
        // empty and the sheet simply hides the group line.
        String g41 = getIntent().getStringExtra("group");
        sayadawGroup = g41 == null ? "" : g41;
        pageUrl = getIntent().getStringExtra("url");
        // v15.41 (slice 1920): newly-added talks — the dot set
        // for this sayadaw (prefs-backed, so the restore path
        // below needs no separate load).
        newTalkUrls = TalkListCache.loadNewSet(this, pageUrl);
        autoUrl = getIntent().getStringExtra("autoUrl");
        // v13.36 (Talks T5, M9/F5 class): the chooser title was wired
        // bare in onCreate — a missing id NPEd before anything was
        // usable, the same latent crash Main M9 (v13.32) and
        // Settings F5 (v13.34) closed on their screens. The title
        // view is now a guarded local, shared by the three onCreate
        // uses below; a missing title simply never wires.
        android.widget.TextView titleTv = (android.widget.TextView) findViewById(R.id.title);
        if (titleTv != null) titleTv.setText(sayadawName);
        // v15.45 (slice 1939): the header star binds beside
        // the title — the sayadaw-favourite toggle for this
        // screen (see toggleTalkFav below). The set loads
        // here and is re-read fresh on every toggle (the
        // v12.45 lesson); the painter below reads prefs
        // fresh too. Fx.tap beat at the click site, body
        // in toggleTalkFav.
        try {
            java.util.Set<String> f39 = getSharedPreferences("dhamma", MODE_PRIVATE).getStringSet("favs", null);
            talkFavSet = f39 == null ? new java.util.HashSet<String>() : new java.util.HashSet<String>(f39);
        } catch (Exception ignored) {}
        talkFavBtn = (android.widget.TextView) findViewById(R.id.talkFavBtn);
        if (talkFavBtn != null) talkFavBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                Fx.tap(TalksActivity.this);
                toggleTalkFav(v);
            }
        });
        // v15.45 (slice 1941): the header info button binds
        // beside the star — the sayadaw info sheet for this
        // screen (see showSayadawInfo below). Fx.tap beat at
        // the click site, morph + sheet in the body.
        talkInfoBtn = (android.widget.TextView) findViewById(R.id.talkInfoBtn);
        if (talkInfoBtn != null) talkInfoBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                Fx.tap(TalksActivity.this);
                Fx.morph(v);
                showSayadawInfo();
            }
        });
        // v15.53 (slice 1980): the header share button binds
        // beside the info glyph — the sayadaw share for this
        // screen (see shareSayadaw below). Fx.tap beat at the
        // click site, share sheet in the body.
        talkShareBtn = (android.widget.TextView) findViewById(R.id.talkShareBtn);
        if (talkShareBtn != null) talkShareBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                Fx.tap(TalksActivity.this);
                shareSayadaw();
            }
        });
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
            // v15.45 (slice 1941): keep the resolved header
            // photo URL for the info sheet — the same
            // resolution (intent extra, else the catalog
            // map), so the sheet's portrait can never
            // disagree with the header's.
            talkInfoPhotoUrl = headerPhoto == null ? "" : headerPhoto;
        } catch (Exception ignored) {}
        // v13.89 (slice 1081): bind the header photo ring frame (see the
        // field comment) — background only, set by updateChooserHeaderRing
        // on every header refresh; a missing frame simply never rings.
        talksHeaderPhotoRing = findViewById(R.id.talksHeaderPhotoRing);
        // v2.16: premium title entrance — the sayadaw-name title used
        // to appear instantly while its rows poured in below (v2.07
        // cascade), so the screen opened top-dead. On a fresh open
        // only, the title now fades + rises in first (same Fx
        // entrance family as the Room panel since v2.15), ahead of
        // the row cascade that follows once the list loads.
        // Rotations (saved state) never replay it, and applyTheme()
        // below touches only colour/size, never alpha/translation,
        // so the entrance ends cleanly at full opacity.
        // v15.30 (slice 1816): shared-element open — the grid photo
        // grows into the header portrait, so the window takes the
        // standard Move set (300ms) for the photo and a Fade (250ms)
        // for the rest of the screen, the Room pattern from slice
        // 1815. The header-row entrance is SKIPPED on a shared open:
        // Fx.entrance rises the whole row 24dp + fades it over
        // 320ms, which would drag the shared photo off its flight
        // path for the entire 300ms Move — the window Fade owns the
        // header's arrival instead, and the title still fades in
        // with it. Plain opens keep the entrance exactly as before.
        sharedTalksPhotoOpen = getIntent().getBooleanExtra("sharedPhoto", false);
        if (sharedTalksPhotoOpen) {
            // v15.30 (slice 1817): the grid tap carries its own
            // per-tap name in the extra — the header portrait takes
            // that name so the framework matches it to the tapped
            // card's photo instead of the last row's (every grid
            // row shares the XML name). Set in onCreate, before the
            // enter transition captures shared elements; the photo
            // bind above is untouched (a name is not a bitmap). A
            // plain open carries no extra and keeps the XML name.
            String sharedPhotoName = getIntent().getStringExtra(Fx.SHARED_PHOTO_NAME_EXTRA);
            if (sharedPhotoName != null && sharedPhotoName.length() > 0) {
                android.view.View sharedHp = findViewById(R.id.talksHeaderPhoto);
                if (sharedHp != null) sharedHp.setTransitionName(sharedPhotoName);
            }
            android.transition.TransitionSet sharedMove =
                    new android.transition.TransitionSet();
            sharedMove.addTransition(new android.transition.ChangeBounds());
            sharedMove.addTransition(new android.transition.ChangeTransform());
            sharedMove.addTransition(new android.transition.ChangeImageTransform());
            sharedMove.addTransition(new android.transition.ChangeClipBounds());
            sharedMove.setDuration(300);
            sharedMove.setInterpolator(new android.view.animation.DecelerateInterpolator());
            getWindow().setSharedElementEnterTransition(sharedMove);
            getWindow().setSharedElementReturnTransition(sharedMove);
            android.transition.Fade talksFade = new android.transition.Fade();
            talksFade.setDuration(250);
            getWindow().setEnterTransition(talksFade);
            getWindow().setReturnTransition(talksFade);
        }
        if (savedInstanceState == null && !sharedTalksPhotoOpen) Fx.entrance(findViewById(R.id.talksHeaderRow)); // v6.04: photo + title rise together
        // v4.12: tap the sayadaw-name title to glide back to the top
        // of a long talk list (same tap-to-top as Main's app name).
        // No new view, no mark, rows stay number + title only; an
        // empty or not-yet-loaded list is a no-op, and the tap blip
        // is the shared Fx.tap every other tap already uses.
        if (titleTv != null) titleTv.setOnClickListener(new android.view.View.OnClickListener() {
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
        if (titleTv != null) titleTv.setOnLongClickListener(new android.view.View.OnLongClickListener() {
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
                        if (SiteHttp.sameTalk(talks.get(i).url, liveUrl)) {
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
        // v13.36 (Talks T5): the list was wired bare — a missing id
        // NPEd on setAdapter before the rows could ever pour in.
        // Guarded in the file's own if (x != null) shape; a missing
        // list simply never wires, the search box still does.
        if (list != null) list.setAdapter(adapter);
        wirePullRefresh(); // v15.34 (slice 1841): pull-to-refresh on this list
        wireTalkTopBtn(); // v15.45 (slice 1940): back-to-top button on this list
        wireSearchBox(); // v4.93: talk title search under the header
        if (list != null) list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
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
                    if (!talkQueueHas(moveHeldUrl)) {
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
                    if ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && SiteHttp.sameTalk(moveHeldUrl, talkPlayingUrl))
                            || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(moveHeldUrl, talkPlayingLibUrl))) {
                        moveHeldUrl = null;
                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return;
                    }
                    if (!talkQueueHas(tappedUrl)) {
                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_tap_only"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int heldIdx = -1;
                    for (int i = 0; i < talks.size(); i++)
                        if (talks.get(i).url.equals(moveHeldUrl)) { heldIdx = i; break; }
                    String heldTitle = heldIdx >= 0 ? talks.get(heldIdx).title : "";
                    talkQueueRemove(moveHeldUrl);
                    talkQueue.add(talkQueueIndexOf(tappedUrl), moveHeldUrl);
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
        if (list != null) list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
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
                    if ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && SiteHttp.sameTalk(moveHeldUrl, talkPlayingUrl))
                            || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(moveHeldUrl, talkPlayingLibUrl))) {
                        moveHeldUrl = null;
                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return true;
                    }
                    if (!talkQueueHas(moveHeldUrl)) {
                        moveHeldUrl = null;
                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return true;
                    }
                    String movedUrl86 = moveHeldUrl;
                    talkQueueRemove(moveHeldUrl);
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
                                    if (e86 != null && SiteHttp.sameTalk(e86.url, movedUrl86)) { mvIdx86 = i86; break; }
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
                        && (talkPlayingUrl == null || !SiteHttp.sameTalk(t.url, talkPlayingUrl))
                        && (talkPlayingLibUrl == null || !SiteHttp.sameTalk(t.url, talkPlayingLibUrl))) {
                    final Talk dt = t;
                    java.util.ArrayList<String> dOpts = new java.util.ArrayList<String>();
                    // v13.15: key list parallel to dOpts — dialogs dispatch on keys,
                    // not on re-evaluated labels (language-proof).
                    java.util.ArrayList<String> dKeys = new java.util.ArrayList<String>();
                    dOpts.add(Lang.t(TalksActivity.this, "menu_share"));
                    dKeys.add("menu_share");
                    // v15.48 (slice 1956): share this one FILE itself — right after the menu_share text share above, so the two share verbs sit together: the text share sends the talk's title + link, this one sends the actual downloaded .mp3 through the TalkFileProvider content:// URI. Gated on the chooser's own isTalkDownloaded (mapped name + basename candidates, the same gate the download verb below reads in reverse), so a streamed-only talk never offers it; the tap-time resolve re-checks the file on disk. The menu_share offer above stays byte-identical.
                    if (isTalkDownloaded(dt.url)) { dOpts.add(Lang.t(TalksActivity.this, "dl_menu_share_file")); dKeys.add("dl_menu_share_file"); }
                    // v15.52 (slice 1977): copy this one talk's LINK — the chooser dialog's clipboard verb: the dialog could share the talk's text (menu_share) and its file (dl_menu_share_file, v15.48 slice 1956) but never copy the link — a listener who wanted just the link pasted elsewhere had to share it through the sheet and copy from there. Same verb the library per-row menu has had since v6.56 and the Room drawer gained in slice 1975, through the same RoomCopyLink core (same rule, so the three surfaces can never disagree); the offer gate is blank-url-only, so a streamed-only talk still offers it. Sits right after the file-share offer, so the share/copy trio sits together, like the drawer's.
                    if (RoomCopyLink.canOffer(dt.url)) { dOpts.add(Lang.t(TalksActivity.this, "dl_menu_copy_link")); dKeys.add("dl_menu_copy_link"); }
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
                        dFav = ft88 != null && dt.url != null && ft88.contains(SiteHttp.identityUrl(dt.url));
                    } catch (Exception ignored) {}
                    dOpts.add(dFav ? Lang.t(TalksActivity.this, "menu_fav_remove") : Lang.t(TalksActivity.this, "menu_fav_add"));
                    dKeys.add(dFav ? "menu_fav_remove" : "menu_fav_add");
                    // v15.51 (slice 1969): mark this one talk listened / unlistened — the Talks chooser's first mark verb: the library had per-row (v15.47, slice 1949) and shown-set (v6.44/v6.45 + star/new twins) mark verbs, but a streamed talk in the browsing list could only earn its listened mark by playing to the end, and a talk heard elsewhere (or a re-download of one already finished) could not be cleared from the unlistened view without opening the library. The verb sits in the existing dialog (no new view, no row menu resurrected), right after the favourite toggle — the other per-talk state toggle — and reuses the 1949 pure core (DlMark) and its keys (dl_menu_mark_one / dl_menu_unmark_one), so 0 new Lang keys. The label reads the row's own cache (talkPlayedCache, the same set the row's tick renders from), so it can never disagree with the visible mark; the sounding talk keeps its direct share below (it earns its mark by finishing, like the 1949 sounding exclusion), and the tap-time re-check in the handler below guards the auto-advance window while the dialog sits open.
                    boolean dPlaying69 = (talkPlayingUrl != null && talkPlayingUrl.length() > 0 && SiteHttp.sameTalk(dt.url, talkPlayingUrl))
                            || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(dt.url, talkPlayingLibUrl));
                    if (DlMark.canOffer(dt.url, dPlaying69)) { String markKey69 = DlMark.labelKey(DlMark.isMarked(talkPlayedCache, SiteHttp.identityUrl(dt.url))); dOpts.add(Lang.t(TalksActivity.this, markKey69)); dKeys.add(markKey69); }
                    final boolean dQueued = talkQueueHas(dt.url);
                    if (dQueued) { dOpts.add(Lang.t(TalksActivity.this, "menu_queue_remove")); dKeys.add("menu_queue_remove"); }
                    else { dOpts.add(Lang.t(TalksActivity.this, "menu_queue_add")); dKeys.add("menu_queue_add"); }
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
                    dKeys.add("menu_queue_from_here");
                    // v15.45 (slice 1942): "play from here"
                    // — the dialog's first play verb: queue
                    // this talk and every shown talk below
                    // it (the v13.04 range) AND open the Room
                    // on it, so the run plays from here
                    // onward in one tap. After its sibling
                    // (same range, no playback); share stays
                    // first, muscle memory preserved.
                    dOpts.add(Lang.t(TalksActivity.this, "menu_play_from_here"));
                    dKeys.add("menu_play_from_here");
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
                    if (dQueued) { dOpts.add(Lang.t(TalksActivity.this, "menu_move")); dKeys.add("menu_move"); }
                    String dFw = null;
                    if (talkPlayingUrl != null && talkPlayingUrl.length() > 0) {
                        for (String q : talkQueue)
                            if (!SiteHttp.sameTalk(q, talkPlayingUrl)) { dFw = q; break; }
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
                            && (!dQueued || (dFxGate != null && !SiteHttp.sameTalk(dFxGate.url, dt.url))
                            || (dFw != null && !SiteHttp.sameTalk(dFw, dt.url)))) {
                        dOpts.add(dQueued ? Lang.t(TalksActivity.this, "menu_play_next") : Lang.t(TalksActivity.this, "menu_add_next"));
                        dKeys.add(dQueued ? "menu_play_next" : "menu_add_next");
                    }
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
                    if (!isTalkDownloaded(dt.url) && !talkIsDlQueued(dt.url)) {
                        dOpts.add(Lang.t(TalksActivity.this, "dl_btn_download"));
                        dKeys.add("dl_btn_download");
                    }
                    // v15.53 (slice 1984): delete this DOWNLOADED FILE — the chooser dialog's delete verb: the browsing screen could download a talk (v11.70) but never delete its file, so a listener who had finished a downloaded talk and wanted the storage back had to leave the browsing screen, open the library and find the row again. Offered only while downloaded (the chooser's own isTalkDownloaded, through the slice core's canOffer) — the download verb above is offered exactly when this is not, so the two never share a row. The label reuses the library single delete's del_one_btn ("Delete"); the confirm + toasts below reuse its keys too, so 0 new Lang keys.
                    if (ChooserDelete.canOffer(isTalkDownloaded(dt.url))) { dOpts.add(Lang.t(TalksActivity.this, "del_one_btn")); dKeys.add("del_one_btn"); }
                    final String[] dOptArr = dOpts.toArray(new String[0]);
                    final String[] dKeyArr = dKeys.toArray(new String[0]);
                    new android.app.AlertDialog.Builder(TalksActivity.this)
                            .setTitle(dt.title != null && dt.title.length() > 0 ? dt.title : dt.url)
                            .setItems(dOptArr, new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int which) {
                                    if ("menu_share".equals(dKeyArr[which])) {
                                        suppressSharePause = true;
                                        Intent send = new Intent(Intent.ACTION_SEND);
                                        send.setType("text/plain");
                                        send.putExtra(Intent.EXTRA_TEXT, (sayadawName == null ? "" : sayadawName) + " — " + dt.title
                                                + "\n" + dt.url + Lang.t(TalksActivity.this, "share_source"));
                                        startActivity(Intent.createChooser(send, Lang.t(TalksActivity.this, "share_talk")));
                                    } else if ("dl_menu_share_file".equals(dKeyArr[which])) {
                                        // v15.48 (slice 1956): share this one DOWNLOADED FILE — the menu_share branch above sends the talk's text (title + original dhammadownload.com link + credit); a listener who wanted the actual .mp3 in another app had no way to send it from the chooser — the file sits in Downloads/Dhamma and a file:// URI is refused on API 24+. The file crosses as a TalkFileProvider content:// URI with a one-shot read grant, type audio/mpeg, same chooser title as every other share. The name comes from the chooser's own isTalkDownloaded + talkDlFileCache (v5.59 — the Resume row already reads it), re-checked fresh at tap time, and the URI is built through TalkFileShare.shareUriFor (resolve + uriStringFor), so a name that no longer resolves to a file on disk toasts the existing talk_no_longer_in_list, never a guessed URI. A share mutates nothing. suppressSharePause only when actually sending, like the text share beside it. The sounding talk keeps its direct text share below (no dialog to host a second verb; the Room drawer already serves its file share, slice 1955).
                                        java.io.File dir56 = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma");
                                        String fn56 = isTalkDownloaded(dt.url) ? talkDlFileCache.get(dt.url) : null;
                                        String uri56 = TalkFileShare.shareUriFor(dir56, fn56);
                                        if (uri56 == null) {
                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "talk_no_longer_in_list"), android.widget.Toast.LENGTH_SHORT).show();
                                        } else {
                                            suppressSharePause = true;
                                            Intent send56 = new Intent(Intent.ACTION_SEND);
                                            send56.setType("audio/mpeg");
                                            send56.putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(uri56));
                                            send56.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                            startActivity(Intent.createChooser(send56, Lang.t(TalksActivity.this, "share_talk")));
                                        }
                                    } else if ("dl_menu_copy_link".equals(dKeyArr[which])) {
                                        // v15.52 (slice 1977): "Copy this talk's link" — the chooser dialog's clipboard verb (see the offer above for the rule). Copies the talk's own original link verbatim (the v6.56 rule — never a bare title, never a guessed link) with the v6.56 clipboard label + toast, through the RoomCopyLink core: the tap-time gate re-checks the offer, so the two can never disagree. No pause guard (no sheet is left to return from) and no file gate (a link is not a file — a streamed-only talk copies too). The sounding talk keeps its direct text share below (no dialog to host a second verb; the Room drawer already serves its copy, slice 1975). A copy mutates nothing.
                                        if (RoomCopyLink.canOffer(dt.url)) {
                                            try {
                                                android.content.ClipboardManager cm77 =
                                                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                                if (cm77 != null)
                                                    cm77.setPrimaryClip(android.content.ClipData.newPlainText(
                                                            Lang.t(TalksActivity.this, "clip_talk"), RoomCopyLink.clipText(dt.url)));
                                                android.widget.Toast.makeText(TalksActivity.this,
                                                        RoomCopyLink.toastText(Lang.t(TalksActivity.this, "link_copied_prefix"), dt.title, dt.url),
                                                        android.widget.Toast.LENGTH_SHORT).show();
                                            } catch (Exception ignored) {}
                                        }
                                    } else if ("menu_fav_add".equals(dKeyArr[which]) || "menu_fav_remove".equals(dKeyArr[which])) {
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
                                            boolean cardTalk = dt.url != null && SiteHttp.sameTalk(dt.url, rsp.getString("lastUrl", ""));
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
                                        if (dt != null && dt.url != null) { menuMarkFadeUrl75 = dt.url; menuMarkBurstFav41 = Boolean.valueOf(added88); fadeMenuMarkedTalkRow(); }
                                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, added88 ? "fav_added" : "fav_removed"), android.widget.Toast.LENGTH_SHORT).show();
                                    } else if ("dl_menu_mark_one".equals(dKeyArr[which])) {
                                        // v15.51 (slice 1969): mark this one talk listened — the Talks chooser's first mark verb, reusing the 1949 pure core (DlMark) and the library's playedTalks key, so the mark lands in the same set the library, the rails, the header new-count and the filters all read. The tap-time sounding re-check (the v11.14 rule): the dialog can sit open across an auto-advance, so this talk can BE sounding by tap time — it earns its mark by finishing, never by this verb. talkPlayedCache grows in step so the row rebinds with its tick at once; refreshTalkRowMarks reloads the caches synchronously (the v10.88/v14.75 idiom, so the 1s tick snapshots the new state and sees no change), refreshFavHeader drops the header new-count, and rebuildFilter drops the row from an unlistened-only view at once. Neither the queue, the shelf order nor any saved resume position is mutated. The toast is the verb (the 1946 idiom); an already-marked tap toasts already_listened, never a false count.
                                        if ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && SiteHttp.sameTalk(dt.url, talkPlayingUrl))
                                                || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(dt.url, talkPlayingLibUrl))) {
                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        DlMark.Result marked69 = null;
                                        try {
                                            android.content.SharedPreferences sp69 = getSharedPreferences("dhamma", MODE_PRIVATE);
                                            java.util.Set<String> cur69 = sp69.getStringSet("playedTalks", new java.util.HashSet<String>());
                                            marked69 = DlMark.mark(cur69, SiteHttp.identityUrl(dt.url));
                                            sp69.edit().putStringSet("playedTalks", marked69.set).apply();
                                        } catch (Exception ignored) {}
                                        if (marked69 != null && marked69.changed) talkPlayedCache.add(SiteHttp.identityUrl(dt.url));
                                        refreshTalkRowMarks(true);
                                        if (!talks.isEmpty() && sayadawName != null) refreshFavHeader();
                                        if (filterMode != 0) rebuildFilter();
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        if (dt != null && dt.url != null) { menuMarkFadeUrl75 = dt.url; fadeMenuMarkedTalkRow(); }
                                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, (marked69 != null && marked69.changed) ? "dl_menu_mark_one" : "already_listened"), android.widget.Toast.LENGTH_SHORT).show();
                                    } else if ("dl_menu_unmark_one".equals(dKeyArr[which])) {
                                        // v15.51 (slice 1969): un-mark this one talk listened — the mark leg's mirror: the same identityUrl, the same tap-time sounding re-check (a sounding talk keeps its mark while it plays), and the v14.85 both-forms removal, so a legacy http twin banked by the old raw verb goes too. talkPlayedCache drops in step so the row rebinds without its tick at once, and rebuildFilter returns the row to an unlistened-only view at once. The toast is the verb; a not-marked tap toasts nothing_to_unmark, never a false count.
                                        if ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && SiteHttp.sameTalk(dt.url, talkPlayingUrl))
                                                || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(dt.url, talkPlayingLibUrl))) {
                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        DlMark.Result unmarked69 = null;
                                        try {
                                            android.content.SharedPreferences sp69 = getSharedPreferences("dhamma", MODE_PRIVATE);
                                            java.util.Set<String> cur69 = sp69.getStringSet("playedTalks", new java.util.HashSet<String>());
                                            unmarked69 = DlMark.unmark(cur69, SiteHttp.identityUrl(dt.url), dt.url);
                                            sp69.edit().putStringSet("playedTalks", unmarked69.set).apply();
                                        } catch (Exception ignored) {}
                                        if (unmarked69 != null && unmarked69.changed) { talkPlayedCache.remove(SiteHttp.identityUrl(dt.url)); talkPlayedCache.remove(dt.url); }
                                        refreshTalkRowMarks(true);
                                        if (!talks.isEmpty() && sayadawName != null) refreshFavHeader();
                                        if (filterMode != 0) rebuildFilter();
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        if (dt != null && dt.url != null) { menuMarkFadeUrl75 = dt.url; fadeMenuMarkedTalkRow(); }
                                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, (unmarked69 != null && unmarked69.changed) ? "dl_menu_unmark_one" : "nothing_to_unmark"), android.widget.Toast.LENGTH_SHORT).show();
                                    } else if ("menu_queue_remove".equals(dKeyArr[which])) {
                                        if (!talkQueueHas(dt.url)) {
                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        talkQueueRemove(dt.url);
                                        saveTalkQueueKeepingForeign(talkQueue, (String) null);
                                        refreshQueueMarks();
                                        if (!talks.isEmpty() && sayadawName != null) refreshFavHeader();
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        if (dt != null && dt.url != null) { menuMarkFadeUrl75 = dt.url; fadeMenuMarkedTalkRow(); }
                                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "removed_from_queue"), android.widget.Toast.LENGTH_SHORT).show();
                                    } else if ("menu_queue_add".equals(dKeyArr[which])) {
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
                                        if ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && SiteHttp.sameTalk(dt.url, talkPlayingUrl))
                                                || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(dt.url, talkPlayingLibUrl))) {
                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        if (talkQueueHas(dt.url)) {
                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        talkQueue.add(dt.url);
                                        saveTalkQueueKeepingForeign(talkQueue, dt.url);
                                        refreshQueueMarks();
                                        if (!talks.isEmpty() && sayadawName != null) refreshFavHeader();
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        if (dt != null && dt.url != null) { menuMarkFadeUrl75 = dt.url; fadeMenuMarkedTalkRow(); }
                                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "added_to_queue"), android.widget.Toast.LENGTH_SHORT).show();
                                    } else if ("menu_queue_from_here".equals(dKeyArr[which])) {
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
                                        // v13.13 T1: the start position is re-resolved
                                        // from the anchor talk dt.url at tap time — the
                                        // dialog can sit open across a filter rebuild
                                        // (the 1s strip tick rebuilds on a mark change),
                                        // which shifts visibleHits under the captured
                                        // pos, so the old loop queued from the wrong
                                        // talk (or none, when the list shrank below
                                        // pos and the toast lied "already_in_queue").
                                        // Every other dialog verb already acts on dt,
                                        // not on pos; this one does now too.
                                        int start04 = -1;
                                        if (dt != null && dt.url != null) {
                                            for (int dp04 = 0; dp04 < dispCount04; dp04++) {
                                                int ti04 = talkIndexAtDisplay(dp04);
                                                if (ti04 < 0 || ti04 >= talks.size()) continue;
                                                Talk t04 = talks.get(ti04);
                                                if (t04 != null && dt.url.equals(t04.url)) { start04 = dp04; break; }
                                            }
                                        }
                                        if (start04 < 0) {
                                            // Anchor no longer visible (filtered out
                                            // while the dialog sat open): queue just the anchor itself, never a stale range.
                                            if (dt != null && dt.url != null && dt.url.length() > 0
                                                    && !((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && SiteHttp.sameTalk(dt.url, talkPlayingUrl))
                                                            || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(dt.url, talkPlayingLibUrl)))
                                                    && !talkQueueHas(dt.url)) {
                                                talkQueue.add(dt.url);
                                                keep04.add(dt.url);
                                                added04++;
                                            }
                                        }
                                        if (start04 >= 0) for (int dp04 = start04; dp04 < dispCount04; dp04++) {
                                            int ti04 = talkIndexAtDisplay(dp04);
                                            if (ti04 < 0 || ti04 >= talks.size()) continue;
                                            Talk t04 = talks.get(ti04);
                                            if (t04 == null || t04.url == null || t04.url.length() == 0) continue;
                                            if ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && SiteHttp.sameTalk(t04.url, talkPlayingUrl))
                                                    || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(t04.url, talkPlayingLibUrl))) continue;
                                            if (talkQueueHas(t04.url)) continue;
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
                                    } else if ("menu_play_from_here".equals(dKeyArr[which])) {
                                        // v15.45 (slice 1942): play from here
                                        // — the dialog's first play verb (see
                                        // playFromHere below): queue the run
                                        // from the long-pressed talk onward
                                        // and open the Room on it in one tap.
                                        playFromHere(dt);
                                    } else if ("dl_btn_download".equals(dKeyArr[which])) {
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
                                                    new android.app.DownloadManager.Request(android.net.Uri.parse(SiteHttp.siteUrl(dt.url)));
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
                                                    Lang.t(TalksActivity.this, "download_failed") + Fx.errDetail(e), android.widget.Toast.LENGTH_LONG).show();
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
                                        // v15.28 (slice 1802): melt the row whose ⏳ just
                                        // appeared — the download verb used to end in a
                                        // bare notify, so the hourglass snapped onto its
                                        // row in one frame while the ★ toggle in the same
                                        // dialog melts (v14.75). Same shape as the fav
                                        // arm above (menuMarkFadeUrl75 + posted resolve
                                        // through visibleHits, Fx.fadeIn 250ms); the tick
                                        // snapshots the new queued state, so it cannot
                                        // double-arm (v10.88 / v14.75). The already-
                                        // downloaded / already-queued early returns above
                                        // change no mark, so they arm nothing.
                                        if (dt != null && dt.url != null) { menuMarkFadeUrl75 = dt.url; fadeMenuMarkedTalkRow(); }
                                        if (Fx.stripIsWifi(TalksActivity.this))
                                            android.widget.Toast.makeText(TalksActivity.this,
                                                    "\u2B07 " + (dt.title != null && dt.title.length() > 0 ? dt.title : dt.url) + Lang.t(TalksActivity.this, "dl_downloading_suffix"),
                                                    android.widget.Toast.LENGTH_SHORT).show();
                                        else
                                            android.widget.Toast.makeText(TalksActivity.this,
                                                    Lang.t(TalksActivity.this, "dl_room_wifi_queued"), android.widget.Toast.LENGTH_LONG).show();
                                    } else if ("del_one_btn".equals(dKeyArr[which])) {
                                        // v15.53 (slice 1984): delete this downloaded file — the library's single-row delete (doDelete) in chooser form: the confirm is the library's own (del_one_title + the talk title + del_one_btn / del_btn_no), and the positive button mirrors its rules. Tap-time re-checks, the v11.70/v11.14 pattern: a file deleted externally while the dialog sat open refreshes the marks and toasts dl_file_gone, never a guessed delete; the file resolves through TalkFileShare.resolve (the 1956 gate — canonical-path containment + isFile), so a name that no longer resolves to a file on disk toasts dl_file_gone too. Sounding by tap time (the v11.12 window) stops the session first with the ACTION_STOP intent (the library v1.56/v11.73 rule; Talks never binds, so the stop goes as the intent, the 7442 pattern). A queued URL is pruned from the hand queue first (the library v4.58 rule). On a successful delete the dlMap rows for this talk are pruned — all three key forms (the slice core's pruneKeys, the v14.88 rule) under DlLock like recordTalkDownload, one apply — then the marks refresh, the header and the row melt (the v15.28 melt), and the toast is dl_deleted_done; a failed delete toasts delete_failed and changes nothing.
                                        new android.app.AlertDialog.Builder(TalksActivity.this)
                                                .setTitle(Lang.t(TalksActivity.this, "del_one_title"))
                                                .setMessage(dt.title)
                                                .setPositiveButton(Lang.t(TalksActivity.this, "del_one_btn"), new android.content.DialogInterface.OnClickListener() {
                                                    @Override public void onClick(android.content.DialogInterface d, int which) {
                                                        if (!isTalkDownloaded(dt.url)) {
                                                            refreshTalkRowMarks(true);
                                                            if (adapter != null) adapter.notifyDataSetChanged();
                                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "dl_file_gone"), android.widget.Toast.LENGTH_SHORT).show();
                                                            return;
                                                        }
                                                        java.io.File dir84 = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma");
                                                        java.io.File f84 = TalkFileShare.resolve(dir84, talkDlFileCache.get(dt.url));
                                                        if (f84 == null) {
                                                            refreshTalkRowMarks(true);
                                                            if (adapter != null) adapter.notifyDataSetChanged();
                                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "dl_file_gone"), android.widget.Toast.LENGTH_SHORT).show();
                                                            return;
                                                        }
                                                        if (ChooserDelete.isSounding(dt.url, talkPlayingUrl, talkPlayingLibUrl)) {
                                                            android.content.Intent stop84 = new android.content.Intent(TalksActivity.this, AudioService.class);
                                                            stop84.setAction(AudioService.ACTION_STOP);
                                                            startService(stop84);
                                                        }
                                                        if (talkQueueHas(dt.url)) {
                                                            talkQueueRemove(dt.url);
                                                            saveTalkQueueKeepingForeign(talkQueue, (String) null);
                                                            refreshQueueMarks();
                                                        }
                                                        if (f84.delete()) {
                                                            synchronized (DlLock.LOCK) {
                                                                try {
                                                                    android.content.SharedPreferences sp84 = getSharedPreferences("dhamma", MODE_PRIVATE);
                                                                    org.json.JSONObject map84 = new org.json.JSONObject(sp84.getString("dlMap", "{}"));
                                                                    for (String k84 : ChooserDelete.pruneKeys(dt.url)) map84.remove(k84);
                                                                    sp84.edit().putString("dlMap", map84.toString()).apply();
                                                                } catch (Exception ignored) {}
                                                            }
                                                            refreshTalkRowMarks(true);
                                                            if (!talks.isEmpty() && sayadawName != null) refreshFavHeader();
                                                            if (adapter != null) adapter.notifyDataSetChanged();
                                                            if (dt != null && dt.url != null) { menuMarkFadeUrl75 = dt.url; fadeMenuMarkedTalkRow(); }
                                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "dl_deleted_done"), android.widget.Toast.LENGTH_SHORT).show();
                                                        } else {
                                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "delete_failed"), android.widget.Toast.LENGTH_SHORT).show();
                                                        }
                                                    }
                                                })
                                                .setNegativeButton(Lang.t(TalksActivity.this, "del_btn_no"), null)
                                                .show();
                                    } else if ("menu_move".equals(dKeyArr[which])) {
                                        // v11.22: tap-time sounding re-check, the
                                        // move branch's missing v11.12/v11.14 leg —
                                        // both sounding forms (Room URL and
                                        // library reverse-lookup URL), before any
                                        // arm, exactly like its neighbouring verbs.
                                        if ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && SiteHttp.sameTalk(dt.url, talkPlayingUrl))
                                                || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(dt.url, talkPlayingLibUrl))) {
                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        if (!talkQueueHas(dt.url)) {
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
                                        if ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && SiteHttp.sameTalk(dt.url, talkPlayingUrl))
                                                || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(dt.url, talkPlayingLibUrl))) {
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
                                                if (!SiteHttp.sameTalk(q, talkPlayingUrl)) { fwBefore = q; break; }
                                        }
                                        if (fwBefore != null && SiteHttp.sameTalk(fwBefore, dt.url)
                                                && (dFxHead == null || SiteHttp.sameTalk(dFxHead.url, dt.url))) {
                                            android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "already_next"), android.widget.Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        boolean wasQueued = talkQueueHas(dt.url);
                                        if (wasQueued) talkQueueRemove(dt.url);
                                        String fw = null;
                                        if (talkPlayingUrl != null && talkPlayingUrl.length() > 0) {
                                            for (String q : talkQueue)
                                                if (!SiteHttp.sameTalk(q, talkPlayingUrl)) { fw = q; break; }
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
                                        if (dFxHead != null && !SiteHttp.sameTalk(dFxHead.url, dt.url)) {
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
                                                        if (fxIdx64 < 0 && SiteHttp.sameTalk(e64.url, dFxHead.url)) fxIdx64 = i64;
                                                        if (dtIdx64 < 0 && SiteHttp.sameTalk(e64.url, dt.url)) dtIdx64 = i64;
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
        // v13.36 (Talks T5): the retry button was wired bare in
        // onCreate — a missing id NPEd on the first listener, so a
        // failed load could never even offer its retry. Guarded
        // local, shared by the wiring below and the two rotation
        // restores; a missing button simply never wires.
        android.view.View retryBtn = findViewById(R.id.btnRetry);
        if (retryBtn != null) retryBtn.setOnClickListener(new View.OnClickListener() {
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
        if (retryBtn != null) retryBtn.setOnLongClickListener(new View.OnLongClickListener() {
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
        if (retryBtn != null) Fx.press(retryBtn);
        // v13.76 (slice 1068): wire the chooser Play All button — one
        // tap queues the shown list and starts it from the top
        // (playAllShown); the button stays GONE until a list lands
        // (refreshTalkPlayAllRow), so a missing id simply never wires.
        btnTalkPlayAll = findViewById(R.id.btnTalkPlayAll);
        if (btnTalkPlayAll != null) btnTalkPlayAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(TalksActivity.this);
                playAllShown(v);
            }
        });
        if (btnTalkPlayAll != null) Fx.press(btnTalkPlayAll);
        // v13.93 (slice 1088): wire the Shuffle button the same
        // way — one tap shuffles the shown list into the queue
        // and starts the first shuffled talk (shufflePlayShown).
        talkPlayRow = findViewById(R.id.talkPlayRow);
        btnTalkShuffle = findViewById(R.id.btnTalkShuffle);
        if (btnTalkShuffle != null) btnTalkShuffle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(TalksActivity.this);
                shufflePlayShown(v);
            }
        });
        if (btnTalkShuffle != null) Fx.press(btnTalkShuffle);
        // v15.43 (slice 1931): wire the Sort button the same
        // way — one tap reverses the shown list between the
        // site order and oldest-first (toggleTalkSort); the
        // button shares the Play row's GONE-until-a-list-
        // lands gate, so a missing id simply never wires.
        btnTalkSort = findViewById(R.id.btnTalkSort);
        if (btnTalkSort != null) btnTalkSort.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(TalksActivity.this);
                toggleTalkSort(v);
            }
        });
        if (btnTalkSort != null) Fx.press(btnTalkSort);
        // v15.43 (slice 1932): wire the Continue row — one
        // tap returns to this sayadaw's in-progress talk
        // (openRoom on the picked index, the row tap's own
        // funnel: the shared 700ms guard, Fx.tap + Fx.press,
        // the shared-element flight). An invalid tap never
        // consumes the guard window (index resolved first),
        // and a missing id simply never wires.
        talkContinueRow = findViewById(R.id.talkContinueRow);
        talkContinueTitle = findViewById(R.id.talkContinueTitle);
        if (talkContinueRow != null) talkContinueRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (continuePickIdx < 0 || continuePickIdx >= talks.size()) return;
                long now32 = android.os.SystemClock.uptimeMillis();
                if (now32 - lastRowTapOpenMs < 700) return;
                lastRowTapOpenMs = now32;
                Fx.tap(TalksActivity.this);
                Fx.press(v);
                openRoom(continuePickIdx);
            }
        });
        // v15.44 (slice 1933): wire the Play-new row — one tap
        // queues + plays only this sayadaw's new talks
        // (playNewShown; the pick is re-resolved fresh at tap
        // time, the empty case refuses with the all_listened
        // toast and never consumes the 700ms guard). A missing
        // id simply never wires.
        talkPlayNewRow = findViewById(R.id.talkPlayNewRow);
        talkPlayNewTitle = findViewById(R.id.talkPlayNewTitle);
        if (talkPlayNewRow != null) talkPlayNewRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(TalksActivity.this);
                playNewShown(v);
            }
        });
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
                if (mh != null && talkQueueHas(mh)) moveHeldUrl = mh;
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
                            else if (savedFilter == 1) { if (ft != null && ft.contains(SiteHttp.identityUrl(u))) { filterOk = true; break; } }
                            else if (pl == null || !pl.contains(SiteHttp.identityUrl(u))) { filterOk = true; break; }
                        }
                    } catch (Exception ignored) {}
                    if (filterOk) {
                        filterMode = savedFilter;
                        android.view.View sRowF = findViewById(R.id.talkSearchRow);
                        if (sRowF != null) sRowF.setVisibility(View.VISIBLE);
                        android.view.View fRowF = findViewById(R.id.talkFilterRow);
                        if (fRowF != null) fRowF.setVisibility(View.VISIBLE);
                        refreshTalkFilterChips();
                        rebuildFilter();
                    }
                }
                String savedSearch = savedInstanceState.getString("talkSearch", "");
                if (savedSearch != null && savedSearch.length() > 0) {
                    android.view.View sRow = findViewById(R.id.talkSearchRow);
                    if (sRow != null) sRow.setVisibility(View.VISIBLE);
                    android.view.View fRow = findViewById(R.id.talkFilterRow);
                    if (fRow != null) fRow.setVisibility(View.VISIBLE);
                    // v14.23 (slice 1677): hold the restore flag while
                    // the saved query goes back into the box — the
                    // watcher fires on setText, and without the flag its
                    // pour-key logic would reset the guard and replay
                    // the fade + rise on restored rows (v1.53: a
                    // rotation restores still). The flag only skips the
                    // pour decision; the rebuild + rebind still run.
                    restoringTalkSearch = true;
                    if (searchBox != null) searchBox.setText(savedSearch);
                    else { searchQuery = savedSearch; rebuildSearch(); }
                    restoringTalkSearch = false;
                }
                adapter.notifyDataSetChanged();
                // v4.24: rotation restore re-applies the title count
                // (same rule as the fresh-load site above) instead
                // of dropping back to the plain name.
                if (sayadawName != null) refreshFavHeader();
                // v14.45 (slice 1700): a rotation restore snaps
                // the Play All row back (the v1.53 rule) — set the
                // skip flag for this call only; a fresh open /
                // retry never sets it, so the list landing fades.
                talkPlayRowSkipFirstFade = true;
                refreshTalkPlayAllRow(); // v13.76: row follows the restored list
                if (progress != null) progress.setVisibility(View.GONE);
                if (retryBtn != null) retryBtn.setVisibility(View.GONE);
                // v13.64 (F-971-A-2): a rotation after a failed load
                // restores the cached list — keep the retry button
                // and re-toast the reason, or the failure vanishes.
                if (savedInstanceState.getBoolean("talkLoadFailed", false) && retryBtn != null) {
                    retryBtn.setVisibility(View.VISIBLE);
                    String rmsg = savedInstanceState.getString("talkLoadReason", null);
                    if (rmsg != null) {
                        String fmsg = Lang.t(TalksActivity.this, rmsg);
                        if (savedInstanceState.getBoolean("talkLoadCached", false))
                            fmsg = fmsg + "\n" + Lang.t(TalksActivity.this, "cached_list_note");
                        android.widget.Toast.makeText(TalksActivity.this, fmsg, android.widget.Toast.LENGTH_LONG).show();
                    }
                }
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
                // v14.09 (Talks L1, sim1478): the same `list` local
                // the v13.36 T5 guard protects at setAdapter — the
                // restore-branch setSelection below it was bare.
                if (list != null && searchQuery.length() == 0 && filterMode == 0 && scrollPos > 0 && scrollPos < scrollLimit) list.setSelection(scrollPos);
                // v15.45 (slice 1940): a restored scroll position
                // lands deep with no scroll event — repaint the
                // back-to-top button from the restored position.
                paintTalkTopBtnFromList();
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
                        if (SiteHttp.sameTalk(talks.get(i).url, want)) { openRoom(i); found = true; break; }
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
                if (progress != null) progress.setVisibility(View.GONE);
                if (retryBtn != null) retryBtn.setVisibility(View.GONE);
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
        // v13.64 (F-971-A-2): carry the failure state, or the
        // restore branch below shows a cached list as live.
        outState.putBoolean("talkLoadFailed", talkLoadFailed);
        outState.putString("talkLoadReason", talkLoadReason);
        outState.putBoolean("talkLoadCached", talkLoadCached);
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
        talksResumed = false; // v13.24 (T2): the landing's resumed read
        stopTalksStripTick(); // v4.31: never tick a detached strip
        // v15.35 (slice 1848): same for the strip ring breath —
        // a hidden view must not keep animating, and an infinite
        // animator would pin the dead activity past a rotation.
        // The restarted tick re-arms it on return (the
        // unconditional arm after the ring change block in
        // syncTalksStripPhoto). Every destroy path passes through
        // onPause, so no separate onDestroy finish is needed.
        stopTalksStripPhotoRingBreath();
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
        // v15.34 (slice 1845): stop the header ring breath on
        // leave — a hidden view must not keep animating, and an
        // infinite animator would pin the dead activity past a
        // rotation. onResume's refreshFavHeader re-arms it on
        // return (the unconditional arm in
        // updateChooserHeaderRing). Every destroy path passes
        // through onPause, so no separate onDestroy finish is
        // needed (the talksRetryFade rule below).
        stopChooserRingBreath();
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
        // v15.30 (slice 1816): a shared-element open returns the
        // same way — the header portrait shrinks back into the
        // grid photo (finishAfterTransition runs the
        // shared-element return + the window return Fade). A plain
        // open keeps the stock nav fade.
        if (sharedTalksPhotoOpen) {
            // v15.34 (slice 1842): restore the grid name — a
            // row tap above overwrote the header portrait's
            // transition name with the Room name, so the
            // return flight to the grid would find no element
            // under the grid name and degrade to a bare fade
            // (the v15.30 flight lost after any Room visit).
            // The grid name lives in this intent's own extra
            // (the name the grid tap carried), so restoring it
            // here — after every Room return has already
            // landed, before this finish starts — costs
            // nothing. A plain open has no grid name and never
            // reaches this branch; a missing header view is
            // guarded, never a crash.
            String gridName = getIntent().getStringExtra(Fx.SHARED_PHOTO_NAME_EXTRA);
            android.view.View hp = findViewById(R.id.talksHeaderPhoto);
            if (gridName != null && gridName.length() > 0 && hp != null) hp.setTransitionName(gridName);
            finishAfterTransition(); return;
        }
        super.onBackPressed();
        overridePendingTransition(R.anim.nav_in, R.anim.nav_out);
    }

    // Open this talk in the Buddha-altar Room player.
    // v13.76 (slice 1068): Play All row visibility — VISIBLE exactly
    // when a list has landed (talks non-empty), GONE while loading,
    // on a failed load and on an empty load: the search row's own
    // rule, but driven from the list itself rather than from the
    // filter/search restore branches, so a plain rotation restore
    // (no saved filter/search) shows it too. Called from the
    // fresh-load site, the rotation-restore site and the applyTheme
    // tail (create + every return). Null-safe before onCreate wires
    // the button.
    private void refreshTalkPlayAllRow() {
        // v13.93 (slice 1088): the row owns the gate, so Play
        // All and Shuffle appear together; a missing row id
        // (old layout) falls back to the button alone.
        // v14.45 (slice 1700): the row fades in/out with the
        // list instead of snapping — the search + filter rows
        // do the same at the load callback, so all three
        // landing rows melt in together while the rows pour
        // below (v14.23). Edge-only, the v14.44 rhythm: a
        // steady landed list never re-animates (applyTheme
        // calls this on create + every return), a re-appear
        // landing mid-fade-out glides back from its current
        // alpha (Fx.fadeIn, v3.52) and the gen guard strands
        // nothing hidden. The gate formula is untouched.
        boolean show93 = (talks != null && !talks.isEmpty());
        paintTalkSortBtn(false); // v15.43 (slice 1931): instant stamp on create / return / theme / restore — the toggle itself melts (see toggleTalkSort)
        boolean skipFade93 = talkPlayRowSkipFirstFade;
        talkPlayRowSkipFirstFade = false;
        android.view.View row93 = talkPlayRow;
        if (row93 != null) {
            if (skipFade93) {
                row93.setVisibility(show93 ? android.view.View.VISIBLE : android.view.View.GONE);
                row93.setAlpha(1f);
            } else if (show93) {
                if (row93.getVisibility() != android.view.View.VISIBLE) {
                    row93.setVisibility(android.view.View.VISIBLE);
                    Fx.fadeIn(row93, 250);
                } else if (row93.getAlpha() < 0.999f) {
                    Fx.fadeIn(row93, 250);
                }
            } else if (row93.getVisibility() == android.view.View.VISIBLE) {
                Fx.fadeOut(row93, 200);
            }
            return;
        }
        if (btnTalkPlayAll == null) return;
        if (skipFade93) {
            btnTalkPlayAll.setVisibility(show93 ? android.view.View.VISIBLE : android.view.View.GONE);
            btnTalkPlayAll.setAlpha(1f);
        } else if (show93) {
            if (btnTalkPlayAll.getVisibility() != android.view.View.VISIBLE) {
                btnTalkPlayAll.setVisibility(android.view.View.VISIBLE);
                Fx.fadeIn(btnTalkPlayAll, 250);
            } else if (btnTalkPlayAll.getAlpha() < 0.999f) {
                Fx.fadeIn(btnTalkPlayAll, 250);
            }
        } else if (btnTalkPlayAll.getVisibility() == android.view.View.VISIBLE) {
            Fx.fadeOut(btnTalkPlayAll, 200);
        }
    }

    // v13.76 (slice 1068): play the shown list from the top. Queues
    // the shown talks (visibleHits when a search/filter is active,
    // else the full list — the same mapping talkIndexAtDisplay
    // uses, so a narrowed list plays exactly what is on screen)
    // with the v13.04 "queue all from here" rules — display order,
    // sounding + already-queued skipped, keep-set through the
    // funnel — then opens the Room on the first shown talk, so
    // auto-advance walks the queue from there. The final queue
    // state is exactly the state the shipped manual flow builds
    // (long-press "queue all from here" + tap the first row), so
    // no new advance semantics are needed. When the first shown
    // talk is already sounding, the queue is built but the Room is
    // NOT reopened (Downloaded's replayCurrentLibFile shape) — it
    // is already playing, and reopening would restart it. Empty
    // shown list refuses with the same words as Downloaded's Play
    // All (no_search_match / talks_none_for_sayadaw); an invalid
    // tap never consumes the 700ms window (the v12.28 rule).
    private void playAllShown(android.view.View v) {
        if (talks == null || talks.isEmpty()) {
            android.widget.Toast.makeText(TalksActivity.this,
                    Lang.t(TalksActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        int dispCount76 = visibleHits != null ? visibleHits.size() : talks.size();
        if (dispCount76 <= 0) {
            android.widget.Toast.makeText(TalksActivity.this,
                    Lang.t(TalksActivity.this, "no_search_match"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        int firstTi76 = talkIndexAtDisplay(0);
        if (firstTi76 < 0 || firstTi76 >= talks.size()) {
            android.widget.Toast.makeText(TalksActivity.this,
                    Lang.t(TalksActivity.this, "no_search_match"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Talk first76 = talks.get(firstTi76);
        boolean firstSounds76 = first76 != null && first76.url != null
                && ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && SiteHttp.sameTalk(first76.url, talkPlayingUrl))
                        || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(first76.url, talkPlayingLibUrl)));
        long now76 = android.os.SystemClock.uptimeMillis();
        if (now76 - lastPlayAllMs < 700) return;
        lastPlayAllMs = now76;
        int added76 = 0;
        java.util.Set<String> keep76 = new java.util.HashSet<String>();
        for (int dp76 = 0; dp76 < dispCount76; dp76++) {
            int ti76 = talkIndexAtDisplay(dp76);
            if (ti76 < 0 || ti76 >= talks.size()) continue;
            Talk t76 = talks.get(ti76);
            if (t76 == null || t76.url == null || t76.url.length() == 0) continue;
            if ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && SiteHttp.sameTalk(t76.url, talkPlayingUrl))
                    || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(t76.url, talkPlayingLibUrl))) continue;
            if (talkQueueHas(t76.url)) continue;
            talkQueue.add(t76.url);
            keep76.add(t76.url);
            added76++;
        }
        saveTalkQueueKeepingForeign(talkQueue, keep76);
        refreshQueueMarks();
        if (!talks.isEmpty() && sayadawName != null) refreshFavHeader();
        if (adapter != null) adapter.notifyDataSetChanged();
        android.widget.Toast.makeText(TalksActivity.this, added76 > 0
                ? Lang.t(TalksActivity.this, "queue_plain_prefix") + added76 + Lang.t(TalksActivity.this, "queue_added_suffix")
                : Lang.t(TalksActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
        Fx.morph(v);
        if (!firstSounds76) openRoom(firstTi76);
    }

    // v13.93 (slice 1088): shuffle-play the shown list — the
    // Play All shape with the queue order shuffled. Same guards
    // (empty list / no shown talk refuse with the same words),
    // same 700ms debounce on its own clock (an invalid tap never
    // consumes the window, v12.28 rule), same skips (sounding +
    // already-queued), same keep-set through the funnel, same
    // count toast, same openRoom(first shuffled) unless it
    // already sounds. The shuffled queue IS the shuffle — the
    // global Prefs.shuffleOn is left alone, so a user who
    // deliberately left it off is not surprised, and after the
    // queue drains normal advance resumes.
    private void shufflePlayShown(android.view.View v) {
        if (talks == null || talks.isEmpty()) {
            android.widget.Toast.makeText(TalksActivity.this,
                    Lang.t(TalksActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        int dispCount93 = visibleHits != null ? visibleHits.size() : talks.size();
        if (dispCount93 <= 0) {
            android.widget.Toast.makeText(TalksActivity.this,
                    Lang.t(TalksActivity.this, "no_search_match"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.ArrayList<Integer> order93 = new java.util.ArrayList<Integer>();
        for (int dp93 = 0; dp93 < dispCount93; dp93++) {
            int ti93 = talkIndexAtDisplay(dp93);
            if (ti93 < 0 || ti93 >= talks.size()) continue;
            order93.add(ti93);
        }
        if (order93.isEmpty()) {
            android.widget.Toast.makeText(TalksActivity.this,
                    Lang.t(TalksActivity.this, "no_search_match"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.Collections.shuffle(order93, new java.util.Random());
        int firstTi93 = order93.get(0);
        Talk first93 = talks.get(firstTi93);
        boolean firstSounds93 = first93 != null && first93.url != null
                && ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && SiteHttp.sameTalk(first93.url, talkPlayingUrl))
                        || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(first93.url, talkPlayingLibUrl)));
        long now93 = android.os.SystemClock.uptimeMillis();
        if (now93 - lastShuffleMs < 700) return;
        lastShuffleMs = now93;
        int added93 = 0;
        java.util.Set<String> keep93 = new java.util.HashSet<String>();
        for (int ti93 : order93) {
            Talk t93 = talks.get(ti93);
            if (t93 == null || t93.url == null || t93.url.length() == 0) continue;
            if ((talkPlayingUrl != null && talkPlayingUrl.length() > 0 && SiteHttp.sameTalk(t93.url, talkPlayingUrl))
                    || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(t93.url, talkPlayingLibUrl))) continue;
            if (talkQueueHas(t93.url)) continue;
            talkQueue.add(t93.url);
            keep93.add(t93.url);
            added93++;
        }
        saveTalkQueueKeepingForeign(talkQueue, keep93);
        refreshQueueMarks();
        if (!talks.isEmpty() && sayadawName != null) refreshFavHeader();
        if (adapter != null) adapter.notifyDataSetChanged();
        android.widget.Toast.makeText(TalksActivity.this, added93 > 0
                ? Lang.t(TalksActivity.this, "queue_plain_prefix") + added93 + Lang.t(TalksActivity.this, "queue_added_suffix")
                : Lang.t(TalksActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
        Fx.morph(v);
        if (!firstSounds93) openRoom(firstTi93);
    }

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
        // v15.34 (slice 1842): shared-element open — the
        // chooser header portrait grows into the Room header
        // portrait, the Spotify / YouTube Music / Apple Music
        // now-playing open, on the funnel every Room entry
        // from this screen already uses (row tap, Play All,
        // Shuffle, auto-opens). The strip tap beside it
        // already flies (v15.29/v15.31); the row tap used to
        // slide plain, so the same face snapped from the
        // chooser header to the Room header. Same photo both
        // ends (same intent "photo" extra, same ImageLoader
        // memory cache, same CircleImageView), so the snapshot
        // hands over with no pop; the header is fixed above
        // the list, always laid out. Per-tap unique name, the
        // v15.30/1817 idiom: the Room renames its portrait
        // from this extra in onCreate, and the return flight
        // finds the header under the same name. Fx.
        // goSharedNamed falls back to plain Fx.go itself when
        // the header is hidden or not laid out, so a shared
        // open can never be the reason a Room fails to open.
        // No CLEAR_TOP flags on this intent, so no roomInStack
        // guard (that guard is only for the v15.31 CLEAR_TOP
        // re-delivery paths — a fresh Room stacks and the
        // flight runs normally even with a Room alive below).
        String sharedRoomName = Fx.sharedPhotoNameFor(Fx.SHARED_PHOTO_NAME, sayadawName);
        i.putExtra(Fx.SHARED_PHOTO_NAME_EXTRA, sharedRoomName);
        Fx.goSharedNamed(TalksActivity.this, i, findViewById(R.id.talksHeaderPhoto), sharedRoomName);
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
        // v13.36 (Talks T5): applyTheme runs on open AND on every
        // return — a missing title NPEd here both times, although
        // every other view in this method is already guarded (root,
        // retry, list, search box). Guard the two title writes.
        if (title != null) title.setTextColor(night
                ? android.graphics.Color.parseColor("#C9A227")
                : android.graphics.Color.parseColor("#7A2E1F"));
        int f = Prefs.fontSize(this);
        if (title != null) title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, f == 0 ? 18 : (f == 1 ? 20 : 26));
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
        // v13.76 (slice 1068): Play All button follows the theme like
        // the retry button beside it — same button step (14/16/18),
        // same glass face, same ink/white text; the label follows
        // the UI language via the existing pl_play_all key, so
        // Burmese display is byte-identical to the layout text.
        android.widget.Button pa76 = btnTalkPlayAll;
        if (pa76 != null) {
            pa76.setText(Lang.t(this, "pl_play_all"));
            pa76.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    f == 0 ? 14 : (f == 1 ? 16 : 18));
            pa76.setBackgroundResource(night ? R.drawable.glass_btn_night : R.drawable.glass_btn);
            pa76.setTextColor(night
                    ? android.graphics.Color.WHITE
                    : android.graphics.Color.parseColor("#3B2314"));
        }
        // v13.93 (slice 1088): Shuffle follows the theme like
        // Play All beside it — same step, same glass face, same
        // ink/white text; the label follows the UI language via
        // the existing "shuffle" key, so Burmese display is
        // byte-identical to the layout text.
        android.widget.Button sh93 = btnTalkShuffle;
        if (sh93 != null) {
            sh93.setText(Lang.t(this, "shuffle"));
            sh93.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    f == 0 ? 14 : (f == 1 ? 16 : 18));
            sh93.setBackgroundResource(night ? R.drawable.glass_btn_night : R.drawable.glass_btn);
            sh93.setTextColor(night
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
            // Same EXISTING keys refreshTalkFilterChips already uses, so
            // Burmese display is byte-identical; the null guard inside
            // makes the pre-wire first-create pass a safe no-op.
            refreshTalkFilterChips();
            // v9.97: the search box kept its layout size (16sp) in
            // every font setting -- Main's and Downloaded's search
            // boxes both follow Prefs.fontSize and the Talks rows
            // do, but applyTheme() never sized this row, so a
            // large-text listener returning from Settings got a small
            // search row on a grown screen (same back-stack-stale
            // class as v9.91/v9.94/v9.96, font-size dimension). Box
            // 14/16/20 = the exact Main/Downloaded search step.
            // Normal font yields exactly the layout size, so default
            // display is unchanged. Runs on create and on every
            // return, inside the existing searchBox null guard.
            // v13.95: the chips are sized by their own painter
            // (12/14/16, the old filter step), not here.
            searchBox.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    f == 0 ? 14 : (f == 1 ? 16 : 20));
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
        refreshTalkPlayAllRow(); // v13.76: row follows the loaded list
        if (adapter != null) adapter.notifyDataSetChanged();
        refreshTalksQueuePreview(); // v4.87: load/return paints the preview before the first tick
    }

    @Override
    protected void onResume() {
        super.onResume();
        talksResumed = true; // v13.24 (T2): the landing's resumed read
        // v14.98 (slice 1756): snapshot the ★/✓ caches at the very
        // top of the return, BEFORE applyTheme's tail
        // refreshTalksQueuePreview (or the dip's applyTheme) refreshes
        // them — the diff below is taken against these pre-return
        // snapshots, so a ★ gained in the Room (or a ✓ banked by a
        // finish) is exactly the changed set. Gated on resumedOnce
        // (a real return): the first onResume is creation, whose
        // load pour already carries the rows, and a rotation restore
        // is a fresh instance (resumedOnce false), so neither melts
        // on top of the entrance pour (v14.23).
        boolean retIsReturn98 = resumedOnce;
        java.util.Set<String> retFavBefore98 = null;
        java.util.Set<String> retPlayedBefore98 = null;
        java.util.Set<String> retQueuedBefore98 = null;
        if (retIsReturn98) {
            retFavBefore98 = talkFavCache != null
                    ? new java.util.HashSet<String>(talkFavCache) : new java.util.HashSet<String>();
            retPlayedBefore98 = talkPlayedCache != null
                    ? new java.util.HashSet<String>(talkPlayedCache) : new java.util.HashSet<String>();
            retQueuedBefore98 = talkDlQueuedCache != null
                    ? new java.util.HashSet<String>(talkDlQueuedCache) : new java.util.HashSet<String>();
        }
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
                // v14.09 (Talks L2, sim1478): the header's Up Next
                // line reads talkQueue, and applyTheme refreshes
                // it — so re-refresh the header here, after the
                // dip's swap, or it keeps the pre-dip queue until
                // the next tick (<=1s). The non-dip path below
                // already refreshes the header after applyTheme.
                @Override public void run() { applyTheme(); refreshFavHeader(); }
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
        // v14.98 (slice 1756): melt the rows whose ★/✓ changed while
        // this chooser sat paused — the return twin of the v14.97
        // tick melt. First-hand this run: on return with filterMode
        // == 0 the new mark sat STALE, neither melting nor snapping
        // — applyTheme's tail notify rebinds BEFORE the caches
        // refresh (its own tail refreshTalksQueuePreview refreshes
        // them after the notify), the refreshTalkRowMarks call above
        // rebinds only on a downloaded-set change or a prune, the
        // notify below is gated on filterMode != 0, and the next
        // tick snapshots the already-refreshed caches, so
        // marksChanged44 is false and the v14.97 arm never fires.
        // The header count refreshed (refreshFavHeader above) while
        // the row kept its old mark — a visible disagreement until
        // the next load, menu action, sounding change or
        // scroll-recycle repaint. The diff reuses the pure
        // syncMarksChanged97 (a spelling-only http/https split diffs
        // to nothing); the rebind runs only when the set is
        // non-empty and only in mode 0 (mode != 0 rebinds just
        // below), and the fade reuses fadeSyncMarkedTalkRows97
        // (posted, resolved at fade time through visibleHits,
        // Fx.fadeIn 250ms, consume-once) — a ★-only filter unstar
        // drops the row in rebuildFilter before the posted resolve
        // and fades nothing (the v14.75 case). A theme-dip return
        // still ends correct: the dip's applyTheme rebind reads the
        // refreshed caches, and the v14.47 getView hygiene restores
        // row alpha 1 on any rebind, so the fade can never strand a
        // row half-transparent.
        // v15.28 (slice 1802): the queued pair joins the return diff
        // too — a ⏳ gained while in the Room (queued from the drawer
        // or the data gate) melts on the return rebind, like ★/✓.
        if (retIsReturn98 && retFavBefore98 != null && retPlayedBefore98 != null && retQueuedBefore98 != null) {
            java.util.Set<String> retChanged98 = syncMarksChanged97(retFavBefore98, talkFavCache,
                    retPlayedBefore98, talkPlayedCache,
                    retQueuedBefore98, talkDlQueuedCache);
            if (retChanged98 != null && !retChanged98.isEmpty()) {
                if (filterMode == 0 && adapter != null) adapter.notifyDataSetChanged();
                syncMarksFadeUrls97 = retChanged98;
                fadeSyncMarkedTalkRows97();
            }
        }
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
                        // (v14.07: except a successful 0-byte landing, which toasts
                        // download_failed — the row never flips ⏳→⬇ for a dead file.)
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
                                                        oursFail = mapFail.has(failUrl) || mapFail.has(SiteHttp.identityUrl(failUrl));
                                                    } catch (Exception ignored) {}
                                                    String failTitle = null;
                                                    int titleColFail = curFail.getColumnIndex(android.app.DownloadManager.COLUMN_TITLE);
                                                    if (titleColFail >= 0) failTitle = curFail.getString(titleColFail);
                                                    if (talks != null) {
                                                        for (Talk t : talks) {
                                                            if (t != null && SiteHttp.sameTalk(failUrl, t.url)) {
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
                        // v14.07 (audit-1356 L1): same hole as the library receiver — a
                        // SUCCESSFUL job landing a 0-byte file reports success, so the
                        // failure block above stays silent, and isTalkDownloaded (length-gated
                        // since v13.66) never flips the row ⏳→⬇: the download vanished with
                        // no word. Toast download_failed + title for that case, same string
                        // shape as the failure block. Gated on the source URL being a talk
                        // this app queued (dlMap, or this list) and on the mapped file
                        // existing with length 0 — a missing file is not toasted (deleted
                        // between landing and this callback). Separate try, BEFORE the
                        // card-sync block: that block returns early on !liveSession, but a
                        // dead landing must be reported even while nothing plays. A real
                        // landing still toasts nothing — the ⏳→⬇ row flip is the feedback.
                        try {
                            long doneId = in.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                            if (doneId >= 0) {
                                android.app.DownloadManager dmDone = (android.app.DownloadManager)
                                        getSystemService(android.content.Context.DOWNLOAD_SERVICE);
                                android.database.Cursor curDone = dmDone.query(
                                        new android.app.DownloadManager.Query().setFilterById(doneId));
                                if (curDone != null) {
                                    try {
                                        if (curDone.moveToFirst()) {
                                            int stColDone = curDone.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS);
                                            if (stColDone >= 0 && curDone.getInt(stColDone) == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                                                int srcColDone = curDone.getColumnIndex(android.app.DownloadManager.COLUMN_URI);
                                                String doneUrl = srcColDone >= 0 ? curDone.getString(srcColDone) : null;
                                                if (doneUrl != null && doneUrl.length() > 0) {
                                                    String mappedDone = null;
                                                    boolean oursDone = false;
                                                    try {
                                                        org.json.JSONObject mapDone = new org.json.JSONObject(
                                                                getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlMap", "{}"));
                                                        // v13.59 (887 F-NEW-1, F944-1 twin): dlMap keys are https — the DM source may be the http form.
                                                        oursDone = mapDone.has(doneUrl) || mapDone.has(SiteHttp.identityUrl(doneUrl));
                                                        mappedDone = mapDone.optString(doneUrl, "");
                                                        if (mappedDone == null || mappedDone.length() == 0) mappedDone = mapDone.optString(SiteHttp.identityUrl(doneUrl), "");
                                                    } catch (Exception ignored) {}
                                                    if (!oursDone && talks != null) {
                                                        for (Talk t : talks) {
                                                            if (t != null && SiteHttp.sameTalk(doneUrl, t.url)) {
                                                                oursDone = true;
                                                                if (mappedDone == null || mappedDone.length() == 0) mappedDone = talkMappedFileName(t.url);
                                                                break;
                                                            }
                                                        }
                                                    }
                                                    if (oursDone && mappedDone != null && mappedDone.length() > 0) {
                                                        java.io.File landedDone = new java.io.File(
                                                                new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                                                                        android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma"), mappedDone);
                                                        if (landedDone.isFile() && landedDone.length() == 0) {
                                                            String doneTitle = null;
                                                            int titleColDone = curDone.getColumnIndex(android.app.DownloadManager.COLUMN_TITLE);
                                                            if (titleColDone >= 0) doneTitle = curDone.getString(titleColDone);
                                                            if ((doneTitle == null || doneTitle.length() == 0) && talks != null) {
                                                                for (Talk t : talks) {
                                                                    if (t != null && SiteHttp.sameTalk(doneUrl, t.url) && t.title != null && t.title.length() > 0) { doneTitle = t.title; break; }
                                                                }
                                                            }
                                                            if (doneTitle == null || doneTitle.length() == 0) doneTitle = doneUrl;
                                                            android.widget.Toast.makeText(TalksActivity.this,
                                                                    Lang.t(TalksActivity.this, "download_failed") + doneTitle,
                                                                    android.widget.Toast.LENGTH_LONG).show();
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } finally { curDone.close(); }
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
                            boolean cardTalk = SiteHttp.sameTalk(srcUrl, rsp.getString("lastUrl", ""));
                            if (!cardTalk) {
                                String libPath = rsp.getString("lastLibPath", "");
                                if (libPath.length() > 0) {
                                    String fn = new org.json.JSONObject(rsp.getString("dlMap", "{}")).optString(srcUrl, "");
                                    // v13.62 (F-1024-B-1): legacy http dlMap key
                                    // misses the raw lookup — try the identity form.
                                    if (fn.length() == 0) fn = new org.json.JSONObject(rsp.getString("dlMap", "{}")).optString(SiteHttp.identityUrl(srcUrl), "");
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
        // v13.24 (T2): flush a load landing's user-facing effects
        // deferred because the load finished while this screen was
        // paused (Home press) — the failure/empty toast and the
        // auto-open fire here, when the user is looking at this
        // screen again, exactly once. A rotation loses the stash
        // (instance state), but autoUrl stays unconsumed, so the
        // restored path still auto-opens.
        if (landingDeferred) flushDeferredLanding();
    }

    // v13.89 (slice 1081): the chooser header photo ring — one boolean
    // (ringing) drives the frame's background, change-only on
    // lastChooserHeaderRing, so a steady refresh never re-sets it.
    // The predicate is the rows' sounding rule over this list (see the
    // field comment): any in-list talk matching the Room session's
    // talkPlayingUrl or the library session's talkPlayingLibUrl, while
    // not ended. Runs at the top of refreshFavHeader, before its early
    // returns, so an empty or not-yet-loaded list still clears a stale
    // ring — and the tick's change branch (talkPlayingUrl / LibUrl /
    // liveEnded) refreshes the header before the rebind, so the ring
    // moves within a tick of a switch, an auto-advance or a natural end.
    private void updateChooserHeaderRing() {
        boolean ringing = false;
        if (!AudioService.liveEnded && talks != null) {
            for (Talk t : talks) {
                if (t == null || t.url == null) continue;
                if ((talkPlayingUrl != null && talkPlayingUrl.length() > 0
                        && SiteHttp.sameTalk(t.url, talkPlayingUrl))
                        || (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0
                        && SiteHttp.sameTalk(t.url, talkPlayingLibUrl))) { ringing = true; break; }
            }
        }
        // v14.07 (slice 1102): the header ring names the failure too —
        // gold while an in-list talk sounds, the red failed ring while
        // it sounds + failed (reused shelf_ring_failed.xml, v14.05),
        // nothing otherwise. The rows below already say ⚠ (v14.05),
        // but the portrait itself read exactly like a healthy sounding
        // talk — the same class v14.05 fixed on the Room header ring.
        // Change-only on both bits, so a steady refresh never re-sets
        // the background; no tick change — refreshFavHeader rides the
        // tick's failure-flip branch already (talkRowFailed) and this
        // update runs at its top, so the ring flips within a tick.
        // Loading keeps the gold ring (v14.06: the ring says sounding).
        boolean ringFailed = ringing && AudioService.liveTrackError;
        if (ringing != lastChooserHeaderRing || ringFailed != lastChooserHeaderRingFailed) {
            lastChooserHeaderRing = ringing;
            lastChooserHeaderRingFailed = ringFailed;
            // v15.34 (slice 1845): the breath owns the outgoing
            // background's alpha — cancel it before the
            // background is replaced, or its animator would
            // keep writing to a detached drawable and the
            // restore below would land on the wrong drawable.
            // A no-op when no breath runs (first gold, fresh
            // instance); the restore puts the old drawable back
            // to full alpha for any later reuse.
            stopChooserRingBreath();
            if (talksHeaderPhotoRing != null) {
                // v14.16 (slice 1670): gold/red/none crossfades
                // instead of snapping — see Fx.crossfadeRing.
                Fx.crossfadeRing(talksHeaderPhotoRing, ringFailed ? R.drawable.shelf_ring_failed
                        : ringing ? R.drawable.shelf_ring_playing : 0);
            }
        }
        // v15.34 (slice 1845): arm the breath on a gold ring,
        // stop it anywhere else — the change block above fires
        // only on a state flip, so this unconditional arm is
        // the re-arm after an onPause cancel (a hidden view must
        // not keep animating, and an infinite animator would
        // pin the dead activity): onResume's refreshFavHeader
        // re-runs this method on every return while the ring
        // state never moved. A running breath or a pending start
        // is a no-op; a failed (red) ring never breathes — an
        // error state must read still, not alive.
        if (ringing && !ringFailed) armChooserRingBreath();
        else stopChooserRingBreath();
    }

    // v15.34 (slice 1845): sounding header ring breath — the
    // last sounding ring that sat static while its talk played
    // (the Main grid card name breathes, v3.13/v3.17; the Room
    // play glow breathes, v2.07/v2.20; the Library pause icon
    // breathes, v2.25). The ring breathes 1.00->0.55 on a 900ms
    // reverse loop, the Library rhythm, while a talk in this
    // list sounds — visible the moment the user browses a
    // sounding sayadaw's list. The photo is a child of the
    // ring frame, so the loop writes the background drawable's
    // alpha, never the view's: crossfadeRing paints with a
    // TransitionDrawable and never touches the view alpha, so
    // the two never fight. The start waits 260ms (the crossfade
    // is 250ms) so the breath never dims a fade-in, and the
    // loop drops itself the moment the background is replaced.
    private void armChooserRingBreath() {
        if (chooserRingBreath != null || chooserRingBreathPending) return;
        if (talksHeaderPhotoRing == null) return;
        chooserRingBreathPending = true;
        final int myGen = chooserRingBreathGen;
        talksHeaderPhotoRing.postDelayed(new Runnable() {
            @Override public void run() {
                chooserRingBreathPending = false;
                if (myGen != chooserRingBreathGen) return;
                if (talksHeaderPhotoRing == null) return;
                if (!lastChooserHeaderRing || lastChooserHeaderRingFailed) return;
                android.graphics.drawable.Drawable bg = talksHeaderPhotoRing.getBackground();
                if (bg == null) return;
                chooserRingBreathBg = bg;
                android.animation.ValueAnimator breath = android.animation.ValueAnimator.ofInt(255, 140);
                breath.setDuration(900);
                breath.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                breath.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                breath.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                        android.graphics.drawable.Drawable cur = chooserRingBreathBg;
                        if (cur == null || talksHeaderPhotoRing == null
                                || talksHeaderPhotoRing.getBackground() != cur) {
                            // Background replaced (a crossfade) —
                            // drop the loop; the arm re-starts it
                            // on the new background if the ring
                            // is still gold. No gen bump: no
                            // delayed start can exist while a
                            // loop runs (the arm returns early).
                            chooserRingBreath = null;
                            chooserRingBreathBg = null;
                            a.cancel();
                            return;
                        }
                        cur.setAlpha(((Integer) a.getAnimatedValue()).intValue());
                    }
                });
                chooserRingBreath = breath;
                breath.start();
            }
        }, 260);
    }

    private void stopChooserRingBreath() {
        if (chooserRingBreath == null && !chooserRingBreathPending) return;
        chooserRingBreathGen++;
        chooserRingBreathPending = false;
        if (chooserRingBreath != null) chooserRingBreath.cancel();
        chooserRingBreath = null;
        if (chooserRingBreathBg != null) {
            // v15.37 (slice 1882): ease out, never snap — the
            // ring glides back to opaque over 200ms (Fx
            // easeRingAlphaToOpaque), which always lands
            // before any re-arm's 260ms delayed start.
            android.graphics.drawable.Drawable stoppedBg = chooserRingBreathBg;
            chooserRingBreathBg = null;
            Fx.easeRingAlphaToOpaque(stoppedBg);
        }
    }

    private void refreshFavHeader() {
        updateChooserHeaderRing(); // v13.89 (slice 1081): the ring rides the header refresh, never a steady tick
        // v15.45 (slice 1939): the header star paints on
        // every header refresh (load / return / change-only
        // tick) — before the early returns below, so an
        // empty list or a missing title never strands a
        // stale star, and a Settings clear heals on return.
        // The painter is change-gated, so steady refreshes
        // never re-touch it.
        paintTalkFavBtn(false);
        // v15.45 (slice 1941): the info button paints on
        // every header refresh too — before the early returns
        // below, so an empty list hides it and a landed list
        // shows it; the painter is change-gated, so steady
        // refreshes never re-touch it.
        paintTalkInfoBtn();
        // v15.53 (slice 1980): the share button paints on
        // every header refresh too — same funnel as the info
        // button above, so an empty list or a page-less
        // sayadaw hides it and a landed list with a page
        // shows it; the painter is change-gated, so steady
        // refreshes never re-touch it.
        paintTalkShareBtn();
        // v13.85 (slice 1077): clear the Up Next row mark first — this method's two early returns below (empty list / no sayadaw / no header view) must not leave a stale upNextRowUrl crowning a row from a dead session.
        upNextRowUrl = "";
        talkRefreshInProgressDlNames(false); // v11.57: header ⬇ count reads isTalkDownloaded fresh
        if (talks == null || talks.isEmpty() || sayadawName == null) return;
        android.widget.TextView ht = findViewById(R.id.title);
        if (ht == null) return;
        int fc = 0;
        try {
            java.util.Set<String> ft = getSharedPreferences("dhamma", MODE_PRIVATE).getStringSet("favTalks", null);
            if (ft != null) for (Talk t : talks) if (ft.contains(SiteHttp.identityUrl(t.url))) fc++;
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
            if (pl != null) for (Talk t : talks) if (pl.contains(SiteHttp.identityUrl(t.url))) playedCount++;
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
                    if (SiteHttp.sameTalk(t.url, talkPlayingUrl)
                            || (talkPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(t.url, talkPlayingLibUrl))) continue;
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
                if (SiteHttp.sameTalk(talks.get(i).url, talkPlayingUrl)) { playIdx = i; break; }
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
                        if (talkPlayingUrl != null && SiteHttp.sameTalk(qu, talkPlayingUrl)) continue;
                        for (int i = 0; i < talks.size(); i++)
                            if (SiteHttp.sameTalk(talks.get(i).url, qu)) { nxTalk = talks.get(i); break; }
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
                                if (SiteHttp.sameTalk(fu21, talkPlayingUrl)) continue;
                                for (int i = 0; i < talks.size(); i++)
                                    if (SiteHttp.sameTalk(talks.get(i).url, fu21)) { nxTalk = talks.get(i); break; }
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
        // v13.85 (slice 1077): the row mark's source — the header's Up Next line, in row form. The line names the foreign head first when one waits (v7.22/v8.62 branch below), and a foreign head has no in-list row to crown (the drawer's v11.60 rule), so the field is empty then; else it is the resolved in-list next talk (queued head, forward top, or shuffle-gated list-next, the resolution above), or empty when none resolves (no live Room session, single-talk list, shuffle with no head). The rows read only this field, never the drawer's peek — the chooser never owned that machinery — and refreshFavHeader is the one place it is written, on every load, return, queue action and change-only tick.
        upNextRowUrl = fxHead != null ? "" : (nxTalk != null ? nxTalk.url : "");
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
                        // v13.63 (F-1024-B-2): the DM source URL is the
                        // transport form (http once this launch proved
                        // TLS refused) while talkUrls holds https —
                        // normalise once and compare by identity, like
                        // the strip count below.
                        String srcId = src == null ? null : SiteHttp.identityUrl(src);
                        if (srcId != null && talkUrls.contains(srcId)) progCount++;
                        // v4.71: the Up Next talk's live percent, from
                        // this same query — no second query, and -1
                        // stays -1 when the talk is not downloading
                        // (saved talks read ⬇ instead, below).
                        if (nxTalk != null && srcId != null && srcId.equals(nxTalk.url)
                                && soFarCol >= 0 && totalCol >= 0) {
                            long soFar = c.getLong(soFarCol);
                            long total = c.getLong(totalCol);
                            if (total > 0 && soFar >= 0) nxPct = (int) (soFar * 100 / total);
                        }
                        // v8.64: same percent for the foreign head (its
                        // URL is not in talkUrls, so it is never counted
                        // in progCount above — only its own percent here).
                        if (fxHead != null && srcId != null && srcId.equals(fxHead.url)
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
                    && talkQueueHas(talkPlayingUrl)) waitQ--;
            // v11.76: same skip for the library-playing talk — it stays queued
            // (a library play never pops the Room queue), so without this the
            // header ◈ read one above the row marks; the inequality guard keeps
            // a talk that is somehow both forms from being subtracted twice.
            if (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0
                    && !SiteHttp.sameTalk(talkPlayingLibUrl, talkPlayingUrl)
                    && talkQueueHas(talkPlayingLibUrl)) waitQ--;
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
                    if (talkQueue != null && talkQueueHas(u42)) continue;
                    if (talkPlayingUrl != null && talkPlayingUrl.length() > 0
                            && SiteHttp.sameTalk(u42, talkPlayingUrl)) continue;
                    // v11.76: foreign line that is the library-sounding talk skips too.
                    if (talkPlayingLibUrl != null && talkPlayingLibUrl.length() > 0
                            && SiteHttp.sameTalk(u42, talkPlayingLibUrl)) continue;
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
                                && SiteHttp.sameTalk(e64.url, talkPlayingUrl)) continue;
                        fxOrd64++;
                        if (SiteHttp.sameTalk(e64.url, fxHead.url)) { fxFound64 = true; break; }
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
                if (ft64 != null && ft64.contains(SiteHttp.identityUrl(fxHead.url))) fxMark += " ★";
            } catch (Exception ignored) {}
            try {
                java.util.Set<String> pl64 = getSharedPreferences("dhamma", MODE_PRIVATE)
                        .getStringSet("playedTalks", null);
                if (pl64 != null && pl64.contains(SiteHttp.identityUrl(fxHead.url))) fxMark += " ✓";
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
                if (ft2 != null) nxFav = ft2.contains(SiteHttp.identityUrl(nx.url));
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
                        if (talkPlayingUrl != null && SiteHttp.sameTalk(qu, talkPlayingUrl)) continue;
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
                if (pl2 != null) nxPlayed = pl2.contains(SiteHttp.identityUrl(nx.url));
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
        // v15.31 (slice 1824): snapshot the header text BEFORE the
        // rewrite — the v14.56 tick arm's baseline, now owned here.
        String headerBefore31 = stripHeaderLivePct(ht.getText().toString());
        ht.setText(header);
        // v15.31 (slice 1824): the header melts on a structural
        // change — the v14.56 arm (slice 1713) moved in here from
        // the tick branch, so it fires on EVERY refresh, not just
        // tick-detected ones: a Play All / Shuffle tap, a long-press
        // menu action, a download landing, or a back-stack return
        // used to swap the counts / Up Next line in one frame while
        // the ▸ row (v14.55) and the sounding row (v14.48) melt
        // beside it — the tick arm could never catch those, because
        // the call sites paint the new text before the next tick
        // evaluation reads it. Only a structural change arms (see
        // stripHeaderLivePct — a pure ⏳ percent tick reads equal,
        // never re-fires), primed only (the first successful paint
        // adopts silently, never melting on top of the v6.04 open
        // pour), resumed only (talksResumed — a download landing
        // while this chooser sits paused paints silently and the
        // return reads the new text already in place), and only
        // while the header row sits settled (the open pour animates
        // the ROW's alpha/translation, so a melt landing mid-pour
        // would be cut short — a missed melt, never a fight; the
        // v14.58 drawer-header settled guard, restated for the row
        // that carries this title). Load landings and rotation
        // restores prime but never melt; theme dips rewrite no text
        // and read equal.
        if (headerFadePrimed && talksResumed
                && !stripHeaderLivePct(ht.getText().toString()).equals(headerBefore31)) {
            android.view.View headerRow31 = findViewById(R.id.talksHeaderRow);
            if (headerRow31 == null
                    || (headerRow31.getAlpha() >= 0.999f && Math.abs(headerRow31.getTranslationY()) <= 0.5f))
                Fx.fadeIn(ht, 250);
        }
        headerFadePrimed = true;
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
                if (SiteHttp.sameTalk(talks.get(i).url, liveUrl)) { idx = i; break; }
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
        // v13.90 (slice 1082): strip portrait sounding gold ring — the
        // frame's background is the shelf ring drawable (reused, no new
        // drawable) while a live session sounds, and nothing otherwise,
        // so the sounding sayadaw reads in a still screenshot on every
        // strip. The predicate is the strip's own live rule
        // (liveSession + liveHasTrack, the same gate the strip sync's
        // live branch uses) with the v3.99 sounding identity: paused
        // still rings, an ended talk never rings, and the resume strip
        // (dead session) never rings — the portrait ring is what tells
        // a live strip from a resume strip in a still, the nav_glass
        // gold rim being identical in both modes. Change-only on the
        // flag below, before the name-equality return, so a
        // live-to-dead transition with the same sayadaw still clears
        // the ring on the next 1s tick; the frame owns visibility now,
        // the photo inside stays visible. No new view except the
        // ring-carrier frame, no new drawable, no new key.
        android.view.View ring = findViewById(R.id.nowStripPhotoRing);
        boolean ringing = name != null && name.length() > 0
                && AudioService.liveSession && AudioService.liveHasTrack && !AudioService.liveEnded;
        if (ringing != lastTalksStripPhotoRing) {
            lastTalksStripPhotoRing = ringing;
            // v15.35 (slice 1848): the breath owns the outgoing
            // background's alpha — cancel it before the
            // background is replaced, or its animator would
            // keep writing to a detached drawable and the
            // restore below would land on the wrong drawable.
            // A no-op when no breath runs (first gold, fresh
            // instance); the restore puts the old drawable back
            // to full alpha for any later reuse.
            stopTalksStripPhotoRingBreath();
            if (ring != null) {
                // v14.16 (slice 1670): the ring crossfades in/out
                // instead of snapping — see Fx.crossfadeRing.
                Fx.crossfadeRing(ring, ringing ? R.drawable.shelf_ring_playing : 0);
            }
        }
        // v15.35 (slice 1848): arm the breath on a gold ring,
        // stop it anywhere else — the change block above fires
        // only on a state flip, so this unconditional arm is
        // the re-arm after an onPause cancel (a hidden view must
        // not keep animating, and an infinite animator would
        // pin the dead activity): onResume restarts the 1s tick,
        // which re-runs syncTalksStripPhoto on every tick while the
        // ring state never moved. A running breath or a pending
        // start is a no-op. The strip ring has no failed (red)
        // state — gold while sounding, nothing otherwise — so
        // the arm condition is the ringing predicate alone.
        if (ringing) armTalksStripPhotoRingBreath();
        else stopTalksStripPhotoRingBreath();
        if (name == null || name.length() == 0) {
            talksStripPhotoName = null;
            if (ring != null) ring.setVisibility(android.view.View.GONE);
            else pv.setVisibility(android.view.View.GONE);
            return;
        }
        if (ring != null) ring.setVisibility(android.view.View.VISIBLE);
        else pv.setVisibility(android.view.View.VISIBLE);
        if (name.equals(talksStripPhotoName)) return;
        boolean firstPhoto = talksStripPhotoName == null;
        talksStripPhotoName = name;
        String url = AudioService.stripPhotoUrlForSayadaw(TalksActivity.this, name);
        // v14.22 (slice 1676): the photo crossfades on a sayadaw
        // change instead of swapping — see Fx.crossfadeStripPhoto.
        Fx.crossfadeStripPhoto((CircleImageView) pv, url, firstPhoto, new Runnable() {
            @Override public void run() {
                ImageLoader.load(TalksActivity.this, url, name, (CircleImageView) pv);
            }
        });
    }

    // v15.35 (slice 1848): sounding strip ring breath — the
    // twin of the Main strip ring breath (v15.35, slice 1847),
    // the chooser ring breath (v15.34, slice 1845) and the
    // Room header ring breath (v15.35, slice 1846): the
    // chooser strip is the surface a listener stares at while
    // browsing one sayadaw's talks, and its gold ring only ever
    // crossfaded in and sat still while its talk played. The
    // ring now breathes 1.00->0.55 on a 900ms reverse loop, the
    // Library rhythm, while the session sounds — visible the
    // moment the user looks at the strip while browsing. The
    // photo is a child of the ring frame, so the loop writes
    // the background drawable's alpha, never the view's:
    // crossfadeRing paints with a TransitionDrawable and never
    // touches the view alpha, so the two never fight. The start
    // waits 260ms (the crossfade is 250ms) so the breath never
    // dims a fade-in, and the loop drops itself the moment the
    // background is replaced. The strip ring has no failed
    // (red) state — gold while sounding, nothing otherwise —
    // so the breath condition is the ringing predicate alone.
    private void armTalksStripPhotoRingBreath() {
        if (talksStripPhotoRingBreath != null || talksStripPhotoRingBreathPending) return;
        final android.view.View ring = findViewById(R.id.nowStripPhotoRing);
        if (ring == null) return;
        talksStripPhotoRingBreathPending = true;
        final int myGen = talksStripPhotoRingBreathGen;
        ring.postDelayed(new Runnable() {
            @Override public void run() {
                talksStripPhotoRingBreathPending = false;
                if (myGen != talksStripPhotoRingBreathGen) return;
                if (!lastTalksStripPhotoRing) return;
                android.graphics.drawable.Drawable bg = ring.getBackground();
                if (bg == null) return;
                talksStripPhotoRingBreathBg = bg;
                android.animation.ValueAnimator breath = android.animation.ValueAnimator.ofInt(255, 140);
                breath.setDuration(900);
                breath.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                breath.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                breath.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                        android.graphics.drawable.Drawable cur = talksStripPhotoRingBreathBg;
                        if (cur == null || ring.getBackground() != cur) {
                            // Background replaced (a crossfade) —
                            // drop the loop; the arm re-starts it
                            // on the new background if the ring
                            // is still gold. No gen bump: no
                            // delayed start can exist while a
                            // loop runs (the arm returns early).
                            talksStripPhotoRingBreath = null;
                            talksStripPhotoRingBreathBg = null;
                            a.cancel();
                            return;
                        }
                        cur.setAlpha(((Integer) a.getAnimatedValue()).intValue());
                    }
                });
                talksStripPhotoRingBreath = breath;
                breath.start();
            }
        }, 260);
    }

    private void stopTalksStripPhotoRingBreath() {
        if (talksStripPhotoRingBreath == null && !talksStripPhotoRingBreathPending) return;
        talksStripPhotoRingBreathGen++;
        talksStripPhotoRingBreathPending = false;
        if (talksStripPhotoRingBreath != null) talksStripPhotoRingBreath.cancel();
        talksStripPhotoRingBreath = null;
        if (talksStripPhotoRingBreathBg != null) {
            // v15.37 (slice 1882): ease out, never snap — the
            // ring glides back to opaque over 200ms (Fx
            // easeRingAlphaToOpaque), which always lands
            // before any re-arm's 260ms delayed start.
            android.graphics.drawable.Drawable stoppedBg = talksStripPhotoRingBreathBg;
            talksStripPhotoRingBreathBg = null;
            Fx.easeRingAlphaToOpaque(stoppedBg);
        }
    }

    // v14.71 (slice 1729): the Talks resume strip's title melts on a
    // structural change instead of snapping — the 1s stripTick re-runs
    // syncTalksStrip in resume mode, and the title carries the download
    // mark (stripResumeDownloadMark / the lib branch's inline check),
    // so a download landing while the chooser sits open flipped its ⏳ to ⬇
    // in one frame on the visible strip; a ★ gained or lost on a
    // favour/clear return flips the same way. The sub line carries no
    // mark (sayadaw + the static saved spot) and the ▶ glyph never
    // changes in resume mode, so the title is the only writer here.
    // Not fadeStripText: that helper is keyed on TALK IDENTITY (the
    // Room URL / library path), so a same-talk text change writes
    // instantly by design (v14.21) — the mark flip is exactly a
    // same-talk change, and routing the resume title through it would
    // melt nothing. The resume title has no live-ticking part (no
    // clock in the title; the sub's clock is the saved spot, which
    // never moves without a live session), so every change IS the
    // structure — the v14.65 skeleton rule, restated for the strip:
    // first resume sighting writes instantly (the 280ms slide-in
    // owns that entrance), steady ticks never touch the view, and a
    // change melts 250ms via Fx.fadeIn only while the view is
    // VISIBLE and settled (alpha >= 0.999f). A stale live dip is
    // cancelled first: the live branch's fadeStripText dip runs on
    // this same view, and a session dying inside its 90ms dip would
    // otherwise swap the stale live title over the resume text at
    // the dip end (its end-action only skips when the tag moved) —
    // the cancel listener restores alpha 1 and clears the tag, so
    // the resume write lands clean and the stale swap never fires.
    // The tracker resets where the strip leaves resume mode — the
    // live entry below (the live branch owns tx via fadeStripText
    // from then on) and both resume hide paths — so a later
    // re-appear with a different talk never melts under its own
    // slide-in. Rotation recreates the activity, so the tracker
    // resets there too. The live branch's stripTextLast tag is
    // untouched: fadeStripText keys on identity, not text, so a
    // resume write never desyncs its next live melt (v14.43 class).
    private String resumeStripTitle71 = null;
    private void meltResumeStripTitle71(android.widget.TextView tx, String want) {
        if (tx == null || want == null) return;
        Object aObj71 = tx.getTag(R.id.stripTextAnim);
        if (aObj71 instanceof android.animation.Animator) ((android.animation.Animator) aObj71).cancel();
        String prev71 = resumeStripTitle71;
        resumeStripTitle71 = want;
        if (prev71 == null) { tx.setText(want); return; }
        if (prev71.equals(want)) return;
        tx.setText(want);
        if (tx.getVisibility() == android.view.View.VISIBLE && tx.getAlpha() >= 0.999f) Fx.fadeIn(tx, 250);
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
        java.util.Map<String, Integer> marksDlProg44 = new java.util.HashMap<String, Integer>(talkDlProgress);
        java.util.ArrayList<String> marksQueue44 = new java.util.ArrayList<String>(talkQueue);
        refreshTalksQueuePreview(); // v4.87: tick head — every early return below still refreshes it
        // v14.97 (slice 1755): the changed ★/✓ set for the sync melt
        // — the Room drawer's v14.96 shape, restated for the chooser:
        // the tick already rebinds on a fav/played change (v7.44
        // marksChanged44), but the rebind is bare, so a ★ gained on
        // the notification card (or a ✓ banked by a finish in
        // another session) snapped onto its row in one frame while
        // the sounding row (v14.48), the ▸ row (v14.55), the stopped
        // row (v14.60) and the header (v14.56) melt beside it. The
        // diff is taken here, from the pre-refresh snapshots against
        // the refreshed caches, canonicalised through
        // SiteHttp.identityUrl (pure syncMarksChanged97) — a
        // spelling-only http/https split diffs to nothing and arms
        // nothing. Primed only (syncMarksPrimed97, set at the tail
        // of this same evaluation, like the sibling primes), so a
        // load / rotation restore never melts on top of the list
        // pour (v14.23). The menu path cannot double-arm: the menu
        // branches reload the mark caches synchronously
        // (refreshTalkRowMarks), so the next tick snapshots the new
        // state and sees no change (v10.88 / v14.75), and the return
        // path refreshes the caches in onResume before any tick
        // runs — the arm reads only what this tick itself found.
        java.util.Set<String> syncChanged97 = null;
        if (syncMarksPrimed97) {
            syncChanged97 = syncMarksChanged97(marksFav44, talkFavCache,
                    marksPlayed44, talkPlayedCache,
                    marksDlQueued44, talkDlQueuedCache);
        }
        boolean marksChanged44 = !marksFav44.equals(talkFavCache)
                || !marksPlayed44.equals(talkPlayedCache)
                || !marksDlQueued44.equals(talkDlQueuedCache)
                || !marksDl44.equals(talkDlCache)
                || !marksDlProg44.equals(talkDlProgress)
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
        // v14.48 (slice 1705): arm the newly-sounding row's melt
        // BEFORE the trackers below adopt the fresh URLs — the
        // v3.24 drawer shape, restated for the chooser. Only a
        // sounding-URL change to a NEW non-empty URL arms it: a
        // favourite / played / download / queue mark change
        // (marksChanged44) rebinds the same rows with no new
        // sounding talk, and an end / failure / buffering flip
        // only strips or swaps the mark on the row already
        // sounding, so neither melts anything; a URL changing
        // to empty (stopped) has no row to melt. Room wins over
        // library when both changed in one tick (the two URLs
        // are mutually exclusive by refreshQueueMarks — lastKind
        // room vs lib, both reset to "" each refresh — so this
        // only ever picks the one live session). Unprimed first
        // evaluation (load / rotation restore) adopts silently
        // — the entrance pour (v14.23) already carries the
        // sounding row — and primed is set after the branch, so
        // a change found on the very next tick melts.
        String soundTarget48 = "";
        if (talkSoundPrimed
                && !talkPlayingUrl.equals(talkRowPlayingUrl == null ? "" : talkRowPlayingUrl)
                && talkPlayingUrl.length() > 0) {
            soundTarget48 = talkPlayingUrl;
        } else if (talkSoundPrimed
                && !talkPlayingLibUrl.equals(talkRowPlayingLibUrl == null ? "" : talkRowPlayingLibUrl)
                && talkPlayingLibUrl.length() > 0) {
            soundTarget48 = talkPlayingLibUrl;
        }
        // v14.60 (slice 1717): arm the departing row's melt BEFORE
        // the trackers below adopt the fresh URLs — the v14.48
        // shape, restated for the row that just STOPPED sounding.
        // Only a sounding-URL change AWAY from a non-empty old URL
        // arms it: the old row loses its ▶ + gold and gains ✓ + the
        // listened dim + a filled hairline on this same rebind
        // (played cache refreshed on the tick path, v7.44; the
        // service banks the played mark at onCompletion, v7.21),
        // so it used to snap its marks in one frame while the new
        // sounding row melted beside it (v14.48). A stop arms too
        // (fresh URL empty — the old row still loses ▶ + gold);
        // a same-talk replay (repeat-one) arms nothing (fresh ==
        // old); a marks-only change or an end/failure/buffering
        // flip without a URL change arms nothing (the v14.48
        // deliberate rule — the sounding row's own mark swap is a
        // different class). Room wins over library, like
        // soundTarget48 above. Unprimed first evaluation adopts
        // silently — the entrance pour (v14.23) already carries
        // the row.
        String prevSoundTarget60 = "";
        if (talkSoundPrimed
                && talkRowPlayingUrl != null && talkRowPlayingUrl.length() > 0
                && !talkPlayingUrl.equals(talkRowPlayingUrl)) {
            prevSoundTarget60 = talkRowPlayingUrl;
        } else if (talkSoundPrimed
                && talkRowPlayingLibUrl != null && talkRowPlayingLibUrl.length() > 0
                && !talkPlayingLibUrl.equals(talkRowPlayingLibUrl)) {
            prevSoundTarget60 = talkRowPlayingLibUrl;
        }
        if (adapter != null && (marksChanged44 || !talkPlayingUrl.equals(talkRowPlayingUrl == null ? "" : talkRowPlayingUrl)
                || !talkPlayingLibUrl.equals(talkRowPlayingLibUrl == null ? "" : talkRowPlayingLibUrl)
                || AudioService.liveEnded != talkRowEnded
                || AudioService.liveTrackError != talkRowFailed
                || AudioService.liveBuffering != talkRowBuffering
                || AudioService.livePlaying != talkRowPlaying)) {
            talkRowPlayingUrl = talkPlayingUrl;
            talkRowPlayingLibUrl = talkPlayingLibUrl;
            talkRowEnded = AudioService.liveEnded;
            talkRowFailed = AudioService.liveTrackError;
            talkRowBuffering = AudioService.liveBuffering;
            talkRowPlaying = AudioService.livePlaying;
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
            // v14.55 (slice 1712): snapshot the Up Next URL the rows
            // are currently bound against — BEFORE the refresh below
            // rewrites it. This is the arm's baseline: the field
            // always holds the last value a rebind could have bound
            // (menu actions rebind with it at once), so the compare
            // below can never mistake an already-▸ row for a new one.
            String upNextBefore55 = upNextRowUrl;
            // v15.31 (slice 1824): no header snapshot here any more
            // — the header's melt arm moved into refreshFavHeader
            // itself (its own before/after baseline, same
            // stripHeaderLivePct strip), so the refresh below melts
            // on a structural change whatever called it. The
            // upNextBefore55 snapshot beside it stays: the ▸ row arm
            // still compares against the last-bound value here.
            // v13.85 (slice 1077): the refresh runs BEFORE the notify below — refreshFavHeader also refreshes upNextRowUrl, and the rebind reads it, so a notify-first order would bind every row against the previous next talk until the next change tick.
            try { refreshFavHeader(); } catch (Exception ignored) {}
            // v14.55 (slice 1712): arm the new Up Next row's melt —
            // only a change to a NEW non-empty URL, primed only. A
            // marks-only change rebinds the same rows with no new
            // imminent talk, an end / failure / buffering flip only
            // strips or swaps the mark on the row already sounding,
            // and a fresh value of "" (foreign head waits, nothing
            // resolves) has no row to melt — none of them arms.
            // The snapshot above is the last-bound value, so a ▸
            // move that a menu action already bound (queue ⋮, move
            // drop) reads equal here and never re-melts.
            String upNextTarget55 = "";
            if (upNextPrimed && upNextRowUrl != null && upNextRowUrl.length() > 0
                    && !upNextRowUrl.equals(upNextBefore55 == null ? "" : upNextBefore55)) {
                upNextTarget55 = upNextRowUrl;
            }
            // v15.31 (slice 1824): the header's melt already fired
            // inside refreshFavHeader above when the rewrite changed
            // the structure — no second arm here (a second fadeIn on
            // the same tick would only restart the same fade).
            adapter.notifyDataSetChanged();
            if (soundTarget48.length() > 0) { soundingFadeUrl = soundTarget48; fadeSoundingTalkRow(); }
            if (prevSoundTarget60.length() > 0) { prevSoundFadeUrl = prevSoundTarget60; fadePrevSoundTalkRow(); }
            if (upNextTarget55.length() > 0) { upNextFadeUrl = upNextTarget55; fadeUpNextTalkRow(); }
            if (syncChanged97 != null && !syncChanged97.isEmpty()) { syncMarksFadeUrls97 = syncChanged97; fadeSyncMarkedTalkRows97(); }
        }
        // v14.48 (slice 1705): first tick evaluation adopts the
        // trackers silently — the arm above only ever reads the
        // OLD primed value, so a load / rotation restore never
        // melts on top of the entrance pour (v14.23).
        talkSoundPrimed = true;
        // v14.55 (slice 1712): same first-tick adoption for the Up
        // Next arm — a load / rotation restore never melts the ▸
        // row on top of the entrance pour (v14.23).
        upNextPrimed = true;
        // v15.31 (slice 1824): the header arm's first-paint
        // adoption moved into refreshFavHeader itself — the tick no
        // longer primes it.
        // v14.97 (slice 1755): same first-tick adoption for the
        // sync-marks arm — a load / rotation restore never melts
        // rows on top of the entrance pour (v14.23).
        syncMarksPrimed97 = true;
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
                if (SiteHttp.sameTalk(rSavedUrl, rUrl)) rMatch = true;
                else {
                    try {
                        org.json.JSONObject map = new org.json.JSONObject(rsp.getString("dlMap", "{}"));
                        String fn = map.optString(rUrl, "");
                        // v13.62 (F-1033-B-3): legacy http dlMap key
                        // misses the raw lookup — try the identity form.
                        if (fn.length() == 0) fn = map.optString(SiteHttp.identityUrl(rUrl), "");
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
                // v14.01 (Settings F1 library half, all four strips):
                // a 0-byte file is not downloaded — the strip used to
                // claim ⬇ for a dead stub. The in-progress exception
                // keeps the v11.58 order (a live job reads ⏳ before
                // any exists/length check, like stripResumeDownloadMark).
                if (lPath.length() > 0 && rPos > 5000 && rSavedUrl.length() > 0
                        && new java.io.File(lPath).isFile()
                        && (new java.io.File(lPath).length() > 0
                            || Fx.stripInProgressDlFilenames(TalksActivity.this, rsp).contains(new java.io.File(lPath).getName()))) {
                    if (rSavedUrl.equals(lPath)) lMatch = true;
                    else {
                        try {
                            org.json.JSONObject map = new org.json.JSONObject(rsp.getString("dlMap", "{}"));
                            java.util.Iterator<String> keys = map.keys();
                            String fn = new java.io.File(lPath).getName();
                            while (keys.hasNext()) {
                                String u = keys.next();
                                if (SiteHttp.sameTalk(u, rSavedUrl) && fn.equals(map.optString(u, ""))) { lMatch = true; break; }
                            }
                        } catch (Exception ignored) {}
                    }
                }
                if (lTitle.length() == 0 || !lMatch) {
                    syncTalksStripPhoto(null);
                    resumeStripTitle71 = null;
                    Fx.hideStrip(strip);
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
                        if (nClaim49 == 1 && onlyClaim49 != null && ft.contains(SiteHttp.identityUrl(onlyClaim49))) lFav = true;
                    }
                } catch (Exception ignored) {}
                int lSec = rPos / 1000;
                String lTime = Fx.stripResumeTime(lSec);
                // v14.03 (slice 1098): same total + bar as the room branch —
                // legs are the saved URL, the file path, then the dlMap
                // single-claimant stream URL (a file downloaded after the
                // listen has no path-form duration yet).
                int lDur = Fx.stripResumeDuration(TalksActivity.this, rsp, rSavedUrl, lPath);
                String lClock = Fx.stripClockSuffix(rPos, lDur, Prefs.stripClockRemaining(TalksActivity.this));
                String lClockTxt = lClock.length() > 0 ? lClock : " · " + lTime;
                // v8.52: Main's v7.94 resume mark, mirrored — the proven file is offline, like Main's lib branch.
                String want71 = "↺ " + lTitle + (lFav ? " ★" : "") + (Fx.stripInProgressDlFilenames(TalksActivity.this, rsp).contains(new java.io.File(lPath).getName()) ? " ⏳" : " ⬇");
                meltResumeStripTitle71(tx, want71);
                // v5.46: a dead library strip names its recorded
                // sayadaw like the live strip since v5.45, in the
                // Room strip's "name · mm:ss မှ ဆက်နားထောင်ရန်"
                // shape; an unrecorded download keeps today's
                // generic line, never a guessed name.
                String libSd = Fx.stripLibSayadaw(TalksActivity.this, rsp);
                if (sb != null) sb.setText(libSd != null ? libSd + lClockTxt + Lang.t(TalksActivity.this, "resume_from") : Lang.t(TalksActivity.this, "downloaded_talk") + lClockTxt + Lang.t(TalksActivity.this, "resume_from"));
                if (tg != null) tg.setText("▶");
                if (nx != null) Fx.crossfadeStripBtn(nx, false);
                // v7.31: Previous hides with Next in resume mode
                // (Main's mirror fix) — stepping back needs a
                // live track exactly like advancing does, and
                // this handler had no resume branch, so a ⏮ tap
                // on a ↺ strip ACTION_PREV-ed into a different
                // talk instead of resuming this one.
                android.view.View pv0 = findViewById(R.id.nowStripPrev);
                if (pv0 != null) Fx.crossfadeStripBtn(pv0, false);
                android.widget.ProgressBar lPb = findViewById(R.id.nowStripProgress);
                if (lPb != null) Fx.stripResumeProgress(lPb, rPos, lDur);
                talksResumeStripMode = true;
                syncTalksStripPhoto(libSd);
                Fx.showStrip(strip);
                return;
            }
            if (!"room".equals(rKind) || rName.length() == 0 || rTitle.length() == 0
                    || rPage.length() == 0 || rPos <= 5000 || !rMatch) {
                syncTalksStripPhoto(null);
                resumeStripTitle71 = null;
                Fx.hideStrip(strip);
                return;
            }
            boolean rFav = false;
            try {
                java.util.Set<String> ft = rsp.getStringSet("favTalks", null);
                if (ft != null) rFav = ft.contains(rUrl);
            } catch (Exception ignored) {}
            int rSec = rPos / 1000;
            String rTime = Fx.stripResumeTime(rSec);
            // v14.03 (slice 1098): the ↺ strip gains its total — the stored
            // duration (v13.96) turns the position-only sub-line into
            // "pos / total" (or "−left" in remaining mode, the live strip's
            // own clock rule) and fills the bar; unknown duration keeps
            // today's exact line and empty bar.
            int rDur = Fx.stripResumeDuration(TalksActivity.this, rsp, rUrl, null);
            String rClock = Fx.stripClockSuffix(rPos, rDur, Prefs.stripClockRemaining(TalksActivity.this));
            // v8.52: Main's v7.94 resume download mark, mirrored — a ↺ strip here never said whether resuming streams or plays offline.
            String want71 = "↺ " + rTitle + (rFav ? " ★" : "") + Fx.stripResumeDownloadMark(TalksActivity.this, rsp, rUrl);
            meltResumeStripTitle71(tx, want71);
            if (sb != null) sb.setText(rName + (rClock.length() > 0 ? rClock : " · " + rTime) + Lang.t(TalksActivity.this, "resume_from"));
            if (tg != null) tg.setText("▶");
            if (nx != null) Fx.crossfadeStripBtn(nx, false);
            // v7.31: same Previous hide as the library resume
            // branch above — a ↺ strip never shows ⏮.
            android.view.View pv0 = findViewById(R.id.nowStripPrev);
            if (pv0 != null) Fx.crossfadeStripBtn(pv0, false);
            android.widget.ProgressBar rPb = findViewById(R.id.nowStripProgress);
            if (rPb != null) Fx.stripResumeProgress(rPb, rPos, rDur);
            talksResumeStripMode = true;
            syncTalksStripPhoto(rName);
            Fx.showStrip(strip);
            return;
        }
        talksResumeStripMode = false;
        resumeStripTitle71 = null; // v14.71: live branch owns tx via fadeStripText from here
        // v15.26 (slice 1791): the strip Next/Prev buttons melt in/out instead of popping on the resume <-> live mode flip — both buttons ride Fx.crossfadeStripBtn from here (the two resume branches above hide through the same helper); the toggle glyph + title stay instant per v14.16.
        if (nx != null) Fx.crossfadeStripBtn(nx, true);
        // v7.31: Previous returns with Next on a live session.
        android.view.View pv0 = findViewById(R.id.nowStripPrev);
        if (pv0 != null) Fx.crossfadeStripBtn(pv0, true);
        android.content.SharedPreferences sp =
                getSharedPreferences("dhamma", MODE_PRIVATE);
        String kind = sp.getString("lastKind", "");
        // v13.94 (F-920 F-NEW-5): the strip names a stream still loading or
        // already failed — until now both read exactly like a paused talk
        // (▶ + title). Buffering borrows the Room's own word
        // (notif_state_buffering, the drawer-row / status-line prefix); a
        // failure borrows the Settings ⚠ mark. Error wins: the two states
        // are exclusive by construction (isBuffering() requires !trackError)
        // and both flags clear on every new play, so neither sticks to the
        // next talk. Resume mode is untouched (no live track there).
        String statePre94 = AudioService.liveTrackError ? "⚠ "
                : (AudioService.liveBuffering ? Lang.t(TalksActivity.this, "notif_state_buffering") + " " : "");
        String text = null;
        String subText = null;
        if ("room".equals(kind)) {
            String name = sp.getString("lastName", "");
            String title = sp.getString("lastTitle", "");
            if (name.length() > 0 && title.length() > 0) {
                text = "🎵 " + statePre94 + title;
                subText = name;
            }
        } else if ("lib".equals(kind)) {
            String title = sp.getString("lastLibTitle", "");
            if (title.length() > 0) {
                text = "🎵 " + statePre94 + title;
                // v5.45: a library session names its sayadaw when
                // one was recorded at download time, like a Room
                // session does above; an unrecorded download keeps
                // today's generic line, never a guessed name.
                String libSd = Fx.stripLibSayadaw(TalksActivity.this, sp);
                subText = libSd != null ? libSd : Lang.t(TalksActivity.this, "downloaded_talk");
            }
        }
        if (text == null) { syncTalksStripPhoto(null); Fx.hideStrip(strip); return; }
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
                        if (nClaim49 == 1 && onlyClaim49 != null && ft.contains(SiteHttp.identityUrl(onlyClaim49))) stripFav = true;
                    }
                }
            }
        } catch (Exception ignored) {}
        if (stripFav) text += " ★";
        // v14.02 (slice 1097): the live strip names a downloaded talk —
        // the Room title has carried ⬇ since v1.57 and the resume strips
        // since v13.66, but a live strip never did, so a sounding
        // downloaded talk read exactly like a stream. Room kind reuses
        // Fx.stripResumeDownloadMark (dlMap + length>0 gate, the same
        // helper the resume branch uses, in-progress-first so a partial
        // file never reads ⬇); only its ⬇ return is kept — the ⏳ return
        // would duplicate the app-wide " ⏳ N" suffix below. Library
        // kind is downloaded by definition; the file-exists guard keeps
        // a stale path from claiming the mark. Suffix, after ★, the
        // resume branches' own order.
        if ("room".equals(kind)) {
            String dlMarkLive = Fx.stripResumeDownloadMark(TalksActivity.this, sp, sp.getString("lastUrl", ""));
            if (" ⬇".equals(dlMarkLive)) text += dlMarkLive;
        } else if ("lib".equals(kind)) {
            String lpLive = sp.getString("lastLibPath", "");
            if (lpLive.length() > 0) {
                java.io.File lfLive = new java.io.File(lpLive);
                if (lfLive.isFile() && lfLive.length() > 0) text += " ⬇";
            }
        }
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
                                || (src != null && dlUrls != null && (dlUrls.contains(src) || dlUrls.contains(SiteHttp.identityUrl(src))))) progN++;
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
        // v14.21 (slice 1675): the strip title/sub fade on a talk
        // change, keyed on the talk itself (Room URL / library
        // path) — the composed strings tick every second (sleep
        // countdown on the title, live clock on the sub), so the
        // text can never be the key.
        String stripTalkId = "room".equals(kind) ? sp.getString("lastUrl", "") : sp.getString("lastLibPath", "");
        if (tx != null) Fx.fadeStripText(tx, text, stripTalkId);
        // v5.32: live clock on the strip — sayadaw name + elapsed/total.
        android.widget.ProgressBar pb = findViewById(R.id.nowStripProgress);
        if (subText != null) subText += Fx.stripClockSuffixForStrip(pb, AudioService.livePositionMs, AudioService.liveDurationMs, Prefs.stripClockRemaining(TalksActivity.this));
        if (sb != null && subText != null) Fx.fadeStripText(sb, subText, stripTalkId);
        Fx.syncStripProgress(pb); // v7.93: skips while a mini-seek drag is in flight
        if (tg != null) Fx.flipStripGlyph(tg, AudioService.livePlaying);
        // v6.01: live strip's portrait — Room names lastName, library
        // names its recorded sayadaw (Fx.stripLibSayadaw, null when
        // unrecorded); both resolve through syncTalksStripPhoto's
        // exact-name catalog match, never a guessed face.
        syncTalksStripPhoto("room".equals(kind) ? sp.getString("lastName", "")
                : ("lib".equals(kind) ? Fx.stripLibSayadaw(TalksActivity.this, sp) : null));
        Fx.showStrip(strip);
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
            Fx.stripSwipe(nowStrip);
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
            // v13.82 (Talks F1): the flip is a no-op there — the sub
            // line is only a clock control while a live session is
            // showing (mirror of Main's v13.12 M7 guard).
            android.widget.TextView stripSub = findViewById(R.id.nowStripSub);
            if (stripSub != null) {
                stripSub.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        // v13.82 (Talks F1, sim1412): the flip is a live-mode
                        // affordance only — a resume strip shows no
                        // clock, so a flip there was invisible at tap
                        // time and silently re-timed the next live
                        // session. In resume mode (same live recheck
                        // as the dismiss branch, v12.57) the tap is a
                        // deliberate no-op instead of a silent mutation.
                        // Mirror of Main's v13.12 M7 guard.
                        if (talksResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) return;
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
                    // v15.29 (slice 1815): shared-element open — the strip
                    // photo grows into the Room header portrait, but only
                    // when no Room is alive in the stack: with CLEAR_TOP
                    // the tap re-delivers into the existing Room via
                    // onNewIntent, and no transition runs for a
                    // re-delivery, so that path keeps the plain nav fade
                    // (RoomActivity.roomInStack). A fresh Room takes the
                    // shared open; Fx.goShared falls back to Fx.go
                    // itself when the photo is hidden or not laid out.
                    if (RoomActivity.roomInStack()) Fx.go(TalksActivity.this, i);
                    else Fx.goShared(TalksActivity.this, i, findViewById(R.id.nowStripPhoto));
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
                        // v13.28: same as Downloaded's dismiss — one resume spot cleared,
                        // not the history; toast resume_cleared (Main's key), not
                        // hist_cleared (Settings' real clear-history key).
                        android.widget.Toast.makeText(TalksActivity.this, Lang.t(TalksActivity.this, "resume_cleared"), android.widget.Toast.LENGTH_SHORT).show();
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
                    // v15.31 (slice 1823): shared-element open — the strip photo grows into the Room header portrait (v15.29, slice 1815), like the strip tap on this screen: this control used to slide plain while the strip tap beside it grew. Only when no Room is alive in the stack (RoomActivity.roomInStack): with CLEAR_TOP the tap re-delivers into the existing Room via onNewIntent and no transition runs, so that path keeps the plain nav fade; a fresh Room takes the shared open. Fx.goShared falls back to Fx.go itself when the photo is hidden or not laid out. Intent, flags, action and extras untouched.
                    if (RoomActivity.roomInStack()) Fx.go(TalksActivity.this, i);
                    else Fx.goShared(TalksActivity.this, i, findViewById(R.id.nowStripPhoto));
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
                    // v15.31 (slice 1823): shared-element open — the strip photo grows into the Room header portrait (v15.29, slice 1815), like the strip tap on this screen: this control used to slide plain while the strip tap beside it grew. Only when no Room is alive in the stack (RoomActivity.roomInStack): with CLEAR_TOP the tap re-delivers into the existing Room via onNewIntent and no transition runs, so that path keeps the plain nav fade; a fresh Room takes the shared open. Fx.goShared falls back to Fx.go itself when the photo is hidden or not laid out. Intent, flags, action and extras untouched.
                    if (RoomActivity.roomInStack()) Fx.go(TalksActivity.this, i);
                    else Fx.goShared(TalksActivity.this, i, findViewById(R.id.nowStripPhoto));
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
        // v13.35: removed the unused row local (findViewById result never read).
        searchBox = (android.widget.EditText) findViewById(R.id.talkSearch);
        if (searchBox == null) return;
        searchBox.setHint(Lang.t(TalksActivity.this, "talks_search_hint"));
        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                searchQuery = s.toString().trim();
                // v15.18 (slice 1781): capture BEFORE rebuildSearch + the rebind below move the rows; a rotation restore never arms the glide (see captureTalkSetGlide).
                final java.util.HashMap<Integer, Integer> tops82 = restoringTalkSearch ? null : captureTalkSetGlide();
                rebuildSearch();
                maybePourOnListChange(); // v14.23: rows pour when the searched set actually changes
                if (adapter != null) adapter.notifyDataSetChanged();
                postTalkSetGlide(tops82);
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
        // v15.28 (slice 1800): the clear x had no press state at all — its listener was a bare clearSearch() with no morph, no tap and no press, while its Library twin (btnClearDlSearch) morphs (v2.41) and the chooser's own chips / Play All / Shuffle / rows all carry Fx.press. Fx.morph (dip 0.86 over 70ms, spring back over 220ms) renders fully here — this tap leaves the chooser open while the list re-pours, unlike the dev-profile x (v15.27 piece 2/5, Fx.press, because that tap dismisses the dialog). No non-empty guard: the x is GONE with an empty query (v14.44), so a tap can only land on a real query. The morph animates scale only; clearSearch's fade-out of the x (v14.44, alpha only) runs on a separate channel, so the x squashes away as it fades. clearSearch() itself is untouched.
        if (clear != null) clear.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { Fx.morph(v); clearSearch(); }
        });
        // v13.95 (slice 1090): wire the visible filter chips — one
        // tap sets the mode directly (setTalkFilterMode, the cycle's
        // own body); the cycling button is gone. The chips paint
        // from refreshTalkFilterChips below, and setTalkFilterMode
        // repaints them on every later change. A filtered view
        // applies to the full list, and the rows pour when the
        // filtered set actually changes (v14.23, same set-changed
        // rule as the search watcher).
        chipTalkAll = findViewById(R.id.chipTalkAll);
        chipTalkStar = findViewById(R.id.chipTalkStar);
        chipTalkNew = findViewById(R.id.chipTalkNew);
        chipTalkDl = findViewById(R.id.chipTalkDl);
        if (chipTalkAll != null) chipTalkAll.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                Fx.tap(TalksActivity.this);
                setTalkFilterMode(0);
            }
        });
        if (chipTalkStar != null) chipTalkStar.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                Fx.tap(TalksActivity.this);
                setTalkFilterMode(1);
            }
        });
        if (chipTalkNew != null) chipTalkNew.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                Fx.tap(TalksActivity.this);
                setTalkFilterMode(2);
            }
        });
        if (chipTalkDl != null) chipTalkDl.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                Fx.tap(TalksActivity.this);
                setTalkFilterMode(3);
            }
        });
        if (chipTalkAll != null) Fx.press(chipTalkAll);
        if (chipTalkStar != null) Fx.press(chipTalkStar);
        if (chipTalkNew != null) Fx.press(chipTalkNew);
        if (chipTalkDl != null) Fx.press(chipTalkDl);
        refreshTalkFilterChips();
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
        // the first return. Same step as the applyTheme() block above.
        // v13.95: the chips are sized by their own painter, not here.
        int wf = Prefs.fontSize(TalksActivity.this);
        searchBox.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                wf == 0 ? 14 : (wf == 1 ? 16 : 20));
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
            // v14.47 (slice 1704): row-alpha hygiene, the Room
            // drawer v3.45 mirror — the landed-row fade above runs
            // on the row view itself, so a rebind landing mid-fade
            // (tick percent rebind, return theme-dip swap, user
            // filter/search change) used to cancel the animator
            // and strand the row half-transparent: this getView
            // never touched row alpha. A rebind now restores alpha
            // 1 with the scale, so a cut-short fade just ends
            // fully visible. The pour (v14.23) is a view Animation
            // and the press squash (v2.08/v3.90) is scale-only, so
            // neither reads this property; the listened title dim
            // lives on titleView, untouched.
            v.setAlpha(1f);
            // v1.96: the row number stayed at a fixed 14sp while the
            // title beside it followed the font size — on ကြီး the
            // numbers read a size smaller than the titles they count.
            // v13.85 (Talks L3, M9/F5 row sweep — main-talks-recheck-1355): the row views
            // were dereferenced bare, like the onCreate sites v13.36 closed — a renamed
            // or missing row id NPEd the whole list bind. Same shape as Downloaded
            // v13.38 / Room v13.43: each use is null-guarded, so a missing id leaves its
            // view unbound instead of failing the bind. Both ids exist in row_talk.xml
            // today, so no live behaviour changes; cardInner was already guarded.
            TextView indexView = v.findViewById(R.id.index);
            // v12.85: the row number is the list number (the
            // queue-only view is deleted); a queued row still
            // carries its ◈N mark below.
            if (indexView != null) indexView.setText((talkIdx + 1) + ".");
            int idxF = cachedFont;
            if (indexView != null) indexView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, idxF == 0 ? 12 : (idxF == 1 ? 14 : 16));
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
                    && SiteHttp.sameTalk(talks.get(talkIdx).url, talkPlayingUrl))
                    && !(talkPlayingLibUrl.length() > 0
                    && SiteHttp.sameTalk(talks.get(talkIdx).url, talkPlayingLibUrl))) {
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
                        && !SiteHttp.sameTalk(talkPlayingLibUrl, talkPlayingUrl)) {
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
                    && SiteHttp.sameTalk(talks.get(talkIdx).url, talkPlayingUrl);
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
                    && SiteHttp.sameTalk(talks.get(talkIdx).url, talkPlayingLibUrl);
            // v13.97 (slice 1092): listened-progress hairline
            // fraction for this row — 0 = hidden. Set inside the
            // resume block below (same gate as the ↺ mark: never
            // the playing row, whose live position is on the
            // strip), painted after it.
            float hairFrac = 0f;
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
                // v13.97: hairline numerator is rp above; the
                // denominator comes from the per-load duration
                // cache — stream form first, then the downloaded
                // path form (same two legs as rp: a duration
                // written while streaming has no path form when
                // the file was downloaded after the listen, and
                // a duration written from the library has no
                // stream form when dlMap never claimed the file).
                // Hidden when the talk was never meaningfully
                // heard (rp <= 5000) or no duration was ever
                // stored; the fraction clamps to 1 and never
                // below a 3% sliver, so a barely-started talk
                // still reads as a visible gold dash.
                if (rp > 5000) {
                    Integer durI = talkDurCache.get(rUrl);
                    if (durI == null && talkDlCache.contains(rUrl)) {
                        String dlFn = talkDlFileCache.get(rUrl);
                        if (dlFn != null) {
                            String dlPath = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma/" + dlFn).getAbsolutePath();
                            durI = talkDurCache.get(dlPath);
                        }
                    }
                    if (durI != null && durI.intValue() > 0) {
                        hairFrac = Math.min(1f, (float) rp / (float) durI.intValue());
                        if (hairFrac < 0.03f) hairFrac = 0.03f;
                    }
                }
                // v15.43 (slice 1928): a live download paints its
                // own progress on this row's gold hairline — the
                // download fraction (talkDlProgress, refreshed
                // every 1s tick, rebind on change) wins over the
                // listened fraction while the job is live, so the
                // row fills beside its "⏳ 42%" mark; no percent
                // (unknown total) leaves the listened line
                // untouched, and the moment the file lands the ⬇
                // mark takes over and the line returns to the
                // listened fraction. Same gate as the listened
                // line above: never the playing row (the strip
                // owns its live state). The melt (v15.25) and
                // the instant weight split below are untouched.
                hairFrac = Fx.dlHairFrac(talkDlQueuedCache.contains(rUrl),
                        talkDlProgress.get(SiteHttp.identityUrl(rUrl)), hairFrac);
            }
            // v13.97 (slice 1092): paint the hairline — reset on
            // every bind, like the alpha/card/wash below, so a
            // recycled row can never keep the previous talk's
            // fraction; the track is GONE unless this bind set a
            // fraction above. The fill/space weights split the
            // track 0dp-wide by fraction (no measuring, no post
            // pass — the ListView recycle is safe).
            android.view.View hairTrack = v.findViewById(R.id.hairTrack);
            android.view.View hairFill = v.findViewById(R.id.hairFill);
            android.view.View hairSpace = v.findViewById(R.id.hairSpace);
            // v15.25 (slice 1788): the hairline melts in/out instead of popping — the v14.31 card-crossfade fix, now on the gold line: the 1s tick rebinds exactly when a talk ends, stops or auto-advances, and getView swapped the track in one frame (setVisibility), so a finished talk's line flashed in while the gold card beside it crossfades (v15.24). Fx.crossfadeHairline (250ms, DecelerateInterpolator, recorded shown state, instant seed on a fresh view) owns the track from here; the fill/space weight split below stays an instant layout update (a progress value, not an appearance change), and the ▶/✓ marks + title dim stay instant per the v14.16 settled decision.
            if (hairTrack != null) Fx.crossfadeHairline(hairTrack, hairFrac > 0f);
            if (hairFrac > 0f && hairFill != null && hairSpace != null) {
                hairFill.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, hairFrac));
                hairSpace.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f - hairFrac));
            }
            // v5.49: drawer marks on chooser rows (see the cache
            // comment above refreshTalkRowMarks) — ★/⬇/⏳/✓ in
            // drawer order, from the caches only, never per-bind
            // prefs or file reads. ⬇ wins over ⏳, like the
            // drawer (a saved talk is not still downloading).
            String rowUrl = talks.get(talkIdx).url;
            String favMark = talkFavCache.contains(SiteHttp.identityUrl(rowUrl)) ? "★ " : "";
            // v14.08 (slice 1103): the row names its live download percent — the Room drawer rows (v4.24), the Room title (v4.27) and the Downloaded rows (v4.27) all read "⏳ 42%", but this row read a bare ⏳ until the file landed. The lookup is by identity URL (the http retry form of a queued https URL still matches, v13.59 rule); an unknown total keeps the bare ⏳ (Fx.dlRowMark), and ⬇ still wins over ⏳.
            String offMark = Fx.dlRowMark(talkDlCache.contains(rowUrl), talkDlQueuedCache.contains(rowUrl),
                    talkDlProgress.get(SiteHttp.identityUrl(rowUrl)));
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
            // v13.83 (slice 1075): one listened boolean for the ✓ mark
            // and the dim below, so the two can never disagree about
            // which rows count as listened — same gate as before
            // (played, never the playing row, Room or library form).
            // v14.84 (slice 1742): the cache read compares the
            // canonical identity — the cache itself stays raw
            // (the v7.55 .equals detector), only the query
            // changes, so a rebind can never strand the mark.
            boolean rowListened = !rowPlaying && !rowPlayingLib && talkPlayedCache.contains(SiteHttp.identityUrl(rowUrl));
            String playedMark = rowListened ? "✓ " : "";
            // v15.41 (slice 1920): newly-added talk dot — a talk
            // that arrived since the last visit wears a ● before
            // its title (Spotify's new-episode dot, in this app's
            // text-mark language) until it is started (a non-empty
            // resumeMark means a saved spot exists — starting a
            // talk clears its dot on the next tick rebind) or
            // finished (playedMark); never on the playing row,
            // whose ▶ already owns its emphasis. Title-coloured
            // like every other mark — no colour interpolation,
            // no label fade (v14.16 idiom).
            String newMark = (!rowPlaying && !rowPlayingLib && !rowListened && resumeMark.length() == 0
                    && newTalkUrls != null && newTalkUrls.contains(SiteHttp.identityUrl(rowUrl))) ? "● " : "";
            if (titleView != null) titleView.setText((held ? "✋ " : "") + Fx.soundingRowMark(rowPlaying || rowPlayingLib, AudioService.liveTrackError, AudioService.liveBuffering ? Lang.t(TalksActivity.this, "notif_state_buffering") : "", "\u25B6") + qMark + favMark + offMark + playedMark + newMark + talks.get(talkIdx).title + resumeMark);
            // v1.52: rows fade + rise in once when first shown
            if (pos > lastAnimPos) {
                lastAnimPos = pos;
                // v15.18 (slice 1781): skip the pour for a row the filter/search set change is about to glide — its old display position differs from its new one (identity by full-list talk index: the ListView recycles views by position). The pour's +24dp rise would start the row 24dp below its old top and fight the glide's park-at-old-top, so the glide owns the motion alone; alpha stays 1 (the row was fully visible before, no fade). Rows that did not move (oldPos == pos) and brand-new rows (no old position) pour exactly as before. The glide is symmetric — a widening change moves survivors DOWN (oldPos < pos) and glides them down home just as a narrowing change glides them up.
                boolean setGlide82 = talkSetGlidePos != null
                        && talkSetGlidePos.containsKey(Integer.valueOf(talkIdx))
                        && talkSetGlidePos.get(Integer.valueOf(talkIdx)).intValue() != pos;
                if (!setGlide82) {
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
                    // A view recycled mid-pour keeps its old fade + rise running — clear it so the glide owns the motion alone (clearAnimation touches view Animations only, never the ObjectAnimator translationY glide, and never the v2.08 press squash).
                    v.clearAnimation();
                }
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
            if (titleView != null) titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tf == 0 ? 14 : (tf == 1 ? 16 : 20));
            // v15.30 (slice 1820): the sounding-row title colour melts on the flip instead of snapping — the last snap in the sounding-row transition (card v15.24, hairline v15.25, clock v15.26, EQ slot v15.30): the 1s tick rebinds exactly when the sounding URL changes, and getView swapped the title colour in one frame (bare setTextColor), so a talk ending, stopping or auto-advancing flashed the gold name in or out while the card beside it crossfades. Fx.crossfadeTextColor (v14.34 helper: 250ms ValueAnimator.ofArgb, recorded target, no-op when unchanged) owns the colour from here. The v14.16 settled decision does NOT bar this — its reason was that fading the whole label to ease a 2-char mark would flash the row; here no label is faded, only the text colour is interpolated (alpha stays 1, text unchanged), so the row cannot flash — the same distinction v14.34 drew for the sleep text and v14.62 for the nav tint. The ▶ prefix + title text stay instant; the listened dim (alpha, below) is untouched.
            if (titleView != null) Fx.crossfadeTextColor(titleView, (rowPlaying || rowPlayingLib)
                    ? android.graphics.Color.parseColor("#C9A227")
                    : (cachedNight
                    ? android.graphics.Color.WHITE
                    : android.graphics.Color.parseColor("#3B2314")));
            // v13.83 (slice 1075): listened rows sit dimmer — the Room
            // drawer's v4.12 Spotify pattern, now on the chooser too:
            // a ✓ talk's title reads at 0.6 alpha so the next unheard
            // talk stands out at a glance. Set on every bind (1.0f
            // otherwise) so a recycled row rebinds its alpha here and
            // a dim can never leak onto the next talk; the playing row
            // is never dimmed — its gold tile owns its emphasis.
            // v15.36 (slice 1861): the dim melts through
            // Fx.crossfadeTitleDim instead of snapping (see Fx) — the
            // Up Next ▸ row below is exempted here, so a marked row
            // seeds at 1.0f on its first bind instead of melting up
            // from a just-seeded 0.6f.
            boolean rowDimmed = rowListened
                    && !(upNextRowUrl.length() > 0
                    && SiteHttp.sameTalk(rowUrl, upNextRowUrl));
            if (titleView != null) Fx.crossfadeTitleDim(titleView, rowDimmed);
            // v13.99 (slice 1094): talk duration clock — the stored
            // total length (talkDurCache, v13.96) as a small muted
            // clock on the row's right edge, so a listener can judge
            // "do I have time for this one" without opening it.
            // Same two legs as the hairline above (stream form,
            // then the downloaded path form), but ungated by the
            // resume spot: a never-heard talk still has a known
            // length, and the playing row shows it too (its live
            // position is on the strip, the length is a property
            // of the talk). Hidden when no duration was ever
            // stored — never guessed. Reset on every bind (text,
            // size, colour), like the alpha above, so a recycled
            // row can never keep the previous talk's clock; the
            // visibility melts through the helper below (v15.26).
            android.widget.TextView durView = v.findViewById(R.id.talkDur);
            if (durView != null) {
                Integer durI99 = talkDurCache.get(rowUrl);
                if (durI99 == null && talkDlCache.contains(rowUrl)) {
                    String dlFn99 = talkDlFileCache.get(rowUrl);
                    if (dlFn99 != null) {
                        String dlPath99 = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma/" + dlFn99).getAbsolutePath();
                        durI99 = talkDurCache.get(dlPath99);
                    }
                }
                if (durI99 != null && durI99.intValue() > 0) {
                    durView.setText(Fx.fmtTime(durI99.intValue()));
                } else {
                    durView.setText("");
                }
                // v15.26 (slice 1790): the clock melts in/out instead of popping — the v14.31 card-crossfade fix, now on the duration label: the 1s tick rebinds exactly when a duration lands in talkDurCache, and getView swapped the label in one frame (setVisibility), so a fresh length flashed onto its row while the gold card beside it crossfades (v15.24) and the hairline melts (v15.25). Fx.crossfadeClock (250ms, DecelerateInterpolator, recorded shown state, instant seed on a fresh view) owns the label from here; the text/size/colour reset above stays instant (a value, not an appearance change), like the hairline's weight split.
                Fx.crossfadeClock(durView, durI99 != null && durI99.intValue() > 0);
                durView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, cachedFont == 0 ? 12 : (cachedFont == 1 ? 13 : 16));
                durView.setTextColor(cachedNight ? android.graphics.Color.parseColor("#AAAAAA") : android.graphics.Color.parseColor("#7A6A58"));
            }
            // v13.85 (slice 1077): Up Next row mark — the Room drawer's v4.13 ▸, now on the chooser rows too: the row the header's Up Next line names carries a ▸ prefix at full alpha, even when already listened, so the imminent talk stands out in the long list instead of hiding behind its ✓ dim. The mark follows upNextRowUrl, the URL refreshFavHeader resolved alongside that line (queued head first, else forward-history top, else shuffle-gated list-next — the Room's peekNextIndex order, restated in the chooser), so the row and the line can never disagree. No ▸ when a foreign queue head waits (upNextRowUrl is empty then, the drawer's v11.60 rule — the foreign head has no row here), while shuffle is on with no head (order unknowable, v5.84), or with no live Room session for this sayadaw (the resolution gates on talkPlayingUrl). The playing row itself never carries it: the guard below, plus the resolution can never name the sounding talk. Set on every bind — the dim above already exempts the marked row (v15.36), so no alpha write lives here any more and the mark can never leak onto the next talk.
            if (titleView != null && !rowPlaying && !rowPlayingLib
                    && upNextRowUrl.length() > 0
                    && SiteHttp.sameTalk(rowUrl, upNextRowUrl)) {
                titleView.setText("▸ " + titleView.getText().toString());
            }
            android.view.View card = v.findViewById(R.id.cardInner);
            // v13.83 (slice 1075): the playing row is a raised gold
            // card — the Room drawer's v12.92 gold tile, now on the
            // chooser too. The sounding talk used to read only as gold
            // text + ▶, which vanished in a long list; now its whole
            // card turns gold (talks_row_playing.xml: drawer gold,
            // chooser 24dp geometry, theme-independent like the
            // drawer's tile). Set on every bind so a recycled row can
            // never keep a stale gold card; the held-row wash on the
            // row root below is untouched and stacks harmlessly.
            // v15.24 (slice 1787): the sounding card crossfades instead of snapping — the drawer's v14.32 fix, now on the chooser too: the 1s tick rebinds the rows exactly when the sounding URL changes (v4.89/v7.44), and getView swapped the card background in one frame, so a talk ending, stopping or auto-advancing while the chooser sat on top flashed the gold tile in or out while everything around it moves (header melt v14.56, Up Next melt v14.55, mark melts v14.97). Fx.crossfadeCardTile (v14.31 helper: 250ms TransitionDrawable, crossFade on, recorded target, instant seed on a fresh view) owns the card from here; the ▶ prefix + title text stay instant per the v14.16 settled decision (its colour melts, v15.30), and the held-row wash below lives on the row root, untouched.
            if (card != null) Fx.crossfadeCardTile(card, (rowPlaying || rowPlayingLib)
                    ? R.drawable.talks_row_playing
                    : (cachedNight ? R.drawable.card_bg_night : R.drawable.card_bg));
            // v15.29 (slice 1813): the sounding row's EQ bars — the
            // one standard streaming motion the chooser lacked (see
            // EqBarsView): the number slot becomes three dancing
            // gold bars while the talk sounds and rests, low and
            // equal, while it is paused (livePlaying counts
            // buffering as playing, so the bars dance while the
            // talk is on its way, matching the strip's pause icon).
            // Set on every bind so a recycled row rebinds its
            // number/bars here and a dance can never leak onto the
            // next talk; setPlaying is idempotent, so a tick rebind
            // of the same sounding row never restarts the dance.
            // The ▶ prefix + gold name stay instant per the v14.16
            // settled decision — the EQ is an added live indicator,
            // not a mark swap. Library rows keep their pulse
            // (v2.25); the drawer keeps its title pulse (v2.27).
            com.h2h.dhamma.EqBarsView eqBars = v.findViewById(R.id.eqBars);
            if (indexView != null && eqBars != null) {
                boolean eqSounding = rowPlaying || rowPlayingLib;
                // v15.30 (slice 1819): the slot crossfades (see
                // Fx.crossfadeEqSlot) instead of snapping the number
                // and the bars — the last snap in the sounding-row
                // transition (card v15.24, hairline v15.25, clock
                // v15.26). The helper owns both views' visibility +
                // alpha from here; the raw setVisibility swap is gone.
                Fx.crossfadeEqSlot(indexView, eqBars, eqSounding);
                eqBars.setPlaying(eqSounding && AudioService.livePlaying);
            }
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
            // v14.11 (slice 1292): a line-wrapped href (newline/tab
            // inside the quoted value) is a REAL link — the site
            // wraps long mp3 hrefs across a line break, and the
            // WHATWG URL parser strips tab/LF/CR from URLs, so the
            // link works in a browser. The v11.83 skip dropped these
            // talks (1,993 across the catalog, 8 sayadaws to zero —
            // Pa Auk 23 of 670, Shwe Oo Min 0 of 14, measured live).
            // Strip the control chars at the source instead: the
            // stripped URL can never poison a newline-joined store,
            // so the skip is unnecessary for this class.
            href = href.replace("\t", "").replace("\n", "").replace("\r", "");
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

    // v14.13 (slice 1294): hub-page crawl — a catalog row whose page
    // lists no mp3 itself but links to sub-pages that do (1290 NEW 3,
    // re-measured live, correcting slice 1290's "5 hub pages" framing:
    // only idx1 is a true hub — its 3 sub-pages hold 601 + 47 + 381 =
    // 1,029 mp3 talks. idx4 is a biography stub with no links at all;
    // idx33/60/63 are .wma-only pages (38/11/12 distinct talks —
    // idx33 lists one .wma twice — no mp3 twins, and Android
    // cannot play WMA), so those stay empty rather than listing
    // unplayable rows. The crawl fires only on a 0-talk parse,
    // for it; a failed sub-fetch is skipped, never an error).
    // HubFetcher supplies one sub-page's HTML (the caller's own
    // fetch — SiteHttp in the loaders, fetchPageHtml68 in the job —
    // so the TLS retry and the Wi-Fi binding ride along unchanged).
    public interface HubFetcher {
        String fetch(String url) throws Exception;
    }

    // The site's nav chrome, on every page (filenames, lowercased):
    // crawling these would merge the whole site into one row
    // (AudioInMyanmar.htm is the catalog index — every sayadaw's
    // talks would land in this row). Never crawled.
    private static final java.util.Set<String> HUB_NAV =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "index.htm", "abhidhammainenglish.htm",
                    "abhidhammainmyanmar.htm", "audioinenglish.htm",
                    "audioinmyanmar.htm", "videoinenglish.htm",
                    "videoinmyanmar.htm", "ebook-english.htm",
                    "ebook-myanmar.htm", "dhammadownload-news.htm",
                    "contribute-to-dhammadownload.htm",
                    "suggestion-cbox.htm", "contact.htm",
                    "usefullinks.htm", "live.htm"));

    // Same-host .htm/.html sub-page links of a hub page, in order:
    // nav dropped, the page itself dropped, other hosts dropped,
    // duplicates dropped, capped at 40 (measured max is 3).
    // Pure static core, so WgTest proves it in real Java.
    public static java.util.List<String> extractHubLinks(String pageUrl, String html) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (pageUrl == null || html == null) return out;
        URL base;
        try { base = new URL(pageUrl); } catch (Exception ignored) { return out; }
        String self;
        try { self = stripHubFragment(new URL(base, pageUrl).toString()); }
        catch (Exception ignored) { self = pageUrl; }
        Pattern pat = Pattern.compile("<a[^>]*href\\s*=\\s*(?:\"([^\"]*\\.htm[^\"]*)\"|'([^']*\\.htm[^']*)'|([^\\s>]*\\.htm[^\\s>]*))[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = pat.matcher(html);
        java.util.Set<String> seen = new java.util.LinkedHashSet<String>();
        while (m.find() && out.size() < 40) {
            String href = m.group(1) != null ? m.group(1)
                    : (m.group(2) != null ? m.group(2) : m.group(3));
            href = href.trim();
            // Same wrap class as the v14.11 mp3 fix: the site wraps
            // long hrefs across a line break inside the quoted value
            // (the .wma anchors on idx33/63 wrap after "Audio-Library/").
            href = href.replace("\t", "").replace("\n", "").replace("\r", "");
            String abs;
            try {
                abs = stripHubFragment(new URL(base, href.replace("&amp;", "&")
                        .replace(" ", "%20")).toString());
            } catch (Exception ignored) { continue; }
            URL u;
            try { u = new URL(abs); } catch (Exception ignored) { continue; }
            if (!u.getHost().equalsIgnoreCase(base.getHost())) continue;
            String path = u.getPath();
            if (path == null) continue;
            String lower = path.toLowerCase();
            if (!lower.endsWith(".htm") && !lower.endsWith(".html")) continue;
            String file = path.substring(path.lastIndexOf('/') + 1).toLowerCase();
            if (HUB_NAV.contains(file)) continue;
            if (abs.equals(self)) continue;
            if (seen.add(abs)) out.add(abs);
        }
        return out;
    }

    private static String stripHubFragment(String url) {
        int h = url.indexOf('#');
        return h >= 0 ? url.substring(0, h) : url;
    }

    // Fetch each hub sub-page and merge its talks (parseTalkList's
    // rules, deduped by URL across sub-pages). A sub-page that fails
    // to fetch or parse is skipped — the hub still shows the talks
    // its other sub-pages hold. Never throws.
    public static java.util.List<Talk> crawlHubTalks(String pageUrl, String html,
            HubFetcher fetcher) {
        java.util.List<Talk> out = new java.util.ArrayList<Talk>();
        if (fetcher == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (String sub : extractHubLinks(pageUrl, html)) {
            if (out.size() >= 10000) break;
            String sh;
            try { sh = fetcher.fetch(sub); } catch (Exception ignored) { continue; }
            if (sh == null || sh.length() == 0) continue;
            java.util.List<Talk> subTalks;
            try { subTalks = parseTalkList(sub, sh); } catch (Exception ignored) { continue; }
            for (Talk t : subTalks) {
                if (t == null || t.url == null || t.url.length() == 0) continue;
                if (seen.add(t.url)) out.add(t);
            }
        }
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

    // v15.34 (slice 1841): pull-to-refresh on the Talks list —
    // the chooser had no way to re-fetch a sayadaw's talks without
    // leaving and re-entering (the retry button only appears after
    // a failed load). A downward pull at the very top of the list
    // now grows a small gold header ("↻ ပြန်ဆွဲထုတ်မယ်" — the
    // retry button's own text, no new strings) and, past the
    // threshold, reloads the list through the same loadTalks() the
    // retry uses. The header IS the load indicator while it runs:
    // loadTalks keeps the central spinner down (see its guard), so
    // the two never stack. The listener never consumes an event —
    // row taps, long-press share and the queue pick-up/drop move
    // (v5.77) all keep working; a pull simply never starts while
    // a queue move is held. Damped 0.5, threshold 72dp, cap 144dp,
    // settle 56dp, collapse 200ms Decelerate — the app's own melt
    // rhythm (v2.60/v2.79). Rotation restores the list, never a
    // pull in flight (v1.53): the fresh instance starts GONE.
    private void wirePullRefresh() {
        pullHeader = findViewById(R.id.pullRefreshHeader);
        pullSpinner = findViewById(R.id.pullRefreshSpinner);
        pullLabel = findViewById(R.id.pullRefreshLabel);
        if (talkList == null || pullHeader == null) return;
        talkList.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent ev) {
                return pullOnTouch(ev);
            }
        });
    }

    private boolean pullAtTop() {
        if (talkList == null) return false;
        if (talkList.getFirstVisiblePosition() != 0) return false;
        View first = talkList.getChildAt(0);
        return first == null || first.getTop() >= 0;
    }

    private int pullDp(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private int pullMutedColor() {
        return android.graphics.Color.parseColor(Prefs.isNight(TalksActivity.this) ? "#AAAAAA" : "#7A6A58");
    }

    private void pullSetHeight(int px) {
        if (pullHeader == null) return;
        android.view.ViewGroup.LayoutParams lp = pullHeader.getLayoutParams();
        lp.height = px;
        pullHeader.setLayoutParams(lp);
        pullHeader.setVisibility(px > 0 ? View.VISIBLE : View.GONE);
    }

    private void pullSetArmed(boolean armed) {
        if (pullArmed == armed) return;
        pullArmed = armed;
        if (pullLabel == null) return;
        Fx.crossfadeTextColor(pullLabel, armed
                ? android.graphics.Color.parseColor("#C9A227")
                : pullMutedColor());
    }

    private void pullHeightTo(int px, long durationMs) {
        if (pullHeader == null) return;
        if (pullHeightAnim != null) pullHeightAnim.cancel();
        final int start = pullHeader.getHeight();
        if (start == px) return;
        final android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofInt(start, px);
        pullHeightAnim = anim;
        anim.setDuration(durationMs);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                pullSetHeight((Integer) a.getAnimatedValue());
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (pullHeightAnim == anim) pullHeightAnim = null;
            }
        });
        anim.start();
    }

    private void pullCollapse() {
        if (pullHeader == null || pullHeader.getVisibility() != View.VISIBLE) return;
        pullHeightTo(0, 200);
    }

    private void pullFinish() {
        if (!pullRefreshing) return;
        pullRefreshing = false;
        if (pullSpinner != null) pullSpinner.setVisibility(View.GONE);
        if (pullLabel != null) Fx.setTextColorNow(pullLabel, pullMutedColor());
        pullCollapse();
    }

    private boolean pullOnTouch(android.view.MotionEvent ev) {
        if (talkList == null || pullHeader == null) return false;
        int action = ev.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            if (pullRefreshing) return false;
            if (moveHeldUrl != null) return false;
            if (progress != null && progress.getVisibility() == View.VISIBLE) return false;
            if (!pullAtTop()) return false;
            pullStartY = ev.getY();
            pullLastY = ev.getY();
            pullTracking = true;
            return false;
        }
        if (action == android.view.MotionEvent.ACTION_MOVE) {
            float y = ev.getY();
            if (!pullTracking) {
                // Scrolled up to the top inside this same gesture:
                // arm here, so a continuous scroll-then-pull works
                // like the platform pull (no lift needed).
                if (pullRefreshing || moveHeldUrl != null) { pullLastY = y; return false; }
                if (progress != null && progress.getVisibility() == View.VISIBLE) { pullLastY = y; return false; }
                if (pullAtTop() && y > pullLastY) {
                    pullStartY = y;
                    pullTracking = true;
                }
                pullLastY = y;
                return false;
            }
            pullLastY = y;
            float dy = y - pullStartY;
            if (dy <= 0) {
                pullSetHeight(0);
                pullSetArmed(false);
                return false;
            }
            if (pullHeightAnim != null) { pullHeightAnim.cancel(); pullHeightAnim = null; }
            int h = Math.min((int) (dy * 0.5f), pullDp(144));
            pullSetHeight(h);
            pullSetArmed(h >= pullDp(72));
            return false;
        }
        if (action == android.view.MotionEvent.ACTION_UP
                || action == android.view.MotionEvent.ACTION_CANCEL) {
            if (!pullTracking) return false;
            pullTracking = false;
            pullStartY = -1f;
            pullLastY = -1f;
            if (action == android.view.MotionEvent.ACTION_CANCEL || !pullArmed) {
                pullSetArmed(false);
                pullCollapse();
                return false;
            }
            pullArmed = false;
            pullRefreshing = true;
            if (pullSpinner != null) pullSpinner.setVisibility(View.VISIBLE);
            pullHeightTo(pullDp(56), 200);
            loadTalks();
            return false;
        }
        return false;
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
        // v13.36 (Talks T5): the load spinner block dereferenced the
        // progress field bare — a missing R.id.progress NPEd every
        // load, right after open. Guard the whole block; the load
        // itself proceeds without a spinner.
        // v15.34 (slice 1841): a pull-refresh load already shows
        // its own spinner in the pull header — the central spinner
        // stays down, so the two never stack. pullRefreshing is
        // only ever true between the pull's release and this
        // load's landing, so every other caller is untouched.
        if (progress != null && !pullRefreshing) {
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
                // v13.57 (offline resilience): the HTTP status and the
                // failure classification ride to the landing with the
                // list — the landing names the real reason and shows
                // the saved list when the fetch itself failed.
                int httpCode = 0;
                final boolean[] usedCache = new boolean[1];
                final String[] failReason = new String[1];
                // v13.57: the connection lifecycle (disconnect in a
                // finally, v1.58/v1.66) now lives in SiteHttp, which
                // owns both the HTTPS attempt and the http retry.
                try {
                    // v13.25 (catalog F1): a sayadaw with no talk page
                    // on the source site (idx 44/45 — plain text, no
                    // link) has a null/empty pageUrl, so new URL("")
                    // used to throw MalformedURLException and the
                    // landing showed the failure toast + a retry
                    // button that could never succeed. Skip the fetch:
                    // found stays empty and err stays null, so the
                    // landing is the clean-empty one (no-talks toast,
                    // no retry — the v12.31 rule), and talkCleanEmpty
                    // keeps a rotation from refetching a URL that
                    // does not exist (v12.56 restore branch).
                    if (pageUrl != null && pageUrl.length() > 0) {
                        // v13.57 (TLS resilience): the fetch rides
                        // through SiteHttp — HTTPS first, one retry
                        // over plain http when the site's TLS is
                        // refused on this phone/network. The status
                        // still rides to the landing for the reason
                        // classifier (SiteHttp.lastHttpCode).
                        byte[] bytes = SiteHttp.fetchBytes(pageUrl, 20000, 30000);
                        httpCode = SiteHttp.lastHttpCode;
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
                        // v14.13 (slice 1294): hub pages — when the
                        // first parse is empty, follow the page's own
                        // sub-page links (idx1's 3 sub-pages hold
                        // 1,029 talks the row shows as empty today).
                        // A failed sub-fetch is skipped inside the
                        // crawl, never an error, so err stays null
                        // and the row lands clean-empty as before.
                        if (found.isEmpty()) {
                            try {
                                found.addAll(crawlHubTalks(pageUrl, html,
                                        new HubFetcher() {
                                    @Override public String fetch(String u)
                                            throws Exception {
                                        byte[] b = SiteHttp.fetchBytes(
                                                u, 20000, 30000);
                                        return new String(b, "UTF-8");
                                    }
                                }));
                            } catch (Exception ignored) {}
                        }
                    }
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
                    // v13.57: classify the real reason now, while the
                    // exception is in hand — the landing only gets the
                    // message string, which cannot tell DNS from a
                    // certificate failure. The status rides in
                    // SiteHttp.lastHttpCode (an HTTP error throws
                    // after recording it; a network failure leaves 0).
                    httpCode = SiteHttp.lastHttpCode;
                    failReason[0] = TalkListCache.reasonKey(e, httpCode);
                }
                // v13.57 (offline resilience): a good list is saved
                // for the next offline visit; a failed load falls back
                // to the saved list for this sayadaw instead of an
                // empty screen, so downloaded talks in it still play
                // and the listener still sees the titles. The failure
                // itself (err) is untouched — the landing still shows
                // the retry button and the reason toast.
                if (err == null && !found.isEmpty()) {
                    try {
                        // v15.41 (slice 1920): newly-added talks —
                        // diff the fresh list against the saved
                        // one BEFORE overwriting it: identity URLs
                        // in the fresh list but not the saved one
                        // are new since the last visit. A first
                        // visit has no saved list, so the diff is
                        // empty (nothing is new — the whole list
                        // is a first sight). A failed load never
                        // reaches here, so a failed retry keeps
                        // the old set.
                        java.util.List<String[]> oldPairs = TalkListCache.load(TalksActivity.this, pageUrl);
                        java.util.List<String> freshUrls = new java.util.ArrayList<String>();
                        for (Talk t : found) freshUrls.add(t.url);
                        TalkListCache.saveNewSet(TalksActivity.this, pageUrl,
                                TalkListCache.diffNewIds(oldPairs, freshUrls));
                        java.util.List<String[]> pairs = new java.util.ArrayList<String[]>();
                        for (Talk t : found) pairs.add(new String[]{t.title, t.url});
                        TalkListCache.save(TalksActivity.this, pageUrl, pairs);
                    } catch (Exception ignored) {}
                } else if (err != null) {
                    try {
                        java.util.List<String[]> cached = TalkListCache.load(TalksActivity.this, pageUrl);
                        if (cached != null && !cached.isEmpty()) {
                            for (String[] p : cached) found.add(new Talk(p[0], p[1]));
                            usedCache[0] = true;
                        }
                    } catch (Exception ignored) {}
                }
                final String ferr = err;
                final String freason = failReason[0];
                final boolean fcache = usedCache[0];
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // v1.63: a load finishing after back-press used
                        // to touch this dead screen (views + toasts on
                        // a finished activity) — drop it silently.
                        if (isFinishing() || isDestroyed()) return;
                        // v1.83: a stale load (see loadSeq) must not
                        // overwrite a newer load's list.
                        if (mySeq != loadSeq) return;
                        // v13.24 (T2): Home-press landing — a load
                        // finishing while this screen is paused must
                        // not toast or auto-open over the launcher.
                        // The v1.63 guard above only drops finished /
                        // destroyed landings; a paused activity is
                        // neither, so the user-facing effects below
                        // (toasts + auto-open) run only while resumed
                        // and are stashed for onResume otherwise.
                        // The silent data landing (list, marks, title,
                        // retry button) still runs — it only touches
                        // this screen's own views.
                        // (Activity has no isResumed() — that is a
                        // Fragment method — so this reads the field
                        // onResume/onPause maintain below. UI thread
                        // only, so no race with the lifecycle.)
                        final boolean landingResumed = talksResumed;
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
                        pullFinish(); // v15.34 (slice 1841): a pull-refresh load settles its header here — success or failure, the same landing owns both
                        talks.clear();
                        talks.addAll(found);
                        // v15.41 (slice 1920): the bg thread just
                        // replaced the new-talk set for this load —
                        // pick it up before the rebind below paints.
                        newTalkUrls = TalkListCache.loadNewSet(TalksActivity.this, pageUrl);
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
                        // v14.45 (slice 1700): the search + filter rows
                        // fade in/out with the list instead of snapping
                        // — the chooser's landing rows (Play All below
                        // does the same in refreshTalkPlayAllRow), so
                        // the search box, the chips and the Play All /
                        // Shuffle row all melt in together while the
                        // rows pour below (v14.23). Edge-only, the
                        // v14.44 empty-line/✕ rhythm: a steady landed
                        // list never re-animates, a re-appear landing
                        // mid-fade-out glides back from its current
                        // alpha (Fx.fadeIn, v3.52) and the gen guard
                        // strands nothing hidden. The callback fires
                        // only on a fresh open / retry (a rotation
                        // restores instead, `if (!restored)
                        // loadTalks()`), so no skip flag is needed
                        // here — rotation restores keep snapping, the
                        // v1.53 rule. The show formula is untouched.
                        android.view.View sRow = findViewById(R.id.talkSearchRow);
                        if (sRow != null) {
                            boolean showRow = !found.isEmpty();
                            if (showRow) {
                                if (sRow.getVisibility() != android.view.View.VISIBLE) {
                                    sRow.setVisibility(android.view.View.VISIBLE);
                                    Fx.fadeIn(sRow, 250);
                                } else if (sRow.getAlpha() < 0.999f) {
                                    Fx.fadeIn(sRow, 250);
                                }
                            } else if (sRow.getVisibility() == android.view.View.VISIBLE) {
                                Fx.fadeOut(sRow, 200);
                            }
                        }
                        android.view.View fRow = findViewById(R.id.talkFilterRow);
                        if (fRow != null) {
                            boolean showRow = !found.isEmpty();
                            if (showRow) {
                                if (fRow.getVisibility() != android.view.View.VISIBLE) {
                                    fRow.setVisibility(android.view.View.VISIBLE);
                                    Fx.fadeIn(fRow, 250);
                                } else if (fRow.getAlpha() < 0.999f) {
                                    Fx.fadeIn(fRow, 250);
                                }
                            } else if (fRow.getVisibility() == android.view.View.VISIBLE) {
                                Fx.fadeOut(fRow, 200);
                            }
                        }
                        refreshTalkPlayAllRow(); // v13.76: Play All lands with the list
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
                        // v13.24 (T2): ... but only while resumed — a
                        // paused landing stashes the toast for the
                        // onResume flush instead of firing it over
                        // the launcher.
                        if (landingResumed) {
                            if (ferr != null) {
                                // v13.57: the toast names the real reason
                                // (DNS / certificate / timeout / connect /
                                // server) instead of the one generic line,
                                // and adds the saved-list note when the
                                // rows below are the cached list.
                                String fmsg = (freason == null
                                        ? Lang.t(TalksActivity.this, "talks_fetch_failed_long")
                                        : Lang.t(TalksActivity.this, freason));
                                if (fcache) fmsg = fmsg + "\n"
                                        + Lang.t(TalksActivity.this, "cached_list_note");
                                android.widget.Toast.makeText(TalksActivity.this,
                                        fmsg,
                                        android.widget.Toast.LENGTH_LONG).show();
                            } else if (found.isEmpty())
                                android.widget.Toast.makeText(TalksActivity.this,
                                        Lang.t(TalksActivity.this, "talks_none_for_sayadaw"),
                                        android.widget.Toast.LENGTH_LONG).show();
                        } else {
                            landingDeferred = true;
                            pendingLandingFerr = ferr;
                            pendingLandingEmpty = found.isEmpty();
                            pendingLandingReason = freason;
                            pendingLandingCached = fcache;
                        }
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
                        talkLoadFailed = ferr != null;
                        talkLoadReason = freason;
                        talkLoadCached = fcache;
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
                        // v13.24 (T2): same Home-press rule — the
                        // Room must not open over the launcher.
                        // autoOpenFromLanding is this block, factored
                        // out so the onResume flush runs the identical
                        // code; autoUrl stays set while deferred, so
                        // the flush consumes it exactly once, on a
                        // clean non-empty load only (v10.84).
                        boolean autoOpened = false;
                        if (landingResumed) autoOpened = autoOpenFromLanding(ferr);
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
                                                if (SiteHttp.sameTalk(talks.get(i).url, liveUrl)) {
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

    // v13.24 (T2): the load landing's auto-open, factored out of the
    // landing runnable so the onResume flush for a paused landing
    // runs the identical code. Returns true when the Room opened.
    private boolean autoOpenFromLanding(String ferr) {
        if (autoUrl == null || autoUrl.length() == 0) return false;
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
        boolean opened = false;
        for (int i = 0; i < talks.size(); i++) {
            if (SiteHttp.sameTalk(talks.get(i).url, want)) { opened = true; openRoom(i); found = true; break; }
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
        return opened;
    }

    // v13.24 (T2): flush a load landing's user-facing effects that
    // were deferred because the load finished while this screen was
    // paused (Home press). The flag clears before anything fires, so
    // a second onResume never re-fires; the stashed ferr is captured
    // before the fields clear, so the auto-open sees the same
    // failure/clean state the landing saw.
    private void flushDeferredLanding() {
        landingDeferred = false;
        String pendingFerr = pendingLandingFerr;
        pendingLandingFerr = null;
        boolean pendingEmpty = pendingLandingEmpty;
        pendingLandingEmpty = false;
        // v13.57: the stashed reason + cached flag flush with the
        // deferred toast, so a Home-press landing shows the same
        // message it would have shown while resumed.
        String pendingReason = pendingLandingReason;
        pendingLandingReason = null;
        boolean pendingCached = pendingLandingCached;
        pendingLandingCached = false;
        if (pendingFerr != null) {
            String fmsg = (pendingReason == null
                    ? Lang.t(TalksActivity.this, "talks_fetch_failed_long")
                    : Lang.t(TalksActivity.this, pendingReason));
            if (pendingCached) fmsg = fmsg + "\n"
                    + Lang.t(TalksActivity.this, "cached_list_note");
            android.widget.Toast.makeText(TalksActivity.this,
                    fmsg,
                    android.widget.Toast.LENGTH_LONG).show();
        } else if (pendingEmpty)
            android.widget.Toast.makeText(TalksActivity.this,
                    Lang.t(TalksActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_LONG).show();
        autoOpenFromLanding(pendingFerr);
    }
}
