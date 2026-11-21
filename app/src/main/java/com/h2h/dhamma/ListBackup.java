package com.h2h.dhamma;

// v15.48 (slice 1957): pure backup builder for the Settings "Back
// up my lists" row — the listener's own lists as one JSON text:
// favourite sayadaws, favourite talks, listened talks, recent
// searches. Sets are sorted (stable text); the searches keep
// their recorded order (recency is data) with duplicates dropped.
// Null-safe everywhere; null/empty entries are dropped, never
// written. escape() covers quote, backslash and control chars;
// Burmese stays raw UTF-8 so the text reads in any notes app.
// No Android import — pure Java, tested by WgTest section 47 and
// modelled in sim1957. Import is the open follow-on; this core
// only writes.
public class ListBackup {
    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') b.append("\\\"");
            else if (c == '\\') b.append("\\\\");
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    static java.util.List<String> cleanSet(java.util.Collection<String> c) {
        java.util.TreeSet<String> out = new java.util.TreeSet<String>();
        if (c != null) {
            for (String s : c) {
                if (s != null && s.length() > 0) out.add(s);
            }
        }
        return new java.util.ArrayList<String>(out);
    }

    static java.util.List<String> cleanOrdered(java.util.Collection<String> c) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<String>();
        if (c != null) {
            for (String s : c) {
                if (s != null && s.length() > 0) out.add(s);
            }
        }
        return new java.util.ArrayList<String>(out);
    }

    static String arr(java.util.List<String> items) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) b.append(",");
            b.append("\"").append(escape(items.get(i))).append("\"");
        }
        b.append("]");
        return b.toString();
    }

    public static int count(java.util.Set<String> favs, java.util.Set<String> favTalks,
            java.util.Set<String> played, java.util.List<String> recents) {
        return cleanSet(favs).size() + cleanSet(favTalks).size()
                + cleanSet(played).size() + cleanOrdered(recents).size();
    }

    public static boolean isEmpty(java.util.Set<String> favs, java.util.Set<String> favTalks,
            java.util.Set<String> played, java.util.List<String> recents) {
        return count(favs, favTalks, played, recents) == 0;
    }

    public static String build(java.util.Set<String> favs, java.util.Set<String> favTalks,
            java.util.Set<String> played, java.util.List<String> recents) {
        return "{\"app\":\"Dhamma\",\"v\":1,\"favs\":" + arr(cleanSet(favs))
                + ",\"favTalks\":" + arr(cleanSet(favTalks))
                + ",\"played\":" + arr(cleanSet(played))
                + ",\"searches\":" + arr(cleanOrdered(recents)) + "}";
    }
}
