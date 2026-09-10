// ============================================================
// FILE: ParadoxEngine.java
// Axis-6 birth mechanism: BREAKING A PARADOX RUPTURES REALITY.
//
//   consistent change of the past -> timeline branch (axis 5)
//   paradox (contradiction)       -> universe rupture (axis 6)
//
// Watches block breaks while time travel is active. Breaking
// blocks of an explicitly-dated structure (a FIXED POINT of
// history) in the past accumulates temporal stress; past the
// threshold, reality ruptures: the next free universe slot is
// forged with physics warped by the paradox, and the offender
// is cast into the newborn universe.
//
// Pure mechanics live in ParadoxMath (unit-tested standalone).
// ============================================================
package net.buildtheearth.terraplusplus.generator.historical;

import net.buildtheearth.terraplusplus.generator.TerraChunkGenerator;
import net.buildtheearth.terraplusplus.generator.UniverseTransforms;
import net.buildtheearth.terraplusplus.generator.osm.OsmStructureIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = "terraplusplus")
public final class ParadoxEngine {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++Paradox");

    /** Per-player temporal stress with lazy decay. */
    private static final class Stress {
        double value; long updatedMs;
    }
    private static final ConcurrentHashMap<UUID, Stress>
        stress = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Integer>
        lastBand = new ConcurrentHashMap<>();

    private ParadoxEngine() {}

    // ── BLOCK BREAK HOOK ──────────────────────────────────────

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent ev) {
        try {
            if (!(ev.getPlayer() instanceof ServerPlayer p))
                return;
            if (!(p.level() instanceof ServerLevel level))
                return;
            if (!TerraTimeManager.isTimeEnabled()) return;
            int year = TerraTimeManager.getCurrentYear();
            if (year >= ParadoxMath.PRESENT_YEAR) return;

            TerraChunkGenerator gen =
                TerraChunkGenerator.activeInstance;
            if (gen == null) return;

            BlockPos pos = ev.getPos();
            double[] geo = gen.toGeoPublic(
                pos.getX(), pos.getZ());
            if (geo == null) return;
            double lon = geo[0], lat = geo[1];

            // Find a dated structure whose footprint contains
            // the broken block. Memory-cached tiles only —
            // never a network wait on the break path.
            OsmStructureIndex.OsmStructure hit =
                findFixedPointAt(gen, lat, lon);
            if (hit == null) return;
            if (!ParadoxMath.isParadox(hit.startYear,
                    hit.endYear, year,
                    HistoricalDateParser.UNKNOWN_YEAR))
                return;

            double add = ParadoxMath.violationStress(year,
                ParadoxMath.typeWeight(hit.subType, hit.name));

            Stress st = stress.computeIfAbsent(p.getUUID(),
                k -> new Stress());
            double now;
            synchronized (st) {
                long t = System.currentTimeMillis();
                st.value = ParadoxMath.decay(st.value,
                    st.updatedMs == 0
                        ? 0 : t - st.updatedMs) + add;
                st.updatedMs = t;
                now = st.value;
            }

            LOGGER.info("PARADOX violation by {}: {} '{}' "
                + "(start {}) broken at year {} -> +{} "
                + "stress={}",
                p.getName().getString(), hit.subType,
                hit.name == null ? "?" : hit.name,
                hit.startYear, year,
                String.format("%.0f", add),
                String.format("%.0f", now));

            if (ParadoxMath.ruptures(now)) {
                synchronized (st) { st.value = 0; }
                lastBand.remove(p.getUUID());
                rupture(level, p, hit, year, now);
            } else {
                feedback(p, now);
            }
        } catch (Exception e) {
            LOGGER.debug("paradox hook error: {}",
                e.getMessage());
        }
    }

    /** Dated, still-standing structure containing this point. */
    private static OsmStructureIndex.OsmStructure
            findFixedPointAt(TerraChunkGenerator gen,
            double lat, double lon) {
        try {
            List<OsmStructureIndex.OsmStructure> list =
                gen.getStructureIndexPublic()
                    .getStructuresAt(lat, lon,
                        gen.getWorldConfig());
            for (OsmStructureIndex.OsmStructure s : list) {
                if (s.startYear
                        == HistoricalDateParser.UNKNOWN_YEAR)
                    continue;
                if (s.type != OsmStructureIndex
                        .StructureType.BUILDING
                    && s.type != OsmStructureIndex
                        .StructureType.BUILDING_PART
                    && s.type != OsmStructureIndex
                        .StructureType.TOWER
                    && s.type != OsmStructureIndex
                        .StructureType.BRIDGE)
                    continue;
                if (s.isArea
                        ? s.containsPoint(lat, lon)
                        : s.distanceToLine(lat, lon) < 6.0)
                    return s;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── FEEDBACK BANDS ────────────────────────────────────────

    private static void feedback(ServerPlayer p, double now) {
        int band = ParadoxMath.band(now);
        Integer prev = lastBand.put(p.getUUID(), band);
        int pct = (int) (100 * now
            / ParadoxMath.PARADOX_THRESHOLD);
        if (band >= 1 && (prev == null || prev < 1))
            p.sendSystemMessage(Component.literal(
                "§5[Paradox]§r §7Reality trembles… you are "
                + "contradicting recorded history. (§d"
                + pct + "%§7)"));
        if (band >= 2 && (prev == null || prev < 2)) {
            p.sendSystemMessage(Component.literal(
                "§5[Paradox]§r §cCracks spread through the "
                + "world-line. Stop, or reality will rupture. "
                + "(§d" + pct + "%§c)"));
            p.level().playSound(null, p.blockPosition(),
                SoundEvents.END_PORTAL_SPAWN,
                SoundSource.AMBIENT, 0.6f, 0.5f);
        }
    }

    // ── RUPTURE ───────────────────────────────────────────────

    private static void rupture(ServerLevel level,
            ServerPlayer p,
            OsmStructureIndex.OsmStructure cause,
            int year, double finalStress) {

        // Deterministic newborn physics from the paradox.
        long seed = ParadoxMath.ruptureSeed(
            cause.osmId, year, finalStress);
        ParadoxMath.RuptureParams rp =
            ParadoxMath.rupturePhysics(seed, finalStress);

        // Claim the first unforged slot; if all five are
        // taken, the rupture overwrites the oldest (slot 1 +
        // seed rotation) — reality reuses scar tissue.
        String slot = null;
        for (int i = 1; i <= UniverseTransforms.CUSTOM_SLOTS;
                i++) {
            var f = UniverseTransforms.getForge("custom" + i);
            if (f == null || f.isIdentity()) {
                slot = "custom" + i;
                break;
            }
        }
        if (slot == null)
            slot = "custom" + (1 + Math.floorMod(seed,
                UniverseTransforms.CUSTOM_SLOTS));

        String structName = cause.name != null
            && !cause.name.isBlank()
            ? cause.name : (cause.subType != null
                ? cause.subType : "structure");
        String uniName = "Rupture of " + structName
            + " (" + TerraTimeManager.formatYear(year) + ")";

        UniverseTransforms.forge(slot, rp.seaOffset,
            rp.elevScale, rp.invert, rp.mirror, uniName);
        try {
            UniverseTransforms.saveForged(level.getServer()
                .getWorldPath(LevelResource.ROOT));
        } catch (Exception ignored) {}

        // Also record the causal history: the paradox's own
        // timeline branch, so the story of WHY survives.
        WorldLineRegistry.createBranch(
            "paradox_" + Long.toHexString(seed & 0xFFFFFF),
            year, "Paradox: " + structName
                + " destroyed before its own history");

        LOGGER.info("REALITY RUPTURE by {}: '{}' year {} -> "
            + "{} forged (sea {}m scale {} invert {} "
            + "mirror {})",
            p.getName().getString(), structName, year, slot,
            String.format("%.0f", rp.seaOffset),
            String.format("%.2f", rp.elevScale),
            rp.invert, rp.mirror);

        // Broadcast + cast the offender into the newborn
        // universe at the same world-line x/z.
        level.getServer().getPlayerList().broadcastSystemMessage(
            Component.literal("§5§l[PARADOX]§r §d"
                + p.getName().getString()
                + "§r destroyed §e" + structName
                + "§r before its own history. Reality "
                + "ruptured — a new universe was born: §d"
                + slot + "§r (§7" + uniName + "§r)"), false);

        ResourceKey<Level> key = ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            new ResourceLocation("terraplusplus",
                "universe_" + slot));
        ServerLevel target = level.getServer().getLevel(key);
        if (target != null) {
            BlockPos here = p.blockPosition();
            int y = target.getHeight(
                Heightmap.Types.WORLD_SURFACE,
                here.getX(), here.getZ());
            if (y <= target.getMinBuildHeight() + 1)
                y = Math.max(here.getY(), 120);
            level.playSound(null, here,
                SoundEvents.END_PORTAL_SPAWN,
                SoundSource.AMBIENT, 1.0f, 0.3f);
            p.teleportTo(target, here.getX() + 0.5, y + 1,
                here.getZ() + 0.5, p.getYRot(), p.getXRot());
            p.sendSystemMessage(Component.literal(
                "§5You fall through the crack you made. "
                + "This world is your paradox: §d"
                + uniName + "§r"));
        } else {
            p.sendSystemMessage(Component.literal(
                "§5[Paradox]§r Universe " + slot + " forged "
                + "but its dimension is not loaded — travel "
                + "with §e/terra worldline universe travel "
                + slot + "§r after restart."));
        }
    }

    /** Current stress (decayed) — for commands/HUD. */
    public static double getStress(UUID player) {
        Stress st = stress.get(player);
        if (st == null) return 0;
        synchronized (st) {
            long t = System.currentTimeMillis();
            st.value = ParadoxMath.decay(st.value,
                st.updatedMs == 0 ? 0 : t - st.updatedMs);
            st.updatedMs = t;
            return st.value;
        }
    }
}
