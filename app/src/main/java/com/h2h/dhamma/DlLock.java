package com.h2h.dhamma;

// v13.68 (audit-1369 F11): the ONE shared lock for every dlMap /
// dlSayadaw / dlQueued read-modify-write sequence in the app.
//
// The AutoDownloadJob worker thread runs recordDownload68 /
// pruneStaleDlQueued68 off the UI thread while Room, Talks, Main,
// Settings, Downloaded and Fx write the same three keys on the UI
// thread. SharedPreferences.apply() is thread-safe per call, but a
// read-parse-modify-writeString sequence is not atomic: a job record
// could read {A,B}, a UI prune drop B and write {A}, and the job then
// write {A,B,C} - resurrecting B's drop (stale hourglass until the
// next prune) - or the reverse order could lose C (missing hourglass
// + double-enqueue window). Every record / prune / cancel / migrate
// sequence now runs inside synchronized (DlLock.LOCK).
//
// Single-key readers stay unlocked: each getString is atomic, and
// since v13.65 (C4) dlMap + dlQueued land in one apply(), so a reader
// never sees a torn pair. One lock, never nested with another monitor
// (the doWorkInner68 read+prune pair re-enters it on the same thread;
// synchronized is re-entrant). No lock ordering exists with
// Prefs.class (dlMapCached / dlSayCached): no DlLock holder calls
// those, and they never call a dl writer.
public final class DlLock {
    public static final Object LOCK = new Object();
    private DlLock() {}
}
