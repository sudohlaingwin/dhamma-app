package com.h2h.dhamma;

// v15.52 (slice 1975): "Copy this talk's link" — the Room drawer's
// clipboard verb: the pure rules for offering and copying one
// talk's own original link from any of the four drawer row menus
// (foreign rows, queue rows, the sounding row, in-list rows). The
// drawer could share a talk's text (the text share) and its file
// (the v15.48 file share), but a listener who wanted just the link
// pasted elsewhere had to share it through the sheet and copy from
// there. Pure string rules only: the Activity owns the menu offer,
// the clipboard write, and the toast; the core never touches
// Android, prefs, or the queue.
// v15.52 (slice 1977): the Talks chooser dialog reuses this core
// too — same verb on the browsing screen, same rule, so the drawer
// and the chooser can never disagree about what copies.
// v15.53 (slice 1981): the History row menu reuses this core
// too — same verb on the screen that lists what was heard, same
// rule, so the drawer, the chooser and the history can never
// disagree about what copies.
public class RoomCopyLink {
    // A drawer talk offers the copy verb when it has a link worth
    // copying: a non-blank url. The text share beside it is offered
    // unconditionally, but a blank url would copy an empty string
    // into the clipboard — the gate keeps the offer and the
    // tap-time handler (both call this) from ever disagreeing, the
    // 1952 lesson. A streamed-only talk still offers it (a link is
    // not a file); only a blank url hides it. Null-safe on every
    // input: null or blank reads as no-offer, never a crash. The
    // blank test trims, so whitespace-only is blank too.
    public static boolean canOffer(String url) {
        if (url == null) return false;
        return url.trim().length() > 0;
    }

    // The clipboard text: the talk's own original link, verbatim —
    // never a bare title, never a guessed link (the v6.56 rule).
    // The handler calls this only after canOffer, so the url is
    // non-blank here; the value passes through untouched (no trim,
    // no prefix), so what pastes is exactly the link. Null passes
    // through as null, never a crash.
    public static String clipText(String url) {
        return url;
    }

    // The toast: the link_copied_prefix + the talk's display title.
    // A blank title falls back to the url itself — the f-list share
    // branch's title-or-url rule — so the toast always names
    // something, even for a row whose title never loaded. A null
    // prefix reads as no prefix; a null url with a blank title
    // reads as an empty tail. The title itself is never trimmed:
    // only the blank test trims, so a real title pastes exactly
    // as the drawer shows it.
    public static String toastText(String prefix, String title, String url) {
        String p = prefix == null ? "" : prefix;
        String t = (title != null && title.trim().length() > 0) ? title : url;
        if (t == null) t = "";
        return p + t;
    }
}
