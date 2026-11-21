package com.h2h.dhamma;

// v15.46 (slice 1947): "Choose which favourites auto-download" —
// the Wi-Fi auto-download scope picker. The job used to check
// EVERY star-favourited sayadaw; a listener with many favourites
// and a small phone had no way to say "only these three". The
// scope is the explicit set of favourite names the job may check,
// null = unset (all favourites, the pre-1947 behaviour). Pure
// list/set math only: Prefs owns the stored set, the job owns the
// fetch loop, the core never touches Android, the catalog, or any
// string the user sees.
public class AutoDlScope {
    // The effective scope, sorted: null chosen -> every favourite;
    // otherwise the chosen names that are still favourites (a
    // sayadaw unfavourited after choosing drops out; a name that
    // was never a favourite never enters). Null/empty favourites
    // -> empty. Null entries are skipped. Never mutates an input.
    public static java.util.List<String> scope(java.util.Set<String> favs, java.util.Set<String> chosen) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (favs == null) return out;
        if (chosen == null) {
            for (String f : favs) if (f != null && f.length() > 0) out.add(f);
        } else {
            for (String c : chosen) if (c != null && c.length() > 0 && favs.contains(c)) out.add(c);
        }
        java.util.Collections.sort(out);
        return out;
    }

    // What to persist after the picker changes: null while the
    // checked set still covers every favourite (the scope stays
    // dynamic — a favourite added later joins on its own), else
    // the checked set intersected with the favourites (stale
    // names never persist). Null checked, or null-or-empty
    // favourites, -> null. An empty checked set saves empty
    // (explicit none), never null — "no sayadaw" is a choice,
    // not the unset state.
    public static java.util.Set<String> saveValue(java.util.Set<String> favs, java.util.Set<String> checked) {
        if (checked == null) return null;
        if (favs == null || favs.isEmpty()) return null;
        for (String f : favs) {
            if (f == null || f.length() == 0) continue;
            if (!checked.contains(f)) {
                java.util.Set<String> out = new java.util.HashSet<String>();
                for (String c : checked) if (c != null && c.length() > 0 && favs.contains(c)) out.add(c);
                return out;
            }
        }
        return null;
    }
}
