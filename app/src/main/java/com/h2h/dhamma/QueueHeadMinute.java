package com.h2h.dhamma;
// v15.66 (slice 2054): the v3 queue head's minute, as a pure core — the
// Room pop's minute rule, pinnable without Android. minuteFor(lines,
// talkUrl) returns the minute (ms) the queued head for this talk must
// start at, or -1 when there is none, so the caller falls back to the
// saved spot, never to a guess:
// - the FIRST line naming the talk decides (SiteHttp.sameTalk, so a
//   legacy-http line matches its https talk): a plain v2 line ahead of
//   a mark line means THIS pop plays at the saved spot — the mark line
//   behind it is the NEXT pop's, once the played line is dropped.
// - only a REAL minute counts (isV3() and posMs > 0): no real mark is
//   ever 0 (bookmarkAddPure refuses pos <= 0), so a 0-minute v3 line
//   can only be hand-corrupted, and it reads as no minute.
// - a v1/v2 line, a corrupt line, a missing talk and null inputs all
//   read as -1 (fail-closed, the 2053 rule).
// A talk with three marks queues three lines; the pop drops the played
// one, so each pop reads the next mark in order.
public class QueueHeadMinute {
    public static int minuteFor(java.util.List<String> lines, String talkUrl) {
        if (lines == null || talkUrl == null || talkUrl.length() == 0) return -1;
        for (String line : lines) {
            QueueEntry e = QueueEntry.parse(line);
            if (e == null) continue;
            if (!SiteHttp.sameTalk(e.url, talkUrl)) continue;
            if (e.isV3() && e.posMs > 0) return e.posMs;
            return -1;
        }
        return -1;
    }
}
