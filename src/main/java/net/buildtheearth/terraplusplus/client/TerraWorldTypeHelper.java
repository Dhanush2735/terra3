// ============================================================
// FILE: TerraWorldTypeHelper.java (FIXED)
// FIX: Constructor updated to include 4 new time/OHM fields.
//      Serialization/deserialization updated to match.
//      Null type safety warnings suppressed with @SuppressWarnings.
// ============================================================
package net.buildtheearth.terraplusplus.client;

import net.buildtheearth.terraplusplus.config.TerraWorldConfig;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Mod.EventBusSubscriber(
        modid = "terraplusplus",
        value = Dist.CLIENT)
public class TerraWorldTypeHelper {

    private static final Logger LOGGER =
            LogManager.getLogger("Terra++WorldType");

    // File written on Done, read+deleted by generator
    private static final Path PENDING_CONFIG_FILE =
            Paths.get("terra_pending_config.json");

    // In-memory copy for the current session
    private static volatile TerraWorldConfig pendingConfig =
            TerraWorldConfig.DEFAULT;

    // ── PUBLIC API ────────────────────────────────────────────

    public static TerraWorldConfig getPendingConfig() {
        return pendingConfig;
    }

    public static void setPendingConfig(
            TerraWorldConfig config) {
        pendingConfig = config != null
                ? config : TerraWorldConfig.DEFAULT;
    }

    /**
     * Called by TerraChunkGenerator lazily on first use.
     * Reads the pending config file if it exists, deletes it.
     * Returns null if no pending config exists.
     */
    public static TerraWorldConfig consumePendingConfig() {
        try {
            if (Files.exists(PENDING_CONFIG_FILE)) {
                String json = Files.readString(
                        PENDING_CONFIG_FILE,
                        java.nio.charset.StandardCharsets
                                .UTF_8);
                Files.deleteIfExists(PENDING_CONFIG_FILE);
                LOGGER.info(
                        "Consumed pending config from file");
                TerraWorldConfig cfg = parseConfigJson(json);
                if (cfg != null) {
                    pendingConfig = TerraWorldConfig.DEFAULT;
                    return cfg;
                }
            }
        } catch (Exception e) {
            LOGGER.debug(
                    "Could not read pending config file: {}",
                    e.getMessage());
        }

        TerraWorldConfig config = pendingConfig;
        if (config == TerraWorldConfig.DEFAULT) return null;
        pendingConfig = TerraWorldConfig.DEFAULT;
        LOGGER.info(
                "Consumed pending config from memory: {}",
                config);
        return config;
    }

    /**
     * Write the config to disk so it survives thread handoff.
     */
    public static void writePendingConfig(
            TerraWorldConfig config) {
        if (config == null
                || config == TerraWorldConfig.DEFAULT) {
            try {
                Files.deleteIfExists(PENDING_CONFIG_FILE);
            } catch (Exception ignored) {}
            pendingConfig = TerraWorldConfig.DEFAULT;
            return;
        }
        try {
            String json = serializeConfig(config);
            Files.writeString(PENDING_CONFIG_FILE, json,
                    java.nio.charset.StandardCharsets.UTF_8);
            pendingConfig = config;
            LOGGER.info(
                    "Wrote pending config to file: {}",
                    config);
        } catch (Exception e) {
            pendingConfig = config;
            LOGGER.warn(
                    "Could not write pending config file: {}",
                    e.getMessage());
        }
    }

    // ── SERIALIZATION ─────────────────────────────────────────

    private static String serializeConfig(TerraWorldConfig c) {
        return String.format(
                "{"
                + "\"buildings\":%b,"
                + "\"roads\":%b,"
                + "\"railways\":%b,"
                + "\"bridges\":%b,"
                + "\"walls\":%b,"
                + "\"gardens\":%b,"
                + "\"landuse\":%b,"
                + "\"street_lights\":%b,"
                + "\"natural_features\":%b,"
                + "\"water_bodies\":%b,"
                + "\"building_detail\":%d,"
                + "\"road_width_scale\":%.2f,"
                + "\"cleanup_artifacts\":%b,"
                + "\"overture_buildings\":%b,"
                + "\"overture_supplement\":%b,"
                + "\"time_enabled\":%b,"
                + "\"time_scale\":%d,"
                + "\"base_year\":%d,"
                + "\"ohm_enabled\":%b"
                + "}",
                c.buildings,
                c.roads,
                c.railways,
                c.bridges,
                c.walls,
                c.gardens,
                c.landuse,
                c.streetLights,
                c.naturalFeatures,
                c.waterBodies,
                c.buildingDetail,
                c.roadWidthScale,
                c.cleanupArtifacts,
                c.overtureBuildings,
                c.overtureSupplement,
                c.timeEnabled,
                c.timeScale,
                c.baseYear,
                c.ohmEnabled);
    }

    private static TerraWorldConfig parseConfigJson(
            String json) {
        try {
            boolean buildings       =
                    readBool(json, "buildings",       true);
            boolean roads           =
                    readBool(json, "roads",           true);
            boolean railways        =
                    readBool(json, "railways",        true);
            boolean bridges         =
                    readBool(json, "bridges",         true);
            boolean walls           =
                    readBool(json, "walls",           true);
            boolean gardens         =
                    readBool(json, "gardens",         true);
            boolean landuse         =
                    readBool(json, "landuse",         true);
            boolean streetLights    =
                    readBool(json, "street_lights",   true);
            boolean naturalFeatures =
                    readBool(json, "natural_features",true);
            boolean waterBodies     =
                    readBool(json, "water_bodies",    true);
            int buildingDetail      =
                    readInt(json,  "building_detail", 2);
            double roadWidthScale   =
                    readDouble(json,"road_width_scale",1.0);
            boolean cleanupArtifacts =
                    readBool(json, "cleanup_artifacts",true);
            boolean overtureBuildings =
                    readBool(json, "overture_buildings",true);
            boolean overtureSupplement =
                    readBool(json, "overture_supplement",true);
            // ── NEW TIME/OHM FIELDS ──────────────────────────
            boolean timeEnabled     =
                    readBool(json, "time_enabled",    true);
            int timeScale           =
                    readInt(json,  "time_scale",      1);
            int baseYear            =
                    readInt(json,  "base_year",       -3000);
            boolean ohmEnabled      =
                    readBool(json, "ohm_enabled",     true);

            return new TerraWorldConfig(
                    buildings, roads, railways, bridges,
                    walls, gardens, landuse, streetLights,
                    naturalFeatures, waterBodies,
                    buildingDetail, roadWidthScale,
                    cleanupArtifacts, overtureBuildings,
                    overtureSupplement,
                    timeEnabled, timeScale, baseYear,
                    ohmEnabled);
        } catch (Exception e) {
            LOGGER.warn(
                    "Failed to parse pending config: {}",
                    e.getMessage());
            return null;
        }
    }

    // ── JSON HELPERS ──────────────────────────────────────────

    private static boolean readBool(String json, String key,
                                     boolean def) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return def;
        int start = idx + search.length();
        if (json.startsWith("true",  start)) return true;
        if (json.startsWith("false", start)) return false;
        return def;
    }

    private static int readInt(String json, String key,
                                int def) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return def;
        int start = idx + search.length();
        int end = start;
        while (end < json.length()
                && (Character.isDigit(json.charAt(end))
                || json.charAt(end) == '-'))
            end++;
        try {
            return Integer.parseInt(
                    json.substring(start, end));
        } catch (Exception e) { return def; }
    }

    private static double readDouble(String json, String key,
                                      double def) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return def;
        int start = idx + search.length();
        int end = start;
        while (end < json.length()
                && (Character.isDigit(json.charAt(end))
                || json.charAt(end) == '-'
                || json.charAt(end) == '.'))
            end++;
        try {
            return Double.parseDouble(
                    json.substring(start, end));
        } catch (Exception e) { return def; }
    }

    // ── SCREEN EVENT ──────────────────────────────────────────

    @SuppressWarnings({"null","unchecked"})
    @SubscribeEvent
    public static void onScreenInit(
            ScreenEvent.Init.Post event) {
        if (!(event.getScreen()
                instanceof CreateWorldScreen createScreen))
            return;

        // Guard: don't add button if already present
        boolean alreadyAdded = event.getListenersList()
                .stream()
                .anyMatch(w ->
                    w instanceof net.minecraft.client.gui
                            .components.Button b
                    && b.getMessage().getString()
                            .equals("Terra++ Settings"));
        if (alreadyAdded) return;

        try {
            event.addListener(
                net.minecraft.client.gui.components.Button
                    .builder(
                        net.minecraft.network.chat.Component
                            .literal("Terra++ Settings"),
                        btn -> net.minecraft.client.Minecraft
                            .getInstance().setScreen(
                                new TerraCreateWorldScreen(
                                    createScreen,
                                    pendingConfig,
                                    config -> {
                                        writePendingConfig(
                                                config);
                                        LOGGER.info(
                                            "Config "
                                            + "committed:"
                                            + " {}",
                                            config);
                                    }
                                )
                            )
                    )
                    .bounds(createScreen.width / 2 + 5,
                            187, 150, 20)
                    .build());

            LOGGER.info(
                    "Terra++ customize button added");
        } catch (Exception e) {
            LOGGER.debug(
                    "Could not add button: {}",
                    e.getMessage());
        }
    }
}