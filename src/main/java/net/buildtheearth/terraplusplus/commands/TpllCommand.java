// ============================================================
// FILE: TpllCommand.java (FULL — Overture aligned)
// ============================================================
package net.buildtheearth.terraplusplus.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.buildtheearth.terraplusplus.generator.TerraChunkGenerator;
import net.buildtheearth.terraplusplus.generator.TerraDensityFunctions;
import net.buildtheearth.terraplusplus.generator.osm.AsyncStructureManager;
import net.buildtheearth.terraplusplus.generator.osm.MicrosoftIndex;
import net.buildtheearth.terraplusplus.generator.osm.StructurePlacementQueue;
import net.buildtheearth.terraplusplus.generator.osm.StructureTickHandler;
import net.buildtheearth.terraminusminus.projection.GeographicProjection;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import net.buildtheearth.terraminusminus.generator.EarthGeneratorSettings;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

public class TpllCommand {

    private static volatile GeographicProjection projection;
    private static volatile TerraDensityFunctions densityFunctions;
    private static volatile boolean projectionFailed = false;

    private static final int NO_Y = Integer.MIN_VALUE;

    private static GeographicProjection getProjection() {
        if (projection == null && !projectionFailed) {
            synchronized (TpllCommand.class) {
                if (projection == null && !projectionFailed) {
                    try {
                        EarthGeneratorSettings settings =
                                EarthGeneratorSettings.parse(
                                        EarthGeneratorSettings.BTE_DEFAULT_SETTINGS);
                        projection = settings.projection();
                    } catch (Throwable t) {
                        projectionFailed = true;
                    }
                }
            }
        }
        return projection;
    }

    /**
     * Same projection as {@code /tpll}: geographic lat/lon to Minecraft XZ.
     * {@code fromGeo} takes longitude first. Returns null if unloaded or out of bounds.
     */
    public static double[] blockPosFromGeo(double lat, double lon) {
        GeographicProjection proj = getProjection();
        if (proj == null) {
            return null;
        }
        try {
            return proj.fromGeo(lon, lat);
        } catch (OutOfProjectionBoundsException e) {
            return null;
        }
    }

    private static TerraDensityFunctions getDensityFunctions() {
        if (densityFunctions == null) {
            synchronized (TpllCommand.class) {
                if (densityFunctions == null) {
                    try {
                        densityFunctions = new TerraDensityFunctions(15);
                    } catch (Throwable t) {}
                }
            }
        }
        return densityFunctions;
    }

    // classify() can trigger SYNCHRONOUS elevation-tile downloads on a
    // cold cache (observed: /tpll froze the server thread for 57s /
    // 1147 ticks). All command-path classification therefore runs on
    // this worker with a hard wait cap; on timeout we teleport high
    // and let the heightmap correct Y once the chunk generates.
    private static final java.util.concurrent.ExecutorService CLASSIFY_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Terra++TpllClassify");
                t.setDaemon(true);
                return t;
            });
    private static final long CLASSIFY_WAIT_MS = 1500;

    private static TerraDensityFunctions.TerrainColumn classifyBounded(
            int blockX, int blockZ) {
        TerraDensityFunctions df = getDensityFunctions();
        if (df == null) return null;
        try {
            return java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> df.classify(blockX, blockZ), CLASSIFY_POOL)
                    .get(CLASSIFY_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Timeout/failure: tile keeps downloading in the background;
            // the NEXT call for this position is a cache hit.
            return null;
        }
    }

    // ── ASYNC TELEPORT COMPLETION ─────────────────────────────
    private record PendingTeleport(java.util.UUID player,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim,
            double x, double y, double z, float yRot, float xRot, long since) {}

    private static final java.util.concurrent.ConcurrentLinkedQueue<PendingTeleport>
            PENDING_TELEPORTS = new java.util.concurrent.ConcurrentLinkedQueue<>();
    // RUN-14 FIX: was 90_000 — and run 14 hit the full cap
    // ("Destination still generating; teleported anyway" at
    // T+90s) even though structures downloaded at T+7s and
    // appetizers served at T+5s. The chunk's FULL status is a
    // formality the player shouldn't wait for: terrain exists
    // within seconds, and every remaining feature arrives via
    // the appetizer/swap machinery AFTER arrival. 12s covers
    // the honest terrain-generation cases; past that, waiting
    // helps nothing.
    private static final long PENDING_TELEPORT_TIMEOUT_MS = 12_000;

    /** Called every server tick (registered from TerraPlusPlus events). */
    public static void tickPendingTeleports(net.minecraft.server.MinecraftServer server) {
        if (PENDING_TELEPORTS.isEmpty()) return;
        java.util.Iterator<PendingTeleport> it = PENDING_TELEPORTS.iterator();
        while (it.hasNext()) {
            PendingTeleport pt = it.next();
            ServerLevel lvl = server.getLevel(pt.dim());
            ServerPlayer p = server.getPlayerList().getPlayer(pt.player());
            if (lvl == null || p == null) { it.remove(); continue; }
            boolean timedOut = System.currentTimeMillis() - pt.since() > PENDING_TELEPORT_TIMEOUT_MS;
            // getChunkNow is non-blocking: null until workers finish generating.
            if (lvl.getChunkSource().getChunkNow(((int) pt.x()) >> 4, ((int) pt.z()) >> 4) != null
                    || timedOut) {
                p.teleportTo(lvl, pt.x(), pt.y(), pt.z(), pt.yRot(), pt.xRot());
                p.sendSystemMessage(Component.literal(timedOut
                        ? "\u00a7e[Terra++] Destination still generating; teleported anyway."
                        : "\u00a7a[Terra++] Destination ready \u2014 teleported."));
                it.remove();
            }
        }
    }

    /** Surface Y for world spawn / teleport. Falls back high so the player is not buried. */
    public static int spawnYFromBlock(int blockX, int blockZ) {
        return estimateSurfaceY(blockX, blockZ);
    }

    private static int estimateSurfaceY(int blockX, int blockZ) {
        try {
            TerraDensityFunctions.TerrainColumn col = classifyBounded(blockX, blockZ);
            if (col == null) return 200;
            switch (col.type) {
                case LAND: return col.surfaceY + 2;
                case COASTLINE: return TerraDensityFunctions.SEA_LEVEL + 2;
                default: return TerraDensityFunctions.SEA_LEVEL + 2;
            }
        } catch (Exception e) { return 200; }
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {

        // /tpll <lat> <lon> [y]
        dispatcher.register(
                Commands.literal("tpll")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("lat", DoubleArgumentType.doubleArg(-85, 85))
                                .then(Commands.argument("lon", DoubleArgumentType.doubleArg(-180, 180))
                                        .executes(ctx -> {
                                            double lat = DoubleArgumentType.getDouble(ctx, "lat");
                                            double lon = DoubleArgumentType.getDouble(ctx, "lon");
                                            return teleport(ctx.getSource(), lat, lon, NO_Y);
                                        })
                                        .then(Commands.argument("y",
                                                        IntegerArgumentType.integer(
                                                                TerraDensityFunctions.MIN_Y,
                                                                TerraDensityFunctions.MAX_Y))
                                                .executes(ctx -> {
                                                    double lat = DoubleArgumentType.getDouble(ctx, "lat");
                                                    double lon = DoubleArgumentType.getDouble(ctx, "lon");
                                                    int y = IntegerArgumentType.getInteger(ctx, "y");
                                                    return teleport(ctx.getSource(), lat, lon, y);
                                                })
                                        )
                                )
                        )
        );

        // /terra where | places | tp | source
        dispatcher.register(
                Commands.literal("terra")

                        .then(Commands.literal("where")
                                .executes(ctx -> showWhere(ctx.getSource()))
                        )

                        .then(Commands.literal("places")
                                .executes(ctx -> showPlaces(ctx.getSource()))
                        )

                        .then(Commands.literal("source")
                                .then(Commands.literal("osm")
                                        .executes(ctx -> setSource(ctx.getSource(), "osm")))
                                .then(Commands.literal("Microsoft")
                                        .executes(ctx -> setSource(ctx.getSource(), "overture")))
                                .then(Commands.literal("both")
                                        .executes(ctx -> setSource(ctx.getSource(), "both")))
                                .then(Commands.literal("status")
                                        .executes(ctx -> showSourceStatus(ctx.getSource())))
                        )

                        .then(Commands.literal("tp")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("lat", DoubleArgumentType.doubleArg(-85, 85))
                                        .then(Commands.argument("lon", DoubleArgumentType.doubleArg(-180, 180))
                                                .executes(ctx -> {
                                                    double lat = DoubleArgumentType.getDouble(ctx, "lat");
                                                    double lon = DoubleArgumentType.getDouble(ctx, "lon");
                                                    return teleport(ctx.getSource(), lat, lon, NO_Y);
                                                })
                                                .then(Commands.argument("y",
                                                                IntegerArgumentType.integer(
                                                                        TerraDensityFunctions.MIN_Y,
                                                                        TerraDensityFunctions.MAX_Y))
                                                        .executes(ctx -> {
                                                            double lat = DoubleArgumentType.getDouble(ctx, "lat");
                                                            double lon = DoubleArgumentType.getDouble(ctx, "lon");
                                                            int y = IntegerArgumentType.getInteger(ctx, "y");
                                                            return teleport(ctx.getSource(), lat, lon, y);
                                                        })
                                                )
                                        )
                                )
                        )
        );
    }

    private static int setSource(CommandSourceStack source, String mode) {
        TerraChunkGenerator.setActiveSource(mode);

        source.sendSuccess(() -> prefix()
                .append(Component.literal("Building source set to: ")
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal(mode.toUpperCase())
                        .withStyle(ChatFormatting.GREEN)),
                true);

        source.sendSuccess(() -> Component.literal(
                switch (mode) {
                    case "osm" -> "  Using OpenStreetMap only (richest metadata, roads+buildings+barriers)";
                    case "overture" -> "  Using Overture Maps only (OSM+Microsoft+Google, 2.3B buildings)";
                    case "both" -> "  OSM for roads/barriers/landuse, Overture supplements buildings";
                    default -> "  Unknown mode";
                }).withStyle(ChatFormatting.GRAY), false);

        source.sendSuccess(() -> Component.literal(
                "  Changes apply to newly generated chunks only. Delete existing chunks to regenerate.")
                .withStyle(ChatFormatting.DARK_GRAY), false);

        return 1;
    }

    private static int showSourceStatus(CommandSourceStack source) {
        String activeMode = TerraChunkGenerator.getActiveSource();

        source.sendSuccess(() -> prefix()
                .append(Component.literal("Structure Source Status:")
                        .withStyle(ChatFormatting.WHITE)),
                false);

        source.sendSuccess(() -> Component.literal(
                String.format("  Active mode: %s", activeMode.toUpperCase()))
                .withStyle(ChatFormatting.GREEN), false);

        source.sendSuccess(() -> Component.literal(
                "  OSM: Overpass API — buildings, roads, railways, barriers, landuse")
                .withStyle(ChatFormatting.GRAY), false);

        source.sendSuccess(() -> Component.literal(
                "  Overture: OSM + Microsoft ML + Google Open Buildings + Esri (~2.3B buildings)")
                .withStyle(ChatFormatting.GRAY), false);

        source.sendSuccess(() -> Component.literal(
                "  Overture sources: OpenStreetMap, Microsoft ML Buildings, Google Open Buildings, Esri")
                .withStyle(ChatFormatting.DARK_GRAY), false);

        if (StructureTickHandler.isInitialized()) {
            AsyncStructureManager async = StructureTickHandler.getAsyncManager();
            StructurePlacementQueue pq = StructureTickHandler.getPlacementQueue();

            if (async != null) {
                source.sendSuccess(() -> Component.literal(
                        String.format("  Async: %d pending, %d completed, %d retries, %d successes",
                                async.getPendingCount(), async.getCompletedCount(),
                                async.getTotalRetries(), async.getTotalSuccesses()))
                        .withStyle(ChatFormatting.DARK_GRAY), false);
            }

            if (pq != null) {
                source.sendSuccess(() -> Component.literal(
                        String.format("  Queue: %d active, %d deferred, %d placed",
                                pq.getActiveQueueSize(), pq.getDeferredSize(), pq.getTotalPlaced()))
                        .withStyle(ChatFormatting.DARK_GRAY), false);
            }

            source.sendSuccess(() -> Component.literal(
                    String.format("  Tick: %d chunks placed, %d blocks placed",
                            StructureTickHandler.getTotalChunksPlaced(),
                            StructureTickHandler.getTotalBlocksPlaced()))
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }

        source.sendSuccess(() -> Component.literal("")
                .append(Component.literal("  Switch: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("[OSM]").withStyle(Style.EMPTY
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/terra source osm"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Use OSM only — best for roads, barriers, detailed buildings")))))
                .append(Component.literal(" "))
                .append(Component.literal("[Overture]").withStyle(Style.EMPTY
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/terra source overture"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Use Overture only — 2.3B buildings from OSM+Microsoft+Google")))))
                .append(Component.literal(" "))
                .append(Component.literal("[Both]").withStyle(Style.EMPTY
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/terra source both"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("OSM for all features + Overture supplements buildings"))))),
                false);

        return 1;
    }

    private static int teleport(CommandSourceStack source, double lat, double lon, int manualY) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            GeographicProjection proj = getProjection();
            if (proj == null) {
                source.sendFailure(prefix().append(Component.literal("BTE projection not loaded!")
                        .withStyle(ChatFormatting.RED)));
                return 0;
            }

            double[] mc;
            try { mc = proj.fromGeo(lon, lat); }
            catch (OutOfProjectionBoundsException e) {
                source.sendFailure(prefix().append(Component.literal(
                        String.format("Location outside BTE bounds: %.4f, %.4f", lat, lon))
                        .withStyle(ChatFormatting.RED)));
                return 0;
            }

            double mcX = mc[0], mcZ = mc[1];

            // SIT→ORDER LATENCY FIX: fire the data-source
            // prefetch FIRST. It only needs lat/lon (valid now
            // — bounds check passed), but it used to run AFTER
            // estimateSurfaceY, whose classify can block up to
            // 1.5s on a cold elevation download, plus lazy
            // projection init (~1s). That was 1-2.5s of the
            // waiter measuring the table before taking the
            // order. Async: costs the command thread nothing.
            if (TerraChunkGenerator.activeInstance != null) {
                final double wLat = lat, wLon = lon;
                java.util.concurrent.CompletableFuture.runAsync(() ->
                        TerraChunkGenerator.activeInstance
                                .warmDestination(wLat, wLon));
            }

            int teleportY; String ySource;

            if (manualY != NO_Y) {
                teleportY = manualY; ySource = "manual";
            } else {
                teleportY = estimateSurfaceY((int) mcX, (int) mcZ); ySource = "elevation";
                ServerLevel level = player.serverLevel();
                if (level.hasChunk((int) mcX >> 4, (int) mcZ >> 4)) {
                    int hmY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) mcX, (int) mcZ) + 2;
                    if (hmY > level.getMinBuildHeight() + 5) { teleportY = hmY; ySource = "heightmap"; }
                }
            }

            teleportY = Math.max(TerraDensityFunctions.MIN_Y, Math.min(TerraDensityFunctions.MAX_Y, teleportY));

            // RUN-3 FIX: teleporting into ungenerated chunks forced
            // SYNCHRONOUS generation on the server thread (measured:
            // 53s stall / 1063 ticks behind). If the destination chunk
            // is already live we teleport instantly; otherwise we add a
            // chunk ticket so workers generate it in the background and
            // a tick handler completes the teleport when it is ready.
            ServerLevel destLevel = player.serverLevel();
            int dcx = ((int) mcX) >> 4, dcz = ((int) mcZ) >> 4;

            // (data-source prefetch already dispatched above,
            // before the Y estimate — SIT→ORDER latency fix)

            if (destLevel.getChunkSource().getChunkNow(dcx, dcz) != null) {
                player.teleportTo(destLevel, mcX, teleportY, mcZ, player.getYRot(), player.getXRot());
            } else {
                net.minecraft.world.level.ChunkPos dpos = new net.minecraft.world.level.ChunkPos(dcx, dcz);
                destLevel.getChunkSource().addRegionTicket(
                        net.minecraft.server.level.TicketType.POST_TELEPORT, dpos, 1,
                        player.getId());
                PENDING_TELEPORTS.add(new PendingTeleport(player.getUUID(), destLevel.dimension(),
                        mcX, teleportY, mcZ, player.getYRot(), player.getXRot(),
                        System.currentTimeMillis()));
                source.sendSuccess(() -> prefix().append(Component.literal(
                        "Generating destination terrain... you will be teleported when ready.")
                        .withStyle(ChatFormatting.YELLOW)), false);
            }

            String coordText = String.format("%.4f, %.4f", lat, lon);
            MutableComponent coordComponent = Component.literal(coordText)
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, coordText))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Click to copy coordinates").withStyle(ChatFormatting.YELLOW))));

            source.sendSuccess(() -> prefix()
                    .append(Component.literal("Teleported to ").withStyle(ChatFormatting.WHITE))
                    .append(coordComponent), false);

            final int finalY = teleportY; final String finalYSource = ySource;
            source.sendSuccess(() -> Component.literal(
                    String.format("  MC: %.0f, %d, %.0f (Y: %s)", mcX, finalY, mcZ, finalYSource))
                    .withStyle(ChatFormatting.GRAY), false);

            TerraDensityFunctions df = getDensityFunctions();
            if (df != null) {
                try {
                    TerraDensityFunctions.TerrainColumn col = classifyBounded((int) mcX, (int) mcZ);
                    if (col != null)
                        source.sendSuccess(() -> Component.literal(
                                String.format("  Elevation: %.1fm | Type: %s | Surface Y: %d",
                                        col.elevationMeters, col.type, col.surfaceY))
                                .withStyle(ChatFormatting.GRAY), false);
                } catch (Exception ignored) {}
            }

            try {
                double[] verify = proj.toGeo(mcX, mcZ);
                source.sendSuccess(() -> Component.literal(
                        String.format("  Verify: Lat %.4f, Lon %.4f", verify[1], verify[0]))
                        .withStyle(ChatFormatting.DARK_GRAY), false);
            } catch (Exception ignored) {}

            return 1;
        } catch (Exception e) {
            source.sendFailure(prefix().append(Component.literal("Failed: " + e.getMessage())
                    .withStyle(ChatFormatting.RED)));
            return 0;
        }
    }

    private static int showWhere(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            GeographicProjection proj = getProjection();
            if (proj == null) {
                source.sendFailure(prefix().append(Component.literal("BTE projection not loaded!")
                        .withStyle(ChatFormatting.RED)));
                return 0;
            }

            int bx = player.getBlockX(), by = player.getBlockY(), bz = player.getBlockZ();
            double[] geo;
            try { geo = proj.toGeo(bx, bz); }
            catch (OutOfProjectionBoundsException e) {
                source.sendFailure(prefix().append(Component.literal("You are outside BTE projection bounds!")
                        .withStyle(ChatFormatting.RED)));
                return 0;
            }

            double lon = geo[0], lat = geo[1];
            String coordText = String.format("%.6f, %.6f", lat, lon);
            MutableComponent coordComponent = Component.literal(coordText)
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, coordText))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Click to copy coordinates").withStyle(ChatFormatting.YELLOW))));

            source.sendSuccess(() -> prefix()
                    .append(Component.literal("You are at ").withStyle(ChatFormatting.WHITE))
                    .append(coordComponent), false);

            source.sendSuccess(() -> Component.literal(String.format("  MC: %d, %d, %d", bx, by, bz))
                    .withStyle(ChatFormatting.GRAY), false);

            source.sendSuccess(() -> Component.literal(
                    String.format("  Source: %s", TerraChunkGenerator.getActiveSource().toUpperCase()))
                    .withStyle(ChatFormatting.GRAY), false);

            TerraDensityFunctions df = getDensityFunctions();
            if (df != null) {
                try {
                    TerraDensityFunctions.TerrainColumn col = classifyBounded(bx, bz);
                    if (col != null)
                        source.sendSuccess(() -> Component.literal(
                                String.format("  Elevation: %.1fm | Type: %s", col.elevationMeters, col.type))
                                .withStyle(ChatFormatting.GRAY), false);
                    else
                        source.sendSuccess(() -> Component.literal(
                                "  Elevation: (loading tiles...)")
                                .withStyle(ChatFormatting.GRAY), false);
                } catch (Exception ignored) {}
            }

            String tpllCmd = String.format("/tpll %.6f %.6f %d", lat, lon, by);
            MutableComponent tpllComponent = Component.literal("  " + tpllCmd)
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, tpllCmd))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Click to copy tpll command").withStyle(ChatFormatting.YELLOW))));
            source.sendSuccess(() -> tpllComponent, false);

            String mapsUrl = String.format("https://maps.google.com/?q=%.6f,%.6f", lat, lon);
            MutableComponent mapsComponent = Component.literal("  Google Maps")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.BLUE).withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, mapsUrl))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Click to open in browser").withStyle(ChatFormatting.YELLOW))));
            source.sendSuccess(() -> mapsComponent, false);

            return 1;
        } catch (Exception e) {
            source.sendFailure(prefix().append(Component.literal("Failed: " + e.getMessage())
                    .withStyle(ChatFormatting.RED)));
            return 0;
        }
    }

    private static int showPlaces(CommandSourceStack source) {
        source.sendSuccess(() -> prefix()
                .append(Component.literal("Famous locations:").withStyle(ChatFormatting.WHITE)), false);

        String[][] places = {
                {"Mount Everest",        "27.9881",   "86.9250"},
                {"Mariana Trench",       "11.3493",   "142.1996"},
                {"New York, USA",        "40.7128",   "-74.0060"},
                {"London, UK",           "51.5074",   "-0.1278"},
                {"Tokyo, Japan",         "35.6762",   "139.6503"},
                {"Sydney, Australia",    "-33.8688",  "151.2093"},
                {"Amazon River",         "-3.1190",   "-59.9820"},
                {"Grand Canyon",         "36.1069",   "-112.1129"},
                {"Dead Sea",             "31.5000",   "35.5000"},
                {"Great Barrier Reef",   "-18.2871",  "147.6992"},
                {"Sahara Desert",        "23.4162",   "25.6628"},
                {"Ganges River",         "25.2677",   "83.0046"},
                {"Niagara Falls",        "43.0962",   "-79.0377"},
                {"Mt Kilimanjaro",       "-3.0674",   "37.3556"},
        };

        GeographicProjection proj = getProjection();
        TerraDensityFunctions df = getDensityFunctions();

        for (String[] place : places) {
            String name = place[0], lat = place[1], lon = place[2];
            String tpllCmd = String.format("/tpll %s %s", lat, lon);

            String extraInfo = "";
            if (proj != null) {
                try {
                    double[] mc = proj.fromGeo(Double.parseDouble(lon), Double.parseDouble(lat));
                    String mcPart = String.format("MC: %.0f, %.0f", mc[0], mc[1]);
                    TerraDensityFunctions.TerrainColumn col =
                            df != null ? classifyBounded((int) mc[0], (int) mc[1]) : null;
                    if (col != null) {
                        extraInfo = String.format(" (%s | %.0fm | %s)", mcPart, col.elevationMeters, col.type);
                    } else {
                        extraInfo = String.format(" (%s)", mcPart);
                    }
                } catch (Exception ignored) { extraInfo = " (outside bounds)"; }
            }

            final String finalExtraInfo = extraInfo;

            MutableComponent cmdComponent = Component.literal(tpllCmd)
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, tpllCmd))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Click to paste command").withStyle(ChatFormatting.YELLOW))));

            MutableComponent copyButton = Component.literal(" [\uD83D\uDCCB]")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, tpllCmd))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Click to copy command").withStyle(ChatFormatting.YELLOW))));

            MutableComponent runButton = Component.literal(" [▶]")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, tpllCmd))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Click to teleport!").withStyle(ChatFormatting.GREEN))));

            source.sendSuccess(() ->
                    Component.literal("  ")
                            .append(Component.literal(name + ": ").withStyle(ChatFormatting.YELLOW))
                            .append(cmdComponent)
                            .append(copyButton)
                            .append(runButton)
                            .append(Component.literal(finalExtraInfo).withStyle(ChatFormatting.DARK_GRAY)),
                    false);
        }

        source.sendSuccess(() -> Component.literal("")
                .append(Component.literal("  Tip: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("Click command to paste | [\uD83D\uDCCB] to copy | [▶] to teleport!")
                        .withStyle(ChatFormatting.DARK_GRAY)), false);

        return 1;
    }

    private static MutableComponent prefix() {
        return Component.literal("[Terra++] ").withStyle(ChatFormatting.GOLD);
    }
}