package com.h2h.dhamma;

// v15.45 (slice 1941): the sayadaw info sheet's count — the
// "how much is new" number no surface shows. The sheet's new
// count is DEFINED as the Play-new pick's size (PlayNewPick,
// slice 1933): the same gate as the ● dot on the rows (1920)
// — in the new-set, unfinished, unstarted (no saved spot over
// 5s), not the sounding talk — so the sheet, the dots and the
// Play-new row can never disagree about how many new talks
// wait. Pure set/index math only: the Activity owns the list,
// the resume map and the dialog; the core never touches
// Android or the catalog.
public class SayadawInfo {
    // The count of unseen-new talks in the list: the size of
    // the Play-new pick over the same inputs. Null-safe on
    // every input (PlayNewPick.pick already is); a null or
    // empty new-set counts zero.
    static int unseenNewCount(java.util.List<String> displayIds,
            java.util.Set<String> newIds,
            java.util.Set<String> playedIds,
            java.util.Map<String, Integer> resumeMsById,
            String soundingUrl, String soundingLibUrl) {
        return PlayNewPick.pick(displayIds, newIds, playedIds,
                resumeMsById, soundingUrl, soundingLibUrl).size();
    }

    // The "new" line shows only while new talks wait — the
    // header counts' zero-guard idiom (v4.26/v5.66): a zero
    // never earns a line.
    static boolean showNewLine(int newCount) {
        return newCount > 0;
    }

    // The group line shows only when the opener sent a group
    // (Main always does; a Room/notification open may not) —
    // an empty group hides the line instead of printing a
    // blank one.
    static boolean showGroup(String group) {
        return group != null && group.length() > 0;
    }

    // v15.46 (slice 1943): the "original page" row shows only
    // while this sayadaw has an original page URL — the Talks
    // intent always sends one (Main's catalog link), a
    // Room/notification open may carry none. The literal
    // "null" string counts as none (the Room retry guard's
    // shape, v4.19): a page URL is never validated here, only
    // its presence — the tap's try/catch owns the open.
    static boolean showPageRow(String pageUrl) {
        return pageUrl != null && pageUrl.length() > 0 && !"null".equals(pageUrl);
    }
}
