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
    // v15.96 (slice 2237): the total behind the header — the unfiltered pick's size, captured beside the pick in refresh(); the header paints it, the search never touches it.
    private int newTotal37 = 0;
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
    // v15.85 (slice 2169): the favourite-star snapshot for the rows (History v13.77) — newFavSet is the favTalks set copied once per refresh (never read per row per bind, the v1.59 rule); refreshed on refresh() and on the strip tick's change detector, so a star flipped from the notification card rebinds the rows here.
    private java.util.Set<String> newFavSet = new java.util.HashSet<String>();
    // v15.89 (slice 2196): the downloaded-badge snapshot for the rows — the identity forms of the unfiltered pick's stream addresses that are already on the phone, built once per refresh from the unfiltered pick, refreshed on refresh and on the one-second tick's change detector, so a download landing while the list sits open rebinds the rows here.
    private java.util.Set<String> newDlSet96 = new java.util.HashSet<String>();
    // v15.86 (slice 2176): the total-duration snapshot for the rows — newDurCache is the per-talk total durations (Prefs.talkDurMap, one parse per refresh, never talkDurGet per row per bind, the v13.97 rule); refreshed on refresh() and on the strip tick's change detector, so a length landing while the list sits open rebinds the rows here.
    private java.util.Map<String, Integer> newDurCache = new java.util.HashMap<String, Integer>();
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
    // v15.75 (slice 2109): "Back to top" on this screen — the Talks 1940 / Downloaded 2003 button, now on the third full-list screen without one: a new-talks list grows with every catalogue refresh, so its header is off-screen exactly when the jump is needed. newTopBtn is the floating gold ↑ button (GONE until ScrollTop.visible — the first visible row passes 8 — the 1940 shape, on the shown list's own scroll, not the sounding row); newTopShown is the last-painted state for the painter's change gate. Back to top landed in slice 2109.
    private android.view.View newTopBtn = null;
    private boolean newTopShown = false;
    // v15.97 (slice 2245): New talks row swipe — the live drag state for this screen's gesture twin of the shared row swipe: a left drag marks that one talk listened, a right drag adds it to its sayadaw's queue. The row is the DOWN-time row (a rebind can move rows under a held finger, the commit acts on the DOWN row, never a re-resolved one); the stream address and sayadaw are resolved at DOWN time; the mark side is mark-only on this screen — a marked row leaves the list at once, so there is no listened flag to keep. The gesture math stays in the shared core; this activity owns the touch stream, the prefs, the queue and the toasts.
    private NewTalkRail.Card ntSwipeRow45 = null;
    private String ntSwipeStream45 = null;
    private String ntSwipeSd45 = null;
    private float ntSwipeDownX45 = 0f;
    private float ntSwipeDownY45 = 0f;
    private boolean ntSwipeArmed45 = false;
    private int ntSwipeDir45 = 0;
    private boolean ntSwipeLeftOk45 = false;
    private boolean ntSwipeRightOk45 = false;
    private int ntSwipeSlop45 = -1;
    private android.animation.ValueAnimator ntSwipeSettle45 = null;
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
    // v15.84 (slice 2162): "Queue from here" debounce — queueFromHereNew's own 700ms clock (the chooser's v12.28 rule), beside lastNewPlayFromHereMs, so a queue-from-here tap never eats a play-from-here tap.
    private long lastNewQueueFromHereMs = 0;

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
                    // v15.75 (slice 2109): the top button paints on the same scroll (the 2003 shape — its only show rule is the first visible row, so no laid-out-window guard; the threshold keeps it hidden until row 8 anyway).
                    paintNewTopBtn(firstVisibleItem);
                    if (visibleItemCount > 0) paintNewJumpBtn(firstVisibleItem, firstVisibleItem + visibleItemCount - 1);
                }
            });
            // v15.97 (slice 2245): the swipe owns this list's touch stream — one listener, so the swipe and the tap / long-press / scroll never fight over it. A swipe DOWN records its candidate and returns false, so the tap, the long-press menu and the scroll still track; a swipe that arms consumes the gesture from there, so the list never scrolls under the travelling card.
            listView.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, android.view.MotionEvent ev) {
                    return ntSwipeOnTouch45(ev);
                }
            });
        }
        wireNewSearch(); // v15.64 (slice 2045): search on this screen, before the first refresh
        wireNewSort(); // v15.66 (slice 2057): sort on this screen, before the first refresh
        wireNewJumpBtn(); // v15.67 (slice 2061): jump-to-playing button on this list
        wireNewTopBtn(); // v15.75 (slice 2109): back to top on this screen, before the first refresh
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

// v16.06 (slice 2307): the header carries the list total time — the 2298/2300/2301/2304/2305/2306 total-time block, now on the New talks screen: the header already names its count (2237), so the total rides beside it over the SAME unfiltered pick (newAllItems), summed through the row resolver newRowDurMs (the 2176 two-leg resolve — stream identity URL, then the row's own URL) and formatted by this screen's own qTotalFmtDur2307 (the 2295 idiom verbatim — "2h 15m" with hs_hour/hs_min/hs_sec, Myanmar digits in Burmese), so no new Lang key; hidden-when-0, an unknown total reads "" and a list with no banked lengths keeps exactly today's header; one length per listed entry — the count's population, like 2304/2305/2306; one paint site (refresh(), beside the count) — the search never re-totals, the header stays the unfiltered pick's total like the count.
    private String qTotalNum2307(long n) {
        String s = Long.toString(n);
        return Lang.index(this) == 0 ? Prefs.statsDigitsPure(s) : s;
    }

    private String qTotalFmtDur2307(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (h > 0) return qTotalNum2307(h) + " " + Lang.t(this, "hs_hour")
                + " " + qTotalNum2307(m) + " " + Lang.t(this, "hs_min");
        if (m > 0) return qTotalNum2307(m) + " " + Lang.t(this, "hs_min");
        return qTotalNum2307(s) + " " + Lang.t(this, "hs_sec");
    }

    private String qTotalHeaderSuf2307() {
        if (newAllItems == null || newAllItems.isEmpty()) return "";
        long qTotMs2307 = 0;
        for (NewTalkRail.Card qTotR2307 : newAllItems) qTotMs2307 += newRowDurMs(qTotR2307.e);
        if (qTotMs2307 > 0) return " · " + qTotalFmtDur2307(qTotMs2307 / 1000);
        return "";
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
        // v15.96 (slice 2237): the total captured beside the pick — one parse per refresh, the search never re-reads the store per keystroke, so the total stays the unfiltered pick's size.
        newTotal37 = newAllItems == null ? 0 : newAllItems.size();
        refreshNewTalksPlayingMarks(); // v15.64 (slice 2043): mark right on first paint, not one tick late
        refreshNewFavSet(); // v15.85 (slice 2169): the star snapshot fresh on first paint, beside the playing marks
        refreshNewDlSet(); // v15.89 (slice 2196): the badge snapshot fresh on first paint, beside the star snapshot — the rows read the snapshot, never a per-row store test
        refreshNewDurCache(); // v15.86 (slice 2176): the duration snapshot fresh on first paint, beside the star snapshot
        // v15.96 (slice 2237): the header carries its total — the drawer header's own idiom, now on the full-list screen: the count sits beside the title in parens, ASCII digits; an empty list keeps the bare title, so the count never shows (0).
        if (headerTitle != null)
            headerTitle.setText(newTotal37 >= 1 ? Lang.t(this, "hs_new_talks") + " (" + newTotal37 + ")" + qTotalHeaderSuf2307() : Lang.t(this, "hs_new_talks"));
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
        // v15.75 (slice 2109): a search / refresh can land the list deep without a scroll event — repaint the top button from the new position too (the 2003 shape).
        paintNewTopBtnFromList();
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

    // v15.75 (slice 2109): wire the back-to-top button — one tap glides the open list to the top (the Talks 1940 / Downloaded 2003 shape: the tap needs no gate — the button only shows past row 8, so a tap always has somewhere to go; this screen HAS a search field, so the keyboard hides with the glide, the 2003 shape in this file's own inline form — the wireNewSort tap's two lines, not a new helper). A missing button simply never wires; a missing list never scrolls.
    private void wireNewTopBtn() {
        newTopBtn = findViewById(R.id.newTopBtn);
        if (newTopBtn != null) newTopBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(NewTalksActivity.this);
                Fx.morph(v);
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                if (listView != null) listView.smoothScrollToPosition(0);
            }
        });
    }

    // v15.75 (slice 2109): the back-to-top state — shown once the first visible row passes ScrollTop's threshold, hidden at the top (the 1940 Talks / 2003 Downloaded shape verbatim: ScrollTop.visible, THRESHOLD 8 — about one screen of rows). No sounding gate: the rule is the shown list's own scroll. The change gate keeps steady scrolls and the search/refresh repaint from re-touching the button; the show/hide melts (Fx.fadeIn/fadeOut, 180ms) instead of snapping, and a cancelled fade never strands it half-transparent (the v2.62 gen guard, the 1940 shape).
    private void paintNewTopBtn(int firstVisible) {
        if (newTopBtn == null) return;
        boolean want = ScrollTop.visible(firstVisible);
        if (want == newTopShown) return;
        newTopShown = want;
        if (want) {
            newTopBtn.setVisibility(android.view.View.VISIBLE);
            Fx.fadeIn(newTopBtn, 180);
        } else {
            Fx.fadeOut(newTopBtn, 180);
        }
    }

    // v15.75 (slice 2109): repaint from the list's own position after a scroll-less change — a search / refresh rebuild can land the list deep without a scroll event, so read the position after layout and paint from it. Posted, because the first visible row is only valid once the list has laid out (the 1940 shape). The rotation restore needs no separate hook: onCreate restores through refresh(), whose applyNewSearch tail already posts this repaint.
    private void paintNewTopBtnFromList() {
        if (listView == null) return;
        listView.post(new Runnable() {
            @Override public void run() {
                if (listView == null) return;
                paintNewTopBtn(listView.getFirstVisiblePosition());
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
        // v15.85 (slice 2169): a favourited new talk says so — "\u2605 " after the "\u25b6 " playing mark when both apply (History v13.77), gold either way, so the mark reads at a glance. The star reads the newFavSet snapshot (refreshed on refresh() and on the strip tick's change detector, the History shape), resolved through the existing newStreamUrlFor — a downloaded (path-form) row claims its stream URL through the dlMap exactly-one-claimant resolve, so it stars exactly when its stream twin does. No Lang key, no Prefs write, no layout change — the row is built in code.
        boolean newRowFav69 = newRowFav(e);
        // v15.89 (slice 2196): the downloaded row says so — the badge after the star when both apply (this screen's order: play first, star second, badge third), so a new talk already on the phone reads as offline at a glance. The row reads the snapshot the refresh filled; a row whose stream address sits in the set is downloaded. The badge alone does not gold the row — only a sounding or favourited row turns gold. The sounding row badges too — downloaded is a property of the talk, not a state.
        boolean newRowDl96 = newRowDl(e);
        tv.setText((newRowPlaying ? "\u25b6 " : "") + (newRowFav69 ? "\u2605 " : "") + (newRowDl96 ? "\u2B07 " : "") + e.title);
        tv.setTextColor((newRowPlaying || newRowFav69) ? goldColor() : android.graphics.Color.parseColor("#7A6A58"));
        tv.setTextSize(12);
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(col, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        // v15.86 (slice 2176): a new talk's total length clocks on the row's right edge — the chooser's 1094 clock, now on the last full-list screen without one (History rows clock heard time, not length; Bookmarks rows clock the mark minute; Favourites rows clock the resume spot — no New talks row ever said how long its talk runs). The clock reads newRowDurMs (the duration snapshot, refreshed on refresh() and on the strip tick's change detector), formatted by Fx.fmtTime ("42:15", "1:05:22" past the hour, the chooser's clock shape); the playing row clocks too (its length is a property of the talk, the 1094 rule — its live position is on the strip). Hidden when no duration was ever banked — GONE, never guessed, never "0:00". No Lang key, no Prefs write, no layout change — the row is built in code.
        int newRowDur76 = newRowDurMs(e);
        TextView newDur76 = new TextView(this);
        newDur76.setText(newRowDur76 > 0 ? Fx.fmtTime(newRowDur76) : "");
        newDur76.setTextColor(night ? android.graphics.Color.parseColor("#AAAAAA")
                : android.graphics.Color.parseColor("#7A6A58"));
        newDur76.setTextSize(12);
        newDur76.setVisibility(newRowDur76 > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
        LinearLayout.LayoutParams newDurParams76 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        newDurParams76.setMargins(0, 0, (int) (8 * d), 0);
        newDur76.setLayoutParams(newDurParams76);
        row.addView(newDur76);
        TextView go = new TextView(this);
        go.setText("\u203a");
        go.setTextColor(night ? android.graphics.Color.parseColor("#AAAAAA")
                : android.graphics.Color.parseColor("#7A6A58"));
        go.setTextSize(22);
        row.addView(go, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        // v15.97 (slice 2245): the swipe frame — the row's card gains a gold strip behind it, built in code like the rest of this row: a frame holds the strip first (behind) and the card second (in front), so the strip is invisible at rest and revealed as the card travels. The strip starts hidden and the card starts plain — at rest the row keeps exactly today's look — and the gesture lifts both together when a drag arms. The strip's labels are painted here, where the row is built: the queue word on the left (revealed by a right drag, always the add word — an already-queued row never starts a right drag) and the mark word on the right (revealed by a left drag, always the mark word — this screen lists unplayed talks only). The card carries the bare row, so the photo, the text and the clock travel together.
        android.widget.FrameLayout swFrame = new android.widget.FrameLayout(this);
        LinearLayout swUnder = new LinearLayout(this);
        swUnder.setId(R.id.ntSwipeUnder45);
        swUnder.setOrientation(LinearLayout.HORIZONTAL);
        swUnder.setGravity(Gravity.CENTER_VERTICAL);
        swUnder.setBackgroundResource(R.drawable.dl_swipe_bg);
        swUnder.setVisibility(android.view.View.GONE);
        android.widget.TextView swLeft = new android.widget.TextView(this);
        swLeft.setId(R.id.ntSwipeLeft45);
        swLeft.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        swLeft.setPadding((int) (20 * d), 0, 0, 0);
        swLeft.setTextColor(android.graphics.Color.parseColor("#3B2314"));
        swLeft.setTextSize(15);
        swLeft.setTypeface(null, android.graphics.Typeface.BOLD);
        swLeft.setText(Lang.t(this, "menu_queue_add"));
        android.widget.TextView swRight = new android.widget.TextView(this);
        swRight.setId(R.id.ntSwipeRight45);
        swRight.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        swRight.setGravity(Gravity.END);
        swRight.setPadding(0, 0, (int) (20 * d), 0);
        swRight.setTextColor(android.graphics.Color.parseColor("#3B2314"));
        swRight.setTextSize(15);
        swRight.setTypeface(null, android.graphics.Typeface.BOLD);
        swRight.setText(Lang.t(this, "dl_menu_mark_one"));
        swUnder.addView(swLeft);
        swUnder.addView(swRight);
        LinearLayout swCard = new LinearLayout(this);
        swCard.setId(R.id.ntSwipeCard45);
        swCard.setOrientation(LinearLayout.VERTICAL);
        swCard.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        swFrame.addView(swUnder, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        swFrame.addView(swCard, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        return swFrame;
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
    // opens with the verbs this screen needs: "Share talk" first
    // (the History menu_share shape, v13.77 - share the row's talk
    // through the Android sheet), "Copy link" second (the History
    // dl_menu_copy_link shape, v15.53 - copy the row's stream URL
    // to the clipboard, the share/copy pair together), "Share file"
    // third (the History dl_menu_share_file shape, v15.53 - send
    // the downloaded .mp3 through the sheet, the share/copy/file
    // trio together), then "Play from here"
    // (the chooser's 1942 / History's 2030 / Favourites' 2069 verb
    // — queue the shown run from this row onward and open the Room
    // on it at 0:00), "Add to playlist" (the Room's v12.65 verb —
    // file the talk in one of the listener's own lists, before
    // the star verb) and the state-dependent star verb ("Add to
    // favourites" when unstarred, "Remove from favourites" when
    // starred — the chooser 1969 / library / History 1267 shape).
    // download is the remaining future verb on this same menu
    // (playlist-add landed in slice 2092, share-file in slice
    // 2087, copy-link in slice 2081, share in slice 2080,
    // go-to-sayadaw in slice 2079, queue in slice 2074; the
    // History menu grew the same way: 1981, 1982, 1995, 2030).
    // The offer comes from the pure NewRowMenu
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
        // v15.70 (slice 2080): the share verb — "Share talk"
        // (NewShare, the History menu_share shape, v13.77): the
        // row resolves to its stream URL once per menu open
        // (newStreamUrlFor — URL-form rows share their own URL,
        // path-form rows resolve through the dlMap reverse lookup
        // with the exactly-one-claimant rule, v8.44; an
        // unresolvable row offers no share, never a guessed
        // link). The key composes FIRST, before Play-from-here,
        // so the share sits at the top of the menu like History's
        // menu and the chooser's dialog ("share first so muscle
        // memory is preserved", v7.82). Offered on any row with a
        // resolvable stream URL — sounding or not, sharing never
        // touches playback.
        // v15.70 (slice 2081): the copy verb — "Copy link"
        // (RoomCopyLink, the History dl_menu_copy_link shape,
        // v15.53 slice 1981): the same resolved stream URL
        // (shareUrl80 — no second resolve, the v5.69 cost rule)
        // offers the clipboard copy, so a listener who wants
        // just the link pasted elsewhere never has to share it
        // through the sheet and copy from there. The gate is
        // blank-URL-only (RoomCopyLink.canOffer), so a
        // streamed-only talk still offers it (a link is not a
        // file); only a blank URL hides it. The key composes
        // after the queue/go inserts (below), right after the
        // share, so the share/copy pair sits together like
        // History's menu and the chooser's trio — the pair can
        // never be split by a later insert.
        // v15.71 (slice 2087): the file verb — "Share file"
        // (TalkFileShare, the History dl_menu_share_file shape,
        // v15.53 slice 1982): the same resolved stream URL
        // (shareUrl80 — no second resolve, the v5.69 cost rule)
        // offers sending the actual downloaded .mp3, so a
        // listener who just downloaded a new talk never has to
        // find it in Downloads/Dhamma by hand. The gate is the
        // screen's own download check (newIsDownloaded — the
        // History v13.77 pair: dlMap mapped file + the v13.66
        // length gate, plus the basename leg), so a streamed-only
        // talk never offers it (a file is not a link). The key
        // composes right after the copy (below), so the
        // share/copy/file trio sits together like History's menu
        // and the chooser's dialog — the trio can never be split
        // by a later insert.
        // v15.72 (slice 2092): the playlist verb — "Add to playlist"
        // (NewPlAdd, the Room's showPlaylistAddDialog shape,
        // v12.65–v12.80): the same resolved stream URL
        // (shareUrl80 — no second resolve, the v5.69 cost rule)
        // offers filing the talk in one of the listener's own
        // lists, so a listener who just found a new talk never
        // has to open the Room to file it. The gate is the
        // same non-blank URL the share reads (NewPlAdd.canOffer),
        // so a streamed-only talk still offers it (filing is not
        // a file); only a blank URL hides it. The key composes
        // right before the star verb (below), so the collect
        // verb sits with the star, last — the share trio, the
        // play verbs and the navigation are never split by it.
        String shareKey80 = "";
        String copyKey81 = "";
        String fileKey87 = "";
        String plKey92 = "";
        String dlKey2136 = "";
        String markKey2138 = "";
        try {
            String shareUrl80 = newStreamUrlFor(c.e);
            if (NewShare.canOffer(shareUrl80)) shareKey80 = "menu_share";
            if (RoomCopyLink.canOffer(shareUrl80)) copyKey81 = "dl_menu_copy_link";
            if (newIsDownloaded(shareUrl80)) fileKey87 = "dl_menu_share_file";
            if (NewPlAdd.canOffer(shareUrl80)) plKey92 = "pl_add";
            // v15.79 (slice 2136): the download key — gated on the v11.70 rule through the pinned pure core MainDl.canOffer (slice 1997: neither downloaded nor already queued), plus the non-blank stream URL leg like History's offer, so an unresolvable row never offers it. The sounding row keeps it (History offers it on sounding rows too — downloading the talk now playing is harmless, the Room keeps streaming).
            if (shareUrl80.length() > 0 && MainDl.canOffer(newIsDownloaded(shareUrl80), newIsDlQueued(shareUrl80))) dlKey2136 = "dl_btn_download";
            // v15.80 (slice 2138): the mark key — gated on the 1949 DlMark core (canOffer: a non-blank stream URL, and not the sounding talk — the sounding talk earns its mark by finishing, like the 1949 sounding exclusion), through the screen's own three sounding identities (NewPlayAll.isSounding — the 2043 mark rule the play-from-here verb already reads). The label is DlMark.labelKey over the prefs-fresh played set (readPlayedTalks, the same read the refresh's NewAll pick uses), so the label can never disagree with the list membership — and on this screen the rows are unplayed by construction (NewAll.pickAll drops played talks), so the label reads dl_menu_mark_one; the unmark leg is never offered here, only the tap-time already-marked race toasts already_listened.
            if (DlMark.canOffer(shareUrl80, NewPlayAll.isSounding(shareUrl80, newPlayingUrl, newPlayingLibUrl, newPlayingLibPath))) markKey2138 = DlMark.labelKey(DlMark.isMarked(readPlayedTalks(), SiteHttp.identityUrl(shareUrl80)));
        } catch (Exception ignored) {}
        if (shareKey80.length() > 0) keys71.add(0, shareKey80);
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
        // v15.84 (slice 2162): the queue-from-here key — gated on the new NewRowMenu.queueFromHereKey leg (EXACTLY the play-from-here gate — a stream URL with a sayadaw, not sounding — so the anchor itself can join the run; rows below that cannot queue are skipped by the helper's pick, not by the offer). It composes right after the queue key (indexOf qKey74, fallback indexOf menu_play_from_here — the queue verbs sit together, the chooser's order: queue add/remove, queue-from-here, play-from-here), so the display order reads Share, Copy, File, Queue, QueueFromHere, PlayFromHere, Go, Download, PlAdd, Star, Mark. The queue key is always present when the gate passes (the same sounding rule feeds both), so the < 0 leg is defensive only.
        String qfhKey62 = NewRowMenu.queueFromHereKey(url, sd, newPlayingUrl, newPlayingLibUrl, newPlayingLibPath);
        if (qfhKey62 != null) {
            int at62 = keys71.indexOf(qKey74);
            if (at62 < 0) at62 = keys71.indexOf("menu_play_from_here");
            keys71.add(at62 < 0 ? keys71.size() : at62 + 1, qfhKey62);
        }
        // v15.70 (slice 2079): the navigation verb — "Go to
        // sayadaw's talks" (NewGo, the FavGo mirror over
        // GoSayadaw): the row's sayadaw resolved through the
        // bundled catalog (catalogFor — the cached, canonicalized
        // read the play verbs already pay for; one cache lookup
        // per menu open at most). The key composes after the
        // queue verb, so the play verbs sit together, the
        // navigation follows, and the star verb stays last
        // (inserted before menu_fav_add / menu_fav_remove —
        // whichever star key the row's state composed, never
        // appended past it). Offered on any row whose sayadaw
        // resolves to a page URL — sounding or not, the
        // navigation never touches playback — and hidden while
        // the sayadaw is unknown or unlisted (NewGo.canOffer).
        // A stale banked sayadaw can only mis-offer when the
        // canonical name still resolves — the handler re-resolves
        // at tap time and toasts no_sayadaw_page on a miss, never
        // a dead tap.
        String goKey79 = "";
        try {
            String[] goCat79 = catalogFor(sd);
            if (NewGo.canOffer(sd, goCat79)) goKey79 = "menu_go_sayadaw";
        } catch (Exception ignored) {}
        if (goKey79.length() > 0) {
            int at79 = keys71.indexOf("menu_fav_remove");
            if (at79 < 0) at79 = keys71.indexOf("menu_fav_add");
            keys71.add(at79 < 0 ? keys71.size() : at79, goKey79);
        }
        // v15.70 (slice 2081): the copy key composes right after
        // the share (indexOf + 1 — the queue/go inserts above
        // already landed, so inserting here keeps the share/copy
        // pair adjacent whatever they did; a copy implies a
        // share, since both gates are the same non-blank URL —
        // the at81 < 0 leg is defensive only).
        if (copyKey81.length() > 0) {
            int at81 = keys71.indexOf("menu_share");
            keys71.add(at81 < 0 ? keys71.size() : at81 + 1, copyKey81);
        }
        // v15.71 (slice 2087): the file key composes right after
        // the copy (indexOf + 1 — the copy insert above already
        // landed, so inserting here keeps the share/copy/file
        // trio adjacent whatever later inserts do; a file implies
        // a copy, since a downloaded talk always has a non-blank
        // URL — the at87 < 0 leg is defensive only).
        if (fileKey87.length() > 0) {
            int at87 = keys71.indexOf("dl_menu_copy_link");
            keys71.add(at87 < 0 ? keys71.size() : at87 + 1, fileKey87);
        }
        // v15.79 (slice 2136): the download key composes right before the star verb, before the playlist insert below lands — the History order (download, playlist-add, star), so the collect verbs sit together last and the star stays last; a download implies a share, since both gates are the same non-blank stream URL — the atDl2136 < 0 leg is defensive only.
        if (dlKey2136.length() > 0) {
            int atDl2136 = keys71.indexOf("menu_fav_remove");
            if (atDl2136 < 0) atDl2136 = keys71.indexOf("menu_fav_add");
            keys71.add(atDl2136 < 0 ? keys71.size() : atDl2136, dlKey2136);
        }
        // v15.72 (slice 2092): the playlist key composes right before
        // the star verb (indexOf menu_fav_remove else menu_fav_add —
        // whichever star key the row's state composed, never
        // appended past it; the collect verb sits with the star,
        // last — a playlist add implies a share, since both gates
        // are the same non-blank URL — the at92 < 0 leg is
        // defensive only).
        if (plKey92.length() > 0) {
            int at92 = keys71.indexOf("menu_fav_remove");
            if (at92 < 0) at92 = keys71.indexOf("menu_fav_add");
            keys71.add(at92 < 0 ? keys71.size() : at92, plKey92);
        }
        // v15.80 (slice 2138): the mark key composes AFTER the star verb — the 1949 library / 1969 chooser shape (the mark sits right after the favourite toggle, the other per-talk state toggle), so the star is no longer last on this menu; a mark implies a share, since both gates are the same non-blank stream URL — the keys71.isEmpty() check below still guards the empty menu.
        if (markKey2138.length() > 0) {
            keys71.add(markKey2138);
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
                        if ("menu_share".equals(keyArr[which])) {
                            shareTalkNew(c);
                        } else if ("dl_menu_copy_link".equals(keyArr[which])) {
                            copyTalkLinkNew(c);
                        } else if ("dl_menu_share_file".equals(keyArr[which])) {
                            shareFileNew(c);
                        } else if ("pl_add".equals(keyArr[which])) {
                            addToPlaylistNew(c);
                        } else if ("dl_btn_download".equals(keyArr[which])) {
                            // v15.79 (slice 2136): "Download" — the v11.70 download verb on the New talks row menu, through downloadTalkNew below (the file routes every menu verb through a named helper).
                            downloadTalkNew(c);
                        } else if ("menu_play_from_here".equals(keyArr[which])) {
                            playFromHereNew(c);
                        } else if ("menu_queue_add".equals(keyArr[which])) {
                            queueAddNew(c);
                        } else if ("menu_queue_remove".equals(keyArr[which])) {
                            queueRemoveNew(c);
                        } else if ("menu_queue_from_here".equals(keyArr[which])) {
                            // v15.84 (slice 2162): "Queue from here" — the bulk queue verb on the New talks row menu, through queueFromHereNew below (the file routes every menu verb through a named helper). Queue from here landed in slice 2162.
                            queueFromHereNew(c);
                        } else if ("menu_go_sayadaw".equals(keyArr[which])) {
                            goToSayadawNew(c);
                        } else if ("menu_fav_add".equals(keyArr[which]) || "menu_fav_remove".equals(keyArr[which])) {
                            toggleFavFromMenu(c);
                        } else if ("dl_menu_mark_one".equals(keyArr[which])) {
                            // v15.80 (slice 2138): "Mark as listened" — the 1949 mark verb on the New talks row menu, through markListenedNew below (the file routes every menu verb through a named helper).
                            markListenedNew(c);
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

    // v15.84 (slice 2162): "Queue from here" — the bulk queue verb the New talks row menu lacked that the Downloaded library row menu has carried since before v13.04 (the Talks chooser ported it in v13.04), the Room drawer in-list dialog since v15.82 (slice 2153), the Playlist detail row menu since v15.83 (slice 2157), the History row menu since v15.83 (slice 2158), the Bookmarks row menu since v15.83 (slice 2159) and the Favourites row menu since v15.83 (slice 2160): a new talk could be queued one by one or played from here, but never queued as a run for later without playing now. playFromHereNew MINUS the Room open, verbatim: queues the run from the long-pressed row onward — in display order, each talk carrying its own sayadaw (the v2 lines, 2030) — into the anchor sayadaw's saved queue, and stops there. No playback: this is the queue-only run; play-from-here is the sibling that plays. The anchor is re-resolved at tap time (the v13.13 rule): the dialog can sit open across a search/sort/refresh, so the captured position may have moved — the scan below matches by identity (SiteHttp.sameTalk over the shown list). A row the scan cannot find falls back to the anchor alone (the chooser's v13.04 fallback shape), never a stale range. Stream anchors only: on this screen the card's URL IS the stream URL, so a path-form row names no stream URL at all — the offer gate enforces it, and the tap re-check refuses it whole (queue_changed); a path-form row BELOW the anchor reads as a null id and the pick skips it. The sounding anchor is refused whole at the offer (the queue branches' rule), NOT skipped-while-below-queue: unlike the Playlist/library shape where a sounding anchor skips only itself, here the offer never appears for a sounding row, so the helper never sees one — and the tap-time re-check below refuses the whole run (queue_changed, no partial queue) if the anchor turned sounding while the dialog sat open (all THREE sounding identities — the 2043 mark rule: Room URL, library URL, library path). The anchor sayadaw is healed exactly like playFromHereNew (catalog + the sayadawForTalk heal) — the queue key must be the healed name, never a stale banked one. No catalog, no run: the queue is untouched, toast pl_no_play (same as playFromHereNew). The debounce has its own clock (lastNewQueueFromHereMs, the chooser's 700ms rule, v12.28): validity BEFORE consuming the window, so an invalid tap never consumes it. The toast counts written lines only (queue_plain_prefix + N + queue_added_suffix, already_in_queue when nothing new queued). Writes only the anchor sayadaw's saved queue via Prefs.saveQueue (the playFromHereNew write block verbatim); the star set, the resume positions and the played set stay.
    private void queueFromHereNew(final NewTalkRail.Card c) {
        if (items == null || items.isEmpty() || c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long now62 = android.os.SystemClock.uptimeMillis();
        if (now62 - lastNewQueueFromHereMs < 700) return;
        lastNewQueueFromHereMs = now62;
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
        String sd62 = c.e.sayadaw;
        String[] cat62 = catalogFor(sd62);
        if (cat62 == null || cat62[1] == null || cat62[1].length() == 0) {
            String owner62 = Prefs.sayadawForTalk(this, "lib", streamUrl, "");
            if (owner62 != null && owner62.length() > 0 && !owner62.equals(sd62)) {
                sd62 = owner62;
                cat62 = catalogFor(sd62);
            }
        }
        if (cat62 == null || cat62[1] == null || cat62[1].length() == 0) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "pl_no_play"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // The anchor's display position, re-resolved by identity —
        // the dialog can sit open across a search/sort/refresh, so
        // the captured position may have moved (the v13.13 rule).
        int anchorIdx62 = -1;
        for (int i62 = 0; i62 < items.size(); i62++) {
            NewTalkRail.Card r62 = items.get(i62);
            if (r62 == null || r62.e == null || r62.e.url == null) continue;
            if (SiteHttp.sameTalk(r62.e.url, streamUrl)) { anchorIdx62 = i62; break; }
        }
        // Display-order identity list from the anchor onward (the
        // PlayFromHere pick's input), each row carrying its own
        // sayadaw + title for the v2 lines. A path-form row reads as
        // null and the pick skips it (a card is a stream URL by
        // NewAll's construction, but the gate costs nothing).
        java.util.List<String> ids62 = new java.util.ArrayList<String>();
        java.util.List<String> sds62 = new java.util.ArrayList<String>();
        java.util.List<String> titles62 = new java.util.ArrayList<String>();
        if (anchorIdx62 < 0) {
            // Anchor gone from the list while the dialog sat open:
            // queue just the anchor itself, never a stale range.
            ids62.add(SiteHttp.identityUrl(streamUrl));
            sds62.add(sd62);
            titles62.add(c.e.title);
        } else {
            for (int i62 = anchorIdx62; i62 < items.size(); i62++) {
                NewTalkRail.Card r62 = items.get(i62);
                if (r62 == null || r62.e == null) { ids62.add(null); sds62.add(""); titles62.add(""); continue; }
                String u62 = r62.e.url;
                ids62.add(u62 != null && u62.length() > 0 && !u62.startsWith("/") ? SiteHttp.identityUrl(u62) : null);
                sds62.add(r62.e.sayadaw != null ? r62.e.sayadaw : "");
                titles62.add(r62.e.title != null ? r62.e.title : "");
            }
            // The anchor's own line carries the healed sayadaw.
            sds62.set(0, sd62);
        }
        // The anchor sayadaw's current queue, as identities — the
        // pick skips talks already queued (they stay where they
        // are). Saved lines are carried verbatim (histQueueAdd's
        // discipline), so a foreign line the Room drawer moved
        // mid-queue survives the write.
        java.util.Set<String> queuedIds62 = new java.util.HashSet<String>();
        java.util.ArrayList<String> saved62 = new java.util.ArrayList<String>();
        try {
            String savedRaw62 = Prefs.savedQueue(this, sd62);
            if (savedRaw62 != null && savedRaw62.length() > 0) {
                for (String ln62 : savedRaw62.split("\n")) {
                    if (ln62.length() == 0) continue;
                    saved62.add(ln62);
                    QueueEntry qe62 = QueueEntry.parse(ln62);
                    if (qe62 != null && qe62.url.length() > 0)
                        queuedIds62.add(SiteHttp.identityUrl(qe62.url));
                }
            }
        } catch (Exception ignored) {}
        java.util.List<Integer> picked62 = PlayFromHere.pick(ids62,
                SiteHttp.identityUrl(streamUrl), queuedIds62,
                newPlayingUrl, newPlayingLibUrl);
        java.util.List<String> run62 = HistPlayFromHere.lines(ids62, sds62, titles62, picked62);
        int added62 = 0;
        for (String ln62 : run62) {
            QueueEntry qe62 = QueueEntry.parse(ln62);
            if (qe62 == null || qe62.url.length() == 0) continue;
            boolean dup62 = false;
            for (String ex62 : saved62) {
                QueueEntry qx62 = QueueEntry.parse(ex62);
                if (qx62 != null && SiteHttp.sameTalk(qx62.url, qe62.url)) { dup62 = true; break; }
            }
            if (dup62) continue;
            saved62.add(ln62);
            added62++;
        }
        Prefs.saveQueue(this, sd62, saved62);
        android.widget.Toast.makeText(NewTalksActivity.this, added62 > 0
                ? Lang.t(NewTalksActivity.this, "queue_plain_prefix") + added62 + Lang.t(NewTalksActivity.this, "queue_added_suffix")
                : Lang.t(NewTalksActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
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

    // v15.70 (slice 2079): "Go to sayadaw's talks" — the row
    // menu's navigation verb (History's menu_go_sayadaw, v15.56
    // slice 1995, on this screen's menu; the Downloaded row's
    // 1944 verb through the same GoSayadaw core; the Favourites
    // row's 2078 verb, FavGo): re-resolve the row's sayadaw
    // through the bundled catalog at tap time (catalogFor —
    // cached, canonicalized, so a renamed sayadaw still
    // resolves; the offer and the tap can never disagree),
    // toast no_sayadaw_page on a failed re-read, else open that
    // sayadaw's Talks list inside the app with Main's own intent
    // extras (name/group/url/photo). The name is the row's own
    // sayadaw (the catalog entry carries no name slot). A
    // navigation mutates nothing: no favourite, queue or resume
    // change, no pause guard (no sheet is left to return from).
    private void goToSayadawNew(final NewTalkRail.Card c) {
        if (c == null || c.e == null || c.e.sayadaw == null || c.e.sayadaw.length() == 0) return;
        final String sd = c.e.sayadaw;
        String[] cat79 = catalogFor(sd);
        if (!NewGo.canOffer(sd, cat79)) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "no_sayadaw_page"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent gi79 = new android.content.Intent(NewTalksActivity.this, TalksActivity.class);
        gi79.putExtra("name", sd);
        gi79.putExtra("group", GoSayadaw.group(cat79));
        gi79.putExtra("url", GoSayadaw.page(cat79));
        gi79.putExtra("photo", GoSayadaw.photo(cat79));
        Fx.tap(NewTalksActivity.this);
        Fx.go(NewTalksActivity.this, gi79);
    }

    // v15.70 (slice 2080): "Share talk" — the row menu's share
    // verb (History's menu_share, v13.77, on this screen's menu;
    // the chooser's long-press dialog shares first, v7.82, and
    // the library row menu has shared since v9.55): re-resolve
    // the row to its stream URL at tap time (newStreamUrlFor —
    // the offer and the tap can never disagree), toast
    // talk_no_longer_in_list when the file behind a path-form
    // row no longer resolves (the History file-share's stale
    // toast), else share the talk's text through the Android
    // sheet — sayadaw — title + stream URL + the
    // dhammadownload.com credit (NewShare.shareText, the History
    // text shape) — chooser titled share_talk. A share mutates
    // nothing: no favourite, queue or resume change; no pause
    // guard (no sheet is left to return from); the sounding row
    // keeps it (sharing never touches playback).
    private void shareTalkNew(final NewTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        final String streamUrl = newStreamUrlFor(c.e);
        if (!NewShare.canOffer(streamUrl)) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent send80 = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send80.setType("text/plain");
        send80.putExtra(android.content.Intent.EXTRA_TEXT,
                NewShare.shareText(c.e.sayadaw, c.e.title, streamUrl,
                        Lang.t(NewTalksActivity.this, "share_source")));
        Fx.tap(NewTalksActivity.this);
        startActivity(android.content.Intent.createChooser(send80,
                Lang.t(NewTalksActivity.this, "share_talk")));
    }

    // v15.70 (slice 2080): the row's stream URL for the share
    // verb — the histStreamUrlFor shape (v13.77): a URL-form row
    // canonicalises to its identity form; a path-form row (a
    // downloaded file) resolves through the dlMap reverse lookup
    // with the exactly-one-claimant rule (v8.44 — a dual-claimed
    // file names no talk rather than the wrong one), so a deleted
    // file with no claimant offers no stream verb. The dlMap is
    // parsed once per call (dlMapNow2039) and only for path-form
    // rows — URL-form rows never pay the parse.
    private String newStreamUrlFor(Prefs.HistEntry e) {
        if (e == null || e.url == null || e.url.length() == 0) return "";
        if (!e.url.startsWith("/")) return SiteHttp.identityUrl(e.url);
        try {
            return NewShare.resolve(e.url, dlMapNow2039(getSharedPreferences("dhamma", MODE_PRIVATE)));
        } catch (Exception ignored) { return ""; }
    }

    // v15.70 (slice 2081): "Copy link" — the row menu's clipboard
    // verb (History's dl_menu_copy_link, v15.53 slice 1981, on
    // this screen's menu; the library row menu has copied since
    // v6.56, the Room drawer since slice 1975, the Talks chooser
    // since slice 1977 — all through the same RoomCopyLink core,
    // so the surfaces can never disagree): re-resolve the row to
    // its stream URL at tap time (newStreamUrlFor — the offer and
    // the tap can never disagree), toast talk_no_longer_in_list
    // when the file behind a path-form row no longer resolves
    // (this screen's share already sets that toast precedent;
    // History's copy stays silent, but a visible menu verb that
    // silently does nothing is a dead tap), else copy the talk's
    // own original link verbatim (the v6.56 rule — never a bare
    // title, never a guessed link) with the v6.56 clipboard label
    // + toast (RoomCopyLink.clipText / toastText). A copy mutates
    // nothing: no favourite, queue or resume change; no pause
    // guard (no sheet is left to return from); the sounding row
    // keeps it (copying never touches playback).
    private void copyTalkLinkNew(final NewTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        final String streamUrl = newStreamUrlFor(c.e);
        if (!RoomCopyLink.canOffer(streamUrl)) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.content.ClipboardManager cm81 =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm81 != null)
                cm81.setPrimaryClip(android.content.ClipData.newPlainText(
                        Lang.t(NewTalksActivity.this, "clip_talk"), RoomCopyLink.clipText(streamUrl)));
            android.widget.Toast.makeText(NewTalksActivity.this,
                    RoomCopyLink.toastText(Lang.t(NewTalksActivity.this, "link_copied_prefix"), c.e.title, streamUrl),
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    // v15.71 (slice 2087): "Share file" — the row menu's
    // file-share verb (History's dl_menu_share_file, v15.53
    // slice 1982, on this screen's menu; the library row menu
    // has shared its file since v15.48 slice 1954, the Room
    // drawer since slice 1955, the Talks chooser since slice
    // 1956 and the History row menu since slice 1982 — all
    // through the same TalkFileShare core, so the surfaces can
    // never disagree): re-resolve the row to its stream URL at
    // tap time (newStreamUrlFor — the offer and the tap can
    // never disagree), re-check the download gate fresh
    // (newIsDownloaded + newDownloadFileNameFor — the History
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
    private void shareFileNew(final NewTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        final String streamUrl = newStreamUrlFor(c.e);
        java.io.File dir87 = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma");
        String fn87 = newIsDownloaded(streamUrl) ? newDownloadFileNameFor(streamUrl) : null;
        String uri87 = TalkFileShare.shareUriFor(dir87, fn87);
        if (uri87 == null) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent send87 = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send87.setType("audio/mpeg");
        send87.putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse(uri87));
        send87.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(android.content.Intent.createChooser(send87,
                Lang.t(NewTalksActivity.this, "share_talk")));
    }

    // v15.79 (slice 2136): "Download" — the download verb the New talks row menu lacked that the History row menu has had since v13.77, the Favourite-talks rail since v15.57 (slice 2000), the Talks chooser since v11.70, the Room drawer rows since v11.70 and the Favourites + Bookmarks row menus since v15.79 (slices 2134/2135): a new talk could be shared, copied, filed or opened, but never saved for offline listening without leaving the screen. Same verb through the same rules, so the surfaces can never disagree: tap-time re-checks (a Wi-Fi job can land, or another path can queue this talk, while the dialog sits open), a Wi-Fi-only DownloadManager request (NETWORK_WIFI, so a tap here can never silently spend mobile data — the data-saver rule), the filename from newDownloadFileNameFor, and the record under DlLock in ONE apply (newRecordDownload below — dlMap + dlSayadaw + dlQueued, identityUrl banking). Mirrors History's dl_btn_download dispatch verbatim, with the New-talks-local read side (newStreamUrlFor + newIsDownloaded + newIsDlQueued + newDownloadFileNameFor). The blank-URL guard toasts talk_no_longer_in_list — the share helpers' precedent: New talks re-resolves at tap time, so a file deleted while the menu sat open must toast, never enqueue a blank URL. No sounding gate (History offers it on sounding rows too); no pause guard (no sheet is left to return from). The adapter repaint + success toast are History's: Wi-Fi shows "\u2B07 title + dl_downloading_suffix", mobile data shows dl_room_wifi_queued.
    private void downloadTalkNew(final NewTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        final String streamUrl = newStreamUrlFor(c.e);
        if (streamUrl == null || streamUrl.length() == 0) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // Tap-time re-checks (Talks v11.70): a Wi-Fi job can land, or another path can queue this talk, while the dialog sits open.
        if (newIsDownloaded(streamUrl)) {
            if (adapter != null) adapter.notifyDataSetChanged();
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "downloaded_talk"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (newIsDlQueued(streamUrl)) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "dl_already_queued"),
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String file = newDownloadFileNameFor(streamUrl);
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
            newRecordDownload(streamUrl, file, c.e.sayadaw);
        } catch (Exception ex) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "download_failed") + Fx.errDetail(ex),
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (Fx.stripIsWifi(NewTalksActivity.this))
            android.widget.Toast.makeText(NewTalksActivity.this,
                    "\u2B07 " + (c.e.title != null && c.e.title.length() > 0 ? c.e.title : streamUrl)
                            + Lang.t(NewTalksActivity.this, "dl_downloading_suffix"),
                    android.widget.Toast.LENGTH_SHORT).show();
        else
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "dl_room_wifi_queued"),
                    android.widget.Toast.LENGTH_LONG).show();
    }

    // v15.80 (slice 2138): "Mark as listened" — the mark verb the New talks row menu lacked that the Downloaded library row menu has had since v15.47 (slice 1949), the Talks chooser since v15.51 (slice 1969) and the Room drawer dialogs since v15.52 (slices 1978/1979): a new talk heard elsewhere could only earn its listened mark by playing it to the end, and a talk already finished could not be cleared from the new list without opening the library. Same verb through the same rules, so the surfaces can never disagree: the 1949 pure core (DlMark — canOffer / labelKey / isMarked / mark, inputs never mutated), the v11.14 tap-time sounding re-check (the dialog can sit open across an auto-advance — a sounding talk earns its mark by finishing, never by this verb), the prefs-fresh playedTalks read at tap time, and the mark landing in the SAME playedTalks key AudioService banks on finish and the library, the rails, the header new-count and the filters all read. The row leaves the list at once: the refresh re-picks through NewAll.pickAll, which drops played talks (the 1920 gate), so a marked talk is gone from this screen on the next paint — the visible effect, no melt needed (there is no tick on this screen to flip). Neither the queue, the shelf order nor any saved resume position is mutated. The toast is the verb (the 1946 idiom); an already-marked tap toasts already_listened, never a false count. No sounding gate at the offer beyond DlMark.canOffer (the three sounding identities, the 2043 mark rule); no unmark leg on this screen (the list shows unplayed talks only — the label reads dl_menu_mark_one by construction).
    private void markListenedNew(final NewTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        final String streamUrl = newStreamUrlFor(c.e);
        if (streamUrl == null || streamUrl.length() == 0) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // Tap-time sounding re-check (Talks v11.14): the dialog can sit open across an auto-advance, so this talk can BE sounding by tap time — it earns its mark by finishing, never by this verb. All three sounding identities, the 2043 mark rule.
        refreshNewTalksPlayingMarks();
        if (NewPlayAll.isSounding(streamUrl, newPlayingUrl, newPlayingLibUrl, newPlayingLibPath)) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        DlMark.Result marked38 = null;
        try {
            android.content.SharedPreferences sp38 = getSharedPreferences("dhamma", MODE_PRIVATE);
            java.util.Set<String> cur38 = sp38.getStringSet("playedTalks", new java.util.HashSet<String>());
            marked38 = DlMark.mark(cur38, SiteHttp.identityUrl(streamUrl));
            sp38.edit().putStringSet("playedTalks", marked38.set).apply();
        } catch (Exception ignored) {}
        if (marked38 != null && marked38.changed) {
            // The refresh re-picks through NewAll.pickAll, which drops played talks — the marked row leaves the list at once (the 1920 gate), and the play-all run follows the new shown list.
            refresh();
        }
        android.widget.Toast.makeText(NewTalksActivity.this,
                Lang.t(NewTalksActivity.this, (marked38 != null && marked38.changed) ? "dl_menu_mark_one" : "already_listened"),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // v15.89 (slice 2196): the downloaded-badge snapshot — the set is the unfiltered pick's downloaded stream addresses, built from the unfiltered pick through the same gate the row menu's download offer reads, so the badge and the offer can never disagree about a talk; the map is parsed once per build, never per row. The refresh adopts it; the row predicate resolves the row to its stream address the same way the star does (a downloaded path-form row claims its stream address through the exactly-one-claimant resolve) and tests membership. A null list reads empty; a null entry or an unresolvable stream is skipped; the badge paints on the sounding row too — downloaded is a property of the talk, not a state. The badge alone does not gold the row — only a sounding or favourited row turns gold.
    private java.util.Set<String> newDlSetNow() {
        java.util.Set<String> out96 = new java.util.HashSet<String>();
        if (newAllItems == null) return out96;
        try {
            android.content.SharedPreferences sp96 =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONObject map96 = new org.json.JSONObject(sp96.getString("dlMap", "{}"));
            for (NewTalkRail.Card r96 : newAllItems) {
                if (r96 == null || r96.e == null) continue;
                String stream96 = newStreamUrlFor(r96.e);
                if (stream96 == null || stream96.length() == 0) continue;
                if (newIsDownloaded(stream96, map96)) {
                    String id96 = SiteHttp.identityUrl(stream96);
                    if (id96 != null) out96.add(id96);
                }
            }
        } catch (Exception ignored) {}
        return out96;
    }

    private void refreshNewDlSet() { newDlSet96 = newDlSetNow(); }

    private boolean newRowDl(Prefs.HistEntry e) {
        if (e == null) return false;
        String stream96 = newStreamUrlFor(e);
        return stream96.length() > 0 && newDlSet96 != null
                && newDlSet96.contains(SiteHttp.identityUrl(stream96));
    }

    // v15.71 (slice 2087): History histIsDownloaded (v13.77),
    // New-talks-local — the dlMap mapped file with the v13.66
    // length gate (a 0-byte file is not downloaded), plus the
    // plain basename leg for files the app enqueued under a
    // stripped name. The offer and the tap both read it fresh,
    // so a download landing while the dialog sits open can
    // never be missed and a deletion can never be served.
    private boolean newIsDownloaded(String url) {
        if (url == null || url.length() == 0) return false;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
            return newIsDownloaded(url, map);
        } catch (Exception ignored) { return false; }
    }

    // v15.89 (slice 2196): the same gate with a caller-supplied map — the snapshot builder parses the map once per build and tests every row against it, instead of re-parsing per row; the one-argument form above keeps its exact behaviour (a fresh map per call) for the menu offers.
    private boolean newIsDownloaded(String url, org.json.JSONObject map) {
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

    // v15.71 (slice 2087): History histDownloadFileNameFor
    // (v13.77), New-talks-local — the dlMap name wins, else the
    // basename stripped of ?/# with .mp3 guaranteed, and a
    // basename another URL's download claims takes the 8-hex MD5
    // suffix (v8.40 claim-alone rule).
    private String newDownloadFileNameFor(String url) {
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

    // v15.79 (slice 2136): History histIsDlQueued (v13.77),
    // New-talks-local — fresh prefs read at offer/tap time, so a
    // job queued from the Room a second earlier still blocks a
    // double enqueue. Raw-equals scan like History's — the banked
    // form is identityUrl and the offer and the tap read the same
    // resolved stream URL.
    private boolean newIsDlQueued(String url) {
        if (url == null) return false;
        try {
            org.json.JSONArray qa = new org.json.JSONArray(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlQueued", "[]"));
            for (int i = 0; i < qa.length(); i++)
                if (url.equals(qa.optString(i))) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // v15.79 (slice 2136): History histRecordDownload (v13.77),
    // New-talks-local — dlMap, dlSayadaw and dlQueued land in ONE
    // apply under DlLock.LOCK (v13.68/v13.70), the URL banked in
    // identity form (v13.71).
    private void newRecordDownload(String url, String file, String sd) {
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

    // v15.72 (slice 2092): "Add to playlist" — the row menu's
    // playlist verb (the Room's showPlaylistAddDialog, v12.65–
    // v12.80, on this screen's menu; the Room's dialog has no
    // entry point anywhere in the app — defined once, called
    // zero times — so this is the verb's second live offer, on
    // the screen of new talks, where "a new talk -> my morning
    // / bedtime list" is the natural flow; premium backlog #2,
    // the Spotify / YouTube Music / Apple Music row-menu verb):
    // re-resolve the row to its stream URL at tap time
    // (newStreamUrlFor — the offer and the tap can never
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
    // (NewPlAdd.toastKey: added / already there / full /
    // could-not-create); the new-list row opens the name
    // dialog. An add mutates no favourite, queue or resume
    // state; no pause guard (no sheet is left to return from);
    // the sounding row keeps it (filing never touches
    // playback).
    private void addToPlaylistNew(final NewTalkRail.Card c) {
        if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return;
        final String streamUrl = newStreamUrlFor(c.e);
        if (!NewPlAdd.canOffer(streamUrl)) {
            android.widget.Toast.makeText(NewTalksActivity.this,
                    Lang.t(NewTalksActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String sd92 = c.e.sayadaw;
        String[] cat92 = catalogFor(sd92);
        if (cat92 == null || cat92[1] == null || cat92[1].length() == 0) {
            String owner92 = Prefs.sayadawForTalk(this, "lib", streamUrl, "");
            if (owner92 != null && owner92.length() > 0 && !owner92.equals(sd92)) {
                sd92 = owner92;
            }
        }
        final String title92 = c.e.title != null ? c.e.title : "";
        final String sdF92 = sd92;
        final java.util.List<String> names92 = Prefs.playlistNames(this);
        final String[] items92 = new String[names92.size() + 1];
        for (int i92 = 0; i92 < names92.size(); i92++) items92[i92] = names92.get(i92);
        items92[names92.size()] = Lang.t(this, "pl_new");
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "pl_choose"))
                .setItems(items92, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (NewPlAdd.isNewRow(which, names92.size())) {
                            showNewPlaylistNew(streamUrl, title92, sdF92);
                            return;
                        }
                        int r92 = Prefs.playlistAdd(NewTalksActivity.this,
                                names92.get(which), streamUrl, sdF92, title92);
                        android.widget.Toast.makeText(NewTalksActivity.this,
                                Lang.t(NewTalksActivity.this, NewPlAdd.toastKey(r92)),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // v15.72 (slice 2092): the "new list" dialog for the
    // playlist verb — the Room's showNewPlaylistDialog
    // (v12.80), on this screen: a name field (pl_name_hint),
    // a Create button (pl_create) and a cancel (btn_no_clear);
    // an empty name re-prompts with the hint toast; the name
    // caps at 40 chars (PLAYLIST_MAX_NAME); create is a
    // no-op for an existing name, so a repeat name just adds
    // to that list; at the 20-list cap a genuinely new name
    // toasts pl_limit (NewPlAdd.limitToastKey), any other
    // create failure pl_no_create — neither falls through to
    // the add (the v12.80 leg). The add toasts the same
    // v12.80 result as the choose dialog.
    private void showNewPlaylistNew(final String url, final String title, final String sd) {
        final android.widget.EditText et92 = new android.widget.EditText(this);
        et92.setHint(Lang.t(this, "pl_name_hint"));
        et92.setSingleLine(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "pl_new"))
                .setView(et92)
                .setPositiveButton(Lang.t(this, "pl_create"),
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        String name92 = et92.getText().toString().trim();
                        if (name92.length() == 0) {
                            android.widget.Toast.makeText(NewTalksActivity.this,
                                    Lang.t(NewTalksActivity.this, "pl_name_hint"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (name92.length() > 40) name92 = name92.substring(0, 40);
                        boolean created92 = Prefs.playlistCreate(NewTalksActivity.this, name92);
                        java.util.List<String> held92 = Prefs.playlistNames(NewTalksActivity.this);
                        String limit92 = NewPlAdd.limitToastKey(created92,
                                held92.contains(name92), held92.size());
                        if (limit92 != null) {
                            android.widget.Toast.makeText(NewTalksActivity.this,
                                    Lang.t(NewTalksActivity.this, limit92),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        int r92 = Prefs.playlistAdd(NewTalksActivity.this, name92, url, sd, title);
                        android.widget.Toast.makeText(NewTalksActivity.this,
                                Lang.t(NewTalksActivity.this, NewPlAdd.toastKey(r92)),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(Lang.t(this, "btn_no_clear"), null)
                .show();
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
    // v15.85 (slice 2169): the favourite-star snapshot (History v13.77, verbatim shape) — newFavSetNow copies the favTalks set (migrated first, the v13.66 rule — a pre-migration http URL must star its https twin); refreshNewFavSet adopts it; newRowFav resolves the row's stream URL through the existing newStreamUrlFor (a downloaded path-form row claims its stream URL, the 2084 exactly-one-claimant resolve) and tests membership, so a new talk stars exactly when its stream twin does. Null stream names no talk.
    private java.util.Set<String> newFavSetNow() {
        try {
            Prefs.migrateLegacyHttpRows66(this);
            java.util.Set<String> ft = getSharedPreferences("dhamma", MODE_PRIVATE)
                    .getStringSet("favTalks", null);
            return ft == null ? new java.util.HashSet<String>()
                    : new java.util.HashSet<String>(ft);
        } catch (Exception ignored) {
            return new java.util.HashSet<String>();
        }
    }

    private void refreshNewFavSet() {
        newFavSet = newFavSetNow();
    }

    private boolean newRowFav(Prefs.HistEntry e) {
        if (e == null) return false;
        String stream = newStreamUrlFor(e);
        return stream.length() > 0 && newFavSet != null
                && newFavSet.contains(stream);
    }

    // v15.86 (slice 2176): the duration snapshot (the chooser 1094 clock shape) — newDurCacheNow parses the durations store once (Prefs.talkDurMap, the talkPosKeysOver shape); refreshNewDurCache adopts it; newRowDurMs resolves the row's stream URL through newStreamUrlFor (a downloaded path-form row claims its stream URL, the 2084 reverse lookup) and reads its length, falling back to the row's own URL for a downloaded row whose length was banked under its path form (talkDurPutBoth banks both forms, the v13.96 rule), so a new talk clocks exactly when its stream twin does. Null stream names no talk; an unknown length reads 0 — the clock hides, never guesses.
    private java.util.Map<String, Integer> newDurCacheNow() { return Prefs.talkDurMap(this); }

    private void refreshNewDurCache() { newDurCache = newDurCacheNow(); }

    private int newRowDurMs(Prefs.HistEntry e) {
        if (e == null || newDurCache == null) return 0;
        Integer d76 = null;
        String stream = newStreamUrlFor(e);
        if (stream != null && stream.length() > 0) d76 = newDurCache.get(SiteHttp.identityUrl(stream));
        if ((d76 == null || d76.intValue() <= 0) && e.url != null) d76 = newDurCache.get(e.url);
        return d76 != null && d76.intValue() > 0 ? d76.intValue() : 0;
    }

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
        // v15.85 (slice 2169): the star snapshot joins the change detector — a star
        // flipped from the notification card rebinds the rows here (History v13.77).
        java.util.Set<String> newFavNow69 = newFavSetNow();
        // v15.89 (slice 2196): the badge snapshot joins the change detector — a download landing while the list sits open rebinds the rows here.
        java.util.Set<String> newDlNow96 = newDlSetNow();
        // v15.86 (slice 2176): the duration snapshot joins the change detector — a length
        // landing while the list sits open (a talk just prepared banks its duration) rebinds the rows here.
        java.util.Map<String, Integer> newDurNow76 = newDurCacheNow();
        if (adapter != null && (!newPlayingUrl.equals(newRowPlayingUrl == null ? "" : newRowPlayingUrl)
                || !newPlayingLibUrl.equals(newRowPlayingLibUrl == null ? "" : newRowPlayingLibUrl)
                || !newPlayingLibPath.equals(newRowPlayingLibPath == null ? "" : newRowPlayingLibPath)
                || AudioService.liveEnded != newRowEnded
                || !newFavNow69.equals(newFavSet)
                || !newDlNow96.equals(newDlSet96)
                || !newDurNow76.equals(newDurCache))) {
            newRowPlayingUrl = newPlayingUrl;
            newRowPlayingLibUrl = newPlayingLibUrl;
            newRowPlayingLibPath = newPlayingLibPath;
            newRowEnded = AudioService.liveEnded;
            newFavSet = newFavNow69;
            newDlSet96 = newDlNow96;
            newDurCache = newDurNow76;
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
                // v16.03 (slice 2285): the resume strip's listened tick, lib branch — the
                // same exactly-one-claimant resolve as the star above, read against playedTalks
                // instead of favTalks (canonical compare, the 2282 card precedent); a
                // dual-claimed file never earns a tick (the v8.49 rule).
                boolean lListened = false;
                try {
                    java.util.Set<String> pt = rsp.getStringSet("playedTalks", null);
                    if (pt != null) {
                        String fn = new java.io.File(lPath).getName();
                        org.json.JSONObject map = new org.json.JSONObject(rsp.getString("dlMap", "{}"));
                        java.util.Iterator<String> keys = map.keys();
                        int nClaim49 = 0;
                        String onlyClaim49 = null;
                        while (keys.hasNext()) {
                            String u = keys.next();
                            if (fn.equals(map.optString(u, ""))) { nClaim49++; onlyClaim49 = u; }
                        }
                        if (nClaim49 == 1 && onlyClaim49 != null && pt.contains(SiteHttp.identityUrl(onlyClaim49))) lListened = true;
                    }
                } catch (Exception ignored) {}
                int lSec = rPos / 1000;
                String lTime = Fx.stripResumeTime(lSec);
                // v15.40 (slice 1907): the stored duration (v13.96) fills the bar — Main's v14.03 rule; unknown duration still reads empty (stripResumeProgress).
                int lDur = Fx.stripResumeDuration(NewTalksActivity.this, rsp, rSavedUrl, lPath);
                // v8.52: Main's v7.94 resume mark, mirrored — the proven file is offline, like Main's lib branch.
                meltResumeStripTitleNew(tx, "↺ " + lTitle + (lFav ? " ★" : "") + (Fx.stripInProgressDlFilenames(NewTalksActivity.this, rsp).contains(new java.io.File(lPath).getName()) ? " ⏳" : " ⬇") + (lListened ? " ✓" : ""));
                // v5.46: a dead library strip names its recorded
                // sayadaw like the live strip since v5.45, in the
                // Room strip's "name · mm:ss မှ ဆက်နားထောင်ရန်"
                // shape; an unrecorded download keeps today's
                // generic line, never a guessed name.
                String libSd = Fx.stripLibSayadaw(NewTalksActivity.this, rsp);
                if (sb != null) sb.setText(libSd != null ? libSd + Fx.stripQueuePosSuffix(rsp, rKind) + " · " + lTime + Lang.t(NewTalksActivity.this, "resume_from") : Lang.t(NewTalksActivity.this, "downloaded_talk") + Fx.stripQueuePosSuffix(rsp, rKind) + " · " + lTime + Lang.t(NewTalksActivity.this, "resume_from"));
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
            // v16.03 (slice 2285): the resume strip's listened tick — the room branch's talk
            // earns the tick after the star/download mark when it is in playedTalks (canonical
            // compare, the 2280 strip order); a not-listened talk reads exactly as today.
            boolean rListened = false;
            try {
                java.util.Set<String> pt = rsp.getStringSet("playedTalks", null);
                if (pt != null) rListened = pt.contains(SiteHttp.identityUrl(rUrl));
            } catch (Exception ignored) {}
            int rSec = rPos / 1000;
            String rTime = Fx.stripResumeTime(rSec);
            int rDur = Fx.stripResumeDuration(NewTalksActivity.this, rsp, rUrl, null);
            // v8.52: Main's v7.94 resume download mark, mirrored — a ↺ strip here never said whether resuming streams or plays offline.
            meltResumeStripTitleNew(tx, "↺ " + rTitle + (rFav ? " ★" : "") + Fx.stripResumeDownloadMark(NewTalksActivity.this, rsp, rUrl) + (rListened ? " ✓" : ""));
            if (sb != null) sb.setText(rName + Fx.stripQueuePosSuffix(rsp, rKind) + " · " + rTime + Lang.t(NewTalksActivity.this, "resume_from"));
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
        // v16.02 (slice 2280): strip listened tick — the Room line
        // (v15.99) and the rows carry the tick, the strips never
        // did; it lands after the star (no offline-mark block on
        // this strip), the strip's own suffix order.
        text += Fx.stripListenedTick(NewTalksActivity.this, sp);
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
        // v16.01 (slice 2271): strip queue position — the Room's
        // "N / M" lands after the sayadaw name, before the clock.
        if (subText != null) subText += Fx.stripQueuePosSuffix(sp, kind) + Fx.stripClockSuffixForStrip(pb, AudioService.livePositionMs, AudioService.liveDurationMs, Prefs.stripClockRemaining(NewTalksActivity.this));
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

    // v15.97 (slice 2245): the New talks row swipe — the gesture twin of the shared row swipe on this screen: a left drag marks that one talk listened, a right drag adds it to its sayadaw's queue, with the gold strip + action label revealed under the card as it travels. The DOWN records the candidate row and both offers and returns false, so the tap, the long-press menu and the scroll still track; the MOVE arms only on a horizontal-dominant drag in an offered direction, lifts the row into its card first, then takes the gesture; the UP commits past the threshold and glides home before firing. Never arms on a row with no stream address to mark or queue, never on the sounding row (both verbs exclude it), never right on an already-queued row or with no sayadaw to queue into or on a path-form row (the queue verb takes the row's own URL and refuses a downloaded file — the menu offers nothing there either), never left on a talk with no address to mark. Rows here are built fresh per bind with no recycling, so the card is re-resolved from the list on every MOVE and at UP — a rebind landing mid-drag replaces the view, and the drag follows the new view instead of a stale one. The left drag is mark-only: this screen lists unplayed talks only, so the row leaves the list the moment it is marked and there is no unmark leg to offer.
    private boolean ntSwipeOnTouch45(android.view.MotionEvent ev) {
        if (listView == null) return false;
        int action = ev.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            ntSwipeReset45();
            int pos = listView.pointToPosition((int) ev.getX(), (int) ev.getY());
            if (pos == android.widget.AdapterView.INVALID_POSITION) return false;
            if (pos < 0 || pos >= items.size()) return false;
            NewTalkRail.Card c = items.get(pos);
            if (c == null || c.e == null || c.e.url == null || c.e.url.length() == 0) return false;
            String stream = newStreamUrlFor(c.e);
            if (stream == null || stream.length() == 0) return false;
            String sd = c.e.sayadaw;
            refreshNewTalksPlayingMarks();
            boolean soundingMark = NewPlayAll.isSounding(stream, newPlayingUrl, newPlayingLibUrl, newPlayingLibPath);
            boolean soundingQueue = NewPlayAll.isSounding(c.e.url, newPlayingUrl, newPlayingLibUrl, newPlayingLibPath);
            boolean queued = ntSwipeQueueHas45(sd, c.e.url);
            boolean leftOk = DlMark.canOffer(stream, soundingMark);
            boolean rightOk = sd != null && sd.length() > 0 && !c.e.url.startsWith("/") && DlSwipe.offerRight(soundingQueue, queued);
            if (!leftOk && !rightOk) return false;
            if (ntSwipeSlop45 < 0) ntSwipeSlop45 = android.view.ViewConfiguration.get(NewTalksActivity.this).getScaledTouchSlop();
            ntSwipeRow45 = c;
            ntSwipeStream45 = stream;
            ntSwipeSd45 = sd;
            ntSwipeDownX45 = ev.getX();
            ntSwipeDownY45 = ev.getY();
            return false;
        }
        if (ntSwipeRow45 == null) return false;
        if (action == android.view.MotionEvent.ACTION_MOVE) {
            float dx = ev.getX() - ntSwipeDownX45;
            float dy = ev.getY() - ntSwipeDownY45;
            if (!ntSwipeArmed45) {
                if (!DlSwipe.dominant(dx, dy, ntSwipeSlop45)) return false;
                int dir = DlSwipe.direction(dx);
                if (dir == DlSwipe.LEFT && !ntSwipeLeftOk45) { ntSwipeReset45(); return false; }
                if (dir == DlSwipe.RIGHT && !ntSwipeRightOk45) { ntSwipeReset45(); return false; }
                if (dir == DlSwipe.NONE) return false;
                android.view.View wrap = ntSwipeWrap45();
                if (wrap == null) { ntSwipeReset45(); return false; }
                android.view.View under = wrap.findViewById(R.id.ntSwipeUnder45);
                android.view.View card = wrap.findViewById(R.id.ntSwipeCard45);
                if (under == null || card == null) { ntSwipeReset45(); return false; }
                ntSwipeArmed45 = true;
                ntSwipeDir45 = dir;
                // The row lifts into its card as the drag starts:
                // at rest the row is plain text on the gradient,
                // so the gold strip would show through it — the
                // strip appears and the card goes opaque together,
                // and both clear when the gesture ends, so the
                // screen at rest keeps exactly today's look.
                under.setVisibility(android.view.View.VISIBLE);
                card.setBackgroundResource(night ? R.drawable.card_bg_night : R.drawable.card_bg);
                listView.requestDisallowInterceptTouchEvent(true);
            }
            android.view.View wrap = ntSwipeWrap45();
            if (wrap == null) { ntSwipeReset45(); return true; }
            android.view.View under = wrap.findViewById(R.id.ntSwipeUnder45);
            android.view.View card = wrap.findViewById(R.id.ntSwipeCard45);
            if (card == null) { ntSwipeReset45(); return true; }
            if (under != null) under.setVisibility(android.view.View.VISIBLE);
            card.setBackgroundResource(night ? R.drawable.card_bg_night : R.drawable.card_bg);
            card.setTranslationX(DlSwipe.clampDir(ev.getX() - ntSwipeDownX45, ntSwipeDir45, card.getWidth()));
            return true;
        }
        if (action == android.view.MotionEvent.ACTION_UP) {
            if (!ntSwipeArmed45) { ntSwipeReset45(); return false; }
            android.view.View wrap = ntSwipeWrap45();
            android.view.View card = wrap == null ? null : wrap.findViewById(R.id.ntSwipeCard45);
            float dx = card == null ? 0f : card.getTranslationX();
            boolean fire = card != null && DlSwipe.commit(dx, card.getWidth(), ntSwipeMinCommit45());
            ntSwipeSettle45(card, ntSwipeDir45, ntSwipeRow45, fire);
            return true;
        }
        if (action == android.view.MotionEvent.ACTION_CANCEL
                || action == android.view.MotionEvent.ACTION_POINTER_DOWN) {
            if (!ntSwipeArmed45) { ntSwipeReset45(); return false; }
            android.view.View wrap = ntSwipeWrap45();
            ntSwipeSettle45(wrap == null ? null : wrap.findViewById(R.id.ntSwipeCard45), ntSwipeDir45, null, false);
            return true;
        }
        return false;
    }

    // The 96dp commit floor in px — the core takes the floor as
    // an argument so the pure math never reads a resource.
    private float ntSwipeMinCommit45() {
        return 96f * getResources().getDisplayMetrics().density;
    }

    // v15.97 (slice 2245): glide the card home over 200ms Decelerate (the app's melt rhythm), then fire the verb — the glide is the feedback, the toast follows it. A cancelled settle never fires: the cancel flag is checked on animation end, so a fresh DOWN that cuts a settle short cannot queue a stale verb. The swipe fields stay set until the animation ends, so a rebind landing mid-glide never snaps the card (the fresh row is built at rest, and the reset clears the old one).
    private void ntSwipeSettle45(final android.view.View card, final int dir, final NewTalkRail.Card c, final boolean fire) {
        if (ntSwipeSettle45 != null) ntSwipeSettle45.cancel();
        ntSwipeSettle45 = null;
        if (card == null) { ntSwipeReset45(); return; }
        final float start = card.getTranslationX();
        if (start == 0f) { ntSwipeReset45(); return; }
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(start, 0f);
        ntSwipeSettle45 = anim;
        anim.setDuration(200);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                card.setTranslationX((Float) a.getAnimatedValue());
            }
        });
        final boolean[] cancelled45 = new boolean[1];
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationCancel(android.animation.Animator a) {
                cancelled45[0] = true;
            }
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (ntSwipeSettle45 == anim) ntSwipeSettle45 = null;
                if (!cancelled45[0] && fire && c != null && dir == DlSwipe.LEFT) applyNtSwipeMark45(c);
                else if (!cancelled45[0] && fire && c != null && dir == DlSwipe.RIGHT) applyNtSwipeQueueAdd45(c);
                ntSwipeReset45();
            }
        });
        anim.start();
    }

    private void ntSwipeReset45() {
        if (ntSwipeSettle45 != null) {
            android.animation.ValueAnimator a = ntSwipeSettle45;
            ntSwipeSettle45 = null;
            a.cancel();
        }
        // A fresh DOWN, a cancel or a settle end clears the live row back to rest: the card glides home on its own, but the strip hides and the card goes plain here, so a row can never keep gold or a card after its gesture ends. The row is found again at the DOWN point (a rebind may have replaced it); a row that scrolled away is already at rest, so there is nothing to clear.
        android.view.View wrap = ntSwipeWrap45();
        if (wrap != null) {
            android.view.View under = wrap.findViewById(R.id.ntSwipeUnder45);
            if (under != null) under.setVisibility(android.view.View.GONE);
            android.view.View card = wrap.findViewById(R.id.ntSwipeCard45);
            if (card != null) {
                card.setTranslationX(0f);
                card.setBackground(null);
            }
        }
        ntSwipeRow45 = null;
        ntSwipeStream45 = null;
        ntSwipeSd45 = null;
        ntSwipeArmed45 = false;
        ntSwipeDir45 = 0;
        ntSwipeLeftOk45 = false;
        ntSwipeRightOk45 = false;
        ntSwipeDownX45 = 0f;
        ntSwipeDownY45 = 0f;
    }

    // v15.97 (slice 2245): the swipe-left commit — the mark verb fired by the gesture: the row menu's own mark verb as a twin, so the swipe and the long-press can never disagree. The verb itself re-checks the sounding state at tap time, reads the prefs-fresh set, toasts already-marked on the race, and refreshes the list when the mark lands — so this twin adds no second read of the mark state and no rebind of its own; the row leaves the list through the pick's played gate on that refresh.
    private void applyNtSwipeMark45(NewTalkRail.Card c) {
        if (c == null) return;
        markListenedNew(c);
    }

    // v15.97 (slice 2245): the swipe-right commit — the queue-add verb fired by the gesture: the row menu's own queue-add branch as a twin, so the swipe and the long-press can never disagree. The verb itself re-checks the sounding state at tap time and toasts already-in-queue or added, so this twin adds only the rebind — queueing changes no row the list renders, but the list rebinds here so the row's queue state reads right at once.
    private void applyNtSwipeQueueAdd45(NewTalkRail.Card c) {
        if (c == null) return;
        queueAddNew(c);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    // The live row, re-resolved from the list at the DOWN point on every call — rows here are built fresh per bind, so a rebind landing mid-drag replaces the view under a held finger. The resolved row must still be the DOWN row (same object), or the drag has moved on and there is nothing to follow.
    private android.view.View ntSwipeWrap45() {
        if (listView == null || ntSwipeRow45 == null) return null;
        int pos = listView.pointToPosition((int) ntSwipeDownX45, (int) ntSwipeDownY45);
        if (pos == android.widget.AdapterView.INVALID_POSITION) return null;
        if (pos < 0 || pos >= items.size()) return null;
        NewTalkRail.Card cur = items.get(pos);
        if (cur != ntSwipeRow45) return null;
        return listView.getChildAt(pos - listView.getFirstVisiblePosition());
    }

    // Whether the DOWN row's talk already sits in its sayadaw's saved queue — the saved lines are read fresh here (the menu reads them the same way per open), and the queue's own membership test answers, so the offer and the menu can never disagree about a queued talk. The test reads the row's own URL, the same address the menu's queue key checks.
    private boolean ntSwipeQueueHas45(String sd, String url) {
        if (sd == null || sd.length() == 0 || url == null || url.length() == 0) return false;
        try {
            java.util.ArrayList<String> saved45 = new java.util.ArrayList<String>();
            String raw45 = Prefs.savedQueue(this, sd);
            if (raw45 != null && raw45.length() > 0) {
                for (String ln45 : raw45.split("\n")) {
                    if (ln45.length() == 0) continue;
                    saved45.add(ln45);
                }
            }
            return NewQueue.isQueued(url, saved45);
        } catch (Exception ignored) { return false; }
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
