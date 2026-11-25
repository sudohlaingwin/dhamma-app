package com.h2h.dhamma;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

// v12.66: Listening history / stats (premium backlog #3, user order
// 2026-11-06): total hours listened, day streak, most-listened
// sayadaw, and the recent talks — one screen, three gold stat cards
// on top, the recent list beneath. The numbers are real: the
// service banks actually-listened seconds while a talk is sounding
// (1s tick, 60s flush + pause/stop/end flush), never while
// buffering or paused, into one prefs key ("listenStats66").
// Talk titles and sayadaw names are content and stay Burmese in
// every UI language, like everywhere else in the app.
//
// Playback rides the hand queue, not a new player: tapping a
// recent talk writes its single v2 line into that sayadaw's saved
// queue and opens its Room with autoUrl, so the Room's own play
// pops the head exactly like a queued talk, and repeat / shuffle /
// autoplay / the data-saver gate all behave exactly as they do for
// a queue. Playing a recent talk REPLACES that sayadaw's hand
// queue (the "play this talk now" semantic). A path-form row is
// the exception (v12.82): it IS a downloaded file, and no Room
// can play it — the load completion matches stream URLs only —
// so it plays directly as a library session and opens the
// library instead.
public class HistoryActivity extends Activity {
    private TextView headerTitle;
    private Button clearBtn;
    private TextView totalValue;
    private TextView totalLabel;
    private TextView streakValue;
    private TextView streakLabel;
    private TextView topValue;
    private TextView topSub;
    private TextView topLabel;
    private TextView recentLabel;
    private ListView listView;
    private TextView emptyText;
    private java.util.List<Prefs.HistEntry> items =
            new java.util.ArrayList<Prefs.HistEntry>();
    // v15.65 (slice 2050): the search keeps the unfiltered pick
    // beside the shown list — a keystroke re-filters in memory
    // (the Downloaded applyDlFilter shape), never re-reading the
    // stats store per keystroke.
    private java.util.List<Prefs.HistEntry> hsAllItems =
            new java.util.ArrayList<Prefs.HistEntry>();
    // v15.96 (slice 2234): the total behind the header — the unfiltered pick's size, captured beside the pick in refresh(); the header paints it, the search never touches it.
    private int hsTotal34 = 0;
    private BaseAdapter adapter;
    private boolean night;
    private java.util.Map<String, String[]> catCache;
    // v13.54: real mini-player on this screen (the same strip as
    // Talks' v4.31) — the History screen used to show nothing
    // about a playing recent talk: tapping one opened the Room
    // and Back returned here with no way to see, pause or jump
    // back to the talk. Same 1s-tick shape as Talks: stripHandler +
    // stripTick, hsResumeStripMode (the ↺ resume branch), and
    // hsStripPhotoName (the face the strip last showed).
    private android.os.Handler stripHandler = new android.os.Handler();
    private Runnable stripTick;
    private boolean hsResumeStripMode = false;
    private String hsStripPhotoName = null;
    private boolean lastHsStripPhotoRing = false;
    // v13.55: playing-row mark (Talks v4.89/v11.07, same rules) — the History rows
    // carried no live marks: a listener browsing recents could not spot the talk the strip is
    // naming without remembering its title. The sounding identities below mirror Talks'
    // refreshQueueMarks playing half: histPlayingUrl for a Room session (lastUrl), histPlayingLibUrl
    // for a library session via the dlMap reverse lookup (exactly-one claimant, v8.44), and
    // histPlayingLibPath for a library session matched directly — a path-form row IS the file
    // (playRecent v12.82 writes the same path), so no lookup is needed there. histRowPlayingUrl /
    // histRowPlayingLibUrl / histRowPlayingLibPath / histRowEnded are the last bound snapshots;
    // the 1s tick rebinds once when any of them changes.
    private String histPlayingUrl = "";
    private String histPlayingLibUrl = "";
    private String histPlayingLibPath = "";
    private String histRowPlayingUrl = null;
    private String histRowPlayingLibUrl = null;
    private String histRowPlayingLibPath = null;
    private boolean histRowEnded = false;

    // v13.77 (slice 1069): favourite snapshot for the row star mark —
    // the identity-form URLs in favTalks, refreshed with the list
    // (refresh) and re-checked on the 1s tick's change detector, so a
    // star flipped from the notification card shows here too.
    private java.util.Set<String> histFavSet = new java.util.HashSet<String>();
    // v15.85 (slice 2172): the listened-tick snapshot for the rows — histPlayedSet is the playedTalks set copied once per refresh (never read per row per bind, the v1.59 rule); refreshed on refresh() and on the 1s tick's change detector, so a tick flipped from the notification card or the mark verb rebinds the rows here.
    private java.util.Set<String> histPlayedSet = new java.util.HashSet<String>();
    // v15.86 (slice 2178): the total-length snapshot for the rows — histDurCache is the banked talk lengths (Prefs.talkDurMap, one parse per refresh, never talkDurGet per row per bind, the v13.97 rule); refreshed on refresh() and on the 1s tick's change detector, so a length landing while the list sits open rebinds the rows here.
    private java.util.Map<String, Integer> histDurCache = new java.util.HashMap<String, Integer>();
    // v15.91 (slice 2204): the resume-spot snapshot for the rows — histPosCache is the per-talk resume spots (one parse per refresh, never a per-row store test, the v5.69 rule); refreshed on refresh() and on the strip tick's change detector, so a spot landing while the list sits open rebinds the rows here.
    private java.util.Map<String, Integer> histPosCache = new java.util.HashMap<String, Integer>();
    // v15.89 (slice 2193): the downloaded-badge snapshot for the rows — the identity forms of the recent list's stream addresses that are already on the phone, built once per refresh from the unfiltered pick, refreshed on refresh and on the one-second tick's change detector, so a download landing while the list sits open rebinds the rows here.
    private java.util.Set<String> histDlSet93 = new java.util.HashSet<String>();
    // v15.62 (slice 2030): "Play from here" debounce — its own
    // clock (the chooser's 700ms rule, v12.28: an invalid tap
    // never consumes the window).
    private long lastHistPlayFromHereMs = 0;
    // v15.83 (slice 2158): "Queue from here" debounce — its own
    // clock beside lastHistPlayFromHereMs (the chooser's 700ms rule, v12.28: an invalid tap
    // never consumes the window).
    private long lastHistQueueFromHereMs = 0;
    // v15.65 (slice 2050): search on this screen — the field, the
    // clear X, the live query and the keystroke flag (the
    // Downloaded dlSearchField / dlClearBtn / dlQuery /
    // dlSearchKeystroke shape, v1.51 + v15.33). The query is the
    // trimmed field text; the flag is true only while the watcher
    // drives a pass, so the X melts on keystrokes and lands
    // instantly on silent passes (rotation / restore / onResume).
    private android.widget.EditText hsSearchField = null;
    private android.view.View hsClearBtn = null;
    private String hsQuery = "";
    private boolean hsSearchKeystroke = false;
    // v15.68 (slice 2068): sort on this screen — the button and the
    // persisted mode (the Downloaded dlSortMode shape, v1.47/v4.24,
    // itself the Bookmarks bmSortMode shape, v15.65 slice 2051).
    // The mode is read from Prefs in wireHsSort(); the button label
    // names the CURRENT order (hsSortLabel), so the order reads in
    // a still. Rotation recreates the activity and re-reads the
    // persisted mode — no watcher or saved-state needed.
    private android.widget.Button hsSortBtn = null;
    private int hsSortMode = 0;
    // v15.73 (slice 2099): "Shuffle play" on this screen — the chooser's v13.93 shuffle-play, now on the History screen: one tap shuffles the shown recent run into the queue and opens the Room on the first shuffled talk. lastHsShuffleMs is the handler's own 700ms debounce clock (an invalid tap never consumes the window, v12.28); btnHsShuffle is the header button beside Sort (GONE until refreshHsShuffleBtn shows it, riding the shown-run gate — the 2065 rule, so the verb never sits live over an empty list); hsShuffleShown is the last-painted state for the painter's change gate.
    private android.widget.Button btnHsShuffle = null;
    private long lastHsShuffleMs = 0;
    private boolean hsShuffleShown = false;
    // v15.74 (slice 2102): "Play all" on this screen — the chooser's v13.76 Play All, now on the History screen: one tap queues the shown recent run in order and opens the Room on the first talk at its saved spot. lastHsPlayAllMs is the handler's own 700ms debounce clock (an invalid tap never consumes the window, v12.28); btnHsPlayAll is the header button before Shuffle (GONE until refreshHsPlayAllBtn shows it, riding the shown-run gate — the 2065 rule, so the verb never sits live over an empty list); hsPlayAllShown is the last-painted state for the painter's change gate.
    private android.widget.Button btnHsPlayAll = null;
    private long lastHsPlayAllMs = 0;
    private boolean hsPlayAllShown = false;
    // v15.67 (slice 2063): "Jump to the playing talk" — the
    // floating gold ▶ over the list's bottom-end corner; hsPlayingShown is the last-painted state
    // for the painter's change gate, and hsJumpTargetIdx is the
    // cached SHOWN-list index of the sounding talk (recomputed on
    // search/refresh and on sounding change, never on scroll —
    // the 1994 pattern with a cache, so a fling never pays a
    // sameTalk scan per scroll event).
    private android.view.View hsPlayingBtn = null;
    private boolean hsPlayingShown = false;
    private int hsJumpTargetIdx = -1;
    // v15.75 (slice 2107): "Back to top" on this screen — the Talks 1940 / Downloaded 2003 button, now on the first full-list screen without one: a history grows with every listen, so its header is off-screen exactly when the jump is needed. hsTopBtn is the floating gold ↑ button (GONE until ScrollTop.visible — the first visible row passes 8 — the 1940 shape, on the shown list's own scroll, not the sounding row); hsTopShown is the last-painted state for the painter's change gate. Back to top landed in slice 2107.
    private android.view.View hsTopBtn = null;
    private boolean hsTopShown = false;
    // v15.97 (slice 2242): History row swipe — the live drag state for this screen's gesture twin of the library swipe: a left drag marks (or un-marks) that one talk listened, a right drag adds it to its sayadaw's queue. The entry is the DOWN-time row (a rebind can move rows under a held finger, the commit acts on the DOWN row, never a re-resolved one); the stream address and sayadaw are resolved at DOWN time; the listened flag is the DOWN-time snapshot the row's own tick reads. The gesture math stays in the shared core; this activity owns the touch stream, the prefs, the queue and the toasts.
    private Prefs.HistEntry hsSwipeEntry42 = null;
    private String hsSwipeStream42 = null;
    private String hsSwipeSd42 = null;
    private float hsSwipeDownX42 = 0f;
    private float hsSwipeDownY42 = 0f;
    private boolean hsSwipeArmed42 = false;
    private int hsSwipeDir42 = 0;
    private boolean hsSwipeLeftOk42 = false;
    private boolean hsSwipeRightOk42 = false;
    private int hsSwipeSlop42 = -1;
    private android.animation.ValueAnimator hsSwipeSettle42 = null;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        night = Prefs.isNight(this);
        Fx.applySystemBars(this, night); // v13.07: bars follow the theme
        View root = findViewById(R.id.hsRoot);
        if (root != null)
            root.setBackgroundResource(night ? R.drawable.bg_gradient_night
                    : R.drawable.bg_gradient_day);
        headerTitle = findViewById(R.id.hsTitle);
        clearBtn = findViewById(R.id.hsClearBtn);
        totalValue = findViewById(R.id.hsTotalValue);
        totalLabel = findViewById(R.id.hsTotalLabel);
        streakValue = findViewById(R.id.hsStreakValue);
        streakLabel = findViewById(R.id.hsStreakLabel);
        topValue = findViewById(R.id.hsTopValue);
        topSub = findViewById(R.id.hsTopSub);
        topLabel = findViewById(R.id.hsTopLabel);
        recentLabel = findViewById(R.id.hsRecentLabel);
        listView = findViewById(R.id.hsList);
        emptyText = findViewById(R.id.hsEmpty);
        if (headerTitle != null) {
            headerTitle.setText(Lang.t(this, "hs_title"));
            headerTitle.setTextColor(night ? 0xFFC9A227 : 0xFF7A2E1F);
        }
        if (clearBtn != null) {
            clearBtn.setText(Lang.t(this, "btn_clear"));
            clearBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { confirmClear(); }
            });
        }
        if (totalLabel != null) totalLabel.setText(Lang.t(this, "hs_total_label"));
        if (streakLabel != null) streakLabel.setText(Lang.t(this, "hs_streak_label"));
        if (topLabel != null) topLabel.setText(Lang.t(this, "hs_top_label"));
        if (recentLabel != null) recentLabel.setText(Lang.t(this, "hs_recent"));
        adapter = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int p) { return items.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(final int position,
                    View convertView, android.view.ViewGroup parent) {
                return itemRow(position);
            }
        };
        if (listView != null) {
            listView.setAdapter(adapter);
            listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                    playRecent(items.get(pos));
                }
            });
            // v13.77 (slice 1069): the recent row's long-press menu —
            // tap still plays; a long-press opens share / favourite /
            // queue / download / remove-from-history (showHistRowMenu).
            listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                    if (pos < 0 || pos >= items.size()) return false;
                    Fx.tap(HistoryActivity.this);
                    showHistRowMenu(items.get(pos));
                    return true;
                }
            });
            // v15.67 (slice 2063): the jump button paints on the same scroll — off-screen is its only show rule, so a laid-out window is required (count 0 means no layout yet).
            listView.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
                @Override public void onScrollStateChanged(android.widget.AbsListView v, int scrollState) {}
                @Override public void onScroll(android.widget.AbsListView v, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                    // v15.75 (slice 2107): the top button paints on the same scroll (the 2003 shape — its only show rule is the first visible row, so no laid-out-window guard; the threshold keeps it hidden until row 8 anyway).
                    paintHsTopBtn(firstVisibleItem);
                    if (visibleItemCount > 0) paintHsJumpBtn(firstVisibleItem, firstVisibleItem + visibleItemCount - 1);
                }
            });
            // v15.97 (slice 2242): the swipe owns this list's touch stream — one listener, so the swipe and the tap / long-press / scroll never fight over it. A swipe DOWN records its candidate and returns false, so the tap, the long-press menu and the scroll still track; a swipe that arms consumes the gesture from there, so the list never scrolls under the travelling card.
            listView.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, android.view.MotionEvent ev) {
                    return hsSwipeOnTouch42(ev);
                }
            });
        }
        wireHsSearch(); // v15.65 (slice 2050): search on this screen, before the first refresh
        wireHsJumpBtn(); // v15.67 (slice 2063): jump-to-playing button on this list
        wireHsTopBtn(); // v15.75 (slice 2107): back to top on this screen, before the first refresh
        wireHsSort(); // v15.68 (slice 2068): sort on this screen, before the first refresh
        wireHsShuffle(); // v15.73 (slice 2099): shuffle on this screen, before the first refresh
        wireHsPlayAll(); // v15.74 (slice 2102): play all on this screen, before the first refresh
        refresh();
        wireHistoryStrip(); // v13.54: real mini-player below the list
    }

    @Override protected void onResume() {
        super.onResume();
        // Stats keep growing while the Room is open; re-read the
        // store on return so the cards are never stale.
        refresh();
        startHistoryStripTick(); // v13.54: mini-player live state
    }

    @Override protected void onPause() {
        super.onPause();
        stopHistoryStripTick(); // v13.54: never tick a detached strip
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        // v13.54: detach the liquid-glass surface (Talks v13.08) —
        // the drawable pins the dead view + its destroyed
        // activity otherwise.
        GlassBlur.detach(findViewById(R.id.nowStrip));
        // v15.40 (slice 1908): cancel in-flight stats melts so a
        // destroy mid-melt never touches a detached card (the
        // update-gate dismissBlock hygiene, 1906).
        Fx.cancelMeltText(totalValue);
        Fx.cancelMeltText(streakValue);
        Fx.cancelMeltText(topValue);
        Fx.cancelMeltText(topSub);
    }

    // v15.68 (slice 2068): sort on this screen — the Downloaded
    // sort-button shape (v1.47 label + v2.90 premium label dip +
    // v4.22 keyboard hide): the button names the CURRENT order
    // (hsSortLabel), a tap morphs the button, hides the search
    // keyboard, advances the mode (recent -> oldest -> sayadaw),
    // persists it, and dips the header row while the label swaps
    // (Fx.themeDip on hsHeaderRow, the dlBtnRow shape — the dip
    // runs on the row, never on the tapped button, so its alpha
    // channel never cancels the morph's scale channel; themeDip
    // null-guards, so a missing row still swaps the label). The
    // sort re-runs applyHsSearch() at once (it only reads
    // hsSortMode, like the Settings syncLiveService pattern), so
    // the list re-orders in place with no re-read. The mode
    // survives rotation through Prefs, like the Downloaded sort
    // (v1.47).
    private void wireHsSort() {
        hsSortBtn = findViewById(R.id.btnHsSort);
        hsSortMode = Prefs.hsSortMode(this);
        if (hsSortBtn != null) hsSortBtn.setText(hsSortLabel());
        if (hsSortBtn != null) hsSortBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.morph(v);
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                hsSortMode = (hsSortMode + 1) % 3;
                Prefs.setHsSortMode(HistoryActivity.this, hsSortMode);
                Fx.themeDip(findViewById(R.id.hsHeaderRow), new Runnable() { @Override public void run() { if (hsSortBtn != null) hsSortBtn.setText(hsSortLabel()); } });
                applyHsSearch();
            }
        });
    }

    // v15.73 (slice 2099): wire the Shuffle button — one tap shuffles the shown recent run into the queue and opens the Room on the first shuffled talk (shufflePlayHs). The label is the pre-existing Lang "shuffle" key, so no new key is added. Shuffle play landed in slice 2099.
    private void wireHsShuffle() {
        btnHsShuffle = findViewById(R.id.btnHsShuffle);
        if (btnHsShuffle != null) btnHsShuffle.setText(Lang.t(this, "shuffle"));
        if (btnHsShuffle != null) btnHsShuffle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shufflePlayHs(v); }
        });
        if (btnHsShuffle != null) Fx.press(btnHsShuffle);
    }

    // v15.74 (slice 2102): wire the Play All button — one tap queues the shown recent run in order and opens the Room on the first talk (playAllHs). The label is the pre-existing Lang "pl_play_all" key, so no new key is added. Play all landed in slice 2102.
    private void wireHsPlayAll() {
        btnHsPlayAll = findViewById(R.id.btnHsPlayAll);
        if (btnHsPlayAll != null) btnHsPlayAll.setText(Lang.t(this, "pl_play_all"));
        if (btnHsPlayAll != null) btnHsPlayAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { playAllHs(v); }
        });
        if (btnHsPlayAll != null) Fx.press(btnHsPlayAll);
    }

    // v15.68 (slice 2068): the sort label — the Downloaded
    // dlSortLabel shape (1 method, 3 returns): most-recent first,
    // oldest first, sayadaw A-Z.
    private String hsSortLabel() {
        if (hsSortMode == 1) return Lang.t(HistoryActivity.this, "hs_sort_oldest");
        if (hsSortMode == 2) return Lang.t(HistoryActivity.this, "hs_sort_sayadaw");
        return Lang.t(HistoryActivity.this, "hs_sort_recent");
    }

    private int titleColor() { return night ? 0xFFFFFFFF : 0xFF3B2314; }
    private int subColor() { return night ? 0xFFAAAAAA : 0xFF7A6A58; }
    // v15.65 (slice 2050): search on this screen — the Downloaded
    // search shape (v1.51 field + v4.20 Search-key dismiss + v15.33
    // clear X): the hint is the Talks chooser's own
    // talks_search_hint (v9.88), the filter is the ONE rule
    // (Fx.titleMatches through HistSearch, title or sayadaw), and a
    // keystroke re-filters the in-memory pick without re-reading
    // the stats store. Rotation restore rides the watcher (the
    // v1.51 rule): the system's restored text fires afterTextChanged
    // after onStart, so the filter re-runs with the restored query
    // before onResume's refresh. The clear X clears through
    // setText("") so the watcher re-runs the filter exactly like a
    // keystroke (the Main v15.33 shape, slice 1836); it is GONE
    // with an empty query, so a tap can only land on a real query.
    private void wireHsSearch() {
        hsSearchField = findViewById(R.id.hsSearch);
        hsClearBtn = findViewById(R.id.btnClearHsSearch);
        if (hsSearchField != null)
            hsSearchField.setHint(Lang.t(this, "talks_search_hint"));
        if (hsSearchField != null)
            hsSearchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        if (hsSearchField != null) hsSearchField.setSingleLine(true);
        if (hsSearchField != null) hsSearchField.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
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
        if (hsClearBtn != null) hsClearBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.morph(v);
                if (hsSearchField != null) hsSearchField.setText("");
            }
        });
        if (hsSearchField != null) hsSearchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                hsQuery = s.toString().trim();
                hsSearchKeystroke = true;
                applyHsSearch();
                hsSearchKeystroke = false;
            }
        });
    }

    // v15.67 (slice 2063): wire the jump-to-playing button — one
    // tap glides to the sounding talk's first shown listen (the
    // chooser's v4.15 title long-press glide, in a visible button
    // — this screen has no long-press jump at all). The tap
    // re-resolves everything fresh — live guards, the shown-list
    // index — so a track change while the button sat visible can
    // never glide to a stale row; a filtered-out target clears
    // the search first (the v12.54 rule, in this screen's
    // setText("") clear shape — the watcher re-runs applyHsSearch
    // at once, the v1.51 rule), a shown target keeps the view. Any
    // guard failure toasts no_talk_playing (the v4.15 word), never
    // scrolls nowhere. A missing button simply never wires.
    private void wireHsJumpBtn() {
        hsPlayingBtn = findViewById(R.id.hsPlayingBtn);
        if (hsPlayingBtn != null) hsPlayingBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v10.43: bound-trackless stop — liveSession stays true but the mirror is false, so toast instead of gliding to a stopped talk; the v11.15 gate: an ended talk is not playing either (its mark lifts, so there is no row to jump to).
                if (listView == null || items.isEmpty() || !AudioService.liveSession || !AudioService.liveHasTrack || AudioService.liveEnded) {
                    android.widget.Toast.makeText(HistoryActivity.this, Lang.t(HistoryActivity.this, "no_talk_playing"), android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    refreshHistoryPlayingMarks();
                    int target = HistJumpPlaying.findShownIndex(items, histPlayingUrl, histPlayingLibUrl, histPlayingLibPath, AudioService.liveEnded);
                    // v12.54: a shown target glides with the view untouched; only a target filtered OUT of the shown list clears the search first, so the shown index means something again.
                    if (target < 0 && hsQuery != null && hsQuery.length() > 0 && hsSearchField != null) {
                        hsSearchField.setText("");
                        refreshHistoryPlayingMarks();
                        target = HistJumpPlaying.findShownIndex(items, histPlayingUrl, histPlayingLibUrl, histPlayingLibPath, AudioService.liveEnded);
                    }
                    if (target < 0) {
                        android.widget.Toast.makeText(HistoryActivity.this, Lang.t(HistoryActivity.this, "no_talk_playing"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Fx.tap(HistoryActivity.this);
                    Fx.morph(v);
                    listView.smoothScrollToPosition(target);
                } catch (Exception ignored) {}
            }
        });
    }

    // v15.67 (slice 2063): refresh the jump button's cached
    // target — the shown-list index of the sounding talk, from the
    // tick-fresh sounding identities (refreshHistoryPlayingMarks
    // runs first on every path that calls this: refresh() before
    // applyHsSearch, the strip-tick change branch, the tap's own
    // re-resolution). Runs on search/refresh and on sounding
    // change only, never on scroll; -1 while nothing live sounds
    // among the shown rows.
    private void refreshHsJumpTarget() {
        hsJumpTargetIdx = HistJumpPlaying.findShownIndex(items, histPlayingUrl, histPlayingLibUrl, histPlayingLibPath, AudioService.liveEnded);
    }

    // v15.67 (slice 2063): the jump-to-playing state — shown only
    // while a live talk has a cached target AND that row is
    // off-screen (above or below the visible window, the
    // JumpPlaying.wantShown rule, the 1994 shape). The change gate
    // keeps steady scrolls and the search/refresh repaints from
    // re-touching the button; the show/hide melts (Fx.fadeIn/
    // fadeOut, 180ms) instead of snapping, and a cancelled fade
    // never strands it half-transparent (the v2.62 gen guard, the
    // 1940 shape). A stop clears the identities on the next tick,
    // so the button hides within a tick of the session ending.
    private void paintHsJumpBtn(int firstVisible, int lastVisible) {
        if (hsPlayingBtn == null) return;
        boolean want = hsJumpTargetIdx >= 0
                && JumpPlaying.wantShown(hsJumpTargetIdx, firstVisible, lastVisible);
        if (want == hsPlayingShown) return;
        hsPlayingShown = want;
        if (want) {
            hsPlayingBtn.setVisibility(android.view.View.VISIBLE);
            Fx.fadeIn(hsPlayingBtn, 180);
        } else {
            Fx.fadeOut(hsPlayingBtn, 180);
        }
    }

    // v15.67 (slice 2063): repaint from the list's own position
    // after a scroll-less change — a search/refresh, a rotation
    // restore, or a sounding change can move the playing row
    // off-screen with no scroll event, so read the position
    // after layout and paint from it. Posted, because the first /
    // last visible rows are only valid once the list has laid out
    // (the 1940 shape).
    private void paintHsJumpBtnFromList() {
        if (listView == null) return;
        listView.post(new Runnable() {
            @Override public void run() {
                if (listView == null) return;
                paintHsJumpBtn(listView.getFirstVisiblePosition(), listView.getLastVisiblePosition());
            }
        });
    }

    // v15.65 (slice 2050): the search clear X melts with the query
    // — the Downloaded refreshDlClear shape verbatim (v15.33, slice
    // 1837): fades in 250ms (the app's settled Fx.fadeIn) when a
    // keystroke-driven pass leaves a non-empty query, and fades out
    // 200ms (the v14.33 sleep-line rhythm) when the query empties;
    // silent passes (rotation / restore / onResume) land instantly,
    // the v1.53 rule. Shown for a non-empty query only — the X is a
    // clear control, not a no-match marker, so it never waits on
    // the entries or the results. A mid-fadeOut re-show flows back
    // on from its current alpha (Fx.fadeIn is restart-safe, v3.52,
    // and its gen bump stands the fade-out's end action down, so it
    // can never strand the view GONE), and a silent pass on a
    // mid-fadeOut view parks it at full alpha instead.
    // v15.75 (slice 2107): wire the back-to-top button — one tap glides the open list to the top (the Talks 1940 / Downloaded 2003 shape: the tap needs no gate — the button only shows past row 8, so a tap always has somewhere to go; this screen HAS a search field, so the keyboard hides with the glide, the 2003 shape in this file's own inline form — the wireHsSort tap's two lines, not a new helper). A missing button simply never wires; a missing list never scrolls.
    private void wireHsTopBtn() {
        hsTopBtn = findViewById(R.id.hsTopBtn);
        if (hsTopBtn != null) hsTopBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(HistoryActivity.this);
                Fx.morph(v);
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                if (listView != null) listView.smoothScrollToPosition(0);
            }
        });
    }

    // v15.75 (slice 2107): the back-to-top state — shown once the first visible row passes ScrollTop's threshold, hidden at the top (the 1940 Talks / 2003 Downloaded shape verbatim: ScrollTop.visible, THRESHOLD 8 — about one screen of rows). No sounding gate: the rule is the shown list's own scroll. The change gate keeps steady scrolls and the search/refresh repaint from re-touching the button; the show/hide melts (Fx.fadeIn/fadeOut, 180ms) instead of snapping, and a cancelled fade never strands it half-transparent (the v2.62 gen guard, the 1940 shape).
    private void paintHsTopBtn(int firstVisible) {
        if (hsTopBtn == null) return;
        boolean want = ScrollTop.visible(firstVisible);
        if (want == hsTopShown) return;
        hsTopShown = want;
        if (want) {
            hsTopBtn.setVisibility(android.view.View.VISIBLE);
            Fx.fadeIn(hsTopBtn, 180);
        } else {
            Fx.fadeOut(hsTopBtn, 180);
        }
    }

    // v15.75 (slice 2107): repaint from the list's own position after a scroll-less change — a search / refresh rebuild can land the list deep without a scroll event, so read the position after layout and paint from it. Posted, because the first visible row is only valid once the list has laid out (the 1940 shape). The rotation restore needs no separate hook: onCreate restores through refresh(), whose applyHsSearch tail already posts this repaint.
    private void paintHsTopBtnFromList() {
        if (listView == null) return;
        listView.post(new Runnable() {
            @Override public void run() {
                if (listView == null) return;
                paintHsTopBtn(listView.getFirstVisiblePosition());
            }
        });
    }

    private void refreshHsClear(boolean animate) {
        if (hsClearBtn == null) return;
        if (hsQuery != null && hsQuery.length() > 0) {
            if (hsClearBtn.getVisibility() != android.view.View.VISIBLE) {
                hsClearBtn.setVisibility(android.view.View.VISIBLE);
                if (animate) Fx.fadeIn(hsClearBtn, 250);
                else hsClearBtn.setAlpha(1f);
            } else if (animate && hsClearBtn.getAlpha() < 0.999f) {
                Fx.fadeIn(hsClearBtn, 250);
            } else if (!animate) {
                hsClearBtn.animate().cancel();
                hsClearBtn.setAlpha(1f);
            }
        } else {
            if (hsClearBtn.getVisibility() == android.view.View.VISIBLE) {
                if (animate) Fx.fadeOut(hsClearBtn, 200);
                else {
                    hsClearBtn.animate().cancel();
                    hsClearBtn.setVisibility(android.view.View.GONE);
                    hsClearBtn.setAlpha(1f);
                }
            }
        }
    }

    // v15.73 (slice 2099): the Shuffle button shows exactly when the shown run is non-empty — the 2065 rule: a play verb never sits live over an empty list (a search-narrowed empty list has nothing to shuffle, and clearing the search restores the full run). The change gate keeps steady keystrokes and refreshes from re-touching the button; the show/hide melts (Fx.fadeIn/fadeOut, 180ms) instead of snapping, and a cancelled fade never strands it half-transparent (the v2.62 gen guard, the 1940 shape).
    private void refreshHsShuffleBtn() {
        if (btnHsShuffle == null) return;
        boolean show = items != null && !items.isEmpty();
        if (show == hsShuffleShown) return;
        hsShuffleShown = show;
        if (show) {
            btnHsShuffle.setVisibility(android.view.View.VISIBLE);
            Fx.fadeIn(btnHsShuffle, 180);
        } else {
            Fx.fadeOut(btnHsShuffle, 180);
        }
    }

    // v15.74 (slice 2102): the Play All button shows exactly when the shown run is non-empty — the 2065 rule: a play verb never sits live over an empty list (a search-narrowed empty list has nothing to play, and clearing the search restores the full run). The change gate keeps steady keystrokes and refreshes from re-touching the button; the show/hide melts (Fx.fadeIn/fadeOut, 180ms) instead of snapping, and a cancelled fade never strands it half-transparent (the v2.62 gen guard, the 1940 shape).
    private void refreshHsPlayAllBtn() {
        if (btnHsPlayAll == null) return;
        boolean show = items != null && !items.isEmpty();
        if (show == hsPlayAllShown) return;
        hsPlayAllShown = show;
        if (show) {
            btnHsPlayAll.setVisibility(android.view.View.VISIBLE);
            Fx.fadeIn(btnHsPlayAll, 180);
        } else {
            Fx.fadeOut(btnHsPlayAll, 180);
        }
    }

    private int goldColor() { return 0xFFC9A227; }

    // Numbers in Myanmar digits when the UI language is Burmese,
    // Latin digits otherwise — like the app's timers, but native
    // on the Burmese screen where these cards live.
    private String num(long n) {
        String s = Long.toString(n);
        return Lang.index(this) == 0 ? Prefs.statsDigitsPure(s) : s;
    }

    private String fmtDur(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (h > 0) return num(h) + " " + Lang.t(this, "hs_hour")
                + " " + num(m) + " " + Lang.t(this, "hs_min");
        if (m > 0) return num(m) + " " + Lang.t(this, "hs_min");
        return num(s) + " " + Lang.t(this, "hs_sec");
    }

    private void refresh() {
        long total = Prefs.statsTotal(this);
        int streak = Prefs.statsStreak(this);
        String[] top = Prefs.statsTopSayadaw(this);
        // v15.65 (slice 2050): the search keeps its own copy of the
        // unfiltered pick — a keystroke re-filters in memory (the
        // Downloaded applyDlFilter shape), never re-reading the
        // stats store per keystroke.
        hsAllItems = Prefs.statsRecent(this);
        // v15.96 (slice 2234): the total captured beside the pick — one parse per refresh, the search never re-reads the store per keystroke, so the total stays the unfiltered pick's size.
        hsTotal34 = hsAllItems == null ? 0 : hsAllItems.size();
        refreshHistFavSet(); // v13.77: rows bind their star mark from this snapshot
        refreshHistPlayedSet(); // v15.85 (slice 2172): the tick snapshot fresh on first paint, beside the star snapshot
        refreshHistDurCache(); // v15.86 (slice 2178): the duration snapshot fresh on first paint, beside the tick snapshot — the clocks read the snapshot, never a per-row parse
        refreshHistDlSet(); // v15.89 (slice 2193): the badge snapshot fresh on first paint, beside the duration snapshot — the rows read the snapshot, never a per-row store test
        refreshHistPosCache(); // v15.91 (slice 2204): the pos snapshot fresh on first paint, beside the badge snapshot
        if (totalValue != null) {
            meltStat40(totalValue, fmtDur(total));
            totalValue.setTextColor(goldColor());
        }
        if (streakValue != null) {
            meltStat40(streakValue, num(streak) + " " + Lang.t(this, "hs_days"));
            streakValue.setTextColor(goldColor());
        }
        if (topValue != null) {
            meltStat40(topValue, top == null ? "—" : top[0]);
            topValue.setTextColor(goldColor());
        }
        if (topSub != null) {
            meltStat40(topSub, top == null ? "" : fmtDur(Long.parseLong(top[1])));
            topSub.setTextColor(subColor());
        }
        // v15.96 (slice 2234): the header carries its total — the drawer header's own idiom, now on the full-list screen: the count sits beside the title in parens, ASCII digits; an empty list keeps the bare title, so the count never shows (0).
        if (headerTitle != null)
            headerTitle.setText(hsTotal34 >= 1 ? Lang.t(this, "hs_title") + " (" + hsTotal34 + ")" : Lang.t(this, "hs_title"));
        hsStatsKnown40 = true;
        refreshHistoryPlayingMarks(); // v13.55: mark right on first paint, not one tick late
        applyHsSearch();
    }

    // v15.65 (slice 2050): the search pass — the filtered list,
    // the adapter, the clear X and the empty line, from the
    // unfiltered pick. The watcher calls this per keystroke;
    // refresh() calls it after a fresh pick; onResume's refresh()
    // re-applies the live query after a Room return. A
    // filtered-empty list says no_search_match (v8.94) — there ARE
    // recent talks, just none matching — while a blank query with
    // an empty list still says hs_empty. The playing-row mark
    // needs nothing here: itemRow re-reads the sounding
    // identities per bind, so a filtered-out sounding talk simply
    // has no row to mark and a re-shown one marks at once.
    // "Play from here" follows the filtered list (the 2047 rule):
    // the shown run is the run — a search-narrowed list plays the
    // narrowed run from the anchor, clearing the search restores
    // the full run. The stat cards stay unfiltered — the search
    // filters only the recent list beneath them.
    private void applyHsSearch() {
        // v15.68 (slice 2068): the sort rides the search pass — the
        // shown list is the filtered list in the chosen order, so a
        // keystroke re-sorts in memory and the sort tap re-runs this
        // pass instead of re-reading the pick. The playing-row mark
        // needs nothing here, like the search (2050): itemRow
        // re-reads the sounding identities per bind. The stat cards
        // stay unfiltered — the sort orders only the recent list
        // beneath them.
        items = HistSort.sort(HistSearch.filter(hsAllItems, hsQuery), hsSortMode);
        if (adapter != null) adapter.notifyDataSetChanged();
        refreshHsClear(hsSearchKeystroke);
        if (emptyText == null) return;
        boolean empty = items.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) emptyText.setText(Lang.t(this,
                hsQuery.length() > 0 ? "no_search_match" : "hs_empty"));
        // v15.67 (slice 2063): a search / refresh re-shows the list — the jump button's cached target moves with it (new list, new filter), so refresh the target and repaint from the new position.
        refreshHsJumpTarget();
        paintHsJumpBtnFromList();
        // v15.75 (slice 2107): a search / refresh can land the list deep without a scroll event — repaint the top button from the new position too (the 2003 shape).
        paintHsTopBtnFromList();
        // v15.73 (slice 2099): the Shuffle button follows the shown run — a search / refresh that empties the list hides it, and one that fills it shows it (the 2065 rule).
        refreshHsShuffleBtn();
        // v15.74 (slice 2102): the Play All button follows the shown run — the same 2065 rule as Shuffle, beside it.
        refreshHsPlayAllBtn();
    }

    private View itemRow(final int position) {
        final Prefs.HistEntry e = items.get(position);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * d);
        // v15.84 (slice 2164): the sayadaw's photo joins the row — Bookmarks, Favourites and New talks rows carry the 44dp circle photo (their itemRow photo block, verbatim here), but History rows stayed text-only, so the screen read as a ledger beside its photo-led sisters. The photo comes from the same catalogFor cache this Activity already keeps (cat[2] is the photo_url; a null cat — a blank or uncatalogued sayadaw — loads "" and ImageLoader paints its initials fallback, the same rule the three sister screens use). The column's left padding drops to 0, the photo's own 8dp right margin carrying the gap, so the photo sits at the same inset as the sisters (the root's 16dp padding is shared). No Lang key, no Prefs write, no layout change — the row is built in code.
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
        TextView tv = new TextView(this);
        // v13.55: the sounding row says so — "▶ " ahead of its title in gold (Talks v4.89/v11.07),
        // for a Room session (URL match), a library session on a URL-form row (dlMap exactly-one
        // claimant) or a path-form row (direct path match); an ended talk is not playing (v11.15 gate),
        // so its mark lifts at once. Rows are built fresh per getView, but both text and colour rebind
        // here so neither can leak onto the next talk.
        boolean hsRowPlaying = !AudioService.liveEnded
                && ((histPlayingUrl.length() > 0 && SiteHttp.sameTalk(e.url, histPlayingUrl))
                        || (histPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(e.url, histPlayingLibUrl))
                        || (histPlayingLibPath.length() > 0 && e.url.equals(histPlayingLibPath)));
        // v13.77: a favourited recent row says so — "★ " ahead of its
        // title (the strip star and the Talks star marks read the same
        // set), after the "▶ " playing mark when both apply; gold
        // either way, so the mark reads at a glance.
        // v15.85 (slice 2172): a finished recent talk says so — "✓ " after the "★ " star when both apply (the library v4.24 order, play first, star second, tick third), gold either way; the tick reads the histPlayedSet snapshot (refreshed on refresh() and on the 1s tick's change detector), resolved through histStreamUrlFor — a downloaded (path-form) row claims its stream URL through the dlMap exactly-one-claimant resolve, so it ticks exactly when its stream twin does. A row stopped early sits in History but NOT in playedTalks (the 2139 mark verb offers Mark there), so the tick distinguishes finished from merely started; the 2163 day stamp says WHEN, this says WHETHER finished. The playing row never ticks — its live position is on the strip, not a finished mark. No Lang key, no Prefs write, no layout change — the row is built in code.
        boolean hsRowPlayed72 = !hsRowPlaying && histRowPlayed(e);
        // v15.89 (slice 2193): the downloaded row says so — the badge after the star when both apply (the chooser/library order: play first, star second, badge third, tick fourth), so a recent talk already on the phone reads as offline at a glance. The row reads the snapshot the refresh filled; a row whose stream address sits in the set is downloaded. The badge alone does not gold the row — only a sounding or favourited row turns gold. The sounding row badges too — downloaded is a property of the talk, not a state.
        boolean hsRowDl93 = histRowDl(e);
        // v15.91 (slice 2207): the resume spot joins the title — "↺ 12:34 · title" when the talk was meaningfully heard (spot over 5s, the same gate the progress line under the row uses), the Favourites row's resume segment now on History rows: the sub-line says how LONG it was heard (cumulative) and WHEN, but never WHERE listening stopped — the resume prefix is the cue, so a recent talk reads as "continue from 12:34" at a glance. The spot reads the existing per-talk snapshot through the row's own resolver (the line's read, one more map lookup, no new snapshot, no new parse). The playing row shows it too (its spot is where playback will resume if it ends now — the Favourites shape, where the sounding row's resume advances with the saved spot). No Lang key, no Prefs write, no layout change — the row is built in code.
        int hsRowPos07 = histRowPosMs(e);
        String hsResume07 = hsRowPos07 > 5000 ? "↺ " + FavTalkRail.fmtTime(hsRowPos07) + " · " : "";
        String hsPrefix77 = (hsRowPlaying ? "▶ " : "")
                + (histRowFav(e) ? "★ " : "")
                + (hsRowDl93 ? "⬇ " : "")
                + (hsRowPlayed72 ? "✓ " : "")
                + hsResume07;
        tv.setText(hsPrefix77 + (e.title.length() > 0 ? e.title : e.url));
        tv.setTextColor((hsRowPlaying || histRowFav(e)) ? goldColor() : titleColor());
        tv.setTextSize(16);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView sv = new TextView(this);
        // v15.84 (slice 2163): the listened day joins the sub-line — the stored lastMs (the REC line's own stamp, refreshed on every re-listen, v12.82) never rendered anywhere on this screen, so a row said how LONG a talk was heard but never WHEN. HistDay.label reads "" for a pre-timestamp stamp (lastMs <= 0), so legacy rows keep exactly today's sub-line; a real stamp renders "d.M.yyyy" in the device's local day, Myanmar digits in Burmese UI (the num() rule — statsDigitsPure), so the row reads "sayadaw • 25 min • 24.11.2026". No Lang key, no Prefs write, no layout change — the sub-line is built in code.
        String hsDay63 = HistDay.label(e.lastMs);
        String sub = e.sayadaw.length() > 0
                ? e.sayadaw + " • " + fmtDur(e.seconds) : fmtDur(e.seconds);
        if (hsDay63.length() > 0) sub += " • " + (Lang.index(this) == 0 ? Prefs.statsDigitsPure(hsDay63) : hsDay63);
        sv.setText(sub);
        sv.setTextColor(subColor());
        sv.setTextSize(12);
        col.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(col, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        // v15.86 (slice 2178): the talk's total length clocks on the row's right edge — "42:15", "1:05:22" past the hour — so a listener can judge "how long is this talk" without opening it; the sub-line already says how LONG it was heard (2163 fmtDur) and WHEN (2163 day stamp), but never the total. The clock reads the histDurCache snapshot (one parse per refresh, never talkDurGet per row per bind, the v13.97 rule), resolved through the pre-existing histStreamUrlFor — a URL-form row reads its own stream URL (identityUrl-normalised), a downloaded (path-form) row falls back to its own URL for a length banked under its path form (talkDurPutBoth banks both forms, the v13.96 rule), so it clocks exactly when its stream twin does. The clock is a muted 12sp TextView (subColor — the chevron's own muted colour) between the text column and the "›" chevron, with an 8dp right margin. The playing row clocks too (its length is a property of the talk, the 1094 rule — its live position is on the strip, not the clock). Hidden when no duration was ever banked — GONE, never guessed, never "0:00" — so a row with no banked length keeps exactly today's layout. No Lang key, no Prefs write, no layout change — the row is built in code.
        int hsRowDur78 = histRowDurMs(e);
        TextView hsDur78 = new TextView(this);
        hsDur78.setText(hsRowDur78 > 0 ? Fx.fmtTime(hsRowDur78) : "");
        hsDur78.setTextColor(subColor());
        hsDur78.setTextSize(12);
        hsDur78.setVisibility(hsRowDur78 > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
        LinearLayout.LayoutParams hsDurParams78 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hsDurParams78.setMargins(0, 0, (int) (8 * d), 0);
        hsDur78.setLayoutParams(hsDurParams78);
        row.addView(hsDur78);
        TextView go = new TextView(this);
        go.setText("›");
        go.setTextColor(subColor());
        go.setTextSize(22);
        row.addView(go, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        // v15.91 (slice 2204): a partly-heard recent talk's listened-progress hairline — the Talks-row line (v13.97, slice 1092) and the rail cards' line (v15.60, slices 2020, 2027) now under this row: 3dp tall, 4dp below the row, faint-gold track (#33C9A227) with a solid-gold fill (#C9A227, @color/gold) split by weight, weightSum 1. The fraction is the talk's RESUME spot from the pos snapshot — never the row's heard total (the sub-line's heard seconds are cumulative across re-listens, the resume spot is how far into the talk they got, the 2027 rail rule) — over the 2178 clock's total (the shared fraction helper — the Talks-row rule verbatim: 0 when never meaningfully heard, spot at or below 5s, or no duration ever stored; clamped to 1, never below a 3% sliver, so a barely-started talk still reads as a visible gold dash). Never the playing row (its live position is on the strip, the 1092 gate). GONE unless a fraction was set — Fx.crossfadeHairline owns the show/hide (on these freshly built rows it seeds instantly, no melt ever plays here, the 2020 rail shape). No Lang key, no Prefs write, no layout change — the row is built in code.
        int hsRowPos04 = histRowPosMs(e);
        float hsHairFrac04 = hsRowPlaying ? 0f : RailHair.fraction(hsRowPos04, hsRowDur78);
        LinearLayout hsHairTrack04 = new LinearLayout(this);
        hsHairTrack04.setOrientation(LinearLayout.HORIZONTAL);
        hsHairTrack04.setBackgroundColor(android.graphics.Color.parseColor("#33C9A227"));
        hsHairTrack04.setWeightSum(1f);
        LinearLayout.LayoutParams hsHairParams04 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (3 * d));
        hsHairParams04.topMargin = (int) (4 * d);
        hsHairTrack04.setLayoutParams(hsHairParams04);
        android.view.View hsHairFill04 = new android.view.View(this);
        hsHairFill04.setBackgroundColor(android.graphics.Color.parseColor("#C9A227"));
        hsHairFill04.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, hsHairFrac04));
        android.view.View hsHairSpace04 = new android.view.View(this);
        hsHairSpace04.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f - hsHairFrac04));
        hsHairTrack04.addView(hsHairFill04);
        hsHairTrack04.addView(hsHairSpace04);
        Fx.crossfadeHairline(hsHairTrack04, hsHairFrac04 > 0f);
        // v15.97 (slice 2242): the swipe frame — the row's card gains a gold strip behind it, built in code like the rest of this row: a frame holds the strip first (behind) and the card second (in front), so the strip is invisible at rest and revealed as the card travels. The strip starts hidden and the card starts plain — at rest the row keeps exactly today's look — and the gesture lifts both together when a drag arms. The strip's labels are painted here, where the row's listened state is known: the queue word on the left (revealed by a right drag, always the add word — an already-queued row never starts a right drag) and the mark word on the right (revealed by a left drag, flipping with the row's own listened state, the same snapshot the tick reads). The card carries the row and its hairline together, so the progress line travels with the talk it belongs to.
        android.widget.FrameLayout swFrame = new android.widget.FrameLayout(this);
        LinearLayout swUnder = new LinearLayout(this);
        swUnder.setId(R.id.hsSwipeUnder42);
        swUnder.setOrientation(LinearLayout.HORIZONTAL);
        swUnder.setGravity(Gravity.CENTER_VERTICAL);
        swUnder.setBackgroundResource(R.drawable.dl_swipe_bg);
        swUnder.setVisibility(android.view.View.GONE);
        android.widget.TextView swLeft = new android.widget.TextView(this);
        swLeft.setId(R.id.hsSwipeLeft42);
        swLeft.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        swLeft.setPadding((int) (20 * d), 0, 0, 0);
        swLeft.setTextColor(android.graphics.Color.parseColor("#3B2314"));
        swLeft.setTextSize(15);
        swLeft.setTypeface(null, android.graphics.Typeface.BOLD);
        swLeft.setText(Lang.t(this, "menu_queue_add"));
        android.widget.TextView swRight = new android.widget.TextView(this);
        swRight.setId(R.id.hsSwipeRight42);
        swRight.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        swRight.setGravity(Gravity.END);
        swRight.setPadding(0, 0, (int) (20 * d), 0);
        swRight.setTextColor(android.graphics.Color.parseColor("#3B2314"));
        swRight.setTextSize(15);
        swRight.setTypeface(null, android.graphics.Typeface.BOLD);
        swRight.setText(Lang.t(this, DlSwipe.labelKey(DlSwipe.LEFT, hsRowPlayed72)));
        swUnder.addView(swLeft);
        swUnder.addView(swRight);
        LinearLayout swCard = new LinearLayout(this);
        swCard.setId(R.id.hsSwipeCard42);
        swCard.setOrientation(LinearLayout.VERTICAL);
        swCard.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        swCard.addView(hsHairTrack04);
        swFrame.addView(swUnder, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        swFrame.addView(swCard, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout hsWrap04 = new LinearLayout(this);
        hsWrap04.setOrientation(LinearLayout.VERTICAL);
        hsWrap04.addView(swFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return hsWrap04;
    }

    // v15.97 (slice 2242): the History row swipe — the gesture twin of the library swipe on this screen: a left drag marks (or un-marks) that one talk listened, a right drag adds it to its sayadaw's queue, with the gold strip + action label revealed under the card as it travels. The DOWN records the candidate row and both offers and returns false, so the tap, the long-press menu and the scroll still track; the MOVE arms only on a horizontal-dominant drag in an offered direction, lifts the row into its card first, then takes the gesture; the UP commits past the threshold and glides home before firing. Never arms on a row with no stream address to mark or queue, never on the sounding row (both verbs exclude it), never right on an already-queued row or with no sayadaw to queue into, never left on a talk with no address to mark. Rows here are built fresh per bind with no recycling, so the card is re-resolved from the list on every MOVE and at UP — a rebind landing mid-drag replaces the view, and the drag follows the new view instead of a stale one.
    private boolean hsSwipeOnTouch42(android.view.MotionEvent ev) {
        if (listView == null) return false;
        int action = ev.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            hsSwipeReset42();
            int pos = listView.pointToPosition((int) ev.getX(), (int) ev.getY());
            if (pos == android.widget.AdapterView.INVALID_POSITION) return false;
            if (pos < 0 || pos >= items.size()) return false;
            Prefs.HistEntry e = items.get(pos);
            if (e == null || e.url == null || e.url.length() == 0) return false;
            String stream = histStreamUrlFor(e);
            if (stream.length() == 0) return false;
            String sd = histSayadawFor(e);
            refreshHistoryPlayingMarks();
            boolean sounding = (histPlayingUrl.length() > 0 && SiteHttp.sameTalk(stream, histPlayingUrl))
                    || (histPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(stream, histPlayingLibUrl));
            boolean queued = histQueueHas(sd, stream);
            boolean leftOk = DlMark.canOffer(stream, sounding);
            boolean rightOk = sd.length() > 0 && DlSwipe.offerRight(sounding, queued);
            if (!leftOk && !rightOk) return false;
            if (hsSwipeSlop42 < 0) hsSwipeSlop42 = android.view.ViewConfiguration.get(HistoryActivity.this).getScaledTouchSlop();
            hsSwipeEntry42 = e;
            hsSwipeStream42 = stream;
            hsSwipeSd42 = sd;
            hsSwipeDownX42 = ev.getX();
            hsSwipeDownY42 = ev.getY();
            return false;
        }
        if (hsSwipeEntry42 == null) return false;
        if (action == android.view.MotionEvent.ACTION_MOVE) {
            float dx = ev.getX() - hsSwipeDownX42;
            float dy = ev.getY() - hsSwipeDownY42;
            if (!hsSwipeArmed42) {
                if (!DlSwipe.dominant(dx, dy, hsSwipeSlop42)) return false;
                int dir = DlSwipe.direction(dx);
                if (dir == DlSwipe.LEFT && !hsSwipeLeftOk42) { hsSwipeReset42(); return false; }
                if (dir == DlSwipe.RIGHT && !hsSwipeRightOk42) { hsSwipeReset42(); return false; }
                if (dir == DlSwipe.NONE) return false;
                android.view.View wrap = hsSwipeWrap42();
                if (wrap == null) { hsSwipeReset42(); return false; }
                android.view.View under = wrap.findViewById(R.id.hsSwipeUnder42);
                android.view.View card = wrap.findViewById(R.id.hsSwipeCard42);
                if (under == null || card == null) { hsSwipeReset42(); return false; }
                hsSwipeArmed42 = true;
                hsSwipeDir42 = dir;
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
            android.view.View wrap = hsSwipeWrap42();
            if (wrap == null) { hsSwipeReset42(); return true; }
            android.view.View under = wrap.findViewById(R.id.hsSwipeUnder42);
            android.view.View card = wrap.findViewById(R.id.hsSwipeCard42);
            if (card == null) { hsSwipeReset42(); return true; }
            if (under != null) under.setVisibility(android.view.View.VISIBLE);
            card.setBackgroundResource(night ? R.drawable.card_bg_night : R.drawable.card_bg);
            card.setTranslationX(DlSwipe.clampDir(ev.getX() - hsSwipeDownX42, hsSwipeDir42, card.getWidth()));
            return true;
        }
        if (action == android.view.MotionEvent.ACTION_UP) {
            if (!hsSwipeArmed42) { hsSwipeReset42(); return false; }
            android.view.View wrap = hsSwipeWrap42();
            android.view.View card = wrap == null ? null : wrap.findViewById(R.id.hsSwipeCard42);
            float dx = card == null ? 0f : card.getTranslationX();
            boolean fire = card != null && DlSwipe.commit(dx, card.getWidth(), hsSwipeMinCommit42());
            hsSwipeSettle42(card, hsSwipeDir42, hsSwipeEntry42, fire);
            return true;
        }
        if (action == android.view.MotionEvent.ACTION_CANCEL
                || action == android.view.MotionEvent.ACTION_POINTER_DOWN) {
            if (!hsSwipeArmed42) { hsSwipeReset42(); return false; }
            android.view.View wrap = hsSwipeWrap42();
            hsSwipeSettle42(wrap == null ? null : wrap.findViewById(R.id.hsSwipeCard42), hsSwipeDir42, null, false);
            return true;
        }
        return false;
    }

    // The 96dp commit floor in px — the core takes the floor as
    // an argument so the pure math never reads a resource.
    private float hsSwipeMinCommit42() {
        return 96f * getResources().getDisplayMetrics().density;
    }

    // v15.97 (slice 2242): glide the card home over 200ms Decelerate (the app's melt rhythm), then fire the verb — the glide is the feedback, the toast follows it. A cancelled settle never fires: the cancel flag is checked on animation end, so a fresh DOWN that cuts a settle short cannot queue a stale verb. The swipe fields stay set until the animation ends, so a rebind landing mid-glide never snaps the card (the fresh row is built at rest, and the reset clears the old one).
    private void hsSwipeSettle42(final android.view.View card, final int dir, final Prefs.HistEntry e, final boolean fire) {
        if (hsSwipeSettle42 != null) hsSwipeSettle42.cancel();
        hsSwipeSettle42 = null;
        if (card == null) { hsSwipeReset42(); return; }
        final float start = card.getTranslationX();
        if (start == 0f) { hsSwipeReset42(); return; }
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(start, 0f);
        hsSwipeSettle42 = anim;
        anim.setDuration(200);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                card.setTranslationX((Float) a.getAnimatedValue());
            }
        });
        final boolean[] cancelled42 = new boolean[1];
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationCancel(android.animation.Animator a) {
                cancelled42[0] = true;
            }
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (hsSwipeSettle42 == anim) hsSwipeSettle42 = null;
                if (!cancelled42[0] && fire && e != null && dir == DlSwipe.LEFT) applyHsSwipeMark42(e);
                else if (!cancelled42[0] && fire && e != null && dir == DlSwipe.RIGHT) applyHsSwipeQueueAdd42(e);
                hsSwipeReset42();
            }
        });
        anim.start();
    }

    private void hsSwipeReset42() {
        if (hsSwipeSettle42 != null) {
            android.animation.ValueAnimator a = hsSwipeSettle42;
            hsSwipeSettle42 = null;
            a.cancel();
        }
        // A fresh DOWN, a cancel or a settle end clears the live row back to rest: the card glides home on its own, but the strip hides and the card goes plain here, so a row can never keep gold or a card after its gesture ends. The row is found again at the DOWN point (a rebind may have replaced it); a row that scrolled away is already at rest, so there is nothing to clear.
        android.view.View wrap = hsSwipeWrap42();
        if (wrap != null) {
            android.view.View under = wrap.findViewById(R.id.hsSwipeUnder42);
            if (under != null) under.setVisibility(android.view.View.GONE);
            android.view.View card = wrap.findViewById(R.id.hsSwipeCard42);
            if (card != null) {
                card.setTranslationX(0f);
                card.setBackground(null);
            }
        }
        hsSwipeEntry42 = null;
        hsSwipeStream42 = null;
        hsSwipeSd42 = null;
        hsSwipeArmed42 = false;
        hsSwipeDir42 = 0;
        hsSwipeLeftOk42 = false;
        hsSwipeRightOk42 = false;
        hsSwipeDownX42 = 0f;
        hsSwipeDownY42 = 0f;
    }

    // v15.97 (slice 2242): the swipe-left commit — the mark verb fired by the gesture: the row menu's own mark/un-mark verb as a twin, so the swipe and the long-press can never disagree. The mark state is read fresh at commit time, so a mark that landed while the finger travelled still reads right; the verb itself re-checks the sounding state at tap time and writes the same store the menu writes. The row's tick flips at once (the snapshot is adopted and the list rebinds here — the 1s change detector would catch it within a second anyway, but the swipe's own toast deserves its own tick).
    private void applyHsSwipeMark42(Prefs.HistEntry e) {
        if (e == null) return;
        String stream = histStreamUrlFor(e);
        if (stream.length() == 0) {
            android.widget.Toast.makeText(HistoryActivity.this, Lang.t(HistoryActivity.this, "talk_no_longer_in_list"), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        boolean listened = DlMark.isMarked(readPlayedTalksHist(), SiteHttp.identityUrl(stream));
        markListenedHist(e, stream, listened);
        refreshHistPlayedSet();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    // v15.97 (slice 2242): the swipe-right commit — the queue-add verb fired by the gesture: the row menu's own queue-add branch as a twin, so the swipe and the long-press can never disagree. The sounding re-check guards the window while the finger travels; an already-queued talk toasts that it is already in the queue instead of duplicating, and a fresh add rebinds the list at once so the row's queue state reads right.
    private void applyHsSwipeQueueAdd42(Prefs.HistEntry e) {
        if (e == null) return;
        String stream = histStreamUrlFor(e);
        String sd = histSayadawFor(e);
        if (stream.length() == 0 || sd.length() == 0) {
            android.widget.Toast.makeText(HistoryActivity.this, Lang.t(HistoryActivity.this, "talk_no_longer_in_list"), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        refreshHistoryPlayingMarks();
        if ((histPlayingUrl.length() > 0 && SiteHttp.sameTalk(stream, histPlayingUrl))
                || (histPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(stream, histPlayingLibUrl))) {
            android.widget.Toast.makeText(HistoryActivity.this, Lang.t(HistoryActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!histQueueAdd(sd, stream, e.title)) {
            android.widget.Toast.makeText(HistoryActivity.this, Lang.t(HistoryActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        android.widget.Toast.makeText(HistoryActivity.this, Lang.t(HistoryActivity.this, "added_to_queue"), android.widget.Toast.LENGTH_SHORT).show();
    }

    // The live row, re-resolved from the list at the DOWN point on every call — rows here are built fresh per bind, so a rebind landing mid-drag replaces the view under a held finger. The resolved row must still be the DOWN row (same object), or the drag has moved on and there is nothing to follow.
    private android.view.View hsSwipeWrap42() {
        if (listView == null || hsSwipeEntry42 == null) return null;
        int pos = listView.pointToPosition((int) hsSwipeDownX42, (int) hsSwipeDownY42);
        if (pos == android.widget.AdapterView.INVALID_POSITION) return null;
        if (pos < 0 || pos >= items.size()) return null;
        Prefs.HistEntry cur = items.get(pos);
        if (cur != hsSwipeEntry42) return null;
        return listView.getChildAt(pos - listView.getFirstVisiblePosition());
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "hs_clear_title"))
                .setMessage(Lang.t(this, "hs_clear_msg"))
                .setPositiveButton(Lang.t(this, "btn_clear"),
                        new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        Prefs.statsClear(HistoryActivity.this);
                        refresh();
                        Toast.makeText(HistoryActivity.this,
                                Lang.t(HistoryActivity.this, "hs_cleared"),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(Lang.t(this, "btn_no_clear"), null)
                .show();
    }

    // Sayadaw -> {group, page_url, photo_url} from the bundled
    // catalog (same asset + field pattern as Prefs.loadCatalogMaps99;
    // the asset only changes with an app update, which is a new
    // process, so a per-process cache cannot go stale). Aliases
    // resolve to the current name first, so a pre-rename name
    // stored in a recent row still opens its Room.
    private String[] catalogFor(String name) {
        if (name == null || name.length() == 0) return null;
        if (catCache == null) {
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
        return catCache.get(Prefs.canonicalSayadaw99(this, name));
    }

    // v15.73 (slice 2099): "Shuffle play" on the History screen — the chooser's v13.93 shuffle-play, now on the last list surface that lacked it: the recent talks used to play one row tap at a time (playRecent) or as an in-order run from a row onward (histPlayFromHere, v15.62); one tap here shuffles the SHOWN run (the filtered + sorted list, the 2047 rule — a search-narrowed list shuffles the narrowed run) into the queue in random order (HistShuffle.shuffleEntries over the shown list — the 2065 rule: the shown run is the run) and opens the Room on the first shuffled talk at its saved spot. Same guards as the chooser: empty list refuses with the same words, the 700ms debounce has its own clock (an invalid tap never consumes the window, v12.28), the sounding talk is never re-queued (the stay-put branch: the rest queue behind it, no restart — this screen's THREE sounding identities collapse to the two the queue pick takes, the v13.55 mark rule — a path-form row's stream URL resolves through histStreamUrlFor, so a library session's URL matches it), and the count toast counts written lines only. The anchor sayadaw is the FIRST SHUFFLED entry's sayadaw, healed exactly like playRecent's first item (catalog + the sayadawForTalk heal); leading entries with no resolvable stream URL or no catalog page are skipped, not refused (the v12.79 leg, over the shuffled order — a path-form row with no dlMap claimant has no stream verb, so its run belongs to the library, not this Room-queue run). No catalog, no run: the queue is untouched. A talk heard twice sits twice in History, so the run dedupes by identity (HistPlayFromHere.lines). The Room open is histPlayFromHere's own block verbatim (autoUrl canonicalised, six extras, NO fromPlaylist — History is not a playlist), so a queued anchor plays from its queue spot. The shuffled queue is an APPEND to the anchor's hand queue (the FavShuffle semantics — saved lines are carried verbatim, histQueueAdd's discipline), not a replace: a history tap means "play these again", and replacing would wipe the anchor's hand queue (which may hold foreign lines from the Room drawer) behind a sounding talk.
    private void shufflePlayHs(android.view.View v) {
        if (items == null || items.isEmpty()) {
            android.widget.Toast.makeText(HistoryActivity.this,
                    Lang.t(HistoryActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long now99 = android.os.SystemClock.uptimeMillis();
        if (now99 - lastHsShuffleMs < 700) return;
        lastHsShuffleMs = now99;
        refreshHistoryPlayingMarks(); // tap-time sounding identities
        java.util.List<Prefs.HistEntry> shuffled99 = HistShuffle.shuffleEntries(items, new java.util.Random());
        if (shuffled99.isEmpty()) {
            android.widget.Toast.makeText(HistoryActivity.this,
                    Lang.t(HistoryActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // The first playable shuffled entry — skip leading entries with no resolvable stream URL or no catalog page (the v12.79 leg, over the shuffled order), healing the sayadaw exactly like playRecent's (catalog + the sayadawForTalk heal). No playable entry: the queue is untouched.
        Prefs.HistEntry first99 = null;
        String firstStream99 = "";
        String sd99 = null;
        String[] cat99 = null;
        int start99 = 0;
        for (int i99 = 0; i99 < shuffled99.size(); i99++) {
            Prefs.HistEntry e99 = shuffled99.get(i99);
            if (e99 == null || e99.url == null || e99.url.length() == 0) continue;
            String stream99 = histStreamUrlFor(e99);
            if (stream99.length() == 0) continue;
            String sdTry99 = histSayadawFor(e99);
            String[] catTry99 = catalogFor(sdTry99);
            if (catTry99 == null || catTry99[1] == null || catTry99[1].length() == 0) {
                String owner99 = Prefs.sayadawForTalk(this, "lib", e99.url, "");
                if (owner99 != null && owner99.length() > 0 && !owner99.equals(sdTry99)) {
                    sdTry99 = owner99;
                    catTry99 = catalogFor(sdTry99);
                }
            }
            if (catTry99 != null && catTry99[1] != null && catTry99[1].length() > 0) {
                first99 = e99;
                firstStream99 = stream99;
                sd99 = sdTry99;
                cat99 = catTry99;
                start99 = i99;
                break;
            }
        }
        if (first99 == null) {
            android.widget.Toast.makeText(HistoryActivity.this,
                    Lang.t(HistoryActivity.this, "pl_no_play"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        boolean firstSounds99 = (histPlayingUrl.length() > 0 && SiteHttp.sameTalk(firstStream99, histPlayingUrl))
                || (histPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(firstStream99, histPlayingLibUrl));
        // The shuffled run from the first playable on, in shuffled order (the PlayFromHere pick's input), each row carrying its own sayadaw + title for the v2 lines. A row with no resolvable stream URL reads as null and the pick skips it.
        java.util.List<String> ids99 = new java.util.ArrayList<String>();
        java.util.List<String> sds99 = new java.util.ArrayList<String>();
        java.util.List<String> titles99 = new java.util.ArrayList<String>();
        for (int i99 = start99; i99 < shuffled99.size(); i99++) {
            Prefs.HistEntry r99 = shuffled99.get(i99);
            if (r99 == null) { ids99.add(null); sds99.add(""); titles99.add(""); continue; }
            String ru99 = histStreamUrlFor(r99);
            ids99.add(ru99.length() > 0 ? SiteHttp.identityUrl(ru99) : null);
            sds99.add(histSayadawFor(r99));
            titles99.add(r99.title);
        }
        // The first shuffled line carries the healed sayadaw.
        sds99.set(0, sd99);
        // The anchor sayadaw's current queue, as identities — the pick skips talks already queued (they stay where they are). Saved lines are carried verbatim (histQueueAdd's discipline), so a foreign line the Room drawer moved mid-queue survives the write.
        java.util.Set<String> queuedIds99 = new java.util.HashSet<String>();
        java.util.ArrayList<String> saved99 = new java.util.ArrayList<String>();
        try {
            String savedRaw99 = Prefs.savedQueue(this, sd99);
            if (savedRaw99 != null && savedRaw99.length() > 0) {
                for (String ln99 : savedRaw99.split("\n")) {
                    if (ln99.length() == 0) continue;
                    saved99.add(ln99);
                    QueueEntry qe99 = QueueEntry.parse(ln99);
                    if (qe99 != null && qe99.url.length() > 0)
                        queuedIds99.add(SiteHttp.identityUrl(qe99.url));
                }
            }
        } catch (Exception ignored) {}
        java.util.List<Integer> picked99 = PlayFromHere.pick(ids99,
                SiteHttp.identityUrl(firstStream99), queuedIds99,
                histPlayingUrl, histPlayingLibUrl);
        java.util.List<String> run99 = HistPlayFromHere.lines(ids99, sds99, titles99, picked99);
        int added99 = 0;
        for (String ln99 : run99) {
            QueueEntry qe99 = QueueEntry.parse(ln99);
            if (qe99 == null || qe99.url.length() == 0) continue;
            boolean dup99 = false;
            for (String ex99 : saved99) {
                QueueEntry qx99 = QueueEntry.parse(ex99);
                if (qx99 != null && SiteHttp.sameTalk(qx99.url, qe99.url)) { dup99 = true; break; }
            }
            if (dup99) continue;
            saved99.add(ln99);
            added99++;
        }
        Prefs.saveQueue(this, sd99, saved99);
        if (adapter != null) adapter.notifyDataSetChanged();
        android.widget.Toast.makeText(HistoryActivity.this, added99 > 0
                ? Lang.t(HistoryActivity.this, "queue_plain_prefix") + added99 + Lang.t(HistoryActivity.this, "queue_added_suffix")
                : Lang.t(HistoryActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
        Fx.morph(v);
        if (!firstSounds99) {
            // Open the Room on the anchor (histPlayFromHere's tail, same six extras) — a queued anchor plays from its queue spot.
            android.content.Intent it99 = new android.content.Intent(this, RoomActivity.class);
            it99.putExtra("name", sd99);
            it99.putExtra("group", cat99[0]);
            it99.putExtra("url", cat99[1]);
            it99.putExtra("photo", cat99[2]);
            it99.putExtra("autoUrl", SiteHttp.identityUrl(firstStream99));
            it99.putExtra("autoTitle", first99.title);
            Fx.tap(this);
            Fx.go(this, it99);
        }
    }

    // v15.74 (slice 2102): "Play all" on the History screen — the chooser's v13.76 Play All, now on the last list surface that lacked it: the recent talks used to play one row tap at a time (playRecent) or as an in-order run from a row onward (histPlayFromHere, v15.62) or shuffled (shufflePlayHs, 2099); one tap here queues the SHOWN run (the filtered + sorted list, the 2047 rule — a search-narrowed list plays the narrowed run) in order (HistPlayAll.run over the shown list — the 2065 rule: the shown run is the run) and opens the Room on the first talk at its saved spot. Same guards as the chooser: empty list refuses with the same words, the 700ms debounce has its own clock (an invalid tap never consumes the window, v12.28), the sounding talk is never re-queued (the stay-put branch: the rest queue behind it, no restart — this screen's THREE sounding identities collapse to the two the queue pick takes, the v13.55 mark rule — a path-form row's stream URL resolves through histStreamUrlFor, so a library session's URL matches it), and the count toast counts written lines only. The anchor sayadaw is the FIRST entry's sayadaw, healed exactly like playRecent's first item (catalog + the sayadawForTalk heal); leading entries with no resolvable stream URL or no catalog page are skipped, not refused (the v12.79 leg, over the shown order — a path-form row with no dlMap claimant has no stream verb, so its run belongs to the library, not this Room-queue run). No catalog, no run: the queue is untouched. A talk heard twice sits twice in History, so the run dedupes by identity (HistPlayFromHere.lines). The Room open is histPlayFromHere's own block verbatim (autoUrl canonicalised, six extras, NO fromPlaylist — History is not a playlist), so a queued anchor plays from its queue spot. The in-order queue is an APPEND to the anchor's hand queue (the FavPlayAll semantics — saved lines are carried verbatim, histQueueAdd's discipline), not a replace: a history tap means "play these again", and replacing would wipe the anchor's hand queue (which may hold foreign lines from the Room drawer) behind a sounding talk.
    private void playAllHs(android.view.View v) {
        if (items == null || items.isEmpty()) {
            android.widget.Toast.makeText(HistoryActivity.this,
                    Lang.t(HistoryActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long now02 = android.os.SystemClock.uptimeMillis();
        if (now02 - lastHsPlayAllMs < 700) return;
        lastHsPlayAllMs = now02;
        refreshHistoryPlayingMarks(); // tap-time sounding identities
        java.util.List<Prefs.HistEntry> ordered02 = HistPlayAll.run(items);
        if (ordered02.isEmpty()) {
            android.widget.Toast.makeText(HistoryActivity.this,
                    Lang.t(HistoryActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // The first playable entry — skip leading entries with no resolvable stream URL or no catalog page (the v12.79 leg, over the shown order), healing the sayadaw exactly like playRecent's (catalog + the sayadawForTalk heal). No playable entry: the queue is untouched.
        Prefs.HistEntry first02 = null;
        String firstStream02 = "";
        String sd02 = null;
        String[] cat02 = null;
        int start02 = 0;
        for (int i02 = 0; i02 < ordered02.size(); i02++) {
            Prefs.HistEntry e02 = ordered02.get(i02);
            if (e02 == null || e02.url == null || e02.url.length() == 0) continue;
            String stream02 = histStreamUrlFor(e02);
            if (stream02.length() == 0) continue;
            String sdTry02 = histSayadawFor(e02);
            String[] catTry02 = catalogFor(sdTry02);
            if (catTry02 == null || catTry02[1] == null || catTry02[1].length() == 0) {
                String owner02 = Prefs.sayadawForTalk(this, "lib", e02.url, "");
                if (owner02 != null && owner02.length() > 0 && !owner02.equals(sdTry02)) {
                    sdTry02 = owner02;
                    catTry02 = catalogFor(sdTry02);
                }
            }
            if (catTry02 != null && catTry02[1] != null && catTry02[1].length() > 0) {
                first02 = e02;
                firstStream02 = stream02;
                sd02 = sdTry02;
                cat02 = catTry02;
                start02 = i02;
                break;
            }
        }
        if (first02 == null) {
            android.widget.Toast.makeText(HistoryActivity.this,
                    Lang.t(HistoryActivity.this, "pl_no_play"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        boolean firstSounds02 = (histPlayingUrl.length() > 0 && SiteHttp.sameTalk(firstStream02, histPlayingUrl))
                || (histPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(firstStream02, histPlayingLibUrl));
        // The run from the first playable on, in shown order (the PlayFromHere pick's input), each row carrying its own sayadaw + title for the v2 lines. A row with no resolvable stream URL reads as null and the pick skips it.
        java.util.List<String> ids02 = new java.util.ArrayList<String>();
        java.util.List<String> sds02 = new java.util.ArrayList<String>();
        java.util.List<String> titles02 = new java.util.ArrayList<String>();
        for (int i02 = start02; i02 < ordered02.size(); i02++) {
            Prefs.HistEntry r02 = ordered02.get(i02);
            if (r02 == null) { ids02.add(null); sds02.add(""); titles02.add(""); continue; }
            String ru02 = histStreamUrlFor(r02);
            ids02.add(ru02.length() > 0 ? SiteHttp.identityUrl(ru02) : null);
            sds02.add(histSayadawFor(r02));
            titles02.add(r02.title);
        }
        // The first line carries the healed sayadaw.
        sds02.set(0, sd02);
        // The anchor sayadaw's current queue, as identities — the pick skips talks already queued (they stay where they are). Saved lines are carried verbatim (histQueueAdd's discipline), so a foreign line the Room drawer moved mid-queue survives the write.
        java.util.Set<String> queuedIds02 = new java.util.HashSet<String>();
        java.util.ArrayList<String> saved02 = new java.util.ArrayList<String>();
        try {
            String savedRaw02 = Prefs.savedQueue(this, sd02);
            if (savedRaw02 != null && savedRaw02.length() > 0) {
                for (String ln02 : savedRaw02.split("\n")) {
                    if (ln02.length() == 0) continue;
                    saved02.add(ln02);
                    QueueEntry qe02 = QueueEntry.parse(ln02);
                    if (qe02 != null && qe02.url.length() > 0)
                        queuedIds02.add(SiteHttp.identityUrl(qe02.url));
                }
            }
        } catch (Exception ignored) {}
        java.util.List<Integer> picked02 = PlayFromHere.pick(ids02,
                SiteHttp.identityUrl(firstStream02), queuedIds02,
                histPlayingUrl, histPlayingLibUrl);
        java.util.List<String> run02 = HistPlayFromHere.lines(ids02, sds02, titles02, picked02);
        int added02 = 0;
        for (String ln02 : run02) {
            QueueEntry qe02 = QueueEntry.parse(ln02);
            if (qe02 == null || qe02.url.length() == 0) continue;
            boolean dup02 = false;
            for (String ex02 : saved02) {
                QueueEntry qx02 = QueueEntry.parse(ex02);
                if (qx02 != null && SiteHttp.sameTalk(qx02.url, qe02.url)) { dup02 = true; break; }
            }
            if (dup02) continue;
            saved02.add(ln02);
            added02++;
        }
        Prefs.saveQueue(this, sd02, saved02);
        if (adapter != null) adapter.notifyDataSetChanged();
        android.widget.Toast.makeText(HistoryActivity.this, added02 > 0
                ? Lang.t(HistoryActivity.this, "queue_plain_prefix") + added02 + Lang.t(HistoryActivity.this, "queue_added_suffix")
                : Lang.t(HistoryActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
        Fx.morph(v);
        if (!firstSounds02) {
            // Open the Room on the anchor (histPlayFromHere's tail, same six extras) — a queued anchor plays from its queue spot.
            android.content.Intent it02 = new android.content.Intent(this, RoomActivity.class);
            it02.putExtra("name", sd02);
            it02.putExtra("group", cat02[0]);
            it02.putExtra("url", cat02[1]);
            it02.putExtra("photo", cat02[2]);
            it02.putExtra("autoUrl", SiteHttp.identityUrl(firstStream02));
            it02.putExtra("autoTitle", first02.title);
            Fx.tap(this);
            Fx.go(this, it02);
        }
    }

    // v15.62 (slice 2030): "Play from here" — the History row menu's
    // first play verb, the History mirror of the Talks chooser's
    // playFromHere (v15.45, slice 1942) and the library's
    // DlPlayFromHere (v15.57, slice 2001). Queue-from-here does not
    // exist on this screen and a row tap plays one talk (replacing
    // that sayadaw's hand queue), so starting a run through the
    // talks just heard took a long-press, a queue, a dismiss and a
    // re-tap per talk. One tap here queues the run from the
    // long-pressed row onward — in display order, across sayadaws
    // (every line carries its own sayadaw, so the Room renders the
    // other sayadaws' talks as foreign rows and hands over when one
    // sounds) — and opens the Room on the anchor.
    // The anchor is re-resolved at tap time (the v13.13 rule): the
    // dialog can sit open across a refresh, so the captured row may
    // have moved — the scan below matches by identity (sameTalk on
    // the raw URL, plus the dlMap resolution for a path-form row of
    // the same talk). A row the scan cannot find falls back to the
    // anchor alone (the chooser's v13.04 fallback shape), never a
    // stale range. Stream anchors only: a path-form row plays as a
    // library session (playRecent's v12.82 branch), so its run
    // belongs to the library's DlPlayFromHere, not this Room-queue
    // run — the offer gate enforces it, and the tap re-checks it.
    // History is capped at 30 rows (Prefs STATS_MAX_RECENT), so the
    // per-row histStreamUrlFor below is bounded.
    private void histPlayFromHere(Prefs.HistEntry e) {
        if (items == null || items.isEmpty() || e == null || e.url == null || e.url.length() == 0) {
            android.widget.Toast.makeText(HistoryActivity.this,
                    Lang.t(HistoryActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long now30 = android.os.SystemClock.uptimeMillis();
        if (now30 - lastHistPlayFromHereMs < 700) return;
        lastHistPlayFromHereMs = now30;
        // Tap-time re-checks (the dialog's own shape): the anchor
        // may have lost its stream URL or its sayadaw while the
        // dialog sat open, or turned into the sounding talk.
        refreshHistoryPlayingMarks();
        final String streamUrl = histStreamUrlFor(e);
        if (streamUrl.length() == 0 || e.url.startsWith("/")) {
            android.widget.Toast.makeText(HistoryActivity.this,
                    Lang.t(HistoryActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if ((histPlayingUrl.length() > 0 && SiteHttp.sameTalk(streamUrl, histPlayingUrl))
                || (histPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(streamUrl, histPlayingLibUrl))) {
            android.widget.Toast.makeText(HistoryActivity.this,
                    Lang.t(HistoryActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // The anchor sayadaw, resolved exactly like playRecent
        // (catalog + the v12.71 sayadawForTalk heal) — the queue key
        // and the Room name must be the healed name, never a stale
        // banked one. No catalog, no run: the queue is untouched.
        String sd = histSayadawFor(e);
        String[] cat = catalogFor(sd);
        if (cat == null || cat[1] == null || cat[1].length() == 0) {
            String owner = Prefs.sayadawForTalk(this, "lib", e.url, "");
            if (owner != null && owner.length() > 0 && !owner.equals(sd)) {
                sd = owner;
                cat = catalogFor(sd);
            }
        }
        if (cat == null || cat[1] == null || cat[1].length() == 0) {
            android.widget.Toast.makeText(HistoryActivity.this,
                    Lang.t(HistoryActivity.this, "pl_no_play"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // The anchor's display position, re-resolved by identity.
        int anchorIdx30 = -1;
        for (int i30 = 0; i30 < items.size(); i30++) {
            Prefs.HistEntry r30 = items.get(i30);
            if (r30 == null || r30.url == null) continue;
            if (SiteHttp.sameTalk(r30.url, e.url)) { anchorIdx30 = i30; break; }
            if (r30.url.startsWith("/")) {
                String ru30 = histStreamUrlFor(r30);
                if (ru30.length() > 0 && SiteHttp.sameTalk(ru30, streamUrl)) { anchorIdx30 = i30; break; }
            }
        }
        // Display-order identity list from the anchor onward (the
        // PlayFromHere pick's input), each row carrying its own
        // sayadaw + title for the v2 lines. A row with no resolvable
        // stream URL reads as null and the pick skips it.
        java.util.List<String> ids30 = new java.util.ArrayList<String>();
        java.util.List<String> sds30 = new java.util.ArrayList<String>();
        java.util.List<String> titles30 = new java.util.ArrayList<String>();
        if (anchorIdx30 < 0) {
            // Anchor gone from the list while the dialog sat open:
            // queue just the anchor itself, never a stale range.
            ids30.add(SiteHttp.identityUrl(streamUrl));
            sds30.add(sd);
            titles30.add(e.title);
        } else {
            for (int i30 = anchorIdx30; i30 < items.size(); i30++) {
                Prefs.HistEntry r30 = items.get(i30);
                if (r30 == null) { ids30.add(null); sds30.add(""); titles30.add(""); continue; }
                String ru30 = histStreamUrlFor(r30);
                ids30.add(ru30.length() > 0 ? SiteHttp.identityUrl(ru30) : null);
                sds30.add(histSayadawFor(r30));
                titles30.add(r30.title);
            }
            // The anchor's own line carries the healed sayadaw.
            sds30.set(0, sd);
        }
        // The anchor sayadaw's current queue, as identities — the
        // pick skips talks already queued (they stay where they
        // are). Saved lines are carried verbatim (histQueueAdd's
        // discipline), so a foreign line the Room drawer moved
        // mid-queue survives the write.
        java.util.Set<String> queuedIds30 = new java.util.HashSet<String>();
        java.util.ArrayList<String> saved30 = new java.util.ArrayList<String>();
        try {
            String savedRaw30 = Prefs.savedQueue(this, sd);
            if (savedRaw30 != null && savedRaw30.length() > 0) {
                for (String ln30 : savedRaw30.split("\n")) {
                    if (ln30.length() == 0) continue;
                    saved30.add(ln30);
                    QueueEntry qe30 = QueueEntry.parse(ln30);
                    if (qe30 != null && qe30.url.length() > 0)
                        queuedIds30.add(SiteHttp.identityUrl(qe30.url));
                }
            }
        } catch (Exception ignored) {}
        java.util.List<Integer> picked30 = PlayFromHere.pick(ids30,
                SiteHttp.identityUrl(streamUrl), queuedIds30,
                histPlayingUrl, histPlayingLibUrl);
        java.util.List<String> run30 = HistPlayFromHere.lines(ids30, sds30, titles30, picked30);
        int added30 = 0;
        for (String ln30 : run30) {
            QueueEntry qe30 = QueueEntry.parse(ln30);
            if (qe30 == null || qe30.url.length() == 0) continue;
            boolean dup30 = false;
            for (String ex30 : saved30) {
                QueueEntry qx30 = QueueEntry.parse(ex30);
                if (qx30 != null && SiteHttp.sameTalk(qx30.url, qe30.url)) { dup30 = true; break; }
            }
            if (dup30) continue;
            saved30.add(ln30);
            added30++;
        }
        Prefs.saveQueue(this, sd, saved30);
        if (adapter != null) adapter.notifyDataSetChanged();
        android.widget.Toast.makeText(HistoryActivity.this, added30 > 0
                ? Lang.t(HistoryActivity.this, "queue_plain_prefix") + added30 + Lang.t(HistoryActivity.this, "queue_added_suffix")
                : Lang.t(HistoryActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
        // Open the Room on the anchor (playRecent's tail, same six
        // extras) — a queued anchor plays from its queue spot.
        android.content.Intent it30 = new android.content.Intent(this, RoomActivity.class);
        it30.putExtra("name", sd);
        it30.putExtra("group", cat[0]);
        it30.putExtra("url", cat[1]);
        it30.putExtra("photo", cat[2]);
        it30.putExtra("autoUrl", SiteHttp.identityUrl(streamUrl));
        it30.putExtra("autoTitle", e.title);
        Fx.tap(this);
        Fx.go(this, it30);
    }

    // v15.83 (slice 2158): "Queue from here" — the bulk queue verb the History row menu lacked that the Downloaded library row menu has carried since before v13.04 (the Talks chooser ported it in v13.04), the Room drawer in-list dialog since v15.82 (slice 2153) and the Playlist detail row menu since v15.83 (slice 2157): a heard talk could be queued one by one or played from here, but never queued as a run for later without playing now. histPlayFromHere MINUS the Room open, verbatim: queues the run from the long-pressed row onward — in display order, across sayadaws (every line carries its own sayadaw, so the Room renders the other sayadaws' talks as foreign rows and hands over when one sounds) — into the anchor sayadaw's saved queue, and stops there. No playback: this is the queue-only run; play-from-here is the sibling that plays. The anchor is re-resolved at tap time (the v13.13 rule): the dialog can sit open across a refresh, so the captured row may have moved — the scan below matches by identity (sameTalk on the raw URL, plus the dlMap resolution for a path-form row of the same talk). A row the scan cannot find falls back to the anchor alone (the chooser's v13.04 fallback shape), never a stale range. Stream anchors only: a path-form row plays as a library session (playRecent's v12.82 branch), so its run belongs to the library's DlPlayFromHere, not this Room-queue run — the offer gate enforces it, and the tap re-checks it. The sounding anchor is refused whole at the offer (the queue branches' rule), NOT skipped-while-below-queue: unlike the Playlist/library shape where a sounding anchor skips only itself, here the offer never appears for a sounding row, so the helper never sees one — and the tap-time re-check below refuses the whole run (queue_changed, no partial queue) if the anchor turned sounding while the dialog sat open. The anchor sayadaw is healed exactly like histPlayFromHere/playRecent (histSayadawFor + catalogFor + the Prefs.sayadawForTalk heal) — the queue key must be the healed name, never a stale banked one. No catalog, no run: the queue is untouched, toast pl_no_play (same as histPlayFromHere). The debounce has its own clock (lastHistQueueFromHereMs, the chooser's 700ms rule, v12.28): validity BEFORE consuming the window, so an invalid tap never consumes it. The toast counts written lines only (queue_plain_prefix + N + queue_added_suffix, already_in_queue when nothing new queued). Writes only the anchor sayadaw's saved queue via Prefs.saveQueue (the histPlayFromHere write block verbatim); the history REC lines, lifetime totals and resume positions stay.
    private void queueFromHereHist(final Prefs.HistEntry e) {
        if (items == null || items.isEmpty() || e == null || e.url == null || e.url.length() == 0) {
            android.widget.Toast.makeText(HistoryActivity.this,
                    Lang.t(HistoryActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long now58 = android.os.SystemClock.uptimeMillis();
        if (now58 - lastHistQueueFromHereMs < 700) return;
        lastHistQueueFromHereMs = now58;
        // Tap-time re-checks (the dialog's own shape): the anchor
        // may have lost its stream URL or its sayadaw while the
        // dialog sat open, or turned into the sounding talk.
        refreshHistoryPlayingMarks();
        final String streamUrl58 = histStreamUrlFor(e);
        if (streamUrl58.length() == 0 || e.url.startsWith("/")) {
            android.widget.Toast.makeText(HistoryActivity.this,
                    Lang.t(HistoryActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if ((histPlayingUrl.length() > 0 && SiteHttp.sameTalk(streamUrl58, histPlayingUrl))
                || (histPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(streamUrl58, histPlayingLibUrl))) {
            android.widget.Toast.makeText(HistoryActivity.this,
                    Lang.t(HistoryActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // The anchor sayadaw, resolved exactly like playRecent
        // (catalog + the v12.71 sayadawForTalk heal) — the queue key
        // must be the healed name, never a stale banked one. No
        // catalog, no run: the queue is untouched.
        String sd58 = histSayadawFor(e);
        String[] cat58 = catalogFor(sd58);
        if (cat58 == null || cat58[1] == null || cat58[1].length() == 0) {
            String owner58 = Prefs.sayadawForTalk(this, "lib", e.url, "");
            if (owner58 != null && owner58.length() > 0 && !owner58.equals(sd58)) {
                sd58 = owner58;
                cat58 = catalogFor(sd58);
            }
        }
        if (cat58 == null || cat58[1] == null || cat58[1].length() == 0) {
            android.widget.Toast.makeText(HistoryActivity.this,
                    Lang.t(HistoryActivity.this, "pl_no_play"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // The anchor's display position, re-resolved by identity.
        int anchorIdx58 = -1;
        for (int i58 = 0; i58 < items.size(); i58++) {
            Prefs.HistEntry r58 = items.get(i58);
            if (r58 == null || r58.url == null) continue;
            if (SiteHttp.sameTalk(r58.url, e.url)) { anchorIdx58 = i58; break; }
            if (r58.url.startsWith("/")) {
                String ru58 = histStreamUrlFor(r58);
                if (ru58.length() > 0 && SiteHttp.sameTalk(ru58, streamUrl58)) { anchorIdx58 = i58; break; }
            }
        }
        // Display-order identity list from the anchor onward (the
        // PlayFromHere pick's input), each row carrying its own
        // sayadaw + title for the v2 lines. A row with no resolvable
        // stream URL reads as null and the pick skips it.
        java.util.List<String> ids58 = new java.util.ArrayList<String>();
        java.util.List<String> sds58 = new java.util.ArrayList<String>();
        java.util.List<String> titles58 = new java.util.ArrayList<String>();
        if (anchorIdx58 < 0) {
            // Anchor gone from the list while the dialog sat open:
            // queue just the anchor itself, never a stale range.
            ids58.add(SiteHttp.identityUrl(streamUrl58));
            sds58.add(sd58);
            titles58.add(e.title);
        } else {
            for (int i58 = anchorIdx58; i58 < items.size(); i58++) {
                Prefs.HistEntry r58 = items.get(i58);
                if (r58 == null) { ids58.add(null); sds58.add(""); titles58.add(""); continue; }
                String ru58 = histStreamUrlFor(r58);
                ids58.add(ru58.length() > 0 ? SiteHttp.identityUrl(ru58) : null);
                sds58.add(histSayadawFor(r58));
                titles58.add(r58.title);
            }
            // The anchor's own line carries the healed sayadaw.
            sds58.set(0, sd58);
        }
        // The anchor sayadaw's current queue, as identities — the
        // pick skips talks already queued (they stay where they
        // are). Saved lines are carried verbatim (histQueueAdd's
        // discipline), so a foreign line the Room drawer moved
        // mid-queue survives the write.
        java.util.Set<String> queuedIds58 = new java.util.HashSet<String>();
        java.util.ArrayList<String> saved58 = new java.util.ArrayList<String>();
        try {
            String savedRaw58 = Prefs.savedQueue(this, sd58);
            if (savedRaw58 != null && savedRaw58.length() > 0) {
                for (String ln58 : savedRaw58.split("\n")) {
                    if (ln58.length() == 0) continue;
                    saved58.add(ln58);
                    QueueEntry qe58 = QueueEntry.parse(ln58);
                    if (qe58 != null && qe58.url.length() > 0)
                        queuedIds58.add(SiteHttp.identityUrl(qe58.url));
                }
            }
        } catch (Exception ignored) {}
        java.util.List<Integer> picked58 = PlayFromHere.pick(ids58,
                SiteHttp.identityUrl(streamUrl58), queuedIds58,
                histPlayingUrl, histPlayingLibUrl);
        java.util.List<String> run58 = HistPlayFromHere.lines(ids58, sds58, titles58, picked58);
        int added58 = 0;
        for (String ln58 : run58) {
            QueueEntry qe58 = QueueEntry.parse(ln58);
            if (qe58 == null || qe58.url.length() == 0) continue;
            boolean dup58 = false;
            for (String ex58 : saved58) {
                QueueEntry qx58 = QueueEntry.parse(ex58);
                if (qx58 != null && SiteHttp.sameTalk(qx58.url, qe58.url)) { dup58 = true; break; }
            }
            if (dup58) continue;
            saved58.add(ln58);
            added58++;
        }
        Prefs.saveQueue(this, sd58, saved58);
        if (adapter != null) adapter.notifyDataSetChanged();
        android.widget.Toast.makeText(HistoryActivity.this, added58 > 0
                ? Lang.t(HistoryActivity.this, "queue_plain_prefix") + added58 + Lang.t(HistoryActivity.this, "queue_added_suffix")
                : Lang.t(HistoryActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
    }

    // Play one recent talk: its single v2 line becomes the
    // sayadaw's saved queue (replacing its hand queue — "play this
    // talk now"), and the Room opens on it. The Room's play pops
    // the head; the service's advance then walks whatever queue
    // follows, exactly like a hand queue.
    // v12.71: heal a banked row whose sayadaw is wrong or empty
    // (library listens banked under the stale Room name before
    // v12.71, or under "" when no Room session ever happened) —
    // the row's own URL resolves its true owner through the
    // download-time dlSayadaw record, so the talk plays from
    // History instead of toasting pl_no_play. History itself is
    // never rewritten; the heal happens at read time only.
    private void playRecent(Prefs.HistEntry e) {
        if (e == null || e.url == null || e.url.length() == 0) return;
        // v12.82 (sim1298): a path-form row IS a downloaded file —
        // the Room's load completion only matches stream URLs in
        // the loaded list, so no Room can ever play it (the sim1297
        // B finding: zero/dual-claimant listens banked rows History
        // could only toast about — pl_no_play with no owner, or the
        // wrong Room's talk_not_in_list with a stale one). Play the
        // file directly as a library session instead: the same three
        // anchors DownloadedActivity writes on a row tap, through
        // the service's no-bind play-file action, and open the
        // library so the listener sees the playing file + strip.
        // A file deleted outside the app falls through to the Room
        // route below, which toasts as it does today.
        if (e.url.startsWith("/")) {
            java.io.File f = new java.io.File(e.url);
            // v13.66 (audit-1369 F9 length-gate pass): a 0-byte file is not a
            // playable download — the old exists-only check fired
            // ACTION_PLAY_FILE with the dead path (and wrote the poisoned
            // lib anchors first). A dead file now falls through to the Room
            // route below, which toasts as it does for a deleted file.
            if (f.isFile() && f.length() > 0) {
                String title = e.title != null && e.title.length() > 0
                        ? e.title : f.getName();
                getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                        .putString("lastKind", "lib")
                        .putString("lastLibPath", e.url)
                        .putString("lastLibTitle", title).apply();
                android.content.Intent play = new android.content.Intent(
                        this, AudioService.class);
                play.setAction(AudioService.ACTION_PLAY_FILE);
                play.putExtra("url", e.url);
                play.putExtra("title", title);
                startService(play);
                Fx.tap(this);
                Fx.go(this, new android.content.Intent(this,
                        DownloadedActivity.class));
                return;
            }
        }
        String sd = e.sayadaw;
        String[] cat = catalogFor(sd);
        if (cat == null || cat[1] == null || cat[1].length() == 0) {
            String owner = Prefs.sayadawForTalk(this, "lib", e.url, "");
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
        java.util.ArrayList<String> lines = new java.util.ArrayList<String>();
        // v13.65 (planters): a legacy http REC row must not plant an
        // http queue line + autoUrl — canonicalise at the source.
        String playUrl = SiteHttp.identityUrl(e.url);
        String line = QueueEntry.format(new QueueEntry(playUrl, sd, e.title));
        if (line.length() > 0) lines.add(line);
        Prefs.saveQueue(this, sd, lines);
        Intent it = new Intent(this, RoomActivity.class);
        it.putExtra("name", sd);
        it.putExtra("group", cat[0]);
        it.putExtra("url", cat[1]);
        it.putExtra("photo", cat[2]);
        it.putExtra("autoUrl", playUrl);
        it.putExtra("autoTitle", e.title);
        Fx.tap(this);
        Fx.go(this, it);
    }

    // v13.54: the mini-player — a faithful mirror of Talks'
    // strip (syncTalksStrip / wireNowStrip: v4.31 live strip,
    // v4.61/v4.63 resume strips, v5.32 live clock, v5.45 lib
    // sayadaw, v5.67 ⏳ count, v4.68 ◈ count, v10.40
    // bound-trackless leg, v10.94 glyph colours, v13.03
    // liquid glass), minus the chooser-only head (row-mark
    // rebind, filter rebuild, header refresh — History rows
    // carry no live marks). Live session: title + ★/😴/⏳/◈/
    // ended marks, sayadaw + clock sub line, seekable progress,
    // ⏸/▶ glyph, portrait. Dead session with a saved spot
    // (>5s): the ↺ resume strip (Room talk or downloaded
    // file); anything else: hidden, so the history stays
    // list-only. Scope note: the strip is the piece — the
    // rows themselves gain no playing mark this run.
    // v13.55: sounding identities for the row mark — Talks' refreshQueueMarks playing half, minus the
    // queue cache (History has no queue marks). Room: liveSession + liveHasTrack (v10.43 bound-trackless
    // stop: a stopped talk is never the playing talk) + lastKind room -> lastUrl. Library: same live
    // guards + lastKind lib -> lastLibPath, plus the dlMap reverse lookup for URL-form rows
    // (exactly-one claimant, v8.44 — a dual-claimed file names no talk rather than the wrong one).
    // Prefs only, never per bind.
    // v13.77 (slice 1069): History row long-press menu helpers.
    // histStreamUrlFor resolves the row to the stream URL every verb
    // needs: a URL-form row canonicalises to its identity form; a
    // path-form row (a downloaded file) resolves through the dlMap
    // reverse lookup with the exactly-one-claimant rule (v8.44 — a
    // dual-claimed file names no talk rather than the wrong one), so
    // a deleted file with no claimant offers no stream verb.
    private String histStreamUrlFor(Prefs.HistEntry e) {
        if (e == null || e.url == null || e.url.length() == 0) return "";
        if (!e.url.startsWith("/")) return SiteHttp.identityUrl(e.url);
        try {
            String base = e.url.substring(e.url.lastIndexOf('/') + 1);
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
            java.util.Iterator<String> keys = map.keys();
            String hit = null;
            int matches = 0;
            while (keys.hasNext()) {
                String k = keys.next();
                if (base.equals(map.optString(k, ""))) { hit = k; matches++; }
            }
            return matches == 1 ? hit : "";
        } catch (Exception ignored) { return ""; }
    }

    // v13.77: the row's sayadaw, healed exactly like playRecent's —
    // a row banked under "" (library listens before v12.71) resolves
    // its true owner through the download-time dlSayadaw record.
    private String histSayadawFor(Prefs.HistEntry e) {
        if (e == null) return "";
        String sd = e.sayadaw != null ? e.sayadaw : "";
        if (sd.length() > 0) return sd;
        String owner = Prefs.sayadawForTalk(this, "lib", e.url, "");
        return owner != null ? owner : "";
    }

    // v15.56 (slice 1995): the bundled-catalog entry for a sayadaw
    // name (RoomActivity.catalogEntryForSayadaw, byte-identical
    // shape): exact-name match over assets/sayadaws.json,
    // returning {group, page, photo} or null. A bad row never hides
    // the rows after it (the v13.30 skip); a missing asset or a bad
    // file reads as null, never a crash. The History row menu uses
    // it for the "go to sayadaw's talks" verb.
    private String[] catalogEntryForSayadaw(String name) {
        if (name == null || name.length() == 0) return null;
        java.io.InputStream in = null;
        try {
            in = getAssets().open("sayadaws.json");
            java.io.ByteArrayOutputStream baos =
                    new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) baos.write(buf, 0, read);
            org.json.JSONArray arr = new org.json.JSONArray(
                    new String(baos.toByteArray(), "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                // v13.30 (Catalog F3): a bad row must not hide every
                // sayadaw after it — skip a non-object entry instead
                // of aborting the whole loop out of getJSONObject.
                org.json.JSONObject o;
                try { o = arr.getJSONObject(i); } catch (Exception ignored) { continue; }
                if (!name.equals(o.optString("name", ""))) continue;
                String group = o.optString("group", "");
                String page = o.isNull("page_url") ? ""
                        : o.optString("page_url", "");
                String photo = o.isNull("photo_url") ? ""
                        : o.optString("photo_url", "");
                if (photo.length() == 0 && !o.isNull("photo"))
                    photo = o.optString("photo", "");
                return new String[]{group, page, photo};
            }
        } catch (Exception ignored) {
        } finally {
            if (in != null) { try { in.close(); } catch (Exception ignored) {} }
        }
        return null;
    }

    // v13.77: the favourite set, fresh from prefs (identity-form
    // keys, the toggleFavTalk canonical form). The legacy-http
    // migration runs here so a stale http key can never hide a star.
    private java.util.Set<String> histFavSetNow() {
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

    private void refreshHistFavSet() {
        histFavSet = histFavSetNow();
    }

    private boolean histRowFav(Prefs.HistEntry e) {
        if (e == null) return false;
        String stream = histStreamUrlFor(e);
        return stream.length() > 0 && histFavSet != null
                && histFavSet.contains(stream);
    }

    // v15.85 (slice 2172): the listened-tick snapshot (the v13.77 star shape, verbatim) — histPlayedSetNow copies the playedTalks set (the SAME set the 2139 mark verb reads, so the tick and the menu label can never disagree); refreshHistPlayedSet adopts it; histRowPlayed resolves the row's stream URL through histStreamUrlFor (a downloaded path-form row claims its stream URL, the 2084 exactly-one-claimant resolve) and tests membership, so a recent talk ticks exactly when its stream twin does. Null stream names no talk.
    private java.util.Set<String> histPlayedSetNow() { return readPlayedTalksHist(); }

    private void refreshHistPlayedSet() { histPlayedSet = histPlayedSetNow(); }

    private boolean histRowPlayed(Prefs.HistEntry e) {
        if (e == null) return false;
        String stream = histStreamUrlFor(e);
        return stream.length() > 0 && histPlayedSet != null
                && histPlayedSet.contains(stream);
    }

    // v15.86 (slice 2178): the total-length clock's snapshot + resolver (the 2175 Playlist detail shape, verbatim) — histDurCacheNow copies the banked lengths (Prefs.talkDurMap, the SAME store the 2172 tick reads for finished talks, so the clock and the tick can never disagree about which talk a row is); refreshHistDurCache adopts it; histRowDurMs resolves the row's stream URL through histStreamUrlFor (a downloaded path-form row claims its stream URL, the 2084 exactly-one-claimant resolve) and reads its length, falling back to the row's own URL for a length banked under its path form (talkDurPutBoth banks both forms, the v13.96 rule). Null stream names no talk; an unknown length reads 0 and the view hides.
    private java.util.Map<String, Integer> histDurCacheNow() { return Prefs.talkDurMap(this); }

    private void refreshHistDurCache() { histDurCache = histDurCacheNow(); }

    private int histRowDurMs(Prefs.HistEntry e) {
        if (e == null || histDurCache == null) return 0;
        Integer d78 = null;
        String stream = histStreamUrlFor(e);
        if (stream != null && stream.length() > 0) d78 = histDurCache.get(SiteHttp.identityUrl(stream));
        if ((d78 == null || d78.intValue() <= 0) && e.url != null) d78 = histDurCache.get(e.url);
        return d78 != null && d78.intValue() > 0 ? d78.intValue() : 0;
    }

    // v15.89 (slice 2193): the downloaded-badge snapshot — the set is the recent list's downloaded stream addresses, built from the unfiltered pick through the same gate the row menu's file-share offer reads, so the badge and the offer can never disagree about a talk; the map is parsed once per build, never per row. The refresh adopts it; the row predicate resolves the row to its stream address the same way the tick and the clock do (a downloaded path-form row claims its stream address through the exactly-one-claimant resolve) and tests membership. A null list reads empty; a null entry or an unresolvable stream is skipped; the badge paints on the sounding row too — downloaded is a property of the talk, not a state. The badge alone does not gold the row — only a sounding or favourited row turns gold.
    private java.util.Set<String> histDlSetNow() {
        java.util.Set<String> out93 = new java.util.HashSet<String>();
        if (hsAllItems == null) return out93;
        try {
            android.content.SharedPreferences sp93 =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONObject map93 = new org.json.JSONObject(sp93.getString("dlMap", "{}"));
            for (Prefs.HistEntry e93 : hsAllItems) {
                if (e93 == null) continue;
                String stream93 = histStreamUrlFor(e93);
                if (stream93 == null || stream93.length() == 0) continue;
                if (histIsDownloaded(stream93, map93)) {
                    String id93 = SiteHttp.identityUrl(stream93);
                    if (id93 != null) out93.add(id93);
                }
            }
        } catch (Exception ignored) {}
        return out93;
    }

    private void refreshHistDlSet() { histDlSet93 = histDlSetNow(); }

    private boolean histRowDl(Prefs.HistEntry e) {
        if (e == null) return false;
        String stream93 = histStreamUrlFor(e);
        return stream93.length() > 0 && histDlSet93 != null
                && histDlSet93.contains(SiteHttp.identityUrl(stream93));
    }

    // v15.91 (slice 2204): the resume-spot snapshot (the one-parse shape the rail refresh uses) — histPosCacheNow parses the saved-spot store once, identity-keyed with the first line per identity winning (the single-lookup first-match rule); refreshHistPosCache adopts it; histRowPosMs resolves the row's stream URL through the row's stream resolver and reads its spot, falling through to the row's own URL when the stream spot is at or below 5s (a downloaded row's spot was banked under its path form), so a recent talk lines exactly when its stream twin does. Null stream names no talk; an unknown spot reads 0 — the line hides, never guesses.
    private java.util.Map<String, Integer> histPosCacheNow() {
        java.util.Map<String, Integer> out = new java.util.HashMap<String, Integer>();
        try {
            String raw = getSharedPreferences("dhamma", MODE_PRIVATE).getString("talkPosMap", "");
            if (raw != null && raw.length() > 0) {
                for (String line : raw.split("\n")) {
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

    private void refreshHistPosCache() { histPosCache = histPosCacheNow(); }

    private int histRowPosMs(Prefs.HistEntry e) {
        if (e == null || histPosCache == null) return 0;
        int p04 = 0;
        String stream = histStreamUrlFor(e);
        if (stream != null && stream.length() > 0) {
            Integer p = histPosCache.get(SiteHttp.identityUrl(stream));
            if (p != null) p04 = p.intValue();
        }
        if (p04 <= 5000 && e.url != null) {
            Integer q = histPosCache.get(e.url);
            if (q != null) p04 = q.intValue();
        }
        return p04;
    }

    // v13.77: Talks isTalkDownloaded, History-local — the dlMap
    // mapped file with the v13.66 length gate (a 0-byte file is not
    // downloaded), plus the plain basename leg for files the app
    // enqueued under a stripped name.
    private boolean histIsDownloaded(String url) {
        if (url == null || url.length() == 0) return false;
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
            return histIsDownloaded(url, map);
        } catch (Exception ignored) { return false; }
    }

    // v15.89 (slice 2193): the same gate with a caller-supplied map — the snapshot builder parses the map once per build and tests every row against it, instead of re-parsing per row; the one-argument form above keeps its exact behaviour (a fresh map per call) for the menu offers.
    private boolean histIsDownloaded(String url, org.json.JSONObject map) {
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

    // v13.77: Talks talkIsDlQueued, History-local — fresh prefs read
    // at offer/tap time, so a job queued from the Room a second
    // earlier still blocks a double enqueue.
    private boolean histIsDlQueued(String url) {
        if (url == null) return false;
        try {
            org.json.JSONArray qa = new org.json.JSONArray(
                    getSharedPreferences("dhamma", MODE_PRIVATE).getString("dlQueued", "[]"));
            for (int i = 0; i < qa.length(); i++)
                if (url.equals(qa.optString(i))) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // v13.77: Talks talkDownloadFileNameFor, History-local — the
    // dlMap name wins, else the basename stripped of ?/# with .mp3
    // guaranteed, and a basename another URL's download claims takes
    // the 8-hex MD5 suffix (v8.40 claim-alone rule).
    private String histDownloadFileNameFor(String url) {
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

    // v13.77: Talks recordTalkDownload, History-local — dlMap,
    // dlSayadaw and dlQueued land in ONE apply under DlLock.LOCK
    // (v13.68/v13.70), the URL banked in identity form (v13.71).
    private void histRecordDownload(String url, String file, String sd) {
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

    // v13.77: the sayadaw's saved-queue membership, read straight
    // from the store (no in-memory queue on this screen). A v2 line
    // and a bare v1 line both parse through QueueEntry.
    private boolean histQueueHas(String sd, String url) {
        if (sd == null || sd.length() == 0 || url == null || url.length() == 0) return false;
        try {
            String saved = Prefs.savedQueue(this, sd);
            if (saved == null || saved.length() == 0) return false;
            for (String ln : saved.split("\n")) {
                QueueEntry qe = QueueEntry.parse(ln);
                if (qe != null && SiteHttp.sameTalk(qe.url, url)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // v13.77: append the talk as a v2 line (identity URL, sayadaw,
    // title) and save the queue back. Every other line is carried
    // verbatim — foreign lines are never re-spelled or dropped here,
    // so the Room's funnel (v10.98 carry) still sees them intact.
    private boolean histQueueAdd(String sd, String url, String title) {
        if (sd == null || sd.length() == 0 || url == null || url.length() == 0) return false;
        try {
            String saved = Prefs.savedQueue(this, sd);
            java.util.ArrayList<String> lines = new java.util.ArrayList<String>();
            if (saved != null && saved.length() > 0) {
                for (String ln : saved.split("\n")) {
                    if (ln.length() == 0) continue;
                    QueueEntry qe = QueueEntry.parse(ln);
                    if (qe != null && SiteHttp.sameTalk(qe.url, url)) return false;
                    lines.add(ln);
                }
            }
            String line = QueueEntry.format(
                    new QueueEntry(SiteHttp.identityUrl(url), sd, title));
            if (line.length() == 0) return false;
            lines.add(line);
            Prefs.saveQueue(this, sd, lines);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private boolean histQueueRemove(String sd, String url) {
        if (sd == null || sd.length() == 0 || url == null || url.length() == 0) return false;
        try {
            String saved = Prefs.savedQueue(this, sd);
            if (saved == null || saved.length() == 0) return false;
            java.util.ArrayList<String> lines = new java.util.ArrayList<String>();
            boolean dropped = false;
            for (String ln : saved.split("\n")) {
                if (ln.length() == 0) continue;
                QueueEntry qe = QueueEntry.parse(ln);
                if (qe != null && SiteHttp.sameTalk(qe.url, url)) { dropped = true; continue; }
                lines.add(ln);
            }
            if (!dropped) return false;
            Prefs.saveQueue(this, sd, lines);
            return true;
        } catch (Exception ignored) { return false; }
    }

    // v15.80 (slice 2139): "Mark as listened / Unmark as listened" — the mark verb the History row menu lacked that the Downloaded library row menu has had since v15.47 (slice 1949), the Talks chooser since v15.51 (slice 1969), the Room drawer dialogs since v15.52 (slices 1978/1979) and the New talks row menu since v15.80 (slice 2138): a heard talk could only earn or lose its listened mark by playing it to the end or opening the library — and a History row is exactly where both legs are live, because playedTalks is banked only on natural finish (AudioService.markPlayedForCurrent): a row stopped early sits in History with seconds but no mark (offers Mark), a finished row sits marked (offers Unmark — it returns to the New talks list). Same verb through the same rules, so the surfaces can never disagree: the 1949 pure core (DlMark — canOffer / labelKey / isMarked / mark / unmark, inputs never mutated), the v11.14 tap-time sounding re-check over this screen's two sounding identities (the queue branches' rule), the prefs-fresh playedTalks read at tap time, and the mark landing in the SAME playedTalks key AudioService banks on finish and the library, the rails, the header new-count and the filters all read. No refresh and no repaint on this screen: the row renders no listened tick (only the sounding ▶ and favourite ★ prefixes), so nothing here rebinds from playedTalks — the visible effect is the menu label flipping mark<->unmark on the next open, the verb toast, and the propagation (New talks list + header new-count + chooser tick). Neither the history REC line, the lifetime totals, the queue, the shelf order nor any saved resume position is mutated.
    private void markListenedHist(final Prefs.HistEntry e, final String streamUrl, final boolean unmark) {
        if (e == null || streamUrl == null || streamUrl.length() == 0) {
            android.widget.Toast.makeText(HistoryActivity.this,
                    Lang.t(HistoryActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // Tap-time sounding re-check (Talks v11.14): the dialog can sit open across an auto-advance, so this talk can BE sounding by tap time — it earns its mark by finishing, never by this verb. This screen's two sounding identities, the queue branches' rule.
        refreshHistoryPlayingMarks();
        if ((histPlayingUrl.length() > 0 && SiteHttp.sameTalk(streamUrl, histPlayingUrl))
                || (histPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(streamUrl, histPlayingLibUrl))) {
            android.widget.Toast.makeText(HistoryActivity.this,
                    Lang.t(HistoryActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        DlMark.Result res39 = null;
        try {
            android.content.SharedPreferences sp39 = getSharedPreferences("dhamma", MODE_PRIVATE);
            java.util.Set<String> cur39 = sp39.getStringSet("playedTalks", new java.util.HashSet<String>());
            res39 = unmark ? DlMark.unmark(cur39, SiteHttp.identityUrl(streamUrl), streamUrl)
                    : DlMark.mark(cur39, SiteHttp.identityUrl(streamUrl));
            sp39.edit().putStringSet("playedTalks", res39.set).apply();
        } catch (Exception ignored) {}
        android.widget.Toast.makeText(HistoryActivity.this,
                Lang.t(HistoryActivity.this, (res39 != null && res39.changed) ? (unmark ? "dl_menu_unmark_one" : "dl_menu_mark_one") : (unmark ? "nothing_to_unmark" : "already_listened")),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private java.util.Set<String> readPlayedTalksHist() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        try {
            java.util.Set<String> raw = getSharedPreferences("dhamma", MODE_PRIVATE).getStringSet("playedTalks", null);
            if (raw != null) out.addAll(raw);
        } catch (Exception ignored) {}
        return out;
    }

    // v13.77 (slice 1069): the recent row's long-press menu — the
    // History screen's rows were tap-only: a listener who had just
    // heard a talk could only replay it (replacing that sayadaw's
    // hand queue), never favourite, share, queue or download it
    // from the screen that lists what was heard. The dialog mirrors
    // Talks' (same keys, same key-dispatched handler, same tap-time
    // re-checks), minus the verbs History cannot offer soundly: no
    // play-next (the waiting-foreign-head correction needs the
    // sayadaw's loaded list, which this screen never loads) and no
    // move (History has no queue view to pick up from). The sounding
    // row keeps share / favourite / download / remove (none of them
    // touches the queue); only the queue verbs gate on non-sounding,
    // like Talks' v5.82 rule. "Add to playlist" (the Room's v12.65 verb —
    // file the talk in one of the listener's own lists) composes
    // right before Remove from history, so the collect verb sits
    // with the removal, last; playlist-add landed in slice 2095.
    // Remove-from-history drops the row's
    // REC line(s) only — the lifetime totals stay (the time WAS
    // listened) — so a stale row no longer needs the clear-all
    // button. A row with no resolvable stream URL offers remove
    // only, never an empty dialog.
    private void showHistRowMenu(final Prefs.HistEntry e) {
        if (e == null) return;
        final String streamUrl = histStreamUrlFor(e);
        final String sd = histSayadawFor(e);
        refreshHistoryPlayingMarks();
        final boolean sounding = streamUrl.length() > 0
                && ((histPlayingUrl.length() > 0 && SiteHttp.sameTalk(streamUrl, histPlayingUrl))
                        || (histPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(streamUrl, histPlayingLibUrl)));
        java.util.ArrayList<String> opts = new java.util.ArrayList<String>();
        java.util.ArrayList<String> keys = new java.util.ArrayList<String>();
        if (streamUrl.length() > 0) {
            opts.add(Lang.t(this, "menu_share"));
            keys.add("menu_share");
            // v15.53 (slice 1981): copy this one talk's LINK — the History row menu's clipboard verb: the menu could share the talk's text (menu_share above) but never copy the link — a listener who had just heard a talk and wanted just the link pasted elsewhere had to share it through the sheet and copy from there. Same verb the library per-row menu has had since v6.56, the Room drawer gained in slice 1975 and the Talks chooser in slice 1977, through the same RoomCopyLink core (same rule, so the four surfaces can never disagree); the offer gate is blank-url-only, so a streamed-only talk still offers it. Sits right after the text share, so the share/copy pair sits together, like the chooser's trio. The sounding row keeps it (it touches neither playback nor the queue, like the share beside it); a row with no resolvable stream URL never reaches this block, so it offers remove only.
            if (RoomCopyLink.canOffer(streamUrl)) { opts.add(Lang.t(this, "dl_menu_copy_link")); keys.add("dl_menu_copy_link"); }
            // v15.53 (slice 1982): share this one DOWNLOADED FILE — the History row menu's file-share verb: the menu could share the talk's text (menu_share above) and copy its link (dl_menu_copy_link, slice 1981) but never send the actual .mp3 — a listener who had just heard a talk and wanted the file itself in another app had to find it in Downloads/Dhamma by hand. Same verb the library per-row menu has had since v15.48 (slice 1954), the Room drawer since slice 1955 and the Talks chooser since slice 1956, through the same TalkFileShare core (same rule, so the four surfaces can never disagree); the offer gate is History's own histIsDownloaded (v13.77 — dlMap mapped file + length gate, plus the basename leg), so a streamed-only talk never offers it. Sits right after the copy-link offer, so the share/copy/file trio sits together, like the chooser's. The sounding row keeps it (it touches neither playback nor the queue, like the share beside it); a row with no resolvable stream URL never reaches this block, so it offers remove only. The 1956 note's "share family complete on every surface that has a text share" is corrected here — History had a text share all along and was the one surface still missing its file share.
            if (histIsDownloaded(streamUrl)) { opts.add(Lang.t(this, "dl_menu_share_file")); keys.add("dl_menu_share_file"); }
            // v15.56 (slice 1995): this row's sayadaw resolved
            // through the bundled catalog for the "go to
            // sayadaw's talks" verb below — one asset parse per
            // menu open, the same cost the share block above
            // already pays for its reverse lookups. A miss reads
            // as no-go (never a crash, never a dead tap).
            final String[] goCat95 = catalogEntryForSayadaw(sd);
            boolean fav = false;
            try {
                java.util.Set<String> ft = getSharedPreferences("dhamma", MODE_PRIVATE)
                        .getStringSet("favTalks", null);
                fav = ft != null && ft.contains(streamUrl);
            } catch (Exception ignored) {}
            opts.add(Lang.t(this, fav ? "menu_fav_remove" : "menu_fav_add"));
            keys.add(fav ? "menu_fav_remove" : "menu_fav_add");
            // v15.80 (slice 2139): the mark key — gated on the 1949 DlMark core (canOffer: a non-blank stream URL, and not the sounding talk — the sounding talk earns its mark by finishing, like the 1949 sounding exclusion), through this screen's two sounding identities (the same gate the queue verbs read). The label is DlMark.labelKey over the prefs-fresh played set (readPlayedTalksHist, a local mirror of NewTalksActivity's readPlayedTalks — HistoryActivity had no playedTalks reader), so the label can never disagree with the list membership — and on this screen both legs are live, because playedTalks is banked only on natural finish: a row stopped early offers Mark, a finished row offers Unmark.
            if (DlMark.canOffer(streamUrl, sounding)) { String markKey39 = DlMark.labelKey(DlMark.isMarked(readPlayedTalksHist(), SiteHttp.identityUrl(streamUrl))); opts.add(Lang.t(this, markKey39)); keys.add(markKey39); }
            // v15.56 (slice 1995): "go to sayadaw's talks" —
            // the row menu's first navigation verb: a heard
            // talk's sayadaw (sd, the healed row name)
            // resolved through the bundled catalog opens that
            // sayadaw's Talks list inside the app, so a
            // listener who liked a just-heard talk can reach
            // the full catalogue without going back to Main
            // and searching. Offered on any row whose sayadaw
            // resolves to a page URL — sounding or not, the
            // navigation never touches playback — and hidden
            // while the sayadaw is unknown or unlisted
            // (HistGo.canOffer). Sits after the favourite
            // toggle — the other per-talk state toggle — and
            // before the queue verb, the drawer foreign
            // dialog's shape (1985).
            if (HistGo.canOffer(sd, goCat95)) { opts.add(Lang.t(this, "menu_go_sayadaw")); keys.add("menu_go_sayadaw"); }
        }
        if (streamUrl.length() > 0 && sd.length() > 0 && !sounding) {
            boolean queued = histQueueHas(sd, streamUrl);
            opts.add(Lang.t(this, queued ? "menu_queue_remove" : "menu_queue_add"));
            keys.add(queued ? "menu_queue_remove" : "menu_queue_add");
            // v15.83 (slice 2158): "Queue from here" — the bulk queue verb the History row menu lacked that the Downloaded library row menu has carried since before v13.04, the Talks chooser since v13.04, the Room drawer in-list dialog since v15.82 (slice 2153) and the Playlist detail row menu since v15.83 (slice 2157): queue the run from this row onward into the anchor sayadaw's saved queue, for later listening, without playing now. The gate is EXACTLY the play-from-here gate on this menu — stream anchors only (!e.url.startsWith("/")), inside this !sounding block — so the two run verbs can never disagree about which rows offer a run; a path-form row's run belongs to the library's DlPlayFromHere, not this Room-queue run. The sounding anchor is refused whole at the offer (the queue branches' rule), NOT skipped-while-below-queue: the offer never appears for a sounding row, so the helper never sees one. It composes right after the queue add/remove offer and before play-from-here (the chooser's order: queue add/remove, queue-from-here, play-from-here), so the display order reads Share, CopyLink, ShareFile, Fav, Mark, Go, Queue, QueueFromHere, PlayFromHere, Download, PlAdd, Remove.
            if (!e.url.startsWith("/")) { opts.add(Lang.t(this, "menu_queue_from_here")); keys.add("menu_queue_from_here"); }
            // v15.62 (slice 2030): "Play from here" — the row
            // menu's first play verb (the chooser's
            // menu_play_from_here, v15.45): queue the run from
            // this row onward and open the Room on it, in one
            // tap. Stream anchors only: a path-form row plays
            // as a library session (playRecent's v12.82
            // branch), not in the Room, so its run belongs to
            // the library's DlPlayFromHere, not this Room-queue
            // run. The menu's streamUrl is "" for an
            // unresolvable path row, but a downloaded talk CAN
            // resolve (exactly-one dlMap hit) — the e.url gate
            // below is what keeps the file session honest, not
            // the resolver.
            if (!e.url.startsWith("/")) { opts.add(Lang.t(this, "menu_play_from_here")); keys.add("menu_play_from_here"); }
        }
        if (streamUrl.length() > 0 && !histIsDownloaded(streamUrl) && !histIsDlQueued(streamUrl)) {
            opts.add(Lang.t(this, "dl_btn_download"));
            keys.add("dl_btn_download");
        }
        opts.add(Lang.t(this, "hs_remove_one"));
        keys.add("hs_remove_one");
        // v15.73 (slice 2095): the playlist verb — "Add to playlist"
        // (HistPlAdd, the Room's showPlaylistAddDialog shape,
        // v12.65–v12.80): the menu's already-resolved streamUrl
        // (no second resolve, the v5.69 cost rule) offers filing
        // the talk in one of the listener's own lists, so a
        // listener who just heard a talk never has to open the
        // Room to file it. The gate is the same non-blank URL
        // the share reads (HistPlAdd.canOffer), so a streamed-only
        // talk still offers it (filing is not a file); only a
        // blank URL hides it. The key composes right before
        // Remove from history (below), so the collect verb sits
        // with the removal, last — the share trio, the play verbs
        // and the navigation are never split by it.
        if (HistPlAdd.canOffer(streamUrl)) {
            int at95 = keys.indexOf("hs_remove_one");
            keys.add(at95 < 0 ? keys.size() : at95, "pl_add");
            opts.add(at95 < 0 ? opts.size() : at95, Lang.t(this, "pl_add"));
        }
        final String[] optArr = opts.toArray(new String[0]);
        final String[] keyArr = keys.toArray(new String[0]);
        new android.app.AlertDialog.Builder(this)
                .setTitle(e.title != null && e.title.length() > 0 ? e.title : e.url)
                .setItems(optArr, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if ("menu_share".equals(keyArr[which])) {
                            String name = sd.length() > 0 ? sd : e.sayadaw;
                            Intent send = new Intent(Intent.ACTION_SEND);
                            send.setType("text/plain");
                            send.putExtra(Intent.EXTRA_TEXT, (name != null && name.length() > 0 ? name + " — " : "")
                                    + (e.title != null ? e.title : "")
                                    + "\n" + streamUrl + Lang.t(HistoryActivity.this, "share_source"));
                            startActivity(Intent.createChooser(send, Lang.t(HistoryActivity.this, "share_talk")));
                        } else if ("dl_menu_copy_link".equals(keyArr[which])) {
                            // v15.53 (slice 1981): "Copy this talk's link" — the History row menu's clipboard verb (see the offer above for the rule). Copies the talk's own original link verbatim (the v6.56 rule — never a bare title, never a guessed link) with the v6.56 clipboard label + toast, through the RoomCopyLink core: the tap-time gate re-checks the offer, so the two can never disagree. No pause guard (no sheet is left to return from) and no file gate (a link is not a file — a streamed-only talk copies too). The sounding talk keeps it (neither share nor copy touches playback). A copy mutates nothing — the history line stays, the lifetime totals stay.
                            if (RoomCopyLink.canOffer(streamUrl)) {
                                try {
                                    android.content.ClipboardManager cm81 =
                                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                    if (cm81 != null)
                                        cm81.setPrimaryClip(android.content.ClipData.newPlainText(
                                                Lang.t(HistoryActivity.this, "clip_talk"), RoomCopyLink.clipText(streamUrl)));
                                    android.widget.Toast.makeText(HistoryActivity.this,
                                            RoomCopyLink.toastText(Lang.t(HistoryActivity.this, "link_copied_prefix"), e.title, streamUrl),
                                            android.widget.Toast.LENGTH_SHORT).show();
                                } catch (Exception ignored) {}
                            }
                        } else if ("dl_menu_share_file".equals(keyArr[which])) {
                            // v15.53 (slice 1982): "Share the audio file" — the History row menu's file-share verb (see the offer above for the rule). The menu_share branch above sends the talk's text (title + original dhammadownload.com link + credit); a listener who wanted the actual .mp3 in another app had no way to send it from History — the file sits in Downloads/Dhamma and a file:// URI is refused on API 24+. The file crosses as a TalkFileProvider content:// URI with a one-shot read grant, type audio/mpeg, same chooser title as every other share. The name comes from History's own histIsDownloaded + histDownloadFileNameFor (v13.77 — dlMap name wins, else the basename leg), re-checked fresh at tap time, and the URI is built through TalkFileShare.shareUriFor (resolve + uriStringFor), so a name that no longer resolves to a file on disk toasts the existing talk_no_longer_in_list, never a guessed URI. No pause guard (History's onPause only stops the strip tick; the text share beside it sets none). A share mutates nothing — the history line stays, the lifetime totals stay.
                            java.io.File dir82 = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma");
                            String fn82 = histIsDownloaded(streamUrl) ? histDownloadFileNameFor(streamUrl) : null;
                            String uri82 = TalkFileShare.shareUriFor(dir82, fn82);
                            if (uri82 == null) {
                                android.widget.Toast.makeText(HistoryActivity.this, Lang.t(HistoryActivity.this, "talk_no_longer_in_list"), android.widget.Toast.LENGTH_SHORT).show();
                            } else {
                                Intent send82 = new Intent(Intent.ACTION_SEND);
                                send82.setType("audio/mpeg");
                                send82.putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(uri82));
                                send82.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(Intent.createChooser(send82, Lang.t(HistoryActivity.this, "share_talk")));
                            }
                        } else if ("pl_add".equals(keyArr[which])) {
                            addToPlaylistHist(e);
                        } else if ("menu_fav_add".equals(keyArr[which]) || "menu_fav_remove".equals(keyArr[which])) {
                            boolean added = Fx.toggleFavTalk(HistoryActivity.this, streamUrl);
                            // Talks v10.03: a flip for the sounding talk refreshes the card star.
                            try {
                                android.content.SharedPreferences rsp =
                                        getSharedPreferences("dhamma", MODE_PRIVATE);
                                boolean cardTalk = SiteHttp.sameTalk(streamUrl, rsp.getString("lastUrl", ""));
                                if (!cardTalk) {
                                    String libPath = rsp.getString("lastLibPath", "");
                                    if (libPath.length() > 0) {
                                        String fn = new org.json.JSONObject(rsp.getString("dlMap", "{}"))
                                                .optString(streamUrl, "");
                                        cardTalk = fn.length() > 0
                                                && fn.equals(new java.io.File(libPath).getName());
                                    }
                                }
                                if (cardTalk) {
                                    Intent rsv = new Intent(HistoryActivity.this, AudioService.class);
                                    rsv.setAction(AudioService.ACTION_REFRESH_NOTIFICATION);
                                    startService(rsv);
                                }
                            } catch (Exception ignored) {}
                            refreshHistFavSet();
                            if (adapter != null) adapter.notifyDataSetChanged();
                            Toast.makeText(HistoryActivity.this,
                                    Lang.t(HistoryActivity.this, added ? "fav_added" : "fav_removed"),
                                    Toast.LENGTH_SHORT).show();
                        } else if ("dl_menu_mark_one".equals(keyArr[which])) {
                            // v15.80 (slice 2139): "Mark as listened" — the 1949 mark verb on the History row menu, through markListenedHist below (the file routes every menu verb through a named helper or an inline block).
                            markListenedHist(e, streamUrl, false);
                        } else if ("dl_menu_unmark_one".equals(keyArr[which])) {
                            // v15.80 (slice 2139): "Unmark as listened" — the 1949 unmark leg on the History row menu, through markListenedHist below (both-forms rule: the canonical URL and the raw URL leave the played set together).
                            markListenedHist(e, streamUrl, true);
                        } else if ("menu_go_sayadaw".equals(keyArr[which])) {
                            // v15.56 (slice 1995): "Go to sayadaw's talks" — the 1944 verb on the History surface: re-resolve the sayadaw through the bundled catalog at tap time (the offer and the tap can never disagree), toast no_sayadaw_page on a failed re-read, else open that sayadaw's Talks list inside the app with Main's own intent extras (name/group/url/photo). The name is the row's own sayadaw (histSayadawFor's heal, never the catalog's — the catalog entry carries no name slot). A navigation mutates nothing: no history, queue or resume change, no pause guard (no sheet is left to return from).
                            String[] cat95 = catalogEntryForSayadaw(sd);
                            if (!GoSayadaw.canGo(cat95)) {
                                android.widget.Toast.makeText(HistoryActivity.this, Lang.t(HistoryActivity.this, "no_sayadaw_page"), android.widget.Toast.LENGTH_SHORT).show();
                            } else {
                                android.content.Intent gi95 = new android.content.Intent(HistoryActivity.this, TalksActivity.class);
                                gi95.putExtra("name", sd);
                                gi95.putExtra("group", GoSayadaw.group(cat95));
                                gi95.putExtra("url", GoSayadaw.page(cat95));
                                gi95.putExtra("photo", GoSayadaw.photo(cat95));
                                Fx.tap(HistoryActivity.this);
                                Fx.go(HistoryActivity.this, gi95);
                            }
                        } else if ("menu_queue_add".equals(keyArr[which])) {
                            // Tap-time sounding re-check (Talks v11.14): the dialog can sit open
                            // across an auto-advance, and queueing the sounding talk would replay it.
                            refreshHistoryPlayingMarks();
                            if ((histPlayingUrl.length() > 0 && SiteHttp.sameTalk(streamUrl, histPlayingUrl))
                                    || (histPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(streamUrl, histPlayingLibUrl))) {
                                Toast.makeText(HistoryActivity.this,
                                        Lang.t(HistoryActivity.this, "queue_changed"),
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (!histQueueAdd(sd, streamUrl, e.title)) {
                                Toast.makeText(HistoryActivity.this,
                                        Lang.t(HistoryActivity.this, "already_in_queue"),
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (adapter != null) adapter.notifyDataSetChanged();
                            Toast.makeText(HistoryActivity.this,
                                    Lang.t(HistoryActivity.this, "added_to_queue"),
                                    Toast.LENGTH_SHORT).show();
                        } else if ("menu_queue_remove".equals(keyArr[which])) {
                            refreshHistoryPlayingMarks();
                            if ((histPlayingUrl.length() > 0 && SiteHttp.sameTalk(streamUrl, histPlayingUrl))
                                    || (histPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(streamUrl, histPlayingLibUrl))) {
                                Toast.makeText(HistoryActivity.this,
                                        Lang.t(HistoryActivity.this, "queue_changed"),
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (!histQueueRemove(sd, streamUrl)) {
                                Toast.makeText(HistoryActivity.this,
                                        Lang.t(HistoryActivity.this, "queue_changed"),
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (adapter != null) adapter.notifyDataSetChanged();
                            Toast.makeText(HistoryActivity.this,
                                    Lang.t(HistoryActivity.this, "removed_from_queue"),
                                    Toast.LENGTH_SHORT).show();
                        } else if ("menu_queue_from_here".equals(keyArr[which])) {
                            // v15.83 (slice 2158): "Queue from here" — the bulk queue verb on the History row menu, through queueFromHereHist below (the file routes every menu verb through a named helper or an inline block). Queue from here landed in slice 2158.
                            queueFromHereHist(e);
                        } else if ("menu_play_from_here".equals(keyArr[which])) {
                            // v15.62 (slice 2030): play from here
                            // — the dialog's first play verb (see
                            // histPlayFromHere below): queue the run
                            // from the long-pressed row onward and
                            // open the Room on it in one tap.
                            histPlayFromHere(e);
                        } else if ("dl_btn_download".equals(keyArr[which])) {
                            // Tap-time re-checks (Talks v11.70): a Wi-Fi job can land, or another
                            // path can queue this talk, while the dialog sits open.
                            if (histIsDownloaded(streamUrl)) {
                                if (adapter != null) adapter.notifyDataSetChanged();
                                Toast.makeText(HistoryActivity.this,
                                        Lang.t(HistoryActivity.this, "downloaded_talk"),
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (histIsDlQueued(streamUrl)) {
                                Toast.makeText(HistoryActivity.this,
                                        Lang.t(HistoryActivity.this, "dl_already_queued"),
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            String file = histDownloadFileNameFor(streamUrl);
                            try {
                                android.app.DownloadManager dm = (android.app.DownloadManager)
                                        getSystemService(android.content.Context.DOWNLOAD_SERVICE);
                                android.app.DownloadManager.Request req =
                                        new android.app.DownloadManager.Request(
                                                android.net.Uri.parse(SiteHttp.siteUrl(streamUrl)));
                                req.setTitle(e.title != null && e.title.length() > 0 ? e.title : streamUrl);
                                req.setNotificationVisibility(android.app.DownloadManager.Request
                                        .VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                                // Wi-Fi-only, exactly like Talks' chooser enqueue — on Wi-Fi it
                                // starts at once, on mobile data it waits for Wi-Fi, so a tap
                                // here can never silently spend mobile data.
                                req.setAllowedNetworkTypes(android.app.DownloadManager.Request.NETWORK_WIFI);
                                req.setDestinationInExternalPublicDir(
                                        android.os.Environment.DIRECTORY_DOWNLOADS, "Dhamma/" + file);
                                dm.enqueue(req);
                                histRecordDownload(streamUrl, file, sd);
                            } catch (Exception ex) {
                                Toast.makeText(HistoryActivity.this,
                                        Lang.t(HistoryActivity.this, "download_failed") + Fx.errDetail(ex),
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            if (adapter != null) adapter.notifyDataSetChanged();
                            if (Fx.stripIsWifi(HistoryActivity.this))
                                Toast.makeText(HistoryActivity.this,
                                        "\u2B07 " + (e.title != null && e.title.length() > 0 ? e.title : streamUrl)
                                                + Lang.t(HistoryActivity.this, "dl_downloading_suffix"),
                                        Toast.LENGTH_SHORT).show();
                            else
                                Toast.makeText(HistoryActivity.this,
                                        Lang.t(HistoryActivity.this, "dl_room_wifi_queued"),
                                        Toast.LENGTH_LONG).show();
                        } else if ("hs_remove_one".equals(keyArr[which])) {
                            boolean removed = Prefs.statsRemoveOne(HistoryActivity.this, e.url);
                            refresh();
                            Toast.makeText(HistoryActivity.this,
                                    Lang.t(HistoryActivity.this, removed ? "dl_deleted_done" : "nothing_to_delete"),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    // v15.73 (slice 2095): "Add to playlist" — the row menu's
    // playlist verb (the Room's showPlaylistAddDialog, v12.65–
    // v12.80, on this screen's menu; the Room's dialog has no
    // entry point anywhere in the app — defined once, called
    // zero times — so this is the verb's fourth live offer, on
    // the screen of heard talks, where "a just-heard talk ->
    // my morning / bedtime list" is the natural flow; premium
    // backlog #2, the Spotify / YouTube Music / Apple Music
    // row-menu verb): re-resolve the row to its stream URL at
    // tap time (histStreamUrlFor — the offer and the tap can never
    // disagree), toast talk_no_longer_in_list when the file
    // behind a path-form row no longer resolves (the
    // share/copy/file precedent on this screen), heal the
    // sayadaw exactly like the row tap (histSayadawFor +
    // catalogEntryForSayadaw + the sayadawForTalk heal — a stale
    // banked sayadaw would strand the playlist item under the
    // wrong owner, the v12.78 lesson; the Room's
    // playlistOwnerFor heals the same way), then offer the
    // listener's lists (Prefs.playlistNames — the seed lists are
    // there from the first read) plus a "new list" row (pl_new,
    // last). A tap files the talk through Prefs.playlistAdd and
    // toasts the v12.80 result (HistPlAdd.toastKey: added /
    // already there / full / could-not-create); the new-list row
    // opens the name dialog. An add mutates no history, queue or
    // resume state; no pause guard (no sheet is left to return
    // from); the sounding row keeps it (filing never touches
    // playback). The filed unit is the TALK, not the listen (the
    // 2084 adjudication, extended): the playlist store carries
    // url / sayadaw / title and no minute, so a filed talk plays
    // from its saved spot like every other playlist item — the
    // listen's position stays a History-screen concept
    // (play-from-here queues the run at positions; the row tap
    // replays from the saved spot).
    private void addToPlaylistHist(final Prefs.HistEntry e) {
        if (e == null || e.url == null || e.url.length() == 0) return;
        final String streamUrl = histStreamUrlFor(e);
        if (!HistPlAdd.canOffer(streamUrl)) {
            android.widget.Toast.makeText(HistoryActivity.this,
                    Lang.t(HistoryActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String sd95 = histSayadawFor(e);
        String[] cat95 = catalogEntryForSayadaw(sd95);
        if (cat95 == null || cat95[1] == null || cat95[1].length() == 0) {
            String owner95 = Prefs.sayadawForTalk(this, "lib", streamUrl, "");
            if (owner95 != null && owner95.length() > 0 && !owner95.equals(sd95)) {
                sd95 = owner95;
            }
        }
        final String title95 = e.title != null ? e.title : "";
        final String sdF95 = sd95;
        final java.util.List<String> names95 = Prefs.playlistNames(this);
        final String[] items95 = new String[names95.size() + 1];
        for (int i95 = 0; i95 < names95.size(); i95++) items95[i95] = names95.get(i95);
        items95[names95.size()] = Lang.t(this, "pl_new");
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "pl_choose"))
                .setItems(items95, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (HistPlAdd.isNewRow(which, names95.size())) {
                            showNewPlaylistHist(streamUrl, title95, sdF95);
                            return;
                        }
                        int r95 = Prefs.playlistAdd(HistoryActivity.this,
                                names95.get(which), streamUrl, sdF95, title95);
                        android.widget.Toast.makeText(HistoryActivity.this,
                                Lang.t(HistoryActivity.this, HistPlAdd.toastKey(r95)),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // v15.73 (slice 2095): the "new list" dialog for the
    // playlist verb — the Room's showNewPlaylistDialog
    // (v12.80), on this screen: a name field (pl_name_hint),
    // a Create button (pl_create) and a cancel (btn_no_clear);
    // an empty name re-prompts with the hint toast; the name
    // caps at 40 chars (PLAYLIST_MAX_NAME); create is a
    // no-op for an existing name, so a repeat name just adds
    // to that list; at the 20-list cap a genuinely new name
    // toasts pl_limit (HistPlAdd.limitToastKey), any other
    // create failure pl_no_create — neither falls through to
    // the add (the v12.80 leg). The add toasts the same
    // v12.80 result as the choose dialog.
    private void showNewPlaylistHist(final String url, final String title, final String sd) {
        final android.widget.EditText et95 = new android.widget.EditText(this);
        et95.setHint(Lang.t(this, "pl_name_hint"));
        et95.setSingleLine(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "pl_new"))
                .setView(et95)
                .setPositiveButton(Lang.t(this, "pl_create"),
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        String name95 = et95.getText().toString().trim();
                        if (name95.length() == 0) {
                            android.widget.Toast.makeText(HistoryActivity.this,
                                    Lang.t(HistoryActivity.this, "pl_name_hint"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (name95.length() > 40) name95 = name95.substring(0, 40);
                        boolean created95 = Prefs.playlistCreate(HistoryActivity.this, name95);
                        java.util.List<String> held95 = Prefs.playlistNames(HistoryActivity.this);
                        String limit95 = HistPlAdd.limitToastKey(created95,
                                held95.contains(name95), held95.size());
                        if (limit95 != null) {
                            android.widget.Toast.makeText(HistoryActivity.this,
                                    Lang.t(HistoryActivity.this, limit95),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        int r95 = Prefs.playlistAdd(HistoryActivity.this, name95, url, sd, title);
                        android.widget.Toast.makeText(HistoryActivity.this,
                                Lang.t(HistoryActivity.this, HistPlAdd.toastKey(r95)),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(Lang.t(this, "btn_no_clear"), null)
                .show();
    }

    private void refreshHistoryPlayingMarks() {
        histPlayingUrl = "";
        histPlayingLibUrl = "";
        histPlayingLibPath = "";
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (AudioService.liveSession && AudioService.liveHasTrack
                    && "room".equals(sp.getString("lastKind", "")))
                histPlayingUrl = sp.getString("lastUrl", "");
            if (AudioService.liveSession && AudioService.liveHasTrack
                    && "lib".equals(sp.getString("lastKind", ""))) {
                histPlayingLibPath = sp.getString("lastLibPath", "");
                String libPath = histPlayingLibPath;
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
                    if (matches == 1) histPlayingLibUrl = hit;
                }
            }
        } catch (Exception ignored) {}
    }

    private void syncHistoryStripPhoto(String name) {
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
        if (ringing != lastHsStripPhotoRing) {
            lastHsStripPhotoRing = ringing;
            if (ring != null) {
                // v14.16 (slice 1670): the ring crossfades in/out
                // instead of snapping — see Fx.crossfadeRing.
                Fx.crossfadeRing(ring, ringing ? R.drawable.shelf_ring_playing : 0);
            }
        }
        if (name == null || name.length() == 0) {
            hsStripPhotoName = null;
            if (ring != null) ring.setVisibility(android.view.View.GONE);
            else pv.setVisibility(android.view.View.GONE);
            return;
        }
        if (ring != null) ring.setVisibility(android.view.View.VISIBLE);
        else pv.setVisibility(android.view.View.VISIBLE);
        if (name.equals(hsStripPhotoName)) return;
        boolean firstPhoto = hsStripPhotoName == null;
        hsStripPhotoName = name;
        String url = AudioService.stripPhotoUrlForSayadaw(HistoryActivity.this, name);
        // v14.22 (slice 1676): the photo crossfades on a sayadaw
        // change instead of swapping — see Fx.crossfadeStripPhoto.
        Fx.crossfadeStripPhoto((CircleImageView) pv, url, firstPhoto, new Runnable() {
            @Override public void run() {
                ImageLoader.load(HistoryActivity.this, url, name, (CircleImageView) pv);
            }
        });
    }

    // v15.40 (slice 1907): the History resume strip's title melts on a
    // structural change instead of snapping — the mirror of Main's
    // v14.70 meltResumeStripTitle70. The 1s tick re-runs
    // syncHistoryStrip in resume mode, and the title carries the
    // download mark (stripResumeDownloadMark / the lib branch's inline
    // check), so a download landing while History sits open flipped
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
    private String hsResumeStripTitle = null;
    private void meltResumeStripTitleHs(android.widget.TextView tx, String want) {
        if (tx == null || want == null) return;
        Object aObjHs = tx.getTag(R.id.stripTextAnim);
        if (aObjHs instanceof android.animation.Animator) ((android.animation.Animator) aObjHs).cancel();
        String prevHs = hsResumeStripTitle;
        hsResumeStripTitle = want;
        if (prevHs == null) { tx.setText(want); return; }
        if (prevHs.equals(want)) return;
        tx.setText(want);
        if (tx.getVisibility() == android.view.View.VISIBLE && tx.getAlpha() >= 0.999f) Fx.fadeIn(tx, 250);
    }

    // v15.40 (slice 1908): the stats cards melt on a value change
    // instead of snapping — total hours, day streak, top sayadaw
    // (+ its hours) are re-read on every refresh() (onCreate,
    // onResume, clear-confirm, row-menu remove), and a return from
    // the Room after listening flipped all three values in one
    // frame. Same rhythm as the update-gate phase melt (1906):
    // Fx.meltText dips 90ms, swaps at the bottom, rises 180ms
    // (Decelerate); same-text is a no-op inside meltText, and a
    // hidden/unsettled view adopts at once. The first refresh
    // adopts (hsStatsKnown40) so the cards never melt under their
    // own entrance; later refreshes melt only what changed. The
    // gold/sub colour stamps stay bare — they re-stamp the same
    // value every refresh, so there is nothing to melt.
    private boolean hsStatsKnown40 = false;
    private void meltStat40(android.widget.TextView tv, String want) {
        if (tv == null || want == null) return;
        if (!hsStatsKnown40) { tv.setText(want); return; }
        Fx.meltText(tv, want);
    }

    private void syncHistoryStrip() {
        // v13.55: playing-row mark — rebind once when the sounding talk changes under the list
        // (Talks v4.89/v11.15, same change-only rule): auto-advance, a Next from the strip, a
        // natural end (same URL, liveEnded flipped) or a stop moves the mark at once; steady ticks
        // never touch the adapter, so scrolling stays undisturbed. Runs before the strip's own early
        // returns, so a dead session clears the mark even while the strip hides.
        refreshHistoryPlayingMarks();
        // v13.77: the star snapshot joins the change detector — a star
        // flipped from the notification card rebinds the rows here.
        java.util.Set<String> hsFavNow77 = histFavSetNow();
        // v15.85 (slice 2172): the tick snapshot joins the change detector — a tick
        // flipped from the notification card or the mark verb rebinds the rows here.
        java.util.Set<String> hsPlayedNow72 = histPlayedSetNow();
        // v15.86 (slice 2178): the duration snapshot joins the change detector — a length landing while the list sits open rebinds the rows here (the 2172 tick leg, verbatim).
        java.util.Map<String, Integer> hsDurNow78 = histDurCacheNow();
        // v15.89 (slice 2193): the badge snapshot joins the change detector — a download landing while the list sits open rebinds the rows here.
        java.util.Set<String> hsDlNow93 = histDlSetNow();
        // v15.91 (slice 2204): the pos snapshot joins the change detector — a spot landing while the list sits open (a talk just heard banks its spot) rebinds the rows here.
        java.util.Map<String, Integer> hsPosNow04 = histPosCacheNow();
        if (adapter != null && (!histPlayingUrl.equals(histRowPlayingUrl == null ? "" : histRowPlayingUrl)
                || !histPlayingLibUrl.equals(histRowPlayingLibUrl == null ? "" : histRowPlayingLibUrl)
                || !histPlayingLibPath.equals(histRowPlayingLibPath == null ? "" : histRowPlayingLibPath)
                || AudioService.liveEnded != histRowEnded
                || !hsFavNow77.equals(histFavSet)
                || !hsPlayedNow72.equals(histPlayedSet)
                || !hsDurNow78.equals(histDurCache)
                || !hsDlNow93.equals(histDlSet93)
                || !hsPosNow04.equals(histPosCache))) {
            histRowPlayingUrl = histPlayingUrl;
            histRowPlayingLibUrl = histPlayingLibUrl;
            histRowPlayingLibPath = histPlayingLibPath;
            histRowEnded = AudioService.liveEnded;
            histFavSet = hsFavNow77;
            histPlayedSet = hsPlayedNow72;
            histDurCache = hsDurNow78;
            histDlSet93 = hsDlNow93;
            histPosCache = hsPosNow04;
            adapter.notifyDataSetChanged();
            // v15.67 (slice 2063): the sounding change above moves the playing talk (advance, stop, or a new session while browsing) — refresh the jump button's cached target and repaint it. Change-only: steady ticks never touch the button.
            refreshHsJumpTarget();
            paintHsJumpBtnFromList();
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
            hsResumeStripMode = false;
            android.content.SharedPreferences rsp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            String rKind = rsp.getString("lastKind", "");
            String rName = rsp.getString("lastName", "");
            String rTitle = rsp.getString("lastTitle", "");
            String rPage = rsp.getString("lastPage", "");
            String rUrl = rsp.getString("lastUrl", "");
            int rPos = rsp.getInt("lastPos", 0);
            String rSavedUrl = rsp.getString("lastPosUrl", "");
            boolean rMatch = false;
            if (rUrl.length() > 0 && rSavedUrl.length() > 0) {
                if (SiteHttp.sameTalk(rSavedUrl, rUrl)) rMatch = true;
                else {
                    try {
                        org.json.JSONObject map = new org.json.JSONObject(rsp.getString("dlMap", "{}"));
                        String fn = map.optString(rUrl, "");
                        // v13.62 (F-1033-B-3): legacy http dlMap key
                        // misses the raw lookup — try the identity form.
                        if (fn.length() == 0) fn = map.optString(SiteHttp.identityUrl(rUrl), "");
                        if (fn.length() > 0 && rSavedUrl.endsWith("/" + fn)) rMatch = true;
                    } catch (Exception ignored) {}
                }
            }
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
                            || Fx.stripInProgressDlFilenames(HistoryActivity.this, rsp).contains(new java.io.File(lPath).getName()))) {
                    if (rSavedUrl.equals(lPath)) lMatch = true;
                    else {
                        try {
                            org.json.JSONObject map = new org.json.JSONObject(rsp.getString("dlMap", "{}"));
                            java.util.Iterator<String> keys = map.keys();
                            String fn = new java.io.File(lPath).getName();
                            while (keys.hasNext()) {
                                String u = keys.next();
                                if (SiteHttp.sameTalk(u, rSavedUrl) && fn.equals(map.optString(u, ""))) { lMatch = true; break; }
                            }
                        } catch (Exception ignored) {}
                    }
                }
                if (lTitle.length() == 0 || !lMatch) {
                    syncHistoryStripPhoto(null);
                    hsResumeStripTitle = null;
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
                int lDur = Fx.stripResumeDuration(HistoryActivity.this, rsp, rSavedUrl, lPath);
                // v8.52: Main's v7.94 resume mark, mirrored — the proven file is offline, like Main's lib branch.
                meltResumeStripTitleHs(tx, "↺ " + lTitle + (lFav ? " ★" : "") + (Fx.stripInProgressDlFilenames(HistoryActivity.this, rsp).contains(new java.io.File(lPath).getName()) ? " ⏳" : " ⬇"));
                // v5.46: a dead library strip names its recorded
                // sayadaw like the live strip since v5.45, in the
                // Room strip's "name · mm:ss မှ ဆက်နားထောင်ရန်"
                // shape; an unrecorded download keeps today's
                // generic line, never a guessed name.
                String libSd = Fx.stripLibSayadaw(HistoryActivity.this, rsp);
                if (sb != null) sb.setText(libSd != null ? libSd + " · " + lTime + Lang.t(HistoryActivity.this, "resume_from") : Lang.t(HistoryActivity.this, "downloaded_talk") + " · " + lTime + Lang.t(HistoryActivity.this, "resume_from"));
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
                hsResumeStripMode = true;
                syncHistoryStripPhoto(libSd);
                Fx.showStrip(strip);
                return;
            }
            if (!"room".equals(rKind) || rName.length() == 0 || rTitle.length() == 0
                    || rPage.length() == 0 || rPos <= 5000 || !rMatch) {
                syncHistoryStripPhoto(null);
                hsResumeStripTitle = null;
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
            int rDur = Fx.stripResumeDuration(HistoryActivity.this, rsp, rUrl, null);
            // v8.52: Main's v7.94 resume download mark, mirrored — a ↺ strip here never said whether resuming streams or plays offline.
            meltResumeStripTitleHs(tx, "↺ " + rTitle + (rFav ? " ★" : "") + Fx.stripResumeDownloadMark(HistoryActivity.this, rsp, rUrl));
            if (sb != null) sb.setText(rName + " · " + rTime + Lang.t(HistoryActivity.this, "resume_from"));
            if (tg != null) tg.setText("▶");
            if (nx != null) Fx.crossfadeStripBtn(nx, false);
            // v7.31: same Previous hide as the library resume
            // branch above — a ↺ strip never shows ⏮.
            android.view.View pv0 = findViewById(R.id.nowStripPrev);
            if (pv0 != null) Fx.crossfadeStripBtn(pv0, false);
            android.widget.ProgressBar rPb = findViewById(R.id.nowStripProgress);
            if (rPb != null) Fx.stripResumeProgress(rPb, rPos, rDur);
            hsResumeStripMode = true;
            syncHistoryStripPhoto(rName);
            Fx.showStrip(strip);
            return;
        }
        hsResumeStripMode = false;
        hsResumeStripTitle = null; // v15.40: live branch owns tx via fadeStripText from here
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
                String libSd = Fx.stripLibSayadaw(HistoryActivity.this, sp);
                subText = libSd != null ? libSd : Lang.t(HistoryActivity.this, "downloaded_talk");
            }
        }
        if (text == null) { syncHistoryStripPhoto(null); Fx.hideStrip(strip); return; }
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
                    ? " 😴 " + Lang.t(HistoryActivity.this, "sleep_end")
                    : " 😴 ⏸ " + Lang.t(HistoryActivity.this, "sleep_end");
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
                String saved = Prefs.savedQueue(HistoryActivity.this,
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
                String saved = Prefs.savedQueueLib(HistoryActivity.this);
                if (saved.length() > 0) {
                    for (String line : saved.split("\n"))
                        if (line.trim().length() > 0
                                && new java.io.File(line.trim()).isFile()
                                && !line.trim().equals(curLib)) qN++;
                }
            }
            if (qN > 0) text += " · ◈ " + qN;
        } catch (Exception ignored) {}
        if (AudioService.liveEnded) text += " · " + Lang.t(HistoryActivity.this, "notif_state_ended");
        // v14.21 (slice 1675): the strip title/sub fade on a talk
        // change, keyed on the talk itself (Room URL / library
        // path) — the composed strings tick every second (sleep
        // countdown on the title, live clock on the sub), so the
        // text can never be the key.
        String stripTalkId = "room".equals(kind) ? sp.getString("lastUrl", "") : sp.getString("lastLibPath", "");
        if (tx != null) Fx.fadeStripText(tx, text, stripTalkId);
        // v5.32: live clock on the strip — sayadaw name + elapsed/total.
        android.widget.ProgressBar pb = findViewById(R.id.nowStripProgress);
        if (subText != null) subText += Fx.stripClockSuffixForStrip(pb, AudioService.livePositionMs, AudioService.liveDurationMs, Prefs.stripClockRemaining(HistoryActivity.this));
        if (sb != null && subText != null) Fx.fadeStripText(sb, subText, stripTalkId);
        Fx.syncStripProgress(pb); // v7.93: skips while a mini-seek drag is in flight
        if (tg != null) Fx.flipStripGlyph(tg, AudioService.livePlaying);
        // v6.01: live strip's portrait — Room names lastName, library
        // names its recorded sayadaw (Fx.stripLibSayadaw, null when
        // unrecorded); both resolve through syncHistoryStripPhoto's
        // exact-name catalog match, never a guessed face.
        syncHistoryStripPhoto("room".equals(kind) ? sp.getString("lastName", "")
                : ("lib".equals(kind) ? Fx.stripLibSayadaw(HistoryActivity.this, sp) : null));
        Fx.showStrip(strip);
    }

    private void startHistoryStripTick() {
        if (stripTick != null) return;
        stripTick = new Runnable() {
            @Override public void run() {
                if (stripTick == null) return;
                // v11.76: re-post in finally — a throw inside syncHistoryStrip used
                // to kill the 1s tick outright, freezing strip title/clock/
                // progress/glyph until the next onPause/onResume cycle.
                try { syncHistoryStrip(); }
                finally { stripHandler.postDelayed(this, 1000); }
            }
        };
        stripHandler.post(stripTick);
    }

    private void stopHistoryStripTick() {
        if (stripTick != null) {
            stripHandler.removeCallbacks(stripTick);
            stripTick = null;
        }
    }

    private void wireHistoryStrip() {
        android.view.View nowStrip = findViewById(R.id.nowStrip);
        if (nowStrip != null) {
            Fx.press(nowStrip);
            // v4.73: the strip's progress bar seeks (see Fx.miniSeekBar).
            Fx.miniSeekBar(HistoryActivity.this, nowStrip);
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
                        if (hsResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) return;
                        Prefs.setStripClockRemaining(HistoryActivity.this, !Prefs.stripClockRemaining(HistoryActivity.this));
                        Fx.tap(HistoryActivity.this);
                        syncHistoryStrip();
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
                            Fx.tap(HistoryActivity.this);
                            Fx.morph(v);
                            Intent li = new Intent(HistoryActivity.this, DownloadedActivity.class);
                            li.putExtra("jumpPath", lp);
                            // v4.63: in dead-library resume mode
                            // the tap also auto-plays the file from
                            // its saved spot; a live session never
                            // passes this flag, so it still only
                            // glides to the playing file.
                            // v12.57: live recheck, not just the cached
                            // flag — hsResumeStripMode refreshes on
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
                            if (hsResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) li.putExtra("autoPlayPath", lp);
                            Fx.go(HistoryActivity.this, li);
                            return;
                        }
                        android.widget.Toast.makeText(HistoryActivity.this, Lang.t(HistoryActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(HistoryActivity.this, Lang.t(HistoryActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(HistoryActivity.this, RoomActivity.class);
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
                    Fx.tap(HistoryActivity.this);
                    Fx.morph(v);
                    Fx.go(HistoryActivity.this, i);
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
                    // hsResumeStripMode refreshes on the 1s tick, so a
                    // session dying <1s before this long-press left the
                    // flag false and the press ACTION_STOPped a trackless
                    // service (phantom "talk_stopped" toast, saved spot
                    // NOT dismissed — the same ≤1s stale-flag class
                    // v12.55 closed for the Toggle long-press). The
                    // strip's own branch test is !liveSession ||
                    // !liveHasTrack, so re-checking the same statics here
                    // can only dismiss a genuinely dead session, never
                    // stop a live one.
                    if (hsResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        // v5.34: same as Talks' dismiss — the
                        // per-talk spot goes with the global one.
                        String dismissUrl = getSharedPreferences("dhamma", MODE_PRIVATE)
                                .getString("lastPosUrl", "");
                        getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                                .putInt("lastPos", 0).putString("lastPosUrl", "").apply();
                        if (dismissUrl.length() > 0) Prefs.talkPosRemoveBoth(HistoryActivity.this, dismissUrl);
                        Fx.tap(HistoryActivity.this);
                        Fx.morph(v);
                        // v13.28: same as Downloaded's dismiss — one resume spot cleared,
                        // not the history; toast resume_cleared (Main's key), not
                        // hist_cleared (Settings' real clear-history key).
                        android.widget.Toast.makeText(HistoryActivity.this, Lang.t(HistoryActivity.this, "resume_cleared"), android.widget.Toast.LENGTH_SHORT).show();
                        syncHistoryStrip();
                        // History rows carry no ↺ mark, so this
                        // rebind changes nothing visible today; it
                        // keeps the dismiss path a faithful mirror
                        // of Talks' (v12.41) if a mark ever lands.
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return true;
                    }
                    Intent svc = new Intent(HistoryActivity.this, AudioService.class);
                    svc.setAction(AudioService.ACTION_STOP);
                    startService(svc);
                    Fx.tap(HistoryActivity.this);
                    Fx.morph(v);
                    android.widget.Toast.makeText(HistoryActivity.this, Lang.t(HistoryActivity.this, "talk_stopped"), android.widget.Toast.LENGTH_SHORT).show();
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
                // v12.55: live recheck, not just the cached flag — hsResumeStripMode
                // refreshes on the 1s tick, so a session dying <1s before this tap left
                // the flag false and the tap ACTION_TOGGLEd a trackless service (dead
                // tap, tap-twice-to-resume — the exact bug Settings v11.90 fixed for
                // its Toggle). The strip's own branch test is !liveSession ||
                // !liveHasTrack, so re-checking the same statics here can only route a
                // genuinely dead session to resume, never a live one.
                if (hsResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                    android.view.View strip2 = findViewById(R.id.nowStrip);
                    if (strip2 != null) strip2.performClick();
                    return;
                }
                Intent svc = new Intent(HistoryActivity.this, AudioService.class);
                svc.setAction(AudioService.ACTION_TOGGLE);
                startService(svc);
                Fx.tap(HistoryActivity.this);
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
                if (hsResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                    android.view.View strip2 = findViewById(R.id.nowStrip);
                    if (strip2 != null) return strip2.performLongClick();
                    return true;
                }
                Intent svc = new Intent(HistoryActivity.this, AudioService.class);
                svc.setAction(AudioService.ACTION_STOP);
                startService(svc);
                Fx.tap(HistoryActivity.this);
                Fx.morph(v);
                android.widget.Toast.makeText(HistoryActivity.this, Lang.t(HistoryActivity.this, "talk_stopped"), android.widget.Toast.LENGTH_SHORT).show();
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
                    // hsResumeStripMode refreshes on the 1s tick, so a session dying
                    // <1s before this tap left the flag false and the tap ACTION_NEXT-ed
                    // into a trackless Room instead of resuming the ↺ talk.
                    if (hsResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    Fx.tap(HistoryActivity.this);
                    Fx.morph(v);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        Intent li = new Intent(HistoryActivity.this, DownloadedActivity.class);
                        li.setAction(DownloadedActivity.ACTION_NEXT);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        Fx.go(HistoryActivity.this, li);
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(HistoryActivity.this, Lang.t(HistoryActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(HistoryActivity.this, RoomActivity.class);
                    i.setAction(RoomActivity.ACTION_NEXT);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.go(HistoryActivity.this, i);
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
                    if (hsResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    Fx.tap(HistoryActivity.this);
                    Fx.morph(v);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        Intent li = new Intent(HistoryActivity.this, DownloadedActivity.class);
                        li.setAction(DownloadedActivity.ACTION_PREV);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        Fx.go(HistoryActivity.this, li);
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(HistoryActivity.this, Lang.t(HistoryActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(HistoryActivity.this, RoomActivity.class);
                    i.setAction(RoomActivity.ACTION_PREV);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.go(HistoryActivity.this, i);
                }
            });
        }
    }
}
