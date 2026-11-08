package com.h2h.dhamma;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class RoomView extends View {

    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Path path = new Path();
    private RectF rect = new RectF();
    private Random random = new Random();
    private boolean lit = false;
    // v4.11: sleep-fade dim — the v4.04 bedtime fade lowered the
    // volume to silence over ~3s but the altar lamps stayed fully
    // lit until the fade-end pause snapped them dark, so the
    // shrine looked wide awake while the sound died. While this
    // flag is set the animator's lit target is 0 even though lit
    // is still true, so flames, halo and smoke fade out with the
    // audio over the 600ms ramp instead of snapping at the end.
    // The flag itself never animates — it only retargets the
    // existing ramp, and clearing it (fade cancelled by a new
    // talk) relights the lamps through the same ramp.
    private boolean sleepFading = false;
    // v2.13: light-transition blend — play/pause used to snap the
    // flames, halo and paused dim on/off in a single frame. litBlend
    // ramps 0<->1 over ~600ms (stepped by the 50ms animator) and every
    // photo-scene light element scales with it, so lighting a talk
    // feels like lamps being lit, not a switch. The lit boolean keeps
    // its exact logic role (smoke spawn/drain, ember state); only the
    // drawn intensities follow the blend. v2.77: the cartoon fallback
    // (photo assets missing) follows the same blend — before it, its
    // halo, flames, embers, lanterns and water shimmer all snapped
    // with lit while the photo scene crossfaded, so a fallback Room
    // lit and unlit like a switch.
    private float litBlend = 0f;
    // v2.26: Ken Burns drift clock — advanced ONLY while a talk is
    // playing (the animator ticks every 50ms while lit), so the
    // shrine photo zooms slowly while listening and freezes exactly
    // where it is on pause, with no time-jump on resume/reattach.
    // Wall-clock time would jump after a pause; this never does.
    private long driftMs = 0;
    private float w, h, u;
    private LinearGradient wallGrad;
    private List<Smoke> smokes = new ArrayList<Smoke>();
    // v3.89: smoke puffs pending a size — restoreSmoke() runs in
    // onCreate before the view is measured, so fractions wait here
    // until onSizeChanged can turn them back into pixels.
    private java.util.List<float[]> pendingSmoke;
    // v3.89: true while an animator chain is posted or running, so
    // a second poster (onSizeChanged applying pending smoke) never
    // starts a duplicate chain that would double the drift clock.
    private boolean animatorLive;
    private int frame = 0;

    private static class Smoke {
        float x, y, startY, alpha, size, phase, speed, bx;
        Smoke(float x, float y, float phase) {
            this.x = x; this.y = y; this.startY = y; this.alpha = 0.55f; this.size = 7f; this.phase = phase;
            this.speed = 3.5f; this.bx = x;
        }
        // v2.07: varied rise speed + start size, so wisps do not
        // march in lockstep like the old uniform ones.
        Smoke(float x, float y, float phase, float speed, float size) {
            this.x = x; this.y = y; this.startY = y; this.alpha = 0.55f; this.size = size; this.phase = phase;
            this.speed = speed; this.bx = x;
        }
    }

    // ---- 30 room themes: palette + motif, Myanmar names ----
    public static class Theme {
        String name; int motif;
        int wallTop, wallBottom, floor, arch, altarWood, goldLight, goldDark;
        int robe, halo, flowerA, flowerB, vase, lampLight, candleLight;
        int waterLight, waterDark, mat, cushion;
        Theme(String n, int m, String wt, String wb, String fl, String ar, String aw,
              String gl, String gd, String rb, String ha, String fa, String fb, String va,
              String ll, String cl, String wl, String wd, String mt, String cu) {
            name = n; motif = m;
            wallTop = Color.parseColor(wt); wallBottom = Color.parseColor(wb);
            floor = Color.parseColor(fl); arch = Color.parseColor(ar);
            altarWood = Color.parseColor(aw); goldLight = Color.parseColor(gl);
            goldDark = Color.parseColor(gd); robe = Color.parseColor(rb);
            halo = Color.parseColor(ha); flowerA = Color.parseColor(fa);
            flowerB = Color.parseColor(fb); vase = Color.parseColor(va);
            lampLight = Color.parseColor(ll); candleLight = Color.parseColor(cl);
            waterLight = Color.parseColor(wl); waterDark = Color.parseColor(wd);
            mat = Color.parseColor(mt); cushion = Color.parseColor(cu);
        }
    }

    public static final Theme[] THEMES = {
        new Theme("ရွှေဘုရားစင်", 0, "#3A2415", "#5A3A20", "#6B4A2F", "#7A5A2E", "#5A3A20", "#FFE98A", "#D4AF37", "#8E2F1B", "#FFD75A", "#E87A9A", "#F2C94C", "#2F5F8F", "#D9B25A", "#FFF3C4", "#BFE3F2", "#5FA8C8", "#7A2E1F", "#E67E22"),
        new Theme("ညအလင်း", 2, "#101828", "#1E2A44", "#2A2118", "#3A4A6E", "#3A2A1A", "#FFE98A", "#D4AF37", "#5B2A86", "#C9B8FF", "#E87A9A", "#F2C94C", "#3A5F8F", "#D9B25A", "#FFF3C4", "#BFE3F2", "#5FA8C8", "#4A1A2E", "#8E6EC8"),
        new Theme("နေထွက်", 3, "#4A2A1A", "#7A4A22", "#6B4A2F", "#8A5A2E", "#5A3A20", "#FFF0A0", "#E8B93A", "#C85A1E", "#FFB84A", "#FF8A9A", "#FFD54A", "#2F7A5F", "#E8C25A", "#FFF3C4", "#CFE8F2", "#6AB0C8", "#8A3A1E", "#F08A2E"),
        new Theme("သစ်သား နွေးထွေး", 1, "#4A2E1A", "#6E4426", "#7A5636", "#8A6A3E", "#6E4426", "#FFE98A", "#C8A030", "#7A2E1F", "#FFD75A", "#D87A8A", "#E8C94C", "#4A6A3E", "#C8A24A", "#F5E8C0", "#BFE3F2", "#5FA8C8", "#6E2A1E", "#D87A3E"),
        new Theme("ပန်းရောင် နွေ", 6, "#5A2A30", "#7A3A44", "#6B4A3A", "#8A5A5E", "#5A2A30", "#FFE98A", "#D4AF37", "#B83A5E", "#FFB8C8", "#FF7AA8", "#FFD54A", "#8A5F7A", "#D9B25A", "#FFE8E8", "#F2D8E8", "#C88AB0", "#8A2A44", "#FF8AA8"),
        new Theme("ကန်ရေပြာ", 7, "#1A3A44", "#2A5A6A", "#3A4A44", "#3A6A7A", "#2A3A34", "#FFF0A0", "#D4AF37", "#1E6A6A", "#8AD8E8", "#FFFFFF", "#F2C94C", "#2A7A8A", "#D9B25A", "#E8F8F8", "#D8F0F8", "#3A9AC8", "#1E4A5A", "#3AB8C8"),
        new Theme("မီးရောင်မှိန်", 5, "#1E140E", "#33241A", "#2A1E14", "#4A3423", "#33241A", "#FFE98A", "#C8A030", "#6E2315", "#FFC85A", "#E87A9A", "#F2C94C", "#2F5F8F", "#E8C25A", "#FFF3C4", "#BFE3F2", "#5FA8C8", "#5A1E14", "#C86A2E"),
        new Theme("တောင်တန်း နံနက်", 7, "#2A4426", "#4A6A3A", "#5A4A2E", "#5A7A4A", "#3A4A26", "#FFF0A0", "#D4AF37", "#2E6A3A", "#C8E88A", "#FFFFFF", "#FFD54A", "#3A6A4A", "#D9B25A", "#F0F8E8", "#CFE8D8", "#6AC88A", "#3A5A26", "#7AC86A"),
        new Theme("ခရမ်းရောင် ည", 2, "#241A3A", "#3A2A5A", "#2E2233", "#4A3A6A", "#2A1E33", "#FFE98A", "#C8A030", "#5B2A86", "#D8B8FF", "#E88AC8", "#F2C94C", "#5A4A8A", "#D9B25A", "#F0E8F8", "#D8C8F0", "#8A6AC8", "#3A1E4A", "#9A7AD8"),
        new Theme("ရွှေဝါရောင်", 0, "#5A421A", "#7A5E26", "#6B5A2E", "#8A702E", "#5A421A", "#FFF8B0", "#F0C040", "#B85C1E", "#FFE85A", "#FF8A5A", "#FFD54A", "#8A6A2E", "#E8C25A", "#FFF8D0", "#FFF0C8", "#E8B85A", "#8A421E", "#F0A03E"),
        new Theme("အဖြူ သန့်", 1, "#5A5348", "#7A7264", "#8A7A5E", "#9A8A6E", "#6A5E48", "#FFF8D0", "#D8B858", "#8A4A3A", "#FFE8A0", "#E88AA0", "#F2C94C", "#5A7A8A", "#D9B25A", "#FFFFFF", "#E8F4F8", "#8AC0D8", "#7A4A3A", "#D8A06A"),
        new Theme("သံလွင်စိမ်း", 1, "#3A4430", "#5A6A44", "#5A5238", "#6A7A54", "#3A4430", "#FFE98A", "#C8A030", "#4A6A2E", "#D8E88A", "#FFFFFF", "#F2C94C", "#4A6A5A", "#D9B25A", "#F0F8E8", "#D8E8C8", "#7AB88A", "#3A4A26", "#8AC86A"),
        new Theme("ကျောက်စိမ်း", 0, "#1E4438", "#2E6A54", "#3A4A3A", "#3A7A64", "#1E4438", "#FFF0A0", "#C8A030", "#1E6A54", "#8AE8C8", "#FFFFFF", "#FFD54A", "#2E8A6A", "#D9B25A", "#E8F8F0", "#C8E8D8", "#3AB898", "#1E4A38", "#3AC8A0"),
        new Theme("နီညို", 4, "#4A1E1A", "#6E2E26", "#5A3326", "#7A4034", "#4A1E1A", "#FFE98A", "#D4AF37", "#8E1E1E", "#FF8A6A", "#FF6A8A", "#FFD54A", "#7A3A4A", "#D9B25A", "#FFE8E0", "#F2C8C8", "#C86A7A", "#6E1E1E", "#D84A3A"),
        new Theme("လိမ္မော် နေဝင်", 3, "#4A2410", "#7A3E1E", "#5A3A26", "#8A5028", "#4A2410", "#FFF0A0", "#E8A83A", "#C84A1E", "#FF9A4A", "#FF7A6A", "#FFD54A", "#8A5A3A", "#E8B25A", "#FFE8C8", "#F8D8B8", "#E89A5A", "#7A2A1E", "#F08A3E"),
        new Theme("မိုးပြာ", 7, "#2A4A6A", "#4A7AA8", "#5A6A7A", "#6A8AAA", "#3A4A5A", "#FFF0A0", "#D4AF37", "#2A6A8A", "#B8E0F8", "#FFFFFF", "#FFD54A", "#3A7AAA", "#D9B25A", "#F0F8FF", "#D8E8F8", "#6AB8E8", "#2A4A6A", "#6AC8E8"),
        new Theme("ရွှေည", 5, "#1E1A24", "#332A44", "#2A2233", "#4A3A5A", "#241E2E", "#FFF0A0", "#E8C040", "#7A3A8A", "#FFD88A", "#FF8AC8", "#FFD54A", "#5A4A7A", "#E8C25A", "#FFF8E0", "#F0D8E8", "#C88AC8", "#3A2444", "#C88AD8"),
        new Theme("ပန်းခရမ်း", 6, "#3A2A4A", "#5A426A", "#4A3A52", "#6A527A", "#3A2A4A", "#FFE98A", "#C8A030", "#6A3A8A", "#D8B8F0", "#E88AD8", "#FFD54A", "#6A5A8A", "#D9B25A", "#F8E8F8", "#E8D8F0", "#A88AD8", "#4A2A5A", "#B88AE8"),
        new Theme("သစ်ရွက် စိမ်း", 1, "#24402A", "#3A5E3E", "#4A4A30", "#527A4A", "#24402A", "#FFF0A0", "#C8A030", "#2E7A3A", "#A8E88A", "#FFFFFF", "#FFD54A", "#3A7A5A", "#D9B25A", "#F0F8E8", "#D8E8C8", "#6AC88A", "#2A4A26", "#7AC86A"),
        new Theme("ကြေးညို", 0, "#3A2A1E", "#5A422E", "#5A4632", "#6A5238", "#3A2A1E", "#FFE8A0", "#C89A4A", "#6A3A2A", "#E8C88A", "#E88A9A", "#E8C94C", "#5A4A3A", "#C89A4A", "#F0E8D0", "#E8D8B8", "#B8986A", "#4A2A1E", "#C88A5A"),
        new Theme("ငွေရောင် ည", 2, "#1E2430", "#323E54", "#2A2A33", "#4A546A", "#1E2430", "#F0F0FF", "#C8C8E8", "#4A5A7A", "#D8E8FF", "#FFFFFF", "#E8D8A8", "#5A6A8A", "#D8D8E8", "#F8F8FF", "#E0E8F8", "#9AB8D8", "#2A2A3A", "#A8B8D8"),
        new Theme("ပင်လယ်", 7, "#14303E", "#1E5064", "#2A3E44", "#2E6A7A", "#14303E", "#FFF0A0", "#D4AF37", "#1E6A7A", "#8AD8E8", "#FFFFFF", "#FFD54A", "#2E8A9A", "#D9B25A", "#E8F8FF", "#C8E8F8", "#2A9AC8", "#14303E", "#3AB8D8"),
        new Theme("တောတောင်", 1, "#22331E", "#3A5230", "#4A4426", "#5A6A3E", "#22331E", "#FFF0A0", "#C8A030", "#2E5A2A", "#B8E88A", "#FFFFFF", "#FFD54A", "#3A6A44", "#D9B25A", "#F0F8E8", "#D8E8C8", "#6AB87A", "#2A3A1E", "#7AC86A"),
        new Theme("ဆောင်းနံနက်", 7, "#3A4A54", "#5A6E7A", "#6A6A5E", "#7A8A96", "#3A4A54", "#FFF8D0", "#D8B858", "#5A6A7A", "#E0F0F8", "#FFFFFF", "#F2C94C", "#6A7A8A", "#D9B25A", "#FFFFFF", "#E8F4F8", "#8AC0D8", "#4A5A64", "#B8C8D8"),
        new Theme("နွေည", 2, "#1A2438", "#2A3A5A", "#2A2233", "#3A4A6A", "#1A2438", "#FFE98A", "#D4AF37", "#3A5A8A", "#FFE8A8", "#FF8AA8", "#FFD54A", "#4A6A8A", "#D9B25A", "#FFF8E0", "#F0E0C8", "#D8A88A", "#2A2444", "#D88A9A"),
        new Theme("ရွှေပန်း", 6, "#5A421A", "#7A5E26", "#6B5A2E", "#8A702E", "#5A421A", "#FFF8B0", "#F0C040", "#B85C1E", "#FFE85A", "#FF8AC8", "#FFD54A", "#8A6A2E", "#E8C25A", "#FFF8D0", "#FFF0C8", "#E8B85A", "#8A421E", "#F0A03E"),
        new Theme("အနီရောင် ပွဲ", 4, "#4A1010", "#6E1E1E", "#5A2620", "#7A2E2E", "#4A1010", "#FFF0A0", "#E8B83A", "#A81E1E", "#FF7A5A", "#FF5A7A", "#FFD54A", "#7A3A3A", "#E8B25A", "#FFE0D0", "#F8C8B8", "#D87A6A", "#6E1414", "#E84A3A"),
        new Theme("ငြိမ်းချမ်း ပြာ", 7, "#24344A", "#3A506A", "#3A4652", "#4A627A", "#24344A", "#FFF0A0", "#D4AF37", "#2E5A7A", "#A8D0E8", "#FFFFFF", "#FFD54A", "#3A6A8A", "#D9B25A", "#F0F8FF", "#D8E8F8", "#6AB8D8", "#2A3A4A", "#7AB8C8"),
        new Theme("မဟူရာ", 1, "#3E1E14", "#5E3226", "#5A3A26", "#6E4432", "#3E1E14", "#FFE98A", "#C8A030", "#6E2A1E", "#FFC88A", "#E88A8A", "#E8C94C", "#5A3A3A", "#C8A24A", "#F5E8D0", "#E8D8C8", "#C89A7A", "#4A2018", "#C87A4A"),
        new Theme("ရောင်စုံ ပွဲ", 5, "#3A1E3A", "#5A2E4A", "#4A2A3A", "#6A3E5A", "#3A1E3A", "#FFF0A0", "#E8B83A", "#8A2A5A", "#FFC88A", "#FF7AA8", "#FFD54A", "#7A4A6A", "#E8B25A", "#FFF0D8", "#F8D8E8", "#D88AB8", "#4A1E3A", "#E88AC8"),
    };

    private Theme theme = THEMES[0];
    private int themeIndex = 0;

    // ---- photorealistic altar photos (v1.38): the cartoon scene is
    // replaced by real-looking photos; animation overlays stay on top ----
    // v10.43: 6 altar THEMES (0 classic + 5 new AI scenes); the
    // retired PHOTO_FILES below stay untouched (loadPhotos unused).
    public static final int PHOTO_COUNT = 6;
    private static final String[] PHOTO_FILES = {
        "room/room_day.jpg", "room/room_candle.jpg",
        "room/room_morning.jpg", "room/room_dusk.jpg" };
    // head centre per photo (PHOTO fractions, converted via dst below)
    private static final float[][] PHOTO_HEAD = {
        {0.50f, 0.17f}, {0.50f, 0.27f}, {0.55f, 0.35f}, {0.50f, 0.34f} };
    // flame BASE positions per photo (photo fractions + size): the base is
    // the wick level of the photo's own flame, so the drawn flicker sits
    // exactly on it instead of floating above the lamp.
    // v7.19: every base re-measured against the shipped photos (gridded
    // lamp-zone crops + annotated re-check of all 32 flames). The old
    // table sat 0.025-0.09 BELOW the real wicks — front rows at 0.925
    // drew on the table apron, tall candles on their wax, shelf lamps
    // on the carving — because the photos' lamps sit higher than the
    // table assumed. Sizes unchanged; only x/y move, and only onto a
    // real flame at that lamp (a stored x with no lamp under it snaps
    // to the nearest lamp of its row, e.g. candle front 0.585->0.60).
    // Heads untouched: all four head markers land on the statue face.
    private static final float[][][] PHOTO_FLAMES = {
        // day: 4 oil lamps along the front
        {{0.055f, 0.855f, 14f}, {0.180f, 0.855f, 14f}, {0.795f, 0.855f, 14f}, {0.935f, 0.850f, 14f}},
        // candle night: front row + centre lamp + upper lamps + tall candles
        {{0.090f, 0.885f, 13f}, {0.225f, 0.890f, 13f}, {0.340f, 0.890f, 13f}, {0.600f, 0.885f, 13f},
         {0.680f, 0.885f, 13f}, {0.795f, 0.885f, 13f}, {0.500f, 0.835f, 14f},
         {0.170f, 0.725f, 13f}, {0.775f, 0.725f, 13f}, {0.050f, 0.820f, 14f}, {0.955f, 0.815f, 14f}},
        // morning: 3 tall candles + front oil lamps + tall right lamp
        {{0.270f, 0.630f, 14f}, {0.350f, 0.635f, 14f}, {0.730f, 0.630f, 14f},
         {0.095f, 0.765f, 13f}, {0.200f, 0.765f, 13f}, {0.775f, 0.770f, 13f}, {0.885f, 0.770f, 13f}, {0.935f, 0.735f, 13f}},
        // dusk: 2 tall candles + oil lamps
        {{0.315f, 0.695f, 14f}, {0.665f, 0.685f, 14f}, {0.050f, 0.730f, 13f}, {0.070f, 0.750f, 13f},
         {0.165f, 0.775f, 13f}, {0.255f, 0.780f, 13f}, {0.660f, 0.780f, 13f}, {0.750f, 0.775f, 13f}, {0.875f, 0.740f, 13f}},
    };
    private Bitmap[] photos;
    private boolean photosTried = false;
    private int photoIndex = 0;

    private Bitmap prevPhoto;
    private int prevPhotoIndex = 0;
    // v3.21: mid-crossfade layer — the photo that was fading IN when
    // a second talk change retargeted the fade (see setPhoto). It
    // draws between the outgoing photo and the new one at its frozen
    // alpha, so the retarget starts from the exact visible frame.
    // Null whenever no fade is in flight (cleared with prevPhoto).
    private Bitmap midPhoto;
    private int midPhotoIndex = -1;
    private float midAlpha = 0f;
    private long fadeStart = 0;
    private static final long FADE_MS = 700;
    // v3.25: premium crossfade self-drive — the fade clock above
    // runs on wall time, but its frames only arrived via the lit
    // animator, which ticks solely while a talk plays. A talk
    // change whose service bind (or buffering start) lagged past
    // the 700ms window therefore showed its first lit frame with
    // p already >= 1: the photo hard-cut while the title, seek
    // row and controls row all poured in. This ticker owns the
    // fade's own frames (invalidate every 50ms while a fade is
    // in flight), so the crossfade always actually animates,
    // whatever the play state is when it starts. It self-stops:
    // the p >= 1 draw clears prevPhoto, and the next tick sees
    // null and returns; detach removes it (v1.83 pattern), and
    // reattach restarts it while a fade is still in flight
    // (v1.87 pattern). It only invalidates — drift, flames and
    // smoke still advance solely on the lit animator, so a
    // paused fade holds its Ken Burns frame exactly, with no
    // position step, and a non-fade frame is pixel-identical.
    private final Runnable fadeTicker = new Runnable() {
        @Override public void run() {
            if (prevPhoto == null) return;
            invalidate();
            postDelayed(this, 50);
        }
    };

    // v9.15: the 0..3 slots RoomActivity sends are unchanged (0 day,
    // 1 candle night, 2 morning, 3 dusk — the old hour slots),
    // but they now pick a composed-scene THEME instead of a photo:
    // day -> gold shrine, candle -> night light, morning -> sunrise,
    // dusk -> dim lamplight. The auto-change toggle keeps working
    // exactly as before (random different slot per talk change),
    // and the opening theme is now a fresh random pick per Room
    // open (his 2026-11-06 order), not the hour.
    // v12.49: returns the theme actually showing. loadAiImage may
    // revise aiTheme — a failed decode falls back to classic (0),
    // a failed fallback keeps the old image (aiLoadedTheme) — so
    // the requested idx is NOT always what ends up on screen (the
    // 08:28 +07 altar audit's Finding 3: the activity recorded the
    // requested index, so the next pickTheme excluded a theme that
    // was not showing and could roll the one already showing, and
    // a rotation saved/restored the failed index and re-requested
    // it). Callers adopt the return as the record of what shows.
    private static final int[] SHRINE_THEMES = {0, 1, 2, 6};
    public int setPhoto(int idx) {
        if (idx < 0 || idx >= PHOTO_COUNT) idx = 0;
        // v11.02: soften the theme hard-cut on talk change — the
        // 08:33 +07 altar audit's open item: AI/theme scenes switch
        // instantly (the v1.52 photo crossfade died with the retired
        // photo scene), and a bitmap-only fade would misplace the
        // flames mid-transition, so the whole altar view dips
        // instead: alpha to 0.55 over 150ms, back to 1 over 250ms.
        // Flames/smoke/halo all fade WITH the view, so nothing can
        // drift off its wick mid-transition. Guarded to changes on
        // an already-visible view only: w==0 means first paint
        // (fresh open / rotation restore), which must not dip.
        // A second change mid-dip restarts from the current alpha
        // and still ends at 1; detach cancels + resets (below), so
        // no stranded dim is reachable. Nothing else animates
        // this view's alpha, so no animation channel is fought.
        boolean dip = (w > 0);
        // v12.47: remember the theme actually showing, so the smoke
        // below can be dropped only when the visible altar changes
        // (loadAiImage may revise aiTheme: a failed decode falls
        // back to classic, a failed fallback keeps the old image).
        int oldAiTheme = aiTheme;
        photoIndex = idx;
        // v12.48: the light slot is the HOUR, not the theme. Until now
        // aiLightSlot = idx, so it always equalled aiTheme — and
        // drawAiScene runs only when aiTheme == 0, so the slot was 0
        // there and the 1/2/3 wash branches (08:28 +07 altar audit,
        // Finding 2) could never fire: the classic altar showed no
        // time-of-day mood at any hour. The wash was written for the
        // v3.92 photo slots (0 day, 1 candle night, 2 morning, 3
        // dusk); v10.43 turned the photo index into a theme index
        // (0 classic, 1 morning marble, 2 jade, 3 dusk bronze,
        // 4 night crystal, 5 festival) and the slot silently became
        // the theme. Bands match lightSlotForHour below; the wash
        // only applies to the classic theme (theme 0), so the
        // hour still sets its light even though the opening
        // theme is now a random pick.
        aiLightSlot = lightSlotForHour(
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
        aiTheme = idx;
        setTheme(SHRINE_THEMES[idx % SHRINE_THEMES.length]);
        // v11.79: decode here, at theme selection, instead of leaving the
        // first decode to the next onDraw — the full-resolution JPEG used
        // to decode on the UI thread in the middle of a draw pass, hitching
        // the frame exactly when the altar should dip. The dip below now
        // covers the decode; onDraw's call stays as the lazy backstop for
        // a first paint or rotation restore that never passed through here.
        loadAiImage();
        // v12.47: drop the old theme's smoke when the visible altar
        // changes — the 08:28 +07 altar audit's Finding 1. The smoke
        // list is shared by every scene (one list, L62) and a puff's
        // sway base is fixed at spawn from its own theme's incense
        // tips, so on a talk change that rolled a new theme the old
        // ~200 puffs kept rising from the old tips (up to ~0.15w
        // away — classic 0.695 vs t5 0.860) under the new image for
        // ~29s, a second ghost column rising from mid-air where the
        // new altar has no incense, while the new ribbon spawned
        // alongside. A puff lives until it has risen 0.30h, so the
        // drain alone could never hide it. The dip above masks the
        // clear (the view is at 0.55 alpha while it happens) and
        // the new ribbon respawns from the new tips within a
        // second. Guarded on the EFFECTIVE theme (aiTheme after
        // loadAiImage): a same-theme re-set and a keep-old-image
        // fallback keep their smoke; a rotation restore is a fresh
        // view with an empty list, and restoreSmoke runs after
        // this call, so its puffs are never caught by the clear.
        // pendingSmoke is cleared with it, so a restore still
        // waiting for layout cannot re-apply old-theme puffs.
        if (aiTheme != oldAiTheme) {
            smokes.clear();
            pendingSmoke = null;
        }
        if (dip) {
            animate().cancel();
            animate().alpha(0.55f).setDuration(150)
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            animate().alpha(1f).setDuration(250).start();
                        }
                    }).start();
        }
        // v12.49: the theme actually showing (see the header note) —
        // aiTheme after loadAiImage, which may have fallen back.
        return aiTheme;
    }

    private void loadPhotos() {
        if (photosTried) return;
        photosTried = true;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            photos = new Bitmap[PHOTO_FILES.length];
            for (int i = 0; i < PHOTO_FILES.length; i++) {
                // v1.67: close in a finally — a decode dying mid-photo
                // used to skip the close and leak the stream (same
                // class the loaders and ImageLoader fixed in v1.65/66).
                java.io.InputStream in = null;
                try {
                    in = getContext().getAssets().open(PHOTO_FILES[i]);
                    photos[i] = BitmapFactory.decodeStream(in, null, o);
                } finally {
                    if (in != null) { try { in.close(); } catch (Exception ignored) {} }
                }
            }
        } catch (Exception e) {
            photos = null;
        }
    }

    // ---- v9.17: near-real AI altar image scene (user order 2026-11-02
    // ~21:36 +07 — he wants the shrine to look almost real, animated).
    // AI VIDEO is region-blocked for this account (403, three recorded
    // attempts, never retried), so the near-real look comes from an
    // AI still image (assets/room/altar_ai.jpg, front-view golden
    // Buddha + oil lamps + flowers + water + incense) with LIVE
    // animation drawn on top: flickering flames on the lamps' own
    // wicks, smoke rising from the incense tips, breathing halo
    // glow. ONE image, drawn stretched to the full view with NO
    // zoom/pan and NO switching, so every overlay maps by exact
    // image fraction and can never drift off its lamp — the fix
    // for the photo scene's misalignment. Positions were measured
    // on the shipped image (visual grounding): lamp wicks
    // (0.189/0.262/0.335, 0.814), head (0.497, 0.297), incense
    // tips around x 0.70-0.78, y 0.712. The drawn scene below stays
    // as the fallback if the asset ever fails to decode.
    private Bitmap aiImage;
    private boolean aiTried = false;
    // v12.48: the time-of-day light slot for drawAiScene's wash —
    // the HOUR band, NOT the theme (see setPhoto). 0 day (no wash),
    // 1 night, 2 morning, 3 dusk; bands live in lightSlotForHour
    // below. Carried separately because
    // aiTheme names the altar image while the wash names the light.
    private int aiLightSlot = 0;
    private static int lightSlotForHour(int h) {
        if (h >= 5 && h < 10) return 2;  // morning
        if (h >= 10 && h < 17) return 0; // day
        if (h >= 17 && h < 19) return 3; // dusk
        return 1;                        // night
    }
    // v9.36 CALIBRATION (two-point, from HIS videos):
    // v9.28 (fy 0.657) overlays landed ~138px below the baked
    // flames; v9.35 (fy 0.593) still ~44px below (measured on his
    // 1080x2392 video frame 2026-11-02 ~23:07 +07: baked bases
    // y~478, animated bases y~496, i.e. 44px). Effective overlay
    // slope is only ~1469px per fy unit, not the 2160 aiY assumes
    // — so fy 0.563/0.561/0.559 puts the bases ON the baked wicks
    // (overlay_y = 1469*fy + 316 = baked 1143). Same -0.030 step
    // applied to smoke tips (0.491->0.461 etc.). fx corrected to
    // the video's baked centres (0.144/0.240/0.322): the old 0.304
    // right-flame pick was a spurious small flame in the image,
    // the true right wick sits ~20px further right.
    private static final float[][] AI_FLAMES = {
        {0.144f, 0.563f}, {0.240f, 0.561f}, {0.322f, 0.559f} };
    private static final float[] AI_FLAME_PHASE = {0.7f, 2.4f, 5.0f};
    private static final float AI_HEAD_X = 0.500f, AI_HEAD_Y = 0.238f;
    private static final float[][] AI_SMOKE_TIPS = {
        {0.695f, 0.461f}, {0.705f, 0.451f}, {0.719f, 0.444f} };
    // v9.28 crop mapping (see drawAiScene): the image is NOT drawn
    // full-view. Only the y-band [AI_CROP_TOP, AI_CROP_BOT] is shown,
    // aspect-preserved, top-aligned — so statue + offerings land in
    // the visible band above the Room bottom panel (panel top sits
    // at ~0.55 of the view on his 1080x2392 phone, measured from
    // his 2026-11-02 screen recording). Overlay fractions map via
    // aiX()/aiY() below, never raw fractions.

    // ---- v10.43: six altar themes (user order 2026-11-03 ~11:36 +07:
    // one altar every day is boring — six different Buddhas, six
    // different animations). Theme 0 is the proven classic image
    // and keeps its OWN crop path (drawAiScene) untouched — its
    // on-device alignment is already exact, never re-derive it.
    // Themes 1-5 use drawThemeScene with a FIT mapping: the bitmap
    // and every overlay are computed from the SAME dst rect, so a
    // flame cannot drift off its wick the way the old crop mapping
    // did (that mapping needed the empirical -0.094 correction).
    // Flame fractions below are FILE fractions measured from the
    // shipped images by bright-core pixel clustering (core-bottom
    // = wick level): the method was validated on the classic
    // asset, where it reproduces the proven 0.563 code constant
    // to within 0.003. Head = halo-ring centre (ring-arc centroid
    // for t1, eyeballed for t2-t4 where the ring is dim). Smoke
    // tips are eyeballed stick tops — smoke is a soft ribbon, so
    // a few px of tolerance there is invisible, unlike flames.
    private static final String[] THEME_FILES = {
        "room/altar_ai.jpg", "room/altar_t1.jpg", "room/altar_t2.jpg",
        "room/altar_t3.jpg", "room/altar_t4.jpg", "room/altar_t5.jpg" };
    private int aiTheme = 0;
    private int aiLoadedTheme = -1;
    private static final float[][][] THEME_FLAMES = {
        { {0f, 0f}, {0f, 0f}, {0f, 0f} },
        { {0.333f, 0.730f}, {0.502f, 0.731f}, {0.663f, 0.730f} },
        { {0.388f, 0.816f}, {0.500f, 0.822f}, {0.611f, 0.819f} },
        { {0.386f, 0.775f}, {0.509f, 0.777f}, {0.630f, 0.776f} },
        { {0.372f, 0.778f}, {0.498f, 0.784f}, {0.622f, 0.778f} },
        { {0.322f, 0.803f}, {0.498f, 0.803f}, {0.661f, 0.795f} } };
    private static final float[][] THEME_HEAD = {
        {0.500f, 0.238f}, {0.507f, 0.217f}, {0.500f, 0.235f},
        {0.500f, 0.235f}, {0.500f, 0.235f}, {0.500f, 0.235f} };
    private static final float[][][] THEME_TIPS = {
        { {0f, 0f}, {0f, 0f}, {0f, 0f} },
        { {0.710f, 0.625f}, {0.750f, 0.615f}, {0.790f, 0.620f} },
        { {0.750f, 0.640f}, {0.790f, 0.630f}, {0.820f, 0.640f} },
        { {0.700f, 0.640f}, {0.730f, 0.630f}, {0.760f, 0.640f} },
        { {0.750f, 0.640f}, {0.790f, 0.630f}, {0.820f, 0.640f} },
        { {0.800f, 0.625f}, {0.830f, 0.615f}, {0.860f, 0.625f} } };
    // anim: 1 falling petals, 2 rising petals, 3 sweeping beams +
    // embers, 4 twinkling stars, 5 fast festival petals
    private static final int[] THEME_ANIM = {0, 1, 2, 3, 4, 5};
    private static final int[][] THEME_GLOW = {
        {255, 215, 90}, {255, 225, 180}, {150, 255, 190},
        {255, 170, 60}, {190, 220, 255}, {255, 190, 80} };
    private static final float[] THEME_PULSE =
        {0.0016f, 0.0016f, 0.0012f, 0.0016f, 0.0024f, 0.0032f};
    private static final float[] THEME_FLAME_H =
        {1f, 1f, 1f, 1f, 1f, 1.25f};

    // v11.79: decode one theme asset, or null when it cannot be opened or
    // decoded. Extracted so loadAiImage can try the classic fallback on an
    // open failure too — the old inline code only fell back when decode
    // returned null, so a missing/corrupt asset skipped the fallback.
    private Bitmap decodeThemeBitmap(int theme) throws java.io.IOException {
        java.io.InputStream in = null;
        try {
            in = getContext().getAssets().open(THEME_FILES[theme]);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeStream(in, null, o);
        } finally {
            if (in != null) { try { in.close(); } catch (Exception ignored) {} }
        }
    }

    private void loadAiImage() {
        if (aiTried && aiLoadedTheme == aiTheme) return;
        aiTried = true;
        int wanted = aiTheme;
        // v11.79: decode into a local first — the old code set
        // aiLoadedTheme and recycled the working altar BEFORE the new
        // decode, so a failed theme change lost the altar the user
        // already had and never retried (the guard above then matched).
        Bitmap tmp = null;
        try { tmp = decodeThemeBitmap(wanted); } catch (Exception ignored) { }
        if (tmp == null && wanted > 0) {
            // v10.45: a failed theme image used to fall back to the classic
            // bitmap while aiTheme still named the failed theme, so onDraw
            // dispatched to drawThemeScene and drew the failed theme's
            // flame/head fractions over the classic image — flames off
            // the wicks, halo off the head. A successful fallback now
            // becomes fully classic (theme 0, cache key 0), so the
            // classic crop path and its proven fractions apply; the
            // hour light slot is untouched here (v12.48: it names the
            // light, not the theme, so a fallback must not reset it
            // to the theme). A failed fallback still leaves aiImage
            // null for the drawn scene, unchanged. Normal path
            // untouched: this block only runs when the theme decode
            // already failed.
            try { tmp = decodeThemeBitmap(0); } catch (Exception ignored) { }
            if (tmp != null) wanted = 0;
        }
        if (tmp != null) {
            if (aiImage != null) aiImage.recycle();
            aiImage = tmp;
            aiTheme = wanted;
            aiLoadedTheme = wanted;
        } else if (aiImage == null) {
            // First load failed and there is nothing to keep: mark
            // attempted so a failed load does not retry on every 50ms
            // frame; the drawn scene shows meanwhile.
            aiLoadedTheme = wanted;
        } else {
            // Keep the working altar: revert aiTheme to the theme the
            // surviving image actually shows, so flame/head fractions
            // stay on the right wicks. The hour light slot is left
            // alone (v12.48) — it names the light, not the image.
            // The guard above still blocks retries until the next
            // setPhoto.
            aiTheme = aiLoadedTheme;
        }
    }

    private float aiDstH;
    private float aiDstL, aiDstW; // v13.23: centred dst rect left/width for the landscape fit
    private float aiX(float fx) { return aiDstL + fx * aiDstW; }
    private float aiY(float fy) {
        return (fy - 0.12f) / 0.60f * aiDstH;
    }

    private void drawAiScene(Canvas c, float cx, long tt) {
        // Crop band [0.12, 0.72] of the image, aspect-preserved,
        // top-aligned: on his phone (w=1080) dstH = 1.2*w = 1296px,
        // so lamp wicks land at ~0.40h, incense tips at ~0.31h,
        // head at ~0.11h — all ABOVE the panel top (~0.55h).
        // The old full-view draw put wicks at 0.81h and smoke tips
        // at 0.71h, i.e. BEHIND the panel: flames invisible, and
        // smoke alpha had already faded to 0 before rising clear
        // of the panel — exactly what his video showed (2026-11-02
        // ~22:28 +07). The dark table below the crop is simply not
        // drawn; the view behind it stays dark temple shadow.
        int iw = aiImage.getWidth(), ih = aiImage.getHeight();
        int srcTop = (int) (0.12f * ih), srcBot = (int) (0.72f * ih);
        // v13.23 (RoomView F2, landscape classic altar): the crop band
        // used to be drawn full-width at aiDstH = 1.2*w with no fit to
        // the view height or the panel — in landscape (w=2392, h=1080)
        // the lamp wicks landed at y~2119, i.e. below the bottom of
        // the view, so the classic altar showed only the Buddha's head
        // and halo: no flames, no smoke, no lamps. Same fit as
        // drawThemeScene now: shrink uniformly (centred) until the
        // highest lamp base sits just above the panel top, and never
        // taller than the view; the fit scale multiplies every overlay
        // through aiX()/aiY() and the *u radii below, so flames stay
        // on their wicks by construction. Portrait is provably
        // unchanged: fit=1 there (flames ~957px vs panel top ~1315px
        // on his 1080x2392 phone, aiDstH 1296 < h).
        float fullH = w * (float) (srcBot - srcTop) / iw;
        float flameFy = 0f;
        for (int i = 0; i < AI_FLAMES.length; i++)
            flameFy = Math.max(flameFy, AI_FLAMES[i][1]);
        float flameY = (flameFy - 0.12f) / 0.60f * fullH;
        float fit = 1f;
        float limitY = panelLimitY();
        if (flameY > limitY) fit = limitY / flameY;
        if (fullH * fit > h) fit = h / fullH;
        aiDstH = fullH * fit;
        aiDstL = (w - w * fit) / 2f;
        aiDstW = w * fit;
        paint.setShader(null);
        paint.setAlpha(255);
        c.drawBitmap(aiImage,
                new Rect(0, srcTop, iw, srcBot),
                new RectF(aiDstL, 0, aiDstL + aiDstW, aiDstH), paint);
        // time-of-day light wash per hour slot (v12.48: aiLightSlot
        // is the hour band from setPhoto, never the theme — the wash
        // branches below were dead until then, because the slot
        // always equalled aiTheme and this scene runs only for
        // theme 0). 1 night warm amber, 2 morning pale gold,
        // 3 dusk dark; 0 day draws no wash.
        if (aiLightSlot == 1) {
            paint.setColor(Color.argb(28, 255, 170, 60));
            c.drawRect(0, 0, w, h, paint);
        } else if (aiLightSlot == 2) {
            paint.setColor(Color.argb(16, 255, 215, 90));
            c.drawRect(0, 0, w, h, paint);
        } else if (aiLightSlot == 3) {
            paint.setColor(Color.argb(52, 20, 12, 30));
            c.drawRect(0, 0, w, h, paint);
        }
        float hx = aiX(AI_HEAD_X), hy = aiY(AI_HEAD_Y);
        if (litBlend > 0.01f) {
            float breath = (float) Math.sin(tt * 0.0016);
            float pulse = 0.78f + 0.22f * breath;
            // breathing halo glow over the baked ring
            paint.setShader(new RadialGradient(hx, hy, 130 * u * fit,
                    Color.argb((int) ((26 + 60 * litBlend) * (0.85f + 0.15f * breath)), 255, 215, 90),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawCircle(hx, hy, 130 * u * fit, paint);
            paint.setShader(null);
            // ROTATING halo rays — real movement, not just
            // bright/dim: 24 thin rays turning one full turn in
            // ~2 min, each shimmering. This is what "alive" means
            // here; the v9.19 breathing glow alone read as a lamp
            // dimmer switch (his words: လင်းလိုက်မှိန်လိုက်).
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f * u * fit);
            paint.setStrokeCap(Paint.Cap.ROUND);
            float rot = tt * 0.00005f;
            for (int i = 0; i < 24; i++) {
                float a = rot + i * (float) (Math.PI * 2 / 24);
                float shim = 0.7f + 0.3f * (float) Math.sin(tt * 0.0016 + i);
                paint.setColor(Color.argb((int) (30 * litBlend * shim), 255, 220, 110));
                float r1 = 95 * u * fit, r2 = 140 * u * fit;
                c.drawLine(hx + (float) Math.cos(a) * r1, hy + (float) Math.sin(a) * r1,
                        hx + (float) Math.cos(a) * r2, hy + (float) Math.sin(a) * r2, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            // gold dust motes drifting up through the halo light
            for (int i = 0; i < 12; i++) {
                float rise = (tt * 0.02f + i * 97) % (240 * u * fit);
                float mx = hx + (float) Math.sin(i * 2.3 + tt * 0.001) * 70 * u * fit;
                float my = hy + 120 * u * fit - rise;
                float tw = 0.5f + 0.5f * (float) Math.sin(tt * 0.003 + i * 1.7);
                paint.setColor(Color.argb((int) (110 * litBlend * tw), 255, 225, 130));
                c.drawCircle(mx, my, 2 * u * fit, paint);
            }
            // warm lamp glow rising from the oil lamps
            paint.setShader(new RadialGradient(aiX(0.222f), aiY(0.655f), 200 * u * fit,
                    Color.argb((int) (40 * pulse * litBlend), 255, 170, 60),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawRect(aiDstL, aiY(0.45f), aiDstL + aiDstW, aiDstH, paint);
            paint.setShader(null);
            // live flames on the lamps' own wicks, bigger now
            // v9.38 TRIM (his video 2026-11-02 ~23:14 +07 "နဲနဲများတယ်"):
            // v9.36 bases measured EXACT on the baked wicks (gaps
            // 1-2px), but the 17u flame flickers up to ~60px while
            // the baked flames are ~58px — at flicker peaks the
            // orange tip pokes above the real flame, reading as a
            // little too much. 15u peaks at ~53px, always inside
            // the baked silhouette; bases untouched, so alignment
            // stays exact. Glow discs shrink with it (fw scales).
            for (int i = 0; i < AI_FLAMES.length; i++) {
                flame(c, aiX(AI_FLAMES[i][0]), aiY(AI_FLAMES[i][1]),
                        15 * u * fit * (0.35f + 0.65f * litBlend), AI_FLAME_PHASE[i], litBlend);
            }
            // incense smoke — v9.43 NATURAL WISP REWORK (his order
            // 2026-11-02 ~23:33 +07: smoke does not look natural).
            // ROOT CAUSE of the old look: spawn EVERY tick with cap
            // 30 + fast rise + fast growth meant the column was
            // CLUMPS of big soft balls (30 puffs, then a gap until
            // the first one died) with per-tick sway jitter — beads,
            // not a wisp. New model: spawn every 3rd tick, slow
            // rise, slow growth, cap 220 (life ~600 ticks, steady
            // count ~200, never throttled, so spacing stays even
            // at ~3px — puffs overlap into ONE continuous curling
            // ribbon instead of separate balls.
            if (lit && !sleepFading && frame % 3 == 0 && smokes.size() < 220) {
                float[] tip = AI_SMOKE_TIPS[random.nextInt(AI_SMOKE_TIPS.length)];
                smokes.add(new Smoke(aiX(tip[0]) + (random.nextFloat() - 0.5f) * 8 * u,
                        aiY(tip[1]), random.nextFloat() * 6f,
                        0.9f + random.nextFloat() * 0.4f, 4f + random.nextFloat() * 2f));
            }
        }
        // smoke rise/curl/drain — v9.43: sway is now an ABSOLUTE
        // curl (x = base + sin(risen) * widening amplitude), not a
        // per-tick random-walk increment, so each wisp traces one
        // smooth curling ribbon; alpha fades IN over the first
        // 45px (no popping at birth) then OUT over 0.30h; growth
        // is slow (0.05/tick) so puffs stay wispy, never balloons.
        java.util.Iterator<Smoke> it = smokes.iterator();
        while (it.hasNext()) {
            Smoke s = it.next();
            s.y -= s.speed;
            float risen = s.startY - s.y;
            s.x = s.bx + (float) Math.sin(risen * 0.012 + s.phase) * (3.0f + risen * 0.035f);
            s.size += 0.05f;
            if (lit) s.alpha = 0.42f * Math.min(1f, risen / 45f) * Math.max(0f, 1f - risen / (h * 0.30f));
            else s.alpha -= 0.08f;
            if (s.alpha <= 0 || s.y < h * 0.05f) { it.remove(); continue; }
            drawSmokePuff(c, s);
        }
        if (litBlend < 1f) {
            paint.setColor(Color.argb((int) (90 * (1f - litBlend)), 0, 0, 0));
            c.drawRect(0, 0, w, h, paint);
        }
    }

    // v10.43: theme scene for altar themes 1-5. FIT mapping: the
    // bitmap rect and every overlay share one dst rect (left, 0,
    // left+dw, dh), so flames sit on their wicks BY CONSTRUCTION —
    // no crop-band formula, no empirical offset to re-derive per
    // image. The image is drawn full-width unless its lamps would
    // fall behind the Room bottom panel, in which case it shrinks
    // uniformly (centred) until the highest lamp base sits just
    // above the panel top; the head stays visible because every
    // theme's head-to-lamp span fits the window at that scale
    // (verified per theme from the shipped files). Flame height
    // scales with s too, so the animated flame stays inside its
    // baked silhouette. Smoke reuses the shared v9.43 wisp system
    // (spawn every 3rd tick, curl sway, fade-in/out) unchanged.
    // v12.52 (altar/themes audit Finding 6): the shrink target
    // used to be a hardcoded 0.52h, which assumed the panel top
    // sits at ~0.55h (true on his 1080x2392 phone). The panel is
    // wrap_content, so on a shorter screen it covers a larger
    // fraction and the 0.52h lamps slid behind it. The limit now
    // comes from the panel's real top (panelLimitY), read fresh
    // every frame, so a taller panel (long title, retry button)
    // moves the lamps up with it. On his phone the measured top
    // is ~0.55h, so the limit lands within a pixel of the old
    // 0.52h — invisible there.
    // v12.52: highest y a lamp base may sit at — the panel's real
    // top minus the old 0.03h margin, floored at 0.20h so a
    // pathological panel can never shrink the altar to nothing.
    // The panel View is cached (re-looked-up if the parent
    // changed) but its top is read fresh on every call; unknown
    // panel (no id, or not laid out yet — getTop() == 0) falls
    // back to the old hardcoded 0.52h.
    private android.view.View panelView;
    private float panelLimitY() {
        if (panelView == null || panelView.getParent() != getParent()) {
            panelView = null;
            android.view.ViewParent p = getParent();
            if (p instanceof android.view.View)
                panelView = ((android.view.View) p).findViewById(R.id.roomBottomPanel);
        }
        if (panelView != null) {
            float top = panelView.getTop();
            if (top > 0) {
                float limit = top - 0.03f * h;
                if (limit < h * 0.20f) limit = h * 0.20f;
                return limit;
            }
        }
        return h * 0.52f;
    }
    private void drawThemeScene(Canvas c, float cx, long tt) {
        int iw = aiImage.getWidth(), ih = aiImage.getHeight();
        float fullH = w * (float) ih / iw;
        float lampFy = 0f;
        for (int i = 0; i < 3; i++)
            lampFy = Math.max(lampFy, THEME_FLAMES[aiTheme][i][1]);
        float s = 1f;
        float limitY = panelLimitY();
        if (lampFy * fullH > limitY) s = limitY / (lampFy * fullH);
        float dw = w * s, dh = fullH * s, left = (w - dw) / 2f;
        paint.setShader(null);
        paint.setAlpha(255);
        c.drawBitmap(aiImage, null, new RectF(left, 0, left + dw, dh), paint);
        float hx = left + THEME_HEAD[aiTheme][0] * dw;
        float hy = THEME_HEAD[aiTheme][1] * dh;
        int anim = THEME_ANIM[aiTheme];
        int[] glow = THEME_GLOW[aiTheme];
        if (litBlend > 0.01f) {
            float breath = (float) Math.sin(tt * THEME_PULSE[aiTheme]);
            paint.setShader(new RadialGradient(hx, hy, 130 * u * s,
                    Color.argb((int) ((26 + 60 * litBlend) * (0.85f + 0.15f * breath)), glow[0], glow[1], glow[2]),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawCircle(hx, hy, 130 * u * s, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            if (anim == 1) {
                for (int i = 0; i < 14; i++) {
                    float fall = (tt * 0.02f + i * 173) % (dh + 40 * u);
                    float px2 = left + ((i * 197) % Math.max(1, (int) dw)) + (float) Math.sin(tt * 0.001 + i) * 12 * u;
                    float py2 = fall - 20 * u;
                    paint.setColor(Color.argb((int) (150 * litBlend), 255, 230, 235));
                    c.drawOval(px2 - 6 * u * s, py2 - 3 * u * s, px2 + 6 * u * s, py2 + 3 * u * s, paint);
                }
            } else if (anim == 2) {
                for (int i = 0; i < 12; i++) {
                    float rise = (tt * 0.015f + i * 211) % (dh + 40 * u);
                    float px2 = left + ((i * 223) % Math.max(1, (int) dw)) + (float) Math.sin(tt * 0.001 + i * 2) * 10 * u;
                    float py2 = dh - rise + 20 * u;
                    paint.setColor(Color.argb((int) (130 * litBlend), 200, 255, 190));
                    c.drawOval(px2 - 5 * u * s, py2 - 2.5f * u * s, px2 + 5 * u * s, py2 + 2.5f * u * s, paint);
                }
            } else if (anim == 3) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3f * u * s);
                paint.setStrokeCap(Paint.Cap.ROUND);
                float rot = tt * 0.00002f;
                for (int i = 0; i < 6; i++) {
                    float a = rot + i * (float) (Math.PI * 2 / 6);
                    paint.setColor(Color.argb((int) (26 * litBlend), 255, 170, 60));
                    c.drawLine(hx + (float) Math.cos(a) * 60 * u * s, hy + (float) Math.sin(a) * 60 * u * s,
                            hx + (float) Math.cos(a) * 280 * u * s, hy + (float) Math.sin(a) * 280 * u * s, paint);
                }
                paint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 10; i++) {
                    float rise = (tt * 0.03f + i * 167) % (300 * u);
                    float ex = left + dw * (0.30f + 0.04f * i) + (float) Math.sin(tt * 0.002 + i) * 6 * u;
                    float ey = lampFy * dh - rise;
                    paint.setColor(Color.argb((int) (160 * litBlend), 255, 150, 40));
                    c.drawCircle(ex, ey, 2 * u * s, paint);
                }
            } else if (anim == 4) {
                for (int i = 0; i < 24; i++) {
                    float sx = left + ((i * 269) % (int) dw);
                    float sy = (i * 131) % (int) (dh * 0.45f);
                    float tw = 0.5f + 0.5f * (float) Math.sin(tt * 0.004 + i * 1.9f);
                    paint.setColor(Color.argb((int) (170 * litBlend * tw), 220, 235, 255));
                    c.drawCircle(sx, sy, 2.2f * u * s, paint);
                }
            } else if (anim == 5) {
                for (int i = 0; i < 18; i++) {
                    float fall = (tt * 0.05f + i * 149) % (dh + 40 * u);
                    float px2 = left + ((i * 181) % (int) dw) + (float) Math.sin(tt * 0.002 + i) * 14 * u;
                    float py2 = fall - 20 * u;
                    paint.setColor(Color.argb((int) (170 * litBlend), 255, 180, 40));
                    c.drawOval(px2 - 6 * u * s, py2 - 3 * u * s, px2 + 6 * u * s, py2 + 3 * u * s, paint);
                }
            }
            for (int i = 0; i < 3; i++) {
                flame(c, left + THEME_FLAMES[aiTheme][i][0] * dw, THEME_FLAMES[aiTheme][i][1] * dh,
                        15 * u * s * THEME_FLAME_H[aiTheme] * (0.35f + 0.65f * litBlend), AI_FLAME_PHASE[i], litBlend);
            }
            if (lit && !sleepFading && frame % 3 == 0 && smokes.size() < 220) {
                float[] tip = THEME_TIPS[aiTheme][random.nextInt(3)];
                smokes.add(new Smoke(left + tip[0] * dw + (random.nextFloat() - 0.5f) * 8 * u,
                        tip[1] * dh, random.nextFloat() * 6f,
                        0.9f + random.nextFloat() * 0.4f, 4f + random.nextFloat() * 2f));
            }
        }
        java.util.Iterator<Smoke> it = smokes.iterator();
        while (it.hasNext()) {
            Smoke sm = it.next();
            sm.y -= sm.speed;
            float risen = sm.startY - sm.y;
            sm.x = sm.bx + (float) Math.sin(risen * 0.012 + sm.phase) * (3.0f + risen * 0.035f);
            sm.size += 0.05f;
            if (lit) sm.alpha = 0.42f * Math.min(1f, risen / 45f) * Math.max(0f, 1f - risen / (h * 0.30f));
            else sm.alpha -= 0.08f;
            if (sm.alpha <= 0 || sm.y < h * 0.05f) { it.remove(); continue; }
            drawSmokePuff(c, sm);
        }
        if (litBlend < 1f) {
            paint.setColor(Color.argb((int) (90 * (1f - litBlend)), 0, 0, 0));
            c.drawRect(0, 0, w, h, paint);
        }
    }

    private void drawPhotoScene(Canvas c, float cx, long tt) {
        // v11.79 (dead-code guards): no caller today and photos[] is never
        // allocated, but if the scene is ever revived, a null table must not
        // NPE here and photoIndex 4/5 (PHOTO_COUNT is 6) must not run off
        // the 4-row PHOTO_HEAD/PHOTO_FLAMES tables (~1/3 of talk changes).
        if (photos == null) return;
        if (photoIndex < 0 || photoIndex >= PHOTO_HEAD.length) photoIndex = 0;
        Bitmap bm = photos[photoIndex];
        if (bm == null) return;
        float bw = bm.getWidth(), bh = bm.getHeight();
        float scale = Math.max(w / bw, h / bh);
        // v2.26: Ken Burns drift — the shrine photo breathes 1.00 ->
        // 1.03 -> 1.00 over a slow 24s cycle while a talk plays, so
        // the altar feels alive instead of frozen behind the flames.
        // Only 3% and centred, so no edge ever shows; the halo and
        // flame positions below are computed from this same dst, so
        // they stay glued to the statue and lamps as it zooms. The
        // cartoon fallback scene is untouched (photos are the real
        // scene), and pause freezes the drift via driftMs.
        // v2.72: slow pan added to the same play-only clock — a
        // centred zoom alone still reads static over a long talk,
        // because the statue never moves in the frame. The photo
        // now also drifts sideways, ±0.7% of the width over the
        // same 24s cycle, while the zoom floor rises to 1.02 so
        // the pan can never expose an edge: at the smallest zoom
        // the overscan is 1% each side and the pan peaks at 0.7%,
        // leaving a 0.3% margin even at the extremes, and the pan
        // is 0 at the cycle ends where the zoom is smallest. The
        // outgoing crossfade photo pans with the same value, so a
        // talk-change fade never shows a position step, and the
        // halo/flame positions below still come from this same
        // dst, so they stay glued as the frame pans. Pause still
        // freezes everything via driftMs, exactly as in v2.26.
        float theta = (float) (driftMs * Math.PI * 2 / 24000.0);
        float drift = 1.02f + 0.015f * (0.5f - 0.5f * (float) Math.cos(theta));
        float panPx = 0.007f * w * (float) Math.sin(theta);
        // v2.74: slow vertical drift on the same play-only clock —
        // the sideways pan alone still slid on a straight line, so
        // over a long talk the frame's motion read as a single
        // axis. The photo now also drifts up/down, ±0.4% of the
        // height on a quarter-cycle-shifted sine (cos), so pan +
        // vertical trace a slow oval instead of a straight slide.
        // Edge safety per axis: at the smallest zoom (1.02) the
        // height overscan is 1% each side and the vertical peak
        // is 0.4%, leaving a 0.6% margin even at the extremes
        // (width-determined crops have a larger overscan, so the
        // same bound holds there too). The outgoing crossfade
        // photo drifts with the same value, so a talk-change fade
        // never shows a position step, and halo/flame positions
        // below still come from this same dst, so they stay glued
        // as the frame ovals. Pause still freezes everything via
        // driftMs, exactly as in v2.26/v2.72.
        float driftYPx = 0.004f * h * (float) Math.cos(theta);
        float dw = bw * scale * drift, dh = bh * scale * drift;
        RectF dst = new RectF((w - dw) / 2f + panPx, (h - dh) / 2f + driftYPx, (w + dw) / 2f + panPx, (h + dh) / 2f + driftYPx);
        paint.setShader(null);
        // v3.09: crossfade progress for the drawn overlays below —
        // the flames/halo used to jump to the NEW photo's positions
        // at full strength in the first crossfade frame, while the
        // old photo was still fading out underneath them. 1 when
        // no crossfade is running, so every non-talk-change frame
        // is pixel-identical to before.
        float crossP = 1f;
        RectF prevDst = null;
        RectF midDst = null;
        if (prevPhoto != null) {
            float p = Math.min(1f, (SystemClock.uptimeMillis() - fadeStart) / (float) FADE_MS);
            crossP = p;
            // v1.84: the outgoing photo used to be drawn in the NEW
            // photo's centre-crop rect — the morning photo is
            // 1280x1920 while the other three are 1120x2240, so
            // every fade to or from it stretched the old photo for
            // 700ms. Each photo gets its own centre-crop rect.
            float pbw = prevPhoto.getWidth(), pbh = prevPhoto.getHeight();
            float pscale = Math.max(w / pbw, h / pbh);
            // v2.26: the outgoing photo zooms with the same drift, so
            // a talk-change crossfade never shows a size step.
            // v2.72: it pans with the same panPx too, so the fade
            // never shows a position step either.
            // v2.74: it drifts vertically with the same driftYPx too.
            float pdw = pbw * pscale * drift, pdh = pbh * pscale * drift;
            prevDst = new RectF((w - pdw) / 2f + panPx, (h - pdh) / 2f + driftYPx,
                    (w + pdw) / 2f + panPx, (h + pdh) / 2f + driftYPx);
            // v3.12: the outgoing photo used to draw at alpha
            // (1 - p) with the incoming drawn at p OVER it, so the
            // outgoing layer was attenuated twice: at mid-fade it
            // contributed only (1 - p)^2 = 25% while the incoming
            // gave 50%, a 25% brightness dip on every talk change
            // (the v3.09-v3.11 overlay rides could not fix it —
            // the photos themselves dipped). The outgoing photo
            // now draws opaque and only the incoming fades over
            // it, the textbook crossfade: weights (1 - p) + p = 1
            // at every step. Endpoints identical (p = 0: outgoing
            // only; p = 1: incoming only, prev cleared below),
            // and the v3.09/v3.10 overlay rides are already in
            // exactly these weights (litFade = p, outFade = 1 - p),
            // so halo/flames stay glued to their photos.
            c.drawBitmap(prevPhoto, null, prevDst, paint);
            // v3.21: the mid layer (see setPhoto) draws at its frozen
            // alpha fading out with the new fade, so a retarget
            // starts from the exact visible frame: at p = 0 this is
            // base opaque + mid at its frozen alpha, the frame
            // before the retarget; at p = 1 only the new photo
            // remains. Its own centre-crop rect, like prevDst.
            if (midPhoto != null) {
                float mbw = midPhoto.getWidth(), mbh = midPhoto.getHeight();
                float mscale = Math.max(w / mbw, h / mbh);
                float mdw = mbw * mscale * drift, mdh = mbh * mscale * drift;
                midDst = new RectF((w - mdw) / 2f + panPx, (h - mdh) / 2f + driftYPx,
                        (w + mdw) / 2f + panPx, (h + mdh) / 2f + driftYPx);
                paint.setAlpha((int) (255 * midAlpha * (1f - p)));
                c.drawBitmap(midPhoto, null, midDst, paint);
                paint.setAlpha(255);
            }
            paint.setAlpha((int) (255 * p));
            c.drawBitmap(bm, null, dst, paint);
            paint.setAlpha(255);
            if (p >= 1f) { prevPhoto = null; midPhoto = null; midAlpha = 0f; }
            else postInvalidateDelayed(50);
        } else {
            c.drawBitmap(bm, null, dst, paint);
        }
        // head position converted through the photo's centre-crop rect,
        // so it tracks the statue on every screen shape
        float hx = dst.left + PHOTO_HEAD[photoIndex][0] * dst.width();
        float hy = dst.top + PHOTO_HEAD[photoIndex][1] * dst.height();
        // v1.88: the incense base is shared by the lit-only smoke spawn
        // below and the sticks themselves, which now draw paused too
        // (see after the if/else) instead of vanishing with the flames.
        float incBase = h * 0.78f, incH = 34 * u;
        if (litBlend > 0.01f) {
            // v2.13: the whole lit block now fades with litBlend
            // instead of snapping — halo/glow alphas and flame size
            // scale with the blend, so the lamps light up gradually
            // over the 600ms ramp (smoke spawn still needs lit).
            // v2.07: slower, calmer breath (~4s instead of ~1.6s)
            // and the halo itself swells a few percent with it —
            // the old pulse only changed brightness, so the light
            // felt like a flickering lamp, not a breathing glow.
            float breath = (float) Math.sin(tt * 0.0016);
            float pulse = 0.78f + 0.22f * breath;
            // v3.09: the drawn overlays ride the photo crossfade —
            // the halo, lamp glow and flames are positioned from
            // the NEW photo's dst, so at full strength in the first
            // crossfade frame they sat on lamps/statue of a photo
            // still fading out underneath. They now fade in with
            // the new photo itself (litFade = litBlend * crossP), so a
            // talk change reads as one crossfade, not a photo fade
            // plus an overlay jump. The old photo's own baked-in
            // flames carry the light during the fade, and at p = 1
            // this is exactly the old value. Screen-fixed overlays
            // (sticks, embers, full-screen dim) are untouched —
            // they have no photo position to jump from.
            float litFade = litBlend * crossP;
            // realistic halo: layered soft light behind the head, no lines —
            // light glows, it does not draw itself
            int[][] aura = {{300, 32}, {190, 55}, {110, 90}};
            for (int[] a : aura) {
                paint.setShader(new RadialGradient(hx, hy, a[0] * u * (1f + 0.03f * breath),
                        Color.argb((int) (a[1] * pulse * litFade), 255, 215, 90),
                        Color.TRANSPARENT, Shader.TileMode.CLAMP));
                c.drawCircle(hx, hy, a[0] * u, paint);
            }
            paint.setShader(null);
            // warm lamp glow rising from the offerings at the bottom
            // v3.11: this glow is screen-fixed (cx, h*0.82) — unlike
            // the halo and flames it belongs to no photo, so riding
            // the crossfade with litFade dipped the room's light to
            // half mid-fade while the two photos themselves summed
            // to full brightness. It now holds litBlend through the
            // fade instead; at crossP = 1 this is exactly the old
            // value, and every non-crossfade frame is unchanged.
            paint.setShader(new RadialGradient(cx, h * 0.82f, 230 * u,
                    Color.argb((int) (50 * pulse * litBlend), 255, 170, 60),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.55f, w, h, paint);
            paint.setShader(null);
            // flicker exactly on each photo flame (converted via dst,
            // so the flame never drifts off its lamp on any screen)
            for (int i = 0; i < PHOTO_FLAMES[photoIndex].length; i++) {
                float[] fp = PHOTO_FLAMES[photoIndex][i];
                // v2.13: flames ignite small and grow to full height
                // with the blend instead of popping in at full size.
                flame(c, dst.left + fp[0] * dst.width(), dst.top + fp[1] * dst.height(),
                        fp[2] * u * (0.35f + 0.65f * litFade), i * 1.7f + 0.6f, litFade);
            }
            // incense smoke: thin wisps rising from the stick tips
            // (the sticks themselves now draw below, lit or not)
            // v2.13: smoke spawns only while truly lit, not during
            // the fade-out ramp (the block above now runs on blend).
            // v4.11: a sleep fade is a fade-out ramp too — no new
            // wisps spawn while the lamps are going out, the ones
            // already rising drain with the blend as before.
            if (lit && !sleepFading && frame % 2 == 0 && smokes.size() < 20)
                smokes.add(new Smoke(cx + (random.nextFloat() - 0.5f) * 24 * u, incBase - incH, random.nextFloat() * 6f,
                        2.5f + random.nextFloat() * 1.5f, 6f + random.nextFloat() * 4f));
            // v3.10: the outgoing photo's drawn overlays ride the
            // crossfade out — v3.09 faded the NEW overlays in, but
            // the OLD halo/flames still vanished in one frame while
            // the old photo itself was only ~2% faded, a small pop
            // on every talk change. They now fade out with
            // (1 - crossP) at the OLD photo's head/lamp positions
            // (prevDst), so the light hands over instead of
            // swapping. At crossP = 1 this block draws nothing
            // (outFade = 0) and every non-crossfade frame is
            // pixel-identical; the screen-fixed lamp glow and the
            // sticks/embers below are untouched — they never
            // belonged to either photo.
            if (prevPhoto != null && prevDst != null && prevPhotoIndex != photoIndex
                    && prevPhotoIndex >= 0 && prevPhotoIndex < PHOTO_HEAD.length) {
                float outFade = litBlend * (1f - crossP);
                if (outFade > 0.01f) {
                    float ohx = prevDst.left + PHOTO_HEAD[prevPhotoIndex][0] * prevDst.width();
                    float ohy = prevDst.top + PHOTO_HEAD[prevPhotoIndex][1] * prevDst.height();
                    for (int[] a : aura) {
                        paint.setShader(new RadialGradient(ohx, ohy, a[0] * u * (1f + 0.03f * breath),
                                Color.argb((int) (a[1] * pulse * outFade), 255, 215, 90),
                                Color.TRANSPARENT, Shader.TileMode.CLAMP));
                        c.drawCircle(ohx, ohy, a[0] * u, paint);
                    }
                    paint.setShader(null);
                    for (int i = 0; i < PHOTO_FLAMES[prevPhotoIndex].length; i++) {
                        float[] fp = PHOTO_FLAMES[prevPhotoIndex][i];
                        flame(c, prevDst.left + fp[0] * prevDst.width(), prevDst.top + fp[1] * prevDst.height(),
                                fp[2] * u * (0.35f + 0.65f * outFade), i * 1.7f + 0.6f, outFade);
                    }
                }
            }
            // v3.21: the mid photo's drawn overlays ride out with
            // its photo layer — without this, a mid-fade retarget
            // kept the photo continuous but its halo/flames still
            // popped out in one frame. At the retarget they stood
            // at litBlend * midAlpha (the old incoming ride), and
            // they now fade with the mid layer itself: weight
            // litBlend * midAlpha * (1 - crossP), 0 at the end of
            // the new fade, and every non-retarget frame is
            // untouched (midPhoto null outside a retarget).
            if (midPhoto != null && midDst != null && midPhotoIndex != photoIndex
                    && midPhotoIndex >= 0 && midPhotoIndex < PHOTO_HEAD.length) {
                float midFade = litBlend * midAlpha * (1f - crossP);
                if (midFade > 0.01f) {
                    float mhx = midDst.left + PHOTO_HEAD[midPhotoIndex][0] * midDst.width();
                    float mhy = midDst.top + PHOTO_HEAD[midPhotoIndex][1] * midDst.height();
                    for (int[] a : aura) {
                        paint.setShader(new RadialGradient(mhx, mhy, a[0] * u * (1f + 0.03f * breath),
                                Color.argb((int) (a[1] * pulse * midFade), 255, 215, 90),
                                Color.TRANSPARENT, Shader.TileMode.CLAMP));
                        c.drawCircle(mhx, mhy, a[0] * u, paint);
                    }
                    paint.setShader(null);
                    for (int i = 0; i < PHOTO_FLAMES[midPhotoIndex].length; i++) {
                        float[] fp = PHOTO_FLAMES[midPhotoIndex][i];
                        flame(c, midDst.left + fp[0] * midDst.width(), midDst.top + fp[1] * midDst.height(),
                                fp[2] * u * (0.35f + 0.65f * midFade), i * 1.7f + 0.6f, midFade);
                    }
                }
            }
        }
        // v2.13: NOT an else — during the ramp both halos draw, the
        // lit one fading with the blend and this faint one fading
        // in, so play/pause crossfades instead of swapping.
        if (litBlend < 0.99f) {
            // offerings out: only the faintest trace of light remains
            // v3.09: same crossfade ride as the lit halo above — the
            // faint halo sits at the NEW photo's head position, so
            // during a paused talk change it used to jump there
            // while the old photo was still fading out.
            paint.setShader(new RadialGradient(hx, hy, 190 * u,
                    Color.argb((int) (18 * (1f - litBlend) * crossP), 255, 215, 90),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawCircle(hx, hy, 190 * u, paint);
            paint.setShader(null);
            // v3.10: same outgoing ride for the faint paused halo —
            // on a paused talk change it used to jump to the new
            // head position while the old photo was still fading
            // out; now the old faint halo fades out with it.
            if (prevPhoto != null && prevDst != null && prevPhotoIndex != photoIndex
                    && prevPhotoIndex >= 0 && prevPhotoIndex < PHOTO_HEAD.length) {
                float ohx = prevDst.left + PHOTO_HEAD[prevPhotoIndex][0] * prevDst.width();
                float ohy = prevDst.top + PHOTO_HEAD[prevPhotoIndex][1] * prevDst.height();
                paint.setShader(new RadialGradient(ohx, ohy, 190 * u,
                        Color.argb((int) (18 * (1f - litBlend) * (1f - crossP)), 255, 215, 90),
                        Color.TRANSPARENT, Shader.TileMode.CLAMP));
                c.drawCircle(ohx, ohy, 190 * u, paint);
                paint.setShader(null);
            }
            // v3.21: same mid ride for the faint paused halo — on a
            // paused mid-fade retarget it used to jump with the new
            // photo's halo while the mid photo was still fading
            // out; it now fades with the mid layer itself.
            if (midPhoto != null && midDst != null && midPhotoIndex != photoIndex
                    && midPhotoIndex >= 0 && midPhotoIndex < PHOTO_HEAD.length) {
                float mhx = midDst.left + PHOTO_HEAD[midPhotoIndex][0] * midDst.width();
                float mhy = midDst.top + PHOTO_HEAD[midPhotoIndex][1] * midDst.height();
                paint.setShader(new RadialGradient(mhx, mhy, 190 * u,
                        Color.argb((int) (18 * (1f - litBlend) * midAlpha * (1f - crossP)), 255, 215, 90),
                        Color.TRANSPARENT, Shader.TileMode.CLAMP));
                c.drawCircle(mhx, mhy, 190 * u, paint);
                paint.setShader(null);
            }
        }
        // v2.13: the faint paused halo above is drawn only while the
        // blend is below full, so during the ramp it crossfades
        // against the lit halo instead of either snapping.
        // v1.88: the sticks + holder used to live inside the lit block
        // above, so pausing made the whole incense offering vanish —
        // v1.46 says paused = dark tips, no smoke, and the cartoon
        // fallback already draws them that way. They draw in both
        // states now (ember only while lit); the dim rect below still
        // dims them with the rest of the shrine when paused.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f * u);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = -1; i <= 1; i++) {
            float x0 = cx + i * 8 * u, x1 = x0 + i * 3 * u;
            paint.setColor(Color.parseColor("#6B4A2F"));
            c.drawLine(x0, incBase, x1, incBase - incH, paint);
            // v2.75: the dark tip always draws underneath first, so
            // the ember cools by revealing it instead of popping
            // from a small orange dot to a larger grey one at the
            // end of the ramp (the old code only drew grey once
            // the blend hit zero). At full lit the orange core
            // (2.6u) covers the grey tip (2.2u) completely.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.parseColor("#444444"));
            c.drawCircle(x1, incBase - incH, 2.2f * u, paint);
            if (litBlend > 0.01f) {
                // v2.07: ember tips breathe with the halo instead of
                // burning at one flat brightness.
                float emberPulse = 1f + 0.08f * (float) Math.sin(tt * 0.0016);
                // v2.13: ember cools with the blend — glow alpha and
                // size shrink as the lamps go out, dark tips take over
                // only once the blend reaches zero (below).
                // v2.75: the core itself now fades its alpha with the
                // blend too — it used to stay full orange while
                // shrinking, then swap to grey in one frame.
                // glowing ember tip
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb((int) (60 * litBlend), 255, 100, 0));
                c.drawCircle(x1, incBase - incH, 7 * u * emberPulse * (0.4f + 0.6f * litBlend), paint);
                paint.setColor(Color.argb((int) (255 * litBlend), 255, 106, 0));
                c.drawCircle(x1, incBase - incH, 2.6f * u * emberPulse * (0.4f + 0.6f * litBlend), paint);
            }
            paint.setStyle(Paint.Style.STROKE);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#8C6A1F"));
        c.drawOval(cx - 22 * u, incBase - 5 * u, cx + 22 * u, incBase + 7 * u, paint);
        paint.setColor(Color.parseColor("#C9A227"));
        c.drawOval(cx - 22 * u, incBase - 7 * u, cx + 22 * u, incBase + 1 * u, paint);
        java.util.Iterator<Smoke> it = smokes.iterator();
        while (it.hasNext()) {
            Smoke s = it.next();
            s.y -= s.speed;
            float risen = s.startY - s.y;
            // v2.07: the sway widens as the wisp climbs, like real
            // smoke catching the air, instead of one fixed wiggle.
            // v11.79: absolute form, like the AI/theme scenes — the old
            // incremental += drifted the puff sideways off the incense
            // column over its life instead of swaying around its base.
            s.x = s.bx + (float) Math.sin(risen * 0.05 + s.phase) * (2.0f + risen * 0.012f);
            s.size += 0.25f;
            if (lit) s.alpha = Math.max(0, 0.38f - (s.startY - s.y) / (h * 0.30f));
            else s.alpha -= 0.08f;
            if (s.alpha <= 0 || s.y < h * 0.05f) { it.remove(); continue; }
            drawSmokePuff(c, s);
        }
        if (litBlend < 1f) {
            // v2.13: lamps out — the shrine dims, as if the offerings
            // were put out; the dim now fades with the blend instead
            // of snapping on at full strength the frame play stops.
            paint.setColor(Color.argb((int) (90 * (1f - litBlend)), 0, 0, 0));
            c.drawRect(0, 0, w, h, paint);
        }
    }

    public void setTheme(int idx) {
        if (idx < 0 || idx >= THEMES.length) idx = 0;
        themeIndex = idx;
        theme = THEMES[idx];
        wallGrad = null;
        invalidate();
    }

    public String getThemeName() { return theme.name; }
    public int getThemeIndex() { return themeIndex; }

    private static int shade(int color, float f) {
        int r = Math.min(255, Math.max(0, (int) (Color.red(color) * f)));
        int g = Math.min(255, Math.max(0, (int) (Color.green(color) * f)));
        int b = Math.min(255, Math.max(0, (int) (Color.blue(color) * f)));
        return Color.rgb(r, g, b);
    }

    private void drawMotif(Canvas c, float cx, float altarY, float headY, long t) {
        switch (theme.motif) {
            case 1: // pillars
                paint.setColor(theme.arch);
                c.drawRect(14 * u, 0, 40 * u, h * 0.76f, paint);
                c.drawRect(w - 40 * u, 0, w - 14 * u, h * 0.76f, paint);
                paint.setColor(theme.goldDark);
                c.drawRect(10 * u, 0, 44 * u, 8 * u, paint);
                c.drawRect(w - 44 * u, 0, w - 10 * u, 8 * u, paint);
                c.drawRect(10 * u, h * 0.76f - 8 * u, 44 * u, h * 0.76f, paint);
                c.drawRect(w - 44 * u, h * 0.76f - 8 * u, w - 10 * u, h * 0.76f, paint);
                break;
            case 2: // moon + stars
                paint.setColor(Color.argb(40, 255, 240, 180));
                c.drawCircle(w - 64 * u, 78 * u, 46 * u, paint);
                paint.setColor(Color.parseColor("#FFF8D0"));
                c.drawCircle(w - 64 * u, 78 * u, 26 * u, paint);
                paint.setColor(Color.parseColor("#E8D9A0"));
                c.drawCircle(w - 74 * u, 70 * u, 6 * u, paint);
                c.drawCircle(w - 56 * u, 86 * u, 5 * u, paint);
                paint.setColor(Color.WHITE);
                for (int i = 0; i < 18; i++) {
                    float sx = ((i * 67) % 340 + 20) * u;
                    float sy = ((i * 53) % 180 + 20) * u;
                    if (Math.abs(sx - (w - 64 * u)) < 50 * u && Math.abs(sy - 78 * u) < 50 * u) continue;
                    c.drawCircle(sx, sy, 2.5f * u, paint);
                }
                break;
            case 3: // sunrise behind the arch
                paint.setColor(Color.argb(60, 255, 180, 80));
                c.drawCircle(cx, h * 0.10f, 90 * u, paint);
                paint.setColor(Color.parseColor("#FFB84A"));
                c.drawCircle(cx, h * 0.10f, 46 * u, paint);
                paint.setStrokeWidth(3 * u);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(Color.argb(120, 255, 180, 80));
                for (int i = 0; i < 12; i++) {
                    double a = i * Math.PI * 2 / 12 - Math.PI / 2;
                    c.drawLine(cx + (float) Math.cos(a) * 58 * u, h * 0.10f + (float) Math.sin(a) * 58 * u,
                            cx + (float) Math.cos(a) * 82 * u, h * 0.10f + (float) Math.sin(a) * 82 * u, paint);
                }
                paint.setStyle(Paint.Style.FILL);
                break;
            case 4: // curtains from the top corners
                paint.setColor(theme.robe);
                path.reset();
                path.moveTo(0, 0);
                path.lineTo(96 * u, 0);
                path.lineTo(70 * u, h * 0.42f);
                path.lineTo(34 * u, h * 0.30f);
                path.close();
                c.drawPath(path, paint);
                path.reset();
                path.moveTo(w, 0);
                path.lineTo(w - 96 * u, 0);
                path.lineTo(w - 70 * u, h * 0.42f);
                path.lineTo(w - 34 * u, h * 0.30f);
                path.close();
                c.drawPath(path, paint);
                paint.setColor(shade(theme.robe, 0.7f));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2 * u);
                c.drawLine(48 * u, 0, 52 * u, h * 0.34f, paint);
                c.drawLine(w - 48 * u, 0, w - 52 * u, h * 0.34f, paint);
                paint.setStyle(Paint.Style.FILL);
                break;
            case 5: // hanging lanterns
                float[] lx = {cx - 120 * u, cx, cx + 120 * u};
                for (int i = 0; i < 3; i++) {
                    paint.setColor(Color.parseColor("#3A2415"));
                    c.drawRect(lx[i] - 1 * u, 0, lx[i] + 1 * u, 62 * u, paint);
                    // v2.77: lantern glow fades with litBlend, like
                    // the photo-scene halo, instead of popping with lit.
                    if (litBlend > 0.01f) {
                        paint.setColor(Color.argb((int) (50 * litBlend), 255, 180, 80));
                        c.drawCircle(lx[i], 84 * u, 30 * u, paint);
                    }
                    paint.setColor(Color.parseColor("#C0392B"));
                    rect.set(lx[i] - 20 * u, 62 * u, lx[i] + 20 * u, 106 * u);
                    c.drawRoundRect(rect, 10 * u, 10 * u, paint);
                    // v2.77: lantern window crossfades dark-red ->
                    // lit-yellow with litBlend (endpoints exact).
                    int lr = (int) (0x8A + (0xFF - 0x8A) * litBlend);
                    int lg = (int) (0x3A + (0xD5 - 0x3A) * litBlend);
                    int lb = (int) (0x2A + (0x4A - 0x2A) * litBlend);
                    paint.setColor(Color.rgb(lr, lg, lb));
                    rect.set(lx[i] - 12 * u, 72 * u, lx[i] + 12 * u, 96 * u);
                    c.drawRoundRect(rect, 6 * u, 6 * u, paint);
                }
                break;
            case 6: // flower garland across the top
                for (int i = 0; i <= 14; i++) {
                    float gx = i * (w / 14f);
                    float gy = 30 * u + (float) Math.sin(i * 0.9f) * 8 * u;
                    paint.setColor(i % 2 == 0 ? theme.flowerA : theme.flowerB);
                    c.drawCircle(gx, gy, 9 * u, paint);
                    paint.setColor(Color.parseColor("#FFD54A"));
                    c.drawCircle(gx, gy, 3.5f * u, paint);
                }
                break;
            case 7: // clouds
                paint.setColor(Color.argb(70, 255, 255, 255));
                c.drawOval(new RectF(20 * u, 30 * u, 110 * u, 62 * u), paint);
                c.drawOval(new RectF(60 * u, 18 * u, 140 * u, 54 * u), paint);
                c.drawOval(new RectF(w - 150 * u, 110 * u, w - 60 * u, 140 * u), paint);
                c.drawOval(new RectF(w - 110 * u, 98 * u, w - 30 * u, 128 * u), paint);
                break;
            default:
                break;
        }
    }

    private final Runnable animator = new Runnable() {
        @Override public void run() {
            animatorLive = true;
            // v2.13: step the light-transition blend toward its
            // target (12 frames x 50ms = ~600ms) and keep animating
            // while it is mid-ramp, even with no smoke to drain.
            // v4.11: a sleep fade retargets the lamps to dark while
            // the talk still counts as playing, so the shrine goes
            // out with the sound instead of at the fade-end pause.
            float target = (lit && !sleepFading) ? 1f : 0f;
            if (litBlend < target) litBlend = Math.min(target, litBlend + 50f / 600f);
            else if (litBlend > target) litBlend = Math.max(target, litBlend - 50f / 600f);
            // v2.26: Ken Burns clock — playing only, so pause freezes
            // the zoom and resume continues it instead of jumping.
            if (lit) driftMs += 50;
            frame++;
            invalidate();
            if (lit || !smokes.isEmpty() || litBlend != target) postDelayed(this, 50);
            else animatorLive = false;
        }
    };

    public RoomView(Context c) { super(c); }
    public RoomView(Context c, AttributeSet a) { super(c, a); }

    // v3.88: premium rotation scene restore — the living scene's
    // state (litBlend, the Ken Burns drift clock) lived only in
    // memory, so a rotation while a talk played rebuilt the view
    // dark (litBlend 0, driftMs 0): the shrine flashed dim and
    // relit over the 600ms ramp while the photo jumped back to
    // its cycle start, even though the photo itself survived
    // rotation since v1.51 and the panel/list were restored
    // as-is. The Room now saves these three values in
    // onSaveInstanceState and restores them here before the
    // first draw, so the recreated view opens at the exact lit
    // level and drift position the old one had. The poll's
    // setPlaying is then a no-op (state already matches), never
    // a ramp restart; a paused-mid-ramp restore keeps ramping
    // down via the animator's own re-post rule, and a fully-dim
    // paused restore posts nothing, exactly as before.
    // Values are clamped so a stale bundle can never strand
    // the view over-lit or with a negative clock.
    public boolean isLit() { return lit; }
    public float getLitBlend() { return litBlend; }
    public long getDriftMs() { return driftMs; }
    // v3.89: smoke state as position fractions, so a rotation never
    // strands wisps at the old screen's pixel spots. Each puff is 7
    // floats: x/w, y/h, startY/h, alpha, size, phase, speed. While
    // lit the draw loop recomputes alpha from the risen distance,
    // so the stored alpha only matters to a paused drain — but it
    // is stored anyway, so a paused Room restores mid-drain, not
    // at full strength. Null when unmeasured or smokeless.
    public float[] getSmokeState() {
        if (w <= 0 || h <= 0 || smokes.isEmpty()) return null;
        // v11.79: 8 floats per puff — the 8th is the sway base bx. The old
        // 7-float form dropped it, so a rotation rebuilt every puff with
        // bx = its current x and the ribbon jumped sideways by up to ~28px.
        float[] out = new float[smokes.size() * 8];
        int i = 0;
        for (Smoke s : smokes) {
            out[i++] = Math.max(0f, Math.min(1f, s.x / w));
            out[i++] = Math.max(0f, Math.min(1f, s.y / h));
            out[i++] = Math.max(0f, Math.min(1f, s.startY / h));
            out[i++] = Math.max(0f, Math.min(1f, s.alpha));
            out[i++] = Math.max(0f, s.size);
            out[i++] = s.phase;
            out[i++] = Math.max(0f, s.speed);
            out[i++] = Math.max(0f, Math.min(1f, s.bx / w));
        }
        return out;
    }
    // v3.89: hold restored puffs until the view is measured; a
    // measured view applies them at once. Malformed bundles (wrong
    // length, stale values) are dropped, never trusted — the worst
    // case is the old behaviour, wisps respawning over a second.
    public void restoreSmoke(float[] flat) {
        pendingSmoke = null;
        if (flat == null || flat.length < 7) return;
        // v11.79: stride 8 carries bx (see getSmokeState); stride 7 is an
        // older bundle — still accepted, with bx defaulting to the puff's
        // current x (the old behaviour). The bundle only ever crosses a
        // rotation inside one process, so no cross-version bundle exists;
        // the dual stride is belt-and-braces, not a migration path.
        int stride = (flat.length % 8 == 0) ? 8 : 7;
        if (flat.length % stride != 0) return;
        pendingSmoke = new ArrayList<float[]>();
        // v11.13: restore cap raised 26 -> 220 — the v9.43 wisp rework
        // raised the AI scenes' live smoke cap to 220 (steady state
        // ~200 puffs forming one ribbon), but this restore path still
        // capped at the old drawn-scene 26, so a rotation thinned the
        // incense ribbon to a wisp and took ~20s to rebuild. The
        // bundle cost is 220*8 floats (~7KB), far under the limit,
        // and the drawn/photo scenes never hold more than their own
        // spawn caps, so their restores are unchanged in practice.
        for (int i = 0; i + stride - 1 < flat.length && pendingSmoke.size() < 220; i += stride) {
            float[] p = new float[8];
            p[0] = Math.max(0f, Math.min(1f, flat[i]));
            p[1] = Math.max(0f, Math.min(1f, flat[i + 1]));
            p[2] = Math.max(0f, Math.min(1f, flat[i + 2]));
            p[3] = Math.max(0f, Math.min(1f, flat[i + 3]));
            p[4] = Math.max(0f, flat[i + 4]);
            p[5] = flat[i + 5];
            p[6] = Math.max(0f, flat[i + 6]);
            p[7] = (stride == 8) ? Math.max(0f, Math.min(1f, flat[i + 7])) : p[0];
            pendingSmoke.add(p);
        }
        if (w > 0 && h > 0) applyPendingSmoke();
    }
    // v3.89: turn pending fractions into live puffs at the current
    // size. Runs once (pending is cleared), from onSizeChanged on
    // a recreated view or at once on an already-measured one. Both
    // scenes share this list and the same rise/sway/drain math, so
    // one restore covers the photo scene and the cartoon fallback.
    private void applyPendingSmoke() {
        if (pendingSmoke == null || pendingSmoke.isEmpty() || w <= 0 || h <= 0) return;
        for (float[] p : pendingSmoke) {
            if (smokes.size() >= 220) break; // v11.13: match the AI-scene cap (see restoreSmoke)
            Smoke s = new Smoke(p[0] * w, p[2] * h, p[5]);
            s.y = p[1] * h;
            s.alpha = p[3];
            s.size = p[4];
            s.speed = p[6];
            s.bx = p[7] * w; // v11.79: restore the sway base, not the current x
            smokes.add(s);
        }
        pendingSmoke = null;
        if (!smokes.isEmpty() && !animatorLive) {
            removeCallbacks(animator);
            post(animator);
            animatorLive = true;
        }
    }
    public void restoreScene(boolean playing, float blend, long drift) {
        lit = playing;
        litBlend = Math.max(0f, Math.min(1f, blend));
        driftMs = Math.max(0L, drift);
        removeCallbacks(animator);
        animatorLive = false;
        // v3.89: pending smoke counts as work to do — without it a
        // paused Room restored with draining puffs posted nothing
        // and the wisps froze until the next play/pause toggle.
        if (lit || (pendingSmoke != null && !pendingSmoke.isEmpty())
                || litBlend != (lit ? 1f : 0f)) {
            post(animator);
            animatorLive = true;
        }
        invalidate();
    }

    // v4.11: sleep-fade feed from the Room poll (same change-only
    // pattern as the wave's setSleepFading). Runs before
    // setPlaying in the poll so a fade starting into play dims
    // straight from the current blend instead of flashing full
    // lamps first; a steady state never re-posts. The re-post
    // covers a rotation landing mid-fade, where restoreScene
    // cannot know the flag and the poll's setPlaying is a no-op.
    // Pause never sets the flag, so the normal pause dim is
    // untouched, and the flag is invisible once lit is false
    // (target 0 either way), so a stale flag can never strand
    // the lamps — the next poll tick clears it anyway.
    public void setSleepFading(boolean fading) {
        if (fading == sleepFading) return;
        sleepFading = fading;
        removeCallbacks(animator);
        post(animator);
        animatorLive = true;
    }

    public void setPlaying(boolean playing) {
        // v1.71: no-op when the state is unchanged — the old guard
        // only skipped playing→playing, so every 500ms Room poll
        // re-posted a one-frame animator run while paused (smoke
        // only rises while lit, and a draining animator already
        // re-posts itself until its puffs fade, so nothing is lost).
        if (playing == lit) return;
        lit = playing;
        removeCallbacks(animator);
        post(animator);
        animatorLive = true;
    }

    // v1.83: stop the flame/smoke animator when the view leaves the
    // window — destroying the Room while smoke puffs were still
    // draining left the animator queued on the detached view, which
    // kept the whole view (and its photos) referenced until it ever
    // reattached, which a destroyed Room never does.
    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(animator);
        animatorLive = false;
        removeCallbacks(fadeTicker); // v3.25: no tick on a dead view
        // v11.02: never reattach dimmed — a detach mid-dip must not
        // strand the altar at 0.55 (the end action may never run).
        animate().cancel();
        setAlpha(1f);
        super.onDetachedFromWindow();
    }

    // v1.87: the other half of v1.83 — a view that DETACHES and
    // REATTACHES (rotation recreates the Room's view) came back
    // with its animator dead, and the Room poll's setPlaying() is a
    // no-op when the state is unchanged, so lit flames and draining
    // smoke froze until the next play/pause toggle. Restart the
    // animator on reattach while anything still needs animating.
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (lit || !smokes.isEmpty() || litBlend != (lit ? 1f : 0f)) {
            removeCallbacks(animator);
            post(animator);
            animatorLive = true;
        }
        // v3.25: a fade in flight across a detach/reattach resumes
        // its own frames too, not just the lit animator above.
        if (prevPhoto != null) {
            removeCallbacks(fadeTicker);
            post(fadeTicker);
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldW, int oldH) {
        super.onSizeChanged(width, height, oldW, oldH);
        w = width; h = height; u = w / 400f;
        // v3.89: first measurement after a recreation turns the
        // pending smoke fractions into live puffs (and, if the
        // animator is not already running, starts it so paused
        // puffs keep draining instead of freezing mid-air).
        applyPendingSmoke();
        wallGrad = new LinearGradient(0, 0, 0, h * 0.75f,
                Color.parseColor("#3A2415"), Color.parseColor("#5A3A20"), Shader.TileMode.CLAMP);
    }

    // one wispy smoke puff: soft radial gradient, never a hard bubble
    // v3.03: premium smoke lit-fade — the v2.13 ramp fades flames,
    // halo and dim with litBlend and v2.75 extended it to flame
    // alpha, but smoke kept drawing at its own full alpha, so on
    // play the first wisps popped in at near-full strength while
    // the lamps were still ramping up over 600ms, and on pause
    // they drained at their own rate instead of with the lamps
    // going out. The puff alpha now scales with litBlend, so
    // smoke fades in with the lamps and out with them; spawn
    // still needs lit (v2.13), rise/drain rates are untouched,
    // and both draw paths share this one method (photo + cartoon
    // fallback), so both get the fade. Visual only.
    private void drawSmokePuff(Canvas c, Smoke s) {
        int a = (int) (95 * s.alpha * litBlend);
        if (a <= 0) return;
        // v9.43: puffs are drawn STRETCHED vertically (0.72 x 1.3),
        // so a wisp reads as a curling ribbon, not a round ball.
        // v11.79: the gradient MUST be centred at (0,0), not (s.x, s.y):
        // the canvas translate below already moves the circle to the
        // puff, and the shader is transformed by the same matrix, so
        // absolute coords counted the position twice — the gradient's
        // bright centre landed ~600-1100px from the circle and, with
        // CLAMP, every puff drew as a fully transparent circle. Smoke
        // was invisible in every scene since v9.43 (proven in Skia:
        // as-shipped brightest pixel = background, fixed = wisp).
        paint.setShader(new RadialGradient(0, 0, Math.max(1f, s.size),
                Color.argb(a, 235, 235, 235), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.save();
        c.translate(s.x, s.y);
        c.scale(0.72f, 1.3f);
        c.drawCircle(0, 0, Math.max(1f, s.size), paint);
        c.restore();
        paint.setShader(null);
    }

    // v2.75: premium lit-fade completion — the v2.13 ramp faded
    // the halo, glow and dim with litBlend, but the flames it
    // drew kept full solid colours and only shrank to 35% height,
    // so at the end of the 600ms pause ramp they vanished in one
    // frame at a third of full size. Every flame layer now also
    // fades its alpha with the blend (photo path passes litBlend;
    // v2.77: the cartoon fallback passes litBlend too, height-scaled
    // like the photo path, instead of lit-guarded at full size).
    // Height shrink + alpha fade together mean
    // the last frame is already invisible — no pop at blend end.
    private void flame(Canvas c, float x, float y, float baseH, float phase, float alpha) {
        long t = SystemClock.uptimeMillis();
        float flick = 1f + 0.20f * (float) Math.sin(t * 0.018 + phase)
                + 0.10f * (float) Math.sin(t * 0.043 + phase * 2f)
                + (random.nextFloat() - 0.5f) * 0.05f;
        // v2.07: the flame leans gently with the heat instead of
        // standing dead upright — the whole flame (glow included)
        // sways a few percent of its height.
        x += (float) Math.sin(t * 0.003 + phase) * baseH * 0.07f;
        float fh = baseH * flick;
        float fw = baseH * 0.42f;
        int a = Math.max(0, Math.min(255, (int) (255 * alpha)));
        // glow — v9.40: was a flat-alpha disc (argb 28) whose rim
        // read as a brown bubble on the dark wood in his video
        // frames. Now a radial fade (bright centre -> transparent
        // edge), so there is no rim at all, just light.
        paint.setShader(new RadialGradient(x, y - fh * 0.45f, fw * 2.4f,
                Color.argb((int) (46 * alpha), 255, 140, 0), Color.TRANSPARENT,
                Shader.TileMode.CLAMP));
        c.drawCircle(x, y - fh * 0.45f, fw * 2.4f, paint);
        paint.setShader(null);
        // outer flame
        path.reset();
        path.moveTo(x, y - fh);
        path.cubicTo(x + fw, y - fh * 0.55f, x + fw * 0.7f, y - fh * 0.1f, x, y);
        path.cubicTo(x - fw * 0.7f, y - fh * 0.1f, x - fw, y - fh * 0.55f, x, y - fh);
        path.close();
        paint.setColor(Color.argb(a, 255, 122, 0));
        c.drawPath(path, paint);
        // inner flame
        path.reset();
        path.moveTo(x, y - fh * 0.62f);
        path.cubicTo(x + fw * 0.55f, y - fh * 0.3f, x + fw * 0.35f, y, x, y);
        path.cubicTo(x - fw * 0.35f, y, x - fw * 0.55f, y - fh * 0.3f, x, y - fh * 0.62f);
        path.close();
        paint.setColor(Color.argb(a, 255, 213, 74));
        c.drawPath(path, paint);
        // core
        paint.setColor(Color.argb(a, 255, 248, 208));
        c.drawOval(x - fw * 0.16f, y - fh * 0.22f, x + fw * 0.16f, y, paint);
    }

    private void flower(Canvas c, float x, float y, float r, int color) {
        paint.setColor(color);
        for (int i = 0; i < 5; i++) {
            double a = i * Math.PI * 2 / 5 - Math.PI / 2;
            c.drawCircle(x + (float) Math.cos(a) * r * 0.9f, y + (float) Math.sin(a) * r * 0.9f, r * 0.62f, paint);
        }
        paint.setColor(Color.parseColor("#FFD54A"));
        c.drawCircle(x, y, r * 0.45f, paint);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (w == 0) return;
        long t = SystemClock.uptimeMillis();
        float cx = w / 2f;
        float floorY = h * 0.76f;
        // v11.79: the whole drawn scene moved up 0.12h — head and altar
        // together, so the statue's proportions are unchanged. The old
        // altarY (0.52h) put the lamp bowls (0.572h), incense holder and
        // water tray (0.62h) below the panel top (~0.55h), so on the
        // fallback scene only flame tips showed above the panel and the
        // water offerings, mat and cushion were hidden entirely. Now the
        // lowest offering (water tray bottom, ~0.53h) clears the panel.
        float altarY = h * 0.40f;
        float headY = h * 0.14f;
        float headR = 34 * u;

        // v9.15 (user order 2026-11-02 ~21:28 +07): the composed shrine
        // scene below IS the Room altar now. The photos are retired from
        // display: their lamps sat at different spots per photo while the
        // incense sticks were screen-fixed, so flames and sticks could
        // never stay glued to the statue — the misalignment he named.
        // The drawn scene puts Buddha, halo, offerings, flames and
        // smoke in ONE coordinate space, so a flame always sits on
        // its own lamp's wick and smoke always rises from its own
        // stick tips. drawPhotoScene/loadPhotos stay in the file
        // unused (assets stay on disk); setPhoto now switches themes.
        // v9.17 (user order 2026-11-02 ~21:36 +07): the near-real AI
        // altar image wins over the drawn scene when it loads — the
        // drawn scene stays as its fallback. Same one-coordinate
        // rule, photorealistic look.
        loadAiImage();
        if (aiImage != null) {
            if (aiTheme == 0) drawAiScene(c, cx, t);
            else drawThemeScene(c, cx, t);
            return;
        }

        // wall + floor (from the current theme)
        paint.setShader(new LinearGradient(0, 0, 0, floorY, theme.wallTop, theme.wallBottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, floorY, paint);
        paint.setShader(null);
        paint.setColor(theme.floor);
        c.drawRect(0, floorY, w, h, paint);
        paint.setColor(shade(theme.floor, 1.15f));
        c.drawRect(0, floorY, w, floorY + 4 * u, paint);
        drawMotif(c, cx, altarY, headY, t);

        // soft shadow the altar casts on the floor
        paint.setColor(Color.argb(70, 0, 0, 0));
        rect.set(cx - 165 * u, floorY - 6 * u, cx + 165 * u, floorY + 22 * u);
        c.drawOval(rect, paint);
        paint.setColor(Color.argb(50, 0, 0, 0));
        rect.set(cx - 105 * u, altarY + 58 * u, cx + 105 * u, altarY + 76 * u);
        c.drawOval(rect, paint);

        // arch behind buddha
        paint.setColor(theme.arch);
        rect.set(cx - 130 * u, h * 0.06f, cx + 130 * u, altarY + 10 * u);
        c.drawRoundRect(rect, 90 * u, 90 * u, paint);
        paint.setColor(shade(theme.arch, 1.15f));
        rect.set(cx - 118 * u, h * 0.08f, cx + 118 * u, altarY + 2 * u);
        c.drawRoundRect(rect, 80 * u, 80 * u, paint);

        // halo rays behind head — v9.15: the rays shimmer (per-ray alpha),
        // so the radiance behind the head visibly shimmers instead of
        // standing still; the glow below breathes too. v11.79: the slow
        // rotation is GONE — the arc spans only the upper half-circle, so
        // rotating it swept the rays down behind the body (drawn after the
        // rays, wider than the ray reach) and the halo vanished for ~30s
        // of every ~2min cycle. The AI scene's full 24-ray circle can
        // rotate invisibly by symmetry; this half-arc cannot.
        float haloBreath = 0.85f + 0.15f * (float) Math.sin(t * 0.0016);
        paint.setStrokeWidth(3 * u);
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i <= 11; i++) {
            double a = -Math.PI + i * Math.PI / 11;
            // v2.77: halo rays crossfade with litBlend (0.18 flat
            // when dark) instead of snapping between the two values.
            float litShimmer = 0.55f + 0.45f * (float) Math.sin(t * 0.004 + i * 0.9f);
            float shimmer = 0.18f * (1f - litBlend) + litShimmer * litBlend;
            paint.setColor(Color.argb((int) (150 * shimmer), Color.red(theme.halo), Color.green(theme.halo), Color.blue(theme.halo)));
            float r1 = headR * 1.5f, r2 = headR * 2.5f;
            c.drawLine(cx + (float) Math.cos(a) * r1, headY + (float) Math.sin(a) * r1,
                    cx + (float) Math.cos(a) * r2, headY + (float) Math.sin(a) * r2, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, headY, headR * 2.6f,
                Color.argb((int) ((30 + 80 * litBlend) * haloBreath), Color.red(theme.halo), Color.green(theme.halo), Color.blue(theme.halo)),
                Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawCircle(cx, headY, headR * 2.6f, paint);
        paint.setShader(null);

        // buddha body (gold robe) — radial gold gives the round 3D form
        paint.setShader(new RadialGradient(cx - 30 * u, headY + headR + 30 * u, 130 * u,
                theme.goldLight, theme.goldDark, Shader.TileMode.CLAMP));
        // shoulders to lap
        path.reset();
        path.moveTo(cx - 52 * u, headY + headR + 6 * u);
        path.cubicTo(cx - 78 * u, headY + headR + 42 * u, cx - 88 * u, altarY - 78 * u, cx - 92 * u, altarY - 62 * u);
        path.lineTo(cx + 92 * u, altarY - 62 * u);
        path.cubicTo(cx + 88 * u, altarY - 78 * u, cx + 78 * u, headY + headR + 42 * u, cx + 52 * u, headY + headR + 6 * u);
        path.close();
        c.drawPath(path, paint);
        paint.setShader(null);
        // maroon sash — darker fold lines make the cloth fold in 3D
        paint.setColor(theme.robe);
        path.reset();
        path.moveTo(cx - 30 * u, headY + headR + 8 * u);
        path.lineTo(cx + 10 * u, headY + headR + 8 * u);
        path.lineTo(cx - 38 * u, altarY - 62 * u);
        path.lineTo(cx - 72 * u, altarY - 62 * u);
        path.close();
        c.drawPath(path, paint);
        paint.setColor(shade(theme.robe, 0.75f));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * u);
        path.reset();
        path.moveTo(cx - 22 * u, headY + headR + 20 * u);
        path.lineTo(cx - 52 * u, altarY - 62 * u);
        path.moveTo(cx - 8 * u, headY + headR + 22 * u);
        path.lineTo(cx - 34 * u, altarY - 62 * u);
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        // lap / crossed legs — light on top, shaded below = rounded legs
        paint.setShader(new LinearGradient(0, altarY - 70 * u, 0, altarY - 34 * u,
                theme.goldLight, theme.goldDark, Shader.TileMode.CLAMP));
        rect.set(cx - 92 * u, altarY - 70 * u, cx + 92 * u, altarY - 34 * u);
        c.drawOval(rect, paint);
        paint.setShader(null);
        paint.setColor(Color.parseColor("#B8962E"));
        rect.set(cx - 70 * u, altarY - 62 * u, cx + 70 * u, altarY - 40 * u);
        c.drawOval(rect, paint);
        // v9.15: folded hands in dhyana mudra on the lap — the old
        // body read as seated, but the meditation pose had no hands.
        // Two overlapping ovals, palms up, resting on the crossed legs.
        paint.setColor(shade(theme.goldDark, 0.8f));
        c.drawOval(new RectF(cx - 26 * u, altarY - 56 * u, cx + 2 * u, altarY - 42 * u), paint);
        paint.setColor(theme.goldLight);
        c.drawOval(new RectF(cx - 2 * u, altarY - 56 * u, cx + 26 * u, altarY - 42 * u), paint);
        paint.setColor(shade(theme.goldDark, 0.7f));
        c.drawOval(new RectF(cx - 6 * u, altarY - 52 * u, cx + 6 * u, altarY - 46 * u), paint);
        // head — radial gold + a soft highlight on the cheek
        paint.setShader(new RadialGradient(cx - 12 * u, headY - 14 * u, headR * 1.4f,
                theme.goldLight, theme.goldDark, Shader.TileMode.CLAMP));
        c.drawCircle(cx, headY, headR, paint);
        paint.setShader(null);
        paint.setColor(Color.parseColor("#FFF2B0"));
        c.drawCircle(cx - 12 * u, headY - 12 * u, headR * 0.28f, paint);
        paint.setColor(theme.goldLight);
        c.drawCircle(cx, headY - headR - 4 * u, headR * 0.38f, paint);
        // ears
        rect.set(cx - headR - 10 * u, headY - 8 * u, cx - headR + 6 * u, headY + 22 * u);
        c.drawOval(rect, paint);
        rect.set(cx + headR - 6 * u, headY - 8 * u, cx + headR + 10 * u, headY + 22 * u);
        c.drawOval(rect, paint);
        // serene face: closed eyes + smile
        paint.setColor(Color.parseColor("#5A3A20"));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.5f * u);
        rect.set(cx - 22 * u, headY - 6 * u, cx - 6 * u, headY + 8 * u);
        c.drawArc(rect, 20, 140, false, paint);
        rect.set(cx + 6 * u, headY - 6 * u, cx + 22 * u, headY + 8 * u);
        c.drawArc(rect, 20, 140, false, paint);
        rect.set(cx - 10 * u, headY + 10 * u, cx + 10 * u, headY + 22 * u);
        c.drawArc(rect, 15, 150, false, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(theme.robe);
        c.drawCircle(cx, headY - 12 * u, 3 * u, paint);
        // lotus pedestal — bevel: bright top edge, shaded base
        paint.setShader(new LinearGradient(0, altarY - 36 * u, 0, altarY - 8 * u,
                theme.goldLight, shade(theme.goldDark, 0.65f), Shader.TileMode.CLAMP));
        rect.set(cx - 100 * u, altarY - 36 * u, cx + 100 * u, altarY - 8 * u);
        c.drawRoundRect(rect, 10 * u, 10 * u, paint);
        paint.setShader(null);
        paint.setColor(Color.argb(140, 255, 242, 176));
        c.drawRoundRect(new RectF(cx - 100 * u, altarY - 36 * u, cx + 100 * u, altarY - 30 * u), 10 * u, 10 * u, paint);
        paint.setColor(theme.goldLight);
        for (int i = -3; i <= 3; i++) {
            rect.set(cx + i * 26 * u - 12 * u, altarY - 52 * u, cx + i * 26 * u + 12 * u, altarY - 30 * u);
            c.drawOval(rect, paint);
            paint.setColor(Color.parseColor("#FFF2B0"));
            c.drawOval(new RectF(cx + i * 26 * u - 8 * u, altarY - 50 * u, cx + i * 26 * u + 2 * u, altarY - 40 * u), paint);
            paint.setColor(theme.goldLight);
        }

        // altar table
        paint.setColor(theme.altarWood);
        c.drawRect(cx - 150 * u, altarY, cx + 150 * u, altarY + 14 * u, paint);
        paint.setColor(theme.goldDark);
        c.drawRect(cx - 150 * u, altarY, cx + 150 * u, altarY + 4 * u, paint);
        paint.setColor(shade(theme.altarWood, 0.8f));
        c.drawRect(cx - 140 * u, altarY + 14 * u, cx - 128 * u, altarY + 62 * u, paint);
        c.drawRect(cx + 128 * u, altarY + 14 * u, cx + 140 * u, altarY + 62 * u, paint);

        // flowers in vases (left + right)
        paint.setStrokeWidth(3 * u);
        paint.setColor(Color.parseColor("#3E7A3E"));
        c.drawLine(cx - 128 * u, altarY - 6 * u, cx - 118 * u, altarY - 52 * u, paint);
        c.drawLine(cx - 128 * u, altarY - 6 * u, cx - 138 * u, altarY - 50 * u, paint);
        c.drawLine(cx + 128 * u, altarY - 6 * u, cx + 118 * u, altarY - 52 * u, paint);
        c.drawLine(cx + 128 * u, altarY - 6 * u, cx + 138 * u, altarY - 50 * u, paint);
        flower(c, cx - 116 * u, altarY - 58 * u, 13 * u, theme.flowerA);
        flower(c, cx - 140 * u, altarY - 56 * u, 11 * u, theme.flowerB);
        flower(c, cx + 116 * u, altarY - 58 * u, 13 * u, Color.parseColor("#FFFFFF"));
        flower(c, cx + 140 * u, altarY - 56 * u, 11 * u, theme.flowerA);
        paint.setColor(theme.vase);
        path.reset();
        path.moveTo(cx - 140 * u, altarY - 4 * u);
        path.lineTo(cx - 116 * u, altarY - 4 * u);
        path.lineTo(cx - 121 * u, altarY + 26 * u);
        path.lineTo(cx - 135 * u, altarY + 26 * u);
        path.close();
        c.drawPath(path, paint);
        path.reset();
        path.moveTo(cx + 140 * u, altarY - 4 * u);
        path.lineTo(cx + 116 * u, altarY - 4 * u);
        path.lineTo(cx + 121 * u, altarY + 26 * u);
        path.lineTo(cx + 135 * u, altarY + 26 * u);
        path.close();
        c.drawPath(path, paint);

        // candles (tall, sides of altar) — cylinder gradient: bright
        // stripe on the left, shade on the right, wax top + a drip
        float candleY = altarY + 30 * u;
        paint.setShader(new LinearGradient(cx - 108 * u, 0, cx - 96 * u, 0,
                Color.parseColor("#FFFFFF"), shade(theme.candleLight, 0.85f), Shader.TileMode.CLAMP));
        c.drawRect(cx - 108 * u, candleY - 52 * u, cx - 96 * u, candleY, paint);
        paint.setShader(new LinearGradient(cx + 96 * u, 0, cx + 108 * u, 0,
                Color.parseColor("#FFFFFF"), shade(theme.candleLight, 0.85f), Shader.TileMode.CLAMP));
        c.drawRect(cx + 96 * u, candleY - 52 * u, cx + 108 * u, candleY, paint);
        paint.setShader(null);
        paint.setColor(Color.parseColor("#FFF8DC"));
        c.drawOval(new RectF(cx - 108 * u, candleY - 56 * u, cx - 96 * u, candleY - 48 * u), paint);
        c.drawOval(new RectF(cx + 96 * u, candleY - 56 * u, cx + 108 * u, candleY - 48 * u), paint);
        paint.setColor(Color.parseColor("#FFF3C4"));
        c.drawOval(new RectF(cx - 100 * u, candleY - 30 * u, cx - 96 * u, candleY - 12 * u), paint);
        c.drawOval(new RectF(cx + 104 * u, candleY - 38 * u, cx + 108 * u, candleY - 20 * u), paint);
        paint.setColor(Color.parseColor("#5A3A20"));
        c.drawRect(cx - 104 * u, candleY - 56 * u, cx - 100 * u, candleY - 50 * u, paint);
        c.drawRect(cx + 100 * u, candleY - 56 * u, cx + 104 * u, candleY - 50 * u, paint);
        // v2.77: candle flames fade + grow with litBlend, like the
        // photo path, instead of popping in at full size with lit.
        if (litBlend > 0.01f) {
            flame(c, cx - 102 * u, candleY - 54 * u, 22 * u * (0.35f + 0.65f * litBlend), 1.3f, litBlend);
            flame(c, cx + 102 * u, candleY - 54 * u, 22 * u * (0.35f + 0.65f * litBlend), 4.1f, litBlend);
        }

        // oil lamps (3 bowls on altar front)
        float lampY = altarY + 46 * u;
        float[] lampX = {cx - 62 * u, cx, cx + 62 * u};
        float[] lampPhase = {0.7f, 2.4f, 5.0f};
        for (int i = 0; i < 3; i++) {
            paint.setShader(new RadialGradient(lampX[i] - 5 * u, lampY, 20 * u,
                    theme.lampLight, shade(theme.lampLight, 0.45f), Shader.TileMode.CLAMP));
            rect.set(lampX[i] - 18 * u, lampY - 6 * u, lampX[i] + 18 * u, lampY + 12 * u);
            c.drawOval(rect, paint);
            paint.setShader(null);
            // dark oil surface on top, with a glint
            paint.setColor(Color.parseColor("#2B1A08"));
            c.drawOval(new RectF(lampX[i] - 14 * u, lampY - 8 * u, lampX[i] + 14 * u, lampY + 2 * u), paint);
            paint.setColor(Color.argb(150, 255, 220, 130));
            c.drawOval(new RectF(lampX[i] - 8 * u, lampY - 6 * u, lampX[i] - 2 * u, lampY - 2 * u), paint);
            paint.setColor(Color.parseColor("#3A2415"));
            c.drawLine(lampX[i], lampY - 8 * u, lampX[i], lampY - 2 * u, paint);
            if (litBlend > 0.01f) flame(c, lampX[i], lampY - 6 * u, 20 * u * (0.35f + 0.65f * litBlend), lampPhase[i], litBlend);
        }

        // incense holder (center front)
        float incY = altarY + 62 * u;
        paint.setColor(shade(theme.goldDark, 0.65f));
        rect.set(cx - 22 * u, incY, cx + 22 * u, incY + 14 * u);
        c.drawOval(rect, paint);
        paint.setStrokeWidth(2 * u);
        paint.setColor(Color.parseColor("#A0522D"));
        c.drawLine(cx - 6 * u, incY + 2 * u, cx - 10 * u, incY - 44 * u, paint);
        c.drawLine(cx, incY + 2 * u, cx, incY - 50 * u, paint);
        c.drawLine(cx + 6 * u, incY + 2 * u, cx + 10 * u, incY - 44 * u, paint);
        // v2.77: incense embers fade + shrink with litBlend
        // instead of popping between orange and nothing.
        if (litBlend > 0.01f) {
            paint.setColor(Color.argb((int) (255 * litBlend), 255, 122, 0));
            float er = 3 * u * (0.4f + 0.6f * litBlend);
            c.drawCircle(cx - 10 * u, incY - 44 * u, er, paint);
            c.drawCircle(cx, incY - 50 * u, er, paint);
            c.drawCircle(cx + 10 * u, incY - 44 * u, er, paint);
        }

        // water offerings (ရေချမ်း): three glass cups on a tray, in
        // front of the altar — glass with water, surface glint, shadow
        float trayY = altarY + 92 * u;
        paint.setColor(Color.argb(60, 0, 0, 0));
        rect.set(cx - 78 * u, trayY + 16 * u, cx + 78 * u, trayY + 26 * u);
        c.drawOval(rect, paint);
        paint.setColor(shade(theme.goldDark, 0.65f));
        rect.set(cx - 72 * u, trayY + 10 * u, cx + 72 * u, trayY + 18 * u);
        c.drawRoundRect(rect, 4 * u, 4 * u, paint);
        paint.setColor(theme.goldDark);
        c.drawRect(cx - 72 * u, trayY + 10 * u, cx + 72 * u, trayY + 13 * u, paint);
        float[] cupX = {cx - 46 * u, cx, cx + 46 * u};
        for (int i = 0; i < 3; i++) {
            float cupTop = trayY - 26 * u;
            float cupBot = trayY + 10 * u;
            // glass body: pale vertical gradient, darker rim at the base
            paint.setShader(new LinearGradient(cupX[i] - 13 * u, 0, cupX[i] + 13 * u, 0,
                    Color.argb(170, 220, 235, 245), Color.argb(170, 160, 195, 215), Shader.TileMode.CLAMP));
            path.reset();
            path.moveTo(cupX[i] - 13 * u, cupTop);
            path.lineTo(cupX[i] + 13 * u, cupTop);
            path.lineTo(cupX[i] + 10 * u, cupBot);
            path.lineTo(cupX[i] - 10 * u, cupBot);
            path.close();
            c.drawPath(path, paint);
            paint.setShader(null);
            // water inside: clear blue, filled to just below the rim
            paint.setShader(new LinearGradient(0, cupTop + 6 * u, 0, cupBot,
                    theme.waterLight, theme.waterDark, Shader.TileMode.CLAMP));
            path.reset();
            path.moveTo(cupX[i] - 11 * u, cupTop + 6 * u);
            path.lineTo(cupX[i] + 11 * u, cupTop + 6 * u);
            path.lineTo(cupX[i] + 9 * u, cupBot - 2 * u);
            path.lineTo(cupX[i] - 9 * u, cupBot - 2 * u);
            path.close();
            c.drawPath(path, paint);
            paint.setShader(null);
            // water surface + shimmer (moves gently while a talk plays)
            // v2.77: water shimmer eases with litBlend instead of
            // snapping to flat the frame play stops.
            float shimmer = litBlend * (float) Math.sin(t * 0.004 + i * 1.7f) * 1.2f * u;
            paint.setColor(Color.parseColor("#EAF7FC"));
            c.drawOval(new RectF(cupX[i] - 11 * u, cupTop + 4 * u, cupX[i] + 11 * u, cupTop + 9 * u), paint);
            paint.setColor(Color.argb(180, 255, 255, 255));
            c.drawLine(cupX[i] - 7 * u + shimmer, cupTop + 8 * u, cupX[i] - 7 * u + shimmer, cupBot - 5 * u, paint);
            // glass rim highlight
            paint.setColor(Color.argb(200, 255, 255, 255));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * u);
            c.drawOval(new RectF(cupX[i] - 13 * u, cupTop - 2 * u, cupX[i] + 13 * u, cupTop + 3 * u), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        // v9.15: warm lamp-light wash rising from the offerings —
        // the photo scene had one and the drawn scene went dark
        // above the altar. Low alpha: it warms the gold without
        // washing it out, and breathes with the halo.
        if (litBlend > 0.01f) {
            float glowPulse = 0.78f + 0.22f * (float) Math.sin(t * 0.0016);
            paint.setShader(new RadialGradient(cx, altarY + 80 * u, 260 * u,
                    Color.argb((int) (42 * glowPulse * litBlend), 255, 170, 60),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawRect(0, altarY - 40 * u, w, h, paint);
            paint.setShader(null);
        }

        // smoke (only rises while lit; fades out after stop)
        if (lit && frame % 4 == 0 && smokes.size() < 26) {
            smokes.add(new Smoke(cx + (random.nextFloat() - 0.5f) * 16 * u, incY - 50 * u, random.nextFloat() * 6f));
        }
        Iterator<Smoke> it = smokes.iterator();
        while (it.hasNext()) {
            Smoke s = it.next();
            s.y -= 3.5f;
            // v11.79: absolute form, like the AI/theme scenes — the old
            // incremental += let the puff wander sideways off the incense
            // column over its life instead of swaying around its base.
            s.x = s.bx + (float) Math.sin((s.startY - s.y) * 0.05 + s.phase) * 3.0f;
            s.size += 0.25f;
            if (lit) s.alpha = Math.max(0, 0.38f - (s.startY - s.y) / (h * 0.30f));
            else s.alpha -= 0.08f;
            if (s.alpha <= 0 || s.y < h * 0.05f) { it.remove(); continue; }
            drawSmokePuff(c, s);
        }

        // listener mat + cushion (foreground)
        paint.setColor(theme.mat);
        rect.set(cx - 110 * u, h - 52 * u, cx + 110 * u, h - 18 * u);
        c.drawRoundRect(rect, 12 * u, 12 * u, paint);
        paint.setColor(theme.goldDark);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * u);
        c.drawRoundRect(rect, 12 * u, 12 * u, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(theme.cushion);
        rect.set(cx - 52 * u, h - 66 * u, cx + 52 * u, h - 40 * u);
        c.drawOval(rect, paint);
    }
}
