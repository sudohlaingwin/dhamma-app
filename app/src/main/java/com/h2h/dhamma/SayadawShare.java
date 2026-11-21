package com.h2h.dhamma;

// v15.53 (slice 1980): the Talks header's sayadaw share — the
// visible ↗ on the chooser header shares this sayadaw (name +
// original dhammadownload.com page + credit) through the system
// share sheet. The text is the Room header long-press's (v4.16)
// composition verbatim, lifted into a pure core so the two
// surfaces can never disagree: the name first, then the page URL
// on its own line, then the credit line. The URL is sent
// VERBATIM — never respelled to https, never guessed: a share
// sends the catalog's own link, unlike the identityUrl rule
// that normalises talk URLs for the played set. The offer gate
// is SayadawInfo.showPageRow itself (the info sheet's
// original-page row reads the same gate), so the button, the
// sheet and the share can never disagree about whether this
// sayadaw has a page. Pure string math only: the Activity owns
// the dialog, the chooser and the toast; the core never touches
// Android or the catalog.
public class SayadawShare {
    // The share offers only while the sayadaw has an original
    // page URL — the showPageRow rule verbatim (null, blank and
    // the literal "null" string all count as none).
    static boolean canOffer(String pageUrl) {
        return SayadawInfo.showPageRow(pageUrl);
    }

    // name + "\n" + pageUrl + credit, every leg null-safe.
    // The credit already carries its leading newline (the
    // share_source key), so the composition adds none.
    static String shareText(String sayadawName, String pageUrl, String sourceCredit) {
        return (sayadawName == null ? "" : sayadawName)
                + "\n" + (pageUrl == null ? "" : pageUrl)
                + (sourceCredit == null ? "" : sourceCredit);
    }
}
