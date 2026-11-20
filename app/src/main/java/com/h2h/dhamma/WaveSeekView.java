package com.h2h.dhamma;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * v2.48: stylized waveform seek bar — replaces the plain yellow SeekBar
 * line in the Room. 56 bars with a fixed, deterministic wave pattern
 * (NOT a decoded audio waveform — decoding each talk's mp3 on-device
 * just to draw true waves is out of scope); bars up to the play
 * position glow gold, the rest sit dim. Dragging scrubs like the old
 * bar, and bars swell slightly under the finger (the v2.29 drag
 * feedback, now inside the view). Same int progress/max contract as
 * the SeekBar it replaces, so the Room's poll glide keeps working.
 */
public class WaveSeekView extends View {
    public interface OnWaveSeekListener {
        void onProgressChanged(WaveSeekView v, int p, boolean fromUser);
        void onStartTrackingTouch(WaveSeekView v);
        void onStopTrackingTouch(WaveSeekView v);
        // v7.99: a cancelled drag is NOT a release — the old code
        // merged ACTION_CANCEL into onStopTrackingTouch, so the
        // Room committed the finger's preview position as a seek
        // even when the system aborted the gesture (interruption,
        // parent interception) and the user never lifted to
        // confirm it. Cancel now has its own callback; the Room
        // discards the preview and restores the live position,
        // never seeking. The only implementer is RoomActivity.
        void onCancelTrackingTouch(WaveSeekView v);
    }

    private static final int BARS = 56;
    private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint restPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // v4.06: buffered-ahead tint — bars already loaded but not yet
    // played sit between gold played and dim rest: a muted olive-
    // gold, clearly dimmer than played, clearly brighter than rest,
    // so a slow stream reads at a glance (how far ahead is loaded)
    // without any new view. Downloaded playback never sets a value
    // (service getter returns 0), so its wave is pixel-identical.
    private final Paint bufferedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // v4.13: drag readout paint, configured in init().
    private final Paint dragTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int bufferedPercent = 0;
    // v15.28 (slice 1805): the buffered-ahead tint edge glides —
    // the shown value below is what onDraw paints, eased toward
    // the target over 400ms, so the tint creeps between the 500ms
    // polls instead of stepping. At rest shown == target, so a
    // settled wave is pixel-identical.
    private float bufferedShown = 0f;
    private android.animation.ValueAnimator bufferedAnim;
    // v12.64: bookmark pins — premium backlog #1. Gold pins on the
    // wave at each saved minute of the sounding talk (the Room feeds
    // them via setBookmarks on every track change / add / delete).
    // A tap landing within 14dp of a pin glides the scrub to that
    // exact minute instead of the finger's x (v15.36, slice
    // 1852 — the landing used to snap there in one frame), so a
    // bookmark is a one-tap jump; the Room reads the snapped
    // value after the release commit and toasts the time.
    // Dragging away from the pin scrubs normally — the glide
    // only owns the landing.
    private java.util.List<Integer> bookmarks;
    private int snappedBookmarkMs = -1;
    // v15.36 (slice 1852): bookmark pin-tap glide state — the
    // pin landing used to jump the bar to the pin in one frame
    // (v12.64 scrubTo on DOWN); the glide animates it there
    // instead. pinGlideAnim is the running glide (null when
    // still); pinGlideTargetMs is the pin it is gliding to,
    // so finishPinGlide can land exactly on it even after the
    // animator is cancelled.
    private android.animation.ValueAnimator pinGlideAnim = null;
    private int pinGlideTargetMs = -1;
    // v15.32 (slice 1827): the pin arrival fade — see setBookmarks.
    // v15.32 (slice 1832): the pin departure fade — a clear glides
    // the set out over the same 250ms instead of snapping it off
    // (the 1827 fade-in's missing half); fadingBookmarks holds the
    // leaving set while it fades, so a new arrival can still start
    // from 0 and a tap never snaps to a leaving pin (touch reads
    // bookmarks only). pinAlpha is what onDraw paints: 0 = pins
    // hidden, 1 = fully in. No animator alive always means
    // pinAlpha == 1 while pins exist (0 while none) — the v15.28
    // buffered rule restated; a fade-out in flight is the only
    // mid-alpha state, and it always owns the animator.
    private android.animation.ValueAnimator pinFadeAnim;
    private float pinAlpha = 0f;
    private java.util.List<Integer> fadingBookmarks;
    private final Paint bookmarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path bookmarkPath = new android.graphics.Path();
    private int max = 0;
    private int progress = 0;
    private boolean dragging = false;
    // v11.80: the drag belongs to ONE finger. The old code read
    // e.getX() (pointer index 0) on every MOVE, so when a second
    // finger landed and the first lifted, index 0 silently became
    // the second finger and the scrub jumped to its x mid-drag.
    // The DOWN pointer's id is tracked now; other fingers are
    // ignored, and lifting the tracked finger ends the drag.
    private int activePointerId = -1;
    private OnWaveSeekListener listener;
    // v2.52: living wave — the v2.50 wave seek used to sit frozen
    // while a talk played (only its gold progress crept forward),
    // while the glow, flames and smoke around it all breathe. While
    // playing, the bars now ripple gently (a travelling sine, ±10%
    // of height, ~2.8s period) so the seek bar reads alive; paused,
    // it freezes back to its base shape, like the altar going
    // still. The shape stays stylised, never decoded audio, and
    // progress colours/positions are untouched. A 150ms tick
    // redraws only this 32dp view while playing; it stops on
    // pause, detach and onPause (the Room stops it there like the
    // glow pulse), so nothing animates off-screen. Idempotent:
    // the Room's 500ms poll calls setPlaying every tick.
    // v2.53: drag-swell ease — the v2.48/v2.50 wave bars used to
    // jump to 1.25x the instant a finger landed and snap back to
    // 1.0x the instant it lifted, so every scrub started and ended
    // with a visible pop while the ripple (v2.52) and the poll
    // glide (v2.20) around it all move smoothly. The swell is now
    // a real animated value: 120ms up on touch-down, 200ms down
    // on lift (both decelerate), ending at exactly 1.0 so a later
    // drag never starts from a half-swollen state. A new touch
    // cancels a running ease-out and swells from wherever it is,
    // and detach cancels the animator so nothing ticks off-screen.
    // The ripple, progress colours/positions and drag scrubbing
    // itself are untouched; only the swell envelope moves.
    // v2.76: ripple phase continuity — the v2.52 ripple read its
    // phase straight from the wall clock while playing and from 0
    // while paused, so every pause snapped every bar to a different
    // height in one frame (and every resume snapped them again to
    // wherever the clock had reached). The phase is now accumulated
    // play-time: pausing freezes it exactly where it was, resuming
    // continues from that frozen shape, and the value is wrapped
    // modulo 2*PI on every freeze so it never grows unbounded over
    // a long talk. A bar that never played still draws flat (the
    // pre-v2.76 paused look); only a played-then-paused bar keeps
    // its frozen ripple shape. Progress colours/positions, the
    // swell envelope and drag scrubbing are untouched.
    private double rippleBase = 0.0;
    private long rippleStartMs = 0;
    private boolean rippleStarted = false;
    private double ripplePhase() {
        if (!playing) return rippleBase;
        return rippleBase + (android.os.SystemClock.uptimeMillis() - rippleStartMs) / 450.0;
    }
    private float swellScale = 1f;
    private android.animation.ValueAnimator swellAnim;
    private void animateSwell(float target, long durationMs) {
        if (swellAnim != null) swellAnim.cancel();
        swellAnim = android.animation.ValueAnimator.ofFloat(swellScale, target);
        swellAnim.setDuration(durationMs);
        swellAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        swellAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                swellScale = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        swellAnim.start();
    }
    // v14.18: drag-readout fade — the v4.13 scrub-time readout used
    // to pop in at full alpha the instant a finger landed and vanish
    // in one frame the instant it lifted, while the swell around it
    // eases (v2.53: 120ms in, 200ms out) — the one instant change
    // left in the scrub itself. The readout now rides the swell's
    // own rhythm: it fades 0->1 over the same 120ms on touch-down
    // and melts 1->0 over the same 200ms on lift, so the time and
    // the bars swell and settle together. The fade-out runs after
    // dragging clears (onDraw keys on readoutAlpha, not dragging),
    // so the time lingers briefly at the scrub target instead of
    // blinking out; a re-touch mid-fade-out cancels and fades back
    // in from the current alpha, exactly like the swell. The
    // TalkBack path never shows the readout (no dragging), so it
    // never calls this; detach cancels and resets, like the swell.
    private float readoutAlpha = 0f;
    private android.animation.ValueAnimator readoutAnim;
    private void animateReadout(float target, long durationMs) {
        if (readoutAnim != null) readoutAnim.cancel();
        readoutAnim = null;
        float from = readoutAlpha;
        dragTextPaint.setAlpha((int) (255f * readoutAlpha));
        if (from == target) return;
        readoutAnim = android.animation.ValueAnimator.ofFloat(from, target);
        readoutAnim.setDuration(durationMs);
        readoutAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        readoutAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                readoutAlpha = (Float) a.getAnimatedValue();
                dragTextPaint.setAlpha((int) (255f * readoutAlpha));
                invalidate();
            }
        });
        readoutAnim.start();
    }
    // v3.15: premium ripple ramp-in — the v2.52 wave bars used to jump
    // from flat (never played) to the full +/-10% ripple in a single
    // tick on the first play, the one play-start pop left after the
    // v2.53 drag-swell ease and the v2.76 phase freeze (pause/resume
    // never snapped, but first play did). The ripple amplitude now
    // eases 0->1 over 400ms (decelerate) on play start, mirroring
    // the swell envelope's own pattern: a fresh play ramps from 0,
    // a pause mid-ramp freezes the amplitude where it is (the phase
    // freeze above keeps the shape too), and a resume continues the
    // ramp from that amplitude at the same rate, never restarting
    // from 0. The paused steady state is untouched — a played bar
    // keeps its frozen ripple shape at whatever amplitude it reached.
    private float rippleAmp = 0f;
    private android.animation.ValueAnimator rippleAmpAnim;
    private void animateRippleAmp(float target) {
        if (rippleAmpAnim != null) rippleAmpAnim.cancel();
        // v11.80: a cancelled animator is dead — null the field
        // here too, not only in the callers that cancel it. The
        // old code left a non-null reference to the dead animator
        // on the from == target early return below, so later
        // readers (setPlaying's else-if, resetRipple, detach)
        // cancelled an animator that had already ended.
        rippleAmpAnim = null;
        float from = rippleAmp;
        if (from == target) return;
        rippleAmpAnim = android.animation.ValueAnimator.ofFloat(from, target);
        rippleAmpAnim.setDuration((long) (400 * Math.abs(target - from)));
        rippleAmpAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        rippleAmpAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                rippleAmp = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        rippleAmpAnim.start();
    }
    // v3.27: premium stop-reset ripple — a stopped talk used to
    // keep the frozen ripple shape of the talk that just ended
    // (v2.76 freeze + v3.15 ramp state survived setPlaying(false)),
    // so after a notification stop the bar showed wavy bars at
    // 0:00 while a never-played bar draws flat — the stopped bar
    // looked half-played beside the reset clock. Only the Room's
    // once-only stop branch calls this (inside its !seeking bar
    // reset, next to setMax(0)/setProgress(0)); pause never calls
    // it, so a paused bar keeps its designed frozen shape, and a
    // later play ramps back in from 0 via the v3.15 ramp instead
    // of popping to full ripple. Progress, colours, swell and
    // drag scrubbing are untouched — only the shape values reset.
    // v3.42: premium stop-ripple ease-out — the v3.27 reset used to
    // snap rippleAmp straight to 0, so on a notification stop the
    // bars jumped from wavy to flat in one frame while the seek
    // row around them poured (v2.82) and the button morphed
    // (v2.07) — the one instant change in an otherwise moving
    // stop. The shape now eases to flat over 250ms (decelerate,
    // inside the row pour's 320ms window), and the phase clears
    // only when the ease actually ends, so the bars settle instead
    // of popping. Guards: an already-flat bar still resets instantly
    // (no animator for nothing); a play mid-ease cancels it through
    // the existing animateRippleAmp path and ramps back up from
    // the current amplitude (cancel never fires the end action, so
    // the phase is never cleared under a playing bar); pause never
    // calls this, so its frozen shape is untouched; a detach
    // mid-ease completes the reset instead of stranding a
    // half-flattened stopped bar. Progress, colours, swell and
    // drag scrubbing are untouched — only the shape's exit moves.
    private boolean rippleResetEasing = false;
    public void resetRipple() {
        if (rippleAmpAnim != null) { rippleAmpAnim.cancel(); rippleAmpAnim = null; }
        if (rippleAmp <= 0f) {
            rippleResetEasing = false;
            rippleStarted = false;
            rippleBase = 0.0;
            invalidate();
            return;
        }
        rippleResetEasing = true;
        rippleAmpAnim = android.animation.ValueAnimator.ofFloat(rippleAmp, 0f);
        rippleAmpAnim.setDuration(250);
        rippleAmpAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        rippleAmpAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                rippleAmp = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        // v3.65: premium stop-ease restart guard — the v3.42 note
        // above claimed "cancel never fires the end action", but
        // the v3.19 lesson (which v3.62/v3.63/v3.64 all rely on)
        // says a cancelled animator can still fire it. A play
        // mid-ease cancels this ease through animateRippleAmp,
        // and a late end landing after the new ramp was assigned
        // used to null rippleAmpAnim out from under that ramp:
        // a later pause then could not cancel the ramp, so the
        // amplitude kept easing to full under a paused bar
        // instead of freezing (v3.15). Capturing the ease and
        // returning unless the field still holds it closes it,
        // the same identity pattern as the v3.64 retry-hide
        // guard: a synchronous cancel fires while the field
        // still matches (playing is already true by then, so
        // the clear below is skipped, as before), while a late
        // end after a new ramp was assigned is dropped.
        // Natural completion is unchanged (field still matches,
        // the bar still lands flat and the phase still clears).
        final android.animation.ValueAnimator easeAnim = rippleAmpAnim;
        rippleAmpAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (rippleAmpAnim != easeAnim) return;
                rippleResetEasing = false;
                rippleAmpAnim = null;
                if (!playing) {
                    rippleStarted = false;
                    rippleBase = 0.0;
                }
                invalidate();
            }
        });
        rippleAmpAnim.start();
    }
    // v4.07: buffering stall calm — the v2.52 rule counts a buffering
    // talk as playing for the wave, so a stream stalled mid-talk
    // danced at the full +/-10% ripple exactly like healthy
    // playback: only the status-line text ("ဆွဲထုတ်နေသည်…") and
    // the v4.06 buffered tint said it was waiting, and the tint
    // only shows how much is loaded, not that progress is stuck.
    // While the Room's poll reports buffering (towards play, never
    // a held pause), the ripple amplitude now eases to 0.25 — a
    // gentle idle sway, phase still travelling, so it never reads
    // as a frozen pause — and ramps back to full the tick
    // buffering clears. Change-only, so the 500ms poll never
    // re-animates a steady state; pause keeps its frozen shape
    // (the flag is only recorded while not playing, and play
    // ramps to the flag's target); a stop-ease owns the animator
    // while it runs, so this never fights resetRipple; drag
    // swell, progress colours/positions and scrubbing untouched.
    private static final float BUFFER_RIPPLE_AMP = 0.25f;
    private boolean buffering = false;
    // v4.08: audio-focus duck calm — a notification dipping the
    // volume to 20% (AudioService ducked) used to leave the wave
    // dancing at full ripple exactly like normal playback, the
    // same read-as-healthy class v4.07 fixed for a stall: the only
    // sign of the dip was the quieter sound itself. While ducked
    // the ripple now eases to 0.5 — clearly softer than full
    // playback, clearly livelier than the 0.25 buffering sway, so
    // the two quiet states never read as each other — and ramps
    // back to full the tick focus returns. Both flags feed one
    // combined target (stall wins when both hold), so the two
    // setters never fight over the animator on the same poll
    // tick; change-only, pause records the flag and keeps its
    // frozen shape, and a stop-ease owns the animator while it
    // runs, exactly like the v4.07 buffering rule. Drag swell,
    // progress colours/positions, scrubbing, flames, halo, smoke
    // and the buffered tint are untouched; the downloaded library
    // never calls setDucked, so its behaviour is unchanged.
    private static final float DUCK_RIPPLE_AMP = 0.5f;
    private boolean ducked = false;
    // v4.09: sleep-fade dying calm — the v4.04 bedtime fade lowers
    // the volume to silence over ~3s, but the wave used to dance
    // at full ripple right up to the fade-end pause, the same
    // read-as-healthy class v4.07 fixed for a stall and v4.08 for
    // a duck: the only sign of the fade was the quieter sound
    // itself. While fading the ripple now eases to 0.12 — nearly
    // flat, clearly below the 0.25 stall sway and the 0.5 duck
    // calm, so the three quiet states never read as each other —
    // and the fade-end pause freezes it there, exactly where the
    // sound died, until resume ramps back up. The fade is
    // terminal, so it wins the combined target over stall and
    // duck; change-only, pause records the flag and keeps its
    // frozen shape, and a stop-ease owns the animator while it
    // runs, exactly like the v4.07/v4.08 rules. Drag swell,
    // progress colours/positions, scrubbing, flames, halo, smoke
    // and the buffered tint are untouched; the downloaded library
    // never calls setSleepFading, so its behaviour is unchanged.
    private static final float FADE_RIPPLE_AMP = 0.12f;
    private boolean sleepFading = false;
    // v4.10: failed-stream flat — a stream that fails sets
    // trackError in the service, and the Room poll then reads
    // active=false, so setPlaying(false) froze the wave
    // mid-ripple exactly like a pause: only the toast/status
    // text said it had failed, and the bar itself read as a
    // paused talk. While the error flag holds, the ripple now
    // eases to 0 (flat bars, like a never-played bar) instead
    // of freezing, so a dead stream reads as dead, never as
    // paused; retry ramps back up from 0 via the v3.15 ramp
    // (play clears trackError), and an explicit stop clears it
    // too (v1.67), so a stale error can never strand the flat.
    // Change-only like setBuffering/setDucked/setSleepFading;
    // the flag itself never animates — the ease runs once, in
    // the setPlaying(false) transition (poll order puts this
    // setter before setPlaying, so the flag is already set
    // when the transition runs) or directly here when the
    // flag arrives while already not playing (error while the
    // Room was backgrounded, poll stopped). A stop-ease owns
    // the animator while it runs, so this never fights
    // resetRipple; pause never sets the flag, so its frozen
    // shape is untouched; drag swell, progress colours/
    // positions, scrubbing, flames, halo, smoke and the
    // buffered tint are untouched; the downloaded library
    // never calls setStreamError, so its behaviour is
    // unchanged.
    private boolean streamError = false;
    public void setStreamError(boolean e) {
        if (e == streamError) return;
        streamError = e;
        if (e && !playing && !rippleResetEasing && rippleAmp > 0f) animateRippleAmp(0f);
    }
    private float rippleTarget() {
        if (sleepFading) return FADE_RIPPLE_AMP;
        if (buffering) return BUFFER_RIPPLE_AMP;
        if (ducked) return DUCK_RIPPLE_AMP;
        return 1f;
    }
    private void applyRippleTarget() {
        if (!playing || rippleResetEasing) return;
        animateRippleAmp(rippleTarget());
    }
    public void setBuffering(boolean b) {
        if (b == buffering) return;
        buffering = b;
        applyRippleTarget();
    }
    public void setDucked(boolean d) {
        if (d == ducked) return;
        ducked = d;
        applyRippleTarget();
    }
    public void setSleepFading(boolean f) {
        if (f == sleepFading) return;
        sleepFading = f;
        applyRippleTarget();
    }
    // v14.18: pause/play dim ease — the v4.13 dim used to snap the
    // three bar paints (and the playhead dot, which shares
    // playedPaint) straight between full and ~0.55 alpha the instant
    // setPlaying flipped, while the glow around it fades (v2.33) and
    // the ripple freezes or ramps — the one instant change left in
    // the play/pause transition. The dim now eases over 200ms
    // (decelerate), the same window as the swell-out, so a pause
    // settles instead of blinking dim and a play brightens while
    // the ripple ramps back in (v3.15). Change-only like the
    // buffering/duck/fade targets: a poll tick re-entering a settled
    // dim or an already-running ease toward the same target is a
    // no-op, so the 500ms poll can never restart the fade; a
    // mid-fade flip eases on from the current alpha. Detach cancels
    // the animator and keeps the value, like the ripple amplitude.
    private float dimAlphaF = 255f;
    private android.animation.ValueAnimator dimAnim;
    private float dimAnimTarget = 255f;
    private void applyDim(float a) {
        dimAlphaF = a;
        int ia = Math.round(a);
        playedPaint.setAlpha(ia);
        bufferedPaint.setAlpha(ia);
        restPaint.setAlpha(ia);
    }
    private void animateDim(float target) {
        if (dimAnim != null && dimAnimTarget == target) return;
        if (Math.abs(dimAlphaF - target) < 0.5f) { applyDim(target); return; }
        if (dimAnim != null) dimAnim.cancel();
        dimAnim = null;
        dimAnimTarget = target;
        dimAnim = android.animation.ValueAnimator.ofFloat(dimAlphaF, target);
        dimAnim.setDuration(200);
        dimAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        dimAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                applyDim((Float) a.getAnimatedValue());
                invalidate();
            }
        });
        dimAnim.start();
    }
    private boolean playing = false;
    private final Runnable waveTick = new Runnable() {
        @Override public void run() {
            if (!playing) return;
            invalidate();
            postDelayed(this, 150);
        }
    };
    public void setPlaying(boolean p) {
        // v4.13: paused dim — a silent wave sits at ~0.55 paint alpha
        // (bars and playhead dot alike), a sounding one at full, so
        // pause reads at a glance instead of only freezing the
        // ripple. Paint alpha, never view alpha, so the entrance
        // pours (which own the view's alpha channel) never fight it;
        // applied on every call, not only on change, so a recreated
        // view lands dim even when the poll's first call matches
        // the default flag. The error-flat (v4.10) and the frozen
        // pause shape are untouched — only their brightness moves.
        // v14.18: the dim eases (animateDim, 200ms) instead of
        // snapping — see the field note above; the every-call
        // property is kept (animateDim no-ops on a settled dim),
        // and a recreated view eases to dim on its first poll
        // call, masked by the Room's own entrance pour.
        animateDim(p ? 255f : 140f);
        if (p == playing) return;
        if (!p) {
            // v2.76: freeze the phase before flipping, so the next
            // draw keeps the bars exactly where they were.
            rippleBase = ripplePhase() % (Math.PI * 2.0);
            if (rippleBase < 0) rippleBase += Math.PI * 2.0;
        }
        playing = p;
        if (playing) {
            rippleStarted = true;
            rippleStartMs = android.os.SystemClock.uptimeMillis();
            // v3.15: ease the ripple amplitude in (see field note);
            // a mid-ramp pause left rippleAmp where it was, so a
            // resume continues from there instead of popping to full.
            // v3.42: a play mid stop-ease cancels that ease here
            // (animateRippleAmp cancels rippleAmpAnim) and ramps back
            // up from the current amplitude — clear the easing flag
            // so a later detach never completes a dead reset.
            rippleResetEasing = false;
            // v4.07: ramp to the buffering target when stalled, so a
            // play that starts into a buffer never flashes full
            // ripple first; a resume from a frozen dim amplitude
            // stays put (no animator for an equal target).
            // v4.08: the combined target — a play starting ducked
            // ramps straight to the duck calm, never via full.
            float playTarget = rippleTarget();
            if (Math.abs(rippleAmp - playTarget) > 0.001f) animateRippleAmp(playTarget);
            removeCallbacks(waveTick);
            post(waveTick);
        } else {
            // v3.15: cancel a running ramp but keep rippleAmp — the
            // paused bars keep their frozen shape at this amplitude.
            // v4.10: except on a failed stream — the error flag
            // (set by the poll before this call) eases the ripple
            // to flat instead of freezing it, so a dead stream
            // never reads as a pause; a stop-ease still owns the
            // animator while it runs, so it is never fought here.
            if (streamError && !rippleResetEasing && rippleAmp > 0f) {
                animateRippleAmp(0f);
            } else if (rippleAmpAnim != null) { rippleAmpAnim.cancel(); rippleAmpAnim = null; }
            removeCallbacks(waveTick);
            invalidate();
        }
    }
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(waveTick);
        // v11.80: a detach mid-drag must not strand the drag state
        // — the old code left dragging true, so a re-attached
        // instance (same view, new window) kept drawing the stale
        // scrub readout and a MOVE without any DOWN would scrub.
        // The finger is gone with the window; the next drag starts
        // from a fresh DOWN.
        dragging = false;
        activePointerId = -1;
        // v2.76: a detach mid-play must not strand a running phase —
        // freeze it like a pause, so a later setPlaying(true) restarts
        // the tick from the frozen shape instead of no-opping.
        if (playing) {
            rippleBase = ripplePhase() % (Math.PI * 2.0);
            if (rippleBase < 0) rippleBase += Math.PI * 2.0;
            playing = false;
        }
        if (swellAnim != null) swellAnim.cancel(); // v2.53: no off-screen swell tick
        swellAnim = null;
        // v14.18: no off-screen readout fade or dim tick — the
        // readout resets (a re-attached view starts with no
        // readout, like a fresh drag), the dim value is kept like
        // the ripple amplitude, so the next setPlaying re-targets
        // from wherever it froze.
        if (readoutAnim != null) readoutAnim.cancel();
        readoutAnim = null;
        readoutAlpha = 0f;
        dragTextPaint.setAlpha(255);
        if (dimAnim != null) dimAnim.cancel();
        dimAnim = null;
        // v15.28 (slice 1805): no off-screen buffered glide — the
        // animator is cancelled and the shown value snaps to the
        // target, so a re-attached view never sits frozen at a
        // stale tint: no animator alive always means shown ==
        // target, and the next poll re-targets from the true value.
        if (bufferedAnim != null) bufferedAnim.cancel();
        bufferedAnim = null;
        bufferedShown = bufferedPercent;
        // v15.32 (slice 1827): no off-screen pin fade — the animator
        // is cancelled and the alpha snaps to its target, so a
        // re-attached view never sits frozen mid-fade: no animator
        // alive always means pinAlpha == 1 while pins exist (0
        // while none), the v15.28 buffered rule restated.
        // v15.32 (slice 1832): the leaving set dies with the view
        // too — a fade-out in flight never outlives a detach.
        if (pinFadeAnim != null) { pinFadeAnim.cancel(); pinFadeAnim = null; }
        cancelPinGlide(); // v15.36 (slice 1852): no off-screen pin glide tick
        fadingBookmarks = null;
        pinAlpha = (bookmarks == null ? 0f : 1f);
        // v3.65: same stop-ease guard, detach side — the flag used
        // to be read AFTER the cancel, so a synchronous end action
        // (v3.19 behaviour) cleared the flag first and the detach
        // completion below never ran, stranding a half-flattened
        // bar (the ease's own end never sets rippleAmp to 0).
        // The flag is now captured and cleared BEFORE the cancel
        // (the v3.19 ordering lesson), and completion runs off the
        // saved value; a late end is dropped by the v3.65 identity
        // guard above. The pause case never sets the flag, so its
        // kept amplitude above is untouched.
        boolean wasStopEasing = rippleResetEasing;
        rippleResetEasing = false;
        if (rippleAmpAnim != null) rippleAmpAnim.cancel(); // v3.15: no off-screen ramp tick; rippleAmp itself is kept, like the frozen phase
        rippleAmpAnim = null;
        if (wasStopEasing) {
            rippleAmp = 0f;
            rippleStarted = false;
            rippleBase = 0.0;
        }
        swellScale = 1f;
        super.onDetachedFromWindow();
    }

    public WaveSeekView(Context c) { super(c); init(); }
    public WaveSeekView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        playedPaint.setColor(0xFFC9A227);
        bufferedPaint.setColor(0xFF8A7A3D); // v4.06: buffered-ahead tint
        restPaint.setColor(0x738A7A68);
        // v12.64: bookmark pins — gold, same gold as played bars.
        bookmarkPaint.setColor(0xFFC9A227);
        bookmarkPaint.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);
        // v4.13: drag readout paint — gold on a soft shadow so the
        // scrub time reads over the bars while dragging.
        dragTextPaint.setColor(0xFFC9A227);
        dragTextPaint.setTextSize(13f * getResources().getDisplayMetrics().density);
        dragTextPaint.setTextAlign(Paint.Align.CENTER);
        dragTextPaint.setShadowLayer(4f, 0f, 2f, 0xFF000000);
        setClickable(true);
    }

    // v4.13: scrub-time formatter for the drag readout (same clock
    // shape as the Room's formatTime: m:ss, h:mm:ss past an hour).
    // v13.51: Locale.US on the %02d calls — same Burmese-phone
    // mixed-digit defect as Fx.fmtTime.
    private static String fmtDragTime(int ms) {
        int total = Math.max(0, ms / 1000);
        if (total >= 3600)
            return (total / 3600) + ":" + String.format(java.util.Locale.US, "%02d", (total % 3600) / 60)
                    + ":" + String.format(java.util.Locale.US, "%02d", total % 60);
        return (total / 60) + ":" + String.format(java.util.Locale.US, "%02d", total % 60);
    }

    // v4.06: percent of duration buffered (0-100, streams only).
    // Invalidates only on change, so the 500ms Room poll costs
    // nothing once buffering settles at 100.
    // v15.28 (slice 1805): the tint edge glides instead of stepping
    // — the poll's setBufferedPercent used to land the new value in
    // one frame, so on a slow stream the buffered tint jumped in
    // big steps (12 -> 31 -> 58) every 500ms while the gold played
    // edge beside it glides smoothly (v2.20 poll glide): the one
    // still element left in the wave. The shown value now eases
    // toward each new target over 400ms (DecelerateInterpolator,
    // the rippleAmp ramp's own rhythm), re-targeting mid-glide
    // from wherever the edge has reached, exactly like the swell
    // (v2.53); a repeated poll with the same target is still a
    // no-op, so a settled stream costs nothing. Resets (stop /
    // new talk / discard) snap at once via clearBufferedPercent
    // below — a stale tint must never melt across a fresh bar.
    public void setBufferedPercent(int p) {
        int np = Math.max(0, Math.min(100, p));
        if (np == bufferedPercent) return;
        bufferedPercent = np;
        animateBuffered(np);
    }

    // v15.28 (slice 1805): the buffered glide itself — the
    // swell/readout/rippleAmp pattern (cancel, null, re-animate
    // from the current value, DecelerateInterpolator). The end
    // listener nulls the field on natural completion (the v3.65
    // identity guard: a cancel fires the end synchronously while
    // the field still matches, so it nulls harmlessly there too,
    // and a late end after a new glide was assigned is dropped),
    // so a finished animator never lingers as a live one.
    private void animateBuffered(int target) {
        if (bufferedAnim != null) bufferedAnim.cancel();
        bufferedAnim = null;
        float from = bufferedShown;
        if (from == target) return;
        bufferedAnim = android.animation.ValueAnimator.ofFloat(from, target);
        bufferedAnim.setDuration(400);
        bufferedAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        bufferedAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                bufferedShown = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        final android.animation.ValueAnimator glideAnim = bufferedAnim;
        bufferedAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (bufferedAnim != glideAnim) return;
                bufferedAnim = null;
            }
        });
        bufferedAnim.start();
    }

    // v15.28 (slice 1805): instant buffered clear for the resets —
    // stop, new talk, discard and no-service all pair it with
    // setMax(0)/setProgress(0), so the tint must vanish with the
    // bar, never melt across a fresh one. Cancels any glide in
    // flight and snaps both values to 0; a no-op when already 0.
    public void clearBufferedPercent() {
        if (bufferedAnim != null) { bufferedAnim.cancel(); bufferedAnim = null; }
        if (bufferedPercent == 0 && bufferedShown == 0f) return;
        bufferedPercent = 0;
        bufferedShown = 0f;
        invalidate();
    }

    // v12.64: bookmark pins for the sounding talk, in ms. A null or
    // empty list clears them. Change-only, so the 500ms Room poll
    // costs nothing on a steady talk; a pending tap-snap is voided
    // with the list it belonged to.
    // v15.32 (slice 1827): the set fades in instead of popping — a
    // track change brings a new talk's pins while the bar glides
    // (v2.20), the total fades in (v3.33), the buffered tint glides
    // (v15.28) and the playhead swells (v15.27) around them, so the
    // pins were the one element still landing in a single frame.
    // The fade is set-level (250ms, DecelerateInterpolator, the
    // melt rhythm): every new list starts at alpha 0 and rises to
    // 1 — a track change, and an add/delete too, which re-reads as
    // the set refreshing, the queue-bar count dip's rhythm (v14.43).
    // v15.32 (slice 1832): the set fades OUT too — deleting the
    // last bookmark used to snap the pin off in one frame while
    // the list dialog exits and the wave sits fully drawn (max >
    // 0), the departure half the 1827 arrival fade never got.
    // The clear now glides pinAlpha to 0 over the same 250ms
    // Decelerate, painting the leaving set from fadingBookmarks
    // (bookmarks is already null, so a re-add of the same list
    // mid-fade can never no-op on the change-only guard, and a
    // tap never snaps to a leaving pin). The 1827 reason still
    // holds: a fresh arrival cancels the fade-out and restarts
    // from 0, so no stale pin ever melts across a fresh bar —
    // the clear only owns the leaving, never the arriving. The
    // track-change / stop clears still snap in EFFECT, not in
    // code: both reset max to 0 first (RoomActivity), so the
    // paint guard (max > 0) hides the pins before the fade
    // starts — the fade only ever shows where the wave is drawn.
    // A rapid second change mid-fade cancels and restarts from 0
    // (arrival) or from the current alpha (a second clear is a
    // no-op — the set is already leaving); the end action is
    // identity-guarded the v3.65 way.
    public void setBookmarks(java.util.List<Integer> marks) {
        java.util.List<Integer> next = (marks == null || marks.isEmpty()) ? null
                : new java.util.ArrayList<Integer>(marks);
        if (next == null && bookmarks == null && fadingBookmarks == null) return;
        if (next != null && next.equals(bookmarks)) return;
        if (next == null) {
            // v15.32 (slice 1832): clear — glide the leaving set
            // out instead of snapping pinAlpha to 0 (see the
            // comment above). bookmarks is already null here, so
            // the change-only guard above can never swallow a
            // re-add mid-fade, and the paint below reads the
            // leaving set from fadingBookmarks.
            if (bookmarks == null) return; // already leaving
            fadingBookmarks = bookmarks;
            bookmarks = null;
            snappedBookmarkMs = -1;
            cancelPinGlide(); // v15.36 (slice 1852): a new pin set voids the pending snap
            if (pinFadeAnim != null) { pinFadeAnim.cancel(); pinFadeAnim = null; }
            final float fromAlpha = pinAlpha;
            pinFadeAnim = android.animation.ValueAnimator.ofFloat(fromAlpha, 0f);
            pinFadeAnim.setDuration(250);
            pinFadeAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
            pinFadeAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    pinAlpha = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            final android.animation.ValueAnimator fadeOutAnim = pinFadeAnim;
            pinFadeAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    if (pinFadeAnim != fadeOutAnim) return;
                    pinFadeAnim = null;
                    fadingBookmarks = null;
                    pinAlpha = 0f;
                    invalidate();
                }
            });
            pinFadeAnim.start();
            invalidate();
            return;
        }
        // Arrival: a fresh set voids any fade-out in flight, then
        // fades in from 0 exactly as before (v15.32, slice 1827).
        fadingBookmarks = null;
        bookmarks = next;
        snappedBookmarkMs = -1;
        cancelPinGlide(); // v15.36 (slice 1852): a new pin set voids the pending snap
        if (pinFadeAnim != null) { pinFadeAnim.cancel(); pinFadeAnim = null; }
        pinAlpha = 0f;
        pinFadeAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        pinFadeAnim.setDuration(250);
        pinFadeAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        pinFadeAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                pinAlpha = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        final android.animation.ValueAnimator fadeAnim = pinFadeAnim;
        pinFadeAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (pinFadeAnim != fadeAnim) return;
                pinFadeAnim = null;
            }
        });
        pinFadeAnim.start();
        invalidate();
    }

    // The snapped minute of the last tap-landing, or -1; consumed
    // (cleared) on read, so a later release never re-toasts it.
    public int consumeSnappedBookmark() {
        int v = snappedBookmarkMs;
        snappedBookmarkMs = -1;
        return v;
    }

    public void setOnWaveSeekListener(OnWaveSeekListener l) { listener = l; }
    public int getMax() { return max; }
    public void setMax(int m) { max = Math.max(0, m); if (progress > max) progress = max; snappedBookmarkMs = -1; cancelPinGlide(); invalidate(); }
    public int getProgress() { return progress; }
    public void setProgress(int p) {
        int np = Math.max(0, Math.min(max, p));
        if (np != progress) { progress = np; invalidate(); }
    }

    private float barHeight(int i) {
        double f = Math.abs(Math.sin(i * 1.7) * 0.6 + Math.sin(i * 0.43) * 0.4);
        return (float) (0.18 + 0.82 * f);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float gap = 3f * getResources().getDisplayMetrics().density;
        // v11.91: on a narrow window (split-screen) the 55 gaps
        // alone could exceed the width, so the strip overflowed
        // the right edge and the playhead dot (placed from w)
        // no longer lined up with the played/rest bar boundary.
        // Shrink the gap first so the 56 bars always fit inside w.
        if (w < BARS + gap * (BARS - 1))
            gap = Math.max(0f, (w - BARS) / (float) (BARS - 1));
        float bw = (w - gap * (BARS - 1)) / BARS;
        if (bw <= 0) bw = 1;
        float playedFrac = max > 0 ? (float) progress / max : 0f;
        float bufferedFrac = bufferedShown / 100f; // v4.06 + v15.28: the shown (gliding) value, not the target — the tint edge creeps between polls instead of stepping; at rest shown == target, so a settled wave paints exactly as before
        float swell = swellScale; // v2.53: animated swell, not a dragging snap
        // v2.52: travelling ripple while playing, frozen when paused.
        // v2.76: the frozen phase is the accumulated one, not 0, so
        // pause/resume never snaps the bars; never-played bars stay
        // flat exactly as before.
        double phase = ripplePhase();
        for (int i = 0; i < BARS; i++) {
            float ripple = (playing || rippleStarted)
                    ? (float) (1.0 + 0.10 * rippleAmp * Math.sin(phase + i * 0.55)) : 1f; // v3.15: ramped amplitude, 0 before first play
            float bh = h * barHeight(i) * swell * ripple;
            if (bh > h) bh = h;
            float left = i * (bw + gap);
            float top = (h - bh) / 2f;
            float right = left + bw;
            float bottom = top + bh;
            float r = bw / 2f;
            // v4.06: played first (a seek past the buffered point
            // still reads gold), then buffered-ahead, then dim rest.
            canvas.drawRoundRect(left, top, right, bottom, r, r,
                    i < playedFrac * BARS ? playedPaint
                    : i < bufferedFrac * BARS ? bufferedPaint : restPaint);
        }
        // v4.12: playhead dot — the gold/dim boundary used to be the
        // only position cue, and on a 56-bar strip it reads a bar
        // late; a small gold dot now rides the exact progress point
        // (Spotify-style scrubber head, drawn, not a new view). It
        // sits at the centre height so the ripple/swell pass under
        // it untouched, and it draws only once a talk has actually
        // started (progress > 0 with a known range); dragging keeps
        // working because this is paint only, never touch handling.
        if (max > 0 && progress > 0) {
            // v15.27 (slice 1798): the playhead dot swells under the finger — the bars swell (v2.53) and the readout fades in (v14.18) on a drag, but the scrubber head itself sat at a constant 3.5dp, the one still element left in the scrub (Spotify grows its head on drag). The radius now rides the bars' own animated swellScale: 3.5dp at rest (factor exactly 1, pixel-identical), 5.6dp at full drag swell (1.6x), easing over the same 120ms-in / 200ms-out DecelerateInterpolator with zero new animator or state. The v11.91 clamp below already uses dotR, so the swollen dot still stays a full circle inside the view; the readout baseline sits at h/2 - 8dp while the biggest dot top is h/2 - 5.6dp, so the time never overlaps the head. TalkBack never drags (swell 1.0, dot unchanged); CANCEL / POINTER_UP ease the swell out so the dot shrinks back with the bars; detach resets swellScale to 1.
            float dotR = 3.5f * getResources().getDisplayMetrics().density
                    * (1f + 2.4f * (swellScale - 1f));
            // v11.80: the dot used to sit at playedFrac * w
            // unclamped, so at full progress its centre was on the
            // view's right edge (right half clipped away) and at
            // a hair of progress its left half was cut. It now
            // stays a full circle inside the view, like the
            // drag readout's own clamp below.
            float dotX = playedFrac * w;
            if (dotX < dotR) dotX = dotR;
            if (dotX > w - dotR) dotX = w - dotR;
            canvas.drawCircle(dotX, h / 2f, dotR, playedPaint);
        }
        // v12.64: bookmark pins — a gold diamond at the top edge
        // with a hairline down to the bar centre, at each saved
        // minute's fraction. Only with a known range; a stale mark
        // past the end clamps inside. Paint-only: touch handling
        // below owns the tap, and the pins never dim with pause
        // (they are marks, not playback state), so they read in a
        // still whether the talk is playing or paused.
        // v15.32 (slice 1832): the paint reads the leaving set too
        // — bookmarks while pins live, fadingBookmarks while they
        // fade out, so a clear glides the pins off instead of
        // popping them. Touch still reads bookmarks only (below):
        // a leaving pin takes no tap-snap.
        java.util.List<Integer> pinsToPaint = (bookmarks != null ? bookmarks : fadingBookmarks);
        if (max > 0 && pinsToPaint != null) {
            float density = getResources().getDisplayMetrics().density;
            float d = 4.5f * density;
            // v15.32 (slice 1827): the arrival fade — the paint
            // carries pinAlpha, restored to 255 after the loop so
            // no later use inherits a faded alpha.
            bookmarkPaint.setAlpha((int) (255 * pinAlpha));
            for (int ms : pinsToPaint) {
                float frac = Math.max(0f, Math.min(1f, (float) ms / max));
                float cx = frac * w;
                if (cx < d) cx = d;
                if (cx > w - d) cx = w - d;
                float cy = d + 1f * density;
                canvas.drawLine(cx, cy + d, cx, h / 2f, bookmarkPaint);
                bookmarkPath.reset();
                bookmarkPath.moveTo(cx, cy - d);
                bookmarkPath.lineTo(cx + d, cy);
                bookmarkPath.lineTo(cx, cy + d);
                bookmarkPath.lineTo(cx - d, cy);
                bookmarkPath.close();
                canvas.drawPath(bookmarkPath, bookmarkPaint);
            }
            bookmarkPaint.setAlpha(255);
        }
        // v4.13: drag readout — while a finger scrubs, the exact
        // scrub time floats above the playhead (Spotify-style), so
        // a long talk can be landed by the clock, not by guessing
        // from the bar. Paint only: touch handling, the dot and
        // the bar colours are untouched, and the readout vanishes
        // with the finger (dragging clears on UP/CANCEL). Clamped
        // inside the view so edge drags never clip the text.
        // v14.18: it fades, not pops — the draw keys on
        // readoutAlpha (animateReadout, 120ms in / 200ms out), so
        // the time melts away at the scrub target after the lift
        // instead of vanishing in one frame.
        if (readoutAlpha > 0.01f && max > 0) {
            String t = fmtDragTime(progress);
            float tw = dragTextPaint.measureText(t);
            float cx = playedFrac * w;
            if (cx < tw / 2f) cx = tw / 2f;
            if (cx > w - tw / 2f) cx = w - tw / 2f;
            canvas.drawText(t, cx, h / 2f - 8f * getResources().getDisplayMetrics().density, dragTextPaint);
        }
    }

    // v13.31 (W10): ms-direct commit — the TalkBack path steps in ms,
    // so it commits in ms too instead of round-tripping through pixel
    // space, where a pre-layout width of 0 turned the target into NaN
    // and the seek landed at 0:00.
    private void scrubToMs(int ms) {
        if (max <= 0) return;
        int p = Math.max(0, Math.min(max, ms));
        progress = p;
        invalidate();
        if (listener != null) listener.onProgressChanged(this, p, true);
    }

    private void scrubTo(float x) {
        if (max <= 0 || getWidth() <= 0) return;
        float frac = Math.max(0f, Math.min(1f, x / getWidth()));
        scrubToMs(Math.round(frac * max));
    }

    // v15.36 (slice 1852): bookmark pin-tap glide — the pin
    // landing used to jump the bar to the pin in one frame
    // (v12.64 scrubTo on DOWN) while every other wave move
    // glides (poll glide v2.20, buffered tint v15.28, pin fades
    // v15.32). A tap landing within 14dp of a pin now glides
    // the playhead to that exact minute over 200ms Decelerate
    // (the swell-out rhythm, v2.53), so the jump reads as the
    // bar sliding to the mark the finger chose. The glide writes
    // through scrubToMs per frame, so the elapsed / remaining
    // clocks glide with the bar (the onProgressChanged channel,
    // same as a drag); the Room's 500ms poll can never fight
    // it — seeking is true from onStartTrackingTouch (fired
    // before the snap) and the poll's bar writes are
    // !seeking-guarded. A MOVE cancels it (the finger owns the
    // bar from there); UP / POINTER_UP finish it first, so the
    // release commit (seekTarget = getProgress()) always reads
    // the pin exactly, never a mid-glide value; CANCEL, a new
    // pin set, a track change (setMax) and a detach all cancel
    // it. A tap already on the pin (from == target) skips the
    // animator and lands at once — no movement, no glide. The
    // end listener is identity-guarded the v3.65 way; finish
    // nulls the field BEFORE cancelling, so a synchronous end
    // drops instead of clearing state the finish already owns.
    private void startPinGlide(int targetMs) {
        cancelPinGlide();
        if (targetMs == progress) { scrubToMs(targetMs); return; }
        pinGlideTargetMs = targetMs;
        final int from = progress;
        android.animation.ValueAnimator g = android.animation.ValueAnimator.ofInt(from, targetMs);
        g.setDuration(200);
        g.setInterpolator(new android.view.animation.DecelerateInterpolator());
        g.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                scrubToMs(((Integer) a.getAnimatedValue()).intValue());
            }
        });
        final android.animation.ValueAnimator self = g;
        g.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (pinGlideAnim != self) return;
                pinGlideAnim = null;
            }
        });
        pinGlideAnim = g;
        g.start();
    }

    private void cancelPinGlide() {
        if (pinGlideAnim == null) return;
        android.animation.ValueAnimator g = pinGlideAnim;
        pinGlideAnim = null;
        g.cancel();
    }

    private void finishPinGlide() {
        if (pinGlideAnim == null) return;
        int target = pinGlideTargetMs;
        android.animation.ValueAnimator g = pinGlideAnim;
        pinGlideAnim = null;
        g.cancel();
        scrubToMs(target);
    }

    // v11.91: TalkBack support — the view extends plain View, so
    // a TalkBack user could not operate the seek bar at all (no
    // range, no actions, activation a no-op). It now presents as
    // a SeekBar with its real range/value, and scroll-forward /
    // scroll-backward step ±10s (or 1/20th of the talk, whichever
    // is larger) through the same start/scrub/commit path a finger
    // drag uses, so Room commits the seek exactly like a release.
    // The v4.13 rest-paint alpha note stays untouched: setPlaying's
    // dim values are the literal v4.13 design ("a sounding one at
    // full"), not a bug — no change made there.
    @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(android.widget.SeekBar.class.getName());
        if (max > 0) {
            info.setRangeInfo(android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(
                    android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, max, progress));
        }
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override public boolean performAccessibilityAction(int action, android.os.Bundle args) {
        int fwd = android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId();
        int back = android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.getId();
        if (action == fwd || action == back) {
            if (max <= 0) return false;
            int step = Math.max(10000, max / 20);
            int target = action == fwd ? Math.min(max, progress + step) : Math.max(0, progress - step);
            if (listener != null) listener.onStartTrackingTouch(this);
            // v13.31 (W10): commit the step target in ms — the old
            // scrubTo(target / max * getWidth()) divided by a
            // pre-layout width of 0 (0/0 -> NaN -> round 0), so a
            // TalkBack scroll before first layout sought to 0:00.
            scrubToMs(target);
            if (listener != null) listener.onStopTrackingTouch(this);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // v11.80: this finger owns the drag (see the field
                // note); a second finger's DOWN is ignored, so it
                // can never hijack the scrub.
                activePointerId = e.getPointerId(0);
                dragging = true;
                animateSwell(1.25f, 120); // v2.53: swell eases in, no pop
                animateReadout(1f, 120); // v14.18: readout fades in with the swell
                if (listener != null) listener.onStartTrackingTouch(this);
                // v12.64: a tap landing within 14dp of a bookmark pin
                // glides the scrub to that exact minute (v15.36,
                // slice 1852 — the landing used to snap there in
                // one frame) — the pin is a one-tap jump, not a
                // near-miss scrub. The snapped value rides the
                // normal release commit (the glide is finished on
                // UP, so the bar sits exactly at the pin), and the
                // Room toasts the time from consumeSnappedBookmark;
                // a MOVE clears it, so a drag away from the pin
                // never toasts.
                snappedBookmarkMs = -1;
                cancelPinGlide(); // v15.36 (slice 1852): a stale glide can never survive a new DOWN
                float downX = e.getX();
                if (max > 0 && bookmarks != null) {
                    float snapPx = 14f * getResources().getDisplayMetrics().density;
                    float best = Float.MAX_VALUE;
                    int bestMs = -1;
                    for (int ms : bookmarks) {
                        float bx = Math.max(0f, Math.min(1f, (float) ms / max)) * getWidth();
                        float dist = Math.abs(downX - bx);
                        if (dist <= snapPx && dist < best) { best = dist; bestMs = ms; }
                    }
                    if (bestMs >= 0) {
                        snappedBookmarkMs = bestMs;
                        downX = (float) bestMs / max * getWidth();
                    }
                }
                if (snappedBookmarkMs >= 0) startPinGlide(snappedBookmarkMs);
                else scrubTo(downX);
                return true;
            case MotionEvent.ACTION_MOVE:
                // v11.80: read the tracked finger, not index 0 —
                // after the tracked finger lifts, index 0 is a
                // different finger, and the old code jumped the
                // scrub to its x. A MOVE with no tracked finger
                // (no DOWN) scrubs nothing.
                if (activePointerId < 0) return true;
                int moveIdx = e.findPointerIndex(activePointerId);
                if (moveIdx < 0) return true;
                snappedBookmarkMs = -1; // v12.64: a drag away from the pin is a scrub, not a bookmark tap
                cancelPinGlide(); // v15.36 (slice 1852): the finger owns the bar from here
                scrubTo(e.getX(moveIdx));
                return true;
            case MotionEvent.ACTION_UP:
                // v11.91: the listener fires only while a drag is
                // still open — a two-finger lift used to commit the
                // same scrub twice: the tracked finger's
                // POINTER_UP fired onStopTrackingTouch, then this
                // UP (the last finger leaving) fired it again, so
                // Room re-ran the whole release path (a second
                // seekTo at the same spot + a fresh 1500ms
                // settle hold that froze the bar/clocks). A
                // single-finger lift still fires once (dragging
                // is true until here); performClick stays
                // unconditional for the clickable-view contract.
                boolean wasDragging = dragging;
                dragging = false;
                activePointerId = -1;
                animateSwell(1f, 200); // v2.53: swell eases out, no snap
                animateReadout(0f, 200); // v14.18: readout melts out with the swell
                // v11.80: the clickable-view contract — a consumed
                // UP must offer performClick() (no OnClickListener
                // is attached, so this is a no-op that keeps
                // TalkBack activation working).
                performClick();
                finishPinGlide(); // v15.36 (slice 1852): land exactly on the pin before the commit reads progress
                if (wasDragging && listener != null) listener.onStopTrackingTouch(this);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                // v11.80: lifting the tracked finger ends the drag
                // exactly like a lift (commits at the finger's last
                // scrub); lifting any other finger is ignored, so
                // the tracked finger keeps scrubbing undisturbed.
                if (e.getPointerId(e.getActionIndex()) != activePointerId) return true;
                dragging = false;
                activePointerId = -1;
                animateSwell(1f, 200);
                animateReadout(0f, 200); // v14.18: readout melts out with the swell
                performClick();
                finishPinGlide(); // v15.36 (slice 1852): land exactly on the pin before the commit reads progress
                if (listener != null) listener.onStopTrackingTouch(this);
                return true;
            case MotionEvent.ACTION_CANCEL:
                // v7.99: cancelled, not released — see the
                // onCancelTrackingTouch contract above. Swell and
                // readout still settle exactly like a lift, but
                // the listener must NOT commit the preview seek.
                // v13.20: the listener fires only while a drag
                // is still open (the ACTION_UP v11.91 rule, in
                // CANCEL form) — after a two-finger POINTER_UP
                // commit the drag is closed, but a later system
                // CANCEL still reached the Room handler, which
                // cleared the 1500ms settle hold while the
                // committed seek was landing, so the bar could
                // snap back to the pre-seek position. A real
                // mid-drag CANCEL still fires (dragging true).
                boolean wasDraggingCancel = dragging;
                dragging = false;
                activePointerId = -1;
                snappedBookmarkMs = -1; // v12.64: a cancelled tap is not a bookmark jump either
                cancelPinGlide(); // v15.36 (slice 1852): a cancelled tap glides nowhere
                animateSwell(1f, 200);
                animateReadout(0f, 200); // v14.18: readout melts out with the swell
                if (wasDraggingCancel && listener != null) listener.onCancelTrackingTouch(this);
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override public boolean performClick() { return super.performClick(); }
}
