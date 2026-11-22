package com.h2h.dhamma;

// v15.62 (slice 2028): "Global talk search" — the index core (foundation, multi-run piece 1 of N).
// The chooser searches ONE sayadaw's list; a listener who remembers a talk title but not its
// sayadaw has no way to find it. The index is the fix: every talk list the app already parses
// (chooser open, Room open, auto-download job) merges its titles into one store, and search reads
// the store across all sayadaws. This file is the PURE core only — encode/decode/merge/search
// over the REAL parseTalkList output — no UI, no wiring, no version bump; the wiring slice (merge
// calls in the loaders + the Main search UI) follows, and until then the core ships inert.
// Reuses, never re-implements: TalksActivity.parseTalkList (the ONE parser, v12.68 — the core
// consumes its List<Talk>, so the index can never disagree with the chooser about what a page
// holds); Fx.titleMatches (the ONE search rule: NFC + foldDigits + the Zawgyi reading + flat,
// v13.60 — a global search that matched differently from the chooser's own search would be a
// bug); SiteHttp.identityUrl (the ONE identity, http->https — the stored URL is the identity
// form, so a talk streamed under http and listed under https is one entry, not two). Line
// discipline is the bookmarks64/playlists65 one (v12.64/v12.65): tab-separated fields,
// newline-joined lines, control chars stripped on write, malformed lines skipped, never fatal.
// The store itself lives in SharedPreferences under the key the wiring slice will own — this
// core only ever sees the raw string, so it stays Android-free and WgTest-provable.
public class TalkIndex {

    // One index entry: one talk, under the sayadaw whose page it was parsed from. The sayadaw
    // name is stored per entry (not a header) so a merge can drop one sayadaw's block without
    // touching the rest, and a search result always knows which sayadaw to open.
    public static class Entry {
        public final String sayadaw;
        public final String url;
        public final String title;
        public Entry(String s, String u, String t) { sayadaw = s; url = u; title = t; }
    }

    // A single sayadaw page never approaches this (measured max is one hub's 1,029 talks across
    // 3 sub-pages, v14.13; the biggest single page measured is Pa Auk's 670) — the cap only
    // guards a runaway page, it never truncates a real one.
    static final int MAX_PER_SAYADAW = 2000;
    // Bounds the prefs string the wiring slice will store (one key, whole index): the catalog
    // holds ~100 sayadaws, so 30,000 entries is headroom over any real catalog, and the
    // over-cap rule drops the OLDEST-indexed sayadaw blocks first, never the fresh merge.
    static final int MAX_TOTAL = 30000;
    // A search never pours more than this many rows — the UI shows the first 100 in store order
    // (catalog order, sayadaw by sayadaw); a broader query narrows, never pages.
    static final int MAX_RESULTS = 100;

    // The bookmarks64/playlists65 clean (v12.64/v12.65): control chars stripped on write, so a
    // stored field can never break the line split; a malformed line is skipped on read, never
    // fatal. Titles come from cleanTitle already, but a title is never trusted to be clean —
    // the strip runs here too, so the invariant holds for every writer.
    static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    // One line per entry: sayadaw TAB identityUrl TAB title. No trailing newline; an empty
    // store is the empty string, never null.
    static String encode(java.util.List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Entry e : entries) {
            if (e == null) continue;
            String s = clean(e.sayadaw);
            String u = clean(e.url);
            String t = clean(e.title);
            if (s.length() == 0 || u.length() == 0 || t.length() == 0) continue;
            if (!first) sb.append('\n');
            first = false;
            sb.append(s).append('\t').append(u).append('\t').append(t);
        }
        return sb.toString();
    }

    // The decode: a line needs exactly two tabs and three non-blank fields. A title can never
    // carry a tab (clean strips it on write), so a three-tab line is corrupt, not a title —
    // skipped, like every other malformed line. Never throws: a bad store decodes to the
    // entries that survive it, never a crash.
    static java.util.List<Entry> decode(String raw) {
        java.util.List<Entry> out = new java.util.ArrayList<Entry>();
        if (raw == null || raw.length() == 0) return out;
        for (String line : raw.split("\n")) {
            if (line.length() == 0) continue;
            int t1 = line.indexOf('\t');
            if (t1 <= 0) continue;
            int t2 = line.indexOf('\t', t1 + 1);
            if (t2 <= t1 + 1) continue;
            if (line.indexOf('\t', t2 + 1) >= 0) continue;
            String s = line.substring(0, t1);
            String u = line.substring(t1 + 1, t2);
            String t = line.substring(t2 + 1);
            if (s.length() == 0 || u.length() == 0 || t.length() == 0) continue;
            out.add(new Entry(s, u, t));
        }
        return out;
    }

    // Replace one sayadaw's block with a fresh parse (the REAL parseTalkList output — the ONE
    // parser, v12.68). The old block is dropped wholesale and the fresh block appends at the
    // end, so a re-merge moves the sayadaw to the end of the store order (search results for a
    // re-browsed sayadaw follow its later talks). Fresh entries dedupe by identity keeping the
    // first — parseTalkList dedupes by abs URL, not identity, so an http twin and its https
    // twin would otherwise index twice. A null/blank sayadaw or a null fresh list leaves the
    // store untouched (the raw string back, null as the empty string); a talk with a blank
    // URL or title is skipped, never stored. Caps: at most MAX_PER_SAYADAW fresh entries, and
    // at most MAX_TOTAL overall — over the total, the OLDEST-indexed sayadaw blocks drop
    // first (first distinct sayadaw in store order), never the fresh merge; the fresh block
    // alone can never exceed the total (MAX_PER_SAYADAW < MAX_TOTAL), so the truncate leg is
    // a guard for the caps ever moving, not a live path.
    static String merge(String raw, String sayadaw, java.util.List<TalksActivity.Talk> fresh) {
        String s = clean(sayadaw);
        if (s.length() == 0 || fresh == null) return raw == null ? "" : raw;
        java.util.List<Entry> out = new java.util.ArrayList<Entry>();
        for (Entry e : decode(raw)) if (!e.sayadaw.equals(s)) out.add(e);
        java.util.Set<String> seen = new java.util.HashSet<String>();
        int added = 0;
        for (TalksActivity.Talk t : fresh) {
            if (t == null || t.url == null || t.title == null) continue;
            String id = SiteHttp.identityUrl(t.url);
            if (id == null || id.length() == 0) continue;
            String title = clean(t.title);
            if (title.length() == 0) continue;
            if (!seen.add(id)) continue;
            if (added >= MAX_PER_SAYADAW) break;
            out.add(new Entry(s, id, title));
            added++;
        }
        while (out.size() > MAX_TOTAL) {
            String first = out.get(0).sayadaw;
            if (first.equals(s)) break;
            java.util.List<Entry> kept = new java.util.ArrayList<Entry>();
            for (Entry e : out) if (!e.sayadaw.equals(first)) kept.add(e);
            out = kept;
        }
        if (out.size() > MAX_TOTAL) out = out.subList(0, MAX_TOTAL);
        return encode(out);
    }

    // The search: the chooser's own rule (Fx.titleMatches — NFC + foldDigits + the Zawgyi
    // reading + flat, v13.60), over the title AND the sayadaw name, so a query naming the
    // sayadaw finds all their indexed talks and a query naming the talk finds it under any
    // sayadaw. A blank query matches nothing — never everything (the UI shows "type to
    // search", not the whole catalog). Store order, capped at MAX_RESULTS.
    static java.util.List<Entry> search(String raw, String query) {
        java.util.List<Entry> out = new java.util.ArrayList<Entry>();
        if (query == null || query.trim().length() == 0) return out;
        String q = query.trim();
        for (Entry e : decode(raw)) {
            if (Fx.titleMatches(e.title, q) || Fx.titleMatches(e.sayadaw, q)) {
                out.add(e);
                if (out.size() >= MAX_RESULTS) break;
            }
        }
        return out;
    }

    // Distinct sayadaw names in first-seen order — the "indexed N of M" honesty note the
    // search UI will show while the index is still growing (a fresh install indexes as the
    // listener browses; the store never claims full coverage it does not have).
    static java.util.List<String> indexedSayadaws(String raw) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (Entry e : decode(raw)) if (!out.contains(e.sayadaw)) out.add(e.sayadaw);
        return out;
    }
}
