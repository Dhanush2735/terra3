package net.buildtheearth.terraplusplus.generator.water;

import net.minecraft.world.level.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background source-tile coordinator for water.  It deliberately performs no
 * world/block mutation: background workers fetch/cache OHM and OSM tiles, then
 * hand ready chunks to TerraChunkGenerator for safe server-thread placement.
 */
public final class AsyncWaterManager {
    private static final Logger LOGGER = LogManager.getLogger("Terra++AsyncWater");
    private static final double TILE_DEG = 0.05;
    private static final int MAX_PENDING = 512;
    private static final int MAX_RETRIES = 8;
    private static final long INITIAL_BACKOFF_MS = 2_000L;
    private static final long MAX_BACKOFF_MS = 60_000L;
    private static final long OHM_WAIT_MS = 15_000L;
    private static final long OSM_WAIT_MS = 12_000L;

    /** A lookup must never use NONE to mean both absent data and no water. */
    public enum WaterDataState { PENDING, READY, RETRY_LATER }

    private static final class Task {
        final long key;
        final double lat, lon;
        final Set<ChunkPos> waiting = ConcurrentHashMap.newKeySet();
        int attempts;
        long backoffMs = INITIAL_BACKOFF_MS;
        volatile WaterDataState state = WaterDataState.PENDING;

        Task(long key, double lat, double lon, ChunkPos first) {
            this.key = key;
            this.lat = lat;
            this.lon = lon;
            this.waiting.add(first);
        }
    }

    private final OsmWaterIndex osm;
    private final OhmWaterIndex ohm;
    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<Long, Task> pending = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ChunkPos> ready = new ConcurrentLinkedQueue<>();
    private final Set<Long> readySet = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;

    public AsyncWaterManager(OsmWaterIndex osm, OhmWaterIndex ohm) {
        this.osm = osm;
        this.ohm = ohm;
        this.workers = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Terra++AsyncWater");
            t.setDaemon(true);
            return t;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Terra++AsyncWaterRetry");
            t.setDaemon(true);
            return t;
        });
    }

    /** Subscribe a chunk to the one fetch task for its 0.05-degree source tile. */
    public void request(double latitude, double longitude, ChunkPos chunk) {
        if (!running || chunk == null || !Double.isFinite(latitude) || !Double.isFinite(longitude)) return;
        double lat = Math.floor(latitude / TILE_DEG) * TILE_DEG;
        double lon = Math.floor(longitude / TILE_DEG) * TILE_DEG;
        long key = tileKey(lat, lon);
        if (pending.size() >= MAX_PENDING && !pending.containsKey(key)) return;
        pending.compute(key, (ignored, existing) -> {
            if (existing != null) {
                existing.waiting.add(chunk);
                return existing;
            }
            Task task = new Task(key, lat, lon, chunk);
            submit(task, 0L);
            return task;
        });
    }

    private void submit(Task task, long delayMs) {
        if (!running) return;
        if (delayMs <= 0L) workers.submit(() -> fetch(task));
        else scheduler.schedule(() -> workers.submit(() -> fetch(task)), delayMs, TimeUnit.MILLISECONDS);
    }

    private void fetch(Task task) {
        if (!running || pending.get(task.key) != task) return;
        boolean ohmReady = false;
        boolean osmReady = false;
        try {
            // These waits happen only on this manager's daemon workers, never
            // on Minecraft's server tick or a terrain-generation worker.
            if (ohm.isEnabled()) ohmReady = ohm.getTileBlocking(task.lat, task.lon, OHM_WAIT_MS);
            if (osm.isEnabled()) osmReady = osm.getTileBlocking(task.lat, task.lon, OSM_WAIT_MS);
        } catch (Exception e) {
            LOGGER.debug("Water tile [{},{}] fetch: {}", task.lat, task.lon, e.getMessage());
        }

        // A final no-water decision requires every enabled authority to have
        // answered. OHM may have no historical feature while OSM still has a
        // present-day pond, so "OHM ready" alone is not enough.
        boolean allEnabledSourcesReady = (!ohm.isEnabled() || ohmReady)
                && (!osm.isEnabled() || osmReady);
        if (allEnabledSourcesReady) {
            task.state = WaterDataState.READY;
            pending.remove(task.key, task);
            for (ChunkPos pos : task.waiting) enqueueReady(pos);
            LOGGER.debug("Water tile [{},{}] ready (ohm={}, osm={}, chunks={})",
                    task.lat, task.lon, ohmReady, osmReady, task.waiting.size());
            return;
        }

        task.state = WaterDataState.RETRY_LATER;
        task.attempts++;
        if (task.attempts >= MAX_RETRIES) {
            // Do not finalise terrain as land. Keep a slow retry alive so a
            // public endpoint outage cannot permanently lose a water feature.
            task.attempts = 0;
            task.backoffMs = MAX_BACKOFF_MS;
        } else {
            task.backoffMs = Math.min(MAX_BACKOFF_MS, task.backoffMs * 2L);
        }
        submit(task, task.backoffMs);
    }

    private void enqueueReady(ChunkPos pos) {
        long key = (((long) pos.x) << 32) | (pos.z & 0xFFFFFFFFL);
        if (readySet.add(key)) ready.add(pos);
    }

    public ChunkPos pollReady() {
        ChunkPos pos = ready.poll();
        if (pos != null) readySet.remove((((long) pos.x) << 32) | (pos.z & 0xFFFFFFFFL));
        return pos;
    }

    public int getPendingCount() { return pending.size(); }
    public int getReadyCount() { return ready.size(); }

    public void shutdown() {
        running = false;
        workers.shutdownNow();
        scheduler.shutdownNow();
        pending.clear();
        ready.clear();
        readySet.clear();
    }

    private static long tileKey(double lat, double lon) {
        long a = Math.round((lat + 90.0) / TILE_DEG);
        long b = Math.round((lon + 180.0) / TILE_DEG);
        return (a << 32) | (b & 0xFFFFFFFFL);
    }
}
