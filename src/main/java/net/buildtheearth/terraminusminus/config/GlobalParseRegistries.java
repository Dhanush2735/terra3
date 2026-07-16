package net.buildtheearth.terraminusminus.config;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import net.buildtheearth.terraminusminus.projection.AzimuthalEquidistantProjection;
import net.buildtheearth.terraminusminus.projection.EqualEarthProjection;
import net.buildtheearth.terraminusminus.projection.EquirectangularProjection;
import net.buildtheearth.terraminusminus.projection.GeographicProjection;
import net.buildtheearth.terraminusminus.projection.LambertAzimuthalProjection;
import net.buildtheearth.terraminusminus.projection.SinusoidalProjection;
import net.buildtheearth.terraminusminus.projection.StereographicProjection;
import net.buildtheearth.terraminusminus.projection.dymaxion.BTEDymaxionProjection;
import net.buildtheearth.terraminusminus.projection.dymaxion.ConformalDynmaxionProjection;
import net.buildtheearth.terraminusminus.projection.dymaxion.DymaxionProjection;
import net.buildtheearth.terraminusminus.projection.mercator.CenteredMercatorProjection;
import net.buildtheearth.terraminusminus.projection.mercator.TransverseMercatorProjection;
import net.buildtheearth.terraminusminus.projection.mercator.WebMercatorProjection;
import net.buildtheearth.terraminusminus.projection.transform.ClampProjectionTransform;
import net.buildtheearth.terraminusminus.projection.transform.FlipHorizontalProjectionTransform;
import net.buildtheearth.terraminusminus.projection.transform.FlipVerticalProjectionTransform;
import net.buildtheearth.terraminusminus.projection.transform.OffsetProjectionTransform;
import net.buildtheearth.terraminusminus.projection.transform.RotateProjectionTransform;
import net.buildtheearth.terraminusminus.projection.transform.ScaleProjectionTransform;
import net.buildtheearth.terraminusminus.projection.transform.SwapAxesProjectionTransform;

/**
 * Minimal TerraMinusMinus parse registry retained by Terra3.
 *
 * Terra3 only needs TerraMinusMinus for projection parsing and the small
 * Terra121 climate/soil datasets. Legacy scalar/vector/OSM generator registries
 * were removed from the runtime surface during pruning.
 */
@UtilityClass
public class GlobalParseRegistries {
    public final BiMap<String, Class<? extends GeographicProjection>> PROJECTIONS =
        new BiMapBuilder<String, Class<? extends GeographicProjection>>()
            .put("centered_mercator", CenteredMercatorProjection.class)
            .put("web_mercator", WebMercatorProjection.class)
            .put("transverse_mercator", TransverseMercatorProjection.class)
            .put("equirectangular", EquirectangularProjection.class)
            .put("sinusoidal", SinusoidalProjection.class)
            .put("equal_earth", EqualEarthProjection.class)
            .put("bte_conformal_dymaxion", BTEDymaxionProjection.class)
            .put("dymaxion", DymaxionProjection.class)
            .put("conformal_dymaxion", ConformalDynmaxionProjection.class)
            .put("lambert_azimuthal", LambertAzimuthalProjection.class)
            .put("azimuthal_equidistant", AzimuthalEquidistantProjection.class)
            .put("stereographic", StereographicProjection.class)
            .put("clamp", ClampProjectionTransform.class)
            .put("flip_horizontal", FlipHorizontalProjectionTransform.class)
            .put("flip_vertical", FlipVerticalProjectionTransform.class)
            .put("offset", OffsetProjectionTransform.class)
            .put("rotate", RotateProjectionTransform.class)
            .put("scale", ScaleProjectionTransform.class)
            .put("swap_axes", SwapAxesProjectionTransform.class)
            .build();

    @RequiredArgsConstructor
    private static class BiMapBuilder<K, V> {
        @NonNull
        private final BiMap<K, V> delegate;

        public BiMapBuilder() {
            this(HashBiMap.create());
        }

        public BiMapBuilder<K, V> put(K key, V value) {
            this.delegate.put(key, value);
            return this;
        }

        public BiMap<K, V> build() {
            return this.delegate;
        }
    }
}
