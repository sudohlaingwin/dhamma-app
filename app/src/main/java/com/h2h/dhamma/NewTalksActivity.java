package com.h2h.dhamma;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

// v15.63 (slice 2036): "See all" on the Main "New talks" rail —
// the full list of every new talk, across all sayadaws. The rail
// shows 5 cards (NewTalkRail.MAX_CARDS) while the listener's unheard
// arrivals can run to dozens; no screen anywhere showed all new talks
// together (the grid's dots are per-sayadaw, the Talks chooser is
// per-sayadaw). Rows are the rail card's own shape (44dp portrait +
// sayadaw name + the bare title — the 1921 gate only ever picks talks
// nobody started, so there is no saved-spot prefix — + the History
// "›" chevron); a tap lands the Room at 0:00 through the
// openNewTalk1966 shape (the Room intent, NO resume-spot write —
// the Room writes the spot as the talk plays, v1.56 re-entry — and
// NO stream resolution: the rail's own tap passes e.url as-is, so
// whatever the rail does for a path-form row, the list does).
// Mini-player strip since v15.63 (slice 2039, the History v13.54
// precedent); no clear-all (a bulk destructive verb, the
// 2016-rejected class — a new talk leaves this list by being played
// or marked, never by a bulk clear), no downloaded badge and no
// hairline (the badge + hairline families are complete on the rails;
// one dlMap parse per row on an unbounded list would break the v5.69
// cost rule, the 2033 precedent).
public class NewTalksActivity extends Activity {
    private TextView headerTitle;
    private ListView listView;
    private TextView emptyText;
    private java.util.List<NewTalkRail.Card> items =
            new java.util.ArrayList<NewTalkRail.Card>();
    // v15.64 (slice 2045): the search keeps the unfiltered pick
    // beside the shown list — a keystroke re-filters in memory
    // (the Downloaded applyDlFilter shape), never re-reading the
    // cache files per keystroke.
    private java.util.List<NewTalkRail.Card> newAllItems =
            new java.util.ArrayList<NewTalkRail.Card>();
    private BaseAdapter adapter;
    private boolean night;
    private java.util.Map<String, String[]> catCache;
    private java.util.List<String[]> catOrder;
    // v15.63 (slice 2036): row-tap debounce — its own clock (the
    // chooser's 700ms rule, v12.28: an invalid tap never consumes
    // the window). A rapid double-tap must never stack two Rooms.
    private long lastNewTapMs = 0;
    // v15.63 (slice 2039): real mini-player on this screen (the
    // History v13.54 precedent) — the new talks screen used to
    // show nothing about a playing new talk: tapping a row
    // opened the Room and Back returned here with no way to see,
    // pause or jump back to the talk. Same 1s-tick shape as
    // History: stripHandler + stripTick, newResumeStripMode (the
    // resume branch), and newStripPhotoName (the face the strip
    // last showed).
    private android.os.Handler stripHandler = new android.os.Handler();
    private Runnable stripTick;
    private boolean newResumeStripMode = false;
    private String newStripPhotoName = null;
    private boolean lastNewStripPhotoRing = false;
    // v15.64 (slice 2043): playing-row mark (History v13.55, Talks
    // v4.89/v11.07, same rules) — the New talks rows carried no live
    // marks: a listener browsing new talks could not spot the
    // talk the strip is naming without remembering its title. The
    // sounding identities below mirror History's
    // refreshHistoryPlayingMarks playing half: newPlayingUrl for a
    // Room session (lastUrl), newPlayingLibUrl for a library session
    // via the dlMap reverse lookup (exactly-one claimant, v8.44),
    // and newPlayingLibPath for a library session matched directly
    // — a path-form row IS the file (playRecent v12.82 writes the
    // same path), so no lookup is needed there. newRowPlayingUrl /
    // newRowPlayingLibUrl / newRowPlayingLibPath / newRowEnded are the
    // last bound snapshots; the 1s tick rebinds once when any of
    // them changes.
    private String newPlayingUrl = "";
    private String newPlayingLibUrl = "";
    private String newPlayingLibPath = "";
    private String newRowPlayingUrl = null;
    private String newRowPlayingLibUrl = null;
    private String newRowPlayingLibPath = null;
    private boolean newRowEnded = false;
    // v15.64 (slice 2045): search on this screen — the field, the
    // clear ✕, the live query and the keystroke flag (the
    // Downloaded dlSearchField / dlClearBtn / dlQuery /
    // dlSearchKeystroke shape, v1.51 + v15.33). The query is the
    // trimmed field text; the flag is true only while the watcher
    // drives a pass, so the ✕ melts on keystrokes and lands
    // instantly on silent passes (rotation / restore / onResume).
    private android.widget.EditText newSearchField = null;
    private android.view.View newClearBtn = null;
    private String newQuery = "";
    private boolean newSearchKeystroke = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_talks);
        night = Prefs.isNight(this);
        Fx.applySystemBars(this, night); // v13.07: bars follow the theme
        View root = findViewById(R.id.newRoot);
        if (root != null)
            root.setBackgroundResource(night ? R.drawable.bg_gradient_night
                    : R.drawable.bg_gradient_day);
        headerTitle = findViewById(R.id.newTitle);
        listView = findViewById(R.id.newList);
        emptyText = findViewById(R.id.newEmpty);
        if (headerTitle != null) {
            headerTitle.setText(Lang.t(this, "hs_new_talks"));
            headerTitle.setTextColor(night ? 0xFFC9A227 : 0xFF7A2E1F);
        }
        adapter = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int position) { return items.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView,
                    android.view.ViewGroup parent) {
                return itemRow(position);
            }
        };
        if (listView != null) {
            listView.setAdapter(adapter);
            listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override public void onItemClick(AdapterView<?> p, View v,
                        int pos, long id) {
                    if (pos < 0 || pos >= items.size()) return;
                    playNew(items.get(pos), v);
                }
            });
        }
        wireNewSearch(); // v15.64 (slice 2045): search on this screen, before the first refresh
        refresh();
        wireNewTalksStrip(); // v15.63 (slice 2039): real mini-player below the list
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        startNewTalksStripTick(); // v15.63 (slice 2039): mini-player live state
    }

    @Override protected void onPause() {
        super.onPause();
        stopNewTalksStripTick(); // v15.63 (slice 2039): never tick a detached strip
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        // v15.63 (slice 2039): detach the liquid-glass surface (History v13.54,
        // Talks v13.08) — the drawable pins the dead view + its destroyed
        // activity otherwise.
        GlassBlur.detach(findViewById(R.id.nowStrip));
    }

    private void refresh() {
        java.util.Set<String> played = readPlayedTalks();
        java.util.Set<String> resumeKeys = new java.util.HashSet<String>();
        try { resumeKeys = Prefs.talkPosKeysOver(this, 5000); } catch (Exception ignored) {}
        // v15.64 (slice 2045): the search keeps its own copy of the
        // unfiltered pick — a keystroke re-filters in memory (the
        // Downloaded applyDlFilter shape), never re-reading the
        // cache files per keystroke.
        newAllItems = NewAll.pickAll(collectNewTalkEntries(played, resumeKeys),
                played, resumeKeys);
        refreshNewTalksPlayingMarks(); // v15.64 (slice 2043): mark right on first paint, not one tick late
        applyNewSearch();
    }

    // v15.64 (slice 2045): the search pass — the filtered list,
    // the adapter, the clear ✕ and the empty line, from the
    // unfiltered pick. The watcher calls this per keystroke;
    // refresh() calls it after a fresh pick; onResume's refresh()
    // re-applies the live query after a Room return. A
    // filtered-empty list says no_search_match (v8.94) — there ARE
    // new talks, just none matching — while a blank query with an
    // empty list still says no_new_talk. The playing-row mark
    // needs nothing here: itemRow re-reads the sounding
    // identities per bind, so a filtered-out sounding talk simply
    // has no row to mark and a re-shown one marks at once.
    private void applyNewSearch() {
        items = NewSearch.filter(newAllItems, newQuery);
        if (adapter != null) adapter.notifyDataSetChanged();
        refreshNewClear(newSearchKeystroke);
        if (emptyText == null) return;
        boolean empty = items.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) emptyText.setText(Lang.t(this,
                newQuery.length() > 0 ? "no_search_match" : "no_new_talk"));
    }

    // v15.64 (slice 2045): search on this screen — the Downloaded
    // search shape (v1.51 field + v4.20 Search-key dismiss + v15.33
    // clear ✕): the hint is the Talks chooser's own
    // talks_search_hint (v9.88), the filter is the ONE rule
    // (Fx.titleMatches through NewSearch, title or sayadaw), and a
    // keystroke re-filters the in-memory pick without re-reading
    // the cache files. Rotation restore rides the watcher (the
    // v1.51 rule): the system's restored text fires afterTextChanged
    // after onStart, so the filter re-runs with the restored query
    // before onResume's refresh. The clear ✕ clears through
    // setText("") so the watcher re-runs the filter exactly like a
    // keystroke (the Main v15.33 shape, slice 1836); it is GONE
    // with an empty query, so a tap can only land on a real query.
    private void wireNewSearch() {
        newSearchField = findViewById(R.id.newSearch);
        newClearBtn = findViewById(R.id.btnClearNewSearch);
        if (newSearchField != null)
            newSearchField.setHint(Lang.t(this, "talks_search_hint"));
        if (newSearchField != null)
            newSearchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        if (newSearchField != null) newSearchField.setSingleLine(true);
        if (newSearchField != null) newSearchField.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(android.widget.TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                        || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                    return true;
                }
                return false;
            }
        });
        if (newClearBtn != null) newClearBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.morph(v);
                if (newSearchField != null) newSearchField.setText("");
            }
        });
        if (newSearchField != null) newSearchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                newQuery = s.toString().trim();
                newSearchKeystroke = true;
                applyNewSearch();
                newSearchKeystroke = false;
            }
        });
    }

    // v15.64 (slice 2045): the search clear ✕ melts with the query
    // — the Downloaded refreshDlClear shape verbatim (v15.33, slice
    // 1837): fades in 250ms (the app's settled Fx.fadeIn) when a
    // keystroke-driven pass leaves a non-empty query, and fades out
    // 200ms (the v14.33 sleep-line rhythm) when the query empties;
    // silent passes (rotation / restore / onResume) land instantly,
    // the v1.53 rule. Shown for a non-empty query only — the ✕ is a
    // clear control, not a no-match marker, so it never waits on
    // the entries or the results. A mid-fadeOut re-show flows back
    // on from its current alpha (Fx.fadeIn is restart-safe, v3.52,
    // and its gen bump stands the fade-out's end action down, so it
    // can never strand the view GONE), and a silent pass on a
    // mid-fadeOut view parks it at full alpha instead.
    private void refreshNewClear(boolean animate) {
        if (newClearBtn == null) return;
        if (newQuery != null && newQuery.length() > 0) {
            if (newClearBtn.getVisibility() != android.view.View.VISIBLE) {
                newClearBtn.setVisibility(android.view.View.VISIBLE);
                if (animate) Fx.fadeIn(newClearBtn, 250);
                else newClearBtn.setAlpha(1f);
            } else if (animate && newClearBtn.getAlpha() < 0.999f) {
                Fx.fadeIn(newClearBtn, 250);
            } else if (!animate) {
                newClearBtn.animate().cancel();
                newClearBtn.setAlpha(1f);
            }
        } else {
            if (newClearBtn.getVisibility() == android.view.View.VISIBLE) {
                if (animate) Fx.fadeOut(newClearBtn, 200);
                else {
                    newClearBtn.animate().cancel();
                    newClearBtn.setVisibility(android.view.View.GONE);
                    newClearBtn.setAlpha(1f);
                }
            }
        }
    }

    private int goldColor() { return 0xFFC9A227; } // v15.64 (slice 2043): History's gold, the playing-row mark

    private View itemRow(final int position) {
        final NewTalkRail.Card c = items.get(position);
        final Prefs.HistEntry e = c.e;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * d);
        String[] cat = catalogFor(e.sayadaw);
        CircleImageView photo = new CircleImageView(this);
        int px = (int) (44 * d);
        LinearLayout.LayoutParams pp =
                new LinearLayout.LayoutParams(px, px);
        pp.setMargins(0, 0, (int) (8 * d), 0);
        photo.setLayoutParams(pp);
        photo.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        ImageLoader.load(this, cat == null ? "" : cat[2], e.sayadaw, photo);
        row.addView(photo);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(0, (int) (10 * d), pad, (int) (10 * d));
        TextView nv = new TextView(this);
        nv.setText(e.sayadaw);
        nv.setTextColor(night ? android.graphics.Color.WHITE
                : android.graphics.Color.parseColor("#3B2314"));
        nv.setTextSize(13);
        nv.setTypeface(null, android.graphics.Typeface.BOLD);
        nv.setMaxLines(1);
        nv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(nv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView tv = new TextView(this);
        // The rail card's own title (MainActivity refreshNewTalkRail,
        // v15.50): the bare title — the 1921 gate only ever picks
        // talks nobody started, so there is no saved-spot prefix
        // and no downloaded badge here (the 2033 precedent).
        // v15.64 (slice 2043): the sounding row says so — "\u25b6 " ahead of
        // its bare title in gold (History v13.55, Talks v4.89/v11.07), for a Room
        // session (URL match) or a library session on a URL-form row (dlMap
        // exactly-one claimant); an ended talk is not playing (v11.15 gate),
        // so its mark lifts at once. New talks rows carry no saved-spot prefix
        // (the 1921 gate only ever picks talks nobody started), so the mark
        // reads on the bare title. Rows are built fresh per getView, but both
        // text and colour rebind here so neither can leak onto the next talk.
        boolean newRowPlaying = !AudioService.liveEnded
                && ((newPlayingUrl.length() > 0 && SiteHttp.sameTalk(e.url, newPlayingUrl))
                        || (newPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(e.url, newPlayingLibUrl))
                        || (newPlayingLibPath.length() > 0 && e.url.equals(newPlayingLibPath)));
        tv.setText((newRowPlaying ? "\u25b6 " : "") + e.title);
        tv.setTextColor(newRowPlaying ? goldColor() : android.graphics.Color.parseColor("#7A6A58"));
        tv.setTextSize(12);
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(col, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView go = new TextView(this);
        go.setText("\u203a");
        go.setTextColor(night ? android.graphics.Color.parseColor("#AAAAAA")
                : android.graphics.Color.parseColor("#7A6A58"));
        go.setTextSize(22);
        row.addView(go, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void playNew(final NewTalkRail.Card c, android.view.View v) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastNewTapMs < 700) return;
        lastNewTapMs = now;
        String sd = c.e.sayadaw;
        String[] cat = catalogFor(sd);
        if (cat == null || cat[1] == null || cat[1].length() == 0) {
            String owner = Prefs.sayadawForTalk(this, "lib", c.e.url, "");
            if (owner != null && owner.length() > 0 && !owner.equals(sd)) {
                sd = owner;
                cat = catalogFor(sd);
            }
        }
        if (cat == null || cat[1] == null || cat[1].length() == 0) {
            Toast.makeText(this, Lang.t(this, "pl_no_play"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // The openNewTalk1966 shape (the rail card tap's own route,
        // v15.50, via openRecentTalk1930): the Room intent opens
        // the talk at the start (spot 0 — the gate only ever picks
        // talks nobody started) — no resume-spot write, the Room
        // writes the spot as the talk plays (v1.56 re-entry).
        // Fx.tap + Fx.go, the FavouritesActivity row-tap shape
        // (2035).
        Intent it = new Intent(this, RoomActivity.class);
        it.putExtra("name", sd);
        it.putExtra("group", cat[0]);
        it.putExtra("url", cat[1]);
        it.putExtra("photo", cat[2]);
        it.putExtra("autoUrl", c.e.url);
        it.putExtra("autoTitle", c.e.title);
        Fx.tap(this);
        Fx.go(this, it);
    }

    // v15.50 (slice 1966): the raw playedTalks set for the rail —
    // canonical identity URLs. Copied before use (the Prefs.java:386
    // rule: a getStringSet result must never be mutated in place).
    // Fail closed to an empty set — a torn write is an empty screen
    // for one pass, never a crash.
    // v15.63 (slice 2039): real mini-player on this screen — a
    // faithful mirror of History's v13.54 strip (the same strip as
    // Talks' v4.31). The v13.55 row-mark head lives in
    // syncNewTalksStrip below since v15.64 (slice 2043). Live session:
    // title + star/sleep/download/queue/ended marks, sayadaw + clock
    // sub line, seekable progress, play/pause glyph, portrait with
    // the sounding gold ring. Dead session with a saved spot (>5s):
    // the resume strip (Room talk or downloaded file); anything
    // else: hidden, so the new talks stay list-only. The two
    // resume-match computations delegate to the pure StripResume
    // core; every other branch is History's body verbatim, renamed.
    // v15.64 (slice 2043): sounding identities for the row mark —
    // History's refreshHistoryPlayingMarks, minus the queue cache
    // (New talks has no queue marks). Room: liveSession +
    // liveHasTrack (v10.43 bound-trackless stop: a stopped talk is
    // never the playing talk) + lastKind room -> lastUrl. Library:
    // same live guards + lastKind lib -> lastLibPath, plus the dlMap
    // reverse lookup for URL-form rows (exactly-one claimant, v8.44
    // — a dual-claimed file names no talk rather than the wrong one).
    // Prefs only, never per bind.
    private void refreshNewTalksPlayingMarks() {
        newPlayingUrl = "";
        newPlayingLibUrl = "";
        newPlayingLibPath = "";
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (AudioService.liveSession && AudioService.liveHasTrack
                    && "room".equals(sp.getString("lastKind", "")))
                newPlayingUrl = sp.getString("lastUrl", "");
            if (AudioService.liveSession && AudioService.liveHasTrack
                    && "lib".equals(sp.getString("lastKind", ""))) {
                newPlayingLibPath = sp.getString("lastLibPath", "");
                String libPath = newPlayingLibPath;
                if (libPath.length() > 0) {
                    String base = libPath.substring(libPath.lastIndexOf('/') + 1);
                    org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
                    java.util.Iterator<String> keys = map.keys();
                    String hit = null;
                    int matches = 0;
                    while (keys.hasNext()) {
                        String k = keys.next();
                        if (base.equals(map.optString(k, ""))) { hit = k; matches++; }
                    }
                    if (matches == 1) newPlayingLibUrl = hit;
                }
            }
        } catch (Exception ignored) {}
    }

    private void syncNewTalksStripPhoto(String name) {
        android.view.View pv = findViewById(R.id.nowStripPhoto);
        if (pv == null) return;
        // v13.90 (slice 1082): strip portrait sounding gold ring — the
        // frame's background is the shelf ring drawable (reused, no new
        // drawable) while a live session sounds, and nothing otherwise,
        // so the sounding sayadaw reads in a still screenshot on every
        // strip. The predicate is the strip's own live rule
        // (liveSession + liveHasTrack, the same gate the strip sync's
        // live branch uses) with the v3.99 sounding identity: paused
        // still rings, an ended talk never rings, and the resume strip
        // (dead session) never rings — the portrait ring is what tells
        // a live strip from a resume strip in a still, the nav_glass
        // gold rim being identical in both modes. Change-only on the
        // flag below, before the name-equality return, so a
        // live-to-dead transition with the same sayadaw still clears
        // the ring on the next 1s tick; the frame owns visibility now,
        // the photo inside stays visible. No new view except the
        // ring-carrier frame, no new drawable, no new key.
        android.view.View ring = findViewById(R.id.nowStripPhotoRing);
        boolean ringing = name != null && name.length() > 0
                && AudioService.liveSession && AudioService.liveHasTrack && !AudioService.liveEnded;
        if (ringing != lastNewStripPhotoRing) {
            lastNewStripPhotoRing = ringing;
            if (ring != null) {
                // v14.16 (slice 1670): the ring crossfades in/out
                // instead of snapping — see Fx.crossfadeRing.
                Fx.crossfadeRing(ring, ringing ? R.drawable.shelf_ring_playing : 0);
            }
        }
        if (name == null || name.length() == 0) {
            newStripPhotoName = null;
            if (ring != null) ring.setVisibility(android.view.View.GONE);
            else pv.setVisibility(android.view.View.GONE);
            return;
        }
        if (ring != null) ring.setVisibility(android.view.View.VISIBLE);
        else pv.setVisibility(android.view.View.VISIBLE);
        if (name.equals(newStripPhotoName)) return;
        boolean firstPhoto = newStripPhotoName == null;
        newStripPhotoName = name;
        String url = AudioService.stripPhotoUrlForSayadaw(NewTalksActivity.this, name);
        // v14.22 (slice 1676): the photo crossfades on a sayadaw
        // change instead of swapping — see Fx.crossfadeStripPhoto.
        Fx.crossfadeStripPhoto((CircleImageView) pv, url, firstPhoto, new Runnable() {
            @Override public void run() {
                ImageLoader.load(NewTalksActivity.this, url, name, (CircleImageView) pv);
            }
        });
    }

    // v15.40 (slice 1907): the New talks resume strip's title melts on a
    // structural change instead of snapping — the mirror of Main's
    // v14.70 meltResumeStripTitle70. The 1s tick re-runs
    // syncNewTalksStrip in resume mode, and the title carries the
    // download mark (stripResumeDownloadMark / the lib branch's inline
    // check), so a download landing while New talks sits open flipped
    // its ⏳ to ⬇ in one frame on the visible strip; a ★ gained or
    // lost on a favour/clear return flips the same way. Same idiom
    // as Main: first resume sighting writes instantly (the slide-in
    // owns the entrance), steady ticks never touch the view, and a
    // change melts 250ms via Fx.fadeIn only while VISIBLE and settled
    // (alpha >= 0.999f); a stale live dip is cancelled first. The
    // tracker resets where the strip leaves resume mode — the live
    // entry below and both resume hide paths — so a later re-appear
    // with a different talk never melts under its own slide-in.
    // Rotation recreates the activity, so the tracker resets there
    // too. The live branch's stripTextLast tag is untouched:
    // fadeStripText keys on identity, not text, so a resume write
    // never desyncs its next live melt (v14.43 class).
    private String newResumeStripTitle = null;
    private void meltResumeStripTitleNew(android.widget.TextView tx, String want) {
        if (tx == null || want == null) return;
        Object aObjHs = tx.getTag(R.id.stripTextAnim);
        if (aObjHs instanceof android.animation.Animator) ((android.animation.Animator) aObjHs).cancel();
        String prevHs = newResumeStripTitle;
        newResumeStripTitle = want;
        if (prevHs == null) { tx.setText(want); return; }
        if (prevHs.equals(want)) return;
        tx.setText(want);
        if (tx.getVisibility() == android.view.View.VISIBLE && tx.getAlpha() >= 0.999f) Fx.fadeIn(tx, 250);
    }

    private void syncNewTalksStrip() {
        // v15.64 (slice 2043): playing-row mark — rebind once when the sounding talk changes under the list
        // (History v13.55, Talks v4.89/v11.15, same change-only rule): auto-advance, a Next from the strip, a
        // natural end (same URL, liveEnded flipped) or a stop moves the mark at once; steady ticks
        // never touch the adapter, so scrolling stays undisturbed. Runs before the strip's own early
        // returns, so a dead session clears the mark even while the strip hides.
        refreshNewTalksPlayingMarks();
        if (adapter != null && (!newPlayingUrl.equals(newRowPlayingUrl == null ? "" : newRowPlayingUrl)
                || !newPlayingLibUrl.equals(newRowPlayingLibUrl == null ? "" : newRowPlayingLibUrl)
                || !newPlayingLibPath.equals(newRowPlayingLibPath == null ? "" : newRowPlayingLibPath)
                || AudioService.liveEnded != newRowEnded)) {
            newRowPlayingUrl = newPlayingUrl;
            newRowPlayingLibUrl = newPlayingLibUrl;
            newRowPlayingLibPath = newPlayingLibPath;
            newRowEnded = AudioService.liveEnded;
            adapter.notifyDataSetChanged();
        }
        android.view.View strip = findViewById(R.id.nowStrip);
        if (strip == null) return;
        int navText = night ? android.graphics.Color.WHITE
                : android.graphics.Color.parseColor("#3B2314");
        // v13.03: real liquid glass — GlassBlur blurs the content behind this surface.
        GlassBlur.attach(strip, night);
        android.widget.TextView tx = findViewById(R.id.nowStripText);
        android.widget.TextView tg = findViewById(R.id.nowStripToggle);
        android.widget.TextView sb = findViewById(R.id.nowStripSub);
        android.widget.TextView nx = findViewById(R.id.nowStripNext);
        if (tx != null) tx.setTextColor(navText);
        if (tg != null) tg.setTextColor(navText);
        if (sb != null) sb.setTextColor(navText);
        if (nx != null) nx.setTextColor(navText);
        android.widget.TextView pv = findViewById(R.id.nowStripPrev);
        if (pv != null) pv.setTextColor(navText);
        // v4.39: same mini-player font-size gap as Talks' strip —
        // title uses the row title step (14/16/20), sub one step
        // below (12/14/16); glyphs stay 22sp like the nav icons.
        int fs = Prefs.fontSize(this);
        if (tx != null) tx.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fs == 0 ? 14 : (fs == 1 ? 16 : 20));
        if (sb != null) sb.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fs == 0 ? 12 : (fs == 1 ? 14 : 16));
        // v10.40: bound-trackless leg of the live rule (Talks'
        // v10.39 audit, confirmed for the no-bind strips):
        // liveSession alone stays true when a bound Room/Library
        // activity in the back stack keeps a stopped service
        // alive. A no-bind screen cannot call hasTrack(), so it
        // reads the liveHasTrack mirror instead; a trackless
        // live flag now takes the resume branch below at once.
        if (!AudioService.liveSession || !AudioService.liveHasTrack) {
            // v4.61: continue-listening strip, the mirror of
            // Talks' — a dead session used to leave this screen
            // with no mini-player at all even though a saved spot
            // (>5s, the same threshold) was waiting. Same rule
            // as Talks (Room talk only, saved URL matching the
            // last talk directly or via its downloaded file, spot
            // >5s) and same presentation: ↺ title over sayadaw ·
            // mm:ss resume cue, ▶ re-opening the Room, Next
            // hidden (advancing needs a live track), progress
            // filled from the stored duration (Main's v14.03 rule;
            // unknown duration still reads empty).
            // Global like the live strip — it shows whichever
            // sayadaw's talk was playing, not only this list's.
            // An explicit stop already clears the spot (v1.57),
            // so a stopped talk never shows here.
            newResumeStripMode = false;
            android.content.SharedPreferences rsp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String rKind = rsp.getString("lastKind", "");
            String rName = rsp.getString("lastName", "");
            String rTitle = rsp.getString("lastTitle", "");
            String rPage = rsp.getString("lastPage", "");
            String rUrl = rsp.getString("lastUrl", "");
            int rPos = rsp.getInt("lastPos", 0);
            String rSavedUrl = rsp.getString("lastPosUrl", "");
            // v15.63 (slice 2039): the Room resume match delegates
            // to the pure StripResume core (sameTalk direct, or the
            // last URL's dlMap filename ending the saved path, with
            // the v13.62 legacy-http identity leg). The dlMap is
            // parsed once here (dlMapNow2039) and reused by the
            // library leg below — History parses it twice, once
            // per leg. The core takes a plain Map because the JVM
            // test harness cannot construct org.json (stubbed in
            // the SDK android.jar); on-device the lookup is the
            // same JSONObject read, once per tick.
            java.util.Map<String, String> dlMap2039 = dlMapNow2039(rsp);
            boolean rMatch = StripResume.roomMatch(rSavedUrl, rUrl, dlMap2039);
            // v4.63: continue-listening strip for a dead LIBRARY
            // session — the mirror of the v4.61 Room strip: a
            // reboot or system kill used to leave this screen with
            // no strip at all after a downloaded file was paused,
            // even though its saved spot (>5s, same threshold) was
            // waiting. Same match rule as the library's own row
            // resume (file path, or its Room URL via the dlMap
            // reverse lookup), same presentation (↺ title over
            // the offline sub line + mm:ss resume cue, ★ when it
            // applies, ▶, Next hidden, progress empty). Tap and ▶
            // open the library with autoPlayPath, so the file
            // resumes from the spot in one tap; the live lib tap
            // below still only glides, because that track is
            // already playing.
            if ("lib".equals(rKind)) {
                String lTitle = rsp.getString("lastLibTitle", "");
                String lPath = rsp.getString("lastLibPath", "");
                boolean lMatch = false;
                // v14.01 (Settings F1 library half, all four strips):
                // a 0-byte file is not downloaded — the strip used to
                // claim ⬇ for a dead stub. The in-progress exception
                // keeps the v11.58 order (a live job reads ⏳ before
                // any exists/length check, like stripResumeDownloadMark).
                if (lPath.length() > 0 && rPos > 5000 && rSavedUrl.length() > 0
                        && new java.io.File(lPath).isFile()
                        && (new java.io.File(lPath).length() > 0
                            || Fx.stripInProgressDlFilenames(NewTalksActivity.this, rsp).contains(new java.io.File(lPath).getName()))) {
                    // v15.63 (slice 2039): the library resume match
                    // delegates to the pure StripResume core (the
                    // path itself, or a dlMap URL whose filename is
                    // this file's name and whose talk is the saved
                    // one), over the map parsed once above. The
                    // v14.01 file gate above stays inline.
                    lMatch = StripResume.libMatch(rSavedUrl, lPath, dlMap2039);
                }
                if (lTitle.length() == 0 || !lMatch) {
                    syncNewTalksStripPhoto(null);
                    newResumeStripTitle = null;
                    Fx.hideStrip(strip);
                    return;
                }
                boolean lFav = false;
                try {
                    java.util.Set<String> ft = rsp.getStringSet("favTalks", null);
                    if (ft != null) {
                        String fn = new java.io.File(lPath).getName();
                        org.json.JSONObject map = new org.json.JSONObject(rsp.getString("dlMap", "{}"));
                        java.util.Iterator<String> keys = map.keys();
                        // v8.49: exactly-one claimant, never the first favourited one —
                        // a dual-claimed file showed ★ when the OTHER talk was favourited
                        // (v8.46 library-strip class, here on the Main/Talks/Settings strips).
                        int nClaim49 = 0;
                        String onlyClaim49 = null;
                        while (keys.hasNext()) {
                            String u = keys.next();
                            if (fn.equals(map.optString(u, ""))) { nClaim49++; onlyClaim49 = u; }
                        }
                        if (nClaim49 == 1 && onlyClaim49 != null && ft.contains(onlyClaim49)) lFav = true;
                    }
                } catch (Exception ignored) {}
                int lSec = rPos / 1000;
                String lTime = Fx.stripResumeTime(lSec);
                // v15.40 (slice 1907): the stored duration (v13.96) fills the bar — Main's v14.03 rule; unknown duration still reads empty (stripResumeProgress).
                int lDur = Fx.stripResumeDuration(NewTalksActivity.this, rsp, rSavedUrl, lPath);
                // v8.52: Main's v7.94 resume mark, mirrored — the proven file is offline, like Main's lib branch.
                meltResumeStripTitleNew(tx, "↺ " + lTitle + (lFav ? " ★" : "") + (Fx.stripInProgressDlFilenames(NewTalksActivity.this, rsp).contains(new java.io.File(lPath).getName()) ? " ⏳" : " ⬇"));
                // v5.46: a dead library strip names its recorded
                // sayadaw like the live strip since v5.45, in the
                // Room strip's "name · mm:ss မှ ဆက်နားထောင်ရန်"
                // shape; an unrecorded download keeps today's
                // generic line, never a guessed name.
                String libSd = Fx.stripLibSayadaw(NewTalksActivity.this, rsp);
                if (sb != null) sb.setText(libSd != null ? libSd + " · " + lTime + Lang.t(NewTalksActivity.this, "resume_from") : Lang.t(NewTalksActivity.this, "downloaded_talk") + " · " + lTime + Lang.t(NewTalksActivity.this, "resume_from"));
                if (tg != null) tg.setText("▶");
                if (nx != null) Fx.crossfadeStripBtn(nx, false);
                // v7.31: Previous hides with Next in resume mode
                // (Main's mirror fix) — stepping back needs a
                // live track exactly like advancing does, and
                // this handler had no resume branch, so a ⏮ tap
                // on a ↺ strip ACTION_PREV-ed into a different
                // talk instead of resuming this one.
                android.view.View pv0 = findViewById(R.id.nowStripPrev);
                if (pv0 != null) Fx.crossfadeStripBtn(pv0, false);
                android.widget.ProgressBar lPb = findViewById(R.id.nowStripProgress);
                if (lPb != null) Fx.stripResumeProgress(lPb, rPos, lDur);
                newResumeStripMode = true;
                syncNewTalksStripPhoto(libSd);
                Fx.showStrip(strip);
                return;
            }
            if (!"room".equals(rKind) || rName.length() == 0 || rTitle.length() == 0
                    || rPage.length() == 0 || rPos <= 5000 || !rMatch) {
                syncNewTalksStripPhoto(null);
                newResumeStripTitle = null;
                Fx.hideStrip(strip);
                return;
            }
            boolean rFav = false;
            try {
                java.util.Set<String> ft = rsp.getStringSet("favTalks", null);
                if (ft != null) rFav = ft.contains(rUrl);
            } catch (Exception ignored) {}
            int rSec = rPos / 1000;
            String rTime = Fx.stripResumeTime(rSec);
            int rDur = Fx.stripResumeDuration(NewTalksActivity.this, rsp, rUrl, null);
            // v8.52: Main's v7.94 resume download mark, mirrored — a ↺ strip here never said whether resuming streams or plays offline.
            meltResumeStripTitleNew(tx, "↺ " + rTitle + (rFav ? " ★" : "") + Fx.stripResumeDownloadMark(NewTalksActivity.this, rsp, rUrl));
            if (sb != null) sb.setText(rName + " · " + rTime + Lang.t(NewTalksActivity.this, "resume_from"));
            if (tg != null) tg.setText("▶");
            if (nx != null) Fx.crossfadeStripBtn(nx, false);
            // v7.31: same Previous hide as the library resume
            // branch above — a ↺ strip never shows ⏮.
            android.view.View pv0 = findViewById(R.id.nowStripPrev);
            if (pv0 != null) Fx.crossfadeStripBtn(pv0, false);
            android.widget.ProgressBar rPb = findViewById(R.id.nowStripProgress);
            if (rPb != null) Fx.stripResumeProgress(rPb, rPos, rDur);
            newResumeStripMode = true;
            syncNewTalksStripPhoto(rName);
            Fx.showStrip(strip);
            return;
        }
        newResumeStripMode = false;
        newResumeStripTitle = null; // v15.40: live branch owns tx via fadeStripText from here
        if (nx != null) Fx.crossfadeStripBtn(nx, true);
        // v7.31: Previous returns with Next on a live session.
        android.view.View pv0 = findViewById(R.id.nowStripPrev);
        if (pv0 != null) Fx.crossfadeStripBtn(pv0, true);
        android.content.SharedPreferences sp =
                getSharedPreferences("dhamma", MODE_PRIVATE);
        String kind = sp.getString("lastKind", "");
        String text = null;
        String subText = null;
        if ("room".equals(kind)) {
            String name = sp.getString("lastName", "");
            String title = sp.getString("lastTitle", "");
            if (name.length() > 0 && title.length() > 0) {
                text = "🎵 " + title;
                subText = name;
            }
        } else if ("lib".equals(kind)) {
            String title = sp.getString("lastLibTitle", "");
            if (title.length() > 0) {
                text = "🎵 " + title;
                // v5.45: a library session names its sayadaw when
                // one was recorded at download time, like a Room
                // session does above; an unrecorded download keeps
                // today's generic line, never a guessed name.
                String libSd = Fx.stripLibSayadaw(NewTalksActivity.this, sp);
                subText = libSd != null ? libSd : Lang.t(NewTalksActivity.this, "downloaded_talk");
            }
        }
        if (text == null) { syncNewTalksStripPhoto(null); Fx.hideStrip(strip); return; }
        boolean stripFav = false;
        try {
            java.util.Set<String> ft = sp.getStringSet("favTalks", null);
            if (ft != null) {
                if ("room".equals(kind)) {
                    stripFav = ft.contains(sp.getString("lastUrl", ""));
                } else if ("lib".equals(kind)) {
                    String lp = sp.getString("lastLibPath", "");
                    if (lp.length() > 0) {
                        String fn = new java.io.File(lp).getName();
                        org.json.JSONObject map =
                                new org.json.JSONObject(sp.getString("dlMap", "{}"));
                        java.util.Iterator<String> keys = map.keys();
                        // v8.49: exactly-one claimant, never the first favourited one —
                        // a dual-claimed file showed ★ when the OTHER talk was favourited
                        // (v8.46 library-strip class, here on the Main/Talks/Settings strips).
                        int nClaim49 = 0;
                        String onlyClaim49 = null;
                        while (keys.hasNext()) {
                            String u = keys.next();
                            if (fn.equals(map.optString(u, ""))) { nClaim49++; onlyClaim49 = u; }
                        }
                        if (nClaim49 == 1 && onlyClaim49 != null && ft.contains(onlyClaim49)) stripFav = true;
                    }
                }
            }
        } catch (Exception ignored) {}
        if (stripFav) text += " ★";
        // v4.72: the strip's sleep mark gains the live countdown —
        // the Room line and the Settings sleep row already showed
        // it, but every mini-player only said 😴, so a listener
        // off the Room could not tell when playback would stop.
        // Running: "😴 M:SS"; pause-suspended: "😴 ⏸ M:SS"
        // (Room's order); no remaining: the bare mark, as before.
        // No bind: the service's liveSleepRemainingMs static,
        // refreshed by its own tick + every sleep edge.
        if (AudioService.liveSleepActive) {
            long sr = AudioService.liveSleepRemainingMs;
            // v4.82: the one-shot has no countdown — name it instead.
            if (AudioService.liveSleepEndOfTalk) text += AudioService.livePlaying
                    ? " 😴 " + Lang.t(NewTalksActivity.this, "sleep_end")
                    : " 😴 ⏸ " + Lang.t(NewTalksActivity.this, "sleep_end");
            else if (sr > 0) text += AudioService.livePlaying
                    ? " 😴 " + AudioService.sleepClock(sr)
                    : " 😴 ⏸ " + AudioService.sleepClock(sr);
            else text += " 😴";
        }
        // v5.67: the strip names downloads in progress (" ⏳ N") —
        // the mirror of Main's v4.27 strip suffix. Same
        // app-scoped rule as Main (Dhamma destination or a
        // dlMap source URL), same order (★/😴/⏳/◈), same 1s
        // tick refresh; zero adds nothing, resume mode returns
        // above, no new view. Suffix only.
        try {
            java.util.Set<String> dlUrls = null;
            try {
                org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
                dlUrls = new java.util.HashSet<String>();
                java.util.Iterator<String> keys = map.keys();
                while (keys.hasNext()) dlUrls.add(keys.next());
            } catch (Exception ignored) {}
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            // v11.76: filter in the provider — this runs on the 1s strip tick and
            // used to pull every download on the main thread and discard the
            // finished ones in Java; the status check below stays as a guard.
            android.database.Cursor c = dm.query(new android.app.DownloadManager.Query()
                    .setFilterByStatus(android.app.DownloadManager.STATUS_RUNNING
                            | android.app.DownloadManager.STATUS_PAUSED
                            | android.app.DownloadManager.STATUS_PENDING));
            int progN = 0;
            if (c != null) {
                try {
                    int statusCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS);
                    int uriCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_LOCAL_URI);
                    int srcCol = c.getColumnIndex(android.app.DownloadManager.COLUMN_URI);
                    while (c.moveToNext()) {
                        int st = statusCol >= 0 ? c.getInt(statusCol) : -1;
                        if (st != android.app.DownloadManager.STATUS_RUNNING
                                && st != android.app.DownloadManager.STATUS_PAUSED
                                && st != android.app.DownloadManager.STATUS_PENDING) continue;
                        String local = uriCol >= 0 ? c.getString(uriCol) : null;
                        String src = srcCol >= 0 ? c.getString(srcCol) : null;
                        if ((local != null && local.contains("Dhamma/"))
                                || (src != null && dlUrls != null && (dlUrls.contains(src) || dlUrls.contains(SiteHttp.identityUrl(src))))) progN++;
                    }
                } finally { c.close(); }
            }
            if (progN > 0) text += " ⏳ " + progN;
        } catch (Exception ignored) {}
        // v4.68: the strip reads the hand queue — the mirror of
        // Main's v4.67 strip suffix. Same counting rule as Main,
        // so every strip agrees: Room sessions count this
        // sayadaw's saved queue (Prefs.savedQueue, the store the
        // Room restores from), library sessions count saved queue
        // paths that still exist as files. Suffix only, no new
        // view; the 1s tick re-reads it. Resume mode is untouched
        // (it returns above, Next hidden there); zero/empty adds
        // nothing.
        try {
            // v5.80: the strip's ◈ count skips the talk now playing —
            // the plain card's v5.43 waiting count already does, but
            // the strips counted the raw saved queue, so a playing
            // talk still saved (a service-restart play that never
            // passed the Room/library pop) read as one waiting on
            // every strip and zero on the card. Room skips lastUrl,
            // library skips lastLibPath — prefs the strip already
            // reads, so no new read and no tick cost.
            String curUrl = sp.getString("lastUrl", "");
            String curLib = sp.getString("lastLibPath", "");
            int qN = 0;
            if ("room".equals(kind)) {
                String saved = Prefs.savedQueue(NewTalksActivity.this,
                        sp.getString("lastName", ""));
                // v12.69: the drawer-header rule as a pure core —
                // dedup + skip the sounding talk (sim1272: a
                // repeated URL counted twice here and once in the
                // drawer header, v11.04). Ghosts are shed from
                // the store at list load (Room peek / chooser
                // prune), so this count agrees with the drawer
                // on a clean store.
                qN = Prefs.waitingQueueCountPure(saved, curUrl);
            } else if ("lib".equals(kind)) {
                String saved = Prefs.savedQueueLib(NewTalksActivity.this);
                if (saved.length() > 0) {
                    for (String line : saved.split("\n"))
                        if (line.trim().length() > 0
                                && new java.io.File(line.trim()).isFile()
                                && !line.trim().equals(curLib)) qN++;
                }
            }
            if (qN > 0) text += " · ◈ " + qN;
        } catch (Exception ignored) {}
        if (AudioService.liveEnded) text += " · " + Lang.t(NewTalksActivity.this, "notif_state_ended");
        // v14.21 (slice 1675): the strip title/sub fade on a talk
        // change, keyed on the talk itself (Room URL / library
        // path) — the composed strings tick every second (sleep
        // countdown on the title, live clock on the sub), so the
        // text can never be the key.
        String stripTalkId = "room".equals(kind) ? sp.getString("lastUrl", "") : sp.getString("lastLibPath", "");
        if (tx != null) Fx.fadeStripText(tx, text, stripTalkId);
        // v5.32: live clock on the strip — sayadaw name + elapsed/total.
        android.widget.ProgressBar pb = findViewById(R.id.nowStripProgress);
        if (subText != null) subText += Fx.stripClockSuffixForStrip(pb, AudioService.livePositionMs, AudioService.liveDurationMs, Prefs.stripClockRemaining(NewTalksActivity.this));
        if (sb != null && subText != null) Fx.fadeStripText(sb, subText, stripTalkId);
        Fx.syncStripProgress(pb); // v7.93: skips while a mini-seek drag is in flight
        if (tg != null) Fx.flipStripGlyph(tg, AudioService.livePlaying);
        // v6.01: live strip's portrait — Room names lastName, library
        // names its recorded sayadaw (Fx.stripLibSayadaw, null when
        // unrecorded); both resolve through syncNewTalksStripPhoto's
        // exact-name catalog match, never a guessed face.
        syncNewTalksStripPhoto("room".equals(kind) ? sp.getString("lastName", "")
                : ("lib".equals(kind) ? Fx.stripLibSayadaw(NewTalksActivity.this, sp) : null));
        Fx.showStrip(strip);
    }

    private void startNewTalksStripTick() {
        if (stripTick != null) return;
        stripTick = new Runnable() {
            @Override public void run() {
                if (stripTick == null) return;
                // v11.76: re-post in finally — a throw inside syncNewTalksStrip used
                // to kill the 1s tick outright, freezing strip title/clock/
                // progress/glyph until the next onPause/onResume cycle.
                try { syncNewTalksStrip(); }
                finally { stripHandler.postDelayed(this, 1000); }
            }
        };
        stripHandler.post(stripTick);
    }

    private void stopNewTalksStripTick() {
        if (stripTick != null) {
            stripHandler.removeCallbacks(stripTick);
            stripTick = null;
        }
    }

    private void wireNewTalksStrip() {
        android.view.View nowStrip = findViewById(R.id.nowStrip);
        if (nowStrip != null) {
            Fx.press(nowStrip);
            // v4.73: the strip's progress bar seeks (see Fx.miniSeekBar).
            Fx.miniSeekBar(NewTalksActivity.this, nowStrip);
            // v5.33: tap the strip's sub line (sayadaw + clock) flips
            // the shared clock mode elapsed/total <-> remaining and
            // re-syncs at once (the 1s tick would catch it anyway).
            // The sub view consumes the tap, so it no longer opens
            // the player — the title line still does. Its long-press
            // forwards to the strip, so stop/dismiss is unchanged.
            // Resume strips show no clock (they return before the
            // live branch), so a flip there is invisible until the
            // next live session, which then shows the chosen mode.
            // v13.82 (Talks F1): the flip is a no-op there — the sub
            // line is only a clock control while a live session is
            // showing (mirror of Main's v13.12 M7 guard).
            android.widget.TextView stripSub = findViewById(R.id.nowStripSub);
            if (stripSub != null) {
                stripSub.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        // v13.82 (Talks F1, sim1412): the flip is a live-mode
                        // affordance only — a resume strip shows no
                        // clock, so a flip there was invisible at tap
                        // time and silently re-timed the next live
                        // session. In resume mode (same live recheck
                        // as the dismiss branch, v12.57) the tap is a
                        // deliberate no-op instead of a silent mutation.
                        // Mirror of Main's v13.12 M7 guard.
                        if (newResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) return;
                        Prefs.setStripClockRemaining(NewTalksActivity.this, !Prefs.stripClockRemaining(NewTalksActivity.this));
                        Fx.tap(NewTalksActivity.this);
                        syncNewTalksStrip();
                    }
                });
                stripSub.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) { return nowStrip.performLongClick(); }
                });
            }
            nowStrip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        String lp = sp.getString("lastLibPath", "");
                        if (lp.length() > 0 && new java.io.File(lp).isFile()) {
                            Fx.tap(NewTalksActivity.this);
                            Fx.morph(v);
                            Intent li = new Intent(NewTalksActivity.this, DownloadedActivity.class);
                            li.putExtra("jumpPath", lp);
                            // v4.63: in dead-library resume mode
                            // the tap also auto-plays the file from
                            // its saved spot; a live session never
                            // passes this flag, so it still only
                            // glides to the playing file.
                            // v12.57: live recheck, not just the cached
                            // flag — newResumeStripMode refreshes on
                            // the 1s tick, so a session dying <1s before
                            // this tap left the flag false and the tap
                            // glided to the library WITHOUT auto-playing
                            // (one extra tap to resume — the same ≤1s
                            // stale-flag class v12.55 closed for the
                            // four transport buttons). The strip's own
                            // branch test is !liveSession ||
                            // !liveHasTrack, so re-checking the same
                            // statics here can only auto-play a
                            // genuinely dead session, never a live one.
                            if (newResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) li.putExtra("autoPlayPath", lp);
                            Fx.go(NewTalksActivity.this, li);
                            return;
                        }
                        android.widget.Toast.makeText(NewTalksActivity.this, Lang.t(NewTalksActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(NewTalksActivity.this, Lang.t(NewTalksActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(NewTalksActivity.this, RoomActivity.class);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.putExtra("autoUrl", sp.getString("lastUrl", ""));
                    i.putExtra("autoTitle", sp.getString("lastTitle", ""));
                    // v11.86: reuse the Room already in the stack, like the Next/Prev
                    // buttons in this same method — the Room header opens Talks without
                    // finishing the Room, so this tap used to push a SECOND Room for the
                    // same talk on top, and Back then showed the first Room again instead
                    // of the list. onNewIntent's no-action tap path (v8.09) handles the
                    // re-delivery: same page returns, the poll already syncs the row.
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.tap(NewTalksActivity.this);
                    Fx.morph(v);
                    Fx.go(NewTalksActivity.this, i);
                }
            });
            nowStrip.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    // v4.61: resume mode has no session to stop —
                    // dismiss the saved spot instead (lastPos
                    // only; the talk itself is untouched, so
                    // reopening it starts from the beginning)
                    // and the next tick hides the strip.
                    // v12.57: live recheck, not just the cached flag —
                    // newResumeStripMode refreshes on the 1s tick, so a
                    // session dying <1s before this long-press left the
                    // flag false and the press ACTION_STOPped a trackless
                    // service (phantom "talk_stopped" toast, saved spot
                    // NOT dismissed — the same ≤1s stale-flag class
                    // v12.55 closed for the Toggle long-press). The
                    // strip's own branch test is !liveSession ||
                    // !liveHasTrack, so re-checking the same statics here
                    // can only dismiss a genuinely dead session, never
                    // stop a live one.
                    if (newResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        // v5.34: same as Talks' dismiss — the
                        // per-talk spot goes with the global one.
                        String dismissUrl = getSharedPreferences("dhamma", MODE_PRIVATE)
                                .getString("lastPosUrl", "");
                        getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                                .putInt("lastPos", 0).putString("lastPosUrl", "").apply();
                        if (dismissUrl.length() > 0) Prefs.talkPosRemoveBoth(NewTalksActivity.this, dismissUrl);
                        Fx.tap(NewTalksActivity.this);
                        Fx.morph(v);
                        // v13.28: same as Downloaded's dismiss — one resume spot cleared,
                        // not the history; toast resume_cleared (Main's key), not
                        // hist_cleared (Settings' real clear-history key).
                        android.widget.Toast.makeText(NewTalksActivity.this, Lang.t(NewTalksActivity.this, "resume_cleared"), android.widget.Toast.LENGTH_SHORT).show();
                        syncNewTalksStrip();
                        // New talks rows carry no ↺ mark, so this
                        // rebind changes nothing visible today; it
                        // keeps the dismiss path a faithful mirror
                        // of Talks' (v12.41) if a mark ever lands.
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return true;
                    }
                    Intent svc = new Intent(NewTalksActivity.this, AudioService.class);
                    svc.setAction(AudioService.ACTION_STOP);
                    startService(svc);
                    Fx.tap(NewTalksActivity.this);
                    Fx.morph(v);
                    android.widget.Toast.makeText(NewTalksActivity.this, Lang.t(NewTalksActivity.this, "talk_stopped"), android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
            });
        }
        android.view.View nowToggle = findViewById(R.id.nowStripToggle);
        if (nowToggle != null) nowToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v4.61: resume mode has no live track to toggle
                // — the ▶ button re-opens the Room like the
                // strip tap, which resumes from the spot.
                // v12.55: live recheck, not just the cached flag — newResumeStripMode
                // refreshes on the 1s tick, so a session dying <1s before this tap left
                // the flag false and the tap ACTION_TOGGLEd a trackless service (dead
                // tap, tap-twice-to-resume — the exact bug Settings v11.90 fixed for
                // its Toggle). The strip's own branch test is !liveSession ||
                // !liveHasTrack, so re-checking the same statics here can only route a
                // genuinely dead session to resume, never a live one.
                if (newResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                    android.view.View strip2 = findViewById(R.id.nowStrip);
                    if (strip2 != null) strip2.performClick();
                    return;
                }
                Intent svc = new Intent(NewTalksActivity.this, AudioService.class);
                svc.setAction(AudioService.ACTION_TOGGLE);
                startService(svc);
                Fx.tap(NewTalksActivity.this);
                Fx.morph(v);
            }
        });
        if (nowToggle != null) nowToggle.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                // v4.61: resume mode has no session to stop —
                // hand the long-press to the strip, which
                // dismisses the saved spot instead.
                // v12.55: same live recheck as the click above — a stale-false flag here
                // ACTION_STOPs a trackless service instead of dismissing the saved spot
                // (Settings v11.90 fixed the same site for its Toggle long-press).
                if (newResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                    android.view.View strip2 = findViewById(R.id.nowStrip);
                    if (strip2 != null) return strip2.performLongClick();
                    return true;
                }
                Intent svc = new Intent(NewTalksActivity.this, AudioService.class);
                svc.setAction(AudioService.ACTION_STOP);
                startService(svc);
                Fx.tap(NewTalksActivity.this);
                Fx.morph(v);
                android.widget.Toast.makeText(NewTalksActivity.this, Lang.t(NewTalksActivity.this, "talk_stopped"), android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        android.view.View nowNext = findViewById(R.id.nowStripNext);
        if (nowNext != null) {
            Fx.press(nowNext);
            nowNext.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // v7.34: same v7.31 resume-mode guard as Prev —
                    // Prev/Toggle/Forward all resume the ↺ talk in
                    // resume mode, but Next had no guard: a tick-
                    // window ⏭ tap ACTION_NEXT-ed into a Room with
                    // no live track, where handleNext finds no
                    // current talk and stays silent instead of
                    // resuming the saved talk like every other
                    // strip transport button. Now it resumes via
                    // the strip tap, exactly like ⏮/⏩/▶ do.
                    // v12.55: live recheck, not just the cached flag (Settings v11.88) —
                    // newResumeStripMode refreshes on the 1s tick, so a session dying
                    // <1s before this tap left the flag false and the tap ACTION_NEXT-ed
                    // into a trackless Room instead of resuming the ↺ talk.
                    if (newResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    Fx.tap(NewTalksActivity.this);
                    Fx.morph(v);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        Intent li = new Intent(NewTalksActivity.this, DownloadedActivity.class);
                        li.setAction(DownloadedActivity.ACTION_NEXT);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        Fx.go(NewTalksActivity.this, li);
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(NewTalksActivity.this, Lang.t(NewTalksActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(NewTalksActivity.this, RoomActivity.class);
                    i.setAction(RoomActivity.ACTION_NEXT);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.go(NewTalksActivity.this, i);
                }
            });
        }

        android.view.View nowPrev = findViewById(R.id.nowStripPrev);
        if (nowPrev != null) {
            Fx.press(nowPrev);
            nowPrev.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // v7.31: belt-and-braces for the tick window
                    // between a session dying and the next sync
                    // hiding this button — a resume-mode ⏮ tap
                    // resumes the ↺ talk like ▶/⏩ do, instead
                    // of ACTION_PREV-ing into a different talk.
                    // v12.55: same live recheck as Next above (Settings v11.88) — the cached
                    // flag can lag a just-died session by up to one 1s tick, and a stale-false
                    // flag here ACTION_PREVs into a trackless Room instead of resuming the
                    // ↺ talk.
                    if (newResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    Fx.tap(NewTalksActivity.this);
                    Fx.morph(v);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        Intent li = new Intent(NewTalksActivity.this, DownloadedActivity.class);
                        li.setAction(DownloadedActivity.ACTION_PREV);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        Fx.go(NewTalksActivity.this, li);
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(NewTalksActivity.this, Lang.t(NewTalksActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(NewTalksActivity.this, RoomActivity.class);
                    i.setAction(RoomActivity.ACTION_PREV);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.go(NewTalksActivity.this, i);
                }
            });
        }
    }

    // v15.63 (slice 2039): the download map as a plain map
    // for the StripResume core — parsed once per strip sync
    // (History parses it inline once per leg). Torn JSON reads
    // as an empty map, so a torn write hides the resume strip
    // for one pass, never a crash.
    private java.util.Map<String, String> dlMapNow2039(android.content.SharedPreferences rsp) {
        java.util.Map<String, String> out = new java.util.HashMap<String, String>();
        try {
            org.json.JSONObject map = new org.json.JSONObject(rsp.getString("dlMap", "{}"));
            java.util.Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                out.put(k, map.optString(k, ""));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private java.util.Set<String> readPlayedTalks() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        try {
            java.util.Set<String> raw = getSharedPreferences("dhamma", MODE_PRIVATE).getStringSet("playedTalks", null);
            if (raw != null) out.addAll(raw);
        } catch (Exception ignored) {}
        return out;
    }

    // v15.50 (slice 1966): the new-talk entries for the rail — one
    // pass over the catalogue in order. The 1921 gate at sayadaw
    // level (loadNewSet + hasUnseenNew over the played set + resume
    // keys) decides which sayadaws pay a cache-file read, so
    // undotted sayadaws cost one prefs read and nothing else; a
    // dotted sayadaw contributes its cached [title, url] pairs in
    // page order. A failed loadNewSet reads as "no new talks" (the
    // 1920 rule: failed loads keep the old set); a missing cache
    // file drops the sayadaw — a new talk with no cached title has
    // no row, never a title-less row (the 1962 aged-out edge).
    private java.util.List<NewTalkRail.Entry> collectNewTalkEntries(
            java.util.Set<String> played, java.util.Set<String> resumeKeys) {
        java.util.List<NewTalkRail.Entry> out =
                new java.util.ArrayList<NewTalkRail.Entry>();
        try {
            ensureCatalog();
            if (catOrder == null) return out;
            for (String[] row : catOrder) {
                if (row == null || row.length < 3) continue;
                if (row[0] == null || row[0].length() == 0) continue;
                if (row[2] == null || row[2].length() == 0) continue;
                java.util.Set<String> news = TalkListCache.loadNewSet(this, row[2]);
                if (!TalkListCache.hasUnseenNew(news, played, resumeKeys)) continue;
                java.util.List<String[]> cached = TalkListCache.load(this, row[2]);
                if (cached == null || cached.isEmpty()) continue;
                out.add(new NewTalkRail.Entry(row[0], news, cached));
            }
        } catch (Exception ignored) {}
        return out;
    }

    // Sayadaw -> {group, page_url, photo_url} from the bundled
    // catalog (HistoryActivity.catalogFor verbatim: the asset only
    // changes with an app update, which is a new process, so a
    // per-process cache cannot go stale). Aliases resolve to the
    // current name first, so a pre-rename name stored in a recent
    // row still opens its Room. catOrder keeps the same rows in
    // asset order — the rail's catalogue order across sayadaws
    // (MainActivity.loadCatalog builds `all` from the same asset
    // in the same order).
    private void ensureCatalog() {
        if (catCache != null) return;
        catCache = new java.util.HashMap<String, String[]>();
        catOrder = new java.util.ArrayList<String[]>();
        java.io.InputStream in = null;
        try {
            in = getAssets().open("sayadaws.json");
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) baos.write(buf, 0, read);
            org.json.JSONArray arr = new org.json.JSONArray(
                    new String(baos.toByteArray(), "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                // v13.30 (Catalog F3): a bad row must not hide
                // every sayadaw after it — skip a non-object
                // entry instead of aborting the cache build.
                org.json.JSONObject o;
                try { o = arr.getJSONObject(i); } catch (Exception ignored) { continue; }
                String n = o.optString("name", "");
                if (n.length() == 0) continue;
                String[] row = new String[]{
                        n,
                        o.optString("group", ""),
                        o.isNull("page_url") ? "" : o.optString("page_url", ""),
                        o.isNull("photo_url") ? "" : o.optString("photo_url", "")};
                catCache.put(n, new String[]{row[1], row[2], row[3]});
                catOrder.add(row);
            }
        } catch (Exception ignored) {
        } finally {
            if (in != null) { try { in.close(); } catch (Exception ignored) {} }
        }
    }

    private String[] catalogFor(String name) {
        if (name == null || name.length() == 0) return null;
        ensureCatalog();
        return catCache.get(Prefs.canonicalSayadaw99(this, name));
    }
}
