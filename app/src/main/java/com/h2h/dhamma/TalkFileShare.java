package com.h2h.dhamma;

// v15.48 (slice 1954): "Share the audio file" — the pure core
// behind the library's per-row file share: the library's rows are
// real .mp3 files in Downloads/Dhamma, and the v6.53 share sends
// only the talk's text (title + original dhammadownload.com link
// + credit), so sending the actual file to another app needed a
// content:// URI (a file:// URI is refused on API 24+). The URI
// carries only the file NAME; this core decides which names may
// be served, builds the URI string, and resolves a name back to
// the file inside the download folder. Pure set math + path math
// only: the Provider owns the Android side (ContentProvider,
// ParcelFileDescriptor) and the Activity owns the menu offer, the
// intent and the toast; the core never touches Android, prefs, or
// the queue. The resolve gate is canonical-path containment, so a
// crafted URI can never escape Downloads/Dhamma — not even
// through a symlink planted inside it.
public class TalkFileShare {
    public static final String AUTHORITY = "com.h2h.dhamma.talkfileprovider";
    private static final String SCHEME = "content://";

    // A name is shareable when it is one plain file name ending
    // in .mp3 (any case): no separator (a sub-path would leave the
    // folder), not "." / ".." (the folder itself / its parent).
    // Null or blank is never shareable, never a crash.
    public static boolean isSafeName(String name) {
        if (name == null || name.length() == 0) return false;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return false;
        if (".".equals(name) || "..".equals(name)) return false;
        if (name.length() < 4) return false;
        String tail = name.substring(name.length() - 4);
        return ".mp3".equalsIgnoreCase(tail);
    }

    // Percent-encode one URI path segment (UTF-8 bytes; only the
    // unreserved set stays bare). The file name is the segment, so
    // a '#' or '?' inside a talk name must not become a fragment
    // or query delimiter, and a '%' must not start a false escape.
    // Null encodes to null; the empty string encodes to itself.
    public static String encodeSegment(String s) {
        if (s == null) return null;
        try {
            byte[] b = s.getBytes("UTF-8");
            StringBuilder out = new StringBuilder(b.length);
            for (int i = 0; i < b.length; i++) {
                int c = b[i] & 0xFF;
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9')
                        || c == '-' || c == '.' || c == '_' || c == '~') {
                    out.append((char) c);
                } else {
                    out.append('%');
                    out.append("0123456789ABCDEF".charAt((c >> 4) & 0xF));
                    out.append("0123456789ABCDEF".charAt(c & 0xF));
                }
            }
            return out.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // Reverse encodeSegment: %XX bytes reassemble to UTF-8 text.
    // A malformed escape (a '%' not followed by two hex digits),
    // a raw non-ASCII char (the encoder percent-encodes every
    // such byte, so a raw one means the segment did not come from
    // encodeSegment), or bytes that are not valid UTF-8 decode to
    // null — the caller treats it as "no such file", never a
    // crash and never a guessed name. Null decodes to null.
    public static String decodeSegment(String s) {
        if (s == null) return null;
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '%') {
                    if (i + 2 >= s.length()) return null;
                    int hi = Character.digit(s.charAt(i + 1), 16);
                    int lo = Character.digit(s.charAt(i + 2), 16);
                    if (hi < 0 || lo < 0) return null;
                    out.write((hi << 4) | lo);
                    i += 2;
                } else if (ch > 127) {
                    return null;
                } else {
                    out.write((byte) ch);
                }
            }
            java.nio.charset.CharsetDecoder dec = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return dec.decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString();
        } catch (Exception e) {
            return null;
        }
    }

    // The share URI for a file name, or null when the name is not
    // shareable — the Activity toasts instead of sending a URI
    // that could never resolve.
    public static String uriStringFor(String name) {
        if (!isSafeName(name)) return null;
        String enc = encodeSegment(name);
        if (enc == null) return null;
        return SCHEME + AUTHORITY + "/" + enc;
    }

    // v15.48 (slice 1955): the share URI for a file that resolves
    // inside the dir, or null when it must not be served — the
    // Room drawer's one-call share: resolve() (canonical-path
    // containment + isFile, the 1954 gate) then uriStringFor of
    // the resolved file's own name. A null dir or name reads as
    // nothing to serve, never a crash and never a guessed URI.
    public static String shareUriFor(java.io.File dir, String name) {
        java.io.File f = resolve(dir, name);
        if (f == null) return null;
        return uriStringFor(f.getName());
    }

    // The file a URI name resolves to inside the download folder,
    // or null when it must not be served: an unsafe name, a
    // folder that cannot be read, a name whose canonical path
    // escapes the folder (a ".." smuggled past the name gate, or
    // a symlink inside the folder pointing out of it), or a name
    // that is not an existing file (a directory, or nothing there).
    // The folder itself is never served. The inputs are never
    // mutated; a null folder or name reads as nothing to serve.
    public static java.io.File resolve(java.io.File dir, String name) {
        if (dir == null || !isSafeName(name)) return null;
        try {
            java.io.File f = new java.io.File(dir, name);
            String cDir = dir.getCanonicalPath();
            String cFile = f.getCanonicalPath();
            if (!cFile.startsWith(cDir + java.io.File.separator)) return null;
            if (!f.isFile()) return null;
            return f;
        } catch (Exception e) {
            return null;
        }
    }
}
