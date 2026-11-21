package com.h2h.dhamma;

// v15.44 (slice 1937): recent Main searches — the last five
// committed sayadaw searches, most-recent-first. Pure core (no
// Android): Prefs only reads/writes the stored string, this
// class owns the shape. A query is stored exactly as committed
// (trimmed, control characters stripped so the \n-joined store
// can never split one query into two); matching is exact-after-
// trim, like the favs set — a differently-cased retype is a
// different entry, which is what the listener typed.
public class RecentSearches {
    public static final int MAX = 5;

    // One query, normalised for storage: strip \n/\r/\t (a
    // pasted query can carry them; the field is single-line but
    // the store must never hold a line break), trim, null -> "".
    public static String clean(String q) {
        if (q == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    // Parse the stored string: split on \n, drop empties,
    // dedupe keep-first, cap MAX.
    public static java.util.List<String> load(String raw) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (raw == null || raw.length() == 0) return out;
        for (String line : raw.split("\n")) {
            String q = clean(line);
            if (q.length() == 0) continue;
            if (out.contains(q)) continue;
            out.add(q);
            if (out.size() >= MAX) break;
        }
        return out;
    }

    // Serialise under the same rules as load (clean, skip
    // empty/dup, cap MAX), so save(load(x)) is idempotent.
    public static String save(java.util.List<String> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        java.util.List<String> seen = new java.util.ArrayList<String>();
        for (String q : list) {
            String c = clean(q);
            if (c.length() == 0) continue;
            if (seen.contains(c)) continue;
            seen.add(c);
            if (seen.size() > MAX) break;
            if (sb.length() > 0) sb.append('\n');
            sb.append(c);
        }
        return sb.toString();
    }

    // Record a committed search: most-recent-first, an existing
    // entry moves to the front (never duplicated), cap MAX —
    // the oldest drops off the end.
    public static java.util.List<String> record(java.util.List<String> cur, String q) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        String c = clean(q);
        if (c.length() > 0) out.add(c);
        if (cur != null) {
            for (String s : cur) {
                String cs = clean(s);
                if (cs.length() == 0 || cs.equals(c)) continue;
                out.add(cs);
                if (out.size() >= MAX) break;
            }
        }
        return out;
    }

    // Drop one exact entry, keep the rest in order.
    public static java.util.List<String> remove(java.util.List<String> cur, String q) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        String c = clean(q);
        if (cur != null) {
            for (String s : cur) {
                String cs = clean(s);
                if (cs.length() == 0 || cs.equals(c)) continue;
                out.add(cs);
            }
        }
        return out;
    }
}
