# TerraCheck batch 1 — owner's log, New World (3), 2026-11-18 14:23–14:29

Owner typed only `/terracheck`, three times: spawn area
14:24:25 and 14:27:29 (Secunderabad), Charminar 14:28:27
after `/tpll 17.3617 78.4747`. The jar compiled and ran —
batch 1 is runtime-proven, not just source-checked.

## Proven by this log

1. **TC-LOG works.** Every TerraCheck line is in latest.log
   under `[TerraCheck]`, as designed.
2. **The Charminar parser fix works in the real game.**
   14:28:27, chunk [629472,-126410]:
   `CHARMINAR osmId=238186115: FOUND -> type=BUILDING sub=yes
   name=Charminar levels=0 height=19.0 resolvedBlocks=19
   colour=#FFE4B5 material=limestone roof=flat` — exactly the
   prediction written before the run. The old
   `BUILDING_PART` misclassification is dead in the built jar.
   (Physical placement in the world is still the owner's
   eyes / world file — the log proves classification and
   that the chunk's structure list holds it.)
3. **Cold start, caught in the act (TC-HERE).** First run,
   chunk [629378,-126959]: OSM `tilesLoaded=true`,
   structures=144 (BUILDING=79 …), while Microsoft reported
   `tilesLoaded=false missingTiles=2 cacheTiles=0
   buildingsHere=0` and VIDA `shard NOT LOADED YET`.
   Minutes later the VIDA shard landed (15,355 buildings,
   14:24:29) and chunks born after that show vida=5..62.
   Chunks born before it show vida=0. This is the cold-start
   gap, measured by the test instead of guessed.
4. **VIDA at the Charminar was still cold** at check time
   (`shard NOT LOADED YET, cachedShards=2` — the two shards
   were the Secunderabad ones); all Charminar-area placements
   in this session show vida=0 microsoft=0.
5. **Timejump stays off:** `timejump disabled (temporary);
   year pinned to 2026`, OHM structures `enabled=false`,
   TerraCheck config `timejump=false ohm=false year=2026`.
6. Fetch probes: OHM/Microsoft/VIDA hosts answer (HTTP
   200/206). The OSM *Overpass* probe timed out once and
   returned HTTP 502 once (see flaw 1 below) — while the real
   structure path via api.osm.org fetched fine all session
   (OSM FETCHED 1647 / 3665 / 1238 / 703 features).

## Flaws found in the test bench itself (fix in batch 2)

1. **Probe false-positive:** HTTP 502 was reported as
   `OSM (Overpass): ANSWERS (HTTP 502...)`. Any HTTP response
   must not count as success — 5xx = NO ANSWER.
2. **The probe tests the wrong OSM door:** it pings
   overpass-api.de, but structures actually arrive via
   api.osm.org tiles. Probe verdicts must be labelled as
   host-reachability only, or probe the real endpoints.
3. **Status counter misleads:** `Placed so far: 0 chunks,
   0 blocks` stayed 0 all session while thousands of
   `[TerraStruct] PLACED` lines fired — that counter belongs
   to the async tick handler only. Rename it so it cannot be
   misread as total placements.
4. **Microsoft state is under-described:** `cacheTiles=0`
   at all three checks and every PLACED line shows
   microsoft=0, yet the index downloaded (25,606 quadkeys,
   14:24:41) and a tile download started. The test must also
   log Microsoft queries/hits/disk-hits/empty-tiles so
   "still downloading" vs "never asked" vs "failed" can be
   told apart. (Verified in code: generator and TerraCheck
   share the same MicrosoftIndex singleton, so the empty
   cache reading itself is truthful.)
5. **First-25 items are road-heavy:** at the Charminar the
   monument happened to be item 3; elsewhere buildings can be
   pushed past the cut. Building/part items should be listed
   first in batch 2.

## Still open after batch 1

- Does the Charminar *stand in the world* (owner's eyes or
  the world file)? Classification is proven; placement of
  that specific building is not yet isolated in the log.
- Microsoft fetch completion: never observed this session.
- Warm-cache behaviour (Test 2) still pending.
- Async refill of cold-born chunks: queued retries and OSM
  retry successes are logged, but no TerraCheck evidence yet
  that an early chunk got topped up.
