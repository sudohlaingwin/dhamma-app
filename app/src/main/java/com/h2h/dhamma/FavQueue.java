package com.h2h.dhamma;

// v15.69 (slice 2073): "Add to queue" / "Remove from queue" on the
// Favourites row menu — the pure queue math. The Favourites screen
// had a row menu since slice 2069 (play-from-here + remove-from-
// favourites) but no queue verb at all: a listener who wanted to
// hear a favourite talk LATER, after the sounding one, had to play
// it now (a tap replaces the queue) or queue the whole run from
// here. History's menu has had queue add/remove since v13.77
// (histQueueAdd / histQueueRemove); this core is those two verbs
// as pure list math over the saved lines, so the Activity owns the
// prefs read, the menu offer, the queue write and the toasts, and
// the core never touches Android, prefs, or the queue. The queue
// key is the ROW's sayadaw (histQueueAdd's discipline — no Room
// opens here, so no healed-name coupling like playFromHereFav's);
// a live Room adopts an appended line on its next saveQueue (the
// v12.03 adopt rule). Appends only — this is NOT play-next: the
// waiting-foreign-head correction needs the sayadaw's loaded list,
// which this screen never loads (the v13.77 History adjudication),
// so no head-position claim is ever made.
public class FavQueue {
    // True when the saved lines already queue this talk (sameTalk
    // over parsed entries — http/https twins count as one talk;
    // unparseable lines are skipped, never fatal, the v5.34 rule).
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
    // when the row can be queued; "menu_queue_remove" when it is
    // already queued. A stream URL with a sayadaw, not sounding
    // (the FavPlayAll rule — the offer and the gold mark never
    // disagree); a path-form row is a downloaded file, so it
    // offers nothing, like FavRowMenu's play gate.
    static String queueKey(String url, String sayadaw,
            String soundingUrl, String soundingLibUrl, boolean queued) {
        if (url == null || url.length() == 0) return "";
        if (url.startsWith("/")) return "";
        if (sayadaw == null || sayadaw.length() == 0) return "";
        if (FavPlayAll.isSounding(url, soundingUrl, soundingLibUrl)) return "";
        return queued ? "menu_queue_remove" : "menu_queue_add";
    }

    // Append this talk to the saved queue: a v2 line (identity URL,
    // the row's sayadaw, the row's title — the histQueueAdd shape).
    // Saved lines are carried verbatim (the histQueueAdd discipline),
    // the input list is never mutated, a null input reads as an
    // empty queue. Null when the talk cannot be queued (blank or
    // path-form URL, blank sayadaw) or is already queued — the
    // Activity re-checks validity first, so a null here means
    // "already in queue" at tap time.
    static java.util.ArrayList<String> add(String url, String sayadaw,
            String title, java.util.List<String> savedLines) {
        if (url == null || url.length() == 0) return null;
        if (url.startsWith("/")) return null;
        if (sayadaw == null || sayadaw.length() == 0) return null;
        if (isQueued(url, savedLines)) return null;
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        if (savedLines != null) {
            for (String ln : savedLines) {
                if (ln == null || ln.length() == 0) continue;
                out.add(ln);
            }
        }
        String line = QueueEntry.format(
                new QueueEntry(SiteHttp.identityUrl(url), sayadaw, title));
        if (line.length() == 0) return null;
        out.add(line);
        return out;
    }

    // Drop this talk from the saved queue (sameTalk over parsed
    // entries; unparseable lines are carried, never dropped on a
    // guess). A fresh list, the input never mutated. Null when the
    // talk is not queued — the Activity re-checks first, so a null
    // here means the queue changed under the dialog.
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
