# Biome/Ground Step 4 — real shore rule

Status: IMPLEMENTED in source 2026-11-19, JDK 17 parse-clean.
NOT yet proven in-game. (Steps 1+2 PROVEN, Step 3 implemented
awaiting test — see BIOME_GROUND_FIX_STEP3.md.)

NW(14) save check (2026-11-19, Juhu, 3,003 chunks): shore
unchanged vs NW(12) — Juhu's sand beach already existed via
mapped-beach painting, so this save can neither prove nor
disprove Step 4 (identical shore censuses, no regression).
Proof still needed: a coast WITHOUT a mapped beach (wild
coast: Konkan, Norway, Chile) or `SHORE [...]` log lines.
Same scan PROVED the coral/stone fix: sea-floor stone
14,068 (NW12) -> 0, coral floors ~14,003 in their place.

## v2 (2026-11-19, after owner NW14 report)

Owner reported: (1) coastline rough/blocky again,
(2) no beach biome in F3. Measured on the NW14 save:

- Beach biomes in save palettes: ZERO (plains 1579 chunks,
  warm_ocean 666, savanna 495...). v1 shore-name route never
  fired (narrow +/-8 probes, BUILT_UP exclusion, or the NW14
  jar predated Step 4 — indistinguishable from outside).
  v2: probes 8 directions at 12 blocks, BUILT_UP exclusion
  removed, height band widened to SL+8.
- Painted beach was only the single touching column wide —
  inherently blocky. v2: shore distance grid `shd`/`wsf`
  (water=0, relax 6 passes) paints shd 1..4; stony gravel
  and mud only at the waterline (shd==1), sand behind.
- Coastline teeth: NW14 edge measured BETTER than NW12
  (detrended wiggle mean 2.47 vs 5.38 blocks; step mean
  1.92 vs 3.38) but 65 jumps >=3 blocks remain, not
  chunk-aligned — the cold-start two-shoreline mix again
  (COAST WAIT was only 2.5 s; NW12's coastline took ~132 s
  cold). COAST WAIT now 10 s, gate widened to elev -10..10,
  plus throttled log `COAST WAIT [...]: coastline still not
  ready after 10s` when it still fails (those chunks heal
  via round-3 repair on refresh).

Owner retest: rebuild; F3 anywhere on Juhu sand strip near
water -> minecraft:beach; log SHORE lines; fresh coast in a
NEW city (cold caches) for teeth + watch COAST WAIT lines.
Bench (2026-11-19): `/terracheck here` now prints a SHORE
census + VERDICT (shoreCells / beachBiomeCells /
shoreTopCells via the shared `isShoreCellPublic`) and a
DATA readiness line (coastlineTile CACHED/MISSING,
esaUnknown) — F3-at-beach proof no longer needs a save scan.

## v3 (2026-11-19, NW15 save proof — 36 full chunks, Juhu)

The v2 route DID fire — and the save showed exactly why F3
still never said "beach": on sand-top columns the saved
biomes were desert 4532 / mangrove_swamp 1761 / river 272,
beach 0. Per-cell map: seaward sand = mangrove_swamp,
landward sand = desert.
- Bug 1: `classifyCoast` had a precip-only mangrove rule
  (tropical + temp>=20 + precip>=800 -> MANGROVE_SWAMP).
  Mumbai (2,111 mm) turned the whole open beach mangrove.
  FIXED: rule deleted; mangrove now requires ESA
  MANGROVES/WETLAND class (muddy sheltered water).
- Bug 2: sand >12 blocks from water kept inland names
  (desert). FIXED: `isShoreCellPublic` probes two rings
  (12 + 28 blocks, 8 directions).
- Also in NW15: sea-floor STONE = 0 (coral fix holds),
  shoreline smooth on the sampled stretch (wiggle mean
  1.23, 2 jumps >=4 over 32 rows — small sample, warm
  caches; not a cold-start proof).
Awaiting NW16: F3 on Juhu sand -> minecraft:beach across
the strip; `/terracheck here` -> SHORE VERDICT PASS.

## v3 PROVEN (2026-11-19, NW16 save scan)

2,613 chunks (803 full). `minecraft:beach` present in 126
chunks (NW14/NW15: 0). Per-cell map at spawn: a clean B
band exactly over the sand strip (O ocean | B beach sand |
D inland). Sand-top columns: beach 12,153 (dominant),
warm_ocean 1,120 (submerged sand), desert 1,582 (inland
beyond the strip), mangrove_swamp 768 (creek pockets;
world mangrove chunk-presence collapsed 12 -> 3, so the
hijack is gone). Sea-floor STONE still 0. Shoreline
wiggle mean 2.60 over 470 rows (NW14: 2.47 — basically
unchanged; residual teeth remain a separate open item,
they live away from spawn and are not caused by the biome
route). STEP 4 CLOSED for the biome-name and shore-paint
goals; only the cold-start 10 s COAST WAIT line watch
remains informal.

## Shore SHAPE fix (2026-11-19, owner: "coastline is rough
## and blocky" — NW16 forensics + fix, awaiting proof)

Measured on the NW16 save (downloads/nw16/shore_shape.py,
chunk_stitch_test.py, puddle_census.py):
- NOT chunk stitching: |dev step| at z%16 borders 0.95 <
  inside 1.31; between-chunk SD 0.60 vs within 2.35.
- Edge deviation (31-row median detrend): mean |dev| 2.12
  blocks, autocorr +0.82 @lag1 → ~0 @lag8 = noise with a
  ~5-block wavelength = the DEM pixel scale (Terrarium
  z15 ≈ 4.8 m/px). Plus 83 cells of isolated inland water
  singles/pairs (DEM-dip pits).
- Conclusion: where the smooth OSM coastline line is not
  the witness, the painted edge is drawn by the ESA 10 m
  raster + elevation contour — intrinsically rough at
  block scale. Waiting longer cannot cure data that never
  arrives (Overpass chronically 502 in owner runs).

Fix (TerraChunkGenerator.smoothShoreMask, after
reclassWB, before floor transitions): sea/land cells in
the shore elevation band flip when neighbours vote the
other way (5 of 8, or all 4 cardinal; up to 6 passes,
stops early when stable). If the
coastline tile + segments are present for the chunk
(new `coastData` flag), mapped cells are trusted and
flip only as total singles (8/8). Rivers/lakes/below-sea
water untouched; 3+-cell-wide real coves survive.
OsmWaterIndex.coastSegCountAround added. New log lines:
`COAST SRC [x,z]: centre=<side> segs=N coastData=bool`
(throttled 30) tells which witness drew a shore;
`SHORE SMOOTH [x,z]: flipped=N coastData=bool`
(throttled 30) shows the pass working.
Proof wanted (fresh coast, rebuild): shoreline visibly
smoother; wiggle mean on a new save scan target < ~1.2;
COAST SRC lines in log settle which witness is live.

## The problem (audit P1)

`classify()` never returns `TerrainType.COASTLINE`, so
`TerraBiomeProvider.classifyCoast` (BEACH / SNOWY_BEACH /
STONY_SHORE / MANGROVE_SWAMP) was dead code and shore ground
came from whatever the inland painter chose. Juhu shore could
be grass or red sand instead of beach sand.

## The fix (audit road: detect shore from adjacency, not COASTLINE)

Ground (TerraChunkGenerator):
- The near-water scan in `generateTerrain` now also records
  `wsn` = highest neighbouring water SURFACE per land column
  (sea = SL 63; rivers/lakes use their own surface in `osy`).
- `pLand` takes `ws` and applies the SHORE RULE when a land
  column touches water (`nw`) and sits just above it
  (`sy - ws` in -1..6, `sy > SL-2`):
  - frozen ground (bt=3): SAND top/sub + snow layer from the
    existing snow rule = snowy beach
  - wetland (bt=4) at sea level: MUD top/sub (mangrove shore)
  - steep drop (>=5) or rough (roughness > 0.4): GRAVEL
    (stony shore)
  - otherwise: SAND (beach)
  - dirt paths (bt=11) untouched; only top + sub-surface
    blocks change. Log (first 20): `SHORE [x,z]: sand|mud|stony gravel top ...`

Biome name (TerraBiomeProvider + assignBiomes):
- New `getBiomeForLocation(..., boolean shore)` overload:
  shore + LAND routes to the existing `classifyCoast`
  (frozen → SNOWY_BEACH, elev > 15 m → STONY_SHORE,
  mangroves/tropical wetland → MANGROVE_SWAMP, else BEACH).
- `assignBiomes` detects shore: LAND cell, not BUILT_UP,
  surfaceY in [SL-2, SL+6], with a sea column (OCEAN /
  SHALLOW_WATER) within ±8 blocks (4 classify probes).

## Owner test (fresh chunks only)

1. Juhu Beach `/tpll 19.0968 72.8265`: shore ground = SAND,
   F3 biome = `minecraft:beach`; log shows `SHORE ... sand`.
2. Cold coast (e.g. Baltic / north Norway): sand + snow,
   F3 = `snowy_beach`.
3. Rocky steep coast: gravel tops, F3 = `stony_shore` when
   real elevation > 15 m.
4. Mangrove coast (e.g. Sundarbans edge): mud at shore,
   F3 = `mangrove_swamp`.

## Known limits

- Rivers/lakes keep RIVER biome name (shore SAND still
  paints at their banks via the ground rule).
- Shore band is one column wide by adjacency (plus the
  terracing already in heights); wide flat beaches rely on
  ESA/bare classes too.
- BUILT_UP shore cells keep inland biome names (ground
  still gets sand under structures).
