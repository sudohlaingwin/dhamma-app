package com.h2h.dhamma;

// v15.64 (slice 2044): "Play all" on the Favourites screen — the
// pure pick for playing every starred talk straight through, in
// the screen's own order (most-recently-played first, FavAll's
// order). The chooser plays one sayadaw's shown list (v13.76) and
// the library plays one shelf's listened subset (v15.50); the
// favourites are the third list that deserves the same verb —
// starring a talk is the listener's "play this again" list, and
// re-hearing it meant tapping each row in turn. One tap here
// queues the whole starred run across sayadaws (every line
// carries its own sayadaw, the HistPlayFromHere v2 shape, so the
// Room renders the other sayadaws' talks as foreign rows and
// hands over when one sounds) and opens the Room on the first
// starred talk at its saved spot. Pure line math only: the
// Activity owns the list, the queue write and the Room open.
// The sounding talk is NOT in the run: the handler's stay-put
// branch owns it (the queue never restarts what already sounds,
// the chooser's v13.76 rule) — the core hands the handler the
// full non-sounding run and the handler applies the sounding
// rule for the first card. A talk already queued stays where it
// is (the History v15.62 rule); a blank URL or a blank sayadaw
// skips the row (a v2 line with no owner formats as a bare v1
// URL, and a v1 line whose URL is not in the anchor sayadaw's
// list is dropped by the Room's funnel, so writing it would only
// inflate the count toast). The input list is never mutated.
public class FavPlayAll {
    // True when this card's talk is the sounding one — a Room
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

    // The v2 queue lines for the starred run, in screen order.
    // `queuedIds` holds the identity URLs already in the anchor
    // sayadaw's saved queue (null reads as none queued). The first
    // card's line is included when it is not sounding and not
    // already queued — the handler opens the Room on it, and the
    // Room's play pops the head, so the line must be in the queue
    // for the run to continue after it.
    static java.util.List<String> lines(
            java.util.List<FavTalkRail.Card> cards,
            java.util.Set<String> queuedIds,
            String soundingUrl, String soundingLibUrl) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (cards == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (FavTalkRail.Card c : cards) {
            if (c == null || c.e == null) continue;
            String url = c.e.url;
            if (url == null || url.length() == 0) continue;
            String id = SiteHttp.identityUrl(url);
            if (id == null || id.length() == 0) continue;
            if (seen.contains(id)) continue;
            if (queuedIds != null && queuedIds.contains(id)) continue;
            if (isSounding(url, soundingUrl, soundingLibUrl)) continue;
            String sd = c.e.sayadaw;
            if (sd == null || sd.length() == 0) continue;
            seen.add(id);
            String line = QueueEntry.format(new QueueEntry(id, sd, c.e.title));
            if (line.length() > 0) out.add(line);
        }
        return out;
    }

    // The offer gate: at least one starred talk with a usable URL
    // that is not the sounding talk. The button shows on that —
    // a list whose only talk already sounds has nothing to play,
    // and an empty list has nothing at all. Already-queued talks
    // do NOT hide the button (the handler toasts already_in_queue
    // when the run adds nothing, the DlPlayListened shape).
    static boolean hasPlayable(java.util.List<FavTalkRail.Card> cards,
            String soundingUrl, String soundingLibUrl) {
        if (cards == null) return false;
        for (FavTalkRail.Card c : cards) {
            if (c == null || c.e == null) continue;
            String url = c.e.url;
            if (url == null || url.length() == 0) continue;
            if (isSounding(url, soundingUrl, soundingLibUrl)) continue;
            return true;
        }
        return false;
    }
}
