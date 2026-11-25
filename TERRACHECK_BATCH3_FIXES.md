# TerraCheck batch 3 — "solve VIDA late and Microsoft" (implemented 2026-11-18)

Owner directive after batch 2: solve the VIDA-late problem and
the Microsoft problem. This batch changes SOURCE CODE (not only
the test bench). Diagnosis below is proven by reading the code;
the fixes are proven or killed by the next owner's log.

## Diagnosis — three faults chained together

### Fault 1: the refill road was vetoed by Microsoft (the big one)

`AsyncStructureManager.processRetry` completes a tile only when
EVERY tier is terminal: OHM → OSM → VIDA → Microsoft. Microsoft's
answer never comes (fault 3), so `overtureDone` never becomes
true, the tile defers forever, and the completion block — the
only place that increments `totalSuccesses`, marks the tile
completed, and feeds `StructurePlacementQueue` — never runs.

That fully explains the batch-2 "contradiction": `OSM retry
success` is only the stage-2 log line; `successes=0` and
`completedTiles=0` were honest counts of full completions, and
there were none. There is exactly ONE `AsyncStructureManager`
in the code (constructed in `TerraChunkGenerator.getAsyncManager()`
and handed to `StructureTickHandler.initialize(...)`), so
`/terracheck` was reading the right counters all along. Batch 2's
hypothesis (a) "two different managers" is dead; hypothesis (b)
is refined: the counters count only full completions, and
completion was vetoed by the lowest tier.

Consequence: chunks born before their data arrived were NEVER
topped up. Late VIDA only ever appeared in chunks generated
after the shard landed — exactly what both owner logs show.

### Fault 2: VIDA starts late by construction

- `TerraChunkGenerator.warmDestination(...)` (fired by `/tpll`
  and by first-terrain-generation prefetch) started OSM,
  Microsoft, water, worldcover and elevation — but never VIDA.
- VIDA's own fetch carries a deliberate 5 s `COLD_START_DELAY_MS`,
  then the ranged shard fetch takes tens of seconds.
- Generation waits at most `GEN_STRUCT_WAIT_MS` (1.5 s), and
  `SourceCooperativeIndex.getBuildingsForChunkBlocking` actually
  returns null immediately without waiting. First chunks are
  therefore always born OSM-only — survivable IF the refill road
  worked, fatal while fault 1 stood.

### Fault 3: Microsoft download — black box, memory bomb, silent death

Verified live from the sandbox on 2026-11-18: the current
`dataset-links.csv` gives quadkey `123301331` (Hyderabad) as a
53.3 MB `.csv.gz` whose content IS GeoJSON-Lines (properties
`height`/`confidence` use −1.0 for "unknown"). So the URL and
format are right. The download loop in
`MicrosoftIndex.fetchMicrosoftQuadkey` then:

- appended EVERY line (~628k) to one `StringBuilder`, copied it
  with `toString()`, and wrote it — hundreds of MB in the heap at
  once inside a Minecraft process;
- logged nothing between "Downloading ..." and completion;
- caught only `Exception` — a memory `Error` would vanish into
  the async future with NO log line at all.

That matches both owner sessions exactly: download starts,
never finishes, no error line. (Whether the final blow on the
owner's machine was GC thrash or an OutOfMemoryError cannot be
told from the old log — which is itself the bug. Batch 3 makes
the download report its own state either way.)

## The fixes (5 files)

1. **`MicrosoftIndex.java`**
   - Download streams line-by-line to a `.geojsonl.tmp` file and
     renames on success. Memory stays flat; an interrupted
     download cannot poison the disk cache.
   - Progress: `[TerraStruct] MICROSOFT downloading quadkey …:
     N lines read, Ts elapsed` every 100k lines, plus a live
     `DownloadProgress` published every 1024 lines.
   - `catch (Throwable)`: any failure now logs
     `[TerraStruct] MICROSOFT FAILED quadkey …: <ErrorName> <msg>`.
     Fetch errors in `fetchTile` likewise promoted from
     debug/Exception-only to a visible warn line.
   - New `getDownloadState()` + `isIndexLoaded()` for the bench.
   - −1.0 height/confidence sentinels clamped (height→0 =
     unknown, confidence→0.85 default).

2. **`SourceCooperativeIndex.java`**
   - New `prefetch(lat, lon)`: starts the region shard fetch
     immediately (delay 0), respecting cache/retry-cooldown/
     already-pending. Incidental chunk queries keep the 5 s
     cold-start delay as designed.
   - New `getPendingCount()` for the bench.

3. **`TerraChunkGenerator.java`**
   - `warmDestination` now fires VIDA prefetch too (guarded by
     `worldConfig.vidaBuildings`). VIDA downloads in parallel
     with terrain generation instead of after first decoration.

4. **`AsyncStructureManager.java`** — the refill-road root fix
   - Microsoft tier: after 6 failed tries (spread over minutes
     of backoff) the tile completes WITHOUT Microsoft, counted
     in `tilesWithoutMsft`, logged as
     `… Microsoft not ready after 6 tries — completing WITHOUT
     Microsoft (OSM/VIDA still placed)`. New chunks still query
     the Microsoft cache at generation time, so a late-arriving
     download is not wasted.
   - VIDA tier: same pattern after 12 tries
     (`tilesWithoutVida`), completing OSM-only rather than never.
   - New getters `getTilesCompletedWithoutMsft/Vida()`.

5. **All-sources testing (owner directive: "let batch 3 test
   all sources")** — `TerraCheckCommand` gains five sections,
   and `TerraChunkGenerator` gains four public accessors
   (`getWorldCoverPublic`, `getRlePublic`, `getDfPublic`,
   `getOhmIndexPublic`, same pattern as the existing
   `getStructureIndexPublic`):
   - **ESA WorldCover**: class sampled at chunk centre + 4
     corners (generation's own non-blocking call), tile-loaded
     flag, queries/hits/S3-fetches counters. Verdict: READY /
     NOT LOADED YET / SWITCHED OFF. A city chunk should read
     mostly BUILT_UP.
   - **Water**: OSM+OHM water engine counters (queries, hits,
     cached tiles, enabled flags). Verdict: DATA ARRIVED /
     ASKED, NOTHING CACHED / NEVER ASKED / SWITCHED OFF.
     (Placement in the world stays a log/eyes question.)
   - **Overture appetizer**: real `getStructuresForArea` with a
     1.5 s bounded wait — the exact call generation makes.
     Verdict: SERVING / SERVING, AREA EMPTY / NOT SERVING,
     judged together with the OVERTURE log lines (healthy OSM
     keeps this backup dormant by design).
   - **Elevation**: centre elevation + tile/elev cache sizes.
     Hyderabad must read roughly 500–600 m.
   - **OHM**: while timejump is disabled it must print
     `OFF BY SWITCH … idle BY DESIGN` — an honest "not in
     service", not a fake pass. Real OHM test waits for
     timejump re-enabling.
   - Roads/rails/bridges remain covered by the OSM byType
     count already in the bench.

6. **`TerraCheckCommand.java`** (batch-3 readouts; owner still
   types only `/terracheck`)
   - `MICROSOFT download: indexLoaded=… state=…` + verdicts:
     DOWNLOADING NOW (lines must rise between checks; frozen =
     stuck) / NOT DOWNLOADING, NOT CACHED (search MICROSOFT
     FAILED) / previous verdicts unchanged.
   - `VIDA state: cachedRegions=… pendingRegions=…` (READY /
     DOWNLOADING / NOT ASKED YET).
   - REFILL: `completedWithoutMsft/Vida` counters + plain
     VERDICT (WORKS / STILL WAITING with a 5-minute bound /
     NOTHING QUEUED).

Static check (sandbox cannot build Forge): brace/paren/bracket
balance clean on all 5 files; javac-11 parse shows only
pre-existing Java-17-syntax and missing-classpath errors, none
in batch-3 code (TerraCheckCommand parses clean). Real compile
is the owner's `gradle jarJar`, as always.

## Predictions — written BEFORE the owner's batch-3 run

Fresh world, owner standing at spawn:

1. The log shows `VIDA prefetch …: shard fetch starting now
   (no cold-start delay)` at world start / first terrain gen —
   BEFORE the first `[TerraStruct] PLACED` lines.
2. Microsoft progress lines appear and RISE:
   `MICROSOFT downloading quadkey 123301331: 100000 lines …`,
   then a completion line `MSFT quadkey 123301331: N retained,
   … lines parsed (Ts)` — OR an explicit
   `MICROSOFT FAILED …` line naming the error. Either outcome
   is a successful test: the black box is gone.
3. Within ~2–5 minutes at spawn, `[TerraStruct] AsyncStruct
   tile […] osm=… vida=… microsoft=… queued=…` lines appear and
   `/terracheck` prints `REFILL VERDICT: WORKS` with
   successes > 0. `completedWithoutMsft` may be > 0 — honest:
   refill no longer waits for Microsoft.
4. `VIDA state` shows pendingRegions > 0 early, then the VIDA
   section shows buildingsHere > 0 once the shard lands
   (earlier than batch 1/2's ~30–60 s).
5. If the Microsoft download completes, `MICROSOFT counters`
   shows cacheTiles > 0 and msftHits > 0. buildingsHere for a
   specific chunk may still be 0 (OSM/VIDA outrank it and dedup
   removes overlaps) — cacheTiles/msftHits are the proof, not
   any single chunk.
6. ESA at Secunderabad/Charminar: classes mostly BUILT_UP,
   verdict READY (possibly NOT LOADED YET on the very first
   check, READY by the second). If it stays UNKNOWN across all
   checks, ESA S3 joins the problem list — that would be a new
   finding, not a prediction failure.
7. Elevation at both places reads roughly 500–600 m, verdict
   READY from the first check (terrain generation already
   forced it in).
8. Water: totalQueries > 0 and DATA ARRIVED (batch 2 already
   saw its fetch road work cold).
9. Overture: NOT SERVING is acceptable ONLY alongside log
   lines showing healthy OSM (the backup is dormant by
   design); SERVING with structures is also a pass. NOT
   SERVING + struggling OSM would be a real finding.
10. OHM prints OFF BY SWITCH — expected and honest while
    timejump stays disabled.

## Owner run (batch 3)

The owner types ONLY `/terracheck`.

1. `gradle jarJar`, start the game with the new jar.
2. NEW world (fresh cache shows the whole cold road). Stand at
   spawn; run `/terracheck` at spawn, again after ~2 minutes,
   again after ~3 more minutes (refill needs its minutes).
3. `/tpll 17.3617 78.4747`, stand still ~1 min → `/terracheck`.
4. Send `latest.log`.

Note: batch 3 does not repair chunks already saved in older
worlds beyond what the (now unblocked) refill road re-queues
when those tiles are requested again; the fresh world is the
clean proof.
