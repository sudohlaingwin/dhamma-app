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

    // v15.29 (slice 1815): shared-element open — the mini-player
    // strip photo grows into the Room header portrait (the Spotify /
    // YouTube Music / Apple Music now-playing open). The strip and
    // the Room load the SAME sayadaw portrait through ImageLoader
    // (memory-cache hit, set synchronously in load()), so the
    // framework snapshot and the Room's real view show the same
    // face and there is no pop at the handover. The transition
    // name below must match the android:transitionName on
    // nowStripPhoto (all six strip layouts) and roomSayadawPhoto
    // (activity_room.xml) — the sim pins the parity.
    // Falls back to plain go() when the shared view is missing,
    // hidden, not laid out yet, or detached (a resume strip with
    // no photo, a tap mid-rebind) — a shared open must never be
    // the reason a Room fails to open. Never calls
    // overridePendingTransition: the override would replace the
    // window animation the shared-element transition needs, so a
    // shared open gets no nav_in/nav_out — the Room's own window
    // enter Fade (set in RoomActivity when sharedPhoto) owns the
    // rest of the screen. A startActivity throw after the options
    // are built falls back to go() so the Room still opens.
    public static final String SHARED_PHOTO_NAME = "roomSayadawPhoto";

    // v15.30 (slice 1816): second shared-element pair — the Main
    // grid photo grows into the Talks chooser header portrait.
    // Same model as the strip->Room pair above (the same portrait
    // through ImageLoader, memory-cache hit, set synchronously),
    // but a different transition name: the grid photo and the
    // Talks header portrait share "talksSayadawPhoto", while the
    // strips and the Room share "roomSayadawPhoto". Two names
    // because both pairs can be live in one back stack (a grid
    // under a chooser under a Room) and a name must identify one
    // pair. The sim pins the parity.
    public static final String SHARED_TALKS_PHOTO_NAME = "talksSayadawPhoto";

    // v15.30 (slice 1817): per-tap unique shared names — a shared-
    // element transition needs a UNIQUE transition name: the
    // framework maps shared elements by name, so an XML name shared
    // by every grid row leaves it no way to know which row's photo
    // flies (it takes the last row in traversal order — the wrong
    // face — and the return has nowhere to land). The grid therefore
    // never shares under a static name: each tap builds its own name
    // from the pair base + the sayadaw's name (the stable identity
    // across filtering and rebinding — a position would shift) and
    // carries it in this extra, so the destination portrait takes
    // the same name in onCreate and the return finds the same card.
    // A null/empty key falls back to the bare base (never a crash);
    // a strip open carries no extra and the Room keeps its XML name.
    public static final String SHARED_PHOTO_NAME_EXTRA = "sharedPhotoName";

    public static String sharedPhotoNameFor(String base, String key) {
        if (base == null) base = "";
        if (key == null || key.length() == 0) return base;
        return base + "_" + key;
    }

    public static void goShared(Activity a, Intent i, View shared) {
        goSharedNamed(a, i, shared, SHARED_PHOTO_NAME);
    }

    public static void goSharedNamed(Activity a, Intent i, View shared, String name) {
        if (a == null || i == null) { return; }
        if (shared == null || shared.getVisibility() != View.VISIBLE
                || shared.getWidth() == 0 || shared.getHeight() == 0
                || !shared.isAttachedToWindow()) {
            go(a, i);
            return;
        }
        // v15.30 (slice 1817): the exit-side lookup is by NAME, so
        // the source view must carry exactly the name passed here —
        // a just-set name is read at transition start, which is what
        // makes the per-tap unique names work. Set it here, in the
        // one funnel every shared open uses, so no caller can pass a
        // name its view does not carry. A strip tap passes the static
        // name its photo already carries in XML, so this is a no-op
        // there; a grid tap passes its per-tap name.
        shared.setTransitionName(name);
        i.putExtra("sharedPhoto", true);
        try {
            android.app.ActivityOptions opts =
                    android.app.ActivityOptions.makeSceneTransitionAnimation(
                            a, shared, name);
            a.startActivity(i, opts.toBundle());
        } catch (Exception e) {
            go(a, i);
        }
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
        // v13.32 (M9): a null first view used to NPE inside the
        // posted Runnable — views[0].post() + views[0].getResources()
        // run before the per-element null check in the loop, and
        // callers pass findViewById results straight in. With no
        // anchor to post on the entrance is skipped.
        if (views.length == 0 || views[0] == null) return;
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

    // v15.00 (slice 1758): premium fade-out-then-remove — the mirror
    // of fadeOut for a view that LEAVES its parent instead of being
    // hidden. Fades to 0 over durationMs, then removes the view from
    // its parent and restores alpha to 1, so a re-add starts from a
    // clean state. Gen-guarded exactly like fadeOut (v3.62): a
    // superseded fade never removes a live view, and a view already
    // detached at end time is a no-op (its parent is null). Used by
    // the Main favourites shelf: the removed chip fades out in its
    // old slot while the rebuilt chips sit beside it, then leaves —
    // the layout shift lands only at the end, when the chip is
    // already invisible. Visual only.
    public static void fadeOutAndRemove(final View v, long durationMs) {
        fadeOutAndRemove(v, durationMs, null);
    }

    // v15.03 (slice 1761): fade-out-then-remove with a removal
    // callback — the same gen-guarded fade as the 2-arg form, plus
    // afterRemove, run only when the end action really removed the
    // view from a parent. A superseding rebuild landing mid-fade
    // detaches the view first, so its parent is null at end time
    // and the callback is skipped (the fresh layout owns its own
    // entrance then); a superseded fade's gen mismatch skips it
    // too. Used by the Main favourites shelf: the rank-close glide
    // must start at the removal, not at the fade, so it rides the
    // end action. Visual only.
    public static void fadeOutAndRemove(final View v, long durationMs,
            final Runnable afterRemove) {
        if (v == null) return;
        final int gen = bumpFadeGen(v);
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(durationMs)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        Integer now = fadeGens.get(v);
                        if (now == null || now != gen) return;
                        android.view.ViewParent parent = v.getParent();
                        boolean removed03 = false;
                        if (parent instanceof android.view.ViewGroup) {
                            ((android.view.ViewGroup) parent).removeView(v);
                            removed03 = true;
                        }
                        v.setAlpha(1f);
                        if (removed03 && afterRemove != null) afterRemove.run();
                    }
                }).start();
    }

    // v15.03 (slice 1761): premium rank-close glide — the trailing
    // sibling of a removed view used to jump one slot in a single
    // frame when the removal landed. Park it one slot right of its
    // new layout position (translationX += shiftPx, so its visual
    // position never moves) and animate translationX to 0 over
    // durationMs (DecelerateInterpolator, the fade rhythm), so it
    // glides home instead of jumping. A second removal landing
    // while a glide is still in flight cancels ONLY the recorded
    // glide animator and re-parks from the sibling's current
    // position, so the sibling keeps gliding from where it
    // visually is — and an alpha fade on the same view (the
    // v15.02 parked chip's delayed melt-in) is never touched,
    // because this helper never calls animate().cancel().
    // Visual only.
    private static java.util.WeakHashMap<View, android.animation.ObjectAnimator> glideAnims =
            new java.util.WeakHashMap<View, android.animation.ObjectAnimator>();
    public static void glideHome(final View v, float shiftPx, long durationMs) {
        if (v == null) return;
        android.animation.ObjectAnimator prev = glideAnims.get(v);
        if (prev != null) prev.cancel();
        v.setTranslationX(v.getTranslationX() + shiftPx);
        android.animation.ObjectAnimator glide =
                android.animation.ObjectAnimator.ofFloat(v, "translationX", 0f);
        glide.setDuration(durationMs);
        glide.setInterpolator(new DecelerateInterpolator());
        glideAnims.put(v, glide);
        glide.start();
    }

    // v15.14 (slice 1773): premium rank-close glide, vertical —
    // the v15.03 (slice 1761) shelf shape transposed to the
    // Library queue-preview box. A preview row leaving the box
    // used to make every trailing row jump one slot up in a
    // single frame when the removal landed; park each trailing
    // row one slot down (translationY += shiftPx, so its visual
    // position never moves) and animate translationY to 0 over
    // durationMs (DecelerateInterpolator, the fade rhythm), so it
    // glides home instead of jumping. Recorded in its own
    // WeakHashMap (glideAnimsY), never the shelf's glideAnims:
    // a second removal landing while a glide is still in flight
    // cancels ONLY the recorded Y glide and re-parks from the
    // sibling's current position, so the sibling keeps gliding
    // from where it visually is — and an alpha fade on the same
    // view (the v15.12 diff-melt) is never touched, because this
    // helper never calls animate().cancel(). No glideHomeYFrom /
    // cancelGlideY: the box rebuilds on the 1s tick and a 250ms
    // glide always completes between ticks, so no carry case can
    // land (the shelf's v15.05/v15.08 cases cannot land here).
    // Visual only.
    private static java.util.WeakHashMap<View, android.animation.ObjectAnimator> glideAnimsY =
            new java.util.WeakHashMap<View, android.animation.ObjectAnimator>();
    public static void glideHomeY(final View v, float shiftPx, long durationMs) {
        if (v == null) return;
        android.animation.ObjectAnimator prev = glideAnimsY.get(v);
        if (prev != null) prev.cancel();
        v.setTranslationY(v.getTranslationY() + shiftPx);
        android.animation.ObjectAnimator glide =
                android.animation.ObjectAnimator.ofFloat(v, "translationY", 0f);
        glide.setDuration(durationMs);
        glide.setInterpolator(new DecelerateInterpolator());
        glideAnimsY.put(v, glide);
        glide.start();
    }

    // v15.08 (slice 1767): premium rank-close glide from an explicit
    // start — the carry half of glideHome above. A rebuild landing
    // mid-glide replaces the gliding view with a fresh one at home,
    // so the fresh chip is parked at the captured translationX and
    // glides home over the exact remainder instead of snapping. The
    // animator is recorded in glideAnims exactly like glideHome's,
    // so a later departure's glideHome cancels it and re-parks from
    // the current position (the v15.03 stacking rule) and an alpha
    // fade on the same view is never touched. Visual only.
    public static void glideHomeFrom(final View v, float fromX, long durationMs) {
        if (v == null) return;
        android.animation.ObjectAnimator prev = glideAnims.get(v);
        if (prev != null) prev.cancel();
        v.setTranslationX(fromX);
        android.animation.ObjectAnimator glide =
                android.animation.ObjectAnimator.ofFloat(v, "translationX", 0f);
        glide.setDuration(durationMs);
        glide.setInterpolator(new DecelerateInterpolator());
        glideAnims.put(v, glide);
        glide.start();
    }

    // v15.08 (slice 1767): cancel a view's recorded rank-close glide
    // without touching anything else — a departing chip re-seated
    // mid-glide must stop sliding before its translationX is reset
    // to 0, or the still-running animator would undo the reset on
    // its next frame. A view with no recorded glide is a no-op; a
    // finished glide cancels harmlessly. Visual only.
    public static void cancelGlide(final View v) {
        if (v == null) return;
        android.animation.ObjectAnimator prev = glideAnims.get(v);
        if (prev != null) prev.cancel();
    }

    // v14.14 (slice 1668): mini-player strip slide — the strip on Main /
    // Talks / Settings / Downloaded / History / Playlist used to pop in
    // and out instantly on every sync-tick visibility edge. It now
    // slides up from below + fades in on show, and slides down + fades
    // out on hide. Edge-only: a call matching the current settled
    // visibility is a no-op, so the 1s sync tick never re-animates a
    // settled strip. A direction flip cancels the in-flight animation
    // and bumps the fade gen, so a stale hide end-action can never GONE
    // a strip a show just brought back (same gen-guard family as
    // fadeOut). Visual only; conditions at every call site untouched.
    public static void showStrip(final View strip) {
        if (strip == null) return;
        if (strip.getVisibility() == View.VISIBLE && strip.getAlpha() >= 0.999f
                && Math.abs(strip.getTranslationY()) <= 0.5f) return;
        final boolean wasGone = strip.getVisibility() != View.VISIBLE;
        bumpFadeGen(strip);
        strip.animate().cancel();
        strip.setVisibility(View.VISIBLE);
        strip.post(new Runnable() {
            @Override public void run() {
                float h = strip.getHeight() > 0 ? strip.getHeight()
                        : 72f * strip.getResources().getDisplayMetrics().density;
                if (wasGone) {
                    strip.setAlpha(0f);
                    strip.setTranslationY(h);
                }
                strip.animate().alpha(1f).translationY(0f).setDuration(280)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
        });
    }

    public static void hideStrip(final View strip) {
        if (strip == null) return;
        if (strip.getVisibility() != View.VISIBLE) return;
        final int gen = bumpFadeGen(strip);
        strip.animate().cancel();
        float h = strip.getHeight() > 0 ? strip.getHeight()
                : 72f * strip.getResources().getDisplayMetrics().density;
        strip.animate().alpha(0f).translationY(h).setDuration(240)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        Integer now = fadeGens.get(strip);
                        if (now == null || now != gen) return;
                        strip.setVisibility(View.GONE);
                        strip.setAlpha(1f);
                        strip.setTranslationY(0f);
                    }
                }).start();
    }

    // v14.16 (slice 1670): the sounding rings used to snap on/off —
    // every ring site swapped its background instantly
    // (setBackgroundResource / setBackground(null)), so a tap, a
    // natural end or a failed stream flashed the gold (or red) ring
    // in or out in one frame while everything around it moves
    // (v14.14 strip slide, v14.15 shelf pour). The ring now
    // crossfades instead: a TransitionDrawable blends the old ring
    // out and the new one in over 250ms (crossFade on, so gold ->
    // red melts instead of double-painting). The recorded target
    // lives in a WeakHashMap (same model as fadeGens/morphGens),
    // so a repeated call for the state already showing — the 1s
    // tick re-entering a settled ring — is a no-op and never
    // restarts the fade; a mid-fade re-target starts from the
    // recorded state. 0 means "no ring" (transparent layer). The
    // photo inside the frame is untouched — only the frame's
    // background crossfades, so the portrait never blinks. The
    // ▶/⚠ marks stay instant text swaps by design: they are a
    // 2-char prefix inside the row label, and fading the whole
    // label to ease them would flash the row; the ring carries
    // the motion. Widget (RemoteViews) and shelf chips (fresh
    // views each pour) have no previous state to fade from and
    // keep their instant set.
    private static java.util.WeakHashMap<View, Integer> ringRes =
            new java.util.WeakHashMap<View, Integer>();
    public static void crossfadeRing(final View frame, int newRes) {
        if (frame == null) return;
        Integer cur = ringRes.get(frame);
        int curRes = cur == null ? 0 : cur.intValue();
        if (curRes == newRes) return;
        ringRes.put(frame, newRes);
        android.content.res.Resources res = frame.getResources();
        android.graphics.drawable.Drawable from = curRes == 0
                ? new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                : res.getDrawable(curRes, null);
        android.graphics.drawable.Drawable to = newRes == 0
                ? new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                : res.getDrawable(newRes, null);
        android.graphics.drawable.TransitionDrawable td =
                new android.graphics.drawable.TransitionDrawable(
                        new android.graphics.drawable.Drawable[]{from, to});
        td.setCrossFadeEnabled(true);
        frame.setBackground(td);
        td.startTransition(250);
    }

    // v14.50 (slice 1707): record a frame's current ring drawable
    // without touching the view — the favourites-shelf build sets
    // its ring with setBackgroundResource (fresh views, no fade),
    // and the in-place sounding update crossfades from the recorded
    // target, so the seed keeps the two in agreement.
    public static void seedRing(final View frame, int res) {
        if (frame == null) return;
        ringRes.put(frame, res);
    }

    // v14.31 (slice 1686): the sounding card's gold tile used to snap
    // on/off — the 1s tick rebinds the grid exactly when the sounding
    // name / ended / failed / buffering flips (v11.15/v12.38/v14.05/
    // v14.06), and getView swapped the card background in one frame
    // (setBackgroundResource: card_bg <-> main_card_playing), so a
    // talk ending, stopping or switching sayadaw while Main sat on
    // top flashed the gold tile in or out while everything around it
    // moves (name breath, strip crossfades, shelf re-pour). The tile
    // now crossfades the same way crossfadeRing does above: a
    // TransitionDrawable blends the old tile out and the new one in
    // over 250ms (crossFade on, so gold -> plain melts instead of
    // double-painting), with the recorded target in a WeakHashMap, so
    // a repeated call for the state already showing is a no-op and
    // never restarts the fade, and a mid-fade re-target starts from
    // the recorded state. One difference from the ring: a card has
    // NO "no tile" state — a view with no recorded res is seeded
    // instantly (setBackgroundResource, no fade), so a fresh inflate
    // and a scroll recycle never melt in from transparent; only a
    // real change of a known tile crossfades. The ▶ prefix + gold
    // name stay instant text swaps by the v14.16 settled decision:
    // they are a 2-char prefix inside the label, and fading the
    // whole label to ease them would flash the row; the tile carries
    // the motion. The press squash (scale), entrance pour (alpha +
    // scale view Animation) and breath (name view) all live on other
    // views/channels — the tile is the card root's background alone,
    // and a TransitionDrawable is not a ViewPropertyAnimator, so
    // getView's animate().cancel() never kills a tile fade in flight.
    private static java.util.WeakHashMap<View, Integer> tileRes =
            new java.util.WeakHashMap<View, Integer>();
    public static void crossfadeCardTile(final View card, int newRes) {
        if (card == null) return;
        Integer cur = tileRes.get(card);
        if (cur == null) {
            tileRes.put(card, newRes);
            card.setBackgroundResource(newRes);
            return;
        }
        int curRes = cur.intValue();
        if (curRes == newRes) return;
        tileRes.put(card, newRes);
        android.content.res.Resources res = card.getResources();
        android.graphics.drawable.Drawable from = res.getDrawable(curRes, null);
        android.graphics.drawable.Drawable to = res.getDrawable(newRes, null);
        android.graphics.drawable.TransitionDrawable td =
                new android.graphics.drawable.TransitionDrawable(
                        new android.graphics.drawable.Drawable[]{from, to});
        td.setCrossFadeEnabled(true);
        card.setBackground(td);
        td.startTransition(250);
    }

    // v15.25 (slice 1788): the listened-progress hairline melts in/out
    // instead of popping — the v14.31 card-crossfade fix, now on the
    // gold line: the 1s tick rebinds the chooser/Library rows exactly
    // when a talk ends, stops or auto-advances, and getView swapped
    // the hairTrack in one frame (setVisibility VISIBLE <-> GONE), so
    // a finished talk's gold line flashed in under its row while the
    // gold card beside it crossfades (v15.24) and the strip and clock
    // melt. The track now melts over the same 250ms,
    // DecelerateInterpolator, through the gen-guarded fadeIn / fadeOut
    // above: fadeIn from 0 for an appearing line, fadeOut to 0 then
    // GONE (alpha restored to 1) for a leaving one. Recorded shown
    // state in a WeakHashMap, like the tile map: a repeated call for
    // the state already showing is a no-op and never restarts the
    // fade, a fresh view seeds instantly (never melts in from
    // transparent on a scroll recycle), and a mid-fade re-target
    // (fadeIn/fadeOut bump the same fade gen) starts from the alpha
    // the view actually has. The fill/space weight split stays an
    // instant layout update — it is a progress value, not an
    // appearance change. The row's own pour (view Animation), press
    // squash (scale) and delete fade (row alpha) all live on other
    // views/channels — the hairline is a child view whose alpha is
    // its own. v15.26 (slice 1789): the Room drawer hairlines ride
    // Fx.crossfadeHairline now — the drawer's top-of-bind reset moved
    // onto the stale-slot return (RoomActivity), so both drawer paints
    // are the sole writers on a content bind and a showing line never
    // re-melts on a rebind.
    private static java.util.WeakHashMap<View, Boolean> hairShown =
            new java.util.WeakHashMap<View, Boolean>();
    public static void crossfadeHairline(final View track, boolean show) {
        if (track == null) return;
        Boolean cur = hairShown.get(track);
        if (cur == null) {
            hairShown.put(track, Boolean.valueOf(show));
            track.setVisibility(show ? View.VISIBLE : View.GONE);
            track.setAlpha(1f);
            return;
        }
        if (cur.booleanValue() == show) return;
        hairShown.put(track, Boolean.valueOf(show));
        if (show) {
            track.setVisibility(View.VISIBLE);
            fadeIn(track, 250);
        } else {
            fadeOut(track, 250);
        }
    }

    // v15.26 (slice 1790): the duration clock melts in/out instead
    // of popping — the v15.24 card-crossfade / v15.25 hairline fix,
    // now on the clock beside them: the 1s tick rebinds the chooser
    // rows exactly when a duration lands in the cache (a talk played
    // or downloaded stores its length, v13.96) and the 500ms poll
    // rebinds the drawer, and both paints swapped the talkDur label
    // in one frame (setVisibility), so a fresh length flashed onto
    // its row while the gold card beside it crossfades (v15.24) and
    // the hairline melts (v15.25). Fx.crossfadeClock (250ms,
    // DecelerateInterpolator, recorded shown state, instant seed on a
    // fresh view) owns the label from here; the clock text/size/colour
    // reset on every bind, instantly, at the call sites — the helper
    // owns visibility + alpha only, never the text. A repeated call
    // for the state already showing is a no-op and never restarts
    // the fade (a duration-text-only rebind never re-melts), and a
    // mid-fade re-target starts from the alpha the view actually has.
    private static java.util.WeakHashMap<View, Boolean> clockShown =
            new java.util.WeakHashMap<View, Boolean>();
    public static void crossfadeClock(final View clock, boolean show) {
        if (clock == null) return;
        Boolean cur = clockShown.get(clock);
        if (cur == null) {
            clockShown.put(clock, Boolean.valueOf(show));
            clock.setVisibility(show ? View.VISIBLE : View.GONE);
            clock.setAlpha(1f);
            return;
        }
        if (cur.booleanValue() == show) return;
        clockShown.put(clock, Boolean.valueOf(show));
        if (show) {
            clock.setVisibility(View.VISIBLE);
            fadeIn(clock, 250);
        } else {
            fadeOut(clock, 250);
        }
    }

    // v15.30 (slice 1819): the chooser EQ slot crossfades instead of
    // snapping — the v15.24 card / v15.25 hairline / v15.26 clock melts,
    // now on the last snap in the sounding-row transition: the 1s tick
    // rebinds the chooser rows exactly when the sounding URL changes
    // (v4.89/v7.44) and getView swapped the 48dp number slot in one
    // frame (setVisibility), so a talk starting, stopping or
    // auto-advancing flashed three gold bars in (or the number back)
    // while the gold card beside it crossfades (v15.24), the hairline
    // melts (v15.25) and the clock melts (v15.26). Fx.crossfadeEqSlot
    // (250ms, DecelerateInterpolator, recorded shown state, instant
    // seed on a fresh view) owns the slot from here: sounding melts
    // the number out and the bars in; silent melts the bars out and
    // the number in. The dance itself is untouched — setPlaying stays
    // idempotent at the call site, and the bars keep dancing (or
    // resting) under their own animator while their alpha melts; the
    // ▶ prefix + gold name stay instant per the v14.16 settled
    // decision. A repeated call for the state already showing is a
    // no-op and never restarts the melt, and a mid-melt re-target
    // starts from the alpha each view actually has (fadeIn / fadeOut
    // bump the same fade gen, so a superseded fade-out end action
    // can never GONE a just-re-shown view). The record is keyed on
    // the bars view — one entry per row slot, and the two views are
    // always driven together, so they can never disagree. The row's
    // own pour (view Animation), press squash (scale) and delete
    // fade (row alpha) all live on other views/channels — the slot
    // children own only their own alpha, and getView writes no other
    // visibility/alpha onto them, so there is no strand to move.
    private static java.util.WeakHashMap<View, Boolean> eqSlotSounding =
            new java.util.WeakHashMap<View, Boolean>();
    public static void crossfadeEqSlot(final View number, final View eq, boolean sounding) {
        if (number == null || eq == null) return;
        Boolean cur = eqSlotSounding.get(eq);
        if (cur == null) {
            eqSlotSounding.put(eq, Boolean.valueOf(sounding));
            number.setVisibility(sounding ? View.GONE : View.VISIBLE);
            number.setAlpha(1f);
            eq.setVisibility(sounding ? View.VISIBLE : View.GONE);
            eq.setAlpha(1f);
            return;
        }
        if (cur.booleanValue() == sounding) return;
        eqSlotSounding.put(eq, Boolean.valueOf(sounding));
        if (sounding) {
            fadeOut(number, 250);
            eq.setVisibility(View.VISIBLE);
            fadeIn(eq, 250);
        } else {
            fadeOut(eq, 250);
            number.setVisibility(View.VISIBLE);
            fadeIn(number, 250);
        }
    }

    // v15.33 (slice 1838): the grid-card EQ badge melt — the
    // crossfadeEqSlot record pattern (v15.25) restated for a single
    // view: the grid card has no number slot to swap, so the badge
    // itself fades in/out on the sounding flip (250ms,
    // DecelerateInterpolator, the melt rhythm). Recorded shown
    // state, instant seed on a fresh view (a scroll-recycled card
    // never melts in from transparent), no-op on a steady rebind,
    // mid-fade re-target flows from the alpha the view actually
    // has (fadeIn / fadeOut bump the same fade gen, so a
    // superseded fade-out end action can never GONE a just-
    // re-shown badge). The dance itself is untouched — setPlaying
    // stays idempotent at the call site, and the bars keep dancing
    // (or resting) under their own animator while their alpha
    // melts; a real stop decays under the 250ms fade-out
    // (v15.32). The card's entrance pour, press squash and tile
    // crossfade live on other views/channels — the badge owns
    // only its own alpha/visibility.
    private static java.util.WeakHashMap<View, Boolean> eqBadgeSounding =
            new java.util.WeakHashMap<View, Boolean>();
    public static void crossfadeEqBadge(final View badge, boolean sounding) {
        if (badge == null) return;
        Boolean cur = eqBadgeSounding.get(badge);
        if (cur == null) {
            eqBadgeSounding.put(badge, Boolean.valueOf(sounding));
            badge.setVisibility(sounding ? View.VISIBLE : View.GONE);
            badge.setAlpha(1f);
            return;
        }
        if (cur.booleanValue() == sounding) return;
        eqBadgeSounding.put(badge, Boolean.valueOf(sounding));
        if (sounding) {
            badge.setVisibility(View.VISIBLE);
            fadeIn(badge, 250);
        } else {
            fadeOut(badge, 250);
        }
    }

    // v15.26 (slice 1791): the strip Next/Prev buttons melt in/out instead
    // of popping on the resume <-> live mode flip — the v14.70/71/72
    // title melt, now on the transport pair: the 1s tick sync flips
    // the strip between live and resume mode, and both branches
    // swapped Next + Prev in one frame (setVisibility) while the
    // resume title beside them melts in (v14.70) and the strip itself
    // slides (v14.14). Fx.crossfadeStripBtn (250ms,
    // DecelerateInterpolator, recorded shown state, instant seed on a
    // fresh view) owns both buttons from here; the toggle glyph
    // ▶/⏸ + title/sub text stay instant on every sync (values, not
    // appearance changes, per the v14.16 settled decision). A
    // repeated call for the state already showing is a no-op and
    // never restarts the fade, and a mid-fade re-target starts from
    // the alpha the view actually has. Next and Prev are independent
    // map entries — one button's melt never touches the other's
    // record. No top-of-bind reset exists on these buttons (no other
    // writer), so there is no strand to move — unlike the drawer
    // clock (slice 1790).
    private static java.util.WeakHashMap<View, Boolean> stripBtnShown =
            new java.util.WeakHashMap<View, Boolean>();
    public static void crossfadeStripBtn(final View btn, boolean show) {
        if (btn == null) return;
        Boolean cur = stripBtnShown.get(btn);
        if (cur == null) {
            stripBtnShown.put(btn, Boolean.valueOf(show));
            btn.setVisibility(show ? View.VISIBLE : View.GONE);
            btn.setAlpha(1f);
            return;
        }
        if (cur.booleanValue() == show) return;
        stripBtnShown.put(btn, Boolean.valueOf(show));
        if (show) {
            btn.setVisibility(View.VISIBLE);
            fadeIn(btn, 250);
        } else {
            fadeOut(btn, 250);
        }
    }

    // v15.26 (slice 1793): the drawer move-grip melts in/out instead
    // of popping on the queue-only <-> full-list flip — the v14.32
    // card crossfade / v15.25 hairline / v15.26 piece-2 clock melt,
    // now on the gold ↕ grip (v13.73): every drawer rebind (the
    // queue-only flip, a move arm, a track handover on the 500ms
    // poll resync) re-painted every row through bindMoveGrip73, and
    // the grip swapped in one frame (setVisibility) while the card
    // beside it crossfades and the hairline and clock melt. Fx.
    // crossfadeMoveGrip (250ms, DecelerateInterpolator, recorded
    // shown state, instant seed on a fresh view) owns the grip from
    // here; the ↕ text / size / tap listener stay instant at the
    // call site (values, not appearance changes, per the v14.16
    // settled decision). A repeated call for the state already
    // showing is a no-op and never restarts the fade, and a mid-fade
    // re-target starts from the alpha the view actually has. Each
    // row view is its own map entry — one row's melt never touches
    // another row's record. The top-of-bind reset's visibility half
    // moved onto the stale-slot return through this helper (see
    // RoomActivity), so the recorded target stays truthful.
    private static java.util.WeakHashMap<View, Boolean> gripShown =
            new java.util.WeakHashMap<View, Boolean>();
    public static void crossfadeMoveGrip(final View grip, boolean show) {
        if (grip == null) return;
        Boolean cur = gripShown.get(grip);
        if (cur == null) {
            gripShown.put(grip, Boolean.valueOf(show));
            grip.setVisibility(show ? View.VISIBLE : View.GONE);
            grip.setAlpha(1f);
            return;
        }
        if (cur.booleanValue() == show) return;
        gripShown.put(grip, Boolean.valueOf(show));
        if (show) {
            grip.setVisibility(View.VISIBLE);
            fadeIn(grip, 250);
        } else {
            fadeOut(grip, 250);
        }
    }

    // v15.29 (slice 1808): the held-row gold wash melts in/out
    // instead of snapping — the last still state arrival in the
    // Room drawer: arming a pick-up/drop move (grip tap, dialog
    // "move") rebinds and the picked-up row's wash (v4.92, the
    // ✋ mark + 0x33C9A227 gold wash) used to land in one frame,
    // while every surface beside it already melts (tile v14.32,
    // hairline v15.25, clock v15.26, grip v15.26) and the drop
    // landing fades (v14.46). Fx.crossfadeHeldWash (250ms,
    // DecelerateInterpolator, recorded target, instant seed on a
    // fresh view) owns the row root's background from here; the
    // ✋ prefix + ◈/★/⬇/✓ marks stay instant per the v14.16
    // settled decision. Same WeakHashMap discipline as
    // crossfadeImageTint: a recorded target per row view makes
    // the 500ms poll's rebind a repaint, not a re-melt; a
    // mid-melt re-target cancels the in-flight animator and melts
    // from the last painted alpha; the end stamps the exact
    // target only while its animator is still current. Two
    // differences from the tint helper, both forced by the wash
    // living on the row root's background: the top-of-bind reset
    // (RoomActivity, TRANSPARENT at the top of every bind) wipes
    // the background before this call, so the steady-state branch
    // must RE-PAINT — the target colour when no melt runs, the
    // current interpolated colour while one does (no flicker, no
    // restart) — and only the alpha animates (ofInt 0<->51, RGB
    // fixed C9A227), so a half-melted wash is the same gold at
    // half strength, never a darkened blend. The drop fade
    // (v14.46, row alpha) and the rank-close glide (v15.20,
    // translationY) live on other channels, so a wash melting out
    // on the dropped row never fights either. No new view, no
    // layout change, no speed-row touch.
    private static java.util.WeakHashMap<View, Integer> washTarget =
            new java.util.WeakHashMap<View, Integer>();
    private static java.util.WeakHashMap<View, android.animation.ValueAnimator> washAnim =
            new java.util.WeakHashMap<View, android.animation.ValueAnimator>();
    private static java.util.WeakHashMap<View, Integer> washCurrent =
            new java.util.WeakHashMap<View, Integer>();
    private static final int WASH_HELD = 0x33C9A227;
    public static void crossfadeHeldWash(final View row, final boolean held) {
        if (row == null) return;
        final int toColor = held ? WASH_HELD : android.graphics.Color.TRANSPARENT;
        Integer cur = washTarget.get(row);
        if (cur != null && cur.intValue() == toColor) {
            // Steady state — but the top-of-bind reset just wiped
            // the background, so re-paint: the target colour when
            // no melt runs, the current interpolated colour while
            // one does (the animator repaints on its next frame;
            // this keeps the frame between reset and frame
            // continuous). Never restarts the melt.
            android.animation.ValueAnimator live = washAnim.get(row);
            if (live != null) {
                Integer now = washCurrent.get(row);
                if (now != null) row.setBackgroundColor(now.intValue());
            } else {
                row.setBackgroundColor(toColor);
            }
            return;
        }
        washTarget.put(row, toColor);
        android.animation.ValueAnimator old = washAnim.get(row);
        washAnim.remove(row);
        if (old != null) old.cancel();
        Integer fromBox = washCurrent.get(row);
        if (fromBox == null) {
            // Fresh view: instant seed, like the other helpers —
            // a first bind (or a rotation's new views) paints the
            // final wash at once, never melts in from nothing.
            washCurrent.put(row, toColor);
            row.setBackgroundColor(toColor);
            return;
        }
        final int fromAlpha = android.graphics.Color.alpha(fromBox.intValue());
        final int toAlpha = android.graphics.Color.alpha(toColor);
        if (fromAlpha == toAlpha) {
            washCurrent.put(row, toColor);
            row.setBackgroundColor(toColor);
            return;
        }
        final android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofInt(fromAlpha, toAlpha);
        anim.setDuration(250);
        anim.setInterpolator(new DecelerateInterpolator());
        washAnim.put(row, anim);
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                int al = ((Integer) a.getAnimatedValue()).intValue();
                int c = android.graphics.Color.argb(al, 0xC9, 0xA2, 0x27);
                washCurrent.put(row, c);
                row.setBackgroundColor(c);
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                // cancel() fires onAnimationEnd too: only the
                // still-current animator may stamp the exact target —
                // a superseded one must never paint its stale wash
                // over a newer melt.
                if (washAnim.get(row) == anim) {
                    washCurrent.put(row, toColor);
                    row.setBackgroundColor(toColor);
                    washAnim.remove(row);
                }
            }
        });
        // Paint the starting alpha at once: the top-of-bind reset
        // just wiped the background, so without this the row shows
        // no wash until the animator's first frame (~16ms) — a
        // one-frame blink on every re-target.
        row.setBackgroundColor(fromBox.intValue());
        anim.start();
    }

    // v15.27 (slice 1794): the Settings Wi-Fi auto-download "check
    // now" gold action line melts in/out instead of popping when
    // the toggle flips — the Settings notes all melt already (size
    // note v14.66, auto-download note v14.67, data-saver note
    // v14.68, notification-blocked note v14.69), but this line was
    // the last raw visibility flip in refresh(): the toggle tap
    // morphs the switch and dips the root (themeDip: root -> 0.55,
    // swap at the dip bottom, root back over 260ms), and refresh()
    // runs as that swap, so the line used to appear/vanish in one
    // frame at 55% brightness directly under the tapped toggle
    // while the screen rose back. Fx.crossfadeCheckNow (250ms,
    // DecelerateInterpolator, recorded shown state, instant seed on
    // a fresh view) owns the line from here; the text / colour /
    // size stay instant at the call site (values, not appearance
    // changes, per the v14.16 settled decision). A repeated call
    // for the state already showing is a no-op and never restarts
    // the fade (refresh() runs on every onResume and every toggle
    // tap, so a steady line must never re-melt), and a mid-fade
    // re-target starts from the alpha the view actually has. One
    // view, one map entry — a refresh() from any other toggle is a
    // no-op for the line. The tap routing, the toggle morph/dip
    // and the note above are untouched — the melt changes
    // appearance only.
    private static java.util.WeakHashMap<View, Boolean> checkNowShown =
            new java.util.WeakHashMap<View, Boolean>();
    public static void crossfadeCheckNow(final View line, boolean show) {
        if (line == null) return;
        Boolean cur = checkNowShown.get(line);
        if (cur == null) {
            checkNowShown.put(line, Boolean.valueOf(show));
            line.setVisibility(show ? View.VISIBLE : View.GONE);
            line.setAlpha(1f);
            return;
        }
        if (cur.booleanValue() == show) return;
        checkNowShown.put(line, Boolean.valueOf(show));
        if (show) {
            line.setVisibility(View.VISIBLE);
            fadeIn(line, 250);
        } else {
            fadeOut(line, 250);
        }
    }

    // v14.34 (slice 1689): the Room sleep countdown's under-a-minute
    // gold flip used to snap — the 500ms poll swapped the text
    // colour in one frame (setTextColor: grey #AAAAAA <-> gold
    // #C9A227) the moment the remainder crossed 60s, while
    // everything around the line already moves (its own fade-in/out,
    // the ring/tile crossfades). The colour now melts over 250ms,
    // the same duration as the ring/tile crossfades, via a
    // ValueAnimator.ofArgb from the current colour to the target.
    // The v14.16 settled decision does NOT bar this: its reason was
    // that fading the whole label to ease a 2-char mark would flash
    // the row — here no label is faded, only the text colour is
    // interpolated (alpha stays 1, text unchanged), so the row
    // cannot flash. Recorded target per view (a steady tick is a
    // no-op, like the ring/tile maps); already-at-target records
    // with no animator; a superseded animator is cancelled BEFORE
    // the new one is recorded — the map entry is removed first, so
    // the old one's end (cancel() fires it) sees it is superseded
    // and never stamps its stale colour — and the same cancel runs
    // before the from==to fast path below (v14.42: the 1694 caveat —
    // a call landing on an already-correct colour used to return
    // without cancelling, so the superseded melt's end could stamp
    // its stale colour; crossfadeImageTint closes the same hole by
    // construction, v14.41). The end stamps the exact target only
    // while this animator is still current. The helper owns the
    // colour: no other writer touches this view's text colour.
    private static java.util.WeakHashMap<View, Integer> textColorTarget =
            new java.util.WeakHashMap<View, Integer>();
    private static java.util.WeakHashMap<View, android.animation.ValueAnimator> textColorAnim =
            new java.util.WeakHashMap<View, android.animation.ValueAnimator>();
    public static void crossfadeTextColor(final android.widget.TextView v, final int toColor) {
        if (v == null) return;
        Integer cur = textColorTarget.get(v);
        if (cur != null && cur.intValue() == toColor) return;
        // v14.42 (slice 1697): close the 1694 from==to fast-path
        // caveat — record the target and cancel any in-flight melt
        // (map entry removed BEFORE the cancel, so the cancelled
        // animator's end listener skips its stale stamp) BEFORE
        // reading the painted colour, mirroring crossfadeImageTint
        // (v14.41): a call landing on an already-correct colour now
        // cancels the superseded melt first instead of returning
        // with it still running behind it.
        textColorTarget.put(v, toColor);
        android.animation.ValueAnimator old = textColorAnim.get(v);
        textColorAnim.remove(v);
        if (old != null) old.cancel();
        int from = v.getCurrentTextColor();
        if (from == toColor) return;
        final android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofArgb(from, toColor);
        anim.setDuration(250);
        textColorAnim.put(v, anim);
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                v.setTextColor((Integer) a.getAnimatedValue());
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                // cancel() fires onAnimationEnd too: only the
                // still-current animator may stamp the exact target —
                // a superseded one must never paint its stale colour
                // over a newer melt.
                if (textColorAnim.get(v) == anim) {
                    v.setTextColor(toColor);
                    textColorAnim.remove(v);
                }
            }
        });
        anim.start();
    }

    // v14.39 (slice 1694): the instant writer for a view whose
    // colour crossfadeTextColor owns — the Talks chooser chips melt
    // on tap through the helper, but create / theme / language /
    // rotation / clearFilter repaints must land instantly, and a
    // bare setTextColor there would desync the helper's recorded
    // target (map says gold while the view is muted, so a later
    // melt to gold no-ops and the chip strands muted). This stamps
    // the colour at once AND records it as the target, cancelling
    // any in-flight melt first: the map entry is removed BEFORE
    // the cancel, so the cancelled animator's end listener — which
    // stamps only while still current — skips its stale colour.
    // Same WeakHashMap discipline as the melt.
    public static void setTextColorNow(final android.widget.TextView v, final int color) {
        if (v == null) return;
        textColorTarget.put(v, color);
        android.animation.ValueAnimator old = textColorAnim.get(v);
        textColorAnim.remove(v);
        if (old != null) old.cancel();
        v.setTextColor(color);
    }

    // v15.38 (slice 1894): the filter-chip weight flip melts instead
    // of snapping — the last snap in the filter-chip transition on
    // the Talks chooser (All/Star/New/Downloaded) and the Library
    // (All/Star/New): the chip colours already melt on tap (v14.39 /
    // v14.40, 250ms crossfadeTextColor) but the selected chip's
    // BOLD/NORMAL flip swapped in one frame, so the label jumped
    // weight (and a few px of width, shifting its neighbours) while
    // the colour beside it melted. The weight IS the selection state
    // — the v14.39/v14.40 comments already say so — and the melt
    // carries the transition now: the label dips 90ms, swaps weight
    // at the bottom, and rises 180ms (the flipStripGlyph / 1884
    // sleep-text rhythm, Decelerate), landing inside the same
    // ~250ms window as the colour melt beside it. A typeface has no
    // interpolator, so the dip masks the swap the way the sleep
    // text's dip masks its text swap. Same WeakHashMap discipline
    // as crossfadeTextColor: a recorded target per view makes a
    // repeat paint a no-op (the tap handler and the apply tail
    // repaint the chips on every pass), a mid-melt re-target
    // cancels the in-flight dip/rise and melts from the alpha the
    // view actually has, and a stale phase end never swaps the
    // weight or stamps the alpha (the map entry is removed BEFORE
    // the cancel, the v14.42 order — a cancelled ValueAnimator
    // still fires its end listener). The melt owns the chip's alpha
    // only while it runs: it starts only on a visible chip, and the
    // instant writer below is the only other alpha writer on these
    // chips (verified first-hand: no other setAlpha/animate call
    // touches any of the seven chips), so the dip can never fight
    // the theme dip (which dips the root, never the chip) or a press
    // squash (scale, never alpha). Text, size and colour are
    // untouched — the colour melt lives on textColor, a different
    // property, and the two run side by side on every tap.
    private static java.util.WeakHashMap<View, Boolean> chipWeightTarget =
            new java.util.WeakHashMap<View, Boolean>();
    private static java.util.WeakHashMap<View, android.animation.ValueAnimator> chipWeightAnim =
            new java.util.WeakHashMap<View, android.animation.ValueAnimator>();
    public static void meltChipWeight(final android.widget.TextView v, final boolean bold) {
        if (v == null) return;
        Boolean cur = chipWeightTarget.get(v);
        if (cur != null && cur.booleanValue() == bold) return;
        // A fresh chip seeds instantly — the first paint is the
        // animate=false creation path anyway, and there is no
        // previous weight to melt from.
        if (cur == null) { setChipWeightNow(v, bold); return; }
        chipWeightTarget.put(v, Boolean.valueOf(bold));
        android.animation.ValueAnimator old = chipWeightAnim.remove(v);
        if (old != null) old.cancel();
        // Only a visible chip melts — a hidden one stamps instantly
        // instead, so the dip can never strand a label the user
        // cannot see. No settled-alpha guard: the chip's alpha has
        // no foreign writer (see the header), so a mid-melt
        // re-target simply melts from the alpha the view actually
        // has, like crossfadeTitleDim — it never jumps.
        if (v.getVisibility() != View.VISIBLE) { setChipWeightNow(v, bold); return; }
        float from = v.getAlpha();
        if (from <= 0.36f) {
            // Already at the dip floor (a re-target landing on a
            // deep dip) — swap at once and rise from here instead
            // of dipping further.
            v.setTypeface(null, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            riseChipWeight(v, bold);
            return;
        }
        final android.animation.ValueAnimator dip =
                android.animation.ValueAnimator.ofFloat(from, 0.35f);
        dip.setDuration(90);
        dip.setInterpolator(new DecelerateInterpolator());
        chipWeightAnim.put(v, dip);
        dip.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                v.setAlpha(((Float) a.getAnimatedValue()).floatValue());
            }
        });
        dip.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                // cancel() fires onAnimationEnd too: only the
                // still-current dip may swap the weight — a
                // superseded one must never paint its stale weight
                // over a newer melt.
                if (chipWeightAnim.get(v) != dip) return;
                v.setTypeface(null, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                riseChipWeight(v, bold);
            }
        });
        dip.start();
    }
    private static void riseChipWeight(final android.widget.TextView v, final boolean bold) {
        float from = v.getAlpha();
        final android.animation.ValueAnimator rise =
                android.animation.ValueAnimator.ofFloat(from, 1.0f);
        rise.setDuration(180);
        rise.setInterpolator(new DecelerateInterpolator());
        chipWeightAnim.put(v, rise);
        rise.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                v.setAlpha(((Float) a.getAnimatedValue()).floatValue());
            }
        });
        rise.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (chipWeightAnim.get(v) == rise) {
                    v.setAlpha(1.0f);
                    chipWeightAnim.remove(v);
                }
            }
        });
        rise.start();
    }

    // v15.38 (slice 1894): the instant writer for a chip whose
    // weight meltChipWeight owns — create / theme / language /
    // rotation / clearFilter repaints must land instantly, and a
    // bare setTypeface there would desync the helper's recorded
    // target (map says BOLD while the view is NORMAL, so a later
    // melt to BOLD no-ops and the chip strands unbolded while
    // selected). This stamps the weight at once AND records it as
    // the target, cancelling any in-flight melt first, and restores
    // alpha 1.0 so a melt cut short mid-dip can never strand the
    // label half-transparent. Same discipline as setTextColorNow
    // (v14.39).
    public static void setChipWeightNow(final android.widget.TextView v, final boolean bold) {
        if (v == null) return;
        chipWeightTarget.put(v, Boolean.valueOf(bold));
        android.animation.ValueAnimator old = chipWeightAnim.remove(v);
        if (old != null) old.cancel();
        v.setTypeface(null, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        v.setAlpha(1.0f);
    }

    // v15.37 (slice 1882): the sounding ring breath now eases out on
    // stop — the last snap in the ring family. All seven ring breaths
    // (chooser header 1845, Room header 1846, Main/Talks/Settings/
    // Downloaded strips 1847-1850, shelf chip 1851) animate the ring
    // drawable 255 -> 140 over 900ms REVERSE INFINITE, and every stop
    // cancelled the loop and setAlpha(255) in one frame — a talk
    // ending or a pause snapped the gold ring from wherever it sat
    // mid-breath (as low as 0.55) straight to full alpha, the same
    // pop slice 1831 eased out of the sounding card's name breath
    // and slice 1826 eased out of the EQ bars. The ring now glides
    // back to opaque over 200ms (Decelerate, the Library pause
    // rhythm), so the breath hands over instead of cutting. 200ms is
    // deliberately shorter than the 260ms delayed start every arm
    // posts: a re-arm's runnable always lands after the ease has
    // finished, and a stop inside the wait vetoes the start by gen —
    // so no settle is needed on the restart half (the 1831 settle
    // exists because the card pulse starts at once; the ring breath
    // never does). The helper owns the animator per drawable
    // (WeakHashMap, the crossfadeTextColor discipline): a repeated
    // call cancels the in-flight ease first — map entry removed
    // BEFORE the cancel, so the cancelled animator's end skips its
    // stale stamp (v14.42) — and the end stamps 255 only while its
    // animator is still current. A background replaced mid-ease (a
    // crossfade) strands the write on the detached drawable, which
    // is invisible; the new background is fresh at 255. Visual only;
    // every stop call site and every arm is untouched.
    private static java.util.WeakHashMap<android.graphics.drawable.Drawable, android.animation.ValueAnimator> ringEaseOutAnim =
            new java.util.WeakHashMap<android.graphics.drawable.Drawable, android.animation.ValueAnimator>();
    public static void easeRingAlphaToOpaque(final android.graphics.drawable.Drawable bg) {
        if (bg == null) return;
        android.animation.ValueAnimator old = ringEaseOutAnim.remove(bg);
        if (old != null) old.cancel();
        int from = bg.getAlpha();
        if (from >= 255) return;
        final android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofInt(from, 255);
        anim.setDuration(200);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        ringEaseOutAnim.put(bg, anim);
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                bg.setAlpha(((Integer) a.getAnimatedValue()).intValue());
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                // cancel() fires onAnimationEnd too: only the
                // still-current animator may stamp the exact target.
                if (ringEaseOutAnim.get(bg) == anim) {
                    bg.setAlpha(255);
                    ringEaseOutAnim.remove(bg);
                }
            }
        });
        anim.start();
    }

    // v15.36 (slice 1861): the listened title dim melts instead of
    // snapping — the last snap in the listened-flip transition on
    // the Talks chooser and Library rows (colour v15.30, hairline
    // v15.25, clock v15.26 all melt beside it): when a talk
    // finishes, the 1s tick rebinds and the title's gold melted to
    // base over 250ms while its alpha snapped 1.0 -> 0.6 in the
    // same frame. Fx.crossfadeTitleDim (250ms, DecelerateInterpolator,
    // recorded target, instant seed on a fresh view) owns the alpha
    // from here; the ✓ mark stays instant and the text is untouched,
    // so the dim IS the state change — the v14.16 settled decision
    // does NOT bar this (its reason was fading a whole label to ease
    // a 2-char mark; here no label is faded to mask anything, the
    // same distinction v15.30 drew for the colour melt). A repeated
    // call for the state already showing is a no-op and never
    // restarts the melt; a mid-melt re-target cancels the in-flight
    // animator and melts from the alpha the view actually has; the
    // end stamps the exact target only while its animator is still
    // current (the v14.42 cancel-then-end order, map entry removed
    // BEFORE the cancel). v15.38 (slice 1888): the Room drawer rows
    // melt through this helper too now — the v14.16 decision, read
    // through its own reason (fading a label to ease a mark), never
    // barred the dim itself, and the drawer was left instant in
    // 1861 only because its bind stamped alpha 1.0 unconditionally
    // before the dim line, which would restart a melt on every
    // rebind. Those stamps are gone (RoomActivity: the foreign top
    // reset, the pulse-recycle stamp, the v3.46 hygiene stamp); the
    // dim line is the drawer's title-alpha sole writer, and the Up
    // Next ▸ row goes through setTitleDimNow below.
    private static java.util.WeakHashMap<View, Float> titleDimTarget =
            new java.util.WeakHashMap<View, Float>();
    private static java.util.WeakHashMap<View, android.animation.ValueAnimator> titleDimAnim =
            new java.util.WeakHashMap<View, android.animation.ValueAnimator>();
    public static void crossfadeTitleDim(final android.widget.TextView v, boolean listened) {
        if (v == null) return;
        float to = listened ? 0.6f : 1.0f;
        Float cur = titleDimTarget.get(v);
        if (cur == null) {
            titleDimTarget.put(v, Float.valueOf(to));
            v.setAlpha(to);
            return;
        }
        if (cur.floatValue() == to) return;
        titleDimTarget.put(v, Float.valueOf(to));
        android.animation.ValueAnimator old = titleDimAnim.get(v);
        titleDimAnim.remove(v);
        if (old != null) old.cancel();
        float from = v.getAlpha();
        if (from == to) return;
        final android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofFloat(from, to);
        anim.setDuration(250);
        anim.setInterpolator(new DecelerateInterpolator());
        titleDimAnim.put(v, anim);
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                v.setAlpha(((Float) a.getAnimatedValue()).floatValue());
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (titleDimAnim.get(v) == anim) {
                    v.setAlpha(to);
                    titleDimAnim.remove(v);
                }
            }
        });
        anim.start();
    }

    // v15.36 (slice 1861): the instant writer for a view whose
    // alpha crossfadeTitleDim owns — the Library stale-slot clear
    // must land at once (an empty row has no content to melt), and
    // a bare setAlpha there would desync the helper's recorded
    // target (map says 0.6 while the view is 1.0, so a later melt
    // to 0.6 no-ops and the row strands undimmed while listened).
    // This stamps the alpha at once AND records it as the target,
    // cancelling any in-flight melt first — the v14.39
    // setTextColorNow shape.
    public static void setTitleDimNow(final android.widget.TextView v, boolean listened) {
        if (v == null) return;
        float to = listened ? 0.6f : 1.0f;
        titleDimTarget.put(v, Float.valueOf(to));
        android.animation.ValueAnimator old = titleDimAnim.get(v);
        titleDimAnim.remove(v);
        if (old != null) old.cancel();
        v.setAlpha(to);
    }

    // v14.41 (slice 1696): the Room shuffle glyph melts gold<->grey
    // on tap instead of snapping — applyShuffleGlyph set the
    // ImageButton's colour filter in one frame, so the shuffle icon
    // jumped gold/grey while the button itself morphs (Fx.morph)
    // and presses (Fx.press) under the finger: the state change
    // was the one un-choreographed part of the tap. Same 250ms
    // melt rhythm as the chips (v14.39/v14.40) and the sleep text
    // (v14.34), same WeakHashMap discipline as crossfadeTextColor:
    // a recorded target per view makes the 500ms poll's re-apply a
    // no-op (the poll repaints the same target every tick), a
    // mid-melt re-target cancels the in-flight animator and melts
    // from the last painted frame, and the end stamps the exact
    // target only while its animator is still current. One
    // difference from crossfadeTextColor, closing its 1694 caveat
    // by construction: the from==to fast path cancels the in-flight
    // animator BEFORE returning, so a tap landing on an already-
    // correct colour can never let a superseded melt's end stamp
    // a stale tint. A view reads its tint only through these two
    // helpers — the current painted tint lives in tintCurrent,
    // updated on every animation frame, because ImageView exposes
    // no getColorFilter to read the "from" back. Visual only.
    private static java.util.WeakHashMap<View, Integer> tintTarget =
            new java.util.WeakHashMap<View, Integer>();
    private static java.util.WeakHashMap<View, android.animation.ValueAnimator> tintAnim =
            new java.util.WeakHashMap<View, android.animation.ValueAnimator>();
    private static java.util.WeakHashMap<View, Integer> tintCurrent =
            new java.util.WeakHashMap<View, Integer>();
    public static void crossfadeImageTint(final android.widget.ImageView v, final int toColor) {
        if (v == null) return;
        Integer cur = tintTarget.get(v);
        if (cur != null && cur.intValue() == toColor) return;
        tintTarget.put(v, toColor);
        android.animation.ValueAnimator old = tintAnim.get(v);
        tintAnim.remove(v);
        if (old != null) old.cancel();
        Integer fromBox = tintCurrent.get(v);
        if (fromBox == null) { setImageTintNow(v, toColor); return; }
        final int from = fromBox.intValue();
        if (from == toColor) { tintCurrent.put(v, toColor); return; }
        final android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofArgb(from, toColor);
        anim.setDuration(250);
        anim.setInterpolator(new DecelerateInterpolator());
        tintAnim.put(v, anim);
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                int c = ((Integer) a.getAnimatedValue()).intValue();
                v.setColorFilter(c, android.graphics.PorterDuff.Mode.SRC_IN);
                tintCurrent.put(v, c);
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                // cancel() fires onAnimationEnd too: only the
                // still-current animator may stamp the exact target —
                // a superseded one must never paint its stale tint
                // over a newer melt.
                if (tintAnim.get(v) == anim) {
                    v.setColorFilter(toColor, android.graphics.PorterDuff.Mode.SRC_IN);
                    tintCurrent.put(v, toColor);
                    tintAnim.remove(v);
                }
            }
        });
        anim.start();
    }

    // v14.41 (slice 1696): the instant writer for a view whose
    // tint crossfadeImageTint owns — the Room shuffle glyph melts
    // on tap, but the onCreate first paint has no previous tint to
    // melt from and must land instantly. Stamps the filter at once
    // AND records it as target + current, cancelling any in-flight
    // melt first: the map entry is removed BEFORE the cancel, so
    // the cancelled animator's end listener — which stamps only
    // while still current — skips its stale tint. Same discipline
    // as setTextColorNow (v14.39).
    public static void setImageTintNow(final android.widget.ImageView v, final int color) {
        if (v == null) return;
        tintTarget.put(v, color);
        tintCurrent.put(v, color);
        android.animation.ValueAnimator old = tintAnim.get(v);
        tintAnim.remove(v);
        if (old != null) old.cancel();
        v.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
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

    // v15.29 (slice 1812): swipe the mini-player strip to skip —
    // the strip is the one playback surface with no gesture: the
    // Room altar already swipes left/right to change talk (v4.14)
    // and up/down for the drawer (v4.16), but on Main/Talks/
    // Library/Settings the only way to advance was the ⏭/⏮
    // buttons — the Spotify / YouTube Music / Apple Music mini-
    // player swipe (left = next, right = previous, same 100dp
    // threshold and direction as the Room) was missing. This
    // helper replaces Fx.press on the four live strips and owns
    // the strip container's touch stream from DOWN (the Room
    // v4.14 pattern — the container's background is the plain
    // nav_glass layer-list, no pressed-state ripple to lose, and
    // the ⏮/⏯/⏭ buttons keep their own streams, so owning the
    // container's stream cannot steal a button tap): DOWN
    // squashes 0.96 over 110ms (the Fx.press beat) and arms the
    // long-press at the platform timeout; MOVE past 100dp
    // horizontal (and dominant) arms the fling and releases the
    // squash; UP fires it through the button's own performClick
    // — the Next/Prev click guards ride along for free (resume
    // mode resumes the saved talk instead of advancing, the live
    // path ACTION_NEXTs into the Room, morph + tap sound
    // included), so a swipe can never do what the button would
    // not. A tap (<12dp) still performClicks the strip (opens
    // the Room / resumes), a hold still performLongClicks it
    // (stop/dismiss) — both re-dispatched, never re-implemented
    // — and a move past the 12dp tap slop cancels the hold, so
    // a slow drag can never stop the session. The sub line is
    // clickable (clock flip, v5.33), so its touches never reach
    // the container: it gets a fling-only listener that returns
    // false until the swipe threshold, so the clock flip, the
    // sub long-press forward and the slop-cancelled hold all
    // behave exactly as before, and only a real fling is
    // consumed (pressed cleared, no flip). The progress bar
    // keeps miniSeekBar (v4.73) — a fling starting on the bar
    // still seeks. History/Playlist strips are dormant (no
    // entry point) and keep plain Fx.press. No new view, no
    // layout change, no speed-row touch.
    public static void stripSwipe(final View strip) {
        if (strip == null) return;
        strip.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            boolean downActive = false;
            boolean longFired = false;
            boolean flung = false;
            boolean tracking = false; // v15.37: a horizontal drag is following the finger
            int flingDir = 0; // -1 = left = next, +1 = right = prev (Room v4.14)
            Runnable pendingLongPress = null;
            @Override public boolean onTouch(final View v, MotionEvent e) {
                float density = v.getResources().getDisplayMetrics().density;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX(); downY = e.getY();
                        downActive = true; longFired = false; flung = false; flingDir = 0;
                        tracking = false;
                        stripSwipeCancelX(v); // a fresh finger snaps any in-flight toss home first
                        pressScale(v, 0.96f, 110, new DecelerateInterpolator());
                        pendingLongPress = new Runnable() {
                            @Override public void run() {
                                if (!downActive || longFired || flung) return;
                                longFired = true;
                                v.performLongClick();
                            }
                        };
                        v.postDelayed(pendingLongPress, android.view.ViewConfiguration.getLongPressTimeout());
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float mdx = e.getX() - downX, mdy = e.getY() - downY;
                        if (!tracking && downActive && Math.abs(mdx) > 12f * density
                                && Math.abs(mdx) > Math.abs(mdy)) {
                            // v15.37: pick-up — the strip starts following the finger
                            // here (12dp slop, horizontal dominant), not at the
                            // 100dp fling arm: a drag is not a hold, so the
                            // long-press dies and the DOWN squash lifts.
                            tracking = true;
                            downActive = false;
                            if (pendingLongPress != null) v.removeCallbacks(pendingLongPress);
                            pressScale(v, 1f, 180, new OvershootInterpolator(1.4f));
                        }
                        if (tracking && !flung && Math.abs(mdx) > 100f * density
                                && Math.abs(mdx) > Math.abs(mdy)) {
                            flung = true;
                            flingDir = mdx < 0 ? -1 : +1;
                            if (pendingLongPress != null) v.removeCallbacks(pendingLongPress);
                        }
                        if (tracking) stripSwipeTrack(v, mdx);
                        else if (downActive && (Math.abs(mdx) > 12f * density
                                || Math.abs(mdy) > 12f * density)) {
                            downActive = false;
                            if (pendingLongPress != null) v.removeCallbacks(pendingLongPress);
                        }
                        return true;
                    case MotionEvent.ACTION_UP: {
                        downActive = false;
                        if (pendingLongPress != null) v.removeCallbacks(pendingLongPress);
                        pressScale(v, 1f, 180, new OvershootInterpolator(1.4f));
                        if (longFired) {
                            // a post-hold drag ends here: snap any follow home.
                            tracking = false; flung = false;
                            stripSwipeCancelX(v);
                            return true;
                        }
                        if (tracking) {
                            boolean wasFlung = flung; int dir = flingDir;
                            tracking = false; flung = false;
                            if (wasFlung) fireStripSkip(strip, dir);
                            stripSwipeSettle(v, wasFlung, dir);
                            return true;
                        }
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        if (Math.abs(dx) < 12f * density && Math.abs(dy) < 12f * density) {
                            v.performClick();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        downActive = false;
                        if (pendingLongPress != null) v.removeCallbacks(pendingLongPress);
                        pressScale(v, 1f, 180, new OvershootInterpolator(1.4f));
                        if (tracking) {
                            tracking = false; flung = false;
                            stripSwipeSettle(v, false, 0);
                        }
                        return true;
                }
                return true;
            }
        });
        // The sub line (v5.33 clock flip) is clickable, so its
        // touches never reach the container listener above — a
        // swipe starting on the sayadaw/clock line would do
        // nothing. A fling-only listener here: false until the
        // swipe threshold, so the tap (clock flip), the long-
        // press forward to the strip and the slop-cancelled hold
        // all behave exactly as before; only a real fling is
        // consumed (pressed cleared so no ghost state, no flip).
        final View sub = strip.findViewById(R.id.nowStripSub);
        if (sub != null) {
            sub.setOnTouchListener(new View.OnTouchListener() {
                float downX, downY;
                boolean flung = false;
                boolean tracking = false; // v15.37: same finger-follow as the container
                int flingDir = 0;
                @Override public boolean onTouch(View view, MotionEvent e) {
                    float density = view.getResources().getDisplayMetrics().density;
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = e.getX(); downY = e.getY();
                            flung = false; flingDir = 0;
                            tracking = false;
                            stripSwipeCancelX(strip);
                            return false;
                        case MotionEvent.ACTION_MOVE:
                            float mdx = e.getX() - downX, mdy = e.getY() - downY;
                            if (!tracking && Math.abs(mdx) > 12f * density
                                    && Math.abs(mdx) > Math.abs(mdy)) {
                                tracking = true;
                                view.setPressed(false);
                            }
                            if (tracking && !flung && Math.abs(mdx) > 100f * density
                                    && Math.abs(mdx) > Math.abs(mdy)) {
                                flung = true;
                                flingDir = mdx < 0 ? -1 : +1;
                            }
                            if (tracking) stripSwipeTrack(strip, mdx);
                            return tracking;
                        case MotionEvent.ACTION_UP:
                            if (tracking) {
                                boolean wasFlung = flung; int dir = flingDir;
                                tracking = false; flung = false;
                                if (wasFlung) {
                                    fireStripSkip(strip, dir);
                                    stripSwipeSettle(strip, true, dir);
                                } else {
                                    stripSwipeSettle(strip, false, 0);
                                }
                                return true;
                            }
                            return false;
                        case MotionEvent.ACTION_CANCEL:
                            if (tracking) {
                                tracking = false; flung = false;
                                stripSwipeSettle(strip, false, 0);
                                return true;
                            }
                            flung = false;
                            return false;
                    }
                    return false;
                }
            });
        }
    }

    // v15.29 (slice 1812): the swipe's landing — through the
    // strip's own Next/Prev buttons (see stripSwipe), never a
    // parallel skip path: resume mode resumes, live mode
    // ACTION_NEXTs/PREVs into the player, exactly as a button
    // tap would.
    private static void fireStripSkip(final View strip, final int dir) {
        if (strip == null) return;
        View btn = strip.findViewById(dir < 0 ? R.id.nowStripNext : R.id.nowStripPrev);
        if (btn != null) btn.performClick();
    }

    // v15.37 (slice 1878): the strip swipe follows the finger —
    // the v15.29 swipe was dead during the drag: DOWN squashed the
    // strip 0.96, MOVE only armed the fling at 100dp, UP fired the
    // skip, so a 100dp drag moved nothing until the release, while
    // the Spotify / YouTube Music / Apple Music mini-player tracks
    // the finger and tosses the card off on a committed skip. The
    // strip now translates with the drag (clamped to half its
    // width, so it can never slide clean off), springs back under
    // the threshold, and on a committed skip tosses 0.75 widths in
    // the fling direction (150ms Decelerate) then springs home
    // (240ms Overshoot 1.4, the Fx.press restore beat) while the
    // skip fires through the same Next/Prev button as before — the
    // new track's content melts underneath via the existing strip
    // fades. Only translationX moves: showStrip/hideStrip own
    // translationY + alpha and pressScale owns scale, so nothing
    // fights; the X animator lives in its own ObjectAnimator (never
    // strip.animate()), so a show/hide mid-toss cannot cancel it
    // and every path ends at exactly 0 — a fresh DOWN snaps any
    // in-flight X back to identity first. No new view, no layout
    // change, no speed-row touch; the four live strips share this
    // helper, History/Playlist keep plain Fx.press.
    private static java.util.WeakHashMap<android.view.View, android.animation.Animator> stripSwipeXAnims =
            new java.util.WeakHashMap<android.view.View, android.animation.Animator>();

    private static void stripSwipeTrack(android.view.View strip, float dx) {
        if (strip == null) return;
        float w = strip.getWidth() > 0 ? strip.getWidth()
                : 160f * strip.getResources().getDisplayMetrics().density;
        float cap = 0.5f * w;
        strip.setTranslationX(Math.max(-cap, Math.min(cap, dx)));
    }

    private static void stripSwipeCancelX(android.view.View strip) {
        if (strip == null) return;
        android.animation.Animator a = stripSwipeXAnims.remove(strip);
        if (a != null) a.cancel();
        strip.setTranslationX(0f);
    }

    private static void stripSwipeSettle(final android.view.View strip, boolean flung, int dir) {
        if (strip == null) return;
        android.animation.Animator old = stripSwipeXAnims.remove(strip);
        if (old != null) old.cancel();
        float w = strip.getWidth() > 0 ? strip.getWidth()
                : 160f * strip.getResources().getDisplayMetrics().density;
        android.animation.AnimatorSet set = new android.animation.AnimatorSet();
        if (flung) {
            android.animation.ObjectAnimator toss =
                    android.animation.ObjectAnimator.ofFloat(strip, "translationX", dir * 0.75f * w);
            toss.setDuration(150);
            toss.setInterpolator(new android.view.animation.DecelerateInterpolator());
            android.animation.ObjectAnimator home =
                    android.animation.ObjectAnimator.ofFloat(strip, "translationX", 0f);
            home.setDuration(240);
            home.setInterpolator(new android.view.animation.OvershootInterpolator(1.4f));
            set.playSequentially(toss, home);
        } else {
            android.animation.ObjectAnimator home =
                    android.animation.ObjectAnimator.ofFloat(strip, "translationX", 0f);
            home.setDuration(220);
            home.setInterpolator(new android.view.animation.OvershootInterpolator(1.4f));
            set.play(home);
        }
        set.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (stripSwipeXAnims.get(strip) == set) stripSwipeXAnims.remove(strip);
                strip.setTranslationX(0f);
            }
            @Override public void onAnimationCancel(android.animation.Animator a) {
                if (stripSwipeXAnims.get(strip) == set) stripSwipeXAnims.remove(strip);
            }
        });
        stripSwipeXAnims.put(strip, set);
        set.start();
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
                        cancelStripGlide(pb); // v14.19: the finger owns the bar 1:1 — a glide left over from the last tick must not fight the preview
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
                        // v13.59 (887 F-NEW-1, M948-2): identity
                        // compare — liveUrl may flip to the http
                        // retry form mid-drag; that flip must not
                        // discard a legitimate scrub.
                        if (dragUrl != null && liveUrl != null && !SiteHttp.sameTalk(dragUrl, liveUrl)) {
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
    // v14.19: the strip bar used to jump once a second — the 1s tick
    // wrote the live fraction in one frame, so on a short talk the
    // gold edge ticked ~3dp every second while everything around it
    // moves (v14.14 strip slide, v14.15 shelf pour, v14.16 ring
    // crossfade, v14.18 wave ease). The bar now glides between
    // ticks instead: a linear 1000ms ValueAnimator from the bar's
    // current displayed value to the live target, so it moves at
    // the same constant speed as the talk itself. Linear, not the
    // framework's decelerate — a clock-driven bar must not slow
    // down and catch up every second. Change-only like the Room
    // wave's dim ease (v14.18): a tick whose target matches the
    // running glide's target is a no-op, so a paused or settled
    // bar never re-animates; a retarget cancels and restarts from
    // the displayed value, so the bar can never jump or lag.
    // The animator + its target ride on the bar's own tags (see
    // ids.xml), so they die with the view. The drag cancels the
    // glide first (miniSeekBar) — the finger owns the bar 1:1 —
    // and a talk change snaps (syncStripProgress), never sweeps.
    private static String stripGlideUrl = null;

    private static void cancelStripGlide(android.widget.ProgressBar pb) {
        if (pb == null) return;
        Object aObj = pb.getTag(R.id.stripGlideAnim);
        if (aObj instanceof android.animation.ValueAnimator) {
            ((android.animation.ValueAnimator) aObj).cancel();
        }
        pb.setTag(R.id.stripGlideAnim, null);
        pb.setTag(R.id.stripGlideTarget, null);
    }

    private static void glideStripProgress(android.widget.ProgressBar pb, int target) {
        if (pb == null) return;
        Object aObj = pb.getTag(R.id.stripGlideAnim);
        android.animation.ValueAnimator anim = (aObj instanceof android.animation.ValueAnimator)
                ? (android.animation.ValueAnimator) aObj : null;
        Object tObj = pb.getTag(R.id.stripGlideTarget);
        if (anim != null && anim.isRunning() && tObj instanceof Integer
                && ((Integer) tObj).intValue() == target) return;
        if (anim != null) anim.cancel();
        int from = pb.getProgress();
        if (from == target) {
            pb.setTag(R.id.stripGlideAnim, null);
            pb.setTag(R.id.stripGlideTarget, null);
            return;
        }
        final android.widget.ProgressBar bar = pb;
        android.animation.ValueAnimator g =
                android.animation.ValueAnimator.ofFloat(from, target);
        g.setDuration(1000);
        g.setInterpolator(new android.view.animation.LinearInterpolator());
        g.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                bar.setProgress(((Float) a.getAnimatedValue()).intValue());
            }
        });
        g.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (bar.getTag(R.id.stripGlideAnim) == a) {
                    bar.setTag(R.id.stripGlideAnim, null);
                    bar.setTag(R.id.stripGlideTarget, null);
                }
            }
            @Override public void onAnimationCancel(android.animation.Animator a) {
                if (bar.getTag(R.id.stripGlideAnim) == a) {
                    bar.setTag(R.id.stripGlideAnim, null);
                    bar.setTag(R.id.stripGlideTarget, null);
                }
            }
        });
        pb.setTag(R.id.stripGlideAnim, g);
        pb.setTag(R.id.stripGlideTarget, Integer.valueOf(target));
        g.start();
    }

    public static void syncStripProgress(android.widget.ProgressBar pb) {
        if (pb == null) return;
        if (Boolean.TRUE.equals(pb.getTag(R.id.nowStripProgress))) {
            if (!AudioService.liveSession || AudioService.liveDurationMs <= 0) {
                pb.setTag(R.id.nowStripProgress, null);
                pb.setTag(R.id.stripDragUrl, null); // v8.01: identity dies with the dead mark
                cancelStripGlide(pb); // v14.19: a dead drag strands the glide too
            }
            else return;
        }
        int dur = AudioService.liveDurationMs;
        int pos = AudioService.livePositionMs;
        if (dur <= 0 || pos < 0) {
            cancelStripGlide(pb); // v14.19: no live fraction to glide toward
            pb.setProgress(0);
            stripGlideUrl = null;
            return;
        }
        int target = (int) ((long) pos * 1000 / dur);
        String url = AudioService.liveUrl;
        // v14.19: a new talk SNAPS — gliding from the old talk's
        // fraction would sweep the bar backwards across the strip
        // on every auto-advance or skip. Identity by the v13.59
        // rule (SiteHttp.sameTalk: the http retry twin is the same
        // talk, never a change). A first sighting (null last) still
        // glides in from 0 — the bar sweeps to where the talk is,
        // the strip's entrance, not a jump.
        if (stripGlideUrl != null && url != null && !SiteHttp.sameTalk(stripGlideUrl, url)) {
            cancelStripGlide(pb);
            pb.setProgress(target);
        } else {
            glideStripProgress(pb, target);
        }
        stripGlideUrl = url;
    }

    // v14.20 (slice 1674): the strip glyph used to swap instantly —
    // all six strips set the toggle with a bare setText(livePlaying
    // ? ⏸ : ▶) on the 1s tick, while the Room's own play button
    // already fades its icon on every flip (playGlyphFade) and the
    // tap already morphs (Fx.morph in every toggle click handler).
    // Now the strip glyph flips the Room's way: dip the glyph's
    // alpha to 0 over 90ms, swap the text at the dip, fade the new
    // glyph back over 180ms. Change-only (the last glyph rides on
    // the view's own tag, so a steady 1s tick never re-animates);
    // first sighting sets instantly (no dip on first paint, the
    // v14.19 glide rule); alpha-only, so it never fights the
    // tap-time morph that owns scale on this same view; a rapid
    // double-tap cancels the in-flight flip first, restores alpha
    // 1, and the next tick retries from the still-recorded old
    // glyph (the record moves only at the swap, never upfront).
    // The animator rides on the view's own tags (see ids.xml), so
    // it dies with the view. The 12 resume-strip ▶ writes stay
    // instant one-shots — the live tick takes over the moment a
    // session is live.
    public static void flipStripGlyph(android.widget.TextView tg, boolean playing) {
        if (tg == null) return;
        String glyph = playing ? "\u23F8\uFE0E" : "▶";
        Object lastObj = tg.getTag(R.id.stripGlyphLast);
        String last = lastObj instanceof String ? (String) lastObj : null;
        if (glyph.equals(last)) return;
        Object aObj = tg.getTag(R.id.stripGlyphAnim);
        if (aObj instanceof android.animation.Animator) ((android.animation.Animator) aObj).cancel();
        if (last == null) {
            tg.setText(glyph);
            tg.setTag(R.id.stripGlyphLast, glyph);
            return;
        }
        final android.widget.TextView v = tg;
        final String next = glyph;
        android.animation.ObjectAnimator dip =
                android.animation.ObjectAnimator.ofFloat(v, "alpha", v.getAlpha(), 0f);
        dip.setDuration(90);
        dip.setInterpolator(new android.view.animation.DecelerateInterpolator());
        dip.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (v.getTag(R.id.stripGlyphAnim) != a) return;
                v.setText(next);
                v.setTag(R.id.stripGlyphLast, next);
                android.animation.ObjectAnimator rise =
                        android.animation.ObjectAnimator.ofFloat(v, "alpha", 0f, 1f);
                rise.setDuration(180);
                rise.setInterpolator(new android.view.animation.DecelerateInterpolator());
                rise.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a2) {
                        if (v.getTag(R.id.stripGlyphAnim) == a2) {
                            v.setTag(R.id.stripGlyphAnim, null);
                            v.setAlpha(1f);
                        }
                    }
                    @Override public void onAnimationCancel(android.animation.Animator a2) {
                        if (v.getTag(R.id.stripGlyphAnim) == a2) {
                            v.setTag(R.id.stripGlyphAnim, null);
                            v.setAlpha(1f);
                        }
                    }
                });
                v.setTag(R.id.stripGlyphAnim, rise);
                rise.start();
            }
            @Override public void onAnimationCancel(android.animation.Animator a) {
                if (v.getTag(R.id.stripGlyphAnim) == a) {
                    v.setTag(R.id.stripGlyphAnim, null);
                    v.setAlpha(1f);
                }
            }
        });
        tg.setTag(R.id.stripGlyphAnim, dip);
        dip.start();
    }

    // v15.31 (slice 1825): the library row's ▶/⏸ state icon flips
    // instead of snapping — the last transport glyph in the app that
    // still snapped (the strip glyph flips, v14.20; the Room play
    // glyph dips/swaps/rises, v3.20). Same 90ms dip / swap at the
    // bottom / 180ms rise as the strip flip, same DecelerateInterpolator.
    // The caller (DownloadedActivity.flipDlStateGlyph) owns the "last"
    // record per file path — this helper takes no last-glyph tag, because
    // a recycled view's tag would lie about its file's glyph — so it
    // always flips when called, and the caller decides whether a flip
    // is due. The generation guard is the v3.20 lesson: a cancelled
    // dip still fires its end action, so the gen is bumped BEFORE
    // every cancel and a stale end action returns without swapping
    // text or running onRiseEnd; the tag-identity check is the
    // flipStripGlyph second guard. A v3.14 stop-fade or v3.18 settle
    // on this same icon (the ViewPropertyAnimator channel) is
    // cancelled first, or it would fight the dip per frame; the
    // flip's own rise lands at alpha 1, so no fade-back is owed.
    // onRiseEnd runs only when the gen is still current — the
    // caller uses it to start the v2.25 pulse loop once the flip
    // has handed the alpha back. Null onRiseEnd means no post-rise
    // work (the departing row's flip).
    public static void flipDlStateGlyph(final android.widget.TextView v,
            final String glyph, final Runnable onRiseEnd) {
        if (v == null || glyph == null) return;
        Object gObj = v.getTag(R.id.dlStateGlyphGen);
        int gen = (gObj instanceof Integer) ? ((Integer) gObj).intValue() + 1 : 1;
        v.setTag(R.id.dlStateGlyphGen, Integer.valueOf(gen));
        Object aObj = v.getTag(R.id.dlStateGlyphAnim);
        if (aObj instanceof android.animation.Animator) ((android.animation.Animator) aObj).cancel();
        v.animate().cancel();
        final int myGen = gen;
        final android.widget.TextView tv = v;
        final String next = glyph;
        final Runnable after = onRiseEnd;
        android.animation.ObjectAnimator dip =
                android.animation.ObjectAnimator.ofFloat(tv, "alpha", tv.getAlpha(), 0f);
        dip.setDuration(90);
        dip.setInterpolator(new android.view.animation.DecelerateInterpolator());
        dip.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (tv.getTag(R.id.dlStateGlyphAnim) != a) return;
                Object gg = tv.getTag(R.id.dlStateGlyphGen);
                if (!(gg instanceof Integer) || ((Integer) gg).intValue() != myGen) return;
                tv.setText(next);
                android.animation.ObjectAnimator rise =
                        android.animation.ObjectAnimator.ofFloat(tv, "alpha", 0f, 1f);
                rise.setDuration(180);
                rise.setInterpolator(new android.view.animation.DecelerateInterpolator());
                rise.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a2) {
                        if (tv.getTag(R.id.dlStateGlyphAnim) != a2) return;
                        Object gg2 = tv.getTag(R.id.dlStateGlyphGen);
                        if (!(gg2 instanceof Integer) || ((Integer) gg2).intValue() != myGen) return;
                        tv.setTag(R.id.dlStateGlyphAnim, null);
                        tv.setAlpha(1f);
                        if (after != null) after.run();
                    }
                    @Override public void onAnimationCancel(android.animation.Animator a2) {
                        if (tv.getTag(R.id.dlStateGlyphAnim) == a2) {
                            tv.setTag(R.id.dlStateGlyphAnim, null);
                            tv.setAlpha(1f);
                        }
                    }
                });
                tv.setTag(R.id.dlStateGlyphAnim, rise);
                rise.start();
            }
            @Override public void onAnimationCancel(android.animation.Animator a) {
                if (tv.getTag(R.id.dlStateGlyphAnim) == a) {
                    tv.setTag(R.id.dlStateGlyphAnim, null);
                    tv.setAlpha(1f);
                }
            }
        });
        v.setTag(R.id.dlStateGlyphAnim, dip);
        dip.start();
    }

    // v14.21 (slice 1675): the strip title/sub used to swap instantly —
    // all six strips set them with bare setText on the 1s tick, while
    // the Room's own title already pours on a talk change (v2.22/
    // v2.70 fade + rise) and the strip's own glyph now flips (v14.20)
    // and its bar glides (v14.19). Now the title and the sub fade the
    // glyph's way: dip the alpha to 0 over 90ms, swap the text at the
    // dip, fade the new text back over 180ms. Keyed on TALK IDENTITY
    // (the Room URL, or the library path), not on the text: both
    // composed strings change every second while the talk is the
    // same — the title's sleep countdown (M:SS) and in-progress
    // count, the sub's live clock (mm:ss / mm:ss) — so a text-keyed
    // fade would re-animate on every tick. Same-identity ticks write
    // instantly (clock and suffixes land at once, never a stale
    // second); an identity change dips + swaps + rises. First
    // sighting sets instantly (no dip on first paint, the v14.19/
    // v14.20 rule); alpha-only, so it never fights the strip
    // slide-in/out (v14.14), which owns the strip container while
    // this owns the two text views inside it; a rapid double-tap
    // cancels the in-flight fade first, restores alpha 1, and the
    // next tick retries from the still-recorded old identity (the
    // record moves only at the swap, never upfront). A steady tick
    // landing mid-fade returns without writing, so a clock update
    // can never flicker the text under the dip — the next tick
    // refreshes it. The animator + last identity ride on the
    // view's own tags (see ids.xml), so they die with the view.
    // The resume-strip one-shot writes stay instant — static setup
    // while the strip itself slides in; the live tick takes over
    // the moment a session is live.
    // v15.38 (slice 1887): strip the live-ticking parts of a composed
    // strip string so structural forms can be compared — the title's
    // sleep countdown after 😴 (with optional ⏸) ticks every second,
    // and the sub's clock suffix (" · M:SS / M:SS", or the remaining
    // " · −M:SS") ticks every second; everything else in the
    // composition is structure: ★/⬇ flips, sleep appearing,
    // pausing, ending or firing, ⏳ N, · ◈ N, · ended. The sleep
    // clock sits mid-string (⏳/◈/ended follow it), so the 1884
    // sleep-line trailing-time strip cannot see it — the clock is cut
    // out in place and the mark survives, so countdown <-> pause and
    // countdown -> bare mark still read as form changes. Only the
    // comparison uses the stripped form; the view always shows the
    // full text, clocks included.
    public static String stripStripLive(String s) {
        if (s == null) return "";
        String r = s.replaceAll("😴( ⏸)? \\d+(:\\d{2}){1,2}", "😴$1");
        r = r.replaceFirst("\\s*\u00b7\\s*\\d+(:\\d{2}){1,2}\\s*/\\s*\\d+(:\\d{2}){1,2}$", "");
        r = r.replaceFirst("\\s*\u00b7\\s*\u2212\\d+(:\\d{2}){1,2}$", "");
        return r;
    }

    public static void fadeStripText(android.widget.TextView v, String text, String identity) {
        if (v == null) return;
        Object lastObj = v.getTag(R.id.stripTextLast);
        String last = lastObj instanceof String ? (String) lastObj : null;
        if (identity == last || (identity != null && identity.equals(last))) {
            Object aObj0 = v.getTag(R.id.stripTextAnim);
            if (aObj0 instanceof android.animation.Animator) return;
            // v15.38 (slice 1887): same talk, structural change melts
            // — the composed title ticks every second (sleep
            // countdown, sub clock), so this branch used to write
            // every same-talk change instantly: a ★ flip, a ⬇
            // landing, sleep appearing / pausing / ending / firing,
            // a ⏳/◈ count move, the · ended line all swapped in one
            // frame on the visible strip. Compare the structural
            // forms (live clocks stripped, stripStripLive): a
            // tick-only change still writes at once (the v14.95
            // distinction — melting a ticking clock would flicker,
            // and the Room-clock-melt rejection stands),
            // a form change falls through to the dip/swap/rise
            // below (last is non-null here — the identity matched a
            // recorded one — so the first-sighting instant write
            // below is never taken). The view's own text is the old
            // form: the helper records identity only at the swap,
            // so a mid-melt tick returns above and the next tick
            // refreshes the clocks.
            String cur = v.getText() == null ? "" : v.getText().toString();
            if (stripStripLive(text).equals(stripStripLive(cur))) {
                v.setText(text);
                return;
            }
        }
        Object aObj = v.getTag(R.id.stripTextAnim);
        if (aObj instanceof android.animation.Animator) ((android.animation.Animator) aObj).cancel();
        if (last == null) {
            v.setText(text);
            v.setTag(R.id.stripTextLast, identity);
            return;
        }
        final android.widget.TextView tv = v;
        final String next = text;
        final String nextId = identity;
        android.animation.ObjectAnimator dip =
                android.animation.ObjectAnimator.ofFloat(tv, "alpha", tv.getAlpha(), 0f);
        dip.setDuration(90);
        dip.setInterpolator(new android.view.animation.DecelerateInterpolator());
        dip.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (tv.getTag(R.id.stripTextAnim) != a) return;
                tv.setText(next);
                tv.setTag(R.id.stripTextLast, nextId);
                android.animation.ObjectAnimator rise =
                        android.animation.ObjectAnimator.ofFloat(tv, "alpha", 0f, 1f);
                rise.setDuration(180);
                rise.setInterpolator(new android.view.animation.DecelerateInterpolator());
                rise.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a2) {
                        if (tv.getTag(R.id.stripTextAnim) == a2) {
                            tv.setTag(R.id.stripTextAnim, null);
                            tv.setAlpha(1f);
                        }
                    }
                    @Override public void onAnimationCancel(android.animation.Animator a2) {
                        if (tv.getTag(R.id.stripTextAnim) == a2) {
                            tv.setTag(R.id.stripTextAnim, null);
                            tv.setAlpha(1f);
                        }
                    }
                });
                tv.setTag(R.id.stripTextAnim, rise);
                rise.start();
            }
            @Override public void onAnimationCancel(android.animation.Animator a) {
                if (tv.getTag(R.id.stripTextAnim) == a) {
                    tv.setTag(R.id.stripTextAnim, null);
                    tv.setAlpha(1f);
                }
            }
        });
        v.setTag(R.id.stripTextAnim, dip);
        dip.start();
    }

    // v14.43 (slice 1698): the instant writer for a view whose text
    // fadeStripText owns — the Room drawer queue-toggle label melts
    // on a count change through the helper, but a mode flip must
    // land its label instantly (the flip's own 200ms rise owns the
    // motion), and a bare setText there would desync the helper's
    // recorded identity (tag says "◈ Queue · 3" while the view
    // shows "show the full list again", so a later count change
    // back to "◈ Queue · 3" hits the identity==last instant path
    // and the melt is skipped). This stamps the text at once AND
    // records it as the identity, cancelling any in-flight dip/rise
    // first: a dip cancelled mid-flight would otherwise swap its
    // stale text over the flip label at the dip end. The cancel
    // listener restores alpha 1 and clears the tag (it fires before
    // the end listener, which then skips its stale swap — the same
    // cancel-then-end order crossfadeTextColor relies on, v14.42),
    // and the explicit setAlpha(1f) pins it for the already-ended
    // case. Alpha-only, so the tap morph's ViewPropertyAnimator
    // (scale, same view) is untouched. Same "Now" pattern as
    // setTextColorNow (v14.39) / setImageTintNow (v14.41).
    public static void setStripTextNow(final android.widget.TextView v,
            final String text, final String identity) {
        if (v == null) return;
        Object aObj = v.getTag(R.id.stripTextAnim);
        if (aObj instanceof android.animation.Animator)
            ((android.animation.Animator) aObj).cancel();
        v.setText(text);
        v.setTag(R.id.stripTextLast, identity);
        v.setAlpha(1f);
    }

    // v14.22 (slice 1676): the strip photo used to swap instantly —
    // all six strips end their photo sync with a bare
    // ImageLoader.load on a sayadaw change, while everything around
    // it on the same strip already moves: the glyph flips (v14.20),
    // the title/sub fade (v14.21), the bar glides (v14.19) and the
    // ring crossfades (v14.16). Now the photo crossfades the same
    // way: dip the alpha to 0 over 90ms, load the new portrait at
    // the dip, fade the new face back over 180ms. Same 90+180ms
    // Decelerate rhythm as the glyph and the text, so the whole
    // strip transitions in one choreography on a talk change.
    // The load runs AT the dip end, never before it:
    // ImageLoader.load's setInstant cancels the view's animator and
    // forces alpha 1 (the v3.50 hygiene — instant means instant, so
    // scrolling never re-fades a row), so loading first would kill
    // the dip in the same frame. At the dip end the load swaps the
    // bitmap, alpha is dropped to 0 in the same UI-thread callback
    // (no frame ever renders the new face at full alpha) and the
    // rise runs. A never-cached portrait still lands the way the
    // grid does: the placeholder letter fades in over 180ms here,
    // then the download's own 250ms arrival fade (v2.24) takes over
    // when it lands — fadeTo cancels this rise first, so the two
    // fades never stack.
    // The view tag is claimed for the new URL BEFORE the dip
    // starts: ImageLoader's async landing (fadeTo) is tag-guarded,
    // and a stale download for the OLD portrait landing inside the
    // 90ms dip would otherwise cancel the dip — and the dip end is
    // the only place the load runs, while the activity's
    // <field>StripPhotoName already moved to the new name, so no
    // later tick would retry and the strip would keep the old face.
    // Claiming the tag first makes the stale landing fail its guard.
    // A landing for the NEW portrait is legitimate: it cancels the
    // dip but performs the swap itself (sets the new bitmap, fades
    // 0->1), so nothing is stranded. No name record lives in this
    // helper: the activity field is the single record (the
    // change-only guard at each call site), because an async landing
    // can swap the bitmap behind any helper-side record and a return
    // to the previous sayadaw would then no-op while the view shows
    // the other face. First sighting loads instantly (no dip on
    // first paint, the v14.19/v14.20/v14.21 rule) — the strip slides
    // in anyway (v14.14). Alpha-only: the ring crossfade owns the
    // frame's background and the strip slide owns the container;
    // this owns only the photo view's alpha. A rapid change cancels
    // the in-flight dip/rise first, restores alpha 1, and the newer
    // call dips again; every cancel path either is superseded by a
    // newer dip (which loads) or is a legitimate landing (which
    // loaded), so a cancelled dip never strands a load. The animator
    // rides on the view's own tag (see ids.xml), so it dies with
    // the view. Deliberately NOT done: a second ImageView for a
    // true overlap crossfade (new layout in six strips for a small
    // circular portrait — the dip+rise reads the same at this size);
    // touching the Room shrine photo (already crossfades, v14.17 +
    // v2.21); touching grid portraits (cache hits set instantly on
    // purpose, v2.24/v3.50); touching the notification large icon
    // (a cached bitmap, no animator).
    public static void crossfadeStripPhoto(final android.widget.ImageView pv,
            final String url, boolean first, final Runnable swap) {
        if (pv == null) return;
        Object aObj = pv.getTag(R.id.stripPhotoAnim);
        if (aObj instanceof android.animation.Animator) ((android.animation.Animator) aObj).cancel();
        if (first) {
            if (swap != null) swap.run();
            return;
        }
        if (url != null) pv.setTag(url);
        final android.widget.ImageView v = pv;
        android.animation.ObjectAnimator dip =
                android.animation.ObjectAnimator.ofFloat(v, "alpha", v.getAlpha(), 0f);
        dip.setDuration(90);
        dip.setInterpolator(new android.view.animation.DecelerateInterpolator());
        dip.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (v.getTag(R.id.stripPhotoAnim) != a) return;
                if (swap != null) swap.run();
                v.setAlpha(0f);
                android.animation.ObjectAnimator rise =
                        android.animation.ObjectAnimator.ofFloat(v, "alpha", 0f, 1f);
                rise.setDuration(180);
                rise.setInterpolator(new android.view.animation.DecelerateInterpolator());
                rise.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a2) {
                        if (v.getTag(R.id.stripPhotoAnim) == a2) {
                            v.setTag(R.id.stripPhotoAnim, null);
                            v.setAlpha(1f);
                        }
                    }
                    @Override public void onAnimationCancel(android.animation.Animator a2) {
                        if (v.getTag(R.id.stripPhotoAnim) == a2) {
                            v.setTag(R.id.stripPhotoAnim, null);
                            v.setAlpha(1f);
                        }
                    }
                });
                v.setTag(R.id.stripPhotoAnim, rise);
                rise.start();
            }
            @Override public void onAnimationCancel(android.animation.Animator a) {
                if (v.getTag(R.id.stripPhotoAnim) == a) {
                    v.setTag(R.id.stripPhotoAnim, null);
                    v.setAlpha(1f);
                }
            }
        });
        pv.setTag(R.id.stripPhotoAnim, dip);
        dip.start();
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
        // v13.51: clamp the elapsed position to the duration — no
        // live writer can overshoot today, but a future one must
        // never show "65:01 / 65:00" for a tick.
        return stripTimeSuffix(Math.min(posMs, durMs), durMs);
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

    // v14.03 (slice 1098): resume-strip duration — the ↺ strip is the
    // only strip surface that never showed a total: it printed the saved
    // position alone and forced its bar to 0, because "no duration
    // survives a dead session" (v4.60). Since v13.96 the duration DOES
    // survive, in Prefs.talkDurMap (written in AudioService.onPrepared
    // under both forms). Legs, cheapest first: the URL as stored, its
    // alternate-scheme twin (the putBoth twin), then the downloaded
    // path, then the dlMap reverse lookup — a file downloaded AFTER
    // the listen has no path-form duration yet (the v13.96 asymmetry),
    // so the single claimant's stream URL is the last leg; >1
    // claimants read as unknown rather than a possibly wrong talk
    // (the v8.49 exactly-one rule). 0 means never stored — the caller
    // keeps today's exact position-only line and empty bar.
    public static int stripResumeDuration(Context ctx,
            android.content.SharedPreferences sp, String url, String path) {
        if (ctx != null && sp != null) {
            if (url != null && url.length() > 0) {
                int d = Prefs.talkDurGet(ctx, url);
                if (d > 0) return d;
                String alt = SiteHttp.identityUrl(url);
                if (alt != null && !alt.equals(url)) {
                    d = Prefs.talkDurGet(ctx, alt);
                    if (d > 0) return d;
                }
            }
            if (path != null && path.length() > 0) {
                // sim1548 catch: the path leg skips when path==url
                // (already tried as the URL leg above), but the dlMap
                // reverse lookup below must still run — when the saved
                // URL IS the path, dlMap is the only way back to the
                // stream URL the duration was stored under.
                if (!path.equals(url)) {
                    int d = Prefs.talkDurGet(ctx, path);
                    if (d > 0) return d;
                }
                try {
                    org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
                    String fn = new java.io.File(path).getName();
                    java.util.Iterator<String> keys = map.keys();
                    int n = 0;
                    String only = null;
                    while (keys.hasNext()) {
                        String u = keys.next();
                        if (fn.equals(map.optString(u, ""))) { n++; only = u; }
                    }
                    if (n == 1 && only != null) {
                        int d = Prefs.talkDurGet(ctx, only);
                        if (d > 0) return d;
                        String alt = SiteHttp.identityUrl(only);
                        if (alt != null && !alt.equals(only)) {
                            d = Prefs.talkDurGet(ctx, alt);
                            if (d > 0) return d;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    // v14.03 (slice 1098): resume-strip progress — the live strip's
    // syncStripProgress reads the live service, which a dead session
    // has none of, so the resume branch fills from the stored
    // duration instead. Same 1000 scale as the layout max and
    // syncStripProgress, same empty read when the duration was never
    // stored; the position clamps to the duration (the v13.51 rule)
    // and never below a 3% sliver, so a barely-started talk still
    // reads as a visible gold dash (the v13.97 hairline rule).
    public static void stripResumeProgress(android.widget.ProgressBar pb,
            int posMs, int durMs) {
        if (pb == null) return;
        if (durMs <= 0 || posMs < 0) { pb.setProgress(0); return; }
        int frac = (int) ((long) Math.min(posMs, durMs) * 1000 / durMs);
        if (frac < 30) frac = 30;
        pb.setProgress(frac);
    }

    // v5.34: shared mm:ss / H:MM:SS formatter — the strips kept it
    // private, but the Talks rows and Room drawer rows now show a
    // per-talk resume time too, and they must read identically.
    // v13.51: Locale.US on every %02d — the bare format followed
    // the phone language, so on a Burmese phone the minutes and
    // seconds rendered as Myanmar digits while the hour stayed
    // Latin, and the strip clock disagreed with the notification
    // card for the same talk.
    public static String fmtTime(int ms) {
        int total = Math.max(0, ms / 1000);
        if (total >= 3600)
            return (total / 3600) + ":" + String.format(java.util.Locale.US, "%02d", (total % 3600) / 60)
                    + ":" + String.format(java.util.Locale.US, "%02d", total % 60);
        return (total / 60) + ":" + String.format(java.util.Locale.US, "%02d", total % 60);
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
    // v14.05 (sixth pass slice 4): exception detail for user-visible
    // error toasts. Java string-concats a null message as the literal
    // "null", so a null-message exception (an NPE's message is null)
    // used to show "…: null" to the listener. Return "" instead: the
    // toast then shows the bare prefix, which is honest. Deliberately
    // NOT the v1.92 internet_error fallback — that fit the loaders
    // (a load failure is a network failure), but these four call sites
    // are a catalog open and download enqueues, where the failure is
    // usually local (bad URI / unavailable DownloadManager / bad
    // asset), so "internet error" would mislead. Empty counts too:
    // a blank detail adds nothing either way.
    public static String errDetail(Exception e) {
        if (e == null) return "";
        String m = e.getMessage();
        return (m == null || m.length() == 0) ? "" : m;
    }
    // v13.60 (slice 924 F-NEW-1): Zawgyi→Unicode conversion for
    // search queries. Talk titles are Unicode, but a query typed on
    // a Zawgyi keyboard stores the ေ vowel sign (U+1031) BEFORE
    // its consonant (visual order) while Unicode stores it AFTER
    // the consonant cluster, and stores the ဝ consonant as U+1040
    // (Myanmar digit zero) instead of U+101D — so a raw Zawgyi
    // query never matches a Unicode title. Two conservative rules:
    //   (1) a U+1031 followed by a consonant moves after that
    //       consonant's cluster (base + medials U+103B–U+103E +
    //       any U+1039+consonant stacks);
    //   (2) U+1040 becomes U+101D only when neither neighbour is
    //       a Myanmar digit (U+1040–U+1049), so digit queries like
    //       ၁၀ are untouched.
    // Anything unrecognised passes through unchanged, and the
    // U+1039-asat/U+103A difference is deliberately NOT mapped
    // (unverifiable from here — those queries simply miss as
    // before, never mistranslate). Converts the QUERY only;
    // titleMatches tries both the original and the converted
    // query, so a Unicode query can never lose a match it used
    // to find — conversion only ADDS the Zawgyi reading.
    // Verified by TestZg (see fix-960-v1360.md test log): 110
    // cases (100 catalog names + 10 synthetic titles) round-trip
    // Zawgyi→Unicode, Unicode queries still match their own
    // titles, digit queries intact, 0 false positives.
    public static String zawgyiToUnicode(String s) {
        if (s == null) return null;
        int n = s.length();
        boolean any = false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\u1031' && i + 1 < n && isZgBase(s.charAt(i + 1))) { any = true; break; }
            if (c == '\u1040' && !isMyanmarDigitAt(s, i - 1) && !isMyanmarDigitAt(s, i + 1)) { any = true; break; }
        }
        if (!any) return s;
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\u1031' && i + 1 < n && isZgBase(s.charAt(i + 1))) {
                // Move past the following cluster: base + medials +
                // any virama stacks, then the vowel sign.
                int j = i + 1;
                j++; // base consonant
                while (j < n && s.charAt(j) >= '\u103b' && s.charAt(j) <= '\u103e') j++;
                while (j + 1 < n && s.charAt(j) == '\u1039' && isZgBase(s.charAt(j + 1))) {
                    j += 2;
                    while (j < n && s.charAt(j) >= '\u103b' && s.charAt(j) <= '\u103e') j++;
                }
                for (int k = i + 1; k < j; k++) {
                    // A Zawgyi ဝ inside the cluster is U+1040, not
                    // U+101D — convert it here too, same digit guard.
                    char ck = s.charAt(k);
                    if (ck == '\u1040' && !isMyanmarDigitAt(s, k - 1) && !isMyanmarDigitAt(s, k + 1)) b.append('\u101d');
                    else b.append(ck);
                }
                b.append('\u1031');
                i = j - 1;
                continue;
            }
            if (c == '\u1040' && !isMyanmarDigitAt(s, i - 1) && !isMyanmarDigitAt(s, i + 1)) {
                b.append('\u101d');
                continue;
            }
            b.append(c);
        }
        return b.toString();
    }
    private static boolean isZgBase(char c) {
        // U+1040 is Zawgyi's ဝ (see the U+1040 rule above) — a
        // U+1031 before it belongs after it, as in ဝေ.
        return (c >= '\u1000' && c <= '\u1021') || c == '\u1040'
                || c == '\u1025' || c == '\u1026' || c == '\u1027';
    }
    private static boolean isMyanmarDigitAt(String s, int i) {
        if (i < 0 || i >= s.length()) return false;
        char c = s.charAt(i);
        return c >= '\u1040' && c <= '\u1049';
    }
    public static boolean titleMatches(String title, String query) {
        if (title == null || query == null || query.length() == 0) return false;
        try {
            String t = foldDigits(java.text.Normalizer.normalize(title,
                    java.text.Normalizer.Form.NFC).toLowerCase(java.util.Locale.ROOT));
            String q = foldDigits(java.text.Normalizer.normalize(query,
                    java.text.Normalizer.Form.NFC).toLowerCase(java.util.Locale.ROOT));
            if (t.contains(q)) return true;
            // v13.60: the Zawgyi reading of the query — tried in
            // addition to the original, never instead of it.
            String qz = foldDigits(java.text.Normalizer.normalize(zawgyiToUnicode(query),
                    java.text.Normalizer.Form.NFC).toLowerCase(java.util.Locale.ROOT));
            if (!qz.equals(q) && t.contains(qz)) return true;
            String tf = stripWhitespace(t);
            if (tf.contains(stripWhitespace(q))) return true;
            String qzf = stripWhitespace(qz);
            return !qzf.equals(stripWhitespace(q)) && tf.contains(qzf);
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
        // v13.66: lazy-once legacy http-row migration (slice 1052).
        Prefs.migrateLegacyHttpRows66(ctx);
        // v13.65 (F-983-A-1): funnel canonicalisation — a legacy http
        // dlMap key (pre-v13.59 enqueue) reaches here from Downloaded
        // and the card star toggle; banking it verbatim splits the
        // favourite in two so the star never clears. Paths pass through.
        url = SiteHttp.identityUrl(url);
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

    // v14.10 (audit-1367 L2): the Wi-Fi Network object behind the
    // stripIsWifi verdict above — same decision rule, but returning
    // the network instead of a boolean, so a caller can bind a
    // socket to it (Network.openConnection) instead of the default
    // route. Null means "no Wi-Fi to bind to" — the caller must
    // skip the fetch, never fall back to a plain connection.
    // VPN case: the active network is the VPN, so the scan returns
    // the CONNECTED Wi-Fi network underneath it — the bound fetch
    // then rides Wi-Fi directly, outside the tunnel, for one
    // anonymous public page GET (no credentials, generic UA). That
    // is deliberate: the data-saver promise ("the job never spends
    // mobile data") wins over tunnel routing for this fetch, and a
    // VPN Network object would keep working if the VPN re-established
    // over mobile after a Wi-Fi drop, leaving the leak open.
    public static android.net.Network stripWifiNetwork(Context ctx) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                android.net.Network net = cm.getActiveNetwork();
                if (net != null) {
                    android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(net);
                    if (nc != null) {
                        if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))
                            return net;
                        if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                            try {
                                for (android.net.Network n : cm.getAllNetworks()) {
                                    android.net.NetworkInfo ni2 = cm.getNetworkInfo(n);
                                    if (ni2 != null && ni2.isConnected()
                                            && ni2.getType() == android.net.ConnectivityManager.TYPE_WIFI)
                                        return n;
                                }
                            } catch (Exception ignored) {}
                            return null;
                        }
                        return null;
                    }
                }
            }
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            if (info != null && info.isConnected()
                    && info.getType() == android.net.ConnectivityManager.TYPE_WIFI)
                return cm.getActiveNetwork();
            return null;
        } catch (Exception ignored) {
            return null;
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
                if (!SiteHttp.sameTalk(k, url) && filename.equals(map.optString(k, ""))) return true;
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
        // v14.90 (slice 1748): the strip twin of the job's
        // mappedFileName68 — the mapped lookup reads through the
        // shared DlLanded.mappedName (raw, then canonical https,
        // then downgraded http), so a strip re-download of a talk
        // whose dlMap key is spelled under the other scheme reuses
        // its mapped name instead of minting a hash-suffixed
        // duplicate file (the v14.88 chooser case, strip-side).
        // The claim twin below (stripFileClaimedByOther) already
        // reads sameTalk; only the mapped lookup was still raw.
        try {
            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
            String mapped = DlLanded.mappedName(map, url);
            if (mapped != null) return mapped;
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
            if (map.has(SiteHttp.identityUrl(url)) && inProg.contains(map.getString(SiteHttp.identityUrl(url))))
                return STRIP_DL_QUEUED;
            for (String name : stripFileNameCandidates(url)) {
                if (!stripFileClaimedByOther(sp, name, url)
                        && inProg.contains(name))
                    return STRIP_DL_QUEUED;
            }
            // v13.66 (audit-1369 F6 length-gate pass): a 0-byte file is not
            // downloaded — the strip used to show the download mark for a dead
            // file while the Room (length-gated downloadedFiles set) showed no
            // mark and the service refused to play it offline. The length()
            // stat is the same cost class as exists() on the 1s strip tick
            // (v1.57). stripResumeDownloadMark below carries the same gate.
            if (map.has(SiteHttp.identityUrl(url)) && stripDownloadsFile(map.getString(SiteHttp.identityUrl(url))).exists()
                    && stripDownloadsFile(map.getString(SiteHttp.identityUrl(url))).length() > 0)
                return STRIP_DL_HIDDEN;
            // v13.66: same length gate as the mapped branch above (audit-1369 F6).
            for (String name : stripFileNameCandidates(url)) {
                if (!stripFileClaimedByOther(sp, name, url)
                        && stripDownloadsFile(name).exists()
                        && stripDownloadsFile(name).length() > 0)
                    return STRIP_DL_HIDDEN;
            }
            org.json.JSONArray queued = new org.json.JSONArray(sp.getString("dlQueued", "[]"));
            for (int i = 0; i < queued.length(); i++) {
                if (SiteHttp.sameTalk(url, queued.optString(i))) return STRIP_DL_QUEUED;
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
                        // v13.64 (F-1018-B-03 / F-983-B-1): the DM job's
                        // source URL is the transport form (http once this
                        // launch proved TLS refused) while dlMap keys are
                        // https — compare by identity, not raw string
                        // (v13.62 Room/AudioService/Talks shape).
                        if (!added && src != null && dlMap.containsKey(SiteHttp.identityUrl(src))) {
                            String fn = dlMap.get(SiteHttp.identityUrl(src));
                            if (fn != null && fn.length() > 0) out.add(fn);
                        }
                    }
                } finally { c.close(); }
            }
        } catch (Exception ignored) {}
        return out;
    }

    // v14.06 (slice 1101): sounding-row glyph, now with the loading
    // word — the strips' ⚠ truth (v13.94, carried onto the rows in
    // v14.05) plus the strips' buffering word (v13.94): a stream still
    // loading used to read as a normal playing/paused talk on every row
    // surface, because the rows only ever wrote their sounding glyph (▶
    // on the chooser/library/grid, ⏸ on the Room drawer). Error wins,
    // then loading, in the sounding glyph's own slot, so no mark order
    // changes and no new view: not sounding returns "", sounding +
    // failed returns the strips' ⚠ (U+26A0, the same codepoint the
    // strips, the Room title and the widget use — never U+269A),
    // sounding + loading returns the caller's buffering word + space
    // (the bare notif_state_buffering word, no new Lang key), sounding
    // + fine returns the caller's play glyph + space. The gold tile /
    // gold name / pulse stay — the tile says "sounding", the word says
    // "loading", exactly like the strips keep their gold rim under the
    // word. A new play() clears liveTrackError, so the mark can never
    // stick to the next talk; every caller passes the service's static
    // mirror, so no new state is invented here.
    public static String soundingRowMark(boolean sounding, boolean failed, String bufferingWord, String playGlyph) {
        if (!sounding) return "";
        if (failed) return "\u26A0 ";
        if (bufferingWord != null && bufferingWord.length() > 0) return bufferingWord + " ";
        return (playGlyph == null ? "" : playGlyph) + " ";
    }

    // v14.08 (slice 1103): live download percent, folded onto queued
    // talk URLs — the Room drawer rows (v4.24), the Room title (v4.27)
    // and the Downloaded rows (v4.27) all name the number ("⏳ 42%"),
    // but the Talks chooser rows — the screen where a download is
    // STARTED — read a bare ⏳ until the file lands. Same two maps
    // the Room's prune builds (progressBySource keyed by the DM
    // source URL's identity form, progressByFile keyed by the
    // %XX-decoded destination basename, v11.81): a source-URL hit
    // wins, else the dlMap filename for the queued URL; a queued
    // talk with no known total (or no live job row) stays absent,
    // so the row keeps its bare ⏳ rather than a lie. Pure, so the
    // sim pins it 1:1; dlProgressByUrl below pays the one DM query.
    public static java.util.Map<String, Integer> dlProgressFold(
            java.util.Map<String, Integer> bySource,
            java.util.Map<String, Integer> byFile,
            java.util.Set<String> queued,
            java.util.Map<String, String> fileFor) {
        java.util.Map<String, Integer> out = new java.util.HashMap<String, Integer>();
        if (queued == null) return out;
        for (String u : queued) {
            if (u == null) continue;
            Integer pct = (bySource == null) ? null : bySource.get(u);
            if (pct == null && byFile != null && fileFor != null) {
                String fn = fileFor.get(u);
                if (fn != null) pct = byFile.get(fn);
            }
            if (pct != null) out.put(u, pct);
        }
        return out;
    }

    // v14.08 (slice 1103): the chooser row's offline mark — ⬇ wins
    // over ⏳, like the drawer (a saved talk is not still
    // downloading); a queued talk names its live percent when one
    // is known and keeps the bare ⏳ when the total is unknown
    // (Room v4.24 rule), so the row never prints a guessed number.
    public static String dlRowMark(boolean downloaded, boolean queued, Integer pct) {
        if (downloaded) return "⬇ ";
        if (!queued) return "";
        return pct != null ? "⏳ " + pct.intValue() + "% " : "⏳ ";
    }

    // v14.08 (slice 1103): one filtered DownloadManager query for
    // the queued talks' live percents — the stripInProgressDlFilenames
    // shape (RUNNING | PAUSED | PENDING only, same columns, same
    // %XX decode on the destination basename, same identityUrl on
    // the source), folded through dlProgressFold above. Empty queue
    // pays no query at all; a failed query returns an empty map,
    // so the rows fall back to the bare ⏳ instead of a stale
    // number (the Room prune's rule). The dlMap parse gives the
    // filename leg, keyed by identity URL like dlProgressFold wants.
    public static java.util.Map<String, Integer> dlProgressByUrl(
            Context ctx, android.content.SharedPreferences sp, java.util.Set<String> queued) {
        java.util.Map<String, Integer> out = new java.util.HashMap<String, Integer>();
        if (ctx == null || queued == null || queued.isEmpty()) return out;
        try {
            java.util.Map<String, String> fileFor = new java.util.HashMap<String, String>();
            try {
                org.json.JSONObject map = new org.json.JSONObject(sp == null ? "{}" : sp.getString("dlMap", "{}"));
                java.util.Iterator<String> keys = map.keys();
                while (keys.hasNext()) {
                    String u = keys.next();
                    String fn = map.optString(u, "");
                    if (fn != null && fn.length() > 0) fileFor.put(SiteHttp.identityUrl(u), fn);
                }
            } catch (Exception ignored) {}
            java.util.Map<String, Integer> bySource = new java.util.HashMap<String, Integer>();
            java.util.Map<String, Integer> byFile = new java.util.HashMap<String, Integer>();
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            android.database.Cursor c = dm.query(new android.app.DownloadManager.Query()
                    .setFilterByStatus(android.app.DownloadManager.STATUS_RUNNING
                            | android.app.DownloadManager.STATUS_PAUSED
                            | android.app.DownloadManager.STATUS_PENDING));
            if (c != null) {
                try {
                    int srcCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_URI);
                    int uriCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_LOCAL_URI);
                    int doneCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                    int totalCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                    while (c.moveToNext()) {
                        String src = srcCol >= 0 ? c.getString(srcCol) : null;
                        String uri = uriCol >= 0 ? c.getString(uriCol) : null;
                        long done = doneCol >= 0 ? c.getLong(doneCol) : -1;
                        long total = totalCol >= 0 ? c.getLong(totalCol) : -1;
                        if (done < 0 || total <= 0) continue;
                        int pct = (int) (done * 100 / total);
                        if (src != null) bySource.put(SiteHttp.identityUrl(src), pct);
                        if (uri != null) {
                            String dec = android.net.Uri.decode(uri);
                            if (dec == null) dec = uri;
                            int slash = dec.lastIndexOf('/');
                            if (slash >= 0 && slash + 1 < dec.length())
                                byFile.put(dec.substring(slash + 1), pct);
                        }
                    }
                } finally { c.close(); }
            }
            out = dlProgressFold(bySource, byFile, queued, fileFor);
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
            if (map.has(SiteHttp.identityUrl(url)) && inProg.contains(map.getString(SiteHttp.identityUrl(url))))
                return " \u23F3";
            for (String name : stripFileNameCandidates(url)) {
                if (!stripFileClaimedByOther(sp, name, url)
                        && inProg.contains(name))
                    return " \u23F3";
            }
            // v13.66: same length gate as stripDownloadState above (audit-1369 F6).
            if (map.has(SiteHttp.identityUrl(url)) && stripDownloadsFile(map.getString(SiteHttp.identityUrl(url))).exists()
                    && stripDownloadsFile(map.getString(SiteHttp.identityUrl(url))).length() > 0)
                return " \u2B07";
            // v13.66: same length gate as stripDownloadState above (audit-1369 F6).
            for (String name : stripFileNameCandidates(url)) {
                if (!stripFileClaimedByOther(sp, name, url)
                        && stripDownloadsFile(name).exists()
                        && stripDownloadsFile(name).length() > 0)
                    return " \u2B07";
            }
            org.json.JSONArray queued = new org.json.JSONArray(sp.getString("dlQueued", "[]"));
            for (int i = 0; i < queued.length(); i++) {
                if (SiteHttp.sameTalk(url, queued.optString(i))) return " \u23F3";
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
        // v13.68 (audit-1369 F11): dlMap/dlSayadaw/dlQueued read-modify-write - serialize on DlLock.LOCK (see DlLock).
        synchronized (DlLock.LOCK) {
        try {
            String url = stripFavUrl(ctx, sp);
            String title = sp.getString("lastTitle", "");
            if (url == null || title.length() == 0) return false;
            // v13.67 (slice 1053): bank the canonical (https) form —
            // stripFavUrl returns raw lastUrl, so an http sounding
            // talk planted an http dlQueued/dlMap/dlSayadaw row and
            // every reader missed it. Paths pass through.
            url = SiteHttp.identityUrl(url);
            String file = stripDownloadFileName(sp, url);
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            // v13.57 (TLS resilience): the download itself rides the
            // session's transport — plain http once this launch has
            // proven the site's TLS refused. The dlMap key below
            // stays the https URL (identity, not transport).
            android.app.DownloadManager.Request req =
                    new android.app.DownloadManager.Request(android.net.Uri.parse(SiteHttp.siteUrl(url)));
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
                if (SiteHttp.sameTalk(url, queued.optString(i))) { found = true; break; }
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
    // never drift from the app's clock rule again. v13.51:
    // Locale.US on the %02d calls (same Burmese-phone mixed-digit
    // defect as fmtTime above).
    public static String stripResumeTime(int sec) {
        if (sec >= 3600)
            return (sec / 3600) + ":" + String.format(java.util.Locale.US, "%02d", (sec % 3600) / 60)
                    + ":" + String.format(java.util.Locale.US, "%02d", sec % 60);
        return (sec / 60) + ":" + (sec % 60 < 10 ? "0" + (sec % 60) : "" + (sec % 60));
    }

}
