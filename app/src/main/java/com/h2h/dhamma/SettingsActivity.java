package com.h2h.dhamma;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class SettingsActivity extends Activity {

    private View root;
    // v3.71: return-dip cache, mirroring Main/Talks/Room/Library —
    // refresh() itself keeps these in step (see its tail), so the
    // onResume diff below always compares against what this screen
    // last actually drew, not against onCreate alone.
    private boolean cachedStNight = false;
    private int cachedStFont = 1;
    private TextView titleText;
    private android.view.View rowFont, rowLanguage;
    private android.widget.TextView fontValue, langValue;
    // v12.94 (his order 2026-11-06 ~22:46 +07: Settings rebuilt to the
    // worldwide streaming-app standard): the on/off toggles are Switch
    // views now (Switch extends Button, so the field type only narrows);
    // Repeat/Sleep are value TextViews inside tap rows, their handlers
    // moved to the row containers below. Every pref + gesture stays.
    private android.widget.Switch btnNoti, btnAutoplay, btnShuffle, btnBackground, btnKeepScreen, btnThemeAuto, btnFx, btnDataSaver, btnAutoDl;
    private android.widget.TextView btnRepeat, btnSleep;
    // v4.12: live sleep countdown — the sleep button used to show
    // only the setting ("30 မိနစ်") while a timer ran, so a listener
    // could not tell how long was left without leaving Settings for
    // the Room. While this screen is on top it now binds (flag 0,
    // like syncLiveService — never starts a stopped service) and
    // re-reads the running timer every 5s. Bound flag guards the
    // unbind: a never-connected bind is never unbound.
    private AudioService sleepWatchService = null;
    private android.content.ServiceConnection sleepWatchConn = null;
    private boolean sleepWatchBound = false;
    // v11.72: a bind is outstanding from bindService's true return
    // until onServiceConnected fires -- stopSleepWatch must unbind
    // a merely-pending bind too (see startSleepWatch below), or a
    // fast open-and-back leaks the connection and pins the service.
    private boolean sleepWatchBindPending = false;
    // v11.88: pending one-shot live-sync binds (syncLiveService below) — a bind whose
    // onServiceConnected never fires (service dies between bind and connect) used to
    // stay registered with no reference left to unbind it. Tracked here and swept in
    // onDestroy; the normal path unbinds + removes itself in the callback, so the
    // sweep is a no-op there.
    private java.util.Set<android.content.ServiceConnection> syncLiveConns88 =
            new java.util.HashSet<android.content.ServiceConnection>();
    private android.os.Handler sleepWatchHandler = new android.os.Handler();
    private Runnable sleepWatchTick = null;
    // v4.41: mini-player strip tick — Settings was the last screen
    // without a live strip (Main v4.30, Talks v4.31, library v4.40).
    // Own 1s handler, not the 5s sleep watch: progress and the
    // play/pause glyph must move like the other strips do.
    private android.os.Handler stripHandler = new android.os.Handler();
    private Runnable stripTick = null;
    // v4.64: dead-session resume mode for the Settings strip —
    // the mirror of Main's v4.60/v4.63 flag. True only while the
    // strip shows a saved spot with no live session, so the tap,
    // toggle and long-press below route to resume/dismiss
    // instead of toggling or stopping a trackless service.
    private boolean settingsResumeStripMode = false;
    // v6.03: Settings strip portrait guard — the v6.00/v6.01/v6.02
    // change-only guard, so the 1s tick never re-loads a face.
    private String settingsStripPhotoName = null;
    // v10.10: download-complete listener for the paused shade card
    // (Main v10.08 pattern) — registered in onResume, unregistered
    // in onPause; Settings has no rows, its strip + button counts
    // already tick every second, so this is card-only.
    private android.content.BroadcastReceiver settingsDlCompleteReceiver = null;
    private boolean settingsDlCompleteRegistered = false;
    // v10.47: which single-choice picker is open ("font"/"lang"/"sleep", null = none).
    // Settings never had onSaveInstanceState, so rotating with a picker open
    // destroyed the dialog and the pick was lost (the screen itself rebuilds
    // from prefs, so only the open picker vanished). The field is saved below
    // and the picker re-shown after rotation; a pick or Close dismiss clears
    // it via the dialog's dismiss listener, so a rotated-back screen never
    // re-shows a picker the user already closed. Confirm dialogs (clears,
    // reset) are deliberately NOT restored: re-showing a destructive confirm
    // after rotation would surprise, and losing one costs only a re-tap.
    private String openPicker = null;
    // v11.88: last blocked-channel value the noti button + note were drawn with, and
    // whether refresh() has drawn them yet — the 1s tick's refreshBlockedChannelMark88
    // compares against these so it rewrites only on a real change (see below).
    private String lastBlockedChan88 = null;
    private boolean blockedChanKnown88 = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        root = findViewById(R.id.root);
        titleText = findViewById(R.id.titleText);
        // v3.80: RESTORED — the v3.77 Theme/Speed removal cut too wide and deleted
        // these shared assignments, leaving every field below null, so the very
        // first setOnClickListener in onCreate threw NullPointerException and
        // Settings exited at once. These lines must stay even though the Theme
        // and Speed cards themselves are gone.
        rowFont = findViewById(R.id.rowFont);
        fontValue = findViewById(R.id.fontValue);
        rowLanguage = findViewById(R.id.rowLanguage);
        langValue = findViewById(R.id.langValue);
        btnNoti = findViewById(R.id.btnNoti);
        btnAutoplay = findViewById(R.id.btnAutoplay);
        btnRepeat = findViewById(R.id.btnRepeat);
        btnShuffle = findViewById(R.id.btnShuffle);
        btnBackground = findViewById(R.id.btnBackground);
        btnKeepScreen = findViewById(R.id.btnKeepScreen);
        btnThemeAuto = findViewById(R.id.btnThemeAuto);
        btnSleep = findViewById(R.id.btnSleep);
        btnFx = findViewById(R.id.btnFx);
        btnDataSaver = findViewById(R.id.btnDataSaver);
        btnAutoDl = findViewById(R.id.btnAutoDl);
        // v2.85: premium font-size transition — the theme buttons
        // dip the screen while swapping (v2.23), but a font-size
        // tap reflowed every row instantly: all text jumped to its
        // new size in one frame while the tapped button alone
        // morphed. A real size change now dips with the same
        // Fx.themeDip (140ms down to 0.55, swap at the dip, 260ms
        // rise to exactly alpha 1), so the reflow lands softly
        // like a theme swap; re-tapping the already-selected size
        // still refreshes instantly with no pointless dip, same
        // guard as the theme buttons. The morph beat is untouched
        // (scale channel, beside the dip's alpha channel), and
        // the pref is set before the dip so the swap reads it.
        // v9.11 (user order 2026-11-02 ~21:12 +07): font size is ONE row
        // (label + current value + ›) — tap opens the 3-choice dialog below
        // (same single-choice shape as the sleep picker), instead of 3 inline
        // buttons. Long-press still resets to Normal (v4.19 gesture, moved).
        if (rowFont != null) rowFont.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Fx.morph(v); showFontDialog(); }
        });
        if (rowFont != null) rowFont.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Fx.tap(SettingsActivity.this);
                if (Prefs.fontSize(SettingsActivity.this) != 1) {
                    Prefs.setFontSize(SettingsActivity.this, 1);
                    Fx.morph(v);
                    Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } });
                }
                return true;
            }
        });
        // v9.11: language is ONE row (label + current language + ›) — the
        // 12 inline buttons were the clutter he named. Tap opens a
        // single-choice dialog with the 12 native names (Lang.NAMES),
        // current checked — the Facebook pattern. Long-press still resets
        // to Burmese (v4.21 gesture, moved from the buttons).
        if (rowLanguage != null) rowLanguage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Fx.morph(v); showLangDialog(); }
        });
        if (rowLanguage != null) rowLanguage.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                String o = Prefs.lang(SettingsActivity.this);
                if ("my".equals(o)) { Fx.tap(SettingsActivity.this); return true; }
                Prefs.setLang(SettingsActivity.this, "my");
                Fx.morph(v);
                // v10.00: same live-sync gap as the dialog pick above -- the
                // long-press reset also left a playing card in the old language.
                syncLiveService();
                Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } });
                return true;
            }
        });
        // v13.34 (Settings F5): every toggle/button listener below is
        // null-guarded like rowFont/rowLanguage above — a toggle id
        // missing from the layout used to throw at the first
        // setOnClickListener and Settings exited at once (v3.80
        // precedent), and refresh() below dereferences the same
        // fields, so the guards live at both sites. A missing
        // toggle is simply inert; the rest of the screen still opens.
        if (btnNoti != null) btnNoti.setOnClickListener(new View.OnClickListener() {
            // v2.88: premium notification-toggle transition —
            // mirroring the v2.85 font-size / v2.86 default-speed /
            // v2.87 sleep-timer dips. A notification tap used to
            // flip its label (ဖွင့် ✓ <-> ပိတ်) in one frame while
            // only the tapped button morphed. It now dips with the
            // existing Fx.themeDip (140ms down to 0.55, swap at the
            // dip, 260ms rise to exactly alpha 1), so the new label
            // lands softly like a theme/font/speed/sleep swap. The
            // morph beat is untouched, the pref is set before the
            // dip so the swap reads it, and syncLiveService() still
            // runs at once (it only reads prefs).
            @Override public void onClick(View v) { Prefs.setNotiEnabled(SettingsActivity.this, !Prefs.notiEnabled(SettingsActivity.this)); Fx.morph(v); Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } }); syncLiveService(); }
        });
        // v4.17: notification-toggle long-press opens the app's
        // system notification settings — the in-app toggle only
        // silences the card, it cannot re-grant a permission the
        // listener denied in the system prompt, and that page was
        // reachable only through system Settings. Tap still flips
        // the in-app toggle; a failed deep link toasts instead of
        // crashing (pre-26 falls back to the app-details page).
        // v10.86: when the live channel itself is blocked
        // (blockedChannelId() below), the deep link goes straight
        // to THAT channel's page instead of the app-level page —
        // the app-level page shows Allow ON while the channel
        // stays blocked, which is exactly why the reported "no
        // notification at all" survived every earlier check. The
        // channel page is where the block actually lives, so one
        // long-press lands the listener on the switch that fixes
        // it. Unblocked phones keep the v4.17 app-level page.
        // v12.27: the channel page only when the app-level switch
        // itself is ON — with the app off (appNotiBlocked), the
        // channel toggle cannot help, so the long-press goes to
        // the app-level page instead, where the fix actually
        // lives. The mark + note already name the app-level
        // block in that case (notiBlockedState), so the target
        // and the text can never disagree.
        if (btnNoti != null) btnNoti.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Fx.tap(SettingsActivity.this);
                Fx.morph(v);
                try {
                    Intent i;
                    String blocked = blockedChannelId();
                    if (blocked != null && !appNotiBlocked()) {
                        i = new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
                        i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                        i.putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, blocked);
                    } else if (android.os.Build.VERSION.SDK_INT >= 26) {
                        i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                        i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                    } else {
                        i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        i.setData(android.net.Uri.parse("package:" + getPackageName()));
                    }
                    startActivity(i);
                } catch (Exception e) {
                    android.widget.Toast.makeText(SettingsActivity.this, Lang.t(SettingsActivity.this, "settings_open_failed"), android.widget.Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        if (btnAutoplay != null) btnAutoplay.setOnClickListener(new View.OnClickListener() {
            // v2.89: same themeDip as the FX/data-saver toggles —
            // the autoplay label used to flip in one frame while
            // only the button morphed. Pref set before the dip;
            // no other behaviour changed (motion only).
            @Override public void onClick(View v) { Prefs.setAutoplay(SettingsActivity.this, !Prefs.autoplay(SettingsActivity.this)); Fx.morph(v); Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } });  }
        });
        // v4.19: long-press the autoplay toggle flips it AND says
        // what it means in plain words — the button label itself
        // only ever reads ဖွင့်/ပိတ်, so an older listener could
        // never tell from the screen whether a finished talk
        // would continue or stop. Tap still flips silently.
        if (btnAutoplay != null) btnAutoplay.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                boolean on = !Prefs.autoplay(SettingsActivity.this);
                Prefs.setAutoplay(SettingsActivity.this, on);
                Fx.morph(v);
                Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } });
                android.widget.Toast.makeText(SettingsActivity.this,
                        on ? Lang.t(SettingsActivity.this, "autoplay_next_on")
                           : Lang.t(SettingsActivity.this, "autoplay_next_off"),
                        android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        // v5.12: repeat + shuffle controls — the v5.11 Room stack removal
        // orphaned both prefs (no screen could change them; the Room only
        // loads them in onCreate). They live here now, in the playback
        // card, on the same tap pattern as autoplay: pref set before
        // the dip, refresh() re-reads the label. Repeat cycles
        // ပိတ် → အားလုံး → တစ်ပုဒ်ထဲ (Prefs 0/1/2, default 1);
        // shuffle is a plain on/off toggle (default off). No service
        // call: both modes are Room-local, and the Room reloads them
        // on return (see RoomActivity.syncLiveSettings).
        // v10.01: the v5.12 "no service call" note above is stale since
        // v5.41 put the repeat/shuffle glyphs on the notification card:
        // the card reads both prefs at rebuild time, and rebuilds run
        // every 15s only while playing, so a PAUSED talk's card kept
        // the old glyphs after a Settings flip until the next tap —
        // the same class as the v10.00 language fix. Both toggles now
        // syncLiveService() (bind flag 0 never starts a stopped
        // service; syncSettingsFromPrefs leaves an unchanged sleep
        // pref alone, so nothing else moves).
        android.view.View rowRepeat = findViewById(R.id.rowRepeat);
        if (rowRepeat != null) rowRepeat.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Prefs.setRepeatMode(SettingsActivity.this, (Prefs.repeatMode(SettingsActivity.this) + 1) % 3); Fx.morph(v); Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } }); syncLiveService(); }
        });
        if (btnShuffle != null) btnShuffle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Prefs.setShuffleOn(SettingsActivity.this, !Prefs.shuffleOn(SettingsActivity.this)); Fx.morph(v); Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } }); syncLiveService(); }
        });
        if (btnBackground != null) btnBackground.setOnClickListener(new View.OnClickListener() {
            // v2.89: same dip — the background-playback label used
            // to flip in one frame while only the button morphed.
            // Pref set before the dip; no other behaviour changed.
            @Override public void onClick(View v) { Prefs.setBackgroundEnabled(SettingsActivity.this, !Prefs.backgroundEnabled(SettingsActivity.this)); Fx.morph(v); Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } });  }
        });
        // v4.18: long-press the background-playback toggle opens
        // the system's battery-optimization settings — the mirror
        // of the notification toggle's system-settings long-press
        // (v4.17). Background playback is the feature phone
        // makers kill first under battery optimization, and the
        // in-app toggle cannot exempt the app; that page was
        // reachable only through system Settings. Tap still flips
        // the toggle; a failed deep link toasts instead of
        // crashing.
        if (btnBackground != null) btnBackground.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Fx.tap(SettingsActivity.this);
                Fx.morph(v);
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception e) {
                    android.widget.Toast.makeText(SettingsActivity.this, Lang.t(SettingsActivity.this, "settings_open_failed"), android.widget.Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        // v1.30: keep the screen on while listening (talks + Room screens
        // read this flag when they open)
        if (btnKeepScreen != null) btnKeepScreen.setOnClickListener(new View.OnClickListener() {
            // v2.89: same dip — the keep-screen-on label used to
            // flip in one frame while only the button morphed.
            // Pref set before the dip; no other behaviour changed.
            @Override public void onClick(View v) { Prefs.setKeepScreenOn(SettingsActivity.this, !Prefs.keepScreenOn(SettingsActivity.this)); Fx.morph(v); Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } });  }
        });
        // v4.18: long-press the keep-screen-on toggle opens the
        // system's display settings — the mirror of the
        // notification toggle's system-settings long-press
        // (v4.17). Keep-screen-on only holds the screen while
        // listening; the system screen-timeout itself lives on
        // that page, and it was reachable only through system
        // Settings. Tap still flips the toggle; a failed deep
        // link toasts instead of crashing.
        if (btnKeepScreen != null) btnKeepScreen.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Fx.tap(SettingsActivity.this);
                Fx.morph(v);
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS));
                } catch (Exception e) {
                    android.widget.Toast.makeText(SettingsActivity.this, Lang.t(SettingsActivity.this, "settings_open_failed"), android.widget.Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        // v1.40: freeze the Room's opening shrine photo instead of
        // switching it on every talk change (v1.39 behaviour is on).
        if (btnThemeAuto != null) btnThemeAuto.setOnClickListener(new View.OnClickListener() {
            // v2.89: same dip — the shrine-photo-auto-change label
            // used to flip in one frame while only the button
            // morphed. Pref set before the dip; no other behaviour
            // changed. With this, every label-flipping Settings
            // toggle now dips — the series started at v2.85 is
            // complete (theme, font, speed, sleep, notification,
            // FX, data saver, autoplay, background, keep-screen,
            // shrine-photo-auto).
            @Override public void onClick(View v) { Prefs.setAutoTheme(SettingsActivity.this, !Prefs.autoTheme(SettingsActivity.this)); Fx.morph(v); Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } });  }
        });
        // v4.19: long-press the shrine-photo-auto toggle flips it
        // AND says what it means in plain words, the autoplay
        // long-press's mirror above — the label only ever reads
        // ဖွင့်/ပိတ်, never whether the altar photo changes with
        // each talk. With this, every Settings toggle now has a
        // long-press (system pages v4.17/4.18, sleep-cancel v4.15,
        // FX preview v4.17, these two consequence toasts v4.19).
        if (btnThemeAuto != null) btnThemeAuto.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                boolean on = !Prefs.autoTheme(SettingsActivity.this);
                Prefs.setAutoTheme(SettingsActivity.this, on);
                Fx.morph(v);
                Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } });
                android.widget.Toast.makeText(SettingsActivity.this,
                        on ? Lang.t(SettingsActivity.this, "shrine_auto_on")
                           : Lang.t(SettingsActivity.this, "shrine_auto_off"),
                        android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        // v4.14: RESTORED — the sleep, FX and data-saver buttons
        // showed live values but had no tap handler at all, so the
        // sleep timer was un-settable (its pref could only ever be
        // 0 unless reset-all touched it) and FX / data saver could
        // never be flipped from this screen. Same morph + themeDip
        // + refresh pattern as every toggle above; no card returns
        // (the removed Theme/Speed cards stay gone) and no new view.
        // Sleep opens a preset picker (the timer itself still only
        // arms on a playing talk, per the Room/library sync); FX and
        // data saver flip in place, data saver re-reading its live
        // network note on the next refresh.
        android.view.View rowSleep = findViewById(R.id.rowSleep);
        if (rowSleep != null) rowSleep.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.morph(v);
                showSleepDialog();
            }
        });
        // v4.15: long-press the sleep button cancels the timer at
        // once (tap still opens the preset picker, v4.14) — one
        // gesture instead of picker + ပိတ်. Same refresh path as
        // the picker, so the live countdown (v4.12) clears with it
        // and a running timer is cancelled via syncLiveService,
        // exactly as turning the timer off already did (v1.56).
        if (rowSleep != null) rowSleep.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Prefs.setSleepMinutes(SettingsActivity.this, 0);
                Fx.morph(v);
                Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } });
                syncLiveService();
                Fx.cancelEndOfTalkSleep(SettingsActivity.this); // v4.82
                return true;
            }
        });
        if (btnFx != null) btnFx.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Prefs.setFxEnabled(SettingsActivity.this, !Prefs.fxEnabled(SettingsActivity.this)); Fx.morph(v); Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } }); }
        });
        // v4.17: FX-toggle long-press previews the sounds — the
        // toggle's own tap blip is gated by the toggle itself, so
        // previewing with FX off used to mean previewing silence.
        // Fx.preview plays the blip + bell regardless of the
        // toggle, with no pref change; tap still flips FX on/off.
        if (btnFx != null) btnFx.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Fx.morph(v);
                Fx.preview(SettingsActivity.this);
                return true;
            }
        });
        if (btnDataSaver != null) btnDataSaver.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Prefs.setDataSaver(SettingsActivity.this, !Prefs.dataSaver(SettingsActivity.this)); Fx.morph(v); Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } }); }
        });
        // v12.68: Wi-Fi auto-download toggle — the data-saver
        // shape (flip + morph + dip + refresh). Flipping ON
        // schedules the 6h job AND a one-off seed check within a
        // minute; flipping OFF cancels both jobs at once, so no
        // background check can ever run while the toggle reads
        // off. The status line under the button (refresh below)
        // says when the last check ran and how many new talks it
        // downloaded.
        if (btnAutoDl != null) btnAutoDl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = !Prefs.autoDlEnabled(SettingsActivity.this);
                Prefs.setAutoDlEnabled(SettingsActivity.this, on);
                if (on) AutoDownloadJob.scheduleSoon(SettingsActivity.this);
                else AutoDownloadJob.cancel(SettingsActivity.this);
                Fx.morph(v); Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } });
            }
        });
        // v4.18: long-press the data-saver toggle opens the
        // system's data-usage settings — the mirror of the
        // notification toggle's system-settings long-press
        // (v4.17). Data saver gates the app's own streaming, but
        // the system data-saver and per-app usage live on that
        // page, reachable otherwise only through system Settings.
        // Tap still flips the toggle; a failed deep link (the
        // action needs newer system versions) toasts instead of
        // crashing.
        if (btnDataSaver != null) btnDataSaver.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Fx.tap(SettingsActivity.this);
                Fx.morph(v);
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_DATA_USAGE_SETTINGS));
                } catch (Exception e) {
                    android.widget.Toast.makeText(SettingsActivity.this, Lang.t(SettingsActivity.this, "settings_open_failed"), android.widget.Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        // v12.94: one row for all three clears (see showClearDataDialog).
        android.view.View rowClearData = findViewById(R.id.rowClearData);
        if (rowClearData != null) rowClearData.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Fx.morph(v); showClearDataDialog(); }
        });
        // v1.36: the downloaded library lost its main-screen button in the
        // v1.25 minimal-main redesign, so it opens from Settings now
        android.view.View rowDownloaded = findViewById(R.id.rowDownloaded);
        if (rowDownloaded != null) rowDownloaded.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v3.02: premium nav click beat — the Main nav items
                // morph before Fx.go (v2.36), but this Settings
                // navigation button went straight to Fx.go, so its
                // tap read only as the press squash. It now pops
                // with the shared morph beat first; the beat reads
                // through the transition because Settings stays in
                // the back stack while it fades out.
                Fx.morph(v);
                Fx.go(SettingsActivity.this, new Intent(SettingsActivity.this, DownloadedActivity.class));
            }
        });
        // v4.16: long-press the downloaded-library button jumps
        // straight to the playing file (jumpPath, the same extra
        // Main's Player nav already uses) when a library session
        // is live and its file still exists; otherwise it opens
        // the library plain, exactly like a tap. Tap unchanged.
        if (rowDownloaded != null) rowDownloaded.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Fx.morph(v);
                android.content.SharedPreferences sp =
                        getSharedPreferences("dhamma", MODE_PRIVATE);
                Intent li = new Intent(SettingsActivity.this, DownloadedActivity.class);
                if ("lib".equals(sp.getString("lastKind", ""))) {
                    String lp = sp.getString("lastLibPath", "");
                    if (lp.length() > 0 && new java.io.File(lp).isFile()) li.putExtra("jumpPath", lp);
                }
                Fx.go(SettingsActivity.this, li);
                return true;
            }
        });
        // v1.36: reset every setting to its default in one tap (history,
        // favourites, listened marks and downloads are untouched)
        android.view.View btnResetAll = findViewById(R.id.btnResetAll);
        if (btnResetAll != null) btnResetAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v3.02: same dialog-open morph as the clear buttons
                // above. (v2.91's "no morph" was about the dialog's
                // own confirm button, not this opener — the opener
                // is a Settings button, and delete-all's opener
                // morphs under v2.41.)
                Fx.morph(v);
                new android.app.AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(Lang.t(SettingsActivity.this, "dlg_reset_title"))
                        .setMessage(Lang.t(SettingsActivity.this, "dlg_reset_msg"))
                        .setPositiveButton(Lang.t(SettingsActivity.this, "btn_reset"), new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int which) {
                                // v2.91: premium reset-all transition — every
                                // individual Settings toggle now dips (v2.85-
                                // v2.89), but confirming reset-all called
                                // refresh() instantly, so all labels (theme,
                                // font, speed, every toggle) snapped to
                                // their defaults in one frame. A real
                                // reset now dips with the same Fx.themeDip
                                // (140ms down to 0.55, refresh at the dip,
                                // 260ms rise to exactly alpha 1), so the
                                // whole screen lands softly like a single
                                // toggle swap. Guarded like theme/font:
                                // when every pref is already at its
                                // default, refresh() stays instant with
                                // no pointless dip. Prefs are set before
                                // the dip so refresh reads them, and
                                // syncLiveService() still runs at once
                                // (it only reads prefs). Motion only;
                                // the confirm dialog and toast are
                                // unchanged, and no morph is added —
                                // the tap landed on a dialog button,
                                // not a Settings button.
                                // v11.88: the notiChannelId removal below is a change too — when a
                                // fallback id is the ONLY non-default state, changed stayed false
                                // and the screen refreshed instantly instead of dipping (the
                                // removal itself still happened; only the transition was skipped).
                                // A stored original id is no change (the default reads the same).
                                String storedChan88 = getSharedPreferences("dhamma", MODE_PRIVATE).getString("notiChannelId", "playback_media");
                                boolean changed = Prefs.isNight(SettingsActivity.this)
                                        || Prefs.fontSize(SettingsActivity.this) != 1
                                        || Prefs.defaultSpeed(SettingsActivity.this) != 1.0f
                                        || !Prefs.notiEnabled(SettingsActivity.this)
                                        || !Prefs.autoplay(SettingsActivity.this)
                                        || Prefs.repeatMode(SettingsActivity.this) != 1
                                        || Prefs.shuffleOn(SettingsActivity.this)
                                        || !Prefs.backgroundEnabled(SettingsActivity.this)
                                        || Prefs.keepScreenOn(SettingsActivity.this)
                                        || !Prefs.autoTheme(SettingsActivity.this)
                                        || Prefs.dlSortMode(SettingsActivity.this) != 0
                                        || Prefs.sleepMinutes(SettingsActivity.this) != 0
                                        || !Prefs.fxEnabled(SettingsActivity.this)
                                        || !Prefs.dataSaver(SettingsActivity.this)
                                        || !Prefs.autoDlEnabled(SettingsActivity.this)
                                        || Prefs.notiPermAsked(SettingsActivity.this)
                                        || !"my".equals(Prefs.lang(SettingsActivity.this))
                                        || Prefs.stripClockRemaining(SettingsActivity.this)
                                        || (storedChan88 != null && !"playback_media".equals(storedChan88));
                                Prefs.setNight(SettingsActivity.this, false);
                                Prefs.setFontSize(SettingsActivity.this, 1);
                                Prefs.setDefaultSpeed(SettingsActivity.this, 1.0f);
                                Prefs.setNotiEnabled(SettingsActivity.this, true);
                                Prefs.setAutoplay(SettingsActivity.this, true);
                                // v5.12: same left-behind class — the new repeat/
                                // shuffle prefs must reset to their defaults too.
                                Prefs.setRepeatMode(SettingsActivity.this, 1);
                                Prefs.setShuffleOn(SettingsActivity.this, false);
                                Prefs.setBackgroundEnabled(SettingsActivity.this, true);
                                Prefs.setKeepScreenOn(SettingsActivity.this, false);
                                Prefs.setAutoTheme(SettingsActivity.this, true);
                                // v1.83: the v1.44 sort flags are gone
                                // (dead since the v1.25 minimal main),
                                // so reset-all no longer touches them.
                                // v1.48: the same class of miss — the
                                // downloaded-library sort (v1.47) and the
                                // sleep default (v1.48) were left behind.
                                Prefs.setDlSortMode(SettingsActivity.this, 0);
                                Prefs.setSleepMinutes(SettingsActivity.this, 0);
                                // v1.53: and the FX toggle (v1.52) — reset
                                // used to leave sounds/vibration off- or
                                // on-state behind, so "reset all" lied.
                                Prefs.setFxEnabled(SettingsActivity.this, true);
                                // v1.59: same left-behind class — the
                                // v1.58 data-saver toggle was never reset,
                                // so "reset all" left it off-state behind.
                                Prefs.setDataSaver(SettingsActivity.this, true);
                                // v12.68: same left-behind class — the
                                // v12.68 auto-download toggle was never
                                // reset, so "reset all" left it off-state
                                // behind; restoring the ON default also
                                // re-schedules the job (the toggle-on
                                // path schedules, reset must do the same).
                                Prefs.setAutoDlEnabled(SettingsActivity.this, true);
                                AutoDownloadJob.schedule(SettingsActivity.this);
                                // v1.86: and the one-time notification
                                // prompt (v1.72) — an explicit reset-all
                                // should restore default behaviour, so
                                // a previously declined prompt is asked
                                // once more instead of never again.
                                Prefs.setNotiPermAsked(SettingsActivity.this, false);
                                // v4.20: same left-behind class — the v3.76 language
                                // choice was never reset, so "reset all" left a
                                // non-Burmese UI behind while claiming defaults.
                                Prefs.setLang(SettingsActivity.this, "my");
                                // v5.33: same left-behind class — the
                                // strip clock mode must return to
                                // elapsed/total on reset-all, not stay
                                // on remaining while claiming defaults.
                                Prefs.setStripClockRemaining(SettingsActivity.this, false);
                                // v11.83: same left-behind class — the
                                // notification channel migration
                                // (AudioService.resolveChannelId) only
                                // migrates when the stored id is the
                                // original channel, so a reset that
                                // leaves a fallback id behind keeps
                                // posting to the fallback forever, and
                                // a later block of the fallback can
                                // never migrate again. Removing it
                                // restores the original channel; if
                                // that channel is still blocked the
                                // migration re-runs and lands on the
                                // fallback again by itself.
                                getSharedPreferences("dhamma", android.content.Context.MODE_PRIVATE)
                                        .edit().remove("notiChannelId").apply();
                                if (changed) Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } });
                                else refresh();
                                syncLiveService();
                                // v11.44: reset-all is an EXPLICIT cancel of the
                                // armed "stop at end of talk" one-shot, like the
                                // sleep long-press (v4.15) — not a routine sync.
                                // syncSettingsFromPrefs only cancels a one-shot
                                // when the minute pref changed since it was armed
                                // (v4.82/v11.05), and the minute pref is reset to
                                // 0 here: a one-shot armed while the pref was
                                // already 0 (the default, so the common case)
                                // survived reset-all and still stopped the talk
                                // at its end, while a running minute timer was
                                // cancelled by the same reset. The explicit
                                // cancel intent clears it regardless of the pref
                                // (guarded to live sessions only, like the
                                // sleep long-press; a stopped session has
                                // nothing armed).
                                Fx.cancelEndOfTalkSleep(SettingsActivity.this);
                                android.widget.Toast.makeText(SettingsActivity.this,
                                        Lang.t(SettingsActivity.this, "toast_reset"), android.widget.Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton(Lang.t(SettingsActivity.this, "btn_no_reset"), null)
                        .show();
            }
        });
        // v3.78: About — Team + Policy (user order 2026-10-30 ~22:35 +07). Team names
        // the developer exactly as he spelled it (Htay Hlaing Win); Policy states
        // the talk source (dhammadownload.com, direct play, never rehosted) and
        // that rights belong to the original sayadaws/site. v13.56: the Team row
        // (now labelled Developer Profile) opens the photo pop-up instead of the
        // plain text dialog; Policy stays a plain info dialog (one Close button).
        android.view.View rowTeam = findViewById(R.id.rowTeam);
        if (rowTeam != null) rowTeam.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.morph(v);
                showDeveloperProfileDialog();
            }
        });
        android.view.View rowPolicy = findViewById(R.id.rowPolicy);
        if (rowPolicy != null) rowPolicy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.morph(v);
                new android.app.AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(Lang.t(SettingsActivity.this, "policy_title"))
                        .setMessage(Lang.t(SettingsActivity.this, "policy_msg"))
                        .setPositiveButton(Lang.t(SettingsActivity.this, "dlg_close"), null)
                        .show();
            }
        });
        // v4.19: long-press the dhammadownload.com credit line opens
        // the catalogue source itself in the browser — the credit
        // was display-only, and the retry-page long-presses (Talks
        // v4.18, Room v4.19) already proved the pattern: a source
        // line the listener can actually visit. Tap on the line
        // does nothing, as before; no page, or no browser, toasts.
        View aboutSrc = findViewById(R.id.aboutSrc);
        if (aboutSrc != null) aboutSrc.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Fx.tap(SettingsActivity.this);
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.dhammadownload.com/audioinmyanmar.htm")));
                } catch (Exception e) {
                    android.widget.Toast.makeText(SettingsActivity.this, Lang.t(SettingsActivity.this, "open_failed"), android.widget.Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        // v4.41: mini-player strip wiring — same routes as Main's
        // strip (v4.15/v4.30): tap returns to the player (library
        // file still on disk -> library, else the Room talk), the
        // toggle pauses/resumes with no bind, Next routes like
        // Main's, long-press stops the session outright. The
        // strip's own 1s tick (below) keeps it live; refresh()
        // never touches it, so a Settings choice tap cannot
        // clobber the strip's colours or glyph.
        android.view.View settingsStrip = findViewById(R.id.nowStrip);
        if (settingsStrip != null) {
            Fx.press(settingsStrip);
            // v4.73: the strip's progress bar seeks (see Fx.miniSeekBar).
            Fx.miniSeekBar(SettingsActivity.this, settingsStrip);
            // v5.33: tap the strip's sub line (sayadaw + clock) flips
            // the shared clock mode elapsed/total <-> remaining and
            // re-syncs at once (the 1s tick would catch it anyway).
            // The sub view consumes the tap, so it no longer opens
            // the player — the title line still does. Its long-press
            // forwards to the strip, so stop/dismiss is unchanged.
            // Resume strips show no clock (they return before the
            // live branch), so a flip there is invisible until the
            // next live session, which then shows the chosen mode.
            // v13.83 (Settings F1): the flip is a no-op there — the sub
            // line is only a clock control while a live session is
            // showing (mirror of Main's v13.12 M7 guard).
            android.widget.TextView stripSub = findViewById(R.id.nowStripSub);
            if (stripSub != null) {
                stripSub.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        // v13.83 (Settings F1, sim1413): the flip is a live-mode
                        // affordance only — a resume strip shows no
                        // clock, so a flip there was invisible at tap
                        // time and silently re-timed the next live
                        // session. In resume mode (same live recheck
                        // as the dismiss branch, v12.58) the tap is a
                        // deliberate no-op instead of a silent mutation.
                        // Mirror of Main's v13.12 M7 guard.
                        if (settingsResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) return;
                        Prefs.setStripClockRemaining(SettingsActivity.this, !Prefs.stripClockRemaining(SettingsActivity.this));
                        Fx.tap(SettingsActivity.this);
                        syncSettingsStrip();
                    }
                });
                stripSub.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) { return settingsStrip.performLongClick(); }
                });
            }
            settingsStrip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        String lp = sp.getString("lastLibPath", "");
                        if (lp.length() > 0 && new java.io.File(lp).isFile()) {
                            Fx.tap(SettingsActivity.this);
                            Fx.morph(v);
                            Intent li = new Intent(SettingsActivity.this, DownloadedActivity.class);
                            li.putExtra("jumpPath", lp);
                            // v4.64: in dead-library resume mode
                            // the tap also auto-plays the file from
                            // its saved spot (Main v4.63 mechanism);
                            // a live session never passes this flag.
                            // v12.58: live recheck, not just the cached flag — settingsResumeStripMode
                            // refreshes on the 1s tick, so a session dying <1s before this tap left
                            // the flag false and the tap glided to the library without auto-playing
                            // the saved spot (one extra tap to resume). The strip's own branch test
                            // is !liveSession || !liveHasTrack, so re-checking the same statics here
                            // can only route a genuinely dead session to resume, never a live one.
                            // (Talks v12.57 fixed the same site; Settings/Downloaded mirrors are the
                            // cross-screen sweep that run named.)
                            if (settingsResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) li.putExtra("autoPlayPath", lp);
                            Fx.go(SettingsActivity.this, li);
                            return;
                        }
                        // v11.88: a recorded path whose file is gone is not "choose a talk
                        // first" — the listener chose one; the file was deleted. Land in
                        // the library (the Downloaded button's long-press already falls
                        // through this way) instead of stranding them on a wrong toast.
                        // An empty path still means nothing was ever chosen, so it keeps
                        // the choose_first toast below.
                        if (lp.length() > 0) {
                            Fx.tap(SettingsActivity.this);
                            Fx.morph(v);
                            Fx.go(SettingsActivity.this, new Intent(SettingsActivity.this, DownloadedActivity.class));
                            return;
                        }
                        android.widget.Toast.makeText(SettingsActivity.this, Lang.t(SettingsActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(SettingsActivity.this, Lang.t(SettingsActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(SettingsActivity.this, RoomActivity.class);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.putExtra("autoUrl", sp.getString("lastUrl", ""));
                    i.putExtra("autoTitle", sp.getString("lastTitle", ""));
                    Fx.tap(SettingsActivity.this);
                    Fx.morph(v);
                    Fx.go(SettingsActivity.this, i);
                }
            });
            settingsStrip.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    // v4.64: in resume mode there is no session to
                    // stop — a long-press dismisses the saved spot
                    // instead (Main v4.60 mirror: lastPos only, the
                    // talk itself untouched, next sync hides it).
                    // v12.58: same live recheck as the transport buttons (v11.90) — the cached
                    // flag can lag a just-died session by up to one 1s tick, and a stale-false
                    // flag here ACTION_STOPs a trackless service (phantom "talk_stopped" toast,
                    // saved spot NOT dismissed) instead of dismissing it. (Talks v12.57 fixed
                    // the same site; Settings/Downloaded mirrors are the cross-screen sweep.)
                    if (settingsResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        // v5.34: same as Main's dismiss — the
                        // per-talk spot goes with the global one.
                        String dismissUrl = getSharedPreferences("dhamma", MODE_PRIVATE)
                                .getString("lastPosUrl", "");
                        getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                                .putInt("lastPos", 0).putString("lastPosUrl", "").apply();
                        if (dismissUrl.length() > 0) Prefs.talkPosRemoveBoth(SettingsActivity.this, dismissUrl);
                        Fx.tap(SettingsActivity.this);
                        Fx.morph(v);
                        android.widget.Toast.makeText(SettingsActivity.this, Lang.t(SettingsActivity.this, "hist_cleared"), android.widget.Toast.LENGTH_SHORT).show();
                        syncSettingsStrip();
                        return true;
                    }
                    Intent svc = new Intent(SettingsActivity.this, AudioService.class);
                    svc.setAction(AudioService.ACTION_STOP);
                    startService(svc);
                    Fx.tap(SettingsActivity.this);
                    Fx.morph(v);
                    android.widget.Toast.makeText(SettingsActivity.this, Lang.t(SettingsActivity.this, "talk_stopped"), android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
            });
        }
        android.view.View settingsStripToggle = findViewById(R.id.nowStripToggle);
        if (settingsStripToggle != null) {
            settingsStripToggle.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // v4.64: in resume mode there is no live track
                    // to toggle — the ▶ button re-opens the player
                    // like the strip tap (Main v4.60 mirror).
                    // v11.90: same live recheck Next/Prev got in v11.88 — the cached flag can
                    // lag a just-died session by up to one 1s tick, and a stale-false flag
                    // here ACTION_TOGGLEs a trackless service (dead tap, tap-twice-to-resume,
                    // the exact bug Downloaded v11.89 fixed for its Toggle).
                    if (settingsResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    Intent svc = new Intent(SettingsActivity.this, AudioService.class);
                    svc.setAction(AudioService.ACTION_TOGGLE);
                    startService(svc);
                    Fx.tap(SettingsActivity.this);
                    Fx.morph(v);
                }
            });
            settingsStripToggle.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    // v4.64: resume mode has no session to stop —
                    // hand the long-press to the strip, which
                    // dismisses the saved spot (Main v4.60 mirror).
                    // v11.90: same live recheck as the click above — a stale-false flag here
                    // ACTION_STOPs a trackless service instead of dismissing the saved spot.
                    if (settingsResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) return strip2.performLongClick();
                        return true;
                    }
                    Intent svc = new Intent(SettingsActivity.this, AudioService.class);
                    svc.setAction(AudioService.ACTION_STOP);
                    startService(svc);
                    Fx.tap(SettingsActivity.this);
                    Fx.morph(v);
                    android.widget.Toast.makeText(SettingsActivity.this, Lang.t(SettingsActivity.this, "talk_stopped"), android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
            });
        }
        android.view.View settingsStripNext = findViewById(R.id.nowStripNext);
        if (settingsStripNext != null) {
            Fx.press(settingsStripNext);
            settingsStripNext.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // v7.36: belt-and-braces for the tick window
                    // between a session dying and the next sync
                    // hiding this button — a resume-mode ⏭ tap
                    // resumes the ↺ talk like ▶/⏩ do (Toggle/
                    // Forward already guard this way), instead of
                    // ACTION_NEXT-ing into a trackless Room /
                    // library action with no live file. Main and
                    // Talks got this guard in v7.34, the library
                    // in v7.35; Settings was the last strip
                    // without it.
                    // v11.88: live recheck, not just the cached flag — settingsResumeStripMode
                    // refreshes on the 1s tick, so a session dying <1s before this tap left
                    // the flag false and the tap fell through to ACTION_NEXT into a trackless
                    // Room, which opens the list with nothing playing instead of resuming
                    // the ↺ talk. The strip's own branch test is !liveSession ||
                    // !liveHasTrack, so re-checking the same statics here can only route a
                    // genuinely dead session to resume, never a live one.
                    if (settingsResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    Fx.tap(SettingsActivity.this);
                    Fx.morph(v);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        Intent li = new Intent(SettingsActivity.this, DownloadedActivity.class);
                        li.setAction(DownloadedActivity.ACTION_NEXT);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        Fx.go(SettingsActivity.this, li);
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(SettingsActivity.this, Lang.t(SettingsActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(SettingsActivity.this, RoomActivity.class);
                    i.setAction(RoomActivity.ACTION_NEXT);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.go(SettingsActivity.this, i);
                }
            });
        }
android.view.View settingsStripPrev = findViewById(R.id.nowStripPrev);
        if (settingsStripPrev != null) {
            Fx.press(settingsStripPrev);
            settingsStripPrev.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // v7.36: same resume guard as Next above and
                    // as Main's ⏮ guard in v7.31 — a resume-mode
                    // ⏮ tap resumes the ↺ talk like ▶/⏩ do,
                    // instead of ACTION_PREV-ing into a different
                    // talk. Unlike Next, Prev was never even
                    // hidden by sync in resume mode (see below),
                    // so this guard is the live defence, not just
                    // a tick-window one.
                    // v11.88: same live recheck as Next above — the cached flag can lag a
                    // just-died session by up to one 1s tick, and a stale-false flag here
                    // ACTION_PREVs into a trackless Room instead of resuming the ↺ talk.
                    if (settingsResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    Fx.tap(SettingsActivity.this);
                    Fx.morph(v);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        Intent li = new Intent(SettingsActivity.this, DownloadedActivity.class);
                        li.setAction(DownloadedActivity.ACTION_PREV);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        Fx.go(SettingsActivity.this, li);
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(SettingsActivity.this, Lang.t(SettingsActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(SettingsActivity.this, RoomActivity.class);
                    i.setAction(RoomActivity.ACTION_PREV);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.go(SettingsActivity.this, i);
                }
            });
        }
        // v2.11: press feedback on every Settings button — this
        // screen was the one surface v2.08's press pass never
        // reached. Visual only; clicks still work (Fx.press
        // returns false), and refresh() restyles colours only.
        for (int id : new int[]{
                R.id.rowFont, R.id.rowLanguage,
                R.id.btnNoti, R.id.btnAutoplay, R.id.rowRepeat, R.id.btnShuffle, R.id.btnBackground,
                R.id.btnKeepScreen, R.id.btnThemeAuto, R.id.rowSleep,
                R.id.btnFx, R.id.btnDataSaver, R.id.btnAutoDl,
                R.id.rowDownloaded, R.id.rowClearData, R.id.btnResetAll, R.id.rowTeam, R.id.rowPolicy}) {
            View b = findViewById(id);
            if (b != null) Fx.press(b);
        }
        refresh();
        // v2.14: entrance cascade — Settings was the last screen
        // with no entrance motion (Main grid since v2.07, Talks
        // rows since v2.07, Room sheet since v2.10, library rows
        // since v2.12). Its sections now pour in with the same
        // Fx.staggerChildren 45ms-step fade + rise, on a fresh
        // open only: rotations (saved state) and back-stack
        // returns (onResume refreshes, never re-cascades) stay
        // still, and the post() inside staggerChildren runs after
        // the layout pass so off-screen sections simply finish
        // before they are ever scrolled into view. Visual only.
        if (savedInstanceState == null) {
            android.view.ViewGroup content =
                    findViewById(R.id.content);
            if (content != null) Fx.staggerChildren(content);
        }
        // v10.47: re-show the picker that was open before rotation, posted
        // so it lands after the layout pass (a dialog shown straight from
        // onCreate can attach before the window is ready). A null or
        // unknown value re-shows nothing; the dismiss listener clears the
        // field, so a second rotation re-shows it again, never twice.
        if (savedInstanceState != null) {
            final String restored = savedInstanceState.getString("openPicker", null);
            if ("font".equals(restored) || "lang".equals(restored) || "sleep".equals(restored) || "clearData".equals(restored)) {
                openPicker = restored;
                if (root != null) root.post(new Runnable() {
                    @Override public void run() {
                        if ("font".equals(openPicker)) showFontDialog();
                        else if ("lang".equals(openPicker)) showLangDialog();
                        else if ("sleep".equals(openPicker)) showSleepDialog();
                        else if ("clearData".equals(openPicker)) showClearDataDialog();
                    }
                });
            }
        }
    }

    // v13.56: Developer Profile pop-up (his order 2026-11-10 ~20:18 +07).
    // His photo is the card background (center-cropped), his name below it,
    // and the Facebook link is the real "f" icon only — no text link. Tapping
    // the icon opens his Facebook profile (share link) in the browser or the
    // Facebook app; no browser toasts open_failed. Rounded corners via
    // clipToOutline on the root; the window itself is transparent.
    private void showDeveloperProfileDialog() {
        final android.app.Dialog dlg = new android.app.Dialog(SettingsActivity.this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dlg.setContentView(R.layout.dialog_dev_profile);
        dlg.setCancelable(true);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        android.view.View root = dlg.findViewById(R.id.devProfileRoot);
        if (root != null) {
            final int radiusPx = (int) (24 * getResources().getDisplayMetrics().density + 0.5f);
            root.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(android.view.View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
                }
            });
            root.setClipToOutline(true);
        }
        android.view.View fb = dlg.findViewById(R.id.devProfileFb);
        if (fb != null) fb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.morph(v);
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.facebook.com/share/1ExQj88xmi/")));
                } catch (Exception e) {
                    android.widget.Toast.makeText(SettingsActivity.this, Lang.t(SettingsActivity.this, "open_failed"), android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
        android.view.View close = dlg.findViewById(R.id.devProfileClose);
        if (close != null) close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        dlg.show();
    }

    // v1.30: clear favourite sayadaws + favourite talks in one tap
    // (history, listened marks and downloads are untouched). v12.94:
    // reached from the single "clear data" row's chooser, not its own button.
    private void showClearFavsDialog() {
new android.app.AlertDialog.Builder(SettingsActivity.this)
                .setTitle(Lang.t(SettingsActivity.this, "dlg_favs_title"))
                .setMessage(Lang.t(SettingsActivity.this, "dlg_favs_msg"))
                .setPositiveButton(Lang.t(SettingsActivity.this, "btn_clear"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                                .remove("favs").remove("favTalks").apply();
                        android.widget.Toast.makeText(SettingsActivity.this,
                                Lang.t(SettingsActivity.this, "toast_favs"), android.widget.Toast.LENGTH_SHORT).show();
                        // v10.11: live-sync family, Settings clear-favourites leg of v10.02 — this is the only site that clears favTalks, and it never synced the live service, so a PAUSED sounding talk that had been favourited kept its ★ on the notification card until the next tap (rebuilds run every 15s only while playing, never while paused; the card reads favTalks at rebuild time). Reset-all already syncs but never clears favs, so it never covered this. syncLiveService binds with flag 0, never starts a stopped service, and a rebuild with no star change paints the identical card.
                        syncLiveService();
                    }
                })
                .setNegativeButton(Lang.t(SettingsActivity.this, "btn_no_clear"), null)
                .show();
    }

    // v1.19: clear play history + resume bar (downloads untouched).
    // v12.94: reached from the "clear data" chooser.
    private void showClearHistoryDialog() {
new android.app.AlertDialog.Builder(SettingsActivity.this)
                .setTitle(Lang.t(SettingsActivity.this, "dlg_hist_title"))
                .setMessage(Lang.t(SettingsActivity.this, "dlg_hist_msg"))
                .setPositiveButton(Lang.t(SettingsActivity.this, "btn_clear"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        clearHistoryWithLiveGuard();
                    }
                })
                .setNegativeButton(Lang.t(SettingsActivity.this, "btn_no_clear"), null)
                .show();
    }

    // v1.28: clear only the listened ✓ marks on talk rows (history,
    // favourites and downloads are untouched). v12.94: chooser-reached.
    private void showClearPlayedDialog() {
new android.app.AlertDialog.Builder(SettingsActivity.this)
                .setTitle(Lang.t(SettingsActivity.this, "dlg_played_title"))
                .setMessage(Lang.t(SettingsActivity.this, "dlg_played_msg"))
                .setPositiveButton(Lang.t(SettingsActivity.this, "btn_clear"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                                .remove("playedTalks").apply();
                        android.widget.Toast.makeText(SettingsActivity.this,
                                Lang.t(SettingsActivity.this, "toast_played"), android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(Lang.t(SettingsActivity.this, "btn_no_clear"), null)
                .show();
    }

    // v12.94: one "clear data" row replaces the three clear buttons —
    // a chooser names the three clears, each opening its own confirm
    // dialog above (same titles, messages, toasts and clears as before).
    private void showClearDataDialog() {
        final String[] items = {Lang.t(this, "clear_favs"), Lang.t(this, "clear_history"), Lang.t(this, "clear_played")};
        // v13.16: record the open chooser like font/lang/sleep, so a
        // rotation re-shows it instead of losing it (see the onCreate
        // restore); the dismiss listener clears it, so a dismissed
        // chooser never re-shows.
        openPicker = "clearData";
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "clear_data"))
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) showClearFavsDialog();
                        else if (which == 1) showClearHistoryDialog();
                        else showClearPlayedDialog();
                    }
                })
                .setNegativeButton(Lang.t(this, "btn_no_clear"), null)
                .create();
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                if ("clearData".equals(openPicker)) openPicker = null;
            }
        });
        dlg.show();
    }

    // v10.47: extracted verbatim from the btnSleep click listener so a
    // rotation-restored picker can re-open it (see onCreate restore); the
    // listener below now just morphs + calls this. No behaviour change.
    private void showSleepDialog() {
        final String off = Lang.t(SettingsActivity.this, "off");
        final String min = Lang.t(SettingsActivity.this, "sleep_min");
        // v8.57: 5 minutes restored, mirroring the strip picker —
        // the v1.56 cycle had it, the v4.14 picker dropped it, so
        // no current UI could pick 5 minutes, and a stored
        // 5-minute pref read as "off" checked here while still
        // arming on sync. Same picker, one more item; the
        // checked loop below already walks vals.
        final int[] vals = {0, 5, 15, 30, 60};
        // v4.82: one-shot "stop at end of talk" as the last
        // item, mirroring the strip picker — it arms the
        // live service without touching the minute pref.
        final String endLabel = Lang.t(SettingsActivity.this, "sleep_end");
        final String[] items = {off, "5 " + min, "15 " + min, "30 " + min, "60 " + min, endLabel};
        int cur = Prefs.sleepMinutes(SettingsActivity.this);
        int checked = AudioService.liveSleepEndOfTalk ? items.length - 1 : 0;
        if (!AudioService.liveSleepEndOfTalk)
            for (int i = 0; i < vals.length; i++) if (vals[i] == cur) checked = i;
        openPicker = "sleep";
        android.app.AlertDialog sleepDlg = new android.app.AlertDialog.Builder(SettingsActivity.this)
                .setTitle(Lang.t(SettingsActivity.this, "sleep"))
                .setSingleChoiceItems(items, checked, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        d.dismiss();
                        if (which == items.length - 1) {
                            // v10.43: bound-trackless stop — liveSession stays true but the mirror is false, so a trackless pick toasts sleep_end_need instead of claiming an armed one-shot the handler drops.
                            if (AudioService.liveSession && AudioService.liveHasTrack) {
                                android.content.Intent i = new android.content.Intent(SettingsActivity.this, AudioService.class);
                                i.setAction(AudioService.ACTION_SLEEP_END);
                                startService(i);
                                android.widget.Toast.makeText(SettingsActivity.this, Lang.t(SettingsActivity.this, "sleep") + ": " + endLabel, android.widget.Toast.LENGTH_SHORT).show();
                            } else {
                                android.widget.Toast.makeText(SettingsActivity.this, Lang.t(SettingsActivity.this, "sleep_end_need"), android.widget.Toast.LENGTH_SHORT).show();
                            }
                            Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } });
                            return;
                        }
                        Prefs.setSleepMinutes(SettingsActivity.this, vals[which]);
                        Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } });
                        // v8.56: an explicit pick, not a routine sync — the
                        // pick intent applies the minutes directly and
                        // clears an armed one-shot even when the pick
                        // equals the pref it was armed with, where
                        // syncLiveService's routine guard would leave
                        // the one-shot armed (see AudioService).
                        Fx.applySleepPick(SettingsActivity.this, vals[which]);
                    }
                })
                .setNegativeButton(Lang.t(SettingsActivity.this, "dlg_close"), null)
                .create();
        sleepDlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                if ("sleep".equals(openPicker)) openPicker = null;
            }
        });
        sleepDlg.show();
    }

    // v9.11: pickers for the single-row font/language rows — the same
    // AlertDialog single-choice shape as the sleep picker, so the whole
    // screen follows one pattern: one row, picker on tap. No new view,
    // no new string: items come from Lang.
    private void showFontDialog() {
        final String[] items = {Lang.t(this, "font_small"), Lang.t(this, "font_normal"), Lang.t(this, "font_large")};
        // v11.88: clamp the stored size to the 3-item list — Prefs.fontSize has no range
        // check and every other consumer treats an out-of-range value as large via its
        // ternary, but this checked index goes straight to the dialog; a corrupt stored
        // value outside 0–2 would hand it an out-of-range position. Valid prefs read
        // exactly as before.
        int checked = Math.max(0, Math.min(2, Prefs.fontSize(this)));
        openPicker = "font";
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "font_label"))
                .setSingleChoiceItems(items, checked, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        d.dismiss();
                        int o = Prefs.fontSize(SettingsActivity.this);
                        Prefs.setFontSize(SettingsActivity.this, which);
                        if (o != which) Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } });
                        else refresh();
                    }
                })
                .setNegativeButton(Lang.t(this, "dlg_close"), null)
                .create();
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                if ("font".equals(openPicker)) openPicker = null;
            }
        });
        dlg.show();
    }

    private void showLangDialog() {
        final String[] items = Lang.NAMES;
        int checked = Lang.index(this);
        openPicker = "lang";
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "lang_label"))
                .setSingleChoiceItems(items, checked, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        d.dismiss();
                        String o = Prefs.lang(SettingsActivity.this);
                        Prefs.setLang(SettingsActivity.this, Lang.LANGS[which]);
                        // v10.00: a language pick never synced the live service, so a
                        // talk already playing kept its notification card in the OLD
                        // language (state/action/queue/sleep labels are built from Lang
                        // at refresh time) until the next play/pause tap or a Room /
                        // Library return (the only onResume paths that refresh it);
                        // Main/Talks returns never refresh it. The noti toggle, sleep
                        // and reset-all all call syncLiveService() at once for exactly
                        // this class of change -- the language pick is the outlier.
                        // Syncing here (bind flag 0: fails silently when nothing is
                        // playing) rebuilds the card in the new language at once, on
                        // every return path. No new string, no new UI.
                        if (!o.equals(Lang.LANGS[which])) syncLiveService();
                        if (!o.equals(Lang.LANGS[which])) Fx.themeDip(root, new Runnable() { @Override public void run() { refresh(); } });
                        else refresh();
                    }
                })
                .setNegativeButton(Lang.t(this, "dlg_close"), null)
                .create();
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                if ("lang".equals(openPicker)) openPicker = null;
            }
        });
        dlg.show();
    }

    // v1.81: count this screen as on top / schedule the background
    // check (see AppForeground) — a Home press from Settings used to
    // keep playing even with background-playback-off, and a Settings
    // visit must not look like "left the app" to another screen's
    // check while it sits on top.
    // v10.21: Settings prune-drop leg of the v10.06/v10.14/v10.15/v10.16/
    // v10.17/v10.18/v10.19/v10.20 download-mark sync — Settings was
    // the one surface left with a dlQueued-reading strip but NO
    // prune. Its live strip's global " ⏳ N" queries live
    // DownloadManager jobs every tick (v5.68), so it never goes
    // stale; but its resume strip (dead Room session, ↺, room
    // branch) calls Fx.stripResumeDownloadMark on every 1s tick,
    // and that helper reads dlQueued raw, so a job that FAILED,
    // was cancelled, or was cleared left its ⏳ on the ↺ strip
    // until some Room/Talks/Library/Main rescan pruned it.
    // Mirrors the Main v10.20 rule, Settings-adapted (no talk
    // list): the landed leg is the shared DlLanded definition, and
    // a landed job's DM row is STATUS_SUCCESSFUL, hence not a live
    // row, so its entry drops by the landed leg or the same
    // no-live-job rule.
    // v12.15: the unmapped keep ends here. The v10.21 keep cited
    // "the Talks conservative branch", but Talks abandoned that
    // branch in v11.86 and Room (v11.85) / Library (v12.14) /
    // Main (v12.15) followed: all four enqueue sites record
    // dlMap at-or-before dlQueued (Room recordDownloadFile
    // before addQueuedDownload, Talks recordTalkDownload, Fx
    // stripEnqueueDownload in one edit), so a map-less entry is
    // a legacy remnant or torn write whose job is dead — drop it
    // when none of its filename candidates names a live job, the
    // shared rule. Keeping it left its ⏳ on the resume strip
    // (Fx.stripResumeDownloadMark reads dlQueued raw) until some
    // Room/Talks/Library/Main rescan pruned it, and this
    // screen's own onResume/completion rescans never could. A
    // failed query keeps everything.
    // Returns the dropped URLs (empty when nothing dropped or on
    // any failure); writes back only when something actually dropped.
    private java.util.Set<String> pruneSettingsQueuedDownloads1021() {
        // v13.68 (audit-1369 F11): dlMap/dlSayadaw/dlQueued read-modify-write - serialize on DlLock.LOCK (see DlLock).
        synchronized (DlLock.LOCK) {
        java.util.Set<String> dropped1021 = new java.util.HashSet<String>();
        try {
            android.content.SharedPreferences sp1021 =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONArray arr1021 = new org.json.JSONArray(
                    sp1021.getString("dlQueued", "[]"));
            if (arr1021.length() == 0) return dropped1021;
            java.util.Set<String> queued1021 = new java.util.HashSet<String>();
            for (int i1021 = 0; i1021 < arr1021.length(); i1021++)
                queued1021.add(arr1021.optString(i1021));
            org.json.JSONObject map1021 = new org.json.JSONObject(
                    sp1021.getString("dlMap", "{}"));
            java.util.Set<String> activeSources1021 = new java.util.HashSet<String>();
            java.util.Set<String> activeNames1021 = null;
            try {
                android.app.DownloadManager dm1021 = (android.app.DownloadManager)
                        getSystemService(android.content.Context.DOWNLOAD_SERVICE);
                android.database.Cursor c1021 = dm1021.query(
                        new android.app.DownloadManager.Query());
                // v12.14: the set is created only after a non-null cursor is
                // confirmed (Talks v11.76 / Main v11.78 port) — a null cursor
                // used to leave an empty non-null set, so the drop loop below
                // wiped every live queued entry as "not active", although the
                // comment above promises a failed query keeps everything.
                if (c1021 != null) {
                    activeNames1021 = new java.util.HashSet<String>();
                    try {
                        int uriCol1021 = c1021.getColumnIndex(
                                android.app.DownloadManager.COLUMN_LOCAL_URI);
                        int srcCol1021 = c1021.getColumnIndex(
                                android.app.DownloadManager.COLUMN_URI);
                        int statusCol1021 = c1021.getColumnIndex(
                                android.app.DownloadManager.COLUMN_STATUS);
                        while (c1021.moveToNext()) {
                            int st1021 = statusCol1021 >= 0 ? c1021.getInt(statusCol1021) : -1;
                            if (st1021 >= 0
                                    && st1021 != android.app.DownloadManager.STATUS_RUNNING
                                    && st1021 != android.app.DownloadManager.STATUS_PAUSED
                                    && st1021 != android.app.DownloadManager.STATUS_PENDING) continue;
                            String src1021 = srcCol1021 >= 0 ? c1021.getString(srcCol1021) : null;
                            String uri1021 = uriCol1021 >= 0 ? c1021.getString(uriCol1021) : null;
                            if (src1021 != null && !queued1021.contains(src1021)
                                    && (uri1021 == null || !uri1021.contains("Dhamma/"))) continue;
                            if (src1021 == null && uri1021 != null && !uri1021.contains("Dhamma/")) continue;
                            if (src1021 != null) activeSources1021.add(src1021);
                            if (uri1021 != null) {
                                // v12.14: decode before the basename (Room v11.81 /
                                // Talks v11.86 port) — a filename carrying a %XX
                                // escape arrives here as %25XX and never matched
                                // the dlMap name, so the prune dropped a live job.
                                String dec1021 = android.net.Uri.decode(uri1021);
                                if (dec1021 == null) dec1021 = uri1021;
                                int slash1021 = dec1021.lastIndexOf('/');
                                activeNames1021.add(slash1021 >= 0 ? dec1021.substring(slash1021 + 1) : dec1021);
                            }
                        }
                    } finally { c1021.close(); }
                }
            } catch (Exception ignored) { activeNames1021 = null; }
            if (activeNames1021 == null) return dropped1021;
            java.util.Iterator<String> it1021 = queued1021.iterator();
            while (it1021.hasNext()) {
                String url1021 = it1021.next();
                // v13.69 (audit-1369 F13): the landed leg is the shared
                // DlLanded definition now - Settings had none; a landed
                // file's entry used to drop only by the no-live-job
                // rule below. Same drop, one definition everywhere.
                if (DlLanded.isDownloaded(this, url1021, activeNames1021, activeSources1021)) {
                    it1021.remove(); dropped1021.add(url1021); continue;
                }
                if (activeSources1021.contains(url1021)) continue;
                String mapped1021 = map1021.optString(url1021, "");
                if (mapped1021.length() > 0) {
                    if (!activeNames1021.contains(mapped1021)) {
                        it1021.remove();
                        dropped1021.add(url1021);
                    }
                } else {
                    // v12.15: map-less branch (Room v11.85 / Talks v11.86 /
                    // Library v12.14 / Main v12.15 port) — see the header:
                    // a queued URL with no dlMap entry is a legacy remnant
                    // whose job is dead; drop it when none of its filename
                    // candidates names a live job. A live job's candidates
                    // always match (its source URL is guarded above, its
                    // destination basename is one of the candidates), and
                    // a failed query keeps everything (activeNames null
                    // returns before this loop).
                    boolean anyActive15 = false;
                    for (String cand15 : fileNameCandidates15(url1021)) {
                        if (cand15 != null && activeNames1021.contains(cand15)) { anyActive15 = true; break; }
                    }
                    if (!anyActive15) { it1021.remove(); dropped1021.add(url1021); }
                }
            }
            if (!dropped1021.isEmpty()) {
                org.json.JSONArray out1021 = new org.json.JSONArray();
                for (String u1021 : queued1021) out1021.put(u1021);
                sp1021.edit().putString("dlQueued", out1021.toString()).apply();
            }
            return dropped1021;
        } catch (Exception ignored) { return dropped1021; }
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

    // v10.21: prune + paused-card refresh for Settings, the v10.15/
    // v10.17/v10.19/v10.20 shape — the no-bind refresh intent goes
    // out only when a dropped URL is the sounding talk (room:
    // dropped == lastUrl; library: dlMap[dropped] file ==
    // lastLibPath file, read fresh) and a session is live, so a
    // dead session is never started. The ↺ strip itself needs no
    // intent: it re-syncs on the 1s tick, so the pruned entry's ⏳
    // clears on the next pass.
    private void pruneSettingsQueuedAndRefreshCard1021() {
        try {
            java.util.Set<String> dropped1021 = pruneSettingsQueuedDownloads1021();
            if (dropped1021.isEmpty() || !AudioService.liveSession) return;
            android.content.SharedPreferences rsp1021 =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String lastUrl1021 = rsp1021.getString("lastUrl", "");
            String libPath1021 = rsp1021.getString("lastLibPath", "");
            org.json.JSONObject map1021 = new org.json.JSONObject(
                    rsp1021.getString("dlMap", "{}"));
            boolean cardTalk1021 = false;
            for (String d1021 : dropped1021) {
                if (d1021.equals(lastUrl1021)) { cardTalk1021 = true; break; }
                if (libPath1021.length() > 0) {
                    String fn1021 = map1021.optString(d1021, "");
                    if (fn1021.length() > 0 && fn1021.equals(new java.io.File(libPath1021).getName())) {
                        cardTalk1021 = true; break;
                    }
                }
            }
            if (cardTalk1021) {
                android.content.Intent rsv1021 = new android.content.Intent(SettingsActivity.this, AudioService.class);
                rsv1021.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                startService(rsv1021);
            }
        } catch (Exception ignored) {}
    }

    // v10.47: save the open picker (see the openPicker field note).
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("openPicker", openPicker);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppForeground.activityResumed();
        pruneSettingsQueuedAndRefreshCard1021(); // v10.21: Settings prune leg
        // v1.86: choices and colours were only drawn in onCreate, so
        // returning to a Settings screen that sat in the back stack
        // showed stale values (same class as the library's v1.60
        // theme fix) — re-sync on every return.
        // v3.71: premium Settings return theme/font dip, completing
        // the return-dip family (Main v2.98, Talks v2.99, Room
        // v3.00, library v3.01) on the fifth and last screen — a
        // theme/font change landing while Settings sits in the back
        // stack used to recolour + resize the whole screen in one
        // frame on return, while every other screen dips. A real
        // change since refresh() last drew now dips with the
        // existing Fx.themeDip (140ms down to 0.55, swap at the
        // dip, 260ms rise to exactly alpha 1). Guards: refresh()
        // itself syncs the cache (see its tail), so the onCreate
        // pass, every choice tap and every dip swap all leave the
        // cache at the drawn values — the first onResume and
        // rotations never dip (no diff), and a return with nothing
        // changed stays instant, so back-stack navigation never
        // dips pointlessly and a tap made in Settings itself never
        // re-dips on the next return; the dip's swap is refresh()
        // itself, no second call; the dip lives on the root while
        // the fresh-open cascade (v2.14) lives on the content
        // children, so the two never fight. Visual only.
        boolean stThemeChanged = Prefs.isNight(this) != cachedStNight
                || Prefs.fontSize(this) != cachedStFont;
        if (stThemeChanged && root != null) {
            Fx.themeDip(root, new Runnable() {
                @Override public void run() { refresh(); }
            });
        } else {
            refresh();
        }
        startSleepWatch(); // v4.12, below
        startSettingsStripTick(); // v4.41, below
        // v10.10: listen for download completions while foreground —
        // card-only hook completing the live-sync family on the
        // fifth and last screen (Room v10.06, Talks v10.07, Main
        // v10.08, library v10.09). Settings has no rows to rescan
        // and its strip download count + Downloaded button counts
        // already follow the 1s tick (v5.68/v7.53), so nothing in-app
        // goes stale — only the shade/lock-screen card does, its
        // mark rebuilding every 15s while playing, never while
        // paused, so a download landing for a PAUSED sounding talk
        // left the card on its old mark until the next tap. Any
        // completion fires it (the broadcast is device-wide), but
        // the card send is gated on the finished job being the
        // sounding talk, so unrelated system downloads never
        // re-post the card and never start a dead service
        // (liveSession guard, v10.03 shape). The card leg toasts
        // nothing — the card's own mark flipping is the feedback;
        // a FAILED job toasts via the receiver's own fail block
        // (v12.36).
        if (!settingsDlCompleteRegistered) {
            if (settingsDlCompleteReceiver == null) {
                settingsDlCompleteReceiver = new android.content.BroadcastReceiver() {
                    @Override public void onReceive(android.content.Context c, android.content.Intent in) {
                        pruneSettingsQueuedAndRefreshCard1021(); // v10.21: Settings prune leg
                        // v12.36: a FAILED job used to die silently on
                        // this screen — the last receiver without the
                        // failed-download toast (Talks v12.29, Room +
                        // Library v12.30, Main v12.35; the v12.35 note
                        // called Main the last one, but this fifth
                        // receiver was missed). A queued download that
                        // fails (Wi-Fi wait, then a network drop
                        // mid-file) while Settings is foreground lost
                        // its ⏳ — the strip's global " ⏳ N" drops on
                        // the next 1s tick, the resume strip's ⏳ mark
                        // clears after the prune above, and the
                        // Downloaded button's "· ⏳ M" drops on the
                        // next tick — with no file and no word why,
                        // and only one screen's receiver is registered
                        // at a time, so while Settings is on top
                        // nobody toasted the failure. A failure now
                        // toasts "download_failed" + the talk title,
                        // the same string shape as the enqueue catch.
                        // Separate try, BEFORE the card-sync block:
                        // that block returns early on !liveSession,
                        // but a download can fail while nothing plays
                        // — the toast must not be gated on a live
                        // session. Gated on the source URL being a
                        // talk this app queued (dlMap only — no talk
                        // list lives on this screen, the Main v12.35
                        // shape), so an unrelated system download
                        // failing while this screen is open never
                        // toasts here; the prune above touches
                        // dlQueued only, never dlMap, so the gate
                        // still reads true after it. Title falls back
                        // COLUMN_TITLE -> URL. The broadcast fires
                        // once per job, so the toast fires once.
                        // Success toasts nothing — the strip's ⏳ N
                        // drop is the feedback.
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
                                                        android.widget.Toast.makeText(SettingsActivity.this,
                                                                Lang.t(SettingsActivity.this, "download_failed") + failTitle,
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
                                android.content.Intent rsv = new android.content.Intent(SettingsActivity.this, AudioService.class);
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
                    registerReceiver(settingsDlCompleteReceiver, f, android.content.Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(settingsDlCompleteReceiver, f);
                }
                settingsDlCompleteRegistered = true;
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopSettingsStripTick(); // v4.41, below
        // v10.10: stop listening while hidden — onResume re-registers.
        if (settingsDlCompleteRegistered && settingsDlCompleteReceiver != null) {
            try { unregisterReceiver(settingsDlCompleteReceiver); } catch (Exception ignored) {}
            settingsDlCompleteRegistered = false;
        }
        stopSleepWatch(); // v4.12, below
        AppForeground.activityPaused();
        AppForeground.scheduleBackgroundCheck(this);
    }

    // v11.88: sweep any one-shot live-sync bind whose onServiceConnected never fired
    // (syncLiveConns88, see syncLiveService) — a double unbind against a callback
    // that already unbound itself throws and is swallowed here, so the sweep can
    // never crash a destroy.
    @Override
    protected void onDestroy() {
        try {
            for (android.content.ServiceConnection c88
                    : new java.util.ArrayList<android.content.ServiceConnection>(syncLiveConns88)) {
                try { unbindService(c88); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        syncLiveConns88.clear();
        // v13.08: detach the liquid-glass surface — the drawable is
        // the view's background and its callback pins the dead view +
        // its destroyed activity in GlassBlur's static map on every
        // rotation (the v13.05 weak view ref did not fix this).
        GlassBlur.detach(findViewById(R.id.nowStrip));
        super.onDestroy();
    }

    // v4.41: Settings mini-player sync + tick — the same strip
    // Main (v4.30), Talks (v4.31) and the library (v4.40) carry,
    // read from prefs + the service's live statics with no bind,
    // so it stays correct while the user changes settings
    // mid-talk. Title-over-sub split, ★ favourite mark, 😴 sleep
    // mark, finished mark, live progress and the live play/pause
    // glyph; font steps match the other strips (v4.39). The ⏳
    // download-count scan was deliberately left out until
    // v5.68, like the Talks strip (v4.31): Settings already
    // shows download state on its own Downloaded button and
    // footer — but the strip is what the eye lands on first
    // while tuning settings mid-talk, so it now carries the
    // count like Main (v4.27), Talks and the library (v5.67).
    // v6.03: sayadaw portrait on the Settings strip — Main's v6.00,
    // Talks' v6.01 and the library's v6.02 mirror, completing
    // portrait parity across all four mini-player surfaces. The
    // Settings strip was the last text-only one: a listener tuning
    // sleep or data saver mid-talk saw only title over name.
    // Resolves through AudioService's once-per-process catalog map
    // (Settings holds no catalog list, like Talks/library), shown
    // through ImageLoader.load so a never-cached portrait reads as
    // that sayadaw's own gold initial circle until its download
    // lands, exactly like a grid row. A library session with no
    // recorded sayadaw keeps today's text-only strip, never a
    // guessed face. Called at every strip visibility edge below,
    // so a hidden strip never leaves a stale face behind, and a
    // session switch lands the new face on the same tick.
    private void syncSettingsStripPhoto(String name) {
        android.view.View pv = findViewById(R.id.nowStripPhoto);
        if (pv == null) return;
        if (name == null || name.length() == 0) {
            settingsStripPhotoName = null;
            pv.setVisibility(android.view.View.GONE);
            return;
        }
        pv.setVisibility(android.view.View.VISIBLE);
        if (name.equals(settingsStripPhotoName)) return;
        settingsStripPhotoName = name;
        String url = AudioService.stripPhotoUrlForSayadaw(SettingsActivity.this, name);
        ImageLoader.load(SettingsActivity.this, url, name, (CircleImageView) pv);
    }

    private void syncSettingsStrip() {
        android.view.View strip = findViewById(R.id.nowStrip);
        if (strip == null) return;
        boolean night = Prefs.isNight(this);
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
            // v4.64: continue-listening strip for a dead session —
            // Settings was the last mini-player surface without
            // one (Main v4.60/v4.63, Talks v4.61, library v4.62):
            // after a reboot or system kill it showed no strip at
            // all even though a saved spot (>5s, the same
            // threshold pause() saves by) was waiting. Same
            // validation, presentation and guards as Main's
            // strips, verbatim: Room kind needs name/title/page
            // plus a lastPosUrl match (direct, or the file name
            // via dlMap); library kind needs an existing file
            // plus a path match (direct, or the Room URL via the
            // dlMap reverse lookup). An explicit stop already
            // clears the spot (v1.57), so a stopped talk never
            // shows here. No new view: Next hidden (advancing
            // needs a live track), progress empty (no duration
            // survives a dead session). Tap routes: Room via the
            // existing autoUrl path, library via autoPlayPath
            // (v4.63 mechanism, added to the tap above).
            settingsResumeStripMode = false;
            String rKind = sp.getString("lastKind", "");
            int rPos = sp.getInt("lastPos", 0);
            String rSavedUrl = sp.getString("lastPosUrl", "");
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
                            || Fx.stripInProgressDlFilenames(SettingsActivity.this, sp).contains(new java.io.File(lPath).getName()))) {
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
                    syncSettingsStripPhoto(null);
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
                // v8.52: Main's v7.94 resume mark, mirrored — the proven file is offline, like Main's lib branch.
                if (tx != null) tx.setText("↺ " + lTitle + (lFav ? " ★" : "") + (Fx.stripInProgressDlFilenames(SettingsActivity.this, sp).contains(new java.io.File(lPath).getName()) ? " ⏳" : " ⬇"));
                // v5.46: a dead library strip names its recorded
                // sayadaw like the live strip since v5.45, in the
                // Room strip's "name · mm:ss မှ ဆက်နားထောင်ရန်"
                // shape; an unrecorded download keeps today's
                // generic line, never a guessed name.
                String libSd = Fx.stripLibSayadaw(SettingsActivity.this, sp);
                if (sb != null) sb.setText(libSd != null ? libSd + " · " + lTime + Lang.t(SettingsActivity.this, "resume_from") : Lang.t(SettingsActivity.this, "downloaded_talk") + " · " + lTime + Lang.t(SettingsActivity.this, "resume_from"));
                if (tg != null) tg.setText("▶");
                if (nx != null) nx.setVisibility(android.view.View.GONE);
                // v7.36: Previous hides with Next in resume mode —
                // stepping back needs a live track exactly like
                // advancing does (Main v7.31, library v7.35).
                // Settings was the last strip whose sync never
                // hid ⏮, so a ↺ strip here openly showed it.
                android.view.View pvPrev0 = findViewById(R.id.nowStripPrev);
                if (pvPrev0 != null) pvPrev0.setVisibility(android.view.View.GONE);
                android.widget.ProgressBar lPb = findViewById(R.id.nowStripProgress);
                if (lPb != null) lPb.setProgress(0);
                settingsResumeStripMode = true;
                syncSettingsStripPhoto(libSd);
                strip.setVisibility(android.view.View.VISIBLE);
                return;
            }
            String rName = sp.getString("lastName", "");
            String rTitle = sp.getString("lastTitle", "");
            String rPage = sp.getString("lastPage", "");
            String rUrl = sp.getString("lastUrl", "");
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
            if (!"room".equals(rKind) || rName.length() == 0 || rTitle.length() == 0
                    || rPage.length() == 0 || rPos <= 5000 || !rMatch) {
                syncSettingsStripPhoto(null);
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
            // v8.52: Main's v7.94 resume download mark, mirrored — a ↺ strip here never said whether resuming streams or plays offline.
            if (tx != null) tx.setText("↺ " + rTitle + (rFav ? " ★" : "") + Fx.stripResumeDownloadMark(SettingsActivity.this, sp, rUrl));
            if (sb != null) sb.setText(rName + " · " + rTime + Lang.t(SettingsActivity.this, "resume_from"));
            if (tg != null) tg.setText("▶");
            if (nx != null) nx.setVisibility(android.view.View.GONE);
            // v7.36: same Previous hide as the library resume
            // branch above — a ↺ strip never shows ⏮.
            android.view.View pvPrev0 = findViewById(R.id.nowStripPrev);
            if (pvPrev0 != null) pvPrev0.setVisibility(android.view.View.GONE);
            android.widget.ProgressBar rPb = findViewById(R.id.nowStripProgress);
            if (rPb != null) rPb.setProgress(0);
            settingsResumeStripMode = true;
            syncSettingsStripPhoto(rName);
            strip.setVisibility(android.view.View.VISIBLE);
            return;
        }
        settingsResumeStripMode = false;
        if (nx != null) nx.setVisibility(android.view.View.VISIBLE);
        // v7.36: Previous returns with Next on a live session
        // (Main v7.31, library v7.35 mirrors).
        android.view.View pvPrev0 = findViewById(R.id.nowStripPrev);
        if (pvPrev0 != null) pvPrev0.setVisibility(android.view.View.VISIBLE);
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
                String libSd = Fx.stripLibSayadaw(SettingsActivity.this, sp);
                subText = libSd != null ? libSd : Lang.t(SettingsActivity.this, "downloaded_talk");
            }
        }
        if (text == null) { syncSettingsStripPhoto(null); strip.setVisibility(android.view.View.GONE); return; }
        try {
            java.util.Set<String> ft = sp.getStringSet("favTalks", null);
            if (ft != null) {
                boolean stripFav = false;
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
                if (stripFav) text += " ★";
            }
        } catch (Exception ignored) {}
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
                    ? " 😴 " + Lang.t(SettingsActivity.this, "sleep_end")
                    : " 😴 ⏸ " + Lang.t(SettingsActivity.this, "sleep_end");
            else if (sr > 0) text += AudioService.livePlaying
                    ? " 😴 " + AudioService.sleepClock(sr)
                    : " 😴 ⏸ " + AudioService.sleepClock(sr);
            else text += " 😴";
        }
        // v5.68: the Settings strip names downloads in progress
        // (" ⏳ N") — the mirror of Main's v4.27 strip suffix and
        // the Talks/library v5.67 mirrors, completing strip
        // parity: Settings was the last strip without it. A
        // listener tuning settings mid-talk saw only ★/😴/◈
        // here while a download ran, even though the Downloaded
        // button and footer below already count ⏳ — the strip
        // is what the eye lands on first, and it is the surface
        // with the Next button, so the pending cue belongs on
        // it too. Same app-scoped rule as Main (Dhamma
        // destination or a dlMap source URL), same order
        // (★/😴/⏳/◈), same 1s tick refresh; zero adds nothing,
        // resume mode returns above, no new view. Suffix only.
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
        // v4.68: the Settings strip reads the hand queue — the
        // mirror of Main's v4.67 strip suffix, completing strip
        // parity (Main v4.67, Talks + library this run). Same
        // counting rule as Main, so every strip agrees: Room
        // sessions count this sayadaw's saved queue
        // (Prefs.savedQueue, the store the Room restores from),
        // library sessions count saved queue paths that still
        // exist as files. Suffix only, no new view; the 1s tick
        // re-reads it. Resume mode is untouched (it returns
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
            // suffix's visibility rule (saved queue non-empty,
            // v4.77) is untouched; an all-playing queue still
            // toasts empty on open, as today.
            String curUrl = sp.getString("lastUrl", "");
            String curLib = sp.getString("lastLibPath", "");
            int qN = 0;
            if ("room".equals(kind)) {
                String saved = Prefs.savedQueue(SettingsActivity.this,
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
                String saved = Prefs.savedQueueLib(SettingsActivity.this);
                if (saved.length() > 0) {
                    for (String line : saved.split("\n"))
                        if (line.trim().length() > 0
                                && new java.io.File(line.trim()).isFile()
                                && !line.trim().equals(curLib)) qN++;
                }
            }
            if (qN > 0) text += " · ◈ " + qN;
        } catch (Exception ignored) {}
        if (AudioService.liveEnded) text += " · " + Lang.t(SettingsActivity.this, "notif_state_ended");
        if (tx != null) tx.setText(text);
        // v5.32: live clock on the strip — sayadaw name + elapsed/total.
        android.widget.ProgressBar pb = findViewById(R.id.nowStripProgress);
        if (subText != null) subText += Fx.stripClockSuffixForStrip(pb, AudioService.livePositionMs, AudioService.liveDurationMs, Prefs.stripClockRemaining(SettingsActivity.this));
        if (sb != null && subText != null) sb.setText(subText);
        Fx.syncStripProgress(pb); // v7.93: skips while a mini-seek drag is in flight
        if (tg != null) tg.setText(AudioService.livePlaying ? "\u23F8\uFE0E" : "▶");
        // v6.03: live strip's portrait — Room names lastName, library
        // names its recorded sayadaw (Fx.stripLibSayadaw, null when
        // unrecorded); both resolve through syncSettingsStripPhoto's
        // catalog-map lookup, never a guessed face.
        syncSettingsStripPhoto("room".equals(kind) ? sp.getString("lastName", "")
                : ("lib".equals(kind) ? Fx.stripLibSayadaw(SettingsActivity.this, sp) : null));
        strip.setVisibility(android.view.View.VISIBLE);
    }

    private void startSettingsStripTick() {
        stopSettingsStripTick();
        stripTick = new Runnable() {
            @Override public void run() {
                if (stripTick == null) return;
                // v14.03: re-post in finally — a throw inside syncSettingsStrip
                // (or the button refreshes below) used to kill the 1s tick
                // outright, freezing strip title/clock/progress/glyph plus
                // the download/data-saver/blocked marks until the next
                // onPause/onResume cycle. Main got the same guard in v11.78
                // and Talks in v11.76; Settings was the last strip without
                // it (the 2026-11-06 strip audit's Slice B claimed all four
                // ticks shared the guard — true only for Main + Talks).
                // The null guard keeps a tick stopped mid-run from
                // resurrecting, Main's shape.
                try {
                    syncSettingsStrip();
                    refreshDownloadedButton53(); // v7.53: button counts follow the strip tick
                    refreshDataSaverButton54(); // v7.54: network note follows the strip tick
                    refreshBlockedChannelMark88(); // v11.88: ⚠ mark follows the strip tick
                } finally {
                    if (stripTick != null) stripHandler.postDelayed(this, 1000);
                }
            }
        };
        stripHandler.post(stripTick);
    }

    private void stopSettingsStripTick() {
        if (stripTick != null) {
            stripHandler.removeCallbacks(stripTick);
            stripTick = null;
        }
    }

    // v4.12: live sleep countdown (see fields above). The tick only
    // ever rewrites the existing sleep button's text: a running
    // timer reads "အိပ်ချိန်: 30 မိနစ် · −12:34", a set-but-idle
    // timer falls back to refresh()'s plain text (read fresh from
    // Prefs, so a choice tap made here is never overwritten with a
    // stale value). A 5s tick, not 1s — a countdown to the second
    // would rewrite the button five times as often for no benefit,
    // and the Room's own clock already ticks finely. No new view,
    // no new setting, and the Theme/Speed cards stay gone.
    private void startSleepWatch() {
        stopSleepWatch();
        try {
            sleepWatchConn = new android.content.ServiceConnection() {
                @Override public void onServiceConnected(android.content.ComponentName n,
                        android.os.IBinder b) {
                    try { sleepWatchService = ((AudioService.LocalBinder) b).getService(); }
                    catch (Exception ignored) { sleepWatchService = null; }
                    sleepWatchBound = true;
                    sleepWatchBindPending = false;
                    updateSleepCountdown();
                }
                @Override public void onServiceDisconnected(android.content.ComponentName n) {
                    sleepWatchService = null;
                    sleepWatchBound = false;
                    sleepWatchBindPending = false;
                }
            };
            sleepWatchBindPending = bindService(new Intent(this, AudioService.class), sleepWatchConn, 0);
        } catch (Exception ignored) {}
        sleepWatchTick = new Runnable() {
            @Override public void run() {
                if (sleepWatchTick == null) return;
                updateSleepCountdown();
                sleepWatchHandler.postDelayed(this, 5000);
            }
        };
        sleepWatchHandler.postDelayed(sleepWatchTick, 5000);
    }

    private void stopSleepWatch() {
        if (sleepWatchTick != null) {
            sleepWatchHandler.removeCallbacks(sleepWatchTick);
            sleepWatchTick = null;
        }
        // v11.72: bound OR pending -- unbinding a not-yet-connected
        // connection is legal and cancels its delivery, so a bind
        // still in flight at onPause can never complete onto this
        // dead activity and leak (see the field note above).
        if ((sleepWatchBound || sleepWatchBindPending) && sleepWatchConn != null) {
            try { unbindService(sleepWatchConn); } catch (Exception ignored) {}
        }
        sleepWatchBound = false;
        sleepWatchBindPending = false;
        sleepWatchService = null;
        sleepWatchConn = null;
    }

    private void updateSleepCountdown() {
        try {
            if (btnSleep == null) return;
            long susp = sleepWatchService != null ? sleepWatchService.getSleepSuspendedMs() : 0;
            long rem = sleepWatchService != null ? sleepWatchService.getSleepRemainingMs() : 0;
            int sl = Prefs.sleepMinutes(this);
            // v4.82: the one-shot has no countdown to show — the
            // button names it instead of the minute pref.
            if (sleepWatchService != null && sleepWatchService.isSleepEndOfTalk()) {
                btnSleep.setText(Lang.t(this, "sleep_end"));
            } else if (rem > 0 && sl > 0) {
                // v11.88: same clock as the strip (AudioService.sleepClock) — the manual
                // M:SS format read "60:00" at the instant a 60-minute timer was armed
                // while the strip read "1:00:00" for the same timer (H:MM:SS at ≥3600s).
                btnSleep.setText(sl + Lang.t(this, "sleep_min")
                        + " · −" + AudioService.sleepClock(rem));
            } else if (susp > 0 && sl > 0) {
                // v13.49 (F1): while the talk is paused the timer is suspended,
                // not running — getSleepRemainingMs() reads 0 and the button used
                // to drop to the plain setting while the strip on this same screen
                // and the Room kept showing the frozen remainder. Same guard as
                // the Room (susp > 0 && sl > 0) so a remainder orphaned by a
                // turned-off setting never shows as live; same clock shape, with
                // the strip's pause prefix marking it paused.
                btnSleep.setText(sl + Lang.t(this, "sleep_min")
                        + " · −⏸ " + AudioService.sleepClock(susp));
            } else {
                btnSleep.setText(sl == 0 ? Lang.t(this, "off") : sl + Lang.t(this, "sleep_min"));
            }
        } catch (Exception ignored) {}
    }

    // v1.94: system Back faded nowhere on this screen either — the
    // on-screen Back button has faded since v1.77, but the system
    // Back cut with no transition. Same soft fade as Talks/Room.
    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.nav_in, R.anim.nav_out);
    }

    // v1.91: push a Settings change to a talk already playing in
    // the background at once (speed, sleep, notification card) —
    // it used to wait for a Room/Library return. bindService with
    // flag 0 never starts the service: it fails at once when
    // nothing is playing, and only then is there nothing to sync.
    // v11.88: the connection is tracked in syncLiveConns88 (see the field note) and
    // swept in onDestroy — the old fire-and-forget shape unbound only inside
    // onServiceConnected, so a callback that never fired left the connection
    // registered with nothing able to unbind it.
    private void syncLiveService() {
        try {
            final android.content.ServiceConnection conn88 = new android.content.ServiceConnection() {
                @Override public void onServiceConnected(android.content.ComponentName n,
                        android.os.IBinder b) {
                    try {
                        AudioService s = ((AudioService.LocalBinder) b).getService();
                        if (s != null) { s.syncSettingsFromPrefs(); s.refreshNotification(); }
                    } catch (Exception ignored) {}
                    try { SettingsActivity.this.unbindService(this); }
                    catch (Exception ignored) {}
                    syncLiveConns88.remove(this);
                }
                @Override public void onServiceDisconnected(android.content.ComponentName n) {}
            };
            if (bindService(new Intent(this, AudioService.class), conn88, 0)) syncLiveConns88.add(conn88);
        } catch (Exception ignored) {}
    }

    // v11.88: no bind, no silent path — the live-track question is answered from the
    // service's own liveHasTrack mirror (v10.40), which is equivalent to hasTrack():
    // currentTitle is assigned only in play() alongside liveHasTrack = true and
    // cleared only in stop() alongside liveHasTrack = false (AudioService:869-870,
    // 3607-3609; onDestroy clears the mirror too, :4956). The old bind deferred the
    // clear to onServiceConnected with no timeout and no fallback, so a service death
    // in that window silently swallowed a confirmed clear — no clear, no toast, no
    // error, while every other outcome in this method toasts. Now the clear always
    // runs synchronously and always toasts, exactly like the bind-failed path did.
    private void clearHistoryWithLiveGuard() {
        doClearHistory(AudioService.liveHasTrack);
    }

    private void doClearHistory(boolean keepLiveAnchors) {
        android.content.SharedPreferences.Editor e =
                getSharedPreferences("dhamma", MODE_PRIVATE).edit().remove("history");
        // v8.33: per-talk resume spots clear in BOTH branches now.
        // The v1.91 live guard kept last* anchors (right: Mp3 Player
        // nav + notification tap still need them while a talk plays),
        // but talkPosMap sat inside the !keep block, so clearing
        // history while any talk played left EVERY half-heard talk's
        // spot behind — the dialog promises "recent listens and
        // resume history will be removed", yet the result depended
        // on whether something happened to be playing. The live talk
        // itself is safe: its resume rides the kept global lastPos /
        // lastPosUrl (every resume path — Room savedPosFor, library
        // savedPosForFile, service savedSeekFor — checks the global
        // spot first), and the service rewrites its per-talk entry
        // on the next 15s checkpoint / pause / seek while it still
        // plays, so clearing the map cannot strand the playing talk.
        // Only the other talks' spots go, which is what was asked.
        e.remove("talkPosMap");
        e.remove("forwardHistoryRaw").remove("hiddenTrail");
        // v14.04 (L7, sixth pass slice 3): the same class, stats side —
        // a Settings clear erased history / resume spots / trails but
        // left "listenStats66" behind, while the History screen's own
        // clear path (HistoryActivity.confirmClear) calls
        // Prefs.statsClear — so after a clear the total hours / day
        // streak / most-listened sayadaw / recent rows still counted
        // exactly the listens just erased, and statsRecent still
        // listed erased talks. One removal in this same editor, both
        // branches like the keys above: the live talk's stats ride
        // Prefs.statsAdd's read-modify-write (AudioService
        // flushListenStats re-reads the store on every 60-banked-
        // second flush and on pause/stop/end/destroy), so a mid-play
        // clear keeps only what the live talk banks after it — the
        // flush lands the banked seconds on the fresh store and
        // nothing pre-clear resurrects, same reasoning as talkPosMap
        // (v8.33). The literal mirrors Prefs.STATS_KEY; sim1472 pins
        // the two equal.
        e.remove("listenStats66");
        // v8.35: the same class, library side — libPrevTrail /
        // libTrailNext are the downloaded library's heard-history
        // stacks (AudioService.noteLibTrail11 calls them "the shelf
        // mirror of the Room's hiddenTrail"): every background
        // auto-advance appends the ended file and names the started
        // file, and DownloadedActivity's Previous walks that chain
        // while the sounding file still equals libTrailNext. Clear
        // history left both keys behind, so after a clear the
        // library's Previous still stepped through exactly the
        // listens just erased, and a later auto-advance stacked
        // fresh entries on the stale prefix (the v8.13 stacking
        // failure, now via a clear instead of a manual pick).
        // Both go in BOTH branches, like forwardHistoryRaw /
        // hiddenTrail: the live talk's resume still rides the kept
        // global anchors (lastLibPath etc. while playing, v8.33),
        // and a cleared trail only makes Previous fall back to
        // shelf order (consumeLibTrailPrev returns null on an
        // empty trail) — graceful, never a crash, and a fresh
        // advance after the clear starts a clean chain.
        // v11.21: the redo stack is history too — v8.35 cleared
        // libPrevTrail/libTrailNext but missed libRedoTrail (it
        // did not exist until v11.17), so a clear left the
        // back-step stack behind: a later fresh advance +
        // back-step stacked new entries on the stale prefix
        // (the v8.13 failure via a clear), and repeated Next
        // walked past the fresh redo entries into pre-clear
        // erased listens. The library reads the stack from
        // prefs on every use, so removing the key here fully
        // closes it, in both branches like the other two keys.
        e.remove("libPrevTrail").remove("libTrailNext").remove("libRedoTrail");
        // v8.36: bump the history-clear generation in BOTH branches,
        // so a Room sitting in the back stack can tell on return
        // that its in-memory stacks predate the clear (see
        // RoomActivity seenHistoryClearGen36): the persisted keys
        // above already clear, but a back-stack Room's prevHistory
        // (in-memory only, no persisted copy) and forwardHistory
        // (persisted copy cleared, in-memory copy untouched until
        // the next Next/Prev tap reconciles it) both survived a
        // clear until now, so Previous after a clear still stepped
        // through erased listens and the next-talk preview still
        // named an erased forward entry. A generation, not a
        // consume-once flag, so every back-stack Room sees it
        // independently and a fresh Room observes the current
        // generation as its baseline. The live talk's resume
        // still rides the kept global anchors, exactly as for the
        // keys above.
        long clearGen36 = getSharedPreferences("dhamma", MODE_PRIVATE)
                .getLong("historyClearGen", 0);
        e.putLong("historyClearGen", clearGen36 + 1);
        // v8.34: the same class, one layer further out — the persisted
        // play-history stacks survived a clear. forwardHistoryRaw is
        // the Room's redo (Next) stack, written on every mutation and
        // consulted by the background advance (v8.27) and adopted on
        // return by reconcileForwardAfterBackground27 (an empty copy
        // is a subsequence of any stack, so a cleared copy empties
        // a live Room's forward stack on its next return too);
        // hiddenTrail is the service's background-advance chain,
        // drained into prevHistory on return (v8.08). Clearing
        // history without them left Previous/Next stepping through
        // exactly the listens the user just erased, and a background
        // advance could still replay a cleared forward entry. Both
        // go in BOTH branches, like talkPosMap: the live talk's own
        // resume still rides the kept global anchors (v8.33), and
        // its in-memory stacks in an already-open Room die with
        // that Room — only the durable copies are cleared here.
        // Deliberately untouched: playList57/playIndex57 (the live
        // session's playlist for auto-advance, not history —
        // clearing it would break the playing session's next talk),
        // queue_* (a hand-built queue is not history), playedTalks
        // (its own clear button), dlMap/dlQueued (downloads stay,
        // per the dialog).
        if (!keepLiveAnchors) {
            e.remove("lastUrl").remove("lastTitle")
             .remove("lastName").remove("lastGroup")
             .remove("lastPage").remove("lastPhoto")
             .remove("lastPosUrl").remove("lastPos")
             // v1.93: the library-session anchors too, or "clear
             // everything" still left Mp3 Player nav opening the
             // library for a session the user just erased.
             .remove("lastKind").remove("lastLibPath").remove("lastLibTitle");
        }
        e.apply();
        android.widget.Toast.makeText(SettingsActivity.this,
                keepLiveAnchors
                        ? Lang.t(SettingsActivity.this, "toast_hist_live")
                        : Lang.t(SettingsActivity.this, "toast_hist"),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void setLabelText(int id, String key) {
        TextView v = findViewById(id);
        if (v != null) v.setText(Lang.t(this, key));
    }

    private void setBtnText(int id, String key) {
        // v12.94: TextView, not Button — the reset/team/policy/downloaded
        // rows are plain labels now (Button would throw ClassCastException).
        android.widget.TextView b = findViewById(id);
        if (b != null) b.setText(Lang.t(this, key));
    }

    // v4.13: live network note for the data-saver button (see
    // refresh()). ACCESS_NETWORK_STATE is already in the manifest
    // (v1.58); getActiveNetworkInfo is deprecated on newer APIs
    // but still returns the live type here, and any failure just
    // yields a blank note, never a wrong one.
    private String dataSaverNetworkNote() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
            if (ni == null || !ni.isConnected()) return "";
            if (ni.getType() == android.net.ConnectivityManager.TYPE_WIFI) return " · Wi-Fi";
            if (ni.getType() == android.net.ConnectivityManager.TYPE_VPN) {
                // v10.80: last VPN-blind Wi-Fi site (v10.78/79 fixed the
                // three gating copies only). A VPN active network hides
                // the underlying Wi-Fi from this legacy-type read, so
                // the note went blank on VPN-on-Wi-Fi while the gates
                // correctly treated it as Wi-Fi. Same rule as v10.79:
                // only a CONNECTED TYPE_WIFI network counts, so a merely
                // tracked Wi-Fi object cannot fake the note; no connected
                // Wi-Fi falls through to the mobile suffix (v12.09),
                // never a wrong claim.
                try {
                    for (android.net.Network n : cm.getAllNetworks()) {
                        android.net.NetworkInfo ni2 = cm.getNetworkInfo(n);
                        if (ni2 != null && ni2.isConnected()
                                && ni2.getType() == android.net.ConnectivityManager.TYPE_WIFI)
                            return " · Wi-Fi";
                    }
                } catch (Exception ignored) {}
                // v12.09: a VPN with no connected Wi-Fi underneath is gated as mobile, so
                // the note says mobile instead of going blank (same for the fallthrough
                // below); only a missing/disconnected network still reads blank.
                return Lang.t(SettingsActivity.this, "net_mobile_suffix");
            }
            if (ni.getType() == android.net.ConnectivityManager.TYPE_MOBILE) return Lang.t(SettingsActivity.this, "net_mobile_suffix");
        } catch (Exception ignored) {}
        // v12.09: any other connected transport (Ethernet, ...) is gated as mobile too.
        return Lang.t(SettingsActivity.this, "net_mobile_suffix");
    }

    // v7.53: the downloaded-library button's live counts, extracted
    // verbatim from refresh()'s v4.24 file count ("... (N)") and v4.27
    // in-progress count ("... \u23f3 N") so the 1s strip tick can
    // refresh it too: refresh() runs only on create/return/choice
    // taps, while a bulk download started elsewhere can land while
    // Settings sits foreground (the shade never pauses it, so no
    // return-refresh fires) -- the strip's own \u23f3 suffix already
    // followed within a second (v5.68) while the button below kept
    // the old "(N) \u00b7 \u23f3 M" until the next return, the same
    // split the v7.50 library footer and v7.51 chooser header fixed
    // on their screens. The label is rebuilt from the base Lang
    // text every call, so suffixes never stack, and the text is
    // only written when it actually changed, so steady ticks never
    // touch the button (and never disturb the sleep countdown text
    // refresh() would clobber -- that is why the whole refresh()
    // is NOT put on the tick). Same app-scoped rules as before:
    // .mp3 files in Downloads/Dhamma, in-progress = Dhamma/
    // destination or a dlMap source URL; other apps never count.
    private void refreshDownloadedButton53() {
        try {
            android.widget.TextView dlBtn = findViewById(R.id.btnDownloaded);
            if (dlBtn == null) return;
            String label = Lang.t(this, "downloaded");
            // v11.88: the in-progress pass runs FIRST now, so the (N) count below can
            // exclude still-downloading partials — DownloadManager writes straight into
            // Downloads/Dhamma under the final filename (Fx.stripResumeDownloadMark
            // v11.58), so a partial file used to count in BOTH "(N)" and "· ⏳ M".
            // Same single DM query as before (no extra scan): this pass also collects
            // the in-progress filenames, with Fx.stripInProgressDlFilenames' exact
            // derivation (decoded Dhamma/ basename, else the dlMap filename for the
            // source URL). The progN rule itself is unchanged.
            java.util.Set<String> inProgNames88 = new java.util.HashSet<String>();
            int progN88 = 0;
            try {
                java.util.Map<String, String> dlNames88 = new java.util.HashMap<String, String>();
                try {
                    org.json.JSONObject map = new org.json.JSONObject(
                            getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlMap", "{}"));
                    java.util.Iterator<String> keys = map.keys();
                    while (keys.hasNext()) { String u = keys.next(); dlNames88.put(u, map.optString(u, "")); }
                } catch (Exception ignored) {}
                android.app.DownloadManager dm = (android.app.DownloadManager)
                        getSystemService(android.content.Context.DOWNLOAD_SERVICE);
                // v12.84: filter in the provider — the v12.70 strip-scan shape; this
                // button pass runs on the 1s strip tick and used to pull every download
                // on the device; the status check below stays as a guard.
                android.database.Cursor c = dm.query(new android.app.DownloadManager.Query()
                        .setFilterByStatus(android.app.DownloadManager.STATUS_RUNNING
                                | android.app.DownloadManager.STATUS_PAUSED
                                | android.app.DownloadManager.STATUS_PENDING));
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
                                    || (src != null && dlNames88.containsKey(src))) {
                                progN88++;
                                boolean added88 = false;
                                if (local != null) {
                                    String dec = android.net.Uri.decode(local);
                                    if (dec != null && dec.contains("Dhamma/")) {
                                        int slash = dec.lastIndexOf('/');
                                        if (slash >= 0 && slash + 1 < dec.length()) {
                                            inProgNames88.add(dec.substring(slash + 1));
                                            added88 = true;
                                        }
                                    }
                                }
                                if (!added88 && src != null && dlNames88.containsKey(src)) {
                                    String fn88 = dlNames88.get(src);
                                    if (fn88 != null && fn88.length() > 0) inProgNames88.add(fn88);
                                }
                            }
                        }
                    } finally { c.close(); }
                }
            } catch (Exception ignored) {}
            try {
                java.io.File dlDir = new java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma");
                if (dlDir.isDirectory()) {
                    java.io.File[] dlFiles = dlDir.listFiles();
                    int n = 0;
                    if (dlFiles != null) {
                        for (java.io.File df : dlFiles)
                            // v13.49 (L3 rider): a 0-byte file never plays — v13.48 F3 class; it is not a downloaded talk
                            if (df.isFile() && df.length() > 0 && df.getName().toLowerCase().endsWith(".mp3")
                                    && !inProgNames88.contains(df.getName())) n++;
                    }
                    if (n > 0) label += " (" + n + ")";
                }
            } catch (Exception ignored) {}
            if (progN88 > 0) label += " \u00b7 \u23f3 " + progN88;
            CharSequence cur = dlBtn.getText();
            if (cur == null || !label.equals(cur.toString())) dlBtn.setText(label);
        } catch (Exception ignored) {}
    }

    // v12.68: the auto-download status note — "not yet checked"
    // before the first run, else "last check <date time> · N new
    // talks" (Latin digits, like the app's timers). A failed or
    // Wi-Fi-less run never updates the stored stamp, so the note
    // always names a real completed check.
    private String autoDlStatusLine() {
        try {
            long last = Prefs.autoDlLastRun(this);
            if (last <= 0) return Lang.t(this, "ad_never");
            String when = new java.text.SimpleDateFormat("dd/MM HH:mm",
                    java.util.Locale.US).format(new java.util.Date(last));
            return Lang.t(this, "ad_last") + " " + when + " · "
                    + Prefs.autoDlLastNew(this) + " " + Lang.t(this, "ad_new");
        } catch (Exception ignored) { return Lang.t(this, "ad_never"); }
    }

    // v7.54: the data-saver button's live network note, extracted
    // verbatim from refresh()'s v4.13 line (base Lang label + on/off
    // + dataSaverNetworkNote()) so the 1s strip tick can refresh it
    // too: refresh() runs only on create/return/choice taps, while a
    // Wi-Fi/mobile switch (or a loss/regain of connection) while
    // Settings sits foreground left the button's note stale --
    // "· Wi-Fi" after moving to mobile data, or a note lingering
    // after going offline -- until the next return, the same split
    // the v7.53 Downloaded button fixed on this same screen. The
    // label is rebuilt from the base Lang text every call, so the
    // note never stacks, and the text is only written when it
    // actually changed, so steady ticks never touch the button
    // (and never disturb the sleep countdown text, which is why
    // the whole refresh() is NOT put on the tick -- only this one
    // button, like v7.53). The network lookup itself is unchanged
    // (dataSaverNetworkNote's existing call, failure = blank note).
    private void refreshDataSaverButton54() {
        // v12.94: the switch holds the label + checked state (set in
        // refresh()); this method owns only the live network note,
        // now a subtext line under the switch ("· Wi-Fi" / "· မိုဘိုင်း",
        // hidden when unknown) — the v7.54 change-only write, same as before.
        // v13.35: the label + checked writes were NOT change-only —
        // setText/setChecked ran on every 1s tick even when nothing
        // changed. Guard both against the live view state, the same
        // pattern as the note below it.
        try {
            if (btnDataSaver == null) return;
            String dsLabel = Lang.t(this, "data_saver");
            CharSequence dsCur = btnDataSaver.getText();
            if (dsCur == null || !dsLabel.equals(dsCur.toString())) btnDataSaver.setText(dsLabel);
            boolean dsOn = Prefs.dataSaver(this);
            if (btnDataSaver.isChecked() != dsOn) btnDataSaver.setChecked(dsOn);
            String note = dataSaverNetworkNote();
            android.widget.TextView dn = findViewById(R.id.dataSaverNote);
            if (dn != null) {
                CharSequence cur = dn.getText();
                if (cur == null || !note.equals(cur.toString())) dn.setText(note);
                dn.setVisibility(note.length() == 0 ? android.view.View.GONE : android.view.View.VISIBLE);
            }
        } catch (Exception ignored) {}
    }

    // v11.88: blocked-channel mark follows the 1s tick — resolveChannelId() migrates the
    // stored channel id when a strip tap posts to a NONE-blocked channel while Settings
    // is foreground, so the ⚠ mark + note drawn by refresh() described an abandoned
    // channel until the next refresh() (onResume or a choice tap). Re-read on the tick
    // and rewrite only the noti button + note when the value changes, mirroring the
    // change-only write pattern of refreshDownloadedButton53(); the sleep countdown
    // button is untouched, and refresh() keeps the cache in step (see its tail).
    // v12.27: the tick reads the combined notiBlockedState() (app-level "APP" wins
    // over a channel id), so a flip of the app-level switch in system Settings while
    // this screen is foreground heals/shows the mark at once too — the same
    // change-only cache covers both, and the note key follows the state.
    private void refreshBlockedChannelMark88() {
        try {
            String blocked = notiBlockedState();
            if (blockedChanKnown88
                    && (blocked == null ? lastBlockedChan88 == null : blocked.equals(lastBlockedChan88))) return;
            lastBlockedChan88 = blocked;
            blockedChanKnown88 = true;
            if (btnNoti != null) { btnNoti.setText(Lang.t(this, "noti") + (blocked != null ? " ⚠" : "")); btnNoti.setChecked(Prefs.notiEnabled(this)); }
            android.widget.TextView nn = findViewById(R.id.notiNote);
            if (nn != null) {
                // v12.94: the note exists only while a block is live.
                if (blocked == null) nn.setVisibility(android.view.View.GONE);
                else { nn.setVisibility(android.view.View.VISIBLE); nn.setText(Lang.t(this, "APP".equals(blocked) ? "noti_app_blocked" : "noti_channel_blocked")); }
            }
        } catch (Exception ignored) {}
    }

    // v10.86: the live channel the service actually posts to, when
    // it is blocked — or null. Reads the same "dhamma" prefs key
    // the service's resolveChannelId() migrates (default
    // "playback_media", the service's CHANNEL_ID) and asks the
    // system for that channel's live importance, so this is never
    // a stale flag: a channel unblocked in system Settings reads
    // healthy on the next refresh, and a channel the service just
    // migrated away from is no longer the stored id. A NONE
    // channel is one silent-drop class the v10.74–v10.77
    // service fixes cannot surface on their own — posting to it
    // succeeds and throws nothing, while the app-level Allow stays
    // ON, so without this check the listener has no screen anywhere
    // that says why no card ever appears. The other class is the
    // app-level switch itself (appNotiBlocked() below, v12.27).
    // IMPORTANCE_NONE only: a LOW/MIN channel still shows in
    // the shade, so it is not a "no notification at all" case
    // and earns no warning.
    private String blockedChannelId() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 26) return null;
            String id = getSharedPreferences("dhamma", MODE_PRIVATE).getString("notiChannelId", "playback_media");
            if (id == null || id.length() == 0) return null;
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm == null) return null;
            android.app.NotificationChannel ch = nm.getNotificationChannel(id);
            if (ch != null && ch.getImportance() == android.app.NotificationManager.IMPORTANCE_NONE) return id;
            return null;
        } catch (Exception ignored) { return null; }
    }

    // v12.27: the app-level switch — the second silent-drop class
    // blockedChannelId() above cannot see. areNotificationsEnabled()
    // is false when the listener turned the app's notifications off
    // in system Settings (or denied POST_NOTIFICATIONS on API 33+);
    // posting to any channel then drops silently, exactly like a
    // NONE channel, while the channel itself stays healthy — so
    // the v10.86 mark + note stayed silent for it and nothing on
    // any screen said why no card ever appears. SDK>=26 gate
    // mirrors blockedChannelId(); the in-app toggle is irrelevant
    // here, since a blocked app-level switch silences even the
    // button-less plain card the toggle-off path posts.
    private boolean appNotiBlocked() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 26) return false;
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            return !nm.areNotificationsEnabled();
        } catch (Exception ignored) { return false; }
    }

    // v12.27: one combined blocked state for the ⚠ mark, the note
    // and the long-press target, so the three can never disagree:
    // "APP" when the app-level switch is off (fix at the app page
    // first — the channel page cannot help while the app is off),
    // otherwise the blocked channel id, otherwise null. "APP" can
    // never collide with a channel id (the ids are playback_media
    // / playback_media2, fixed in AudioService), so the v11.88
    // change-only cache can hold this string as-is.
    private String notiBlockedState() {
        try {
            if (appNotiBlocked()) return "APP";
            return blockedChannelId();
        } catch (Exception ignored) { return null; }
    }

    private void refresh() {
        // v10.50: keep-screen-on was honoured on the Room, Talks, library
        // and (v10.49) Main windows but never on Settings itself — the
        // fifth screen with a mini-player strip. Toggling it ON here
        // flipped only the label while this window stayed dimmable
        // mid-listen, and a fresh Settings open never set the flag at
        // all. One live read here covers all three paths because
        // refresh() runs on fresh open, on every onResume and inside
        // the toggle's dip runnable, mirroring the Main v10.49
        // onResume block; the else-clear drops a flag a previous
        // visit set when the setting is now off.
        if (Prefs.keepScreenOn(this))
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        boolean night = Prefs.isNight(this);
        Fx.applySystemBars(this, night); // v13.07: bars follow the theme
        int ink = night ? Color.WHITE : Color.parseColor("#3B2314");
        int muted = night ? Color.parseColor("#AAAAAA") : Color.parseColor("#7A6A58");
        root.setBackgroundResource(night ? R.drawable.bg_gradient_night : R.drawable.bg_gradient_day);
        // v1.58: the inner content layout has its own parchment
        // background, which used to cover the ScrollView's night
        // background entirely — night mode left white labels on a
        // light background, i.e. invisible. Colour the content too.
        View content = findViewById(R.id.content);
        if (content != null)
            content.setBackgroundResource(night ? R.drawable.bg_gradient_night : R.drawable.bg_gradient_day);
        // v12.94: no cards remain — rows sit on the screen background.
        // v14.14 (L5, sixth pass slice 15): the titleText field
        // was the only bare deref left in refresh() — every
        // other view here is null-guarded, and R.id.titleText
        // exists in the layout so this is unreachable today,
        // the same class as the Talks L1 setSelection guard
        // (v14.09). Guard all three sites; a null titleText
        // now skips the title writes and the rest of refresh()
        // still runs.
        if (titleText != null) titleText.setTextColor(night ? Color.parseColor("#C9A227") : Color.parseColor("#7A2E1F"));
        // v1.96: this screen never followed the font-size setting it
        // exists to change — its title, labels, notes and buttons
        // stayed at fixed layout sizes, so a large-text listener set
        // ကြီး elsewhere and still read small text here. Scale every
        // existing text element with the same steps as the other
        // screens (labels 14/16/20, notes 12/13/16, buttons 14/16/18,
        // title 22/26/32); refresh() runs on create, on return and on
        // every choice tap, so picking ကြီး resizes this screen at once.
        int fs = Prefs.fontSize(this);
        float lbl = fs == 0 ? 14 : (fs == 1 ? 16 : 20);
        float note = fs == 0 ? 12 : (fs == 1 ? 13 : 16);
        float btn = fs == 0 ? 14 : (fs == 1 ? 16 : 18);
        if (titleText != null) titleText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fs == 0 ? 22 : (fs == 1 ? 26 : 32));
        int[] labelIds = {R.id.labelLanguage, R.id.labelFont, R.id.lblRepeat, R.id.lblSleep, R.id.lblClearData, R.id.lblVersion, R.id.btnDownloaded, R.id.btnTeam, R.id.btnPolicy};
        for (int id : labelIds) { TextView lv = findViewById(id); if (lv != null) { lv.setTextColor(ink); lv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, lbl); } }
        // v12.94: section headers — small muted caps, not card titles.
        for (int id : new int[]{R.id.secAppearance, R.id.secPlayback, R.id.secNoti, R.id.secData, R.id.secAbout}) { TextView hv = findViewById(id); if (hv != null) { hv.setTextColor(muted); hv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, note); } }
        for (int id : new int[]{R.id.notiNote, R.id.autoDlNote, R.id.dataSaverNote, R.id.aboutSrc}) { TextView nv = findViewById(id); if (nv != null) { nv.setTextColor(muted); nv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, note); } }
        // v13.35: no second Prefs.fontSize read here — fs above is reused below (was int f).
        // v10.86: one live blocked-channel read for the whole
        // refresh — the note override below reuses it, so the
        // label mark and the note can never disagree mid-refresh.
        // v12.27: the combined notiBlockedState() ("APP" for the
        // app-level switch, else the blocked channel id), so the
        // mark + note + long-press target all name the same block.
        String blockedChan = notiBlockedState();
        // v11.88: keep the tick's change-only cache in step, so the 1s tick never
        // rewrites what this refresh just drew.
        lastBlockedChan88 = blockedChan;
        blockedChanKnown88 = true;
        // v12.94: switches show the label only — the thumb IS the on/off
        // state now (no more "label: ဖွင့်/ပိတ်" text). Value rows show
        // the value only; their labels live in lblRepeat/lblSleep below.
        if (btnNoti != null) { btnNoti.setText(Lang.t(this, "noti") + (blockedChan != null ? " ⚠" : "")); btnNoti.setChecked(Prefs.notiEnabled(this)); }
        if (btnAutoplay != null) { btnAutoplay.setText(Lang.t(this, "autoplay")); btnAutoplay.setChecked(Prefs.autoplay(this)); }
        int rm = Prefs.repeatMode(this);
        if (btnRepeat != null) btnRepeat.setText(rm == 0 ? Lang.t(this, "off") : rm == 1 ? Lang.t(this, "repeat_all") : Lang.t(this, "repeat_one"));
        if (btnShuffle != null) { btnShuffle.setText(Lang.t(this, "shuffle")); btnShuffle.setChecked(Prefs.shuffleOn(this)); }
        if (btnBackground != null) { btnBackground.setText(Lang.t(this, "background")); btnBackground.setChecked(Prefs.backgroundEnabled(this)); }
        if (btnKeepScreen != null) { btnKeepScreen.setText(Lang.t(this, "keep_screen")); btnKeepScreen.setChecked(Prefs.keepScreenOn(this)); }
        if (btnThemeAuto != null) { btnThemeAuto.setText(Lang.t(this, "auto_theme")); btnThemeAuto.setChecked(Prefs.autoTheme(this)); }
        int sl = Prefs.sleepMinutes(this);
        if (btnSleep != null) btnSleep.setText(sl == 0 ? Lang.t(this, "off") : sl + Lang.t(this, "sleep_min"));
        if (btnFx != null) { btnFx.setText(Lang.t(this, "fx")); btnFx.setChecked(Prefs.fxEnabled(this)); }
        refreshDataSaverButton54(); // v7.54: extracted verbatim, see below
        // v12.68: Wi-Fi auto-download toggle + status line — the
        // data-saver shape (label + on/off on the button, live note
        // under it): the note says when the last background check
        // ran and how many new talks it downloaded, or that no
        // check has run yet.
        if (btnAutoDl != null) { btnAutoDl.setText(Lang.t(this, "ad_auto_dl")); btnAutoDl.setChecked(Prefs.autoDlEnabled(this)); }
        android.widget.TextView adn = findViewById(R.id.autoDlNote);
        if (adn != null) adn.setText(autoDlStatusLine());
        // v4.13: live network note on the existing data-saver
        // button (Spotify-style context, no new view): the gate
        // only matters on mobile data, so the button now says
        // which network the phone is actually on right now
        // ("· Wi-Fi" / "· မိုဘိုင်း", blank when unknown).
        // refresh() runs on every onResume, so the note follows
        // a Wi-Fi/mobile switch without reopening Settings; a
        // lookup failure never changes the on/off value itself.
        // v1.96 (cont.): every choice/action button on this screen,
        // including the ones only wired in onCreate, at the same step.
        Button[] allBtns = {btnNoti, btnAutoplay, btnShuffle, btnBackground, btnKeepScreen, btnThemeAuto, btnFx, btnDataSaver, btnAutoDl};
        // v12.94: switches need their label colour here — the layout's
        // fixed @color/ink would stay dark brown at night.
        for (Button b : allBtns) if (b != null) { b.setTextColor(ink); b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, btn); }
        // v12.94: no raised buttons remain — the reset row is plain red
        // text; value texts read muted at the button step, like the
        // font/language values above.
        android.widget.TextView rst = findViewById(R.id.btnResetAll);
        if (rst != null) { rst.setTextColor(night ? Color.parseColor("#EF9A9A") : Color.parseColor("#C0392B")); rst.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, btn); }
        for (int id : new int[]{R.id.btnRepeat, R.id.btnSleep, R.id.versionValue}) { TextView vv = findViewById(id); if (vv != null) { vv.setTextColor(muted); vv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, btn); } }
        // v3.76: every Settings text now reads from Lang — title, section
        // labels, font/language values, switch labels, value rows, action
        // rows and the version row (live versionName, so it can never lag
        // behind the build again). refresh() runs on create, on return and
        // inside every choice dip, so a language tap re-reads the whole
        // screen at the dip. Talk/sayadaw names are content, not chrome,
        // and are deliberately untouched by Lang.
        if (titleText != null) titleText.setText(Lang.t(this, "settings"));
        setLabelText(R.id.labelLanguage, "lang_label");
        setLabelText(R.id.labelFont, "font_label");
        setLabelText(R.id.lblRepeat, "repeat");
        setLabelText(R.id.lblSleep, "sleep");
        // v13.18: the five section headers + version/clear-data
        // labels were the last hardcoded-Burmese chrome on this
        // screen — in every other UI language they stayed Burmese
        // while the rest of the screen followed the language tap.
        // They now read from Lang like the other labels (refresh
        // runs on create, on return and inside every choice dip).
        setLabelText(R.id.secAppearance, "sec_appearance");
        setLabelText(R.id.secPlayback, "sec_playback");
        setLabelText(R.id.secNoti, "sec_noti");
        setLabelText(R.id.secData, "sec_data");
        setLabelText(R.id.secAbout, "sec_about");
        setLabelText(R.id.lblClearData, "clear_data");
        setLabelText(R.id.lblVersion, "version");
        if (fontValue != null) { fontValue.setText(fs == 0 ? Lang.t(this, "font_small") : fs == 1 ? Lang.t(this, "font_normal") : Lang.t(this, "font_large")); fontValue.setTextColor(muted); fontValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, btn); }
        if (langValue != null) { langValue.setText(Lang.NAMES[Lang.index(this)]); langValue.setTextColor(muted); langValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, btn); }
        // v11.93: the two "›" chevrons had no id, so refresh() never
        // recoloured them — they kept day muted-brown at night and
        // nearly vanished on the dark card. Same night-grey as the
        // values beside them.
        for (int id : new int[]{R.id.fontChevron, R.id.langChevron, R.id.repeatChevron, R.id.sleepChevron, R.id.downloadedChevron, R.id.clearChevron, R.id.teamChevron, R.id.policyChevron}) { TextView cv = findViewById(id); if (cv != null) cv.setTextColor(muted); }
        // v12.94: the long Android-rules paragraph is gone — the note
        // under the notification row appears ONLY while a block is live
        // (v10.86/v12.27), naming the fix; healthy phones see no note
        // at all, like Spotify. The ⚠ mark on the switch stays either way.
        TextView nn0 = findViewById(R.id.notiNote);
        if (nn0 != null) {
            if (blockedChan != null) { nn0.setVisibility(android.view.View.VISIBLE); nn0.setText(Lang.t(this, "APP".equals(blockedChan) ? "noti_app_blocked" : "noti_channel_blocked")); }
            else nn0.setVisibility(android.view.View.GONE);
        }
        setBtnText(R.id.btnDownloaded, "downloaded");
        refreshDownloadedButton53();
        setBtnText(R.id.btnResetAll, "reset");
        setLabelText(R.id.aboutSrc, "footer_src");
        setBtnText(R.id.btnTeam, "team_btn");
        setBtnText(R.id.btnPolicy, "policy_btn");
        TextView vv2 = findViewById(R.id.versionValue);
        if (vv2 != null) {
            String vn = "";
            try { vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception ignored) {}
            vv2.setText("v" + vn);
        }
        // v3.71: the return-dip cache lives here, not in onCreate
        // alone — refresh() runs on create, on return, on every
        // choice tap and inside every dip swap, so syncing here
        // means the cache always names the theme/font this screen
        // last actually drew. A tap made in Settings itself therefore
        // never looks like a "change since last draw" on the next
        // return; only a change landing while Settings sits in the
        // back stack does. `night`/`fs` are this call's own drawn
        // values, so no extra Prefs read is needed.
        cachedStNight = night;
        cachedStFont = fs;
    }
}
