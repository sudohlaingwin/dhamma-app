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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

// v12.65: Personal Playlist (premium backlog #2, user order 2026-11-06):
// the user's own lists of favourite talks (seeded "မနက်" morning /
// "အိပ်ခါနီး" bedtime, plus any list they create) that auto-advance
// through the list. One activity, two modes: lists (tap a list to
// open it, long-press to delete it) and detail (tap a talk to play
// the list from there, ✕ per row to remove it, Play All from the
// top). Talk titles and sayadaw names are content and stay Burmese
// in every UI language, like everywhere else in the app.
//
// Playback rides the hand queue, not a new player: playFrom writes
// the list's v2 lines (from the tapped talk on) into the first
// talk's saved queue and opens its Room with autoUrl, so the
// tested advance (advanceQueueHead63 + playForeignHead11) walks
// the list across sayadaws, the Room's own play pops the head
// exactly like a queued talk, and repeat / shuffle / autoplay /
// the data-saver gate all behave exactly as they do for a queue.
// Playing a list REPLACES that sayadaw's hand queue (the "play
// this list now" semantic); the Room's queue drawer shows the
// remaining list talks and can still reorder/remove them.
public class PlaylistActivity extends Activity {
    private TextView headerTitle;
    private Button headerBtn;
    private Button playAllBtn;
    private ListView listView;
    private TextView emptyText;
    private String curList = null; // null = lists mode
    private java.util.List<String> names = new java.util.ArrayList<String>();
    private java.util.List<QueueEntry> items = new java.util.ArrayList<QueueEntry>();
    private BaseAdapter adapter;
    private boolean night;
    private java.util.Map<String, String[]> catCache;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playlist);
        night = Prefs.isNight(this);
        Fx.applySystemBars(this, night); // v13.07: bars follow the theme
        View root = findViewById(R.id.plRoot);
        if (root != null)
            root.setBackgroundResource(night ? R.drawable.bg_gradient_night
                    : R.drawable.bg_gradient_day);
        headerTitle = findViewById(R.id.plTitle);
        headerBtn = findViewById(R.id.plHeaderBtn);
        playAllBtn = findViewById(R.id.plPlayAll);
        listView = findViewById(R.id.plList);
        emptyText = findViewById(R.id.plEmpty);
        if (headerTitle != null)
            headerTitle.setTextColor(night ? 0xFFC9A227 : 0xFF7A2E1F);
        if (playAllBtn != null) {
            playAllBtn.setText(Lang.t(this, "pl_play_all"));
            playAllBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { playFrom(0); }
            });
        }
        if (headerBtn != null) {
            headerBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (curList != null) showLists();
                    else showNewListDialog();
                }
            });
        }
        adapter = new BaseAdapter() {
            @Override public int getCount() {
                return curList == null ? names.size() : items.size();
            }
            @Override public Object getItem(int p) {
                return curList == null ? names.get(p) : items.get(p);
            }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(final int position,
                    View convertView, android.view.ViewGroup parent) {
                return curList == null ? listRow(position) : itemRow(position);
            }
        };
        if (listView != null) {
            listView.setAdapter(adapter);
            listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                    if (curList == null) showDetail(names.get(pos));
                    else playFrom(pos);
                }
            });
            listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override public boolean onItemLongClick(AdapterView<?> p, View v,
                        int pos, long id) {
                    if (curList != null) return false;
                    confirmDeleteList(names.get(pos));
                    return true;
                }
            });
        }
        showLists();
    }

    @Override protected void onResume() {
        super.onResume();
        // Lists may have changed while the Room was open (a talk
        // added to a list there); re-read the store on return.
        if (curList == null) showLists();
        else showDetail(curList);
    }

    @Override public void onBackPressed() {
        if (curList != null) { showLists(); return; }
        super.onBackPressed();
    }

    private int titleColor() { return night ? 0xFFFFFFFF : 0xFF3B2314; }
    private int subColor() { return night ? 0xFFAAAAAA : 0xFF7A6A58; }

    private LinearLayout textRows(String title, String sub) {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * d);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(pad, (int) (10 * d), pad, (int) (10 * d));
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(titleColor());
        tv.setTextSize(16);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        if (sub != null && sub.length() > 0) {
            TextView sv = new TextView(this);
            sv.setText(sub);
            sv.setTextColor(subColor());
            sv.setTextSize(12);
            col.addView(sv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        return col;
    }

    private View listRow(final int position) {
        String name = names.get(position);
        int count = Prefs.playlistItems(this, name).size();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = textRows(name,
                count + " " + Lang.t(this, "pl_talks"));
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

    private View itemRow(final int position) {
        final QueueEntry qe = items.get(position);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String title = qe.title.length() > 0 ? qe.title : qe.url;
        LinearLayout col = textRows(title, qe.sayadaw);
        row.addView(col, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button del = new Button(this);
        del.setText("✕");
        del.setTextColor(0xFFFFFFFF);
        del.setBackgroundResource(R.drawable.glass_btn_dark);
        del.setMinWidth(0);
        del.setFocusable(false);
        del.setFocusableInTouchMode(false);
        del.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (curList == null) return;
                // v12.81: check the store result (sim1295 B) — a
                // failed remove used to drop the row anyway.
                if (!Prefs.playlistRemove(PlaylistActivity.this, curList, qe.url)) return;
                items.remove(position);
                adapter.notifyDataSetChanged();
                // v12.81: Play All follows the list (sim1295 A) —
                // an emptied list kept a dead Play All button.
                if (playAllBtn != null) playAllBtn.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
                refreshEmpty();
                Toast.makeText(PlaylistActivity.this,
                        Lang.t(PlaylistActivity.this, "pl_removed"),
                        Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(del, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void refreshEmpty() {
        if (emptyText == null) return;
        boolean empty = curList == null ? names.isEmpty() : items.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) emptyText.setText(Lang.t(this, "pl_empty"));
    }

    private void showLists() {
        curList = null;
        names = Prefs.playlistNames(this);
        if (headerTitle != null) headerTitle.setText(Lang.t(this, "pl_title"));
        if (headerBtn != null) headerBtn.setText(Lang.t(this, "pl_new"));
        if (playAllBtn != null) playAllBtn.setVisibility(View.GONE);
        if (adapter != null) adapter.notifyDataSetChanged();
        refreshEmpty();
    }

    private void showDetail(String name) {
        curList = name;
        items = Prefs.playlistItems(this, name);
        if (headerTitle != null) headerTitle.setText(name);
        if (headerBtn != null) headerBtn.setText(Lang.t(this, "back"));
        if (playAllBtn != null)
            playAllBtn.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        if (adapter != null) adapter.notifyDataSetChanged();
        refreshEmpty();
    }

    private void confirmDeleteList(final String name) {
        new AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "pl_delete_title"))
                .setMessage(name)
                .setPositiveButton(Lang.t(this, "pl_delete_btn"),
                        new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        Prefs.playlistDelete(PlaylistActivity.this, name);
                        showLists();
                        Toast.makeText(PlaylistActivity.this,
                                Lang.t(PlaylistActivity.this, "pl_deleted"),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(Lang.t(this, "btn_no_clear"), null)
                .show();
    }

    private void showNewListDialog() {
        final EditText et = new EditText(this);
        et.setHint(Lang.t(this, "pl_name_hint"));
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "pl_new"))
                .setView(et)
                .setPositiveButton(Lang.t(this, "pl_create"),
                        new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String name = et.getText().toString().trim();
                        if (name.length() == 0) {
                            Toast.makeText(PlaylistActivity.this,
                                    Lang.t(PlaylistActivity.this, "pl_name_hint"),
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (name.length() > 40) name = name.substring(0, 40);
                        // An existing name just opens that list —
                        // no error path for a repeat name.
                        if (Prefs.playlistCreate(PlaylistActivity.this, name)) {
                            showDetail(name);
                            return;
                        }
                        // v12.80: the create result is checked
                        // (sim1293 A) — at the 20-list cap a new
                        // name used to open a PHANTOM detail the
                        // store never held. A repeat name still
                        // opens the existing list; the cap toasts
                        // a limit message instead.
                        java.util.List<String> held =
                                Prefs.playlistNames(PlaylistActivity.this);
                        if (held.contains(name)) {
                            showDetail(name);
                        } else if (held.size() >= 20) {
                            // 20 = the store's 20-list cap.
                            Toast.makeText(PlaylistActivity.this,
                                    Lang.t(PlaylistActivity.this, "pl_limit"),
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(PlaylistActivity.this,
                                    Lang.t(PlaylistActivity.this, "pl_no_create"),
                                    Toast.LENGTH_SHORT).show();
                        }
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
    // stored in a list still opens its Room.
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

    // Play the list from idx: the remaining lines become the first
    // talk's saved queue (replacing its hand queue — "play this
    // list now"), and the Room opens on the first talk. The Room's
    // play pops the head; the service's advance walks the rest,
    // across sayadaws, exactly like a hand queue.
    private void playFrom(int idx) {
        if (curList == null) return;
        java.util.List<QueueEntry> all = Prefs.playlistItems(this, curList);
        if (idx < 0 || idx >= all.size()) return;
        // v12.79: skip LEADING items whose sayadaw has no catalog
        // page instead of refusing the whole list (sim1291 A3) —
        // one dead sayadaw used to strand every playable item
        // after it. The queue starts at the first playable item;
        // leading dead items never enter it. Only when NO item
        // from idx on resolves is the list refused.
        QueueEntry first = null;
        String[] cat = null;
        int start = idx;
        for (int i = idx; i < all.size(); i++) {
            String[] c = catalogFor(all.get(i).sayadaw);
            if (c != null && c[1] != null && c[1].length() > 0) {
                first = all.get(i); cat = c; start = i; break;
            }
        }
        if (first == null) {
            Toast.makeText(this, Lang.t(this, "pl_no_play"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.saveQueue(this, first.sayadaw,
                Prefs.playlistQueueLines(this, curList, start));
        Intent it = new Intent(this, RoomActivity.class);
        it.putExtra("name", first.sayadaw);
        it.putExtra("group", cat[0]);
        it.putExtra("url", cat[1]);
        it.putExtra("photo", cat[2]);
        it.putExtra("autoUrl", first.url);
        it.putExtra("autoTitle", first.title);
        // v12.79: marks a playlist-originated auto-play, so the
        // Room's load completion may fall through to the next
        // playable queued line when the first item went stale.
        it.putExtra("fromPlaylist", true);
        Fx.tap(this);
        Fx.go(this, it);
    }
}
