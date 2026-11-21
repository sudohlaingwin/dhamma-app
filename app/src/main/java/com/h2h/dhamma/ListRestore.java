package com.h2h.dhamma;

// v15.49 (slice 1959): pure backup reader for the Settings "Restore
// my lists" row — the import twin of the 1957 export: parses the
// one-line JSON text ListBackup.build writes ({"app":"Dhamma",
// "v":1,"favs":[...],"favTalks":[...],"played":[...],"searches":
// [...]}), so a list copied out on one phone (or saved to Drive /
// notes / mail) can be loaded back from the clipboard. Strict on
// the two markers — the app name must read "Dhamma" and the version
// must read 1 — so random clipboard text is refused with the
// restore_bad word instead of silently merging garbage. Tolerant
// of key order and surrounding whitespace; a missing list key
// reads as empty (a valid empty backup has all four); a present
// but malformed array is a bad backup, never a partial merge.
// Empty entries are dropped while parsing, so the merged sets can
// never gain a blank entry. No Android import — pure Java, tested
// by WgTest section 48 and modelled in sim1959. The merge itself
// (union into the stored sets, searches appended in order) lives
// in SettingsActivity, not here.
public class ListRestore {
    public static class Parsed {
        public final java.util.List<String> favs;
        public final java.util.List<String> favTalks;
        public final java.util.List<String> played;
        public final java.util.List<String> searches;
        Parsed(java.util.List<String> favs, java.util.List<String> favTalks,
                java.util.List<String> played, java.util.List<String> searches) {
            this.favs = favs;
            this.favTalks = favTalks;
            this.played = played;
            this.searches = searches;
        }
    }

    public static int count(Parsed p) {
        if (p == null) return 0;
        return p.favs.size() + p.favTalks.size() + p.played.size() + p.searches.size();
    }

    public static boolean isEmpty(Parsed p) {
        return count(p) == 0;
    }

    static int skipWs(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
            else break;
        }
        return i;
    }

    // Index just past the ':' of the first "key": pair, or -1 when
    // the key never appears with a colon after it.
    static int valueAfterKey(String s, String key) {
        String pat = "\"" + key + "\"";
        int k = s.indexOf(pat);
        while (k >= 0) {
            int i = skipWs(s, k + pat.length());
            if (i < s.length() && s.charAt(i) == ':') return i + 1;
            k = s.indexOf(pat, k + 1);
        }
        return -1;
    }

    // Reads a JSON string starting at s.charAt(pos[0]) (after
    // whitespace); returns the decoded value, or null when
    // malformed; advances pos[0] past the closing quote. The
    // exact inverse of ListBackup.escape (plus \/ \b \f, which
    // JSON allows and the export never writes).
    static String readString(String s, int[] pos) {
        int i = skipWs(s, pos[0]);
        if (i >= s.length() || s.charAt(i) != '"') return null;
        i++;
        StringBuilder b = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') { pos[0] = i + 1; return b.toString(); }
            if (c == '\\') {
                if (i + 1 >= s.length()) return null;
                char e = s.charAt(i + 1);
                if (e == '"') { b.append('"'); i += 2; }
                else if (e == '\\') { b.append('\\'); i += 2; }
                else if (e == '/') { b.append('/'); i += 2; }
                else if (e == 'b') { b.append('\b'); i += 2; }
                else if (e == 'f') { b.append('\f'); i += 2; }
                else if (e == 'n') { b.append('\n'); i += 2; }
                else if (e == 'r') { b.append('\r'); i += 2; }
                else if (e == 't') { b.append('\t'); i += 2; }
                else if (e == 'u') {
                    if (i + 6 > s.length()) return null;
                    try {
                        int cp = Integer.parseInt(s.substring(i + 2, i + 6), 16);
                        b.append((char) cp);
                        i += 6;
                    } catch (Exception ex) { return null; }
                } else return null;
            } else if (c < 0x20) return null;
            else { b.append(c); i++; }
        }
        return null;
    }

    // Reads a JSON string array starting at s.charAt(pos[0])
    // (after whitespace); returns the decoded list, or null when
    // malformed; advances pos[0] past ']'.
    static java.util.List<String> readArray(String s, int[] pos) {
        int i = skipWs(s, pos[0]);
        if (i >= s.length() || s.charAt(i) != '[') return null;
        i = skipWs(s, i + 1);
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (i < s.length() && s.charAt(i) == ']') { pos[0] = i + 1; return out; }
        while (true) {
            int[] p = { i };
            String v = readString(s, p);
            if (v == null) return null;
            out.add(v);
            i = skipWs(s, p[0]);
            if (i < s.length() && s.charAt(i) == ',') { i = skipWs(s, i + 1); continue; }
            if (i < s.length() && s.charAt(i) == ']') { pos[0] = i + 1; return out; }
            return null;
        }
    }

    // One list key: missing -> empty; present -> the parsed array
    // (null when malformed); empty entries dropped, like build.
    static java.util.List<String> readListKey(String s, String key) {
        int ki = valueAfterKey(s, key);
        if (ki < 0) return new java.util.ArrayList<String>();
        int[] p = { ki };
        java.util.List<String> raw = readArray(s, p);
        if (raw == null) return null;
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String v : raw) if (v != null && v.length() > 0) out.add(v);
        return out;
    }

    // Parses the backup text, or null when it is not a backup:
    // null/blank text, a missing or wrong app marker, a missing
    // or non-1 version, or any malformed list array.
    public static Parsed parse(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.length() == 0) return null;
        int ai = valueAfterKey(s, "app");
        if (ai < 0) return null;
        int[] p = { ai };
        if (!"Dhamma".equals(readString(s, p))) return null;
        int vi = valueAfterKey(s, "v");
        if (vi < 0) return null;
        int j = skipWs(s, vi);
        if (j >= s.length() || s.charAt(j) != '1') return null;
        int k = skipWs(s, j + 1);
        if (k >= s.length() || (s.charAt(k) != ',' && s.charAt(k) != '}')) return null;
        java.util.List<String> favs = readListKey(s, "favs");
        java.util.List<String> favTalks = readListKey(s, "favTalks");
        java.util.List<String> played = readListKey(s, "played");
        java.util.List<String> searches = readListKey(s, "searches");
        if (favs == null || favTalks == null || played == null || searches == null) return null;
        return new Parsed(favs, favTalks, played, searches);
    }
}
