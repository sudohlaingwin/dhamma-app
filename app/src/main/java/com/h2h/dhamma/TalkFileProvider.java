package com.h2h.dhamma;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

// v15.48 (slice 1954): serves one downloaded talk file to the
// Android share sheet — the library's rows are real .mp3 files in
// Downloads/Dhamma, and a file:// URI would throw
// FileUriExposedException on API 24+, so the file crosses to the
// receiving app as a content:// URI with a one-shot read grant.
// No androidx FileProvider in this no-Gradle app (the ApkProvider
// precedent): the URI carries only the file NAME, and the file is
// resolved through the TalkFileShare pure core — canonical-path
// containment inside Downloads/Dhamma, .mp3 only, must exist —
// so a crafted URI can never escape the download folder. The name
// comes from the URI's ENCODED path (last segment, decoded by the
// core), never Uri's own decoding, so the core is the single
// authority on what a name means.
public class TalkFileProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = null;
        try {
            String raw = uri == null ? null : uri.getEncodedPath();
            if (raw != null) {
                int cut = raw.lastIndexOf('/');
                String seg = cut < 0 ? raw : raw.substring(cut + 1);
                name = TalkFileShare.decodeSegment(seg);
            }
        } catch (Exception ignored) {}
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "Dhamma");
        File f = TalkFileShare.resolve(dir, name);
        if (f == null) throw new FileNotFoundException("talk file not found");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "audio/mpeg"; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] sa, String so) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] sa) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] sa) { return 0; }
}
