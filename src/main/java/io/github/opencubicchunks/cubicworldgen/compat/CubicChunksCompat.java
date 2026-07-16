package io.github.opencubicchunks.cubicworldgen.compat;

import io.github.opencubicchunks.cubicworldgen.CubicWorldGen;
import net.minecraft.world.level.Level;

/**
 * Compatibility layer for CubicChunks2.
 * Uses reflection/soft references to avoid hard dependency.
 */
public class CubicChunksCompat {
    
    private static boolean initialized = false;
    
    public static void initialize() {
        if (initialized) return;
        initialized = true;
        
        try {
            // Verify CubicChunks API is accessible
            Class.forName("io.github.opencubicchunks.cubicchunks.api.CubicChunksAPI");
            CubicWorldGen.LOGGER.info("CubicChunks API loaded successfully");
        } catch (ClassNotFoundException e) {
            CubicWorldGen.LOGGER.warn("CubicChunks API not found - cubic features disabled");
        }
    }
    
    /**
     * Check if a level is using cubic chunks.
     * Safe to call even if CubicChunks2 is not present.
     */
    public static boolean isCubic(Level level) {
        if (!CubicWorldGen.isCubicChunksPresent()) {
            return false;
        }
        
        try {
            return CubicChunksAPIBridge.isCubic(level);
        } catch (Throwable t) {
            return false;
        }
    }
    
    /**
     * Bridge class for API calls - only loaded if CubicChunks is present.
     */
    private static class CubicChunksAPIBridge {
        static boolean isCubic(Level level) {
            return io.github.opencubicchunks.cubicchunks.api.CubicChunksAPI.isCubic(level);
        }
    }
}
