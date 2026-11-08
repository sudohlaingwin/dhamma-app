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
    private Set<String> favs = new HashSet<String>();
    private View rootView;
    private TextView titleText;
    private Button themeToggle;
    private EditText searchField;
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
    // list, so no landed-file check — a landed job's DM row is
    // STATUS_SUCCESSFUL, hence not a live row, so its entry drops
    // by the same no-live-job rule).
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
                android.database.Cursor c1020 = dm1020.query(
                        new android.app.DownloadManager.Query());
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
                            if (src1020 != null && !queued1020.contains(src1020)
                                    && (uri1020 == null || !uri1020.contains("Dhamma/"))) continue;
                            if (src1020 == null && uri1020 != null && !uri1020.contains("Dhamma/")) continue;
                            if (src1020 != null) activeSources1020.add(src1020);
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
                if (activeSources1020.contains(url1020)) continue;
                String mapped1020 = map1020.optString(url1020, "");
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
                if (d1020.equals(lastUrl1020)) { cardTalk1020 = true; break; }
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
        syncNavPlayerLiveTint(navText, gold); // v4.12, below
        if (adapter != null) adapter.notifyDataSetChanged();
        // v12.38: the grid was just rebound with the current
        // marks — seed both tick trackers here (onResume and
        // the theme-dip swap), so the tick's rebind check
        // below never fires on a return with a live session.
        mainGridEnded = AudioService.liveEnded;
        mainGridSounding = AudioService.liveEnded ? null : soundingSayadawNameVisual();
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
                                                        oursFail = mapFail.has(failUrl);
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
                            boolean cardTalk = srcUrl.equals(rsp.getString("lastUrl", ""));
                            if (!cardTalk) {
                                String libPath = rsp.getString("lastLibPath", "");
                                if (libPath.length() > 0) {
                                    String fn = new org.json.JSONObject(rsp.getString("dlMap", "{}")).optString(srcUrl, "");
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
        // This helper feeds the two ROUTING call sites (glide-
        // to-sounding, card-tap-to-Room), so one gate here
        // keeps them both honest at once. The three VISUAL
        // sites (nav glow, card pulse, grid ▶ mark) use the
        // visual twin below instead — a library session must
        // still show on the grid, but must never route a card
        // tap into the Room. Paused/buffering/ended untouched
        // (play() sets the mirror synchronously).
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

    private void startMainPulseOn(android.view.View v, String name) {
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
                startMainPulseOn(nameView, name);
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
            startMainPulseOn(found, sounding);
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
    private void syncNavPlayerLiveTint(int navText, int gold) {
        // v11.94: visual helper — the nav glows gold for a
        // library session too (see soundingSayadawNameVisual).
        boolean live = soundingSayadawNameVisual() != null;
        TextView ic = findViewById(R.id.navPlayerIcon);
        TextView lb = findViewById(R.id.navPlayerLabel);
        if (ic != null) ic.setTextColor(live ? gold : navText);
        if (lb != null) lb.setTextColor(live ? gold : navText);
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
        if (name == null || name.length() == 0) {
            stripPhotoName = null;
            pv.setVisibility(android.view.View.GONE);
            return;
        }
        pv.setVisibility(android.view.View.VISIBLE);
        if (name.equals(stripPhotoName)) return;
        stripPhotoName = name;
        String url = "";
        try {
            for (Sayadaw s : all)
                if (s.name.equals(name)) { url = s.photo; break; }
        } catch (Exception ignored) {}
        ImageLoader.load(MainActivity.this, url, name, (CircleImageView) pv);
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
                if (rSavedUrl.equals(rUrl)) rMatch = true;
                else {
                    try {
                        org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
                        String fn = map.optString(rUrl, "");
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
                if (lPath.length() > 0 && rPos > 5000 && rSavedUrl.length() > 0
                        && new java.io.File(lPath).isFile()) {
                    if (rSavedUrl.equals(lPath)) lMatch = true;
                    else {
                        try {
                            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
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
                    syncStripPhoto(null);
                    strip.setVisibility(android.view.View.GONE);
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
                        if (nClaim49 == 1 && onlyClaim49 != null && ft.contains(onlyClaim49)) lFav = true;
                    }
                } catch (Exception ignored) {}
                int lSec = rPos / 1000;
                String lTime = Fx.stripResumeTime(lSec);
                if (tx != null) tx.setText("↺ " + lTitle + (lFav ? " ★" : "") + (Fx.stripInProgressDlFilenames(MainActivity.this, sp).contains(new java.io.File(lPath).getName()) ? " ⏳" : " ⬇"));
                // v5.46: a dead library strip names its recorded
                // sayadaw like the live strip since v5.45, in the
                // Room strip's "name · mm:ss မှ ဆက်နားထောင်ရန်"
                // shape; an unrecorded download keeps today's
                // generic line, never a guessed name.
                String libSd = Fx.stripLibSayadaw(MainActivity.this, sp);
                if (sb != null) sb.setText(libSd != null ? libSd + " · " + lTime + Lang.t(MainActivity.this, "resume_from") : Lang.t(MainActivity.this, "downloaded_talk") + " · " + lTime + Lang.t(MainActivity.this, "resume_from"));
                if (tg != null) tg.setText("▶");
                if (nx != null) nx.setVisibility(android.view.View.GONE);
                // v7.31: Previous hides with Next in resume mode —
                // stepping back needs a live track exactly like
                // advancing does, and its handler had no resume
                // branch, so a ⏮ tap on a ↺ strip opened the
                // Room/library with ACTION_PREV and played a
                // different talk instead of resuming this one.
                android.view.View pv0 = findViewById(R.id.nowStripPrev);
                if (pv0 != null) pv0.setVisibility(android.view.View.GONE);
                android.widget.ProgressBar lPb = findViewById(R.id.nowStripProgress);
                if (lPb != null) lPb.setProgress(0);
                resumeStripMode = true;
                syncStripPhoto(libSd);
                strip.setVisibility(android.view.View.VISIBLE);
                return;
            }
            if (!"room".equals(rKind) || rName.length() == 0 || rTitle.length() == 0
                    || rPage.length() == 0 || rPos <= 5000 || !rMatch) {
                syncStripPhoto(null);
                strip.setVisibility(android.view.View.GONE);
                return;
            }
            boolean rFav = false;
            try {
                java.util.Set<String> ft = sp.getStringSet("favTalks", null);
                if (ft != null) rFav = ft.contains(rUrl);
            } catch (Exception ignored) {}
            int rSec = rPos / 1000;
            String rTime = Fx.stripResumeTime(rSec);
            if (tx != null) tx.setText("↺ " + rTitle + (rFav ? " ★" : "") + Fx.stripResumeDownloadMark(MainActivity.this, sp, rUrl));
            if (sb != null) sb.setText(rName + " · " + rTime + Lang.t(MainActivity.this, "resume_from"));
            if (tg != null) tg.setText("▶");
            if (nx != null) nx.setVisibility(android.view.View.GONE);
            // v7.31: same Previous hide as the library resume
            // branch above — a ↺ strip never shows ⏮.
            android.view.View pv0 = findViewById(R.id.nowStripPrev);
            if (pv0 != null) pv0.setVisibility(android.view.View.GONE);
            android.widget.ProgressBar rPb = findViewById(R.id.nowStripProgress);
            if (rPb != null) rPb.setProgress(0);
            resumeStripMode = true;
            syncStripPhoto(rName);
            strip.setVisibility(android.view.View.VISIBLE);
            return;
        }
        resumeStripMode = false;
        if (nx != null) nx.setVisibility(android.view.View.VISIBLE);
        // v7.31: Previous returns with Next on a live session
        // (it was never hidden by layout, only left visible
        // through resume mode by the missing sync above).
        android.view.View pv0 = findViewById(R.id.nowStripPrev);
        if (pv0 != null) pv0.setVisibility(android.view.View.VISIBLE);
        String kind = sp.getString("lastKind", "");
        // v4.30: a real mini-player shows title over artist, not
        // one combined line — the strip's old "name — title"
        // string is split here; the suffix marks below still land
        // on the title line, and the sub line names the sayadaw
        // (Room) or the saved-file source (library).
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
                String libSd = Fx.stripLibSayadaw(MainActivity.this, sp);
                subText = libSd != null ? libSd : Lang.t(MainActivity.this, "downloaded_talk");
            }
        }
        if (text == null) { syncStripPhoto(null); strip.setVisibility(android.view.View.GONE); return; }
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
                        if (nClaim49 == 1 && onlyClaim49 != null && ft.contains(onlyClaim49)) stripFav = true;
                    }
                }
            }
        } catch (Exception ignored) {}
        if (stripFav) text += " ★";
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
                                || (src != null && dlUrls != null && dlUrls.contains(src))) progN++;
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
        if (tx != null) tx.setText(text);
        // v5.32: live clock on the strip — sayadaw name + elapsed/total.
        android.widget.ProgressBar pb = findViewById(R.id.nowStripProgress);
        if (subText != null) subText += Fx.stripClockSuffixForStrip(pb, AudioService.livePositionMs, AudioService.liveDurationMs, Prefs.stripClockRemaining(MainActivity.this));
        if (sb != null && subText != null) sb.setText(subText);
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
        if (tg != null) tg.setText(AudioService.livePlaying ? "\u23F8\uFE0E" : "▶");
        // v6.00: live strip's portrait — Room names lastName, library
        // names its recorded sayadaw (Fx.stripLibSayadaw, null when
        // unrecorded); both resolve through syncStripPhoto's
        // exact-name catalog match, never a guessed face.
        syncStripPhoto("room".equals(kind) ? sp.getString("lastName", "")
                : ("lib".equals(kind) ? Fx.stripLibSayadaw(MainActivity.this, sp) : null));
        strip.setVisibility(android.view.View.VISIBLE);
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
                                    : !sndNow38.equals(mainGridSounding))) {
                        mainGridEnded = AudioService.liveEnded;
                        mainGridSounding = sndNow38;
                        if (adapter != null) adapter.notifyDataSetChanged();
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

    // v1.48: rotating the phone used to wipe the search text (and the
    // filtered grid with it) — keep the query across recreation.
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("q", searchField != null ? searchField.getText().toString() : query);
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
        favs = new HashSet<String>(getSharedPreferences("dhamma", MODE_PRIVATE)
                .getStringSet("favs", new HashSet<String>()));
        rootView = findViewById(R.id.root);
        titleText = findViewById(R.id.titleText);
        // v4.12: tap the app name to glide back to the top of the
        // grid (streaming-app tap-to-top, on the existing title —
        // no new view, no new button). A tap with an empty grid is
        // a no-op; the grid itself, search and theme are untouched.
        titleText.setOnClickListener(new View.OnClickListener() {
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
        titleText.setOnLongClickListener(new View.OnLongClickListener() {
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
        themeToggle.setOnClickListener(new View.OnClickListener() {
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
        themeToggle.setOnLongClickListener(new View.OnLongClickListener() {
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
                    if (favOnly) { favOnly = false; applyFilter(); refreshFavHint(); }
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
            Fx.press(nowStrip);
            // v4.73: the strip's progress bar seeks (see Fx.miniSeekBar).
            Fx.miniSeekBar(MainActivity.this, nowStrip);
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
                    Fx.tap(MainActivity.this);
                    Fx.morph(v);
                    Fx.go(MainActivity.this, i);
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
                        Toast.makeText(MainActivity.this, Lang.t(MainActivity.this, "hist_cleared"), Toast.LENGTH_SHORT).show();
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
        adapter = new SayadawAdapter();
        gridView.setAdapter(adapter);
        gridView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Sayadaw s = shown.get(pos);
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
        });

        // v4.16: long-press a sayadaw card toggles its favourite
        // (the same favs set + saveFavs + rows-only refresh the
        // star tap uses, v1.44) — a whole-card target for older
        // listeners instead of hunting the small star. A tap still
        // opens the chooser (or the Room for the sounding card);
        // returning true consumes the long-press so no tap fires.
        gridView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
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
                        if (favs.isEmpty()) { favOnly = false; refreshFavHint(); }
                        applyFilter();
                        if (favs.isEmpty())
                            Toast.makeText(MainActivity.this, Lang.t(MainActivity.this, "no_fav_sayadaw"), Toast.LENGTH_SHORT).show();
                    } else adapter.notifyDataSetChanged();
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
        // v1.48: restore the pre-rotation query (see onSaveInstanceState).
        if (savedInstanceState != null) query = savedInstanceState.getString("q", "");
        // v4.26: restore the filter with the query (guarded: an
        // emptied favourites set restores to the full grid, never
        // a blank filtered one). The onCreate applyFilter below
        // picks it up, and its hint refresh follows it.
        if (savedInstanceState != null && savedInstanceState.getBoolean("favOnly", false)
                && favs != null && !favs.isEmpty()) favOnly = true;
        // v2.07: a rotated grid must not replay its entrance.
        if (savedInstanceState != null) lastGridAnimPos = Integer.MAX_VALUE;
        searchField.addTextChangedListener(new TextWatcher() {
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
        searchField.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Fx.tap(MainActivity.this);
                if (searchField.getText().length() > 0) searchField.setText("");
                else {
                    // v4.19: long-press an EMPTY search toggles the
                    // favourites-only filter — a non-empty query
                    // still clears (v4.17), so the two gestures
                    // never collide: clearing needs text, filtering
                    // needs none. The filter itself runs through
                    // applyFilter, so a typed query still narrows
                    // within favourites, and the empty toast path
                    // stays untouched (it only fires for a typed
                    // query). No favourites yet just says so.
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
                    applyFilter();
                    refreshFavHint();
                }
                return true;
            }
        });
        // v4.20: keyboard Search key dismisses the keyboard — the
        // field never had an editor action, so pressing Search on
        // the keyboard did nothing (and a hardware Enter could drop
        // a newline into the query). The Search key now hides the
        // keyboard; the live filter already ran on every keystroke.
        searchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchField.setSingleLine(true);
        searchField.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
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
        if (query.length() > 0) {
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
            if (scrollPos > 0 && scrollPos < shown.size()) gridView.setSelection(scrollPos);
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
                JSONObject o = arr.getJSONObject(i);
                String url = o.isNull("page_url") ? "" : o.optString("page_url", "");
                String photo = "";
                if (!o.isNull("photo_url")) photo = o.optString("photo_url", "");
                if (photo.length() == 0 && !o.isNull("photo")) photo = o.optString("photo", "");
                all.add(new Sayadaw(o.optString("name", ""), o.optString("group", ""), url, photo));
            }
        } catch (Exception e) {
            Toast.makeText(this, Lang.t(this, "catalog_open_failed") + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            // v1.66: same stream-leak class as the talk loaders —
            // a read dying mid-catalog used to skip the close.
            if (in != null) { try { in.close(); } catch (Exception ignored) {} }
        }
        // v11.99: queue-key migration (B-D6) — once per process
        // (Prefs guards it); running it here migrates favs on
        // launch even when no queue is touched this session.
        Prefs.migrateQueueKeys99(this);
    }

    private void applyFilter() { applyFilter(false); }
    // v4.21: favourites-only state stays visible after its toast fades —
    // the grid alone never said it was filtered, so a small filtered grid
    // read as a missing catalogue. The search hint carries a star prefix
    // while favOnly is on (in-place text on the existing field, no new
    // view); every path that flips favOnly refreshes it below.
    private void refreshFavHint() {
        if (searchField != null)
            searchField.setHint(favOnly ? "★ " + Lang.t(this, "search_hint") : Lang.t(this, "search_hint"));
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
        for (Sayadaw s : all) {
            if (favOnly && (favs == null || !favs.contains(s.name))) continue;
            if (q.length() > 0) {
                String n = Fx.foldDigits(java.text.Normalizer.normalize(s.name,
                        java.text.Normalizer.Form.NFC).toLowerCase());
                String g = s.group == null ? "" : Fx.foldDigits(java.text.Normalizer.normalize(s.group,
                        java.text.Normalizer.Form.NFC).toLowerCase());
                if (n.indexOf(q) < 0 && g.indexOf(q) < 0
                        && Fx.stripWhitespace(n).indexOf(qFlat) < 0
                        && Fx.stripWhitespace(g).indexOf(qFlat) < 0) continue;
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
        boolean setChanged = false;
        if (allowToast) {
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
        if (allowToast && setChanged && gridView != null && gridView.getFirstVisiblePosition() > 0)
            gridView.setSelection(0);
        // v2.02: a search matching no sayadaw used to leave a
        // blank grid with no word at all — the listener could not
        // tell "still loading" from "nothing matches". Say so, once
        // per query (typing on does not re-toast), and only from a
        // real keystroke — the onCreate/rotation applyFilter stays
        // silent so a rotated no-match search does not nag again.
        if (allowToast && q.length() > 0 && shown.isEmpty()
                && !q.equals(lastEmptyToastQuery)) {
            lastEmptyToastQuery = q;
            Toast.makeText(this, Lang.t(this, "sayadaw_not_found"),
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
            v.setBackgroundResource(night ? R.drawable.card_bg_night : R.drawable.card_bg);
            ImageLoader.load(MainActivity.this, s.photo, s.name, (CircleImageView) v.findViewById(R.id.photo));
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
            nameView.setText(gridPlaying ? "▶ " + s.name : s.name);
            nameView.setTextColor(gridPlaying ? Color.parseColor("#C9A227")
                    : (night ? Color.WHITE : Color.parseColor("#3B2314")));
            nameView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, cachedNameSize);
            armMainPulseFor(nameView, s.name);
            String g = s.group == null ? "" : s.group;
            // v1.92: the tap guard below already refuses a literal
            // "null" page_url, but this suffix missed it — such a
            // sayadaw showed no "no link yet" note and the tap then
            // refused with no visible reason. Same condition both places.
            if (s.url == null || s.url.length() == 0 || "null".equals(s.url)) g += Lang.t(MainActivity.this, "sayadaw_no_link_suffix");
            TextView groupView = v.findViewById(R.id.group);
            groupView.setText(g);
            groupView.setTextColor(night ? Color.parseColor("#AAAAAA") : Color.parseColor("#7A6A58"));
            groupView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, cachedGroupSize);
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
