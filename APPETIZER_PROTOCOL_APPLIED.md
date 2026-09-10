# Appetizer Protocol — Cold-Start Progressive Serving

User-specified design: "if customer is starved give appetizers before
serving main food." Download both fast sources and Overpass in parallel;
serve fast-source content immediately as PROVISIONAL; swap to full OSM
when the tile lands; after that the area is self-sustained (OSM on disk).

## Flow

```
COLD chunk, OSM tile still cooking (7-90s):
  fetchStructuresForChunk -> OSM blocking fetch returns null
    -> osmPending = true
    -> APPETIZER: VIDA(Google) > Microsoft buildings served now
       (1.5s bounded waits; both are fast/cached sources)
    -> ProvisionalServing.markServed(chunk, tile)
    -> chunk generates WITH buildings instead of bare

OSM tile lands (either path):
  - AsyncStructureManager tier-2 "OSM retry success"
  - OsmStructureIndex batch fetch tile resident
    -> ProvisionalServing.notifyTileArrived(tile centre)
       (O(1) no-op if nothing provisional = warm start)
    -> hops to server thread
    -> per provisional chunk: invalidateChunk +
       scheduleSingleChunkRefresh  (RefreshEngine swap —
       same battle-tested machinery as a time jump)

End state: pure OSM hierarchy (OSM > VIDA > MSFT), disk-cached,
appetizers never served in that area again.
```

## Files

- `generator/historical/ProvisionalServing.java` (NEW) — the ledger.
  byTile/byChunk maps, MAX 8192 (overflow -> untracked, heals via
  refresh index), clear() on level unload, self-check logs.
- `TerraChunkGenerator` — `APPETIZER_SERVING` static toggle (same A/B
  pattern as USE_REFRESH_ENGINE); `osmPending` branch in
  fetchStructuresForChunk serves fast tiers + marks provisional.
- `AsyncStructureManager` — swap hook on OSM retry success.
- `OsmStructureIndex` — swap hook on batch-tile residency.
- `PersistentChunkRefreshIndex.onLevelUnload` — ProvisionalServing.clear().
- Analyzer: appetizer_served / main_course / appetizer_full patterns +
  Phase-2 checks (served vs swapped ratio; ledger overflow) — verified
  against synthetic log.

## Design guarantees

1. Single owner at any moment: appetizer owns until swap; OSM after.
2. Absence is PENDING: appetizer fires only on OSM-pending, never
   OSM-empty. Empty OSM is a valid main course.
3. Warm start untouched: OSM answers from disk -> osmPending never set;
   notifyTileArrived is O(1) when ledger empty.
4. Chunk unloaded before swap -> DeferredChunkRefreshLedger (existing
   safety net). Ledger cleared on unload; staleness still tracked by
   the persistent refresh index.
5. Feature-off switch: APPETIZER_SERVING=false restores old behaviour.

## Verified

- Full compile: 0 non-baseline errors in all 5 touched files.
- Analyzer syntax + behaviour on synthetic appetizer log: PASS.

## Expected next run (cold area)

- `APPETIZER served chunk [x,z] (N fast-source features; OSM pending)`
  within ~2s of arrival — buildings visible on generation.
- `MAIN COURSE arrived for tile [lat,lon]: N chunks to swap` when the
  Overpass batch lands; RefreshEngine drains the swaps at 8ms/tick.
- Analyzer `--phase cold`: "appetizer -> main course" PASS with
  served ≈ swapped.

## Roadmap (agreed with user)

- Step 2: Overture(OSM) buildings extract via FlatGeobuf behind the
  same appetizer socket (upgrades appetizer to real OSM-derived
  buildings with names/heights).
- Step 3: Overture GeoParquet reader for transportation + base/water
  (appetizer gains roads and water; verified live: Wellington Road,
  "Durgam Cheruvu" lake by name, 2026-08-19 release).
- Step 4 (option 2-B): diff-swap — skip re-placing tag-light features
  whose Overture and OSM versions are equivalent; only swap tag-rich
  deltas. Build after churn numbers from naive swap logs.

---

## Standalone test results (sandbox, real class + mocked MC)

Harness: /tmp/appet_test — the REAL ProvisionalServing.java compiled
unmodified against instrumented mocks (same method as the RefreshEngine
sim that caught its first-check bug).

**Unit suite: 24/24 PASS** — serve/track/log, swap (schedule+invalidate+
drain+log+server-thread hop), dedup, tile isolation, unloaded->deferred,
warm-start no-op, wrong-tile no-op, no-server survival, clear(), 8192 cap
+ overflow log, negative coords (southern hemisphere), lifetime counters,
double-arrival idempotence.

**Race test (8 writer threads vs concurrent arrivals), 10/10 PASS** — and
it caught two real bugs before they ever reached the game:

1. **Serve-after-arrival orphan**: a chunk marked provisional after its
   tile's arrival event fired would stay provisional forever (arrival
   never repeats). FIX: `presenceCheck` SPI — markServed self-swaps when
   the tile is already resident. Wired in TerraChunkGenerator via
   `areTilesLoaded`.
2. **computeIfAbsent().add() window**: the arrival drain could remove a
   tile's list between list-creation and add, orphaning the chunk
   (observed leftover=1). FIX: atomic `byTile.compute()` — the drain
   either sees the chunk or the race guard fires.

Duplicate swap submissions under race are absorbed by the RefreshEngine's
by-key dedup (its design rule 1) — verified tolerable, not a bug.

---

## Update: the REAL Overture tier (user directive)

Run 9 finding: appetizer starved because MSFT (40s) and VIDA (143s) are
NOT fast on a truly cold first visit — optimized live OSM (20s) won the
race. Fix per user decision (option 1 confirmed): give the appetizer a
source that is fast cold.

### Renamed
`OvertureIndex` -> **`MicrosoftIndex`** (it only ever read Microsoft
quadkey files; the name was a lie). All 9 referencing files updated,
logger renamed Terra++Microsoft. Log lines "Overture retry success" etc.
now say Microsoft.

### New: `OvertureOsmIndex` — the genuine Overture
- Reads Overture Maps' per-theme **PMTiles** (release 2026-08-19.0) from
  S3 via HTTP range requests. GZIP internal compression = zero new
  dependencies (java.util.zip).
- Hand-written PMTiles v3 reader (header/root-dir/leaf-dir/Hilbert tile
  ids) + minimal MVT (protobuf subset) parser -> OsmStructure.
- Buildings + roads + rails at z14 (~2.4 km tiles). Negative osmId space
  marks appetizer provenance. Water/base skipped (water indexes own it).
- mem + disk cache (`terra_cache/overture_osm_2026-08-19_0/`), pending
  dedup, all fetches bounded 8s, failures retryable never poisoned.

### Appetizer hierarchy now (user-specified)
**Overture(OSM) > VIDA(Google) > Microsoft** while OSM pending;
main-course swap to live OSM unchanged (option 1: temporary until OSM
self-sustaining).

### Wiring
- appetizer branch: OvertureOsmIndex tier 1 (1.5s bounded)
- warmDestination: prefetch() fires with the other 5 sources

### Live-tested from sandbox (real class, real S3, cold)
**4,100 Secunderabad features in 3.4s**: 3,259 buildings (incl. 3
train_station, named "Regional passport office"), 703 road segments
(primary/secondary/tertiary/residential, "Sebastian Road"), 57 rail,
81 footways. Geometry bounds verified. vs run 9 cold: MSFT 40s,
VIDA 143s, live OSM 20s -> Overture 3.4s wins the appetizer race.

---

## RUN-11 PREP: Partial-Serving Rewrite (2026-09-01)

Run 10 post-mortem: appetizer starved because (a) spawn warmup lands at
lat0,lon0 (Gulf of Guinea — expected, game spawns there on cold start),
so Overture only got the ~9s teleport head start; (b) at that moment all
three cooks missed their budgets. Root causes found IN THE CODE:

1. **All-or-nothing serving** — `getStructuresForArea` returned null if
   ANY tile (including never-used BASE theme) missed. One late
   transportation tile threw away resident buildings.
2. **`disconnect()` after every range request** — killed HTTP keep-alive;
   every request paid a fresh ~600-900ms TLS handshake to us-west-2.
3. **Serial tile loop** — themes/tiles fetched one after another inside
   the budget.
4. **±0.01° prefetch box** (~1.1km) could straddle a z14 tile border
   (~2.2km/tile) and leave the player's actual tile cold.

### Fixes applied (all in OvertureOsmIndex.java)
| # | Fix | Effect (live-measured) |
|---|-----|------------------------|
| 1 | APPETIZER_THEMES = {BUILDINGS, TRANSPORTATION}; BASE dropped; parallel dispatch → bounded wait → plate whatever is resident ("partial serve") | starved call returns clean null; healed area serves in 1ms later |
| 2 | keep-alive: stream close instead of disconnect(); error body drained | cold 4-tile fetch 1277ms (was ~3s+ serial) |
| 3 | all missing tiles submitted to dlPool concurrently, Future.get with remaining budget | themes overlap instead of stacking |
| 4 | prefetch = 3×3 z14 neighborhood × 2 themes (18 tiles), logs dispatch line | post-prefetch fetch 2ms |

### New self-check log lines (analyzer patterns added)
```
OVERTURE area [lat,lon]: N structures from H/T tiles in Xms (partial serve)?
OVERTURE area [lat,lon]: 0/T tiles resident after Xms — nothing to serve
OVERTURE prefetch [lat,lon]: N tiles dispatched (3x3 neighborhood, 2 themes)
```
Analyzer also knows: `COLD WARMUP [~0..3,-6..0]` at boot = world spawn,
never a wrong-location bug.

### Live test (real AWS, Secunderabad + Charminar + Chennai): 11/11 PASS
- cold 1277ms / warm 4ms / post-prefetch 2ms
- 100ms starvation budget honored (101ms, null), background heals (82 structs, 1ms)
- negative-id provenance intact; buildings + transport both present

### Expected in run 11
- `OVERTURE prefetch` at teleport, `OVERTURE area ... in <2500ms` on any
  BLOCKING TIMEOUT, then `APPETIZER served` — or a `0/T resident` line
  that tells us exactly how many tiles were still cooking.

---

## RUN-12 POST-MORTEM: The Appetizer Branch Was Dead Code (2026-09-01)

Run 12 proved fixes 2-4 work (`OVERTURE prefetch ... 18 tiles dispatched`
at teleport; `OVERTURE tile buildings/11765/7386: 15265 features
(cache=26 tiles)` within 7s) — yet ZERO `OVERTURE area` / `APPETIZER
served` lines despite two BLOCKING TIMEOUTs. Tiles were resident; the
appetizer never looked at them.

**Root cause: `fetchStructuresForChunk` — the method containing the
entire appetizer branch — has NO CALLERS.** It was orphaned in the
RefreshEngine migration (USE_REFRESH_ENGINE=true routes chunk decoration
through EngineWorker.peekData -> `fetchStructuresCachedOnly`, which just
queued a retry and returned null on a miss). Runs 10/11/12 could never
serve an appetizer no matter the budget: the restaurant moved buildings
and the appetizer kitchen stayed in the old one.

### Fix (RUN-12): appetizer wired into fetchStructuresCachedOnly
- Both miss paths (timeEnabled: AsyncStructureManager miss; non-time:
  areTilesLoaded false) now try
  `OvertureOsmIndex.getStructuresForArea(..., 0)` — **0ms budget**:
  server thread, so plate ONLY resident tiles, never wait. Prefetch
  (3x3 at teleport) is what makes residency likely by the time chunks
  decorate.
- timeEnabled path applies the same `isVisibleAt` year filter as the
  cached path (no modern appetizers at 3000 BCE).
- Serve -> `ProvisionalServing.markServed` -> swap on OSM arrival
  (unchanged machinery).
- `OVERTURE area ... 0/T resident` miss line rate-limited to first 10
  when budget==0 (engine may poll every tick).

### Expected in run 13
- After `/tpll` to a cold area: `OVERTURE prefetch ... 18 tiles`, then
  within seconds `OVERTURE area [...]: N structures from H/T tiles in
  0ms` + `APPETIZER served chunk [...]`, then `MAIN COURSE arrived` +
  swaps when OSM lands.

### Still open after run 12
- MSFT cache vanished a 4th consecutive session (re-download at
  01:59:50). Folder check still pending:
  `terra_cache\msft_buildings\123301331.geojsonl`.
- CONNECTOR 850869297 still `16b -> 0b` (Line 2 layer=4 not in local
  list at compute time; run-10 fix prevents caching the bad answer but
  not the first bad placement).

---

## 🎉 RUN-13: APPETIZER FIRED IN-GAME — 117 served / 117 swapped / 0 orphans (2026-09-01)

First `APPETIZER served` lines in game history:
```
[17:09:07.501] APPETIZER served chunk [629378,-126959] (7 fast-source features; OSM pending)
[17:09:07.501] MAIN COURSE arrived for tile [17.43,78.50]: 1 chunks to swap (lifetime: 1 served, 1 swapped)
... -> (lifetime: 117 served, 117 swapped)
```
- PLACEMENT_TEST warnings show negative-id features (osm:-447233...
  type=ROAD) = appetizer content genuinely reached block placement.
- Year filter verified live: before the timejump (year -3000) polls
  returned structures but ZERO serves (correctly hidden); serves began
  the moment the jump to 2026 landed.
- Prefetch verified: 18 tiles at teleport, 26 resident within ~9s,
  every 0ms poll answered in 0-2ms on the server thread.

### Two flaws found in run 13, fixed:
1. **Empty-success log spam** — `0 structures from 2/2 tiles` printed
   ~150x at ocean spawn (rate-limit only covered the starved path).
   Fix: empty result on a 0ms poll logs first 10 only.
2. **Serve-after-arrival churn** — every serve was instantly swapped
   (same ms). OSM tile was already resident; the appetizer branch fired
   only because the async OHM->OSM->VIDA->MSFT merge wasn't done yet.
   Each serve = placement + instant invalidate + refresh = double work
   x117, feeding the 1845-tick debt. Fix: timeEnabled appetizer now
   requires `!areTilesLoaded(...)` (pure map lookup) — serve only when
   the main course is genuinely absent, not merely being plated.

### Expected in run 14
- Ocean spawn: at most 10 `OVERTURE area ... empty area` lines, then
  silence.
- Cold teleport (fresh city, year with buildings): serves ONLY in the
  window before the OSM tile lands (~5-20s), each swapped once on
  arrival. Lifetime counters should show served ~= swapped with serves
  spread over the starvation window, not a same-millisecond burst.
- Ticket debt from the appetizer path should drop to near zero.

### Still open
- MSFT cache vanished 5th consecutive session (17:08:20 re-download).
  Folder check STILL pending: terra_cache\msft_buildings\123301331.geojsonl
- Heavy engine ticks (11.7s / 6.2s / 5.4s in run 13) — reverted-pile
  known issue, user chose to leave as-is.

---

## FAST-LOAD / FAST-TELEPORT FIX (post run 13, 2026-09-01)

User clarified the real complaint: not placement — **slow world load and
slow /tpll**. Run 13 audit: terrain math for the destination was done in
1 SECOND (SURFACE logs at 17:06:09), but the teleport took 126s and
timed out anyway, because applyBiomeDecoration blocked up to 8s per
source per chunk waiting for downloads — destination chunks could not
reach FULL until Overpass answered. Teleport time == Overpass time.

Live supplier measurements (sandbox, during diagnosis):
- ALL 5 Overpass mirrors down simultaneously (dead / 502 / 504 / 500@47s)
- api.openstreetmap.org /api/0.6/map.json: same bbox in **1.5s**, 2.4MB,
  full tags (1172 buildings / 376 highways / 85 railways)

### Fix A — never gate generation on downloads
- New GEN_STRUCT_WAIT_MS=1500 replaces 8000ms in applyBiomeDecoration
  for ALL five blocking calls (historical, OSM, VIDA, MSFT x2).
- On a miss with nothing fetched: appetizer serves resident Overture
  data (year-filtered) + markServed; async retry unchanged; the proven
  117/117 swap upgrades the chunk when the main course lands.
- Expected: chunks reach FULL in ~1-2s -> /tpll completes in ~5-10s
  regardless of network health.

### Fix B — official OSM API as primary structure source
- New fetchViaOsmApi() in OsmStructureIndex: api.openstreetmap.org
  /api/0.6/map.json tried FIRST in fetchTile; Overpass fleet is now the
  fallback. 300ms polite spacing (global gate), 3-strikes -> 2min
  API cooldown so dead-API scenarios fall straight through to Overpass.
- convertApiToOutGeom(): API raw nodes+ways+relations -> Overpass
  `out geom` shape (way geometry resolved from node refs, tagged nodes
  kept for CONTEXT ENRICH, relation member geometry assembled), so
  parseResponse/saveDisk/enrichment/disk-cache format are untouched.
- Converter live-verified against real API data: 2060 elements, 0
  broken geometries, POIs + multipolygons intact, way 635428057 OK.
- Log line: `OSM STRUCT [lat,lon]: N features (api.osm.org)` —
  analyzer's existing pattern still matches.
- Batches (fetchMissingAsBatches) still Overpass: a 5x6-tile block
  exceeds the API's 50k-node cap in dense cities. Single-tile path
  (the one that decides teleport latency) is API-first.

### Expected in run 14
- /tpll: "Destination ready — teleported" in seconds (not "still
  generating; teleported anyway" after 126s).
- `OSM STRUCT ... (api.osm.org)` lines ~1-3s after request.
- BLOCKING TIMEOUT lines: near zero (nothing waits 4s anymore).
- If chunks generate bare during the first seconds, APPETIZER served
  lines appear and swaps land shortly after.

Compile: exactly 3128 baseline, 0 non-baseline.

---

## ORDER→APPETIZER LATENCY FIX (2026-09-02)

User: "reduce the time between taking the order and giving appetizer."
Flaw: the generation-path appetizer polled at 0ms budget, but /tpll's
prefetch puts Overture tiles in flight ~2-3s ahead — a 0ms poll missed
tiles that were seconds from landing, so first-generation chunks went
bare and had to wait for the swap. The 0ms rule only exists for the
SERVER thread; decoration runs on Worker-Main async threads where the
old code blocked 8s.

Fix: new GEN_APPETIZER_WAIT_MS=1500 used by the generation-path
appetizer call (server-thread engine polls stay 0ms). Combined with
parallel dispatch + partial serve inside getStructuresForArea, the
appetizer now catches the in-flight prefetch.

Expected order→appetizer timeline (cold city, modern year):
  T+0s /tpll → prefetch 18 tiles;  T+~1s chunks decorate → appetizer
  waits ≤1.5s → tiles land → APPETIZER served IN the first generation
  pass. First buildings visible ~2-4s after the order. Swap upgrades
  to main course when OSM (api.osm.org, ~1-3s) merges.

Compile: 3128 baseline, 0 non-baseline.

---

## LOADING-SCREEN FIX (2026-09-02)

User: "reduce the time of loading screen." Run 13: "Preparing spawn
area" took **27.6s** (world create ~65s total). Spawn is the projection
origin = open Atlantic at -4835m, yet all 441 spawn chunks ran the full
decoration pipeline: OSM/OHM/water/WorldCover lookups + async retries
for chunks that can never contain anything, at any year.

### Fixes
1. **OCEAN FASTPATH** in applyBiomeDecoration: if all 4 corners +
   centre of the chunk bbox are below **-30m** (elevation read from the
   cache the terrain pass just filled — zero network), mark decorated +
   current-year and return. No structure fetch, no water, no
   vegetation, no retries. Log: `OCEAN FASTPATH [cx,cz]` (first 10).
   -30m threshold spares all coastal/shallow/below-sea-level land
   (Netherlands -6m safe).
2. **prefetchForChunk skips deep ocean** — spawn chunks no longer fire
   6-source warmups at the Atlantic.
3. (Earlier this session) SIT→ORDER fix in TpllCommand: prefetch now
   dispatched before estimateSurfaceY's 1.5s classify wait.

### Expected in run 14
- "Preparing spawn area" noticeably faster (terrain gen remains; all
  per-chunk data-source work at spawn is gone) + far fewer boot-time
  OHM/OSM-water `[2.xx,-5.xx]: 0 features` lines.
- `OCEAN FASTPATH` lines at spawn; NONE near Hyderabad (538m).
- Analyzer: `ocean_fastpath` pattern added.

Compile: 3128 baseline, 0 non-baseline. TpllCommand/analyzer verified.

---

## RUN-14 RESULTS + FINAL TWO BOTTLENECKS FIXED (2026-09-02)

### Run-14 verdicts (all this week's fixes verified live)
- **api.osm.org: 86s -> 7s.** `/tpll` 01:13:30 -> `OSM STRUCT: 1654
  features (api.osm.org)` 01:13:37. Multiple tiles (991/1535/1193)
  all via the API. BLOCKING TIMEOUT now 750ms (was 4000).
- **SIT->ORDER:** prefetch dispatched BEFORE Y-estimate (01:13:29.375,
  a second ahead of the chat line).
- **ORDER->APPETIZER:** partial serves inside the first generation
  pass at T+5s; post-timejump serves 22-25 structures/chunk from 2/2
  tiles in 0-2ms. Deferred swaps (chunks not fully loaded at arrival)
  heal via DEFERRED_BOUNDARY as designed.
- **OCEAN FASTPATH fires** (10 lines) but loading screen unchanged
  (27.2s vs 27.6s) -> the cost was never data sources; it is vanilla's
  start-region rule: 441 spawn chunks of 4064-tall Atlantic terrain
  must generate before the screen closes.
- **Teleport still hit the 90s cap** despite data ready at T+7s: the
  FULL-status wait is a formality the player needlessly sat through.
- MSFT cache: 6th consecutive vanish (01:14:02 re-download).

### Fix 1 — MixinSpawnPrep (loading screen)
First real Terra++ mixin (terraplusplus.mixins.json was empty).
Targets MinecraftServer#prepareLevels: START ticket radius 11 -> 1 and
wait target 441 -> 1 (matched pair so the vanilla `!= target` loop
terminates). Spawn is open Atlantic and the player always /tplls away;
preparing 1 chunk instead of 441 should cut the 27s to ~1-3s.
require=0: on mapping mismatch the game boots unchanged.
Evidence next run: "Time elapsed: ___ ms" line.

### Fix 2 — teleport cap 90s -> 12s
PENDING_TELEPORT_TIMEOUT_MS 90_000 -> 12_000. Terrain exists within
seconds; structures/appetizers arrive via swap machinery regardless of
where the player stands. Past ~12s the wait helps nothing.

Compile: 3138 total = 3128 old baseline + 10 (MixinSpawnPrep's
MC/SpongePowered imports, absent in sandbox). NEW BASELINE: 3138.

---

## FOUR-TIMER SPEED PASS (2026-09-02, post run 14)

Task: cut loading screen, teleport, terrain gen, and timejump times.

1. **Log-spam fix (loading screen/terrain):** TerraDensityFunctions
   debug gates (elevDebug/bathyDebug/belowSeaDebug/classifyDebug) were
   per-instance; 11 universes => ~900 INFO lines in the hot terrain
   path during boot. Now static (global): same diagnostics, 1/11th
   the console I/O.
2. **ELEV PREFETCH (teleport+terrain, the big one):** new
   TerraDensityFunctions.prefetchArea(bbox) warms every height tile
   (terrain zoom 15 + bathy zoom 8) covering the box in parallel on
   dlPool, dedup vs mem/disk/pending, self-cleaning futures, 128-tile
   cap. Wired as the **7th source in warmDestination** — elevation was
   the one source never prefetched, yet every column blocks on it (3s
   per missing tile, serial). Verified: Secunderabad warmup box = 7
   tiles (~0.3MB), real S3 tile fetch 0.38s => whole area warm in ~1s.
   Log: `ELEV PREFETCH [...]: N height tiles dispatched in parallel`.
3. **Timejump burst drain:** RefreshEngine.burst() — after a jump
   queues work, tick budget rises 8ms -> 40ms for 60s, then back to
   polite. Called from the scheduled-refresh path when va>0.
   Log: `REFRESH_ENGINE burst: budget 40ms/tick for 60s`.
4. (Already shipped, untested: MixinSpawnPrep 441->1 spawn chunks +
   12s teleport cap — run 15 verifies via "Time elapsed".)

Analyzer: elev_prefetch + engine_burst patterns added.
Compile: 3138 = baseline exactly, 0 new errors.

### Run-15 scorecard
- "Time elapsed" (loading): 27,228ms -> target ~1-4s (mixin) plus less
  log I/O; if >20s the mixin didn't apply (analyzer flags).
- /tpll: expect `ELEV PREFETCH ... 7 height tiles` right after COLD
  WARMUP; SURFACE lines should follow within ~1-2s; teleport completes
  <=12s worst case.
- Timejump: `REFRESH_ENGINE burst` line; done=N climbing ~5x faster in
  the first minute vs run 14.

---

## COLD-START ROOT-CAUSE FIX: RELATIVE CACHE PATHS (2026-09-02)

User refined the speed task: "during cold starts." Rereading run 14
with cold eyes: EVERYTHING was cold again — OSM structs, VIDA (full
15787-feature ranged fetch), WorldCover headers, MSFT (6th 240MB
re-download) — despite run 13 visiting the same city and 14-30 day
TTLs. The MSFT mystery was never MSFT-specific.

**Root cause candidate: every cache used
`Paths.get("terra_cache", ...)` — RELATIVE, resolved against the JVM
working directory.** If the launcher's cwd differs from the game dir
(or varies between launches), each session writes a fresh cache
somewhere else and finds nothing from last time. Writes work
in-session (consistent cwd), reads next session miss — exactly the
observed symptom, for every index at once.

### Fix
- New `util/TerraCachePaths`: anchors terra_cache at Forge's
  `FMLPaths.GAMEDIR` (fallback user.dir), logs one line at first use:
  `CACHE ROOT: <absolute path> (via FMLPaths.GAMEDIR, cwd was <cwd>)`
- All 11 cache dirs migrated (worldcover, ohm_structures_v2, wikidata,
  tiles, overture_buildings, msft_buildings, osm_structures_v4,
  overture_osm_*, vida_buildings, ohm_water, osm_water).
- Analyzer: `cache_root` pattern. If the logged path ever differs
  between sessions, the mystery is definitively closed.

### Why this is THE cold-start fix
Prefetch/appetizer/API make cold starts fast; this makes most of them
NOT COLD AT ALL: session 2+ becomes disk-warm for every previously
visited area (30d TTLs finally doing their job). Expected run-15+:
- MSFT: "from disk" instead of "Downloading ... quadkey"
- VIDA: no cold-start ranged fetch for Hyderabad
- OSM STRUCT: disk hits, no api.osm.org calls for visited tiles
- Loading screen/teleport/terrain/timejump all benefit because the
  network leaves the critical path entirely on revisits.

Also asked user to check `CACHE ROOT` path in next log vs where they
looked for msft_buildings — likely explains 8 runs of folder mystery.

Compile: 3141 = 3138 + 3 (TerraCachePaths log4j/FML baseline).
NEW BASELINE: 3141.

---

## API SPEED PASS + OVERTURE DEMOTION (2026-09-02)

User: "Implement it. If osm is fast there is no need for overture."

### 1. HttpKeepAlive (all styles) — 16 hang-up sites fixed
New util/HttpKeepAlive.release(conn): drains+closes streams so the JVM
pools the socket, instead of disconnect() killing it (fresh TLS
handshake ~600-900ms from IN per request). All 16 disconnect() sites
across 9 downloaders migrated (elevation, WorldCover, MSFT x2, VIDA,
OHM struct, WikiData x3, OSM struct x3, OHM water, OSM water x2).
Includes 256KB max-drain guard (bigger leftovers: disconnect is
cheaper) + error-stream drain (unread error bodies poison pooled
sockets). Null/404 safety live-tested; socket-reuse mechanism verified
(timing gain invisible from 1ms-RTT sandbox — but this exact fix
measured 2-3x on OvertureOsmIndex in-game from India).

### 2. BATCH via api.osm.org — whole cold-start area in ONE GET
fetchBatch now tries fetchViaOsmApi for the entire block (max 5x6
tiles = 0.003 deg², ~80x under the API's 0.25 cap) before falling back
to Overpass. Converter output is Overpass-shaped so the existing
per-tile split/enrich/disk-save pipeline runs unchanged. 400 on the
50k-node cap (very dense blocks) falls through to Overpass unchanged.
Log: `BATCH via api.osm.org [a..b,c..d]: one request`.
Expected: warmup area 9 round trips -> 1; cold OSM ~7s -> ~2-3s.

### 3. OVERTURE DEMOTION — appetizer becomes emergency parachute
Per user directive. NOT deleted (sole cover when everything is down);
instead warmDestination only prefetches Overture when
OsmStructureIndex.osmStruggling() — evidence-based: API in cooldown,
any full-chain tile failure in last 60s, or last successful fetch
>3s. Healthy OSM => zero Overture bandwidth, appetizer dormant.
Struggling OSM => `WARMUP: OSM struggling — Overture appetizer armed`
and everything works as before. Serve paths untouched (self-arming:
a miss at the 1.5s budget still dispatches Overture on demand).

Analyzer: batch_via_api + overture_armed patterns.
Compile: 3141 = baseline exactly (HttpKeepAlive = pure JDK, 0 errors).

### Run-15/16 expectations
- `BATCH via api.osm.org` on cold teleports; OSM STRUCT lines ~2-3s
  after /tpll.
- NO `OVERTURE prefetch` lines while OSM is healthy; if you ever see
  `Overture appetizer armed`, that IS the parachute deploying.
- With CACHE ROOT fix: revisits should show none of the above at all.

---

## RUN-16 VERDICT + TWO FIXES (2026-09-03)

### Wins verified live
- **CACHE ROOT landed**: `C:\...\instances\old\minecraft\terra_cache
  (via FMLPaths.GAMEDIR, cwd was <same path>)` — and cwd == GAMEDIR,
  so RELATIVE PATHS WERE NEVER THE CULPRIT. Caches always wrote to the
  folder the user checked. Mystery narrowed, not closed.
- **ELEV PREFETCH fired** (7 tiles at /tpll, then 1 — dedup working);
  SURFACE math ~0.5s later.
- **BATCH via api.osm.org [1742..1744,7848..7848]: one request** —
  phase-2 batching works (59ms later: 3 tiles split+cached).
- **REFRESH_ENGINE burst: 40ms/tick for 60s** — post-jump drain
  done=9→285 in ~56s; ticks routinely 40-47ms clearing 8-12 entries
  (was 8ms/1-2). Timejump audit complete=238/240 within ~60s.
- **Overture demotion**: no OVERTURE prefetch at warmup (OSM healthy);
  appetizer self-armed on demand later (serve-path dispatch), correct.
- api.osm.org: 1654 features T+7.5s; second area 3651 feats.

### Failures + fixes
1. **Loading screen still 26.8s — MixinSpawnPrep NEVER APPLIED.** The
   config file terraplusplus.mixins.json existed but was never
   registered: not in build.gradle's mixin{} block, not in the jar
   manifest MixinConfigs. CubicChunks' own configs are registered in
   both places; ours was missing. FIXED: added
   `config "terraplusplus.mixins.json"` + refmap to mixin{} and
   appended to MixinConfigs manifest attribute. Requires rebuild;
   evidence next run: "Time elapsed" ~1-4s (else analyzer flags).
2. **Every session still fully cold at the CORRECT path** (MSFT
   re-download #7, VIDA cold fetch, OSM re-fetch). Since paths were
   never wrong, either something wipes terra_cache between sessions,
   or per-index read paths fail at boot. Added to TerraCachePaths:
   - `CACHE MARKER: SURVIVOR/FRESH` — session marker file; SURVIVOR
     next boot proves disk persists (=> read bugs); FRESH every boot
     proves external wipe (launcher/AV).
   - `CACHE CENSUS: <subdir> = N files, K KB` per cache dir at boot —
     shows exactly which caches persist.
   No manual folder check ever needed again.

### Also noted (no action yet)
- Teleport fell back at 12s cap ("still generating; teleported
  anyway") — acceptable UX now; revisit after cache mystery closes.
- kumi.systems answered 200 for water twice — first success in weeks
  (rotation still useful).

Compile: 3141 = baseline. Analyzer: cache_survivor/fresh/census.

---

## RUN-16 REGRESSION FIX: DELETED ROADS/BUILDINGS (2026-09-03)

User report: some chunks lost roads/buildings during run 16.

Root cause (log-proven): post-jump refresh = wipe-then-replace per
chunk. The new 40ms burst drain made the engine FASTER than the cold
kitchen — refreshes ran while the OHM→OSM→VIDA→MSFT merge was still
combining (MSFT arrived 00:56:41+, VIDA 00:57:18, mid-drain). For a
chunk whose peekData hit the appetizer branch (OSM tile momentarily
non-resident / merge incomplete), the pipeline WIPED a fully decorated
chunk and re-placed only a handful of Overture silhouettes; the
corrective swap was deferred (chunk borderline-loaded). Run 14 never
showed this because the 8ms drain was slower than every source.

Fix: appetizer can no longer be used as refresh data for chunks that
already have content — gate added:
    getChunkPresence(ch) < PRESENCE_DECORATED
in fetchStructuresCachedOnly's appetizer branch. Decorated chunks now
park in NEEDS_DATA until the COMPLETE merge exists (no data -> no
wipe -> nothing can be lost). Appetizer still serves empty plates
(fresh generation) exactly as before.

Also: existing worlds with already-missing content self-heal — those
chunks are marked stale/deferred, and once the complete merge arrives
the normal refresh re-places the full data.

Compile: 3141 = baseline.

Run-17 watch: post-jump, NO chunk should lose content; APPETIZER lines
should appear only for freshly generated chunks (presence < decorated).

---

## RUN-17 BOOT CRASH FIX (2026-09-03)

Crash: CubicWorldGen's MixinNoiseBasedChunkGenerator "No refMap
loaded" -> boot abort. MY fault: the run-16 registration added
`add sourceSets.main, "terraplusplus.refmap.json"` — but the mixin
gradle plugin keeps ONE refmap per source set (last `add` wins).
Log-proof of the mechanism: every earlier run warned only about
cubicchunks.refmap.json missing (displaced by the cubicworldgen add,
harmless because CC mixins never needed remapped injections there);
the crash run warned about BOTH cubicchunks AND cubicworldgen —
because my third add displaced cubicworldgen's refmap, whose
buildSurface @Inject NEEDS remapping. required=false didn't save it:
that flag governs OUR config, not CWG's.

Fix:
- build.gradle: removed the third `add` (config registration stays).
- terraplusplus.mixins.json: `"refmap": "cubicworldgen.refmap.json"`
  — the annotation processor writes ALL sourceSets.main mixins into
  the single refmap, so MixinSpawnPrep's entries land there too.
Needs rebuild. Expected next run: boots normally, cubicworldgen
warning gone again, and (finally) Time elapsed ~1-4s if MixinSpawnPrep
applies — watch for absence of the 441-chunk 22%..98% ladder.
