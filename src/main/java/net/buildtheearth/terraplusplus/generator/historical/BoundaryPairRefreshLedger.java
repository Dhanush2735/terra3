package net.buildtheearth.terraplusplus.generator.historical;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Durable one-hop boundary records. Unlike a generic deferred chunk queue,
 * records are source->target pairs and consuming a target never creates a new
 * outward pair. */
public final class BoundaryPairRefreshLedger {
    private static final int MAGIC = 0x54504252; // TPBR
    private static final int VERSION = 1;
    private static final Object LOCK = new Object();
    private static Path root, file;
    private static final Set<Pair> pairs = new HashSet<>();
    private record Pair(long source, long target) {}
    private BoundaryPairRefreshLedger() {}

    private static boolean init(ServerLevel level) {
        if (level == null || level.dimension() != Level.OVERWORLD) return false;
        Path requested = level.getServer().getWorldPath(LevelResource.ROOT);
        synchronized (LOCK) {
            if (requested.equals(root) && file != null) return true;
            root = requested;
            file = requested.resolve("data").resolve("terra_chunk_index").resolve("boundary_pairs.bin");
            pairs.clear();
            try {
                if (!Files.exists(file)) return true;
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                    if (in.readInt() != MAGIC || in.readInt() != VERSION) return false;
                    int count = in.readInt();
                    if (count < 0 || count > 100_000) return false;
                    for (int i=0;i<count;i++) pairs.add(new Pair(in.readLong(), in.readLong()));
                }
                return true;
            } catch (Exception ignored) { pairs.clear(); return false; }
        }
    }

    public static void record(ServerLevel level, int sourceX, int sourceZ, int targetX, int targetZ) {
        if (!init(level)) return;
        synchronized (LOCK) {
            if (pairs.add(new Pair(key(sourceX, sourceZ), key(targetX, targetZ)))) save();
        }
    }

    /** Returns source chunks whose one-hop boundary target is this loaded chunk. */
    public static List<long[]> consumeTarget(ServerLevel level, int targetX, int targetZ) {
        if (!init(level)) return List.of();
        long target = key(targetX, targetZ);
        synchronized (LOCK) {
            List<long[]> sources = new ArrayList<>();
            pairs.removeIf(pair -> {
                if (pair.target != target) return false;
                sources.add(new long[]{(int)(pair.source >> 32), (int)pair.source});
                return true;
            });
            if (!sources.isEmpty()) save();
            return sources;
        }
    }

    public static void flush() { synchronized (LOCK) { if (file != null) save(); } }
    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp=file.resolveSibling(file.getFileName()+".tmp");
            try (DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp, StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)))) {
                out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(pairs.size());
                for (Pair pair:pairs) { out.writeLong(pair.source); out.writeLong(pair.target); }
            }
            try { Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch(Exception e) { Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING); }
        } catch(Exception ignored) {}
    }
    private static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
}
