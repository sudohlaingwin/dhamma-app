package com.h2h.dhamma;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * v15.29 (slice 1813): the now-playing equalizer — three gold bars
 * that dance while a talk sounds and rest, low and equal, while it
 * is paused. The Spotify / YouTube Music / Apple Music indicator
 * the Talks chooser sounding row was missing: its ▶ prefix (v4.89)
 * + gold title + crossfading gold card (v15.24) all read as a still
 * image while playing, while the Main grid breathes (v3.99), the
 * Library row's state icon pulses (v2.25) and the Room drawer
 * pulses its playing title (v2.27). The bars live in the row's
 * 48dp number slot (row_talk.xml) — the number returns when the
 * row stops sounding, exactly like Spotify swapping the track
 * number for bars. Decorative: no focus, no content description.
 *
 * setPlaying is idempotent — a tick rebind of the same sounding
 * row never restarts the dance — and the amplitude ramps 0 to 1
 * over 180ms from start, so a resume grows out of the rest pose
 * instead of popping to staggered heights. Stopping eases the dance
 * down over the same 180ms ramp and only then parks at rest
 * (v15.32): a pause used to snap the bars flat in full view while
 * the sounding row still showed them. Detach cancels too, so a
 * recycled row can never keep a dead animator running.
 */
public class EqBarsView extends View {
    private static final int BAR_COUNT = 3;
    private static final int GOLD = 0xFFC9A227;
    private static final long CYCLE_MS = 1000;
    private static final long RAMP_MS = 180;
    private static final float REST_FRAC = 0.25f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private ValueAnimator animator;
    private ValueAnimator stopAnim; // v15.32: the pause decay — see setPlaying.
    private float stopRamp = 1f; // v15.32: surviving dance amplitude, 1 = full dance, 0 = rest pose.
    private long startMs;
    private boolean playing;
    private final float[] heights = new float[BAR_COUNT];

    public EqBarsView(Context c) { super(c); density = c.getResources().getDisplayMetrics().density; init(); }
    public EqBarsView(Context c, AttributeSet a) { super(c, a); density = c.getResources().getDisplayMetrics().density; init(); }
    public EqBarsView(Context c, AttributeSet a, int s) { super(c, a, s); density = c.getResources().getDisplayMetrics().density; init(); }

    private void init() {
        paint.setColor(GOLD);
        paint.setStyle(Paint.Style.FILL);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        rest();
    }

    private void rest() {
        for (int i = 0; i < BAR_COUNT; i++) heights[i] = REST_FRAC;
    }

    /** Dance while sounding, rest while paused. Safe to call on every bind. */
    public void setPlaying(boolean p) {
        if (p == playing) return;
        playing = p;
        if (p) {
            // v15.32 (slice 1826): a resume mid-decay cancels the
            // decay and restores full amplitude before the dance
            // restarts, so the start ramp grows from rest exactly
            // as before and no stale end action can park the new
            // dance at rest (the instance guard below).
            if (stopAnim != null) { stopAnim.cancel(); stopAnim = null; }
            stopRamp = 1f;
            startMs = SystemClock.uptimeMillis();
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(CYCLE_MS);
            animator.setInterpolator(new LinearInterpolator());
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    float frac = (Float) a.getAnimatedValue();
                    long elapsed = SystemClock.uptimeMillis() - startMs;
                    float ramp = elapsed >= RAMP_MS ? 1f : (float) elapsed / RAMP_MS;
                    for (int i = 0; i < BAR_COUNT; i++) {
                        float full = REST_FRAC + (1f - REST_FRAC)
                                * Math.abs((float) Math.sin(Math.PI * (frac + i * 0.37f)));
                        // v15.32 (slice 1826): the pause decay rides the same
                        // formula — at stopRamp 1 the paint is pixel-identical,
                        // and as the decay runs the bars keep dancing while
                        // shrinking toward rest instead of freezing flat.
                        heights[i] = REST_FRAC + (full - REST_FRAC) * ramp * stopRamp;
                    }
                    invalidate();
                }
            });
            animator.start();
        } else {
            // v15.32 (slice 1826): the bars ease down to rest instead
            // of snapping flat — the start ramp's mirror. The dance
            // keeps running while stopAnim decays its amplitude 1
            // -> 0 over the same 180ms, and only the decay's end
            // stops the dance and parks the rest pose, so a pause
            // while the row still sounds decays in full view and a
            // real stop decays under the slot's 250ms fade-out. A
            // stop with no dance parks at rest at once (defensive —
            // playing is only ever true beside a live animator).
            if (animator == null) { rest(); invalidate(); return; }
            stopAnim = ValueAnimator.ofFloat(stopRamp, 0f);
            stopAnim.setDuration(RAMP_MS);
            stopAnim.setInterpolator(new LinearInterpolator());
            stopAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    stopRamp = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            stopAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    if (a != stopAnim) return; // v3.20 lesson: a cancelled decay still fires its end action, so the instance check stands between it and the teardown of a newer dance.
                    stopAnim = null;
                    if (animator != null) { animator.cancel(); animator = null; }
                    stopRamp = 1f;
                    rest();
                    invalidate();
                }
            });
            stopAnim.start();
        }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) { animator.cancel(); animator = null; }
        // v15.32 (slice 1826): a decay in flight dies with the view
        // — a recycled row can never keep a dead decay running, and
        // the amplitude resets so the next dance starts full.
        if (stopAnim != null) { stopAnim.cancel(); stopAnim = null; }
        playing = false;
        stopRamp = 1f;
        rest();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float barW = 3f * density;
        float gap = 3f * density;
        float maxH = 16f * density;
        float totalW = BAR_COUNT * barW + (BAR_COUNT - 1) * gap;
        float x = (getWidth() - totalW) / 2f;
        float cy = getHeight() / 2f;
        float r = 1.5f * density;
        for (int i = 0; i < BAR_COUNT; i++) {
            float h = Math.max(1f, heights[i] * maxH);
            rect.set(x, cy - h / 2f, x + barW, cy + h / 2f);
            canvas.drawRoundRect(rect, r, r, paint);
            x += barW + gap;
        }
    }
}
