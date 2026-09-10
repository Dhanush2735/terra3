// ============================================================
// COLD-START ROOT-CAUSE FIX (2026-09-02)
//
// Every Terra++ cache used Paths.get("terra_cache", ...) — a
// RELATIVE path resolved against the JVM working directory
// (user.dir), which for third-party launchers is not guaranteed
// to be the game directory, or even the same directory between
// launches. Symptom observed across runs 9-14: caches write and
// read back fine WITHIN a session, but every new session is
// fully cold (MSFT 240MB re-downloaded 6 sessions running, VIDA
// cold ranged fetch every run, WorldCover headers re-fetched,
// OSM tiles re-fetched despite 30-day TTLs).
//
// This helper anchors terra_cache at Forge's canonical game
// directory (FMLPaths.GAMEDIR) and logs the ABSOLUTE path once
// at first use — so the next run's log proves where the cache
// actually lives, closing the 8-run "vanishing cache" mystery
// with evidence either way.
//
// Fallback chain: FMLPaths.GAMEDIR -> user.dir (old behaviour).
// ============================================================
package net.buildtheearth.terraplusplus.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class TerraCachePaths {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++Cache");

    private static volatile Path root;

    private TerraCachePaths() {}

    /** Absolute, launcher-independent terra_cache root. */
    public static Path root() {
        Path r = root;
        if (r != null) return r;
        synchronized (TerraCachePaths.class) {
            if (root != null) return root;
            Path base;
            String via;
            try {
                base = net.minecraftforge.fml.loading.FMLPaths
                    .GAMEDIR.get();
                via = "FMLPaths.GAMEDIR";
            } catch (Throwable t) {
                // Dedicated tools / tests without FML on the
                // classpath: keep the old behaviour.
                base = Paths.get(System.getProperty(
                    "user.dir", "."));
                via = "user.dir (FML unavailable: "
                    + t.getClass().getSimpleName() + ")";
            }
            root = base.resolve("terra_cache")
                .toAbsolutePath().normalize();
            // Self-check: one machine-parseable line proving
            // where every cache in this session lives.
            LOGGER.info("CACHE ROOT: {} (via {}, cwd was {})",
                root, via,
                Paths.get(System.getProperty("user.dir", "?"))
                    .toAbsolutePath());
            // RUN-16: cwd == GAMEDIR, so relative paths were
            // never the cold-start culprit — yet every session
            // is still fully cold. These two checks settle the
            // remaining question WITHOUT any manual folder
            // check: (a) does anything written last session
            // still exist at this boot? (b) census per subdir.
            persistenceCheck(root);
            census(root);
            return root;
        }
    }

    /** Drop-in replacement for Paths.get("terra_cache", sub). */
    public static Path dir(String... sub) {
        Path p = root();
        for (String s : sub) p = p.resolve(s);
        return p;
    }

    /** (a) Writes a session marker and reports whether the
     *  PREVIOUS session's marker survived. If SURVIVOR appears
     *  next boot, disk persistence works and any cold fetches
     *  are per-index read bugs; if FRESH appears every boot,
     *  something wipes terra_cache between sessions (launcher
     *  setting / antivirus). */
    private static void persistenceCheck(Path r) {
        try {
            java.nio.file.Files.createDirectories(r);
            Path marker = r.resolve("session_marker.txt");
            if (java.nio.file.Files.exists(marker)) {
                String prev = java.nio.file.Files
                    .readString(marker).trim();
                LOGGER.info("CACHE MARKER: SURVIVOR — previous "
                    + "session's marker intact (written {}). "
                    + "Disk persistence WORKS.", prev);
            } else {
                LOGGER.info("CACHE MARKER: FRESH — no marker "
                    + "from a previous session. Either first "
                    + "run ever at this root, or something "
                    + "wipes terra_cache between sessions.");
            }
            java.nio.file.Files.writeString(marker,
                java.time.Instant.now().toString());
        } catch (Exception e) {
            LOGGER.warn("CACHE MARKER failed: {}", e.getMessage());
        }
    }

    /** (b) One line per cache subdir: file count + bytes. Run
     *  over two sessions this shows exactly which caches
     *  persist and which are empty at boot. */
    private static void census(Path r) {
        try (java.util.stream.Stream<Path> subs =
                java.nio.file.Files.list(r)) {
            subs.filter(java.nio.file.Files::isDirectory)
                .forEach(d -> {
                    long[] agg = {0, 0};
                    try (java.util.stream.Stream<Path> w =
                            java.nio.file.Files.walk(d)) {
                        w.filter(java.nio.file.Files
                                ::isRegularFile)
                         .forEach(f -> {
                             agg[0]++;
                             try { agg[1] += java.nio.file
                                 .Files.size(f); }
                             catch (Exception ignored) {}
                         });
                    } catch (Exception ignored) {}
                    LOGGER.info("CACHE CENSUS: {} = {} files, "
                        + "{} KB", d.getFileName(), agg[0],
                        agg[1] / 1024);
                });
        } catch (Exception e) {
            LOGGER.warn("CACHE CENSUS failed: {}", e.getMessage());
        }
    }
}
