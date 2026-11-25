# CAVES — Phase 1: real-geology karst caves (2026-11-20)

Owner decision (2026-11-20): build caves in order 1 → 2 → 3:
1. Karst cave regions (this phase), 2. famous real caves at true
entrances/directions/depths, 3. OSM tunnels + hand-modelled sites.
Phases run ahead of biome-plan Steps 6/7 (still queued).

## What Phase 1 does

- **Where:** only where the rocks can actually hold caves — karst
  (carbonate/evaporite) geology from the WHYMAP World Karst Aquifer
  Map (WOKAM v1, BGR/UNESCO, https://download.bgr.de/bgr/grundwasser/
  whymap/shp/WHYMAP_WOKAM_v1.zip). Bundled as a 720×360 (0.5°) byte
  grid `src/main/resources/net/buildtheearth/terraplusplus/karst.gz`
  (20 KB): cell = karst strength 0–100, weighted by rock type
  (continuous carbonate 100, discontinuous carbonate 70, mixed
  carb+evaporite 70, continuous evaporite 55, discontinuous
  evaporite 40; coverage-weighted mean of 0.125° sub-cells).
  16,370 / 259,200 cells ≥ 20 (≈22% of land — matches published
  karst share of ice-free land).
- **Cave biomes:** in karst LAND/COASTLINE columns, `assignBiomes`
  now overrides the surface biome below 12 blocks depth by band:
  lush caves (Y > 20), dripstone caves (−32 < Y ≤ 20), deep dark
  (Y ≤ −32), with null-safe fallbacks. LUSH_CAVES / DRIPSTONE_CAVES /
  DEEP_DARK holders added to `TerraBiomeProvider` (were unused).
- **Carving:** `applyCarvers` was an empty no-op; it now calls
  `carveKarstCaves`. Deterministic world-seeded design: an 80 m
  grid of cells, each seeded by position hash; per cell a worm
  tunnel (60–220 steps, radius ~2–3.3, gentle downward drift,
  chambers every 40–100 steps) is fully determined by world
  coordinates, so the neighbouring chunk recomputes the same worm
  and only writes its own blocks → tunnels cross chunk borders.
  Density scales with karst score (strong karst = seed every cell,
  score 20 = 1 cell in 5).
- **Safety rails:** carves only cave rock (stone, deepslate,
  granite, diorite, andesite, tuft, calcite, dripstone block,
  sandstones); never within 8 blocks below the estimated surface;
  a solid-ceiling check requires rock above every carved block
  (no surface perforation, even on compressed high terrain);
  never in water-classified columns (rivers/lakes/oceans keep
  their water); tunnels clamped to Y ≥ MIN_Y+280.
- **Dressing (light):** floor-block conversion — moss (13%) and
  dripstone block (7%) in the lush band (Y > 20), dripstone block
  (10%) deeper. No water pools, no pointed dripstone yet.
- **Kill switch:** config `realCaves` (default true). If karst.gz
  is missing, `KarstData` answers "no karst" everywhere and the
  whole layer silently disables. Log lines: `CAVES carvedChunks=..
  carvedBlocks=..` every 100 carved chunks.

## Verification (sandbox)

- `make_karst_grid.py` spot checks 13/14 as expected; the single
  "mismatch" (Gandikota) is the test's expectation, not the data —
  the 55 km cell genuinely contains Cuddapah-basin limestone.
- `KarstData` compiled against a stub log4j and run: all 12
  named-place checks PASS (Yucatán 100, Kras 94, Mammoth 100,
  Guilin 90, Nullarbor 100, Florida 70, Podolian gypsum 70;
  Sahara/Amazon/Deccan/Siberia/Phoenix 0), lon wrap & poles safe.
- Edited files parse-clean with JDK 17 (`-encoding UTF-8`).

## NOT proven (owner's eyes decide, as always)

Nothing in-game yet. Sandbox cannot run the Forge carver path.
First test: `gradle jarJar`, new world, teleport to a strong-karst
spot (e.g. Mammoth Cave, Kentucky 37.19, −86.10; or Guilin
24.90, 110.49), dig down / spectator: expect tunnels + chambers
below ~15 m depth, F3 showing Lush Caves / Dripstone Caves /
Deep Dark by depth, and `CAVES carvedChunks=` lines in the log.
Control test: same dig in Sahara / Deccan should find solid rock
only. Watch for: surface holes (should be none — report any with
coordinates), generation slowdown, tunnels under rivers/ocean
edges.

## Honest limits

- Karst grid is 0.5° (~55 km) — cave/no-cave borders are soft at
  that scale; a karst cell next to non-karst rock will cave both.
- Tunnels are worm approximations, not real cave surveys (that is
  Phase 2, and only simplified even there).
- A worm entering a chunk whose neighbour already finished the
  carving stage will not be back-carved into that neighbour
  (same class of quirk as vanilla carvers; tunnels may rarely end
  at a chunk border on first generation, but both chunks compute
  the same worm when each is carved, so in practice borders match).
- Only chunks generated with the new build have caves; old chunks
  never change. Cave decoration (ores already generate elsewhere,
  glow berries, sculk features) is vanilla feature placement and
  not wired into these hand-carved tunnels in Phase 1 — biomes
  show in F3 and affect ambience/spawns, dressing is the light
  floor conversion above.

## Phase 2 seed data

`famous_caves_wokam.csv` (in this folder): the 92 WHYMAP famous
caves with entrance lat/lon, depth (75 have it) and length
(83 have it) — starting point for true-entrance caves.
