package com.h2h.dhamma;

// v15.51 (slice 1973): "Copy shown listened links" — the fourth
// member of the Downloaded copy-shown family (v6.49 copies the
// whole shown set, v6.92 copies the shown star / shown unlistened
// subsets, this copies the shown listened subset): the pure pick
// for copying exactly the listened files among the shown rows.
// The library could copy a narrowed set (v6.49) and its starred
// / unlistened subsets (v6.92), but copying just the finished
// talks meant copying the whole shown set and trimming it, or
// copying them one by one (v6.56, one link, no header). Pure set
// math only: the Activity owns the prefs read, the menu offer,
// the clipboard and the toast; the core never touches Android,
// prefs, or the clipboard. Membership is the rows' own listened
// cache (libListenedFiles, the v6.94 rule) joined to the dlMap's
// exactly-one-claimant set (the v8.48 rule), so the offer gate
// and the dispatch handler — both calling pick — can never
// disagree about a file marked since the menu opened. Like the
// v6.46/v6.92 copies, the sounding file is NOT excluded from
// pick: a link list is not a play order, so the playing file
// stays in the set on purpose.
public class DlCopyListened {
    // One shown file is copyable here when its name sits in the
    // listened cache and in the exactly-one-claimant set (a name
    // with no dlMap entry, or claimed by two URLs, has no single
    // original link to copy). Null-safe on every input: a
    // null/blank name and a null set on either side all read as
    // not-copyable, never a crash.
    public static boolean isCopyable(String name, java.util.Set<String> listenedNames, java.util.Set<String> claimableNames) {
        if (name == null || name.length() == 0) return false;
        if (listenedNames == null || !listenedNames.contains(name)) return false;
        if (claimableNames == null || !claimableNames.contains(name)) return false;
        return true;
    }

    // The copyable names among the shown rows, in display order:
    // every shown name that passes isCopyable. A null list reads
    // as nothing copyable; the inputs are never mutated, and
    // duplicates are kept (the caller counts positions, like the
    // v6.92 loop — a name on two shown rows counts twice there
    // too, so the offer and the handler stay in step).
    public static java.util.List<String> pick(java.util.List<String> shownNames, java.util.Set<String> listenedNames, java.util.Set<String> claimableNames) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (shownNames == null) return out;
        for (int i = 0; i < shownNames.size(); i++) {
            String name = shownNames.get(i);
            if (isCopyable(name, listenedNames, claimableNames)) out.add(name);
        }
        return out;
    }
}
