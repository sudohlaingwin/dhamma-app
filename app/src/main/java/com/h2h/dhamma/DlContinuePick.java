package com.h2h.dhamma;

// v16.00 (slice 2266): "Continue listening" pick — the one-tap
// return to the library's most recently listened half-heard file.
// The chooser's ContinuePick (v15.43) picks the most recently
// listened talk ON THE PAGE; this picks the most recently
// listened file ON THE SHELF, in path space: the library queue
// holds paths, never URLs, so there are no http/https twins to
// normalise (the DlJumpUnheard world). Pure index math only: the
// Activity owns the shelf, the resume map and the play.
public class DlContinuePick {
    // The row ↺ rule's threshold (getView): a spot at or under
    // 5s is a fresh start, not a return.
    static final int MIN_RESUME_MS = 5000;

    // The file name of an absolute shelf path (the
    // libListenedFiles key form), or "" for a null/blank path.
    // A bare name with no slash passes through unchanged.
    static String fileName(String path) {
        if (path == null || path.length() == 0) return "";
        int s = path.lastIndexOf('/');
        return s < 0 ? path : path.substring(s + 1);
    }

    // The absolute shelf path of the talk to continue, or null
    // when none. `recent` is most-recent-first (Prefs.statsRecent);
    // `urlToPath` maps a talk's canonical identityUrl to its shelf
    // path (libUrlToPath, single-claim files only — the v8.43 rule);
    // `resumeMsByPath` maps absolute path to the saved spot in ms
    // (libResumeMap); `listenedNames` holds the finished files'
    // NAMES (libListenedFiles); `soundingPath` is the sounding
    // file's absolute path (libRowPlayingPath, exact equality).
    // Skips null entries, blank urls, urls with no shelf file, the
    // sounding file, finished files, and files with no saved spot
    // over the threshold. The first surviving entry wins; the input
    // lists are never mutated.
    static String pick(
            java.util.List<Prefs.HistEntry> recent,
            java.util.Map<String, String> urlToPath,
            java.util.Map<String, Integer> resumeMsByPath,
            java.util.Set<String> listenedNames,
            String soundingPath) {
        if (recent == null || urlToPath == null || urlToPath.isEmpty()) return null;
        for (Prefs.HistEntry e : recent) {
            if (e == null) continue;
            if (e.url == null || e.url.length() == 0) continue;
            String id = SiteHttp.identityUrl(e.url);
            if (id == null) continue;
            String path = urlToPath.get(id);
            if (path == null || path.length() == 0) continue;
            if (soundingPath != null && soundingPath.length() > 0 && soundingPath.equals(path)) continue;
            if (listenedNames != null && listenedNames.contains(fileName(path))) continue;
            Integer ms = resumeMsByPath == null ? null : resumeMsByPath.get(path);
            if (ms == null || ms.intValue() <= MIN_RESUME_MS) continue;
            return path;
        }
        return null;
    }
}
