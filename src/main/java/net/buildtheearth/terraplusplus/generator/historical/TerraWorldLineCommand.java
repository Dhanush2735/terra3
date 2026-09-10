// ============================================================
// FILE: TerraWorldLineCommand.java
// The 6-axis travel interface:
//   x, y, z          — /tp (vanilla, axes 1-3)
//   year             — /terra timejump   (axis 4)
//   timeline branch  — /terra worldline branch ... (axis 5)
//   universe         — /terra worldline universe ... (axis 6)
//
// Hybrid model:
//   Branch switch  = in-place morph of the current world via the
//                    time-jump refresh machinery (same Earth,
//                    different history).
//   Universe travel = teleport into a separate dimension whose
//                    TerraChunkGenerator carries a universe
//                    transform (different Earth entirely).
//                    Chunks persist per-universe.
// ============================================================
package net.buildtheearth.terraplusplus.generator.historical;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.buildtheearth.terraplusplus.generator.TerraChunkGenerator;
import net.buildtheearth.terraplusplus.generator.UniverseTransforms;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

@Mod.EventBusSubscriber(modid = "terraplusplus")
public class TerraWorldLineCommand {

    private static final org.apache.logging.log4j.Logger LOGGER =
        org.apache.logging.log4j.LogManager
            .getLogger("Terra++WorldLine");

    @SubscribeEvent
    public static void onRegisterCommands(
            RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("terra")
            .then(Commands.literal("worldline")

                // ── /terra worldline status ───────────────────
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("status")
                    .executes(ctx -> status(ctx.getSource())))

                // ── /terra worldline paradox ──────────────────
                .then(Commands.literal("paradox")
                    .executes(ctx -> {
                        CommandSourceStack src = ctx.getSource();
                        ServerPlayer sp;
                        try {
                            sp = src.getPlayerOrException();
                        } catch (Exception e) {
                            src.sendFailure(Component.literal(
                                "§c[Terra++] Players only."));
                            return 0;
                        }
                        double st = ParadoxEngine.getStress(
                            sp.getUUID());
                        int pct = (int) (100 * st
                            / ParadoxMath.PARADOX_THRESHOLD);
                        String bandTxt = switch (
                                ParadoxMath.band(st)) {
                            case 0 -> "§astable§r";
                            case 1 -> "§etrembling§r";
                            case 2 -> "§ccracking§r";
                            default -> "§4RUPTURING§r";
                        };
                        src.sendSuccess(() -> Component.literal(
                            "§6[Terra++]§r Temporal stress: §d"
                            + pct + "%§r — reality is "
                            + bandTxt + "\n§7Breaking dated "
                            + "structures in the past "
                            + "contradicts recorded history. "
                            + "At 100% reality ruptures into a "
                            + "new universe.§r"), false);
                        return 1;
                    }))

                // ── /terra worldline branch ... ───────────────
                .then(Commands.literal("branch")
                    .then(Commands.literal("list")
                        .executes(ctx ->
                            listBranches(ctx.getSource())))
                    .then(Commands.literal("create")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("id",
                                StringArgumentType.word())
                            .then(Commands.argument(
                                    "divergence_year",
                                    IntegerArgumentType.integer(
                                        TerraTimeManager.MIN_YEAR,
                                        TerraTimeManager.MAX_YEAR))
                                .then(Commands.argument("cause",
                                        StringArgumentType
                                            .greedyString())
                                    .executes(ctx -> createBranch(
                                        ctx.getSource(),
                                        StringArgumentType.getString(
                                            ctx, "id"),
                                        IntegerArgumentType.getInteger(
                                            ctx, "divergence_year"),
                                        StringArgumentType.getString(
                                            ctx, "cause"))))
                                .executes(ctx -> createBranch(
                                    ctx.getSource(),
                                    StringArgumentType.getString(
                                        ctx, "id"),
                                    IntegerArgumentType.getInteger(
                                        ctx, "divergence_year"),
                                    null)))))
                    .then(Commands.literal("switch")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("id",
                                StringArgumentType.word())
                            .executes(ctx -> switchBranch(
                                ctx.getSource(),
                                StringArgumentType.getString(
                                    ctx, "id"))))))

                // ── /terra worldline universe ... ─────────────
                .then(Commands.literal("universe")
                    .then(Commands.literal("list")
                        .executes(ctx ->
                            listUniverses(ctx.getSource())))
                    .then(Commands.literal("travel")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("id",
                                StringArgumentType.word())
                            .executes(ctx -> travelUniverse(
                                ctx.getSource(),
                                StringArgumentType.getString(
                                    ctx, "id")))))
                    // /terra worldline universe forge
                    //   <slot 1-5> <seaOffset> <elevScale>
                    //   <invert> <mirror> [name...]
                    .then(Commands.literal("forge")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("slot",
                                IntegerArgumentType.integer(1, 5))
                            .then(Commands.argument("sea_offset_m",
                                    com.mojang.brigadier.arguments
                                        .DoubleArgumentType
                                        .doubleArg(-2000, 2000))
                                .then(Commands.argument("elev_scale",
                                        com.mojang.brigadier.arguments
                                            .DoubleArgumentType
                                            .doubleArg(-10, 10))
                                    .then(Commands.argument("invert",
                                            com.mojang.brigadier.arguments
                                                .BoolArgumentType.bool())
                                        .then(Commands.argument("mirror",
                                                com.mojang.brigadier.arguments
                                                    .BoolArgumentType.bool())
                                            .executes(ctx -> forgeUniverse(
                                                ctx, null))
                                            .then(Commands.argument("name",
                                                    StringArgumentType
                                                        .greedyString())
                                                .executes(ctx ->
                                                    forgeUniverse(ctx,
                                                        StringArgumentType
                                                            .getString(ctx,
                                                                "name")))))))))))
            ));
    }

    private static int forgeUniverse(
            com.mojang.brigadier.context.CommandContext<
                CommandSourceStack> ctx, String name) {
        CommandSourceStack src = ctx.getSource();
        int slot = IntegerArgumentType.getInteger(ctx, "slot");
        double sea = com.mojang.brigadier.arguments
            .DoubleArgumentType.getDouble(ctx, "sea_offset_m");
        double scale = com.mojang.brigadier.arguments
            .DoubleArgumentType.getDouble(ctx, "elev_scale");
        boolean invert = com.mojang.brigadier.arguments
            .BoolArgumentType.getBool(ctx, "invert");
        boolean mirror = com.mojang.brigadier.arguments
            .BoolArgumentType.getBool(ctx, "mirror");
        String id = "custom" + slot;

        var params = UniverseTransforms.forge(
            id, sea, scale, invert, mirror, name);
        if (params == null) {
            src.sendFailure(Component.literal(
                "§c[Terra++] Bad universe slot."));
            return 0;
        }
        try {
            UniverseTransforms.saveForged(src.getServer()
                .getWorldPath(LevelResource.ROOT));
        } catch (Exception ignored) {}

        src.sendSuccess(() -> Component.literal(
            "§6[Terra++]§r Universe §d" + id
            + "§r forged: §7" + params.describe() + "§r\n"
            + "§7Travel with §e/terra worldline universe "
            + "travel " + id + "§r\n"
            + "§8(Chunks already generated in this slot keep "
            + "their old physics until the dimension's region "
            + "files are deleted.)"), true);
        return 1;
    }

    // ── STATUS ────────────────────────────────────────────────

    private static int status(CommandSourceStack src) {
        int year = TerraTimeManager.getCurrentYear();
        WorldLineRegistry.Branch b =
            WorldLineRegistry.getActiveBranch();
        String universe = currentUniverse(src);
        BlockPos pos = BlockPos.containing(src.getPosition());
        src.sendSuccess(() -> Component.literal(
            "§6[Terra++]§r World-line coordinate:\n"
            + "  §7x,y,z:§r " + pos.getX() + ", "
                + pos.getY() + ", " + pos.getZ() + "\n"
            + "  §7year (axis 4):§r §e"
                + TerraTimeManager.formatYear(year)
                + "§r (" + TerraTimeManager.getEraName(year)
                + ")\n"
            + "  §7timeline (axis 5):§r §b" + b.id + "§r"
                + (b.salt == 0 ? " (prime timeline)"
                   : " — diverged "
                     + TerraTimeManager.formatYear(
                         b.divergenceYear)
                     + ": " + b.cause) + "\n"
            + "  §7universe (axis 6):§r §d" + universe + "§r — "
                + UniverseTransforms.get(universe)
                    .description()),
            false);
        return 1;
    }

    // ── BRANCHES (axis 5) ─────────────────────────────────────

    private static int listBranches(CommandSourceStack src) {
        StringBuilder sb = new StringBuilder(
            "§6[Terra++]§r Timeline branches:\n");
        for (WorldLineRegistry.Branch b :
                WorldLineRegistry.getBranches()) {
            boolean active = b.id.equals(
                WorldLineRegistry.getActiveBranchId());
            sb.append(active ? "  §a▶ " : "  §7  ")
              .append(b.id).append("§r");
            if (b.salt != 0)
                sb.append(" §8(diverged ")
                  .append(TerraTimeManager.formatYear(
                      b.divergenceYear))
                  .append(": ").append(b.cause).append(")§r");
            sb.append("\n");
        }
        String out = sb.toString();
        src.sendSuccess(
            () -> Component.literal(out.stripTrailing()),
            false);
        return 1;
    }

    private static int createBranch(CommandSourceStack src,
            String id, int divergenceYear, String cause) {
        WorldLineRegistry.Branch b =
            WorldLineRegistry.createBranch(
                id, divergenceYear, cause);
        if (b == null) {
            src.sendFailure(Component.literal(
                "§c[Terra++] Invalid branch id (a-z, 0-9, "
                + "_ or -, max 32 chars)."));
            return 0;
        }
        persist(src);
        src.sendSuccess(() -> Component.literal(
            "§6[Terra++]§r Branch §b" + b.id
            + "§r ready (diverged "
            + TerraTimeManager.formatYear(b.divergenceYear)
            + "). Switch with §e/terra worldline branch "
            + "switch " + b.id + "§r."), false);
        return 1;
    }

    private static int switchBranch(CommandSourceStack src,
            String id) {
        if (id.equals(WorldLineRegistry.getActiveBranchId())) {
            src.sendFailure(Component.literal(
                "§c[Terra++] Already on branch '" + id + "'."));
            return 0;
        }
        if (!WorldLineRegistry.setActiveBranch(id)) {
            src.sendFailure(Component.literal(
                "§c[Terra++] Unknown branch '" + id
                + "'. Create it first."));
            return 0;
        }
        persist(src);

        // Morph the loaded world in place — the branch salt has
        // changed every chunk's staleness fingerprint, so this
        // is driven by the identical machinery as a time jump.
        ServerLevel level = getOverworld();
        if (level != null) {
            int year = TerraTimeManager.getCurrentYear();
            PersistentChunkRefreshIndex
                .advanceRefreshGeneration(level, year);
            PersistentChunkRefreshIndex.beginTimeJumpAudit(
                PersistentChunkRefreshIndex.getGeneration(),
                year);
            TerraChunkGenerator.resetRefreshQueuesForTimeJump();
            TerraChunkGenerator
                .captureTimeJumpBoundaryRing(level);
            TerraChunkGenerator
                .scheduleLoadedChunkRefresh(level);
            TerraChunkGenerator.postJumpRescanCountdown = 200;
        }

        WorldLineRegistry.Branch b =
            WorldLineRegistry.getActiveBranch();
        src.sendSuccess(() -> Component.literal(
            "§6[Terra++]§r Crossed to timeline §b" + b.id
            + "§r" + (b.salt == 0 ? " (prime)"
              : " — diverged "
                + TerraTimeManager.formatYear(b.divergenceYear)
                + ": " + b.cause)
            + "\n§7The world is re-forming around you…§r"),
            true);
        return 1;
    }

    // ── UNIVERSES (axis 6) ────────────────────────────────────

    private static int listUniverses(CommandSourceStack src) {
        String current = currentUniverse(src);
        StringBuilder sb = new StringBuilder(
            "§6[Terra++]§r Known universes:\n");
        for (var e : UniverseTransforms.all().entrySet()) {
            boolean active = e.getKey().equals(current);
            sb.append(active ? "  §a▶ §d" : "  §7  §d")
              .append(e.getKey()).append("§r §8— ")
              .append(e.getValue().description())
              .append("§r\n");
        }
        String out = sb.toString();
        src.sendSuccess(
            () -> Component.literal(out.stripTrailing()),
            false);
        return 1;
    }

    private static int travelUniverse(CommandSourceStack src,
            String id) {
        if (!UniverseTransforms.exists(id)) {
            src.sendFailure(Component.literal(
                "§c[Terra++] Unknown universe '" + id
                + "'. See /terra worldline universe list"));
            return 0;
        }
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception e) {
            src.sendFailure(Component.literal(
                "§c[Terra++] Universe travel needs a player."));
            return 0;
        }

        ResourceKey<Level> targetKey =
            UniverseTransforms.PRIME.equals(id)
                ? Level.OVERWORLD
                : ResourceKey.create(
                    net.minecraft.core.registries
                        .Registries.DIMENSION,
                    new ResourceLocation("terraplusplus",
                        "universe_" + id));
        ServerLevel target =
            player.server.getLevel(targetKey);
        if (target == null) {
            src.sendFailure(Component.literal(
                "§c[Terra++] Dimension " + targetKey.location()
                + " is not loaded. (Datapack dimensions load "
                + "at world start — restart after installing.)"));
            return 0;
        }

        // Same x/z world-line position, surface-snapped y in
        // the destination universe: the elevation there is
        // genuinely different (drowned/inverted/...).
        BlockPos here = player.blockPosition();
        int y = target.getHeight(
            Heightmap.Types.WORLD_SURFACE,
            here.getX(), here.getZ());
        // Unloaded destination chunks report min build height;
        // fall back to current y so we never bury the player —
        // terrain forms around them instead.
        if (y <= target.getMinBuildHeight() + 1)
            y = Math.max(here.getY(), 80);
        final BlockPos dest =
            new BlockPos(here.getX(), y + 1, here.getZ());

        player.teleportTo(target,
            dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5,
            player.getYRot(), player.getXRot());

        var u = UniverseTransforms.get(id);
        src.sendSuccess(() -> Component.literal(
            "§6[Terra++]§r Crossed into universe §d" + id
            + "§r\n§7" + u.description() + "§r"), true);
        LOGGER.info("UNIVERSE travel: {} -> {} at [{}, {}]",
            player.getName().getString(), id,
            here.getX(), here.getZ());
        return 1;
    }

    // ── HELPERS ───────────────────────────────────────────────

    private static String currentUniverse(
            CommandSourceStack src) {
        try {
            ServerLevel lvl = src.getLevel();
            if (lvl.getChunkSource().getGenerator()
                    instanceof TerraChunkGenerator tg)
                return tg.getUniverseId();
            String path = lvl.dimension().location().getPath();
            if (path.startsWith("universe_"))
                return path.substring("universe_".length());
        } catch (Exception ignored) {}
        return UniverseTransforms.PRIME;
    }

    private static void persist(CommandSourceStack src) {
        try {
            WorldLineRegistry.save(src.getServer()
                .getWorldPath(LevelResource.ROOT));
        } catch (Exception e) {
            LOGGER.warn("worldline persist failed: {}",
                e.getMessage());
        }
    }

    private static ServerLevel getOverworld() {
        try {
            var server =
                ServerLifecycleHooks.getCurrentServer();
            return server == null
                ? null : server.overworld();
        } catch (Exception e) {
            return null;
        }
    }
}
