package com.h2h.dhamma;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("dhamma", Context.MODE_PRIVATE);
    }

    public static boolean isNight(Context c) {
        return sp(c).getBoolean("themeNight", false);
    }

    public static void setNight(Context c, boolean night) {
        sp(c).edit().putBoolean("themeNight", night).apply();
    }

    // 0 small, 1 normal, 2 large
    public static int fontSize(Context c) {
        SharedPreferences s = sp(c);
        if (!s.contains("fontSize")) {
            if (s.getBoolean("largeText", false)) return 2;
            return 1;
        }
        // v11.91: clamp — a corrupt stored value used to render as
        // Large on every screen with no indication anything was
        // wrong (same class as the v11.89 dlSortMode clamp and the
        // v11.88 font-picker clamp, which only guarded the dialog).
        int v = s.getInt("fontSize", 1);
        return v < 0 || v > 2 ? 1 : v;
    }

    public static void setFontSize(Context c, int v) {
        sp(c).edit().putInt("fontSize", v).putBoolean("largeText", v == 2).apply();
    }

    // v3.76: UI language (user order 2026-10-30 ~22:24 +07) — "my" default;
    // the language picker lives in Settings; Lang.java holds the strings.
    public static String lang(Context c) {
        return sp(c).getString("lang", "my");
    }

    public static void setLang(Context c, String v) {
        sp(c).edit().putString("lang", v).apply();
    }

    // v1.72: the notification-permission prompt was asked on every
    // cold start (and every rotation) until granted — a user who
    // declined it got nagged again on the next open. Ask once; a
    // later grant in system settings still works, it just stops
    // re-prompting from the app.
    public static boolean notiPermAsked(Context c) {
        return sp(c).getBoolean("notiPermAsked", false);
    }

    public static void setNotiPermAsked(Context c, boolean v) {
        sp(c).edit().putBoolean("notiPermAsked", v).apply();
    }

    public static boolean notiEnabled(Context c) {
        return sp(c).getBoolean("notiEnabled", true);
    }

    public static void setNotiEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("notiEnabled", v).apply();
    }

    public static boolean autoplay(Context c) {
        return sp(c).getBoolean("autoplay", true);
    }

    // v4.35: repeat mode — 0 off, 1 all (default: today's wrap
    // behaviour, v1.43), 2 one. Persisted, so one tap survives
    // reopening the Room; the Room button itself shows the state.
    public static int repeatMode(Context c) {
        // v11.91: clamp 0–2 — a corrupt stored value made three
        // screens disagree (Settings read it as repeat-one, the
        // Room glyph as repeat-all, playback as repeat-all
        // mid-list but repeat-off at the end); same class as the
        // v11.89 dlSortMode clamp.
        int v = sp(c).getInt("repeatMode", 1);
        return v < 0 || v > 2 ? 1 : v;
    }

    public static void setRepeatMode(Context c, int v) {
        sp(c).edit().putInt("repeatMode", v).apply();
    }

    // v4.36: shuffle — mirrors repeatMode: persisted, so one tap
    // survives reopening the Room; the Room button itself shows
    // the state. Default off = today's list order.
    public static boolean shuffleOn(Context c) {
        return sp(c).getBoolean("shuffleOn", false);
    }

    public static void setShuffleOn(Context c, boolean v) {
        sp(c).edit().putBoolean("shuffleOn", v).apply();
    }

    // v4.37: hand queue persistence, per sayadaw — one key per
    // sayadaw name, so opening sayadaw B's Room can never clobber
    // sayadaw A's saved queue (a single shared key would). URLs are
    // newline-joined in queue order (mp3 URLs never contain a
    // newline); restore filters against the loaded talk list, so a
    // stale URL simply drops instead of breaking the queue.
    // v11.99: the key is the sayadaw's page_url, not the name
    // (queueKeyFor99 below — the v11.83 B-D6 rename orphan); the
    // name still arrives here, the page is resolved inside.
    public static String savedQueue(Context c, String sayadaw) {
        migrateQueueKeys99(c);
        if (sayadaw == null || sayadaw.length() == 0) return "";
        return sp(c).getString(queueKeyFor99(c, sayadaw), "");
    }

    public static void saveQueue(Context c, String sayadaw, java.util.ArrayList<String> urls) {
        migrateQueueKeys99(c);
        if (sayadaw == null || sayadaw.length() == 0) return;
        // v13.55 (audit-1372 F3): the join lives in joinQueueLines11
        // below so the foreign-head carry writes byte-identical lines.
        sp(c).edit().putString(queueKeyFor99(c, sayadaw), joinQueueLines11(urls)).apply();
    }

    // v13.55 (audit-1372 F3): the newline-join shared by saveQueue above
    // and the foreign-head carry below — one source of truth for the
    // filter, so the carry can never drift from the plain save.
    // Byte-identical output to the old inline loop in saveQueue.
    private static String joinQueueLines11(java.util.ArrayList<String> urls) {
        StringBuilder sb = new StringBuilder();
        for (String u : urls) {
            if (u == null || u.length() == 0) continue;
            // v11.83: a URL carrying a control char would split this
            // newline-joined store into two lines, and the fragments
            // fail the restore filter — the talk silently drops
            // from the restored queue. The href loops reject such
            // URLs at parse time; this is the second line of
            // defence, same as talkPosPut's tab/newline guard.
            // v11.87: the '\t' leg of that guard is REMOVED — a v2
            // queue line is "url \t sayadaw \t title" by construction
            // (QueueEntry.format), so it dropped EVERY foreign line on
            // every save and the next restore adopted a foreign-less
            // queue (Room move-to-end, onPause, handover, Talks funnel
            // and background advance all lost their foreign talks).
            // Only '\n'/'\r' can split a newline-joined store; a tab
            // cannot. A line QueueEntry.parse() rejects (empty URL)
            // is still skipped, mirroring the read side.
            if (u.indexOf('\n') >= 0 || u.indexOf('\r') >= 0) continue;
            if (QueueEntry.parse(u) == null) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(u);
        }
        return sb.toString();
    }

    // v13.55 (audit-1372 F3): the foreign-head queue carry — the rest of
    // the queue travels to the new sayadaw's key and the old key is
    // cleared — in ONE Editor transaction. playForeignHead11 used to do
    // this as two saveQueue calls (two apply()s); a process death between
    // them left the queue duplicated across both keys: the carried rest
    // under the new sayadaw AND the full queue, head included, still
    // under the old sayadaw, so a fresh Room for the old sayadaw
    // re-offered talks already moved away. One apply() makes the carry
    // atomic — either both keys land or neither does — and a death
    // mid-carry leaves the pre-carry state, which the head restore
    // (armPendingQueueHead14) already handles. Empty sides are skipped,
    // exactly like saveQueue's early return; the same sayadaw on both
    // sides still ends cleared, exactly like the old two writes.
    public static void carryQueueForeign11(Context c, String toSayadaw,
            java.util.ArrayList<String> carried, String fromSayadaw) {
        migrateQueueKeys99(c);
        SharedPreferences.Editor ed = sp(c).edit();
        if (toSayadaw != null && toSayadaw.length() > 0)
            ed.putString(queueKeyFor99(c, toSayadaw), joinQueueLines11(carried));
        if (fromSayadaw != null && fromSayadaw.length() > 0)
            ed.putString(queueKeyFor99(c, fromSayadaw), "");
        ed.apply();
    }

    // v13.56 (Room handover torn-carry fix — the mirror of v13.55's
    // carryQueueForeign11, opposite direction): handoverToForeignTalk
    // used to clear the OLD sayadaw's key with one saveQueue call and
    // save the carried queue under the NEW sayadaw with a second (the
    // saveQueue funnel's final persist); a process death between the
    // two apply()s lost the queue from BOTH keys — old cleared, new
    // unwritten. One Editor transaction now: either both keys land
    // or neither does, and a death mid-carry leaves the pre-handover
    // state (old key intact, new key untouched), which the handover
    // simply re-runs on the next Next tap. Put order is the Room's
    // old order — clear first, carried second — so the same-sayadaw
    // degenerate case still ends with the carried list (the Room's
    // old clear-then-save result); carryQueueForeign11 puts the
    // clear last, so ITS degenerate case ends cleared (the service's
    // old save-then-clear result). Empty sides are skipped, exactly
    // like saveQueue's early return. Lines are joined by
    // joinQueueLines11, byte-identical to a plain saveQueue.
    public static void carryQueueHandover11(Context c, String toSayadaw,
            java.util.ArrayList<String> carried, String fromSayadaw) {
        migrateQueueKeys99(c);
        SharedPreferences.Editor ed = sp(c).edit();
        if (fromSayadaw != null && fromSayadaw.length() > 0)
            ed.putString(queueKeyFor99(c, fromSayadaw), "");
        if (toSayadaw != null && toSayadaw.length() > 0)
            ed.putString(queueKeyFor99(c, toSayadaw), joinQueueLines11(carried));
        ed.apply();
    }

    // v4.57: downloaded-library hand queue — one shared key, not
    // per sayadaw: the library is a single shelf, not one list per
    // sayadaw, so a per-sayadaw key would be meaningless here.
    // Paths are newline-joined in queue order (file paths never
    // contain a newline); restore filters against the loaded
    // entries, so a deleted file's stale path simply drops
    // instead of breaking the queue — the same rule as v4.37.
    public static String savedQueueLib(Context c) {
        return sp(c).getString("queue_library", "");
    }

    public static void saveQueueLib(Context c, java.util.ArrayList<String> paths) {
        StringBuilder sb = new StringBuilder();
        for (String u : paths) {
            if (u == null || u.length() == 0) continue;
            // v11.83: same control-char guard as saveQueue above —
            // a path carrying one would split this newline-joined
            // store and corrupt the restored library queue.
            // v11.91: the '\t' leg is REMOVED — the v11.87 fix to
            // saveQueue: a tab cannot split a newline-joined
            // store, and the leg dropped every tab-named library
            // file from the saved queue on every persist.
            if (u.indexOf('\n') >= 0 || u.indexOf('\r') >= 0) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(u);
        }
        sp(c).edit().putString("queue_library", sb.toString()).apply();
    }

    // v11.99: queue keys are page URLs, not sayadaw names (B-D6 —
    // the v11.83 persistence audit's deferred sayadaw-rename queue
    // orphan). v11.54 corrected one catalog spelling (သီရိကုဉ္စနာ →
    // သီရိကဉ္စနာ, same page_url) with no migration: a queue saved
    // under the old name became unreachable — savedQueue(newName)
    // reads "" while the old "queue_<oldName>" key lingers forever,
    // v2 foreign lines naming the old sayadaw fail
    // catalogEntryForSayadaw at handover, and the old name silently
    // drops out of favs. The fix has three parts, all driven by the
    // bundled catalog:
    //   (1) the catalog carries "aliases" (old names) on a renamed
    //       entry — whoever renames a sayadaw records the old name
    //       there; the migration below is what makes that record
    //       work. One alias ships so far (the v11.54 spelling).
    //   (2) queue storage keys are the sayadaw's page_url (stable
    //       across renames — verified unique across the 100
    //       entries), resolved inside Prefs, so NO call site
    //       changes: every caller keeps passing the sayadaw name.
    //       Link-less sayadaws (empty page_url — 3 entries, no talk
    //       page, so no queue can exist for them) and unresolvable
    //       names keep today's name key, never lose data.
    //   (3) a one-time, idempotent migration (once per process,
    //       triggered lazily here and from Main.loadCatalog so favs
    //       migrate on launch even when no queue is touched):
    //       every "queue_<name>" key moves to "queue_<page>" (both
    //       keys holding values merge, old lines first, dedup by
    //       URL — the merge covers a write that landed under the
    //       new key before the migration ran), v2 foreign lines
    //       and handoverBack21 lines have an aliased sayadaw field
    //       rewritten to the current name, and the favs set drops
    //       the alias for the current name. A key whose suffix is
    //       neither a current name nor an alias is left untouched —
    //       a future catalog alias can still rescue it; user
    //       content is never deleted by this migration.
    private static java.util.Map<String, String> sNameToPage99;
    private static java.util.Map<String, String> sAliasToPage99;
    private static java.util.Map<String, String> sAliasToName99;
    private static boolean sCatLoaded99;
    private static boolean sMigDone99;

    // Catalog name/alias maps, parsed once per process from the
    // bundled asset (same asset + field pattern as
    // Main.loadCatalog; the asset only changes with an app update,
    // which is a new process, so a per-process cache cannot go
    // stale). A failed parse leaves the maps empty and every
    // caller falls back to today's name key.
    private static void loadCatalogMaps99(Context c) {
        if (sCatLoaded99) return;
        sCatLoaded99 = true;
        sNameToPage99 = new java.util.HashMap<String, String>();
        sAliasToPage99 = new java.util.HashMap<String, String>();
        sAliasToName99 = new java.util.HashMap<String, String>();
        java.io.InputStream in = null;
        try {
            in = c.getAssets().open("sayadaws.json");
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) baos.write(buf, 0, read);
            org.json.JSONArray arr = new org.json.JSONArray(
                    new String(baos.toByteArray(), "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                // v13.30 (Catalog F3): a bad row must not hide every
                // name and alias after it — skip a non-object entry
                // instead of aborting the whole map build.
                org.json.JSONObject o;
                try { o = arr.getJSONObject(i); } catch (Exception ignored) { continue; }
                String name = o.optString("name", "");
                String page = o.isNull("page_url") ? ""
                        : o.optString("page_url", "");
                if (name.length() > 0 && page.length() > 0)
                    sNameToPage99.put(name, page);
                org.json.JSONArray al = o.isNull("aliases")
                        ? null : o.optJSONArray("aliases");
                if (al == null) continue;
                for (int j = 0; j < al.length(); j++) {
                    String a = al.optString(j, "");
                    if (a.length() == 0 || a.equals(name)) continue;
                    if (page.length() > 0) sAliasToPage99.put(a, page);
                    sAliasToName99.put(a, name);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (in != null) { try { in.close(); } catch (Exception ignored) {} }
        }
    }

    // v12.18 (slice A F5): canonical sayadaw name — a catalog alias resolves to
    // the current name, anything else passes through unchanged. The v11.99 queue
    // migration rewrites stored v2 sayadaw fields, but the await record
    // (AWAIT_SAYADAW12) is written by the Room at record time and can hold a
    // pre-rename name across an app update; resolving it keeps the Case A scan
    // and the catalog lookup from abandoning a valid head on a stale name, and
    // keeps a catalog-miss prune (advanceQueueHead63) from dropping a head
    // whose sayadaw is only an old spelling.
    public static String canonicalSayadaw99(Context c, String name) {
        if (name == null || name.length() == 0) return name;
        loadCatalogMaps99(c);
        String cur = sAliasToName99.get(name);
        return cur != null && cur.length() > 0 ? cur : name;
    }

    // Storage key for a sayadaw's queue: "queue_<page_url>" when the
    // name (or one of its catalog aliases) resolves to a page, else
    // today's "queue_<name>". A page_url argument passes through
    // untouched (defensive — no caller passes one today).
    private static String queueKeyFor99(Context c, String sayadaw) {
        if (sayadaw == null || sayadaw.length() == 0) return "queue_";
        if (sayadaw.startsWith("http")) return "queue_" + sayadaw;
        loadCatalogMaps99(c);
        String page = sNameToPage99.get(sayadaw);
        if (page == null) page = sAliasToPage99.get(sayadaw);
        if (page != null && page.length() > 0) return "queue_" + page;
        return "queue_" + sayadaw;
    }

    // One-time migration (see the v11.99 header above). The done
    // flag is set first so a re-entrant call cannot loop; the flag
    // is per-process, so a failed run retries on the next launch.
    // Best-effort by design: any exception leaves prefs exactly as
    // they were — worst case the orphan persists, as today — and
    // migration must never break launch.
    public static void migrateQueueKeys99(Context c) {
        // v13.68 (audit-1369 F11): dlMap/dlSayadaw/dlQueued read-modify-write - serialize on DlLock.LOCK (see DlLock).
        synchronized (DlLock.LOCK) {
        if (sMigDone99) return;
        sMigDone99 = true;
        try {
            loadCatalogMaps99(c);
            SharedPreferences s = sp(c);
            java.util.Map<String, ?> raw = s.getAll();
            java.util.Map<String, String> all =
                    new java.util.HashMap<String, String>();
            for (java.util.Map.Entry<String, ?> e : raw.entrySet()) {
                if (e.getValue() instanceof String)
                    all.put(e.getKey(), (String) e.getValue());
            }
            // getStringSet must not be mutated in place — copy it.
            java.util.Set<String> favs = s.getStringSet("favs", null);
            java.util.Set<String> favsCopy = favs == null ? null
                    : new java.util.HashSet<String>(favs);
            java.util.Map<String, String> edits =
                    migrateQueueKeysPure99(all, favsCopy,
                            sNameToPage99, sAliasToPage99, sAliasToName99);
            SharedPreferences.Editor ed = s.edit();
            boolean changed = false;
            for (java.util.Map.Entry<String, String> e : edits.entrySet()) {
                if (e.getValue() == null) ed.remove(e.getKey());
                else ed.putString(e.getKey(), e.getValue());
                changed = true;
            }
            if (favsCopy != null && !favsCopy.equals(favs)) {
                ed.putStringSet("favs", favsCopy);
                changed = true;
            }
            // v12.43: dlSayadaw values (URL -> sayadaw, written at
            // download time since v5.45) — an aliased owner here seeds
            // recordHandoverBack21 with the old name (RoomActivity,
            // library-interlude seed), so handlePrev's walk-back hits
            // the same catalog miss the queue-line rewrite was meant
            // to prevent. Rewrite aliased values to the current name;
            // a malformed map is left exactly as-is, and a value that
            // is already current (or unknown) is untouched. Lives
            // here, not in the pure core: the map is a JSON string.
            try {
                String dlRaw = s.getString("dlSayadaw", null);
                if (dlRaw != null && dlRaw.length() > 0
                        && sAliasToName99 != null && !sAliasToName99.isEmpty()) {
                    org.json.JSONObject dl = new org.json.JSONObject(dlRaw);
                    boolean dlChanged = false;
                    java.util.Iterator<String> dk = dl.keys();
                    while (dk.hasNext()) {
                        String k = dk.next();
                        String v = dl.optString(k, "");
                        String cur = sAliasToName99.get(v);
                        if (cur != null && cur.length() > 0 && !cur.equals(v)) {
                            dl.put(k, cur);
                            dlChanged = true;
                        }
                    }
                    if (dlChanged) {
                        ed.putString("dlSayadaw", dl.toString());
                        changed = true;
                    }
                }
            } catch (Exception ignored) {}
            if (changed) ed.apply();
        } catch (Exception ignored) {}
        }
    }

    // Pure core of the migration — no Android types, so a standalone
    // javac run can exercise it against the real catalog JSON.
    //   all: every String-valued prefs entry.
    //   favs: the "favs" string set, or null; rewritten IN PLACE.
    //   nameToPage / aliasToPage / aliasToName: from the catalog.
    // Returns edits to apply: key -> new value; a null value means
    // remove the key. The caller applies the edits and writes favs
    // back only when it changed (compare before/after).
    static java.util.Map<String, String> migrateQueueKeysPure99(
            java.util.Map<String, String> all,
            java.util.Set<String> favs,
            java.util.Map<String, String> nameToPage,
            java.util.Map<String, String> aliasToPage,
            java.util.Map<String, String> aliasToName) {
        java.util.Map<String, String> edits =
                new java.util.HashMap<String, String>();
        if (all == null) return edits;
        java.util.ArrayList<String> keys =
                new java.util.ArrayList<String>(all.keySet());
        for (String key : keys) {
            if (key == null) continue;
            if ("handoverBack21".equals(key)) {
                String rw = rewriteQueueLines99(all.get(key), aliasToName);
                if (rw != null && !rw.equals(all.get(key))) edits.put(key, rw);
                continue;
            }
            if (!key.startsWith("queue_") || "queue_library".equals(key)) continue;
            String suffix = key.substring("queue_".length());
            if (suffix.startsWith("http")) {
                // Already a page key — only its v2 lines may need
                // the alias rewrite (a foreign talk of a renamed
                // sayadaw). Skip when a merge already wrote this
                // key: the merge rewrites lines too, and the
                // rewrite below reads the ORIGINAL value, so it
                // would overwrite the merged value with the
                // un-merged one (iteration order is unspecified).
                if (edits.containsKey(key)) continue;
                String rw = rewriteQueueLines99(all.get(key), aliasToName);
                if (rw != null && !rw.equals(all.get(key))) edits.put(key, rw);
                continue;
            }
            String page = nameToPage == null ? null : nameToPage.get(suffix);
            if (page == null && aliasToPage != null) page = aliasToPage.get(suffix);
            if (page == null || page.length() == 0) continue;
            // Unknown name — leave untouched; a future catalog
            // alias can still rescue it. Never delete user content.
            String newKey = "queue_" + page;
            if (newKey.equals(key)) continue;
            // Two old keys merging into one page key (impossible
            // today — page_urls are unique — but cheap to cover):
            // merge onto the running merged value, not the
            // original, so the first merge's lines survive.
            String cur = edits.containsKey(newKey)
                    ? edits.get(newKey) : all.get(newKey);
            String merged = mergeQueueValues99(all.get(key), cur, aliasToName);
            edits.put(newKey, merged);
            edits.put(key, null); // remove the old name key
        }
        if (favs != null && aliasToName != null) {
            for (java.util.Map.Entry<String, String> a : aliasToName.entrySet()) {
                if (favs.contains(a.getKey())) {
                    favs.remove(a.getKey());
                    favs.add(a.getValue());
                }
            }
        }
        // v12.43: stored sayadaw-name scalars — the v11.99 migration
        // rewrote queue keys, queue v2 owner fields, handoverBack21
        // and favs, but never lastName (or the await record): lastName
        // kept the pre-rename spelling while every queue line became
        // the current name, so every raw comparison against it
        // misjudged — advanceQueueHead63 read an own head as foreign
        // (and its carry then re-poisoned bare lines with the old
        // owner, stranding the next Room handover on a catalog
        // miss), the Room's unbound sounding mirror and paused-card
        // count gate failed, and Main/notification Room opens
        // carried the old name as the Room's own sayadaw. A stored
        // value that IS a catalog alias is rewritten to the current
        // name; a current name, an unknown name or an absent key is
        // left untouched (same rule as favs). The await record is
        // already canonicalised at read (v12.18); rewriting the
        // stored value keeps the store canonical too.
        if (aliasToName != null) {
            for (String k : new String[]{"lastName", "awaitAdvanceSayadaw"}) {
                String v = all.get(k);
                if (v != null && v.length() > 0) {
                    String cur = aliasToName.get(v);
                    if (cur != null && cur.length() > 0) edits.put(k, cur);
                }
            }
        }
        return edits;
    }

    // Union of two queue values: old lines first, dedup by URL
    // (first wins — except a v1 line loses to a later v2 line for
    // the same URL, so a foreign talk keeps its owner), aliased
    // sayadaw fields rewritten to the current name. Lines
    // QueueEntry.parse() rejects are dropped — no reader can use
    // them, and every save path already skips them.
    static String mergeQueueValues99(String oldVal, String newVal,
            java.util.Map<String, String> aliasToName) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        java.util.Map<String, Integer> urlToIndex =
                new java.util.HashMap<String, Integer>();
        for (String raw : new String[]{oldVal, newVal}) {
            if (raw == null || raw.length() == 0) continue;
            for (String line : raw.split("\n")) {
                if (line.length() == 0) continue;
                // Parse the RAW line, exactly like filterSavedQueue:
                // trimming first would resurrect lines the readers
                // drop (a tab-leading line parses to an empty URL).
                QueueEntry e = QueueEntry.parse(line);
                if (e == null) continue;
                String sd = e.sayadaw;
                String kept = line;
                if (sd.length() > 0 && aliasToName != null
                        && aliasToName.containsKey(sd)) {
                    kept = QueueEntry.format(new QueueEntry(
                            e.url, aliasToName.get(sd), e.title));
                }
                Integer at = urlToIndex.get(e.url);
                if (at == null) {
                    urlToIndex.put(e.url, out.size());
                    out.add(kept);
                } else if (e.sayadaw.length() > 0) {
                    // v13.13 Q8: the CURRENT owner wins — the old
                    // first-wins rule kept a stale v2 owner when the
                    // same URL arrived under two owners (old name-key
                    // vs current page-key in the alias migration),
                    // and a stale owner makes the Room handover refuse
                    // the head. A bare v1 line still upgrades to v2
                    // (its owner is empty, so it differs); a
                    // same-owner repeat is a no-op. Position stays at
                    // first occurrence — only the owner/title refresh.
                    QueueEntry prev = QueueEntry.parse(out.get(at.intValue()));
                    QueueEntry keptE = QueueEntry.parse(kept);
                    if (prev != null && keptE != null && !keptE.sayadaw.equals(prev.sayadaw))
                        out.set(at.intValue(), kept);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String l : out) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        return sb.toString();
    }

    // Rewrite aliased sayadaw fields in a queue value. Returns the
    // rewritten value, or null when nothing changes (so the caller
    // can skip the write). Unparseable lines are kept verbatim —
    // unlike the merge, a pure rewrite must not drop user content
    // it was never asked to drop.
    static String rewriteQueueLines99(String raw,
            java.util.Map<String, String> aliasToName) {
        if (raw == null || raw.length() == 0
                || aliasToName == null || aliasToName.isEmpty()) return null;
        boolean changed = false;
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n")) {
            if (sb.length() > 0) sb.append('\n');
            // Parse the RAW line, exactly like filterSavedQueue (see
            // mergeQueueValues99): trimming first would misread
            // lines the readers drop.
            QueueEntry e = QueueEntry.parse(line);
            if (e != null && e.sayadaw.length() > 0
                    && aliasToName.containsKey(e.sayadaw)) {
                sb.append(QueueEntry.format(new QueueEntry(
                        e.url, aliasToName.get(e.sayadaw), e.title)));
                changed = true;
            } else {
                sb.append(line);
            }
        }
        return changed ? sb.toString() : null;
    }

    // v5.34: per-talk resume positions — the app only ever saved ONE
    // resume spot (lastPos/lastPosUrl, the most recent talk), so
    // returning to any other half-heard talk restarted it from 0
    // even though the listener had stopped mid-talk. One key holds
    // every talk's spot: lines "url\tpos", most-recent first,
    // capped at 100 lines (a talk's entry counts once per form, so
    // this is ~50 talks; old spots simply age out instead of
    // growing prefs forever). URLs never contain a tab or newline;
    // a line that fails to parse is skipped, never fatal. The
    // service writes a talk under BOTH its forms (stream URL and
    // downloaded-file path, see AudioService), so every lookup here
    // needs only the one form the caller already has.
    private static final String TALK_POS_KEY = "talkPosMap";
    private static final int TALK_POS_CAP = 100;

    public static int talkPosGet(Context c, String url) {
        if (url == null || url.length() == 0) return 0;
        try {
            String raw = sp(c).getString(TALK_POS_KEY, "");
            if (raw.length() == 0) return 0;
            for (String line : raw.split("\n")) {
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                if (!line.substring(0, t).equals(url)) continue;
                // v11.91: scan on past a malformed line instead of
                // giving up — a later valid duplicate for the same
                // URL is the real spot; the old return 0 restarted
                // the talk from the beginning. Matches
                // talkPosKeysOver's skip-the-line handling.
                try { return Integer.parseInt(line.substring(t + 1)); }
                catch (Exception ignored) { continue; }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    // v5.69: keys with a saved spot above a threshold, parsed once.
    // Callers that count half-heard talks over a whole list (the
    // Room drawer header) must not call talkPosGet per talk — that
    // re-splits the whole map on every lookup, once per talk per
    // poll tick. One parse, one set, then plain membership tests.
    // v5.72 FIX: this split used to be "\\n" (a literal backslash
    // + n, which never matches a real newline) — with two or more
    // saved spots the whole blob parsed as one line, parseInt
    // failed on it, and every caller got an empty set, so the
    // v5.69/v5.70 ↺ header counts always read zero. Now "\n".
    public static java.util.Set<String> talkPosKeysOver(Context c, int minPos) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        try {
            String raw = sp(c).getString(TALK_POS_KEY, "");
            if (raw.length() == 0) return out;
            for (String line : raw.split("\n")) {
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                try {
                    if (Integer.parseInt(line.substring(t + 1)) > minPos)
                        out.add(line.substring(0, t));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void talkPosPut(Context c, String url, int pos) {
        talkPosPutInner(c, url, pos, false);
    }

    // v13.06: synchronous twin — same write, commit() instead of
    // apply(), for the swipe-away exit (AudioService.onTaskRemoved)
    // where the process is killed ~300ms after the save and an
    // apply() flush may never land on disk.
    public static void talkPosPutSync(Context c, String url, int pos) {
        talkPosPutInner(c, url, pos, true);
    }

    // v13.06: commit twin of talkPosPutBoth — same both-forms write,
    // synchronous, so a re-saved spot survives the swipe-away kill.
    public static void talkPosPutBothSync(Context c, String url, int pos) {
        talkPosPutSync(c, url, pos);
        String other = talkPosOtherForm(c, url);
        if (other != null) talkPosPutSync(c, other, pos);
    }

    private static void talkPosPutInner(Context c, String url, int pos, boolean sync) {
        if (url == null || url.length() == 0 || pos <= 0) return;
        if (url.indexOf('\t') >= 0 || url.indexOf('\n') >= 0 || url.indexOf('\r') >= 0) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(url).append('\t').append(pos);
            int count = 1;
            String raw = sp(c).getString(TALK_POS_KEY, "");
            if (raw.length() > 0) {
                for (String line : raw.split("\n")) {
                    if (count >= TALK_POS_CAP) break;
                    int t = line.indexOf('\t');
                    if (t <= 0) continue;
                    if (line.substring(0, t).equals(url)) continue;
                    sb.append('\n').append(line);
                    count++;
                }
            }
            android.content.SharedPreferences.Editor e =
                    sp(c).edit().putString(TALK_POS_KEY, sb.toString());
            if (sync) e.commit(); else e.apply();
        } catch (Exception ignored) {}
    }

    // v5.34: the other form of a talk's key, so a spot is findable
    // whichever way the talk is opened next. A stream URL maps
    // forward to its downloaded file (dlMap entry, Downloads/Dhamma
    // construction, only when the file actually exists); a file path
    // maps back to the stream URL whose dlMap entry names it. Null
    // when the talk has no second form, so callers just use the one
    // form they have.
    private static String talkPosOtherForm(Context c, String url) {
        try {
            org.json.JSONObject map = new org.json.JSONObject(
                    sp(c).getString("dlMap", "{}"));
            if (url.startsWith("/")) {
                // v8.50: exactly-one claimant, never the first
                // match — the v8.41 strip rule, extended to the
                // resume-spot form lookup it never covered. A
                // dual-claimed file (two talks mapped to one
                // name, creatable by the delivered v8.39; v8.40
                // stops new ones) let the old first-match write
                // the heard talk's spot under the OTHER talk's
                // stream URL (talkPosPutBoth, via the service's
                // checkpoint/pause writes for a library session),
                // so the other talk later resumed at a wrong
                // minute — and let a finish/dismiss clear
                // (talkPosRemoveBoth) delete the other talk's
                // genuine spot while the heard talk's own stream
                // spot survived to resurrect it (the v8.30 class,
                // cross-talk). An ambiguous file now maps to no
                // second form: its spot lives under the path
                // form only, exactly like a file with no dlMap
                // entry, and no talk gains or loses a spot it
                // never earned. Single-claim and zero-claim
                // files behave exactly as before.
                String fn = new java.io.File(url).getName();
                java.util.Iterator<String> keys = map.keys();
                String hit = null;
                int matches = 0;
                while (keys.hasNext()) {
                    String k = keys.next();
                    if (map.optString(k, "").equals(fn)) { hit = k; matches++; }
                }
                return matches == 1 ? hit : null;
            }
            String fn = map.optString(url, "");
            if (fn.length() == 0) return null;
            java.io.File f = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma/" + fn);
            return f.exists() ? f.getAbsolutePath() : null;
        } catch (Exception ignored) { return null; }
    }

    // v5.34: save/remove under both forms at once — every writer and
    // clearer goes through here, so no path can leave the other form
    // behind to resurrect a spot that was dismissed, finished or
    // cleared (the dismiss/finish/clear promise is "starts from the
    // beginning", and a surviving second form would break it).
    public static void talkPosPutBoth(Context c, String url, int pos) {
        talkPosPut(c, url, pos);
        String other = talkPosOtherForm(c, url);
        if (other != null) talkPosPut(c, other, pos);
    }

    // v8.30: the dlMap path for a stream URL, WITHOUT the file-
    // existence check talkPosOtherForm applies. That check is
    // right for WRITES (talkPosPutBoth must never invent a path
    // entry for a talk with no downloaded file), but it breaks
    // REMOVALS: a talk half-heard offline, whose file was then
    // deleted outside the app, keeps its spot under the path
    // form, and the dlMap link survives (only in-app deletes
    // prune it, v8.29) — so a later finish/dismiss by stream
    // URL removed the stream entry while the path entry
    // survived, and re-downloading the talk resurrected the
    // old minute through savedPosFor's path-form fallback
    // (v5.36) even though the talk had been finished. A
    // removal must therefore also drop the mapped path, file
    // or no file: a phantom path entry can never be a valid
    // resume target, and a finished/dismissed talk must start
    // from the beginning in every form.
    private static String talkPosMappedPath(Context c, String url) {
        if (url == null || url.startsWith("/")) return null;
        try {
            org.json.JSONObject map = new org.json.JSONObject(
                    sp(c).getString("dlMap", "{}"));
            String fn = map.optString(url, "");
            if (fn.length() == 0) return null;
            return new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma/" + fn)
                    .getAbsolutePath();
        } catch (Exception ignored) { return null; }
    }

    public static void talkPosRemoveBoth(Context c, String url) {
        talkPosRemove(c, url);
        String other = talkPosOtherForm(c, url);
        if (other != null) talkPosRemove(c, other);
        // v8.30: also the mapped path when the file is gone —
        // talkPosOtherForm returns null for a stream URL whose
        // file no longer exists, so without this the path-form
        // spot survived every finish/dismiss (see above).
        // talkPosRemove is a no-op for an absent key, and when
        // the file exists this names the same path other
        // already removed, so steady-state behaviour is
        // unchanged and no write path is touched.
        String mapped = talkPosMappedPath(c, url);
        if (mapped != null) talkPosRemove(c, mapped);
        // v11.83: also a path-form spot with NO dlMap link at all —
        // a file placed by hand (or predating dlMap) holds its
        // spot under the downloaded-file path only, and neither
        // helper above can find it, so a finish/dismiss by stream
        // URL left it behind and re-downloading the talk
        // resurrected the old minute (the v8.30 class, one step
        // further out). See talkPosRemovePathCandidates below.
        talkPosRemovePathCandidates(c, url);
    }

    // v11.83: drop path-form resume spots for a stream URL whose
    // file has no dlMap link. Candidate basenames are the
    // offlineFileFor set (base / stripped / decoded); a basename
    // another URL claims in dlMap is skipped (the v8.50
    // exactly-one rule, mirroring localPathFor's
    // fileClaimedByOther guard). Same-file identity: two talks
    // sharing one basename in one directory ARE one file on disk,
    // so the spot belongs to that file — removing it when any of
    // its talks finishes matches the app's file-identity model
    // everywhere else. Stream URLs only: a path argument's own
    // line is already removed by talkPosRemove above.
    private static void talkPosRemovePathCandidates(Context c, String url) {
        if (url == null || url.startsWith("/")) return;
        try {
            String base = url.substring(url.lastIndexOf('/') + 1);
            String stripped = base;
            int q = stripped.indexOf('?');
            if (q >= 0) stripped = stripped.substring(0, q);
            int h = stripped.indexOf('#');
            if (h >= 0) stripped = stripped.substring(0, h);
            String decoded = stripped;
            try { decoded = java.net.URLDecoder.decode(stripped.replace("+", "%2B"), "UTF-8"); }
            catch (Exception ignored) {}
            java.util.Set<String> cand = new java.util.HashSet<String>();
            cand.add(base); cand.add(stripped); cand.add(decoded);
            org.json.JSONObject map = new org.json.JSONObject(
                    sp(c).getString("dlMap", "{}"));
            java.util.Set<String> claimedByOther = new java.util.HashSet<String>();
            java.util.Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!k.equals(url)) claimedByOther.add(map.optString(k, ""));
            }
            String dhammaDir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma").getAbsolutePath() + "/";
            String raw = sp(c).getString(TALK_POS_KEY, "");
            if (raw.length() == 0) return;
            StringBuilder sb = new StringBuilder();
            boolean changed = false;
            for (String line : raw.split("\n")) {
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                String key = line.substring(0, t);
                if (key.startsWith(dhammaDir)) {
                    String fn = key.substring(dhammaDir.length());
                    if (cand.contains(fn) && !claimedByOther.contains(fn)) {
                        changed = true;
                        continue;
                    }
                }
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            if (changed) {
                if (sb.length() == 0) sp(c).edit().remove(TALK_POS_KEY).apply();
                else sp(c).edit().putString(TALK_POS_KEY, sb.toString()).apply();
            }
        } catch (Exception ignored) {}
    }

    public static void talkPosRemove(Context c, String url) {
        if (url == null || url.length() == 0) return;
        try {
            String raw = sp(c).getString(TALK_POS_KEY, "");
            if (raw.length() == 0) return;
            StringBuilder sb = new StringBuilder();
            for (String line : raw.split("\n")) {
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                if (line.substring(0, t).equals(url)) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            if (sb.length() == 0) sp(c).edit().remove(TALK_POS_KEY).apply();
            else sp(c).edit().putString(TALK_POS_KEY, sb.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static void setAutoplay(Context c, boolean v) {
        sp(c).edit().putBoolean("autoplay", v).apply();
    }

    public static boolean backgroundEnabled(Context c) {
        return sp(c).getBoolean("backgroundEnabled", true);
    }

    public static void setBackgroundEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("backgroundEnabled", v).apply();
    }

    // v1.83: favFirst / sayadawSortAlpha removed — their main-screen
    // toggles died with the v1.25 minimal main, Settings never had
    // controls for them, and nothing has read either flag since, so
    // reset-all was "resetting" settings that did nothing.
    public static float defaultSpeed(Context c) {
        // v11.91: clamp to the speed cycle's 0.5–2.0 range — a
        // corrupt stored value (e.g. 0) used to throw inside
        // PlaybackParams.setSpeed (swallowed) so the talk played
        // at 1x while the Room label displayed the stored value.
        // The range check also rejects NaN (no comparison holds).
        float v = sp(c).getFloat("defaultSpeed", 1.0f);
        return (v >= 0.5f && v <= 2.0f) ? v : 1.0f;
    }

    public static void setDefaultSpeed(Context c, float v) {
        sp(c).edit().putFloat("defaultSpeed", v).apply();
    }

    // v1.40: Room photo auto-change — on = a different shrine photo on
    // every talk change (the v1.39 behaviour); off = keep the photo the
    // Room opened with for the whole session.
    public static boolean autoTheme(Context c) {
        return sp(c).getBoolean("autoTheme", true);
    }

    public static void setAutoTheme(Context c, boolean v) {
        sp(c).edit().putBoolean("autoTheme", v).apply();
    }

    // v1.30: keep the screen on while a talk is playing (older users)
    public static boolean keepScreenOn(Context c) {
        return sp(c).getBoolean("keepScreenOn", false);
    }

    public static void setKeepScreenOn(Context c, boolean v) {
        sp(c).edit().putBoolean("keepScreenOn", v).apply();
    }

    // v1.48: sleep timer, in minutes — 0 = off. Restored as a Settings
    // choice: the per-session sleep button died with the old Talks
    // player (v1.31) and the Room lost its sleep button (v1.37), so
    // since then nothing called AudioService.setSleepMinutes at all.
    public static int sleepMinutes(Context c) {
        // v12.25: clamp to the pickers' domain — the last unclamped settings int
        // (v11.91 clamped fontSize/repeatMode/defaultSpeed, v11.89 dlSortMode,
        // v11.88 the font-picker index). Every writer stores only {0, 5, 15, 30,
        // 60} (both sleep pickers' vals; long-press/reset write 0), so any other
        // stored value is corrupt; the old raw read let it arm a timer neither
        // picker could show (checked stayed "off" while sync armed it — the v8.57
        // class) and let a negative value print a nonsense "-N min" button label.
        // An out-of-set value now reads as off (0, the default): nothing arms,
        // both pickers check "off", the button reads "off" — and no legitimate
        // stored value ever changes (the v1.56 cycle wrote exactly this set).
        // The stored value itself is left alone, like the v11.91 clamps.
        int v = sp(c).getInt("sleepMinutes", 0);
        return (v == 0 || v == 5 || v == 15 || v == 30 || v == 60) ? v : 0;
    }

    public static void setSleepMinutes(Context c, int v) {
        sp(c).edit().putInt("sleepMinutes", v).apply();
    }

    // v1.58: data saver — on mobile data, never stream a talk the
    // user has not downloaded without asking first (Wi-Fi + offline
    // files are always free to play).
    public static boolean dataSaver(Context c) {
        return sp(c).getBoolean("dataSaver", true);
    }

    public static void setDataSaver(Context c, boolean v) {
        sp(c).edit().putBoolean("dataSaver", v).apply();
    }

    // v5.33: strip clock mode — false = elapsed/total, true = remaining.
    // One shared flag so a flip on any strip carries to the other three.
    public static boolean stripClockRemaining(Context c) {
        return sp(c).getBoolean("stripClockRemaining", false);
    }

    public static void setStripClockRemaining(Context c, boolean v) {
        sp(c).edit().putBoolean("stripClockRemaining", v).apply();
    }

    // v1.52: sound design + vibration on/off (tap blip, start bell, taps)
    public static boolean fxEnabled(Context c) {
        return sp(c).getBoolean("fxEnabled", true);
    }

    public static void setFxEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("fxEnabled", v).apply();
    }

    // v1.47: downloaded-library sort choice, kept across reopenings.
    // v4.24: three states now — 0 latest-download first, 1 A-Z,
    // 2 largest file first. The old boolean pref migrates once:
    // a stored A-Z true becomes mode 1, anything else mode 0.
    public static int dlSortMode(Context c) {
        android.content.SharedPreferences s = sp(c);
        // v11.89: clamp to 0–2 — a corrupt stored value used to survive forever
        // (same class as the v11.88 font-picker clamp).
        if (s.contains("dlSortMode")) { int v = s.getInt("dlSortMode", 0); return v < 0 || v > 2 ? 0 : v; }
        return s.getBoolean("dlSortAZ", false) ? 1 : 0;
    }

    public static void setDlSortMode(Context c, int v) {
        sp(c).edit().putInt("dlSortMode", v).apply();
    }

    // v7.57: Room playing list + index for background auto-advance
    // (step 1: write-only this run — no reader yet, see
    // RoomActivity.savePlayList57). Lines "url<TAB>title", index
    // into that list, -1 when nothing is playing.
    // v11.83: the stored index is dead — no reader ever existed
    // (Prefs.playIndex had zero call sites; AudioService re-derives
    // the index by scanning the raw list), so only the list is
    // persisted now. The idx parameter stays for the callers.
    // v13.78 (audit-1364 content-change race): stamp every list write
    // with its wall time in the SAME apply, so the foreign-refresh
    // worker can tell a fresher write from a stale one.
    public static void savePlayList(Context c, String raw, int idx) {
        if (raw == null) raw = "";
        sp(c).edit().putString("playList57", raw)
                .putLong("playListWriteMs", System.currentTimeMillis()).apply();
    }

    // v13.78: wall time of the last savePlayList write, 0 when never written.
    public static long playListWriteMs(Context c) {
        return sp(c).getLong("playListWriteMs", 0);
    }

    public static String playListRaw(Context c) {
        return sp(c).getString("playList57", "");
    }

    // Main-list name size by font level
    public static float nameSize(Context c) {
        int f = fontSize(c);
        return f == 0 ? 9 : (f == 1 ? 11 : 14);
    }

    public static float groupSize(Context c) {
        int f = fontSize(c);
        return f == 0 ? 8 : (f == 1 ? 10 : 13);
    }

    // v12.64: bookmarks — premium backlog #1 (user order 2026-11-06):
    // mark an important minute inside a long talk, tap the mark to
    // jump straight back. One key holds every talk's marks: lines
    // "url\tposMs", most-recent first, capped at 200 lines (a talk's
    // marks count once per form, so this is ~100 talks; old marks
    // simply age out instead of growing prefs forever). Same line
    // format as talkPosMap (v5.34) and the same both-forms rule: a
    // mark is written under the stream URL AND the downloaded-file
    // path (talkPosOtherForm), so a talk bookmarked while streaming
    // still shows its marks when played offline, and vice versa. A
    // mark within 5s of an existing one is a duplicate — tapping
    // "bookmark" twice on the same minute must not stack two pins
    // on the wave. Pure cores below carry no Android types, so
    // sim1267 exercises them directly.
    // v12.72: dedup is against the MERGED list (both forms) — a
    // mark that lives under one form only (streamed before the
    // download, or a dual-claimed file) must still block a near
    // tap from the other form, or two pins stack ~3s apart on the
    // wave (sim1278). Pure cores below carry no Android types, so
    // sim1279 + AdTest1272 exercise them directly.
    private static final String BOOKMARK_KEY = "bookmarks64";
    private static final int BOOKMARK_CAP = 200;
    private static final int BOOKMARK_DEDUP_MS = 5000;

    static java.util.List<Integer> bookmarkListPure(String raw, String url) {
        java.util.List<Integer> out = new java.util.ArrayList<Integer>();
        if (raw == null || raw.length() == 0 || url == null || url.length() == 0) return out;
        for (String line : raw.split("\n")) {
            int t = line.indexOf('\t');
            if (t <= 0) continue;
            if (!line.substring(0, t).equals(url)) continue;
            try {
                int pos = Integer.parseInt(line.substring(t + 1));
                if (pos > 0 && !out.contains(Integer.valueOf(pos))) out.add(Integer.valueOf(pos));
            } catch (Exception ignored) {}
        }
        java.util.Collections.sort(out);
        return out;
    }

    // Returns the new raw store, or null when nothing changes
    // (invalid input, or a mark within DEDUP_MS already exists).
    static String bookmarkAddPure(String raw, String url, int pos) {
        if (url == null || url.length() == 0 || pos <= 0) return null;
        if (url.indexOf('\t') >= 0 || url.indexOf('\n') >= 0 || url.indexOf('\r') >= 0) return null;
        java.util.List<Integer> cur = bookmarkListPure(raw, url);
        for (int m : cur) if (Math.abs(m - pos) < BOOKMARK_DEDUP_MS) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(url).append('\t').append(pos);
        int count = 1;
        if (raw != null && raw.length() > 0) {
            for (String line : raw.split("\n")) {
                if (count >= BOOKMARK_CAP) break;
                if (line.length() == 0) continue;
                sb.append('\n').append(line);
                count++;
            }
        }
        return sb.toString();
    }

    // Returns the new raw store, or null when nothing changes
    // (no mark at exactly this position under this URL).
    static String bookmarkRemovePure(String raw, String url, int pos) {
        if (raw == null || raw.length() == 0 || url == null || url.length() == 0) return null;
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        for (String line : raw.split("\n")) {
            int t = line.indexOf('\t');
            if (t <= 0) continue;
            if (line.substring(0, t).equals(url)) {
                try {
                    if (Integer.parseInt(line.substring(t + 1)) == pos) { changed = true; continue; }
                } catch (Exception ignored) {}
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        if (!changed) return null;
        return sb.toString();
    }

    // Marks for a talk, both forms merged, ascending, exact-deduped.
    // Pure core: other is the talk's other form (talkPosOtherForm),
    // or null when it has none.
    static java.util.List<Integer> bookmarkListBothPure(String raw, String url, String other) {
        java.util.List<Integer> out = new java.util.ArrayList<Integer>();
        if (url == null || url.length() == 0) return out;
        out.addAll(bookmarkListPure(raw, url));
        if (other != null && other.length() > 0) {
            for (int m : bookmarkListPure(raw, other))
                if (!out.contains(Integer.valueOf(m))) out.add(Integer.valueOf(m));
        }
        java.util.Collections.sort(out);
        return out;
    }

    // v12.72: dedup against the MERGED list — refuse when any mark
    // under EITHER form is within DEDUP_MS of the new one, so a
    // single-form mark (streamed before the download, or a
    // dual-claimed file) still blocks a near tap from the other
    // form. Returns the new raw store, or null when nothing
    // changes (invalid input, or a near mark under either form).
    // The mirror add below can then no longer fail on a near mark,
    // so a half-mirrored write is structurally closed.
    static String bookmarkAddBothPure(String raw, String url, String other, int pos) {
        if (url == null || url.length() == 0 || pos <= 0) return null;
        for (int m : bookmarkListBothPure(raw, url, other))
            if (Math.abs(m - pos) < BOOKMARK_DEDUP_MS) return null;
        String next = bookmarkAddPure(raw, url, pos);
        if (next == null) return null;
        if (other != null && other.length() > 0) {
            String next2 = bookmarkAddPure(next, other, pos);
            if (next2 != null) next = next2;
        }
        return next;
    }

    // Marks for a talk, both forms merged, ascending, exact-deduped.
    public static java.util.List<Integer> bookmarkListBoth(Context c, String url) {
        java.util.List<Integer> out = new java.util.ArrayList<Integer>();
        if (url == null || url.length() == 0) return out;
        try {
            String raw = sp(c).getString(BOOKMARK_KEY, "");
            out.addAll(bookmarkListBothPure(raw, url, talkPosOtherForm(c, url)));
        } catch (Exception ignored) {}
        return out;
    }

    // Writes under both forms; true when a new mark was stored.
    public static boolean bookmarkAddBoth(Context c, String url, int pos) {
        if (url == null || url.length() == 0 || pos <= 0) return false;
        try {
            String raw = sp(c).getString(BOOKMARK_KEY, "");
            String next = bookmarkAddBothPure(raw, url, talkPosOtherForm(c, url), pos);
            if (next == null) return false;
            sp(c).edit().putString(BOOKMARK_KEY, next).apply();
            return true;
        } catch (Exception ignored) { return false; }
    }

    // Removes under both forms (each form checked independently —
    // a mark added before its file existed lives under one form
    // only, and must still delete from the other form's read).
    public static void bookmarkRemoveBoth(Context c, String url, int pos) {
        if (url == null || url.length() == 0) return;
        try {
            String raw = sp(c).getString(BOOKMARK_KEY, "");
            String next = bookmarkRemovePure(raw, url, pos);
            String other = talkPosOtherForm(c, url);
            if (other != null) {
                String base = next == null ? raw : next;
                String next2 = bookmarkRemovePure(base, other, pos);
                if (next2 != null) next = next2;
            }
            if (next == null) return;
            if (next.length() == 0) sp(c).edit().remove(BOOKMARK_KEY).apply();
            else sp(c).edit().putString(BOOKMARK_KEY, next).apply();
        } catch (Exception ignored) {}
    }

    // v12.65: personal playlists — premium backlog #2 (user order 2026-11-06):
    // group favourite talks into the user's own lists (e.g. morning /
    // bedtime) that auto-advance through the list. One key holds every
    // list: "LIST\t<name>" headers with "ITEM\t<url>\t<sayadaw>\t<title>"
    // lines beneath, in list order (same line discipline as talkPosMap
    // v5.34 and bookmarks64 v12.64 — control chars stripped on write,
    // a malformed line skipped, never fatal). Two lists are seeded on
    // first read ("မနက်" morning, "အိပ်ခါနီး" bedtime — the backlog's
    // own examples), so the screen is never empty; deleting every
    // list leaves an empty store and never re-seeds. Playback rides
    // the hand queue, not a new player: PlaylistActivity writes the
    // list's v2 lines into the first talk's saved queue and opens
    // its Room, so the tested advance (advanceQueueHead63 +
    // playForeignHead11) walks the list across sayadaws, and the
    // Room's own play pops the head exactly like a queued talk.
    // Pure cores below carry no Android types, so sim1268 exercises
    // them directly.
    private static final String PLAYLIST_KEY = "playlists65";
    private static final int PLAYLIST_MAX_LISTS = 20;
    private static final int PLAYLIST_MAX_ITEMS = 200;
    private static final int PLAYLIST_MAX_NAME = 40;
    private static final String PLAYLIST_SEED = "LIST\tမနက်\nLIST\tအိပ်ခါနီး";

    private static String playlistClean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    static java.util.List<String> playlistNamesPure(String raw) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (raw == null || raw.length() == 0) return out;
        for (String line : raw.split("\n")) {
            if (!line.startsWith("LIST\t")) continue;
            String name = playlistClean(line.substring(5));
            if (name.length() == 0 || out.contains(name)) continue;
            out.add(name);
        }
        return out;
    }

    // v12.79: the last path segment of a URL or file path — the
    // cross-form signal for the playlist both-forms rule below.
    static String playlistFileName(String url) {
        if (url == null) return "";
        int s = url.lastIndexOf('/');
        return s < 0 ? url : url.substring(s + 1);
    }

    // v12.79: two playlist URLs name the SAME talk when they are
    // the exact same string, or when one is a downloaded path and
    // the other is a stream URL with the same filename — the
    // cross-form mirror of the v12.72 bookmark rule. A path with
    // zero or two dlMap claimants (hand-placed file, or one
    // predating dlMap — the v11.83 case; dual-claimed file,
    // creatable by delivered v8.39) never normalises through
    // streamUrlFor, so the filename is the only signal tying it
    // to its stream form, and the same talk added once while
    // streaming and once while its file sounded landed TWICE in
    // one list (sim1291 B1/B2/B6). Stream-vs-stream rows with one
    // filename are NOT the same talk: two different talks can
    // share a filename (the dual-claim case), and only a
    // path/stream pair is one talk in two forms. A path whose
    // filename matches a DIFFERENT talk's stream URL is treated
    // as that talk — the same ambiguity the dlMap rule already
    // accepts for single-claimant files, and dual-claimed
    // filenames are legacy v8.39 artifacts.
    static boolean playlistSameTalkPure(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        boolean aPath = a.startsWith("/");
        boolean bPath = b.startsWith("/");
        if (aPath == bPath) return false;
        String fa = playlistFileName(a);
        String fb = playlistFileName(b);
        return fa.length() > 0 && fa.equals(fb);
    }

    static java.util.List<QueueEntry> playlistItemsPure(String raw, String name) {
        java.util.List<QueueEntry> out = new java.util.ArrayList<QueueEntry>();
        if (raw == null || raw.length() == 0) return out;
        name = playlistClean(name);
        if (name.length() == 0) return out;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        // v12.79: cross-form collapse — a store written before
        // this fix can hold one talk in both forms (sim1291 B);
        // the read shows it once, keeping the FIRST row, so the
        // list, its count and its queue never emit the talk
        // twice. Stream and path filenames are tracked apart so
        // two different stream rows sharing a filename are never
        // collapsed (see playlistSameTalkPure).
        java.util.Set<String> seenStreamFn = new java.util.HashSet<String>();
        java.util.Set<String> seenPathFn = new java.util.HashSet<String>();
        String cur = null;
        for (String line : raw.split("\n")) {
            if (line.startsWith("LIST\t")) { cur = playlistClean(line.substring(5)); continue; }
            if (!line.startsWith("ITEM\t")) continue;
            if (cur == null || !cur.equals(name)) continue;
            String rest = line.substring(5);
            int t1 = rest.indexOf('\t');
            if (t1 <= 0) continue;
            String url = rest.substring(0, t1);
            if (url.length() == 0 || seen.contains(url)) continue;
            boolean isPath = url.startsWith("/");
            String fn = playlistFileName(url);
            if (fn.length() > 0
                    && (isPath ? seenStreamFn.contains(fn) : seenPathFn.contains(fn))) continue;
            String rest2 = rest.substring(t1 + 1);
            int t2 = rest2.indexOf('\t');
            String sd = t2 < 0 ? rest2 : rest2.substring(0, t2);
            String ti = t2 < 0 ? "" : rest2.substring(t2 + 1);
            seen.add(url);
            if (fn.length() > 0) {
                if (isPath) seenPathFn.add(fn); else seenStreamFn.add(fn);
            }
            out.add(new QueueEntry(url, sd, ti));
        }
        return out;
    }

    // Returns the new raw store, or null when nothing changes
    // (invalid name, duplicate, or the 20-list cap).
    static String playlistCreatePure(String raw, String name) {
        String clean = playlistClean(name);
        if (clean.length() == 0 || clean.length() > PLAYLIST_MAX_NAME) return null;
        java.util.List<String> names = playlistNamesPure(raw);
        if (names.contains(clean)) return null;
        if (names.size() >= PLAYLIST_MAX_LISTS) return null;
        StringBuilder sb = new StringBuilder();
        if (raw != null && raw.length() > 0) { sb.append(raw); sb.append('\n'); }
        sb.append("LIST\t").append(clean);
        return sb.toString();
    }

    // Returns the new raw store, or null when nothing changes
    // (no such list). The list's ITEM lines go with it; other
    // lists are untouched.
    static String playlistDeletePure(String raw, String name) {
        if (raw == null || raw.length() == 0) return null;
        name = playlistClean(name);
        if (name.length() == 0) return null;
        boolean found = false;
        StringBuilder sb = new StringBuilder();
        String cur = null;
        for (String line : raw.split("\n")) {
            if (line.startsWith("LIST\t")) {
                cur = playlistClean(line.substring(5));
                if (cur.equals(name)) { found = true; continue; }
            }
            if (found && name.equals(cur)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        if (!found) return null;
        return sb.toString();
    }

    // Returns the new raw store, or null when nothing changes (no
    // such list, invalid entry, duplicate URL, or the 200-item
    // cap). The new ITEM lands at the end of this list's block,
    // so list order is the order items were added.
    static String playlistAddPure(String raw, String name, QueueEntry e) {
        if (e == null || e.url == null || e.url.length() == 0) return null;
        if (e.url.indexOf('\t') >= 0 || e.url.indexOf('\n') >= 0 || e.url.indexOf('\r') >= 0) return null;
        name = playlistClean(name);
        if (name.length() == 0) return null;
        if (!playlistNamesPure(raw).contains(name)) return null;
        java.util.List<QueueEntry> cur = playlistItemsPure(raw, name);
        // v12.79: refuse when EITHER form of the incoming talk is
        // already in the list (sim1291 B) — the v12.72 bookmark
        // mirror. Exact-string only used to let a zero/two-claimant
        // path duplicate its own stream row.
        for (QueueEntry q : cur) if (playlistSameTalkPure(q.url, e.url)) return null;
        if (cur.size() >= PLAYLIST_MAX_ITEMS) return null;
        String itemLine = "ITEM\t" + e.url + "\t" + playlistClean(e.sayadaw)
                + "\t" + playlistClean(e.title);
        String[] lines = (raw == null || raw.length() == 0)
                ? new String[0] : raw.split("\n");
        StringBuilder sb = new StringBuilder();
        String curName = null;
        boolean inserted = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("LIST\t")) curName = playlistClean(line.substring(5));
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
            // End of this list's block: the next line starts a new
            // list, or this is the last line — insert here (after
            // the LIST line when the list is empty, after the last
            // ITEM otherwise). A line of another list never matches.
            if (!inserted && name.equals(curName)
                    && (i + 1 >= lines.length || lines[i + 1].startsWith("LIST\t"))) {
                sb.append('\n').append(itemLine);
                inserted = true;
            }
        }
        if (!inserted) return null;
        return sb.toString();
    }

    // Returns the new raw store, or null when nothing changes
    // (no such item in this list).
    static String playlistRemovePure(String raw, String name, String url) {
        if (raw == null || raw.length() == 0 || url == null || url.length() == 0) return null;
        name = playlistClean(name);
        if (name.length() == 0) return null;
        StringBuilder sb = new StringBuilder();
        String cur = null;
        boolean changed = false;
        for (String line : raw.split("\n")) {
            if (line.startsWith("LIST\t")) { cur = playlistClean(line.substring(5)); }
            else if (line.startsWith("ITEM\t") && name.equals(cur)) {
                String rest = line.substring(5);
                int t = rest.indexOf('\t');
                String u = t < 0 ? rest : rest.substring(0, t);
                // v12.79: one remove tap clears the talk in BOTH
                // forms (sim1291 B5) — the v12.72 bookmark mirror.
                // A store written before this fix holds the dup;
                // removing the visible row must not leave the twin
                // behind to reappear as the same talk still listed.
                if (playlistSameTalkPure(u, url)) { changed = true; continue; }
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        if (!changed) return null;
        return sb.toString();
    }

    // The store, seeded with the two backlog lists on first read
    // only: a key that exists (even empty, after deleting every
    // list) is never re-seeded.
    public static String playlistRaw(Context c) {
        try {
            String raw = sp(c).getString(PLAYLIST_KEY, null);
            if (raw == null) {
                sp(c).edit().putString(PLAYLIST_KEY, PLAYLIST_SEED).apply();
                return PLAYLIST_SEED;
            }
            return raw;
        } catch (Exception ignored) { return PLAYLIST_SEED; }
    }

    public static java.util.List<String> playlistNames(Context c) {
        return playlistNamesPure(playlistRaw(c));
    }

    public static java.util.List<QueueEntry> playlistItems(Context c, String name) {
        return playlistItemsPure(playlistRaw(c), name);
    }

    public static boolean playlistCreate(Context c, String name) {
        try {
            String next = playlistCreatePure(playlistRaw(c), name);
            if (next == null) return false;
            sp(c).edit().putString(PLAYLIST_KEY, next).apply();
            return true;
        } catch (Exception ignored) { return false; }
    }

    public static boolean playlistDelete(Context c, String name) {
        try {
            String next = playlistDeletePure(playlistRaw(c), name);
            if (next == null) return false;
            sp(c).edit().putString(PLAYLIST_KEY, next).apply();
            return true;
        } catch (Exception ignored) { return false; }
    }

    // 0 added, 1 already in the list, -1 could not add,
    // -2 list full (200 talks).
    public static int playlistAdd(Context c, String name, String url, String sayadaw, String title) {
        if (name == null || url == null || url.length() == 0) return -1;
        try {
            String stream = streamUrlFor(c, url);
            String next = playlistAddPure(playlistRaw(c), name,
                    new QueueEntry(stream, sayadaw, title));
            if (next == null) {
                // Distinguish "already there" from a real failure
                // so the caller toasts the right thing. The check
                // is the same both-forms rule as the add (v12.79):
                // a path refused against its stream row is
                // already there, not a failure.
                for (QueueEntry q : playlistItems(c, name))
                    if (playlistSameTalkPure(q.url, stream)) return 1;
                // v12.80: a full list is its own code (sim1293 B)
                // — the callers used to toast a play failure for
                // it. The size check reads the same collapsed list
                // the add itself refused against.
                if (playlistNamesPure(playlistRaw(c)).contains(
                        playlistClean(name))
                        && playlistItems(c, name).size() >= PLAYLIST_MAX_ITEMS) return -2;
                return -1;
            }
            sp(c).edit().putString(PLAYLIST_KEY, next).apply();
            return 0;
        } catch (Exception ignored) { return -1; }
    }

    public static boolean playlistRemove(Context c, String name, String url) {
        if (name == null || url == null) return false;
        try {
            String next = playlistRemovePure(playlistRaw(c), name, url);
            if (next == null) return false;
            sp(c).edit().putString(PLAYLIST_KEY, next).apply();
            return true;
        } catch (Exception ignored) { return false; }
    }

    // v2 queue lines for playback, from fromIdx on — the hand-queue
    // form the Room + service advance already walk (QueueEntry.format).
    public static java.util.ArrayList<String> playlistQueueLines(Context c, String name, int fromIdx) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        java.util.List<QueueEntry> items = playlistItems(c, name);
        for (int i = Math.max(0, fromIdx); i < items.size(); i++) {
            String line = QueueEntry.format(items.get(i));
            if (line.length() > 0) out.add(line);
        }
        return out;
    }

    // A downloaded file's stream URL (exactly-one dlMap claimant,
    // the talkPosOtherForm rule) — playlist items are stored as
    // stream URLs so the Room can match them to its loaded list
    // and the queue can find the downloaded file at play time; a
    // path with no claimant (or two) stays a path, exactly like
    // the resume spot's both-forms rule.
    public static String streamUrlFor(Context c, String url) {
        if (url == null || !url.startsWith("/")) return url;
        String other = talkPosOtherForm(c, url);
        return other != null ? other : url;
    }

    // v12.71: the owner of a sounding talk, resolved from the talk
    // itself — not from the session anchor. A library session never
    // writes lastName (it writes only lastKind / lastLibPath /
    // lastLibTitle), so banking, the widget and playlist-add all
    // attributed a library listen to the stale Room sayadaw (or to
    // no sayadaw at all), and a foreign head sounding in a Room
    // that had not handed over kept the Room's sayadaw. The fix
    // rule (sim1276's POST model, ported 1:1 in sim1277): a
    // library session resolves the sounding file's owner through
    // its stream URL + the download-time dlSayadaw record; a Room
    // session keeps lastName, which the service already moves on
    // foreign heads. Unresolvable falls back to lastName — never
    // worse than today, never a guessed name.
    static String streamUrlForPure(String url,
            java.util.Map<String, String> dlMap) {
        if (url == null) return null;
        if (!url.startsWith("/")) return url;
        // talkPosOtherForm's path branch, reduced to the map: the
        // filename's exactly-one dlMap claimant names the stream
        // URL; zero or two claimants stay a path, never a guess.
        String fn = new java.io.File(url).getName();
        String hit = null;
        int matches = 0;
        for (java.util.Map.Entry<String, String> e : dlMap.entrySet()) {
            if (e.getValue() != null && e.getValue().equals(fn)) {
                hit = e.getKey();
                matches++;
            }
        }
        return matches == 1 ? hit : url;
    }

    static String ownerForSoundingTalkPure(String kind, String soundingUrl,
            String lastName, java.util.Map<String, String> dlMap,
            java.util.Map<String, String> dlSayadaw) {
        if (lastName == null) lastName = "";
        if ("lib".equals(kind) && soundingUrl != null && soundingUrl.length() > 0) {
            String stream = streamUrlForPure(soundingUrl, dlMap);
            String owner = stream == null ? "" : dlSayadaw.get(stream);
            if (owner != null && owner.length() > 0) return owner;
            // v12.82 (sim1298): a path whose claimants AGREE on one
            // sayadaw names its owner even when the exact talk stays
            // ambiguous (dual claimants, v8.39) — the file belongs
            // to one sayadaw's talks, so the seconds belong to that
            // sayadaw even though no single stream URL can be picked
            // for the row. STRICT agreement: every claimant must
            // carry a dlSayadaw record and all must name the same
            // sayadaw; one unrecorded or dissenting claimant leaves
            // it unresolved — never a guessed name (the v8.43 rule).
            // A single claimant never reaches here (the lookup above
            // already returned it, or it has no record); zero
            // claimants find no entry at all.
            if (soundingUrl.startsWith("/") && dlMap != null && dlSayadaw != null) {
                String fn = new java.io.File(soundingUrl).getName();
                String agreed = null;
                boolean resolved = false;
                for (java.util.Map.Entry<String, String> e : dlMap.entrySet()) {
                    if (e.getValue() == null || !e.getValue().equals(fn)) continue;
                    String sd = dlSayadaw.get(e.getKey());
                    if (sd == null || sd.length() == 0) { resolved = false; break; }
                    if (agreed == null) { agreed = sd; resolved = true; }
                    else if (!agreed.equals(sd)) { resolved = false; break; }
                }
                if (resolved && agreed != null) return agreed;
            }
            // v12.82: a library session's file is not the Room's
            // talk — the stale Room lastName is never this file's
            // owner, so an unresolvable library listen banks under
            // "" (TOTAL/DAY still count it; no SAY line) instead of
            // the wrong sayadaw (the sim1297 B2b banking + the
            // wrong-Room open from History). Callers that pass ""
            // already see this; only flushListenStats passed a
            // non-empty lastName, and only for lib sessions.
            return "";
        }
        return lastName;
    }

    // v13.11: sayadawForTalk used to re-parse the whole dlMap +
    // dlSayadaw JSON out of SharedPreferences on EVERY call, and it
    // is called on the UI thread on every widget refresh / play /
    // pause / completion while a library session is live — a large
    // download library made every state edge pay a full JSON parse.
    // Cache the parsed maps keyed by the raw strings: a repeat call
    // with unchanged prefs is a cheap String.equals, and any writer
    // (Fx / Downloaded / AutoDownloadJob / Room / Talks / the v12.43
    // migration) invalidates it for free, because the raw string it
    // wrote differs. The cached maps are never mutated — the only
    // consumer, ownerForSoundingTalkPure, reads them (get/iterate).
    // Synchronized: writers run on background threads
    // (AutoDownloadJob) while readers run on the UI thread.
    private static String cachedDlMapRaw = null;
    private static java.util.Map<String, String> cachedDlMap = null;
    private static String cachedDlSayRaw = null;
    private static java.util.Map<String, String> cachedDlSay = null;

    private static java.util.Map<String, String> dlMapCached(
            android.content.SharedPreferences s) {
        String raw = s.getString("dlMap", "{}");
        synchronized (Prefs.class) {
            if (raw.equals(cachedDlMapRaw) && cachedDlMap != null) return cachedDlMap;
            java.util.Map<String, String> m = new java.util.HashMap<String, String>();
            try {
                org.json.JSONObject j = new org.json.JSONObject(raw);
                java.util.Iterator<String> k = j.keys();
                while (k.hasNext()) {
                    String key = k.next();
                    m.put(key, j.optString(key, ""));
                }
            } catch (Exception ignored) {}
            cachedDlMapRaw = raw;
            cachedDlMap = m;
            return m;
        }
    }

    private static java.util.Map<String, String> dlSayCached(
            android.content.SharedPreferences s) {
        String raw = s.getString("dlSayadaw", "{}");
        synchronized (Prefs.class) {
            if (raw.equals(cachedDlSayRaw) && cachedDlSay != null) return cachedDlSay;
            java.util.Map<String, String> m = new java.util.HashMap<String, String>();
            try {
                org.json.JSONObject j = new org.json.JSONObject(raw);
                java.util.Iterator<String> k = j.keys();
                while (k.hasNext()) {
                    String key = k.next();
                    m.put(key, j.optString(key, ""));
                }
            } catch (Exception ignored) {}
            cachedDlSayRaw = raw;
            cachedDlSay = m;
            return m;
        }
    }

    // Context wrapper for the pure rule above: reads the two JSON
    // maps (dlMap stream -> filename, dlSayadaw stream -> sayadaw)
    // the same way stripFavUrl / stripLibSayadaw read them. "" when
    // nothing resolves — the caller keeps its fallback.
    public static String sayadawForTalk(Context c, String kind,
            String soundingUrl, String lastName) {
        if (c == null) return lastName == null ? "" : lastName;
        try {
            android.content.SharedPreferences s = sp(c);
            java.util.Map<String, String> dlMap = dlMapCached(s);
            java.util.Map<String, String> dlSay = dlSayCached(s);
            return ownerForSoundingTalkPure(kind, soundingUrl, lastName, dlMap, dlSay);
        } catch (Exception ignored) {
            return lastName == null ? "" : lastName;
        }
    }

    // v12.66: listening history / stats — premium backlog #3 (user
    // order 2026-11-06): total hours listened, day streak,
    // most-listened sayadaw, recent talks. One key holds everything:
    // "TOTAL\t<sec>" (all-time listened seconds),
    // "DAY\t<yyyy-MM-dd>\t<sec>" (one line per day, for the streak),
    // "SAY\t<sayadaw>\t<sec>" (one line per sayadaw, for the top
    // sayadaw), "REC\t<url>\t<sayadaw>\t<title>\t<sec>\t<lastMs>"
    // (recent talks, most-recent first, cap 30). Same line discipline
    // as talkPosMap v5.34 / bookmarks64 v12.64 / playlists65 v12.65
    // — control chars stripped on write, a malformed line skipped,
    // never fatal. A day counts toward the streak at >= 60 listened
    // seconds. The store is re-emitted canonically on every add
    // (TOTAL, DAYs oldest-first, SAYs most-listened first, RECs
    // most-recent first), so a hand-edited or older store heals
    // itself on the next listen. Pure cores below carry no Android
    // types, so sim1269 exercises them directly.
    private static final String STATS_KEY = "listenStats66";
    private static final int STATS_MAX_RECENT = 30;
    private static final int STATS_MAX_DAYS = 400;
    private static final int STATS_MAX_SAYADAWS = 100;
    private static final int STATS_STREAK_MIN_SEC = 60;

    public static class HistEntry {
        public String url, sayadaw, title;
        public long seconds, lastMs;
        public HistEntry(String url, String sayadaw, String title,
                long seconds, long lastMs) {
            this.url = url == null ? "" : url;
            this.sayadaw = sayadaw == null ? "" : sayadaw;
            this.title = title == null ? "" : title;
            this.seconds = seconds;
            this.lastMs = lastMs;
        }
    }

    // "yyyy-MM-dd" only (length 10, dashes at 4 and 7, digits
    // elsewhere, month 1-12, day 1-31). Deliberately light: the
    // day string always comes from statsDayString's formatter, so
    // a stricter calendar check would only duplicate it.
    static boolean statsDayValidPure(String day) {
        if (day == null || day.length() != 10) return false;
        if (day.charAt(4) != '-' || day.charAt(7) != '-') return false;
        for (int i = 0; i < 10; i++) {
            if (i == 4 || i == 7) continue;
            char c = day.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        int m = Integer.parseInt(day.substring(5, 7));
        int d = Integer.parseInt(day.substring(8, 10));
        return m >= 1 && m <= 12 && d >= 1 && d <= 31;
    }

    static int statsDaysInMonthPure(int y, int m) {
        if (m == 4 || m == 6 || m == 9 || m == 11) return 30;
        if (m == 2) {
            boolean leap = (y % 4 == 0) && (y % 100 != 0 || y % 400 == 0);
            return leap ? 29 : 28;
        }
        return 31;
    }

    // The calendar day before the given one, or null for an
    // invalid input. Manual month math (no Calendar) so the sim
    // mirrors it exactly. v13.51: Locale.US — the bare %02d
    // followed the phone language, so on a Burmese phone the key
    // came back in Myanmar digits and never matched the ASCII
    // store, freezing the History day-streak at 1.
    static String statsPrevDayPure(String day) {
        if (!statsDayValidPure(day)) return null;
        int y = Integer.parseInt(day.substring(0, 4));
        int m = Integer.parseInt(day.substring(5, 7));
        int d = Integer.parseInt(day.substring(8, 10));
        d--;
        if (d < 1) {
            m--;
            if (m < 1) { m = 12; y--; }
            d = statsDaysInMonthPure(y, m);
        }
        return String.format(java.util.Locale.US, "%04d-%02d-%02d",
                Integer.valueOf(y), Integer.valueOf(m), Integer.valueOf(d));
    }

    // ASCII digits to Myanmar digits (U+1040-U+1049); every other
    // character passes through unchanged. The History screen applies
    // it only when the UI language is Burmese — other languages
    // keep Latin digits, like the app's timers.
    static String statsDigitsPure(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append((char) ('\u1040' + (c - '0')));
            else sb.append(c);
        }
        return sb.toString();
    }

    static long statsTotalPure(String raw) {
        if (raw == null || raw.length() == 0) return 0;
        for (String line : raw.split("\n")) {
            if (!line.startsWith("TOTAL\t")) continue;
            try {
                return Math.max(0, Long.parseLong(line.substring(6).trim()));
            } catch (Exception ignored) { return 0; }
        }
        return 0;
    }

    static long statsDaySecondsPure(String raw, String day) {
        if (raw == null || raw.length() == 0) return 0;
        if (!statsDayValidPure(day)) return 0;
        long out = 0;
        for (String line : raw.split("\n")) {
            if (!line.startsWith("DAY\t")) continue;
            String rest = line.substring(4);
            int t = rest.indexOf('\t');
            if (t <= 0) continue;
            if (!day.equals(rest.substring(0, t))) continue;
            try { out += Math.max(0, Long.parseLong(rest.substring(t + 1).trim())); }
            catch (Exception ignored) {}
        }
        return out;
    }

    // Consecutive qualifying days ending today — or ending
    // yesterday when today has not qualified yet (a streak does
    // not die at midnight before the first listen of the day).
    static int statsStreakPure(String raw, String today) {
        if (!statsDayValidPure(today)) return 0;
        String d = today;
        if (statsDaySecondsPure(raw, d) < STATS_STREAK_MIN_SEC)
            d = statsPrevDayPure(d);
        int n = 0;
        while (d != null
                && statsDaySecondsPure(raw, d) >= STATS_STREAK_MIN_SEC) {
            n++;
            d = statsPrevDayPure(d);
        }
        return n;
    }

    // {name, seconds} of the most-listened sayadaw, or null when
    // nothing has been listened to yet. Ties keep the first SAY
    // line in the store (most-listened first after a re-emit).
    static String[] statsTopSayadawPure(String raw) {
        if (raw == null || raw.length() == 0) return null;
        String best = null;
        long bestSec = 0;
        for (String line : raw.split("\n")) {
            if (!line.startsWith("SAY\t")) continue;
            String rest = line.substring(4);
            int t = rest.indexOf('\t');
            if (t <= 0) continue;
            String name = playlistClean(rest.substring(0, t));
            if (name.length() == 0) continue;
            long v;
            try { v = Long.parseLong(rest.substring(t + 1).trim()); }
            catch (Exception ignored) { continue; }
            if (v > bestSec) { bestSec = v; best = name; }
        }
        if (best == null) return null;
        return new String[]{best, Long.toString(bestSec)};
    }

    static java.util.List<HistEntry> statsRecentPure(String raw) {
        java.util.List<HistEntry> out = new java.util.ArrayList<HistEntry>();
        if (raw == null || raw.length() == 0) return out;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (String line : raw.split("\n")) {
            if (!line.startsWith("REC\t")) continue;
            String rest = line.substring(4);
            int t1 = rest.indexOf('\t');
            if (t1 <= 0) continue;
            String url = rest.substring(0, t1);
            if (url.length() == 0 || seen.contains(url)) continue;
            String rest2 = rest.substring(t1 + 1);
            int t2 = rest2.indexOf('\t');
            if (t2 < 0) continue;
            String sd = playlistClean(rest2.substring(0, t2));
            String rest3 = rest2.substring(t2 + 1);
            int t3 = rest3.indexOf('\t');
            if (t3 < 0) continue;
            String ti = playlistClean(rest3.substring(0, t3));
            String rest4 = rest3.substring(t3 + 1);
            int t4 = rest4.indexOf('\t');
            if (t4 < 0) continue;
            long sec, last;
            try {
                sec = Long.parseLong(rest4.substring(0, t4).trim());
                last = Long.parseLong(rest4.substring(t4 + 1).trim());
            } catch (Exception ignored) { continue; }
            if (sec <= 0) continue;
            seen.add(url);
            out.add(new HistEntry(url, sd, ti, sec, last));
        }
        return out;
    }

    // Returns the new raw store, or null when nothing is recorded
    // (no seconds, empty/bad URL, or a malformed day string).
    static String statsAddPure(String raw, String dayStr, String sayadaw,
            String url, String title, long seconds, long nowMs) {
        if (seconds <= 0 || url == null || url.length() == 0) return null;
        if (url.indexOf('\t') >= 0 || url.indexOf('\n') >= 0
                || url.indexOf('\r') >= 0) return null;
        if (!statsDayValidPure(dayStr)) return null;
        sayadaw = playlistClean(sayadaw);
        title = playlistClean(title);
        long total = 0;
        java.util.Map<String, Long> days = new java.util.HashMap<String, Long>();
        java.util.Map<String, Long> says = new java.util.HashMap<String, Long>();
        java.util.List<HistEntry> recs = new java.util.ArrayList<HistEntry>();
        if (raw != null && raw.length() > 0) {
            for (String line : raw.split("\n")) {
                if (line.startsWith("TOTAL\t")) {
                    try { total = Math.max(0, Long.parseLong(line.substring(6).trim())); }
                    catch (Exception ignored) {}
                } else if (line.startsWith("DAY\t")) {
                    String rest = line.substring(4);
                    int t = rest.indexOf('\t');
                    if (t <= 0) continue;
                    String d = rest.substring(0, t);
                    if (!statsDayValidPure(d)) continue;
                    try {
                        long v = Long.parseLong(rest.substring(t + 1).trim());
                        if (v > 0) {
                            // Sum, like statsDaySecondsPure: a
                            // hand-edited store can hold two lines
                            // for one day, and both days listened.
                            Long old = days.get(d);
                            days.put(d, old == null ? v : old.longValue() + v);
                        }
                    } catch (Exception ignored) {}
                } else if (line.startsWith("SAY\t")) {
                    String rest = line.substring(4);
                    int t = rest.indexOf('\t');
                    if (t <= 0) continue;
                    String name = playlistClean(rest.substring(0, t));
                    if (name.length() == 0) continue;
                    try {
                        long v = Long.parseLong(rest.substring(t + 1).trim());
                        if (v > 0) {
                            Long old = says.get(name);
                            says.put(name, old == null ? v : old.longValue() + v);
                        }
                    } catch (Exception ignored) {}
                } else if (line.startsWith("REC\t")) {
                    java.util.List<HistEntry> one = statsRecentPure(line);
                    for (HistEntry h : one) {
                        // First-wins across lines, like
                        // statsRecentPure: a duplicated REC line in
                        // a hand-edited store never doubles a talk.
                        boolean dup = false;
                        for (HistEntry e : recs)
                            if (e.url.equals(h.url)) { dup = true; break; }
                        if (!dup) recs.add(h);
                    }
                }
            }
        }
        total += seconds;
        Long dayOld = days.get(dayStr);
        days.put(dayStr, dayOld == null ? seconds : dayOld.longValue() + seconds);
        if (sayadaw.length() > 0) {
            Long sayOld = says.get(sayadaw);
            says.put(sayadaw, sayOld == null ? seconds : sayOld.longValue() + seconds);
        }
        HistEntry found = null;
        for (HistEntry e : recs) {
            if (e.url.equals(url)) { found = e; break; }
        }
        if (found != null) {
            found.seconds += seconds;
            found.lastMs = nowMs;
            // v12.82 (sim1298): a re-listen refreshes the row's
            // title/sayadaw from the incoming values when they
            // are non-empty — a talk renamed on the site kept its
            // first listen's title in History forever, and a
            // renamed sayadaw kept the old name on the row. An
            // empty incoming value keeps the stored one, so an
            // unresolvable library listen never wipes a good name.
            if (title.length() > 0) found.title = title;
            if (sayadaw.length() > 0) found.sayadaw = sayadaw;
            recs.remove(found);
            recs.add(0, found);
        } else {
            recs.add(0, new HistEntry(url, sayadaw, title, seconds, nowMs));
        }
        if (recs.size() > STATS_MAX_RECENT)
            recs = recs.subList(0, STATS_MAX_RECENT);
        StringBuilder sb = new StringBuilder();
        sb.append("TOTAL\t").append(total);
        java.util.List<String> dayKeys = new java.util.ArrayList<String>(days.keySet());
        java.util.Collections.sort(dayKeys);
        if (dayKeys.size() > STATS_MAX_DAYS)
            dayKeys = dayKeys.subList(dayKeys.size() - STATS_MAX_DAYS, dayKeys.size());
        for (String d : dayKeys)
            sb.append('\n').append("DAY\t").append(d).append('\t').append(days.get(d));
        java.util.List<java.util.Map.Entry<String, Long>> sayList =
                new java.util.ArrayList<java.util.Map.Entry<String, Long>>(says.entrySet());
        java.util.Collections.sort(sayList,
                new java.util.Comparator<java.util.Map.Entry<String, Long>>() {
                    @Override public int compare(
                            java.util.Map.Entry<String, Long> a,
                            java.util.Map.Entry<String, Long> b) {
                        // Most-listened first; a tie breaks by name
                        // so the canonical order (and the top
                        // sayadaw) is deterministic, never HashMap
                        // order.
                        int bySec = Long.compare(b.getValue(), a.getValue());
                        return bySec != 0 ? bySec : a.getKey().compareTo(b.getKey());
                    }
                });
        int nSay = Math.min(sayList.size(), STATS_MAX_SAYADAWS);
        for (int i = 0; i < nSay; i++)
            sb.append('\n').append("SAY\t").append(sayList.get(i).getKey())
                    .append('\t').append(sayList.get(i).getValue());
        for (HistEntry e : recs)
            sb.append('\n').append("REC\t").append(e.url).append('\t')
                    .append(e.sayadaw).append('\t').append(e.title).append('\t')
                    .append(e.seconds).append('\t').append(e.lastMs);
        return sb.toString();
    }

    // Today's "yyyy-MM-dd" in the device's local timezone (the
    // streak is a calendar-day streak, so local days are the
    // honest unit — the same day the user sees on the phone).
    public static String statsDayString() {
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd",
                    java.util.Locale.US).format(new java.util.Date());
        } catch (Exception ignored) { return ""; }
    }

    public static String statsRaw(Context c) {
        try {
            String raw = sp(c).getString(STATS_KEY, null);
            return raw == null ? "" : raw;
        } catch (Exception ignored) { return ""; }
    }

    // Bank actually-listened seconds for one talk. The URL is
    // mapped to its stream form first (downloaded talks play from
    // a local path) so a recent row replays exactly like a
    // playlist item. Returns false when nothing was recorded.
    public static boolean statsAdd(Context c, String sayadaw, String url,
            String title, int seconds) {
        if (c == null || seconds <= 0) return false;
        try {
            String stream = streamUrlFor(c, url);
            String next = statsAddPure(statsRaw(c), statsDayString(),
                    sayadaw, stream, title, seconds,
                    System.currentTimeMillis());
            if (next == null) return false;
            sp(c).edit().putString(STATS_KEY, next).apply();
            return true;
        } catch (Exception ignored) { return false; }
    }

    public static long statsTotal(Context c) {
        try { return statsTotalPure(statsRaw(c)); }
        catch (Exception ignored) { return 0; }
    }

    public static int statsStreak(Context c) {
        try { return statsStreakPure(statsRaw(c), statsDayString()); }
        catch (Exception ignored) { return 0; }
    }

    // {name, seconds} of the most-listened sayadaw, or null.
    public static String[] statsTopSayadaw(Context c) {
        try { return statsTopSayadawPure(statsRaw(c)); }
        catch (Exception ignored) { return null; }
    }

    public static java.util.List<HistEntry> statsRecent(Context c) {
        try { return statsRecentPure(statsRaw(c)); }
        catch (Exception ignored) { return new java.util.ArrayList<HistEntry>(); }
    }

    public static void statsClear(Context c) {
        try { sp(c).edit().remove(STATS_KEY).apply(); }
        catch (Exception ignored) {}
    }

    // v12.68: Wi-Fi auto-download — premium backlog #5 (user order
    // 2026-11-06): new talks from ★-favourited sayadaws download
    // themselves on Wi-Fi, so the listener never has to chase a new
    // talk onto the phone. Default ON, like the data saver (v1.58):
    // the feature only ever spends Wi-Fi, never mobile data, so
    // there is nothing to opt into. One key holds every sayadaw's
    // seen talk URLs ("autoDlKnown67", lines "pageUrl\turl",
    // newest first, cap 5000 — a sayadaw's list is only ever
    // consulted for its own page, so a rename can never merge two
    // sayadaws' seen sets); a second key ("autoDlSeeded67",
    // StringSet of page URLs) records which sayadaws have been
    // seeded. SEED SEMANTICS: the first check for a sayadaw
    // records ALL its current talks as seen and enqueues NOTHING
    // — only talks appearing AFTER the seed ever auto-download, so
    // enabling the feature never triggers a bulk download of a
    // whole back catalogue. The pure cores below carry no Android
    // types, so sim1271 exercises them directly.
    private static final String AUTO_DL_KNOWN_KEY = "autoDlKnown67";
    private static final String AUTO_DL_SEEDED_KEY = "autoDlSeeded67";
    static final int AUTO_DL_MAX_PER_SAYADAW = 5;
    static final int AUTO_DL_MAX_TOTAL = 20;
    private static final int AUTO_DL_KNOWN_CAP = 5000;

    public static boolean autoDlEnabled(Context c) {
        return sp(c).getBoolean("autoDlEnabled", true);
    }

    public static void setAutoDlEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("autoDlEnabled", v).apply();
    }

    // Epoch ms of the last completed check, 0 when never checked.
    public static long autoDlLastRun(Context c) {
        return sp(c).getLong("autoDlLastRun", 0);
    }

    // New talks the last check downloaded, 0 when none / never.
    public static int autoDlLastNew(Context c) {
        return sp(c).getInt("autoDlLastNew", 0);
    }

    public static void autoDlSetLastRun(Context c, long runMs, int newCount) {
        sp(c).edit().putLong("autoDlLastRun", runMs)
                .putInt("autoDlLastNew", Math.max(0, newCount)).apply();
    }

    // v13.29 (A9): the last favourite the previous run CHECKED, "" when
    // none yet — the next run resumes AFTER it (wrap-around), so the
    // 20-per-run cap can no longer starve the alphabetically-last
    // favourites run after run. A removed favourite simply has no
    // name after it in the new sort; a new one slots in.
    public static String autoDlResumeAfter(Context c) {
        return sp(c).getString("autoDlResumeAfter68", "");
    }

    public static void autoDlSetResumeAfter(Context c, String name) {
        sp(c).edit().putString("autoDlResumeAfter68",
                name == null ? "" : name).apply();
    }

    // The catalog page URL for a sayadaw name (or one of its
    // aliases), "" when the name has no page — the v11.99 maps,
    // so a renamed sayadaw still resolves.
    public static String pageUrlForSayadaw(Context c, String name) {
        if (name == null || name.length() == 0) return "";
        loadCatalogMaps99(c);
        String page = sNameToPage99.get(name);
        if (page == null) page = sAliasToPage99.get(name);
        return page == null ? "" : page;
    }

    // A copy of the ★-favourited sayadaw names (never the live
    // set — callers iterate it while prefs may change).
    public static java.util.Set<String> favNames(Context c) {
        java.util.Set<String> f = sp(c).getStringSet("favs", null);
        return f == null ? new java.util.HashSet<String>()
                : new java.util.HashSet<String>(f);
    }

    public static String autoDlKnownRaw(Context c) {
        try {
            String raw = sp(c).getString(AUTO_DL_KNOWN_KEY, null);
            return raw == null ? "" : raw;
        } catch (Exception ignored) { return ""; }
    }

    public static boolean autoDlIsSeeded(Context c, String pageUrl) {
        if (pageUrl == null || pageUrl.length() == 0) return false;
        java.util.Set<String> s = sp(c).getStringSet(AUTO_DL_SEEDED_KEY, null);
        return s != null && s.contains(pageUrl);
    }

    public static void autoDlMarkSeeded(Context c, String pageUrl) {
        if (pageUrl == null || pageUrl.length() == 0) return;
        try {
            java.util.Set<String> s = sp(c).getStringSet(AUTO_DL_SEEDED_KEY, null);
            java.util.Set<String> copy = s == null
                    ? new java.util.HashSet<String>()
                    : new java.util.HashSet<String>(s);
            if (copy.add(pageUrl))
                sp(c).edit().putStringSet(AUTO_DL_SEEDED_KEY, copy).apply();
        } catch (Exception ignored) {}
    }

    // Seen URLs for one sayadaw's page, in store order, deduped.
    // Malformed lines (no tab, empty page or URL, wrong page) are
    // skipped, never fatal — a hand-edited store cannot break a
    // check. A URL under two pages is two independent facts: each
    // page's set is consulted alone, so the same talk listed by
    // two sayadaws is seeded/diffed per sayadaw.
    static java.util.Set<String> autoDlKnownForPure(String raw, String pageUrl) {
        java.util.Set<String> out = new java.util.LinkedHashSet<String>();
        if (raw == null || raw.length() == 0
                || pageUrl == null || pageUrl.length() == 0) return out;
        for (String line : raw.split("\n")) {
            int t = line.indexOf('\t');
            if (t <= 0) continue;
            if (!line.substring(0, t).equals(pageUrl)) continue;
            String url = line.substring(t + 1);
            if (url.length() == 0) continue;
            if (url.indexOf('\t') >= 0) continue;
            out.add(url);
        }
        return out;
    }

    public static java.util.Set<String> autoDlKnownFor(Context c, String pageUrl) {
        try { return autoDlKnownForPure(autoDlKnownRaw(c), pageUrl); }
        catch (Exception ignored) { return new java.util.LinkedHashSet<String>(); }
    }

    // New URLs to enqueue for one sayadaw: fresh-list order,
    // duplicates in the fresh list collapsed (first wins), known
    // URLs and already-downloaded-or-queued URLs skipped, capped
    // at AUTO_DL_MAX_PER_SAYADAW per run so one prolific sayadaw
    // can never burst the download queue. Null/empty inputs give
    // an empty list, never a crash.
    static java.util.List<String> autoDlDiffPure(
            java.util.List<String> fresh,
            java.util.Set<String> known,
            java.util.Set<String> downloadedOrQueued) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (fresh == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (String u : fresh) {
            if (out.size() >= AUTO_DL_MAX_PER_SAYADAW) break;
            if (u == null || u.length() == 0) continue;
            if (!seen.add(u)) continue;
            if (known != null && known.contains(u)) continue;
            if (downloadedOrQueued != null && downloadedOrQueued.contains(u)) continue;
            out.add(u);
        }
        return out;
    }

    // Record URLs as seen for one page. Returns the new raw store,
    // or null when nothing changes (every URL already seen, or no
    // usable URL at all). New lines go first (most-recent first,
    // like talkPosMap/bookmarks), valid old lines still on the page
    // follow in order — `catalogue` (the page's full current list,
    // null = unknown) prunes the stale ones (v12.77) — malformed
    // lines drop on the rewrite (the statsAddPure canonical
    // re-emit); the whole store is capped at AUTO_DL_KNOWN_CAP
    // lines so prefs never grows unbounded.
    static String autoDlAddKnownPure(String raw, String pageUrl,
            java.util.List<String> urls,
            java.util.List<String> catalogue) {
        if (pageUrl == null || pageUrl.length() == 0
                || urls == null || urls.isEmpty()) return null;
        java.util.Set<String> known = autoDlKnownForPure(raw, pageUrl);
        java.util.List<String> fresh = new java.util.ArrayList<String>();
        for (String u : urls) {
            if (u == null || u.length() == 0) continue;
            if (u.indexOf('\t') >= 0 || u.indexOf('\n') >= 0
                    || u.indexOf('\r') >= 0) continue;
            if (known.contains(u)) continue;
            if (fresh.contains(u)) continue;
            fresh.add(u);
            known.add(u);
        }
        if (fresh.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String u : fresh) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(pageUrl).append('\t').append(u);
        }
        int count = fresh.size();
        if (raw != null && raw.length() > 0) {
            for (String line : raw.split("\n")) {
                if (count >= AUTO_DL_KNOWN_CAP) break;
                int t = line.indexOf('\t');
                if (t <= 0) continue;
                String p = line.substring(0, t);
                String u = line.substring(t + 1);
                if (u.length() == 0 || u.indexOf('\t') >= 0) continue;
                // The page's own lines are already in `known`
                // (fresh ones above); any other page's line is
                // kept verbatim, and a duplicate line for this
                // page is kept once — the first occurrence wins.
                if (p.equals(pageUrl)) {
                    // Already emitted above as fresh, or an old
                    // duplicate of a known URL: skip, it is
                    // recorded exactly once.
                    continue;
                }
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
                count++;
            }
            // v12.77: the page's live set for the prune below (null
            // when the caller passed no catalogue — prune nothing).
            java.util.Set<String> live = catalogue == null ? null
                    : new java.util.HashSet<String>(catalogue);
            // Old lines for THIS page that were known but not
            // re-added above: they were skipped in the loop (the
            // page branch), so re-emit them here from the known
            // set minus the fresh ones — otherwise a seed would
            // forget every talk it had seen before.
            for (String u : autoDlKnownForPure(raw, pageUrl)) {
                if (count >= AUTO_DL_KNOWN_CAP) break;
                if (fresh.contains(u)) continue;
                // v12.77 prune-stale: a known URL no longer on the
                // page is a removed talk — drop it instead of
                // re-emitting, so dead lines stop eating cap room
                // and evicting this page's own live history.
                if (live != null && !live.contains(u)) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(pageUrl).append('\t').append(u);
                count++;
            }
        }
        return sb.toString();
    }

    // Write the pure-core result back. Returns false when nothing
    // was recorded.
    public static boolean autoDlAddKnown(Context c, String pageUrl,
            java.util.List<String> urls,
            java.util.List<String> catalogue) {
        if (c == null) return false;
        try {
            String next = autoDlAddKnownPure(autoDlKnownRaw(c), pageUrl,
                    urls, catalogue);
            if (next == null) return false;
            sp(c).edit().putString(AUTO_DL_KNOWN_KEY, next).apply();
            return true;
        } catch (Exception ignored) { return false; }
    }

    // v13.71 (audit-1367 F2 repair): drop URLs from the known set
    // for one page — the un-know half of the auto-download retry.
    // Returns the new raw store, or null when nothing changes (no
    // URL to drop, or none of them is known for this page). Only
    // this page's lines are touched; other pages' lines pass through
    // verbatim, and malformed lines drop on the rewrite exactly as
    // autoDlAddKnownPure already does.
    static String autoDlDropKnownPure(String raw, String pageUrl,
            java.util.List<String> urls) {
        if (pageUrl == null || pageUrl.length() == 0
                || urls == null || urls.isEmpty()) return null;
        if (raw == null || raw.length() == 0) return null;
        java.util.Set<String> drop = new java.util.HashSet<String>();
        for (String u : urls) {
            if (u != null && u.length() > 0) drop.add(u);
        }
        if (drop.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        for (String line : raw.split("\n")) {
            int t = line.indexOf('\t');
            if (t <= 0) continue;
            String p = line.substring(0, t);
            String u = line.substring(t + 1);
            if (p.equals(pageUrl) && drop.contains(u)) {
                changed = true;
                continue;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        if (!changed) return null;
        return sb.toString();
    }

    // Write the pure-core result back. Returns false when nothing
    // was dropped.
    public static boolean autoDlDropKnown(Context c, String pageUrl,
            java.util.List<String> urls) {
        if (c == null) return false;
        try {
            String next = autoDlDropKnownPure(autoDlKnownRaw(c), pageUrl, urls);
            if (next == null) return false;
            sp(c).edit().putString(AUTO_DL_KNOWN_KEY, next).apply();
            return true;
        } catch (Exception ignored) { return false; }
    }

    // v12.69: hand-queue WAITING count — the drawer-header rule
    // (sim1272 finding) as a pure core, so every ◈ reader counts
    // the same number the Room drawer header already shows:
    // parse each saved line, dedup by URL (a repeated URL is one
    // waiting talk, v11.04), skip the sounding talk (a queued URL
    // still saved when its talk starts, v5.80). Malformed lines
    // (empty URL) are skipped, never fatal. This is the COUNT
    // only: it cannot apply the ghost rule (a line whose talk
    // left the site list) — no list is in hand here. Ghosts are
    // shed from the store itself where the list IS known (Room
    // peek v5.73 + onPause save, chooser load prune v5.74 /
    // v12.69 below), so a clean store + this count agree with
    // the drawer everywhere. The lib branches keep their own
    // isFile filter (sim1272 case 9) and never call this.
    static int waitingQueueCountPure(String saved, String soundingUrl) {
        if (saved == null || saved.length() == 0) return 0;
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        int n = 0;
        for (String line : saved.split("\n")) {
            if (line == null) continue;
            QueueEntry e = QueueEntry.parse(line.trim());
            String u = e == null ? "" : e.url;
            if (u.length() == 0) continue;
            if (!seen.add(u)) continue;
            if (soundingUrl != null && soundingUrl.length() > 0
                    && u.equals(soundingUrl)) continue;
            n++;
        }
        return n;
    }

    // v12.69: stale-line predicate for a loaded list — the
    // display rule (drawerQueueDisplayLines + firstWaiting-
    // QueueEntry) restated as a pure check, so the Talks
    // chooser's load prune (v5.74) and this core can never
    // disagree about what is stale. A line is stale when its
    // URL is not in the fresh list AND it is not a foreign v2
    // line (another sayadaw's talk, kept by design since
    // v7.08/v7.09); the sounding talk is skipped, never stale
    // (Room peek v5.86 — only playIndex drops a played talk).
    // Unparseable lines are stale (the Room's peek drops them
    // too); an empty/failed list never prunes — the caller
    // gates on talks non-empty, so a load that found nothing
    // cannot wipe the queue on a guess. This predicate only
    // DECIDES; the write still goes through the chooser's
    // funnel, which carries foreign lines and refreshes the
    // card.
    static boolean queueHasStaleLinePure(String saved,
            java.util.Set<String> inListUrls, String sayadaw,
            String soundingUrl) {
        if (saved == null || saved.length() == 0) return false;
        for (String line : saved.split("\n")) {
            if (line == null || line.length() == 0) continue;
            QueueEntry e = QueueEntry.parse(line);
            String u = e == null ? line : e.url;
            if (u.length() == 0) continue;
            if (soundingUrl != null && soundingUrl.length() > 0
                    && u.equals(soundingUrl)) continue;
            if (inListUrls != null && inListUrls.contains(u)) continue;
            if (e != null && e.isV2()
                    && (sayadaw == null || !e.sayadaw.equals(sayadaw))) continue;
            return true;
        }
        return false;
    }
}
