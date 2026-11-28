# Terra3 underground blocks — research and implementation plan

**Decision:** start with an opt-in rock-only preset for fresh chunks. It must not change existing saves, surface biomes, coastlines, cave placement rules, or structure layouts. This is a plan, not a completed implementation.

## What the data can actually tell us

- **Global default:** Hartmann & Moosdorf (2012), [GLiM v1.0 grid](https://doi.pangaea.de/10.1594/PANGAEA.788537), CC BY 3.0, with attribution. I downloaded and parsed the actual archive. It contains a 720×360 WGS84 categorical grid, one class per half-degree cell, not layers or rock proportions at each depth. Archive SHA-256: `43b4ce3276b155d804db8ff9fb227d620b4c35015a4cf564eac4d06d2b69d88e`. Preserve no-data as unknown. The raw grid returned Mammoth `ss`, Mississippi Delta `su`, Hyderabad `pa`, and no-data at sampled Krubera, Kīlauea and Pacific points. These are coverage checks, not local geology claims.
- **Local override:** the [National Park Service Mammoth Cave description](https://www.nps.gov/maca/learn/nature/rocks-of-mammoth-cave.htm) documents sandstone and shale above limestone and names the formations. Use a bounded local test profile only; do not claim a surveyed block-level model or apply that stack across Kentucky. The sandstone cap must be an elevation-referenced layer that erosion can cut through, not a coating on every hill and valley.
- **Map to explore:** [Macrostrat](https://macrostrat.org/map/). Useful to inspect local map units; not selected as a runtime dependency or blanket license for repackaging data.
- **Later:** [Global Unconsolidated Sediments Map](https://doi.pangaea.de/10.1594/PANGAEA.884822) is a candidate for loose-deposit type, not proof of thickness. Groundwater, ores and localized lava need their own design and testing.

## Build order

1. Add an opt-in geology preset. Replace the legacy land lava blanket with solid rock only in that preset. Keep the bottom bedrock layer, the original preset and the existing surface/seabed palettes. Use a neutral stone/deepslate fallback wherever coverage is missing, including the ocean until a separate seabed geology model is approved. No new runtime service or world scan.
2. Bake the grid into a small versioned resource with class mapping, citation and checksum. Sample it once per column using the existing Earth projection; never blend categorical IDs like numbers. Route both land and underwater bulk fill through the same profile selection while preserving their distinct surface and sediment caps. Map broad rock families to a small vanilla palette: calcite is a visual stand-in for limestone; sandstone, granite and basalt represent appropriate families; missing data remains visibly labeled unknown in diagnostics. Depth profiles are generated estimates unless locally documented.
3. Use deterministic regional layer boundaries from continuous coordinates, not chunk-local noise. Evaluate boundaries once per column and fill by vertical runs. Apply overrides in the order documented local profile → global class → neutral fallback. Keep the rock palette and cave-carvable allowlist in sync; do not allow fluids, bedrock or player blocks through the entrance guard. Add a `/terracheck` geology report showing source, family, estimated layers and fallback reason.
4. Add a bounded Mammoth profile, then validate. Treat any later work on thick valley deposits, ores, lava pockets, aquifers or surveyed cave layouts as separate changes.

## Acceptance checks

- **Mammoth:** sandstone/shale above limestone; the mapped 2×2 entrance opens through all four columns into a connected passage in fresh chunks. Verify both the saved blocks and the in-game route.
- **Krubera:** missing global cell selects fallback, and the previously open mapped entrance remains open. Check passage continuity separately from logged planned depth.
- **Hyderabad and Mississippi Delta:** confirm different profiles and an honest unknown-depth label. For Kīlauea, confirm the no-data cell does not invent lava; use a separate mapped volcanic site or vetted local data to verify the volcanic palette.
- **World checks:** no blanket lava in the new preset; flat bedrock preserved; unchanged surface and biome blocks for identical test inputs; saved chunks not rewritten; deterministic seam checks on negative coordinates, map edges and reversed generation order; benchmark land, coast and cave-heavy chunk generation against the legacy preset before release.
- Stop after the first rock-only build for owner visual approval. Research and offline checks do not certify in-game appearance.

## First implementation — ready for a build, not yet verified in-game

Set `realisticUnderground = true` under `[general]` in Terra++'s Forge common config before creating fresh test chunks. Leave `realCaves = true`. The switch defaults to false. Land uses the packaged GLiM class and approximate continuous depth profiles; water beds keep their existing sediment caps and use neutral bulk rock. Unknown and unmapped palette classes use neutral rock. The 1 km Mammoth pilot places sandstone above tuff (shale stand-in) above calcite (limestone stand-in), using estimated Minecraft elevations, not a surveyed 3D model. Basalt is permitted by the rock carver. Groundwater and ores are outside this pass.

Offline checks read the packaged 720×360 grid, sampled five sites, and checked 32 height/profile combinations for gap-free and non-overlapping rock runs. These did not compile Forge or launch Minecraft.

Joint Phase 2 run: at Mammoth, inspect the rock layers and all four shaft columns at X -9521304, Z -5410957. Follow the passage from the entrance. Recheck Krubera at X 5737951, Z -3921095. In a separate fresh low-elevation land test, verify solid rock around the former lava-band depth and intact bedrock below it. Compare the surface with a legacy-preset control. Use fresh chunks for both presets; switching the flag does not retrofit old chunks.

## Experimental extras for joint tests

With `realisticUnderground = true`, also set `undergroundExtras = true` under `[general]` (default false). This adds generated ore clusters in eligible intact host rock, modest gravel/clay thickness under mapped loose ground, rare sealed water pockets, and rarer sealed lava pockets in mapped volcanic-class areas. Pockets are after cave carving and skipped unless their entire volume is still rock. They are isolated game features, not connected aquifers, surveyed ore bodies, or magma reservoirs. Keep a separate rock-only test with extras off for comparison. No old chunks are rewritten.

Validation so far: Java 17 syntax parse and offline face-enclosure and chunk-bounds checks. Full Forge build and fresh-chunk playtest still required. No Phase 3 survey/tunnel imports or groundwater flow model were added.

## NW36-R2 activation

Supersedes the older optional-preset instructions above. Rock and extras are now always active in newly generated chunks; the old `realisticUnderground` and `undergroundExtras` keys no longer control the generator. `realCaves` still defaults to true and remains the intentionally retained cave kill switch. Verify the new `build=NW36-R2` line after rebuilding. Existing terrain is not rewritten.

## NW36-R3: irregular groundwater pools

Replaces the rectangular terracotta water pockets with varied pools carved in intact rock: flat waterline, uneven banks, dry headroom and clay only at the wet sides and bottom. No dry wall or roof replacement. Pre-existing cavities or non-rock in the footprint cause rejection. Lava and ore generation unchanged. Java 17 standalone check extracted the actual placement method and validated 512 shapes with a stub chunk; not yet a Forge build or in-game approval. Test fresh Delta chunks and include the R3 log marker. Old pools remain unchanged.
