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

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        night = Prefs.isNight(this);
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
    }

    @Override protected void onResume() {
        super.onResume();
        // Stats keep growing while the Room is open; re-read the
        // store on return so the cards are never stale.
        refresh();
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
                    org.json.JSONObject o = arr.getJSONObject(i);
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
            if (f.isFile()) {
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
}
