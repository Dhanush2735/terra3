// ============================================================
// FILE: TerraTimeManager.java
// FIX: getWorldPath() returns a Path directly in Forge 1.20.1.
//      Removed the incorrect .toPath() call.
//      Used server.getWorldPath(LevelResource.ROOT) correctly.
// ============================================================
package net.buildtheearth.terraplusplus.generator.historical;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

@Mod.EventBusSubscriber(modid = "terraplusplus")
public class TerraTimeManager {

    private static final Logger LOGGER =
        LogManager.getLogger("Terra++Time");

    // ── CONSTANTS ─────────────────────────────────────────────
    public static final int   BASE_YEAR     = -3000;
    public static final int   DAYS_PER_YEAR = 365;
    public static final int   MIN_YEAR      = -3000;
    public static final int   MAX_YEAR      = 9999;
    public static final int[] VALID_SCALES  = {1,10,25,50};

    private static final String SAVE_FILE =
        "terra_time.dat";
    private static final int SAVE_INTERVAL_TICKS = 100;

    // ── STATE ─────────────────────────────────────────────────
    private static volatile int     timeScale      = 1;
    private static volatile int     currentYear    = BASE_YEAR;
    private static volatile int     lastSavedYear  = BASE_YEAR;
    private static volatile int     lastSavedScale = 1;
    private static volatile boolean initialized    = false;
    private static volatile boolean timeEnabled    = true;

    private static int tickCounter = 0;
    private static final AtomicInteger yearChangeCount =
        new AtomicInteger(0);

    private TerraTimeManager() {}

    // ── INITIALIZATION ────────────────────────────────────────

    public static void initialize(boolean enabled,
            int scale, int baseYearOverride) {
        timeEnabled = enabled;
        timeScale   = clampScale(scale);
        currentYear = Math.max(MIN_YEAR,
            Math.min(MAX_YEAR, baseYearOverride));
        initialized = true;
        LOGGER.info(
            "TerraTimeManager init: enabled={} "
            +"scale={}x startYear={}",
            enabled, timeScale, currentYear);
    }

    public static void initialize() {
        initialize(true, 1, BASE_YEAR);
    }

    // ── TICK HANDLER ──────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(
            TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!initialized || !timeEnabled) return;

        tickCounter++;

        ServerLevel level = getOverworld();
        if (level != null) {
            long worldDay = level.getDayTime() / 24000L;
            int newYear = calculateYear(worldDay);
            if (newYear != currentYear) {
                int oldYear = currentYear;
                currentYear = newYear;
                yearChangeCount.incrementAndGet();
                onYearChanged(oldYear, newYear, level);
            }
        }

        if (tickCounter % SAVE_INTERVAL_TICKS == 0) {
            if (currentYear != lastSavedYear
                    || timeScale != lastSavedScale) {
                save();
            }
        }
    }

    // ── YEAR CALCULATION ──────────────────────────────────────

    public static int calculateYear(long worldDay) {
        long yearsElapsed =
            (worldDay * timeScale) / DAYS_PER_YEAR;
        int year = (int) Math.min(MAX_YEAR,
            BASE_YEAR + yearsElapsed);
        return Math.max(MIN_YEAR, year);
    }

    public static int getCurrentYear() {
        return currentYear;
    }
    public static int getTimeScale() {
        return timeScale;
    }
    public static boolean isTimeEnabled() {
        return timeEnabled;
    }

    public static void setTimeScale(int scale) {
        timeScale = clampScale(scale);
        LOGGER.info(
            "Time scale set to {}x (year={})",
            timeScale, currentYear);
        save();
    }

    public static void setTimeEnabled(boolean enabled) {
        timeEnabled = enabled;
        LOGGER.info("Time system {}",
            enabled ? "enabled" : "disabled");
    }

    // ── ERA NAME ──────────────────────────────────────────────

    public static String getEraName(int year) {
        if (year < -2000) return "Prehistoric";
        if (year < -1000) return "Ancient";
        if (year < -500)  return "Classical Antiquity";
        if (year < 0)     return "Late Antiquity";
        if (year < 500)   return "Early Medieval";
        if (year < 1000)  return "Medieval";
        if (year < 1300)  return "High Medieval";
        if (year < 1500)  return "Late Medieval";
        if (year < 1700)  return "Early Modern";
        if (year < 1800)  return "Age of Enlightenment";
        if (year < 1900)  return "Industrial Revolution";
        if (year < 1950)  return "Early Modern Era";
        if (year < 2000)  return "20th Century";
        return "Contemporary";
    }

    public static String formatYear(int year) {
        if (year < 0)
            return Math.abs(year) + " BCE";
        return year + " CE";
    }

    // ── YEAR CHANGE EVENT ─────────────────────────────────────

    private static void onYearChanged(int oldYear,
            int newYear, ServerLevel level) {
        if (Math.abs(newYear - oldYear) >= 10
                || newYear % 100 == 0) {
            LOGGER.info(
                "Year advanced: {} → {} ({})",
                formatYear(oldYear),
                formatYear(newYear),
                getEraName(newYear));
        }
    }

    // ── PERSISTENCE ───────────────────────────────────────────

    public static void save() {
        try {
            ServerLevel level = getOverworld();
            if (level == null) return;

            Path worldDir = level.getServer()
                .getWorldPath(LevelResource.ROOT);
            Path saveFile = worldDir.resolve(SAVE_FILE);

            Files.createDirectories(
                saveFile.getParent() != null
                ? saveFile.getParent()
                : worldDir);

            try (DataOutputStream dos =
                    new DataOutputStream(
                    new BufferedOutputStream(
                    Files.newOutputStream(saveFile)))) {
                dos.writeInt(currentYear);
                dos.writeInt(timeScale);
                dos.writeBoolean(timeEnabled);
                dos.writeLong(
                    System.currentTimeMillis());
            }
            lastSavedYear  = currentYear;
            lastSavedScale = timeScale;
            LOGGER.debug(
                "Saved time state: year={} scale={}x",
                currentYear, timeScale);
        } catch (Exception e) {
            LOGGER.warn(
                "Failed to save time state: {}",
                e.getMessage());
        }
    }

    public static void load(ServerLevel level) {
        try {
            Path worldDir = level.getServer()
                .getWorldPath(LevelResource.ROOT);
            Path saveFile = worldDir.resolve(SAVE_FILE);

            if (!Files.exists(saveFile)) {
                LOGGER.info(
                    "No terra_time.dat found, "
                    +"starting from year {}",
                    BASE_YEAR);
                initialized = true;
                return;
            }

            try (DataInputStream dis =
                    new DataInputStream(
                    new BufferedInputStream(
                    Files.newInputStream(saveFile)))) {
                currentYear  = dis.readInt();
                timeScale    = clampScale(dis.readInt());
                timeEnabled  = dis.readBoolean();
                long savedAt = dis.readLong();
                LOGGER.info(
                    "Loaded time state: year={} "
                    +"scale={}x enabled={} "
                    +"(saved {}ms ago)",
                    currentYear, timeScale,
                    timeEnabled,
                    System.currentTimeMillis()
                        - savedAt);
            }
            lastSavedYear  = currentYear;
            lastSavedScale = timeScale;
            initialized    = true;
        } catch (Exception e) {
            LOGGER.warn(
                "Failed to load time state: {} "
                +"— starting from scratch",
                e.getMessage());
            currentYear = BASE_YEAR;
            timeScale   = 1;
            initialized = true;
        }
    }

    // ── FORCE RECALCULATE ─────────────────────────────────────

    public static void forceRecalculate(
            ServerLevel level) {
        if (level == null) return;
        long worldDay = level.getDayTime() / 24000L;
        int newYear = calculateYear(worldDay);
        int oldYear = currentYear;
        currentYear = newYear;
        if (oldYear != newYear) {
            yearChangeCount.incrementAndGet();
            LOGGER.info(
                "Time jump: {} → {} ({})",
                formatYear(oldYear),
                formatYear(newYear),
                getEraName(newYear));
        }
    }

    // ── HELPERS ───────────────────────────────────────────────

    private static int clampScale(int scale) {
        for (int v : VALID_SCALES)
            if (v == scale) return scale;
        int best = VALID_SCALES[0];
        int bestDiff = Math.abs(scale - best);
        for (int v : VALID_SCALES) {
            int diff = Math.abs(scale - v);
            if (diff < bestDiff) {
                best = v;
                bestDiff = diff;
            }
        }
        return best;
    }

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

    public static int getYearChangeCount() {
        return yearChangeCount.get();
    }
}