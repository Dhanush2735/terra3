package net.buildtheearth.terraminusminus.config;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import net.buildtheearth.terraminusminus.projection.EquirectangularProjection;
import net.buildtheearth.terraminusminus.projection.GeographicProjection;
import net.buildtheearth.terraminusminus.projection.dymaxion.BTEDymaxionProjection;
import net.buildtheearth.terraminusminus.projection.dymaxion.ConformalDynmaxionProjection;
import net.buildtheearth.terraminusminus.projection.dymaxion.DymaxionProjection;
import net.buildtheearth.terraminusminus.projection.transform.FlipVerticalProjectionTransform;
import net.buildtheearth.terraminusminus.projection.transform.ScaleProjectionTransform;

/**
 * Minimal TerraMinusMinus parse registry retained by Terra3.
 *
 * Terra3 only needs TerraMinusMinus for projection parsing and the small
 * Terra121 climate/soil datasets. Legacy scalar/vector/OSM generator registries
 * were removed from the runtime surface during pruning.
 *
 * <h2>Why this registry is deliberately small</h2>
 * Terra3 only ever parses two bundled configs:
 * <ul>
 *   <li>{@code bte_generator_settings.json5} &mdash; {@code scale} &rarr;
 *       {@code flip_vertical} &rarr; {@code bte_conformal_dymaxion}</li>
 *   <li>{@code default_generator_settings.json5} &mdash; {@code scale} &rarr;
 *       {@code equirectangular}</li>
 * </ul>
 * No code path feeds a user-supplied projection string into Jackson, so the
 * alternative projections that upstream TerraMinusMinus registered here
 * (mercator variants, equal-area families, and the clamp/rotate/offset/
 * flip-horizontal/swap-axes transforms) could never be selected. They were
 * removed rather than left as unreachable registry entries that imply support
 * Terra3 does not actually have.
 *
 * If you ever want a non-BTE world, restore the projection class and re-add its
 * {@code .put(...)} line here &mdash; that pair of edits is the whole contract.
 */
@UtilityClass
public class GlobalParseRegistries {
    public final BiMap<String, Class<? extends GeographicProjection>> PROJECTIONS =
        new BiMapBuilder<String, Class<? extends GeographicProjection>>()
            .put("equirectangular", EquirectangularProjection.class)
            .put("bte_conformal_dymaxion", BTEDymaxionProjection.class)
            .put("dymaxion", DymaxionProjection.class)
            .put("conformal_dymaxion", ConformalDynmaxionProjection.class)
            .put("flip_vertical", FlipVerticalProjectionTransform.class)
            .put("scale", ScaleProjectionTransform.class)
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
