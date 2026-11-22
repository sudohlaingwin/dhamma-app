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
    // v15.62 (slice 2030): "Play from here" debounce — its own
    // clock (the chooser's 700ms rule, v12.28: an invalid tap
    // never consumes the window).
    private long lastHistPlayFromHereMs = 0;
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
        }
        wireHsSearch(); // v15.65 (slice 2050): search on this screen, before the first refresh
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
        refreshHistFavSet(); // v13.77: rows bind their star mark from this snapshot
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
        items = HistSearch.filter(hsAllItems, hsQuery);
        if (adapter != null) adapter.notifyDataSetChanged();
        refreshHsClear(hsSearchKeystroke);
        if (emptyText == null) return;
        boolean empty = items.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) emptyText.setText(Lang.t(this,
                hsQuery.length() > 0 ? "no_search_match" : "hs_empty"));
    }

    private View itemRow(final int position) {
        final Prefs.HistEntry e = items.get(position);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * d);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(pad, (int) (10 * d), pad, (int) (10 * d));
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
        String hsPrefix77 = (hsRowPlaying ? "▶ " : "")
                + (histRowFav(e) ? "★ " : "");
        tv.setText(hsPrefix77 + (e.title.length() > 0 ? e.title : e.url));
        tv.setTextColor((hsRowPlaying || histRowFav(e)) ? goldColor() : titleColor());
        tv.setTextSize(16);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView sv = new TextView(this);
        String sub = e.sayadaw.length() > 0
                ? e.sayadaw + " • " + fmtDur(e.seconds) : fmtDur(e.seconds);
        sv.setText(sub);
        sv.setTextColor(subColor());
        sv.setTextSize(12);
        col.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(col, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView go = new TextView(this);
        go.setText("›");
        go.setTextColor(subColor());
        go.setTextSize(22);
        row.addView(go, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
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

    // v13.77: Talks isTalkDownloaded, History-local — the dlMap
    // mapped file with the v13.66 length gate (a 0-byte file is not
    // downloaded), plus the plain basename leg for files the app
    // enqueued under a stripped name.
    private boolean histIsDownloaded(String url) {
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
    // like Talks' v5.82 rule. Remove-from-history drops the row's
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
        if (adapter != null && (!histPlayingUrl.equals(histRowPlayingUrl == null ? "" : histRowPlayingUrl)
                || !histPlayingLibUrl.equals(histRowPlayingLibUrl == null ? "" : histRowPlayingLibUrl)
                || !histPlayingLibPath.equals(histRowPlayingLibPath == null ? "" : histRowPlayingLibPath)
                || AudioService.liveEnded != histRowEnded
                || !hsFavNow77.equals(histFavSet))) {
            histRowPlayingUrl = histPlayingUrl;
            histRowPlayingLibUrl = histPlayingLibUrl;
            histRowPlayingLibPath = histPlayingLibPath;
            histRowEnded = AudioService.liveEnded;
            histFavSet = hsFavNow77;
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
