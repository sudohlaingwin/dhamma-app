package com.h2h.dhamma;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * No image library in this toolchain, so portraits are downloaded,
 * circular-cropped and cached by hand. A missing/failed portrait falls
 * back to a gold circle with the sayadaw's first letter.
 */
public class ImageLoader {

    // v1.63: true LRU eviction — the v1.62 cap cleared ALL 64 entries
    // at once, so the next scroll re-decoded every portrait from disk
    // (a stampede). Access-order eviction drops only the least
    // recently used portrait when the 65th arrives.
    // v2.43: memory cap halved (64 -> 32) to pay for the sharper
    // portraits below — 32 x 512px bitmaps is ~32MB worst case,
    // and the disk cache behind it makes a re-decode cheap.
    private static final Map<String, Bitmap> MEM = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
            return size() > 32;
        }
    };
    // v2.43: VISIBLE portrait sharpness (his 2026-10-30 feedback that
    // motion-only premium work does not read as premium in stills).
    // Grid portraits display at 110dp — ~330px on xxhdpi, ~440px on
    // xxxhdpi — so the v1.53 256px decode was always upscaled and
    // soft, worst on the high-density phones most users carry.
    // Decode to 512px before the circular crop instead; a 1000px
    // source now lands at ~500px, at or above the displayed size.
    private static final int MAX_PX = 512;
    // v11.91: portrait downloads were uncapped — a large response
    // was read to EOF into a byte array and decoded, so a server
    // misconfiguration could OOM-kill the app mid-scroll. 8MB is
    // far above any real portrait (small GIFs, 512px renders).
    private static final int MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;
    // v11.91: one raw thread per uncached portrait — a cold fling
    // through the 100-sayadaw grid could leave ~100 download
    // threads alive at once. A 4-thread pool bounds that; the
    // IN_FLIGHT dedup above still guarantees one download per URL.
    private static final java.util.concurrent.ExecutorService DL_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(4);
    // v11.91: disk cache never pruned — one .p512.png per URL plus
    // orphaned pre-v2.43 files and stale .tmp files accumulated
    // forever in getCacheDir()/photos/. Cap the renders at 128
    // (the catalog has 100 sayadaws); the OS still prunes the
    // cache dir itself under pressure.
    private static final int MAX_DISK_FILES = 128;
    private static final Map<String, Bitmap> PLACEHOLDERS = new HashMap<String, Bitmap>();
    // v1.85: portrait downloads already in flight — a fast scroll
    // used to spawn one download thread per row bind for the same
    // not-yet-cached portrait (and re-spawned one per bind while a
    // corrupt disk file kept failing to decode), all writing the
    // same cache file. One thread per URL; the rest reuse the
    // placeholder until the single download lands.
    private static final java.util.Set<String> IN_FLIGHT = new java.util.HashSet<String>();
    // v1.86: views bound while a portrait download is already in
    // flight — the old code returned the placeholder and never told
    // them when the download landed (only the first view got the
    // bitmap), so a fast-scrolled row kept its letter circle until
    // the next scroll forced a rebind. Waiters are updated on
    // completion, guarded by the view tag like the first view.
    private static final Map<String, java.util.List<ImageView>> WAITERS =
            new HashMap<String, java.util.List<ImageView>>();
    // v1.88: ONE lock for IN_FLIGHT + WAITERS together — the two maps
    // used to be locked separately, so a bind landing between a
    // download's waiter-clear and its in-flight release registered a
    // waiter nobody ever woke (the success snapshot was already taken,
    // the failure clear already ran): the row kept its letter circle
    // until a scroll forced a rebind. One critical section for the
    // check-and-register and for the clear makes that interleave
    // impossible — either the bind registers before the clear (and
    // the clear drops it, correctly, as a failed download) or it
    // starts its own download after the clear.
    private static final Object LOAD_LOCK = new Object();

    private static int sampleFor(int w, int h) {
        int sample = 1;
        while (Math.max(w, h) / sample > MAX_PX) sample *= 2;
        return sample;
    }

    private static Bitmap decodeBoundedFile(File f) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), b);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sampleFor(b.outWidth, b.outHeight);
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    private static Bitmap decodeBoundedBytes(byte[] data) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, b);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sampleFor(b.outWidth, b.outHeight);
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    // v3.50: premium recycled-portrait fade hygiene — the instant-set
    // paths below used to swap the bitmap without touching the
    // photo child's own animator. The v2.24 arrival fade runs on
    // the photo ImageView itself, while the v3.47 rebind hygiene
    // only cancels the CARD's animator, so a card recycled
    // mid-fade (fling inside the 250ms arrival window) carried its
    // old fade onto the new sayadaw: a cache-hit portrait — which
    // this file sets instantly on purpose, "so scrolling never
    // re-fades a row" — appeared at the stranger's mid-fade alpha
    // and faded up, and a placeholder did the same. Every instant
    // set now cancels the photo's stale fade and restores alpha 1
    // first, so instant means instant. The async landing below is
    // untouched — fadeTo still cancels and fades from 0 on purpose.
    private static void setInstant(ImageView view, Bitmap bmp) {
        bumpFadeGen(view);
        view.animate().cancel();
        view.setAlpha(1f);
        view.setImageBitmap(bmp);
    }

    public static void load(final Context ctx, final String url, final String name, final ImageView view) {
        if (view == null) return;
        view.setTag(url);
        if (url == null || url.length() == 0) {
            setInstant(view, placeholder(name));
            return;
        }
        synchronized (MEM) {
            Bitmap hit = MEM.get(url);
            if (hit != null) { setInstant(view, hit); return; }
        }
        final File disk = diskFile(ctx.getApplicationContext(), url);
        if (disk.exists()) {
            Bitmap bmp = decodeBoundedFile(disk);
            if (bmp != null) {
                Bitmap circ = circular(bmp);
                // v1.63: LRU eviction (see MEM) — no more clear-all.
                synchronized (MEM) { MEM.put(url, circ); }
                setInstant(view, circ);
                return;
            }
            // v1.85: an undecodable cache file (truncated download,
            // bad PNG) used to sit there forever — every bind decoded
            // null and spawned yet another download thread for it.
            // Drop the bad file so the one download below lands clean.
            try { disk.delete(); } catch (Exception ignored) {}
        }
        setInstant(view, placeholder(name));
        synchronized (LOAD_LOCK) {
            if (!IN_FLIGHT.add(url)) {
                // v1.86: already downloading — wait for it instead of
                // keeping the placeholder until a later rebind.
                java.util.List<ImageView> ws = WAITERS.get(url);
                if (ws == null) { ws = new java.util.ArrayList<ImageView>(); WAITERS.put(url, ws); }
                if (!ws.contains(view)) ws.add(view);
                return;
            }
        }
        // v11.91: the worker thread used to close over the calling
        // Activity (ctx) and hold it until the download ended or
        // timed out (~35s). It only ever needs the application
        // context (cache dir) and a main-thread post for the fade,
        // so capture those instead; the primary view is held weakly
        // (the waiter list still holds its own views by design,
        // cleared on completion below).
        final java.lang.ref.WeakReference<ImageView> viewRef =
                new java.lang.ref.WeakReference<ImageView>(view);
        final android.os.Handler mainHandler =
                new android.os.Handler(android.os.Looper.getMainLooper());
        DL_POOL.execute(new Runnable() {
            @Override public void run() {
                // v1.60: disconnect in a finally — v1.57 only released
                // the connection on the success path, so a failed
                // portrait read (the common case on a bad network)
                // still leaked its socket, unlike the talk loaders
                // fixed the same way in v1.58.
                HttpURLConnection conn = null;
                InputStream in = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(20000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                    // v11.91: refuse an over-cap response before
                    // reading a byte of it (see MAX_DOWNLOAD_BYTES).
                    int contentLen = conn.getContentLength();
                    if (contentLen > MAX_DOWNLOAD_BYTES) return;
                    in = conn.getInputStream();
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    // v11.91: abort past the cap instead of reading
                    // to EOF — the placeholder stays, the finally
                    // below releases the in-flight slot.
                    while ((n = in.read(buf)) > 0) {
                        baos.write(buf, 0, n);
                        if (baos.size() > MAX_DOWNLOAD_BYTES) return;
                    }
                    Bitmap raw = decodeBoundedBytes(baos.toByteArray());
                    // v1.87: an undecodable download used to return
                    // here leaving its waiters registered forever.
                    // v1.88: the finally below now clears waiters +
                    // in-flight atomically (see LOAD_LOCK), so this
                    // path just returns — clearing here separately
                    // would reopen the interleave the lock closes.
                    if (raw == null) {
                        return;
                    }
                    final Bitmap circ = circular(raw);
                    // v1.63: same LRU eviction as the disk-cache path.
                    synchronized (MEM) { MEM.put(url, circ); }
                    // v1.66: the disk-write stream was only closed
                    // on success — a compress failure leaked it, the
                    // same class as the read-stream leaks fixed
                    // in v1.65. Close it in a finally.
                    // v11.67: atomic cache write — the old code wrote
                    // straight to the final cache path, so a bind
                    // landing mid-write decoded a truncated PNG as
                    // null and DELETED the file being written (the
                    // v1.85 corrupt-file cleanup cannot tell a
                    // half-written file from a corrupt one): the
                    // session still got its bitmap from MEM, but the
                    // cache file was lost and the portrait
                    // re-downloaded on the next launch. Write to a
                    // temp file in the same dir and rename, so a
                    // bind only ever sees a complete file or none.
                    File tmp = new File(disk.getParent(), disk.getName() + ".tmp");
                    FileOutputStream out = null;
                    boolean wrote = false;
                    try {
                        disk.getParentFile().mkdirs();
                        out = new FileOutputStream(tmp);
                        wrote = circ.compress(Bitmap.CompressFormat.PNG, 100, out);
                    } catch (Exception ignored) {
                    } finally {
                        if (out != null) { try { out.close(); } catch (Exception ignored) {} }
                    }
                    if (wrote) {
                        if (!tmp.renameTo(disk)) { try { tmp.delete(); } catch (Exception ignored) {} }
                        else pruneDiskCache(disk.getParentFile());
                    } else {
                        try { tmp.delete(); } catch (Exception ignored) {}
                    }
                    // v1.87: snapshot the waiters HERE, on the worker
                    // thread, instead of inside the UI runnable — the
                    // old code only removed them when the UI runnable
                    // ran, so a download that failed after this point
                    // (or a destroyed Activity whose runnable never
                    // ran) left the waiter list — and its dead views —
                    // registered forever. A new bind after this point
                    // hits the MEM cache above, never re-registers.
                    // v1.88: take the in-flight slot in the same
                    // critical section — a bind between the snapshot
                    // and the old finally-only release used to
                    // register a waiter nobody woke (see LOAD_LOCK).
                    final java.util.List<ImageView> waiterSnap;
                    synchronized (LOAD_LOCK) {
                        waiterSnap = WAITERS.remove(url);
                        IN_FLIGHT.remove(url);
                    }
                    // v11.91: wake-up via the main looper, not the
                    // calling Activity's runOnUiThread — the thread
                    // no longer holds the Activity at all (see the
                    // note above DL_POOL.execute). The primary view
                    // is read back weakly; a collected view simply
                    // skips its fade (MEM already holds the bitmap,
                    // so a rebind still lands it).
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            ImageView v = viewRef.get();
                            if (v != null && url.equals(v.getTag())) fadeTo(v, circ);
                            // v1.86: wake the waiters too (tag-guarded,
                            // so a recycled view never gets a stale photo).
                            if (waiterSnap != null) for (ImageView wv : waiterSnap)
                                if (wv != null && url.equals(wv.getTag())) fadeTo(wv, circ);
                        }
                    });
                } catch (Exception ignored) {
                    // Placeholder stays; a failed portrait must never break the list.
                    // v1.87: a failed download used to leave its
                    // waiters registered forever, holding dead views.
                    // v1.88: the finally below clears them with the
                    // in-flight slot in one critical section.
                } finally {
                    // v1.65: a read dying mid-stream used to leak the
                    // stream itself — disconnect() alone does not
                    // reliably close it. Close it here, like the
                    // connection in v1.60.
                    if (in != null) { try { in.close(); } catch (Exception ignored) {} }
                    if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
                    // v1.85: release the in-flight slot (see above) —
                    // a failed download must not block its portrait
                    // forever, and a good one must not leave a stale
                    // slot that suppresses a later re-download.
                    // v1.88: cleared together with the waiters under
                    // LOAD_LOCK, so no bind can slip between the two.
                    synchronized (LOAD_LOCK) {
                        IN_FLIGHT.remove(url);
                        WAITERS.remove(url);
                    }
                }
            }
        });
    }

    // v11.91: keep the photos dir bounded — delete the oldest
    // renders past MAX_DISK_FILES, and any stale .tmp left by a
    // process killed mid-write. Runs on the download worker, once
    // per successful write; failures are ignored (the OS still
    // prunes the cache dir under pressure).
    private static void pruneDiskCache(File dir) {
        try {
            if (dir == null) return;
            File[] files = dir.listFiles();
            if (files == null) return;
            java.util.ArrayList<File> renders = new java.util.ArrayList<File>();
            for (File f : files) {
                if (f.getName().endsWith(".tmp")) { try { f.delete(); } catch (Exception ignored) {} }
                else if (f.getName().endsWith(".p512.png")) renders.add(f);
            }
            if (renders.size() <= MAX_DISK_FILES) return;
            java.util.Collections.sort(renders, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (int i = 0; i < renders.size() - MAX_DISK_FILES; i++) {
                try { renders.get(i).delete(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    // v2.24: premium portrait arrival — a downloaded portrait used
    // to pop instantly from the gold letter placeholder to the photo
    // while the grid card itself was already animating (v2.07), so a
    // slow-network arrival flashed. Only this async landing fades
    // (250ms, decelerate, ending at exactly alpha 1): memory/disk
    // hits and placeholders above are set instantly on purpose, so
    // scrolling never re-fades a row. Cancel first, so a recycled
    // view never inherits a half-faded state.
    // v3.69: premium portrait-fade end-action guard — the v3.62
    // fadeOut guard + v3.63 themeDip guard + v3.64 retry-hide
    // guard + v3.65 wave stop-ease guard + v3.66 glow guard +
    // v3.67 drawer-close guard + v3.68 glow fade-out guard
    // applied to the last unguarded end action in live code
    // (Fx.sheet's close end is dead code — no callers — so this
    // fade is the last one that can actually fire). Every other
    // live end action is now identity- or generation-guarded;
    // this one set alpha 1 unconditionally. Two async portrait
    // landings for the same recycled photo view in quick
    // succession — a fling through the grid while portraits
    // download — make the second fadeTo cancel the first, and
    // under the v3.19 behaviour a stale end landing after the
    // second fade started would snap the new portrait straight
    // to full alpha mid-fade instead of gliding 0->1: a visible
    // pop on exactly the fling path the v3.47 card hygiene and
    // the v3.50 instant-set hygiene fixed for scale and alpha.
    // A generation map per photo view (same WeakHashMap model
    // as Fx.fadeGens/morphGens, so no leak) closes it: fadeTo
    // and setInstant both bump BEFORE the cancel (v3.19
    // ordering), and the fade end sets alpha 1 only while its
    // generation is still current, so a superseded fade can
    // neither pop a newer portrait nor resurrect alpha 1 over
    // an instant set. Natural completion is unchanged (the
    // generation still matches, the portrait still lands at
    // exactly 1). Visual only; bitmaps, tags, cache paths and
    // the 250ms fade itself are untouched.
    private static java.util.WeakHashMap<android.view.View, Integer> fadeGens =
            new java.util.WeakHashMap<android.view.View, Integer>();
    private static int bumpFadeGen(android.view.View v) {
        Integer cur = fadeGens.get(v);
        int gen = (cur == null ? 1 : cur + 1);
        fadeGens.put(v, gen);
        return gen;
    }
    private static void fadeTo(ImageView v, Bitmap bmp) {
        final int gen = bumpFadeGen(v);
        v.animate().cancel();
        v.setImageBitmap(bmp);
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(250)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .setListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) {
                        Integer now = fadeGens.get(v);
                        if (now == null || now != gen) return;
                        v.setAlpha(1f);
                    }
                }).start();
    }

    private static File diskFile(Context ctx, String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            // v2.43: new suffix, not a new folder — the pre-v2.43
            // cache files hold 256px renders, and reusing them
            // would keep every already-seen portrait blurry
            // forever despite the 512px decode above. The old
            // files are simply orphaned (cache dir, system-pruned).
            return new File(new File(ctx.getCacheDir(), "photos"), sb.toString() + ".p512.png");
        } catch (Exception e) {
            return new File(ctx.getCacheDir(), "photo.png");
        }
    }

    // v5.23: cache-only portrait read for the notification large icon —
    // the service must never download on the notification path (a
    // network call there would block the card rebuild), so this
    // returns the already-cached 512px render or null when the
    // sayadaw has never been seen in the grid. Callers fall back
    // to placeholder(name) on null.
    // v11.47: MEM first — the service runs in the same process as
    // the activities (no android:process on the service), so the
    // in-memory LRU is the same cache the grid/strip already read.
    // A disk-only read dropped to the letter circle whenever the
    // system had pruned the cache dir while the app was still
    // alive, even though the face was sitting in MEM and the grid
    // was still showing it — the card disagreed with the app.
    // The MEM render is the exact same 512px circular bitmap the
    // disk file holds, and notiScaled / the custom-card canvas
    // never mutate it, so sharing it here is safe.
    public static Bitmap cached(Context ctx, String url) {
        if (url == null || url.length() == 0) return null;
        synchronized (MEM) {
            Bitmap hit = MEM.get(url);
            if (hit != null) return hit;
        }
        try {
            File f = diskFile(ctx, url);
            if (f == null || !f.isFile()) return null;
            return decodeBoundedFile(f);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static Bitmap circular(Bitmap src) {
        int size = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - size) / 2;
        int y = (src.getHeight() - size) / 2;
        Bitmap square = Bitmap.createBitmap(src, x, y, size, size);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        return out;
    }

    public static Bitmap placeholder(String name) {
        // v1.53: one cached bitmap per initial letter — the old code
        // re-drew a fresh 240px bitmap on every row bind while scrolling.
        // v11.91: the first letter is one code POINT, not one
        // UTF-16 unit — substring(0, 1) split an astral-plane
        // initial (e.g. an emoji) into a lone surrogate and drew
        // a tofu box. Latent today (every catalog name starts
        // with a BMP Myanmar letter), fixed for free.
        String letter = "?";
        if (name != null && name.length() > 0)
            letter = new String(Character.toChars(name.codePointAt(0)));
        synchronized (PLACEHOLDERS) {
            Bitmap hit = PLACEHOLDERS.get(letter);
            if (hit != null) return hit;
        }
        // v2.43: fallback circles rendered at 480px, not 240px —
        // the same upscale softness as the photos: a 240px gold
        // letter circle shown at ~330-440px looked fuzzy in stills.
        // Text size scales with it (110 -> 220) so the letter keeps
        // its exact proportions.
        int size = 480;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        circle.setColor(Color.parseColor("#C9A227"));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, circle);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.parseColor("#121212"));
        text.setTextSize(220f);
        text.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = text.getFontMetrics();
        float cy = size / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(letter, size / 2f, cy, text);
        synchronized (PLACEHOLDERS) { PLACEHOLDERS.put(letter, out); }
        return out;
    }
}
