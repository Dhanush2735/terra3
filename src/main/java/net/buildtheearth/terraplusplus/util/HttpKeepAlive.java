// ============================================================
// KEEP-ALIVE FIX (2026-09-02) — the universal "hang-up tax".
//
// Every downloader called HttpURLConnection.disconnect() in its
// finally block. disconnect() closes the underlying SOCKET, so
// the next request to the same host pays a fresh TCP+TLS
// handshake (~600-900ms from IN to US/EU hosts). Measured on
// OvertureOsmIndex when we fixed it there: cold multi-request
// fetches roughly halved.
//
// The polite way to finish with a connection is to fully drain
// and close its streams — the JVM then returns the socket to
// its internal keep-alive pool, and the next request to that
// host skips the handshake entirely.
//
// This helper replaces all 16 disconnect() call sites:
//     finally { HttpKeepAlive.release(conn); }
// It never throws, tolerates null, and drains both the normal
// and the error stream (an unread error body would otherwise
// poison the pooled connection).
// ============================================================
package net.buildtheearth.terraplusplus.util;

import java.io.InputStream;
import java.net.HttpURLConnection;

public final class HttpKeepAlive {

    private HttpKeepAlive() {}

    private static final byte[] SINK = new byte[8192];
    /** Don't drain more than this to salvage a socket; past it,
     *  killing the connection is cheaper than reading the rest. */
    private static final long MAX_DRAIN = 256 * 1024;

    /** Finish with a connection while keeping its socket
     *  reusable. Call from finally blocks instead of
     *  conn.disconnect(). */
    public static void release(HttpURLConnection conn) {
        if (conn == null) return;
        boolean reusable = drain(safeInputStream(conn))
                         & drain(conn.getErrorStream());
        if (!reusable) {
            // Stream too large/broken to drain — abandoning the
            // socket is correct here.
            try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private static InputStream safeInputStream(HttpURLConnection c) {
        try { return c.getInputStream(); }
        catch (Exception e) { return null; }
    }

    /** @return true if the stream ended (socket reusable). */
    private static boolean drain(InputStream in) {
        if (in == null) return true;
        try (InputStream is = in) {
            long total = 0;
            int n;
            while ((n = is.read(SINK)) != -1) {
                total += n;
                if (total > MAX_DRAIN) return false;
            }
            return true;
        } catch (Exception e) {
            // Already-closed / broken stream: nothing to salvage
            // but also nothing left open.
            return true;
        }
    }
}
