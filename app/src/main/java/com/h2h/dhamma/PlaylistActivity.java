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
    // v15.73 (slice 2098): "Shuffle play" on this screen — the chooser's v13.93 shuffle-play, now on the playlist detail screen: one tap shuffles the open list into the queue and opens the Room on the first shuffled talk. lastPlShuffleMs is the handler's own 700ms debounce clock (an invalid tap never consumes the window, v12.28); plShuffleBtn is the header button beside Play-all (GONE until showDetail shows it, riding the Play-all gate — the 2065 rule, so the two header verbs show and hide together).
    private Button plShuffleBtn;
    private long lastPlShuffleMs = 0;
    // v15.74 (slice 2103): "Jump to the playing talk" on this screen — the Talks chooser's v15.55 jump (slice 1994), now on the playlist detail screen, the last list surface without it: the detail rows carry the sounding gold mark since v13.55, but a listener who scrolled away from the marked row had no visible way back. plJumpBtn is the floating gold ▶ button (GONE until refreshPlJumpTarget + paintPlJumpBtn show it — the 1994 shape); plJumpTargetIdx is the cached shown-list index of the sounding talk (-1 in lists mode and while nothing live sounds in the open list); plJumpShown is the last-painted state for the painter's change gate. Jump to the playing talk landed in slice 2103.
    private android.widget.TextView plJumpBtn = null;
    private int plJumpTargetIdx = -1;
    private boolean plJumpShown = false;
    // v15.74 (slice 2104): "Back to top" on this screen — the Talks 1940 / Downloaded 2003 button, now on the last list surface without one: a long playlist's header is off-screen exactly when the jump is needed. plTopBtn is the floating gold ↑ button (GONE until ScrollTop.visible — the first visible row passes 8 — the 1940 shape, in lists mode and detail alike, since the rule is the list's own scroll, not the sounding row); plTopShown is the last-painted state for the painter's change gate. Back to top landed in slice 2104.
    private android.widget.TextView plTopBtn = null;
    private boolean plTopShown = false;
    private ListView listView;
    private TextView emptyText;
    private String curList = null; // null = lists mode
    private java.util.List<String> names = new java.util.ArrayList<String>();
    private java.util.List<QueueEntry> items = new java.util.ArrayList<QueueEntry>();
    private BaseAdapter adapter;
    private boolean night;
    private java.util.Map<String, String[]> catCache;
    // v13.53: real mini-player on this screen (the same strip as
    // Talks' v4.31) — the Playlist screen used to show nothing
    // about a playing list: Play All opened the Room and Back
    // returned here with no way to see, pause or jump back to
    // the talk. Same 1s-tick shape as Talks: stripHandler +
    // stripTick, plResumeStripMode (the ↺ resume branch), and
    // plStripPhotoName (the face the strip last showed).
    private android.os.Handler stripHandler = new android.os.Handler();
    private Runnable stripTick;
    private boolean plResumeStripMode = false;
    private String plStripPhotoName = null;
    private boolean lastPlStripPhotoRing = false;
    // v13.55: playing-row mark (Talks v4.89/v11.07, same rules) — the Playlist rows
    // carried no live marks: a listener browsing an open list could not spot the talk the strip is
    // naming without remembering its title. plPlayingUrl is the sounding Room-session URL (lastUrl);
    // plPlayingLibUrl is the sounding library talk via the dlMap reverse lookup (exactly-one
    // claimant, v8.44 — a dual-claimed file names no talk rather than the wrong one), so a list talk
    // sounding from the downloaded library reads as the playing row exactly like a Room row (v11.07).
    // plRowPlayingUrl / plRowPlayingLibUrl / plRowEnded are the last bound snapshots; the 1s tick
    // rebinds once when any of them changes.
    private String plPlayingUrl = "";
    private String plPlayingLibUrl = "";
    private String plRowPlayingUrl = null;
    private String plRowPlayingLibUrl = null;
    private boolean plRowEnded = false;
    // v14.06 (F4, sixth pass slice 5): rotation-restore track for
    // the open new-list / delete-confirm dialogs - the dialog refs
    // (+ the live EditText) let onSaveInstanceState read what is
    // open; the dismiss listeners clear them on a normal close.
    // curList itself is saved by key, like Settings' openPicker.
    private AlertDialog newListDialog;
    private EditText newListEdit;
    private AlertDialog deleteDialog;
    private String deleteListName;
    // v15.75 (slice 2111): rotation-restore track for the open
    // rename dialog — the same v14.06 shape as the delete
    // confirm (the dialog ref + the live EditText + the old
    // name let onSaveInstanceState read what is open; the
    // dismiss listener clears them on a normal close).
    private AlertDialog renameDialog;
    private EditText renameListEdit;
    private String renameListName;
    // v15.76 (slice 2114): rotation-restore track for the open
    // copy dialog — the same v14.06 shape as the rename dialog
    // (the dialog ref + the live EditText + the old name let
    // onSaveInstanceState read what is open; the dismiss
    // listener clears them on a normal close).
    private AlertDialog copyDialog;
    private EditText copyListEdit;
    private String copyListName;
    // v15.77 (slice 2120): sort on the lists screen — the button
    // and the persisted mode (the Bookmarks bmSortMode shape,
    // v15.65 slice 2051). The mode is read from Prefs in
    // wirePlSort(); the button label names the CURRENT order
    // (plSortLabel — the 2051 bm_sort_* keys, reused: creation
    // order reads as oldest first, newest first as latest
    // first, name order as sort_az), so the order reads in a
    // still. The button lives in lists mode only (GONE in detail
    // — the 2065 rule, so the header verbs show and hide
    // together: Play All + Shuffle in detail, Sort in lists).
    private android.widget.Button plSortBtn = null;
    private int plSortMode = 0;
    // v15.77 (slice 2121): Share playlist on the detail screen
    // — the button (the 2113 share verb's second surface: the
    // open list's own screen). The label is the reused
    // menu_pl_share key; the tap shares the open list
    // (sharePlaylist) — the 2113 method, re-read +
    // re-checked). GONE in lists mode (the 2065 rule — the
    // header verbs show and hide together: Play All + Shuffle
    // + Share in detail, Sort in lists).
    private android.widget.Button plShareBtn = null;
    // v15.77 (slice 2122): Copy playlist on the detail screen
    // — the button (the 2114 copy verb's second surface: the
    // open list's own screen, beside Share in the second
    // action row). The label is the reused menu_pl_copy key;
    // the tap opens the 2114 copy dialog for the open list.
    // GONE in lists mode (the 2065 rule — the header verbs
    // show and hide together: Play All + Shuffle in the first
    // row, Share + Copy in the second).
    private android.widget.Button plCopyBtn = null;
    // v15.77 (slice 2123): Rename playlist on the detail screen
    // — the button (the 2111 rename verb's second surface: the
    // open list's own screen, in the third action row — Share +
    // Copy + Rename would need ~385dp against a 328-361dp
    // budget, so Rename gets its own row). The label is the
    // reused menu_pl_rename key; the tap opens the 2111 rename
    // dialog for the open list — a successful rename stays on
    // the renamed list instead of dropping back to the lists
    // screen. GONE in lists mode (the 2065 rule — the header
    // verbs show and hide together: Play All + Shuffle in the
    // first row, Share + Copy in the second, Rename in the
    // third).
    private android.widget.Button plRenameBtn = null;
    // v15.77 (slice 2124): Delete playlist on the detail screen
    // — the button (the v14.06 delete verb's second surface: the
    // open list's own screen, beside Rename in the third action
    // row — Rename + Delete together need ~267dp against a
    // 328-361dp budget, so they share the row). The label is the
    // reused pl_delete_btn key; the tap opens the delete confirm
    // for the open list — confirming deletes it and drops back
    // to the lists screen (there is no list to stay on). GONE in
    // lists mode (the 2065 rule — the header verbs show and hide
    // together: Play All + Shuffle in the first row, Share +
    // Copy in the second, Rename + Delete in the third).
    private android.widget.Button plDeleteBtn = null;

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
        // v15.73 (slice 2098): wire the Shuffle button the same way — one tap shuffles the open list into the queue and opens the Room on the first shuffled talk (shufflePlayPl). The label is the pre-existing Lang "shuffle" key, so no new key is added. Shuffle play landed in slice 2098.
        plShuffleBtn = findViewById(R.id.plShuffle);
        if (plShuffleBtn != null) plShuffleBtn.setText(Lang.t(this, "shuffle"));
        if (plShuffleBtn != null) plShuffleBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shufflePlayPl(v); }
        });
        if (plShuffleBtn != null) Fx.press(plShuffleBtn);
        wirePlJumpBtn(); // v15.74 (slice 2103): jump to the playing talk on this screen, before the first refresh
        wirePlTopBtn(); // v15.74 (slice 2104): back to top on this screen, before the first refresh
        wirePlSort(); // v15.77 (slice 2120): sort on the lists screen, before the first refresh
        wirePlShare(); // v15.77 (slice 2121): share playlist on the detail screen, before the first refresh
        wirePlCopy(); // v15.77 (slice 2122): copy playlist on the detail screen, before the first refresh
        wirePlRename(); // v15.77 (slice 2123): rename playlist on the detail screen, before the first refresh
        wirePlDelete(); // v15.77 (slice 2124): delete playlist on the detail screen, before the first refresh
        if (headerBtn != null) {
            headerBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (curList != null) { plNav40 = true; showLists(); }
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
                    if (curList == null) { plNav40 = true; showDetail(names.get(pos)); }
                    else playFrom(pos);
                }
            });
            listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override public boolean onItemLongClick(AdapterView<?> p, View v,
                        int pos, long id) {
                    // v15.76 (slice 2114): lists rows gain a copy verb — the menu used to open with three verbs (Rename + Share + Delete), so a listener who built a morning list and wanted a bedtime variant had to re-add every talk by hand: Copy joins second (PlListMenu.menuKeys — the copy dialog is prefilled with the old name). Detail rows keep their row menu (2105). Copy playlist landed in slice 2114.
                    if (curList != null) { showPlRowMenu(items.get(pos), pos); return true; }
                    showPlListMenu(names.get(pos));
                    return true;
                }
            });
        }
        // v14.06 (F4, sixth pass slice 5): restore the rotation
        // state - the plain curList field used to die with the
        // recreate, and onCreate's bare showLists() dumped the
        // user from an open list back to the lists screen. A
        // restored list that no longer exists (deleted while the
        // activity was away) falls back to the lists screen; the
        // open dialogs come back too, same shape as
        // SettingsActivity's openPicker restore.
        String restoredList = savedInstanceState != null
                ? savedInstanceState.getString("curList") : null;
        if (restoredList != null
                && Prefs.playlistNames(this).contains(restoredList))
            showDetail(restoredList);
        else showLists();
        String restoredNew = savedInstanceState != null
                ? savedInstanceState.getString("newListText") : null;
        if (restoredNew != null) showNewListDialogWithText(restoredNew);
        String restoredDel = savedInstanceState != null
                ? savedInstanceState.getString("deleteList") : null;
        if (restoredDel != null
                && Prefs.playlistNames(this).contains(restoredDel))
            confirmDeleteList(restoredDel);
        String restoredRename = savedInstanceState != null
                ? savedInstanceState.getString("renameList") : null;
        String restoredRenameText = savedInstanceState != null
                ? savedInstanceState.getString("renameListText") : null;
        if (restoredRename != null
                && Prefs.playlistNames(this).contains(restoredRename))
            showRenameListDialogWithText(restoredRename, restoredRenameText);
        String restoredCopy = savedInstanceState != null
                ? savedInstanceState.getString("copyList") : null;
        String restoredCopyText = savedInstanceState != null
                ? savedInstanceState.getString("copyListText") : null;
        if (restoredCopy != null
                && Prefs.playlistNames(this).contains(restoredCopy))
            showCopyListDialogWithText(restoredCopy, restoredCopyText);
        wirePlaylistStrip(); // v13.53: real mini-player below the list
    }

    @Override protected void onResume() {
        super.onResume();
        // Lists may have changed while the Room was open (a talk
        // added to a list there); re-read the store on return.
        if (curList == null) showLists();
        else showDetail(curList);
        startPlaylistStripTick(); // v13.53: mini-player live state
    }

    @Override protected void onPause() {
        super.onPause();
        stopPlaylistStripTick(); // v13.53: never tick a detached strip
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        // v13.53: detach the liquid-glass surface (Talks v13.08) —
        // the drawable pins the dead view + its destroyed
        // activity otherwise.
        GlassBlur.detach(findViewById(R.id.nowStrip));
        // v15.40 (slice 1909): cancel in-flight header melts so a
        // destroy mid-melt never touches a detached header (the
        // update-gate dismissBlock / 1908 stats hygiene).
        Fx.cancelMeltText(headerTitle);
        Fx.cancelMeltText(headerBtn);
    }

    @Override public void onBackPressed() {
        if (curList != null) { plNav40 = true; showLists(); return; }
        super.onBackPressed();
    }

    // v14.06 (F4): rotation state - the open list and any open
    // dialog. onSaveInstanceState runs while the dialogs are
    // still alive, so the typed new-list name reads live; the
    // isShowing guards keep a normally-dismissed dialog from
    // being saved as open. HistoryActivity needs none of this:
    // it is stateless (refresh() in onCreate + onResume).
    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (curList != null) outState.putString("curList", curList);
        if (newListDialog != null && newListDialog.isShowing()
                && newListEdit != null)
            outState.putString("newListText",
                    newListEdit.getText().toString());
        if (deleteDialog != null && deleteDialog.isShowing()
                && deleteListName != null)
            outState.putString("deleteList", deleteListName);
        if (renameDialog != null && renameDialog.isShowing()
                && renameListName != null)
            outState.putString("renameList", renameListName);
        if (renameDialog != null && renameDialog.isShowing()
                && renameListEdit != null)
            outState.putString("renameListText",
                    renameListEdit.getText().toString());
        if (copyDialog != null && copyDialog.isShowing()
                && copyListName != null)
            outState.putString("copyList", copyListName);
        if (copyDialog != null && copyDialog.isShowing()
                && copyListEdit != null)
            outState.putString("copyListText",
                    copyListEdit.getText().toString());
    }

    private int titleColor() { return night ? 0xFFFFFFFF : 0xFF3B2314; }
    private int subColor() { return night ? 0xFFAAAAAA : 0xFF7A6A58; }
    private int goldColor() { return 0xFFC9A227; } // v13.55: playing-row gold, same as Talks/History

    private LinearLayout textRows(String title, String sub) {
        return textRows(title, sub, false);
    }

    // v13.55: gold variant for the playing row — the title TextView takes the gold colour; the sub
    // line is unchanged. The list rows (listRow) keep the plain variant.
    private LinearLayout textRows(String title, String sub, boolean gold) {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * d);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(pad, (int) (10 * d), pad, (int) (10 * d));
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(gold ? goldColor() : titleColor());
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
        // v13.55: the sounding row says so — "▶ " ahead of its title in gold (Talks v4.89/v11.07),
        // for a Room session (URL match) or a library session (dlMap exactly-one claimant); an ended
        // talk is not playing (v11.15 gate), so its mark lifts at once. Rows are built fresh per
        // getView, but both text and colour rebind here so neither can leak onto the next talk.
        boolean plRowPlaying = !AudioService.liveEnded
                && ((plPlayingUrl.length() > 0 && SiteHttp.sameTalk(qe.url, plPlayingUrl))
                        || (plPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(qe.url, plPlayingLibUrl)));
        LinearLayout col = textRows(plRowPlaying ? "▶ " + title : title, qe.sayadaw, plRowPlaying);
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
                removePlItem(position);
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

    // v15.40 (slice 1909): the Playlist lists<->detail navigation
    // moves instead of swapping in one frame — the last
    // un-choreographed navigation in the app. showLists() /
    // showDetail() used to rewrite the header title ("Playlist"
    // <-> list name), the header button ("New" <-> "Back"), the
    // Play All button (GONE <-> VISIBLE) and the whole row set in
    // a single frame, while every sibling navigation already
    // moves (drawer slide v3.36, grid pour v2.07, strip melts
    // 1907). meltHeader40 routes both header texts through the
    // shared Fx.meltText (dip 90ms, swap at the bottom, rise
    // 180ms, Decelerate); the first paint adopts (plHeaderKnown40,
    // the 1906 gate / 1908 stats idiom) so the header never melts
    // under its own entrance, and the onCreate->onResume double
    // call melts nothing (identical values no-op inside meltText).
    // Play All rides Fx.crossfadeStripBtn (the 1907 strip idiom:
    // recorded shown state, instant seed on a fresh view, 250ms
    // fade in/out, steady state a no-op). The list itself pours
    // only on a user navigation: the six nav sites arm plNav40
    // (row tap, back, header button, new-list create, new-list
    // existing-name, delete-list confirm); showLists() /
    // showDetail() consume it and Fx.fadeIn the ListView 250ms
    // (settled alpha still starts from 0, v3.52). onCreate
    // restore and onResume never arm it, so a return from the
    // Room never re-pours the list. Empty state untouched
    // (1891-closed family); strip untouched (1907).
    private boolean plHeaderKnown40 = false;
    private boolean plNav40 = false;
    private void meltHeader40(android.widget.TextView tv, String want) {
        if (tv == null || want == null) return;
        if (!plHeaderKnown40) { tv.setText(want); return; }
        Fx.meltText(tv, want);
    }
    private void pourList40() {
        boolean pour = plNav40;
        plNav40 = false;
        if (pour && listView != null) Fx.fadeIn(listView, 250);
    }

    private void showLists() {
        curList = null;
        // v15.77 (slice 2120): the lists read in the chosen
        // order — PlSort.sort on the store names (creation order
        // is the store order, the default; newest first reverses
        // it; name A-Z sorts it). The sort rides showLists, so
        // every lists paint (navigation, return from the Room,
        // a sort tap) re-orders in place with no extra store
        // read.
        names = PlSort.sort(Prefs.playlistNames(this), plSortMode);
        meltHeader40(headerTitle, Lang.t(this, "pl_title"));
        meltHeader40(headerBtn, Lang.t(this, "pl_new"));
        Fx.crossfadeStripBtn(playAllBtn, false);
        Fx.crossfadeStripBtn(plShuffleBtn, false);
        Fx.crossfadeStripBtn(plSortBtn, true);
        // v15.77 (slice 2121): the Share button lives in detail
        // mode only (the 2065 rule — the header verbs show and
        // hide together).
        Fx.crossfadeStripBtn(plShareBtn, false);
        // v15.77 (slice 2122): Copy lives in detail mode only
        // too (the same 2065 rule — the second action row shows
        // and hides with the first).
        Fx.crossfadeStripBtn(plCopyBtn, false);
        // v15.77 (slice 2123): Rename lives in detail mode only
        // too (the same 2065 rule — the third action row shows
        // and hides with the first two).
        Fx.crossfadeStripBtn(plRenameBtn, false);
        // v15.77 (slice 2124): Delete lives in detail mode only
        // too (the same 2065 rule — the third action row shows
        // and hides with the first two).
        Fx.crossfadeStripBtn(plDeleteBtn, false);
        // v15.74 (slice 2103): lists mode has no sounding row — clear the jump target and hide the button (the target reads -1 with curList null).
        refreshPlJumpTarget();
        paintPlJumpBtnFromList();
        // v15.74 (slice 2104): a return to the lists screen can land deep without a scroll event — repaint the top button from the new position too (the 2003 shape; the names list is a list too).
        paintPlTopBtnFromList();
        if (adapter != null) adapter.notifyDataSetChanged();
        refreshEmpty();
        plHeaderKnown40 = true;
        pourList40();
    }

    private void showDetail(String name) {
        curList = name;
        items = Prefs.playlistItems(this, name);
        meltHeader40(headerTitle, name);
        meltHeader40(headerBtn, Lang.t(this, "back"));
        Fx.crossfadeStripBtn(playAllBtn, !items.isEmpty());
        Fx.crossfadeStripBtn(plShuffleBtn, !items.isEmpty());
        // v15.77 (slice 2120): detail rows are talks, not lists
        // — the sort button lives in lists mode only (the 2065
        // rule, so the header verbs show and hide together).
        Fx.crossfadeStripBtn(plSortBtn, false);
        // v15.77 (slice 2121): Share shows only while the open
        // list can share (the PlShare gate — a real name + at
        // least one resolvable talk; an empty or unresolvable
        // list offers no Share, never a dead tap).
        Fx.crossfadeStripBtn(plShareBtn, PlShare.canShare(name, items, plDlMap()));
        // v15.77 (slice 2122): Copy shows for any open list
        // (no talk gate — copying an empty list is valid, and
        // the 2114 dialog itself guards a blank name and toasts
        // the failure legs, so the tap never dead-ends).
        Fx.crossfadeStripBtn(plCopyBtn, true);
        // v15.77 (slice 2123): Rename shows for any open list
        // (no talk gate — renaming an empty list is valid, and
        // the 2111 dialog itself guards a blank name and toasts
        // the failure legs, so the tap never dead-ends).
        Fx.crossfadeStripBtn(plRenameBtn, true);
        // v15.77 (slice 2124): Delete shows for any open list
        // (no talk gate — deleting an empty list is valid, and
        // the confirm dialog itself is the guard — Cancel is
        // btn_no_clear — so the tap never dead-ends).
        Fx.crossfadeStripBtn(plDeleteBtn, true);
        refreshPlaylistPlayingMarks(); // v13.55: mark right on first paint, not one tick late
        // v15.74 (slice 2103): the open list's sounding row is the jump target — refresh it and repaint the button from the new position (the 2063 shape: a fresh detail open can land with the playing row off-screen).
        refreshPlJumpTarget();
        paintPlJumpBtnFromList();
        // v15.74 (slice 2104): a fresh detail open can land deep without a scroll event — repaint the top button from the new position too (the 2003 shape).
        paintPlTopBtnFromList();
        if (adapter != null) adapter.notifyDataSetChanged();
        refreshEmpty();
        plHeaderKnown40 = true;
        pourList40();
    }

    // v15.76 (slice 2114): lists-mode long-press menu gains "Copy" — the dialog opens with the verbs the screen needs ("Rename" first, "Copy" second, "Share" third, "Delete" last — PlListMenu.menuKeys plus the PlShare offer, inserted at index 2 only while the list has a resolvable talk), titled with the list's own name. The menu is modal, so the long-press position stays valid at tap time. "Rename" opens the rename dialog (2111); "Copy" opens the copy dialog (2114); "Share" opens the Android share sheet with the whole list as one message (sharePlaylist); "Delete" keeps the existing delete confirm. A blank name names no list, so no menu opens on it; an empty or unresolvable list offers no Share. Copy playlist landed in slice 2114.
    private void showPlListMenu(final String name) {
        if (name == null || name.length() == 0) return;
        java.util.List<String> keys11 = PlListMenu.menuKeys(name);
        if (keys11.isEmpty()) return;
        java.util.List<QueueEntry> items11 = Prefs.playlistItems(this, name);
        java.util.Map<String, String> dlMap11 = plDlMap();
        if (PlShare.canShare(name, items11, dlMap11)) keys11.add(2, "menu_pl_share");
        java.util.ArrayList<String> opts11 = new java.util.ArrayList<String>();
        for (String k11 : keys11) opts11.add(Lang.t(this, k11));
        final String[] optArr11 = opts11.toArray(new String[0]);
        final String[] keyArr11 = keys11.toArray(new String[0]);
        new android.app.AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(optArr11, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if ("menu_pl_rename".equals(keyArr11[which])) {
                            showRenameListDialog(name);
                        } else if ("menu_pl_copy".equals(keyArr11[which])) {
                            showCopyListDialog(name);
                        } else if ("menu_pl_share".equals(keyArr11[which])) {
                            sharePlaylist(name);
                        } else if ("pl_delete_btn".equals(keyArr11[which])) {
                            confirmDeleteList(name);
                        }
                    }
                })
                .show();
    }

    // v15.76 (slice 2113): "Share playlist" — the whole list as one message through the Android sheet (the shareTalkBm shape: ACTION_SEND text/plain, the chooser titled with the menu_pl_share label itself): the items + dlMap are re-read fresh and the offer re-checked (PlShare.canShare — the offer and the tap can never disagree), so a list emptied while the menu sat open shares nothing instead of a bare header. The text is PlShare.shareText (the list name + one numbered talk per shareable line + the share_source credit). A share mutates nothing: no store write, no queue or resume change, no pause guard (no sheet is left to return from — the shareTalkBm precedent); the sounding row keeps it. Share playlist landed in slice 2113.
    private void sharePlaylist(final String name) {
        if (name == null || name.length() == 0) return;
        java.util.List<QueueEntry> items13 = Prefs.playlistItems(this, name);
        java.util.Map<String, String> dlMap13 = plDlMap();
        if (!PlShare.canShare(name, items13, dlMap13)) return;
        android.content.Intent send13 = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send13.setType("text/plain");
        send13.putExtra(android.content.Intent.EXTRA_TEXT,
                PlShare.shareText(name, items13, dlMap13,
                        Lang.t(PlaylistActivity.this, "share_source")));
        Fx.tap(PlaylistActivity.this);
        startActivity(android.content.Intent.createChooser(send13,
                Lang.t(PlaylistActivity.this, "menu_pl_share")));
    }

    // v15.76 (slice 2113): the dlMap as a plain map for the share resolve — the refreshPlaylistPlayingMarks shape (the "dhamma" prefs' dlMap JSON, keys = stream URLs, values = downloaded basenames), parsed fresh at each call so a download landing while the menu sits open can never be missed. Any failure reads as an empty map (no resolution, never a crash) — the pure core treats a null / empty map as no claimant.
    private java.util.Map<String, String> plDlMap() {
        java.util.Map<String, String> out = new java.util.HashMap<String, String>();
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            org.json.JSONObject map = new org.json.JSONObject(sp.getString("dlMap", "{}"));
            java.util.Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                out.put(k, map.optString(k, ""));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void showRenameListDialog(final String oldName) {
        showRenameListDialogWithText(oldName, oldName);
    }

    // v15.75 (slice 2111): rename dialog — the new-list dialog's shape (showNewListDialogWithText), prefilled with the current name instead of empty: the title and the positive button both carry the menu_pl_rename label, the hint is the same pl_name_hint, the cursor sits at the end of the old name (a rename starts from the old name, unlike a create), and the 40-char cap is the same leg (a longer name is cut, never refused). An empty name toasts the hint (the create flow's shape); the unchanged name and a name another list already holds both toast pl_exists (the Room new-list dialog's duplicate leg); a real rename rewrites the one LIST line in the store (the ITEM lines carry no list name, so the talks move with it) and repaints the lists — the renamed list shows in place, which is the confirmation, so no success toast. Rotation restore: the dialog + its EditText + the old name are tracked (the v14.06 shape), so a rotate re-shows the dialog with the typed text intact. Rename landed in slice 2111.
    private void showRenameListDialogWithText(final String oldName, String initial) {
        if (oldName == null || oldName.length() == 0) return;
        final EditText et = new EditText(this);
        et.setHint(Lang.t(this, "pl_name_hint"));
        et.setSingleLine(true);
        if (initial != null) et.setText(initial);
        if (initial != null) et.setSelection(initial.length());
        renameListEdit = et;
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "menu_pl_rename"))
                .setView(et)
                .setPositiveButton(Lang.t(this, "menu_pl_rename"),
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
                        if (name.equals(oldName)) {
                            Toast.makeText(PlaylistActivity.this,
                                    Lang.t(PlaylistActivity.this, "pl_exists"),
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (Prefs.playlistRename(PlaylistActivity.this, oldName, name)) {
                            plNav40 = true;
                            // v15.77 (slice 2123): renamed from the detail screen — stay on the renamed list (showDetail) instead of dropping back to the lists screen (showLists): the open list is still open under its new name, and the dialog's own guards (blank / unchanged / duplicate) already ran, so the tap never dead-ends. Renamed from the lists menu, curList is null and the old path stands.
                            if (curList != null && curList.equals(oldName)) showDetail(name);
                            else showLists();
                            return;
                        }
                        Toast.makeText(PlaylistActivity.this,
                                Lang.t(PlaylistActivity.this, "pl_exists"),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(Lang.t(this, "btn_no_clear"), null)
                .create();
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                renameDialog = null;
                renameListEdit = null;
                renameListName = null;
            }
        });
        renameListName = oldName;
        renameDialog = dlg;
        dlg.show();
    }

    private void showCopyListDialog(final String oldName) {
        showCopyListDialogWithText(oldName, oldName);
    }

    // v15.76 (slice 2114): copy dialog — the rename dialog's shape (showRenameListDialogWithText), prefilled with the current name instead of empty: the title and the positive button both carry the menu_pl_copy label, the hint is the same pl_name_hint, the cursor sits at the end of the old name (a copy starts from the old name, unlike a create), and the 40-char cap is the same leg (a longer name is cut, never refused). An empty name toasts the hint (the create flow's shape); the unchanged name and a name another list already holds both toast pl_exists (the Room new-list dialog's duplicate leg); at the 20-list cap the copy toasts pl_limit, and any other failure toasts pl_no_create (the create flow's shape). A real copy appends a new LIST block with the source block's ITEM lines verbatim (Prefs.playlistDuplicate) and repaints the lists — the copied list shows in place, which is the confirmation, so no success toast. Rotation restore: the dialog + its EditText + the old name are tracked (the v14.06 shape), so a rotate re-shows the dialog with the typed text intact. Copy playlist landed in slice 2114.
    private void showCopyListDialogWithText(final String oldName, String initial) {
        if (oldName == null || oldName.length() == 0) return;
        final EditText et = new EditText(this);
        et.setHint(Lang.t(this, "pl_name_hint"));
        et.setSingleLine(true);
        if (initial != null) et.setText(initial);
        if (initial != null) et.setSelection(initial.length());
        copyListEdit = et;
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "menu_pl_copy"))
                .setView(et)
                .setPositiveButton(Lang.t(this, "menu_pl_copy"),
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
                        if (name.equals(oldName)) {
                            Toast.makeText(PlaylistActivity.this,
                                    Lang.t(PlaylistActivity.this, "pl_exists"),
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (Prefs.playlistDuplicate(PlaylistActivity.this, oldName, name)) {
                            plNav40 = true;
                            showLists();
                            return;
                        }
                        java.util.List<String> held =
                                Prefs.playlistNames(PlaylistActivity.this);
                        if (held.contains(name)) {
                            Toast.makeText(PlaylistActivity.this,
                                    Lang.t(PlaylistActivity.this, "pl_exists"),
                                    Toast.LENGTH_SHORT).show();
                        } else if (held.size() >= 20) {
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
                .create();
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                copyDialog = null;
                copyListEdit = null;
                copyListName = null;
            }
        });
        copyListName = oldName;
        copyDialog = dlg;
        dlg.show();
    }

    private void confirmDeleteList(final String name) {
        // v14.06 (F4): track the open dialog so rotation re-shows
        // it instead of dropping the confirm - the name is saved
        // in onSaveInstanceState and re-shown from onCreate; the
        // dismiss listener clears the track so a normally-closed
        // dialog is never mistaken for an open one.
        deleteListName = name;
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "pl_delete_title"))
                .setMessage(name)
                .setPositiveButton(Lang.t(this, "pl_delete_btn"),
                        new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        Prefs.playlistDelete(PlaylistActivity.this, name);
                        plNav40 = true;
                        showLists();
                        Toast.makeText(PlaylistActivity.this,
                                Lang.t(PlaylistActivity.this, "pl_deleted"),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(Lang.t(this, "btn_no_clear"), null)
                .create();
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                deleteDialog = null;
                deleteListName = null;
            }
        });
        deleteDialog = dlg;
        dlg.show();
    }

    private void showNewListDialog() {
        showNewListDialogWithText(null);
    }

    // v14.06 (F4): the initial text is the rotation restore - a
    // typed-but-unsaved name survives the recreate instead of
    // being lost with the dialog. The dialog + its EditText are
    // tracked so onSaveInstanceState can read the live text; the
    // dismiss listener clears the track on a normal close.
    private void showNewListDialogWithText(String initial) {
        final EditText et = new EditText(this);
        et.setHint(Lang.t(this, "pl_name_hint"));
        et.setSingleLine(true);
        if (initial != null) et.setText(initial);
        newListEdit = et;
        AlertDialog dlg = new AlertDialog.Builder(this)
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
                            plNav40 = true;
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
                            plNav40 = true;
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
                .create();
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                newListDialog = null;
                newListEdit = null;
            }
        });
        newListDialog = dlg;
        dlg.show();
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
    // v15.73 (slice 2098): "Shuffle play" on the Playlist detail screen — the chooser's v13.93 shuffle-play, now on the playlist: the open list used to play in stored order only (Play All, playFrom); one tap here shuffles the OPEN list into the queue in random order (PlShuffle.shuffleEntries over the list — the 2065 rule: the shown run is the run) and opens the Room on the first shuffled talk at its saved spot (playFrom's own route — the Room resumes where the listener left off, v1.56). Same guards as the chooser: empty list refuses with the same words, the 700ms debounce has its own clock (an invalid tap never consumes the window, v12.28), the sounding talk is never re-queued (the stay-put branch: the rest queue behind it, no restart — this screen's TWO sounding identities, the v13.55 mark rule), and the count toast counts written lines only. The anchor sayadaw is the FIRST SHUFFLED entry's sayadaw, healed exactly like playFrom's first item (catalog + the sayadawForTalk heal); leading entries whose sayadaw has no catalog page are skipped, not refused (the v12.79 leg, over the shuffled order). No catalog, no run: the queue is untouched. The semantics are this screen's own Play All semantics, not the chooser's append: shuffle REPLACES the anchor sayadaw's hand queue with the shuffled run (playFrom's "play this list now" semantic), because a playlist tap always means "play this list now". When nothing is written (the only playable item already sounds) the toast is already_in_queue and the queue is left untouched — never cleared behind a sounding talk. The Room open is playFrom's own block verbatim (autoUrl canonicalised, fromPlaylist true), so the Room's load completion may fall through to the next playable queued line when the first item went stale (the v12.79 leg). The shuffled queue IS the shuffle — the global shuffle-continuous toggle is left alone, so a user who deliberately left it off is not surprised, and after the queue drains normal advance resumes.
    private void shufflePlayPl(android.view.View v) {
        if (items == null || items.isEmpty()) {
            android.widget.Toast.makeText(PlaylistActivity.this,
                    Lang.t(PlaylistActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        long now98 = android.os.SystemClock.uptimeMillis();
        if (now98 - lastPlShuffleMs < 700) return;
        lastPlShuffleMs = now98;
        refreshPlaylistPlayingMarks(); // tap-time sounding identities
        java.util.List<QueueEntry> shuffled98 = PlShuffle.shuffleEntries(items, new java.util.Random());
        if (shuffled98.isEmpty()) {
            android.widget.Toast.makeText(PlaylistActivity.this,
                    Lang.t(PlaylistActivity.this, "talks_none_for_sayadaw"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // The first playable shuffled entry — skip leading entries whose sayadaw has no catalog page (the v12.79 leg, over the shuffled order), healing the sayadaw exactly like the chooser's shuffle (catalog + the sayadawForTalk heal). No playable entry: the queue is untouched.
        QueueEntry first98 = null;
        String sd98 = null;
        String[] cat98 = null;
        int start98 = 0;
        for (int i98 = 0; i98 < shuffled98.size(); i98++) {
            QueueEntry qe98 = shuffled98.get(i98);
            if (qe98 == null || qe98.url == null || qe98.url.length() == 0) continue;
            String sdTry98 = qe98.sayadaw;
            String[] catTry98 = catalogFor(sdTry98);
            if (catTry98 == null || catTry98[1] == null || catTry98[1].length() == 0) {
                String owner98 = Prefs.sayadawForTalk(this, "lib", qe98.url, "");
                if (owner98 != null && owner98.length() > 0 && !owner98.equals(sdTry98)) {
                    sdTry98 = owner98;
                    catTry98 = catalogFor(sdTry98);
                }
            }
            if (catTry98 != null && catTry98[1] != null && catTry98[1].length() > 0) {
                first98 = qe98;
                sd98 = sdTry98;
                cat98 = catTry98;
                start98 = i98;
                break;
            }
        }
        if (first98 == null) {
            android.widget.Toast.makeText(PlaylistActivity.this,
                    Lang.t(PlaylistActivity.this, "pl_no_play"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        boolean firstSounds98 = (plPlayingUrl.length() > 0 && SiteHttp.sameTalk(first98.url, plPlayingUrl))
                || (plPlayingLibUrl.length() > 0 && SiteHttp.sameTalk(first98.url, plPlayingLibUrl));
        // The shuffled queue: the shuffled run from the first playable on, minus the sounding talk when it leads (the stay-put branch — the rest queue behind it, no restart). Saved lines are carried verbatim (histQueueAdd's discipline), so a foreign line the Room drawer moved mid-queue survives the write.
        java.util.ArrayList<String> lines98 = new java.util.ArrayList<String>();
        for (int i98 = start98; i98 < shuffled98.size(); i98++) {
            QueueEntry qe98 = shuffled98.get(i98);
            if (qe98 == null) continue;
            if (firstSounds98 && i98 == start98) continue;
            String ln98 = QueueEntry.format(qe98);
            if (ln98.length() > 0) lines98.add(ln98);
        }
        // Nothing written (the only playable item already sounds): the queue is left untouched — never cleared behind a sounding talk — and the toast says so.
        if (lines98.isEmpty()) {
            android.widget.Toast.makeText(PlaylistActivity.this,
                    Lang.t(PlaylistActivity.this, "already_in_queue"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.saveQueue(this, sd98, lines98);
        android.widget.Toast.makeText(PlaylistActivity.this,
                Lang.t(PlaylistActivity.this, "queue_plain_prefix") + lines98.size() + Lang.t(PlaylistActivity.this, "queue_added_suffix"),
                android.widget.Toast.LENGTH_SHORT).show();
        Fx.morph(v);
        if (!firstSounds98) {
            Intent it = new Intent(this, RoomActivity.class);
            it.putExtra("name", sd98);
            it.putExtra("group", cat98[0]);
            it.putExtra("url", cat98[1]);
            it.putExtra("photo", cat98[2]);
            // v13.65 (planters): a legacy http ITEM row must not plant an
            // http autoUrl — canonicalise at the source (the queue lines
            // stay as stored; advance / sounding match them by identity).
            it.putExtra("autoUrl", SiteHttp.identityUrl(first98.url));
            it.putExtra("autoTitle", first98.title);
            // v12.79: marks a playlist-originated auto-play, so the
            // Room's load completion may fall through to the next
            // playable queued line when the first item went stale.
            it.putExtra("fromPlaylist", true);
            Fx.tap(this);
            Fx.go(this, it);
        }
    }

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
        // v13.65 (planters): a legacy http ITEM row must not plant an
        // http autoUrl — canonicalise at the source (the queue lines
        // stay as stored; advance / sounding match them by identity).
        it.putExtra("autoUrl", SiteHttp.identityUrl(first.url));
        it.putExtra("autoTitle", first.title);
        // v12.79: marks a playlist-originated auto-play, so the
        // Room's load completion may fall through to the next
        // playable queued line when the first item went stale.
        it.putExtra("fromPlaylist", true);
        Fx.tap(this);
        Fx.go(this, it);
    }

    // v13.53: the mini-player — a faithful mirror of Talks'
    // strip (syncTalksStrip / wireNowStrip: v4.31 live strip,
    // v4.61/v4.63 resume strips, v5.32 live clock, v5.45 lib
    // sayadaw, v5.67 ⏳ count, v4.68 ◈ count, v10.40
    // bound-trackless leg, v10.94 glyph colours, v13.03
    // liquid glass), minus the chooser-only head (row-mark
    // rebind, filter rebuild, header refresh — Playlist rows
    // carry no live marks). Live session: title + ★/😴/⏳/◈/
    // ended marks, sayadaw + clock sub line, seekable progress,
    // ⏸/▶ glyph, portrait. Dead session with a saved spot
    // (>5s): the ↺ resume strip (Room talk or downloaded
    // file); anything else: hidden, so the lists stay
    // list-only. Scope note: the strip is the piece — the
    // rows themselves gain no playing mark this run.
    // v13.55: sounding identities for the row mark — Talks' refreshQueueMarks playing half, minus the
    // queue cache (Playlist has no queue marks). Room: liveSession + liveHasTrack (v10.43 bound-trackless
    // stop: a stopped talk is never the playing talk) + lastKind room -> lastUrl. Library: same live
    // guards + lastKind lib -> lastLibPath, resolved to its stream URL through the dlMap reverse lookup
    // (exactly-one claimant, v8.44). Prefs only, never per bind.
    // v15.74 (slice 2103): wire the jump-to-playing button — one tap glides the open list to the sounding talk (the 1994 Talks shape: the tap re-resolves the target fresh, so a finish or an advance while the button sat visible can never glide to a stale row; the target is by construction in the shown list, so no search/filter clear is ever needed — this screen has no search). The tap gates on a live session with a track that has not ended (the v10.43 bound-trackless stop + the v11.15 ended gate, the History 2063 gate verbatim); a miss toasts no_talk_playing, never glides nowhere. A missing button simply never wires; a missing list never scrolls.
    // v15.77 (slice 2120): sort on the lists screen — the
    // Downloaded sort-button shape (v1.47 label + v2.90 premium
    // label dip): the button names the CURRENT order
    // (plSortLabel), a tap morphs the button, advances the mode
    // (created -> newest -> name), persists it, and dips the
    // header row while the label swaps (Fx.themeDip on
    // plHeaderRow, the dlBtnRow shape — the dip runs on the row,
    // never on the tapped button, so its alpha channel never
    // cancels the morph's scale channel; themeDip null-guards,
    // so a missing row still swaps the label). The sort re-runs
    // showLists() at once (it only reads plSortMode, like the
    // Settings syncLiveService pattern), so the lists re-order
    // in place with no re-read of the store beyond the names
    // showLists already reads. The mode survives rotation
    // through Prefs, like the Downloaded sort (v1.47).
    private void wirePlSort() {
        plSortBtn = findViewById(R.id.btnPlSort);
        plSortMode = Prefs.plSortMode(this);
        if (plSortBtn != null) plSortBtn.setText(plSortLabel());
        if (plSortBtn != null) plSortBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.morph(v);
                plSortMode = (plSortMode + 1) % 3;
                Prefs.setPlSortMode(PlaylistActivity.this, plSortMode);
                Fx.themeDip(findViewById(R.id.plHeaderRow), new Runnable() { @Override public void run() { if (plSortBtn != null) plSortBtn.setText(plSortLabel()); } });
                showLists();
            }
        });
    }

    // v15.77 (slice 2120): the sort button's label names the
    // CURRENT order (the 2051 bmSortLabel shape): creation order
    // reads as oldest first (the store order IS oldest-created
    // first — the seeded lists, then each created list), newest
    // first as latest first, name order as A-Z. The 2051
    // bm_sort_* keys name the ORDER, not the screen (Favourites
    // reuses them verbatim), so no new key is added; sort_az is
    // the Downloaded A-Z label (v9.29).
    private String plSortLabel() {
        if (plSortMode == 1) return Lang.t(PlaylistActivity.this, "bm_sort_recent");
        if (plSortMode == 2) return Lang.t(PlaylistActivity.this, "sort_az");
        return Lang.t(PlaylistActivity.this, "bm_sort_oldest");
    }

    // v15.77 (slice 2121): Share playlist on the detail screen
    // — the Shuffle button's shape (the 2098 wiring: label from
    // Lang in code, Fx.press on the button, one tap does the
    // verb): the label is the reused menu_pl_share key (the
    // 2113 lists-screen label — the same verb, the same words),
    // the tap shares the OPEN list (sharePlaylist) —
    // the 2113 method re-reads items + dlMap fresh and
    // re-checks the gate, so the offer and the tap can never
    // disagree). No new key, no new store write, no queue or
    // resume change (the shareTalkBm precedent — the sounding
    // row keeps it).
    private void wirePlShare() {
        plShareBtn = findViewById(R.id.btnPlShare);
        if (plShareBtn != null) plShareBtn.setText(Lang.t(this, "menu_pl_share"));
        if (plShareBtn != null) plShareBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sharePlaylist(curList);
            }
        });
        if (plShareBtn != null) Fx.press(plShareBtn);
    }

    // v15.77 (slice 2122): Copy playlist on the detail screen
    // — the Share button's shape (the 2121 wiring: label from
    // Lang in code, Fx.press on the button, one tap does the
    // verb): the label is the reused menu_pl_copy key (the
    // 2114 lists-screen label — the same verb, the same words),
    // the tap opens the 2114 copy dialog for the OPEN list
    // (showCopyListDialog) — the dialog is prefilled with the
    // open list's name and its success path repaints the lists,
    // so the copied list shows in place, which is the
    // confirmation. No new key, no new store write, no queue
    // or resume change. No removePlItem repaint: unlike Share,
    // Copy's offer does not depend on the talks (an empty list
    // copies), so removing the last talk never strands it.
    private void wirePlCopy() {
        plCopyBtn = findViewById(R.id.btnPlCopy);
        if (plCopyBtn != null) plCopyBtn.setText(Lang.t(this, "menu_pl_copy"));
        if (plCopyBtn != null) plCopyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showCopyListDialog(curList);
            }
        });
        if (plCopyBtn != null) Fx.press(plCopyBtn);
    }

    private void wirePlJumpBtn() {
        plJumpBtn = findViewById(R.id.plJumpBtn);
        if (plJumpBtn != null) plJumpBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // v10.43: bound-trackless stop — liveSession stays true but the mirror is false, so toast instead of gliding to a stopped talk; the v11.15 gate: an ended talk is not playing either (its mark lifts, so there is no row to jump to).
                if (listView == null || curList == null || items.isEmpty() || !AudioService.liveSession || !AudioService.liveHasTrack || AudioService.liveEnded) {
                    android.widget.Toast.makeText(PlaylistActivity.this, Lang.t(PlaylistActivity.this, "no_talk_playing"), android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    refreshPlaylistPlayingMarks();
                    int target = PlJumpPlaying.findShownIndex(items, plPlayingUrl, plPlayingLibUrl, AudioService.liveEnded);
                    if (target < 0) {
                        android.widget.Toast.makeText(PlaylistActivity.this, Lang.t(PlaylistActivity.this, "no_talk_playing"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Fx.tap(PlaylistActivity.this);
                    Fx.morph(v);
                    listView.smoothScrollToPosition(target);
                } catch (Exception ignored) {}
            }
        });
        if (listView != null) listView.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int state) {}
            @Override public void onScroll(android.widget.AbsListView v, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                // v15.74 (slice 2104): the top button paints on the same scroll (the 2003 shape — its only show rule is the first visible row, so no laid-out-window guard; the threshold keeps it hidden until row 8 anyway).
                paintPlTopBtn(firstVisibleItem);
                // v15.74 (slice 2103): the jump button paints on the same scroll — off-screen is its only show rule, so a laid-out window is required (count 0 means no layout yet; the 1994 Talks shape).
                if (visibleItemCount > 0) paintPlJumpBtn(firstVisibleItem, firstVisibleItem + visibleItemCount - 1);
            }
        });
    }

    // v15.74 (slice 2103): refresh the jump button's cached target — the shown-list index of the sounding talk, from the tick-fresh sounding identities (refreshPlaylistPlayingMarks runs first on every path that calls this: showDetail before its repaint, the strip-tick change branch, the tap's own re-resolution). Runs on detail-open / sounding change only, never on scroll; -1 in lists mode (curList null — the names list has no sounding row) and while nothing live sounds among the shown rows.
    private void refreshPlJumpTarget() {
        plJumpTargetIdx = curList == null ? -1 : PlJumpPlaying.findShownIndex(items, plPlayingUrl, plPlayingLibUrl, AudioService.liveEnded);
    }

    // v15.74 (slice 2103): the jump-to-playing state — shown only while a live talk has a cached target in the open list AND that row is off-screen (above or below the visible window, the JumpPlaying.wantShown rule, the 1994 shape). The change gate keeps steady scrolls and the detail-open / sounding-change repaints from re-touching the button; the show/hide melts (Fx.fadeIn/fadeOut, 180ms) instead of snapping, and a cancelled fade never strands it half-transparent (the v2.62 gen guard, the 1940 shape). A stop clears the identities on the next tick, so the button hides within a tick of the session ending.
    private void paintPlJumpBtn(int firstVisible, int lastVisible) {
        if (plJumpBtn == null) return;
        boolean want = curList != null && plJumpTargetIdx >= 0
                && JumpPlaying.wantShown(plJumpTargetIdx, firstVisible, lastVisible);
        if (want == plJumpShown) return;
        plJumpShown = want;
        if (want) {
            plJumpBtn.setVisibility(android.view.View.VISIBLE);
            Fx.fadeIn(plJumpBtn, 180);
        } else {
            Fx.fadeOut(plJumpBtn, 180);
        }
    }

    // v15.74 (slice 2103): repaint from the list's own position after a scroll-less change — a detail open, a return to the lists screen, or a sounding change can move the playing row off-screen with no scroll event, so read the position after layout and paint from it. Posted, because the first / last visible rows are only valid once the list has laid out (the 1940 shape).
    private void paintPlJumpBtnFromList() {
        if (listView == null) return;
        listView.post(new Runnable() {
            @Override public void run() {
                if (listView == null) return;
                paintPlJumpBtn(listView.getFirstVisiblePosition(), listView.getLastVisiblePosition());
            }
        });
    }

    // v15.74 (slice 2104): wire the back-to-top button — one tap glides the open list to the top (the Talks 1940 / Downloaded 2003 shape: the tap needs no gate — the button only shows past row 8, so a tap always has somewhere to go; this screen has no search field, so no keyboard hides with the glide, the 1940 Talks tap shape). A missing button simply never wires; a missing list never scrolls.
    private void wirePlTopBtn() {
        plTopBtn = findViewById(R.id.plTopBtn);
        if (plTopBtn != null) plTopBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(PlaylistActivity.this);
                Fx.morph(v);
                if (listView != null) listView.smoothScrollToPosition(0);
            }
        });
    }

    // v15.74 (slice 2104): the back-to-top state — shown once the first visible row passes ScrollTop's threshold, hidden at the top (the 1940 Talks / 2003 Downloaded shape verbatim: ScrollTop.visible, THRESHOLD 8 — about one screen of rows). No curList gate: the rule is the shown list's own scroll, so the names list in lists mode grows one too once it passes eight rows. The change gate keeps steady scrolls and the detail-open / lists-return repaints from re-touching the button; the show/hide melts (Fx.fadeIn/fadeOut, 180ms) instead of snapping, and a cancelled fade never strands it half-transparent (the v2.62 gen guard, the 1940 shape).
    private void paintPlTopBtn(int firstVisible) {
        if (plTopBtn == null) return;
        boolean want = ScrollTop.visible(firstVisible);
        if (want == plTopShown) return;
        plTopShown = want;
        if (want) {
            plTopBtn.setVisibility(android.view.View.VISIBLE);
            Fx.fadeIn(plTopBtn, 180);
        } else {
            Fx.fadeOut(plTopBtn, 180);
        }
    }

    // v15.74 (slice 2104): repaint from the list's own position after a scroll-less change — a detail open or a return to the lists screen can land the list deep without a scroll event, so read the position after layout and paint from it. Posted, because the first visible row is only valid once the list has laid out (the 1940 shape). The rotation restore needs no separate hook: onCreate restores through showDetail/showLists, whose tails already post this repaint.
    private void paintPlTopBtnFromList() {
        if (listView == null) return;
        listView.post(new Runnable() {
            @Override public void run() {
                if (listView == null) return;
                paintPlTopBtn(listView.getFirstVisiblePosition());
            }
        });
    }

    // v15.77 (slice 2123): Rename playlist on the detail screen
    // — the Copy button's shape (the 2122 wiring: label from
    // Lang in code, Fx.press on the button, one tap does the
    // verb): the label is the reused menu_pl_rename key (the
    // 2111 lists-screen label — the same verb, the same words),
    // the tap opens the 2111 rename dialog for the OPEN list
    // (showRenameListDialog) — prefilled with the open list's
    // name; a successful rename stays on the renamed list (the
    // in-place branch in the dialog's success path), so the
    // renamed list shows in place, which is the confirmation.
    // No new key, no new store write, no queue or resume
    // change. No removePlItem repaint: unlike Share, Rename's
    // offer does not depend on the talks (an empty list
    // renames), so removing the last talk never strands it.
    // Defined after wirePlJumpBtn / wirePlTopBtn on purpose:
    // a method inserted between wirePlShare and wirePlJumpBtn
    // pollutes sim2120 D5 / sim2121 D3 / verify L5 (the 2122
    // note), so this wire lands after the jump/top cluster.
    private void wirePlRename() {
        plRenameBtn = findViewById(R.id.btnPlRename);
        if (plRenameBtn != null) plRenameBtn.setText(Lang.t(this, "menu_pl_rename"));
        if (plRenameBtn != null) plRenameBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showRenameListDialog(curList);
            }
        });
        if (plRenameBtn != null) Fx.press(plRenameBtn);
    }

    // v15.77 (slice 2124): Delete playlist on the detail screen
    // — the Rename button's shape (the 2123 wiring: label from
    // Lang in code, Fx.press on the button, one tap does the
    // verb): the label is the reused pl_delete_btn key (the
    // lists-menu label — the same verb, the same words), the tap
    // opens the v14.06 delete confirm for the OPEN list
    // (confirmDeleteList) — confirming deletes it and drops back
    // to the lists screen (there is no list to stay on, so the
    // dialog's existing path stands, byte-for-byte — no
    // in-place branch). No new key, no new store write, no
    // queue or resume change. No removePlItem repaint: unlike
    // Share, Delete's offer does not depend on the talks (an
    // empty list deletes), so removing the last talk never
    // strands it. Defined after wirePlRename on purpose: a
    // method inserted between wirePlShare and wirePlJumpBtn
    // pollutes sim2120 D5 / sim2121 D3 / verify L5 (the 2122
    // note), so this wire lands after the jump/top cluster,
    // beside its Rename sibling.
    private void wirePlDelete() {
        plDeleteBtn = findViewById(R.id.btnPlDelete);
        if (plDeleteBtn != null) plDeleteBtn.setText(Lang.t(this, "pl_delete_btn"));
        if (plDeleteBtn != null) plDeleteBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmDeleteList(curList);
            }
        });
        if (plDeleteBtn != null) Fx.press(plDeleteBtn);
    }

    // v15.76 (slice 2116): the stream URL a detail row shares / copies — the row's own URL when it is a stream URL; a downloaded (path-form) row resolves through the dlMap reverse lookup (the refreshPlaylistPlayingMarks exactly-one-claimant shape: the basename of the path claims exactly one stream URL, else null — a path whose basename no stream URL claims shares nothing). Null when the row names no stream URL.
    private String plShareUrlFor(QueueEntry qe) {
        if (qe == null || qe.url == null || qe.url.length() == 0) return null;
        if (!qe.url.startsWith("/")) return qe.url;
        String base = qe.url.substring(qe.url.lastIndexOf('/') + 1);
        if (base.length() == 0) return null;
        java.util.Map<String, String> m = plDlMap();
        String hit = null;
        int matches = 0;
        for (java.util.Map.Entry<String, String> e : m.entrySet()) {
            if (base.equals(e.getValue())) { hit = e.getKey(); matches++; }
        }
        return matches == 1 ? hit : null;
    }

    // v15.76 (slice 2116): the downloaded file name a detail row shares — a downloaded (path-form) row's own basename; a stream row's dlMap name. Only when the file is really on disk (TalkFileShare.resolve — the canonical-path containment + isFile gate), so the menu never offers a file that is gone; the tap-time shareUriFor re-checks, so a file that vanished while the menu sat open toasts instead of a guessed URI. Null when nothing is downloadable.
    private String plShareFileFor(QueueEntry qe) {
        if (qe == null || qe.url == null || qe.url.length() == 0) return null;
        String name;
        if (qe.url.startsWith("/")) name = qe.url.substring(qe.url.lastIndexOf('/') + 1);
        else name = plDlMap().get(qe.url);
        if (name == null || name.length() == 0) return null;
        java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma");
        return TalkFileShare.resolve(dir, name) != null ? name : null;
    }

    // v15.76 (slice 2117): the sayadaw's saved-queue membership, read straight from the store (no in-memory queue on this screen) — the History v13.77 shape: a v2 line and a bare v1 line both parse through QueueEntry.
    private boolean plQueueHas(String sd, String url) {
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

    // v15.76 (slice 2117): append the talk as a v2 line (identity URL, sayadaw, title) and save the queue back — the History v13.77 shape: every other line is carried verbatim, so foreign lines are never re-spelled or dropped here.
    private boolean plQueueAdd(String sd, String url, String title) {
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

    // v15.76 (slice 2117): drop the talk from the sayadaw's saved queue — the History v13.77 shape: every other line is carried verbatim, so foreign lines survive the write.
    private boolean plQueueRemove(String sd, String url) {
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

    // v15.80 (slice 2142): "Mark as listened / Unmark as listened" — the mark verb the Playlist detail row menu lacked that the Downloaded library row menu has had since v15.47 (slice 1949), the Talks chooser since v15.51 (slice 1969), the Room drawer dialogs since v15.52 (slices 1978/1979), the New talks row menu since v15.80 (slice 2138), the History row menu since v15.80 (slice 2139), the Favourites row menu since v15.80 (slice 2140) and the Bookmarks row menu since v15.80 (slice 2141): a playlist talk could only earn or lose its listened mark by playing it to the end or opening the library — and a Playlist detail row is exactly where both legs are live, because playedTalks is banked only on natural finish (AudioService.markPlayedForCurrent) and playlist membership is independent of it: a playlist talk stopped early sits unmarked (offers Mark), a finished one sits marked (offers Unmark — it returns to the New talks list). Same verb through the same rules, so the surfaces can never disagree: the 1949 pure core (DlMark — canOffer / labelKey / isMarked / mark / unmark, inputs never mutated), the v11.14 tap-time sounding re-check over this screen's PlRowMenu sounding rule (the queue branches' rule), the prefs-fresh playedTalks read at tap time, and the mark landing in the SAME playedTalks key AudioService banks on finish and the library, the rails, the header new-count and the filters all read. The helper re-resolves the row to its stream URL at tap time (plShareUrlFor — the Playlist-local shape, the offer and the tap can never disagree). No refresh and no repaint on this screen: the row renders no listened tick (only the sounding ▶ prefix in gold), so nothing here rebinds from playedTalks — the visible effect is the menu label flipping mark<->unmark on the next open, the verb toast, and the propagation (New talks list + header new-count + chooser tick). Neither the history REC line, the lifetime totals, the queue, the shelf order, the playlist membership itself nor any saved resume position is mutated.
    private void markListenedPl(final QueueEntry qe, final boolean unmark) {
        if (qe == null || qe.url == null || qe.url.length() == 0) return;
        final String streamUrl = plShareUrlFor(qe);
        if (streamUrl == null || streamUrl.length() == 0) {
            android.widget.Toast.makeText(PlaylistActivity.this,
                    Lang.t(PlaylistActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // Tap-time sounding re-check (Talks v11.14): the dialog can sit open across an auto-advance, so this talk can BE sounding by tap time — it earns its mark by finishing, never by this verb. This screen's PlRowMenu sounding rule, the queue branches' rule.
        refreshPlaylistPlayingMarks();
        if (PlRowMenu.isSounding(streamUrl, plPlayingUrl, plPlayingLibUrl, AudioService.liveEnded)) {
            android.widget.Toast.makeText(PlaylistActivity.this,
                    Lang.t(PlaylistActivity.this, "queue_changed"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        DlMark.Result res42 = null;
        try {
            android.content.SharedPreferences sp42 = getSharedPreferences("dhamma", MODE_PRIVATE);
            java.util.Set<String> cur42 = sp42.getStringSet("playedTalks", new java.util.HashSet<String>());
            res42 = unmark ? DlMark.unmark(cur42, SiteHttp.identityUrl(streamUrl), streamUrl)
                    : DlMark.mark(cur42, SiteHttp.identityUrl(streamUrl));
            sp42.edit().putStringSet("playedTalks", res42.set).apply();
        } catch (Exception ignored) {}
        android.widget.Toast.makeText(PlaylistActivity.this,
                Lang.t(PlaylistActivity.this, (res42 != null && res42.changed) ? (unmark ? "dl_menu_unmark_one" : "dl_menu_mark_one") : (unmark ? "nothing_to_unmark" : "already_listened")),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private java.util.Set<String> readPlayedTalksPl() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        try {
            java.util.Set<String> raw = getSharedPreferences("dhamma", MODE_PRIVATE).getStringSet("playedTalks", null);
            if (raw != null) out.addAll(raw);
        } catch (Exception ignored) {}
        return out;
    }

    // v15.76 (slice 2116): row long-press menu gains the share trio — the 2105 shell opened with "Play from here" + "Remove from list" and left the menu OPEN for the share / copy / file / go-to-sayadaw / queue verbs (one per slice, the 2073-2091 pattern); this slice lands the trio History carries (menu_share + dl_menu_copy_link, slice 1981, + dl_menu_share_file, slice 1982) through the same pure cores (RoomCopyLink + TalkFileShare), so the detail rows can never disagree with History about what shares, copies or sends. The trio sits between play-from-here and remove (constructive first, destructive last — the 2114 lists-menu order): the stream URL is plShareUrlFor (the row's own URL, or the dlMap reverse lookup for a downloaded row — a row with no resolvable stream URL offers neither share nor copy), the file name is plShareFileFor (the dlMap name for a stream row, the basename for a downloaded row, only when the file is on disk). The sounding row keeps the trio (share/copy/file touch neither playback nor the queue, like History's). The tap-time gates re-check the offer (RoomCopyLink.canOffer / TalkFileShare.shareUriFor), so a file that vanished while the menu sat open toasts talk_no_longer_in_list instead of a guessed URI. A share/copy mutates nothing — the list, the queue and the lifetime totals stay. No Fx.tap in the three branches (History's row-menu precedent — the 2113 lists-menu share taps, the row-menu shares do not). Row menu trio landed in slice 2116.
    // v15.76 (slice 2117): row long-press menu gains go-to-sayadaw + queue — the 2105 shell opened with "Play from here" + "Remove from list" and left the menu OPEN for the share / copy / file / go-to-sayadaw / queue verbs (one per slice, the 2073-2091 pattern); the 2116 trio landed first, and this slice lands the last two History verbs the detail rows lacked: "Go to sayadaw" (menu_go_sayadaw, slice 1995, through HistGo + GoSayadaw — the row's sayadaw resolved through this screen's own catalogFor, cached per process with alias healing, so a pre-rename name still opens its Talks list) and the queue toggle (menu_queue_add / menu_queue_remove, v13.77, through plQueueHas/plQueueAdd/plQueueRemove — the History shapes, v2 lines carried verbatim). Both sit after the trio and before remove (constructive first, destructive last — the 2114 lists-menu order): the go verb is offered sounding or not (navigation never touches playback, History's rule); the queue verb only when the talk can queue (a resolved stream URL with a sayadaw, not sounding — the Talks v5.82 gate). The tap-time gates re-check the offer (catalogFor re-resolve + GoSayadaw.canGo, so a stale entry toasts no_sayadaw_page; the sounding re-check, so a talk that started sounding while the menu sat open toasts queue_changed instead of queueing a replay). A go mutates nothing; a queue add/remove writes only that sayadaw's saved queue — the list, the lifetime totals and the other sayadaws' queues stay. Fx.tap + Fx.go on the go branch (History's 1995 shape). Row menu go + queue landed in slice 2117; the menu stays OPEN for playlist-add (piece 5/5).
    // v15.76 (slice 2118): row long-press menu gains "Add to playlist" — the 2105 shell opened with "Play from here" + "Remove from list" and left the menu OPEN for the share / copy / file / go-to-sayadaw / queue / playlist-add verbs (one per slice, the 2073-2091 pattern); the 2116 trio landed first, then go + queue in 2117, and this slice lands the last one the detail rows lacked: "Add to playlist" (pl_add, slice 2095, through HistPlAdd + Prefs.playlistAdd — the History / Favourites / New talks / Bookmarks / Downloaded / Room-queue / Talks-chooser shapes, Favourites' addToPlaylistFav the closest). It sits after the queue verb and before remove (constructive first, destructive last — the 2114 lists-menu order; History's own order inserts pl_add right before its remove too): the gate is the same non-blank stream URL the share reads (HistPlAdd.canOffer), so a streamed-only talk still offers it (filing is not a file); only a row with no resolvable stream URL hides it. The tap-time gates re-check the offer (plShareUrlFor re-resolve, so a downloaded row whose file vanished toasts talk_no_longer_in_list; the sayadaw heal, so a stale banked sayadaw never strands the item under the wrong owner). An add writes only the chosen list — this list, the queue and the lifetime totals stay. The sounding row keeps it (filing never touches playback). Row menu playlist-add landed in slice 2118; the menu's 2105 open set is now COMPLETE — the playlist-add family is on EIGHT surfaces.
    private void showPlRowMenu(final QueueEntry qe, final int position) {
        if (qe == null || qe.url == null || qe.url.length() == 0) return;
        refreshPlaylistPlayingMarks();
        final String url = qe.url;
        final String sd = qe.sayadaw;
        final String streamUrl05 = plShareUrlFor(qe);
        final String fileName05 = plShareFileFor(qe);
        final String[] goCat05 = catalogFor(sd);
        final boolean queued05 = plQueueHas(sd, streamUrl05);
        java.util.List<String> keys05 = PlRowMenu.menuKeys(url, sd, plPlayingUrl, plPlayingLibUrl, AudioService.liveEnded, streamUrl05, fileName05, goCat05, queued05);
        // v15.80 (slice 2142): the mark key — gated on the new PlRowMenu.markKey leg (the 1949 DlMark core: canOffer — a non-blank stream URL, and not the sounding talk — the sounding talk earns its mark by finishing, like the 1949 sounding exclusion; the label is DlMark.labelKey over the prefs-fresh played set, readPlayedTalksPl — PlaylistActivity had no playedTalks reader), so the label can never disagree with the list membership — and on this screen both legs are live, because playedTalks is banked only on natural finish and playlist membership is independent of it: a playlist talk stopped early offers Mark, a finished one offers Unmark.
        String markKey42 = PlRowMenu.markKey(streamUrl05, plPlayingUrl, plPlayingLibUrl, AudioService.liveEnded, DlMark.isMarked(readPlayedTalksPl(), SiteHttp.identityUrl(streamUrl05)));
        // v15.80 (slice 2142): the mark key composes right before Remove from list (indexOf — menuKeys always adds menu_pl_remove last, and the mark follows the playlist insert, so the display order reads ..., Go, Queue, PlAdd, Mark, Remove; a mark implies a share, since both gates are the same non-blank stream URL — the at42 < 0 leg is defensive only).
        if (markKey42 != null) {
            int at42 = keys05.indexOf("menu_pl_remove");
            keys05.add(at42 < 0 ? keys05.size() : at42, markKey42);
        }
        java.util.ArrayList<String> opts05 = new java.util.ArrayList<String>();
        for (String k05 : keys05) opts05.add(Lang.t(this, k05));
        final String[] optArr05 = opts05.toArray(new String[0]);
        final String[] keyArr05 = keys05.toArray(new String[0]);
        String title05 = qe.title != null && qe.title.length() > 0 ? qe.title : url;
        new android.app.AlertDialog.Builder(this)
                .setTitle(title05)
                .setItems(optArr05, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if ("menu_play_from_here".equals(keyArr05[which])) {
                            playFrom(position);
                        } else if ("menu_share".equals(keyArr05[which])) {
                            android.content.Intent send05 = new android.content.Intent(android.content.Intent.ACTION_SEND);
                            send05.setType("text/plain");
                            send05.putExtra(android.content.Intent.EXTRA_TEXT, (sd != null && sd.length() > 0 ? sd + " — " : "")
                                    + (qe.title != null ? qe.title : "")
                                    + "\n" + streamUrl05 + Lang.t(PlaylistActivity.this, "share_source"));
                            startActivity(android.content.Intent.createChooser(send05, Lang.t(PlaylistActivity.this, "share_talk")));
                        } else if ("dl_menu_copy_link".equals(keyArr05[which])) {
                            if (RoomCopyLink.canOffer(streamUrl05)) {
                                try {
                                    android.content.ClipboardManager cm05 =
                                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                    if (cm05 != null)
                                        cm05.setPrimaryClip(android.content.ClipData.newPlainText(
                                                Lang.t(PlaylistActivity.this, "clip_talk"), RoomCopyLink.clipText(streamUrl05)));
                                    android.widget.Toast.makeText(PlaylistActivity.this,
                                            RoomCopyLink.toastText(Lang.t(PlaylistActivity.this, "link_copied_prefix"), qe.title, streamUrl05),
                                            android.widget.Toast.LENGTH_SHORT).show();
                                } catch (Exception ignored) {}
                            }
                        } else if ("dl_menu_share_file".equals(keyArr05[which])) {
                            java.io.File dir05 = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Dhamma");
                            String fn05 = plShareFileFor(qe);
                            String uri05 = TalkFileShare.shareUriFor(dir05, fn05);
                            if (uri05 == null) {
                                android.widget.Toast.makeText(PlaylistActivity.this, Lang.t(PlaylistActivity.this, "talk_no_longer_in_list"), android.widget.Toast.LENGTH_SHORT).show();
                            } else {
                                android.content.Intent send05 = new android.content.Intent(android.content.Intent.ACTION_SEND);
                                send05.setType("audio/mpeg");
                                send05.putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse(uri05));
                                send05.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(android.content.Intent.createChooser(send05, Lang.t(PlaylistActivity.this, "share_talk")));
                            }
                        } else if ("menu_go_sayadaw".equals(keyArr05[which])) {
                            String[] cat05 = catalogFor(sd);
                            if (!GoSayadaw.canGo(cat05)) {
                                android.widget.Toast.makeText(PlaylistActivity.this, Lang.t(PlaylistActivity.this, "no_sayadaw_page"), android.widget.Toast.LENGTH_SHORT).show();
                            } else {
                                android.content.Intent gi05 = new android.content.Intent(PlaylistActivity.this, TalksActivity.class);
                                gi05.putExtra("name", sd);
                                gi05.putExtra("group", GoSayadaw.group(cat05));
                                gi05.putExtra("url", GoSayadaw.page(cat05));
                                gi05.putExtra("photo", GoSayadaw.photo(cat05));
                                Fx.tap(PlaylistActivity.this);
                                Fx.go(PlaylistActivity.this, gi05);
                            }
                        } else if ("menu_queue_add".equals(keyArr05[which])) {
                            refreshPlaylistPlayingMarks();
                            if (PlRowMenu.isSounding(streamUrl05, plPlayingUrl, plPlayingLibUrl, AudioService.liveEnded)) {
                                android.widget.Toast.makeText(PlaylistActivity.this, Lang.t(PlaylistActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (!plQueueAdd(sd, streamUrl05, qe.title)) {
                                android.widget.Toast.makeText(PlaylistActivity.this, Lang.t(PlaylistActivity.this, "already_in_queue"), android.widget.Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (adapter != null) adapter.notifyDataSetChanged();
                            android.widget.Toast.makeText(PlaylistActivity.this, Lang.t(PlaylistActivity.this, "added_to_queue"), android.widget.Toast.LENGTH_SHORT).show();
                        } else if ("menu_queue_remove".equals(keyArr05[which])) {
                            refreshPlaylistPlayingMarks();
                            if (PlRowMenu.isSounding(streamUrl05, plPlayingUrl, plPlayingLibUrl, AudioService.liveEnded)) {
                                android.widget.Toast.makeText(PlaylistActivity.this, Lang.t(PlaylistActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (!plQueueRemove(sd, streamUrl05)) {
                                android.widget.Toast.makeText(PlaylistActivity.this, Lang.t(PlaylistActivity.this, "queue_changed"), android.widget.Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (adapter != null) adapter.notifyDataSetChanged();
                            android.widget.Toast.makeText(PlaylistActivity.this, Lang.t(PlaylistActivity.this, "removed_from_queue"), android.widget.Toast.LENGTH_SHORT).show();
                        } else if ("pl_add".equals(keyArr05[which])) {
                            addToPlaylistPl(qe);
                        } else if ("dl_menu_mark_one".equals(keyArr05[which])) {
                            // v15.80 (slice 2142): "Mark as listened" — the 1949 mark verb on the Playlist detail row menu, through markListenedPl below (the file routes every menu verb through a named helper or an inline block).
                            markListenedPl(qe, false);
                        } else if ("dl_menu_unmark_one".equals(keyArr05[which])) {
                            // v15.80 (slice 2142): "Unmark as listened" — the 1949 unmark leg on the Playlist detail row menu, through markListenedPl below (both-forms rule: the canonical URL and the raw URL leave the played set together).
                            markListenedPl(qe, true);
                        } else if ("menu_pl_remove".equals(keyArr05[which])) {
                            removePlItem(position);
                        }
                    }
                })
                .show();
    }

    // v15.76 (slice 2118): "Add to playlist" — the row menu's
    // playlist verb (the Room's showPlaylistAddDialog, v12.65–
    // v12.80, on this screen's menu; History's 2095 verb through
    // HistPlAdd + Prefs.playlistAdd — the Favourites 2091 / New
    // talks 2092 / Bookmarks 2093 shapes, Favourites'
    // addToPlaylistFav the closest: the same catalogFor +
    // sayadawForTalk heal): re-resolve the row to its stream URL
    // at tap time (plShareUrlFor — the offer and the tap can never
    // disagree), toast talk_no_longer_in_list when a downloaded
    // row no longer resolves, heal the sayadaw exactly like the
    // Favourites verb (catalogFor + the sayadawForTalk heal — a
    // stale banked sayadaw would strand the playlist item under
    // the wrong owner, the v12.78 lesson), then offer the
    // listener's lists (Prefs.playlistNames — the seed lists are
    // there from the first read) plus a "new list" row (pl_new,
    // last). A tap files the talk through Prefs.playlistAdd and
    // toasts the v12.80 result (HistPlAdd.toastKey: added /
    // already there / full / could-not-create); the new-list row
    // opens the name dialog. An add mutates no list membership
    // here, no queue or resume state; no pause guard (no sheet
    // is left to return from); the sounding row keeps it (filing
    // never touches playback). The filed unit is the TALK, not
    // the listen (the 2084 adjudication, extended): the playlist
    // store carries url / sayadaw / title and no minute, so a
    // filed talk plays from its saved spot like every other
    // playlist item.
    private void addToPlaylistPl(final QueueEntry qe) {
        if (qe == null || qe.url == null || qe.url.length() == 0) return;
        final String streamUrl05 = plShareUrlFor(qe);
        if (!HistPlAdd.canOffer(streamUrl05)) {
            android.widget.Toast.makeText(PlaylistActivity.this,
                    Lang.t(PlaylistActivity.this, "talk_no_longer_in_list"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String sd05 = qe.sayadaw != null ? qe.sayadaw : "";
        String[] cat05 = catalogFor(sd05);
        if (cat05 == null || cat05[1] == null || cat05[1].length() == 0) {
            String owner05 = Prefs.sayadawForTalk(this, "lib", streamUrl05, "");
            if (owner05 != null && owner05.length() > 0 && !owner05.equals(sd05)) {
                sd05 = owner05;
            }
        }
        final String title05 = qe.title != null ? qe.title : "";
        final String sdF05 = sd05;
        final java.util.List<String> names05 = Prefs.playlistNames(this);
        final String[] items05 = new String[names05.size() + 1];
        for (int i05 = 0; i05 < names05.size(); i05++) items05[i05] = names05.get(i05);
        items05[names05.size()] = Lang.t(this, "pl_new");
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "pl_choose"))
                .setItems(items05, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (HistPlAdd.isNewRow(which, names05.size())) {
                            showNewPlaylistPl(streamUrl05, title05, sdF05);
                            return;
                        }
                        int r05 = Prefs.playlistAdd(PlaylistActivity.this,
                                names05.get(which), streamUrl05, sdF05, title05);
                        android.widget.Toast.makeText(PlaylistActivity.this,
                                Lang.t(PlaylistActivity.this, HistPlAdd.toastKey(r05)),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // v15.76 (slice 2118): the "new list" dialog for the
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
    private void showNewPlaylistPl(final String url, final String title, final String sd) {
        final android.widget.EditText et05 = new android.widget.EditText(this);
        et05.setHint(Lang.t(this, "pl_name_hint"));
        et05.setSingleLine(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle(Lang.t(this, "pl_new"))
                .setView(et05)
                .setPositiveButton(Lang.t(this, "pl_create"),
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        String name05 = et05.getText().toString().trim();
                        if (name05.length() == 0) {
                            android.widget.Toast.makeText(PlaylistActivity.this,
                                    Lang.t(PlaylistActivity.this, "pl_name_hint"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (name05.length() > 40) name05 = name05.substring(0, 40);
                        boolean created05 = Prefs.playlistCreate(PlaylistActivity.this, name05);
                        java.util.List<String> held05 = Prefs.playlistNames(PlaylistActivity.this);
                        String limit05 = HistPlAdd.limitToastKey(created05,
                                held05.contains(name05), held05.size());
                        if (limit05 != null) {
                            android.widget.Toast.makeText(PlaylistActivity.this,
                                    Lang.t(PlaylistActivity.this, limit05),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        int r05 = Prefs.playlistAdd(PlaylistActivity.this, name05, url, sd, title);
                        android.widget.Toast.makeText(PlaylistActivity.this,
                                Lang.t(PlaylistActivity.this, HistPlAdd.toastKey(r05)),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(Lang.t(this, "btn_no_clear"), null)
                .show();
    }

    // v15.74 (slice 2105): the row removal the per-row remove button and the row menu share — extracted from the button (behaviour byte-identical: the store result gates the row drop, sim1295 B; Play All + Shuffle follow the list, sim1295 A; the empty state refreshes; the pl_removed toast confirms). A bounds guard: the menu's position is the long-press row, the button's is the bound row — both are valid at tap time, but a stale position never drops the wrong row. The store remove is the only write this verb makes.
    private void removePlItem(int position) {
        if (curList == null) return;
        if (position < 0 || position >= items.size()) return;
        QueueEntry qe = items.get(position);
        // v12.81: check the store result (sim1295 B) — a
        // failed remove used to drop the row anyway.
        if (!Prefs.playlistRemove(this, curList, qe.url)) return;
        items.remove(position);
        adapter.notifyDataSetChanged();
        // v12.81: Play All follows the list (sim1295 A) —
        // an emptied list kept a dead Play All button.
        if (playAllBtn != null) playAllBtn.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        if (plShuffleBtn != null) plShuffleBtn.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        // v15.77 (slice 2121): Share follows the list too (the
        // v12.81 rule — a removed last shareable talk never
        // leaves a dead Share button; the gate re-reads the
        // dlMap fresh, so a download landing mid-list never
        // strands the offer).
        if (plShareBtn != null) plShareBtn.setVisibility(PlShare.canShare(curList, items, plDlMap()) ? View.VISIBLE : View.GONE);
        refreshEmpty();
        Toast.makeText(this,
                Lang.t(this, "pl_removed"),
                Toast.LENGTH_SHORT).show();
    }

    private void refreshPlaylistPlayingMarks() {
        plPlayingUrl = "";
        plPlayingLibUrl = "";
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("dhamma", MODE_PRIVATE);
            if (AudioService.liveSession && AudioService.liveHasTrack
                    && "room".equals(sp.getString("lastKind", "")))
                plPlayingUrl = sp.getString("lastUrl", "");
            if (AudioService.liveSession && AudioService.liveHasTrack
                    && "lib".equals(sp.getString("lastKind", ""))) {
                String libPath = sp.getString("lastLibPath", "");
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
                    if (matches == 1) plPlayingLibUrl = hit;
                }
            }
        } catch (Exception ignored) {}
    }

    private void syncPlaylistStripPhoto(String name) {
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
        if (ringing != lastPlStripPhotoRing) {
            lastPlStripPhotoRing = ringing;
            if (ring != null) {
                // v14.16 (slice 1670): the ring crossfades in/out
                // instead of snapping — see Fx.crossfadeRing.
                Fx.crossfadeRing(ring, ringing ? R.drawable.shelf_ring_playing : 0);
            }
        }
        if (name == null || name.length() == 0) {
            plStripPhotoName = null;
            if (ring != null) ring.setVisibility(android.view.View.GONE);
            else pv.setVisibility(android.view.View.GONE);
            return;
        }
        if (ring != null) ring.setVisibility(android.view.View.VISIBLE);
        else pv.setVisibility(android.view.View.VISIBLE);
        if (name.equals(plStripPhotoName)) return;
        boolean firstPhoto = plStripPhotoName == null;
        plStripPhotoName = name;
        String url = AudioService.stripPhotoUrlForSayadaw(PlaylistActivity.this, name);
        // v14.22 (slice 1676): the photo crossfades on a sayadaw
        // change instead of swapping — see Fx.crossfadeStripPhoto.
        Fx.crossfadeStripPhoto((CircleImageView) pv, url, firstPhoto, new Runnable() {
            @Override public void run() {
                ImageLoader.load(PlaylistActivity.this, url, name, (CircleImageView) pv);
            }
        });
    }

    // v15.40 (slice 1907): the Playlist resume strip's title melts on a
    // structural change instead of snapping — the mirror of Main's
    // v14.70 meltResumeStripTitle70. The 1s tick re-runs
    // syncPlaylistStrip in resume mode, and the title carries the
    // download mark (stripResumeDownloadMark / the lib branch's inline
    // check), so a download landing while Playlist sits open flipped
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
    private String plResumeStripTitle = null;
    private void meltResumeStripTitlePl(android.widget.TextView tx, String want) {
        if (tx == null || want == null) return;
        Object aObjPl = tx.getTag(R.id.stripTextAnim);
        if (aObjPl instanceof android.animation.Animator) ((android.animation.Animator) aObjPl).cancel();
        String prevPl = plResumeStripTitle;
        plResumeStripTitle = want;
        if (prevPl == null) { tx.setText(want); return; }
        if (prevPl.equals(want)) return;
        tx.setText(want);
        if (tx.getVisibility() == android.view.View.VISIBLE && tx.getAlpha() >= 0.999f) Fx.fadeIn(tx, 250);
    }

    private void syncPlaylistStrip() {
        // v13.55: playing-row mark — rebind once when the sounding talk changes under the list
        // (Talks v4.89/v11.15, same change-only rule): auto-advance, a Next from the strip, a
        // natural end (same URL, liveEnded flipped) or a stop moves the mark at once; steady ticks
        // never touch the adapter, so scrolling stays undisturbed. Runs before the strip's own early
        // returns, so a dead session clears the mark even while the strip hides.
        refreshPlaylistPlayingMarks();
        if (adapter != null && (!plPlayingUrl.equals(plRowPlayingUrl == null ? "" : plRowPlayingUrl)
                || !plPlayingLibUrl.equals(plRowPlayingLibUrl == null ? "" : plRowPlayingLibUrl)
                || AudioService.liveEnded != plRowEnded)) {
            plRowPlayingUrl = plPlayingUrl;
            plRowPlayingLibUrl = plPlayingLibUrl;
            plRowEnded = AudioService.liveEnded;
            adapter.notifyDataSetChanged();
            // v15.74 (slice 2103): the sounding change above moves the playing talk (advance, stop, or a new session while browsing) — refresh the jump button's cached target and repaint it. Change-only: steady ticks never touch the button.
            refreshPlJumpTarget();
            paintPlJumpBtnFromList();
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
            plResumeStripMode = false;
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
                            || Fx.stripInProgressDlFilenames(PlaylistActivity.this, rsp).contains(new java.io.File(lPath).getName()))) {
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
                    syncPlaylistStripPhoto(null);
                    plResumeStripTitle = null;
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
                int lDur = Fx.stripResumeDuration(PlaylistActivity.this, rsp, rSavedUrl, lPath);
                // v8.52: Main's v7.94 resume mark, mirrored — the proven file is offline, like Main's lib branch.
                meltResumeStripTitlePl(tx, "↺ " + lTitle + (lFav ? " ★" : "") + (Fx.stripInProgressDlFilenames(PlaylistActivity.this, rsp).contains(new java.io.File(lPath).getName()) ? " ⏳" : " ⬇"));
                // v5.46: a dead library strip names its recorded
                // sayadaw like the live strip since v5.45, in the
                // Room strip's "name · mm:ss မှ ဆက်နားထောင်ရန်"
                // shape; an unrecorded download keeps today's
                // generic line, never a guessed name.
                String libSd = Fx.stripLibSayadaw(PlaylistActivity.this, rsp);
                if (sb != null) sb.setText(libSd != null ? libSd + " · " + lTime + Lang.t(PlaylistActivity.this, "resume_from") : Lang.t(PlaylistActivity.this, "downloaded_talk") + " · " + lTime + Lang.t(PlaylistActivity.this, "resume_from"));
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
                plResumeStripMode = true;
                syncPlaylistStripPhoto(libSd);
                Fx.showStrip(strip);
                return;
            }
            if (!"room".equals(rKind) || rName.length() == 0 || rTitle.length() == 0
                    || rPage.length() == 0 || rPos <= 5000 || !rMatch) {
                syncPlaylistStripPhoto(null);
                plResumeStripTitle = null;
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
            int rDur = Fx.stripResumeDuration(PlaylistActivity.this, rsp, rUrl, null);
            // v8.52: Main's v7.94 resume download mark, mirrored — a ↺ strip here never said whether resuming streams or plays offline.
            meltResumeStripTitlePl(tx, "↺ " + rTitle + (rFav ? " ★" : "") + Fx.stripResumeDownloadMark(PlaylistActivity.this, rsp, rUrl));
            if (sb != null) sb.setText(rName + " · " + rTime + Lang.t(PlaylistActivity.this, "resume_from"));
            if (tg != null) tg.setText("▶");
            if (nx != null) Fx.crossfadeStripBtn(nx, false);
            // v7.31: same Previous hide as the library resume
            // branch above — a ↺ strip never shows ⏮.
            android.view.View pv0 = findViewById(R.id.nowStripPrev);
            if (pv0 != null) Fx.crossfadeStripBtn(pv0, false);
            android.widget.ProgressBar rPb = findViewById(R.id.nowStripProgress);
            if (rPb != null) Fx.stripResumeProgress(rPb, rPos, rDur);
            plResumeStripMode = true;
            syncPlaylistStripPhoto(rName);
            Fx.showStrip(strip);
            return;
        }
        plResumeStripMode = false;
        plResumeStripTitle = null; // v15.40: live branch owns tx via fadeStripText from here
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
                String libSd = Fx.stripLibSayadaw(PlaylistActivity.this, sp);
                subText = libSd != null ? libSd : Lang.t(PlaylistActivity.this, "downloaded_talk");
            }
        }
        if (text == null) { syncPlaylistStripPhoto(null); Fx.hideStrip(strip); return; }
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
                    ? " 😴 " + Lang.t(PlaylistActivity.this, "sleep_end")
                    : " 😴 ⏸ " + Lang.t(PlaylistActivity.this, "sleep_end");
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
                String saved = Prefs.savedQueue(PlaylistActivity.this,
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
                String saved = Prefs.savedQueueLib(PlaylistActivity.this);
                if (saved.length() > 0) {
                    for (String line : saved.split("\n"))
                        if (line.trim().length() > 0
                                && new java.io.File(line.trim()).isFile()
                                && !line.trim().equals(curLib)) qN++;
                }
            }
            if (qN > 0) text += " · ◈ " + qN;
        } catch (Exception ignored) {}
        if (AudioService.liveEnded) text += " · " + Lang.t(PlaylistActivity.this, "notif_state_ended");
        // v14.21 (slice 1675): the strip title/sub fade on a talk
        // change, keyed on the talk itself (Room URL / library
        // path) — the composed strings tick every second (sleep
        // countdown on the title, live clock on the sub), so the
        // text can never be the key.
        String stripTalkId = "room".equals(kind) ? sp.getString("lastUrl", "") : sp.getString("lastLibPath", "");
        if (tx != null) Fx.fadeStripText(tx, text, stripTalkId);
        // v5.32: live clock on the strip — sayadaw name + elapsed/total.
        android.widget.ProgressBar pb = findViewById(R.id.nowStripProgress);
        if (subText != null) subText += Fx.stripClockSuffixForStrip(pb, AudioService.livePositionMs, AudioService.liveDurationMs, Prefs.stripClockRemaining(PlaylistActivity.this));
        if (sb != null && subText != null) Fx.fadeStripText(sb, subText, stripTalkId);
        Fx.syncStripProgress(pb); // v7.93: skips while a mini-seek drag is in flight
        if (tg != null) Fx.flipStripGlyph(tg, AudioService.livePlaying);
        // v6.01: live strip's portrait — Room names lastName, library
        // names its recorded sayadaw (Fx.stripLibSayadaw, null when
        // unrecorded); both resolve through syncPlaylistStripPhoto's
        // exact-name catalog match, never a guessed face.
        syncPlaylistStripPhoto("room".equals(kind) ? sp.getString("lastName", "")
                : ("lib".equals(kind) ? Fx.stripLibSayadaw(PlaylistActivity.this, sp) : null));
        Fx.showStrip(strip);
    }

    private void startPlaylistStripTick() {
        if (stripTick != null) return;
        stripTick = new Runnable() {
            @Override public void run() {
                if (stripTick == null) return;
                // v11.76: re-post in finally — a throw inside syncPlaylistStrip used
                // to kill the 1s tick outright, freezing strip title/clock/
                // progress/glyph until the next onPause/onResume cycle.
                try { syncPlaylistStrip(); }
                finally { stripHandler.postDelayed(this, 1000); }
            }
        };
        stripHandler.post(stripTick);
    }

    private void stopPlaylistStripTick() {
        if (stripTick != null) {
            stripHandler.removeCallbacks(stripTick);
            stripTick = null;
        }
    }

    private void wirePlaylistStrip() {
        android.view.View nowStrip = findViewById(R.id.nowStrip);
        if (nowStrip != null) {
            Fx.press(nowStrip);
            // v4.73: the strip's progress bar seeks (see Fx.miniSeekBar).
            Fx.miniSeekBar(PlaylistActivity.this, nowStrip);
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
                        if (plResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) return;
                        Prefs.setStripClockRemaining(PlaylistActivity.this, !Prefs.stripClockRemaining(PlaylistActivity.this));
                        Fx.tap(PlaylistActivity.this);
                        syncPlaylistStrip();
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
                            Fx.tap(PlaylistActivity.this);
                            Fx.morph(v);
                            Intent li = new Intent(PlaylistActivity.this, DownloadedActivity.class);
                            li.putExtra("jumpPath", lp);
                            // v4.63: in dead-library resume mode
                            // the tap also auto-plays the file from
                            // its saved spot; a live session never
                            // passes this flag, so it still only
                            // glides to the playing file.
                            // v12.57: live recheck, not just the cached
                            // flag — plResumeStripMode refreshes on
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
                            if (plResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) li.putExtra("autoPlayPath", lp);
                            Fx.go(PlaylistActivity.this, li);
                            return;
                        }
                        android.widget.Toast.makeText(PlaylistActivity.this, Lang.t(PlaylistActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(PlaylistActivity.this, Lang.t(PlaylistActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(PlaylistActivity.this, RoomActivity.class);
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
                    Fx.tap(PlaylistActivity.this);
                    Fx.morph(v);
                    Fx.go(PlaylistActivity.this, i);
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
                    // plResumeStripMode refreshes on the 1s tick, so a
                    // session dying <1s before this long-press left the
                    // flag false and the press ACTION_STOPped a trackless
                    // service (phantom "talk_stopped" toast, saved spot
                    // NOT dismissed — the same ≤1s stale-flag class
                    // v12.55 closed for the Toggle long-press). The
                    // strip's own branch test is !liveSession ||
                    // !liveHasTrack, so re-checking the same statics here
                    // can only dismiss a genuinely dead session, never
                    // stop a live one.
                    if (plResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        // v5.34: same as Talks' dismiss — the
                        // per-talk spot goes with the global one.
                        String dismissUrl = getSharedPreferences("dhamma", MODE_PRIVATE)
                                .getString("lastPosUrl", "");
                        getSharedPreferences("dhamma", MODE_PRIVATE).edit()
                                .putInt("lastPos", 0).putString("lastPosUrl", "").apply();
                        if (dismissUrl.length() > 0) Prefs.talkPosRemoveBoth(PlaylistActivity.this, dismissUrl);
                        Fx.tap(PlaylistActivity.this);
                        Fx.morph(v);
                        // v13.28: same as Downloaded's dismiss — one resume spot cleared,
                        // not the history; toast resume_cleared (Main's key), not
                        // hist_cleared (Settings' real clear-history key).
                        android.widget.Toast.makeText(PlaylistActivity.this, Lang.t(PlaylistActivity.this, "resume_cleared"), android.widget.Toast.LENGTH_SHORT).show();
                        syncPlaylistStrip();
                        // Playlist rows carry no ↺ mark, so this
                        // rebind changes nothing visible today; it
                        // keeps the dismiss path a faithful mirror
                        // of Talks' (v12.41) if a mark ever lands.
                        if (adapter != null) adapter.notifyDataSetChanged();
                        return true;
                    }
                    Intent svc = new Intent(PlaylistActivity.this, AudioService.class);
                    svc.setAction(AudioService.ACTION_STOP);
                    startService(svc);
                    Fx.tap(PlaylistActivity.this);
                    Fx.morph(v);
                    android.widget.Toast.makeText(PlaylistActivity.this, Lang.t(PlaylistActivity.this, "talk_stopped"), android.widget.Toast.LENGTH_SHORT).show();
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
                // v12.55: live recheck, not just the cached flag — plResumeStripMode
                // refreshes on the 1s tick, so a session dying <1s before this tap left
                // the flag false and the tap ACTION_TOGGLEd a trackless service (dead
                // tap, tap-twice-to-resume — the exact bug Settings v11.90 fixed for
                // its Toggle). The strip's own branch test is !liveSession ||
                // !liveHasTrack, so re-checking the same statics here can only route a
                // genuinely dead session to resume, never a live one.
                if (plResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                    android.view.View strip2 = findViewById(R.id.nowStrip);
                    if (strip2 != null) strip2.performClick();
                    return;
                }
                Intent svc = new Intent(PlaylistActivity.this, AudioService.class);
                svc.setAction(AudioService.ACTION_TOGGLE);
                startService(svc);
                Fx.tap(PlaylistActivity.this);
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
                if (plResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                    android.view.View strip2 = findViewById(R.id.nowStrip);
                    if (strip2 != null) return strip2.performLongClick();
                    return true;
                }
                Intent svc = new Intent(PlaylistActivity.this, AudioService.class);
                svc.setAction(AudioService.ACTION_STOP);
                startService(svc);
                Fx.tap(PlaylistActivity.this);
                Fx.morph(v);
                android.widget.Toast.makeText(PlaylistActivity.this, Lang.t(PlaylistActivity.this, "talk_stopped"), android.widget.Toast.LENGTH_SHORT).show();
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
                    // plResumeStripMode refreshes on the 1s tick, so a session dying
                    // <1s before this tap left the flag false and the tap ACTION_NEXT-ed
                    // into a trackless Room instead of resuming the ↺ talk.
                    if (plResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    Fx.tap(PlaylistActivity.this);
                    Fx.morph(v);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        Intent li = new Intent(PlaylistActivity.this, DownloadedActivity.class);
                        li.setAction(DownloadedActivity.ACTION_NEXT);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        Fx.go(PlaylistActivity.this, li);
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(PlaylistActivity.this, Lang.t(PlaylistActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(PlaylistActivity.this, RoomActivity.class);
                    i.setAction(RoomActivity.ACTION_NEXT);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.go(PlaylistActivity.this, i);
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
                    if (plResumeStripMode || !AudioService.liveSession || !AudioService.liveHasTrack) {
                        android.view.View strip2 = findViewById(R.id.nowStrip);
                        if (strip2 != null) strip2.performClick();
                        return;
                    }
                    android.content.SharedPreferences sp =
                            getSharedPreferences("dhamma", MODE_PRIVATE);
                    Fx.tap(PlaylistActivity.this);
                    Fx.morph(v);
                    if ("lib".equals(sp.getString("lastKind", ""))) {
                        Intent li = new Intent(PlaylistActivity.this, DownloadedActivity.class);
                        li.setAction(DownloadedActivity.ACTION_PREV);
                        li.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        Fx.go(PlaylistActivity.this, li);
                        return;
                    }
                    String page = sp.getString("lastPage", "");
                    if (page.length() == 0) {
                        android.widget.Toast.makeText(PlaylistActivity.this, Lang.t(PlaylistActivity.this, "choose_first"), android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent i = new Intent(PlaylistActivity.this, RoomActivity.class);
                    i.setAction(RoomActivity.ACTION_PREV);
                    i.putExtra("name", sp.getString("lastName", ""));
                    i.putExtra("group", sp.getString("lastGroup", ""));
                    i.putExtra("url", page);
                    i.putExtra("photo", sp.getString("lastPhoto", ""));
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    Fx.go(PlaylistActivity.this, i);
                }
            });
        }
    }
}
