// ============================================================
// FILE: WorldLineRegistry.java
// Axis 5 of the world-line coordinate system:
//   (x, y, z, year, BRANCH, universe)
//
// A branch is an alternative timeline sharing the same Earth.
// Branch switches morph the loaded world in place through the
// existing time-jump refresh machinery; the branch changes WHAT
// history produced at the current year.
//
// Effects model:
//   - Explicitly dated structures (OHM/OSM start_date) are
//     history's fixed points — never altered by a branch.
//   - ESTIMATED dates get a deterministic per-branch offset
//     (seeded default), so undated cities grow earlier/later.
//   - A BranchEffects provider (Eternal Engine's event system)
//     can override both the offset and per-structure existence
//     ("this building was never built in this timeline").
//
// The chunk staleness fingerprint folds the branch salt into the
// per-chunk short stored by PersistentChunkRefreshIndex, so
// switching branch marks chunks stale exactly like a year jump.
// ============================================================
package net.buildtheearth.terraplusplus.generator.historical;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class WorldLineRegistry {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++WorldLine");

    public static final String MAIN_BRANCH = "main";
    private static final String SAVE_FILE = "terra_worldline.dat";
    private static final int SAVE_MAGIC   = 0x54574C31; // "TWL1"

    // ── BRANCH EFFECTS SPI ────────────────────────────────────
    /**
     * Implemented by an external driver (Eternal Engine's
     * timeline/event system) to give a branch meaning derived
     * from real divergence events. When no provider is
     * registered, a deterministic seeded default applies.
     */
    public interface BranchEffects {
        /**
         * Offset (in years) applied to ESTIMATED structure dates
         * near (lat, lon). Positive = this area developed later
         * in this timeline. Explicit start_date tags are never
         * offset.
         */
        int estimatedYearOffset(double lat, double lon);

        /**
         * False if this structure was never built in this
         * timeline (e.g. its founding event was prevented).
         * osmId 0 (ML footprints) is always asked too.
         */
        boolean structureExists(long osmId, double lat, double lon);
    }

    // ── BRANCH RECORD ─────────────────────────────────────────
    public static final class Branch {
        public final String id;
        public final String parentId;
        public final int    divergenceYear;
        public final String cause;
        public final long   salt;

        Branch(String id, String parentId,
                int divergenceYear, String cause) {
            this.id             = id;
            this.parentId       = parentId;
            this.divergenceYear = divergenceYear;
            this.cause          = cause;
            // Deterministic salt: same branch id = same world
            // on every server, every session.
            long h = 1125899906842597L;
            for (int i = 0; i < id.length(); i++)
                h = 31 * h + id.charAt(i);
            this.salt = id.equals(MAIN_BRANCH) ? 0L : (h | 1L);
        }
    }

    // ── STATE ─────────────────────────────────────────────────
    private static final Map<String, Branch> branches =
        new ConcurrentHashMap<>();
    private static final Map<String, BranchEffects> providers =
        new ConcurrentHashMap<>();
    private static volatile String activeBranchId = MAIN_BRANCH;
    private static volatile Branch activeBranch =
        new Branch(MAIN_BRANCH, null,
            TerraTimeManager.MIN_YEAR,
            "Original historical timeline");

    static {
        branches.put(MAIN_BRANCH, activeBranch);
    }

    private WorldLineRegistry() {}

    // ── PUBLIC API (also the Eternal Engine socket) ───────────

    public static String getActiveBranchId() {
        return activeBranchId;
    }

    public static Branch getActiveBranch() {
        return activeBranch;
    }

    public static boolean isMainTimeline() {
        return activeBranch.salt == 0L;
    }

    public static Collection<Branch> getBranches() {
        return Collections.unmodifiableCollection(
            branches.values());
    }

    public static Branch getBranch(String id) {
        return id == null ? null : branches.get(id);
    }

    /**
     * Create (or return existing) branch. Deterministic: the
     * same id always produces the same alternative history.
     */
    public static Branch createBranch(String id,
            int divergenceYear, String cause) {
        if (id == null || id.isBlank()
                || !id.matches("[a-zA-Z0-9_\\-]{1,32}"))
            return null;
        return branches.computeIfAbsent(id, k -> {
            Branch b = new Branch(k, activeBranchId,
                divergenceYear,
                cause == null || cause.isBlank()
                    ? "Unrecorded divergence" : cause);
            LOGGER.info(
                "Branch created: {} (diverged {} from {}: {})",
                k, divergenceYear, b.parentId, b.cause);
            return b;
        });
    }

    /**
     * Switch the active timeline. The CALLER is responsible for
     * driving the world refresh (advanceRefreshGeneration +
     * scheduleLoadedChunkRefresh), exactly like a time jump —
     * see TerraWorldLineCommand.
     *
     * @return true if the branch exists and is now active.
     */
    public static boolean setActiveBranch(String id) {
        Branch b = branches.get(id);
        if (b == null) return false;
        Branch old = activeBranch;
        activeBranch   = b;
        activeBranchId = id;
        LOGGER.info("WORLDLINE branch switch: {} -> {}",
            old.id, id);
        return true;
    }

    /**
     * Eternal Engine (or any driver mod) registers the
     * event-derived meaning of a branch here. Overrides the
     * seeded default for that branch id.
     */
    public static void setBranchEffects(String branchId,
            BranchEffects effects) {
        if (branchId == null) return;
        if (effects == null) providers.remove(branchId);
        else providers.put(branchId, effects);
        LOGGER.info("BranchEffects provider {} for '{}'",
            effects == null ? "removed" : "registered",
            branchId);
    }

    // ── GENERATION HOOKS ──────────────────────────────────────

    /**
     * Fold the branch salt into the per-chunk staleness short.
     * Main timeline returns the year short unchanged, keeping
     * old region files valid. Never returns Short.MIN_VALUE
     * (the UNSET sentinel).
     */
    public static short fingerprintShort(short yearShort) {
        long salt = activeBranch.salt;
        if (salt == 0L) return yearShort;
        int h = yearShort * 31
            ^ (int) (salt ^ (salt >>> 32));
        // 0..32767: positive shorts only, cannot be UNSET.
        return (short) (h & 0x7FFF);
    }

    /**
     * Branch adjustment for an ESTIMATED structure date.
     * Explicit start_date tags must never pass through here.
     */
    public static int adjustEstimatedYear(int estimatedYear,
            double lat, double lon) {
        Branch b = activeBranch;
        if (b.salt == 0L) return estimatedYear;
        BranchEffects fx = providers.get(b.id);
        int offset = fx != null
            ? fx.estimatedYearOffset(lat, lon)
            : seededOffset(b.salt, lat, lon);
        return estimatedYear + offset;
    }

    /**
     * Whether a structure exists at all in this timeline.
     * Default: everything exists (removal is event-driven and
     * belongs to the BranchEffects provider).
     */
    public static boolean structureAllowed(long osmId,
            double lat, double lon) {
        Branch b = activeBranch;
        if (b.salt == 0L) return true;
        BranchEffects fx = providers.get(b.id);
        return fx == null
            || fx.structureExists(osmId, lat, lon);
    }

    /**
     * Seeded default: smooth ±150-year development shift that
     * varies over ~0.02° cells. Deterministic per branch.
     * Gives a visibly different (older/younger) city skyline
     * per district until an event provider supplies real
     * divergence consequences.
     */
    private static int seededOffset(long salt,
            double lat, double lon) {
        long cLat = (long) Math.floor(lat / 0.02);
        long cLon = (long) Math.floor(lon / 0.02);
        long h = salt;
        h ^= cLat * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 27) * 0xBF58476D1CE4E5B9L;
        h ^= cLon * 0xC2B2AE3D27D4EB4FL;
        h = Long.rotateLeft(h, 31) * 0x94D049BB133111EBL;
        h ^= h >>> 33;
        return (int) (h % 151L)
            - 75 + (int) ((salt >>> 8) % 76L)
            - 37; // range roughly [-112, +112]
    }

    // ── PERSISTENCE ───────────────────────────────────────────

    public static void save(Path worldRoot) {
        try {
            Path f = worldRoot.resolve(SAVE_FILE);
            try (DataOutputStream out =
                    new DataOutputStream(
                    new BufferedOutputStream(
                    Files.newOutputStream(f)))) {
                out.writeInt(SAVE_MAGIC);
                out.writeUTF(activeBranchId);
                List<Branch> list =
                    new ArrayList<>(branches.values());
                out.writeInt(list.size());
                for (Branch b : list) {
                    out.writeUTF(b.id);
                    out.writeUTF(b.parentId == null
                        ? "" : b.parentId);
                    out.writeInt(b.divergenceYear);
                    out.writeUTF(b.cause);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("worldline save failed: {}",
                e.getMessage());
        }
    }

    public static void load(Path worldRoot) {
        try {
            Path f = worldRoot.resolve(SAVE_FILE);
            if (!Files.exists(f)) return;
            try (DataInputStream in =
                    new DataInputStream(
                    new BufferedInputStream(
                    Files.newInputStream(f)))) {
                if (in.readInt() != SAVE_MAGIC) return;
                String active = in.readUTF();
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    String id     = in.readUTF();
                    String parent = in.readUTF();
                    int    year   = in.readInt();
                    String cause  = in.readUTF();
                    branches.computeIfAbsent(id, k ->
                        new Branch(k,
                            parent.isEmpty() ? null : parent,
                            year, cause));
                }
                Branch b = branches.get(active);
                if (b != null) {
                    activeBranch   = b;
                    activeBranchId = active;
                }
                LOGGER.info(
                    "worldline loaded: {} branches, active={}",
                    branches.size(), activeBranchId);
            }
        } catch (Exception e) {
            LOGGER.warn("worldline load failed: {}",
                e.getMessage());
        }
    }
}
