package com.h2h.dhamma;
// v15.67 (slice 2059): the download-await record's minute, as a pure core — the
// 2054 known gap, closed. The record carries the minute (ms) a gated pick must
// start at, so the landed re-fire plays a queued mark at its minute instead of
// at the saved spot:
// - record(posMs) normalises what the record stores: a REAL minute (posMs > 0)
//   is stored as-is; anything else (-1 for a v2 line, 0 for a hand-corrupted
//   v3 line) stores as -1, so the re-fire falls back to the saved spot.
// - seek(stored, savedSeek) picks the effective seek for the re-fire: the
//   stored minute when real (> 0), else the saved spot. The minute rides the
//   seek argument only — talkPos is never written by this slice.
// - a pre-2059 record has no minute field and reads as -1 (the getInt
//   default), so old records re-fire exactly as before; a list pick
//   (forward / shuffle / list-next) records -1 by the same rule, since no
//   queue line names it.
public class AwaitMinute {
    public static int record(int posMs) {
        return posMs > 0 ? posMs : -1;
    }
    public static int seek(int storedPosMs, int savedSeek) {
        return storedPosMs > 0 ? storedPosMs : savedSeek;
    }
}
