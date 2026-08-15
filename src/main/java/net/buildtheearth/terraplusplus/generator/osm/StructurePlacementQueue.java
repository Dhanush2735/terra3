// ============================================================
// FILE: StructurePlacementQueue.java
// ============================================================
package net.buildtheearth.terraplusplus.generator.osm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe queue for deferred structure placement.
 *
 * <p>Holds completed structure data paired with chunk coordinates.
 * The StructureTickHandler consumes entries each server tick.</p>
 *
 * <p>Thread safety contract:
 * <ul>
 *   <li>{@link #enqueue} may be called from any background thread.</li>
 *   <li>{@link #poll}, {@link #requeue}, {@link #requeuePriority},
 *       and {@link #markPlaced} are called from the server tick thread.</li>
 *   <li>{@link #clear} must only be called when the tick handler is stopped.</li>
 * </ul>
 * </p>
 */
public class StructurePlacementQueue {

    private static final Logger LOGGER = LogManager.getLogger("Terra++PlaceQ");

    private static final int MAX_QUEUE_SIZE = 1000;
    private static final int MAX_DEFERRED   = 5000;
    private static final int MAX_ATTEMPTS   = 20;

    // ================================================================
    // INNER TYPES
    // ================================================================

    /**
     * A chunk that has structure data ready and is waiting to be placed.
     */
    public static class PlacementEntry {
        public final int    chunkX;
        public final int    chunkZ;
        public final double tileLat;
        public final double tileLon;
        public final long   queuedTimeMs;

        /**
         * Immutable snapshot of structures to place.
         * Defensive copy is taken at construction — callers may safely
         * modify their list after calling enqueue().
         */
        public final List<OsmStructureIndex.OsmStructure> structures;

        /** Incremented when a placement must retry due to chunk/world state. */
        public int placementAttempts;
        /** Next structure index for bounded multi-tick placement batches. */
        public int nextStructureIndex;

        public PlacementEntry(int chunkX, int chunkZ,
                              List<OsmStructureIndex.OsmStructure> structures,
                              double tileLat, double tileLon) {
            this.chunkX       = chunkX;
            this.chunkZ       = chunkZ;
            this.tileLat      = tileLat;
            this.tileLon      = tileLon;
            this.queuedTimeMs = System.currentTimeMillis();
            this.structures   = List.copyOf(structures); // immutable defensive copy
            this.placementAttempts = 0;
            this.nextStructureIndex = 0;
        }
    }

    /**
     * Lightweight record kept for chunks that were unloaded before
     * their structures could be placed. When the chunk generates again,
     * the generator should call {@link #checkDeferred} and use the cached
     * tile coordinates to re-fetch structure data.
     */
    public static class DeferredEntry {
        public final int    chunkX;
        public final int    chunkZ;
        public final double tileLat;
        public final double tileLon;
        public final long   deferredTimeMs;

        public DeferredEntry(int chunkX, int chunkZ, double tileLat, double tileLon) {
            this.chunkX        = chunkX;
            this.chunkZ        = chunkZ;
            this.tileLat       = tileLat;
            this.tileLon       = tileLon;
            this.deferredTimeMs = System.currentTimeMillis();
        }
    }

    // ================================================================
    // STATE
    // ================================================================

    /**
     * Primary placement queue.
     * ConcurrentLinkedDeque is used (instead of ConcurrentLinkedQueue)
     * so that {@link #requeuePriority} can insert at the front in O(1).
     * All normal operations (offer/poll) use the tail, preserving FIFO
     * for standard entries.
     */
    private final ConcurrentLinkedDeque<PlacementEntry> activeQueue
            = new ConcurrentLinkedDeque<>();

    /**
     * O(1) size counter — ConcurrentLinkedDeque.size() is O(n)
     * and must never be called in a hot path.
     */
    private final AtomicInteger queueSize = new AtomicInteger(0);

    /**
     * Deferred chunks: key → entry.
     * Entries here will be picked up the next time the chunk generates.
     */
    private final ConcurrentHashMap<Long, DeferredEntry> deferredMap
            = new ConcurrentHashMap<>();

    /**
     * Insertion-order key log for O(1) deferred eviction.
     * Kept in sync with deferredMap: a key is added to the tail
     * when inserted and removed from the head when evicting.
     */
    private final ConcurrentLinkedDeque<Long> deferredInsertOrder
            = new ConcurrentLinkedDeque<>();

    /**
     * Guards against duplicate entries in activeQueue.
     * A key is present while the entry lives inside activeQueue
     * and is removed in poll() / markPlaced() / when moved to deferred.
     */
    private final ConcurrentHashMap<Long, Boolean> queuedChunks
            = new ConcurrentHashMap<>();

    // Lifetime counters — monotonically increasing, never reset
    private final AtomicInteger totalEnqueued = new AtomicInteger(0);
    private final AtomicInteger totalPlaced   = new AtomicInteger(0);
    private final AtomicInteger totalDeferred = new AtomicInteger(0);
    private final AtomicInteger totalDropped  = new AtomicInteger(0);

    // ================================================================
    // ENQUEUE — called from background threads
    // ================================================================

    /**
     * Add a chunk to the active placement queue.
     *
     * <p>If the queue is at capacity, the oldest entry is evicted and
     * a warning is logged. If this chunk is already queued, the call
     * is a no-op. Any previously deferred record for this chunk is
     * cleared because fresh data has arrived.</p>
     *
     * @param chunkX     chunk X coordinate
     * @param chunkZ     chunk Z coordinate
     * @param structures non-null, non-empty list of structures to place
     * @param tileLat    OSM tile latitude  (used if the entry later becomes deferred)
     * @param tileLon    OSM tile longitude (used if the entry later becomes deferred)
     */
    public void enqueue(int chunkX, int chunkZ,
                        List<OsmStructureIndex.OsmStructure> structures,
                        double tileLat, double tileLon) {

        if (structures == null || structures.isEmpty()) return;

        // ── Capacity enforcement ──────────────────────────────────────
        // queueSize is O(1); ConcurrentLinkedDeque.size() would be O(n).
        while (queueSize.get() >= MAX_QUEUE_SIZE) {
            PlacementEntry dropped = activeQueue.pollFirst();
            if (dropped == null) break; // queue emptied concurrently — stop

            long droppedKey = chunkKey(dropped.chunkX, dropped.chunkZ);
            queuedChunks.remove(droppedKey);
            queueSize.decrementAndGet();
            totalDropped.incrementAndGet();
            LOGGER.warn("[PlaceQ] Queue full — dropped chunk [{},{}] after {} attempts",
                    dropped.chunkX, dropped.chunkZ, dropped.placementAttempts);
        }

        // ── Deduplication ─────────────────────────────────────────────
        long key = chunkKey(chunkX, chunkZ);
        if (queuedChunks.putIfAbsent(key, Boolean.TRUE) != null) return;

        // ── Enqueue ───────────────────────────────────────────────────
        PlacementEntry entry = new PlacementEntry(chunkX, chunkZ, structures, tileLat, tileLon);
        activeQueue.offerLast(entry);
        queueSize.incrementAndGet();
        totalEnqueued.incrementAndGet();

        // Fresh data arrived — discard any stale deferred record
        if (deferredMap.remove(key) != null) {
            LOGGER.debug("[PlaceQ] Cleared deferred record for chunk [{},{}]", chunkX, chunkZ);
        }

        LOGGER.debug("[PlaceQ] Enqueued chunk [{},{}] with {} structures",
                chunkX, chunkZ, structures.size());
    }

    // ================================================================
    // POLL — called from StructureTickHandler each tick
    // ================================================================

    /**
     * Retrieve and remove the next entry from the front of the queue.
     *
     * @return the next {@link PlacementEntry}, or {@code null} if the queue is empty
     */
    public PlacementEntry poll() {
        PlacementEntry entry = activeQueue.pollFirst();
        if (entry != null) {
            queuedChunks.remove(chunkKey(entry.chunkX, entry.chunkZ));
            queueSize.decrementAndGet();
        }
        return entry;
    }

    /**
     * Continue an oversized successful placement on a later tick. This is not
     * a failure, so it must not consume placementAttempts or become deferred.
     */
    public void requeueContinuation(PlacementEntry entry) {
        long key = chunkKey(entry.chunkX, entry.chunkZ);
        if (queuedChunks.putIfAbsent(key, Boolean.TRUE) != null) return;
        activeQueue.offerLast(entry);
        queueSize.incrementAndGet();
    }

    // ================================================================
    // REQUEUE — called from StructureTickHandler when chunk not loaded
    // ================================================================

    /**
     * Re-queue an entry at the <em>back</em> of the queue (normal priority).
     *
     * <p>Called when a chunk was not yet loaded at placement time.
     * After {@code MAX_ATTEMPTS} failures the entry is moved to the
     * deferred map instead.</p>
     *
     * @param entry the entry returned by a previous {@link #poll()} call
     */
    public void requeue(PlacementEntry entry) {
        entry.placementAttempts++;
        if (entry.placementAttempts > MAX_ATTEMPTS) {
            LOGGER.debug("[PlaceQ] Max attempts reached for chunk [{},{}] — deferring",
                    entry.chunkX, entry.chunkZ);
            addDeferred(entry.chunkX, entry.chunkZ, entry.tileLat, entry.tileLon);
            return;
        }

        long key = chunkKey(entry.chunkX, entry.chunkZ);
        // Guard against the (unlikely) case where two holders of the same
        // entry reference both call requeue() concurrently.
        if (queuedChunks.putIfAbsent(key, Boolean.TRUE) != null) return;

        activeQueue.offerLast(entry);   // normal tail — FIFO order preserved
        queueSize.incrementAndGet();
    }

    /**
     * Re-queue an entry at the <em>front</em> of the queue (high priority).
     *
     * <p>Used by StructureTickHandler when a previously-waiting chunk
     * becomes loaded and should be processed in the next tick cycle
     * ahead of newly-arrived work.</p>
     *
     * @param entry the entry returned by a previous {@link #poll()} call
     */
    public void requeuePriority(PlacementEntry entry) {
        entry.placementAttempts++;
        if (entry.placementAttempts > MAX_ATTEMPTS) {
            LOGGER.debug("[PlaceQ] Max attempts reached for chunk [{},{}] — deferring",
                    entry.chunkX, entry.chunkZ);
            addDeferred(entry.chunkX, entry.chunkZ, entry.tileLat, entry.tileLon);
            return;
        }

        long key = chunkKey(entry.chunkX, entry.chunkZ);
        if (queuedChunks.putIfAbsent(key, Boolean.TRUE) != null) return;

        activeQueue.offerFirst(entry);  // ✅ true front insertion — ConcurrentLinkedDeque
        queueSize.incrementAndGet();
    }

    /**
     * Mark an entry as successfully placed.
     * Must be called by the tick handler after placement succeeds
     * so that lifetime counters stay accurate.
     *
     * @param entry the entry that was just placed
     */
    public void markPlaced(PlacementEntry entry) {
        // queuedChunks was already cleaned up in poll(); this is a safety net.
        queuedChunks.remove(chunkKey(entry.chunkX, entry.chunkZ));
        totalPlaced.incrementAndGet();
    }

    /** Server-thread time-jump handoff: discard old-year async entries so the
     * historical transaction is the sole writer for stale loaded chunks. */
    public int discardActiveForTimeJump() {
        int discarded = 0;
        while (poll() != null) discarded++;
        if (discarded > 0)
            LOGGER.info("[PlaceQ] Discarded {} pre-jump active entries", discarded);
        return discarded;
    }

    // ================================================================
    // DEFERRED — for chunks that exhausted placement attempts
    // ================================================================

    /**
     * Record that a chunk needs structures placed the next time it
     * generates. Evicts the oldest deferred entry if the map is full.
     *
     * @param chunkX  chunk X coordinate
     * @param chunkZ  chunk Z coordinate
     * @param tileLat OSM tile latitude
     * @param tileLon OSM tile longitude
     */
    public void addDeferred(int chunkX, int chunkZ, double tileLat, double tileLon) {
        long key = chunkKey(chunkX, chunkZ);

        // putIfAbsent — don't reset the timestamp if already deferred
        DeferredEntry newEntry = new DeferredEntry(chunkX, chunkZ, tileLat, tileLon);
        if (deferredMap.putIfAbsent(key, newEntry) != null) return; // already present

        deferredInsertOrder.addLast(key);
        totalDeferred.incrementAndGet();

        // ── Capacity enforcement — O(1) eviction via insertion-order deque ──
        while (deferredMap.size() > MAX_DEFERRED) {
            Long oldest = deferredInsertOrder.pollFirst();
            if (oldest == null) break; // insertion-order deque empty — stop
            DeferredEntry evicted = deferredMap.remove(oldest);
            if (evicted != null) {
                LOGGER.debug("[PlaceQ] Deferred map full — evicted chunk [{},{}]",
                        evicted.chunkX, evicted.chunkZ);
            }
        }
    }

    /**
     * Check whether a chunk has a deferred record waiting.
     *
     * <p>Called during chunk generation. If a record exists it is
     * <em>removed</em> and returned so the generator can use the tile
     * coordinates to request structure data again.</p>
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return the {@link DeferredEntry}, or {@code null} if none exists
     */
    public DeferredEntry checkDeferred(int chunkX, int chunkZ) {
        // deferredInsertOrder is not updated here — the key will simply
        // be a no-op when the eviction loop later tries to remove it.
        return deferredMap.remove(chunkKey(chunkX, chunkZ));
    }

    // ================================================================
    // QUERIES
    // ================================================================

    /**
     * Returns {@code true} if a placement entry for this chunk is
     * currently sitting in the active queue.
     */
    public boolean isQueued(int chunkX, int chunkZ) {
        return queuedChunks.containsKey(chunkKey(chunkX, chunkZ));
    }

    /** @return number of entries currently in the active queue — O(1) */
    public int getActiveQueueSize() { return queueSize.get(); }

    /** @return number of entries in the deferred map */
    public int getDeferredSize() { return deferredMap.size(); }

    /** @return lifetime count of successfully enqueued entries */
    public int getTotalEnqueued() { return totalEnqueued.get(); }

    /** @return lifetime count of successfully placed entries */
    public int getTotalPlaced() { return totalPlaced.get(); }

    /** @return lifetime count of entries moved to the deferred map */
    public int getTotalDeferred() { return totalDeferred.get(); }

    /** @return lifetime count of entries forcibly dropped due to queue overflow */
    public int getTotalDropped() { return totalDropped.get(); }

    /** @return {@code true} if the active queue contains no entries */
    public boolean isEmpty() { return queueSize.get() == 0; }

    // ================================================================
    // LIFECYCLE
    // ================================================================

    /**
     * Clear all queues and reset tracking state.
     *
     * <p><strong>Must only be called when the tick handler is fully
     * stopped.</strong> Calling this while tick processing is active
     * will produce inconsistent state.</p>
     */
    public void clear() {
        activeQueue.clear();
        deferredMap.clear();
        deferredInsertOrder.clear();
        queuedChunks.clear();
        queueSize.set(0);
        // Lifetime counters (totalEnqueued etc.) are intentionally preserved
        // so that statistics remain meaningful across world reloads.
    }

    // ================================================================
    // UTILITY
    // ================================================================

    /**
     * Encode a chunk coordinate pair into a single {@code long} key.
     * Visible to other classes in this package (e.g. StructureTickHandler)
     * so they do not need to duplicate the encoding logic.
     */
    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}