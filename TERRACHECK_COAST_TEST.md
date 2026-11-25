# /terracheck coast — find the ACTUAL beach/coastline problem (2026-11-21)

Owner asked for multiple tests in terracheck to find the beach/coastline
slowdown "100%" — fetching vs generating, no more guessing.

## What was added

New subcommand `/terracheck coast` (also started by plain `/terracheck`).
Implemented in `TerraCheckCommand.java` (`coastPerf`/`coastPerfAsync`),
JDK17 parse-clean; every called method verified to exist. Read-only —
classifies and times, places nothing, runs off the server thread.

Stand **in a chunk that contains both land/sand and sea**, then run
`/terracheck coast` ALONE for clean numbers (plain /terracheck also
starts it, but the other async tests add timing noise).

## The 8 tests (all logged as `TEST coastperf ...`)

1. **FETCH-STATE** — is the OSM coastline tile cached? How many coastline
   segments are loaded around here (`coastSegCountAround`)?
2. **FETCH** — if not cached, times the exact shared wait generation
   uses (`getTileBlockingShared`, 12 s) and whether the tile landed.
3. **SCAN** — average time of ONE coastline land/sea decision
   (`classifyCoastlineMemOnly` ×2000), with the segment count.
4. **ELEV** — average time of one elevation sample (both books at shore).
5. **CLASSIFY** — average time of one full `classify()`; printed next to
   scan+elev so the remainder is visible.
6. **PAINT-GRID** — times the generator's real 64×64 classify grid for
   this chunk (4,096 calls), counts land/water columns, and says whether
   this is truly a boundary chunk.
7. **BEACH** — times the 16 biome `classify()` calls and the 16
   `isShoreCellPublic` calls (centre re-classify + up to 16 probes each).
8. **ESA** — first `getClassBlocking` time (the cold wait) vs warm
   `getClass` average.

Ends with `TEST coastperf VERDICT:` and a one-line `SUMMARY` with every
number side by side. The verdict names the culprit: FETCH PROBLEM
(tile not ready after 12 s), GENERATING PROBLEM (paint grid / scan),
GENERATING PROBLEM (beach probes), WAIT PROBLEM (ESA first answer), or
"not a boundary chunk — move to a real beach chunk and re-run".

## How to read the result at Juhu

- `waitMs` large / `cachedAfter=false` → fetching still guilty there.
- `paintGridMs` in the hundreds+ with `scanUs` tens of µs and segments
  ~1,771 → generating (repeated coastline scan) confirmed in-game.
- `shoreProbeMs` large next to `paintGridMs` → beach probes matter.
- `esaFirstMs` in seconds → cold ESA wait matters for first chunks.

## Owner test

1. Rebuild (`gradle jarJar`), confirm jar in mods.
2. Teleport to Juhu beach (19.0968, 72.8265), stand where the chunk has
   both sand and sea.
3. Run `/terracheck coast`, wait for the chat verdict.
4. Send the `TEST coastperf` lines from latest.log (or just the VERDICT
   + SUMMARY lines).

## NW(28) RESULT — 22 Nov 2026, Juhu, owner ran plain /terracheck

World "New World (30)", fresh spawn Gandikota then /tpll Juhu. Owner ran
/terracheck three times; the first two (chunk [588948,-119469]) were one
chunk too far inland (64x64 grid land=4096 water=0) and the test
correctly returned NOT A BEACH/COAST BOUNDARY CHUNK. The valid run at
21:18:44 (chunk [588945,-119466], centre COASTLINE, boundary=true):

- FETCH: cachedBefore=true, waitMs=0, segments=1771 → NOT fetching.
- SCAN: 31.9 us per coastline decision.
- ELEV: 0.5 us → innocent.
- CLASSIFY: 32.8 us → **scan = ~97% of classify**.
- PAINT-GRID: **136.6 ms per chunk** (avg 33.3 us x 4096).
- BEACH probes: 0.9 ms total (shoreTrue 5/16) → NOT a problem.
- ESA: first 0 ms, warm 0.0 us → NOT a problem.

**PROVEN: the beach/coastline slowness is GENERATING — the repeated
unindexed coastline segment scan inside classify(), ~137 ms of
classification per coastal chunk, re-paid by every chunk along a
shore.** The printed VERDICT ("NO SINGLE BIG COST") was a calibration
bug: the 250 ms threshold was too strict. Threshold fixed same day
(>=100 ms grid + scan >=70% of classify → GENERATING PROBLEM);
JDK17 parse-clean.

Context from the same log: cold Juhu arrival still had a one-time
fetch/warmup component (WATER BATCH landed 21:17:32, ~31 s after the
21:17:01 teleport; server 34.1 s behind at 21:17:51) and heavy Juhu
structure placement (city load, not beach load). ESA WAIT 9,295 ms
loaded=false seen at Gandikota spawn is a separate cold-start item,
not the beach problem (Juhu ESA = 0 ms).
