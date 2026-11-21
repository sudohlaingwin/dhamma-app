package com.h2h.dhamma;

// v15.50 (slice 1965): "Shuffle play" pick — the one-tap play of
// a shuffled set from a randomly-picked sayadaw. Every play in the
// app starts with a sayadaw choice (grid tap, rail card, the 1934
// fav-new row, Talks Play All / Shuffle); this is the first verb
// that needs no choice at all: Spotify's home Shuffle Play /
// YouTube Music's home Shuffle, one tap, a random sayadaw, its
// talks shuffled, the Room opening on the first shuffled talk.
// Pure selection only: the Activity owns the catalog entries, the
// page load/fetch, the sounding skip's live URLs, the queue
// rebuild and the Room open.
public class ShufflePick {

    // One valid entry: [name, pageUrl]. Uniform over the entries
    // with a non-empty name and a non-empty page URL; a sayadaw
    // with no page can never be picked (there is nothing to
    // shuffle). Null/empty inputs yield null, never a guess; the
    // input list is never mutated.
    static String[] pickSayadaw(java.util.List<String[]> entries,
            java.util.Random rng) {
        if (entries == null || rng == null) return null;
        java.util.List<String[]> valid = new java.util.ArrayList<String[]>();
        for (String[] e : entries) {
            if (e == null || e.length < 2) continue;
            if (e[0] == null || e[0].length() == 0) continue;
            if (e[1] == null || e[1].length() == 0) continue;
            valid.add(e);
        }
        if (valid.isEmpty()) return null;
        return valid.get(rng.nextInt(valid.size()));
    }

    // The page's talks in shuffle order. `pairs` are the
    // TalkListCache pairs in site order ([title, url]); the
    // output is a permutation of the usable pairs (no loss, no
    // duplication): null/short pairs skipped, empty URLs
    // skipped, http/https twins collapse to one (identity URL),
    // the sounding talk excluded in both its forms (live URL +
    // library URL, the 1934 rule). A null title reads "".
    // Null/empty inputs yield an empty list.
    static java.util.List<String[]> shuffleOrder(
            java.util.List<String[]> pairs, java.util.Random rng,
            String soundingUrl, String soundingLibUrl) {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        if (pairs == null || rng == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (String[] p : pairs) {
            if (p == null || p.length < 2) continue;
            String title = p[0];
            String url = p[1];
            if (url == null || url.length() == 0) continue;
            if (title == null) title = "";
            if (soundingUrl != null && soundingUrl.length() > 0
                    && SiteHttp.sameTalk(url, soundingUrl)) continue;
            if (soundingLibUrl != null && soundingLibUrl.length() > 0
                    && SiteHttp.sameTalk(url, soundingLibUrl)) continue;
            String id = SiteHttp.identityUrl(url);
            if (id == null) continue;
            if (seen.contains(id)) continue;
            seen.add(id);
            out.add(new String[]{title, url});
        }
        java.util.Collections.shuffle(out, rng);
        return out;
    }
}
