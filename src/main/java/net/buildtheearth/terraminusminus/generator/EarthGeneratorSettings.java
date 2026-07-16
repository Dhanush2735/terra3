package net.buildtheearth.terraminusminus.generator;

import static net.daporkchop.lib.common.util.PValidation.checkState;

import java.io.IOException;
import java.io.InputStream;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import lombok.NonNull;
import lombok.SneakyThrows;
import net.buildtheearth.terraminusminus.TerraConstants;
import net.buildtheearth.terraminusminus.TerraMinusMinus;
import net.buildtheearth.terraminusminus.config.GlobalParseRegistries;
import net.buildtheearth.terraminusminus.projection.GeographicProjection;
import net.buildtheearth.terraminusminus.projection.transform.FlipHorizontalProjectionTransform;
import net.buildtheearth.terraminusminus.projection.transform.FlipVerticalProjectionTransform;
import net.buildtheearth.terraminusminus.projection.transform.OffsetProjectionTransform;
import net.buildtheearth.terraminusminus.projection.transform.ProjectionTransform;
import net.buildtheearth.terraminusminus.projection.transform.ScaleProjectionTransform;
import net.buildtheearth.terraminusminus.projection.transform.SwapAxesProjectionTransform;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class EarthGeneratorSettings {
    public static final int CONFIG_VERSION = 2;
    public static final String DEFAULT_SETTINGS;
    public static final String BTE_DEFAULT_SETTINGS;

    private static final LoadingCache<String, EarthGeneratorSettings> SETTINGS_PARSE_CACHE =
        CacheBuilder.newBuilder().weakKeys().weakValues()
            .build(CacheLoader.from(EarthGeneratorSettings::parseUncached));

    static {
        try {
            try (InputStream in = EarthGeneratorSettings.class
                    .getResourceAsStream("default_generator_settings.json5")) {
                DEFAULT_SETTINGS = new String(in.readAllBytes());
            }
            try (InputStream in = EarthGeneratorSettings.class
                    .getResourceAsStream("bte_generator_settings.json5")) {
                BTE_DEFAULT_SETTINGS = new String(in.readAllBytes());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static EarthGeneratorSettings parse(String generatorSettings) {
        if (Strings.isNullOrEmpty(generatorSettings)) {
            generatorSettings = DEFAULT_SETTINGS;
        }
        return SETTINGS_PARSE_CACHE.getUnchecked(generatorSettings);
    }

    @SneakyThrows(IOException.class)
    public static EarthGeneratorSettings parseUncached(String generatorSettings) {
        if (Strings.isNullOrEmpty(generatorSettings)) {
            generatorSettings = DEFAULT_SETTINGS;
        }

        if (!generatorSettings.contains("version")) {
            TerraMinusMinus.LOGGER.info("Parsing legacy config: {}", generatorSettings);
            LegacyConfig legacy = TerraConstants.JSON_MAPPER
                .readValue(generatorSettings, LegacyConfig.class);

            GeographicProjection projection = TerraConstants.JSON_MAPPER
                .readValue("{\"" + LegacyConfig.upgradeLegacyProjectionName(legacy.projection) + "\":{}}",
                    GeographicProjection.class);
            projection = LegacyConfig.orientProjectionLegacy(projection, legacy.orentation);
            if (legacy.scaleX != 1.0d || legacy.scaleY != 1.0d) {
                projection = new ScaleProjectionTransform(projection, legacy.scaleX, legacy.scaleY);
            }
            return new EarthGeneratorSettings(projection, legacy.customcubic,
                true, true, CONFIG_VERSION);
        }

        return TerraConstants.JSON_MAPPER.readValue(generatorSettings,
            EarthGeneratorSettings.class);
    }

    @NonNull
    protected final GeographicProjection projection;
    protected final boolean useDefaultHeights;
    protected final boolean useDefaultTreeCover;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public EarthGeneratorSettings(
            @JsonProperty(value = "projection", required = true) @NonNull GeographicProjection projection,
            @JsonProperty(value = "cwg") String cwg,
            @JsonProperty(value = "useDefaultHeights") Boolean useDefaultHeights,
            @JsonProperty(value = "useDefaultTreeCover") @JsonAlias("useDefaultTrees") Boolean useDefaultTreeCover,
            @JsonProperty(value = "version", required = true) int version) {
        checkState(version == CONFIG_VERSION, "invalid version %d (expected: %d)", version, CONFIG_VERSION);
        this.projection = projection;
        this.useDefaultHeights = useDefaultHeights != null ? useDefaultHeights : true;
        this.useDefaultTreeCover = useDefaultTreeCover != null ? useDefaultTreeCover : true;
    }

    @JsonGetter
    public GeographicProjection projection() {
        return this.projection;
    }

    @JsonGetter
    public boolean useDefaultHeights() {
        return this.useDefaultHeights;
    }

    @JsonGetter
    public boolean useDefaultTreeCover() {
        return this.useDefaultTreeCover;
    }

    @Override
    public String toString() {
        try {
            return TerraConstants.JSON_MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @JsonGetter("version")
    private int version() {
        return CONFIG_VERSION;
    }

    @JsonIgnore
    public String getLegacyGeneratorString() {
        LegacyConfig legacy = new LegacyConfig();
        legacy.scaleX = 1;
        legacy.scaleY = 1;
        GeographicProjection proj = this.projection();
        GeographicProjection base = proj;
        ProjectionTransform transform = null;

        while (base instanceof ProjectionTransform) {
            base = ((ProjectionTransform) base).delegate();
        }

        legacy.projection = LegacyConfig.downgradeToLegacyProjectionName(
            GlobalParseRegistries.PROJECTIONS.inverse().get(base.getClass()));
        if (legacy.projection == null) {
            return null;
        }

        while (proj instanceof ProjectionTransform) {
            ProjectionTransform trs = (ProjectionTransform) proj;
            if (proj instanceof ScaleProjectionTransform scale) {
                legacy.scaleX *= scale.x();
                legacy.scaleY *= scale.y();
            } else if (proj instanceof FlipVerticalProjectionTransform
                    || proj instanceof SwapAxesProjectionTransform) {
                if (transform != null) return null;
                transform = trs;
            } else if (proj instanceof FlipHorizontalProjectionTransform
                    || proj instanceof OffsetProjectionTransform) {
                return null;
            }
            proj = trs.delegate();
        }

        if (base.upright()) {
            if (transform == null) legacy.orentation = LegacyConfig.Orientation.upright;
            else if (transform instanceof FlipVerticalProjectionTransform) legacy.orentation = LegacyConfig.Orientation.none;
            else if (transform instanceof SwapAxesProjectionTransform) return null;
        } else {
            if (transform == null) legacy.orentation = LegacyConfig.Orientation.none;
            else if (transform instanceof FlipVerticalProjectionTransform) legacy.orentation = LegacyConfig.Orientation.upright;
            else if (transform instanceof SwapAxesProjectionTransform) legacy.orentation = LegacyConfig.Orientation.swapped;
        }

        try {
            return TerraConstants.JSON_MAPPER.writeValueAsString(legacy);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @JsonDeserialize
    @JsonSerialize
    private static class LegacyConfig {
        private static GeographicProjection orientProjectionLegacy(GeographicProjection base,
                Orientation orientation) {
            if (base.upright()) {
                if (orientation == Orientation.upright) return base;
                base = new FlipVerticalProjectionTransform(base);
            }
            if (orientation == Orientation.swapped) return new SwapAxesProjectionTransform(base);
            if (orientation == Orientation.upright) base = new FlipVerticalProjectionTransform(base);
            return base;
        }

        private static String upgradeLegacyProjectionName(String name) {
            return switch (name) {
                case "web_mercator" -> "centered_mercator";
                case "airocean" -> "dymaxion";
                case "conformal" -> "conformal_dymaxion";
                case "bteairocean" -> "bte_conformal_dymaxion";
                default -> name;
            };
        }

        private static String downgradeToLegacyProjectionName(String name) {
            return switch (name) {
                case "centered_mercator" -> "web_mercator";
                case "dymaxion" -> "airocean";
                case "conformal_dymaxion" -> "conformal";
                case "bte_conformal_dymaxion" -> "bteairocean";
                case "equirectangular", "sinusoidal", "equal_earth", "transverse_mercator" -> name;
                default -> null;
            };
        }

        public String projection = "equirectangular";
        public Orientation orentation = Orientation.none;
        public double scaleX = 100000.0d;
        public double scaleY = 100000.0d;
        public String customcubic = "";

        @JsonAnySetter
        private void fallback(String key, String value) {
            TerraMinusMinus.LOGGER.warn("Ignoring unknown legacy config option: \"{}\": \"{}\"", key, value);
        }

        private enum Orientation { none, upright, swapped }
    }
}
