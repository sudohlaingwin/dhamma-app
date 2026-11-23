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

// v15.62 (slice 2033): "See all" on the Main "Bookmarks" rail —
// the full list of every saved minute, across all talks. The rail
// shows 5 cards (BmRail.MAX_CARDS) while the listener's marks can
// run to dozens; the Room's bookmark list dialog (v12.64) is
// per-talk only, so no screen anywhere showed all marks together.
// Rows are the rail card's own shape (44dp portrait + sayadaw
// name + "12:34 · title" + the History "›" chevron); a tap lands
// the Room on the mark (talkPosPutBoth + the Room intent, the
// openBmTalk1962 rule). Mini-player strip since v15.63 (slice
// 2037, the History v13.54 precedent); no clear-all (a bulk
// destructive verb, the 2016-rejected class — marks are removed
// per-row from the Room dialog or the rail menu), no downloaded
// badge (the badge family is complete on the rails; the list stays
// light — one dlMap parse per row on an unbounded list would
// break the v5.69 cost rule).
public class BookmarksActivity extends Activity {
    private TextView headerTitle;
    private ListView listView;
    private TextView emptyText;
    private java.util.List<BmAll.Row> items =
            new java.util.ArrayList<BmAll.Row>();
    // v15.65 (slice 2049): the search keeps the unfiltered pick
    // beside the shown list — a keystroke re-filters in memory
    // (the Downloaded applyDlFilter shape), never re-reading the
    // cache files per keystroke.
    private java.util.List<BmAll.Row> bmAllItems =
            new java.util.ArrayList<BmAll.Row>();
    private BaseAdapter adapter;
    private boolean night;
    private java.util.Map<String, String[]> catCache;
    // v15.62 (slice 2033): row-tap debounce — its own clock (the
    // chooser's 700ms rule, v12.28: an invalid tap never consumes
    // the window). A rapid double-tap must never stack two Rooms.
    private long lastBmTapMs = 0;
    // v15.63 (slice 2037): real mini-player on this screen (the
    // History v13.54 precedent) — the bookmarks screen used to
    // show nothing about a playing marked talk: tapping a row
    // opened the Room and Back returned here with no way to see,
    // pause or jump back to the talk. Same 1s-tick shape as
    // History: stripHandler + stripTick, bmResumeStripMode (the
    // resume branch), and bmStripPhotoName (the face the strip
    // last showed).
    private android.os.Handler stripHandler = new android.os.Handler();
    private Runnable stripTick;
    private boolean bmResumeStripMode = false;
    private String bmStripPhotoName = null;
    private boolean lastBmStripPhotoRing = false;
    // v15.64 (slice 2041): playing-row mark (History v13.55, Talks
    // v4.89/v11.07, same rules) — the Bookmarks rows carried no live
    // marks: a listener browsing saved minutes could not spot the
    // talk the strip is naming without remembering its title. The
    // sounding identities below mirror History's
    // refreshHistoryPlayingMarks playing half: bmPlayingUrl for a
    // Room session (lastUrl), bmPlayingLibUrl for a library session
    // via the dlMap reverse lookup (exactly-one claimant, v8.44),
    // and bmPlayingLibPath for a library session matched directly
    // — a path-form row IS the file (playRecent v12.82 writes the
    // same path), so no lookup is needed there. bmRowPlayingUrl /
    // bmRowPlayingLibUrl / bmRowPlayingLibPath / bmRowEnded are the
    // last bound snapshots; the 1s tick rebinds once when any of
    // them changes.
    private String bmPlayingUrl = "";
    private String bmPlayingLibUrl = "";
    private String bmPlayingLibPath = "";
    private String bmRowPlayingUrl = null;
    private String bmRowPlayingLibUrl = null;
    private String bmRowPlayingLibPath = null;
    private boolean bmRowEnded = false;
    // v15.65 (slice 2049): search on this screen — the field, the
    // clear ✕, the live query and the keystroke flag (the
    // Downloaded dlSearchField / dlClearBtn / dlQuery /
    // dlSearchKeystroke shape, v1.51 + v15.33). The query is the
    // trimmed field text; the flag is true only while the watcher
    // drives a pass, so the ✕ melts on keystrokes and lands
    // instantly on silent passes (rotation / restore / onResume).
    private android.widget.EditText bmSearchField = null;
    private android.view.View bmClearBtn = null;
    private String bmQuery = "";
    private boolean bmSearchKeystroke = false;
    // v15.65 (slice 2051): sort on this screen — the button and the
    // persisted mode (the Downloaded dlSortMode shape, v1.47/v4.24).
    // The mode is read from Prefs in wireBmSort(); the button label
    // names the CURRENT order (bmSortLabel), so the order reads in
    // a still. Rotation recreates the activity and re-reads the
    // persisted mode — no watcher or saved-state needed.
    private android.widget.Button bmSortBtn = null;
    private int bmSortMode = 0;
    // v15.66 (slice 2055): "Play all marks" on this screen — the
    // header button + the handler's own 700ms clock + the
    // change-only snapshot (the Favourites 2044 shape). bmItems is
    // the shown run as resolved Items (stream URLs, one per shown
    // row) — rebuilt by applyBmSearch on every pass, so the
    // handler and the visibility gate always read the SHOWN run
    // (the 2047 rule), never a stale pick.
    private android.widget.Button btnBmPlayAll = null;
    private long lastBmPlayAllMs = 0;
    private Boolean lastBmPlayAllVisible = null;
    // v15.68 (slice 2067): "Shuffle play" on this screen — the
    // chooser's v13.93 shuffle-play, the third of the three
    // full-list screens to get it: one tap shuffles the shown run
    // of marks into the queue and opens the Room on the first
    // shuffled mark at its minute. lastBmShuffleMs is the handler's
    // own 700ms clock (the chooser's v12.28 rule — a separate clock
    // from Play-all-marks', so a Shuffle tap never eats a Play-all
    // tap); btnBmShuffle is the header button beside Play-all
    // (GONE until a list lands, the same gate, the same
    // change-only snapshot — syncBmPlayAllBtn paints both).
    private long lastBmShuffleMs = 0;
    private android.widget.Button btnBmShuffle = null;
    // v15.69 (slice 2072): row long-press menu on this screen — the
    // Bookmarks screen had search, sort, play-all, shuffle, jump,
    // mark and strip, but no row menu at all: a listener could drop
    // a mark only by leaving the screen, and nothing started a run
    // mid-list. lastBmPlayFromHereMs is the handler's own 700ms
    // clock (the chooser's v12.28 rule — a separate clock from
    // Play-all-marks' and Shuffle's, so a Play-from-here tap never
    // eats a Play-all tap).
    private long lastBmPlayFromHereMs = 0;
    private java.util.List<BmPlayAll.Item> bmItems =
            new java.util.ArrayList<BmPlayAll.Item>();
    // v15.67 (slice 2062): "Jump to the playing talk" — the
    // floating gold ▶ over the list's bottom-end corner; bmPlayingShown is the last-painted state
    // for the painter's change gate, and bmJumpTargetIdx is the
    // cached SHOWN-list index of the sounding talk (recomputed on
    // search/sort/refresh and on sounding change, never on scroll
    // — the 1994 pattern with a cache, so a fling never pays a
    // sameTalk scan per scroll event).
    private android.view.View bmPlayingBtn = null;
    private boolean bmPlayingShown = false;
    private int bmJumpTargetIdx = -1;
    // v15.75 (slice 2110): "Back to top" on this screen — the Talks 1940 / Downloaded 2003 button, now on the last full-list screen without one: a marked list grows with every bookmark tap, so its header is off-screen exactly when the jump is needed. bmTopBtn is the floating gold ↑ button (GONE until ScrollTop.visible — the first visible row passes 8 — the 1940 shape, on the shown list's own scroll, not the sounding row); bmTopShown is the last-painted state for the painter's change gate. Back to top landed in slice 2110.
    private android.view.View bmTopBtn = null;
    private boolean bmTopShown = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bookmarks);
        night = Prefs.isNight(this);
        Fx.applySystemBars(this, night); // v13.07: bars follow the theme
        View root = findViewById(R.id.bmRoot);
        if (root != null)
            root.setBackgroundResource(night ? R.drawable.bg_gradient_night
                    : R.drawable.bg_gradient_day);
        headerTitle = findViewById(R.id.bmTitle);
        listView = findViewById(R.id.bmList);
        emptyText = findViewById(R.id.bmEmpty);
        if (headerTitle != null) {
            headerTitle.setText(Lang.t(this, "bm_title"));
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
                    playBookmark(items.get(pos), v);
                }
            });
            // v15.69 (slice 2072): the row's long-press menu — tap
            // still plays; a long-press opens Play-from-here /
            // Delete-bookmark (showBmRowMenu). The History v13.77
            // shape: Fx.tap blip, then the menu, return true.
            listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                    if (pos < 0 || pos >= items.size()) return false;
                    Fx.tap(BookmarksActivity.this);
                    showBmRowMenu(items.get(pos));
                    return true;
                }
            });
            // v15.67 (slice 2062): the jump button paints on the same scroll — off-screen is its only show rule, so a laid-out window is required (count 0 means no layout yet).
            listView.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
                @Override public void onScrollStateChanged(android.widget.AbsListView v, int scrollState) {}
                @Override public void onScroll(android.widget.AbsListView v, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                    // v15.75 (slice 2110): the top button paints on the same scroll (the 2003 shape — its only show rule is the first visible row, so no laid-out-window guard; the threshold keeps it hidden until row 8 anyway).
                    paintBmTopBtn(firstVisibleItem);
                    if (visibleItemCount > 0) paintBmJumpBtn(firstVisibleItem, firstVisibleItem + visibleItemCount - 1);
                }
            });
        }
        wireBmSearch(); // v15.65 (slice 2049): search on this screen, before the first refresh
        wireBmSort(); // v15.65 (slice 2051): sort on this screen, before the first refresh
        wireBmJumpBtn(); // v15.67 (slice 2062): jump-to-playing button on this list
        wireBmTopBtn(); // v15.75 (slice 2110): back to top on this screen, before the first refresh
        // v15.66 (slice 2055): "Play all marks" — the Favourites 2044 wiring, before the first refresh so the button shows on first paint; the label is the chooser's own pl_play_all key, so no new string. The button stays GONE until applyBmSearch shows it (syncBmPlayAllBtn below).
        btnBmPlayAll = findViewById(R.id.btnBmPlayAll);
        if (btnBmPlayAll != null) btnBmPlayAll.setText(Lang.t(this, "pl_play_all"));
        if (btnBmPlayAll != null) btnBmPlayAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(BookmarksActivity.this);
                playAllBm(v);
            }
        });
        if (btnBmPlayAll != null) Fx.press(btnBmPlayAll);
        // v15.68 (slice 2067): wire the Shuffle button the same
        // way — one tap shuffles the shown run of marks into the
        // queue and starts the first shuffled mark (shufflePlayBm).
        // The label is the pre-existing Lang "shuffle" key, so no
        // new key and no literal here. The button stays GONE until
        // applyBmSearch() shows it (syncBmPlayAllBtn below).
        btnBmShuffle = findViewById(R.id.btnBmShuffle);
        if (btnBmShuffle != null) btnBmShuffle.setText(Lang.t(this, "shuffle"));
        if (btnBmShuffle != null) btnBmShuffle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(BookmarksActivity.this);
                shufflePlayBm(v);
            }
        });
        if (btnBmShuffle != null) Fx.press(btnBmShuffle);
        refresh();
        wireBookmarksStrip(); // v15.63 (slice 2037): real mini-player below the list
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        startBookmarksStripTick(); // v15.63 (slice 2037): mini-player live state
    }

    @Override protected void onPause() {
        super.onPause();
        stopBookmarksStripTick(); // v15.63 (slice 2037): never tick a detached strip
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        // v15.63 (slice 2037): detach the liquid-glass surface (History v13.54,
        // Talks v13.08) — the drawable pins the dead view + its destroyed
        // activity otherwise.
        GlassBlur.detach(findViewById(R.id.nowStrip));
    }

    private void refresh() {
        java.util.Set<String> names = catalogNames();
        // v15.65 (slice 2049): the search keeps its own copy of the
        // unfiltered pick — a keystroke re-filters in memory (the
        // Downloaded applyDlFilter shape), never re-reading the
        // cache files per keystroke.
        bmAllItems = BmAll.pickAll(readBookmarks(), Prefs.statsRecent(this), names);
        refreshBookmarksPlayingMarks(); // v15.64 (slice 2041): mark right on first paint, not one tick late
        applyBmSearch();
    }

    // v15.65 (slice 2049): the search pass — the filtered list,
    // the adapter, the clear ✕ and the empty line, from the
    // unfiltered pick. The watcher calls this per keystroke;
    // refresh() calls it after a fresh pick; onResume's refresh()
    // re-applies the live query after a Room return. A
    // filtered-empty list says no_search_match (v8.94) — there ARE
    // saved marks, just none matching — while a blank query with
    // an empty list still says bm_all_empty. The playing-row mark
    // needs nothing here: itemRow re-reads the sounding
    // identities per bind, so a filtered-out sounding talk simply
    // has no row to mark and a re-shown one marks at once.
    // v15.66 (slice 2055): "Play all marks" follows the FILTERED list — the shown run is the run (the 2047 rule): the pass rebuilds bmItems from the shown rows and re-syncs the button, so a keystroke re-aims the run in memory and the sort tap re-runs this pass instead of re-reading the pick.
    private void applyBmSearch() {
        // v15.65 (slice 2051): the sort rides the search pass — the
        // shown list is the filtered list in the chosen order, so a
        // keystroke re-sorts in memory and the sort tap re-runs this
        // pass instead of re-reading the pick. The playing-row mark
        // needs nothing here, like the search (2049): itemRow
        // re-reads the sounding identities per bind.
        items = BmSort.sort(BmSearch.filter(bmAllItems, bmQuery), bmSortMode);
        bmItems = buildBmItems(items);
        syncBmPlayAllBtn(true); // v15.66 (slice 2055): Play-all follows the FILTERED list — the shown run is the run. First paint here (refresh() calls applyBmSearch), and every keystroke re-syncs it with the shown run; instant, never faded, the clear-X silent-pass rule.
        // v15.67 (slice 2062): a search / sort / refresh re-shows the list — the jump button's cached target moves with it (new list, new filter), so refresh the target and repaint from the new position.
        refreshBmJumpTarget();
        paintBmJumpBtnFromList();
        // v15.75 (slice 2110): a search / refresh can land the list deep without a scroll event — repaint the top button from the new position too (the 2003 shape).
        paintBmTopBtnFromList();
        if (adapter != null) adapter.notifyDataSetChanged();
        refreshBmClear(bmSearchKeystroke);
        if (emptyText == null) return;
        boolean empty = items.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) emptyText.setText(Lang.t(this,
                bmQuery.length() > 0 ? "no_search_match" : "bm_all_empty"));
    }

    // v15.65 (slice 2049): search on this screen — the Downloaded
    // search shape (v1.51 field + v4.20 Search-key dismiss + v15.33
    // clear ✕): the hint is the Talks chooser's own
    // talks_search_hint (v9.88), the filter is the ONE rule
    // (Fx.titleMatches through BmSearch, title or sayadaw), and a
    // keystroke re-filters the in-memory pick without re-reading
    // the cache files. Rotation restore rides the watcher (the
    // v1.51 rule): the system's restored text fires afterTextChanged
    // after onStart, so the filter re-runs with the restored query
    // before onResume's refresh. The clear ✕ clears through
    // setText("") so the watcher re-runs the filter exactly like a
    // keystroke (the Main v15.33 shape, slice 1836); it is GONE
    // with an empty query, so a tap can only land on a real query.
    private void wireBmSearch() {
        bmSearchField = findViewById(R.id.bmSearch);
        bmClearBtn = findViewById(R.id.btnClearBmSearch);
        if (bmSearchField != null)
            bmSearchField.setHint(Lang.t(this, "talks_search_hint"));
        if (bmSearchField != null)
            bmSearchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        if (bmSearchField != null) bmSearchField.setSingleLine(true);
        if (bmSearchField != null) bmSearchField.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
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
        if (bmClearBtn != null) bmClearBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.morph(v);
                if (bmSearchField != null) bmSearchField.setText("");
            }
        });
        if (bmSearchField != null) bmSearchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                bmQuery = s.toString().trim();
                bmSearchKeystroke = true;
                applyBmSearch();
                bmSearchKeystroke = false;
            }
        });
    }

    // v15.65 (slice 2049): the search clear ✕ melts with the query
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
    private void refreshBmClear(boolean animate) {
        if (bmClearBtn == null) return;
        if (bmQuery != null && bmQuery.length() > 0) {
            if (bmClearBtn.getVisibility() != android.view.View.VISIBLE) {
                bmClearBtn.setVisibility(android.view.View.VISIBLE);
                if (animate) Fx.fadeIn(bmClearBtn, 250);
                else bmClearBtn.setAlpha(1f);
            } else if (animate && bmClearBtn.getAlpha() < 0.999f) {
                Fx.fadeIn(bmClearBtn, 250);
            } else if (!animate) {
                bmClearBtn.animate().cancel();
                bmClearBtn.setAlpha(1f);
            }
        } else {
            if (bmClearBtn.getVisibility() == android.view.View.VISIBLE) {
                if (animate) Fx.fadeOut(bmClearBtn, 200);
                else {
                    bmClearBtn.animate().cancel();
                    bmClearBtn.setVisibility(android.view.View.GONE);
                    bmClearBtn.setAlpha(1f);
                }
            }
        }
    }

    // v15.65 (slice 2051): sort on this screen — the Downloaded
    // sort-button shape (v1.47 label + v2.90 premium label dip +
    // v4.22 keyboard hide): the button names the CURRENT order
    // (bmSortLabel), a tap morphs the button, hides the search
    // keyboard, advances the mode (recent -> oldest -> sayadaw),
    // persists it, and dips the header row while the label swaps
    // (Fx.themeDip on bmHeaderRow, the dlBtnRow shape — the dip
    // runs on the row, never on the tapped button, so its alpha
    // channel never cancels the morph's scale channel; themeDip
    // null-guards, so a missing row still swaps the label). The
    // sort re-runs applyBmSearch() at once (it only reads
    // bmSortMode, like the Settings syncLiveService pattern), so
    // the list re-orders in place with no re-read. The mode
    // survives rotation through Prefs, like the Downloaded sort
    // (v1.47).
    private void wireBmSort() {
        bmSortBtn = findViewById(R.id.btnBmSort);
        bmSortMode = Prefs.bmSortMode(this);
        if (bmSortBtn != null) bmSortBtn.setText(bmSortLabel());
        if (bmSortBtn != null) bmSortBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.morph(v);
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                bmSortMode = (bmSortMode + 1) % 3;
                Prefs.setBmSortMode(BookmarksActivity.this, bmSortMode);
                Fx.themeDip(findViewById(R.id.bmHeaderRow), new Runnable() { @Override public void run() { if (bmSortBtn != null) bmSortBtn.setText(bmSortLabel()); } });
                applyBmSearch();
            }
        });
    }

    // v15.67 (slice 2062): wire the jump-to-playing button — one
    // tap glides to the sounding talk's first shown mark (the
    // chooser's v4.15 title long-press glide, in a visible button
    // — this screen has no long-press jump at all). The tap
    // re-resolves everything fresh — live guards, the shown-list
    // index — so a track change while the button sat visible can
    // never glide to a stale row; a filtered-out target clears
    // the search first (the v12.54 rule, in this screen's
    // setText("") clear shape — the watcher re-runs applyBmSearch
    // at once, the v1.51 rule), a shown target keeps the view. Any
    // guard failure toasts no_talk_playing (the v4.15 word), never
    // scrolls nowhere. A missing button simply never wires.
    private void wireBmJumpBtn() {
        bmPlayingBtn = findViewById(R.id.bmPlayingBtn);
        if (bmPlayingBtn != null) bmPlayingBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v10.43: bound-trackless stop — liveSession stays true but the mirror is false, so toast instead of gliding to a stopped talk; the v11.15 gate: an ended talk is not playing either (its mark lifts, so there is no row to jump to).
                if (listView == null || items.isEmpty() || !AudioService.liveSession || !AudioService.liveHasTrack || AudioService.liveEnded) {
                    android.widget.Toast.makeText(BookmarksActivity.this, Lang.t(BookmarksActivity.this, "no_talk_playing"), android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    refreshBookmarksPlayingMarks();
                    int target = BmJumpPlaying.findShownIndex(items, bmPlayingUrl, bmPlayingLibUrl, bmPlayingLibPath, AudioService.liveEnded);
                    // v12.54: a shown target glides with the view untouched; only a target filtered OUT of the shown list clears the search first, so the shown index means something again.
                    if (target < 0 && bmQuery != null && bmQuery.length() > 0 && bmSearchField != null) {
                        bmSearchField.setText("");
                        refreshBookmarksPlayingMarks();
                        target = BmJumpPlaying.findShownIndex(items, bmPlayingUrl, bmPlayingLibUrl, bmPlayingLibPath, AudioService.liveEnded);
                    }
                    if (target < 0) {
                        android.widget.Toast.makeText(BookmarksActivity.this, Lang.t(BookmarksActivity.this, "no_talk_playing"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Fx.tap(BookmarksActivity.this);
                    Fx.morph(v);
                    listView.smoothScrollToPosition(target);
                } catch (Exception ignored) {}
            }
        });
    }

    // v15.67 (slice 2062): refresh the jump button's cached
    // target — the shown-list index of the sounding talk, from the
    // tick-fresh sounding identities (refreshBookmarksPlayingMarks
    // runs first on every path that calls this: refresh() before
    // applyBmSearch, the strip-tick change branch, the tap's own
    // re-resolution). Runs on search/sort/refresh and on sounding
    // change only, never on scroll; -1 while nothing live sounds
    // among the shown rows.
    private void refreshBmJumpTarget() {
        bmJumpTargetIdx = BmJumpPlaying.findShownIndex(items, bmPlayingUrl, bmPlayingLibUrl, bmPlayingLibPath, AudioService.liveEnded);
    }

    // v15.67 (slice 2062): the jump-to-playing state — shown only
    // while a live talk has a cached target AND that row is
    // off-screen (above or below the visible window, the
    // JumpPlaying.wantShown rule, the 1994 shape). The change gate
    // keeps steady scrolls and the search/sort/refresh repaints
    // from re-touching the button; the show/hide melts (Fx.fadeIn/
    // fadeOut, 180ms) instead of snapping, and a cancelled fade
    // never strands it half-transparent (the v2.62 gen guard, the
    // 1940 shape). A stop clears the identities on the next tick,
    // so the button hides within a tick of the session ending.
    private void paintBmJumpBtn(int firstVisible, int lastVisible) {
        if (bmPlayingBtn == null) return;
        boolean want = bmJumpTargetIdx >= 0
                && JumpPlaying.wantShown(bmJumpTargetIdx, firstVisible, lastVisible);
        if (want == bmPlayingShown) return;
        bmPlayingShown = want;
        if (want) {
            bmPlayingBtn.setVisibility(android.view.View.VISIBLE);
            Fx.fadeIn(bmPlayingBtn, 180);
        } else {
            Fx.fadeOut(bmPlayingBtn, 180);
        }
    }

    // v15.67 (slice 2062): repaint from the list's own position
    // after a scroll-less change — a search/sort/refresh, a
    // rotation restore, or a sounding change can move the playing
    // row off-screen with no scroll event, so read the position
    // after layout and paint from it. Posted, because the first /
    // last visible rows are only valid once the list has laid out
    // (the 1940 shape).
    private void paintBmJumpBtnFromList() {
        if (listView == null) return;
        listView.post(new Runnable() {
            @Override public void run() {
                if (listView == null) return;
                paintBmJumpBtn(listView.getFirstVisiblePosition(), listView.getLastVisiblePosition());
            }
        });
    }

    // v15.75 (slice 2110): wire the back-to-top button — one tap glides the open list to the top (the Talks 1940 / Downloaded 2003 shape: the tap needs no gate — the button only shows past row 8, so a tap always has somewhere to go; this screen HAS a search field, so the keyboard hides with the glide, the 2003 shape in this file's own inline form — the wireBmSort tap's two lines, not a new helper). A missing button simply never wires; a missing list never scrolls.
    private void wireBmTopBtn() {
        bmTopBtn = findViewById(R.id.bmTopBtn);
        if (bmTopBtn != null) bmTopBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(BookmarksActivity.this);
                Fx.morph(v);
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                if (listView != null) listView.smoothScrollToPosition(0);
            }
        });
    }

    // v15.75 (slice 2110): the back-to-top state — shown once the first visible row passes ScrollTop's threshold, hidden at the top (the 1940 Talks / 2003 Downloaded shape verbatim: ScrollTop.visible, THRESHOLD 8 — about one screen of rows). No sounding gate: the rule is the shown list's own scroll. The change gate keeps steady scrolls and the search/refresh repaint from re-touching the button; the show/hide melts (Fx.fadeIn/fadeOut, 180ms) instead of snapping, and a cancelled fade never strands it half-transparent (the v2.62 gen guard, the 1940 shape).
    private void paintBmTopBtn(int firstVisible) {
        if (bmTopBtn == null) return;
        boolean want = ScrollTop.visible(firstVisible);
        if (want == bmTopShown) return;
        bmTopShown = want;
        if (want) {
            bmTopBtn.setVisibility(android.view.View.VISIBLE);
            Fx.fadeIn(bmTopBtn, 180);
        } else {
            Fx.fadeOut(bmTopBtn, 180);
        }
    }

    // v15.75 (slice 2110): repaint from the list's own position after a scroll-less change — a search / refresh rebuild can land the list deep without a scroll event, so read the position after layout and paint from it. Posted, because the first visible row is only valid once the list has laid out (the 1940 shape). The rotation restore needs no separate hook: onCreate restores through refresh(), whose applyBmSearch tail already posts this repaint.
    private void paintBmTopBtnFromList() {
        if (listView == null) return;
        listView.post(new Runnable() {
            @Override public void run() {
                if (listView == null) return;
                paintBmTopBtn(listView.getFirstVisiblePosition());
            }
        });
    }

    // v15.65 (slice 2051): the sort button label names the CURRENT
    // order — the Downloaded dlSortLabel shape (1 method, 3
    // returns): most-recent first, oldest first, sayadaw A-Z.
    private String bmSortLabel() {
        if (bmSortMode == 1) return Lang.t(BookmarksActivity.this, "bm_sort_oldest");
        if (bmSortMode == 2) return Lang.t(BookmarksActivity.this, "bm_sort_sayadaw");
        return Lang.t(BookmarksActivity.this, "bm_sort_recent");
    }

    private int goldColor() { return 0xFFC9A227; } // v15.64 (slice 2041): History's gold, the playing-row mark

    private View itemRow(final int position) {
        final BmAll.Row r = items.get(position);
        final Prefs.HistEntry e = r.e;
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
        // v15.64 (slice 2041): the sounding row says so — "\u25b6 " ahead of its
        // "12:34 \u00b7 title" line in gold (History v13.55, Talks v4.89/v11.07),
        // for a Room session (URL match) or a library session on a
        // URL-form row (dlMap exactly-one claimant); an ended talk is
        // not playing (v11.15 gate), so its mark lifts at once. A talk
        // with three marks is three rows, and the mark reads on all
        // three — each row IS the sounding talk at its own minute.
        // Rows are built fresh per getView, but both text and colour
        // rebind here so neither can leak onto the next talk.
        boolean bmRowPlaying = !AudioService.liveEnded
                && ((bmPlayingUrl.length() > 0 && SiteHttp.sameTalk(e.url, bmPlayingUrl))
                        || (bmPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(e.url, bmPlayingLibUrl))
                        || (bmPlayingLibPath.length() > 0 && e.url.equals(bmPlayingLibPath)));
        tv.setText((bmRowPlaying ? "\u25b6 " : "") + BmRail.fmtTime(r.posMs) + " \u00b7 " + e.title);
        tv.setTextColor(bmRowPlaying ? goldColor() : android.graphics.Color.parseColor("#7A6A58"));
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

    // v15.66 (slice 2055): Play-all visibility — the chooser's
    // refreshTalkPlayAllRow shape (v13.76): GONE until a list lands,
    // fading in/out on change, instant on first paint. The gate is
    // BmPlayAll.hasPlayable: at least one shown mark with a usable
    // URL and sayadaw, a real minute, that is not the sounding talk.
    // lastBmPlayAllVisible is the change-only snapshot — steady 1s
    // ticks never touch the button.
    // v15.68 (slice 2067): the Shuffle button rides the same gate
    // and the same snapshot — the two header verbs show and hide
    // together.
    private void syncBmPlayAllBtn(boolean skipFade) {
        if (btnBmPlayAll == null) return;
        boolean show = BmPlayAll.hasPlayable(bmItems, bmPlayingUrl, bmPlayingLibUrl);
        if (lastBmPlayAllVisible != null && lastBmPlayAllVisible.booleanValue() == show) return;
        lastBmPlayAllVisible = Boolean.valueOf(show);
        if (skipFade) {
            btnBmPlayAll.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
            btnBmPlayAll.setAlpha(1f);
        } else if (show) {
            if (btnBmPlayAll.getVisibility() != android.view.View.VISIBLE) {
                btnBmPlayAll.setVisibility(android.view.View.VISIBLE);
                Fx.fadeIn(btnBmPlayAll, 250);
            } else if (btnBmPlayAll.getAlpha() < 0.999f) {
                Fx.fadeIn(btnBmPlayAll, 250);
            }
        } else if (btnBmPlayAll.getVisibility() == android.view.View.VISIBLE) {
            Fx.fadeOut(btnBmPlayAll, 200);
        }
        // v15.68 (slice 2067): the Shuffle button rides the same
        // gate and the same change-only snapshot, so the two header
        // verbs show and hide together; a null button simply never
        // paints (both buttons live in the same layout, so they
        // exist or miss together — this guard is belt and braces).
        if (btnBmShuffle != null) {
            if (skipFade) {
                btnBmShuffle.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
                btnBmShuffle.setAlpha(1f);
            } else if (show) {
                if (btnBmShuffle.getVisibility() != android.view.View.VISIBLE) {
                    btnBmShuffle.setVisibility(android.view.View.VISIBLE);
                    Fx.fadeIn(btnBmShuffle, 250);
                } else if (btnBmShuffle.getAlpha() < 0.999f) {
                    Fx.fadeIn(btnBmShuffle, 250);
                }
            } else if (btnBmShuffle.getVisibility() == android.view.View.VISIBLE) {
                Fx.fadeOut(btnBmShuffle, 200);
            }
        }
    }

    // v15.66 (slice 2055): "Play all marks" — the Bookmarks mirror of
    // the Favourites playAllFav (v15.64, slice 2044) and New talks'
    // playAllNew (v15.65, slice 2047). The marks used to play one row
    // tap at a time; one tap here queues the whole SHOWN run of marks
    // in screen order, across sayadaws (every line carries its own
    // sayadaw, the HistPlayFromHere v2 shape, so the Room renders the
    // other sayadaws' talks as foreign rows and hands over when one
    // sounds) and opens the Room on the first mark at its minute
    // (playBookmark's own route — the mark was written into the
    // talk's resume spot by the openBmTalk1962 rule, so the Room
    // resumes there, v1.56; the queued v3 head for that same mark is
    // dropped on real play by the v4.43 ahead-drop, by URL, so the
    // run continues with the next mark instead of replaying it).
    // Same guards as the chooser: empty list refuses with the same
    // words, the 700ms debounce has its own clock (an invalid tap
    // never consumes the window, v12.28), the sounding talk is never
    // re-queued (the stay-put branch: the rest queue behind it, no
    // restart), already-queued talks stay where they are, and the
    // count toast counts queued lines only. The anchor sayadaw is
    // healed exactly like playBookmark (catalog + the sayadawForTalk
    // heal) — the queue key and the Room name must be the healed
    // name, never a stale banked one. No catalog, no run: the queue
    // is untouched. The dup check is per MARK, not per talk — a
    // sameTalk-only check (the 2044 shape) would drop the second
    // mark of a talk the moment the first landed in the run; a saved
    // v2 line (posMs -1) never matches a v3 line's minute, and the
    // core already skipped talks queued in any form, so this loop
    // only guards the race. The first row is always a stream URL by
    // buildBmItems' construction (a path-form row resolves through
    // streamUrlFor or drops out), so no library leg is needed here.
    private void playAllBm(android.view.View v) {
        if (items == null || items.isEmpty()) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long now55 = android.os.SystemClock.uptimeMillis();
        if (now55 - lastBmPlayAllMs < 700) return;
        lastBmPlayAllMs = now55;
        refreshBookmarksPlayingMarks(); // tap-time sounding identities
        final BmAll.Row first55 = items.get(0);
        if (first55 == null || first55.e == null || first55.e.url == null || first55.e.url.length() == 0) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String sd55 = first55.e.sayadaw;
        String[] cat55 = catalogFor(sd55);
        if (cat55 == null || cat55[1] == null || cat55[1].length() == 0) {
            String owner55 = Prefs.sayadawForTalk(this, "lib", first55.e.url, "");
            if (owner55 != null && owner55.length() > 0 && !owner55.equals(sd55)) {
                sd55 = owner55;
                cat55 = catalogFor(sd55);
            }
        }
        if (cat55 == null || cat55[1] == null || cat55[1].length() == 0) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "pl_no_play"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        boolean firstSounds55 = (bmPlayingUrl.length() > 0 && SiteHttp.sameTalk(first55.e.url, bmPlayingUrl))
                || (bmPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(first55.e.url, bmPlayingLibUrl));
        // The anchor sayadaw's current queue, as identities — the
        // pick skips talks already queued (they stay where they are).
        // Saved lines are carried verbatim (histQueueAdd's discipline),
        // so a foreign line the Room drawer moved mid-queue survives
        // the write.
        java.util.Set<String> queuedIds55 = new java.util.HashSet<String>();
        java.util.ArrayList<String> saved55 = new java.util.ArrayList<String>();
        try {
            String savedRaw55 = Prefs.savedQueue(this, sd55);
            if (savedRaw55 != null && savedRaw55.length() > 0) {
                for (String ln55 : savedRaw55.split("\n")) {
                    if (ln55.length() == 0) continue;
                    saved55.add(ln55);
                    QueueEntry qe55 = QueueEntry.parse(ln55);
                    if (qe55 != null && qe55.url.length() > 0)
                        queuedIds55.add(SiteHttp.identityUrl(qe55.url));
                }
            }
        } catch (Exception ignored) {}
        java.util.List<String> run55 = BmPlayAll.lines(bmItems, queuedIds55, bmPlayingUrl, bmPlayingLibUrl);
        int added55 = 0;
        for (String ln55 : run55) {
            QueueEntry qe55 = QueueEntry.parse(ln55);
            if (qe55 == null || qe55.url.length() == 0) continue;
            boolean dup55 = false;
            for (String ex55 : saved55) {
                QueueEntry qx55 = QueueEntry.parse(ex55);
                if (qx55 != null && qx55.posMs == qe55.posMs
                        && SiteHttp.sameTalk(qx55.url, qe55.url)) { dup55 = true; break; }
            }
            if (dup55) continue;
            saved55.add(ln55);
            added55++;
        }
        Prefs.saveQueue(this, sd55, saved55);
        android.widget.Toast.makeText(BookmarksActivity.this, added55 > 0
                ? Lang.t(BookmarksActivity.this, "queue_plain_prefix") + added55 + Lang.t(BookmarksActivity.this, "queue_added_suffix")
                : Lang.t(BookmarksActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
        Fx.morph(v);
        if (!firstSounds55) playBookmark(first55, v);
    }

    // v15.68 (slice 2067): "Shuffle play" — the Bookmarks mirror
    // of the chooser's shufflePlayShown (v13.93). The marks used
    // to play in screen order only (Play-all-marks, v15.66); one
    // tap here shuffles the SHOWN run of marks into the queue in
    // random order (BmShuffle.shuffleRows over the filtered +
    // sorted list — the 2047 rule: the shown run is the run; a
    // talk with three marks is three rows, so the marks play in
    // random order, each at its own minute) and opens the Room on
    // the first shuffled mark at its minute (playBookmark's own
    // route — the mark was written into the talk's resume spot by
    // the openBmTalk1962 rule, so the Room resumes there, v1.56;
    // the queued v3 head for that same mark is dropped on real
    // play by the v4.43 ahead-drop, by URL). Same guards as the
    // chooser: empty list refuses with the same words, the 700ms
    // debounce has its own clock (an invalid tap never consumes
    // the window, v12.28), the sounding talk's marks are never
    // re-queued (the stay-put branch: the rest queue behind it, no
    // restart — this screen's sounding identities, the 2041 mark
    // rule), already-queued talks stay where they are, and the
    // count toast counts queued lines only. The anchor sayadaw is
    // the FIRST SHUFFLED mark's sayadaw, healed exactly like
    // playBookmark (catalog + the sayadawForTalk heal). No
    // catalog, no run: the queue is untouched. The dup check is
    // per MARK, not per talk — a sameTalk-only check (the 2044
    // shape) would drop the second mark of a talk the moment the
    // first landed in the run; a saved v2 line (posMs -1) never
    // matches a v3 line's minute, and the core already skipped
    // talks queued in any form, so this loop only guards the
    // race. The first row is always a stream URL by
    // buildBmItems' construction (a path-form row resolves
    // through streamUrlFor or drops out), so no library leg is
    // needed here. The shuffled queue IS the shuffle — the global
    // shuffle-continuous toggle is left alone, so a user who
    // deliberately left it off is not surprised, and after the
    // queue drains normal advance resumes. playBookmark's own
    // 700ms row-tap clock stands: a row tap under 700ms before
    // this tap swallows the Room open while the queue still
    // writes (the same class as playAllBm, accepted in 2055).
    private void shufflePlayBm(android.view.View v) {
        if (items == null || items.isEmpty()) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long now67 = android.os.SystemClock.uptimeMillis();
        if (now67 - lastBmShuffleMs < 700) return;
        lastBmShuffleMs = now67;
        refreshBookmarksPlayingMarks(); // tap-time sounding identities
        java.util.List<BmAll.Row> shuffled67 = BmShuffle.shuffleRows(items, new java.util.Random());
        if (shuffled67.isEmpty()) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final BmAll.Row first67 = shuffled67.get(0);
        if (first67 == null || first67.e == null || first67.e.url == null || first67.e.url.length() == 0) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String sd67 = first67.e.sayadaw;
        String[] cat67 = catalogFor(sd67);
        if (cat67 == null || cat67[1] == null || cat67[1].length() == 0) {
            String owner67 = Prefs.sayadawForTalk(this, "lib", first67.e.url, "");
            if (owner67 != null && owner67.length() > 0 && !owner67.equals(sd67)) {
                sd67 = owner67;
                cat67 = catalogFor(sd67);
            }
        }
        if (cat67 == null || cat67[1] == null || cat67[1].length() == 0) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "pl_no_play"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        boolean firstSounds67 = (bmPlayingUrl.length() > 0 && SiteHttp.sameTalk(first67.e.url, bmPlayingUrl))
                || (bmPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(first67.e.url, bmPlayingLibUrl));
        // The anchor sayadaw's current queue, as identities — the
        // pick skips talks already queued (they stay where they are).
        // Saved lines are carried verbatim (histQueueAdd's discipline),
        // so a foreign line the Room drawer moved mid-queue survives
        // the write.
        java.util.Set<String> queuedIds67 = new java.util.HashSet<String>();
        java.util.ArrayList<String> saved67 = new java.util.ArrayList<String>();
        try {
            String savedRaw67 = Prefs.savedQueue(this, sd67);
            if (savedRaw67 != null && savedRaw67.length() > 0) {
                for (String ln67 : savedRaw67.split("\n")) {
                    if (ln67.length() == 0) continue;
                    saved67.add(ln67);
                    QueueEntry qe67 = QueueEntry.parse(ln67);
                    if (qe67 != null && qe67.url.length() > 0)
                        queuedIds67.add(SiteHttp.identityUrl(qe67.url));
                }
            }
        } catch (Exception ignored) {}
        // The shuffled order flows through BmPlayAll.lines (it keeps
        // its input order): the run is the shuffled run, deduped,
        // sounding-skipped and already-queued-skipped exactly like
        // Play-all-marks'. buildBmItems resolves the shuffled rows
        // to stream URLs first, so a path-form mark still plays.
        java.util.List<BmPlayAll.Item> shuffledItems67 = buildBmItems(shuffled67);
        java.util.List<String> run67 = BmPlayAll.lines(shuffledItems67, queuedIds67, bmPlayingUrl, bmPlayingLibUrl);
        int added67 = 0;
        for (String ln67 : run67) {
            QueueEntry qe67 = QueueEntry.parse(ln67);
            if (qe67 == null || qe67.url.length() == 0) continue;
            boolean dup67 = false;
            for (String ex67 : saved67) {
                QueueEntry qx67 = QueueEntry.parse(ex67);
                if (qx67 != null && qx67.posMs == qe67.posMs
                        && SiteHttp.sameTalk(qx67.url, qe67.url)) { dup67 = true; break; }
            }
            if (dup67) continue;
            saved67.add(ln67);
            added67++;
        }
        Prefs.saveQueue(this, sd67, saved67);
        android.widget.Toast.makeText(BookmarksActivity.this, added67 > 0
                ? Lang.t(BookmarksActivity.this, "queue_plain_prefix") + added67 + Lang.t(BookmarksActivity.this, "queue_added_suffix")
                : Lang.t(BookmarksActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
        Fx.morph(v);
        if (!firstSounds67) playBookmark(first67, v);
    }

    // v15.66 (slice 2055): the shown rows as resolved Items — one
    // per shown row, in screen order. A path-form row resolves
    // through streamUrlFor (the exactly-one-claimant rule, v8.44);
    // an unresolvable path names no talk and drops out of the run
    // rather than queueing a line the Room's funnel would drop.
    // A stream row rides as-is (identityUrl). Called by
    // applyBmSearch on every pass, so the run always matches the
    // screen (the 2047 rule).
    private java.util.List<BmPlayAll.Item> buildBmItems(java.util.List<BmAll.Row> rows) {
        java.util.List<BmPlayAll.Item> out = new java.util.ArrayList<BmPlayAll.Item>();
        if (rows == null) return out;
        for (BmAll.Row r : rows) {
            if (r == null || r.e == null) continue;
            String u = streamUrlFor(r.e);
            if (u == null || u.length() == 0) continue;
            out.add(new BmPlayAll.Item(u, r.e.sayadaw, r.e.title, r.posMs));
        }
        return out;
    }

    // v15.80 (slice 2141): "Mark as listened / Unmark as listened" — the mark verb the Bookmarks row menu lacked that the Downloaded library row menu has had since v15.47 (slice 1949), the Talks chooser since v15.51 (slice 1969), the Room drawer dialogs since v15.52 (slices 1978/1979), the New talks row menu since v15.80 (slice 2138), the History row menu since v15.80 (slice 2139) and the Favourites row menu since v15.80 (slice 2140): a bookmarked talk could only earn or lose its listened mark by playing it to the end or opening the library — and a Bookmarks row is exactly where both legs are live, because playedTalks is banked only on natural finish (AudioService.markPlayedForCurrent) and bookmarking is independent of it: a bookmarked talk stopped early sits unmarked (offers Mark), a finished one sits marked (offers Unmark — it returns to the New talks list). Same verb through the same rules, so the surfaces can never disagree: the 1949 pure core (DlMark — canOffer / labelKey / isMarked / mark / unmark, inputs never mutated), the v11.14 tap-time sounding re-check over this screen's BmPlayAll sounding rule (the queue branches' rule), the prefs-fresh playedTalks read at tap time, and the mark landing in the SAME playedTalks key AudioService banks on finish and the library, the rails, the header new-count and the filters all read. The helper re-resolves the row to its stream URL at tap time (streamUrlFor — the Bookmarks-local shape, the offer and the tap can never disagree). No refresh and no repaint on this screen: the row renders no listened tick (only the sounding ▶ prefix and the row's own frozen minute), so nothing here rebinds from playedTalks — the visible effect is the menu label flipping mark<->unmark on the next open, the verb toast, and the propagation (New talks list + header new-count + chooser tick). Neither the history REC line, the lifetime totals, the queue, the shelf order, the bookmark itself nor any saved resume position is mutated.
    private void markListenedBm(final BmAll.Row r, final boolean unmark) {
        if (r == null || r.e == null || r.e.url == null || r.e.url.length() == 0) return;
        final String streamUrl = streamUrlFor(r.e);
        if (streamUrl == null || streamUrl.length() == 0) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // Tap-time sounding re-check (Talks v11.14): the dialog can sit open across an auto-advance, so this talk can BE sounding by tap time — it earns its mark by finishing, never by this verb. This screen's BmPlayAll sounding rule, the queue branches' rule.
        refreshBookmarksPlayingMarks();
        if (BmPlayAll.isSounding(streamUrl, bmPlayingUrl, bmPlayingLibUrl)) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        DlMark.Result res41 = null;
        try {
            android.content.SharedPreferences sp41 = getSharedPreferences("dhamma", MODE_PRIVATE);
            java.util.Set<String> cur41 = sp41.getStringSet("playedTalks", new java.util.HashSet<String>());
            res41 = unmark ? DlMark.unmark(cur41, SiteHttp.identityUrl(streamUrl), streamUrl)
                    : DlMark.mark(cur41, SiteHttp.identityUrl(streamUrl));
            sp41.edit().putStringSet("playedTalks", res41.set).apply();
        } catch (Exception ignored) {}
        android.widget.Toast.makeText(BookmarksActivity.this,
                Lang.t(BookmarksActivity.this, (res41 != null && res41.changed) ? (unmark ? "dl_menu_unmark_one" : "dl_menu_mark_one") : (unmark ? "nothing_to_unmark" : "already_listened")),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private java.util.Set<String> readPlayedTalksBm() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        try {
            java.util.Set<String> raw = getSharedPreferences("dhamma", MODE_PRIVATE).getStringSet("playedTalks", null);
            if (raw != null) out.addAll(raw);
        } catch (Exception ignored) {}
        return out;
    }

    // v15.69 (slice 2072): row long-press menu on this screen — the
    // Bookmarks screen had no row menu at all (no long-press, no
    // dialog, no per-row delete verb): marks were dropped only
    // from the Room bookmark list's ✕ (v12.64) or the Bookmarks
    // rail menu (v15.55 slice 1992), and nothing started a run
    // mid-list (Play-all-marks starts at the top, a tap plays one
    // mark at its minute). The menu opens with the verbs this screen needs:
    // "Share talk" first (the History menu_share shape, v13.77 — share the
    // row's talk through the Android sheet), "Copy link" second (the History
    // dl_menu_copy_link shape, v15.53 — copy the row's stream URL
    // to the clipboard, the share/copy pair together), "Share file"
    // third (the History dl_menu_share_file shape, v15.53 — send
    // the downloaded .mp3 through the sheet, the share/copy/file
    // trio together), then "Play from here" (the chooser's 1942 /
    // History's 2030 / Favourites' 2069 / New talks' 2071 verb —
    // queue the shown run of marks from this mark onward and open
    // the Room on it at its minute), "Add to playlist" (the Room's v12.65 verb —
    // file the talk in one of the listener's own lists, before
    // Delete bookmark) and "Delete bookmark" (the rail's own
    // verb — the confirm names the exact minute, so a talk with
    // several marks never deletes the wrong one).
    // download is the remaining future verb on this same menu
    // (playlist-add landed in slice 2093, share-file in slice
    // 2090, copy-link in slice 2086, share in slice 2084,
    // go-to-sayadaw in slice 2077, queue in slice 2075; the
    // History menu grew the same way: 1981, 1982, 1995, 2030).
    // The offer comes from the pure BmRowMenu core (the resolved
    // stream URL + the BmPlayAll sounding rule; the delete verb is
    // always offered — the row IS a bookmark); the dialog is
    // History's AlertDialog shape (title = the row's title,
    // key-dispatched handler); every verb re-checks its offer at
    // tap time, so a dialog left open across a refresh can never
    // act on a stale row.
    private void showBmRowMenu(final BmAll.Row r) {
        if (r == null || r.e == null || r.e.url == null || r.e.url.length() == 0) return;
        refreshBookmarksPlayingMarks();
        final String streamUrl = streamUrlFor(r.e);
        final String sd = r.e.sayadaw;
        java.util.List<String> keys72 = BmRowMenu.menuKeys(streamUrl, sd, bmPlayingUrl, bmPlayingLibUrl);
        // v15.69 (slice 2075): the queue verb — "Add to queue" /
        // "Remove from queue" (BmQueue): the row's sayadaw queue,
        // read once per menu open; the key composes into the menu
        // between Play-from-here and Delete bookmark, so the play
        // verbs sit together and the delete stays last. The queued
        // unit is the mark (a v3 line, 2053), but the offer is per
        // talk (the 2055 rule): a talk already queued under any
        // line offers Remove, never a second Add. A stale banked
        // sayadaw can only mis-offer (add for a talk already queued
        // under another key) — the handler re-reads the same key
        // and refuses with already_in_queue, never a wrong write.
        String qKey75 = "";
        try {
            java.util.ArrayList<String> saved75 = new java.util.ArrayList<String>();
            String savedRaw75 = Prefs.savedQueue(this, sd);
            if (savedRaw75 != null && savedRaw75.length() > 0) {
                for (String ln75 : savedRaw75.split("\n")) {
                    if (ln75.length() == 0) continue;
                    saved75.add(ln75);
                }
            }
            qKey75 = BmQueue.queueKey(streamUrl, sd, r.posMs, bmPlayingUrl, bmPlayingLibUrl,
                    BmQueue.isQueued(streamUrl, saved75));
        } catch (Exception ignored) {}
        if (qKey75.length() > 0) {
            int at75 = keys72.contains("menu_play_from_here") ? 1 : 0;
            keys72.add(at75, qKey75);
        }
        // v15.70 (slice 2077): the navigation verb — "Go to
        // sayadaw's talks" (BmGo, the HistGo mirror over
        // GoSayadaw): the row's sayadaw resolved through the
        // bundled catalog (catalogFor — the cached, canonicalized
        // read the play verbs already pay for; one cache lookup
        // per menu open at most). The key composes after the
        // queue verb, so the play verbs sit together, the
        // navigation follows, and Delete bookmark stays last
        // (inserted before bm_menu_remove, never appended past
        // it). Offered on any row whose sayadaw resolves to a
        // page URL — sounding or not, the navigation never
        // touches playback — and hidden while the sayadaw is
        // unknown or unlisted (BmGo.canOffer). A stale banked
        // sayadaw can only mis-offer when the canonical name
        // still resolves — the handler re-resolves at tap time
        // and toasts no_sayadaw_page on a miss, never a dead tap.
        String goKey77 = "";
        try {
            String[] goCat77 = catalogFor(sd);
            if (BmGo.canOffer(sd, goCat77)) goKey77 = "menu_go_sayadaw";
        } catch (Exception ignored) {}
        if (goKey77.length() > 0) {
            int at77 = keys72.indexOf("bm_menu_remove");
            keys72.add(at77 < 0 ? keys72.size() : at77, goKey77);
        }
        // v15.71 (slice 2084): the share verb — "Share talk"
        // (BmTalkShare, the History menu_share shape, v13.77): the
        // row's stream URL is already resolved once per menu open
        // (streamUrlFor above — URL-form rows share their own URL,
        // path-form rows resolve through the dlMap reverse lookup
        // with the exactly-one-claimant rule, v8.44; an
        // unresolvable row offers no share, never a guessed
        // link). The key composes FIRST, before Play-from-here,
        // so the share sits at the top of the menu like History's
        // menu and the chooser's dialog ("share first so muscle
        // memory is preserved", v7.82). Offered on any row with a
        // resolvable stream URL — sounding or not, sharing never
        // touches playback.
        // v15.71 (slice 2086): the copy verb — "Copy link"
        // (RoomCopyLink, the History dl_menu_copy_link shape,
        // v15.53 slice 1981): the same resolved stream URL
        // (streamUrl — no second resolve, the v5.69 cost rule)
        // offers the clipboard copy, so a listener who wants
        // just the link pasted elsewhere never has to share it
        // through the sheet and copy from there. The gate is
        // blank-URL-only (RoomCopyLink.canOffer), so a
        // streamed-only talk still offers it (a link is not a
        // file); only a blank URL hides it. The key composes
        // right after the share (below), so the share/copy pair
        // sits together like History's menu and the chooser's
        // trio — the pair can never be split by a later insert.
        // v15.72 (slice 2090): the file verb — "Share file"
        // (TalkFileShare, the History dl_menu_share_file shape,
        // v15.53 slice 1982): the same resolved stream URL
        // (streamUrl — no second resolve, the v5.69 cost rule)
        // offers sending the actual downloaded .mp3, so a
        // listener who just downloaded a bookmarked talk never has
        // to find it in Downloads/Dhamma by hand. The gate is the
        // screen's own download check (bmIsDownloaded — the
        // History v13.77 pair: dlMap mapped file + the v13.66
        // length gate, plus the basename leg), so a streamed-only
        // talk never offers it (a file is not a link). The key
        // composes right after the copy (below), so the
        // share/copy/file trio sits together like History's menu
        // and the chooser's dialog — the trio can never be split
        // by a later insert.
        // v15.72 (slice 2093): the playlist verb — "Add to playlist"
        // (BmPlAdd, the Room's showPlaylistAddDialog shape,
        // v12.65–v12.80): the same resolved stream URL
        // (streamUrl — no second resolve, the v5.69 cost rule)
        // offers filing the talk in one of the listener's own
        // lists, so a listener who just marked a minute in a
        // long talk never has to open the Room to file it. The
        // gate is the same non-blank URL the share reads
        // (BmPlAdd.canOffer), so a streamed-only talk still
        // offers it (filing is not a file); only a blank URL
        // hides it. The key composes right before Delete
        // bookmark (below), so the collect verb sits with the
        // removal, last — the share trio, the play verbs and
        // the navigation are never split by it.
        String shareKey84 = "";
        String copyKey86 = "";
        String fileKey90 = "";
        String plKey93 = "";
        String minKey2132 = "";
        String dlKey2135 = "";
        String markKey41 = "";
        try {
            if (BmTalkShare.canOffer(streamUrl)) shareKey84 = "menu_share";
            if (RoomCopyLink.canOffer(streamUrl)) copyKey86 = "dl_menu_copy_link";
            if (bmIsDownloaded(streamUrl)) fileKey90 = "dl_menu_share_file";
            if (BmPlAdd.canOffer(streamUrl)) plKey93 = "pl_add";
            // v15.79 (slice 2132): the minute key — gated on the row's own rendered minute (r.posMs > 5000, the 2012/2013/2018 family rule) plus the share offer, so a mark under 5s or a share-less row never offers it. The minute is the bookmark's own frozen mark (BmAll.Row.posMs), not a live spot.
            if (r.posMs > 5000 && shareKey84.length() > 0) minKey2132 = "dl_menu_share_minute";
            // v15.79 (slice 2135): the download key — gated on the v11.70 rule through the pinned pure core MainDl.canOffer (slice 1997: neither downloaded nor already queued), plus the non-blank stream URL leg like History's offer, so an unresolvable row never offers it. The sounding row keeps it (History offers it on sounding rows too — downloading the talk now playing is harmless, the Room keeps streaming).
            if (streamUrl.length() > 0 && MainDl.canOffer(bmIsDownloaded(streamUrl), bmIsDlQueued(streamUrl))) dlKey2135 = "dl_btn_download";
            // v15.80 (slice 2141): the mark key — gated on the 1949 DlMark core (canOffer: a non-blank stream URL, and not the sounding talk — the sounding talk earns its mark by finishing, like the 1949 sounding exclusion), through this screen's BmPlayAll sounding rule (the same gate the queue verbs read), over the resolved stream URL (streamUrl — streamUrlFor, so a path-form row resolves through the dlMap exactly-one-claimant rule, never a guessed link). The label is DlMark.labelKey over the prefs-fresh played set (readPlayedTalksBm, a local mirror of NewTalksActivity's readPlayedTalks — BookmarksActivity had no playedTalks reader), so the label can never disagree with the list membership — and on this screen both legs are live, because playedTalks is banked only on natural finish: a bookmarked talk stopped early offers Mark, a finished one offers Unmark.
            boolean sounding41 = BmPlayAll.isSounding(streamUrl, bmPlayingUrl, bmPlayingLibUrl);
            if (DlMark.canOffer(streamUrl, sounding41)) markKey41 = DlMark.labelKey(DlMark.isMarked(readPlayedTalksBm(), SiteHttp.identityUrl(streamUrl)));
        } catch (Exception ignored) {}
        if (shareKey84.length() > 0) keys72.add(0, shareKey84);
        // v15.71 (slice 2086): the copy key composes right after
        // the share (indexOf + 1 — the share sits at index 0, so
        // inserting here keeps the share/copy pair adjacent
        // whatever later inserts do; a copy implies a share, since
        // both gates are the same non-blank URL — the at86 < 0
        // leg is defensive only).
        if (copyKey86.length() > 0) {
            int at86 = keys72.indexOf("menu_share");
            keys72.add(at86 < 0 ? keys72.size() : at86 + 1, copyKey86);
        }
        // v15.72 (slice 2090): the file key composes right after
        // the copy (indexOf + 1 — the copy insert above already
        // landed, so inserting here keeps the share/copy/file
        // trio adjacent whatever later inserts do; a file implies
        // a copy, since a downloaded talk always has a non-blank
        // URL — the at90 < 0 leg is defensive only).
        if (fileKey90.length() > 0) {
            int at90 = keys72.indexOf("dl_menu_copy_link");
            keys72.add(at90 < 0 ? keys72.size() : at90 + 1, fileKey90);
        }
        // v15.79 (slice 2132): the minute key composes right after the share (indexOf + 1 — the file insert above already landed, so inserting here keeps the share verbs together in the order text, minute, copy, file, whatever later inserts do; a minute implies a share, since both gates are the same non-blank stream URL plus the row's own rendered minute — the atMin2132 < 0 leg is defensive only).
        if (minKey2132.length() > 0) {
            int atMin2132 = keys72.indexOf("menu_share");
            keys72.add(atMin2132 < 0 ? keys72.size() : atMin2132 + 1, minKey2132);
        }
        // v15.79 (slice 2135): the download key composes right before Delete bookmark, before the playlist insert below lands — the History order (download, playlist-add, remove), so the collect verbs sit together last and the removal stays last; a download implies a share, since both gates are the same non-blank stream URL — the atDl2135 < 0 leg is defensive only.
        if (dlKey2135.length() > 0) {
            int atDl2135 = keys72.indexOf("bm_menu_remove");
            keys72.add(atDl2135 < 0 ? keys72.size() : atDl2135, dlKey2135);
        }
        // v15.72 (slice 2093): the playlist key composes right before
        // Delete bookmark (indexOf — the removal stays last and the
        // collect verb sits with it, last; a playlist add implies
        // a share, since both gates are the same non-blank URL — the
        // at93 < 0 leg is defensive only).
        if (plKey93.length() > 0) {
            int at93 = keys72.indexOf("bm_menu_remove");
            keys72.add(at93 < 0 ? keys72.size() : at93, plKey93);
        }
        // v15.80 (slice 2141): the mark key composes right before Delete bookmark (indexOf — the removal stays last and the mark sits with the collect verbs, last; the 1969 chooser shape adapted: on this screen the mark follows the playlist insert, so the display order reads ..., Go, Download, Playlist, Mark, Remove; a mark implies a share, since both gates are the same non-blank stream URL — the at41 < 0 leg is defensive only).
        if (markKey41.length() > 0) {
            int at41 = keys72.indexOf("bm_menu_remove");
            keys72.add(at41 < 0 ? keys72.size() : at41, markKey41);
        }
        if (keys72.isEmpty()) return;
        java.util.ArrayList<String> opts = new java.util.ArrayList<String>();
        for (String k72 : keys72) opts.add(Lang.t(this, k72));
        final String[] optArr = opts.toArray(new String[0]);
        final String[] keyArr = keys72.toArray(new String[0]);
        new android.app.AlertDialog.Builder(this)
                .setTitle(r.e.title != null && r.e.title.length() > 0 ? r.e.title : r.e.url)
                .setItems(optArr, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if ("menu_share".equals(keyArr[which])) {
                            shareTalkBm(r);
                        } else if ("dl_menu_share_minute".equals(keyArr[which])) {
                            // v15.79 (slice 2132): "Share at this minute" — the 1993 Bookmarks-rail verb on the Bookmarks row menu: the menu could share a bookmarked talk's text, copy its link, send its file, file it in a playlist, play from its mark, queue it, open its sayadaw or delete the mark, but never hand a friend the exact marked minute — the row renders "12:34 · title" while the plain share carries the bare title. Same verb the Bookmarks rail carries since v15.55 (slice 1993), the Favourite-talks rail since slice 2011, the Downloaded library row since slice 2012, the Talks chooser since slice 2013 and the Room drawer rows since slices 2018/2019, through the same BmShare.shareAtMinuteText core (same text contract, unchanged), so the surfaces can never disagree. The link stays the talk's original link (no minute in the URL — the site serves plain .mp3 links, nothing to deep-link into); the minute lives in the text. Chooser title is share_talk, like the plain share. No tap-time re-check: the minute is the row's own frozen mark (r.posMs, > 5000 by the offer gate); the only writer of the mark is the bookmark list's remove, which cannot run while the modal menu sits open, and a stale minute in shared text is harmless. A share mutates nothing — no bookmark, queue or resume change; no pause guard (the text share beside it sets none). The sounding row keeps it (sharing never touches playback, and the mark is frozen, not live).
                            android.content.Intent send32 = new android.content.Intent(android.content.Intent.ACTION_SEND);
                            send32.setType("text/plain");
                            send32.putExtra(android.content.Intent.EXTRA_TEXT, BmShare.shareAtMinuteText(r.e.sayadaw, r.e.title, streamUrl, r.posMs, Lang.t(BookmarksActivity.this, "share_source")));
                            Fx.tap(BookmarksActivity.this);
                            startActivity(android.content.Intent.createChooser(send32, Lang.t(BookmarksActivity.this, "share_talk")));
                        } else if ("dl_menu_copy_link".equals(keyArr[which])) {
                            copyTalkLinkBm(r);
                        } else if ("dl_menu_share_file".equals(keyArr[which])) {
                            shareFileBm(r);
                        } else if ("pl_add".equals(keyArr[which])) {
                            addToPlaylistBm(r);
                        } else if ("dl_btn_download".equals(keyArr[which])) {
                            // v15.79 (slice 2135): "Download" — the v11.70 download verb on the Bookmarks row menu, through downloadTalkBm below (the file routes every menu verb through a named helper).
                            downloadTalkBm(r);
                        } else if ("menu_play_from_here".equals(keyArr[which])) {
                            playFromHereBm(r);
                        } else if ("menu_queue_add".equals(keyArr[which])) {
                            queueAddBm(r);
                        } else if ("menu_queue_remove".equals(keyArr[which])) {
                            queueRemoveBm(r);
                        } else if ("menu_go_sayadaw".equals(keyArr[which])) {
                            goToSayadawBm(r);
                        } else if ("dl_menu_mark_one".equals(keyArr[which])) {
                            // v15.80 (slice 2141): "Mark as listened" — the 1949 mark verb on the Bookmarks row menu, through markListenedBm below (the file routes every menu verb through a named helper).
                            markListenedBm(r, false);
                        } else if ("dl_menu_unmark_one".equals(keyArr[which])) {
                            // v15.80 (slice 2141): "Unmark as listened" — the 1949 unmark leg on the Bookmarks row menu, through markListenedBm below (both-forms rule: the canonical URL and the raw URL leave the played set together).
                            markListenedBm(r, true);
                        } else if ("bm_menu_remove".equals(keyArr[which])) {
                            removeBmFromMenu(r);
                        }
                    }
                })
                .show();
    }

    // v15.69 (slice 2072): "Play from here" on this screen — the
    // row menu's first play verb (the chooser's menu_play_from_here,
    // v15.45 slice 1942, History's histPlayFromHere, v15.62 slice
    // 2030, Favourites' playFromHereFav, v15.68 slice 2069, and
    // New talks' playFromHereNew, v15.69 slice 2071): queue the
    // SHOWN run of marks from the long-pressed mark onward (the
    // 2047 rule — the filtered + sorted list is the run) and open
    // the Room on it at its minute, in one tap. The run is built
    // with BmPlayAll.lines over buildBmItems (the v3 lines carry
    // each mark's own minute, 2053/2054, so a talk with three marks
    // queues three lines, each at its own minute). The anchor is
    // re-resolved by identity + minute at tap time (the v13.13
    // rule, with BmAll's own dedupe key — sameTalk alone would
    // land on the talk's FIRST mark, not the long-pressed one).
    // The anchor sayadaw is healed exactly like playBookmark
    // (catalog + the sayadawForTalk heal) — the queue key and the
    // Room name must be the healed name, never a stale banked one.
    // No catalog, no run: the queue is untouched. The Room opens
    // through playBookmark (this screen's own run-verb shape —
    // playAllBm and shufflePlayBm route through it too, the 2055 /
    // 2067 accepted shape: a row tap under 700ms before this tap
    // swallows the open while the queue still writes). Its View
    // parameter is unused in its body (verified — only the
    // signature names it), so this handler passes null. A sounding
    // anchor never re-opens: the offer hid it, and the tap-time
    // re-check refuses with queue_changed (the 2069 / 2071 shape),
    // rather than queueing behind a talk that already sounds —
    // the menu verb's offer is per-row, unlike play-all's
    // list-wide gate.
    // v15.72 (slice 2093): "Add to playlist" — the row menu's
    // playlist verb (the Room's showPlaylistAddDialog, v12.65–
    // v12.80, on this screen's menu; the Room's dialog has no
    // entry point anywhere in the app — defined once, called
    // zero times — so this is the verb's third live offer, on
    // the screen of marked minutes, where "a marked minute ->
    // my morning / bedtime list" is the natural flow; premium
    // backlog #2, the Spotify / YouTube Music / Apple Music
    // row-menu verb): re-resolve the row to its stream URL at
    // tap time (streamUrlFor — the offer and the tap can never
    // disagree), toast talk_no_longer_in_list when the file
    // behind a path-form row no longer resolves (the
    // share/copy/file precedent on this screen), heal the
    // sayadaw exactly like the row tap (catalogFor + the
    // sayadawForTalk heal — a stale banked sayadaw would strand
    // the playlist item under the wrong owner, the v12.78
    // lesson; the Room's playlistOwnerFor heals the same way),
    // then offer the listener's lists (Prefs.playlistNames —
    // the seed lists are there from the first read) plus a
    // "new list" row (pl_new, last). A tap files the talk
    // through Prefs.playlistAdd and toasts the v12.80 result
    // (BmPlAdd.toastKey: added / already there / full /
    // could-not-create); the new-list row opens the name
    // dialog. An add mutates no bookmark, queue or resume
    // state; no pause guard (no sheet is left to return from);
    // the sounding row keeps it (filing never touches
    // playback). The filed unit is the TALK, not the mark (the
    // 2084 adjudication): the playlist store carries url /
    // sayadaw / title and no minute, so a filed talk plays from
    // its saved spot like every other playlist item — the
    // mark's minute stays a Bookmarks-screen concept
    // (play-from-here and play-all-marks queue v3 lines at each
    // mark's minute).
    private void addToPlaylistBm(final BmAll.Row r) {
        if (r == null || r.e == null || r.e.url == null || r.e.url.length() == 0) return;
        final String streamUrl = streamUrlFor(r.e);
        if (!BmPlAdd.canOffer(streamUrl)) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String sd93 = r.e.sayadaw;
        String[] cat93 = catalogFor(sd93);
        if (cat93 == null || cat93[1] == null || cat93[1].length() == 0) {
            String owner93 = Prefs.sayadawForTalk(this, "lib", streamUrl, "");
            if (owner93 != null && owner93.length() > 0 && !owner93.equals(sd93)) {
                sd93 = owner93;
            }
        }
        final String title93 = r.e.title != null ? r.e.title : "";
        final String sdF93 = sd93;
        final java.util.List<String> names93 = Prefs.playlistNames(this);
        final String[] items93 = new String[names93.size() + 1];
        for (int i93 = 0; i93 < names93.size(); i93++) items93[i93] = names93.get(i93);
        items93[names93.size()] = Lang.t(this, "pl_new");
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "pl_choose"))
                .setItems(items93, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (BmPlAdd.isNewRow(which, names93.size())) {
                            showNewPlaylistBm(streamUrl, title93, sdF93);
                            return;
                        }
                        int r93 = Prefs.playlistAdd(BookmarksActivity.this,
                                names93.get(which), streamUrl, sdF93, title93);
                        android.widget.Toast.makeText(BookmarksActivity.this,
                                Lang.t(BookmarksActivity.this, BmPlAdd.toastKey(r93)),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // v15.72 (slice 2093): the "new list" dialog for the
    // playlist verb — the Room's showNewPlaylistDialog
    // (v12.80), on this screen: a name field (pl_name_hint),
    // a Create button (pl_create) and a cancel (btn_no_clear);
    // an empty name re-prompts with the hint toast; the name
    // caps at 40 chars (PLAYLIST_MAX_NAME); create is a
    // no-op for an existing name, so a repeat name just adds
    // to that list; at the 20-list cap a genuinely new name
    // toasts pl_limit (BmPlAdd.limitToastKey), any other
    // create failure pl_no_create — neither falls through to
    // the add (the v12.80 leg). The add toasts the same
    // v12.80 result as the choose dialog.
    private void showNewPlaylistBm(final String url, final String title, final String sd) {
        final android.widget.EditText et93 = new android.widget.EditText(this);
        et93.setHint(Lang.t(this, "pl_name_hint"));
        et93.setSingleLine(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "pl_new"))
                .setView(et93)
                .setPositiveButton(Lang.t(this, "pl_create"),
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        String name93 = et93.getText().toString().trim();
                        if (name93.length() == 0) {
                            android.widget.Toast.makeText(BookmarksActivity.this,
                                    Lang.t(BookmarksActivity.this, "pl_name_hint"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (name93.length() > 40) name93 = name93.substring(0, 40);
                        boolean created93 = Prefs.playlistCreate(BookmarksActivity.this, name93);
                        java.util.List<String> held93 = Prefs.playlistNames(BookmarksActivity.this);
                        String limit93 = BmPlAdd.limitToastKey(created93,
                                held93.contains(name93), held93.size());
                        if (limit93 != null) {
                            android.widget.Toast.makeText(BookmarksActivity.this,
                                    Lang.t(BookmarksActivity.this, limit93),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        int r93 = Prefs.playlistAdd(BookmarksActivity.this, name93, url, sd, title);
                        android.widget.Toast.makeText(BookmarksActivity.this,
                                Lang.t(BookmarksActivity.this, BmPlAdd.toastKey(r93)),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(Lang.t(this, "btn_no_clear"), null)
                .show();
    }

    private void playFromHereBm(final BmAll.Row r) {
        if (items == null || items.isEmpty() || r == null || r.e == null || r.e.url == null || r.e.url.length() == 0) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long now72 = android.os.SystemClock.uptimeMillis();
        if (now72 - lastBmPlayFromHereMs < 700) return;
        lastBmPlayFromHereMs = now72;
        // Tap-time re-checks (the dialog's own shape): the anchor
        // may have lost its stream URL or turned into the sounding
        // talk while the dialog sat open, or left the list (its
        // mark deleted from the Room list).
        refreshBookmarksPlayingMarks();
        final String streamUrl = streamUrlFor(r.e);
        if (streamUrl == null || streamUrl.length() == 0) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (BmPlayAll.isSounding(streamUrl, bmPlayingUrl, bmPlayingLibUrl)) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String sd72 = r.e.sayadaw;
        String[] cat72 = catalogFor(sd72);
        if (cat72 == null || cat72[1] == null || cat72[1].length() == 0) {
            String owner72 = Prefs.sayadawForTalk(this, "lib", streamUrl, "");
            if (owner72 != null && owner72.length() > 0 && !owner72.equals(sd72)) {
                sd72 = owner72;
                cat72 = catalogFor(sd72);
            }
        }
        if (cat72 == null || cat72[1] == null || cat72[1].length() == 0) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "pl_no_play"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // The anchor's display position, re-resolved by identity +
        // minute — the dialog can sit open across a search/sort/
        // refresh, so the captured position may have moved (the
        // v13.13 rule); a talk with three marks is three rows, so
        // the minute is part of the identity (BmAll's own dedupe
        // key), or the run would start at the talk's first mark.
        int anchorIdx72 = -1;
        for (int i72 = 0; i72 < items.size(); i72++) {
            BmAll.Row row72 = items.get(i72);
            if (row72 == null || row72.e == null || row72.e.url == null) continue;
            if (row72.posMs == r.posMs && SiteHttp.sameTalk(row72.e.url, r.e.url)) { anchorIdx72 = i72; break; }
        }
        java.util.List<BmAll.Row> from72;
        if (anchorIdx72 < 0) {
            // Anchor gone from the list while the dialog sat open:
            // queue just the anchor mark itself, never a stale range.
            from72 = new java.util.ArrayList<BmAll.Row>();
            from72.add(r);
        } else {
            from72 = items.subList(anchorIdx72, items.size());
        }
        // The anchor sayadaw's current queue, as identities — the
        // pick skips talks already queued (they stay where they
        // are). Saved lines are carried verbatim (histQueueAdd's
        // discipline), so a foreign line the Room drawer moved
        // mid-queue survives the write.
        java.util.Set<String> queuedIds72 = new java.util.HashSet<String>();
        java.util.ArrayList<String> saved72 = new java.util.ArrayList<String>();
        try {
            String savedRaw72 = Prefs.savedQueue(this, sd72);
            if (savedRaw72 != null && savedRaw72.length() > 0) {
                for (String ln72 : savedRaw72.split("\n")) {
                    if (ln72.length() == 0) continue;
                    saved72.add(ln72);
                    QueueEntry qe72 = QueueEntry.parse(ln72);
                    if (qe72 != null && qe72.url.length() > 0)
                        queuedIds72.add(SiteHttp.identityUrl(qe72.url));
                }
            }
        } catch (Exception ignored) {}
        // The run is the shown run from the anchor onward, as
        // resolved Items (a path-form mark resolves through
        // streamUrlFor or drops out) — BmPlayAll.lines keeps the
        // order, dedupes per mark, skips sounding and
        // already-queued talks exactly like Play-all-marks'.
        java.util.List<BmPlayAll.Item> runItems72 = buildBmItems(from72);
        java.util.List<String> run72 = BmPlayAll.lines(runItems72, queuedIds72, bmPlayingUrl, bmPlayingLibUrl);
        int added72 = 0;
        for (String ln72 : run72) {
            QueueEntry qe72 = QueueEntry.parse(ln72);
            if (qe72 == null || qe72.url.length() == 0) continue;
            boolean dup72 = false;
            for (String ex72 : saved72) {
                QueueEntry qx72 = QueueEntry.parse(ex72);
                if (qx72 != null && qx72.posMs == qe72.posMs
                        && SiteHttp.sameTalk(qx72.url, qe72.url)) { dup72 = true; break; }
            }
            if (dup72) continue;
            saved72.add(ln72);
            added72++;
        }
        Prefs.saveQueue(this, sd72, saved72);
        android.widget.Toast.makeText(BookmarksActivity.this, added72 > 0
                ? Lang.t(BookmarksActivity.this, "queue_plain_prefix") + added72 + Lang.t(BookmarksActivity.this, "queue_added_suffix")
                : Lang.t(BookmarksActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
        // Open the Room on the anchor at its minute (playBookmark's
        // own route — the mark was written into the talk's resume
        // spot by the openBmTalk1962 rule, so the Room resumes
        // there, v1.56; the queued v3 head for that same mark is
        // dropped on real play by the v4.43 ahead-drop, by URL, so
        // the run continues with the next mark instead of replaying
        // it). A queued anchor plays from its queue spot.
        BmAll.Row anchorRow72 = anchorIdx72 < 0 ? r : items.get(anchorIdx72);
        playBookmark(anchorRow72, null);
    }

    // v15.69 (slice 2072): "Delete bookmark" — the row menu's
    // removal verb (the Bookmarks rail's own, v15.55 slice 1992:
    // the only way to drop a mark without opening the Room
    // bookmark list, until now). The confirm is the library
    // delete's own (del_one_title + del_one_btn / del_btn_no, the
    // 1986 drawer shape) and its message names the exact minute
    // (BmRemove.confirmMessage — the row's "12:34 · title"
    // rendering, so a talk with several marks never deletes the
    // wrong one). The remove is Prefs.bookmarkRemoveBoth — the
    // Room ✕ delete's own call, both http/https forms — then
    // refresh() drops the row at once (re-pick + re-filter +
    // re-sort + jump-target refresh) and the toast is bm_deleted.
    // No tap-time re-check is needed: no concurrent deleter can
    // run while the modal menu/confirm sits open (the Room
    // bookmark list needs this screen dismissed first), and
    // bookmarkRemoveBoth is idempotent anyway (the rail's own
    // comment, MainActivity v15.55). No notification refresh: the
    // mark is not on the card, and a removed sounding talk keeps
    // playing — the mark is not playback.
    // v15.69 (slice 2075): "Add to queue" — the row menu's queue
    // verb (History's menu_queue_add, v13.77, on this screen's
    // menu): append the row's MARK to the row's sayadaw queue as a
    // v3 line (BmQueue.add — identity URL, the row's sayadaw, the
    // row's title, the mark's minute, the 2053 shape) and leave it
    // there for the next Room session on that sayadaw — the Room
    // pop (2054) opens on the mark when its turn comes, and the
    // sounding talk keeps playing, nothing opens. The offer hid
    // sounding marks and marks with no real minute (BmQueue.
    // queueKey, the BmPlayAll two-identity rule); the tap-time
    // re-checks refuse with queue_changed when the mark's talk
    // turned sounding or the row lost its stream URL while the
    // dialog sat open (the 2072 shape), and the dup check answers
    // already_in_queue. The resolved stream URL is re-read at tap
    // time (streamUrlFor — a path-form mark resolves through the
    // dlMap exactly-one-claimant rule, v8.44, or names no talk),
    // so the queued line is always a stream URL. No catalog heal:
    // the queue key is the row's sayadaw as listed (histQueueAdd's
    // discipline — no Room opens here, so no healed-name coupling
    // like playFromHereBm's). No list refresh: queueing changes
    // no row the list renders (there is no queued mark on this
    // screen — the 2041 mark renders the sounding talk only).
    private void queueAddBm(final BmAll.Row r) {
        if (r == null || r.e == null || r.e.url == null || r.e.url.length() == 0) return;
        refreshBookmarksPlayingMarks();
        final String streamUrl = streamUrlFor(r.e);
        final String sd = r.e.sayadaw;
        if (streamUrl == null || streamUrl.length() == 0 || sd == null || sd.length() == 0
                || BmPlayAll.isSounding(streamUrl, bmPlayingUrl, bmPlayingLibUrl)) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.ArrayList<String> saved75 = new java.util.ArrayList<String>();
        try {
            String savedRaw75 = Prefs.savedQueue(this, sd);
            if (savedRaw75 != null && savedRaw75.length() > 0) {
                for (String ln75 : savedRaw75.split("\n")) {
                    if (ln75.length() == 0) continue;
                    saved75.add(ln75);
                }
            }
        } catch (Exception ignored) {}
        java.util.ArrayList<String> next75 = BmQueue.add(streamUrl, sd, r.e.title, r.posMs, saved75);
        if (next75 == null) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "already_in_queue"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.saveQueue(this, sd, next75);
        android.widget.Toast.makeText(BookmarksActivity.this,
                Lang.t(BookmarksActivity.this, "added_to_queue"),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // v15.69 (slice 2075): "Remove from queue" — the
    // state-dependent twin of queueAddBm above (History's
    // menu_queue_remove, v13.77): drop the row's talk from the
    // row's sayadaw queue (BmQueue.remove — sameTalk over parsed
    // entries, every line for the talk, v2 and every v3 mark line,
    // so the talk leaves the queue entirely, the 2055 rule) and
    // leave the bookmark alone — queueing is not bookmarking, so
    // removing from the queue never deletes the mark. Same
    // tap-time re-checks as the add (sounding / blank sayadaw /
    // lost stream URL refuse with queue_changed); a talk that left
    // the queue while the dialog sat open answers queue_changed
    // too (BmQueue.remove returns null when absent). No list
    // refresh: the queue change renders nowhere on this screen.
    private void queueRemoveBm(final BmAll.Row r) {
        if (r == null || r.e == null || r.e.url == null || r.e.url.length() == 0) return;
        refreshBookmarksPlayingMarks();
        final String streamUrl = streamUrlFor(r.e);
        final String sd = r.e.sayadaw;
        if (streamUrl == null || streamUrl.length() == 0 || sd == null || sd.length() == 0
                || BmPlayAll.isSounding(streamUrl, bmPlayingUrl, bmPlayingLibUrl)) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.ArrayList<String> saved75 = new java.util.ArrayList<String>();
        try {
            String savedRaw75 = Prefs.savedQueue(this, sd);
            if (savedRaw75 != null && savedRaw75.length() > 0) {
                for (String ln75 : savedRaw75.split("\n")) {
                    if (ln75.length() == 0) continue;
                    saved75.add(ln75);
                }
            }
        } catch (Exception ignored) {}
        java.util.ArrayList<String> next75 = BmQueue.remove(streamUrl, saved75);
        if (next75 == null) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.saveQueue(this, sd, next75);
        android.widget.Toast.makeText(BookmarksActivity.this,
                Lang.t(BookmarksActivity.this, "removed_from_queue"),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // v15.70 (slice 2077): "Go to sayadaw's talks" — the row
    // menu's navigation verb (History's menu_go_sayadaw, v15.56
    // slice 1995, on this screen's menu; the Downloaded row's
    // 1944 verb through the same GoSayadaw core): re-resolve the
    // row's sayadaw through the bundled catalog at tap time
    // (catalogFor — cached, canonicalized, so a renamed sayadaw
    // still resolves; the offer and the tap can never disagree),
    // toast no_sayadaw_page on a failed re-read, else open that
    // sayadaw's Talks list inside the app with Main's own intent
    // extras (name/group/url/photo). The name is the row's own
    // sayadaw (the catalog entry carries no name slot). A
    // navigation mutates nothing: no bookmark, queue or resume
    // change, no pause guard (no sheet is left to return from).
    private void goToSayadawBm(final BmAll.Row r) {
        if (r == null || r.e == null || r.e.sayadaw == null || r.e.sayadaw.length() == 0) return;
        final String sd = r.e.sayadaw;
        String[] cat77 = catalogFor(sd);
        if (!BmGo.canOffer(sd, cat77)) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "no_sayadaw_page"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent gi77 = new android.content.Intent(BookmarksActivity.this, TalksActivity.class);
        gi77.putExtra("name", sd);
        gi77.putExtra("group", GoSayadaw.group(cat77));
        gi77.putExtra("url", GoSayadaw.page(cat77));
        gi77.putExtra("photo", GoSayadaw.photo(cat77));
        Fx.tap(BookmarksActivity.this);
        Fx.go(BookmarksActivity.this, gi77);
    }

    // v15.71 (slice 2084): "Share talk" — the row menu's share
    // verb (History's menu_share, v13.77, on this screen's menu;
    // the chooser's long-press dialog shares first, v7.82, and
    // the library row menu has shared since v9.55; the New talks
    // row menu shares since slice 2080, Favourites since slice
    // 2083): re-resolve the row to its stream URL at tap time
    // (streamUrlFor — the offer and the tap can never disagree),
    // toast talk_no_longer_in_list when the file behind a
    // path-form row no longer resolves (the History file-share's
    // stale toast), else share the talk's text through the
    // Android sheet — sayadaw — title + stream URL + the
    // dhammadownload.com credit (BmTalkShare.shareText, the
    // History text shape) — chooser titled share_talk. A share
    // mutates nothing: no bookmark, queue or resume change; no
    // pause guard (no sheet is left to return from); the sounding
    // row keeps it (sharing never touches playback). The shared
    // unit is the TALK, not the mark: a mark's minute is not in
    // the text — the minute share lives on the Main Bookmarks
    // rail (BmShare, v15.55 slice 1993), a different payload.
    private void shareTalkBm(final BmAll.Row r) {
        if (r == null || r.e == null || r.e.url == null || r.e.url.length() == 0) return;
        final String streamUrl = streamUrlFor(r.e);
        if (!BmTalkShare.canOffer(streamUrl)) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent send84 = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send84.setType("text/plain");
        send84.putExtra(android.content.Intent.EXTRA_TEXT,
                BmTalkShare.shareText(r.e.sayadaw, r.e.title, streamUrl,
                        Lang.t(BookmarksActivity.this, "share_source")));
        Fx.tap(BookmarksActivity.this);
        startActivity(android.content.Intent.createChooser(send84,
                Lang.t(BookmarksActivity.this, "share_talk")));
    }

    // v15.71 (slice 2086): "Copy link" — the row menu's clipboard
    // verb (History's dl_menu_copy_link, v15.53 slice 1981, on
    // this screen's menu; the library row menu has copied since
    // v6.56, the Room drawer since slice 1975, the Talks chooser
    // since slice 1977, the New talks row menu since slice 2081
    // and the Favourites row menu since slice 2085 — all through
    // the same RoomCopyLink core, so the surfaces can never
    // disagree): re-resolve the row to its stream URL at tap time
    // (streamUrlFor — the offer and the tap can never disagree),
    // toast talk_no_longer_in_list when the file behind a
    // path-form row no longer resolves (this screen's share
    // already sets that toast precedent; History's copy stays
    // silent, but a visible menu verb that silently does nothing
    // is a dead tap), else copy the talk's own original link
    // verbatim (the v6.56 rule — never a bare title, never a
    // guessed link) with the v6.56 clipboard label + toast
    // (RoomCopyLink.clipText / toastText). A copy mutates
    // nothing: no bookmark, queue or resume change; no pause
    // guard (no sheet is left to return from); the sounding row
    // keeps it (copying never touches playback). The copied unit
    // is the TALK, not the mark (the 2084 adjudication): the
    // minute payload lives on the Main Bookmarks rail (BmShare,
    // v15.55 slice 1993), a different payload.
    private void copyTalkLinkBm(final BmAll.Row r) {
        if (r == null || r.e == null || r.e.url == null || r.e.url.length() == 0) return;
        final String streamUrl = streamUrlFor(r.e);
        if (!RoomCopyLink.canOffer(streamUrl)) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.content.ClipboardManager cm86 =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm86 != null)
                cm86.setPrimaryClip(android.content.ClipData.newPlainText(
                        Lang.t(BookmarksActivity.this, "clip_talk"), RoomCopyLink.clipText(streamUrl)));
            android.widget.Toast.makeText(BookmarksActivity.this,
                    RoomCopyLink.toastText(Lang.t(BookmarksActivity.this, "link_copied_prefix"), r.e.title, streamUrl),
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    // v15.72 (slice 2090): "Share file" — the row menu's
    // file-share verb (History's dl_menu_share_file, v15.53
    // slice 1982, on this screen's menu; the library row menu
    // has shared its file since v15.48 slice 1954, the Room
    // drawer since slice 1955, the Talks chooser since slice
    // 1956, the History row menu since slice 1982, the New
    // talks row menu since v15.71 slice 2087 and the Favourites
    // row menu since v15.72 slice 2089 — all through the
    // same TalkFileShare core, so the surfaces can never
    // disagree): re-resolve the row to its stream URL at tap
    // time (streamUrlFor — the offer and the tap can never
    // disagree), re-check the download gate fresh
    // (bmIsDownloaded + bmDownloadFileNameFor — the History
    // v13.77 pair, mirrored), and send the actual .mp3 through
    // the Android sheet as a TalkFileProvider content:// URI
    // with a one-shot read grant, type audio/mpeg (a file://
    // URI is refused on API 24+), chooser titled share_talk.
    // A name that no longer resolves to a file on disk toasts
    // talk_no_longer_in_list, never a guessed URI. A share
    // mutates nothing: no bookmark, queue or resume change;
    // no pause guard (History's file share sets none; the text
    // share beside it sets none); the sounding row keeps it
    // (sharing never touches playback). The shared unit is the
    // talk's downloaded FILE, not the mark (the 2084
    // adjudication): the minute payload lives on the Main
    // Bookmarks rail (BmShare, v15.55 slice 1993), a different
    // payload.
    private void shareFileBm(final BmAll.Row r) {
        if (r == null || r.e == null || r.e.url == null || r.e.url.length() == 0) return;
        final String streamUrl = streamUrlFor(r.e);
        java.io.File dir90 = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma");
        String fn90 = bmIsDownloaded(streamUrl) ? bmDownloadFileNameFor(streamUrl) : null;
        String uri90 = TalkFileShare.shareUriFor(dir90, fn90);
        if (uri90 == null) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent send90 = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send90.setType("audio/mpeg");
        send90.putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse(uri90));
        send90.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(android.content.Intent.createChooser(send90,
                Lang.t(BookmarksActivity.this, "share_talk")));
    }

    // v15.79 (slice 2135): "Download" — the download verb the Bookmarks row menu lacked that the History row menu has had since v13.77, the Favourite-talks rail since v15.57 (slice 2000), the Talks chooser since v11.70, the Room drawer rows since v11.70 and the Favourites row menu since v15.79 (slice 2134): a bookmarked talk could be shared, copied, filed or opened, but never saved for offline listening without leaving the screen. Same verb through the same rules, so the surfaces can never disagree: tap-time re-checks (a Wi-Fi job can land, or another path can queue this talk, while the dialog sits open), a Wi-Fi-only DownloadManager request (NETWORK_WIFI, so a tap here can never silently spend mobile data — the data-saver rule), the filename from bmDownloadFileNameFor, and the record under DlLock in ONE apply (bmRecordDownload below — dlMap + dlSayadaw + dlQueued, identityUrl banking). Mirrors History's dl_btn_download dispatch verbatim, with the Bookmarks-local read side (streamUrlFor + bmIsDownloaded + bmIsDlQueued + bmDownloadFileNameFor). The blank-URL guard toasts talk_no_longer_in_list — the share helpers' precedent: Bookmarks re-resolves at tap time, so a file deleted while the menu sat open must toast, never enqueue a blank URL. No sounding gate (History offers it on sounding rows too); no pause guard (no sheet is left to return from). The adapter repaint + success toast are History's: Wi-Fi shows "\u2B07 title + dl_downloading_suffix", mobile data shows dl_room_wifi_queued.
    private void downloadTalkBm(final BmAll.Row r) {
        if (r == null || r.e == null || r.e.url == null || r.e.url.length() == 0) return;
        final String streamUrl = streamUrlFor(r.e);
        if (streamUrl == null || streamUrl.length() == 0) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // Tap-time re-checks (Talks v11.70): a Wi-Fi job can land, or another path can queue this talk, while the dialog sits open.
        if (bmIsDownloaded(streamUrl)) {
            if (adapter != null) adapter.notifyDataSetChanged();
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "downloaded_talk"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (bmIsDlQueued(streamUrl)) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "dl_already_queued"),
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String file = bmDownloadFileNameFor(streamUrl);
        try {
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            android.app.DownloadManager.Request req =
                    new android.app.DownloadManager.Request(
                            android.net.Uri.parse(SiteHttp.siteUrl(streamUrl)));
            req.setTitle(r.e.title != null && r.e.title.length() > 0 ? r.e.title : streamUrl);
            req.setNotificationVisibility(android.app.DownloadManager.Request
                    .VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            // Wi-Fi-only, exactly like Talks' chooser enqueue and History's row enqueue — on Wi-Fi it starts at once, on mobile data it waits for Wi-Fi, so a tap here can never silently spend mobile data.
            req.setAllowedNetworkTypes(android.app.DownloadManager.Request.NETWORK_WIFI);
            req.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, "Dhamma/" + file);
            dm.enqueue(req);
            bmRecordDownload(streamUrl, file, r.e.sayadaw);
        } catch (Exception ex) {
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "download_failed") + Fx.errDetail(ex),
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (Fx.stripIsWifi(BookmarksActivity.this))
            android.widget.Toast.makeText(BookmarksActivity.this,
                    "\u2B07 " + (r.e.title != null && r.e.title.length() > 0 ? r.e.title : streamUrl)
                            + Lang.t(BookmarksActivity.this, "dl_downloading_suffix"),
                    android.widget.Toast.LENGTH_SHORT).show();
        else
            android.widget.Toast.makeText(BookmarksActivity.this,
                    Lang.t(BookmarksActivity.this, "dl_room_wifi_queued"),
                    android.widget.Toast.LENGTH_LONG).show();
    }

    // v15.72 (slice 2090): History histIsDownloaded (v13.77),
    // Bookmarks-local — the dlMap mapped file with the v13.66
    // length gate (a 0-byte file is not downloaded), plus the
    // plain basename leg for files the app enqueued under a
    // stripped name. The offer and the tap both read it fresh,
    // so a download landing while the dialog sits open can
    // never be missed and a deletion can never be served.
    private boolean bmIsDownloaded(String url) {
        if (url == null || url.length() == 0) return false;
        try {
            java.io.File dir = new java.io.File(android.os.Environment
                    .getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma");
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
            String mapped = map.optString(SiteHttp.identityUrl(url), "");
            if (mapped.length() > 0) {
                java.io.File f = new java.io.File(dir, mapped);
                if (f.isFile() && f.length() > 0) return true;
            }
            String base = url.substring(url.lastIndexOf('/') + 1);
            int q = base.indexOf('?');
            if (q >= 0) base = base.substring(0, q);
            int h = base.indexOf('#');
            if (h >= 0) base = base.substring(0, h);
            if (base.length() > 0) {
                java.io.File f = new java.io.File(dir, base);
                if (f.isFile() && f.length() > 0) return true;
            }
            return false;
        } catch (Exception ignored) { return false; }
    }

    // v15.72 (slice 2090): History histDownloadFileNameFor
    // (v13.77), Bookmarks-local — the dlMap name wins, else the
    // basename stripped of ?/# with .mp3 guaranteed, and a
    // basename another URL's download claims takes the 8-hex MD5
    // suffix (v8.40 claim-alone rule).
    private String bmDownloadFileNameFor(String url) {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
            String mapped = map.optString(SiteHttp.identityUrl(url), "");
            if (mapped.length() > 0) return mapped;
            String base = url.substring(url.lastIndexOf('/') + 1);
            int q = base.indexOf('?');
            if (q >= 0) base = base.substring(0, q);
            int hh = base.indexOf('#');
            if (hh >= 0) base = base.substring(0, hh);
            if (!base.toLowerCase().endsWith(".mp3")) base = base + ".mp3";
            java.util.Iterator<String> keys = map.keys();
            boolean claimed = false;
            while (keys.hasNext()) {
                String k = keys.next();
                if (!SiteHttp.sameTalk(k, url) && base.equals(map.optString(k, ""))) {
                    claimed = true;
                    break;
                }
            }
            if (claimed) {
                String hash = "";
                try {
                    java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                    byte[] d = md.digest(url.getBytes("UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i]));
                    hash = sb.toString();
                } catch (Exception ignored) { hash = String.format("%08x", url.hashCode()); }
                if (base.toLowerCase().endsWith(".mp3"))
                    return base.substring(0, base.length() - 4) + "_" + hash + base.substring(base.length() - 4);
                return base + "_" + hash;
            }
            return base;
        } catch (Exception ignored) {
            String base = url.substring(url.lastIndexOf('/') + 1);
            if (!base.toLowerCase().endsWith(".mp3")) base = base + ".mp3";
            return base;
        }
    }

    // v15.79 (slice 2135): History histIsDlQueued (v13.77),
    // Bookmarks-local — fresh prefs read at offer/tap time, so a
    // job queued from the Room a second earlier still blocks a
    // double enqueue. Raw-equals scan like History's — the banked
    // form is identityUrl and the offer and the tap read the same
    // resolved stream URL.
    private boolean bmIsDlQueued(String url) {
        if (url == null) return false;
        try {
            org.json.JSONArray qa = new org.json.JSONArray(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlQueued", "[]"));
            for (int i = 0; i < qa.length(); i++)
                if (url.equals(qa.optString(i))) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // v15.79 (slice 2135): History histRecordDownload (v13.77),
    // Bookmarks-local — dlMap, dlSayadaw and dlQueued land in ONE
    // apply under DlLock.LOCK (v13.68/v13.70), the URL banked in
    // identity form (v13.71).
    private void bmRecordDownload(String url, String file, String sd) {
        synchronized (DlLock.LOCK) {
        try {
            url = SiteHttp.identityUrl(url);
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
            map.put(url, file);
            String sayStr = null;
            if (sd != null && sd.length() > 0) {
                try {
                    org.json.JSONObject sm = new org.json.JSONObject(sp.getString("dlSayadaw", "{}"));
                    sm.put(url, sd);
                    sayStr = sm.toString();
                } catch (Exception ignored) {}
            }
            org.json.JSONArray queued = new org.json.JSONArray(sp.getString("dlQueued", "[]"));
            boolean found = false;
            for (int i = 0; i < queued.length(); i++) {
                if (SiteHttp.sameTalk(url, queued.optString(i))) { found = true; break; }
            }
            if (!found) queued.put(url);
            android.content.SharedPreferences.Editor ed = sp.edit()
                    .putString("dlMap", map.toString())
                    .putString("dlQueued", queued.toString());
            if (sayStr != null) ed.putString("dlSayadaw", sayStr);
            ed.apply();
        } catch (Exception ignored) {}
        }
    }

    private void removeBmFromMenu(final BmAll.Row r) {
        if (r == null || r.e == null || r.e.url == null || r.e.url.length() == 0) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "del_one_title"))
                .setMessage(BmRemove.confirmMessage(r.posMs, r.e.title, r.e.url))
                .setPositiveButton(Lang.t(this, "del_one_btn"), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        Prefs.bookmarkRemoveBoth(BookmarksActivity.this, r.e.url, r.posMs);
                        refresh();
                        android.widget.Toast.makeText(BookmarksActivity.this, Lang.t(BookmarksActivity.this, "bm_deleted"), android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(Lang.t(this, "del_btn_no"), null)
                .show();
    }

    private void playBookmark(final BmAll.Row r, android.view.View v) {
        if (r == null || r.e == null || r.e.url == null || r.e.url.length() == 0) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastBmTapMs < 700) return;
        lastBmTapMs = now;
        // A path-form row (a mark written while a downloaded file
        // played) resolves to its stream URL first (the
        // histStreamUrlFor exactly-one-claimant rule, v8.44) so the
        // Room route below can land on the minute; a file with no
        // claimant names no talk rather than the wrong one.
        String streamUrl = streamUrlFor(r.e);
        if (streamUrl == null || streamUrl.length() == 0) {
            Toast.makeText(this, Lang.t(this, "pl_no_play"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String sd = r.e.sayadaw;
        String[] cat = catalogFor(sd);
        if (cat == null || cat[1] == null || cat[1].length() == 0) {
            String owner = Prefs.sayadawForTalk(this, "lib", streamUrl, "");
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
        // The mark becomes the talk's resume spot (talkPosPutBoth,
        // both forms) and the Room opens through the Room intent,
        // which resumes from the saved spot (v1.56 re-entry), so
        // the tap lands on the mark — the openBmTalk1962 rule.
        Prefs.talkPosPutBoth(this, streamUrl, r.posMs);
        Intent it = new Intent(this, RoomActivity.class);
        it.putExtra("name", sd);
        it.putExtra("group", cat[0]);
        it.putExtra("url", cat[1]);
        it.putExtra("photo", cat[2]);
        it.putExtra("autoUrl", streamUrl);
        it.putExtra("autoTitle", r.e.title);
        Fx.tap(this);
        Fx.go(this, it);
    }

    // v15.63 (slice 2037): real mini-player on this screen — a
    // faithful mirror of History's v13.54 strip (the same strip as
    // Talks' v4.31). The v13.55 row-mark head lives in
    // syncBookmarksStrip below since v15.64 (slice 2041). Live session:
    // title + star/sleep/download/queue/ended marks, sayadaw + clock
    // sub line, seekable progress, play/pause glyph, portrait with
    // the sounding gold ring. Dead session with a saved spot (>5s):
    // the resume strip (Room talk or downloaded file); anything
    // else: hidden, so the bookmarks stay list-only. The two
    // resume-match computations delegate to the pure StripResume
    // core; every other branch is History's body verbatim, renamed.
    // v15.64 (slice 2041): sounding identities for the row mark —
    // History's refreshHistoryPlayingMarks, minus the queue cache
    // (Bookmarks has no queue marks). Room: liveSession +
    // liveHasTrack (v10.43 bound-trackless stop: a stopped talk is
    // never the playing talk) + lastKind room -> lastUrl. Library:
    // same live guards + lastKind lib -> lastLibPath, plus the dlMap
    // reverse lookup for URL-form rows (exactly-one claimant, v8.44
    // — a dual-claimed file names no talk rather than the wrong one).
    // Prefs only, never per bind.
    private void refreshBookmarksPlayingMarks() {
        bmPlayingUrl = "";
        bmPlayingLibUrl = "";
        bmPlayingLibPath = "";
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (AudioService.liveSession && AudioService.liveHasTrack
                    && "room".equals(sp.getString("lastKind", "")))
                bmPlayingUrl = sp.getString("lastUrl", "");
            if (AudioService.liveSession && AudioService.liveHasTrack
                    && "lib".equals(sp.getString("lastKind", ""))) {
                bmPlayingLibPath = sp.getString("lastLibPath", "");
                String libPath = bmPlayingLibPath;
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
                    if (matches == 1) bmPlayingLibUrl = hit;
                }
            }
        } catch (Exception ignored) {}
    }

    private void syncBookmarksStripPhoto(String name) {
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
        if (ringing != lastBmStripPhotoRing) {
            lastBmStripPhotoRing = ringing;
            if (ring != null) {
                // v14.16 (slice 1670): the ring crossfades in/out
                // instead of snapping — see Fx.crossfadeRing.
                Fx.crossfadeRing(ring, ringing ? R.drawable.shelf_ring_playing : 0);
            }
        }
        if (name == null || name.length() == 0) {
            bmStripPhotoName = null;
            if (ring != null) ring.setVisibility(android.view.View.GONE);
            else pv.setVisibility(android.view.View.GONE);
            return;
        }
        if (ring != null) ring.setVisibility(android.view.View.VISIBLE);
        else pv.setVisibility(android.view.View.VISIBLE);
        if (name.equals(bmStripPhotoName)) return;
        boolean firstPhoto = bmStripPhotoName == null;
        bmStripPhotoName = name;
        String url = AudioService.stripPhotoUrlForSayadaw(BookmarksActivity.this, name);
        // v14.22 (slice 1676): the photo crossfades on a sayadaw
        // change instead of swapping — see Fx.crossfadeStripPhoto.
        Fx.crossfadeStripPhoto((CircleImageView) pv, url, firstPhoto, new Runnable() {
            @Override public void run() {
                ImageLoader.load(BookmarksActivity.this, url, name, (CircleImageView) pv);
            }
        });
    }

    // v15.40 (slice 1907): the Bookmarks resume strip's title melts on a
    // structural change instead of snapping — the mirror of Main's
    // v14.70 meltResumeStripTitle70. The 1s tick re-runs
    // syncBookmarksStrip in resume mode, and the title carries the
    // download mark (stripResumeDownloadMark / the lib branch's inline
    // check), so a download landing while Bookmarks sits open flipped
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
    private String bmResumeStripTitle = null;
    private void meltResumeStripTitleBm(android.widget.TextView tx, String want) {
        if (tx == null || want == null) return;
        Object aObjHs = tx.getTag(R.id.stripTextAnim);
        if (aObjHs instanceof android.animation.Animator) ((android.animation.Animator) aObjHs).cancel();
        String prevHs = bmResumeStripTitle;
        bmResumeStripTitle = want;
        if (prevHs == null) { tx.setText(want); return; }
        if (prevHs.equals(want)) return;
        tx.setText(want);
        if (tx.getVisibility() == android.view.View.VISIBLE && tx.getAlpha() >= 0.999f) Fx.fadeIn(tx, 250);
    }

    private void syncBookmarksStrip() {
        // v15.64 (slice 2041): playing-row mark — rebind once when the sounding talk changes under the list
        // (History v13.55, Talks v4.89/v11.15, same change-only rule): auto-advance, a Next from the strip, a
        // natural end (same URL, liveEnded flipped) or a stop moves the mark at once; steady ticks
        // never touch the adapter, so scrolling stays undisturbed. Runs before the strip's own early
        // returns, so a dead session clears the mark even while the strip hides.
        refreshBookmarksPlayingMarks();
        syncBmPlayAllBtn(false); // v15.66 (slice 2055): the sounding talk moved under the list — re-show or hide Play-all with the mark, change-only
        if (adapter != null && (!bmPlayingUrl.equals(bmRowPlayingUrl == null ? "" : bmRowPlayingUrl)
                || !bmPlayingLibUrl.equals(bmRowPlayingLibUrl == null ? "" : bmRowPlayingLibUrl)
                || !bmPlayingLibPath.equals(bmRowPlayingLibPath == null ? "" : bmRowPlayingLibPath)
                || AudioService.liveEnded != bmRowEnded)) {
            bmRowPlayingUrl = bmPlayingUrl;
            bmRowPlayingLibUrl = bmPlayingLibUrl;
            bmRowPlayingLibPath = bmPlayingLibPath;
            bmRowEnded = AudioService.liveEnded;
            adapter.notifyDataSetChanged();
            // v15.67 (slice 2062): the sounding change above moves the playing talk (advance, stop, or a new session while browsing) — refresh the jump button's cached target and repaint it. Change-only: steady ticks never touch the button.
            refreshBmJumpTarget();
            paintBmJumpBtnFromList();
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
            bmResumeStripMode = false;
            android.content.SharedPreferences rsp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String rKind = rsp.getString("lastKind", "");
            String rName = rsp.getString("lastName", "");
            String rTitle = rsp.getString("lastTitle", "");
            String rPage = rsp.getString("lastPage", "");
            String rUrl = rsp.getString("lastUrl", "");
            int rPos = rsp.getInt("lastPos", 0);
            String rSavedUrl = rsp.getString("lastPosUrl", "");
            // v15.63 (slice 2037): the Room resume match delegates
            // to the pure StripResume core (sameTalk direct, or the
            // last URL's dlMap filename ending the saved path, with
            // the v13.62 legacy-http identity leg). The dlMap is
            // parsed once here (dlMapNow2037) and reused by the
            // library leg below — History parses it twice, once
            // per leg. The core takes a plain Map because the JVM
            // test harness cannot construct org.json (stubbed in
            // the SDK android.jar); on-device the lookup is the
            // same JSONObject read, once per tick.
            java.util.Map<String, String> dlMap2037 = dlMapNow2037(rsp);
            boolean rMatch = StripResume.roomMatch(rSavedUrl, rUrl, dlMap2037);
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
                            || Fx.stripInProgressDlFilenames(BookmarksActivity.this, rsp).contains(new java.io.File(lPath).getName()))) {
                    // v15.63 (slice 2037): the library resume match
                    // delegates to the pure StripResume core (the
                    // path itself, or a dlMap URL whose filename is
                    // this file's name and whose talk is the saved
                    // one), over the map parsed once above. The
                    // v14.01 file gate above stays inline.
                    lMatch = StripResume.libMatch(rSavedUrl, lPath, dlMap2037);
                }
                if (lTitle.length() == 0 || !lMatch) {
                    syncBookmarksStripPhoto(null);
                    bmResumeStripTitle = null;
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
                int lDur = Fx.stripResumeDuration(BookmarksActivity.this, rsp, rSavedUrl, lPath);
                // v8.52: Main's v7.94 resume mark, mirrored — the proven file is offline, like Main's lib branch.
                meltResumeStripTitleBm(tx, "↺ " + lTitle + (lFav ? " ★" : "") + (Fx.stripInProgressDlFilenames(BookmarksActivity.this, rsp).contains(new java.io.File(lPath).getName()) ? " ⏳" : " ⬇"));
                // v5.46: a dead library strip names its recorded
                // sayadaw like the live strip since v5.45, in the
                // Room strip's "name · mm:ss မှ ဆက်နားထောင်ရန်"
                // shape; an unrecorded download keeps today's
                // generic line, never a guessed name.
                String libSd = Fx.stripLibSayadaw(BookmarksActivity.this, rsp);
                if (sb != null) sb.setText(libSd != null ? libSd + " · " + lTime + Lang.t(BookmarksActivity.this, "resume_from") : Lang.t(BookmarksActivity.this, "downloaded_talk") + " · " + lTime + Lang.t(BookmarksActivity.this, "resume_from"));
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
                bmResumeStripMode = true;
                syncBookmarksStripPhoto(libSd);
                Fx.showStrip(strip);
                return;
            }
            if (!"room".equals(rKind) || rName.length() == 0 || rTitle.length() == 0
                    || rPage.length() == 0 || rPos <= 5000 || !rMatch) {
                syncBookmarksStripPhoto(null);
                bmResumeStripTitle = null;
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
            int rDur = Fx.stripResumeDuration(BookmarksActivity.this, rsp, rUrl, null);
            // v8.52: Main's v7.94 resume download mark, mirrored — a ↺ strip here never said whether resuming streams or plays offline.
            meltResumeStripTitleBm(tx, "↺ " + rTitle + (rFav ? " ★" : "") + Fx.stripResumeDownloadMark(BookmarksActivity.this, rsp, rUrl));
            if (sb != null) sb.setText(rName + " · " + rTime + Lang.t(BookmarksActivity.this, "resume_from"));
            if (tg != null) tg.setText("▶");
            if (nx != null) Fx.crossfadeStripBtn(nx, false);
            // v7.31: same Previous hide as the library resume
            // branch above — a ↺ strip never shows ⏮.
            android.view.View pv0 = findViewById(R.id.nowStripPrev);
            if (pv0 != null) Fx.crossfadeStripBtn(pv0, false);
            android.widget.ProgressBar rPb = findViewById(R.id.nowStripProgress);
            if (rPb != null) Fx.stripResumeProgress(rPb, rPos, rDur);
            bmResumeStripMode = true;
            syncBookmarksStripPhoto(rName);
            Fx.showStrip(strip);
            return;
        }
        bmResumeStripMode = false;
        bmResumeStripTitle = null; // v15.40: live branch owns tx via fadeStripText from here
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
                String libSd = Fx.stripLibSayadaw(BookmarksActivity.this, sp);
                subText = libSd != null ? libSd : Lang.t(BookmarksActivity.this, "downloaded_talk");
            }
        }
        if (text == null) { syncBookmarksStripPhoto(null); Fx.hideStrip(strip); return; }
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
                    ? " 😴 " + Lang.t(BookmarksActivity.this, "sleep_end")
                    : " 😴 ⏸ " + Lang.t(BookmarksActivity.this, "sleep_end");
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
                String saved = Prefs.savedQueue(BookmarksActivity.this,
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
                String saved = Prefs.savedQueueLib(BookmarksActivity.this);
                if (saved.length() > 0) {
                    for (String line : saved.split("\n"))
                        if (line.trim().length() > 0
                                && new java.io.File(line.trim()).isFile()
                                && !line.trim().equals(curLib)) qN++;
                }
            }
            if (qN > 0) text += " · ◈ " + qN;
        } catch (Exception ignored) {}
        if (AudioService.liveEnded) text += " · " + Lang.t(BookmarksActivity.this, "notif_state_ended");
        // v14.21 (slice 1675): the strip title/sub fade on a talk
        // change, keyed on the talk itself (Room URL / library
        // path) — the composed strings tick every second (sleep
        // countdown on the title, live clock on the sub), so the
        // text can never be the key.
        String stripTalkId = "room".equals(kind) ? sp.getString("lastUrl", "") : sp.getString("lastLibPath", "");
        if (tx != null) Fx.fadeStripText(tx, text, stripTalkId);
        // v5.32: live clock on the strip — sayadaw name + elapsed/total.
        android.widget.ProgressBar pb = findViewById(R.id.nowStripProgress);
        if (subText != null) subText += Fx.stripClockSuffixForStrip(pb, AudioService.livePositionMs, AudioService.liveDurationMs, Prefs.stripClockRemaining(BookmarksActivity.this));
        if (sb != null && subText != null) Fx.fadeStripText(sb, subText, stripTalkId);
        Fx.syncStripProgress(pb); // v7.93: skips while a mini-seek drag is in flight
        if (tg != null) Fx.flipStripGlyph(tg, AudioService.livePlaying);
        // v6.01: live strip's portrait — Room names lastName, library
        // names its recorded sayadaw (Fx.stripLibSayadaw, null when
        // unrecorded); both resolve through syncBookmarksStripPhoto's
        // exact-name catalog match, never a guessed face.
        syncBookmarksStripPhoto("room".equals(kind) ? sp.getString("lastName", "")
                : ("lib".equals(kind) ? Fx.stripLibSayadaw(BookmarksActivity.this, sp) : null));
        Fx.showStrip(strip);
    }

    private void startBookmarksStripTick() {
        if (stripTick != null) return;
        stripTick = new Runnable() {
            @Override public void run() {
                if (stripTick == null) return;
                // v11.76: re-post in finally — a throw inside syncBookmarksStrip used
                // to kill the 1s tick outright, freezing strip title/clock/
                // progress/glyph until the next onPause/onResume cycle.
                try { syncBookmarksStrip(); }
                finally { stripHandler.postDelayed(this, 1000); }
            }
        };
        stripHandler.post(stripTick);
    }

    private void stopBookmarksStripTick() {
        if (stripTick != null) {
            stripHandler.removeCallbacks(stripTick);
            stripTick = null;
        }
    }

    private void wireBookmarksStrip() {
        android.view.View nowStrip = findViewById(R.id.nowStrip);
        if (nowStrip != null) {
            Fx.press(nowStrip);
            // v4.73: the strip's progress bar seeks (see Fx.miniSeekBar).
            Fx.miniSeekBar(BookmarksActivity.this, nowStrip);
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
                        if (bmResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) return;
                        Prefs.setStripClockRemaining(BookmarksActivity.this, !Prefs.stripClockRemaining(BookmarksActivity.this));
                        Fx.tap(BookmarksActivity.this);
                        syncBookmarksStrip();
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
                            Fx.tap(BookmarksActivity.this);
                            Fx.morph(v);
                            Intent li = new Intent(BookmarksActivity.this, DownloadedActivity.class);
                            li.putExtra("jumpPath", lp);
                            // v4.63: in dead-library resume mode
                            // the tap also auto-plays the file from
                            // its saved spot; a live session never
                            // passes this flag, so it still only
                            // glides to the playing file.
                            // v12.57: live recheck, not just the cached
                            // flag — bmResumeStripMode refreshes on
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
                            if (bmResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) li.putExtra("autoPlayPath", lp);
                            Fx.go(BookmarksActivity.this, li);
                            return;
                        }
                        android.widget.Toast.makeText(BookmarksActivity.this, Lang.t(BookmarksActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(BookmarksActivity.this, Lang.t(BookmarksActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(BookmarksActivity.this, RoomActivity.class);
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
                    Fx.tap(BookmarksActivity.this);
                    Fx.morph(v);
                    Fx.go(BookmarksActivity.this, i);
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
                    // bmResumeStripMode refreshes on the 1s tick, so a
                    // session dying <1s before this long-press left the
                    // flag false and the press ACTION_STOPped a trackless
                    // service (phantom "talk_stopped" toast, saved spot
                    // NOT dismissed — the same ≤1s stale-flag class
                    // v12.55 closed for the Toggle long-press). The
                    // strip's own branch test is !liveSession ||
                    // !liveHasTrack, so re-checking the same statics here
                    // can only dismiss a genuinely dead session, never
                    // stop a live one.
                    if (bmResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        // v5.34: same as Talks' dismiss — the
                        // per-talk spot goes with the global one.
                        String dismissUrl = getSharedPreferences("dhamma", MODE_PRIVATE)
                                .getString("lastPosUrl", "");
                        getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                                .putInt("lastPos", 0).putString("lastPosUrl", "").apply();
                        if (dismissUrl.length() > 0) Prefs.talkPosRemoveBoth(BookmarksActivity.this, dismissUrl);
                        Fx.tap(BookmarksActivity.this);
                        Fx.morph(v);
                        // v13.28: same as Downloaded's dismiss — one resume spot cleared,
                        // not the history; toast resume_cleared (Main's key), not
                        // hist_cleared (Settings' real clear-history key).
                        android.widget.Toast.makeText(BookmarksActivity.this, Lang.t(BookmarksActivity.this, "resume_cleared"), android.widget.Toast.LENGTH_SHORT).show();
                        syncBookmarksStrip();
                        // Bookmarks rows carry no ↺ mark, so this
                        // rebind changes nothing visible today; it
                        // keeps the dismiss path a faithful mirror
                        // of Talks' (v12.41) if a mark ever lands.
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return true;
                    }
                    Intent svc = new Intent(BookmarksActivity.this, AudioService.class);
                    svc.setAction(AudioService.ACTION_STOP);
                    startService(svc);
                    Fx.tap(BookmarksActivity.this);
                    Fx.morph(v);
                    android.widget.Toast.makeText(BookmarksActivity.this, Lang.t(BookmarksActivity.this, "talk_stopped"), android.widget.Toast.LENGTH_SHORT).show();
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
                // v12.55: live recheck, not just the cached flag — bmResumeStripMode
                // refreshes on the 1s tick, so a session dying <1s before this tap left
                // the flag false and the tap ACTION_TOGGLEd a trackless service (dead
                // tap, tap-twice-to-resume — the exact bug Settings v11.90 fixed for
                // its Toggle). The strip's own branch test is !liveSession ||
                // !liveHasTrack, so re-checking the same statics here can only route a
                // genuinely dead session to resume, never a live one.
                if (bmResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                    android.view.View strip2 = findViewById(R.id.nowStrip);
                    if (strip2 != null) strip2.performClick();
                    return;
                }
                Intent svc = new Intent(BookmarksActivity.this, AudioService.class);
                svc.setAction(AudioService.ACTION_TOGGLE);
                startService(svc);
                Fx.tap(BookmarksActivity.this);
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
                if (bmResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                    android.view.View strip2 = findViewById(R.id.nowStrip);
                    if (strip2 != null) return strip2.performLongClick();
                    return true;
                }
                Intent svc = new Intent(BookmarksActivity.this, AudioService.class);
                svc.setAction(AudioService.ACTION_STOP);
                startService(svc);
                Fx.tap(BookmarksActivity.this);
                Fx.morph(v);
                android.widget.Toast.makeText(BookmarksActivity.this, Lang.t(BookmarksActivity.this, "talk_stopped"), android.widget.Toast.LENGTH_SHORT).show();
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
                    // bmResumeStripMode refreshes on the 1s tick, so a session dying
                    // <1s before this tap left the flag false and the tap ACTION_NEXT-ed
                    // into a trackless Room instead of resuming the ↺ talk.
                    if (bmResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    Fx.tap(BookmarksActivity.this);
                    Fx.morph(v);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        Intent li = new Intent(BookmarksActivity.this, DownloadedActivity.class);
                        li.setAction(DownloadedActivity.ACTION_NEXT);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        Fx.go(BookmarksActivity.this, li);
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(BookmarksActivity.this, Lang.t(BookmarksActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(BookmarksActivity.this, RoomActivity.class);
                    i.setAction(RoomActivity.ACTION_NEXT);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.go(BookmarksActivity.this, i);
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
                    if (bmResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    Fx.tap(BookmarksActivity.this);
                    Fx.morph(v);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        Intent li = new Intent(BookmarksActivity.this, DownloadedActivity.class);
                        li.setAction(DownloadedActivity.ACTION_PREV);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        Fx.go(BookmarksActivity.this, li);
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(BookmarksActivity.this, Lang.t(BookmarksActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(BookmarksActivity.this, RoomActivity.class);
                    i.setAction(RoomActivity.ACTION_PREV);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.go(BookmarksActivity.this, i);
                }
            });
        }
    }

    // v15.63 (slice 2037): the download map as a plain map
    // for the StripResume core — parsed once per strip sync
    // (History parses it inline once per leg). Torn JSON reads
    // as an empty map, so a torn write hides the resume strip
    // for one pass, never a crash.
    private java.util.Map<String, String> dlMapNow2037(android.content.SharedPreferences rsp) {
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

    // v15.71 (slice 2084): the path-form leg now delegates to the
    // pure BmTalkShare.resolve over the dlMap (dlMapNow2037 — the
    // same parse the strip pays for); the inline loop was the
    // same exactly-one-claimant rule, v8.44, written twice — once
    // here, once as the share core — so the rule lives in one
    // place and the WgTest pins it directly (the 2083
    // adjudication). Behaviour is identical: the same base
    // extraction, the same single-claimant gate, "" on a miss or
    // a torn map. Every verb that resolves a row (play-from-here,
    // queue, play-all, the share) flows through this one method,
    // so the offer and every tap can never disagree.
    private String streamUrlFor(Prefs.HistEntry e) {
        if (e == null || e.url == null || e.url.length() == 0) return "";
        if (!e.url.startsWith("/")) return SiteHttp.identityUrl(e.url);
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            return BmTalkShare.resolve(e.url, dlMapNow2037(sp));
        } catch (Exception ignored) { return ""; }
    }

    // v15.49 (slice 1962): raw bookmarks64 lines for the rail — the
    // store's own most-recent-first order; all parsing lives in
    // BmAll.pickAll. Fail closed to an empty list — a torn write
    // is an empty screen for one pass, never a crash.
    private java.util.List<String> readBookmarks() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        try {
            String raw = getSharedPreferences("dhamma", MODE_PRIVATE).getString("bookmarks64", "");
            if (raw != null && raw.length() > 0) {
                for (String line : raw.split("\n")) if (line.length() > 0) out.add(line);
            }
        } catch (Exception ignored) {}
        return out;
    }

    // Sayadaw -> {group, page_url, photo_url} from the bundled
    // catalog (HistoryActivity.catalogFor verbatim: the asset only
    // changes with an app update, which is a new process, so a
    // per-process cache cannot go stale). Aliases resolve to the
    // current name first, so a pre-rename name stored in a recent
    // row still opens its Room.
    private void ensureCatalog() {
        if (catCache != null) return;
        catCache = new java.util.HashMap<String, String[]>();
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
                catCache.put(n, new String[]{
                        o.optString("group", ""),
                        o.isNull("page_url") ? "" : o.optString("page_url", ""),
                        o.isNull("photo_url") ? "" : o.optString("photo_url", "")});
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

    private java.util.Set<String> catalogNames() {
        ensureCatalog();
        return new java.util.HashSet<String>(catCache.keySet());
    }
}
