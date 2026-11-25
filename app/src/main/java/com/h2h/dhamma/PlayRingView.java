package com.h2h.dhamma;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * v15.94 (slice 2225): the gold progress arc around the Room play button — a thin gold arc
 * sweeping 0 to 360 degrees around the play control as the talk plays, over a faint full
 * track circle, in the app's own gold. The arc starts at the top and grows clockwise, so a
 * half-swept arc reads as halfway through the talk in a still. The painted fraction is
 * quantized to per-mille and change-only, so a steady 500ms poll never redraws; the caller
 * owns visibility (gone until a talk has a duration). No state beyond the painted fraction;
 * no Prefs, no Lang, no listener, no animator — the poll is the only writer.
 */
public class PlayRingView extends View {
    private static final int GOLD = 0xFFC9A227;
    private static final int TRACK = 0x33C9A227;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int perMille = 0;

    public PlayRingView(Context ctx) {
        super(ctx);
        init();
    }

    public PlayRingView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    private void init() {
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(dp(3));
        trackPaint.setColor(TRACK);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(dp(3));
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(GOLD);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** The painted fraction, 0..1, clamped; change-only on the per-mille value, so a steady poll never redraws. */
    public void setFraction(float f) {
        if (Float.isNaN(f)) f = 0f;
        int pm = (int) (1000f * Math.max(0f, Math.min(1f, f)));
        if (pm == perMille) return;
        perMille = pm;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float r = Math.min(w, h) / 2f - dp(3) / 2f;
        float cx = w / 2f;
        float cy = h / 2f;
        canvas.drawCircle(cx, cy, r, trackPaint);
        if (perMille > 0) canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, perMille * 360f / 1000f, false, arcPaint);
    }
}
