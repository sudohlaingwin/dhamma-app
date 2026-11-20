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
 * instead of popping to staggered heights. Stopping cancels the
 * animator and repaints the rest pose at once. Detach cancels too,
 * so a recycled row can never keep a dead animator running.
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
                        heights[i] = REST_FRAC + (full - REST_FRAC) * ramp;
                    }
                    invalidate();
                }
            });
            animator.start();
        } else {
            if (animator != null) { animator.cancel(); animator = null; }
            rest();
            invalidate();
        }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) { animator.cancel(); animator = null; }
        playing = false;
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
