package com.h2h.dhamma;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * v13.03 — REAL liquid glass (user order 2026-11-07 ~22:40 +07).
 *
 * v13.02 was only gradients painted on top of a flat tint — the user
 * called it out plainly: "ဒါက liquid glass အစစ်မှမဟုတ်ပဲ" (this is not
 * real liquid glass). He was right, so v13.02 was reverted in full and
 * this replaces it with the real mechanism, the one iOS liquid glass
 * uses: the glass shows the content BEHIND it, blurred.
 *
 * How it works (the Dimezis BlurView pattern, hand-rolled so the
 * manual aapt2/javac build needs no new dependency):
 *  1. The glass view's background is a BlurDrawable.
 *  2. On a throttled pre-draw tick the glass view hides itself for one
 *     synchronous UI-thread pass, the whole window (decor view) is
 *     drawn into a 1/8-scale bitmap, the glass view is shown again —
 *     no frame is ever drawn while it is hidden, so there is no flicker.
 *  3. The bitmap is blurred with a pure-Java separable box blur
 *     (radius 4 at 1/8 scale ~= 32px full-scale, two iterations, so it
 *     reads as a soft gaussian, not a blocky mosaic) and the drawable
 *     paints the blurred region behind the glass, clipped to the glass
 *     shape, plus a translucent tint, a top sheen and a gold rim.
 *  4. The snapshot refreshes at most every 200ms while the glass is
 *     visible, so a scrolling list or the animated altar behind the
 *     Room panel stays live behind the blur instead of freezing.
 *
 * Pure Java blur on a 1/8 bitmap (~135x300px on a phone) costs a few
 * ms, so there is no API-level requirement — it runs on minSdk 24,
 * no RenderEffect / RenderScript needed. If a capture ever fails the
 * drawable falls back to the old static glass drawable, so the glass
 * never goes blank.
 *
 * v13.05 hardening: the first version leaked — ATTACHED is a
 * WeakHashMap keyed by the view, but each Glass held its view
 * strongly, so the map could never expunge a dead entry and every
 * rotation stranded a Glass + view + bitmap; the view is now held
 * weakly. The pre-draw listener was also lost when a glass view
 * detached and reattached (a dead observer is never revived), so the
 * blur captured once and froze — it is re-registered on reattach.
 * The per-capture and per-draw allocations (two int[] per blur, a
 * Path + RectF + int[2] per draw) are now reused buffers.
 *
 * Attached surfaces (all one-line attach calls at the existing
 * theme/background sites): Main nav bar + search + mini-player strip,
 * Talks strip + search, Downloaded strip + search, Settings strip,
 * Room bottom panel. The Main sayadaw cards and Talks rows sit over a
 * flat cream background, so blur would be invisible there — they keep
 * the flat card_bg, deliberately.
 */
public class GlassBlur {

    private static final int SCALE = 8;
    private static final int BLUR_RADIUS = 4;
    private static final long MIN_CAPTURE_MS = 200;
    private static final WeakHashMap<View, Glass> ATTACHED = new WeakHashMap<>();

    /** 28dp rounded glass (nav bar, strips, search bars). */
    public static void attach(View glass, boolean night) {
        attach(glass, night, 28f,
                R.drawable.nav_glass, R.drawable.nav_glass_night);
    }

    /** 28dp rounded glass with the search-bar fallback drawables. */
    public static void attachSearch(View glass, boolean night) {
        attach(glass, night, 28f,
                R.drawable.search_bg, R.drawable.search_bg_night);
    }

    /** Room bottom panel: 24dp top corners, square bottom, dark tint always
     *  (the panel sits over the dark altar in both themes). */
    public static void attachPanel(View glass) {
        attachTop(glass, true, 24f, R.drawable.glass_panel, R.drawable.glass_panel);
    }

    public static void attach(View glass, boolean night, float radiusDp,
                              int fallbackDayRes, int fallbackNightRes) {
        attachInternal(glass, night, radiusDp, radiusDp, radiusDp, radiusDp,
                fallbackDayRes, fallbackNightRes);
    }

    private static void attachTop(View glass, boolean night, float topDp,
                                  int fallbackDayRes, int fallbackNightRes) {
        attachInternal(glass, night, topDp, topDp, 0f, 0f,
                fallbackDayRes, fallbackNightRes);
    }

    private static synchronized void attachInternal(View glass, boolean night,
            float tlDp, float trDp, float brDp, float blDp,
            int fallbackDayRes, int fallbackNightRes) {
        if (glass == null) return;
        Glass g = ATTACHED.get(glass);
        if (g != null) {
            g.setNight(night, tlDp, trDp, brDp, blDp, fallbackDayRes, fallbackNightRes);
            g.refresh();
            return;
        }
        g = new Glass(glass, night, tlDp, trDp, brDp, blDp,
                fallbackDayRes, fallbackNightRes);
        ATTACHED.put(glass, g);
        g.start();
    }

    // ------------------------------------------------------------------
    // One attached glass surface.
    // ------------------------------------------------------------------
    private static class Glass {
        // v13.05: WEAK — the static ATTACHED map is a WeakHashMap keyed
        // by the view, and a strong value->key reference pins every
        // entry forever (the map can never expunge a key its own value
        // holds). A destroyed activity's views then strand their Glass
        // + blurred bitmap in the map on every rotation. With a weak
        // ref the entry expunges on the map's next access instead.
        final WeakReference<View> glassRef;
        final BlurDrawable drawable;
        final ViewTreeObserver.OnPreDrawListener preDraw =
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        refresh();
                        return true;
                    }
                };
        final View.OnAttachStateChangeListener attachListener =
                new View.OnAttachStateChangeListener() {
                    // v13.05: a detached view gets a NEW ViewTreeObserver
                    // on reattach — the pre-draw listener registered on
                    // the old (now dead) observer is gone, so re-register
                    // here or the blur captures once and then freezes.
                    @Override public void onViewAttachedToWindow(View v) {
                        registerPreDraw();
                        refresh();
                    }
                    @Override public void onViewDetachedFromWindow(View v) { stop(); }
                };
        final Runnable captureRunnable = new Runnable() {
            @Override public void run() { capture(); }
        };
        ViewTreeObserver observer;
        Bitmap blurred;
        // v13.05: scratch reused across captures — boxBlur used to
        // allocate two fresh int[] (~160KB each) every 200ms tick.
        int[] blurA, blurB;
        boolean capturePending;
        long lastCaptureMs;

        /** The attached view, or null once its activity is gone. */
        View view() { return glassRef.get(); }

        Glass(View glass, boolean night,
              float tlDp, float trDp, float brDp, float blDp,
              int fallbackDayRes, int fallbackNightRes) {
            this.glassRef = new WeakReference<>(glass);
            this.drawable = new BlurDrawable(this);
            setNight(night, tlDp, trDp, brDp, blDp, fallbackDayRes, fallbackNightRes);
            glass.setBackground(drawable);
        }

        void setNight(boolean night,
                      float tlDp, float trDp, float brDp, float blDp,
                      int fallbackDayRes, int fallbackNightRes) {
            View v = view();
            if (v == null) return;
            float d = v.getResources().getDisplayMetrics().density;
            drawable.setRadii(new float[]{
                    tlDp * d, tlDp * d, trDp * d, trDp * d,
                    brDp * d, brDp * d, blDp * d, blDp * d});
            drawable.setNight(night, fallbackDayRes, fallbackNightRes);
        }

        void start() {
            View v = view();
            if (v == null) return;
            v.addOnAttachStateChangeListener(attachListener);
            registerPreDraw();
            refresh();
        }

        void registerPreDraw() {
            View v = view();
            if (v == null) return;
            ViewTreeObserver o = v.getViewTreeObserver();
            if (o != null && o.isAlive()) {
                // Remove-then-add: the first attach already merged the
                // floating observer's listener into this one, so a bare
                // add here would register preDraw twice.
                o.removeOnPreDrawListener(preDraw);
                observer = o;
                observer.addOnPreDrawListener(preDraw);
            }
        }

        void stop() {
            if (observer != null && observer.isAlive())
                observer.removeOnPreDrawListener(preDraw);
            observer = null;
        }

        void refresh() {
            if (capturePending) return;
            View v = view();
            if (v == null) return;
            if (v.getWindowToken() == null || !v.isShown()) return;
            if (SystemClock.uptimeMillis() - lastCaptureMs < MIN_CAPTURE_MS) return;
            capturePending = true;
            v.post(captureRunnable);
        }

        void capture() {
            capturePending = false;
            View v = view();
            if (v == null) return;
            try {
                View root = v.getRootView();
                if (root == null || v.getWindowToken() == null) return;
                int rw = root.getWidth();
                int rh = root.getHeight();
                if (rw <= 0 || rh <= 0) return;
                int w = Math.max(1, rw / SCALE);
                int h = Math.max(1, rh / SCALE);
                if (blurred == null || blurred.getWidth() != w || blurred.getHeight() != h) {
                    if (blurred != null) blurred.recycle();
                    blurred = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    blurA = null;
                    blurB = null;
                }
                int n = w * h;
                if (blurA == null || blurA.length != n) {
                    blurA = new int[n];
                    blurB = new int[n];
                }
                Canvas c = new Canvas(blurred);
                c.scale(1f / SCALE, 1f / SCALE);
                int vis = v.getVisibility();
                v.setVisibility(View.INVISIBLE);
                try {
                    root.draw(c);
                } finally {
                    v.setVisibility(vis);
                }
                boxBlur(blurred, BLUR_RADIUS, blurA, blurB);
                drawable.setBitmap(blurred, SCALE);
                v.invalidate();
                lastCaptureMs = SystemClock.uptimeMillis();
            } catch (Exception e) {
                // Capture must never crash the UI: the drawable keeps its
                // static-glass fallback and the next tick retries.
            }
        }
    }

    // ------------------------------------------------------------------
    // The drawable: blurred backdrop + tint + sheen + gold rim.
    // ------------------------------------------------------------------
    private static class BlurDrawable extends Drawable {
        final Glass owner;
        final Paint bitmapPaint = new Paint();
        final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // v13.05: reused across draws — draw() used to allocate a Path,
        // a RectF and an int[2] on every frame the glass invalidated.
        final Path roundPath = new Path();
        final RectF roundRect = new RectF();
        final int[] loc = new int[2];
        Bitmap bmp;
        int scale = SCALE;
        float[] radii = new float[8];
        Drawable fallback;
        int alpha = 255;
        Rect lastBounds = new Rect();
        Shader lastSheen;

        BlurDrawable(Glass owner) {
            this.owner = owner;
            bitmapPaint.setFilterBitmap(true);
            rimPaint.setStyle(Paint.Style.STROKE);
        }

        void setRadii(float[] radii) {
            this.radii = radii;
            invalidateSelf();
        }

        void setNight(boolean night, int fallbackDayRes, int fallbackNightRes) {
            tintPaint.setColor(night ? 0x66020201 : 0x59FFFFFF);
            rimPaint.setColor(night ? 0x66C9A227 : 0x99C9A227);
            View v = owner.view();
            if (v == null) return;
            rimPaint.setStrokeWidth(
                    v.getResources().getDisplayMetrics().density);
            try {
                fallback = v.getResources().getDrawable(
                        night ? fallbackNightRes : fallbackDayRes, null);
            } catch (Exception e) {
                fallback = null;
            }
            invalidateSelf();
        }

        void setBitmap(Bitmap bmp, int scale) {
            this.bmp = bmp;
            this.scale = scale;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            roundPath.reset();
            roundRect.set(b);
            roundPath.addRoundRect(roundRect, radii, Path.Direction.CW);
            if (bmp != null && !bmp.isRecycled()) {
                View gv = owner.view();
                if (gv == null) return;
                gv.getLocationInWindow(loc);
                Rect src = new Rect(
                        loc[0] / scale, loc[1] / scale,
                        (loc[0] + b.width()) / scale,
                        (loc[1] + b.height()) / scale);
                src.intersect(0, 0, bmp.getWidth(), bmp.getHeight());
                if (!src.isEmpty()) {
                    canvas.save();
                    canvas.clipPath(roundPath);
                    canvas.drawBitmap(bmp, src, b, bitmapPaint);
                    canvas.restore();
                }
            } else if (fallback != null) {
                fallback.setBounds(b);
                fallback.setAlpha(alpha);
                fallback.draw(canvas);
                return;
            }
            // Translucent tint over the blur.
            tintPaint.setAlpha(alpha);
            canvas.drawPath(roundPath, tintPaint);
            // Top sheen: bright band fading down over the top half.
            if (!b.equals(lastBounds)) {
                lastBounds.set(b);
                lastSheen = new LinearGradient(0, b.top, 0, b.top + b.height() * 0.5f,
                        new int[]{0x59FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 1f}, Shader.TileMode.CLAMP);
            }
            sheenPaint.setShader(lastSheen);
            sheenPaint.setAlpha(alpha);
            canvas.drawPath(roundPath, sheenPaint);
            // Gold rim.
            rimPaint.setAlpha(alpha);
            canvas.drawPath(roundPath, rimPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------------
    // Separable box blur, two iterations ~= soft gaussian. Operates
    // in-place on a small bitmap; sums fit easily in int. The scratch
    // arrays are the caller's reused buffers (v13.05), never allocated
    // per capture.
    // ------------------------------------------------------------------
    private static void boxBlur(Bitmap bmp, int radius, int[] src, int[] tmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        bmp.getPixels(src, 0, w, 0, 0, w, h);
        boxBlurPass(src, tmp, w, h, radius, true);
        boxBlurPass(tmp, src, w, h, radius, false);
        boxBlurPass(src, tmp, w, h, radius, true);
        boxBlurPass(tmp, src, w, h, radius, false);
        bmp.setPixels(src, 0, w, 0, 0, w, h);
    }

    private static void boxBlurPass(int[] in, int[] out, int w, int h,
                                    int radius, boolean horizontal) {
        int div = radius * 2 + 1;
        if (horizontal) {
            for (int y = 0; y < h; y++) {
                int row = y * w;
                int sumA = 0, sumR = 0, sumG = 0, sumB = 0;
                for (int x = -radius; x <= radius; x++) {
                    int px = in[row + clamp(x, 0, w - 1)];
                    sumA += (px >>> 24) & 0xFF;
                    sumR += (px >>> 16) & 0xFF;
                    sumG += (px >>> 8) & 0xFF;
                    sumB += px & 0xFF;
                }
                for (int x = 0; x < w; x++) {
                    out[row + x] = (((sumA + div / 2) / div) << 24)
                            | (((sumR + div / 2) / div) << 16)
                            | (((sumG + div / 2) / div) << 8)
                            | ((sumB + div / 2) / div);
                    int remove = in[row + clamp(x - radius, 0, w - 1)];
                    int add = in[row + clamp(x + radius + 1, 0, w - 1)];
                    sumA += ((add >>> 24) & 0xFF) - ((remove >>> 24) & 0xFF);
                    sumR += ((add >>> 16) & 0xFF) - ((remove >>> 16) & 0xFF);
                    sumG += ((add >>> 8) & 0xFF) - ((remove >>> 8) & 0xFF);
                    sumB += (add & 0xFF) - (remove & 0xFF);
                }
            }
        } else {
            for (int x = 0; x < w; x++) {
                int sumA = 0, sumR = 0, sumG = 0, sumB = 0;
                for (int y = -radius; y <= radius; y++) {
                    int px = in[clamp(y, 0, h - 1) * w + x];
                    sumA += (px >>> 24) & 0xFF;
                    sumR += (px >>> 16) & 0xFF;
                    sumG += (px >>> 8) & 0xFF;
                    sumB += px & 0xFF;
                }
                for (int y = 0; y < h; y++) {
                    out[y * w + x] = (((sumA + div / 2) / div) << 24)
                            | (((sumR + div / 2) / div) << 16)
                            | (((sumG + div / 2) / div) << 8)
                            | ((sumB + div / 2) / div);
                    int remove = in[clamp(y - radius, 0, h - 1) * w + x];
                    int add = in[clamp(y + radius + 1, 0, h - 1) * w + x];
                    sumA += ((add >>> 24) & 0xFF) - ((remove >>> 24) & 0xFF);
                    sumR += ((add >>> 16) & 0xFF) - ((remove >>> 16) & 0xFF);
                    sumG += ((add >>> 8) & 0xFF) - ((remove >>> 8) & 0xFF);
                    sumB += (add & 0xFF) - (remove & 0xFF);
                }
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
