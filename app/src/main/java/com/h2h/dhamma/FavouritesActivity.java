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

// v15.63 (slice 2035): "See all" on the Main "Favourite talks" rail —
// the full list of every starred talk, across all sayadaws. The rail
// shows 5 cards (FavTalkRail.MAX_CARDS) while the listener's stars
// can run to dozens; no screen anywhere showed all starred talks
// together (the Downloaded favourites filter is a filter, not a
// list). Rows are the rail card's own shape (44dp portrait +
// sayadaw name + the rail's saved-spot title "↺ 12:34 · title",
// bare title when never started + the History "›" chevron); a tap
// lands the Room at the saved spot through the openRecentTalk1930
// shape (the Room intent, NO talkPosPutBoth — a star is not a
// spot, the 1964 rule — and NO stream resolution: the rail's own
// tap passes e.url as-is, so whatever the rail does for a
// path-form row, the list does). Mini-player strip since v15.63 (slice
// 2038, the History v13.54 precedent); no clear-all (a
// bulk destructive verb, the 2016-rejected class — stars are
// removed per-row from the rail/chooser/library menus), no
// downloaded badge and no hairline (the badge + hairline families
// are complete on the rails; one dlMap parse per row on an
// unbounded list would break the v5.69 cost rule, the 2033
// precedent).
public class FavouritesActivity extends Activity {
    private TextView headerTitle;
    private ListView listView;
    private TextView emptyText;
    private java.util.List<FavTalkRail.Card> items =
            new java.util.ArrayList<FavTalkRail.Card>();
    // v15.65 (slice 2048): the search keeps the unfiltered pick
    // beside the shown list — a keystroke re-filters in memory
    // (the Downloaded applyDlFilter shape), never re-reading the
    // cache files per keystroke.
    private java.util.List<FavTalkRail.Card> favAllItems =
            new java.util.ArrayList<FavTalkRail.Card>();
    private BaseAdapter adapter;
    private boolean night;
    private java.util.Map<String, String[]> catCache;
    // v15.63 (slice 2035): row-tap debounce — its own clock (the
    // chooser's 700ms rule, v12.28: an invalid tap never consumes
    // the window). A rapid double-tap must never stack two Rooms.
    private long lastFavTapMs = 0;
    // v15.63 (slice 2038): real mini-player on this screen (the
    // History v13.54 precedent) — the favourites screen used to
    // show nothing about a playing starred talk: tapping a row
    // opened the Room and Back returned here with no way to see,
    // pause or jump back to the talk. Same 1s-tick shape as
    // History: stripHandler + stripTick, favResumeStripMode (the
    // resume branch), and favStripPhotoName (the face the strip
    // last showed).
    private android.os.Handler stripHandler = new android.os.Handler();
    private Runnable stripTick;
    private boolean favResumeStripMode = false;
    private String favStripPhotoName = null;
    private boolean lastFavStripPhotoRing = false;
    // v15.64 (slice 2042): playing-row mark (History v13.55, Talks
    // v4.89/v11.07, same rules) — the Favourites rows carried no live
    // marks: a listener browsing starred talks could not spot the
    // talk the strip is naming without remembering its title. The
    // sounding identities below mirror History's
    // refreshHistoryPlayingMarks playing half: favPlayingUrl for a
    // Room session (lastUrl), favPlayingLibUrl for a library session
    // via the dlMap reverse lookup (exactly-one claimant, v8.44),
    // and favPlayingLibPath for a library session matched directly
    // — a path-form row IS the file (playRecent v12.82 writes the
    // same path), so no lookup is needed there. favRowPlayingUrl /
    // favRowPlayingLibUrl / favRowPlayingLibPath / favRowEnded are the
    // last bound snapshots; the 1s tick rebinds once when any of
    // them changes.
    private String favPlayingUrl = "";
    private String favPlayingLibUrl = "";
    private String favPlayingLibPath = "";
    private String favRowPlayingUrl = null;
    private String favRowPlayingLibUrl = null;
    private String favRowPlayingLibPath = null;
    private boolean favRowEnded = false;
    // v15.85 (slice 2171): the listened-tick snapshot for the rows — favPlayedSet is the playedTalks set copied once per refresh (never read per row per bind, the v1.59 rule); refreshed on refresh() and on the strip tick's change detector, so a tick flipped from the notification card or the mark verb rebinds the rows here.
    private java.util.Set<String> favPlayedSet = new java.util.HashSet<String>();
    // v15.89 (slice 2195): the downloaded-badge snapshot for the rows — the identity forms of the unfiltered pick's stream addresses that are already on the phone, built once per refresh from the unfiltered pick, refreshed on refresh and on the one-second tick's change detector, so a download landing while the list sits open rebinds the rows here.
    private java.util.Set<String> favDlSet95 = new java.util.HashSet<String>();
    // v15.90 (slice 2199): the total-duration snapshot for the rows — favDurCache is the per-talk total durations (Prefs.talkDurMap, one parse per refresh, never talkDurGet per row per bind, the v13.97 rule); refreshed on refresh() and on the strip tick's change detector, so a length landing while the list sits open rebinds the rows here.
    private java.util.Map<String, Integer> favDurCache = new java.util.HashMap<String, Integer>();
    // v15.64 (slice 2044): "Play all" on this screen (the chooser's
    // v13.76 Play All + the library's v15.50 play-listened, the third
    // list that deserves the verb) — the starred talks used to play
    // one row tap at a time; one tap here queues the whole starred
    // run across sayadaws (FavPlayAll v2 lines) and opens the Room
    // on the first starred talk at its saved spot. lastFavPlayAllMs
    // is the handler's own 700ms clock (the chooser's v12.28 rule);
    // btnFavPlayAll is the header button (GONE until a list lands,
    // the chooser's refreshTalkPlayAllRow shape); lastFavPlayAllVisible
    // is the change-only snapshot — the 1s tick re-shows or hides the
    // button when the sounding talk moves under the list, steady ticks
    // never touch it.
    private long lastFavPlayAllMs = 0;
    private android.widget.Button btnFavPlayAll = null;
    private Boolean lastFavPlayAllVisible = null;
    // v15.68 (slice 2065): "Shuffle play" on this screen — the
    // chooser's v13.93 shuffle-play, the first of the three
    // full-list screens to get it: one tap shuffles the shown
    // starred run into the queue and opens the Room on the first
    // shuffled talk. lastFavShuffleMs is the handler's own 700ms
    // clock (the chooser's v12.28 rule — a separate clock from
    // Play-all's, so a Shuffle tap never eats a Play-all tap);
    // btnFavShuffle is the header button beside Play-all (GONE
    // until a list lands, the same gate, the same change-only
    // snapshot — syncFavPlayAllBtn paints both).
    private long lastFavShuffleMs = 0;
    private android.widget.Button btnFavShuffle = null;
    // v15.68 (slice 2069): row long-press menu on this screen — the
    // Favourites screen had search, sort, play-all, shuffle, jump,
    // mark and strip, but no row menu at all: a listener could
    // un-star a talk only by leaving the screen, and nothing started
    // a run mid-list. lastFavPlayFromHereMs is the handler's own
    // 700ms clock (the chooser's v12.28 rule — a separate clock from
    // Play-all's, so a Play-from-here tap never eats a Play-all tap).
    private long lastFavPlayFromHereMs = 0;
    // v15.83 (slice 2160): "Queue from here" debounce — queueFromHereFav's own 700ms clock (the chooser's v12.28 rule), beside lastFavPlayFromHereMs, so a queue-from-here tap never eats a play-from-here tap.
    private long lastFavQueueFromHereMs = 0;
    // v15.65 (slice 2048): search on this screen — the field, the
    // clear ✕, the live query and the keystroke flag (the
    // Downloaded dlSearchField / dlClearBtn / dlQuery /
    // dlSearchKeystroke shape, v1.51 + v15.33). The query is the
    // trimmed field text; the flag is true only while the watcher
    // drives a pass, so the ✕ melts on keystrokes and lands
    // instantly on silent passes (rotation / restore / onResume).
    private android.widget.EditText favSearchField = null;
    private android.view.View favClearBtn = null;
    private String favQuery = "";
    private boolean favSearchKeystroke = false;
    // v15.66 (slice 2056): sort on this screen — the button and the
    // persisted mode (the Bookmarks bmSortMode shape, v15.65 slice
    // 2051). The mode is read from Prefs in wireFavSort(); the
    // button label names the CURRENT order (favSortLabel, the 2051
    // bm_sort_* keys — the keys name the ORDER, not the screen),
    // so the order reads in a still. Rotation recreates the
    // activity and re-reads the persisted mode — no watcher or
    // saved-state needed.
    private android.widget.Button favSortBtn = null;
    private int favSortMode = 0;
    // v15.67 (slice 2060): "Jump to the playing talk" — the
    // floating gold ▶ over the list's bottom-end corner; favPlayingShown is the last-painted state
    // for the painter's change gate, and favJumpTargetIdx is the
    // cached SHOWN-list index of the sounding talk (recomputed on
    // search/sort/refresh and on sounding change, never on scroll
    // — the 1994 pattern with a cache, so a fling never pays a
    // sameTalk scan per scroll event).
    private android.view.View favPlayingBtn = null;
    private boolean favPlayingShown = false;
    private int favJumpTargetIdx = -1;
    // v15.75 (slice 2108): "Back to top" on this screen — the Talks 1940 / Downloaded 2003 button, now on the second full-list screen without one: a starred list grows with every favourite tap, so its header is off-screen exactly when the jump is needed. favTopBtn is the floating gold ↑ button (GONE until ScrollTop.visible — the first visible row passes 8 — the 1940 shape, on the shown list's own scroll, not the sounding row); favTopShown is the last-painted state for the painter's change gate. Back to top landed in slice 2108.
    private android.view.View favTopBtn = null;
    private boolean favTopShown = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_favourites);
        night = Prefs.isNight(this);
        Fx.applySystemBars(this, night); // v13.07: bars follow the theme
        View root = findViewById(R.id.favRoot);
        if (root != null)
            root.setBackgroundResource(night ? R.drawable.bg_gradient_night
                    : R.drawable.bg_gradient_day);
        headerTitle = findViewById(R.id.favTitle);
        listView = findViewById(R.id.favList);
        emptyText = findViewById(R.id.favEmpty);
        if (headerTitle != null) {
            headerTitle.setText(Lang.t(this, "hs_fav_talks"));
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
                    playFav(items.get(pos), v);
                }
            });
            // v15.68 (slice 2069): the row's long-press menu — tap
            // still plays; a long-press opens Play-from-here /
            // Remove-from-favourites (showFavRowMenu). The History
            // v13.77 shape: Fx.tap blip, then the menu, return true.
            listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                    if (pos < 0 || pos >= items.size()) return false;
                    Fx.tap(FavouritesActivity.this);
                    showFavRowMenu(items.get(pos));
                    return true;
                }
            });
            // v15.67 (slice 2060): the jump button paints on the same scroll — off-screen is its only show rule, so a laid-out window is required (count 0 means no layout yet).
            listView.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
                @Override public void onScrollStateChanged(android.widget.AbsListView v, int scrollState) {}
                @Override public void onScroll(android.widget.AbsListView v, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                    // v15.75 (slice 2108): the top button paints on the same scroll (the 2003 shape — its only show rule is the first visible row, so no laid-out-window guard; the threshold keeps it hidden until row 8 anyway).
                    paintFavTopBtn(firstVisibleItem);
                    if (visibleItemCount > 0) paintFavJumpBtn(firstVisibleItem, firstVisibleItem + visibleItemCount - 1);
                }
            });
        }
        wireFavSearch(); // v15.65 (slice 2048): search on this screen, before the first refresh
        wireFavSort(); // v15.66 (slice 2056): sort on this screen, before the first refresh
        wireFavJumpBtn(); // v15.67 (slice 2060): jump-to-playing button on this list
        wireFavTopBtn(); // v15.75 (slice 2108): back to top on this screen, before the first refresh
        refresh();
        // v15.64 (slice 2044): wire the Play-all button the same way
        // as the chooser's Play All (v13.76) — one tap plays the whole
        // starred list in order; the label is the chooser's own
        // pl_play_all key, so no new string. The button stays GONE
        // until refresh() shows it (syncFavPlayAllBtn below).
        btnFavPlayAll = findViewById(R.id.btnFavPlayAll);
        if (btnFavPlayAll != null) btnFavPlayAll.setText(Lang.t(this, "pl_play_all"));
        if (btnFavPlayAll != null) btnFavPlayAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(FavouritesActivity.this);
                playAllFav(v);
            }
        });
        if (btnFavPlayAll != null) Fx.press(btnFavPlayAll);
        // v15.68 (slice 2065): wire the Shuffle button the same
        // way — one tap shuffles the shown run into the queue and
        // starts the first shuffled talk (shufflePlayFav). The
        // label is the pre-existing Lang "shuffle" key, so no new
        // key and no literal here. The button stays GONE until
        // refresh() shows it (syncFavPlayAllBtn below).
        btnFavShuffle = findViewById(R.id.btnFavShuffle);
        if (btnFavShuffle != null) btnFavShuffle.setText(Lang.t(this, "shuffle"));
        if (btnFavShuffle != null) btnFavShuffle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(FavouritesActivity.this);
                shufflePlayFav(v);
            }
        });
        if (btnFavShuffle != null) Fx.press(btnFavShuffle);
        wireFavouritesStrip(); // v15.63 (slice 2038): real mini-player below the list
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        startFavouritesStripTick(); // v15.63 (slice 2038): mini-player live state
    }

    @Override protected void onPause() {
        super.onPause();
        stopFavouritesStripTick(); // v15.63 (slice 2038): never tick a detached strip
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        // v15.63 (slice 2038): detach the liquid-glass surface (History v13.54,
        // Talks v13.08) — the drawable pins the dead view + its destroyed
        // activity otherwise.
        GlassBlur.detach(findViewById(R.id.nowStrip));
    }

    private void refresh() {
        java.util.Set<String> names = catalogNames();
        // v15.65 (slice 2048): the search keeps its own copy of the
        // unfiltered pick — a keystroke re-filters in memory (the
        // Downloaded applyDlFilter shape), never re-reading the
        // cache files per keystroke.
        favAllItems = FavAll.pickAll(readFavTalks(), Prefs.statsRecent(this), names, readTalkPos());
        refreshFavouritesPlayingMarks(); // v15.64 (slice 2042): mark right on first paint, not one tick late
        refreshFavPlayedSet(); // v15.85 (slice 2171): the tick snapshot fresh on first paint, beside the playing marks
        refreshFavDlSet(); // v15.89 (slice 2195): the badge snapshot fresh on first paint, beside the tick snapshot — the rows read the snapshot, never a per-row store test
        refreshFavDurCache(); // v15.90 (slice 2199): the duration snapshot fresh on first paint, beside the badge snapshot
        applyFavSearch();
    }

    // v15.65 (slice 2048): the search pass — the filtered list,
    // the adapter, the clear ✕ and the empty line, from the
    // unfiltered pick. The watcher calls this per keystroke;
    // refresh() calls it after a fresh pick; onResume's refresh()
    // re-applies the live query after a Room return. A
    // filtered-empty list says no_search_match (v8.94) — there ARE
    // starred talks, just none matching — while a blank query with
    // an empty list still says no_fav_talk. The playing-row mark
    // needs nothing here: itemRow re-reads the sounding
    // identities per bind, so a filtered-out sounding talk simply
    // has no row to mark and a re-shown one marks at once.
    // Play-all follows the filtered list (the 2047 rule): the
    // shown run is the run — a search-narrowed list plays the
    // narrowed run, clearing the search restores the full run.
    private void applyFavSearch() {
        // v15.66 (slice 2056): the sort rides the search pass — the
        // shown list is the filtered list in the chosen order, so a
        // keystroke re-sorts in memory and the sort tap re-runs this
        // pass instead of re-reading the pick. The playing-row mark
        // needs nothing here, like the search (2048): itemRow
        // re-reads the sounding identities per bind. The play-all
        // run follows the sorted list (the 2047 rule): the shown run
        // is the run, in the chosen order.
        items = FavSort.sort(FavSearch.filter(favAllItems, favQuery), favSortMode);
        if (adapter != null) adapter.notifyDataSetChanged();
        refreshFavClear(favSearchKeystroke);
        if (emptyText == null) return;
        boolean empty = items.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) emptyText.setText(Lang.t(this,
                favQuery.length() > 0 ? "no_search_match" : "no_fav_talk"));
        syncFavPlayAllBtn(true); // v15.65 (slice 2048): Play-all follows the FILTERED list — the shown run is the run. First paint here (refresh() calls applyFavSearch), and every keystroke re-syncs it with the shown run; instant, never faded, the clear-X silent-pass rule.
        // v15.67 (slice 2060): a search / sort / refresh re-shows the list — the jump button's cached target moves with it (new list, new filter), so refresh the target and repaint from the new position.
        refreshFavJumpTarget();
        paintFavJumpBtnFromList();
        // v15.75 (slice 2108): a search / refresh can land the list deep without a scroll event — repaint the top button from the new position too (the 2003 shape).
        paintFavTopBtnFromList();
    }

    // v15.65 (slice 2048): search on this screen — the Downloaded
    // search shape (v1.51 field + v4.20 Search-key dismiss + v15.33
    // clear ✕): the hint is the Talks chooser's own
    // talks_search_hint (v9.88), the filter is the ONE rule
    // (Fx.titleMatches through FavSearch, title or sayadaw), and a
    // keystroke re-filters the in-memory pick without re-reading
    // the cache files. Rotation restore rides the watcher (the
    // v1.51 rule): the system's restored text fires afterTextChanged
    // after onStart, so the filter re-runs with the restored query
    // before onResume's refresh. The clear ✕ clears through
    // setText("") so the watcher re-runs the filter exactly like a
    // keystroke (the Main v15.33 shape, slice 1836); it is GONE
    // with an empty query, so a tap can only land on a real query.
    private void wireFavSearch() {
        favSearchField = findViewById(R.id.favSearch);
        favClearBtn = findViewById(R.id.btnClearFavSearch);
        if (favSearchField != null)
            favSearchField.setHint(Lang.t(this, "talks_search_hint"));
        if (favSearchField != null)
            favSearchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        if (favSearchField != null) favSearchField.setSingleLine(true);
        if (favSearchField != null) favSearchField.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
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
        if (favClearBtn != null) favClearBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.morph(v);
                if (favSearchField != null) favSearchField.setText("");
            }
        });
        if (favSearchField != null) favSearchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                favQuery = s.toString().trim();
                favSearchKeystroke = true;
                applyFavSearch();
                favSearchKeystroke = false;
            }
        });
    }

    // v15.65 (slice 2048): the search clear ✕ melts with the query
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
    private void refreshFavClear(boolean animate) {
        if (favClearBtn == null) return;
        if (favQuery != null && favQuery.length() > 0) {
            if (favClearBtn.getVisibility() != android.view.View.VISIBLE) {
                favClearBtn.setVisibility(android.view.View.VISIBLE);
                if (animate) Fx.fadeIn(favClearBtn, 250);
                else favClearBtn.setAlpha(1f);
            } else if (animate && favClearBtn.getAlpha() < 0.999f) {
                Fx.fadeIn(favClearBtn, 250);
            } else if (!animate) {
                favClearBtn.animate().cancel();
                favClearBtn.setAlpha(1f);
            }
        } else {
            if (favClearBtn.getVisibility() == android.view.View.VISIBLE) {
                if (animate) Fx.fadeOut(favClearBtn, 200);
                else {
                    favClearBtn.animate().cancel();
                    favClearBtn.setVisibility(android.view.View.GONE);
                    favClearBtn.setAlpha(1f);
                }
            }
        }
    }

    // v15.66 (slice 2056): sort on this screen — the Bookmarks
    // sort-button shape (v15.65, slice 2051, itself the Downloaded
    // v1.47 label + v2.90 premium label dip + v4.22 keyboard hide):
    // the button names the CURRENT order (favSortLabel), a tap
    // morphs the button, hides the search keyboard, advances the
    // mode (recent -> oldest -> sayadaw), persists it, and dips the
    // header row while the label swaps (Fx.themeDip on
    // favHeaderRow, the dlBtnRow shape — the dip runs on the row,
    // never on the tapped button, so its alpha channel never
    // cancels the morph's scale channel; themeDip null-guards, so
    // a missing row still swaps the label). The sort re-runs
    // applyFavSearch() at once (it only reads favSortMode, like the
    // Settings syncLiveService pattern), so the list re-orders in
    // place with no re-read. The mode survives rotation through
    // Prefs, like the Bookmarks sort (v15.65).
    private void wireFavSort() {
        favSortBtn = findViewById(R.id.btnFavSort);
        favSortMode = Prefs.favSortMode(this);
        if (favSortBtn != null) favSortBtn.setText(favSortLabel());
        if (favSortBtn != null) favSortBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.morph(v);
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                favSortMode = (favSortMode + 1) % 3;
                Prefs.setFavSortMode(FavouritesActivity.this, favSortMode);
                Fx.themeDip(findViewById(R.id.favHeaderRow), new Runnable() { @Override public void run() { if (favSortBtn != null) favSortBtn.setText(favSortLabel()); } });
                applyFavSearch();
            }
        });
    }

    // v15.67 (slice 2060): wire the jump-to-playing button — one
    // tap glides to the sounding talk (the chooser's v4.15 title
    // long-press glide, in a visible button — this screen has no
    // long-press jump at all). The tap re-resolves everything
    // fresh — live guards, the shown-list index — so a track change
    // while the button sat visible can never glide to a stale row;
    // a filtered-out target clears the search first (the v12.54
    // rule, in this screen's setText("") clear shape — the watcher
    // re-runs applyFavSearch at once, the v1.51 rule), a shown
    // target keeps the view. Any guard failure toasts
    // no_talk_playing (the v4.15 word), never scrolls nowhere. A
    // missing button simply never wires.
    private void wireFavJumpBtn() {
        favPlayingBtn = findViewById(R.id.favPlayingBtn);
        if (favPlayingBtn != null) favPlayingBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v10.43: bound-trackless stop — liveSession stays true but the mirror is false, so toast instead of gliding to a stopped talk; the v11.15 gate: an ended talk is not playing either (its mark lifts, so there is no row to jump to).
                if (listView == null || items.isEmpty() || !AudioService.liveSession || !AudioService.liveHasTrack || AudioService.liveEnded) {
                    android.widget.Toast.makeText(FavouritesActivity.this, Lang.t(FavouritesActivity.this, "no_talk_playing"), android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    refreshFavouritesPlayingMarks();
                    int target = FavJumpPlaying.findShownIndex(items, favPlayingUrl, favPlayingLibUrl, favPlayingLibPath, AudioService.liveEnded);
                    // v12.54: a shown target glides with the view untouched; only a target filtered OUT of the shown list clears the search first, so the shown index means something again.
                    if (target < 0 && favQuery != null && favQuery.length() > 0 && favSearchField != null) {
                        favSearchField.setText("");
                        refreshFavouritesPlayingMarks();
                        target = FavJumpPlaying.findShownIndex(items, favPlayingUrl, favPlayingLibUrl, favPlayingLibPath, AudioService.liveEnded);
                    }
                    if (target < 0) {
                        android.widget.Toast.makeText(FavouritesActivity.this, Lang.t(FavouritesActivity.this, "no_talk_playing"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Fx.tap(FavouritesActivity.this);
                    Fx.morph(v);
                    listView.smoothScrollToPosition(target);
                } catch (Exception ignored) {}
            }
        });
    }

    // v15.67 (slice 2060): refresh the jump button's cached
    // target — the shown-list index of the sounding talk, from the
    // tick-fresh sounding identities (refreshFavouritesPlayingMarks
    // runs first on every path that calls this: refresh() before
    // applyFavSearch, the strip-tick change branch, the tap's own
    // re-resolution). Runs on search/sort/refresh and on sounding
    // change only, never on scroll; -1 while nothing live sounds
    // among the shown rows.
    private void refreshFavJumpTarget() {
        favJumpTargetIdx = FavJumpPlaying.findShownIndex(items, favPlayingUrl, favPlayingLibUrl, favPlayingLibPath, AudioService.liveEnded);
    }

    // v15.67 (slice 2060): the jump-to-playing state — shown only
    // while a live talk has a cached target AND that row is
    // off-screen (above or below the visible window, the
    // JumpPlaying.wantShown rule, the 1994 shape). The change gate
    // keeps steady scrolls and the search/sort/refresh repaints
    // from re-touching the button; the show/hide melts (Fx.fadeIn/
    // fadeOut, 180ms) instead of snapping, and a cancelled fade
    // never strands it half-transparent (the v2.62 gen guard, the
    // 1940 shape). A stop clears the identities on the next tick,
    // so the button hides within a tick of the session ending.
    private void paintFavJumpBtn(int firstVisible, int lastVisible) {
        if (favPlayingBtn == null) return;
        boolean want = favJumpTargetIdx >= 0
                && JumpPlaying.wantShown(favJumpTargetIdx, firstVisible, lastVisible);
        if (want == favPlayingShown) return;
        favPlayingShown = want;
        if (want) {
            favPlayingBtn.setVisibility(android.view.View.VISIBLE);
            Fx.fadeIn(favPlayingBtn, 180);
        } else {
            Fx.fadeOut(favPlayingBtn, 180);
        }
    }

    // v15.67 (slice 2060): repaint from the list's own position
    // after a scroll-less change — a search/sort/refresh, a
    // rotation restore, or a sounding change can move the playing
    // row off-screen with no scroll event, so read the position
    // after layout and paint from it. Posted, because the first /
    // last visible rows are only valid once the list has laid out
    // (the 1940 shape).
    private void paintFavJumpBtnFromList() {
        if (listView == null) return;
        listView.post(new Runnable() {
            @Override public void run() {
                if (listView == null) return;
                paintFavJumpBtn(listView.getFirstVisiblePosition(), listView.getLastVisiblePosition());
            }
        });
    }

    // v15.75 (slice 2108): wire the back-to-top button — one tap glides the open list to the top (the Talks 1940 / Downloaded 2003 shape: the tap needs no gate — the button only shows past row 8, so a tap always has somewhere to go; this screen HAS a search field, so the keyboard hides with the glide, the 2003 shape in this file's own inline form — the wireFavSort tap's two lines, not a new helper). A missing button simply never wires; a missing list never scrolls.
    private void wireFavTopBtn() {
        favTopBtn = findViewById(R.id.favTopBtn);
        if (favTopBtn != null) favTopBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(FavouritesActivity.this);
                Fx.morph(v);
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                if (listView != null) listView.smoothScrollToPosition(0);
            }
        });
    }

    // v15.75 (slice 2108): the back-to-top state — shown once the first visible row passes ScrollTop's threshold, hidden at the top (the 1940 Talks / 2003 Downloaded shape verbatim: ScrollTop.visible, THRESHOLD 8 — about one screen of rows). No sounding gate: the rule is the shown list's own scroll. The change gate keeps steady scrolls and the search/refresh repaint from re-touching the button; the show/hide melts (Fx.fadeIn/fadeOut, 180ms) instead of snapping, and a cancelled fade never strands it half-transparent (the v2.62 gen guard, the 1940 shape).
    private void paintFavTopBtn(int firstVisible) {
        if (favTopBtn == null) return;
        boolean want = ScrollTop.visible(firstVisible);
        if (want == favTopShown) return;
        favTopShown = want;
        if (want) {
            favTopBtn.setVisibility(android.view.View.VISIBLE);
            Fx.fadeIn(favTopBtn, 180);
        } else {
            Fx.fadeOut(favTopBtn, 180);
        }
    }

    // v15.75 (slice 2108): repaint from the list's own position after a scroll-less change — a search / refresh rebuild can land the list deep without a scroll event, so read the position after layout and paint from it. Posted, because the first visible row is only valid once the list has laid out (the 1940 shape). The rotation restore needs no separate hook: onCreate restores through refresh(), whose applyFavSearch tail already posts this repaint.
    private void paintFavTopBtnFromList() {
        if (listView == null) return;
        listView.post(new Runnable() {
            @Override public void run() {
                if (listView == null) return;
                paintFavTopBtn(listView.getFirstVisiblePosition());
            }
        });
    }

    // v15.66 (slice 2056): the sort button label names the CURRENT
    // order — the Bookmarks bmSortLabel shape (1 method, 3
    // returns): most-recent first, oldest first, sayadaw A-Z. The
    // keys are the 2051 bm_sort_* keys — they name the ORDER, not
    // the screen, so no new Lang keys and no duplicated Burmese.
    private String favSortLabel() {
        if (favSortMode == 1) return Lang.t(FavouritesActivity.this, "bm_sort_oldest");
        if (favSortMode == 2) return Lang.t(FavouritesActivity.this, "bm_sort_sayadaw");
        return Lang.t(FavouritesActivity.this, "bm_sort_recent");
    }

    private int goldColor() { return 0xFFC9A227; } // v15.64 (slice 2042): History's gold, the playing-row mark

    private View itemRow(final int position) {
        final FavTalkRail.Card c = items.get(position);
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
        // The rail card's own title (MainActivity refreshFavTalkRail,
        // v15.50): the saved spot first, bare title when never
        // started. No downloaded badge here (the 2033 precedent).
        // v15.64 (slice 2042): the sounding row says so — "\u25b6 " ahead of its
        // "\u21ba 12:34 \u00b7 title" line (or the bare title when never started) in gold
        // (History v13.55, Talks v4.89/v11.07), for a Room session (URL match) or a
        // library session on a URL-form row (dlMap exactly-one claimant); an ended talk
        // is not playing (v11.15 gate), so its mark lifts at once. One row per talk here
        // (not one per mark as on Bookmarks), so the mark reads on the one row of the
        // sounding talk. Rows are built fresh per getView, but both text and colour
        // rebind here so neither can leak onto the next talk.
        boolean favRowPlaying = !AudioService.liveEnded
                && ((favPlayingUrl.length() > 0 && SiteHttp.sameTalk(e.url, favPlayingUrl))
                        || (favPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(e.url, favPlayingLibUrl))
                        || (favPlayingLibPath.length() > 0 && e.url.equals(favPlayingLibPath)));
        // v15.85 (slice 2171): a listened favourite says so — "\u2713 " after the "\u25b6 " playing mark when both apply (the library v4.24 order ▶ ★ ✓, minus the star — every row here IS a favourite; the tick sits before the "\u21ba " resume segment, mirroring the Bookmarks tick before its minute). The tick reads the favPlayedSet snapshot (refreshed on refresh() and on the strip tick's change detector), resolved through favStreamUrlFor — a downloaded (path-form) row claims its stream URL through the dlMap exactly-one-claimant resolve, so it ticks exactly when its stream twin does. A finished talk's spot is cleared on natural completion, so without the tick it would read as never started; a marked-but-unfinished talk keeps its spot, so it reads "✓ ↺ mm:ss · title". No Lang key, no Prefs write, no layout change — the row is built in code.
        boolean favRowPlayed71 = !favRowPlaying && favRowPlayed(e);
        // v15.89 (slice 2195): the downloaded row says so — the badge after the playing mark when both apply (this screen's order: play first, badge second, tick third — no star on this screen, every row IS a favourite), so a starred talk already on the phone reads as offline at a glance. The row reads the snapshot the refresh filled; a row whose stream address sits in the set is downloaded. The badge alone does not gold the row — only a sounding row turns gold. The sounding row badges too — downloaded is a property of the talk, not a state.
        boolean favRowDl95 = favRowDl(e);
        tv.setText((favRowPlaying ? "\u25b6 " : "") + (favRowDl95 ? "\u2B07 " : "") + (favRowPlayed71 ? "\u2713 " : "") + (c.posMs > 0 ? "\u21BA " + FavTalkRail.fmtTime(c.posMs) + " \u00b7 " + e.title : e.title));
        tv.setTextColor(favRowPlaying ? goldColor() : android.graphics.Color.parseColor("#7A6A58"));
        tv.setTextSize(12);
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(col, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        // v15.90 (slice 2199): a starred talk's total length clocks on the row's right edge — the chooser's 1094 clock, now on the last full-list screen without any length readout (History rows clock heard time, Bookmarks rows clock the mark minute, Favourites rows clock the resume spot — no Favourites row ever said how long its talk runs). The clock reads favRowDurMs (the duration snapshot, refreshed on refresh() and on the strip tick's change detector), formatted by Fx.fmtTime ("42:15", "1:05:22" past the hour, the chooser's clock shape); the playing row clocks too (its length is a property of the talk, the 1094 rule — its live position is on the strip). Hidden when no duration was ever banked — GONE, never guessed, never "0:00". No Lang key, no Prefs write, no layout change — the row is built in code.
        int favRowDur98 = favRowDurMs(e);
        TextView favDur98 = new TextView(this);
        favDur98.setText(favRowDur98 > 0 ? Fx.fmtTime(favRowDur98) : "");
        favDur98.setTextColor(night ? android.graphics.Color.parseColor("#AAAAAA")
                : android.graphics.Color.parseColor("#7A6A58"));
        favDur98.setTextSize(12);
        favDur98.setVisibility(favRowDur98 > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
        LinearLayout.LayoutParams favDurParams98 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        favDurParams98.setMargins(0, 0, (int) (8 * d), 0);
        favDur98.setLayoutParams(favDurParams98);
        row.addView(favDur98);
        TextView go = new TextView(this);
        go.setText("\u203a");
        go.setTextColor(night ? android.graphics.Color.parseColor("#AAAAAA")
                : android.graphics.Color.parseColor("#7A6A58"));
        go.setTextSize(22);
        row.addView(go, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        // v15.90 (slice 2201): a partly-heard favourite's listened-progress hairline — the Talks-row line (v13.97, slice 1092) and the rail cards' line (v15.60, slice 2020) now under this row: 3dp tall, 4dp below the row, faint-gold track (#33C9A227) with a solid-gold fill (#C9A227, @color/gold) split by weight, weightSum 1. The fraction is the card's own resume spot over the 2199 clock's total (the shared fraction helper — the Talks-row rule verbatim: 0 when never meaningfully heard, spot at or below 5s, or no duration ever stored; clamped to 1, never below a 3% sliver, so a barely-started talk still reads as a visible gold dash). Never the playing row (its live position is on the strip, the 1092 gate). GONE unless a fraction was set — Fx.crossfadeHairline owns the show/hide (on these freshly built rows it seeds instantly, no melt ever plays here, the 2020 rail shape). No Lang key, no Prefs write, no layout change — the row is built in code.
        float favHairFrac98 = favRowPlaying ? 0f : RailHair.fraction(c.posMs, favRowDur98);
        LinearLayout favHairTrack98 = new LinearLayout(this);
        favHairTrack98.setOrientation(LinearLayout.HORIZONTAL);
        favHairTrack98.setBackgroundColor(android.graphics.Color.parseColor("#33C9A227"));
        favHairTrack98.setWeightSum(1f);
        LinearLayout.LayoutParams favHairParams98 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (3 * d));
        favHairParams98.topMargin = (int) (4 * d);
        favHairTrack98.setLayoutParams(favHairParams98);
        android.view.View favHairFill98 = new android.view.View(this);
        favHairFill98.setBackgroundColor(android.graphics.Color.parseColor("#C9A227"));
        favHairFill98.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, favHairFrac98));
        android.view.View favHairSpace98 = new android.view.View(this);
        favHairSpace98.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f - favHairFrac98));
        favHairTrack98.addView(favHairFill98);
        favHairTrack98.addView(favHairSpace98);
        Fx.crossfadeHairline(favHairTrack98, favHairFrac98 > 0f);
        LinearLayout favWrap98 = new LinearLayout(this);
        favWrap98.setOrientation(LinearLayout.VERTICAL);
        favWrap98.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        favWrap98.addView(favHairTrack98);
        return favWrap98;
    }

    // v15.64 (slice 2044): Play-all visibility — the chooser's
    // refreshTalkPlayAllRow shape (v13.76): GONE until a list lands,
    // fading in/out on change, instant on first paint. The gate is
    // FavPlayAll.hasPlayable: at least one starred talk with a usable
    // URL that is not the sounding talk. lastFavPlayAllVisible is the
    // change-only snapshot — steady 1s ticks never touch the button.
    // v15.68 (slice 2065): the Shuffle button rides the same gate
    // and the same snapshot — the two header verbs show and hide
    // together.
    private void syncFavPlayAllBtn(boolean skipFade) {
        if (btnFavPlayAll == null) return;
        boolean show = FavPlayAll.hasPlayable(items, favPlayingUrl, favPlayingLibUrl);
        if (lastFavPlayAllVisible != null && lastFavPlayAllVisible.booleanValue() == show) return;
        lastFavPlayAllVisible = Boolean.valueOf(show);
        if (skipFade) {
            btnFavPlayAll.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
            btnFavPlayAll.setAlpha(1f);
        } else if (show) {
            if (btnFavPlayAll.getVisibility() != android.view.View.VISIBLE) {
                btnFavPlayAll.setVisibility(android.view.View.VISIBLE);
                Fx.fadeIn(btnFavPlayAll, 250);
            } else if (btnFavPlayAll.getAlpha() < 0.999f) {
                Fx.fadeIn(btnFavPlayAll, 250);
            }
        } else if (btnFavPlayAll.getVisibility() == android.view.View.VISIBLE) {
            Fx.fadeOut(btnFavPlayAll, 200);
        }
        // v15.68 (slice 2065): the Shuffle button rides the same
        // gate and the same change-only snapshot, so the two header
        // verbs show and hide together; a null button simply never
        // paints (both buttons live in the same layout, so they
        // exist or miss together — this guard is belt and braces).
        if (btnFavShuffle != null) {
            if (skipFade) {
                btnFavShuffle.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
                btnFavShuffle.setAlpha(1f);
            } else if (show) {
                if (btnFavShuffle.getVisibility() != android.view.View.VISIBLE) {
                    btnFavShuffle.setVisibility(android.view.View.VISIBLE);
                    Fx.fadeIn(btnFavShuffle, 250);
                } else if (btnFavShuffle.getAlpha() < 0.999f) {
                    Fx.fadeIn(btnFavShuffle, 250);
                }
            } else if (btnFavShuffle.getVisibility() == android.view.View.VISIBLE) {
                Fx.fadeOut(btnFavShuffle, 200);
            }
        }
    }

    // v15.64 (slice 2044): "Play all" — the Favourites mirror of the
    // chooser's playAllShown (v13.76) and History's histPlayFromHere
    // (v15.62, slice 2030). The starred talks used to play one row
    // tap at a time; one tap here queues the whole starred run in
    // screen order, across sayadaws (every line carries its own
    // sayadaw, the HistPlayFromHere v2 shape), and opens the Room on
    // the first starred talk at its saved spot (playFav's own route —
    // the Room resumes where the listener left off, v1.56).
    // Same guards as the chooser: empty list refuses with the same
    // words, the 700ms debounce has its own clock (an invalid tap
    // never consumes the window, v12.28), the sounding talk is never
    // re-queued (the stay-put branch: the rest queue behind it, no
    // restart), already-queued talks stay where they are, and the
    // count toast counts queued lines only. The anchor sayadaw is
    // healed exactly like playFav (catalog + the sayadawForTalk
    // heal) — the queue key and the Room name must be the healed
    // name, never a stale banked one. No catalog, no run: the queue
    // is untouched. The first card is always a stream URL by
    // FavAll's own construction (favUrls are canonical https, matched
    // by sameTalk — a path-form recent row can never be a card), so
    // no library leg is needed here.
    private void playAllFav(android.view.View v) {
        if (items == null || items.isEmpty()) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long now44 = android.os.SystemClock.uptimeMillis();
        if (now44 - lastFavPlayAllMs < 700) return;
        lastFavPlayAllMs = now44;
        refreshFavouritesPlayingMarks(); // tap-time sounding identities
        final FavTalkRail.Card first44 = items.get(0);
        if (first44 == null || first44.e == null || first44.e.url == null || first44.e.url.length() == 0) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String sd44 = first44.e.sayadaw;
        String[] cat44 = catalogFor(sd44);
        if (cat44 == null || cat44[1] == null || cat44[1].length() == 0) {
            String owner44 = Prefs.sayadawForTalk(this, "lib", first44.e.url, "");
            if (owner44 != null && owner44.length() > 0 && !owner44.equals(sd44)) {
                sd44 = owner44;
                cat44 = catalogFor(sd44);
            }
        }
        if (cat44 == null || cat44[1] == null || cat44[1].length() == 0) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "pl_no_play"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        boolean firstSounds44 = (favPlayingUrl.length() > 0 && SiteHttp.sameTalk(first44.e.url, favPlayingUrl))
                || (favPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(first44.e.url, favPlayingLibUrl));
        // The anchor sayadaw's current queue, as identities — the
        // pick skips talks already queued (they stay where they are).
        // Saved lines are carried verbatim (histQueueAdd's discipline),
        // so a foreign line the Room drawer moved mid-queue survives
        // the write.
        java.util.Set<String> queuedIds44 = new java.util.HashSet<String>();
        java.util.ArrayList<String> saved44 = new java.util.ArrayList<String>();
        try {
            String savedRaw44 = Prefs.savedQueue(this, sd44);
            if (savedRaw44 != null && savedRaw44.length() > 0) {
                for (String ln44 : savedRaw44.split("\n")) {
                    if (ln44.length() == 0) continue;
                    saved44.add(ln44);
                    QueueEntry qe44 = QueueEntry.parse(ln44);
                    if (qe44 != null && qe44.url.length() > 0)
                        queuedIds44.add(SiteHttp.identityUrl(qe44.url));
                }
            }
        } catch (Exception ignored) {}
        java.util.List<String> run44 = FavPlayAll.lines(items, queuedIds44, favPlayingUrl, favPlayingLibUrl);
        int added44 = 0;
        for (String ln44 : run44) {
            QueueEntry qe44 = QueueEntry.parse(ln44);
            if (qe44 == null || qe44.url.length() == 0) continue;
            boolean dup44 = false;
            for (String ex44 : saved44) {
                QueueEntry qx44 = QueueEntry.parse(ex44);
                if (qx44 != null && SiteHttp.sameTalk(qx44.url, qe44.url)) { dup44 = true; break; }
            }
            if (dup44) continue;
            saved44.add(ln44);
            added44++;
        }
        Prefs.saveQueue(this, sd44, saved44);
        android.widget.Toast.makeText(FavouritesActivity.this, added44 > 0
                ? Lang.t(FavouritesActivity.this, "queue_plain_prefix") + added44 + Lang.t(FavouritesActivity.this, "queue_added_suffix")
                : Lang.t(FavouritesActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
        Fx.morph(v);
        if (!firstSounds44) playFav(first44, v);
    }

    // v15.68 (slice 2065): "Shuffle play" — the Favourites mirror
    // of the chooser's shufflePlayShown (v13.93). The starred talks
    // used to play in screen order only (Play-all, v15.64); one tap
    // here shuffles the SHOWN run into the queue in random order
    // (FavShuffle.shuffleCards over the filtered + sorted list —
    // the 2047 rule: the shown run is the run) and opens the Room
    // on the first shuffled talk at its saved spot (playFav's own
    // route). Same guards as the chooser: empty list refuses with
    // the same words, the 700ms debounce has its own clock (an
    // invalid tap never consumes the window, v12.28), the sounding
    // talk is never re-queued (the stay-put branch: the rest queue
    // behind it, no restart), already-queued talks stay where they
    // are, and the count toast counts queued lines only. The anchor
    // sayadaw is the FIRST SHUFFLED card's sayadaw, healed exactly
    // like playFav (catalog + the sayadawForTalk heal). No catalog,
    // no run: the queue is untouched. The shuffled queue IS the
    // shuffle — the global shuffle-continuous toggle is left alone,
    // so a user who deliberately left it off is not surprised, and
    // after the queue drains normal advance resumes.
    private void shufflePlayFav(android.view.View v) {
        if (items == null || items.isEmpty()) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long now65 = android.os.SystemClock.uptimeMillis();
        if (now65 - lastFavShuffleMs < 700) return;
        lastFavShuffleMs = now65;
        refreshFavouritesPlayingMarks(); // tap-time sounding identities
        java.util.List<FavTalkRail.Card> shuffled65 = FavShuffle.shuffleCards(items, new java.util.Random());
        if (shuffled65.isEmpty()) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final FavTalkRail.Card first65 = shuffled65.get(0);
        if (first65 == null || first65.e == null || first65.e.url == null || first65.e.url.length() == 0) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String sd65 = first65.e.sayadaw;
        String[] cat65 = catalogFor(sd65);
        if (cat65 == null || cat65[1] == null || cat65[1].length() == 0) {
            String owner65 = Prefs.sayadawForTalk(this, "lib", first65.e.url, "");
            if (owner65 != null && owner65.length() > 0 && !owner65.equals(sd65)) {
                sd65 = owner65;
                cat65 = catalogFor(sd65);
            }
        }
        if (cat65 == null || cat65[1] == null || cat65[1].length() == 0) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "pl_no_play"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        boolean firstSounds65 = (favPlayingUrl.length() > 0 && SiteHttp.sameTalk(first65.e.url, favPlayingUrl))
                || (favPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(first65.e.url, favPlayingLibUrl));
        // The anchor sayadaw's current queue, as identities — the
        // pick skips talks already queued (they stay where they are).
        // Saved lines are carried verbatim (histQueueAdd's discipline),
        // so a foreign line the Room drawer moved mid-queue survives
        // the write.
        java.util.Set<String> queuedIds65 = new java.util.HashSet<String>();
        java.util.ArrayList<String> saved65 = new java.util.ArrayList<String>();
        try {
            String savedRaw65 = Prefs.savedQueue(this, sd65);
            if (savedRaw65 != null && savedRaw65.length() > 0) {
                for (String ln65 : savedRaw65.split("\n")) {
                    if (ln65.length() == 0) continue;
                    saved65.add(ln65);
                    QueueEntry qe65 = QueueEntry.parse(ln65);
                    if (qe65 != null && qe65.url.length() > 0)
                        queuedIds65.add(SiteHttp.identityUrl(qe65.url));
                }
            }
        } catch (Exception ignored) {}
        // The shuffled order flows through FavPlayAll.lines (it keeps
        // its input order): the run is the shuffled run, deduped,
        // sounding-skipped and already-queued-skipped exactly like
        // Play-all's.
        java.util.List<String> run65 = FavPlayAll.lines(shuffled65, queuedIds65, favPlayingUrl, favPlayingLibUrl);
        int added65 = 0;
        for (String ln65 : run65) {
            QueueEntry qe65 = QueueEntry.parse(ln65);
            if (qe65 == null || qe65.url.length() == 0) continue;
            boolean dup65 = false;
            for (String ex65 : saved65) {
                QueueEntry qx65 = QueueEntry.parse(ex65);
                if (qx65 != null && SiteHttp.sameTalk(qx65.url, qe65.url)) { dup65 = true; break; }
            }
            if (dup65) continue;
            saved65.add(ln65);
            added65++;
        }
        Prefs.saveQueue(this, sd65, saved65);
        android.widget.Toast.makeText(FavouritesActivity.this, added65 > 0
                ? Lang.t(FavouritesActivity.this, "queue_plain_prefix") + added65 + Lang.t(FavouritesActivity.this, "queue_added_suffix")
                : Lang.t(FavouritesActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
        Fx.morph(v);
        if (!firstSounds65) playFav(first65, v);
    }

    // v15.80 (slice 2140): "Mark as listened / Unmark as listened" — the mark verb the Favourites row menu lacked that the Downloaded library row menu has had since v15.47 (slice 1949), the Talks chooser since v15.51 (slice 1969), the Room drawer dialogs since v15.52 (slices 1978/1979), the New talks row menu since v15.80 (slice 2138) and the History row menu since v15.80 (slice 2139): a starred talk could only earn or lose its listened mark by playing it to the end or opening the library — and a Favourites row is exactly where both legs are live, because playedTalks is banked only on natural finish (AudioService.markPlayedForCurrent) and starring is independent of it: a starred talk stopped early sits unmarked (offers Mark), a finished one sits marked (offers Unmark — it returns to the New talks list). Same verb through the same rules, so the surfaces can never disagree: the 1949 pure core (DlMark — canOffer / labelKey / isMarked / mark / unmark, inputs never mutated), the v11.14 tap-time sounding re-check over this screen's two sounding identities (the queue branches' rule), the prefs-fresh playedTalks read at tap time, and the mark landing in the SAME playedTalks key AudioService banks on finish and the library, the rails, the header new-count and the filters all read. The helper re-resolves the row to its stream URL at tap time (favStreamUrlFor — the Favourites-local shape, the offer and the tap can never disagree). No refresh and no repaint on this screen: the row renders no listened tick (only the sounding ▶ and saved-spot ↺ prefixes), so nothing here rebinds from playedTalks — the visible effect is the menu label flipping mark<->unmark on the next open, the verb toast, and the propagation (New talks list + header new-count + chooser tick). Neither the history REC line, the lifetime totals, the queue, the shelf order, the star itself nor any saved resume position is mutated.
    private void markListenedFav(final FavTalkRail.Card c, final boolean unmark) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        final String streamUrl = favStreamUrlFor(c.e);
        if (streamUrl == null || streamUrl.length() == 0) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // Tap-time sounding re-check (Talks v11.14): the dialog can sit open across an auto-advance, so this talk can BE sounding by tap time — it earns its mark by finishing, never by this verb. This screen's two sounding identities, the queue branches' rule.
        refreshFavouritesPlayingMarks();
        if ((favPlayingUrl.length() > 0 && SiteHttp.sameTalk(streamUrl, favPlayingUrl))
                || (favPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(streamUrl, favPlayingLibUrl))) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        DlMark.Result res40 = null;
        try {
            android.content.SharedPreferences sp40 = getSharedPreferences("dhamma", MODE_PRIVATE);
            java.util.Set<String> cur40 = sp40.getStringSet("playedTalks", new java.util.HashSet<String>());
            res40 = unmark ? DlMark.unmark(cur40, SiteHttp.identityUrl(streamUrl), streamUrl)
                    : DlMark.mark(cur40, SiteHttp.identityUrl(streamUrl));
            sp40.edit().putStringSet("playedTalks", res40.set).apply();
        } catch (Exception ignored) {}
        android.widget.Toast.makeText(FavouritesActivity.this,
                Lang.t(FavouritesActivity.this, (res40 != null && res40.changed) ? (unmark ? "dl_menu_unmark_one" : "dl_menu_mark_one") : (unmark ? "nothing_to_unmark" : "already_listened")),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private java.util.Set<String> readPlayedTalksFav() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        try {
            java.util.Set<String> raw = getSharedPreferences("dhamma", MODE_PRIVATE).getStringSet("playedTalks", null);
            if (raw != null) out.addAll(raw);
        } catch (Exception ignored) {}
        return out;
    }

    // v15.68 (slice 2069): row long-press menu on this screen — the
    // Favourites screen had no row menu at all (no long-press, no
    // dialog, no per-row un-star): stars were removed per-row from
    // the rail/chooser/library menus only (the 2035 header note),
    // and nothing started a run mid-list (Play-all starts at the
    // top, a tap plays one talk). The menu opens with the verbs this screen needs:
    // "Share talk" first (the History menu_share shape, v13.77 — share the
    // row's talk through the Android sheet), "Copy link" second (the History
    // dl_menu_copy_link shape, v15.53 — copy the row's stream URL
    // to the clipboard, the share/copy pair together), "Share file"
    // third (the History dl_menu_share_file shape, v15.53 — send
    // the downloaded .mp3 through the sheet, the share/copy/file
    // trio together), then "Play from here" (the chooser's 1942 /
    // History's 2030 verb — queue the shown run from this row
    // onward and open the Room on it), "Add to playlist" (the
    // Room's v12.65 verb — file the talk in one of the
    // listener's own lists, before Remove from favourites) and
    // "Remove from favourites" (the screen's own removal verb —
    // the row leaves the list at once). download is the remaining
    // future verb on this same menu (playlist-add landed in
    // slice 2091, share-file in slice 2089, copy-link in slice
    // 2085, share in slice 2083, go-to-sayadaw in slice 2078,
    // queue in slice 2073; the History menu grew the same way:
    // 1981, 1982, 1995, 2030). The offer comes from the pure
    // FavRowMenu core (sameTalk sounding gate, path-form rows offer
    // remove only); the dialog is History's AlertDialog shape
    // (title = the card's title, key-dispatched handler); every
    // verb re-checks its offer at tap time, so a dialog left open
    // across a refresh can never act on a stale row.
    private void showFavRowMenu(final FavTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        refreshFavouritesPlayingMarks();
        final String url = c.e.url;
        final String sd = c.e.sayadaw;
        java.util.List<String> keys69 = FavRowMenu.menuKeys(url, sd, favPlayingUrl, favPlayingLibUrl);
        // v15.69 (slice 2073): the queue verb — "Add to queue" /
        // "Remove from queue" (FavQueue): the row's sayadaw queue,
        // read once per menu open; the key composes into the menu
        // between Play-from-here and Remove-from-favourites, so the
        // play verbs sit together and the removal stays last. A
        // stale banked sayadaw can only mis-offer (add for a talk
        // already queued under another key) — the handler re-reads
        // the same key and refuses with already_in_queue, never a
        // wrong write.
        String qKey73 = "";
        try {
            java.util.ArrayList<String> saved73 = new java.util.ArrayList<String>();
            String savedRaw73 = Prefs.savedQueue(this, sd);
            if (savedRaw73 != null && savedRaw73.length() > 0) {
                for (String ln73 : savedRaw73.split("\n")) {
                    if (ln73.length() == 0) continue;
                    saved73.add(ln73);
                }
            }
            qKey73 = FavQueue.queueKey(url, sd, favPlayingUrl, favPlayingLibUrl,
                    FavQueue.isQueued(url, saved73));
        } catch (Exception ignored) {}
        if (qKey73.length() > 0) {
            int at73 = keys69.contains("menu_play_from_here") ? 1 : 0;
            keys69.add(at73, qKey73);
        }
        // v15.83 (slice 2160): the queue-from-here key — gated on the new FavRowMenu.queueFromHereKey leg (EXACTLY the play-from-here gate — a stream URL with a sayadaw, not sounding — so the anchor itself can join the run; rows below that cannot queue are skipped by the helper's pick, not by the offer). It composes right after the queue key (indexOf qKey73, fallback indexOf menu_play_from_here — the queue verbs sit together, the chooser's order: queue add/remove, queue-from-here, play-from-here — play-from-here already leads this menu's play verbs), so the display order reads Share, Minute, Copy, File, PlayFromHere, Queue, QueueFromHere, Go, Download, PlAdd, Mark, Remove. The queue key is always present when the gate passes (the same sounding rule feeds both), so the < 0 leg is defensive only.
        String qfhKey60 = FavRowMenu.queueFromHereKey(url, sd, favPlayingUrl, favPlayingLibUrl);
        if (qfhKey60 != null) {
            int at60 = keys69.indexOf(qKey73);
            if (at60 < 0) at60 = keys69.indexOf("menu_play_from_here");
            keys69.add(at60 < 0 ? keys69.size() : at60 + 1, qfhKey60);
        }
        // v15.70 (slice 2078): the navigation verb — "Go to
        // sayadaw's talks" (FavGo, the BmGo mirror over
        // GoSayadaw): the row's sayadaw resolved through the
        // bundled catalog (catalogFor — the cached, canonicalized
        // read the play verbs already pay for; one cache lookup
        // per menu open at most). The key composes after the
        // queue verb, so the play verbs sit together, the
        // navigation follows, and Remove from favourites stays
        // last (inserted before menu_fav_remove, never appended
        // past it). Offered on any row whose sayadaw resolves to
        // a page URL — sounding or not, the navigation never
        // touches playback — and hidden while the sayadaw is
        // unknown or unlisted (FavGo.canOffer). A stale banked
        // sayadaw can only mis-offer when the canonical name
        // still resolves — the handler re-resolves at tap time
        // and toasts no_sayadaw_page on a miss, never a dead tap.
        String goKey78 = "";
        try {
            String[] goCat78 = catalogFor(sd);
            if (FavGo.canOffer(sd, goCat78)) goKey78 = "menu_go_sayadaw";
        } catch (Exception ignored) {}
        if (goKey78.length() > 0) {
            int at78 = keys69.indexOf("menu_fav_remove");
            keys69.add(at78 < 0 ? keys69.size() : at78, goKey78);
        }
        // v15.71 (slice 2083): the share verb — "Share talk"
        // (FavShare, the History menu_share shape, v13.77): the
        // row resolves to its stream URL once per menu open
        // (favStreamUrlFor — URL-form rows share their own URL,
        // path-form rows resolve through the dlMap reverse lookup
        // with the exactly-one-claimant rule, v8.44; an
        // unresolvable row offers no share, never a guessed
        // link). The key composes FIRST, before Play-from-here,
        // so the share sits at the top of the menu like History's
        // menu and the chooser's dialog ("share first so muscle
        // memory is preserved", v7.82). Offered on any row with a
        // resolvable stream URL — sounding or not, sharing never
        // touches playback.
        // v15.71 (slice 2085): the copy verb — "Copy link"
        // (RoomCopyLink, the History dl_menu_copy_link shape,
        // v15.53 slice 1981): the same resolved stream URL
        // (shareUrl83 — no second resolve, the v5.69 cost rule)
        // offers the clipboard copy, so a listener who wants
        // just the link pasted elsewhere never has to share it
        // through the sheet and copy from there. The gate is
        // blank-URL-only (RoomCopyLink.canOffer), so a
        // streamed-only talk still offers it (a link is not a
        // file); only a blank URL hides it. The key composes
        // right after the share (below), so the share/copy pair
        // sits together like History's menu and the chooser's
        // trio — the pair can never be split by a later insert.
        // v15.72 (slice 2089): the file verb — "Share file"
        // (TalkFileShare, the History dl_menu_share_file shape,
        // v15.53 slice 1982): the same resolved stream URL
        // (shareUrl83 — no second resolve, the v5.69 cost rule)
        // offers sending the actual downloaded .mp3, so a
        // listener who just downloaded a favourite talk never has
        // to find it in Downloads/Dhamma by hand. The gate is the
        // screen's own download check (favIsDownloaded — the
        // History v13.77 pair: dlMap mapped file + the v13.66
        // length gate, plus the basename leg), so a streamed-only
        // talk never offers it (a file is not a link). The key
        // composes right after the copy (below), so the
        // share/copy/file trio sits together like History's menu
        // and the chooser's dialog — the trio can never be split
        // by a later insert.
        // v15.72 (slice 2091): the playlist verb — "Add to playlist"
        // (FavPlAdd, the Room's showPlaylistAddDialog shape,
        // v12.65–v12.80): the same resolved stream URL
        // (shareUrl83 — no second resolve, the v5.69 cost rule)
        // offers filing the talk in one of the listener's own
        // lists, so a listener who just starred a favourite talk
        // never has to open the Room to file it. The gate is the
        // same non-blank URL the share reads (FavPlAdd.canOffer),
        // so a streamed-only talk still offers it (filing is not
        // a file); only a blank URL hides it. The key composes
        // right before Remove from favourites (below), so the
        // collect verbs sit together last — the share trio, the
        // play verbs and the navigation are never split by it.
        String shareKey83 = "";
        String copyKey85 = "";
        String fileKey89 = "";
        String plKey91 = "";
        String minKey2133 = "";
        String dlKey2134 = "";
        String markKey40 = "";
        try {
            String shareUrl83 = favStreamUrlFor(c.e);
            if (FavShare.canOffer(shareUrl83)) shareKey83 = "menu_share";
            if (RoomCopyLink.canOffer(shareUrl83)) copyKey85 = "dl_menu_copy_link";
            if (favIsDownloaded(shareUrl83)) fileKey89 = "dl_menu_share_file";
            if (FavPlAdd.canOffer(shareUrl83)) plKey91 = "pl_add";
            // v15.79 (slice 2133): the minute key — gated on the row's own rendered minute (c.posMs > 5000, the 2012/2013/2018 family rule) plus the share offer, so a spot under 5s or a share-less row never offers it. The minute is the card's captured saved spot (FavTalkRail.Card.posMs, final), not a live spot.
            if (c.posMs > 5000 && shareKey83.length() > 0) minKey2133 = "dl_menu_share_minute";
            // v15.79 (slice 2134): the download key — gated on the v11.70 rule through the pinned pure core MainDl.canOffer (slice 1997: neither downloaded nor already queued), plus the non-blank stream URL leg like History's offer, so an unresolvable row never offers it. The sounding row keeps it (History offers it on sounding rows too — downloading the talk now playing is harmless, the Room keeps streaming).
            if (shareUrl83.length() > 0 && MainDl.canOffer(favIsDownloaded(shareUrl83), favIsDlQueued(shareUrl83))) dlKey2134 = "dl_btn_download";
            // v15.80 (slice 2140): the mark key — gated on the 1949 DlMark core (canOffer: a non-blank stream URL, and not the sounding talk — the sounding talk earns its mark by finishing, like the 1949 sounding exclusion), through this screen's two sounding identities (the same gate the queue verbs read), over the resolved stream URL (shareUrl83 — favStreamUrlFor, so a path-form row resolves through the dlMap exactly-one-claimant rule, never a guessed link). The label is DlMark.labelKey over the prefs-fresh played set (readPlayedTalksFav, a local mirror of NewTalksActivity's readPlayedTalks — FavouritesActivity had no playedTalks reader), so the label can never disagree with the list membership — and on this screen both legs are live, because playedTalks is banked only on natural finish: a starred talk stopped early offers Mark, a finished one offers Unmark.
            boolean sounding40 = (favPlayingUrl.length() > 0 && SiteHttp.sameTalk(shareUrl83, favPlayingUrl))
                    || (favPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(shareUrl83, favPlayingLibUrl));
            if (DlMark.canOffer(shareUrl83, sounding40)) markKey40 = DlMark.labelKey(DlMark.isMarked(readPlayedTalksFav(), SiteHttp.identityUrl(shareUrl83)));
        } catch (Exception ignored) {}
        if (shareKey83.length() > 0) keys69.add(0, shareKey83);
        // v15.71 (slice 2085): the copy key composes right after
        // the share (indexOf + 1 — the share sits at index 0, so
        // inserting here keeps the share/copy pair adjacent
        // whatever later inserts do; a copy implies a share, since
        // both gates are the same non-blank URL — the at85 < 0
        // leg is defensive only).
        if (copyKey85.length() > 0) {
            int at85 = keys69.indexOf("menu_share");
            keys69.add(at85 < 0 ? keys69.size() : at85 + 1, copyKey85);
        }
        // v15.72 (slice 2089): the file key composes right after
        // the copy (indexOf + 1 — the copy insert above already
        // landed, so inserting here keeps the share/copy/file
        // trio adjacent whatever later inserts do; a file implies
        // a copy, since a downloaded talk always has a non-blank
        // URL — the at89 < 0 leg is defensive only).
        if (fileKey89.length() > 0) {
            int at89 = keys69.indexOf("dl_menu_copy_link");
            keys69.add(at89 < 0 ? keys69.size() : at89 + 1, fileKey89);
        }
        // v15.79 (slice 2133): the minute key composes right after the share (indexOf + 1 — the file insert above already landed, so inserting here keeps the share verbs together in the order text, minute, copy, file, whatever later inserts do; a minute implies a share, since both gates are the same non-blank stream URL plus the row's own rendered minute — the atMin2133 < 0 leg is defensive only).
        if (minKey2133.length() > 0) {
            int atMin2133 = keys69.indexOf("menu_share");
            keys69.add(atMin2133 < 0 ? keys69.size() : atMin2133 + 1, minKey2133);
        }
        // v15.79 (slice 2134): the download key composes right before Remove from favourites, before the playlist insert below lands — the History order (download, playlist-add, remove), so the collect verbs sit together last and the removal stays last; a download implies a share, since both gates are the same non-blank stream URL — the atDl2134 < 0 leg is defensive only.
        if (dlKey2134.length() > 0) {
            int atDl2134 = keys69.indexOf("menu_fav_remove");
            keys69.add(atDl2134 < 0 ? keys69.size() : atDl2134, dlKey2134);
        }
        // v15.72 (slice 2091): the playlist key composes right before
        // Remove from favourites (indexOf — the removal stays last
        // and the collect verbs sit together; a playlist add implies
        // a share, since both gates are the same non-blank URL — the
        // at91 < 0 leg is defensive only).
        if (plKey91.length() > 0) {
            int at91 = keys69.indexOf("menu_fav_remove");
            keys69.add(at91 < 0 ? keys69.size() : at91, plKey91);
        }
        // v15.80 (slice 2140): the mark key composes right before Remove from favourites (indexOf — the removal stays last and the mark sits beside the favourite toggle, the 1969 chooser shape adapted: on this screen the fav toggle IS the removal, so "right after the toggle" would put the mark past it; a mark implies a share, since both gates are the same non-blank stream URL — the at40 < 0 leg is defensive only).
        if (markKey40.length() > 0) {
            int at40 = keys69.indexOf("menu_fav_remove");
            keys69.add(at40 < 0 ? keys69.size() : at40, markKey40);
        }
        java.util.ArrayList<String> opts = new java.util.ArrayList<String>();
        for (String k69 : keys69) opts.add(Lang.t(this, k69));
        final String[] optArr = opts.toArray(new String[0]);
        final String[] keyArr = keys69.toArray(new String[0]);
        new android.app.AlertDialog.Builder(this)
                .setTitle(c.e.title != null && c.e.title.length() > 0 ? c.e.title : url)
                .setItems(optArr, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if ("menu_share".equals(keyArr[which])) {
                            shareTalkFav(c);
                        } else if ("dl_menu_share_minute".equals(keyArr[which])) {
                            // v15.79 (slice 2133): "Share at this minute" — the 1993 minute-share verb on the Favourites row menu, through shareAtMinuteFav below (the file routes every menu verb through a named helper).
                            shareAtMinuteFav(c);
                        } else if ("dl_menu_copy_link".equals(keyArr[which])) {
                            copyTalkLinkFav(c);
                        } else if ("dl_menu_share_file".equals(keyArr[which])) {
                            shareFileFav(c);
                        } else if ("pl_add".equals(keyArr[which])) {
                            addToPlaylistFav(c);
                        } else if ("dl_btn_download".equals(keyArr[which])) {
                            // v15.79 (slice 2134): "Download" — the v11.70 download verb on the Favourites row menu, through downloadTalkFav below (the file routes every menu verb through a named helper).
                            downloadTalkFav(c);
                        } else if ("menu_play_from_here".equals(keyArr[which])) {
                            playFromHereFav(c);
                        } else if ("menu_queue_add".equals(keyArr[which])) {
                            queueAddFav(c);
                        } else if ("menu_queue_remove".equals(keyArr[which])) {
                            queueRemoveFav(c);
                        } else if ("menu_queue_from_here".equals(keyArr[which])) {
                            // v15.83 (slice 2160): "Queue from here" — the bulk queue verb on the Favourites row menu, through queueFromHereFav below (the file routes every menu verb through a named helper). Queue from here landed in slice 2160.
                            queueFromHereFav(c);
                        } else if ("menu_go_sayadaw".equals(keyArr[which])) {
                            goToSayadawFav(c);
                        } else if ("dl_menu_mark_one".equals(keyArr[which])) {
                            // v15.80 (slice 2140): "Mark as listened" — the 1949 mark verb on the Favourites row menu, through markListenedFav below (the file routes every menu verb through a named helper).
                            markListenedFav(c, false);
                        } else if ("dl_menu_unmark_one".equals(keyArr[which])) {
                            // v15.80 (slice 2140): "Unmark as listened" — the 1949 unmark leg on the Favourites row menu, through markListenedFav below (both-forms rule: the canonical URL and the raw URL leave the played set together).
                            markListenedFav(c, true);
                        } else if ("menu_fav_remove".equals(keyArr[which])) {
                            removeFavFromMenu(c);
                        }
                    }
                })
                .show();
    }

    // v15.68 (slice 2069): "Play from here" on this screen — the
    // row menu's first play verb (the chooser's menu_play_from_here,
    // v15.45 slice 1942, and History's histPlayFromHere, v15.62
    // slice 2030): queue the SHOWN run from the long-pressed row
    // onward (the 2047 rule — the filtered + sorted list is the
    // run) and open the Room on it, in one tap. The run is built
    // with the chooser's PlayFromHere.pick (anchor onward, minus
    // the sounding talk and minus already-queued talks) and
    // History's HistPlayFromHere.lines (v2 lines carrying each
    // talk's OWN sayadaw, so the Room renders the other sayadaws'
    // talks as foreign rows). The anchor sayadaw is healed exactly
    // like the row tap (catalog + the sayadawForTalk heal) — the
    // queue key and the Room name must be the healed name, never a
    // stale banked one. No catalog, no run: the queue is untouched.
    // The Room opens with the row tap's own intent, built inline —
    // NOT through the row-tap method, which carries its own 700ms
    // tap debounce, and the menu tap is not a row tap, so routing
    // through it could swallow the open (histPlayFromHere builds
    // its intent inline for the same reason). A queued anchor plays
    // from its queue spot; a sounding anchor never re-opens
    // (stay-put).
    private void playFromHereFav(final FavTalkRail.Card c) {
        if (items == null || items.isEmpty() || c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long now69 = android.os.SystemClock.uptimeMillis();
        if (now69 - lastFavPlayFromHereMs < 700) return;
        lastFavPlayFromHereMs = now69;
        // Tap-time re-checks (the dialog's own shape): the anchor
        // may have lost its stream URL or its sayadaw while the
        // dialog sat open, turned into the sounding talk, or left
        // the list (un-starred from another screen).
        refreshFavouritesPlayingMarks();
        final String streamUrl = c.e.url;
        if (streamUrl.startsWith("/")) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if ((favPlayingUrl.length() > 0 && SiteHttp.sameTalk(streamUrl, favPlayingUrl))
                || (favPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(streamUrl, favPlayingLibUrl))) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String sd69 = c.e.sayadaw;
        String[] cat69 = catalogFor(sd69);
        if (cat69 == null || cat69[1] == null || cat69[1].length() == 0) {
            String owner69 = Prefs.sayadawForTalk(this, "lib", streamUrl, "");
            if (owner69 != null && owner69.length() > 0 && !owner69.equals(sd69)) {
                sd69 = owner69;
                cat69 = catalogFor(sd69);
            }
        }
        if (cat69 == null || cat69[1] == null || cat69[1].length() == 0) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "pl_no_play"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // The anchor's display position, re-resolved by identity —
        // the dialog can sit open across a search/sort/refresh, so
        // the captured position may have moved (the v13.13 rule).
        int anchorIdx69 = -1;
        for (int i69 = 0; i69 < items.size(); i69++) {
            FavTalkRail.Card r69 = items.get(i69);
            if (r69 == null || r69.e == null || r69.e.url == null) continue;
            if (SiteHttp.sameTalk(r69.e.url, streamUrl)) { anchorIdx69 = i69; break; }
        }
        // Display-order identity list from the anchor onward (the
        // PlayFromHere pick's input), each row carrying its own
        // sayadaw + title for the v2 lines. A path-form row reads as
        // null and the pick skips it (a card is a stream URL by
        // FavAll's construction, but the gate costs nothing).
        java.util.List<String> ids69 = new java.util.ArrayList<String>();
        java.util.List<String> sds69 = new java.util.ArrayList<String>();
        java.util.List<String> titles69 = new java.util.ArrayList<String>();
        if (anchorIdx69 < 0) {
            // Anchor gone from the list while the dialog sat open:
            // queue just the anchor itself, never a stale range.
            ids69.add(SiteHttp.identityUrl(streamUrl));
            sds69.add(sd69);
            titles69.add(c.e.title);
        } else {
            for (int i69 = anchorIdx69; i69 < items.size(); i69++) {
                FavTalkRail.Card r69 = items.get(i69);
                if (r69 == null || r69.e == null) { ids69.add(null); sds69.add(""); titles69.add(""); continue; }
                String u69 = r69.e.url;
                ids69.add(u69 != null && u69.length() > 0 && !u69.startsWith("/") ? SiteHttp.identityUrl(u69) : null);
                sds69.add(r69.e.sayadaw != null ? r69.e.sayadaw : "");
                titles69.add(r69.e.title != null ? r69.e.title : "");
            }
            // The anchor's own line carries the healed sayadaw.
            sds69.set(0, sd69);
        }
        // The anchor sayadaw's current queue, as identities — the
        // pick skips talks already queued (they stay where they
        // are). Saved lines are carried verbatim (histQueueAdd's
        // discipline), so a foreign line the Room drawer moved
        // mid-queue survives the write.
        java.util.Set<String> queuedIds69 = new java.util.HashSet<String>();
        java.util.ArrayList<String> saved69 = new java.util.ArrayList<String>();
        try {
            String savedRaw69 = Prefs.savedQueue(this, sd69);
            if (savedRaw69 != null && savedRaw69.length() > 0) {
                for (String ln69 : savedRaw69.split("\n")) {
                    if (ln69.length() == 0) continue;
                    saved69.add(ln69);
                    QueueEntry qe69 = QueueEntry.parse(ln69);
                    if (qe69 != null && qe69.url.length() > 0)
                        queuedIds69.add(SiteHttp.identityUrl(qe69.url));
                }
            }
        } catch (Exception ignored) {}
        java.util.List<Integer> picked69 = PlayFromHere.pick(ids69,
                SiteHttp.identityUrl(streamUrl), queuedIds69,
                favPlayingUrl, favPlayingLibUrl);
        java.util.List<String> run69 = HistPlayFromHere.lines(ids69, sds69, titles69, picked69);
        int added69 = 0;
        for (String ln69 : run69) {
            QueueEntry qe69 = QueueEntry.parse(ln69);
            if (qe69 == null || qe69.url.length() == 0) continue;
            boolean dup69 = false;
            for (String ex69 : saved69) {
                QueueEntry qx69 = QueueEntry.parse(ex69);
                if (qx69 != null && SiteHttp.sameTalk(qx69.url, qe69.url)) { dup69 = true; break; }
            }
            if (dup69) continue;
            saved69.add(ln69);
            added69++;
        }
        Prefs.saveQueue(this, sd69, saved69);
        android.widget.Toast.makeText(FavouritesActivity.this, added69 > 0
                ? Lang.t(FavouritesActivity.this, "queue_plain_prefix") + added69 + Lang.t(FavouritesActivity.this, "queue_added_suffix")
                : Lang.t(FavouritesActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
        // Open the Room on the anchor (the row tap's own intent,
        // built inline — see the header note): a queued anchor plays
        // from its queue spot.
        android.content.Intent it69 = new android.content.Intent(this, RoomActivity.class);
        it69.putExtra("name", sd69);
        it69.putExtra("group", cat69[0]);
        it69.putExtra("url", cat69[1]);
        it69.putExtra("photo", cat69[2]);
        it69.putExtra("autoUrl", c.e.url);
        it69.putExtra("autoTitle", c.e.title);
        Fx.tap(this);
        Fx.go(this, it69);
    }

    // v15.83 (slice 2160): "Queue from here" — the bulk queue verb the Favourites row menu lacked that the Downloaded library row menu has carried since before v13.04 (the Talks chooser ported it in v13.04), the Room drawer in-list dialog since v15.82 (slice 2153), the Playlist detail row menu since v15.83 (slice 2157), the History row menu since v15.83 (slice 2158) and the Bookmarks row menu since v15.83 (slice 2159): a starred talk could be queued one by one or played from here, but never queued as a run for later without playing now. playFromHereFav MINUS the Room open, verbatim: queues the run from the long-pressed row onward — in display order, each talk carrying its own sayadaw (the v2 lines, 2030) — into the anchor sayadaw's saved queue, and stops there. No playback: this is the queue-only run; play-from-here is the sibling that plays. The anchor is re-resolved at tap time (the v13.13 rule): the dialog can sit open across a search/sort/refresh, so the captured position may have moved — the scan below matches by identity (SiteHttp.sameTalk over the shown list). A row the scan cannot find falls back to the anchor alone (the chooser's v13.04 fallback shape), never a stale range. Stream anchors only: on this screen the card's URL IS the stream URL, so a path-form row names no stream URL at all — the offer gate enforces it, and the tap re-check refuses it whole (queue_changed); a path-form row BELOW the anchor reads as a null id and the pick skips it. The sounding anchor is refused whole at the offer (the queue branches' rule), NOT skipped-while-below-queue: unlike the Playlist/library shape where a sounding anchor skips only itself, here the offer never appears for a sounding row, so the helper never sees one — and the tap-time re-check below refuses the whole run (queue_changed, no partial queue) if the anchor turned sounding while the dialog sat open. The anchor sayadaw is healed exactly like playFromHereFav (catalog + the sayadawForTalk heal) — the queue key must be the healed name, never a stale banked one. No catalog, no run: the queue is untouched, toast pl_no_play (same as playFromHereFav). The debounce has its own clock (lastFavQueueFromHereMs, the chooser's 700ms rule, v12.28): validity BEFORE consuming the window, so an invalid tap never consumes it. The toast counts written lines only (queue_plain_prefix + N + queue_added_suffix, already_in_queue when nothing new queued). Writes only the anchor sayadaw's saved queue via Prefs.saveQueue (the playFromHereFav write block verbatim); the star set, the resume positions and the played set stay.
    private void queueFromHereFav(final FavTalkRail.Card c) {
        if (items == null || items.isEmpty() || c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long now60 = android.os.SystemClock.uptimeMillis();
        if (now60 - lastFavQueueFromHereMs < 700) return;
        lastFavQueueFromHereMs = now60;
        // Tap-time re-checks (the dialog's own shape): the anchor
        // may have lost its stream URL or its sayadaw while the
        // dialog sat open, turned into the sounding talk, or left
        // the list (un-starred from another screen).
        refreshFavouritesPlayingMarks();
        final String streamUrl = c.e.url;
        if (streamUrl.startsWith("/")) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if ((favPlayingUrl.length() > 0 && SiteHttp.sameTalk(streamUrl, favPlayingUrl))
                || (favPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(streamUrl, favPlayingLibUrl))) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String sd60 = c.e.sayadaw;
        String[] cat60 = catalogFor(sd60);
        if (cat60 == null || cat60[1] == null || cat60[1].length() == 0) {
            String owner60 = Prefs.sayadawForTalk(this, "lib", streamUrl, "");
            if (owner60 != null && owner60.length() > 0 && !owner60.equals(sd60)) {
                sd60 = owner60;
                cat60 = catalogFor(sd60);
            }
        }
        if (cat60 == null || cat60[1] == null || cat60[1].length() == 0) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "pl_no_play"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // The anchor's display position, re-resolved by identity —
        // the dialog can sit open across a search/sort/refresh, so
        // the captured position may have moved (the v13.13 rule).
        int anchorIdx60 = -1;
        for (int i60 = 0; i60 < items.size(); i60++) {
            FavTalkRail.Card r60 = items.get(i60);
            if (r60 == null || r60.e == null || r60.e.url == null) continue;
            if (SiteHttp.sameTalk(r60.e.url, streamUrl)) { anchorIdx60 = i60; break; }
        }
        // Display-order identity list from the anchor onward (the
        // PlayFromHere pick's input), each row carrying its own
        // sayadaw + title for the v2 lines. A path-form row reads as
        // null and the pick skips it (a card is a stream URL by
        // FavAll's construction, but the gate costs nothing).
        java.util.List<String> ids60 = new java.util.ArrayList<String>();
        java.util.List<String> sds60 = new java.util.ArrayList<String>();
        java.util.List<String> titles60 = new java.util.ArrayList<String>();
        if (anchorIdx60 < 0) {
            // Anchor gone from the list while the dialog sat open:
            // queue just the anchor itself, never a stale range.
            ids60.add(SiteHttp.identityUrl(streamUrl));
            sds60.add(sd60);
            titles60.add(c.e.title);
        } else {
            for (int i60 = anchorIdx60; i60 < items.size(); i60++) {
                FavTalkRail.Card r60 = items.get(i60);
                if (r60 == null || r60.e == null) { ids60.add(null); sds60.add(""); titles60.add(""); continue; }
                String u60 = r60.e.url;
                ids60.add(u60 != null && u60.length() > 0 && !u60.startsWith("/") ? SiteHttp.identityUrl(u60) : null);
                sds60.add(r60.e.sayadaw != null ? r60.e.sayadaw : "");
                titles60.add(r60.e.title != null ? r60.e.title : "");
            }
            // The anchor's own line carries the healed sayadaw.
            sds60.set(0, sd60);
        }
        // The anchor sayadaw's current queue, as identities — the
        // pick skips talks already queued (they stay where they
        // are). Saved lines are carried verbatim (histQueueAdd's
        // discipline), so a foreign line the Room drawer moved
        // mid-queue survives the write.
        java.util.Set<String> queuedIds60 = new java.util.HashSet<String>();
        java.util.ArrayList<String> saved60 = new java.util.ArrayList<String>();
        try {
            String savedRaw60 = Prefs.savedQueue(this, sd60);
            if (savedRaw60 != null && savedRaw60.length() > 0) {
                for (String ln60 : savedRaw60.split("\n")) {
                    if (ln60.length() == 0) continue;
                    saved60.add(ln60);
                    QueueEntry qe60 = QueueEntry.parse(ln60);
                    if (qe60 != null && qe60.url.length() > 0)
                        queuedIds60.add(SiteHttp.identityUrl(qe60.url));
                }
            }
        } catch (Exception ignored) {}
        java.util.List<Integer> picked60 = PlayFromHere.pick(ids60,
                SiteHttp.identityUrl(streamUrl), queuedIds60,
                favPlayingUrl, favPlayingLibUrl);
        java.util.List<String> run60 = HistPlayFromHere.lines(ids60, sds60, titles60, picked60);
        int added60 = 0;
        for (String ln60 : run60) {
            QueueEntry qe60 = QueueEntry.parse(ln60);
            if (qe60 == null || qe60.url.length() == 0) continue;
            boolean dup60 = false;
            for (String ex60 : saved60) {
                QueueEntry qx60 = QueueEntry.parse(ex60);
                if (qx60 != null && SiteHttp.sameTalk(qx60.url, qe60.url)) { dup60 = true; break; }
            }
            if (dup60) continue;
            saved60.add(ln60);
            added60++;
        }
        Prefs.saveQueue(this, sd60, saved60);
        android.widget.Toast.makeText(FavouritesActivity.this, added60 > 0
                ? Lang.t(FavouritesActivity.this, "queue_plain_prefix") + added60 + Lang.t(FavouritesActivity.this, "queue_added_suffix")
                : Lang.t(FavouritesActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
    }

    // v15.68 (slice 2069): "Remove from favourites" — the row
    // menu's removal verb (the screen's own: the 2035 header note
    // says stars are removed per-row from the rail/chooser/library
    // menus, and this screen is none of those, so until now a
    // starred talk could only be un-starred by leaving the screen).
    // The tap-time re-check reads the live fav set (readFavTalks)
    // and flips only when the talk is still starred — a dialog left
    // open across an un-star elsewhere must never re-add it (the
    // toggle flips both ways, so a blind toggle is a re-add). The
    // removal is confirmed by re-reading the set after the flip,
    // never by trusting the toggle's return alone. The row leaves
    // the list at once (refresh() re-picks, re-sorts and re-paints
    // the jump target), and the toast names the outcome (the
    // History hs_remove_one shape: removed ? fav_removed :
    // nothing_to_delete). A removed sounding talk keeps playing —
    // the star is not playback — but the Room's card star flips,
    // so the notification card is refreshed (the Talks v10.03
    // shape, through this screen's own sounding identities).
    // v15.69 (slice 2073): "Add to queue" — the row menu's queue
    // verb (History's menu_queue_add, v13.77, on this screen's
    // menu): append the row's talk to the row's sayadaw queue as a
    // v2 line (FavQueue.add — identity URL, the row's sayadaw, the
    // row's title, the histQueueAdd shape) and leave it there for
    // the next Room session on that sayadaw — the sounding talk
    // keeps playing, nothing opens. The offer hid sounding and
    // path-form rows (FavQueue.queueKey); the tap-time re-checks
    // refuse with queue_changed when the row turned sounding or
    // lost its stream URL while the dialog sat open (the 2069
    // shape), and the dup check answers already_in_queue. No
    // catalog heal: the queue key is the row's sayadaw as listed
    // (histQueueAdd's discipline — no Room opens here, so no
    // healed-name coupling like playFromHereFav's). No list
    // refresh: queueing changes no row the list renders (there is
    // no queued mark on this screen — the 2042 mark renders the
    // sounding talk only).
    private void queueAddFav(final FavTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        refreshFavouritesPlayingMarks();
        final String url = c.e.url;
        final String sd = c.e.sayadaw;
        if (url.startsWith("/") || sd == null || sd.length() == 0
                || FavPlayAll.isSounding(url, favPlayingUrl, favPlayingLibUrl)) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.ArrayList<String> saved73 = new java.util.ArrayList<String>();
        try {
            String savedRaw73 = Prefs.savedQueue(this, sd);
            if (savedRaw73 != null && savedRaw73.length() > 0) {
                for (String ln73 : savedRaw73.split("\n")) {
                    if (ln73.length() == 0) continue;
                    saved73.add(ln73);
                }
            }
        } catch (Exception ignored) {}
        java.util.ArrayList<String> next73 = FavQueue.add(url, sd, c.e.title, saved73);
        if (next73 == null) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "already_in_queue"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.saveQueue(this, sd, next73);
        android.widget.Toast.makeText(FavouritesActivity.this,
                Lang.t(FavouritesActivity.this, "added_to_queue"),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // v15.69 (slice 2073): "Remove from queue" — the
    // state-dependent twin of queueAddFav above (History's
    // menu_queue_remove, v13.77): drop the row's talk from the
    // row's sayadaw queue (FavQueue.remove — sameTalk over parsed
    // entries; unparseable lines are carried, never dropped on a
    // guess) and leave the star alone — queueing is not
    // favouriting, so removing from the queue never un-stars the
    // row. Same tap-time re-checks as the add (sounding /
    // path-form / blank sayadaw refuse with queue_changed); a talk
    // that left the queue while the dialog sat open answers
    // queue_changed too (FavQueue.remove returns null when absent).
    // No list refresh: the queue change renders nowhere on this
    // screen.
    private void queueRemoveFav(final FavTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        refreshFavouritesPlayingMarks();
        final String url = c.e.url;
        final String sd = c.e.sayadaw;
        if (url.startsWith("/") || sd == null || sd.length() == 0
                || FavPlayAll.isSounding(url, favPlayingUrl, favPlayingLibUrl)) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.ArrayList<String> saved73 = new java.util.ArrayList<String>();
        try {
            String savedRaw73 = Prefs.savedQueue(this, sd);
            if (savedRaw73 != null && savedRaw73.length() > 0) {
                for (String ln73 : savedRaw73.split("\n")) {
                    if (ln73.length() == 0) continue;
                    saved73.add(ln73);
                }
            }
        } catch (Exception ignored) {}
        java.util.ArrayList<String> next73 = FavQueue.remove(url, saved73);
        if (next73 == null) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.saveQueue(this, sd, next73);
        android.widget.Toast.makeText(FavouritesActivity.this,
                Lang.t(FavouritesActivity.this, "removed_from_queue"),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // v15.70 (slice 2078): "Go to sayadaw's talks" — the row
    // menu's navigation verb (History's menu_go_sayadaw, v15.56
    // slice 1995, on this screen's menu; the Downloaded row's
    // 1944 verb through the same GoSayadaw core; the Bookmarks
    // row's 2077 verb, BmGo): re-resolve the row's sayadaw
    // through the bundled catalog at tap time (catalogFor —
    // cached, canonicalized, so a renamed sayadaw still
    // resolves; the offer and the tap can never disagree),
    // toast no_sayadaw_page on a failed re-read, else open that
    // sayadaw's Talks list inside the app with Main's own intent
    // extras (name/group/url/photo). The name is the row's own
    // sayadaw (the catalog entry carries no name slot). A
    // navigation mutates nothing: no favourite, queue or resume
    // change, no pause guard (no sheet is left to return from).
    private void goToSayadawFav(final FavTalkRail.Card c) {
        if (c == null || c.e == null || c.e.sayadaw == null || c.e.sayadaw.length() == 0) return;
        final String sd = c.e.sayadaw;
        String[] cat78 = catalogFor(sd);
        if (!FavGo.canOffer(sd, cat78)) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "no_sayadaw_page"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent gi78 = new android.content.Intent(FavouritesActivity.this, TalksActivity.class);
        gi78.putExtra("name", sd);
        gi78.putExtra("group", GoSayadaw.group(cat78));
        gi78.putExtra("url", GoSayadaw.page(cat78));
        gi78.putExtra("photo", GoSayadaw.photo(cat78));
        Fx.tap(FavouritesActivity.this);
        Fx.go(FavouritesActivity.this, gi78);
    }

    // v15.71 (slice 2083): "Share talk" — the row menu's share
    // verb (History's menu_share, v13.77, on this screen's menu;
    // the chooser's long-press dialog shares first, v7.82, and
    // the library row menu has shared since v9.55; the New talks
    // row menu shares since slice 2080): re-resolve the row to
    // its stream URL at tap time (favStreamUrlFor — the offer
    // and the tap can never disagree), toast
    // talk_no_longer_in_list when the file behind a path-form
    // row no longer resolves (the History file-share's stale
    // toast), else share the talk's text through the Android
    // sheet — sayadaw — title + stream URL + the
    // dhammadownload.com credit (FavShare.shareText, the History
    // text shape) — chooser titled share_talk. A share mutates
    // nothing: no favourite, queue or resume change; no pause
    // guard (no sheet is left to return from); the sounding row
    // keeps it (sharing never touches playback).
    private void shareTalkFav(final FavTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        final String streamUrl = favStreamUrlFor(c.e);
        if (!FavShare.canOffer(streamUrl)) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent send83 = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send83.setType("text/plain");
        send83.putExtra(android.content.Intent.EXTRA_TEXT,
                FavShare.shareText(c.e.sayadaw, c.e.title, streamUrl,
                        Lang.t(FavouritesActivity.this, "share_source")));
        Fx.tap(FavouritesActivity.this);
        startActivity(android.content.Intent.createChooser(send83,
                Lang.t(FavouritesActivity.this, "share_talk")));
    }

    // v15.79 (slice 2133): "Share at this minute" — the minute-share verb the Favourites row menu lacked that the Favourite-talks rail has had since v15.59 (slice 2011), the Bookmarks rail since v15.55 (slice 1993), the Downloaded library row since slice 2012, the Talks chooser since slice 2013, the Room drawer rows since slices 2018/2019 and the Bookmarks row menu since slice 2132: the row renders its saved spot ("\u21ba 12:34 \u00b7 title", FavTalkRail.fmtTime(c.posMs)) but the dialog's plain share carried the bare title, so a friend got no minute at all. Same verb through the same BmShare.shareAtMinuteText core (the 1993 text contract, unchanged), so the surfaces can never disagree. The link stays the talk's original link (no minute in the URL — the site serves plain .mp3 links, nothing to deep-link into); the minute lives in the text. Chooser title is share_talk, like the plain share. Mirrors shareTalkFav above: the same guards (null/blank card or URL returns, unresolvable URL toasts talk_no_longer_in_list — the share already sets that precedent), the same Fx.tap, the same chooser. No tap-time re-check of the minute: it is the card's captured saved spot (FavTalkRail.Card.posMs is final, > 5000 by the offer gate); the only writer of the spot is the talk's own Room session, which cannot start while the modal menu sits open, and a stale minute in shared text is harmless. A share mutates nothing — no favourite, queue or resume change; no pause guard (the text share beside it sets none). The sounding row keeps it (sharing never touches playback, and the spot is frozen, not live).
    private void shareAtMinuteFav(final FavTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        final String streamUrl = favStreamUrlFor(c.e);
        if (!FavShare.canOffer(streamUrl)) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent send33 = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send33.setType("text/plain");
        send33.putExtra(android.content.Intent.EXTRA_TEXT,
                BmShare.shareAtMinuteText(c.e.sayadaw, c.e.title, streamUrl, c.posMs,
                        Lang.t(FavouritesActivity.this, "share_source")));
        Fx.tap(FavouritesActivity.this);
        startActivity(android.content.Intent.createChooser(send33,
                Lang.t(FavouritesActivity.this, "share_talk")));
    }

    // v15.79 (slice 2134): "Download" — the download verb the Favourites row menu lacked that the History row menu has had since v13.77, the Favourite-talks rail since v15.57 (slice 2000), the Talks chooser since v11.70 and the Room drawer rows since v11.70: a starred talk could be shared, copied, filed or opened, but never saved for offline listening without leaving the screen. Same verb through the same rules, so the surfaces can never disagree: tap-time re-checks (a Wi-Fi job can land, or another path can queue this talk, while the dialog sits open), a Wi-Fi-only DownloadManager request (NETWORK_WIFI, so a tap here can never silently spend mobile data — the data-saver rule), the filename from favDownloadFileNameFor, and the record under DlLock in ONE apply (favRecordDownload below — dlMap + dlSayadaw + dlQueued, identityUrl banking). Mirrors History's dl_btn_download dispatch verbatim, with the Favourites-local read side (favStreamUrlFor + favIsDownloaded + favIsDlQueued + favDownloadFileNameFor). The blank-URL guard toasts talk_no_longer_in_list — the share helpers' precedent: Favourites re-resolves at tap time, so a file deleted while the menu sat open must toast, never enqueue a blank URL. No sounding gate (History offers it on sounding rows too); no pause guard (no sheet is left to return from). The adapter repaint + success toast are History's: Wi-Fi shows "\u2B07 title + dl_downloading_suffix", mobile data shows dl_room_wifi_queued.
    private void downloadTalkFav(final FavTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        final String streamUrl = favStreamUrlFor(c.e);
        if (streamUrl == null || streamUrl.length() == 0) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // Tap-time re-checks (Talks v11.70): a Wi-Fi job can land, or another path can queue this talk, while the dialog sits open.
        if (favIsDownloaded(streamUrl)) {
            if (adapter != null) adapter.notifyDataSetChanged();
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "downloaded_talk"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (favIsDlQueued(streamUrl)) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "dl_already_queued"),
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String file = favDownloadFileNameFor(streamUrl);
        try {
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            android.app.DownloadManager.Request req =
                    new android.app.DownloadManager.Request(
                            android.net.Uri.parse(SiteHttp.siteUrl(streamUrl)));
            req.setTitle(c.e.title != null && c.e.title.length() > 0 ? c.e.title : streamUrl);
            req.setNotificationVisibility(android.app.DownloadManager.Request
                    .VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            // Wi-Fi-only, exactly like Talks' chooser enqueue and History's row enqueue — on Wi-Fi it starts at once, on mobile data it waits for Wi-Fi, so a tap here can never silently spend mobile data.
            req.setAllowedNetworkTypes(android.app.DownloadManager.Request.NETWORK_WIFI);
            req.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, "Dhamma/" + file);
            dm.enqueue(req);
            favRecordDownload(streamUrl, file, c.e.sayadaw);
        } catch (Exception ex) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "download_failed") + Fx.errDetail(ex),
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (Fx.stripIsWifi(FavouritesActivity.this))
            android.widget.Toast.makeText(FavouritesActivity.this,
                    "\u2B07 " + (c.e.title != null && c.e.title.length() > 0 ? c.e.title : streamUrl)
                            + Lang.t(FavouritesActivity.this, "dl_downloading_suffix"),
                    android.widget.Toast.LENGTH_SHORT).show();
        else
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "dl_room_wifi_queued"),
                    android.widget.Toast.LENGTH_LONG).show();
    }

    // v15.71 (slice 2083): the row's stream URL for the share
    // verb — the histStreamUrlFor shape (v13.77): a URL-form row
    // canonicalises to its identity form; a path-form row (a
    // downloaded file) resolves through the dlMap reverse lookup
    // with the exactly-one-claimant rule (v8.44 — a dual-claimed
    // file names no talk rather than the wrong one), so a deleted
    // file with no claimant offers no stream verb. The dlMap is
    // parsed once per call (dlMapNow2038) and only for path-form
    // rows — URL-form rows never pay the parse.
    private String favStreamUrlFor(Prefs.HistEntry e) {
        if (e == null || e.url == null || e.url.length() == 0) return "";
        if (!e.url.startsWith("/")) return SiteHttp.identityUrl(e.url);
        try {
            return FavShare.resolve(e.url, dlMapNow2038(getSharedPreferences("dhamma", MODE_PRIVATE)));
        } catch (Exception ignored) { return ""; }
    }

    // v15.71 (slice 2085): "Copy link" — the row menu's clipboard
    // verb (History's dl_menu_copy_link, v15.53 slice 1981, on
    // this screen's menu; the library row menu has copied since
    // v6.56, the Room drawer since slice 1975, the Talks chooser
    // since slice 1977, the New talks row menu since slice 2081
    // — all through the same RoomCopyLink core, so the surfaces
    // can never disagree): re-resolve the row to its stream URL
    // at tap time (favStreamUrlFor — the offer and the tap can
    // never disagree), toast talk_no_longer_in_list when the file
    // behind a path-form row no longer resolves (this screen's
    // share already sets that toast precedent; History's copy
    // stays silent, but a visible menu verb that silently does
    // nothing is a dead tap), else copy the talk's own original
    // link verbatim (the v6.56 rule — never a bare title, never
    // a guessed link) with the v6.56 clipboard label + toast
    // (RoomCopyLink.clipText / toastText). A copy mutates
    // nothing: no favourite, queue or resume change; no pause
    // guard (no sheet is left to return from); the sounding row
    // keeps it (copying never touches playback).
    private void copyTalkLinkFav(final FavTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        final String streamUrl = favStreamUrlFor(c.e);
        if (!RoomCopyLink.canOffer(streamUrl)) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.content.ClipboardManager cm85 =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm85 != null)
                cm85.setPrimaryClip(android.content.ClipData.newPlainText(
                        Lang.t(FavouritesActivity.this, "clip_talk"), RoomCopyLink.clipText(streamUrl)));
            android.widget.Toast.makeText(FavouritesActivity.this,
                    RoomCopyLink.toastText(Lang.t(FavouritesActivity.this, "link_copied_prefix"), c.e.title, streamUrl),
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    // v15.72 (slice 2089): "Share file" — the row menu's
    // file-share verb (History's dl_menu_share_file, v15.53
    // slice 1982, on this screen's menu; the library row menu
    // has shared its file since v15.48 slice 1954, the Room
    // drawer since slice 1955, the Talks chooser since slice
    // 1956, the History row menu since slice 1982 and the New
    // talks row menu since v15.71 slice 2087 — all through the
    // same TalkFileShare core, so the surfaces can never
    // disagree): re-resolve the row to its stream URL at tap
    // time (favStreamUrlFor — the offer and the tap can never
    // disagree), re-check the download gate fresh
    // (favIsDownloaded + favDownloadFileNameFor — the History
    // v13.77 pair, mirrored), and send the actual .mp3 through
    // the Android sheet as a TalkFileProvider content:// URI
    // with a one-shot read grant, type audio/mpeg (a file://
    // URI is refused on API 24+), chooser titled share_talk.
    // A name that no longer resolves to a file on disk toasts
    // talk_no_longer_in_list, never a guessed URI. A share
    // mutates nothing: no favourite, queue or resume change;
    // no pause guard (History's file share sets none; the text
    // share beside it sets none); the sounding row keeps it
    // (sharing never touches playback). The shared unit is the
    // talk's downloaded FILE, not the link (the copy beside it
    // sends the link; this verb sends the bytes).
    private void shareFileFav(final FavTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        final String streamUrl = favStreamUrlFor(c.e);
        java.io.File dir89 = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma");
        String fn89 = favIsDownloaded(streamUrl) ? favDownloadFileNameFor(streamUrl) : null;
        String uri89 = TalkFileShare.shareUriFor(dir89, fn89);
        if (uri89 == null) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent send89 = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send89.setType("audio/mpeg");
        send89.putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse(uri89));
        send89.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(android.content.Intent.createChooser(send89,
                Lang.t(FavouritesActivity.this, "share_talk")));
    }

    // v15.89 (slice 2195): the downloaded-badge snapshot — the set is the unfiltered pick's downloaded stream addresses, built from the unfiltered pick through the same gate the row menu's download offer reads, so the badge and the offer can never disagree about a talk; the map is parsed once per build, never per row. The refresh adopts it; the row predicate resolves the row to its stream address the same way the tick does (a downloaded path-form row claims its stream address through the exactly-one-claimant resolve) and tests membership. A null list reads empty; a null entry or an unresolvable stream is skipped; the badge paints on the sounding row too — downloaded is a property of the talk, not a state. The badge alone does not gold the row — only a sounding row turns gold.
    private java.util.Set<String> favDlSetNow() {
        java.util.Set<String> out95 = new java.util.HashSet<String>();
        if (favAllItems == null) return out95;
        try {
            android.content.SharedPreferences sp95 =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONObject map95 = new org.json.JSONObject(sp95.getString("dlMap", "{}"));
            for (FavTalkRail.Card r95 : favAllItems) {
                if (r95 == null || r95.e == null) continue;
                String stream95 = favStreamUrlFor(r95.e);
                if (stream95 == null || stream95.length() == 0) continue;
                if (favIsDownloaded(stream95, map95)) {
                    String id95 = SiteHttp.identityUrl(stream95);
                    if (id95 != null) out95.add(id95);
                }
            }
        } catch (Exception ignored) {}
        return out95;
    }

    private void refreshFavDlSet() { favDlSet95 = favDlSetNow(); }

    private boolean favRowDl(Prefs.HistEntry e) {
        if (e == null) return false;
        String stream95 = favStreamUrlFor(e);
        return stream95.length() > 0 && favDlSet95 != null
                && favDlSet95.contains(SiteHttp.identityUrl(stream95));
    }

    // v15.72 (slice 2089): History histIsDownloaded (v13.77),
    // Favourites-local — the dlMap mapped file with the v13.66
    // length gate (a 0-byte file is not downloaded), plus the
    // plain basename leg for files the app enqueued under a
    // stripped name. The offer and the tap both read it fresh,
    // so a download landing while the dialog sits open can
    // never be missed and a deletion can never be served.
    private boolean favIsDownloaded(String url) {
        if (url == null || url.length() == 0) return false;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
            return favIsDownloaded(url, map);
        } catch (Exception ignored) { return false; }
    }

    // v15.89 (slice 2195): the same gate with a caller-supplied map — the snapshot builder parses the map once per build and tests every row against it, instead of re-parsing per row; the one-argument form above keeps its exact behaviour (a fresh map per call) for the menu offers.
    private boolean favIsDownloaded(String url, org.json.JSONObject map) {
        if (url == null || url.length() == 0 || map == null) return false;
        try {
            java.io.File dir = new java.io.File(android.os.Environment
                    .getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma");
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

    // v15.72 (slice 2089): History histDownloadFileNameFor
    // (v13.77), Favourites-local — the dlMap name wins, else the
    // basename stripped of ?/# with .mp3 guaranteed, and a
    // basename another URL's download claims takes the 8-hex MD5
    // suffix (v8.40 claim-alone rule).
    private String favDownloadFileNameFor(String url) {
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

    // v15.79 (slice 2134): History histIsDlQueued (v13.77),
    // Favourites-local — fresh prefs read at offer/tap time, so a
    // job queued from the Room a second earlier still blocks a
    // double enqueue. Raw-equals scan like History's — the banked
    // form is identityUrl and the offer and the tap read the same
    // resolved stream URL.
    private boolean favIsDlQueued(String url) {
        if (url == null) return false;
        try {
            org.json.JSONArray qa = new org.json.JSONArray(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlQueued", "[]"));
            for (int i = 0; i < qa.length(); i++)
                if (url.equals(qa.optString(i))) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // v15.79 (slice 2134): History histRecordDownload (v13.77),
    // Favourites-local — dlMap, dlSayadaw and dlQueued land in ONE
    // apply under DlLock.LOCK (v13.68/v13.70), the URL banked in
    // identity form (v13.71).
    private void favRecordDownload(String url, String file, String sd) {
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

    private void removeFavFromMenu(final FavTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        boolean starred = false;
        try {
            for (String f : readFavTalks()) {
                if (f != null && SiteHttp.sameTalk(f, c.e.url)) { starred = true; break; }
            }
        } catch (Exception ignored) {}
        boolean removed = false;
        if (starred) {
            Fx.toggleFavTalk(this, c.e.url);
            boolean stillStarred = false;
            try {
                for (String f : readFavTalks()) {
                    if (f != null && SiteHttp.sameTalk(f, c.e.url)) { stillStarred = true; break; }
                }
            } catch (Exception ignored) {}
            removed = !stillStarred;
        }
        refreshFavouritesPlayingMarks();
        if (removed) {
            boolean cardTalk = (favPlayingUrl.length() > 0 && SiteHttp.sameTalk(c.e.url, favPlayingUrl))
                    || (favPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(c.e.url, favPlayingLibUrl));
            if (cardTalk) {
                android.content.Intent rsv = new android.content.Intent(this, AudioService.class);
                rsv.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                startService(rsv);
            }
        }
        refresh();
        android.widget.Toast.makeText(this,
                Lang.t(this, removed ? "fav_removed" : "nothing_to_delete"),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // v15.72 (slice 2091): "Add to playlist" — the row menu's
    // playlist verb (the Room's showPlaylistAddDialog, v12.65–
    // v12.80, on this screen's menu; the Room's dialog has no
    // entry point anywhere in the app — defined once, called
    // zero times — so this is the verb's first live offer, on
    // the screen of favourite talks, where "my favourite talk
    // -> my morning / bedtime list" is the natural flow;
    // premium backlog #2, the Spotify / YouTube Music /
    // Apple Music row-menu verb): re-resolve the row to its
    // stream URL at tap time (favStreamUrlFor — the offer and
    // the tap can never disagree), toast
    // talk_no_longer_in_list when the file behind a path-form
    // row no longer resolves (the share/copy/file precedent on
    // this screen), heal the sayadaw exactly like the row tap
    // (catalogFor + the sayadawForTalk heal — a stale banked
    // sayadaw would strand the playlist item under the wrong
    // owner, the v12.78 lesson; the Room's playlistOwnerFor
    // heals the same way), then offer the listener's lists
    // (Prefs.playlistNames — the seed lists are there from the
    // first read) plus a "new list" row (pl_new, last). A tap
    // files the talk through Prefs.playlistAdd and toasts the
    // v12.80 result (FavPlAdd.toastKey: added / already there
    // / full / could-not-create); the new-list row opens the
    // name dialog. An add mutates no favourite, queue or
    // resume state; no pause guard (no sheet is left to
    // return from); the sounding row keeps it (filing never
    // touches playback).
    private void addToPlaylistFav(final FavTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        final String streamUrl = favStreamUrlFor(c.e);
        if (!FavPlAdd.canOffer(streamUrl)) {
            android.widget.Toast.makeText(FavouritesActivity.this,
                    Lang.t(FavouritesActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String sd91 = c.e.sayadaw;
        String[] cat91 = catalogFor(sd91);
        if (cat91 == null || cat91[1] == null || cat91[1].length() == 0) {
            String owner91 = Prefs.sayadawForTalk(this, "lib", streamUrl, "");
            if (owner91 != null && owner91.length() > 0 && !owner91.equals(sd91)) {
                sd91 = owner91;
            }
        }
        final String title91 = c.e.title != null ? c.e.title : "";
        final String sdF91 = sd91;
        final java.util.List<String> names91 = Prefs.playlistNames(this);
        final String[] items91 = new String[names91.size() + 1];
        for (int i91 = 0; i91 < names91.size(); i91++) items91[i91] = names91.get(i91);
        items91[names91.size()] = Lang.t(this, "pl_new");
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "pl_choose"))
                .setItems(items91, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (FavPlAdd.isNewRow(which, names91.size())) {
                            showNewPlaylistFav(streamUrl, title91, sdF91);
                            return;
                        }
                        int r91 = Prefs.playlistAdd(FavouritesActivity.this,
                                names91.get(which), streamUrl, sdF91, title91);
                        android.widget.Toast.makeText(FavouritesActivity.this,
                                Lang.t(FavouritesActivity.this, FavPlAdd.toastKey(r91)),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // v15.72 (slice 2091): the "new list" dialog for the
    // playlist verb — the Room's showNewPlaylistDialog
    // (v12.80), on this screen: a name field (pl_name_hint),
    // a Create button (pl_create) and a cancel (btn_no_clear);
    // an empty name re-prompts with the hint toast; the name
    // caps at 40 chars (PLAYLIST_MAX_NAME); create is a
    // no-op for an existing name, so a repeat name just adds
    // to that list; at the 20-list cap a genuinely new name
    // toasts pl_limit (FavPlAdd.limitToastKey), any other
    // create failure pl_no_create — neither falls through to
    // the add (the v12.80 leg). The add toasts the same
    // v12.80 result as the choose dialog.
    private void showNewPlaylistFav(final String url, final String title, final String sd) {
        final android.widget.EditText et91 = new android.widget.EditText(this);
        et91.setHint(Lang.t(this, "pl_name_hint"));
        et91.setSingleLine(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "pl_new"))
                .setView(et91)
                .setPositiveButton(Lang.t(this, "pl_create"),
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        String name91 = et91.getText().toString().trim();
                        if (name91.length() == 0) {
                            android.widget.Toast.makeText(FavouritesActivity.this,
                                    Lang.t(FavouritesActivity.this, "pl_name_hint"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (name91.length() > 40) name91 = name91.substring(0, 40);
                        boolean created91 = Prefs.playlistCreate(FavouritesActivity.this, name91);
                        java.util.List<String> held91 = Prefs.playlistNames(FavouritesActivity.this);
                        String limit91 = FavPlAdd.limitToastKey(created91,
                                held91.contains(name91), held91.size());
                        if (limit91 != null) {
                            android.widget.Toast.makeText(FavouritesActivity.this,
                                    Lang.t(FavouritesActivity.this, limit91),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        int r91 = Prefs.playlistAdd(FavouritesActivity.this, name91, url, sd, title);
                        android.widget.Toast.makeText(FavouritesActivity.this,
                                Lang.t(FavouritesActivity.this, FavPlAdd.toastKey(r91)),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(Lang.t(this, "btn_no_clear"), null)
                .show();
    }

    private void playFav(final FavTalkRail.Card c, android.view.View v) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastFavTapMs < 700) return;
        lastFavTapMs = now;
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
        // The openRecentTalk1930 shape (the rail card tap's own
        // route, v15.50): the Room intent resumes from the saved
        // spot (v1.56 re-entry), so the tap lands where the
        // listener left off — no resume-spot write, a star is not
        // a spot. Fx.tap + Fx.go, the BookmarksActivity row-tap
        // shape (2033).
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

    // v15.50 (slice 1964): the raw favTalks set for the rail — the
    // store's own unordered set; all matching lives in
    // FavAll.pickAll. Copied before use (the Prefs.java:386 rule: a
    // getStringSet result must never be mutated in place). Fail
    // closed to an empty set — a torn write is an empty screen for
    // one pass, never a crash.
    // v15.63 (slice 2038): real mini-player on this screen — a
    // faithful mirror of History's v13.54 strip (the same strip as
    // Talks' v4.31). The v13.55 row-mark head lives in
    // syncFavouritesStrip below since v15.64 (slice 2042). Live session:
    // title + star/sleep/download/queue/ended marks, sayadaw + clock
    // sub line, seekable progress, play/pause glyph, portrait with
    // the sounding gold ring. Dead session with a saved spot (>5s):
    // the resume strip (Room talk or downloaded file); anything
    // else: hidden, so the favourites stay list-only. The two
    // resume-match computations delegate to the pure StripResume
    // core; every other branch is History's body verbatim, renamed.
    // v15.64 (slice 2042): sounding identities for the row mark —
    // History's refreshHistoryPlayingMarks, minus the queue cache
    // (Favourites has no queue marks). Room: liveSession +
    // liveHasTrack (v10.43 bound-trackless stop: a stopped talk is
    // never the playing talk) + lastKind room -> lastUrl. Library:
    // same live guards + lastKind lib -> lastLibPath, plus the dlMap
    // reverse lookup for URL-form rows (exactly-one claimant, v8.44
    // — a dual-claimed file names no talk rather than the wrong one).
    // Prefs only, never per bind.
    // v15.85 (slice 2171): the listened-tick snapshot (History v13.77, verbatim shape) — favPlayedSetNow copies the playedTalks set (the SAME set the 2140 mark verb reads, so the tick and the menu label can never disagree); refreshFavPlayedSet adopts it; favRowPlayed resolves the row's stream URL through favStreamUrlFor (a downloaded path-form row claims its stream URL, the 2084 exactly-one-claimant resolve) and tests membership, so a starred talk ticks exactly when its stream twin does. Null stream names no talk.
    private java.util.Set<String> favPlayedSetNow() { return readPlayedTalksFav(); }

    private void refreshFavPlayedSet() { favPlayedSet = favPlayedSetNow(); }

    private boolean favRowPlayed(Prefs.HistEntry e) { if (e == null) return false; String stream = favStreamUrlFor(e); return stream.length() > 0 && favPlayedSet != null && favPlayedSet.contains(stream); }

    // v15.90 (slice 2199): the duration snapshot (the chooser 1094 clock shape) — favDurCacheNow parses the durations store once (Prefs.talkDurMap, the talkPosKeysOver shape); refreshFavDurCache adopts it; favRowDurMs resolves the row's stream URL through favStreamUrlFor (a downloaded path-form row claims its stream URL, the 2084 reverse lookup) and reads its length, falling back to the row's own URL for a downloaded row whose length was banked under its path form (talkDurPutBoth banks both forms, the v13.96 rule), so a starred talk clocks exactly when its stream twin does. Null stream names no talk; an unknown length reads 0 — the clock hides, never guesses.
    private java.util.Map<String, Integer> favDurCacheNow() { return Prefs.talkDurMap(this); }

    private void refreshFavDurCache() { favDurCache = favDurCacheNow(); }

    private int favRowDurMs(Prefs.HistEntry e) {
        if (e == null || favDurCache == null) return 0;
        Integer d98 = null;
        String stream = favStreamUrlFor(e);
        if (stream != null && stream.length() > 0) d98 = favDurCache.get(SiteHttp.identityUrl(stream));
        if ((d98 == null || d98.intValue() <= 0) && e.url != null) d98 = favDurCache.get(e.url);
        return d98 != null && d98.intValue() > 0 ? d98.intValue() : 0;
    }

    private void refreshFavouritesPlayingMarks() {
        favPlayingUrl = "";
        favPlayingLibUrl = "";
        favPlayingLibPath = "";
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (AudioService.liveSession && AudioService.liveHasTrack
                    && "room".equals(sp.getString("lastKind", "")))
                favPlayingUrl = sp.getString("lastUrl", "");
            if (AudioService.liveSession && AudioService.liveHasTrack
                    && "lib".equals(sp.getString("lastKind", ""))) {
                favPlayingLibPath = sp.getString("lastLibPath", "");
                String libPath = favPlayingLibPath;
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
                    if (matches == 1) favPlayingLibUrl = hit;
                }
            }
        } catch (Exception ignored) {}
    }

    private void syncFavouritesStripPhoto(String name) {
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
        if (ringing != lastFavStripPhotoRing) {
            lastFavStripPhotoRing = ringing;
            if (ring != null) {
                // v14.16 (slice 1670): the ring crossfades in/out
                // instead of snapping — see Fx.crossfadeRing.
                Fx.crossfadeRing(ring, ringing ? R.drawable.shelf_ring_playing : 0);
            }
        }
        if (name == null || name.length() == 0) {
            favStripPhotoName = null;
            if (ring != null) ring.setVisibility(android.view.View.GONE);
            else pv.setVisibility(android.view.View.GONE);
            return;
        }
        if (ring != null) ring.setVisibility(android.view.View.VISIBLE);
        else pv.setVisibility(android.view.View.VISIBLE);
        if (name.equals(favStripPhotoName)) return;
        boolean firstPhoto = favStripPhotoName == null;
        favStripPhotoName = name;
        String url = AudioService.stripPhotoUrlForSayadaw(FavouritesActivity.this, name);
        // v14.22 (slice 1676): the photo crossfades on a sayadaw
        // change instead of swapping — see Fx.crossfadeStripPhoto.
        Fx.crossfadeStripPhoto((CircleImageView) pv, url, firstPhoto, new Runnable() {
            @Override public void run() {
                ImageLoader.load(FavouritesActivity.this, url, name, (CircleImageView) pv);
            }
        });
    }

    // v15.40 (slice 1907): the Favourites resume strip's title melts on a
    // structural change instead of snapping — the mirror of Main's
    // v14.70 meltResumeStripTitle70. The 1s tick re-runs
    // syncFavouritesStrip in resume mode, and the title carries the
    // download mark (stripResumeDownloadMark / the lib branch's inline
    // check), so a download landing while Favourites sits open flipped
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
    private String favResumeStripTitle = null;
    private void meltResumeStripTitleFav(android.widget.TextView tx, String want) {
        if (tx == null || want == null) return;
        Object aObjHs = tx.getTag(R.id.stripTextAnim);
        if (aObjHs instanceof android.animation.Animator) ((android.animation.Animator) aObjHs).cancel();
        String prevHs = favResumeStripTitle;
        favResumeStripTitle = want;
        if (prevHs == null) { tx.setText(want); return; }
        if (prevHs.equals(want)) return;
        tx.setText(want);
        if (tx.getVisibility() == android.view.View.VISIBLE && tx.getAlpha() >= 0.999f) Fx.fadeIn(tx, 250);
    }

    private void syncFavouritesStrip() {
        // v15.64 (slice 2042): playing-row mark — rebind once when the sounding talk changes under the list
        // (History v13.55, Talks v4.89/v11.15, same change-only rule): auto-advance, a Next from the strip, a
        // natural end (same URL, liveEnded flipped) or a stop moves the mark at once; steady ticks
        // never touch the adapter, so scrolling stays undisturbed. Runs before the strip's own early
        // returns, so a dead session clears the mark even while the strip hides.
        refreshFavouritesPlayingMarks();
        // v15.85 (slice 2171): the tick snapshot joins the change detector — a tick
        // flipped from the notification card or the mark verb rebinds the rows here.
        java.util.Set<String> favPlayedNow71 = favPlayedSetNow();
        // v15.89 (slice 2195): the badge snapshot joins the change detector — a download landing while the list sits open rebinds the rows here.
        java.util.Set<String> favDlNow95 = favDlSetNow();
        // v15.90 (slice 2199): the duration snapshot joins the change detector — a length landing while the list sits open (a talk just prepared banks its duration) rebinds the rows here.
        java.util.Map<String, Integer> favDurNow98 = favDurCacheNow();
        if (adapter != null && (!favPlayingUrl.equals(favRowPlayingUrl == null ? "" : favRowPlayingUrl)
                || !favPlayingLibUrl.equals(favRowPlayingLibUrl == null ? "" : favRowPlayingLibUrl)
                || !favPlayingLibPath.equals(favRowPlayingLibPath == null ? "" : favRowPlayingLibPath)
                || AudioService.liveEnded != favRowEnded
                || !favPlayedNow71.equals(favPlayedSet)
                || !favDlNow95.equals(favDlSet95)
                || !favDurNow98.equals(favDurCache))) {
            favRowPlayingUrl = favPlayingUrl;
            favRowPlayingLibUrl = favPlayingLibUrl;
            favRowPlayingLibPath = favPlayingLibPath;
            favRowEnded = AudioService.liveEnded;
            favPlayedSet = favPlayedNow71;
            favDlSet95 = favDlNow95;
            favDurCache = favDurNow98;
            adapter.notifyDataSetChanged();
            // v15.67 (slice 2060): the sounding change above moves the playing talk (advance, stop, or a new session while browsing) — refresh the jump button's cached target and repaint it. Change-only: steady ticks never touch the button.
            refreshFavJumpTarget();
            paintFavJumpBtnFromList();
        }
        syncFavPlayAllBtn(false); // v15.64 (slice 2044): the sounding talk moved under the list — re-show or hide Play-all with the mark, change-only
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
            favResumeStripMode = false;
            android.content.SharedPreferences rsp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String rKind = rsp.getString("lastKind", "");
            String rName = rsp.getString("lastName", "");
            String rTitle = rsp.getString("lastTitle", "");
            String rPage = rsp.getString("lastPage", "");
            String rUrl = rsp.getString("lastUrl", "");
            int rPos = rsp.getInt("lastPos", 0);
            String rSavedUrl = rsp.getString("lastPosUrl", "");
            // v15.63 (slice 2038): the Room resume match delegates
            // to the pure StripResume core (sameTalk direct, or the
            // last URL's dlMap filename ending the saved path, with
            // the v13.62 legacy-http identity leg). The dlMap is
            // parsed once here (dlMapNow2038) and reused by the
            // library leg below — History parses it twice, once
            // per leg. The core takes a plain Map because the JVM
            // test harness cannot construct org.json (stubbed in
            // the SDK android.jar); on-device the lookup is the
            // same JSONObject read, once per tick.
            java.util.Map<String, String> dlMap2038 = dlMapNow2038(rsp);
            boolean rMatch = StripResume.roomMatch(rSavedUrl, rUrl, dlMap2038);
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
                            || Fx.stripInProgressDlFilenames(FavouritesActivity.this, rsp).contains(new java.io.File(lPath).getName()))) {
                    // v15.63 (slice 2038): the library resume match
                    // delegates to the pure StripResume core (the
                    // path itself, or a dlMap URL whose filename is
                    // this file's name and whose talk is the saved
                    // one), over the map parsed once above. The
                    // v14.01 file gate above stays inline.
                    lMatch = StripResume.libMatch(rSavedUrl, lPath, dlMap2038);
                }
                if (lTitle.length() == 0 || !lMatch) {
                    syncFavouritesStripPhoto(null);
                    favResumeStripTitle = null;
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
                int lDur = Fx.stripResumeDuration(FavouritesActivity.this, rsp, rSavedUrl, lPath);
                // v8.52: Main's v7.94 resume mark, mirrored — the proven file is offline, like Main's lib branch.
                meltResumeStripTitleFav(tx, "↺ " + lTitle + (lFav ? " ★" : "") + (Fx.stripInProgressDlFilenames(FavouritesActivity.this, rsp).contains(new java.io.File(lPath).getName()) ? " ⏳" : " ⬇"));
                // v5.46: a dead library strip names its recorded
                // sayadaw like the live strip since v5.45, in the
                // Room strip's "name · mm:ss မှ ဆက်နားထောင်ရန်"
                // shape; an unrecorded download keeps today's
                // generic line, never a guessed name.
                String libSd = Fx.stripLibSayadaw(FavouritesActivity.this, rsp);
                if (sb != null) sb.setText(libSd != null ? libSd + " · " + lTime + Lang.t(FavouritesActivity.this, "resume_from") : Lang.t(FavouritesActivity.this, "downloaded_talk") + " · " + lTime + Lang.t(FavouritesActivity.this, "resume_from"));
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
                favResumeStripMode = true;
                syncFavouritesStripPhoto(libSd);
                Fx.showStrip(strip);
                return;
            }
            if (!"room".equals(rKind) || rName.length() == 0 || rTitle.length() == 0
                    || rPage.length() == 0 || rPos <= 5000 || !rMatch) {
                syncFavouritesStripPhoto(null);
                favResumeStripTitle = null;
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
            int rDur = Fx.stripResumeDuration(FavouritesActivity.this, rsp, rUrl, null);
            // v8.52: Main's v7.94 resume download mark, mirrored — a ↺ strip here never said whether resuming streams or plays offline.
            meltResumeStripTitleFav(tx, "↺ " + rTitle + (rFav ? " ★" : "") + Fx.stripResumeDownloadMark(FavouritesActivity.this, rsp, rUrl));
            if (sb != null) sb.setText(rName + " · " + rTime + Lang.t(FavouritesActivity.this, "resume_from"));
            if (tg != null) tg.setText("▶");
            if (nx != null) Fx.crossfadeStripBtn(nx, false);
            // v7.31: same Previous hide as the library resume
            // branch above — a ↺ strip never shows ⏮.
            android.view.View pv0 = findViewById(R.id.nowStripPrev);
            if (pv0 != null) Fx.crossfadeStripBtn(pv0, false);
            android.widget.ProgressBar rPb = findViewById(R.id.nowStripProgress);
            if (rPb != null) Fx.stripResumeProgress(rPb, rPos, rDur);
            favResumeStripMode = true;
            syncFavouritesStripPhoto(rName);
            Fx.showStrip(strip);
            return;
        }
        favResumeStripMode = false;
        favResumeStripTitle = null; // v15.40: live branch owns tx via fadeStripText from here
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
                String libSd = Fx.stripLibSayadaw(FavouritesActivity.this, sp);
                subText = libSd != null ? libSd : Lang.t(FavouritesActivity.this, "downloaded_talk");
            }
        }
        if (text == null) { syncFavouritesStripPhoto(null); Fx.hideStrip(strip); return; }
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
                    ? " 😴 " + Lang.t(FavouritesActivity.this, "sleep_end")
                    : " 😴 ⏸ " + Lang.t(FavouritesActivity.this, "sleep_end");
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
                String saved = Prefs.savedQueue(FavouritesActivity.this,
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
                String saved = Prefs.savedQueueLib(FavouritesActivity.this);
                if (saved.length() > 0) {
                    for (String line : saved.split("\n"))
                        if (line.trim().length() > 0
                                && new java.io.File(line.trim()).isFile()
                                && !line.trim().equals(curLib)) qN++;
                }
            }
            if (qN > 0) text += " · ◈ " + qN;
        } catch (Exception ignored) {}
        if (AudioService.liveEnded) text += " · " + Lang.t(FavouritesActivity.this, "notif_state_ended");
        // v14.21 (slice 1675): the strip title/sub fade on a talk
        // change, keyed on the talk itself (Room URL / library
        // path) — the composed strings tick every second (sleep
        // countdown on the title, live clock on the sub), so the
        // text can never be the key.
        String stripTalkId = "room".equals(kind) ? sp.getString("lastUrl", "") : sp.getString("lastLibPath", "");
        if (tx != null) Fx.fadeStripText(tx, text, stripTalkId);
        // v5.32: live clock on the strip — sayadaw name + elapsed/total.
        android.widget.ProgressBar pb = findViewById(R.id.nowStripProgress);
        if (subText != null) subText += Fx.stripClockSuffixForStrip(pb, AudioService.livePositionMs, AudioService.liveDurationMs, Prefs.stripClockRemaining(FavouritesActivity.this));
        if (sb != null && subText != null) Fx.fadeStripText(sb, subText, stripTalkId);
        Fx.syncStripProgress(pb); // v7.93: skips while a mini-seek drag is in flight
        if (tg != null) Fx.flipStripGlyph(tg, AudioService.livePlaying);
        // v6.01: live strip's portrait — Room names lastName, library
        // names its recorded sayadaw (Fx.stripLibSayadaw, null when
        // unrecorded); both resolve through syncFavouritesStripPhoto's
        // exact-name catalog match, never a guessed face.
        syncFavouritesStripPhoto("room".equals(kind) ? sp.getString("lastName", "")
                : ("lib".equals(kind) ? Fx.stripLibSayadaw(FavouritesActivity.this, sp) : null));
        Fx.showStrip(strip);
    }

    private void startFavouritesStripTick() {
        if (stripTick != null) return;
        stripTick = new Runnable() {
            @Override public void run() {
                if (stripTick == null) return;
                // v11.76: re-post in finally — a throw inside syncFavouritesStrip used
                // to kill the 1s tick outright, freezing strip title/clock/
                // progress/glyph until the next onPause/onResume cycle.
                try { syncFavouritesStrip(); }
                finally { stripHandler.postDelayed(this, 1000); }
            }
        };
        stripHandler.post(stripTick);
    }

    private void stopFavouritesStripTick() {
        if (stripTick != null) {
            stripHandler.removeCallbacks(stripTick);
            stripTick = null;
        }
    }

    private void wireFavouritesStrip() {
        android.view.View nowStrip = findViewById(R.id.nowStrip);
        if (nowStrip != null) {
            Fx.press(nowStrip);
            // v4.73: the strip's progress bar seeks (see Fx.miniSeekBar).
            Fx.miniSeekBar(FavouritesActivity.this, nowStrip);
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
                        if (favResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) return;
                        Prefs.setStripClockRemaining(FavouritesActivity.this, !Prefs.stripClockRemaining(FavouritesActivity.this));
                        Fx.tap(FavouritesActivity.this);
                        syncFavouritesStrip();
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
                            Fx.tap(FavouritesActivity.this);
                            Fx.morph(v);
                            Intent li = new Intent(FavouritesActivity.this, DownloadedActivity.class);
                            li.putExtra("jumpPath", lp);
                            // v4.63: in dead-library resume mode
                            // the tap also auto-plays the file from
                            // its saved spot; a live session never
                            // passes this flag, so it still only
                            // glides to the playing file.
                            // v12.57: live recheck, not just the cached
                            // flag — favResumeStripMode refreshes on
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
                            if (favResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) li.putExtra("autoPlayPath", lp);
                            Fx.go(FavouritesActivity.this, li);
                            return;
                        }
                        android.widget.Toast.makeText(FavouritesActivity.this, Lang.t(FavouritesActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(FavouritesActivity.this, Lang.t(FavouritesActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(FavouritesActivity.this, RoomActivity.class);
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
                    Fx.tap(FavouritesActivity.this);
                    Fx.morph(v);
                    Fx.go(FavouritesActivity.this, i);
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
                    // favResumeStripMode refreshes on the 1s tick, so a
                    // session dying <1s before this long-press left the
                    // flag false and the press ACTION_STOPped a trackless
                    // service (phantom "talk_stopped" toast, saved spot
                    // NOT dismissed — the same ≤1s stale-flag class
                    // v12.55 closed for the Toggle long-press). The
                    // strip's own branch test is !liveSession ||
                    // !liveHasTrack, so re-checking the same statics here
                    // can only dismiss a genuinely dead session, never
                    // stop a live one.
                    if (favResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        // v5.34: same as Talks' dismiss — the
                        // per-talk spot goes with the global one.
                        String dismissUrl = getSharedPreferences("dhamma", MODE_PRIVATE)
                                .getString("lastPosUrl", "");
                        getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                                .putInt("lastPos", 0).putString("lastPosUrl", "").apply();
                        if (dismissUrl.length() > 0) Prefs.talkPosRemoveBoth(FavouritesActivity.this, dismissUrl);
                        Fx.tap(FavouritesActivity.this);
                        Fx.morph(v);
                        // v13.28: same as Downloaded's dismiss — one resume spot cleared,
                        // not the history; toast resume_cleared (Main's key), not
                        // hist_cleared (Settings' real clear-history key).
                        android.widget.Toast.makeText(FavouritesActivity.this, Lang.t(FavouritesActivity.this, "resume_cleared"), android.widget.Toast.LENGTH_SHORT).show();
                        syncFavouritesStrip();
                        // Favourites rows carry no ↺ mark, so this
                        // rebind changes nothing visible today; it
                        // keeps the dismiss path a faithful mirror
                        // of Talks' (v12.41) if a mark ever lands.
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return true;
                    }
                    Intent svc = new Intent(FavouritesActivity.this, AudioService.class);
                    svc.setAction(AudioService.ACTION_STOP);
                    startService(svc);
                    Fx.tap(FavouritesActivity.this);
                    Fx.morph(v);
                    android.widget.Toast.makeText(FavouritesActivity.this, Lang.t(FavouritesActivity.this, "talk_stopped"), android.widget.Toast.LENGTH_SHORT).show();
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
                // v12.55: live recheck, not just the cached flag — favResumeStripMode
                // refreshes on the 1s tick, so a session dying <1s before this tap left
                // the flag false and the tap ACTION_TOGGLEd a trackless service (dead
                // tap, tap-twice-to-resume — the exact bug Settings v11.90 fixed for
                // its Toggle). The strip's own branch test is !liveSession ||
                // !liveHasTrack, so re-checking the same statics here can only route a
                // genuinely dead session to resume, never a live one.
                if (favResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                    android.view.View strip2 = findViewById(R.id.nowStrip);
                    if (strip2 != null) strip2.performClick();
                    return;
                }
                Intent svc = new Intent(FavouritesActivity.this, AudioService.class);
                svc.setAction(AudioService.ACTION_TOGGLE);
                startService(svc);
                Fx.tap(FavouritesActivity.this);
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
                if (favResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                    android.view.View strip2 = findViewById(R.id.nowStrip);
                    if (strip2 != null) return strip2.performLongClick();
                    return true;
                }
                Intent svc = new Intent(FavouritesActivity.this, AudioService.class);
                svc.setAction(AudioService.ACTION_STOP);
                startService(svc);
                Fx.tap(FavouritesActivity.this);
                Fx.morph(v);
                android.widget.Toast.makeText(FavouritesActivity.this, Lang.t(FavouritesActivity.this, "talk_stopped"), android.widget.Toast.LENGTH_SHORT).show();
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
                    // favResumeStripMode refreshes on the 1s tick, so a session dying
                    // <1s before this tap left the flag false and the tap ACTION_NEXT-ed
                    // into a trackless Room instead of resuming the ↺ talk.
                    if (favResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    Fx.tap(FavouritesActivity.this);
                    Fx.morph(v);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        Intent li = new Intent(FavouritesActivity.this, DownloadedActivity.class);
                        li.setAction(DownloadedActivity.ACTION_NEXT);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        Fx.go(FavouritesActivity.this, li);
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(FavouritesActivity.this, Lang.t(FavouritesActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(FavouritesActivity.this, RoomActivity.class);
                    i.setAction(RoomActivity.ACTION_NEXT);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.go(FavouritesActivity.this, i);
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
                    if (favResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    Fx.tap(FavouritesActivity.this);
                    Fx.morph(v);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        Intent li = new Intent(FavouritesActivity.this, DownloadedActivity.class);
                        li.setAction(DownloadedActivity.ACTION_PREV);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        Fx.go(FavouritesActivity.this, li);
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(FavouritesActivity.this, Lang.t(FavouritesActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(FavouritesActivity.this, RoomActivity.class);
                    i.setAction(RoomActivity.ACTION_PREV);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.go(FavouritesActivity.this, i);
                }
            });
        }
    }

    // v15.63 (slice 2038): the download map as a plain map
    // for the StripResume core — parsed once per strip sync
    // (History parses it inline once per leg). Torn JSON reads
    // as an empty map, so a torn write hides the resume strip
    // for one pass, never a crash.
    private java.util.Map<String, String> dlMapNow2038(android.content.SharedPreferences rsp) {
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

    private java.util.Set<String> readFavTalks() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        try {
            java.util.Set<String> raw = getSharedPreferences("dhamma", MODE_PRIVATE).getStringSet("favTalks", null);
            if (raw != null) out.addAll(raw);
        } catch (Exception ignored) {}
        return out;
    }

    // v15.50 (slice 1964): the saved-spot map for the rail — one
    // parse of the raw talkPosMap string (the v5.69 lesson: never
    // call talkPosGet per talk, that re-splits the whole map on
    // every lookup), keyed by identity URL so http/https twins
    // collapse to one spot; the first line per identity wins (the
    // talkPosGet first-match rule). Fail closed to an empty map.
    private java.util.Map<String, Integer> readTalkPos() {
        java.util.Map<String, Integer> out = new java.util.HashMap<String, Integer>();
        try {
            String raw = getSharedPreferences("dhamma", MODE_PRIVATE).getString("talkPosMap", "");
            if (raw != null && raw.length() > 0) {
                for (String line : raw.split("\\n")) {
                    int t = line.indexOf('\t');
                    if (t <= 0) continue;
                    String id = SiteHttp.identityUrl(line.substring(0, t));
                    if (id == null || out.containsKey(id)) continue;
                    try {
                        int pos = Integer.parseInt(line.substring(t + 1));
                        if (pos > 0) out.put(id, Integer.valueOf(pos));
                    } catch (Exception ignored) {}
                }
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
