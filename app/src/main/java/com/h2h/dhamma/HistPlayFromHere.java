package com.h2h.dhamma;

// v15.62 (slice 2030): "Play from here" line builder for the History
// row menu — the History mirror of the Talks chooser's PlayFromHere
// pick (v15.45, slice 1942) and the library's DlPlayFromHere
// (v15.57, slice 2001). The chooser queues one sayadaw's run; the
// library queues one shelf's run; History queues a run across
// sayadaws — every picked row becomes a v2 queue line carrying its
// OWN sayadaw, so the Room renders the other sayadaws' talks as
// foreign rows and hands over when one sounds (the v10.98 carry +
// the (b) handover). Pure line math only: the Activity owns the
// list, the queue write and the Room open.
public class HistPlayFromHere {
    // The v2 queue lines for the picked run positions, in run order.
    // `ids` / `sds` / `titles` are parallel to the History display
    // list (ids = SiteHttp.identityUrl per row, null where the row
    // has no resolvable stream URL); `picked` is the
    // PlayFromHere.pick output (display positions from the anchor
    // onward). A talk heard twice sits twice in History, so the run
    // dedupes by identity — the same talk never queues twice in one
    // run. A null/blank id or a blank sayadaw skips the row: a line
    // with no owner formats as a bare v1 URL, and a v1 line whose
    // URL is not in the anchor sayadaw's list is dropped by the
    // Room's funnel, so writing it would only inflate the count
    // toast. The input lists are never mutated.
    static java.util.List<String> lines(
            java.util.List<String> ids,
            java.util.List<String> sds,
            java.util.List<String> titles,
            java.util.List<Integer> picked) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (ids == null || sds == null || titles == null || picked == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Integer pBox : picked) {
            if (pBox == null) continue;
            int p = pBox.intValue();
            if (p < 0 || p >= ids.size() || p >= sds.size() || p >= titles.size()) continue;
            String id = ids.get(p);
            if (id == null || id.length() == 0) continue;
            if (seen.contains(id)) continue;
            String sd = sds.get(p);
            if (sd == null || sd.length() == 0) continue;
            seen.add(id);
            String line = QueueEntry.format(new QueueEntry(id, sd, titles.get(p)));
            if (line.length() > 0) out.add(line);
        }
        return out;
    }
}
