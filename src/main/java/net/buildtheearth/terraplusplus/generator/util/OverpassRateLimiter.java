// ============================================================
// FILE: OverpassRateLimiter.java
// A token bucket rate limiter for Overpass API endpoints.
// Replaces the synchronized(rateLock)+sleep pattern used in
// OsmWaterIndex, OhmWaterIndex, OsmStructureIndex, and
// OhmStructureIndex.
//
// Design:
//   - Each endpoint gets its own OverpassRateLimiter instance
//   - Tokens refill at a fixed interval (refillIntervalMs)
//   - Up to burstCapacity tokens can accumulate
//   - acquire() blocks only as long as needed for next token
//   - Multiple threads calling acquire() simultaneously are
//     each given a separate token slot — no pile-up
//   - Thread-safe via a single ReentrantLock
// ============================================================
package net.buildtheearth.terraplusplus.generator.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class OverpassRateLimiter {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++RateLimit");

    // Public endpoints are shared by water and structure indexes. One global
    // bucket per endpoint prevents those independent systems from each sending
    // their own "1 request/sec" stream to the same Overpass server.
    private static final ConcurrentHashMap<String, OverpassRateLimiter> SHARED =
        new ConcurrentHashMap<>();

    // ── FIELDS ────────────────────────────────────────────────
    private final String  name;
    private final long    refillIntervalMs;
    private final int     burstCapacity;

    private final ReentrantLock lock =
        new ReentrantLock();
    private final Condition     tokenAvailable =
        lock.newCondition();
    // Public Overpass services are sensitive to overlapping long requests.
    // The permit is shared whenever the limiter is shared by endpoint URL.
    private final Semaphore inFlight = new Semaphore(1, true);

    // Current token count — may be fractional internally
    // but exposed as whole tokens
    private double tokens;

    // When we last refilled
    private long lastRefillMs;
    // Endpoint-level cooldown after a rate-limit or gateway overload response.
    private long cooldownUntilMs;

    // ── CONSTRUCTOR ───────────────────────────────────────────
    /**
     * @param name             Human-readable name for logging
     * @param refillIntervalMs How many ms between each token refill.
     *                         e.g. 1000 = 1 token per second
     * @param burstCapacity    Max tokens that can accumulate.
     *                         e.g. 2 = burst of 2 requests allowed
     *                         before rate limiting kicks in
     */
    public OverpassRateLimiter(String name,
            long refillIntervalMs,
            int burstCapacity) {
        this.name             = name;
        this.refillIntervalMs = refillIntervalMs;
        this.burstCapacity    = burstCapacity;

        // Start full — allows immediate burst on cold start
        this.tokens       = burstCapacity;
        this.lastRefillMs = System.currentTimeMillis();
    }

    // ── ACQUIRE ───────────────────────────────────────────────
    /**
     * Acquire one token, blocking until one is available.
     * Multiple threads calling this simultaneously will each
     * get their own token slot without piling up.
     *
     * Thread A needs token at t=0  → gets it immediately (burst)
     * Thread B needs token at t=0  → gets it immediately (burst)
     * Thread C needs token at t=0  → waits ~1000ms for refill
     * Thread D needs token at t=0  → waits ~2000ms for refill
     *
     * @throws InterruptedException if thread is interrupted while
     *                              waiting for a token
     */
    public void acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                long now = System.currentTimeMillis();
                if (now < cooldownUntilMs) {
                    tokenAvailable.await(cooldownUntilMs - now,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                    continue;
                }
                refill();

                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return;
                }

                // Calculate exact wait time until next token
                // tokens is in range [0, 1) here
                // We need (1 - tokens) more tokens
                // Each ms produces 1.0/refillIntervalMs tokens
                double needed = 1.0 - tokens;
                long waitMs = (long) Math.ceil(
                    needed * refillIntervalMs);

                // Wait — releases lock so other threads
                // can also call acquire() simultaneously
                // and compute their own wait slots
                boolean elapsed = tokenAvailable
                    .await(waitMs,
                        java.util.concurrent.TimeUnit
                            .MILLISECONDS);
                // Loop back — refill() will add tokens
                // based on actual elapsed time
            }
        } finally {
            lock.unlock();
        }
    }

    /** Acquire the single active-request permit for this endpoint. */
    public void acquireInFlight() throws InterruptedException {
        inFlight.acquire();
    }

    /** Release the endpoint permit after the HTTP request has completed. */
    public void releaseInFlight() {
        inFlight.release();
    }

    /**
     * Attempt to acquire one token without blocking.
     * Returns true if token was available, false otherwise.
     * Useful for checking if a request can proceed immediately.
     */
    public boolean tryAcquireImmediate() {
        lock.lock();
        try {
            if (System.currentTimeMillis() < cooldownUntilMs) return false;
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reset the limiter — called when switching endpoints or
     * after a long pause so the burst capacity is restored.
     */
    public void reset() {
        lock.lock();
        try {
            tokens       = burstCapacity;
            lastRefillMs = System.currentTimeMillis();
            cooldownUntilMs = 0L;
            tokenAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Penalize an endpoint after HTTP 429/504. Unlike reset(), this removes
     * burst capacity and postpones the next request instead of immediately
     * sending another burst to an overloaded public service.
     */
    public void penalize(long cooldownMs) {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            cooldownUntilMs = Math.max(cooldownUntilMs, now + Math.max(0L, cooldownMs));
            tokens = 0.0;
            lastRefillMs = cooldownUntilMs;
            tokenAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Return how many ms until the next token will be available.
     * Returns 0 if a token is available now.
     * Does not block.
     */
    public long msUntilNextToken() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            if (now < cooldownUntilMs) return cooldownUntilMs - now;
            refill();
            if (tokens >= 1.0) return 0;
            double needed = 1.0 - tokens;
            return (long) Math.ceil(
                needed * refillIntervalMs);
        } finally {
            lock.unlock();
        }
    }

    // ── REFILL ────────────────────────────────────────────────
    /**
     * Add tokens based on time elapsed since last refill.
     * Called inside the lock — never call from outside.
     */
    private void refill() {
        long now     = System.currentTimeMillis();
        long elapsed = now - lastRefillMs;
        if (elapsed <= 0) return;

        double newTokens = (double) elapsed
            / refillIntervalMs;

        tokens = Math.min(
            burstCapacity,
            tokens + newTokens);

        lastRefillMs = now;
    }

    // ── CONSTANTS FOR EACH ENDPOINT ───────────────────────────
    // Convenience factory methods so each index
    // doesn't need to remember the values.

    /**
     * One limiter for one OSM Overpass endpoint.
     * 1 req/sec, burst of 2.
     * OsmWaterIndex and OsmStructureIndex create one
     * instance per endpoint.
     */
    public static OverpassRateLimiter forOsmEndpoint(
            String endpointName) {
        String key = "OSM/" + endpointName;
        return SHARED.computeIfAbsent(key,
            ignored -> new OverpassRateLimiter(key, 1000L, 1));
    }

    /**
     * Limiter for the single OHM Water Overpass endpoint.
     * 1 req/2sec, burst of 2.
     */
    public static OverpassRateLimiter forOhmWater() {
        return sharedOhmLimiter();
    }

    /**
     * Limiter for the single OHM Structure Overpass endpoint.
     * 1 req/1.5sec, burst of 2.
     */
    public static OverpassRateLimiter forOhmStructures() {
        return sharedOhmLimiter();
    }

    private static OverpassRateLimiter sharedOhmLimiter() {
        return SHARED.computeIfAbsent("OHM/public",
            ignored -> new OverpassRateLimiter("OHM/public", 2000L, 1));
    }

    // ── DIAGNOSTICS ───────────────────────────────────────────
    public String getName()  { return name; }

    public double getTokens() {
        lock.lock();
        try { refill(); return tokens; }
        finally { lock.unlock(); }
    }

    @Override
    public String toString() {
        return String.format(
            "OverpassRateLimiter[%s tokens=%.2f "
            + "interval=%dms burst=%d]",
            name, getTokens(),
            refillIntervalMs, burstCapacity);
    }
}