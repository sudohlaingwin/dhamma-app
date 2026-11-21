package com.h2h.dhamma;

// v15.47 (slice 1949): "Mark as listened / Mark as unlistened" —
// the Downloaded row menu's first single-file listened toggle.
// The library had shown-set mark/un-mark verbs (v6.44/v6.45, plus
// their ★/new twins) but no per-row toggle: a listener who wanted
// just this one file marked had to play it to the end or narrow
// the library down to it first, and a file heard elsewhere (or a
// re-download of one already finished) could not be cleared from
// the unlistened view at all. Pure set math only: the Activity
// owns the prefs read/write, the menu offer and the row rebind;
// the core never touches Android, prefs, or the dlMap.
// v15.52 (slice 1978): the Room drawer queue dialog reuses this core
// too — same verb on the queue surface, same rule, so the drawer,
// the chooser and the library can never disagree about a mark.
// v15.52 (slice 1979): the Room drawer foreign + in-list dialogs
// reuse it too — the mark family is now complete on every
// per-talk dialog in the drawer (queue 1978 + foreign/in-list
// 1979; the sounding row is excluded by the gate itself).
public class DlMark {
    // One mutation result: the new played set (a copy — the input
    // is never mutated) and whether the set actually changed.
    public static class Result {
        public final java.util.Set<String> set;
        public final boolean changed;
        public Result(java.util.Set<String> set, boolean changed) {
            this.set = set;
            this.changed = changed;
        }
    }

    // The offer gate: the row needs a source URL to mark (the
    // v6.53 shareUrl653 final — a file with no dlMap entry has no
    // URL, so nothing to mark, never a guessed link), and the
    // sounding file is excluded (the v6.44/v6.45 bulk rule: the
    // playing file earns its mark by finishing, like the
    // chooser's v6.30 skip). The favourite toggle beside it
    // includes the playing file — starring is not a play order,
    // marking is the finish itself.
    static boolean canOffer(String shareUrl, boolean playing) {
        return shareUrl != null && shareUrl.length() > 0 && !playing;
    }

    // The menu label key for the row's current mark state — the
    // caller reads libListenedFiles, the same cache the row's own
    // mark renders from, so the label can never disagree with the
    // visible mark (the v7.90 favourite-toggle idiom).
    static String labelKey(boolean listened) {
        return listened ? "dl_menu_unmark_one" : "dl_menu_mark_one";
    }

    // Current mark state over the canonical played set. Null-safe
    // on every input: a null set, a null URL and a blank URL all
    // read as unmarked, never a crash.
    static boolean isMarked(java.util.Set<String> played, String canonicalUrl) {
        return canonicalUrl != null && canonicalUrl.length() > 0
                && played != null && played.contains(canonicalUrl);
    }

    // Mark one talk listened: a copy of the played set with the
    // canonical URL added. A null/blank URL or an already-marked
    // URL returns the copy unchanged with changed=false, so the
    // caller can tell a real mark from a tap-time no-op.
    static Result mark(java.util.Set<String> played, String canonicalUrl) {
        java.util.Set<String> next = played == null
                ? new java.util.HashSet<String>()
                : new java.util.HashSet<String>(played);
        boolean changed = false;
        if (canonicalUrl != null && canonicalUrl.length() > 0) {
            changed = next.add(canonicalUrl);
        }
        return new Result(next, changed);
    }

    // Un-mark one talk listened: a copy of the played set with
    // BOTH forms removed — the canonical URL and the raw URL
    // (the v14.85 both-forms rule: a legacy http twin banked by
    // the old raw verb must go too, or the mark survives the
    // un-mark while the toast says it is gone). A URL in neither
    // form returns the copy unchanged with changed=false.
    static Result unmark(java.util.Set<String> played, String canonicalUrl, String rawUrl) {
        java.util.Set<String> next = played == null
                ? new java.util.HashSet<String>()
                : new java.util.HashSet<String>(played);
        boolean changed = false;
        if (canonicalUrl != null && canonicalUrl.length() > 0) {
            if (next.remove(canonicalUrl)) changed = true;
        }
        if (rawUrl != null && rawUrl.length() > 0) {
            if (next.remove(rawUrl)) changed = true;
        }
        return new Result(next, changed);
    }
}
