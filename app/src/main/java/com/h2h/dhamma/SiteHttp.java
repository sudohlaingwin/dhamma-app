package com.h2h.dhamma;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// v13.57 (TLS resilience — the 2026-11-10 Redmi Note 14 Pro incident:
// his phone rejects dhammadownload.com's TLS certificate — a cert
// error in Chrome, while the site's current certificate is valid per
// the public CT log (Sectigo, issued 2026-07-03, good to 2027-01-17),
// so the refusal happens on his phone/network, not on the site — and
// the site answers plain HTTP 200 with no redirect. HTTPS stays the
// default everywhere; only when an HTTPS fetch to dhammadownload.com
// fails with an SSL error does the app retry the SAME request over
// plain http://, and remember that for the rest of the launch, so
// portraits, playback and downloads on this session use http too
// instead of failing one by one. No certificate is ever bypassed or
// trusted blindly: the http retry is a different, unencrypted
// transport, used only for this one public site's public content,
// only after HTTPS has actually failed. The flag is in-memory only —
// every launch tries HTTPS first again. The updater (GitHub) and
// every other host are untouched: isSiteUrl gates the rewrite to
// dhammadownload.com alone.
public class SiteHttp {

    // Set the first time an HTTPS fetch to the site fails with an SSL
    // error this launch; cleared on the next cold start (v13.00 true
    // exit guarantees a cold start), so a changed network re-probes
    // HTTPS instead of staying on http forever.
    public static volatile boolean tlsBroken = false;

    // The HTTP status of the most recent fetchBytes/fetchBytesCapped
    // call: 200 on success, the server's code on an HTTP error, 0
    // when the connection itself failed. Callers read it in their
    // catch block to classify the failure (TalkListCache.reasonKey
    // needs the code: >= 400 is a server error, not a network one).
    public static volatile int lastHttpCode = 0;

    public static boolean isSiteUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(java.util.Locale.US);
        return u.startsWith("https://www.dhammadownload.com/")
                || u.startsWith("https://dhammadownload.com/");
    }

    // https://[www.]dhammadownload.com/... -> http://[www.]dhammadownload.com/...
    // Any other URL (including local file paths) comes back unchanged.
    public static String downgrade(String url) {
        if (url == null) return null;
        if (url.startsWith("https://www.dhammadownload.com/"))
            return "http://" + url.substring("https://".length());
        if (url.startsWith("https://dhammadownload.com/"))
            return "http://" + url.substring("https://".length());
        return url;
    }

    // The URL to actually use for a site resource this launch: http
    // once TLS has proven broken, https otherwise. Non-site URLs and
    // local paths pass through untouched.
    public static String siteUrl(String url) {
        return (tlsBroken && isSiteUrl(url)) ? downgrade(url) : url;
    }

    // Fetch a whole response body: HTTPS first, one retry over plain
    // http when the HTTPS attempt dies with an SSL error. Any other
    // failure (DNS, timeout, connect, HTTP status) throws at once —
    // downgrading cannot help those, and a server error must not be
    // hidden behind a second request. Owns the connection lifecycle
    // (disconnect in a finally, v1.58/v1.66) so callers never leak a
    // socket on either attempt.
    public static byte[] fetchBytes(String urlStr, int connectMs, int readMs) throws IOException {
        String fetchUrl = siteUrl(urlStr);
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = null;
            InputStream in = null;
            try {
                conn = (HttpURLConnection) new URL(fetchUrl).openConnection();
                conn.setConnectTimeout(connectMs);
                conn.setReadTimeout(readMs);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                int code = conn.getResponseCode();
                lastHttpCode = code;
                if (code != HttpURLConnection.HTTP_OK)
                    throw new IOException("HTTP " + code);
                in = conn.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
                return baos.toByteArray();
            } catch (javax.net.ssl.SSLException ssl) {
                if (attempt == 0 && isSiteUrl(urlStr) && fetchUrl.startsWith("https://")) {
                    tlsBroken = true;
                    fetchUrl = downgrade(urlStr);
                    continue;
                }
                throw ssl;
            } finally {
                if (in != null) { try { in.close(); } catch (Exception ignored) {} }
                if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
            }
        }
        throw new IOException("fetch failed");
    }

    // Same as fetchBytes, but refuses an over-cap response before
    // reading it (v11.91: a misconfigured server must not OOM the
    // app) and aborts a read that grows past the cap. Portraits only.
    public static byte[] fetchBytesCapped(String urlStr, int connectMs, int readMs, int maxBytes) throws IOException {
        String fetchUrl = siteUrl(urlStr);
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = null;
            InputStream in = null;
            try {
                conn = (HttpURLConnection) new URL(fetchUrl).openConnection();
                conn.setConnectTimeout(connectMs);
                conn.setReadTimeout(readMs);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                int code = conn.getResponseCode();
                lastHttpCode = code;
                if (code != HttpURLConnection.HTTP_OK)
                    throw new IOException("HTTP " + code);
                int contentLen = conn.getContentLength();
                if (contentLen > maxBytes) throw new IOException("response too large");
                in = conn.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    baos.write(buf, 0, n);
                    if (baos.size() > maxBytes) throw new IOException("response too large");
                }
                return baos.toByteArray();
            } catch (javax.net.ssl.SSLException ssl) {
                if (attempt == 0 && isSiteUrl(urlStr) && fetchUrl.startsWith("https://")) {
                    tlsBroken = true;
                    fetchUrl = downgrade(urlStr);
                    continue;
                }
                throw ssl;
            } finally {
                if (in != null) { try { in.close(); } catch (Exception ignored) {} }
                if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
            }
        }
        throw new IOException("fetch failed");
    }
}
