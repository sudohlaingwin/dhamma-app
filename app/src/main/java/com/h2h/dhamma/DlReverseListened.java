package com.h2h.dhamma;

// v15.52 (slice 1976): "Reverse-play shown listened talks" — the
// listened member of the Downloaded reverse-shown family (v6.48
// reverses the whole shown set, v6.82 reverses the shown star /
// shown unlistened subsets, this reverses the shown listened
// subset): the pure pick for reverse-playing exactly the listened
// files among the shown rows. The library could reverse the whole
// narrowed set (v6.48) and its starred / unlistened subsets
// (v6.82), but re-hearing just the finished talks backwards —
// last-listened first — meant reversing the whole shown set and
// skipping the unlistened files, or queueing the subset and
// reversing the queue by hand. Pure set math only: the Activity
// owns the prefs read, the menu offer, the queue write, the Room
// open and the toast; the core never touches Android, prefs, or
// the queue. Membership is the rows' own listened cache
// (libListenedFiles, the v6.94 rule), delegated to
// DlShuffleListened (slice 1974) — the same membership rule, so
// the offer gate and the dispatch handler — both calling this
// core — can never disagree about a file marked since the menu
// opened, and the shuffle and reverse members of the family can
// never disagree with each other either. This core adds only the
// reverse ordering the verb promises. Like the v6.47/v6.74 play
// pick (1967) and the shuffle pick (1974), the sounding file is
// NOT excluded from the pick: the v6.48/v6.82 reverse handler
// keeps it as the subset's first file and takes the stay-put
// branch (head-queue the rest behind it, no restart), so the
// core hands the handler the full listened subset in reverse
// display order and the handler applies the sounding rule; the
// offer gate's hasPlayable applies it for the menu.
public class DlReverseListened {
    // The listened paths among the shown rows, in REVERSE display
    // order: DlShuffleListened.pick (display order) reversed, so
    // the last-shown listened file plays first. A new list every
    // call — the caller's lists are never reordered — and null
    // inputs read as nothing listened, never a crash (the pick
    // core returns an empty list for them, and reversing an empty
    // list is still empty).
    public static java.util.List<String> pickReversed(java.util.List<String> shownNames, java.util.List<String> shownPaths, java.util.Set<String> listenedNames) {
        java.util.List<String> picked = DlShuffleListened.pick(shownNames, shownPaths, listenedNames);
        java.util.Collections.reverse(picked);
        return picked;
    }

    // The offer gate, delegated to the shared listened-set core:
    // at least one listened file other than the file still
    // sounding — the v6.82 gate, so the verb never offers when its
    // only member is already playing. A null or empty sounding
    // path means nothing sounds, so every listened file is
    // playable.
    public static boolean hasPlayable(java.util.List<String> shownNames, java.util.List<String> shownPaths, java.util.Set<String> listenedNames, String soundingPath) {
        return DlShuffleListened.hasPlayable(shownNames, shownPaths, listenedNames, soundingPath);
    }
}
