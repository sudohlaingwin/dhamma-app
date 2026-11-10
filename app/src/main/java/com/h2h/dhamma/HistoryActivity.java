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
        }
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
    }

    private int titleColor() { return night ? 0xFFFFFFFF : 0xFF3B2314; }
    private int subColor() { return night ? 0xFFAAAAAA : 0xFF7A6A58; }
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
        items = Prefs.statsRecent(this);
        if (totalValue != null) {
            totalValue.setText(fmtDur(total));
            totalValue.setTextColor(goldColor());
        }
        if (streakValue != null) {
            streakValue.setText(num(streak) + " " + Lang.t(this, "hs_days"));
            streakValue.setTextColor(goldColor());
        }
        if (topValue != null) {
            topValue.setText(top == null ? "—" : top[0]);
            topValue.setTextColor(goldColor());
        }
        if (topSub != null) {
            topSub.setText(top == null ? "" : fmtDur(Long.parseLong(top[1])));
            topSub.setTextColor(subColor());
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (emptyText == null) return;
        boolean empty = items.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) emptyText.setText(Lang.t(this, "hs_empty"));
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
        tv.setText(e.title.length() > 0 ? e.title : e.url);
        tv.setTextColor(titleColor());
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
        String line = QueueEntry.format(new QueueEntry(e.url, sd, e.title));
        if (line.length() > 0) lines.add(line);
        Prefs.saveQueue(this, sd, lines);
        Intent it = new Intent(this, RoomActivity.class);
        it.putExtra("name", sd);
        it.putExtra("group", cat[0]);
        it.putExtra("url", cat[1]);
        it.putExtra("photo", cat[2]);
        it.putExtra("autoUrl", e.url);
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
    private void syncHistoryStripPhoto(String name) {
        android.view.View pv = findViewById(R.id.nowStripPhoto);
        if (pv == null) return;
        if (name == null || name.length() == 0) {
            hsStripPhotoName = null;
            pv.setVisibility(android.view.View.GONE);
            return;
        }
        pv.setVisibility(android.view.View.VISIBLE);
        if (name.equals(hsStripPhotoName)) return;
        hsStripPhotoName = name;
        String url = AudioService.stripPhotoUrlForSayadaw(HistoryActivity.this, name);
        ImageLoader.load(HistoryActivity.this, url, name, (CircleImageView) pv);
    }

    private void syncHistoryStrip() {
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
            // empty (no duration survives a dead session).
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
                if (rSavedUrl.equals(rUrl)) rMatch = true;
                else {
                    try {
                        org.json.JSONObject map = new org.json.JSONObject(rsp.getString("dlMap", "{}"));
                        String fn = map.optString(rUrl, "");
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
                                if (u.equals(rSavedUrl) && fn.equals(map.optString(u, ""))) { lMatch = true; break; }
                            }
                        } catch (Exception ignored) {}
                    }
                }
                if (lTitle.length() == 0 || !lMatch) {
                    syncHistoryStripPhoto(null);
                    strip.setVisibility(android.view.View.GONE);
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
                // v8.52: Main's v7.94 resume mark, mirrored — the proven file is offline, like Main's lib branch.
                if (tx != null) tx.setText("↺ " + lTitle + (lFav ? " ★" : "") + (Fx.stripInProgressDlFilenames(HistoryActivity.this, rsp).contains(new java.io.File(lPath).getName()) ? " ⏳" : " ⬇"));
                // v5.46: a dead library strip names its recorded
                // sayadaw like the live strip since v5.45, in the
                // Room strip's "name · mm:ss မှ ဆက်နားထောင်ရန်"
                // shape; an unrecorded download keeps today's
                // generic line, never a guessed name.
                String libSd = Fx.stripLibSayadaw(HistoryActivity.this, rsp);
                if (sb != null) sb.setText(libSd != null ? libSd + " · " + lTime + Lang.t(HistoryActivity.this, "resume_from") : Lang.t(HistoryActivity.this, "downloaded_talk") + " · " + lTime + Lang.t(HistoryActivity.this, "resume_from"));
                if (tg != null) tg.setText("▶");
                if (nx != null) nx.setVisibility(android.view.View.GONE);
                // v7.31: Previous hides with Next in resume mode
                // (Main's mirror fix) — stepping back needs a
                // live track exactly like advancing does, and
                // this handler had no resume branch, so a ⏮ tap
                // on a ↺ strip ACTION_PREV-ed into a different
                // talk instead of resuming this one.
                android.view.View pv0 = findViewById(R.id.nowStripPrev);
                if (pv0 != null) pv0.setVisibility(android.view.View.GONE);
                android.widget.ProgressBar lPb = findViewById(R.id.nowStripProgress);
                if (lPb != null) lPb.setProgress(0);
                hsResumeStripMode = true;
                syncHistoryStripPhoto(libSd);
                strip.setVisibility(android.view.View.VISIBLE);
                return;
            }
            if (!"room".equals(rKind) || rName.length() == 0 || rTitle.length() == 0
                    || rPage.length() == 0 || rPos <= 5000 || !rMatch) {
                syncHistoryStripPhoto(null);
                strip.setVisibility(android.view.View.GONE);
                return;
            }
            boolean rFav = false;
            try {
                java.util.Set<String> ft = rsp.getStringSet("favTalks", null);
                if (ft != null) rFav = ft.contains(rUrl);
            } catch (Exception ignored) {}
            int rSec = rPos / 1000;
            String rTime = Fx.stripResumeTime(rSec);
            // v8.52: Main's v7.94 resume download mark, mirrored — a ↺ strip here never said whether resuming streams or plays offline.
            if (tx != null) tx.setText("↺ " + rTitle + (rFav ? " ★" : "") + Fx.stripResumeDownloadMark(HistoryActivity.this, rsp, rUrl));
            if (sb != null) sb.setText(rName + " · " + rTime + Lang.t(HistoryActivity.this, "resume_from"));
            if (tg != null) tg.setText("▶");
            if (nx != null) nx.setVisibility(android.view.View.GONE);
            // v7.31: same Previous hide as the library resume
            // branch above — a ↺ strip never shows ⏮.
            android.view.View pv0 = findViewById(R.id.nowStripPrev);
            if (pv0 != null) pv0.setVisibility(android.view.View.GONE);
            android.widget.ProgressBar rPb = findViewById(R.id.nowStripProgress);
            if (rPb != null) rPb.setProgress(0);
            hsResumeStripMode = true;
            syncHistoryStripPhoto(rName);
            strip.setVisibility(android.view.View.VISIBLE);
            return;
        }
        hsResumeStripMode = false;
        if (nx != null) nx.setVisibility(android.view.View.VISIBLE);
        // v7.31: Previous returns with Next on a live session.
        android.view.View pv0 = findViewById(R.id.nowStripPrev);
        if (pv0 != null) pv0.setVisibility(android.view.View.VISIBLE);
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
        if (text == null) { syncHistoryStripPhoto(null); strip.setVisibility(android.view.View.GONE); return; }
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
                                || (src != null && dlUrls != null && dlUrls.contains(src))) progN++;
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
        if (tx != null) tx.setText(text);
        // v5.32: live clock on the strip — sayadaw name + elapsed/total.
        android.widget.ProgressBar pb = findViewById(R.id.nowStripProgress);
        if (subText != null) subText += Fx.stripClockSuffixForStrip(pb, AudioService.livePositionMs, AudioService.liveDurationMs, Prefs.stripClockRemaining(HistoryActivity.this));
        if (sb != null && subText != null) sb.setText(subText);
        Fx.syncStripProgress(pb); // v7.93: skips while a mini-seek drag is in flight
        if (tg != null) tg.setText(AudioService.livePlaying ? "\u23F8\uFE0E" : "▶");
        // v6.01: live strip's portrait — Room names lastName, library
        // names its recorded sayadaw (Fx.stripLibSayadaw, null when
        // unrecorded); both resolve through syncHistoryStripPhoto's
        // exact-name catalog match, never a guessed face.
        syncHistoryStripPhoto("room".equals(kind) ? sp.getString("lastName", "")
                : ("lib".equals(kind) ? Fx.stripLibSayadaw(HistoryActivity.this, sp) : null));
        strip.setVisibility(android.view.View.VISIBLE);
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
