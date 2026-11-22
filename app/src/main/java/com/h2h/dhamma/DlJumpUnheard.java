package com.h2h.dhamma;

// v15.58 (slice 2007): "Jump to the first unheard file" pick —
// the Downloaded library list's floating gold ↓ button, the
// library mirror of the Talks chooser's JumpUnheard (2005).
// The library had a floating ↑ (2003, back to top) and a
// floating ▶ (2002, jump to the playing file) but no visible
// way to the next fresh file: the Continue strip (v4.60)
// covers only the one in-progress talk and the unlistened-only
// filter (mode 2, v4.95) narrows the list instead of gliding
// within it. This picks that file: the first shown row that is
// neither finished, nor started past 5s, nor sounding — the
// 1920 dot gate (in the new-set, unfinished, unstarted, not
// sounding) minus the new-set membership, restated for the
// library's own machinery: finished is the file's NAME in
// libListenedFiles (the v4.24 per-load cache — playedTalks via
// the dlMap reverse lookup, canonical identityUrl, dual-
// claimant pruned v8.43), started is the file's PATH in
// libResumeMap (the v5.35 per-load cache — talkPosMap, path
// form first, stream form via the reverse lookup, same >5s
// rule as every other resume read), sounding is the exact
// shelf path libRowPlayingPath (the DlJumpPlaying world — the
// library queue holds paths, never URLs, so there are no
// http/https twins to normalise). Pure index math only: the
// Activity owns the list, the mark caches and the glide.
public class DlJumpUnheard {
    // The file name of an absolute shelf path (the
    // libListenedFiles key form), or "" for a null/blank path.
    // A bare name with no slash passes through unchanged.
    static String fileName(String path) {
        if (path == null || path.length() == 0) return "";
        int s = path.lastIndexOf('/');
        return s < 0 ? path : path.substring(s + 1);
    }

    // The display position of the first unheard file, in display
    // order, or -1 when none. `displayPaths` is parallel to the
    // shown list (absolute shelf paths, null where a row has
    // none); `listenedNames` holds the finished files' NAMES
    // (the libListenedFiles key form — a path under another
    // directory with the same name still matches, because the
    // library's own mark is by name); `resumeMsByPath` maps
    // absolute path to the saved spot in ms (the libResumeMap
    // key form — a spot banked under another spelling does not
    // match, exactly as the row's own ↺ renders it);
    // `soundingPath` is the sounding file's absolute path
    // (exact equality — a streamed Room-session talk has no
    // shelf row, so its URL form never matches a path).
    // Skips null/blank paths, finished files, started files
    // (resume over PlayNewPick.MIN_RESUME_MS, 5000 — the
    // getView ↺ threshold verbatim), and the sounding file.
    // The input lists are never mutated.
    static int firstIndex(java.util.List<String> displayPaths,
            java.util.Set<String> listenedNames,
            java.util.Map<String, Integer> resumeMsByPath,
            String soundingPath) {
        if (displayPaths == null) return -1;
        for (int i = 0; i < displayPaths.size(); i++) {
            String p = displayPaths.get(i);
            if (p == null || p.length() == 0) continue;
            if (listenedNames != null && listenedNames.contains(fileName(p))) continue;
            Integer ms = resumeMsByPath == null ? null : resumeMsByPath.get(p);
            if (ms != null && ms.intValue() > PlayNewPick.MIN_RESUME_MS) continue;
            if (soundingPath != null && soundingPath.length() > 0 && soundingPath.equals(p)) continue;
            return i;
        }
        return -1;
    }
}
