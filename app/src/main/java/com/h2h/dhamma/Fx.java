package com.h2h.dhamma;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

// v1.52: animation/transition/sound/vibration design — one small helper
// so every screen feels the same. All of it is gated by the Settings
// "အသံနှင့် တုန်ခါမှု" toggle (Prefs.fxEnabled, default on).
public class Fx {
    private static SoundPool pool;
    private static int tapId, bellId;
    // v1.53: SoundPool.load() is asynchronous — the old code set
    // loaded=true right after calling load(), so the first tap after
    // app start played into a sample that was not ready yet and was
    // silently dropped. Gate on real load completion instead.
    private static boolean loaded;
    private static int loadsDone;

    private static void ensure(Context c) {
        if (pool != null) return;
        try {
            AudioAttributes at = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(at).build();
            pool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                @Override public void onLoadComplete(SoundPool sp, int sampleId, int status) {
                    // count even a failed sample: playing it is a
                    // no-op anyway, and one bad file must not mute
                    // the other sound forever.
                    if (++loadsDone >= 2) loaded = true;
                }
            });
            tapId = pool.load(c.getAssets().openFd("fx/fx_tap.wav"), 1);
            bellId = pool.load(c.getAssets().openFd("fx/fx_bell.wav"), 1);
        } catch (Exception ignored) {
            // v1.57: a failed first init used to leave a half-built pool
            // behind, so ensure() returned early forever and every later
            // tap stayed silent — drop it so the next tap retries.
            // v1.60: reset the load counter too — a half-finished first
            // attempt left loadsDone part-counted, so the retry's
            // completion count no longer meant "both samples ready".
            // v11.74: release the half-built pool before dropping it —
            // a load() throw (missing/corrupt fx/*.wav) used to abandon
            // the built SoundPool with no release(), leaking one native
            // pool per tap until the asset problem was fixed. The
            // release also drops the stale load listener, so no late
            // callback can count toward the retry's loadsDone.
            if (pool != null) {
                try { pool.release(); } catch (Exception ignored2) {}
            }
            pool = null;
            loaded = false;
            loadsDone = 0;
        }
    }

    // soft tap blip for any button / row tap
    public static void tap(Context c) {
        if (!Prefs.fxEnabled(c)) return;
        ensure(c);
        if (loaded) pool.play(tapId, 0.5f, 0.5f, 1, 0, 1.0f);
        vibrate(c, 12);
    }

    // gentle temple bell when a new talk starts in the Room
    public static void bell(Context c) {
        if (!Prefs.fxEnabled(c)) return;
        ensure(c);
        if (loaded) pool.play(bellId, 0.6f, 0.6f, 1, 0, 1.0f);
        vibrate(c, 25);
    }

    // v4.17: FX preview — plays the tap blip + temple bell even
    // when the FX toggle is off, so the Settings long-press can
    // demonstrate what the toggle controls instead of previewing
    // silence. Sounds only (no pref change); the bell follows
    // 400ms later so the two samples never clash, and the blip's
    // vibration plays raw for the same reason tap()/bell() would
    // otherwise swallow it while FX is off.
    public static void preview(final Context c) {
        ensure(c);
        if (!loaded) return;
        try { pool.play(tapId, 0.5f, 0.5f, 1, 0, 1.0f); } catch (Exception ignored) {}
        try {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    try { if (loaded) pool.play(bellId, 0.6f, 0.6f, 1, 0, 1.0f); } catch (Exception ignored) {}
                }
            }, 400);
        } catch (Exception ignored) {}
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(12);
            }
        } catch (Exception ignored) {}
    }

    public static void vibrate(Context c, long ms) {
        if (!Prefs.fxEnabled(c)) return;
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26)
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else
                v.vibrate(ms);
        } catch (Exception ignored) {}
    }

    // v2.07: premium navigation — fade + a slight scale-in
    // instead of the flat system fade. Every screen change in the
    // app goes through go()/back(), so this one change covers all
    // of them; the four onBackPressed handlers use the same pair.
    public static void go(Activity a, Intent i) {
        a.startActivity(i);
        a.overridePendingTransition(R.anim.nav_in, R.anim.nav_out);
    }

    // finish with the same pair (used for Back from Talks / Room)
    public static void back(Activity a) {
        a.finish();
        a.overridePendingTransition(R.anim.nav_in, R.anim.nav_out);
    }

    // v2.09: premium morph — the same squash + overshoot the Room
    // play button has used since v2.07, now shared so the Next
    // button morphs on every advance instead of only squashing
    // under the finger (v2.08 press). Visual only.
    // v3.35: premium morph-restart continuity — the squash used to
    // snap scale straight to 0.86 (setScaleX/Y), so a rapid re-tap
    // mid-spring jumped sideways in one frame, the same snap class
    // v3.34 fixed for the nudges. The dip now animates from the
    // current scale to 0.86 over 70ms (decelerate) and only then
    // springs back over 220ms with the old overshoot, so an idle
    // tap reads exactly as before (1 -> 0.86 -> 1) and a mid-flight
    // re-tap flows current -> 0.86 -> 1 with no jump. A generation
    // map per view (WeakHashMap, so no leak) guards the dip's end
    // action the v3.19 way: the gen is bumped BEFORE the cancel,
    // and a stale end action returns without starting a rise.
    // Visual only; the squash depth and spring-back are untouched.
    private static java.util.WeakHashMap<View, Integer> morphGens =
            new java.util.WeakHashMap<View, Integer>();
    public static void morph(final View v) {
        Integer cur = morphGens.get(v);
        final int gen = (cur == null ? 1 : cur + 1);
        morphGens.put(v, gen);
        cancelPress(v); // v3.90: the press squash is an ObjectAnimator now — the ViewPropertyAnimator cancel below cannot stop it, so a click landing mid-spring would otherwise fight the morph for scale.
        v.animate().cancel();
        v.animate().setListener(null);
        if (v.getScaleX() <= 0.86f) {
            v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(new OvershootInterpolator(1.4f)).start();
            return;
        }
        v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(70)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) {
                        Integer now = morphGens.get(v);
                        if (now == null || now != gen) return;
                        v.animate().setListener(null);
                        v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                                .setInterpolator(new OvershootInterpolator(1.4f)).start();
                    }
                }).start();
    }

    // v2.09: premium sheet motion — the Room talk list used to
    // appear/disappear instantly on the စာရင်း tap. It now slides
    // up + fades in on open, and slides down + fades out on close
    // (GONE only when the close animation ends, so every existing
    // visibility check still reads the same states). A rapid
    // re-tap cancels the running animation and starts the new one.
    // v3.70: premium sheet-close end-action guard — the v3.62
    // fadeOut guard + v3.63 themeDip guard + v3.64 retry-hide
    // guard + v3.65 wave stop-ease guard + v3.66 glow guard +
    // v3.67 drawer-close guard + v3.68 glow fade-out guard +
    // v3.69 portrait-fade guard applied to the last unguarded
    // end action left in the codebase (v3.69 named it: this
    // helper's close end). Under the v3.19 behaviour a close
    // cancelled by a reopen still fires its end action, and
    // that end used to hide the sheet unconditionally (GONE +
    // translationY 0 + alpha 1) — the same hole the drawer
    // close (v3.67) had on its own path, and the v2.48
    // setListener(null) only clears a listener that has not
    // already fired, so it cannot catch a late landing after
    // the show animation started. A generation map per sheet
    // view (same WeakHashMap model as morphGens/fadeGens)
    // closes it: every sheet() call bumps BEFORE the cancel
    // (v3.19 ordering), and the close end hides only while
    // its generation is still current, so a superseded close
    // can never hide a reopened sheet. Natural completion is
    // unchanged (generation still matches, the sheet still
    // goes GONE at full alpha with translation reset). The
    // Room drawer does not call this helper (it has its own
    // guarded close path since v3.67), so this guards the
    // shared helper itself and any future caller of it.
    // Visual only; durations, targets and the row reset below
    // are untouched.
    private static java.util.WeakHashMap<View, Integer> sheetGens =
            new java.util.WeakHashMap<View, Integer>();
    public static void sheet(final View v, boolean show) {
        float dy = 32f * v.getResources().getDisplayMetrics().density;
        Integer sheetCur = sheetGens.get(v);
        final int sheetGen = (sheetCur == null ? 1 : sheetCur + 1);
        sheetGens.put(v, sheetGen);
        v.animate().cancel();
        // v2.48: drop any stale close listener first — it used to
        // survive onto the next show animation and hide the list
        // the moment the show ended (appear-then-vanish bug).
        v.animate().setListener(null);
        if (show) {
            // v3.78: premium sheet-show reopen continuity — the
            // mirror of the drawer open fix (v3.36) on the one
            // branch of this helper v3.70 left untouched: the show
            // used to force translationY=dy + alpha=0
            // unconditionally, so a show tapped mid-close (sheet
            // still VISIBLE, part slid out, part faded) jumped
            // fully out + fully transparent in one frame before
            // sliding back in, the same snap class v3.34/v3.35/
            // v3.36 fixed for the nudges, the morph and the
            // drawer. A fresh show is unchanged (sheet GONE, so
            // it still starts from off/0, invisible until the
            // slide begins); a mid-close (or mid-show) show now
            // animates from wherever the close/show left it to
            // 0/1 over the same 260ms, reading as a smooth
            // reversal. The close branch already resets the rows
            // to 1/0 up front, so no row rides a stale offset
            // into the reversal, and the v3.70 generation guard
            // above still stops a cancelled close end from
            // hiding this reopened sheet. Visual only; targets
            // and duration untouched. Honest scope note, as in
            // v3.70: no live caller uses this helper today (the
            // Room drawer has its own path since v2.48), so this
            // guards the shared helper itself and any future
            // caller of it, rather than fixing a live glitch on
            // a screen today.
            boolean midClose = v.getVisibility() == View.VISIBLE;
            v.setVisibility(View.VISIBLE);
            if (!midClose) {
                v.setTranslationY(dy);
                v.setAlpha(0f);
            }
            v.animate().translationY(0f).alpha(1f).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            // v2.10: a close mid-cascade used to leave rows half-faded
            // or offset, so the next open started from a dirty state —
            // cancel the row animators and reset them up front; the
            // container's own fade still carries the close.
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    c.animate().cancel();
                    c.setAlpha(1f);
                    c.setTranslationY(0f);
                }
            }
            v.animate().translationY(dy).alpha(0f).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator())
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(android.animation.Animator a) {
                            Integer now = sheetGens.get(v);
                            if (now == null || now != sheetGen) return;
                            v.setVisibility(View.GONE);
                            v.setTranslationY(0f);
                            v.setAlpha(1f);
                            v.animate().setListener(null);
                        }
                    }).start();
        }
    }

    // v2.10: premium sheet-row cascade — the Room talk list used to
    // slide in as one solid block (v2.09 sheet) while the Talks list
    // has poured in row by row since v2.07. On sheet open, the rows
    // now visible cascade in the same 45ms-step fade + rise, posted
    // so it runs after the sheet's setSelection layout pass. Rows
    // scrolled into view later are untouched (no replay on scroll,
    // and the rotation-restore path never calls this, so a rotated
    // list never replays it either). Visual only.
    // v3.75: premium stagger restart continuity — the cascade used
    // to force every row to alpha 0 + 24dp rise unconditionally, so
    // a second cascade landing mid-pour snapped half-poured rows
    // back to transparent in one frame before pouring again. The
    // real trigger is the Room drawer: the open path cascades the
    // rows (v2.10) and a talk list finishing loading while the
    // drawer is already open cascades them again (v3.06), and the
    // v3.06 guard only checks the panel is settled, not that the
    // open cascade finished — the two cascades overlap inside one
    // 750ms window. The settled-vs-in-flight rule from v3.39 /
    // v3.52 now lives in the shared helper: a settled row (alpha
    // ~1, X ~0, Y ~0) pours visibly from 0/24dp exactly as before,
    // while a mid-pour row animates on from its current alpha/X/Y
    // to 1/0/0 over the same 300ms with the same 45ms stagger, so
    // the first pour rides on instead of snapping. staggerChildren-
    // FromCurrent keeps its own entry for its mid-close caller;
    // every other caller gains the rule at once. Visual only;
    // targets, rhythm and the no-replay-on-scroll rule untouched.
    public static void staggerChildren(final android.view.ViewGroup parent) {
        parent.post(new Runnable() {
            @Override public void run() {
                float dy = 24f * parent.getResources().getDisplayMetrics().density;
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View c = parent.getChildAt(i);
                    c.animate().cancel();
                    if (c.getAlpha() >= 0.999f && Math.abs(c.getTranslationX()) <= 0.5f
                            && Math.abs(c.getTranslationY()) <= 0.5f) {
                        c.setAlpha(0f);
                        c.setTranslationY(dy);
                    }
                    c.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(300)
                            .setStartDelay(Math.min(i, 10) * 45)
                            .setInterpolator(new DecelerateInterpolator()).start();
                }
            }
        });
    }

    // v3.39: premium drawer-row reopen continuity — the v3.36
    // panel fix and the v3.38 header fix left one snap behind in
    // the same mid-close reopen: setPlayListPanel's open path
    // cleared every visible row's X to 0 in one frame and then
    // staggerChildren forced alpha 0 + 24dp rise, so a row
    // half-dissolved by the v3.05 close (alpha mid-fade, drifted
    // right up to 16dp) jumped to fully transparent, back to X 0
    // and dropped 24dp before cascading again — the same restart
    // snap v3.37 fixed for the title/seek/controls pours. This
    // variant judges each row on its own values, posted after
    // the caller's setSelection layout pass exactly like
    // staggerChildren: a settled row (alpha ~1, X ~0, Y ~0 —
    // including one whose close dissolve was still in its start
    // delay) pours visibly from 0/24dp as before, while a
    // mid-dissolve row animates on from its current alpha/X/Y
    // to 1/0/0 over the same 300ms with the same 45ms stagger,
    // so the close drift rides back instead of snapping. Only
    // the mid-close reopen caller uses it; fresh opens and the
    // v3.06 load-into-open-drawer cascade keep staggerChildren.
    // Visual only; targets and rhythm untouched.
    public static void staggerChildrenFromCurrent(final android.view.ViewGroup parent) {
        parent.post(new Runnable() {
            @Override public void run() {
                float dy = 24f * parent.getResources().getDisplayMetrics().density;
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View c = parent.getChildAt(i);
                    c.animate().cancel();
                    if (c.getAlpha() >= 0.999f && Math.abs(c.getTranslationX()) <= 0.5f
                            && Math.abs(c.getTranslationY()) <= 0.5f) {
                        c.setAlpha(0f);
                        c.setTranslationY(dy);
                    }
                    c.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(300)
                            .setStartDelay(Math.min(i, 10) * 45)
                            .setInterpolator(new DecelerateInterpolator()).start();
                }
            }
        });
    }

    // v2.15: premium panel entrance — the Room's bottom panel used
    // to appear as one static block under the shrine photo while
    // every list in the app already pours in (v2.07/v2.10/v2.12/
    // v2.14). On a fresh open the panel's rows now fade + rise in
    // order (sayadaw, now-playing, seek row, controls row, 80ms
    // steps), choreographed with the 700ms photo crossfade. The
    // caller passes only existing views and only on first creation,
    // so rotations and back-stack returns stay still; a later title
    // fade (v2.09) cancels just that view's entrance animator and
    // still ends at full opacity. Visual only.
    // v3.76: premium entrance restart continuity — the helper used
    // to force every view to alpha 0 + 24dp rise unconditionally,
    // so a posted entrance landing after a poll pour had already
    // started on the same view snapped it back to transparent in
    // one frame before pouring again. The real trigger is the Room
    // fresh open: entrance is posted (views[0].post, so it runs
    // after the layout pass) while the poll ticks every 500ms, and
    // a track already playing (e.g. resumed from the notification)
    // pours nowPlaying (v3.49) and the seek row (v2.82) inside the
    // entrance's 560ms window — the pours themselves already flow
    // on from current values, but the entrance coming second did
    // not. The settled-vs-in-flight rule from v3.37/v3.49/v3.52/
    // v3.75 now lives in the shared helper: a settled view (alpha
    // ~1, Y ~0) pours visibly from 0/24dp exactly as before, while
    // a mid-pour view animates on from its current alpha/Y to
    // 1/0 over the same 320ms with the same 80ms stagger, so the
    // poll pour rides on instead of snapping. Every caller gains
    // the rule at once (Main header/nav, Talks title, Room panel,
    // library header); targets, rhythm and the fresh-open-only
    // gating are untouched.
    public static void entrance(final View... views) {
        if (views.length == 0) return;
        views[0].post(new Runnable() {
            @Override public void run() {
                float dy = 24f * views[0].getResources().getDisplayMetrics().density;
                for (int i = 0; i < views.length; i++) {
                    View v = views[i];
                    if (v == null) continue;
                    v.animate().cancel();
                    if (v.getAlpha() >= 0.999f && Math.abs(v.getTranslationY()) <= 0.5f) {
                        v.setAlpha(0f);
                        v.setTranslationY(dy);
                    }
                    v.animate().alpha(1f).translationY(0f).setDuration(320)
                            .setStartDelay(i * 80)
                            .setInterpolator(new DecelerateInterpolator()).start();
                }
            }
        });
    }

    // v2.21: premium photo fade-in — the Room's shrine photo used
    // to pop in instantly on a fresh open while the bottom panel
    // poured in below it (v2.15 entrance), so the screen opened
    // photo-first and panel-second. On a fresh open only, the photo
    // view itself now fades 0->1 over 500ms (decelerate, no rise —
    // a full-screen photo must not slide), choreographed with the
    // panel entrance. Alpha-only, so taps on the photo (pause/
    // resume) still work during the fade, and rotations / back-stack
    // returns never replay it because the caller gates on a fresh
    // open. Visual only.
    // v3.52: premium fadeIn restart continuity — the helper used to
    // force alpha 0 unconditionally, so a second fade landing
    // mid-flight snapped the view back to fully transparent in one
    // frame before fading again. The drawer mark-fade family hits
    // exactly that: on auto-advance the v3.23 previous-row fade and
    // the v3.22 newly-played fade post back-to-back for the same
    // row, and rapid Next taps stack v3.23/v3.24 posts inside one
    // 250ms window, so the second fade dipped a half-faded row to
    // 0 before rising again. The settled case is unchanged (alpha
    // ~1 still starts from 0, so an ordinary fade-in reads exactly
    // as before); a mid-fade restart now flows on from its current
    // alpha over the same duration, ending at exactly 1. This is
    // the v3.37/v3.49 settled-vs-in-flight rule, moved into the
    // shared helper so every caller gains it at once (the library
    // footer total, the Talks progress/retry fades and the Room
    // retry fade all restart the same way). Visual only; durations,
    // targets and the fadeOut mirror are untouched, and a fadeOut
    // cancelled by this call still never runs its end action, so
    // no view is ever stranded half-transparent or GONE.
    // v3.62: premium fadeOut end-action guard — the shared fade
    // helper's own history disagrees with itself: v3.52 assumed a
    // cancelled fadeOut never runs its end action, while v3.19
    // (Room drawer) recorded that a cancelled ViewPropertyAnimator
    // still fires its end action. Under the v3.19 behaviour the
    // helper had a real hole: a second fade landing mid-fadeOut
    // (a retry inside the Talks spinner's 250ms completion fade,
    // a retype inside the library empty word's 200ms fade-out)
    // cancelled the fadeOut, whose end action then hid the view
    // (GONE + alpha 1) underneath the new animation — the spinner
    // vanished for the whole retry load, the empty word's glide
    // back to full ran 1->1 on a hidden view. A generation map per
    // view (same WeakHashMap model as morphGens, so no leak)
    // closes it: fadeIn, fadeOut and cancelFade all bump BEFORE
    // the cancel (the v3.19 ordering lesson — bump after cancel
    // lets the fired end action still pass), and the fadeOut end
    // action runs only while its generation is still current, so
    // a superseded fade can never hide a live view. Natural
    // completion is unchanged (generation still matches, the view
    // still goes GONE at full alpha), and if end actions never
    // fire on cancel the guard is a harmless no-op. Visual only;
    // durations, targets and every caller below are untouched.
    private static java.util.WeakHashMap<View, Integer> fadeGens =
            new java.util.WeakHashMap<View, Integer>();
    private static int bumpFadeGen(View v) {
        Integer cur = fadeGens.get(v);
        int gen = (cur == null ? 1 : cur + 1);
        fadeGens.put(v, gen);
        return gen;
    }
    // Cancel any Fx fade on this view without letting a stale
    // fadeOut end action hide it — use this instead of a raw
    // animate().cancel() wherever the view must stay visible.
    public static void cancelFade(View v) {
        if (v == null) return;
        bumpFadeGen(v);
        v.animate().cancel();
    }
    public static void fadeIn(final View v, long durationMs) {
        if (v == null) return;
        bumpFadeGen(v);
        v.animate().cancel();
        if (v.getAlpha() >= 0.999f) v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(durationMs)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    // v2.62: premium fade-out — the mirror of fadeIn for views
    // that leave. Fades to 0 over durationMs, then sets GONE and
    // restores alpha to 1, so a later fadeIn starts from a clean
    // state and a cancelled fade (a new show starting mid-fade)
    // never strands the view half-transparent: cancelling does
    // not run the end action, and the new show resets alpha
    // itself. Visual only.
    public static void fadeOut(final View v, long durationMs) {
        if (v == null) return;
        final int gen = bumpFadeGen(v);
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(durationMs)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        Integer now = fadeGens.get(v);
                        if (now == null || now != gen) return;
                        v.setVisibility(View.GONE);
                        v.setAlpha(1f);
                    }
                }).start();
    }

    // v2.23: premium theme transition — day/night used to swap
    // colours instantly (a hard flash) on the Main toggle and the
    // Settings day/night buttons, while every other screen change
    // already moves (v2.07 nav fade, v2.15/v2.21 Room entrances).
    // The caller passes the swap as a Runnable: the root dips to
    // 0.55 alpha over 140ms, the swap runs at the dip, then the
    // root rises back to exactly alpha 1 over 260ms (decelerate).
    // A rapid re-tap cancels the running dip and starts a fresh
    // one, so the screen never strands at half alpha; only the
    // explicit theme taps use it — onResume/onCreate theme
    // applications still swap instantly, and the v2.07 launch fade
    // / v2.17 header entrance are untouched. Visual only.
    // v3.63: premium themeDip restart guard — the v3.62 fadeOut
    // guard applied to the one shared helper it never reached.
    // themeDip was the only dip with unguarded end actions: under
    // the v3.19 behaviour (a cancelled ViewPropertyAnimator still
    // fires its end action), a rapid re-tap mid-dip cancelled the
    // dip, whose end action then ran the swap early AND started a
    // rise — and the new dip's cancel of that rise fired the
    // rise's end action, snapping alpha straight to 1 in one
    // frame mid-dip before dipping again: a visible flash on
    // every fast double-tap of a Settings toggle, the Main day/
    // night toggle, or the library sort button. A generation map
    // per root (same WeakHashMap model as morphGens/fadeGens)
    // closes it: each themeDip bumps BEFORE the cancel, the dip
    // end runs the swap and starts the rise only while its
    // generation is still current, and the rise end sets alpha 1
    // only while its generation is still current, so a superseded
    // dip/rise can neither swap early nor snap the alpha. A
    // settled single tap reads exactly as before (same dip, same
    // swap at the dip, same 260ms rise to exactly 1), and the
    // swaps themselves are untouched (callers set prefs before
    // the dip; refresh() just re-reads them). Visual only.
    private static java.util.WeakHashMap<View, Integer> dipGens =
            new java.util.WeakHashMap<View, Integer>();
    public static void themeDip(final View root, final Runnable swap) {
        if (root == null) { if (swap != null) swap.run(); return; }
        Integer cur = dipGens.get(root);
        final int gen = (cur == null ? 1 : cur + 1);
        dipGens.put(root, gen);
        root.animate().cancel();
        root.animate().alpha(0.55f).setDuration(140)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) {
                        Integer now = dipGens.get(root);
                        if (now == null || now != gen) return;
                        if (swap != null) swap.run();
                        root.animate().cancel();
                        root.animate().alpha(1f).setDuration(260)
                                .setInterpolator(new DecelerateInterpolator())
                                .setListener(new android.animation.AnimatorListenerAdapter() {
                                    @Override public void onAnimationEnd(android.animation.Animator a2) {
                                        Integer now2 = dipGens.get(root);
                                        if (now2 == null || now2 != gen) return;
                                        root.setAlpha(1f);
                                    }
                                }).start();
                    }
                }).start();
    }

    // v13.07: system bars follow the theme — the status bar and the
    // navigation bar paint the same colours as the top and bottom
    // of the day/night gradient, so the theme runs edge to edge
    // instead of stopping at the theme's maroon status bar (day)
    // or a maroon strip over a near-black app (night). Colours are
    // the gradient endpoints themselves (bg_gradient_day/night):
    // status = top, nav = bottom. Day icons are dark, night icons
    // light. LIGHT_NAVIGATION_BAR needs API 26, so on 24/25 the
    // nav bar stays black (light buttons stay readable) while the
    // status bar still follows the theme (LIGHT_STATUS_BAR is
    // API 23+). Room passes night=true always: its altar is dark
    // in both themes. Only the two light-icon bits are flipped —
    // every other system-ui flag the window carries is preserved.
    // All calls are guarded: a window without a decor view, or any
    // throw, leaves the theme default in place.
    public static void applySystemBars(Activity a, boolean night) {
        if (a == null) return;
        try {
            android.view.Window w = a.getWindow();
            if (w == null) return;
            android.view.View decor = w.getDecorView();
            if (decor == null) return;
            w.setStatusBarColor(night ? 0xFF3A2415 : 0xFFFFF9E8);
            int vis = decor.getSystemUiVisibility();
            if (night) vis &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else vis |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                w.setNavigationBarColor(night ? 0xFF101010 : 0xFFEFD9A0);
                if (night) vis &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                else vis |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                w.setNavigationBarColor(0xFF000000);
            }
            decor.setSystemUiVisibility(vis);
        } catch (Exception ignored) {}
    }

    // v2.35: premium theme-toggle spin — the Day/Night toggle used
    // to swap its glyph instantly while the theme dipped (v2.23),
    // so the control itself never moved. It now turns one full
    // revolution over 450ms, starting with the dip and landing as
    // the new theme rises, reading as the sun/moon flipping over.
    // A dedicated ObjectAnimator on rotation only, so it never
    // cancels the press squash's scale animation on the same view
    // (ViewPropertyAnimator.cancel would freeze the scale mid
    // spring-back). The target is always the next multiple of
    // 360, so a rapid re-tap spins on from the current angle
    // instead of snapping, and the glyph ends upright every time.
    // Visual only. cancelSpin is called from onPause so a leave
    // mid-spin snaps the glyph to the nearest upright angle.
    private static android.animation.ObjectAnimator spinAnim;
    public static void spin(final View v) {
        if (v == null) return;
        if (spinAnim != null) spinAnim.cancel();
        float r = v.getRotation();
        float norm = ((r % 360f) + 360f) % 360f;
        float target = r - norm + 360f;
        spinAnim = android.animation.ObjectAnimator.ofFloat(v, "rotation", target);
        spinAnim.setDuration(450);
        spinAnim.setInterpolator(new DecelerateInterpolator());
        spinAnim.start();
    }
    public static void cancelSpin(final View v) {
        if (v == null) return;
        if (spinAnim != null && spinAnim.getTarget() == v) spinAnim.cancel();
        v.setRotation(Math.round(v.getRotation() / 360f) * 360f);
    }

    // v2.08: premium press feedback — a gentle squash (0.96) on
    // touch-down, springing back on lift, on existing tappable
    // views only (grid cards, list rows, Room controls). Visual
    // only, so it is NOT gated by the sound/vibration toggle.
    // Returns false so clicks, long-presses and ListView/GridView
    // scrolling still work; ACTION_CANCEL covers a finger that
    // slides into a scroll instead of lifting.
    // v3.90: premium press-channel separation — the squash used
    // to live on the shared ViewPropertyAnimator, so its DOWN
    // cancel() also killed whatever else was animating on the
    // same view: a tap landing on a Room drawer row or a library
    // row mid-cascade (staggerChildren), on the Main theme toggle
    // mid-entrance (Fx.entrance), or on a retry button mid-fade
    // (Fx.fadeIn) froze that pour/fade half-way — the row or
    // button sat half-faded or dropped until a rebind or a
    // recreate carried it home, while the squash itself sprang
    // back cleanly. The squash now runs on dedicated
    // ObjectAnimators for scaleX/scaleY (the same channel split
    // the spin, glyph-fade and nudge animators already use), so
    // a press never touches the ViewPropertyAnimator channel at
    // all: pours, cascades, fades and dims ride on undisturbed,
    // and a press mid-pour still squashes instantly from its
    // current scale. Durations, targets and easings are exactly
    // the v2.08 ones (110ms decelerate to 0.96; 180ms overshoot
    // 1.4 back to 1). cancelPress() is the rebind/morph side of
    // the split: morph() and the adapters' rebind hygiene call
    // it so a stale squash can never write scale after a reset
    // (the v3.47/v3.48 guarantee, kept on the new channel).
    // Honest scope note: a second tap landing inside a morph's
    // 220ms spring (a double-tap on a morph button) still has
    // two scale writers for that window — the press animator
    // and the morph's ViewPropertyAnimator — but both targets
    // sit at 1/0.96, and the UP spring or the next morph
    // settles it, so nothing strands half-squashed the way a
    // frozen pour used to. Visual only; no view gains or loses
    // a press, and the squash feel is unchanged.
    private static java.util.WeakHashMap<View, android.animation.AnimatorSet> pressAnims =
            new java.util.WeakHashMap<View, android.animation.AnimatorSet>();
    public static void cancelPress(final View v) {
        if (v == null) return;
        android.animation.AnimatorSet s = pressAnims.remove(v);
        if (s != null) s.cancel();
    }
    private static void pressScale(final View v, float target, long durationMs,
            android.view.animation.Interpolator interp) {
        cancelPress(v);
        android.animation.ObjectAnimator sx =
                android.animation.ObjectAnimator.ofFloat(v, "scaleX", target);
        android.animation.ObjectAnimator sy =
                android.animation.ObjectAnimator.ofFloat(v, "scaleY", target);
        final android.animation.AnimatorSet set = new android.animation.AnimatorSet();
        set.playTogether(sx, sy);
        set.setDuration(durationMs);
        set.setInterpolator(interp);
        set.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (pressAnims.get(v) == set) pressAnims.remove(v);
            }
        });
        pressAnims.put(v, set);
        set.start();
    }
    public static void press(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        pressScale(view, 0.96f, 110, new DecelerateInterpolator());
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        pressScale(view, 1f, 180, new OvershootInterpolator(1.4f));
                        break;
                }
                return false;
            }
        });
    }

    // v4.73: seekable mini-player progress bar — every now-playing
    // strip (Main, Talks, library, Settings) shares this one helper,
    // so the bar behaves the same everywhere. The bar itself is only
    // 4dp tall, far too thin to tap, so the strip carries a
    // TouchDelegate that widens it to the strip's own bottom padding
    // plus the gap above it (~20dp tall) without changing how it
    // looks. A tap or drag previews the position live and commits
    // on lift via AudioService.ACTION_SEEK; the touch is consumed,
    // so a bar scrub never also opens the player the way a strip tap
    // does. Guarded on AudioService.liveSession, so the dead-session
    // continue-listening strip (no live track, empty bar) keeps its
    // tap-to-resume behaviour untouched — a bar touch there falls
    // through to the strip click instead of seeking nowhere.
    public static void miniSeekBar(final android.app.Activity act, final View strip) {
        if (act == null || strip == null) return;
        final android.widget.ProgressBar pb =
                (android.widget.ProgressBar) strip.findViewById(R.id.nowStripProgress);
        if (pb == null) return;
        pb.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                // v7.93: a CANCEL always clears the drag mark first,
                // even if the session died mid-drag (the live check
                // below would otherwise return early and strand the
                // mark, freezing the bar on the next live strip).
                if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    pb.setTag(R.id.nowStripProgress, null);
                    pb.setTag(R.id.stripDragUrl, null); // v8.01: identity dies with the mark
                    syncStripProgress(pb);
                    return true;
                }
                // v8.01: the dragged talk, captured at DOWN (see
                // below) — read BEFORE the UP pre-clear wipes the
                // tags, so the commit branch can still compare it
                // against the live talk. A null capture means
                // "unknown", never a mismatch (see the UP branch).
                Object dragObj = pb.getTag(R.id.stripDragUrl);
                String dragUrl = (dragObj instanceof String) ? (String) dragObj : null;
                // v7.98: an UP landing while the replacement talk is
                // still buffering (session dead or duration unknown)
                // used to return early WITHOUT clearing the drag mark
                // — the v7.95 self-heal only clears a mark it finds
                // dead at tick time, so if the new talk prepared
                // before the next tick, the tick honoured a drag
                // whose finger was long gone and froze bar + clock
                // on the old preview while the new talk played on.
                // Clear the mark on UP first, whatever the session
                // state; the live branch below still commits the
                // seek, the not-live fall-through (tap-to-resume)
                // is unchanged, and the next tick now writes the
                // live fraction because the mark is gone.
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    pb.setTag(R.id.nowStripProgress, null);
                    pb.setTag(R.id.stripDragUrl, null); // v8.01: local dragUrl above still holds it
                }
                int dur = AudioService.liveDurationMs;
                if (!AudioService.liveSession || dur <= 0) return false;
                int w = view.getWidth();
                if (w <= 0) return false;
                float f = e.getX() / (float) w;
                if (f < 0f) f = 0f;
                if (f > 1f) f = 1f;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_MOVE:
                        // v7.93: mark the drag (see syncStripProgress)
                        // so the strip's 1s tick cannot yank the
                        // preview back to the live position mid-drag.
                        // v8.01: DOWN also captures the dragged
                        // talk's identity (AudioService.liveUrl, the
                        // no-bind mirror of the Room wave's
                        // dragTalkUrl) — MOVE refreshes the mark
                        // but never re-captures, so a talk change
                        // mid-drag stays visible at UP.
                        if (e.getActionMasked() == MotionEvent.ACTION_DOWN)
                            pb.setTag(R.id.stripDragUrl, AudioService.liveUrl);
                        pb.setTag(R.id.nowStripProgress, Boolean.TRUE);
                        pb.setProgress((int) (f * 1000));
                        return true;
                    case MotionEvent.ACTION_UP: {
                        pb.setTag(R.id.nowStripProgress, null);
                        // v8.01: a scrub that outlived its talk is
                        // DISCARDED, never applied to the talk
                        // that replaced it — the strip mirror of
                        // the Room wave's v7.97 discard. A finger
                        // mid-scrub when a talk auto-advances (or
                        // is skipped from the card) used to seek
                        // the NEW talk to the OLD talk's fraction
                        // of the new duration, because UP only
                        // checked that SOME live duration existed
                        // (v7.98's buffering guard never fired
                        // once the new talk prepared). A positive
                        // mismatch of two known identities
                        // discards: bar restored from the live
                        // service at once, clock on the next tick,
                        // touch consumed so the scrub never also
                        // opens the Room. Unknown on either side
                        // (null capture, null live) keeps the old
                        // behaviour — a same-URL repeat-one restart
                        // still seeks, as does every normal drag.
                        String liveUrl = AudioService.liveUrl;
                        if (dragUrl != null && liveUrl != null && !dragUrl.equals(liveUrl)) {
                            syncStripProgress(pb);
                            return true;
                        }
                        int ms = (int) (f * dur);
                        pb.setProgress((int) (f * 1000));
                        Intent svc = new Intent(act, AudioService.class);
                        svc.setAction(AudioService.ACTION_SEEK);
                        svc.putExtra("seekMs", ms);
                        act.startService(svc);
                        tap(act);
                        return true;
                    }
                    default:
                        return true;
                }
            }
        });
        // v11.39: the delegate is (re)installed from the bar's real
        // laid-out bounds, not once from wire time. Wire runs in
        // onCreate while the strip is still GONE (visibility=gone
        // in XML, no session yet on a cold start), so the old
        // one-shot post measured a never-laid-out bar: a
        // zero-size hit rect, expanded to a degenerate delegate
        // that never widened anything. The strip then became
        // VISIBLE on first play with no further install, so the
        // promised ~20dp seek target stayed a 4dp bar and taps
        // just above/below it opened the player instead of
        // seeking — the exact mis-tap an older listener makes
        // most. A layout listener re-installs whenever the bar
        // gains real size (strip shown, rotation, font change);
        // a zero size is skipped, never installed degenerate,
        // and the original post is kept for the already-visible
        // case where no layout change ever fires. Installing a
        // delegate never triggers layout, so this cannot loop.
        final Runnable installDelegate = new Runnable() {
            @Override public void run() {
                if (pb.getWidth() <= 0 || pb.getHeight() <= 0) return;
                android.graphics.Rect r = new android.graphics.Rect();
                pb.getHitRect(r);
                if (r.width() <= 0 || r.height() <= 0) return;
                // v11.74: the expansion is in dp, not raw pixels — the
                // old plain 6/10 widened the 4dp bar to ~20dp only on
                // mdpi; on xhdpi it was 12dp and on xxhdpi ~9dp, so a
                // tap just above/below the bar missed the delegate,
                // fell through to the strip click and opened the Room
                // instead of seeking — the mis-tap v11.39 was written
                // to fix. 6dp above + 4dp bar + 10dp below = ~20dp.
                float d = strip.getResources().getDisplayMetrics().density;
                r.top -= Math.round(6 * d);
                r.bottom += Math.round(10 * d);
                strip.setTouchDelegate(new android.view.TouchDelegate(r, pb));
            }
        };
        pb.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                    int ol, int ot, int or, int ob) {
                installDelegate.run();
            }
        });
        strip.post(installDelegate);
    }

    // v7.93: the one strip-bar writer — the four strips' 1s ticks
    // used to set the bar unconditionally from livePositionMs, so
    // a finger mid-scrub watched its preview yanked back to the
    // live position on the next tick (the Room wave solved the
    // same fight with its seeking flag in v2.20; the strips never
    // got one). While a mini-seek drag is in flight (mark set by
    // miniSeekBar, keyed on the bar's own id so the mark dies
    // with the view and can never strand across activities) the
    // tick leaves the preview alone; the drag's UP commits via
    // ACTION_SEEK and the service re-syncs livePositionMs at once
    // (seekTo -> updateSession), so the next tick lands on the
    // new position with no hold needed. Formula unchanged: an
    // unknown duration still reads empty, never a stale fraction.
    // v7.95: self-heal for a drag that outlives its talk — a
    // finger mid-scrub when the talk changes, errors or stops
    // leaves the mark set while the session is gone (or the new
    // stream is still buffering, duration unknown), and the old
    // skip-while-marked rule then froze the bar on the dragged
    // preview forever: no later tick ever wrote it again, since
    // every tick returned early on the stranded mark. A mark
    // with no live session or no known duration is a dead drag
    // by definition (a drag can only start while both hold), so
    // the tick clears it and writes the live fraction at once —
    // 0 while the new stream buffers, the real fraction once it
    // prepares — instead of honouring a preview for a talk that
    // no longer exists. A live drag (session up, duration
    // known) is untouched: the mark still suppresses the tick.
    public static void syncStripProgress(android.widget.ProgressBar pb) {
        if (pb == null) return;
        if (Boolean.TRUE.equals(pb.getTag(R.id.nowStripProgress))) {
            if (!AudioService.liveSession || AudioService.liveDurationMs <= 0) {
                pb.setTag(R.id.nowStripProgress, null);
                pb.setTag(R.id.stripDragUrl, null); // v8.01: identity dies with the dead mark
            }
            else return;
        }
        int dur = AudioService.liveDurationMs;
        int pos = AudioService.livePositionMs;
        pb.setProgress(dur > 0 && pos >= 0 ? (int) ((long) pos * 1000 / dur) : 0);
    }

    // v5.32: the mini-player's live clock — every now-playing strip
    // (Main, Talks, library, Settings) shows a progress bar but no
    // times, so a listener could never tell how far into a talk
    // they were without opening the Room. One helper owns the
    // suffix so all four strips read identically: " · mm:ss / mm:ss"
    // (H:MM:SS for hour+ talks, the Room's v1.47 rule), empty when
    // the duration is unknown, never a stale fraction. The strips'
    // 1s ticks already read livePositionMs/liveDurationMs for the
    // bar, so the clock rides the same tick with no new timer.
    public static String stripTimeSuffix(int posMs, int durMs) {
        if (durMs <= 0 || posMs < 0) return "";
        return " · " + stripFmtTime(posMs) + " / " + stripFmtTime(durMs);
    }

    // v5.33: strip clock mode — the Room's own clock (v4.12) already
    // taps elapsed <-> remaining, but the strips (v5.32) only ever
    // showed elapsed/total, so a listener off the Room could never
    // see the time left. Remaining mode mirrors the Room exactly:
    // " · -mm:ss" (the Room's minus + time-left, no total), same
    // unknown-duration rule (empty, never a stale fraction).
    public static String stripClockSuffix(int posMs, int durMs, boolean remaining) {
        if (durMs <= 0 || posMs < 0) return "";
        if (remaining) return " \u00b7 \u2212" + stripFmtTime(Math.max(0, durMs - posMs));
        return stripTimeSuffix(posMs, durMs);
    }

    // v7.96: strip clock follows the drag preview -- v7.93 froze the
    // bar while a mini-seek drag was in flight but left the clock
    // on the live position, so mid-drag the bar preview and the
    // clock disagreed on the same strip (bar at the finger, clock
    // still at the live position). While the drag mark is set (and
    // the session/duration are live, the same gate syncStripProgress
    // uses to honour a drag), the clock reads the bar's preview
    // fraction converted back to ms; remaining mode subtracts the
    // preview, like the live mode does for the live position. A
    // dead drag (no session / no duration) falls through to the
    // live suffix, which reads empty -- the v7.95 self-heal then
    // clears the mark on the same tick's bar write, so clock and
    // bar agree again at once. The bar's own max is the scale,
    // never a hardcoded 1000, so a layout max change cannot skew it.
    public static String stripClockSuffixForStrip(android.widget.ProgressBar pb, int posMs, int durMs, boolean remaining) {
        if (pb != null && Boolean.TRUE.equals(pb.getTag(R.id.nowStripProgress))
                && AudioService.liveSession && durMs > 0 && pb.getMax() > 0) {
            int previewMs = (int) ((long) pb.getProgress() * durMs / pb.getMax());
            return stripClockSuffix(previewMs, durMs, remaining);
        }
        return stripClockSuffix(posMs, durMs, remaining);
    }

    // v5.34: shared mm:ss / H:MM:SS formatter — the strips kept it
    // private, but the Talks rows and Room drawer rows now show a
    // per-talk resume time too, and they must read identically.
    public static String fmtTime(int ms) {
        int total = Math.max(0, ms / 1000);
        if (total >= 3600)
            return (total / 3600) + ":" + String.format("%02d", (total % 3600) / 60)
                    + ":" + String.format("%02d", total % 60);
        return (total / 60) + ":" + String.format("%02d", total % 60);
    }

    private static String stripFmtTime(int ms) {
        return fmtTime(ms);
    }

    // v5.58: shared talk-title search match — the main sayadaw list
    // (v1.47 + NFC v1.90) and the downloaded library (v1.51 + v1.91)
    // match space-insensitively, but the two talk-title searches
    // did not: the Talks chooser's rebuildSearch and the Room
    // drawer's drawerSearchSeq both used a plain contains, so a
    // title typed without its spaces found nothing there while
    // the same query worked on Main. One helper owns the rule so
    // the two title searches can never drift apart again: NFC
    // first (identical-looking Myanmar stacking variants match),
    // then a plain substring hit, or a hit with spaces stripped
    // from both sides (extra or missing spaces still find it).
    // Normalising already-normal text is a no-op, so every match
    // the old contains found is still found.
    // v7.24: case-insensitive too — the v5.58 rule was the only
    // search in the app that stayed case-sensitive (Main's
    // sayadaw search and the library search both lowercase), so
    // a Latin-letter query in the other case ("dhamma" for a
    // "Dhamma" title, "part 2" for "Part 2") found nothing in
    // the Talks chooser and the Room drawer while succeeding
    // everywhere else. Both sides lowercase after NFC (ROOT
    // locale, so no locale surprises); Myanmar script has no
    // case, so Myanmar queries match exactly as before.
    // v10.72: whitespace stripping for every search in the app —
    // the flat (space-insensitive) match used to strip only the
    // plain ASCII space, but a title can carry a non-breaking
    // space: cleanTitle collapses \s+ and \s does NOT cover
    // U+00A0, so a numeric &#160; in a site title decodes to a
    // NBSP that survives into the shown title, and a pasted query
    // can carry one too. Either way the visible space is there
    // but the flat match never stripped it, so a regular-space
    // query missed a title that plainly contains those words.
    // stripWhitespace removes every Java-whitespace char plus
    // the NBSP family (U+00A0, U+2007, U+202F); the plain
    // contains() hit is untouched, so every match the old rule
    // found is still found, and whitespace-variant titles now
    // match as well. Main and the library use it directly on
    // their own flat strings (they do not go through
    // titleMatches).
    // v10.81: flat match also strips invisible FORMAT chars — the
    // v10.72 NBSP family was only half the invisible-char class:
    // U+200B ZWSP, U+200C ZWNJ, U+200D ZWJ, U+FEFF BOM and U+2060
    // WORD JOINER are Character.FORMAT, NOT whitespace, so
    // isWhitespace() returns false for all of them and the old
    // strip left them in place. That matters most for Myanmar
    // text, where ZWSP is the standard invisible word-break: a
    // site title or a pasted query carrying one missed on BOTH
    // the plain contains() and the flat match, even though the
    // words visibly match. The plain hit is still tried first,
    // so every match the old rule found is still found; only
    // the flat fallback now also drops format chars, on both
    // sides. One helper covers all four searches (Main and the
    // library call it directly, Talks and the Room drawer go
    // through titleMatches). Displayed titles are untouched —
    // ZWSP still does its line-breaking job on screen.
    public static String stripWhitespace(String s) {
        if (s == null) return null;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '\u00a0' || c == '\u2007' || c == '\u202f'
                    || Character.getType(c) == Character.FORMAT) { any = true; break; }
        }
        if (!any) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '\u00a0' || c == '\u2007' || c == '\u202f'
                    || Character.getType(c) == Character.FORMAT) continue;
            b.append(c);
        }
        return b.toString();
    }
    // v10.71: Myanmar-digit folding for every search in the app —
    // talk titles are numbered in Myanmar digits (၁၂၃) while many
    // keyboards type Latin digits (123), and some titles use Latin
    // digits a Myanmar-digit query would miss. Folding U+1040–
    // U+1049 to 0–9 on both sides makes either keystroke find
    // either title; digit-free text is untouched, so every match
    // the old rule found is still found. Main's sayadaw search
    // and the library search call this directly on their own
    // normalised strings (they do not go through titleMatches).
    public static String foldDigits(String s) {
        if (s == null) return null;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u1040' && c <= '\u1049') { any = true; break; }
        }
        if (!any) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append((c >= '\u1040' && c <= '\u1049') ? (char) ('0' + (c - '\u1040')) : c);
        }
        return b.toString();
    }
    public static boolean titleMatches(String title, String query) {
        if (title == null || query == null || query.length() == 0) return false;
        try {
            String t = foldDigits(java.text.Normalizer.normalize(title,
                    java.text.Normalizer.Form.NFC).toLowerCase(java.util.Locale.ROOT));
            String q = foldDigits(java.text.Normalizer.normalize(query,
                    java.text.Normalizer.Form.NFC).toLowerCase(java.util.Locale.ROOT));
            if (t.contains(q)) return true;
            return stripWhitespace(t).contains(stripWhitespace(q));
        } catch (Exception ignored) {
            return foldDigits(title.toLowerCase(java.util.Locale.ROOT))
                    .contains(foldDigits(query.toLowerCase(java.util.Locale.ROOT)));
        }
    }

    // v4.75: the mini-player's favourite — the strip's title suffix
    // (v4.26) reads favTalks, but every activity re-derived the
    // sounding talk's URL its own way (lastUrl for Room, a dlMap
    // reverse lookup for library files). One helper owns that rule
    // so the new strip star and the suffix can never disagree:
    // Room sessions resolve to lastUrl, library sessions to the
    // URL whose dlMap entry names the last library file, and a
    // library file with no dlMap entry resolves to null (never a
    // guessed URL, so the star hides instead of favouring wrong).
    public static String stripFavUrl(Context ctx, android.content.SharedPreferences sp) {
        if (sp == null) return null;
        try {
            if ("lib".equals(sp.getString("lastKind", ""))) {
                String lp = sp.getString("lastLibPath", "");
                if (lp.length() == 0) return null;
                String fn = new java.io.File(lp).getName();
                org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
                java.util.Iterator<String> keys = map.keys();
                // v8.41: exactly-one match, never the first match —
                // the old loop returned the first URL claiming this
                // filename, so a dual claim (two talks mapped to
                // one file — the pre-v8.40 collision, still
                // creatable by the delivered v8.39, or a file
                // deleted outside the app and re-downloaded under
                // the other talk's name) let the strip star toggle
                // the WRONG talk's favourite, the strip share send
                // the WRONG talk's link, and the strip sayadaw name
                // name the WRONG sayadaw. The v8.24 Room seed
                // already refuses a filename two URLs claim
                // ("never a guess"); this lookup is the strip
                // equivalent, and every caller is null-safe (star
                // hidden, toggle returns early, share returns
                // null, sayadaw falls back to generic text), so
                // an ambiguous file now shows no star/share/name
                // instead of a wrong one. Zero matches still
                // returns null, as before.
                String hit = null;
                int matches = 0;
                while (keys.hasNext()) {
                    String u = keys.next();
                    if (fn.equals(map.optString(u, ""))) { hit = u; matches++; }
                }
                return matches == 1 ? hit : null;
            }
            String u = sp.getString("lastUrl", "");
            return u.length() > 0 ? u : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    // v5.45: the downloaded talk's sayadaw — a library file never
    // carried one (stripShareText's own comment said so: no
    // sayadaw in prefs, and a wrong name is worse than none, the
    // v1.47 lesson), so every now-playing surface for a library
    // session read a generic line instead of a name. Downloads
    // now record their sayadaw beside dlMap (Room's
    // recordDownloadFile, stripEnqueueDownload below — both know
    // the Room sayadaw at enqueue time), and this helper resolves
    // it the same guarded way stripFavUrl resolves the URL: the
    // last library file's dlMap entry gives the URL, the
    // dlSayadaw entry for that URL gives the name. A file with
    // no dlMap entry, or a download from before v5.45 with no
    // dlSayadaw entry, resolves to null — never a guessed name —
    // so every caller keeps today's generic text for it.
    public static String stripLibSayadaw(Context ctx, android.content.SharedPreferences sp) {
        if (sp == null || !"lib".equals(sp.getString("lastKind", ""))) return null;
        try {
            String url = stripFavUrl(ctx, sp);
            if (url == null) return null;
            org.json.JSONObject m = new org.json.JSONObject(sp.getString("dlSayadaw", "{}"));
            String n = m.optString(url, "");
            return n.length() > 0 ? n : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    // v4.75: toggle one talk in favTalks — the Room's own toggle
    // (v4.19) copies the set before mutating it, because the set
    // getStringSet returns must never be mutated in place.
    // Returns true when the talk is now a favourite.
    public static boolean toggleFavTalk(Context ctx, String url) {
        if (url == null || url.length() == 0) return false;
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences(
                    "dhamma", Context.MODE_PRIVATE);
            java.util.Set<String> cur = sp.getStringSet("favTalks", new java.util.HashSet<String>());
            java.util.Set<String> next = new java.util.HashSet<String>(cur);
            boolean added;
            if (next.contains(url)) { next.remove(url); added = false; }
            else { next.add(url); added = true; }
            sp.edit().putStringSet("favTalks", next).apply();
            return added;
        } catch (Exception ignored) {
            return false;
        }
    }

    // v4.78: the mini-player's share text — the strip's own
    // sounding talk, in the Room shareTalk format (sayadaw —
    // title, original link, dhammadownload.com credit). The
    // title/URL come from the same prefs the strip itself
    // displays (lastTitle/lastUrl for Room, lastLibTitle plus
    // the dlMap reverse lookup for library), so the shared
    // text can never name a talk the strip is not showing.
    // v5.45: library sessions name their sayadaw when one was
    // recorded at download time (stripLibSayadaw above); a
    // download from before v5.45 still names none, and a wrong
    // name (the v1.47 notification lesson) is worse than none,
    // so an unrecorded file keeps the title-only format. Null
    // when no URL resolves or the title is empty, which is
    // also the share button's visibility rule on every strip.
    public static String stripShareText(Context ctx, android.content.SharedPreferences sp) {
        if (sp == null) return null;
        try {
            String url = stripFavUrl(ctx, sp);
            if (url == null) return null;
            String kind = sp.getString("lastKind", "");
            if ("lib".equals(kind)) {
                String title = sp.getString("lastLibTitle", "");
                if (title.length() == 0) return null;
                String sd = stripLibSayadaw(ctx, sp);
                return (sd != null ? sd + " \u2014 " : "") + title + "\n" + url + "\n\u1019\u1030\u101b\u1004\u103a\u1039: dhammadownload.com";
            }
            if (!"room".equals(kind)) return null;
            String name = sp.getString("lastName", "");
            String title = sp.getString("lastTitle", "");
            if (name.length() == 0 || title.length() == 0) return null;
            return name + " \u2014 " + title + "\n" + url + "\n\u1019\u1030\u101b\u1004\u103a\u1039: dhammadownload.com";
        } catch (Exception ignored) {
            return null;
        }
    }

    // v4.79: mini-player download state — 0 hidden, 1 ready (⬇),
    // 2 already queued (⏳). Room sessions only: a library session
    // is already a local file, so there is nothing to download.
    // The title must resolve too, since DownloadManager needs it.
    // Downloaded wins over queued, so a file that landed hides the
    // button even before Room's rescan prunes the dlQueued entry
    // (the v4.01 queued-mark class). Only single-file exists()
    // checks here, never a directory scan, so the 1s strip tick
    // stays cheap (the v1.57 jank rule).
    public static final int STRIP_DL_HIDDEN = 0;
    public static final int STRIP_DL_READY = 1;
    public static final int STRIP_DL_QUEUED = 2;

    // v4.79: Wi-Fi check for the strip download, mirroring Room's
    // isWifi (capabilities first, deprecated active-network info
    // only as a fallback) so the data-saver rule matches the gate.
    public static boolean stripIsWifi(Context ctx) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
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
                            // false above and never scans, so it can
                            // never inherit a stray Wi-Fi object as a
                            // false Wi-Fi verdict that would silently
                            // lift the data-saver gate.
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
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected()
                    && info.getType() == android.net.ConnectivityManager.TYPE_WIFI;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static java.io.File stripDownloadsFile(String name) {
        return new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma/" + name);
    }

    private static boolean stripFileClaimedByOther(android.content.SharedPreferences sp,
            String filename, String url) {
        try {
            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
            java.util.Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!k.equals(url) && filename.equals(map.optString(k, ""))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // v4.79: candidate on-disk names for a URL, mirroring Room's
    // fileNameCandidates (raw basename, stripped, %XX-decoded).
    // v12.16: the enqueue destination form appended last (stripped +
    // ".mp3" when it lacks .mp3) — a wrapper-URL live job lands under
    // that name; see Room fileNameCandidates.
    private static String[] stripFileNameCandidates(String url) {
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

    // v4.79: the filename a new strip download lands under,
    // mirroring Room's downloadFileNameFor (mapped name wins, else
    // the stripped basename with a guaranteed .mp3, plus the 8-hex
    // MD5 suffix when another talk already owns that basename).
    public static String stripDownloadFileName(android.content.SharedPreferences sp, String url) {
        try {
            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
            if (map.has(url)) return map.getString(url);
        } catch (Exception ignored) {}
        String base = url.substring(url.lastIndexOf('/') + 1);
        int q = base.indexOf('?');
        if (q >= 0) base = base.substring(0, q);
        int hh = base.indexOf('#');
        if (hh >= 0) base = base.substring(0, hh);
        if (!base.toLowerCase().endsWith(".mp3")) base = base + ".mp3";
        // v8.40: mirror of Room's fix — the claim alone forces the
        // hash suffix, no exists() conjunct. A file deleted outside
        // the app leaves its dlMap claim surviving (in-app deletes
        // prune it, outside deletes do not, v8.30), so a same-
        // basename strip download that found no file on disk took
        // the plain name and created a dual claim: once its file
        // landed, the old talk showed a false ⬇ and played the
        // new talk's audio. Same harmless-over-suffix trade as
        // Room: a dead claim costs a unique filename, a dual
        // claim costs the wrong audio.
        if (stripFileClaimedByOther(sp, base, url)) {
            String hash = "";
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] d = md.digest(url.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i]));
                hash = sb.toString();
            } catch (Exception ignored) {
                hash = String.format("%08x", url.hashCode());
            }
            return base.substring(0, base.length() - 4) + "_" + hash + base.substring(base.length() - 4);
        }
        return base;
    }

    public static int stripDownloadState(Context ctx, android.content.SharedPreferences sp) {
        if (sp == null) return STRIP_DL_HIDDEN;
        try {
            if (!"room".equals(sp.getString("lastKind", ""))) return STRIP_DL_HIDDEN;
            String url = stripFavUrl(ctx, sp);
            if (url == null) return STRIP_DL_HIDDEN;
            if (sp.getString("lastTitle", "").length() == 0) return STRIP_DL_HIDDEN;
            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
            // v11.74: a talk still downloading is NOT downloaded yet —
            // the last surface of the v11.55 (library list) / v11.56
            // (Room rows) / v11.58 (resume strips) partial-file class.
            // DownloadManager writes straight into Downloads/Dhamma,
            // so the old exists()-first order read a partial file as
            // downloaded and never reached the queued ⏳ branch below:
            // the moment a Room download started running, the ⬇ button
            // vanished instead of turning into ⏳. A live job's
            // filename now reads ⏳ before any exists() check, mapped
            // name and unclaimed candidates alike, exactly like
            // stripResumeDownloadMark; a DM failure fails open to the
            // old order. The 500ms Room poll already parses this JSON
            // and stats files every tick, so one DM query is the same
            // cost class as the sibling's 1s strip tick.
            java.util.Set<String> inProg = stripInProgressDlFilenames(ctx, sp);
            if (map.has(url) && inProg.contains(map.getString(url)))
                return STRIP_DL_QUEUED;
            for (String name : stripFileNameCandidates(url)) {
                if (!stripFileClaimedByOther(sp, name, url)
                        && inProg.contains(name))
                    return STRIP_DL_QUEUED;
            }
            if (map.has(url) && stripDownloadsFile(map.getString(url)).exists())
                return STRIP_DL_HIDDEN;
            for (String name : stripFileNameCandidates(url)) {
                if (!stripFileClaimedByOther(sp, name, url)
                        && stripDownloadsFile(name).exists())
                    return STRIP_DL_HIDDEN;
            }
            org.json.JSONArray queued = new org.json.JSONArray(sp.getString("dlQueued", "[]"));
            for (int i = 0; i < queued.length(); i++) {
                if (url.equals(queued.optString(i))) return STRIP_DL_QUEUED;
            }
            return STRIP_DL_READY;
        } catch (Exception ignored) {
            return STRIP_DL_HIDDEN;
        }
    }

    // v7.94: the dead-session resume strip's per-talk download
    // mark — the one now-playing surface with no download state
    // at all. The live strip carries the global " ⏳ N" (v4.27)
    // and the drawer rows carry a per-talk ⬇/⏳ (v4.32), but
    // syncNowStrip's resume branches return before either, so a
    // ↺ strip never said whether resuming streams or plays
    // offline — the exact question a data-saver listener asks
    // before tapping. Positive-only, mirroring
    // stripDownloadState's downloaded branch (dlMap entry, then
    // unclaimed candidate filenames) so a stale or missing map
    // reads as no mark, never a guessed ⬇; queued wins over
    // nothing but loses to downloaded, the same order as the
    // drawer rows. Single-file exists() checks only, never a
    // directory scan, so the 1s strip tick stays cheap (the
    // v1.57 jank rule). Callers pass the stream URL; the
    // library resume branch never calls this (its file is
    // proven present by its own match rule, so it marks ⬇
    // directly).
    // v11.58: filenames of this app's in-progress downloads
    // (RUNNING/PAUSED/PENDING), the exact v11.55/v11.56 rule
    // shared here so the strips can use it too: destination
    // under Downloads/Dhamma via the percent-decoded local
    // URI, or the dlMap filename when the job exposes no
    // destination yet (a Wi-Fi-waiting PENDING job). A DM
    // query failure fails open (empty set = the old
    // behaviour), never an empty shelf; a null context
    // reads as no live job, same fail-open.
    public static java.util.Set<String> stripInProgressDlFilenames(
            Context ctx, android.content.SharedPreferences sp) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        if (ctx == null) return out;
        try {
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
                    ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            // v12.70: filter in the provider — the v11.76 Talks shape; every
            // resume branch rides this on the 1s strip tick (and stripDownloadState
            // on the Room's 500ms poll) and it used to pull every download on the
            // main thread and discard the finished ones in Java; the status check
            // below stays as a guard.
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
        } catch (Exception ignored) {}
        return out;
    }

    public static String stripResumeDownloadMark(
            Context ctx, android.content.SharedPreferences sp, String url) {
        if (sp == null || url == null || url.length() == 0) return "";
        try {
            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
            // v11.58: a talk still downloading is NOT downloaded
            // yet — the remaining surface of the v11.55 (library
            // list) / v11.56 (Room rows) / v11.57 (chooser rows)
            // partial-file class, on the resume strips.
            // DownloadManager writes straight into
            // Downloads/Dhamma, so the old exists()-first order
            // read a partial file as ⬇ and never reached the
            // queued ⏳ branch below: a ↺ strip after a reboot
            // or system kill (the session dead, the DM job
            // still running) claimed the talk was offline.
            // A live job's filename now reads ⏳ before any
            // exists() check, mapped name and unclaimed
            // candidates alike; a DM failure fails open to
            // the old order. The 1s strip tick already pays
            // one DM query on the live branch (the v4.27
            // " ⏳ N" count), so this adds no new tick cost
            // class on the resume branch.
            java.util.Set<String> inProg = stripInProgressDlFilenames(ctx, sp);
            if (map.has(url) && inProg.contains(map.getString(url)))
                return " \u23F3";
            for (String name : stripFileNameCandidates(url)) {
                if (!stripFileClaimedByOther(sp, name, url)
                        && inProg.contains(name))
                    return " \u23F3";
            }
            if (map.has(url) && stripDownloadsFile(map.getString(url)).exists())
                return " \u2B07";
            for (String name : stripFileNameCandidates(url)) {
                if (!stripFileClaimedByOther(sp, name, url)
                        && stripDownloadsFile(name).exists())
                    return " \u2B07";
            }
            org.json.JSONArray queued = new org.json.JSONArray(sp.getString("dlQueued", "[]"));
            for (int i = 0; i < queued.length(); i++) {
                if (url.equals(queued.optString(i))) return " \u23F3";
            }
            return "";
        } catch (Exception ignored) {
            return "";
        }
    }

    // v4.79: enqueue the strip talk's download exactly like Room's
    // data-gate Wi-Fi path (same destination, same dlMap record,
    // same dlQueued mark so the strip shows ⏳ until it lands and
    // Room's rescan prunes it). Returns false when nothing could
    // be enqueued; the caller toasts the failure.
    public static boolean stripEnqueueDownload(Context ctx,
            android.content.SharedPreferences sp, boolean wifiOnly) {
        try {
            String url = stripFavUrl(ctx, sp);
            String title = sp.getString("lastTitle", "");
            if (url == null || title.length() == 0) return false;
            String file = stripDownloadFileName(sp, url);
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            android.app.DownloadManager.Request req =
                    new android.app.DownloadManager.Request(android.net.Uri.parse(url));
            req.setTitle(title);
            req.setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (wifiOnly)
                req.setAllowedNetworkTypes(android.app.DownloadManager.Request.NETWORK_WIFI);
            req.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, "Dhamma/" + file);
            dm.enqueue(req);
            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
            map.put(url, file);
            // v5.45: record this download's sayadaw beside dlMap
            // (see stripLibSayadaw above) — the strip enqueues the
            // sounding Room talk, whose sayadaw is lastName, so a
            // later library session can name it. Guarded to Room
            // sessions: a stale lastName must never attach to a
            // library-enqueued file. Older downloads simply have
            // no entry, and every reader falls back.
            String sdMapStr = null;
            if ("room".equals(sp.getString("lastKind", ""))) {
                String ln = sp.getString("lastName", "");
                if (ln.length() > 0) {
                    try {
                        org.json.JSONObject sm = new org.json.JSONObject(sp.getString("dlSayadaw", "{}"));
                        sm.put(url, ln);
                        sdMapStr = sm.toString();
                    } catch (Exception ignored) {}
                }
            }
            org.json.JSONArray queued = new org.json.JSONArray(sp.getString("dlQueued", "[]"));
            boolean found = false;
            for (int i = 0; i < queued.length(); i++) {
                if (url.equals(queued.optString(i))) { found = true; break; }
            }
            if (!found) queued.put(url);
            sp.edit().putString("dlMap", map.toString())
                    .putString("dlQueued", queued.toString()).apply();
            if (sdMapStr != null) sp.edit().putString("dlSayadaw", sdMapStr).apply();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    // v4.80: the mini-player's sleep timer — the strips showed the
    // live countdown (v4.72) but could not arm or cancel one, so
    // setting a timer meant leaving for Settings. The picker is
    // Settings' own (v4.14: 0/15/30/60, current pref checked); the
    // choice is written to the same pref, so the Room/library sync
    // and the next talk agree with the strip, and the live talk
    // picks it up at once through ACTION_SLEEP with no bind.
    public static void stripSleepPick(final android.app.Activity act) {
        if (act == null) return;
        final String off = Lang.t(act, "off");
        final String min = Lang.t(act, "sleep_min");
        // v8.57: 5 minutes restored — the v1.56 cycle (ပိတ်→5→15→30→60)
        // had it, but the v4.14/v4.80 pickers that replaced the cycle
        // dropped it, so no current UI could pick 5 minutes at all,
        // and a stored 5-minute pref (from the old cycle) read as
        // "off" checked here while still arming a 5-minute timer on
        // sync. Same picker, one more item; the checked loop below
        // already walks vals, so no other change is needed.
        final int[] vals = {0, 5, 15, 30, 60};
        // v4.82: one-shot "stop at end of talk" as the last item —
        // it arms the live service without touching the minute pref,
        // so it reads checked only while actually armed.
        final String endLabel = Lang.t(act, "sleep_end");
        final String[] items = {off, "5 " + min, "15 " + min, "30 " + min, "60 " + min, endLabel};
        int cur = Prefs.sleepMinutes(act);
        int checked = AudioService.liveSleepEndOfTalk ? items.length - 1 : 0;
        if (!AudioService.liveSleepEndOfTalk)
            for (int i = 0; i < vals.length; i++) if (vals[i] == cur) checked = i;
        new android.app.AlertDialog.Builder(act)
                .setTitle(Lang.t(act, "sleep"))
                .setSingleChoiceItems(items, checked, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        d.dismiss();
                        if (which == items.length - 1) {
                            // v10.43: bound-trackless stop — liveSession stays true but the mirror is false, so a trackless pick toasts sleep_end_need instead of claiming an armed one-shot the handler drops.
                            if (AudioService.liveSession && AudioService.liveHasTrack) {
                                android.content.Intent i = new android.content.Intent(act, AudioService.class);
                                i.setAction(AudioService.ACTION_SLEEP_END);
                                act.startService(i);
                                android.widget.Toast.makeText(act, Lang.t(act, "sleep") + ": " + endLabel, android.widget.Toast.LENGTH_SHORT).show();
                            } else {
                                android.widget.Toast.makeText(act, Lang.t(act, "sleep_end_need"), android.widget.Toast.LENGTH_SHORT).show();
                            }
                            return;
                        }
                        int mins = vals[which];
                        Prefs.setSleepMinutes(act, mins);
                        applySleepPick(act, mins);
                        String msg = mins == 0 ? Lang.t(act, "sleep") + ": " + off
                                : Lang.t(act, "sleep") + ": " + mins + " " + min;
                        android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(Lang.t(act, "dlg_close"), null)
                .show();
    }

    // v4.80: strip sleep long-press — cancels at once, mirroring
    // the Settings sleep button's long-press (v4.15): one gesture
    // instead of picker + off, same pref + live apply as a pick.
    public static void stripSleepCancel(final android.app.Activity act) {
        if (act == null) return;
        Prefs.setSleepMinutes(act, 0);
        stripApplySleep(act);
        cancelEndOfTalkSleep(act); // v4.82: see helper below
        android.widget.Toast.makeText(act, Lang.t(act, "sleep") + ": " + Lang.t(act, "off"),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // v4.82: explicit one-shot cancel for strip long-press and the
    // Settings sleep long-press — fires only while a session is
    // live (like stripApplySleep), because the pref guard in the
    // service cannot see a cancel when the pref never changed.
    public static void cancelEndOfTalkSleep(final android.app.Activity act) {
        if (act == null || !AudioService.liveSession) return;
        try {
            android.content.Intent i = new android.content.Intent(act, AudioService.class);
            i.setAction(AudioService.ACTION_SLEEP_END_CANCEL);
            act.startService(i);
        } catch (Exception ignored) {}
    }

    // v8.56: push an EXPLICIT picker minute choice to the live talk
    // (strip picker + Settings picker). No bind, same liveSession
    // guard as stripApplySleep, but a different intent: ACTION_
    // SLEEP_PICK applies the minutes directly and clears an armed
    // one-shot even when the pick equals the pref it was armed
    // with, where the routine ACTION_SLEEP sync would leave the
    // one-shot armed and the pick unapplied (see AudioService).
    public static void applySleepPick(android.app.Activity act, int mins) {
        if (act == null || !AudioService.liveSession) return;
        try {
            android.content.Intent i = new android.content.Intent(act, AudioService.class);
            i.setAction(AudioService.ACTION_SLEEP_PICK);
            i.putExtra("mins", mins);
            act.startService(i);
        } catch (Exception ignored) {}
    }

    // v4.80: push the strip's sleep choice to the live talk. No
    // bind, and never starts a stopped service: liveSession is
    // false for a dead strip, and the callers hide the button
    // there, so this only fires while a session is loaded.
    private static void stripApplySleep(android.app.Activity act) {
        if (!AudioService.liveSession) return;
        try {
            android.content.Intent i = new android.content.Intent(act, AudioService.class);
            i.setAction(AudioService.ACTION_SLEEP);
            act.startService(i);
        } catch (Exception ignored) {}
    }

    public static String speedLabel(float s) {
        if (s == 1.0f) return "1x";
        if (s == 1.25f) return "1.25x";
        if (s == 1.5f) return "1.5x";
        if (s == 2.0f) return "2x";
        if (s == 0.75f) return "0.75x";
        String t = String.valueOf(s);
        if (t.endsWith(".0")) t = t.substring(0, t.length() - 2);
        return t + "x";
    }

    // v7.49: resume-cue clock — the Room (v1.47), the service card
    // (v1.72) and the library row (v3.93) all read H:MM:SS once a
    // talk passes an hour, but the mini-strip resume cue on all
    // four strip screens still formatted plain minutes, so a
    // resumed hour-long talk read "65:23" instead of "1:05:23".
    // One helper, used by all eight resume branches (library +
    // Room on Main, Talks, Downloaded, Settings), so the cue can
    // never drift from the app's clock rule again.
    public static String stripResumeTime(int sec) {
        if (sec >= 3600)
            return (sec / 3600) + ":" + String.format("%02d", (sec % 3600) / 60)
                    + ":" + String.format("%02d", sec % 60);
        return (sec / 60) + ":" + (sec % 60 < 10 ? "0" + (sec % 60) : "" + (sec % 60));
    }

}
