package net.buildtheearth.terraplusplus.generator.historical;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/** Durable, non-destructive boundary work. A deferred entry never says
 * "clear trees" or guesses ownership; it says only "refresh this target chunk
 * through its own exact ownership transaction when it becomes available". */
public final class DeferredChunkRefreshLedger {
    private static final Logger LOGGER = LogManager.getLogger("Terra++DeferredRefresh");
    private static final int MAGIC = 0x54504452; // TPDR
    private static final int VERSION = 1;
    private static final Object LOCK = new Object();
    private static Path worldRoot, file;
    private static final Set<Long> pending = new HashSet<>();

    private DeferredChunkRefreshLedger() {}

    private static boolean init(ServerLevel level) {
        if (level == null || level.dimension() != Level.OVERWORLD) return false;
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        synchronized (LOCK) {
            if (root.equals(worldRoot) && file != null) return true;
            worldRoot = root;
            file = root.resolve("data").resolve("terra_generated_ownership")
                .resolve("deferred_chunk_refresh.bin");
            pending.clear();
            try {
                if (!Files.exists(file)) return true;
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                    if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IllegalStateException("bad header");
                    int count = in.readInt();
                    if (count < 0 || count > 2_000_000) throw new IllegalStateException("bad count");
                    for (int i = 0; i < count; i++) pending.add(in.readLong());
                }
                return true;
            } catch (Exception e) {
                // A corrupt queue must not trigger broad destructive cleanup.
                LOGGER.warn("Deferred refresh ledger ignored: {}", e.getMessage());
                pending.clear();
                return false;
            }
        }
    }

    public static void defer(ServerLevel level, int chunkX, int chunkZ) {
        if (!init(level)) return;
        synchronized (LOCK) {
            if (pending.add(key(chunkX, chunkZ))) {
                save();
                LOGGER.info("DEFERRED_BOUNDARY queued target=[{},{}]", chunkX, chunkZ);
            }
        }
    }

    /** Atomically consumes a target's work before it is queued on the server. */
    public static boolean consume(ServerLevel level, int chunkX, int chunkZ) {
        if (!init(level)) return false;
        synchronized (LOCK) {
            boolean found = pending.remove(key(chunkX, chunkZ));
            if (found) {
                save();
                LOGGER.info("DEFERRED_BOUNDARY consumed target=[{},{}]", chunkX, chunkZ);
            }
            return found;
        }
    }

    public static void flush() { synchronized (LOCK) { if (file != null) save(); } }

    private static void save() {
        if (file == null) return;
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)))) {
                out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(pending.size());
                for (long entry : pending) out.writeLong(entry);
            }
            try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (Exception unsupported) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { LOGGER.warn("Deferred refresh ledger save: {}", e.getMessage()); }
    }

    private static long key(int x, int z) { return ((long)x << 32) | (z & 0xffffffffL); }
}
