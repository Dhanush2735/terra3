// ============================================================
// FILE: TerraTimeCommand.java
// Commands:
//   /terra year              — show current year
//   /terra timescale 1x      — set speed
//   /terra timescale 10x
//   /terra timescale 25x
//   /terra timescale 50x
//   /terra timejump <year>   — jump to specific year
//   /terra timeinfo          — detailed time info
// FIX: scheduleLoadedChunkRefresh() called after time jump.
// FIX: Roads generate with buildings in pass 2, not instant.
//      Client chunk resync ensures no invisible structures.
// FIX: Water sync uses chunkMap.getChunks() to reach
//      semi-loaded chunks that getChunk(x,z,false) misses.
//      Fallback to snapshot if chunkMap unavailable.
// ADDED: PersistentChunkRefreshIndex generation advance.
// ADDED: postJumpRescanCountdown for semi-loaded chunk fix.
// ADDED: Algorithm logging around time jump.
// ============================================================
package net.buildtheearth.terraplusplus.generator.historical;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.buildtheearth.terraplusplus.generator.TerraChunkGenerator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

@Mod.EventBusSubscriber(modid = "terraplusplus")
public class TerraTimeCommand {

    private static final
        org.apache.logging.log4j.Logger LOGGER =
        org.apache.logging.log4j.LogManager
        .getLogger("Terra++TimeCmd");

    @SubscribeEvent
    public static void onRegisterCommands(
            RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(
            CommandDispatcher<CommandSourceStack>
            dispatcher) {
        dispatcher.register(
            Commands.literal("terra")

            // ── /terra year ───────────────────────────
            .then(Commands.literal("year")
                .executes(ctx -> {
                    int year =
                        TerraTimeManager
                        .getCurrentYear();
                    int scale =
                        TerraTimeManager.getTimeScale();
                    String era =
                        TerraTimeManager.getEraName(year);
                    String fmt =
                        TerraTimeManager.formatYear(year);
                    ctx.getSource().sendSuccess(
                        () -> Component.literal(
                            "§6[Terra++]§r Current year:"
                            +" §e"+fmt
                            +"§r | Era: §b"+era
                            +"§r | Speed: §a"+scale+"x"),
                        false);
                    return 1;
                }))

            // ── /terra timescale ──────────────────────
            .then(Commands.literal("timescale")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("1x")
                    .executes(ctx ->
                        setScale(ctx.getSource(), 1)))
                .then(Commands.literal("10x")
                    .executes(ctx ->
                        setScale(ctx.getSource(), 10)))
                .then(Commands.literal("25x")
                    .executes(ctx ->
                        setScale(ctx.getSource(), 25)))
                .then(Commands.literal("50x")
                    .executes(ctx ->
                        setScale(ctx.getSource(), 50))))

            // ── /terra timejump <year> ────────────────
            .then(Commands.literal("timejump")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("year",
                        IntegerArgumentType.integer(
                            -3000, 9999))
                    .executes(ctx -> {
                        int targetYear =
                            IntegerArgumentType
                            .getInteger(ctx, "year");
                        return executeTimeJump(
                            ctx.getSource(),
                            targetYear);
                    })))

            // ── /terra timeinfo ───────────────────────
            .then(Commands.literal("timeinfo")
                .executes(ctx -> {
                    int year =
                        TerraTimeManager
                        .getCurrentYear();
                    int scale =
                        TerraTimeManager.getTimeScale();
                    boolean en =
                        TerraTimeManager.isTimeEnabled();
                    ServerLevel level = getOverworld();
                    long worldTick = level != null
                        ? level.getDayTime() : 0;
                    long worldDay =
                        worldTick / 24000L;
                    int refreshPending =
                        TerraChunkGenerator
                        .getRefreshQueueSize();
                    ctx.getSource().sendSuccess(
                        () -> Component.literal(
                            "§6[Terra++] Time System§r\n"
                            +"  Year: §e"
                            +TerraTimeManager
                                .formatYear(year)
                            +"§r\n"
                            +"  Era: §b"
                            +TerraTimeManager
                                .getEraName(year)
                            +"§r\n"
                            +"  Scale: §a"+scale
                            +"x§r\n"
                            +"  Enabled: §a"+en
                            +"§r\n"
                            +"  World day: §7"+worldDay
                            +"§r\n"
                            +"  World tick: §7"+worldTick
                            +"§r\n"
                            +"  365 MC days = §e"+scale
                            +" year(s)§r\n"
                            +"  Days per year: §7"
                            +TerraTimeManager.DAYS_PER_YEAR
                            +"§r\n"
                            +"  Refresh pending: §7"
                            +refreshPending
                            +" chunks§r\n"
                            +"  Refresh gen: §7"
                            +PersistentChunkRefreshIndex
                                .getGeneration()
                            +"§r\n"
                            +"  Loaded now: §7"
                            +PersistentChunkRefreshIndex
                                .getLoadedChunkCount()
                            +" chunks§r\n"
                            +"  Rescan countdown: §7"
                            +TerraChunkGenerator
                                .postJumpRescanCountdown
                            +" ticks§r"),
                        false);
                    return 1;
                }))
        );
    }

    // ── SET SCALE ─────────────────────────────────────────────
    private static int setScale(
            CommandSourceStack src, int scale) {
        TerraTimeManager.setTimeScale(scale);
        src.sendSuccess(
            () -> Component.literal(
                "§6[Terra++]§r Time scale set to §a"
                +scale+"x§r. 365 MC days = §e"
                +scale+" year(s)§r."),
            true);
        return 1;
    }

    // ── TIME JUMP ─────────────────────────────────────────────
    private static int executeTimeJump(
            CommandSourceStack src, int targetYear) {
        ServerLevel level = getOverworld();
        if (level == null) {
            src.sendFailure(Component.literal(
                "§c[Terra++] Cannot find overworld."));
            return 0;
        }

        int scale = TerraTimeManager.getTimeScale();
        if (scale <= 0) scale = 1;

        long yearsFromBase = (long) targetYear
            - TerraTimeManager.BASE_YEAR;
        if (yearsFromBase < 0) {
            src.sendFailure(Component.literal(
                "§c[Terra++] Target year "
                + targetYear
                + " is before base year "
                + TerraTimeManager.BASE_YEAR));
            return 0;
        }

        LOGGER.info(
            "TIMEJUMP_START targetYear={} "
            + "currentGen={} loadedSetSize={} "
            + "refreshQueueSize={}",
            targetYear,
            PersistentChunkRefreshIndex.getGeneration(),
            PersistentChunkRefreshIndex
                .getLoadedChunkCount(),
            TerraChunkGenerator.getRefreshQueueSize());

        long worldDay = (yearsFromBase
            * TerraTimeManager.DAYS_PER_YEAR) / scale;
        long worldTick = worldDay * 24000L;
        long currentTimeOfDay =
            level.getDayTime() % 24000L;
        long newWorldTime =
            worldTick + currentTimeOfDay;

        level.setDayTime(newWorldTime);
        TerraTimeManager.forceRecalculate(level);

        int actualYear =
            TerraTimeManager.getCurrentYear();

        PersistentChunkRefreshIndex
            .advanceRefreshGeneration(level, actualYear);

        // ── IMMEDIATE WATER SYNC ──────────────────────
        // Must run BEFORE queue-based refresh.
        // Uses chunkMap.getChunks() to reach ALL
        // chunks in server memory including semi-loaded
        // chunks that getChunk(x,z,false) misses.
        // Semi-loaded chunks are in the server's chunk
        // holder map but not yet fully promoted to
        // ticking state — getChunk(x,z,false) returns
        // null for them but chunkMap.getChunks() does
        // not.
        // Safe here because we are inside a server tick
        // command execution, not inside ChunkEvent.Load,
        // so no chunk lock is held.
        var gen = TerraChunkGenerator.activeInstance;
        if (gen != null) {
            int totalScanned = 0;
            int waterSynced  = 0;
            int waterFailed  = 0;
            boolean usedChunkMap = false;

// ── METHOD 1: chunkMap.getChunks() via reflection ─
// getChunks() is package-private in ChunkMap so
// we must use reflection to call it.
try {
    var chunkSource =
        level.getChunkSource();
    var chunkMap = chunkSource.chunkMap;

    java.lang.reflect.Method getChunks =
        chunkMap.getClass()
        .getDeclaredMethod("getChunks");
    getChunks.setAccessible(true);

    @SuppressWarnings("unchecked")
    Iterable<net.minecraft.server.level.ChunkHolder>
        holders =
        (Iterable<net.minecraft.server.level
            .ChunkHolder>)
        getChunks.invoke(chunkMap);

    if (holders != null) {
        usedChunkMap = true;
        for (var holder : holders) {
            totalScanned++;
            LevelChunk lc = null;
            try {
                lc = holder.getTickingChunk();

                if (lc == null) {
                    var fut =
                        holder.getFullChunkFuture();
                    if (fut != null
                            && fut.isDone()) {
                        var result =
                            fut.getNow(null);
                        if (result != null) {
                            var opt =
                                result.left();
                            if (opt != null
                                    && opt
                                    .isPresent())
                                lc = opt.get();
                        }
                    }
                }

                if (lc == null) continue;

                gen.syncHistoricalWaterPublic(
                    level, lc);
                waterSynced++;
            } catch (Exception e) {
                waterFailed++;
                LOGGER.debug(
                    "Water sync holder error: {}",
                    e.getMessage());
            }
        }
    }
} catch (Exception e) {
    LOGGER.warn(
        "ChunkMap reflection failed: {} "
        + "— using fallback",
        e.getMessage());
}

            // ── METHOD 2: FALLBACK ────────────────────
            // If chunkMap failed or returned nothing,
            // fall back to PersistentChunkRefreshIndex
            // snapshot + level.hasChunk.
            // This covers fully loaded chunks only
            // but is better than nothing.
            if (!usedChunkMap || totalScanned == 0) {
                LOGGER.info(
                    "TIMEJUMP water sync: using "
                    + "fallback snapshot method");
                totalScanned = 0;
                waterSynced  = 0;
                waterFailed  = 0;

                for (ChunkPos cp :
                        PersistentChunkRefreshIndex
                        .getLoadedChunkSnapshot(
                            level)) {
                    totalScanned++;
                    try {
                        if (!level.hasChunk(
                                cp.x, cp.z))
                            continue;
                        LevelChunk lc =
                            level.getChunk(cp.x, cp.z);
                        if (lc == null) continue;
                        gen.syncHistoricalWaterPublic(
                            level, lc);
                        waterSynced++;
                    } catch (Exception e) {
                        waterFailed++;
                        LOGGER.debug(
                            "Water sync fallback "
                            + "[{},{}]: {}",
                            cp.x, cp.z,
                            e.getMessage());
                    }
                }
            }

            LOGGER.info(
                "TIMEJUMP water sync complete: "
                + "method={} scanned={} "
                + "synced={} failed={}",
                usedChunkMap ? "chunkMap" : "fallback",
                totalScanned,
                waterSynced,
                waterFailed);
        } else {
            LOGGER.warn(
                "TIMEJUMP water sync SKIPPED: "
                + "activeInstance is null");
        }

        // ── QUEUE VEGETATION + STRUCTURE REFRESH ─────
        // Pass 1: vegetation (10-64/tick)
        // Pass 2: roads + buildings via
        //         OsmStructureGenerator (2/tick)
        // Client chunk resync after pass 2 placement
        TerraChunkGenerator
            .scheduleLoadedChunkRefresh(level);

        TerraChunkGenerator
            .postJumpRescanCountdown = 200;

        LOGGER.info(
            "TIMEJUMP_DONE actualYear={} newGen={} "
            + "pass1Queued={} rescanIn=200ticks",
            actualYear,
            PersistentChunkRefreshIndex.getGeneration(),
            TerraChunkGenerator.getRefreshQueueSize());

        String fmt =
            TerraTimeManager.formatYear(actualYear);
        String era =
            TerraTimeManager.getEraName(actualYear);
        int chunksQueued =
            TerraChunkGenerator.getRefreshQueueSize();

        src.sendSuccess(
            () -> Component.literal(
                "§6[Terra++]§r Time jumped to §e"
                + fmt + "§r (§b" + era + "§r)\n"
                + "  World day: §7" + worldDay + "§r\n"
                + "  World tick: §7" + newWorldTime
                + "§r\n"
                + "  Refresh generation: §7"
                + PersistentChunkRefreshIndex
                    .getGeneration()
                + "§r\n"
                + "  Refreshing §a" + chunksQueued
                + "§r loaded chunks now...\n"
                + "  Roads + buildings via pass 2\n"
                + "  Client resync after placement\n"
                + "  Semi-loaded rescan in §710s§r\n"
                + "§7Previously visited unloaded chunks"
                + " will refresh when loaded.\n"
                + "§7Structure blocks are preserved."),
            true);

        TerraTimeManager.save();
        return 1;
    }

    // ── UTILITY ───────────────────────────────────────────────
    private static ServerLevel getOverworld() {
        try {
            var server = ServerLifecycleHooks
                .getCurrentServer();
            if (server == null) return null;
            return server.overworld();
        } catch (Exception e) {
            return null;
        }
    }
}