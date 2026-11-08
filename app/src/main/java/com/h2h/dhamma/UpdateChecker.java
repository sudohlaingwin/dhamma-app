package com.h2h.dhamma;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// v12.95: in-app updater, copied from the 3D-number app's force-update
// system (his order 2026-11-06 ~23:35 +07 "3D appက update systemအတိုင်းပေါ့").
// On every Main launch the app asks GitHub for the latest PUBLISHED
// release of sudohlaingwin/dhamma-app; release tags are v<VERSIONCODE>
// (e.g. v1295) and the asset is the .apk. If the published tag is newer
// than the installed versionCode, a full-screen blocking dialog appears
// — no close button, back swallowed — with a real byte-based download
// (redirects followed, 20s with no bytes = stall, 3 attempts) and the
// system installer. Any failure fails OPEN: the app keeps working, no
// toast, no block. Draft releases are invisible to the checker, so a new
// build only reaches phones once its release is published (same draft
// rule as the 3D app: publish only on his "update လုပ်လိုက်").
// v13.10: the block is never a trap — after a failed download, a
// refused install permission, or a cancelled installer the dialog
// offers "ဒီဗားရှင်းနဲ့ပဲ ဆက်သုံးမယ်" (keep this version for this
// process; asked again on the next cold start).
public class UpdateChecker {
    public static final String REPO = "sudohlaingwin/dhamma-app";
    public static final String APK_NAME = "dhamma-update.apk";
    private static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final int STALL_MS = 20000;
    private static final int MAX_ATTEMPTS = 3;

    private static boolean checkedThisProcess = false;
    private static boolean checkRunning = false;
    // v12.98: wall-clock of the last check start — onResume re-checks
    // at most once a minute (see onResume).
    private static long lastCheckAtMs = 0;
    // Pending-update state, kept across rotation (static, process-wide).
    private static String pendingUrl = null;
    private static String pendingName = null;
    // v13.10 (F3): the .apk asset's size from the release JSON, used
    // to verify a download is complete before it is ever installed.
    private static long pendingSize = -1;
    // v13.10 (F2): the tag the user skipped this process — the
    // once-a-minute onResume re-check must not re-block for it.
    // Cleared on the next cold start (statics reset), so the user
    // is asked again then.
    private static String skippedTag = null;
    private static boolean downloading = false;
    private static int lastProgress = -1;
    private static long lastPostAt = 0;
    private static File downloadedFile = null;
    private static boolean downloadFailed = false;
    private static boolean waitingForPermission = false;
    private static Dialog blockDialog = null;
    private static Activity blockActivity = null;
    private static TextView blockSub;
    private static TextView blockVersion;
    private static TextView blockStatus;
    private static Button blockBtn;
    private static Button blockSkip;
    private static ProgressBar blockBar;

    public static boolean isBlockVisible() {
        return blockDialog != null && blockDialog.isShowing();
    }

    // v13.10 (F5): dismiss the block and drop every view ref so a
    // rotation never leaks the old dialog's window (WindowLeaked) —
    // called from MainActivity.onDestroy; the next onCreate re-shows
    // the block from pendingUrl, so nothing is lost. Clears only the
    // dialog, never the pending-update state.
    public static void dismissBlock() {
        try {
            if (blockDialog != null) blockDialog.dismiss();
        } catch (Exception ignored) {}
        blockDialog = null;
        blockActivity = null;
        blockSub = null;
        blockVersion = null;
        blockStatus = null;
        blockBtn = null;
        blockSkip = null;
        blockBar = null;
    }

    public static void check(final Activity activity) {
        if (pendingUrl != null) { showBlock(activity); return; }
        if (checkedThisProcess || checkRunning) return;
        checkedThisProcess = true;
        checkRunning = true;
        lastCheckAtMs = System.currentTimeMillis();
        final Context appCtx = activity.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Latest found = fetchLatest();
                    if (found != null) {
                        int current = currentCode(appCtx);
                        int latest = parseCode(found.tag);
                        // v13.10 (F2): a version skipped this process
                        // never re-blocks until the next cold start
                        // (the statics reset) — the once-a-minute
                        // onResume re-check must not undo the skip.
                        if (current > 0 && latest > current
                                && !found.tag.equals(skippedTag)) {
                            pendingUrl = found.url;
                            pendingName = found.tag;
                            pendingSize = found.size;
                            final Activity a = activity;
                            a.runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    if (!a.isFinishing()) showBlock(a);
                                }
                            });
                        }
                    }
                } catch (Exception ignored) {
                    // fail open: offline / API down -> keep using the app
                } finally {
                    checkRunning = false;
                }
            }
        }).start();
    }

    public static void onResume(Activity activity) {
        if (pendingUrl != null) {
            if (!isBlockVisible()) { showBlock(activity); return; }
            if (waitingForPermission && canInstall(activity)) {
                waitingForPermission = false;
                if (downloadedFile != null && downloadedFile.exists()) fireInstaller(activity);
                else refreshBlockUi(activity);
            } else {
                // v12.99: returning from a CANCELLED installer left the
                // update button dead — fireInstaller disables it with
                // the "upd_inst" text, and this branch did nothing when
                // waitingForPermission was false, so the button stayed
                // disabled until rotation or process death. Re-sync the
                // button: with the file downloaded it becomes tappable
                // again (tap re-fires the installer); mid-download it
                // shows progress; without permission it re-shows the
                // permission prompt. Same for a Settings return without
                // granting: refreshBlockUi re-shows that state too.
                refreshBlockUi(activity);
            }
            return;
        }
        // v12.98: the check used to run ONCE per process — if the app
        // was already open when a release was published, no Main resume
        // ever re-checked, so the update never appeared until the
        // process died (his v12.97 report 2026-11-07 ~11:40 +07: the
        // app was open since the v12.96 install, before v1297 was
        // published). Re-check on resume, at most once a minute.
        if (!checkRunning && System.currentTimeMillis() - lastCheckAtMs > 60000) {
            checkedThisProcess = false;
            check(activity);
        }
    }

    // v13.10 (F4): parse only the LEADING digits after one optional
    // 'v' — the old strip-all-digits turned "v1308-rc1" into 13081,
    // a false update prompt. A tag outside the v<code> convention
    // (e.g. "v13.07" -> 13) fails SAFE: never a false prompt.
    private static int parseCode(String tag) {
        try {
            String t = tag == null ? "" : tag.trim();
            if (t.startsWith("v") || t.startsWith("V")) t = t.substring(1);
            int i = 0;
            while (i < t.length() && Character.isDigit(t.charAt(i))) i++;
            if (i == 0) return 0;
            return Integer.parseInt(t.substring(0, i));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int currentCode(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            if (Build.VERSION.SDK_INT >= 33) {
                return (int) pm.getPackageInfo(ctx.getPackageName(),
                        PackageManager.PackageInfoFlags.of(0)).getLongVersionCode();
            }
            return pm.getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Exception ignored) {
            return 0;
        }
    }

    // Returns the latest release's tag, .apk URL and asset size, or null.
    private static Latest fetchLatest() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API_URL).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "DhammaApp");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            InputStream in = c.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
            in.close();
            JSONObject j = new JSONObject(sb.toString());
            String tag = j.optString("tag_name", "");
            String url = null;
            long size = -1;
            JSONArray assets = j.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    if (name.toLowerCase(java.util.Locale.US).endsWith(".apk")) {
                        url = a.optString("browser_download_url", null);
                        // v13.10 (F3): the asset's real size, for the
                        // download verification in startDownload.
                        size = a.optLong("size", -1);
                        break;
                    }
                }
            }
            if (tag.length() == 0 || url == null) return null;
            return new Latest(tag, url, size);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static class Latest {
        final String tag;
        final String url;
        final long size;
        Latest(String tag, String url, long size) {
            this.tag = tag;
            this.url = url;
            this.size = size;
        }
    }

    private static void showBlock(final Activity activity) {
        // v13.10 (F5): dismissBlock() also nulls the view refs.
        dismissBlock();
        blockDialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        blockDialog.setCancelable(false);
        blockDialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override public boolean onKey(DialogInterface d, int keyCode, KeyEvent e) {
                return keyCode == KeyEvent.KEYCODE_BACK; // swallow back: update first
            }
        });
        blockDialog.setContentView(R.layout.update_block);
        blockSub = (TextView) blockDialog.findViewById(R.id.updSub);
        blockVersion = (TextView) blockDialog.findViewById(R.id.updVersion);
        blockStatus = (TextView) blockDialog.findViewById(R.id.updStatus);
        blockBtn = (Button) blockDialog.findViewById(R.id.updBtn);
        blockBar = (ProgressBar) blockDialog.findViewById(R.id.updBar);
        // v13.10 (F2): the escape hatch — hidden until the update is
        // actually stuck (refreshBlockUi shows it), so the blocking
        // update still comes first.
        blockSkip = (Button) blockDialog.findViewById(R.id.updSkip);
        blockSkip.setText(Lang.t(activity, "upd_skip"));
        blockSkip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onSkipButton(activity);
            }
        });
        TextView title = (TextView) blockDialog.findViewById(R.id.updTitle);
        title.setText(Lang.t(activity, "upd_title"));
        blockSub.setText(Lang.t(activity, "upd_sub"));
        blockVersion.setText(pendingName == null ? "" : pendingName);
        blockBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Fx.tap(activity);
                onUpdateButton(activity);
            }
        });
        blockActivity = activity;
        refreshBlockUi(activity);
        try {
            blockDialog.show();
        } catch (Exception ignored) {}
    }

    private static void onUpdateButton(Activity activity) {
        if (downloading) return;
        if (downloadFailed) {
            downloadFailed = false;
            downloadedFile = null;
            startDownload(activity);
            return;
        }
        if (downloadedFile != null && downloadedFile.exists()) {
            fireInstaller(activity);
            return;
        }
        if (!canInstall(activity)) {
            waitingForPermission = true;
            try {
                Intent it = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(it);
            } catch (Exception ignored) {}
            refreshBlockUi(activity);
            return;
        }
        startDownload(activity);
    }

    // v13.10 (F2): the escape hatch. The user keeps this version for
    // the rest of this process — skippedTag stops the once-a-minute
    // onResume re-check from re-blocking, and the next cold start
    // (statics reset) asks again. Never deletes the downloaded file.
    private static void onSkipButton(Activity activity) {
        Fx.tap(activity);
        skippedTag = pendingName;
        pendingUrl = null;
        pendingName = null;
        pendingSize = -1;
        downloadedFile = null;
        downloadFailed = false;
        waitingForPermission = false;
        dismissBlock();
    }

    private static boolean canInstall(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return true;
        try {
            return ctx.getPackageManager().canRequestPackageInstalls();
        } catch (Exception ignored) {
            return true;
        }
    }

    private static void startDownload(final Activity activity) {
        if (downloading) return;
        downloading = true;
        downloadFailed = false;
        lastProgress = -1;
        downloadedFile = null;
        refreshBlockUi(activity);
        final Context appCtx = activity.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                // v13.10 (F1): the whole body is guarded — a null
                // external dir used to throw before `downloading`
                // was cleared, leaving the button dead forever.
                // v13.10 (F3): each attempt also verifies the file
                // against the release asset's size, so a truncated
                // chunked body (no Content-Length) retries instead
                // of becoming a corrupt "downloaded" file.
                boolean ok = false;
                File out = null;
                try {
                    File dir = appCtx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) dir = appCtx.getFilesDir(); // unmounted / shared storage
                    if (dir == null) throw new IllegalStateException("no storage dir");
                    out = new File(dir, APK_NAME);
                    for (int attempt = 1; attempt <= MAX_ATTEMPTS && !ok; attempt++) {
                        try {
                            ok = downloadOnce(pendingUrl, out)
                                    && (pendingSize <= 0 || out.length() == pendingSize);
                        } catch (Exception ignored) {
                            ok = false;
                        }
                        if (!ok && attempt < MAX_ATTEMPTS) {
                            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                        }
                    }
                    // v13.19 (F10): after the final failed attempt no
                    // half-written file stays in Downloads — the next
                    // attempt (or skip) starts from a clean slate.
                    if (!ok && out != null) out.delete();
                } catch (Throwable t) {
                    ok = false;
                } finally {
                    downloading = false;
                }
                if (ok) {
                    downloadedFile = out;
                    postToUi(new Runnable() {
                        @Override public void run() {
                            Activity a = blockActivity;
                            if (a != null && !a.isFinishing()) fireInstaller(a);
                        }
                    });
                } else {
                    downloadFailed = true;
                    postToUi(new Runnable() {
                        @Override public void run() {
                            Activity a = blockActivity;
                            if (a != null && !a.isFinishing()) refreshBlockUi(a);
                        }
                    });
                }
            }
        }).start();
    }

    // One download attempt. Read timeout = stall window: 20s with no bytes
    // throws, and the caller retries (3 attempts, like the 3D updater).
    private static boolean downloadOnce(String url, File out) throws Exception {
        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            String cur = url;
            for (int hops = 0; hops < 5; hops++) {
                c = (HttpURLConnection) new URL(cur).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(STALL_MS);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", "DhammaApp");
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    c = null;
                    if (loc == null) return false;
                    // v13.19 (F11): a relative Location ("/x.apk") is
                    // not a URL — resolve it against the current URL
                    // instead of dying on every attempt.
                    try {
                        cur = new URL(new URL(cur), loc).toString();
                    } catch (Exception ignored) {
                        return false;
                    }
                    continue;
                }
                if (code != 200) return false;
                break;
            }
            if (c == null) return false;
            long total = c.getContentLengthLong();
            in = c.getInputStream();
            fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            long done = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
                done += n;
                // v13.19 (F14): when the server hides Content-Length
                // (chunked body) the bar used to sit at 0% for the
                // whole download — post from the release asset's known
                // size (pendingSize, v13.10 F3) instead, capped at 99%
                // until the caller verifies the finished file.
                if (total > 0) postProgress((int) (done * 100 / total));
                else if (pendingSize > 0)
                    postProgress(Math.min(99, (int) (done * 100 / pendingSize)));
            }
            fos.getFD().sync();
            // v13.19 (F7): an empty chunked body (done == 0, no length)
            // used to count as a success — it is not a download.
            return total <= 0 ? done > 0 : done == total;
        } finally {
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            if (c != null) c.disconnect();
        }
    }

    private static void postProgress(final int p) {
        long now = System.currentTimeMillis();
        if (p == lastProgress) return;
        if (now - lastPostAt < 150 && p < 100) return; // ~4-6 posts/sec, like the 3D updater
        lastProgress = p;
        lastPostAt = now;
        postToUi(new Runnable() {
            @Override public void run() {
                Activity a = blockActivity;
                if (a != null && !a.isFinishing()) refreshBlockUi(a);
            }
        });
    }

    private static void postToUi(Runnable r) {
        Activity a = blockActivity;
        if (a != null) {
            try { a.runOnUiThread(r); } catch (Exception ignored) {}
        }
    }

    private static void fireInstaller(Activity activity) {
        try {
            File f = downloadedFile;
            if (f == null || !f.exists() || f.length() == 0) {
                downloadFailed = true;
                downloadedFile = null;
                refreshBlockUi(activity);
                return;
            }
            // v13.10 (F3): never fire the installer on a file whose
            // size no longer matches the release asset — a truncated
            // file used to loop the installer forever. Drop the bad
            // file and offer a re-download instead.
            if (pendingSize > 0 && f.length() != pendingSize) {
                downloadFailed = true;
                downloadedFile = null;
                try { f.delete(); } catch (Exception ignored) {}
                refreshBlockUi(activity);
                return;
            }
            Uri uri = Uri.parse("content://com.h2h.dhamma.apkprovider/" + APK_NAME);
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(uri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(it);
            if (blockStatus != null) blockStatus.setText(Lang.t(activity, "upd_inst"));
            if (blockBtn != null) {
                blockBtn.setEnabled(false);
                blockBtn.setText(Lang.t(activity, "upd_inst"));
            }
        } catch (Exception ignored) {
            downloadFailed = true;
            downloadedFile = null;
            refreshBlockUi(activity);
        }
    }

    private static void refreshBlockUi(Activity activity) {
        if (blockSub == null || blockBtn == null || blockSkip == null
                || blockBar == null || blockStatus == null) return;
        if (downloading) {
            blockSub.setText(Lang.t(activity, "upd_sub"));
            int p = lastProgress < 0 ? 0 : lastProgress;
            blockBar.setVisibility(View.VISIBLE);
            blockBar.setProgress(p);
            blockStatus.setText(Lang.t(activity, "upd_dl") + " " + p + "%");
            blockBtn.setEnabled(false);
            blockBtn.setText(Lang.t(activity, "upd_dl") + " " + p + "%");
            // v13.10 (F2): no skipping mid-download — the escape
            // appears only once the update is actually stuck.
            blockSkip.setVisibility(View.GONE);
        } else if (downloadFailed) {
            blockBar.setVisibility(View.VISIBLE);
            blockBar.setProgress(0);
            blockStatus.setText(Lang.t(activity, "upd_fail"));
            blockBtn.setEnabled(true);
            blockBtn.setText(Lang.t(activity, "upd_fail"));
            blockSkip.setVisibility(View.VISIBLE);
        } else if (waitingForPermission) {
            blockBar.setVisibility(View.INVISIBLE);
            blockSub.setText(Lang.t(activity, "upd_perm_sub"));
            blockStatus.setText(Lang.t(activity, "upd_perm"));
            blockBtn.setEnabled(true);
            blockBtn.setText(Lang.t(activity, "upd_perm"));
            blockSkip.setVisibility(View.VISIBLE);
        } else if (downloadedFile != null && downloadedFile.exists()) {
            blockBar.setVisibility(View.VISIBLE);
            blockBar.setProgress(100);
            blockStatus.setText(Lang.t(activity, "upd_inst"));
            blockBtn.setEnabled(true);
            blockBtn.setText(Lang.t(activity, "upd_inst"));
            // v13.10 (F2): the installer already opened once and the
            // user came back — the escape is available here too.
            blockSkip.setVisibility(View.VISIBLE);
        } else {
            blockBar.setVisibility(View.INVISIBLE);
            blockSub.setText(Lang.t(activity, "upd_sub"));
            blockStatus.setText("");
            blockBtn.setEnabled(true);
            blockBtn.setText(Lang.t(activity, "upd_btn"));
            // v13.10 (F2): the update must be tried once before the
            // escape appears — the blocking update still comes first.
            blockSkip.setVisibility(View.GONE);
        }
    }
}
