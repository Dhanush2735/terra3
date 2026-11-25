# Missing coastline / sea — Juhu Beach (New World (10))

Status: DIAGNOSED from the owner's world save + live data tests,
2026-11-19. FIX IMPLEMENTED in source 2026-11-19.
**PROVEN IN-GAME (bench level) 2026-11-19, New World (11) owner
log 01:42–01:46:** `COAST VERDICT: SEA FOUND`; osm tile cached;
WEST 300m = SHALLOW_WATER/coast OCEAN, SOUTH 300m = SHALLOW_WATER/
OCEAN, EAST/NORTH = LAND. The new OSM API fallback door fired in
anger: Overpass `de returned 502` repeatedly, then
`OSM API coastline [19.1000,72.7500]: 786 coast segs`. ESA witness
samples at the shore read `Permanent water bodies`. Bathy lines
also reconfirm the original diagnosis (z8 +2.1m offshore).
**OWNER VISUAL CHECK 2026-11-19: FAILED — no water at the
coast.** The bench was classify-only and gave a false green.
Round-2 root cause found in source the same day (see Round 2
below): `generateTerrain`'s elevation cleanup passes
(`oceanBFS` + `reclassWB`) turned every mapped shallow-sea
cell back into LAND before painting, because "honest ocean"
required elevation ≤ −500 m (WEM) while mapped shallow sea
carries −5 m. Round-2 fix implemented in source (mappedSea
flag); in-game proof pending owner rebuild.
Note: BIOME/GROUND in the spawn chunk now reads TOP sand=240/256
(OSM beach polygon + sea-side classification), but the bench
still flags mismatch=256 because the *current rule* expects
red_sand for ESA BARE — that is the separate Step 4/5 shore
palette work, not this fix.
Owner directed the approach: decide
land/sea the OpenStreetMap Carto way — from mapped AREAS
(coastline land areas + water areas), not from height guesses.

Implemented (all JDK 17 parse-checked; owner compiler is the
real check):
1. `OsmWaterIndex.fetchCoastlineViaOsmApi` — when all Overpass
   endpoints fail, coastline segments come from the official
   OSM API (`api.openstreetmap.org/api/0.6/map.json`, the door
   structures already prove alive). Tile split into 2x2 boxes
   (50k-node cap), polite 300 ms spacing, 3-fail 120 s cooldown,
   disk-cached as synthesized out-geom JSON. Log line:
   `OSM API coastline [lat,lon]: N coast segs (Overpass fallback door)`.
2. `TerraDensityFunctions.classify` STEP 1C — ESA WorldCover
   permanent-water AREAS as second sea witness (wired in
   `TerraChunkGenerator.getDf` via `setEsaWaterQuery`).
3. `TerraChunkGenerator.generateTerrain` COAST WAIT — chunk
   centre elevation in −5..+5 m and coastline tile missing →
   one bounded 2.5 s `getTileBlocking` before classifying.
4. `syncHistoricalWater` SEA REPAIR — classify says OCEAN but
   the column holds no water and ends in natural ground at
   ≤ sea level + 2 → `placeSeaRepairColumn` carves it to the
   classify floor and fills water to sea level (structures and
   tree-topped columns untouched). Counted as `seaRepaired=`
   in the syncWater log. Rides the existing AsyncWaterManager
   ready-chunk drain, so it also heals old saves.
5. `/terracheck` COAST bench (`coastCheck` in
   TerraCheckCommand, runs inside plain `/terracheck`): osm
   tile cached?, classify + coastline side at player and
   300 m W/E/N/S, verdict line `TEST here COAST VERDICT:`.

In-game proof wanted: fresh world (or walk to ungenerated
coast) at Juhu → sea present west of the beach; `/terracheck`
at the beach shows COAST samples OCEAN on the sea side. In the
old NW(10) save, stand at the fake-land coast, wait for water
tiles, and watch for seaRepaired refresh + water appearing.

## Symptom

At Juhu Beach (Mumbai) there is no sea. West of the beach, where
the Arabian Sea should be, the world is a flat grass plain.

## Proof from the owner's save (newworld10.zip)

Parsed all 2,500 chunks / 257,280 columns of the saved area
(528 × 512 blocks around spawn 9423176, -1911459):

- **Water columns in the whole save: 13** (one small drain trickle).
  The sea should cover roughly the western half (~100k columns).
- West edge profile at spawn latitude: top block `grass_block`
  at Y62, rising smoothly eastward Y63 → Y65 → Y71+. Land
  elevations ~0–3 m where the sea floor should be.
- Map: `juhu_save_map.png` (top blocks; red cross = spawn on the
  sand strip; the pale-green flat sheet south-west of the beach
  is the missing sea).

## Root cause — three links, all proven

1. **The sea is decided by one memory-only lookup at generation
   time.** `TerraDensityFunctions.classify()` asks
   `OsmWaterIndex.classifyCoastlineMemOnly()`. If the coastline
   tile is not already in memory it returns UNKNOWN, and
   `generateTerrain()` deliberately never waits ("Never wait for
   public Overpass while generating a chunk"). World-spawn chunks
   generate in the first seconds, before any fetch can land.

2. **The fallback cannot say "ocean" near a coast.** Without the
   coastline answer, classify falls back to elevation:
   `elev >= -1.0 → LAND`. Live Terrarium test at Juhu
   (19.0968, 72.8265, sandbox fetch 2026-11-19):
   - game zoom z15 offshore: **0.00 m** for kilometres (Terrarium
     fine tiles carry no sea depth; ocean reads exactly 0);
   - bathymetry chain z8/z7/z6/z5/z3 at 300–800 m offshore:
     **+1.9 / +3.4 / +7.1 / +14.4 / +26.2 m** — coarse pixels are
     land-contaminated near Mumbai; z8 first dips below −1 m only
     ~1.6 km offshore. So `fetchBathymetry()` finds nothing < −1,
     elevation stays ≈0 → LAND, snapped to sea-level Y63. That is
     exactly the flat Y62–63 grass sheet in the save.

3. **Nothing repairs it later.** The coastline math itself is
   correct — tested against the real OSM coastline way
   1459343480 from api.openstreetmap.org: offshore 300 m →
   OCEAN (−272 m signed), inland 300 m → LAND. But:
   - `OsmWaterIndex` gets coastline data **only from Overpass**
     (3 endpoints), which chronically 502/timeouts in the owner's
     environment;
   - the late-repair path (`syncHistoricalWater`) explicitly
     **skips** columns that classify as OCEAN ("never drain real
     ocean"), and `RiverLakeEngine.analyze()` only matches closed
     water polygons/lines — the open sea is not a feature. So a
     chunk born as fake land stays fake land forever.

Same disease family as the ESA cold-start ground fossilisation
(Step 3), but for water: a non-blocking lookup + an impossible
fallback + no repaint = permanent wrong terrain.

## Proposed fix (pending owner "start")

1. **Second door for coastline data:** teach `OsmWaterIndex` to
   fetch its 0.05° coastline tiles from the main OSM API
   (`api.openstreetmap.org/api/0.6/map?bbox=`) when Overpass
   fails — that door is proven alive in the owner's environment
   (structures use it). Coastline ways only; keep Overpass for
   water polygons.
2. **Bounded wait for the sea decision:** in `generateTerrain`,
   only when a column's elevation is in the sea-ambiguous band
   (−1…+2 m) and the coastline tile is missing, wait briefly
   (≤ ~3 s, like elevation tiles already do) for the tile before
   classifying. Land interiors never pay this wait.
3. **Coast repair pass:** when a water tile arrives late for an
   already-generated chunk (AsyncWaterManager ready queue),
   re-classify its columns; columns that are now OCEAN/SHALLOW
   and carry no structures get carved to ocean floor and filled
   to sea level. This also heals existing saves like this one.
4. **Bench:** `/terracheck` gains a COAST section — coastline
   tile cached?, classify verdict + elevation at 300 m/800 m
   offshore and inland of the player, and water-column count in
   the current chunk — so the fix can be proven from the log.

Old saved chunks keep their fake land until the repair pass (3)
runs on them or the region is regenerated.

## Round 2 — bench said sea, world was dry (2026-11-19)

Owner, New World (11): "there is no coastline (water)". The
COAST bench only called `df.classify()` — it never looked at
the painted blocks. Reading `generateTerrain` shows why both
could be true at once:

1. `classify()` (fixed in round 1) returns SHALLOW_WATER with
   elevation **−5 m** for mapped sea (`classifyAsOcean` maps
   any elev ≥ −0.5 to −5.0).
2. `oceanBFS()` seeded "real ocean" only from grid-edge cells
   with `elevationMeters <= WEM`, and **WEM =
   DRY_LAND_MIN_ELEV = −500 m**. A −5 m shallow cell can never
   seed, so `or[][]` stayed all-false and the next loop
   converted **every** OCEAN/SHALLOW cell to LAND
   (dry Y for −5 m = 62, painted grass — the fake coast).
3. `reclassWB()` second pass did the same thing cell by cell:
   shallow ocean with no ≤ −500 m "honest" neighbour and any
   land neighbour → LAND. Deep ocean (≤ −500 m) survived,
   which is why only the *coastline* water was missing.

Round-2 fix (source, JDK 17 parse-checked):

- `TerraDensityFunctions.TerrainColumn.mappedSea` — new flag,
  set true only when the sea verdict came from a mapped area
  (coastline OCEAN or ESA permanent water), false for the
  elevation guess. Old constructors keep working (false).
- `TerraChunkGenerator`: mapped sea is now exempt from the
  elevation cleanup — `oceanBFS` seeds from mapped border
  cells, the BFS conversion loop and both `reclassWB` passes
  skip mapped cells, and a mapped neighbour counts as honest.
  Elevation-only shallow guesses (dry basins like Death
  Valley) keep the old −500 m treatment.
- SEA REPAIR tightened the same way: it carves only mapped
  sea (or ≤ −500 m verdicts), never a shallow height guess;
  and it now logs at INFO (`SEA REPAIR [x,z]: N columns
  carved into real sea`) so old-save healing is visible.
- `/terracheck` COAST bench now probes REAL blocks
  (`worldWater`: world=WATER/dry/not-loaded + top block at
  here and the four 300 m samples). Verdicts: `SEA FOUND IN
  WORLD` / `MAP SAYS SEA BUT WORLD IS DRY` / `SEA ON MAP
  ONLY` (samples unloaded — walk into the sea and re-run).

Proof wanted after rebuild: fresh world at Juhu → real water
west of the beach, `/terracheck` at the shore shows
`world=WATER` on the sea-side samples and `SEA FOUND IN
WORLD`; in the old NW(10)/NW(11) saves, watch for
`SEA REPAIR` lines as coast chunks load.

## Round 2 test — New World (12) log 2026-11-19 02:03–02:07

Result: **INCONCLUSIVE for the west sea; the bench upgrade is
proven live.** The new round-2 jar was running (log shows the
`world=` probes and the new honest verdict).

- `COAST: osmTileCached=true here=SHALLOW_WATER elev=-5.0m`
  (19.09702, 72.82662); WEST/SOUTH 300m SHALLOW/coast OCEAN,
  EAST/NORTH LAND — map side correct again.
- WEST and SOUTH samples: `world=not-loaded` — those chunks
  were never opened (300 m ≈ 19 chunks > view distance 12),
  so NW(12) says nothing about real blocks in the sea.
- Verdict printed: `MAP SAYS SEA BUT WORLD IS DRY` — driven
  by the HERE column only: the player stands on beach sand
  (BIOME/GROUND: TOP sand=256, y=67, biome desert) in a
  column classify() now calls SHALLOW_WATER. That chunk was
  painted before the coastline map arrived, and the repair
  rail (top ≤ SL+2 = 65) will not carve a Y67 beach column.
  Bench now also prints a `world-here:` line so this is
  self-explaining in future logs.
- Cold start again: `CACHE MARKER: FRESH`; spawn generation
  began 02:03:46, OSM coastline door answered only 02:05:58
  (`786 coast segs`) after repeated Overpass 502s; player
  logged in 02:05:00 and quit 02:06:33 (two pauses between).
  No `SEA REPAIR` line — repair had ~1 minute of tick time,
  and the nearby fake-land is the Y67 beach the rail skips.
- Cache is warm now (149 MB), so the next run does not
  start cold.

Decisive test still wanted (same NW(12) world): fly/walk
400–500 m WEST into never-loaded ground, wait ~30 s, eyes
down, then `/terracheck` standing in the sea area. Good:
`world=WATER` + `SEA FOUND IN WORLD`. Bad (fresh west ground
still grass): one more painter step is killing mapped sea —
hunt continues from that log.

## Round 3 — the save itself speaks; coastline teeth (2026-11-19)

Owner sent the NW(12) save (tmpfiles zip). Scanned with a
pure-Python NBT/Anvil reader (downloads/nw12/scan_nw12.py;
world height 4064 → heightmap values are 12-bit, not 9):

- 3,150 chunks, 354,816 columns, X 9422912–9423487,
  Z −1911744–−1911041. **Water-topped columns: 142,305**
  (NW(10): 13). **Round 2 is PROVEN in real blocks.**
- Sea floor flat: Y58 (74k cols), Y59 (48k), Y60 (15k) — the
  mapped −5 m rule everywhere; no elevation-fallback sea.
- Maps: nw12_save_map.png (top blocks), juhu_esa_vs_osm.png.

Owner complaint, confirmed: "coastline is rough and blocky."
Measured shore (per-row water→land transition, 623 rows):
step |dx| mostly 0–2, but direction reversals 46%; shore
wanders ±4.6 blocks (max 21) around its smooth drift; 41
jumps ≥4 blocks. NOT aligned to chunk borders (10% of jumps
near a border vs 25% random) — the chunk-mosaic theory in
its naive form is dead; the mixture is finer than chunks
(the coastline tile landed mid-generation, and classify
answers per column from whatever is resident).

Cause (measured): two different shorelines were used.
- Real OSM line at Juhu (Overpass fetch, way 1459343480):
  13 nodes in 1,478 m, segment median 114 m, turning ≤7° —
  intrinsically smooth.
- ESA WorldCover edge (fetched strip of tile N18E072, 10 m
  pixels): water edge sits **5–25 m inland** of the OSM line
  (profile: ESA water in 7/7 samples at −5 m seaward, still
  6/7 at +5 m and 4/7 at +10 m landward), with 10 m steps.
- NW(12) was painted during the cold start (coastline map
  arrived 02:05:58, generation began 02:03:46). Columns
  painted before arrival used ESA (then elevation); after
  arrival, OSM. The visible edge jumps between the two lines
  — that jump is the teeth. Fossilised because repair could
  only carve (land→sea), never fill (sea→land).

Fix (implemented, source-only, JDK17 parse-clean):
- `TerrainColumn.mappedLand`: true only for coastline-area
  LAND and coastline-band verdicts; elevation fallback and
  ESA-not-water verdicts stay false.
- `syncHistoricalWater` new branch: LAND + mappedLand +
  sea-level water present (waterTopY in [SL−1, SL]) →
  `placeSeaFillColumn`, vetoed when `rle.analyze` reports a
  mapped river/lake feature. Fill builds to classify
  surfaceY (shore cap SL+4, no pillars), DIRT fill, SAND top
  at ≤SL+1 else GRASS_BLOCK, clears water/plants above to
  SL+1 (mirrors pLand's anti-flood rule). Safety rails mirror
  the carve: natural sea-bed floor whitelist (incl. coral
  blocks), water shaft + 2 above must be water/aquatic
  plants/air only. INFO log:
  `SEA REPAIR FILL [x,z]: N columns filled into real land
  (fake-sea coast healed)`; counter joins the heightmap
  rebuild condition.

Owner test: rebuild, reopen NW(12), walk the Juhu shore,
watch for SEA REPAIR / SEA REPAIR FILL lines and teeth
healing; fresh western chunks (warm cache) should be born
smooth. Prevention (don't paint final shore from backup maps
while the coastline tile is still in flight — provisional
coast repaint) remains Step-3 territory, not this round.
