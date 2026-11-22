package com.h2h.dhamma;

// v15.55 (slice 1993): "Share this bookmark" — the Bookmarks rail
// quick-actions menu's minute-share verb (the other scout-closed
// candidate from slice 1989). The rail had the four-verb menu since
// v15.54 (slice 1989) plus the remove verb since slice 1992, but no
// way to hand a friend the exact marked minute: the plain share
// (RailMenu.shareText) carries the bare title, and the tap jumps
// the listener into the Room at the mark — a friend receiving the
// plain share gets no minute at all. Pure text only: the Activity
// owns the menu offer, the share sheet and the chooser title; the
// core never touches Android, prefs, or the queue.
public class BmShare {
    // The minute share: the Talks chooser's shape verbatim —
    // sayadaw + em dash + title, newline, the talk's own original
    // link, then the share_source credit suffix (which carries its
    // own leading newline) — with the card's own "12:34 · title"
    // rendering (BmRail.fmtTime, M:SS / H:MM:SS past the hour) in
    // the title slot, so the friend sees exactly which minute
    // matters. The link stays the talk's original link: the site
    // serves plain .mp3 links with nothing to deep-link a minute
    // into, so the minute lives in the text, not the URL. Null-safe
    // on every slot like RailMenu.shareText (a blank title passes
    // through blank — BmRail.pick guarantees a non-blank title on
    // every card this verb can ever be offered on); the values pass
    // through untouched otherwise, so what shares is exactly what
    // the card shows, plus its minute.
    static String shareAtMinuteText(String sayadawName, String title,
            String url, int posMs, String source) {
        String n = sayadawName == null ? "" : sayadawName;
        String t = title == null ? "" : title;
        String u = url == null ? "" : url;
        String s = source == null ? "" : source;
        return n + " \u2014 " + BmRail.fmtTime(posMs) + " \u00b7 " + t + "\n" + u + s;
    }
}
