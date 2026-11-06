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
public class UpdateChecker {
    public static final String REPO = "sudohlaingwin/dhamma-app";
    public static final String APK_NAME = "dhamma-update.apk";
    private static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final int STALL_MS = 20000;
    private static final int MAX_ATTEMPTS = 3;

    private static boolean checkedThisProcess = false;
    private static boolean checkRunning = false;
    // Pending-update state, kept across rotation (static, process-wide).
    private static String pendingUrl = null;
    private static String pendingName = null;
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
    private static ProgressBar blockBar;

    public static boolean isBlockVisible() {
        return blockDialog != null && blockDialog.isShowing();
    }

    public static void check(final Activity activity) {
        if (pendingUrl != null) { showBlock(activity); return; }
        if (checkedThisProcess || checkRunning) return;
        checkedThisProcess = true;
        checkRunning = true;
        final Context appCtx = activity.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String[] found = fetchLatest();
                    if (found != null) {
                        int current = currentCode(appCtx);
                        int latest = parseCode(found[0]);
                        if (current > 0 && latest > current) {
                            pendingUrl = found[1];
                            pendingName = found[0];
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
        if (pendingUrl == null) return;
        if (!isBlockVisible()) { showBlock(activity); return; }
        if (waitingForPermission && canInstall(activity)) {
            waitingForPermission = false;
            if (downloadedFile != null && downloadedFile.exists()) fireInstaller(activity);
            else refreshBlockUi(activity);
        }
    }

    private static int parseCode(String tag) {
        try {
            String digits = tag.replaceAll("[^0-9]", "");
            if (digits.length() == 0) return 0;
            return Integer.parseInt(digits);
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

    // Returns {tag_name, browser_download_url of the first .apk asset}, or null.
    private static String[] fetchLatest() {
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
            JSONArray assets = j.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    if (name.toLowerCase(java.util.Locale.US).endsWith(".apk")) {
                        url = a.optString("browser_download_url", null);
                        break;
                    }
                }
            }
            if (tag.length() == 0 || url == null) return null;
            return new String[]{tag, url};
        } catch (Exception ignored) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void showBlock(final Activity activity) {
        try {
            if (blockDialog != null) blockDialog.dismiss();
        } catch (Exception ignored) {}
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
                File out = new File(appCtx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME);
                boolean ok = false;
                for (int attempt = 1; attempt <= MAX_ATTEMPTS && !ok; attempt++) {
                    try {
                        ok = downloadOnce(pendingUrl, out);
                    } catch (Exception ignored) {
                        ok = false;
                    }
                    if (!ok && attempt < MAX_ATTEMPTS) {
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    }
                }
                downloading = false;
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
                    cur = loc;
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
                if (total > 0) postProgress((int) (done * 100 / total));
            }
            fos.getFD().sync();
            return total <= 0 || done == total;
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
        if (blockSub == null || blockBtn == null || blockBar == null || blockStatus == null) return;
        if (downloading) {
            blockSub.setText(Lang.t(activity, "upd_sub"));
            int p = lastProgress < 0 ? 0 : lastProgress;
            blockBar.setVisibility(View.VISIBLE);
            blockBar.setProgress(p);
            blockStatus.setText(Lang.t(activity, "upd_dl") + " " + p + "%");
            blockBtn.setEnabled(false);
            blockBtn.setText(Lang.t(activity, "upd_dl") + " " + p + "%");
        } else if (downloadFailed) {
            blockBar.setVisibility(View.VISIBLE);
            blockBar.setProgress(0);
            blockStatus.setText(Lang.t(activity, "upd_fail"));
            blockBtn.setEnabled(true);
            blockBtn.setText(Lang.t(activity, "upd_fail"));
        } else if (waitingForPermission) {
            blockBar.setVisibility(View.INVISIBLE);
            blockSub.setText(Lang.t(activity, "upd_perm_sub"));
            blockStatus.setText(Lang.t(activity, "upd_perm"));
            blockBtn.setEnabled(true);
            blockBtn.setText(Lang.t(activity, "upd_perm"));
        } else if (downloadedFile != null && downloadedFile.exists()) {
            blockBar.setVisibility(View.VISIBLE);
            blockBar.setProgress(100);
            blockStatus.setText(Lang.t(activity, "upd_inst"));
            blockBtn.setEnabled(true);
            blockBtn.setText(Lang.t(activity, "upd_inst"));
        } else {
            blockBar.setVisibility(View.INVISIBLE);
            blockSub.setText(Lang.t(activity, "upd_sub"));
            blockStatus.setText("");
            blockBtn.setEnabled(true);
            blockBtn.setText(Lang.t(activity, "upd_btn"));
        }
    }
}
