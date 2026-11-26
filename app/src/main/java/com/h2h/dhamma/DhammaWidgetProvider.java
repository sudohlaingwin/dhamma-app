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
    public static final String ACTION_WIDGET_PREV = "com.h2h.dhamma.WIDGET_PREV";
    public static final int RC_WIDGET_TOGGLE = 200;
    public static final int RC_WIDGET_PLAY = 201;
    public static final int RC_WIDGET_NEXT = 202;
    public static final int RC_WIDGET_OPEN = 203;
    public static final int RC_WIDGET_PREV = 204;

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

    // v13.91 (slice 1083): the widget's sounding gold cue — the
    // gold arc's last surface has no photo to ring, so the title
    // itself turns gold while a live session sounds. The strip
    // predicate, verbatim: liveSession + liveHasTrack +
    // !liveEnded. Paused still gold (gold says "this talk is the
    // live one"; the play/pause icon says playing vs paused);
    // an ended talk, a dead-service resume, and a trackless live
    // flag never gold. The empty state can never be gold: a live
    // session always carries its title (play() sets liveTitle
    // with the track, beside liveHasTrack).
    static boolean widgetTitleGoldPure(boolean liveSession,
                                       boolean liveHasTrack, boolean liveEnded) {
        return liveSession && liveHasTrack && !liveEnded;
    }

    // v14.04 (slice 1099): the widget title's state marks — the strips
    // name a failed stream (\u26A0, v13.94) and a downloaded talk (\u2B07,
    // v14.02); the widget named neither, so on the home screen a
    // failed stream read exactly like a paused talk and a sounding
    // downloaded talk read exactly like a stream. Same marks, same
    // order as the strips: \u26A0 prefix (error wins, exclusive with the
    // buffering word by construction — isBuffering() requires
    // !trackError), \u2B07 suffix after the title (after \u2605 on the strips;
    // the widget has no \u2605). The empty state returns "" — the caller
    // keeps today's choose_talk text, so marks only ever decorate a
    // real title, never the placeholder. A new play() clears
    // liveTrackError (AudioService :2011), so \u26A0 never sticks to the
    // next talk; every failure path refreshes the widget through
    // notifyState, so the mark lands on the failure edge, not the
    // next poll (the widget is never polled). v14.06 (slice 1101):
    // the loading word prefixes the title after the ⚠ (failed wins
    // over loading), from the same liveBuffering mirror — the
    // play()/onPrepared edges already refresh the widget, so the
    // word lands on the loading edge and clears when it sounds.
    // v16.03 (slice 2286): the widget title's favourite/listened marks —
    // the strips and cards name a favourited talk with a star and a
    // listened talk with a tick; the widget named neither, so on the
    // home screen a favourited talk read exactly like any other talk.
    // Both marks append AFTER the downloaded suffix, star first then
    // tick (the strips' star-before-tick order, the downloaded mark
    // kept where it is); never on the empty state, never before the
    // title text. The booleans come from buildViews (favTalks /
    // playedTalks, canonical compare — a legacy-http download key
    // still matches a canonical https entry).
    static String widgetTitleMarkedPure(boolean failed, String bufferingWord, String title, String dlMark,
                                        boolean fav, boolean listened) {
        String t = clean67(title);
        if (t.length() == 0) return "";
        String out = (failed ? "\u26A0 " : (bufferingWord != null && bufferingWord.length() > 0 ? bufferingWord + " " : "")) + t;
        if (" \u2B07".equals(dlMark)) out += dlMark;
        if (fav) out += " \u2605";
        if (listened) out += " \u2713";
        return out;
    }

    // v14.09 (slice 1104): the widget's sleep suffix — the strips
    // (v4.72) and the Room clock row name the live sleep countdown,
    // the notification sub (v5.39) too, but the home-screen widget
    // named nothing: a sleeping listener's widget read exactly like
    // a plain playing talk. The Main strip's sleep rule, verbatim
    // (MainActivity :1455): the one-shot names its word, a running
    // countdown its M:SS clock, a pause-suspended countdown the same
    // clock with ⏸, an armed timer with no remainder the bare mark.
    // clockText is the caller's AudioService.sleepClock(remainingMs);
    // endWord is Lang "sleep_end". Applied in buildViews only onto a
    // real title (never the choose_talk placeholder), after the ⬇
    // suffix — the strips' order.
    static String widgetSleepSuffixPure(boolean active, boolean endOfTalk, boolean playing,
                                        long remainingMs, String clockText, String endWord) {
        if (!active) return "";
        String clock = clockText == null ? "" : clockText;
        String word = endWord == null ? "" : endWord;
        if (endOfTalk) return playing ? " 😴 " + word : " 😴 ⏸ " + word;
        if (remainingMs > 0) return playing ? " 😴 " + clock : " 😴 ⏸ " + clock;
        return " 😴";
    }

    // v14.09 (slice 1104): the 15s-tick gate for the widget's sleep
    // countdown — the widget is push-only (no tick of its own), so a
    // live countdown needs a periodic refresh, and the service's 15s
    // posRunnable is the existing one (the notification's sleep
    // countdown rides the same tick, v5.39). Gated to a running
    // minute countdown while actually playing: a sleep-free session
    // pays nothing, a paused talk's frozen remainder rides the pause
    // edge instead (notifyState), and the one-shot has no countdown
    // to move (its arm edge refreshes). Prepared + playing is the
    // card's own gate, so a buffering-held talk never refreshes.
    static boolean widgetSleepTickPure(boolean playing, boolean prepared,
                                       boolean sleepActive, boolean endOfTalk, long sleepEndMs) {
        return playing && prepared && sleepActive && !endOfTalk && sleepEndMs != 0;
    }

    // v15.94 (slice 2226): the widget's listened-progress bar — the per-mille the bar paints, -1 when the bar stays hidden. A live track reads its own mirrors (the strips' position + duration, already trusted for the strip clock): visible whenever a duration is known, even at 0 — the bare track says just started. A dead service reads the banked spot over the banked duration for the displayed talk (the rails' rule: a spot at or below 5s paints nothing, a finished talk's spot was cleared on completion, so neither reads as started). Both legs clamp the spot into the duration and quantize to per-mille, so the bar and the rails can never disagree on the same talk.
    static int widgetProgressPermille2226(boolean hasTrack, int livePosMs, int liveDurMs,
                                          int bankedPosMs, int bankedDurMs) {
        if (hasTrack) {
            if (liveDurMs <= 0) return -1;
            int pos = Math.max(0, Math.min(livePosMs, liveDurMs));
            return (int) (1000L * pos / liveDurMs);
        }
        if (bankedDurMs <= 0 || bankedPosMs <= 5000) return -1;
        int pos = Math.max(0, Math.min(bankedPosMs, bankedDurMs));
        return (int) (1000L * pos / bankedDurMs);
    }

    // v15.94 (slice 2226): the 15s-tick gate for the widget's progress bar — the widget is push-only (no tick of its own), so a live bar needs a periodic refresh, and the service's 15s tick is the existing one (the notification's elapsed text and the sleep countdown ride the same tick). Gated to a playing track with a known duration: a paused talk's frozen bar rides the pause edge instead, a dead service's banked bar is static, and a trackless or duration-less session has no bar to move.
    static boolean widgetProgressTickPure(boolean playing, boolean prepared,
                                          boolean hasTrack, int liveDurMs) {
        return playing && prepared && hasTrack && liveDurMs > 0;
    }

    // v13.92 (slice 1084): the widget photo's URL — which
    // sayadaw's portrait the widget shows. A library session
    // takes its recorded sayadaw's catalog photo (libPhotoUrl,
    // exact name, "" on miss); anything else takes lastPhoto,
    // the last ROOM sayadaw. The lib branch is gated on libOk —
    // the same file-on-disk gate the title and sayadaw lines use
    // — so a deleted library file falls back to the Room anchors
    // with the title and the name, never a stale face beside a
    // Room name. An unresolvable lib owner (libPhotoUrl "") falls
    // back the same way: the photo always names the sayadaw the
    // line beside it shows, never a guessed face.
    static String widgetPhotoUrlPure(boolean isLib, boolean libOk,
                                     String lastPhoto, String libPhotoUrl) {
        if (isLib && libOk && libPhotoUrl != null && libPhotoUrl.length() > 0)
            return libPhotoUrl;
        return lastPhoto == null ? "" : lastPhoto;
    }

    // v13.92 (slice 1084): the notification's 192px cap
    // (AudioService.notiScaled, same shape) — the cached portrait
    // is a 512px render and a 512x512 ARGB bitmap is 1MB on the
    // Binder parcel; 192px is 147KB and still 4x the 48dp view.
    static android.graphics.Bitmap widgetScaled(android.graphics.Bitmap src) {
        if (src == null) return null;
        try {
            int m = Math.max(src.getWidth(), src.getHeight());
            if (m <= 192) return src;
            float s = 192f / m;
            return android.graphics.Bitmap.createScaledBitmap(src,
                    Math.max(1, Math.round(src.getWidth() * s)),
                    Math.max(1, Math.round(src.getHeight() * s)), true);
        } catch (Exception ignored) { return src; }
    }

    // Buffering counts as playing (livePlaying is true through
    // the buffering window), so it shows the pause icon.
    static String widgetPlayIconPure(boolean playing) {
        return playing ? "pause" : "play";
    }

    // v15.94 (slice 2226): the widget's banked spot + duration for a dead-service talk — the rails' three-leg resolve, as single lookups (the widget paints ONE talk, so one parse per lookup is the single-talk shape): the exact stored key first, then the identity twin, then the downloaded file's own key. A 0 reads as a miss on every leg (the maps bank only positive spots and durations), so a twin that was never banked never overwrites a real spot. The caller gates on the 5s rule; these helpers only resolve.
    static int widgetBankedPos2226(android.content.Context context, String url) {
        if (url == null || url.length() == 0) return 0;
        int pos = Prefs.talkPosGet(context, url);
        if (pos <= 5000) {
            String id = SiteHttp.identityUrl(url);
            if (id != null && !id.equals(url)) {
                int ip = Prefs.talkPosGet(context, id);
                if (ip > 0) pos = ip;
            }
        }
        if (pos <= 5000) {
            String fn = DlLanded.downloadedFileName(context, url, null, null);
            if (fn != null) {
                String path = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma/" + fn).getAbsolutePath();
                int pp = Prefs.talkPosGet(context, path);
                if (pp > 0) pos = pp;
            }
        }
        return pos;
    }

    static int widgetBankedDur2226(android.content.Context context, String url) {
        if (url == null || url.length() == 0) return 0;
        int dur = Prefs.talkDurGet(context, url);
        if (dur <= 0) {
            String id = SiteHttp.identityUrl(url);
            if (id != null && !id.equals(url)) {
                int idu = Prefs.talkDurGet(context, id);
                if (idu > 0) dur = idu;
            }
        }
        if (dur <= 0) {
            String fn = DlLanded.downloadedFileName(context, url, null, null);
            if (fn != null) {
                String path = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma/" + fn).getAbsolutePath();
                int dd = Prefs.talkDurGet(context, path);
                if (dd > 0) dur = dd;
            }
        }
        return dur;
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
        // v13.59 (887 F-NEW-1, F948-2): identity compare — the
        // live URL may be the http retry form of the stored talk.
        if (SiteHttp.sameTalk(liveUrl, lastUrl)) return true;
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
        // v13.92 (slice 1084): libOk + libPhotoUrl escape the lib
        // branch below so the photo rule can share the title and
        // sayadaw lines' own gate — one session decision for all
        // three. Room sessions keep libOk true and no lib photo,
        // so the photo falls back to lastPhoto with the name.
        boolean libOk = true;
        String libPhotoUrl = "";
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
                    && new java.io.File(libPath).isFile()
                    && new java.io.File(libPath).length() > 0) effLastTitle = libTitle;
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
            libOk = hasTrack;
            if (!hasTrack) {
                libOk = sounding.length() > 0
                        && new java.io.File(sounding).isFile()
                        && new java.io.File(sounding).length() > 0;
            }
            if (libOk) {
                String owner = Prefs.sayadawForTalk(context, "lib", sounding, "");
                if (owner != null && owner.length() > 0) {
                    sayadaw = widgetSayadawPure(owner);
                    // v13.92 (slice 1084): the library session's
                    // own photo — the catalog portrait of the
                    // recorded sayadaw (exact name, "" on miss),
                    // so the widget never shows the stale Room
                    // face after a library listen.
                    libPhotoUrl = AudioService.stripPhotoUrlForSayadaw(context, owner);
                }
            }
        }
        boolean playing = AudioService.livePlaying;

        // v14.04 (slice 1099): the widget title's state marks — see
        // widgetTitleMarkedPure. Room kind reuses
        // Fx.stripResumeDownloadMark on the stored lastUrl (the strips'
        // own call, v14.02 — only its \u2B07 return is kept; the \u23F3 return
        // would name a job the widget has no count for). Lib kind is
        // downloaded by definition, so the file-exists guard on the
        // sounding path keeps a stale path from claiming the mark
        // (the strips' lib leg, same gate — a dead lib session reads
        // lastLibPath, a live one the live URL, which is the file).
        boolean widgetFailed = AudioService.liveTrackError;
        String widgetDlMark = "";
        if (isLib) {
            String wPath = hasTrack ? AudioService.liveUrl : sp.getString("lastLibPath", "");
            if (wPath != null && wPath.length() > 0) {
                java.io.File wf = new java.io.File(wPath);
                if (wf.isFile() && wf.length() > 0) widgetDlMark = " \u2B07";
            }
        } else {
            String m = Fx.stripResumeDownloadMark(context, sp, sp.getString("lastUrl", ""));
            if (" \u2B07".equals(m)) widgetDlMark = m;
        }
        // v16.03 (slice 2286): the widget title's favourite/listened marks —
        // see widgetTitleMarkedPure. Room kind reads the talk URL
        // straight (live URL while a track is live, lastUrl when the
        // service is dead); lib kind resolves it through the dlMap
        // exactly-one-claimant rule (the 2285 lib precedent — a
        // dual-claimed file never earns a mark). Both sets are read
        // against SiteHttp.identityUrl (the 2282 card precedent), so
        // a legacy-http download key still matches a canonical https
        // favTalks/playedTalks entry. The provider reads the same
        // "dhamma" SharedPreferences the Activities read (sp above),
        // so no new Prefs API is needed.
        boolean wFav = false;
        boolean wListened = false;
        try {
            java.util.Set<String> wFt = sp.getStringSet("favTalks", null);
            java.util.Set<String> wPt = sp.getStringSet("playedTalks", null);
            String wUrl = null;
            if (isLib) {
                String wLibPath = hasTrack ? AudioService.liveUrl : sp.getString("lastLibPath", "");
                if (wLibPath != null && wLibPath.length() > 0) {
                    String wFn = new java.io.File(wLibPath).getName();
                    org.json.JSONObject wMap = new org.json.JSONObject(sp.getString("dlMap", "{}"));
                    java.util.Iterator<String> wKeys = wMap.keys();
                    int wN = 0;
                    String wOnly = null;
                    while (wKeys.hasNext()) {
                        String u = wKeys.next();
                        if (wFn.equals(wMap.optString(u, ""))) { wN++; wOnly = u; }
                    }
                    if (wN == 1) wUrl = wOnly;
                }
            } else {
                wUrl = hasTrack ? AudioService.liveUrl : sp.getString("lastUrl", "");
            }
            if (wUrl != null && wUrl.length() > 0) {
                String wId = SiteHttp.identityUrl(wUrl);
                if (wFt != null) wFav = wFt.contains(wId);
                if (wPt != null) wListened = wPt.contains(wId);
            }
        } catch (Exception ignored) {}
        String widgetBuf = AudioService.liveBuffering ? Lang.t(context, "notif_state_buffering") : "";
        String markedTitle = widgetTitleMarkedPure(widgetFailed, widgetBuf, title, widgetDlMark, wFav, wListened);
        // v14.09 (slice 1104): the sleep countdown on the widget title —
        // see widgetSleepSuffixPure. Only onto a real title (never the
        // choose_talk placeholder); the live mirrors are false with a
        // dead service, so the suffix dies with the session.
        String widgetSleep = "";
        if (markedTitle.length() > 0 && AudioService.liveSleepActive) {
            long widgetSleepRem = AudioService.liveSleepRemainingMs;
            widgetSleep = widgetSleepSuffixPure(true, AudioService.liveSleepEndOfTalk,
                    AudioService.livePlaying, widgetSleepRem,
                    widgetSleepRem > 0 ? AudioService.sleepClock(widgetSleepRem) : "",
                    Lang.t(context, "sleep_end"));
        }
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_dhamma);
        views.setTextViewText(R.id.wg_app, Lang.t(context, "wg_app"));
        views.setTextViewText(R.id.wg_title,
                markedTitle.length() > 0 ? markedTitle + widgetSleep : Lang.t(context, "choose_talk"));
        // v13.91 (slice 1083): gold title while a live session
        // sounds — the same gold as the wg_app label (#E8C878),
        // white otherwise. No new colour, no layout change; the
        // refresh is push-driven on every state edge (notifyState
        // + play/next/prev/stop/destroy), so the colour follows
        // the session with no new timer.
        boolean sounding = widgetTitleGoldPure(AudioService.liveSession,
                AudioService.liveHasTrack, AudioService.liveEnded);
        views.setTextColor(R.id.wg_title, sounding ? 0xFFE8C878 : 0xFFFFFFFF);
        views.setTextViewText(R.id.wg_sayadaw, sayadaw);
        views.setViewVisibility(R.id.wg_sayadaw,
                sayadaw.length() > 0 ? android.view.View.VISIBLE : android.view.View.GONE);

        // v13.92 (slice 1084): the sounding sayadaw's portrait in
        // the 48dp ring frame — the widget's own photo, the gold
        // arc's last surface. Room sessions read lastPhoto (the
        // last ROOM sayadaw); a library session takes its recorded
        // sayadaw's catalog photo, so a library listen never shows
        // the stale Room face — the notification card's lib branch
        // (v5.99) no-guess rule, same as the name beside it.
        // Cache-only bitmap (ImageLoader.cached, MEM + disk, never
        // a download — the card's v5.23 rule); a miss falls back
        // to the gold initial placeholder of the DISPLAYED sayadaw,
        // so the face always matches the name. Scaled to the
        // notification's 192px cap (widgetScaled) so the
        // RemoteViews parcel stays far under the Binder limit.
        // The frame owns visibility (the strips' v13.90 rule);
        // the ring is the reused shelf ring while sounding (the
        // same widgetTitleGoldPure predicate as the gold title),
        // nothing otherwise. A photo failure never breaks the
        // title or the buttons: the frame simply stays hidden.
        try {
            String photoUrl = widgetPhotoUrlPure(isLib, libOk,
                    sp.getString("lastPhoto", ""), libPhotoUrl);
            android.graphics.Bitmap photoBmp = null;
            if (sayadaw.length() > 0) {
                if (photoUrl.length() > 0)
                    photoBmp = ImageLoader.cached(context, photoUrl);
                if (photoBmp == null)
                    photoBmp = ImageLoader.placeholder(sayadaw);
                photoBmp = widgetScaled(photoBmp);
            }
            if (photoBmp != null) {
                views.setImageViewBitmap(R.id.wg_photo, photoBmp);
                views.setInt(R.id.wg_photo_ring, "setBackgroundResource",
                        sounding ? R.drawable.shelf_ring_playing : 0);
                views.setViewVisibility(R.id.wg_photo_ring,
                        android.view.View.VISIBLE);
            } else {
                views.setViewVisibility(R.id.wg_photo_ring,
                        android.view.View.GONE);
            }
        } catch (Exception ignored) {
            try {
                views.setViewVisibility(R.id.wg_photo_ring,
                        android.view.View.GONE);
            } catch (Exception ignored2) {}
        }

        // v15.94 (slice 2226): the widget's listened-progress bar — the thin gold line at the widget's bottom edge, the notification seek bar's shape on the home screen: a live track reads the service's own position over its own duration, a dead service reads the banked spot over the banked duration for the displayed talk, and the bar stays gone when there is nothing to show (no duration, never started, finished). The bar only repaints when a refresh lands (state edges + the 15s tick while playing), so a steady session never re-renders it.
        int bankedPos2226 = 0;
        int bankedDur2226 = 0;
        if (!hasTrack) {
            String deadUrl2226 = isLib ? sp.getString("lastLibPath", "") : sp.getString("lastUrl", "");
            bankedPos2226 = widgetBankedPos2226(context, deadUrl2226);
            bankedDur2226 = widgetBankedDur2226(context, deadUrl2226);
        }
        int wgPermille2226 = widgetProgressPermille2226(hasTrack, AudioService.livePositionMs,
                AudioService.liveDurationMs, bankedPos2226, bankedDur2226);
        views.setProgressBar(R.id.wg_progress, 1000, wgPermille2226 < 0 ? 0 : wgPermille2226, false);
        views.setViewVisibility(R.id.wg_progress, wgPermille2226 >= 0 ? android.view.View.VISIBLE : android.view.View.GONE);

        String icon = widgetPlayIconPure(playing);
        views.setImageViewResource(R.id.wg_play,
                "pause".equals(icon) ? R.drawable.ic_pause : R.drawable.ic_play);
        views.setContentDescription(R.id.wg_play,
                Lang.t(context, "pause".equals(icon) ? "wg_pause" : "wg_play"));
        views.setContentDescription(R.id.wg_next, Lang.t(context, "wg_next"));
        // v13.78 (slice 1070): the Previous icon + TalkBack label.
        views.setImageViewResource(R.id.wg_prev, R.drawable.ic_prev);
        views.setContentDescription(R.id.wg_prev, Lang.t(context, "wg_prev"));

        views.setOnClickPendingIntent(R.id.wg_play,
                playPendingIntent(context, sp, hasTrack, effLastTitle));
        // v13.78 (slice 1070): widget Prev — the transport row's
        // missing control. Same no-UI shape as Next below (service
        // intent, foreground-service form on API 26+ — the v13.46
        // note — so a tap after a swipe/kill still lands).
        Intent prev = new Intent(context, AudioService.class);
        prev.setAction(ACTION_WIDGET_PREV);
        PendingIntent prevPi;
        if (Build.VERSION.SDK_INT >= 26) {
            prevPi = PendingIntent.getForegroundService(context,
                    RC_WIDGET_PREV, prev,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            prevPi = PendingIntent.getService(context,
                    RC_WIDGET_PREV, prev,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        views.setOnClickPendingIntent(R.id.wg_prev, prevPi);
        Intent next = new Intent(context, AudioService.class);
        next.setAction(ACTION_WIDGET_NEXT);
        // v13.46: a dead service must come up as a foreground service
        // to advance; the widget tap exempts foreground-service
        // starts only, so plain getService was refused on API 26+
        // ("Background start not allowed") and Next was a dead tap
        // after a swipe/kill — same shape as the Play-resume path.
        PendingIntent nextPi;
        if (Build.VERSION.SDK_INT >= 26) {
            nextPi = PendingIntent.getForegroundService(context,
                    RC_WIDGET_NEXT, next,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            nextPi = PendingIntent.getService(context,
                    RC_WIDGET_NEXT, next,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        views.setOnClickPendingIntent(R.id.wg_next, nextPi);
        views.setOnClickPendingIntent(R.id.wg_body,
                openPendingIntent(context, sp));
        return views;
    }

    private static PendingIntent playPendingIntent(Context context, SharedPreferences sp,
                                                   boolean hasTrack, String lastTitle) {
        String kind = widgetPlayKindPure(hasTrack, lastTitle);
        if ("open".equals(kind)) {
            Intent main = new Intent(context, MainActivity.class);
            main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            return PendingIntent.getActivity(context, RC_WIDGET_OPEN, main,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        // v13.60 (F938-1): live-toggle AND dead-resume both go through
        // ACTION_WIDGET_PLAY — the service handler toggles when a track
        // is live and resumes the stored talk when dead. The old shape
        // sent ACTION_TOGGLE while the service was alive, and that
        // intent went stale across a process death: the first tap after
        // the kill hit the trackless-toggle leg (stopSelf, silent
        // no-op) and only the onCreate refresh swapped in the resume
        // intent, so the second tap worked. One action, no stale leg.
        // The notification card keeps its own ACTION_TOGGLE (rebuilt
        // on every card post, never stale the same way).
        Intent play = new Intent(context, AudioService.class);
        play.setAction(ACTION_WIDGET_PLAY);
        // A dead service must come up as a foreground service to play;
        // getForegroundService is the API-26+ form for exactly this,
        // getService the pre-26 form.
        if (Build.VERSION.SDK_INT >= 26) {
            return PendingIntent.getForegroundService(context, RC_WIDGET_PLAY, play,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        return PendingIntent.getService(context, RC_WIDGET_PLAY, play,
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
            try { lpExists = lp.length() > 0 && new java.io.File(lp).isFile() && new java.io.File(lp).length() > 0; }
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
            // v13.59 (887 F-NEW-1, F948-2): identity compare — the
            // live URL may be the http retry form of the stored talk.
            if (SiteHttp.sameTalk(liveUrl, lastUrl)) return true;
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
