package net.buildtheearth.terraminusminus.generator;

import static net.daporkchop.lib.common.util.PValidation.checkState;

import java.io.IOException;
import java.io.InputStream;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import lombok.NonNull;
import lombok.SneakyThrows;
import net.buildtheearth.terraminusminus.TerraConstants;
import net.buildtheearth.terraminusminus.projection.GeographicProjection;

/**
 * Parsed form of a TerraMinusMinus generator settings document.
 *
 * <h2>Terra3 scope</h2>
 * Terra3 only ever parses the two bundled, versioned configs
 * ({@code bte_generator_settings.json5} and {@code default_generator_settings.json5}),
 * always via the {@link #BTE_DEFAULT_SETTINGS} / {@link #DEFAULT_SETTINGS} constants.
 *
 * The upstream pre-v2 ("legacy") config upgrade path and the matching
 * {@code getLegacyGeneratorString()} downgrade path were removed during pruning:
 * both bundled configs declare {@code "version": 2}, so the legacy branch was
 * unreachable, and nothing in either repo ever called the downgrade method. Those
 * paths also pulled in five projection transforms that Terra3 does not ship
 * (flip_horizontal, offset, swap_axes, clamp, rotate).
 *
 * A config without a {@code version} field is now an explicit error rather than a
 * silent legacy-upgrade attempt, because Terra3 cannot express the legacy
 * orientations any more.
 */
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

        checkState(generatorSettings.contains("version"),
            "generator settings must declare \"version\": %d "
            + "(pre-v2 legacy configs are no longer supported)", CONFIG_VERSION);

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
}
