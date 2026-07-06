// src/main/java/net/buildtheearth/terraplusplus/debug/TerrainDiagnostic.java
package net.buildtheearth.terraplusplus.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

public class TerrainDiagnostic {

    private static final Logger LOGGER = LogManager.getLogger("TerraPlusPlus-Terrain");

    public static void runFullCheck() {
        LOGGER.info("========== TERRAIN DATA CHECK ==========");

        checkProjectionConfig();
        checkElevationAPIs();
        checkDataPipeline();

        LOGGER.info("========== END TERRAIN DATA CHECK ==========");
    }

    // 1. Check if projection config exists and is valid
    private static void checkProjectionConfig() {
        LOGGER.info("--- Projection Config ---");
        String[] possiblePaths = {
            "/assets/terraplusplus/default_projection.json",
            "/data/terraplusplus/projection.json",
            "/terraplusplus/projection.json",
            "/assets/terraplusplus/data/projection.json",
            "/default_generator_settings.json",
            "/assets/terraplusplus/generator_settings.json"
        };

        boolean found = false;
        for (String path : possiblePaths) {
            try {
                InputStream is = TerrainDiagnostic.class.getResourceAsStream(path);
                if (is != null) {
                    String content = new String(is.readAllBytes());
                    is.close();
                    LOGGER.info("[OK] Found config at: {}", path);
                    LOGGER.info("[OK] Content: {}", content.substring(0,
                        Math.min(500, content.length())));

                    // Validate JSON
                    ObjectMapper mapper = new ObjectMapper();
                    var tree = mapper.readTree(content);
                    LOGGER.info("[OK] Valid JSON with {} fields", tree.size());
                    tree.fieldNames().forEachRemaining(
                        f -> LOGGER.info("  field: {}", f));
                    found = true;
                }
            } catch (Exception e) {
                LOGGER.error("[FAIL] Error reading {}: {}", path, e.getMessage());
            }
        }
        if (!found) {
            LOGGER.error("[MISSING] No projection/generator config found!");
            LOGGER.error("  This means Terra++ has no settings to generate terrain.");
        }
    }

    // 2. Check if elevation data APIs are reachable
    private static void checkElevationAPIs() {
        LOGGER.info("--- Elevation API Connectivity ---");

        String[][] endpoints = {
            // {name, url}
            {"AWS Terrain Tiles",
             "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/10/512/512.png"},
            {"MapZen/Nextzen",
             "https://tile.nextzen.org/tilezen/terrain/v1/256/terrarium/10/512/512.png"},
            {"OpenTopography",
             "https://portal.opentopography.org/API/globaldem"},
            {"OSM Buildings",
             "https://data.osmbuildings.org/0.2/anonymous/tile/15/16384/16384.json"},
            {"OpenStreetMap Tile",
             "https://tile.openstreetmap.org/0/0/0.png"},
            {"GitHub (general internet)",
             "https://api.github.com"},
        };

        for (String[] ep : endpoints) {
            testEndpoint(ep[0], ep[1]);
        }
    }

    private static void testEndpoint(String name, String urlStr) {
        try {
            long start = System.currentTimeMillis();
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr)
                .openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "TerraPlusPlus-Diagnostic/1.0");
            int code = conn.getResponseCode();
            long elapsed = System.currentTimeMillis() - start;
            conn.disconnect();

            if (code >= 200 && code < 400) {
                LOGGER.info("[OK] {} — HTTP {} ({}ms)", name, code, elapsed);
            } else {
                LOGGER.warn("[WARN] {} — HTTP {} ({}ms)", name, code, elapsed);
            }
        } catch (Exception e) {
            LOGGER.error("[FAIL] {} — {}: {}", name, e.getClass().getSimpleName(),
                e.getMessage());
        }
    }

    // 3. Check the actual generator classes
    private static void checkDataPipeline() {
        LOGGER.info("--- Generator Pipeline ---");

        String[] generatorClasses = {
            "net.buildtheearth.terraplusplus.generator.TerraChunkGenerator",
            "net.buildtheearth.terraplusplus.generator.EarthGeneratorSettings",
            "net.buildtheearth.terraplusplus.generator.data.ElevationDataSource",
            "net.buildtheearth.terraplusplus.generator.data.TreeCoverDataSource",
            "net.buildtheearth.terraplusplus.generator.data.OSMDataSource",
            "net.buildtheearth.terraplusplus.projection.GeographicProjection",
            "net.buildtheearth.terraplusplus.projection.EqualEarth",
            "net.buildtheearth.terraplusplus.dataset.TerraConnector",
            "net.buildtheearth.terraplusplus.dataset.builtin.ElevationDataset",
        };

        for (String cls : generatorClasses) {
            try {
                Class<?> c = Class.forName(cls);
                LOGGER.info("[OK] {} loaded", cls);

                // Check for key methods
                var methods = c.getDeclaredMethods();
                for (var m : methods) {
                    if (m.getName().contains("elevation") ||
                        m.getName().contains("generate") ||
                        m.getName().contains("fetch") ||
                        m.getName().contains("get") ||
                        m.getName().contains("load")) {
                        LOGGER.info("  method: {}({})", m.getName(),
                            m.getParameterCount() + " params");
                    }
                }
            } catch (ClassNotFoundException e) {
                LOGGER.warn("[SKIP] {} not found (may not exist in this version)",
                    cls);
            }
        }
    }

    // Add this method to TerrainDiagnostic.java
public static void scanJarResources() {
    LOGGER.info("========== JAR RESOURCE SCAN ==========");
    try {
        var url = TerrainDiagnostic.class.getProtectionDomain()
            .getCodeSource().getLocation();
        LOGGER.info("Mod JAR location: {}", url);

        // Scan for ALL resource files in the mod
        String[] searchRoots = {
            "/assets/terraplusplus/",
            "/data/terraplusplus/",
            "/terraplusplus/",
            "/META-INF/"
        };

        // Method 1: Try to list files using classloader
        var classLoader = TerrainDiagnostic.class.getClassLoader();
        for (String root : searchRoots) {
            var resource = classLoader.getResource(root.substring(1)); // remove leading /
            LOGGER.info("Resource root '{}': {}", root,
                resource != null ? "EXISTS at " + resource : "NOT FOUND");
        }

        // Method 2: Scan for specific file patterns
        String[] possibleFiles = {
            "assets/terraplusplus/default_projection.json",
            "assets/terraplusplus/generator_settings.json",
            "assets/terraplusplus/settings.json",
            "assets/terraplusplus/config.json",
            "assets/terraplusplus/earth.json",
            "data/terraplusplus/worldgen/generator.json",
            "data/terraplusplus/projection.json",
            "data/terraplusplus/settings.json",
            "terraplusplus/settings.json",
            "terraplusplus/projection.json",
            "default_generator_settings.json",
            "generator_settings.json",
            "earth_generator.json",
            "projection.json",
        };

        LOGGER.info("--- Checking specific file paths ---");
        for (String file : possibleFiles) {
            var is = classLoader.getResourceAsStream(file);
            if (is != null) {
                String content = new String(is.readAllBytes());
                is.close();
                LOGGER.info("[FOUND] {} ({}bytes): {}", file, content.length(),
                    content.substring(0, Math.min(300, content.length())));
            }
        }

        // Method 3: Try to enumerate via JAR
        LOGGER.info("--- Full JAR scan for .json and .conf files ---");
        try {
            java.util.jar.JarFile jar = null;
            String path = url.toURI().getPath();
            if (path != null && path.endsWith(".jar")) {
                jar = new java.util.jar.JarFile(path);
            }
            // Also try the mod jar directly
            if (jar == null) {
                String modPath = "C:/Users/bondi/AppData/Roaming/.minecraft/versions/terra/mods/terraplusplus-2.0.0-alpha.jar";
                java.io.File f = new java.io.File(modPath);
                if (f.exists()) {
                    jar = new java.util.jar.JarFile(f);
                }
            }

            if (jar != null) {
                var entries = jar.entries();
                int count = 0;
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String name = entry.getName();
                    // Log all non-class files, or files in assets/data dirs
                    if (name.endsWith(".json") || name.endsWith(".conf") ||
                        name.endsWith(".properties") || name.endsWith(".yml") ||
                        name.endsWith(".yaml") || name.endsWith(".toml") ||
                        name.contains("assets/") || name.contains("data/")) {
                        LOGGER.info("  JAR entry: {}", name);
                        count++;
                    }
                }
                LOGGER.info("Found {} config/resource entries in JAR", count);
                jar.close();
            } else {
                LOGGER.warn("Could not open JAR for scanning");
            }
        } catch (Exception e) {
            LOGGER.warn("JAR scan failed: {}", e.getMessage());
        }

    } catch (Exception e) {
        LOGGER.error("Resource scan failed: ", e);
    }
    LOGGER.info("========== END JAR RESOURCE SCAN ==========");
}
}