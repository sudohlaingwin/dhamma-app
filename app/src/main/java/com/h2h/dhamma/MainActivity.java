package com.h2h.dhamma;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    public static class Sayadaw {
        String name, group, url, photo;
        Sayadaw(String n, String g, String u, String p) { name = n; group = g; url = u; photo = p; }
    }

    private List<Sayadaw> all = new ArrayList<Sayadaw>();
    private List<Sayadaw> shown = new ArrayList<Sayadaw>();
    private SayadawAdapter adapter;
    private String query = "";
    // v4.19: favourites-only grid filter (see the search long-press
    // below) — Main never had a way to view only ★ sayadaws, so a
    // listener with many favourites scrolled the whole catalogue
    // to find them. No new view: the existing search field's
    // long-press toggles it, and the Home-nav long-press reset
    // clears it along with the query.
    private boolean favOnly = false;
    // v2.03: restoring the pre-rotation query into the search box
    // fires the watcher, which used to run applyFilter(true) and
    // re-toast a no-match search on every rotation — the v2.02
    // "rotation stays silent" promise never held, because setText
    // goes through the same watcher as a real keystroke. While
    // this flag is up the watcher filters silently, like the
    // onCreate/rotation applyFilter() call already does.
    private boolean restoringSearch = false;
    // v2.02: the query whose no-match toast already fired — a
    // no-match toast must fire once per query, not on every
    // keystroke while the grid stays empty.
    private String lastEmptyToastQuery = null;
    // v2.07: grid entrance guard, same model as the Talks rows —
    // only positions never seen before animate in.
    private int lastGridAnimPos = -1;
    // v2.48: snapshot of the last filtered grid, so a search that
    // actually changes the results can re-pour them (below) while
    // a keystroke that changes nothing never replays the cascade.
    private String lastShownKey = "";
    // v14.26 (slice 1681): ★ filter-toggle pour arm — toggling the
    // favourites-only filter (or clearing it by gesture: Home
    // long-press reset, star long-press last-favourite exit) changes
    // the shown set through the silent applyFilter(), so the new
    // cards snapped in while a search keystroke pours (v2.48).
    // Armed only at those gesture sites, never on rotation,
    // restore or return (onResume staleness guard), and consumed
    // on every applyFilter pass below.
    private boolean mainFavToggleChange = false;
    private Set<String> favs = new HashSet<String>();
    // v13.74 (slice 1065): ★ favourites shelf views + the key of the
    // last poured shelf (favourite names + night flag) — a rebuild
    // only happens when the set or the theme actually changes, so
    // keystroke filters and ticks never re-pour the chips.
    private android.widget.LinearLayout favShelfWrap;
    private android.widget.LinearLayout favShelf;
    private String lastFavShelfKey = null;
    // v14.50 (slice 1707): sounding-only shelf trackers — the ordered
    // favourite names + night flag + sounding name of the last built
    // shelf, so a sounding change under a visible shelf with the same
    // set can update the chips in place (below) instead of rebuilding
    // them. Null until the first build, so a load / rotation restore
    // always takes the rebuild path and never melts on top of the
    // first-appearance pour (v14.15).
    private java.util.List<String> lastFavShelfNames = null;
    private boolean lastFavShelfNight = false;
    private String lastShelfSounding = null;
    // v15.04 (slice 1762): cross-rebuild melt continuity — the chips
    // this shelf is currently melting in, by name. The v14.99 added-chip
    // melt, the v15.02 parked melt and the v14.50 sounding melt all fade
    // a chip in over 250ms, but a rebuild landing inside that window
    // detaches the chip (removeAllViews) and rebuilds it at full alpha —
    // the v3.52 restart continuity covers a fade landing on the SAME view,
    // not a view replacement, so the half-faded chip jumped to opaque in
    // one frame. Each melt records its (name, view) here; the rebuild
    // snapshots the still-attached views' alpha before destroying them
    // and the fresh chip for the same name starts there and fades on.
    // Cleared at every rebuild after the snapshot, so a stale entry can
    // never outlive one cycle.
    private java.util.Map<String, android.view.View> shelfMeltViews62 =
            new java.util.HashMap<String, android.view.View>();
    // v15.05 (slice 1763): the departures still fading out — the
    // mirror of shelfMeltViews62 above, for the fade-OUT side. A
    // rebuild landing inside a departure's 250ms fade-out (the
    // v15.00 re-seat) used to detach the chip mid-fade at alpha
    // ~0.36 and it vanished in one frame (sim1762 B6). Each re-seat
    // records its departing view here; the next rebuild snapshots
    // the still-attached ones' alpha + slot before removeAllViews
    // and re-seats them to finish the fade. Cleared at every
    // rebuild after the snapshot, so a stale entry can never
    // outlive one cycle; a view already detached is skipped by the
    // parent check there.
    private java.util.List<android.view.View> shelfDepartingViews63 =
            new java.util.ArrayList<android.view.View>();
    // v15.08 (slice 1767): the rank-close glides still running — the
    // mirror of shelfMeltViews62 above, for the translationX side. A
    // rebuild landing inside the v15.03 glide's 250ms window used to
    // replace the gliding views with fresh ones at translationX 0, so
    // the shelf snapped to home positions mid-glide (the 1766 open
    // question, traced first-hand this run: a rebuild at t=350 jumps
    // a chip ~0.36 of a slot left in one frame). Each glide records
    // its (name, view) here plus its start translationX and duration
    // below; the rebuild snapshots the still-attached ones before
    // removeAllViews and the fresh chip parks at the captured
    // translationX and glides home over the exact remainder
    // (duration * sqrt(x / start)). Cleared at every rebuild after
    // the snapshot, so a stale entry can never outlive one cycle.
    private java.util.Map<String, android.view.View> shelfGlideViews65 =
            new java.util.HashMap<String, android.view.View>();
    private java.util.Map<String, Float> shelfGlideStart65 =
            new java.util.HashMap<String, Float>();
    private java.util.Map<String, Long> shelfGlideDur65 =
            new java.util.HashMap<String, Long>();
    // v14.15 (slice 1669): ★ favourites shelf first-appearance
    // cascade — the shelf's chips used to pop in instantly while
    // the header (v2.17) and the grid (v2.07) pour in around them,
    // so the first screen opened with a dead strip between two
    // moving ones. The chips now pour with the same staggerChildren
    // fade + rise the Settings content and the Downloaded list use.
    // Only a GONE -> VISIBLE appearance pours: a rebuild while the
    // shelf is already visible (sounding change, star toggle) just
    // rebinds, like the grid's rows-only refresh, so a track change
    // never re-pours the shelf. A rotation restores the shelf
    // still (shelfSkipFirstPour, set in onCreate) — same rule as
    // the grid's v2.07 guard.
    private boolean shelfSkipFirstPour = false;
    private View rootView;
    private TextView titleText;
    private Button themeToggle;
    private EditText searchField;
    // v13.82 (slice 1074): ★ favourites-filter button beside the
    // search field — a direct tap for the favourites-only filter
    // (was a hidden long-press on an empty search, v4.19).
    private android.widget.TextView favFilterBtn;
    private GridView gridView;
    // v2.03: row-bind cache — getView used to read SharedPreferences
    // (isNight + three size lookups, each a fontSize() call) for
    // every row on every scroll bind. applyThemeAndFont() already
    // runs on create and on return and rebinds, so cache here;
    // defaults are the normal-font values, corrected by that call.
    private boolean cachedNight = false;
    private float cachedNameSize = 11, cachedGroupSize = 10;
    // v3.84: cached UI language for the return dip (see onResume) —
    // null until applyThemeAndFont first runs, so a first onResume
    // can never dip on it.
    private String cachedLang = null;
    // v3.97: Main return-glide anchor — the live session's sayadaw
    // when Main was last left (null until Main has ever paused, so
    // a cold start never glides). Read from Prefs only, never a
    // service bind: Main deliberately never binds (v1.81), and the
    // nav Player button already reads lastName/lastKind this way.
    private String mainReturnAnchorName = null;
    // v3.99: sounding-card pulse state — the running animator + the
    // name view it breathes on, so a recycled view never keeps
    // another sayadaw's breath (same one-pulse model as the library
    // row v2.25 / Room sheet v2.27). v11.78: the sayadaw-name field
    // is gone — it was written, never read; identity is by view.
    private android.animation.ObjectAnimator mainPulse = null;
    private android.view.View mainPulseView = null;
    private android.os.Handler mainPulseHandler = new android.os.Handler();
    private Runnable mainPulseTick = null;
    // v11.15: ended state the grid rows were last bound against —
    // the tick never rebinds the grid, so without this the v11.15
    // mark gate above would only correct on a scroll/return
    // rebind while Main sat on top through the end.
    private boolean mainGridEnded = false;
    // v12.38: sounding sayadaw the grid rows were last bound
    // against (null = no mark) — the v11.15 twin for the
    // stop/switch class: the tick rebinds only on the ended
    // flip, so a stop (liveSession false, liveEnded stays
    // false) or a switch to another sayadaw (lastName moves,
    // liveEnded stays false) left the bind-time "▶ " + gold
    // mark stale while Main sat on top — the pulse moved or
    // cleared on the tick, but the mark disagreed with it
    // until a scroll/rebind or return, the exact disagreement
    // the v5.44 comment says cannot happen. Seeded at the
    // applyThemeAndFont rebind below, so a live session on
    // return costs no extra tick rebind; steady ticks never
    // touch it (the sounding name is unchanged).
    private String mainGridSounding = null;
    // v14.05 (slice 1100): failed state the grid rows were last bound
    // against — the v11.15/v12.38 twin for the failure class: a stream
    // failing (same sayadaw, liveTrackError flipped) would otherwise
    // leave the ▶ on the failed card until the next rebind, while the
    // strip already said ⚠.
    private boolean mainGridFailed = false;
    private boolean mainGridBuffering = false;
    // v4.60: is the mini strip currently showing the resume mode
    // (dead session + saved spot) instead of a live session? The
    // strip's toggle and long-press read it, so a resume tap
    // re-opens the Room instead of toggling a dead service, and
    // a resume long-press clears the saved spot instead of
    // stopping a session that no longer exists. Set on every
    // syncNowStrip pass, never anywhere else.
    private boolean resumeStripMode = false;
    // v6.00: the sayadaw whose portrait the mini strip last showed
    // (null = photo hidden). The strip's 1s tick re-syncs text, but
    // the portrait loads once per sayadaw — re-loading every tick
    // would re-queue the ImageLoader waiter on every second.
    private String stripPhotoName = null;
    private boolean lastStripPhotoRing = false;
    // v10.08: download-complete listener for the notification card
    // while Main is foreground — Main leg of the v10.06/v10.07
    // live-sync family. Main's own strip re-syncs every second
    // (syncNowStrip's ⏳ count included), so nothing in-app goes
    // stale here; only the shade/lock-screen card does — its
    // ⬇/⏳ mark rebuilds every 15s while playing, never while
    // paused, so a download landing for a PAUSED sounding talk
    // left the card on ⏳ until the next tap. Registered in
    // onResume, unregistered in onPause, Talks v10.07 pattern.
    private android.content.BroadcastReceiver mainDlCompleteReceiver = null;
    private boolean mainDlCompleteRegistered = false;
    private View navBar;
    private View navPlayerItem, navSettingsItem;

    // v10.20: Main prune-drop leg of the v10.06/v10.14/v10.15/v10.16/
    // v10.17/v10.18/v10.19 download-mark sync — Main was the one
    // surface left with a dlQueued-reading strip but NO prune. Its
    // live strip's global " ⏳ N" queries live DownloadManager jobs
    // every tick (v4.27), so it never goes stale; but its resume
    // strip (dead Room session, ↺) calls Fx.stripResumeDownloadMark
    // on every tick, and that helper reads dlQueued raw, so a job
    // that FAILED, was cancelled, or was cleared left its ⏳ on the
    // ↺ strip until some Room/Talks/Library rescan pruned it.
    // Mirrors the Talks v7.77/v10.17 rule, Main-adapted (no talk
    // list): the landed leg is the shared DlLanded definition, and
    // a landed job's DM row is STATUS_SUCCESSFUL, hence not a live
    // row, so its entry drops by the landed leg or the same
    // no-live-job rule.
    // v12.15: the unmapped keep ends here. The v10.20 keep cited
    // "the Talks conservative branch", but Talks abandoned that
    // branch in v11.86 and Room (v11.85) / Library (v12.14)
    // followed: all four enqueue sites record dlMap at-or-before
    // dlQueued (Room recordDownloadFile before addQueuedDownload,
    // Talks recordTalkDownload, Fx stripEnqueueDownload in one
    // edit), so a map-less entry is a legacy remnant or torn
    // write whose job is dead — drop it when none of its filename
    // candidates names a live job, the shared rule. Keeping it
    // left its ⏳ on the resume strip (Fx.stripResumeDownloadMark
    // reads dlQueued raw) until some Room/Talks/Library rescan
    // pruned it, and this screen's own onResume/completion
    // rescans never could. A failed query keeps everything.
    // Returns the dropped URLs (empty when nothing dropped or on
    // any failure); writes back only when something actually dropped.
    private java.util.Set<String> pruneMainQueuedDownloads1020() {
        // v13.68 (audit-1369 F11): dlMap/dlSayadaw/dlQueued read-modify-write - serialize on DlLock.LOCK (see DlLock).
        synchronized (DlLock.LOCK) {
        java.util.Set<String> dropped1020 = new java.util.HashSet<String>();
        try {
            android.content.SharedPreferences sp1020 =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONArray arr1020 = new org.json.JSONArray(
                    sp1020.getString("dlQueued", "[]"));
            if (arr1020.length() == 0) return dropped1020;
            java.util.Set<String> queued1020 = new java.util.HashSet<String>();
            for (int i1020 = 0; i1020 < arr1020.length(); i1020++)
                queued1020.add(arr1020.optString(i1020));
            org.json.JSONObject map1020 = new org.json.JSONObject(
                    sp1020.getString("dlMap", "{}"));
            java.util.Set<String> activeSources1020 = new java.util.HashSet<String>();
            java.util.Set<String> activeNames1020 = null;
            try {
                android.app.DownloadManager dm1020 = (android.app.DownloadManager)
                        getSystemService(android.content.Context.DOWNLOAD_SERVICE);
                // v13.27 (F9): filter in the provider — the status check
                // below stays as a guard.
                android.database.Cursor c1020 = dm1020.query(
                        new android.app.DownloadManager.Query()
                                .setFilterByStatus(android.app.DownloadManager.STATUS_RUNNING
                                        | android.app.DownloadManager.STATUS_PAUSED
                                        | android.app.DownloadManager.STATUS_PENDING));
                activeNames1020 = new java.util.HashSet<String>();
                if (c1020 == null) {
                    // v11.78: a null cursor is a failed query, not an
                    // empty result — fail closed (keep everything),
                    // the v11.76 Talks twin of this prune.
                    activeNames1020 = null;
                } else {
                    try {
                        int uriCol1020 = c1020.getColumnIndex(
                                android.app.DownloadManager.COLUMN_LOCAL_URI);
                        int srcCol1020 = c1020.getColumnIndex(
                                android.app.DownloadManager.COLUMN_URI);
                        int statusCol1020 = c1020.getColumnIndex(
                                android.app.DownloadManager.COLUMN_STATUS);
                        while (c1020.moveToNext()) {
                            int st1020 = statusCol1020 >= 0 ? c1020.getInt(statusCol1020) : -1;
                            if (st1020 >= 0
                                    && st1020 != android.app.DownloadManager.STATUS_RUNNING
                                    && st1020 != android.app.DownloadManager.STATUS_PAUSED
                                    && st1020 != android.app.DownloadManager.STATUS_PENDING) continue;
                            String src1020 = srcCol1020 >= 0 ? c1020.getString(srcCol1020) : null;
                            String uri1020 = uriCol1020 >= 0 ? c1020.getString(uriCol1020) : null;
                            // v13.59 (887 F-NEW-1, F945-3): the DM
                            // source may be the http form of a queued
                            // https URL — check the canonical form
                            // too, or a waiting job is skipped and
                            // its queue entry pruned while live.
                            if (src1020 != null && !queued1020.contains(src1020)
                                    && !queued1020.contains(SiteHttp.identityUrl(src1020))
                                    && (uri1020 == null || !uri1020.contains("Dhamma/"))) continue;
                            if (src1020 == null && uri1020 != null && !uri1020.contains("Dhamma/")) continue;
                            // v13.59 (887 F-NEW-1, F945-3): record
                            // the canonical form, so the source leg
                            // and DlLanded's live-source exclusion
                            // recognise the job under its https key.
                            if (src1020 != null) activeSources1020.add(SiteHttp.identityUrl(src1020));
                            if (uri1020 != null) {
                                // v12.14: decode before the basename (Room v11.81 /
                                // Talks v11.86 port) — a filename carrying a %XX
                                // escape arrives here as %25XX and never matched
                                // the dlMap name, so the prune dropped a live job.
                                String dec1020 = android.net.Uri.decode(uri1020);
                                if (dec1020 == null) dec1020 = uri1020;
                                int slash1020 = dec1020.lastIndexOf('/');
                                activeNames1020.add(slash1020 >= 0 ? dec1020.substring(slash1020 + 1) : dec1020);
                            }
                        }
                    } finally { c1020.close(); }
                }
            } catch (Exception ignored) { activeNames1020 = null; }
            if (activeNames1020 == null) return dropped1020;
            java.util.Iterator<String> it1020 = queued1020.iterator();
            while (it1020.hasNext()) {
                String url1020 = it1020.next();
                // v13.69 (audit-1369 F13): the landed leg is the shared
                // DlLanded definition now - Main had none; a landed
                // file's entry used to drop only by the no-live-job
                // rule below. Same drop, one definition everywhere.
                if (DlLanded.isDownloaded(this, url1020, activeNames1020, activeSources1020)) {
                    it1020.remove(); dropped1020.add(url1020); continue;
                }
                // v14.91 (slice 1749): the prune's live-source leg
                // reads the canonical form — activeSources1020 holds
                // identityUrl(src) (v13.59), but the lookup read the raw
                // queued url, so an http-spelled queued talk missed its
                // live job entirely: a Wi-Fi-waiting job (no destination
                // name yet) then had its hourglass pruned while still
                // live, and a re-tap could double-enqueue. Same
                // provenance as the mapped twins (legacy http dlMap keys,
                // v13.57 fallback, never migrated by v13.66; canonical
                // https keys, v13.59; absolute-http hrefs kept as-is,
                // 1737): one talk, two spellings.
                if (activeSources1020.contains(SiteHttp.identityUrl(url1020))) continue;
                // v14.91 (slice 1749): the prune no-live-job leg reads
                // the mapped name through the shared DlLanded.mappedName
                // (raw, then canonical https, then downgraded http) —
                // the Main twin of mapped1019. A talk downloaded under
                // one scheme and queued under the other missed its mapped
                // name and fell into the map-less branch, which drops the
                // entry when none of its filename candidates names a live
                // job: a live job landing under a hash-suffixed mapped
                // name (no candidate spells it) had its hourglass pruned
                // while still downloading. mappedName returns null when
                // no form matches where optString returned "", so the
                // null folds back to "" here — the length test below and
                // the map-less branch see exactly what the old raw read
                // gave them.
                String mapped1020 = DlLanded.mappedName(map1020, url1020);
                if (mapped1020 == null) mapped1020 = "";
                if (mapped1020.length() > 0) {
                    if (!activeNames1020.contains(mapped1020)) {
                        it1020.remove();
                        dropped1020.add(url1020);
                    }
                } else {
                    // v12.15: map-less branch (Room v11.85 / Talks v11.86 /
                    // Library v12.14 port) — see the header: a queued URL
                    // with no dlMap entry is a legacy remnant whose job is
                    // dead; drop it when none of its filename candidates
                    // names a live job. A live job's candidates always
                    // match (its source URL is guarded above, its
                    // destination basename is one of the candidates),
                    // and a failed query keeps everything (activeNames
                    // null returns before this loop).
                    boolean anyActive15 = false;
                    for (String cand15 : fileNameCandidates15(url1020)) {
                        if (cand15 != null && activeNames1020.contains(cand15)) { anyActive15 = true; break; }
                    }
                    if (!anyActive15) { it1020.remove(); dropped1020.add(url1020); }
                }
            }
            if (!dropped1020.isEmpty()) {
                org.json.JSONArray out1020 = new org.json.JSONArray();
                for (String u1020 : queued1020) out1020.put(u1020);
                sp1020.edit().putString("dlQueued", out1020.toString()).apply();
            }
            return dropped1020;
        } catch (Exception ignored) { return dropped1020; }
        }
    }

    // v12.15: filename candidates for a queued URL (Room fileNameCandidates,
    // v11.85) — the raw basename, the query/fragment-stripped basename, and
    // the percent-decoded basename — used by the map-less prune branch above.
    // v12.16: the enqueue destination form appended last (stripped +
    // ".mp3" when it lacks .mp3) — a wrapper-URL live job lands under
    // that name; see Room fileNameCandidates.
    private static String[] fileNameCandidates15(String url) {
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

    // v10.20: prune + paused-card refresh for Main, the v10.15/
    // v10.17/v10.19 shape — the no-bind refresh intent goes out only
    // when a dropped URL is the sounding talk (room: dropped ==
    // lastUrl; library: dlMap[dropped] file == lastLibPath file, read
    // fresh) and a session is live, so a dead session is never
    // started. The ↺ strip itself needs no intent: it re-syncs on
    // the 1s tick, so the pruned entry's ⏳ clears on the next pass.
    private void pruneMainQueuedAndRefreshCard1020() {
        try {
            java.util.Set<String> dropped1020 = pruneMainQueuedDownloads1020();
            if (dropped1020.isEmpty() || !AudioService.liveSession) return;
            android.content.SharedPreferences rsp1020 =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String lastUrl1020 = rsp1020.getString("lastUrl", "");
            String libPath1020 = rsp1020.getString("lastLibPath", "");
            org.json.JSONObject map1020 = new org.json.JSONObject(
                    rsp1020.getString("dlMap", "{}"));
            boolean cardTalk1020 = false;
            for (String d1020 : dropped1020) {
                if (SiteHttp.sameTalk(d1020, lastUrl1020)) { cardTalk1020 = true; break; }
                if (libPath1020.length() > 0) {
                    String fn1020 = map1020.optString(d1020, "");
                    if (fn1020.length() > 0 && fn1020.equals(new java.io.File(libPath1020).getName())) {
                        cardTalk1020 = true; break;
                    }
                }
            }
            if (cardTalk1020) {
                android.content.Intent rsv1020 = new android.content.Intent(MainActivity.this, AudioService.class);
                rsv1020.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                startService(rsv1020);
            }
        } catch (Exception ignored) {}
    }

    private void saveFavs() {
        getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                .putStringSet("favs", new HashSet<String>(favs)).apply();
    }

    private void applyThemeAndFont() {
        boolean night = Prefs.isNight(this);
        cachedNight = night;
        cachedNameSize = Prefs.nameSize(this);
        Fx.applySystemBars(this, night); // v13.07: bars follow the theme
        cachedGroupSize = Prefs.groupSize(this);
        cachedLang = Prefs.lang(this); // v3.84: synced with the rest, so the dip's swap re-arms it
        if (rootView != null)
            rootView.setBackgroundResource(night ? R.drawable.bg_gradient_night : R.drawable.bg_gradient_day);
        if (titleText != null) {
            titleText.setTextColor(night ? Color.parseColor("#C9A227") : Color.parseColor("#7A2E1F"));
            // v1.96: the app-name title and nav labels never followed
            // the font-size setting (only the search box did) — a
            // large-text listener got a small header and nav. Title
            // 24/28/34, nav labels 11/12/14; icons stay 22sp so the
            // three nav items still fit side by side.
            int f = Prefs.fontSize(this);
            titleText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, f == 0 ? 24 : (f == 1 ? 28 : 34));
            float navLbl = f == 0 ? 11 : (f == 1 ? 12 : 14);
            for (int id : new int[]{R.id.navMainLabel, R.id.navPlayerLabel, R.id.navSettingsLabel}) {
                TextView v = findViewById(id);
                if (v != null) v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, navLbl);
            }
        }
        if (themeToggle != null)
            themeToggle.setText(night ? "☀" : "🌙");
        if (searchField != null) {
            // v13.03: real liquid glass — GlassBlur blurs the content behind this surface.
            GlassBlur.attachSearch(searchField, night);
            searchField.setTextColor(night ? Color.WHITE : Color.parseColor("#3B2314"));
            searchField.setHintTextColor(night ? Color.parseColor("#AAAAAA") : Color.parseColor("#7A6A58"));
            int f = Prefs.fontSize(this);
            searchField.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, f == 0 ? 14 : (f == 1 ? 16 : 20));
        }
        // v3.76: UI language — nav labels and search hint read from Lang, so a
        // language picked in Settings shows here on return (applyThemeAndFont
        // runs on create and on return). The app-name title stays Burmese: it
        // is the app's name, and sayadaw/group names in the grid are content.
        TextView navMain = findViewById(R.id.navMainLabel);
        if (navMain != null) navMain.setText(Lang.t(this, "nav_home"));
        TextView navPlayer = findViewById(R.id.navPlayerLabel);
        if (navPlayer != null) navPlayer.setText(Lang.t(this, "nav_player"));
        TextView navSettings = findViewById(R.id.navSettingsLabel);
        if (navSettings != null) navSettings.setText(Lang.t(this, "nav_settings"));
        if (searchField != null) searchField.setHint(Lang.t(this, "search_hint"));
        refreshFavHint();
        if (navBar != null)
            // v13.03: real liquid glass — GlassBlur blurs the content behind this surface.
            GlassBlur.attach(navBar, night);
        int navText = night ? Color.WHITE : Color.parseColor("#3B2314");
        int gold = Color.parseColor("#C9A227");
        for (int id : new int[]{R.id.navPlayerIcon, R.id.navSettingsIcon}) {
            TextView v = findViewById(id);
            if (v != null) v.setTextColor(navText);
        }
        for (int id : new int[]{R.id.navPlayerLabel, R.id.navSettingsLabel}) {
            TextView v = findViewById(id);
            if (v != null) v.setTextColor(navText);
        }
        TextView mainIcon = findViewById(R.id.navMainIcon);
        TextView mainLabel = findViewById(R.id.navMainLabel);
        if (mainIcon != null) mainIcon.setTextColor(gold);
        if (mainLabel != null) mainLabel.setTextColor(gold);
        syncNavPlayerLiveTintNow(navText, gold); // v14.62: instant stamp on first paint / dip / return —
        // the melt lives on the 1s tick only (syncNavPlayerLiveTint below)
        if (adapter != null) adapter.notifyDataSetChanged();
        // v12.38: the grid was just rebound with the current
        // marks — seed all three tick trackers here (onResume and
        // the theme-dip swap), so the tick's rebind check
        // below never fires on a return with a live session.
        mainGridEnded = AudioService.liveEnded;
        mainGridSounding = AudioService.liveEnded ? null : soundingSayadawNameVisual();
        mainGridFailed = AudioService.liveTrackError; // v14.05
        mainGridBuffering = AudioService.liveBuffering; // v14.06
        refreshFavShelf(); // v13.74: shelf recolour on theme return
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v12.95: returning from the unknown-sources Settings page —
        // if install is now allowed, continue the pending update.
        UpdateChecker.onResume(this);
        // v1.81: count this screen as on top (see AppForeground).
        AppForeground.activityResumed();
        // v10.49: keep-screen-on was honoured on the Room, the Talks
        // chooser and the library (onCreate + onResume, else-clear)
        // but never here — a talk playing under Main's own
        // mini-player strip could dim the screen mid-listen despite
        // the setting, and a Settings toggle made while Main sat in
        // the back stack changed nothing until recreation. Same
        // live read as the Talks v1.60 onResume block: onResume
        // runs on creation too, so this one site covers fresh open
        // and back-stack return, and the else-clear drops a flag a
        // previous visit set when the setting is now off.
        if (Prefs.keepScreenOn(this))
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        pruneMainQueuedAndRefreshCard1020(); // v10.20: Main prune leg
        // v10.33: unconditional return card refresh, Main leg of the v1.74
        // family -- Main never binds (no bindService anywhere in this
        // file), so it has no bound refreshNotification like the Room /
        // library onResume paths, only the gated v10.20 prune-drop leg
        // above. A card-relevant change made while Main sat in the
        // back stack through a path that missed its funnel leg left a
        // PAUSED card stale until the next tap. The no-bind intent is
        // the v10.03 shape, liveSession-guarded, never starts a dead
        // service; the handler stopSelfs when trackless and a rebuild
        // with no change paints the identical card. No new UI, no toast.
        try {
            if (AudioService.liveSession) {
                android.content.Intent rsv1033 = new android.content.Intent(MainActivity.this, AudioService.class);
                rsv1033.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                startService(rsv1033);
            }
        } catch (Exception ignored) {}
        // v1.53: favourites reloaded on return — clearing them in
        // Settings used to leave stale ★ on the grid until restart.
        favs = new HashSet<String>(getSharedPreferences("dhamma", MODE_PRIVATE)
                .getStringSet("favs", new HashSet<String>()));
        // v7.29: favourites-only grid goes stale on return — the
        // reload above refreshes the set but nothing re-ran the
        // filter, so clearing favourites in Settings and coming
        // back left the cleared sayadaws sitting in a grid that
        // claims to show favourites only (the v1.53 reload only
        // fixed the old star marks, and the cards carry no star
        // any more). When favOnly is on and the shown set no
        // longer matches the set — cleared to empty, or holding
        // a name no longer favourited — re-run the silent filter
        // now; an emptied set drops the filter entirely, the
        // rotation guard's "never a blank filtered grid" rule.
        // Untouched when favOnly is off or the set still matches,
        // so ordinary returns never re-filter or re-scroll.
        if (favOnly && shown != null) {
            boolean stale = (favs == null || favs.isEmpty());
            if (!stale) {
                for (Sayadaw s : shown) {
                    if (s == null || !favs.contains(s.name)) { stale = true; break; }
                }
            }
            if (stale) {
                if (favs == null || favs.isEmpty()) favOnly = false;
                applyFilter();
                refreshFavHint();
            }
        }
        // v2.98: premium return theme/font dip — Fx.themeDip's own
        // note says onResume theme applications still swap
        // instantly, and only the explicit toggle dipped (v2.23).
        // Changing Day/Night or the font size in Settings and
        // coming back here used to recolour + resize the whole
        // screen in one frame, while the toggle tap dips. A real
        // change since this screen last applied its theme now
        // dips with the existing Fx.themeDip (140ms down to 0.55,
        // swap at the dip, 260ms rise to exactly alpha 1), so the
        // return lands softly like a toggle tap. Guards: the
        // onCreate pass just set the cache, so the first onResume
        // and rotations never dip (no diff); a return with nothing
        // changed stays instant, so back-stack navigation never
        // dips pointlessly; the favs reload above still runs at
        // once, and the dip's swap is applyThemeAndFont itself,
        // which rebinds the grid — no second call is needed.
        // v3.84: premium return language dip, completing the v2.98
        // return-dip family (theme/font) on the one Settings change
        // it never covered — picking a language in Settings (which
        // dips on its own screen, v3.76) used to swap this screen's
        // nav labels + search hint in one frame on return, while a
        // theme/font change dipped. A real language change since
        // this screen last applied now dips with the existing
        // Fx.themeDip, whose swap is applyThemeAndFont itself, which
        // re-reads the Lang labels. Guards: the onCreate pass runs
        // applyThemeAndFont, so the first onResume never dips (and
        // the null guard covers any path where it did not run yet);
        // a return with nothing changed stays instant; the dip's
        // swap re-syncs cachedLang, so a dipped return never
        // re-dips on the next return.
        boolean themeChanged = Prefs.isNight(this) != cachedNight
                || Prefs.nameSize(this) != cachedNameSize
                || Prefs.groupSize(this) != cachedGroupSize
                || (cachedLang != null && !Prefs.lang(this).equals(cachedLang));
        if (themeChanged && rootView != null) {
            Fx.themeDip(rootView, new Runnable() {
                @Override public void run() { applyThemeAndFont(); }
            });
        } else {
            applyThemeAndFont();
        }
        glideToChangedSayadawOnReturn();
        startMainPulseTick();
        // v10.08: listen for download completions while foreground —
        // card-only hook (Main has no rows to rescan; its strip
        // already ticks every second). On fire, the guarded refresh
        // below rebuilds a paused card whose ⬇/⏳ mark just changed
        // (same class as the v10.02/v10.03/v10.05/v10.06/v10.07
        // paused-card syncs; the Room and Talks legs already do
        // this for their own screens). Any completion fires it
        // (the broadcast is device-wide), but the card send is
        // gated on the finished job being the sounding talk, so
        // unrelated system downloads never re-post the card and
        // never start a dead service (liveSession guard, v10.03
        // shape). The card leg toasts nothing — the card's own
        // mark flipping ⏳→⬇ is the feedback; a FAILED job
        // toasts via the receiver's own fail block (v12.35).
        if (!mainDlCompleteRegistered) {
            if (mainDlCompleteReceiver == null) {
                mainDlCompleteReceiver = new android.content.BroadcastReceiver() {
                    @Override public void onReceive(android.content.Context c, android.content.Intent in) {
                        pruneMainQueuedAndRefreshCard1020(); // v10.20: Main prune leg
                        // v12.35: a FAILED job used to die silently on
                        // this screen — the last receiver without the
                        // failed-download toast (Talks v12.29, Room +
                        // Library v12.30). A queued download that fails
                        // (Wi-Fi wait, then a network drop mid-file)
                        // while Main is foreground lost its ⏳ — the
                        // live strip's global " ⏳ N" drops on the
                        // next 1s tick and the resume strip's ⏳ mark
                        // clears after the prune above — with no file
                        // and no word why, and only one screen's
                        // receiver is registered at a time, so while
                        // Main is on top nobody toasted the failure.
                        // A failure now toasts "download_failed" +
                        // the talk title, the same string shape as
                        // the enqueue catch. Separate try, BEFORE the
                        // card-sync block: that block returns early
                        // on !liveSession, but a download can fail
                        // while nothing plays — the toast must not be
                        // gated on a live session. Gated on the
                        // source URL being a talk this app queued
                        // (dlMap only — no talk list lives on this
                        // screen, the Library v12.30 shape), so an
                        // unrelated system download failing while
                        // this screen is open never toasts here; the
                        // prune above touches dlQueued only, never
                        // dlMap, so the gate still reads true after
                        // it. Title falls back COLUMN_TITLE -> URL.
                        // The broadcast fires once per job, so the
                        // toast fires once. Success toasts nothing —
                        // the strip's ⏳ N drop is the feedback.
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
                                                        // v13.59 (887 F-NEW-1, F945-2): dlMap
                                                        // keys are https — the DM source may
                                                        // be the http form.
                                                        oursFail = mapFail.has(failUrl) || mapFail.has(SiteHttp.identityUrl(failUrl));
                                                    } catch (Exception ignored) {}
                                                    if (oursFail) {
                                                        String failTitle = null;
                                                        int titleColFail = curFail.getColumnIndex(android.app.DownloadManager.COLUMN_TITLE);
                                                        if (titleColFail >= 0) failTitle = curFail.getString(titleColFail);
                                                        if (failTitle == null || failTitle.length() == 0) failTitle = failUrl;
                                                        android.widget.Toast.makeText(MainActivity.this,
                                                                Lang.t(MainActivity.this, "download_failed") + failTitle,
                                                                android.widget.Toast.LENGTH_LONG).show();
                                                    }
                                                }
                                            }
                                        }
                                    } finally { curFail.close(); }
                                }
                            }
                        } catch (Exception ignored) {}
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
                                                    while (keys0.hasNext()) {
                                                        String k = keys0.next();
                                                        if (landed.equals(map0.optString(k, ""))) { srcUrl = k; break; }
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
                            // v13.59 (887 F-NEW-1, F945-2): identity
                            // compare — the DM source may be the http
                            // form of the stored https talk.
                            boolean cardTalk = SiteHttp.sameTalk(srcUrl, rsp.getString("lastUrl", ""));
                            if (!cardTalk) {
                                String libPath = rsp.getString("lastLibPath", "");
                                if (libPath.length() > 0) {
                                    org.json.JSONObject dlMapCard = new org.json.JSONObject(rsp.getString("dlMap", "{}"));
                                    String fn = dlMapCard.optString(srcUrl, "");
                                    if (fn.length() == 0) fn = dlMapCard.optString(SiteHttp.identityUrl(srcUrl), "");
                                    cardTalk = fn.length() > 0 && fn.equals(new java.io.File(libPath).getName());
                                }
                            }
                            if (cardTalk) {
                                android.content.Intent rsv = new android.content.Intent(MainActivity.this, AudioService.class);
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
                    registerReceiver(mainDlCompleteReceiver, f, android.content.Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(mainDlCompleteReceiver, f);
                }
                mainDlCompleteRegistered = true;
            } catch (Exception ignored) {}
        }
    }

    // v3.98: Main fresh-open landing on the sounding sayadaw — the
    // mirror of the Talks fresh-open auto-scroll (v3.91) and the
    // library fresh-open glide (v3.95) on the one list surface that
    // never had it. The v3.97 return-glide deliberately never fires
    // on a cold start (its anchor is null until Main has ever
    // paused), so a listener who backed all the way out of the app
    // while a talk played — activities finished, foreground service
    // still sounding — and then relaunched it landed at the top of
    // the grid, with the sounding sayadaw's card possibly screens
    // away, even though the same situation lands on the talk in
    // Talks and on the file in the library. On a fresh creation
    // only, when a live Room session exists, the grid now opens on
    // that sayadaw's card instead. Guards mirroring v3.91/v3.97: a
    // stopped session never lands (liveSession false — the service
    // sets it only while a session is loaded, and clears it on
    // destroy, so a dead process can never land on a stale name);
    // a library session never lands (lastKind must be "room" — the
    // nav Player button already owns that session's return); the
    // match is the sayadaw name itself, so a sayadaw with no card
    // in the shown list simply leaves the grid at the top; a
    // rotation restore never reaches here (its own setSelection
    // above still owns that return, and the v1.55 scroll rule is
    // untouched); a back-stack return never reaches here either
    // (savedInstanceState is null only on a fresh creation, and
    // the v3.97 return-glide still owns returns). setSelection,
    // not a glide, on purpose — the same reasoning as v3.91: the
    // grid was never visible (the v2.07 root launch fade is still
    // fading in, and the v2.07 row cascade pours the visible rows
    // in place around the landed card), so the jump is invisible
    // and the open reads as landing directly on the sounding
    // sayadaw. No new UI element, no mark, Main spec untouched
    // (App Name + search + Day/Night + 4-col grid + bottom nav).
    private void landOnPlayingSayadawOnFreshOpen() {
        if (gridView == null || shown.isEmpty()) return;
        // v10.43: bound-trackless stop — liveSession stays true but the mirror is false, so never land on a stopped talk's card.
        if (!AudioService.liveSession || !AudioService.liveHasTrack) return;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (!"room".equals(sp.getString("lastKind", ""))) return;
            String cur = sp.getString("lastName", "");
            if (cur.length() == 0) return;
            for (int i = 0; i < shown.size(); i++) {
                if (cur.equals(shown.get(i).name)) {
                    gridView.setSelection(i);
                    break;
                }
            }
        } catch (Exception ignored) {}
    }

    // v3.97: Main return-glide to a changed sayadaw — the mirror of
    // the Talks return-scroll (v3.94) and the library return-glide
    // (v3.96) on the one list surface that never had it. Main used
    // to sit at its pre-leave browsed position after a listener
    // changed sayadaws in the Room (Next/drawer pick, or a new
    // sayadaw's Talks) and pressed back, with the sounding
    // sayadaw's card possibly screens away in the 4-col grid. On a
    // back-stack return only, when the live Room session's sayadaw
    // differs from the anchor taken when Main was left, the grid
    // now glides (GridView smooth scroll) to that sayadaw's card.
    // Guards mirroring v3.94/v3.96: a stopped session never glides
    // (liveSession false); a library session never glides
    // (lastKind must be "room"); an unchanged sayadaw never glides
    // (the anchor comparison), so Settings/theme/font/language
    // returns and plain Talks browsing leave the browsed position
    // exactly alone; a sayadaw filtered out by the search box has
    // no visible card and never scrolls; an already-visible card
    // never scrolls (visible range re-checked inside the post,
    // after layout and after the return theme-dip rebind); rotation
    // never glides, because onPause re-anchors to the current
    // sayadaw and the v1.55 scroll restore still owns that return.
    // No new UI element, no mark, Main spec untouched (App Name +
    // search + Day/Night + 4-col grid + bottom nav).
    private void glideToChangedSayadawOnReturn() {
        if (gridView == null || shown.isEmpty() || mainReturnAnchorName == null) return;
        // v10.43: bound-trackless stop — liveSession stays true but the mirror is false, so never glide to a stopped talk's card.
        if (!AudioService.liveSession || !AudioService.liveHasTrack) return;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (!"room".equals(sp.getString("lastKind", ""))) return;
            String cur = sp.getString("lastName", "");
            if (cur.length() == 0 || cur.equals(mainReturnAnchorName)) return;
            int idx = -1;
            for (int i = 0; i < shown.size(); i++) {
                if (cur.equals(shown.get(i).name)) { idx = i; break; }
            }
            if (idx < 0) return;
            final int target = idx;
            gridView.post(new Runnable() {
                @Override public void run() {
                    if (gridView == null) return;
                    int first = gridView.getFirstVisiblePosition();
                    int last = gridView.getLastVisiblePosition();
                    if (target < first || target > last)
                        gridView.smoothScrollToPosition(target);
                }
            });
        } catch (Exception ignored) {}
    }

    // v3.99: sounding-sayadaw card breath — the visible feature this
    // run. Main was the one list surface with no sign of who is
    // sounding: the Talks list marks its playing row, the library
    // breathes on its playing file (v2.25) and the Room sheet
    // breathes on its playing title (v2.27), but the grid that the
    // v3.97/v3.98 glides land on stayed identical for every card,
    // so a listener back on Main could not tell at a glance which
    // sayadaw the sound belongs to. The sounding card's name now
    // breathes 1.00->0.55 on the same 900ms reverse loop the Room
    // sheet and library use, alpha only, so the card never shifts
    // and the portrait is untouched. No new UI element, Main spec
    // untouched (App Name + search + Day/Night + 4-col grid +
    // bottom nav). Semantics mirror v3.97/v3.98 exactly: the
    // sounding sayadaw is the live Room session (liveSession +
    // lastKind "room" + lastName), read from prefs/statics only —
    // Main deliberately never binds (v1.81), and a paused session
    // still counts as live, exactly as the glides treat it, so a
    // pause issued from the notification shade never strands a
    // breath that contradicts the glide state. Only a stopped
    // session clears it — liveSession false, or (v10.42) a
    // bound-trackless stop, where liveSession stays true but
    // the v10.40 liveHasTrack mirror is false. Hygiene, in the
    // library/Room pulse model: the bind arms the breath at once
    // for a sounding card and leaves a running breath alone on a
    // same-sayadaw rebind (star tap), while a view recycled to a
    // different sayadaw drops its breath instantly at alpha 1;
    // the 1s tick (prefs/statics only, no bind) corrects anything
    // the bind cannot see — a session stopped or switched while
    // Main sat on top, or a card that only became visible after
    // layout — and a filtered-out sounding sayadaw simply never
    // breathes. The pulse lives on the name view alone, never the
    // card root, so it cannot fight the v2.08 press squash, the
    // v2.09 star morph or the v2.07 entrance pour, which all live
    // on other views/channels. onPause stops the tick and the
    // breath instantly, so a detached view never keeps breathing.
    private String soundingSayadawName() {
        // v10.42: bound-trackless leg of the live rule (the
        // v10.40 audit's named candidate, confirmed for the
        // sounding helper): liveSession alone stays true when
        // a bound Room/Library activity in the back stack
        // keeps a stopped service alive, with stale prefs.
        // v13.12: this helper feeds ONE routing site — the
        // card-tap-to-Room branch below; the glides inline
        // their own liveSession/liveHasTrack/lastKind checks
        // and never call it (the old two-call-sites claim
        // was stale). The three VISUAL sites (nav glow,
        // card pulse, grid ▶ mark) use the visual twin below
        // instead — a library session must still show on the
        // grid, but must never route a card tap into the Room;
        // its card tap routes to the library instead, via the
        // visual twin (see the card tap). Paused/buffering/
        // ended untouched (play() sets the mirror synchronously).
        if (!AudioService.liveSession || !AudioService.liveHasTrack) return null;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (!"room".equals(sp.getString("lastKind", ""))) return null;
            String cur = sp.getString("lastName", "");
            return cur.length() == 0 ? null : cur;
        } catch (Exception ignored) { return null; }
    }

    // v11.94: visual twin of soundingSayadawName (the v11.78
    // audit's deferred item): a library session showed NO
    // ▶ mark, NO pulse and NO nav glow on Main — the Room-
    // only helper returned null for lastKind "lib", so a talk
    // playing from the downloaded library read as silence on
    // the home grid while the mini strip already named it.
    // Same live gate (liveSession + liveHasTrack + !liveEnded, v12.86), then the
    // kind decides the name: "room" reads lastName exactly
    // as the Room helper does, "lib" reads Fx.stripLibSayadaw
    // (the dlSayadaw map written at download time — the same
    // source the strip already shows, null for a pre-v5.45
    // download or an ambiguous file, so an unrecorded file
    // keeps today's unmarked card instead of a guessed name).
    // VISUAL SITES ONLY — nav glow, card pulse, grid ▶ mark.
    // Never the routing sites: a card tap on a library-
    // sounding sayadaw still opens its Talks chooser (the
    // strip deliberately routes lib returns to
    // DownloadedActivity, and the Room's autoUrl re-entry
    // expects a Room talk), and the glides stay Room-only.
    private String soundingSayadawNameVisual() {
        // v12.86: an ended talk is not sounding — gate the helper itself,
        // so the nav glow (:985) and the title glide (:1619) stop treating
        // a finished talk as live; a replay clears liveEnded and it sounds again.
        if (AudioService.liveEnded || !AudioService.liveSession || !AudioService.liveHasTrack) return null;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String kind = sp.getString("lastKind", "");
            if ("room".equals(kind)) {
                String cur = sp.getString("lastName", "");
                return cur.length() == 0 ? null : cur;
            }
            if ("lib".equals(kind)) return Fx.stripLibSayadaw(this, sp);
            return null;
        } catch (Exception ignored) { return null; }
    }

    private void startMainPulseOn(android.view.View v) {
        mainPulseView = v;
        mainPulse = android.animation.ObjectAnimator.ofFloat(v, "alpha", 1f, 0.55f);
        mainPulse.setDuration(900);
        mainPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        mainPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        mainPulse.start();
    }

    private void stopMainPulse() {
        if (mainPulse != null) mainPulse.cancel();
        mainPulse = null;
        if (mainPulseView != null) mainPulseView.setAlpha(1f);
        mainPulseView = null;
    }

    private void armMainPulseFor(android.view.View nameView, String name) {
        // v4.29: a finished talk is not sounding — the card kept
        // breathing after an autoplay-off end, because the v3.99
        // rule counts a paused session as live. Gate the pulse
        // only (glides still use soundingSayadawName untouched):
        // a replay clears liveEnded and the breath resumes.
        if (AudioService.liveEnded) {
            if (mainPulseView == nameView) stopMainPulse();
            return;
        }
        // v11.94: visual helper — a library session breathes
        // its card too (see soundingSayadawNameVisual).
        String sounding = soundingSayadawNameVisual();
        if (sounding != null && sounding.equals(name)) {
            if (mainPulseView != nameView) {
                stopMainPulse();
                startMainPulseOn(nameView);
            }
        } else if (mainPulseView == nameView) {
            stopMainPulse();
        }
    }

    private void syncMainPulseFromVisibleCards() {
        // v4.29: same finished-talk pulse gate as armMainPulseFor
        // above — the 1s tick stops a breath the bind cannot see
        // ending (a talk finishing while Main sat on top).
        if (AudioService.liveEnded) { stopMainPulse(); return; }
        // v11.94: visual helper — the tick keeps a library
        // session's breath alive, not only a Room one.
        String sounding = soundingSayadawNameVisual();
        if (sounding == null || gridView == null) { stopMainPulse(); return; }
        android.view.View found = null;
        for (int i = 0; i < gridView.getChildCount(); i++) {
            android.view.View child = gridView.getChildAt(i);
            if (child == null) continue;
            android.view.View nv = child.findViewById(R.id.name);
            if (nv instanceof android.widget.TextView) {
                // v11.03: the playing card's name text carries the v5.44
                // "▶ " mark, so a plain equals() here never matched the
                // very card the bind had just armed — the first 1s tick
                // then stopped the breath it was meant to keep alive,
                // and the sounding card went still a second after it
                // started pulsing until the next rebind. Strip the
                // mark before comparing, so the tick keeps the pulse
                // the bind armed; the mark itself is untouched.
                String t = ((android.widget.TextView) nv).getText().toString();
                if (t.startsWith("▶ ")) t = t.substring(2);
                if (sounding.equals(t)) { found = nv; break; }
            }
        }
        if (found == null) { stopMainPulse(); return; }
        if (mainPulseView != found) {
            stopMainPulse();
            startMainPulseOn(found);
        }
    }

    // v4.12: the Mp3 Player nav item glows gold while a Room session
    // is live (same live rule as the sounding-card breath: live
    // Room session + lastKind "room" + a saved name), so Main itself
    // says at a glance that something is sounding — the nav used to
    // read identical whether a talk played or not. No new view: the
    // existing icon/label just take the gold the home item already
    // wears. A stopped session (liveSession false) drops it back to
    // the plain nav colour; the 1s tick re-syncs it, so a stop or a
    // switch landing while Main sits on top is corrected within a
    // second instead of waiting for a theme application.
    // v14.62 (slice 1719): the glow used to snap — the 1s tick
    // swapped both text colours in one frame (bare setTextColor)
    // the moment a session started or ended, while everything
    // around the nav already moves on that same beat (the strip
    // slides in v14.14, the shelf pours v14.15, the ring
    // crossfades v14.16, the tile crossfades v14.31, the strip
    // glyph flips v14.20, the strip text fades v14.21, the strip
    // photo crossfades v14.22) — the nav tint was the one
    // un-choreographed element of the arrival. The icon and the
    // label now melt gold<->navText through Fx.crossfadeTextColor
    // (v14.34, 250ms, same rhythm as the sleep line and the
    // shuffle tint): change-only via the helper's recorded target,
    // so the steady 1s tick is a no-op and never restarts the
    // melt, and a rapid start-then-stop inside one melt cancels
    // and melts from the current painted colour. The v14.16
    // settled decision does NOT bar this: no label is faded, only
    // the text colour is interpolated (alpha stays 1, text
    // unchanged), so the nav cannot flash. The icon and the label
    // melt together — one call, one target, same duration — so
    // they never disagree mid-flight. The tick path owns the melt;
    // first paint / theme-dip swap / return stamps instantly
    // through syncNavPlayerLiveTintNow below (the v14.39
    // setTextColorNow pattern), so a rotation restores still and
    // the dip owns the motion. Visual only; the live predicate,
    // the colours and both call sites are untouched.
    private void syncNavPlayerLiveTint(int navText, int gold) {
        // v11.94: visual helper — the nav glows gold for a
        // library session too (see soundingSayadawNameVisual).
        boolean live = soundingSayadawNameVisual() != null;
        TextView ic = findViewById(R.id.navPlayerIcon);
        TextView lb = findViewById(R.id.navPlayerLabel);
        if (ic != null) Fx.crossfadeTextColor(ic, live ? gold : navText);
        if (lb != null) Fx.crossfadeTextColor(lb, live ? gold : navText);
    }

    // v14.62 (slice 1719): the instant writer for the nav Player
    // tint — applyThemeAndFont (create / theme-dip swap / return)
    // must land the glow at once, never melt it: a rotation
    // restores still, the dip owns the motion, and a language
    // change repaints without a colour sweep. Stamps both views
    // at once AND records the target, cancelling any in-flight
    // tick melt first (setTextColorNow removes the map entry
    // BEFORE the cancel, so the cancelled animator's end skips
    // its stale stamp — the v14.42 discipline). The bare
    // setTextColor loop just above still paints navText first;
    // this call is the last writer in applyThemeAndFont, so the
    // recorded target always agrees with the painted colour.
    private void syncNavPlayerLiveTintNow(int navText, int gold) {
        boolean live = soundingSayadawNameVisual() != null;
        TextView ic = findViewById(R.id.navPlayerIcon);
        TextView lb = findViewById(R.id.navPlayerLabel);
        if (ic != null) Fx.setTextColorNow(ic, live ? gold : navText);
        if (lb != null) Fx.setTextColorNow(lb, live ? gold : navText);
    }

    // v6.00: sayadaw portrait on the mini strip — the strip was the
    // last now-playing surface still text-only: Room, drawer, card
    // and grid all show the face, but back on Main a listener saw
    // only title over name, like a pre-artwork mini-player. The
    // portrait is the catalog photo for the session's sayadaw
    // (exact-name match against the already-loaded `all` list, the
    // same source the grid binds from — no new catalog parse, no
    // new read on the 1s tick), shown through ImageLoader.load so
    // a never-cached portrait reads as that sayadaw's own gold
    // initial circle until its download lands, exactly like a grid
    // row. A library session with no recorded sayadaw (pre-v5.45
    // download, libSd null) keeps today's text-only strip, never a
    // guessed face. Called at every strip visibility edge below,
    // so a hidden strip never leaves a stale face behind, and a
    // session switch lands the new face on the same tick.
    private void syncStripPhoto(String name) {
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
        if (ringing != lastStripPhotoRing) {
            lastStripPhotoRing = ringing;
            if (ring != null) {
                // v14.16 (slice 1670): the ring crossfades in/out
                // instead of snapping — see Fx.crossfadeRing.
                Fx.crossfadeRing(ring, ringing ? R.drawable.shelf_ring_playing : 0);
            }
        }
        if (name == null || name.length() == 0) {
            stripPhotoName = null;
            if (ring != null) ring.setVisibility(android.view.View.GONE);
            else pv.setVisibility(android.view.View.GONE);
            return;
        }
        if (ring != null) ring.setVisibility(android.view.View.VISIBLE);
        else pv.setVisibility(android.view.View.VISIBLE);
        if (name.equals(stripPhotoName)) return;
        boolean firstPhoto = stripPhotoName == null;
        stripPhotoName = name;
        String url = "";
        try {
            for (Sayadaw s : all)
                if (s.name.equals(name)) { url = s.photo; break; }
        } catch (Exception ignored) {}
        final String photoUrl = url;
        // v14.22 (slice 1676): the photo crossfades on a sayadaw
        // change instead of swapping — see Fx.crossfadeStripPhoto.
        Fx.crossfadeStripPhoto((CircleImageView) pv, photoUrl, firstPhoto, new Runnable() {
            @Override public void run() {
                ImageLoader.load(MainActivity.this, photoUrl, name, (CircleImageView) pv);
            }
        });
    }

    // v4.15: mini now-playing strip above the nav (Spotify-style) —
    // visible only while a session is live, so the minimal main
    // stays minimal when nothing plays. Room sessions show
    // "sayadaw — talk" (same live rule as the nav glow: liveSession
    // + lastKind room + saved name/title); library sessions show
    // the downloaded file's title. Tap the strip to return to the
    // player (same branch as the nav Player item, morphed on the
    // strip itself); ⏯ toggles via the service intent with no bind
    // (Main deliberately never binds, v1.81) and claims no state —
    // the combined glyph never lies about playing vs paused;
    // long-press stops the session outright. The 1s tick re-syncs
    // it, so a stop or switch landing while Main sits on top is
    // corrected within a second, and a GONE strip takes no space.
    // v14.70 (slice 1728): the Main resume strip's title melts on a
    // structural change instead of snapping — the 1s tick re-runs
    // syncNowStrip in resume mode, and the title carries the download
    // mark (stripResumeDownloadMark / the lib branch's inline check),
    // so a download landing while Main sits open flipped its ⏳ to ⬇
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
    private String resumeStripTitle70 = null;
    private void meltResumeStripTitle70(android.widget.TextView tx, String want) {
        if (tx == null || want == null) return;
        Object aObj70 = tx.getTag(R.id.stripTextAnim);
        if (aObj70 instanceof android.animation.Animator) ((android.animation.Animator) aObj70).cancel();
        String prev70 = resumeStripTitle70;
        resumeStripTitle70 = want;
        if (prev70 == null) { tx.setText(want); return; }
        if (prev70.equals(want)) return;
        tx.setText(want);
        if (tx.getVisibility() == android.view.View.VISIBLE && tx.getAlpha() >= 0.999f) Fx.fadeIn(tx, 250);
    }

    private void syncNowStrip() {
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

        // v4.39: the mini-player was the last now-playing surface
        // ignoring the font-size setting — Room title, Up Next
        // strip, Talks rows and the search box all scale, but the
        // strip pinned its title/sub at 14/12sp in XML, so a
        // large-text listener got a small mini-player. Title uses
        // the Talks-row title step (14/16/20), sub one step below
        // (12/14/16); glyphs stay 22sp like the nav icons, and the
        // 1s tick re-applies it, so a Settings change lands on
        // return without recreating the screen.
        int fs = Prefs.fontSize(this);
        if (tx != null) tx.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fs == 0 ? 14 : (fs == 1 ? 16 : 20));
        if (sb != null) sb.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fs == 0 ? 12 : (fs == 1 ? 14 : 16));
        android.content.SharedPreferences sp =
                getSharedPreferences("dhamma", MODE_PRIVATE);
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
            // v4.60: continue-listening strip — a dead session used
            // to leave Main with no now-playing strip at all, even
            // though a saved spot (>5s, the same threshold pause()
            // saves by and the Room resumes by) was waiting: after
            // a reboot or a system kill the listener had to find
            // the talk again through the nav Player. When the last
            // session was a Room talk with such a spot, the strip
            // now shows it instead of vanishing — title over
            // sayadaw, with the saved mm:ss and the resume cue —
            // and its tap re-opens the Room, which resumes from
            // that spot (Room's autoUrl + saved-position path).
            // An explicit stop already clears the spot (v1.57),
            // so a stopped talk never shows here; a library
            // session keeps the old behaviour (strip hidden, the
            // library's own row resume untouched). No new view:
            // Next is hidden in this mode (advancing needs a live
            // track) and the progress bar reads empty, since no
            // duration survives a dead session.
            resumeStripMode = false;
            String rKind = sp.getString("lastKind", "");
            String rName = sp.getString("lastName", "");
            String rTitle = sp.getString("lastTitle", "");
            String rPage = sp.getString("lastPage", "");
            String rUrl = sp.getString("lastUrl", "");
            int rPos = sp.getInt("lastPos", 0);
            String rSavedUrl = sp.getString("lastPosUrl", "");
            boolean rMatch = false;
            if (rUrl.length() > 0 && rSavedUrl.length() > 0) {
                if (SiteHttp.sameTalk(rSavedUrl, rUrl)) rMatch = true;
                else {
                    try {
                        org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
                        String fn = map.optString(rUrl, "");
                        // v13.62 (F-1033-B-3): legacy http dlMap key
                        // misses the raw lookup — try the identity form.
                        if (fn.length() == 0) fn = map.optString(SiteHttp.identityUrl(rUrl), "");
                        if (fn.length() > 0 && rSavedUrl.endsWith("/" + fn)) rMatch = true;
                    } catch (Exception ignored) {}
                }
            }
            // v4.63: continue-listening strip for a dead LIBRARY
            // session — the mirror of the v4.60 Room strip: a
            // reboot or system kill used to leave Main with no
            // strip at all after a downloaded file was paused,
            // even though its saved spot (>5s, same threshold)
            // was waiting. Same match rule as the library's own
            // row resume (file path, or its Room URL via the
            // dlMap reverse lookup), same presentation (↺ title
            // over the offline sub line + mm:ss resume cue, ★
            // when it applies, ▶, Next hidden, progress empty).
            // Tap and ▶ open the library with autoPlayPath, so
            // the file resumes from the spot in one tap; the
            // live lib tap below still only glides, because that
            // track is already playing.
            if ("lib".equals(rKind)) {
                String lTitle = sp.getString("lastLibTitle", "");
                String lPath = sp.getString("lastLibPath", "");
                boolean lMatch = false;
                // v14.01 (Settings F1 library half, all four strips):
                // a 0-byte file is not downloaded — the strip used to
                // claim ⬇ for a dead stub. The in-progress exception
                // keeps the v11.58 order (a live job reads ⏳ before
                // any exists/length check, like stripResumeDownloadMark).
                if (lPath.length() > 0 && rPos > 5000 && rSavedUrl.length() > 0
                        && new java.io.File(lPath).isFile()
                        && (new java.io.File(lPath).length() > 0
                            || Fx.stripInProgressDlFilenames(MainActivity.this, sp).contains(new java.io.File(lPath).getName()))) {
                    if (rSavedUrl.equals(lPath)) lMatch = true;
                    else {
                        try {
                            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
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
                    syncStripPhoto(null);
                    resumeStripTitle70 = null;
                    Fx.hideStrip(strip);
                    return;
                }
                boolean lFav = false;
                try {
                    java.util.Set<String> ft = sp.getStringSet("favTalks", null);
                    if (ft != null) {
                        String fn = new java.io.File(lPath).getName();
                        org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
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
                int lDur = Fx.stripResumeDuration(MainActivity.this, sp, rSavedUrl, lPath);
                String lClock = Fx.stripClockSuffix(rPos, lDur, Prefs.stripClockRemaining(MainActivity.this));
                String lClockTxt = lClock.length() > 0 ? lClock : " · " + lTime;
                String want70 = "↺ " + lTitle + (lFav ? " ★" : "") + (Fx.stripInProgressDlFilenames(MainActivity.this, sp).contains(new java.io.File(lPath).getName()) ? " ⏳" : " ⬇");
                meltResumeStripTitle70(tx, want70);
                // v5.46: a dead library strip names its recorded
                // sayadaw like the live strip since v5.45, in the
                // Room strip's "name · mm:ss မှ ဆက်နားထောင်ရန်"
                // shape; an unrecorded download keeps today's
                // generic line, never a guessed name.
                String libSd = Fx.stripLibSayadaw(MainActivity.this, sp);
                if (sb != null) sb.setText(libSd != null ? libSd + lClockTxt + Lang.t(MainActivity.this, "resume_from") : Lang.t(MainActivity.this, "downloaded_talk") + lClockTxt + Lang.t(MainActivity.this, "resume_from"));
                if (tg != null) tg.setText("▶");
                if (nx != null) Fx.crossfadeStripBtn(nx, false);
                // v7.31: Previous hides with Next in resume mode —
                // stepping back needs a live track exactly like
                // advancing does, and its handler had no resume
                // branch, so a ⏮ tap on a ↺ strip opened the
                // Room/library with ACTION_PREV and played a
                // different talk instead of resuming this one.
                android.view.View pv0 = findViewById(R.id.nowStripPrev);
                if (pv0 != null) Fx.crossfadeStripBtn(pv0, false);
                android.widget.ProgressBar lPb = findViewById(R.id.nowStripProgress);
                if (lPb != null) Fx.stripResumeProgress(lPb, rPos, lDur);
                resumeStripMode = true;
                syncStripPhoto(libSd);
                Fx.showStrip(strip);
                return;
            }
            if (!"room".equals(rKind) || rName.length() == 0 || rTitle.length() == 0
                    || rPage.length() == 0 || rPos <= 5000 || !rMatch) {
                syncStripPhoto(null);
                resumeStripTitle70 = null;
                Fx.hideStrip(strip);
                return;
            }
            boolean rFav = false;
            try {
                java.util.Set<String> ft = sp.getStringSet("favTalks", null);
                if (ft != null) rFav = ft.contains(rUrl);
            } catch (Exception ignored) {}
            int rSec = rPos / 1000;
            String rTime = Fx.stripResumeTime(rSec);
            // v14.03 (slice 1098): the ↺ strip gains its total — the stored
            // duration (v13.96) turns the position-only sub-line into
            // "pos / total" (or "−left" in remaining mode, the live strip's
            // own clock rule) and fills the bar; unknown duration keeps
            // today's exact line and empty bar.
            int rDur = Fx.stripResumeDuration(MainActivity.this, sp, rUrl, null);
            String rClock = Fx.stripClockSuffix(rPos, rDur, Prefs.stripClockRemaining(MainActivity.this));
            String want70 = "↺ " + rTitle + (rFav ? " ★" : "") + Fx.stripResumeDownloadMark(MainActivity.this, sp, rUrl);
            meltResumeStripTitle70(tx, want70);
            if (sb != null) sb.setText(rName + (rClock.length() > 0 ? rClock : " · " + rTime) + Lang.t(MainActivity.this, "resume_from"));
            if (tg != null) tg.setText("▶");
            if (nx != null) Fx.crossfadeStripBtn(nx, false);
            // v7.31: same Previous hide as the library resume
            // branch above — a ↺ strip never shows ⏮.
            android.view.View pv0 = findViewById(R.id.nowStripPrev);
            if (pv0 != null) Fx.crossfadeStripBtn(pv0, false);
            android.widget.ProgressBar rPb = findViewById(R.id.nowStripProgress);
            if (rPb != null) Fx.stripResumeProgress(rPb, rPos, rDur);
            resumeStripMode = true;
            syncStripPhoto(rName);
            Fx.showStrip(strip);
            return;
        }
        resumeStripMode = false;
        resumeStripTitle70 = null; // v14.70: live branch owns tx via fadeStripText from here
        // v15.26 (slice 1791): the strip Next/Prev buttons melt in/out instead of popping on the resume <-> live mode flip — both buttons ride Fx.crossfadeStripBtn from here (the two resume branches above hide through the same helper); the toggle glyph + title stay instant per v14.16.
        if (nx != null) Fx.crossfadeStripBtn(nx, true);
        // v7.31: Previous returns with Next on a live session
        // (it was never hidden by layout, only left visible
        // through resume mode by the missing sync above).
        android.view.View pv0 = findViewById(R.id.nowStripPrev);
        if (pv0 != null) Fx.crossfadeStripBtn(pv0, true);
        String kind = sp.getString("lastKind", "");
        // v4.30: a real mini-player shows title over artist, not
        // one combined line — the strip's old "name — title"
        // string is split here; the suffix marks below still land
        // on the title line, and the sub line names the sayadaw
        // (Room) or the saved-file source (library).
        // v13.94 (F-920 F-NEW-5): the strip names a stream still loading or
        // already failed — until now both read exactly like a paused talk
        // (▶ + title). Buffering borrows the Room's own word
        // (notif_state_buffering, the drawer-row / status-line prefix); a
        // failure borrows the Settings ⚠ mark. Error wins: the two states
        // are exclusive by construction (isBuffering() requires !trackError)
        // and both flags clear on every new play, so neither sticks to the
        // next talk. Resume mode is untouched (no live track there).
        String statePre94 = AudioService.liveTrackError ? "⚠ "
                : (AudioService.liveBuffering ? Lang.t(MainActivity.this, "notif_state_buffering") + " " : "");
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
                String libSd = Fx.stripLibSayadaw(MainActivity.this, sp);
                subText = libSd != null ? libSd : Lang.t(MainActivity.this, "downloaded_talk");
            }
        }
        if (text == null) { syncStripPhoto(null); Fx.hideStrip(strip); return; }
        // v4.26: the mini strip names a favourite sounding talk -
        // every other now-playing surface (Room line, drawer rows,
        // library rows) carries its star, but the strip back on
        // Main never did. Room sessions check lastUrl against
        // favTalks directly; library sessions reverse the Room's
        // dlMap (URL -> filename, same map the library's own
        // mark cache reads) from lastLibPath. A 1s tick refresh
        // keeps it true after a favour or a clear-favs return.
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
            String dlMarkLive = Fx.stripResumeDownloadMark(MainActivity.this, sp, sp.getString("lastUrl", ""));
            if (" ⬇".equals(dlMarkLive)) text += dlMarkLive;
        } else if ("lib".equals(kind)) {
            String lpLive = sp.getString("lastLibPath", "");
            if (lpLive.length() > 0) {
                java.io.File lfLive = new java.io.File(lpLive);
                if (lfLive.isFile() && lfLive.length() > 0) text += " ⬇";
            }
        }
        // v4.25: the mini strip names a running sleep timer — a
        // listener back on Main had no bedtime cue at all (the
        // countdown lived only in Settings and, now, the Room).
        // The service's liveSleepActive flag covers armed and
        // pause-suspended timers and clears on cancel, fire and
        // stop, so the mark never outlives the timer; a fired
        // timer clears before the strip's next 1s tick. Suffix,
        // not a new view, and the strip's own tap/long-press
        // behaviour is untouched.
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
                    ? " 😴 " + Lang.t(MainActivity.this, "sleep_end")
                    : " 😴 ⏸ " + Lang.t(MainActivity.this, "sleep_end");
            else if (sr > 0) text += AudioService.livePlaying
                    ? " 😴 " + AudioService.sleepClock(sr)
                    : " 😴 ⏸ " + AudioService.sleepClock(sr);
            else text += " 😴";
        }
        // v4.27: the mini strip names downloads in progress
        // (" ⏳ N") beside ★/😴 — a listener back on Main
        // had no download cue at all (the drawer rows and
        // the library footer are both a screen away).
        // App-scoped (Dhamma destination or a dlMap source
        // URL), refreshed on the strip's own 1s tick.
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
                                // v13.59 (887 F-NEW-1, F945-2/F942-1): dlUrls holds https keys — the DM source may be the http form.
                                || (src != null && dlUrls != null && (dlUrls.contains(src) || dlUrls.contains(SiteHttp.identityUrl(src))))) progN++;
                    }
                } finally { c.close(); }
            }
            if (progN > 0) text += " ⏳ " + progN;
        } catch (Exception ignored) {}
        // v4.67: the mini strip reads the hand queue — the last
        // now-playing surface that did not. The Room drawer header
        // (v4.32), the Talks chooser header (v4.65) and the library
        // footer (v4.66) all carry "· ◈ N", but back on Main a
        // listener saw only ★/😴/⏳ and had no cue that the strip's
        // own Next button would play a hand-queued talk first
        // (Room/library ACTION_NEXT consume the queue head, v4.32
        // / v4.57), not the list-next talk. Room sessions count
        // this sayadaw's saved queue (Prefs.savedQueue, the same
        // store the Room restores from, v4.37); library sessions
        // count saved queue paths that still exist as files, the
        // library's own stale-drop rule, so a deleted file never
        // inflates the count. Suffix only, no new view; the 1s
        // tick re-reads it, so a queue built in the Room, chooser
        // or library lands here within a second of returning to
        // Main. Resume mode is untouched (no live track, Next
        // hidden there), and a zero/empty queue adds nothing.
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
                String saved = Prefs.savedQueue(MainActivity.this,
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
                String saved = Prefs.savedQueueLib(MainActivity.this);
                if (saved.length() > 0) {
                    for (String line : saved.split("\n"))
                        if (line.trim().length() > 0
                                && new java.io.File(line.trim()).isFile()
                                && !line.trim().equals(curLib)) qN++;
                }
            }
            if (qN > 0) text += " · ◈ " + qN;
        } catch (Exception ignored) {}
        // v4.29: a finished talk used to read on the strip exactly
        // like a paused one (▶ + title) — the strip is the one
        // now-playing surface with no bind, so it reads the
        // service's liveEnded flag on its own 1s tick. A replay
        // or auto-advance clears the flag before the next tick.
        if (AudioService.liveEnded) text += " · " + Lang.t(MainActivity.this, "notif_state_ended");
        // v14.21 (slice 1675): the strip title/sub fade on a talk
        // change, keyed on the talk itself (Room URL / library
        // path) — the composed strings tick every second (sleep
        // countdown on the title, live clock on the sub), so the
        // text can never be the key.
        String stripTalkId = "room".equals(kind) ? sp.getString("lastUrl", "") : sp.getString("lastLibPath", "");
        if (tx != null) Fx.fadeStripText(tx, text, stripTalkId);
        // v5.32: live clock on the strip — sayadaw name + elapsed/total.
        android.widget.ProgressBar pb = findViewById(R.id.nowStripProgress);
        if (subText != null) subText += Fx.stripClockSuffixForStrip(pb, AudioService.livePositionMs, AudioService.liveDurationMs, Prefs.stripClockRemaining(MainActivity.this));
        if (sb != null && subText != null) Fx.fadeStripText(sb, subText, stripTalkId);
        // v4.30: the progress bar itself — the service's own 1s
        // tick (livePositionMs/liveDurationMs) feeds it, so the
        // strip shows where the talk is without ever binding.
        // Paused it freezes at the held position; a zero or
        // unknown duration reads empty, never a stale fraction.
        Fx.syncStripProgress(pb); // v7.93: skips while a mini-seek drag is in flight
        // v4.16: the toggle glyph used to be a static ⏯ from the
        // layout — it read the same whether the talk played or
        // sat paused, so it lied half the time. It now follows
        // AudioService.livePlaying (1s tick, no bind, same live
        // rule as the strip itself): ⏸ while sounding, ▶ paused.
        if (tg != null) Fx.flipStripGlyph(tg, AudioService.livePlaying);
        // v6.00: live strip's portrait — Room names lastName, library
        // names its recorded sayadaw (Fx.stripLibSayadaw, null when
        // unrecorded); both resolve through syncStripPhoto's
        // exact-name catalog match, never a guessed face.
        syncStripPhoto("room".equals(kind) ? sp.getString("lastName", "")
                : ("lib".equals(kind) ? Fx.stripLibSayadaw(MainActivity.this, sp) : null));
        Fx.showStrip(strip);
    }

    private void startMainPulseTick() {
        if (mainPulseTick != null) return;
        mainPulseTick = new Runnable() {
            @Override public void run() {
                if (mainPulseTick == null) return;
                try {
                    syncMainPulseFromVisibleCards();
                    syncNavPlayerLiveTint(cachedNight ? android.graphics.Color.WHITE
                            : android.graphics.Color.parseColor("#3B2314"),
                            android.graphics.Color.parseColor("#C9A227"));
                    syncNowStrip(); // v4.15: mini now-playing strip, below
                    // v11.15: rebind the grid exactly when the ended
                    // state flips — a talk ending while Main sits on
                    // top must drop its ▶ mark within a tick, not on
                    // the next scroll; steady ticks never touch it.
                    // v12.38: same rule for the sounding name — a
                    // stop or a sayadaw switch while Main sits on
                    // top moves no ended flag, so the v11.15 check
                    // alone left the ▶ mark stale (pulse moved,
                    // mark did not). The effective sounding name
                    // (null when ended, else the visual helper,
                    // the exact value getView marks against) is
                    // tracked beside it; a change rebinds once,
                    // steady ticks still never touch the grid.
                    String sndNow38 = AudioService.liveEnded ? null : soundingSayadawNameVisual();
                    if (AudioService.liveEnded != mainGridEnded
                            || (sndNow38 == null ? mainGridSounding != null
                                    : !sndNow38.equals(mainGridSounding))
                            || AudioService.liveTrackError != mainGridFailed
                            || AudioService.liveBuffering != mainGridBuffering) {
                        mainGridEnded = AudioService.liveEnded;
                        mainGridSounding = sndNow38;
                        mainGridFailed = AudioService.liveTrackError;
                        mainGridBuffering = AudioService.liveBuffering;
                        if (adapter != null) adapter.notifyDataSetChanged();
                        // v13.87: the same change moves the shelf ring
                        // (refreshFavShelf's key carries the sounding
                        // name, so steady ticks return early there and
                        // only a real change re-pours the chips).
                        refreshFavShelf();
                    }
                } finally {
                    // v11.78: a throw above used to abort the re-post
                    // and freeze the strip/pulse/nav tint for the rest
                    // of the visible session (v11.76 Talks twin) —
                    // the re-post now always runs; the null guard
                    // keeps a tick stopped mid-run from resurrecting.
                    if (mainPulseTick != null) mainPulseHandler.postDelayed(this, 1000);
                }
            }
        };
        mainPulseHandler.post(mainPulseTick);
    }

    private void stopMainPulseTick() {
        if (mainPulseTick != null) {
            mainPulseHandler.removeCallbacks(mainPulseTick);
            mainPulseTick = null;
        }
        stopMainPulse();
    }

    // v1.81: report the leave + schedule the background check — this
    // screen never bound to the service, so without the check a Home
    // press from here kept playing even with background-off (see
    // AppForeground; in-app navigation re-resumes before it fires).
    @Override
    protected void onPause() {
        super.onPause();
        // v3.97: anchor the live session's sayadaw for the return
        // glide — taken here, when Main is actually left, so a
        // rotation's own onPause re-anchors to the current sayadaw
        // and can never fire a glide on its onResume.
        mainReturnAnchorName = getSharedPreferences("dhamma", MODE_PRIVATE)
                .getString("lastName", "");
        // v3.99: never strand the sounding-card breath on a hidden
        // view — the tick stops with the screen, like the library
        // pulse stop on its onPause (v2.25).
        stopMainPulseTick();
        // v10.08: stop listening while hidden — onResume re-registers.
        if (mainDlCompleteRegistered && mainDlCompleteReceiver != null) {
            try { unregisterReceiver(mainDlCompleteReceiver); } catch (Exception ignored) {}
            mainDlCompleteRegistered = false;
        }
        // v2.35: never strand the theme toggle mid-spin.
        Fx.cancelSpin(themeToggle);
        AppForeground.activityPaused();
        AppForeground.scheduleBackgroundCheck(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // v13.08: detach the liquid-glass surfaces — the drawable is
        // the view's background and its callback pins the dead view +
        // its destroyed activity in GlassBlur's static map on every
        // rotation (the v13.05 weak view ref did not fix this).
        GlassBlur.detach(searchField);
        GlassBlur.detach(navBar);
        GlassBlur.detach(findViewById(R.id.nowStrip));
        // v13.10 (F5): dismiss the update block so a rotation never
        // leaks its window (WindowLeaked) — the next onCreate re-shows
        // it from the pending update, so nothing is lost.
        UpdateChecker.dismissBlock();
    }

    // v1.48: rotating the phone used to wipe the search text (and the
    // filtered grid with it) — keep the query across recreation.
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("q", searchField != null ? searchField.getText().toString() : query);
        // v13.16: the no-match toast guard used to die on rotation —
        // the query survived but lastEmptyToastQuery reset to null,
        // so deleting a char and retyping the same no-match query
        // toasted again. Save it beside the query.
        outState.putString("lastEmptyToastQuery", lastEmptyToastQuery);
        // v4.26: the favourites-only filter used to die on
        // rotation - only the query and scroll survived, so a
        // rotated filtered grid silently showed every sayadaw.
        outState.putBoolean("favOnly", favOnly);
        // v1.55: rotation dropped the grid back to the top (the Talks
        // list got its scroll saved in v1.54, the main grid never did).
        if (gridView != null) outState.putInt("scrollPos", gridView.getFirstVisiblePosition());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !Prefs.notiPermAsked(this)) {
            // v1.72: ask once (see Prefs) — the old code re-prompted
            // on every cold start and every rotation after a decline.
            Prefs.setNotiPermAsked(this, true);
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        setContentView(R.layout.activity_main);
        // v13.09 (widget F3): a force-stop cancels every widget
        // PendingIntent, and the host keeps the dead-intent
        // RemoteViews — the widget buttons stay dead until the
        // next in-app play. Reboot and app update self-heal
        // (APPWIDGET_UPDATE), force-stop is the unique hole. The
        // app icon is the way back in after one, so re-issue the
        // widget's intents on every Main launch: refresh() rebuilds
        // the RemoteViews with fresh PendingIntents, and returns
        // early when no widget is placed.
        DhammaWidgetProvider.refresh(this);
        favs = new HashSet<String>(getSharedPreferences("dhamma", MODE_PRIVATE)
                .getStringSet("favs", new HashSet<String>()));
        rootView = findViewById(R.id.root);
        // v13.32 (M9): the four header views are guarded below —
        // a missing id used to NPE on launch (all four exist
        // today, so this is latent robustness, same class as
        // the v13.30 catalog guards).
        titleText = findViewById(R.id.titleText);
        // v4.12: tap the app name to glide back to the top of the
        // grid (streaming-app tap-to-top, on the existing title —
        // no new view, no new button). A tap with an empty grid is
        // a no-op; the grid itself, search and theme are untouched.
        if (titleText != null) titleText.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(MainActivity.this);
                // v4.22: a scroll-to-top under an open keyboard
                // revealed nothing — the keyboard kept covering
                // the grid's lower half. Hide it with the scroll.
                hideKeyboard(v);
                if (gridView != null) gridView.smoothScrollToPosition(0);
            }
        });
        // v4.18: long-press the app name glides the grid to the
        // sounding sayadaw's card (tap still goes to the top,
        // v4.12) — the fresh-open landing (v3.98) fires once and
        // the return-glide (v3.97) only on a changed sayadaw, so
        // after browsing away the sounding card was unreachable
        // without hunting, the mirror of the Talks title
        // long-press (v4.15). No live Room session, or a sounding
        // sayadaw filtered out by the search box, toasts instead
        // of scrolling nowhere.
        // v12.22: the visual helper, not the Room-only one — a
        // library session used to toast "no playing sayadaw"
        // while its talk was sounding and its sayadaw's card sat
        // in the grid, the same lib-blindness v11.94 fixed for
        // the ▶ mark / pulse / nav glow. The glide only scrolls
        // the grid (no Room entry, no routing), so the v11.94
        // "routing sites stay Room-only" rule never covered it —
        // the card tap below still uses the Room helper, and the
        // automatic fresh-open / return glides stay Room-only as
        // v11.94 left them (their anchor is lastName-based).
        // Unrecorded lib sayadaw (null) still toasts, as today.
        if (titleText != null) titleText.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                String sounding = soundingSayadawNameVisual();
                if (sounding != null && gridView != null && shown != null) {
                    for (int i = 0; i < shown.size(); i++) {
                        if (sounding.equals(shown.get(i).name)) {
                            Fx.tap(MainActivity.this);
                            gridView.smoothScrollToPosition(i);
                            return true;
                        }
                    }
                }
                Toast.makeText(MainActivity.this, Lang.t(MainActivity.this, "no_playing_sayadaw"), Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        themeToggle = findViewById(R.id.themeToggle);
        if (themeToggle != null) themeToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v2.23: theme transition — dip, swap, rise, instead
                // of the instant colour flash the toggle had before.
                // v2.35: the toggle itself spins one full turn with
                // the dip, so the sun/moon visibly flips over.
                Fx.spin(v);
                Prefs.setNight(MainActivity.this, !Prefs.isNight(MainActivity.this));
                Fx.themeDip(rootView, new Runnable() {
                    @Override public void run() { applyThemeAndFont(); }
                });
            }
        });
        // v4.17: theme-toggle long-press matches the theme to the
        // clock (day 06–18, night otherwise) instead of flipping
        // it — a one-gesture "auto" for listeners who never want
        // to think about the toggle. Same spin + dip as the tap;
        // an already-matching theme just blips.
        if (themeToggle != null) themeToggle.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
                boolean night = hour < 6 || hour >= 18;
                if (night == Prefs.isNight(MainActivity.this)) { Fx.tap(MainActivity.this); return true; }
                Fx.spin(v);
                Prefs.setNight(MainActivity.this, night);
                Fx.themeDip(rootView, new Runnable() {
                    @Override public void run() { applyThemeAndFont(); }
                });
                Toast.makeText(MainActivity.this, night ? Lang.t(MainActivity.this, "theme_night") : Lang.t(MainActivity.this, "theme_day"), Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        navBar = findViewById(R.id.navBar);
        navPlayerItem = findViewById(R.id.navPlayerItem);
        navSettingsItem = findViewById(R.id.navSettingsItem);
        if (navPlayerItem != null) navPlayerItem.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                android.content.SharedPreferences sp = getSharedPreferences("dhamma", MODE_PRIVATE);
                // v1.93: the newest session may be a downloaded
                // file, not a Room talk — v1.92 fixed the
                // notification tap for that case, but this nav
                // still refused it (lastPage empty) even while the
                // file played. A library session whose file still
                // exists returns to the library; a deleted file (or
                // no session at all) keeps the old refusal instead
                // of opening a stale Room talk.
                if ("lib".equals(sp.getString("lastKind", ""))) {
                    String lp = sp.getString("lastLibPath", "");
                    if (lp.length() > 0 && new java.io.File(lp).isFile()) {
                        Fx.tap(MainActivity.this);
                        // v2.36: nav morph — the nav items squash
                        // under the finger (v2.11 press) but had no
                        // click beat of their own, unlike the star
                        // (v2.28) and the Room list toggle (v2.22);
                        // a successful nav tap now pops with the
                        // shared morph beat. Refusal toasts (no
                        // session yet) do not morph, so a dead tap
                        // never reads as a successful one.
                        Fx.morph(v);
                        // v1.94: land on the file being resumed, not
                        // the top of the library (see jumpPath there).
                        Intent li = new Intent(MainActivity.this, DownloadedActivity.class);
                        li.putExtra("jumpPath", lp);
                        // v8.58: in dead-library resume mode the nav
                        // tap resumes the file from its saved spot
                        // like the strip tap already does (Main
                        // strip, Talks strip, Settings strip and
                        // the library's own strip all pass
                        // autoPlayPath in resume mode) — before
                        // this, only the strip resumed while the
                        // nav merely glided to the row and the
                        // listener had to tap the file again, even
                        // though the ↺ strip was showing. Live
                        // sessions keep the glide-only path: the
                        // field is false then, and the library's
                        // applyAutoPlayOnce glides anyway when the
                        // file is already the sounding track.
                        // v12.59: live recheck, not just the cached
                        // flag — resumeStripMode refreshes on the 1s
                        // tick, so a session dying <1s before this
                        // tap left the flag false and the tap glided
                        // to the library WITHOUT auto-playing (one
                        // extra tap to resume — the same ≤1s stale-
                        // flag class v12.55/v12.57/v12.58 closed on
                        // Talks/Settings/Downloaded). The strip's
                        // own branch test is !liveSession ||
                        // !liveHasTrack, so this can only auto-play
                        // a genuinely dead session, never a live one.
                        if (resumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) li.putExtra("autoPlayPath", lp);
                        Fx.go(MainActivity.this, li);
                        return;
                    }
                    Toast.makeText(MainActivity.this, Lang.t(MainActivity.this, "choose_first"), Toast.LENGTH_SHORT).show();
                    return;
                }
                String page = sp.getString("lastPage", "");
                if (page.length() == 0) {
                    Toast.makeText(MainActivity.this, Lang.t(MainActivity.this, "choose_first"), Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent i = new Intent(MainActivity.this, RoomActivity.class);
                i.putExtra("name", sp.getString("lastName", ""));
                i.putExtra("group", sp.getString("lastGroup", ""));
                i.putExtra("url", page);
                i.putExtra("photo", sp.getString("lastPhoto", ""));
                i.putExtra("autoUrl", sp.getString("lastUrl", ""));
                i.putExtra("autoTitle", sp.getString("lastTitle", ""));
                Fx.tap(MainActivity.this);
                Fx.morph(v); // v2.36: nav morph, same beat as above
                Fx.go(MainActivity.this, i);
            }
        });
        // v4.16: long-press the Mp3 Player nav item stops the
        // session outright (service intent, no bind — the same
        // stop the mini strip's long-press already does, with a
        // toast here so a nav stop never reads as silent). Tap
        // still returns to the player; nothing else changes.
        // v7.25: in dead-resume mode there is no session to stop
        // — the strip's own long-press deliberately dismisses
        // the saved spot instead of toasting a phantom stop
        // (v4.60), but this nav long-press still sent ACTION_STOP
        // to a trackless service and toasted "တရား ရပ်ထားသည်"
        // while the spot survived untouched. Hand it to the strip
        // like the toggle long-press already does, so the same
        // press dismisses the spot with the dismiss toast; live
        // (incl. paused) sessions keep the stop path below.
        // v12.59: live recheck, not just the cached flag —
        // resumeStripMode refreshes on the 1s tick, so a session
        // dying <1s before this long-press left the flag false
        // and the press ACTION_STOPped a trackless service
        // (phantom "talk_stopped" toast, saved spot NOT dismissed
        // — the same ≤1s stale-flag class v12.55/v12.57/v12.58
        // closed on Talks/Settings/Downloaded). The strip's own
        // branch test is !liveSession || !liveHasTrack, so this
        // can only delegate a genuinely dead session to the strip
        // dismiss, never stop a live one.
        if (navPlayerItem != null) navPlayerItem.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (resumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                    android.view.View strip = findViewById(R.id.nowStrip);
                    if (strip != null) return strip.performLongClick();
                    return true;
                }
                Intent svc = new Intent(MainActivity.this, AudioService.class);
                svc.setAction(AudioService.ACTION_STOP);
                startService(svc);
                Fx.tap(MainActivity.this);
                Fx.morph(v);
                Toast.makeText(MainActivity.this, Lang.t(MainActivity.this, "talk_stopped"), Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        if (navSettingsItem != null) navSettingsItem.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(MainActivity.this);
                Fx.morph(v); // v2.36: nav morph, same beat as above
                Fx.go(MainActivity.this, new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        // v4.18: long-press the Settings nav item opens the
        // downloaded library directly (tap still opens Settings)
        // — the offline shelf is the one screen with no Main
        // shortcut (Settings' own button, v1.36, was the only
        // route), so reaching downloads took two taps through
        // Settings. No new view, no new button.
        if (navSettingsItem != null) navSettingsItem.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Fx.tap(MainActivity.this);
                Fx.morph(v);
                Fx.go(MainActivity.this, new Intent(MainActivity.this, DownloadedActivity.class));
                return true;
            }
        });
        // v4.17: Home nav tap scrolls the grid to the top — the
        // active-tab tap-to-top streaming convention (the title
        // tap already does this, v4.12, but the Home item itself
        // was dead: v2.11 gave it no press because it was the
        // current screen). Tap stays on Main; no navigation.
        android.view.View navMainItem = findViewById(R.id.navMainItem);
        if (navMainItem != null) {
            Fx.press(navMainItem);
            navMainItem.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Fx.tap(MainActivity.this);
                    Fx.morph(v);
                    // v4.22: same scroll-to-top keyboard rule as
                    // the title tap — the Home item is the thumb
                    // target for the same gesture, so it hides too.
                    hideKeyboard(v);
                    if (gridView != null) gridView.smoothScrollToPosition(0);
                }
            });
            // v4.18: long-press Home resets the browse view —
            // clears the search query (the watcher's filter
            // re-runs, like a typed edit) and scrolls to the top.
            // Tap alone (v4.17) only scrolls, so a filtered grid
            // stayed filtered; the search box's own long-press
            // (v4.17) clears too, but the Home item is the bigger
            // thumb target for a full reset.
            navMainItem.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    Fx.tap(MainActivity.this);
                    Fx.morph(v);
                    if (searchField != null && searchField.getText().length() > 0) searchField.setText("");
                    // v4.19: the reset-browse also clears the
                    // favourites-only filter, so a reset always
                    // returns to the full catalogue, never to a
                    // filtered view the listener forgot was on.
                    // v14.26: the reset clears the ★ filter, so the
                    // restored full grid pours like a toggle-off.
                    if (favOnly) { favOnly = false; mainFavToggleChange = true; applyFilter(); refreshFavHint(); }
                    if (gridView != null) gridView.smoothScrollToPosition(0);
                    return true;
                }
            });
        }
        // v2.11: press feedback reaches the surfaces v2.08 missed —
        // the Day/Night toggle and the two actionable nav items
        // (navMainItem is the current screen, so it gets none).
        // Visual only; clicks still work (Fx.press returns false).
        if (themeToggle != null) Fx.press(themeToggle);
        if (navPlayerItem != null) Fx.press(navPlayerItem);
        if (navSettingsItem != null) Fx.press(navSettingsItem);
        // v4.15: mini now-playing strip wiring (see syncNowStrip).
        // Tap returns to the player with the nav Player branch
        // (library file still on disk -> library, else the Room
        // talk); ⏯ toggles the service track with no bind; a
        // long-press stops the session outright.
        android.view.View nowStrip = findViewById(R.id.nowStrip);
        if (nowStrip != null) {
            Fx.stripSwipe(nowStrip);
            // v4.73: the strip's progress bar seeks (see Fx.miniSeekBar).
            Fx.miniSeekBar(MainActivity.this, nowStrip);
            // v5.33: tap the strip's sub line (sayadaw + clock) flips
            // the shared clock mode elapsed/total <-> remaining and
            // re-syncs at once (the 1s tick would catch it anyway).
            // The sub view consumes the tap, so it no longer opens
            // the player — the title line still does. Its long-press
            // forwards to the strip, so stop/dismiss is unchanged.
            // v13.12 (M7): resume strips show no clock (they
            // return before the live branch), so the flip is a
            // no-op there — the sub line is only a clock control
            // while a live session is showing.
            android.widget.TextView stripSub = findViewById(R.id.nowStripSub);
            if (stripSub != null) {
                stripSub.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        // v13.12 (M7, sim1324): the flip is a live-mode
                        // affordance only — a resume strip shows no
                        // clock, so a flip there was invisible at tap
                        // time and silently re-timed the next live
                        // session. In resume mode (same live recheck
                        // as the dismiss branch, v12.59) the tap is a
                        // deliberate no-op instead of a silent mutation.
                        if (resumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) return;
                        Prefs.setStripClockRemaining(MainActivity.this, !Prefs.stripClockRemaining(MainActivity.this));
                        Fx.tap(MainActivity.this);
                        syncNowStrip();
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
                            Fx.tap(MainActivity.this);
                            Fx.morph(v);
                            Intent li = new Intent(MainActivity.this, DownloadedActivity.class);
                            li.putExtra("jumpPath", lp);
                            // v4.63: in dead-library resume mode
                            // the tap also auto-plays the file from
                            // its saved spot; a live session never
                            // passes this flag, so it still only
                            // glides to the playing file.
                            // v12.59: live recheck, not just the cached
                            // flag — resumeStripMode refreshes on the
                            // 1s tick, so a session dying <1s before
                            // this tap left the flag false and the tap
                            // glided to the library WITHOUT auto-playing
                            // (one extra tap to resume — the same ≤1s
                            // stale-flag class v12.55/v12.57/v12.58
                            // closed on Talks/Settings/Downloaded).
                            // The strip's own branch test is
                            // !liveSession || !liveHasTrack, so this
                            // can only auto-play a genuinely dead
                            // session, never a live one.
                            if (resumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) li.putExtra("autoPlayPath", lp);
                            Fx.go(MainActivity.this, li);
                            return;
                        }
                        Toast.makeText(MainActivity.this, Lang.t(MainActivity.this, "choose_first"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        Toast.makeText(MainActivity.this, Lang.t(MainActivity.this, "choose_first"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(MainActivity.this, RoomActivity.class);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.putExtra("autoUrl", sp.getString("lastUrl", ""));
                    i.putExtra("autoTitle", sp.getString("lastTitle", ""));
                    // v13.72: reuse the Room already in the stack, like Talks'
                    // v11.86 fix — the Main strip tap used to push a SECOND Room
                    // for the same talk on top, and Back then showed the first
                    // Room again instead of Main.
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.tap(MainActivity.this);
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
                    if (RoomActivity.roomInStack()) Fx.go(MainActivity.this, i);
                    else Fx.goShared(MainActivity.this, i, findViewById(R.id.nowStripPhoto));
                }
            });
            nowStrip.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    // v4.60: in resume mode there is no session to
                    // stop — stopping would toast a lie. A long-
                    // press instead dismisses the resume spot
                    // (lastPos only; the talk itself is untouched,
                    // so reopening it starts from the beginning)
                    // and the next sync hides the strip.
                    // v12.59: live recheck, not just the cached flag —
                    // resumeStripMode refreshes on the 1s tick, so a
                    // session dying <1s before this long-press left
                    // the flag false and the press ACTION_STOPped a
                    // trackless service (phantom "talk_stopped"
                    // toast, saved spot NOT dismissed — the same ≤1s
                    // stale-flag class v12.55/v12.57/v12.58 closed
                    // on Talks/Settings/Downloaded). The strip's own
                    // branch test is !liveSession || !liveHasTrack,
                    // so this can only dismiss a genuinely dead
                    // session, never stop a live one.
                    if (resumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        // v5.34: dismissing the spot must also drop
                        // this talk's per-talk spot, or the promise
                        // in the comment above ("reopening it starts
                        // from the beginning") would be a lie.
                        String dismissUrl = getSharedPreferences("dhamma", MODE_PRIVATE)
                                .getString("lastPosUrl", "");
                        getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                                .putInt("lastPos", 0).putString("lastPosUrl", "").apply();
                        if (dismissUrl.length() > 0) Prefs.talkPosRemoveBoth(MainActivity.this, dismissUrl);
                        Fx.tap(MainActivity.this);
                        Fx.morph(v);
                        Toast.makeText(MainActivity.this, Lang.t(MainActivity.this, "resume_cleared"), Toast.LENGTH_SHORT).show();
                        syncNowStrip();
                        return true;
                    }
                    Intent svc = new Intent(MainActivity.this, AudioService.class);
                    svc.setAction(AudioService.ACTION_STOP);
                    startService(svc);
                    // v4.21: the strip stop was tap-only — the same stop
                    // from the nav Player item (v4.16) and the strip's own
                    // toggle (v4.17) both pop + say so, so a strip stop
                    // read as a dead press. Same morph + toast as them.
                    Fx.tap(MainActivity.this);
                    Fx.morph(v);
                    Toast.makeText(MainActivity.this, Lang.t(MainActivity.this, "talk_stopped"), Toast.LENGTH_SHORT).show();
                    return true;
                }
            });
        }
        android.view.View nowToggle = findViewById(R.id.nowStripToggle);
        if (nowToggle != null) nowToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v4.60: in resume mode there is no live track to
                // toggle (a toggle intent on a trackless service
                // just ends it) — the ▶ button re-opens the Room
                // like the strip tap, which resumes from the spot.
                // v12.59: live recheck, not just the cached flag —
                // resumeStripMode refreshes on the 1s tick, so a
                // session dying <1s before this tap left the flag
                // false and the tap ACTION_TOGGLEd a trackless
                // service (dead tap, tap-twice-to-resume — the
                // same ≤1s stale-flag class v12.55/v12.57/v12.58
                // closed on Talks/Settings/Downloaded). The
                // strip's own branch test is !liveSession ||
                // !liveHasTrack, so this can only resume a
                // genuinely dead session, never toggle a live one.
                if (resumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                    android.view.View strip2 = findViewById(R.id.nowStrip);
                    if (strip2 != null) strip2.performClick();
                    return;
                }
                Intent svc = new Intent(MainActivity.this, AudioService.class);
                svc.setAction(AudioService.ACTION_TOGGLE);
                startService(svc);
                Fx.tap(MainActivity.this);
                Fx.morph(v);
                // v4.22: the toggle plays/pauses in place (no
                // navigation to auto-hide the keyboard), so a
                // search keyboard open from typing stayed up over
                // the grid after the tap. Hide it with the toggle.
                hideKeyboard(v);
            }
        });
        // v4.17: mini-strip toggle long-press stops the session
        // outright with a toast — the strip long-press stop
        // (v4.15) and the nav Player long-press stop (v4.16)
        // with feedback, now on the toggle itself, so the small
        // ⏸/▶ target is not a dead end for stopping. Tap still
        // toggles; the child consumes the long-press, so the
        // strip's own silent stop never double-fires.
        if (nowToggle != null) nowToggle.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                // v4.60: resume mode has no session to stop — hand
                // the long-press to the strip, which dismisses the
                // saved spot instead of toasting a phantom stop.
                // v12.59: live recheck, not just the cached flag —
                // resumeStripMode refreshes on the 1s tick, so a
                // session dying <1s before this long-press left the
                // flag false and the press ACTION_STOPped a
                // trackless service (phantom "talk_stopped" toast,
                // saved spot NOT dismissed — the same ≤1s stale-flag
                // class v12.55/v12.57/v12.58 closed on Talks/
                // Settings/Downloaded). The strip's own branch test
                // is !liveSession || !liveHasTrack, so this can only
                // delegate a genuinely dead session to the strip
                // dismiss, never stop a live one.
                if (resumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                    android.view.View strip2 = findViewById(R.id.nowStrip);
                    if (strip2 != null) return strip2.performLongClick();
                    return true;
                }
                Intent svc = new Intent(MainActivity.this, AudioService.class);
                svc.setAction(AudioService.ACTION_STOP);
                startService(svc);
                Fx.tap(MainActivity.this);
                Fx.morph(v);
                Toast.makeText(MainActivity.this, Lang.t(MainActivity.this, "talk_stopped"), Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        // v4.30: the mini-player's Next — the strip's toggle
        // plays/pauses in place, but advancing needs the talk
        // list, which only the Room (or the library) owns. So
        // Next routes exactly like the notification's Next
        // button: library sessions to DownloadedActivity's
        // ACTION_NEXT, Room sessions to RoomActivity's
        // ACTION_NEXT with the same sayadaw extras, which
        // advances from the service's current track (pendingNext
        // covers unbound/unloaded). It lands on the player for
        // the next talk, like tapping the strip itself — never
        // a dead press, and no new service action was needed.
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
                    // v12.59: live recheck, not just the cached flag —
                    // resumeStripMode refreshes on the 1s tick, so a
                    // session dying <1s before this tap left the flag
                    // false and the tap still ACTION_NEXT-ed into a
                    // trackless Room (the same ≤1s stale-flag class
                    // v12.55/v12.57/v12.58 closed on Talks/Settings/
                    // Downloaded). The strip's own branch test is
                    // !liveSession || !liveHasTrack, so this can only
                    // resume a genuinely dead session, never advance
                    // a live one.
                    if (resumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    Fx.tap(MainActivity.this);
                    Fx.morph(v);
                    hideKeyboard(v);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        Intent li = new Intent(MainActivity.this, DownloadedActivity.class);
                        li.setAction(DownloadedActivity.ACTION_NEXT);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        Fx.go(MainActivity.this, li);
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        Toast.makeText(MainActivity.this, Lang.t(MainActivity.this, "choose_first"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(MainActivity.this, RoomActivity.class);
                    i.setAction(RoomActivity.ACTION_NEXT);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.go(MainActivity.this, i);
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
                    // v12.59: live recheck, not just the cached flag —
                    // resumeStripMode refreshes on the 1s tick, so a
                    // session dying <1s before this tap left the flag
                    // false and the tap still ACTION_PREV-ed into a
                    // trackless Room (the same ≤1s stale-flag class
                    // v12.55/v12.57/v12.58 closed on Talks/Settings/
                    // Downloaded). The strip's own branch test is
                    // !liveSession || !liveHasTrack, so this can only
                    // resume a genuinely dead session, never rewind
                    // a live one.
                    if (resumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    Fx.tap(MainActivity.this);
                    Fx.morph(v);
                    hideKeyboard(v);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        Intent li = new Intent(MainActivity.this, DownloadedActivity.class);
                        li.setAction(DownloadedActivity.ACTION_PREV);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        Fx.go(MainActivity.this, li);
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        Toast.makeText(MainActivity.this, Lang.t(MainActivity.this, "choose_first"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(MainActivity.this, RoomActivity.class);
                    i.setAction(RoomActivity.ACTION_PREV);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.go(MainActivity.this, i);
                }
            });
        }
        gridView = findViewById(R.id.grid);
        favShelfWrap = findViewById(R.id.favShelfWrap);
        favShelf = findViewById(R.id.favShelf);
        adapter = new SayadawAdapter();
        if (gridView != null) gridView.setAdapter(adapter);
        if (gridView != null) gridView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= shown.size()) return;
                openSayadawCard(shown.get(pos), v);
            }
        });

        // v4.16: long-press a sayadaw card toggles its favourite
        // (the same favs set + saveFavs + rows-only refresh the
        // star tap uses, v1.44) — a whole-card target for older
        // listeners instead of hunting the small star. A tap still
        // opens the chooser (or the Room for the sounding card);
        // returning true consumes the long-press so no tap fires.
        if (gridView != null) gridView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= shown.size()) return false;
                Sayadaw s = shown.get(pos);
                boolean nowFav;
                if (favs.contains(s.name)) { favs.remove(s.name); nowFav = false; }
                else { favs.add(s.name); nowFav = true; }
                saveFavs();
                Fx.tap(MainActivity.this);
                Fx.morph(v);
                Toast.makeText(MainActivity.this, Lang.t(MainActivity.this, nowFav ? "fav_added" : "fav_removed"), Toast.LENGTH_SHORT).show();
                // v4.26: the star tap got this in v4.21 but the
                // whole-card long-press never did - unfavouriting
                // under the favourites-only filter left the
                // unstarred card sitting in a grid that claims to
                // show favourites only until the next filter run.
                // Same branch as the star tap: re-run the filter
                // (never scrolls here) and say so when it empties.
                // v10.83: but never leave a blank filtered grid —
                // unfavouriting the LAST favourite under favOnly
                // used to re-run the filter with the filter still
                // on, so the grid went empty (only the toast said
                // why) until a rotation, a return, or a second
                // toggle tripped the v7.29 rule elsewhere — the
                // rotation restore, the search long-press toggle
                // and the onResume staleness guard all refuse
                // exactly this state, but this path never did.
                // Drop the filter (and its ★ hint) first, so the
                // re-run shows the full catalogue; the toast
                // still says the favourites are gone.
                if (favOnly) {
                        // v14.26: exiting the ★ filter this way pours
                        // the restored grid; a non-last unfavourite
                        // keeps the filter on and stays rows-only (v1.44).
                        if (favs.isEmpty()) { favOnly = false; mainFavToggleChange = true; refreshFavHint(); }
                        applyFilter();
                        if (favs.isEmpty())
                            Toast.makeText(MainActivity.this, Lang.t(MainActivity.this, "no_fav_sayadaw"), Toast.LENGTH_SHORT).show();
                    } else adapter.notifyDataSetChanged();
                refreshFavShelf(); // v13.74: star/unstar re-pours the shelf
                return true;
            }
        });
        loadCatalog();
        // v12.45: re-read favs after the catalog load — loadCatalog
        // runs the v11.99 alias migration (Prefs.migrateQueueKeys99),
        // which rewrites the STORED favs set alias -> current name,
        // but the field above was loaded BEFORE it ran, so a renamed
        // favourite stayed under its old spelling in memory while
        // the grid binds the current name: under favOnly that
        // sayadaw dropped out of the grid for the whole first
        // session after a rename, and the onResume staleness guard
        // could never see it (it iterates `shown`, and the missing
        // sayadaw is not in `shown`). A long-press toggle in that
        // session then re-poisoned the stored set via saveFavs.
        // Taking the post-migration values here costs nothing when
        // no alias is stored (the same set comes back), and every
        // later load (onResume, toggles) already reads the field.
        favs = new HashSet<String>(getSharedPreferences("dhamma", MODE_PRIVATE)
                .getStringSet("favs", new HashSet<String>()));

        searchField = findViewById(R.id.search);
        // v13.82 (slice 1074): the ★ button runs the same toggle as
        // the long-press (toggleFavOnly below) — one method, two
        // gestures, never disagreeing. Its own Fx.tap beat mirrors
        // the long-press handler's beat at the top of that handler.
        favFilterBtn = findViewById(R.id.favFilterBtn);
        if (favFilterBtn != null) favFilterBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                Fx.tap(MainActivity.this);
                toggleFavOnly(v);
            }
        });
        // v1.48: restore the pre-rotation query (see onSaveInstanceState).
        if (savedInstanceState != null) query = savedInstanceState.getString("q", "");
        // v13.16: restore the no-match toast guard with the query (see
        // onSaveInstanceState) — a rotated no-match search stays silent
        // until a genuinely new query is typed.
        if (savedInstanceState != null) lastEmptyToastQuery = savedInstanceState.getString("lastEmptyToastQuery", null);
        // v4.26: restore the filter with the query (guarded: an
        // emptied favourites set restores to the full grid, never
        // a blank filtered one). The onCreate applyFilter below
        // picks it up, and its hint refresh follows it.
        if (savedInstanceState != null && savedInstanceState.getBoolean("favOnly", false)
                && favs != null && !favs.isEmpty()) favOnly = true;
        // v2.07: a rotated grid must not replay its entrance.
        if (savedInstanceState != null) lastGridAnimPos = Integer.MAX_VALUE;
        // v14.15 (slice 1669): same rule for the ★ shelf — a
        // rotated shelf restores still, never re-pours its first
        // appearance. Cleared at the end of onCreate, so a later
        // appearance (cleared search, first favourite) still pours.
        if (savedInstanceState != null) shelfSkipFirstPour = true;
        if (searchField != null) searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                query = e.toString();
                applyFilter(!restoringSearch);
            }
        });
        // v4.17: search long-press clears the query — Main never
        // had a clear button (the library's ✕ is its own screen),
        // so wiping a typed name meant deleting it letter by
        // letter. Empty query just blips; a cleared query re-runs
        // the filter through the watcher above, like a typed edit.
        if (searchField != null) searchField.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Fx.tap(MainActivity.this);
                if (searchField.getText().length() > 0) searchField.setText("");
                else toggleFavOnly(v); // v13.82: body lives in toggleFavOnly below (verbatim v4.19/v7.29/v4.28) — the ★ button calls the same method.
                return true;
            }
        });
        // v4.20: keyboard Search key dismisses the keyboard — the
        // field never had an editor action, so pressing Search on
        // the keyboard did nothing (and a hardware Enter could drop
        // a newline into the query). The Search key now hides the
        // keyboard; the live filter already ran on every keystroke.
        if (searchField != null) searchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        if (searchField != null) searchField.setSingleLine(true);
        if (searchField != null) searchField.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
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
        if (searchField != null && query.length() > 0) {
            restoringSearch = true;
            searchField.setText(query);
            restoringSearch = false;
        }
        applyFilter();
        refreshFavHint();
        // v1.55: restore the pre-rotation grid scroll (see)
        // onSaveInstanceState) — the catalog loads synchronously, so
        // the position is valid right after the filter runs.
        if (savedInstanceState != null) {
            int scrollPos = savedInstanceState.getInt("scrollPos", 0);
            if (gridView != null && scrollPos > 0 && scrollPos < shown.size()) gridView.setSelection(scrollPos);
        } else {
            landOnPlayingSayadawOnFreshOpen();
        }
        applyThemeAndFont();
        // v2.07: launch fade — a cold start fades the main screen in
        // instead of cutting to it; rotations skip it (a restored
        // screen must not replay its entrance).
        if (savedInstanceState == null && rootView != null) {
            rootView.setAlpha(0f);
            rootView.animate().alpha(1f).setDuration(400).setStartDelay(60).start();
        }
        // v2.17: premium header entrance — the app-name title, the
        // Day/Night toggle and the search box used to sit flat while
        // the grid cascaded below them (v2.07), so the screen opened
        // top-dead like the Talks title did before v2.16. On a fresh
        // open only, they now fade + rise in order via the existing
        // Fx.entrance() family, choreographed with the root launch
        // fade above and the grid cascade below. Rotations (saved
        // state) and back-stack returns never replay it, and
        // applyThemeAndFont() touches only colour/size/background,
        // never alpha/translation, so the entrance ends cleanly at
        // full opacity. No view added — only existing header views.
        // v2.18: premium nav entrance — the bottom nav bar was the
        // last Main element still appearing instantly while the
        // header (v2.17) and grid (v2.07) poured in, so the screen
        // closed bottom-dead. It now joins the same entrance call
        // as the fourth step (240ms delay), rising in after the
        // header, on a fresh open only; rotations and back-stack
        // returns never replay it, and its press feedback (v2.11)
        // is untouched.
        if (savedInstanceState == null)
            Fx.entrance(titleText, themeToggle, searchField, navBar);
        // v12.68: Wi-Fi auto-download — schedule the 6-hour
        // unmetered check idempotently on launch (same job id
        // overwrites; a disabled toggle schedules nothing, and a
        // scheduling failure never breaks launch — schedule()
        // catches everything itself).
        AutoDownloadJob.schedule(this);
        // v12.95: in-app updater (3D-app force-update system) — checks
        // GitHub for a newer published release on every launch; a
        // newer release shows the blocking update dialog.
        UpdateChecker.check(this);
        // v14.15 (slice 1669): the rotation pour-skip above only
        // covers this onCreate's own shelf build — clear it here so
        // a later first appearance (cleared search, first favourite)
        // still pours.
        shelfSkipFirstPour = false;
    }

    // v12.95: back is swallowed while the update block is up — the
    // update comes first, like the 3D app's blocking screen.
    // v13.00: Back on the Main screen must REALLY exit the app.
    // finish() alone left the foreground service (and the process)
    // alive, so the app never cold-started and the updater never saw
    // a new release. Now Back stops the service, closes every
    // activity, and kills the process. (Home keeps background
    // playback going — only Back exits for real.)
    @Override public void onBackPressed() {
        if (UpdateChecker.isBlockVisible()) return;
        exitAppForReal();
    }

    private void exitAppForReal() {
        try { stopService(new Intent(this, AudioService.class)); } catch (Exception ignored) {}
        finishAffinity();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, 200);
    }

    private void loadCatalog() {
        InputStream in = null;
        try {
            // v1.47: read the catalog to the end in a loop — a single
            // read() is not guaranteed to fill the buffer, so a large
            // catalog could silently lose its last sayadaws.
            in = getAssets().open("sayadaws.json");
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) baos.write(buf, 0, read);
            String json = new String(baos.toByteArray(), "UTF-8");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                // v13.30 (Catalog F3): a bad row must not hide every
                // sayadaw after it — skip a non-object entry instead
                // of aborting the whole loop out of getJSONObject.
                JSONObject o;
                try { o = arr.getJSONObject(i); } catch (Exception ignored) { continue; }
                // v14.22 (catalog 2nd re-check): an object row with no
                // name would add a blank card to the grid — the other
                // six readers all skip empty names (Prefs maps, photo
                // map, History/Playlist caches; the Room/service
                // lookups match by name, so an empty name never
                // matches), so the grid agrees with them here.
                if (o.optString("name", "").length() == 0) continue;
                String url = o.isNull("page_url") ? "" : o.optString("page_url", "");
                String photo = "";
                if (!o.isNull("photo_url")) photo = o.optString("photo_url", "");
                if (photo.length() == 0 && !o.isNull("photo")) photo = o.optString("photo", "");
                all.add(new Sayadaw(o.optString("name", ""), o.optString("group", ""), url, photo));
            }
        } catch (Exception e) {
            Toast.makeText(this, Lang.t(this, "catalog_open_failed") + Fx.errDetail(e), Toast.LENGTH_LONG).show();
        } finally {
            // v1.66: same stream-leak class as the talk loaders —
            // a read dying mid-catalog used to skip the close.
            if (in != null) { try { in.close(); } catch (Exception ignored) {} }
        }
        // v11.99: queue-key migration (B-D6) — once per process
        // (Prefs guards it); running it here migrates favs on
        // launch even when no queue is touched this session.
        Prefs.migrateQueueKeys99(this);
        // v13.66: legacy http-row migration (slice 1052) — once
        // per process (Prefs guards it); running it here migrates
        // the five stores + scalars on launch even when none is
        // touched this session.
        Prefs.migrateLegacyHttpRows66(this);
    }

    private void applyFilter() { applyFilter(false); }
    // v4.21: favourites-only state stays visible after its toast fades —
    // the grid alone never said it was filtered, so a small filtered grid
    // read as a missing catalogue. The search hint carries a star prefix
    // while favOnly is on (in-place text on the existing field, no new
    // view); every path that flips favOnly refreshes it below.
    // v13.74 (slice 1065): grid-tap routing extracted verbatim into
    // this helper so the favourites-shelf chips share it: no-link
    // refusal, sounding card -> Room, library session ->
    // Downloaded, else the Talks chooser. Both taps on the same
    // sayadaw can now never disagree. One addition: the long-press
    // handler's bounds guard (a tap with a stale position returns
    // instead of crashing the grid bind).
    private void openSayadawCard(Sayadaw s, android.view.View v) {
        if (s.url == null || s.url.length() == 0 || "null".equals(s.url)) {
            Toast.makeText(MainActivity.this, Lang.t(MainActivity.this, "no_sayadaw_page"), Toast.LENGTH_LONG).show();
            return;
        }
        // v4.15: tapping the sounding sayadaw's own card
        // goes straight to the Room (streaming "tap
        // now-playing goes to the player") instead of the
        // Talks chooser — every other card still opens its
        // chooser. The Room's own re-entry rule (v1.56)
        // keeps the playing talk put instead of restarting
        // it, so this is a return, never a replay.
        String sounding = soundingSayadawName();
        if (sounding != null && sounding.equals(s.name)) {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            Intent i = new Intent(MainActivity.this, RoomActivity.class);
            i.putExtra("name", s.name);
            i.putExtra("group", s.group);
            i.putExtra("url", s.url);
            i.putExtra("photo", s.photo == null ? "" : s.photo);
            i.putExtra("autoUrl", sp.getString("lastUrl", ""));
            i.putExtra("autoTitle", sp.getString("lastTitle", ""));
            Fx.morph(v);
            Fx.tap(MainActivity.this);
            Fx.go(MainActivity.this, i);
            return;
        }
        // v13.12 (M5, sim1324): a library session sounds too —
        // the card breathes and shows ▶ for it, so tapping it
        // must return to the library, not the Talks chooser.
        // The visual twin is the same name the grid's ▶ mark
        // and pulse use, so the tap can never disagree with
        // what the card shows; the Room branch above already
        // returned for a room session. Same jumpPath glide as
        // the strip tap — a live session glides only, never
        // auto-plays. A file gone from disk falls through to
        // the chooser below, which is still useful.
        android.content.SharedPreferences spLib =
                getSharedPreferences("dhamma", MODE_PRIVATE);
        if ("lib".equals(spLib.getString("lastKind", ""))
                && AudioService.liveSession && AudioService.liveHasTrack
                && !AudioService.liveEnded) {
            String soundingVisual = soundingSayadawNameVisual();
            if (soundingVisual != null && soundingVisual.equals(s.name)) {
                String lp = spLib.getString("lastLibPath", "");
                if (lp.length() > 0 && new java.io.File(lp).isFile()) {
                    Fx.morph(v);
                    Fx.tap(MainActivity.this);
                    Intent li = new Intent(MainActivity.this, DownloadedActivity.class);
                    li.putExtra("jumpPath", lp);
                    Fx.go(MainActivity.this, li);
                    return;
                }
            }
        }
        Intent i = new Intent(MainActivity.this, TalksActivity.class);
        i.putExtra("name", s.name);
        i.putExtra("group", s.group);
        i.putExtra("url", s.url);
        i.putExtra("photo", s.photo == null ? "" : s.photo);
        // v2.39: grid-card click beat — the card squashed under
        // the finger (v2.08 press) but a tap landing on it had
        // no beat of its own, unlike nav (v2.36), Settings
        // choices (v2.37) and Talks rows (v2.38), so the app's
        // entry action — opening a sayadaw's talks — felt
        // flatter than every other tap. It now pops with the
        // shared Fx.morph beat (v2.09) before Fx.go's
        // navigation: the press squash's UP spring-back is
        // replaced by the morph exactly as on nav, and the
        // beat reads through the transition because Main stays
        // in the back stack while it fades out (nav_out 250ms)
        // under the Talks fade-in (300ms). The no-link refusal
        // path returns before it, so a dead tap never reads
        // as a successful one.
        Fx.morph(v);
        Fx.tap(MainActivity.this);
        Fx.go(MainActivity.this, i);
    }

    // v13.82 (slice 1074): favourites-only toggle — the body of the
    // search long-press empty-query branch (v4.19), extracted verbatim
    // so the new ★ button beside the search field and the long-press
    // gesture run the identical toggle and can never disagree. The
    // button tap adds its own Fx.tap beat at the click site (the
    // long-press keeps its beat at the top of its handler, as before).
    private void toggleFavOnly(android.view.View v) {
        // v4.19: long-press an EMPTY search toggles the
        // favourites-only filter — a non-empty query
        // still clears (v4.17), so the two gestures
        // never collide: clearing needs text, filtering
        // needs none. The filter itself runs through
        // applyFilter, so a typed query still narrows
        // within favourites, and the empty toast path
        // stays untouched (it only fires for a typed
        // query). No favourites yet just says so.
        // v14.26: remember the pre-toggle state — the no-favourites
        // refusal below flips favOnly back, and a refused toggle
        // changes nothing, so it must not arm the pour.
        boolean wasFavOnly = favOnly;
        favOnly = !favOnly;
        if (favOnly && (favs == null || favs.isEmpty())) {
            // v7.29: the toggle used to ENTER the filter
            // with zero favourites — a blank grid with
            // only a toast, until the same hidden gesture
            // was repeated to leave it. The rotation
            // restore already refuses exactly that state
            // ("never a blank filtered one"); refuse it
            // here too, so the grid stays full and the
            // toast is the whole story.
            favOnly = false;
            Toast.makeText(MainActivity.this, Lang.t(MainActivity.this, "no_fav_sayadaw"), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(MainActivity.this, favOnly ? Lang.t(MainActivity.this, "fav_only_on") : Lang.t(MainActivity.this, "fav_only_off"), Toast.LENGTH_SHORT).show();
        }
        // v4.28: the grid just changed underneath the
        // keyboard, so it hides with the toggle — the
        // mirror of the library toggle's own hide (this
        // run) and the v4.22 rule. The clear branch above
        // keeps the keyboard: a cleared query means a
        // retype is imminent, but a toggle never is.
        hideKeyboard(v);
        // v14.26: arm the grid pour only when the toggle actually
        // stuck — a refused toggle (no favourites) leaves the grid
        // untouched.
        if (favOnly != wasFavOnly) mainFavToggleChange = true;
        applyFilter();
        refreshFavHintAnimated();
    }

    private void refreshFavHint() {
        paintFavHint(false);
    }

    // v14.42 (slice 1697): the ★ filter button melts gold<->muted on
    // toggle instead of snapping — refreshFavHint painted the
    // button's state colour in one frame while the grid below pours
    // (v14.26) and the hint gains its ★ prefix, so the tap's own
    // control was the one un-choreographed part of the toggle. One
    // painter, two rhythms (the chooser/library chip pattern,
    // v14.39/v14.40): the animated painter melts the colour via
    // Fx.crossfadeTextColor (250ms, v14.34 sleep-text rhythm); the
    // instant painter routes it through Fx.setTextColorNow, which
    // records the target and cancels any in-flight melt so the
    // helper's map can never desync. Only toggleFavOnly melts — the
    // search-field long-press shares its body, so it melts too
    // (same deliberate gesture, the grid pours with it); theme /
    // language / rotation / stale / reset / unfavourite / first
    // paint keep the instant painter.
    private void refreshFavHintAnimated() {
        paintFavHint(true);
    }

    private void paintFavHint(boolean animate) {
        if (searchField != null)
            searchField.setHint(favOnly ? "★ " + Lang.t(this, "search_hint") : Lang.t(this, "search_hint"));
        // v13.82 (slice 1074): the ★ filter button wears the same
        // state as the hint prefix — gold while favourites-only is
        // on, the card-group muted pair while off (night-aware, the
        // getView group colours). Null-guarded: applyThemeAndFont
        // calls this before onCreate binds the button, and the
        // unfavourite-last-fav paths call it too. The description
        // reuses the shipped fav_only_on/off strings (12 languages),
        // so no new Lang key and no hand-typed Burmese.
        if (favFilterBtn != null) {
            paintFavBtnColor(animate);
            favFilterBtn.setContentDescription(Lang.t(this, favOnly ? "fav_only_on" : "fav_only_off"));
        }
    }

    private void paintFavBtnColor(boolean animate) {
        int color = favOnly ? android.graphics.Color.parseColor("#C9A227")
                : (cachedNight ? android.graphics.Color.parseColor("#AAAAAA") : android.graphics.Color.parseColor("#7A6A58"));
        if (animate) Fx.crossfadeTextColor(favFilterBtn, color);
        else Fx.setTextColorNow(favFilterBtn, color);
    }

    // v15.06 (slice 1764): the park delay for a chip whose home index
    // is homeIdx — anchored to the departure's own end, not to this
    // rebuild. A fresh departure's fade starts now, so 250 is its
    // exact end; a carried departure leaves at its remaining time
    // (250 * sqrt(alpha), the v15.05 remainder), so the longest
    // remaining time among the carried departures displacing the chip
    // is when its slot frees. -1 when no departure displaces the chip
    // at all — the chip melts in at once. The park block (added chips)
    // and the melt carry (re-parked chips) share it, so a wait started
    // in one rebuild and re-posted in the next still ends at the same
    // departure-leave moment.
    // v15.07 (slice 1766): PLUS the v15.03 rank-close glide's own
    // duration (250ms) — the parked chip's fadeIn used to fire at the
    // departure's leave, the exact frame the glide starts, so it
    // melted in WHILE sliding home (slice 1765's first-hand trace:
    // glide and fadeIn both run t=250->500, the chip ~75% visible
    // while still a quarter-slot right of home). The melt now starts
    // when the glide has finished, so the entrance reads as
    // glide-then-melt instead of melt-while-sliding: the handoff is
    // out 250 -> glide 250 -> in 250. The glide's duration is the
    // same 250 literal the two glideHome call sites below pass, so a
    // change there must change this +250 too.
    private long shelfParkDelay64(int homeIdx,
            java.util.List<Integer> freshIdx64,
            java.util.List<Integer> carryIdx64,
            java.util.List<Float> carryAlpha64) {
        if (freshIdx64 != null) {
            for (int d64 = 0; d64 < freshIdx64.size(); d64++) {
                // v15.07: 250 (this fresh fade's own end) + 250 (the
                // rank-close glide that starts at that end).
                if (freshIdx64.get(d64) <= homeIdx) return 250 + 250;
            }
        }
        long maxRemain64 = -1;
        if (carryIdx64 != null && carryAlpha64 != null) {
            for (int d64 = 0; d64 < carryIdx64.size(); d64++) {
                if (carryIdx64.get(d64) <= homeIdx) {
                    long r64 = (long) (250 * Math.sqrt(carryAlpha64.get(d64)));
                    if (r64 > maxRemain64) maxRemain64 = r64;
                }
            }
        }
        // v15.07: the longest carried remainder, plus the same 250ms
        // glide — -1 stays -1 when nothing displaces the chip.
        return maxRemain64 < 0 ? maxRemain64 : maxRemain64 + 250;
    }

    // v15.08 (slice 1767): the exact remainder of a rank-close glide
    // at translationX x, of a glide that started at start over dur —
    // the DecelerateInterpolator curve is quadratic, so dur *
    // sqrt(x / start) reproduces the original curve and the chip
    // still lands home at the same instant as the uninterrupted
    // glide (the v15.05 remainder rule, for translationX instead of
    // alpha). -1 when the glide is not really running (no start, no
    // duration, already home — |x| at dust, 0.01f: the curve drops x
    // below 0.5 within ~73ms, so a 0.5 threshold would miss most of
    // the glide window). The caller melts in at once then.
    // Clamped to [1, dur]: a dust remainder still animates one
    // frame, and a float wobble above the start never stretches the
    // glide. The glide carry below and the parked-chip wait in the
    // melt carry share it, so the two can never disagree on when a
    // glide ends.
    private long shelfGlideRemain65(float x, float start, long dur) {
        if (start <= 0.01f || dur <= 0) return -1;
        if (x <= 0.01f) return -1;
        long r65 = (long) (dur * Math.sqrt(x / start));
        if (r65 < 1) r65 = 1;
        if (r65 > dur) r65 = dur;
        return r65;
    }

    // v13.74 (slice 1065): ★ favourites shelf — pours the starred
    // sayadaws (catalogue order) as photo + name chips above the grid.
    // Hidden while a query is typed or the favourites-only filter is
    // on: the grid already shows the narrowed set there, so the shelf
    // would duplicate it. Empty favourites (or only stale names left
    // after a rename) hide the whole block, so Main looks exactly as
    // before until the user stars someone. Tap a chip -> the same
    // openSayadawCard routing as a grid-card tap. Called from
    // applyFilter (every keystroke / toggle / load), from
    // applyThemeAndFont (theme/font/language return recolour), and
    // from the card long-press (the non-favOnly branch refreshes the
    // grid without a filter run).
    // v13.87 (slice 1079): the sounding favourite's chip wears the
    // gold ring — the shelf was the last Main surface naming a
    // sounding sayadaw without the gold language (the grid card got
    // its tile in v13.86, the strip already carries the gold rim +
    // gold progress): one boolean (shelfPlaying, the grid's
    // gridPlaying predicate verbatim) drives the ring, the ▶ mark
    // and the gold name, so the three can never disagree. A paused
    // session still rings (v3.99), a library session rings its
    // recorded sayadaw (v11.94), an ended talk never rings (the
    // helper itself returns null when ended, v12.86). The ring is
    // the frame's background alone — the press squash is scale-only
    // (Fx.press, on the chip) and the shelf chips carry no entrance
    // animation at all (the staggered pour lives in the grid's
    // getView, not here), so nothing fights the ring. NOT the
    // banned favStar badge: no ★, no new view id, no new control —
    // the frame is only the ring's carrier, and the shelf's ★ header
    // is pre-existing chrome.
    private void refreshFavShelf() {
        if (favShelfWrap == null || favShelf == null) return;
        java.util.List<Sayadaw> favList = new java.util.ArrayList<Sayadaw>();
        if (query != null && query.length() == 0 && !favOnly
                && favs != null && !favs.isEmpty() && all != null) {
            for (Sayadaw s : all) if (favs.contains(s.name)) favList.add(s);
        }
        if (favList.isEmpty()) {
            // v14.38 (slice 1693): the shelf fades out instead of
            // vanishing — its appearance pours (v14.15 stagger
            // cascade) but the last favourite un-starred (or a
            // search keystroke / favOnly toggle emptying the shelf)
            // used to snap the whole wrap to GONE in one frame.
            // Fx.fadeOut (200ms, the v14.33 sleep-line rhythm),
            // GONE at the end, gen-guarded (v3.62) so a re-appear
            // landing mid-fade never strands it hidden. The
            // steady-empty case is unchanged (already GONE skips).
            if (favShelfWrap.getVisibility() != android.view.View.GONE)
                Fx.fadeOut(favShelfWrap, 200);
            lastFavShelfKey = null;
            return;
        }
        // v13.87: the sounding name joins the pour key, so a
        // sounding change re-pours the shelf exactly like the
        // v12.38 tick rebinds the grid — steady ticks still return
        // early below and never rebuild the chips.
        String soundingShelf = soundingSayadawNameVisual();
        StringBuilder kb = new StringBuilder();
        kb.append(cachedNight ? "N" : "D");
        for (Sayadaw s : favList) kb.append('|').append(s.name);
        kb.append('|').append(soundingShelf == null ? "∅" : soundingShelf);
        // v14.07 (slice 1102): the failure + loading bits join the
        // pour key — a stream failing (or starting to load) under
        // the same sounding sayadaw used to leave the chip's mark
        // + ring stale until the next filter run, while the grid
        // card already said ⚠. The Main tick already re-pours
        // on both flips (mainGridFailed / mainGridBuffering), so
        // the chip now moves within a tick of either edge.
        kb.append(AudioService.liveTrackError ? "|F" : "|f");
        kb.append(AudioService.liveBuffering ? "|B" : "|b");
        String key = kb.toString();
        if (key.equals(lastFavShelfKey)
                && favShelfWrap.getVisibility() == android.view.View.VISIBLE) return;
        lastFavShelfKey = key;
        // v14.50 (slice 1707): sounding-only change under a visible
        // shelf — the key changed only in its sounding / failed /
        // buffering bits (same ordered names, same night), so the
        // chips below are the same views in the same order: update
        // them in place instead of removeAllViews + rebuilding every
        // chip. The rebuild used to snap the new gold ring + ▶ +
        // gold name in one frame (and reload every photo) on every
        // auto-advance / Next while Main sat on top, while the grid
        // card crossfades (v14.31) and the chooser / library rows
        // melt (v14.48/v14.49). The ring now crossfades through
        // Fx.crossfadeRing (v14.16, seeded at build time below so a
        // chip that already wears gold melts out instead of sticking),
        // the ▶/⚠ mark + gold name stay instant text swaps by the
        // v14.16 settled decision, and the newly-sounding chip melts
        // in 250ms (the v14.48 rhythm). A fail / buffering flip on
        // the same sayadaw crossfades gold <-> red with no chip fade
        // (no new sounding chip); a stop (sounding -> null) melts
        // nothing out, the old ring just crossfades away. A star /
        // unstar, a theme flip, a search narrowing or a rotation
        // restore all miss the same-names / same-night / visible
        // gate and take the rebuild path below — and on that path
        // the added chip melts in (v14.99 (slice 1757)) while a
        // removed chip melts out (v15.00 (slice 1758)) — sequenced
        // into one handoff when they share a slot (v15.02).
        boolean sameShelfNames = lastFavShelfNames != null
                && lastFavShelfNames.size() == favList.size();
        if (sameShelfNames) {
            for (int i = 0; i < favList.size(); i++) {
                if (!lastFavShelfNames.get(i).equals(favList.get(i).name)) {
                    sameShelfNames = false;
                    break;
                }
            }
        }
        boolean sameShelfNight = (lastFavShelfNight == cachedNight);
        String prevShelfSounding = lastShelfSounding;
        // v14.99 (slice 1757): the pre-rebuild member names — the
        // added-chip melt below diffs the rebuilt chips against
        // these, so only a genuinely new chip melts in. Captured
        // beside prevShelfSounding (same pattern): null until the
        // first build, so a first build / rotation restore melts
        // nothing (its pour owns the entrance).
        java.util.List<String> prevShelfNames99 = lastFavShelfNames;
        java.util.List<String> names50 = new java.util.ArrayList<String>();
        for (Sayadaw s : favList) names50.add(s.name);
        lastFavShelfNames = names50;
        lastFavShelfNight = cachedNight;
        lastShelfSounding = soundingShelf;
        if (favShelfWrap.getVisibility() == android.view.View.VISIBLE
                && sameShelfNames && sameShelfNight) {
            for (int i = 0; i < favList.size(); i++) {
                Sayadaw s = favList.get(i);
                android.view.View chip = favShelf.getChildAt(i);
                if (chip == null || !(chip instanceof android.view.ViewGroup)) continue;
                boolean shelfPlaying = !AudioService.liveEnded
                        && soundingShelf != null
                        && soundingShelf.equals(s.name);
                android.view.View ring = ((android.view.ViewGroup) chip).getChildAt(0);
                Fx.crossfadeRing(ring, shelfPlaying && AudioService.liveTrackError
                        ? R.drawable.shelf_ring_failed
                        : shelfPlaying ? R.drawable.shelf_ring_playing : 0);
                android.view.View nameV = ((android.view.ViewGroup) chip).getChildAt(1);
                if (nameV instanceof TextView) {
                    TextView name = (TextView) nameV;
                    name.setText(Fx.soundingRowMark(shelfPlaying, AudioService.liveTrackError, AudioService.liveBuffering ? Lang.t(MainActivity.this, "notif_state_buffering") : "", "\u25B6") + s.name);
                    name.setTextColor(shelfPlaying ? android.graphics.Color.parseColor("#C9A227")
                            : (cachedNight ? android.graphics.Color.WHITE
                            : android.graphics.Color.parseColor("#3B2314")));
                }
                if (shelfPlaying && (prevShelfSounding == null
                        || !prevShelfSounding.equals(s.name))) {
                    Fx.fadeIn(chip, 250);
                    // v15.04 (slice 1762): record the melt — a rebuild
                    // landing inside this 250ms window carries the
                    // chip's alpha to its fresh view (below).
                    shelfMeltViews62.put(s.name, chip);
                }
            }
            // v14.38 re-arm, same as the rebuild path: a re-appear
            // landing mid-fade-out rides back to full instead of
            // letting a stale fadeOut end action GONE the wrap.
            if (favShelfWrap.getAlpha() < 0.999f) Fx.fadeIn(favShelfWrap, 250);
            return;
        }
        // v14.15 (slice 1669): remember whether the shelf was
        // hidden — only a GONE -> VISIBLE appearance pours (below).
        boolean shelfWasGone = favShelfWrap.getVisibility() != android.view.View.VISIBLE;
        // v15.00 (slice 1758): capture the departing chips' views
        // BEFORE the rebuild below destroys them — the old child
        // order is the prevShelfNames99 order (chips are always
        // built in favList order and lastFavShelfNames is set in
        // that order), so the chip for a removed name is the child
        // at that name's index. Only a visible shelf can show a
        // departure: a first appearance pours instead (shelfWasGone)
        // and a first build / rotation restore has a null previous
        // list. A removal under the in-place path above is
        // impossible (same ordered names), so capturing here covers
        // every case that reaches the rebuild.
        java.util.List<android.view.View> departing00 = null;
        java.util.List<Integer> departingIdx00 = null;
        if (!shelfWasGone && prevShelfNames99 != null) {
            // v15.05 (slice 1763): the child order is NOT the
            // prev-list order while a departure from an earlier
            // rebuild is still re-seated in the shelf — getChildAt
            // (i00) would grab the fading departure instead of the
            // member chip for the removed name (a second unstar
            // landing inside the 250ms window used to re-fade the
            // OLD departure and let the NEWLY unstarred chip pop
            // out with no fade at all). Filter the recorded
            // departures out first; the survivors are the member
            // chips in prev-list order, so the index below resolves
            // the right view. A stale record (already detached)
            // never matches a child, so it filters nothing.
            java.util.List<android.view.View> members00 = new java.util.ArrayList<android.view.View>();
            for (int m00 = 0; m00 < favShelf.getChildCount(); m00++) {
                android.view.View c00 = favShelf.getChildAt(m00);
                if (c00 != null && !shelfDepartingViews63.contains(c00)) members00.add(c00);
            }
            for (int i00 = 0; i00 < prevShelfNames99.size(); i00++) {
                boolean stillThere00 = false;
                for (Sayadaw s00 : favList) {
                    if (s00.name.equals(prevShelfNames99.get(i00))) {
                        stillThere00 = true;
                        break;
                    }
                }
                if (stillThere00) continue;
                android.view.View dv00 = i00 < members00.size() ? members00.get(i00) : null;
                if (dv00 == null) continue;
                if (departing00 == null) {
                    departing00 = new java.util.ArrayList<android.view.View>();
                    departingIdx00 = new java.util.ArrayList<Integer>();
                }
                departing00.add(dv00);
                departingIdx00.add(i00);
            }
        }
        // v15.04 (slice 1762): snapshot the alpha of every chip still
        // melting in, before the rebuild below destroys the views — the
        // fresh chip for the same name starts there and fades on (the
        // v3.52 rule, moved across the view replacement). Only views
        // still attached to the shelf count (a superseding rebuild's
        // leftovers are already detached), only names that survive into
        // the new list (a departing chip's name leaves, so its fade is
        // not carried — the cut departure is the next piece), and only
        // alpha under 0.999 (a settled chip stores nothing and rebuilds
        // at full alpha exactly as today). The record map is cleared
        // here, so a stale entry never outlives one cycle.
        java.util.Map<String, Float> carryAlpha62 = null;
        if (!shelfMeltViews62.isEmpty()) {
            for (java.util.Map.Entry<String, android.view.View> e62 : shelfMeltViews62.entrySet()) {
                android.view.View v62 = e62.getValue();
                if (v62 == null || v62.getParent() != favShelf) continue;
                boolean survives62 = false;
                for (Sayadaw s62 : favList) {
                    if (s62.name.equals(e62.getKey())) { survives62 = true; break; }
                }
                if (!survives62) continue;
                if (v62.getAlpha() < 0.999f) {
                    if (carryAlpha62 == null) carryAlpha62 = new java.util.HashMap<String, Float>();
                    carryAlpha62.put(e62.getKey(), v62.getAlpha());
                }
            }
            shelfMeltViews62.clear();
        }
        // v15.05 (slice 1763): snapshot the departures still fading
        // out — the mirror of the v15.04 melt carry above. A rebuild
        // landing inside a departure's 250ms fade-out (removeAllViews
        // below) used to detach the re-seated chip mid-fade at alpha
        // ~0.36 and it vanished in one frame (sim1762 B6, pre and post
        // identical). Each recorded departure still attached to the
        // shelf is carried below: re-seated at its old slot and its
        // fade continued from the captured alpha over the remaining
        // time (250 * sqrt(alpha) — the DecelerateInterpolator curve
        // is quadratic, so the restarted fade reproduces the original
        // curve exactly and the departure still leaves at t=250, when
        // the v15.02 parked chip expects its slot free). Only alpha
        // above 0.001 carries — an already-invisible chip has nothing
        // left to show — and unlike the melt carry above there is no
        // 0.999 ceiling: a fade that only just started sits near 1.0
        // and detaching it would pop a fully-visible chip. The record
        // list is cleared here, so a stale entry never outlives one
        // cycle; the re-seat below records the carried chip again, so
        // a third rebuild landing inside the continued fade carries it
        // onward the same way.
        java.util.List<android.view.View> carryDep63 = null;
        java.util.List<Float> carryDepAlpha63 = null;
        java.util.List<Integer> carryDepIdx63 = null;
        if (!shelfDepartingViews63.isEmpty()) {
            for (android.view.View v63 : shelfDepartingViews63) {
                if (v63 == null || v63.getParent() != favShelf) continue;
                float a63 = v63.getAlpha();
                if (a63 <= 0.001f) continue;
                int idx63 = favShelf.indexOfChild(v63);
                if (idx63 < 0) continue;
                if (carryDep63 == null) {
                    carryDep63 = new java.util.ArrayList<android.view.View>();
                    carryDepAlpha63 = new java.util.ArrayList<Float>();
                    carryDepIdx63 = new java.util.ArrayList<Integer>();
                }
                carryDep63.add(v63);
                carryDepAlpha63.add(a63);
                carryDepIdx63.add(idx63);
            }
            shelfDepartingViews63.clear();
        }
        // v15.08 (slice 1767): snapshot the glides still running —
        // the mirror of the v15.04 melt snapshot and the v15.05
        // departure snapshot above. Only views still attached to the
        // shelf count (a superseding rebuild's leftovers are already
        // detached), only names that survive into the new list (a
        // gliding chip that was itself unstarred leaves with no fresh
        // chip to carry), and only a translationX above dust (0.01f)
        // — a settled glide sits at exactly 0 and records nothing,
        // while a mid-glide chip is still visibly displaced (the
        // curve drops x below 0.5 within ~73ms, so 0.5 would miss
        // most of the window). The start and duration come from the
        // glide-time record: the
        // remainder is duration * sqrt(x / start) (the
        // DecelerateInterpolator curve is quadratic, the v15.05
        // remainder rule), so a stacked glide (start above one slot)
        // carries its true remainder, not a one-slot guess. The record
        // maps are cleared here, so a stale entry never outlives one
        // cycle; the apply block below records the carried glide
        // again, so a third rebuild carries it onward.
        java.util.Map<String, Float> carryGlideX65 = null;
        java.util.Map<String, Float> carryGlideStart65 = null;
        java.util.Map<String, Long> carryGlideDur65 = null;
        if (!shelfGlideViews65.isEmpty()) {
            for (java.util.Map.Entry<String, android.view.View> e65 : shelfGlideViews65.entrySet()) {
                android.view.View v65 = e65.getValue();
                if (v65 == null || v65.getParent() != favShelf) continue;
                boolean survives65 = false;
                for (Sayadaw s65 : favList) {
                    if (s65.name.equals(e65.getKey())) { survives65 = true; break; }
                }
                if (!survives65) continue;
                float x65 = v65.getTranslationX();
                if (Math.abs(x65) <= 0.01f) continue;
                Float st65 = shelfGlideStart65.get(e65.getKey());
                Long du65 = shelfGlideDur65.get(e65.getKey());
                if (st65 == null || du65 == null || st65 <= 0.01f || du65 <= 0) continue;
                if (carryGlideX65 == null) {
                    carryGlideX65 = new java.util.HashMap<String, Float>();
                    carryGlideStart65 = new java.util.HashMap<String, Float>();
                    carryGlideDur65 = new java.util.HashMap<String, Long>();
                }
                carryGlideX65.put(e65.getKey(), x65);
                carryGlideStart65.put(e65.getKey(), st65);
                carryGlideDur65.put(e65.getKey(), du65);
            }
            shelfGlideViews65.clear();
            shelfGlideStart65.clear();
            shelfGlideDur65.clear();
        }
        favShelf.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        for (final Sayadaw s : favList) {
            boolean shelfPlaying = !AudioService.liveEnded
                    && soundingShelf != null
                    && soundingShelf.equals(s.name);
            android.widget.LinearLayout chip = new android.widget.LinearLayout(this);
            chip.setOrientation(android.widget.LinearLayout.VERTICAL);
            chip.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            android.widget.LinearLayout.LayoutParams clp =
                    new android.widget.LinearLayout.LayoutParams(
                            (int) (72 * d),
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.setMargins(0, 0, (int) (10 * d), 0);
            chip.setLayoutParams(clp);
            Fx.press(chip); // v2.08 press feedback, like the grid cards
            // v15.08 (slice 1767): the chip carries its sayadaw name
            // in its tag — a removal end action runs 250ms later, when
            // child indices are no longer reliable (re-seated
            // departures interleave the shelf), so the glide it starts
            // is recorded against the name, which rides the view
            // instead of an index. The unkeyed tag slot on a shelf
            // chip is otherwise unused (the keyed R.id tags live on
            // strip views, never here), and nothing else reads it.
            chip.setTag(s.name);
            // v13.87: 56dp ring frame around the 52dp portrait — the
            // frame's background is the gold ring while sounding and
            // nothing otherwise, so the photo itself never shrinks
            // or moves; the ring reads as a 2dp rim around it.
            android.widget.FrameLayout ring = new android.widget.FrameLayout(this);
            int rp = (int) (56 * d);
            ring.setLayoutParams(new android.widget.LinearLayout.LayoutParams(rp, rp));
            // v14.07 (slice 1102): the chip ring names the failure too —
            // gold while sounding, the red failed ring while sounding +
            // failed (reused shelf_ring_failed.xml, v14.05), nothing
            // otherwise. The chip name below says ⚠ through the same
            // soundingRowMark the grid card uses, so mark and ring can
            // never disagree; loading keeps the gold ring (v14.06:
            // the ring says sounding, the name carries the word).
            int ringRes50 = shelfPlaying && AudioService.liveTrackError
                    ? R.drawable.shelf_ring_failed
                    : shelfPlaying ? R.drawable.shelf_ring_playing : 0;
            if (ringRes50 != 0) ring.setBackgroundResource(ringRes50);
            // v14.50 (slice 1707): seed Fx's recorded ring target so
            // the in-place sounding update above crossfades from the
            // ring actually showing — without it a chip that already
            // wears gold would read as "no ring" and the crossfade
            // would no-op, leaving the gold ring stuck after a stop.
            Fx.seedRing(ring, ringRes50);
            CircleImageView photo = new CircleImageView(this);
            int px = (int) (52 * d);
            android.widget.FrameLayout.LayoutParams flp =
                    new android.widget.FrameLayout.LayoutParams(px, px);
            flp.gravity = android.view.Gravity.CENTER;
            photo.setLayoutParams(flp);
            photo.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            ImageLoader.load(this, s.photo, s.name, photo);
            ring.addView(photo);
            TextView name = new TextView(this);
            android.widget.LinearLayout.LayoutParams nlp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            nlp.setMargins(0, (int) (4 * d), 0, 0);
            name.setLayoutParams(nlp);
            // v14.07 (slice 1102): the chip name borrows the grid card's
            // soundingRowMark (v14.05/v14.06) — the hardcoded "▶ " used to
            // survive a failed stream, so a favourited sayadaw's chip read
            // as playing while its card already said ⚠. Error wins, then
            // the loading word, in the glyph's own slot (the Main mirror
            // rule, like the grid card); the gold colour below stays —
            // gold says sounding, the mark says failed.
            name.setText(Fx.soundingRowMark(shelfPlaying, AudioService.liveTrackError, AudioService.liveBuffering ? Lang.t(MainActivity.this, "notif_state_buffering") : "", "\u25B6") + s.name);
            name.setTextColor(shelfPlaying ? android.graphics.Color.parseColor("#C9A227")
                    : (cachedNight ? android.graphics.Color.WHITE
                    : android.graphics.Color.parseColor("#3B2314")));
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            name.setGravity(android.view.Gravity.CENTER);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.addView(ring);
            chip.addView(name);
            chip.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    openSayadawCard(s, v);
                }
            });
            favShelf.addView(chip);
        }
        favShelfWrap.setVisibility(android.view.View.VISIBLE);
        // v14.38 (slice 1693): re-arm guard — a re-appear landing
        // mid-fade-out rides the v14.33 sleep-line pattern: the wrap
        // is still VISIBLE but half-transparent, and a stale fadeOut
        // end action would GONE it. Fx.fadeIn bumps the fade gen
        // (v3.62), so the stale end action is suppressed and the
        // wrap rides back to full. A steady VISIBLE shelf (alpha 1)
        // never re-fades.
        if (favShelfWrap.getAlpha() < 0.999f) Fx.fadeIn(favShelfWrap, 250);
        // v14.15 (slice 1669): first-appearance cascade — fresh
        // launch with favourites, first favourite added, or a
        // cleared search bringing the shelf back. A rebuild while
        // visible never pours, and a rotation's first build is
        // skipped by shelfSkipFirstPour above.
        if (shelfWasGone && !shelfSkipFirstPour) Fx.staggerChildren(favShelf);
        // v14.99 (slice 1757): melt a newly-starred chip in — a
        // rebuild while the shelf is already visible (star/unstar
        // via the card long-press) never pours (v14.15 above), so
        // the added chip used to pop in in one frame while the
        // sounding chip melts (v14.50) and the first appearance
        // pours (v14.15) beside it. The added chip now fades in
        // 250ms (the v14.48 rhythm) once the rebuild has bound it
        // — the fade lands in the same frame as the rebuild (the
        // chips are new views, never drawn yet, so the first drawn
        // frame already shows the chip fading in, never a flash).
        // A removal is handled below (v15.00 (slice 1758)): the
        // departing chip is re-seated in its old slot and fades
        // out instead of just leaving — the v14.75 case (a
        // departing row fades nothing) stays the rule elsewhere,
        // but unstarring is the user's own gesture and the chip
        // leaving IS its feedback, the mirror of the melt-in. A
        // theme flip or a rotation restore removes no name, so it
        // fades nothing; a first appearance pours instead
        // (shelfWasGone above).
        // v15.02 (slice 1760): a rebuild that BOTH adds and removes
        // used to let the melt-in and the fade-out collide on one
        // slot — the departing chip re-seats at its old index, so an
        // added chip whose home is at-or-after that index melts in
        // one slot right of home, then jumps home in a single frame
        // at t=250 when the departure is removed, at the exact frame
        // it reaches full opacity (its entrance read as two motions,
        // slice 1758's open question (b), traced first-hand this
        // run). A displaced added chip is now parked at alpha 0 and
        // its fadeIn is posted after the departure leaves — so the
        // rank-close has already moved it home invisibly by the time
        // it melts in: one handoff (out 250 -> glide 250 -> in 250),
        // no visible teleport. Undisplaced added chips still melt in
        // at once, as today. v15.06 (slice 1764): the wait is anchored
        // to the departure's own end (shelfParkDelay64) — a carried
        // departure leaves sooner, so the wait ends when the slot
        // frees, and the melt carry re-parks a still-parked chip
        // with the same anchor instead of melting it in early.
        // v15.07 (slice 1766): the anchor is the departure's end
        // PLUS the 250ms rank-close glide — the fadeIn used to fire
        // at the departure's leave, the exact frame the glide starts,
        // so the chip melted in while sliding home (slice 1765).
        if (!shelfWasGone && prevShelfNames99 != null) {
            for (int i99 = 0; i99 < favList.size(); i99++) {
                if (!prevShelfNames99.contains(favList.get(i99).name)) {
                    android.view.View added99 = favShelf.getChildAt(i99);
                    if (added99 == null) continue;
                    // v15.04 (slice 1762): record the melt — the
                    // undisplaced chip below and the parked chip
                    // (v15.02) alike; a rebuild landing inside the
                    // 250ms window carries this chip's alpha to its
                    // fresh view (snapshot below).
                    shelfMeltViews62.put(favList.get(i99).name, added99);
                    // v15.02 (slice 1760): displaced by a departing
                    // chip re-seated at-or-before this home index —
                    // park it invisible and melt it in after the
                    // departure leaves, at its home slot.
                    boolean displaced02 = false;
                    if (departingIdx00 != null) {
                        for (int d02 = 0; d02 < departingIdx00.size(); d02++) {
                            if (departingIdx00.get(d02) <= i99) {
                                displaced02 = true;
                                break;
                            }
                        }
                    }
                    // v15.05 (slice 1763): a carried departure
                    // re-seated at-or-before this chip's home index
                    // displaces it exactly like a fresh one —
                    // without this the chip would melt in at once,
                    // then jump home when the carried departure
                    // leaves (the v15.02 teleport, reintroduced by
                    // the carry). Only reached when no fresh
                    // departure already displaced it, so the
                    // captured index compares exactly against the
                    // home index.
                    if (!displaced02 && carryDepIdx63 != null) {
                        for (int d63 = 0; d63 < carryDepIdx63.size(); d63++) {
                            if (carryDepIdx63.get(d63) <= i99) {
                                displaced02 = true;
                                break;
                            }
                        }
                    }
                    if (!displaced02) {
                        Fx.fadeIn(added99, 250);
                        continue;
                    }
                    // v15.06 (slice 1764): anchor the wait to the
                    // departure's own end, not to this rebuild — the
                    // parked chip melts in when its slot frees. A
                    // fresh departure's fade starts now, so 250 is its
                    // exact end; a carried departure leaves at its
                    // remaining time, so a fresh 250 here would hold
                    // the chip invisible past the slot freeing (the
                    // 1763 open question, traced first-hand: unstar B
                    // at t=0, star D at t=100, D's wait stretched to
                    // t=350 while B left at t=250). If the remainder
                    // is already spent, melt in at once — the slot is
                    // free. v15.07 (slice 1766): the anchor adds the
                    // 250ms rank-close glide, so the melt starts when
                    // the glide has finished, not when it starts.
                    final long delay02 = shelfParkDelay64(i99, departingIdx00, carryDepIdx63, carryDepAlpha63);
                    if (delay02 <= 0) {
                        Fx.fadeIn(added99, 250);
                        continue;
                    }
                    final android.view.View addedFinal02 = added99;
                    added99.setAlpha(0f);
                    added99.postDelayed(new Runnable() {
                        @Override public void run() {
                            // A superseding rebuild landing mid-wait
                            // detached this view; its fresh chip owns
                            // the entrance then, so stay a no-op.
                            if (addedFinal02.getParent() == null) return;
                            Fx.fadeIn(addedFinal02, 250);
                        }
                    }, delay02);
                }
            }
        }
        // v15.04 (slice 1762): carry a mid-melt chip's alpha across
        // the rebuild — for each name the snapshot caught mid-fade,
        // the fresh chip starts at the captured alpha and fades in
        // 250ms (the v14.48 rhythm), so a rebuild landing inside the
        // melt window reads as one continuous fade instead of a pop
        // to opaque. Recorded again, so a third rebuild landing
        // inside THIS fade carries it onward the same way. Runs
        // before the v15.00 re-seat below, so the favList indices
        // are still exact; a carried chip beside a re-seated
        // departure rides the v15.03 rank-close glide home while it
        // melts (glideHome never touches alpha, fadeIn never touches
        // translationX — the two motions compose for a chip already
        // melting; the v15.02 parked chip no longer composes —
        // v15.07 waits out the glide first). Settled chips carry
        // nothing (the snapshot only stores alpha under 0.999).
        // v15.06 (slice 1764): a chip still PARKED — displaced by a
        // departure at-or-before its home AND never started melting
        // (alpha under 0.001) — is re-parked below, not melted in:
        // the carry used to start its fade at once, at its displaced
        // slot, while the departure was still fading — the compound
        // fade+slide the v15.02 park was built to prevent (traced
        // first-hand this run: unstar B + star D at t=0, rebuild at
        // t=100, D melted in from t=100 instead of waiting for B to
        // leave at t=250). Its melt-in posts for the departure's own
        // end (shelfParkDelay64), on this fresh view, and it is
        // recorded again so a third rebuild carries it onward. A
        // mid-melt chip (alpha above 0.001) keeps the carry exactly
        // as today — it rides the v15.03 glide home while it melts.
        if (carryAlpha62 != null) {
            for (int i62 = 0; i62 < favList.size(); i62++) {
                Float a62 = carryAlpha62.get(favList.get(i62).name);
                if (a62 == null || a62 >= 0.999f) continue;
                android.view.View chip62 = favShelf.getChildAt(i62);
                if (chip62 == null) continue;
                // v15.06 (slice 1764): still parked — keep it
                // invisible and post its melt-in for the departure's
                // own end (the anchored delay, never a fresh 250),
                // so the wait ends when the slot frees no matter how
                // many rebuilds land inside it. The old view's
                // runnable no-ops once detached (parent check), so
                // this fresh view owns the entrance now. v15.07
                // (slice 1766): the anchored delay now also carries
                // the 250ms rank-close glide, so a re-parked chip
                // keeps the extended anchor across rebuilds.
                long parkDelay62 = shelfParkDelay64(i62, departingIdx00, carryDepIdx63, carryDepAlpha63);
                // v15.08 (slice 1767): a still-parked chip that is
                // ALSO mid-glide waits out its carried glide's
                // remainder too — its departure already left, so the
                // helper above returns -1, but the chip is still
                // sliding home (the glide carry below parks it at its
                // captured translationX) and melting in now would be
                // the v15.07 melt-while-sliding through the carry.
                // The wait is the LONGER of the two: a departure
                // still displacing it already carries the full glide
                // in its own delay (departure end + 250, the v15.07
                // anchor), so the max keeps the melt starting exactly
                // when the glide ends either way.
                if (a62 < 0.001f && carryGlideX65 != null) {
                    Float gx65 = carryGlideX65.get(favList.get(i62).name);
                    Float gs65 = carryGlideStart65.get(favList.get(i62).name);
                    Long gd65 = carryGlideDur65.get(favList.get(i62).name);
                    if (gx65 != null && gs65 != null && gd65 != null) {
                        long gr65 = shelfGlideRemain65(gx65, gs65, gd65);
                        if (gr65 >= 0 && gr65 > parkDelay62) parkDelay62 = gr65;
                    }
                }
                if (a62 < 0.001f && parkDelay62 >= 0) {
                    final android.view.View chipFinal62 = chip62;
                    chip62.setAlpha(0f);
                    if (parkDelay62 <= 0) {
                        Fx.fadeIn(chip62, 250);
                    } else {
                        chip62.postDelayed(new Runnable() {
                            @Override public void run() {
                                // A superseding rebuild landing mid-wait
                                // detached this view; its fresh chip owns
                                // the entrance then, so stay a no-op.
                                if (chipFinal62.getParent() == null) return;
                                Fx.fadeIn(chipFinal62, 250);
                            }
                        }, parkDelay62);
                    }
                    shelfMeltViews62.put(favList.get(i62).name, chip62);
                    continue;
                }
                chip62.setAlpha(a62);
                Fx.fadeIn(chip62, 250);
                shelfMeltViews62.put(favList.get(i62).name, chip62);
            }
        }
        // v15.08 (slice 1767): carry a mid-glide chip's travel across
        // the rebuild — for each name the snapshot caught mid-glide,
        // the fresh chip parks at the captured translationX and glides
        // home over the exact remainder (shelfGlideRemain65), so a
        // rebuild landing inside the glide window reads as one
        // continuous slide instead of a snap to home. The remainder
        // reproduces the original DecelerateInterpolator curve, so the
        // chip lands home at the same instant as the uninterrupted
        // glide. Recorded again (start = the captured position, the
        // remainder as the duration), so a third rebuild carries it
        // onward the same way. Runs after the melt carry above and
        // before the v15.00 re-seat below, so the favList indices are
        // still exact; a carried chip beside a re-seated departure
        // rides the next rank-close glide home while it glides
        // (glideHomeFrom never touches alpha, fadeIn never touches
        // translationX — the two carries compose, and a parked chip
        // gliding invisibly keeps its melt-in at the instant this
        // carried glide ends, step 6 above).
        if (carryGlideX65 != null) {
            for (int i65 = 0; i65 < favList.size(); i65++) {
                Float x65 = carryGlideX65.get(favList.get(i65).name);
                if (x65 == null) continue;
                android.view.View chip65 = favShelf.getChildAt(i65);
                if (chip65 == null) continue;
                Float st65o = carryGlideStart65.get(favList.get(i65).name);
                Long du65o = carryGlideDur65.get(favList.get(i65).name);
                if (st65o == null || du65o == null) continue;
                long remain65 = shelfGlideRemain65(x65, st65o, du65o);
                if (remain65 < 0) continue;
                Fx.glideHomeFrom(chip65, x65, remain65);
                shelfGlideViews65.put(favList.get(i65).name, chip65);
                shelfGlideStart65.put(favList.get(i65).name, x65);
                shelfGlideDur65.put(favList.get(i65).name, remain65);
            }
        }
        // v15.00 (slice 1758): melt a removed chip out — the
        // mirror of the v14.99 melt-in. The departing chip was
        // captured above before the rebuild destroyed it; it is
        // re-seated here in its old slot AFTER the melt loop (so
        // the melt indices above still resolve the new chips) and
        // fades out 250ms (the v14.48 rhythm) through the new
        // Fx.fadeOutAndRemove, which removes it from the shelf at
        // the end and restores its alpha. The shelf shows N+1 chips
        // for the 250ms — the fading chip beside the rebuilt ones —
        // then the siblings glide home (v15.03, slice 1761) instead
        // of jumping one slot left in a single frame: the removal
        // end action parks each trailing sibling one slot right of
        // its new layout position and animates it to 0 over 250ms,
        // so the rank-close reads as one motion with the fade-out.
        // A displaced added chip
        // (v15.02, slice 1760) rides that same rank-close home
        // invisibly and melts in after it, so the removal also frees
        // the added chip's slot. Inserting in ascending
        // old-index order keeps several departures in their old
        // relative order; the clamp covers a departure past the
        // new end (the removed last chip). The fade is gen-guarded
        // (v3.62): a rebuild landing mid-fade detaches the chip
        // first and the stale end action is a no-op.
        if (departing00 != null) {
            for (int i00 = 0; i00 < departing00.size(); i00++) {
                android.view.View dv00 = departing00.get(i00);
                int at00 = departingIdx00.get(i00);
                if (at00 > favShelf.getChildCount()) at00 = favShelf.getChildCount();
                favShelf.addView(dv00, at00);
                // v15.08 (slice 1767): a departing chip captured
                // mid-glide keeps its translationX on re-seat and
                // would fade out up to a slot right of its own slot,
                // overlapping its neighbour while fading (the v15.00
                // comment says it fades out "in its old slot"). Cancel
                // the recorded glide first — a bare setTranslationX
                // would be undone on the next animation frame by the
                // still-running glide animator — then reset to 0, so
                // it fades out exactly in its slot.
                Fx.cancelGlide(dv00);
                dv00.setTranslationX(0f);
                // v15.05 (slice 1763): record the departure — a
                // rebuild landing inside this fade-out re-seats it
                // and continues the fade (snapshot above).
                shelfDepartingViews63.add(dv00);
                // v15.03 (slice 1761): the departing chip's slot
                // width — its measured width plus its right margin
                // (72dp + 10dp as built above) — is the distance
                // every trailing sibling travels when the rank
                // closes; measured, so a density rounding quirk
                // can never leave a 1px step at the end of the
                // glide. The 82dp fallback covers a chip that has
                // not been laid out yet.
                final int atFinal03 = at00;
                final float shift03;
                {
                    float s03 = 0f;
                    if (dv00.getWidth() > 0) {
                        s03 = dv00.getWidth();
                        android.view.ViewGroup.LayoutParams lp03 =
                                dv00.getLayoutParams();
                        if (lp03 instanceof android.widget.LinearLayout.LayoutParams)
                            s03 += ((android.widget.LinearLayout.LayoutParams) lp03).rightMargin;
                    }
                    if (s03 <= 0f) s03 = 82 * d;
                    shift03 = s03;
                }
                Fx.fadeOutAndRemove(dv00, 250, new Runnable() {
                    @Override public void run() {
                        // v15.03 (slice 1761): glide the trailing
                        // siblings home — each child at-or-after the
                        // departed slot (the first trailing sibling
                        // now sits AT that index) is parked one slot
                        // right of its new layout position and
                        // animated to 0 over 250ms, so the rank-close
                        // reads as one motion with the fade-out
                        // instead of a single-frame jump. A second
                        // departure's removal adds its own slot to a
                        // sibling already gliding (glideHome cancels
                        // only the recorded glide, never an alpha
                        // fade — the v15.02 parked chip melts in
                        // untouched). No trailing sibling (the
                        // removed last chip) glides nothing.
                        for (int i03 = atFinal03; i03 < favShelf.getChildCount(); i03++) {
                            android.view.View sib03 = favShelf.getChildAt(i03);
                            // v15.09 (slice 1768): never glide a still-fading
                            // departure — a second departure re-seated behind
                            // this one is still fading out in its own slot,
                            // and this loop used to park it one slot right
                            // and slide it home while it faded (traced
                            // first-hand this run: unstar B at t=0, unstar
                            // C at t=200, B leaves at t=250 and C — 64%
                            // opaque — slides a full slot left while fading
                            // to 0 at t=450, the v15.08 "fades out in its
                            // old slot" rule broken by the end action
                            // itself). A departure's own end action closes
                            // its rank when it leaves, so skipping it here
                            // loses nothing; the record list still holds
                            // it (cleared only by a rebuild snapshot, and
                            // a rebuild would have suppressed this stale
                            // end action through the fade gen guard).
                            if (shelfDepartingViews63.contains(sib03)) continue;
                            Fx.glideHome(sib03, shift03, 250);
                            // v15.08 (slice 1767): record the glide —
                            // a rebuild landing mid-glide carries it
                            // below instead of snapping the fresh chip
                            // home. The name comes from the chip's tag
                            // (step 2): the child index is meaningless
                            // here, re-seated departures interleave.
                            // The start is the parked translationX just
                            // after glideHome (old position + shift),
                            // the duration the 250 literal below.
                            Object tag03 = sib03 == null ? null : sib03.getTag();
                            if (tag03 instanceof String) {
                                shelfGlideViews65.put((String) tag03, sib03);
                                shelfGlideStart65.put((String) tag03, sib03.getTranslationX());
                                shelfGlideDur65.put((String) tag03, 250L);
                            }
                        }
                    }
                });
            }
        }
        // v15.05 (slice 1763): re-seat the carried departures — the
        // mirror of the v15.00 re-seat above. Each departure the
        // snapshot caught mid-fade goes back into its old slot at its
        // captured alpha and its fade-out continues over the remaining
        // time (250 * sqrt(alpha), the exact remainder of the
        // DecelerateInterpolator curve), so a rebuild landing inside
        // the 250ms window reads as one continuous fade instead of a
        // one-frame cut at alpha ~0.36. The v15.03 rank-close glide
        // rides the removal end action exactly as in the v15.00
        // block, and the chip is recorded again, so a third rebuild
        // landing inside the continued fade carries it onward. Runs
        // after the v15.00 re-seat, in ascending captured-index
        // order, so a fresh departure and a carried one keep their
        // old relative order; the clamp covers a departure past the
        // new end. A carried departure beside a carried melt-in chip
        // (v15.04) composes the same way — glideHome never touches
        // alpha and the continued fade never touches translationX.
        if (carryDep63 != null) {
            for (int i63 = 0; i63 < carryDep63.size(); i63++) {
                android.view.View dv63 = carryDep63.get(i63);
                float a63 = carryDepAlpha63.get(i63);
                int at63 = carryDepIdx63.get(i63);
                if (at63 > favShelf.getChildCount()) at63 = favShelf.getChildCount();
                favShelf.addView(dv63, at63);
                // v15.08 (slice 1767): same reset as the v15.00
                // re-seat above — a carried departure re-seated while
                // a later departure's removal glided it would otherwise
                // fade out displaced from its slot.
                Fx.cancelGlide(dv63);
                dv63.setTranslationX(0f);
                dv63.setAlpha(a63);
                // v15.03 (slice 1761): the departing chip's slot
                // width — measured exactly as in the v15.00 block
                // above, so the rank-close glide travels the same
                // distance when this departure leaves.
                final int atFinal63 = at63;
                final float shift63;
                {
                    float s63 = 0f;
                    if (dv63.getWidth() > 0) {
                        s63 = dv63.getWidth();
                        android.view.ViewGroup.LayoutParams lp63 =
                                dv63.getLayoutParams();
                        if (lp63 instanceof android.widget.LinearLayout.LayoutParams)
                            s63 += ((android.widget.LinearLayout.LayoutParams) lp63).rightMargin;
                    }
                    if (s63 <= 0f) s63 = 82 * d;
                    shift63 = s63;
                }
                // v15.05: the exact remainder of the fade — the
                // DecelerateInterpolator curve is quadratic, so a
                // fresh fade over 250 * sqrt(alpha) from the captured
                // alpha reproduces the original 1 -> 0 curve and the
                // departure still leaves at t=250, when the v15.02
                // parked chip expects its slot free.
                long remain63 = (long) (250 * Math.sqrt(a63));
                if (remain63 < 1) remain63 = 1;
                Fx.fadeOutAndRemove(dv63, remain63, new Runnable() {
                    @Override public void run() {
                        for (int j63 = atFinal63; j63 < favShelf.getChildCount(); j63++) {
                            android.view.View sib63 = favShelf.getChildAt(j63);
                            // v15.09 (slice 1768): never glide a still-fading
                            // departure — same as the v15.00 loop above: a
                            // carried departure leaving through this end
                            // action used to glide a second, still-fading
                            // departure home while it faded (B leaves via
                            // its carried fade at t=250, C — unstarred at
                            // t=200 — slides a full slot left while fading
                            // to 0 at t=450). Its own end action closes
                            // its rank when it leaves.
                            if (shelfDepartingViews63.contains(sib63)) continue;
                            Fx.glideHome(sib63, shift63, 250);
                            // v15.08 (slice 1767): record the glide —
                            // same as the v15.00 block above (the
                            // carried departure's removal glides the
                            // same trailing siblings home).
                            Object tag63 = sib63 == null ? null : sib63.getTag();
                            if (tag63 instanceof String) {
                                shelfGlideViews65.put((String) tag63, sib63);
                                shelfGlideStart65.put((String) tag63, sib63.getTranslationX());
                                shelfGlideDur65.put((String) tag63, 250L);
                            }
                        }
                    }
                });
                shelfDepartingViews63.add(dv63);
            }
        }
    }

    // v4.22: hide the search keyboard — the only keyboard-hide
    // sites in the app were the two IME Search actions (v4.20),
    // so every in-place action below left the keyboard covering
    // the grid it had just scrolled or the player it toggled.
    // The tapped view shares the search field's window, and a
    // hide with no keyboard open costs nothing.
    private void hideKeyboard(android.view.View v) {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Exception ignored) {}
    }

    private void applyFilter(boolean allowToast) {
        shown.clear();
        // v1.90: NFC-normalise both sides — Myanmar stacking/keystroke
        // variants that look identical but differ in code-point order
        // used to miss, so the same name typed two ways gave
        // different results. Normalising already-normal text is a
        // no-op, so existing matches never move.
        // v10.71: digit-fold both sides (Fx.foldDigits) — a sayadaw
        // group or name numbered in Myanmar digits now matches a
        // Latin-digit keystroke and vice versa, like the talk and
        // library searches below; digit-free names match as before.
        // v10.72: flat match strips all whitespace (Fx.stripWhitespace),
        // not just the plain space — a non-breaking space in a name
        // or a pasted query used to survive the flat strip and miss.
        String q = Fx.foldDigits(java.text.Normalizer.normalize(query.trim(),
                java.text.Normalizer.Form.NFC).toLowerCase());
        // v1.47: space-insensitive match too — typing a name without its
        // spaces (or with extra ones) still finds the sayadaw.
        String qFlat = Fx.stripWhitespace(q);
        // v13.61: the Zawgyi reading of the query — same NFC +
        // digit-fold normalisation as q, tried in addition to the
        // original, never instead of it (mirrors Fx.titleMatches):
        // v13.60 wired Zawgyi search into Talks only, so a
        // Zawgyi-typed sayadaw name still found nothing here on
        // Main. A Unicode query can never lose a match it used to
        // find — conversion only ADDS the Zawgyi reading.
        String qz = Fx.foldDigits(java.text.Normalizer.normalize(Fx.zawgyiToUnicode(query.trim()),
                java.text.Normalizer.Form.NFC).toLowerCase());
        String qzFlat = Fx.stripWhitespace(qz);
        for (Sayadaw s : all) {
            if (favOnly && (favs == null || !favs.contains(s.name))) continue;
            if (q.length() > 0) {
                String n = Fx.foldDigits(java.text.Normalizer.normalize(s.name,
                        java.text.Normalizer.Form.NFC).toLowerCase());
                String g = s.group == null ? "" : Fx.foldDigits(java.text.Normalizer.normalize(s.group,
                        java.text.Normalizer.Form.NFC).toLowerCase());
                if (n.indexOf(q) < 0 && n.indexOf(qz) < 0
                        && g.indexOf(q) < 0 && g.indexOf(qz) < 0
                        && Fx.stripWhitespace(n).indexOf(qFlat) < 0
                        && Fx.stripWhitespace(n).indexOf(qzFlat) < 0
                        && Fx.stripWhitespace(g).indexOf(qFlat) < 0
                        && Fx.stripWhitespace(g).indexOf(qzFlat) < 0) continue;
            }
            shown.add(s);
        }
        // v2.48: premium filter-change entrance — the first grid load
        // pours in (v2.07), but every search after it used to snap
        // the new results in instantly, because lastGridAnimPos
        // stays high once the first load has animated. A real
        // keystroke (allowToast is only true for one — onCreate,
        // rotation and restore all filter silently) that actually
        // changes the shown set now resets the guard, so the new
        // results pour in with the same v2.07 fade + grow. A
        // keystroke that leaves the set unchanged (same size and
        // same leading names) never resets it, so fast typing does
        // not flicker; rotations keep their MAX guard because
        // their applyFilter call is silent, and star taps never
        // reach applyFilter at all, so the rows-only refresh
        // (v1.44) still never re-pours. Visual only.
        // v4.21: a keystroke that changes the shown set also returns the
        // grid to the top — typing while scrolled down used to leave the
        // narrowed results wherever the old scroll position clamped them,
        // so the first matches could sit above the viewport. Only a real
        // keystroke (allowToast) that actually changes the set scrolls;
        // rotations, restores, star taps and fav toggles never reach here
        // with allowToast, so their scroll positions stay put (v1.44).
        // v14.26 (slice 1681): the ★ filter toggle is the same gap
        // in the other direction — toggleFavOnly, the Home long-press
        // reset and the star long-press last-favourite exit all flip
        // or clear favOnly and re-run this filter silently, so the
        // narrowed (or restored) grid snapped in in one frame while
        // a keystroke pours. The flag below arms only at those three
        // gesture sites and rides the same key comparison, so an
        // unchanged set (toggle with every sayadaw favourited) never
        // replays; the scroll-to-top above stays keystroke-only.
        boolean setChanged = false;
        // v14.26: consume the ★ toggle arm on every pass, so a
        // gesture that armed it always pours on this pass and never
        // leaks into a later silent filter (rotation, return).
        boolean favToggleChange = mainFavToggleChange;
        mainFavToggleChange = false;
        if (allowToast || favToggleChange) {
            // v11.69: the key covers EVERY shown name, not just the
            // first three -- a query replacement that swaps a tail
            // item while keeping the size and the first three names
            // ({A,B,C,D,E} -> {A,B,C,D,F}) used to read as
            // "unchanged", so the narrowed grid never re-poured and
            // never scrolled to the top, leaving the first matches
            // above the viewport (the v4.21 bug, still reachable
            // via clear + retype). Joining at most the 100 catalog
            // names per keystroke costs nothing; an actually
            // unchanged set still never resets or scrolls.
            StringBuilder kb = new StringBuilder();
            kb.append(shown.size());
            for (int i = 0; i < shown.size(); i++) kb.append('|').append(shown.get(i).name);
            String key = kb.toString();
            if (!key.equals(lastShownKey)) {
                lastGridAnimPos = -1;
                lastShownKey = key;
                setChanged = true;
            }
        }
        adapter.notifyDataSetChanged();
        refreshFavShelf(); // v13.74: shelf follows every filter run
        if (allowToast && setChanged && gridView != null && gridView.getFirstVisiblePosition() > 0)
            gridView.setSelection(0);
        // v2.02: a search matching no sayadaw used to leave a
        // blank grid with no word at all — the listener could not
        // tell "still loading" from "nothing matches". Say so, once
        // per query (typing on does not re-toast), and only from a
        // real keystroke — the onCreate/rotation applyFilter stays
        // silent so a rotated no-match search does not nag again.
        // v13.81 (Main F1): the no-match toast was filter-blind — with
        // the favourites-only filter on, a sayadaw who EXISTS but is
        // not a favourite read as "not in the app at all" ("ဆရာတော်
        // ရှာမတွေ့ပါ"). When the query matches someone in the full
        // catalogue, say the fav-only word instead; a query matching
        // nobody anywhere keeps the plain not-found word, so a
        // garbage query never claims the name exists. The mirror
        // pass below reuses the filter loop's match (name + group,
        // NFC + digit-fold + flat) without the favOnly gate; the
        // once-per-query guard and the silent paths are untouched.
        if (allowToast && q.length() > 0 && shown.isEmpty()
                && !q.equals(lastEmptyToastQuery)) {
            lastEmptyToastQuery = q;
            String toastKey = "sayadaw_not_found";
            if (favOnly) {
                for (Sayadaw s : all) {
                    String n = Fx.foldDigits(java.text.Normalizer.normalize(s.name,
                            java.text.Normalizer.Form.NFC).toLowerCase());
                    String g = s.group == null ? "" : Fx.foldDigits(java.text.Normalizer.normalize(s.group,
                            java.text.Normalizer.Form.NFC).toLowerCase());
                    if (n.indexOf(q) >= 0 || n.indexOf(qz) >= 0
                            || g.indexOf(q) >= 0 || g.indexOf(qz) >= 0
                            || Fx.stripWhitespace(n).indexOf(qFlat) >= 0
                            || Fx.stripWhitespace(n).indexOf(qzFlat) >= 0
                            || Fx.stripWhitespace(g).indexOf(qFlat) >= 0
                            || Fx.stripWhitespace(g).indexOf(qzFlat) >= 0) {
                        toastKey = "sayadaw_not_found_fav";
                        break;
                    }
                }
            }
            Toast.makeText(this, Lang.t(this, toastKey),
                    Toast.LENGTH_SHORT).show();
        } else if (!shown.isEmpty()) {
            lastEmptyToastQuery = null;
        }
    }

    private class SayadawAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this).inflate(R.layout.row_sayadaw_h, parent, false);
                Fx.press(v); // v2.08: press feedback on grid cards
            }
            // v3.47: premium recycled-card squash hygiene, the one
            // channel the delivered v3.40 pour hygiene explicitly
            // left untouched. The v2.08 press squash is a
            // ViewPropertyAnimator scale (0.96 on DOWN, spring-back
            // to 1 over 180ms on UP/CANCEL), a different mechanism
            // from the entrance view Animation v3.40 clears — so a
            // card recycled mid-spring-back (finger lifts into a
            // fling inside that 180ms window) used to carry a
            // transient scale onto the new sayadaw: the new card
            // appeared slightly squashed or overshooting past 1,
            // then sprang to 1 on content that was never pressed,
            // the same recycled-animation class v3.40/v3.45 fixed
            // on the pour/row channels. A rebind now cancels any
            // stale card animator and restores scale 1. This never
            // breaks the squash itself: Fx.press re-arms only on
            // touch events, which arrive after bind, never during
            // it, and the entrance pour below is a view Animation
            // this cancel does not touch; the star morph lives on
            // the star child, not the card. Visual only; the bind
            // below (photo, names, star) is untouched.
            v.animate().cancel();
            Fx.cancelPress(v); // v3.90: press squash is an ObjectAnimator now — the cancel above cannot stop it.
            v.setScaleX(1f);
            v.setScaleY(1f);
            final Sayadaw s = shown.get(pos);
            boolean night = cachedNight;
            ImageLoader.load(MainActivity.this, s.photo, s.name, (CircleImageView) v.findViewById(R.id.photo));
            // v13.85 (Main L3, M9/F5 row sweep — main-talks-recheck-1355): the card views
            // were dereferenced bare, like the onCreate sites v13.37 closed — a renamed
            // or missing row id NPEd the whole grid bind. Same shape as Downloaded
            // v13.38 / Room v13.43: each use is null-guarded, so a missing id leaves its
            // view unbound instead of failing the bind. Both ids exist in
            // row_sayadaw_h.xml today, so no live behaviour changes; the photo view is
            // already safe (ImageLoader.load null-guards its target).
            TextView nameView = v.findViewById(R.id.name);
            // v5.44: explicit now-playing mark on the grid card — the
            // sounding sayadaw already breathes (armMainPulseFor
            // below), but a pulse alone never names the state: a
            // listener glancing at the home grid could not tell
            // which card is playing without watching for motion,
            // while the Talks chooser marks its playing row outright
            // (▶ + gold). The card now does the same: ▶ ahead of
            // the name in gold, reusing soundingSayadawNameVisual
            // so the mark and the pulse can never disagree, and
            // both branches rebind here on every bind so a
            // recycled card can never carry the mark onto another
            // sayadaw.
            // A paused mid-talk session still counts (liveSession
            // counts paused as live, v3.99); a dead session marks
            // nothing, like the pulse. A library session marks its
            // sayadaw too (v11.94 — the visual helper reads the
            // recorded dlSayadaw name); a pre-v5.45 download with
            // no recorded sayadaw marks nothing, never a guess.
            // v11.15: an ended talk is not sounding — the v4.29
            // pulse gate stopped the breath after an autoplay-off
            // end, but this v5.44 mark never got the same gate, so
            // the ended sayadaw's card kept its ▶ + gold on every
            // rebind while the strip already said ended and the
            // pulse stood still. Same gate as the pulse (navigation
            // — glides, card tap to Room — still uses
            // soundingSayadawName untouched, like v4.29).
            String soundingVisual = soundingSayadawNameVisual();
            boolean gridPlaying = !AudioService.liveEnded
                    && soundingVisual != null
                    && soundingVisual.equals(s.name);
            // v13.86 (slice 1078): sounding-sayadaw gold card — the row language, finally on the Main grid: the sounding card's background becomes the gold tile (main_card_playing.xml, the chooser tile's body: gold gradient + 2dp rim, 24dp radius like card_bg, theme-independent), so the sounding sayadaw reads in a still screenshot, not only while the name breathes. The same gridPlaying boolean drives the ▶ text and the gold name above, so tile, mark and name colour can never disagree; a paused session still tiles (liveSession counts paused as live, v3.99), a library session tiles its recorded sayadaw (v11.94), an ended talk never tiles (the liveEnded gate, v5.44/v11.15). Set on every bind, so a recycled card rebinds its background and the tile can never leak onto another sayadaw; the v12.38 tick already rebinds the grid exactly when the sounding name changes, so the tile stays fresh while Main sits on top. The press squash (scale-only), entrance pour (alpha+scale), star morph (star child) and breath (name view) all live on other views/channels — the tile is the card root's background alone. Not the banned favStar badge: no new view, no ★, background only.
            // v14.31 (slice 1686): sounding-card gold tile crossfade
            // — the tile used to snap on/off here (setBackgroundResource)
            // whenever the 1s tick rebind fired on a sounding flip; now
            // it melts over 250ms via Fx.crossfadeCardTile (same model
            // as the v14.16 rings), seeded instantly on a fresh view so
            // scroll recycles never melt in from transparent. The ▶ +
            // gold name below stay instant by the v14.16 decision.
            Fx.crossfadeCardTile(v, gridPlaying ? R.drawable.main_card_playing : (night ? R.drawable.card_bg_night : R.drawable.card_bg));
            if (nameView != null) nameView.setText(Fx.soundingRowMark(gridPlaying, AudioService.liveTrackError, AudioService.liveBuffering ? Lang.t(MainActivity.this, "notif_state_buffering") : "", "\u25B6") + s.name);
            if (nameView != null) nameView.setTextColor(gridPlaying ? Color.parseColor("#C9A227")
                    : (night ? Color.WHITE : Color.parseColor("#3B2314")));
            if (nameView != null) nameView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, cachedNameSize);
            if (nameView != null) armMainPulseFor(nameView, s.name);
            String g = s.group == null ? "" : s.group;
            // v1.92: the tap guard below already refuses a literal
            // "null" page_url, but this suffix missed it — such a
            // sayadaw showed no "no link yet" note and the tap then
            // refused with no visible reason. Same condition both places.
            if (s.url == null || s.url.length() == 0 || "null".equals(s.url)) g += Lang.t(MainActivity.this, "sayadaw_no_link_suffix");
            TextView groupView = v.findViewById(R.id.group);
            if (groupView != null) groupView.setText(g);
            if (groupView != null) groupView.setTextColor(night ? Color.parseColor("#AAAAAA") : Color.parseColor("#7A6A58"));
            if (groupView != null) groupView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, cachedGroupSize);
            // v2.07: staggered grid entrance — portraits fade + grow
            // in one after another, cascading across the grid.
            if (pos > lastGridAnimPos) {
                lastGridAnimPos = pos;
                android.view.animation.AnimationSet set = new android.view.animation.AnimationSet(true);
                android.view.animation.AlphaAnimation fa = new android.view.animation.AlphaAnimation(0f, 1f);
                fa.setDuration(280);
                android.view.animation.ScaleAnimation sa = new android.view.animation.ScaleAnimation(0.94f, 1f, 0.94f, 1f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
                sa.setDuration(280);
                set.addAnimation(fa); set.addAnimation(sa);
                set.setStartOffset(Math.min(pos, 12) * 35);
                set.setInterpolator(new android.view.animation.DecelerateInterpolator());
                v.startAnimation(set);
            } else {
                // v3.40: premium recycled-row pour hygiene — the
                // entrance above is a view Animation, and a view
                // recycled mid-pour (fast scroll-back inside the
                // entrance window, or a filter re-pour) keeps its
                // old animation running when rebound to a position
                // already past the guard, so the new card fades +
                // grows late on content that never poured. A row
                // that gets no new pour now clears any stale one;
                // settled rows are untouched (clearAnimation is a
                // no-op with nothing running) and the v2.08 press
                // squash uses ViewPropertyAnimator, a different
                // mechanism this never disturbs. Visual only.
                v.clearAnimation();
            }
            return v;
        }
    }
}
