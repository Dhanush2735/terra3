// ============================================================
// FILE: ProvisionalServing.java
// THE APPETIZER LEDGER — cold-start progressive enhancement.
//
// Restaurant protocol (user-specified):
//   "if customer is starved give appetizers before serving
//    main food."
//
//   COLD START: OSM (Overpass) takes 7-90s to cook. Instead of
//   bare terrain, chunks are served immediately from the FAST
//   sources (appetizer tier: Overture/MSFT+VIDA today, full
//   Overture GeoParquet later) and recorded here as PROVISIONAL.
//
//   MAIN COURSE: when the OSM tile lands (AsyncStructureManager
//   tier-2 success), every provisional chunk in that tile is
//   invalidated and resubmitted through the SAME battle-tested
//   swap machinery a time jump uses (clear owned blocks ->
//   re-place with full OSM). After the swap the area is
//   self-sustained: OSM on disk, appetizers never served there
//   again.
//
// Design rules (inherited from 7 runs of scar tissue):
//   - single owner at any moment: appetizer OWNS the chunk until
//     the swap; then OSM owns it. Never both.
//   - absence is PENDING: a chunk is provisional because OSM is
//     PENDING, never because OSM is "empty" (empty OSM is a
//     valid main course — swap still runs, placing nothing,
//     clearing the appetizer's excess).
//   - every method self-checks and logs (APPETIZER/MAIN COURSE
//     prefixes, machine-parseable).
//   - bounded memory: hard cap, oldest evicted (an evicted
//     provisional chunk heals via the deferred ledger on next
//     load — same safety net as everything else).
// ============================================================
package net.buildtheearth.terraplusplus.generator.historical;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProvisionalServing {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++Appetizer");

    /** Hard cap on tracked provisional chunks. */
    private static final int MAX_ENTRIES = 8192;

    /** 0.01-deg structure-tile key -> chunks served provisionally
     *  from that tile's appetizer data. */
    private static final ConcurrentHashMap<Long, List<Long>>
        byTile = new ConcurrentHashMap<>();
    /** chunk key -> tile key (reverse lookup + dedup). */
    private static final ConcurrentHashMap<Long, Long>
        byChunk = new ConcurrentHashMap<>();

    private static final AtomicInteger totalServed =
        new AtomicInteger();
    private static final AtomicInteger totalSwapped =
        new AtomicInteger();

    /** SPI: is the OSM tile covering (lat,lon) already resident?
     *  Registered by TerraChunkGenerator. Closes the serve/arrive
     *  race: a chunk marked provisional AFTER its tile's arrival
     *  event already fired would otherwise stay provisional
     *  forever (the arrival never repeats). RACE TEST caught it. */
    public interface TilePresence { boolean isResident(double lat, double lon); }
    public static volatile TilePresence presenceCheck = null;

    private ProvisionalServing() {}

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static long tileKey(double lat, double lon) {
        int tLat = (int) Math.floor(lat / 0.01);
        int tLon = (int) Math.floor(lon / 0.01);
        return ((long) tLat << 32) | (tLon & 0xFFFFFFFFL);
    }

    /** Records that a chunk was served from appetizer data while
     *  its OSM tile is still pending. Idempotent per chunk. */
    public static void markServed(int cx, int cz,
            double lat, double lon, int featureCount) {
        long ck = chunkKey(cx, cz);
        long tk = tileKey(lat, lon);
        if (byChunk.putIfAbsent(ck, tk) != null) return;
        if (byChunk.size() > MAX_ENTRIES) {
            // Bounded: drop tracking (not blocks). The persistent
            // refresh index still knows the chunk is stale for
            // the current year, so it heals on a later pass.
            byChunk.remove(ck);
            LOGGER.warn("APPETIZER ledger full ({}); chunk "
                + "[{},{}] untracked — heals via refresh index",
                MAX_ENTRIES, cx, cz);
            return;
        }
        // ATOMIC add: computeIfAbsent(...).add() had a window
        // where the arrival event could drain the still-empty
        // list, orphaning this chunk (tracked in byChunk, in no
        // list, never swapped — RACE TEST leftover=1). compute()
        // makes list-creation+add one atomic step per key: the
        // drain either sees this chunk in the list, or removes
        // the key first and the race guard below fires the swap.
        byTile.compute(tk, (k, v) -> {
            if (v == null)
                v = java.util.Collections.synchronizedList(
                    new ArrayList<>());
            v.add(ck);
            return v;
        });
        int n = totalServed.incrementAndGet();
        if (n <= 20 || n % 50 == 0)
            LOGGER.info("APPETIZER served chunk [{},{}] "
                + "({} fast-source features; OSM pending) "
                + "— total provisional: {}",
                cx, cz, featureCount, byChunk.size());
        // RACE GUARD: if the OSM tile became resident while we
        // were marking (arrival event already consumed the old
        // list), fire the swap ourselves — otherwise this chunk
        // would stay provisional forever.
        TilePresence pc = presenceCheck;
        if (pc != null) {
            try {
                if (pc.isResident(lat, lon))
                    notifyTileArrived(lat, lon);
            } catch (Exception ignored) {}
        }
    }

    /** True if this chunk currently holds appetizer content. */
    public static boolean isProvisional(int cx, int cz) {
        return byChunk.containsKey(chunkKey(cx, cz));
    }

    /** MAIN COURSE: the OSM tile for (lat,lon) has landed.
     *  Invalidate + resubmit every provisional chunk of that
     *  tile through the standard refresh machinery. Returns how
     *  many chunks were sent to swap. */
    public static int onOsmTileArrived(ServerLevel level,
            double lat, double lon) {
        long tk = tileKey(lat, lon);
        List<Long> chunks = byTile.remove(tk);
        if (chunks == null || chunks.isEmpty()) return 0;
        int swapped = 0, deferred = 0;
        synchronized (chunks) {
            for (long ck : chunks) {
                byChunk.remove(ck);
                int cx = (int) (ck >> 32);
                int cz = (int) ck;
                try {
                    if (level != null
                            && level.hasChunk(cx, cz)) {
                        PersistentChunkRefreshIndex
                            .invalidateChunk(level, cx, cz);
                        net.buildtheearth.terraplusplus
                            .generator.TerraChunkGenerator
                            .scheduleSingleChunkRefresh(level,
                                new ChunkPos(cx, cz));
                        swapped++;
                    } else if (level != null) {
                        // Unloaded: same safety net as all
                        // refresh work.
                        DeferredChunkRefreshLedger.defer(
                            level, cx, cz);
                        deferred++;
                    }
                } catch (Exception e) {
                    LOGGER.debug("swap [{},{}]: {}", cx, cz,
                        e.getMessage());
                }
            }
        }
        totalSwapped.addAndGet(swapped);
        LOGGER.info("MAIN COURSE arrived for tile "
            + "[{},{}]: {} chunks to swap, {} deferred "
            + "(lifetime: {} served, {} swapped)",
            String.format("%.2f", Math.floor(lat / 0.01) * 0.01),
            String.format("%.2f", Math.floor(lon / 0.01) * 0.01),
            swapped, deferred,
            totalServed.get(), totalSwapped.get());
        return swapped;
    }

    /** Convenience for fetch-layer callers (batch/single tile
     *  success in OsmStructureIndex): resolves the server level
     *  itself. No-op if no provisional chunks in that tile —
     *  which is the warm-start case, so this is O(1) there. */
    public static void notifyTileArrived(double lat, double lon) {
        if (byTile.isEmpty()) return;
        if (!byTile.containsKey(tileKey(lat, lon))) return;
        try {
            var server = net.minecraftforge.server
                .ServerLifecycleHooks.getCurrentServer();
            if (server == null || server.overworld() == null) return;
            final double fLat = lat, fLon = lon;
            final ServerLevel level = server.overworld();
            // Swap scheduling touches chunks: hop to the server
            // thread; the refresh engine drains it budgeted.
            server.execute(() ->
                onOsmTileArrived(level, fLat, fLon));
        } catch (Exception e) {
            LOGGER.debug("notifyTileArrived: {}", e.getMessage());
        }
    }

    /** For status commands / analyzer. */
    public static int provisionalCount() { return byChunk.size(); }
    public static int lifetimeServed()   { return totalServed.get(); }
    public static int lifetimeSwapped()  { return totalSwapped.get(); }

    /** World unload: drop tracking (blocks stay; the persistent
     *  refresh index still marks them stale if OSM never came). */
    public static void clear() {
        int n = byChunk.size();
        byTile.clear();
        byChunk.clear();
        if (n > 0)
            LOGGER.info("APPETIZER ledger cleared "
                + "({} provisional chunks dropped; refresh "
                + "index still owns their staleness)", n);
    }
}
