// ============================================================
// FILE: UniverseTransforms.java
// Axis 6 of the world-line coordinate system:
//   (x, y, z, year, branch, UNIVERSE)
//
// A universe is a physically different Earth, hosted in its own
// Minecraft dimension (hybrid model: timeline branches morph the
// prime world in place, universes get separate dimensions with
// separate chunk storage, so builds persist per-universe).
//
// Each universe is a pure, deterministic transform applied at the
// two chokepoints every terrain fact flows through:
//   - geography  (projection.toGeo / fromGeo)
//   - elevation  (getElevationInterpolated)
// Everything downstream — coastlines, rivers, biomes, OSM
// structure placement — inherits the transform automatically,
// because it all consumes those two functions.
// ============================================================
package net.buildtheearth.terraplusplus.generator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

public final class UniverseTransforms {

    public static final String PRIME = "prime";

    /** A universe: rule-breaking but deterministic. */
    public interface Universe {
        String id();
        String description();

        /** Transform elevation (metres vs sea level). */
        default double mapElevation(double elev,
                double lat, double lon) {
            return elev;
        }

        /**
         * Transform geographic coords AFTER projection.toGeo.
         * Array is Terra++ order [lon, lat]; return the same
         * array mutated (or a new one).
         */
        default double[] mapGeo(double[] lonLat) {
            return lonLat;
        }

        /**
         * Inverse transform BEFORE projection.fromGeo.
         * Input/output order [lat, lon].
         */
        default double[] unmapGeo(double lat, double lon) {
            return new double[]{lat, lon};
        }
    }

    private static final Map<String, Universe> REGISTRY =
        new LinkedHashMap<>();

    private static void register(Universe u) {
        REGISTRY.put(u.id(), u);
    }

    static {
        // ── PRIME — our universe, identity ────────────────────
        register(new Universe() {
            public String id() { return PRIME; }
            public String description() {
                return "Our Earth. Baseline reality.";
            }
        });

        // ── DROWNED — sea level +60 m ─────────────────────────
        // Coastal cities flood; OSM structures still generate,
        // producing drowned metropolises.
        register(new Universe() {
            public String id() { return "drowned"; }
            public String description() {
                return "Meltwater Earth: oceans rose 60 m. "
                     + "Coastal civilization is underwater.";
            }
            public double mapElevation(double e,
                    double la, double lo) {
                return e - 60.0;
            }
        });

        // ── GLACIAL — sea level −120 m ────────────────────────
        // Ice-age ocean drop: land bridges (Doggerland, Bering,
        // Sunda shelf) emerge as walkable terrain.
        register(new Universe() {
            public String id() { return "glacial"; }
            public String description() {
                return "Ice-age Earth: oceans fell 120 m. "
                     + "Lost land bridges are walkable.";
            }
            public double mapElevation(double e,
                    double la, double lo) {
                return e + 120.0;
            }
        });

        // ── INVERTED — elevation × −1 ─────────────────────────
        // Mountains are trenches; ocean floors are mountain
        // ranges. The Mariana Trench becomes an 11 km peak.
        register(new Universe() {
            public String id() { return "inverted"; }
            public String description() {
                return "Inverted Earth: mountains are abysses, "
                     + "ocean floors are peaks.";
            }
            public double mapElevation(double e,
                    double la, double lo) {
                return -e;
            }
        });

        // ── MIRROR — longitude reflected ──────────────────────
        // The planet is mirror-imaged east-west. Walking your
        // home city, everything is flipped — including real OSM
        // buildings, because structure fetch flows through the
        // same transformed toGeo/fromGeo pair.
        register(new Universe() {
            public String id() { return "mirror"; }
            public String description() {
                return "Mirror Earth: the world reflected "
                     + "east-west. Familiar, and wrong.";
            }
            public double[] mapGeo(double[] lonLat) {
                lonLat[0] = -lonLat[0];
                return lonLat;
            }
            public double[] unmapGeo(double lat, double lon) {
                return new double[]{lat, -lon};
            }
        });

        // ── AMPLIFIED — relief × 2.5 ──────────────────────────
        register(new Universe() {
            public String id() { return "amplified"; }
            public String description() {
                return "Amplified Earth: every mountain and "
                     + "trench exaggerated 2.5x.";
            }
            public double mapElevation(double e,
                    double la, double lo) {
                return e * 2.5;
            }
        });
    }

    // ── FORGED (CUSTOM) UNIVERSES ─────────────────────────────
    // Five pre-registered slot dimensions (universe_custom1..5)
    // whose PHYSICS are defined at runtime by command and
    // persisted per-world. Slots exist at world start (1.20.1
    // datapack dimensions are fixed then); forging = giving a
    // slot its parameters. Params are read live, so a forge
    // takes effect without restart — chunks generated before a
    // re-forge keep their old shape until refreshed/deleted.
    public static final int CUSTOM_SLOTS = 5;

    /** Mutable parameters of a forged universe. */
    public static final class ForgeParams {
        public volatile double  seaOffset = 0;   // metres
        public volatile double  elevScale = 1;   // multiplier
        public volatile boolean invert    = false;
        public volatile boolean mirror    = false;
        public volatile String  name      = "";  // display
        public boolean isIdentity() {
            return seaOffset == 0 && elevScale == 1
                && !invert && !mirror;
        }
        public String describe() {
            if (isIdentity()) return "unforged (identity)";
            StringBuilder sb = new StringBuilder();
            if (!name.isBlank())
                sb.append('"').append(name).append("\" — ");
            if (invert)         sb.append("inverted ");
            if (elevScale != 1) sb.append("relief x")
                                  .append(elevScale).append(' ');
            if (seaOffset != 0) sb.append("sea ")
                                  .append(seaOffset > 0 ? "+" : "")
                                  .append(seaOffset).append("m ");
            if (mirror)         sb.append("mirrored ");
            return sb.toString().trim();
        }
    }

    private static final Map<String, ForgeParams> FORGED =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** Live-parameter universe bound to one slot id. */
    private static final class SlotUniverse implements Universe {
        private final String id;
        SlotUniverse(String id) { this.id = id; }
        public String id() { return id; }
        public String description() {
            return "Forged universe slot — "
                + params().describe();
        }
        private ForgeParams params() {
            return FORGED.computeIfAbsent(id,
                k -> new ForgeParams());
        }
        public double mapElevation(double e,
                double la, double lo) {
            ForgeParams p = params();
            double v = e;
            if (p.invert) v = -v;
            v *= p.elevScale;
            v -= p.seaOffset;
            return v;
        }
        public double[] mapGeo(double[] lonLat) {
            if (params().mirror) lonLat[0] = -lonLat[0];
            return lonLat;
        }
        public double[] unmapGeo(double lat, double lon) {
            return params().mirror
                ? new double[]{lat, -lon}
                : new double[]{lat, lon};
        }
    }

    static {
        for (int i = 1; i <= CUSTOM_SLOTS; i++)
            register(new SlotUniverse("custom" + i));
    }

    /** Forge (define/redefine) a slot's physics. */
    public static ForgeParams forge(String slotId,
            double seaOffset, double elevScale,
            boolean invert, boolean mirror, String name) {
        if (!slotId.matches("custom[1-5]")) return null;
        ForgeParams p = FORGED.computeIfAbsent(slotId,
            k -> new ForgeParams());
        p.seaOffset = Math.max(-2000, Math.min(2000, seaOffset));
        p.elevScale = Math.max(-10,  Math.min(10,
            elevScale == 0 ? 1 : elevScale));
        p.invert = invert;
        p.mirror = mirror;
        p.name   = name == null ? "" : name;
        return p;
    }

    public static ForgeParams getForge(String slotId) {
        return FORGED.get(slotId);
    }

    // ── PERSISTENCE (per world) ───────────────────────────────
    private static final int FORGE_MAGIC = 0x54554E31; // "TUN1"

    public static void saveForged(java.nio.file.Path worldRoot) {
        try (java.io.DataOutputStream out =
                new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(
                java.nio.file.Files.newOutputStream(
                    worldRoot.resolve("terra_universes.dat"))))) {
            out.writeInt(FORGE_MAGIC);
            out.writeInt(FORGED.size());
            for (var e : FORGED.entrySet()) {
                out.writeUTF(e.getKey());
                ForgeParams p = e.getValue();
                out.writeDouble(p.seaOffset);
                out.writeDouble(p.elevScale);
                out.writeBoolean(p.invert);
                out.writeBoolean(p.mirror);
                out.writeUTF(p.name);
            }
        } catch (Exception ignored) {}
    }

    public static void loadForged(java.nio.file.Path worldRoot) {
        java.nio.file.Path f =
            worldRoot.resolve("terra_universes.dat");
        if (!java.nio.file.Files.exists(f)) return;
        try (java.io.DataInputStream in =
                new java.io.DataInputStream(
                new java.io.BufferedInputStream(
                java.nio.file.Files.newInputStream(f)))) {
            if (in.readInt() != FORGE_MAGIC) return;
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                String id = in.readUTF();
                double sea = in.readDouble();
                double sc  = in.readDouble();
                boolean inv = in.readBoolean();
                boolean mir = in.readBoolean();
                String name = in.readUTF();
                forge(id, sea, sc, inv, mir, name);
            }
        } catch (Exception ignored) {}
    }

    private UniverseTransforms() {}

    /** Never null: unknown ids fall back to prime. */
    public static Universe get(String id) {
        Universe u = id == null ? null : REGISTRY.get(id);
        return u != null ? u : REGISTRY.get(PRIME);
    }

    public static boolean exists(String id) {
        return id != null && REGISTRY.containsKey(id);
    }

    public static Map<String, Universe> all() {
        return Collections.unmodifiableMap(REGISTRY);
    }
}
