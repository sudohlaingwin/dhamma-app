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
    // v15.66 (slice 2057): sort on this screen — the button and the
    // persisted mode (the Bookmarks bmSortMode shape, v15.65 slice
    // 2051). The mode is read from Prefs in wireNewSort(); the
    // button label names the CURRENT order (newSortLabel, the 2051
    // bm_sort_* keys — the keys name the ORDER, not the screen),
    // so the order reads in a still. Rotation recreates the
    // activity and re-reads the persisted mode — no watcher or
    // saved-state needed.
    private android.widget.Button newSortBtn = null;
    private int newSortMode = 0;
    // v15.67 (slice 2061): "Jump to the playing talk" — the
    // floating gold ▶ over the list's bottom-end corner; newPlayingShown is the last-painted state
    // for the painter's change gate, and newJumpTargetIdx is the
    // cached SHOWN-list index of the sounding talk (recomputed on
    // search/sort/refresh and on sounding change, never on scroll
    // — the 1994 pattern with a cache, so a fling never pays a
    // sameTalk scan per scroll event).
    private android.view.View newPlayingBtn = null;
    private boolean newPlayingShown = false;
    private int newJumpTargetIdx = -1;
    // v15.65 (slice 2047): "Play all new" on this screen (the
    // chooser's v13.76 Play All + the library's v15.50
    // play-listened + Favourites' v15.64 play-all, the fourth list
    // that deserves the verb) — the new talks used to play one row
    // tap at a time; one tap here queues the whole shown run
    // across sayadaws (NewPlayAll v2 lines) and opens the Room on
    // the first new talk at 0:00. lastNewPlayAllMs is the handler's
    // own 700ms clock (the chooser's v12.28 rule); btnNewPlayAll
    // is the header button (GONE until a list lands, the chooser's
    // refreshTalkPlayAllRow shape); lastNewPlayAllVisible is the
    // change-only snapshot — the sync lives in applyNewSearch
    // (the filtered list IS the run) and in the 1s strip-tick
    // head, so steady ticks never touch the button.
    private long lastNewPlayAllMs = 0;
    private android.widget.Button btnNewPlayAll = null;
    private Boolean lastNewPlayAllVisible = null;
    // v15.68 (slice 2066): "Shuffle play" on this screen — the
    // chooser's v13.93 shuffle-play, the second of the three
    // full-list screens to get it: one tap shuffles the shown
    // new-talk run into the queue and opens the Room on the first
    // shuffled talk at 0:00. lastNewShuffleMs is the handler's own
    // 700ms clock (the chooser's v12.28 rule — a separate clock
    // from Play-all-new's, so a Shuffle tap never eats a Play-all
    // tap); btnNewShuffle is the header button beside Play-all
    // (GONE until a list lands, the same gate, the same
    // change-only snapshot — syncNewPlayAllBtn paints both).
    private long lastNewShuffleMs = 0;
    private android.widget.Button btnNewShuffle = null;
    // v15.69 (slice 2071): row long-press menu on this screen — the
    // New talks screen had search, sort, play-all, shuffle, jump,
    // mark and strip, but no row menu at all: a listener could star
    // a new talk only by leaving the screen, and nothing started a
    // run mid-list. lastNewPlayFromHereMs is the handler's own
    // 700ms clock (the chooser's v12.28 rule — a separate clock from
    // Play-all-new's, so a Play-from-here tap never eats a Play-all
    // tap).
    private long lastNewPlayFromHereMs = 0;

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
            // v15.69 (slice 2071): the row's long-press menu — tap
            // still plays; a long-press opens Play-from-here /
            // Add-or-remove-favourite (showNewRowMenu). The History
            // v13.77 shape: Fx.tap blip, then the menu, return true.
            listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                    if (pos < 0 || pos >= items.size()) return false;
                    Fx.tap(NewTalksActivity.this);
                    showNewRowMenu(items.get(pos));
                    return true;
                }
            });
            // v15.67 (slice 2061): the jump button paints on the same scroll — off-screen is its only show rule, so a laid-out window is required (count 0 means no layout yet).
            listView.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
                @Override public void onScrollStateChanged(android.widget.AbsListView v, int scrollState) {}
                @Override public void onScroll(android.widget.AbsListView v, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                    if (visibleItemCount > 0) paintNewJumpBtn(firstVisibleItem, firstVisibleItem + visibleItemCount - 1);
                }
            });
        }
        wireNewSearch(); // v15.64 (slice 2045): search on this screen, before the first refresh
        wireNewSort(); // v15.66 (slice 2057): sort on this screen, before the first refresh
        wireNewJumpBtn(); // v15.67 (slice 2061): jump-to-playing button on this list
        refresh();
        // v15.65 (slice 2047): wire the Play-all button the same way
        // as the chooser's Play All (v13.76) — one tap plays the whole
        // shown new-talk run in order; the label is the chooser's own
        // pl_play_all key, so no new string. The button stays GONE
        // until applyNewSearch() shows it (syncNewPlayAllBtn below).
        btnNewPlayAll = findViewById(R.id.btnNewPlayAll);
        if (btnNewPlayAll != null) btnNewPlayAll.setText(Lang.t(this, "pl_play_all"));
        if (btnNewPlayAll != null) btnNewPlayAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(NewTalksActivity.this);
                playAllNew(v);
            }
        });
        if (btnNewPlayAll != null) Fx.press(btnNewPlayAll);
        // v15.68 (slice 2066): wire the Shuffle button the same
        // way — one tap shuffles the shown run into the queue and
        // starts the first shuffled talk (shufflePlayNew). The
        // label is the pre-existing Lang "shuffle" key, so no new
        // key and no literal here. The button stays GONE until
        // applyNewSearch() shows it (syncNewPlayAllBtn below).
        btnNewShuffle = findViewById(R.id.btnNewShuffle);
        if (btnNewShuffle != null) btnNewShuffle.setText(Lang.t(this, "shuffle"));
        if (btnNewShuffle != null) btnNewShuffle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(NewTalksActivity.this);
                shufflePlayNew(v);
            }
        });
        if (btnNewShuffle != null) Fx.press(btnNewShuffle);
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
        // v15.66 (slice 2057): the sort rides the search pass — the
        // shown list is the filtered list in the chosen order, so a
        // keystroke re-sorts in memory and the sort tap re-runs this
        // pass instead of re-reading the pick. The playing-row mark
        // needs nothing here, like the search (2045): itemRow
        // re-reads the sounding identities per bind. The play-all
        // run follows the sorted list (the 2047 rule): the shown run
        // is the run, in the chosen order.
        items = NewSort.sort(NewSearch.filter(newAllItems, newQuery), newSortMode);
        if (adapter != null) adapter.notifyDataSetChanged();
        refreshNewClear(newSearchKeystroke);
        if (emptyText == null) return;
        boolean empty = items.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) emptyText.setText(Lang.t(this,
                newQuery.length() > 0 ? "no_search_match" : "no_new_talk"));
        syncNewPlayAllBtn(true); // v15.65 (slice 2047): Play-all follows the FILTERED list — the shown run is the run. First paint here (refresh() calls applyNewSearch), and every keystroke re-syncs it with the shown run; instant, never faded, the clear-X silent-pass rule.
        // v15.67 (slice 2061): a search / sort / refresh re-shows the list — the jump button's cached target moves with it (new list, new filter), so refresh the target and repaint from the new position.
        refreshNewJumpTarget();
        paintNewJumpBtnFromList();
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

    // v15.66 (slice 2057): sort on this screen — the Bookmarks
    // sort-button shape (v15.65, slice 2051, itself the Downloaded
    // v1.47 label + v2.90 premium label dip + v4.22 keyboard hide):
    // the button names the CURRENT order (newSortLabel), a tap
    // morphs the button, hides the search keyboard, advances the
    // mode (recent -> oldest -> sayadaw), persists it, and dips the
    // header row while the label swaps (Fx.themeDip on
    // newHeaderRow, the dlBtnRow shape — the dip runs on the row,
    // never on the tapped button, so its alpha channel never
    // cancels the morph's scale channel; themeDip null-guards, so
    // a missing row still swaps the label). The sort re-runs
    // applyNewSearch() at once (it only reads newSortMode, like the
    // Settings syncLiveService pattern), so the list re-orders in
    // place with no re-read. The mode survives rotation through
    // Prefs, like the Bookmarks sort (v15.65).
    private void wireNewSort() {
        newSortBtn = findViewById(R.id.btnNewSort);
        newSortMode = Prefs.newSortMode(this);
        if (newSortBtn != null) newSortBtn.setText(newSortLabel());
        if (newSortBtn != null) newSortBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.morph(v);
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                newSortMode = (newSortMode + 1) % 3;
                Prefs.setNewSortMode(NewTalksActivity.this, newSortMode);
                Fx.themeDip(findViewById(R.id.newHeaderRow), new Runnable() { @Override public void run() { if (newSortBtn != null) newSortBtn.setText(newSortLabel()); } });
                applyNewSearch();
            }
        });
    }

    // v15.67 (slice 2061): wire the jump-to-playing button — one
    // tap glides to the sounding talk (the chooser's v4.15 title
    // long-press glide, in a visible button — this screen has no
    // long-press jump at all). The tap re-resolves everything
    // fresh — live guards, the shown-list index — so a track change
    // while the button sat visible can never glide to a stale row;
    // a filtered-out target clears the search first (the v12.54
    // rule, in this screen's setText("") clear shape — the watcher
    // re-runs applyNewSearch at once, the v1.51 rule), a shown
    // target keeps the view. Any guard failure toasts
    // no_talk_playing (the v4.15 word), never scrolls nowhere. A
    // missing button simply never wires.
    private void wireNewJumpBtn() {
        newPlayingBtn = findViewById(R.id.newPlayingBtn);
        if (newPlayingBtn != null) newPlayingBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v10.43: bound-trackless stop — liveSession stays true but the mirror is false, so toast instead of gliding to a stopped talk; the v11.15 gate: an ended talk is not playing either (its mark lifts, so there is no row to jump to).
                if (listView == null || items.isEmpty() || !AudioService.liveSession || !AudioService.liveHasTrack || AudioService.liveEnded) {
                    android.widget.Toast.makeText(NewTalksActivity.this, Lang.t(NewTalksActivity.this, "no_talk_playing"), android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    refreshNewTalksPlayingMarks();
                    int target = NewJumpPlaying.findShownIndex(items, newPlayingUrl, newPlayingLibUrl, newPlayingLibPath, AudioService.liveEnded);
                    // v12.54: a shown target glides with the view untouched; only a target filtered OUT of the shown list clears the search first, so the shown index means something again.
                    if (target < 0 && newQuery != null && newQuery.length() > 0 && newSearchField != null) {
                        newSearchField.setText("");
                        refreshNewTalksPlayingMarks();
                        target = NewJumpPlaying.findShownIndex(items, newPlayingUrl, newPlayingLibUrl, newPlayingLibPath, AudioService.liveEnded);
                    }
                    if (target < 0) {
                        android.widget.Toast.makeText(NewTalksActivity.this, Lang.t(NewTalksActivity.this, "no_talk_playing"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Fx.tap(NewTalksActivity.this);
                    Fx.morph(v);
                    listView.smoothScrollToPosition(target);
                } catch (Exception ignored) {}
            }
        });
    }

    // v15.67 (slice 2061): refresh the jump button's cached
    // target — the shown-list index of the sounding talk, from the
    // tick-fresh sounding identities (refreshNewTalksPlayingMarks
    // runs first on every path that calls this: refresh() before
    // applyNewSearch, the strip-tick change branch, the tap's own
    // re-resolution). Runs on search/sort/refresh and on sounding
    // change only, never on scroll; -1 while nothing live sounds
    // among the shown rows.
    private void refreshNewJumpTarget() {
        newJumpTargetIdx = NewJumpPlaying.findShownIndex(items, newPlayingUrl, newPlayingLibUrl, newPlayingLibPath, AudioService.liveEnded);
    }

    // v15.67 (slice 2061): the jump-to-playing state — shown only
    // while a live talk has a cached target AND that row is
    // off-screen (above or below the visible window, the
    // JumpPlaying.wantShown rule, the 1994 shape). The change gate
    // keeps steady scrolls and the search/sort/refresh repaints
    // from re-touching the button; the show/hide melts (Fx.fadeIn/
    // fadeOut, 180ms) instead of snapping, and a cancelled fade
    // never strands it half-transparent (the v2.62 gen guard, the
    // 1940 shape). A stop clears the identities on the next tick,
    // so the button hides within a tick of the session ending.
    private void paintNewJumpBtn(int firstVisible, int lastVisible) {
        if (newPlayingBtn == null) return;
        boolean want = newJumpTargetIdx >= 0
                && JumpPlaying.wantShown(newJumpTargetIdx, firstVisible, lastVisible);
        if (want == newPlayingShown) return;
        newPlayingShown = want;
        if (want) {
            newPlayingBtn.setVisibility(android.view.View.VISIBLE);
            Fx.fadeIn(newPlayingBtn, 180);
        } else {
            Fx.fadeOut(newPlayingBtn, 180);
        }
    }

    // v15.67 (slice 2061): repaint from the list's own position
    // after a scroll-less change — a search/sort/refresh, a
    // rotation restore, or a sounding change can move the playing
    // row off-screen with no scroll event, so read the position
    // after layout and paint from it. Posted, because the first /
    // last visible rows are only valid once the list has laid out
    // (the 1940 shape).
    private void paintNewJumpBtnFromList() {
        if (listView == null) return;
        listView.post(new Runnable() {
            @Override public void run() {
                if (listView == null) return;
                paintNewJumpBtn(listView.getFirstVisiblePosition(), listView.getLastVisiblePosition());
            }
        });
    }

    // v15.66 (slice 2057): the sort button label names the CURRENT
    // order — the Bookmarks bmSortLabel shape (1 method, 3
    // returns): most-recent first, oldest first, sayadaw A-Z. The
    // keys are the 2051 bm_sort_* keys — they name the ORDER, not
    // the screen, so no new Lang keys and no duplicated Burmese.
    private String newSortLabel() {
        if (newSortMode == 1) return Lang.t(NewTalksActivity.this, "bm_sort_oldest");
        if (newSortMode == 2) return Lang.t(NewTalksActivity.this, "bm_sort_sayadaw");
        return Lang.t(NewTalksActivity.this, "bm_sort_recent");
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

    // v15.65 (slice 2047): Play-all visibility — the chooser's
    // refreshTalkPlayAllRow shape (v13.76): GONE until a list lands,
    // fading in/out on change, instant on first paint and on
    // keystroke passes. The gate is NewPlayAll.hasPlayable: at
    // least one shown talk with a usable URL that is not the
    // sounding talk (the screen's three sounding identities, the
    // 2043 mark rule). lastNewPlayAllVisible is the change-only
    // snapshot — steady 1s ticks never touch the button.
    // v15.68 (slice 2066): the Shuffle button rides the same gate
    // and the same snapshot — the two header verbs show and hide
    // together.
    private void syncNewPlayAllBtn(boolean skipFade) {
        if (btnNewPlayAll == null) return;
        boolean show = NewPlayAll.hasPlayable(items, newPlayingUrl, newPlayingLibUrl, newPlayingLibPath);
        if (lastNewPlayAllVisible != null && lastNewPlayAllVisible.booleanValue() == show) return;
        lastNewPlayAllVisible = Boolean.valueOf(show);
        if (skipFade) {
            btnNewPlayAll.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
            btnNewPlayAll.setAlpha(1f);
        } else if (show) {
            if (btnNewPlayAll.getVisibility() != android.view.View.VISIBLE) {
                btnNewPlayAll.setVisibility(android.view.View.VISIBLE);
                Fx.fadeIn(btnNewPlayAll, 250);
            } else if (btnNewPlayAll.getAlpha() < 0.999f) {
                Fx.fadeIn(btnNewPlayAll, 250);
            }
        } else if (btnNewPlayAll.getVisibility() == android.view.View.VISIBLE) {
            Fx.fadeOut(btnNewPlayAll, 200);
        }
        // v15.68 (slice 2066): the Shuffle button rides the same
        // gate and the same change-only snapshot, so the two header
        // verbs show and hide together; a null button simply never
        // paints (both buttons live in the same layout, so they
        // exist or miss together — this guard is belt and braces).
        if (btnNewShuffle != null) {
            if (skipFade) {
                btnNewShuffle.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
                btnNewShuffle.setAlpha(1f);
            } else if (show) {
                if (btnNewShuffle.getVisibility() != android.view.View.VISIBLE) {
                    btnNewShuffle.setVisibility(android.view.View.VISIBLE);
                    Fx.fadeIn(btnNewShuffle, 250);
                } else if (btnNewShuffle.getAlpha() < 0.999f) {
                    Fx.fadeIn(btnNewShuffle, 250);
                }
            } else if (btnNewShuffle.getVisibility() == android.view.View.VISIBLE) {
                Fx.fadeOut(btnNewShuffle, 200);
            }
        }
    }

    // v15.65 (slice 2047): "Play all new" — the New talks mirror of
    // the chooser's playAllShown (v13.76), History's histPlayFromHere
    // (v15.62, slice 2030) and Favourites' playAllFav (v15.64, slice
    // 2044). The new talks used to play one row tap at a time; one
    // tap here queues the whole SHOWN run in screen order, across
    // sayadaws (every line carries its own sayadaw, the
    // HistPlayFromHere v2 shape), and opens the Room on the first
    // shown talk at 0:00 (playNew's own route — the 1921 gate only
    // ever picks talks nobody started, so there is no saved spot).
    // Same guards as the chooser: empty list refuses with the same
    // words, the 700ms debounce has its own clock (an invalid tap
    // never consumes the window, v12.28), the sounding talk is never
    // re-queued (the stay-put branch: the rest queue behind it, no
    // restart), already-queued talks stay where they are, and the
    // count toast counts queued lines only. The anchor sayadaw is
    // healed exactly like playNew (catalog + the sayadawForTalk
    // heal) — the queue key and the Room name must be the healed
    // name, never a stale banked one. No catalog, no run: the queue
    // is untouched. The first card is always a stream URL by
    // NewAll's own construction (cached [title, url] pairs are
    // site URLs, matched by sameTalk — NewTalkRail carries no path
    // handling), so no library leg is needed here. playNew's own
    // 700ms row-tap clock stands: a row tap under 700ms before
    // this tap swallows the Room open while the queue still writes
    // (the same class as playAllFav/playFav, accepted in 2044).
    private void playAllNew(android.view.View v) {
        if (items == null || items.isEmpty()) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long now47 = android.os.SystemClock.uptimeMillis();
        if (now47 - lastNewPlayAllMs < 700) return;
        lastNewPlayAllMs = now47;
        refreshNewTalksPlayingMarks(); // tap-time sounding identities
        final NewTalkRail.Card first47 = items.get(0);
        if (first47 == null || first47.e == null || first47.e.url == null || first47.e.url.length() == 0) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String sd47 = first47.e.sayadaw;
        String[] cat47 = catalogFor(sd47);
        if (cat47 == null || cat47[1] == null || cat47[1].length() == 0) {
            String owner47 = Prefs.sayadawForTalk(this, "lib", first47.e.url, "");
            if (owner47 != null && owner47.length() > 0 && !owner47.equals(sd47)) {
                sd47 = owner47;
                cat47 = catalogFor(sd47);
            }
        }
        if (cat47 == null || cat47[1] == null || cat47[1].length() == 0) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "pl_no_play"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        boolean firstSounds47 = (newPlayingUrl.length() > 0 && SiteHttp.sameTalk(first47.e.url, newPlayingUrl))
                || (newPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(first47.e.url, newPlayingLibUrl))
                || (newPlayingLibPath.length() > 0 && first47.e.url.equals(newPlayingLibPath));
        // The anchor sayadaw's current queue, as identities — the
        // pick skips talks already queued (they stay where they are).
        // Saved lines are carried verbatim (histQueueAdd's discipline),
        // so a foreign line the Room drawer moved mid-queue survives
        // the write.
        java.util.Set<String> queuedIds47 = new java.util.HashSet<String>();
        java.util.ArrayList<String> saved47 = new java.util.ArrayList<String>();
        try {
            String savedRaw47 = Prefs.savedQueue(this, sd47);
            if (savedRaw47 != null && savedRaw47.length() > 0) {
                for (String ln47 : savedRaw47.split("\n")) {
                    if (ln47.length() == 0) continue;
                    saved47.add(ln47);
                    QueueEntry qe47 = QueueEntry.parse(ln47);
                    if (qe47 != null && qe47.url.length() > 0)
                        queuedIds47.add(SiteHttp.identityUrl(qe47.url));
                }
            }
        } catch (Exception ignored) {}
        java.util.List<String> run47 = NewPlayAll.lines(items, queuedIds47, newPlayingUrl, newPlayingLibUrl, newPlayingLibPath);
        int added47 = 0;
        for (String ln47 : run47) {
            QueueEntry qe47 = QueueEntry.parse(ln47);
            if (qe47 == null || qe47.url.length() == 0) continue;
            boolean dup47 = false;
            for (String ex47 : saved47) {
                QueueEntry qx47 = QueueEntry.parse(ex47);
                if (qx47 != null && SiteHttp.sameTalk(qx47.url, qe47.url)) { dup47 = true; break; }
            }
            if (dup47) continue;
            saved47.add(ln47);
            added47++;
        }
        Prefs.saveQueue(this, sd47, saved47);
        android.widget.Toast.makeText(NewTalksActivity.this, added47 > 0
                ? Lang.t(NewTalksActivity.this, "queue_plain_prefix") + added47 + Lang.t(NewTalksActivity.this, "queue_added_suffix")
                : Lang.t(NewTalksActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
        Fx.morph(v);
        if (!firstSounds47) playNew(first47, v);
    }

    // v15.68 (slice 2066): "Shuffle play" — the New talks mirror
    // of the chooser's shufflePlayShown (v13.93). The new talks
    // used to play in catalogue order only (Play-all-new, v15.65);
    // one tap here shuffles the SHOWN run into the queue in random
    // order (NewShuffle.shuffleCards over the filtered + sorted
    // list — the 2047 rule: the shown run is the run) and opens
    // the Room on the first shuffled talk at 0:00 (playNew's own
    // route — the 1921 gate only ever picks talks nobody started,
    // so there is no saved spot). Same guards as the chooser:
    // empty list refuses with the same words, the 700ms debounce
    // has its own clock (an invalid tap never consumes the window,
    // v12.28), the sounding talk is never re-queued (the stay-put
    // branch: the rest queue behind it, no restart — this screen's
    // THREE sounding identities, the 2043 mark rule), already-queued
    // talks stay where they are, and the count toast counts queued
    // lines only. The anchor sayadaw is the FIRST SHUFFLED card's
    // sayadaw, healed exactly like playNew (catalog + the
    // sayadawForTalk heal). No catalog, no run: the queue is
    // untouched. The first card is always a stream URL by NewAll's
    // own construction, so no library leg is needed here. The
    // shuffled queue IS the shuffle — the global shuffle-continuous
    // toggle is left alone, so a user who deliberately left it off
    // is not surprised, and after the queue drains normal advance
    // resumes. playNew's own 700ms row-tap clock stands: a row tap
    // under 700ms before this tap swallows the Room open while the
    // queue still writes (the same class as playAllNew, accepted
    // in 2047).
    private void shufflePlayNew(android.view.View v) {
        if (items == null || items.isEmpty()) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long now66 = android.os.SystemClock.uptimeMillis();
        if (now66 - lastNewShuffleMs < 700) return;
        lastNewShuffleMs = now66;
        refreshNewTalksPlayingMarks(); // tap-time sounding identities
        java.util.List<NewTalkRail.Card> shuffled66 = NewShuffle.shuffleCards(items, new java.util.Random());
        if (shuffled66.isEmpty()) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final NewTalkRail.Card first66 = shuffled66.get(0);
        if (first66 == null || first66.e == null || first66.e.url == null || first66.e.url.length() == 0) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String sd66 = first66.e.sayadaw;
        String[] cat66 = catalogFor(sd66);
        if (cat66 == null || cat66[1] == null || cat66[1].length() == 0) {
            String owner66 = Prefs.sayadawForTalk(this, "lib", first66.e.url, "");
            if (owner66 != null && owner66.length() > 0 && !owner66.equals(sd66)) {
                sd66 = owner66;
                cat66 = catalogFor(sd66);
            }
        }
        if (cat66 == null || cat66[1] == null || cat66[1].length() == 0) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "pl_no_play"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        boolean firstSounds66 = (newPlayingUrl.length() > 0 && SiteHttp.sameTalk(first66.e.url, newPlayingUrl))
                || (newPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(first66.e.url, newPlayingLibUrl))
                || (newPlayingLibPath.length() > 0 && first66.e.url.equals(newPlayingLibPath));
        // The anchor sayadaw's current queue, as identities — the
        // pick skips talks already queued (they stay where they are).
        // Saved lines are carried verbatim (histQueueAdd's discipline),
        // so a foreign line the Room drawer moved mid-queue survives
        // the write.
        java.util.Set<String> queuedIds66 = new java.util.HashSet<String>();
        java.util.ArrayList<String> saved66 = new java.util.ArrayList<String>();
        try {
            String savedRaw66 = Prefs.savedQueue(this, sd66);
            if (savedRaw66 != null && savedRaw66.length() > 0) {
                for (String ln66 : savedRaw66.split("\n")) {
                    if (ln66.length() == 0) continue;
                    saved66.add(ln66);
                    QueueEntry qe66 = QueueEntry.parse(ln66);
                    if (qe66 != null && qe66.url.length() > 0)
                        queuedIds66.add(SiteHttp.identityUrl(qe66.url));
                }
            }
        } catch (Exception ignored) {}
        // The shuffled order flows through NewPlayAll.lines (it keeps
        // its input order): the run is the shuffled run, deduped,
        // sounding-skipped and already-queued-skipped exactly like
        // Play-all-new's.
        java.util.List<String> run66 = NewPlayAll.lines(shuffled66, queuedIds66, newPlayingUrl, newPlayingLibUrl, newPlayingLibPath);
        int added66 = 0;
        for (String ln66 : run66) {
            QueueEntry qe66 = QueueEntry.parse(ln66);
            if (qe66 == null || qe66.url.length() == 0) continue;
            boolean dup66 = false;
            for (String ex66 : saved66) {
                QueueEntry qx66 = QueueEntry.parse(ex66);
                if (qx66 != null && SiteHttp.sameTalk(qx66.url, qe66.url)) { dup66 = true; break; }
            }
            if (dup66) continue;
            saved66.add(ln66);
            added66++;
        }
        Prefs.saveQueue(this, sd66, saved66);
        android.widget.Toast.makeText(NewTalksActivity.this, added66 > 0
                ? Lang.t(NewTalksActivity.this, "queue_plain_prefix") + added66 + Lang.t(NewTalksActivity.this, "queue_added_suffix")
                : Lang.t(NewTalksActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
        Fx.morph(v);
        if (!firstSounds66) playNew(first66, v);
    }

    // v15.69 (slice 2071): the live favourite set, for the row
    // menu's star verb (FavouritesActivity.readFavTalks verbatim —
    // the "dhamma" prefs, the "favTalks" string set, copied before
    // use). The menu reads it at open time and the toggle handler
    // re-reads it at tap time, so a dialog left open across a star
    // flip elsewhere can never show or act on a stale state.
    private java.util.Set<String> readFavTalks() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        try {
            java.util.Set<String> raw = getSharedPreferences("dhamma", MODE_PRIVATE).getStringSet("favTalks", null);
            if (raw != null) out.addAll(raw);
        } catch (Exception ignored) {}
        return out;
    }

    // v15.69 (slice 2071): row long-press menu on this screen — the
    // New talks screen had no row menu at all (no long-press, no
    // dialog, no per-row star verb): stars were added/removed
    // per-row from the rail/chooser/library menus only (the 2035
    // header note), and nothing started a run mid-list (Play-all
    // starts at the top, a tap plays one talk at 0:00). The menu
    // opens with the two verbs this screen needs: "Play from here"
    // (the chooser's 1942 / History's 2030 / Favourites' 2069 verb
    // — queue the shown run from this row onward and open the Room
    // on it at 0:00) and the state-dependent star verb ("Add to
    // favourites" when unstarred, "Remove from favourites" when
    // starred — the chooser 1969 / library / History 1267 shape).
    // Share / go-to-sayadaw / queue / download are future verbs on
    // this same menu (the History menu grew the same way: 1981,
    // 1982, 1995, 2030). The offer comes from the pure NewRowMenu
    // core (the NewPlayAll three-identity sounding gate, path-form
    // rows offer the star verb only); the dialog is History's
    // AlertDialog shape (title = the card's title, key-dispatched
    // handler); every verb re-checks its offer at tap time, so a
    // dialog left open across a refresh can never act on a stale
    // row.
    private void showNewRowMenu(final NewTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        refreshNewTalksPlayingMarks();
        final String url = c.e.url;
        final String sd = c.e.sayadaw;
        boolean starred = false;
        try {
            for (String f : readFavTalks()) {
                if (f != null && SiteHttp.sameTalk(f, url)) { starred = true; break; }
            }
        } catch (Exception ignored) {}
        java.util.List<String> keys71 = NewRowMenu.menuKeys(url, sd, newPlayingUrl, newPlayingLibUrl, newPlayingLibPath, starred);
        // v15.69 (slice 2074): the queue verb — "Add to queue" /
        // "Remove from queue" (NewQueue): the row's sayadaw queue,
        // read once per menu open; the key composes into the menu
        // between Play-from-here and the star verb, so the play
        // verbs sit together and the star stays last. A stale
        // banked sayadaw can only mis-offer (add for a talk
        // already queued under another key) — the handler re-reads
        // the same key and refuses with already_in_queue, never a
        // wrong write.
        String qKey74 = "";
        try {
            java.util.ArrayList<String> saved74 = new java.util.ArrayList<String>();
            String savedRaw74 = Prefs.savedQueue(this, sd);
            if (savedRaw74 != null && savedRaw74.length() > 0) {
                for (String ln74 : savedRaw74.split("\n")) {
                    if (ln74.length() == 0) continue;
                    saved74.add(ln74);
                }
            }
            qKey74 = NewQueue.queueKey(url, sd, newPlayingUrl, newPlayingLibUrl,
                    newPlayingLibPath, NewQueue.isQueued(url, saved74));
        } catch (Exception ignored) {}
        if (qKey74.length() > 0) {
            int at74 = keys71.contains("menu_play_from_here") ? 1 : 0;
            keys71.add(at74, qKey74);
        }
        if (keys71.isEmpty()) return;
        java.util.ArrayList<String> opts = new java.util.ArrayList<String>();
        for (String k71 : keys71) opts.add(Lang.t(this, k71));
        final String[] optArr = opts.toArray(new String[0]);
        final String[] keyArr = keys71.toArray(new String[0]);
        new android.app.AlertDialog.Builder(this)
                .setTitle(c.e.title != null && c.e.title.length() > 0 ? c.e.title : url)
                .setItems(optArr, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if ("menu_play_from_here".equals(keyArr[which])) {
                            playFromHereNew(c);
                        } else if ("menu_queue_add".equals(keyArr[which])) {
                            queueAddNew(c);
                        } else if ("menu_queue_remove".equals(keyArr[which])) {
                            queueRemoveNew(c);
                        } else if ("menu_fav_add".equals(keyArr[which]) || "menu_fav_remove".equals(keyArr[which])) {
                            toggleFavFromMenu(c);
                        }
                    }
                })
                .show();
    }

    // v15.69 (slice 2071): "Play from here" on this screen — the
    // row menu's first play verb (the chooser's menu_play_from_here,
    // v15.45 slice 1942, History's histPlayFromHere, v15.62 slice
    // 2030, and Favourites' playFromHereFav, v15.68 slice 2069):
    // queue the SHOWN run from the long-pressed row onward (the
    // 2047 rule — the filtered + sorted list is the run) and open
    // the Room on it at 0:00, in one tap. The run is built with
    // the chooser's PlayFromHere.pick (anchor onward, minus the
    // sounding talk and minus already-queued talks) and History's
    // HistPlayFromHere.lines (v2 lines carrying each talk's OWN
    // sayadaw, so the Room renders the other sayadaws' talks as
    // foreign rows). The anchor sayadaw is healed exactly like
    // the row tap (catalog + the sayadawForTalk heal) — the queue
    // key and the Room name must be the healed name, never a stale
    // banked one. No catalog, no run: the queue is untouched. The
    // Room opens with the row tap's own intent, built inline —
    // NOT through the row-tap method, which carries its own 700ms
    // tap debounce, and the menu tap is not a row tap, so routing
    // through it could swallow the open (histPlayFromHere builds
    // its intent inline for the same reason). A queued anchor plays
    // from its queue spot; a sounding anchor never re-opens
    // (stay-put — all three sounding identities, the 2043 mark
    // rule).
    private void playFromHereNew(final NewTalkRail.Card c) {
        if (items == null || items.isEmpty() || c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long now71 = android.os.SystemClock.uptimeMillis();
        if (now71 - lastNewPlayFromHereMs < 700) return;
        lastNewPlayFromHereMs = now71;
        // Tap-time re-checks (the dialog's own shape): the anchor
        // may have lost its stream URL or its sayadaw while the
        // dialog sat open, turned into the sounding talk, or left
        // the list (played from another screen — the 1921 gate
        // drops it from the pick).
        refreshNewTalksPlayingMarks();
        final String streamUrl = c.e.url;
        if (streamUrl.startsWith("/")) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if ((newPlayingUrl.length() > 0 && SiteHttp.sameTalk(streamUrl, newPlayingUrl))
                || (newPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(streamUrl, newPlayingLibUrl))
                || (newPlayingLibPath.length() > 0 && streamUrl.equals(newPlayingLibPath))) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String sd71 = c.e.sayadaw;
        String[] cat71 = catalogFor(sd71);
        if (cat71 == null || cat71[1] == null || cat71[1].length() == 0) {
            String owner71 = Prefs.sayadawForTalk(this, "lib", streamUrl, "");
            if (owner71 != null && owner71.length() > 0 && !owner71.equals(sd71)) {
                sd71 = owner71;
                cat71 = catalogFor(sd71);
            }
        }
        if (cat71 == null || cat71[1] == null || cat71[1].length() == 0) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "pl_no_play"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // The anchor's display position, re-resolved by identity —
        // the dialog can sit open across a search/sort/refresh, so
        // the captured position may have moved (the v13.13 rule).
        int anchorIdx71 = -1;
        for (int i71 = 0; i71 < items.size(); i71++) {
            NewTalkRail.Card r71 = items.get(i71);
            if (r71 == null || r71.e == null || r71.e.url == null) continue;
            if (SiteHttp.sameTalk(r71.e.url, streamUrl)) { anchorIdx71 = i71; break; }
        }
        // Display-order identity list from the anchor onward (the
        // PlayFromHere pick's input), each row carrying its own
        // sayadaw + title for the v2 lines. A path-form row reads as
        // null and the pick skips it (a card is a stream URL by
        // NewAll's construction, but the gate costs nothing).
        java.util.List<String> ids71 = new java.util.ArrayList<String>();
        java.util.List<String> sds71 = new java.util.ArrayList<String>();
        java.util.List<String> titles71 = new java.util.ArrayList<String>();
        if (anchorIdx71 < 0) {
            // Anchor gone from the list while the dialog sat open:
            // queue just the anchor itself, never a stale range.
            ids71.add(SiteHttp.identityUrl(streamUrl));
            sds71.add(sd71);
            titles71.add(c.e.title);
        } else {
            for (int i71 = anchorIdx71; i71 < items.size(); i71++) {
                NewTalkRail.Card r71 = items.get(i71);
                if (r71 == null || r71.e == null) { ids71.add(null); sds71.add(""); titles71.add(""); continue; }
                String u71 = r71.e.url;
                ids71.add(u71 != null && u71.length() > 0 && !u71.startsWith("/") ? SiteHttp.identityUrl(u71) : null);
                sds71.add(r71.e.sayadaw != null ? r71.e.sayadaw : "");
                titles71.add(r71.e.title != null ? r71.e.title : "");
            }
            // The anchor's own line carries the healed sayadaw.
            sds71.set(0, sd71);
        }
        // The anchor sayadaw's current queue, as identities — the
        // pick skips talks already queued (they stay where they
        // are). Saved lines are carried verbatim (histQueueAdd's
        // discipline), so a foreign line the Room drawer moved
        // mid-queue survives the write.
        java.util.Set<String> queuedIds71 = new java.util.HashSet<String>();
        java.util.ArrayList<String> saved71 = new java.util.ArrayList<String>();
        try {
            String savedRaw71 = Prefs.savedQueue(this, sd71);
            if (savedRaw71 != null && savedRaw71.length() > 0) {
                for (String ln71 : savedRaw71.split("\n")) {
                    if (ln71.length() == 0) continue;
                    saved71.add(ln71);
                    QueueEntry qe71 = QueueEntry.parse(ln71);
                    if (qe71 != null && qe71.url.length() > 0)
                        queuedIds71.add(SiteHttp.identityUrl(qe71.url));
                }
            }
        } catch (Exception ignored) {}
        java.util.List<Integer> picked71 = PlayFromHere.pick(ids71,
                SiteHttp.identityUrl(streamUrl), queuedIds71,
                newPlayingUrl, newPlayingLibUrl);
        java.util.List<String> run71 = HistPlayFromHere.lines(ids71, sds71, titles71, picked71);
        int added71 = 0;
        for (String ln71 : run71) {
            QueueEntry qe71 = QueueEntry.parse(ln71);
            if (qe71 == null || qe71.url.length() == 0) continue;
            boolean dup71 = false;
            for (String ex71 : saved71) {
                QueueEntry qx71 = QueueEntry.parse(ex71);
                if (qx71 != null && SiteHttp.sameTalk(qx71.url, qe71.url)) { dup71 = true; break; }
            }
            if (dup71) continue;
            saved71.add(ln71);
            added71++;
        }
        Prefs.saveQueue(this, sd71, saved71);
        android.widget.Toast.makeText(NewTalksActivity.this, added71 > 0
                ? Lang.t(NewTalksActivity.this, "queue_plain_prefix") + added71 + Lang.t(NewTalksActivity.this, "queue_added_suffix")
                : Lang.t(NewTalksActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
        // Open the Room on the anchor at 0:00 (the row tap's own
        // intent, built inline — see the header note): a queued
        // anchor plays from its queue spot.
        android.content.Intent it71 = new android.content.Intent(this, RoomActivity.class);
        it71.putExtra("name", sd71);
        it71.putExtra("group", cat71[0]);
        it71.putExtra("url", cat71[1]);
        it71.putExtra("photo", cat71[2]);
        it71.putExtra("autoUrl", c.e.url);
        it71.putExtra("autoTitle", c.e.title);
        Fx.tap(this);
        Fx.go(this, it71);
    }

    // v15.69 (slice 2071): the row menu's star verb — "Add to
    // favourites" when the talk is unstarred, "Remove from
    // favourites" when it is starred (the chooser 1969 / library /
    // History 1267 shape: the verb names the CURRENT state at
    // menu-open time, and the handler re-checks the live star at
    // tap time). Fx.toggleFavTalk returns false both when the star
    // is removed AND when the write throws, so the outcome is
    // confirmed by re-reading the set after the flip, never by
    // trusting the return alone (the 2069 lesson). The toast
    // names the confirmed outcome (fav_added / fav_removed), or
    // nothing_to_delete when the flip did not take. The row stays
    // in this list either way — starring never moves a new talk,
    // and the list renders no star — so no refresh() here. A
    // flipped sounding talk keeps playing — the star is not
    // playback — but the Room's card star flips, so the
    // notification card is refreshed (the Talks v10.03 shape,
    // through this screen's own three sounding identities).
    // v15.69 (slice 2074): "Add to queue" — the row menu's queue
    // verb (History's menu_queue_add, v13.77, on this screen's
    // menu): append the row's talk to the row's sayadaw queue as a
    // v2 line (NewQueue.add — identity URL, the row's sayadaw, the
    // row's title, the histQueueAdd shape) and leave it there for
    // the next Room session on that sayadaw — the sounding talk
    // keeps playing, nothing opens. The offer hid sounding and
    // path-form rows (NewQueue.queueKey, the NewPlayAll
    // three-identity rule); the tap-time re-checks refuse with
    // queue_changed when the row turned sounding or lost its
    // stream URL while the dialog sat open (the 2071 shape), and
    // the dup check answers already_in_queue. No catalog heal: the
    // queue key is the row's sayadaw as listed (histQueueAdd's
    // discipline — no Room opens here, so no healed-name coupling
    // like playFromHereNew's). No list refresh: queueing changes
    // no row the list renders (there is no queued mark on this
    // screen — the 2043 mark renders the sounding talk only, and
    // a new talk leaves the list the moment it is played, the
    // 1921 gate).
    private void queueAddNew(final NewTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        refreshNewTalksPlayingMarks();
        final String url = c.e.url;
        final String sd = c.e.sayadaw;
        if (url.startsWith("/") || sd == null || sd.length() == 0
                || NewPlayAll.isSounding(url, newPlayingUrl, newPlayingLibUrl,
                        newPlayingLibPath)) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.ArrayList<String> saved74 = new java.util.ArrayList<String>();
        try {
            String savedRaw74 = Prefs.savedQueue(this, sd);
            if (savedRaw74 != null && savedRaw74.length() > 0) {
                for (String ln74 : savedRaw74.split("\n")) {
                    if (ln74.length() == 0) continue;
                    saved74.add(ln74);
                }
            }
        } catch (Exception ignored) {}
        java.util.ArrayList<String> next74 = NewQueue.add(url, sd, c.e.title, saved74);
        if (next74 == null) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "already_in_queue"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.saveQueue(this, sd, next74);
        android.widget.Toast.makeText(NewTalksActivity.this,
                Lang.t(NewTalksActivity.this, "added_to_queue"),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // v15.69 (slice 2074): "Remove from queue" — the
    // state-dependent twin of queueAddNew above (History's
    // menu_queue_remove, v13.77): drop the row's talk from the
    // row's sayadaw queue (NewQueue.remove — sameTalk over parsed
    // entries; unparseable lines are carried, never dropped on a
    // guess) and leave the star alone — queueing is not
    // favouriting, so removing from the queue never un-stars the
    // row. Same tap-time re-checks as the add (sounding /
    // path-form / blank sayadaw refuse with queue_changed); a talk
    // that left the queue while the dialog sat open answers
    // queue_changed too (NewQueue.remove returns null when absent).
    // No list refresh: the queue change renders nowhere on this
    // screen.
    private void queueRemoveNew(final NewTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        refreshNewTalksPlayingMarks();
        final String url = c.e.url;
        final String sd = c.e.sayadaw;
        if (url.startsWith("/") || sd == null || sd.length() == 0
                || NewPlayAll.isSounding(url, newPlayingUrl, newPlayingLibUrl,
                        newPlayingLibPath)) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.ArrayList<String> saved74 = new java.util.ArrayList<String>();
        try {
            String savedRaw74 = Prefs.savedQueue(this, sd);
            if (savedRaw74 != null && savedRaw74.length() > 0) {
                for (String ln74 : savedRaw74.split("\n")) {
                    if (ln74.length() == 0) continue;
                    saved74.add(ln74);
                }
            }
        } catch (Exception ignored) {}
        java.util.ArrayList<String> next74 = NewQueue.remove(url, saved74);
        if (next74 == null) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.saveQueue(this, sd, next74);
        android.widget.Toast.makeText(NewTalksActivity.this,
                Lang.t(NewTalksActivity.this, "removed_from_queue"),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void toggleFavFromMenu(final NewTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        refreshNewTalksPlayingMarks();
        boolean pre71 = false;
        try {
            for (String f : readFavTalks()) {
                if (f != null && SiteHttp.sameTalk(f, c.e.url)) { pre71 = true; break; }
            }
        } catch (Exception ignored) {}
        Fx.toggleFavTalk(this, c.e.url);
        boolean post71 = false;
        try {
            for (String f : readFavTalks()) {
                if (f != null && SiteHttp.sameTalk(f, c.e.url)) { post71 = true; break; }
            }
        } catch (Exception ignored) {}
        boolean changed71 = pre71 ? !post71 : post71;
        if (changed71) {
            boolean cardTalk = (newPlayingUrl.length() > 0 && SiteHttp.sameTalk(c.e.url, newPlayingUrl))
                    || (newPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(c.e.url, newPlayingLibUrl))
                    || (newPlayingLibPath.length() > 0 && c.e.url.equals(newPlayingLibPath));
            if (cardTalk) {
                android.content.Intent rsv = new android.content.Intent(this, AudioService.class);
                rsv.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                startService(rsv);
            }
        }
        android.widget.Toast.makeText(this,
                Lang.t(this, changed71 ? (pre71 ? "fav_removed" : "fav_added") : "nothing_to_delete"),
                android.widget.Toast.LENGTH_SHORT).show();
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
            // v15.67 (slice 2061): the sounding change above moves the playing talk (advance, stop, or a new session while browsing) — refresh the jump button's cached target and repaint it. Change-only: steady ticks never touch the button.
            refreshNewJumpTarget();
            paintNewJumpBtnFromList();
        }
        syncNewPlayAllBtn(false); // v15.65 (slice 2047): the sounding talk moved under the list — re-show or hide Play-all with the mark, change-only
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
