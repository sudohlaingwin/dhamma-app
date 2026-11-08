package com.h2h.dhamma;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.RemoteViews;

// v12.67: Home Screen Widget (premium backlog #4) — the current
// talk (title + sayadaw) with Play/Pause + Next, working WITHOUT
// opening the app. The buttons are PendingIntents into
// AudioService (the notification's proven getService shape, with
// request codes 200-203 clear of the card's 0-8 and 100+), so
// they work from the home screen with the app closed; the body
// tap opens the Room on the last talk (Main if none yet).
// Updates are push-driven only: AudioService calls refresh() on
// its state edges (notifyState + stopPlayback + onDestroy) and
// the provider refreshes on onUpdate; updatePeriodMillis is 0,
// so the widget is never polled. No MediaStyle, no banned
// control resurrected — this is a new home-screen surface, not
// a strip button. Pure model ported 1:1 in
// hidden_files/sim1270.py (build) and sim1299.py (v12.83 audit:
// dead-library body tap + deleted-file title/sayadaw gates);
// WgTest.java proves the Java itself.
public class DhammaWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_WIDGET_PLAY = "com.h2h.dhamma.WIDGET_PLAY";
    public static final String ACTION_WIDGET_NEXT = "com.h2h.dhamma.WIDGET_NEXT";
    public static final int RC_WIDGET_TOGGLE = 200;
    public static final int RC_WIDGET_PLAY = 201;
    public static final int RC_WIDGET_NEXT = 202;
    public static final int RC_WIDGET_OPEN = 203;

    // ---- pure model (ported 1:1 in sim1270.py) ----

    static String clean67(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') continue;
            b.append(c);
        }
        return b.toString().trim();
    }

    // Live title wins while a track is live; a dead service falls
    // back to the stored last talk; "" means the empty state
    // (the caller shows the choose_talk text).
    static String widgetTitlePure(boolean hasTrack, String liveTitle, String lastTitle) {
        String live = clean67(liveTitle);
        String last = clean67(lastTitle);
        if (hasTrack) return live.length() > 0 ? live : last;
        return last;
    }

    // Always the stored lastName — the same source as the
    // notification card's sayadaw line.
    static String widgetSayadawPure(String lastName) {
        return clean67(lastName);
    }

    // Buffering counts as playing (livePlaying is true through
    // the buffering window), so it shows the pause icon.
    static String widgetPlayIconPure(boolean playing) {
        return playing ? "pause" : "play";
    }

    // Live track: toggle in place. Dead service with a stored
    // last talk: resume it through the service (no UI opens).
    // Never played: open Main so a talk can be picked.
    static String widgetPlayKindPure(boolean hasTrack, String lastTitle) {
        if (hasTrack) return "toggle";
        if (clean67(lastTitle).length() > 0) return "resume";
        return "open";
    }

    // Mirrors the notification open logic: Room when a last
    // sayadaw page is known and the track really is that talk,
    // library when the current track is an on-disk file, Main
    // otherwise. v12.83 (sim1299 FINDING A): a dead service with
    // valid library anchors opens the LIBRARY — the widget shows
    // that library talk and Play resumes it, so the body tap must
    // land on it too, not on the stale Room talk a library session
    // never overwrote. libResume is that dead-library state.
    static String widgetOpenKindPure(String lastPage, boolean resumeOk,
                                     boolean currentIsFile, boolean libResume) {
        if (libResume) return "library";
        if (clean67(lastPage).length() > 0 && resumeOk) return "room";
        if (currentIsFile) return "library";
        return "main";
    }

    // Dead-service library resume, pure core (ported 1:1 in
    // sim1299.py): the service is dead, the newest session is a
    // library one, and its file is still on disk. A deleted file
    // is NOT a resume (the Play handler's skip-dead-files
    // discipline v11.66); a live track rides currentIsFile, not
    // this branch.
    static boolean widgetLibResumePure(boolean hasTrack, String lastKind,
                                       String libPath, boolean libFileExists) {
        if (hasTrack) return false;
        if (!"lib".equals(lastKind)) return false;
        if (clean67(libPath).length() == 0) return false;
        return libFileExists;
    }

    // The notification card's resumeOk rule: trackless means the
    // stored last talk (its currentUrl == null leg); a live
    // track must be that talk — same stream URL, or its
    // downloaded file whose basename matches the last URL's
    // basename (offlineFileFor's candidate rule, reduced to the
    // basename match — a file of another talk never borrows
    // the Room).
    static boolean widgetResumeOkPure(boolean hasTrack, String liveUrl, String lastUrl,
                                      boolean liveIsFile, boolean baseMatch) {
        if (!hasTrack) return true;
        if (liveUrl == null) return true;
        if (liveUrl.equals(lastUrl)) return true;
        if (lastUrl == null || lastUrl.length() == 0) return false;
        if (!liveIsFile) return false;
        return baseMatch;
    }

    // ---- live wiring ----

    // Push a widget update for every placed widget. Safe with no
    // widget placed (empty id list) and safe to call from the
    // service's state edges — it only reads the live mirrors and
    // the stored last talk, never binds or starts anything.
    public static void refresh(Context context) {
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(context);
            int[] ids = awm.getAppWidgetIds(
                    new ComponentName(context, DhammaWidgetProvider.class));
            if (ids == null || ids.length == 0) return;
            RemoteViews views = buildViews(context);
            for (int id : ids) {
                try { awm.updateAppWidget(id, views); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private static RemoteViews buildViews(Context context) {
        SharedPreferences sp = context.getSharedPreferences("dhamma", Context.MODE_PRIVATE);
        boolean hasTrack = AudioService.liveHasTrack;
        String lastTitle = sp.getString("lastTitle", "");
        // v12.71: a library session never writes lastTitle /
        // lastName, so a dead service after a library listen
        // offered the stale Room talk under the stale Room name.
        // The stored library anchors name the talk actually
        // heard: its title is the resume title, its owner (the
        // download-time dlSayadaw record) the sayadaw line.
        // While the service lives the live title still wins, and
        // an unresolvable owner keeps today's lastName — never
        // worse, never a guessed name.
        boolean isLib = "lib".equals(sp.getString("lastKind", ""));
        String effLastTitle = lastTitle;
        if (isLib && !hasTrack) {
            String libTitle = sp.getString("lastLibTitle", "");
            // v12.83 (sim1299 FINDING B): the library anchors
            // outlive a deleted file — a deleted talk must not
            // keep naming the widget title while Play falls back
            // to the Room talk. Only a file still on disk lends
            // its title.
            String libPath = sp.getString("lastLibPath", "");
            if (libTitle.length() > 0 && libPath.length() > 0
                    && new java.io.File(libPath).isFile()) effLastTitle = libTitle;
        }
        String title = widgetTitlePure(hasTrack, AudioService.liveTitle, effLastTitle);
        String sayadaw = widgetSayadawPure(sp.getString("lastName", ""));
        if (isLib) {
            String sounding = hasTrack ? AudioService.liveUrl
                    : sp.getString("lastLibPath", "");
            // v12.83 (sim1299 FINDING B): dead + deleted file —
            // the dlSayadaw record outlives the file, so only
            // trust the owner while the file exists. A live
            // track's file exists by definition, so the live
            // branch is untouched.
            boolean libOk = hasTrack;
            if (!hasTrack) {
                libOk = sounding.length() > 0
                        && new java.io.File(sounding).isFile();
            }
            if (libOk) {
                String owner = Prefs.sayadawForTalk(context, "lib", sounding, "");
                if (owner != null && owner.length() > 0) sayadaw = widgetSayadawPure(owner);
            }
        }
        boolean playing = AudioService.livePlaying;

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_dhamma);
        views.setTextViewText(R.id.wg_app, Lang.t(context, "wg_app"));
        views.setTextViewText(R.id.wg_title,
                title.length() > 0 ? title : Lang.t(context, "choose_talk"));
        views.setTextViewText(R.id.wg_sayadaw, sayadaw);
        views.setViewVisibility(R.id.wg_sayadaw,
                sayadaw.length() > 0 ? android.view.View.VISIBLE : android.view.View.GONE);

        String icon = widgetPlayIconPure(playing);
        views.setImageViewResource(R.id.wg_play,
                "pause".equals(icon) ? R.drawable.ic_pause : R.drawable.ic_play);
        views.setContentDescription(R.id.wg_play,
                Lang.t(context, "pause".equals(icon) ? "wg_pause" : "wg_play"));
        views.setContentDescription(R.id.wg_next, Lang.t(context, "wg_next"));

        views.setOnClickPendingIntent(R.id.wg_play,
                playPendingIntent(context, sp, hasTrack, effLastTitle));
        Intent next = new Intent(context, AudioService.class);
        next.setAction(ACTION_WIDGET_NEXT);
        views.setOnClickPendingIntent(R.id.wg_next, PendingIntent.getService(context,
                RC_WIDGET_NEXT, next,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        views.setOnClickPendingIntent(R.id.wg_body,
                openPendingIntent(context, sp));
        return views;
    }

    private static PendingIntent playPendingIntent(Context context, SharedPreferences sp,
                                                   boolean hasTrack, String lastTitle) {
        String kind = widgetPlayKindPure(hasTrack, lastTitle);
        if ("toggle".equals(kind)) {
            Intent toggle = new Intent(context, AudioService.class);
            toggle.setAction(AudioService.ACTION_TOGGLE);
            return PendingIntent.getService(context, RC_WIDGET_TOGGLE, toggle,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        if ("resume".equals(kind)) {
            Intent play = new Intent(context, AudioService.class);
            play.setAction(ACTION_WIDGET_PLAY);
            // A dead service must come up as a foreground service
            // to play; getForegroundService is the API-26+ form
            // for exactly this, getService the pre-26 form.
            if (Build.VERSION.SDK_INT >= 26) {
                return PendingIntent.getForegroundService(context, RC_WIDGET_PLAY, play,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }
            return PendingIntent.getService(context, RC_WIDGET_PLAY, play,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        Intent main = new Intent(context, MainActivity.class);
        main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, RC_WIDGET_OPEN, main,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent openPendingIntent(Context context, SharedPreferences sp) {
        String lastPage = sp.getString("lastPage", "");
        boolean resumeOk = widgetResumeOk(context, sp);
        String liveUrl = AudioService.liveUrl;
        boolean currentIsFile = false;
        try {
            currentIsFile = AudioService.liveHasTrack && liveUrl != null
                    && new java.io.File(liveUrl).isFile();
        } catch (Exception ignored) {}
        // v12.83 (sim1299 FINDING A): a dead service with valid
        // library anchors opens the library on the heard file —
        // the widget shows that talk and Play resumes it, so the
        // body tap must land on it too instead of the stale Room
        // talk. The jump path is the library file itself (the
        // live track's path is null when the service is dead).
        boolean libResume = false;
        String libJumpPath = null;
        if (!AudioService.liveHasTrack) {
            String lk = sp.getString("lastKind", "");
            String lp = sp.getString("lastLibPath", "");
            boolean lpExists = false;
            try { lpExists = lp.length() > 0 && new java.io.File(lp).isFile(); }
            catch (Exception ignored) {}
            libResume = widgetLibResumePure(false, lk, lp, lpExists);
            if (libResume) libJumpPath = lp;
        }
        String kind = widgetOpenKindPure(lastPage, resumeOk, currentIsFile, libResume);
        Intent open;
        if ("room".equals(kind)) {
            open = new Intent(context, RoomActivity.class);
            open.putExtra("name", sp.getString("lastName", ""));
            open.putExtra("group", sp.getString("lastGroup", ""));
            open.putExtra("url", lastPage);
            open.putExtra("photo", sp.getString("lastPhoto", ""));
            open.putExtra("autoUrl", sp.getString("lastUrl", ""));
            // v13.09 (widget F8): the autoTitle extra is dropped —
            // RoomActivity v11.75 deleted it (read nowhere; the
            // load-completion auto-play keys off autoUrl only).
        } else if ("library".equals(kind)) {
            open = new Intent(context, DownloadedActivity.class);
            open.putExtra("jumpPath", libResume ? libJumpPath : liveUrl);
        } else {
            open = new Intent(context, MainActivity.class);
        }
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, RC_WIDGET_OPEN, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // Glue for widgetResumeOkPure: the liveUrl / file / basename
    // inputs the pure rule needs.
    static boolean widgetResumeOk(Context context, SharedPreferences sp) {
        try {
            if (!AudioService.liveHasTrack) return true;
            String liveUrl = AudioService.liveUrl;
            if (liveUrl == null) return true;
            String lastUrl = sp.getString("lastUrl", "");
            if (liveUrl.equals(lastUrl)) return true;
            if (lastUrl.length() == 0) return false;
            java.io.File f = new java.io.File(liveUrl);
            if (!f.isFile()) return false;
            String base = stripUrlTail(liveUrl.substring(liveUrl.lastIndexOf('/') + 1));
            String lastBase = stripUrlTail(lastUrl.substring(lastUrl.lastIndexOf('/') + 1));
            String lastRaw = lastUrl.substring(lastUrl.lastIndexOf('/') + 1);
            return base.equals(lastBase) || base.equals(lastRaw);
        } catch (Exception ignored) {
            return false;
        }
    }

    // offlineFileFor's query/fragment strip, reduced to the
    // basename the widget compares.
    static String stripUrlTail(String s) {
        if (s == null) return "";
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        return s;
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        refresh(context);
    }

    @Override
    public void onEnabled(Context context) {
        refresh(context);
    }
}
