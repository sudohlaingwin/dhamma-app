package com.h2h.dhamma;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// v1.37: clean Room player — only Play/Pause, Next and the talk list.
// (User order: "Play Pause Next ဆိုတော်ပြီ" — speed, sleep, repeat,
// shuffle, share, copy, seek-jumps and the extra status lines are gone.)
public class RoomActivity extends Activity {

    public static class Talk {
        String title, url;
        Talk(String t, String u) { title = t; url = u; }
    }

    private List<Talk> talks = new ArrayList<Talk>();
    private TalkAdapter adapter;
    private ListView roomList;
    private RoomView roomView;
    private Button btnRetry;
    // v2.78: the retry fade-out animator (see hideRetry) — kept in a
    // field so a failure arriving mid-fade can cancel it instead of
    // being overwritten by it, and so onPause/onDestroy can finish
    // it to its intended GONE state instead of stranding the button
    // half-faded. Null whenever no fade-out is running.
    private android.animation.ObjectAnimator retryFade;
    // v11.10: direction of the fade above -- true only while a
    // hide fade (alpha -> 0) runs; the v3.53 show glides reuse
    // the same field, and onPause must not GONE a button that
    // was gliding back to visible (Talks mirror of this fix).
    private boolean retryFadeHiding = false;
    private TextView nowPlaying, elapsedText, totalText, sleepText;
    // v4.12: clock display mode — false shows the talk length in the
    // total slot (as always), true shows the time left ("-mm:ss",
    // Spotify-style tap-to-toggle on the clock itself). No new view:
    // the two existing clock texts are the toggle. The poll's total
    // branch honours it, and the toggle refreshes at once below.
    private boolean showRemaining = false;
    private WaveSeekView seekBar;
    private android.widget.ImageButton btnPlayPause; // v7.28: vector icon, not a text glyph
    // v5.18: Room speed button, cached so the 500ms poll can refresh
    // its label change-only without a findViewById every tick.
    private Button roomSpeedBtn;
    // v5.19: Room download button, cached so the 500ms poll can refresh
    // its glyph/visibility change-only without a findViewById every tick.
    private Button roomDownloadBtn;
    // v5.20: Room favourite button, cached so the 500ms poll can refresh
    // its glyph/visibility change-only without a findViewById every tick.
    private Button roomFavBtn;
    // v5.21: Room share button, cached for the same reason — the
    // glyph never changes, only its visibility (live session or not).
    private Button roomShareBtn;
    // v5.23: Room repeat/shuffle buttons, cached so the 500ms poll
    // can refresh their glyphs change-only without a findViewById
    // every tick, mirroring the speed/download/fav/share buttons.
    private Button roomRepeatBtn;
    private android.widget.ImageButton roomShuffleBtn; // v7.28: vector icon, not a text glyph
    // v2.07: premium play motion — gold glow ring + morph bounce.
    private View playGlow;
    private android.animation.ObjectAnimator glowPulse;
    // v2.20: smooth seek glide + glow scale pulse — the 500ms poll
    // used to jump the seek thumb (and the clock) in 500ms steps,
    // and the glow ring only breathed in alpha. Both live here so
    // the poll, the drag guard and onPause can cancel them together.
    private android.animation.ValueAnimator seekGlide;
    private android.animation.ObjectAnimator glowScale;
    // v3.26: seek-settle hold — a scrub release used to snap the wave
    // bar back to the pre-drag position on the next 500ms poll (the
    // service position had not caught up to the dragged target yet),
    // then glide forward again once the seek landed: a visible
    // jump-back on every scrub. The poll now holds the dragged
    // target for up to 1500ms after a user seek while the service
    // position is still behind it, then resumes normal updates.
    private long lastUserSeekMs = 0;
    private int lastUserSeekPos = 0;
    // v12.72: the sounding talk the wave's bookmark pins belong
    // to — the poll refreshes the pins when this changes, not
    // only when the duration changes (a foreign head whose
    // duration equals the finished talk's to the exact ms used
    // to keep the old talk's pins until the next duration
    // change, sim1278). Tracked only on polls that know a
    // duration, so a change is always caught on the first such
    // poll after the handover.
    private String lastBookmarkTickUrl = null;
    // v3.29: seek direction for the v3.26 hold — the hold only ever
    // covered a FORWARD seek (service position behind the target):
    // its clear test (pos >= target) is true the instant a
    // BACKWARD scrub releases, because the service position is
    // still AHEAD of the dragged target. The next poll then glided
    // the wave from the target back up to the old position and
    // snapped down again once the seek landed — a backward sweep
    // + snap on every rewind scrub. +1 = forward, -1 = backward.
    private int lastUserSeekDir = 1;
    // v2.33: glow fade in/out — the ring used to pop to 0.3 alpha on
    // play and vanish instantly on pause, while the altar light
    // itself ramps over 600ms (v2.13), so the button and the shrine
    // changed in two rhythms. The flags keep the 500ms poll from
    // re-triggering a fade that is already running, and from
    // starting the pulse loop before the fade-in lands on 0.3.
    private boolean glowFadingIn = false;
    private boolean glowFadingOut = false;
    // v3.66: premium glow fade-in/settle end-action guard — the
    // v3.62 fadeOut guard, v3.63 themeDip guard, v3.64 retry-hide
    // guard and v3.65 wave stop-ease guard applied to the last
    // unguarded end actions in the Room poll. The fade-out end
    // below is flag-guarded and the morph/glyph ends are
    // gen-guarded, but these two fade-in/settle ends cleared
    // glowFadingIn unconditionally: a stale end landing late
    // (v3.19/v3.64 behaviour) after a newer fade-in/settle had
    // started cleared the flag early, so the 500ms poll started
    // the pulse loop mid-fade and alpha jumped to the loop's
    // 0.3->0.7 keyframes instead of gliding to the 0.3 floor.
    // Each start bumps this generation and its end returns
    // unless still current, mirroring playMorphGen above.
    private int glowFadeGen = 0;
    // v3.67: drawer-close generation guard — the v3.62 fadeOut
    // guard + v3.63 themeDip guard + v3.64 retry-hide guard +
    // v3.65 wave stop-ease guard + v3.66 glow guard applied to
    // the last unguarded end action in the Room: the Play List
    // drawer close. See setPlayListPanel for the full story.
    // v3.68: the glow fade-out end was the one guard the
    // v3.66 glow fix left behind (flag-only, not gen-guarded);
    // see the resume settle above for the full story.
    private int drawerCloseGen = 0;
    // v2.34: Next-button forward nudge — Next already morphs in
    // scale (v2.09), but a scale bounce reads the same on every
    // button while "next" has a direction. A dedicated
    // ObjectAnimator on translationX (independent of the scale
    // ViewPropertyAnimator, so morph + press squash still run)
    // slides the button 10dp forward and back over 320ms on each
    // manual advance. Headset/notification Next has no visible
    // button to nudge, so only the tap path calls it.
    private android.animation.ObjectAnimator nextNudge;
    // v7.03: forwardNudge field deleted with nudgeForward (above) —
    // orphaned with the Room secondary row's Forward button.
    // v2.71: Play List button fresh-open entrance — the button (top-right,
    // delivered v2.50) used to pop in instantly while the shrine photo
    // faded (v2.21) and the bottom panel poured in row by row (v2.15),
    // so the one control sitting over the photo moved in a different
    // rhythm from the rest of the screen. It now joins the entrance
    // as the final step (320ms fade + 24dp rise, starting after the
    // controls row, same decelerate). It runs on a dedicated
    // AnimatorSet on alpha/translationY, NOT the shared
    // ViewPropertyAnimator: the button also carries the v2.08 press
    // squash, whose ACTION_DOWN calls view.animate().cancel() — on
    // the shared animator that would cancel the entrance mid-flight
    // and strand the button half-faded if tapped inside its first
    // 640ms. The ObjectAnimator pair is untouched by that cancel,
    // and the press scale runs alongside it. onPause/onDestroy
    // cancel + reset it, like the panel rows.
    private android.animation.AnimatorSet playListEntrance;
    // v3.82: premium drawer-toggle state dim — the toggle (top-right,
    // three-bar icon since v3.72) was the one drawer-related view
    // the drawer choreography never touched: panel slides (v2.48),
    // rows cascade (v2.10), header pours/dissolves (v3.07/v3.80),
    // but the button that opened it stayed identical whether the
    // drawer was open or closed, so an open drawer read as if
    // nothing owned it. It now dims to 0.8 while its drawer is open
    // and brightens back to 1.0 on close, over 200ms inside the
    // panel's 300ms slide. Alpha channel only, on its own
    // ObjectAnimator: the tap morph + press squash live on scale
    // and the fresh-open entrance (v2.71) owns alpha until it ends,
    // so neither is ever cancelled or stranded by this. v3.83:
    // the entrance-end listener now catches up a drawer opened
    // inside that entrance window (it used to stay undimmed
    // for the whole session); the skip itself still avoids any
    // fight while the entrance runs. drawerOpen
    // is the logical state (set at every setPlayListPanel call and
    // at rotation restore), so the onResume re-arm after a leave
    // reads it instead of the panel's mid-animation visibility.
    private android.animation.ObjectAnimator toggleDimAnim;
    private boolean drawerOpen = false;
    private boolean playListEntranceDone = true;
    // v2.27: Room sheet playing-row pulse — at most one row is ever
    // playing, so a single animator + its title view is enough (same
    // model as the library's v2.25 pulse). The poll re-resolves the
    // visible playing row every 500ms, so a recycled view, a track
    // change, a pause or a closed sheet is caught at the next tick.
    private android.animation.ObjectAnimator roomPulse;
    private android.view.View roomPulseView;
    // v3.17: pulse-start settle flag — mirrors the v3.16 glow
    // settle. A pulse title caught mid-fade (alpha < 1) must glide
    // to 1 before the loop starts, or the loop's first keyframe
    // (exactly 1f) snaps it there in one frame. While settling,
    // roomPulseView already points at the title, so the poll's
    // handover branch skips it and the settle's end action starts
    // the loop; a pause or a leave clears the flag with the view.
    private boolean roomPulseSettling = false;
    private boolean lastPollActive = false;
    // v11.16: ended state the poll last saw — mirrors Main's
    // mainGridEnded / Talks' talkRowEnded (v11.15): the Room
    // poll never rebinds rows on its own tick, so without
    // this the bind gate below would only move the mark on
    // the next scroll, return or menu action.
    private boolean lastPollEnded = false;
    // v3.20: play/pause glyph crossfade — the label used to swap at
    // full alpha in the same frame the v2.07 morph snapped scale to
    // 0.86, so the ▶/⏸ glyph visibly popped mid-squash. The label
    // now swaps at the bottom of a short alpha dip (ObjectAnimator,
    // not the ViewPropertyAnimator channel, so press squash and
    // morph cancels never strand it dim). Null whenever no dip is
    // running; the generation guard is the v3.19 lesson — a
    // cancelled animator still fires its end action, so the gen is
    // bumped BEFORE every cancel and a stale end action returns
    // without swapping text.
    private android.animation.ObjectAnimator playGlyphFade;
    private int playGlyphGen = 0;
    // v3.35: morph generation guard, same v3.19 lesson as the glyph
    // gen above — the Room play morph's dip end action must not
    // fire a stale spring-back over a newer dip.
    private int playMorphGen = 0;
    private AudioService service;
    private boolean bound;
    private int currentIndex = -1;
    // v4.32: REAL session queue — ordered talk URLs the listener
    // queued by hand (Play next / Add to queue from a drawer's ⋮
    // menu). The talk list stays the source of truth for titles;
    // queueUrls only reorders what Next / auto-advance play next.
    // v4.37: persisted per sayadaw in prefs (Prefs.saveQueue) as
    // well as across rotation in onSaveInstanceState — closing
    // the app used to lose a hand-built queue; a fresh Room now
    // restores it on list load (filtered to the loaded talks).
    private java.util.ArrayList<String> queueUrls = new java.util.ArrayList<String>();
    // v12.03: snapshot of queueUrls as last persisted/synced -- the removed
    // set for the saveQueue adoption is the diff between this and the
    // current in-memory list (see saveQueue). Updated at every saveQueue
    // exit and at every read-only adopt (restore/sync/bundle restore);
    // reset to empty on a sayadaw switch (the clear there is a deliberate
    // emptying under a new key, not a removal to tombstone). It is NOT
    // bundled across rotation: the bundle's queueUrls IS the last
    // persisted state (every mutation saves at once), so the snapshot is
    // rebuilt from it on restore.
    private java.util.ArrayList<String> lastQueueUrls03 = new java.util.ArrayList<String>();
    // v5.16: shuffle play history — the URLs played before the current
    // talk, oldest first, capped at 50. Previous steps back
    // through what was actually heard (Spotify-style), not to
    // the list-previous talk, which a jump never played.
    // v8.17: ungated from shuffle — see handlePrev.
    // Stale URLs (gone from a reloaded list) are pruned lazily by
    // handlePrev and skipped by the long-press preview, never
    // played. historyBackUrl marks a history-back in flight so
    // playIndex does not push the talk being left back onto the
    // stack (that would make a second Previous bounce forward
    // instead of stepping further back); it is consumed by the
    // matching playIndex call, and cleared when its data-gate
    // question is dismissed unanswered — with the popped entry
    // restored to the stack first (v8.15), so an unanswered
    // question loses nothing. Rotation saves/restores the stack
    // with the hand queue, and the marker with its question; a
    // fresh Room starts empty.
    private java.util.ArrayList<String> prevHistory = new java.util.ArrayList<String>();
    // v8.36: history-clear generation last observed by this Room
    // (-1 = not yet observed). Settings' doClearHistory bumps the
    // "historyClearGen" pref on every clear; onResume compares it
    // and drops both in-memory stacks when it moved. Saved with
    // the stacks across rotation, so a recreation after a clear
    // still detects the pre-clear generation instead of adopting
    // the post-clear one as its baseline.
    private long seenHistoryClearGen36 = -1;
    // v8.08: drain the service's hidden-advance trail into prevHistory.
    // advanceInBackground58 chains while the Room sits hidden (a second
    // completion moves from the already-advanced talk, so the Room's
    // index still names the FIRST finished talk when it returns), and
    // the v8.06/v8.07 correctors only ever pushed that one left-behind
    // talk — every intermediate talk in the chain never entered
    // prevHistory, so a shuffle Previous after a chained hidden
    // advance skipped straight past it. The service records each
    // hidden step's ended talk (stream URL, cap 50) in the
    // "hiddenTrail" pref; draining appends the entries this Room's
    // list still names, in chain order, and keeps only the entries
    // no list claimed (another sayadaw's chain stays for its own
    // Room). Returns true when the left-behind URL was among the
    // appended entries, so the caller pushes it only when the trail
    // did not already supply it (empty trail = the old single push;
    // a trail that ends before the left-behind talk — a recreated
    // Room's later advance — still gets the left-behind talk pushed
    // after the drain, keeping the walk order chronological).
    private boolean drainHiddenTrail08(String leftBehindUrl) {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String raw = sp.getString("hiddenTrail", "");
            if (raw == null || raw.length() == 0) return false;
            String[] parts = raw.split("\\n");
            java.util.ArrayList<String> keep = new java.util.ArrayList<String>();
            boolean appendedLeftBehind = false;
            for (int i = 0; i < parts.length; i++) {
                String u = parts[i];
                if (u == null || u.length() == 0) continue;
                if (talkIndexForUrl(u) >= 0) {
                    pushPrevHistory16(u);
                    if (u.equals(leftBehindUrl)) appendedLeftBehind = true;
                } else keep.add(u);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < keep.size(); i++) {
                if (i > 0) sb.append("\n");
                sb.append(keep.get(i));
            }
            sp.edit().putString("hiddenTrail", sb.toString()).apply();
            return appendedLeftBehind;
        } catch (Exception ignored) { return false; }
    }
    // v8.16: one push rule for the whole shuffle history — a
    // talk is never appended directly on top of itself. The
    // v8.15 dismiss restore and the v8.06/v8.07/v8.08
    // correctors each re-add the same left-behind talk for a
    // different reason (an unanswered gate question gives its
    // popped entry back; a poll/onResume/adopt corrector records
    // the same finished talk when it re-points the index), and
    // in the first-mover + gate-cancel flow both fire for one
    // transition: the restore re-adds the popped entry, then the
    // poll resync pushes it again (or the poll pushes first and
    // the restore appends second), leaving [.., F, F]. The next
    // two Previous presses then both land on F instead of
    // walking F -> older talk. A consecutive duplicate is never
    // a real walk step — the talk before F is an older talk, not
    // F again — so skipping it loses nothing; deeper, non-
    // consecutive repeats (a tiny list's fallback picks) are
    // untouched, since only the top element is compared. Every
    // push site below funnels through here, including the trail
    // drain, so no site can re-create the pair on its own.
    private void pushPrevHistory16(String u) {
        if (u == null || u.length() == 0) return;
        if (!prevHistory.isEmpty() && prevHistory.get(prevHistory.size() - 1).equals(u)) return;
        prevHistory.add(u);
        while (prevHistory.size() > 50) prevHistory.remove(0);
    }
    private String historyBackUrl = null;
    // v8.26: forward history — the redo stack for Previous.
    // handlePrev pops prevHistory destructively and playIndex's
    // historyBack branch deliberately does NOT push the talk being
    // left back onto prevHistory (that would make a second Previous
    // bounce forward), so the talk just left was recorded NOWHERE:
    // after stepping back, Next played list-next / shuffle / queue
    // instead of the talk just heard, unless it happened to be the
    // list successor. A history-back play now pushes the talk left
    // behind here (oldest first, cap 50, same dedup rule as
    // pushPrevHistory16), and peekNextIndex consults it after the
    // hand-queue head and before shuffle/list-next — so every Next
    // door (button, swipe, strip, notification, headset, auto-
    // advance) and every Next preview (Up Next line, mark, long-
    // press preview) agree on the redo target, and a gated stop
    // loses nothing because peek never pops: only a real fresh
    // play pops the top (playIndex) or clears the stack (any
    // branching play). Cross-sayadaw back-steps push nothing:
    // the handover path never reaches playIndex's historyBack
    // branch, so forward across a sayadaw switch stays list-next
    // in the new Room, exactly as today. Plain neighbour-step
    // Previous (empty prevHistory) also pushes nothing — its Next
    // already returns in the sequential case via list-next.
    // Rotation saves/restores the stack with prevHistory; a fresh
    // Room starts empty, like prevHistory. Stale entries (gone
    // from a reloaded list) are pruned lazily by forwardTopIndex26.
    private java.util.ArrayList<String> forwardHistory = new java.util.ArrayList<String>();
    private void pushForwardHistory26(String u) {
        if (u == null || u.length() == 0) return;
        if (!forwardHistory.isEmpty() && forwardHistory.get(forwardHistory.size() - 1).equals(u)) return;
        forwardHistory.add(u);
        while (forwardHistory.size() > 50) forwardHistory.remove(0);
        saveForwardHistory27(); // v8.27: service background advance reads this
    }
    private int forwardTopIndex26() {
        boolean pruned27 = false;
        while (!forwardHistory.isEmpty()) {
            String u = forwardHistory.get(forwardHistory.size() - 1);
            int fi = talkIndexForUrl(u);
            if (fi >= 0) { if (pruned27) saveForwardHistory27(); return fi; }
            forwardHistory.remove(forwardHistory.size() - 1);
            pruned27 = true;
        }
        if (pruned27) saveForwardHistory27();
        return -1;
    }
    // v8.27: persist the forward stack for the background
    // advance -- the service cannot see this Room's memory,
    // so every mutation writes the stack (newline-joined
    // URLs, order kept -- a StringSet would lose it) to
    // forwardHistoryRaw. playIndex writes even when the
    // stack is already empty: a fresh play defines the
    // session, so it must invalidate a back-stack Room's
    // stale copy before any background advance of this
    // session can read it. A fresh Room writes nothing
    // until its first play, so merely opening a Room never
    // clobbers the sounding session's stack.
    private void saveForwardHistory27() {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < forwardHistory.size(); i++) {
                if (i > 0) sb.append("\n");
                sb.append(forwardHistory.get(i));
            }
            getSharedPreferences("dhamma", MODE_PRIVATE).edit().putString("forwardHistoryRaw", sb.toString()).apply();
        } catch (Exception ignored) {}
    }
    // v8.27: reconcile a background-played forward step --
    // the service's background advance can now play the
    // pending forward target while this Room sits in the
    // back stack, bypassing playIndex, so the in-memory
    // stack still names the sounding talk as "next": the
    // next Next tap (and every preview until then) would
    // replay it. A forward top equal to the sounding talk
    // is therefore already consumed: pop it and persist.
    // Safe everywhere else: in the foreground the sounding
    // talk can never be the forward top (playIndex pops on
    // a real play), so this only fires for a background-
    // played step. Callers: poll resync, handleNext/Prev
    // entry (the notification/headset/strip path runs
    // before any return poll tick).
    // v8.28: the v8.27 single pop only covers ONE background
    // step. A chained hidden advance (two talks ending while
    // the Room sits hidden) plays two forward entries, and
    // an advance that plays the last forward entry and then
    // moves on to list-next leaves the whole stack stale:
    // in both cases the sounding talk is NOT the stack top
    // (chained: stack [C,B], sounding C; moved-on: stack
    // [B], sounding list-next), so the old pop fired on
    // nothing, peek kept naming an already-heard talk as
    // next, and the next saveForwardHistory27 resurrected
    // the service-popped entries into prefs for a later
    // background advance to replay. Fix: adopt the prefs
    // copy (forwardHistoryRaw) when it is a subsequence of
    // the in-memory stack, in order -- the service only ever
    // removes entries it played (stale tops it skipped stay,
    // so the copy need not be a prefix), and the Room writes
    // the copy on every mutation, so any in-order shrinkage
    // is exactly background consumption. Guarded twice:
    // the sounding track must belong to this list (a foreign
    // session sounding elsewhere must never clear this
    // Room's stack), and a copy that is NOT a subsequence
    // (another Room overwrote the key on its own fresh play)
    // falls back to the v8.27 single pop, never an adopt.
    private void reconcileForwardAfterBackground27() {
        try {
            if (forwardHistory.isEmpty() || service == null) return;
            boolean soundingHere = false;
            for (int i = 0; i < talks.size(); i++)
                if (isCurrentTalk(talks.get(i).url)) { soundingHere = true; break; }
            if (!soundingHere) return;
            String raw = getSharedPreferences("dhamma", MODE_PRIVATE)
                    .getString("forwardHistoryRaw", "");
            java.util.ArrayList<String> persisted =
                    new java.util.ArrayList<String>();
            if (raw != null && raw.length() > 0) {
                String[] parts = raw.split("\\n");
                for (int i = 0; i < parts.length; i++)
                    if (parts[i] != null && parts[i].length() > 0) persisted.add(parts[i]);
            }
            int pi = 0;
            for (int si = 0; si < forwardHistory.size() && pi < persisted.size(); si++)
                if (forwardHistory.get(si).equals(persisted.get(pi))) pi++;
            if (pi == persisted.size()) {
                // v11.85: an EMPTY persisted copy is a subsequence of everything, so the
                // guard above never fires for it — but a fresh play in ANOTHER Room for
                // this sayadaw writes exactly that empty copy (v8.27: "a fresh play
                // defines the session"), and adopting it would wipe this Room's live
                // redo chain. Adopt an empty copy only when it is consistent with
                // service consumption here: the sounding talk is the stack's last
                // entry (already consumed) or its list successor (the moved-on case,
                // v8.28). Otherwise fall through to the single-pop branch below,
                // which preserves the stack.
                if (persisted.isEmpty()) {
                    String top85 = forwardHistory.get(forwardHistory.size() - 1);
                    boolean consumed85 = top85 != null && top85.length() > 0 && isCurrentTalk(top85);
                    if (!consumed85 && top85 != null) {
                        int topIdx85 = talkIndexForUrl(top85);
                        if (topIdx85 >= 0 && topIdx85 + 1 < talks.size()
                                && isCurrentTalk(talks.get(topIdx85 + 1).url)) consumed85 = true;
                    }
                    // v12.32: a REMOVED key also adopts — the persisted copy is empty
                    // in two shapes, and only one of them is the v11.85 another-Room
                    // fresh play (which WRITES "" — the key stays present). The other
                    // is a service branch play (queue/shuffle/plain/foreign tail) or a
                    // Settings history clear: those REMOVE the key, and a branch play
                    // invalidates the redo chain exactly like playIndex's fresh-play
                    // clear — the sounding session moved on past the back-stepped talk.
                    // Without this, a hidden chain — forward step played, then a shuffle
                    // (or queue-head / plain) branch — left this Room's in-memory stack
                    // naming the already-heard talk as "next" on return (peek's forward
                    // step outranks list-next), so the next Next replayed it out of
                    // order while the chooser header (which reads the persisted copy)
                    // named the true successor. A present-but-empty key keeps the
                    // v11.85 protection exactly: only the two sounding checks above
                    // adopt there. pushForwardHistory26 persists on every push, so a
                    // non-empty in-memory stack always wrote the key once — absent can
                    // only mean a later removal, never a never-written stack.
                    boolean keyAbsent32 = false;
                    try {
                        keyAbsent32 = !getSharedPreferences("dhamma", MODE_PRIVATE)
                                .contains("forwardHistoryRaw");
                    } catch (Exception ignored) {}
                    if (consumed85 || keyAbsent32) {
                        forwardHistory = new java.util.ArrayList<String>(persisted);
                        saveForwardHistory27();
                        return;
                    }
                } else {
                    if (persisted.size() != forwardHistory.size()) {
                        forwardHistory = new java.util.ArrayList<String>(persisted);
                        saveForwardHistory27();
                    }
                    return;
                }
            }
            String top = forwardHistory.get(forwardHistory.size() - 1);
            if (top != null && top.length() > 0 && isCurrentTalk(top)) {
                forwardHistory.remove(forwardHistory.size() - 1);
                saveForwardHistory27();
            }
        } catch (Exception ignored) {}
    }
    private String moveHeldUrl = null;
    // v4.92: pick-up/drop queue move — the URL of the talk the
    // listener picked up (v6.85: queue-only long-press, after the
    // v6.61 drawer row ⋮ removal + v6.84 menu deletion killed the
    // old "နေရာရွှေ့မယ်" arming path), waiting for the drop tap
    // on another queued row. v5.79: the arm now
    // survives rotation via onSaveInstanceState (validated
    // against queueUrls on restore, like every other use);
    // drawer close still clears it in setPlayListPanel, and every
    // use re-validates
    // against queueUrls, so a queue changed elsewhere can never
    // drop a talk that is no longer queued.
    // v4.55: queue-only drawer view — the Play List drawer shows the
    // full talk list, so a hand-built queue only read as ◈ marks
    // scattered through it; a listener could never see the queue
    // itself in play order. When true, the drawer adapter shows
    // only queued talks, in queue order (the streaming queue
    // screen). pos is then a DISPLAY position, never a talk index:
    // drawerTalkIndex / drawerPosOfTalk / drawerScrollPos translate
    // between the two, skipping queue URLs gone from the list.
    // The flag is in-memory + rotation state only — the persisted
    // queue itself (Prefs.saveQueue) is untouched, and every scroll
    // site below maps through drawerScrollPos so a talk change
    // never scrolls a queue-only list to a full-list index.
    private boolean drawerQueueOnly = false;
    // v4.96: filter views on the Room drawer — the chooser gained
    // ★-only / unlistened-only views (v4.94) and the library
    // gained them (v4.95), but the Room drawer, where the queue
    // itself lives, still showed only the full list or the queue:
    // a listener in the Room who wanted just their ★ talks, or
    // just the talks not heard yet, had to leave the Room. Mode
    // 0 = all, 1 = ★ only, 2 = unlistened only; v5.14 adds
    // 3 = downloaded only, mirroring the chooser's v5.13 fourth
    // step, so an offline listener in the Room can view just the
    // saved talks for this sayadaw without leaving the player.
    // In-memory + rotation state only, like drawerQueueOnly,
    // and mutually exclusive with it (entering one view exits
    // the other, the chooser's rule). The filtered indices are
    // recomputed on demand from the same favTalks / playedTalks
    // sets the drawer marks already read, so a return from
    // Settings or the chooser never shows stale ★/✓ membership;
    // mode 3 reads the cached downloadedFiles set through
    // isDownloadedFile instead, so the poll stays cheap and a
    // download landed from the Room appears on the next rebind
    // after the rescan, with no new pref or set.
    private int drawerFilterMode = 0;
    private int talkIndexForUrl(String u) {
        for (int i = 0; i < talks.size(); i++) if (talks.get(i).url.equals(u)) return i;
        return -1;
    }
    private java.util.ArrayList<Integer> drawerFilteredIndices() {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<Integer>();
        try {
            java.util.Set<String> s = getSharedPreferences("dhamma", MODE_PRIVATE)
                    .getStringSet(drawerFilterMode == 1 ? "favTalks" : "playedTalks", null);
            for (int i = 0; i < talks.size(); i++) {
                String u = talks.get(i).url;
                if (drawerFilterMode == 3) { if (isDownloadedFile(u)) out.add(i); }
                else if (drawerFilterMode == 1) { if (s != null && s.contains(u)) out.add(i); }
                else if (s == null || !s.contains(u)) out.add(i);
            }
        } catch (Exception ignored) {}
        return out;
    }
    // v7.12: queue-only display lines — the drawer rows in queue
    // order, parsed, not raw. Before this, every queue-only map
    // compared raw lines against the list, so a foreign v2 line
    // (another sayadaw's talk, kept since v7.08) was skipped
    // everywhere: a queue of foreign lines only opened an EMPTY
    // drawer, and a foreign line between two in-list lines shifted
    // nothing — the rows after it kept their pre-foreign
    // positions. A line displays when its URL is in this list
    // (in-list row, v1 or v2 spelling alike) or when it is a v2
    // line for another sayadaw (foreign row, below); anything
    // else is stale and still skipped, exactly as before. Search
    // still narrows to in-list hits only (drawerSearchSeq is
    // talk-index based, and the drawer search box is unwired —
    // wireDrawerSearch is an empty stub — so no live path hits
    // that branch today); the foreign surface lives in the
    // unsearched queue-only view.
    private java.util.ArrayList<String> drawerQueueDisplayLines() {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        for (String line : queueUrls) {
            QueueEntry e = QueueEntry.parse(line);
            if (e == null) continue;
            if (talkIndexForUrl(e.url) >= 0) out.add(line);
            else if (e.isV2() && (sayadawName == null
                    || !e.sayadaw.equals(sayadawName))) out.add(line);
        }
        return out;
    }
    // Foreign entry at a queue-only display position, or null:
    // null for in-list rows, for stale lines, and outside the
    // unsearched queue-only view. Callers keep using
    // drawerTalkIndex for talk rows (-1 for a foreign row, as
    // before) and this helper for foreign rows, so no existing
    // talk-row site changes meaning.
    private QueueEntry drawerForeignEntry(int displayPos) {
        if (!drawerQueueOnly || drawerSearching()) return null;
        java.util.ArrayList<String> lines = drawerQueueDisplayLines();
        if (displayPos < 0 || displayPos >= lines.size()) return null;
        QueueEntry e = QueueEntry.parse(lines.get(displayPos));
        if (e == null || talkIndexForUrl(e.url) >= 0) return null;
        return e.isV2() ? e : null;
    }
    private int drawerDisplayCount() {
        if (drawerSearching()) return drawerSearchSeq().size();
        if (drawerQueueOnly) return drawerQueueDisplayLines().size();
        if (drawerFilterMode != 0) return drawerFilteredIndices().size();
        return talks.size();
    }
    private int drawerTalkIndex(int displayPos) {
        if (drawerSearching()) {
            java.util.ArrayList<Integer> seq = drawerSearchSeq();
            if (displayPos < 0 || displayPos >= seq.size()) return -1;
            return seq.get(displayPos);
        }
        if (drawerQueueOnly) {
            java.util.ArrayList<String> lines = drawerQueueDisplayLines();
            if (displayPos < 0 || displayPos >= lines.size()) return -1;
            return talkIndexForUrl(queueLineUrl(lines.get(displayPos)));
        }
        if (drawerFilterMode != 0) {
            java.util.ArrayList<Integer> f = drawerFilteredIndices();
            if (displayPos < 0 || displayPos >= f.size()) return -1;
            return f.get(displayPos);
        }
        return displayPos;
    }
    private int drawerPosOfTalk(int talkIdx) {
        if (talkIdx < 0) return -1;
        if (drawerSearching()) return drawerSearchSeq().indexOf(talkIdx);
        if (drawerFilterMode != 0 && !drawerQueueOnly) return drawerFilteredIndices().indexOf(talkIdx);
        if (!drawerQueueOnly) return talkIdx;
        // v7.12: count display lines, foreign rows included — a
        // foreign row occupies a display slot, so an in-list row
        // after it sits one slot lower than the old in-list-only
        // count said (scroll-to-row and the pulse row both map
        // through here).
        int seen = -1;
        for (String line : drawerQueueDisplayLines()) {
            seen++;
            if (talkIndexForUrl(queueLineUrl(line)) == talkIdx) return seen;
        }
        return -1;
    }
    // Display position to scroll to for a talk: its own row when
    // shown, else the queue head (0) in queue-only view — the
    // playing talk was consumed from the queue, so the head is the
    // Up Next talk — or the filtered head (0) in a filter view,
    // where the playing talk may not be shown at all — else -1
    // (no talk, nothing to scroll to).
    private int drawerScrollPos(int talkIdx) {
        if (talkIdx < 0) return -1;
        int dp = drawerPosOfTalk(talkIdx);
        // v4.98: a searched talk that is not a hit has no row to
        // scroll to — the old queue/filter fallback (head) or the
        // full-list index would both land on the wrong row in a
        // filtered list, so a search miss scrolls nowhere (every
        // caller already guards sp >= 0).
        if (drawerSearchQuery != null && drawerSearchQuery.length() > 0) return dp >= 0 ? dp : -1;
        return dp >= 0 ? dp : ((drawerQueueOnly || drawerFilterMode != 0) ? 0 : talkIdx);
    }
    // v4.98: title search in the Room Play List drawer — the
    // chooser gained one (v4.93) and the drawer gained filter
    // views (v4.96), but the drawer itself still had no way to
    // find one title in a long sayadaw list without scrolling it.
    // Unlike the chooser's search, this one COMPOSES with the
    // current view instead of exiting it: the search sequence is
    // built on demand from the current base sequence (queue order
    // in queue-only view, filter indices in a filter view, full
    // list otherwise), so a ★-only view narrows to ★ hits and a
    // queue view narrows to queued hits, and no cached hit list
    // can ever go stale when the queue syncs, a filter empties,
    // or a reload replaces the list. Matching mirrors v4.93
    // exactly (trimmed query, plain title substring), and row
    // numbers stay full-list numbers (queue position in
    // queue-only view) like the chooser's, so every verb — tap,
    // long-press share, ⋮ menu, pick-up/drop — keeps acting on
    // the right talk through drawerTalkIndex. The only paths
    // that clear the search are the ones that promise the full
    // list ("စာရင်းအားလုံး ပြန်ပြပါ" and scroll-to-playing),
    // where a still-filtered list would break the promise.
    private String drawerSearchQuery = "";
    private android.widget.EditText drawerSearchBox = null;
    private java.util.ArrayList<Integer> drawerBaseIndices() {
        java.util.ArrayList<Integer> base = new java.util.ArrayList<Integer>();
        if (drawerQueueOnly) {
            for (String u : queueUrls) {
                // v7.41: line-aware like the v7.38 sites — a v2-spelled
                // in-list queued talk used to miss its index here
                // (talkIndexForUrl compares bare URLs), so a restored
                // drawer search over the queue-only view dropped it.
                int ti = talkIndexForUrl(queueLineUrl(u));
                if (ti >= 0) base.add(ti);
            }
        } else if (drawerFilterMode != 0) {
            base = drawerFilteredIndices();
        } else {
            for (int i = 0; i < talks.size(); i++) base.add(i);
        }
        return base;
    }
    private java.util.ArrayList<Integer> drawerSearchSeq() {
        java.util.ArrayList<Integer> hits = new java.util.ArrayList<Integer>();
        if (drawerSearchQuery == null || drawerSearchQuery.length() == 0) return hits;
        for (Integer i : drawerBaseIndices()) {
            Talk t = talks.get(i);
            // v5.58: space-insensitive title match (Fx.titleMatches)
            // — the plain contains above used to miss a title
            // typed without its spaces, while Main (v1.47) and
            // the library (v1.51) already found it.
            if (t.title != null && Fx.titleMatches(t.title, drawerSearchQuery)) hits.add(i);
        }
        return hits;
    }
    private boolean drawerSearching() {
        return drawerSearchQuery != null && drawerSearchQuery.length() > 0;
    }
    // v10.35: the drawer search box, completing v4.98 -- the empty
    // line reads exactly when a typed query matches nothing in the
    // current view (queue / filter / full: drawerDisplayCount already
    // narrows through drawerSearchSeq). Hidden otherwise, including
    // while not searching and while the list is still loading.
    private void syncDrawerSearchEmpty() {
        // Removed v10.41: Play List drawer search box removed per his v6.61 order (re-added by loop v10.35, reverted here).
    }
    private void rebuildDrawerSearch() {
        // Removed v10.41: Play List drawer search box removed per his v6.61 order (re-added by loop v10.35, reverted here).
    }
    private void clearDrawerSearch() {
        // Setting the box text fires the watcher, which rebuilds
        // + rebinds; the direct rebuild below covers the box
        // being null (pre-wire) or the watcher not firing.
        if (drawerSearchBox != null) drawerSearchBox.setText("");
        drawerSearchQuery = "";
        rebuildDrawerSearch();
        if (adapter != null) adapter.notifyDataSetChanged();
    }
    // v10.35: wire the drawer search box, completing v4.98 -- the
    // stub left the whole search machinery unreachable by typing
    // (only a rotation restore could set a query, with no box to
    // edit or clear it). Chooser wireSearchBox shape, drawer
    // adapted: typing NARROWS the current view (queue / filter /
    // full via drawerBaseIndices, v4.98 promise) and never exits
    // queue-only or a filter view, unlike the chooser; the
    // scroll-to-playing / show-all paths already clear for their
    // full-list promise. Rebinds on keystroke are pour-safe (v3.45
    // hygiene cancels stale row animators, never re-pours).
    private void wireDrawerSearch() {
        // Removed v10.41: Play List drawer search box removed per his v6.61 order (re-added by loop v10.35, reverted here).
    }
    // v10.35: drawer search font steps (v9.97/v9.98 class) -- box
    // follows the chooser search-box step (14/16/20; layout 16sp, so
    // normal font is unchanged), empty line the Downloaded empty-text
    // step (14/15/18; layout 15sp). Called from wireDrawerSearch
    // (first create) and applyFontSizes (create + every return dip),
    // so a Settings font change lands on return too.
    private void sizeDrawerSearchBox() {
        // Removed v10.41: Play List drawer search box removed per his v6.61 order (re-added by loop v10.35, reverted here).
    }

    // v12.05: the caller's moved set -- entries whose relative order
    // changed between the last synced snapshot and the caller's
    // pre-mutation list, via longest common subsequence (LCS) over the
    // entries present in both (deduped, first occurrence): an entry in
    // the LCS kept its relative order, so this call did not move it;
    // every other common entry, plus every entry new to the caller
    // (not in the snapshot), counts as moved. A single drawer verb
    // moves at most one entry, so the LCS is unique except for a pure
    // adjacent swap, where either member may count as moved -- that
    // tie is harmless: when the saved order is unchanged both branches
    // reproduce the caller order exactly, and when it changed it is a
    // genuine two-window conflict (sim1205 R4/R12/T4b). Runs on the
    // main thread per save, O(n*m) on queue-sized lists.
    private java.util.Set<String> movedQueueUrls05(java.util.ArrayList<String> snapshotUrls05,
                                                   java.util.ArrayList<String> callerUrls05) {
        java.util.Set<String> snapSet05 = new java.util.HashSet<String>(snapshotUrls05);
        java.util.Set<String> callerSet05 = new java.util.HashSet<String>(callerUrls05);
        java.util.ArrayList<String> a05 = new java.util.ArrayList<String>();
        java.util.HashSet<String> seenA05 = new java.util.HashSet<String>();
        for (String u05 : snapshotUrls05)
            if (callerSet05.contains(u05) && seenA05.add(u05)) a05.add(u05);
        java.util.ArrayList<String> b05 = new java.util.ArrayList<String>();
        java.util.HashSet<String> seenB05 = new java.util.HashSet<String>();
        for (String u05 : callerUrls05)
            if (snapSet05.contains(u05) && seenB05.add(u05)) b05.add(u05);
        int n05 = a05.size(), m05 = b05.size();
        int[][] dp05 = new int[n05 + 1][m05 + 1];
        for (int i05 = n05 - 1; i05 >= 0; i05--)
            for (int j05 = m05 - 1; j05 >= 0; j05--)
                dp05[i05][j05] = a05.get(i05).equals(b05.get(j05))
                        ? dp05[i05 + 1][j05 + 1] + 1
                        : Math.max(dp05[i05 + 1][j05], dp05[i05][j05 + 1]);
        java.util.Set<String> lcs05 = new java.util.HashSet<String>();
        int i05 = 0, j05 = 0;
        while (i05 < n05 && j05 < m05) {
            if (a05.get(i05).equals(b05.get(j05))) { lcs05.add(a05.get(i05)); i05++; j05++; }
            else if (dp05[i05 + 1][j05] >= dp05[i05][j05 + 1]) i05++;
            else j05++;
        }
        java.util.Set<String> moved05 = new java.util.HashSet<String>();
        for (String u05 : callerUrls05) if (!snapSet05.contains(u05)) moved05.add(u05);
        for (String u05 : b05) if (!lcs05.contains(u05)) moved05.add(u05);
        return moved05;
    }

    // v12.03: keep02 is the set of URLs this call just added (the full-list
    // long-press append, or the handover's whole carried list) -- entries not
    // in the saved read yet that must survive the stale drop below; every
    // other caller passes null (remove / move / move-to-end / play-next /
    // clear / prune / consume add nothing new, so nothing of theirs is
    // kept). adopt03 is false ONLY for the handover -- its carried list
    // REPLACES the new sayadaw's key, so the adoption below must not merge
    // the new key's old entries back in; every other caller passes true.
    // The signature itself changed (no no-arg overload) so a future
    // edit that adds a queue entry must choose a keep explicitly instead
    // of silently losing the addition to the drop.
    private void saveQueue(java.util.Set<String> keep02, boolean adopt03) {
        // v12.05: capture the caller's pre-mutation order + moved set
        // BEFORE the drop/adopt below mutates queueUrls -- the LCS
        // compares the last synced snapshot against what THIS call is
        // about to save, so the capture must precede any mutation.
        // Skipped for the handover (adopt03 false): its carried list
        // replaces the key, and no order adoption runs there.
        java.util.Set<String> moved05 = null;
        java.util.Set<String> callerSet05 = null;
        if (adopt03 && sayadawName != null && sayadawName.length() > 0) {
            java.util.ArrayList<String> snapUrls05 = new java.util.ArrayList<String>();
            for (String l05 : lastQueueUrls03) {
                String u05 = queueLineUrl(l05);
                if (u05.length() > 0) snapUrls05.add(u05);
            }
            java.util.ArrayList<String> callerUrls05 = new java.util.ArrayList<String>();
            callerSet05 = new java.util.HashSet<String>();
            for (String l05 : queueUrls) {
                String u05 = queueLineUrl(l05);
                if (u05.length() > 0) { callerUrls05.add(u05); callerSet05.add(u05); }
            }
            moved05 = movedQueueUrls05(snapUrls05, callerUrls05);
        }
        // v12.02: re-read prefs and drop entries the other window already
        // popped -- the v12.00 audit's slice-B NOTE-2, multi-window: two
        // Room instances can be alive at once (standard launchMode, no
        // singleTop), and in split-screen both are resumed, so the service
        // defers its advance to the top Room (v7.74) while the other
        // Room's in-memory queueUrls goes stale; any drawer edit there
        // called saveQueue() and wrote the stale list straight back,
        // resurrecting the popped head (the v11.98 Talks funnel /
        // v12.00 library-dialog class, now in the Room funnel). The drop
        // mirrors the v11.98 rule: an entry absent from the fresh saved
        // read is gone -- popped, cleared or pruned elsewhere -- unless
        // this call just added it (keep02). Runs even when the saved
        // read is empty (a cleared queue must not resurrect wholesale);
        // compared by URL (queueLineUrl), so a foreign v2 line survives
        // under either spelling, and an unparseable line is kept, never
        // dropped on a guess. Skipped when there is no sayadaw key to
        // read (Prefs.saveQueue is a no-op there too, so the in-memory
        // list must stay untouched); a read failure keeps today's
        // behaviour. peekNextIndex is unaffected: its firstMatch is an
        // index into talks, not queueUrls, and its scan runs over a copy
        // before this save.
        // v12.03: ADOPT entries added elsewhere -- the v12.02 note's
        // rotation corner, audit A1: a Room rotated with empty talks
        // (or split-screen with no sync since the other window's add)
        // used to persist its stale list and wipe the addition. The
        // adoption below merges saved entries absent from memory back
        // in, EXCEPT the removed set -- URLs in the lastQueueUrls03
        // snapshot but no longer in memory, i.e. entries THIS Room
        // removed since the last save, whose removal has not persisted
        // yet so the fresh saved read still holds them (adopting them
        // would undo the caller's own removal -- the reason v12.02
        // said a merge was unsafe). A removal older than the last save
        // already landed in the store, so it cannot be adopted back --
        // the set needs no longer lifetime than this diff, and a re-add
        // elsewhere AFTER the removal persisted is a deliberate new add
        // that IS adopted (last writer wins per entry). A clear-all
        // wipes only what this Room knew: a concurrent add elsewhere
        // survives, the same rule as any other entry this Room had not
        // seen (sim1203 case 8).
        if (sayadawName != null && sayadawName.length() > 0) {
            try {
                String saved02 = Prefs.savedQueue(this, sayadawName);
                java.util.Set<String> savedUrls02 = new java.util.HashSet<String>();
                java.util.ArrayList<String> savedLines03 = new java.util.ArrayList<String>();
                if (saved02 != null && saved02.length() > 0) {
                    for (String line02 : saved02.split("\n")) {
                        String u02 = queueLineUrl(line02);
                        if (u02.length() > 0) { savedUrls02.add(u02); savedLines03.add(line02); }
                    }
                }
                java.util.Set<String> removed03 = new java.util.HashSet<String>();
                for (String line03 : lastQueueUrls03) {
                    String u03 = queueLineUrl(line03);
                    if (u03.length() > 0 && queueLineIndexForUrl(u03) < 0) removed03.add(u03);
                }
                for (int i02 = queueUrls.size() - 1; i02 >= 0; i02--) {
                    String u02 = queueLineUrl(queueUrls.get(i02));
                    if (u02.length() == 0) continue;
                    if (keep02 != null && keep02.contains(u02)) continue;
                    if (!savedUrls02.contains(u02)) queueUrls.remove(i02);
                }
                if (adopt03) {
                    // When talks are loaded the adoption runs through
                    // filterSavedQueue (the sync's own keep-rule: in-list,
                    // or a foreign v2 line), so a stale line the sync
                    // dropped is never re-adopted; when talks are EMPTY
                    // the filter would drop every in-list line, so the
                    // raw saved read is adopted instead -- a stale line
                    // may ride along transiently, but the load-completion
                    // sync drops it from memory and the next loaded save
                    // sheds it from the store (sim1203 case 10). An
                    // adopted entry slots into its saved-order position
                    // (before the first in-memory entry that follows it
                    // in the saved order), never at the tail -- a
                    // play-next insert elsewhere keeps its "next"
                    // meaning. The ORDER adoption below (v12.05) then
                    // re-sorts this Room's own unmoved entries to the
                    // saved order; only entries this call moved stay
                    // pinned at the caller's positions.
                    java.util.ArrayList<String> adoptable03;
                    if (talks.isEmpty()) adoptable03 = savedLines03;
                    else adoptable03 = filterSavedQueue(saved02);
                    java.util.Map<String, Integer> order03 = new java.util.HashMap<String, Integer>();
                    for (int i03 = 0; i03 < adoptable03.size(); i03++) {
                        String u03 = queueLineUrl(adoptable03.get(i03));
                        if (u03.length() > 0 && !order03.containsKey(u03)) order03.put(u03, Integer.valueOf(i03));
                    }
                    for (String line03 : adoptable03) {
                        String u03 = queueLineUrl(line03);
                        if (u03.length() == 0) continue;
                        if (queueLineIndexForUrl(u03) >= 0) continue;
                        if (removed03.contains(u03)) continue;
                        int at03 = queueUrls.size();
                        Integer pos03 = order03.get(u03);
                        if (pos03 != null) {
                            for (int j03 = 0; j03 < queueUrls.size(); j03++) {
                                Integer pj03 = order03.get(queueLineUrl(queueUrls.get(j03)));
                                if (pj03 != null && pj03.intValue() > pos03.intValue()) { at03 = j03; break; }
                            }
                        }
                        queueUrls.add(at03, line03);
                    }
                }
                // v12.05: ORDER ADOPTION -- the v12.03 note's accepted
                // limit, now closed: a Room drawer reorder made in the
                // other window inside the <1s window used to be
                // reverted by this window's stale order written back.
                // Entries this call did NOT move (not in moved05, and
                // present in the caller's list at entry -- adopted /
                // carried entries are pinned at the position the adopt
                // step just gave them, which already is their saved
                // position) are re-sorted to the fresh saved order;
                // entries this call moved, just added (keeps), or with
                // no saved rank (unparseable lines) stay pinned at
                // their current slots. Runs only when adopt03 (the
                // handover skips it: its carried list replaces the
                // key). Steady states are unchanged -- when the saved
                // order equals this Room's, the sort is a no-op --
                // and a no-edit save (e.g. the onPause catch-all) now
                // adopts the other window's reorder instead of
                // reverting it (sim1205 R1). In-place, so the drawer
                // adapter's notifyDataSetChanged after this call (every
                // drawer verb site) rebinds the merged order.
                if (adopt03 && moved05 != null) {
                    java.util.Map<String, Integer> rank05 = new java.util.HashMap<String, Integer>();
                    for (int i05 = 0; i05 < savedLines03.size(); i05++) {
                        String u05 = queueLineUrl(savedLines03.get(i05));
                        if (u05.length() > 0 && !rank05.containsKey(u05)) rank05.put(u05, Integer.valueOf(i05));
                    }
                    // callerSet05 is the PRE-mutation capture from the
                    // top of saveQueue: an entry counts as
                    // caller-owned only if it was in the list this
                    // call started from, so adopted entries (absent
                    // from it) are pinned at their adopted position.
                    java.util.ArrayList<String> merged05 = new java.util.ArrayList<String>();
                    for (int i05 = 0; i05 < queueUrls.size(); i05++) merged05.add(null);
                    java.util.ArrayList<String> rest05 = new java.util.ArrayList<String>();
                    for (int i05 = 0; i05 < queueUrls.size(); i05++) {
                        String line05 = queueUrls.get(i05);
                        String u05 = queueLineUrl(line05);
                        if (u05.length() == 0 || !callerSet05.contains(u05) || moved05.contains(u05) || !rank05.containsKey(u05)) merged05.set(i05, line05);
                        else rest05.add(line05);
                    }
                    java.util.Collections.sort(rest05, new java.util.Comparator<String>() {
                        @Override public int compare(String a, String b) {
                            Integer ra = rank05.get(queueLineUrl(a));
                            Integer rb = rank05.get(queueLineUrl(b));
                            int ia = ra == null ? Integer.MAX_VALUE : ra.intValue();
                            int ib = rb == null ? Integer.MAX_VALUE : rb.intValue();
                            return ia < ib ? -1 : (ia > ib ? 1 : 0);
                        }
                    });
                    int r05 = 0;
                    for (int i05 = 0; i05 < merged05.size(); i05++)
                        if (merged05.get(i05) == null) merged05.set(i05, rest05.get(r05++));
                    queueUrls.clear();
                    queueUrls.addAll(merged05);
                }
            } catch (Exception ignored) {}
        }
        // v13.56 (Room handover torn-carry fix): when a handover
        // armed clearQueueSayadawOnSave11, the persist below also
        // clears the sayadaw being left — in the SAME Editor
        // transaction as the new key's write, so a process death
        // can no longer land between the clear and the save and
        // lose the queue from both keys. A death before the apply
        // leaves the pre-handover state (old key intact, new key
        // untouched — the handover simply re-runs on the next Next
        // tap). Consumed here, so no other saveQueue call can
        // inherit it; this persist always runs, so the field
        // cannot leak past this call.
        if (clearQueueSayadawOnSave11 != null) {
            Prefs.carryQueueHandover11(this, sayadawName, queueUrls,
                    clearQueueSayadawOnSave11);
            clearQueueSayadawOnSave11 = null;
        } else {
            Prefs.saveQueue(this, sayadawName, queueUrls);
        }
        lastQueueUrls03 = new java.util.ArrayList<String>(queueUrls);
        // v10.36: drawer search empty-line sync on a live queue change -- every
        // drawer queue verb funnels through here and already rebinds the adapter,
        // but the no-hit line re-decided only on drawer open, list reload, or
        // keystroke, so a queue edit under an active search left it stale in
        // both directions (last hit removed: blank list, no line; matching talk
        // queued: stale line over fresh rows) until the next reopen/reload.
        // One funnel line covers add/remove/queue-all/clear/reorder/pick-up/drop/
        // consume/prune; the handover + switch paths keep their load-completion
        // sync, and a rebuild with no hit change paints the identical line.
        syncDrawerSearchEmpty();
        // v10.05: paused-card queue-count sync — the card's ◈ waiting count is read
        // from this persisted key at rebuild time, and rebuilds run only every 15s while
        // playing (never while paused), so a drawer queue add/remove left a paused card
        // showing the old count until the next tap (same class as the v10.02 fav-star /
        // v10.03 Talks-fav syncs). Rebuild at once from the one funnel every drawer
        // queue edit writes through — add, remove, queue-all, clear, reorder, pick-up/drop
        // — instead of a refresh at each dialog site; refreshNotification is a no-op with
        // no track, and a rebuild with an unchanged count paints the identical card.
        if (service != null) service.refreshNotification();
        else if (AudioService.liveSession) {
            // v10.28: unbound leg of the v10.05 queue-count sync — same two windows
            // as the v10.27 shuffle leg (before onServiceConnected fires, after
            // service death), so a drawer queue add/remove in a fast-opened Room
            // left a PAUSED card showing the old ◈ count until the next tap. Unlike
            // the global shuffle glyph, the count is NOT global: the card reads the
            // persisted queue keyed by the sounding sayadaw (lastName) for Room
            // sessions and the shared library key for library sessions, so the
            // no-bind ACTION_REFRESH_NOTIFICATION intent (v10.03 shape,
            // liveSession-guarded, never starts a dead service) fires only when
            // this Room IS the sounding session — lastKind room and this
            // sayadawName equal to prefs lastName, read fresh. An edit in any
            // other sayadaw's Room, or while a library session sounds, changes no
            // card number and must not re-post the card. The handler stopSelfs
            // when trackless and a rebuild with no count change paints the
            // identical card. Bound path byte-identical.
            try {
                android.content.SharedPreferences rsp =
                        getSharedPreferences("dhamma", MODE_PRIVATE);
                if ("room".equals(rsp.getString("lastKind", ""))
                        && sayadawName != null
                        && sayadawName.equals(rsp.getString("lastName", ""))) {
                    android.content.Intent rsv = new android.content.Intent(RoomActivity.this, AudioService.class);
                    rsv.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                    startService(rsv);
                }
            } catch (Exception ignored) {}
        }
    }

    // v7.06: ONE queue filter shared by restore + sync -- the 15:48 v2
    // design requires the two filters never to disagree, and they were
    // duplicated verbatim (same loop, same dedupe, same list match).
    // v7.07: the filter now parses each line through QueueEntry (the
    // v2 record type) but STILL applies the v1 keep-rule: a line
    // survives only if its URL is in the loaded talk list, deduped
    // by URL, order preserved. For every line today's writer can
    // produce (bare v1 URLs, no tabs) this is identical to the v7.06
    // loop; a tab-bearing line -- unproducible today -- now resolves
    // by its URL instead of dropping outright, and a foreign v2 line
    // still drops exactly like a stale v1 URL until the (d) widening
    // lands together with the (b) handover. Never widen this filter
    // alone: a restored foreign row with no handover would play
    // nothing on tap.
    // v7.08: (d) widening + (b) handover helpers, per the 15:48 v2
    // design contract. The filter keeps a v1 line only when its URL
    // is in the loaded list (unchanged), but now also keeps a v2
    // line whose sayadaw field is non-empty even when its URL is
    // not in this list -- that is the cross-sayadaw queue. A kept
    // line for THIS sayadaw normalises back to its bare URL (its
    // title lives in the list, so no info is lost, and every
    // existing indexOf/contains site keeps working verbatim);
    // a kept foreign line stays a whole v2 line, and only the
    // v7.08 helpers below ever resolve it. Dedupe is by URL, not
    // by line, so a v1 and a v2 spelling of one talk never double
    // up. For every line today's writer can produce (bare v1 URLs)
    // this is byte-identical to v7.07.
    private java.util.ArrayList<String> filterSavedQueue(String saved) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        java.util.ArrayList<String> seen = new java.util.ArrayList<String>();
        if (saved == null || saved.length() == 0) return out;
        for (String line : saved.split("\n")) {
            QueueEntry e = QueueEntry.parse(line);
            if (e == null || seen.contains(e.url)) continue;
            seen.add(e.url);
            boolean inList = false;
            for (int i = 0; i < talks.size(); i++)
                if (talks.get(i).url.equals(e.url)) { inList = true; break; }
            if (inList) out.add(e.url);
            // v11.75: a stale v2 line for THIS sayadaw is dropped —
            // the display rule (drawerQueueDisplayLines) and
            // firstWaitingQueueEntry already skip it, so keeping
            // it here left a ghost entry restore/sync could never
            // shed (invisible in the drawer, skipped by play-next,
            // re-persisted by saveQueue). Foreign v2 lines stay.
            else if (e.isV2() && (sayadawName == null
                    || !e.sayadaw.equals(sayadawName))) out.add(line);
        }
        return out;
    }
    // v7.08: queue-line identity helpers. queueUrls now holds v2
    // lines as well as bare URLs, so any site that must resolve a
    // line to a talk parses it instead of assuming line == URL.
    private static String queueLineUrl(String line) {
        QueueEntry e = QueueEntry.parse(line);
        return e == null ? "" : e.url;
    }
    private int queueLineIndexForUrl(String url) {
        for (int i = 0; i < queueUrls.size(); i++)
            if (queueLineUrl(queueUrls.get(i)).equals(url)) return i;
        return -1;
    }

    // v13.52 (audit-1373 F2 / audit-1374 F-F): the v4.43 ahead-drop,
    // with a restored failed head preserved. A background failure
    // restores its queue head at position 0 (AudioService
    // restorePendingQueueHead14); the plain subList(0, qi + 1) clear
    // then deleted that restored head without it ever being played
    // or retried whenever the user played a talk behind it. The
    // failed line is excluded from the clear — everything ahead of
    // it and behind it up to qi still drops, so it ends up at queue
    // front, still skipped as sounding by peek and still counted as
    // sounding by the header, exactly like a service-restart resumed
    // talk (v5.85). When the tapped talk IS the failed talk (a retry
    // tap, f == qi) the line is likewise kept: the retry may fail
    // again, and a kept head is the sounding state, not a leak.
    // NOTE: this guard only sees a LIVE failure (failedUrl needs the
    // service flag); a head whose failure was superseded by a later
    // successful play carries no marker after the restore, so the
    // already-holding call site below is unreachable-with-live-error
    // today (its :11180 guard requires !hasTrackError) and its guard
    // call is symmetry/defense only.
    private void dropQueueAheadUpTo(int qi) {
        if (qi < 0 || queueUrls.isEmpty()) return;
        int end = Math.min(qi + 1, queueUrls.size());
        int fi = -1;
        for (int i = 0; i < end; i++) {
            if (isFailedUrl(queueLineUrl(queueUrls.get(i)))) { fi = i; break; }
        }
        if (fi < 0) { queueUrls.subList(0, end).clear(); return; }
        queueUrls.subList(fi + 1, end).clear();
        queueUrls.subList(0, fi).clear();
    }
    // v11.81: no-track Play starts the talk ON SCREEN, not talk 0 — after a
    // notification/sleep stop the poll clears currentIndex only on its next tick, so
    // until then the Room still shows the stopped talk; starting talk 0 was a
    // wrong-talk tap in that window (and on return before the first tick). A valid
    // index plays from its saved spot on a plain Play tap (the error-retry rule) or
    // from 0 on a restart long-press; no index yet keeps the old first-talk start
    // from 0. Empty list keeps the "no talks" toast.
    // v11.90: the post-tick window the v11.81 fix never covered — one 500ms tick
    // after a notification/sleep stop the poll clears currentIndex to -1, so the
    // same Play tap fell through to playIndex(0) and started the FIRST talk from 0,
    // discarding the stopped talk entirely. With no valid index, fall back to the
    // stopped talk itself (the saved last-talk URL, gated on this Room's page so
    // another sayadaw's last talk never anchors here) when it is in this list;
    // only when it is not found does the first talk play. Restart (-1, see
    // playIndex) still starts from 0; a plain tap resumes the saved spot.
    private void playShownOrFirst81(boolean restart) {
        if (talks.isEmpty()) {
            Toast.makeText(this, Lang.t(this, "no_talk_yet"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentIndex >= 0 && currentIndex < talks.size())
            playIndex(currentIndex, restart ? -1 : savedPosFor(talks.get(currentIndex).url));
        else {
            int anchor = -1;
            try {
                android.content.SharedPreferences pp = getSharedPreferences("dhamma", MODE_PRIVATE);
                String lastPage = pp.getString("lastPage", "");
                String lastUrl = pp.getString("lastUrl", "");
                if (lastUrl.length() > 0 && lastPage.equals(pageUrl)) anchor = talkIndexForUrl(lastUrl);
            } catch (Exception ignored) {}
            if (anchor >= 0)
                playIndex(anchor, restart ? -1 : savedPosFor(talks.get(anchor).url));
            else
                playIndex(0, restart ? -1 : 0);
        }
    }

    private String currentTalkUrl() {
        return (currentIndex >= 0 && currentIndex < talks.size())
                ? talks.get(currentIndex).url : null;
    }
    // v12.64: Bookmarks — premium backlog #1 (user order 2026-11-06):
    // mark an important minute inside a long talk, tap the mark to
    // jump straight back. The sounding talk's URL is the service
    // URL (v12.71: in the pre-tick window after a background
    // advance the service already sounds the next head while
    // currentIndex still names the finished talk, so the indexed
    // talk would mark the wrong minute), with the indexed list
    // URL as fallback when nothing sounds yet; Prefs mirrors
    // every mark under both forms, so streaming and downloaded
    // play share one mark list.
    private String bookmarkTalkUrl() {
        // v12.71: sounding talk wins (pre-tick window); else indexed.
        // v13.59 (audit-1374 F-K): a failed stream's URL is not the
        // sounding talk — onError never clears currentUrl, so after
        // a failure the service URL still names the dead stream and
        // the wave pins / add / list would follow it. With a live
        // error the indexed talk wins instead (the talk on screen;
        // in the common case it IS the failed talk, so the answer
        // is unchanged — the fix only bites in the pre-tick window
        // where the service already advanced past currentIndex,
        // and when nothing is indexed, where it now returns null
        // instead of the dead URL).
        if (service != null && service.hasTrackError()) return currentTalkUrl();
        String svc = service == null ? null : service.getCurrentUrl();
        if (svc != null && svc.length() > 0) return svc;
        return currentTalkUrl();
    }

    // Refresh the wave's gold pins for the sounding talk. Called on
    // every fresh track (poll), every add and every delete; the
    // view only redraws when the list actually changes.
    private void refreshBookmarkTicks() {
        if (seekBar == null) return;
        String url = bookmarkTalkUrl();
        if (url == null || url.length() == 0) { seekBar.setBookmarks(null); return; }
        seekBar.setBookmarks(Prefs.bookmarkListBoth(this, url));
    }

    private void onBookmarkAdd() {
        String url = bookmarkTalkUrl();
        int dur = service == null ? 0 : service.getDuration();
        int pos = service == null ? 0 : service.getEffectivePosition();
        if (url == null || url.length() == 0 || dur <= 0 || pos <= 0) {
            android.widget.Toast.makeText(this, Lang.t(this, "no_talk_yet"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (Prefs.bookmarkAddBoth(this, url, pos)) {
            refreshBookmarkTicks();
            android.widget.Toast.makeText(this,
                    "🔖 " + formatTime(pos) + " " + Lang.t(this, "bm_added"),
                    android.widget.Toast.LENGTH_SHORT).show();
        } else {
            android.widget.Toast.makeText(this, Lang.t(this, "bm_exists"),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void showBookmarkList() {
        final String url = bookmarkTalkUrl();
        if (url == null || url.length() == 0) {
            android.widget.Toast.makeText(this, Lang.t(this, "no_talk_yet"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final java.util.ArrayList<Integer> marks =
                new java.util.ArrayList<Integer>(Prefs.bookmarkListBoth(this, url));
        if (marks.isEmpty()) {
            android.widget.Toast.makeText(this, Lang.t(this, "bm_none"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final android.app.AlertDialog[] dlgBox = new android.app.AlertDialog[1];
        final android.widget.BaseAdapter bmAdapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return marks.size(); }
            @Override public Object getItem(int p) { return marks.get(p); }
            @Override public long getItemId(int p) { return marks.get(p); }
            @Override public android.view.View getView(final int position,
                    android.view.View convertView, android.view.ViewGroup parent) {
                android.widget.LinearLayout row = new android.widget.LinearLayout(RoomActivity.this);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                float d = getResources().getDisplayMetrics().density;
                int pad = (int) (12 * d);
                row.setPadding(pad, (int) (10 * d), pad, (int) (10 * d));
                android.widget.TextView tv = new android.widget.TextView(RoomActivity.this);
                tv.setText("🔖 " + formatTime(marks.get(position)));
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(16);
                row.addView(tv, new android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                android.widget.Button del = new android.widget.Button(RoomActivity.this);
                del.setText("✕");
                del.setTextColor(0xFFFFFFFF);
                del.setBackgroundResource(R.drawable.glass_btn_dark);
                del.setMinWidth(0);
                del.setFocusable(false);
                del.setFocusableInTouchMode(false);
                del.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override public void onClick(android.view.View v) {
                        int ms = marks.get(position);
                        Prefs.bookmarkRemoveBoth(RoomActivity.this, url, ms);
                        marks.remove(position);
                        notifyDataSetChanged();
                        refreshBookmarkTicks();
                        android.widget.Toast.makeText(RoomActivity.this,
                                Lang.t(RoomActivity.this, "bm_deleted"),
                                android.widget.Toast.LENGTH_SHORT).show();
                        if (marks.isEmpty() && dlgBox[0] != null) dlgBox[0].dismiss();
                    }
                });
                row.addView(del, new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
                return row;
            }
        };
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(RoomActivity.this)
                .setTitle(Lang.t(this, "bm_title"))
                .setAdapter(bmAdapter, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        int ms = marks.get(which);
                        // v12.88: tap-time branch (sim1308 A/B/C) — the old
                        // handler sought whenever the service object existed,
                        // so an unbound tap died silently, a trackless tap
                        // lied on the clock, and a tap after an advance
                        // sought the NEW talk at the OLD talk's minute.
                        // Sounding talk -> seekTo (today's path); a mark
                        // whose talk is in the loaded list -> playIndex
                        // (gate-aware, offline-aware, pre-bind-queued);
                        // otherwise the seekTo fallback for a foreign
                        // sounding talk; unbound + no row -> dismiss only.
                        // v13.41: RoomActivity clock-line guards (M9/F5 class, same as
                        // Main v13.32, Settings v13.34, Talks v13.36, Downloaded
                        // v13.37, DlAdapter v13.38, Room onCreate v13.39, nowPlaying
                        // v13.40) — every bare elapsedText/totalText deref in the
                        // file is now null-guarded: a renamed or missing roomElapsed
                        // / roomTotal id leaves its clock unwritten instead of killing
                        // the Room on the 500ms poll tick, a bookmark tap, a drag, or
                        // a talk change. All ids exist in activity_room.xml today, so
                        // no live behaviour changes; the already-safe sites
                        // (clockToggle/clockReset try/catch, applyTheme sizes, clock
                        // click/long-click wiring) and the null-safe Fx.fadeIn call
                        // are untouched. The seekBar bare derefs in the same blocks
                        // are a separate family (next slice), not this patch.
                        if (isCurrentTalk(url)) {
                            service.seekTo(ms);
                            if (seekBar != null) seekBar.setProgress(ms);
                            if (elapsedText != null) elapsedText.setText(formatTime(ms));
                        } else {
                            int idx = talkIndexForUrl(url);
                            if (idx >= 0) playIndex(idx, ms);
                            else if (service != null) {
                                service.seekTo(ms);
                                if (seekBar != null) seekBar.setProgress(ms);
                                if (elapsedText != null) elapsedText.setText(formatTime(ms));
                            }
                        }
                        d.dismiss();
                    }
                })
                .create();
        dlgBox[0] = dlg;
        dlg.show();
    }

    // v12.65: Personal Playlist (premium backlog #2) — the sounding
    // talk's title: the list row when the sounding URL is the
    // current list talk, else the service's sounding title (a
    // foreign head sounding in this Room has no row here). The
    // owner is playlistOwnerFor below, not always this Room's
    // sayadaw (v12.71: a foreign head's owner is its own queue
    // line; a library file's owner is its dlSayadaw record).
    private String playlistTalkTitle(String url) {
        if (url != null && currentIndex >= 0 && currentIndex < talks.size()
                && url.equals(talks.get(currentIndex).url)) return talks.get(currentIndex).title;
        return service == null ? "" : service.getCurrentTitle();
    }

    // v12.71: the sounding talk's owner, resolved from the talk
    // itself — a foreign head sounding in this Room is owned by
    // its v2 queue line (the queue this Room is walking), an
    // in-list talk by this Room's sayadaw, a downloaded file by
    // its dlSayadaw record; sayadawName only as the last
    // fallback. The sounding URL is stream-mapped first, so a
    // downloaded file (path form) still matches its queue line
    // and list row (stream form). A mis-attributed item used to
    // open the wrong Room and file its remaining queue under
    // the wrong sayadaw's saved-queue key.
    private String playlistOwnerFor(String url) {
        if (url != null && url.length() > 0) {
            String stream = Prefs.streamUrlFor(this, url);
            if (stream != null && stream.length() > 0) {
                for (String line : queueUrls) {
                    QueueEntry e = QueueEntry.parse(line);
                    if (e != null && e.isV2() && stream.equals(e.url))
                        return e.sayadaw;
                }
                for (Talk t : talks) {
                    if (stream.equals(t.url)) return sayadawName;
                }
                // v12.78 (sim1289): the anchors name a sounding
                // foreign head the queue line no longer can — a
                // background foreign-head advance pops the head
                // from the saved queue and clears the old key, so
                // on return the queue scan above finds nothing and
                // the in-list scan misses too, and the stream-form
                // head used to fall through to this Room's (old)
                // sayadaw and strand the playlist item in the
                // wrong Room (talk_not_in_list on play, never
                // playable from the list). The service moves
                // lastUrl/lastName to the head's sayadaw on every
                // foreign advance, and every lastUrl writer either
                // writes lastName with it or plays an in-list talk
                // of lastName's sayadaw, so the stored pair always
                // agrees on the sounding talk's owner — and the
                // equality guard means a stale anchor (a library
                // session's old lastUrl) never fires. Queue and
                // in-list keep precedence; the path branch below
                // still owns downloaded files whose anchors a
                // library session never wrote.
                android.content.SharedPreferences sp78 =
                        getSharedPreferences("dhamma", MODE_PRIVATE);
                String lastUrl78 = sp78.getString("lastUrl", "");
                String lastName78 = sp78.getString("lastName", "");
                if (stream.equals(lastUrl78) && lastName78.length() > 0) return lastName78;
                if (url.startsWith("/")) {
                    String owner = Prefs.sayadawForTalk(this, "lib", url, "");
                    if (owner != null && owner.length() > 0) return owner;
                }
            }
        }
        return sayadawName;
    }

    private void showPlaylistAddDialog() {
        final String url = bookmarkTalkUrl();
        if (url == null || url.length() == 0
                || sayadawName == null || sayadawName.length() == 0) {
            android.widget.Toast.makeText(this, Lang.t(this, "no_talk_yet"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final String title = playlistTalkTitle(url);
        final String sd = playlistOwnerFor(url);
        final java.util.List<String> names = Prefs.playlistNames(this);
        final String[] items = new String[names.size() + 1];
        for (int i = 0; i < names.size(); i++) items[i] = names.get(i);
        items[names.size()] = Lang.t(this, "pl_new");
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "pl_choose"))
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (which == names.size()) {
                            showNewPlaylistDialog(url, title, sd);
                            return;
                        }
                        int r = Prefs.playlistAdd(RoomActivity.this,
                                names.get(which), url, sd, title);
                        // v12.80: -2 is a full list, -1 is a real
                        // failure — neither is a play failure
                        // (sim1293 B), so neither toasts pl_no_play.
                        android.widget.Toast.makeText(RoomActivity.this,
                                Lang.t(RoomActivity.this, r == 0 ? "pl_added"
                                        : (r == 1 ? "pl_exists"
                                        : (r == -2 ? "pl_full" : "pl_no_create"))),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void showNewPlaylistDialog(final String url, final String title, final String sd) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint(Lang.t(this, "pl_name_hint"));
        et.setSingleLine(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "pl_new"))
                .setView(et)
                .setPositiveButton(Lang.t(this, "pl_create"),
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        String name = et.getText().toString().trim();
                        if (name.length() == 0) {
                            android.widget.Toast.makeText(RoomActivity.this,
                                    Lang.t(RoomActivity.this, "pl_name_hint"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        // Cap to the store's name length; create is
                        // a no-op for an existing name, so a repeat
                        // name just adds to that list.
                        if (name.length() > 40) name = name.substring(0, 40);
                        // v12.80: check the create result (sim1293
                        // B2) — at the 20-list cap a new name used
                        // to fall through to the add and toast a
                        // play failure. A repeat name still adds
                        // to the existing list.
                        if (!Prefs.playlistCreate(RoomActivity.this, name)) {
                            java.util.List<String> held =
                                    Prefs.playlistNames(RoomActivity.this);
                            if (!held.contains(name)) {
                                android.widget.Toast.makeText(RoomActivity.this,
                                        Lang.t(RoomActivity.this,
                                                held.size() >= 20 ? "pl_limit" : "pl_no_create"),
                                        android.widget.Toast.LENGTH_SHORT).show();
                                return;
                            }
                        }
                        int r = Prefs.playlistAdd(RoomActivity.this, name, url, sd, title);
                        android.widget.Toast.makeText(RoomActivity.this,
                                Lang.t(RoomActivity.this, r == 0 ? "pl_added"
                                        : (r == 1 ? "pl_exists"
                                        : (r == -2 ? "pl_full" : "pl_no_create"))),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(Lang.t(this, "btn_no_clear"), null)
                .show();
    }
    // v7.71: scan-proven sounding index for the drawer row bind,
    // cached — the last unpatched reader in the v7.58
    // background-advance family. peekNextIndex and
    // firstWaitingQueueEntry got their own scan adopts in v7.69,
    // but getView's in-list bind still identified the playing row
    // by talkIdx == currentIndex alone. In the pre-poll-tick
    // window after a background auto-advance, currentIndex still
    // names the FINISHED talk, so rows bound in that window (a
    // drawer open or a scroll landing in it) marked the finished
    // row ⏸ + gold and the sounding row as an ordinary row —
    // and the poll never rebinds rows (it only moves the pulse),
    // so the wrong marks lived until the resync tick's rebind.
    // A full scan per row bind would be O(n) filesystem checks
    // per row (localPathFor, offline form), so the result is
    // cached under a key of everything that can change it: the
    // service URL (a track change), currentIndex (the poll
    // resync), and the URL sitting at currentIndex (a list
    // reload under a fixed index). Scroll rebinds with no change
    // hit the cache and pay nothing; a key change rescans once
    // and every later bind in the pass reuses it. A scan miss
    // keeps the old behaviour exactly (currentIndex), and the
    // normal path is a no-op (scan == index). Read-only: the
    // cache never moves currentIndex — the poll resync and the
    // gesture adopts still own that.
    private int soundingIdxCache = -1;
    private String soundingIdxKey = null;
    private int soundingIndexForBind() {
        // v13.59 (audit-1374 F-K): a failed stream's URL is not the
        // sounding talk — onError never clears currentUrl, so the
        // scan below would name the dead stream's row as sounding
        // (pulse + gold). With a live error the indexed talk wins
        // instead (the talk on screen; in the common case it IS the
        // failed talk, so the answer is unchanged — the fix only
        // bites in the pre-tick window where the service already
        // advanced past currentIndex, and when nothing is indexed,
        // where it returns -1, the bound-scan-miss rule, instead
        // of the dead row). Before the cache lookup on purpose:
        // the key carries no error flag, so a cached pre-failure
        // index would otherwise survive the failure. This return
        // writes nothing, so when the error clears the stale key
        // either still matches (same talk retried — the cached
        // index is right) or differs (the index moved during the
        // error — a fresh scan runs).
        if (service != null && service.hasTrackError())
            return (currentIndex >= 0 && talks != null
                    && currentIndex < talks.size()) ? currentIndex : -1;
        String svc = service == null ? null : service.getCurrentUrl();
        String idxUrl = (currentIndex >= 0 && talks != null
                && currentIndex < talks.size())
                ? talks.get(currentIndex).url : "-";
        // v12.40: the live flags join the key, so an unbound stop
        // invalidates a cached stale index (the old key never
        // changed on a stop while unbound — same svc/index/url).
        String key = (svc == null ? "" : svc) + "|" + currentIndex + "|" + idxUrl
                + "|" + (AudioService.liveSession ? "1" : "0")
                + (AudioService.liveHasTrack ? "1" : "0");
        if (key.equals(soundingIdxKey)) return soundingIdxCache;
        soundingIdxKey = key;
        int found = -1;
        if (service != null && talks != null) {
            for (int i = 0; i < talks.size(); i++)
                if (isCurrentTalk(talks.get(i).url)) { found = i; break; }
        }
        // v12.40: a scan miss tells the truth in all four states.
        // The old fallback returned currentIndex whenever NOT
        // (bound + hasTrack), so a bound-but-trackless stop and an
        // unbound dead session both kept marking the stopped talk
        // ⏸ + gold: after a rotation restore the title line is a
        // COUNT line, and the poll's v1.48/v1.62 stop branch only
        // clears currentIndex behind a ▶/⏸/buffering title guard,
        // so the clear never fired and every rebind re-marked the
        // stopped row (losing its ◈ number and ✓/dim) while the
        // button already read ▶. Bound, a scan miss now means
        // nothing sounds (-1 — v11.90 already did this for a
        // live-track miss); unbound, currentIndex stays the best
        // guess ONLY while a live session still holds a track (the
        // bind-window case — onServiceConnected corrects it within
        // a tick of binding), else -1. currentIndex itself is
        // untouched: it stays the stopped-talk anchor for Play
        // (v11.81/v11.90) and the peekNextIndex list-next anchor —
        // only what the row bind reads from it changes.
        if (found >= 0) soundingIdxCache = found;
        else if (service != null) soundingIdxCache = -1;
        else if (AudioService.liveSession && AudioService.liveHasTrack) soundingIdxCache = currentIndex;
        else soundingIdxCache = -1;
        return soundingIdxCache;
    }
    // v10.92: scan-proven playing URL for the queue waiting
    // counts — the clear dialog (v5.83) and the queue-only entry
    // gate (v5.87) both named the playing talk from currentIndex,
    // which lags the service by up to one poll tick after a
    // background auto-advance (v7.58 window): the finished talk
    // still indexed while the next talk already sounds, so a
    // queue holding only the sounding talk passed the gate and
    // opened a queue-only drawer whose only row was the talk
    // already playing, and the clear dialog counted it as
    // waiting. firstWaitingQueueEntry (v7.69) already scans
    // isCurrentTalk for exactly this window; this helper is the
    // same scan for the two count/gate sites. A scan miss keeps
    // the old behaviour exactly (indexed talk, then the lastUrl
    // session fallback when nothing is indexed); an unbound
    // service scans to nothing, same as today.
    // v11.75: liveness gate for the two fallbacks below — the
    // v11.29 mirror's own comment names the ungated lastUrl
    // fallback as the over-fire hazard (a dead session's stale
    // lastUrl, or a library session sounding while this Room
    // is open), but it was never fixed in the fallback
    // itself: with no track loaded (notification stop,
    // service death with rebind pending) the indexed
    // talk was named as sounding, so the header "· ◈ N"
    // read one too low, the clear dialog undercounted
    // waiting talks, and the queue-only gate could open a
    // drawer whose only row was not playing. Bound, a track
    // is live when the service holds one (paused still
    // counts — and a failed track still reads live, since the
    // service keeps its title until a stop; only a
    // stopped/cleared track reads false). v13.52 (audit-1374
    // F-E): the old "failed reads false" wording contradicted
    // the code — the failure-aware skip is explicit now via
    // failedUrl(), not via this liveness read. Unbound, the
    // liveSession + liveHasTrack
    // mirrors, exactly like isSoundingTalk.
    private boolean hasLiveTrackForQueue() {
        if (service != null) return service.hasTrack();
        return AudioService.liveSession && AudioService.liveHasTrack;
    }
    private String soundingUrlForQueue() {
        if (talks != null) {
            for (int i92 = 0; i92 < talks.size(); i92++)
                if (isCurrentTalk(talks.get(i92).url)) return talks.get(i92).url;
        }
        // v11.35: the queue-line scan — the v11.27-v11.34 defect in
        // count/gate form. The scan above covers only THIS Room's
        // talks, so a sounding FOREIGN talk saved in this queue
        // (a v2 line queued from another sayadaw's dialog, or via
        // handover) was never returned: bound, isCurrentTalk
        // against own talks misses it and the fallback below
        // named the indexed own talk instead; unbound, the own-
        // sayadaw lastUrl fallback misses it the same way. All
        // three callers then counted it as waiting — the header
        // "· ◈ N" one too many, the clear dialog counting it as
        // waiting (and offering "view queue only" when it was
        // the only line), and the queue-only gate opening a
        // drawer whose only row was the talk already sounding,
        // the exact v5.87 embarrassment in foreign form. Scan
        // the queue lines with the same mirrors firstWaiting-
        // QueueEntry uses (own lines via isSoundingTalk, v2
        // foreign lines via the v11.32 foreign mirror; bound,
        // both ARE isCurrentTalk, so the normal path is
        // unchanged). The fallbacks below are untouched — a
        // scan miss keeps the old behaviour exactly. A library
        // session is deliberately NOT mirrored unbound (v11.32
        // limit); bound, isCurrentTalk still catches it.
        if (queueUrls != null) {
            for (String line92 : queueUrls) {
                QueueEntry e92 = QueueEntry.parse(line92);
                if (e92 == null || e92.url == null || e92.url.length() == 0) continue;
                if (isSoundingTalk(e92.url)) return e92.url;
                if (e92.isV2() && isSoundingForeignTalk(e92.url, e92.sayadaw)) return e92.url;
            }
        }
        // v11.75: both fallbacks gated on a live track (see
        // hasLiveTrackForQueue above) — a scan miss with
        // nothing sounding now returns "" instead of naming
        // the indexed talk / stale lastUrl as playing.
        // v11.81: the indexed fallback narrows further — a scan miss while a track IS
        // live used to keep naming the indexed talk, but a scan miss with a KNOWN live
        // URL means the sounding talk is elsewhere (a library file, or another
        // sayadaw's talk started from a strip/notification), and naming the indexed
        // talk skipped it as "already playing" (header count one low, clear dialog
        // undercount, queue-only gate refusing a queue whose only entry waits). The
        // fallback now fires only while the service URL is not yet known (bound,
        // early-prepare window); a known URL lets the scans decide, and the
        // ownership-gated lastUrl fallback below still covers a live room session
        // of this sayadaw.
        if (hasLiveTrackForQueue()
                && service != null && service.getCurrentUrl() == null
                && talks != null && currentIndex >= 0 && currentIndex < talks.size())
            return talks.get(currentIndex).url;
        try {
            android.content.SharedPreferences dsp92 =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (hasLiveTrackForQueue()
                    && "room".equals(dsp92.getString("lastKind", ""))
                    && sayadawName != null
                    && sayadawName.equals(dsp92.getString("lastName", "")))
                return dsp92.getString("lastUrl", "");
        } catch (Exception ignored) {}
        return "";
    }
    // First queued entry that is still waiting (the sounding talk
    // is skipped, the v5.85 rule), in queue order, parsed -- not
    // the first in-list talk. Null when nothing is waiting.
    private QueueEntry firstWaitingQueueEntry() {
        // v7.69: skip the SOUNDING talk, not just the indexed one --
        // the v7.58 background-advance window again: currentIndex
        // still names the finished talk until the v7.59 poll tick,
        // so the old currentTalkUrl() skip left the sounding talk
        // as the "first waiting" entry. Every caller of this helper
        // (foreign handover, Up Next, play-next offers/moves) then
        // read the playing talk as waiting. Scan-proven sounding
        // index first (stream or offline-file form via
        // isCurrentTalk); a scan miss keeps the old behaviour
        // exactly, and the normal path is a no-op (scan == index).
        // Read-only: currentIndex itself is never moved here --
        // the poll resync and the gesture adopts own that.
        // v11.33: the v11.31 run's named follow-up — the scan above
        // was the last bound-only sounding check in this helper:
        // unbound (bind pending/dropped, live session sounding),
        // isCurrentTalk is always false, so cur stayed indexed and
        // a sounding talk still saved in the queue (the v5.85
        // rule) was returned as "first waiting" — play-next then
        // targeted the sounding talk instead of the true next
        // entry, and the foreign/handover callers read it as a
        // waiting head. The scan now uses the v11.29 mirror
        // (bound it IS isCurrentTalk, so the normal path is
        // unchanged; unbound it mirrors a live room session of
        // this sayadaw), and the queue loop skips a sounding
        // entry directly, so a sounding talk that is NOT in
        // this list is skipped too: own entries via
        // isSoundingTalk, v2 foreign entries via the v11.32
        // foreign mirror (its owner sayadaw, not this Room's).
        // Bound, both mirrors are isCurrentTalk, so the one
        // behaviour change there is intentional and follows the
        // v5.85 rule: a foreign sounding queue head was returned
        // as waiting (the old scan covered own talks only) and
        // is now skipped — handover / Up Next fire only for a
        // foreign head that is actually waiting, never for one
        // already sounding. A library session is deliberately
        // not mirrored unbound (same v11.32 limit: it never
        // writes lastName, so its pref cannot prove ownership;
        // bound, isCurrentTalk still catches it).
        // v11.75: cur is seeded from the index only when a
        // track is actually live (see hasLiveTrackForQueue)
        // — with nothing sounding the indexed talk IS the
        // first waiting entry, and the old unconditional seed
        // skipped it, so a Next press after a stop could hand
        // over to a foreign queue head while the queued own
        // talk that should play first was skipped. The scan
        // below still overwrites cur on a hit, so a live
        // track keeps the old behaviour exactly.
        // v11.81: seed from the index ONLY when the sounding track cannot be proven
        // foreign — bound with the service URL not yet known (early-prepare window; a
        // known URL the scan below misses means the live track is a library file or
        // another sayadaw's talk, and seeding the indexed talk skipped it as "already
        // playing" although the per-entry mirrors below would have said it is not
        // sounding). Never unbound: the scan + mirrors cover a live room session of
        // this sayadaw, and a library session is deliberately not mirrored (v11.32).
        // Nothing sounding still seeds null, as since v11.75, so the indexed talk
        // stays the first waiting entry after a stop.
        String cur = null;
        if (hasLiveTrackForQueue() && service != null && service.getCurrentUrl() == null)
            cur = currentTalkUrl();
        for (int si69 = 0; si69 < talks.size(); si69++)
            if (isSoundingTalk(talks.get(si69).url)) { cur = talks.get(si69).url; break; }
        for (String line : queueUrls) {
            QueueEntry e = QueueEntry.parse(line);
            if (e == null) continue;
            // v11.50: skip stale lines — the display rule every other
            // queue surface already uses (drawerQueueDisplayLines,
            // header count v7.13, clear dialog v11.38, gate v11.38,
            // exit v11.48), which this helper never adopted: a line
            // whose talk left the site's list (v1, or a v2 line for
            // this sayadaw) used to be returned as the "first
            // waiting" entry, so it masked the true waiting head —
            // play-next gates offered a move for a row that was
            // already first-displayed (and answered "set as next"
            // for a visual no-op instead of "already next"), the
            // header drop targeted the ghost instead of the shown
            // head, and firstWaitingForeignEntry read null, so Up
            // Next and the Next foreign branch missed a waiting
            // foreign head sitting behind the ghost until a peek
            // tick pruned it. A foreign v2 line still counts (it
            // displays and hands over); nothing is pruned here —
            // peekNextIndex still owns the disk prune — so normal
            // queues are byte-identical.
            if (talkIndexForUrl(e.url) < 0
                    && !(e.isV2() && (sayadawName == null
                            || !e.sayadaw.equals(sayadawName)))) continue;
            if (cur != null && e.url.equals(cur)) continue;
            if (isSoundingTalk(e.url)) continue;
            if (e.isV2() && isSoundingForeignTalk(e.url, e.sayadaw)) continue;
            // v13.52 (audit-1374 F1): explicit failed-talk skip — bound
            // it is a no-op today (cur already names it), but the skip
            // no longer depends on the sounding identity alone, and
            // the F-D unbound leg plugs in here.
            if (isFailedUrl(e.url)) continue;
            return e;
        }
        return null;
    }
    // The waiting head when it belongs to another sayadaw: the
    // (b) handover trigger. A v2 line for THIS sayadaw is not
    // foreign (it plays here), and a v1 line never is.
    private QueueEntry firstWaitingForeignEntry() {
        QueueEntry e = firstWaitingQueueEntry();
        if (e == null || !e.isV2()) return null;
        if (sayadawName != null && e.sayadaw.equals(sayadawName)) return null;
        return e;
    }
    // Bundled-catalog lookup for a handover target: group, page
    // URL and portrait for a sayadaw name, or null when the name
    // has no catalog entry (then the handover refuses, queue
    // intact, instead of opening a session with no page).
    // Same asset + field pattern as Main.loadCatalog and
    // AudioService.photoUrlForSayadaw; parsed on demand, so no
    // new cache can go stale.
    private String[] catalogEntryForSayadaw(String name) {
        if (name == null || name.length() == 0) return null;
        java.io.InputStream in = null;
        try {
            in = getAssets().open("sayadaws.json");
            java.io.ByteArrayOutputStream baos =
                    new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) baos.write(buf, 0, read);
            org.json.JSONArray arr = new org.json.JSONArray(
                    new String(baos.toByteArray(), "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                // v13.30 (Catalog F3): a bad row must not hide every
                // sayadaw after it — skip a non-object entry instead
                // of aborting the whole loop out of getJSONObject.
                org.json.JSONObject o;
                try { o = arr.getJSONObject(i); } catch (Exception ignored) { continue; }
                if (!name.equals(o.optString("name", ""))) continue;
                String group = o.optString("group", "");
                String page = o.isNull("page_url") ? ""
                        : o.optString("page_url", "");
                String photo = o.isNull("photo_url") ? ""
                        : o.optString("photo_url", "");
                if (photo.length() == 0 && !o.isNull("photo"))
                    photo = o.optString("photo", "");
                return new String[]{group, page, photo};
            }
        } catch (Exception ignored) {
        } finally {
            if (in != null) { try { in.close(); } catch (Exception ignored) {} }
        }
        return null;
    }
    // v7.08: (b) session handover. When Next / auto-advance
    // reaches a foreign queue head, the Room does NOT index into
    // this list (there is no index for a foreign talk). It
    // switches itself to the owning sayadaw -- the v1.85
    // notification-Next switch pattern, in place, no new
    // activity -- and the load completion plays the foreign talk
    // through the existing autoUrl path, so the data-saver gate
    // and the saved resume position apply exactly as for a row
    // tap. The queue travels: the foreign entry itself is KEPT
    // (v7.23 -- see below), remaining bare-v1 lines are
    // rewritten to v2 lines owned by the sayadaw being left
    // (title looked up in the list still in memory, so a later
    // session can show them), lines already v2 stay as-is, and
    // lines for the NEW sayadaw collapse back to bare URLs (so
    // every existing mark/consume site works verbatim there).
    // The carried queue is saved under the NEW sayadaw key
    // before the reload, and the (d) filter keeps it on every
    // later sync. False only on a catalog miss, queue untouched.
    private boolean handoverToForeignTalk(QueueEntry e) {
        String[] cat = catalogEntryForSayadaw(e.sayadaw);
        if (cat == null || cat[1].length() == 0) {
            Toast.makeText(RoomActivity.this,
                    Lang.t(RoomActivity.this, "sayadaw_list_not_found"),
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        if (gateDialog != null) {
            try { gateDialog.dismiss(); } catch (Exception ignored) {}
            gateDialog = null;
        }
        // v12.73 (sim1280): a history-back question whose HEAD probe is still running
        // (no dialog yet) used to clear here with no restore — the popped entry was
        // lost and historyBackUrl stayed set, blocking the v8.22 fresh-Room seed
        // until a sayadaw switch or process death. A showing dialog's dismiss above
        // already restored it through its listener (and nulled pendingGateBackUrl),
        // so this is a no-op then; with no dialog it gives the entry back —
        // abandon's no-dialog rule (a newer back-step's marker survives: only the
        // captured entry is pushed, and historyBackUrl clears only when it IS
        // that entry).
        if (pendingGateBackUrl != null && historyBackUrl != null) {
            pushPrevHistory16(pendingGateBackUrl);
            if (pendingGateBackUrl.equals(historyBackUrl)) historyBackUrl = null;
        }
        pendingGateIndex = -1;
        pendingGateUrl = null; // v11.97 (B-D4)
        pendingGateSeek = 0;
        pendingGateBackUrl = null; // v12.19
        pendingPlayIndex = -1;
        pendingSeekMs = 0;
        pendingBypass = false;
        pendingNext = false;
        pendingPrev = false;
        // v12.02: adopt the saved queue before building `carried` -- the
        // v12.01 R1/R2 idiom, applied to the handover: a head the other
        // window popped is dropped here instead of being kept into the new
        // key by the handover keep set below. Read-only adopt, no-op when
        // nothing changed; the empty-list guard makes it safe on every
        // handover path (talks is the loaded old list here).
        syncSavedQueueOnReturn();
        String leaving = sayadawName;
        // v8.21: record the talk being left for the walk back — every
        // handover path used to clear the index without pushing the
        // finished talk, so it never entered prevHistory, and even
        // a pushed entry could not play back: handlePrev prunes
        // URLs that are not in the new list. Push it here (drain
        // first, like the return correctors) and record its owner
        // (see recordHandoverBack21), so Previous in the new Room
        // can hand back across the sayadaw switch instead of
        // falling through to the new list's neighbour. The scan
        // fallback covers a stale -1 index (a handover tap while
        // the sounding talk was never indexed); a catalog miss
        // above already returned, so nothing is recorded for a
        // refused handover, and no current talk means no record.
        int leftIdx21 = currentIndex;
        if ((leftIdx21 < 0 || leftIdx21 >= talks.size())
                && service != null && service.hasTrack()) {
            for (int si21 = 0; si21 < talks.size(); si21++)
                if (isCurrentTalk(talks.get(si21).url)) { leftIdx21 = si21; break; }
        }
        if (leftIdx21 >= 0 && leftIdx21 < talks.size()) {
            String leftUrl21 = talks.get(leftIdx21).url;
            if (!drainHiddenTrail08(leftUrl21)) pushPrevHistory16(leftUrl21);
            recordHandoverBack21(leftUrl21, leaving, talks.get(leftIdx21).title);
        }
        java.util.ArrayList<String> carried =
                new java.util.ArrayList<String>();
        // v7.23: the foreign entry is KEPT, not dropped -- as a
        // bare line for the new session (it is this session's
        // talk now, so the v2 spelling would read foreign here).
        // Dropping it ate the talk whenever the new list failed
        // to load or the load-completion data gate was cancelled:
        // the line was already saved away before any play attempt
        // (and v7.18's pre-gate covers auto-advance only, never a
        // manual Next). Kept, it survives both -- playIndex drops
        // it once play actually starts (its post-gate removal),
        // and a gate cancel leaves it queued for the next Next
        // tap, exactly like an in-list queued talk.
        boolean kept = false;
        for (String line : queueUrls) {
            QueueEntry q = QueueEntry.parse(line);
            if (q == null) continue;
            if (!kept && q.url.equals(e.url)) { kept = true; carried.add(q.url); continue; }
            if (!q.isV2()) {
                String title = "";
                for (int i = 0; i < talks.size(); i++)
                    if (talks.get(i).url.equals(q.url)) { title = talks.get(i).title; break; }
                if (title.length() > 0 && leaving != null)
                    line = QueueEntry.format(
                            new QueueEntry(q.url, leaving, title));
            } else if (q.sayadaw.equals(e.sayadaw)) {
                line = q.url;
            }
            carried.add(line);
        }
        queueUrls = carried;
        sayadawName = e.sayadaw;
        // v7.74: the handover changes the resumed Room's sayadaw, so
        // re-publish the top Room (see topRoomSayadaw) — otherwise
        // the next end of the new session would look like a
        // different Room's session to the service and be advanced
        // behind the foreground poll's back again.
        if (roomResumed) topRoomSayadaw = sayadawName;
        sayadawGroup = cat[0];
        pageUrl = cat[1];
        photoUrl = cat[2];
        autoUrl = e.url;
        currentIndex = -1;
        endedHandledFor = null;
        talks.clear();
        // v11.81: same drawer reset as the onNewIntent switch — a search/filter for the
        // OLD sayadaw must not follow the handover into the new sayadaw's list.
        clearDrawerSearch();
        drawerFilterMode = 0;
        drawerQueueOnly = false;
        if (adapter != null) adapter.notifyDataSetChanged();
        android.widget.TextView header =
                findViewById(R.id.roomSayadaw);
        if (header != null) header.setText(sayadawName
                + (sayadawGroup == null || sayadawGroup.length() == 0
                        ? "" : " · " + sayadawGroup));
        refreshSayadawPhoto();
        // v8.59: the queue MOVED with this handover — the old
        // sayadaw's saved key must be cleared (the carried copy is
        // now the live queue, saved under the new sayadaw by the
        // saveQueue() after this). Without this, a fresh Room for
        // the old sayadaw restored the pre-handover queue and
        // re-offered talks the new session already played, as a
        // foreign head handing straight back. Nothing is lost:
        // a load failure in the new session still keeps its
        // foreign entry in the NEW key (v7.23), and a walk back
        // re-carries the live queue under the old name again.
        // v13.56: the clear NO LONGER runs here as its own apply()
        // — a death between it and the funnel's save left the queue
        // in NEITHER key. It is armed below and written by the
        // funnel's final persist in the same Editor transaction as
        // the new key (see clearQueueSayadawOnSave11).
        // v12.02: the carried queue is not in the new sayadaw's saved read
        // yet, so the funnel's stale drop would wipe it -- keep every
        // carried URL. The old key is cleared by the same persist below
        // (v13.56), so this still replaces the new key with the carried
        // queue, exactly as before.
        // v12.03: adopt=false -- the replace must not merge the new key's
        // old entries back in (sim1203 case 7).
        java.util.Set<String> keepHandover02 = new java.util.HashSet<String>();
        for (String line02 : queueUrls) {
            String u02 = queueLineUrl(line02);
            if (u02.length() > 0) keepHandover02.add(u02);
        }
        // v13.56: arm the old-key clear for the funnel's persist —
        // the single transaction that also writes the carried queue
        // under the new sayadaw (see the funnel's final persist).
        clearQueueSayadawOnSave11 = leaving;
        saveQueue(keepHandover02, false);
        loadTalks(true);
        return true;
    }
    // v8.21: cross-sayadaw walk-back record — prevHistory stores bare
    // URLs, so a popped URL from another sayadaw's list carries no
    // owner to hand back to. The handover records one line per
    // left-behind talk, "url TAB sayadaw TAB title", in prefs
    // (cap 50, newest wins per URL), surviving rotation and Room
    // recreation; handlePrev looks it up when the popped URL is
    // not in the current list, and the Previous preview names it
    // the same way. A record is never consumed on use: the stack
    // entry that names it is consumed instead, so re-pushing the
    // same talk later reuses a still-true record, and a reverse
    // handover's own prologue records the talk it leaves, so the
    // walk ping-pongs correctly (S1 -> S2 -> S1) instead of
    // stranding the listener in the second sayadaw's list.
    private void recordHandoverBack21(String url, String sayadaw, String title) {
        try {
            if (url == null || url.length() == 0
                    || sayadaw == null || sayadaw.length() == 0) return;
            String cleanTitle = title == null ? "" : title.replace('\t', ' ')
                    .replace('\n', ' ').replace('\r', ' ');
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String raw = sp.getString("handoverBack21", "");
            java.util.ArrayList<String> lines = new java.util.ArrayList<String>();
            if (raw != null && raw.length() > 0) {
                String[] parts = raw.split("\\n");
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i] == null || parts[i].length() == 0) continue;
                    if (parts[i].startsWith(url + "\t")) continue;
                    lines.add(parts[i]);
                }
            }
            lines.add(url + "\t" + sayadaw + "\t" + cleanTitle);
            while (lines.size() > 50) lines.remove(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) sb.append("\n");
                sb.append(lines.get(i));
            }
            sp.edit().putString("handoverBack21", sb.toString()).apply();
        } catch (Exception ignored) {}
    }
    private String[] handoverBackFor21(String url) {
        try {
            if (url == null || url.length() == 0) return null;
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String raw = sp.getString("handoverBack21", "");
            if (raw == null || raw.length() == 0) return null;
            String[] parts = raw.split("\\n");
            for (int i = parts.length - 1; i >= 0; i--) {
                if (parts[i] == null || !parts[i].startsWith(url + "\t")) continue;
                String rest = parts[i].substring(url.length() + 1);
                int tab = rest.indexOf('\t');
                if (tab < 0) return new String[]{rest, ""};
                return new String[]{rest.substring(0, tab), rest.substring(tab + 1)};
            }
        } catch (Exception ignored) {}
        return null;
    }
    // One-line Next-path gate: true when a foreign handover just
    // fired (the caller must not also advance in this list).
    private boolean tryForeignHandover() {
        QueueEntry e = firstWaitingForeignEntry();
        if (e == null) return false;
        return handoverToForeignTalk(e);
    }
    private void restoreSavedQueue() {
        if (!queueUrls.isEmpty()) return;
        java.util.ArrayList<String> restored = filterSavedQueue(Prefs.savedQueue(this, sayadawName));
        if (!restored.isEmpty()) {
            queueUrls = restored;
            lastQueueUrls03 = new java.util.ArrayList<String>(restored); // v12.03 snapshot
            if (adapter != null) adapter.notifyDataSetChanged();
        }
    }
    // v4.47: re-sync the hand queue on return — the Talks chooser's ⋮
    // menu (v4.47) writes the same per-sayadaw prefs key this Room
    // saves to, so a queue built while browsing while this Room sat
    // in the back stack would otherwise stay invisible until the Room
    // was recreated: its in-memory queueUrls still held the old list.
    // On return, re-read the saved queue (same list filter + dedupe
    // as restoreSavedQueue) and adopt it when it differs. Safe on
    // every other return: this Room's own mutations save at once, so
    // when nothing else touched the key the re-read equals the
    // in-memory list exactly and nothing changes. Guarded to a
    // non-empty talk list — the first onResume runs before the list
    // loads, and filtering against an empty list would wipe a queue
    // restoreSavedQueue is still going to load. The existing
    // notifyDataSetChanged below rebinds ◈ marks and Up Next.
    private void syncSavedQueueOnReturn() {
        if (talks.isEmpty()) return;
        java.util.ArrayList<String> synced = filterSavedQueue(Prefs.savedQueue(this, sayadawName));
        if (!synced.equals(queueUrls)) queueUrls = synced;
        lastQueueUrls03 = new java.util.ArrayList<String>(queueUrls); // v12.03 snapshot
    }

    // v4.35: repeat mode — 0 off, 1 all, 2 one (Prefs.repeatMode,
    // default all = today's v1.43 wrap). Loaded in the Room wiring
    // block, saved on every toggle tap; rotation needs no save,
    // prefs already hold it.
    private int repeatMode = 1;
    // v4.36: shuffle — Prefs.shuffleOn, default off. The pending
    // shuffle pick is a URL, not an index: it survives a list
    // reload (a retry can shorten/reorder the list) and it
    // self-heals — peekNextIndex repicks whenever the stored URL
    // is gone or has become the current talk, so no play path
    // needs to clear it explicitly.
    private boolean shuffleOn = false;
    private String shuffleNextUrl = null;
    // v3.24: arm flag for the new-current-row fade below — set only
    // by an advance that never morphs the new row itself (Next,
    // auto-advance, notification/headset Next), never by a row tap
    // (its morph owns that row's animator, and Fx.fadeIn's cancel
    // would kill the morph mid-spring). Consumed in playIndex once
    // the rebind has run; a gate/bind early return leaves it armed
    // so the replayed playIndex still fades, and a row tap clears
    // it first (see the row click handler).
    private boolean armNewRowFade = false;
    // v11.75: autoTitle deleted — write-only dead state (written
    // in handoverToForeignTalk, onCreate and onNewIntent, read
    // nowhere; the load-completion auto-play keys off autoUrl
    // only).
    private String sayadawName, sayadawGroup, pageUrl, photoUrl, autoUrl;
    // v12.79: true when this Room was opened by a playlist's
    // playFrom — the load-completion fallback (below) only fires
    // for a playlist-originated auto-play, never for a resume or
    // a tap, so a removed resume/tapped talk never starts a
    // different talk on its own. Set from the intent in onCreate
    // and onNewIntent, alongside autoUrl.
    private boolean autoFromPlaylist;
    // v7.74: the sayadaw of the Room currently on top, or null when no
    // Room is resumed — read by AudioService.advanceInBackground58 so
    // the service only advances while the session's own Room is
    // hidden. A plain boolean would also defer a session whose Room
    // is a different sayadaw's (browsing Room B while Room A's talk
    // ends must still advance A, exactly as v7.58 does today); the
    // name match keeps that case advancing and defers only the
    // foreground Room's own end, which its 500ms poll owns (toast,
    // row fade, queue consume). Set on resume, cleared on pause
    // (lifecycle order is sequential on the main thread, so a
    // Room-to-Room move clears then re-sets, never overlaps), and
    // re-published at the foreign handover below, the one place
    // sayadawName changes while resumed. No activity reference is
    // kept — a name only, so a finished Room leaves nothing behind.
    public static volatile String topRoomSayadaw = null;
    private boolean roomResumed = false;
    // v5.38: key of the portrait already bound to roomSayadawPhoto —
    // the header text is set from several paths (create, sayadaw
    // switch, rotation), but the portrait must load only when its
    // sayadaw actually changes, never once per poll or rebind.
    private String loadedSayadawPhotoKey = null;

    // v5.38: sayadaw portrait beside the Room's gold sayadaw name —
    // the Room showed only altar photos, never the speaker's face,
    // while every other screen (grid, notification art) already had
    // it. Same ImageLoader path as the grid: cached/circular 512px
    // portrait, gold initial circle when there is no photo (or no
    // name, "?"). Guarded by loadedSayadawPhotoKey so repeat calls
    // from the create/switch paths are no-ops.
    private void refreshSayadawPhoto() {
        android.widget.ImageView pv = findViewById(R.id.roomSayadawPhoto);
        if (pv == null) return;
        String key = (photoUrl == null ? "" : photoUrl) + "|" + (sayadawName == null ? "" : sayadawName);
        if (key.equals(loadedSayadawPhotoKey)) return;
        loadedSayadawPhotoKey = key;
        ImageLoader.load(this, photoUrl, sayadawName, pv);
    }
    private Handler handler = new Handler();
    private boolean seeking = false;
    // v7.97: which talk a Room wave drag started on — the drag's
    // target is an absolute ms in THAT talk's range, so a talk
    // change mid-drag (the poll's end-of-track advance, which has
    // no !seeking guard, while playIndex's bar reset does) must
    // not let the release seek the NEW talk to the OLD talk's
    // position. Captured at drag start, checked at release with
    // isCurrentTalk (so a stream/file form change for the same
    // talk still counts as the same talk, e.g. a repeat-one
    // restart); null when no talk was held at drag start, in
    // which case the release keeps its old behaviour.
    private String dragTalkUrl = null;
    // v1.69: which ended track the poll already handled — end-of-track
    // used to depend on wasPlaying, so a talk finishing mid-rotation
    // (recreated poll, wasPlaying reset to false) never auto-advanced
    // and just sat at its end. The handled marker survives rotation
    // (saved/restored below) and clears while anything plays, so a
    // replayed talk ends handled again.
    private String endedHandledFor = null;
    // v1.69: how many polls since open — every 60th (~30s) the Room
    // re-scans the downloaded set, so a Wi-Fi download finishing
    // mid-session gains its ⬇ mark without leaving and returning.
    private int pollCount = 0;
    // v4.05: download-complete receiver — a Wi-Fi download queued from
    // the data gate used to gain its ⬇ mark only on the next 30s rescan,
    // so a finished download sat unmarked (⏳) for up to half a minute
    // while the Room stayed in the foreground. The receiver fires the
    // same rescan the instant DownloadManager reports completion (or
    // failure — the v4.01 prune then drops a dead ⏳ just as fast).
    // Registered in onResume, unregistered in onPause, so it only ever
    // runs while this Room is the foreground screen; the onResume
    // refresh already covers the back-stack case.
    private android.content.BroadcastReceiver dlCompleteReceiver = null;
    private boolean dlCompleteRegistered = false;
    // v1.69: a data-gate question interrupted by rotation — the dialog
    // dies with the old activity and the tap used to vanish with it.
    // Keep the question across recreation and re-ask it once the
    // list is back (restored list, or a retried load).
    private int pendingGateIndex = -1;
    private int pendingGateSeek = 0;
    // v11.97 (B-D4, v11.83 persistence audit): the pending data-gate question's
    // target URL, beside its index. The index alone goes stale the moment the list
    // is re-fetched (a talk added/removed above the target shifts every index below
    // it), so the replay sites and the identity checks below resolve this URL in the
    // CURRENT list instead of trusting the stored index. Null when no question is
    // pending; travels in the bundle with the index, and is cleared at every site
    // that clears the index.
    private String pendingGateUrl = null;
    // v12.19: the pending question's popped history-back entry,
    // captured beside its URL in askDataGate (v11.85) so a superseded
    // question restores exactly its own entry — a newer back-step may
    // have replaced historyBackUrl since. Cleared at every site that
    // clears the index; travels in the bundle with the question.
    private String pendingGateBackUrl = null;
    // v13.56 (Room handover torn-carry fix): the sayadaw whose saved
    // queue key the NEXT saveQueue funnel persist must clear, in the
    // SAME Editor transaction as the new key's write. Set by
    // handoverToForeignTalk one statement before its saveQueue call
    // and consumed (nulled) by the funnel's final persist, so no
    // other saveQueue call can inherit it; a leaked value would only
    // clear a key the handover was going to clear anyway.
    private String clearQueueSayadawOnSave11 = null;
    // v11.46: is the pending gate question a RESUME question
    // (a held paused talk refused by the service gate) rather
    // than a fresh-play question? The two answer differently:
    // a fresh "stream" plays via playIndex, but a resume
    // "stream" must resume the held player — playIndex's
    // already-holding branch would only re-mark the row and
    // never start it. Travels in the bundle with the index.
    private boolean pendingGateResume = false;
    // v1.77: the currently shown data-gate dialog, if any — a second
    // talk tapped while a gate question is up (or its HEAD check still
    // running) used to stack a second identical dialog on top of the
    // first, and dismissing the top one cleared the newer question's
    // pending flag with it (see askDataGate).
    private android.app.AlertDialog gateDialog;
    private java.util.Random themeRandom = new java.util.Random();
    private int themeIndex = -1;
    // v12.50: the URL the current altar theme was rolled for — one
    // talk, one altar. pickTheme is a per-talk-change roll, but
    // playIndex re-rolled on every fresh play of the SAME talk
    // whenever the service held nothing (Play after a stop, restart
    // in place, resume from the saved spot, error retry — the
    // already-holding early return never fires there), so the altar
    // changed for a talk that did not change. null until the first
    // roll: the fresh-open hour photo (onCreate) belongs to no talk,
    // and autoTheme-off never records (pickTheme returns false), so
    // toggling it on later still rolls on the next play of that
    // talk. Travels in the bundle with themeIndex (rotation).
    private String themeTalkUrl = null;
    // v12.51: the talk whose altar is WAITING for playback
    // confirmation (08:28 +07 altar audit, Finding 5) — a roll
    // requested but not yet taken, because the talk has not
    // proved it sounds (still buffering, or the service not yet
    // bound). The poll's maybeRollPendingTheme rolls it on the
    // first tick after prepare confirms, or drops it when the
    // stream fails / the session moves on. Travels in the bundle
    // with themeIndex/themeTalkUrl (a rotation mid-buffer must
    // not lose the wait).
    private String pendingThemeUrl = null;
    // v1.48: a talk tapped before the service finished binding used to
    // vanish silently (playIndex no-op'd on a null service). Queue it
    // and play it the moment the service connects.
    // v1.70: notification / headset Next — the Room owns the list,
    // so the service hands Next here instead of advancing blindly.
    public static final String ACTION_NEXT = "com.h2h.dhamma.ACTION_NEXT";
    private boolean pendingNext = false;
    private boolean pendingQueue = false; // v4.76
    // v4.23: Previous parity — the Room had a Next intent since
    // v1.70 and a swipe-right previous, but no Previous intent,
    // so headset/car/notification Previous had nowhere to go.
    public static final String ACTION_PREV = "com.h2h.dhamma.ACTION_PREV";
    public static final String ACTION_QUEUE = "com.h2h.dhamma.ACTION_QUEUE"; // v4.76
    private boolean pendingPrev = false;
    private int pendingPlayIndex = -1;
    private int pendingSeekMs = 0;
    // v1.67: the data-gate bypass belongs to the queued tap too — a
    // tap queued before the bind used to lose it, so the bind handler
    // asked the mobile-data question a second time.
    private boolean pendingBypass = false;
    // v1.57: downloaded-file names, scanned once per list load — the
    // row bind used to hit the filesystem (new File + exists()) for
    // every row on every scroll bind, which janked long lists.
    private java.util.Set<String> downloadedFiles = new java.util.HashSet<String>();
    private java.util.Set<String> inProgressDlFiles = new java.util.HashSet<String>();
    // v11.56: inProgressDlFiles holds the filenames of talks whose DownloadManager job is still
    // live (RUNNING/PAUSED/PENDING) — refreshed inside
    // refreshDownloadedSet below, so the set, the row marks and
    // localPathFor all agree on what counts as downloaded. A
    // partial file is never a downloaded talk (see the helper).
    // v4.01: Wi-Fi-queued download URLs (see refreshQueuedSet below) —
    // cached like downloadedFiles so the row bind never touches prefs.
    private java.util.Set<String> queuedDownloads = new java.util.HashSet<String>();
    // v4.24: live download progress per queued talk URL (percent),
    // refreshed by pruneQueuedDownloads on the same 30s rescan +
    // completion-broadcast cadence as the ⏳ marks themselves, so
    // no row bind ever touches DownloadManager (the v4.01 rule).
    private java.util.Map<String, Integer> dlProgress = new java.util.HashMap<String, Integer>();
    // v1.58: played/favourite marks, cached once per list load like
    // the downloaded names since v1.57 — the row bind used to call
    // getStringSet() twice for every row on every scroll bind.
    private java.util.Set<String> playedSet = new java.util.HashSet<String>();
    private java.util.Set<String> favSet = new java.util.HashSet<String>();
    // v1.74: the share chooser is a transient sheet, not leaving
    // the Room — but opening it fires onPause, and with background
    // playback off that paused the talk the listener was sharing.
    // Set before the chooser opens, honoured in onPause, cleared
    // in onResume (chooser dismissed or a target app chosen).
    private boolean suppressBgPause = false;
    // v1.83: same loader-generation guard as the Talks chooser — a
    // retry tapped while a load was still in flight started a second
    // thread, and the older one finishing last overwrote the newer
    // list, reset the status line and re-showed retry (and could
    // re-fire the auto-talk / pending-Next handling against the
    // wrong list). Only the newest load may touch the list.
    private int loadSeq = 0;
    // v3.00: cached font size last applied by applyFontSizes —
    // the onCreate pass sets it, so onResume can tell a real
    // Settings font change from an ordinary return (same cache
    // pattern as Main v2.98 / Talks v2.99).
    private int cachedFont = 1;
    // v3.86: cached UI language last applied by applyLangLabels —
    // the onCreate pass sets it, so onResume can tell a real
    // Settings language change from an ordinary return (same
    // cache pattern as Main v3.84, which added this check to
    // Main's return dip only and never reached the Room).
    private String cachedLang = null;

    // v7.46: open-drawer rows rebind on external mark change — the
    // v7.43 fresh-open gap, restated while the drawer stays open.
    // The Room favourite button (v5.20) reads favTalks fresh from
    // prefs on every 500ms poll, but drawer rows bind from the
    // cached favSet/playedSet, refreshed only on a fresh open
    // (v7.43), on return, on load, on this Room's own toggles,
    // and on the 30s download rescan (downloaded set only) — so
    // a favourite toggled on the notification card while the
    // Room sat foreground with its Play List open (the shade
    // never pauses it, so no return-refresh fires) flipped the
    // button within one poll while the open drawer rows kept
    // their old ★ indefinitely, same screen, two answers, the
    // v7.43/v7.44/v7.45 class. The poll now compares the live
    // prefs sets against the row caches while the drawer is
    // open and rebinds once on a real difference; steady polls
    // only copy two small sets and compare, never touching the
    // adapter, so scrolling and the open pour stay undisturbed.
    // Guards: skipped while a move is armed (moveHeldUrl), so a
    // rebind can never disturb the held-row/drop logic; the
    // finish path already refreshes its own sets, so its ✓
    // never double-rebinds; queue marks are out of scope (no
    // external queue writer runs while this Room is foreground).
    // Prefs reads only — no layout, duration, or ban touch.
    private void syncOpenDrawerMarks46() {
        try {
            if (adapter == null || !isPlayListPanelOpen() || moveHeldUrl != null) return;
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            java.util.Set<String> favs = sp.getStringSet("favTalks", null);
            java.util.Set<String> played = sp.getStringSet("playedTalks", null);
            boolean favDiff = (favs == null)
                    ? (favSet != null && !favSet.isEmpty()) : !favs.equals(favSet);
            boolean playedDiff = (played == null)
                    ? (playedSet != null && !playedSet.isEmpty()) : !played.equals(playedSet);
            if (favDiff || playedDiff) {
                refreshMarkSets();
                adapter.notifyDataSetChanged();
            }
        } catch (Exception ignored) {}
    }

    private void refreshMarkSets() {
        try {
            playedSet = new java.util.HashSet<String>(
                    getSharedPreferences("dhamma", MODE_PRIVATE)
                            .getStringSet("playedTalks", new java.util.HashSet<String>()));
            favSet = new java.util.HashSet<String>(
                    getSharedPreferences("dhamma", MODE_PRIVATE)
                            .getStringSet("favTalks", new java.util.HashSet<String>()));
        } catch (Exception ignored) {}
        refreshQueuedSet(); // v4.01: queued-download marks refresh with the other marks
    }

    // v5.63: ✓ + ↺ on the long-press previews — the Next/Previous
    // toasts named their talk with only ★, while every other
    // named-talk surface carries the finished and resume marks
    // (drawer rows v1.44/v5.34, Up Next lines v5.62), so a
    // previewed talk read as unheard even when tapping it would
    // replay a finished talk or resume mid-talk. Same sources
    // as the drawer bind (playedSet + the >5s stream-form spot
    // with the downloaded-file path fallback, guarded by the
    // downloaded boolean so a stale cache entry can never mark
    // a deleted file), prefs + caches only, never the filesystem
    // per call — a long-press is a gesture, not a tick, so no
    // jank class applies. The playing target is excluded, like
    // the drawer rows: its live clocks already show its position,
    // and a restart-branch preview (Previous past 3s) never gets
    // ↺ at all, since that tap restarts from the beginning.
    private String previewTalkMarks(Talk t, boolean isPlayingTarget) {
        if (t == null || isPlayingTarget) return "";
        if (playedSet == null || favSet == null) refreshMarkSets();
        String m = "";
        if (playedSet != null && playedSet.contains(t.url)) m += " ✓";
        int rp = Prefs.talkPosGet(RoomActivity.this, t.url);
        if (rp <= 5000 && isDownloadedFile(t.url)) {
            String dlName = downloadedFileName(t.url);
            if (dlName != null)
                rp = Prefs.talkPosGet(RoomActivity.this,
                        downloadsFile(dlName).getAbsolutePath());
        }
        if (rp > 5000) m += " ↺ " + formatTime(rp);
        return m;
    }

    // v5.92: long-press preview lead marks — the Next/Previous
    // previews named their talk with ★ + ✓/↺ only (v5.63), while
    // every row naming that talk carries its ◈ queue position
    // (v4.32, waiting-numbered v5.82) and its ⬇ saved / ⏳
    // downloading mark (drawer rows v1.44/v4.01, Up Next line
    // v4.17/v4.24, chooser header v4.71) — so a previewed talk
    // read as unqueued and streaming even when a tap would play
    // the queued head offline. Same sources as the drawer bind:
    // queueUrls + the playing talk's queue position for the
    // waiting number, isDownloadedFile (cache-only) + the
    // queuedDownloads/dlProgress maps for ⬇/⏳%, favSet for ★ —
    // in the bind's own order ◈ ★ ⬇/⏳. The playing target keeps
    // only ★ (its ◈/⬇ already live on the Room line, like the
    // ✓/↺ exclusion in previewTalkMarks); the Previous restart
    // branch ("အစကနေ:") is untouched — it names the playing
    // talk itself, not a preview target. A long-press is a
    // gesture, not a tick, so no jank class applies.
    private String previewHeadMarks(Talk t, boolean isPlayingTarget) {
        if (t == null) return "";
        if (favSet == null) refreshMarkSets();
        String q = "";
        String off = "";
        if (!isPlayingTarget) {
            try {
                if (queueUrls != null) {
                    // v7.38: line-aware, like the v7.15 drop handlers — a
                    // handover-carried v2 line for an in-list talk
                    // (cross-listed talk, restore skipped on a
                    // non-empty carried queue) used to read as
                    // unqueued here, so the preview lost its ◈.
                    int qpos = queueLineIndexForUrl(t.url);
                    if (qpos >= 0) {
                        int shown = qpos + 1;
                        // v12.26: the playing talk is the scan-proven
                        // sounding one (soundingUrlForQueue: early-
                        // prepare indexed fallback, "" when nothing
                        // sounds), not the indexed talk — the row
                        // bind already reads playIdx71 for the same
                        // rule, this site never did.
                        String playUrl26 = soundingUrlForQueue();
                        if (playUrl26 != null && playUrl26.length() > 0) {
                            int playPos = queueLineIndexForUrl(playUrl26);
                            if (playPos >= 0 && playPos < qpos) shown--;
                        }
                        q = "◈" + shown + " ";
                    }
                }
            } catch (Exception ignored) {}
            try {
                if (isDownloadedFile(t.url)) off = "⬇ ";
                else if (queuedDownloads != null && queuedDownloads.contains(t.url)) {
                    Integer pct = dlProgress != null ? dlProgress.get(t.url) : null;
                    off = pct != null ? "⏳ " + pct + "% " : "⏳ ";
                }
            } catch (Exception ignored) {}
        }
        String fav = "";
        try { if (favSet != null && favSet.contains(t.url)) fav = "★ "; } catch (Exception ignored) {}
        return q + fav + off;
    }

    // v4.19: toggle the playing talk's favourite — the ★ mark in the
    // drawer rows (v1.44 bind) and the favTalks set it reads have
    // existed for ages, but NOTHING in the app ever wrote that set:
    // the v1.50 declutter removed the old favourite buttons, so a
    // talk could never actually gain its ★ (Settings clear-favs
    // only ever cleared an always-empty set). The altar photo's
    // long-press restores the toggle with no new button: tap still
    // plays/pauses, swipes still step talks and the drawer, and a
    // held finger favours/unfavours the playing talk, refreshing
    // the drawer rows at once via refreshMarkSets + notify.

    // v10.25: paused-card star sync, Room fav-flip unbound leg of v10.02/v10.22/v10.23/v10.24 —
    // the four Room fav flips (altar long-press togglePlayingFav + the three drawer-dialog
    // menu verbs) all guarded the card rebuild on service != null, but Room binds only in
    // onCreate and unbinds only in onDestroy, so service is null in exactly two windows:
    // before onServiceConnected fires (a fast drawer open + fav tap) and after
    // onServiceDisconnected (service death while the Room lives on) — in both, a PAUSED
    // sounding talk flipped here kept its old star until the next tap, since rebuilds run
    // every 15s only while playing, never while paused. This one helper keeps the bound
    // path byte-identical (rebuild at once only when the flipped URL is the sounding one,
    // via currentTalkUrl) and adds the unbound path in the v10.03 Talks / v10.23 Downloaded
    // shape: no-bind ACTION_REFRESH_NOTIFICATION intent, liveSession-guarded (never starts
    // a dead service), gated on prefs fresh at tap time — flipped == lastUrl (Room session),
    // or dlMap[flipped] file == lastLibPath file (sounding library/offline session).
    // The handler stopSelfs when trackless and refreshNotification is a no-op with no
    // track, and a rebuild with no star change paints the identical card. No new UI, no toast.
    private void refreshCardIfFlipped1025(String flippedUrl) {
        if (flippedUrl == null) return;
        try {
            if (service != null) {
                if (flippedUrl.equals(currentTalkUrl())) service.refreshNotification();
                return;
            }
            if (!AudioService.liveSession) return;
            android.content.SharedPreferences rsp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            boolean cardTalk = flippedUrl.equals(rsp.getString("lastUrl", ""));
            if (!cardTalk) {
                String libPath = rsp.getString("lastLibPath", "");
                if (libPath.length() > 0) {
                    String fn = new org.json.JSONObject(rsp.getString("dlMap", "{}")).optString(flippedUrl, "");
                    cardTalk = fn.length() > 0 && fn.equals(new java.io.File(libPath).getName());
                }
            }
            if (cardTalk) {
                android.content.Intent rsv = new android.content.Intent(RoomActivity.this, AudioService.class);
                rsv.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                startService(rsv);
            }
        } catch (Exception ignored) {}
    }

    private void togglePlayingFav() {
        if (talks == null || talks.isEmpty() || currentIndex < 0 || currentIndex >= talks.size()) {
            Toast.makeText(this, Lang.t(this, "no_talk_yet"), Toast.LENGTH_SHORT).show();
            return;
        }
        // v7.65: scan-adopt before mutating — the v7.60/v7.61/v7.62
        // window again: after a v7.58 background advance, a held
        // altar finger landing before the v7.59 return poll tick
        // would favourite/unfavourite the FINISHED talk, since
        // currentIndex still names it while the shrine already
        // shows the sounding one. Adopt the scan-proven sounding
        // index first (stream or offline-file form via
        // isCurrentTalk); a scan miss keeps the old behaviour
        // exactly, and the normal path is a no-op.
        if (currentIndex >= 0) {
            int snd65 = -1;
            for (int si65 = 0; si65 < talks.size(); si65++)
                if (isCurrentTalk(talks.get(si65).url)) { snd65 = si65; break; }
            // v8.19: adopt-push — a display/step adopt landing in the pre-poll-tick window after a hidden advance used to move the index without recording the talk left behind, so the v8.06/v8.08/v8.18 correctors never fired for it (their !isCurrentTalk guard saw the sounding talk already) and a later Previous skipped it, the v8.18 swipe failure on the fav/drawer/share/preview/button paths. Drain-or-push exactly like the correctors, only when the adopt actually moves a valid index; a scan miss or a no-op adopt keeps the old behaviour exactly.
if (snd65 >= 0 && snd65 != currentIndex && currentIndex >= 0 && currentIndex < talks.size()) {
    if (!drainHiddenTrail08(talks.get(currentIndex).url)) pushPrevHistory16(talks.get(currentIndex).url);
}
if (snd65 >= 0) currentIndex = snd65;
        }
        Talk t = talks.get(currentIndex);
        try {
            android.content.SharedPreferences sp = getSharedPreferences("dhamma", MODE_PRIVATE);
            java.util.Set<String> cur = sp.getStringSet("favTalks", new java.util.HashSet<String>());
            java.util.Set<String> next = new java.util.HashSet<String>(cur);
            boolean nowFav;
            if (next.contains(t.url)) { next.remove(t.url); nowFav = false; }
            else { next.add(t.url); nowFav = true; }
            sp.edit().putStringSet("favTalks", next).apply();
            // v10.22: paused-card star sync, altar-long-press leg of v10.02 —
            // the drawer-menu flips all rebuild the card when the flipped
            // talk is the sounding one, but this funnel (the altar hold
            // that stars the PLAYING talk itself) never did, so a paused
            // card kept its old star until the next tap. Same guard
            // shape: rebuild at once only when the flipped URL is the
            // sounding one; refreshNotification is a no-op with no track,
            // and a rebuild with no star change paints the identical card.
            // v10.25: route through the shared helper — the bound-only guard above missed
            // the pre-bind / post-disconnect windows (see the helper note).
            refreshCardIfFlipped1025(t.url);
            refreshMarkSets();
            if (adapter != null) adapter.notifyDataSetChanged();
            Fx.tap(this);
            Toast.makeText(this, Lang.t(this, nowFav ? "fav_added" : "fav_removed"), Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    // v1.58: Settings changes made while a talk is already playing
    // used to wait for the NEXT talk — sleep and speed were only
    // applied in the service's onPrepared. Sync them live whenever
    // the Room comes back with a playing service (re-entry from
    // Mp3 Player, or back from the share sheet): start a sleep timer
    // that never started, cancel one that was turned off, and apply
    // the new default speed to the current talk. A paused talk never
    // gains one.
    // v1.80: a timer already running used to be untouchable — changing
    // the sleep setting in Settings (30 → 15) while a talk played left
    // the old 30-minute countdown running until the next talk. The
    // service now remembers the running timer's minutes, so a changed
    // setting restarts the countdown; an unchanged one still never
    // restarts (returning to the Room must not silently extend it).
    // v5.19: the Room download button's state — READY shows ⬇,
    // QUEUED shows ⏳, anything else (already downloaded, nothing
    // playing, library session, stale session) hides the button.
    // Reuses the strips' own state exactly (Fx.stripDownloadState:
    // dlMap + candidate basenames + claim guard + dlQueued), so
    // Room, strips and Settings can never disagree, with one
    // Room-only guard on top: the session URL must be THIS Room's
    // current talk, so a stale previous-session talk never offers
    // itself for download before anything has played here. Called
    // from the 500ms poll and onResume (change-only) and at once
    // after a tap, mirroring the v5.18 speed refresh.
    private void refreshRoomDownloadBtn() {
        if (roomDownloadBtn == null) return;
        int state = Fx.STRIP_DL_HIDDEN;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (currentIndex >= 0 && currentIndex < talks.size()) {
                String cur = talks.get(currentIndex).url;
                String sess = Fx.stripFavUrl(RoomActivity.this, sp);
                if (cur != null && cur.equals(sess))
                    state = Fx.stripDownloadState(RoomActivity.this, sp);
            }
        } catch (Exception ignored) {}
        int wantVis = (state == Fx.STRIP_DL_HIDDEN)
                ? View.GONE : View.VISIBLE;
        String wantText = (state == Fx.STRIP_DL_QUEUED) ? "\u23f3" : "\u2b07";
        if (roomDownloadBtn.getVisibility() != wantVis)
            roomDownloadBtn.setVisibility(wantVis);
        if (!wantText.equals(roomDownloadBtn.getText().toString()))
            roomDownloadBtn.setText(wantText);
    }

    // v5.20: the Room favourite button's state — GONE unless this
    // Room's current talk IS the live session (the same Room-only
    // guard as refreshRoomDownloadBtn, so a stale previous-session
    // talk never offers itself), otherwise ★ when the talk is in
    // favTalks, ☆ when it is not. Reads favTalks fresh from prefs
    // each call, so a favourite toggled on a strip or in Settings
    // lands here without a leave/return. Called from the 500ms poll
    // and onResume (change-only) and at once after a tap, mirroring
    // the v5.19 download refresh.
    private void refreshRoomFavBtn() {
        if (roomFavBtn == null) return;
        String url = null;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (currentIndex >= 0 && currentIndex < talks.size()) {
                String cur = talks.get(currentIndex).url;
                String sess = Fx.stripFavUrl(RoomActivity.this, sp);
                if (cur != null && cur.equals(sess)) url = cur;
            }
        } catch (Exception ignored) {}
        String wantText = "\u2606";
        if (url != null) {
            try {
                java.util.Set<String> favs = getSharedPreferences("dhamma", MODE_PRIVATE)
                        .getStringSet("favTalks", new java.util.HashSet<String>());
                if (favs != null && favs.contains(url)) wantText = "\u2605";
            } catch (Exception ignored) {}
        }
        int wantVis = (url == null) ? View.GONE : View.VISIBLE;
        if (roomFavBtn.getVisibility() != wantVis)
            roomFavBtn.setVisibility(wantVis);
        if (!wantText.equals(roomFavBtn.getText().toString()))
            roomFavBtn.setText(wantText);
    }

    // v5.21: the Room share button's state — VISIBLE only when this
    // Room's current talk IS the live session (the same Room-only
    // guard as refreshRoomFavBtn/refreshRoomDownloadBtn, so a stale
    // previous-session talk never offers itself for sharing). The
    // glyph is static, so only visibility is change-only refreshed.
    // Called from the 500ms poll and onResume, mirroring the fav
    // refresh; no after-tap refresh is needed because sharing
    // changes no state (and returning from the share sheet runs
    // onResume anyway).
    private void refreshRoomShareBtn() {
        if (roomShareBtn == null) return;
        boolean live = false;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (currentIndex >= 0 && currentIndex < talks.size()) {
                String cur = talks.get(currentIndex).url;
                String sess = Fx.stripFavUrl(RoomActivity.this, sp);
                live = cur != null && cur.equals(sess);
            }
        } catch (Exception ignored) {}
        int wantVis = live ? View.VISIBLE : View.GONE;
        if (roomShareBtn.getVisibility() != wantVis)
            roomShareBtn.setVisibility(wantVis);
    }

    // v5.23: the Room repeat/shuffle buttons' state — re-reads the
    // prefs into the auto-advance fields (the same reload
    // syncLiveSettings does on return), then applies the original
    // v4.35/v4.36 glyph helpers, so a mode changed in Settings or
    // on any other screen lands here without a leave/return, and a
    // tap here takes effect at once because the click handler
    // writes the pref before calling this. Called from the 500ms
    // poll and onResume, mirroring the share refresh. The glyph
    // helpers write text + colour unconditionally, so this is
    // only ever called on those two paths, never per-frame.
    private void refreshRoomRepeatShuffle() {
        repeatMode = Prefs.repeatMode(this);
        shuffleOn = Prefs.shuffleOn(this);
        applyRepeatGlyph(roomRepeatBtn);
        applyShuffleGlyph(roomShuffleBtn);
    }

    private void syncLiveSettings() {
        // v5.12: repeat/shuffle were only read in onCreate, so a change
        // made in Settings while the Room sat in the back stack did
        // nothing until the Room was recreated — the same stale-pref
        // class as the v1.60 keep-screen-on bug. Reload on every
        // return (this runs from onResume and onServiceConnected); the
        // fields are Room-local (auto-advance reads them, not the
        // service), so reload even with no service bound. A cached
        // shuffle pick stays valid: peekNextIndex re-validates it
        // by URL and repicks when it no longer fits.
        repeatMode = Prefs.repeatMode(this);
        shuffleOn = Prefs.shuffleOn(this);
        if (service == null) return;
        // v1.65: the speed used to sync only while playing — a speed
        // change made in Settings while the talk sat paused never
        // reached the current talk, and pressing play resumed at the
        // stale speed until the next talk. Speed applies paused too;
        // the sleep timer still only touches a playing talk (a paused
        // talk never gains one, per v1.58).
        service.setSpeed(Prefs.defaultSpeed(this));
        if (!service.isPlaying()) return;
        int mins = Prefs.sleepMinutes(this);
        long remaining = service.getSleepRemainingMs();
        // v4.82: same one-shot guard as the service sync — a minute
        // pref changed since arming cancels it, an unchanged pref
        // is a routine return and leaves it alone.
        if (service.isSleepEndOfTalk() && mins != service.getSleepEndArmedPref())
            service.cancelSleepEndOfTalk();
        if (!service.isSleepEndOfTalk() && mins > 0 && (remaining == 0 || mins != service.getSleepSetMinutes()))
            service.setSleepMinutes(mins);
        else if (!service.isSleepEndOfTalk() && mins == 0 && remaining > 0) service.setSleepMinutes(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v1.81: count this screen as on top (see AppForeground).
        AppForeground.activityResumed();
        // v7.74: publish this Room as the top Room (see topRoomSayadaw).
        roomResumed = true;
        topRoomSayadaw = sayadawName;
        // v11.90: rebind when the onCreate bind failed (bindService returned false) —
        // without this, taps queued for the bind sat in pendingPlayIndex forever and the
        // Room swallowed them silently. The framework auto-reconnects a crashed service,
        // so only the never-bound case needs this; bound==true (bind in flight or live)
        // never rebinds, so the single onDestroy unbind stays exact.
        if (service == null && !bound) {
            try {
                bound = bindService(new Intent(this, AudioService.class), connection, Context.BIND_AUTO_CREATE);
            } catch (Exception ignored) {}
        }
        // v8.36: drop in-memory history stacks after a Settings
        // clear while this Room sat in the back stack — the
        // persisted keys (v8.33–v8.35) already cleared, but
        // prevHistory has no persisted copy to reconcile from and
        // forwardHistory only reconciles on a Next/Prev tap, so
        // until now Previous on return still walked erased
        // listens and the next-talk preview still named an
        // erased forward entry. Runs BEFORE the v7.73 scan-adopt
        // below, so a post-clear background advance still pushes
        // its just-finished talk as fresh history (heard after
        // the clear, not erased by it), while pre-clear entries
        // are gone; the persisted forward copy is re-written
        // empty to keep the v8.27 invariant. A generation
        // mismatch can only come from a clear (nothing else
        // bumps the key), and a fresh Room observes the current
        // generation in onCreate, so the normal return is a
        // no-op. handoverBack21 is deliberately untouched: it
        // is a lookup map, not a stepped stack — with the
        // stacks empty it is never consulted for an erased
        // entry, and a post-clear walk that re-enters a foreign
        // talk still needs its true owner name.
        try {
            long curGen36 = getSharedPreferences("dhamma", MODE_PRIVATE)
                    .getLong("historyClearGen", 0);
            if (seenHistoryClearGen36 >= 0 && curGen36 != seenHistoryClearGen36) {
                // v8.37: only re-write the persisted forward copy
                // empty when it still names THIS Room's pre-clear
                // stack. forwardHistoryRaw is a SHARED key (last
                // writer wins, v8.27/v8.28): a Room opened from
                // Settings' own player strip after the clear can
                // already have written a FRESH stack there, and
                // this late return's blind empty-write (v8.36)
                // would wipe it — the service background advance
                // reads that key, and the other Room's return
                // reconcile adopts an empty copy as a subsequence
                // of everything, so its post-clear forward chain
                // would silently die because an older Room came
                // back late. The test mirrors reconcile's own
                // subsequence walk (service pops only shrink a
                // stack in order, so a shrunken pre-clear copy
                // still matches); a copy that is NOT a
                // subsequence of the dropped stack must be
                // another Room's post-clear stack, so it is left
                // alone. Empty counts as this Room's (a no-op
                // write, the single-Room case). A read failure
                // keeps the old behaviour (empty it: the clear
                // promise wins over a copy we cannot inspect).
                boolean fwdIsMine37 = true;
                try {
                    String fwdRaw37 = getSharedPreferences("dhamma", MODE_PRIVATE)
                            .getString("forwardHistoryRaw", "");
                    if (fwdRaw37 != null && fwdRaw37.length() > 0) {
                        java.util.ArrayList<String> persisted37 = new java.util.ArrayList<String>();
                        for (String p37 : fwdRaw37.split("\\n"))
                            if (p37 != null && p37.length() > 0) persisted37.add(p37);
                        int pi37 = 0;
                        for (int si37 = 0; si37 < forwardHistory.size() && pi37 < persisted37.size(); si37++)
                            if (forwardHistory.get(si37).equals(persisted37.get(pi37))) pi37++;
                        fwdIsMine37 = (pi37 == persisted37.size());
                    }
                } catch (Exception ignored) { fwdIsMine37 = true; }
                prevHistory.clear();
                forwardHistory.clear();
                if (fwdIsMine37) saveForwardHistory27();
            }
            seenHistoryClearGen36 = curGen36;
        } catch (Exception ignored) {}
        // v1.60: keep-screen-on was only read in onCreate, so toggling
        // it in Settings while the Room sat in the back stack did
        // nothing until the Room was recreated — honour it on return.
        if (Prefs.keepScreenOn(this))
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // v1.74: the share chooser is gone by now (see
        // suppressBgPause) — a later onPause is a real leave again.
        suppressBgPause = false;
        // v3.00: premium return font dip, mirroring Main v2.98 /
        // Talks v2.99 — the Room kept resizing its header, now-
        // playing line and clocks in one frame when a Settings
        // font change landed on return, while the other screens
        // dip. A real change since applyFontSizes last ran now
        // dips with the existing Fx.themeDip (140ms down to
        // 0.55, swap at the dip, 260ms rise to exactly alpha 1).
        // Guards: the onCreate pass just set cachedFont, so the
        // first onResume and rotations never dip (no diff); a
        // return with nothing changed stays instant, so back-
        // stack navigation never dips pointlessly; the dip's
        // swap is applyFontSizes itself — no second call; the
        // root has no other alpha animation (only its children
        // animate), so the dip never cancels an entrance; the
        // living altar keeps drawing under the dip, it only dims.
        // v3.86: premium return language dip, completing Main's
        // v3.84 return language dip on the one screen with
        // Lang-driven controls it never covered — picking a
        // language in Settings used to leave this screen's
        // Forward / Next / drawer-title / retry labels in the OLD
        // language until the Room was recreated (they are set
        // only in onCreate), while the play/pause label snapped
        // within one poll tick and Main dipped. A real language
        // change since applyLangLabels last ran now dips with the
        // existing Fx.themeDip, whose swap re-applies the four
        // labels with the font sizes. Guards, mirroring v3.84:
        // the onCreate pass just set cachedLang, so the first
        // onResume and rotations never dip (no diff, and the null
        // guard covers any path where it did not run yet); a
        // return with nothing changed stays instant; the dip's
        // swap re-syncs cachedLang, so a dipped return never
        // re-dips; the poll only rewrites the play/pause TEXT
        // (never alpha), so it cannot fight the root dip, and
        // the four labels are never written anywhere else.
        android.view.View roomRoot = findViewById(R.id.root);
        boolean langChanged = cachedLang != null && !Prefs.lang(this).equals(cachedLang);
        if ((Prefs.fontSize(this) != cachedFont || langChanged) && roomRoot != null) {
            final boolean doLang = langChanged;
            Fx.themeDip(roomRoot, new Runnable() {
                @Override public void run() { applyFontSizes(); if (doLang) applyLangLabels(); }
            });
        } else {
            applyFontSizes();
            if (langChanged) applyLangLabels();
        }
        // v5.18: speed label on return — the poll refresh above only
        // runs with a live session, so a speed change made in
        // Settings while no talk is live would otherwise sit stale
        // until the next tap. Change-only, like the poll.
        if (roomSpeedBtn != null) {
            String spdWant = Fx.speedLabel(Prefs.defaultSpeed(this));
            if (!spdWant.equals(roomSpeedBtn.getText().toString()))
                roomSpeedBtn.setText(spdWant);
        }
        // v7.73: scan-adopt before the return refreshes — the last
        // unpatched reader in the v7.58 background-advance family.
        // The v7.70 poll comment claims onResume's own return sync
        // corrects the index, but no such sync runs here: the bind
        // sync only fires on a fresh bind, and a back-stack return
        // keeps the already-bound service (bind is in onCreate,
        // unbind only in onDestroy), so the v7.59 poll resync —
        // posted at the end of onResume — is the first corrector.
        // Until that tick, the three session-guarded buttons below
        // read the FINISHED talk's index while the session already
        // names the sounding one, so the guard fails and all three
        // hide, then reappear on the first tick: a flicker on
        // every return after a background auto-advance. Adopting
        // the scan-proven sounding index first (stream or
        // offline-file form via isCurrentTalk) fixes the return
        // frame itself; a scan miss keeps the old behaviour
        // exactly, and the normal path is a no-op.
        if (currentIndex >= 0 && talks != null) {
            int snd73 = -1;
            for (int si73 = 0; si73 < talks.size(); si73++)
                if (isCurrentTalk(talks.get(si73).url)) { snd73 = si73; break; }
            // v8.06: the adopt below used to move the index without
            // recording the talk left behind, so after a hidden
            // background advance (v7.58 plain, v7.63 queue, v8.05
            // shuffle) the shuffle Previous walk skipped the
            // just-finished talk: prevHistory held only talks
            // playIndex had pushed, and this adopt is not
            // playIndex. Push the left-behind talk exactly as
            // playIndex does (cap 50, no historyBackUrl branch —
            // that marker is consumed synchronously inside
            // playIndex, so it can never be live across a
            // resume); a no-op when the index already names the
            // sounding talk, and the poll resync below cannot
            // double-push because it only fires while the index
            // is still stale.
            if (snd73 >= 0 && snd73 != currentIndex
                    && currentIndex < talks.size()) {
                // v8.08: drain the hidden-advance trail first — a
                // chained hidden advance leaves intermediate talks
                // in the trail, not just this left-behind one; the
                // single push below only runs when the trail did
                // not already supply the left-behind talk.
                if (!drainHiddenTrail08(talks.get(currentIndex).url)) {
                    pushPrevHistory16(talks.get(currentIndex).url);
                }
                // v8.61: this adopt IS a talk change (a background
                // advance while the Room sat in the back stack), and
                // the shrine photo must follow it like a playIndex
                // change does — until now only playIndex called
                // pickTheme, so the photo stayed on the finished
                // talk's mood for the whole returned session. A
                // no-op when autoTheme is off (frozen opening photo)
                // and when the index already names the sounding talk
                // (this block only runs on a real move); the poll
                // resync below cannot double-switch, its guard only
                // fires while the index is still stale.
                // v12.51: per-talk roll, deferred until the adopted
                // talk is confirmed sounding (see requestThemeForTalk).
                requestThemeForTalk(talks.get(snd73).url);
                // v10.38: the wave must follow it like a playIndex
                // change does too — until now this adopt re-pointed
                // the index but left the bar holding the finished
                // talk's end position, and the first poll tick then
                // read a same-duration new talk as NOT a fresh
                // track (max already equals its duration), so the
                // bar took the v3.32 rewind glide end -> 0 over
                // 500ms instead of starting the new talk at 0 —
                // a backward sweep across the whole bar for a talk
                // that never rewinds. A different-duration advance
                // never showed it (max != duration takes the
                // fresh-track instant set), and the foreground
                // advance never showed it either (playIndex resets
                // the bar). The reset mirrors playIndex exactly
                // (glide cancelled, settle hold voided, max +
                // progress + buffered to 0, clocks to 0:00, same
                // !seeking guard so a drag in flight is never
                // yanked); the next poll then takes the fresh-track
                // path, or the v3.31 paused-resume glide when the
                // sounding talk sits paused at a saved position.
                // A no-op when the index already names the sounding
                // talk (this block only runs on a real move).
                if (!seeking) {
                    if (seekGlide != null) { seekGlide.cancel(); seekGlide = null; }
                    lastUserSeekMs = 0;
                    if (seekBar != null) {
                        seekBar.setMax(0);
                        seekBar.setProgress(0);
                        seekBar.setBufferedPercent(0);
                    }
                    if (elapsedText != null) elapsedText.setText("0:00");
                    if (totalText != null) totalText.setText("0:00");
                }
            }
            if (snd73 >= 0) currentIndex = snd73;
        }
        refreshRoomDownloadBtn();
        refreshRoomFavBtn();
        refreshRoomShareBtn();
        refreshRoomRepeatShuffle();
        refreshMarkSets();
        // v1.62: the ⬇ marks never refreshed on return — a Wi-Fi
        // download queued from the data gate (or finished while this
        // screen sat in the back stack) left its row unmarked until
        // the list was reloaded. Re-scan with the played/favourite
        // marks, like the library's onResume reload.
        refreshDownloadedSet();
        // v4.01: prune dead queue entries on return too, so the
        // notifyDataSetChanged below rebinds rows with fresh ⏳
        // marks (a download finished/failed while the Room sat in
        // the back stack must not keep or lose its mark wrongly).
        // v10.31: snapshot prefs dlQueued before the prune (v10.16 rescan shape) —
        // the onResume prune below is the same dead-entry drop as the rescan's,
        // but its boolean was discarded, so a FAILED/cancelled job pruned on
        // return never refreshed the card (see the v1.74 block below).
        java.util.Set<String> queuedBefore1031 = new java.util.HashSet<String>();
        try {
            org.json.JSONArray qb1031 = new org.json.JSONArray(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlQueued", "[]"));
            for (int i1031 = 0; i1031 < qb1031.length(); i1031++)
                queuedBefore1031.add(qb1031.optString(i1031));
        } catch (Exception ignored) {}
        boolean queuedChanged1031 = pruneQueuedDownloads();
        boolean droppedCur1031 = false;
        if (queuedChanged1031) {
            try {
                String cur1031 = currentTalkUrl();
                if (cur1031 != null && queuedBefore1031.contains(cur1031)) {
                    org.json.JSONArray qa1031 = new org.json.JSONArray(
                            getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlQueued", "[]"));
                    boolean stillQueued1031 = false;
                    for (int i1031 = 0; i1031 < qa1031.length(); i1031++)
                        if (cur1031.equals(qa1031.optString(i1031))) { stillQueued1031 = true; break; }
                    droppedCur1031 = !stillQueued1031;
                }
            } catch (Exception ignored) {}
        }
        syncSavedQueueOnReturn();
        syncLiveSettings();
        // v1.74: a notifications on/off toggle made in Settings
        // while a talk sat paused left the old card until the next
        // play/pause tap — rebuild it on return (no-op with no track).
        if (service != null) service.refreshNotification();
        else if (droppedCur1031 && AudioService.liveSession) {
            // v10.31: unbound leg of the v1.74 onResume card refresh — onResume
            // runs before onServiceConnected fires (a fast return) and the Room
            // lives on after onServiceDisconnected (service death), so the v1.74
            // bound-only refresh missed a FAILED/cancelled job pruned by the
            // onResume prune above in either window, leaving a PAUSED card
            // showing \u23f3 for a talk that had already lost its queue entry
            // until the next tap (rebuilds run every 15s only while playing,
            // never while paused; the v10.30 rescan leg never fires here —
            // this prune site discards its result and runs no rescan). The
            // drop test mirrors the v10.16/v10.30 gate exactly (this Room's
            // current talk in the pre-prune snapshot, gone after); unrelated
            // prunes never re-post the card, and the no-bind intent is the
            // v10.03 shape, liveSession-guarded, never starts a dead service.
            // A rebuild with no mark change paints the identical card, and
            // the bound path above is unchanged (it still refreshes on every
            // return for the Settings notification toggle). No new UI, no toast.
            try {
                android.content.Intent rsv = new android.content.Intent(RoomActivity.this, AudioService.class);
                rsv.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                startService(rsv);
            } catch (Exception ignored) {}
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        // v1.71: restart the poll (see onPause) — remove first so the
        // onCreate post plus this one never double up the 500ms loop.
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
        // v4.05: listen for download completions while foreground —
        // a completion here means one of this Room's talks just landed
        // (or failed), so run the same rescan at once instead of waiting
        // for the next 30s tick. Any completion fires it (the broadcast
        // is device-wide), but the rescan is a pure diff: rows only move
        // when one of this list's talks actually gained/lost a mark.
        if (!dlCompleteRegistered) {
            if (dlCompleteReceiver == null) {
                dlCompleteReceiver = new android.content.BroadcastReceiver() {
                    @Override public void onReceive(android.content.Context c, android.content.Intent in) {
                        runDownloadRescan();
                        // v12.30: a FAILED job used to die silently here too — the rescan
                        // above drops its ⏳ via the prune and the success block below only
                        // toasts on STATUS_SUCCESSFUL, so a listener who queued a download
                        // (Wi-Fi wait, then a network drop mid-file) saw the hourglass vanish
                        // with no file and no word why (same hole Talks v12.29 closed on its
                        // screen). A failure now toasts "download_failed" + the talk title,
                        // the same string shape as the enqueue catch. Separate try, BEFORE the
                        // success block: the success block's dlDoneOk path is success-only, and
                        // a download can fail while nothing plays, so the toast is not gated
                        // on a live session. Gated on the source URL being a talk this app
                        // queued (dlMap, or this Room's list), so an unrelated system download
                        // failing while this screen is open never toasts here; the rescan above
                        // touches dlQueued only, never dlMap, so the dlMap gate still reads true
                        // after it. Title falls back COLUMN_TITLE -> this list's title -> URL.
                        // The broadcast fires once per job, so the toast fires once. Success
                        // toasts nothing new — the existing ⬇ toast below is the feedback.
                        try {
                            long failId = in.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                            if (failId >= 0) {
                                android.app.DownloadManager dmFail = (android.app.DownloadManager)
                                        getSystemService(android.content.Context.DOWNLOAD_SERVICE);
                                android.database.Cursor curFail = dmFail.query(
                                        new android.app.DownloadManager.Query().setFilterById(failId));
                                if (curFail != null) {
                                    try {
                                        if (curFail.moveToFirst()) {
                                            int stColFail = curFail.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS);
                                            if (stColFail >= 0 && curFail.getInt(stColFail) == android.app.DownloadManager.STATUS_FAILED) {
                                                int srcColFail = curFail.getColumnIndex(android.app.DownloadManager.COLUMN_URI);
                                                String failUrl = srcColFail >= 0 ? curFail.getString(srcColFail) : null;
                                                if (failUrl != null && failUrl.length() > 0) {
                                                    boolean oursFail = false;
                                                    try {
                                                        org.json.JSONObject mapFail = new org.json.JSONObject(
                                                                getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlMap", "{}"));
                                                        oursFail = mapFail.has(failUrl);
                                                    } catch (Exception ignored) {}
                                                    String failTitle = null;
                                                    int titleColFail = curFail.getColumnIndex(android.app.DownloadManager.COLUMN_TITLE);
                                                    if (titleColFail >= 0) failTitle = curFail.getString(titleColFail);
                                                    if (talks != null) {
                                                        for (Talk t : talks) {
                                                            if (t != null && failUrl.equals(t.url)) {
                                                                oursFail = true;
                                                                if ((failTitle == null || failTitle.length() == 0) && t.title != null && t.title.length() > 0) failTitle = t.title;
                                                                break;
                                                            }
                                                        }
                                                    }
                                                    if (oursFail) {
                                                        if (failTitle == null || failTitle.length() == 0) failTitle = failUrl;
                                                        android.widget.Toast.makeText(RoomActivity.this,
                                                                Lang.t(RoomActivity.this, "download_failed") + failTitle,
                                                                android.widget.Toast.LENGTH_LONG).show();
                                                    }
                                                }
                                            }
                                        }
                                    } finally { curFail.close(); }
                                }
                            }
                        } catch (Exception ignored) {}
                        // v4.27: a finished download used to land
                        // silently (rows just swapped ⏳ for ⬇) —
                        // now it names itself in a toast. Only
                        // successful jobs landing under
                        // Downloads/Dhamma count, so unrelated
                        // system downloads never toast here.
                        // v10.06: same success gate rebuilds the paused card — the rescan above
                        // flips this Room's rows ⏳→⬇ at once, but the notification card reads its
                        // ⬇/⏳ mark only at rebuild time (v5.93) and rebuilds run every 15s while
                        // playing, never while paused, so a paused card kept showing ⏳ for a talk
                        // that had already landed until the next tap/return (same class as the
                        // v10.02/v10.03/v10.05 paused-card syncs). Rebuild only on a successful
                        // Dhamma/ landing — the toast's own gate — so unrelated system downloads
                        // never re-post the card; a rebuild with no mark change paints the
                        // identical card, and refreshNotification is a no-op with no track.
                        boolean dlDoneOk = false;
                        try {
                            long doneId = in.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                            if (doneId >= 0) {
                                android.app.DownloadManager dmDone = (android.app.DownloadManager)
                                        getSystemService(android.content.Context.DOWNLOAD_SERVICE);
                                android.database.Cursor cur = dmDone.query(
                                        new android.app.DownloadManager.Query().setFilterById(doneId));
                                if (cur != null) {
                                    try {
                                        if (cur.moveToFirst()) {
                                            int stCol = cur.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS);
                                            int uriCol = cur.getColumnIndex(android.app.DownloadManager.COLUMN_LOCAL_URI);
                                            int titleCol = cur.getColumnIndex(android.app.DownloadManager.COLUMN_TITLE);
                                            String localDone = uriCol >= 0 ? cur.getString(uriCol) : null;
                                            int stDone = stCol >= 0 ? cur.getInt(stCol) : -1;
                                            if (stDone == android.app.DownloadManager.STATUS_SUCCESSFUL
                                                    && localDone != null && localDone.contains("Dhamma/")) {
                                                dlDoneOk = true;
                                                String tDone = titleCol >= 0 ? cur.getString(titleCol) : null;
                                                if (tDone != null && tDone.length() > 0)
                                                    android.widget.Toast.makeText(RoomActivity.this,
                                                            "⬇ " + tDone + Lang.t(RoomActivity.this, "room_dl_done_suffix"),
                                                            android.widget.Toast.LENGTH_SHORT).show();
                                            }
                                        }
                                    } finally { cur.close(); }
                                }
                            }
                        } catch (Exception ignored) {}
                        if (dlDoneOk && service != null) service.refreshNotification();
                        else if (dlDoneOk && AudioService.liveSession) {
                            // v10.29: unbound leg of the v10.06 completion sync — same two
                            // windows as the v10.27 shuffle / v10.28 queue-count legs
                            // (before onServiceConnected fires — this receiver is registered
                            // in onResume, which can run before the bind completes — and
                            // after onServiceDisconnected while the Room lives on), so a
                            // download landing in either window left a PAUSED card showing
                            // ⏳ for a talk that had already landed until the next tap
                            // (rebuilds run every 15s only while playing, never while paused).
                            // The bound leg already fires for ANY successful Dhamma/ landing,
                            // so the unbound leg mirrors its gate exactly (dlDoneOk) with
                            // only the v10.03 liveSession guard added — never starts a dead
                            // service; the handler stopSelfs when trackless and a rebuild
                            // with no mark change paints the identical card. Bound path
                            // byte-identical.
                            try {
                                android.content.Intent rsv = new android.content.Intent(RoomActivity.this, AudioService.class);
                                rsv.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                                startService(rsv);
                            } catch (Exception ignored) {}
                        }
                    }
                };
            }
            try {
                android.content.IntentFilter f = new android.content.IntentFilter(
                        android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE);
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(dlCompleteReceiver, f, android.content.Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(dlCompleteReceiver, f);
                }
                dlCompleteRegistered = true;
            } catch (Exception ignored) {}
        }
        // v3.82: re-arm the toggle dim after a leave — onPause
        // resets the toggle to bright (its entrance-reset rule),
        // so a return to an open drawer (e.g. back from the share
        // chooser) would otherwise leave it bright while its drawer
        // stands open. Direct set, not an animation: this is a
        // state restore, and the logical drawerOpen flag (set at
        // every setPlayListPanel call, never from mid-animation
        // visibility) is the truth — a leave mid-close already set
        // it false, so a closing drawer never re-dims here. The
        // first onResume and rotations are no-ops (drawer closed,
        // or the rotation block above already set it).
        if (drawerOpen && playListEntranceDone) {
            android.view.View tgl = findViewById(R.id.roomPlayList);
            if (tgl != null) tgl.setAlpha(0.8f);
        }
    }

    // v1.95: the Room's status lines never followed the Settings
    // font size — the list rows have since v1.53, but the sayadaw
    // header, now-playing line and elapsed/total clocks stayed at
    // their fixed layout sizes, so large-text listeners got big
    // rows under a small header. Same steps as the rows (14/16/20)
    // for the now-playing line, a touch larger for the sayadaw
    // header, smaller for the clocks; re-applied on return so a
    // Settings change lands without recreating the Room. The three
    // control buttons keep their default size — three across, they
    // would crowd at 20sp on narrow phones.
    private void applyFontSizes() {
        int f = Prefs.fontSize(this);
        cachedFont = f;
        // v7.28 (user order 2026-11-01 ~19:07 +07, reversing the v5.30
        // Spotify order): the sayadaw header is now the big line at
        // 20/22/26 and the talk title below it is smaller at 16/18/20,
        // so the sayadaw reads first on every font-size setting. Clocks
        // and the three control buttons stay put (crowding note
        // above); the wave itself grows in layout (32dp→48dp)
        // and the title wraps to 3 lines instead of 2.
        float head = f == 0 ? 20 : (f == 1 ? 22 : 26);
        float now = f == 0 ? 16 : (f == 1 ? 18 : 20);
        float clock = f == 0 ? 12 : (f == 1 ? 14 : 16);
        android.widget.TextView sd = findViewById(R.id.roomSayadaw);
        if (sd != null)
            sd.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, head);
        if (nowPlaying != null)
            nowPlaying.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, now);
        if (elapsedText != null)
            elapsedText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, clock);
        if (totalText != null)
            totalText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, clock);
        if (sleepText != null)
            sleepText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, clock);
        // v1.98: the Room's retry button (shown only on load
        // failure, v1.42) kept its default size while the header /
        // now-playing / clocks scaled in v1.95 — same button step
        // as Settings (14/16/18). The three control buttons stay
        // default-sized per the v1.95 crowding note; the retry
        // button sits alone, so it can scale safely.
        if (btnRetry != null) btnRetry.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_SP, f == 0 ? 14 : (f == 1 ? 16 : 18));
        // v9.99: the Play List drawer title (layout 18sp) was the one Room
        // header never sized by applyFontSizes — the sayadaw header, talk
        // title, clocks and retry button all scale, but the drawer title
        // stayed 18sp in every font setting, so a large-text listener
        // opening the drawer got a small title on a grown screen. It now
        // follows the Room talk-title step (16/18/20): normal font yields
        // exactly the layout 18sp, so default display is unchanged.
        // applyFontSizes runs on create and on every return dip, so a
        // font change in Settings lands on return too.
        android.widget.TextView plTitle = findViewById(R.id.roomPlayListTitle);
        if (plTitle != null) plTitle.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_SP, f == 0 ? 16 : (f == 1 ? 18 : 20));
        // v10.35: the drawer search box + empty line scale with the
        // drawer title above them (same create + return-dip path, so
        // a Settings font change lands on return, not just on a
        // fresh open). findViewById, not the field: this can run
        // before wireDrawerSearch assigns it.
        sizeDrawerSearchBox();
    }

    // v3.86: the Room's Lang-driven control labels in one
    // v5.15: Previous joins them; v5.17: Sleep joins them (seven labels now)
    // place, so onCreate and the return dip share them. Forward /
    // Next / drawer title / retry only; the sayadaw header and
    // talk titles are content and stay Burmese, and play/pause
    // follows the language through the poll. Syncs cachedLang, so
    // a dipped return never re-dips (same pattern as Main v3.84).
    private void applyLangLabels() {
        android.widget.ImageButton nxtBtn = findViewById(R.id.roomNext);
        if (nxtBtn != null) nxtBtn.setImageResource(R.drawable.ic_next); // v7.28: vector icon
        android.widget.ImageButton prvBtn = findViewById(R.id.roomPrev);
        if (prvBtn != null) prvBtn.setImageResource(R.drawable.ic_prev); // v7.28: vector icon
        android.widget.ImageButton slpBtn = findViewById(R.id.roomSleepBtn);
        if (slpBtn != null) slpBtn.setImageResource(R.drawable.ic_sleep); // v7.28: vector moon+Z icon
        TextView plTitle = findViewById(R.id.roomPlayListTitle);
        if (plTitle != null) plTitle.setText(Lang.t(this, "playlist"));
        android.widget.TextView retryBtn = findViewById(R.id.roomRetry);
        if (retryBtn != null) retryBtn.setText(Lang.t(this, "retry"));
        // v9.93: the status line under the sayadaw header was the one Room
        // label applyLangLabels never re-read. When a talk is playing (or
        // buffering) the 500ms poll rewrites it in the new language on the
        // next tick, but a Room sitting idle on its count line ("N talks
        // - choose one") or its empty "choose a talk" line kept the OLD
        // language after a Settings language change, because nothing
        // rewrote it until the next list load. Re-read it here, using
        // the EXISTING room_count_prefix/suffix and choose_talk keys
        // (same keys the list-load path at 3092/3139 already uses, so
        // Burmese display is byte-identical). Guarded: a line starting
        // with the play/pause glyphs or the buffering prefix is a live
        // track line owned by the poll and is left alone; a loading
        // line is overwritten only until the load finishes and sets
        // the count line itself.
        if (nowPlaying != null) {
            String np = nowPlaying.getText().toString();
            if (!np.startsWith("\u25B6 ") && !np.startsWith("\u23F8")
                    && !np.startsWith(Lang.t(this, "notif_state_buffering"))) {
                nowPlaying.setText(talks.isEmpty() ? Lang.t(this, "choose_talk")
                        : Lang.t(this, "room_count_prefix") + talks.size() + Lang.t(this, "room_count_suffix"));
            }
        }
        cachedLang = Prefs.lang(this);
    }

    // v11.56: the library's v11.55 fix, in Room form — a talk still
    // downloading is NOT a downloaded talk yet. DownloadManager
    // writes straight into Downloads/Dhamma, so the Room's scan
    // used to list the partial file as finished: the row gained a
    // false ⬇ mark, pruneQueuedDownloads dropped the ⏳ queue
    // entry early (isDownloadedFile fired on the partial file),
    // and localPathFor handed the play path a truncated file, so
    // tapping the talk played it offline instead of streaming it
    // (and, on mobile data, skipped the data-saver ask entirely).
    // Live jobs (RUNNING/PAUSED/PENDING) are now named here, by
    // destination under Dhamma/ via the percent-decoded local URI,
    // or by the dlMap filename when the job exposes no destination
    // yet (a Wi-Fi-waiting PENDING job) — the exact v11.55 rule,
    // so both screens agree. A DM query failure fails open (empty
    // set = the old behaviour), never an empty shelf; a row bind
    // never touches DownloadManager (the v4.01 rule) — this runs
    // only inside refreshDownloadedSet, on load / return /
    // rotation / 30s-rescan / completion cadence.
    private java.util.Set<String> inProgressDlFilenames() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        try {
            java.util.Map<String, String> dlMap = new java.util.HashMap<String, String>();
            try {
                org.json.JSONObject map = new org.json.JSONObject(
                        getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlMap", "{}"));
                java.util.Iterator<String> keys = map.keys();
                while (keys.hasNext()) {
                    String u = keys.next();
                    dlMap.put(u, map.optString(u, ""));
                }
            } catch (Exception ignored) {}
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            android.database.Cursor c = dm.query(new android.app.DownloadManager.Query());
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

    private void refreshDownloadedSet() {
        downloadedFiles.clear();
        inProgressDlFiles = inProgressDlFilenames();
        try {
            java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma");
            java.io.File[] files = dir.listFiles();
            // v11.90: a zero-length file is a failed/leftover partial, not a download —
            // it used to gain the downloaded mark and play truncated offline with no
            // data-saver ask. (A non-empty partial from a dead job is still counted;
            // telling it from a real file needs a persisted Content-Length, not this run.)
            if (files != null) for (java.io.File f : files)
                if (f.isFile() && f.length() > 0 && !inProgressDlFiles.contains(f.getName())) downloadedFiles.add(f.getName());
        } catch (Exception ignored) {}
    }

    // v1.82: candidate on-disk filenames for a talk URL, in order —
    // the raw basename first (the Wi-Fi download destination uses it
    // verbatim, query string included), then the basename stripped of
    // any ?query / #fragment, then the %XX-decoded stripped name. A
    // talk downloaded by another route (browser, older build) under
    // the clean name used to miss every offline check below, so it
    // streamed and re-asked the data gate even though the file was
    // already on the phone. Raw first, so existing matches never move.
    // v12.16: the enqueue destination form, appended last — the
    // download sites (downloadFileNameFor, v1.95) land a wrapper /
    // bare-name URL as stripped + ".mp3" (play.php?f=x.mp3 ->
    // "play.php.mp3"), so a map-less queued entry whose live job
    // arrived under a duplicate spelling (the source-URL guard cannot
    // save it) dropped wrongly in the prune: none of raw / stripped /
    // decoded names the live destination. Only when the stripped name
    // is non-empty and lacks .mp3 (case-insensitive, the same check
    // the enqueue sites use); a bare "/" has no basename to extend.
    // isDownloadedFile / downloadedFileName / localPathFor gain the
    // same match — a legacy unmapped "play.php.mp3" on disk IS this
    // talk's file — still behind the fileClaimedByOther guard at
    // every use, so a name another URL claims is never misattributed.
    private static String[] fileNameCandidates(String url) {
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

    private static java.io.File downloadsFile(String name) {
        return new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma/" + name);
    }

    // v1.90: URL→filename map for offline downloads ("dlMap" in the
    // shared prefs, a JSON object). Downloads/Dhamma is flat, so two
    // sayadaws' talks with the same basename (01.mp3-style) used to
    // collide: the second talk matched the first talk's file, played
    // the wrong audio, showed a false ⬇ mark and never asked the
    // data gate. The gate records every Wi-Fi download here; the
    // mapped name wins, and a basename the map claims for another
    // url never matches. Files downloaded before this map existed
    // have no entry and keep the old basename fallback.
    private String mappedFileName(String url) {
        try {
            org.json.JSONObject map = new org.json.JSONObject(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlMap", "{}"));
            if (map.has(url)) return map.getString(url);
        } catch (Exception ignored) {}
        return null;
    }

    private boolean fileClaimedByOther(String filename, String url) {
        try {
            org.json.JSONObject map = new org.json.JSONObject(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlMap", "{}"));
            java.util.Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!k.equals(url) && filename.equals(map.optString(k, ""))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // v11.71: is this URL already queued for download -- fresh
    // prefs read at offer/tap time, not the cached set, so a job
    // queued from the chooser a second earlier still blocks a
    // double enqueue (the v10.73 gate guard, in drawer form; the
    // same shape v11.70 added to TalksActivity).
    private boolean roomIsDlQueued(String url) {
        if (url == null) return false;
        try {
            org.json.JSONArray qa = new org.json.JSONArray(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlQueued", "[]"));
            for (int i = 0; i < qa.length(); i++)
                if (url.equals(qa.optString(i))) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // v11.71: enqueue a Wi-Fi-only download for a talk from a Room
    // drawer dialog -- the Room data-gate's own machinery (the
    // gate's Wi-Fi button at ~9150), shared by both sounding-row
    // dialogs below so the verb has exactly one implementation.
    // Tap-time re-checks, the v11.14/v11.12 pattern: the offer
    // gate ran at dialog-build time, but a Wi-Fi job can land, or
    // another path can queue this talk, while the dialog sits
    // open. The re-check re-scans the disk first
    // (refreshDownloadedSet), so a file landed outside the 30s
    // rescan is still caught -- a landed file refreshes the marks
    // and toasts its downloaded line instead of enqueueing a
    // second job; a queued talk toasts "already queued" (the
    // v10.73 guard). On Wi-Fi the job starts at once, on mobile
    // data it waits for Wi-Fi, so a tap here can never silently
    // spend mobile data. No sounding re-check is needed: a talk
    // that stopped sounding while the dialog sat open is still
    // just a talk to download, unlike the queue verbs (v11.12).
    private void enqueueRoomDownload(Talk t) {
        if (t == null || t.url == null) return;
        refreshDownloadedSet();
        if (isDownloadedFile(t.url)) {
            if (adapter != null) adapter.notifyDataSetChanged();
            android.widget.Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "downloaded_talk"), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (roomIsDlQueued(t.url)) {
            android.widget.Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "dl_already_queued"), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String file71 = downloadFileNameFor(t.url);
        try {
            android.app.DownloadManager dm71 = (android.app.DownloadManager)
                    getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            android.app.DownloadManager.Request req71 =
                    new android.app.DownloadManager.Request(android.net.Uri.parse(t.url));
            req71.setTitle(t.title);
            req71.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req71.setAllowedNetworkTypes(android.app.DownloadManager.Request.NETWORK_WIFI);
            req71.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "Dhamma/" + file71);
            dm71.enqueue(req71);
            recordDownloadFile(t.url, file71);
        } catch (Exception e) {
            android.widget.Toast.makeText(RoomActivity.this,
                    Lang.t(RoomActivity.this, "download_failed") + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        // The hourglass at once: addQueuedDownload + the rescan
        // mirror the data-gate's v4.01 / v11.56 shape, and the
        // card sync mirrors its v10.14 leg (a paused sounding
        // talk queued here gains its card mark at once).
        addQueuedDownload(t.url);
        refreshDownloadedSet();
        refreshCardIfFlipped1025(t.url);
        if (adapter != null) adapter.notifyDataSetChanged();
        if (Fx.stripIsWifi(RoomActivity.this))
            android.widget.Toast.makeText(RoomActivity.this,
                    "\u2B07 " + (t.title != null && t.title.length() > 0 ? t.title : t.url) + Lang.t(RoomActivity.this, "dl_downloading_suffix"),
                    android.widget.Toast.LENGTH_SHORT).show();
        else
            android.widget.Toast.makeText(RoomActivity.this,
                    Lang.t(RoomActivity.this, "dl_room_wifi_queued"), android.widget.Toast.LENGTH_LONG).show();
    }

    private void recordDownloadFile(String url, String filename) {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
            map.put(url, filename);
            sp.edit().putString("dlMap", map.toString()).apply();
            // v5.45: record this download's sayadaw beside dlMap
            // (Fx.stripLibSayadaw) — this Room is the only place
            // that knows it, so a later library session can name
            // the talk's sayadaw on its strips, share text and
            // card instead of a generic line. Older downloads
            // simply have no entry, and every reader falls back.
            if (sayadawName != null && sayadawName.length() > 0) {
                try {
                    org.json.JSONObject sm = new org.json.JSONObject(sp.getString("dlSayadaw", "{}"));
                    sm.put(url, sayadawName);
                    sp.edit().putString("dlSayadaw", sm.toString()).apply();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    // v4.01: Wi-Fi-queued download mark — a talk queued from the data
    // gate ("Wi-Fi ရမှ ဒေါင်းလုဒ်လုပ်မယ်") used to show NOTHING on its
    // drawer row until the file landed, when the 30s rescan faded in
    // the ⬇ mark (v3.08): while waiting for Wi-Fi, or while the file
    // was mid-download, the row read exactly like a talk never queued
    // at all, so a listener could not tell a queued talk from one
    // they never asked for. Queued URLs live in prefs ("dlQueued", a
    // JSON array) beside the dlMap entry, added at enqueue time and
    // pruned by pruneQueuedDownloads below. The drawer row binds a
    // ⏳ prefix for a queued-but-not-yet-downloaded talk, in the same
    // text-prefix mark family as ★ ⬇ ✓ — no new view, no new
    // control, and the Talks chooser is untouched (its rows stay
    // number + title only by the fixed spec). Guards: a downloaded
    // talk never shows ⏳ (the ⬇ mark owns it, and the prune drops
    // the queue entry once the file lands); a queue entry whose
    // DownloadManager job is gone (failed, cancelled, or cleared in
    // the system Downloads app) is pruned on the next rescan, so a
    // dead queue never shows ⏳ forever; a DownloadManager query
    // failure skips the job check and keeps the mark rather than
    // dropping a live queue on a transient error; the prune runs on
    // the existing 30s rescan cadence plus Room creation, never per
    // bind, so scrolling never touches DownloadManager.
    private void refreshQueuedSet() {
        try {
            queuedDownloads.clear();
            org.json.JSONArray arr = new org.json.JSONArray(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlQueued", "[]"));
            for (int i = 0; i < arr.length(); i++) queuedDownloads.add(arr.optString(i));
        } catch (Exception ignored) {}
    }

    private void persistQueuedSet() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String u : queuedDownloads) arr.put(u);
            getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                    .putString("dlQueued", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void addQueuedDownload(String url) {
        refreshQueuedSet();
        if (queuedDownloads.add(url)) persistQueuedSet();
    }

    private boolean pruneQueuedDownloads() {
        refreshQueuedSet();
        if (queuedDownloads.isEmpty()) {
            // v4.24: no queue, no progress — drop stale percents so
            // a later queue never inherits an old download's number.
            if (!dlProgress.isEmpty()) dlProgress.clear();
            return false;
        }
        boolean changed = false;
        java.util.Set<String> activeNames = null;
        // v7.92: live source URLs, mirroring the Talks prune's v7.77
        // guard — a job whose destination file does not exist yet
        // (a Wi-Fi-waiting PENDING job) exposes no local URI, so
        // its filename never lands in activeNames below, and the
        // old rule pruned its queue entry while the job was still
        // live: the ⏳ vanished and a re-tap double-enqueued the
        // same destination. A live job's source URL now keeps its
        // entry even with no destination name yet.
        java.util.Set<String> activeSources = new java.util.HashSet<String>();
        // v7.20: a DownloadManager row counts as a live job ONLY
        // when its status is PENDING, RUNNING or PAUSED and it
        // belongs to this app (its source URL is a queued URL,
        // or its destination sits under Downloads/Dhamma). The
        // old rule counted EVERY row's destination basename:
        // a finished job whose row lingers, or another app's
        // download sharing a basename (browser "01.mp3" in
        // Downloads root), kept a dead queue entry alive — the
        // row showed ⏳ forever and every batch verb skipped
        // the talk as "already queued", so it could never be
        // re-downloaded. The Main/Talks strips already used
        // this live-status + app-scoped rule; the prune is
        // now the same rule, so all screens agree. An unknown
        // status (-1, column missing) still counts as live,
        // like the old query-failure rule: never prune on a
        // transient read failure.
        // v4.24: progress per live job, keyed two ways — by the
        // source URL (COLUMN_URI, the talk URL itself) and by the
        // destination filename (COLUMN_LOCAL_URI) — so a queued talk
        // maps to its percent whichever column the job exposes.
        java.util.Map<String, Integer> progressBySource = new java.util.HashMap<String, Integer>();
        java.util.Map<String, Integer> progressByFile = new java.util.HashMap<String, Integer>();
        try {
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    getSystemService(Context.DOWNLOAD_SERVICE);
            android.database.Cursor c = dm.query(
                    new android.app.DownloadManager.Query());
            // v12.14: the set is created only after a non-null cursor is
            // confirmed (Talks v11.76 / Main v11.78 port) — a null cursor
            // used to leave an empty non-null set, so the drop loop below
            // wiped every live queued entry as "not active", although the
            // comment above promises a failed query keeps everything.
            if (c != null) {
                activeNames = new java.util.HashSet<String>();
                try {
                    int uriCol = c.getColumnIndex(
                            android.app.DownloadManager.COLUMN_LOCAL_URI);
                    int srcCol = c.getColumnIndex(
                            android.app.DownloadManager.COLUMN_URI);
                    int statusCol = c.getColumnIndex(
                            android.app.DownloadManager.COLUMN_STATUS);
                    int doneCol = c.getColumnIndex(
                            android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                    int totalCol = c.getColumnIndex(
                            android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                    while (c.moveToNext()) {
                        int st = statusCol >= 0 ? c.getInt(statusCol) : -1;
                        if (st >= 0
                                && st != android.app.DownloadManager.STATUS_RUNNING
                                && st != android.app.DownloadManager.STATUS_PAUSED
                                && st != android.app.DownloadManager.STATUS_PENDING) continue;
                        String src = srcCol >= 0 ? c.getString(srcCol) : null;
                        String uri = uriCol >= 0 ? c.getString(uriCol) : null;
                        if (src != null && !queuedDownloads.contains(src)
                                && (uri == null || !uri.contains("Dhamma/"))) continue;
                        if (src == null && uri != null && !uri.contains("Dhamma/")) continue;
                        if (src != null) activeSources.add(src);
                        if (uri != null) {
                            // v11.81: decode before the basename — COLUMN_LOCAL_URI is a
                            // file:// URI, so a filename carrying a %XX escape (space /
                            // non-ASCII in the URL basename, kept verbatim by
                            // downloadFileNameFor) arrives here as %25XX and never matched
                            // the dlMap name: the prune dropped the live ⏳ and a re-tap
                            // could double-enqueue. inProgressDlFilenames already decodes;
                            // this was the one site that forgot.
                            String dec81 = android.net.Uri.decode(uri);
                            if (dec81 == null) dec81 = uri;
                            int slash = dec81.lastIndexOf('/');
                            activeNames.add(slash >= 0 ? dec81.substring(slash + 1) : dec81);
                        }
                        long done = doneCol >= 0 ? c.getLong(doneCol) : -1;
                        long total = totalCol >= 0 ? c.getLong(totalCol) : -1;
                        if (done >= 0 && total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (srcCol >= 0 && c.getString(srcCol) != null)
                                progressBySource.put(c.getString(srcCol), pct);
                            if (uri != null) {
                                // v11.81: same %XX decode as activeNames above — the progress
                                // is looked up by the decoded mapped name, so an encoded key
                                // here never matched and the ⏳ stayed bare.
                                String dec81 = android.net.Uri.decode(uri);
                                if (dec81 == null) dec81 = uri;
                                int slash = dec81.lastIndexOf('/');
                                progressByFile.put(slash >= 0 ? dec81.substring(slash + 1) : dec81, pct);
                            }
                        }
                    }
                } finally { c.close(); }
            }
        } catch (Exception ignored) { activeNames = null; }
        // v4.24: fold the job progress onto queued talk URLs — a
        // source-URL hit first, else the dlMap filename — and treat
        // a moved percent as a rescan change, so the row rebinds
        // and the ⏳ gains its number instead of sitting bare
        // until the file lands. Unknown total (or a failed query,
        // activeNames null) keeps the bare ⏳ rather than a lie.
        java.util.Map<String, Integer> newProgress = new java.util.HashMap<String, Integer>();
        for (String u : queuedDownloads) {
            Integer pct = progressBySource.get(u);
            if (pct == null) {
                String mapped = mappedFileName(u);
                if (mapped != null) pct = progressByFile.get(mapped);
            }
            if (pct != null) newProgress.put(u, pct);
        }
        if (!newProgress.equals(dlProgress)) {
            dlProgress = newProgress;
            changed = true;
        }
        java.util.Iterator<String> it = queuedDownloads.iterator();
        while (it.hasNext()) {
            String url = it.next();
            if (isDownloadedFile(url)) { it.remove(); changed = true; continue; }
            if (activeSources.contains(url)) continue;
            if (activeNames != null) {
                String mapped = mappedFileName(url);
                if (mapped != null && !activeNames.contains(mapped)) {
                    it.remove(); changed = true;
                } else if (mapped == null) {
                    // v11.85: a queued URL with no dlMap entry could never be pruned
                    // above — the drop depended entirely on the mapping. Both enqueue
                    // sites record the map first (recordDownloadFile before
                    // addQueuedDownload), so a map-less entry is a legacy remnant whose
                    // job is dead; drop it when none of its filename candidates names a
                    // live job, so its ⏳ never sticks forever and blocks re-download
                    // as "already queued". A live job's candidates always match (the
                    // enqueue is synchronous), and a failed query keeps everything.
                    boolean anyActive85 = false;
                    for (String cand85 : fileNameCandidates(url)) {
                        if (cand85 != null && activeNames.contains(cand85)) { anyActive85 = true; break; }
                    }
                    if (!anyActive85) { it.remove(); changed = true; }
                }
            }
        }
        if (changed) persistQueuedSet();
        return changed;
    }

    // v1.90: the filename a new Wi-Fi download must use — the mapped
    // name when one exists, else the stripped basename, unless that
    // basename is already claimed by another url's download, in
    // which case a short hash of this url keeps the two files apart
    // instead of overwriting (or playing) the other talk's audio.
    private String downloadFileNameFor(String url) {
        String mapped = mappedFileName(url);
        if (mapped != null) return mapped;
        String base = url.substring(url.lastIndexOf('/') + 1);
        int q = base.indexOf('?');
        if (q >= 0) base = base.substring(0, q);
        int hh = base.indexOf('#');
        if (hh >= 0) base = base.substring(0, hh);
        // v1.95: a talk whose link basename carries no .mp3 (a
        // wrapper URL like play.php?f=talk.mp3, or a bare name)
        // used to download extension-less — the Room still played
        // it via the map, but the library lists only *.mp3, so the
        // file was invisible there and missing from its totals.
        // Guarantee the extension on every new download (legacy
        // extension-less files keep their mapped name and still
        // play offline, as before).
        if (!base.toLowerCase().endsWith(".mp3")) base = base + ".mp3";
        // v8.40: the claim alone forces the hash suffix — the old
        // exists() conjunct let a stale claim slip through: talk A
        // downloaded as 01.mp3, its file then deleted OUTSIDE the
        // app (in-app deletes prune dlMap, outside deletes leave
        // the link surviving, v8.30), then talk B with the same
        // basename queued a Wi-Fi download, found no file on disk,
        // took the plain name and recorded B->01.mp3 beside the
        // surviving A->01.mp3 — so once B's file landed, A showed
        // a false ⬇, skipped the data gate and played B's audio,
        // the exact v1.90 collision resurrected. A dlMap claim
        // owns its name whether or not the file is present today;
        // an unneeded suffix on a truly dead claim costs nothing
        // (a unique file, correct audio, correct ⬇), while a dual
        // claim plays the wrong talk. Legacy unclaimed files keep
        // the old adopt-the-basename behaviour (claim false).
        if (fileClaimedByOther(base, url)) {
            String hash = "";
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] d = md.digest(url.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i]));
                hash = sb.toString();
            // v1.94: the old fallback (String.valueOf(hashCode)) could
            // be negative or shorter than 8 chars, so the library's
            // " [0-9a-f]{8}$" title strip missed it and the stored
            // name no longer matched the collision-suffix shape.
            // Always 8 lowercase hex digits instead.
            } catch (Exception ignored) { hash = String.format("%08x", url.hashCode()); }
            // v1.94: case-insensitive extension check — the loaders
            // match .mp3 case-insensitively, so a ".MP3" talk could
            // reach the collision branch, lose its extension entirely
            // (the old code only kept ".mp3"), and then vanish from
            // the library, which lists only *.mp3 files. Keep the
            // original extension's case instead of forcing ".mp3".
            if (base.toLowerCase().endsWith(".mp3"))
                return base.substring(0, base.length() - 4) + "_" + hash + base.substring(base.length() - 4);
            return base + "_" + hash;
        }
        return base;
    }

    private boolean isDownloadedFile(String url) {
        try {
            String mapped = mappedFileName(url);
            if (mapped != null && downloadedFiles.contains(mapped)) return true;
            for (String name : fileNameCandidates(url))
                if (!fileClaimedByOther(name, url) && downloadedFiles.contains(name)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // v5.59: the downloaded filename isDownloadedFile matched, or
    // null — the same cache-only checks (dlMap name, then basename
    // candidates against the once-per-load downloadedFiles set),
    // so the ↺ hint below can look a talk's spot up under its
    // downloaded-file form without touching the filesystem in a
    // bind (the v1.57 rule). Never a new scan: a name outside the
    // set is not downloaded, whatever the disk holds right now.
    private String downloadedFileName(String url) {
        try {
            String mapped = mappedFileName(url);
            if (mapped != null && downloadedFiles.contains(mapped)) return mapped;
            for (String name : fileNameCandidates(url))
                if (!fileClaimedByOther(name, url) && downloadedFiles.contains(name)) return name;
        } catch (Exception ignored) {}
        return null;
    }

    // v1.42: the offline file for a talk's stream URL, or null when the
    // talk was never downloaded — playing it saves mobile data.
    private String localPathFor(String url) {
        try {
            String mapped = mappedFileName(url);
            // v11.56: a still-downloading file is not an offline
            // file — returning its partial path here made the play
            // path (and the data-saver gate, which reads null as
            // "not downloaded") treat it as finished. Skip every
            // candidate the live-job set names, so the talk
            // streams until its download really lands.
            if (mapped != null && !inProgressDlFiles.contains(mapped)) {
                java.io.File f = downloadsFile(mapped);
                if (f.exists()) return f.getAbsolutePath();
            }
            for (String name : fileNameCandidates(url)) {
                if (fileClaimedByOther(name, url)) continue;
                if (inProgressDlFiles.contains(name)) continue;
                java.io.File f = downloadsFile(name);
                if (f.exists()) return f.getAbsolutePath();
            }
            return null;
        } catch (Exception ignored) { return null; }
    }

    // v1.66: the saved resume position for a talk URL (0 when none,
    // or when it is only a few seconds in) — same match the auto-play
    // path in loadTalks uses, so a failed-stream retry lands where
    // the listener stopped instead of restarting from the beginning.
    // v1.79: the threshold used to be 10s while pause() has saved
    // from 5s since v1.65 — a talk paused at 7s saved its spot and
    // then had it thrown away on retry, restarting from 0. Match
    // the save rule: anything pause() saved is worth resuming.
    private int savedPosFor(String url) {
        try {
            android.content.SharedPreferences pp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String savedUrl = pp.getString("lastPosUrl", "");
            String savedLocal = localPathFor(url);
            if (url.equals(savedUrl) || (savedLocal != null && savedLocal.equals(savedUrl))) {
                int saved = pp.getInt("lastPos", 0);
                return saved > 5000 ? saved : 0;
            }
            // v5.34: per-talk resume — the global spot above only ever
            // covered the most recent talk, so any other half-heard
            // talk restarted from 0. A talk's own saved spot (written
            // under both its forms by the service, so no local-path
            // lookup is needed here) resumes it too, same >5s rule;
            // a finished talk's spot was cleared on completion, so
            // it still starts fresh. This one lookup feeds every
            // Room play path (tap, Next, play-all, auto-advance,
            // retry, auto-open), so they all gain it at once.
            int per = Prefs.talkPosGet(this, url);
            if (per > 5000) return per;
            // v5.36: single-form spot — the service writes both
            // forms only when the other form resolves at write
            // time, so a talk half-heard as a library file with
            // no dlMap entry holds its spot under the path form
            // only; once it stops being the latest talk the
            // global match above no longer covers it and the
            // stream-form lookup just missed, so it restarted
            // from 0. savedLocal is already computed above for
            // the global match (same file-existence checks, no
            // new cost), so try the path form too before giving
            // up. The drawer/Talks ↺ hints used to stay
            // stream-form-only for the same reason (binds run on
            // the poll and must never touch the filesystem, v1.57
            // rule); v5.59 closes that hint gap via the downloaded
            // caches instead, so hint and tap agree again.
            if (savedLocal != null) {
                int per2 = Prefs.talkPosGet(this, savedLocal);
                if (per2 > 5000) return per2;
            }
            return 0;
        } catch (Exception ignored) {}
        return 0;
    }

    // v1.42: is this talk the one the service is playing, whether it is
    // streaming or playing its downloaded file? The service's current URL
    // is the local path for offline playback, so a plain URL comparison
    // used to miss and restart the playing talk on tap.
    private boolean isCurrentTalk(String url) {
        if (service == null) return false;
        String cur = service.getCurrentUrl();
        if (cur == null) return false;
        if (cur.equals(url)) return true;
        String local = localPathFor(url);
        return local != null && local.equals(cur);
    }

    // v13.52 (audit-1373 F1 / audit-1374 F-B-F-F): the failed talk,
    // made explicit. Until now the pickers skipped a failed talk only
    // incidentally, because onError never clears currentUrl so the
    // sounding identity still matched it (audit-1374 section 2). That
    // breaks wherever the identity breaks (forward step, scan misses,
    // unbound windows). Both URL forms: the service holds the stream
    // URL for a failed stream but the local file path for a failed
    // offline play (playIndex :11706 passes local when downloaded), so
    // isFailedUrl reuses isCurrentTalk's localPathFor equivalence
    // instead of a bare equals.
    // v13.53 (audit-1374 F-D / audit-1375): the unbound leg. Bound,
    // the flag lives in the service; unbound, the service mirrors it
    // in the liveTrackError static, read exactly like the
    // isSoundingTalk mirror below: live session + live track + live
    // failure, lastKind "room", lastName == this sayadaw, then prefs
    // lastUrl names the failed talk (every play-caller writes lastUrl
    // before play(), and nothing rewrites it after a failure, so it
    // stays the failed talk until the next play clears the flag).
    // Own-room only: a failed foreign head writes lastName == its own
    // sayadaw, so this gate returns null for it — documented gap, the
    // foreign leg needs the owner threaded through isFailedUrl.
    private String failedUrl() {
        if (service != null) {
            if (!service.hasTrackError()) return null;
            return service.getCurrentUrl();
        }
        if (!AudioService.liveSession || !AudioService.liveHasTrack || !AudioService.liveTrackError) return null;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (!"room".equals(sp.getString("lastKind", ""))) return null;
            if (sayadawName == null || !sayadawName.equals(sp.getString("lastName", ""))) return null;
            String last = sp.getString("lastUrl", "");
            if (last == null || last.length() == 0) return null;
            return last;
        } catch (Exception ignored) { return null; }
    }
    private boolean isFailedUrl(String u) {
        if (u == null) return false;
        String failed = failedUrl();
        if (failed == null) return false;
        if (service != null) return isCurrentTalk(u);
        // Unbound: compare u against the prefs lastUrl in both forms,
        // mirroring isSoundingTalk's legs below.
        if (failed.equals(u)) return true;
        String local = localPathFor(u);
        return local != null && local.equals(failed);
    }

    // v11.29: unbound-window sounding mirror for the two drop legs
    // below — the follow-up the v11.28 run named. Bound, this is
    // isCurrentTalk exactly, so the normal path is unchanged.
    // Unbound, isCurrentTalk is always false (service == null),
    // so a held talk that started sounding while the bind was
    // pending/dropped passed the drop guard and was re-inserted
    // before the tapped row, replaying after the next talk — the
    // library's v11.27/v11.28 defect, in Room form. The mirror
    // reads the live session only: liveSession + liveHasTrack
    // (a stopped or never-started session reads false), lastKind
    // "room" (a library session never mirrors into the Room),
    // and lastName == this sayadaw (a foreign session never
    // mirrors either) — NOT the ungated lastUrl fallback of
    // soundingUrlForQueue, which would over-fire on a dead
    // session's stale lastUrl or on a foreign session. Offline
    // equivalence is kept: lastUrl may hold the stream URL or
    // the downloaded file path, so both spellings are matched,
    // exactly like isCurrentTalk's localPathFor leg.
    private boolean isSoundingTalk(String url) {
        if (service != null) return isCurrentTalk(url);
        if (!AudioService.liveSession || !AudioService.liveHasTrack) return false;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (!"room".equals(sp.getString("lastKind", ""))) return false;
            if (sayadawName == null || !sayadawName.equals(sp.getString("lastName", ""))) return false;
            String last = sp.getString("lastUrl", "");
            if (last == null || last.length() == 0) return false;
            if (last.equals(url)) return true;
            String local = localPathFor(url);
            return local != null && local.equals(last);
        } catch (Exception ignored) { return false; }
    }

    // v11.32: foreign-aware unbound-window sounding mirror for the
    // foreign dialog's play-next tap-time check below — the
    // follow-up the v11.31 run named. Bound, this is
    // isCurrentTalk exactly, so the normal path is unchanged
    // (stream or downloaded-file form, any session kind).
    // Unbound, the v11.29 mirror must NOT be reused: it requires
    // lastName == this Room's sayadaw, so a foreign session never
    // mirrors and a foreign row that started sounding while its
    // dialog sat open passed the v11.12 tap-time check, was moved
    // before the first waiting entry and replayed after the next
    // talk — the library v11.27 / Room v11.29 defect, in foreign
    // form. A foreign talk sounding in its own Room writes
    // lastKind "room" + lastName = its own sayadaw, so this
    // mirror takes the row's owner (ffe.sayadaw) instead of
    // sayadawName, with the same liveSession + liveHasTrack +
    // lastUrl (stream or downloaded-file form) gates. A library
    // session is deliberately NOT mirrored: it never writes
    // lastName, so its lastName pref is stale and cannot prove
    // ownership — bound, isCurrentTalk still catches a
    // library-sounding foreign file.
    private boolean isSoundingForeignTalk(String url, String ownerSayadaw) {
        if (service != null) return isCurrentTalk(url);
        if (!AudioService.liveSession || !AudioService.liveHasTrack) return false;
        if (ownerSayadaw == null || ownerSayadaw.length() == 0) return false;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (!"room".equals(sp.getString("lastKind", ""))) return false;
            if (!ownerSayadaw.equals(sp.getString("lastName", ""))) return false;
            String last = sp.getString("lastUrl", "");
            if (last == null || last.length() == 0) return false;
            if (last.equals(url)) return true;
            String local = localPathFor(url);
            return local != null && local.equals(last);
        } catch (Exception ignored) { return false; }
    }

    // v11.34: held-row sounding mirror for the three drop legs
    // (foreign, in-list, header) — isSoundingTalk unbound mirrors
    // only a live room session for THIS Room's sayadaw, so a FOREIGN
    // held row (armed from the foreign dialog, or restored across
    // rotation) sounding in its own Room passed all three drop
    // guards unbound and was re-inserted before the tapped row,
    // replaying after the next talk — the v11.27-v11.33 defect in
    // foreign form. Bound, isSoundingTalk IS isCurrentTalk, so the
    // normal path is unchanged and the foreign leg is never reached.
    // Unbound, the held line's own owner (v2 "url⇥sayadaw⇥title")
    // feeds the v11.32 foreign mirror; a bare-URL (v1) held line has
    // no owner, so it falls back to the own mirror exactly. A
    // library session is deliberately NOT mirrored (v11.32 limit:
    // no lastName written, ownership unprovable); bound,
    // isCurrentTalk still catches it.
    private boolean isSoundingHeldUrl(String url) {
        if (isSoundingTalk(url)) return true;
        int li = queueLineIndexForUrl(url);
        if (li < 0) return false;
        QueueEntry held = QueueEntry.parse(queueUrls.get(li));
        if (held == null || held.sayadaw == null || held.sayadaw.length() == 0) return false;
        return isSoundingForeignTalk(url, held.sayadaw);
    }

    // Every talk change picks a different photorealistic shrine photo,
    // unless the settings toggle (v1.40) froze the opening photo.
    // v12.50: returns whether a roll actually happened, so
    // rollThemeForTalk can record the owning talk only on a real
    // roll (autoTheme-off and the one-photo guard roll nothing).
    private boolean pickTheme() {
        if (!Prefs.autoTheme(this)) return false;
        // v1.89: the re-roll loop never exits when there is only one
        // photo to pick from — today there are 4, but a future photo
        // removal would hang the UI thread on every talk change.
        // With one (or zero) photos there is nothing to switch to.
        if (RoomView.PHOTO_COUNT <= 1) return false;
        int idx = themeIndex;
        while (idx == themeIndex) idx = themeRandom.nextInt(RoomView.PHOTO_COUNT);
        // v12.49: adopt the theme actually showing (see
        // RoomView.setPhoto) — a failed decode falls back, so
        // recording the requested idx let the next roll exclude a
        // theme that was not showing and land on the one already
        // showing (08:28 +07 altar audit, Finding 3).
        themeIndex = roomView.setPhoto(idx);
        return true;
    }

    // v12.50: one talk, one altar — the theme is rolled per talk
    // change, not per play call (08:28 +07 altar audit, Finding 4).
    // A same-talk replay/resume (Play after a stop, restart in
    // place, resume from the saved spot, error retry, repeat-one
    // unbound) keeps the altar it already owns; a different talk
    // still rolls, as does a first play (themeTalkUrl is null until
    // the first roll, so the fresh-open hour photo never counts as
    // owned). Identity is the talk URL, not the index, so a list
    // refetch that shifts the talk's row still keeps its altar.
    private void rollThemeForTalk(String url) {
        if (url != null && url.equals(themeTalkUrl)) return;
        if (pickTheme()) themeTalkUrl = url;
    }

    // v12.51: the theme roll, deferred until playback is confirmed
    // (08:28 +07 altar audit, Finding 5). Until v12.50 the three
    // roll sites called rollThemeForTalk directly — playIndex at tap
    // time, before service.play() was even called, and the two
    // correctors on a scan that only proves getCurrentUrl() names
    // the talk (play() sets it before prepareAsync(), so a
    // still-buffering talk already passes). A stream that then
    // failed left the NEW altar showing with silence under it
    // (play() reset()s the old player first). A roll is now
    // requested, not taken: when the talk is already confirmed
    // sounding it rolls at once (the correctors' common case — a
    // background advance that finished long ago — keeps today's
    // immediacy); otherwise the talk waits in pendingThemeUrl and
    // the poll confirms it. A same-talk replay/resume still
    // no-ops inside rollThemeForTalk (v12.50 guard), and a
    // pre-bind tap (service null) simply pends until the bind
    // replay plays it.
    private void requestThemeForTalk(String url) {
        if (isThemeConfirmed(url)) rollThemeForTalk(url);
        else pendingThemeUrl = url;
    }

    // v12.51: is this talk confirmed sounding — bound, the
    // service's current talk (stream or offline-file form via
    // isCurrentTalk), prepared (not still buffering — a buffering
    // talk has set its URL but not yet sounded), and not failed.
    // A prepared-but-held talk (pause tapped while buffering, or
    // a gate-refused resume) counts: its stream loaded and it
    // sounds on resume, while the old talk is already gone.
    private boolean isThemeConfirmed(String url) {
        return service != null && isCurrentTalk(url)
                && !service.isBuffering() && !service.hasTrackError();
    }

    // v12.51: the poll's confirmation of a pending theme roll —
    // called on every tick while the service is bound. A pending
    // talk that prepared rolls now (its first sounding tick);
    // a failed stream drops it without rolling, so the altar
    // stays with the talk actually heard (or the silence); a
    // session that moved on (another talk sounding, or no track
    // after a stop) drops it too — the corrector for the new
    // talk requests that one instead. While still buffering the
    // pending talk waits for a later tick; autoTheme-off and the
    // v12.50 same-talk guard both clear the pending without a
    // roll, exactly as a direct roll would have.
    private void maybeRollPendingTheme() {
        if (pendingThemeUrl == null) return;
        if (!service.hasTrack() || !isCurrentTalk(pendingThemeUrl)
                || service.hasTrackError()) {
            pendingThemeUrl = null;
            return;
        }
        if (service.isBuffering()) return;
        rollThemeForTalk(pendingThemeUrl);
        pendingThemeUrl = null;
    }

    // v4.05: instant download-landing rescan — the 30s poll rescan
    // (v1.69, v3.08 fade, v4.01 prune) is now a method so the
    // download-complete receiver can run the exact same diff on the
    // moment a download lands, instead of up to 30s later. The poll
    // cadence stays as the backstop (external deletes, missed
    // broadcasts); the method itself is unchanged behaviour.
    private void runDownloadRescan() {
        // v8.51: generation guard for the posted newly-downloaded
        // row fade below — a download landing just before a
        // sayadaw switch (handover / retry reload) used to post a
        // fade keyed by row INDEX, and the post ran after the new
        // list had laid out, so the fade lit the wrong row (a
        // different talk that merely sat at the same position).
        // loadSeq increments on every loadTalks, so a stale post
        // drops itself; the drawer-open check alone could not see
        // the switch, since the drawer stays open across it.
        final int rescanSeq = loadSeq;
        java.util.Set<String> before = new java.util.HashSet<String>(downloadedFiles);
        final java.util.List<Integer> newlyDl = new java.util.ArrayList<Integer>();
        if (talks != null) {
            boolean[] wasDl = new boolean[talks.size()];
            for (int i = 0; i < talks.size(); i++) wasDl[i] = isDownloadedFile(talks.get(i).url);
            refreshDownloadedSet();
            for (int i = 0; i < talks.size(); i++)
                if (!wasDl[i] && isDownloadedFile(talks.get(i).url)) newlyDl.add(i);
        } else {
            refreshDownloadedSet();
        }
        // v4.01: the same rescan prunes dead queue entries,
        // so a failed/cancelled queue drops its ⏳ on the
        // same cadence the ⬇ mark arrives on — and a pruned
        // entry rebinds the rows even when the downloaded
        // set itself did not change.
        // v10.16: paused-card download-mark sync, rescan prune-drop leg of
        // v10.06/v10.14 — the rescan below prunes dead queue entries and
        // rebinds the rows, but the card is refreshed only by the v10.06
        // leg, which gates on a SUCCESSFUL Dhamma/ landing. A job that
        // FAILS or is cancelled/cleared still fires this rescan (completion
        // broadcast, or the next 30s poll) and its entry is pruned, so the
        // rows lose their hourglass at once while a PAUSED sounding talk
        // keeps it on the card until the next tap (rebuilds run every 15s
        // only while playing, never while paused). Snapshot prefs dlQueued
        // before the prune; refresh only when THIS Room's current talk
        // (v10.14 guard shape) was dropped, so unrelated prunes never
        // re-post the card, and a rebuild with no mark change paints the
        // identical card. refreshNotification is a no-op with no track.
        java.util.Set<String> queuedBefore1016 = new java.util.HashSet<String>();
        try {
            org.json.JSONArray qb1016 = new org.json.JSONArray(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlQueued", "[]"));
            for (int i1016 = 0; i1016 < qb1016.length(); i1016++)
                queuedBefore1016.add(qb1016.optString(i1016));
        } catch (Exception ignored) {}
        boolean queuedChanged = pruneQueuedDownloads();
        // v10.30: unbound leg of the v10.16 rescan prune-drop sync — the
        // v10.16 block above refreshed the card only when bound, but the
        // rescan runs from the 30s poll and the completion receiver, both
        // of which can fire in the two unbound windows (before
        // onServiceConnected fires, after service death while the Room
        // lives on — same shape as the v10.27/v10.28/v10.29 legs), so a
        // FAILED/cancelled job pruned by the rescan in either window left
        // a PAUSED card showing ⏳ for a talk that had already lost its
        // queue entry until the next tap (the v10.29 receiver leg covers
        // only successful landings, never this prune path). The drop
        // test is computed first, byte-identical to the bound leg's
        // gate; the bound path is unchanged, and the unbound path sends
        // the no-bind ACTION_REFRESH_NOTIFICATION intent (v10.03 shape,
        // liveSession-guarded, never starts a dead service). The
        // handler stopSelfs when trackless and a rebuild with no mark
        // change paints the identical card.
        boolean droppedCur1030 = false;
        if (queuedChanged) {
            try {
                String cur1016 = currentTalkUrl();
                if (cur1016 != null && queuedBefore1016.contains(cur1016)) {
                    org.json.JSONArray qa1016 = new org.json.JSONArray(
                            getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlQueued", "[]"));
                    boolean stillQueued1016 = false;
                    for (int i1016 = 0; i1016 < qa1016.length(); i1016++)
                        if (cur1016.equals(qa1016.optString(i1016))) { stillQueued1016 = true; break; }
                    droppedCur1030 = !stillQueued1016;
                }
            } catch (Exception ignored) {}
        }
        if (droppedCur1030 && service != null) service.refreshNotification();
        else if (droppedCur1030 && AudioService.liveSession) {
            try {
                android.content.Intent rsv = new android.content.Intent(RoomActivity.this, AudioService.class);
                rsv.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                startService(rsv);
            } catch (Exception ignored) {}
        }
        if ((!before.equals(downloadedFiles) || !newlyDl.isEmpty() || queuedChanged) && adapter != null) {
            adapter.notifyDataSetChanged();
            if (!newlyDl.isEmpty() && roomList != null && isPlayListPanelOpen()) {
                roomList.post(new Runnable() {
                    @Override public void run() {
                        if (roomList == null || !isPlayListPanelOpen() || rescanSeq != loadSeq) return;
                        int first = roomList.getFirstVisiblePosition();
                        for (int idx : newlyDl) {
                            // v8.54: talk index -> display position (v4.98 pulse precedent) — in a
                            // queue-only / filter / search view the raw index fades a stranger's row.
                            int dp54 = drawerPosOfTalk(idx);
                            android.view.View row = dp54 >= 0 ? roomList.getChildAt(dp54 - first) : null;
                            if (row != null) Fx.fadeIn(row, 250);
                        }
                    }
                });
            }
        }
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            // v1.69: mid-session download rescan (see pollCount) — a
            // Wi-Fi download queued from the data gate used to leave
            // its row unmarked until the Room was left and re-entered.
            // v3.08: premium newly-downloaded row fade — the rescan
            // used to snap the fresh ⬇ mark onto its row in the same
            // frame as the rebind, while every other Room change
            // already moves (v2.07 morph + glow, v2.10 drawer pour,
            // v2.27 playing pulse, v2.32 talk-change glide), so a
            // download landing mid-session flashed its mark with no
            // motion at all. Rows that gain the mark in this rescan
            // now fade in (existing Fx.fadeIn, 250ms, alpha-only)
            // once the rebind has laid out, but only while the
            // drawer is actually open — a closed drawer has no
            // visible rows to fade, and its next open pours anyway
            // (v2.10). The per-talk diff replaces the old set-only
            // check for the notify too: a talk can gain its mark
            // through the URL->filename map alone, with the raw
            // filename set unchanged, and the old check missed it.
            // Guards: the fade is posted after the rebind's layout
            // pass, so the index->child mapping is read fresh at
            // fade time; a row scrolled out by then simply has no
            // child and is skipped; the fade touches the row view
            // only, never the title's own pulse animator (v2.27);
            // a detach before the post runs just drops the fade,
            // leaving no state behind. Visual only; the rescan,
            // the mark rule and the 30s cadence are untouched.
            if (++pollCount % 60 == 0) runDownloadRescan();
            if (service != null) {
                // v12.51: confirm a pending theme roll first (see
                // maybeRollPendingTheme) — a talk tapped since the
                // last tick rolls on the tick its stream prepares,
                // and a failed one drops without ever changing the
                // altar. Runs before the title/resync branches below,
                // so a resync in this same tick can only request the
                // talk the session actually moved to.
                maybeRollPendingTheme();
                boolean playing = service.isPlaying();
                // v1.79: the Play/Pause label used to follow
                // isPlaying() only — while a stream was still
                // buffering it read ▶ ဖွင့်, but tapping it held
                // the incoming talk paused (v1.69 hold), so the
                // label promised play and the tap did the opposite.
                // A buffering talk that will play is active (⏸);
                // only a held one reads ▶, like the card (v1.79).
                boolean active = playing
                        || (service.isBuffering() && !service.isHoldPaused());
                // v11.16: rebind the drawer rows exactly when
                // the ended state flips — a talk ending while
                // the Room sits on top must drop its ⏸ mark
                // within a tick, not on the next scroll; a
                // replay clears liveEnded in play(), so the
                // mark returns the same way. Steady ticks
                // never touch the adapter.
                if (AudioService.liveEnded != lastPollEnded) {
                    lastPollEnded = AudioService.liveEnded;
                    if (adapter != null) adapter.notifyDataSetChanged();
                }
                // v1.81: the shrine flames follow the same active
                // rule as the button — a talk buffering towards play
                // lights them like a playing one, instead of sitting
                // dark behind a ⏸ ရပ် label until prepare finishes.
                // v4.11: the altar lamps learn the sleep fade too —
                // the v4.04 bedtime fade lowered the volume over ~3s
                // but the shrine stayed fully lit until the fade-end
                // pause snapped it dark. setSleepFading runs before
                // setPlaying for the same reason as the wave's
                // setBuffering/setDucked/setSleepFading above, so
                // the lamps dim out with the sound; change-only, so
                // a steady state never re-animates on the 500ms tick,
                // and a cancelled fade relights them via the same
                // ramp. Pause never sets the flag, so the normal
                // pause dim is untouched.
                roomView.setSleepFading(service.isSleepFading());
                roomView.setPlaying(active);
                // v2.52: the wave seek ripples on the same active
                // rule (buffering towards play counts as playing,
                // like the flames) — the poll re-arms it on return,
                // and setPlaying is idempotent, so the 500ms tick
                // never restarts the ripple mid-breath.
                // v4.07: the wave also learns the stall itself —
                // setBuffering runs before setPlaying so a play
                // starting into a buffer ramps straight to the
                // calm amplitude instead of flashing full ripple;
                // both calls are change-only, so a steady state
                // never re-animates on the 500ms tick.
                if (seekBar != null) seekBar.setBuffering(service.isBuffering() && !service.isHoldPaused());
                // v4.08: the wave also learns the duck — setDucked
                // runs before setPlaying for the same reason as
                // setBuffering above, so a play starting ducked
                // ramps straight to the duck calm; both setters are
                // change-only, so a steady state never re-animates
                // on the 500ms tick, and a pause records the flag
                // without moving the frozen wave.
                if (seekBar != null) seekBar.setDucked(service.isDucked());
                // v4.09: the wave also learns the sleep fade — the
                // v4.04 bedtime fade lowers the volume to silence
                // over ~3s, but the wave danced at full ripple
                // until the fade-end pause froze it. setSleepFading
                // runs before setPlaying for the same reason as
                // setBuffering/setDucked above, so the wave dies
                // down with the fade instead of flashing full
                // ripple first; change-only, so a steady state
                // never re-animates on the 500ms tick, and the
                // fade-end pause freezes the nearly-flat wave
                // exactly where the sound died.
                if (seekBar != null) seekBar.setSleepFading(service.isSleepFading());
                // v4.10: the wave also learns a failed stream —
                // trackError makes the poll read active=false, so
                // setPlaying(false) used to freeze the wave
                // mid-ripple exactly like a pause, and only the
                // toast said it had failed. setStreamError runs
                // before setPlaying for the same reason as
                // setBuffering/setDucked/setSleepFading above, so
                // the failing tick eases the wave flat instead of
                // freezing it; change-only, so a steady state
                // never re-animates on the 500ms tick, and a
                // retry clears the flag in the service before
                // the next play ramps the wave back up.
                if (seekBar != null) seekBar.setStreamError(service.hasTrackError());
                if (seekBar != null) seekBar.setPlaying(active);
                // v4.25: the sleep timer finally reads in the Room —
                // it was set in Settings and counted down invisibly
                // (only the Settings button showed it, v4.12), so a
                // listener mid-talk had no idea bedtime was near.
                // The clock row's centred sleep view (GONE unless a
                // timer is armed, fading or pause-suspended) now
                // shows the live countdown on this same 500ms poll:
                // "😴 mm:ss" while armed, "😴 ⏸ mm:ss" while the
                // talk's pause holds the countdown still, and
                // "😴 ရပ်တော့မယ်" through the 3s sleep fade itself
                // (the service's own fire toast still names the end).
                // Under a minute left the digits turn gold, like a
                // last-minute cue. The suspended read is guarded by
                // the Settings default, so a remainder orphaned by
                // a turned-off setting never shows as live. Text is
                // change-only (a steady minute never rewrites), and
                // the view fades in once on its GONE -> VISIBLE
                // transition instead of popping (Fx.fadeIn, 250ms);
                // later ticks never re-fade. The control row is
                // untouched — Forward | Play/Pause | Next + drawer
                // toggle, no new button anywhere.
                if (sleepText != null) {
                    try {
                        long sleepRem = service.getSleepRemainingMs();
                        long sleepSusp = service.getSleepSuspendedMs();
                        boolean sleepFading = service.isSleepFading();
                        String sleepWant = null;
                        boolean sleepGold = false;
                        if (sleepFading) sleepWant = "😴 " + Lang.t(RoomActivity.this, "notif_sleep_fading");
                        // v4.82: the one-shot has no countdown — name it.
                        else if (service.isSleepEndOfTalk()) sleepWant = "😴 " + Lang.t(RoomActivity.this, "sleep_end");
                        else if (sleepRem > 0) {
                            sleepWant = "😴 " + formatTime((int) sleepRem);
                            sleepGold = sleepRem < 60000;
                        } else if (sleepSusp > 0 && Prefs.sleepMinutes(RoomActivity.this) > 0) {
                            sleepWant = "😴 ⏸ " + formatTime((int) sleepSusp);
                        }
                        if (sleepWant == null) {
                            if (sleepText.getVisibility() != android.view.View.GONE)
                                sleepText.setVisibility(android.view.View.GONE);
                        } else {
                            boolean wasGone = sleepText.getVisibility() == android.view.View.GONE;
                            if (wasGone) sleepText.setVisibility(android.view.View.VISIBLE);
                            if (!sleepWant.equals(sleepText.getText().toString()))
                                sleepText.setText(sleepWant);
                            int wantColor = sleepGold
                                    ? android.graphics.Color.parseColor("#C9A227")
                                    : android.graphics.Color.parseColor("#AAAAAA");
                            if (sleepText.getCurrentTextColor() != wantColor)
                                sleepText.setTextColor(wantColor);
                            if (wasGone) Fx.fadeIn(sleepText, 250);
                        }
                    } catch (Exception ignored) {}
                }
                // v5.18: the Room speed label follows the live default
                // speed pref on this same 500ms poll, change-only, so
                // a speed change made in Settings or on a strip lands
                // in the Room without a leave/return. The tap itself
                // already refreshes the label at once; this covers
                // every other route. Numeric label, no Lang key.
                if (roomSpeedBtn != null) {
                    String spdWant = Fx.speedLabel(Prefs.defaultSpeed(RoomActivity.this));
                    if (!spdWant.equals(roomSpeedBtn.getText().toString()))
                        roomSpeedBtn.setText(spdWant);
                }
                // v3.20: the per-tick label write must not clobber a
                // dip mid-flight — it would swap the glyph early at
                // whatever alpha the dip had reached. While a dip
                // runs, its own end action owns the label; every
                // other tick writes the same label anyway.
                if (playGlyphFade == null) btnPlayPause.setImageResource(active ? R.drawable.ic_pause : R.drawable.ic_play); // v7.28: vector icon, play/pause rule unchanged (playing=pause icon)
                // v2.07: the button morphs on every play/pause flip
                // (quick squash + overshoot back) and a gold ring
                // glows around it while playing. The glow is
                // state-driven, so a resumed Room re-arms it from
                // the poll instead of depending on the flip.
                if (active != lastPollActive) {
                    lastPollActive = active;
                    final int newIcon = active ? R.drawable.ic_pause : R.drawable.ic_play; // v7.28: vector icon
                    final int gen = ++playGlyphGen; // BEFORE the cancel (v3.19 lesson)
                    if (playGlyphFade != null) { playGlyphFade.cancel(); playGlyphFade = null; }
                    // v3.35: morph-restart continuity — the squash
                    // used to setScale straight to 0.86, so a rapid
                    // second flip mid-spring snapped the button in
                    // one frame (same class as the v3.34 nudge snap).
                    // The dip now animates from the current scale to
                    // 0.86 over 70ms (decelerate) and only then springs
                    // back over 220ms with the old overshoot, so an
                    // idle flip reads exactly as before and a
                    // mid-flight flip flows with no jump. The gen is
                    // bumped BEFORE the cancel (v3.19 lesson), and a
                    // stale dip end action returns without rising.
                    final int morphGen = ++playMorphGen;
                    Fx.cancelPress(btnPlayPause); // v3.90: press squash is an ObjectAnimator now — the ViewPropertyAnimator cancel below cannot stop it, or it would fight the morph for scale.
                    btnPlayPause.animate().cancel();
                    btnPlayPause.animate().setListener(null);
                    if (btnPlayPause.getScaleX() <= 0.86f) {
                        btnPlayPause.animate().scaleX(1f).scaleY(1f).setDuration(220)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
                    } else {
                        btnPlayPause.animate().scaleX(0.86f).scaleY(0.86f).setDuration(70)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                .setListener(new android.animation.AnimatorListenerAdapter() {
                                    @Override public void onAnimationEnd(android.animation.Animator a) {
                                        if (morphGen != playMorphGen) return;
                                        btnPlayPause.animate().setListener(null);
                                        btnPlayPause.animate().scaleX(1f).scaleY(1f).setDuration(220)
                                                .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
                                    }
                                }).start();
                    }
                    // v3.20: glyph crossfade — dip to 0 over 90ms
                    // (inside the 220ms morph), swap at the bottom,
                    // rise back over 150ms. A second flip mid-dip
                    // cancels the old dip; its end action sees the
                    // stale gen and returns without swapping, and
                    // the new dip starts from the current alpha.
                    playGlyphFade = android.animation.ObjectAnimator.ofFloat(btnPlayPause, "alpha", btnPlayPause.getAlpha(), 0f);
                    playGlyphFade.setDuration(90);
                    playGlyphFade.setInterpolator(new android.view.animation.DecelerateInterpolator());
                    playGlyphFade.addListener(new android.animation.AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(android.animation.Animator a) {
                            if (gen != playGlyphGen) return;
                            btnPlayPause.setImageResource(newIcon);
                            playGlyphFade = android.animation.ObjectAnimator.ofFloat(btnPlayPause, "alpha", 0f, 1f);
                            playGlyphFade.setDuration(150);
                            playGlyphFade.setInterpolator(new android.view.animation.DecelerateInterpolator());
                            playGlyphFade.addListener(new android.animation.AnimatorListenerAdapter() {
                                @Override public void onAnimationEnd(android.animation.Animator b) {
                                    if (gen != playGlyphGen) return;
                                    playGlyphFade = null;
                                    btnPlayPause.setAlpha(1f);
                                }
                            });
                            playGlyphFade.start();
                        }
                    });
                    playGlyphFade.start();
                }
                if (playGlow != null) {
                    if (active) {
                        // v2.33: a play flip during a pause fade
                        // cancels that fade instead of letting its
                        // end action hide a ring that is playing
                        // again; the flag guard in the end action
                        // below is the second half of this.
                        // v3.16: premium glow-scale settle — the
                        // cancel above used to fall straight through
                        // to the pulse loop, whose scale keyframes
                        // start at exactly 1f, so a ring paused
                        // mid-breath (scale up to 1.08, frozen when
                        // the loop was cancelled) snapped back to
                        // 1f in one frame on resume. Instead the
                        // ring now settles: alpha glides to the
                        // loop floor (0.3) and scale glides to 1f
                        // over 200ms, and glowFadingIn stays true
                        // until the settle ends, so the poll skips
                        // the loop start this tick and starts it
                        // seamlessly on the next tick. A pause
                        // during the settle cancels it (pause
                        // branch clears glowFadingIn) and fades
                        // out from wherever it sits.
                        if (glowFadingOut) {
                            // v3.68: premium glow fade-out end-action
                            // guard — the fade-out end below was
                            // guarded only by the glowFadingOut
                            // flag, and this cancel runs BEFORE the
                            // flag clears, so under the v3.19
                            // behaviour (a cancelled animator still
                            // fires its end action) the stale end
                            // hid the ring (GONE, alpha 1) mid-
                            // resume: the settle then animated an
                            // invisible view and the next tick's
                            // fade-in popped it back from 0 instead
                            // of gliding from where the fade-out
                            // sat. Bumping the generation BEFORE
                            // the cancel (v3.67 ordering) plus the
                            // gen guard on the fade-out end below
                            // leaves the ring visible for the
                            // settle, so a pause->resume inside
                            // the 300ms fade-out now settles
                            // smoothly to 0.3/1 instead of
                            // vanishing and popping back in.
                            // Visual only; targets, durations and
                            // the pulse-loop handover untouched.
                            ++glowFadeGen;
                            playGlow.animate().cancel();
                            glowFadingOut = false;
                            glowFadingIn = true;
                            final int settleGen = glowFadeGen;
                            playGlow.animate().alpha(0.3f).scaleX(1f).scaleY(1f).setDuration(200)
                                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                    .withEndAction(new Runnable() {
                                        @Override public void run() {
                                            if (settleGen != glowFadeGen) return;
                                            glowFadingIn = false;
                                        }
                                    }).start();
                        }
                        if (playGlow.getVisibility() != View.VISIBLE) {
                            // Fade in from 0 to the pulse loop's
                            // floor (0.3) over 350ms, then let the
                            // poll start the loop — the loop's
                            // first value is 0.3, so the handover
                            // is seamless, and the ring no longer
                            // pops in beside the 600ms altar ramp.
                            playGlow.setVisibility(View.VISIBLE);
                            playGlow.setScaleX(1f); playGlow.setScaleY(1f);
                            playGlow.setAlpha(0f);
                            glowFadingIn = true;
                            final int glowFadeInGen = ++glowFadeGen;
                            playGlow.animate().alpha(0.3f).setDuration(350)
                                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                    .withEndAction(new Runnable() {
                                        @Override public void run() {
                                            if (glowFadeInGen != glowFadeGen) return;
                                            glowFadingIn = false;
                                        }
                                    }).start();
                        } else if (glowPulse == null && !glowFadingIn) {
                            glowPulse = android.animation.ObjectAnimator.ofFloat(playGlow, "alpha", 0.3f, 0.7f);
                            glowPulse.setDuration(1800);
                            glowPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                            glowPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                            glowPulse.start();
                            // v2.20: the ring also breathes in size
                            // (1.00-1.08, same 1800ms reverse loop), so
                            // the glow swells instead of only fading.
                            // The 64dp ring in its fixed 72dp frame
                            // grows to ~69dp, so it never clips or
                            // shifts the controls row.
                            glowScale = android.animation.ObjectAnimator.ofPropertyValuesHolder(playGlow,
                                    android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 1.08f),
                                    android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 1.08f));
                            glowScale.setDuration(1800);
                            glowScale.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                            glowScale.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                            glowScale.start();
                        }
                    } else {
                        // v2.33: fade out over 300ms instead of
                        // vanishing in one frame. The poll skips
                        // this branch while glowFadingOut is true,
                        // so it cannot restart the fade every
                        // 500ms tick and strand the ring visible.
                        if (playGlow.getVisibility() == View.VISIBLE && !glowFadingOut) {
                            if (glowPulse != null) { glowPulse.cancel(); glowPulse = null; }
                            if (glowScale != null) { glowScale.cancel(); glowScale = null; }
                            // v3.68: capture the generation BEFORE
                            // the cancel (see the settle above) so
                            // the end action below is gen-guarded
                            // like the fade-in/settle ends (v3.66).
                            final int fadeOutGen = ++glowFadeGen;
                            playGlow.animate().cancel();
                            glowFadingIn = false;
                            glowFadingOut = true;
                            // v3.16: the scale glides home with the
                            // alpha (the loop cancel above used to
                            // freeze it up to 1.08 and the end action
                            // snapped it to 1f while hidden — invisible
                            // alone, but it is exactly the snap the
                            // resume settle above has to undo).
                            playGlow.animate().alpha(0f).scaleX(1f).scaleY(1f).setDuration(300)
                                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                    .withEndAction(new Runnable() {
                                        @Override public void run() {
                                            if (fadeOutGen != glowFadeGen || !glowFadingOut) return;
                                            glowFadingOut = false;
                                            playGlow.setScaleX(1f); playGlow.setScaleY(1f);
                                            playGlow.setAlpha(1f);
                                            playGlow.setVisibility(View.GONE);
                                        }
                                    }).start();
                        }
                    }
                }
                // v1.57: the Room's own status line (loading / "N talks"
                // / load-error) used to be overwritten every 500ms by
                // the title of whatever other sayadaw's talk was still
                // playing in the service — the count and the error
                // message vanished behind it. Only show the service
                // title when the playing talk is in this Room's list.
                if (service.getCurrentTitle() != null && currentIndex >= 0
                        && currentIndex < talks.size()
                        && isCurrentTalk(talks.get(currentIndex).url)) {
                    // v1.79: a buffering talk used to read ⏸ here
                    // like a paused one — say it is loading, like
                    // the notification card has since v1.73.
                    // v2.47: title-line icon follows the action rule —
                    // playing shows ⏸, paused shows ▶, like the button.
                    // v2.70: premium poll icon-flip title pour — the
                    // last snap in the status-line series (v2.66/
                    // v2.67/v2.68/v2.69 poured every other line). A
                    // play/pause tap, or a stream starting to buffer,
                    // used to flip this line's icon in one frame while
                    // the title stayed put; it now fades + rises in
                    // with the same rhythm (320ms, 12dp rise,
                    // decelerate, ending at exactly alpha 1 / offset
                    // 0). Runs once per change: the poll rewrites
                    // this same line every 500ms, so the pour fires
                    // only when the new line actually differs — a
                    // talk-change title already poured by v2.22
                    // writes the identical line here and never
                    // double-animates. Visual only; icon rule and
                    // title text untouched, and the v2.66 onPause/
                    // onDestroy reset already cancels this view's
                    // animator if the Room is left mid-pour.
                    // v4.15: downloaded-track mark on the title
                    // (Spotify marks saved tracks) — ⬇ when the
                    // playing talk is already saved offline, so the
                    // listener sees at a glance that no mobile data
                    // is being spent. isDownloadedFile reads the
                    // in-memory set (v1.57), never the filesystem,
                    // so the 500ms poll stays cheap; the mark sits
                    // after the ▶/⏸ icon, so the notification-stop
                    // clear branch below still matches its prefixes.
                    String dlMark = (currentIndex >= 0 && currentIndex < talks.size()
                            && isDownloadedFile(talks.get(currentIndex).url)) ? "⬇ " : "";
                    // v4.16: queue position inline in the existing
                    // now-playing line ("⏸ 3 / 24 ⬇ title") — the
                    // streaming queue cue the v1.37 simplification
                    // removed as its own line, back as a prefix on
                    // the line that already exists. No new view; the
                    // notification-stop clear branch still matches
                    // its ▶/⏸ prefixes, and count lines are untouched.
                    String queuePos = (currentIndex >= 0 && !talks.isEmpty())
                            ? (currentIndex + 1) + " / " + talks.size() + " " : "";
                    // v4.26: favourite mark on the now-playing line -
                    // the title gained its offline mark in v4.15 but
                    // never said the sounding talk was a favourite,
                    // even though the drawer rows (v1.44) and the
                    // library rows (v4.24) both carry its star. Same
                    // in-memory favSet the rows use, so the 500ms
                    // poll stays cheap; an altar long-press favour
                    // (v4.19) shows here within one tick, and a
                    // clear-favs return clears it the same way.
                    String favMark = (currentIndex >= 0 && currentIndex < talks.size()
                            && favSet.contains(talks.get(currentIndex).url)) ? "★ " : "";
                    // v4.27: the sounding talk's own download
                    // progress ("⏳ 42% ") — ⬇ already owns a
                    // saved talk, so this fires only while its
                    // file is still landing; a queued talk with
                    // no known total reads a bare ⏳, like the
                    // drawer rows (v4.24). Same dlProgress map
                    // the rows use, so the poll stays cheap.
                    String progMark = "";
                    if (currentIndex >= 0 && currentIndex < talks.size()
                            && !isDownloadedFile(talks.get(currentIndex).url)) {
                        Integer pct = dlProgress.get(talks.get(currentIndex).url);
                        if (pct != null) progMark = "⏳ " + pct + "% ";
                        else if (queuedDownloads.contains(talks.get(currentIndex).url)) progMark = "⏳ ";
                    }
                    // v4.29: a finished talk used to read exactly
                    // like a paused one on this line (▶ + title) —
                    // the listener back in the Room could not tell
                    // the talk had ended. The ended suffix lands
                    // on the next 500ms poll after completion; a
                    // replay or auto-advance clears the service
                    // flag before the next poll, so it never
                    // sticks to the following talk. Prefixes
                    // untouched, so the notification-stop clear
                    // branch still matches.
                    String endedSuf = (!playing && service.hasTrackEnded())
                            ? " · " + Lang.t(RoomActivity.this, "notif_state_ended") : "";
                    String pollLine = (playing ? "⏸ "
                            : (service.isBuffering() && !service.isHoldPaused()
                                    ? Lang.t(RoomActivity.this, "notif_state_buffering") + " " : "▶ "))
                            + queuePos + dlMark + progMark + favMark + service.getCurrentTitle() + endedSuf;
                    // v13.40: status-line guards (M9/F5 class, poll/tick +
                    // loadTalks/playTalk/bind) — nowPlaying is a field assigned
                    // from findViewById in onCreate, so a missing roomNowPlaying
                    // id leaves it null and every bare setText/getText/animate
                    // below NPEd: the 500ms poll tick died within a second of
                    // opening the Room, and a load/play/bind died on its status
                    // write. All nine setText sites + their pour blocks are now
                    // guarded; a missing status line is simply never written.
                    // All ids exist in activity_room.xml today, so no live
                    // behaviour changes. The notification-stop read below uses
                    // a null-safe ternary, so that branch becomes a no-op
                    // instead of crashing; the rider guards the three onCreate
                    // sites v13.39 missed (btnRetry long-click + Fx.press,
                    // btnPlayPause Fx.press).
                    if (nowPlaying != null && !pollLine.equals(nowPlaying.getText().toString())) {
                        nowPlaying.setText(pollLine);
                        // v3.49: pour-restart continuity (v3.37 rule, extended) — a settled
                        // view still pours visibly from 0/12dp; a mid-pour view flows on
                        // from its current alpha/offset instead of snapping back.
                        nowPlaying.animate().cancel();
                        if (nowPlaying.getAlpha() >= 0.999f && nowPlaying.getTranslationY() <= 0.5f) {
                            nowPlaying.setAlpha(0f);
                            nowPlaying.setTranslationY(12f * getResources().getDisplayMetrics().density);
                        }
                        nowPlaying.animate().alpha(1f).translationY(0f).setDuration(320)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    }
                }
                // v1.75: stale row — the service track changed
                // elsewhere (e.g. a downloaded file started from the
                // library while this Room sat in the back stack), so
                // the gold ▶ row no longer names anything playing.
                // Clear it instead of leaving a lie on the list; the
                // title line goes back to the count line with it.
                // v7.59: but first re-sync — a v7.58 background
                // advance moves the service to the NEXT talk in
                // THIS list while this Room sits in the back
                // stack, and the bind/load syncs above only run
                // on a fresh bind or a fresh list load, never on
                // a back-stack return, so this poll branch was
                // the only reader left and it cleared the index
                // outright: the returning Room showed the count
                // line forever while the new talk played, the
                // gold row vanished, and Next fell back to talk
                // 0 instead of the talk after the sounding one.
                // Scan for the sounding talk first (same scan
                // the bind sync and the load completion use,
                // stream or offline-file form via isCurrentTalk);
                // a hit re-points the index and rebinds, and the
                // next tick's title branch shows the new talk
                // with its queue position. A miss keeps the old
                // clear exactly as before (a library file from
                // another sayadaw is genuinely not in this list).
                else if (service.hasTrack() && currentIndex >= 0
                        && currentIndex < talks.size()
                        && !isCurrentTalk(talks.get(currentIndex).url)) {
                    int resync59 = -1;
                    for (int i = 0; i < talks.size(); i++) {
                        if (isCurrentTalk(talks.get(i).url)) { resync59 = i; break; }
                    }
                    if (resync59 >= 0) {
                        // v8.06: same history push as the onResume
                        // adopt above, for the foreground-race case
                        // where onResume never ran — without it a
                        // poll-resynced advance also leaves the
                        // finished talk out of the shuffle Previous
                        // walk. Cannot double-push with the adopt:
                        // once either corrector moves the index
                        // onto the sounding talk, this branch's
                        // !isCurrentTalk guard never fires again
                        // for the same transition.
                        if (resync59 != currentIndex) {
                            // v8.08: same trail drain as the onResume
                            // adopt above, for the foreground-race
                            // case where onResume never ran.
                            if (!drainHiddenTrail08(talks.get(currentIndex).url)) {
                                pushPrevHistory16(talks.get(currentIndex).url);
                            }
                            // v8.61: same photo rule as the onResume
                            // adopt — a background advance that the
                            // poll resyncs is a real talk change, so
                            // the shrine photo switches with it instead
                            // of staying on the finished talk until the
                            // next manual tap. Cannot double-switch
                            // with the onResume adopt: once either
                            // corrector moves the index, the other's
                            // move guard never fires for the same
                            // transition; autoTheme off still freezes.
                            // v12.51: per-talk roll, deferred until
                            // the resynced talk is confirmed sounding
                            // (see requestThemeForTalk).
                            requestThemeForTalk(talks.get(resync59).url);
                        }
                        currentIndex = resync59;
                        // v10.38: same wave reset as the v7.73 onResume
                        // adopt above, for the foreground-race case
                        // where onResume never ran — this branch
                        // re-points the index but never reset the
                        // bar, so a same-duration background advance
                        // read as NOT a fresh track on the next tick
                        // and swept the bar backwards end -> 0
                        // (v3.32 rewind glide) instead of starting
                        // the new talk at 0. Mirrors playIndex
                        // (glide cancelled, settle hold voided, max
                        // + progress + buffered to 0, clocks to
                        // 0:00, same !seeking guard); the adopt
                        // above and this branch cannot double-reset
                        // for one transition, since this guard only
                        // fires while the index is still stale.
                        if (!seeking) {
                            if (seekGlide != null) { seekGlide.cancel(); seekGlide = null; }
                            lastUserSeekMs = 0;
                            if (seekBar != null) {
                                seekBar.setMax(0);
                                seekBar.setProgress(0);
                                seekBar.setBufferedPercent(0);
                            }
                            if (elapsedText != null) elapsedText.setText("0:00");
                            if (totalText != null) totalText.setText("0:00");
                        }
                        reconcileForwardAfterBackground27(); // v8.27: background-played forward step consumed
                        // v7.64: same branch, queue side — a v7.63
                        // background advance pops the head from the
                        // saved queue in prefs, but this branch only
                        // re-pointed the index: when the service's
                        // completion raced ahead of this poll tick
                        // (Room foreground throughout, so onResume's
                        // syncSavedQueueOnReturn never ran), the
                        // in-memory queueUrls still held the popped
                        // head, so the drawer header's "· ◈ N" and
                        // the queue-only view read one high until
                        // the next return or list load, while
                        // Next/peek already skipped the sounding
                        // talk. Adopt the saved queue here too —
                        // the same read-only adopt as onResume (no
                        // prefs write, no feedback loop), and a
                        // no-op when onResume already synced it.
                        // talks is non-empty here (resync59 >= 0),
                        // so the empty-list guard cannot wipe a
                        // queue whose list is still loading.
                        syncSavedQueueOnReturn();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        // The title line itself is left for the
                        // next tick's title branch (same 500ms
                        // rhythm as every other poll sync); the
                        // count line is never written, so nothing
                        // pours to a wrong line in between, and
                        // the branch cannot re-enter once the
                        // index points at the sounding talk.
                        // The seek range already follows the
                        // service duration on every tick, and
                        // the finished talk's played mark landed
                        // in the service and refreshes on return.
                        if (roomList != null && roomList.getVisibility() == View.VISIBLE) {
                            int sp59 = drawerScrollPos(currentIndex);
                            if (sp59 >= 0) roomList.setSelection(sp59);
                        }
                    } else {
                    currentIndex = -1;
                    if (nowPlaying != null) {
                        nowPlaying.setText(Lang.t(RoomActivity.this, "room_count_prefix") + talks.size() + Lang.t(RoomActivity.this, "room_count_suffix"));
                        // v2.69: premium poll return-to-count pour — the
                        // mirror of the v2.66 load-complete count pour.
                        // A stale row used to snap the status line back
                        // to this count line in one frame, while every
                        // other status line already pours (v2.66/v2.67/
                        // v2.68); it now fades + rises in with the same
                        // rhythm (320ms, 12dp rise, decelerate, ending
                        // at exactly alpha 1 / offset 0). Runs once:
                        // currentIndex is -1 after this, so the poll
                        // never re-enters the branch, and the v2.66
                        // onPause/onDestroy reset already cancels this
                        // same view's animator. Visual only; text and
                        // stale-row behaviour untouched.
                        // v3.49: pour-restart continuity (v3.37 rule, extended) — a settled
                        // view still pours visibly from 0/12dp; a mid-pour view flows on
                        // from its current alpha/offset instead of snapping back.
                        nowPlaying.animate().cancel();
                        if (nowPlaying.getAlpha() >= 0.999f && nowPlaying.getTranslationY() <= 0.5f) {
                            nowPlaying.setAlpha(0f);
                            nowPlaying.setTranslationY(12f * getResources().getDisplayMetrics().density);
                        }
                        nowPlaying.animate().alpha(1f).translationY(0f).setDuration(320)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    }
                    if (adapter != null) adapter.notifyDataSetChanged();
                    }
                }
                // v1.48: after the notification's "stop", the service has
                // no track but the Room kept showing the old talk title
                // as if it were still there — clear a track-title line
                // (loading/count lines never start with ▶/⏸, so they
                // are left alone).
                else if (!service.hasTrack()) {
                    String np = nowPlaying == null ? "" : nowPlaying.getText().toString();
                    // v1.80: the buffering line ("ဆွဲထုတ်နေသည်… "
                    // + title) is a track-title line too — stopping
                    // from the notification mid-buffer used to leave
                    // it (and the stale seek bar) up forever, since
                    // only ▶/⏸ lines were cleared. The list-loading
                    // line ("တရားစာရင်း ဆွဲထုတ်နေသည်…") starts
                    // differently and is left alone, as are count lines.
                    if (np.startsWith("▶ ") || np.startsWith("⏸ ")
                            || np.startsWith(Lang.t(RoomActivity.this, "notif_state_buffering"))) {
                        // v1.82: back to the count line, like the
                        // stale-row branch above — the bare line used
                        // to hide how many talks this sayadaw has
                        // until the list was reopened.
                        nowPlaying.setText(talks.isEmpty() ? Lang.t(RoomActivity.this, "choose_talk")
                                : Lang.t(RoomActivity.this, "room_count_prefix") + talks.size() + Lang.t(RoomActivity.this, "room_count_suffix"));
                        // v2.69: same pour as the stale branch above —
                        // a notification stop used to snap the status
                        // line back to this count line in one frame.
                        // Same rhythm, same v2.66 lifecycle reset.
                        // Runs once: after this set the line no longer
                        // starts with ▶/⏸/ဆွဲထုတ်နေသည်…, so the poll
                        // never re-enters the branch. Visual only.
                        // v3.49: pour-restart continuity (v3.37 rule, extended) — a settled
                        // view still pours visibly from 0/12dp; a mid-pour view flows on
                        // from its current alpha/offset instead of snapping back.
                        nowPlaying.animate().cancel();
                        if (nowPlaying.getAlpha() >= 0.999f && nowPlaying.getTranslationY() <= 0.5f) {
                            nowPlaying.setAlpha(0f);
                            nowPlaying.setTranslationY(12f * getResources().getDisplayMetrics().density);
                        }
                        nowPlaying.animate().alpha(1f).translationY(0f).setDuration(320)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                        btnPlayPause.setImageResource(R.drawable.ic_play); // v7.28: vector icon
                        roomView.setPlaying(false);
                        if (seekBar != null) seekBar.setPlaying(false); // v2.52: stopped talk = still wave
                        // v1.62: the stopped talk's row kept its gold ▶
                        // mark and Next wrapped from a dead index —
                        // the title cleared but currentIndex never did.
                        // Clear the index and refresh the rows with it.
                        currentIndex = -1;
                        if (adapter != null) adapter.notifyDataSetChanged();
                        // v1.54: the title cleared but the seek bar kept
                        // showing the stopped talk's old progress — reset
                        // it with the title so nothing looks half-played.
                        if (!seeking) {
                            // v1.75: the range was left at the stopped
                            // talk's length, so dragging the bar with
                            // no track sought into a dead range.
                            // v2.20: also stop any seek glide — it
                            // would otherwise keep writing the old
                            // talk's progress over this reset.
                            if (seekGlide != null) { seekGlide.cancel(); seekGlide = null; }
                            lastUserSeekMs = 0; // v3.26: a stop voids the settle hold
                            if (seekBar != null) {
                                seekBar.setMax(0);
                                seekBar.setProgress(0);
                                seekBar.setBufferedPercent(0); // v4.06: a stop clears the buffered tint
                                seekBar.setBookmarks(null); // v12.64: a stop clears the pins too — no track, no marks
                            }
                            // v3.27: a stop resets the wave shape too —
                            // setPlaying(false) above froze the ended
                            // talk's ripple, so the reset bar kept
                            // wavy bars at 0:00 while a never-played
                            // bar draws flat. Flat again here, in
                            // the same !seeking guard as the rest of
                            // this reset, so a drag in flight is
                            // never disturbed; pause never reaches
                            // this branch, so its frozen shape stays.
                            if (seekBar != null) seekBar.resetRipple();
                            if (elapsedText != null) elapsedText.setText("0:00");
                            if (totalText != null) totalText.setText("0:00");
                            // v2.82: premium stop-reset seek-row pour —
                            // the title already pours back to the count
                            // line here (v2.69) and the button flips
                            // with its morph, but the seek row itself
                            // still snapped to 0:00 / empty bar in the
                            // same frame, so the bottom of the panel
                            // changed in a different rhythm from the
                            // rest of it — the same snap v2.30 fixed
                            // on talk change. The row now fades +
                            // rises in with the title (same 320ms,
                            // same 12dp rise, ending at exactly alpha
                            // 1 / offset 0). Runs once per stop: the
                            // branch never re-enters once the line no
                            // longer starts with ▶/⏸/ဆွဲထုတ်နေသည်…,
                            // and it sits inside the !seeking guard,
                            // so a drag in flight is never disturbed.
                            // The poll only sets text on the row's
                            // children and the glide is cancelled
                            // above, so nothing rewrites the row's
                            // alpha/translation mid-pour; the v2.30
                            // onPause/onDestroy reset already cancels
                            // this row's animator if the Room is left
                            // mid-pour. Visual only; reset values,
                            // button, title and stale/stop behaviour
                            // untouched.
                            android.view.View seekRowStop = findViewById(R.id.roomSeekRow);
                            if (seekRowStop != null) {
                                // v3.49: pour-restart continuity (v3.37 rule, extended) — a settled
                                // view still pours visibly from 0/12dp; a mid-pour view flows on
                                // from its current alpha/offset instead of snapping back.
                                seekRowStop.animate().cancel();
                                if (seekRowStop.getAlpha() >= 0.999f && seekRowStop.getTranslationY() <= 0.5f) {
                                    seekRowStop.setAlpha(0f);
                                    seekRowStop.setTranslationY(12f * getResources().getDisplayMetrics().density);
                                }
                                seekRowStop.animate().alpha(1f).translationY(0f).setDuration(320)
                                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                            }
                        }
                        // v1.69: a stopped session ends the handled
                        // marker with it (see endedHandledFor).
                        endedHandledFor = null;
                    }
                }
                // v7.70: the four Room button refreshes used to run
                // at the top of this poll, BEFORE the title /
                // resync / stop branches above — so on the tick
                // where a v7.58 background advance landed, they
                // still read the FINISHED talk's index while the
                // session already named the sounding one: the
                // download / favourite / share buttons' session
                // guard failed and all three hid for one tick,
                // then reappeared on the next tick once the v7.59
                // resync had re-pointed the index — a visible
                // flicker after every background auto-advance
                // with the Room in the foreground. They now run
                // here, after all three branches, like the v2.27
                // pulse block below (whose own comment states
                // the placement rule: currentIndex is already
                // corrected here). Same four calls, change-only,
                // nothing between them and the branches reads
                // repeatMode / shuffleOn or the buttons' state,
                // so the move changes no other tick behaviour;
                // on a notification-stop tick they now also hide
                // in the same tick instead of one tick late.
                // The onResume call site is untouched (its own
                // return sync already corrects the index there).
                refreshRoomDownloadBtn();
                refreshRoomFavBtn();
                refreshRoomShareBtn();
                refreshRoomRepeatShuffle();
                // v2.27: Room sheet playing-row pulse — the sheet's
                // playing row used to sit static in gold while the
                // Room's own play control breathes (v2.07 glow +
                // v2.20 pulse) and the library's playing row breathes
                // (v2.25), so the one list open beside the shrine
                // felt dead. The playing row's title now breathes
                // 1.00->0.55 on the same 900ms reverse loop, alpha
                // only, so the row never shifts. Placed after the
                // stale/stop branches above, so currentIndex is
                // already corrected when the service track differs
                // or stops; driven by the poll's active rule (same
                // as the button and the flames), not by getView, so
                // a pause while the sheet sits open stops the pulse
                // at the next tick without an extra rebind. A rebind
                // of the still-playing row leaves the running pulse
                // alone instead of restarting it mid-breath.
                {
                    android.view.View pulseTitle = null;
                    if (roomList != null && roomList.getVisibility() == View.VISIBLE
                            && active && currentIndex >= 0 && currentIndex < talks.size()) {
                        int first = roomList.getFirstVisiblePosition();
                        // v4.98: the pulse used the full-list index
                        // as a display position, so in a queue-only,
                        // filter or search view it breathed on the
                        // wrong row — the display position is the
                        // mapped one, and a talk with no row here
                        // (searched out, queue-consumed) pulses
                        // nothing instead of a stranger's title.
                        int dpulse = drawerPosOfTalk(currentIndex);
                        android.view.View row = dpulse >= 0 ? roomList.getChildAt(dpulse - first) : null;
                        if (row != null) pulseTitle = row.findViewById(R.id.talkTitle);
                    }
                    if (pulseTitle != null) {
                        if (roomPulseView != pulseTitle) {
                            if (roomPulse != null) roomPulse.cancel();
                            // v3.13: premium pulse-stop fade — the old
                            // row's title used to snap back to alpha 1
                            // from wherever it sat mid-breath (as low
                            // as 0.55) the moment the track changed,
                            // a visible pop beside the new row's
                            // smooth pulse start. It now fades back
                            // to 1 over 200ms (decelerate, alpha-only,
                            // on the title alone, never the row), so
                            // the breath hands over instead of
                            // cutting. A quick A->B->A handover first
                            // cancels any fade still running on the
                            // new pulse title, so the fade (ending
                            // at 1) never fights the pulse (1->0.55).
                            // v3.19: clear a running pulse-start
                            // settle BEFORE cancelling the old
                            // title's animator — a cancelled
                            // ViewPropertyAnimator still fires its
                            // end action, and with the flag and the
                            // view still set the settle's guard
                            // passes and starts an infinite loop
                            // on the stopped title, which the new
                            // title's own settle guard (roomPulse
                            // != null) then refuses to replace.
                            // Clearing first makes that fired end
                            // action return at once; the new
                            // title's settle below re-arms the
                            // flag for itself. The pause branch
                            // already cleared first; the handover
                            // and the leave resets did not.
                            roomPulseSettling = false;
                            if (roomPulseView != null) {
                                roomPulseView.animate().cancel();
                                roomPulseView.animate().alpha(1f).setDuration(200)
                                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                            }
                            roomPulseView = pulseTitle;
                            pulseTitle.animate().cancel();
                            // v3.17: premium pulse-start settle — the
                            // v3.13 handover cancels any fade still
                            // running on this title (a quick A->B->A
                            // change leaves it mid-fade back to 1),
                            // and the loop below starts at exactly
                            // 1f, so the title snapped from wherever
                            // the fade had reached straight to 1 in
                            // one frame — the same snap class v3.16
                            // fixed on the glow ring. If the title
                            // is already at 1 the loop starts at once
                            // as before; otherwise it glides to 1
                            // over 150ms (decelerate) and the loop
                            // starts from the settle's end action,
                            // guarded so a pause or a leave in
                            // between (which clears the flag and the
                            // view) never starts a stale loop. The
                            // poll skips this branch while settling
                            // because roomPulseView already points
                            // at the title.
                            if (pulseTitle.getAlpha() < 0.999f) {
                                roomPulseSettling = true;
                                final android.view.View settleTitle = pulseTitle;
                                pulseTitle.animate().alpha(1f).setDuration(150)
                                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                        .withEndAction(new Runnable() {
                                            @Override public void run() {
                                                if (!roomPulseSettling || roomPulseView != settleTitle || roomPulse != null) return;
                                                roomPulseSettling = false;
                                                roomPulse = android.animation.ObjectAnimator.ofFloat(settleTitle, "alpha", 1f, 0.55f);
                                                roomPulse.setDuration(900);
                                                roomPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                                                roomPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                                                roomPulse.start();
                                            }
                                        }).start();
                            } else {
                                // v3.17: a settle cancelled by an
                                // intervening handover must not leave
                                // the flag true while a loop runs.
                                roomPulseSettling = false;
                                roomPulse = android.animation.ObjectAnimator.ofFloat(pulseTitle, "alpha", 1f, 0.55f);
                                roomPulse.setDuration(900);
                                roomPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                                roomPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                                roomPulse.start();
                            }
                        }
                    } else if (roomPulseView != null) {
                        if (roomPulse != null) roomPulse.cancel();
                        roomPulse = null;
                        // v3.13: same pulse-stop fade as the track
                        // change above — pausing mid-breath used to
                        // snap the title to 1 in one poll tick while
                        // the play glow (v2.07) and the wave ripple
                        // (v2.76) both settle softly. The fade runs
                        // on the title alone; a closed drawer or a
                        // scrolled-out row just ends it unseen at 1,
                        // and onPause/onDestroy still reset instantly.
                        // v3.17: a pause during a pulse-start settle
                        // cancels the settle too (same animator
                        // channel) and clears its flag, so the
                        // settle's end action never starts a loop
                        // for a paused row; the fade below still
                        // lands the title softly at 1.
                        roomPulseSettling = false;
                        roomPulseView.animate().cancel();
                        roomPulseView.animate().alpha(1f).setDuration(200)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                        roomPulseView = null;
                    }
                }
                if (!seeking) {
                    int dur = service.getDuration();
                    int pos = service.getPosition();
                    if (dur > 0) {
                        // v2.20: smooth seek glide — the thumb and
                        // clock used to jump every 500ms poll. The
                        // bar now glides linearly to each poll's
                        // position over the 500ms to the next poll,
                        // so playback progress moves continuously.
                        // A new track (max changed), a backwards
                        // jump (user seek elsewhere, replay) or a
                        // standstill (paused) sets instantly instead
                        // of gliding across the bar or to nowhere.
                        boolean freshTrack = seekBar == null || seekBar.getMax() != dur;
                        if (seekBar != null) seekBar.setMax(dur);
                        // v4.06: buffered-ahead tint on the wave —
                        // the 500ms poll already runs here, so no new
                        // timer; the service returns 0 for downloaded
                        // talks, leaving their wave untouched, and
                        // the view only redraws when the value moves.
                        if (seekBar != null) seekBar.setBufferedPercent(service.getBufferedPercent());
                        // v12.64: a fresh track brings its own marks
                        // — the pins above belong to the new talk, so
                        // refresh them here, once per track change.
                        // v12.72: the sounding URL changing refreshes
                        // too — a foreign head whose duration equals
                        // the finished talk's to the exact ms kept the
                        // old talk's pins until the next duration
                        // change (sim1278). The comparison lives
                        // inside the dur > 0 block on purpose: the
                        // last-seen URL only advances on polls that
                        // can refresh, so a handover is always
                        // caught on the first such poll after it.
                        String tickUrl = bookmarkTalkUrl();
                        boolean freshTalkUrl =
                                (tickUrl == null) != (lastBookmarkTickUrl == null)
                                || (tickUrl != null && !tickUrl.equals(lastBookmarkTickUrl));
                        lastBookmarkTickUrl = tickUrl;
                        if (freshTrack || freshTalkUrl) refreshBookmarkTicks();
                        // v3.33: premium total-time fade-in — a talk
                        // change resets the total to 0:00 (playIndex,
                        // v1.55 reset) and the first poll that learns
                        // the new duration used to swap it straight
                        // to the real length in one frame, while the
                        // title pours (v2.22/v2.66) and the wave
                        // glides (v2.20/v3.30/v3.31) around it. The
                        // total now fades in with the existing
                        // Fx.fadeIn (250ms, decelerate, alpha-only)
                        // on the poll where its value actually
                        // changes. Guards: polls repeating the same
                        // value never re-fade (string comparison, so
                        // a steady total never flickers); this whole
                        // branch already sits inside !seeking, so a
                        // drag in flight is never disturbed; the
                        // stop-reset 0:00 (v1.54 branch) and the
                        // talk-change reset 0:00 (playIndex) are
                        // untouched — only the arrival of a real
                        // duration fades, and it fades on the label
                        // itself, never on the seek row, so the
                        // talk-change row pour (v2.30) and the
                        // stop row pour (v2.82) compose instead of
                        // fighting. Visual only; the duration,
                        // the progress and the clock are untouched.
                        // v4.12: remaining mode (tap the clock) shows
                        // the time left instead of the length. The
                        // remaining value ticks every second, so it
                        // must NOT take the total's arrival fade —
                        // a 250ms fade every tick would flicker;
                        // only the length arrival fades, as before.
                        String newTotal = showRemaining
                                ? "−" + formatTime(Math.max(0, dur - pos))
                                : formatTime(dur);
                        if (totalText != null && !newTotal.equals(totalText.getText().toString())) {
                            totalText.setText(newTotal);
                            if (!showRemaining) Fx.fadeIn(totalText, 250);
                        }
                        int cur = seekBar == null ? 0 : seekBar.getProgress();
                        // v3.26: seek-settle hold — while a user seek
                        // is still landing (service position behind
                        // the dragged target, inside 1500ms of the
                        // release), hold the bar + clock at the
                        // target instead of snapping back to the
                        // pre-drag position and gliding forward
                        // again. A landed seek (position at/past
                        // the target) or an expired hold clears the
                        // grace and normal updates resume; a fresh
                        // track bypasses it entirely, and the
                        // talk-change reset below clears it too.
                        // v3.29: direction-aware landing — a forward
                        // seek lands when pos reaches the target from
                        // below, a backward seek when it reaches it
                        // from above. The old single test (pos >=
                        // target) cleared a backward hold instantly.
                        boolean seekLanded = lastUserSeekMs != 0
                                && (lastUserSeekDir < 0 ? pos <= lastUserSeekPos
                                        : pos >= lastUserSeekPos);
                        if (!freshTrack && seekLanded) lastUserSeekMs = 0;
                        boolean seekSettling = !freshTrack && lastUserSeekMs != 0
                                && android.os.SystemClock.uptimeMillis() - lastUserSeekMs < 1500
                                && !seekLanded;
                        // v3.30: premium resume glide — a talk resumed
                        // from its saved position used to jump the wave
                        // from 0 straight to the saved minute in one
                        // poll tick: playIndex resets the bar to 0, and
                        // the first poll that learns the new duration
                        // takes the freshTrack instant-set branch, long
                        // after the talk-change pour (v2.22/v2.30) has
                        // ended, so the jump lands on a static row. The
                        // v2.20 instant-set stays for its real case —
                        // a bar still holding the old talk's progress
                        // must never sweep across to the new talk —
                        // but a reset bar (cur == 0) has no old progress
                        // to sweep from, so it glides 0 -> saved position
                        // over the same 500ms linear glide the normal
                        // poll path uses, clock gliding with it. Guards:
                        // only outside a drag (seeking blocks this whole
                        // branch), and a settle hold can never be
                        // active here (playIndex voids it on every
                        // talk change, and freshTrack bypasses it
                        // above). The next poll takes the normal glide
                        // path from wherever this glide has reached,
                        // so nothing double-animates. Visual only; the
                        // final position, the duration and the resume
                        // position itself are untouched.
                        // v3.31: premium paused-resume glide — v3.30
                        // only glided while the talk was really playing
                        // (the poll's active rule), so the other resume
                        // case still snapped: re-entering the Room while
                        // a talk sat PAUSED (v1.76 path marks the row
                        // only, never calls playIndex, and nothing
                        // pre-sets the bar — onServiceConnected sets
                        // only the title) left the bar at 0, and the
                        // first poll with a known duration took the
                        // freshTrack instant-set branch straight to
                        // the saved minute in one tick. The glide now
                        // covers a paused resume too: the bar + clock
                        // glide 0 -> saved position over the same
                        // 500ms linear glide, ending exactly on the
                        // true saved position. Guards: the paused
                        // position is static, so the following polls
                        // hold it (pos == cur instant-set) instead of
                        // re-gliding; pressing play mid-glide just
                        // continues on the normal glide path; a drag
                        // mid-glide cancels it (seeking blocks this
                        // branch and onStartTrackingTouch cancels the
                        // animator, v2.20); a settle hold still cannot
                        // be active here (freshTrack bypasses it, and
                        // a recreated Room starts with no hold armed).
                        // Visual only; final position, duration and
                        // saved resume position untouched.
                        boolean resumeGlide = freshTrack && cur == 0 && pos > 0;
                        if (seekSettling) {
                            // Hold: bar and clock already show the
                            // dragged target; touch neither.
                            // v8.02: the remaining total is pinned
                            // to the target too. The total above is
                            // written from the LIVE position before
                            // this test, so in remaining mode it
                            // kept ticking from the lagging pre-seek
                            // position while the elapsed clock sat
                            // frozen at the target — the two clocks
                            // disagreed (elapsed + remaining !=
                            // duration) for the whole 1500ms hold,
                            // moving in opposite directions on a
                            // backward seek. Total mode needs
                            // nothing (dur is unchanged); remaining
                            // mode is rewritten from the held
                            // target, same tick, same UI thread, so
                            // no wrong value ever reaches a frame,
                            // and no fade (remaining never fades,
                            // v4.12). The next poll after the hold
                            // lands or expires resumes live totals.
                            if (showRemaining && dur > 0) {
                                String heldTotal = "−" + formatTime(
                                        Math.max(0, dur - lastUserSeekPos));
                                if (totalText != null && !heldTotal.equals(totalText.getText().toString()))
                                    totalText.setText(heldTotal);
                            }
                        } else if ((freshTrack && !resumeGlide) || pos == cur) {
                            if (seekGlide != null) { seekGlide.cancel(); seekGlide = null; }
                            if (seekBar != null) seekBar.setProgress(pos);
                            if (elapsedText != null) elapsedText.setText(formatTime(pos));
                        } else if (pos < cur) {
                            // v3.32: premium replay rewind glide — the
                            // v2.20 instant-set stayed for every
                            // backwards jump, but one same-track case
                            // still snapped: a talk that ended with
                            // autoplay off (or stopped by the data
                            // gate) leaves the wave sitting at the
                            // end, and pressing play restarts it from
                            // 0 through service.toggle, so the next
                            // poll snapped the wave end -> 0 in one
                            // tick while every other wave move glides
                            // (v2.20 forward, v3.30/v3.31 resume). The
                            // wave now glides back down over the same
                            // 500ms linear glide, clock gliding with
                            // it. Guards: a fresh track still sets
                            // instantly above — old talk progress must
                            // never sweep across to a new talk; the
                            // settle hold above still owns a user
                            // backward drag, so this branch only ever
                            // sees a non-user backward jump; pos ==
                            // cur still sets instantly (no movement);
                            // each poll restarts the glide from the
                            // animated value, exactly like the forward
                            // path, so it converges as playback picks
                            // up; a drag mid-glide cancels it (seeking
                            // blocks this branch and onStartTracking
                            // cancels the animator, v2.20). Visual
                            // only; position, duration and the replay
                            // itself are untouched.
                            if (seekGlide != null) seekGlide.cancel();
                            seekGlide = android.animation.ValueAnimator.ofInt(cur, pos);
                            seekGlide.setDuration(500);
                            seekGlide.setInterpolator(new android.view.animation.LinearInterpolator());
                            seekGlide.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                                    int p = (Integer) a.getAnimatedValue();
                                    if (seekBar != null) seekBar.setProgress(p);
                                    if (elapsedText != null) elapsedText.setText(formatTime(p));
                                }
                            });
                            seekGlide.start();
                        } else {
                            if (seekGlide != null) seekGlide.cancel();
                            seekGlide = android.animation.ValueAnimator.ofInt(cur, pos);
                            seekGlide.setDuration(500);
                            seekGlide.setInterpolator(new android.view.animation.LinearInterpolator());
                            seekGlide.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                                    int p = (Integer) a.getAnimatedValue();
                                    if (seekBar != null) seekBar.setProgress(p);
                                    if (elapsedText != null) elapsedText.setText(formatTime(p));
                                }
                            });
                            seekGlide.start();
                        }
                    } else if (service.hasTrack() && !service.hasTrackError()) { // v12.74 (sim1282): a failed mid-play stream must not snap elapsed to 0:00 — the getters read 0 when unprepared, so the last real elapsed stays until a retry/replay/stop
                        if (elapsedText != null) elapsedText.setText(formatTime(pos));
                    }
                }
                // track ended (polling: no listener here, so the Talks
                // screen's listener is never disturbed)
                // v1.55: end is the service's real completion flag, not
                // position-near-duration — pausing a talk in its last
                // seconds used to look exactly like a natural end, so
                // the pause itself skipped to the next talk and marked
                // this one listened. A manual pause now stays put.
                // v1.69: the old wasPlaying condition also meant a
                // talk finishing mid-rotation never advanced (the
                // recreated poll starts with wasPlaying false). The
                // completion flag alone is the trigger now, handled
                // once per track (see endedHandledFor); while anything
                // plays the marker clears, so a replay ends handled.
                if (playing) endedHandledFor = null;
                if (!playing && service.hasTrackEnded()) {
                    String curEnded = service.getCurrentUrl();
                    // v1.51: guard the end-of-track block — a talk list
                    // reloaded shorter (or emptied) after a retry could
                    // leave currentIndex past the end, and the modulo
                    // below would divide by zero on an empty list.
                    if (currentIndex >= 0
                            && currentIndex < talks.size() && !talks.isEmpty()
                            && curEnded != null
                            // v1.75: the ended track must really be
                            // this row — a downloaded file from
                            // another sayadaw ending while this Room
                            // sat foreground used to mark this row
                            // played and auto-advance into a talk
                            // nobody chose (the title branch above
                            // already had this guard since v1.57;
                            // the end block never did).
                            && isCurrentTalk(talks.get(currentIndex).url)
                            && (endedHandledFor == null || !endedHandledFor.equals(curEnded))) {
                        endedHandledFor = curEnded;
                        try {
                            java.util.Set<String> played = new java.util.HashSet<String>(
                                    getSharedPreferences("dhamma", MODE_PRIVATE)
                                            .getStringSet("playedTalks", new java.util.HashSet<String>()));
                            if (played.add(talks.get(currentIndex).url)) {
                                getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                                        .putStringSet("playedTalks", played).apply();
                                refreshMarkSets();
                            }
                        } catch (Exception ignored) {}
                        // v4.35: repeat-one outranks autoplay — it is
                        // an explicit loop order for this one talk, so
                        // the talk restarts from 0 even with autoplay
                        // off. v11.85: restarted in place like the
                        // v4.13 long-press-Play restart (seek to 0 +
                        // resume) — the old playIndex(currentIndex, 0)
                        // here never restarted anything (its
                        // already-holding return only re-marks the
                        // row). The
                        // endedHandledFor guard above still fires it
                        // once per ended track, and the played ✓ above
                        // already landed, so the restart never
                        // double-marks. The toast names it, or a
                        // listener looking away hears the same talk
                        // restart with no explanation.
                        // v4.82: one-shot "stop at end of talk" — it
                        // outranks repeat-one and autoplay alike: the
                        // finished talk keeps its ✓ above, the flag
                        // is consumed once, and nothing advances.
                        if (service.isSleepEndOfTalk()) {
                            service.consumeSleepEndOfTalk();
                            Toast.makeText(RoomActivity.this,
                                    Lang.t(RoomActivity.this, "sleep_end_stopped"),
                                    Toast.LENGTH_LONG).show();
                        }
                        // v11.77: a bedtime stop is pending (minute timer fired mid-fade and this
                        // talk ended before the fade-end pause) — nothing advances or replays; the
                        // end stays handled (endedHandledFor above), like the one-shot stop.
                        else if (service.isSleepStopPending()) {
                            // No advance, no replay — playback stops at bedtime.
                        }
                        else if (repeatMode == 2 && currentIndex >= 0 && currentIndex < talks.size()) {
                            // v11.85: restart in place — the old playIndex(currentIndex, 0)
                            // never restarted anything: playIndex's already-holding early
                            // return (v1.56/v1.87) fires for the ended talk (onCompletion
                            // never clears currentTitle, so hasTrack() stays true) and only
                            // re-marks the row, while the toast below still announced a
                            // replay — repeat-one sat silent at the end. Restart like the
                            // long-press restart (v3.32): seek to 0, resume if paused; the
                            // toast fires only when playback actually restarts (a gated
                            // resume refusal stays silent, and the poll's resume-gate ask
                            // covers it).
                            if (service != null) {
                                // v11.90: void the seek-settle hold like a talk change does
                                // (playIndex resets lastUserSeekMs) — a scrub within 1500ms
                                // before the end otherwise froze the bar + clock at the old
                                // drag target while the restarted talk played from 0.
                                lastUserSeekMs = 0;
                                if (seekGlide != null) { seekGlide.cancel(); seekGlide = null; }
                                if (service.isBuffering()) service.restartFromStart();
                                else { service.seekTo(0); if (!service.isPlaying()) service.resume(); }
                                if (service.isPlaying()) {
                                    Toast.makeText(RoomActivity.this,
                                            Lang.t(RoomActivity.this, "replay_prefix") + talks.get(currentIndex).title,
                                            Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                // v11.90: explicit-restart sentinel (see playIndex) — the old 0
                                // resumed the saved spot instead of restarting.
                                playIndex(currentIndex, -1);
                            }
                        }
                        // v1.39: honour the autoplay setting — v1.34 claimed
                        // this but the poll advanced unconditionally. With
                        // autoplay off, stop at the end of the talk.
                        // v1.43: autoplay wraps at the last talk back to
                        // the first, so continuous listening never stops
                        // at the end of the list (manual Next already did).
                        else if (Prefs.autoplay(RoomActivity.this)) {
                            // v1.58: no surprise streaming — on mobile data
                            // with data saver on, auto-advance only to a
                            // talk already downloaded; otherwise stop here
                            // and let the user tap (and get the dialog).
                            // v4.32: auto-advance follows the hand queue
                            // first — peek for the data-gate check, and
                            // consume only on the real advance below,
                            // so a gated stop never eats a queued talk.
                            int nx = peekNextIndex();
                            // v4.35: repeat-off stops at the last talk
                            // instead of the v1.43 wrap — but only when
                            // no hand-queued talk is waiting: a queued
                            // talk still plays (streaming rule: off
                            // stops the loop, never the queue).
                            // peekNextIndex already pruned stale queue
                            // URLs, so this contains check is exactly
                            // "a live queued talk is next". The stop
                            // mirrors the autoplay-off branch below
                            // (rebind + "ပြီးပါပြီ" toast), so the
                            // ended row's ✓ still fades in the drawer
                            // the same way on the next open.
                            boolean hasLiveQueue = firstWaitingForeignEntry() != null || (nx >= 0 && queueLineIndexForUrl(talks.get(nx).url) >= 0); // v7.08: a foreign head is a live queue too, so repeat-off never stops on it; v7.38: line-aware, so a v2-spelled in-list next talk still counts as queued
                            // v4.36: shuffle outranks repeat-off here —
                            // repeat-off stops a LIST loop, but a
                            // shuffled advance is random play, not a
                            // loop, so it keeps going to the cached
                            // random pick instead of stopping at the
                            // list's last row. The hand queue still
                            // outranks both, as before.
                            if (repeatMode == 0 && !shuffleOn && !hasLiveQueue && !(nx >= 0 && forwardHistory.contains(talks.get(nx).url)) && currentIndex == talks.size() - 1) {
                                adapter.notifyDataSetChanged();
                                Toast.makeText(RoomActivity.this,
                                        Lang.t(RoomActivity.this, "dl_finished_prefix") + talks.get(currentIndex).title,
                                        Toast.LENGTH_SHORT).show();
                            } else if (firstWaitingForeignEntry() != null) {
                                // v7.18: data-gate BEFORE the handover — the
                                // handover consumes the foreign line (dropped
                                // + saved under the new session), and its
                                // load-completion playIndex then asks the
                                // v1.58 gate; cancelling that question would
                                // silently eat the queued talk. In-list
                                // auto-advance never eats on a gated stop
                                // (peek, never consume, v4.32), so the
                                // foreign branch checks the same rule up
                                // front — data saver on, not Wi-Fi, no
                                // downloaded file for the foreign URL —
                                // and on a gated stop it keeps the queue
                                // untouched and says so with the in-list
                                // gated-stop toast verbatim, so a tap
                                // (Next / header) still answers the gate.
                                // Wi-Fi, data saver off, or a downloaded
                                // foreign talk hands over exactly as v7.08.
                                QueueEntry feGate18 = firstWaitingForeignEntry();
                                if (feGate18 != null && Prefs.dataSaver(RoomActivity.this)
                                        && !isWifi() && localPathFor(feGate18.url) == null) {
                                    // v12.12: record the gated foreign head for the B2 await — the
                                    // gate dialog's Wi-Fi button binds its job by URL, and the
                                    // service receiver re-fires the advance when it lands (Case A
                                    // handover, or Case B post-handover re-fire). Without this the
                                    // bind below found no record and the download landed silently.
                                    noteGatedForeignAdvanceTarget(feGate18);
                                    adapter.notifyDataSetChanged();
                                    Toast.makeText(RoomActivity.this,
                                            Lang.t(RoomActivity.this, "mobile_no_autoplay"),
                                            Toast.LENGTH_LONG).show();
                                } else {
                                    // v8.68: the in-list advance above announces itself
                                    // ("နောက်: ", v4.14) but this handover never did —
                                    // the new session's header names the new sayadaw
                                    // visually, yet a listener looking away (phone
                                    // locked, the v4.14 audience) heard a new voice
                                    // with no announcement at all, while the
                                    // library's announcements gained their sayadaw
                                    // in v8.67. Announce at handover initiation, the
                                    // v4.14 semantics (the in-list toast likewise
                                    // fires when playIndex starts, before prepare
                                    // completes); a later load failure still gets
                                    // its own failure toast. Capture the entry
                                    // first: the handover moves the queue, and a
                                    // refused handover (catalog miss) toasts its
                                    // own refusal inside and must not announce.
                                    // Title · sayadaw order + ★ prefix, matching
                                    // the v8.67 library toast; a title-less line
                                    // falls back to its URL like the v8.63 preview.
                                    QueueEntry fe68 = firstWaitingForeignEntry();
                                    if (tryForeignHandover() && fe68 != null) {
                                        String t68 = fe68.title != null && fe68.title.length() > 0 ? fe68.title : fe68.url;
                                        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "next_prefix") + (favSet != null && favSet.contains(fe68.url) ? "★ " : "") + t68 + (fe68.sayadaw != null && fe68.sayadaw.length() > 0 ? " · " + fe68.sayadaw : ""), Toast.LENGTH_SHORT).show();
                                    } else if (fe68 != null) {
                                        // v11.68: refused handover — the owner has no catalog page, so
                                        // this head can never play. Manual Next already falls through
                                        // to the in-list talk, but this end block used to stall here
                                        // with no advance at all, and the dead head repeated the stall
                                        // at every natural end. Drop it like a stale line, then advance
                                        // in-list exactly as the plain branch does. The dead head was
                                        // the queue the repeat-off stop above saw, so the stop is
                                        // re-checked with the queue as it stands now: repeat-off at
                                        // the last talk with nothing left waiting still stops instead
                                        // of wrapping; a gated in-list next still stops with the gate
                                        // toast below.
                                        dropRefusedForeignHead68(fe68);
                                        boolean hasLiveQueue68 = firstWaitingForeignEntry() != null || (nx >= 0 && queueLineIndexForUrl(talks.get(nx).url) >= 0);
                                        if (repeatMode == 0 && !shuffleOn && !hasLiveQueue68 && !(nx >= 0 && forwardHistory.contains(talks.get(nx).url)) && currentIndex == talks.size() - 1) {
                                            adapter.notifyDataSetChanged();
                                            Toast.makeText(RoomActivity.this,
                                                    Lang.t(RoomActivity.this, "dl_finished_prefix") + talks.get(currentIndex).title,
                                                    Toast.LENGTH_SHORT).show();
                                        } else if (nx >= 0 && !needsDataGate(talks.get(nx))) advanceInListAtEnd68(nx);
                                        else {
                                            adapter.notifyDataSetChanged();
                                            // v12.10: record the deferred advance — the next talk is gated, so
                                            // a Wi-Fi download of it from the gate below re-fires the advance
                                            // when it lands (service receiver); no next talk records nothing.
                                            noteGatedAdvanceTarget(nx);
                                            Toast.makeText(RoomActivity.this,
                                                    Lang.t(RoomActivity.this, "mobile_no_autoplay"),
                                                    Toast.LENGTH_LONG).show();
                                        }
                                    }
                                }
                            } else if (nx >= 0 && !needsDataGate(talks.get(nx))) {
                                advanceInListAtEnd68(nx);
                            }
                            else {
                                adapter.notifyDataSetChanged();
                                // v12.10: record the deferred advance, as in the refused-handover branch
                                // above — the gated next talk re-fires when its Wi-Fi download lands.
                                noteGatedAdvanceTarget(nx);
                                // v1.97: stopping here used to be
                                // silent — the talk ended, nothing
                                // played, and nothing said why
                                // (a manual tap at least gets the
                                // gate dialog). Say so, once per
                                // ended track (endedHandledFor
                                // above), so the listener taps the
                                // next talk and answers the gate.
                                Toast.makeText(RoomActivity.this,
                                        Lang.t(RoomActivity.this, "mobile_no_autoplay"),
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                        else {
                            // v4.29: autoplay-off ends used to stop
                            // silently — the data-gate stop got its
                            // toast in v1.97 and the advance got its
                            // announcement in v4.14, but this branch
                            // only rebound the list, so a listener
                            // looking away never learned the talk
                            // had finished. Once per ended track
                            // via endedHandledFor above, same text
                            // shape as the advance announcement.
                            adapter.notifyDataSetChanged();
                            Toast.makeText(RoomActivity.this,
                                    Lang.t(RoomActivity.this, "dl_finished_prefix") + talks.get(currentIndex).title,
                                    Toast.LENGTH_SHORT).show();
                            // v4.29: the ended row's ✓ used to snap
                            // in here with no motion, while the
                            // advance path fades it (v3.22) — same
                            // 250ms fade, drawer-open only, ended
                            // row only; a closed drawer pours on
                            // its next open anyway.
                            if (roomList != null && isPlayListPanelOpen()) {
                                final int endedIdxOff = currentIndex;
                                roomList.post(new Runnable() {
                                    @Override public void run() {
                                        if (roomList == null || !isPlayListPanelOpen()) return;
                                        int first = roomList.getFirstVisiblePosition();
                                        // v8.54: display position, not talk index (see rescan fade).
                                        int dp54 = drawerPosOfTalk(endedIdxOff);
                                        android.view.View row = dp54 >= 0 ? roomList.getChildAt(dp54 - first) : null;
                                        if (row != null) Fx.fadeIn(row, 250);
                                    }
                                });
                            }
                        }
                    }
                }
            }
            // v7.52: the drawer header now reads the same fresh
            // marks as its rows, in the same tick — the v7.46
            // sync used to run AFTER this header block, at the
            // end of the poll, so a favourite toggled on the
            // notification card or a talk finishing while the
            // Room sat foreground with its Play List open
            // rebound the rows at once while the header's
            // ★/✓ counts and its Up Next ★/✓ marks kept the
            // old values for one more 500ms tick (the v7.50
            // run accepted that lag; the v7.51 header rule —
            // header follows in the same change branch as the
            // rows — leaves this as the last split in the
            // family). The sync moves here, before the header,
            // and nowhere else: still one call per poll, still
            // change-only, still skipped while a move is armed
            // (so a rebind can never disturb held-row/drop
            // logic — during an armed move the header keeps
            // its one-tick lag by design), still drawer-open
            // only, and the header's own change-only guard
            // still owns every write below.
            syncOpenDrawerMarks46();
            // v4.12: the drawer header counts its queue — "Play List
            // (24)" — so the queue's size reads without counting
            // rows. Language-neutral digits in parentheses, so no
            // Lang key is needed; change-only, so a steady list
            // never rewrites the header, and applyLangLabels still
            // owns the base label on a language swap (the next tick
            // re-adds the count, within 500ms).
            try {
                android.widget.TextView plT = findViewById(R.id.roomPlayListTitle);
                if (plT != null && talks != null && !talks.isEmpty()) {
                    String want = Lang.t(RoomActivity.this, "playlist")
                            + " (" + talks.size() + ")";
                    // v4.24: the header also counts how many of this
                    // list are already offline ("· ⬇ M"), so the
                    // queue's offline share reads without counting
                    // ⬇ marks. Zero keeps the plain count, like the
                    // library footer's favourite count (v4.24).
                    int dlCount = 0;
                    for (int i = 0; i < talks.size(); i++)
                        if (isDownloadedFile(talks.get(i).url)) dlCount++;
                    if (dlCount > 0) want += " · ⬇ " + dlCount;
                    // v4.26: the header also counts favourites
                    // ("· ★ M"), mirroring the v4.24 offline
                    // count beside it - the queue's favourite share
                    // reads without counting stars. Zero keeps the
                    // plain count, like the offline count does.
                    int favCount = 0;
                    for (int i = 0; i < talks.size(); i++)
                        if (favSet.contains(talks.get(i).url)) favCount++;
                    if (favCount > 0) want += " · ★ " + favCount;
                    // v4.27: the header also counts downloads
                    // still queued ("· ⏳ N") — the rows already
                    // carry their own ⏳ marks (v4.01/v4.24),
                    // but the queue's pending share never read
                    // in the header beside ⬇/★. Queued here
                    // means waiting for Wi-Fi or mid-download,
                    // never an already-saved talk.
                    int queueCount = 0;
                    for (int i = 0; i < talks.size(); i++)
                        if (!isDownloadedFile(talks.get(i).url)
                                && queuedDownloads.contains(talks.get(i).url)) queueCount++;
                    if (queueCount > 0) want += " · ⏳ " + queueCount;
                    // v4.32: the hand queue reads in the header too
                    // ("· ◈ N") — distinct from the ⏳ download
                    // queue above, so a listener sees at a glance
                    // that Next will not follow the list order.
                    // v11.04: the playing identity is the
                    // scan-proven sounding talk
                    // (soundingUrlForQueue), not the session
                    // lastUrl alone — in the background-advance
                    // window the service already sounds the next
                    // talk while lastUrl still names the finished
                    // one, so this header counted the sounding
                    // talk as waiting (one too many) while the
                    // clear dialog (v10.92) and the queue-only
                    // gate counted it as playing. The scan
                    // falls back to the indexed talk, then to
                    // this sayadaw's session lastUrl, exactly
                    // like those two sites — and like the row
                    // marks (soundingIndexForBind) — so header,
                    // rows, dialog and gate now agree in every
                    // window, and a normal play (already popped)
                    // still changes nothing.
                    String playingUrl = soundingUrlForQueue();
                    // v7.13: the hand-queue count is parse-aware —
                    // the old loop walked this list's talks with a
                    // raw contains, so a queue holding only foreign
                    // (other-sayadaw v2) lines read as NO queue here
                    // ("· ◈" missing) while the queue-only drawer
                    // showed ◈ rows and Next handed over to them —
                    // the widening v7.08/v7.12 gave the rows never
                    // reached this header. Count the displayable
                    // queue lines instead (drawerQueueDisplayLines:
                    // in-list lines plus foreign v2 lines, stale
                    // lines already skipped), still skipping the
                    // talk now playing under the v5.81 rule, and
                    // deduped by URL so a repeated line counts
                    // once, exactly as the old talk loop did.
                    int handQ = 0;
                    java.util.ArrayList<String> handSeen =
                            new java.util.ArrayList<String>();
                    for (String handLine : drawerQueueDisplayLines()) {
                        String handUrl = queueLineUrl(handLine);
                        if (handUrl.length() == 0 || handSeen.contains(handUrl)) continue;
                        handSeen.add(handUrl);
                        if (!handUrl.equals(playingUrl)) handQ++;
                    }
                    if (handQ > 0) want += " · ◈ " + handQ;
                    // v5.64: the header also counts finished talks
                    // ("· ✓ M") — the one share the count line never
                    // carried: total, offline, favourites, pending
                    // downloads and the hand queue all read here
                    // (v4.12/v4.24/v4.26/v4.27/v4.32), but a listener
                    // working through a sayadaw's list could not see
                    // how many talks they had already heard without
                    // counting ✓ marks down the rows. Same pattern
                    // as the ★ count beside it: playedSet membership
                    // (refreshed on finish, like the Up Next line's
                    // ✓), zero keeps the plain line, and the
                    // change-only guard still owns the write, so the
                    // count steps within one 500ms tick of a finish.
                    // Membership, not the row's playing exclusion:
                    // a finished talk being replayed still counts
                    // as heard, like ★ counts a favourited talk
                    // while it plays.
                    int playedCount = 0;
                    for (int i = 0; i < talks.size(); i++)
                        if (playedSet.contains(talks.get(i).url)) playedCount++;
                    if (playedCount > 0) want += " · ✓ " + playedCount;
                    // v5.69: the header also counts half-heard talks
                    // ("· ↺ M") — the one share the count line never
                    // carried: total, offline, favourites, pending
                    // downloads, hand queue and finished talks all
                    // read here (v4.12/v4.24/v4.26/v4.27/v4.32/v5.64),
                    // but a listener with several talks stopped
                    // mid-way could not see how many without
                    // counting ↺ marks down the rows. Same rule as
                    // the row marks (v5.34/v5.59): saved spot over
                    // 5s under the stream URL, or under the
                    // downloaded-file path when already offline;
                    // the playing row is excluded, so the count
                    // always agrees with the ↺ marks on the rows.
                    // One parse via talkPosKeysOver, not a
                    // talkPosGet per talk per poll tick (v1.57
                    // jank class); downloadedFileName comes from
                    // the cache alone, no filesystem work.
                    int resumeCount = 0;
                    java.util.Set<String> resumeKeys =
                            Prefs.talkPosKeysOver(RoomActivity.this, 5000);
                    if (!resumeKeys.isEmpty()) {
                        for (int i = 0; i < talks.size(); i++) {
                            if (i == currentIndex) continue;
                            String u = talks.get(i).url;
                            if (resumeKeys.contains(u)) { resumeCount++; continue; }
                            if (isDownloadedFile(u)) {
                                String dlName = downloadedFileName(u);
                                if (dlName != null && resumeKeys.contains(
                                        downloadsFile(dlName).getAbsolutePath()))
                                    resumeCount++;
                            }
                        }
                    }
                    if (resumeCount > 0) want += " · ↺ " + resumeCount;
                    // v4.13: Up Next line — the talk a Next tap would
                    // jump to (wrapping at the last talk, exactly
                    // like the tap and the v4.12 long-press preview),
                    // so the queue's immediate future reads without
                    // opening anything. Second line of the same
                    // header view (wrap_content height, no new view);
                    // count-only while no talk is current, and the
                    // change-only guard keeps a steady list from
                    // rewriting the header every tick.
                    if (currentIndex >= 0 && currentIndex < talks.size() && talks.size() > 1) {
                        // v4.17: the Up Next line gains a ⬇ when the
                        // next talk is already saved offline — the
                        // now-playing title got its offline mark in
                        // v4.15, but the imminent talk's line never
                        // said whether tapping Next would stream or
                        // play offline. Same in-memory downloaded
                        // set the rows use, so the poll stays cheap.
                        QueueEntry fxUp = firstWaitingForeignEntry(); // v7.08: a foreign queue head is Up Next, named from its record (it has no row here)
                        if (fxUp != null) {
                            // v8.62: the foreign head used to read bare — title + sayadaw
                            // only — while the in-list next talk below carries its full
                            // mark set (◈/⬇/⏳/★/✓/↺, v4.17/v4.24/v4.26/v5.62/v5.94).
                            // A foreign head is still the talk a Next tap will play
                            // (handover), so it hid exactly the same surprises: a
                            // downloaded talk read like a stream, a finished or
                            // half-heard talk read like an unheard one. Same sources
                            // as the in-list branch (same in-memory sets/maps, same
                            // >5s stream-form spot + downloaded-file-form fallback),
                            // same order (◈ first, then ⬇/⏳, ★, ✓, ↺); the ◈ number
                            // uses the same waiting-ordinal rule (queue index + 1,
                            // minus one when the playing talk sits earlier). The
                            // change-only guard above still owns the write, so the
                            // marks appear within one 500ms tick of landing.
                            String fxMark = "";
                            try {
                                if (queueUrls != null) {
                                    int fxQpos = queueLineIndexForUrl(fxUp.url);
                                    if (fxQpos >= 0) {
                                        int fxShown = fxQpos + 1;
                                        // v12.26: playingUrl is the
                                        // scan-proven sounding URL
                                        // computed in this tick above,
                                        // not the indexed talk.
                                        if (playingUrl != null && playingUrl.length() > 0) {
                                            int fxPlayPos = queueLineIndexForUrl(playingUrl);
                                            if (fxPlayPos >= 0 && fxPlayPos < fxQpos) fxShown--;
                                        }
                                        fxMark = " ◈" + fxShown;
                                    }
                                }
                            } catch (Exception ignored) {}
                            if (isDownloadedFile(fxUp.url)) fxMark += " ⬇";
                            else if (dlProgress.containsKey(fxUp.url)) fxMark += " ⏳" + dlProgress.get(fxUp.url) + "%";
                            if (favSet.contains(fxUp.url)) fxMark += " ★";
                            if (playedSet.contains(fxUp.url)) fxMark += " ✓";
                            int fxRp = Prefs.talkPosGet(RoomActivity.this, fxUp.url);
                            if (fxRp <= 5000 && isDownloadedFile(fxUp.url)) {
                                String fxDlName = downloadedFileName(fxUp.url);
                                if (fxDlName != null)
                                    fxRp = Prefs.talkPosGet(RoomActivity.this,
                                            downloadsFile(fxDlName).getAbsolutePath());
                            }
                            if (fxRp > 5000) fxMark += " ↺ " + formatTime(fxRp);
                            want += "\n" + Lang.t(RoomActivity.this, "next_prefix") + (fxUp.title.length() > 0 ? fxUp.title : fxUp.url) + " · " + fxUp.sayadaw + fxMark;
                        } else {
int nxIdx = peekNextIndex(); // v4.32: Up Next names the queued head first
                        Talk nx = talks.get(nxIdx >= 0 ? nxIdx : (currentIndex + 1) % talks.size());
                        // v4.24: the Up Next line mirrors the row
                        // marks — ⬇ when saved, else its live ⏳
                        // percent while downloading, so tapping Next
                        // never surprises with a stream it could
                        // have waited out. Same in-memory maps, so
                        // the poll stays cheap.
                        String nxMark = "";
                        // v5.94: the Up Next line gains its ◈ queue
                        // position — the imminent talk is the queued
                        // head whenever a queue exists (peekNextIndex
                        // v4.32), yet the line named it with ⬇/⏳, ★,
                        // ✓ and ↺ only, while every row naming that
                        // talk carries its ◈ waiting number (drawer
                        // rows v5.82, Room previews v5.92). Same
                        // waiting-number rule as previewHeadMarks:
                        // queue index + 1, minus one when the playing
                        // talk sits earlier in the queue. Shown only
                        // when the imminent talk is actually queued,
                        // so an unqueued list-next reads exactly as
                        // today; first suffix mark, ahead of ⬇/⏳.
                        try {
                            if (queueUrls != null) {
                                // v7.38: line-aware, like previewHeadMarks
                                // above — a v2-spelled in-list imminent
                                // talk used to lose its ◈ here too.
                                int qpos = queueLineIndexForUrl(nx.url);
                                if (qpos >= 0) {
                                    int shown = qpos + 1;
                                    // v12.26: playingUrl is the
                                    // scan-proven sounding URL
                                    // computed in this tick above,
                                    // not the indexed talk.
                                    if (playingUrl != null && playingUrl.length() > 0) {
                                        int playPos = queueLineIndexForUrl(playingUrl);
                                        if (playPos >= 0 && playPos < qpos) shown--;
                                    }
                                    nxMark = " ◈" + shown;
                                }
                            }
                        } catch (Exception ignored) {}
                        if (isDownloadedFile(nx.url)) nxMark += " ⬇";
                        else if (dlProgress.containsKey(nx.url)) nxMark += " ⏳" + dlProgress.get(nx.url) + "%";
                        // v4.26: the Up Next line gains its star when
                        // the imminent talk is a favourite - the
                        // v4.17/v4.24 marks said saved/downloading
                        // but never favourited, while the row itself
                        // (v1.44) always had it.
                        if (favSet.contains(nx.url)) nxMark += " ★";
                        // v5.62: the Up Next line gains the ✓ finished
                        // and ↺ resume marks every other queue surface
                        // already carries — the drawer rows (v5.34
                        // ↺, ✓ since v1.44), the Talks preview (v5.50
                        // ✓, v5.61 ↺) and the library preview (v5.50
                        // ✓, v5.60 ↺) — but this header line, the
                        // first glance at the imminent talk, read a
                        // finished or half-heard talk exactly like
                        // an unheard one. Same sources as the row
                        // bind: playedSet (refreshed on finish) and
                        // the >5s path-form fallback, so the line
                        // always agrees with what a Next tap does.
                        // The change-only guard above still owns
                        // the write, so the marks appear within one
                        // 500ms tick of a finish or a spot landing.
                        if (playedSet.contains(nx.url)) nxMark += " ✓";
                        int nxRp = Prefs.talkPosGet(RoomActivity.this, nx.url);
                        if (nxRp <= 5000 && isDownloadedFile(nx.url)) {
                            String nxDlName = downloadedFileName(nx.url);
                            if (nxDlName != null)
                                nxRp = Prefs.talkPosGet(RoomActivity.this,
                                        downloadsFile(nxDlName).getAbsolutePath());
                        }
                        if (nxRp > 5000) nxMark += " ↺ " + formatTime(nxRp);
                        want += "\n" + Lang.t(RoomActivity.this, "next_prefix") + nx.title + nxMark;
                        }
                    }
                    // v4.25: the drawer header carries the sleep
                    // countdown too — the clock-row view hides
                    // behind the open drawer, so a queue glance
                    // would otherwise lose bedtime entirely. Same
                    // three states as the clock row (armed, fading,
                    // pause-suspended with the Settings guard), as
                    // an in-place suffix on the same header text —
                    // no new view, and the change-only guard above
                    // still owns the write, so the header rewrites
                    // at most once per ticking second.
                    try {
                        if (service != null) {
                            long hRem = service.getSleepRemainingMs();
                            long hSusp = service.getSleepSuspendedMs();
                            if (service.isSleepFading()) want += " · 😴 " + Lang.t(RoomActivity.this, "notif_sleep_fading");
                            else if (hRem > 0) want += " · 😴 " + formatTime((int) hRem);
                            else if (hSusp > 0 && Prefs.sleepMinutes(RoomActivity.this) > 0)
                                want += " · 😴 ⏸ " + formatTime((int) hSusp);
                        }
                    } catch (Exception ignored) {}
                    if (!want.equals(plT.getText().toString())) plT.setText(want);
                }
            } catch (Exception ignored) {}
            // v11.46: resume-mode data gate, asked here — a resume
            // refused by the service gate (Room toggle, strip,
            // notification, headset, focus-gain) leaves the talk
            // paused with the hold flag set, and this foreground
            // poll is the only place that can ask about it: the
            // service has no dialog, and a background advance
            // already defers to this poll the same way. Guards:
            // no question already pending or showing (a HEAD
            // check in flight counts — re-asking would stack a
            // second thread on the same index), not playing (a
            // hold cannot coexist with playback, but the flag is
            // cheap to re-check), and the held talk must be in
            // THIS Room's list — a foreign held talk keeps its
            // flag for its own Room instead of being asked here.
            // A cancel clears the hold (see the dismiss listener)
            // so the poll stays quiet until the next play tap.
            // v12.19: a pending question whose talk the service moved
            // past in the background — a headset/car Next to a
            // downloaded talk while the Room was away, with the dialog
            // still up — ends here instead of asking about a talk
            // nobody is hearing any more. The playIndex supersede
            // above covers the foreground case; this one covers the
            // background advance. A held/playing questioned talk, or
            // no track at all, leaves the question alone.
            if (pendingGateUrl != null && service != null && service.hasTrack()
                    && !isCurrentTalk(pendingGateUrl)) abandonPendingGateQuestion();
            if (service != null && gateDialog == null && pendingGateIndex < 0
                    && service.isResumeGateHeld() && !service.isPlaying()) {
                int hi46 = -1;
                for (int i46 = 0; i46 < talks.size(); i46++)
                    if (isCurrentTalk(talks.get(i46).url)) { hi46 = i46; break; }
                if (hi46 >= 0) {
                    // v12.09: re-validate the hold before asking — the refusal was on mobile
                    // data, but Wi-Fi may have connected, the talk may have finished
                    // downloading, or data saver may have been switched off since, and the
                    // service never clears its hold on a successful resume (see resume()).
                    // A stale hold used to pop the mobile-data question on Wi-Fi on every
                    // pause until answered. If the gate no longer applies, drop the hold
                    // here instead of asking; the next play tap resumes freely.
                    if (!needsDataGate(talks.get(hi46))) {
                        service.clearResumeGateHeld();
                    } else {
                        askDataGate(hi46, service.getPosition(), true);
                    }
                }
            }
            handler.postDelayed(this, 500);
        }
    };

    // v1.47: hour-long talks used to show "65:23" — over an hour now
    // reads "1:05:23" like a clock, so a long talk's length is clear.
    // v13.51: Locale.US on the %02d calls — same Burmese-phone
    // mixed-digit defect as Fx.fmtTime.
    private static String formatTime(int ms) {
        int total = Math.max(0, ms / 1000);
        if (total >= 3600)
            return (total / 3600) + ":" + String.format(java.util.Locale.US, "%02d", (total % 3600) / 60)
                    + ":" + String.format(java.util.Locale.US, "%02d", total % 60);
        return (total / 60) + ":" + String.format(java.util.Locale.US, "%02d", total % 60);
    }

    // v1.51: rotating the phone used to re-randomise the shrine photo
    // and close the open talk list mid-listening — keep both across
    // recreation (playback itself already lived in the service).
    // v1.54: rotating the phone used to refetch the whole talk list
    // over the network (like the Talks screen before v1.53) and, worse,
    // the intent still carried autoUrl, so the auto talk replayed from
    // its saved position with another bell. Keep the list, the playing
    // index and the consumed-auto flag across recreation instead.
    // v2.48: Play List side drawer — slides in from the right.
    // Listener hygiene is deliberate: a stale close listener is what
    // made the old bottom sheet vanish after reopening, so every
    // animation here clears the listener first and the hide
    // end-action clears it again. The drawer stays open when a row
    // is tapped (like the old sheet did) — Play List toggles it shut.
    private boolean isPlayListPanelOpen() {
        android.view.View p = findViewById(R.id.roomPlayListPanel);
        return p != null && p.getVisibility() == View.VISIBLE;
    }
    private void setPlayListPanel(boolean show) {
        final android.view.View panel = findViewById(R.id.roomPlayListPanel);
        if (panel == null) return;
        drawerOpen = show; // v3.82: logical drawer state for the toggle dim
        // v4.92: closing the drawer cancels an armed pick-up/drop
        // move — the held highlight lives on drawer rows, so a
        // reopen must never show a stale ✋ with no drop in mind.
        if (!show) moveHeldUrl = null;
        float off = 320f * getResources().getDisplayMetrics().density;
        // v3.67: premium drawer-close end-action guard — the close
        // listener below used to run unconditionally, and the open
        // path's cancel + setListener(null) only clears it AFTER
        // the cancel: under the v3.19 behaviour (a cancelled
        // ViewPropertyAnimator still fires its end action) a
        // reopen tapped mid-close fired the close end DURING that
        // cancel, hiding the panel (GONE) and resetting its rows
        // and header to 1/0/0 before the open even started — so
        // the v3.36 smooth reversal never happened, the open read
        // midClose=false and poured fresh from off/0 instead of
        // reversing from where the close left it, and a second
        // close mid-close could likewise have its end run under
        // the newer close. Bumping the generation BEFORE the
        // cancel (v3.19 ordering) closes it: a synchronously fired
        // close end sees a stale generation and returns, leaving
        // the mid-close state intact for the open's reversal
        // (v3.36-v3.39) or the newer close's own end; if end
        // actions never fire on cancel the guard is a harmless
        // no-op, and a close left alone keeps its generation, so
        // natural completion is unchanged (panel still goes GONE
        // at 1/0/0 with its rows and header reset).
        // Visual only; open/close targets and durations untouched.
        drawerCloseGen++;
        panel.animate().cancel();
        panel.animate().setListener(null);
        if (show) {
            // v3.36: premium drawer-reopen continuity — the open
            // used to force translationX=off + alpha=0 unconditionally,
            // so a reopen tapped mid-close (panel still VISIBLE, part
            // slid out, part faded) jumped fully out + fully transparent
            // in one frame before sliding back in, the same snap class
            // v3.34/v3.35 fixed for the nudges and the morph. A fresh
            // open is unchanged (panel GONE, so it still starts from
            // off/0, invisible until the slide begins); a mid-close
            // reopen now animates from wherever the close left it to
            // 0/1 over the same 300ms, reading as a smooth reversal.
            // Visual only; open/close targets and duration untouched.
            boolean midClose = panel.getVisibility() == View.VISIBLE;
            panel.setVisibility(View.VISIBLE);
            syncDrawerSearchEmpty(); // v4.98: a list changed while the drawer sat closed re-decides the no-hit line on open
            // v7.43: drawer rows rebind on fresh open — the ★/✓/⬇ marks
            // came from the cached favSet/playedSet/queuedDownloads,
            // refreshed only on onResume, on load, and on this Room's
            // own toggles, while the Room favourite button (v5.20)
            // reads favTalks fresh from prefs on every poll. So a
            // favourite toggled on the notification card while this
            // Room sat foreground (the shade never pauses it, so no
            // onResume fires) left the drawer rows showing the old
            // ★ while the Room button already showed the new one —
            // same screen, two answers, the v7.42 header/strip
            // class. A fresh open now re-reads the marks and rebinds
            // before the slide + cascade pour, so the rows open
            // current. v10.34: the mark refresh now runs on EVERY
            // open, mid-close reopen included -- v7.43's fresh-open
            // rebind left a mid-close reopen showing marks from
            // before the close (a card-toggled ★ during the 300ms
            // close window read stale until the next fresh open).
            // Only the translationX/alpha reset stays !midClose, so
            // the smooth reversal animation is untouched; the
            // rebind touches row views, never the panel transform.
            // Prefs reads only — no layout, duration, or ban touch.
            refreshMarkSets();
            if (adapter != null) adapter.notifyDataSetChanged();
            if (!midClose) {
                panel.setTranslationX(off);
                panel.setAlpha(0f);
            }
            panel.animate().translationX(0f).alpha(1f).setDuration(300)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            // v1.38: opening the list jumps straight to the playing talk.
            // v3.87: premium drawer-open scroll glide — the v3.85
            // already-held glide fixed the playIndex re-mark snap,
            // but this open-path jump still snapped on a mid-close
            // reopen: the panel is VISIBLE part-slid/part-faded
            // (midClose, v3.36), so a list the user had scrolled
            // away from the playing row jumped back to it in one
            // frame while the panel glided back in, the same snap
            // class v3.85 fixed on its path. A mid-close reopen
            // now glides there with the same
            // smoothScrollToPosition the v2.32 fresh-play path
            // uses, riding alongside the v3.39 from-current row
            // pour; a fresh open keeps setSelection on purpose
            // (panel was GONE, so the jump is invisible, and the
            // next cascade pours from the playing row as before).
            // Visual only; the row, the marks and the open slide
            // (300ms) are untouched.
            // v7.68: scan-adopt before the open scroll — the
            // v7.66 toggle long-press adopted, but the open path
            // itself never did, so a PLAIN open (toggle tap,
            // now-playing title tap, altar up-swipe, queue ◈ tap)
            // landing before the v7.59 return poll tick after a
            // v7.58 background advance still scrolled to the
            // FINISHED row instead of the sounding one. Adopting
            // here, inside setPlayListPanel, fixes every open
            // path at once instead of patching each caller; the
            // long-press adopt above stays harmless (a no-op
            // second adopt). Scan-proven sounding index only
            // (stream or offline-file form via isCurrentTalk);
            // a scan miss keeps the old behaviour exactly, and
            // the normal path is a no-op.
            if (currentIndex >= 0 && talks != null) {
                int snd68 = -1;
                for (int si68 = 0; si68 < talks.size(); si68++)
                    if (isCurrentTalk(talks.get(si68).url)) { snd68 = si68; break; }
                // v8.19: adopt-push — a display/step adopt landing in the pre-poll-tick window after a hidden advance used to move the index without recording the talk left behind, so the v8.06/v8.08/v8.18 correctors never fired for it (their !isCurrentTalk guard saw the sounding talk already) and a later Previous skipped it, the v8.18 swipe failure on the fav/drawer/share/preview/button paths. Drain-or-push exactly like the correctors, only when the adopt actually moves a valid index; a scan miss or a no-op adopt keeps the old behaviour exactly.
if (snd68 >= 0 && snd68 != currentIndex && currentIndex >= 0 && currentIndex < talks.size()) {
    if (!drainHiddenTrail08(talks.get(currentIndex).url)) pushPrevHistory16(talks.get(currentIndex).url);
}
if (snd68 >= 0) currentIndex = snd68;
            }
            if (currentIndex >= 0) {
                int sp55 = drawerScrollPos(currentIndex);
                if (sp55 >= 0) {
                    if (midClose) roomList.smoothScrollToPosition(sp55);
                    else roomList.setSelection(sp55);
                }
            }
            // v3.05: a reopen mid-close-cascade must not inherit the
            // close drift — staggerChildren only touches alpha/Y, so
            // clear X here first (it cancels each child animator too).
            // v3.39: premium drawer-row reopen continuity — that
            // unconditional X clear was itself a snap on a mid-close
            // reopen (a row drifted right up to 16dp by the v3.05
            // close jumped back to X 0 in one frame), and the
            // staggerChildren below then forced alpha 0 + 24dp rise
            // on top of it. On a mid-close reopen the child
            // animators are only frozen here — values untouched —
            // and Fx.staggerChildrenFromCurrent animates each row
            // on from its current alpha/X/Y; a fresh open keeps the
            // old clear + pour, since the completed-close end-action
            // already reset its rows to 1/0/0.
            if (roomList != null) {
                for (int i = 0; i < roomList.getChildCount(); i++) {
                    android.view.View c = roomList.getChildAt(i);
                    if (c != null) {
                        c.animate().cancel();
                        if (!midClose) c.setTranslationX(0f);
                    }
                }
            }
            // v3.07: premium drawer-header entrance — the rows have
            // cascaded since v2.10, but the "Play List" title + gold
            // divider only rode the panel's own slide + fade, so the
            // header landed as a flat block ahead of the pour. It now
            // fades + rises 12dp (280ms, divider 40ms behind the
            // title, same rhythm as the v2.15 panel entrance) and
            // settles just as the rows start pouring. A reopen
            // mid-close first cancels any running header dissolve
            // and resets alpha/X/Y, so a stranded half-faded header
            // never carries into the new open; the rotation-restore
            // path sets the panel directly and never calls this.
            // Visual only; no new view, only ids on existing ones.
            // v3.38: premium drawer-header reopen continuity — the
            // v3.36 panel fix left one snap behind: on a mid-close
            // reopen the panel now reverses smoothly from its
            // part-slid state, but the header above still forced
            // alpha 0 + translationX 0 + translationY 12dp, so a
            // title half-dissolved (alpha mid-fade) and drifted
            // right (up to 16dp, v3.07 close) jumped to fully
            // transparent and back to X 0 in one frame before
            // pouring again — the same snap class v3.37 fixed for
            // the title/seek/controls rows. A mid-close reopen
            // now animates from the header's current alpha/X/Y
            // (the close dissolve's X rides back to 0 instead of
            // snapping) over the same 280ms with the same 40ms
            // divider stagger, ending at exactly alpha 1 / X 0 /
            // Y 0. A fresh open is unchanged (the completed-close
            // end-action already reset the header to 1/0/0, so it
            // still starts from 0/12dp and pours visibly).
            // Visual only; targets and duration untouched.
            // v3.80: premium drawer-header close-button pour — the
            // v3.07 entrance above poured only the title + divider,
            // so the ✕ button (top-left since v3.74, same header
            // row) landed flat ahead of the pour, riding only the
            // panel's own slide + fade while its row-mates rose in.
            // It now joins the entrance as the third header view
            // (same 280ms, same 12dp rise, 80ms stagger behind the
            // divider), so the whole header row pours as one. The
            // mid-close from-current rule above covers it for free
            // (the !midClose guard skips the forced 0/12dp start),
            // its press squash + morph live on scale, a property
            // this alpha/X/Y animation never touches, and the
            // rotation-restore path sets the panel directly and
            // never calls this. Visual only; the panel slide, the
            // row cascade and the title/divider rhythm untouched.
            {
                float hdrDy = 12f * getResources().getDisplayMetrics().density;
                android.view.View[] hdr = new android.view.View[] {
                        findViewById(R.id.roomPlayListTitle),
                        findViewById(R.id.roomPlayListDivider),
                        findViewById(R.id.roomPlayListClose) };
                for (int i = 0; i < hdr.length; i++) {
                    android.view.View h = hdr[i];
                    if (h == null) continue;
                    h.animate().cancel();
                    if (!midClose) {
                        h.setAlpha(0f);
                        h.setTranslationX(0f);
                        h.setTranslationY(hdrDy);
                    }
                    h.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(280)
                            .setStartDelay(i * 40)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                }
            }
            // v3.39: mid-close reopen flows on from current row
            // values (see the freeze above); fresh open pours.
            if (midClose) Fx.staggerChildrenFromCurrent(roomList);
            else Fx.staggerChildren(roomList); // v2.10: rows cascade in
        } else {
            // v3.05: close cascade, mirroring the v2.10 open cascade —
            // the drawer used to slide out as one opaque block while
            // the open cascaded row by row. Visible rows now fade +
            // drift right, staggered last-visible-first, inside the
            // same slide-out window, so the list dissolves as the
            // drawer leaves instead of riding out fully opaque.
            // v3.41: premium close-cascade window fit — the cascade
            // above was timed to finish inside the panel's 300ms
            // slide, but its own math ran past it: 220ms + up to
            // 10 x 18ms of stagger = 400ms, so the top rows (which
            // start dissolving last) were still mid-fade when the
            // panel's slide ended and hid them — their dissolve was
            // always cut off, and they rode out visibly less faded
            // than the rows below. The cascade now lands inside
            // the window: 190ms + up to 10 x 10ms = 290ms, so every
            // visible row finishes dissolving before the panel
            // hides. Same last-visible-first order, same 16dp drift
            // and alpha-0 targets, so the mid-close reopen freezes
            // (v3.36-v3.39) and the close end-action reset below
            // read exactly the same values, only earlier; the
            // header dissolve (250ms) already fit and is untouched.
            // Visual only; the panel slide itself is untouched.
            // v3.44: premium drawer-close vertical settle — the
            // cascade above dissolved alpha + X only, so a close
            // tapped mid-open-pour left rows (up to 24dp of rise
            // still owed) and the header (up to 12dp) dissolving
            // while still dropped down; v3.43 only fixed the
            // settled state after the panel hid. Both now also
            // animate translationY to 0 inside their existing
            // durations/staggers, so the dissolve carries home
            // vertically too. A settled close is unchanged (Y
            // already 0, a no-op). Visual only.
            if (roomList != null) {
                float density = getResources().getDisplayMetrics().density;
                int n = roomList.getChildCount();
                for (int i = 0; i < n; i++) {
                    android.view.View c = roomList.getChildAt(i);
                    if (c == null) continue;
                    c.animate().cancel();
                    c.animate().alpha(0f).translationX(16f * density).translationY(0f).setDuration(190)
                            .setStartDelay(Math.min(n - 1 - i, 10) * 10)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                }
            }
            // v3.07: header dissolve, mirroring the v3.05 row
            // dissolve — the title + divider used to ride the panel
            // out fully opaque while the rows dissolved around them,
            // leaving a solid header as the last thing visible. They
            // now fade + drift right 16dp (220ms, divider 30ms behind
            // the title) inside the same slide-out window, so the
            // whole drawer dissolves, not just its rows. The close
            // end-action below resets them with the rows, and the
            // open path resets them again on a mid-close reopen.
            // v3.80: premium drawer-header close-button dissolve —
            // the mirror of the v3.80 open pour above: the ✕ button
            // (top-left since v3.74) was the one header view this
            // dissolve never touched, so on close it stayed fully
            // solid while the title, divider and rows all dissolved
            // around it, then rode the panel out as a lone opaque
            // button. It now dissolves with them (same 220ms, same
            // 16dp drift, 60ms stagger behind the divider, Y to 0
            // per the v3.44 settle), so the whole header row leaves
            // as one. A close tapped mid-open-pour cancels the
            // button's pour animator and dissolves from its current
            // values (ViewPropertyAnimator starts from current, no
            // forced reset), and the end-action reset below now
            // covers it with the title/divider. Visual only; the
            // panel slide, the row dissolve and the title/divider
            // rhythm untouched.
            {
                float density2 = getResources().getDisplayMetrics().density;
                android.view.View[] hdr = new android.view.View[] {
                        findViewById(R.id.roomPlayListTitle),
                        findViewById(R.id.roomPlayListDivider),
                        findViewById(R.id.roomPlayListClose) };
                for (int i = 0; i < hdr.length; i++) {
                    android.view.View h = hdr[i];
                    if (h == null) continue;
                    h.animate().cancel();
                    h.animate().alpha(0f).translationX(16f * density2).translationY(0f).setDuration(220)
                            .setStartDelay(i * 30)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                }
            }
            // v3.67: capture the generation bumped at the top of
            // this call (after that bump, before this animation) —
            // the end below runs only while it is still current.
            final int closeGen = drawerCloseGen;
            panel.animate().translationX(off).alpha(0f).setDuration(300)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .setListener(new android.animation.AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(android.animation.Animator a) {
                            if (closeGen != drawerCloseGen) return;
                            panel.setVisibility(View.GONE);
                            panel.setTranslationX(0f);
                            panel.setAlpha(1f);
                            panel.animate().setListener(null);
                            // v3.05: reset the cascaded rows so a later
                            // rebind never shows a faded/shifted
                            // recycled row (the open path resets X
                            // again, this covers the settled state).
                            // v3.43: premium drawer-close settled-state
                            // completion — the reset above restored
                            // alpha + X but left translationY wherever
                            // a close tapped mid-open-pour had frozen
                            // it (up to 24dp of rise still owed), so a
                            // row recycled to a position that scrolls
                            // in after the next open's pour — rows
                            // scrolled into view later are never
                            // poured (v2.10) — sat shifted down with
                            // no animation to carry it home, while the
                            // header reset right below it already
                            // restored Y. The settled state is now
                            // truly 1/0/0, matching what the open path
                            // and staggerChildrenFromCurrent's settled
                            // check assume. Visual only; close targets
                            // and duration untouched.
                            if (roomList != null) {
                                for (int i = 0; i < roomList.getChildCount(); i++) {
                                    android.view.View c = roomList.getChildAt(i);
                                    if (c != null) { c.animate().cancel(); Fx.cancelPress(c); c.setAlpha(1f); c.setTranslationX(0f); c.setTranslationY(0f); c.setScaleX(1f); c.setScaleY(1f); }
                                }
                            }
                            // v3.07: same reset for the header dissolve
                            // above, so a later open never starts from
                            // a faded/shifted title or divider.
                            // v3.80: the close button joins this reset
                            // with them, since it now dissolves too —
                            // without it a completed close would leave
                            // the ✕ at alpha 0 / drifted 16dp, and the
                            // next open's fresh path (which starts the
                            // header from 0/12dp only when not mid-close)
                            // would read a stale dissolved state.
                            {
                                android.view.View[] hdr = new android.view.View[] {
                                        findViewById(R.id.roomPlayListTitle),
                                        findViewById(R.id.roomPlayListDivider),
                                        findViewById(R.id.roomPlayListClose) };
                                for (int i = 0; i < hdr.length; i++) {
                                    android.view.View h = hdr[i];
                                    if (h != null) { h.animate().cancel(); Fx.cancelPress(h); h.setAlpha(1f); h.setTranslationX(0f); h.setTranslationY(0f); h.setScaleX(1f); h.setScaleY(1f); }
                                }
                            }
                        }
                    }).start();
        }
        dimPlayListToggle(show); // v3.82: toggle dims with its drawer
    }

    // v3.82: premium drawer-toggle state dim (see the field note) —
    // the toggle brightens/dims with the drawer instead of sitting
    // static above it. From its current alpha, so a mid-close
    // reopen (restore still easing back to 1.0) flows on instead
    // of snapping; a previous dim is cancelled first, but only
    // this animator, never the press/morph scale channel and never
    // the fresh-open entrance — while that entrance is still
    // running it owns the toggle's alpha, so the dim is skipped
    // and the entrance itself ends at exactly 1.0. Visual only;
    // the panel slide, row cascade and header rhythm untouched.
    private void dimPlayListToggle(boolean dim) {
        if (!playListEntranceDone) return;
        android.view.View t = findViewById(R.id.roomPlayList);
        if (t == null) return;
        if (toggleDimAnim != null) toggleDimAnim.cancel();
        toggleDimAnim = android.animation.ObjectAnimator.ofFloat(t, "alpha",
                t.getAlpha(), dim ? 0.8f : 1f);
        toggleDimAnim.setDuration(200);
        toggleDimAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        toggleDimAnim.start();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("themeIndex", themeIndex);
        // v12.50: the altar's owning talk travels with the theme —
        // without it a rotation would forget which talk the restored
        // altar belongs to and re-roll on its replay/resume.
        outState.putString("themeTalkUrl", themeTalkUrl);
        // v12.51: the pending (unconfirmed) roll travels with the
        // owned theme — a rotation mid-buffer must not lose the
        // wait (the restored poll confirms it on the next tick).
        outState.putString("pendingThemeUrl", pendingThemeUrl);
        // v11.41: the clock's remaining-time display survives rotation
        // with everything else — it was the one Room display toggle
        // kept only in memory, so rotating while showing "-mm:ss"
        // silently flipped the total back to the full length (the
        // 500ms poll rewrites the total from this flag, so the
        // restore in onCreate below lands before the first tick).
        outState.putBoolean("showRemaining", showRemaining);
        // v3.88: save the living-scene state with the photo index
        // (see RoomView.restoreScene) — without it a rotation
        // while playing rebuilt the shrine dark and relit it.
        if (roomView != null) {
            outState.putBoolean("roomLit", roomView.isLit());
            outState.putFloat("roomLitBlend", roomView.getLitBlend());
            outState.putLong("roomDriftMs", roomView.getDriftMs());
            // v3.89: carry the incense wisps too — v3.88 restored
            // the light and the drift but the smoke restarted empty.
            outState.putFloatArray("roomSmoke", roomView.getSmokeState());
        }
        // v11.90: save the LOGICAL drawer state, not the panel's visibility — during the
        // 300ms close animation drawerOpen is already false while the panel is still
        // VISIBLE, so a rotation in that window restored the drawer fully open after
        // the user had tapped close.
        outState.putBoolean("listVisible", drawerOpen);
        java.util.ArrayList<String> titles = new java.util.ArrayList<String>();
        java.util.ArrayList<String> urls = new java.util.ArrayList<String>();
        for (Talk t : talks) { titles.add(t.title); urls.add(t.url); }
        outState.putStringArrayList("talkTitles", titles);
        outState.putStringArrayList("talkUrls", urls);
        outState.putInt("currentIndex", currentIndex);
        // v4.32: the hand queue survives rotation with the list.
        outState.putStringArrayList("queueUrls", new java.util.ArrayList<String>(queueUrls));
        // v5.79: the armed pick-up/drop move survives rotation
        // with the queue — restored only when still queued.
        if (moveHeldUrl != null) outState.putString("moveHeldUrl", moveHeldUrl);
        outState.putStringArrayList("prevHistory", new java.util.ArrayList<String>(prevHistory)); // v5.16
        outState.putStringArrayList("forwardHistory", new java.util.ArrayList<String>(forwardHistory)); // v8.26
        outState.putLong("seenHistoryClearGen36", seenHistoryClearGen36); // v8.36
        outState.putBoolean("drawerQueueOnly", drawerQueueOnly); // v4.55
        outState.putInt("drawerFilterMode", drawerFilterMode); // v4.96
        outState.putString("drawerSearch", drawerSearchQuery); // v4.98
        // v2.03: keep the list's scroll position too — a rotation
        // with the list open but nothing playing dropped back to
        // the top (the playing case already jumps to the playing
        // talk, so this only fills the nothing-playing case).
        // v13.26 (D2): the position is a DISPLAY position in the
        // pre-rotation view — a queue-only / filter / search row
        // number, not a talk index — so save the talk shown at it
        // too: the restore scrolls to the same talk in whatever
        // view the rotation produced, instead of applying the old
        // number to a view it was never taken in. A foreign
        // queue-only row has no talk (-1) and saves nothing, so
        // the restore falls back to the guarded position below.
        if (roomList != null) {
            outState.putInt("listScrollPos", roomList.getFirstVisiblePosition());
            int ti26 = drawerTalkIndex(roomList.getFirstVisiblePosition());
            if (ti26 >= 0 && ti26 < talks.size())
                outState.putString("listScrollTalkUrl", talks.get(ti26).url);
        }
        // v1.67: a tap queued before the bind used to vanish on
        // rotation — the fields lived only in memory, so the recreated
        // activity never knew a tap was waiting for the service.
        outState.putInt("pendingPlayIndex", pendingPlayIndex);
        outState.putInt("pendingSeekMs", pendingSeekMs);
        outState.putBoolean("pendingBypass", pendingBypass);
        // v1.69: the data-gate question + the ended-track marker (see
        // the fields) — both are in-memory only, like the queued tap.
        outState.putInt("pendingGateIndex", pendingGateIndex);
        outState.putString("pendingGateUrl", pendingGateUrl);
        outState.putString("pendingGateBackUrl", pendingGateBackUrl); // v12.19
        outState.putInt("pendingGateSeek", pendingGateSeek);
        outState.putBoolean("pendingGateResume", pendingGateResume); // v11.46: resume-mode question travels too
        // v8.15: the history-back marker travels with the question —
        // without it, a rotation during a history-back gate question
        // lost the popped entry even though the question itself was
        // re-asked: the recreated Room's dismiss had no marker to
        // restore from. Restored beside pendingGateIndex below; the
        // dismiss listener clears it on resolution, so it can never
        // linger past the re-asked question to suppress a later play.
        if (historyBackUrl != null) outState.putString("historyBackUrl", historyBackUrl);
        outState.putString("endedHandledFor", endedHandledFor);
        // v1.76: the pending notification-Next flag (see onCreate) —
        // without this a Next queued before rotation was lost, and
        // with only the intent it fired twice (see above).
        outState.putBoolean("pendingNext", pendingNext);
        outState.putBoolean("pendingPrev", pendingPrev); // v4.23: same rotation rule as Next
        outState.putBoolean("pendingQueue", pendingQueue); // v4.76: same rotation rule as Next
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_room);
        // v13.07: the altar is dark in both themes — dark system bars always.
        Fx.applySystemBars(this, true);
        // v13.03: real liquid glass on the bottom panel — the altar
        // behind it is genuinely blurred (see GlassBlur).
        android.view.View bottomPanel = findViewById(R.id.roomBottomPanel);
        if (bottomPanel != null) GlassBlur.attachPanel(bottomPanel);
        // v1.38: keep-screen-on was only applied on the Talks chooser,
        // never here where listening actually happens — honour it too.
        if (Prefs.keepScreenOn(this))
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sayadawName = getIntent().getStringExtra("name");
        sayadawGroup = getIntent().getStringExtra("group");
        pageUrl = getIntent().getStringExtra("url");
        photoUrl = getIntent().getStringExtra("photo");
        autoUrl = getIntent().getStringExtra("autoUrl");
        autoFromPlaylist = getIntent().getBooleanExtra("fromPlaylist", false);
        // v8.36: observe the current history-clear generation as
        // this Room's baseline — a fresh Room's stacks start empty
        // so nothing is dropped; a recreated Room restores its
        // pre-clear generation from the bundle instead, so the
        // onResume check below still fires for a clear that
        // happened while it sat in the back stack.
        if (savedInstanceState != null && savedInstanceState.containsKey("seenHistoryClearGen36"))
            seenHistoryClearGen36 = savedInstanceState.getLong("seenHistoryClearGen36", -1);
        else
            seenHistoryClearGen36 = getSharedPreferences("dhamma", MODE_PRIVATE)
                    .getLong("historyClearGen", 0);
        // v1.76: a notification Next used to fire TWICE across a
        // rotation — the recreated activity re-read the same
        // ACTION_NEXT intent and advanced again, skipping a talk.
        // The intent only arms the flag on first creation; a
        // recreation restores the flag from the bundle instead, so
        // an already-handled Next (pendingNext false) never re-fires
        // while a still-queued one (true) still survives to the bind.
        if (savedInstanceState != null) {
            // v11.41: restore the clock display mode with the rest of
            // the rotation state (see onSaveInstanceState) — early
            // in onCreate, before the clock wiring and the poll, so
            // the first total rewrite already shows remaining time.
            showRemaining = savedInstanceState.getBoolean("showRemaining", false);
            pendingNext = savedInstanceState.getBoolean("pendingNext", false);
            pendingPrev = savedInstanceState.getBoolean("pendingPrev", false); // v4.23
            pendingQueue = savedInstanceState.getBoolean("pendingQueue", false); // v4.76
        } else if (ACTION_NEXT.equals(getIntent().getAction())) pendingNext = true;
        else if (ACTION_PREV.equals(getIntent().getAction())) pendingPrev = true; // v4.23
        else if (ACTION_QUEUE.equals(getIntent().getAction())) pendingQueue = true; // v4.76
        roomView = findViewById(R.id.roomView);
        // v4.14: the altar photo itself is now a control surface —
        // it previously took no touch at all, so a listener here
        // cannot steal anything. Tap toggles play/pause through
        // the Play button's own click path (every guard — no
        // service, no track, dead-player retry — is reused, not
        // duplicated); a horizontal fling changes talk (left =
        // next, right = previous, wrapping exactly like the Next
        // tap, armNewRowFade so no row morph fires). While the
        // Play List drawer is open, either gesture just closes
        // it — the standard sheet outside-tap, never a surprise
        // skip. Thresholds: 100dp to count as a fling, 12dp to
        // count as a tap; a fling must beat its vertical travel.
        if (roomView != null) roomView.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            boolean fling;
            boolean downActive = false;
            boolean longFired = false;
            Runnable pendingLongPress = null;
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                float density = getResources().getDisplayMetrics().density;
                switch (e.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getX(); downY = e.getY(); fling = false;
                        downActive = true; longFired = false;
                        // v4.19: altar long-press favours the playing
                        // talk (see togglePlayingFav) — a held,
                        // still finger only: any move past the
                        // 12dp tap threshold, a fling, or finger-up
                        // cancels the pending hold, so taps and
                        // swipes never mis-fire a favourite, and a
                        // fired hold consumes its UP below. While
                        // the drawer is open the hold does nothing
                        // (the tap branch closes the drawer).
                        pendingLongPress = new Runnable() {
                            @Override public void run() {
                                if (!downActive || longFired) return;
                                if (isPlayListPanelOpen()) return;
                                longFired = true;
                                togglePlayingFav();
                            }
                        };
                        v.postDelayed(pendingLongPress, 600);
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (!fling && Math.abs(e.getX() - downX) > 100f * density
                                && Math.abs(e.getX() - downX) > Math.abs(e.getY() - downY)) fling = true;
                        if (downActive && (Math.abs(e.getX() - downX) > 12f * density
                                || Math.abs(e.getY() - downY) > 12f * density)) {
                            downActive = false;
                            if (pendingLongPress != null) v.removeCallbacks(pendingLongPress);
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_UP: {
                        downActive = false;
                        if (pendingLongPress != null) v.removeCallbacks(pendingLongPress);
                        if (longFired) return true;
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        if (fling || (Math.abs(dx) > 100f * density && Math.abs(dx) > Math.abs(dy))) {
                            if (isPlayListPanelOpen()) { setPlayListPanel(false); return true; }
                            if (talks.isEmpty()) {
                                Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "no_talk_yet"), Toast.LENGTH_SHORT).show();
                            } else if (currentIndex >= 0) {
                                armNewRowFade = true;
                                // v7.61: same scan-adopt as handleNext (v7.60) — a
                                // v7.58 background advance can leave currentIndex
                                // naming the finished talk while the service
                                // already sounds the next one, and a swipe that
                                // lands before the return poll tick re-syncs
                                // would otherwise step from the stale index
                                // (next from the finished talk, prev to the
                                // talk before it). The scan already proves
                                // which talk sounds, so adopting it is a no-op
                                // whenever the poll is fresh; a miss keeps
                                // the old currentIndex behaviour exactly.
                                // Swipe-prev keeps its hard-step rule (no
                                // 3s restart — that belongs to handlePrev
                                // alone), only its step origin moves to the
                                // scan index.
                                int snd61 = -1;
                                for (int si61 = 0; si61 < talks.size(); si61++)
                                    if (isCurrentTalk(talks.get(si61).url)) { snd61 = si61; break; }
                                // v8.18: same adopt-push as handleNext /
                                // handlePrev (v8.07/v8.08) for the swipe
                                // first-mover case those two cannot cover:
                                // a swipe landing before the return poll
                                // tick re-syncs adopts the sounding index
                                // above, overwriting the finished talk
                                // without recording it, so the finished
                                // talk (and any chained hidden-advance
                                // intermediates) never entered
                                // prevHistory and a later button
                                // Previous skipped straight past it.
                                // Push the left-behind talk (draining
                                // the hidden trail first, exactly as
                                // the button paths do) before either
                                // swipe step; a no-op when the index
                                // already names the sounding talk, a
                                // scan miss keeps the old behaviour
                                // exactly, and the swipe steps
                                // themselves are untouched — swipe-next
                                // still follows the queue, swipe-prev
                                // still takes its hard list step with
                                // no 3s restart.
                                int old61 = currentIndex;
                                if (snd61 >= 0) currentIndex = snd61;
                                if (snd61 >= 0 && snd61 != old61
                                        && old61 >= 0 && old61 < talks.size()) {
                                    if (!drainHiddenTrail08(talks.get(old61).url)) {
                                        pushPrevHistory16(talks.get(old61).url);
                                    }
                                }
                                if (dx < 0) { if (!tryForeignHandover()) { dropRefusedForeignHead68(firstWaitingForeignEntry()); playIndex(consumeNextIndex()); } } // v4.32: swipe-next follows the queue too; v7.08: foreign head hands over; v11.85: refused head dropped like handleNext (v11.68)
                                else playIndex((currentIndex - 1 + talks.size()) % talks.size());
                            } else playIndex(0);
                        } else if (Math.abs(dy) > 100f * density && Math.abs(dy) > Math.abs(dx)) {
                            // v4.16: vertical altar swipe — up opens
                            // the Play List drawer, down closes it
                            // (the horizontal swipe already closes
                            // an open drawer and steps talks when
                            // closed; a vertical swipe never steps
                            // a talk, so the two axes stay distinct).
                            // A tap is still the small-movement
                            // branch below; an up-swipe with the
                            // drawer already open does nothing.
                            if (dy < 0) { if (!isPlayListPanelOpen()) setPlayListPanel(true); }
                            else if (isPlayListPanelOpen()) setPlayListPanel(false);
                        } else if (Math.abs(dx) < 12f * density && Math.abs(dy) < 12f * density) {
                            if (isPlayListPanelOpen()) setPlayListPanel(false);
                            else if (btnPlayPause != null) btnPlayPause.performClick();
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_CANCEL:
                        downActive = false;
                        if (pendingLongPress != null) v.removeCallbacks(pendingLongPress);
                        return true;
                }
                return false;
            }
        });
        // v1.42: retry appears only when the talk list fails to load.
        // v13.39: RoomActivity onCreate wiring (M9/F5 class, same as
        // Main v13.32, Settings v13.34, Talks v13.36, Downloaded
        // v13.37, DlAdapter v13.38) — every view wired in onCreate
        // is now null-guarded: a renamed or missing id leaves its
        // control unwired instead of exiting the Room on open. All
        // ids exist in activity_room.xml today, so no live behaviour
        // changes; the already-guarded sites (bottomPanel, roomView,
        // sleepText block, sdHeader/sdPhoto, plTitle, plClose,
        // plCloseBottom, roomShuffleBtn, rotation panel/tgl,
        // drawerSearchBox) and the null-safe Fx.entrance / Fx.fadeIn
        // calls are untouched. Fx.press is NOT null-safe, so its
        // four calls are guarded too; the onNewIntent sayadaw
        // twin (9739) is guarded in the same patch.
        // v13.40 rider: this sweep missed btnRetry's long-click +
        // Fx.press and btnPlayPause's Fx.press (their click listeners
        // were guarded, these three sites were not) — guarded now in
        // the same shapes; see the poll/tick + loadTalks status-line
        // guards (v13.40) for the nowPlaying family.
        btnRetry = findViewById(R.id.roomRetry);
        if (btnRetry != null) btnRetry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v2.78: no direct GONE here — loadTalks fades the
                // button out (hideRetry), so a retry tap lands softly
                // like the failure fade-in that showed it (v2.61).
                // v2.84: same retry click beat as the Talks chooser
                // — the Room retry was the other last tappable
                // control without a morph. Scale channel (morph)
                // and alpha channel (hideRetry fade) run side by
                // side, so the pop reads in the 200ms fade-out.
                Fx.morph(v);
                loadTalks(true);
            }
        });
        // v4.19: long-press the Room retry button opens this
        // sayadaw's original dhammadownload.com page in the browser
        // (tap still reloads, v2.84) — the mirror of the Talks
        // retry long-press (v4.18): the button only exists after a
        // failed load, exactly when the listener needs to check
        // whether the site itself is down instead of retrying
        // blind. No page URL, or no browser to take it, toasts
        // instead of crashing.
        if (btnRetry != null) btnRetry.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Fx.tap(RoomActivity.this);
                if (pageUrl == null || pageUrl.length() == 0 || "null".equals(pageUrl)) {
                    Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "no_orig_page"), Toast.LENGTH_SHORT).show();
                    return true;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(pageUrl)));
                } catch (Exception e) {
                    Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "open_failed"), Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        // v2.28: press feedback on the Room retry button, same gap
        // as the Talks chooser's — see TalksActivity.
        if (btnRetry != null) Fx.press(btnRetry);
        // v1.51: restore the pre-rotation photo instead of picking a
        // new one (see onSaveInstanceState).
        if (savedInstanceState != null && savedInstanceState.containsKey("themeIndex")) {
            themeIndex = savedInstanceState.getInt("themeIndex", 0);
            // v12.50: restore the altar's owning talk with the
            // theme — a rotation must not forget which talk the
            // restored altar belongs to (its replay/resume would
            // otherwise re-roll). A bundle saved before v12.50
            // carries no such key and reads null: the first play
            // then rolls, exactly like a fresh open.
            themeTalkUrl = savedInstanceState.getString("themeTalkUrl");
            // v12.51: restore the pending roll with the owned theme
            // (a bundle saved before v12.51 carries no such key and
            // reads null: the wait is simply lost and the next talk
            // change rolls, exactly like a fresh open).
            pendingThemeUrl = savedInstanceState.getString("pendingThemeUrl");
        } else
            // v12.91: opening theme is a fresh random pick from all
            // six themes on every Room open (his 2026-11-06 order:
            // "ဖွင့်တိုင်း ကျပန်း တစ်မျိုး ပြောင်းပေး" — he only ever
            // saw crystal at night / classic by day under the old
            // v3.92 hour rule). Rotation restores the pre-rotation
            // photo above, exactly as before; the v1.40 auto-theme
            // toggle still freezes per-talk switching when off
            // (pickTheme returns early); per-talk switching stays
            // the random different-theme rule. No new UI element,
            // no new setting.
            themeIndex = themeRandom.nextInt(RoomView.PHOTO_COUNT);
        // v12.49: adopt the theme actually showing (see
        // RoomView.setPhoto) — the saved bundle value may name a
        // theme whose decode failed, so recording the request let a
        // rotation re-request it and change the altar with no talk
        // change (08:28 +07 altar audit, Finding 3).
        themeIndex = roomView.setPhoto(themeIndex);
        // v3.88: restore the living scene on recreation (rotation
        // or process death) before the first draw, so a playing
        // Room reopens lit at its old drift position instead of
        // flashing dark and relighting; a fresh open has no bundle
        // and is untouched, and the poll's setPlaying then no-ops.
        // v3.89: smoke FIRST, so restoreScene's post rule sees the
        // pending wisps and posts the animator for a paused drain.
        if (savedInstanceState != null) {
            roomView.restoreSmoke(savedInstanceState.getFloatArray("roomSmoke"));
            roomView.restoreScene(savedInstanceState.getBoolean("roomLit", false),
                    savedInstanceState.getFloat("roomLitBlend", 0f),
                    savedInstanceState.getLong("roomDriftMs", 0L));
        }
        android.widget.TextView sdName = findViewById(R.id.roomSayadaw);
        if (sdName != null) sdName.setText(
                (sayadawName == null ? "" : sayadawName) + (sayadawGroup == null ? "" : " · " + sayadawGroup));
        refreshSayadawPhoto(); // v5.38: portrait beside the name
        nowPlaying = findViewById(R.id.roomNowPlaying);
        // v2.15: premium panel entrance — the bottom panel's rows
        // fade + rise in order on a fresh open only; a rotation
        // restores the panel as-is and never replays it.
        // v2.21: the shrine photo itself fades in with the panel —
        // it used to pop in instantly while the panel poured in.
        if (savedInstanceState == null) {
            Fx.fadeIn(roomView, 500);
            // v5.30: secondary row joins the entrance last, so it
            // does not pop in while the main row animates.
            Fx.entrance(findViewById(R.id.roomSayadaw), nowPlaying,
                    findViewById(R.id.roomSeekRow), findViewById(R.id.roomControlsRow));
            // v2.71: the Play List button joins the fresh-open entrance
            // as its final step (see the playListEntrance field note).
            android.view.View plBtn = findViewById(R.id.roomPlayList);
            if (plBtn != null) {
                float dy = 24f * getResources().getDisplayMetrics().density;
                plBtn.setAlpha(0f);
                plBtn.setTranslationY(dy);
                android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(plBtn, "alpha", 0f, 1f);
                android.animation.ObjectAnimator t = android.animation.ObjectAnimator.ofFloat(plBtn, "translationY", dy, 0f);
                playListEntrance = new android.animation.AnimatorSet();
                playListEntrance.playTogether(a, t);
                playListEntrance.setDuration(320);
                playListEntrance.setStartDelay(320);
                playListEntrance.setInterpolator(new android.view.animation.DecelerateInterpolator());
                // v3.82: the toggle dim (dimPlayListToggle) must not
                // fight this entrance for the toggle's alpha — both
                // are ObjectAnimators on the same property. The flag
                // clears on end AND on cancel (a cancelled animator
                // may fire only one of them), and onPause clears it
                // too, so a drawer opened inside this 640ms window
                // skips its dim instead of jittering.
                // v3.83: premium entrance-end dim catch-up — that
                // skip used to be permanent for the session: the
                // entrance ended at alpha 1.0 and nothing ever
                // re-applied the dim, so a drawer opened inside
                // its first 640ms read as unowned until a leave/
                // return re-armed it (onResume) or a close/open
                // cycle. The end listener below now catches it
                // up: if the drawer is still open when the
                // entrance lands, the dim glides 1.0 -> 0.8 over
                // the usual 200ms. A drawer closed before the
                // entrance ends reads drawerOpen false and stays
                // bright, exactly as before; the cancel path is
                // untouched (onPause/onDestroy reset bright and
                // onResume re-arms from drawerOpen).
                playListEntranceDone = false;
                playListEntrance.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a2) {
                        playListEntranceDone = true;
                        if (drawerOpen) dimPlayListToggle(true); // v3.83
                    }
                    @Override public void onAnimationCancel(android.animation.Animator a2) { playListEntranceDone = true; }
                });
                playListEntrance.start();
            }
        }
        elapsedText = findViewById(R.id.roomElapsed);
        totalText = findViewById(R.id.roomTotal);
        sleepText = findViewById(R.id.roomSleep);
        // v4.25: the sleep countdown lives in the clock row (see the
        // poll below) — tapping it opens Settings, where the timer
        // is set, and long-pressing cancels it at once, mirroring
        // the Settings sleep button's own long-press (v4.15).
        // The cancel also zeroes the Settings default, or the
        // Room's live sync would re-arm the timer on re-entry
        // (mins > 0, remaining == 0). The view hides at once
        // instead of waiting for the next poll tick, and the toast
        // names what happened; a cancelled timer never reaches
        // the fade, so no fade label can follow it.
        if (sleepText != null) {
            sleepText.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    Fx.tap(RoomActivity.this);
                    Fx.go(RoomActivity.this, new Intent(RoomActivity.this, SettingsActivity.class));
                }
            });
            sleepText.setOnLongClickListener(new android.view.View.OnLongClickListener() {
                @Override public boolean onLongClick(android.view.View v) {
                    Fx.tap(RoomActivity.this);
                    Prefs.setSleepMinutes(RoomActivity.this, 0);
                    try { if (service != null) service.setSleepMinutes(0); } catch (Exception ignored) {}
                    sleepText.setVisibility(android.view.View.GONE);
                    android.widget.Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "sleep_off"), android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
            });
        }
        // v4.12: tap either clock text to toggle total <-> remaining.
        // A tap blip + an at-once rewrite (the next 500ms poll would
        // correct it anyway, but the toggle must feel instant); a
        // talk with no known duration keeps the plain total, and a
        // drag in flight is untouched (this never touches the wave).
        android.view.View.OnClickListener clockToggle = new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                Fx.tap(RoomActivity.this);
                showRemaining = !showRemaining;
                try {
                    if (service != null && service.hasTrack()) {
                        int d = service.getDuration();
                        if (d > 0) totalText.setText(showRemaining
                                ? "−" + formatTime(Math.max(0, d - service.getPosition()))
                                : formatTime(d));
                    }
                } catch (Exception ignored) {}
            }
        };
        if (elapsedText != null) elapsedText.setOnClickListener(clockToggle);
        if (totalText != null) totalText.setOnClickListener(clockToggle);
        // v4.16: long-press either clock resets it to elapsed
        // (tap toggles elapsed/remaining; a listener who flipped
        // to remaining gets one gesture back instead of a
        // second toggle tap). The 500ms poll rewrites the total
        // from showRemaining anyway, so the flag is the whole fix;
        // the immediate set below just lands it without a tick.
        android.view.View.OnLongClickListener clockReset = new android.view.View.OnLongClickListener() {
            @Override public boolean onLongClick(android.view.View v) {
                Fx.tap(RoomActivity.this);
                showRemaining = false;
                try {
                    if (service != null && service.hasTrack()) {
                        int d = service.getDuration();
                        if (d > 0) totalText.setText(formatTime(d));
                    }
                } catch (Exception ignored) {}
                return true;
            }
        };
        if (elapsedText != null) elapsedText.setOnLongClickListener(clockReset);
        if (totalText != null) totalText.setOnLongClickListener(clockReset);
        // v4.14: tap the sayadaw header to return to that sayadaw's
        // talk list (the chooser) — the Room previously had no way
        // back except system Back, which drops the whole Room. No
        // new view: the header text itself is the tap target, same
        // Fx.tap-only beat as Main's app name and the Talks title.
        // A sayadaw with no original page link (Main's no-page case)
        // gets that toast instead of opening an empty chooser.
        android.view.View sdHeader = findViewById(R.id.roomSayadaw);
        if (sdHeader != null) sdHeader.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(RoomActivity.this);
                if (pageUrl == null || pageUrl.length() == 0 || "null".equals(pageUrl)) {
                    Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "no_sayadaw_page"), Toast.LENGTH_LONG).show();
                    return;
                }
                Intent i = new Intent(RoomActivity.this, TalksActivity.class);
                i.putExtra("name", sayadawName);
                i.putExtra("group", sayadawGroup);
                i.putExtra("url", pageUrl);
                i.putExtra("photo", photoUrl == null ? "" : photoUrl);
                Fx.go(RoomActivity.this, i);
            }
        });
        // v5.38: the new portrait is part of the same header — a tap
        // on it does exactly what a tap on the name does (back to
        // the talk list), via performClick so the tap beat and the
        // no-link toast both come from the one listener above.
        android.view.View sdPhoto = findViewById(R.id.roomSayadawPhoto);
        if (sdPhoto != null && sdHeader != null) sdPhoto.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sdHeader.performClick(); }
        });
        // v4.16: long-press the sayadaw header shares the sayadaw
        // (name + original dhammadownload.com page + credit) —
        // the chooser's talk-share (v1.40) lifted to the sayadaw,
        // on the header that already names them. Tap still opens
        // the chooser; a no-link sayadaw gets the tap's toast.
        // suppressBgPause mirrors the row share: opening the
        // chooser must not count as leaving under background-off.
        if (sdHeader != null) sdHeader.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Fx.tap(RoomActivity.this);
                if (pageUrl == null || pageUrl.length() == 0 || "null".equals(pageUrl)) {
                    Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "no_sayadaw_page"), Toast.LENGTH_LONG).show();
                    return true;
                }
                suppressBgPause = true;
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, (sayadawName == null ? "" : sayadawName)
                        + "\n" + pageUrl + Lang.t(RoomActivity.this, "share_source"));
                startActivity(Intent.createChooser(send, Lang.t(RoomActivity.this, "share_sayadaw")));
                return true;
            }
        });
        applyFontSizes();
        seekBar = findViewById(R.id.roomSeek);
        if (seekBar != null) seekBar.setOnWaveSeekListener(new WaveSeekView.OnWaveSeekListener() {
            @Override public void onProgressChanged(WaveSeekView s, int p, boolean fromUser) {
                if (fromUser && service != null) {
                    if (elapsedText != null) elapsedText.setText(formatTime(p));
                    // v12.42: remaining-mode total must follow the
                    // finger too — the poll's total write ("-(dur-pos)")
                    // sits inside the !seeking branch, so while a drag
                    // ran the total froze at its pre-drag remaining
                    // value and the two clocks summed past the duration
                    // (2:00 elapsed + -2:00 total on a 3:00 talk).
                    // The v8.02 hold pin fixed the same disagreement
                    // for the 1500ms settle after release, but the
                    // drag itself — often longer than the hold — was
                    // left out. Mirror it here from the bar max (the
                    // same max scrubTo derived p from, so elapsed +
                    // remaining still equals the bar even when the
                    // live duration was revised mid-drag, v8.03);
                    // change-only, no fade (remaining never fades,
                    // v4.12). Total mode needs nothing — the total
                    // there is the duration, position-independent.
                    if (showRemaining && seekBar != null && seekBar.getMax() > 0) {
                        String remWant = "−" + formatTime(Math.max(0, seekBar.getMax() - p));
                        if (totalText != null && !remWant.equals(totalText.getText().toString()))
                            totalText.setText(remWant);
                    }
                }
            }
            @Override public void onStartTrackingTouch(WaveSeekView s) {
                seeking = true;
                // v7.97: capture the dragged talk (see dragTalkUrl).
                dragTalkUrl = (service != null) ? service.getCurrentUrl() : null;
                if (dragTalkUrl == null && currentIndex >= 0 && currentIndex < talks.size())
                    dragTalkUrl = talks.get(currentIndex).url;
                // v2.48: drag swell now lives inside WaveSeekView; the
                // glide still stops the moment a drag starts (v2.20).
                if (seekGlide != null) { seekGlide.cancel(); seekGlide = null; }
            }
            @Override public void onStopTrackingTouch(WaveSeekView s) {
                seeking = false;
                // v7.97: a drag that outlived its talk is discarded,
                // never applied to the talk that replaced it. The
                // end-of-track poll advance runs while seeking (no
                // !seeking guard there) and playIndex's bar reset
                // is !seeking-guarded, so at release the bar still
                // holds the OLD talk's max and the finger's absolute
                // ms in it — service.seekTo would have landed the
                // NEW talk at that ms (the prepared branch has no
                // clamp at all; only the buffering pending-seek path
                // clamps, to dur-2s, which would land the new talk
                // at its own end and advance it again). Discarding
                // is the fix, not clamping: the drag belonged to a
                // talk that is gone. The deferred bar reset runs
                // here instead, exactly as playIndex would have
                // (glide cancelled, settle hold voided, bar to 0),
                // so the next poll with a known duration takes the
                // fresh-track path for the new talk instead of
                // leaving the old preview up while it buffers. A
                // drag on a talk that is still sounding (same URL,
                // stream or file form), a repeat-one restart, a
                // gated stop or an autoplay-off stop all keep the
                // old behaviour below, and a null service keeps it
                // too — only a real talk change discards.
                if (service != null && dragTalkUrl != null && !isCurrentTalk(dragTalkUrl)) {
                    dragTalkUrl = null;
                    if (seekGlide != null) { seekGlide.cancel(); seekGlide = null; }
                    lastUserSeekMs = 0;
                    if (seekBar != null) {
                        seekBar.setMax(0);
                        seekBar.setProgress(0);
                        seekBar.setBufferedPercent(0);
                        // v13.20: the discard resets bar + clocks
                        // to 0/0, but the wave shape was left
                        // frozen — a stop mid-drag (poll branch)
                        // called setPlaying(false) while seeking,
                        // so the !seeking-guarded resetRipple in
                        // the stop branch never ran, and the lift
                        // landed here with wavy bars at 0:00
                        // until the next play (the v3.27 bug,
                        // reachable again). Only when no talk is
                        // current: a stop cleared the current URL,
                        // so the frozen ripple belongs to nothing.
                        // When the discard follows an advance (or
                        // a tap) to a new talk, the current URL
                        // is that talk and the poll has already
                        // re-armed the wave — flattening here
                        // would dip a live wave at the lift, so
                        // leave it alone.
                        if (service.getCurrentUrl() == null) seekBar.resetRipple();
                    }
                    if (elapsedText != null) elapsedText.setText("0:00");
                    if (totalText != null) totalText.setText("0:00");
                    return;
                }
                // v8.00: a wave drag on a bar with no range seeks
                // NOTHING. While a talk still buffers, the poll's
                // bar writes are !seeking-guarded, so the bar sits
                // at max 0 the whole drag — WaveSeekView.scrubTo
                // no-ops at max 0, the finger never moved the
                // progress, and it still reads 0 at release. The
                // old code then called service.seekTo(0): still
                // buffering it was a harmless no-op, but if the
                // talk prepared mid-drag it jumped the talk back
                // to 0:00, wiping the resume position onPrepared
                // had just applied, and armed the v3.26 settle
                // hold on a seek the user never made. A max of 0
                // at release proves no scrub happened (a scrub
                // needs max > 0 to move progress, and no poll
                // reset runs while seeking), so the release is
                // discarded like a v7.99 cancel: no seekTo, no
                // hold, bar + clocks restored from the live
                // service at once (0/0 while still buffering, so
                // the next poll takes the fresh-track path). A
                // real scrub always has max > 0 here, so UP seeks
                // below are untouched, as are the v7.97 discard
                // above and the strip drags (their UP already
                // requires a known live duration).
                if (seekBar != null && seekBar.getMax() <= 0) {
                    dragTalkUrl = null;
                    if (seekGlide != null) { seekGlide.cancel(); seekGlide = null; }
                    lastUserSeekMs = 0;
                    if (service != null && service.getDuration() > 0) {
                        int dur = service.getDuration();
                        int pos = service.getPosition();
                        if (seekBar != null) {
                            seekBar.setMax(dur);
                            seekBar.setProgress(pos);
                            seekBar.setBufferedPercent(service.getBufferedPercent());
                        }
                        if (elapsedText != null) elapsedText.setText(formatTime(pos));
                        if (totalText != null) totalText.setText(showRemaining
                                ? "−" + formatTime(Math.max(0, dur - pos))
                                : formatTime(dur));
                    } else if (seekBar != null) {
                        seekBar.setMax(0);
                        seekBar.setProgress(0);
                        seekBar.setBufferedPercent(0);
                        if (elapsedText != null) elapsedText.setText("0:00");
                        if (totalText != null) totalText.setText("0:00");
                    }
                    return;
                }
                dragTalkUrl = null;
                // v8.03: a scrub whose duration changed mid-drag is
                // clamped to the LIVE end, never sought past it.
                // The poll's bar writes are !seeking-guarded, so
                // the bar max freezes at the pre-drag duration for
                // the whole drag, and scrubTo turns the finger into
                // an absolute ms against that stale max. If the
                // same talk re-prepares mid-drag with a shorter
                // duration (VBR estimate revised, stream
                // re-encoded under the same URL), the finger's ms
                // can sit past the new end — and the prepared
                // seekTo branch below has NO clamp (the v7.97
                // comment says so; only the buffering pending-seek
                // path clamps, to dur-2s, and the strip's
                // ACTION_SEEK clamps to d). An unclamped past-end
                // seek completes the talk at once, so a scrub
                // inside talk A lands the listener on talk B
                // (autoplay advance) or restarts A (repeat-one) —
                // the scrub's own position is lost. The clamp
                // fires only on a proven stale max (bar max !=
                // live duration, so a normal drag — poll-kept max
                // == duration — is untouched, including a plain
                // drag to the very end) and only past the end
                // (target >= live duration), landing 2s before the
                // end like the v1.66 pending path instead of at
                // the end itself, so the talk plays its ending
                // and completes naturally instead of jumping.
                // Still-buffering releases never reach here with a
                // known duration (v8.00 discards max-0 bars, and
                // the pending path clamps at prepare), and the
                // clamped value feeds the settle hold too, so the
                // hold can actually land (pos >= target) and the
                // v8.02 remaining total (dur - target) stays
                // non-negative. Bar + elapsed are pulled back to
                // the clamped spot at once, so the hold's first
                // frame never shows a past-end preview.
                // v10.37: a wave release with NO bound service arms
                // a phantom settle hold — the v7.97 discard above
                // needs service != null, and the v8.00 branch only
                // catches a max-0 bar, so a real scrub released
                // before onServiceConnected fires (fast Room open
                // + drag) fell through here with service == null:
                // seekTo was skipped, but the hold below was armed
                // anyway (seekFrom defaults to the target, so the
                // direction reads forward and the hold can never
                // land). Once the bind landed, the next polls held
                // bar + clocks at the finger's target for up to
                // 1500ms while the untouched talk played on, then
                // snapped back — a preview of a seek that never
                // happened, in the one window the v7.97 comment
                // left as "a null service keeps it too". The strip
                // drags never had this hole (their UP requires a
                // live session and returns without state). Discard
                // like the v7.99 cancel: no seekTo, no hold, bar +
                // clocks to 0/0, so the next poll after the bind
                // takes the fresh-track path for the real talk. A
                // bound release is untouched, as is the v7.97
                // discard above and the v8.00 max-0 branch.
                if (service == null) {
                    dragTalkUrl = null;
                    if (seekGlide != null) { seekGlide.cancel(); seekGlide = null; }
                    lastUserSeekMs = 0;
                    if (seekBar != null) {
                        seekBar.setMax(0);
                        seekBar.setProgress(0);
                        seekBar.setBufferedPercent(0);
                    }
                    if (elapsedText != null) elapsedText.setText("0:00");
                    if (totalText != null) totalText.setText("0:00");
                    return;
                }
                int seekTarget = s.getProgress();
                if (service != null && seekBar != null) {
                    int liveDur = service.getDuration();
                    if (liveDur > 2000 && seekBar.getMax() != liveDur
                            && seekTarget >= liveDur) {
                        seekTarget = liveDur - 2000;
                        seekBar.setProgress(seekTarget);
                        if (elapsedText != null) elapsedText.setText(formatTime(seekTarget));
                    }
                }
                // v3.29: capture the direction BEFORE seekTo — the
                // pre-seek service position is ahead of a backward
                // target and behind a forward one, which is exactly
                // what the direction-aware poll test needs. The bar
                // itself already sits at the target (the finger put
                // it there), so a backward drag needs no land step,
                // only the hold.
                int seekFrom = (service != null) ? service.getPosition() : seekTarget;
                if (service != null) service.seekTo(seekTarget);
                // v3.26: arm the seek-settle hold (see the field) so
                // the next polls do not snap the bar back to the
                // pre-drag position while this seek is still landing.
                lastUserSeekPos = seekTarget;
                lastUserSeekDir = (seekTarget < seekFrom) ? -1 : 1;
                lastUserSeekMs = android.os.SystemClock.uptimeMillis();
                // v12.64: a tap that snapped onto a bookmark pin —
                // the bar already sits at the pin (WaveSeekView
                // snapped the landing) and the seek above committed
                // it, so the toast names the minute and the jump
                // reads as a bookmark jump, not a scrub. A drag
                // clears the snap in the view, so only a clean
                // tap toasts.
                if (seekBar != null) {
                    int snappedBm = seekBar.consumeSnappedBookmark();
                    if (snappedBm >= 0)
                        android.widget.Toast.makeText(RoomActivity.this,
                                "🔖 " + formatTime(snappedBm),
                                android.widget.Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onCancelTrackingTouch(WaveSeekView s) {
                // v7.99: a system-cancelled wave drag commits
                // NOTHING. Before this, WaveSeekView merged CANCEL
                // into onStopTrackingTouch, so an interrupted scrub
                // (notification shade, incoming interruption,
                // parent interception) sought the talk to wherever
                // the finger happened to be and armed the v3.26
                // settle hold on a seek the user never confirmed
                // — the Room-side mirror of the strip-drag family
                // (v7.93–v7.98), where a dead/stranded drag was
                // already fixed but the wave's own cancel path was
                // never split out. Cancel clears the drag state
                // (seeking, dragTalkUrl, settle hold, glide) and
                // restores bar + clocks from the live service
                // position at once, so the finger preview never
                // lingers until the next poll; with no live
                // duration the bar resets to 0/0 like the v7.97
                // discard, and the next poll takes the fresh-track
                // path once the talk prepares. No seekTo, no hold,
                // no talk change, no queue/download/favourite
                // effect — the talk simply keeps playing where it
                // was. UP releases keep the old behaviour exactly.
                seeking = false;
                dragTalkUrl = null;
                if (seekGlide != null) { seekGlide.cancel(); seekGlide = null; }
                lastUserSeekMs = 0;
                if (service != null && service.getDuration() > 0) {
                    int dur = service.getDuration();
                    int pos = service.getPosition();
                    if (seekBar != null) {
                        seekBar.setMax(dur);
                        seekBar.setProgress(pos);
                        seekBar.setBufferedPercent(service.getBufferedPercent());
                    }
                    if (elapsedText != null) elapsedText.setText(formatTime(pos));
                    if (totalText != null) totalText.setText(showRemaining
                            ? "−" + formatTime(Math.max(0, dur - pos))
                            : formatTime(dur));
                } else if (seekBar != null) {
                    seekBar.setMax(0);
                    seekBar.setProgress(0);
                    seekBar.setBufferedPercent(0);
                    // v13.21: a system CANCEL after a stop (the
                    // v13.20 residual) — the same frozen ripple as
                    // W1, through the cancel path instead of the
                    // lift: the poll stop branch froze the ripple
                    // while seeking, so its !seeking-guarded
                    // resetRipple never ran, and this leg reset
                    // bar + clocks to 0/0 without flattening it.
                    // Flatten only when no talk is current —
                    // hasLiveTrackForQueue is the discriminator
                    // the leg lacked: bound, a stop nulled
                    // currentTitle (hasTrack false); buffering
                    // still counts as a track (hasTrack true from
                    // play, duration 0 until prepared), so its
                    // wave keeps the live ripple and the poll's
                    // fresh-track path re-arms it on prepare;
                    // unbound, the v10.40 statics decide. A lift
                    // after a stop never reaches here (the v7.97
                    // discard flattens it), so this leg was the
                    // only remaining path to a frozen 0:00 bar.
                    if (!hasLiveTrackForQueue()) seekBar.resetRipple();
                    if (elapsedText != null) elapsedText.setText("0:00");
                    if (totalText != null) totalText.setText("0:00");
                }
            }
        });
        btnPlayPause = findViewById(R.id.roomPlayPause);
        // v3.76: UI language — control labels read from Lang (Forward/Next,
        // drawer title, retry). The sayadaw header and talk titles are content
        // and stay Burmese; the play/pause label itself is Lang-driven in the
        // poll sites above, so it follows the language on every flip.
        // v3.86: the four labels now live in applyLangLabels, so the
        // return dip below can re-apply them with the same swap.
        applyLangLabels();

        playGlow = findViewById(R.id.playGlow);
        if (btnPlayPause != null) btnPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v1.48: before the service binds, this used to do
                // nothing at all — queue the shown talk (or the first) instead.
                // v1.57: with no talks loaded at all both branches
                // used to die silently — say so instead.
                // v11.81: no-track Play starts the talk on screen, not talk 0 (see
                // playShownOrFirst81) — after a notification/sleep stop the poll clears
                // currentIndex only on its next tick, so until then the Room still shows
                // the stopped talk and talk 0 was a wrong-talk tap in that window.
                if (service == null) {
                    playShownOrFirst81(false);
                    return;
                }
                if (!service.hasTrack()) {
                    playShownOrFirst81(false);
                    return;
                }
                // v1.58: a failed stream leaves a dead player — toggle
                // no-ops, so retry the same talk instead of going dead.
                // v1.66: retry from the saved position, not from 0 —
                // a stream that died mid-talk used to restart it from
                // the beginning, losing where the listener stopped.
                if (service.hasTrackError() && currentIndex >= 0) {
                    // v7.62: scan-adopt like v7.60/v7.61 — a v7.58
                    // background advance can leave currentIndex
                    // naming the finished talk while the service
                    // holds the FAILED next talk; retrying from
                    // currentIndex outright would replay the
                    // finished talk from its saved spot and never
                    // retry the talk that actually failed. The
                    // scan proves which talk is sounding (stream
                    // or offline-file form); a scan miss keeps the
                    // old behaviour exactly, and on the normal path
                    // the adopt is a no-op.
                    int snd62 = -1;
                    for (int si62 = 0; si62 < talks.size(); si62++)
                        if (isCurrentTalk(talks.get(si62).url)) { snd62 = si62; break; }
                    // v8.19: adopt-push — a display/step adopt landing in the pre-poll-tick window after a hidden advance used to move the index without recording the talk left behind, so the v8.06/v8.08/v8.18 correctors never fired for it (their !isCurrentTalk guard saw the sounding talk already) and a later Previous skipped it, the v8.18 swipe failure on the fav/drawer/share/preview/button paths. Drain-or-push exactly like the correctors, only when the adopt actually moves a valid index; a scan miss or a no-op adopt keeps the old behaviour exactly.
if (snd62 >= 0 && snd62 != currentIndex && currentIndex >= 0 && currentIndex < talks.size()) {
    if (!drainHiddenTrail08(talks.get(currentIndex).url)) pushPrevHistory16(talks.get(currentIndex).url);
}
if (snd62 >= 0) currentIndex = snd62;
                    if (currentIndex >= 0 && currentIndex < talks.size())
                        playIndex(currentIndex, savedPosFor(talks.get(currentIndex).url));
                    return;
                }
                Fx.tap(RoomActivity.this);
                service.toggle();
            }
        });
        if (btnPlayPause != null) Fx.press(btnPlayPause); // v2.08: press feedback on the play control
        // v4.13: long-press Play restarts the current talk from the
        // beginning — no new button on the fixed control row (the
        // ↻ restart used to live there before v1.37). A buffering
        // talk restarts through a fresh play (a bare seekTo(0)
        // while buffering stores nothing — the v1.69 guard only
        // keeps ms > 0 — so it would land on the saved position
        // instead); a paused talk seeks to 0 and resumes; a playing
        // talk just seeks. No-track mirrors the click path above
        // (shown talk, or first talk, or the "no talks" toast). Consumed, so the
        // tap handler never fires after it.
        btnPlayPause.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (service == null || !service.hasTrack()) {
                    // v11.81: restart the shown talk, not talk 0 (see playShownOrFirst81).
                    playShownOrFirst81(true);
                    return true;
                }
                Fx.tap(RoomActivity.this);
                if (service.isBuffering()) {
                    // v7.62: scan-adopt, same stale-index window as
                    // the error retry above — a buffering talk is
                    // the sounding one, so restarting from a stale
                    // currentIndex would restart the finished talk
                    // from 0 and abandon the buffering one. No-op
                    // on the normal path; a scan miss keeps the old
                    // behaviour exactly.
                    int snd62b = -1;
                    for (int si62b = 0; si62b < talks.size(); si62b++)
                        if (isCurrentTalk(talks.get(si62b).url)) { snd62b = si62b; break; }
                    // v8.19: adopt-push — a display/step adopt landing in the pre-poll-tick window after a hidden advance used to move the index without recording the talk left behind, so the v8.06/v8.08/v8.18 correctors never fired for it (their !isCurrentTalk guard saw the sounding talk already) and a later Previous skipped it, the v8.18 swipe failure on the fav/drawer/share/preview/button paths. Drain-or-push exactly like the correctors, only when the adopt actually moves a valid index; a scan miss or a no-op adopt keeps the old behaviour exactly.
if (snd62b >= 0 && snd62b != currentIndex && currentIndex >= 0 && currentIndex < talks.size()) {
    if (!drainHiddenTrail08(talks.get(currentIndex).url)) pushPrevHistory16(talks.get(currentIndex).url);
}
if (snd62b >= 0) currentIndex = snd62b;
                    // v10.93: restart the buffering talk from 0 —
                    // the old playIndex(currentIndex, 0) here never
                    // restarted anything: playIndex's already-holding
                    // early return (v1.56/v1.87) fires for the sounding
                    // buffering talk and only re-marks the row, so
                    // the long-press did nothing but a tap sound
                    // while the prepared branch below restarts from
                    // 0 via seekTo(0); and even past that gate, the
                    // seekMs==0 injection (v1.90) would have resumed
                    // the saved spot instead of 0. restartFromStart
                    // (v10.57) is the purpose-built path — it clears
                    // the pending seek and this talk's saved spots
                    // for an unprepared player while preserving hold
                    // state, exactly like handlePrev's buffering
                    // branch — so the talk now opens from 0 when it
                    // prepares, held or playing as before.
                    service.restartFromStart();
                    return true;
                }
                service.seekTo(0);
                if (!service.isPlaying()) service.resume();
                return true;
            }
        });
        android.view.View nextBtn = findViewById(R.id.roomNext);
        if (nextBtn != null) nextBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v1.39: Next is a manual tap, so at the last talk it wraps
                // to the first instead of dead-ending (auto-advance at the
                // end still respects the autoplay setting, see the poll).
                if (talks.isEmpty()) {
                    Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "no_talk_yet"), Toast.LENGTH_SHORT).show();
                } else if (currentIndex >= 0) {
                    Fx.tap(RoomActivity.this);
                    armNewRowFade = true; // v3.24: the Next button morphs, not the new row
                    // v7.61: scan-adopt like handleNext (v7.60) and the
                    // altar swipe above — the on-screen Next used to
                    // trust currentIndex outright and lean on the
                    // v7.59 return poll tick to have re-synced it
                    // after a v7.58 background advance; a tap that
                    // lands before that tick consumed next from the
                    // finished talk instead of the sounding one.
                    // No-op when the poll is fresh (scan index
                    // already equals currentIndex); a scan miss
                    // keeps the old behaviour exactly.
                    int snd61 = -1;
                    for (int si61 = 0; si61 < talks.size(); si61++)
                        if (isCurrentTalk(talks.get(si61).url)) { snd61 = si61; break; }
                    // v8.19: adopt-push, same as the other sites above — the Next button used to adopt then playIndex(consumeNextIndex()), and playIndex only pushes the adopted (sounding) talk, so the finished talk never entered prevHistory.
if (snd61 >= 0 && snd61 != currentIndex && currentIndex >= 0 && currentIndex < talks.size()) {
    if (!drainHiddenTrail08(talks.get(currentIndex).url)) pushPrevHistory16(talks.get(currentIndex).url);
}
if (snd61 >= 0) currentIndex = snd61;
                    if (!tryForeignHandover()) { dropRefusedForeignHead68(firstWaitingForeignEntry()); playIndex(consumeNextIndex()); } // v4.32: queued head first, else list-next; v7.08: foreign head hands over; v11.85: refused head dropped like handleNext (v11.68) — a catalog-miss head can never play, so keeping it repeated the refusal toast on every Next and inflated the ◈ count

                    Fx.morph(v); // v2.09: Next morphs like Play (v2.07)
                    nudgeNext(v); // v2.34: forward nudge on advance
                } else {
                    armNewRowFade = true; // v3.24: same, first-talk advance
                    playIndex(0);
                    Fx.morph(v); // v2.09: Next morphs like Play (v2.07)
                    nudgeNext(v); // v2.34: forward nudge on advance
                }
            }
        });
        if (nextBtn != null) Fx.press(nextBtn); // v2.08: press feedback on Next
        // v4.12: long-press Next previews the queue — the talk a tap
        // would jump to (wrapping at the last talk, exactly like the
        // tap itself), as a toast. No new view, no new control; the
        // control row stays Forward | Play | Next, and a long-press
        // never advances anything, so it cannot mis-fire a skip.
        if (nextBtn != null) nextBtn.setOnLongClickListener(new android.view.View.OnLongClickListener() {
            @Override public boolean onLongClick(android.view.View v) {
                Fx.tap(RoomActivity.this);
                if (talks.isEmpty() || currentIndex < 0) {
                    Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "no_talk_yet"), Toast.LENGTH_SHORT).show();
                } else {
                    // v7.67: scan-adopt before previewing — the v7.58
                    // background-advance window left this read-only
                    // preview on currentIndex (v7.65 deliberately
                    // left previews alone), so before the v7.59
                    // return poll tick it named the finished talk's
                    // successor; worse, peekNextIndex skips the
                    // currentIndex talk, so with a queue waiting it
                    // could name the sounding talk itself as
                    // "next". Adopting first fixes both paths at
                    // once; a scan miss keeps the old behaviour
                    // exactly, and the normal path is a no-op.
                    int snd67n = -1;
                    for (int si67n = 0; si67n < talks.size(); si67n++)
                        if (isCurrentTalk(talks.get(si67n).url)) { snd67n = si67n; break; }
                    // v8.19: adopt-push — a display/step adopt landing in the pre-poll-tick window after a hidden advance used to move the index without recording the talk left behind, so the v8.06/v8.08/v8.18 correctors never fired for it (their !isCurrentTalk guard saw the sounding talk already) and a later Previous skipped it, the v8.18 swipe failure on the fav/drawer/share/preview/button paths. Drain-or-push exactly like the correctors, only when the adopt actually moves a valid index; a scan miss or a no-op adopt keeps the old behaviour exactly.
if (snd67n >= 0 && snd67n != currentIndex && currentIndex >= 0 && currentIndex < talks.size()) {
    if (!drainHiddenTrail08(talks.get(currentIndex).url)) pushPrevHistory16(talks.get(currentIndex).url);
}
if (snd67n >= 0) currentIndex = snd67n;
                    // v8.63: the preview names the foreign queue head first — every real
                    // Next path (button v7.08, swipe, drawer-header tap, service Next)
                    // tries the foreign handover before any in-list advance, and the
                    // drawer Up Next line (v8.62) already names it, but this preview
                    // fell through to peekNextIndex/list-next, so with a foreign talk
                    // queued it named the WRONG talk (the list successor) as "next".
                    // Read-only: it only names the head, never hands over (a preview
                    // must not advance anything), and the in-list path below is
                    // untouched when no foreign head waits. Marks mirror the v8.62
                    // Up Next foreign branch exactly (◈ waiting ordinal with the
                    // same playing-earlier rule, ⬇/⏳%, ★, ✓, ↺ with the >5s
                    // stream-form spot + downloaded-file-form fallback), so the
                    // toast agrees with what a tap would play.
                    QueueEntry fxPrev63 = firstWaitingForeignEntry();
                    if (fxPrev63 != null) {
                        if (favSet == null || playedSet == null) refreshMarkSets();
                        String fxMark63 = "";
                        try {
                            if (queueUrls != null) {
                                int fxQpos63 = queueLineIndexForUrl(fxPrev63.url);
                                if (fxQpos63 >= 0) {
                                    int fxShown63 = fxQpos63 + 1;
                                    // v12.26: the playing talk is the
                                    // scan-proven sounding one
                                    // (soundingUrlForQueue), not the
                                    // indexed talk.
                                    String playUrl63 = soundingUrlForQueue();
                                    if (playUrl63 != null && playUrl63.length() > 0) {
                                        int fxPlayPos63 = queueLineIndexForUrl(playUrl63);
                                        if (fxPlayPos63 >= 0 && fxPlayPos63 < fxQpos63) fxShown63--;
                                    }
                                    fxMark63 = " ◈" + fxShown63;
                                }
                            }
                        } catch (Exception ignored) {}
                        if (isDownloadedFile(fxPrev63.url)) fxMark63 += " ⬇";
                        else if (dlProgress.containsKey(fxPrev63.url)) fxMark63 += " ⏳" + dlProgress.get(fxPrev63.url) + "%";
                        if (favSet.contains(fxPrev63.url)) fxMark63 += " ★";
                        if (playedSet.contains(fxPrev63.url)) fxMark63 += " ✓";
                        int fxRp63 = Prefs.talkPosGet(RoomActivity.this, fxPrev63.url);
                        if (fxRp63 <= 5000 && isDownloadedFile(fxPrev63.url)) {
                            String fxDlName63 = downloadedFileName(fxPrev63.url);
                            if (fxDlName63 != null)
                                fxRp63 = Prefs.talkPosGet(RoomActivity.this,
                                        downloadsFile(fxDlName63).getAbsolutePath());
                        }
                        if (fxRp63 > 5000) fxMark63 += " ↺ " + formatTime(fxRp63);
                        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "next_prefix") + (fxPrev63.title.length() > 0 ? fxPrev63.title : fxPrev63.url) + " · " + fxPrev63.sayadaw + fxMark63, Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    int pk = peekNextIndex(); // v4.32: preview the queued head, never the list-next
                    int nxIdx = pk >= 0 ? pk : (currentIndex + 1) % talks.size();
                    Talk nx = talks.get(nxIdx);
                    Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "next_prefix") + previewHeadMarks(nx, nxIdx == currentIndex) + nx.title + previewTalkMarks(nx, nxIdx == currentIndex), Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        roomList = findViewById(R.id.roomList);
        adapter = new TalkAdapter();
        if (roomList != null) roomList.setAdapter(adapter);
        if (roomList != null) roomList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                // v7.12: foreign queue row tap — a foreign row has
                // no talk index, so drawerTalkIndex below returns
                // -1 and the old code dropped the tap silently.
                // Tapping it plays it: the v7.08 handover switches
                // the session to the owning sayadaw and plays the
                // talk through the autoUrl path (data-saver gate
                // and saved resume position apply exactly as for
                // a row tap). Queue lines ahead of it are trimmed
                // first, the v4.43 skip semantics for a direct
                // tap of a later queue item; the handover itself
                // drops the picked entry and carries the rest.
                // The trim runs only after a catalog pre-check
                // passes — on a catalog miss the handover refuses
                // with its toast and the queue must stay intact,
                // so the pre-check failure hands straight to the
                // handover instead of trimming first. An armed
                // pick-up/drop move drops the held line BEFORE
                // the foreign row, the same "in front of this
                // one" rule the in-list drop uses, measured on
                // line indices after the removal.
                if (drawerQueueOnly && !drawerSearching()) {
                    QueueEntry fe = drawerForeignEntry(pos);
                    if (fe != null) {
                        if (moveHeldUrl != null) {
                            // v7.15: tapping the held row itself cancels
                            // the arm, exactly like the in-list drop
                            // handler below — a held foreign row used
                            // to fall through to the move-to-end
                            // accident (drop index -1 after its own
                            // removal), while a held in-list row
                            // cancelled. Move-to-end for a held
                            // foreign row lives on its long-press
                            // dialog's third item instead (v6.86
                            // pattern, see the long-press handler).
                            if (fe.url.equals(moveHeldUrl)) {
                                moveHeldUrl = null;
                                Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "move_cancelled"), Toast.LENGTH_SHORT).show();
                                if (adapter != null) adapter.notifyDataSetChanged();
                                return;
                            }
                            // v11.22: a held talk that became sounding
                            // between arm and drop must not move — the
                            // arm dies with a queue_changed toast,
                            // before any drop, like the stale-arm rule.
                            // v11.29: mirror, not the bound-only read —
                            // unbound, the sounding held line passed
                            // this guard and was dropped before the
                            // foreign row, replaying after the next
                            // talk (library v11.28, Room form).
                            // v11.34: held-row mirror (own + foreign owner), not the own-only read.
                            if (isSoundingHeldUrl(moveHeldUrl)) {
                                moveHeldUrl = null;
                                Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                if (adapter != null) adapter.notifyDataSetChanged();
                                return;
                            }
                            int heldLine = queueLineIndexForUrl(moveHeldUrl);
                            if (heldLine < 0) {
                                moveHeldUrl = null;
                                Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                if (adapter != null) adapter.notifyDataSetChanged();
                                return;
                            }
                            // v7.15: drop the ORIGINAL line, not the
                            // bare URL — a held foreign line is a v2
                            // "url⇥sayadaw⇥title" line, and re-adding
                            // the bare URL would strip its sayadaw
                            // and title, so the next load's
                            // filterSavedQueue would drop it as a
                            // non-v2 foreign line. The held title
                            // likewise falls back to the stored v2
                            // title when the held talk is not in
                            // this list (the old "" toast).
                            String heldLineStr = queueUrls.get(heldLine);
                            int heldIdx = talkIndexForUrl(moveHeldUrl);
                            String heldTitle = heldIdx >= 0 ? talks.get(heldIdx).title
                                    : QueueEntry.parse(heldLineStr).title;
                            queueUrls.remove(heldLine);
                            int at = queueLineIndexForUrl(fe.url);
                            queueUrls.add(at >= 0 ? at : queueUrls.size(), heldLineStr);
                            moveHeldUrl = null;
                            saveQueue(null, true);
                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "moved_item") + heldTitle, Toast.LENGTH_SHORT).show();
                            if (adapter != null) adapter.notifyDataSetChanged();
                            return;
                        }
                        // v11.36: tapping the SOUNDING foreign row
                        // pauses/resumes it, like the in-list
                        // playing-row toggle (v1.38/v7.72) — the
                        // old code handed over again, restarting
                        // the talk the listener was already
                        // hearing. Bound, non-error only, exactly
                        // like the in-list toggle's gate: unbound,
                        // the mirror can name a sounding foreign
                        // talk but there is no service handle to
                        // toggle, so it falls through to today's
                        // handover; a track error likewise falls
                        // through, since the handover re-attempt
                        // is the only retry a foreign row has
                        // (playIndex needs a talk index, which a
                        // foreign row has not). The ⏸ mark above
                        // names the current track whether playing
                        // or paused, so the toggle needs no
                        // rebind — the poll owns the pulse, and
                        // the mark stays true either way.
                        // v11.85: an ENDED sounding foreign row restarts in place, like the
                        // in-list row (its toggle has no liveEnded gate) — the old !liveEnded
                        // gate below fell through to a full handover + list reload for a
                        // same-session replay, and a failed reload left the Room empty with
                        // only retry, while the talk could no longer be restarted from here.
                        if (service != null && service.hasTrack()
                                && isCurrentTalk(fe.url)
                                && !service.hasTrackError()
                                && AudioService.liveEnded) {
                            Fx.tap(RoomActivity.this);
                            if (service.isBuffering()) service.restartFromStart();
                            else { service.seekTo(0); if (!service.isPlaying()) service.resume(); }
                            return;
                        }
                        if (service != null && service.hasTrack()
                                && isCurrentTalk(fe.url)
                                && !service.hasTrackError()
                                && !AudioService.liveEnded) {
                            Fx.tap(RoomActivity.this);
                            service.toggle();
                            return;
                        }
                        Fx.morph(v);
                        // v11.75: the catalog pre-check that sat here
                        // is deleted — both its branches called
                        // handoverToForeignTalk(fe) identically, so
                        // it decided nothing (a miss is refused
                        // inside the handover, if anywhere), and
                        // cat[1] was read with no length check.
                        // v7.23: NO pre-trim here -- the old trim
                        // dropped the lines ahead of this row and
                        // the handover saved them away before any
                        // play attempt, so a gate cancel (or a
                        // failed load) ate talks the listener never
                        // skipped past by playing. playIndex's
                        // post-gate trim owns the skip now: on a
                        // real stream start it drops the ahead
                        // lines exactly as the trim did, and on a
                        // cancel or failure they stay queued, like
                        // every other tap path (the in-list row tap
                        // never pre-trimmed either).
                        handoverToForeignTalk(fe);
                        return;
                    }
                }
                // v4.55: queue-only display position -> talk index.
                final int talkIdx = drawerTalkIndex(pos);
                if (talkIdx < 0 || talkIdx >= talks.size()) return;
                // v4.92: drop tap for an armed pick-up/drop move —
                // intercepted BEFORE the morph/play path, so a drop
                // never also plays the tapped talk. Tapping the held
                // row itself cancels; tapping a non-queued row keeps
                // the arm and says which rows are droppable; a held
                // URL gone from the queue (cleared or removed from
                // another menu) clears the stale arm instead of
                // dropping a ghost. The drop inserts the held URL
                // immediately BEFORE the tapped row's URL (index
                // measured after the removal), so the result reads
                // exactly as "put it in front of this one" in both
                // views — queue-only rows and full-list rows alike,
                // since both resolve through drawerTalkIndex above.
                if (moveHeldUrl != null) {
                    // v7.15: line-aware membership, not raw contains
                    // — a held FOREIGN line is a v2 line, so the old
                    // contains(moveHeldUrl) read it as stale, and
                    // remove(bare URL) below would have failed
                    // silently and then duplicated it.
                    if (queueLineIndexForUrl(moveHeldUrl) < 0) {
                        moveHeldUrl = null;
                        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return;
                    }
                    String tappedUrl = talks.get(talkIdx).url;
                    if (tappedUrl.equals(moveHeldUrl)) {
                        moveHeldUrl = null;
                        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "move_cancelled"), Toast.LENGTH_SHORT).show();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return;
                    }
                    // v11.22: same sounding guard as the foreign drop
                    // above — an armed talk that started playing
                    // since the arm must not be dropped anywhere.
                    // v11.29: mirror, not the bound-only read —
                    // same unbound window as the foreign leg.
                    // v11.34: held-row mirror (own + foreign owner), not the own-only read.
                    if (isSoundingHeldUrl(moveHeldUrl)) {
                        moveHeldUrl = null;
                        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return;
                    }
                    if (queueLineIndexForUrl(tappedUrl) < 0) {
                        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_tap_only"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int heldLineIdx = queueLineIndexForUrl(moveHeldUrl);
                    String heldLineStr = queueUrls.get(heldLineIdx);
                    int heldIdx = talkIndexForUrl(moveHeldUrl);
                    String heldTitle = heldIdx >= 0 ? talks.get(heldIdx).title
                            : QueueEntry.parse(heldLineStr).title;
                    queueUrls.remove(heldLineIdx);
                    queueUrls.add(queueLineIndexForUrl(tappedUrl), heldLineStr);
                    moveHeldUrl = null;
                    saveQueue(null, true);
                    Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "moved_item") + heldTitle, Toast.LENGTH_SHORT).show();
                    if (adapter != null) adapter.notifyDataSetChanged();
                    return;
                }
                // v3.24: a row tap owns its row via the morph below —
                // clear any stale new-row arm first, so Fx.fadeIn's
                // animator cancel can never kill that morph mid-spring.
                armNewRowFade = false;
                // v2.40: Room-sheet-row click beat — the sheet row
                // squashed under the finger (v2.08 press) but a tap
                // landing on it had no beat of its own, unlike Talks
                // rows (v2.38) and the library rows in this same run,
                // so switching a talk from the open sheet felt flatter
                // than every other tap. It now pops with the shared
                // Fx.morph beat (v2.09); the press squash's UP
                // spring-back is replaced by the morph exactly as on
                // nav, and the beat reads fully because the sheet
                // stays open over the shrine. Morph touches the row
                // view only, never the title the v2.27 pulse breathes.
                Fx.morph(v);
                // v1.38: tapping the talk that is already playing pauses/
                // resumes it instead of restarting it from the beginning.
                // v1.42: isCurrentTalk also matches offline playback.
                // v7.72: this toggle used to require talkIdx ==
                // currentIndex as well, so in the v7.58
                // background-advance window (Room back in
                // foreground, v7.59 return poll tick not yet run)
                // a tap on the SOUNDING row missed the toggle
                // entirely: currentIndex still named the finished
                // talk, so the tap fell through to playIndex,
                // whose already-holding branch stays put without
                // toggling — a pause tap that did nothing, and a
                // resume tap that did nothing, until the resync
                // tick. isCurrentTalk alone is the scan-proven
                // identity (stream or offline-file form), exactly
                // as the v7.71 row bind and the v7.60-7.68
                // gestures already trust it; adopting the index
                // here is a no-op on the normal path (scan index
                // already equals currentIndex) and makes the
                // poll's title/buttons read the sounding talk
                // at once instead of waiting for the resync tick.
                // A tap on any other row still falls through to
                // playIndex below, unchanged.
                if (service != null && service.hasTrack()
                        && isCurrentTalk(talks.get(talkIdx).url)) {
                    // v8.20: adopt-push, same as the v8.19 sites — this toggle
                    // returns before playIndex, so in the pre-poll-tick window
                    // after a hidden advance it used to adopt the sounding row
                    // without recording the finished talk, and the correctors'
                    // !isCurrentTalk guard then never fired for it: a later
                    // shuffle Previous skipped the finished talk. Drain-or-push
                    // the left-behind talk only when the adopt actually moves a
                    // valid index; the normal path (tap on the already-indexed
                    // row) is a no-op.
                    if (talkIdx != currentIndex && currentIndex >= 0 && currentIndex < talks.size()) {
                        if (!drainHiddenTrail08(talks.get(currentIndex).url)) pushPrevHistory16(talks.get(currentIndex).url);
                    }
                    currentIndex = talkIdx;
                    // v4.20: the toggle branch was silent — a new talk
                    // rings the bell below, but pausing/resuming the
                    // playing row from the drawer had neither blip
                    // nor bell, unlike the Play button's own toggle
                    // (which blips). Blip here only: the new-talk path
                    // below keeps its bell alone, never blip + bell.
                    Fx.tap(RoomActivity.this);
                    // v1.58: same dead-player retry as Play/Pause — a
                    // failed stream must restart, not toggle to nothing.
                    // v1.66: from the saved position, like Play/Pause.
                    if (service.hasTrackError()) playIndex(talkIdx, savedPosFor(talks.get(talkIdx).url));
                    else service.toggle();
                    return;
                }
                playIndex(talkIdx);
            }
        });
        // v1.47: long-press a Room list row to share it (sayadaw + title
        // + original dhammadownload.com link) — same as the chooser's
        // long-press since v1.40, no new button in the clean Room.
        // v6.85 exception: in the queue-only view the same gesture
        // arms the pick-up/drop move instead (see the branch in
        // the handler below); share stays on full-list rows.
        roomList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                // v7.12: foreign queue row long-press shares it
                // (sayadaw + stored title + original link — all
                // three ride in the v2 record). It cannot arm a
                // pick-up/drop move: the arm holds a bare URL and
                // the drop handler's raw contains check would
                // read a v2 line as a stale arm, so the arm
                // gesture stays in-list-only (the branch below)
                // and an already-armed move survives this share
                // untouched, like tapping a non-queued row keeps
                // an arm. Before this the gesture died silently
                // on a foreign row (talkIdx -1, return true).
                // v7.15: superseded — the dialog below now arms
                // a foreign row too (third item), because the
                // drop handlers, the held-row bind and the
                // rotation restore all resolve lines by URL now
                // instead of raw contains, and drops move the
                // original v2 line string, so nothing is lost.
                // v7.14: that same long-press now offers the
                // foreign row's missing single-remove as well —
                // a foreign row could only be played, or cleared
                // with the whole queue (header long-press), never
                // removed singly anywhere: the chooser's
                // "တန်းစီမှ ထုတ်" cannot reach it (no row for
                // another sayadaw's talk there) and the drawer
                // has no per-row menu since the 11:39 ⋮ removal.
                // No new view, no row ⋮: the existing gesture
                // opens an AlertDialog (the chooser ⋮ menu's own
                // shape, title = stored title) with the old share
                // first — muscle memory preserved — and
                // "တန်းစီမှ ထုတ်" second, the chooser's exact
                // per-row remove verb and toast. Removal drops
                // the line by URL (queueLineIndexForUrl resolves
                // the v2 spelling, like every other queue site),
                // saves, and empties the queue-only view exactly
                // like the header clear does when nothing is
                // left; a line gone between dialog-open and tap
                // toasts the stale-queue line instead of dropping
                // the wrong row. Share text is the v7.12 text
                // verbatim, suppressBgPause still set only on
                // the share choice (remove never leaves the app),
                // and an armed move survives either choice
                // untouched — the held URL is an in-list URL of
                // this sayadaw, so it can never be this foreign
                // line. The in-list queue-only long-press below
                // is untouched: it still arms the move.
                if (drawerQueueOnly && !drawerSearching()) {
                    QueueEntry fe = drawerForeignEntry(pos);
                    if (fe != null) {
                        Fx.tap(RoomActivity.this);
                        final QueueEntry ffe = fe;
                        // v7.15: the dialog gains the foreign row's
                        // missing move — the third item arms the
                        // pick-up/drop move (in-list arm pattern,
                        // v6.85) or, on the already-held row, drops
                        // it at the end (v6.86 pattern). Offered
                        // only when 2+ rows show, like the in-list
                        // arm gate. No new view, no row ⋮ — the
                        // v7.14 dialog is the only surface, and an
                        // armed move from another row is replaced,
                        // like an in-list re-arm.
                        java.util.ArrayList<String> fOptList = new java.util.ArrayList<String>();
                        // v13.15: key list parallel to fOptList — dialogs dispatch on keys,
                        // not on re-evaluated labels (language-proof).
                        java.util.ArrayList<String> fKeys = new java.util.ArrayList<String>();
                        fOptList.add(Lang.t(RoomActivity.this, "menu_share"));
                        fKeys.add("menu_share");
                        // v7.89: same favourite verb as the full-list
                        // dialog above — a foreign row could only be
                        // shared, removed or moved, never favourited,
                        // even though its ★ mark reads favTalks.
                        // Fresh prefs read, same as v7.88, so the
                        // label never goes stale.
                        boolean fFav89 = false;
                        try {
                            java.util.Set<String> ft89f = getSharedPreferences("dhamma", MODE_PRIVATE).getStringSet("favTalks", null);
                            fFav89 = ft89f != null && ffe.url != null && ft89f.contains(ffe.url);
                        } catch (Exception ignored) {}
                        fOptList.add(fFav89 ? Lang.t(RoomActivity.this, "menu_fav_remove") : Lang.t(RoomActivity.this, "menu_fav_add"));
                        fKeys.add(fFav89 ? "menu_fav_remove" : "menu_fav_add");
                        fOptList.add(Lang.t(RoomActivity.this, "menu_queue_remove"));
                        fKeys.add("menu_queue_remove");
                        final boolean fHeldRow = ffe.url.equals(moveHeldUrl)
                                && queueLineIndexForUrl(ffe.url) >= 0;
                        // v11.34: never offer a move for the sounding foreign row — foreign form of the v11.22 in-list offer gate; bound this is isCurrentTalk, unbound the v11.32 foreign mirror.
                        if (drawerDisplayCount() >= 2 && !isSoundingForeignTalk(ffe.url, ffe.sayadaw)) {
                            fOptList.add(fHeldRow ? Lang.t(RoomActivity.this, "menu_move_end") : Lang.t(RoomActivity.this, "menu_move"));
                            fKeys.add(fHeldRow ? "menu_move_end" : "menu_move");
                        }
                        // v7.16: play-next for the foreign row — the
                        // queue-only view has no row for the playing
                        // talk, so no arm/drop target can ever mean
                        // "right after the playing talk": the arm
                        // inserts before a tapped row and the playing
                        // row is not tappable there, and move-to-end
                        // is the opposite end. "Next" here follows
                        // the app's own rule (firstWaitingQueueEntry:
                        // first line that is not the sounding talk),
                        // so the offer appears only while something
                        // is playing, this row is queued, and it is
                        // not already that next entry — never a
                        // no-op, never on the playing row (a foreign
                        // row never is the playing one).
                        String curUrl16 = currentTalkUrl();
                        int fqi16 = queueLineIndexForUrl(ffe.url);
                        QueueEntry fw16 = firstWaitingQueueEntry();
                        // v11.37: never offer play-next for the SOUNDING foreign row — the
                        // offer gate above the move gate (v11.34) never got the sounding
                        // exclusion: curUrl16 is this Room's INDEXED talk, never the
                        // foreign URL, and firstWaitingQueueEntry (v11.33) SKIPS the
                        // sounding entry, so fw16 != ffe held for the sounding row too
                        // whenever another entry waited — the menu then promised
                        // "set as next" for the talk already sounding, and the tap-time
                        // guard (v11.32) refused it with a queue_changed toast. A dead
                        // offer only, never a wrong move, but the menu must not promise
                        // what the tap refuses. The gate reads the same foreign mirror
                        // as the tap-time guard, so offer and tap can never disagree
                        // (bound it IS isCurrentTalk; unbound the owner's live room
                        // session; the v11.32 library-session limit applies to both
                        // alike). The v7.16 parenthetical above ("a foreign row never
                        // is the playing one") died with the v11.32 mirror and the
                        // v11.36 sounding-row mark — a foreign row CAN be the playing
                        // row now, which is exactly why this gate exists.
                        if (curUrl16 != null && fqi16 >= 0 && fw16 != null && !ffe.url.equals(fw16.url)
                                && !isSoundingForeignTalk(ffe.url, ffe.sayadaw)) {
                            fOptList.add(Lang.t(RoomActivity.this, "menu_play_next"));
                            fKeys.add("menu_play_next");
                        }
                        final String[] fOpts = fOptList.toArray(new String[0]);
                        final String[] fKeyArr = fKeys.toArray(new String[0]);
                        new android.app.AlertDialog.Builder(RoomActivity.this)
                                .setTitle(ffe.title.length() > 0 ? ffe.title : ffe.url)
                                .setItems(fOpts, new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int which) {
                                        if ("menu_share".equals(fKeyArr[which])) {
                                            suppressBgPause = true;
                                            Intent send = new Intent(Intent.ACTION_SEND);
                                            send.setType("text/plain");
                                            send.putExtra(Intent.EXTRA_TEXT, ffe.sayadaw + " — "
                                                    + (ffe.title.length() > 0 ? ffe.title : ffe.url)
                                                    + "\n" + ffe.url + Lang.t(RoomActivity.this, "share_source"));
                                            startActivity(Intent.createChooser(send, Lang.t(RoomActivity.this, "share_talk")));
                                        } else if ("menu_fav_add".equals(fKeyArr[which]) || "menu_fav_remove".equals(fKeyArr[which])) {
                                            // v7.89: same as the full-list dialog —
                                            // refreshMarkSets reloads favSet so the
                                            // foreign row's ★ mark updates at once.
                                            // An armed move survives untouched.
                                            boolean added89f = Fx.toggleFavTalk(RoomActivity.this, ffe.url);
                                            // v10.02: paused-card star sync — the card reads favTalks at rebuild time and rebuilds run only every 15s while playing (never while paused), so a paused card kept the old star until the next tap; rebuild at once when the flipped talk is the sounding one (same class as the v10.00 language / v10.01 repeat-shuffle syncs). refreshNotification is a no-op with no track.
                                            refreshCardIfFlipped1025(ffe.url);
                                            refreshMarkSets();
                                            if (adapter != null) adapter.notifyDataSetChanged();
                                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, added89f ? "fav_added" : "fav_removed"), Toast.LENGTH_SHORT).show();
                                        } else if ("menu_queue_remove".equals(fKeyArr[which])) {
                                            // v7.89: was `which == 1` — the new
                                            // favourite item above shifted remove
                                            // to index 2, so the branch now
                                            // matches by label like every other
                                            // branch in this dialog.
                                            int fqi = queueLineIndexForUrl(ffe.url);
                                            if (fqi < 0) {
                                                Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                                return;
                                            }
                                            queueUrls.remove(fqi);
                                            if (ffe.url.equals(moveHeldUrl)) moveHeldUrl = null; // v7.86: same held-row rule as the full-list dialog
                                            exitQueueOnlyIfNothingWaiting(); // v11.48: leave the view when nothing waits, not only when the raw queue empties
                                            saveQueue(null, true);
                                            if (adapter != null) adapter.notifyDataSetChanged();
                                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "removed_from_queue"), Toast.LENGTH_SHORT).show();
                                        } else if ("menu_move".equals(fKeyArr[which])) {
                                            // v7.15: arm the foreign row
                                            // for a pick-up/drop move —
                                            // the drop handlers and
                                            // the rotation restore
                                            // are line-aware now, so
                                            // the bare-URL arm reads
                                            // a v2 line correctly.
                                            // An arm from another row
                                            // is replaced, like an
                                            // in-list re-arm; the
                                            // share/remove choices
                                            // above leave an existing
                                            // arm untouched, as in
                                            // v7.14.
                                            // v11.34: tap-time sounding re-check, foreign form of the v11.22 in-list arm guard — the dialog can sit open across a track change, so a row offered while waiting can BE sounding by tap time; never arm the sounding talk.
                                            if (isSoundingForeignTalk(ffe.url, ffe.sayadaw)) {
                                                Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                                return;
                                            }
                                            moveHeldUrl = ffe.url;
                                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "talks_move_prefix") + (ffe.title.length() > 0 ? ffe.title : ffe.url) + Lang.t(RoomActivity.this, "room_move_suffix"), Toast.LENGTH_SHORT).show();
                                            if (adapter != null) adapter.notifyDataSetChanged();
                                        } else if ("menu_move_end".equals(fKeyArr[which])) {
                                            // v7.15: move-to-end for the
                                            // already-held foreign row
                                            // (v6.86 pattern) — the
                                            // original v2 line moves,
                                            // so its sayadaw and
                                            // title survive.
                                            // v11.34: tap-time sounding re-check, foreign form of the v11.22 in-list move-to-end guard — a held row that became sounding while the dialog sat open must not move to the end; the arm dies with it, like a remove of the held row (v7.86).
                                            if (isSoundingForeignTalk(ffe.url, ffe.sayadaw)) {
                                                moveHeldUrl = null;
                                                Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                                if (adapter != null) adapter.notifyDataSetChanged();
                                                return;
                                            }
                                            int fqi = queueLineIndexForUrl(ffe.url);
                                            if (fqi < 0) {
                                                Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                                return;
                                            }
                                            String fLine = queueUrls.get(fqi);
                                            queueUrls.remove(fqi);
                                            queueUrls.add(fLine);
                                            moveHeldUrl = null;
                                            saveQueue(null, true);
                                            if (adapter != null) adapter.notifyDataSetChanged();
                                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "moved_to_end"), Toast.LENGTH_SHORT).show();
                                        } else {
                                            // v7.16: play-next — move the
                                            // original v2 line to just
                                            // before the first waiting
                                            // entry (the app's own next
                                            // rule), computed AFTER the
                                            // removal so the shift is
                                            // already accounted for; a
                                            // row that became next since
                                            // the dialog opened is left
                                            // alone, and an armed move
                                            // is cleared (its intent is
                                            // now spent, like move-to-end).
                                            // v11.12: tap-time sounding re-check, same window
                                            // as the in-list dialogs — a foreign row shares its
                                            // URL with a talk that can start sounding while this
                                            // dialog sits open, and the v7.16 gate (firstWaiting
                                            // + indexed talk) also ran only at open time.
                                            // v11.32: foreign-aware mirror, not the bound-only
                                            // isCurrentTalk — unbound, the v11.29 mirror would
                                            // never claim a foreign session (lastName is the
                                            // foreign sayadaw, not this Room's), so the check
                                            // below reads the row's owner instead.
                                            if (isSoundingForeignTalk(ffe.url, ffe.sayadaw)) {
                                                Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                                return;
                                            }
                                            int fqi = queueLineIndexForUrl(ffe.url);
                                            if (fqi < 0) {
                                                Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                                return;
                                            }
                                            QueueEntry fw = firstWaitingQueueEntry();
                                            if (fw != null && fw.url.equals(ffe.url)) {
                                                Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "already_next"), Toast.LENGTH_SHORT).show();
                                                return;
                                            }
                                            String fLine = queueUrls.get(fqi);
                                            queueUrls.remove(fqi);
                                            int target = queueUrls.size();
                                            if (fw != null) {
                                                int ti = queueLineIndexForUrl(fw.url);
                                                if (ti >= 0) target = ti;
                                            }
                                            queueUrls.add(target, fLine);
                                            moveHeldUrl = null;
                                            saveQueue(null, true);
                                            if (adapter != null) adapter.notifyDataSetChanged();
                                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "set_as_next"), Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                })
                                .show();
                        return true;
                    }
                }
                // v4.55: queue-only display position -> talk index.
                final int talkIdx = drawerTalkIndex(pos);
                if (talkIdx < 0 || talkIdx >= talks.size()) return true;
                Talk t = talks.get(talkIdx);
                // v4.20: the drawer share was silent — every other
                // long-press in the app blips (search, drawer toggle,
                // Play restart), but this one opened the chooser with
                // no beat at all. Blip first, chooser second.
                Fx.tap(RoomActivity.this);
                // v7.91: queue-only in-list long-press becomes the full
                // dialog — the last per-talk favourite gap (v7.88
                // chooser, v7.89 Room drawer full-list + foreign
                // rows, v7.90 library row menu): a queued in-list
                // row in this view could only arm a move (v6.85)
                // or move to the end (v6.86), never be shared or
                // favourited, even though its ★ mark reads
                // favTalks — while the foreign row in this very
                // view already got share + favourite + remove +
                // arm/move-to-end + play-next (v7.14/v7.15/v7.16/
                // v7.89). This unifies the in-list row onto that
                // foreign shape, so NO verb is lost: share first
                // (muscle memory, same text + suppressBgPause as
                // the full-list dialog), favourite second (fresh
                // prefs read, toggle by URL, refreshMarkSets so
                // the ★ mark rebinds at once), remove third
                // (line-aware, clears a held arm per v7.86,
                // empties the view like the header clear when
                // nothing is left), arm / move-to-end fourth
                // (same >=2-row gate as the old arm, held row
                // moves to the end, tap-drop and drawer-close
                // cancel unchanged), play-next last (v7.16 shape,
                // target after removal, never offered on the
                // playing row or the already-next row). The
                // search view is untouched: drawerSearching()
                // still falls through to the share path below,
                // exactly as the old arm branch did. No new view,
                // no row ⋮, no drawer search — the 11:39 bans
                // stand, and the drop handler, ✋ mark, rotation
                // save/restore and drawer-close clear are
                // untouched.
                if (drawerQueueOnly && !drawerSearching()
                        && t.url != null && queueLineIndexForUrl(t.url) >= 0) {
                    final Talk qt = t;
                    java.util.ArrayList<String> qOptList = new java.util.ArrayList<String>();
                    // v13.15: key list parallel to qOptList — dialogs dispatch on keys,
                    // not on re-evaluated labels (language-proof).
                    java.util.ArrayList<String> qKeys = new java.util.ArrayList<String>();
                    qOptList.add(Lang.t(RoomActivity.this, "menu_share"));
                    qKeys.add("menu_share");
                    boolean qFav91 = false;
                    try {
                        java.util.Set<String> ft91 = getSharedPreferences("dhamma", MODE_PRIVATE).getStringSet("favTalks", null);
                        qFav91 = ft91 != null && qt.url != null && ft91.contains(qt.url);
                    } catch (Exception ignored) {}
                    qOptList.add(qFav91 ? Lang.t(RoomActivity.this, "menu_fav_remove") : Lang.t(RoomActivity.this, "menu_fav_add"));
                    qKeys.add(qFav91 ? "menu_fav_remove" : "menu_fav_add");
                    qOptList.add(Lang.t(RoomActivity.this, "menu_queue_remove"));
                    qKeys.add("menu_queue_remove");
                    // v11.71: Download for the sounding row in this
                    // view too -- the service-restart leftover still
                    // saved in the queue has a row here, and it is
                    // the same sounding talk the full-list dialog
                    // below now covers. Offered only for the
                    // sounding row (every other row here is a
                    // waiting talk, downloadable from its chooser
                    // since v11.70), and only when neither
                    // downloaded nor already queued.
                    if (isSoundingTalk(qt.url) && !isDownloadedFile(qt.url) && !roomIsDlQueued(qt.url)) {
                        qOptList.add(Lang.t(RoomActivity.this, "dl_btn_download"));
                        qKeys.add("dl_btn_download");
                    }
                    final boolean qHeld = qt.url.equals(moveHeldUrl)
                            && queueLineIndexForUrl(qt.url) >= 0;
                    // v11.22: never offer a move for the sounding row — the
                    // play-next gate (v11.11) and the full-list dialog
                    // already exclude it, but this move offer did not,
                    // so a sounding talk still saved in the queue
                    // (service-restart leftover) could be picked up
                    // as if it were a waiting talk.
                    if (drawerDisplayCount() >= 2 && !isSoundingTalk(qt.url)) {
                        qOptList.add(qHeld ? Lang.t(RoomActivity.this, "menu_move_end") : Lang.t(RoomActivity.this, "menu_move"));
                        qKeys.add(qHeld ? "menu_move_end" : "menu_move");
                    }
                    String qCur91 = currentTalkUrl();
                    int qqi91 = queueLineIndexForUrl(qt.url);
                    QueueEntry qw91 = firstWaitingQueueEntry();
                    // v11.11: scan-proven sounding leg on the play-next gate -- the
                    // v7.91 dialog promises play-next is never offered on the
                    // playing row, but the gate read only the INDEXED talk, so
                    // in the v7.58 post-background-advance window (currentIndex
                    // still naming the finished talk until the poll tick) a
                    // long-press on the SOUNDING row passed the gate and offered
                    // play-next for the talk already playing. Block on either
                    // identity: the indexed leg keeps the old pre-bind behaviour,
                    // the scan leg (isCurrentTalk, same shape as peekNextIndex)
                    // closes the window; a scan miss keeps the old behaviour,
                    // and the normal path is a no-op (scan == index).
                    String qSnd11 = null;
                    if (talks != null) {
                        for (int si11 = 0; si11 < talks.size(); si11++)
                            if (isSoundingTalk(talks.get(si11).url)) { qSnd11 = talks.get(si11).url; break; }
                    }
                    if ((qCur91 != null || qSnd11 != null) && qqi91 >= 0 && qw91 != null
                            && !qt.url.equals(qw91.url)
                            && !qt.url.equals(qCur91) && !qt.url.equals(qSnd11)) {
                        qOptList.add(Lang.t(RoomActivity.this, "menu_play_next"));
                        qKeys.add("menu_play_next");
                    }
                    final String[] qOpts = qOptList.toArray(new String[0]);
                    final String[] qKeyArr = qKeys.toArray(new String[0]);
                    new android.app.AlertDialog.Builder(RoomActivity.this)
                            .setTitle(qt.title != null && qt.title.length() > 0 ? qt.title : qt.url)
                            .setItems(qOpts, new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int which) {
                                    if ("menu_share".equals(qKeyArr[which])) {
                                        suppressBgPause = true;
                                        Intent send = new Intent(Intent.ACTION_SEND);
                                        send.setType("text/plain");
                                        send.putExtra(Intent.EXTRA_TEXT, (sayadawName == null ? "" : sayadawName) + " — " + qt.title
                                                + "\n" + qt.url + Lang.t(RoomActivity.this, "share_source"));
                                        startActivity(Intent.createChooser(send, Lang.t(RoomActivity.this, "share_talk")));
                                    } else if ("menu_fav_add".equals(qKeyArr[which]) || "menu_fav_remove".equals(qKeyArr[which])) {
                                        boolean added91 = Fx.toggleFavTalk(RoomActivity.this, qt.url);
                                        // v10.02: same paused-card star sync as the foreign-row branch above.
                                        refreshCardIfFlipped1025(qt.url);
                                        refreshMarkSets();
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, added91 ? "fav_added" : "fav_removed"), Toast.LENGTH_SHORT).show();
                                    } else if ("menu_queue_remove".equals(qKeyArr[which])) {
                                        int qi = queueLineIndexForUrl(qt.url);
                                        if (qi < 0) {
                                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        queueUrls.remove(qi);
                                        if (qt.url.equals(moveHeldUrl)) moveHeldUrl = null;
                                        exitQueueOnlyIfNothingWaiting(); // v11.48: leave the view when nothing waits, not only when the raw queue empties
                                        saveQueue(null, true);
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "removed_from_queue"), Toast.LENGTH_SHORT).show();
                                    } else if ("menu_move".equals(qKeyArr[which])) {
                                        // v11.22: tap-time sounding re-check (v11.12
                                        // class) — the dialog can sit open across
                                        // a track change, so a row offered while
                                        // waiting can BE sounding by tap time.
                                        // Never arm the sounding talk.
                                        if (isSoundingTalk(qt.url)) {
                                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        moveHeldUrl = qt.url;
                                        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "talks_move_prefix") + qt.title + Lang.t(RoomActivity.this, "room_move_suffix"), Toast.LENGTH_SHORT).show();
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                    } else if ("menu_move_end".equals(qKeyArr[which])) {
                                        // v11.22: same tap-time re-check as the arm
                                        // above — a held row that became sounding
                                        // while the dialog sat open must not move
                                        // to the end; the arm dies with it, like a
                                        // remove of the held row (v7.86).
                                        if (isSoundingTalk(qt.url)) {
                                            moveHeldUrl = null;
                                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                            if (adapter != null) adapter.notifyDataSetChanged();
                                            return;
                                        }
                                        int qi = queueLineIndexForUrl(qt.url);
                                        if (qi < 0) {
                                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        String qLine = queueUrls.get(qi);
                                        queueUrls.remove(qi);
                                        queueUrls.add(qLine);
                                        moveHeldUrl = null;
                                        saveQueue(null, true);
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "moved_to_end"), Toast.LENGTH_SHORT).show();
                                    } else if ("dl_btn_download".equals(qKeyArr[which])) {
                                        // v11.71: the shared Room enqueue -- re-checks
                                        // inside, so a file landed or a queue placed
                                        // while the dialog sat open is caught there.
                                        enqueueRoomDownload(qt);
                                    } else {
                                        int qi = queueLineIndexForUrl(qt.url);
                                        if (qi < 0) {
                                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        // v11.12: tap-time sounding re-check — v11.11 closed the
                                        // gate (a row that IS sounding never offers play-next),
                                        // but the dialog can stay open across a track change, so
                                        // a row offered while waiting can BE sounding by tap time.
                                        // The gate's scan leg cannot help here (it ran at open);
                                        // re-check the live sounding talk now, before any move,
                                        // and leave the queue untouched on a hit — queuing the
                                        // sounding talk would replay it as "next" once it stops
                                        // being sounding, exactly the v11.11 defect one tap later.
                                        if (isSoundingTalk(qt.url)) {
                                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        QueueEntry fw = firstWaitingQueueEntry();
                                        if (fw != null && fw.url.equals(qt.url)) {
                                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "already_next"), Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        String qLine = queueUrls.get(qi);
                                        queueUrls.remove(qi);
                                        int target = queueUrls.size();
                                        if (fw != null) {
                                            int ti = queueLineIndexForUrl(fw.url);
                                            if (ti >= 0) target = ti;
                                        }
                                        queueUrls.add(target, qLine);
                                        moveHeldUrl = null;
                                        saveQueue(null, true);
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "set_as_next"), Toast.LENGTH_SHORT).show();
                                    }
                                }
                            })
                            .show();
                    return true;
                }
                // v7.81: full-list long-press queue dialog — the
                // 2026-11-01 ⋮ removals deleted the queue BUILDERS
                // (chooser ⋮ v4.47, drawer row menu v4.84) while
                // leaving every queue consumer intact (◈ marks,
                // queue-only view, reorder, play-next, clear,
                // download-queued, strip ◈ count), so a talk queue
                // could only drain, never be built again: every
                // writer left in RoomActivity is a move, a remove,
                // a clear or a consume — queueUrls.add appears
                // nowhere else, Talks long-press is share-only,
                // and no row carries a menu button any more. A
                // fresh sayadaw therefore always reads "တန်းစီ
                // ထားတာ မရှိသေးပါ" and the v7.78 entry point
                // manages queues that cannot exist. This restores
                // building WITHOUT resurrecting any removed item:
                // no ⋮ menu, no new view, no strip button — the
                // existing full-list long-press gesture opens an
                // AlertDialog in the v7.14 foreign-row shape, share
                // first so muscle memory is preserved. The playing
                // row keeps the direct share below (adding the
                // sounding talk would only recreate the v5.82
                // sounding-in-queue state). Add appends the bare
                // URL — in-list lines are bare by convention, so
                // the chooser's marks, download-queue and the
                // foreign-keeping save all read it; play-next
                // inserts before the first waiting entry (v7.16
                // shape, target computed after removal for the
                // already-queued case, append when nothing waits).
                // An armed move is left untouched: the dialog
                // never arms, and its drop targets still work.
                // v11.71: full-list SOUNDING row long-press dialog --
                // the placement decision the v11.70 run left open.
                // The sounding row was the one row in the drawer
                // with no dialog at all: its long-press shared
                // directly, so on Wi-Fi the talk actually playing
                // could not be downloaded anywhere in the Room
                // (secondary row banned, strip button banned, gate
                // only on mobile data). It now gets the same
                // dialog shape its neighbours already use: share
                // FIRST (same text + suppressBgPause, one tap on
                // the first item -- the gesture's outcome is
                // preserved, the v7.14/v7.81/v7.91 precedent),
                // favourite second (fresh-prefs toggle, v7.89
                // shape -- the drawer could previously only
                // favourite the sounding talk via the altar hold),
                // Download third (existing dl_btn_download key,
                // only when neither downloaded nor already
                // queued). Queue verbs stay OUT -- a sounding
                // talk must never be queued (v5.82/v11.11). No
                // new view, no new button, no strip button, no
                // secondary row. The search view is untouched:
                // drawerSearching() still falls through to the
                // direct share below, exactly as the v7.91 branch
                // leaves it.
                if (!drawerQueueOnly && !drawerSearching() && t.url != null && isSoundingTalk(t.url)) {
                    final Talk st = t;
                    java.util.ArrayList<String> sOpts = new java.util.ArrayList<String>();
                    // v13.15: key list parallel to sOpts — dialogs dispatch on keys,
                    // not on re-evaluated labels (language-proof).
                    java.util.ArrayList<String> sKeys = new java.util.ArrayList<String>();
                    sOpts.add(Lang.t(RoomActivity.this, "menu_share"));
                    sKeys.add("menu_share");
                    boolean sFav71 = false;
                    try {
                        java.util.Set<String> ft71 = getSharedPreferences("dhamma", MODE_PRIVATE).getStringSet("favTalks", null);
                        sFav71 = ft71 != null && st.url != null && ft71.contains(st.url);
                    } catch (Exception ignored) {}
                    sOpts.add(sFav71 ? Lang.t(RoomActivity.this, "menu_fav_remove") : Lang.t(RoomActivity.this, "menu_fav_add"));
                    sKeys.add(sFav71 ? "menu_fav_remove" : "menu_fav_add");
                    if (!isDownloadedFile(st.url) && !roomIsDlQueued(st.url)) {
                        sOpts.add(Lang.t(RoomActivity.this, "dl_btn_download"));
                        sKeys.add("dl_btn_download");
                    }
                    final String[] sOptArr = sOpts.toArray(new String[0]);
                    final String[] sKeyArr = sKeys.toArray(new String[0]);
                    new android.app.AlertDialog.Builder(RoomActivity.this)
                            .setTitle(st.title != null && st.title.length() > 0 ? st.title : st.url)
                            .setItems(sOptArr, new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int which) {
                                    if ("menu_share".equals(sKeyArr[which])) {
                                        suppressBgPause = true;
                                        Intent send = new Intent(Intent.ACTION_SEND);
                                        send.setType("text/plain");
                                        send.putExtra(Intent.EXTRA_TEXT, (sayadawName == null ? "" : sayadawName) + " — " + st.title
                                                + "\n" + st.url + Lang.t(RoomActivity.this, "share_source"));
                                        startActivity(Intent.createChooser(send, Lang.t(RoomActivity.this, "share_talk")));
                                    } else if ("menu_fav_add".equals(sKeyArr[which]) || "menu_fav_remove".equals(sKeyArr[which])) {
                                        // v7.89 shape: refreshMarkSets reloads favSet
                                        // so the star mark rebinds at once.
                                        boolean added71 = Fx.toggleFavTalk(RoomActivity.this, st.url);
                                        refreshCardIfFlipped1025(st.url);
                                        refreshMarkSets();
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, added71 ? "fav_added" : "fav_removed"), Toast.LENGTH_SHORT).show();
                                    } else if ("dl_btn_download".equals(sKeyArr[which])) {
                                        enqueueRoomDownload(st);
                                    }
                                }
                            })
                            .show();
                    return true;
                }
                // v11.75: !drawerSearching() — the sounding-row
                // dialog above carries it, and the comment above
                // it says the search view falls through to the
                // direct share; this dialog lacked it, so a
                // long-press on a non-sounding row in a drawer
                // search opened the full queue dialog instead.
                if (!drawerQueueOnly && !drawerSearching() && t.url != null && !isSoundingTalk(t.url)) {
                    final Talk dt = t;
                    java.util.ArrayList<String> dOpts = new java.util.ArrayList<String>();
                    // v13.15: key list parallel to dOpts — dialogs dispatch on keys,
                    // not on re-evaluated labels (language-proof).
                    java.util.ArrayList<String> dKeys = new java.util.ArrayList<String>();
                    dOpts.add(Lang.t(RoomActivity.this, "menu_share"));
                    dKeys.add("menu_share");
                    // v7.89: per-talk favourite from the Room drawer — the
                    // v7.88 chooser verb's Room-side mirror. Before this
                    // the drawer could only toggle a favourite for the
                    // SOUNDING talk (altar long-press): a listener
                    // browsing the Room list could never favourite a
                    // talk it had not played yet, even though the ★
                    // marks in these very rows read favTalks. Same
                    // shape as v7.88 (share first, fresh prefs read
                    // so a strip-star toggle a second earlier never
                    // shows a stale label, toggle by URL so the toast
                    // reports the true new state); the sounding talk
                    // keeps its direct share below, and an armed move
                    // is untouched — this dialog never arms.
                    boolean dFav89 = false;
                    try {
                        java.util.Set<String> ft89 = getSharedPreferences("dhamma", MODE_PRIVATE).getStringSet("favTalks", null);
                        dFav89 = ft89 != null && dt.url != null && ft89.contains(dt.url);
                    } catch (Exception ignored) {}
                    dOpts.add(dFav89 ? Lang.t(RoomActivity.this, "menu_fav_remove") : Lang.t(RoomActivity.this, "menu_fav_add"));
                    dKeys.add(dFav89 ? "menu_fav_remove" : "menu_fav_add");
                    final boolean dQueued = queueLineIndexForUrl(dt.url) >= 0;
                    if (dQueued) { dOpts.add(Lang.t(RoomActivity.this, "menu_queue_remove")); dKeys.add("menu_queue_remove"); }
                    else { dOpts.add(Lang.t(RoomActivity.this, "menu_queue_add")); dKeys.add("menu_queue_add"); }
                    // v13.04: "queue all from here" — the Downloaded
                    // library's bulk verb (menu_queue_from_here),
                    // missing from this dialog since the v7.32 ⋮
                    // removal (the v7.81 rebuild restored add /
                    // remove / play-next but not this one): queues
                    // this talk and every shown talk below it, in
                    // drawer display order, so a listener can queue
                    // a whole run without leaving the Room. Offered
                    // on any non-sounding row (the dialog gate
                    // already excludes the sounding talk, and the
                    // sounding-row dialog has no queue verbs by
                    // design); already-queued rows below are skipped
                    // by the handler, like the library verb.
                    dOpts.add(Lang.t(RoomActivity.this, "menu_queue_from_here"));
                    dKeys.add("menu_queue_from_here");
                    final String dCur = currentTalkUrl();
                    final QueueEntry dFw = firstWaitingQueueEntry();
                    final boolean dPlaying = dCur != null && dCur.length() > 0;
                    if (dPlaying && (!dQueued || (dFw != null && !dt.url.equals(dFw.url)))) {
                        dOpts.add(dQueued ? Lang.t(RoomActivity.this, "menu_play_next") : Lang.t(RoomActivity.this, "menu_add_next"));
                        dKeys.add(dQueued ? "menu_play_next" : "menu_add_next");
                    }
                    final String[] dOptArr = dOpts.toArray(new String[0]);
                    final String[] dKeyArr = dKeys.toArray(new String[0]);
                    new android.app.AlertDialog.Builder(RoomActivity.this)
                            .setTitle(dt.title != null && dt.title.length() > 0 ? dt.title : dt.url)
                            .setItems(dOptArr, new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int which) {
                                    if ("menu_share".equals(dKeyArr[which])) {
                                        suppressBgPause = true;
                                        Intent send = new Intent(Intent.ACTION_SEND);
                                        send.setType("text/plain");
                                        send.putExtra(Intent.EXTRA_TEXT, (sayadawName == null ? "" : sayadawName) + " — " + dt.title
                                                + "\n" + dt.url + Lang.t(RoomActivity.this, "share_source"));
                                        startActivity(Intent.createChooser(send, Lang.t(RoomActivity.this, "share_talk")));
                                    } else if ("menu_fav_add".equals(dKeyArr[which]) || "menu_fav_remove".equals(dKeyArr[which])) {
                                        // v7.89: refreshMarkSets reloads favSet from
                                        // prefs, so the ★ marks in these rows
                                        // update at once — the drawer bind only
                                        // re-reads when the set is null.
                                        boolean added89 = Fx.toggleFavTalk(RoomActivity.this, dt.url);
                                        // v10.02: same paused-card star sync as the foreign-row branch above.
                                        refreshCardIfFlipped1025(dt.url);
                                        refreshMarkSets();
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, added89 ? "fav_added" : "fav_removed"), Toast.LENGTH_SHORT).show();
                                    } else if ("menu_queue_remove".equals(dKeyArr[which])) {
                                        int qi = queueLineIndexForUrl(dt.url);
                                        if (qi < 0) {
                                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        queueUrls.remove(qi);
                                        if (dt.url.equals(moveHeldUrl)) moveHeldUrl = null; // v7.86: removing the held row drops the arm, not a stale-arm toast on the next drop
                                        if (queueUrls.isEmpty()) drawerQueueOnly = false;
                                        saveQueue(null, true);
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "removed_from_queue"), Toast.LENGTH_SHORT).show();
                                    } else if ("menu_queue_add".equals(dKeyArr[which])) {
                                        // v11.14: tap-time sounding re-check — the mirror of the v11.12
                                        // play-next leg in the else branch below, which this append
                                        // verb never got: the offer gate ran at dialog-build time
                                        // (!isCurrentTalk), but the dialog can sit open across a
                                        // track change (strip Next, auto-advance), so a row offered
                                        // while waiting can be sounding by tap time. Appending it
                                        // then leaves the sounding talk in the queue to replay
                                        // after the next talk. isCurrentTalk covers both the stream
                                        // URL and the downloaded-file form, like the play-next leg.
                                        if (isSoundingTalk(dt.url)) {
                                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        if (queueLineIndexForUrl(dt.url) >= 0) {
                                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "already_in_queue"), Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        queueUrls.add(dt.url);
                                        saveQueue(java.util.Collections.singleton(dt.url), true);
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "added_to_queue"), Toast.LENGTH_SHORT).show();
                                    } else if ("menu_queue_from_here".equals(dKeyArr[which])) {
                                        // v13.04: queue this talk and every shown talk
                                        // below it, in drawer display order
                                        // (drawerTalkIndex resolves the full list and
                                        // the filter view alike, like the library
                                        // verb's display-position loop; the search
                                        // view never reaches this dialog). The
                                        // library verb's rules: skip the sounding
                                        // talk (re-checked at tap time, the v11.12
                                        // window) and already-queued talks (they
                                        // stay where they are); the keep set carries
                                        // every new URL through the funnel's stale
                                        // drop, or the funnel would drop them as
                                        // not-yet-saved.
                                        int added04 = 0;
                                        java.util.Set<String> keep04 = new java.util.HashSet<String>();
                                        int dispCount04 = drawerDisplayCount();
                                        // v13.15: anchor on the long-pressed talk, not the
                                        // captured display position — the drawer filter
                                        // can rebuild while the dialog sits open (favourite
                                        // toggle, track end, download landing), shifting
                                        // display positions under the captured pos, the
                                        // Talks T1 defect (v13.13) in drawer form.
                                        // drawerPosOfTalk resolves dt's CURRENT display
                                        // position across search/filter/queue-only views.
                                        int start04 = drawerPosOfTalk(talks.indexOf(dt));
                                        if (start04 < 0) {
                                            // Anchor filtered out of view: queue just
                                            // the anchor itself, never a stale range.
                                            if (dt != null && dt.url != null && dt.url.length() > 0
                                                    && !isSoundingTalk(dt.url) && queueLineIndexForUrl(dt.url) < 0) {
                                                queueUrls.add(dt.url);
                                                keep04.add(dt.url);
                                                added04++;
                                            }
                                        }
                                        if (start04 >= 0) for (int dp04 = start04; dp04 < dispCount04; dp04++) {
                                            int ti04 = drawerTalkIndex(dp04);
                                            if (ti04 < 0 || ti04 >= talks.size()) continue;
                                            Talk t04 = talks.get(ti04);
                                            if (t04 == null || t04.url == null || t04.url.length() == 0) continue;
                                            if (isSoundingTalk(t04.url)) continue;
                                            if (queueLineIndexForUrl(t04.url) >= 0) continue;
                                            queueUrls.add(t04.url);
                                            keep04.add(t04.url);
                                            added04++;
                                        }
                                        saveQueue(keep04, true);
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        Toast.makeText(RoomActivity.this, added04 > 0
                                                ? Lang.t(RoomActivity.this, "queue_plain_prefix") + added04 + Lang.t(RoomActivity.this, "queue_added_suffix")
                                                : Lang.t(RoomActivity.this, "already_in_queue"), Toast.LENGTH_SHORT).show();
                                    } else {
                                        // v11.12: same tap-time sounding re-check as the
                                        // queue-only dialog above — this dialog's gate also
                                        // ran only at open time (!isCurrentTalk at build), so a
                                        // row that became sounding while the dialog sat open
                                        // would otherwise be moved/inserted as "next" here.
                                        // Check before any mutation (the already-queued
                                        // restore path below must not run for a sounding row).
                                        if (isSoundingTalk(dt.url)) {
                                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_changed"), Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        // play-next: already-queued moves before the
                                        // first waiting entry, unqueued inserts there.
                                        int qi = queueLineIndexForUrl(dt.url);
                                        String line;
                                        if (qi >= 0) { line = queueUrls.get(qi); queueUrls.remove(qi); }
                                        else line = dt.url;
                                        QueueEntry fw = firstWaitingQueueEntry();
                                        if (fw != null && fw.url.equals(dt.url)) {
                                            if (qi >= 0) queueUrls.add(qi, line);
                                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "already_next"), Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        int target = queueUrls.size();
                                        if (fw != null) {
                                            int ti = queueLineIndexForUrl(fw.url);
                                            if (ti >= 0) target = ti;
                                        }
                                        queueUrls.add(target, line);
                                        if (dt.url.equals(moveHeldUrl)) moveHeldUrl = null; // v7.86: a play-next move of the held row spends the arm, or the next drop would move it a second time (library v7.85 class)
                                        // v12.03: an UNQUEUED play-next inserts a brand-new
                                        // entry (qi < 0), so it needs the same singleton
                                        // keep the long-press ADD passes -- with keep=null
                                        // the v12.02 funnel dropped it (not in the saved
                                        // read yet) while the toast still said "set as
                                        // next". A move of an already-queued talk (qi >= 0)
                                        // adds nothing, so null is correct there.
                                        saveQueue(qi < 0 ? java.util.Collections.singleton(dt.url) : null, true);
                                        if (adapter != null) adapter.notifyDataSetChanged();
                                        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "set_as_next"), Toast.LENGTH_SHORT).show();
                                    }
                                }
                            })
                            .show();
                    return true;
                }
                // v1.74: opening the chooser must not count as
                // leaving (see suppressBgPause) — it pauses the
                // talk under background-off otherwise.
                suppressBgPause = true;
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, (sayadawName == null ? "" : sayadawName) + " — " + t.title
                        + "\n" + t.url + Lang.t(RoomActivity.this, "share_source"));
                startActivity(Intent.createChooser(send, Lang.t(RoomActivity.this, "share_talk")));
                return true;
            }
        });
        wireDrawerSearch(); // v4.98: title search in the Play List drawer
        android.view.View plToggleBtn = findViewById(R.id.roomPlayList);
        if (plToggleBtn != null) plToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(RoomActivity.this);
                Fx.morph(v);
                setPlayListPanel(!isPlayListPanelOpen());
            }
        });
        // USER ORDER 2026-10-31 ~19:39 +07: left stack wiring removed;
        // repeat/shuffle prefs still load so auto-advance keeps its mode.
        repeatMode = Prefs.repeatMode(RoomActivity.this);
        shuffleOn = Prefs.shuffleOn(RoomActivity.this);
// v4.19: long-press the drawer toggle shows the playing row
        // — tap still toggles the drawer, but a long-press never
        // closes it: closed, it opens (and the v3.87 open glide
        // already lands on the playing row); open and scrolled
        // away, it scrolls back to the playing row in place. This
        // is not the plTitle long-press (v4.15): the header text
        // scrolls off with a long queue, while the toggle stays
        // visible, so the playing talk stays one gesture away
        // from anywhere in the drawer. No playing talk toasts.
        if (plToggleBtn != null) plToggleBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Fx.tap(RoomActivity.this);
                // v7.66: scan-adopt before scrolling — the same
                // pre-poll-tick window as the v7.65 gestures: after
                // a v7.58 background advance, currentIndex still
                // names the FINISHED talk until the v7.59 return
                // poll tick, so this long-press landed on the
                // finished row instead of the sounding one — both
                // branches, since the closed branch scrolls through
                // setPlayListPanel's drawerScrollPos(currentIndex)
                // and the open branch calls setSelection on it
                // directly. The plTitle long-press got this adopt
                // in v7.65; the toggle (v4.19) was left on the
                // v7.61 display-only audit, but a scroll to the
                // wrong row is a wrong landing, not a display
                // nuance. Adopt the scan-proven sounding index
                // first (stream or offline-file form via
                // isCurrentTalk); a scan miss keeps the old
                // behaviour exactly, and the normal path is a no-op.
                if (currentIndex >= 0 && talks != null) {
                    int snd66 = -1;
                    for (int si66 = 0; si66 < talks.size(); si66++)
                        if (isCurrentTalk(talks.get(si66).url)) { snd66 = si66; break; }
                    // v8.19: adopt-push — a display/step adopt landing in the pre-poll-tick window after a hidden advance used to move the index without recording the talk left behind, so the v8.06/v8.08/v8.18 correctors never fired for it (their !isCurrentTalk guard saw the sounding talk already) and a later Previous skipped it, the v8.18 swipe failure on the fav/drawer/share/preview/button paths. Drain-or-push exactly like the correctors, only when the adopt actually moves a valid index; a scan miss or a no-op adopt keeps the old behaviour exactly.
if (snd66 >= 0 && snd66 != currentIndex && currentIndex >= 0 && currentIndex < talks.size()) {
    if (!drainHiddenTrail08(talks.get(currentIndex).url)) pushPrevHistory16(talks.get(currentIndex).url);
}
if (snd66 >= 0) currentIndex = snd66;
                }
                if (!isPlayListPanelOpen()) {
                    setPlayListPanel(true);
                } else if (roomList != null && currentIndex >= 0 && currentIndex < talks.size()) {
                    // v4.55: scroll-to-playing leaves the queue-only
                    // view first — the playing talk has no row there
                    // (consumed from the queue), so the full list is
                    // the only place it can be scrolled to. v4.96:
                    // a filter view leaves the same way, since the
                    // playing talk may not be shown in it either.
                    // v4.98: a search leaves the same way — the
                    // setSelection below is a full-list index, and
                    // under a search it would land on the wrong row.
                    if (drawerQueueOnly || drawerFilterMode != 0) { drawerQueueOnly = false; drawerFilterMode = 0; if (adapter != null) adapter.notifyDataSetChanged(); }
                    clearDrawerSearch();
                    roomList.setSelection(currentIndex);
                } else {
                    Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "no_talk_playing"), Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        // v12.93 (his order 2026-11-06 ~22:09 +07: the drawer header is
        // X-only now — a tap here used to play the next talk (v4.14,
        // plus the armed-move drop leg), and an accidental tap changed
        // the talk. The tap listener is removed, so tapping the header
        // does nothing. The long-press below (clear queue / scroll to
        // playing) stays: it needs a deliberate hold plus a dialog,
        // so it cannot fire by accident.
        android.view.View plTitle = findViewById(R.id.roomPlayListTitle);
        // v4.15: long-press the drawer title jumps the drawer back
        // to the playing row (the tap still plays next, v4.14) —
        // after scrolling away through a long list, the playing
        // talk is one gesture away instead of a hunt. No new view.
        if (plTitle != null) plTitle.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                // v7.65: scan-adopt before counting — the same
                // pre-poll-tick window: the clear-queue dialog
                // below counts waiting talks by excluding the
                // drawer's currentIndex talk, so a long-press
                // landing before the v7.59 return poll tick after
                // a v7.58 background advance read the finished
                // talk as playing and overstated the waiting
                // count by one (the scroll branch below lands on
                // the sounding talk as a consequence). A scan miss
                // keeps the old behaviour exactly.
                if (currentIndex >= 0 && talks != null) {
                    int snd65h = -1;
                    for (int si65h = 0; si65h < talks.size(); si65h++)
                        if (isCurrentTalk(talks.get(si65h).url)) { snd65h = si65h; break; }
                    // v8.19: adopt-push — a display/step adopt landing in the pre-poll-tick window after a hidden advance used to move the index without recording the talk left behind, so the v8.06/v8.08/v8.18 correctors never fired for it (their !isCurrentTalk guard saw the sounding talk already) and a later Previous skipped it, the v8.18 swipe failure on the fav/drawer/share/preview/button paths. Drain-or-push exactly like the correctors, only when the adopt actually moves a valid index; a scan miss or a no-op adopt keeps the old behaviour exactly.
if (snd65h >= 0 && snd65h != currentIndex && currentIndex >= 0 && currentIndex < talks.size()) {
    if (!drainHiddenTrail08(talks.get(currentIndex).url)) pushPrevHistory16(talks.get(currentIndex).url);
}
if (snd65h >= 0) currentIndex = snd65h;
                }
                // v4.32: with a hand queue, long-pressing the header
                // offers to clear it (the header count "· ◈ N" names
                // it); without one, the old scroll-to-playing stays.
                // Scroll-to-playing also lives on the drawer toggle
                // long-press (v4.19), so nothing is lost.
                if (!queueUrls.isEmpty()) {
                    Fx.tap(RoomActivity.this);
                    // v5.83: the clear-queue dialog counts waiting talks only —
                    // the header (v5.81), strips (v5.80), card (v5.43) and row
                    // marks (v5.82) already skip the talk now playing, but
                    // this dialog counted the raw queue, so a service-restart
                    // play still saved in the queue read as one waiting here
                    // and zero on the header beside it. Playing identity is
                    // the drawer's own currentIndex talk, falling back to the
                    // session lastUrl for this sayadaw (the v5.81 header rule)
                    // when nothing is indexed yet; a normal play (already
                    // popped) changes nothing. When the queue holds only the
                    // playing talk, the dialog says so instead of "0 ပုဒ်" —
                    // the clear action itself is unchanged (it empties the
                    // whole queue; the playing talk keeps playing).
                    // v10.92: scan-proven sounding talk, not the
                    // indexed one (background-advance window).
                    String playUrl83 = soundingUrlForQueue();
                    // v11.38: count DISPLAY lines, not the raw queue — a line whose talk left the
                    // site's list (stale, pruned only on the next peek) still sits in queueUrls, so
                    // the raw size overstated waiting vs the header (v11.04, display lines) and vs
                    // the queue-only view itself, which skips stale lines: the dialog could quote
                    // N waiting while the view it offers shows fewer, and could offer "view queue
                    // only" when every non-sounding line was stale. Same deduped display loop as
                    // the header, minus the sounding talk; the clear action itself is unchanged
                    // (it empties the whole queue, stale lines included; the playing talk keeps
                    // playing). Normal queues are byte-identical (no stale lines, no duplicates).
                    int waitQ83 = 0;
                    java.util.ArrayList<String> waitSeen38 = new java.util.ArrayList<String>();
                    for (String waitLine38 : drawerQueueDisplayLines()) {
                        String waitUrl38 = queueLineUrl(waitLine38);
                        if (waitUrl38.length() == 0 || waitSeen38.contains(waitUrl38)) continue;
                        waitSeen38.add(waitUrl38);
                        if (playUrl83 == null || !waitUrl38.equals(playUrl83)) waitQ83++;
                    }
                    // v11.81: the "only the playing talk is left" line is true only when
                    // something IS sounding — when the queue holds only stale (pruned-from-
                    // view) lines and nothing plays, it lied. Count the honest 0 instead.
                    String clearMsg83;
                    if (waitQ83 > 0) {
                        clearMsg83 = Lang.t(RoomActivity.this, "queue_clear_prefix") + waitQ83 + Lang.t(RoomActivity.this, "queue_clear_suffix");
                    } else if (playUrl83 != null && playUrl83.length() > 0) {
                        clearMsg83 = Lang.t(RoomActivity.this, "queue_clear_only_playing");
                    } else {
                        clearMsg83 = Lang.t(RoomActivity.this, "queue_clear_prefix") + 0 + Lang.t(RoomActivity.this, "queue_clear_suffix");
                    }
                    // v7.78: the queue-only drawer had no live entry
                    // point left — its only writer, openQueueDrawer,
                    // is reached solely through RoomActivity's
                    // ACTION_QUEUE intent, and nothing sends that
                    // intent: the v4.76 mini-player ◈ button it was
                    // built for was never wired (no such view in
                    // any strip layout, no sender in any activity)
                    // and the 2026-11-01 removals ban re-adding
                    // the mini-player strip buttons, so restoring
                    // the button is NOT an option. The queue
                    // management screen (reorder, play-next, remove)
                    // was therefore unreachable in normal use — the
                    // rotation restore of drawerQueueOnly is
                    // circular, it can only restore a state this
                    // dead intent once set. This dialog is the one
                    // existing queue surface inside the open drawer,
                    // so it gains a third action: "view queue only"
                    // enters the queue-only view through the same
                    // openQueueDrawer gate (waiting-only, so it is
                    // offered only when waitQ83 > 0, the same count
                    // the message already quotes). No new view, no
                    // strip button, no row menu, no drawer search —
                    // every 2026-11-01 removal ban stands.
                    android.app.AlertDialog.Builder clearDlg78 =
                            new android.app.AlertDialog.Builder(RoomActivity.this)
                            .setTitle(Lang.t(RoomActivity.this, "queue_clear_title"))
                            .setMessage(clearMsg83)
                            .setPositiveButton(Lang.t(RoomActivity.this, "queue_clear_yes"), new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int w) {
                                    queueUrls.clear();
                                    moveHeldUrl = null; // v7.86: an emptied queue leaves no held row, like the library clear-all since v7.85
                                    drawerQueueOnly = false; // v4.55: an emptied queue leaves queue-only view
                                    saveQueue(null, true);
                                    if (adapter != null) adapter.notifyDataSetChanged();
                                    Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_cleared"), Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNegativeButton(Lang.t(RoomActivity.this, "btn_no_clear"), null);
                    if (waitQ83 > 0)
                        clearDlg78.setNeutralButton(Lang.t(RoomActivity.this, "queue_clear_only"), new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                openQueueDrawer();
                            }
                        });
                    clearDlg78.show();
                    return true;
                }
                if (roomList != null && currentIndex >= 0 && currentIndex < talks.size()) {
                    if (drawerQueueOnly || drawerFilterMode != 0) { drawerQueueOnly = false; drawerFilterMode = 0; if (adapter != null) adapter.notifyDataSetChanged(); }
                    clearDrawerSearch(); // v4.98: same full-list promise as the toggle long-press above
                    roomList.setSelection(currentIndex);
                    Fx.tap(RoomActivity.this);
                } else {
                    Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "no_talk_playing"), Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        // v4.15: the now-playing title is itself a control surface
        // (no new button in the clean Room): tap opens the Play
        // List drawer — the queue the title names — and long-press
        // shares the playing talk with the drawer's share text, so
        // the Room regains a share path without a new button.
        // The chooser gets the same suppressBgPause guard as the
        // drawer share (v1.74), so a slow share pick never reads
        // as leaving under background-playback-off.
        if (nowPlaying != null) {
            nowPlaying.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (talks.isEmpty()) {
                        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "no_talk_yet"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Fx.tap(RoomActivity.this);
                    Fx.morph(v);
                    setPlayListPanel(true);
                }
            });
            nowPlaying.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (currentIndex < 0 || currentIndex >= talks.size()) {
                        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "no_talk_yet"), Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    // v7.65: scan-adopt before sharing — the same
                    // pre-poll-tick window as togglePlayingFav
                    // above: a long-press landing before the v7.59
                    // return poll tick after a v7.58 background
                    // advance would share the FINISHED talk's
                    // title + original link out of the app, while
                    // the sounding talk is the one playing. A scan
                    // miss keeps the old behaviour exactly.
                    if (currentIndex >= 0) {
                        int snd65s = -1;
                        for (int si65s = 0; si65s < talks.size(); si65s++)
                            if (isCurrentTalk(talks.get(si65s).url)) { snd65s = si65s; break; }
                        // v8.19: adopt-push — a display/step adopt landing in the pre-poll-tick window after a hidden advance used to move the index without recording the talk left behind, so the v8.06/v8.08/v8.18 correctors never fired for it (their !isCurrentTalk guard saw the sounding talk already) and a later Previous skipped it, the v8.18 swipe failure on the fav/drawer/share/preview/button paths. Drain-or-push exactly like the correctors, only when the adopt actually moves a valid index; a scan miss or a no-op adopt keeps the old behaviour exactly.
if (snd65s >= 0 && snd65s != currentIndex && currentIndex >= 0 && currentIndex < talks.size()) {
    if (!drainHiddenTrail08(talks.get(currentIndex).url)) pushPrevHistory16(talks.get(currentIndex).url);
}
if (snd65s >= 0) currentIndex = snd65s;
                    }
                                        // v4.21: this share opened the chooser silently —
                    // the drawer-row share (v4.20) and the sayadaw-header
                    // share (v4.14) both blip first, so a now-playing
                    // share read as a dead press. Blip first, chooser
                    // second; the no-talk refusal above stays silent.
                    Fx.tap(RoomActivity.this);
                    Talk t = talks.get(currentIndex);
                    suppressBgPause = true;
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, (sayadawName == null ? "" : sayadawName) + " — " + t.title
                            + "\n" + t.url + Lang.t(RoomActivity.this, "share_source"));
                    startActivity(Intent.createChooser(send, Lang.t(RoomActivity.this, "share_talk")));
                    return true;
                }
            });
        }
        if (plToggleBtn != null) Fx.press(plToggleBtn); // v2.48: press feedback on Play List
        // v3.72: drawer close button at the drawer's top-right corner
        // (user order) — closes only, unlike the toggle above.
        android.view.View plClose = findViewById(R.id.roomPlayListClose);
        if (plClose != null) {
            plClose.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Fx.tap(RoomActivity.this);
                    Fx.morph(v);
                    setPlayListPanel(false);
                }
            });
            Fx.press(plClose);
        }
        // v12.92: drawer bottom close button (same close as the ✕ above) — label follows the language via dlg_close.
        android.view.View plCloseBottom = findViewById(R.id.roomPlayListCloseBottom);
        if (plCloseBottom != null) {
            ((android.widget.Button) plCloseBottom).setText("✕  " + Lang.t(this, "dlg_close"));
            plCloseBottom.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Fx.tap(RoomActivity.this);
                    Fx.morph(v);
                    setPlayListPanel(false);
                }
            });
            Fx.press(plCloseBottom);
        }

        // v5.15: Previous talk — the Room had Next in the control row
        // and previous only as the hidden altar swipe-right (plus
        // headset/notification intents), so a listener who never
        // discovers the swipe had no visible way back. This sits
        // in the time row under the wave: the control row keeps its
        // three-button shape (v1.95 crowding note) and the left
        // stack stays gone (v5.11), and it reuses handlePrev exactly
        // (restart-from-top past 3s, else previous talk with wrap),
        // so button, swipe and headset all agree. Morph only, no
        // Morph only, no nudge.
        android.view.View prevBtn = findViewById(R.id.roomPrev);
        if (prevBtn != null) prevBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (talks.isEmpty()) {
                    Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "no_talk_yet"), Toast.LENGTH_SHORT).show();
                } else if (currentIndex < 0) {
                    Fx.tap(RoomActivity.this);
                    armNewRowFade = true;
                    Fx.morph(v);
                    playIndex(talks.size() - 1); // nothing playing yet: wrap to the last talk
                } else {
                    Fx.tap(RoomActivity.this);
                    Fx.morph(v);
                    handlePrev();
                }
            }
        });
        if (prevBtn != null) Fx.press(prevBtn);
        // v5.15: long-press Previous previews the talk a tap would
        // land on (restart of the current talk past 3s, else the
        // previous talk with wrap), mirroring Next's preview; a
        // long-press never moves anything.
        if (prevBtn != null) prevBtn.setOnLongClickListener(new android.view.View.OnLongClickListener() {
            @Override public boolean onLongClick(android.view.View v) {
                Fx.tap(RoomActivity.this);
                if (talks.isEmpty() || currentIndex < 0) {
                    Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "no_talk_yet"), Toast.LENGTH_SHORT).show();
                } else if (service != null && !service.hasTrackEnded() && service.getEffectivePosition() > 3000) {
                    // v12.23: same ended guard as handlePrev below — the >3s branch used to name
                    // "restart from the start" for a finished talk while the tap (after the fix)
                    // plays the previous talk, so the preview lied. An ended talk falls into the
                    // else branch, which mirrors the tap's history/list-previous walk.
                    // v7.67: scan-adopt before previewing — same
                    // stale-index window as the Next preview: the
                    // >3s branch named talks.get(currentIndex) as
                    // the restart target, so it named the finished
                    // talk, and the fallback named the finished
                    // talk's predecessor. handlePrev itself already
                    // scans, so only this preview was wrong. A scan
                    // miss keeps the old behaviour exactly.
                    int snd67p = -1;
                    for (int si67p = 0; si67p < talks.size(); si67p++)
                        if (isCurrentTalk(talks.get(si67p).url)) { snd67p = si67p; break; }
                    // v8.19: adopt-push — a display/step adopt landing in the pre-poll-tick window after a hidden advance used to move the index without recording the talk left behind, so the v8.06/v8.08/v8.18 correctors never fired for it (their !isCurrentTalk guard saw the sounding talk already) and a later Previous skipped it, the v8.18 swipe failure on the fav/drawer/share/preview/button paths. Drain-or-push exactly like the correctors, only when the adopt actually moves a valid index; a scan miss or a no-op adopt keeps the old behaviour exactly.
if (snd67p >= 0 && snd67p != currentIndex && currentIndex >= 0 && currentIndex < talks.size()) {
    if (!drainHiddenTrail08(talks.get(currentIndex).url)) pushPrevHistory16(talks.get(currentIndex).url);
}
if (snd67p >= 0) currentIndex = snd67p;
                    Talk cur = talks.get(currentIndex);
                    Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "room_from_start_prefix") + (favSet.contains(cur.url) ? "★ " : "") + cur.title, Toast.LENGTH_SHORT).show();
                } else {
                    // v5.16: mirror handlePrev — the preview names
                    // the last valid history entry (scanned, never
                    // popped: a preview moves nothing); without one
                    // it falls back to the list-previous talk,
                    // exactly what a tap does.
                    // v8.17: same ungating as handlePrev — the
                    // preview used to gate the history scan on
                    // shuffle, so with shuffle off it named the
                    // list neighbour while a tap now walks the
                    // heard talk; the preview lied. Scanned
                    // ungated now, fallback unchanged.
                    // v7.67: same scan-adopt as the >3s branch
                    // above — the fallback's (currentIndex - 1)
                    // and its "is current" mark both read the
                    // stale index in the background-advance
                    // window; a scan miss keeps old behaviour.
                    int snd67q = -1;
                    for (int si67q = 0; si67q < talks.size(); si67q++)
                        if (isCurrentTalk(talks.get(si67q).url)) { snd67q = si67q; break; }
                    // v8.19: adopt-push — a display/step adopt landing in the pre-poll-tick window after a hidden advance used to move the index without recording the talk left behind, so the v8.06/v8.08/v8.18 correctors never fired for it (their !isCurrentTalk guard saw the sounding talk already) and a later Previous skipped it, the v8.18 swipe failure on the fav/drawer/share/preview/button paths. Drain-or-push exactly like the correctors, only when the adopt actually moves a valid index; a scan miss or a no-op adopt keeps the old behaviour exactly.
if (snd67q >= 0 && snd67q != currentIndex && currentIndex >= 0 && currentIndex < talks.size()) {
    if (!drainHiddenTrail08(talks.get(currentIndex).url)) pushPrevHistory16(talks.get(currentIndex).url);
}
if (snd67q >= 0) currentIndex = snd67q;
                    Talk pv = null;
                    String pvSayadaw65 = "";
                    for (int h = prevHistory.size() - 1; h >= 0; h--) {
                        int hi = talkIndexForUrl(prevHistory.get(h));
                        if (hi >= 0) { pv = talks.get(hi); break; }
                        // v8.21: mirror the tap — a stack top that
                        // hands back across a handover is the
                        // Previous target, so name its recorded
                        // title instead of skipping to an older
                        // in-list talk the tap would never play.
                        // Unrecorded foreign entries keep skipping,
                        // exactly as the tap prunes them.
                        // v8.65: remember its owner — the toast
                        // below names it with its sayadaw, or a
                        // walk-back target read as a talk of THIS
                        // sayadaw (same title, wrong owner), the
                        // Previous mirror of the Next foreign
                        // gaps fixed in v8.62/v8.63/v8.64.
                        String[] back21 = handoverBackFor21(prevHistory.get(h));
                        if (back21 != null && back21[0].length() > 0
                                && back21[1].length() > 0
                                && !back21[0].equals(sayadawName)) {
                            pv = new Talk(back21[1], prevHistory.get(h));
                            pvSayadaw65 = back21[0];
                            break;
                        }
                    }
                    if (pv == null) pv = talks.get((currentIndex - 1 + talks.size()) % talks.size());
                    Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "room_prev_prefix") + previewHeadMarks(pv, pv.url.equals(talks.get(currentIndex).url)) + pv.title + (pvSayadaw65.length() > 0 ? " · " + pvSayadaw65 : "") + previewTalkMarks(pv, pv.url.equals(talks.get(currentIndex).url)), Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        // v5.17: Sleep timer in the time row — the Room could only
        // SHOW a running timer (the countdown text, GONE otherwise)
        // and the stack sleep button died with the v5.11 stack
        // removal, so arming one meant leaving the player for
        // Settings. This sits beside Previous under the wave: the
        // control row keeps its three-button shape (v1.95) and the
        // left stack stays gone (v5.11). Tap reuses the strips'
        // own picker exactly (Fx.stripSleepPick: ပိတ်/5/15/30/60/
        // တရားဆုံးရင် ရပ်မယ်), so Room, strips and Settings can
        // never disagree; a minute pick with nothing playing yet
        // still lands as the default (the "starts when a talk
        // starts" pref, like Settings), and the end-of-talk pick
        // with no live talk toasts its own need-talk message from
        // inside the picker. Long-press cancels at once via
        // Fx.stripSleepCancel, mirroring the strip sleep long-press
        // (v4.80) and the countdown's own long-press (v4.25).
        // No poll refresh: the countdown text in this same row
        // already shows the armed state, and the button's own
        // state needs none. Morph only, like Previous.
        android.view.View sleepBtn = findViewById(R.id.roomSleepBtn);
        if (sleepBtn != null) sleepBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(RoomActivity.this);
                Fx.morph(v);
                Fx.stripSleepPick(RoomActivity.this);
            }
        });
        if (sleepBtn != null) Fx.press(sleepBtn);
        if (sleepBtn != null) sleepBtn.setOnLongClickListener(new android.view.View.OnLongClickListener() {
            @Override public boolean onLongClick(android.view.View v) {
                Fx.tap(RoomActivity.this);
                Fx.morph(v);
                Fx.stripSleepCancel(RoomActivity.this);
                return true;
            }
        });
        roomShuffleBtn = findViewById(R.id.roomShuffleBtn);
        if (roomShuffleBtn != null) {
            roomShuffleBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Fx.tap(RoomActivity.this);
                    Fx.morph(v);
                    shuffleOn = !Prefs.shuffleOn(RoomActivity.this);
                    Prefs.setShuffleOn(RoomActivity.this, shuffleOn);
                    applyShuffleGlyph(roomShuffleBtn);
                    // v10.13: paused-card shuffle-glyph sync — the card reads Prefs.shuffleOn at rebuild time (v5.41 glyph block) and rebuilds run every 15s only while playing, never while paused, so a paused card kept the OLD 🔀 glyph after this tap until the next tap; Settings toggles already sync (v10.01) and the Fx strip helpers are dead code with no callers, so this Room button was the only live shuffle toggle that never refreshed the card. refreshNotification is a no-op with no track, and the glyph is a global pref, so no sounding-talk guard is needed (unlike the per-talk fav flips in v10.02).
                    if (service != null) service.refreshNotification();
                    else if (AudioService.liveSession) {
                        // v10.27: unbound leg of the v10.13 shuffle-glyph sync — the
                        // guard above misses the two windows where Room is not yet
                        // bound (before onServiceConnected fires — a fast shuffle tap
                        // right after opening the Room) or no longer bound (service
                        // death while the Room lives on), so a PAUSED card kept the
                        // OLD shuffle glyph until the next tap. The glyph is a global
                        // pref (v10.13 comment), so no sounding-talk guard is needed:
                        // the no-bind ACTION_REFRESH_NOTIFICATION intent (v10.03 shape,
                        // liveSession-guarded, never starts a dead service) rebuilds
                        // the card from the fresh pref; the handler stopSelfs when
                        // trackless and a rebuild with no glyph change paints the
                        // identical card. Bound path byte-identical.
                        try {
                            android.content.Intent rsv = new android.content.Intent(RoomActivity.this, AudioService.class);
                            rsv.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                            startService(rsv);
                        } catch (Exception ignored) {}
                    }
                }
            });
            Fx.press(roomShuffleBtn);
        }
        refreshRoomRepeatShuffle();

        // v1.51: restore the pre-rotation panel open/closed state.
        if (savedInstanceState != null && savedInstanceState.getBoolean("listVisible", false)) {
            android.view.View panel = findViewById(R.id.roomPlayListPanel);
            if (panel != null) { panel.setVisibility(View.VISIBLE); panel.setTranslationX(0f); panel.setAlpha(1f); }
            // v3.82: rotation restore is a state restore, not a
            // transition — the toggle takes its dim directly, and
            // the logical flag matches, so onResume never has to
            // guess from the panel's visibility. No entrance runs
            // on this path (saved state), so nothing fights it.
            drawerOpen = true;
            android.view.View tgl = findViewById(R.id.roomPlayList);
            if (tgl != null) tgl.setAlpha(0.8f);
        }

        // v1.67: restore a tap queued before the bind (see
        // onSaveInstanceState) — only when the list itself was restored,
        // so the index still points at the same talk it did before.
        if (savedInstanceState != null && savedInstanceState.containsKey("talkTitles")) {
            int pp = savedInstanceState.getInt("pendingPlayIndex", -1);
            if (pp >= 0) {
                pendingPlayIndex = pp;
                pendingSeekMs = savedInstanceState.getInt("pendingSeekMs", 0);
                pendingBypass = savedInstanceState.getBoolean("pendingBypass", false);
            }
        }
        // v1.69: restore the interrupted data-gate question + the
        // ended-track marker (see onSaveInstanceState) — the gate is
        // re-asked below once the restored list makes the index valid.
        if (savedInstanceState != null) {
            pendingGateIndex = savedInstanceState.getInt("pendingGateIndex", -1);
            pendingGateUrl = savedInstanceState.getString("pendingGateUrl");
            pendingGateBackUrl = savedInstanceState.getString("pendingGateBackUrl"); // v12.19
            pendingGateSeek = savedInstanceState.getInt("pendingGateSeek", 0);
            pendingGateResume = savedInstanceState.getBoolean("pendingGateResume", false); // v11.46
            endedHandledFor = savedInstanceState.getString("endedHandledFor");
            // v8.15: restore the history-back marker with its question
            // (saved beside pendingGateIndex above) — a null restore
            // keeps the old behaviour exactly when no history-back
            // question was pending, and the marker is cleared by the
            // re-asked question's own dismiss / playIndex branch.
            historyBackUrl = savedInstanceState.getString("historyBackUrl");
        }
        // v1.54: restore the pre-rotation talk list (see
        // onSaveInstanceState) — no refetch, no auto-talk replay, and
        // the open list jumps back to the playing talk, not the top.
        boolean restored = false;
        if (savedInstanceState != null && savedInstanceState.containsKey("talkTitles")) {
            java.util.ArrayList<String> rt = savedInstanceState.getStringArrayList("talkTitles");
            java.util.ArrayList<String> ru = savedInstanceState.getStringArrayList("talkUrls");
            if (rt != null && ru != null && !rt.isEmpty() && rt.size() == ru.size()) {
                for (int i = 0; i < rt.size(); i++) talks.add(new Talk(rt.get(i), ru.get(i)));
                refreshDownloadedSet();
                refreshMarkSets();
                currentIndex = savedInstanceState.getInt("currentIndex", -1);
                if (currentIndex < 0 || currentIndex >= talks.size()) currentIndex = -1;
                // v4.32: restore the hand queue with the list; stale
                // URLs are pruned lazily by peekNextIndex.
                java.util.ArrayList<String> rq = savedInstanceState.getStringArrayList("queueUrls");
                if (rq != null) { queueUrls = rq; lastQueueUrls03 = new java.util.ArrayList<String>(rq); } // v12.03 snapshot
                // v5.79: restore the armed pick-up/drop move with
                // the queue; a held URL no longer queued (cleared
                // elsewhere) never restores, and the bind's own
                // queue-membership guard is the second check.
                String mh = savedInstanceState.getString("moveHeldUrl");
                // v7.15: line-aware, like the drop handlers — a held
                // foreign line is v2, so the old contains(mh) lost
                // the arm on every rotation.
                if (mh != null && queueLineIndexForUrl(mh) >= 0) moveHeldUrl = mh;
                // v5.16: restore the shuffle history with the list;
                // stale URLs are pruned lazily by handlePrev.
                java.util.ArrayList<String> rh = savedInstanceState.getStringArrayList("prevHistory");
                if (rh != null) prevHistory = rh;
                // v8.26: the forward stack travels with the back
                // stack; stale entries are pruned lazily by
                // forwardTopIndex26 on the next peek, as before.
                java.util.ArrayList<String> fh26 = savedInstanceState.getStringArrayList("forwardHistory");
                if (fh26 != null) forwardHistory = fh26;
                // v4.55: restore the queue-only view with the list;
                // an emptied queue (cleared elsewhere) never restores
                // into an empty drawer — getCount self-heals anyway.
                if (savedInstanceState.getBoolean("drawerQueueOnly", false) && !queueUrls.isEmpty())
                    drawerQueueOnly = true;
                // v4.96: restore the filter view with the list; a
                // view whose set emptied elsewhere (★ cleared, all
                // listened) never restores into an empty drawer —
                // getCount self-heals anyway, this keeps it exact.
                // Queue-only wins when both somehow saved, since
                // the two views are exclusive.
                int savedFilter = savedInstanceState.getInt("drawerFilterMode", 0);
                if (savedFilter != 0 && !drawerQueueOnly) {
                    boolean filterOk = false;
                    try {
                        java.util.Set<String> ss = getSharedPreferences("dhamma", MODE_PRIVATE)
                                .getStringSet(savedFilter == 1 ? "favTalks" : "playedTalks", null);
                        for (int i = 0; i < talks.size(); i++) {
                            String u = talks.get(i).url;
                            // v5.14: mode 3 reads the downloaded
                            // cache, not a prefs set — refresh-
                            // DownloadedSet ran just above, so
                            // the cache is current here; the
                            // set branch below would misread
                            // it as the unlistened view.
                            if (savedFilter == 3) { if (isDownloadedFile(u)) { filterOk = true; break; } }
                            else if (savedFilter == 1) { if (ss != null && ss.contains(u)) { filterOk = true; break; } }
                            else if (ss == null || !ss.contains(u)) { filterOk = true; break; }
                        }
                    } catch (Exception ignored) {}
                    if (filterOk) drawerFilterMode = savedFilter;
                }
                // v4.98: restore the drawer search with the list —
                // the box is wired earlier in onCreate, so setting
                // its text fires the watcher (rebuild + rebind);
                // the direct rebuild covers a null box, and the
                // search composes with the restored view above
                // instead of exiting it.
                String ds = savedInstanceState.getString("drawerSearch", "");
                if (ds != null && ds.length() > 0) {
                    drawerSearchQuery = ds;
                    rebuildDrawerSearch();
                    if (drawerSearchBox != null) drawerSearchBox.setText(ds);
                }
                adapter.notifyDataSetChanged();
                if (btnRetry != null) btnRetry.setVisibility(View.GONE);
                if (nowPlaying != null) nowPlaying.setText(Lang.t(RoomActivity.this, "room_count_prefix") + talks.size() + Lang.t(RoomActivity.this, "room_count_suffix"));
                // the auto talk was already consumed before rotation (a
                // restored list only exists after a completed load), so
                // never let the recreated activity replay it from the
                // intent's still-present autoUrl.
                autoUrl = null;
                restored = true;
                if (roomList.getVisibility() == View.VISIBLE && currentIndex >= 0) {
                    int sp55 = drawerScrollPos(currentIndex);
                    if (sp55 >= 0) roomList.setSelection(sp55);
                }
                else if (roomList.getVisibility() == View.VISIBLE) {
                    int sp = savedInstanceState.getInt("listScrollPos", 0);
                    // v13.26 (D2): the saved position is a DISPLAY
                    // position in the pre-rotation view, not a talk
                    // index — applying it against whatever view the
                    // restore produced lands on the wrong row when
                    // the view changed (an emptied queue/filter
                    // falls back to the full list, where the old
                    // number names a different talk) and is silently
                    // lost when the view shrank below it. Scroll to
                    // the saved talk in the restored view first —
                    // its row, whatever view shows — and only when
                    // that talk has no row here fall back to the raw
                    // position, and only when the restored view is
                    // the same view the position was taken in and
                    // the position still exists in it; otherwise
                    // stay at the top.
                    String su26 = savedInstanceState.getString("listScrollTalkUrl");
                    int ti26 = su26 != null ? talkIndexForUrl(su26) : -1;
                    if (ti26 >= 0) {
                        int dp26 = drawerPosOfTalk(ti26);
                        if (dp26 > 0) roomList.setSelection(dp26);
                    } else {
                        String sq26 = savedInstanceState.getString("drawerSearch", "");
                        if (sp > 0 && sp < drawerDisplayCount()
                                && savedInstanceState.getBoolean("drawerQueueOnly", false) == drawerQueueOnly
                                && savedInstanceState.getInt("drawerFilterMode", 0) == drawerFilterMode
                                && sq26 != null && sq26.equals(drawerSearchQuery))
                            roomList.setSelection(sp);
                    }
                }
                // v1.69: the data-gate question interrupted by rotation
                // — ask it again now that the list is back, so the tap
                // is not silently lost (the old dialog died with the
                // old activity). The dialog's dismiss clears the
                // pending question, so this fires at most once.
                // v7.27: re-validate first — Wi-Fi may have connected
                // or the queued download may have finished during
                // the interruption, and then the question is stale:
                // asking it anyway would offer "stream" for a talk
                // that now plays offline (or needs no gate on Wi-Fi).
                // A stale question plays instead, through playIndex
                // (which re-checks the gate, the held track and the
                // resume position itself); the pending question is
                // cleared first so a later load completion cannot
                // re-ask it for a talk already playing.
                // v11.97 (B-D4): resolve the pending question's TARGET URL in the current
                // list, not the stored index -- a re-fetched list can shift indices (a talk
                // added/removed above the target), so the stored index can name a different
                // talk: the gate would be re-asked (or the talk played) for the wrong talk.
                // A URL no longer in the list (talk left the site) drops the question like
                // the v11.90 short-list clear below; a null URL (defensive only -- every
                // setter now records it) falls back to the stored index as before.
                int gi97 = pendingGateUrl != null ? talkIndexForUrl(pendingGateUrl) : pendingGateIndex;
                if (gi97 >= 0 && gi97 < talks.size()) {
                    // v11.46: a resume-mode question re-asks as a
                    // resume question, never through the fresh-play
                    // re-validation below — its stale branch would
                    // playIndex (restart/re-mark) instead of resuming
                    // the held player. v12.09: but the question itself
                    // is still re-validated — a Wi-Fi return or a
                    // finished download while it was interrupted makes
                    // the dialog stale, so a gate that no longer applies
                    // just clears the question (v11.90 tap-again rule:
                    // the next play tap resumes, since the gate no longer
                    // applies) instead of asking about mobile data on
                    // Wi-Fi. The positive button's safe fallback (resume
                    // the held talk, or play it) stays the answer when
                    // the hold is already gone.
                    if (pendingGateResume) {
                        if (needsDataGate(talks.get(gi97)))
                            askDataGate(gi97, pendingGateSeek, true);
                        else {
                            pendingGateIndex = -1;
                            pendingGateUrl = null;
                            pendingGateSeek = 0;
                            pendingGateBackUrl = null; // v12.19
                            pendingGateResume = false;
                        }
                    } else if (needsDataGate(talks.get(gi97)))
                        askDataGate(gi97, pendingGateSeek);
                    else {
                        int gi = gi97;
                        int gs = pendingGateSeek;
                        pendingGateIndex = -1;
                        pendingGateUrl = null;
                        pendingGateSeek = 0;
                        pendingGateBackUrl = null; // v12.19
                        playIndex(gi, gs);
                    }
                } else if (pendingGateIndex >= 0) {
                    // v11.90: the list came back shorter than the pending question's index
                    // (talks left the site, or a fresh shorter load after rotation) — the
                    // question was neither re-asked nor cleared, so its tap stayed swallowed
                    // (playIndex returns on a pending index) and held-resume questions stayed
                    // blocked until a sayadaw switch or process death. Clear it; the tap is
                    // gone, but the next tap plays.
                    // v12.73 (sim1280): a history-back question dropped here used to lose its
                    // popped entry and strand historyBackUrl, blocking the v8.22 fresh-Room
                    // seed until a sayadaw switch or process death. Give the entry back
                    // first — abandon's no-dialog rule (no dialog can be showing in a
                    // freshly created Room; a stale entry is pruned at once by handlePrev,
                    // so the restore is harmless when the entry left the site too).
                    if (pendingGateBackUrl != null && historyBackUrl != null) {
                        pushPrevHistory16(pendingGateBackUrl);
                        if (pendingGateBackUrl.equals(historyBackUrl)) historyBackUrl = null;
                    }
                    pendingGateIndex = -1;
                    pendingGateUrl = null;
                    pendingGateSeek = 0;
                    pendingGateBackUrl = null; // v12.19
                    pendingGateResume = false;
                }
            }
        }
        if (!restored) loadTalks(false);
        Intent svc = new Intent(this, AudioService.class);
        startService(svc);
        bound = bindService(svc, connection, Context.BIND_AUTO_CREATE);
        handler.post(pollRunnable);
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((AudioService.LocalBinder) binder).getService();
            // v1.48: play a talk queued before the bind completed.
            if (pendingPlayIndex >= 0 && pendingPlayIndex < talks.size()) {
                int idx = pendingPlayIndex;
                int seek = pendingSeekMs;
                boolean bypass = pendingBypass;
                pendingPlayIndex = -1;
                pendingSeekMs = 0;
                pendingBypass = false;
                playIndex(idx, seek, bypass);
            }
            if (service.hasTrack()) {
                // v10.68: bind-time line follows the same active rule as
                // the poll/button/card (v1.79) — a talk still buffering
                // towards play is active (⏸); only a held one reads ▶.
                // isPlaying() alone read ▶ for an unheld buffering talk,
                // promising play while a tap would hold it, until the
                // first poll tick rewrote the line. Held/prepared/
                // paused talks read exactly as before.
                boolean bindActive = service.isPlaying()
                        || (service.isBuffering() && !service.isHoldPaused());
                if (nowPlaying != null) nowPlaying.setText((bindActive ? "⏸ " : "▶ ") + service.getCurrentTitle());
                // v1.75: a track from elsewhere leaves the old index
                // stale (gold ▶ on a row that is not playing) — the
                // poll clears it too, but clear it here at once so
                // the row never flashes wrong on return.
                boolean found = false;
                for (int i = 0; i < talks.size(); i++) {
                    if (isCurrentTalk(talks.get(i).url)) {
                        // v8.20: adopt-push, same as the v8.19 sites — the bind
                        // lands after onResume, and isCurrentTalk is false while
                        // the service is unbound, so on a recreated Room (saved
                        // index = the finished talk, restored after a hidden
                        // advance) this sync is the first mover: it used to adopt
                        // the sounding talk without recording the finished one,
                        // and the poll resync's guard then never fired for it.
                        if (i != currentIndex && currentIndex >= 0 && currentIndex < talks.size()) {
                            if (!drainHiddenTrail08(talks.get(currentIndex).url)) pushPrevHistory16(talks.get(currentIndex).url);
                        }
                        currentIndex = i;
                        // v11.85: refresh the last* anchors — a talk started from the library
                        // (or another Room) opened here kept the previous session's anchors,
                        // so the notification tap / Main Mp3 Player nav opened the wrong
                        // talk (same bug class as the v1.92 playIndex site, the only other
                        // already-holding adopt to save them).
                        saveLastTalk(talks.get(i));
                        found = true; break;
                    }
                }
                if (!found && currentIndex >= 0) {
                    currentIndex = -1;
                    if (adapter != null) adapter.notifyDataSetChanged();
                }
            }
            if (pendingNext) handleNext();
            if (pendingPrev) handlePrev(); // v4.23
            if (pendingQueue && !talks.isEmpty()) openQueueDrawer(); // v4.76: restored list can open now; a still-loading list keeps the flag for load completion
            // v1.58: a re-entered Room binds after onResume, so the
            // live Settings sync (sleep/speed) runs here too.
            syncLiveSettings();
            // v1.74: same notification rebuild as onResume — the
            // bind lands after onResume, so a returning Room that
            // was not yet bound would otherwise miss it.
            service.refreshNotification();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; }
    };

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // v11.90: null check before setIntent — a null intent used to wipe the stored
        // intent first (no later code re-reads getIntent() today, so this is hygiene).
        if (intent == null) return;
        setIntent(intent);
        // v1.70: notification Next while this Room already sits on
        // top — advance in place instead of stacking a second Room.
        // v4.23: Previous takes the same path (same sayadaw-switch
        // handling below, with its own pending flag).
        // v4.76: the strip queue button takes the same path (same
        // sayadaw-switch handling, own pending flag, drawer opens
        // at load completion or at once when the list is ready).
        boolean isPrev = intent != null && ACTION_PREV.equals(intent.getAction());
        boolean isQueue = intent != null && ACTION_QUEUE.equals(intent.getAction()); // v4.76
        // v8.09: a plain notification tap carries NO action — only
        // extras (name/group/url/photo/autoUrl) — so the old early
        // return below dropped it entirely. When the Room already
        // sat on top showing a DIFFERENT sayadaw (B) while A's
        // talk kept playing, tapping the card left the user staring
        // at B's Room, not at what was playing: the tap promised a
        // return to the sounding talk and delivered the wrong
        // sayadaw. In-app launches never reach here (no SINGLE_TOP
        // flags, so they create a fresh Room), and the strip buttons
        // all carry actions, so a no-action intent here IS the
        // notification tap. Same-page tap keeps the old behaviour
        // (return; the poll already syncs the row), and a tap with
        // no page does the same. A different-page tap falls through
        // to the v1.85 switch block below, with tap semantics: no
        // pending advance, the tap's autoUrl arms the load
        // completion to land on the playing talk.
        boolean isTap = intent != null && !ACTION_NEXT.equals(intent.getAction()) && !isPrev && !isQueue;
        if (isTap) {
            String tapPage = intent.getStringExtra("url");
            if (tapPage == null || tapPage.length() == 0 || tapPage.equals(pageUrl)) return;
        }
        // v1.85: the Next intent carries the PLAYING sayadaw's page,
        // but this Room may be showing a DIFFERENT sayadaw (the user
        // opened B's Room while A's talk kept playing, then tapped
        // Next on the notification/headset). Advancing in B's list
        // from A's track only ever toasted "not in this list" and
        // nothing advanced. When the page differs, switch this Room
        // to the playing sayadaw first (header + fresh list load),
        // and let the load completion advance it (pendingNext, as
        // for a Next that arrived before the list finished loading).
        String newPage = intent.getStringExtra("url");
        if (newPage != null && newPage.length() > 0 && !newPage.equals(pageUrl)) {
            // v1.86: switching sayadaws must not carry the old list's
            // pending work into the new one — a data-gate dialog left
            // open for the old sayadaw would playIndex() into the NEW
            // list at the old index (a wrong talk), and a tap queued
            // for the bind would do the same once the service
            // connected. Dismiss the question and drop the queued tap;
            // the load completion re-arms pendingNext for this switch.
            if (gateDialog != null) {
                try { gateDialog.dismiss(); } catch (Exception ignored) {}
                gateDialog = null;
            }
            pendingGateIndex = -1;
            pendingGateUrl = null; // v11.97 (B-D4)
            pendingGateSeek = 0;
            pendingGateBackUrl = null; // v12.19
            pendingPlayIndex = -1;
            pendingSeekMs = 0;
            pendingBypass = false;
            // v11.85: a history-back step whose data-gate HEAD check is still running
            // (no dialog yet to dismiss above) leaves its popped entry stranded in
            // historyBackUrl and a stale resume-mode flag — the switch clears
            // pendingGateIndex, so the late HEAD thread returns early and no dialog
            // ever appears, but the popped entry is never restored and the v8.22
            // fresh-Room seed (guarded by historyBackUrl == null) never fires in the
            // new list. Give the entry back exactly like the gate dismiss listener
            // does (a shown dialog already restored it above, so this is a no-op
            // then), and clear the resume-mode flag with the other pending state.
            if (historyBackUrl != null) { pushPrevHistory16(historyBackUrl); historyBackUrl = null; }
            pendingGateResume = false;
            // v8.59: drop this instance's in-memory hand queue
            // BEFORE the sayadaw changes. The switch block used to
            // keep the old sayadaw's queueUrls across the switch,
            // so the new list's load completion could not restore
            // the new sayadaw's saved queue (restoreSavedQueue is
            // guarded by an empty queueUrls), and the first peek
            // after the switch pruned the old lines as stale and
            // saveQueue() wrote the pruned remainder under the NEW
            // sayadaw's key — destroying the new sayadaw's real
            // queued talks, while the drawer count and Up Next
            // named the old sayadaw's talks until then. No write
            // is needed for the old key: every queue mutation
            // saves at once, so the old key already holds this
            // list. Clearing here makes the switch behave like a
            // fresh Room for the new sayadaw (its load completion
            // restores its own saved queue), and the onResume /
            // poll syncs can no longer adopt a foreign list.
            queueUrls.clear();
            lastQueueUrls03.clear(); // v12.03: snapshot belongs to the old key
            // v10.99: a sayadaw switch must also drop the armed
            // pick-up/drop move — the v8.59 block above clears the
            // queue itself so the switch behaves like a fresh Room,
            // and a fresh Room has no arm, but moveHeldUrl survived
            // here (every other queue-emptying path clears it since
            // v7.86). A listener who armed a move, then switched
            // sayadaws (notification Next, headset, queue handover),
            // had the next drawer row tap swallowed by the drop
            // handler's stale-arm branch ("queue_changed", no play)
            // instead of playing the tapped talk; a shared URL could
            // even be moved in the new sayadaw's queue by mistake.
            moveHeldUrl = null;
            sayadawName = intent.getStringExtra("name");
            sayadawGroup = intent.getStringExtra("group");
            pageUrl = newPage;
            photoUrl = intent.getStringExtra("photo");
            // v8.09: a plain tap arms the load completion with the
            // tap's playing talk (the auto block only marks the row
            // when the service already holds it, never replays it),
            // while a Next/Prev/Queue switch still clears it and
            // advances via its pending flag instead.
            if (isTap) {
                autoUrl = intent.getStringExtra("autoUrl");
                // v12.79: the playlist-origin flag rides with
                // autoUrl — a tap switch into a new sayadaw arms
                // the same stale-first fallback; any other switch
                // clears it (see the field note).
                autoFromPlaylist = intent.getBooleanExtra("fromPlaylist", false);
            } else { autoUrl = null; autoFromPlaylist = false; }
            currentIndex = -1;
            endedHandledFor = null;
            talks.clear();
            // v11.81: reset the drawer search/filter too — the switch clears the queue
            // and the move arm so it behaves like a fresh Room, but a search typed (or a
            // star/unlistened/downloaded filter, or queue-only view) for the OLD sayadaw
            // survived, so the new list opened filtered by it and the playing talk could
            // have no visible row.
            clearDrawerSearch();
            drawerFilterMode = 0;
            drawerQueueOnly = false;
            if (adapter != null) adapter.notifyDataSetChanged();
            android.widget.TextView sdNameSwitch = findViewById(R.id.roomSayadaw);
            if (sdNameSwitch != null) sdNameSwitch.setText(
                    (sayadawName == null ? "" : sayadawName)
                            + (sayadawGroup == null ? "" : " · " + sayadawGroup));
            refreshSayadawPhoto(); // v5.38: portrait follows a sayadaw switch
            // v2.65: premium sayadaw-switch header pour — a
            // notification/headset Next that switches the Room to
            // a different sayadaw (v1.85 path) used to swap this
            // header instantly, while every other Room change
            // already moves (v2.15 panel entrance, v2.22 title
            // pour, v2.30/v2.31 row pours, photo crossfade). The
            // new name now fades + rises in with the talk-change
            // title's own rhythm (320ms, 12dp rise, decelerate,
            // ending at exactly alpha 1 / offset 0), so the switch
            // reads as one choreographed change, not a text snap
            // above moving rows. The fresh-open path above is
            // untouched — it already pours via v2.15, and this
            // switch path only runs on an existing activity, so
            // the two never double-animate. Visual only.
            android.view.View sdSwitch = findViewById(R.id.roomSayadaw);
            if (sdSwitch != null) {
                // v3.49: pour-restart continuity (v3.37 rule, extended) — a settled
                // view still pours visibly from 0/12dp; a mid-pour view flows on
                // from its current alpha/offset instead of snapping back.
                sdSwitch.animate().cancel();
                if (sdSwitch.getAlpha() >= 0.999f && sdSwitch.getTranslationY() <= 0.5f) {
                    sdSwitch.setAlpha(0f);
                    sdSwitch.setTranslationY(12f * getResources().getDisplayMetrics().density);
                }
                sdSwitch.animate().alpha(1f).translationY(0f).setDuration(320)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            }
            // v8.09: a tap means "show me what's playing", never
            // "advance" — a pending Next/Prev/Queue left over from
            // the old sayadaw's list must not fire into the new one
            // and step past the playing talk, so the tap switch
            // clears all three instead of arming one.
            if (isTap) { pendingNext = false; pendingPrev = false; pendingQueue = false; }
            else if (isQueue) pendingQueue = true; else if (isPrev) pendingPrev = true; else pendingNext = true; // v4.23, v4.76
            loadTalks(true);
            return;
        }
        // v12.01 R2: adopt the saved queue BEFORE a same-sayadaw Next/Prev/Queue —
        // the v12.00 audit's slice-C C2, confirmed: onNewIntent runs before
        // onResume, so a notification/strip Next arriving while this Room sits
        // in the back stack consumed stale queueUrls (a background advance had
        // already popped the head and persisted the shortened queue), and
        // handleNext's refused-foreign-head drop then wrote the stale list back
        // and resurrected the popped head. Read-only adopt, no-op when nothing
        // changed; the empty-list guard makes it safe before the list loads
        // (the tap defers to pendingNext and load completion re-syncs, R1).
        syncSavedQueueOnReturn();
        if (isQueue) { if (!talks.isEmpty()) openQueueDrawer(); else pendingQueue = true; } // v4.76
        else if (isPrev) handlePrev(); else handleNext(); // v4.23
    }

    // v4.32: queue resolution — the single source of truth for
    // "what plays next". peekNextIndex never mutates the queue
    // except pruning URLs that left the list (a reload after a
    // retry can shorten it); consumeNextIndex pops the head when
    // it really plays, so a queued talk plays once and then the
    // list order resumes. When the queue is empty both fall back
    // to the old list-next wrap, so every pre-queue path behaves
    // exactly as before. -1 only when there is nothing to play.
    // v4.35: the repeat button's glyph IS its state — gold loop for
    // the two looping modes (🔁 all, 🔂 one), grey ↻ for off. Same
    // gold as the sayadaw header, same grey as the clock texts, so
    // the mode reads in the altar-photo corner at a glance.
    private void applyRepeatGlyph(android.widget.Button b) {
        if (b == null) return;
        if (repeatMode == 2) {
            b.setText("🔂");
            b.setTextColor(android.graphics.Color.parseColor("#C9A227"));
        } else if (repeatMode == 0) {
            b.setText("↻");
            b.setTextColor(android.graphics.Color.parseColor("#AAAAAA"));
        } else {
            b.setText("🔁");
            b.setTextColor(android.graphics.Color.parseColor("#C9A227"));
        }
    }

    // v4.36: the shuffle button's glyph IS its state — gold 🔀
    // when shuffling, grey when list order rules. Same gold/grey
    // as the repeat button above it, so the pair reads at a glance.
    private void applyShuffleGlyph(android.widget.ImageButton b) {
        if (b == null) return;
        b.setImageResource(R.drawable.ic_shuffle);
        b.setColorFilter(android.graphics.Color.parseColor(
                shuffleOn ? "#C9A227" : "#AAAAAA"),
                android.graphics.PorterDuff.Mode.SRC_IN);
    }

    private int peekNextIndex() {
        if (talks.isEmpty()) return -1;
        // v7.69: effective current index -- the same window as
        // firstWaitingQueueEntry above, but this helper is the one
        // the row Up Next mark, the header Up Next line, Next
        // previews and consumeNextIndex all read, and several of
        // those read it OUTSIDE any gesture adopt (row rebind on
        // drawer open, poll header). The queue skip, the shuffle
        // validation/pick and the list-next fallback below all used
        // currentIndex outright, so in the window they named the
        // sounding talk itself as "next" (queue-skip missed it,
        // shuffle could pick it, list-next stepped from the
        // finished talk). A scan hit replaces the index for this
        // call only -- never mutating currentIndex, so this read
        // path stays read-only as its contract promises; a scan
        // miss keeps the old behaviour, normal path is a no-op.
        // v12.26: the scan is mirror-aware (isSoundingTalk — bound it
        // IS isCurrentTalk, so the normal path is byte-identical;
        // unbound it mirrors a live room session of this sayadaw,
        // v11.33 shape), and on a miss the indexed talk is kept
        // ONLY for the early-prepare window (live track, bound, URL
        // not yet known — the v11.81 seed rule firstWaiting-
        // QueueEntry uses); a known URL or no track leaves no
        // sounding talk, so the queue skip and the shuffle
        // exclusion below take nothing and the indexed talk stays
        // pickable/waiting, exactly like firstWaitingQueueEntry.
        // The list-next anchor keeps the scan-proven index (now
        // mirror-proven unbound too), else the Room's current
        // talk — its "next after the Room's current talk"
        // reading, unchanged on the normal path.
        int eff69 = currentIndex;
        boolean hit69 = false;
        for (int si69p = 0; si69p < talks.size(); si69p++)
            if (isSoundingTalk(talks.get(si69p).url)) { eff69 = si69p; hit69 = true; break; }
        int snd69 = hit69 ? eff69
                : (hasLiveTrackForQueue() && service != null && service.getCurrentUrl() == null
                        ? currentIndex : -1);
        // v5.73: the stale prune below used to die in memory — it
        // dropped URLs gone from the talk list from queueUrls but
        // never saved, so the disk copy kept them forever and every
        // off-Room reader of that key (Main/Talks/Settings strips,
        // notification card, chooser header) kept counting ghosts
        // until some later queue mutation happened to rewrite the
        // key. The downloaded library already writes its prune back
        // (refreshLibQueueCache); the Room now does the same. The
        // prune also used to stop at the first valid head, so stale
        // entries sitting BEHIND a valid head were never even
        // pruned in memory; the scan below walks the whole queue,
        // remembers the first match, and prunes every stale entry.
        // Persisting is safe by construction: restore/sync filter
        // the same way, so the Room would play exactly the same
        // next talk with or without the write — only the disk
        // readers change, from inflated to true. saveQueue guards
        // a null/empty sayadaw name, and the write fires only when
        // something was actually pruned, never on every poll tick.
        java.util.ArrayList<String> stale = null;
        int firstMatch = -1;
        // v5.85: the queue scan skips the talk now playing — a
        // service-restart play resumes from prefs without going
        // through playIndex (v4.32), which is the one path that
        // drops a played talk from the queue, so the saved queue
        // can still hold the sounding talk (the v5.81 header
        // count, the v5.83 clear dialog and the v4.87 chooser
        // preview already skip it on their own surfaces). Before
        // this, peek returned it as the "next" talk: the header
        // Up Next line named the talk already playing, the ▸
        // mark sat on the playing row, and a Next tap replayed
        // the same talk instead of advancing. Skipped, never
        // pruned: peek stays non-mutating apart from the stale
        // prune above, and playIndex still owns the real drop
        // when the talk is played through the Room.
        String curUrl85 = (snd69 >= 0 && snd69 < talks.size())
                ? talks.get(snd69).url : null;
        for (String u : new java.util.ArrayList<String>(queueUrls)) {
            QueueEntry qe = QueueEntry.parse(u);
            if (qe == null) {
                if (stale == null) stale = new java.util.ArrayList<String>();
                stale.add(u);
                continue;
            }
            String qu = qe.url;
            int found = -1;
            for (int i = 0; i < talks.size(); i++)
                if (talks.get(i).url.equals(qu)) { found = i; break; }
            // v5.86: skip, never stale — the v5.85 code above folded
            // the sounding talk into the stale branch (found = -1),
            // so a read path — the header poll tick calls peek on
            // every refresh — DELETED the current talk from the
            // persisted queue and wrote it back, contradicting its
            // own "Skipped, never pruned" comment and the contract
            // every other reader keeps: only playIndex drops a
            // played talk, only absence from the list prunes disk
            // (chooser refreshQueueMarks keeps in-list URLs only
            // and never writes on its tick; the library prunes
            // against the shelf, never against playback). A talk
            // that is in the list but is the current one is now
            // skipped for firstMatch and left out of stale alike,
            // so the queue on disk keeps it until a real play
            // drops it, while Up Next, the ▸ mark, the preview
            // and every Next/auto-advance tap still name the next
            // WAITING talk exactly as v5.85 promised.
            if (found >= 0 && curUrl85 != null && qu.equals(curUrl85)) continue;
            if (isFailedUrl(qu)) continue; // v13.52: the failed talk is never "next" (F1)
            if (found >= 0) { if (firstMatch < 0) firstMatch = found; }
            // v7.08: a foreign v2 line is WAITING, not stale -- it
            // has no index here by design, and pruning it would
            // delete the cross-sayadaw queue the handover plays.
            // A v2 line for this sayadaw (or a v1 line) that left
            // the list prunes exactly as before.
            else if (qe.isV2() && sayadawName != null
                    && !qe.sayadaw.equals(sayadawName)) {
            }
            else {
                if (stale == null) stale = new java.util.ArrayList<String>();
                stale.add(u);
            }
        }
        if (stale != null) { queueUrls.removeAll(stale); saveQueue(null, true); }
        if (firstMatch >= 0) return firstMatch;
        // v8.26: forward (redo) step — after a history-back, the
        // talk just left outranks shuffle/list-next but never the
        // hand queue above (an explicit order still wins) nor a
        // foreign queue head (callers check that first). Peek only:
        // the entry stays until a real play pops it in playIndex,
        // so a data-gate stop, a preview or a poll tick loses
        // nothing, and Up Next / the mark / the preview name the
        // same talk the next tap will play.
        int fwd26 = forwardTopIndex26();
        // v13.52 (audit-1374 F-B): a forward top naming the failed talk
        // is skipped, not returned — after a history-back the talk just
        // left can be the dead one, and Next must advance past it. The
        // entry stays on the stack (peek never pops), so Previous can
        // still reach it and a later successful play clears the flag.
        // v13.53 (audit-1375 F-M, settled): the guard above is
        // live-flag-only — once another talk plays, the flag clears
        // and no record of the failure remains, so a forward top
        // naming a previously-failed talk is re-offered on the next
        // Next. That is a deliberate retry, not a bug (same call as
        // the F-C single-talk wrap): one wasted replay + the standard
        // failure toast, and the replay consumes the forward entry,
        // so the Next after that moves on. No failure memory is kept.
        if (fwd26 >= 0 && !isFailedUrl(talks.get(fwd26).url)) return fwd26;
        // v4.36: shuffle — the hand queue above still outranks it
        // (a queued talk is an explicit order), but with no queue
        // the next talk is a cached random pick, not list-next.
        // Cached so every peek in one advance agrees: the Up Next
        // line, the ▸ mark, the data-gate check and the consume
        // all name the SAME talk instead of flickering through a
        // fresh random pick per poll tick. The pick is validated
        // by URL on every peek — gone from the list, or now the
        // current talk, means repick — so a row tap, a reload or
        // a rotation can never strand a stale pick. Size 1 falls
        // through to the list-next wrap (the only talk).
        if (shuffleOn && talks.size() > 1) {
            if (shuffleNextUrl != null) {
                // v11.62: a cached pick is re-validated against
                // the heard history, not just the current talk.
                // The cache used to survive unconsumed across a
                // shuffle off/on cycle and across hidden
                // background shuffle advances (the service picks
                // its own talk and the Room's cache never hears
                // about it), so after either the first Next could
                // replay a talk just heard — exactly what the
                // v7.47 fresh-pick pool excludes. A cached pick
                // now in prevHistory falls through to a fresh
                // history-excluding draw below. Within one
                // advance nothing pushes prevHistory before the
                // play, so Up Next / the mark / the gate / the
                // consume still agree on the same pick, and the
                // forward step above still outranks this branch.
                for (int i = 0; i < talks.size(); i++)
                    if (talks.get(i).url.equals(shuffleNextUrl) && i != snd69 && !prevHistory.contains(shuffleNextUrl) && !isFailedUrl(talks.get(i).url)) return i; // v13.52: never shuffle-pick the failed talk (F1)
            }
            // v7.47: a fresh shuffle pick used to exclude only the
            // current talk, so with shuffle on a just-heard talk
            // could recur after a single intervening talk (A-B-A)
            // — the v5.16 shuffle history existed but no pick ever
            // consulted it. A fresh pick now draws from talks that
            // are neither current nor in prevHistory; only when
            // that pool is empty (a 2-talk list, or a history
            // longer than the list) does it fall back to the old
            // exclude-current-only draw, so a small list can never
            // strand with no pick. The cached pick above is
            // deliberately untouched: it is the agreed Next across
            // Up Next / the mark / the gate / the consume, and a
            // history-back forward step relies on it naming the
            // talk left behind, which the history pool would
            // wrongly repick away.
            java.util.ArrayList<Integer> pool = new java.util.ArrayList<Integer>();
            for (int i = 0; i < talks.size(); i++) {
                if (i == snd69) continue;
                if (isFailedUrl(talks.get(i).url)) continue; // v13.52: never shuffle-pick the failed talk (F1)
                if (!prevHistory.contains(talks.get(i).url)) pool.add(i);
            }
            int pick;
            if (!pool.isEmpty()) {
                pick = pool.get(new java.util.Random().nextInt(pool.size()));
            } else {
                do { pick = new java.util.Random().nextInt(talks.size()); }
                while (pick == snd69);
            }
            shuffleNextUrl = talks.get(pick).url;
            return pick;
        }
        // v13.52 (audit-1374 F-C, decided): with a single talk the wrap
        // returns the failed talk itself — a deliberate retry, matching
        // the Play-toggle / row-tap retry of a failed stream, and the
        // only honest Next when there is nothing else to advance to.
        // Returning -1 here would surface handleNext's wrong
        // "playing_not_in_list" toast instead. Multi-talk lists step
        // past the failed talk, since eff69 names it.
        if (eff69 >= 0 && eff69 < talks.size()) return (eff69 + 1) % talks.size();
        return 0;
    }
    // v11.81: PEEK-ONLY now — the pop used to run here, BEFORE playIndex's bind check,
    // so a pre-bind Next durably deleted its queued head and a discarded pending play
    // (sayadaw switch, handover, close before the bind) lost the talk for good without
    // ever playing it — the exact v11.75 hole, fixed in playIndex's own removal but not
    // here. The pop also defeated playIndex's v4.43 ahead-drop on every consume path:
    // with the head already gone, queueLineIndexForUrl returned -1 there and the entries
    // ahead of the played talk survived, so a queued sounding talk replayed as "Up Next"
    // after the next talk and stranded the queue-only drawer on the finished talk. All
    // five callers pass the result straight to playIndex, whose post-gate removal
    // (subList(0, qi + 1)) now drops the head exactly once, on real play only — a gated
    // head stays queued through a cancel exactly as the v7.23 rule requires.
    private int consumeNextIndex() {
        return peekNextIndex();
    }
    // v11.68: drop a refused foreign queue head — its owner has no catalog page, so it
    // can never hand over; leaving it queued stalled every natural end (the end block
    // used to take the foreign branch and then do nothing on refusal) and toasted the
    // refusal on every manual Next. Same stale-drop rule as peek's prune: the line is
    // unplayable, not merely waiting. Only the head line is dropped; duplicates keep
    // their order and drop one per refusal, exactly like a consumed head.
    private void dropRefusedForeignHead68(QueueEntry fe) {
        if (fe == null) return;
        int fqi = queueLineIndexForUrl(fe.url);
        if (fqi >= 0) { queueUrls.remove(fqi); saveQueue(null, true); }
    }
    // v11.68: the in-list end-of-track advance — the plain auto-advance branch's body,
    // extracted unchanged so the refused-foreign-head fall-through above can run the
    // exact same advance (announcement, Next nudge, ended-row fade) instead of
    // duplicating it. nx is the peekNextIndex value the caller computed; consumeNextIndex
    // re-peeks and agrees with it, exactly as the inline block did.
    private void advanceInListAtEnd68(int nx) {
        // v3.22: capture the ended row before
        // playIndex moves currentIndex to nx —
        // the fade below needs the old index.
        final int endedIdx = currentIndex;
        armNewRowFade = true; // v3.24: no tap morph on the new row
        playIndex(consumeNextIndex()); // v4.32: peek the queued head, if any — playIndex drops it on real play (v11.81)
        // v4.14: auto-advance announces itself — a
        // listener looking away heard the title
        // pour but never got the queue-style
        // "Up next is…" notice the manual Next
        // long-press preview (v4.12) already
        // gives on demand. Same "နောက်: " text,
        // fired once per ended track by the
        // endedHandledFor guard above, never
        // on the data-gate stop or autoplay-off
        // branches (this block is autoplay-only).
        Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "next_prefix") + (favSet.contains(talks.get(nx).url) ? "★ " : "") + talks.get(nx).title, Toast.LENGTH_SHORT).show();
        // v3.04: premium auto-advance Next nudge —
        // extending the delivered manual Next
        // nudge (v2.34). A manual advance
        // nudges the button forward, but a
        // natural end-of-track advance left
        // it sitting still while the title
        // poured (v2.22/v2.70), the photo
        // crossfaded (v1.52) and the seek /
        // controls rows poured (v2.30/v2.31),
        // so the one control that means
        // "next" never moved on the advance
        // it stands for. It now nudges exactly
        // like a manual tap (10dp, 320ms,
        // decelerate, same dedicated
        // ObjectAnimator, so morph + press
        // still run untouched). Only this
        // real advance path nudges: the
        // data-gate stop below and the
        // autoplay-off branch never do, and
        // the endedHandledFor guard above
        // fires it once per ended track, so
        // the 500ms poll can never re-nudge.
        // Visual only; advance, gate and
        // autoplay behaviour untouched.
        android.view.View nxBtn = findViewById(R.id.roomNext);
        if (nxBtn != null) nudgeNext(nxBtn);
        // v3.22: premium newly-played row fade —
        // extending the delivered v3.08 newly-
        // downloaded row fade to the other mark
        // a row gains mid-session. The end block
        // above marks the finished talk played
        // and playIndex's rebind puts its ✓ on
        // the row in the same frame as the new
        // row's gold + pulse start, the title
        // pour and the photo crossfade, so the
        // finished row's title visibly jumped
        // right by the ✓ prefix with no motion
        // at all. It now fades in (existing
        // Fx.fadeIn, 250ms, alpha-only) once
        // the rebind has laid out, but only
        // while the drawer is actually open —
        // a closed drawer has no visible rows
        // to fade, and its next open pours
        // anyway (v2.10). Guards, mirroring
        // v3.08: posted after playIndex's own
        // notify, so the index->child mapping
        // is read fresh at fade time; a row
        // scrolled out by then simply has no
        // child and is skipped; the fade
        // touches the finished row only, never
        // the new row's own pulse animator
        // (v2.27); the stop branches (data-gate
        // / autoplay-off) never reach this, and
        // rightly so — there the ended row
        // stays current, so no ✓ appears at
        // all. Visual only; the played rule,
        // the advance and the gate are untouched.
        if (roomList != null && isPlayListPanelOpen()) {
            roomList.post(new Runnable() {
                @Override public void run() {
                    if (roomList == null || !isPlayListPanelOpen()) return;
                    int first = roomList.getFirstVisiblePosition();
                    // v8.54: display position, not talk index (see rescan fade).
                    int dp54 = drawerPosOfTalk(endedIdx);
                    android.view.View row = dp54 >= 0 ? roomList.getChildAt(dp54 - first) : null;
                    if (row != null) Fx.fadeIn(row, 250);
                }
            });
        }
    }
    // v7.03: shareTalk deleted — orphaned since the drawer row ⋮
    // (11:39 removal) and the now-playing long-press now inlines
    // this exact share text with its Fx.tap blip; zero callers,
    // and its comment still claimed two live doors. A future
    // edit "fixing" it would have fixed dead code.

    // v1.70: play the talk AFTER the one the service is playing now.
    // Service/binding/list not ready yet = remember it; the bind
    // callback and the load completion both flush it, so no tap is
    // lost. A current talk outside this list (e.g. a library
    // session) advances nothing — no random jump, just the list.
    // v4.76: open the Room's queue-only drawer for a strip ◈ tap
    // (ACTION_QUEUE, fresh Room or onNewIntent). The queue is the
    // hand queue restored against this list (restoreSavedQueue),
    // so opening needs the list: a caller with an empty list
    // keeps pendingQueue for load completion instead of calling
    // here, and the load-completion flush calls here with the
    // list ready. An empty queue toasts instead of opening an
    // empty drawer (queue-only getCount self-heals to the full
    // list, which would read as the wrong screen); the drawer
    // toggle still opens the full list for queueing. Setting
    // drawerQueueOnly before notify + open keeps the header,
    // ◈ marks and Up Next in queue view from the first frame.
    // v11.48: exit the queue-only view when a single remove leaves
    // nothing waiting -- the v5.87 entry gate and the v11.38 clear
    // dialog both treat "only the sounding talk left" as an empty
    // queue, but the two queue-only single-removes below only
    // dropped the view when the raw queue was fully empty. A
    // service-restart leftover sounding line (v5.86) therefore
    // kept the drawer in queue-only view showing just the talk
    // already playing, after the last waiting talk was removed
    // one by one. Same rule as the gate: a display line whose
    // URL differs from the sounding talk is a waiting row
    // (foreign v2 lines count, stale lines never display, so
    // they cannot hold the view open); soundingUrlForQueue
    // returns "" on a miss, never null, so the comparison is
    // safe. Called before saveQueue/notify, like the old
    // isEmpty check it replaces at both sites.
    private void exitQueueOnlyIfNothingWaiting() {
        if (!drawerQueueOnly) return;
        String sounding = soundingUrlForQueue();
        for (String line : drawerQueueDisplayLines()) {
            String u = queueLineUrl(line);
            if (u.length() > 0 && !u.equals(sounding)) return;
        }
        drawerQueueOnly = false;
    }

    private void openQueueDrawer() {
        pendingQueue = false;
        if (queueUrls.isEmpty()) {
            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_empty"), Toast.LENGTH_SHORT).show();
            return;
        }
        // v5.87: an all-playing queue toasts like an empty one — the
        // strips (v5.80), header (v5.81), card (v5.43) and row marks
        // (v5.82) already count waiting talks only, but this entry
        // gate counted the raw queue, so a service-restart play
        // still saved in the queue (v5.86 keeps it on disk until a
        // real play-through drops it) opened a queue-only drawer
        // whose only row was the talk already playing (⏸, no ◈) —
        // the ◈ button promised a queue and delivered the current
        // talk. Playing identity is the drawer's own currentIndex
        // talk, falling back to the session lastUrl for this
        // sayadaw (the v5.81 header / v5.83 dialog rule) when
        // nothing is indexed yet; a normal play (already popped)
        // or a queue built while browsing changes nothing, and a
        // queue with any waiting talk still opens exactly as
        // today. Rows, marks and the index column inside the
        // drawer are untouched (v5.82's raw-number convention
        // stands); only the entry gate is waiting-only now.
        // v10.92: scan-proven sounding talk, not the indexed
        // one (background-advance window, same as playUrl83).
        String playUrl87 = soundingUrlForQueue();
        boolean anyWaiting87 = false;
        // v7.41: line-aware like the v7.38 clear dialog (v5.83) —
        // the v5.87 gate compared raw lines, so a v2-spelled
        // playing talk counted as waiting and the ◈ tap opened
        // a queue-only drawer whose only row was the talk already
        // playing, the exact embarrassment v5.87 fixed for bare
        // lines. Bare lines parse to themselves, so behaviour
        // for normal queues is byte-identical.
        // v11.38: same display-lines rule as the clear dialog above — a stale line (talk
        // left the site's list, pruned only on the next peek) is not a waiting talk, so the
        // raw-queue loop opened a queue-only drawer whose rows, stale-skipped, were only
        // the sounding talk (or none) — the v5.87 embarrassment in stale form. A display
        // line other than the sounding talk is a real waiting row; foreign v2 lines still
        // count (they display and hand over), normal queues unchanged.
        for (String u : drawerQueueDisplayLines()) {
            String pu87 = queueLineUrl(u);
            if (pu87.length() == 0) continue;
            if (!pu87.equals(playUrl87)) { anyWaiting87 = true; break; }
        }
        if (!anyWaiting87) {
            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "queue_empty"), Toast.LENGTH_SHORT).show();
            return;
        }
        drawerQueueOnly = true;
        drawerFilterMode = 0; // v4.96: queue view replaces any filter view
        if (adapter != null) adapter.notifyDataSetChanged();
        setPlayListPanel(true);
    }

    private void handleNext() {
        if (service == null || talks.isEmpty()) { pendingNext = true; return; }
        pendingNext = false;
        reconcileForwardAfterBackground27(); // v8.27: notification/headset/strip Next before the poll tick
        for (int i = 0; i < talks.size(); i++) {
            if (isCurrentTalk(talks.get(i).url)) { armNewRowFade = true; // v3.24: notification/headset Next, no row morph
                // v7.60: adopt the scan-proven sounding index before
                // consuming next — a v7.58 background advance moves
                // the service on while this Room sits in the back
                // stack and its poll is paused, so currentIndex
                // still names the FINISHED talk when a notification
                // / headset / strip Next arrives via onNewIntent
                // (which runs before any return poll tick, so the
                // v7.59 poll resync cannot save it): consumeNext
                // -> peekNext reads currentIndex for list-next,
                // shuffle validation and the v5.85 queue-skip, so
                // it would target the already-playing talk and
                // playIndex's already-holding branch would stay
                // put — a dead Next press. The scan above already
                // proved i IS sounding (stream or offline-file
                // form), so adopting it is a no-op on the normal
                // path (scan index already equals currentIndex)
                // and repairs only the stale-index path; the
                // miss branch below is untouched, and handlePrev
                // needs no change (it already steps from its own
                // scan index i, never from currentIndex).
                // v8.07: same history push as the v8.06 return
                // correctors, for the first-mover case they cannot
                // cover — onNewIntent runs BEFORE onResume, so a
                // notification / headset / strip Next arriving on
                // a back-stack Room after a hidden background
                // advance (v7.58 plain, v7.63 queue, v8.05
                // shuffle) is the first to move the index, and
                // this adopt used to overwrite it without
                // recording the finished talk: playIndex then
                // pushed the sounding talk on top, so the
                // finished talk never entered prevHistory and a
                // later shuffle Previous skipped it. Push the
                // left-behind talk exactly as playIndex does
                // (cap 50); a no-op when the index already names
                // the sounding talk, and the onResume / poll
                // correctors cannot double-push because once any
                // of them moves the index the others' guards never
                // fire for the same transition.
                if (i != currentIndex
                        && currentIndex >= 0 && currentIndex < talks.size()) {
                    // v8.08: same trail drain as the return
                    // correctors, for the first-mover Next case.
                    if (!drainHiddenTrail08(talks.get(currentIndex).url)) {
                        pushPrevHistory16(talks.get(currentIndex).url);
                    }
                }
                currentIndex = i;
                if (tryForeignHandover()) return; // v7.08: foreign queue head hands over first
                // v11.68: refused handover — drop the dead head (owner has no catalog page)
                // so the refusal toast does not repeat on every Next; the in-list advance
                // below runs either way (a null entry is a no-op here).
                dropRefusedForeignHead68(firstWaitingForeignEntry());
                playIndex(consumeNextIndex()); return; } // v4.32: queued head first, else list-next
        }
        // v1.77: a Next that matches nothing used to die silently —
        // e.g. the notification/headset Next while this Room shows a
        // different sayadaw (or a library file is playing): no talk
        // advanced and nothing said why. Say so instead; a Room with
        // no track at all stays silent (there is nothing to advance).
        if (service.hasTrack())
            Toast.makeText(RoomActivity.this,
                    Lang.t(RoomActivity.this, "playing_not_in_list"),
                    Toast.LENGTH_SHORT).show();
    }

    // v4.23: Previous parity — the mirror of handleNext above,
    // stepping BACK with the same wrap (first talk wraps to the
    // last) and the same queue/bind/load flush points. One
    // streaming convention on top: like Spotify / Apple Music,
    // a Previous pressed more than 3s into a talk restarts that
    // talk instead of skipping — a listener 20 minutes in who
    // hits Previous almost always means "from the top again",
    // not "the previous hour-long talk". The restart is a plain
    // seek to 0 (play state untouched: a paused talk stays
    // paused at 0, a playing one keeps playing), and the
    // service's own seek persistence already covers it. The
    // in-Room swipe-right stays a hard previous-talk step — a
    // deliberate gesture, not a button press — so only the
    // external Previous (headset / notification / session)
    // takes the restart branch.
    private void handlePrev() {
        if (service == null || talks.isEmpty()) { pendingPrev = true; return; }
        pendingPrev = false;
        reconcileForwardAfterBackground27(); // v8.27: same race as handleNext, Previous side
        // v12.23: an ended track must not take the restart branch — the v11.89 library fix,
        // Room form: after a natural end with autoplay off, the position sits at the full
        // duration (>3000), so Previous used to seek to 0 and park the finished talk paused,
        // its ended state consumed and the poll never advancing. An ended track falls through
        // to the previous-talk walk below, like Next advances after an end.
        if (!service.hasTrackEnded() && service.getEffectivePosition() > 3000) { if (service.isBuffering()) service.restartFromStart(); else service.seekTo(0); return; }
        // v5.16: shuffle history — with shuffle on, Previous returns
        // to the talk actually heard before this one, popping the
        // stack (stale URLs pruned, never played).
        // v8.17: the stack is no longer shuffle-gated. playIndex
        // already pushes the talk being left on EVERY change
        // (not only shuffle picks) and the v8.06/v8.08 return
        // correctors push regardless of shuffle, so gating the
        // pop on shuffle made non-shuffle Previous walk the
        // list neighbour after a jump instead of the talk
        // actually heard before — the Room analogue of the
        // library gap closed in v8.11–v8.14: heard A, queue-jump
        // to D, Previous played D's list predecessor, never A;
        // a hidden queue-head advance in the background had
        // the same failure. With an empty/pruned stack the
        // list-previous talk with wrap below is unchanged, and
        // plain sequential listening is identical either way
        // (the stack top IS the list predecessor there), so
        // button, swipe, headset, notification and strip
        // Previous all still agree — they now agree on the
        // heard talk, not the neighbour.
        {
            // v8.07: adopt-push before popping — the mirror of
            // the handleNext adopt above, for the Previous-first
            // case the v8.06 return correctors cannot cover: a
            // notification / headset / strip Previous arriving
            // via onNewIntent runs BEFORE onResume, so after a
            // hidden background advance (v7.58 plain, v7.63
            // queue, v8.05 shuffle) prevHistory still lacks the
            // just-finished talk and this pop would skip straight
            // past it to an older talk. Pushing the left-behind
            // talk first (cap 50, exactly as playIndex does)
            // makes the pop below land on it — the talk actually
            // heard before this one. A no-op when the index
            // already names the sounding talk, a scan miss keeps
            // the old behaviour exactly, and no double-push is
            // possible: the pop consumes the pushed entry at
            // once, and playIndex's historyBack branch never
            // pushes on top of it. The >3s restart branch above
            // is untouched — it moves neither the index nor the
            // history, so the onResume / poll correctors still
            // own that window.
            int snd07 = -1;
            for (int si07 = 0; si07 < talks.size(); si07++)
                if (isCurrentTalk(talks.get(si07).url)) { snd07 = si07; break; }
            if (snd07 >= 0 && snd07 != currentIndex
                    && currentIndex >= 0 && currentIndex < talks.size()) {
                // v8.08: same trail drain as the return
                // correctors, for the first-mover Previous case.
                if (!drainHiddenTrail08(talks.get(currentIndex).url)) {
                    pushPrevHistory16(talks.get(currentIndex).url);
                }
            }
            while (!prevHistory.isEmpty()) {
                String u = prevHistory.remove(prevHistory.size() - 1);
                int hi = talkIndexForUrl(u);
                if (hi >= 0) { armNewRowFade = true; historyBackUrl = u; playIndex(hi); return; }
                // v8.21: cross-handover walk-back — a popped URL from
                // another sayadaw's list used to be pruned here, so
                // Previous after a foreign handover fell through to
                // the new list's neighbour instead of the talk
                // actually heard before it. When the handover
                // recorded an owner for it, hand back: the same
                // in-place switch, whose own prologue records the
                // talk being left, so a second Previous walks
                // forward again. A catalog miss returns false and
                // the loop keeps pruning, exactly as before; a
                // record naming this sayadaw cannot be a handover
                // target, so it prunes too.
                String[] back21 = handoverBackFor21(u);
                if (back21 != null && back21[0].length() > 0
                        && !back21[0].equals(sayadawName)) {
                    if (handoverToForeignTalk(
                            new QueueEntry(u, back21[0], back21[1]))) return;
                }
            }
        }
        for (int i = 0; i < talks.size(); i++) {
            if (isCurrentTalk(talks.get(i).url)) { armNewRowFade = true;
                playIndex((i - 1 + talks.size()) % talks.size()); return; }
        }
        if (service.hasTrack())
            Toast.makeText(RoomActivity.this,
                    Lang.t(RoomActivity.this, "playing_not_in_list"),
                    Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        // v4.20: Back with the Play List drawer open closes the drawer
        // first — every other screen's Back leaves the screen, but an
        // open drawer is a sheet over the Room, so Back must dismiss
        // the sheet before it can leave the Room, like the altar tap
        // and the drawer close button already do. No transition here:
        // the Room stays put, only the sheet goes away.
        if (isPlayListPanelOpen()) { setPlayListPanel(false); return; }
        super.onBackPressed();
        overridePendingTransition(R.anim.nav_in, R.anim.nav_out);
    }

    // v1.58 data saver: are we on Wi-Fi right now?
    // v1.97: NetworkCapabilities first — the legacy
    // getActiveNetworkInfo() is deprecated since API 29 and can
    // report nothing on newer phones, which wrongly put a Wi-Fi
    // listener behind the mobile-data gate (or, worse, hid the
    // gate's Wi-Fi exemption entirely). Fall back to it only
    // when no capabilities are available.
    private boolean isWifi() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
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
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected()
                    && ni.getType() == android.net.ConnectivityManager.TYPE_WIFI;
        } catch (Exception ignored) { return false; }
    }

    // v1.58: would playing this talk cost mobile data? Only when data
    // saver is on, we are NOT on Wi-Fi, and no downloaded file exists.
    private boolean needsDataGate(Talk t) {
        return Prefs.dataSaver(RoomActivity.this) && !isWifi() && localPathFor(t.url) == null;
    }

    // v12.10 (B2): the deferred advance's Room half — the end block records the gated
    // next talk here (URL + title, job -1: no download chosen yet); the gate dialog's
    // Wi-Fi button binds the DownloadManager job id to it, and the service receiver
    // re-fires the advance when that job lands. Only a real gated stop records: no
    // next talk (nx < 0) or an ungated next talk records nothing. The record is
    // consumed/cleared by the service (play / stop / landing guards); a gate cancel
    // clears it for its own talk only, below.
    private void noteGatedAdvanceTarget(int nx) {
        if (nx < 0 || nx >= talks.size()) return;
        if (!needsDataGate(talks.get(nx))) return;
        AudioService.awaitAdvanceSet(RoomActivity.this, talks.get(nx).url, talks.get(nx).title);
    }

    // v12.12 (B2 foreign extension): the v7.18 gated-stop half for a FOREIGN head — same
    // record as noteGatedAdvanceTarget, plus the head's sayadaw and kind=foreign, so the
    // service receiver can hand over (Case A) or re-fire post-handover (Case B) when the
    // Wi-Fi download lands. The gate is already proven by the v7.18 branch (data saver
    // on, not Wi-Fi, no downloaded file), so this records unconditionally here. The gate
    // dialog needs no change: its Wi-Fi button binds by URL, and a cancel clears it for
    // its own talk, exactly as for an in-list talk.
    private void noteGatedForeignAdvanceTarget(QueueEntry fe) {
        if (fe == null || fe.url == null || fe.url.length() == 0) return;
        if (fe.sayadaw == null || fe.sayadaw.length() == 0) return;
        String t12 = fe.title != null && fe.title.length() > 0 ? fe.title : fe.url;
        AudioService.awaitAdvanceSetForeign(RoomActivity.this, fe.url, t12, fe.sayadaw);
    }

    // v12.10: the DownloadManager id of a live job downloading this URL, or -1 — for the
    // gate dialog's already-queued branch, which enqueues nothing and so has no fresh
    // id to bind: the in-flight job is the awaited download, so binding its id lets the
    // landing still re-fire. Same query shape as refreshDownloadedSet's live-job scan.
    private long liveDownloadIdFor(String url) {
        try {
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null || url == null) return -1;
            android.database.Cursor c = dm.query(new android.app.DownloadManager.Query());
            if (c == null) return -1;
            try {
                int stCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS);
                int srcCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_URI);
                int idCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_ID);
                while (c.moveToNext()) {
                    int st = stCol >= 0 ? c.getInt(stCol) : -1;
                    if (st != android.app.DownloadManager.STATUS_RUNNING
                            && st != android.app.DownloadManager.STATUS_PAUSED
                            && st != android.app.DownloadManager.STATUS_PENDING) continue;
                    String src = srcCol >= 0 ? c.getString(srcCol) : null;
                    if (url.equals(src)) return idCol >= 0 ? c.getLong(idCol) : -1;
                }
            } finally { c.close(); }
        } catch (Exception ignored) {}
        return -1;
    }

    // v12.19: abandon a pending gate question whose talk the listener
    // moved past — a fresh play of a different talk (playIndex below)
    // or a background advance the poll notices (the service moved on
    // while the Room was away) ends the question instead of asking it
    // later over a talk nobody is hearing any more. A showing dialog
    // goes away through its own dismiss listener, which restores the
    // question's history-back entry and clears the resume hold exactly
    // as a cancel does; with no dialog yet (HEAD still running) the
    // fields clear here so the landing check finds nothing to show,
    // and the entry is restored from the captured value, the
    // listener's exact rule (a newer back-step's marker survives:
    // only the captured entry is pushed, and historyBackUrl clears
    // only when it IS that entry). Same talk never abandons: the
    // dialog's own stream answer plays the questioned talk, and its
    // dismiss listener ends the question.
    private void abandonPendingGateQuestion() {
        if (pendingGateUrl == null) return;
        if (gateDialog != null) {
            try { gateDialog.dismiss(); } catch (Exception ignored) {}
            gateDialog = null;
            pendingGateBackUrl = null;
            return;
        }
        if (pendingGateBackUrl != null && historyBackUrl != null) {
            pushPrevHistory16(pendingGateBackUrl);
            if (pendingGateBackUrl.equals(historyBackUrl)) historyBackUrl = null;
        }
        pendingGateIndex = -1;
        pendingGateUrl = null;
        pendingGateSeek = 0;
        pendingGateBackUrl = null;
        if (pendingGateResume) {
            pendingGateResume = false;
            if (service != null) service.clearResumeGateHeld();
        }
    }

    // v1.58: ask before spending mobile data — show the talk's real
    // size (HEAD request, no body downloaded) and let the user choose:
    // stream now, download on Wi-Fi, or cancel.
    private void askDataGate(final int idx, final int seekMs) {
        askDataGate(idx, seekMs, false);
    }

    // v11.46: resumeMode = this question is about a HELD paused
    // talk the service gate refused, not a fresh tap. The size
    // probe, the Wi-Fi-download button and the cancel path are
    // shared; only the "stream" answer differs (see there).
    private void askDataGate(final int idx, final int seekMs, final boolean resumeMode) {
        // v11.90: bounds guard — every current caller guards, but a list swap between a
        // guard and this call (or a future caller) would throw IndexOutOfBounds on the
        // UI thread from talks.get(idx) below.
        if (idx < 0 || idx >= talks.size()) return;
        // v11.85: capture THIS question's popped history entry before anything
        // dismisses — a second Previous while this question is up replaces
        // historyBackUrl before this dialog is dismissed, and the listener
        // below used to read the live field (restoring the NEW entry and
        // losing this one). Each dialog restores its own captured entry.
        final String backForThisDialog = historyBackUrl;
        // v1.77: one gate question at a time — dismiss any dialog
        // already up before starting this one (a different talk tapped
        // while a question was showing stacked a second dialog over
        // it). Dismiss first, then set the new pending question, so
        // the old dialog's dismiss listener (below) cannot clear it.
        if (gateDialog != null) {
            try { gateDialog.dismiss(); } catch (Exception ignored) {}
            gateDialog = null;
        }
        final Talk t = talks.get(idx);
        // v11.46: remember the question's mode with its index —
        // the dismiss listener and the bundle restore both need
        // to know a resume question from a fresh one.
        pendingGateResume = resumeMode;
        // v1.69: remember the question — a rotation during the HEAD
        // check (or while the dialog is up) destroys the dialog, and
        // the tap used to vanish with it. The dismiss listener below
        // clears this, and onSaveInstanceState carries it over, so
        // the recreated Room re-asks once (see onCreate).
        pendingGateIndex = idx;
        pendingGateUrl = t.url; // v11.97 (B-D4): the question's target, for replay resolution
        pendingGateBackUrl = backForThisDialog; // v12.19: this question's own entry (see field)
        pendingGateSeek = seekMs;
        // v12.09: the HEAD size probe below can take up to ~20s on slow mobile data (10s
        // connect + 10s read, plus the ranged-GET fallback), and until now the tap looked
        // dead for all of it. Say at once that the size is being checked; the dialog still
        // follows when the probe lands.
        android.widget.Toast.makeText(RoomActivity.this,
                Lang.t(RoomActivity.this, "gate_checking_size"),
                android.widget.Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                String sizeText = "";
                // v1.60: disconnect in a finally (same leak class as
                // the portrait download) and never show the dialog on
                // a finishing activity — the HEAD thread outlives a
                // rotation/back press, and a dialog on a dead window
                // throws instead of asking.
                java.net.HttpURLConnection c = null;
                try {
                    c = (java.net.HttpURLConnection)
                            new java.net.URL(t.url).openConnection();
                    c.setRequestMethod("HEAD");
                    // v1.69: same Mozilla UA as the talk loaders — a
                    // bare HEAD used to come back size-less (or
                    // refused) on hosts that expect a browser UA, so
                    // the dialog hid the talk's real MB size.
                    c.setRequestProperty("User-Agent", "Mozilla/5.0");
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    long len = c.getContentLengthLong();
                    // v1.71: some hosts answer HEAD without a length
                    // (or refuse HEAD outright) — then the dialog hid
                    // the talk's real size. Fall back to a 1-byte
                    // ranged GET: its Content-Range ("bytes 0-0/N")
                    // or Content-Length still gives the true total,
                    // and only one byte is ever fetched.
                    if (len <= 0) {
                        try { c.disconnect(); } catch (Exception ignored) {}
                        c = (java.net.HttpURLConnection)
                                new java.net.URL(t.url).openConnection();
                        c.setRequestMethod("GET");
                        c.setRequestProperty("User-Agent", "Mozilla/5.0");
                        c.setRequestProperty("Range", "bytes=0-0");
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(10000);
                        // v1.73: a ranged reply's Content-Length is 1
                        // (the one byte fetched), not the talk's size —
                        // the old code read it first and only parsed
                        // Content-Range when it was 0, so the dialog
                        // lied "≈ 0.0 MB" for exactly the hosts this
                        // fallback exists for. The total after the '/'
                        // in Content-Range ("bytes 0-0/N") is the real
                        // size; Content-Length is only the fallback.
                        String cr = c.getHeaderField("Content-Range");
                        if (cr != null) {
                            // v1.95: a malformed Content-Range (no
                            // '/') used to fall back to
                            // Content-Length — but for a 206 reply
                            // that length is 1, the one byte fetched,
                            // so the dialog lied "≈ 0.0 MB". A
                            // malformed range leaves the size
                            // unknown (no size text) instead; only a
                            // parseable "bytes 0-0/N" total is shown.
                            // A parse failure of the tail is unknown
                            // the same way, never the 1-byte length.
                            if (cr.indexOf('/') >= 0) {
                                try { len = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim()); }
                                catch (Exception ignored) {}
                            }
                        } else {
                            // No Content-Range: a plain 200 reply, so
                            // Content-Length really is the talk's size.
                            len = c.getContentLengthLong();
                        }
                    }
                    // v1.62: whole-MB truncation lied about small talks
                    // — a 900KB talk showed "≈ 0 MB" and an 8.9MB talk
                    // showed 8MB. One decimal reads as the real size.
                    if (len > 0) sizeText = String.format(" (≈ %.1f MB)", len / 1048576.0);
                } catch (Exception ignored) {}
                finally {
                    // v1.97: disconnect() alone does not close the
                    // response body — the 1-byte ranged GET's body
                    // stayed unread, so its socket never returned
                    // cleanly. Close the stream first (a HEAD reply
                    // has an empty one; an error reply throws here
                    // and is ignored like everything else), then
                    // disconnect as before.
                    if (c != null) {
                        try { java.io.InputStream s = c.getInputStream(); if (s != null) s.close(); }
                        catch (Exception ignored) {}
                        try { c.disconnect(); } catch (Exception ignored) {}
                    }
                }
                final String sz = sizeText;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // v1.63: a destroyed activity (rotation during
                        // the HEAD check) is a dead window too, not
                        // just a finishing one.
                        if (isFinishing() || isDestroyed()) return;
                        // v1.79: a STALE question's HEAD thread used
                        // to overwrite a NEWER question's dialog — tap
                        // talk A (HEAD running), tap talk B (its
                        // dialog up), A's thread lands late, dismisses
                        // B's dialog and shows A's instead, while
                        // pendingGateIndex still says B, so B's next
                        // tap is dropped forever as "already asked".
                        // Only the still-pending question may show.
                        // v11.97 (B-D4): URL identity, not index -- a re-fetched list between
                        // the question and this HEAD landing shifts indices, so the old index
                        // check could let a stale thread show its dialog for the wrong talk
                        // (or kill the live question's dialog). Only the still-pending
                        // question -- the one whose URL is still recorded -- may show.
                        if (pendingGateUrl == null || !pendingGateUrl.equals(t.url)) return;
                        // v1.77: a newer question may have been asked
                        // while this HEAD check ran — dismiss whatever
                        // dialog is up first, so only the latest
                        // question ever shows (see gateDialog above).
                        if (gateDialog != null) {
                            try { gateDialog.dismiss(); } catch (Exception ignored) {}
                            gateDialog = null;
                        }
                        gateDialog = new android.app.AlertDialog.Builder(RoomActivity.this)
                                .setTitle(Lang.t(RoomActivity.this, "gate_title"))
                                .setMessage(Lang.t(RoomActivity.this, "gate_msg_head")
                                        + t.title + "”" + sz
                                        + Lang.t(RoomActivity.this, "gate_msg_tail"))
                                .setPositiveButton(Lang.t(RoomActivity.this, "gate_btn_mobile"), new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int which) {
                                        // v11.46: resume-mode "stream"
                                        // resumes the HELD player —
                                        // playIndex's already-holding
                                        // branch would only re-mark
                                        // the row and never start
                                        // it, which is why the old
                                        // dialog could not resume a
                                        // held talk at all. When the
                                        // hold is already gone (the
                                        // talk resumed elsewhere, or
                                        // the session ended), fall
                                        // back to a bypassed play:
                                        // the listener just approved
                                        // mobile data for this talk,
                                        // so playing it is exactly
                                        // what they chose; an unbound
                                        // service takes the same
                                        // path (playIndex queues it
                                        // for the bind).
                                        // v11.97 (B-D4): answer for the CAPTURED talk, not the stored
                                        // index -- a re-fetched list between the question and this tap
                                        // shifts indices, and the old playIndex(idx, ...) would stream
                                        // the wrong talk on mobile data with the gate bypassed. Resolve
                                        // t.url in the current list; if the talk left the site, answer
                                        // nothing (the dismiss below still clears the question).
                                        int ri97 = talkIndexForUrl(t.url);
                                        if (ri97 >= 0) {
                                            if (resumeMode) {
                                                if (service != null && service.hasTrack() && isCurrentTalk(t.url))
                                                    service.resumeBypassGate();
                                                else
                                                    playIndex(ri97, seekMs, true);
                                            } else {
                                                playIndex(ri97, seekMs, true);
                                            }
                                        }
                                    }
                                })
                                .setNeutralButton(Lang.t(RoomActivity.this, "gate_btn_wifi"), new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int which) {
                                        try {
                                            // v10.73: never double-enqueue from the data gate —
                                            // needsDataGate only checks localPathFor == null, so a
                                            // talk already queued for Wi-Fi (file not landed yet)
                                            // re-asked the gate on its next tap, and this button
                                            // then enqueued a SECOND DownloadManager job for the
                                            // same URL to the same destination (downloadFileNameFor
                                            // returns the mapped name the first queue recorded) —
                                            // two jobs writing one file. The Talks batch paths
                                            // already exclude dlQueued talks, and addQueuedDownload
                                            // only dedups the prefs set, never the DM job, so the
                                            // guard belongs here: an already-queued talk toasts
                                            // "already queued" and enqueues nothing. The gate still
                                            // asks (correct — the talk is not playable offline yet,
                                            // and streaming it without asking would break data
                                            // saver); only the duplicate job goes away.
                                            boolean alreadyQueued73 = false;
                                            try {
                                                org.json.JSONArray qa73 = new org.json.JSONArray(
                                                        getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlQueued", "[]"));
                                                for (int i73 = 0; i73 < qa73.length(); i73++) {
                                                    if (t.url.equals(qa73.optString(i73))) { alreadyQueued73 = true; break; }
                                                }
                                            } catch (Exception ignored) {}
                                            if (alreadyQueued73) {
                                                // v13.57 (audit-1371 F2): the queued job may already
                                                // have LANDED — a finished job yields no live id
                                                // (liveDownloadIdFor counts RUNNING/PAUSED/PENDING
                                                // only), so the bind below never fired and the
                                                // deferred advance died silently behind the
                                                // "already queued" toast: no later completion event
                                                // can re-fire a download that already landed. If the
                                                // file is on disk, play it now instead — the listener
                                                // asked for this talk, the wait is over, and a fresh
                                                // play() supersedes the await record in the service
                                                // (AudioService :1655), so nothing can re-fire it
                                                // later. The length guard matters: localPathFor
                                                // returns a 0-byte file (audit-1368 F2, still open),
                                                // so a bare non-null path is not proof the talk plays
                                                // — only a non-empty file plays here. A talk that
                                                // left the list (re-fetch) or a held talk in
                                                // resumeMode falls through to the old toast+bind
                                                // below: the record survives for a later service
                                                // re-fire, and a held talk is never resumed as a
                                                // stream here — the listener chose Wi-Fi, never
                                                // mobile data, so resumeBypassGate (the stream
                                                // answer's held path) would spend data unapproved,
                                                // while playIndex would only re-mark it
                                                // (already-holding).
                                                String landed57 = localPathFor(t.url);
                                                boolean landedOk57 = landed57 != null
                                                        && new java.io.File(landed57).length() > 0;
                                                int ri57 = landedOk57 ? talkIndexForUrl(t.url) : -1;
                                                boolean held57 = resumeMode && service != null
                                                        && service.hasTrack() && isCurrentTalk(t.url);
                                                if (ri57 >= 0 && !held57) {
                                                    playIndex(ri57, seekMs, true);
                                                    return;
                                                }
                                                // v12.10: the queued job IS the awaited download when this talk
                                                // is the deferred advance — bind its live id so the landing
                                                // still re-fires (no fresh enqueue, so no fresh id exists).
                                                long liveId10 = liveDownloadIdFor(t.url);
                                                if (liveId10 >= 0) AudioService.awaitAdvanceBind(RoomActivity.this, t.url, liveId10);
                                                android.widget.Toast.makeText(RoomActivity.this,
                                                        Lang.t(RoomActivity.this, "dl_already_queued"), android.widget.Toast.LENGTH_LONG).show();
                                                return;
                                            }
                                            android.app.DownloadManager dm = (android.app.DownloadManager)
                                                    getSystemService(Context.DOWNLOAD_SERVICE);
                                            // v1.84: strip any ?query /
                                            // #fragment from the
                                            // download filename — the
                                            // raw basename used to
                                            // carry it, so the file
                                            // could land under a name
                                            // no offline check
                                            // recognises, and the
                                            // talk then streamed and
                                            // re-asked the gate even
                                            // though it was already
                                            // downloaded. The clean
                                            // name is the stripped
                                            // candidate localPathFor
                                            // already matches.
                                            // v1.90: plus the collision
                                            // rule (see
                                            // downloadFileNameFor) —
                                            // a basename another
                                            // talk's download already
                                            // owns gets a hash suffix
                                            // instead of overwriting
                                            // it, and the mapping is
                                            // recorded so every later
                                            // offline check finds
                                            // this talk's own file.
                                            String file = downloadFileNameFor(t.url);
                                            android.app.DownloadManager.Request req =
                                                    new android.app.DownloadManager.Request(android.net.Uri.parse(t.url));
                                            req.setTitle(t.title);
                                            req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                                            req.setAllowedNetworkTypes(android.app.DownloadManager.Request.NETWORK_WIFI);
                                            req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "Dhamma/" + file);
                                            long jobId10 = dm.enqueue(req);
                                            // v12.10: bind the awaited advance to this job — only when this talk
                                            // is the deferred next talk (bind checks the URL); any other talk's
                                            // download leaves the record untouched.
                                            AudioService.awaitAdvanceBind(RoomActivity.this, t.url, jobId10);
                                            recordDownloadFile(t.url, file);
                                            // v4.01: the row gains its ⏳ at once —
                                            // without this rebind the queued mark
                                            // would wait for the next 30s rescan
                                            // (or a Room return) to appear, and the
                                            // fade mirrors the v3.08 landed-mark fade.
                                            addQueuedDownload(t.url);
                                            // v11.56: re-scan at once — the job
                                            // above was just enqueued and
                                            // recordDownloadFile already wrote
                                            // its dlMap entry, so the live-job
                                            // set names its file from this
                                            // instant; without this refresh a
                                            // tap on the row before the next
                                            // 30s rescan would still find the
                                            // partial file via localPathFor
                                            // and play it truncated offline.
                                            refreshDownloadedSet();
                                            // v10.14: paused-card download-mark sync, Room enqueue leg of v10.06 — the card reads dlQueued at rebuild time (v5.93 ⏳ mark) and rebuilds run every 15s only while playing, never while paused, so a PAUSED sounding talk queued for Wi-Fi download from the data gate kept NO ⏳ on the card until the next tap (same class as the v10.06 completion / v10.07 Talks / v10.08 Main / v10.09 Downloaded / v10.10 Settings legs). Rebuild at once when the queued talk is the sounding one (v10.02 guard shape); refreshNotification is a no-op with no track, and a rebuild with no mark change paints the identical card. Talks batch/single enqueue sites are a separate leg (no bind there — needs the v10.03 no-bind intent) and are left for the next run.
                                            // v10.26: route through the shared v10.25 helper — the bound-only guard above missed the pre-bind / post-disconnect windows (see the helper note); bound path byte-identical.
                                            refreshCardIfFlipped1025(t.url);
                                            if (adapter != null) adapter.notifyDataSetChanged();
                                            if (roomList != null && isPlayListPanelOpen()) {
                                                roomList.post(new Runnable() {
                                                    @Override public void run() {
                                                        if (roomList == null || !isPlayListPanelOpen()) return;
                                                        int first = roomList.getFirstVisiblePosition();
                                                        // v8.54: same display-position mapping as the rescan fade above.
                                                        // v12.09: resolve the CURRENT index of this talk, not the index
                                                        // captured when the gate was asked — a re-fetch in between shifts
                                                        // rows, and the old captured index faded the wrong row.
                                                        int dp54 = drawerPosOfTalk(talkIndexForUrl(t.url));
                                                        android.view.View row = dp54 >= 0 ? roomList.getChildAt(dp54 - first) : null;
                                                        if (row != null) Fx.fadeIn(row, 250);
                                                    }
                                                });
                                            }
                                            android.widget.Toast.makeText(RoomActivity.this,
                                                    Lang.t(RoomActivity.this, "dl_room_wifi_queued"), android.widget.Toast.LENGTH_LONG).show();
                                        } catch (Exception e) {
                                            android.widget.Toast.makeText(RoomActivity.this,
                                                    Lang.t(RoomActivity.this, "download_failed") + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                                        }
                                    }
                                })
                                .setNegativeButton(Lang.t(RoomActivity.this, "gate_btn_no"), new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int which) {
                                        // v12.10: an explicit cancel abandons the deferred advance for this
                                        // talk — the listener declined the download, so nothing must
                                        // auto-play when it lands. Only this talk's record clears (a cancel
                                        // for another talk leaves the awaited one alone); a rotation destroy
                                        // fires the dismiss listener below, never this.
                                        AudioService.awaitAdvanceClearIf(RoomActivity.this, t.url);
                                    }
                                })
                                // v1.69: any way this dialog goes away
                                // (button, back press, rotation destroy)
                                // ends the pending question — the
                                // rotation case is already saved in the
                                // bundle before the dismiss fires, so
                                // the recreated Room still re-asks once.
                                // v1.77: but only THIS dialog's question
                                // — the old unconditional clear also
                                // fired when a newer question's dialog
                                // dismissed this stacked one, wiping
                                // the newer tap's pending flag with it.
                                .setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                                    @Override public void onDismiss(android.content.DialogInterface d) {
                                        // v11.97 (B-D4): URL identity -- the old index check could clear
                                        // a NEWER question's pending flag when the list had shifted under
                                        // it (same class as the HEAD stale check above); only this
                                        // dialog's own talk ends its question here.
                                        if (pendingGateUrl != null && pendingGateUrl.equals(t.url)) {
                                            pendingGateIndex = -1;
                                            pendingGateUrl = null;
                                            pendingGateSeek = 0;
                                            pendingGateBackUrl = null; // v12.19
                                            // v11.46: a resume question
                                            // ends here too — clear
                                            // its mode flag and the
                                            // service hold, so the
                                            // poll does not re-ask a
                                            // question the listener
                                            // just cancelled (a new
                                            // play tap re-arms it
                                            // through resume()). A
                                            // rotation destroy
                                            // dismisses as well, but
                                            // the bundle already
                                            // saved the question, so
                                            // the recreated Room
                                            // re-asks it once on its
                                            // own. No history-back
                                            // restore here: a resume
                                            // question never popped
                                            // a history entry, so
                                            // restoring one would
                                            // resurrect a stale step.
                                            if (resumeMode) {
                                                pendingGateResume = false;
                                                if (service != null) service.clearResumeGateHeld();
                                            } else {
                                            // v8.15: an unanswered history-back
                                            // question must give its popped
                                            // entry back — handlePrev pops the
                                            // entry BEFORE playIndex, so a
                                            // cancel / back-press / Wi-Fi-only
                                            // choice used to lose it for good:
                                            // the next Previous skipped the
                                            // talk the listener had just tried
                                            // to return to, walking to an older
                                            // one instead. Re-append it (cap 50,
                                            // exactly as every push site does)
                                            // so the stack reads as if the
                                            // back-step never started; stale
                                            // entries are still pruned lazily
                                            // by handlePrev, as since v5.16.
                                            // A stream answer reaches here
                                            // with the marker already cleared
                                            // by playIndex's historyBack
                                            // branch, so it restores nothing.
                                            // v11.85: restore THIS dialog's own captured entry, not
                                            // whatever the field holds now — a second Previous while
                                            // this question was up replaced the field before this
                                            // dialog was dismissed (its dismiss fires inside the
                                            // next askDataGate), and the old live-field read restored
                                            // the NEW entry and lost this one. A null field means the
                                            // entry was already consumed (stream answer), so nothing
                                            // restores; a different entry means a newer back-step
                                            // superseded this one, so restore ours and leave the
                                            // newer marker alone for its own dialog.
                                            if (backForThisDialog != null && historyBackUrl != null) {
                                                pushPrevHistory16(backForThisDialog);
                                                if (backForThisDialog.equals(historyBackUrl)) historyBackUrl = null;
                                            }
                                            }
                                        }
                                        gateDialog = null;
                                    }
                                })
                                .show();
                    }
                });
            }
        }).start();
    }

    // v1.92: the last* anchors the notification tap and Main's Mp3
    // Player nav return to — written by the fresh-play path and, since
    // this version, by the already-holding early return too (see there).
    private void saveLastTalk(Talk t) {
        getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                // v1.93: newest-session marker — a Room play beats
                // a library play (and vice versa) for Main's Mp3
                // Player nav, which used to know only lastPage.
                .putString("lastKind", "room")
                .putString("lastName", sayadawName == null ? "" : sayadawName)
                .putString("lastGroup", sayadawGroup == null ? "" : sayadawGroup)
                .putString("lastPage", pageUrl == null ? "" : pageUrl)
                .putString("lastPhoto", photoUrl == null ? "" : photoUrl)
                .putString("lastTitle", t.title)
                .putString("lastUrl", t.url)
                .apply();
    }

    // v7.57: background auto-advance, step 1 — persist the playing
    // list + index at play time, so the service can later advance
    // a finished talk while the Room is hidden (the audit queued
    // this as a multi-run piece: the list + index are Room
    // in-memory only today). One line per talk, url TAB title;
    // titles are tab/newline-stripped, URLs never contain either
    // (same rule as the v4.37 queue and v5.34 resume map). No
    // consumer reads these keys yet — this run only writes them,
    // so playback behaviour is unchanged. Called at both real
    // play points in playIndex below; a gate-cancel never reaches
    // either, so no unplayed talk is ever persisted as playing.
    private void savePlayList57() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Talk t : talks) {
                if (t == null || t.url == null || t.url.length() == 0) continue;
                if (sb.length() > 0) sb.append('\n');
                String title = t.title == null ? "" : t.title.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
                sb.append(t.url).append('\t').append(title);
            }
            Prefs.savePlayList(this, sb.toString(), currentIndex);
        } catch (Exception ignored) {}
    }

    private void playIndex(int idx) { playIndex(idx, 0); }

    private void playIndex(int idx, int seekMs) { playIndex(idx, seekMs, false); }

    private void playIndex(int idx, int seekMs, boolean bypassGate) {
        if (idx < 0 || idx >= talks.size()) return;
        // v7.23: the queue trim/remove below used to run FIRST,
        // before the data-gate check -- so a row tap (or a
        // handover auto-play) of a queued talk on mobile data
        // ate the queue entry the moment the gate question
        // appeared, and a cancel left nothing to retry. The
        // removal now lives in exactly two places where play is
        // real: the already-holding branch (the talk IS sounding)
        // and just past the gate check below (play actually
        // starts). Nothing between here and the gate reads the
        // queue, so the move changes no other behaviour.
        // v3.23: the row this change leaves behind (a manual row
        // tap, a Next tap, or an auto-advance) — captured before
        // currentIndex moves, so the fade below can find it.
        final int prevIdx = currentIndex;
        // v1.56: re-entering the Room while the saved talk is already
        // playing used to restart it from the saved position (bell +
        // gap) — the auto-play after every list load did not check.
        // Already playing this talk = stay put, just mark the row.
        // v1.87: the v1.76 load-completion guard covers a talk that
        // is PAUSED or still buffering too, but this one — the path
        // a tap queued before the bind takes (and any direct call)
        // — only covered playing. A Room reopened over a paused talk
        // (or one still buffering), tapped before the bind landed,
        // replayed it from 0 with a bell on bind, losing the paused
        // spot. Already HOLDING this talk (not a failed one) = stay
        // put, whatever its state; pressing play resumes it. A failed
        // track still falls through to a fresh play below.
        // v11.90: an ENDED track is not "already holding" — onCompletion never clears
        // currentTitle, so hasTrack() stays true and this early return used to re-mark
        // the row without replaying (and still ate the queue entry below). An ended
        // talk falls through to a fresh play, which restarts at 0 (its saved spot was
        // cleared on completion).
        if (service != null && service.hasTrack() && !service.hasTrackError()
                && !service.hasTrackEnded()
                && isCurrentTalk(talks.get(idx).url)) {
            // v7.23: already-holding removal (see the playIndex
            // head note) -- this talk IS sounding, so leaving the
            // hand queue is earned here, gate or no gate.
            int qiHeld = queueLineIndexForUrl(talks.get(idx).url);
            // v10.82: the old two-step (clear the prefix, then
            // remove(qiHeld)) removed the WRONG entry — after the
            // prefix clear the held talk sits at index 0, so
            // remove(qiHeld) dropped the talk after it (or threw
            // IndexOutOfBounds when the held talk was in the back
            // half of the queue). One subList clear drops the
            // ahead entries AND the held talk together, no shift.
            // v13.52: ahead-drop via the helper — a restored failed head
            // ahead of the held talk is preserved (F2). Unreachable
            // with a live error today (:11180 needs !hasTrackError);
            // symmetry with the fresh-play site below.
            if (qiHeld >= 0) { dropQueueAheadUpTo(qiHeld); saveQueue(null, true); if (adapter != null) adapter.notifyDataSetChanged(); }
            // v1.92: refresh the last* anchors here too — a talk
            // first started from the downloaded library (its file,
            // no sayadaw page) and then opened in its Room used to
            // keep the PREVIOUS talk's anchors, so the notification
            // tap and Main's Mp3 Player nav pointed at the wrong
            // talk (or refused) instead of this one. The fresh-play
            // path below already writes them; this branch never did.
            saveLastTalk(talks.get(idx));
            // v8.20: adopt-push — the main push below (v5.16) is explicit that
            // this early return never reaches it, so a playIndex for the
            // sounding talk that arrives first in the pre-poll/pre-bind window
            // after a hidden advance (a queued pre-bind tap on the sounding row,
            // a gate answer landing after the service already held the talk)
            // used to adopt the sounding index without recording the finished
            // talk, and the correctors' guard then never fired for it. Mirror
            // the main push exactly: a history-back landing consumes its marker
            // instead of pushing, otherwise drain-or-push the captured prevIdx
            // talk; the normal re-tap (prevIdx == idx) pushes nothing.
            if (historyBackUrl != null && historyBackUrl.equals(talks.get(idx).url)) historyBackUrl = null;
            else if (prevIdx >= 0 && prevIdx < talks.size() && !talks.get(prevIdx).url.equals(talks.get(idx).url)) {
                if (!drainHiddenTrail08(talks.get(prevIdx).url)) pushPrevHistory16(talks.get(prevIdx).url);
            }
            currentIndex = idx;
            savePlayList57(); // v7.57: already-holding play point
            adapter.notifyDataSetChanged();
            // v3.85: premium already-held re-mark glide — the
            // v2.32 talk-change glide covered a fresh play with
            // the sheet already open, but this early return (a
            // queued pre-bind tap or a gate answer landing after
            // the service already held this talk) still snapped
            // the open list to the playing row with setSelection
            // in one frame, while the title, seek row and controls
            // row all pour/glide around it. With the list already
            // open it now glides there with the same
            // smoothScrollToPosition the v2.32 path uses, so the
            // re-mark reads as one choreographed change; with the
            // list hidden the jump stays setSelection on purpose
            // (invisible, and the next drawer open jumps to the
            // playing talk anyway, v1.38), and the load-completion
            // re-mark below keeps its own jump so it never fights
            // the v3.06 load-into-open-drawer cascade. Visual
            // only; the row, the marks and the held talk are
            // untouched.
            if (roomList != null && roomList.getVisibility() == View.VISIBLE) {
                int sp55 = drawerScrollPos(currentIndex);
                if (sp55 >= 0) roomList.smoothScrollToPosition(sp55);
            } else if (roomList != null) {
                int sp55 = drawerScrollPos(currentIndex);
                if (sp55 >= 0) roomList.setSelection(sp55);
            }
            return;
        }
        // v1.90: a re-tapped talk used to restart from 0 even though
        // a saved position existed — only the auto-open and retry
        // paths resumed via savedPosFor. A tap (list, Next, play-all
        // or auto-advance) for a talk that is NOT already held
        // resumes where it stopped; a finished talk's saved spot
        // was cleared on completion, so it still starts fresh, and
        // an explicit seek passed in by a caller is never overridden.
        // Placed before the gate check so a data-gate question
        // carries the resumed position through to the stream choice.
        // v11.90: explicit-restart sentinel (-1, from playShownOrFirst81(true) and the
        // repeat-one fallback below) — a restart long-press must start from 0, but the
        // old 0 was re-injected with the saved position here, so a half-heard talk
        // "restarted" mid-talk. Clear this talk's saved spots (per-talk map, both
        // forms, plus the global spot when it names this talk) so a later resume
        // cannot jump back to the old spot either; the gate below then carries 0.
        if (seekMs < 0) {
            seekMs = 0;
            Prefs.talkPosRemoveBoth(this, talks.get(idx).url);
            try {
                android.content.SharedPreferences pp = getSharedPreferences("dhamma", MODE_PRIVATE);
                String savedUrl = pp.getString("lastPosUrl", "");
                String savedLocal = localPathFor(talks.get(idx).url);
                if (talks.get(idx).url.equals(savedUrl) || (savedLocal != null && savedLocal.equals(savedUrl)))
                    pp.edit().remove("lastPosUrl").remove("lastPos").apply();
            } catch (Exception ignored) {}
        } else if (seekMs == 0) seekMs = savedPosFor(talks.get(idx).url);
        // v1.58: never surprise-stream on mobile data — ask first.
        // (Placed after the already-playing early return above, so a
        // playing talk is never interrupted by its own gate.)
        // v1.74: double-tapping the same talk while its question
        // is already up (or its HEAD check still running) used to
        // stack a second identical dialog on top of the first —
        // the pending question already covers this tap, drop it.
        // (The rotation/load re-ask paths call askDataGate
        // directly, so they are unaffected by this guard.)
        if (!bypassGate && needsDataGate(talks.get(idx))) {
            // v11.97 (B-D4): URL identity -- the old index check swallowed a tap on a
            // DIFFERENT talk that a re-fetched list had moved onto the pending index,
            // and missed a re-tap on the pending talk itself at its new index.
            if (pendingGateUrl != null && pendingGateUrl.equals(talks.get(idx).url)) {
                // v12.09: the tap is already covered by the pending question — say so
                // instead of swallowing it silently while the HEAD size probe runs.
                android.widget.Toast.makeText(RoomActivity.this,
                        Lang.t(RoomActivity.this, "gate_checking_size"),
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            askDataGate(idx, seekMs);
            return;
        }
        // v12.19: a fresh play of a different talk abandons a pending
        // gate question — the v1.77 one-question rule only dismissed
        // the old dialog when a NEW question was asked, so a tap on an
        // ungated (downloaded) talk while a gated talk's HEAD check was
        // still running left the old question pending and its dialog
        // popped over the new talk once the HEAD landed (a headset
        // Next while the dialog was up left it showing the same way).
        // Placed here, before the v5.16 history push below, so the
        // restored back entry lands BELOW the talk just left. Same
        // talk (the dialog's own stream answer, bypassGate) never
        // abandons: the pending URL equals this talk, so the question
        // stands for its own dismiss listener.
        if (pendingGateUrl != null && !pendingGateUrl.equals(talks.get(idx).url))
            abandonPendingGateQuestion();
        // v1.69: a new talk starts the ended-track marker over (see
        // endedHandledFor) — otherwise the poll would think this
        // track's end was already handled.
        endedHandledFor = null;
        // v12.51: per-talk roll, deferred until this talk is
        // confirmed sounding (see requestThemeForTalk) — a
        // same-talk replay/resume still keeps its altar, and a
        // failed stream now keeps the old one too.
        requestThemeForTalk(talks.get(idx).url);
        // v13.58 (audit-1371 F3): the v5.16 history block used to sit
        // here, before the queue branch — moved past it, beside the
        // fresh-play anchors below, so a queued-then-discarded tap
        // writes no history. See the block there.
        // v8.22: fresh-Room switch seed — the v8.21 walk-back
        // chain's last hole. playIndex only ever pushed the
        // INDEXED talk being left (prevIdx), so in a FRESH Room
        // instance (prevIdx -1: Talks tap into a new Room while
        // another talk sounds, same sayadaw or foreign) the
        // sounding talk was never recorded anywhere — the new
        // Room's prevHistory stayed empty and Previous fell
        // through to the new list's neighbour instead of the
        // talk actually heard before this one. The bind sync
        // cannot save it: it scans the list, which is empty
        // until this load lands, and the v8.10 load-completion
        // drain only claims the hidden-advance trail, never the
        // sounding talk itself. Seed it here, past the gate
        // (a gate-cancel never reaches this section, so a
        // cancelled tap seeds nothing) and before saveLastTalk
        // below (which would overwrite the old session this
        // seed reads). Same-sayadaw: scan for the sounding
        // talk in this list (stream/offline forms via
        // isCurrentTalk, like every other adopt site) and
        // drain-or-push it, exactly the corrector pattern.
        // Foreign: the sounding talk is not in this list, so
        // push its stream URL and record its owner from the
        // last Room session prefs (lastKind room, lastName a
        // different sayadaw — the title comes from the service,
        // always the sounding talk's own), so handlePrev's
        // v8.21 pop hands back across the switch and the
        // Previous preview names it. Guarded to a real
        // switch: service bound with a live non-error track,
        // target not the sounding talk, no walk-back marker
        // (a history-back play must never seed), and a
        // first-ever play (no track) seeds nothing. Deliberate
        // boundaries, behaviour exactly as today, except
        // v8.25 below: a library interlude (lastKind lib)
        // used to fail the owner gate, so a Room Previous
        // never walked back into a library play. v8.25 closes
        // that boundary for mapped downloads in this fresh-
        // Room seed — the owner comes from dlSayadaw (not the
        // stale lastName pref, which a library play never
        // writes), so Previous hands back to the library
        // talk's owner Room and replays the offline file. A
        // pre-v5.45 download with no dlSayadaw entry, or a
        // filename two URLs claim, still seeds nothing.
        // v8.24: the foreign-downloaded boundary is closed for
        // mapped downloads — the sounding talk's stream URL is
        // recovered by a reverse dlMap lookup (filename ->
        // URL, exactly one match) and seeded like a stream,
        // so Previous hands back to it and its Room plays it
        // from the same offline file. A legacy file with no
        // dlMap entry, or a filename two URLs claim, still
        // skips the seed (no URL to record, never a guess).
        // v8.23: the pre-bind queued tap is NOT a boundary any
        // more — the anchors below are deferred past the queue
        // branch, so the bind replay seeds exactly like a bound
        // tap (the autoUrl path fires post-load, so the bind has
        // virtually always landed anyway).
        if (prevIdx < 0 && historyBackUrl == null
                && service != null && service.hasTrack() && !service.hasTrackError()
                && !isCurrentTalk(talks.get(idx).url)) {
            String snd22 = service.getCurrentUrl();
            if (snd22 != null && snd22.length() > 0) {
                int sIdx22 = -1;
                for (int si22 = 0; si22 < talks.size(); si22++)
                    if (isCurrentTalk(talks.get(si22).url)) { sIdx22 = si22; break; }
                if (sIdx22 >= 0) {
                    String left22 = talks.get(sIdx22).url;
                    if (!drainHiddenTrail08(left22)) pushPrevHistory16(left22);
                } else if (snd22.startsWith("http")) {
                    android.content.SharedPreferences sp22 =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    String kind22 = sp22.getString("lastKind", "");
                    String owner22 = sp22.getString("lastName", "");
                    if ("room".equals(kind22) && owner22 != null && owner22.length() > 0
                            && !owner22.equals(sayadawName) && !snd22.equals(talks.get(idx).url)) {
                        if (!drainHiddenTrail08(snd22)) pushPrevHistory16(snd22);
                        String title22 = service.getCurrentTitle();
                        recordHandoverBack21(snd22, owner22, title22 == null ? "" : title22);
                    }
                } else {
                    // v8.24: foreign sounding talk held as a
                    // downloaded file — service.getCurrentUrl()
                    // is the local path, not the stream URL the
                    // seed and the v8.21 walk-back record need.
                    // Recover it by reversing dlMap (URL ->
                    // filename, written at download time): the
                    // sounding file's basename names exactly one
                    // mapped URL, or nothing is seeded — zero
                    // matches (legacy pre-map file) and two or
                    // more (a filename two URLs claim) both skip,
                    // never guessing. The owner gate is the same
                    // as the stream branch, and the recovered URL
                    // feeds drain/push/record exactly like one,
                    // so handlePrev's v8.21 pop hands back across
                    // the switch and the foreign Room replays it
                    // from this same offline file.
                    String rec24 = null;
                    int matches24 = 0;
                    try {
                        String base24 = new java.io.File(snd22).getName();
                        org.json.JSONObject map24 = new org.json.JSONObject(
                                getSharedPreferences("dhamma", MODE_PRIVATE)
                                        .getString("dlMap", "{}"));
                        java.util.Iterator<String> keys24 = map24.keys();
                        while (keys24.hasNext()) {
                            String k24 = keys24.next();
                            if (base24.equals(map24.optString(k24, ""))) {
                                rec24 = k24;
                                matches24++;
                            }
                        }
                    } catch (Exception ignored) { rec24 = null; matches24 = 0; }
                    if (matches24 == 1 && rec24 != null && rec24.length() > 0) {
                        android.content.SharedPreferences sp24 =
                                getSharedPreferences("dhamma", MODE_PRIVATE);
                        String kind24 = sp24.getString("lastKind", "");
                        String owner24 = sp24.getString("lastName", "");
                        if ("room".equals(kind24) && owner24 != null && owner24.length() > 0
                                && !owner24.equals(sayadawName)
                                && !rec24.equals(talks.get(idx).url)) {
                            if (!drainHiddenTrail08(rec24)) pushPrevHistory16(rec24);
                            String title24 = service.getCurrentTitle();
                            recordHandoverBack21(rec24, owner24,
                                    title24 == null ? "" : title24);
                        } else if ("lib".equals(kind24)) {
                            // v8.25: library interlude, fresh-Room
                            // scope only — the v8.22 documented
                            // boundary, closed for mapped
                            // downloads. A library play writes
                            // lastKind lib but NO lastName, so
                            // the stale lastName pref must never
                            // be used as the owner here (it names
                            // an older Room session, not the
                            // library talk). The owner comes
                            // from dlSayadaw (URL -> sayadaw,
                            // written at download time since
                            // v5.45, beside dlMap): with it, the
                            // recovered URL seeds drain/push/
                            // record exactly like a foreign
                            // stream, so handlePrev's v8.21 pop
                            // hands back to the owner's Room,
                            // which replays it from the same
                            // offline file. No dlSayadaw entry
                            // (pre-v5.45 download) seeds nothing
                            // — never a guess — and a same-
                            // sayadaw library talk is already
                            // covered by the scan branch above
                            // when it is in this list, so the
                            // != sayadawName guard only keeps
                            // a scan miss from recording a
                            // handover to this very Room (which
                            // handlePrev would prune anyway).
                            String owner25 = null;
                            try {
                                org.json.JSONObject sm25 = new org.json.JSONObject(
                                        sp24.getString("dlSayadaw", "{}"));
                                owner25 = sm25.optString(rec24, "");
                            } catch (Exception ignored) { owner25 = null; }
                            if (owner25 != null && owner25.length() > 0
                                    && !owner25.equals(sayadawName)
                                    && !rec24.equals(talks.get(idx).url)) {
                                if (!drainHiddenTrail08(rec24)) pushPrevHistory16(rec24);
                                String title25 = service.getCurrentTitle();
                                if (title25 == null || title25.length() == 0)
                                    title25 = sp24.getString("lastLibTitle", "");
                                recordHandoverBack21(rec24, owner25,
                                        title25 == null ? "" : title25);
                            }
                        }
                    }
                }
            }
        }
        Talk t = talks.get(idx);
        if (nowPlaying != null) {
            nowPlaying.setText("⏸ " + t.title); // v2.47: action rule — starting = will play = ⏸
            // v2.09: the title fades in with the shrine-photo crossfade
            // on every talk change instead of swapping instantly.
            // v2.22: fade + a 12dp rise, not alpha-only — the title now
            // pours in the same way the v2.15 panel entrance pours the
            // rows in on open, and the rise ends at exactly 0 so the
            // 500ms poll (which only sets text) never leaves it offset.
            // v2.22: the play button morphs on every new talk start too —
            // the v2.07 morph only fired on a play/pause flip, so a Next
            // or row tap while already playing gave no visible beat at
            // all; guarded, since playIndex can run before the button
            // exists on the queued pre-bind path.
            // v3.37: premium talk-change pour-restart continuity —
            // the title/seek/controls pours below used to force
            // alpha 0 + 12dp rise unconditionally, so a rapid second
            // talk change (Next twice, or a row tap mid-pour) snapped
            // each row back to fully faded / fully dropped in one
            // frame before pouring again — the same restart snap
            // v3.34/v3.35/v3.36 fixed for the nudges, the morph and
            // the drawer. A pour that already finished (alpha 1,
            // offset 0) still starts from 0/12dp exactly as before,
            // so an ordinary talk change pours visibly; a pour still
            // in flight now animates on from its current alpha and
            // offset over the same 320ms instead of jumping back.
            // Each row is judged on its own values, since the three
            // animators are independent, and the onPause/onDestroy
            // resets still land every row at exactly 1/0 on leave.
            // Visual only; text, timing targets and the pour rhythm
            // for a settled row are untouched.
            nowPlaying.animate().cancel();
            if (nowPlaying.getAlpha() >= 0.999f && nowPlaying.getTranslationY() <= 0.5f) {
                nowPlaying.setAlpha(0f);
                nowPlaying.setTranslationY(12f * getResources().getDisplayMetrics().density);
            }
            nowPlaying.animate().alpha(1f).translationY(0f).setDuration(320)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
        if (btnPlayPause != null) Fx.morph(btnPlayPause);
        // v2.30: the seek row joins the talk-change choreography —
        // the title (v2.22) and the photo (v1.52 crossfade) already
        // pour in on every talk change, but the elapsed/bar/total
        // row just snapped to 0:00, so the bottom of the panel
        // changed in a different rhythm from the rest of it. It
        // now fades + rises in with the title (same 320ms, same
        // 12dp rise, ending at exactly alpha 1 / offset 0). The
        // row's own animator touches only the row: the v2.29 drag
        // thicken acts on the bar inside it (scaleY), the v2.20
        // glide writes the bar's progress, and the poll only sets
        // text — none of them is disturbed, and a fresh-open
        // panel entrance (v2.15) is simply superseded by this
        // one, which ends at the same clean state.
        android.view.View seekRow = findViewById(R.id.roomSeekRow);
        if (seekRow != null) {
            // v3.37: same pour-restart rule as the title above —
            // settled rows start from 0/12dp, mid-pour rows flow on.
            seekRow.animate().cancel();
            if (seekRow.getAlpha() >= 0.999f && seekRow.getTranslationY() <= 0.5f) {
                seekRow.setAlpha(0f);
                seekRow.setTranslationY(12f * getResources().getDisplayMetrics().density);
            }
            seekRow.animate().alpha(1f).translationY(0f).setDuration(320)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
        // v2.31: the controls row joins the talk-change choreography —
        // the title (v2.22) and the seek row (v2.30) already pour in
        // on every talk change, but the play/next/list row just sat
        // static, so the bottom of the panel changed in two rhythms
        // again; it now fades + rises in with them (same 320ms, same
        // 12dp rise, ending at exactly alpha 1 / offset 0). Row-only
        // animation — the buttons inside keep their own scale
        // animators (v2.07/v2.09 morph, v2.08 press), which touch
        // the buttons, never this row — and onPause/onDestroy cancel
        // the row animator and reset alpha/translation so a leave
        // mid-fade never strands faded controls.
        android.view.View controlsRow = findViewById(R.id.roomControlsRow);
        if (controlsRow != null) {
            // v3.37: same pour-restart rule as the title above —
            // settled rows start from 0/12dp, mid-pour rows flow on.
            controlsRow.animate().cancel();
            if (controlsRow.getAlpha() >= 0.999f && controlsRow.getTranslationY() <= 0.5f) {
                controlsRow.setAlpha(0f);
                controlsRow.setTranslationY(12f * getResources().getDisplayMetrics().density);
            }
            controlsRow.animate().alpha(1f).translationY(0f).setDuration(320)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
        // v1.55: the seek bar used to keep showing the previous talk's
        // progress until the new stream prepared — reset it at once so
        // a talk change never looks half-played from the old talk.
        // v1.56: the range itself was left at the old talk's length, so
        // dragging before the new stream prepared sought into the old
        // talk's range — reset the max with the progress.
        if (!seeking) {
            // v2.20: stop any seek glide with the reset — a glide
            // from the previous talk would otherwise write its old
            // progress over this clean bar until the next poll.
            if (seekGlide != null) { seekGlide.cancel(); seekGlide = null; }
            lastUserSeekMs = 0; // v3.26: a talk change voids the settle hold
            if (seekBar != null) {
                seekBar.setMax(0);
                seekBar.setProgress(0);
                seekBar.setBufferedPercent(0); // v4.06: a new talk starts unbuffered
                seekBar.setBookmarks(null); // v12.64: stale pins go with the old talk; the poll re-adds the new talk's marks once its duration arrives
            }
            if (elapsedText != null) elapsedText.setText("0:00");
            if (totalText != null) totalText.setText("0:00");
        }
        // v1.48: queue instead of dropping the tap when the service
        // has not bound yet (see pendingPlayIndex above).
        if (service == null) {
            pendingPlayIndex = idx;
            pendingSeekMs = seekMs;
            pendingBypass = bypassGate;
            adapter.notifyDataSetChanged();
            return;
        }
        pendingPlayIndex = -1;
        pendingSeekMs = 0;
        pendingBypass = false;
        // v11.75: fresh-path queue removal, moved here from above
        // the bind queue (v7.23: AFTER the gate) — the entry used
        // to drop up there, BEFORE the service-null check, so a
        // pre-bind tap durably deleted its queued entry and then
        // only queued in pendingPlayIndex; a
        // discarded pending tap (sayadaw switch, foreign
        // handover, Room finish before the bind) lost the talk
        // for good without ever playing it. The entry now drops
        // only where play is real, like the v8.23 anchors below
        // — the bind replay removes it exactly once. v4.32 (a
        // talk that starts playing leaves the hand queue;
        // consume paths now peek only (v11.81), so this removal drops the head
        // exactly once on real play) and v4.43 (jumping to a queued talk drops the
        // entries ahead of it) are unchanged; a gate answer of
        // "stream" reaches here via bypassGate and drops the
        // entry; a cancel or a Wi-Fi download choice never
        // reaches here, so the entry stays queued.
        int qi = queueLineIndexForUrl(talks.get(idx).url);
        // v10.82: same shift bug as the already-holding branch
        // above — clear(0, qi) moved the tapped talk to index 0,
        // so remove(qi) then dropped a later talk (or crashed
        // with IndexOutOfBounds in the back half of the queue)
        // while the tapped talk stayed queued. One clear of
        // 0..qi drops the ahead entries and the tapped talk.
        // v13.52: ahead-drop via the helper — a restored failed head
        // ahead of the tapped talk is preserved (F2), not deleted.
        if (qi >= 0) { dropQueueAheadUpTo(qi); saveQueue(null, true); if (adapter != null) adapter.notifyDataSetChanged(); }
        // v13.58 (audit-1371 F3): the v5.16 history block moved here,
        // past the service==null queue branch, beside the other
        // real-play anchors — it used to run BEFORE the queue, so a
        // tap queued for the bind already pushed prevHistory and
        // popped/cleared forwardHistory, and a tap discarded before
        // the bind (sayadaw switch, Room finish) left a phantom
        // Previous step and a wiped redo stack behind; a history-back
        // tap was worse — the marker was consumed pre-bind, so the
        // bind replay took the else branch (phantom prev push +
        // forward wipe) and a switch discard lost the popped entry
        // outright (its restore sees a null marker). Now a queued tap
        // writes no history at all: the bind replay runs this block
        // exactly once, like a bound tap, and a discarded tap leaves
        // prev/forward/historyBackUrl untouched. The gate-cancel and
        // already-holding rules are unchanged (neither reaches here).
        // v5.16: record the talk being left on the shuffle history
        // (prevIdx was captured at the top of playIndex, before any
        // move). The already-holding early return above never
        // reaches here, so a re-tap of the sounding talk pushes
        // nothing; a history-back play consumes its marker instead
        // of pushing (see historyBackUrl), so stepping back twice
        // walks further back instead of bouncing forward.
        if (historyBackUrl != null && historyBackUrl.equals(talks.get(idx).url)) {
            historyBackUrl = null;
            // v8.26: the back-step really plays, so record the talk
            // left behind as the forward step (see the field note).
            // A gate-cancel never reaches here, so a cancelled
            // back-step pushes nothing — the v8.15 restore of the
            // popped prevHistory entry stays exact, with no forward
            // entry to undo. The already-holding branch above never
            // reaches here either, so a back-step landing on an
            // already-sounding talk records no phantom forward step.
            if (prevIdx >= 0 && prevIdx < talks.size() && !talks.get(prevIdx).url.equals(talks.get(idx).url)) {
                pushForwardHistory26(talks.get(prevIdx).url);
            }
        } else {
            if (prevIdx >= 0 && prevIdx < talks.size() && !talks.get(prevIdx).url.equals(talks.get(idx).url)) {
                pushPrevHistory16(talks.get(prevIdx).url);
            }
            // v8.26: forward pop-or-clear — playing the pending
            // forward target consumes it (the remaining stack stays,
            // so repeated Next walks forward through every back-step
            // in order, and playIndex's prev push above lets a later
            // Previous walk back again); any other fresh play is a
            // branch that invalidates the redo, so the stack clears.
            // The already-holding branch never reaches here, so a
            // re-tap of the sounding talk clears nothing.
            if (!forwardHistory.isEmpty() && forwardHistory.get(forwardHistory.size() - 1).equals(talks.get(idx).url)) {
                forwardHistory.remove(forwardHistory.size() - 1);
            } else {
                forwardHistory.clear();
            }
            saveForwardHistory27(); // v8.27: always, even empty -- see the field note
        }
        // v8.23: play-point anchors deferred to real play — the
        // v8.22 fresh-Room seed's pre-bind boundary, closed.
        // The pre-bind call used to set currentIndex = idx and
        // saveLastTalk(t) BEFORE queuing, so the bind replay
        // saw prevIdx == idx (seed guard prevIdx < 0 failed,
        // indexed push no-op'd) and prefs already overwritten
        // with this Room's sayadaw (foreign owner gate failed
        // too) — a tap landing before the bind in a fresh Room
        // lost the sounding talk entirely and Previous fell to
        // the new list's neighbour. Now the queued path writes
        // no anchors: currentIndex stays -1 and lastName still
        // names the sounding talk's sayadaw, so the replay runs
        // the v8.22 seed exactly like a bound tap (same-sayadaw
        // scan seed + foreign owner seed). A non-fresh queued
        // tap is unchanged: prevIdx is the restored index and
        // the indexed push records the talk being left. The
        // title/pour/seek-reset above stay pre-queue (visual
        // only, they read no anchors); a bind that never lands
        // now persists no phantom play point either.
        currentIndex = idx;
        savePlayList57(); // v7.57: fresh-play point (past the gate)
        saveLastTalk(t);
        // v1.67: the bell marks a talk actually starting — it used to
        // ring here even for a tap only queued for the bind, so the
        // listener heard a start bell and then silence until the
        // service connected and the talk really began.
        Fx.bell(RoomActivity.this);
        // v1.42: play the downloaded file when one exists — offline
        // playback, no mobile data, same title and resume position.
        String local = localPathFor(t.url);
        service.play(local != null ? local : t.url, t.title, seekMs);
        // v11.46: a bypassed STREAM play is a gate approval for
        // this talk's session — its later pauses must resume
        // without re-asking (the service gate checks this). An
        // offline play needs no approval and sets none.
        if (bypassGate && local == null) service.noteGateApproved(t.url);
        adapter.notifyDataSetChanged();
        // v3.23: premium previous-row fade — extending the
        // delivered v3.08 newly-downloaded row fade and v3.22
        // newly-played row fade to the third mark a drawer row
        // loses mid-session. The rebind above strips the old
        // row's gold + ⏸ prefix in the same frame as the new
        // row's gold + pulse start, the title pour and the photo
        // crossfade, so the old row's title visibly jumped left
        // by the prefix width with no motion at all — on every
        // manual row tap and Next tap, not just auto-advance.
        // The old row now fades in (existing Fx.fadeIn, 250ms,
        // alpha-only) once the rebind has laid out, but only
        // while the drawer is actually open — a closed drawer's
        // next open pours anyway (v2.10). Guards, mirroring
        // v3.08/v3.22: posted after this notify, so the
        // index->child mapping is read fresh at fade time; a
        // row scrolled out by then simply has no child and is
        // skipped; the fade touches the old row only, never the
        // new row's own pulse animator (v2.27); a same-talk
        // re-tap (prevIdx == idx) and a first-ever play
        // (prevIdx < 0) have no old row and skip it; the
        // auto-advance case simply re-fades the same row the
        // v3.22 post then fades again back-to-back, which reads
        // as one fade, not two. Visual only; the advance, the
        // played/downloaded marks and the gate are untouched.
        if (prevIdx >= 0 && prevIdx != idx && roomList != null && isPlayListPanelOpen()) {
            roomList.post(new Runnable() {
                @Override public void run() {
                    if (roomList == null || !isPlayListPanelOpen()) return;
                    int first = roomList.getFirstVisiblePosition();
                    // v8.54: display position, not talk index (see rescan fade).
                    int dp54 = drawerPosOfTalk(prevIdx);
                    android.view.View row = dp54 >= 0 ? roomList.getChildAt(dp54 - first) : null;
                    if (row != null) Fx.fadeIn(row, 250);
                }
            });
        }
        // v3.24: premium new-current-row fade — completing the
        // delivered v3.08/v3.22/v3.23 mark-fade family with the one
        // mark-gain case that still snapped: a row that BECOMES
        // current gains gold + the ⏸ prefix in the same rebind
        // frame, so its title jumped right by the prefix width.
        // On a manual row tap the morph already covers the row, so
        // this fires only when armed (Next / auto-advance /
        // notification Next — see armNewRowFade), never on a tap.
        // Same guards as v3.23: posted after the rebind so the
        // index->child mapping is read fresh, only while the
        // drawer is open, a scrolled-out row is skipped, and the
        // fade touches the row view only, never the title's own
        // pulse animator (v2.27). A first-ever play (prevIdx < 0)
        // and a same-talk re-tap (prevIdx == idx) have no newly
        // current row and skip it; the arm is consumed here, so a
        // later unrelated playIndex can never inherit it. Visual
        // only; the advance, the marks and the gate are untouched.
        if (armNewRowFade && prevIdx >= 0 && prevIdx != idx && roomList != null && isPlayListPanelOpen()) {
            final int newIdx = idx;
            roomList.post(new Runnable() {
                @Override public void run() {
                    if (roomList == null || !isPlayListPanelOpen()) return;
                    int first = roomList.getFirstVisiblePosition();
                    // v8.54: display position, not talk index (see rescan fade).
                    int dp54 = drawerPosOfTalk(newIdx);
                    android.view.View row = dp54 >= 0 ? roomList.getChildAt(dp54 - first) : null;
                    if (row != null) Fx.fadeIn(row, 250);
                }
            });
        }
        armNewRowFade = false;
        // v1.38: the visible list follows the playing talk on every change.
        // v2.32: it now glides there instead of jumping — with the sheet
        // already open, a Next tap / row tap / auto-advance used to snap
        // the list to the new row instantly while the title, seek row
        // and controls row all pour in over 320ms (v2.22/v2.30/v2.31),
        // so the sheet moved in a different rhythm from the panel it
        // sits over. smoothScrollToPosition glides to the same row;
        // the sheet-open jump (toggle), rotation restore and list-load
        // jumps keep setSelection on purpose — only a talk change
        // with the sheet already open glides. No animator to cancel:
        // the ListView's own scroll stops when the view detaches.
        if (roomList != null && roomList.getVisibility() == View.VISIBLE) {
            int sp55 = drawerScrollPos(currentIndex);
            if (sp55 >= 0) roomList.smoothScrollToPosition(sp55);
        }
    }

    private class TalkAdapter extends BaseAdapter {
        @Override public int getCount() {
            // v4.55: queue-only self-heal — a queue emptied by any
            // path (header clear, chooser sync, auto-advance pop)
            // must never leave an empty drawer behind; the flag
            // drops here on the next layout/notify and the full
            // list returns. The explicit resets in the menu and
            // header handlers cover the common paths at once.
            if (drawerQueueOnly && queueUrls.isEmpty()) drawerQueueOnly = false;
            // v4.96: a filtered view emptied by any path (last ★
            // unstarred elsewhere, last unlistened finished) never
            // leaves an empty drawer behind — the full list returns.
            if (!drawerQueueOnly && drawerFilterMode != 0 && drawerFilteredIndices().isEmpty()) drawerFilterMode = 0;
            return drawerDisplayCount();
        }
        // v11.63: getItem/getItemId honour the display mapping —
        // the old code read talks.get(p) with p a DISPLAY position,
        // so in queue-only / filter / search views it returned the
        // wrong talk (or crashed past the end of a short view),
        // while the Talks chooser (talkIndexAtDisplay) and the
        // library (libEntryAtDisplay) already mapped. No live path
        // reads getItem today (no getItemAtPosition anywhere), so
        // this was latent — but the adapter contract is now true
        // in every view: foreign queue rows return their
        // QueueEntry, in-list rows their talk, stale slots null;
        // ids are the talk index for in-list rows and the URL hash
        // for foreign rows (stable across views; the drawer uses
        // no choice mode, so no checked-state behaviour changes).
        @Override public Object getItem(int p) {
            QueueEntry fe = drawerForeignEntry(p);
            if (fe != null) return fe;
            int ti = drawerTalkIndex(p);
            return ti >= 0 && ti < talks.size() ? talks.get(ti) : null;
        }
        @Override public long getItemId(int p) {
            QueueEntry fe = drawerForeignEntry(p);
            if (fe != null && fe.url != null) return (long) fe.url.hashCode();
            int ti = drawerTalkIndex(p);
            return ti >= 0 ? ti : p;
        }
        @Override public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(RoomActivity.this).inflate(R.layout.row_room_talk, parent, false);
                Fx.press(v); // v2.08: press feedback on Room list rows
            }
            v.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            // v3.45: premium drawer recycled-row pour hygiene, mirroring
            // the delivered v3.40 hygiene in the Main, Talks and
            // Downloaded adapters, which this drawer adapter never got.
            // Drawer rows are animated as whole views — the open pour
            // (v2.10), the close cascade (v3.05) and the mark fades
            // (v3.08/v3.22/v3.23/v3.24) all run ViewPropertyAnimators
            // on the row itself — so a view recycled mid-animation
            // (scroll during the pour window) used to carry its old
            // animator onto the new talk: the new row showed up
            // half-faded, drifted right or dropped down, then rose
            // late on content that never poured. A rebind now
            // cancels any stale row animator and restores the
            // settled state (alpha 1, X/Y 0, scale 1 — the press
            // squash ends at 1 anyway, so a recycled view never
            // inherits a squashed scale either). This never breaks
            // the current position's own motion: the open pour, the
            // close cascade and the mark fades are all applied to
            // children AFTER bind (posted runnables), never during
            // it, and the title's own pulse animator (v2.27) lives
            // on the title view, not the row, so it is untouched.
            // Visual only; marks, colours and font sizes below are
            // untouched.
            v.animate().cancel();
            Fx.cancelPress(v); // v3.90: press squash is an ObjectAnimator now — the cancel above cannot stop it.
            v.setAlpha(1f);
            v.setTranslationX(0f);
            v.setTranslationY(0f);
            v.setScaleX(1f);
            v.setScaleY(1f);
            // v7.12: foreign queue row — a queued talk of ANOTHER
            // sayadaw, visible at last in the queue-only view
            // (before this it had no row anywhere: only the Up
            // Next header line named it, and only when it was
            // the head). Same row_room_talk layout, no new view:
            // the index column is its raw queue position (like
            // the in-list queue rows below), and the title is
            // its stored title + " · " + its sayadaw — the
            // sayadaw name is what marks it foreign, since an
            // in-list row never carries one. Marks reuse the
            // same URL-based sources as the in-list bind (★/⬇/✓
            // are global by URL, ⏳ likewise, ↺ from the saved
            // position), so a foreign row agrees with every
            // other surface that names the talk; it can never
            // be the playing row or the ▸ next row, so those
            // two marks are skipped (v7.15: the held ✋ mark IS
            // shown now — a foreign row can be picked up).
            // Tap plays
            // it via the v7.08 handover (see the click handler),
            // long-press offers share / single-remove
            // (see the long-press handler, v7.14).
            if (drawerQueueOnly && !drawerSearching()) {
                QueueEntry fe = drawerForeignEntry(pos);
                if (fe != null) {
                    if (roomPulseView == v.findViewById(R.id.talkTitle)) {
                        if (roomPulse != null) roomPulse.cancel();
                        roomPulse = null;
                        roomPulseSettling = false;
                        roomPulseView = null;
                    }
                    // v13.43: row views are looked up per bind — a missing row id skips the bind, never kills the list.
                    TextView fTitle = v.findViewById(R.id.talkTitle);
                    if (fTitle != null) { fTitle.animate().cancel(); fTitle.setAlpha(1f); }
                    int tf = Prefs.fontSize(RoomActivity.this);
                    TextView fIdx = (TextView) v.findViewById(R.id.index);
                    if (fIdx != null) fIdx.setText(
                            (queueLineIndexForUrl(fe.url) + 1) + ".");
                    if (fIdx != null) fIdx.setTextSize(
                            android.util.TypedValue.COMPLEX_UNIT_SP,
                            tf == 0 ? 12 : (tf == 1 ? 14 : 16));
                    if (fTitle != null) fTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                            tf == 0 ? 14 : (tf == 1 ? 16 : 20));
                    if (playedSet == null || favSet == null) refreshMarkSets();
                    boolean fPlayed = playedSet.contains(fe.url);
                    boolean fDownloaded = isDownloadedFile(fe.url);
                    boolean fFav = favSet.contains(fe.url);
                    String fQueueMark = "";
                    if (!fDownloaded && queuedDownloads.contains(fe.url)) {
                        Integer pct = dlProgress.get(fe.url);
                        fQueueMark = pct != null ? "⏳ " + pct + "% " : "⏳ ";
                    }
                    // v11.36: a sounding foreign row IS the playing row —
                    // the v7.12 bind comment ("it can never be the
                    // playing row") predates the v11.32 foreign
                    // mirror: this Room can be reopened (or
                    // returned to) while its queued foreign talk
                    // still sounds in its own Room's session, and
                    // the old bind then showed it as an ordinary
                    // row — ✓/dim when already heard, a ↺ resume
                    // mark, a ◈ queue number — with no ⏸ or gold
                    // to say it is the talk sounding now. The
                    // mirror covers both windows (bound it IS
                    // isCurrentTalk, any session kind; unbound
                    // it mirrors a live room session of the
                    // row's owner), and the ended guard mirrors
                    // the in-list rowPlaying116 rule below, so an
                    // ended talk never reads as playing. Marks
                    // follow the in-list playing row exactly: ⏸
                    // + gold, no ◈ (the index column already
                    // carries the queue position), no ✓/dim,
                    // no ↺ — the live position belongs to the
                    // Room clocks, not the row.
                    boolean fSounding = isSoundingForeignTalk(fe.url, fe.sayadaw)
                            && !AudioService.liveEnded;
                    String fResume = "";
                    int frp = Prefs.talkPosGet(RoomActivity.this, fe.url);
                    if (frp <= 5000 && fDownloaded) {
                        String dlName = downloadedFileName(fe.url);
                        if (dlName != null)
                            frp = Prefs.talkPosGet(RoomActivity.this,
                                    downloadsFile(dlName).getAbsolutePath());
                    }
                    if (frp > 5000 && !fSounding) fResume = " ↺ " + formatTime(frp);
                    String fHand = "";
                    int fqpos = queueLineIndexForUrl(fe.url);
                    if (fqpos >= 0 && !fSounding) fHand = "◈" + (fqpos + 1) + " ";
                    // v7.15: a held foreign row reads ✋ + the gold
                    // wash, like a held in-list row — the arm can
                    // hold a foreign URL now, so the bind's own
                    // queue-membership guard is the line-aware
                    // check, not a raw contains. The background
                    // was reset to TRANSPARENT at the top of this
                    // bind, so the wash can never leak onto a
                    // recycled row.
                    boolean fHeld = moveHeldUrl != null && moveHeldUrl.equals(fe.url)
                            && queueLineIndexForUrl(fe.url) >= 0;
                    if (fTitle != null) fTitle.setText((fHeld ? "✋ " : "") + (fSounding ? "⏸ " : "") + fHand + (fFav ? "★ " : "")
                            + (fDownloaded ? "⬇ " : "") + fQueueMark
                            + (fPlayed && !fSounding ? "✓ " : "")
                            + (fe.title.length() > 0 ? fe.title : fe.url)
                            + fResume + " · " + fe.sayadaw);
                    if (fTitle != null) fTitle.setTextColor(fSounding
                            ? android.graphics.Color.parseColor("#C9A227")
                            : android.graphics.Color.WHITE);
                    // v12.92: raised row tile — gold tile for the sounding row, glass tile otherwise; set on every bind so a recycled view never keeps a stale tile.
                    android.view.View fRowCard = v.findViewById(R.id.rowCard);
                    if (fRowCard != null) fRowCard.setBackgroundResource(fSounding ? R.drawable.drawer_row_playing : R.drawable.drawer_row);
                    if (fHeld) v.setBackgroundColor(0x33C9A227);
                    if (fPlayed && !fSounding && fTitle != null) fTitle.setAlpha(0.6f);
                    return v;
                }
            }
            // v1.51: the row layout no longer carries star/download
            // buttons at all (v1.50 declutter) — the ★/⬇ marks in the
            // title text below are the only indicators left, so there
            // is nothing left to hide here.
            // v4.55: queue-only display mapping — pos is a DISPLAY position
            // here, not a talk index: in queue-only view the rows
            // run in queue order, so every identity check below
            // uses talkIdx. A stale display slot (queue URL gone
            // from the list) keeps its old content instead of
            // crashing — getCount already skipped it, so this only
            // fires on a mid-rebind race. The row number is the
            // queue position in queue-only view, the list number
            // otherwise — both equal ◈N for a queued row.
            final int talkIdx = drawerTalkIndex(pos);
            if (talkIdx < 0 || talkIdx >= talks.size()) return v;
            Talk t = talks.get(talkIdx);
            // v7.71: this bind's playing identity is the scan-proven
            // sounding index (see soundingIndexForBind), not
            // currentIndex alone — every talkIdx == currentIndex
            // comparison below reads playIdx71 instead.
            final int playIdx71 = soundingIndexForBind();
            // v7.38: line-aware queue number — a v2-spelled in-list
            // row used to read "0." in queue-only view.
            TextView idxView = (TextView) v.findViewById(R.id.index);
            if (idxView != null) idxView.setText((drawerQueueOnly ? (queueLineIndexForUrl(t.url) + 1) : (talkIdx + 1)) + ".");
            // v1.97: the Room rows never followed the font size for
            // the number (the Talks rows have since v1.96, the Room
            // title since v1.53) — on ကြီး the titles grew but the
            // numbers sat at the layout's 14sp. Same 12/14/16 steps.
            // v2.03: one fontSize read per bind, not two — the number
            // and the title above each read it separately.
            int tf = Prefs.fontSize(RoomActivity.this);
            if (idxView != null) idxView.setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_SP, tf == 0 ? 12 : (tf == 1 ? 14 : 16));
            TextView titleView = v.findViewById(R.id.talkTitle);
            // v2.27: a pulsing title view recycled to another row
            // must stop at once, not wait for the next 500ms poll
            // tick — the poll re-arms the pulse on the true playing
            // row if it is still visible.
            if (titleView != null && roomPulseView == titleView && talkIdx != playIdx71) {
                if (roomPulse != null) roomPulse.cancel();
                roomPulse = null;
                // v3.17: same flag clear as the pause branch — a
                // settle on this title ends here with the view, so
                // its end action must not start a loop later.
                roomPulseSettling = false;
                roomPulseView = null;
                titleView.setAlpha(1f);
            }
            // v3.46: premium drawer recycled-title fade hygiene,
            // extending the delivered v3.45 row hygiene to the
            // channel it explicitly left untouched. The row cancel
            // above never touches the title view, and the pulse
            // branch above only handles a title the poll still
            // tracks (roomPulseView): after a track handover the
            // OLD title's stop-fade (v3.13, 200ms to alpha 1) runs
            // untracked — roomPulseView already points at the new
            // title — so a view recycled mid-fade (scroll inside
            // that 200ms window) used to carry the fade onto the
            // new talk: a static non-playing title faded in late
            // on content that never asked for it, exactly the
            // recycled-animation class v3.40/v3.45 fixed on the
            // row channel. A rebind to a non-playing row now
            // cancels any stale title animator and restores
            // alpha 1. This never breaks the playing row's own
            // motion: talkIdx == playIdx71 is skipped, so the
            // poll's pulse (v2.27), settle (v3.17) and stop-fade
            // (v3.13) keep owning that title, and a settle
            // cancelled here already had its flag/view cleared
            // above, so its end action returns without starting
            // a loop. The stop-fade carries no end action, so
            // cancelling it is clean. Visual only; the row
            // motion, marks, colours and font sizes are untouched.
            // v7.71: playing identity here is playIdx71 too (see above).
            if (talkIdx != playIdx71 && titleView != null) {
                titleView.animate().cancel();
                titleView.setAlpha(1f);
            }
            // (talk resolved above, v4.55)
            // v1.53: Room rows follow the Settings font size, like the
            // Talks rows since v1.26 — large text used to enlarge the
            // chooser but leave the Room list at 15sp.
            if (titleView != null) titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tf == 0 ? 14 : (tf == 1 ? 16 : 20));
            if (playedSet == null || favSet == null) refreshMarkSets();
            boolean played = playedSet.contains(t.url);
            // v1.82: same candidate names as localPathFor — a ⬇ mark
            // used to need the raw basename to match, so a talk
            // downloaded under its clean name showed no mark at all.
            boolean downloaded = isDownloadedFile(t.url);
            // v1.44: favourite talks show ★ in the Room list too, so a
            // listener can spot them without going back to the chooser
            // (the Room has no favourite button by the v1.37 order —
            // this is a mark only, not a new control).
            boolean fav = favSet.contains(t.url);
            // v2.47: current-row mark follows the action rule (playing = ⏸)
            // v4.01: queued-download mark — a talk waiting for Wi-Fi
            // or mid-download reads ⏳ until the file lands and the
            // ⬇ mark (v3.08 fade) replaces it; never beside ⬇.
            boolean queued = !downloaded && queuedDownloads.contains(t.url);
            // v4.24: a queued row's ⏳ carries its live percent
            // ("⏳ 42% ") from the rescan's DownloadManager read,
            // so a long download reads its progress without opening
            // the system Downloads app; unknown total stays a bare ⏳.
            String queueMark = "";
            if (queued) {
                Integer pct = dlProgress.get(t.url);
                queueMark = pct != null ? "⏳ " + pct + "% " : "⏳ ";
            }
            // v4.32: queued rows carry their queue position ("◈1 ")
            // so the hand queue reads in the list itself, not just
            // the header count; the playing row never shows one.
            // v5.82: the position skips the talk now playing —
            // the header (v5.81), strips (v5.80) and card (v5.43)
            // already count waiting talks only, but this mark
            // numbered the raw queue, so a service-restart play
            // still saved in the queue pushed every later mark
            // one too high (next talk read ◈2 against a "· ◈ 1"
            // header). The playing identity is this bind's own
            // (the scan-proven sounding talk, playIdx71 since
            // v7.71 — currentIndex alone still named the finished
            // talk in the pre-poll-tick window), so a normal play (already
            // popped) changes nothing; queue-only view keeps the
            // raw number, so it still equals the index column.
            String handMark = "";
            // v7.38: line-aware ◈, like the v7.15 drop handlers — a
            // v2-spelled in-list row used to lose its mark here.
            int qpos = (talkIdx == playIdx71) ? -1 : queueLineIndexForUrl(t.url);
            if (qpos >= 0) {
                int shown = qpos + 1;
                if (!drawerQueueOnly && playIdx71 >= 0 && playIdx71 < talks.size()) {
                    int playPos = queueLineIndexForUrl(talks.get(playIdx71).url);
                    if (playPos >= 0 && playPos < qpos) shown--;
                }
                handMark = "◈" + shown + " ";
            }
            // v4.92: held-row mark — the picked-up talk reads ✋
            // ahead of its ◈ number plus a gold wash below, and
            // only while it is still queued: a queue cleared or
            // reordered from another menu drops the mark on the
            // next rebind instead of highlighting a stale row.
            // The background is reset to TRANSPARENT at the top of
            // every bind, so the wash can never leak onto a
            // recycled row.
            boolean held = moveHeldUrl != null && moveHeldUrl.equals(t.url) && queueLineIndexForUrl(t.url) >= 0; // v7.38: line-aware, like the foreign-row bind above
            // v5.34: per-talk resume mark — a half-heard row reads
            // "↺ mm:ss" after its title, so the listener can spot
            // where they stopped without opening the talk. Prefs
            // only, never the filesystem: this bind runs on the
            // poll, the v1.57 jank class. Skipped for the playing
            // row, whose live position the Room clocks already show.
            // v5.59: path-form fallback — the stream-form lookup
            // above misses a talk half-heard as a downloaded file
            // whose spot lives under the file path only (v5.36:
            // no dlMap entry, so talkPosPutBoth never wrote the
            // stream form), yet tapping it still resumes via
            // savedPosFor's localPathFor — so the row read as
            // unheard while the tap resumed mid-talk. When this
            // row is already known-downloaded (the cache boolean
            // above), the matched filename gives the path form
            // from the cache alone, no bind-time filesystem work.
            String resumeMark = "";
            if (talkIdx != playIdx71) {
                int rp = Prefs.talkPosGet(RoomActivity.this, t.url);
                if (rp <= 5000 && downloaded) {
                    String dlName = downloadedFileName(t.url);
                    if (dlName != null)
                        rp = Prefs.talkPosGet(RoomActivity.this,
                                downloadsFile(dlName).getAbsolutePath());
                }
                if (rp > 5000) resumeMark = " ↺ " + formatTime(rp);
            }
            // v11.16: an ended talk is not playing — the v11.15
            // class on the fourth surface: this bind marked the
            // ended talk ⏸ + gold (and suppressed its ✓/dim)
            // because soundingIndexForBind still names it
            // after a natural end. Only the mark, the gold
            // below and the ✓/dim suppression follow the
            // ended state; pulse, ◈, ▸, ✋ and ↺ keep
            // playIdx71 untouched, as v11.15 did elsewhere.
            boolean rowPlaying116 = talkIdx == playIdx71 && !AudioService.liveEnded;
            if (titleView != null) titleView.setText((held ? "✋ " : "") + (rowPlaying116 ? "⏸ " : "") + handMark + (fav ? "★ " : "") + (downloaded ? "⬇ " : "") + queueMark + (played && !rowPlaying116 ? "✓ " : "") + t.title + resumeMark);
            if (titleView != null) titleView.setTextColor(rowPlaying116
                    ? android.graphics.Color.parseColor("#C9A227")
                    : android.graphics.Color.WHITE);
            // v12.92: raised row tile — gold tile for the playing row, glass tile otherwise; set on every bind so a recycled view never keeps a stale tile.
            android.view.View rowCard = v.findViewById(R.id.rowCard);
            if (rowCard != null) rowCard.setBackgroundResource(rowPlaying116 ? R.drawable.drawer_row_playing : R.drawable.drawer_row);
            // v4.92: held-row wash (see the ✋ mark above).
            if (held) v.setBackgroundColor(0x33C9A227);
            // v4.12: listened talks sit dimmer in the queue (Spotify
            // queue pattern: heard = quieter, unheard = full white),
            // so the next unheard talk stands out at a glance. The
            // playing row is never dimmed — its pulse owns its
            // alpha — and the ✓ mark stays, so this adds a second
            // cue, never a replacement; a recycled row rebinds its
            // alpha here, so a dim never leaks onto another talk.
            if (played && !rowPlaying116 && titleView != null) titleView.setAlpha(0.6f);
            // v4.13: Up Next row mark — the row after the playing
            // one (wrapping like a Next tap) carries a ▸ prefix at
            // full alpha, even when already listened: it is the
            // imminent talk, so the listened dim must not bury it.
            // Mark-only, like the ✓/★/⬇/⏳ marks above; the playing
            // row's pulse owns its own alpha and is never touched
            // (next can never equal current when size > 1).
            // v11.60: no ▸ when a foreign queue head waits — the
            // Next tap (v7.08 handover), the header Up Next line
            // (v8.62) and the Next long-press preview (v8.63) all
            // name the foreign head first, but this mark read
            // peekNextIndex alone, so with a foreign head queued
            // it crowned the in-list talk that would play only
            // AFTER the handover — the row a Next tap would not
            // play. A foreign head has no in-list row to crown
            // (its drawer row carries talkIdx -1), so the mark
            // simply stays off until the head is consumed, exactly
            // as Up Next already reads. Normal queues unchanged:
            // firstWaitingForeignEntry is null and peek rules.
            if (titleView != null && playIdx71 >= 0 && playIdx71 < talks.size() && talks.size() > 1
                    && firstWaitingForeignEntry() == null
                    && talkIdx == peekNextIndex()) { // v4.32: ▸ follows the queued head, not the list-next
                titleView.setText("▸ " + titleView.getText().toString());
                titleView.setAlpha(1f);
            }
            return v;
        }
    }

    // v1.58: talk titles used to show raw entities (&quot;, &#123;)
    // — only &amp; and &nbsp; were decoded. Decode the named and
    // numeric entities too, so titles read as written on the site.
    private static String cleanTitle(String raw) {
        String title = raw.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'")
                // v1.90: named entities beyond the five hardcoded ones
                // used to render literally in titles (&rsquo; etc. on
                // this old site) — map the common punctuation ones
                // before the numeric decode and the final &amp; pass.
                .replace("&rsquo;", "'").replace("&lsquo;", "'")
                .replace("&rdquo;", "\"").replace("&ldquo;", "\"")
                .replace("&mdash;", "—").replace("&ndash;", "–")
                .replace("&hellip;", "…");
        try {
            Matcher nm = Pattern.compile("&#(\\d+);|&#x([0-9a-fA-F]+);").matcher(title);
            StringBuffer sb = new StringBuffer();
            while (nm.find()) {
                // v1.95: one bad code used to kill the whole decode
                // — an overflowing number (&#9999999999;) throws in
                // parseInt and the outer catch dropped every other
                // &#...; in the title too, like the v1.79 surrogate
                // case. Parse per code, so only that code is lost.
                String rep = "";
                try {
                    int code = nm.group(1) != null
                            ? Integer.parseInt(nm.group(1))
                            : Integer.parseInt(nm.group(2), 16);
                    // v1.79: a surrogate code point (&#55296; etc) used
                    // to throw inside toChars and drop the WHOLE entity
                    // decode — the title kept every raw &#...; code.
                    // Skip just that code; the rest still decode.
                    rep = (code > 0 && code <= 0x10FFFF
                            && (code < 0xD800 || code > 0xDFFF))
                            ? new String(Character.toChars(code)) : "";
                } catch (Exception ignored) {}
                nm.appendReplacement(sb, Matcher.quoteReplacement(rep));
            }
            nm.appendTail(sb);
            title = sb.toString();
        } catch (Exception ignored) {}
        title = title.replace("&amp;", "&");
        return title.replaceAll("\\s+", " ").trim();
    }

    // v1.81: %XX-decode a filename for use as a fallback title —
    // only %XX sequences are touched, so a literal '+' stays '+'
    // (URLDecoder would wrongly turn it into a space).
    private static String decodePct(String s) {
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < s.length();) {
                char c = s.charAt(i);
                if (c == '%' && i + 2 < s.length()) {
                    int hi = Character.digit(s.charAt(i + 1), 16);
                    int lo = Character.digit(s.charAt(i + 2), 16);
                    if (hi >= 0 && lo >= 0) {
                        baos.write(hi * 16 + lo);
                        i += 3;
                        continue;
                    }
                }
                baos.write(String.valueOf(c).getBytes("UTF-8"));
                i++;
            }
            return new String(baos.toByteArray(), "UTF-8");
        } catch (Exception ignored) { return s; }
    }

    // v2.78: premium retry fade-out — the mirror of the v2.61
    // failure fade-in. The Room retry used to snap GONE the instant
    // a new load started (a retry tap or a sayadaw switch), a hard
    // pop while its appearance already fades in (v2.61) and every
    // other Room change already moves. A visible retry now fades
    // to 0 over 200ms (decelerate, alpha-only) and only then goes
    // GONE at full alpha, so a later show starts clean. An
    // already-hidden button stays hidden with no animation (the
    // layout default is GONE, so a fresh open never fades anything
    // in or out). Alpha runs on its own ObjectAnimator, not the
    // ViewPropertyAnimator channel, so the v2.28 press squash's
    // scale spring-back on the same button runs untouched beside
    // it, and a failure arriving mid-fade cancels this animator
    // (see the show branches) instead of fighting it. Visual only;
    // text, tap and load behaviour untouched.
    private void hideRetry() {
        if (btnRetry == null) return;
        if (retryFade != null) { retryFade.cancel(); retryFade = null; retryFadeHiding = false; }
        if (btnRetry.getVisibility() != View.VISIBLE) {
            btnRetry.animate().cancel();
            btnRetry.setAlpha(1f);
            btnRetry.setVisibility(View.GONE);
            return;
        }
        retryFadeHiding = true;
        retryFade = android.animation.ObjectAnimator.ofFloat(btnRetry, "alpha", btnRetry.getAlpha(), 0f);
        retryFade.setDuration(200);
        retryFade.setInterpolator(new android.view.animation.DecelerateInterpolator());
        // v3.64: premium retry-hide restart guard — the same
        // unguarded hide end action as TalksActivity's
        // hideTalksRetry, while the Room show branches already
        // guard theirs on identity (brFade/br2Fade below): a
        // new failure landing inside this 200ms fade cancels
        // the hide, and under the v3.19 behaviour the cancelled
        // hide's end action then hid the button the show had
        // just revealed (GONE + alpha 1 underneath a fresh
        // fade-in) — the same hole v3.62 closed for Fx.fadeOut
        // and v3.63 closed for themeDip. Capturing this fade
        // and returning unless the field still holds it closes
        // it; natural completion is unchanged (field still
        // matches, the button still goes GONE at full alpha).
        // Visual only.
        final android.animation.ObjectAnimator hideFade = retryFade;
        retryFade.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (retryFade != hideFade) return;
                retryFade = null; retryFadeHiding = false;
                btnRetry.setVisibility(View.GONE);
                btnRetry.setAlpha(1f);
            }
        });
        retryFade.start();
    }

    private void loadTalks(boolean pourLoading) {
        final int mySeq = ++loadSeq;
        // v11.90: capture the page for THIS load — the worker thread used to read the
        // pageUrl field, so a second load starting (retry / sayadaw switch) before the
        // first thread opened its connection made the stale thread fetch the NEW page
        // (wasted duplicate download; the seq guard discarded its result, so no wrong
        // list, just wasted bandwidth).
        final String loadPageUrl = pageUrl;
        hideRetry();
        if (nowPlaying != null) nowPlaying.setText(Lang.t(RoomActivity.this, "talks_loading"));
        // v2.68: premium load-start loading-line pour — the
        // mirror of the v2.66 count pour and v2.67 failure/empty
        // pours, closing the last status-line gap: a retry tap
        // or a sayadaw switch used to swap the old line for this
        // loading line in one frame, while every other status
        // line already pours. The loading line now fades + rises
        // in with the same rhythm (320ms, 12dp rise, decelerate,
        // ending at exactly alpha 1 / offset 0). The fresh-open
        // load passes false — it already pours via the v2.15
        // panel entrance, and the two must never double-animate.
        // No new lifecycle code: the v2.66 onPause/onDestroy
        // reset already cancels this same view's animator, the
        // poll never overwrites this line (v1.57/v1.80 guards —
        // it starts with "တရားစာရင်း ", not ▶/⏸/ဆွဲထုတ်နေသည်…),
        // and the v2.66/v2.67 completion pour supersedes it by
        // cancelling this animator. Visual only; text and load
        // behaviour untouched.
        if (pourLoading && nowPlaying != null) {
            // v3.49: pour-restart continuity (v3.37 rule, extended) — a settled
            // view still pours visibly from 0/12dp; a mid-pour view flows on
            // from its current alpha/offset instead of snapping back.
            nowPlaying.animate().cancel();
            if (nowPlaying.getAlpha() >= 0.999f && nowPlaying.getTranslationY() <= 0.5f) {
                nowPlaying.setAlpha(0f);
                nowPlaying.setTranslationY(12f * getResources().getDisplayMetrics().density);
            }
            nowPlaying.animate().alpha(1f).translationY(0f).setDuration(320)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Talk> found = new ArrayList<Talk>();
                String err = null;
                // v1.58: disconnect in a finally — v1.57 only released
                // the connection on the success path, so a failed load
                // (e.g. getInputStream throwing) still leaked its socket.
                HttpURLConnection conn = null;
                InputStream in = null;
                try {
                    conn = (HttpURLConnection) new URL(loadPageUrl).openConnection();
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                    in = conn.getInputStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
                    byte[] bytes = baos.toByteArray();
                    String html = new String(bytes, "UTF-8");
                    // v1.87: same quoted-charset miss as the Talks
                    // chooser — charset="utf-8" never matched the old
                    // pattern, so a non-UTF-8 page kept garbled titles.
                    Matcher cm = Pattern.compile("charset=[\"']?([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(html);
                    if (cm.find()) {
                        try { html = new String(bytes, cm.group(1)); } catch (Exception ignored) {}
                    }
                    URL base = new URL(loadPageUrl);
                    // v1.88: single-quoted hrefs too — the old pattern
                    // required double quotes, so a sayadaw page
                    // written with href='...mp3' came back as an empty
                    // list with a retry button instead of its talks.
                    // v1.90: unquoted hrefs too — valid HTML and
                    // common on this old site, but the old pattern
                    // required quotes, so such a page came back as
                    // an empty list with a retry that could never
                    // succeed. Groups 1-3 are the quoted/unquoted
                    // href alternatives, group 4 the link text.
                    Pattern pat = Pattern.compile("<a[^>]*href\\s*=\\s*(?:\"([^\"]*\\.mp3[^\"]*)\"|'([^']*\\.mp3[^']*)'|([^\\s>]*\\.mp3[^\\s>]*))[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                    Matcher m = pat.matcher(html);
                    Map<String, String> seen = new LinkedHashMap<String, String>();
                    // v12.87: URLs whose stored title is the filename fallback (see below).
                    java.util.Set<String> seenFallback = new java.util.HashSet<String>();
                    while (m.find()) {
                        String href = m.group(1) != null ? m.group(1)
                                : (m.group(2) != null ? m.group(2) : m.group(3));
                        href = href.trim();
                        // v11.83: a line-wrapped href (newline/tab
                        // inside the quoted value) would poison every
                        // newline-joined store the URL enters (hand
                        // queue, handover map, trails) — the fragments
                        // fail every restore filter and the talk
                        // silently drops. Skip it, like any
                        // unparseable href.
                        if (href.indexOf('\n') >= 0 || href.indexOf('\r') >= 0
                                || href.indexOf('\t') >= 0) continue;
                        String title = cleanTitle(m.group(4));
                        // v12.87: remember whether this title is the filename
                        // fallback, so a later duplicate link with a real title
                        // can replace it (see the dedup below).
                        boolean titleFallback = title.length() == 0;
                        // v1.81: same fallback-title fix as the Talks
                        // chooser — decode %XX so a text-less link
                        // reads as a name, not a raw filename.
                        if (title.length() == 0) title = decodePct(href.substring(href.lastIndexOf('/') + 1));
                        // v1.78: same bad-link class as the Talks
                        // chooser — one illegal href threw out of
                        // this loop into the outer catch and failed
                        // the whole Room list. Skip just that link.
                        // v1.80: but two common href shapes are not
                        // bad links at all — a literal space in a
                        // filename (URL() throws on it, so the talk
                        // silently vanished from the list) and an
                        // &amp; escaped ampersand (an HTML attribute
                        // escape, so the raw href 404s). Normalise
                        // both before resolving; truly illegal hrefs
                        // still skip below.
                        String abs;
                        try {
                            abs = new URL(base, href.replace("&amp;", "&")
                                    .replace(" ", "%20")).toString();
                        }
                        catch (Exception ignored) { continue; }
                        // v12.87: a duplicate URL keeps the first title — unless
                        // the first was the filename fallback and this one is a
                        // real title, which replaces it (a page listing the same
                        // mp3 first as a bare link, later with its real title,
                        // used to keep the filename as the row title).
                        if (!seen.containsKey(abs)) {
                            seen.put(abs, title);
                            if (titleFallback) seenFallback.add(abs);
                        } else if (!titleFallback && seenFallback.contains(abs)) {
                            seen.put(abs, title);
                            seenFallback.remove(abs);
                        }
                    }
                    for (Map.Entry<String, String> e : seen.entrySet()) found.add(new Talk(e.getValue(), e.getKey()));
                } catch (Exception e) {
                    // v1.92: same null-message class as the Talks
                    // chooser — a null message used to leave err
                    // null, so the status line fell through to
                    // "no talks found" for a load that had actually
                    // failed. A failure always carries a message now.
                    err = e.getMessage();
                    if (err == null || err.length() == 0) err = Lang.t(RoomActivity.this, "internet_error");
                } finally {
                    // v1.66: same stream leak as the Talks loader —
                    // close the stream in the finally, not only on
                    // the success path (see TalksActivity v1.66).
                    if (in != null) { try { in.close(); } catch (Exception ignored) {} }
                    if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
                }
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // v1.63: same dead-screen guard as the Talks
                        // chooser — a load finishing after back-press
                        // used to rebuild this dead Room's list.
                        if (isFinishing() || isDestroyed()) return;
                        // v1.83: a stale load (see loadSeq) must not
                        // overwrite a newer load's list or re-fire its
                        // auto-talk / pending-Next handling.
                        if (mySeq != loadSeq) return;
                        talks.clear();
                        talks.addAll(found);
                        refreshDownloadedSet();
                        refreshMarkSets();
                        adapter.notifyDataSetChanged();
                        syncDrawerSearchEmpty(); // v4.98: a reload under an active search re-decides the no-hit line
                        // v8.10: fresh-Room history decision (open from the v8.09 note) — drain,
                        // do not leave empty. A Room created AFTER hidden advances (process
                        // death, Back-then-reopen, mini-player / notification tap into a new
                        // instance) starts with an empty prevHistory, and its load completion
                        // never runs a return corrector, so the trail the service recorded
                        // for this list was never claimed: shuffle Previous stepped to the
                        // list-previous talk instead of the talk actually heard before this
                        // one. A clean, non-empty load now drains the trail when this Room
                        // rejoins the sounding session (service bound, sounding talk in this
                        // list — stream or offline form via isCurrentTalk). The drain runs
                        // BEFORE the autoUrl block below, so a tapped talk's playIndex push
                        // lands ON TOP of the drained trail in true heard order (trail,
                        // sounding talk, tapped talk); draining after it would have reversed
                        // them. Gated, not blanket: a Room opened to browse another sayadaw
                        // while a different sayadaw sounds leaves the trail untouched for
                        // that sayadaw's own Room, and a failed/empty load drains nothing
                        // (talkIndexForUrl would claim nothing anyway). Idempotent by
                        // construction — claimed entries leave the pref, so a reload or a
                        // later sayadaw switch in this instance drains only what a new list
                        // still names; stale old-list entries already in prevHistory are
                        // pruned lazily by handlePrev, as before. The return correctors'
                        // drain is untouched — this only covers the creation window they
                        // cannot see.
                        if (ferr == null && !found.isEmpty() && service != null && service.hasTrack()) {
                            int snd10 = -1;
                            for (int si10 = 0; si10 < talks.size(); si10++)
                                if (isCurrentTalk(talks.get(si10).url)) { snd10 = si10; break; }
                            if (snd10 >= 0) drainHiddenTrail08(talks.get(snd10).url);
                        }
                        // v4.37: restore the saved hand queue for
                        // this sayadaw (fresh load only — rotation
                        // restores from the bundle and never reaches
                        // this load completion; an empty queue or a
                        // different sayadaw restores nothing).
                        restoreSavedQueue();
                        // v12.01 R1: sync the hand queue on load completion too — the
                        // v12.00 audit's AMBIGUOUS-2, confirmed: a Room whose list load
                        // failed (talks empty) restores saved queue Q1 here, the user
                        // edits the queue to Q2 in Talks, returns (onResume sync
                        // early-returns on the empty list), taps Retry and the load
                        // succeeds — but restoreSavedQueue is a no-op (Q1 non-empty)
                        // and nothing ever re-syncs, so a drawer edit or Next/Play
                        // then writes the stale Q1 list and silently clobbers Q2.
                        // syncSavedQueueOnReturn is a read-only adopt (no prefs
                        // write) whose empty-list guard makes it a no-op while the
                        // list is still loading, so it is safe on every load path.
                        syncSavedQueueOnReturn();
                        if (ferr != null) {
                            if (nowPlaying != null) {
                                nowPlaying.setText(Lang.t(RoomActivity.this, "fetch_failed_prefix") + ferr);
                                // v2.67: premium load-failure status pour — the
                                // mirror of the v2.66 count pour. A failed load
                                // used to swap the loading line for this error
                                // line in one frame, while the success count
                                // line already pours (v2.66); the error line now
                                // fades + rises in with the same rhythm (320ms,
                                // 12dp rise, decelerate, ending at exactly alpha 1
                                // / offset 0). No new lifecycle code: the v2.66
                                // onPause/onDestroy reset already cancels this
                                // same view's animator, and the poll never
                                // overwrites an error line (v1.57 guard — it
                                // does not start with ▶/⏸/ဆွဲထုတ်နေသည်…).
                                // Visual only; text, retry fade and toasts
                                // are untouched.
                                // v3.49: pour-restart continuity (v3.37 rule, extended) — a settled
                                // view still pours visibly from 0/12dp; a mid-pour view flows on
                                // from its current alpha/offset instead of snapping back.
                                nowPlaying.animate().cancel();
                                if (nowPlaying.getAlpha() >= 0.999f && nowPlaying.getTranslationY() <= 0.5f) {
                                    nowPlaying.setAlpha(0f);
                                    nowPlaying.setTranslationY(12f * getResources().getDisplayMetrics().density);
                                }
                                nowPlaying.animate().alpha(1f).translationY(0f).setDuration(320)
                                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                            }
                            Toast.makeText(RoomActivity.this, Lang.t(RoomActivity.this, "talk_list_fetch_failed"), Toast.LENGTH_LONG).show();
                            // v2.61: premium retry-button fade — the
                            // mirror of the v2.60 Talks fade. The Room
                            // retry used to snap VISIBLE the instant a
                            // load failed or came back empty, a hard
                            // pop beside the failure toast while every
                            // other Room change already moves (v2.07
                            // play morph + glow, v2.09 sheet, v2.15
                            // panel entrance, v2.28 retry press). A
                            // GONE -> VISIBLE retry now fades in with
                            // the existing Fx.fadeIn (250ms,
                            // decelerate, alpha-only), so the failure
                            // lands softly. An already-visible button
                            // just resets to full alpha (no flicker on
                            // a repeated failure), and loadTalks hides
                            // it with the fade cancelled above, so a
                            // retry tap mid-fade never strands it.
                            // Visual only; text, tap and the failure
                            // toasts are untouched.
                            if (btnRetry != null) {
                                // v2.78: a failure landing mid-fade-out
                                // cancels that animator first — without
                                // this it would finish the fade and set
                                // GONE under the freshly shown button.
                                if (retryFade != null) { retryFade.cancel(); retryFade = null; retryFadeHiding = false; }
                                if (btnRetry.getVisibility() != View.VISIBLE) {
                                    btnRetry.setVisibility(View.VISIBLE);
                                    Fx.fadeIn(btnRetry, 250);
                                } else if (btnRetry.getAlpha() < 0.999f) {
                                    // v3.53: premium retry re-appearance continuity, the Room mirror
                                    // of the Talks fix in the same version — the v2.78 cancel above
                                    // left the button half-faded (a failure landing inside the
                                    // 200ms fade-out), and this branch then snapped it straight to
                                    // alpha 1 in one frame, the same snap class v3.52 fixed for
                                    // Fx.fadeIn, but on this button's own ObjectAnimator alpha
                                    // channel, so the v2.28 press squash's scale spring-back beside
                                    // it is untouched. A mid-fade button now glides back to 1 over
                                    // 250ms (decelerate); the fade-in reuses retryFade, so a new
                                    // load's hide cancels it and fades out from its current alpha,
                                    // and a second failure re-glides from wherever it sits. Its end
                                    // action only nulls the field (guarded on identity), never hides
                                    // or re-alphas, so a cancel can never strand the button. A
                                    // settled button (alpha ~1, a repeated failure) keeps the old
                                    // instant reset below, with no pointless fade.
                                    final android.view.View br = btnRetry;
                                    retryFade = android.animation.ObjectAnimator.ofFloat(br, "alpha", br.getAlpha(), 1f);
                                    retryFade.setDuration(250);
                                    retryFade.setInterpolator(new android.view.animation.DecelerateInterpolator());
                                    final android.animation.ObjectAnimator brFade = retryFade;
                                    retryFade.addListener(new android.animation.AnimatorListenerAdapter() {
                                        @Override public void onAnimationEnd(android.animation.Animator a) {
                                            if (retryFade == brFade) retryFade = null;
                                        }
                                    });
                                    retryFade.start();
                                } else {
                                    btnRetry.animate().cancel();
                                    btnRetry.setAlpha(1f);
                                }
                            }
                        } else if (found.isEmpty()) {
                            if (nowPlaying != null) {
                                nowPlaying.setText(Lang.t(RoomActivity.this, "talks_none_for_sayadaw"));
                                // v2.67: same pour as the failure line above —
                                // an empty load used to swap the loading line
                                // for this line in one frame, while the success
                                // count line already pours (v2.66). Same rhythm,
                                // same v2.66 lifecycle reset, same poll guard.
                                // v3.49: pour-restart continuity (v3.37 rule, extended) — a settled
                                // view still pours visibly from 0/12dp; a mid-pour view flows on
                                // from its current alpha/offset instead of snapping back.
                                nowPlaying.animate().cancel();
                                if (nowPlaying.getAlpha() >= 0.999f && nowPlaying.getTranslationY() <= 0.5f) {
                                    nowPlaying.setAlpha(0f);
                                    nowPlaying.setTranslationY(12f * getResources().getDisplayMetrics().density);
                                }
                                nowPlaying.animate().alpha(1f).translationY(0f).setDuration(320)
                                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                            }
                            if (btnRetry != null) {
                                // v2.78: same mid-fade-out cancel as the
                                // failure branch above.
                                if (retryFade != null) { retryFade.cancel(); retryFade = null; retryFadeHiding = false; }
                                if (btnRetry.getVisibility() != View.VISIBLE) {
                                    btnRetry.setVisibility(View.VISIBLE);
                                    Fx.fadeIn(btnRetry, 250);
                                } else if (btnRetry.getAlpha() < 0.999f) {
                                    // v3.53: same retry re-appearance continuity as the failure
                                    // branch above (empty-load path) — a mid-fade button glides
                                    // back to 1 over 250ms on its own ObjectAnimator alpha channel
                                    // instead of snapping to 1; settled buttons keep the instant
                                    // reset below. See the failure branch for the full guard story.
                                    final android.view.View br2 = btnRetry;
                                    retryFade = android.animation.ObjectAnimator.ofFloat(br2, "alpha", br2.getAlpha(), 1f);
                                    retryFade.setDuration(250);
                                    retryFade.setInterpolator(new android.view.animation.DecelerateInterpolator());
                                    final android.animation.ObjectAnimator br2Fade = retryFade;
                                    retryFade.addListener(new android.animation.AnimatorListenerAdapter() {
                                        @Override public void onAnimationEnd(android.animation.Animator a) {
                                            if (retryFade == br2Fade) retryFade = null;
                                        }
                                    });
                                    retryFade.start();
                                } else {
                                    btnRetry.animate().cancel();
                                    btnRetry.setAlpha(1f);
                                }
                            }
                        } else {
                            if (nowPlaying != null) {
                                nowPlaying.setText(Lang.t(RoomActivity.this, "room_count_prefix") + talks.size() + Lang.t(RoomActivity.this, "room_count_suffix"));
                                // v2.66: premium load-complete count pour — a
                                // successful Room load used to swap the
                                // loading line for this count line in one
                                // frame, while every other Room change
                                // already moves (v2.15 panel entrance,
                                // v2.22 title pour, v2.30/v2.31 row pours,
                                // v2.65 sayadaw-switch header pour). The
                                // count line now fades + rises in with
                                // the talk-change title's own rhythm
                                // (320ms, 12dp rise, decelerate, ending
                                // at exactly alpha 1 / offset 0), so a
                                // finished load lands as one choreographed
                                // change, not a text snap. Guards: an
                                // auto-talk below supersedes it — playIndex
                                // cancels this animator and pours its own
                                // title (same supersede as the v2.15
                                // entrance); a stale load never reaches
                                // here (mySeq check above); onPause/
                                // onDestroy cancel it and reset alpha/
                                // translation like the v2.65 header pour,
                                // so a leave mid-pour never strands a
                                // faded status line. Visual only; text,
                                // count and load behaviour untouched.
                                // v3.49: pour-restart continuity (v3.37 rule, extended) — a settled
                                // view still pours visibly from 0/12dp; a mid-pour view flows on
                                // from its current alpha/offset instead of snapping back.
                                nowPlaying.animate().cancel();
                                if (nowPlaying.getAlpha() >= 0.999f && nowPlaying.getTranslationY() <= 0.5f) {
                                    nowPlaying.setAlpha(0f);
                                    nowPlaying.setTranslationY(12f * getResources().getDisplayMetrics().density);
                                }
                                nowPlaying.animate().alpha(1f).translationY(0f).setDuration(320)
                                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                            }
                        }
                        if (autoUrl != null && autoUrl.length() > 0) {
                            String want = autoUrl;
                            // v10.84: a failed or empty load used to
                            // consume the auto talk (null here) while
                            // suppressing its "not in list" toast, so
                            // a retry that then succeeded never
                            // auto-played the resume/tapped talk —
                            // the listener had to open the drawer and
                            // tap it by hand. Only a clean, non-empty
                            // load consumes it now; a failed/empty
                            // load keeps it for the retry, and the
                            // toast guard below already stays silent
                            // for those loads. Rotation restore still
                            // nulls it separately (list already
                            // loaded there), and a clean load that
                            // no longer contains the talk still
                            // consumes it with its toast.
                            if (ferr == null && !found.isEmpty()) autoUrl = null;
                            // v5.34: this block duplicated savedPosFor's
                            // match (v1.42/v1.79) and so missed the
                            // per-talk spots — one lookup now serves
                            // both, global spot first, per-talk spot
                            // second, same >5s rule either way.
                            int saved = savedPosFor(want);
                            boolean matched = false;
                            for (int i = 0; i < talks.size(); i++) {
                                if (talks.get(i).url.equals(want)) {
                                    // v1.76: re-entering the Room while
                                    // this talk sat PAUSED (or still
                                    // buffering) used to restart it —
                                    // the v1.56 guard only covered a
                                    // talk that was playing, so a
                                    // paused talk replayed from its
                                    // saved spot with a bell and
                                    // started playing on its own.
                                    // Already holding this talk (and
                                    // not a failed one) = just mark
                                    // the row; the poll shows its
                                    // paused/buffering state as-is.
                                    // v11.90: same ended guard as playIndex above — an ended
                                    // auto talk falls through to playIndex(i, saved) below
                                    // and replays from 0 instead of sitting marked and silent.
                                    if (service != null && service.hasTrack()
                                            && !service.hasTrackError()
                                            && !service.hasTrackEnded()
                                            && isCurrentTalk(talks.get(i).url)) {
                                        currentIndex = i;
                                        // v11.85: refresh the last* anchors (see the bind sync
                                        // above) — an already-held talk adopted here kept the
                                        // previous session's anchors.
                                        saveLastTalk(talks.get(i));
                                        adapter.notifyDataSetChanged();
                                        if (roomList != null && roomList.getVisibility() == View.VISIBLE) {
                                            int sp55 = drawerScrollPos(currentIndex);
                                            if (sp55 >= 0) roomList.setSelection(sp55);
                                        }
                                    } else {
                                        playIndex(i, saved);
                                    }
                                    matched = true;
                                    break;
                                }
                            }
                            // v1.42: say so instead of silently doing nothing
                            // when the saved talk left the site's list.
                            // v1.72: but not on a failed or empty load —
                            // the failure already has its own toast and
                            // retry button, and "not in the list" lied
                            // about a talk the network simply never
                            // delivered. Only a cleanly loaded list
                            // that no longer contains the talk earns it.
                            if (!matched && ferr == null && !found.isEmpty()) {
                                Toast.makeText(RoomActivity.this,
                                        Lang.t(RoomActivity.this, "talk_not_in_list"),
                                        Toast.LENGTH_LONG).show();
                                // v12.79: a playlist-originated
                                // auto-play must not strand a playable
                                // remainder on one stale first item
                                // (sim1291 A1/A2) — fall through to
                                // the first queued line whose URL is
                                // in this loaded list and play it from
                                // its saved spot. The toast above
                                // still says the requested talk is
                                // gone. Non-playlist auto-plays keep
                                // the old toast-only rule (see the
                                // autoFromPlaylist field note).
                                // restoreSavedQueue (above) already
                                // dropped this sayadaw's stale lines,
                                // so the first in-list line is the
                                // next playable item; playIndex pops
                                // the queue prefix through it, and
                                // foreign lines after it stay queued
                                // for the service advance.
                                if (autoFromPlaylist) {
                                    for (int qi79 = 0; qi79 < queueUrls.size(); qi79++) {
                                        int ti79 = talkIndexForUrl(queueLineUrl(queueUrls.get(qi79)));
                                        if (ti79 >= 0) {
                                            playIndex(ti79, savedPosFor(talks.get(ti79).url));
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                        if (service != null && service.hasTrack()) {
                            // v1.75: same stale-index clear as the
                            // bind sync above — a track from another
                            // screen must not leave a gold ▶ here.
                            boolean found = false;
                            for (int i = 0; i < talks.size(); i++) {
                                if (isCurrentTalk(talks.get(i).url)) { currentIndex = i; saveLastTalk(talks.get(i)); found = true; break; } // v11.85: refresh the last* anchors (see the bind sync above)
                            }
                            if (!found && currentIndex >= 0) currentIndex = -1;
                            adapter.notifyDataSetChanged();
                        }
                        // v1.42: the list already sits on the playing talk
                        // the moment it loads, not only when it is opened.
                        if (currentIndex >= 0) {
                            int sp55 = drawerScrollPos(currentIndex);
                            if (sp55 >= 0) roomList.setSelection(sp55);
                        }
                        // v3.06: premium load-into-open-drawer cascade — opening
                        // the drawer cascades its rows (v2.10) and closing it
                        // dissolves them (v3.05), but a talk list that finished
                        // loading while the drawer was already open just snapped
                        // its rows in, because this completion path never poured
                        // them. A clean, non-empty load into a settled-open drawer
                        // now cascades with the same Fx.staggerChildren the open
                        // path uses (45ms steps, fade + rise, posted after this
                        // load's layout pass). Guards: failure/empty loads have no
                        // rows to pour; a drawer mid-slide (translationX != 0)
                        // is left alone so this never fights the open cascade
                        // (v2.10) or the close cascade (v3.05) — the close end
                        // action still resets rows, and a later open re-cascades.
                        // Visual only; list, selection and load behaviour untouched.
                        if (ferr == null && !found.isEmpty() && isPlayListPanelOpen()) {
                            android.view.View dlPanel = findViewById(R.id.roomPlayListPanel);
                            if (dlPanel != null && dlPanel.getTranslationX() == 0f && roomList != null)
                                Fx.staggerChildren(roomList);
                        }
                        // v1.69: same interrupted-gate re-ask as the
                        // restored-list branch in onCreate, for a load
                        // that only finished after the interruption
                        // (e.g. a retried load) — guarded, so a fresh
                        // load with no pending question asks nothing.
                        // v7.27: same re-validation as the restored
                        // branch — a download may have finished or
                        // Wi-Fi may have connected while the load
                        // ran, so a stale question must play instead
                        // of asking (see there); cleared first so it
                        // cannot fire again for a playing talk.
                        // v11.97 (B-D4): same URL resolution as the restored-list branch above --
                        // the stored index can name a different talk after a re-fetch.
                        int gi97b = pendingGateUrl != null ? talkIndexForUrl(pendingGateUrl) : pendingGateIndex;
                        if (gi97b >= 0 && gi97b < talks.size()) {
                            // v11.46: resume-mode re-ask, same rule
                            // as the restored-list branch above (v12.09
                            // re-validation included: a cleared gate
                            // drops the question instead of asking).
                            if (pendingGateResume) {
                                if (needsDataGate(talks.get(gi97b)))
                                    askDataGate(gi97b, pendingGateSeek, true);
                                else {
                                    pendingGateIndex = -1;
                                    pendingGateUrl = null;
                                    pendingGateSeek = 0;
                                    pendingGateBackUrl = null; // v12.19
                                    pendingGateResume = false;
                                }
                            } else if (needsDataGate(talks.get(gi97b)))
                                askDataGate(gi97b, pendingGateSeek);
                            else {
                                int gi = gi97b;
                                int gs = pendingGateSeek;
                                pendingGateIndex = -1;
                                pendingGateUrl = null;
                                pendingGateSeek = 0;
                                pendingGateBackUrl = null; // v12.19
                                playIndex(gi, gs);
                            }
                        } else if (pendingGateIndex >= 0) {
                            // v11.90: same short-list clear as the restored-list branch above —
                            // a pending question whose index no longer exists is dropped instead
                            // of swallowing that index's taps forever. v12.09: only when a good
                            // load proves the talk left the site — a failed or empty load keeps
                            // the question, so the retry's completion re-asks it and the tap is
                            // not lost; the flag can never strand the poll, because the next load
                            // completion either re-asks or clears it.
                            if (ferr == null && !found.isEmpty()) {
                                // v12.73 (sim1280): same restore as the onCreate drop above —
                                // a history-back question dropped on a proven good load used
                                // to lose its popped entry and strand historyBackUrl, blocking
                                // the v8.22 fresh-Room seed. Give the entry back first —
                                // abandon's no-dialog rule.
                                if (pendingGateBackUrl != null && historyBackUrl != null) {
                                    pushPrevHistory16(pendingGateBackUrl);
                                    if (pendingGateBackUrl.equals(historyBackUrl)) historyBackUrl = null;
                                }
                                pendingGateIndex = -1;
                                pendingGateUrl = null;
                                pendingGateSeek = 0;
                                pendingGateBackUrl = null; // v12.19
                                pendingGateResume = false;
                            }
                        }
                        // v1.70: a Next tap that arrived before the
                        // list finished loading advances now.
                        if (pendingNext) handleNext();
                        if (pendingPrev) handlePrev(); // v4.23
                        if (pendingQueue) openQueueDrawer(); // v4.76: a queue tap that arrived before the list finished loading opens now
                    }
                });
            }
        }).start();
    }

    // v2.34: forward nudge for the Next button — a rapid re-tap
    // cancels the running nudge and restarts from 0, so the button
    // never drifts; the keyframed 0 -> dx -> 0 ends at exactly 0
    // on normal completion, and onPause/onDestroy reset it too.
    // v3.34: premium nudge-restart continuity — the cancel above
    // used to snap the button back to 0 mid-nudge (up to the full
    // 10dp) before the new nudge started, so a rapid double-tap
    // (Next twice to skip two talks, Forward twice for +60s)
    // visibly jumped the button sideways under the finger, while
    // the morph beat (v2.09, scale only) and every other restart
    // in this file now settles instead of snapping (v3.16/v3.17
    // pattern). The restart now begins at the button's current
    // offset — cancel leaves the animated value where it was —
    // and still ends at exactly 0, so an idle tap is pixel-
    // identical to before (start 0 -> dx -> 0) and a mid-flight
    // re-tap flows start -> dx -> 0 with no jump. The leave/
    // destroy resets still force 0, so no offset can ever strand.
    private void nudgeNext(View v) {
        if (nextNudge != null) { nextNudge.cancel(); nextNudge = null; }
        float dx = 10f * getResources().getDisplayMetrics().density;
        nextNudge = android.animation.ObjectAnimator.ofFloat(v, "translationX", v.getTranslationX(), dx, 0f);
        nextNudge.setDuration(320);
        nextNudge.setInterpolator(new android.view.animation.DecelerateInterpolator());
        nextNudge.start();
    }

    // v7.03: nudgeForward deleted — orphaned with the Room
    // secondary row (Forward button removed with it); the Room's
    // forward now lives only on the strips. Zero callers, and its
    // field + leave/destroy resets are deleted with it so no
    // future edit wires a button to a dead animator.

    @Override
    protected void onPause() {
        super.onPause();
        // v4.37: catch-all queue save — covers any mutation path
        // above that a future edit adds without its own save; per
        // sayadaw keys make this safe across sayadaw switches.
        saveQueue(null, true);
        // v1.81: report the leave + schedule the background check —
        // the immediate guard below already pauses on Home from here,
        // but the check is what catches a leave the guard skips
        // (Back to the chooser still counts: the chooser's resume
        // cancels it). Skipped while the share chooser is up, like
        // the guard itself (see suppressBgPause).
        AppForeground.activityPaused();
        // v7.74: no Room is on top any more (see topRoomSayadaw) —
        // the service may advance the session again, and the poll
        // re-syncs on return (v7.59) if it did.
        roomResumed = false;
        topRoomSayadaw = null;
        // v1.90: the delayed check used to be skipped entirely while
        // the share chooser was up — and if the listener then left
        // through the chooser's target app, the Room never resumed,
        // so background-playback-off never paused anything at all.
        // The check is now always scheduled; only the INSTANT pause
        // below stays suppressed for the chooser. The chooser case
        // gets a longer 10s delay instead of 600ms, so a slow share
        // pick (chooser still up, Room still the return target)
        // does not pause mid-pick, while leaving through a target
        // app still pauses within 10s. A return to the Room (or any
        // other screen's own check) replaces/cancels it via the
        // foreground count, as before.
        if (suppressBgPause) AppForeground.scheduleBackgroundCheck(this, 10000);
        else AppForeground.scheduleBackgroundCheck(this);
        // v1.71: the 500ms poll ran even with the Room in the back
        // stack or the phone on the home screen (only onDestroy
        // stopped it), touching views and driving the shrine
        // animation off-screen. Stop it here; onResume restarts it.
        handler.removeCallbacks(pollRunnable);
        // v4.05: stop listening while hidden — onResume re-registers.
        if (dlCompleteRegistered && dlCompleteReceiver != null) {
            try { unregisterReceiver(dlCompleteReceiver); } catch (Exception ignored) {}
            dlCompleteRegistered = false;
        }
        // v2.07: the glow pulse is a view animator, not the poll —
        // stop it here or it pulses on a hidden Room forever.
        if (glowPulse != null) { glowPulse.cancel(); glowPulse = null; }
        // v2.20: same for the glow scale pulse and the seek glide —
        // neither may run on a hidden Room, and the ring must be
        // back at 1.00 scale when the poll re-arms it on return.
        if (glowScale != null) { glowScale.cancel(); glowScale = null; }
        if (seekGlide != null) { seekGlide.cancel(); seekGlide = null; }
        // v2.52: same for the wave ripple — it must not tick on a
        // hidden Room; the restarted poll re-arms it on return.
        if (seekBar != null) seekBar.setPlaying(false);
        // v2.29: a leave mid-drag must not strand the track thickened.
        if (seekBar != null) { seekBar.animate().cancel(); seekBar.setScaleY(1f); }
        // v2.30: same for the talk-change seek-row fade — a leave
        // mid-fade must not strand the row faded or offset; the
        // restarted poll re-arms the next talk change on return.
        android.view.View seekRowPause = findViewById(R.id.roomSeekRow);
        if (seekRowPause != null) { seekRowPause.animate().cancel(); seekRowPause.setAlpha(1f); seekRowPause.setTranslationY(0f); }
        // v2.31: same for the talk-change controls-row fade.
        android.view.View controlsRowPause = findViewById(R.id.roomControlsRow);
        if (controlsRowPause != null) { controlsRowPause.animate().cancel(); controlsRowPause.setAlpha(1f); controlsRowPause.setTranslationY(0f); }
        // v2.65: same for the sayadaw-switch header pour — a leave
        // mid-pour must not strand the header faded or offset; the
        // next switch re-arms it from 0.
        android.view.View sdPause = findViewById(R.id.roomSayadaw);
        if (sdPause != null) { sdPause.animate().cancel(); sdPause.setAlpha(1f); sdPause.setTranslationY(0f); }
        // v2.66: same for the load-complete count pour — a leave
        // mid-pour must not strand the status line faded or offset;
        // the next load re-arms it from 0, and the poll rewrites
        // the text as usual on return.
        if (nowPlaying != null) { nowPlaying.animate().cancel(); nowPlaying.setAlpha(1f); nowPlaying.setTranslationY(0f); }
        // v2.71: same for the fresh-open Play List entrance — a leave
        // mid-entrance must not strand the button faded or offset;
        // a fresh open re-arms it from 0, and a return to an already
        // opened Room never replays it.
        if (playListEntrance != null) { playListEntrance.cancel(); playListEntrance = null; }
        android.view.View plPause = findViewById(R.id.roomPlayList);
        if (plPause != null) { plPause.setAlpha(1f); plPause.setTranslationY(0f); }
        // v3.82: same for the toggle dim — a leave mid-dim must not
        // let a stale dim animator write after the bright reset
        // above, and the entrance flag clears with the cancel so
        // onResume's dim re-arm (from drawerOpen) is never blocked
        // by a finished-then-cancelled entrance.
        if (toggleDimAnim != null) { toggleDimAnim.cancel(); toggleDimAnim = null; }
        playListEntranceDone = true;
        // v2.27: same for the sheet playing-row pulse — a detached
        // title view must never keep breathing, and the restarted
        // poll re-arms it on return.
        if (roomPulse != null) { roomPulse.cancel(); roomPulse = null; }
        if (roomPulseView != null) { android.view.View pvPause = roomPulseView; roomPulseView = null; roomPulseSettling = false; pvPause.animate().cancel(); pvPause.setAlpha(1f); } // v3.13: cancel a running pulse-stop fade too, so it never writes mid-fade values after the instant reset; v3.19: view + flag cleared BEFORE the cancel, or a cancelled settle's end action fires with its guards still passing and starts a loop on the detached view
        // v2.33: same for a mid-fade ring — a leave during the
        // fade-in/out must not strand it faded, and the restarted
        // poll re-arms it from the service state on return.
        if (playGlow != null) { playGlow.animate().cancel(); playGlow.setScaleX(1f); playGlow.setScaleY(1f); playGlow.setAlpha(1f); playGlow.setVisibility(View.GONE); glowFadingIn = false; glowFadingOut = false; }
        // v2.34: same for a mid-nudge Next button — a leave
        // mid-nudge must not strand it offset; the next tap
        // re-arms it from 0.
        if (nextNudge != null) { nextNudge.cancel(); nextNudge = null; }
        android.view.View nextPause = findViewById(R.id.roomNext);
        if (nextPause != null) nextPause.setTranslationX(0f);
        // v2.78: same for a mid-fade-out retry — a leave during the
        // 200ms fade must finish it to its intended GONE state at
        // full alpha, not strand it half-faded; a steady visible
        // retry (a real failure, no fade running) is left alone, so
        // returning to the Room still offers the retry.
        if (retryFade != null) {
            boolean wasHiding = retryFadeHiding;
            retryFade.cancel(); retryFade = null; retryFadeHiding = false;
            if (btnRetry != null) { if (wasHiding) btnRetry.setVisibility(View.GONE); btnRetry.setAlpha(1f); }
        }
        // v3.20: same for a mid-dip glyph fade — a leave mid-dip
        // must not strand the button dim or swap its label after
        // the return; the gen bump (before the cancel, v3.19
        // lesson) silences the stale end action, and the restarted
        // poll rewrites the label as usual on return.
        playGlyphGen++;
        if (playGlyphFade != null) { playGlyphFade.cancel(); playGlyphFade = null; }
        if (btnPlayPause != null) btnPlayPause.setAlpha(1f);
        // v1.78: stopping the poll was only half the fix — the
        // shrine animator itself re-posts every 50ms while lit,
        // and the poll was the only thing ever calling
        // setPlaying, so a talk left playing in the background
        // kept redrawing its flames off-screen at 20fps. Put the
        // flames out here; the restarted poll re-lights them
        // from the service state on return.
        // v13.22: ...except on a rotation — onPause runs BEFORE
        // onSaveInstanceState, so an unconditional put-out made
        // the bundle always save roomLit=false and the v3.88
        // restore rebuilt the shrine dark and relit it over
        // ~600ms (the relight waits on the poll, which needs the
        // rebind). A config change keeps lit=true through
        // detach/reattach (onDetachedFromWindow stops the
        // animator, onAttachedToWindow restarts it while lit),
        // so the altar never blinks; real leaves still put the
        // flames out here, and the leave-guard pause below
        // already skips config changes the same way.
        if (roomView != null && !isChangingConfigurations()) roomView.setPlaying(false);
        // v1.74: ...unless the pause is only the share chooser
        // opening (see suppressBgPause) — a transient sheet is
        // not leaving the Room, and background-off must not stop
        // the talk the listener is about to share.
        // v1.75: a stream still buffering counts as leaving with
        // playback pending — the old isPlaying() check missed it,
        // so the talk started aloud in the background after the
        // Room was left (pause() already holds a buffering talk
        // since v1.74; it just never got called here).
        // v10.67: a held buffering talk already opens paused — the
        // leave-guard pause() would TOGGLE it to will-play (v1.77),
        // so leaving the Room to Main/Talks with background off
        // started a parked talk aloud. Skip held talks only.
        if (!suppressBgPause && !Prefs.backgroundEnabled(this) && service != null
                && (service.isPlaying() || service.isBuffering()) && !service.isHoldPaused()
                && !isFinishing() && !isChangingConfigurations()) {
            service.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // v13.08: detach the liquid-glass panel — the drawable is the
        // view's background and its callback pins the dead view + its
        // destroyed activity in GlassBlur's static map on every
        // rotation (the v13.05 weak view ref did not fix this).
        GlassBlur.detach(findViewById(R.id.roomBottomPanel));
        handler.removeCallbacks(pollRunnable);
        // v2.20: defensive — the poll's animators must not outlive
        // the activity (onPause already cancels them on a normal
        // leave; a finish without pause still ends them here).
        if (seekGlide != null) { seekGlide.cancel(); seekGlide = null; }
        if (glowScale != null) { glowScale.cancel(); glowScale = null; }
        // v2.29: defensive — same thickened-track reset on destroy.
        if (seekBar != null) { seekBar.animate().cancel(); seekBar.setScaleY(1f); }
        // v2.30: defensive — same seek-row fade reset on destroy.
        android.view.View seekRowDestroy = findViewById(R.id.roomSeekRow);
        if (seekRowDestroy != null) { seekRowDestroy.animate().cancel(); seekRowDestroy.setAlpha(1f); seekRowDestroy.setTranslationY(0f); }
        // v2.31: defensive — same controls-row fade reset on destroy.
        android.view.View controlsRowDestroy = findViewById(R.id.roomControlsRow);
        if (controlsRowDestroy != null) { controlsRowDestroy.animate().cancel(); controlsRowDestroy.setAlpha(1f); controlsRowDestroy.setTranslationY(0f); }
        // v2.65: defensive — same sayadaw-switch header pour reset.
        android.view.View sdDestroy = findViewById(R.id.roomSayadaw);
        if (sdDestroy != null) { sdDestroy.animate().cancel(); sdDestroy.setAlpha(1f); sdDestroy.setTranslationY(0f); }
        // v2.66: defensive — same load-complete count pour reset.
        if (nowPlaying != null) { nowPlaying.animate().cancel(); nowPlaying.setAlpha(1f); nowPlaying.setTranslationY(0f); }
        // v2.71: defensive — same Play List entrance reset on destroy.
        if (playListEntrance != null) { playListEntrance.cancel(); playListEntrance = null; }
        android.view.View plDestroy = findViewById(R.id.roomPlayList);
        if (plDestroy != null) { plDestroy.setAlpha(1f); plDestroy.setTranslationY(0f); }
        // v3.82: defensive — same toggle-dim cancel on destroy, so
        // no dim animator outlives the activity.
        if (toggleDimAnim != null) { toggleDimAnim.cancel(); toggleDimAnim = null; }
        playListEntranceDone = true;
        // v2.27: defensive — the sheet pulse must not outlive the
        // activity (onPause already cancels it on a normal leave;
        // a finish without pause still ends it here).
        if (roomPulse != null) { roomPulse.cancel(); roomPulse = null; }
        if (roomPulseView != null) { android.view.View pvDestroy = roomPulseView; roomPulseView = null; roomPulseSettling = false; pvDestroy.animate().cancel(); pvDestroy.setAlpha(1f); } // v3.13: cancel a running pulse-stop fade too, so it never writes mid-fade values after the instant reset; v3.19: view + flag cleared BEFORE the cancel, same cancelled-settle end-action guard as onPause
        // v2.34: defensive — same Next-nudge reset on destroy.
        if (nextNudge != null) { nextNudge.cancel(); nextNudge = null; }
        android.view.View nextDestroy = findViewById(R.id.roomNext);
        if (nextDestroy != null) nextDestroy.setTranslationX(0f);
        // v2.78: defensive — same mid-fade-out retry finish on
        // destroy (a finish without pause still ends it here).
        if (retryFade != null) {
            retryFade.cancel(); retryFade = null;
            if (btnRetry != null) { btnRetry.setVisibility(View.GONE); btnRetry.setAlpha(1f); }
        }
        // v3.20: defensive — same mid-dip glyph finish on destroy
        // (a finish without pause still ends it here).
        playGlyphGen++;
        if (playGlyphFade != null) { playGlyphFade.cancel(); playGlyphFade = null; }
        if (btnPlayPause != null) btnPlayPause.setAlpha(1f);
        if (bound) {
            try { unbindService(connection); } catch (Exception ignored) {}
            bound = false;
        }
    }
}
