package net.buildtheearth.terraplusplus.generator.historical;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable ownership ledger for blocks written during a Terra++ historical
 * refresh (water, vegetation, surfaces, and structures).
 *
 * The ledger deliberately stores an exact world position and the exact state id
 * that Terra++ wrote. During cleanup a block is removed only if it still has
 * that state id. This is intentionally conservative: a player modification is
 * not treated as Terra++ content, even where it lies inside an old footprint.
 */
public final class GeneratedBlockOwnershipIndex {
    private static final Logger LOGGER = LogManager.getLogger("Terra++Ownership");
    private static final int MAGIC = 0x54504F42; // TPOB
    private static final int VERSION = 1;
    private static final int REGION_BITS = 5;
    private static final int REGION_SIZE = 1 << REGION_BITS;
    private static final int REGION_MASK = REGION_SIZE - 1;
    private static final int REGION_CELLS = REGION_SIZE * REGION_SIZE;
    private static final Object LOCK = new Object();

    private static volatile Path baseDir;
    private static volatile Path regionsDir;
    private static volatile Path worldRoot;
    private static final ConcurrentHashMap<Long, Region> cache = new ConcurrentHashMap<>();

    private GeneratedBlockOwnershipIndex() {}

    /** A final state written by a structure generator. */
    public record OwnedBlock(long packedPos, int stateId) {}

    private static final class Region {
        final int rx, rz;
        final Map<Integer, List<OwnedBlock>> chunks = new HashMap<>();
        boolean dirty;
        Region(int rx, int rz) { this.rx = rx; this.rz = rz; }
    }

    private static boolean init(ServerLevel level) {
        if (level == null || level.dimension() != Level.OVERWORLD) return false;
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        if (root.equals(worldRoot) && regionsDir != null) return true;
        synchronized (LOCK) {
            if (root.equals(worldRoot) && regionsDir != null) return true;
            try {
                cache.clear();
                worldRoot = root;
                baseDir = root.resolve("data").resolve("terra_generated_ownership");
                regionsDir = baseDir.resolve("regions");
                Files.createDirectories(regionsDir);
                return true;
            } catch (Exception e) {
                LOGGER.warn("Ownership index init: {}", e.getMessage());
                return false;
            }
        }
    }

    /**
     * Clears old, tracked Terra++ blocks for this chunk. Blocks altered after
     * placement are preserved because they no longer match the recorded state.
     */
    public static boolean clearOwnedBlocks(ServerLevel level, ChunkPos chunk) {
        if (!init(level) || chunk == null) return false;
        List<OwnedBlock> old;
        synchronized (LOCK) {
            Region region = region(chunk.x, chunk.z);
            int index = localIndex(chunk.x, chunk.z);
            old = region.chunks.remove(index);
            if (old == null || old.isEmpty()) return true;
            region.dirty = true;
            // Persist the cleanup intent before world mutation. If disk write
            // fails, restore the in-memory record and leave the world intact.
            if (!save(region)) {
                region.chunks.put(index, old);
                region.dirty = true;
                return false;
            }
        }
        int removed = 0;
        for (OwnedBlock owned : old) {
            BlockPos pos = BlockPos.of(owned.packedPos());
            // Ledger data is chunk-scoped; reject corrupt/misfiled positions.
            if ((pos.getX() >> 4) != chunk.x || (pos.getZ() >> 4) != chunk.z) continue;
            BlockState current = level.getBlockState(pos);
            if (Block.getId(current) != owned.stateId()) continue;
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            removed++;
        }
        if (removed > 0) LOGGER.debug("Ownership cleanup [{},{}]: {} blocks", chunk.x, chunk.z, removed);
        return true;
    }

    /** Replace the chunk's ledger only after a successful new placement. */
    public static boolean commit(ServerLevel level, ChunkPos chunk, List<OwnedBlock> blocks) {
        if (!init(level) || chunk == null) return false;
        // Last write wins per position; generators may set a position more than once.
        Map<Long, Integer> finalStates = new HashMap<>();
        if (blocks != null) for (OwnedBlock block : blocks) {
            BlockPos pos = BlockPos.of(block.packedPos());
            if ((pos.getX() >> 4) != chunk.x || (pos.getZ() >> 4) != chunk.z) continue;
            if (block.stateId() == Block.getId(Blocks.AIR.defaultBlockState()))
                finalStates.remove(block.packedPos());
            else
                finalStates.put(block.packedPos(), block.stateId());
        }
        List<OwnedBlock> finalBlocks = new ArrayList<>(finalStates.size());
        for (var entry : finalStates.entrySet()) finalBlocks.add(new OwnedBlock(entry.getKey(), entry.getValue()));
        synchronized (LOCK) {
            Region region = region(chunk.x, chunk.z);
            int idx = localIndex(chunk.x, chunk.z);
            if (finalBlocks.isEmpty()) region.chunks.remove(idx);
            else region.chunks.put(idx, finalBlocks);
            region.dirty = true;
            if (!save(region)) return false;
        }
        LOGGER.info("OWNERSHIP_COMMIT chunk=[{},{}] blocks={}",
            chunk.x, chunk.z, finalBlocks.size());
        return true;
    }

    public static void flush() {
        synchronized (LOCK) { for (Region region : cache.values()) if (region.dirty) save(region); }
    }

    private static Region region(int cx, int cz) {
        int rx = Math.floorDiv(cx, REGION_SIZE), rz = Math.floorDiv(cz, REGION_SIZE);
        long key = key(rx, rz);
        Region existing = cache.get(key);
        if (existing != null) return existing;
        Region loaded = load(rx, rz);
        cache.put(key, loaded);
        return loaded;
    }

    private static Region load(int rx, int rz) {
        Region out = new Region(rx, rz);
        Path file = file(rx, rz);
        if (!Files.exists(file)) return out;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION || in.readInt() != rx || in.readInt() != rz)
                throw new IllegalStateException("bad header");
            int entries = in.readInt();
            if (entries < 0 || entries > REGION_CELLS) throw new IllegalStateException("bad entry count");
            for (int n = 0; n < entries; n++) {
                int idx = in.readUnsignedShort();
                int count = in.readInt();
                if (idx >= REGION_CELLS || count < 0 || count > 2_000_000) throw new IllegalStateException("bad block count");
                List<OwnedBlock> blocks = new ArrayList<>(count);
                for (int i = 0; i < count; i++) blocks.add(new OwnedBlock(in.readLong(), in.readInt()));
                if (!blocks.isEmpty()) out.chunks.put(idx, blocks);
            }
        } catch (Exception e) {
            // Never guess ownership from a corrupt file. Preserve all world blocks.
            LOGGER.warn("Ownership region [{},{}] ignored: {}", rx, rz, e.getMessage());
        }
        return out;
    }

    private static boolean save(Region region) {
        if (!region.dirty) return true;
        if (regionsDir == null) return false;
        Path target = file(region.rx, region.rz);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(regionsDir);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)))) {
                out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(region.rx); out.writeInt(region.rz);
                out.writeInt(region.chunks.size());
                for (var entry : region.chunks.entrySet()) {
                    out.writeShort(entry.getKey());
                    out.writeInt(entry.getValue().size());
                    for (OwnedBlock block : entry.getValue()) { out.writeLong(block.packedPos()); out.writeInt(block.stateId()); }
                }
            }
            try { Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (Exception atomicUnsupported) { Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            region.dirty = false;
            return true;
        } catch (Exception e) {
            LOGGER.warn("Ownership region [{},{}] save: {}", region.rx, region.rz, e.getMessage());
            return false;
        }
    }

    private static Path file(int rx, int rz) { return regionsDir.resolve("r." + rx + "." + rz + ".bin"); }
    private static long key(int rx, int rz) { return ((long) rx << 32) | (rz & 0xffffffffL); }
    private static int localIndex(int cx, int cz) { return ((cz & REGION_MASK) << REGION_BITS) | (cx & REGION_MASK); }
}
