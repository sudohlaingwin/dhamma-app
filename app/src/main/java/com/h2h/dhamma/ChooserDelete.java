package com.h2h.dhamma;

// v15.53 (slice 1984): "Delete this downloaded file" — the Talks
// chooser dialog's delete verb: the browsing screen could download
// a talk (v11.70) but never delete its file, so a listener who had
// finished a downloaded talk and wanted the storage back had to
// leave the browsing screen, open the library and find the row
// again. Pure set/path math only: the Activity owns the menu offer,
// the confirm dialog, the file delete, the prefs writes and the row
// rebind; the core never touches Android, prefs, or the disk.
public class ChooserDelete {
    // The offer gate: the talk must actually be downloaded — the
    // chooser's own isTalkDownloaded (mapped name + basename
    // candidates, file-exists + length gate, v13.66), so a
    // streamed-only talk never offers it. The download verb beside
    // it is offered exactly when this is not, so the two never
    // share a row.
    public static boolean canOffer(boolean downloaded) {
        return downloaded;
    }

    // The dlMap key forms to prune when the file is deleted — the
    // v14.88 three-form rule, mirrored from talkMappedFileName:
    // the raw URL as listed, its canonical https form, and its
    // downgraded http form. dlMap keys are written under the URL
    // form current at download time and never migrated (v13.66),
    // so a legacy http key survives a prune that removes only the
    // canonical form and keeps claiming the filename for
    // talkFileClaimedByOther — a re-download would then mint a
    // hash-suffixed duplicate instead of reusing the name.
    // Deduped in raw / canonical / downgraded order; a null or
    // blank URL prunes nothing, never a crash.
    public static java.util.List<String> pruneKeys(String url) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (url == null || url.length() == 0) return out;
        out.add(url);
        String canon = SiteHttp.identityUrl(url);
        if (canon != null && canon.length() > 0 && !out.contains(canon)) out.add(canon);
        String down = SiteHttp.downgrade(url);
        if (down != null && down.length() > 0 && !out.contains(down)) out.add(down);
        return out;
    }

    // The tap-time sounding check: the dialog gate already excludes
    // the sounding talk, but the 1s tick refreshes talkPlayingUrl /
    // talkPlayingLibUrl while the dialog sits open, so a row offered
    // while waiting can be sounding by tap time (strip Next,
    // auto-advance, a Room started elsewhere — the v11.12/v11.14
    // window). Both sounding forms, by identity, never raw equals.
    public static boolean isSounding(String url, String playingUrl, String playingLibUrl) {
        if (url == null || url.length() == 0) return false;
        if (playingUrl != null && playingUrl.length() > 0 && SiteHttp.sameTalk(url, playingUrl)) return true;
        if (playingLibUrl != null && playingLibUrl.length() > 0 && SiteHttp.sameTalk(url, playingLibUrl)) return true;
        return false;
    }
}
