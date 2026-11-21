package com.h2h.dhamma;

// v15.54 (slice 1986): "Delete this downloaded file" — the Room
// drawer row dialogs' delete verb. The drawer could share a
// downloaded file (v15.48, slice 1955) but never delete it, so a
// listener in the Room who had finished a downloaded talk and
// wanted the storage back had to leave the Room, open the library
// and find the row again. The library row has had a single delete
// (doDelete) since v1.56 and the Talks chooser dialog gained one
// in v15.53 (slice 1984, ChooserDelete); the drawer dialogs are
// the third surface. Pure gate + key math only: the Activity owns
// the menu offer, the confirm dialog, the file delete, the prefs
// writes and the row rebind; the core never touches Android,
// prefs, or the disk.
public class DrawerDelete {
    // The offer gate: the talk must actually be downloaded — the
    // Room's own downloadedFileName (cache-only dlMap + basename
    // rule, v1.57), so a streamed-only talk never offers it — and
    // the sounding talk is excluded (the DlMark rule, 1949/1978/
    // 1979: the playing file earns its mark by finishing; here the
    // playing file's bytes are open, and the Room IS the session's
    // screen, so a stop-then-delete would kill its own playback —
    // the 1984 chooser's stop belongs to Talks, which never binds).
    // Null-safe on every input: a null or blank download name
    // never offers, never a crash.
    static boolean canOffer(String downloadedName, boolean sounding) {
        return downloadedName != null && downloadedName.length() > 0 && !sounding;
    }

    // The dlMap key forms to prune when the file is deleted — the
    // v14.88 three-form rule, single-sourced through ChooserDelete
    // (1984, the 1953 delegation precedent: the twins share one
    // membership rule, so the rule can never drift between the
    // chooser and the drawer): the raw URL, its canonical https
    // form, and its downgraded http form. dlMap keys are written
    // under the URL form current at download time and never
    // migrated (v13.66), so pruning only the canonical form would
    // leave a legacy http key claiming the filename.
    static java.util.List<String> pruneKeys(String url) {
        return ChooserDelete.pruneKeys(url);
    }
}
