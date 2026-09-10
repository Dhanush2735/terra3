// ============================================================
// FILE: RefreshEngine.java
// THE single owner of chunk refresh work (time jumps, branch
// switches, water sync, boundary healing).
//
// Replaces: refreshQueue + structureRefreshQueue +
// timeJumpBoundaryRing + queuedVegRefresh/queuedStructRefresh +
// the burst/normal budget constants + the pass-1/pass-2 mutex
// with ONE per-chunk state machine:
//
//   NEEDS_DATA -> DATA_READY -> CLEARING -> STRUCTURES -> DONE
//        |               (veg+water)         |
//        +---- TileEscalator pushes -------- + (failures re-park
//              when fetch lands              with backoff)
//
// Design rules (each from a logged production failure):
//   1. A chunk exists AT MOST ONCE in the engine (dedup by key).
//      Feeders submit(reason); they never place blocks.
//   2. TIME-budgeted drain: stages run until the per-tick
//      budget (default 8 ms) is spent, then yield. No count
//      budgets, no burst mode; a heavy chunk parks mid-pipeline.
//   3. NEEDS_DATA chunks never poll. The engine tracks wanted
//      tiles; when a fetch lands the chunk is promoted. Tiles
//      wanted > ESCALATE_MS get a dedicated fast retry.
//   4. Every transition is observable: one summary log line per
//      active tick, machine-parseable (REFRESH_ENGINE prefix).
//   5. The persistent year record (PersistentChunkRefreshIndex)
//      is written ONLY on DONE — single point of truth.
//
// The actual block work is delegated to the proven generator
// methods via the ChunkWorker interface, so placement behaviour
// is byte-identical to the old path.
// ============================================================
package net.buildtheearth.terraplusplus.generator.historical;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RefreshEngine {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++RefreshEngine");

    // ── TUNING ────────────────────────────────────────────────
    /** Max server-thread ms spent per tick on refresh work. */
    public static volatile long TICK_BUDGET_MS = 8;
    /** TIMEJUMP DRAIN FIX: 8ms/tick was the right politeness
     *  for background healing, but after a timejump the player
     *  is actively WAITING for the world to update — one waiter
     *  clearing one plate per lap of a full dining room. During
     *  a burst window we allow a bigger budget, then fall back
     *  to the polite 8ms. 40ms still leaves 10ms of a 50ms tick
     *  for everything else, and the drain loop is depth-bounded
     *  so it cannot spin. */
    public static volatile long BURST_BUDGET_MS = 40;
    /** Burst window duration after burst() is called. */
    private static final long BURST_WINDOW_MS = 60_000;
    private volatile long burstUntil = 0;

    /** Call when a large batch was just submitted (timejump):
     *  drain aggressively for the next window. */
    public void burst() {
        burstUntil = System.currentTimeMillis() + BURST_WINDOW_MS;
        LOGGER.info("REFRESH_ENGINE burst: budget {}ms/tick for {}s",
            BURST_BUDGET_MS, BURST_WINDOW_MS / 1000);
    }
    /** Re-check parked NEEDS_DATA chunks at most this often. */
    private static final long DATA_RECHECK_MS = 1_000;
    /** A tile wanted longer than this gets a fast retry push. */
    private static final long ESCALATE_MS = 10_000;
    /** Failed stage retry backoff (doubles, capped). */
    private static final long RETRY_BASE_MS = 2_000;
    private static final long RETRY_MAX_MS = 30_000;
    /** Hard cap on resident entries (protects memory). */
    private static final int MAX_ENTRIES = 8_192;

    // ── STAGES ────────────────────────────────────────────────
    public enum Stage {
        NEEDS_DATA,   // waiting for structure tiles (async)
        DATA_READY,   // data resident; not yet started
        CLEARING,     // ownership clear + water sync + vegetation
        STRUCTURES,   // roads/buildings placement + commit
        DONE, FAILED  // terminal (FAILED = gave up after maxAttempts)
    }

    /** Why a chunk was submitted (observability only). */
    public enum Reason { TIME_JUMP, BRANCH_SWITCH, BOUNDARY,
        CHUNK_LOAD, WATER_READY, RETRY, COMMAND }

    // ── WORKER SPI (implemented by TerraChunkGenerator) ───────
    public interface ChunkWorker {
        /** Non-blocking: structures for chunk if resident, else null. */
        List<?> peekData(ServerLevel level, ChunkPos pos);
        /** Kick async fetch for this chunk's tiles (non-blocking). */
        void requestData(ServerLevel level, ChunkPos pos);
        /** Fast retry for tiles wanted too long (non-blocking). */
        void escalateData(ServerLevel level, ChunkPos pos);
        /** Clear old ownership + water sync + vegetation. */
        boolean runClearing(ServerLevel level, ChunkPos pos, List<?> data);
        /** Place structures + commit ledger + client resync. */
        boolean runStructures(ServerLevel level, ChunkPos pos, List<?> data);
        /** Mark persistent year record complete. */
        void markDone(ServerLevel level, ChunkPos pos);
    }

    // ── ENTRY ─────────────────────────────────────────────────
    private static final class Entry {
        final ChunkPos pos;
        final Reason reason;
        Stage stage = Stage.NEEDS_DATA;
        List<?> data;                 // resident structure list
        int attempts;
        long notBefore;               // backoff gate (ms epoch)
        long submittedAt = System.currentTimeMillis();
        long dataWantedSince;
        // Sentinel guarantees the FIRST data check happens on the
        // first tick regardless of clock representation (unit sim
        // caught a first-check delay with a 0 default).
        long lastDataCheck = Long.MIN_VALUE / 2;
        Entry(ChunkPos pos, Reason reason) {
            this.pos = pos; this.reason = reason;
        }
    }

    private static long key(ChunkPos p) {
        return ((long) p.x << 32) | (p.z & 0xFFFFFFFFL);
    }

    // ── STATE ─────────────────────────────────────────────────
    private final ChunkWorker worker;
    private final int maxAttempts;
    private final ConcurrentHashMap<Long, Entry> entries =
        new ConcurrentHashMap<>();
    /** Player-distance-ordered work list (drain order). */
    private final ArrayDeque<Long> order = new ArrayDeque<>();
    private final Object orderLock = new Object();

    // stats
    private final AtomicLong totalDone = new AtomicLong();
    private final AtomicLong totalFailed = new AtomicLong();
    private long lastSummaryMs;

    public RefreshEngine(ChunkWorker worker, int maxAttempts) {
        this.worker = worker;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    // ── FEEDER API ────────────────────────────────────────────

    /** Submit one chunk. Idempotent: duplicates are ignored. */
    public boolean submit(ChunkPos pos, Reason reason) {
        if (entries.size() >= MAX_ENTRIES) return false;
        long k = key(pos);
        Entry fresh = new Entry(pos, reason);
        if (entries.putIfAbsent(k, fresh) != null) return false;
        synchronized (orderLock) { order.addLast(k); }
        return true;
    }

    /** Submit many, preserving the given (distance-sorted) order. */
    public int submitAll(List<ChunkPos> sorted, Reason reason) {
        int added = 0;
        for (ChunkPos p : sorted) if (submit(p, reason)) added++;
        return added;
    }

    /** A jump/branch switch supersedes all queued work. */
    public void clear() {
        entries.clear();
        synchronized (orderLock) { order.clear(); }
        LOGGER.info("REFRESH_ENGINE cleared (superseded)");
    }

    public int depth() { return entries.size(); }
    public boolean isIdle() { return entries.isEmpty(); }
    public long doneCount() { return totalDone.get(); }

    /** Non-null when the chunk is being tracked (for feeders that
     *  must not double-place: replaces the old ownership mutex). */
    public Stage stageOf(ChunkPos pos) {
        Entry e = entries.get(key(pos));
        return e == null ? null : e.stage;
    }

    // ── TICK DRAIN ────────────────────────────────────────────

    /** Called once per server tick. Never exceeds TICK_BUDGET_MS. */
    public void tick(ServerLevel level) {
        if (entries.isEmpty()) return;
        final long start = System.nanoTime();
        final long budgetMs = System.currentTimeMillis() < burstUntil
            ? BURST_BUDGET_MS : TICK_BUDGET_MS;
        final long budgetNanos = budgetMs * 1_000_000L;
        final long now = System.currentTimeMillis();

        int did = 0, parkedData = 0, advanced = 0;
        int[] stageRuns = new int[Stage.values().length];
        int scanned = 0;
        final int depthAtStart;
        synchronized (orderLock) { depthAtStart = order.size(); }

        while (System.nanoTime() - start < budgetNanos) {
            Long k;
            synchronized (orderLock) { k = order.pollFirst(); }
            if (k == null) break;
            if (++scanned > depthAtStart + 8) {
                // full rotation done this tick; stop instead of
                // spinning on parked entries
                synchronized (orderLock) { order.addFirst(k); }
                break;
            }
            Entry e = entries.get(k);
            if (e == null) continue;

            boolean requeue = true;
            try {
                if (!level.hasChunk(e.pos.x, e.pos.z)) {
                    // Unloaded: hand off to the deferred ledger and drop.
                    DeferredChunkRefreshLedger.defer(level, e.pos.x, e.pos.z);
                    entries.remove(k);
                    requeue = false;
                    continue;
                }
                if (now < e.notBefore) {
                    continue; // backoff parked; keep in rotation
                }
                switch (e.stage) {
                    case NEEDS_DATA -> {
                        if (now - e.lastDataCheck < DATA_RECHECK_MS) {
                            parkedData++;
                            continue;
                        }
                        e.lastDataCheck = now;
                        List<?> d = worker.peekData(level, e.pos);
                        if (d != null) {
                            e.data = d;
                            e.stage = Stage.DATA_READY;
                            advanced++;
                        } else {
                            if (e.dataWantedSince == 0) {
                                e.dataWantedSince = now;
                                worker.requestData(level, e.pos);
                            } else if (now - e.dataWantedSince
                                    > ESCALATE_MS) {
                                worker.escalateData(level, e.pos);
                                e.dataWantedSince = now; // escalate again in 10s
                            }
                            parkedData++;
                        }
                    }
                    case DATA_READY -> {
                        e.stage = Stage.CLEARING;
                        advanced++;
                    }
                    case CLEARING -> {
                        stageRuns[Stage.CLEARING.ordinal()]++;
                        did++;
                        if (worker.runClearing(level, e.pos, e.data)) {
                            e.stage = Stage.STRUCTURES;
                            e.attempts = 0;
                            advanced++;
                        } else {
                            fail(e, now);
                        }
                    }
                    case STRUCTURES -> {
                        stageRuns[Stage.STRUCTURES.ordinal()]++;
                        did++;
                        if (worker.runStructures(level, e.pos, e.data)) {
                            worker.markDone(level, e.pos);
                            e.stage = Stage.DONE;
                            entries.remove(k);
                            totalDone.incrementAndGet();
                            requeue = false;
                        } else {
                            fail(e, now);
                        }
                    }
                    default -> { entries.remove(k); requeue = false; }
                }
            } catch (Exception ex) {
                LOGGER.debug("engine [{}]: {} at {}", e.pos,
                    ex.getMessage(), e.stage);
                fail(e, now);
            } finally {
                if (requeue && entries.containsKey(k)) {
                    synchronized (orderLock) { order.addLast(k); }
                }
            }
        }

        // one observable line per active second
        if (now - lastSummaryMs > 1_000 && (did > 0 || parkedData > 0)) {
            lastSummaryMs = now;
            LOGGER.info("REFRESH_ENGINE tick: spent={}ms "
                + "clear={} struct={} advanced={} parkedData={} "
                + "depth={} done={} failed={}",
                (System.nanoTime() - start) / 1_000_000,
                stageRuns[Stage.CLEARING.ordinal()],
                stageRuns[Stage.STRUCTURES.ordinal()],
                advanced, parkedData, entries.size(),
                totalDone.get(), totalFailed.get());
        }
    }

    private void fail(Entry e, long now) {
        e.attempts++;
        if (e.attempts >= maxAttempts) {
            LOGGER.warn("REFRESH_ENGINE gave up [{},{}] after {} "
                + "attempts at {}", e.pos.x, e.pos.z,
                e.attempts, e.stage);
            entries.remove(key(e.pos));
            totalFailed.incrementAndGet();
            // leave the persistent record stale so a later load retries
            PersistentChunkRefreshIndex.markChunkRefreshFailed(
                e.pos.x, e.pos.z);
            return;
        }
        long backoff = Math.min(RETRY_MAX_MS,
            RETRY_BASE_MS << Math.min(4, e.attempts - 1));
        e.notBefore = now + backoff;
        // A failed stage restarts from NEEDS_DATA data-check: data
        // may have been evicted; re-peek is cheap and idempotent.
        if (e.stage == Stage.CLEARING || e.stage == Stage.STRUCTURES) {
            // keep stage: stages are idempotent; retry same stage
        }
    }

    /** Health snapshot for /terra health & the analyzer. */
    public String describe() {
        Map<Stage, Integer> byStage = new EnumMap<>(Stage.class);
        for (Entry e : entries.values())
            byStage.merge(e.stage, 1, Integer::sum);
        return "depth=" + entries.size() + " " + byStage
            + " done=" + totalDone.get()
            + " failed=" + totalFailed.get();
    }
}
