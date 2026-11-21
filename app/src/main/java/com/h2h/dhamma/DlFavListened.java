package com.h2h.dhamma;

// v15.53 (slice 1983): "Favourite / un-favourite shown listened
// talks" — the listened member of the v7.05 new-subset fav pair
// (star queues the starred, new stars the unlistened, this stars
// the listened): the pure pick for starring / un-starring exactly
// the listened files among the shown rows. The library could star
// a narrowed set (v6.42), clear just its shown stars (v6.43) and
// star just its fresh files (v7.05), but starring just the finished
// files of a mixed heard + fresh view meant starring the whole
// shown set (also starring the fresh files, polluting the star
// filter) or starring file by file in the Room/chooser. Pure set
// math only: the Activity owns the prefs read, the menu offer, the
// favourite write and the toast; the core never touches Android,
// prefs, or the dlMap. Membership is the rows' own listened cache
// (libListenedFiles, the v6.94 rule), so the offer gate and the
// dispatch handlers — both calling pick — can never disagree about
// a file marked, starred or un-starred since the menu opened.
// The fav set the caller passes must already hold CANONICAL forms
// (the Activity builds it with SiteHttp.identityUrl per entry, the
// v14.80 rule): a legacy http twin banked by the old raw verb
// blocks the star, like the canonical read the rows render from.
public class DlFavListened {
    // One shown file is starrable when its name sits in the listened
    // cache, it resolves through the dlMap (the caller passes the
    // name -> source-URL map, already dual-claimant pruned by the
    // v8.47 rule), and its canonical source URL is not already in
    // the favourites set. Null-safe on every input: a null/blank
    // name, a null listened set, a null map and a null favourites
    // set (which reads as nothing starred) all read as not
    // starrable / starrable respectively, never a crash. The
    // sounding file is NOT skipped, like the v7.04/v7.05 pair
    // (starring the sounding file is what the strip star does).
    public static boolean isFavable(String name, java.util.Set<String> listenedNames, java.util.Map<String, String> nameToUrl, java.util.Set<String> favCanonicalUrls) {
        if (name == null || name.length() == 0) return false;
        if (listenedNames == null || !listenedNames.contains(name)) return false;
        if (nameToUrl == null) return false;
        String u = nameToUrl.get(name);
        if (u == null || u.length() == 0) return false;
        if (favCanonicalUrls != null && favCanonicalUrls.contains(SiteHttp.identityUrl(u))) return false;
        return true;
    }

    // The starrable names, in display order: every shown name that
    // passes isFavable. A null shown list reads as nothing
    // starrable; the inputs are never mutated, and duplicates are
    // kept (the caller de-dupes by set-add, like the v6.42 loop).
    public static java.util.List<String> pickFav(java.util.List<String> shownNames, java.util.Set<String> listenedNames, java.util.Map<String, String> nameToUrl, java.util.Set<String> favCanonicalUrls) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (shownNames == null) return out;
        for (String n : shownNames) {
            if (isFavable(n, listenedNames, nameToUrl, favCanonicalUrls)) out.add(n);
        }
        return out;
    }

    // One shown file is un-starrable when its name sits in the
    // listened cache and in the rows' own star cache (libFavFiles,
    // the v6.43 rule — the rows' mark, not the URL set, so the
    // offer and the rows can never disagree). An unlistened file
    // is never un-starrable here even when starred: the listened
    // filter excludes it, so this verb never touches a fresh
    // favourite. Null-safe on every input: a null/blank name, a
    // null listened set and a null star-name set all read as not
    // un-starrable, never a crash.
    public static boolean isUnfavable(String name, java.util.Set<String> listenedNames, java.util.Set<String> favNames) {
        if (name == null || name.length() == 0) return false;
        if (listenedNames == null || !listenedNames.contains(name)) return false;
        if (favNames == null || !favNames.contains(name)) return false;
        return true;
    }

    // The un-starrable names, in display order: every shown name
    // that passes isUnfavable. A null shown list reads as nothing
    // un-starrable; the inputs are never mutated, and duplicates
    // are kept (the caller de-dupes by set-remove, like the v6.43
    // loop).
    public static java.util.List<String> pickUnfav(java.util.List<String> shownNames, java.util.Set<String> listenedNames, java.util.Set<String> favNames) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (shownNames == null) return out;
        for (String n : shownNames) {
            if (isUnfavable(n, listenedNames, favNames)) out.add(n);
        }
        return out;
    }
}
