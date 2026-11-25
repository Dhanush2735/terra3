# TerraCheck test bench — learning Terra3 by proof, not guessing

Owner directive (2026-11-18): the assistant chooses the tests;
tests run in game via `/terracheck`; answers land in the
Minecraft log under `[TerraCheck]`; the assistant reads the log
and marks each area of Terra3 PROVEN / FAILED / UNTESTED in the
ledger below. "Understood" may only be said when UNTESTED = 0.

Batch 1 implemented (source-only, not compiled — owner builds
with `gradle jarJar`):
`commands/TerraCheckCommand.java`

1. **TC-LOG** — every `/terracheck` chat line is now also
   written to `latest.log` as `[TerraCheck] ...`.
2. **TC-HERE** — `/terracheck here`: for the player's chunk,
   off-thread, read-only: OSM tiles loaded? OSM structure count
   by type (first 25 described: osmId, type, name, levels,
   height, resolved blocks, colour, material, layer); Microsoft
   tiles loaded/missing + buildings here; VIDA shard arrived
   or still in flight (`shard NOT LOADED YET` = the exact
   cold-start moment); world config + year state.
3. **TC-FIND** — `/terracheck find <osmId>`: searches the real
   parser's output for the player's chunk and logs how that one
   feature was classified — or that it is not present at all
   (tile never arrived vs parser dropped it are the next
   question the log distinguishes).

## Predictions — written BEFORE the owner's run

- **Charminar** (stand at `/tpll 17.3617 78.4747`, wait ~1 min,
  type `/terracheck`): expect in the log
  `CHARMINAR osmId=238186115: FOUND -> ... type=BUILDING ... height=19.0
  resolvedBlocks=19 colour=#FFE4B5 material=limestone`
  (Problem-1 fix: real building tag wins over building:part).
  `type=BUILDING_PART` = the fix is not in the built jar.
  `NOT PRESENT` = the tile/fetch road failed, a different bug.
- **Cold start** (fresh world, run `/terracheck here` at once,
  again after ~2 min): first run may show OSM
  `tilesLoaded=false` and/or `VIDA: shard NOT LOADED YET`;
  second run must show `tilesLoaded=true` and VIDA
  `buildingsHere` > 0 in a built-up area. If the second run
  still shows nothing loaded, the refill road is broken —
  that is the cold-start gap, caught by the test.

## Owner run (batch 1)

The owner types ONLY `/terracheck` — plain, nothing after
it. That one command runs the whole batch by itself (status,
chunk test, fetch probes) and includes the Charminar check
for way 238186115 automatically.

1. `gradle jarJar`, start the game with the new jar.
2. New world (or the Secunderabad world) → type `/terracheck`
   at once, then again after ~2 minutes.
3. `/tpll 17.3617 78.4747`, stand still ~1 minute → type
   `/terracheck` once more.
4. Send `latest.log`. Nothing to watch on screen — the log
   is the result.

## Batch 2 — bench fixes + two new tests (implemented 2026-11-18)

Bench fixes (from the batch-1 flaws, all inside
`TerraCheckCommand.java`, nothing else touched):
probes count HTTP 4xx/5xx as NO ANSWER; the OSM probe knocks
on the real structure door (api.openstreetmap.org) as well as
Overpass; status labels the async-road counters honestly;
OSM item list shows buildings/parts first.

New tests bundled into plain `/terracheck`:
- **TC-HEIGHTS** — per chunk: how many buildings/parts carry
  a real `building:levels` tag, a real `height` tag only, or
  no tag at all (Terra3 must guess), plus the tallest.
- **TC-REFILL** — asks the async refill road about THIS
  chunk: pending/done/retries/successes, queue sizes, and
  how many finished structures the refill road already holds
  for this chunk (`NONE YET` = tile still pending).
- **Microsoft VERDICT** — the here-test now prints the
  index's own counters (queries, hits, disk hits, empty
  tiles) and one verdict word: SWITCHED OFF / NEVER ASKED /
  ASKED, STILL WAITING / TILES HERE, NO BUILDINGS /
  BUILDINGS HERE.

### Predictions — written BEFORE the owner's batch-2 run

- Probes: if all doors are healthy, expect `ANSWERS` from
  api.openstreetmap.org; any 5xx anywhere must now print
  `NO ANSWER (HTTP 5..` — if a 502 prints ANSWERS, batch 2
  is not in the built jar.
- Microsoft: batch 1 showed index downloaded + tile download
  started but cache empty at every check. Prediction: the
  verdict will be `NEVER ASKED` (queries=0 — generation never
  calls Microsoft here) or `ASKED, STILL WAITING`
  (queries>0, missingTiles>0). `TILES HERE, NO BUILDINGS`
  would mean the data arrived and the area is truly empty.
  Whichever prints, the open Microsoft question is answered.
- REFILL at spawn, second run (~2 min after the first):
  prediction `refillStructuresForThisChunk` = a number > 0
  (the refill road finished this tile) OR `NONE YET` with
  pending > 0 (still working). `NONE YET` with pending=0 and
  successes=0 would mean the refill road never took the job.
- HEIGHTS at the Charminar chunk: tallestBlocks must be
  ≥ 19 (the Charminar itself). Everywhere: expect a mix —
  Hyderabad OSM tagging is partial, so a large `noTagGuessed`
  count is expected and is data truth, not a bug.

## Batch 3 — FIXES for VIDA-late + Microsoft (implemented 2026-11-18)

Directed: "solve vida late and microsoft problem in batch 3".
Diagnosis proven by code reading (full record:
TERRACHECK_BATCH3_FIXES.md):

- The refill road completes a tile only when ALL tiers answer;
  Microsoft never answered, so every tile deferred forever —
  `successes=0` beside `OSM retry success` lines (stage log vs
  full completion). Exactly one AsyncStructureManager exists;
  /terracheck read the right counters. Chunks born early were
  never topped up.
- VIDA late by construction: warmDestination prefetched every
  source except VIDA; VIDA fetch adds its own 5 s delay; the
  "blocking" VIDA query never waits. First chunks always born
  OSM-only.
- Microsoft: URL/format verified fine live (53.3 MB .csv.gz of
  GeoJSON-Lines, −1.0 = unknown height). The download loop
  buffered the whole file in one StringBuilder + toString copy,
  logged no progress, and caught only Exception — a memory
  Error would leave no log line at all. Matches both sessions.

Fixes: streamed download + progress + FAILED line
(MicrosoftIndex); VIDA prefetch with no delay, fired by
warmDestination (SourceCooperativeIndex, TerraChunkGenerator);
bounded-tier completion so a dead lowest tier cannot veto a
tile (AsyncStructureManager: Microsoft 6 tries, VIDA 12, both
counted as completedWithout*); batch-3 readouts in /terracheck
(download state, VIDA pending, refill verdict).

### Predictions — written BEFORE the owner's batch-3 run

1. `VIDA prefetch … starting now` logged before first PLACED.
2. Microsoft lines RISE (`100000 lines …`), then completion
   (`MSFT quadkey 123301331: N retained`) or a named
   `MICROSOFT FAILED …`. No more silent black box.
3. At spawn within ~2–5 min: `AsyncStruct tile […] queued=…`
   lines and `REFILL VERDICT: WORKS` (successes > 0);
   completedWithoutMsft may be > 0 — honest, not a failure.
4. VIDA pendingRegions > 0 early, buildingsHere > 0 sooner
   than batch 1/2.
5. If Microsoft lands: cacheTiles > 0 and msftHits > 0 (the
   proof; a single chunk may still show buildingsHere=0 due
   to OSM/VIDA outranking + dedup).
6. ALL sources now tested (owner directive): ESA classes
   mostly BUILT_UP + READY at both places (UNKNOWN on every
   check = new finding); elevation ~500–600 m READY; water
   DATA ARRIVED; Overture SERVING, or NOT SERVING only with
   healthy OSM in the same log; OHM honestly OFF BY SWITCH
   while timejump is disabled. Roads/rails/bridges stay
   covered by the OSM byType count.

### Owner run (batch 3)

The owner types ONLY `/terracheck`.

1. `gradle jarJar`, start the game with the new jar.
2. NEW world → `/terracheck` at spawn, again ~2 min later,
   again ~3 min after that.
3. `/tpll 17.3617 78.4747`, stand still ~1 min → `/terracheck`.
4. Send `latest.log`.

### Batch-3 result (owner's log, New World (5), 2026-11-18)

Full verdict: TERRACHECK_BATCH3_RESULT.md. All three fixes
PROVEN: VIDA prefetch fired at warmup (shard ~16 s, READY
at first check); Microsoft download streamed to completion
(628,630 lines, 166 s) and placed buildings (microsoft>0,
heavily at the Charminar); refill road completed tiles
(243 chunks queued, 21,111 async blocks, shutdown
successes=4 vs batch-2's 0). ESA READY, elevation
537.8/507.7 m, water DATA ARRIVED, OHM honestly OFF.
## Batch 4 — sea-level problems + source stack (implemented 2026-11-18)

Owner directive: "freedom to test all the upcoming problems."
Bench-only batch (TerraCheckCommand): EXTREMES (9 world
extremes → raw elevation + game Y, in-game), SEAM (pure-maths
scale audit: 1000 m join step, below-sea 10:1, trench squash,
ceiling/floor), SENTINEL SCAN (-50 fake-ocean risk in this
chunk), DEAD SEA GRID (25 samples; satellite-void dirt),
SOURCE STACK (buildings per source for this chunk — the
Microsoft step-3 pre-test), fetch adds Wikidata/Wikipedia
doors. Same owner's build also carries the Overture fix
(OVERTURE_FIX.md) and Microsoft speed fix
(MICROSOFT_SPEED_FIX.md) — one run tests everything.

### Predictions — written BEFORE the owner's batch-4 run

1. EXTREMES: Hyderabad dryLandY ≈ 570-571 (proven formula);
   Everest dryLandY 1950-2032; Mariana oceanFloorY ≈ -2020;
   Dead Sea W shore elev ≈ -430..-380 (may need a rerun
   while far tiles land). Any NOT LOADED rows shrink on the
   second /terracheck.
2. SEAM: join step = 2-3 blocks; 8848 m -> Y2032 (exact top);
   -430 m -> Y19ish; -11034 m -> Y-2028 (exact floor).
3. SENTINEL SCAN at spawn/Charminar: 0/5 (tiles prefetched).
4. DEAD SEA GRID: SOURCE DATA DIRTY (positive spikes) —
   sandbox sampled +180 m mid-sea at z14.
5. SOURCE STACK at Charminar: osm leads (hundreds);
   overture>0 resident after bench's own Overture call;
   at spawn: osm leads, msft>0.
6. Doors: Wikidata/Wikipedia ANSWERS; OSM doors as before.
7. Overture lines: release discovered (2026-10-21.0 or
   newer), header self-check PASS, VERDICT: SERVING.
8. Microsoft: "parallel download" line with MB/s; total
   cold time < 166 s unless the owner's per-connection
   speed is the hard limit (then ~166 s, but hidden under
   warmup).

### Batch-4 result (owner's log, 2026-11-18 19:14)

Full verdict: TERRACHECK_BATCH4_RESULT.md. 6.5/8
predictions. Overture PROVEN in-game (SERVING, zero
header FAIL); EXTREMES/SEAM/SENTINEL all PASS in-game;
DEAD SEA dirt confirmed; Wikidata door flaky (1/5);
Microsoft parallel NOT exercised (warm cache + 2.2 MB
rural file). Session was also the de-facto warm-cache
test: cache-served, values matching batch 3.

### Owner run (batch 4) — unchanged shape

1. `gradle jarJar`; NEW world (fresh cache = the honest
   cold test).
2. `/terracheck` at spawn, again ~2 min later, again
   ~3 min after that (reruns also land the EXTREMES tiles).
3. `/tpll 17.3617 78.4747`, wait ~1 min, `/terracheck`.
4. Optional but valuable: `/tpll` to a rural spot
   (e.g. /tpll 17.9 79.6), wait ~1 min, `/terracheck` —
   the SOURCE STACK gap-filler case.
5. Send latest.log.

---

One FAILED row remains: the Overture appetizer (header
FAIL even when armed). Charminar standing still awaits
the owner's eyes — the game announced it emerging
(17:52:54) in this very log.

### Owner run (batch 2) — same as batch 1

The owner types ONLY `/terracheck`.

1. `gradle jarJar`, start the game with the new jar.
2. Same world → `/terracheck` at once, again after ~2 min.
3. `/tpll 17.3617 78.4747`, stand still ~1 min → `/terracheck`.
4. Send `latest.log`.

## Knowledge ledger (Terra3 systems)

Status meaning: PROVEN = log/world evidence read by the
assistant. READ = code read, no runtime proof yet.
UNTESTED = not yet examined. FAILED = proof shows a bug.

| System | Status | Evidence |
|---|---|---|
| `/terracheck` bench: log output, here, find | PROVEN | batches 1+2 compiled + ran 2026-11-18; TERRACHECK_BATCH1_RESULT.md, TERRACHECK_BATCH2_RESULT.md (batch-2 fixes all proven in the batch-2 log) |
| OSM fetch + tile cache (disk/mem) | PROVEN (cold) | TEST1_COLD_RESULT.md + batch-1 log; warm test still pending |
| OSM parser classification (building vs part) | PROVEN | batch-1 log: Charminar 238186115 = BUILDING, height 19, #FFE4B5, limestone, flat |
| Generation-road placement (OSM→VIDA→Microsoft) | PROVEN (cold) | [TerraStruct] PLACED lines, TEST1_COLD_RESULT.md + batch-1 log |
| Async retry/refill road | PROVEN | batch-3 log (NW5): give-up fired 17:50:56, tile completed, 243 chunks queued, 21,111 blocks placed via async road, shutdown successes=4 (batch 2: 0 forever). Dense-OSM tiles skip VIDA tier by design (TERRACHECK_BATCH3_RESULT.md) |
| Microsoft fetch + gap-fill placement | PROVEN; SPEED FIX APPLIED 2026-11-18 (sandbox-proven, awaiting owner timing) | batch-3 log: streamed download finished 628,630 lines in 166 s, cacheTiles 17→51, totalBuildings 8,309, PLACED microsoft>0 widely (heavy gap-fill at Charminar). Speed fix: fire-and-forget warmup prefetch + 6-way parallel range download with single-stream fallback (MICROSOFT_SPEED_FIX.md); sandbox: 53.3 MB in ~1 s + full parse in 4.5 s |
| VIDA shard fetch + placement | PROVEN (incl. start timing) | batch-3 log: prefetch fired at warmup 17:48:50 (before first PLACED), shard landed ~16 s later, READY at first /terracheck; TEST1_COLD_RESULT.md for the old late behaviour |
| Duplicate-building guard (floating tower) | READ + fix; log-consistent | FLOATING_BUILDING_FIX.md; owner visual still the gate |
| Timejump OFF switch (year 2026, OHM structures off) | PROVEN | cold + fix-test logs |
| Timejump ON: OHM structures, refresh engine, year change | UNTESTED | disabled at present; test when re-enabled |
| Bridges (deck, towers, layer heights) | READ (world census + in-game parse) | newworld1_READING.md; batch-2 log: BRIDGE items parsed, layer-4 crossing handled, BEAM areas; appearance unproven |
| Water (OSM/OHM water, rivers/lakes) | PROVEN (fetch); placement unchecked | batch-3 log: DATA ARRIVED all checks via OHM tiles (9→15 cached) + OSM mail.ru fallback despite Overpass 502s; whether water is placed in world still open |
| Vegetation / landuse / EsaWorldCover | PROVEN (fetch + serve) | batch-3 log: READY all checks, classes {BUILT_UP=5} at spawn + Charminar; neighbour tile N12E081 404 suppressed (harmless); appearance still owner's eyes |
| SHORE bench (`/terracheck here` Step 4) | IMPLEMENTED 2026-11-19; world-side PROVEN via NW16 save scan, bench verdict line still unobserved in a log | NW16: beach in 126 chunks (NW14/15: 0), clean B band on Juhu sand, sand columns beach 12,153 dominant. Owner F3/`SHORE VERDICT: PASS` log line still welcome but the saved biomes already prove the route. |
| Missing sea / coastline (Juhu) | ROUND-2 PROVEN BY SAVE SCAN; ROUND-3 TEETH FIX IMPLEMENTED, test pending | Owner sent NW(12) save: scanned 3,150 chunks/354,816 cols → 142,305 water-topped (NW(10): 13), floor flat Y58-60 → sea EXISTS, round 2 proven in real blocks. BUT shore has teeth (±4.6 blocks avg, max 21, 46% reversals): this save was painted mid-cold-start from two shorelines (smooth OSM line vs ESA 10m edge 5-25m inland; ESA strip + OSM profile measured). Old repair could only carve. Round-3: TerrainColumn.mappedLand + placeSeaFillColumn + "SEA REPAIR FILL" INFO log (natural-bed/empty-shaft rails, river/lake veto, SL+4 cap). Owner test: rebuild, reopen NW(12), walk Juhu shore → SEA REPAIR/FILL lines + teeth heal; fresh west shore born smooth; /terracheck SEA FOUND IN WORLD. Maps: nw12_save_map.png, juhu_esa_vs_osm.png. Record: COASTLINE_MISSING_SEA.md Round 3. |
| Missing sea / coastline (Juhu) — fix | IMPLEMENTED in source 2026-11-19 | OSM API coastline fallback door (map.json, 2x2 boxes, disk-cached) + ESA water-area witness in classify + bounded 2.5s coast wait in generateTerrain + SEA REPAIR pass in syncHistoricalWater (seaRepaired=) + /terracheck COAST bench with VERDICT line. Proof wanted: Juhu sea present in fresh chunks; COAST samples OCEAN on sea side. |
| Missing sea / coastline (Juhu) — diagnosis | DIAGNOSED from owner save | Owner save parsed: 2,500 chunks, water columns = 13 total; sea area is flat grass Y62–65. Cause: classify() coastline step is memory-only + generateTerrain never waits; elevation fallback can't say ocean near coast (live Terrarium: z15 offshore = 0.00 m, bathy chain +1.9…+26 m at Juhu); late repair skips OCEAN columns and analyze() ignores open sea. Coastline sign math verified CORRECT vs real OSM way 1459343480. Record: COASTLINE_MISSING_SEA.md, map juhu_save_map.png |
| Biomes + ground blocks | STEP 1 PROVEN IN-GAME; STEP 2 bench PROVEN; palette/beach/repaint OPEN | NW(10) Juhu log 2026-11-19: CLIMATE PASS (Hyderabad 796, Cairo 28 HYPER_ARID, Amazon 2236 WET, Berlin 664; Juhu 2111 WET). BIOME/GROUND caught the beach disease numerically: ESA={BARE=256} vs BIOME={mangrove_swamp=256} vs TOP={grass_block=256}, mismatch 256/256 (generation ran wc=UNKNOWN → soil-wetland fallback → mangrove+grass, fossilised; OSM beach polygon rescued the next chunk: sand=214). Next: Step 3 shore/BARE rules + cold-start ground handling (BIOME_GROUND_FIX_STEP1_2.md) |
| Elevation (terrain heights) | PROVEN | batch-3 log: 537.8–539.8 m spawn, 507.7 m Charminar (tpll agrees: 508.0 m); READY all checks. Sea-level review 2026-11-18: above-sea exact ≤1000 m then squashed (Everest 8848→Y2032 = world top exactly, dimension min_y −2032 height 4064 matches); dry below-sea 10:1 (Dead Sea shore → ~Y19); ocean depth via z8..z3 bathy fallback (Mariana −10,389 → Y−2023). Batch-4 EXTREMES/SEAM/SENTINEL sections re-prove in-game |
| Fake-ocean sentinel (−50 on failed height tile) | RISK MEASURED (no fix yet) | batch-4 SENTINEL SCAN: SAFE 0/5 at spawn, Charminar and rural chunks. Fix (regenerate-on-late-height) only on owner direction |
| Dead Sea terrain appearance | DATA DIRT PROVEN in-game; appearance UNTESTED | batch-4 DEAD SEA GRID: loaded 25/25, min −412.1 m, max +1180.5 m, 10/25 positive — satellite voids confirmed in the owner's log. Appearance needs owner's eyes (/tpll 31.5 35.55) |
| Microsoft parallel download (step 2) | PROVEN IN-GAME (2026-11-18 21:29 run) | Fresh cache (marker FRESH, 17.5 MB): check 1 caught `DOWNLOADING (parallel x6) quadkey=123301331 0.3/53.3 MB`; log then `MICROSOFT parallel download quadkey 123301331: 53.3 MB in 24s (2.22 MB/s, 6 connections)`, 628,630 lines in 26 s (old road: 166 s). Check 2: `MICROSOFT VERDICT: BUILDINGS HERE (10)`, totalBuildings=2257; SOURCE STACK osm=79 vida=29 microsoft=10. Delhi teleport proof no longer needed |
| Heightmap source quality (Terrarium vs alternatives) | RACE DONE — no single winner; guard prescribed | 16-place championship (HEIGHTMAP_RACE.md): land levels TIE (Hyderabad 507.8 vs Copernicus 507.8 vs Mapterhorn 507.5); Terrarium's only disease = monster pixels on flat water (Dead Sea −5,227 m, Caspian −5,535 m); Copernicus REJECTED as sole book (Manhattan median 61.4 m rooftops vs 17 bare); Mapterhorn best ground where national data exists (Amsterdam 1.0) but India coarse z12 + WebP + seams; oceans = Terrarium z8 only. Next: Step 2a spike-guard prototype + 2b Mapterhorn Java-door test (workshop), Step 3 game change only on owner go |
| Spike guard v3c (heightmap armour) | PROVEN IN-GAME (2026-11-18) | HEIGHTMAP_STEP3.md: owner log — `Height guard repaired 512 suspect elevation samples in tile 15/20980/12164 (512 by zoom-anchor)`; CASPIAN MONSTER-POINT elev=-29.0 (raw −9,935.2); session total 730 repairs (anchor 714) incl. 3 Dead Sea basin tiles; EXTREMES/SEAM/SENTINEL unchanged PASS. A/B off-proof optional, untested. Terrarium untouched; disk cache now stores RAW tiles only |
| Mapterhorn Java door (WebP decode) | WORKSHOP-PROVEN | TwelveMonkeys ImageIO WebP 3.15.3 (pure Java, no natives): Dead Sea z12 tile decoded in 111 ms, min/median/max byte-exact vs Python. Hybrid layer remains optional/separate decision (seams: Amsterdam book offset −4.6 m → 16 px blend band) |
| Poles beyond ±85° | KNOWN LIMIT (by design) | classify() hard-returns ocean −1000 m → floor Y−937; South Pole plateau lost. Projection-bound; not benched |
| Wikidata/Wikipedia lookup (building years) | UNTESTED → door probes added batch 4 | WikiDataLookup doors (query.wikidata.org, en.wikipedia.org) now probed by /terracheck fetch; real lookup only matters with timejump on |
| Source coverage comparison (MSFT step-3 pre-test) | UNTESTED → bench added batch 4 | SOURCE STACK prints buildings per source per chunk; decides whether VIDA/Overture can carry Microsoft's gap-fill role |
| Overture appetizer (backup source) | PROVEN (fixed + in-game) | batch 2/3: header FAIL forever, 0 served. Fix (OVERTURE_FIX.md): runtime release discovery (2026-10-21.0 discovered in-game), 60 s retry cooldown, reason-logging, rotation-proof cache. Batch-4 log: header self-check PASS both themes, ZERO header FAIL, VERDICT: SERVING at spawn (run 2) and Charminar (run 4), generator road served 15 structures in 3 ms |
| OHM historical structures | UNTESTED (off by switch) | bench printed honest OFF BY SWITCH verdict in batch-3 log; real test when re-enabled |
| Roads/rails classification + widths | UNTESTED | — |
| Heights/roofs/materials/colours rules | PROVEN (where heights come from) | batch-2 TC-HEIGHTS: Secunderabad 184/184 buildings untagged (all guessed), levels tags 0, height tags only on monuments (Charminar 19, Char Kaman 21); how good the guesses look = owner's eyes |
| Projection / tpll coordinate conversion | READ | consistent spawn evidence in logs |
| Cache warm-start behaviour | UNTESTED | warm Test 2 still pending |

Rule: a row moves only on evidence the assistant has read
(log, world file, or the owner's in-game report). After every
batch, UNTESTED and FAILED counts are reported honestly.
