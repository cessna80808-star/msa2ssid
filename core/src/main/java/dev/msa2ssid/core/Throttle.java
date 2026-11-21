package dev.msa2ssid.core;

import java.util.function.Consumer;

/**
 * Paces account additions. A pasted list signs every account in back to back, and
 * Minecraft's login endpoint answers such a burst with HTTP 429 — which the import
 * used to report as a rejected token. Here each line waits its turn, and a 429
 * backs off and retries instead of losing the rest of the list.
 */
public final class Throttle {

    /** Gap between two accounts: a burst is what trips the limit. */
    public static final long GAP_MS = 2000;

    /** Retries for a rate-limited line before the import stops and asks for a pause. */
    public static final int MAX_RETRIES = 4;

    private static final long FIRST_WAIT_MS = 5000;
    private static final long MAX_WAIT_MS = 60000;

    private Throttle() {
    }

    /** Waits the normal gap between two lines. */
    public static void gap() throws InterruptedException {
        Thread.sleep(GAP_MS);
    }

    /**
     * Signs one line in, waiting and retrying while the server asks for fewer
     * requests. {@code progress} gets the countdown, so a long pause is visible.
     */
    public static Auth.TokenLogin login(String clientId, String token,
                                        Consumer<String> progress) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                return Auth.loginWithToken(clientId, token);
            } catch (Exception e) {
                if (attempt >= MAX_RETRIES || !rateLimited(e)) throw e;
                wait(waitMillis(attempt, retryAfter(e)), progress);
            }
        }
    }

    /** True when a failure means "slow down", not "this token is dead". */
    public static boolean rateLimited(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof Http.ApiException a && (a.status == 429 || a.status == 503)) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    /** The server's Retry-After when it sent one, otherwise 5s, 10s, 20s, 40s. */
    public static long waitMillis(int attempt, long retryAfterSeconds) {
        if (retryAfterSeconds > 0) return Math.min(MAX_WAIT_MS, retryAfterSeconds * 1000L);
        return Math.min(MAX_WAIT_MS, FIRST_WAIT_MS << Math.max(0, Math.min(attempt, 3)));
    }

    /** The Retry-After of the first failure that carries one. */
    public static long retryAfter(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof Http.ApiException a) return a.retryAfterSeconds;
            if (t.getCause() == t) break;
        }
        return 0;
    }

    /** Sleeps a second at a time so the status line can show the countdown. */
    private static void wait(long millis, Consumer<String> progress) throws InterruptedException {
        for (long left = millis; left > 0; left -= 1000) {
            if (progress != null) {
                progress.accept("rate limited, retrying in " + ((left + 999) / 1000) + "s");
            }
            Thread.sleep(Math.min(1000, left));
        }
    }
}
