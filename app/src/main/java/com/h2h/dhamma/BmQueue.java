package com.h2h.dhamma;

// v15.69 (slice 2075): "Add to queue" / "Remove from queue" on the
// Bookmarks row menu — the pure queue math, and the first queue
// verb on THIS screen. The Bookmarks screen had a row menu since
// slice 2072 (play-from-here + delete bookmark) but no queue verb
// at all: a listener who wanted to hear a marked minute LATER,
// after the sounding one, had to play it now (a tap replaces the
// queue) or queue the whole run from here (play-from-here).
// History's menu has had queue add/remove since v13.77
// (histQueueAdd / histQueueRemove); Favourites got its queue verb
// in slice 2073 (FavQueue) and New talks in slice 2074
// (NewQueue); this core is those two verbs as pure list math
// over the saved lines, so the Activity owns the prefs read, the
// menu offer, the queue write and the toasts, and the core never
// touches Android, prefs, or the queue.
// The queued unit is the MARK, not the talk: add() writes a v3
// line (2053 — identity URL, the row's sayadaw, the row's title,
// the mark's minute), so the Room pop (2054) opens on the mark
// when its turn comes. But the already-queued check is per TALK,
// not per mark (the 2055 BmPlayAll rule): a queued talk plays
// once, at the spot its queued line names, so its other marks sit
// this run out rather than double-playing the talk — a v2 line
// for the talk blocks the verb exactly like a v3 line for
// another of its marks. Remove drops every line for the talk
// (v2 + every v3 mark line): the queue holds a talk once, so
// removing it removes the talk, not one of its minutes.
// The queue key is the ROW's sayadaw (histQueueAdd's discipline —
// no Room opens here, so no healed-name coupling like
// playFromHereBm's); a live Room adopts an appended line on its
// next saveQueue (the v12.03 adopt rule). Appends only — this is
// NOT play-next: the waiting-foreign-head correction needs the
// sayadaw's loaded list, which this screen never loads (the
// v13.77 History adjudication), so no head-position claim is ever
// made. The sounding gate is BmPlayAll.isSounding, the TWO-
// identity rule of the 2041 mark (Room URL / library reverse
// URL) — the path leg lives in the RESOLUTION (streamUrlFor),
// not in this offer, so the offer and the gold mark never
// disagree; a mark with no real minute (posMs <= 0) offers no
// queue verb, exactly like BmPlayAll.lines skips it.
public class BmQueue {
    // True when the saved lines already queue this talk (sameTalk
    // over parsed entries — http/https twins count as one talk;
    // unparseable lines are skipped, never fatal, the v5.34 rule).
    // Per TALK, not per mark (the 2055 rule): a v2 line for the
    // talk, or a v3 line for ANY of its marks, counts as queued.
    // Null/blank URL, null lines: not queued, never a crash.
    static boolean isQueued(String url, java.util.List<String> savedLines) {
        if (url == null || url.length() == 0 || savedLines == null) return false;
        for (String ln : savedLines) {
            if (ln == null || ln.length() == 0) continue;
            QueueEntry qe = QueueEntry.parse(ln);
            if (qe != null && qe.url.length() > 0 && SiteHttp.sameTalk(qe.url, url)) return true;
        }
        return false;
    }

    // The menu's queue key: "" = no queue verb; "menu_queue_add"
    // when the mark can be queued; "menu_queue_remove" when its
    // talk is already queued. A stream URL with a sayadaw and a
    // real minute, not sounding (the BmPlayAll rule — the offer
    // and the gold mark never disagree); a path-form row is a
    // downloaded file, so it offers nothing, like BmRowMenu's play
    // gate. posMs <= 0 offers nothing (bookmarkAddPure never
    // writes one, and BmPlayAll.lines skips it too).
    static String queueKey(String url, String sayadaw, int posMs,
            String soundingUrl, String soundingLibUrl, boolean queued) {
        if (url == null || url.length() == 0) return "";
        if (url.startsWith("/")) return "";
        if (sayadaw == null || sayadaw.length() == 0) return "";
        if (posMs <= 0) return "";
        if (BmPlayAll.isSounding(url, soundingUrl, soundingLibUrl)) return "";
        return queued ? "menu_queue_remove" : "menu_queue_add";
    }

    // Append this mark to the saved queue: a v3 line (identity URL,
    // the row's sayadaw, the row's title, the mark's minute — the
    // 2053 shape). Saved lines are carried verbatim (the
    // histQueueAdd discipline), the input list is never mutated, a
    // null input reads as an empty queue. Null when the mark cannot
    // be queued (blank or path-form URL, blank sayadaw, posMs <= 0)
    // or its talk is already queued (per talk, the 2055 rule) —
    // the Activity re-checks validity first, so a null here means
    // "already in queue" at tap time.
    static java.util.ArrayList<String> add(String url, String sayadaw,
            String title, int posMs, java.util.List<String> savedLines) {
        if (url == null || url.length() == 0) return null;
        if (url.startsWith("/")) return null;
        if (sayadaw == null || sayadaw.length() == 0) return null;
        if (posMs <= 0) return null;
        if (isQueued(url, savedLines)) return null;
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        if (savedLines != null) {
            for (String ln : savedLines) {
                if (ln == null || ln.length() == 0) continue;
                out.add(ln);
            }
        }
        String line = QueueEntry.format(
                new QueueEntry(SiteHttp.identityUrl(url), sayadaw, title, posMs));
        if (line.length() == 0) return null;
        out.add(line);
        return out;
    }

    // Drop this talk from the saved queue (sameTalk over parsed
    // entries — every line for the talk, v2 and every v3 mark line;
    // unparseable lines are carried, never dropped on a guess).
    // A fresh list, the input never mutated. Null when the talk is
    // not queued — the Activity re-checks first, so a null here
    // means the queue changed under the dialog.
    static java.util.ArrayList<String> remove(String url,
            java.util.List<String> savedLines) {
        if (url == null || url.length() == 0 || savedLines == null) return null;
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        boolean dropped = false;
        for (String ln : savedLines) {
            if (ln == null || ln.length() == 0) continue;
            QueueEntry qe = QueueEntry.parse(ln);
            if (qe != null && qe.url.length() > 0 && SiteHttp.sameTalk(qe.url, url)) { dropped = true; continue; }
            out.add(ln);
        }
        return dropped ? out : null;
    }
}
