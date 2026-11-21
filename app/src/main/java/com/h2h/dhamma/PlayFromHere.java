package com.h2h.dhamma;

// v15.45 (slice 1942): "Play from here" pick — the row menu's
// first play verb. Queue-from-here (v13.04) queues this talk
// and every shown talk below it but never starts playback, and
// a row tap plays one talk without queueing the run below it,
// so starting a run mid-list took two gestures in two places.
// This picks the run: the display positions from the anchor
// talk onward, in display order, minus the sounding talk and
// minus already-queued talks (they stay where they are). The
// anchor itself is in the pick unless it sounds or is already
// queued — the Room still opens on it either way (openRoom on
// the anchor index), so a queued anchor plays from its queue
// spot and a sounding anchor is never re-opened. Pure index
// math only: the Activity owns the list, the queue and the Room
// open.
public class PlayFromHere {
    // The display positions to queue-and-play, in display order,
    // from the anchor onward, or an empty list when none.
    // `displayIds` is parallel to the shown list
    // (SiteHttp.identityUrl per talk, null where a talk has
    // none); `anchorId` is the long-pressed talk's identityUrl;
    // `queuedIds` holds the hand queue's identityUrls
    // (SiteHttp.identityUrl per queued URL, so http/https twins
    // match — sameTalk IS identity equality). Skips null ids,
    // the sounding talk (either form) and already-queued talks.
    // An anchor that is not in the list picks nothing (the
    // Activity falls back to the anchor alone). The input lists
    // are never mutated.
    static java.util.List<Integer> pick(
            java.util.List<String> displayIds,
            String anchorId,
            java.util.Set<String> queuedIds,
            String soundingUrl,
            String soundingLibUrl) {
        java.util.List<Integer> out = new java.util.ArrayList<Integer>();
        int start = anchorIndex(displayIds, anchorId);
        if (start < 0) return out;
        for (int i = start; i < displayIds.size(); i++) {
            String id = displayIds.get(i);
            if (id == null) continue;
            if (queuedIds != null && queuedIds.contains(id)) continue;
            if (soundingUrl != null && soundingUrl.length() > 0
                    && SiteHttp.sameTalk(id, soundingUrl)) continue;
            if (soundingLibUrl != null && soundingLibUrl.length() > 0
                    && SiteHttp.sameTalk(id, soundingLibUrl)) continue;
            out.add(Integer.valueOf(i));
        }
        return out;
    }

    // The anchor's position in the display list, or -1 — the
    // v13.13 tap-time re-resolution (the dialog can sit open
    // across a filter rebuild, so the captured position may
    // have moved). Identity comparison: the list and the anchor
    // are both identityUrls (SiteHttp.identityUrl per talk).
    static int anchorIndex(java.util.List<String> displayIds, String anchorId) {
        if (displayIds == null || anchorId == null || anchorId.length() == 0) return -1;
        for (int i = 0; i < displayIds.size(); i++) {
            String id = displayIds.get(i);
            if (id != null && id.equals(anchorId)) return i;
        }
        return -1;
    }
}
