// ============================================================
// FILE: TerraCreateWorldScreen.java
// ============================================================
package net.buildtheearth.terraplusplus.client;

import net.buildtheearth.terraplusplus.config.TerraSpawnSettings;
import net.buildtheearth.terraplusplus.config.TerraWorldConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public class TerraCreateWorldScreen extends Screen {

    private final Screen parent;
    private final Consumer<TerraWorldConfig> callback;
    private TerraWorldConfig config;
    private final TerraWorldConfig originalConfig;
    private EditBox spawnBox;

    private static final int BUTTON_WIDTH  = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING       = 24;

    public TerraCreateWorldScreen(Screen parent, TerraWorldConfig initial,
                                   Consumer<TerraWorldConfig> callback) {
        super(Component.literal("Terra++ World Settings"));
        this.parent         = parent;
        this.callback       = callback;
        this.config         = initial != null ? initial : TerraWorldConfig.DEFAULT;
        this.originalConfig = this.config;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY  = 32;
        int col1    = centerX - BUTTON_WIDTH - 4;
        int col2    = centerX + 4;
        int row     = 0;

        // Column 1
        addToggle(col1, startY + SPACING * row++, "Buildings",
                config.buildings,
                v -> config = config.withBuildings(v));
        addToggle(col1, startY + SPACING * row++, "Roads",
                config.roads,
                v -> config = config.withRoads(v));
        addToggle(col1, startY + SPACING * row++, "Railways",
                config.railways,
                v -> config = config.withRailways(v));
        addToggle(col1, startY + SPACING * row++, "Bridges",
                config.bridges,
                v -> config = config.withBridges(v));
        addToggle(col1, startY + SPACING * row++, "Walls & Fences",
                config.walls,
                v -> config = config.withWalls(v));
        addToggle(col1, startY + SPACING * row++, "Gardens & Parks",
                config.gardens,
                v -> config = config.withGardens(v));

        // Column 2
        row = 0;
        addToggle(col2, startY + SPACING * row++, "Land Use (Farms etc.)",
                config.landuse,
                v -> config = config.withLanduse(v));
        addToggle(col2, startY + SPACING * row++, "Street Lights",
                config.streetLights,
                v -> config = config.withStreetLights(v));
        addToggle(col2, startY + SPACING * row++, "Natural Features",
                config.naturalFeatures,
                v -> config = config.withNaturalFeatures(v));
        addToggle(col2, startY + SPACING * row++, "Water Bodies",
                config.waterBodies,
                v -> config = config.withWaterBodies(v));
        addToggle(col2, startY + SPACING * row++, "Clean Artifacts",
                config.cleanupArtifacts,
                v -> config = config.withCleanupArtifacts(v));
        addToggle(col2, startY + SPACING * row++, "Microsoft Buildings",
                config.overtureBuildings,
                v -> config = config.withMicrosoftBuildings(v));

        // Building detail — col1 row 6
        row = 6;
        this.addRenderableWidget(CycleButton.<Integer>builder(v -> switch (v) {
            case 1  -> Component.literal("Building Detail: Low");
            case 3  -> Component.literal("Building Detail: High");
            default -> Component.literal("Building Detail: Medium");
        }).withValues(1, 2, 3)
                .withInitialValue(config.buildingDetail)
                .create(col1, startY + SPACING * row,
                        BUTTON_WIDTH, BUTTON_HEIGHT,
                        Component.literal("Building Detail"),
                        (btn, val) -> config =
                                config.withBuildingDetail(val)));

        // Road width — col2 row 6
        this.addRenderableWidget(CycleButton.<Double>builder(v -> {
            if (v <= 0.5)  return Component.literal("Road Width: Narrow (0.5x)");
            if (v <= 0.75) return Component.literal("Road Width: Slim (0.75x)");
            if (v <= 1.0)  return Component.literal("Road Width: Normal (1x)");
            if (v <= 1.5)  return Component.literal("Road Width: Wide (1.5x)");
            return Component.literal("Road Width: Extra Wide (2x)");
        }).withValues(0.5, 0.75, 1.0, 1.5, 2.0)
                .withInitialValue(config.roadWidthScale)
                .create(col2, startY + SPACING * row,
                        BUTTON_WIDTH, BUTTON_HEIGHT,
                        Component.literal("Road Width"),
                        (btn, val) -> config =
                                config.withRoadWidthScale(val)));

        // One paste box: "17.4346 78.5016" — default empty = 0 0
        int bottomY = this.height - 28;
        int spawnY = bottomY - 36;
        int spawnWidth = BUTTON_WIDTH * 2 + 8;
        this.spawnBox = new EditBox(this.font, col1, spawnY, spawnWidth, BUTTON_HEIGHT,
                Component.literal("Spawn lat lon"));
        TerraWorldTypeHelper.bindSpawnBox(this.spawnBox);
        this.spawnBox.setHint(Component.literal("lat lon   e.g. 17.4346 78.5016"));
        this.addRenderableWidget(this.spawnBox);

        // Done / Cancel
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE,
                btn -> {
                    commitSpawnBoxes();
                    // Commit via callback — this writes the file
                    callback.accept(config);
                    this.minecraft.setScreen(parent);
                }).bounds(centerX - BUTTON_WIDTH - 4, bottomY,
                        BUTTON_WIDTH, BUTTON_HEIGHT).build());

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL,
                btn -> {
                    // Restore original — do NOT write file
                    callback.accept(originalConfig);
                    this.minecraft.setScreen(parent);
                }).bounds(centerX + 4, bottomY,
                        BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private void addToggle(int x, int y, String label,
                            boolean initial,
                            java.util.function.Consumer<Boolean> setter) {
        this.addRenderableWidget(CycleButton.onOffBuilder(initial)
                .create(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                        Component.literal(label),
                        (btn, val) -> setter.accept(val)));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY,
                        float partialTick) {
        this.renderBackground(graphics);
        graphics.drawCenteredString(this.font, this.title,
                this.width / 2, 8, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.literal(
                        "Configure which real-world features to generate"),
                this.width / 2, 20, 0xAAAAAA);
        if (this.spawnBox != null) {
            TerraWorldTypeHelper.applySpawnSyntaxColor(this.spawnBox);
            boolean ok = TerraSpawnSettings.isSyntaxValid(this.spawnBox.getValue());
            graphics.drawString(this.font,
                    ok ? "Spawn lat lon (paste both, default 0 0)"
                            : "Spawn lat lon  — invalid (need lat -90..90  lon -180..180)",
                    this.spawnBox.getX(), this.spawnBox.getY() - 10,
                    ok ? 0xA0A0A0 : TerraWorldTypeHelper.SPAWN_TEXT_BAD, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void commitSpawnBoxes() {
        String raw = this.spawnBox == null ? "" : this.spawnBox.getValue();
        TerraSpawnSettings.setFromLatLonString(raw);
    }

    @Override
    public void onClose() {
        // ESC — restore original
        callback.accept(originalConfig);
        this.minecraft.setScreen(parent);
    }
}