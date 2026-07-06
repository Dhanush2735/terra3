package net.buildtheearth.terraplusplus.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;

@Mod.EventBusSubscriber(modid = "terraplusplus", value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SkipExperimentalWarning {

    private static final Logger LOGGER = LogManager.getLogger("Terra++");

    @SubscribeEvent
    public static void onScreenOpen(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof ConfirmScreen screen) {
            try {
                String title = screen.getTitle().getString().toLowerCase();

                if (title.contains("experimental") || title.contains("warning")) {
                    LOGGER.info("Auto-skipping experimental warning");

                    // Cancel showing the warning
                    event.setCanceled(true);

                    // Get the callback using Forge reflection helper
                    Minecraft.getInstance().tell(() -> {
                        try {
                            BooleanConsumer callback =
                                ObfuscationReflectionHelper.getPrivateValue(
                                    ConfirmScreen.class, screen, "f_95522_");
                            if (callback != null) {
                                callback.accept(true);
                                LOGGER.info("Experimental warning auto-accepted");
                            }
                        } catch (Exception e) {
                            LOGGER.warn("Reflection failed, trying all fields...");
                            try {
                                for (var field : ConfirmScreen.class.getDeclaredFields()) {
                                    if (field.getType() == BooleanConsumer.class) {
                                        field.setAccessible(true);
                                        BooleanConsumer cb =
                                            (BooleanConsumer) field.get(screen);
                                        cb.accept(true);
                                        LOGGER.info("Warning auto-accepted (fallback)");
                                        return;
                                    }
                                }
                            } catch (Exception e2) {
                                LOGGER.error("Cannot skip warning: {}", e2.getMessage());
                            }
                        }
                    });
                }
            } catch (Exception e) {
                LOGGER.warn("Warning skipper failed: {}", e.getMessage());
            }
        }
    }
}