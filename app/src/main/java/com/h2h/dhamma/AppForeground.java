package com.h2h.dhamma;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

// v1.81: is any of our screens actually on top right now? The
// background-playback-off guard used to live only in the Room and the
// downloaded library, so leaving the app from the main list, the talk
// chooser or Settings kept playing even with the setting off — those
// screens never held the service, so they had nothing to pause with.
// Every activity reports its own resume/pause here; a pause schedules
// one delayed check, and the check only fires when NO screen came back
// on top in between (a navigation or a rotation re-resumes within the
// delay, so in-app moves never pause anything). The service itself
// decides the pause (see ACTION_AUTO_PAUSE), so a screen that never
// bound to it can still stop playback.
public class AppForeground {

    private static int resumedCount = 0;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable pendingCheck;

    public static synchronized void activityResumed() {
        resumedCount++;
    }

    public static synchronized void activityPaused() {
        if (resumedCount > 0) resumedCount--;
    }

    public static synchronized boolean isForeground() {
        return resumedCount > 0;
    }

    // v1.81: one delayed background check per pause — re-scheduling
    // replaces the previous one, so a flurry of pauses (rotation,
    // fast navigation) never stacks checks. 600ms bridges every
    // in-app transition; only a real leave (Home, recents, another
    // app) still shows zero resumed screens when it fires.
    public static synchronized void scheduleBackgroundCheck(Context ctx) {
        scheduleBackgroundCheck(ctx, 600);
    }

    // v1.90: same check with a caller-chosen delay — the Room's share
    // chooser needs longer than 600ms (a slow share pick must not
    // look like leaving), while still bounding the leave-through-
    // the-chooser leak that skipping the check entirely caused.
    public static synchronized void scheduleBackgroundCheck(Context ctx, long delayMs) {
        // v12.06: a rotation destroys and recreates the activity, so
        // onPause fires with isChangingConfigurations() true — and
        // the 600ms check could then fire in the relaunch gap and
        // pause a talk the user never left (the Room/Downloaded
        // leave-guards already skip the same case). Skip scheduling
        // entirely: the recreated activity resumes and re-arms the
        // count, and a real leave still schedules from its own
        // onPause. Return BEFORE the replace below, so a pending
        // check from an earlier pause (e.g. a share chooser open
        // across the rotation) survives instead of being dropped.
        if (ctx instanceof android.app.Activity
                && ((android.app.Activity) ctx).isChangingConfigurations()) return;
        final Context app = ctx.getApplicationContext();
        if (pendingCheck != null) handler.removeCallbacks(pendingCheck);
        pendingCheck = new Runnable() {
            @Override public void run() {
                if (isForeground()) return;
                try {
                    Intent i = new Intent(app, AudioService.class);
                    i.setAction(AudioService.ACTION_AUTO_PAUSE);
                    app.startService(i);
                } catch (Exception ignored) {}
            }
        };
        handler.postDelayed(pendingCheck, delayMs);
    }
}
