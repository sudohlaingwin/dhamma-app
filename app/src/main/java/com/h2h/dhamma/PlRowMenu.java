package com.h2h.dhamma;

// v15.74 (slice 2105): Row long-press menu on the Playlist detail
// screen — the pure offer. The Playlist detail screen had Play
// All, Shuffle, jump, back-to-top, a row tap (playFrom), a
// per-row remove, a strip, the v13.55 sounding gold mark and the
// empty state, but no row menu at all: a long-press on a detail
// row returned false, so a listener deep in a playlist could only
// play a talk or drop it — the menu opened with the two verbs the
// shell needed: "Play from here" (the chooser's v15.45 verb,
// History's v15.62 mirror, Favourites' v15.68 mirror, New talks'
// v15.69 mirror, Bookmarks' v15.69 mirror) and "Remove from list"
// (the screen's own removal verb, the per-row remove). The 2105
// note left the menu OPEN for the share / copy / file /
// go-to-sayadaw / queue verbs (one per slice, the 2073-2091
// pattern) — the two verbs were the shell's landing set, never a
// cap, so slice 2116 adds the share trio below without
// re-adjudicating the shell. The offer is pure selection only:
// the Activity owns the dialog, the queue write, the store
// remove and the Room open. "Play from here" needs a stream URL
// with a sayadaw that is not sounding (the FavPlayAll sounding
// rule — Room URL / library reverse URL; the ended gate: an
// ended talk is not playing, so its row offers play-from-here
// again — the PlJumpPlaying ended-param shape). A path-form row
// is a downloaded file, so it offers remove only, like History's
// menu. "Remove from list" is always offered — the row IS in
// this list by construction — so an opened menu is never empty.
// v15.76 (slice 2116): the share trio joins the menu — "Share"
// + "Copy link" when a stream URL resolved (the row's own URL,
// or the dlMap reverse lookup for a downloaded row), "Share
// file" when a downloaded file resolved (the dlMap name, or the
// row's own basename) — the History trio (slices 1981/1982)
// through the same pure cores (RoomCopyLink + TalkFileShare), so
// the detail rows can never disagree with History about what
// shares, copies or sends. The trio sits between play-from-here
// and remove (constructive first, destructive last — the 2114
// lists-menu order); the sounding row keeps it (share/copy/file
// touch neither playback nor the queue).
// v15.76 (slice 2117): go-to-sayadaw + queue join the menu —
// "Go to sayadaw" when the row's sayadaw resolves to a catalog
// page (the HistGo gate — History's 1995 verb through the same
// pure core, so the two surfaces can never disagree; sounding
// or not, the navigation never touches playback) and the queue
// verb when the talk can queue ("Add to queue" / "Remove from
// queue" by membership — the Talks v5.82 gate: a resolved stream
// URL with a sayadaw, not sounding). Both sit after the trio
// and before remove (constructive first, destructive last);
// the menu stays OPEN for playlist-add (piece 5/5).
// v15.76 (slice 2118): "Add to playlist" joins the menu —
// the last History row-menu verb the detail rows lacked
// (slice 2095, through HistPlAdd + Prefs.playlistAdd — the
// History / Favourites / New talks / Bookmarks / Downloaded /
// Room-queue / Talks-chooser shapes), so a listener deep in a
// playlist can file the talk in one of their own lists
// without leaving the list. The gate is the same non-blank
// stream URL the share reads (HistPlAdd.canOffer — a pure
// function of the streamUrl arg menuKeys already carries,
// exactly like canShareText, so no new parameter was needed);
// it sits after the queue verb and before remove
// (constructive first, destructive last — History inserts
// pl_add right before its remove too). The sounding row keeps
// it (filing never touches playback). The menu's 2105 open set
// is now COMPLETE — the playlist-add family is on EIGHT
// surfaces.
public class PlRowMenu {
    // True when this row's talk is the sounding one — a Room
    // session (lastUrl) or a library session (dlMap reverse
    // lookup). An ended talk is not playing (the v11.15 gate),
    // so it never reads as sounding. Null/blank sounding URLs
    // name no talk.
    static boolean isSounding(String url, String soundingUrl,
            String soundingLibUrl, boolean ended) {
        if (ended) return false;
        if (url == null || url.length() == 0) return false;
        if (soundingUrl != null && soundingUrl.length() > 0
                && SiteHttp.sameTalk(url, soundingUrl)) return true;
        if (soundingLibUrl != null && soundingLibUrl.length() > 0
                && SiteHttp.sameTalk(url, soundingLibUrl)) return true;
        return false;
    }

    // True when this row can start a run from here: a stream URL
    // with a sayadaw, not sounding. Null/blank names no talk.
    static boolean canPlayFromHere(String url, String sayadaw,
            String soundingUrl, String soundingLibUrl, boolean ended) {
        if (url == null || url.length() == 0) return false;
        if (url.startsWith("/")) return false;
        if (sayadaw == null || sayadaw.length() == 0) return false;
        if (isSounding(url, soundingUrl, soundingLibUrl, ended)) return false;
        return true;
    }

    // True when this row can share its text: a stream URL
    // resolved (the row's own URL, or the dlMap reverse lookup
    // for a downloaded row) — the text share sends title + link
    // + credit, so a row with no stream URL shares nothing. The
    // RoomCopyLink gate (a non-blank url), so the share and the
    // copy beside it can never disagree. Null/blank reads as
    // no-offer, never a crash.
    static boolean canShareText(String streamUrl) {
        return RoomCopyLink.canOffer(streamUrl);
    }

    // True when this row can share its downloaded file: a safe
    // .mp3 name (the TalkFileShare gate) that resolved to a file
    // on disk — the Activity passes only names it resolved, so a
    // streamed-only talk never offers it. Null/blank reads as
    // no-offer, never a crash.
    static boolean canShareFile(String fileName) {
        return TalkFileShare.isSafeName(fileName);
    }

    // True when this row can open its sayadaw's talks: the row
    // names a sayadaw AND that sayadaw resolves to a catalog
    // page (the HistGo gate — History's 1995 verb through the
    // same pure core, so the two surfaces can never disagree).
    // Sounding or not: the navigation never touches playback.
    // Null/blank reads as no-offer, never a crash.
    static boolean canGoToSayadaw(String sayadaw, String[] cat) {
        return HistGo.canOffer(sayadaw, cat);
    }

    // True when this row can join or leave the sayadaw's hand
    // queue: a resolved stream URL with a sayadaw, not sounding
    // (the Talks v5.82 rule — History's queue gate: queueing
    // the sounding talk would replay it). Null/blank reads as
    // no-offer, never a crash.
    static boolean canQueue(String streamUrl, String sayadaw,
            String soundingUrl, String soundingLibUrl, boolean ended) {
        if (streamUrl == null || streamUrl.length() == 0) return false;
        if (sayadaw == null || sayadaw.length() == 0) return false;
        if (isSounding(streamUrl, soundingUrl, soundingLibUrl, ended)) return false;
        return true;
    }

    // True when this row can file its talk in one of the
    // listener's own lists: a resolved stream URL (the
    // HistPlAdd gate — History's 2095 verb through the same
    // pure core, so the two surfaces can never disagree). A
    // streamed-only talk still offers it (filing is not a
    // file); only a row with no resolvable stream URL hides
    // it. Sounding or not: filing never touches playback.
    // Null/blank reads as no-offer, never a crash.
    static boolean canAddToPlaylist(String streamUrl) {
        return HistPlAdd.canOffer(streamUrl);
    }

    // The menu's keys, in display order: "Play from here" first
    // when offered, the share trio next — "Share" + "Copy link"
    // when a stream URL resolved, "Share file" when a downloaded
    // file resolved — "Go to sayadaw" when the sayadaw resolves
    // to a catalog page, the queue verb ("Add to queue" or
    // "Remove from queue" by membership) when the talk can
    // queue, "Add to playlist" when a stream URL resolved, and
    // "Remove from list" always. A fresh list per call, never a
    // shared one. The menu is never empty.
    static java.util.List<String> menuKeys(String url, String sayadaw,
            String soundingUrl, String soundingLibUrl, boolean ended,
            String streamUrl, String fileName, String[] goCat, boolean queued) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (canPlayFromHere(url, sayadaw, soundingUrl, soundingLibUrl, ended))
            out.add("menu_play_from_here");
        if (canShareText(streamUrl)) {
            out.add("menu_share");
            out.add("dl_menu_copy_link");
        }
        if (canShareFile(fileName))
            out.add("dl_menu_share_file");
        if (canGoToSayadaw(sayadaw, goCat))
            out.add("menu_go_sayadaw");
        if (canQueue(streamUrl, sayadaw, soundingUrl, soundingLibUrl, ended))
            out.add(queued ? "menu_queue_remove" : "menu_queue_add");
        if (canAddToPlaylist(streamUrl))
            out.add("pl_add");
        out.add("menu_pl_remove");
        return out;
    }
}
