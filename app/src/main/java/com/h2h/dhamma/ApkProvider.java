package com.h2h.dhamma;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

// v12.95: minimal provider that hands the downloaded update APK to the
// package installer. No androidx FileProvider in this no-Gradle app, so
// this serves the one fixed file (getExternalFilesDir/Downloads/
// dhamma-update.apk) for any URI under this authority.
public class ApkProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                UpdateChecker.APK_NAME);
        if (!f.exists()) throw new FileNotFoundException("update apk missing");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] sa, String so) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] sa) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] sa) { return 0; }
}
