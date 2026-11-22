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
// openBmTalk1962 rule). List-only: no mini-player strip (a later
// piece, the History v13.54 precedent), no clear-all (a bulk
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
    private BaseAdapter adapter;
    private boolean night;
    private java.util.Map<String, String[]> catCache;
    // v15.62 (slice 2033): row-tap debounce — its own clock (the
    // chooser's 700ms rule, v12.28: an invalid tap never consumes
    // the window). A rapid double-tap must never stack two Rooms.
    private long lastBmTapMs = 0;

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
        }
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        java.util.Set<String> names = catalogNames();
        String sounding = (AudioService.liveSession && AudioService.liveHasTrack)
                ? AudioService.liveUrl : null;
        items = BmAll.pickAll(readBookmarks(), Prefs.statsRecent(this), names, sounding);
        if (adapter != null) adapter.notifyDataSetChanged();
        if (emptyText == null) return;
        boolean empty = items.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) emptyText.setText(Lang.t(this, "bm_all_empty"));
    }

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
        tv.setText(BmRail.fmtTime(r.posMs) + " \u00b7 " + e.title);
        tv.setTextColor(android.graphics.Color.parseColor("#7A6A58"));
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

    private String streamUrlFor(Prefs.HistEntry e) {
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
