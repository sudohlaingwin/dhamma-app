package com.h2h.dhamma;

// v15.44 (slice 1934): "Play new from favourites" pick —
// the one-tap play of the newest unseen talk from EACH
// starred sayadaw. The chooser's Play-new row (1933) plays
// one sayadaw's new talks; Main had no way to hear what's
// new from the people the listener follows without opening
// each starred sayadaw and tapping each dotted row by hand.
// YouTube Music's "New releases from your subscriptions" /
// Spotify's "Release Radar" is the same verb; this is it,
// one pick per favourite, in shelf order. Pure selection
// only: the Activity owns the catalog lookup, the sounding
// skip's live URLs and the Room open.
public class FavNewPick {
    // The row rule's threshold (TalksActivity getView, 1920):
    // a spot at or under 5s is a fresh start — and a dotted
    // talk is by definition unstarted, so a resume over the
    // threshold removes it from the pick. The Activity passes
    // the over-threshold keys (Prefs.talkPosKeysOver, built
    // with this value), so the core itself tests membership.
    static final int MIN_RESUME_MS = 5000;

    // One pick: the sayadaw who owns it, the talk to play
    // and its title (the cached list's own title, for the v2
    // queue line and the Room's autoTitle).
    public static class Pick {
        public final String sayadaw;
        public final String url;
        public final String title;
        public Pick(String sayadaw, String url, String title) {
            this.sayadaw = sayadaw == null ? "" : sayadaw;
            this.url = url == null ? "" : url;
            this.title = title == null ? "" : title;
        }
        @Override public String toString() {
            return sayadaw + " :: " + title;
        }
    }

    // The newest unseen talk per favourite, in fav order, or
    // an empty list when none. `favNames` is parallel to
    // `lists` (each favourite's cached TalkListCache pairs,
    // in site order) and to `newSets` (each favourite's 1920
    // new-set of identity URLs). `playedIds` holds the
    // finished talks' identityUrls; `resumeKeys` holds the
    // over-threshold resume keys (raw + identity form,
    // v13.65). A favourite contributes its FIRST cached-list
    // entry that is in the new-set, not finished, not
    // started, and not sounding (either form) — a favourite
    // with no such entry contributes nothing, never a
    // placeholder. A sounding newest-new falls through to
    // the next unseen talk, like the 1933 list skips sounding
    // and keeps the rest. Null/empty inputs yield an empty
    // list; the input lists are never mutated.
    static java.util.List<Pick> pick(
            java.util.List<String> favNames,
            java.util.List<java.util.List<String[]>> lists,
            java.util.List<java.util.Set<String>> newSets,
            java.util.Set<String> playedIds,
            java.util.Set<String> resumeKeys,
            String soundingUrl,
            String soundingLibUrl) {
        java.util.List<Pick> out = new java.util.ArrayList<Pick>();
        if (favNames == null || lists == null || newSets == null) return out;
        int n = Math.min(favNames.size(), Math.min(lists.size(), newSets.size()));
        for (int f = 0; f < n; f++) {
            String name = favNames.get(f);
            java.util.List<String[]> pairs = lists.get(f);
            java.util.Set<String> news = newSets.get(f);
            if (name == null || name.length() == 0) continue;
            if (pairs == null || pairs.isEmpty()) continue;
            if (news == null || news.isEmpty()) continue;
            for (String[] p : pairs) {
                if (p == null || p.length < 2) continue;
                String url = p[1];
                if (url == null || url.length() == 0) continue;
                String id = SiteHttp.identityUrl(url);
                if (id == null) continue;
                if (!news.contains(id)) continue;
                if (playedIds != null && playedIds.contains(id)) continue;
                if (resumeKeys != null && resumeKeys.contains(id)) continue;
                if (soundingUrl != null && soundingUrl.length() > 0
                        && SiteHttp.sameTalk(id, soundingUrl)) continue;
                if (soundingLibUrl != null && soundingLibUrl.length() > 0
                        && SiteHttp.sameTalk(id, soundingLibUrl)) continue;
                out.add(new Pick(name, url, p[0] == null ? "" : p[0]));
                break;
            }
        }
        return out;
    }

    // The library session's sounding talk, by its mp3 URL —
    // the v11.06 dlMap reverse lookup, lifted pure so the sim
    // and WgTest can prove it: the dlMap JSON maps mp3 URL to
    // downloaded basename; the sounding file's basename names
    // its talk only when exactly one URL claims it (the v8.44
    // rule — a dual-claimed file names no talk rather than the
    // wrong one). A file with no dlMap entry, a null/empty
    // path or a malformed map yields null, never a guess.
    // Hand-scanned, no org.json: the map is a flat object of
    // string pairs the app itself wrote, and the pure core
    // stays free of the runtime JSON — android.jar's org.json
    // is a stub on the desktop JVM (it throws "Stub!"), so a
    // lookup built on it could never be proven by WgTest:
    // every case would read null. The phone's runtime JSON
    // is real, but the core must not need it.
    static String libUrlForPath(String dlMapJson, String libPath) {
        if (dlMapJson == null || libPath == null || libPath.length() == 0) return null;
        String base = libPath.substring(libPath.lastIndexOf('/') + 1);
        if (base.length() == 0) return null;
        java.util.List<String[]> pairs = parseFlatStringMap(dlMapJson);
        if (pairs == null) return null;
        String hit = null;
        int matches = 0;
        for (String[] kv : pairs) {
            if (kv != null && kv.length == 2 && base.equals(kv[1])) { hit = kv[0]; matches++; }
        }
        return matches == 1 ? hit : null;
    }

    // Parse a flat JSON object of string-to-string pairs
    // ({"k": "v", ...}) into [key, value] pairs, or null when
    // the text is not such an object. Whitespace between
    // tokens is skipped; string escapes (quote, backslash,
    // slash, backspace, formfeed, newline, carriage return,
    // tab, unicode-hex) are decoded, so a key or value
    // carrying an escaped quote can never split a pair.
    // Anything else (a nested value, a missing
    // quote/colon/comma, trailing junk) fails closed to null,
    // never a partial map.
    static java.util.List<String[]> parseFlatStringMap(String json) {
        if (json == null) return null;
        int n = json.length();
        int[] pos = new int[]{0};
        skipJsonWs(json, pos);
        if (pos[0] >= n || json.charAt(pos[0]) != '{') return null;
        pos[0]++;
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        skipJsonWs(json, pos);
        if (pos[0] < n && json.charAt(pos[0]) == '}') {
            pos[0]++;
            skipJsonWs(json, pos);
            return pos[0] == n ? out : null;
        }
        while (true) {
            String key = readJsonString(json, pos);
            if (key == null) return null;
            skipJsonWs(json, pos);
            if (pos[0] >= n || json.charAt(pos[0]) != ':') return null;
            pos[0]++;
            skipJsonWs(json, pos);
            String val = readJsonString(json, pos);
            if (val == null) return null;
            out.add(new String[]{key, val});
            skipJsonWs(json, pos);
            if (pos[0] >= n) return null;
            char c = json.charAt(pos[0]);
            if (c == ',') { pos[0]++; skipJsonWs(json, pos); continue; }
            if (c == '}') {
                pos[0]++;
                skipJsonWs(json, pos);
                return pos[0] == n ? out : null;
            }
            return null;
        }
    }

    static void skipJsonWs(String json, int[] pos) {
        int n = json.length();
        while (pos[0] < n && Character.isWhitespace(json.charAt(pos[0]))) pos[0]++;
    }

    // Reads one JSON string starting at pos[0] (which must be
    // the opening quote), decodes it, advances pos[0] past the
    // closing quote, and returns the value — or null when the
    // text at pos[0] is not a well-formed JSON string.
    static String readJsonString(String json, int[] pos) {
        int n = json.length();
        int i = pos[0];
        if (i >= n || json.charAt(i) != '"') return null;
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < n) {
            char c = json.charAt(i);
            if (c == '"') { pos[0] = i + 1; return sb.toString(); }
            if (c == '\\') {
                if (i + 1 >= n) return null;
                char e = json.charAt(i + 1);
                switch (e) {
                    case '"': sb.append('"'); i += 2; break;
                    case '\\': sb.append('\\'); i += 2; break;
                    case '/': sb.append('/'); i += 2; break;
                    case 'b': sb.append('\b'); i += 2; break;
                    case 'f': sb.append('\f'); i += 2; break;
                    case 'n': sb.append('\n'); i += 2; break;
                    case 'r': sb.append('\r'); i += 2; break;
                    case 't': sb.append('\t'); i += 2; break;
                    case 'u':
                        if (i + 5 >= n) return null;
                        try {
                            int cp = Integer.parseInt(json.substring(i + 2, i + 6), 16);
                            sb.append((char) cp);
                        } catch (Exception ignored) { return null; }
                        i += 6;
                        break;
                    default: return null;
                }
                continue;
            }
            if (c < 0x20) return null;
            sb.append(c);
            i++;
        }
        return null;
    }
}
