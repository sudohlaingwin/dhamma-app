package com.h2h.dhamma;

// v15.46 (slice 1946): "Remove listened from queue" — the queue
// drawer's clean-up verb. A queue built by Play All / Play-new /
// queue-from-here keeps its finished talks as dimmed check rows;
// Clear would also drop the unplayed ones and removing them one
// by one is a chore, so the core drops exactly the finished
// waiting entries. Pure index math only: the Activity owns the
// queue lines, the finished set and the sounding talk; the core
// never touches Android, the catalog, or any string the user sees.
public class QueueClean {
    // What the clean leaves behind: `keep` is parallel to the input
    // ids (true = the line stays), `removed` counts the dropped lines.
    public static final class Result {
        public final java.util.List<Boolean> keep;
        public final int removed;
        public Result(java.util.List<Boolean> keep, int removed) {
            this.keep = keep;
            this.removed = removed;
        }
    }

    // The lines to drop, by identity: `lineIds` is SiteHttp.identityUrl
    // per queue line ("" where a line has none — a stale or foreign
    // line the parser could not read); `listened` holds the finished
    // talks' identityUrls (the drawer playedSet); `soundingIdentity`
    // is the identityUrl of the sounding talk ("" when nothing sounds).
    // A line drops only while its identity sits in listened AND is
    // not the sounding talk. Empty-identity lines are always kept
    // (unprovable — a bulk action must never vanish a line it cannot
    // read), duplicates of one listened identity all drop, and the
    // input list is never mutated.
    static Result clean(java.util.List<String> lineIds, java.util.Set<String> listened, String soundingIdentity) {
        java.util.List<Boolean> keep = new java.util.ArrayList<Boolean>();
        int removed = 0;
        if (lineIds == null) return new Result(keep, 0);
        String snd = soundingIdentity == null ? "" : soundingIdentity;
        for (String id : lineIds) {
            if (id == null || id.length() == 0 || (snd.length() > 0 && snd.equals(id)) || listened == null || !listened.contains(id)) {
                keep.add(Boolean.TRUE);
            } else {
                keep.add(Boolean.FALSE);
                removed++;
            }
        }
        return new Result(keep, removed);
    }
}
