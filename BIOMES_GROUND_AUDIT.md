# Biomes + ground blocks — audit

Date: 2026-11-05 · Scope: code audit only. No biome/ground code was changed.
Update: Step 1 (climate truth) + Step 2 (/terracheck BIOME/GROUND proof)
were implemented afterwards — see BIOME_GROUND_FIX_STEP1_2.md.
Files checked: `TerraBiomeProvider.java`, `Climate.java`,
`EsaWorldCoverIndex.java`, `TerraDensityFunctions.java` surface functions,
`TerraChunkGenerator.java` (`assignBiomes`, `gbt`, `getSurfaceBlock`,
`pLand`, `pRiver`, `pLake`, `pUW`, `placeWorldCoverVegetation`,
`placeBuiltUpProgression`), and the current `/terracheck` ESA section.

## Verdict

Biomes and ground blocks are **not solved yet**. The source-fetch side is
mostly fine (ESA WorldCover answers), but the decision chain that turns
land-cover + climate into a Minecraft biome and a top block has several
real problems. The worst one is global: the precipitation dataset is read
from the wrong channel, so biome climate logic is using temperature as if
it were rainfall.

## How the current chain works

1. ESA WorldCover gives one class per point: tree cover, grassland,
   cropland, built-up, bare, snow/ice, water, wetland, mangroves, etc.
2. `TerraBiomeProvider` turns terrain type + WorldCover + soil/climate
   into a vanilla biome holder.
3. `TerraChunkGenerator.gbt()` turns that into a small surface code:
   grass/desert/mesa/frozen/swamp/savanna/shrubland/bare-hot/beach/etc.
4. `pLand()` paints deep layer, sub-surface, top block, then snow/extras.
5. Later, `placeWorldCoverVegetation()` adds trees/crops/etc. It mostly
   does **not** repaint the ground, except BUILT_UP progression.

## Findings

### P0 — Precipitation reads the temperature channel; rainfall is fake

Evidence:

- `Climate.Precipitation.apply` and `Climate.Temperature.apply` both read
  `data[(x * ROWS + y) << 1]` (`Climate.java:50-67`). Precipitation must
  read channel 1 (`| 1`).
- Direct decode of `climate.gz` confirms channel 0 = temperature °C and
  channel 1 = precipitation mm/yr: Hyderabad ≈ 26.8°C / 847 mm,
  Berlin ≈ 8.0°C / 691 mm, Cairo ≈ 21.1°C / 29 mm,
  Amazon ≈ 26.6°C / 2221 mm.
- `TerraBiomeProvider.queryData` then treats the returned temperature as
  rainfall: `precip = rawPrecip * 12` for values ≤ 1000
  (`TerraBiomeProvider.java:258-276`). Because `temp == rawPrecip`, its
  own guard throws the real temperature away and substitutes a latitude
  estimate.

Effect: every land biome gets fake rainfall ≈ `temperature × 12`, and
temperature is often just a latitude band. Examples: Hyderabad true
≈794-847 mm becomes ≈323 mm; Amazon true ≈2221 mm becomes ≈319 mm;
Cairo true ≈29 mm becomes ≈253 mm.

Important correction to the old one-line-fix note: channel 1 is already
annual mm/yr, so the provider's `* 12` scaling is also wrong. Fixing only
the channel would make Berlin 691 mm become 8287 mm. The safe fix is:
Precipitation reads channel 1 **and** `queryData` treats dataset rainfall
as annual mm/yr (remove or rework the `* 12` assumption).

### P1 — WorldCover can arrive after the ground is already painted

Evidence:

- `EsaWorldCoverIndex.getClass` is non-blocking: missing header or missing
  internal COG tile returns `UNKNOWN` and triggers async fetch
  (`EsaWorldCoverIndex.java:241-269`).
- `prefetch()` only fetches COG headers, not the internal tiles that hold
  the actual class pixels (`EsaWorldCoverIndex.java:331-345`).
- Terrain generation calls prefetch, then immediately paints columns
  (`TerraChunkGenerator.java:4715`, then `pLand` via `gbt`).
- `placeWorldCoverVegetation()` later adds vegetation, but for non-built-up
  classes it does not repaint the surface block
  (`TerraChunkGenerator.java:3494-3562`).

Effect: cold chunks can be born with fallback ground (usually grass-like),
then later receive trees/crops for the real WorldCover class while the top
block stays wrong. Saved chunks keep the wrong ground.

### P1 — Beach/coast biome path is effectively dead

Evidence:

- `TerraDensityFunctions.classify()` never returns `TerrainType.COASTLINE`;
  the coastline-boundary helper returns `LAND`
  (`TerraDensityFunctions.java:380-402`).
- `TerraBiomeProvider` only routes to `classifyCoast` (BEACH / SNOWY_BEACH /
  STONY_SHORE) when terrain type is `COASTLINE`
  (`TerraBiomeProvider.java:474-479`).
- Surface code 9 (beach sand) depends on that beach biome
  (`TerraBiomeProvider.java:1045-1047`).

Effect: normal shore land is classified as LAND, not coast, so beach sand
is not reliably produced by the biome/ground chain. OSM `natural=beach`
polygons can paint sand where mapped, but the base terrain chain cannot be
trusted for places like Juhu Beach.

### P1 — BUILT_UP ground is contradictory: grass first, TUFF blanket later

Evidence:

- Provider comments say BUILT_UP routes to climate fallback and no longer
  forces a city surface (`TerraBiomeProvider.java:544-550`).
- But `getBiomeTypeCode` returns code 10 for BUILT_UP
  (`TerraBiomeProvider.java:1024-1035`), and `gbt()` immediately converts
  code 10 back to 0 (`TerraChunkGenerator.java:4458-4461`). So the initial
  terrain top is natural/grass-like, and `getSurfaceBlock` case 10 (TUFF)
  is unreachable through `gbt`.
- During decoration, modern-era BUILT_UP converts every natural surface
  block in those pixels to TUFF (`TerraChunkGenerator.java:3738-3747`).

Effect: city ground depends on decoration timing. Where decoration runs,
empty urban ground becomes a TUFF blanket; where it does not, it can stay
grass. Parks/lots inside a 10 m BUILT_UP pixel can be paved even when no
building/road is there.

### P1/P2 — Hot grassland, savanna and shrubland become coarse dirt

Evidence: surface codes 5 and 6 both return `COARSE_DIRT` directly in
`TerraChunkGenerator.getSurfaceBlock` (`TerraChunkGenerator.java:4481-4483`).

Effect: savanna and hot grassland read as dirt fields instead of grass
with patches. This matters for large natural areas in India/Africa/
Australia where the biome logic returns SAVANNA in hot climates.

### P2 — BARE ground is forced to red sand when hot

Evidence: `getBiomeTypeCode` maps ESA BARE to code 7 when temp ≥ 12°C,
else code 1 (`TerraBiomeProvider.java:1030-1033`); code 7 is `RED_SAND`
(`TerraChunkGenerator.java:4484`).

Effect: Sahara/Arabian/rocky bare ground can become red sand even when it
should be yellow sand, gravel, stone, or mixed bare rock. ESA BARE means
"bare / sparse vegetation", not specifically red sand.

### P2 — Wetland/mangrove ground stays grass; mud is missing

Evidence: surface code 4 returns `GRASS` (`TerraChunkGenerator.java:4481`).
Mangrove/wetland vegetation is placed above ground, but the ground block
itself is not changed to mud/muddy mangrove roots/clay.

Effect: swamps and mangroves can look like normal grass fields with plants
on top, instead of wet mud ground.

### P2 — High-elevation override can erase the biome

Evidence: `getLandSurfaceBlock` returns SNOW_BLOCK above 4000 m before
looking at the biome code (`TerraDensityFunctions.java:515-533`). Then
`pLand` adds a snow layer for high/frozen ground except desert code 1
(`TerraChunkGenerator.java:5367-5370`).

Effect: high dry places (Altiplano/Atacama-like terrain) can get snow-block
ground purely from elevation, even when the biome is desert. The surface
block and snow-layer rules also disagree for desert at 4000-4500 m.

### P2 — Mesa/scrub patterns are keyed to elevation, not place

Evidence: mesa and scrubland block choice uses functions of elevation
only (`TerraDensityFunctions.java:536-556`, `565-588`).

Effect: terracotta bands and red-sand/grass/coarse speckles follow contour
lines. Two distant places at the same elevation get the same pattern;
neighbouring blocks can stripe by height instead of forming natural
patches.

### P2 — Ocean floor is one recipe everywhere

Evidence: `pUW` always paints 3 dirt under a gravel top
(`TerraChunkGenerator.java:5465-5478`). `getOceanFloorBlock` exists but is
not used by this path.

Effect: warm shallow coasts, sandy shelves and deep floors share the same
gravel skin, with only random seagrass/kelp/coral decoration above it.
The underwater side of a beach can switch abruptly from sand to gravel.

### P3 — Biome palette and per-block ground can disagree at edges

Evidence: `assignBiomes` samples one point per 4×4 biome cell and applies
it to all Y in that cell (`TerraChunkGenerator.java:4303-4328`), while
`gbt()` chooses the top block per block column.

Effect: near biome borders, F3 biome, grass/foliage colour, and the actual
top block can tell different stories. Underground also inherits the
surface biome. This is mostly a consistency/performance issue, not the
main ground bug.

### P3 — Current /terracheck cannot prove ground truth

Current `/terracheck` samples ESA at only 5 points (centre + corners) and
does not census the actual top blocks in the chunk, the biome palette at
the surface, or expected-vs-actual surface mismatches. F3 debug is better
for one block (blocking ESA sample + biome code), but it is not the same
as generation's non-blocking path and does not audit a whole chunk.

## Recommended order

1. **Climate truth first.** Fix Precipitation channel + annual-rainfall
   handling. Add a `/terracheck` climate sample with known places
   (Hyderabad, Berlin, Cairo, Amazon) before trusting biome output.
2. **Stop cold-start ground fossilisation.** Either prefetch/wait for the
   needed ESA internal tiles on the worker thread during terrain
   generation, or mark ground provisional and repaint it when ESA lands,
   like the water retry road.
3. **Restore a real shore rule.** Do not depend on dead `COASTLINE`;
   detect shore land from terrain type + elevation + nearby water/ESA
   water adjacency and assign beach sand/stony shore deliberately.
4. **Clean the surface palette.** Savanna = grass with coarse patches;
   shrubland = mixed grass/coarse; BARE split by soil/temp/roughness into
   sand/red sand/gravel/stone; wetland/mangrove = mud family; BUILT_UP
   should not TUFF every pixel by default.
   [STATUS 2026-11-19: DONE except the BUILT_UP/TUFF part,
   which is item 5 below. See BIOME_GROUND_FIX_STEP5.md —
   patch-mix palettes, BARE mesa/sand split, steep-rock
   rule, varied sea floor, eroded/wooded/windswept
   badlands-savanna naming. Awaiting owner proof.]
5. **Add a BIOME/GROUND test to plain `/terracheck`.** For the player's
   chunk: ESA class counts over all 256 columns, surface biome counts,
   actual top-block counts, expected surface code counts, mismatch count,
   first mismatches, and the centre climate sample (temp/precip).

## Owner test places after a test bench exists

- Juhu Beach `/tpll 19.0968 72.8265` — expect sand at shore, not grass/red sand.
- Charminar / Hyderabad — BUILT_UP should not turn every open pixel to TUFF.
- Sahara point — expect yellow sand / rocky bare mix, not blanket red sand.
- Amazon — expect jungle biome + grass ground, rainfall WET.
- Berlin — expect temperate forest/plains, rainfall SUB_HUMID, not arid/wet-fake.
- Alps / high dry Andes — check snow only where climate/biome says snow.
- Dead Sea shore — dry basin/shore blocks should agree with the height guard result.

Existing saved chunks will not change just because the code is fixed; they
need a repaint/refresh path or fresh chunks to prove the new ground.
