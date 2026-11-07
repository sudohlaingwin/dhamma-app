package com.h2h.dhamma;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

/**
 * Foreground service owning playback, so a talk keeps playing when the
 * user leaves the talk screen or the app goes to the background.
 * The notification offers pause/resume and stop; stopping the service
 * (notification "stop" action) is the only way playback ends besides
 * the sleep timer or starting another talk.
 */
public class AudioService extends Service {

    public static final String ACTION_TOGGLE = "com.h2h.dhamma.TOGGLE";
    public static final String ACTION_STOP = "com.h2h.dhamma.STOP";
    // v5.23: notification Forward (+30s) — the card had Previous /
    // Play / Next / Stop but no way forward inside a talk, while the
    // Room's control row has had Forward since v2.48. Same no-bind
    // shape as ACTION_SEEK: the branch below reuses seekTo, so a
    // seek before prepare becomes the pending seek and a seek on a
    // failed track retries it, exactly like a Room bar drag.
    public static final String ACTION_FORWARD = "com.h2h.dhamma.FORWARD";
    // v5.42: notification favourite toggle — the card shows a
    // star in the title (v4.26) but could never change it, while
    // the Room's time row (v5.20) and every strip (v4.75) can, so
    // favouring a talk with the phone locked meant reopening the
    // app. Same no-bind shape as ACTION_FORWARD: the branch below
    // reuses the card's own title-star guard, so a library session
    // never toggles another talk's star, and refreshNotification
    // flips the title star and the action's own icon at once.
    public static final String ACTION_FAV = "com.h2h.dhamma.FAV";
    // v10.03: no-bind card refresh for screens that never bind
    // (Talks chooser, same shape as ACTION_STOP / ACTION_TOGGLE
    // which Talks already fires): a dialog fav flip there cannot
    // call refreshNotification() directly, the v10.02 Room +
    // Downloaded leg. Trackless = stopSelf, the v1.78 rule;
    // a live track rebuilds the card, which re-reads favTalks.
    public static final String ACTION_REFRESH_NOTIFICATION = "com.h2h.dhamma.REFRESH_NOTIFICATION";
    public static final String ACTION_SEEK = "com.h2h.dhamma.SEEK";
    // v12.82: History's direct file play (sim1298) — play one
    // downloaded file by path, no bind, no Room. History writes
    // the library anchors first, so the session is a library
    // session exactly like a DownloadedActivity row tap; the
    // branch below mirrors the widget-play library resume.
    public static final String ACTION_PLAY_FILE = "com.h2h.dhamma.PLAY_FILE";
    // v4.80: mini-player sleep, fired by Fx.stripSleepPick/Cancel —
    // the strips never bind, so the choice reaches the live talk
    // as an intent (same no-bind shape as ACTION_SEEK) and the
    // service re-reads the pref itself, keeping the paused-talk
    // rule and the unchanged-no-restart rule of syncSettings.
    public static final String ACTION_SLEEP = "com.h2h.dhamma.SLEEP";
    // v4.82: one-shot "stop at end of talk" sleep — arm / cancel intents
    // for the strip + Settings pickers (no bind, like ACTION_SLEEP).
    public static final String ACTION_SLEEP_END = "com.h2h.dhamma.SLEEP_END";
    public static final String ACTION_SLEEP_END_CANCEL = "com.h2h.dhamma.SLEEP_END_CANCEL";
    // v8.56: explicit minute pick, fired by Fx.stripSleepPick and the
    // Settings sleep picker — carries the picked minutes as an int
    // extra and applies them directly via setSleepMinutes, which
    // clears any armed one-shot. ACTION_SLEEP is a routine re-sync
    // (bind/return/tick) and must keep its unchanged-pref-keeps-
    // the-one-shot guard, so a pick that happens to equal the pref
    // the one-shot was armed with (0 for an off-pick, or 30 for a
    // 30-minute pick) used to toast the new choice while the
    // stop-at-end stayed armed — the pick looked applied and was
    // not. A pick is never a routine sync, so it gets its own
    // intent; every routine sync still goes through ACTION_SLEEP.
    public static final String ACTION_SLEEP_PICK = "com.h2h.dhamma.SLEEP_PICK";
    // v4.91: strip speed button (no bind, like ACTION_SLEEP) —
    // carries the new speed as a float extra; the branch below
    // applies it to the live talk at once.
    public static final String ACTION_SPEED = "com.h2h.dhamma.SPEED";
    // v4.73: mini-player seek, fired by Fx.miniSeekBar — a tap or
    // drag on any now-playing strip's progress bar. "seekMs" is the
    // absolute position; the service clamps and reuses seekTo, so a
    // seek before prepare becomes the pending seek exactly like a
    // Room bar drag, and a seek on a failed track retries it.
    // v1.81: pause-if-backgrounded, fired by AppForeground's delayed
    // check — lets screens that never bound to the service (main list,
    // chooser, Settings) still honour background-playback-off when the
    // user leaves the app from them. The service decides (see below),
    // so a stale check never pauses a talk that came back on top.
    public static final String ACTION_AUTO_PAUSE = "com.h2h.dhamma.AUTO_PAUSE";

    public interface Listener {
        void onStateChanged(boolean playing, String title);
        void onError(String message);
        void onTrackEnded();
    }

    // v5.26: NEW channel id — the old "playback" channel was created
    // IMPORTANCE_LOW back in v1.1 and Android ignores every later
    // re-creation, so its LOW importance was frozen forever. A new
    // id is the only way a new importance actually takes effect.
    private static final String CHANNEL_ID = "playback_media";
    // v10.76: fallback channel id for the blocked-channel recovery
    // (resolveChannelId below) — a fresh id is the only way a new
    // importance takes effect; recreating the same id restores the
    // blocked state Android kept for it.
    private static final String CHANNEL_ID_FALLBACK = "playback_media2";
    // v12.96 TEST (his report 2026-11-06 ~23:45 +07 "notiက လုံးဝမပေါ်တော့ဘူး"
    // on a v10.74+ build): the custom RemoteViews card is OFF. The code
    // already had three fallbacks (plain snapshot, wrapper minimal card,
    // channel migration), all of which "succeed" while the shade shows
    // nothing — the one failure they cannot catch is the phone's shade
    // silently dropping a posted custom-view notification (the Xiaomi-
    // shade hypothesis floated 2026-11-03). This build posts ONLY the
    // plain card (title/sub/progress/actions/portrait — the v5.28 card
    // that DID show in his screenshots). If the card appears now, the
    // custom views were the culprit and plain stays; if it still shows
    // nothing, the cause is channel/permission/system-level and the
    // next step is the Settings ⚠ check on his phone. Revert by
    // flipping this back to true — the v9.01 code is untouched.
    private static final boolean USE_CUSTOM_NOTI_VIEWS = false;
    private static final int NOTIF_ID = 1;

    private final IBinder binder = new LocalBinder();
    // v1.28: audio focus — a phone call or another app pauses the talk
    // and playback resumes when focus returns (permanent loss: stay paused)
    private android.media.AudioManager audioManager;
    private android.media.AudioManager.OnAudioFocusChangeListener focusListener;
    private boolean resumeOnFocusGain = false;
    // v1.56: duck state — a transient-duck event (e.g. a notification
    // sound) only lowers the volume now instead of pausing the talk.
    private boolean ducked = false;
    private MediaPlayer player;
    // v4.06: buffered-ahead percent for the Room wave — MediaPlayer
    // reports it via OnBufferingUpdateListener, but nothing ever
    // listened, so a slow stream looked identical to a fully
    // buffered one (gold played bars, dim rest) and a stall mid-
    // talk gave no clue how much was already loaded ahead. The
    // value is % of the track's duration, reset on every play()
    // and stop, and the getter returns 0 for non-http URLs so a
    // downloaded talk (local path as currentUrl) keeps its exact
    // old wave — its bars never need a buffered tint.
    private volatile int bufferedPercent = 0;
    // v1.70: framework MediaSession — drives the notification's
    // MediaStyle template (live elapsed/total + seekbar, system
    // media controls, headset buttons) with no extra library.
    private android.media.session.MediaSession mediaSession;
    private String currentTitle;
    private String currentUrl;
    // v11.46: resume-mode data-saver gate (closes the hole the
    // 02:08 +07 data-saver audit recorded: a stream talk started
    // on Wi-Fi, paused, then resumed after switching to mobile
    // data streamed silently, because Room/strip/notification/
    // focus-gain resumes all funnel through resume() and none
    // re-checked the gate, and the gate dialog could not resume
    // a held paused talk). gateApprovedUrl = the stream URL the
    // listener already approved for mobile data in this session
    // (set by noteGateApproved / resumeBypassGate, cleared by a
    // fresh play of a different talk and by stopAndClear), so an
    // approved listen is not re-asked on every pause; gateHeld-
    // ResumeUrl = the stream URL resume() refused for the gate,
    // read by the Room poll, which asks the resume-mode question
    // on return. Offline files never gate: a currentUrl that is
    // not an http(s) URL is a local path, and a stream URL with
    // a downloaded file is offline too (see needsResumeGate).
    private String gateApprovedUrl = null;
    private String gateHeldResumeUrl = null;
    // v12.10 (B2, v12.09 deferred): the deferred autoplay advance — a natural end that
    // stopped on the data gate for an in-list next talk records that talk here (Room end
    // block, noteGatedAdvanceTarget); the gate dialog's Wi-Fi-download button binds the
    // DownloadManager job id to it, and the service's download-complete receiver re-fires
    // the advance when that job lands. Behaviour decision, made here not quietly: the
    // advance DOES auto-play on landing, because the listener already chose "Wi-Fi
    // download" as the way to keep listening and autoplay is still on — but only under
    // the guards in onAwaitedDownloadComplete10 (same ended session, nothing else
    // started, autoplay still on, no sleep stop pending, successful download, existing
    // non-zero file). Any guard failing on a successful landing abandons the record, so
    // it can never fire later; a failed download keeps it, so a gate re-queue re-arms it
    // with the new job id. A fresh play (play()) or a stop (stopAndClear()) clears it —
    // the listener moved on. A gate cancel clears it for its own talk only (Room).
    // v12.11: foreign queue heads DO hand over in the background now (advanceQueueHead63):
    // the head plays from its own v2 URL/title, the rest of the queue travels to the new
    // sayadaw's key, and the last* anchors move with it. Still deferred, queue untouched:
    // a catalog-miss head, a gated head, and a natural end while the foreign Room is on top.
    // v12.12 (the B2 foreign extension this record now covers): a gated FOREIGN head is
    // recorded by the Room end block's v7.18 branch (awaitAdvanceSetForeign: URL + title +
    // sayadaw + kind=foreign); the gate dialog's Wi-Fi button binds the job id by URL
    // exactly as for an in-list talk (no dialog change), and the receiver re-fires it on
    // landing — Case A (no manual handover yet: the head is still the first waiting
    // foreign line in the old key, so the v12.11 tail plays it, queue + anchors move) or
    // Case B (the listener already handed over by hand: the head is a bare line in the new
    // sayadaw's key, so the receiver only pops it, moves the anchors and plays the landed
    // file; the Room's handover prologue already recorded the walk-back, so no
    // hidden-trail step here). Any validation failing abandons the record, so a moved-on,
    // removed or catalog-miss head can never fire later; the guards in
    // onAwaitedDownloadComplete10 (same ended session, nothing else started, autoplay
    // still on, no sleep stop) apply unchanged.
    // v12.13 (second hidden end, the v12.11 deferred piece): after a background foreign
    // handover the service now fetches the new sayadaw's list on a worker thread and
    // rewrites playList57 to it (refreshPlayListForForeign13, guarded so a moved-on
    // session or a list that cannot name the sounding head never writes), so the next
    // hidden end chains in-list instead of stopping and deferring to the Room poll.
    // The no-network-on-the-completion-thread rule still holds — only the fetch runs
    // behind the handover, never on the completion thread itself.
    private static final String AWAIT_URL10 = "awaitAdvanceUrl";
    private static final String AWAIT_TITLE10 = "awaitAdvanceTitle";
    private static final String AWAIT_JOB10 = "awaitAdvanceJob";
    private static final String AWAIT_KIND12 = "awaitAdvanceKind";
    private static final String AWAIT_SAYADAW12 = "awaitAdvanceSayadaw";

    public static void awaitAdvanceSet(android.content.Context c, String url, String title) {
        try {
            c.getSharedPreferences("dhamma", android.content.Context.MODE_PRIVATE).edit()
                    .putString(AWAIT_URL10, url == null ? "" : url)
                    .putString(AWAIT_TITLE10, title == null ? "" : title)
                    .putString(AWAIT_KIND12, "")
                    .remove(AWAIT_SAYADAW12)
                    .putLong(AWAIT_JOB10, -1).apply();
        } catch (Exception ignored) {}
    }

    // v12.12: the foreign half of the record above — same slot, so an in-list set still
    // overwrites it (kind "" there) and a foreign set overwrites an in-list one; the
    // kind is what the receiver branches on, and the sayadaw is what Case B validates
    // against (the new key the Room's handover moved the head into).
    public static void awaitAdvanceSetForeign(android.content.Context c, String url,
            String title, String sayadaw) {
        try {
            c.getSharedPreferences("dhamma", android.content.Context.MODE_PRIVATE).edit()
                    .putString(AWAIT_URL10, url == null ? "" : url)
                    .putString(AWAIT_TITLE10, title == null ? "" : title)
                    .putString(AWAIT_SAYADAW12, sayadaw == null ? "" : sayadaw)
                    .putString(AWAIT_KIND12, "foreign")
                    .putLong(AWAIT_JOB10, -1).apply();
        } catch (Exception ignored) {}
    }

    public static void awaitAdvanceBind(android.content.Context c, String url, long jobId) {
        try {
            android.content.SharedPreferences sp =
                    c.getSharedPreferences("dhamma", android.content.Context.MODE_PRIVATE);
            if (url == null || !url.equals(sp.getString(AWAIT_URL10, ""))) return;
            sp.edit().putLong(AWAIT_JOB10, jobId).apply();
        } catch (Exception ignored) {}
    }

    public static void awaitAdvanceClear(android.content.Context c) {
        try {
            c.getSharedPreferences("dhamma", android.content.Context.MODE_PRIVATE).edit()
                    .remove(AWAIT_URL10).remove(AWAIT_TITLE10).remove(AWAIT_JOB10)
                    .remove(AWAIT_KIND12).remove(AWAIT_SAYADAW12).apply();
        } catch (Exception ignored) {}
    }

    public static void awaitAdvanceClearIf(android.content.Context c, String url) {
        try {
            android.content.SharedPreferences sp =
                    c.getSharedPreferences("dhamma", android.content.Context.MODE_PRIVATE);
            if (url != null && url.equals(sp.getString(AWAIT_URL10, ""))) awaitAdvanceClear(c);
        } catch (Exception ignored) {}
    }

    // v12.62 (AudioService audit Finding 4): the in-progress download
    // filenames are now cached — offlineFileFor() + shelfFiles79()
    // used to re-run a full DownloadManager query (every download
    // on the device, Binder + Cursor) on EVERY call, ~35 call
    // sites, bursting at every track end and card rebuild. The
    // query result lives here for 10s (INPROG_TTL_MS62) and is
    // invalidated at once on (a) ANY download completion — the
    // receiver below already fires for every completed download,
    // and (b) ANY dlMap write — dlMapListener62 below; every
    // enqueue path (Room :3185/:10435, Talks :1870, Fx
    // stripEnqueueDownload) writes dlMap AFTER dm.enqueue, so
    // the job exists by the time the listener fires, and a
    // just-enqueued file can never be missed. The dlMap parse
    // and the File.exists() checks stay LIVE on every call: a
    // file deleted outside the app is caught by exists(), and a
    // dlMap edit is read fresh, so the only staleness the cache
    // can introduce is bounded by the 10s TTL and is in the safe
    // direction (a just-finished file streams for at most one
    // TTL window; a just-enqueued file is never returned, because
    // the enqueue's dlMap write invalidates first). A failed DM
    // query is never cached (query returns null -> fail-open
    // empty set, as before), so a dead DownloadManager cannot
    // poison the cache. Same-process note: the service runs in
    // the app process (no android:process), so the listener sees
    // the activities' dlMap writes on the shared prefs instance;
    // it is held in a field (prefs holds listeners weakly) and
    // registered in onCreate / unregistered in onDestroy.
    private static final long INPROG_TTL_MS62 = 10000;
    private volatile java.util.Set<String> inProgCache62 = null;
    private volatile long inProgCacheAt62 = 0;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener dlMapListener62 =
            new android.content.SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override public void onSharedPreferenceChanged(android.content.SharedPreferences sp, String key) {
            if ("dlMap".equals(key)) inProgCache62 = null;
        }
    };

    // v12.10: download-complete receiver for the deferred advance above — the Room's own
    // receiver only rescans/toasts (and dies with the Room), so the re-fire lives here,
    // in the service that holds the ended session. Registered in onCreate, unregistered
    // in onDestroy, same SDK-33 NOT_EXPORTED pattern as noisyReceiver.
    private android.content.BroadcastReceiver dlAwaitedReceiver =
            new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context ctx, Intent intent) {
            try {
                if (!android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                // v12.62 (Finding 4): any completion changes the
                // in-progress set, so the cached query goes now —
                // before the doneId / awaited filtering below, which
                // only serves the deferred advance.
                inProgCache62 = null;
                long doneId = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (doneId < 0) return;
                onAwaitedDownloadComplete10(doneId);
            } catch (Exception ignored) {}
        }
    };

    private void onAwaitedDownloadComplete10(long doneId) {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("dhamma", MODE_PRIVATE);
            String awaitUrl = sp.getString(AWAIT_URL10, "");
            long awaitJob = sp.getLong(AWAIT_JOB10, -1);
            // Not our download (or nothing awaited, or no job bound yet): leave the
            // record — its own job may still be in flight, and a later completion must
            // still find it. The job id is NOT the identity check: a duplicate live job
            // for the same URL (gate enqueue + a Talks batch/single enqueue, or a
            // re-queue after a stall) can land first, and the bound job can fail while
            // the other succeeds — v12.18: the COLUMN_URI check below is the identity
            // (same URL = same talk), so a landing for the awaited URL re-fires
            // whichever job carried it. A different URL still returns untouched.
            if (awaitUrl.length() == 0 || awaitJob < 0) return;
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            if (dm == null) return;
            android.database.Cursor cur = dm.query(
                    new android.app.DownloadManager.Query().setFilterById(doneId));
            if (cur == null) return;
            try {
                if (!cur.moveToFirst()) return;
                int stCol = cur.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS);
                int uriCol = cur.getColumnIndex(android.app.DownloadManager.COLUMN_URI);
                int localCol = cur.getColumnIndex(android.app.DownloadManager.COLUMN_LOCAL_URI);
                int titleCol = cur.getColumnIndex(android.app.DownloadManager.COLUMN_TITLE);
                int st = stCol >= 0 ? cur.getInt(stCol) : -1;
                // A failed download keeps the record: a gate re-queue re-arms it with the
                // new job id, and this dead id can never match again, so keeping it is inert.
                if (st != android.app.DownloadManager.STATUS_SUCCESSFUL) return;
                String src = uriCol >= 0 ? cur.getString(uriCol) : null;
                if (src == null || !src.equals(awaitUrl)) return;
                String localUri = localCol >= 0 ? cur.getString(localCol) : null;
                String title = titleCol >= 0 ? cur.getString(titleCol) : null;
                if (title == null || title.length() == 0) title = sp.getString(AWAIT_TITLE10, "");
                // Resolve the landed file: the job's own local URI first, the dlMap lookup
                // second (same file either way). A zero-length file never plays (v11.90
                // class) — abandon the record instead of playing truncated.
                java.io.File landed = null;
                if (localUri != null && localUri.length() > 0) {
                    try {
                        String p = android.net.Uri.parse(localUri).getPath();
                        if (p != null) landed = new java.io.File(p);
                    } catch (Exception ignored) {}
                }
                if (landed == null || !landed.exists()) {
                    java.io.File off = offlineFileFor(awaitUrl);
                    if (off != null) landed = off;
                }
                if (landed == null || !landed.exists() || landed.length() <= 0) {
                    awaitAdvanceClear(this);
                    return;
                }
                // Guards: the session must still be the ended one — the same talk ended,
                // nothing else started (a fresh play clears trackEnded and the record with
                // it), autoplay still on, and no sleep stop standing in the way. Any
                // failure abandons the record so it can never fire later.
                if (!trackEnded || currentUrl == null || isPlaying()
                        || !Prefs.autoplay(this) || isSleepEndOfTalk() || isSleepStopPending()) {
                    awaitAdvanceClear(this);
                    return;
                }
                // v12.12: a gated FOREIGN head (kind=foreign, recorded by the Room end
                // block's v7.18 branch) — the plain play below would leave the queue and
                // the anchors on the old sayadaw, so it hands over instead (Case A) or
                // re-fires post-handover (Case B). The helper consumes or abandons the
                // record itself; this path returns either way.
                if ("foreign".equals(sp.getString(AWAIT_KIND12, ""))) {
                    onAwaitedForeignComplete12(sp, awaitUrl, title, landed);
                    return;
                }
                // Consume before playing: play() clears the record again (harmless), and a
                // tap in the prepare window supersedes this talk like any other.
                awaitAdvanceClear(this);
                // v12.18 (slice A F1): the anchors move with the advance — every other
                // background advance writes lastUrl/lastTitle before play(), and play()
                // writes neither, so without this the next hidden end re-derived its
                // index from the ENDED talk (soundingLineIndex96 prefers lastUrl) and
                // replayed this talk once, skipping the true next talk (or the ended
                // talk under repeat-one).
                sp.edit().putString("lastUrl", awaitUrl)
                        .putString("lastTitle", title == null ? "" : title).apply();
                if (title != null && title.length() > 0) {
                    android.widget.Toast.makeText(AudioService.this,
                            Lang.t(AudioService.this, "next_prefix") + title,
                            android.widget.Toast.LENGTH_SHORT).show();
                }
                // v12.18 (slice A F6): resume from the saved spot like every other
                // advance (foreign re-fires + plain/queue/shuffle tails all use
                // savedSeekFor) — the 2-arg play() restarts from 0.
                play(landed.getAbsolutePath(), title == null ? "" : title, savedSeekFor(awaitUrl));
            } finally { try { cur.close(); } catch (Exception ignored) {} }
        } catch (Exception ignored) {}
    }
    // v12.12: the gated-foreign re-fire for the await record above (see the v7.18
    // branch + noteGatedForeignAdvanceTarget in RoomActivity). Runs after the receiver's
    // guards pass (same ended session, autoplay on, no sleep stop) and the landed file
    // is resolved non-zero, so the only questions left are queue state and catalog:
    //   Case A — no manual handover yet: the head is still the first waiting foreign
    //     line in the OLD sayadaw's key (awaitedForeignHeadPos12), so the v12.11 tail
    //     plays it — queue + anchors move, exactly like the background handover.
    //   Case B — the listener already handed over by hand (Next / header / drawer row):
    //     the head is a bare line in the NEW sayadaw's key (v7.23 keep), the old key is
    //     already clear, so there is no queue left to carry — pop the bare line, move
    //     the anchors to the new sayadaw (the notification tap must open the foreign
    //     Room, not the old one), pop-or-clear the forward stack like any queue play,
    //     and play the landed file. The hidden-trail step is deliberately NOT repeated
    //     here: the Room's handover prologue already recorded the walk-back
    //     (prevHistory + handoverBack21), and a second step would walk Previous back
    //     to the same talk twice.
    //   Neither — the head was removed, another head moved ahead of it, or the queue
    //     moved on: abandon the record. The download stays on disk (the listener can
    //     still tap the row), but nothing auto-plays out of order.
    // A catalog miss abandons too — the Room's handover would refuse the head on return
    // (v11.68/v11.85), so the service must not play a talk with no page. A missing
    // playListRaw abandons as well: the tail needs the saved list for the v1-line
    // title carry and the ended-talk index. The foreign Room being on top is allowed
    // (unlike v12.11's case (c)): the end already happened in the OLD Room, and the
    // foreign Room's poll syncs to a talk in its own list; deferring here would leave
    // the listener staring at the landed download with nothing playing.
    private void onAwaitedForeignComplete12(android.content.SharedPreferences sp,
            String awaitUrl, String title, java.io.File landed) {
        try {
            String awaitSayadaw = sp.getString(AWAIT_SAYADAW12, "");
            if (awaitSayadaw.length() == 0) { awaitAdvanceClear(this); return; }
            // v12.18 (slice A F5): the record can hold a pre-rename name across an app
            // update (the v11.99 queue migration rewrites stored v2 fields, but never
            // this record) — resolve it to the current catalog name before the lookup
            // and the scan, so a renamed sayadaw is neither abandoned on a catalog
            // miss nor handed over under the wrong anchors.
            awaitSayadaw = Prefs.canonicalSayadaw99(AudioService.this, awaitSayadaw);
            String[] cat12 = catalogEntryForSayadaw11(AudioService.this, awaitSayadaw);
            if (cat12 == null || cat12[1].length() == 0) { awaitAdvanceClear(this); return; }
            String raw12 = Prefs.playListRaw(this);
            if (raw12 == null || raw12.length() == 0) { awaitAdvanceClear(this); return; }
            String[] lines12 = raw12.split("\n");
            String lastName12 = sp.getString("lastName", "");
            int headPos12 = -1;
            String[] qLines12 = null;
            String qRaw12 = Prefs.savedQueue(this, lastName12);
            if (qRaw12 != null && qRaw12.length() > 0) {
                qLines12 = qRaw12.split("\n");
                headPos12 = awaitedForeignHeadPos12(qLines12, lines12, awaitUrl, lastName12, awaitSayadaw);
            }
            int barePos12 = -1;
            String[] newQLines12 = null;
            if (headPos12 < 0) {
                String nqRaw12 = Prefs.savedQueue(this, awaitSayadaw);
                if (nqRaw12 != null && nqRaw12.length() > 0) {
                    newQLines12 = nqRaw12.split("\n");
                    // v12.18 (slices A F4 + B finding 2): the FIRST waiting line decides,
                    // like awaitedForeignHeadPos12 — the old scan took the first line
                    // matching the URL anywhere in the key, so a talk set-as-next ahead
                    // of the head (drawer queueUrls.add at the first-waiting index) was
                    // jumped when the download landed. Sounding/empty lines are skipped;
                    // any other waiting line ahead abandons the record and the normal
                    // advance plays the true head instead.
                    for (int i = 0; i < newQLines12.length; i++) {
                        QueueEntry q12 = QueueEntry.parse(newQLines12[i]);
                        if (q12 == null || q12.url.length() == 0) continue;
                        if (q12.url.equals(currentUrl)) continue;
                        java.io.File qf12 = offlineFileFor(q12.url);
                        if (qf12 != null && qf12.getAbsolutePath().equals(currentUrl)) continue;
                        if (q12.url.equals(awaitUrl)) barePos12 = i;
                        break;
                    }
                }
            }
            if (headPos12 < 0 && barePos12 < 0) { awaitAdvanceClear(this); return; }
            // Consume before playing: play() clears the record again (harmless), and a
            // tap in the prepare window supersedes this talk like any other.
            awaitAdvanceClear(this);
            if (headPos12 >= 0) {
                QueueEntry qe12 = QueueEntry.parse(qLines12[headPos12]);
                String hTitle12 = qe12 != null && qe12.title != null && qe12.title.length() > 0
                        ? qe12.title
                        : (title != null && title.length() > 0 ? title : awaitUrl);
                // v12.63 (AudioService audit Finding 2, carried from the 11:26 run):
                // the v12.61 boolean is load-bearing here too — the old code ignored
                // it, so a pre-play failure inside playForeignHead11 (queue carry /
                // anchor write throw before play() runs) left the listener staring at
                // the landed download in silence: the await record was already
                // consumed, the head possibly already popped, and nothing ever
                // advanced until the Room returned. `false` now falls through to the
                // plain background advance (same entry point the natural end uses),
                // which re-consults the queue (a not-yet-popped head is retried once,
                // then falls through itself) and otherwise plays the next in-list
                // talk — forward/shuffle/list-next with the same gates. The toast
                // moves behind the boolean: the fall-through is silent like every
                // other background advance (no Toast in advanceInBackground58's
                // tails), so a stale "next: <head>" must not promise a talk that
                // never plays. soundingLineIndex96 prefers lastUrl but falls back to
                // currentUrl, so the fall-through still finds the ended index even
                // if the tail moved the anchors before failing.
                if (playForeignHead11(sp, qLines12, headPos12, awaitUrl, awaitSayadaw,
                        hTitle12, cat12, lines12, landed.getAbsolutePath())) {
                    if (hTitle12.length() > 0) {
                        android.widget.Toast.makeText(AudioService.this,
                                Lang.t(AudioService.this, "next_prefix") + hTitle12,
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                advanceInBackground58(false);
                return;
            }
            // Case B: the head is a bare line in the new key — it plays now, so pop it
            // (the Room's firstWaitingQueueEntry would skip it as sounding anyway, but
            // the drawer would keep its ◈ mark and the next end would re-offer it).
            java.util.ArrayList<String> rest12 = new java.util.ArrayList<String>();
            for (int i = 0; i < newQLines12.length; i++)
                if (i != barePos12) rest12.add(newQLines12[i]);
            Prefs.saveQueue(this, awaitSayadaw, rest12);
            String titleB12 = title != null && title.length() > 0 ? title : awaitUrl;
            sp.edit().putString("lastUrl", awaitUrl).putString("lastTitle", titleB12)
                    .putString("lastName", awaitSayadaw)
                    .putString("lastGroup", cat12[0])
                    .putString("lastPage", cat12[1])
                    .putString("lastPhoto", cat12[2]).apply();
            // v11.20 shape: a queue play pop-or-clears the forward stack — the Room's
            // v8.28 reconcile adopts the shrinkage on return, same as the tail.
            try {
                long genB12 = clearGen95(sp);
                String fwdRawB12 = sp.getString("forwardHistoryRaw", "");
                if (fwdRawB12 != null && fwdRawB12.length() > 0) {
                    String[] fwdPartsB12 = fwdRawB12.split("\\n");
                    java.util.ArrayList<String> fwdStackB12 = new java.util.ArrayList<String>();
                    for (String fpB12 : fwdPartsB12)
                        if (fpB12 != null && fpB12.length() > 0) fwdStackB12.add(fpB12);
                    if (!fwdStackB12.isEmpty() && clearGen95(sp) == genB12) {
                        if (fwdStackB12.get(fwdStackB12.size() - 1).equals(awaitUrl)) {
                            fwdStackB12.remove(fwdStackB12.size() - 1);
                            StringBuilder fwdSbB12 = new StringBuilder();
                            for (int fiB12 = 0; fiB12 < fwdStackB12.size(); fiB12++) {
                                if (fiB12 > 0) fwdSbB12.append("\n");
                                fwdSbB12.append(fwdStackB12.get(fiB12));
                            }
                            sp.edit().putString("forwardHistoryRaw", fwdSbB12.toString()).apply();
                        } else {
                            sp.edit().remove("forwardHistoryRaw").apply();
                        }
                    }
                }
            } catch (Exception ignored) {}
            if (titleB12.length() > 0) {
                android.widget.Toast.makeText(AudioService.this,
                        Lang.t(AudioService.this, "next_prefix") + titleB12,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
            play(landed.getAbsolutePath(), titleB12, savedSeekFor(awaitUrl));
            // v12.13: same second-hidden-end refresh as the shared tail (Case B plays
            // here, not through playForeignHead11).
            refreshPlayListForForeign13(awaitSayadaw, cat12[1], awaitUrl);
        } catch (Exception ignored) {}
    }

    // v12.12: Case-A validation scan — the advanceQueueHead63 order rule, as a pure
    // lookup: lines are read in order, malformed lines skipped, the sounding talk
    // skipped, an unlisted in-list line skipped (v11.52 — it stays queued for the
    // Room's fresh-list peek on return). The FIRST foreign line decides: only the
    // awaited head proceeds. A listed in-list head ahead of it means the queue moved
    // on — abandon, so the normal path plays that head and the foreign head stays
    // queued for later. A different foreign head ahead likewise abandons. v12.18:
    // the first foreign line must also name the awaited sayadaw (canonical on both
    // sides — the queue line may still carry a pre-rename name if the v11.99
    // migration has not run in this process), so the same URL queued under two
    // sayadaws hands over under the right anchors. Returns the queue line index
    // of the awaited head, or -1.
    private int awaitedForeignHeadPos12(String[] qLines, String[] lines,
            String awaitUrl, String lastName, String awaitSayadaw) {
        for (int i = 0; i < qLines.length; i++) {
            QueueEntry qe = QueueEntry.parse(qLines[i]);
            if (qe == null) continue;
            String qu = qe.url;
            if (qu.length() == 0) continue;
            if (qu.equals(currentUrl)) continue;
            java.io.File qf = offlineFileFor(qu);
            if (qf != null && qf.getAbsolutePath().equals(currentUrl)) continue;
            if (qe.isV2() && !qe.sayadaw.equals(lastName)) {
                if (!Prefs.canonicalSayadaw99(AudioService.this, qe.sayadaw).equals(awaitSayadaw)) return -1;
                return qu.equals(awaitUrl) ? i : -1;
            }
            int found = -1;
            for (int j = 0; j < lines.length; j++) {
                String line = lines[j];
                if (line == null || line.length() == 0) continue;
                int tab = line.indexOf('\t');
                String u = tab >= 0 ? line.substring(0, tab) : line;
                if (u.equals(qu)) { found = j; break; }
                java.io.File f = offlineFileFor(u);
                if (f != null && f.getAbsolutePath().equals(qu)) { found = j; break; }
            }
            if (found >= 0) return -1;
        }
        return -1;
    }

    // v5.23: notification large-icon cache — the portrait is decoded
    // from the disk cache only when its URL changes, never on every
    // 15s card rebuild, so the rebuild stays cheap.
    private android.graphics.Bitmap notiLargeIcon;
    private String notiLargeUrl = "";
    // v5.99: library-session large-icon cache — the mirror of the
    // Room cache above, keyed by sayadaw NAME (a library session
    // has no lastPhoto pref), so the card's 15s rebuilds never
    // re-decode. The name->photo map itself is loaded once per
    // process from the bundled catalog (see photoUrlForSayadaw).
    private android.graphics.Bitmap notiLibIcon;
    private String notiLibName = "";
    // v3.91: live-session flag for the Talks auto-scroll — true
    // from onCreate until onDestroy, so a screen can tell a
    // playing-or-paused session apart from a remembered one.
    // The service is a started foreground service, so it exists
    // exactly while a session is loaded: every stop path ends in
    // stopSelf (notification stop, sleep end, clear, task removed),
    // and a dead process resets the static. A library session
    // also keeps it true, so readers must still check the
    // lastKind="room" anchor before trusting the Room talk.
    public static boolean liveSession = false;
    // v10.40: static mirror of hasTrack() for the no-bind strips
    // (Main / Talks / Settings never bind, so they cannot call
    // it). True exactly while currentTitle is set: play() sets
    // it with the title, stopPlayback() clears it with the
    // title, onCreate/onDestroy bound it to the instance life.
    public static volatile boolean liveHasTrack = false;
    // v4.16: live play/pause state for screens that never bind
    // (Main's mini strip reads this on its 1s tick, like
    // liveSession — no bind, no service-intent round trip).
    // Set at every state edge below: play/resume true, pause/
    // stop/completion/error false. Buffering counts as playing
    // here (the talk is on its way); the Room's own poll still
    // names buffering separately for the bound screen.
    public static volatile boolean livePlaying = false;
    // v4.29: finished-track flag for the no-bind surfaces (Main
    // strip, Main pulse gate) — set on natural completion only,
    // cleared on every path that clears trackEnded below, so a
    // finished talk never keeps reading as sounding or paused.
    public static volatile boolean liveEnded = false;
    // v4.30: live position/duration for no-bind surfaces — Main's
    // mini-player progress bar reads these on its 1s tick (no
    // bind, like livePlaying). The service owns the 1s tick
    // (syncLivePosition, re-armed from updateSession's state
    // edges): while playing it refreshes both, paused it holds
    // the last position so the bar freezes mid-talk instead of
    // clearing, stopped/destroyed it resets to zero.
    public static volatile int livePositionMs = 0;
    public static volatile int liveDurationMs = 0;
    // v8.01: live identity for the no-bind strips — Fx.miniSeekBar
    // captures this at touch-down and compares it at lift, so a
    // strip scrub that outlives its talk (auto-advance mid-drag)
    // is discarded like the Room wave's v7.97 discard instead of
    // seeking the REPLACEMENT talk to the old talk's fraction.
    // Refreshed wherever position/duration refresh (the 1s
    // playing tick and syncLivePosition's state edges), cleared
    // on destroy with the other live fields; a null here means
    // "unknown", never "changed" — Fx only discards on a
    // positive mismatch of two non-null values.
    public static volatile String liveUrl = null;
    // v12.67: live title mirror for the home-screen widget (and
    // any future no-bind surface that names the talk): the prefs
    // lastTitle is NOT written by play() — only the advance paths
    // write it before play() — so a plain Room tap left the stored
    // title stale while currentTitle was already the new talk, and
    // the widget cannot bind to ask. Set in play() with the title,
    // cleared in stopPlayback()/onDestroy() with the other live
    // fields; null means "no track", never "unknown".
    public static volatile String liveTitle = null;
    // v4.25: a sleep timer is armed or pause-suspended — Main's mini
    // strip reads this on its 1s tick (no bind, like livePlaying),
    // because the strip otherwise cannot know a timer is running.
    // Set at every sleep edge below: arming true, cancelling /
    // firing / stopping false. A pause-suspension keeps it true
    // (the countdown is still pending, not gone); a stale
    // suspension left behind by a turned-off setting is cleared
    // with it at the next talk start (see onPrepared).
    public static volatile boolean liveSleepActive = false;
    // v4.72: live sleep countdown for the no-bind mini-player
    // strips (Main / Talks / library / Settings all tick at 1s
    // but never bind, so they cannot call getSleepRemainingMs).
    // Same pattern as livePositionMs: the service's own tick
    // refreshes it while playing, and every sleep edge below
    // re-syncs it, so a strip never reads a stale minute. While
    // a countdown runs it is end-now; while pause-suspended it
    // is the frozen remainder; 0 when no timer is pending.
    public static volatile long liveSleepRemainingMs = 0;
    // v4.72: M:SS / H:MM:SS clock for that countdown — the same
    // shape Room.formatTime draws for its own sleep line.
    public static String sleepClock(long ms) {
        int total = (int) Math.max(0, ms / 1000);
        if (total >= 3600)
            return (total / 3600) + ":" + String.format("%02d", (total % 3600) / 60)
                    + ":" + String.format("%02d", total % 60);
        return (total / 60) + ":" + String.format("%02d", total % 60);
    }
    private void syncLiveSleepRemaining() {
        if (sleepEndMs != 0) {
            long rem = sleepEndMs - android.os.SystemClock.elapsedRealtime();
            liveSleepRemainingMs = rem > 0 ? rem : 0;
        } else {
            liveSleepRemainingMs = sleepSuspendedMs;
        }
    }
    // v1.55: real end-of-track flag — the Room and the downloaded
    // library used to guess "ended" from position-near-duration, so
    // pausing a talk in its last seconds wrongly skipped to the next
    // talk (and marked it listened). Only natural completion sets this.
    private boolean trackEnded = false;
    // v1.58: a failed stream used to leave a track that Play/Pause
    // could never restart — toggle() no-ops while !prepared, so the
    // button went dead. Remember the error so the Room can retry
    // the same talk instead of toggling a dead player.
    private boolean trackError = false;
    // v1.69: pause tapped while a stream is still buffering — toggle()
    // used to no-op on an unprepared player, so the talk then started
    // playing anyway despite the tap. Remember the hold and honour it
    // in onPrepared instead.
    private boolean holdPaused = false;
    private float speed = 1.0f;
    private boolean prepared;
    private Listener listener;
    private Handler sleepHandler = new Handler();
    private Runnable sleepRunnable;
    // v4.82: one-shot "stop at end of talk" sleep — a session-only flag,
    // never a Settings default: it outranks the minute default for the
    // talk playing when it ends, then is consumed, so it can never
    // silently disable auto-advance forever. sleepEndArmedPref is the
    // minute pref at arm time: a later minute change (mins != this)
    // is a fresh user choice and wins over the one-shot, while a
    // routine sync with the pref unchanged leaves it alone.
    private boolean sleepEndOfTalk = false;
    // v8.38: whether the armed one-shot already did its stop at a
    // natural end (set in onCompletion, cleared by every flag clear
    // below). A foreign end — a Room/library screen that is not the
    // session's own sitting on top — is deliberately never consumed
    // by that screen's poll (Room isCurrentTalk guard, library
    // foreignEnd75), and the background advances only refuse to
    // advance without consuming, so without this the fired flag
    // survived into whatever talk the listener picked next and
    // stopped a SECOND talk at its end: one "stop at end" request,
    // two stops, the first one silent. play() consumes a fired
    // flag before starting the new talk; a fresh arm after an end
    // clears it first, so arm-then-play still stops that talk.
    private boolean sleepEndFired = false;
    private int sleepEndArmedPref = 0;
    public static volatile boolean liveSleepEndOfTalk = false;
    private long sleepEndMs = 0; // v1.24: elapsedRealtime when the sleep timer fires
    // v1.80: the minutes the running timer was set with (0 = none) —
    // the Room's live sync compares the Settings value against this,
    // so changing the setting mid-session restarts the timer instead
    // of leaving the old countdown running until the next talk.
    private int sleepSetMins = 0;
    // v1.80: milliseconds left on the timer when a talk was paused —
    // a pause suspends the countdown instead of cancelling it (the
    // v1.55 silent clear), so resuming continues where it left off.
    private long sleepSuspendedMs = 0;
    // v7.26: sleep minutes pref as it stood when this talk was paused
    // (-1 = no pause recorded) — resume() compares it against the
    // live pref, so minutes set WHILE paused (strip picker, Settings)
    // arm a fresh timer on resume instead of being silently lost:
    // the old resume only re-armed a suspended remainder, and with
    // no remainder (timer was off when paused) nothing ever armed
    // until a Room/library return re-synced. A fired timer is never
    // resurrected by this: its fade-end pause records the same pref
    // the timer ran with, so the comparison matches and resume
    // leaves it consumed, exactly as before.
    private int pausedSleepPrefMins = -1;
    // v4.04: sleep fade-out — the timer used to cut the talk dead
    // mid-word at bedtime. Fading over ~3s lets the listener drift
    // off instead of being woken by the cut. Handler + flag so a
    // manual pause, a new talk or a stop cancels the fade cleanly
    // and restores full volume for the next resume.
    private Handler fadeHandler = new Handler();
    private Runnable fadeRunnable;
    private boolean sleepFading = false;
    // v11.77: bedtime stop pending — the minute timer fired while a talk was playing and the
    // talk ended before the 3.2s fade-end pause, so the fade never got to stop playback. Set
    // where the countdown fires (armSleepCountdown, playing branch) and cleared only by a
    // completed bedtime stop (the fade's final step), an explicit new sleep pick
    // (setSleepMinutes), a fresh play (play() — a manual pick is a new listen), a stop, an
    // ended-talk replay (resume()'s wasEnded arm — a replay is a new listen too), or a
    // cancelled fade (cancelSleepFade() — a pause mid-fade is the listener choosing to stay
    // up; v11.84 fixed both leaks, where the flag used to survive and silently stop the
    // NEXT natural end).
    // While set, the background advances and the Room/library end blocks must not advance or
    // replay — otherwise playback continues past bedtime for another full sleep period with a
    // freshly armed timer. A plain sleepFading flag cannot carry this: the fade runnable exits
    // within 200ms of the natural end (isPlaying() goes false), long before the next poll tick.
    private boolean sleepStopPending = false;
    // v4.13: gentle fade-in on a new talk + soft fade-out on a UI
    // pause (Spotify-style, no clicks at the edges). fadeIn* ramps
    // a fresh talk from silence to its listening volume over
    // ~700ms; pauseFade* lowers a UI pause to silence over ~300ms
    // and only then pauses. System pauses (calls, unplug,
    // leave-guards, sleep-fade end) keep calling pause() directly
    // and stay instant — a fading speaker on unplug would be a
    // regression, not polish. Both fades are generation-guarded
    // by their runnable nulling: a new talk, a stop or a re-tap
    // cancels the running fade instead of fighting it, and a duck
    // arriving mid-fade-in aborts the ramp (the duck handler owns
    // the volume from then on).
    private Handler fadeInHandler = new Handler();
    private Runnable fadeInRunnable = null;
    private boolean fadeInDucked = false;
    private Handler pauseFadeHandler = new Handler();
    private Runnable pauseFadeRunnable = null;
    private boolean pauseFading = false;
    // v1.12: remember where the listener left off, so "ဆက်နားထောင်ရန်"
    // resumes mid-talk instead of restarting. Saved by the service itself
    // (it outlives the talk screen) every 15s while playing.
    private Handler posHandler = new Handler();
    // v4.30: the mini-player position tick (see livePositionMs).
    private Handler livePosHandler = new Handler();
    // v12.66: listening history / stats (premium backlog #3) —
    // seconds of actual sounding banked by the 1s tick below and
    // flushed to the stats store 60s at a time (see
    // flushListenStats); at most a minute is ever lost to a kill.
    private int listenPendingSec = 0;
    private final Runnable livePosRunnable = new Runnable() {
        @Override public void run() {
            // v11.84: getEffectivePosition(), not getPosition() — a tick posted while talk A played
            // could fire after talk B started but before B prepared, and the raw 0 overwrote B's
            // resume spot on every strip until prepare; syncLivePosition() already uses it.
            try { livePositionMs = getEffectivePosition(); liveDurationMs = getDuration(); liveUrl = currentUrl; } catch (Exception ignored) {}
            try { syncLiveSleepRemaining(); } catch (Exception ignored) {}
            // v12.66: bank one actually-listened second — the tick
            // only re-posts while the player is sounding, and the
            // isPlaying() guard covers the one run that can land
            // after a pause, so buffering/paused time never counts.
            try {
                if (isPlaying()) {
                    listenPendingSec++;
                    if (listenPendingSec >= 60) flushListenStats();
                }
            } catch (Exception ignored) {}
            if (isPlaying()) livePosHandler.postDelayed(this, 1000);
        }
    };
    private void syncLivePosition() {
        // v10.70: syncLivePosition used to overwrite v10.69's strip
        // mirrors with getPosition() — 0 while a talk still buffers
        // — on EVERY updateSession() during buffering, so the fix
        // lasted only until the next push: the 15s posRunnable tick
        // (which re-posts while an unheld talk buffers, v10.66) and
        // v10.60's own buffering-seek push both reset the strip
        // clock to 0:00 on a hung network, and a buffering seek's
        // strip then read 0 while the session (v10.58) read the
        // pending seek. getEffectivePosition() IS the pending seek
        // while buffering, the live position once prepared, and 0
        // for a failed/trackless talk — exactly the session's
        // v10.58 rule, now shared by the mirrors. Prepared talks
        // read identically; duration stays getDuration() (0 until
        // prepare, as v10.69 intended). Only caller is
        // updateSession(), so this is the single point.
        try { livePositionMs = getEffectivePosition(); liveDurationMs = getDuration(); } catch (Exception ignored) {}
        liveUrl = currentUrl; // v8.01: identity rides the same refresh as position/duration
        try { syncLiveSleepRemaining(); } catch (Exception ignored) {}
        livePosHandler.removeCallbacks(livePosRunnable);
        if (isPlaying()) livePosHandler.postDelayed(livePosRunnable, 1000);
    }
    // v12.66: flush the banked listened seconds into the stats
    // store (Prefs.statsAdd maps a downloaded path to its stream
    // URL, so a recent row replays). The sayadaw is the session's
    // lastName; a trackless flush (no title/URL yet) drops the
    // bank instead of recording seconds against nothing. Called
    // every 60 banked seconds by the live tick and on every
    // pause/stop/natural end/destroy, so a pause mid-minute still
    // banks its seconds.
    // v12.71: a library session banks under the sounding file's
    // owner (Prefs.sayadawForTalk — stream URL + the
    // download-time dlSayadaw record), not the stale Room
    // lastName a library session never writes; a Room session
    // keeps lastName, which the service already moves on
    // foreign heads. Unresolvable keeps lastName — never worse.
    private void flushListenStats() {
        int sec = listenPendingSec;
        listenPendingSec = 0;
        if (sec <= 0) return;
        try {
            if (currentTitle == null || currentUrl == null) return;
            String sd = getSharedPreferences("dhamma", MODE_PRIVATE)
                    .getString("lastName", "");
            if (isLibSession()) {
                String owner = Prefs.sayadawForTalk(this, "lib", currentUrl, sd);
                if (owner != null && owner.length() > 0) sd = owner;
            }
            Prefs.statsAdd(this, sd, currentUrl, currentTitle, sec);
        } catch (Exception ignored) {}
    }
    private int pendingSeekMs = 0;
    private final Runnable posRunnable = new Runnable() {
        @Override public void run() {
            if (player != null && prepared && player.isPlaying()) {
                try {
                    int pos = player.getCurrentPosition();
                    // v1.88: the old dur-10s exclusion refused a
                    // checkpoint in a talk's last seconds, while
                    // pause() has saved the exact spot there since
                    // v1.65 — a kill in the last seconds fell back
                    // to an older checkpoint instead of resuming
                    // near the spot. Save any >5s position; the
                    // onPrepared clamp (dur-2s, v1.66) and the
                    // completion clear already make a tail
                    // checkpoint safe to resume from.
                    if (pos > 5000 && currentUrl != null) {
                        getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                                .putInt("lastPos", pos).putString("lastPosUrl", currentUrl).apply();
                        saveTalkPos(currentUrl, pos); // v5.34
                    }
                } catch (Exception ignored) {}
            }
            updateSession();
            // v1.72: the plain card's elapsed text only moves when
            // the notification is rebuilt — refresh it on the same
            // 15s tick, but only while actually playing.
            if (player != null && prepared && player.isPlaying()) showNotification(true);
            // v1.78: a trackless service used to re-post this tick
            // forever — opening the Room starts the service, and
            // leaving again without ever playing a talk left the
            // 15s wakeup running until the process died. Stop the
            // tick once there is no track and no player; play()
            // re-arms it below, so a later talk still gets its
            // checkpoints and card refreshes.
            // v1.83: same waste for a PAUSED or FINISHED talk — the
            // old condition re-posted whenever any track existed,
            // so a paused talk kept waking every 15s forever for
            // nothing (position cannot move while paused, the card
            // only refreshes while playing, and the session was
            // already pushed on pause). Re-post only while the
            // track is actually playing or still buffering;
            // resume() re-arms below, so checkpoints resume with
            // playback instead of dying with the first pause.
            // v10.66: same waste for a HELD buffering talk — a
            // pause tapped while loading (or a call/unplug/sleep
            // timer during buffering) left isBuffering() true, so
            // the tick re-posted every 15s for a talk whose
            // position cannot move, on a hung network forever
            // (the body is a no-op while unprepared, like the
            // paused case v1.83 fixed). A held talk is paused,
            // not loading-to-play — every other surface already
            // says so (strips v10.61/v10.62, session v10.63,
            // card text v10.64, card bar v10.65) — so the tick
            // stops with the hold; onPrepared re-arms it when
            // the talk actually starts sounding (below), and
            // resume() already re-arms the open-paused path, so
            // no checkpoint is ever lost. An unheld buffering
            // talk still re-posts, exactly as before.
            if (player != null && (isPlaying() || (isBuffering() && !holdPaused))) posHandler.postDelayed(this, 15000);
        }
    };

    // v1.42: headphones/Bluetooth unplugged mid-talk pauses playback
    // instead of blasting the talk out of the phone speaker.
    private android.content.BroadcastReceiver noisyReceiver =
            new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context ctx, Intent intent) {
            if (android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                // v10.53: an unplug while the talk was still buffering used to
                // no-op — pause() only acts on a prepared, playing player, and
                // nothing held the incoming talk, so onPrepared started it
                // aloud through the phone speaker after the headset was gone
                // (the v1.69 class: a pause tapped while buffering holds, but
                // the noisy broadcast never did). Hold it like a buffering
                // pause tap: onPrepared opens paused, the countdown suspends
                // like pause() suspends it, and a later focus gain must not
                // restart it (pause()'s v1.73 rule) — the unplug was a stop.
                // An already-held talk stays held; a playing talk pauses.
                if (isBuffering() && !holdPaused) {
                    holdPaused = true;
                    // v11.77: snapshot the sleep pref like pause() does — a hold is a pause for
                    // the v7.26 rule (see toggle()'s hold branch); without this a talk held by
                    // an unplug while buffering loses its timer.
                    try { pausedSleepPrefMins = Prefs.sleepMinutes(AudioService.this); } catch (Exception ignored) {}
                    // v10.62: same strip gap as v10.61's toggle hold,
                    // via the unplug route — this branch bypasses
                    // pause(), whose entry clears livePlaying, so
                    // the strips kept showing ⏸ for a held talk
                    // until prepare finished. Card already right
                    // via its holdPaused-aware rule.
                    livePlaying = false;
                    resumeOnFocusGain = false;
                    suspendSleepIfRunning();
                    showNotification(false);
                    notifyState();
                    updateSession();
                // v10.67: a HELD buffering talk is already parked by the
                // branch above's sibling (v10.53) — pause() here would
                // TOGGLE it back to will-play (v1.77) and onPrepared
                // would start it aloud through the phone speaker, the
                // exact leak v10.53 closed for the unheld case. Skip
                // it; a prepared playing talk still pauses below.
                } else if (!(isBuffering() && holdPaused)) pause();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        liveSession = true; // v3.91: a session is loaded from here on
        liveHasTrack = false; // v10.40: fresh instance, no track yet
        posHandler.postDelayed(posRunnable, 15000);
        try {
            // v11.00: the five download-complete receivers already
            // register RECEIVER_NOT_EXPORTED on SDK 33+, but this
            // noisy receiver was the only one registered with no
            // flag at all — on API 33+ that registration can be
            // rejected, and the catch below used to swallow it, so
            // headphone-unplug pause (v1.42) went silently dead
            // with no error anywhere. NOT_EXPORTED cannot block a
            // system broadcast, it only keeps other apps from
            // sending to it, so the same SDK-guarded pattern as
            // the download receivers is safe here.
            android.content.IntentFilter nf = new android.content.IntentFilter(
                    android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(noisyReceiver, nf, android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(noisyReceiver, nf);
            }
        } catch (Exception ignored) {}
        // v12.10: the deferred-advance download receiver (see dlAwaitedReceiver) — same
        // SDK-guarded pattern as the noisy receiver above.
        try {
            android.content.IntentFilter df = new android.content.IntentFilter(
                    android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(dlAwaitedReceiver, df, android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(dlAwaitedReceiver, df);
            }
        } catch (Exception ignored) {}
        // v12.62 (Finding 4): the dlMap-change listener that
        // invalidates the cached in-progress query — see the
        // field note above dlAwaitedReceiver. Same try/catch
        // shape as the receivers above; a failed registration
        // only costs the 10s TTL backstop.
        try {
            getSharedPreferences("dhamma", MODE_PRIVATE)
                    .registerOnSharedPreferenceChangeListener(dlMapListener62);
        } catch (Exception ignored) {}
    }

    public class LocalBinder extends Binder {
        public AudioService getService() { return AudioService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // v1.75: a START_STICKY restart arrives with a null intent
        // after the system killed the service — with no track in
        // memory there is nothing to resume, so a trackless restart
        // used to idle forever (15s tick, no notification, no
        // track). End it; a restart that still holds a track keeps
        // running as before.
        if (intent == null) {
            if (currentTitle == null) stopSelf();
            // v1.76: a system restart with a track in memory used to
            // come back with NO foreground notification at all — the
            // restarted service never re-showed its card, so it ran
            // notification-less until the next play/pause tap. Re-post
            // the card at once (paused text unless really playing).
            else showNotification(isPlaying());
            return START_STICKY;
        }
        if (intent != null) {
            if (ACTION_TOGGLE.equals(intent.getAction())) {
                // v1.69: route through toggle() — it already retries a
                // failed track (v1.60/v1.62) and now also holds a pause
                // tapped while the stream is still buffering, instead
                // of the old branch that silently dropped that tap.
                // v1.78: but a toggle arriving with no track at all
                // (a stale card after the session ended) used to
                // no-op and leave the service started with NO
                // foreground notification — a started service that
                // never calls startForeground risks the system's
                // foreground-service timeout. Nothing to toggle =
                // end the service instead.
                if (currentTitle == null) stopSelf();
                else toggle();
            } else if (DhammaWidgetProvider.ACTION_WIDGET_NEXT.equals(intent.getAction())) {
                // v12.67: widget Next — the in-service manual
                // advance, no UI opens: the library advance for a
                // library session, the Room advance (hand queue >
                // forward stack > shuffle > list-next with wrap,
                // autoplay-off still advances, repeat-one ignored)
                // for a Room session. A trackless tap ends the
                // service like a stale toggle; a non-Room,
                // non-library track advances nothing. The advance
                // itself re-renders the widget through play() /
                // notifyState; the refresh below covers the no-op
                // cases (icon stays honest).
                if (currentTitle == null) stopSelf();
                else if (isLibSession()) advanceLibInBackground79(true);
                else if (isRoomTrack()) advanceInBackground58(true);
                DhammaWidgetProvider.refresh(this);
            } else if (DhammaWidgetProvider.ACTION_WIDGET_PLAY.equals(intent.getAction())) {
                // v12.67: widget Play with a dead service resumes
                // the stored last talk through the service (no UI
                // opens). A live track toggles, like ACTION_TOGGLE.
                // Nothing ever played ends the service. The local
                // file plays when downloaded (same form rule as
                // the advance paths); a data-saver-gated resume
                // starts paused through play()'s own gate, and the
                // Room poll asks the gate question on return.
                // showNotification(false) promotes to foreground
                // at once: prepareAsync may take seconds on a slow
                // stream, and a foreground-service start must show
                // its card inside the deadline.
                if (currentTitle != null) toggle();
                else {
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    // v12.71: a library session resumes its own
                    // file, not the stale Room talk in lastUrl /
                    // lastTitle (a library session never writes
                    // those) — skip-dead-files discipline v11.66:
                    // a deleted file falls through to the Room
                    // anchors below, never to a dead play.
                    boolean resumedLib = false;
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        String libPath = sp.getString("lastLibPath", "");
                        String libTitle = sp.getString("lastLibTitle", "");
                        if (libPath.length() > 0 && libTitle.length() > 0
                                && new java.io.File(libPath).isFile()) {
                            play(libPath, libTitle, savedSeekFor(libPath));
                            showNotification(false);
                            resumedLib = true;
                        }
                    }
                    if (!resumedLib) {
                        String wUrl = sp.getString("lastUrl", "");
                        String wTitle = sp.getString("lastTitle", "");
                        if (wUrl.length() == 0 || wTitle.length() == 0) stopSelf();
                        else {
                            java.io.File wOff = offlineFileFor(wUrl);
                            play(wOff != null ? wOff.getAbsolutePath() : wUrl,
                                    wTitle, savedSeekFor(wUrl));
                            showNotification(false);
                        }
                    }
                }
                DhammaWidgetProvider.refresh(this);
            } else if (ACTION_PLAY_FILE.equals(intent.getAction())) {
                // v12.82 (sim1298): play one downloaded file by
                // path — History's route for a path-form recent
                // row, which no Room can play (its load completion
                // matches stream URLs only). Same shape as the
                // widget-play library resume above: the caller
                // wrote the library anchors, the file plays from
                // its saved spot, the card posts at once. A missing
                // file ends a trackless service like a stale
                // toggle; a live track is switched by play()
                // (which flushes the outgoing talk's stats bank
                // first, v12.82). A local file passes the gate in
                // onPrepared untouched, like every library play.
                String pUrl = intent.getStringExtra("url");
                String pTitle = intent.getStringExtra("title");
                if (pUrl == null || pUrl.length() == 0
                        || !new java.io.File(pUrl).isFile()) {
                    if (currentTitle == null) stopSelf();
                } else {
                    play(pUrl, pTitle == null ? "" : pTitle,
                            savedSeekFor(pUrl));
                    showNotification(false);
                }
            } else if (ACTION_STOP.equals(intent.getAction())) {
                stopPlayback();
                stopSelf();
            } else if (ACTION_SEEK.equals(intent.getAction())) {
                // v4.73: a trackless service started by this intent
                // alone ends at once instead of idling (the v1.78
                // toggle rule); a live track seeks via seekTo, so
                // buffering/failed-track behaviour matches the Room
                // bar. Clamp to the known duration when there is
                // one; an unknown (unprepared) duration passes
                // through, since seekTo already queues it.
                if (currentTitle == null) stopSelf();
                else {
                    int ms = intent.getIntExtra("seekMs", -1);
                    if (ms >= 0) {
                        int d = getDuration();
                        if (d > 0 && ms > d) ms = d;
                        seekTo(ms);
                    }
                }
            } else if (ACTION_FORWARD.equals(intent.getAction())) {
                // v5.23: notification Forward — +30s from the live
                // position, clamped at the known duration, through
                // seekTo (which rebuilds the card with the new time
                // at once and saves a >5s checkpoint, like a Room
                // Forward tap). Trackless = stopSelf, the v1.78 rule.
                if (currentTitle == null) stopSelf();
                else {
                    // v11.77: a failed track reads position 0 (getEffectivePosition), so Forward
                    // retried from 0:30 and the checkpoints then overwrote the real resume spot
                    // as it replayed — base it on the saved spot when failed, like the retry
                    // itself does.
                    int base = trackError ? savedSeekFor(currentUrl) : getEffectivePosition();
                    int target = base + 30000;
                    int d = getDuration();
                    if (d > 0 && target > d) target = d;
                    seekTo(target);
                }
            } else if (ACTION_FAV.equals(intent.getAction())) {
                // v5.42: notification favourite toggle — trackless
                // = stopSelf, the v1.78 rule. The guard is the card
                // title-star guard verbatim (currentUrl == lastUrl,
                // or the last talk's downloaded file): a library
                // session, or a stale card from a previous talk,
                // only refreshes the card instead of toggling a
                // talk the card is not showing. Fx.toggleFavTalk
                // copies the set before mutating (v4.75), and the
                // refresh flips the title star + action icon at
                // once; the Room's 500ms poll and the strips' 1s
                // tick pick the same prefs change up on their own.
                if (currentTitle == null) stopSelf();
                else {
                    android.content.SharedPreferences sp2 =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    String favLastUrl = sp2.getString("lastUrl", "");
                    boolean favOk = currentUrl == null || currentUrl.equals(favLastUrl);
                    if (!favOk && favLastUrl.length() > 0) {
                        java.io.File ff = offlineFileFor(favLastUrl);
                        favOk = ff != null && ff.getAbsolutePath().equals(currentUrl);
                    }
                    if (favOk && favLastUrl.length() > 0)
                        Fx.toggleFavTalk(this, favLastUrl);
                    refreshNotification();
                }
            } else if (ACTION_REFRESH_NOTIFICATION.equals(intent.getAction())) {
                // v10.03: see the constant note — trackless ends
                // at once (v1.78 rule); a live track rebuilds
                // its card with the fresh favTalks set. The
                // caller guards on the card's talk, so a flip
                // for any other talk never reaches here, and
                // refreshNotification itself is a no-op with
                // no track (currentTitle null guard inside).
                if (currentTitle == null) stopSelf();
                else refreshNotification();
            } else if (ACTION_SLEEP.equals(intent.getAction())) {
                // v4.80: see ACTION_SLEEP above — a trackless
                // service started by this intent alone ends at
                // once instead of idling (the v1.78 toggle rule).
                if (currentTitle == null) stopSelf();
                else { syncSettingsFromPrefs(); refreshNotification(); }
            } else if (ACTION_SLEEP_END.equals(intent.getAction())) {
                // v4.82: arm the one-shot from a strip / Settings
                // picker (no bind, like ACTION_SLEEP above). A
                // trackless service started by this intent alone
                // ends at once instead of idling (see v1.78).
                if (currentTitle == null) stopSelf();
                else { armSleepEndOfTalk(); refreshNotification(); }
            } else if (ACTION_SLEEP_END_CANCEL.equals(intent.getAction())) {
                // v4.82: explicit cancel gestures (strip long-press,
                // Settings sleep long-press) clear the one-shot even
                // when the minute pref never changed, so the pref
                // guard in syncSettingsFromPrefs cannot see them.
                if (currentTitle == null) stopSelf();
                else { cancelSleepEndOfTalk(); refreshNotification(); }
            } else if (ACTION_SLEEP_PICK.equals(intent.getAction())) {
                // v8.56: an explicit picker minute choice (see the
                // constant note) — not a routine sync, so it applies
                // directly: setSleepMinutes clears an armed one-shot
                // unconditionally, and 0 cancels outright. A paused
                // pick suspends instead of arming wall-clock (v10.48):
                // resume() arms the picked length from the resume,
                // so a long pause can never silently eat the pick.
                // Trackless = stopSelf, the v1.78 rule.
                if (currentTitle == null) stopSelf();
                else {
                    int mins = intent.getIntExtra("mins", -1);
                    if (mins >= 0) { setSleepMinutes(mins); refreshNotification(); }
                }
            } else if (ACTION_SPEED.equals(intent.getAction())) {
                // v4.91: see ACTION_SPEED above — a trackless
                // service started by this intent alone ends at once
                // instead of idling (the v1.78 toggle rule); a live
                // track takes the new speed at once via setSpeed,
                // which re-applies on prepare if still buffering.
                // The strip already saved the same value as the
                // default, so the next talk starts at it too.
                if (currentTitle == null) stopSelf();
                else {
                    float s = intent.getFloatExtra("speed", -1f);
                    if (s > 0) setSpeed(s);
                }
            } else if (ACTION_AUTO_PAUSE.equals(intent.getAction())) {
                // v1.81: the delayed check only fires when no screen
                // is on top, but re-check the setting here anyway —
                // it may have been turned back on in the meantime.
                // A trackless service started by this intent alone
                // ends at once instead of idling (see v1.75).
                if (currentTitle == null) stopSelf();
                // v10.67: a held buffering talk must not be toggled
                // back to will-play here — it already opens paused,
                // and un-holding it would play it in the background
                // despite background-off (e.g. strip-hold from Main,
                // then Home). Skip; unheld/playing talks pause as before.
                // v12.06: the intent was sent when no screen was on
                // top, but the user may have returned in the window
                // before this ran — a stale intent must not pause a
                // talk the user is back to. Re-check foreground here.
                else if (!Prefs.backgroundEnabled(this)
                        && !AppForeground.isForeground()
                        && (isPlaying() || isBuffering()) && !holdPaused) pause();
            }
        }
        return START_STICKY;
    }

    public void setListener(Listener l) { listener = l; }

    public void play(String url, String title) {
        play(url, title, 0);
    }

    // v1.28: request focus before playing; a failed request never blocks
    // playback, it only means another app may keep playing over us.
    private void requestFocus() {
        try {
            if (audioManager == null)
                audioManager = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager == null) return;
            if (focusListener == null) {
                focusListener = new android.media.AudioManager.OnAudioFocusChangeListener() {
                    @Override public void onAudioFocusChange(int change) {
                        if (change == android.media.AudioManager.AUDIOFOCUS_LOSS) {
                            resumeOnFocusGain = false;
                            ducked = false;
                            // v10.67: a held buffering talk is already
                            // parked — pause() would toggle it to
                            // will-play over the focus owner. Skip.
                            if (!(isBuffering() && holdPaused)) pause();
                        } else if (change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            // v1.73: the pause below now clears
                            // resumeOnFocusGain (a manual/sleep/noisy
                            // pause must never be overridden by a later
                            // focus gain), so capture the flag first
                            // and restore it after — only a real
                            // transient loss earns an auto-resume.
                            // v1.79: a call arriving while the stream
                            // is still buffering used to earn NO
                            // auto-resume at all — isPlaying() is
                            // false before prepare, so the flag was
                            // captured false and the talk sat held
                            // (v1.74 hold) after the call ended,
                            // instead of resuming like a call during
                            // playback. Buffering counts as playing
                            // here; resume() un-holds it below.
                            // v10.56: but a buffering talk the user
                            // ALREADY held (pause tap before the
                            // call) is not about to play — counting
                            // it as playing let the call's end
                            // auto-resume a talk the listener had
                            // deliberately parked, the mirror of
                            // the v1.79 bug. A held talk counts only
                            // when a resume is already pending
                            // (resumeOnFocusGain true) — that hold
                            // came from an earlier transient loss
                            // itself, so a second transient during
                            // the same call must not burn the
                            // pending resume. Prepared talks are
                            // unchanged: isPlaying() is already
                            // false for a user-paused one.
                            boolean wasPlaying = isPlaying()
                                    || (isBuffering() && (!holdPaused || resumeOnFocusGain));
                            // v10.67: a user-held buffering talk (held
                            // and no resume pending) must not be toggled
                            // to will-play by the call — it would sound
                            // during/after the call despite the park.
                            // The resume-pending hold keeps its toggle.
                            if (!(isBuffering() && holdPaused && !resumeOnFocusGain)) pause();
                            resumeOnFocusGain = wasPlaying;
                        } else if (change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                            // v1.56: ducking used to pause the talk outright,
                            // like a transient loss — a notification sound
                            // stopped a whole talk. Now it only dips the
                            // volume; the talk keeps playing underneath.
                            // v1.90: a duck arriving while the stream is
                            // still buffering used to be lost entirely —
                            // isPlaying() is false before prepare, so
                            // ducked stayed false and onPrepared then
                            // forced full volume over the other audio.
                            // Remember the dip for buffering talks too
                            // (setVolume only applies once prepared;
                            // onPrepared reads ducked below).
                            if (player != null && (isPlaying() || isBuffering())) {
                                ducked = true;
                                if (isPlaying()) {
                                    try { player.setVolume(0.2f, 0.2f); } catch (Exception ignored) {}
                                }
                            }
                        } else if (change == android.media.AudioManager.AUDIOFOCUS_GAIN) {
                            if (ducked) {
                                ducked = false;
                                try { if (player != null) player.setVolume(1.0f, 1.0f); } catch (Exception ignored) {}
                            }
                            if (resumeOnFocusGain) {
                                resumeOnFocusGain = false;
                                // v12.06: a call that ended while the
                                // user is on the Home screen must not
                                // resume a talk into the background
                                // when background playback is off — the
                                // AUTO_PAUSE handler no-ops on an
                                // already-paused talk, so this flag
                                // stayed armed through the leave. Resume
                                // only when the app is on top or
                                // background playback is allowed.
                                if (AppForeground.isForeground()
                                        || Prefs.backgroundEnabled(AudioService.this)) resume();
                            }
                        }
                    }
                };
            }
            audioManager.requestAudioFocus(focusListener,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN);
        } catch (Exception ignored) {}
    }

    private void abandonFocus() {
        try {
            if (audioManager != null && focusListener != null)
                audioManager.abandonAudioFocus(focusListener);
        } catch (Exception ignored) {}
        resumeOnFocusGain = false;
        ducked = false;
    }

    public void play(String url, String title, int seekMs) {
        // v12.20: save the outgoing talk's exact spot before
        // switching — the 15s checkpoint was the only while-
        // playing save, so a manual Next / headset Next /
        // background advance left the skipped talk up to 15s
        // stale (or from 0 inside its first 15s). Guarded on
        // a prepared, non-errored, non-ended player: an ended
        // talk's spot was already cleared by onCompletion and
        // must never be re-saved, and a failed/buffering talk
        // has no live position to keep (its spot already sits
        // in prefs from the seek that started it). Same >5s
        // rule and same write pair as pause() below.
        if (currentUrl != null && player != null && prepared
                && !trackEnded && !liveEnded && !trackError) {
            try {
                int outPos = player.getCurrentPosition();
                if (outPos > 5000) {
                    getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                            .putInt("lastPos", outPos).putString("lastPosUrl", currentUrl).apply();
                    saveTalkPos(currentUrl, outPos); // v5.34
                }
            } catch (Exception ignored) {}
        }
        // v12.82 (sim1298): flush the outgoing talk's stats bank
        // BEFORE the switch below overwrites currentUrl /
        // currentTitle — the missing twin of the v12.20 save above.
        // Without it up to 59 sounded seconds of the old talk were
        // banked under the NEW talk at the next flush (the 1s tick
        // only reads the identity at flush time). A same-URL replay
        // / retry banks under the same identity either way, and a
        // post-completion advance already flushed in onCompletion,
        // so the flush is a safe no-op (pending == 0) there.
        flushListenStats();
        // v11.46: a fresh play of a DIFFERENT talk ends the old
        // talk's mobile-data approval (a replay/retry of the same
        // URL keeps it); a fresh play also supersedes any held
        // resume — the new talk is playing, nothing is held.
        if (gateApprovedUrl != null && !gateApprovedUrl.equals(url)) gateApprovedUrl = null;
        gateHeldResumeUrl = null;
        // v12.10: a fresh play supersedes the deferred advance — the listener moved on, so
        // the awaited talk must never auto-play later (the re-fire clears it first, so its
        // own play() call here is a harmless no-op).
        awaitAdvanceClear(this);
        // v4.04: a new talk cancels a sleep fade in progress — the
        // fade belongs to the old talk, never the new one.
        cancelSleepFade();
        // v4.13: same for the UI fades — a new talk owns its own
        // fade-in, and a pause-fade left running would pause the
        // new talk from under it (its guard reads isPlaying()).
        cancelFadeIn(false);
        cancelPauseFade();
        // v8.38: a fired one-shot must not ride into the next talk
        // (see the field note) — but only when the previous talk
        // really ended with it armed. All advance paths check the
        // flag before calling play(), so reaching here with
        // trackEnded + fired can only be a manual pick after the
        // stop, and consuming here is exactly "once". Checked
        // before trackEnded is reset below.
        if (trackEnded && sleepEndFired && sleepEndOfTalk) consumeSleepEndOfTalk();
        livePlaying = true; // v4.16: new talk (buffering counts as playing)
        // v10.69: a new talk used to leave the strip mirrors on the
        // PREVIOUS talk until prepare finished — play() never called
        // updateSession, and the 1s livePos tick only re-posts while
        // prepared-playing, so every strip (Main/Talks/library/
        // Settings) showed the new title over the old talk's clock
        // (" · mm:ss / mm:ss") and bar fraction through the whole
        // buffering window, against Fx's "never a stale fraction"
        // rule; worse, a strip scrub in that window computed its
        // target against the old duration and captured the old
        // liveUrl as its drag identity. Reset the mirrors here,
        // at the single point every new talk passes through:
        // duration unknown until prepare (strips read empty, never
        // stale), position the pending seek (the listener's real
        // spot, mirroring getEffectivePosition), identity the new
        // URL. onPrepared's updateSession fills the real values
        // the moment the stream prepares, as before; a stop still
        // clears them (v10.43), and a prepared talk is untouched
        // (this runs before prepareAsync, never after).
        liveUrl = url;
        liveTitle = title; // v12.67: widget mirror, set with the URL above
        livePositionMs = seekMs;
        liveDurationMs = 0;
        // v1.76: re-assert the started state — stopAndClear (below)
        // now stops the service so it cannot idle forever after its
        // track is deleted, and a later talk must survive the screen
        // being left again, which only a started service does.
        try { startService(new Intent(this, AudioService.class)); } catch (Exception ignored) {}
        // v1.78: re-arm the 15s tick (see posRunnable) — a trackless
        // idle stopped it, and this talk needs its checkpoints and
        // card refreshes back.
        posHandler.removeCallbacks(posRunnable);
        posHandler.postDelayed(posRunnable, 15000);
        currentUrl = url;
        currentTitle = title;
        liveHasTrack = true; // v10.40: mirror for the no-bind strips
        pendingSeekMs = seekMs;
        trackEnded = false;
        liveEnded = false;
        trackError = false;
        holdPaused = false;
        // v10.87: a pending focus-gain auto-resume belongs to the OLD
        // talk, not this one — a transient loss (call) during talk A
        // left resumeOnFocusGain true, and picking talk B never
        // cleared it (play() reset holdPaused/ducked/sleep, never
        // this flag), so a later focus gain could auto-resume a
        // talk the listener had deliberately parked (see the
        // toggle-hold fix below). A new talk starts with no
        // pending resume; a loss during THIS talk sets it again.
        resumeOnFocusGain = false;
        // v1.80: a new talk starts the sleep story over — a remainder
        // suspended from a paused earlier talk must not re-arm under
        // this one (the fresh timer starts in onPrepared below).
        // v12.17: same for its strip mark when the setting is now off — the suspension
        // is dropped here, and onPrepared's kill branches only fire on a live
        // countdown/suspension, so without this the mark outlived the timer into the
        // new talk (the retime twin of this fix covers the resume path). Guarded on
        // a real suspension (a one-shot never coexists with one) and on no running
        // countdown (a live timer's mark stays until onPrepared re-arms it); a live
        // setting keeps the mark through the buffering window, so it never flickers.
        if (sleepSuspendedMs > 0 && sleepEndMs == 0 && Prefs.sleepMinutes(this) == 0)
            liveSleepActive = false;
        sleepSuspendedMs = 0;
        // v11.77: same for a pending bedtime stop — every play that reaches here is a manual
        // pick or an advance the advance functions already cleared past the flag, so a new
        // talk is a new listen and its own timer starts in onPrepared below.
        sleepStopPending = false;
        // v1.56: a new talk starts at full volume — a duck dip from the
        // previous talk must not carry over into the next one.
        ducked = false;
        // v1.38: the Talks player (which used to apply the speed choice)
        // is gone since v1.31 and the Room has no speed button since
        // v1.37, so the settings default speed was silently dead — every
        // talk played at 1x. Apply the saved default here instead.
        speed = Prefs.defaultSpeed(this);
        prepared = false;
        bufferedPercent = 0; // v4.06: a new talk starts unbuffered
        // v4.18: a downloaded talk played from its local file is
        // fully available at once — but MediaPlayer never fires
        // a buffering update for a local file, so the Room wave
        // kept its "nothing buffered yet" look for the whole
        // offline talk (the v4.06 tint's stream-only gap, see the
        // WaveSeekView note). A local path reads as 100% buffered
        // from the first frame; a stream URL still starts at 0
        // and fills from the listener below, and stop still
        // clears it (v4.06).
        if (url != null && new java.io.File(url).isFile()) bufferedPercent = 100;
        requestFocus();
        try {
            if (player != null) { try { player.reset(); } catch (Exception ignored) {} }
            else player = new MediaPlayer();
            // v1.71: hold a partial wake lock while a talk plays — the
            // foreground service alone does not keep the CPU awake, so
            // with the screen off a long talk could stop mid-stream
            // when the phone dozed. Released with the player (below).
            player.setWakeMode(this, android.os.PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(url);
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer mp) {
                    prepared = true;
                    // v1.69: a pause tapped while buffering (see
                    // holdPaused) means this talk opens paused, not
                    // playing — and a paused talk never gains a sleep
                    // timer (v1.58 rule), so the timer start below is
                    // gated on it too. Seeking and speed still apply.
                    boolean startPaused = holdPaused;
                    holdPaused = false;
                    // v12.09: the network may have flipped Wi-Fi -> mobile while this talk
                    // buffered — the Room gate passed on Wi-Fi, but starting now would stream
                    // on mobile data with no question asked (play() itself never checks the
                    // gate). Re-check the same gate resume() enforces: if it now applies, hold
                    // the talk paused as a resume-gate instead of starting it, so the Room poll
                    // asks on return. Treated exactly like a held-pause open from here (no
                    // start, no sleep timer, seek/speed still apply); a gate approval, a local
                    // file, or a still-Wi-Fi network passes through untouched.
                    if (!startPaused && refuseGatedResume()) startPaused = true;
                    // v10.61: the strips poll livePlaying, and
                    // nothing on this path ever set it — a
                    // toggle-held talk opened paused but every
                    // strip kept showing ⏸ (and its tap semantics
                    // inverted: ⏸ tapped resumed) until some
                    // later pause/resume/stop cleared the flag.
                    // The open-paused/open-playing split is the
                    // single point that covers every hold origin.
                    livePlaying = !startPaused;
                    if (!startPaused) mp.start();
                    // v10.66: re-arm the 15s checkpoint tick for a
                    // talk that actually starts sounding — the
                    // tick stops with a buffering hold (see
                    // posRunnable), and the toggle un-hold path
                    // never re-armed it, so without this a talk
                    // un-held while still loading would play with
                    // no checkpoints and no card elapsed refresh.
                    // onPrepared is the single point where sounding
                    // starts, covering every hold origin; a held
                    // talk opening paused takes no tick (paused
                    // talks never checkpoint, v1.83), and resume()
                    // already re-arms that path. Remove-then-post
                    // keeps the normal unheld path to one tick.
                    if (!startPaused) {
                        posHandler.removeCallbacks(posRunnable);
                        posHandler.postDelayed(posRunnable, 15000);
                    }
                    // v1.56: guarantee full volume for the new talk even
                    // if the reused player kept a duck dip from before.
                    // v1.90: unless a duck arrived while this talk was
                    // still buffering (see the focus listener) — then
                    // the dip is still owed, and forcing full volume
                    // here used to undo it the moment the talk started.
                    // v4.13: fade a sounding talk in from silence
                    // instead of starting at full volume (a held-pause
                    // talk opens paused, so it takes its volume
                    // straight — it makes no sound until resume, which
                    // already sets full volume). A duck that arrived
                    // while buffering still owns the target: the ramp
                    // climbs to the dip, not past it.
                    if (startPaused) {
                        try { mp.setVolume(ducked ? 0.2f : 1.0f, ducked ? 0.2f : 1.0f); } catch (Exception ignored) {}
                    } else {
                        startFadeIn(ducked ? 0.2f : 1.0f);
                    }
                    if (pendingSeekMs > 0) {
                        try {
                            // v1.66: the old guard dropped any saved
                            // position within 5s of the end outright —
                            // so a talk paused in its last seconds
                            // (which v1.65 started saving exactly)
                            // silently restarted from 0:00 on reopen.
                            // Clamp to 2s before the end instead, so
                            // the listener lands where they stopped.
                            int dur = mp.getDuration();
                            int target = pendingSeekMs;
                            if (dur > 2000 && target > dur - 2000) target = dur - 2000;
                            if (target > 0) mp.seekTo(target);
                        } catch (Exception ignored) {}
                        pendingSeekMs = 0;
                    }
                    applySpeed();
                    // v1.48: sleep timer restored — the Settings default
                    // starts when a talk starts, but only when no timer
                    // is already running, so changing talks mid-session
                    // never silently restarts (and extends) the countdown.
                    // v1.56: but when the setting was turned OFF mid-
                    // session, the old deadline used to keep running and
                    // still stop a later talk — off now really cancels it.
                    // v7.76: a pref changed WHILE this talk was still
                    // buffering used to be lost when a countdown was
                    // already running — every sync path (service,
                    // Room, library, Settings) early-returns while
                    // !isPlaying, and this block only armed a timer
                    // when none ran, so the old deadline fired
                    // instead of the new minutes. Restart only when
                    // the pref differs from the running timer's own
                    // minutes (the same rule the playing syncs use),
                    // so an unchanged pref still never restarts a
                    // mid-session countdown.
                    int mins = Prefs.sleepMinutes(AudioService.this);
                    if (mins > 0) {
                        // v1.69: a held-pause talk (see above) never
                        // gains a timer — only a talk that really starts.
                        // v4.82: a one-shot armed with this same minute
                        // pref outranks the default for this talk; a
                        // pref changed since arming falls through to
                        // setSleepMinutes, which clears the flag.
                        if (!startPaused
                                && !(sleepEndOfTalk && mins == sleepEndArmedPref)
                                && (sleepEndMs == 0 || mins != sleepSetMins))
                            setSleepMinutes(mins);
                        // v11.77: a held-pause talk never gains a wall-clock timer above — but it
                        // must not lose the setting either. Store the pref as a SUSPENDED countdown
                        // (the same state pause() produces, v10.48), so resume() re-arms it from
                        // the resume instead of playing on with no timer and no sleep mark. Only
                        // when nothing runs or hangs suspended already (a running countdown keeps
                        // its own deadline, v1.48); a one-shot armed with this same minute pref
                        // still outranks the default, and a changed minute pref supersedes it
                        // (mirroring setSleepMinutes).
                        else if (startPaused && sleepEndMs == 0 && sleepSuspendedMs == 0
                                && !(sleepEndOfTalk && mins == sleepEndArmedPref)) {
                            if (sleepEndOfTalk) {
                                sleepEndOfTalk = false;
                                sleepEndFired = false;
                                liveSleepEndOfTalk = false;
                            }
                            sleepSetMins = mins;
                            sleepSuspendedMs = mins * 60L * 1000L;
                            liveSleepActive = true;
                            syncLiveSleepRemaining();
                        }
                    } else if (sleepEndMs != 0) {
                        if (sleepRunnable != null) sleepHandler.removeCallbacks(sleepRunnable);
                        sleepRunnable = null;
                        sleepEndMs = 0;
                        liveSleepActive = false;
                        syncLiveSleepRemaining(); // v4.72
                    } else if (sleepSuspendedMs != 0) {
                        // v4.25: a turned-off setting must not leave a
                        // pause-suspended remainder behind for a later
                        // resume to resurrect — the setting is off,
                        // so the pending countdown dies with the talk
                        // that held it, and the strip mark goes too.
                        sleepSuspendedMs = 0;
                        liveSleepActive = false;
                        syncLiveSleepRemaining(); // v4.72
                    }
                    showNotification(!startPaused);
                    notifyState();
                    // v1.73: push the session state at once — the
                    // old code only refreshed it on the 15s tick, so
                    // headset/system controls showed the stale state.
                    updateSession();
                }
            });
            // v4.06: feed the Room wave's buffered-ahead tint (see
            // field note); percent is % of duration, 0-100.
            player.setOnBufferingUpdateListener(new MediaPlayer.OnBufferingUpdateListener() {
                @Override public void onBufferingUpdate(MediaPlayer mp, int percent) {
                    bufferedPercent = Math.max(0, Math.min(100, percent));
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                    trackError = true;
                    livePlaying = false; // v4.16

                    // v1.40: the Room never sets a listener, so a failed
                    // stream used to stall silently with no message at
                    // all — say so out loud from the service itself.
                    if (listener != null) listener.onError(Lang.t(AudioService.this, "play_failed_internet"));
                    try {
                        android.widget.Toast.makeText(AudioService.this,
                                Lang.t(AudioService.this, "play_failed_internet"), android.widget.Toast.LENGTH_LONG).show();
                    } catch (Exception ignored) {}
                    prepared = false;
                    showNotification(false);
                    notifyState();
                    updateSession();
                    return true;
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) {
                    // v12.66: bank the ended talk's seconds BEFORE
                    // anything below advances — the advance overwrites
                    // currentTitle/currentUrl with the next talk.
                    flushListenStats();
                    trackEnded = true;
                    // v8.38: record that the one-shot fired here, if
                    // armed — the owning poll consumes it with its
                    // toast, but a foreign end or a background
                    // refusal never does, so play() needs this to
                    // tell a fired flag from a fresh arm.
                    if (sleepEndOfTalk) sleepEndFired = true;
                    liveEnded = true;
                    livePlaying = false; // v4.16 (a later play() re-arms it)

                    // Finished talks resume from the start, not from the tail.
                    // v1.89: guarded (see clearSavedPosForCurrent) — the
                    // old unconditional clear also wiped ANOTHER talk's
                    // saved spot when this one ended without ever
                    // checkpointing (a short talk ending inside its
                    // first 15s, before any >5s checkpoint ran).
                    clearSavedPosForCurrent();
                    // v10.52: a finished talk's OWN per-talk spot must
                    // die on natural completion even when the global
                    // spot names another talk. The v1.89 guard inside
                    // clearSavedPosForCurrent protects the GLOBAL spot
                    // (never clear another talk's resume), so when a
                    // talk ends within its first 15s of a resumed
                    // session — no checkpoint, no pause, global still
                    // naming the previous talk — the guard spares the
                    // global spot AND the matched-branch clearTalkPos
                    // never runs, leaving this finished talk's
                    // per-talk spot alive; Room's savedPosFor then
                    // resurrects it on the next manual tap (v10.51
                    // fixed only the repeat-one replay path). The
                    // per-talk entries are keyed by this talk's own
                    // URL/forms only, so clearing them cannot touch
                    // another talk's spot — the global guard above
                    // stays exactly as it is. Matched case: no-op
                    // duplicate of the branch above.
                    clearTalkPos(currentUrl);
                    markPlayedForCurrent();
                    showNotification(false);
                    notifyState();
                    if (listener != null) listener.onTrackEnded();
                    advanceInBackground58(false);
                    advanceLibInBackground79(false);
                }
            });
            player.prepareAsync();
            showNotification(false);
            notifyState();
        } catch (Exception e) {
            trackError = true;
            livePlaying = false; // v4.16

            if (listener != null) listener.onError(Lang.t(AudioService.this, "play_failed_prefix") + e.getMessage());
            // v11.01: the v1.40 onError path toasts directly because no
            // screen ever sets a service listener (verified: no
            // setListener call in any activity) — but this
            // setDataSource-failure catch only notified that listener,
            // so a play that failed before preparing (e.g. a file
            // deleted between the exists-check and play) died with no
            // message at all: strips showed paused, retry silently
            // failed again. Toast the same generic line onError uses
            // (no raw exception text for older listeners).
            try {
                android.widget.Toast.makeText(AudioService.this,
                        Lang.t(AudioService.this, "play_failed_internet"), android.widget.Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {}
            // v1.60: a setDataSource failure used to leave the previous
            // notification showing the old talk as playing, with no
            // state update at all — refresh both, like onError does.
            showNotification(false);
            notifyState();
            updateSession();
        }
    }

    // v1.67: where the listener stopped in this track, so a retry
    // resumes there instead of restarting — the Room's own retry got
    // this in v1.66, but the notification toggle and the library's
    // row tap both retry through toggle() below and restarted from 0.
    // The position savers (15s checkpoints + pause) only ever write
    // this track's own URL (stream URL or offline path), so equality
    // is the whole match; anything else restarts from the beginning.
    private int savedSeekFor(String url) {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String savedUrl = sp.getString("lastPosUrl", "");
            // v2.03: cross-form match, like the Room's savedPosFor
            // (v1.42/v1.66) — a talk paused while streaming saves
            // its stream URL, but a failed offline retry arrives
            // here with the file path (and vice versa), so the old
            // exact match restarted it from 0 instead of the saved
            // spot. The offlineFileFor map/candidates already know
            // both forms; anything else still restarts from 0.
            boolean match = url != null && url.equals(savedUrl);
            if (!match && url != null && savedUrl.length() > 0) {
                java.io.File f = offlineFileFor(savedUrl);
                match = f != null && f.getAbsolutePath().equals(url);
                if (!match) {
                    java.io.File g = offlineFileFor(url);
                    match = g != null && savedUrl.equals(g.getAbsolutePath());
                }
            }
            if (match) {
                int saved = sp.getInt("lastPos", 0);
                return saved > 0 ? saved : 0;
            }
            // v5.35: per-talk fallback — the global spot above
            // only ever covered the most recent talk, so retrying
            // a failed track that is NOT the latest (notification
            // toggle, library row tap) restarted it from 0 even
            // though v5.34's map holds its spot. Same >5s rule
            // as the Room's savedPosFor; the map already holds
            // both forms, so no dlMap lookup is needed here.
            int per = Prefs.talkPosGet(this, url);
            if (per > 5000) return per;
            // v5.36: single-form spot — same gap as the Room's
            // savedPosFor: a talk half-heard as a library file
            // with no dlMap entry holds its spot under the path
            // form only, so the lookup above misses. offlineFileFor
            // is already used above for the global match (same
            // dlMap/candidate checks, no new cost), so try the
            // downloaded-file form too before restarting at 0.
            java.io.File off = offlineFileFor(url);
            if (off != null) {
                int per2 = Prefs.talkPosGet(this, off.getAbsolutePath());
                if (per2 > 5000) return per2;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    // v5.34: save one talk's spot under both its forms (Prefs owns
    // the form rule), same >5s rule as every lastPos write — a
    // barely-started talk never earns a resume spot. Called beside
    // each lastPos write, never instead of it: the global spot
    // still drives the strips.
    private void saveTalkPos(String url, int pos) {
        if (url == null || pos <= 5000) return;
        Prefs.talkPosPutBoth(this, url, pos);
    }

    // v5.34: drop one talk's spot under both its forms — the mirror
    // of saveTalkPos, called wherever a guarded clear drops this
    // track's global spot (pause/seek near the start, stop,
    // natural completion via clearSavedPosForCurrent).
    private void clearTalkPos(String url) {
        if (url == null) return;
        Prefs.talkPosRemoveBoth(this, url);
    }

    public void toggle() {
        // v1.62: central retry — the downloaded library's row tap calls
        // toggle() directly, so a failed track there went dead (Room
        // and the notification got their own retries in v1.59/v1.60,
        // the library never did). Retry the failed track here instead
        // of no-op'ing on an unprepared player.
        // v1.67: from the saved position, like the Room's retry (v1.66).
        if (trackError && currentUrl != null && currentTitle != null) {
            // v11.77: a failed-track retry re-streams — gate it like resume() does, or a
            // Wi-Fi -> mobile-data switch lets the retry stream a whole talk unasked.
            if (refuseGatedResume()) return;
            play(currentUrl, currentTitle, savedSeekFor(currentUrl));
            return;
        }
        if (player == null || currentTitle == null) return;
        // v1.69: a pause tapped while the stream is still buffering
        // used to vanish (the old !prepared return) and the talk then
        // started playing anyway. Hold it — onPrepared opens paused.
        // v1.77: but the hold is a toggle, not a latch — the old code
        // set holdPaused=true on EVERY pre-prepare tap, so Play/Pause
        // tapped twice while buffering (Room button, row tap or the
        // notification) still opened the talk paused: the second tap,
        // the play intent, could never un-hold. Flip it instead, and
        // re-request focus on the un-hold (a manual resume re-requests
        // focus since v1.71; a buffering talk that lost focus
        // permanently would otherwise play focus-less over other audio).
        if (!prepared) {
            if (holdPaused) {
                // v11.77: an un-hold starts the stream — gate it like resume() does, or a
                // pause-tap on Wi-Fi followed by a mobile-data switch lets the held talk
                // start streaming unasked. A refusal leaves the hold in place; the Room
                // poll asks the gate question from the held flag.
                if (refuseGatedResume()) return;
                holdPaused = false;
                // v10.61: an un-hold is a will-play — the strips
                // (Main/Talks/Settings/Library) poll livePlaying on
                // their 1s tick, and a hold placed via pause() left
                // it false, so a held talk un-held from a strip
                // kept showing ▶ until prepare finished even
                // though it was about to sound. Mirror resume()'s
                // entry (livePlaying=true) and play()'s "buffering
                // counts as playing" rule.
                livePlaying = true;
                requestFocus();
                // v1.90: the un-hold is a resume — a countdown
                // suspended by the buffering pause below must come
                // back, or the timer is lost with the first hold.
                rearmSuspendedSleep();
            } else {
                holdPaused = true;
                // v11.77: snapshot the sleep pref like pause() does — a hold is a pause for the
                // v7.26 rule, so resume() can tell a pref changed during the hold from one that
                // merely survived it; without this a talk held while buffering loses its timer.
                try { pausedSleepPrefMins = Prefs.sleepMinutes(this); } catch (Exception ignored) {}
                // v10.87: a deliberate hold cancels any pending
                // focus-gain auto-resume, exactly like pause()'s
                // v1.73 rule — without this, a talk parked with
                // the Room/notification/strip toggle while still
                // buffering (after a call had armed the flag, or
                // after a double-toggle re-held the same talk)
                // auto-played when the call ended, undoing the
                // park. pause()'s buffering branch already
                // cleared it; this branch was the outlier.
                resumeOnFocusGain = false;
                // v10.61: a hold is a pause — the strips poll
                // livePlaying, and this branch never cleared it,
                // so a talk held paused from a strip toggle, the
                // notification toggle, the Room button or a row
                // tap kept showing ⏸ on every strip (while the
                // card already showed paused via its own
                // holdPaused-aware rule and the Room listener got
                // isPlaying()=false). Mirror pause()'s entry,
                // which sets livePlaying=false before its own
                // buffering-hold branch.
                livePlaying = false;
                // v1.90: a hold is a pause — suspend the countdown
                // like pause() does for a playing talk, instead of
                // letting it run out (and silently die, or fire
                // mid-buffer) while the talk is held.
                suspendSleepIfRunning();
            }
            showNotification(false);
            notifyState();
            return;
        }
        // v4.13: UI taps pause softly (fade-out, see helpers above);
        // a re-tap mid-fade cancels it and resumes at once instead
        // of letting the fade's tail pause a talk the listener
        // just un-paused. System callers never reach toggle().
        if (pauseFading) {
            cancelPauseFade();
            try { if (player != null) player.setVolume(ducked ? 0.2f : 1.0f, ducked ? 0.2f : 1.0f); } catch (Exception ignored) {}
            resume();
        } else if (player.isPlaying()) startPauseFade(); else resume();
    }

    public void pause() {
        // v12.66: bank the seconds listened since the last 60s
        // flush — a pause mid-minute still counts.
        flushListenStats();
        livePlaying = false; // v4.16 (covers the buffering-hold early return too)
        // v7.26: snapshot the sleep pref at pause time (see field) —
        // every pause path funnels through here (manual, call/unplug,
        // leave-guard, buffering hold, sleep-fade end), so resume()
        // can tell a pref changed while paused from one that merely
        // survived the pause. Read defensively: a pref read must
        // never break a pause.
        try { pausedSleepPrefMins = Prefs.sleepMinutes(this); } catch (Exception ignored) {}
        // v4.04: a manual pause during a sleep fade cancels the fade
        // and restores volume first — the fade's own final step
        // already cleared the flag, so this is a no-op for it.
        cancelSleepFade();
        // v4.13: same for a fade-in cut short by a fast pause tap —
        // restore the listening volume so a later resume never
        // opens at the half-ramped level (resume() only sets volume
        // when clearing a duck).
        cancelFadeIn(true);
        // v1.74: a pause tapped (or forced by a call, unplug, or
        // leaving the screen) while the stream is still buffering
        // used to do nothing at all — the guard below required a
        // prepared, playing player — so the talk then started
        // playing anyway once prepare finished, over the call or
        // out loud after the headphones came out. Hold it like
        // toggle() already does for a buffering tap (v1.69): the
        // talk opens paused instead.
        if (player != null && !prepared && currentTitle != null) {
            holdPaused = true;
            resumeOnFocusGain = false;
            // v1.90: this branch used to return before the sleep-
            // suspend block below, so a call/unplug/manual pause
            // during buffering left the countdown running in real
            // time — it then fired (and vanished silently) before
            // the talk ever started. Suspend it like a playing
            // pause does; resume()'s un-hold re-arms it.
            suspendSleepIfRunning();
            showNotification(false);
            notifyState();
            updateSession();
            return;
        }
        if (player != null && prepared && player.isPlaying()) {
            try {
                int pos = player.getCurrentPosition();                // v1.65: pausing inside the last 10 seconds used to
                // save nothing (pos < dur - 10000 guard), so reopening
                // the talk fell back to the last 15s checkpoint —
                // minutes back on a long talk. Save the exact spot;
                // natural completion still clears it to 0 above.
                if (pos > 5000 && currentUrl != null) {
                    getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                            .putInt("lastPos", pos).putString("lastPosUrl", currentUrl).apply();
                    saveTalkPos(currentUrl, pos); // v5.34
                } else if (currentUrl != null) {
                    // v1.72: pausing within the first 5 seconds used
                    // to leave an older, much later saved position
                    // for this talk untouched — reopening it from
                    // Mp3 Player then jumped back to that old spot
                    // instead of starting near the beginning. A
                    // barely-started talk resumes from the start;
                    // only clear when the saved position really is
                    // this track's, never another track's.
                    // v8.32: cross-form clear — the old exact match
                    // missed a spot saved under this talk's OTHER
                    // form (paused at 30min streaming, then paused
                    // at 3s offline, or vice versa), so the old
                    // minute survived and reopening jumped back to
                    // it instead of starting near the beginning.
                    // clearSavedPosForCurrent already matches exact
                    // + offlineFileFor both ways + dlMap-only (v8.31)
                    // and still never clears another talk's spot.
                    clearSavedPosForCurrent();
                    // v10.55: same own-spot rule as natural completion
                    // (v10.52) — when the global spot names another
                    // talk the guard above spares it AND the per-talk
                    // spot, so a later tap resurrected the old
                    // minute through savedPosFor's per-talk fallback
                    // even though this pause deliberately parked the
                    // talk near the start. clearTalkPos touches only
                    // this talk's own URL/forms, so another talk's
                    // global resume stays exactly as guarded.
                    clearTalkPos(currentUrl);
                }
            } catch (Exception ignored) {}
            // v1.73: a real pause cancels any pending focus-gain
            // auto-resume — the old flag survived a manual pause, so
            // a call ending later restarted a talk the listener had
            // deliberately stopped (the transient-loss path above
            // re-sets the flag after this call, so it still works).
            resumeOnFocusGain = false;
            // v1.80: suspend the sleep countdown instead of letting
            // it die — the old code left the deadline running, so a
            // talk paused past its deadline lost the timer entirely
            // on resume (v1.55's silent clear). A firing timer sets
            // sleepEndMs to 0 first, so it never suspends itself.
            if (sleepEndMs != 0) {
                long rem = sleepEndMs - android.os.SystemClock.elapsedRealtime();
                if (rem > 0) sleepSuspendedMs = rem;
                if (sleepRunnable != null) sleepHandler.removeCallbacks(sleepRunnable);
                sleepRunnable = null;
                sleepEndMs = 0;
                // sleepSetMins stays: the suspended countdown still
                // belongs to that setting, so the Room's live sync
                // does not mistake it for "no timer" and restart it
                // fresh on return (only a changed setting restarts).
            }
            player.pause();
            showNotification(false);
            notifyState();
            updateSession();
        }
    }

    // v11.46: would resuming the held talk cost mobile data?
    // Only a stream URL (a local path is an offline file, never
    // gated — offlineFileFor on a path can even read null when
    // its basename is claimed by another talk's dlMap entry, so
    // the scheme check, not the file check, is what keeps
    // library sessions ungated), with data saver on, off Wi-Fi,
    // no downloaded file, and no session approval for this URL.
    // Fail-closed like the other isWifi gates: any exception
    // reads as gated, never as free.
    private boolean needsResumeGate() {
        try {
            if (currentUrl == null || currentTitle == null) return false;
            if (gateApprovedUrl != null && gateApprovedUrl.equals(currentUrl)) return false;
            if (!currentUrl.startsWith("http://") && !currentUrl.startsWith("https://")) return false;
            return Prefs.dataSaver(this) && !isWifi58() && offlineFileFor(currentUrl) == null;
        } catch (Exception ignored) { return true; }
    }

    // v11.46: the Room poll's handle on a refused resume — the
    // held URL, or null when no resume is gate-held. A held
    // resume stays held (and re-askable) until the listener
    // answers it, starts another talk, or the session ends.
    public boolean isResumeGateHeld() { return gateHeldResumeUrl != null; }
    public String getResumeGateHeldUrl() { return gateHeldResumeUrl; }
    public void clearResumeGateHeld() { gateHeldResumeUrl = null; }

    // v11.46: the resume-mode gate answer "stream" — approve this
    // talk for the session and resume it. The approval is what
    // keeps later pauses in the same listen from re-asking.
    public void resumeBypassGate() {
        gateApprovedUrl = currentUrl;
        gateHeldResumeUrl = null;
        resume();
    }

    // v11.46: the fresh-play gate answer "stream" — approve this
    // talk for the session, so its later pauses resume without
    // re-asking. Called by the Room after a bypassed play starts;
    // a held resume (if any) for the same talk is superseded.
    public void noteGateApproved(String url) {
        gateApprovedUrl = url;
        if (url != null && url.equals(gateHeldResumeUrl)) gateHeldResumeUrl = null;
    }

    // v11.77: shared refusal for a resume that would stream on mobile data — the gate
    // resume() has enforced since v11.46, extracted so every (re)start-streaming path uses
    // it, not just resume(): toggle()'s failed-track retry and buffering un-hold and
    // seekTo()'s failed-track retry all re-streamed without ever asking, while resume()
    // alone claimed "EVERY resume funnels here". True = refused (caller must return);
    // false = free to play. A refused resume stays exactly as resume() left it: paused,
    // no focus request, no checkpoint tick, no sleep re-arm, no volume change, and the held
    // flag is the Room poll's cue to ask the resume-mode question on return.
    private boolean refuseGatedResume() {
        if (!needsResumeGate()) return false;
        gateHeldResumeUrl = currentUrl;
        showNotification(false);
        notifyState();
        updateSession();
        return true;
    }

    public void resume() {
        // v11.46: shared resume gate — EVERY resume funnels here
        // (Room toggle, strip, notification onPlay, media session,
        // headset play, focus-gain auto-resume, long-press
        // restart), so this one check closes the hole on all of
        // them at once instead of patching one surface and
        // leaving the others open. A refused resume stays paused:
        // no focus request, no checkpoint tick, no sleep re-arm,
        // no volume change — the held flag is the Room poll's cue
        // to ask the resume-mode question on return, and answering
        // "stream" resumes through resumeBypassGate above. A
        // track-error retry and a buffering un-hold pass through
        // the same gate (both would stream), and a Wi-Fi return
        // or a downloaded file clears the condition on its own,
        // so the next resume just plays.
        // v11.77: two guards before the gate — a trackless resume must not set livePlaying
        // (strips would show playing with no track), and an already-playing talk must not
        // take the refusal path (the audio keeps sounding while the card flips to paused
        // and the Room asks the gate question for a talk already playing). The pause-fade
        // re-tap reaches resume() while still playing, so the gate is skipped, not removed,
        // for that path.
        if (currentTitle == null) return;
        if (!isPlaying() && refuseGatedResume()) return;
        // v12.09: a hold that survived into a successful resume is stale — the gate
        // passed (Wi-Fi returned, the talk downloaded, or data saver went off), so the
        // Room poll must not re-ask the question on the next pause for a talk that now
        // plays freely. The hold names this same currentUrl (only refuseGatedResume
        // ever sets it, and play()/stopAndClear() clear it), so clearing here can never
        // drop another talk's hold; the poll-side re-check in RoomActivity covers the
        // download-landed-while-paused case without a resume tap.
        gateHeldResumeUrl = null;
        livePlaying = true; // v4.16
        // v1.71: re-request focus — a manual resume after a call (or
        // after a permanent focus loss) used to play without focus, so
        // another app's audio could keep playing over the talk.
        requestFocus();
        // v1.83: re-arm the 15s checkpoint tick — it now stops when
        // playback pauses (see posRunnable), so a resumed talk must
        // restart it or its checkpoints and card refreshes would
        // never come back after the first pause. Harmless when
        // there is no track: the tick fires once and stops itself.
        posHandler.removeCallbacks(posRunnable);
        posHandler.postDelayed(posRunnable, 15000);
        // v1.82: a headset / media Play press on a FAILED track used
        // to die silently — resume() only handled prepared players
        // and buffering holds, while toggle() has retried failed
        // tracks from the saved position since v1.67. Retry here too
        // (the session's onPlay reaches resume(), never toggle()).
        if (trackError && currentUrl != null && currentTitle != null) {
            play(currentUrl, currentTitle, savedSeekFor(currentUrl));
            return;
        }
        // v1.79: a resume arriving while the stream is still
        // buffering and held (call during buffering, v1.74 hold, or
        // a headset play tap) used to no-op on the unprepared
        // player AND burn the focus-gain flag, so the talk then
        // opened paused and never started. Un-hold instead — the
        // talk starts on prepare, like a manual un-hold tap.
        if (player != null && !prepared && holdPaused && currentTitle != null) {
            holdPaused = false;
            // v1.90: the un-hold is a resume — re-arm a countdown
            // suspended by a buffering pause, like the prepared
            // resume path below does (off cancels, a changed
            // setting restarts full-length).
            rearmSuspendedSleep();
            showNotification(false);
            notifyState();
            updateSession();
            return;
        }
        if (player != null && prepared && !player.isPlaying()) {
            // v1.55: restarting after natural completion is a replay,
            // not another end — clear the flag or the next poll would
            // advance again immediately.
            // v10.89: capture it first — the ended-replay arm below
            // needs to tell a replay from a plain pause-resume.
            boolean wasEnded = trackEnded;
            trackEnded = false;
            liveEnded = false;
            // v11.23: a fired one-shot must not ride into a replay —
            // play() consumes it before a manual pick after the stop
            // (v8.38), but this resume() replay entry never did, so
            // a talk that ended away from Room/library (no poll to
            // consume it) kept the fired flag through a strip /
            // notification / headset replay and stopped THAT end
            // too, blocking autoplay / repeat-one on a deliberately
            // restarted listen. Consume here, before any sleep
            // branch, so the v10.89 ended-replay arm below (guarded
            // on !sleepEndOfTalk) can arm the minute default from
            // the pref exactly like a Room replay via play() +
            // onPrepared. An arm made after the end (fired false)
            // is untouched, like play(): it belongs to the
            // replay's own end. A plain pause-resume is untouched.
            if (wasEnded && sleepEndFired && sleepEndOfTalk) consumeSleepEndOfTalk();
            // v1.74: a talk paused while ducked (a notification
            // sound dipped it to 20%, then the listener paused
            // before focus returned) used to resume at 20% volume
            // forever — pause() never cleared the duck, resume()
            // never restored it, and only the next focus gain or
            // the next talk fixed it. Resume at full volume.
            if (ducked) {
                ducked = false;
                try { player.setVolume(1.0f, 1.0f); } catch (Exception ignored) {}
            }
            player.start();
            applySpeed();
            // v4.17: a resumed talk ramps up softly over ~700ms
            // instead of jumping to full volume — the new-talk
            // fade-in (v4.13) made fresh starts gentle while every
            // resume still clicked in at full. Same startFadeIn
            // machinery, duck already cleared above so the target
            // is full volume; skipped while a sleep fade owns the
            // volume, so the two ramps never fight, and a pause
            // inside the ramp still cancels it via pause()'s
            // cancelFadeIn(true) exactly like a cut-short fade-in.
            if (!sleepFading) startFadeIn(1.0f);
            // v1.81: a sleep change made in Settings while the talk
            // sat PAUSED used to be ignored — the Room/library sync
            // only runs while playing, so this re-armed the OLD
            // suspended remainder (a timer turned off kept firing;
            // a 30→60 change still stopped at 30). Retime the
            // suspension to the current setting first; off cancels
            // it, a new value restarts full-length on this resume.
            // This also covers notification-toggle resumes, which
            // never pass through any screen's sync.
            if (sleepSuspendedMs > 0) {
                int mins = Prefs.sleepMinutes(this);
                if (mins != sleepSetMins) retimeSuspendedSleep(mins);
            }
            // v1.80: re-arm a pause-suspended sleep countdown — the
            // talk resumes with the time it had left, not with the
            // timer silently gone (see pause()).
            if (sleepSuspendedMs > 0 && sleepEndMs == 0) {
                long rem = sleepSuspendedMs;
                sleepSuspendedMs = 0;
                armSleepCountdown(rem);
            }
            // v7.26: a timer set WHILE paused never had a remainder
            // to re-arm above — the strip picker and Settings only
            // wrote the pref, and their live syncs return early for
            // a paused talk, so resume() is the first playing moment
            // that can honour it. Arm it fresh here, but only when
            // the pref actually changed during the pause: an
            // unchanged pref means either no timer was ever wanted
            // (off at pause, still off) or the timer already fired
            // and its fade-end pause recorded the same pref (a
            // manual resume after bedtime must not restart a fresh
            // full timer unasked). The one-shot is never clobbered:
            // setSleepMinutes clears it, so it stays out of this
            // branch entirely, and a running countdown (sleepEndMs)
            // keeps its own deadline.
            if (sleepEndMs == 0 && sleepSuspendedMs == 0 && !sleepEndOfTalk
                    && pausedSleepPrefMins >= 0) {
                int minsNow = Prefs.sleepMinutes(this);
                if (minsNow > 0 && minsNow != pausedSleepPrefMins)
                    setSleepMinutes(minsNow);
            }
            // v10.89: a replay of a naturally ended talk is a new
            // listen, not a pause-resume — but the v7.26 branch above
            // only arms when the pref CHANGED during a pause, and a
            // natural end is not a pause (pausedSleepPrefMins is stale
            // from an earlier pause, or -1 when the listener never
            // paused). Worse, a minute countdown whose deadline passed
            // while the talk sat ended (autoplay off, or a refused
            // background advance) fired in the runnable's silent
            // neither-playing-nor-buffering branch and vanished, so
            // a replay through a strip / notification / headset
            // toggle — which reaches resume(), never play() — played
            // on past bedtime with no timer and no sleep mark, while
            // a replay through the Room's play button re-armed fresh
            // via play() + onPrepared. Arm from the pref here, only
            // for an ended replay with no timer running, suspended or
            // one-shot pending; a plain pause-resume (wasEnded false)
            // is untouched, and an already-armed timer above no-ops
            // through the sleepEndMs guard. The Room/library advance
            // paths still go through play(), so mid-session deadline
            // continuity (v1.48) is unchanged.
            if (wasEnded && sleepEndMs == 0 && sleepSuspendedMs == 0 && !sleepEndOfTalk) {
                int minsNow = Prefs.sleepMinutes(this);
                if (minsNow > 0) setSleepMinutes(minsNow);
                // v11.84: a replay is a new listen — clear a pending bedtime stop even when the
                // sleep pref is now 0 (setSleepMinutes only clears it when mins > 0, so a replay
                // with the timer turned off used to stop again, silently, at its end).
                sleepStopPending = false;
            }
            syncLiveSleepRemaining(); // v4.72
            showNotification(true);
            notifyState();
            updateSession();
        }
    }

    public boolean isPlaying() {
        return player != null && prepared && player.isPlaying();
    }

    // v1.75: a stream still preparing — not playing yet, but about
    // to start. The Room/library leave-guards only asked isPlaying(),
    // so leaving mid-buffer with background playback off never
    // paused anything and the talk then started aloud in the
    // background. A failed stream (trackError) is not buffering.
    public boolean isBuffering() {
        return player != null && !prepared && currentTitle != null && !trackError;
    }

    // v1.79: is the buffering talk held paused (a pause tapped while
    // it loaded, or a call during buffering)? The Room and the
    // notification decide their Play/Pause label from this — a
    // buffering talk that WILL play reads ⏸, a held one reads ▶.
    public boolean isHoldPaused() { return holdPaused; }

    // v4.08: duck state for the Room wave — a transient-duck event
    // (e.g. a notification sound) dips the volume to 20% while the
    // talk keeps playing, but nothing visible ever said so: the
    // wave danced at full ripple, the flames burned full and the
    // button glowed exactly like normal playback. The Room poll
    // reads this on its 500ms tick (same change-only pattern as
    // isBuffering) and calms the wave while ducked; pause() and
    // focus gain already clear the flag, so a stale duck can
    // never strand the calm. The downloaded library never reads
    // it, so its rows are untouched.
    public boolean isDucked() { return ducked; }

    // v4.09: sleep-fade state for the Room wave — the v4.04 fade
    // lowers the volume to silence over ~3s, but nothing visible
    // ever said so: the wave danced at full ripple until the
    // fade-end pause froze it. The Room poll reads this on its
    // 500ms tick (same change-only pattern as isBuffering and
    // isDucked) and dies the wave down with the fade; the fade's
    // own end (pause) and any manual pause already clear the
    // flag, so a stale fade can never strand the calm. The
    // downloaded library never reads it, so its rows are untouched.
    public boolean isSleepFading() { return sleepFading; }

    // v11.77: is a bedtime stop pending (see the sleepStopPending field note)? The Room and
    // library end blocks read this on their poll tick, so a talk that ended mid-fade stops
    // instead of advancing.
    public boolean isSleepStopPending() { return sleepStopPending; }

    // v4.10: failed-stream state for the Room wave — a stream that
    // fails (onError / prepare failure) sets trackError, and the
    // Room poll then reads active=false, so setPlaying(false)
    // froze the wave mid-ripple exactly like a pause: only the
    // toast/status text said it had failed, and the bar itself
    // read as a paused talk. The poll reads this on its 500ms
    // tick (same change-only pattern as isBuffering/isDucked/
    // isSleepFading) and flattens the wave instead of freezing
    // it, so a dead stream reads as dead, never as paused. A
    // new play and an explicit stop already clear trackError
    // (v1.67), so a stale error can never strand the flat;
    // retry ramps the wave back up from 0 via the v3.15 ramp.
    // The downloaded library never reads it, so its rows are
    // untouched.
    public boolean isTrackError() { return trackError; }

    public String getCurrentTitle() { return currentTitle; }
    public String getCurrentUrl() { return currentUrl; }
    public boolean hasTrack() { return currentTitle != null; }
    public int getPosition() {
        if (player != null && prepared) { try { return player.getCurrentPosition(); } catch (Exception ignored) {} }
        return 0;
    }
    // v10.57: effective position while a talk is still buffering —
    // getPosition() reads 0 before prepare, so a resumed talk still
    // loading looked "at the start" to every position decision:
    // Previous jumped to the previous talk instead of restarting
    // this one, and notification Forward landed at 0:30 instead of
    // resume+30s. While buffering, the pending seek IS the
    // listener's position (the resume spot play() stored, or a drag
    // seekTo queued); prepared talks read the live position exactly
    // as before, and a failed/trackless talk reads 0. Display sites
    // (poll, clocks) keep getPosition() — only decisions use this.
    public int getEffectivePosition() {
        if (player != null && prepared) { try { return player.getCurrentPosition(); } catch (Exception ignored) {} }
        if (isBuffering()) return pendingSeekMs;
        return 0;
    }
    // v10.57: restart a buffering talk from 0, preserving hold state
    // — seekTo(0) while unprepared is deliberately a no-op (v1.69:
    // a 0 drag on a fresh bar must not wipe a resume spot), so a
    // Previous restart during buffering silently kept the resume
    // spot and the talk opened where it left off instead of from
    // the start. This explicit restart clears the pending seek and
    // this talk's saved spots (the prepared seekTo(0) rules) without
    // touching holdPaused, so a held buffering talk still opens
    // paused. Prepared talks take the plain seekTo(0) path.
    public void restartFromStart() {
        if (player != null && !prepared && currentUrl != null) {
            pendingSeekMs = 0;
            // v12.60: same un-end clear as seekTo() below — a restart of an ended talk
            // is a new listen, so a stale bedtime stop must not govern the replay's end.
            // (A buffering talk has not completed, so trackEnded is false here in
            // practice; the guard makes the invariant explicit at both un-end points.)
            if (trackEnded) sleepStopPending = false;
            trackEnded = false;
            liveEnded = false;
            clearSavedPosForCurrent();
            clearTalkPos(currentUrl);
            return;
        }
        seekTo(0);
    }
    // v4.06: buffered-ahead percent for the Room wave; 0 for
    // downloaded (non-http) playback so its wave is untouched.
    public int getBufferedPercent() {
        if (currentUrl == null || !currentUrl.startsWith("http")) return 0;
        return bufferedPercent;
    }
    public int getDuration() {
        if (player != null && prepared) { try { return player.getDuration(); } catch (Exception ignored) {} }
        return 0;
    }
    public void seekTo(int ms) {
        // v1.85: seeking a FAILED track used to queue a pending seek
        // that could never apply — the player is in its error state
        // and onPrepared will never fire for it, so dragging the Room
        // seek bar (or a headset scrub) on a dead stream did nothing
        // at all. Retry the same talk from the dragged position
        // instead, like toggle()/resume() already retry failed tracks.
        if (trackError && currentUrl != null && currentTitle != null) {
            // v11.77: a failed-track seek re-streams — gate it like resume() does, or a seek-bar
            // drag on a dead stream after a Wi-Fi -> mobile-data switch streams unasked.
            if (refuseGatedResume()) return;
            // v12.20: persist the dragged spot before the retry —
            // the old code retried from ms but never saved it, so
            // a second failure jumped back to the stale checkpoint
            // instead of the dragged minute. Same >5s rule as the
            // prepared/buffering branches below (a near-start drag
            // clears this talk's own spot, never another's).
            if (ms > 5000) {
                getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                        .putInt("lastPos", ms).putString("lastPosUrl", currentUrl).apply();
                saveTalkPos(currentUrl, ms); // v5.34
            } else {
                clearSavedPosForCurrent();
                clearTalkPos(currentUrl);
            }
            play(currentUrl, currentTitle, ms);
            return;
        }
        // v1.55: seeking away from the end un-ends the track — a seek
        // back from the completed position must not still count as ended.
        // v12.60: an explicit seek-restart of an ENDED talk is a new listen — clear a
        // stale bedtime stop with the un-end. The v11.84 clear lives in resume() guarded
        // on wasEnded, but every Room replay path seeks first (repeat-one end block,
        // row tap on an ended talk, long-press restart), so resume() captured
        // wasEnded=false and the flag survived: the replayed talk then stopped silently
        // at its own end (background advance + Room poll both refuse on the flag) and
        // repeat-one died on the second loop. A seek on a live talk never reaches here
        // with trackEnded true, so a mid-talk scrub can never eat a live bedtime stop.
        boolean seekUnended = trackEnded;
        trackEnded = false;
        liveEnded = false;
        if (seekUnended) sleepStopPending = false;
        // v1.69: a seek dragged before the new stream prepared used
        // to vanish (the old code only sought a prepared player), so
        // the seek bar snapped back to the start on prepare. Keep it
        // as the pending seek onPrepared already applies — except a
        // 0 drag, which on a freshly-reset (max 0) bar is meaningless
        // and must not wipe a resume position play() just stored.
        if (player == null || !prepared) {
            if (ms > 0) {
                pendingSeekMs = ms;
                // v1.76: a real seek while buffering is also worth
                // remembering (a >5s one only, never a tiny drag) —
                // otherwise a service restart after the seek landed
                // the listener back at the old checkpoint.
                if (ms > 5000 && currentUrl != null) {
                    getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                            .putInt("lastPos", ms).putString("lastPosUrl", currentUrl).apply();
                    saveTalkPos(currentUrl, ms); // v5.34
                } else if (ms <= 5000 && currentUrl != null) {
                    // v12.20: a buffering drag to <= 5s clears this
                    // talk's own spot, like the prepared branch below
                    // — the old code left the stale checkpoint, so an
                    // error before any checkpoint retried to the old
                    // minute instead of the dragged start. ms == 0
                    // never reaches here (the ms > 0 guard above
                    // keeps the v1.69 freshly-reset-bar rule intact).
                    clearSavedPosForCurrent();
                    clearTalkPos(currentUrl);
                }
                // v10.60: a buffering seek used to queue the new
                // position but never push the session — the tail
                // below (showNotification + updateSession) sits
                // past this early return, so car/headset controls
                // kept showing the OLD spot (the resume position)
                // after a Room drag, a headset scrub, notification
                // Forward or ACTION_SEEK, until prepare finished
                // and onPrepared pushed. v10.59 pushed only for
                // the 0-scrub, which bypasses seekTo entirely, so
                // every other buffering seek still went stale.
                // Push the session at once: it now reads the new
                // pending seek via getEffectivePosition(). The
                // card is deliberately NOT rebuilt — while
                // buffering it shows no elapsed time (duration
                // unknown), so a rebuild changes nothing visible.
                // Gated on isBuffering() (failed/trackless/player-
                // null seeks keep their old silent path).
                if (isBuffering()) updateSession();
            }
            return;
        }
        try { player.seekTo(ms); } catch (Exception ignored) {}
        // v1.76: a seek made while PAUSED used to vanish on re-entry —
        // nothing saved it (the 15s checkpoint only runs while
        // playing, and pause() only saves the pause spot), so leaving
        // and reopening jumped back to the old checkpoint instead of
        // the seeked spot. Persist it with pause()'s own rules: a
        // >5s seek saves, a near-start seek clears only this track's
        // saved spot, never another track's.
        if (currentUrl != null) {
            if (ms > 5000) {
                getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                        .putInt("lastPos", ms).putString("lastPosUrl", currentUrl).apply();
                saveTalkPos(currentUrl, ms); // v5.34
            } else {
                // v8.32: same cross-form clear as pause() above
                // — a near-start seek must drop this talk's spot
                // whichever form saved it, never another talk's.
                clearSavedPosForCurrent();
                // v10.55: own-spot rule as in completion (v10.52)
                // and pause above — a guarded global miss left the
                // per-talk spot alive, so the next tap jumped back
                // to the old minute instead of the seeked start.
                // Own URL/forms only; another talk untouched.
                clearTalkPos(currentUrl);
            }
        }
        // v1.76: a seek while paused also left the notification card
        // showing the OLD elapsed time until the next play/pause tap
        // (the card text is only rebuilt on play/pause and the 15s
        // playing tick) — rebuild it with the new position at once.
        showNotification(isPlaying());
        updateSession();
    }

    // v11.77: the Downloads/Dhamma shelf in the persisted sort order (Prefs.dlSortMode: 0
    // latest-download first, 1 A-Z, 2 largest first), shared by the background advance below
    // and the in-service manual Previous — one scan, one order, so a headset Previous walks
    // the same shelf the auto-advance does. Null when the folder cannot be listed.
    private java.util.ArrayList<java.io.File> shelfFiles79() {
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma");
            java.io.File[] files = dir.isDirectory() ? dir.listFiles() : null;
            if (files == null) return null;
            java.util.ArrayList<java.io.File> shelf = new java.util.ArrayList<java.io.File>();
            // v12.33: a talk still downloading is NOT on the shelf yet — DownloadManager writes
            // straight into Downloads/Dhamma, so the partial file used to sit here as if
            // finished, and the background advance (queue head / redo / shuffle / list-next)
            // and the headset Previous walk could all pick it while the library sat hidden:
            // a truncated fragment that "ends" early and can even earn a listened mark. The
            // foreground loadList (v11.55) and offlineFileFor (v11.59) already exclude
            // in-progress files, so this was the last shelf that did not. Same helper, same
            // fail-open rule (a DM query failure returns an empty set = the old behaviour).
            // A sounding file mid-re-download drops off the shelf too, so idx < 0 and the
            // advance returns with trackEnded still set — the library poll consumes that
            // silently on return, exactly like today's no-match end.
            java.util.Set<String> inProg = inProgressDlFilenames59();
            for (java.io.File f : files)
                if (f.isFile() && f.getName().toLowerCase().endsWith(".mp3")
                        && !inProg.contains(f.getName())) shelf.add(f);
            java.util.Collections.sort(shelf, new java.util.Comparator<java.io.File>() {
                @Override public int compare(java.io.File a, java.io.File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            final int sortMode = Prefs.dlSortMode(this);
            if (sortMode == 1) {
                java.util.Collections.sort(shelf, new java.util.Comparator<java.io.File>() {
                    @Override public int compare(java.io.File a, java.io.File b) {
                        return libTitleFor79(a).compareToIgnoreCase(libTitleFor79(b));
                    }
                });
            } else if (sortMode == 2) {
                java.util.Collections.sort(shelf, new java.util.Comparator<java.io.File>() {
                    @Override public int compare(java.io.File a, java.io.File b) {
                        int bySize = Long.compare(b.length(), a.length());
                        return bySize != 0 ? bySize
                                : libTitleFor79(a).compareToIgnoreCase(libTitleFor79(b));
                    }
                });
            }
            return shelf;
        } catch (Exception ignored) { return null; }
    }

    // v7.79: background auto-advance for LIBRARY sessions, the
    // mirror of advanceInBackground58 for Room sessions — a
    // downloaded file ending while the library sat hidden used to
    // stop dead until the listener returned to the shelf, whose
    // 1s poll then consumed the stale end flag (the exact Room
    // gap v7.58 closed for Room talks). The service now advances
    // itself, in the library foreground poll's own order
    // (sleep-end > repeat-one > autoplay[queue > shuffle >
    // list-next, repeat-off stops at the last file]) and with
    // its own anchors (saveLastLibrary keys, so Main's Mp3
    // Player nav and the notification tap follow the new file).
    // Everything else still defers to the library poll on return
    // by leaving trackEnded set, exactly as the Room deferred
    // cases do: the library on top (its poll owns the end —
    // toast, glide, row fade — so this never pre-empts it, the
    // v7.74 rule via DownloadedActivity.topLib), the sleep
    // one-shot (the library poll consumes it and toasts on
    // return), autoplay off, repeat-off at the last file, and
    // an ended file no longer on the shelf (the library poll
    // consumes that silently as a no-match end). The shelf is
    // the full Downloads/Dhamma list in the persisted sort
    // order (Prefs.dlSortMode: 0 latest-download first, 1 A-Z,
    // 2 largest first, titles derived exactly like
    // DownloadedActivity.Entry, stable sort from the same
    // lastModified-desc base) — search/favOnly filters are
    // view state, never playback truth, so a hidden library
    // advances in shelf order like Play All does. Two
    // deliberate divergences from the foreground poll, both
    // documented: (a) shuffle reads only the persisted strip
    // flag (Prefs.shuffleOn) — the Random button's dlShuffle is
    // session-only activity memory the service cannot see, so a
    // Random-mode session ending while hidden advances in
    // shelf order (a fresh library open would do the same, its
    // dlShuffle starting false); (b) a queue head equal to the
    // sounding file is skipped, never popped (the Room v5.85
    // rule — a service-restart play can leave it queued), so a
    // sounding-only queue falls through to the plain advance
    // instead of replaying the ended file announced as "next".
    // Advancing calls play(), which clears trackEnded, so the
    // library poll on return sees a playing file, rebinds on
    // the track change, and cannot double-advance.
    private void advanceLibInBackground79(boolean manual) {
        try {
            if (currentUrl == null || currentUrl.length() == 0) return;
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (!"lib".equals(sp.getString("lastKind", ""))) return;
            // v11.77: manual=true is a headset/car Next press, not a natural end — the Room/
            // library on-top defer, the sleep one-shot stop and the bedtime-stop guard below are
            // all auto-advance rules; a deliberate press overrides them (its own play() clears
            // the bedtime flag, and the one-shot still stops the NEXT natural end).
            if (!manual && DownloadedActivity.topLib) return;
            if (!manual && isSleepEndOfTalk()) return;
            if (!manual && (sleepFading || sleepStopPending)) return;
            java.util.ArrayList<java.io.File> shelf = shelfFiles79();
            if (shelf == null) return;
            int idx = -1;
            for (int i = 0; i < shelf.size(); i++)
                if (shelf.get(i).getAbsolutePath().equals(currentUrl)) { idx = i; break; }
            if (idx < 0) return;
            int mode = Prefs.repeatMode(this);
            // v11.66: skip-dead-files — the shelf was fresh at scan time, but a file
            // deleted between scan and play (cleanup app, split-screen Files) must not
            // be anchored or played: the old code anchored the dead pick first, then
            // play() died on the generic "check internet" toast with trackEnded already
            // cleared, so nothing ever advanced past it. A vanished repeat pick simply
            // falls through to the queue / redo / list logic below, unanchored.
            // v11.77: a manual Next ignores repeat-one, like the Room's manual Next
            // (consumeNextIndex) — repeat-one is an end-of-talk loop order, not a Next order.
            if (!manual && mode == 2 && shelf.get(idx).exists()) {
                java.io.File rep = shelf.get(idx);
                sp.edit().putString("lastKind", "lib")
                        .putString("lastLibPath", rep.getAbsolutePath())
                        .putString("lastLibTitle", libTitleFor79(rep)).apply();
                play(rep.getAbsolutePath(), libTitleFor79(rep));
                return;
            }
            // v11.77: a manual Next advances even with autoplay off — autoplay gates the
            // automatic end-of-talk advance, never a deliberate press.
            if (!manual && !Prefs.autoplay(this)) return;
            // Hand queue first, like the foreground poll: stale
            // heads (file gone from the shelf) drop silently and
            // the sounding file is skipped, never popped; the
            // first live non-sounding head is popped by index
            // and persisted with the stale drops in one write.
            // No live head (or a sounding-only queue) falls
            // through to the shuffle / list-next below.
            // v8.73: the prune is persisted in that case too —
            // the old code only wrote inside the head branch,
            // so stale/dup/empty lines computed here lingered
            // in prefs until a library open pruned them (the
            // v5.75 disk-differs rule, mirrored here). The
            // write drops only lines every reader already
            // ignores, keeps every live entry including the
            // sounding one, and changes no playback choice.
            String qRaw = Prefs.savedQueueLib(this);
            if (qRaw != null && qRaw.length() > 0) {
                String[] qLines = qRaw.split("\n");
                java.util.ArrayList<String> pruned = new java.util.ArrayList<String>();
                for (String ql : qLines) {
                    if (ql == null || ql.length() == 0) continue;
                    if (pruned.contains(ql)) continue;
                    boolean live = false;
                    for (java.io.File sf : shelf)
                        if (sf.getAbsolutePath().equals(ql)) { live = true; break; }
                    if (live) pruned.add(ql);
                }
                int headPos = -1;
                java.io.File headFile = null;
                for (int i = 0; i < pruned.size(); i++) {
                    if (pruned.get(i).equals(currentUrl)) continue;
                    for (java.io.File sf : shelf)
                        if (sf.getAbsolutePath().equals(pruned.get(i))) { headFile = sf; break; }
                    if (headFile != null) { headPos = i; break; }
                }
                // v11.66: skip-dead-files — a head that vanished between the shelf scan
                // and this play must not be anchored or played; fall through to the redo /
                // list logic instead of dying on the generic failure toast. The drop line
                // below removes it from pruned before the v8.73 persist writes the queue.
                if (headFile != null && headFile.exists()) {
                    pruned.remove(headPos);
                    Prefs.saveQueueLib(this, pruned);
                    sp.edit().putString("lastKind", "lib")
                            .putString("lastLibPath", headFile.getAbsolutePath())
                            .putString("lastLibTitle", libTitleFor79(headFile)).apply();
                    noteLibTrail11(currentUrl, headFile.getAbsolutePath());
                    // v11.19: a queued background advance is a branch — it
                    // invalidates the redo stack, exactly like the foreground
                    // poll's queue branch (v11.17) and the manual queue-head
                    // Next. Without this, the advance writes a fresh
                    // libTrailNext naming the head file, so the redo gate
                    // (trailNext == sounding file) trusts the stale stack
                    // and the next Next jumps back to a file left before
                    // the back-step instead of advancing from the head.
                    sp.edit().remove("libRedoTrail").apply();
                    // v12.20: resume the pick's saved spot (Room-
                    // advance parity) — the 2-arg play restarted a
                    // half-heard file at 0.
                    play(headFile.getAbsolutePath(), libTitleFor79(headFile),
                            savedSeekFor(headFile.getAbsolutePath()));
                    return;
                }
                // v11.66: a head that was in the shelf but vanished before the play above
                // is dropped here, so the v8.73 persist below writes a cleaned queue
                // instead of leaving the dead head to be re-picked on the next advance.
                if (headFile != null) pruned.remove(headPos);
                // v8.73: no live head — persist the prune anyway
                // (see the comment above): stale/dup/empty lines
                // self-heal on this advance instead of lingering
                // until a library open. Live entries, including
                // a sounding-only queue, survive untouched.
                StringBuilder prunedSb73 = new StringBuilder();
                for (String ql73 : pruned) {
                    if (prunedSb73.length() > 0) prunedSb73.append("\n");
                    prunedSb73.append(ql73);
                }
                if (!prunedSb73.toString().equals(qRaw)) Prefs.saveQueueLib(this, pruned);
            }
            // v11.19: background redo — the Room background advance
            // consults its forward stack (v8.27) and the foreground
            // library poll redoes (v11.17), but this background
            // advance skipped straight to shuffle/list-next, so a
            // file ending while the library sat hidden played a
            // different next file than the same end with the
            // library open, and the unconsumed redo stack then
            // hijacked a later manual Next (see the queue-branch
            // note above). A redo play records its own trail step
            // like every other advance here, so a later Previous
            // walks back to the back-stepped-to file again; only
            // the top is consumed, so chained ends keep walking
            // the stack. Repeat-one above deliberately keeps the
            // stack — the sounding file is unchanged, so the redo
            // target is still valid, exactly like the foreground
            // poll's repeat-one branch.
            long gen95 = clearGen95(sp);
            String redoNext19 = sp.getString("libTrailNext", "");
            if (redoNext19 != null && redoNext19.equals(currentUrl)) {
                String redoRaw19 = sp.getString("libRedoTrail", "");
                if (redoRaw19 != null && redoRaw19.length() > 0) {
                    String[] redoParts19 = redoRaw19.split("\n");
                    java.util.ArrayList<String> redoStack19 =
                            new java.util.ArrayList<String>();
                    for (String rp19 : redoParts19)
                        if (rp19 != null && rp19.length() > 0) redoStack19.add(rp19);
                    java.io.File redoFile19 = null;
                    boolean redoChanged19 = false;
                    // v11.26: peek before popping -- the old loop removed the
                    // candidate first and only then checked it against the
                    // sounding file, so a redo stack whose top live entry
                    // was the sounding file itself lost that blocker
                    // whenever stale (deleted-file) entries above it were
                    // pruned in the same pass (redoChanged write). The
                    // foreground popLibRedo17 keeps the sounding entry and
                    // returns null, so the end stops (repeat-off at the
                    // last file) instead of redoing; the background loop
                    // then fell through to the repeat-off return leaving
                    // the blocker dropped, and the library poll on return
                    // redo-played the older entry below it instead of
                    // stopping. Peek now: stale entries still prune, a
                    // sounding top stays in the stack and blocks, exactly
                    // like the foreground pop.
                    while (!redoStack19.isEmpty()) {
                        String cand19 = redoStack19.get(redoStack19.size() - 1);
                        java.io.File found19 = null;
                        for (java.io.File sf : shelf)
                            if (sf.getAbsolutePath().equals(cand19)) { found19 = sf; break; }
                        if (found19 == null) { redoStack19.remove(redoStack19.size() - 1); redoChanged19 = true; continue; }
                        if (cand19.equals(currentUrl)) break;
                        redoStack19.remove(redoStack19.size() - 1);
                        redoFile19 = found19;
                        break;
                    }
                    StringBuilder redoSb19 = new StringBuilder();
                    for (int ri19 = 0; ri19 < redoStack19.size(); ri19++) {
                        if (ri19 > 0) redoSb19.append("\n");
                        redoSb19.append(redoStack19.get(ri19));
                    }
                    // v11.66: skip-dead-files — same race guard as the queue head above: a
                    // redo pick that vanished between scan and play is dropped (its stack
                    // entry was already popped into redoSb19, so persisting here is the
                    // drop) and the advance falls through to shuffle / list-next unanchored.
                    // v11.95: a clear inside the read→play window
                    // (see clearGen95) — the pick comes from a
                    // pre-clear stack, so do not replay the erased
                    // talk and do not write the popped stack back;
                    // fall through to the shuffle / list-next pick
                    // below, the correct post-clear behaviour.
                    // The tail write below carries its own fresh
                    // check, so a clear landing between the two
                    // checks still cannot resurrect the stack.
                    if (redoFile19 != null && redoFile19.exists() && clearGen95(sp) == gen95) {
                        sp.edit().putString("libRedoTrail", redoSb19.toString()).apply();
                        sp.edit().putString("lastKind", "lib")
                                .putString("lastLibPath", redoFile19.getAbsolutePath())
                                .putString("lastLibTitle", libTitleFor79(redoFile19)).apply();
                        noteLibTrail11(currentUrl, redoFile19.getAbsolutePath());
                        // v12.20: resume the pick's saved spot (see
                        // the queue-head branch above).
                        play(redoFile19.getAbsolutePath(), libTitleFor79(redoFile19),
                                savedSeekFor(redoFile19.getAbsolutePath()));
                        return;
                    }
                    if ((redoChanged19 || redoFile19 != null) && clearGen95(sp) == gen95)
                        sp.edit().putString("libRedoTrail", redoSb19.toString()).apply();
                }
            }
            // v11.66: skip-dead-files for the final pick — walk the same candidate order
            // the old single pick used (ordered: forward with repeat-all wrap; shuffle:
            // random others, then a deterministic sweep so a live file is never missed
            // because random kept hitting dead picks) and take the first file that still
            // exists. If none does, return unanchored with trackEnded still true, so the
            // library poll on return handles the end with its own skip logic instead of
            // a dead session anchored at a deleted file. The old code anchored first and
            // played dead, dying on the generic "check internet" toast.
            java.io.File next = null;
            if (Prefs.shuffleOn(this)) {
                if (shelf.size() <= 1) {
                    if (shelf.get(idx).exists()) next = shelf.get(idx);
                } else {
                    for (int t = 0; t < shelf.size() * 2 && next == null; t++) {
                        int cand = (int) (Math.random() * shelf.size());
                        if (cand != idx && shelf.get(cand).exists()) next = shelf.get(cand);
                    }
                    if (next == null) {
                        for (int ci = 0; ci < shelf.size(); ci++)
                            if (ci != idx && shelf.get(ci).exists()) { next = shelf.get(ci); break; }
                    }
                }
            } else {
                int nx = idx + 1;
                for (int t = 0; t < shelf.size(); t++) {
                    // v11.77: a manual Next wraps at the last file, like the Room's manual Next —
                    // repeat-off stops an automatic loop, never a deliberate press.
                    if (nx >= shelf.size()) { if (manual || mode == 1) nx = 0; else break; }
                    if (shelf.get(nx).exists()) { next = shelf.get(nx); break; }
                    nx++;
                }
            }
            if (next == null) return;
            sp.edit().putString("lastKind", "lib")
                    .putString("lastLibPath", next.getAbsolutePath())
                    .putString("lastLibTitle", libTitleFor79(next)).apply();
            noteLibTrail11(currentUrl, next.getAbsolutePath());
            // v11.19: same redo invalidation as the queue branch
            // above — a list/shuffle advance the gate above declined
            // (or that followed a failed redo gate) is a branch, so
            // the stale stack must not survive to hijack the next
            // manual Next, exactly like the foreground poll's
            // list branch (v11.17).
            sp.edit().remove("libRedoTrail").apply();
            // v12.20: resume the pick's saved spot (see the
            // queue-head branch above).
            play(next.getAbsolutePath(), libTitleFor79(next),
                    savedSeekFor(next.getAbsolutePath()));
        } catch (Exception ignored) {}
    }

    // v11.95: history-clear generation guard — Settings'
    // doClearHistory removes the play-history stacks
    // (forwardHistoryRaw, hiddenTrail, libPrevTrail /
    // libTrailNext, libRedoTrail) and bumps historyClearGen in
    // ONE apply, so the bump and the removals land together; a
    // background advance that read a stack before the clear
    // and writes it back after resurrects the cleared entries
    // (and can replay a cleared forward/redo talk). The Room's
    // in-memory stacks are guarded by the same generation
    // (v8.36); the service never was — this closes that gap at
    // every service read→write site. Capture the generation
    // with each stack read and re-check it before any play or
    // write-back that uses that read: a moved generation means
    // the read predates a clear, so the play is skipped (fall
    // through to the plain advance) and the write is dropped
    // (trail appends keep only the post-clear step, which the
    // Room's onResume comment blesses as fresh history).
    // Same process throughout (no android:process in the
    // manifest), so the service's read sees Settings' bump
    // exactly like the Room's v8.36 read does.
    private long clearGen95(android.content.SharedPreferences sp) {
        try { return sp.getLong("historyClearGen", 0); } catch (Exception ignored) { return 0; }
    }

    // v8.11: library heard-history trail, the shelf mirror of the
    // Room's hiddenTrail (v8.08) — every background auto-advance
    // that really starts a different file appends the ended file's
    // path (cap 50) and names the started file in libTrailNext, so
    // DownloadedActivity's Previous can walk back to the file
    // actually heard before instead of the shelf-neighbour (wrong
    // after a shuffle pick or a queue-head jump). Repeat-one
    // replays the same file and records nothing; the helper also
    // skips a same-file record, so a one-file shuffle pick leaves
    // no trail entry. The foreground poll records through its own
    // copy of this helper in DownloadedActivity.
    private void noteLibTrail11(String ended, String next) {
        try {
            if (ended == null || ended.length() == 0
                    || next == null || next.length() == 0
                    || ended.equals(next)) return;
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            long gen95 = clearGen95(sp);
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
            // v11.95: a clear inside the read→write window
            // (see clearGen95) — drop the stale prefix and keep
            // only this post-clear step, so the erased chain is
            // not resurrected; libTrailNext still names the new
            // file, which was heard after the clear.
            if (clearGen95(sp) != gen95) {
                sp.edit().putString("libPrevTrail", ended)
                        .putString("libTrailNext", next).apply();
                return;
            }
            sp.edit().putString("libPrevTrail", sb.toString())
                    .putString("libTrailNext", next).apply();
        } catch (Exception ignored) {}
    }

    // v7.79: shelf-file title, derived exactly like
    // DownloadedActivity.Entry (filename minus .mp3, %XX-decoded
    // with '+' kept, '_'/'-' to spaces, trimmed, trailing
    // " 8-hex" collision suffix stripped, filename fallback) —
    // so the background advance's A-Z / largest-first order and
    // its session anchors match the library's own rows.
    private String libTitleFor79(java.io.File f) {
        try {
            String n = f.getName();
            if (n.toLowerCase().endsWith(".mp3")) n = n.substring(0, n.length() - 4);
            try { n = java.net.URLDecoder.decode(n.replace("+", "%2B"), "UTF-8"); }
            catch (Exception ignored) {}
            String t = n.replace('_', ' ').replace('-', ' ').trim();
            t = t.replaceAll(" [0-9a-f]{8}$", "");
            if (t.length() == 0) t = f.getName();
            return t;
        } catch (Exception ignored) { return f.getName(); }
    }

    public boolean hasTrackEnded() { return trackEnded; }

    // v7.58: background auto-advance, step 2 of the piece v7.57
    // started — the service now advances a finished Room talk
    // itself when the Room is hidden, instead of stopping until
    // the listener returns and the Room poll consumes the stale
    // end flag. v7.63 extends it from the plain-list case to the
    // hand-queue case, keeping the Room end block's order
    // (sleep-end > repeat-one > queue/autoplay): repeat-one
    // replays before any queue is consulted (v7.58 checked the
    // queue first, so repeat-one + queue deferred to the Room
    // instead of replaying here), and with autoplay on the
    // queue head plays before the plain list-next. Everything
    // else still defers to the Room poll on return by leaving
    // trackEnded set, exactly as before: a library session
    // (lastKind != room), the sleep one-shot (the Room poll
    // consumes it and toasts on return), shuffle on for the
    // plain list (the random pick lives in Room memory — the
    // queue branch below runs before this check, because a
    // queued head is an explicit order, not a shuffle pick),
    // autoplay off (the Room stops there even with a queue
    // waiting), repeat-off at the last talk, a gated queue
    // head (peeked, never popped, v7.23), a catalog-miss or
    // gated foreign queue head, a natural end while the
    // foreign Room is on top (v12.11), and a mobile-data
    // next talk with no downloaded file under data saver (the
    // Room poll asks the gate question on return). The ended
    // talk is found by URL lookup in the v7.57 saved list,
    // never by raw index, so a site-list reload mid-session
    // cannot mis-aim it, and the sounding form may be the
    // local file path (Room play passes the offline path when
    // one exists) — offlineFileFor maps each saved stream URL
    // back to it. Advancing calls play(), which clears
    // trackEnded, so the Room poll on return sees a playing
    // talk, re-syncs currentIndex from the service (its
    // bind/load syncs), and cannot double-advance; the
    // lastUrl/lastTitle anchors move to the new talk first, so
    // a notification tap mid-prepare lands on the new talk
    // instead of re-opening the finished one. The playList
    // index moves with the advance, so a second background
    // completion chains from the right place.
    // v8.08: record one hidden background step for the Room's shuffle
    // Previous walk. advanceInBackground58 chains while the Room sits
    // hidden, but the Room's return correctors (v8.06/v8.07) only ever
    // pushed the single talk their stale index named — intermediate
    // talks in a chained advance (F -> S -> T while hidden) never
    // entered prevHistory, so Previous skipped them. Each hidden
    // advance that actually plays a different talk appends the ended
    // talk's stream URL here (cap 50, oldest dropped); the Room
    // drains it on its next index corrector. Same-talk replays
    // (repeat-one, single-talk shuffle pick) record nothing, and a
    // deferred decision (foreign/gated head, autoplay off) records
    // nothing — the Room poll's playIndex push still owns those.
    // Called only after a successful play(), never before, so a
    // failed play leaves no phantom step behind.
    private void pushHiddenTrail08(android.content.SharedPreferences sp,
            String endedStreamUrl, String newUrl) {
        try {
            if (endedStreamUrl == null || endedStreamUrl.length() == 0) return;
            if (endedStreamUrl.equals(newUrl)) return;
            long gen95 = clearGen95(sp);
            String raw = sp.getString("hiddenTrail", "");
            java.util.ArrayList<String> list = new java.util.ArrayList<String>();
            if (raw != null && raw.length() > 0) {
                String[] parts = raw.split("\\n");
                for (int i = 0; i < parts.length; i++)
                    if (parts[i] != null && parts[i].length() > 0) list.add(parts[i]);
            }
            list.add(endedStreamUrl);
            while (list.size() > 50) list.remove(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append("\n");
                sb.append(list.get(i));
            }
            // v11.95: a clear inside the read→write window
            // (see clearGen95) — drop the stale prefix and keep
            // only this post-clear step (the Room's onResume
            // comment blesses it as fresh history, heard after
            // the clear), so the erased chain is not resurrected.
            if (clearGen95(sp) != gen95) {
                sp.edit().putString("hiddenTrail", endedStreamUrl).apply();
                return;
            }
            sp.edit().putString("hiddenTrail", sb.toString()).apply();
        } catch (Exception ignored) {}
    }

    // v11.96 (B-D3, v11.83 persistence audit): sounding-line index with dual-claim
    // disambiguation -- two stream URLs can map to one downloaded file (a legacy dual
    // claim, v8.42), so a path-form currentUrl matches BOTH claimants' lines and the
    // old first-match picked the wrong one: the background advance then chained from
    // the wrong line (wrong next talk, wrong repeat, wrong shuffle exclusion, wrong
    // Previous walk, wrong hidden-trail step) whenever the sounding talk was the later
    // claimant. Every play() writes lastUrl = the stream URL even when playing the
    // local file, so lastUrl names the sounding talk exactly: prefer its line, and
    // fall back to today's scan (direct match, then first path match) when lastUrl is
    // empty or names no line here. Single-claim and streamed sessions are identical:
    // there lastUrl is the same line the old scan found first.
    private int soundingLineIndex96(String[] lines, android.content.SharedPreferences sp) {
        try {
            String lastUrl = sp.getString("lastUrl", "");
            if (lastUrl != null && lastUrl.length() > 0) {
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    if (line == null || line.length() == 0) continue;
                    int tab = line.indexOf('\t');
                    String u = tab >= 0 ? line.substring(0, tab) : line;
                    if (u.equals(lastUrl)) return i;
                }
            }
        } catch (Exception ignored) {}
        int idx = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.length() == 0) continue;
            int tab = line.indexOf('\t');
            String u = tab >= 0 ? line.substring(0, tab) : line;
            if (u.equals(currentUrl)) { idx = i; break; }
            java.io.File f = offlineFileFor(u);
            if (f != null && f.getAbsolutePath().equals(currentUrl)) { idx = i; break; }
        }
        return idx;
    }

    // v12.09: a manual Next/Prev press (headset/car) that defers on the data gate used to
    // die silently while backgrounded — the Room poll's gate toast only runs with the Room
    // foreground, so a backgrounded press looked dead. Say so at once, reusing the Room's
    // own wording. Manual-only: a natural end still defers silently to the Room poll's
    // gate toast on return, exactly as designed.
    private void toastGateDeferred58() {
        try {
            android.widget.Toast.makeText(AudioService.this,
                    Lang.t(AudioService.this, "mobile_no_autoplay"),
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }

    private void advanceInBackground58(boolean manual) {
        try {
            if (currentUrl == null || currentUrl.length() == 0) return;
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (!"room".equals(sp.getString("lastKind", ""))) return;
            // v11.77: manual=true is a headset/car Next press, not a natural end — the Room
            // on-top defer, the sleep one-shot stop and the bedtime-stop guard below are all
            // auto-advance rules; a deliberate press overrides them (its own play() clears
            // the bedtime flag, and the one-shot still stops the NEXT natural end).
            // v7.74: background-only, as v7.58 intended — when the
            // session's own Room is the foreground screen, its
            // 500ms poll already owns the end (advance toast, new
            // row fade, queue consume, played-mark refresh), and
            // this advance used to pre-empt it: play() cleared
            // trackEnded before the poll's next tick, so a
            // foreground plain-list or queue advance played the
            // next talk with no "နောက်: " toast and no row fade,
            // while every deferred case (shuffle, foreign head,
            // gate, autoplay off, repeat-off at last) still went
            // through the poll — the two paths disagreed in the
            // same Room. Deferring here leaves trackEnded set, so
            // the poll's end block runs exactly as before v7.58.
            // Matched by sayadaw (RoomActivity.topRoomSayadaw,
            // re-published on foreign handover): a different
            // Room on top is not this session's Room, so its
            // advance still runs, and a Room in the back stack
            // re-syncs on return (v7.59) if it did.
            // v11.77: a bedtime stop is pending (minute timer fired mid-fade and the talk ended
            // before the fade-end pause) — an automatic advance would play on past bedtime with
            // a fresh timer, so defer: trackEnded stays set for the Room poll, which skips the
            // advance the same way. A manual skip passes manual=true and overrides (a deliberate
            // press is a new listen; play() clears the flag).
            if (!manual && (sleepFading || sleepStopPending)) return;
            String topSd = RoomActivity.topRoomSayadaw;
            if (!manual && topSd != null && topSd.equals(sp.getString("lastName", ""))) return;
            if (!manual && isSleepEndOfTalk()) return;
            String raw = Prefs.playListRaw(this);
            if (raw == null || raw.length() == 0) return;
            String[] lines = raw.split("\n");
            // v11.96 (B-D3): sounding index via the shared helper -- the old first-match
            // scan named the first claimant of a dual-claimed file.
            int idx = soundingLineIndex96(lines, sp);
            if (idx < 0) return;
            int mode = Prefs.repeatMode(this);
            int nx;
            // v11.77: a manual Next ignores repeat-one, like the Room's manual Next
            // (consumeNextIndex) — repeat-one is an end-of-talk loop order, not a Next order.
            if (mode == 2 && !manual) nx = idx;
            else {
                // v7.63: queue head before plain list-next, but
                // only with autoplay on (the Room's autoplay-off
                // branch stops even with a queue waiting), and
                // only when the helper takes the decision — a
                // false return means no waiting head exists, so
                // the plain advance below still runs.
                // v11.77: a manual Next consults the queue even with autoplay off.
                if ((manual || Prefs.autoplay(this)) && advanceQueueHead63(sp, raw, lines, manual)) return;
                // v8.27: background forward (redo) step -- the
                // Room's peek consults forwardHistory after the
                // hand queue and before shuffle/list-next, so a
                // foreground end plays the talk just left after
                // a Previous step; this background advance used
                // to skip straight to shuffle/list-next, so the
                // same end played a different next talk with the
                // phone locked than with the Room open. The Room
                // persists its stack to forwardHistoryRaw on
                // every mutation (RoomActivity.saveForwardHistory27),
                // and the entry played here is popped from that
                // copy; the Room reconciles its in-memory copy
                // on return, so a background-played step is never
                // replayed. Order mirrors peek: the hand queue
                // above already won, repeat-one never reaches
                // here (mode 2 branch above), autoplay off
                // stops below exactly as before, and a data-
                // gated pick defers untouched to the Room poll's
                // gate toast on return, like the shuffle pick.
                // v11.77: a manual Next consults the forward stack even with autoplay off.
                if (manual || Prefs.autoplay(this)) {
                    long gen95 = clearGen95(sp);
                    String fwdRaw = sp.getString("forwardHistoryRaw", "");
                    if (fwdRaw != null && fwdRaw.length() > 0) {
                        String[] fwdParts = fwdRaw.split("\n");
                        for (int fi = fwdParts.length - 1; fi >= 0; fi--) {
                            String fu = fwdParts[fi];
                            if (fu == null || fu.length() == 0) continue;
                            int fIdx = -1;
                            for (int i = 0; i < lines.length; i++) {
                                String line = lines[i];
                                if (line == null || line.length() == 0) continue;
                                int tab = line.indexOf('\t');
                                String u = tab >= 0 ? line.substring(0, tab) : line;
                                if (u.equals(fu)) { fIdx = i; break; }
                                java.io.File f = offlineFileFor(u);
                                if (f != null && f.getAbsolutePath().equals(fu)) { fIdx = i; break; }
                            }
                            if (fIdx < 0) continue; // stale: gone from the list
                            if (fIdx == idx) continue; // never replay the just-ended talk
                            String fLine = lines[fIdx];
                            int fTab = fLine.indexOf('\t');
                            String fUrl = fTab >= 0 ? fLine.substring(0, fTab) : fLine;
                            if (fUrl.length() == 0) continue;
                            java.io.File fLocal = offlineFileFor(fUrl);
                            if (Prefs.dataSaver(this) && !isWifi58() && fLocal == null) {
                                if (manual) toastGateDeferred58();
                                return;
                            }
                            String fTitle = fTab >= 0 ? fLine.substring(fTab + 1) : "";
                            // v11.95: a clear inside the read→play
                            // window (see clearGen95) — the pick
                            // comes from a pre-clear stack, so do
                            // not replay the erased talk and do not
                            // write the popped stack back; break out
                            // to the shuffle / list-next pick below,
                            // the correct post-clear behaviour.
                            if (clearGen95(sp) != gen95) break;
                            StringBuilder fSb = new StringBuilder();
                            boolean fFirst = true;
                            for (int k = 0; k < fwdParts.length; k++) {
                                if (k == fi) continue;
                                if (!fFirst) fSb.append("\n");
                                fSb.append(fwdParts[k]); fFirst = false;
                            }
                            sp.edit().putString("forwardHistoryRaw", fSb.toString()).apply();
                            sp.edit().putString("lastUrl", fUrl).putString("lastTitle", fTitle).apply();
                            Prefs.savePlayList(this, raw, fIdx);
                            play(fLocal != null ? fLocal.getAbsolutePath() : fUrl, fTitle, savedSeekFor(fUrl));
                            int eTab27 = lines[idx].indexOf('\t');
                            pushHiddenTrail08(sp, eTab27 >= 0 ? lines[idx].substring(0, eTab27) : lines[idx], fUrl);
                            return;
                        }
                    }
                }
                // v8.05: background shuffle — the old code returned
                // here with shuffle on, leaving trackEnded set for
                // the Room poll to consume on return: a talk ending
                // while the Room sat hidden (phone locked, Main
                // screen, notification) stopped dead in silence
                // until the listener reopened the Room, even though
                // background playback is the point. The library
                // background advance (v7.79) already plays its
                // shuffle pick while hidden, so the Room side now
                // does the same: with autoplay on, draw a random
                // non-empty line other than the ended one (a
                // single-talk list replays its only talk, the
                // foreground peek's size-1 fallthrough), then run
                // the plain advance's data gate + anchors + play
                // below. play() clears trackEnded, so a Room
                // returning later re-syncs onto the playing talk
                // (v7.59) instead of double-advancing. Autoplay
                // off still stops (the foreground rule), a gated
                // pick still defers untouched to the Room poll's
                // gate toast on return, and the Room's v7.47
                // recent-history exclusion stays a foreground
                // refinement — hidden shuffle excludes only the
                // just-ended talk, exactly like the library's.
                if (Prefs.shuffleOn(this)) {
                    // v11.77: a manual Next shuffles even with autoplay off.
                    if (!manual && !Prefs.autoplay(this)) return;
                    java.util.ArrayList<Integer> pool =
                            new java.util.ArrayList<Integer>();
                    for (int i = 0; i < lines.length; i++) {
                        if (i == idx) continue;
                        String line = lines[i];
                        if (line == null || line.length() == 0) continue;
                        int tab = line.indexOf('\t');
                        String u = tab >= 0 ? line.substring(0, tab) : line;
                        if (u.length() == 0) continue;
                        pool.add(i);
                    }
                    int pick = pool.isEmpty() ? idx
                            : pool.get(new java.util.Random().nextInt(pool.size()));
                    String pLine = lines[pick];
                    int pTab = pLine.indexOf('\t');
                    String pUrl = pTab >= 0 ? pLine.substring(0, pTab) : pLine;
                    String pTitle = pTab >= 0 ? pLine.substring(pTab + 1) : "";
                    if (pUrl.length() == 0) return;
                    java.io.File pLocal = offlineFileFor(pUrl);
                    if (Prefs.dataSaver(this) && !isWifi58() && pLocal == null) {
                        if (manual) toastGateDeferred58();
                        return;
                    }
                    sp.edit().putString("lastUrl", pUrl).putString("lastTitle", pTitle).apply();
                    Prefs.savePlayList(this, raw, pick);
                    // v11.84: a single-talk pick (pick == idx, pool empty) is a replay — restart from
                    // 0 like the foreground does; savedSeekFor used to resume it from the last
                    // checkpoint, so a headset/car Next mid-talk replayed the last seconds and
                    // looked dead. Auto-advance is unaffected (completion already cleared the spot).
                    play(pLocal != null ? pLocal.getAbsolutePath() : pUrl, pTitle, pick == idx ? 0 : savedSeekFor(pUrl));
                    // v11.20: background shuffle play is a branch
                    // too — same forward invalidation as the queue
                    // head above (a foreground shuffle advance never
                    // runs while a forward top is live, since peek
                    // returns it first, so a shuffle play is never
                    // the forward target). A single-talk pick == idx
                    // replays the same talk, so the stack stays.
                    if (pick != idx) {
                        try { sp.edit().remove("forwardHistoryRaw").apply(); } catch (Exception ignored) {}
                    }
                    // v8.08: hidden-trail step (single-talk pick ==
                    // ended talk records nothing via the guard).
                    int eTab08 = lines[idx].indexOf('\t');
                    pushHiddenTrail08(sp, eTab08 >= 0 ? lines[idx].substring(0, eTab08) : lines[idx], pUrl);
                    return;
                }
                // v11.77: a manual Next advances even with autoplay off — autoplay gates the
                // automatic end-of-talk advance, never a deliberate press.
                if (!manual && !Prefs.autoplay(this)) return;
                nx = idx + 1;
                if (nx >= lines.length) {
                    // v11.77: a manual Next wraps at the last talk, like the Room's manual Next —
                    // repeat-off stops an automatic loop, never a deliberate press.
                    if (manual || mode == 1) nx = 0;
                    else return;
                }
            }
            // v12.18 (slice C finding 3): skip blank lines instead of stalling — the
            // stored list can hold an interior blank line (malformed fetch) and the old
            // code returned with trackEnded set and silence until the Room came back,
            // while every sibling branch skips blanks. Running off the end re-applies
            // the same end-of-list rule (manual/repeat-all wraps once and scans again,
            // repeat-off stops).
            boolean wrapped18 = false;
            while (true) {
                while (nx < lines.length) {
                    String sLine18 = lines[nx];
                    if (sLine18 != null && sLine18.length() > 0) {
                        int sTab18 = sLine18.indexOf('\t');
                        String sUrl18 = sTab18 >= 0 ? sLine18.substring(0, sTab18) : sLine18;
                        if (sUrl18.length() > 0) break;
                    }
                    nx++;
                }
                if (nx < lines.length) break;
                if ((manual || mode == 1) && !wrapped18) { wrapped18 = true; nx = 0; continue; }
                return;
            }
            String nLine = lines[nx];
            int nTab = nLine.indexOf('\t');
            String nUrl = nTab >= 0 ? nLine.substring(0, nTab) : nLine;
            String nTitle = nTab >= 0 ? nLine.substring(nTab + 1) : "";
            if (nUrl.length() == 0) return;
            java.io.File nLocal = offlineFileFor(nUrl);
            if (Prefs.dataSaver(this) && !isWifi58() && nLocal == null) {
                if (manual) toastGateDeferred58();
                return;
            }
            sp.edit().putString("lastUrl", nUrl).putString("lastTitle", nTitle).apply();
            Prefs.savePlayList(this, raw, nx);
            // v10.51: repeat-one replays from 0, never from a stale
            // per-talk spot — the foreground Room replays with an
            // explicit 0 (playIndex(currentIndex, 0), v4.35) and the
            // library background advance with the 2-arg play (0),
            // but this background Room tail passed savedSeekFor
            // for every advance. A talk resumed near its end that
            // finishes within 15s (no checkpoint, no pause) leaves
            // the global spot naming another talk, so the
            // completion clear's v1.89 guard correctly spares that
            // other talk's spot — and the finished talk's own
            // per-talk spot survives with it. With repeat-one on
            // and the Room hidden, the replay then started at
            // that old spot and looped the same tail forever
            // instead of replaying the talk. Plain/shuffle/
            // queue/forward advances keep savedSeekFor (resuming
            // a different talk from its spot is the app's resume
            // rule); only the mode-2 replay forces 0.
            // v11.84: a single-talk wrap (nx == idx) is the same replay as mode 2 — a manual Next
            // must restart from 0, not from the last checkpoint (same dead-Next symptom as the
            // shuffle branch above).
            play(nLocal != null ? nLocal.getAbsolutePath() : nUrl, nTitle, (mode == 2 || nx == idx) ? 0 : savedSeekFor(nUrl));
            // v11.20: background list-next play is a branch too
            // — same forward invalidation as the branches above.
            // Repeat-one (nx == idx) and the single-talk wrap
            // (nx == idx) replay the same talk, so the stack
            // stays valid, like the library v11.19 repeat-one rule.
            if (nx != idx) {
                try { sp.edit().remove("forwardHistoryRaw").apply(); } catch (Exception ignored) {}
            }
            // v8.08: hidden-trail step (repeat-one replay == ended
            // talk records nothing via the guard).
            int eTab08b = lines[idx].indexOf('\t');
            pushHiddenTrail08(sp, eTab08b >= 0 ? lines[idx].substring(0, eTab08b) : lines[idx], nUrl);
        } catch (Exception ignored) {}
    }

    // v11.77: in-service Previous for a backgrounded Room session (headset/car) — the mirror of
    // RoomActivity.handlePrev's list fallback: more than 3s into the talk restarts it, otherwise
    // the previous list talk with wrap. The heard-history stack lives in the Room (no persisted
    // copy), so no trail pop happens here; the Room's return correctors push the left-behind
    // talk on return, exactly as they do for a hidden background advance. A data-gated pick
    // stops silently (no surprise streaming) — the notification's Previous still asks the gate
    // question via the Room.
    private void manualSkipPrevRoom() {
        try {
            if (currentUrl == null || currentUrl.length() == 0) return;
            // v12.23: same ended guard as the foreground handlePrev (v11.89 library fix, Room
            // form) — an ended talk backgrounded with autoplay off sat at the full duration,
            // so a headset/car Previous sought to 0 and parked it paused instead of stepping
            // back to the previous talk in the saved list below.
            if (!trackEnded && getEffectivePosition() > 3000) {
                if (isBuffering()) restartFromStart(); else seekTo(0);
                return;
            }
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (!"room".equals(sp.getString("lastKind", ""))) return;
            String raw = Prefs.playListRaw(this);
            if (raw == null || raw.length() == 0) return;
            String[] lines = raw.split("\n");
            // v11.96 (B-D3): same shared helper as the background advance above.
            int idx = soundingLineIndex96(lines, sp);
            if (idx < 0) return;
            int nx = idx;
            String nUrl = null;
            String nTitle = "";
            for (int t = 0; t < lines.length; t++) {
                nx--;
                if (nx < 0) nx = lines.length - 1;
                String line = lines[nx];
                if (line == null || line.length() == 0) continue;
                int tab = line.indexOf('\t');
                String u = tab >= 0 ? line.substring(0, tab) : line;
                if (u.length() == 0) continue;
                nUrl = u;
                nTitle = tab >= 0 ? line.substring(tab + 1) : "";
                break;
            }
            if (nUrl == null) return;
            java.io.File nLocal = offlineFileFor(nUrl);
            if (Prefs.dataSaver(this) && !isWifi58() && nLocal == null) {
                // v12.09: this whole function is a manual headset/car Previous press, so a
                // gated pick says so at once (see toastGateDeferred58) instead of dying silent.
                toastGateDeferred58();
                return;
            }
            sp.edit().putString("lastUrl", nUrl).putString("lastTitle", nTitle).apply();
            Prefs.savePlayList(this, raw, nx);
            // A single-talk list (or every other line empty) lands back on the same talk:
            // Previous restarts it, like handlePrev's >3s rule.
            play(nLocal != null ? nLocal.getAbsolutePath() : nUrl, nTitle,
                    nx == idx ? 0 : savedSeekFor(nUrl));
        } catch (Exception ignored) {}
    }

    // v11.77: in-service Previous for a backgrounded library session (headset/car) — the mirror
    // of DownloadedActivity.handleLibPrev's list fallback: more than 3s into the file restarts
    // it, otherwise the previous shelf file with wrap, skipping files deleted since the last
    // scan. Records the step like the foreground fallback does (redo cleared, trail noted),
    // so a later foreground Previous walks back to the file just left.
    private void manualSkipPrevLib() {
        try {
            if (currentUrl == null || currentUrl.length() == 0) return;
            // v12.23: same ended guard as the foreground handleLibPrev (v11.89) — an ended
            // file backgrounded with autoplay off sat at the full duration, so a headset/car
            // Previous sought to 0 and parked it paused instead of stepping back to the
            // previous shelf file below.
            if (!trackEnded && getEffectivePosition() > 3000) {
                if (isBuffering()) restartFromStart(); else seekTo(0);
                return;
            }
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (!"lib".equals(sp.getString("lastKind", ""))) return;
            java.util.ArrayList<java.io.File> shelf = shelfFiles79();
            if (shelf == null || shelf.isEmpty()) return;
            int idx = -1;
            for (int i = 0; i < shelf.size(); i++)
                if (shelf.get(i).getAbsolutePath().equals(currentUrl)) { idx = i; break; }
            if (idx < 0) return;
            java.io.File prev = null;
            for (int t = 1; t <= shelf.size(); t++) {
                int nx = idx - t;
                while (nx < 0) nx += shelf.size();
                if (shelf.get(nx).exists()) { prev = shelf.get(nx); break; }
            }
            if (prev == null) {
                try {
                    android.widget.Toast.makeText(AudioService.this,
                            Lang.t(AudioService.this, "dl_file_gone"),
                            android.widget.Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
                return;
            }
            sp.edit().putString("lastKind", "lib")
                    .putString("lastLibPath", prev.getAbsolutePath())
                    .putString("lastLibTitle", libTitleFor79(prev)).apply();
            sp.edit().remove("libRedoTrail").apply();
            noteLibTrail11(currentUrl, prev.getAbsolutePath());
            // v11.84: a single-file shelf lands back on the same file — Previous restarts it from
            // 0, like the Room mirror (nx == idx ? 0); savedSeekFor used to jump it back to an
            // old minute from an earlier session (reachable right after a long-press restart).
            play(prev.getAbsolutePath(), libTitleFor79(prev),
                    prev.getAbsolutePath().equals(currentUrl) ? 0 : savedSeekFor(prev.getAbsolutePath()));
        } catch (Exception ignored) {}
    }

    // v7.63: hand-queue head advance for advanceInBackground58.
    // True = the service took the decision, so the caller must
    // return: either it played the head (popped + anchors +
    // playList index moved, exactly like the plain advance), or
    // it deliberately deferred to the Room poll on return.
    // False = no waiting head exists, so the caller falls
    // through to the plain-list advance. The scan mirrors the
    // Room's firstWaitingQueueEntry + peek pair: lines are read
    // in order, malformed lines skipped, the sounding talk
    // skipped (v5.85 — a service-restart play can leave it
    // queued), and the FIRST remaining line decides. A foreign
    // v2 head decides immediately (v12.11: it plays here, or
    // defers on a catalog miss / data gate / foreign Room on
    // top — the Room's branch checks the foreign head before
    // any in-list advance, so deciding here never mis-orders
    // it). A head the saved v7.57 list does not name
    // hard-defers too,
    // EXCEPT since v11.52 when a later listed head exists (see
    // the scan note there): the service cannot tell a talk that
    // left the site list (which the Room prunes on return) from
    // a saved list gone stale (where the head is still valid),
    // so it never prunes or pops on a guess — the Room
    // re-checks against its fresh list on return. Only a head
    // found in the saved list is popped, and only after the
    // data gate passes (gated head = peeked, never popped,
    // v7.23). Popping is by line index, not by value, so
    // duplicate queue lines keep their order.
    // v12.11: a foreign v2 head is the exception to the "found in the saved list" rule — it
    // has no index in this list by design, so it is popped after the catalog + gate checks
    // in the scan (not after the list scan), the rest of the queue travels to its sayadaw's
    // key, and the last* anchors move with it. playList57 is rewritten behind the handover
    // by refreshPlayListForForeign13 (v12.13, worker thread), so a second hidden end chains
    // in-list instead of stopping and deferring to the Room poll, as it did before v12.13.
    // v12.01: no generation guard on this pop by design — the v12.00 audit's
    // slice-B NOTE-1, closed: every writer of the per-sayadaw queue key runs
    // on the main thread (UI callbacks; MediaPlayer onCompletion on the
    // creating thread's looper; MediaSession callbacks with no Handler;
    // onStartCommand), and there is no yield between the fresh read at the
    // top of this method and the saveQueue write below, so no second writer
    // can interleave. If play() is ever called from a worker thread, this
    // assumption breaks and a guard becomes necessary.
    // The playList index moves to the head's list position, so
    // a later plain background advance chains from the head
    // instead of from the finished talk. A foreign head has no
    // list position (v12.11): the playList stays the old list
    // and the next hidden end defers instead of chaining.
    private boolean advanceQueueHead63(android.content.SharedPreferences sp,
            String raw, String[] lines, boolean manual) {
        try {
            String lastName = sp.getString("lastName", "");
            if (lastName.length() == 0) return false;
            String qRaw = Prefs.savedQueue(this, lastName);
            if (qRaw == null || qRaw.length() == 0) return false;
            String[] qLines = qRaw.split("\n");
            int headPos = -1;
            int headIdx = -1;
            String headUrl = null;
            // v12.11: a foreign head no longer always defers — see the scan below. When it
            // plays, headIdx stays -1 (no index in this list) and these carry the handover.
            boolean headForeign11 = false;
            String headSayadaw11 = null;
            String headTitle11 = null;
            String[] headCat11 = null;
            // v11.52: skip, never stall, on an unlisted head --
            // the old code returned true (defer) at the FIRST
            // line the saved list does not name, so a queue of
            // [stale head, valid head] ended a background talk
            // in silence: the Room poll that the deferral waits
            // for only runs with the Room foreground, and with
            // the phone locked / on Main / on the notification
            // nothing ever re-checked, so the valid head behind
            // the stale one never played -- the exact "stopped
            // dead in silence while hidden" failure the v8.05
            // shuffle fix removed from the plain advance. The
            // deferral's stated reason (a saved list gone stale
            // can hide a still-valid head) is real, so the skip
            // is scan-only, never a prune: an unlisted line is
            // passed over, a later listed head plays, and the
            // skipped line STAYS in the persisted queue (rest
            // below excludes only headPos), so the Room's fresh-
            // list peek on return still finds it first and plays
            // or prunes it on true evidence. Only when NO later
            // listed head exists does the scan still defer
            // (unknownSeen -> true), byte-identical to before
            // for the alone-ambiguous-head case -- and a foreign
            // v2 head still decides immediately, since its
            // handover order must never be jumped: it plays here
            // (v12.11 below) or defers, never lets a later head
            // overtake it.
            boolean unknownSeen52 = false;
            for (int i = 0; i < qLines.length; i++) {
                QueueEntry qe = QueueEntry.parse(qLines[i]);
                if (qe == null) continue;
                String qu = qe.url;
                if (qu.length() == 0) continue;
                if (qu.equals(currentUrl)) continue;
                java.io.File qf = offlineFileFor(qu);
                if (qf != null && qf.getAbsolutePath().equals(currentUrl)) continue;
                if (qe.isV2() && !qe.sayadaw.equals(lastName)) {
                    // v12.11: background foreign handover — the last "stopped dead in silence
                    // while hidden" case (v8.05 plain, v11.52 stale head). Until now this line
                    // hard-deferred to the Room poll, which only runs with the Room foreground,
                    // so a talk ending with the phone locked / on Main / on the notification and
                    // a foreign queue head waiting stopped playback until the listener reopened
                    // that Room. The head plays here instead, from its own v2 URL + title, with
                    // the same order rule as before: the decision is made at the FIRST foreign
                    // line, so a later in-list head never overtakes it. Deferral survives for
                    // exactly two cases, each keeping the queue untouched so the Room poll
                    // still finds the head first on return: (a) data gate — peeked, never popped
                    // (v7.23), the Room poll asks the gate question on return; (b) a natural end
                    // while the FOREIGN Room is on top — its poll owns the toast + row fade
                    // (v7.74 mirror; a manual Next overrides, like v11.77). A catalog miss used
                    // to be a third deferral; v12.18 drops it and keeps scanning instead (the
                    // Room drops it on return anyway, v11.68/v11.85 — see below).
                    // v12.18 (slice B finding 1): a catalog miss no longer defers in silence —
                    // the Room poll that used to drop this head on return only runs with the
                    // Room foreground, so while hidden the talk ended in silence and a headset
                    // Next died dead. A miss is decidable now from the bundled asset (unlike a
                    // stale fetched list, which a fresh load can revive), and the Room drops
                    // the head on return anyway, so drop it here and keep scanning: the next
                    // head plays, and with nothing left the caller falls through to the plain
                    // advance. The lookup is alias-aware (canonicalSayadaw99) so a pre-rename
                    // name is never mistaken for a miss.
                    String catName11 = Prefs.canonicalSayadaw99(AudioService.this, qe.sayadaw);
                    String[] cat11 = catalogEntryForSayadaw11(AudioService.this, catName11);
                    if (cat11 == null || cat11[1].length() == 0) {
                        java.util.ArrayList<String> kept11 = new java.util.ArrayList<String>();
                        for (int pi = 0; pi < qLines.length; pi++)
                            if (pi != i) kept11.add(qLines[pi]);
                        Prefs.saveQueue(this, lastName, kept11);
                        qLines = kept11.toArray(new String[0]);
                        i = -1; // restart the scan over the pruned queue
                        continue;
                    }
                    if (Prefs.dataSaver(this) && !isWifi58() && qf == null) {
                        if (manual) toastGateDeferred58();
                        return true;
                    }
                    if (!manual) {
                        String top11 = RoomActivity.topRoomSayadaw;
                        if (top11 != null && top11.equals(qe.sayadaw)) return true;
                    }
                    headPos = i;
                    headUrl = qu;
                    headForeign11 = true;
                    headSayadaw11 = qe.sayadaw;
                    headTitle11 = qe.title;
                    headCat11 = cat11;
                    break;
                }
                int found = -1;
                for (int j = 0; j < lines.length; j++) {
                    String line = lines[j];
                    if (line == null || line.length() == 0) continue;
                    int tab = line.indexOf('\t');
                    String u = tab >= 0 ? line.substring(0, tab) : line;
                    if (u.equals(qu)) { found = j; break; }
                    java.io.File f = offlineFileFor(u);
                    if (f != null && f.getAbsolutePath().equals(qu)) { found = j; break; }
                }
                if (found < 0) { unknownSeen52 = true; continue; }
                headPos = i;
                headIdx = found;
                headUrl = qu;
                break;
            }
            if (headPos < 0) return unknownSeen52;
            if (headForeign11) {
                return playForeignHead11(sp, qLines, headPos, headUrl, headSayadaw11,
                        headTitle11, headCat11, lines, null);
            }
            java.io.File hLocal = offlineFileFor(headUrl);
            if (Prefs.dataSaver(this) && !isWifi58() && hLocal == null) {
                // v12.09: manual-only toast (see toastGateDeferred58) — a natural end still
                // defers silently to the Room poll's gate toast on return.
                if (manual) toastGateDeferred58();
                return true;
            }
            java.util.ArrayList<String> rest = new java.util.ArrayList<String>();
            for (int i = 0; i < qLines.length; i++) {
                if (i != headPos) rest.add(qLines[i]);
            }
            Prefs.saveQueue(this, lastName, rest);
            String hLine = lines[headIdx];
            int hTab = hLine.indexOf('\t');
            String hTitle = hTab >= 0 ? hLine.substring(hTab + 1) : "";
            // v8.08: the ended talk's stream form, for the hidden
            // trail — currentUrl may be the offline path, so map it
            // back through the saved list exactly like the caller's
            // ended-index scan; unmappable means the Room could not
            // walk back to it anyway, so no step is recorded.
            // v11.96 (B-D3): ended talk via the shared helper -- the old first-match
            // scan named the first claimant of a dual-claimed file, so the hidden-trail
            // step pointed at the wrong talk.
            String ended63 = null;
            int endedIdx96 = soundingLineIndex96(lines, sp);
            if (endedIdx96 >= 0) {
                String eLine96 = lines[endedIdx96];
                int eTab96 = eLine96.indexOf('\t');
                ended63 = eTab96 >= 0 ? eLine96.substring(0, eTab96) : eLine96;
            }
            sp.edit().putString("lastUrl", headUrl).putString("lastTitle", hTitle).apply();
            Prefs.savePlayList(this, raw, headIdx);
            play(hLocal != null ? hLocal.getAbsolutePath() : headUrl, hTitle, savedSeekFor(headUrl));
            // v11.20: background queue-head play is a branch —
            // foreground playIndex pop-or-clears forwardHistory
            // on any fresh play, but this service branch never
            // touched forwardHistoryRaw, so a back-stepped stack
            // survived the queue jump and the next Next replayed
            // the talk left before the queue instead of advancing
            // from the head (Room mirror of the library v11.19
            // background redo gap). Pop-or-clear exactly like
            // playIndex; the Room's v8.28 reconcile adopts the
            // shrinkage on return. Gated heads never reach here.
            try {
                long gen95 = clearGen95(sp);
                String fwdRaw20 = sp.getString("forwardHistoryRaw", "");
                if (fwdRaw20 != null && fwdRaw20.length() > 0) {
                    String[] fwdParts20 = fwdRaw20.split("\\n");
                    java.util.ArrayList<String> fwdStack20 = new java.util.ArrayList<String>();
                    for (String fp20 : fwdParts20)
                        if (fp20 != null && fp20.length() > 0) fwdStack20.add(fp20);
                    // v11.95: a clear inside the read→write window
                    // (see clearGen95) — the clear already removed
                    // the key, so write nothing back; the head play
                    // above is a queue play, unaffected by the clear.
                    if (!fwdStack20.isEmpty() && clearGen95(sp) == gen95) {
                        if (fwdStack20.get(fwdStack20.size() - 1).equals(headUrl)) {
                            fwdStack20.remove(fwdStack20.size() - 1);
                            StringBuilder fwdSb20 = new StringBuilder();
                            for (int fi20 = 0; fi20 < fwdStack20.size(); fi20++) {
                                if (fi20 > 0) fwdSb20.append("\n");
                                fwdSb20.append(fwdStack20.get(fi20));
                            }
                            sp.edit().putString("forwardHistoryRaw", fwdSb20.toString()).apply();
                        } else {
                            sp.edit().remove("forwardHistoryRaw").apply();
                        }
                    }
                }
            } catch (Exception ignored) {}
            pushHiddenTrail08(sp, ended63, headUrl);
            return true;
        } catch (Exception ignored) {
            // v12.18 (slice C finding 4): a failure before the head was popped/played
            // is NOT a decision taken — the old `return true` told the caller the
            // advance was handled, so a natural end stalled silently while hidden (and
            // a manual headset Next died silent) until the Room returned. `false` falls
            // through to the forward/shuffle/list-next advance instead; a head already
            // popped is gone from the queue, so the plain advance simply plays the
            // same talk via the list path.
            return false;
        }
    }

    // v12.12: shared foreign-head play tail — extracted verbatim from advanceQueueHead63
    // (v12.11) so the download-complete re-fire (onAwaitedForeignComplete12, Case A) plays
    // the same handover instead of duplicating it. forceLocal11 is the just-landed file
    // for a re-fire (the download is known good there); null keeps the v12.11 behaviour
    // (offline file if present, else the stream URL). lastName is read here, not passed,
    // because it names the sayadaw being left in both callers.
    private boolean playForeignHead11(android.content.SharedPreferences sp,
            String[] qLines, int headPos, String headUrl, String headSayadaw11,
            String headTitle11, String[] headCat11, String[] lines, String forceLocal11) {
        try {
            String lastName = sp.getString("lastName", "");
            // v12.12: shared foreign-head play tail (extracted from advanceQueueHead63, v12.11)
            // — the mirror of RoomActivity.handoverToForeignTalk
            // (v7.08) minus the list load. The head plays NOW from its own v2 URL/title, so it
            // is popped (not kept, unlike the Room handover's deferred play, whose load-completion
            // playIndex drops it on real play). The REST of the queue travels to the new sayadaw's
            // key with the same rewriting (v1 lines -> v2 owned by the leaving sayadaw, title
            // from the saved old list; v2 lines for the new sayadaw -> bare URLs; other v2 lines
            // as-is), and the old key is cleared (v8.59), so the queue never re-offers played
            // talks to the old session. Anchors move to the new sayadaw — name + group + page +
            // photo, because the notification tap opens the Room from these, and a name-only move
            // would open the new Room on the old page; lastKind stays "room". playList57 is
            // rewritten behind the handover by refreshPlayListForForeign13 (v12.13, worker
            // thread — no network on the completion thread), so a SECOND hidden end now
            // chains in-list instead of stopping and deferring to the Room poll. The
            // forward-history pop-or-clear and the hidden-trail step mirror the in-list tail
            // below, so Previous walks back across the handover.
            java.io.File hLocal11 = offlineFileFor(headUrl);
            java.util.ArrayList<String> carried11 = new java.util.ArrayList<String>();
            for (int i = 0; i < qLines.length; i++) {
                if (i == headPos) continue;
                QueueEntry q11 = QueueEntry.parse(qLines[i]);
                if (q11 == null) continue;
                String line11 = qLines[i];
                if (!q11.isV2()) {
                    String title11 = "";
                    for (int j = 0; j < lines.length; j++) {
                        String l11 = lines[j];
                        if (l11 == null || l11.length() == 0) continue;
                        int t11 = l11.indexOf('\t');
                        String u11 = t11 >= 0 ? l11.substring(0, t11) : l11;
                        if (u11.equals(q11.url)) {
                            title11 = t11 >= 0 ? l11.substring(t11 + 1) : "";
                            break;
                        }
                    }
                    if (title11.length() > 0)
                        line11 = QueueEntry.format(new QueueEntry(q11.url, lastName, title11));
                } else if (q11.sayadaw.equals(headSayadaw11)) {
                    line11 = q11.url;
                }
                carried11.add(line11);
            }
            Prefs.saveQueue(this, headSayadaw11, carried11);
            Prefs.saveQueue(this, lastName, new java.util.ArrayList<String>());
            // v8.63 preview fallback: a title-less v2 line names its URL rather than blank.
            String hTitle11 = headTitle11 != null && headTitle11.length() > 0 ? headTitle11 : headUrl;
            String ended11 = null;
            int endedIdx11 = soundingLineIndex96(lines, sp);
            if (endedIdx11 >= 0) {
                String eLine11 = lines[endedIdx11];
                int eTab11 = eLine11.indexOf('\t');
                ended11 = eTab11 >= 0 ? eLine11.substring(0, eTab11) : eLine11;
            }
            sp.edit().putString("lastUrl", headUrl).putString("lastTitle", hTitle11)
                    .putString("lastName", headSayadaw11)
                    .putString("lastGroup", headCat11[0])
                    .putString("lastPage", headCat11[1])
                    .putString("lastPhoto", headCat11[2]).apply();
            play(forceLocal11 != null ? forceLocal11 : (hLocal11 != null ? hLocal11.getAbsolutePath() : headUrl), hTitle11, savedSeekFor(headUrl));
            try {
                long gen11 = clearGen95(sp);
                String fwdRaw11 = sp.getString("forwardHistoryRaw", "");
                if (fwdRaw11 != null && fwdRaw11.length() > 0) {
                    String[] fwdParts11 = fwdRaw11.split("\\n");
                    java.util.ArrayList<String> fwdStack11 = new java.util.ArrayList<String>();
                    for (String fp11 : fwdParts11)
                        if (fp11 != null && fp11.length() > 0) fwdStack11.add(fp11);
                    // v11.95: a clear inside the read→write window (see clearGen95) — the clear
                    // already removed the key, so write nothing back; the head play above is a
                    // queue play, unaffected by the clear.
                    if (!fwdStack11.isEmpty() && clearGen95(sp) == gen11) {
                        if (fwdStack11.get(fwdStack11.size() - 1).equals(headUrl)) {
                            fwdStack11.remove(fwdStack11.size() - 1);
                            StringBuilder fwdSb11 = new StringBuilder();
                            for (int fi11 = 0; fi11 < fwdStack11.size(); fi11++) {
                                if (fi11 > 0) fwdSb11.append("\n");
                                fwdSb11.append(fwdStack11.get(fi11));
                            }
                            sp.edit().putString("forwardHistoryRaw", fwdSb11.toString()).apply();
                        } else {
                            sp.edit().remove("forwardHistoryRaw").apply();
                        }
                    }
                }
            } catch (Exception ignored) {}
            pushHiddenTrail08(sp, ended11, headUrl);
            // v12.13: the second hidden end — refresh the stored list for the new sayadaw
            // (worker thread; see refreshPlayListForForeign13).
            refreshPlayListForForeign13(headSayadaw11, headCat11[1], headUrl);
            return true;
        } catch (Exception ignored) {
            // v12.61 (AudioService audit Finding 2): a failure before the head was played
            // is NOT a decision taken — the v12.18 mirror of the in-list sibling's slice C
            // finding 4. The old `return true` told advanceQueueHead63's caller the advance
            // was handled, so a natural end stalled silently while hidden (and a manual
            // headset Next died silent) until the Room returned, while the queued foreign
            // head was already popped by the carry. `false` falls through to the
            // forward/shuffle/list-next advance instead; a head already popped is gone from
            // the queue, so the plain advance plays the next in-list talk via the list path
            // (the foreign head itself is not in the old list, unlike the sibling's head),
            // and a head not yet popped stays queued for the next end. Reachable only
            // pre-play: play() catches its own setDataSource failure, savedSeekFor /
            // offlineFileFor / pushHiddenTrail08 are internally caught, the forward-history
            // block has its own nested catch, and the only post-play call
            // (refreshPlayListForForeign13, thread start) can fail only via Error, which
            // this catch never sees — so a head that already sounds can never be
            // double-advanced by the fall-through.
            return false;
        }
    }

    // v12.13: second hidden end after a background foreign handover — until now
    // playList57 still named the OLD sayadaw's list after the handover above, so when
    // the foreign talk ended while still hidden, soundingLineIndex96 found no index
    // and the advance stopped and deferred to the Room poll, which only runs with the
    // Room foreground (the v12.11 "still defers" note). The list is fetched on a worker
    // thread and playList57 rewritten to it, so the next hidden end chains in-list
    // like any other talk (plain advance, queue head, forward step, shuffle all read
    // the same stored list). The no-network-on-the-completion-thread rule still holds:
    // the handover itself plays at once from the v2 URL/title, and only this fetch
    // runs behind it — a slow or failed fetch just leaves today behaviour (that end
    // still defers; the write, when it lands, fixes the ends after it). No data gate
    // on the fetch: a Room loads its list on mobile data too, and this is tens of KB
    // of HTML, not audio. The write is guarded three ways: the fetched list must name
    // the head URL (a list that cannot resolve the sounding talk chains nothing, and
    // a wrong-page fetch must never overwrite the stored list), the session must still
    // be this foreign talk (lastName + lastUrl unchanged — a manual move to another
    // talk abandons the write, as does a second handover superseding this one), and
    // an already-stored identical list is a no-op (a Room that loaded the same list
    // meanwhile makes the write identical — same page order, same "url<TAB>title"
    // lines — so no clobber is possible). A failed or empty fetch abandons silently.
    private void refreshPlayListForForeign13(final String sayadaw13, final String page13,
            final String headUrl13) {
        if (sayadaw13 == null || sayadaw13.length() == 0 || page13 == null
                || page13.length() == 0 || headUrl13 == null || headUrl13.length() == 0) return;
        new Thread(new Runnable() {
            @Override public void run() {
                String raw13 = fetchPlayListRaw13(page13);
                if (raw13 == null || raw13.length() == 0) return;
                boolean namesHead13 = false;
                for (String l13 : raw13.split("\\n")) {
                    if (l13 == null || l13.length() == 0) continue;
                    int t13 = l13.indexOf('\t');
                    String u13 = t13 >= 0 ? l13.substring(0, t13) : l13;
                    if (u13.equals(headUrl13)) { namesHead13 = true; break; }
                }
                if (!namesHead13) return;
                try {
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    if (!sayadaw13.equals(sp.getString("lastName", ""))) return;
                    if (!headUrl13.equals(sp.getString("lastUrl", ""))) return;
                    if (raw13.equals(Prefs.playListRaw(AudioService.this))) return;
                    Prefs.savePlayList(AudioService.this, raw13, -1);
                } catch (Exception ignored) {}
            }
        }).start();
    }

    // v12.13: service-side copy of RoomActivity's talk-list loader (same page fetch,
    // same .mp3 link regex, same href normalisation and title cleaning) — the service
    // never had a loader, and the Room's is an instance method on its own screen.
    // Returns "url<TAB>title" lines in page order, like savePlayList57 builds, or ""
    // when the fetch fails or the page names no talks.
    private static String fetchPlayListRaw13(String page13) {
        java.net.HttpURLConnection conn13 = null;
        java.io.InputStream in13 = null;
        try {
            conn13 = (java.net.HttpURLConnection) new java.net.URL(page13).openConnection();
            conn13.setConnectTimeout(20000);
            conn13.setReadTimeout(30000);
            conn13.setRequestProperty("User-Agent", "Mozilla/5.0");
            in13 = conn13.getInputStream();
            java.io.ByteArrayOutputStream baos13 = new java.io.ByteArrayOutputStream();
            byte[] buf13 = new byte[8192];
            int n13;
            while ((n13 = in13.read(buf13)) > 0) baos13.write(buf13, 0, n13);
            byte[] bytes13 = baos13.toByteArray();
            String html13 = new String(bytes13, "UTF-8");
            java.util.regex.Matcher cm13 = java.util.regex.Pattern.compile(
                    "charset=[\"']?([A-Za-z0-9_-]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html13);
            if (cm13.find()) {
                try { html13 = new String(bytes13, cm13.group(1)); } catch (Exception ignored) {}
            }
            java.net.URL base13 = new java.net.URL(page13);
            java.util.regex.Pattern pat13 = java.util.regex.Pattern.compile(
                    "<a[^>]*href\\s*=\\s*(?:\"([^\"]*\\.mp3[^\"]*)\"|'([^']*\\.mp3[^']*)'|([^\\s>]*\\.mp3[^\\s>]*))[^>]*>(.*?)</a>",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m13 = pat13.matcher(html13);
            java.util.Map<String, String> seen13 = new java.util.LinkedHashMap<String, String>();
            while (m13.find()) {
                String href13 = m13.group(1) != null ? m13.group(1)
                        : (m13.group(2) != null ? m13.group(2) : m13.group(3));
                href13 = href13.trim();
                if (href13.indexOf('\n') >= 0 || href13.indexOf('\r') >= 0
                        || href13.indexOf('\t') >= 0) continue;
                String title13 = cleanTitle13(m13.group(4));
                if (title13.length() == 0)
                    title13 = decodePct13(href13.substring(href13.lastIndexOf('/') + 1));
                String abs13;
                try {
                    abs13 = new java.net.URL(base13, href13.replace("&amp;", "&")
                            .replace(" ", "%20")).toString();
                } catch (Exception ignored) { continue; }
                if (!seen13.containsKey(abs13)) seen13.put(abs13, title13);
            }
            StringBuilder sb13 = new StringBuilder();
            for (java.util.Map.Entry<String, String> e13 : seen13.entrySet()) {
                if (sb13.length() > 0) sb13.append('\n');
                String t13 = e13.getValue() == null ? "" : e13.getValue()
                        .replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
                sb13.append(e13.getKey()).append('\t').append(t13);
            }
            return sb13.toString();
        } catch (Exception ignored) {
            return "";
        } finally {
            if (in13 != null) { try { in13.close(); } catch (Exception ignored) {} }
            if (conn13 != null) { try { conn13.disconnect(); } catch (Exception ignored) {} }
        }
    }

    // v12.13: copies of RoomActivity.cleanTitle / decodePct (private static there) —
    // strip tags, decode the entities this old site uses, collapse whitespace; %XX
    // decode a filename for a text-less link, leaving a literal '+' alone.
    private static String cleanTitle13(String raw13) {
        String title13 = raw13.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'")
                .replace("&rsquo;", "'").replace("&lsquo;", "'")
                .replace("&rdquo;", "\"").replace("&ldquo;", "\"")
                .replace("&mdash;", "—").replace("&ndash;", "–")
                .replace("&hellip;", "…");
        try {
            java.util.regex.Matcher nm13 = java.util.regex.Pattern.compile(
                    "&#(\\d+);|&#x([0-9a-fA-F]+);").matcher(title13);
            StringBuffer sb13 = new StringBuffer();
            while (nm13.find()) {
                String rep13 = "";
                try {
                    int code13 = nm13.group(1) != null
                            ? Integer.parseInt(nm13.group(1))
                            : Integer.parseInt(nm13.group(2), 16);
                    rep13 = (code13 > 0 && code13 <= 0x10FFFF
                            && (code13 < 0xD800 || code13 > 0xDFFF))
                            ? new String(Character.toChars(code13)) : "";
                } catch (Exception ignored) {}
                nm13.appendReplacement(sb13, java.util.regex.Matcher.quoteReplacement(rep13));
            }
            nm13.appendTail(sb13);
            title13 = sb13.toString();
        } catch (Exception ignored) {}
        title13 = title13.replace("&amp;", "&");
        return title13.replaceAll("\\s+", " ").trim();
    }

    private static String decodePct13(String s13) {
        int q13 = s13.indexOf('?');
        if (q13 >= 0) s13 = s13.substring(0, q13);
        try {
            java.io.ByteArrayOutputStream baos13 = new java.io.ByteArrayOutputStream();
            for (int i13 = 0; i13 < s13.length();) {
                char c13 = s13.charAt(i13);
                if (c13 == '%' && i13 + 2 < s13.length()) {
                    int hi13 = Character.digit(s13.charAt(i13 + 1), 16);
                    int lo13 = Character.digit(s13.charAt(i13 + 2), 16);
                    if (hi13 >= 0 && lo13 >= 0) {
                        baos13.write(hi13 * 16 + lo13);
                        i13 += 3;
                        continue;
                    }
                }
                baos13.write(String.valueOf(c13).getBytes("UTF-8"));
                i13++;
            }
            return new String(baos13.toByteArray(), "UTF-8");
        } catch (Exception ignored) { return s13; }
    }

    // v7.58: Wi-Fi check for the background data gate — the Room's
    // isWifi mirrored here, since the service has no such helper
    // (NetworkCapabilities first, legacy fallback only when no
    // capabilities are available, same as the Room since v1.97).
    private boolean isWifi58() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                android.net.Network net = cm.getActiveNetwork();
                if (net != null) {
                    android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(net);
                    if (nc != null) {
                        if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))
                            return true;
                        if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                            // v10.79: v10.78's scan never ran for a VPN --
                            // the old line above returned false for the
                            // VPN network before the scan was reached,
                            // so VPN-on-Wi-Fi still read as mobile data
                            // and the v10.78 fix was dead code for exactly
                            // the case it was written for. Only a VPN
                            // active network looks beneath itself: API
                            // 31+ lists its underlying networks (the real
                            // Wi-Fi when the VPN rides it); when that
                            // yields nothing, and on older releases, the
                            // getAllNetworks scan runs, still gated to
                            // the VPN case -- plain mobile data returns
                            // false above and never scans. Inside the VPN
                            // case the scan CAN still inherit a stray
                            // connected Wi-Fi (dead AP / captive portal)
                            // while the VPN rides mobile data — no public
                            // API exposes a VPN's underlying networks
                            // (checked android-34, v11.84), so this stays
                            // a known narrow gap, not a fixable defect.
                            // Public-API route: a connected Wi-Fi network
                            // reports isConnected + TYPE_WIFI on its own
                            // NetworkInfo, while a merely tracked one does
                            // not -- so the scan now requires a CONNECTED
                            // Wi-Fi network, not just a Wi-Fi transport.
                            try {
                                for (android.net.Network n : cm.getAllNetworks()) {
                                    android.net.NetworkInfo ni2 = cm.getNetworkInfo(n);
                                    if (ni2 != null && ni2.isConnected()
                                            && ni2.getType() == android.net.ConnectivityManager.TYPE_WIFI)
                                        return true;
                                }
                            } catch (Exception ignored) {}
                            return false;
                        }
                        return false;
                    }
                }
            }
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected()
                    && ni.getType() == android.net.ConnectivityManager.TYPE_WIFI;
        } catch (Exception ignored) { return false; }
    }

    public boolean hasTrackError() { return trackError; }

    // v1.74: rebuild the notification card on return to a screen —
    // toggling notifications on/off in Settings while a talk sat
    // paused used to leave the old card (with or without buttons)
    // until the next play/pause tap rebuilt it, because nothing
    // re-showed it on return. Guarded: with no track there is no
    // card worth rebuilding, and startForeground must not invent one.
    public void refreshNotification() {
        if (currentTitle != null) showNotification(isPlaying());
    }

    // v1.91: Settings changes made while a talk plays in the
    // background used to wait for a Room/Library return (Room's
    // syncLiveSettings, v1.58/80, only runs on return) — a speed
    // change in Settings, then Home, left the old speed running,
    // and a sleep change to 0 left the old timer running. Settings
    // binds (no auto-create) and calls this, so the live talk
    // picks the new values up at once. Same paused-talk rule as
    // the Room: speed applies paused too, a sleep timer only
    // touches a playing talk.
    public void syncSettingsFromPrefs() {
        setSpeed(Prefs.defaultSpeed(this));
        // v10.91: a paused-talk cancel reached this sync through
        // the strip / Settings long-press (ACTION_SLEEP) and died
        // on the !isPlaying return — the pause-suspended countdown
        // survived, so the strips kept showing the 😴 ⏸ mark the
        // listener had just cancelled, until a resume retimed it
        // to the now-0 pref or a new talk cleared it. A cancelled
        // (pref 0) suspension is cleared here at once, like the
        // Room's direct long-press already did; a live suspension
        // (pref > 0) and the one-shot are untouched, and a playing
        // talk still follows the branches below.
        if (!isPlaying()) {
            if (!sleepEndOfTalk && Prefs.sleepMinutes(this) == 0 && sleepSuspendedMs > 0)
                setSleepMinutes(0);
            // v11.05: the playing branch below cancels an armed
            // end-of-talk one-shot when the minute pref changed
            // since it was armed, but this paused branch returned
            // without that guard — so reset-all (or a fresh sleep
            // choice) made while paused left the one-shot armed,
            // and it still stopped the talk at its natural end
            // even though the setting had been reset. Mirror the
            // playing-branch guard here: a routine sync (pref
            // unchanged since arming) still leaves the one-shot
            // alone, exactly as the playing branch does.
            if (sleepEndOfTalk && Prefs.sleepMinutes(this) != sleepEndArmedPref)
                cancelSleepEndOfTalk();
            return;
        }
        int mins = Prefs.sleepMinutes(this);
        long remaining = getSleepRemainingMs();
        // v4.82: a minute pref changed since the one-shot was armed
        // is a fresh choice and wins; an unchanged pref is a routine
        // sync and must leave the one-shot alone.
        if (sleepEndOfTalk && mins != sleepEndArmedPref) cancelSleepEndOfTalk();
        if (!sleepEndOfTalk && mins > 0 && (remaining == 0 || mins != getSleepSetMinutes()))
            setSleepMinutes(mins);
        else if (!sleepEndOfTalk && mins == 0 && remaining > 0) setSleepMinutes(0);
    }

    public void setSpeed(float s) {
        speed = s;
        applySpeed();
        // v1.72: push the new speed to the session at once — the old
        // code only refreshed it on the next 15s checkpoint, so a
        // speed change made in Settings left the notification /
        // headset state showing the stale speed until then.
        updateSession();
    }

    public void setSleepMinutes(int mins) {
        if (sleepRunnable != null) sleepHandler.removeCallbacks(sleepRunnable);
        sleepRunnable = null;
        sleepEndMs = 0;
        sleepSetMins = 0;
        liveSleepActive = false;
        // v11.77: an explicit new pick supersedes a pending bedtime stop — the listener chose a
        // new bedtime, so the old stop must not block the next advance.
        sleepStopPending = false;
        // v4.82: a minute setting replaces any one-shot (its own arm
        // path re-arms from the pref, so the flag must not survive
        // here and stop a later talk's end).
        sleepEndOfTalk = false;
        sleepEndFired = false; // v8.38: flag gone, fired state with it
        liveSleepEndOfTalk = false;
        // v1.80: a fresh setting replaces any suspended countdown too —
        // the old pause-saved remainder must not re-arm over it.
        sleepSuspendedMs = 0;
        if (mins <= 0) { syncLiveSleepRemaining(); return; } // v4.72
        sleepSetMins = mins;
        liveSleepActive = true;
        // v10.48: a pick made while the talk sits PAUSED must not arm
        // a wall-clock countdown — the old code did, so a pick made
        // during a long pause ran out silently (the fire branch
        // clears without a word when nothing is playing) and resume()
        // never re-armed it when the pick equalled the pause-time
        // pref (the v7.26 branch only fires on a CHANGED pref): the
        // listener's explicit pick simply vanished, and the talk
        // then played on past bedtime with no 😴 mark. A paused pick
        // now stores as a SUSPENDED countdown instead — the same
        // state pause() itself produces — so resume() (prepared path
        // and buffering un-hold path alike) arms the full picked
        // length from the resume, and a new talk still starts fresh
        // (play() clears the suspension, onPrepared arms from the
        // pref). Playing picks are untouched: they arm at once, as
        // before. The strip countdown already reads the suspended
        // value (syncLiveSleepRemaining), so the 😴 mark stays true
        // through the pause instead of silently clearing.
        if (currentTitle != null && !isPlaying()) {
            sleepSuspendedMs = mins * 60L * 1000L;
            syncLiveSleepRemaining(); // v4.72
            return;
        }
        armSleepCountdown(mins * 60L * 1000L);
        syncLiveSleepRemaining(); // v4.72
    }

    // v4.82: "stop at end of talk" sleep — arm / cancel / consume.
    // Arm replaces any minute countdown (one sleep mode at a time)
    // and records the minute pref, so the sync sites can tell a
    // fresh minute choice from a routine re-sync. The flag survives
    // pauses (a paused talk still ends) and is consumed exactly
    // once, at a natural end, by the Room / library end blocks.
    public void armSleepEndOfTalk() {
        if (sleepRunnable != null) sleepHandler.removeCallbacks(sleepRunnable);
        sleepRunnable = null;
        sleepEndMs = 0;
        sleepSetMins = 0;
        sleepSuspendedMs = 0;
        sleepEndOfTalk = true;
        sleepEndFired = false; // v8.38: a fresh arm is unfired, even after an end
        sleepEndArmedPref = Prefs.sleepMinutes(this);
        liveSleepEndOfTalk = true;
        liveSleepActive = true;
        syncLiveSleepRemaining(); // v4.72: clears any stale countdown
    }

    public void cancelSleepEndOfTalk() {
        sleepEndOfTalk = false;
        sleepEndFired = false; // v8.38: consume/cancel clears the fired state too
        liveSleepEndOfTalk = false;
        if (sleepEndMs == 0 && sleepSuspendedMs == 0) liveSleepActive = false;
        syncLiveSleepRemaining(); // v4.72
    }

    // v4.82: firing the one-shot at a natural end is a cancel with
    // the same state cleanup; the end blocks toast the stop.
    public void consumeSleepEndOfTalk() { cancelSleepEndOfTalk(); }

    public boolean isSleepEndOfTalk() { return sleepEndOfTalk; }

    public int getSleepEndArmedPref() { return sleepEndArmedPref; }

    // v1.90: suspend a running sleep countdown without cancelling it
    // (same shape as pause()'s playing branch) — used by the buffering
    // pause paths below, which used to return before that block and
    // leave the countdown running in real time. sleepSetMins stays:
    // the suspended countdown still belongs to that setting.
    private void suspendSleepIfRunning() {
        if (sleepEndMs != 0) {
            long rem = sleepEndMs - android.os.SystemClock.elapsedRealtime();
            if (rem > 0) sleepSuspendedMs = rem;
            if (sleepRunnable != null) sleepHandler.removeCallbacks(sleepRunnable);
            sleepRunnable = null;
            sleepEndMs = 0;
        }
        syncLiveSleepRemaining(); // v4.72
    }

    // v1.90: re-arm a pause-suspended countdown after a buffering
    // un-hold (toggle()/resume() return early there, before the
    // prepared-resume re-arm below) — retimed to the current setting
    // first, like the prepared path (off cancels, a new value
    // restarts full-length).
    private void rearmSuspendedSleep() {
        if (sleepSuspendedMs <= 0) return;
        int mins = Prefs.sleepMinutes(this);
        if (mins != sleepSetMins) retimeSuspendedSleep(mins);
        if (sleepSuspendedMs > 0 && sleepEndMs == 0) {
            long rem = sleepSuspendedMs;
            sleepSuspendedMs = 0;
            liveSleepActive = true;
            armSleepCountdown(rem);
        }
        syncLiveSleepRemaining(); // v4.72
    }

    // v1.80: shared countdown arm — setSleepMinutes starts it fresh,
    // resume() re-arms a pause-suspended remainder with the same
    // fire behaviour (toast only when something is actually playing).
    private void armSleepCountdown(long delayMs) {
        sleepEndMs = android.os.SystemClock.elapsedRealtime() + delayMs;
        sleepRunnable = new Runnable() {
            @Override public void run() {
                // Clear first, so the pause() below cannot suspend a
                // timer that already fired back into existence.
                sleepEndMs = 0;
                sleepSetMins = 0;
                sleepSuspendedMs = 0;
                liveSleepActive = false;
                syncLiveSleepRemaining(); // v4.72
                // v1.51: the timer used to stop playback with no word
                // at all — the listener could not tell a sleep stop
                // from a failed stream. Say so (pause() already updates
                // the notification and the Room's poll).
                // v1.55: but only when something is actually playing —
                // the wall-clock timer also fires after a manual pause,
                // and then the "sleep time is up" toast lied about a
                // talk that was already stopped. Paused = clear silently.
                // v1.90: a talk still buffering is not paused either —
                // the old code cleared the timer silently and the talk
                // then started playing anyway, past its bedtime. Hold
                // it instead, so it opens paused with the same word.
                if (isPlaying()) {
                    try {
                        android.widget.Toast.makeText(AudioService.this,
                                Lang.t(AudioService.this, "sleep_time_up"),
                                android.widget.Toast.LENGTH_LONG).show();
                    } catch (Exception ignored) {}
                    // v11.77: bedtime stop pending from here until the fade-end pause (or a manual
                    // pick / new sleep setting) — if the talk ends mid-fade, the advances and the
                    // end blocks must see it and stop instead of playing on.
                    sleepStopPending = true;
                    // v4.04: fade out over ~3s instead of cutting
                    // dead mid-word; startSleepFade ends in pause(),
                    // which saves the exact spot for resume.
                    startSleepFade();
                } else if (isBuffering()) {
                    try {
                        android.widget.Toast.makeText(AudioService.this,
                                Lang.t(AudioService.this, "sleep_time_up"),
                                android.widget.Toast.LENGTH_LONG).show();
                    } catch (Exception ignored) {}
                    holdPaused = true;
                    // v10.62: same strip gap as v10.61's toggle hold,
                    // via the sleep-timer route — the fade path
                    // reaches pause() (which clears livePlaying),
                    // but this buffering branch never did, so the
                    // strips kept showing ⏸ for a held talk.
                    livePlaying = false;
                    showNotification(false);
                    notifyState();
                    updateSession();
                }
            }
        };
        sleepHandler.postDelayed(sleepRunnable, delayMs);
    }

    // v4.04: sleep fade-out — 16 steps of 200ms from the current
    // volume (ducked 0.2 or full 1.0) down to silence, then pause().
    // pause() saves the exact spot and updates the notification and
    // the Room poll, so resume continues from the fade-end spot at
    // full volume (resume() already clears a duck dip). If playback
    // stops some other way mid-fade, the step loop just exits.
    private void startSleepFade() {
        if (player == null || !prepared || !isPlaying()) { pause(); return; }
        cancelSleepFade();
        // v8.60: the ramp base is read fresh on every step, not
        // captured at fade start — a notification duck (or its
        // gain) arriving mid-fade used to be overwritten by the
        // next step's captured startVol, so the talk jumped back
        // over the notification sound for the rest of the 3.2s
        // fade. A fade-out cannot abort like the fade-in does
        // (it must still end in pause), so it tracks the live
        // duck state instead; the fade still ends at silence.
        final int[] step = {0};
        sleepFading = true;
        fadeRunnable = new Runnable() {
            @Override public void run() {
                if (player == null || !prepared || !isPlaying()) {
                    sleepFading = false;
                    fadeRunnable = null;
                    return;
                }
                step[0]++;
                if (step[0] >= 16) {
                    try { player.setVolume(0f, 0f); } catch (Exception ignored) {}
                    sleepFading = false;
                    fadeRunnable = null;
                    // v11.77: the bedtime stop completed — the pending flag dies with it, so a
                    // later manual resume past bedtime is a new listen (v10.89) instead of a
                    // still-pending stop.
                    sleepStopPending = false;
                    pause();
                    try { if (player != null) player.setVolume(1.0f, 1.0f); } catch (Exception ignored) {}
                    return;
                }
                float v = (ducked ? 0.2f : 1.0f) * (1f - step[0] / 16f);
                try { player.setVolume(v, v); } catch (Exception ignored) {}
                fadeHandler.postDelayed(this, 200);
            }
        };
        fadeHandler.postDelayed(fadeRunnable, 200);
    }

    // v4.04: cancel a sleep fade and restore the listening volume.
    // No-op when no fade is running (the normal case for pause(),
    // play() and stopPlayback()).
    private void cancelSleepFade() {
        if (fadeRunnable != null) fadeHandler.removeCallbacks(fadeRunnable);
        fadeRunnable = null;
        if (sleepFading) {
            sleepFading = false;
            // v11.84: a pause mid-fade (tap, call, unplug) cancels the fade but used to leave
            // sleepStopPending set, so the NEXT natural end stopped silently as if bedtime had
            // just struck — the listener deliberately resumed past bedtime. The legitimate
            // ended-mid-fade case never reaches here with sleepFading true (the fade runnable
            // exits on !isPlaying() first), so this clear cannot eat a real bedtime stop.
            sleepStopPending = false;
            try { if (player != null) player.setVolume(ducked ? 0.2f : 1.0f, ducked ? 0.2f : 1.0f); } catch (Exception ignored) {}
        }
    }

    // v4.13: fade-in helpers (see fields above). A held-pause talk
    // never reaches startFadeIn — onPrepared sets its volume
    // straight, since it makes no sound until resume (which already
    // sets full volume). A duck change mid-ramp aborts without
    // restoring: the duck handler just set the volume it owns.
    private void startFadeIn(final float target) {
        cancelFadeIn(false);
        fadeInDucked = ducked;
        final int[] step = {0};
        try { if (player != null) player.setVolume(0f, 0f); } catch (Exception ignored) {}
        fadeInRunnable = new Runnable() {
            @Override public void run() {
                if (player == null || !prepared) { fadeInRunnable = null; return; }
                if (ducked != fadeInDucked) { fadeInRunnable = null; return; }
                step[0]++;
                if (step[0] >= 7) {
                    try { player.setVolume(target, target); } catch (Exception ignored) {}
                    fadeInRunnable = null;
                    return;
                }
                float v = target * step[0] / 7f;
                try { player.setVolume(v, v); } catch (Exception ignored) {}
                fadeInHandler.postDelayed(this, 100);
            }
        };
        fadeInHandler.postDelayed(fadeInRunnable, 100);
    }
    private void cancelFadeIn(boolean restore) {
        if (fadeInRunnable != null) fadeInHandler.removeCallbacks(fadeInRunnable);
        fadeInRunnable = null;
        if (restore && player != null && prepared) {
            try { player.setVolume(ducked ? 0.2f : 1.0f, ducked ? 0.2f : 1.0f); } catch (Exception ignored) {}
        }
    }
    // v4.13: soft-pause helpers — only toggle() (UI taps) uses
    // these; pause() itself stays instant for system callers. A
    // re-tap mid-fade cancels in toggle() and resumes at once, so
    // a fast pause-play never strands a talk paused by the fade's
    // tail. The fade-end pause() saves the spot and updates the
    // notification exactly like a direct pause, then the volume is
    // restored so a later resume never opens at silence (resume()
    // only sets volume when clearing a duck).
    private void startPauseFade() {
        if (player == null || !prepared || !isPlaying()) { pause(); return; }
        cancelPauseFade();
        pauseFading = true;
        // v8.60: same live-duck base as the sleep fade — a duck
        // arriving inside this 300ms ramp used to be overwritten
        // by the next step's captured startVol.
        final int[] step = {0};
        pauseFadeRunnable = new Runnable() {
            @Override public void run() {
                if (player == null || !prepared || !isPlaying()) { pauseFading = false; pauseFadeRunnable = null; return; }
                step[0]++;
                if (step[0] >= 4) {
                    pauseFading = false;
                    pauseFadeRunnable = null;
                    pause();
                    try { if (player != null) player.setVolume(ducked ? 0.2f : 1.0f, ducked ? 0.2f : 1.0f); } catch (Exception ignored) {}
                    return;
                }
                float v = (ducked ? 0.2f : 1.0f) * (1f - step[0] / 4f);
                try { player.setVolume(v, v); } catch (Exception ignored) {}
                pauseFadeHandler.postDelayed(this, 75);
            }
        };
        pauseFadeHandler.postDelayed(pauseFadeRunnable, 75);
    }
    private void cancelPauseFade() {
        if (pauseFadeRunnable != null) pauseFadeHandler.removeCallbacks(pauseFadeRunnable);
        pauseFadeRunnable = null;
        pauseFading = false;
    }

    // v1.24: milliseconds left on the sleep timer, 0 when none is set
    public long getSleepRemainingMs() {
        if (sleepEndMs == 0) return 0;
        long rem = sleepEndMs - android.os.SystemClock.elapsedRealtime();
        return rem > 0 ? rem : 0;
    }

    // v1.80: the minutes the running timer was set with (see field).
    public int getSleepSetMinutes() { return sleepSetMins; }

    // v1.81: milliseconds left on a pause-suspended countdown (0 =
    // none) — resume() retimes it to the current setting (above).
    public long getSleepSuspendedMs() { return sleepSuspendedMs; }

    // v1.81: replace a pause-suspended countdown with the current
    // setting's full length (0 cancels it outright). Only the
    // suspended remainder changes — nothing ticks while paused, the
    // new length is simply what resume() re-arms.
    public void retimeSuspendedSleep(int mins) {
        if (sleepSuspendedMs <= 0) return;
        if (mins <= 0) {
            sleepSuspendedMs = 0;
            sleepSetMins = 0;
            // v12.17: the strip mark dies with the suspension — every other suspension
            // clear (setSleepMinutes, stop, onPrepared kill, fire) clears liveSleepActive
            // with it, but this one left it true, so a timer turned off while paused
            // (Settings long-press / reset-all / strip pick, whose async off-sync had
            // not landed before the resume) left a bare 😴 on all four mini-player
            // strips with no timer behind it — and the flag survived a new talk's
            // onPrepared too (both its kill branches need a live countdown/suspension
            // to fire). The sleepEndMs guard mirrors cancelSleepEndOfTalk: a running
            // countdown keeps its own mark.
            if (sleepEndMs == 0) liveSleepActive = false;
        } else {
            sleepSetMins = mins;
            sleepSuspendedMs = mins * 60L * 1000L;
        }
        syncLiveSleepRemaining(); // v4.72
    }

    private void applySpeed() {
        if (player == null || !prepared) return;
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                PlaybackParams pp = new PlaybackParams();
                pp.setSpeed(speed);
                player.setPlaybackParams(pp);
            } catch (Exception ignored) {}
        }
    }

    private void notifyState() {
        if (listener != null) listener.onStateChanged(isPlaying(), currentTitle);
        // v12.67: widget push — every state edge re-renders the
        // home-screen widget (play / prepared / completion /
        // toggle / pause / resume / stats flush). Not called from
        // the 1s position tick, so the widget is never polled.
        DhammaWidgetProvider.refresh(this);
    }

    // v1.56: end the session from outside the notification — deleting
    // the currently-playing downloaded file used to leave a stale track
    // pointing at a file that no longer exists (pressing play then
    // failed silently). The library calls this instead of just pausing.
    public void stopAndClear() {
        // v11.46: a cleared session ends its mobile-data approval
        // and any held resume with it — a later listen answers
        // the gate fresh. v12.10: the deferred advance ends with
        // it too — a stopped session must never auto-play when a
        // download lands later.
        gateApprovedUrl = null;
        gateHeldResumeUrl = null;
        awaitAdvanceClear(this);
        // v12.66: bank the stopped talk's seconds before the
        // session is torn down.
        flushListenStats();
        stopPlayback();
        // v1.76: a cleared session used to leave the started service
        // idling forever (15s tick, no track, no notification) until
        // the app process died — stop it too. A later talk re-starts
        // it via play() (see above), so background playback still
        // survives leaving the screen after a clear.
        stopSelf();
    }

    // v1.89: clear the saved resume spot only when it really belongs
    // to the track ending here. The old unconditional clear wiped
    // ANOTHER talk's spot in one real case: talk A paused (its spot
    // saved), then talk B played briefly (<5s, so no checkpoint and
    // pause()'s guarded clear never touched A) and stopped from the
    // notification — stopping B erased A's resume position, so
    // reopening A restarted from 0. Match by stream URL, or by the
    // downloaded file a stream URL maps to (offline playback stores
    // the local path as the current URL). Callers must call this
    // before currentUrl is nulled below.
    // v8.31: do the dlMap entries link these two forms of one
    // talk, without touching the filesystem? Saved-path ->
    // stream reverses the map by filename (exactly one claimant
    // required); saved-stream -> path builds the mapped path
    // directly. Either direction may be the saved form and the
    // other the current one; equal forms are already matched
    // by the caller, so this only ever links unlike forms.
    private boolean dlMapLinks(String saved, String current) {
        try {
            org.json.JSONObject map = new org.json.JSONObject(
                    getSharedPreferences("dhamma", MODE_PRIVATE)
                            .getString("dlMap", "{}"));
            if (saved.startsWith("/")) {
                String fn = new java.io.File(saved).getName();
                String found = null;
                int count = 0;
                java.util.Iterator<String> keys = map.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    if (map.optString(k, "").equals(fn)) {
                        found = k;
                        count++;
                    }
                }
                return count == 1 && found.equals(current);
            }
            String fn = map.optString(saved, "");
            if (fn.length() == 0) return false;
            java.io.File f = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma/" + fn);
            return f.getAbsolutePath().equals(current);
        } catch (Exception ignored) { return false; }
    }

    private void clearSavedPosForCurrent() {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String saved = sp.getString("lastPosUrl", "");
            if (saved.length() == 0) return;
            boolean match = saved.equals(currentUrl);
            if (!match && currentUrl != null && saved.length() > 0) {
                java.io.File f = offlineFileFor(saved);
                match = f != null && f.getAbsolutePath().equals(currentUrl);
            }
            // v8.29: the reverse direction, mirroring savedSeekFor
            // (v2.03) — the old check only resolved the SAVED form
            // to the current one, so the finisher was asymmetric
            // with the resume lookup that reads the same spot: a
            // path-form spot could survive a streamed finish even
            // while the file still linked the two forms. (Today's
            // Room/library/advance paths all prefer the local file
            // when one exists, so the delete-time translation in
            // pruneDlMapForFilenames is the fix for the known
            // orphan; this keeps the finisher symmetric with the
            // lookup for any play that arrives streamed.) A hit
            // here names this talk's own file — offlineFileFor is
            // keyed by the URL itself — so the v1.89 guard (never
            // clear another talk's spot) still holds.
            if (!match && currentUrl != null && saved.length() > 0) {
                java.io.File g = offlineFileFor(currentUrl);
                match = g != null && saved.equals(g.getAbsolutePath());
            }
            // v8.31: dlMap-only cross-form match, NO file-existence
            // check — the v8.30 class one step further out. Both
            // branches above resolve through offlineFileFor, which
            // returns null unless the file still exists, so a talk
            // paused OFFLINE (lastPosUrl = the file path), whose
            // file was then deleted OUTSIDE the app (the dlMap
            // link survives — only in-app deletes prune it,
            // v8.29), and later finished STREAMED, matched
            // nothing here: the global spot survived AND
            // clearTalkPos never ran, so the per-talk stream +
            // path spots survived too, and re-downloading the
            // talk resurrected the old minute through the
            // per-talk lookup even though the talk had been
            // finished. The dlMap link itself is the identity
            // proof — a filename two stream URLs claim is
            // ambiguous and skips, exactly like v8.24's reverse
            // seed — so the v1.89 guard (never clear another
            // talk's spot) still holds, and a talk with no dlMap
            // entry keeps today's behaviour exactly.
            if (!match && currentUrl != null && saved.length() > 0) {
                match = dlMapLinks(saved, currentUrl);
            }
            if (match) {
                sp.edit().putInt("lastPos", 0)
                        .putString("lastPosUrl", "").apply();
                clearTalkPos(currentUrl); // v5.34: finished/stopped talks resume from the start
            }
        } catch (Exception ignored) {}
    }

    // v7.21: mark the finished talk played HERE, at the service
    // level — the only end consumers used to be the Room and
    // library polls, and neither runs while the listener sits on
    // Main or Talks (Fx.go finishes the Room, and the strips only
    // sync state). A talk that ended while away was never marked:
    // its ✓ never landed, and the unlistened batch verbs (v6.58 /
    // v7.04 / v7.05) kept treating a fully heard talk as fresh.
    // Worse, a strip/notification Play tap on the ended track
    // reaches resume(), which clears trackEnded (v1.55 replay
    // rule) before any poll can consume it, so the end was lost
    // for good unless the talk was heard to the end a second time.
    // onCompletion is the one place that fires for every natural
    // end regardless of foreground screen, so the mark lands here;
    // the Room/library blocks keep their own marks (set add =
    // no-op duplicate) and still own auto-advance + toasts, which
    // need their lists and stay activity-side.
    private void markPlayedForCurrent() {
        try {
            if (currentUrl == null || currentUrl.length() == 0) return;
            String key = currentUrl;
            if (currentUrl.startsWith("/")) {
                // Library file: playedTalks is keyed by stream URL,
                // so reverse the dlMap exactly like the library end
                // block (v4.29); a file with no entry simply has
                // no URL key to mark.
                // v8.42: exactly-one match, never the first match —
                // the v8.41 strip rule, extended to the played mark
                // it deliberately deferred. A dual claim (two talks
                // mapped to one file, creatable by the delivered
                // v8.39) made the old first-match mark the WRONG
                // talk played: the file's audio is the talk that
                // landed last, yet the first claimant earned the
                // ✓ while the heard talk stayed unlistened. An
                // ambiguous file now earns no ✓ at all (the
                // library end block below is fixed the same way,
                // so the service mark cannot be re-added there),
                // and a single-claim file marks exactly as before.
                String fn = new java.io.File(currentUrl).getName();
                org.json.JSONObject map = new org.json.JSONObject(
                        getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlMap", "{}"));
                java.util.Iterator<String> keys = map.keys();
                String hit = null;
                int matches = 0;
                while (keys.hasNext()) {
                    String u = keys.next();
                    if (fn.equals(map.optString(u, ""))) { hit = u; matches++; }
                }
                if (matches != 1) return;
                key = hit;
            }
            android.content.SharedPreferences pp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            java.util.Set<String> played = new java.util.HashSet<String>(
                    pp.getStringSet("playedTalks", new java.util.HashSet<String>()));
            if (played.add(key)) pp.edit().putStringSet("playedTalks", played).apply();
        } catch (Exception ignored) {}
    }

    private void stopPlayback() {
        livePlaying = false; // v4.16 (covers stopAndClear + notification stop)
        // v4.04: a stop ends any sleep fade with it.
        cancelSleepFade();
        // v4.13: and both UI fades — a stop must never leave a
        // runnable holding the (soon released) player.
        cancelFadeIn(false);
        cancelPauseFade();
        if (mediaSession != null) {
            try { mediaSession.setActive(false); } catch (Exception ignored) {}
        }
        abandonFocus();
        // v1.54: stopping from the notification used to leave sleepEndMs
        // set — the stale deadline then blocked the NEXT session's sleep
        // timer, because play() only starts a timer when sleepEndMs == 0.
        // A stop really ends the timer.
        if (sleepRunnable != null) sleepHandler.removeCallbacks(sleepRunnable);
        sleepRunnable = null;
        sleepEndMs = 0;
        sleepSetMins = 0;
        sleepSuspendedMs = 0;
        pausedSleepPrefMins = -1; // v7.26: a stop ends the pause snapshot too
        liveSleepActive = false;
        // v4.82: a stop ends the one-shot with the session.
        sleepEndOfTalk = false;
        sleepEndFired = false; // v8.38
        liveSleepEndOfTalk = false;
        // v11.77: a stop ends a pending bedtime stop with the session too.
        sleepStopPending = false;
        syncLiveSleepRemaining(); // v4.72
        trackEnded = false;
        liveEnded = false;
        // v1.67: a stop really ends the session's error state too — the
        // old code cleared the track but left trackError true and a
        // stale pending seek behind, so the cleared session still
        // carried the dead track's state into whatever came next.
        trackError = false;
        pendingSeekMs = 0;
        holdPaused = false;
        // v1.57: an explicit "stop completely" really ends the session —
        // the saved resume position used to survive it, so opening the
        // stopped talk again from Mp3 Player silently resumed mid-talk
        // instead of starting from the beginning. Pauses still save.
        // v1.89: guarded — stopping one talk must not erase another
        // talk's saved spot (see clearSavedPosForCurrent above).
        clearSavedPosForCurrent();
        // v10.55: own-spot rule as in completion (v10.52) —
        // a guarded global miss left the per-talk spot alive, so
        // reopening the stopped talk resumed mid-talk through
        // the per-talk fallback instead of starting fresh.
        // Own URL/forms only; another talk untouched.
        clearTalkPos(currentUrl);
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        prepared = false;
        bufferedPercent = 0; // v4.06: a stopped talk has nothing buffered
        currentTitle = null;
        currentUrl = null;
        liveHasTrack = false; // v10.40: mirror for the no-bind strips
        liveTitle = null; // v12.67: widget mirror ends with the track
        livePositionMs = 0; liveDurationMs = 0; liveUrl = null; // v10.43: a stop ends the track, so the bar mirrors must end with it — livePosRunnable only re-posts while playing, so without this they froze at the stopped talk's values and Fx's miniSeekBar swallowed resume taps on the bar zone in the bound-trackless window (onDestroy already cleared them at death, v4.30)
        stopForeground(true);
        // v12.67: widget push — a stop falls back to the stored
        // last talk (play icon, resume on tap).
        DhammaWidgetProvider.refresh(this);
    }

    // v1.70: MediaSession plumbing (framework only, API 21+).
    private void ensureSession() {
        if (mediaSession != null) return;
        mediaSession = new android.media.session.MediaSession(this, "dhamma");
        mediaSession.setCallback(new android.media.session.MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            // v1.72: the v1.70 media card showed a live seekbar the
            // user could not drag — the session never advertised
            // SEEK_TO and had no onSeekTo, so scrubbing did nothing.
            // Dragging it now seeks the playing talk like the Room's
            // own seek bar (unprepared seeks queue via seekTo).
            // v10.46: clamp + trackless guard, the ACTION_SEEK mirror —
            // the notification seek (v4.73) clamps to the known duration
            // and ends a trackless service, but this headset/car seek
            // passed the raw position straight to seekTo: a scrub past
            // the end (session extrapolation) sought past duration, and
            // a stale session scrub with no track cleared ended state
            // for nothing. A trackless seek
            // now returns (no stopSelf: a session callback never
            // started the service); a negative scrub clamps to 0 and
            // an over-duration scrub to the known duration, exactly
            // like ACTION_SEEK; an unknown (unprepared) duration still
            // passes through, since seekTo already queues it, and a
            // failed track still retries via seekTo's v1.85 branch.
            // v10.59: a headset/car scrub to 0 while a talk still
            // buffers used to vanish — seekTo(0) is deliberately a
            // no-op while unprepared (v1.69: a 0 drag on a
            // freshly-reset bar must not wipe a resume spot), so
            // the pending resume seek survived and the talk still
            // opened where it left off. But since v10.58 the
            // session REPORTS the resume spot, so a scrub from
            // there to 0 is a deliberate restart, not a reset-bar
            // artifact — and v10.57 built restartFromStart() for
            // exactly this explicit intent (clears the pending seek
            // + this talk's saved spots, preserves holdPaused, so
            // a held buffering talk still opens paused). Route a
            // buffering 0-scrub through it and push the session at
            // once (it now reads 0, not the stale resume spot).
            // isBuffering() already excludes failed tracks, so a
            // dead stream still takes seekTo's v1.85 retry; a
            // prepared 0-scrub keeps plain seekTo(0).
            @Override public void onSeekTo(long pos) {
                if (currentTitle == null) return;
                if (pos < 0) pos = 0;
                int d46 = getDuration();
                if (d46 > 0 && pos > d46) pos = d46;
                if (pos == 0 && isBuffering()) {
                    restartFromStart();
                    updateSession();
                    return;
                }
                seekTo((int) pos);
            }
            @Override public void onSkipToNext() {
                // v11.77: a headset/car Next while the app is backgrounded used to die silently —
                // startActivity() from a background service is blocked on Android 10+ (background
                // activity launch), and the try/catch swallowed the block with no word. The
                // notification's own Next still works (its PendingIntent is exempt), so the
                // headset buttons looked randomly broken. A foreground session keeps the activity
                // route below (visible window = exempt, and it preserves the Room/library Next
                // toasts, fades and gate dialogs); a backgrounded session advances in-service
                // instead, through the same pick order as a natural end (queue > redo/shuffle >
                // list-next) but with manual semantics (autoplay-off still advances, the last
                // talk wraps, repeat-one still steps on). A non-Room, non-library track still
                // advances nothing here.
                if (isLibSession()) {
                    if (!DownloadedActivity.topLib) { advanceLibInBackground79(true); return; }
                    try {
                        Intent next = new Intent(AudioService.this, DownloadedActivity.class);
                        next.setAction(DownloadedActivity.ACTION_NEXT);
                        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(next);
                    } catch (Exception ignored) {}
                    return;
                }
                if (!isRoomTrack()) return;
                try {
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    String lastName = sp.getString("lastName", "");
                    if (RoomActivity.topRoomSayadaw == null
                            || !RoomActivity.topRoomSayadaw.equals(lastName)) {
                        advanceInBackground58(true);
                        return;
                    }
                    String lastPage = sp.getString("lastPage", "");
                    if (lastPage.length() > 0) {
                        Intent next = new Intent(AudioService.this, RoomActivity.class);
                        next.setAction(RoomActivity.ACTION_NEXT);
                        next.putExtra("name", sp.getString("lastName", ""));
                        next.putExtra("group", sp.getString("lastGroup", ""));
                        next.putExtra("url", lastPage);
                        next.putExtra("photo", sp.getString("lastPhoto", ""));
                        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(next);
                    }
                } catch (Exception ignored) {}
            }
            // v4.23: Previous parity — the mirror of onSkipToNext
            // above (same two routes, same flags, same early
            // return for a non-Room, non-library track). Before
            // this, a headset/car Previous press reached a
            // MediaSession with no such callback and died silently,
            // while Next worked everywhere.
            @Override public void onSkipToPrevious() {
                // v11.77: same background-activity-launch block as onSkipToNext above — a
                // headset/car Previous while backgrounded died silently. Foreground sessions
                // keep the activity route (visible window = exempt); backgrounded sessions step
                // back in-service (manualSkipPrevRoom / manualSkipPrevLib: >3s restarts the talk,
                // otherwise the previous talk/file with wrap).
                if (isLibSession()) {
                    if (!DownloadedActivity.topLib) { manualSkipPrevLib(); return; }
                    try {
                        Intent prev = new Intent(AudioService.this, DownloadedActivity.class);
                        prev.setAction(DownloadedActivity.ACTION_PREV);
                        prev.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(prev);
                    } catch (Exception ignored) {}
                    return;
                }
                if (!isRoomTrack()) return;
                try {
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    String lastName = sp.getString("lastName", "");
                    if (RoomActivity.topRoomSayadaw == null
                            || !RoomActivity.topRoomSayadaw.equals(lastName)) {
                        manualSkipPrevRoom();
                        return;
                    }
                    String lastPage = sp.getString("lastPage", "");
                    if (lastPage.length() > 0) {
                        Intent prev = new Intent(AudioService.this, RoomActivity.class);
                        prev.setAction(RoomActivity.ACTION_PREV);
                        prev.putExtra("name", sp.getString("lastName", ""));
                        prev.putExtra("group", sp.getString("lastGroup", ""));
                        prev.putExtra("url", lastPage);
                        prev.putExtra("photo", sp.getString("lastPhoto", ""));
                        prev.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(prev);
                    }
                } catch (Exception ignored) {}
            }
            @Override public void onStop() { stopPlayback(); stopSelf(); }
        });
    }

    // v1.82: the on-disk file for a saved stream URL, or null — the
    // raw basename first (the Wi-Fi download destination uses it
    // verbatim), then the query/fragment-stripped name, then the
    // %XX-decoded stripped name. The old call sites only tried the
    // raw basename, so a downloaded talk whose link carries a query
    // string never matched its file.
    // v1.90: flat-basename matching is also how two sayadaws' talks
    // with the same basename collided (one file, wrong audio, false
    // offline marks). Downloads now record their URL→filename map
    // (see RoomActivity): the mapped name wins when it exists, and
    // a basename the map claims for a DIFFERENT url never matches
    // here. Unmapped (legacy) files keep the old basename fallback.
    // v11.59: filenames of this app's in-progress downloads
    // (RUNNING/PAUSED/PENDING) — the exact v11.55/v11.56 rule,
    // restated here because AudioService never owned a downloaded
    // set: destination under Downloads/Dhamma via the
    // percent-decoded local URI, or the dlMap filename when the job
    // exposes no destination yet (a Wi-Fi-waiting PENDING job).
    // A DM query failure fails open (empty set = the old
    // behaviour), never an empty shelf.
    private java.util.Set<String> inProgressDlFilenames59() {
        // v12.62 (Finding 4): cached wrapper — see the field note
        // above dlAwaitedReceiver. Bursts (queue advance, card
        // rebuilds, shelf scans) hit the cache; a failed query
        // returns null and is never cached (fail-open empty set,
        // as before), so the next call re-queries.
        long now = android.os.SystemClock.elapsedRealtime();
        java.util.Set<String> cached = inProgCache62;
        if (cached != null && now - inProgCacheAt62 < INPROG_TTL_MS62) return cached;
        java.util.Set<String> fresh = queryInProgressDlFilenames62();
        if (fresh == null) return new java.util.HashSet<String>();
        inProgCache62 = fresh;
        inProgCacheAt62 = now;
        return fresh;
    }

    // v12.62: the uncached query, split out of inProgressDlFilenames59
    // (scan body unchanged): a DM query that throws or returns no
    // cursor returns null — the wrapper fails open with an empty set
    // and does NOT cache it, so the next call re-queries. A failure
    // mid-scan still returns the partial set, as before (the provider
    // is dead in that case, and the TTL lapses on its own).
    private java.util.Set<String> queryInProgressDlFilenames62() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            java.util.Map<String, String> dlMap = new java.util.HashMap<String, String>();
            try {
                org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
                java.util.Iterator<String> keys = map.keys();
                while (keys.hasNext()) {
                    String u = keys.next();
                    dlMap.put(u, map.optString(u, ""));
                }
            } catch (Exception ignored) {}
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            android.database.Cursor c;
            try {
                c = dm.query(new android.app.DownloadManager.Query());
            } catch (Exception ignored) {
                return null;
            }
            if (c == null) return null;
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
        } catch (Exception ignored) {}
        return out;
    }

    private java.io.File offlineFileFor(String url) {
        try {
            org.json.JSONObject map = new org.json.JSONObject(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlMap", "{}"));
            // v11.59: a talk still downloading is NOT downloaded
            // yet — the last surface of the v11.55/v11.56/v11.57/
            // v11.58 partial-file class, on the service's shared
            // lookup that every background advance and the
            // data-saver gate read. A live job's filename is
            // skipped here, mapped name and unclaimed candidates
            // alike, so the caller streams the talk (or defers
            // to the Room poll's gate question on mobile data)
            // instead of playing a truncated file.
            java.util.Set<String> inProg = inProgressDlFilenames59();
            if (map.has(url)) {
                java.io.File f = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma/" + map.getString(url));
                if (f.exists() && !inProg.contains(map.getString(url))) return f;
            }
            java.util.Set<String> claimedByOther = new java.util.HashSet<String>();
            java.util.Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!k.equals(url)) claimedByOther.add(map.optString(k, ""));
            }
            String base = url.substring(url.lastIndexOf('/') + 1);
            String stripped = base;
            int q = stripped.indexOf('?');
            if (q >= 0) stripped = stripped.substring(0, q);
            int h = stripped.indexOf('#');
            if (h >= 0) stripped = stripped.substring(0, h);
            String decoded = stripped;
            try { decoded = java.net.URLDecoder.decode(stripped.replace("+", "%2B"), "UTF-8"); }
            catch (Exception ignored) {}
            String[] names = decoded.equals(stripped)
                    ? (stripped.equals(base) ? new String[]{base} : new String[]{base, stripped})
                    : new String[]{base, stripped, decoded};
            for (String name : names) {
                if (claimedByOther.contains(name)) continue;
                if (inProg.contains(name)) continue; // v11.59: still downloading, not offline yet
                java.io.File f = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma/" + name);
                if (f.exists()) return f;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // v1.70: sayadaw name for the session, only when the playing
    // track really is that sayadaw's talk (same guard as the
    // notification text — a library session never names the wrong
    // sayadaw).
    private String sessionArtist() {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String lastUrl = sp.getString("lastUrl", "");
            String sd = sp.getString("lastName", "");
            if (sd.length() == 0) return "";
            boolean ok = currentUrl == null || currentUrl.equals(lastUrl);
            if (!ok && lastUrl.length() > 0) {
                // v1.82: same candidate names as the notification
                // guard above — a query-string link used to fail
                // this match and drop the sayadaw name.
                java.io.File f = offlineFileFor(lastUrl);
                ok = f != null && f.getAbsolutePath().equals(currentUrl);
            }
            return ok ? sd : "";
        } catch (Exception ignored) { return ""; }
    }

    // v1.95: is the playing track a Room talk (not a bare library
    // file)? Same guard as the notification tap above — the stream
    // URL itself, or the downloaded file that stream URL maps to
    // (offlineFileFor, map + candidates). A library file playing
    // while an older Room session is remembered is NOT a Room
    // track, even though lastPage/lastUrl are non-empty: its Next
    // used to open the Room only to toast "not in this list" (and,
    // when the Room showed another sayadaw, to yank it to a new
    // sayadaw's list for nothing). No track = not a Room track.
    private boolean isRoomTrack() {
        try {
            if (currentUrl == null) return false;
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String lastUrl = sp.getString("lastUrl", "");
            if (currentUrl.equals(lastUrl)) return true;
            if (lastUrl.length() == 0) return false;
            java.io.File f = offlineFileFor(lastUrl);
            return f != null && f.getAbsolutePath().equals(currentUrl);
        } catch (Exception ignored) { return false; }
    }

    // v1.70: push title/artist/duration/position/state to the
    // session — the system renders elapsed/total + the moving
    // seekbar from this and extrapolates between updates.
    // v2.03: the newest session is a library one (written by
    // DownloadedActivity.saveLastLibrary on every file play; Room
    // plays overwrite it with "room", so the newest always wins).
    private boolean isLibSession() {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            return "lib".equals(sp.getString("lastKind", ""))
                    && sp.getString("lastLibPath", "").length() > 0;
        } catch (Exception ignored) { return false; }
    }

    // v5.99: catalog name -> portrait URL, loaded once per process
    // and then a map lookup only, so the card's 15s rebuilds never
    // re-read the bundled catalog. Names match exactly: dlSayadaw
    // is recorded from the Room's catalog name at download time,
    // so a library file's recorded sayadaw is a catalog name by
    // construction; a name with no catalog entry returns "" and
    // the caller falls back to the initial placeholder, never a
    // guessed face. Cache-only downstream (ImageLoader.cached),
    // so this path never downloads a portrait.
    private static java.util.Map<String, String> libPhotoMap = null;
    // v6.01: public delegate for the Talks strip portrait — the
    // chooser holds no catalog list (Main's `all`), so it resolves
    // the session sayadaw through this same once-per-process map
    // instead of re-parsing the bundled catalog on its 1s tick.
    public static String stripPhotoUrlForSayadaw(android.content.Context ctx, String name) {
        return photoUrlForSayadaw(ctx, name);
    }

    // v12.11: bundled-catalog lookup for a handover target — group, page URL and portrait
    // for a sayadaw name, or null when the name has no catalog entry. The Room's
    // catalogEntryForSayadaw (RoomActivity) in service form: the background foreign handover
    // refuses on a catalog miss exactly like the Room's (queue untouched, defer to the Room
    // poll), and the page/group/photo become the new session's last* anchors, so the
    // notification tap opens the foreign Room on the foreign page. Parsed on demand, no new
    // cache can go stale.
    private static String[] catalogEntryForSayadaw11(android.content.Context ctx, String name) {
        if (name == null || name.length() == 0) return null;
        java.io.InputStream in = null;
        try {
            in = ctx.getAssets().open("sayadaws.json");
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) baos.write(buf, 0, read);
            org.json.JSONArray arr = new org.json.JSONArray(new String(baos.toByteArray(), "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                if (!name.equals(o.optString("name", ""))) continue;
                String group = o.optString("group", "");
                String page = o.isNull("page_url") ? "" : o.optString("page_url", "");
                String photo = o.isNull("photo_url") ? "" : o.optString("photo_url", "");
                if (photo.length() == 0 && !o.isNull("photo")) photo = o.optString("photo", "");
                return new String[]{group, page, photo};
            }
        } catch (Exception ignored) {
        } finally {
            if (in != null) { try { in.close(); } catch (Exception ignored) {} }
        }
        return null;
    }

    private static String photoUrlForSayadaw(android.content.Context ctx, String name) {
        try {
            if (libPhotoMap == null) {
                libPhotoMap = new java.util.HashMap<String, String>();
                java.io.InputStream in = null;
                try {
                    in = ctx.getAssets().open("sayadaws.json");
                    java.io.ByteArrayOutputStream baos =
                            new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = in.read(buf)) > 0) baos.write(buf, 0, read);
                    org.json.JSONArray arr =
                            new org.json.JSONArray(new String(baos.toByteArray(), "UTF-8"));
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject o = arr.getJSONObject(i);
                        String n = o.optString("name", "");
                        String p = o.isNull("photo_url") ? "" : o.optString("photo_url", "");
                        if (p.length() == 0 && !o.isNull("photo")) p = o.optString("photo", "");
                        if (n.length() > 0 && p.length() > 0) libPhotoMap.put(n, p);
                    }
                } finally {
                    if (in != null) { try { in.close(); } catch (Exception ignored) {} }
                }
            }
            String p = libPhotoMap.get(name);
            return p == null ? "" : p;
        } catch (Exception ignored) { return ""; }
    }

    private void updateSession() {
        syncLivePosition(); // v4.30: first, so a null session still moves the mini-player tick
        if (mediaSession == null) return;
        try {
            int dur = getDuration();
            // v10.58: the session position is what headset/car controls
            // display and scrub from — getPosition() reads 0 while a
            // resumed talk still buffers, so the system showed the
            // talk at the start until prepare finished (the v10.57
            // family fixed every in-app decision but left this
            // external one). getEffectivePosition() is the pending
            // seek (resume spot / queued drag) while buffering and
            // the live position exactly once prepared, so prepared
            // talks report identically and a failed/trackless talk
            // still reports 0. In-app display sites (poll, clocks,
            // strips) keep getPosition() per v10.57.
            int pos = getEffectivePosition();
            boolean playing = isPlaying();
            // v1.84: a stream still buffering used to report PAUSED
            // to headset/system controls — the old code only knew
            // playing vs not, so a talk loading after a tap showed
            // as stopped until prepare finished. Report BUFFERING
            // while it loads, like the Room label and the card text
            // already do (a failed stream is not buffering).
            // v10.63: but a HELD buffering talk is paused, not
            // loading-to-play — the v10.61/v10.62 strip fixes made
            // every in-app surface show ▶ for it (strips via
            // livePlaying, the card via its holdPaused-aware rule,
            // the Room via isPlaying()=false), while the session
            // still said BUFFERING, so headset/car controls showed
            // a spinner with a pause affordance and no way to
            // resume from outside the app (their Play routes to
            // onPlay -> resume(), which un-holds). A held talk
            // reports PAUSED, like every other paused talk; an
            // unheld buffering talk still reports BUFFERING, and
            // the position still reads the pending seek.
            int state = playing
                    ? android.media.session.PlaybackState.STATE_PLAYING
                    : (isBuffering() && !holdPaused
                            ? android.media.session.PlaybackState.STATE_BUFFERING
                            : android.media.session.PlaybackState.STATE_PAUSED);
            // v2.03: advertise Next for library sessions too —
            // v1.95 hid it because it landed in the Room to toast
            // "not in this list". It now routes to the library's
            // own advance (see onSkipToNext), so hiding it was
            // throwing away a working control.
            long actions = android.media.session.PlaybackState.ACTION_PLAY
                    | android.media.session.PlaybackState.ACTION_PAUSE
                    | android.media.session.PlaybackState.ACTION_SEEK_TO
                    | android.media.session.PlaybackState.ACTION_STOP;
            if (isRoomTrack() || isLibSession()) {
                actions |= android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT;
                // v4.23: Previous parity — Next was advertised since
                // v2.03 but Previous never was, so a headset/car
                // Previous button did nothing at all (no
                // onSkipToPrevious existed either). Advertise it on
                // exactly the same sessions that advertise Next.
                actions |= android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            }
            android.media.session.PlaybackState.Builder sb =
                    new android.media.session.PlaybackState.Builder()
                    .setActions(actions)
                    .setState(state, pos, speed);
            mediaSession.setPlaybackState(sb.build());
            android.media.MediaMetadata.Builder mb =
                    new android.media.MediaMetadata.Builder()
                    .putString(android.media.MediaMetadata.METADATA_KEY_TITLE,
                            currentTitle != null ? currentTitle : Lang.t(this, "notif_default_title"))
                    .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION,
                            dur > 0 ? dur : 0);
            String artist = sessionArtist();
            if (artist.length() > 0)
                mb.putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, artist);
            // v5.26: session album art — the media carousel draws
            // its artwork from the SESSION metadata, not only from
            // the notification large icon, so a card with no art
            // in the metadata can render as an empty row. Only the
            // current talk's cached portrait, never a stale one.
            // v12.44: the guard below used to prove only that the
            // bitmap matched lastPhoto — the last ROOM sayadaw.
            // A library session never refreshes notiLargeIcon /
            // notiLargeUrl (its card branch caches into notiLibIcon
            // / notiLibName instead, v5.99), so after any Room
            // session a library file by a DIFFERENT sayadaw kept
            // the Room sayadaw's face in the session metadata
            // while the card itself showed the right face and the
            // session artist stayed blank — the carousel showed
            // B's talk with A's portrait. Room tracks keep the
            // existing rule (also the card's resumeOk-first
            // branch, so the overlap session — a library file
            // that IS the last Room talk's download — stays
            // Room, like the card). A library session takes the
            // card's own lib icon, and only when notiLibName
            // equals this file's recorded sayadaw (stripLibSayadaw,
            // exactly-one dlMap claim, v8.41) — unrecorded,
            // dual-claimed or not-yet-cached files get no art,
            // never a guessed face, the same no-art rule as a
            // Room cache miss. Trackless sessions get no art.
            if (isRoomTrack()) {
                if (notiLargeIcon != null && notiLargeUrl.length() > 0) {
                    try {
                        android.content.SharedPreferences sp2 =
                                getSharedPreferences("dhamma", MODE_PRIVATE);
                        if (notiLargeUrl.equals(sp2.getString("lastPhoto", "")))
                            mb.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART,
                                    notiLargeIcon);
                    } catch (Exception ignored) {}
                }
            } else if (isLibSession()) {
                try {
                    android.content.SharedPreferences sp2 =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    String libSd = Fx.stripLibSayadaw(this, sp2);
                    if (libSd != null && libSd.length() > 0
                            && libSd.equals(notiLibName) && notiLibIcon != null)
                        mb.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART,
                                notiLibIcon);
                } catch (Exception ignored) {}
            }
            mediaSession.setMetadata(mb.build());
        } catch (Exception ignored) {}
    }

    // v1.72: M:SS, H:MM:SS for hour+ talks (Room's rule).
    private static String fmtTime(int ms) {
        int s = Math.max(0, ms / 1000);
        int h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return h + ":" + (m < 10 ? "0" + m : "" + m) + ":" + (sec < 10 ? "0" + sec : "" + sec);
        return m + ":" + (sec < 10 ? "0" + sec : "" + sec);
    }

    // v10.74: notification portraits are DISPLAYED at 48dp (~144px on
    // xxxhdpi) but were parcelled at the full 512px grid decode —
    // twice over (large icon + custom-view photo), ~2MB of bitmap
    // in a single binder transaction. An oversize notification
    // parcel is the classic silent drop: no crash, no toast, the
    // shade card simply never appears — exactly the user's
    // "no notification at all" report on delivered v10.43, which
    // first carried the v9.01 custom views. Downscale every
    // notification bitmap to 192px at the single point it enters
    // the card, so the card's total bitmap payload stays ~300KB
    // and the design (48dp photo) is unchanged. Never mutates the
    // shared cached bitmap: createScaledBitmap returns a new one.
    private static android.graphics.Bitmap notiScaled(android.graphics.Bitmap src) {
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

    // v10.75: top-level guard — v10.74 guarded only the tail post, so a
    // throw BEFORE the plain snapshot (channel creation, a PendingIntent
    // in the 20-zone loop, a RemoteViews op) still skipped startForeground
    // entirely: no card, no status icon, FGS timeout risk — exactly the
    // reported "no notification at all" class. The inner method keeps its
    // own v10.74 plain fallback for post-time failures; this wrapper is
    // the last resort for everything earlier, posting a minimal plain
    // card (title + small icon only, no bitmaps, no custom views,
    // tap returns to the Room/library/Main like the normal card) so the service always goes
    // foreground. It never replaces the normal path: it runs only when
    // the inner method throws.
    // v10.76: blocked-channel recovery — the last silent-drop class
    // v10.74/v10.75 cannot catch: posting to an IMPORTANCE_NONE
    // channel succeeds, throws nothing, and shows no card, no
    // status icon, nothing — exactly the reported "no notification
    // at all" while system Allow stays ON (Allow is app-level; a
    // channel can still be blocked on its own). The in-app toggle
    // cannot explain it either (verified this run: notiOn=false
    // only strips buttons/custom views; the plain card still
    // posts), and the toggle key wiring is sound (Settings and
    // the service both use "notiEnabled" in the "dhamma" prefs).
    // So when the in-app toggle says ON but the live channel is
    // NONE, the two disagree and the in-app choice wins: migrate
    // ONCE to a fresh channel id (recreating the same id would
    // restore the blocked state). A fresh install (no channel
    // yet), a healthy channel, and a migrated channel that the
    // user then blocks again all return unchanged — the second
    // block is respected, never fought. The migrated id persists
    // in prefs so later rebuilds post straight to it.
    // v10.77: the migration must NOT be gated on the in-app
    // toggle — v10.76 was, and that left one silent-drop class
    // open: the toggle only strips buttons/custom views, the
    // plain card still posts with it OFF (see showNotificationInner
    // below, whose startForeground runs unconditionally), so a
    // blocked channel + toggle OFF meant no card AND no recovery,
    // while toggle ON recovered. The toggle's own status is still
    // unconfirmed on the user's phone, so the v10.76 gate could
    // silently be the reason his report survives v10.76. A NONE
    // channel now migrates regardless of the toggle; the
    // second-block rule above is untouched, so a user who blocks
    // the migrated channel again is still respected.
    private String resolveChannelId() {
        try {
            if (Build.VERSION.SDK_INT < 26) return CHANNEL_ID;
            android.content.SharedPreferences sp = getSharedPreferences("dhamma", MODE_PRIVATE);
            String id = sp.getString("notiChannelId", CHANNEL_ID);
            if (id == null || id.length() == 0) id = CHANNEL_ID;
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return id;
            NotificationChannel ch = nm.getNotificationChannel(id);
            if (ch == null) return id;
            if (ch.getImportance() == NotificationManager.IMPORTANCE_NONE
                    && CHANNEL_ID.equals(id)) {
                try { nm.deleteNotificationChannel(CHANNEL_ID); } catch (Exception ignored) {}
                NotificationChannel nc = new NotificationChannel(CHANNEL_ID_FALLBACK,
                        Lang.t(this, "notif_channel_name"), NotificationManager.IMPORTANCE_DEFAULT);
                try { nc.setDescription(Lang.t(this, "notif_channel_desc")); } catch (Exception ignored) {}
                nm.createNotificationChannel(nc);
                sp.edit().putString("notiChannelId", CHANNEL_ID_FALLBACK).apply();
                return CHANNEL_ID_FALLBACK;
            }
            return id;
        } catch (Exception ignored) { return CHANNEL_ID; }
    }

    private void showNotification(boolean playing) {
        try {
            showNotificationInner(playing);
        } catch (Exception ignored) {
            try {
                String chanId = resolveChannelId();
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                        if (nm != null) nm.createNotificationChannel(new NotificationChannel(chanId,
                                Lang.t(this, "notif_channel_name"), NotificationManager.IMPORTANCE_DEFAULT));
                    } catch (Exception ignored2) {}
                }
                Notification.Builder b;
                if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, chanId);
                else b = new Notification.Builder(this);
                String title = currentTitle != null ? currentTitle : Lang.t(this, "notif_default_title");
                b.setSmallIcon(android.R.drawable.ic_media_play)
                 .setOnlyAlertOnce(true)
                 .setContentTitle(title)
                 .setCategory(Notification.CATEGORY_TRANSPORT)
                 .setVisibility(Notification.VISIBILITY_PUBLIC)
                 .setOngoing(true);
                // v10.96: the fallback card previously had NO tap target
                // (its comment claimed one) -- an ongoing card that
                // could not be swiped away and did nothing on tap.
                // Same three-branch target as the normal card
                // (Room / library / Main); a failure here still posts
                // the card, since startForeground below is outside
                // this try.
                try {
                    android.content.SharedPreferences fsp = getSharedPreferences("dhamma", MODE_PRIVATE);
                    String fPage = fsp.getString("lastPage", "");
                    String fUrl = fsp.getString("lastUrl", "");
                    android.content.Intent fOpen;
                    // v10.97: the v10.96 Room branch matched only the
                    // stream URL, so a Room talk playing from its
                    // downloaded file (currentUrl = on-disk path)
                    // fell through to the library branch below.
                    // Mirror the normal card's v1.82 resumeOk: stream
                    // match, or the file that stream URL maps to via
                    // offlineFileFor (map + basename candidates).
                    boolean fResumeOk = currentUrl == null || currentUrl.equals(fUrl);
                    if (!fResumeOk && fUrl.length() > 0) {
                        java.io.File ff = offlineFileFor(fUrl);
                        fResumeOk = ff != null && ff.getAbsolutePath().equals(currentUrl);
                    }
                    if (fPage.length() > 0 && fResumeOk) {
                        fOpen = new android.content.Intent(this, RoomActivity.class);
                        fOpen.putExtra("name", fsp.getString("lastName", ""));
                        fOpen.putExtra("group", fsp.getString("lastGroup", ""));
                        fOpen.putExtra("url", fPage);
                        fOpen.putExtra("photo", fsp.getString("lastPhoto", ""));
                        fOpen.putExtra("autoUrl", fUrl);
                        fOpen.putExtra("autoTitle", fsp.getString("lastTitle", ""));
                    } else if (currentUrl != null && new java.io.File(currentUrl).isFile()) {
                        fOpen = new android.content.Intent(this, DownloadedActivity.class);
                        fOpen.putExtra("jumpPath", currentUrl);
                    } else {
                        fOpen = new android.content.Intent(this, MainActivity.class);
                    }
                    fOpen.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    android.app.PendingIntent fPi = android.app.PendingIntent.getActivity(this, 2, fOpen,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                    b.setContentIntent(fPi);
                } catch (Exception ignored4) {}
                startForeground(NOTIF_ID, b.build());
            } catch (Exception ignored3) {}
        }
    }

    private void showNotificationInner(boolean playing) {
        String chanId = resolveChannelId();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(chanId,
                    Lang.t(this, "notif_channel_name"), NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription(Lang.t(this, "notif_channel_desc"));
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            try { nm.deleteNotificationChannel("playback"); } catch (Exception ignored) {}
            nm.createNotificationChannel(ch);
        }
        Intent toggle = new Intent(this, AudioService.class);
        toggle.setAction(ACTION_TOGGLE);
        PendingIntent togglePi = PendingIntent.getService(this, 0, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, AudioService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean notiOn = getSharedPreferences("dhamma", MODE_PRIVATE).getBoolean("notiEnabled", true);
        // v1.42: tapping the notification returns to the Room for the
        // last talk (with its saved position) instead of the main list —
        // but only when the playing track really is that last talk, so a
        // downloaded-library session never jumps to the wrong sayadaw.
        android.content.SharedPreferences sp = getSharedPreferences("dhamma", MODE_PRIVATE);
        String lastPage = sp.getString("lastPage", "");
        String lastUrl = sp.getString("lastUrl", "");
        boolean resumeOk = currentUrl == null || currentUrl.equals(lastUrl);
        if (!resumeOk && lastUrl.length() > 0) {
            // v1.82: same candidate names as the Room's localPathFor —
            // the raw basename alone missed a downloaded talk whose
            // link carries a query string, so the tap fell back to
            // Main instead of the Room for that talk.
            java.io.File f = offlineFileFor(lastUrl);
            resumeOk = f != null && f.getAbsolutePath().equals(currentUrl);
        }
        Intent open;
        // v1.92: a downloaded-library session used to fall through to
        // Main here (resumeOk is false: a library file has no sayadaw
        // page), and Main's Mp3 Player nav then refused it too (no
        // lastPage), so tapping the card while a downloaded file
        // played left no tap-path back to what was playing at all.
        // A current track that is an existing on-disk file — the
        // library is the only screen that plays bare files, since a
        // Room offline session already resolves via resumeOk above —
        // returns to the library instead.
        if (lastPage.length() > 0 && resumeOk) {
            open = new Intent(this, RoomActivity.class);
            open.putExtra("name", sp.getString("lastName", ""));
            open.putExtra("group", sp.getString("lastGroup", ""));
            open.putExtra("url", lastPage);
            open.putExtra("photo", sp.getString("lastPhoto", ""));
            open.putExtra("autoUrl", lastUrl);
            open.putExtra("autoTitle", sp.getString("lastTitle", ""));
            // v1.74: the session's Next intent already carries
            // NEW_TASK (v1.70) but these two did not — from a
            // service PendingIntent the tap/Next could fail to
            // bring the Room up after the app was swiped away.
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        } else if (currentUrl != null && new java.io.File(currentUrl).isFile()) {
            open = new Intent(this, DownloadedActivity.class);
            // v1.94: same jump as Main's Mp3 Player nav — the card
            // tap lands on the playing file, not the library top.
            open.putExtra("jumpPath", currentUrl);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        } else {
            open = new Intent(this, MainActivity.class);
            // v1.74: same NEW_TASK as the Room tap above.
            // v1.90: plus the Room intent's CLEAR_TOP|SINGLE_TOP —
            // the old flags stacked a duplicate Main on top of the
            // library session every time the card was tapped, so
            // Back walked through Main twice instead of leaving.
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        PendingIntent openPi = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // v1.70: Next goes to the Room (it owns the talk list) —
        // same sayadaw extras as the tap-to-open intent, no autoUrl
        // (the Room advances from the service's current track).
        // Only when a last sayadaw is known at all.
        PendingIntent nextPi = null;
        // v2.03: Next is back for library sessions too — v1.95
        // removed the button because it landed in the Room to toast
        // "not in this list". It now routes to the library's own
        // advance instead; a Room talk — streamed or from its
        // downloaded file — keeps its Next as before.
        if (isLibSession()) {
            Intent next = new Intent(this, DownloadedActivity.class);
            next.setAction(DownloadedActivity.ACTION_NEXT);
            // v1.74: NEW_TASK like the session's own Next intent.
            next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            nextPi = PendingIntent.getActivity(this, 4, next,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else if (lastPage.length() > 0 && isRoomTrack()) {
            Intent next = new Intent(this, RoomActivity.class);
            next.setAction(RoomActivity.ACTION_NEXT);
            next.putExtra("name", sp.getString("lastName", ""));
            next.putExtra("group", sp.getString("lastGroup", ""));
            next.putExtra("url", lastPage);
            next.putExtra("photo", sp.getString("lastPhoto", ""));
            // v1.74: NEW_TASK like the session's own Next intent.
            next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            nextPi = PendingIntent.getActivity(this, 3, next,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        // v4.23: Previous parity on the card — the mirror of
        // nextPi above (same two routes, same flags). Request
        // codes 5 (Room) / 6 (library) stay clear of toggle 0,
        // stop 1, open 2, Next 3/4, so no PendingIntent collides.
        PendingIntent prevPi = null;
        if (isLibSession()) {
            Intent prev = new Intent(this, DownloadedActivity.class);
            prev.setAction(DownloadedActivity.ACTION_PREV);
            prev.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            prevPi = PendingIntent.getActivity(this, 6, prev,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else if (lastPage.length() > 0 && isRoomTrack()) {
            Intent prev = new Intent(this, RoomActivity.class);
            prev.setAction(RoomActivity.ACTION_PREV);
            prev.putExtra("name", sp.getString("lastName", ""));
            prev.putExtra("group", sp.getString("lastGroup", ""));
            prev.putExtra("url", lastPage);
            prev.putExtra("photo", sp.getString("lastPhoto", ""));
            prev.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            prevPi = PendingIntent.getActivity(this, 5, prev,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        // v5.23: notification Forward PendingIntent — request code 7
        // stays clear of toggle 0, stop 1, open 2, Next 3/4, Prev 5/6.
        Intent fwd = new Intent(this, AudioService.class);
        fwd.setAction(ACTION_FORWARD);
        PendingIntent fwdPi = PendingIntent.getService(this, 7, fwd,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // v5.42: notification favourite PendingIntent — request
        // code 8 stays clear of toggle 0, stop 1, open 2, Next 3/4,
        // Prev 5/6, Forward 7. Built always, added only when
        // cardFavShow holds (see the actions block below).
        Intent fav = new Intent(this, AudioService.class);
        fav.setAction(ACTION_FAV);
        PendingIntent favPi = PendingIntent.getService(this, 8, fav,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, chanId);
        else b = new Notification.Builder(this);
        String title = currentTitle != null ? currentTitle : Lang.t(this, "notif_default_title");
        // v4.26: the card names a favourite talk - the Room line,
        // drawer rows and library rows all carry the star, but
        // the shade's card never did. resumeOk (above) already
        // proves this track is the Room's last talk, so lastUrl
        // is the right key; a library session (resumeOk false)
        // never borrows another talk's star.
        // v5.42: the card favourite action's own state — the same
        // resumeOk + lastUrl guard as the title star above, so the
        // action exists exactly when the star is meaningful (a Room
        // session) and never for a library session. cardFavOn is
        // read fresh at every rebuild, so a toggle from the Room,
        // a strip or this very action flips icon + label at once.
        boolean cardFavShow = resumeOk && lastUrl.length() > 0;
        boolean cardFavOn = false;
        try {
            if (resumeOk && lastUrl.length() > 0) {
                java.util.Set<String> ft = sp.getStringSet("favTalks", null);
                if (ft != null && ft.contains(lastUrl)) title = "★ " + title;
                if (ft != null && ft.contains(lastUrl)) cardFavOn = true;
            } else if (isLibSession() && currentUrl != null) {
                // v5.98: the card stars a favourited Room talk (v4.26)
                // but a library session never did — resumeOk is false
                // for a bare file, so the block above cannot apply,
                // while the library rows (v1.44), footer Up Next
                // line (v4.24/v5.96) and auto-advance toasts (v5.97)
                // all carry ★ for the same file. No borrowed star:
                // dlMap maps the favourited talk URL to this exact
                // filename, so the star is this file's own. Title
                // only — the favourite action button stays
                // Room-only (cardFavShow above), the card gains no
                // new action.
                java.util.Set<String> ft = sp.getStringSet("favTalks", null);
                if (ft != null) {
                    String base = new java.io.File(currentUrl).getName();
                    org.json.JSONObject map =
                            new org.json.JSONObject(sp.getString("dlMap", "{}"));
                    java.util.Iterator<String> keys = map.keys();
                    // v8.49: exactly-one claimant, never the first favourited one —
                    // a dual-claimed file starred the card when the OTHER talk was favourited
                    // (same v8.46 class; card action stays Room-only, title mark only).
                    int nClaim49 = 0;
                    String onlyClaim49 = null;
                    while (keys.hasNext()) {
                        String u = keys.next();
                        if (base.equals(map.optString(u, ""))) { nClaim49++; onlyClaim49 = u; }
                    }
                    if (nClaim49 == 1 && onlyClaim49 != null && ft.contains(onlyClaim49)) title = "★ " + title;
                }
            }
        } catch (Exception ignored) {}
        // v1.47: the notification names the sayadaw, not just the talk —
        // a listener glancing at the shade knows whose talk is playing.
        String sd = sp.getString("lastName", "");
        String stateText = (playing ? Lang.t(this, "notif_state_playing") : Lang.t(this, "notif_state_paused"));
        // v4.29: a finished track used to read "ရပ်ထားသည်" on the
        // card, exactly like a paused one — the listener coming
        // back to the shade could not tell the talk had ended.
        // Completion already refreshes the card (showNotification
        // in onCompletion), so the ended read lands at once; a
        // replay clears the flag before the next refresh.
        if (!playing && trackEnded) stateText = Lang.t(this, "notif_state_ended");
        // v1.73: a stream still buffering used to read "ရပ်ထားသည်"
        // (play() shows the card before prepare finishes) — say it
        // is loading instead. A failed stream (trackError) keeps the
        // paused text; the error toast already says what happened.
        // v10.64: but a HELD buffering talk is paused, not loading —
        // the card's own icon/label already knew (the v1.79
        // holdPaused-aware rule below), the Room line already knew
        // (its buffering prefix is gated on !isHoldPaused), the
        // strips knew (v10.61/v10.62) and the session knew
        // (v10.63) — only this status text still overwrote
        // "paused" with "loading…" for a held talk, so the shade
        // card showed a ▶ play affordance next to a "loading"
        // line. A held talk keeps the paused text; an unheld
        // buffering talk still reads loading.
        if (!playing && !trackError && !holdPaused && player != null && !prepared && currentTitle != null)
            stateText = Lang.t(this, "notif_state_buffering");
        // resumeOk (above) is false for a downloaded-library session
        // playing a different talk — never name the wrong sayadaw.
        // v5.45: a library session names its recorded sayadaw
        // instead (Fx.stripLibSayadaw resolves it from the
        // download-time dlSayadaw record for this exact file, so
        // it can never be another talk's name); an unrecorded
        // download keeps today's dhammadownload.com fallback.
        String libSd = isLibSession() ? Fx.stripLibSayadaw(this, sp) : null;
        String who = libSd != null ? libSd : ((sd.length() > 0 && resumeOk) ? sd : Lang.t(this, "notif_orig_src"));
        // v1.72: elapsed/total on the card itself (M:SS, H:MM:SS for
        // hour+ talks, same rule as the Room) — the card text is the
        // only clock a plain notification has.
        String sub = who + " · " + stateText;
        int dur = getDuration();
        int pos = getPosition();
        if (dur > 0 && pos >= 0) sub = who + " · " + fmtTime(pos) + " / " + fmtTime(dur) + " · " + stateText;
        // v5.39: sleep countdown on the plain card — the Room line
        // (\"😴 mm:ss\" / \"😴 ⏸ mm:ss\" / end-of-talk) and the strips
        // both show it, but the shade/lock-screen card never did, so
        // a listener who set the timer and locked the phone could
        // not see when playback would stop without reopening the
        // app. Same four states as the Room line, same guard (a
        // remainder orphaned by a turned-off setting never shows
        // as live); the suffix rides the card's existing rebuilds
        // (every 15s while playing, at once on tap/seek/Forward),
        // so the digits step in 15s jumps, like the progress bar.
        // Plain card only — no MediaStyle, no custom layout.
        try {
            long sleepRem = getSleepRemainingMs();
            long sleepSusp = getSleepSuspendedMs();
            if (isSleepFading()) sub += " · 😴 " + Lang.t(this, "notif_sleep_fading");
            else if (isSleepEndOfTalk()) sub += " · 😴 " + Lang.t(this, "sleep_end");
            else if (sleepRem > 0) sub += " · 😴 " + sleepClock(sleepRem);
            else if (sleepSusp > 0 && Prefs.sleepMinutes(this) > 0)
                sub += " · 😴 ⏸ " + sleepClock(sleepSusp);
        } catch (Exception ignored) {}
        // v5.40: playback speed on the plain card — the Room's time
        // row and every mini-player strip show the live speed
        // button, but the shade/lock-screen card never did, so a
        // listener at 1.5x with the phone locked could not tell
        // from the card why the talk sounded fast. Same label as
        // those buttons (Fx.speedLabel: 1.5x, 2x, 0.75x), shown
        // only when the live speed differs from 1x, so a default
        // listener sees no change at all; rides the card's
        // existing rebuilds like the v5.39 sleep suffix (every 15s
        // while playing, at once on tap/seek/Forward). The field
        // is the live one — setSpeed updates it on every strip,
        // Room and Settings change — never the pref alone.
        // Plain card only — no MediaStyle, no custom layout.
        try {
            if (speed != 1.0f) sub += " · " + Fx.speedLabel(speed);
        } catch (Exception ignored) {}
        // v5.41: repeat + shuffle on the plain card — every
        // mini-player strip (v5.27) and the Room's own secondary
        // row show the live repeat/shuffle glyphs, but the
        // shade/lock-screen card never did, so a listener who set
        // repeat-one or shuffle and locked the phone could not
        // tell from the card why the same talk replayed or why
        // the next talk was out of order. Same glyphs as
        // Fx.stripRepeatGlyph/stripShuffleGlyph (repeat-one
        // U+1F502, repeat-off U+21BB, shuffle U+1F500), shown
        // only when they differ from the defaults (repeat-all +
        // shuffle-off), so a default listener sees no change at
        // all — the v5.40 speed rule. Read from the
        // prefs at rebuild time, rides the card's existing
        // rebuilds like the v5.39/v5.40 suffixes (every 15s while
        // playing, at once on tap/seek/Forward). Plain card
        // only — no MediaStyle, no custom layout.
        // v8.39: library sessions included — the v5.41 "Room
        // sessions ONLY" guard is stale since v7.33 (library
        // end-advance honours repeat-one, repeat-off stop and
        // strip-shuffle), v7.37 (the library strip itself shows
        // these glyphs for exactly that reason) and v7.79 (the
        // background library advance honours the same prefs).
        // A library listener who set repeat-one in the strip
        // and locked the phone saw no glyph on the card while
        // the same file replayed — the card lied by omission.
        // isLibSession() is the same guard the card's own
        // Next/Prev routing uses for library sessions. One
        // deliberate under-report remains, unchanged: the
        // Random button's session-only dlShuffle flag lives in
        // DownloadedActivity memory the service cannot see
        // (the v7.79 divergence), so a shuffle that comes
        // from Random alone still shows no glyph — only a
        // prefs shuffle, which the library does honour, is
        // shown, and it is shown truthfully.
        try {
            if (isRoomTrack() || isLibSession()) {
                String modes = "";
                int rm = Prefs.repeatMode(this);
                if (rm == 2) modes += "\uD83D\uDD02";
                else if (rm == 0) modes += "\u21BB";
                if (Prefs.shuffleOn(this)) {
                    if (modes.length() > 0) modes += " ";
                    modes += "\uD83D\uDD00";
                }
                if (modes.length() > 0) sub += " · " + modes;
            }
        } catch (Exception ignored) {}
        // v5.43: hand-queue waiting count on the plain card — the
        // mini-player strips show only a ◈ presence mark (v4.77)
        // and the card showed nothing at all, so a listener who
        // queued talks and locked the phone could not tell
        // whether anything was still waiting without reopening
        // the app. Same suffix rule as the v5.39–v5.41 suffixes:
        // shown only when the count is non-zero, so a default
        // listener sees no change at all, and it rides the card's
        // existing rebuilds (every 15s while playing, at once on
        // tap/seek/Forward). Room sessions read the persisted
        // per-sayadaw queue (Prefs.savedQueue, keyed by lastName
        // — the same name Room saves it under); library sessions
        // read the shared library queue (Prefs.savedQueueLib).
        // The playing talk itself is never counted: a queued URL
        // still saved when its talk starts (or its downloaded
        // file path, for an offline Room session) is skipped, so
        // the number is the talks still waiting, not the queue
        // length on disk. Plain card only — no MediaStyle, no
        // custom layout.
        try {
            int qn = 0;
            if (isLibSession()) {
                String lq = Prefs.savedQueueLib(this);
                if (lq != null) {
                    for (String s : lq.split("\n")) {
                        if (s == null || s.trim().length() == 0) continue;
                        if (s.equals(currentUrl)) continue;
                        // v5.76: count only files still on disk — the
                        // raw key can hold ghosts (queued files
                        // deleted outside the app), and the v5.75
                        // disk prune only runs when the library
                        // itself opens, so until then this card
                        // showed a wrong number on the lock screen
                        // ("◈ တန်းစီ 3 ပုဒ်" for files already
                        // gone) with no tap-to-heal path, unlike
                        // the strips' ◈ button which prunes on
                        // open. Same filter as the library's own
                        // strip (isFile, readable) so the card and
                        // the strip never disagree. Count-only:
                        // the service never writes prefs from a
                        // notification rebuild — the library load
                        // still owns the disk prune (v5.75).
                        try {
                            java.io.File qf = new java.io.File(s);
                            if (!qf.isFile() || !qf.canRead()) continue;
                        } catch (Exception ignored) { continue; }
                        qn++;
                    }
                }
            } else if (isRoomTrack() && sd.length() > 0) {
                String rq = Prefs.savedQueue(this, sd);
                if (rq != null) {
                    // v12.69: dedup like the drawer header (v11.04)
                    // — a repeated URL counted twice here and once
                    // in the drawer (sim1272 case 4). The
                    // offline-file-form skip below is kept: a
                    // downloaded sounding talk must not count
                    // under either spelling.
                    java.util.HashSet<String> seen69 = new java.util.HashSet<String>();
                    for (String s : rq.split("\n")) {
                        if (s == null || s.trim().length() == 0) continue;
                        // v7.40: line-aware like the strips (v7.39) -- a v2-spelled playing
                        // talk used to count as waiting, and offlineFileFor on the raw
                        // line could never match its file; parse first, skip by URL.
                        QueueEntry e40 = QueueEntry.parse(s.trim());
                        String u40 = e40 == null ? "" : e40.url;
                        if (u40.length() == 0 || u40.equals(currentUrl)) continue;
                        if (!seen69.add(u40)) continue;
                        try {
                            java.io.File f = offlineFileFor(u40);
                            if (f != null && f.getAbsolutePath().equals(currentUrl)) continue;
                        } catch (Exception ignored) {}
                        qn++;
                    }
                }
            }
            if (qn > 0) sub += " · ◈ " + Lang.t(this, "notif_queue_prefix") + qn + Lang.t(this, "notif_queue_suffix");
        } catch (Exception ignored) {}
        // v5.93: offline mark on the plain card — the Room line
        // (⬇/⏳) and the drawer rows carry it, but the shade/
        // lock-screen card never did, so a listener with the phone
        // locked could not tell whether the talk was playing
        // offline or streaming on mobile data (the data-saver
        // question). Room sessions ONLY, the v5.41 guard: a
        // library session is local by definition, so a mark there
        // would be noise. Downloaded wins over queued, the
        // stripDownloadState rule: an offline file for the track
        // URL (offlineFileFor, map + candidates), or the track
        // itself already being that file path (an offline Room
        // session, the resumeOk form above); otherwise the
        // dlQueued mark Room/strip share says ⏳. Shown only
        // when true, so a streaming listener sees no change at
        // all, and it rides the card's existing rebuilds like the
        // v5.39–v5.43 suffixes. Plain card only — no MediaStyle,
        // no custom layout.
        try {
            if (isRoomTrack() && currentUrl != null) {
                boolean downloaded = new java.io.File(currentUrl).isFile()
                        || offlineFileFor(currentUrl) != null;
                if (downloaded) sub += " · ⬇";
                else {
                    String qUrl = currentUrl;
                    if (lastUrl.length() > 0) {
                        java.io.File off = offlineFileFor(lastUrl);
                        if (off != null && off.getAbsolutePath().equals(currentUrl)) qUrl = lastUrl;
                    }
                    org.json.JSONArray queued = new org.json.JSONArray(sp.getString("dlQueued", "[]"));
                    for (int i = 0; i < queued.length(); i++) {
                        if (qUrl.equals(queued.optString(i))) { sub += " · ⏳"; break; }
                    }
                }
            }
        } catch (Exception ignored) {}
        // v1.79: the card's own Play/Pause label used to follow
        // isPlaying() only — while a stream was still buffering it
        // read ▶ ဖွင့်, but tapping it HELD the incoming talk
        // paused (v1.69 hold), so the label promised play and the
        // tap did the opposite. A buffering talk that will play is
        // active (⏸/ရပ်); only a held one reads ▶/ဖွင့်. The
        // status text itself already says "ဆွဲထုတ်နေသည်…" below.
        boolean active = playing || (isBuffering() && !holdPaused);
        // v1.78: the status-bar icon used to show ▶ even while the
        // talk was paused — it now follows the state like the card's
        // own Play/Pause button already does.
        b.setSmallIcon(active ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play)
         .setOnlyAlertOnce(true)
         .setContentTitle(title)
         .setContentText(sub)
         .setContentIntent(openPi);
        // v5.26: media-card visibility flags — TRANSPORT category
        // (this IS a media notification, not a chat/alert), PUBLIC
        // so it shows on the lock screen, ONGOING so the system
        // never swipes it away. The v5.25 card had none of these.
        b.setCategory(Notification.CATEGORY_TRANSPORT);
        b.setVisibility(Notification.VISIBILITY_PUBLIC);
        b.setOngoing(true);
        // v5.23: MP3-player card, plain-card only (NO MediaStyle —
        // v1.70 proved the system claims a MediaStyle card on his
        // phone and the shade card vanishes). Sayadaw portrait as
        // the large icon: cache-only (ImageLoader.cached, never a
        // download on this path), falling back to the gold initial
        // circle when the portrait was never cached. The
        // large icon shows in the EXPANDED card — the collapsed
        // lock-screen row is system-drawn and shows only the small
        // app icon, on every app, not just this one.
        // v5.99: a library session gets its portrait too — the
        // v5.23 note above ("names no sayadaw, gets no icon")
        // went stale when v5.45 started naming the recorded
        // sayadaw on the card and v5.98 starred its title, so a
        // favourited downloaded file's card named its sayadaw
        // but showed no face while a Room session's did. The
        // name is libSd itself (the v5.45 record, never guessed),
        // the photo comes from the bundled catalog by that exact
        // name, and a name with no catalog entry falls back to
        // that sayadaw's own initial placeholder — so the card
        // can never show the wrong face. An unrecorded download
        // (libSd null, pre-v5.45) keeps today's no-icon card.
        if (resumeOk && sd.length() > 0) {
            String photo = sp.getString("lastPhoto", "");
            android.graphics.Bitmap large = null;
            if (photo.length() > 0) {
                if (!photo.equals(notiLargeUrl)) {
                    notiLargeIcon = ImageLoader.cached(this, photo);
                    notiLargeUrl = photo;
                }
                large = notiLargeIcon;
            }
            if (large == null) large = ImageLoader.placeholder(sd);
            if (large != null) b.setLargeIcon(notiScaled(large));
        } else if (isLibSession() && libSd != null && libSd.length() > 0) {
            android.graphics.Bitmap large = null;
            if (!libSd.equals(notiLibName)) {
                String photo = photoUrlForSayadaw(this, libSd);
                notiLibIcon = photo.length() > 0 ? ImageLoader.cached(this, photo) : null;
                notiLibName = libSd;
            }
            large = notiLibIcon;
            if (large == null) large = ImageLoader.placeholder(libSd);
            if (large != null) b.setLargeIcon(notiScaled(large));
        }
        // v5.23: duration progress bar — the plain card's only
        // progress diagram. A custom wave view cannot live in a
        // notification (RemoteViews cannot host WaveSeekView, and
        // a custom layout risks the shade the way MediaStyle did),
        // so the bar is the MP3-player equivalent Android allows
        // here. It moves on the card's existing rebuilds: every
        // 15s while playing, at once on tap / seek / Forward.
        if (dur > 0) b.setProgress(dur, pos, false);
        // v10.65: a HELD buffering talk is paused, not loading — the
        // indeterminate spinner here was the last card surface still
        // saying "loading" for it (icon already ▶ via v1.79, text
        // already paused via v10.64, session already PAUSED via
        // v10.63, strips already ▶ via v10.61/v10.62). An unheld
        // buffering talk still spins; a held one shows no spinner,
        // exactly like a failed stream on this same bar.
        else if (isBuffering() && !holdPaused) b.setProgress(0, 0, true);
        if (notiOn) {
            // v5.28: plain card again (see the MediaStyle removal
            // note below). v12.97: EXACTLY THREE actions — Play |
            // Next | Forward, in that order, the three he asked for.
            // The v12.96 card carried six (Prev|Play|Next|Forward|
            // Fav|Stop) and his shade showed NONE of them in the
            // collapsed card (screenshot 2026-11-07 11:21). Prev/Fav/
            // Stop leave the card only; they all still work inside
            // the app (Room main row, strips, drawer long-press,
            // library row menu). Text is sayadaw + elapsed/total +
            // state, the form that actually shows on his phone.
            b.addAction(active ? android.R.drawable.ic_media_pause
                    : android.R.drawable.ic_media_play,
                    active ? Lang.t(this, "notif_action_pause") : Lang.t(this, "notif_action_play"), togglePi);
            if (nextPi != null)
                b.addAction(android.R.drawable.ic_media_next, Lang.t(this, "notif_action_next"), nextPi);
            b.addAction(android.R.drawable.ic_media_ff, "Forward", fwdPi);
            // v5.28: MediaStyle REMOVED, permanently on this phone.
            // Tested three times and invisible every time — v1.70,
            // v5.25, v5.26 (new DEFAULT channel + TRANSPORT +
            // PUBLIC + session art + pre-set state did not save
            // it) — while the plain card shows on his lock screen
            // and shade in his own screenshots. Spotify's media
            // area works for Spotify; it does not work for this
            // app on this phone, and a fourth MediaStyle try would
            // just leave him card-less again. Plain card back:
            // title + sayadaw + elapsed/total text (the sub built
            // above, NOT the artist-only line), large portrait
            // icon when expanded, progress bar, all five actions.
            // Session stays active for headset buttons only.
            // DO NOT re-add MediaStyle without a new, different
            // mechanism that has been proven to show on HIS phone.
            ensureSession();
            mediaSession.setActive(true);
        } else if (mediaSession != null) {
            // v1.70: notifications-off stays button-less AND session-
            // less — no system media controls either, as the user
            // asked for silence, not just hidden buttons.
            mediaSession.setActive(false);
        }
        // v5.26: session state BEFORE the card posts — the v5.25
        // card posted first and only then pushed its playback
        // state, so a system that snapshots the session at post
        // time saw no playing state at all.
        // v9.01 (user order 2026-11-02 ~20:08 +07): custom notification
        // views — collapsed card carries its own Prev/Play/Next buttons
        // + a 20-zone tap-seek bar, so both work straight from the
        // shade without expanding. The standard addAction calls above
        // stay as fallback for any shade that ignores custom views.
        // NOT MediaStyle (still banned: v1.70/v5.25/v5.26 vanished on
        // his phone) and NOT a SeekBar: RemoteViews cannot host one
        // or report a drag, so each zone seeks to its own fraction
        // via the existing ACTION_SEEK branch (seekMs extra, clamped,
        // card rebuilt at once by seekTo). Gated on notiOn: with
        // notifications off the card stays button-less as before.
        // v10.74: plain-card snapshot BEFORE the custom views attach
        // (see the fallback at the tail of this method) — a build
        // taken after attach would carry the custom views too.
        android.app.Notification plainNotif = null;
        try { plainNotif = b.build(); } catch (Exception ignored) {}
        if (notiOn && USE_CUSTOM_NOTI_VIEWS) {
            android.graphics.Bitmap portrait = null;
            try {
                if (resumeOk && sd.length() > 0) {
                    String photo = sp.getString("lastPhoto", "");
                    if (photo.length() > 0) portrait = ImageLoader.cached(this, photo);
                    if (portrait == null) portrait = ImageLoader.placeholder(sd);
                } else if (isLibSession() && libSd != null && libSd.length() > 0) {
                    String photo = photoUrlForSayadaw(this, libSd);
                    portrait = photo.length() > 0 ? ImageLoader.cached(this, photo) : null;
                    if (portrait == null) portrait = ImageLoader.placeholder(libSd);
                }
                if (portrait != null) {
                    int sz = Math.min(portrait.getWidth(), portrait.getHeight());
                    android.graphics.Bitmap circ = android.graphics.Bitmap.createBitmap(
                            sz, sz, android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas cv = new android.graphics.Canvas(circ);
                    android.graphics.Paint pt = new android.graphics.Paint(
                            android.graphics.Paint.ANTI_ALIAS_FLAG);
                    cv.drawCircle(sz / 2f, sz / 2f, sz / 2f, pt);
                    pt.setXfermode(new android.graphics.PorterDuffXfermode(
                            android.graphics.PorterDuff.Mode.SRC_IN));
                    cv.drawBitmap(portrait, (sz - portrait.getWidth()) / 2f,
                            (sz - portrait.getHeight()) / 2f, pt);
                    portrait = circ;
                }
            } catch (Exception ignored) {}
            // v10.74: same downscale as the large icon above — the
            // custom photo is shown at 48dp, never at 512px.
            portrait = notiScaled(portrait);
            android.widget.RemoteViews smallRv = new android.widget.RemoteViews(
                    getPackageName(), R.layout.notif_player);
            android.widget.RemoteViews bigRv = new android.widget.RemoteViews(
                    getPackageName(), R.layout.notif_player_big);
            android.widget.RemoteViews[] rvs = {smallRv, bigRv};
            for (android.widget.RemoteViews rv : rvs) {
                rv.setTextViewText(R.id.notifTitle, title);
                rv.setTextViewText(R.id.notifSub, sub);
                if (portrait != null) {
                    rv.setImageViewBitmap(R.id.notifPhoto, portrait);
                    rv.setViewVisibility(R.id.notifPhoto, android.view.View.VISIBLE);
                } else {
                    rv.setViewVisibility(R.id.notifPhoto, android.view.View.GONE);
                }
                // v11.84: long math — pos * 1000 overflows int past ~35.8 min (2,147,483 ms), so the
                // bar snapped to empty mid-talk on every hour-long talk; the plain card's
                // setProgress(dur, pos) never multiplied and was always right.
                if (dur > 0) rv.setProgressBar(R.id.notifSeekBar, 1000,
                        (int) Math.min(1000L, (long) pos * 1000 / dur), false);
                // v10.65: same held-buffering gate as the plain bar
                // above — a held talk gets a still empty bar, not a
                // spinning indeterminate one, on the custom card too.
                else rv.setProgressBar(R.id.notifSeekBar, 0, 0, isBuffering() && !holdPaused);
                rv.setTextViewText(R.id.notifToggle, active ? "\u23F8\uFE0E" : "▶");
                if (prevPi != null) rv.setOnClickPendingIntent(R.id.notifPrev, prevPi);
                else rv.setViewVisibility(R.id.notifPrev, android.view.View.GONE);
                rv.setOnClickPendingIntent(R.id.notifToggle, togglePi);
                if (nextPi != null) rv.setOnClickPendingIntent(R.id.notifNext, nextPi);
                else rv.setViewVisibility(R.id.notifNext, android.view.View.GONE);
                for (int i = 0; i < 20; i++) {
                    int zid = getResources().getIdentifier("notifSeek" + i, "id", getPackageName());
                    if (zid == 0) continue;
                    Intent si = new Intent(this, AudioService.class);
                    si.setAction(ACTION_SEEK);
                    si.putExtra("seekMs", dur > 0 ? (2 * i + 1) * dur / 40 : -1);
                    PendingIntent siPi = PendingIntent.getService(this, 100 + i, si,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    rv.setOnClickPendingIntent(zid, siPi);
                }
            }
            bigRv.setOnClickPendingIntent(R.id.notifForward, fwdPi);
            if (cardFavShow) {
                bigRv.setViewVisibility(R.id.notifFav, android.view.View.VISIBLE);
                bigRv.setTextViewText(R.id.notifFav, cardFavOn ? "★" : "☆");
                bigRv.setOnClickPendingIntent(R.id.notifFav, favPi);
            }
            bigRv.setOnClickPendingIntent(R.id.notifStop, stopPi);
            b.setCustomContentView(smallRv);
            b.setCustomBigContentView(bigRv);
            b.setStyle(new Notification.DecoratedCustomViewStyle());
        }
        if (notiOn) updateSession();
        // v10.74: plain-card fallback — showNotification has no
        // top-level guard, so anything thrown while building or
        // posting the v9.01 custom card used to skip startForeground
        // entirely: no card, no status-bar icon, and (on Android
        // 12+) a foreground-service timeout for a service that
        // never went foreground. plainNotif above is the card
        // before the custom views attached; if posting the custom
        // card throws — oversize parcel included — the plain card
        // posts instead. A card that degrades is strictly better
        // than no card at all, and the custom card stays the
        // normal path.
        try {
            startForeground(NOTIF_ID, b.build());
        } catch (Exception ignored) {
            try {
                if (plainNotif != null) startForeground(NOTIF_ID, plainNotif);
            } catch (Exception ignored2) {}
        }
        // v10.75: the card is already posted here — a session-sync throw
        // must not reach the wrapper and overwrite it with the minimal card.
        try { updateSession(); } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // v12.66: last bank before the service dies — a kill
        // mid-talk loses at most the minute since the last flush.
        flushListenStats();
        liveSession = false; // v3.91: no session, no auto-scroll
        liveHasTrack = false; // v10.40: no session, no track
        liveEnded = false;
        liveSleepEndOfTalk = false; // v4.82: no session, no one-shot
        if (mediaSession != null) {
            try { mediaSession.setActive(false); mediaSession.release(); } catch (Exception ignored) {}
            mediaSession = null;
        }
        try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(dlAwaitedReceiver); } catch (Exception ignored) {} // v12.10
        // v12.62 (Finding 4): the dlMap listener goes with the service.
        try { getSharedPreferences("dhamma", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(dlMapListener62); } catch (Exception ignored) {}
        abandonFocus();
        posHandler.removeCallbacks(posRunnable);
        livePosHandler.removeCallbacks(livePosRunnable); // v4.30
        livePositionMs = 0; liveDurationMs = 0; // v4.30
        liveUrl = null; // v8.01: no session, no identity
        liveTitle = null; // v12.67: widget mirror ends with the session
        if (sleepRunnable != null) sleepHandler.removeCallbacks(sleepRunnable);
        // v12.20: final save — the service can be killed mid-talk
        // (system reclaim) and the 15s checkpoint was the only
        // while-playing save, so up to 15s of listening was lost.
        // Same guard as the play() switch-save above: an ended
        // talk's cleared spot is never re-saved (trackEnded is
        // the operative flag here; liveEnded was just reset above).
        if (currentUrl != null && player != null && prepared
                && !trackEnded && !trackError) {
            try {
                int finalPos = player.getCurrentPosition();
                if (finalPos > 5000) {
                    getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                            .putInt("lastPos", finalPos).putString("lastPosUrl", currentUrl).apply();
                    saveTalkPos(currentUrl, finalPos); // v5.34
                }
            } catch (Exception ignored) {}
        }
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        // v12.67: widget push — death falls back to the stored
        // last talk (play icon, resume on tap).
        DhammaWidgetProvider.refresh(this);
    }
}
