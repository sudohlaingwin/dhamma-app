package com.h2h.dhamma;

// v15.66 (slice 2055): "Play all marks" on the Bookmarks screen —
// the pure pick for playing every saved minute straight through,
// in the screen's own order (the shown run: most-recent first by
// default, the BmSort order the listener chose). The chooser plays
// one sayadaw's shown list (v13.76), the library a shelf's
// listened subset (v15.50), Favourites the starred run (v15.64,
// slice 2044) and New talks the unheard run (v15.65, slice 2047);
// the marks are the fifth list that deserves the same verb — a
// talk with three marks used to need three row taps, and the run
// between them was lost. One tap here queues the whole shown run
// of marks as v3 lines (2053/2054: each line carries its own
// minute, and every pop path honours it) and opens the Room on
// the first mark at its minute (playBookmark's own route — the
// Room resumes from the saved spot, v1.56, after the mark was
// written there by the openBmTalk1962 rule).
// Pure line math only: the Activity owns the list, the stream-URL
// resolution, the queue write and the Room open. An Item is one
// shown mark with its URL already resolved to the stream form
// (a path-form row resolves through the Activity's streamUrlFor,
// the exactly-one-claimant rule, v8.44 — a downloaded file's mark
// still plays; an unresolvable path names no talk and never
// becomes an Item, so the queue never holds a line the Room's
// funnel would drop).
// The sounding talk's marks are NOT in the run: the handler's
// stay-put branch owns it (the queue never restarts what already
// sounds, the chooser's v13.76 rule) — the core hands the handler
// the full non-sounding run and the handler applies the sounding
// rule for the first row. A talk already queued stays where it is
// (the History v15.62 rule): the already-queued check is per
// TALK (identity URL), not per mark — a queued talk plays once,
// at the spot its queued line names, so its other marks sit this
// run out rather than double-playing the talk. A talk with three
// marks queues three lines (the in-run dedupe key is identity +
// minute, BmAll's own key); a blank URL or a blank sayadaw skips
// the row (a v2 line with no owner formats as a bare v1 URL, and
// a v1 line whose URL is not in the anchor sayadaw's list is
// dropped by the Room's funnel, so writing it would only inflate
// the count toast — the 2044 guard). A minute <= 0 skips the row
// (bookmarkAddPure never writes one, so it can only be
// hand-built). The input list is never mutated.
public class BmPlayAll {
    public static class Item {
        public final String url;
        public final String sayadaw;
        public final String title;
        public final int posMs;
        public Item(String url, String sayadaw, String title, int posMs) {
            this.url = url;
            this.sayadaw = sayadaw;
            this.title = title;
            this.posMs = posMs;
        }
    }

    // True when this mark's talk is the sounding one — a Room
    // session (lastUrl) or a library session (dlMap reverse
    // lookup). Null/blank sounding URLs name no talk.
    static boolean isSounding(String url, String soundingUrl, String soundingLibUrl) {
        if (url == null || url.length() == 0) return false;
        if (soundingUrl != null && soundingUrl.length() > 0
                && SiteHttp.sameTalk(url, soundingUrl)) return true;
        if (soundingLibUrl != null && soundingLibUrl.length() > 0
                && SiteHttp.sameTalk(url, soundingLibUrl)) return true;
        return false;
    }

    // The v3 queue lines for the shown run of marks, in screen
    // order. `queuedIds` holds the identity URLs already in the
    // anchor sayadaw's saved queue (null reads as none queued).
    // The first mark's line is included when it is not sounding
    // and not already queued — the handler opens the Room on it,
    // and the Room's play drops the head on real play (the v4.43
    // ahead-drop, by URL), so the line must be in the queue for
    // the run to continue after it.
    static java.util.List<String> lines(
            java.util.List<Item> items,
            java.util.Set<String> queuedIds,
            String soundingUrl, String soundingLibUrl) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (items == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Item it : items) {
            if (it == null) continue;
            String url = it.url;
            if (url == null || url.length() == 0) continue;
            String id = SiteHttp.identityUrl(url);
            if (id == null || id.length() == 0) continue;
            if (it.posMs <= 0) continue;
            String key = id + ":" + it.posMs;
            if (seen.contains(key)) continue;
            if (queuedIds != null && queuedIds.contains(id)) continue;
            if (isSounding(url, soundingUrl, soundingLibUrl)) continue;
            String sd = it.sayadaw;
            if (sd == null || sd.length() == 0) continue;
            seen.add(key);
            String line = QueueEntry.format(new QueueEntry(id, sd, it.title, it.posMs));
            if (line.length() > 0) out.add(line);
        }
        return out;
    }

    // The offer gate: at least one shown mark with a usable URL
    // and sayadaw, a real minute, that is not the sounding talk.
    // The button shows on that — a list whose only mark already
    // sounds has nothing to play, and an empty list has nothing
    // at all. Already-queued talks do NOT hide the button (the
    // handler toasts already_in_queue when the run adds nothing,
    // the DlPlayListened shape).
    static boolean hasPlayable(java.util.List<Item> items,
            String soundingUrl, String soundingLibUrl) {
        if (items == null) return false;
        for (Item it : items) {
            if (it == null) continue;
            String url = it.url;
            if (url == null || url.length() == 0) continue;
            if (it.posMs <= 0) continue;
            String sd = it.sayadaw;
            if (sd == null || sd.length() == 0) continue;
            if (isSounding(url, soundingUrl, soundingLibUrl)) continue;
            return true;
        }
        return false;
    }
}
