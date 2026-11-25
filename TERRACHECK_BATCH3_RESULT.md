# TerraCheck batch 3 — owner's log, New World (5), 2026-11-18 17:48–17:53

Owner ran exactly the asked test: fresh world (cache marker
FRESH), `/terracheck` at spawn 17:49:08, again 17:50:53 and
17:52:10, `/tpll 17.3617 78.4747` at 17:52:42, `/terracheck`
at the Charminar 17:53:15. The batch-3 jar compiled and ran.

**Headline: all three batch-3 fixes are PROVEN in this log.
Microsoft delivered for the first time in any session, and
the refill road placed 21,111 blocks through the async road
(batch 2: zero, forever).**

## Prediction scorecard (written before the run)

| # | Prediction | Result |
|---|---|---|
| 1 | `VIDA prefetch … starting now` before first PLACED | ✅ 17:48:50.696, first PLACED 17:48:55.822 |
| 2 | Microsoft lines rise, then completion or named FAILED | ✅ 100k→600k lines, completed 166 s, buildings served |
| 3 | REFILL VERDICT: WORKS within ~2–5 min | ✅ tile completed 17:50:56 (~2 min), WORKS at 17:52:10 |
| 4 | VIDA pending early, READY sooner than batch 1/2 | ✅ READY at first check (17:49:08), shard landed 17:49:06 |
| 5 | cacheTiles>0, msftHits>0 if Microsoft lands | ✅ 17→51 tiles, 2,168→8,309 buildings |
| 6 | ESA BUILT_UP + READY | ✅ all 4 checks |
| 7 | Elevation ~500–600 m | ✅ 537.8–539.8 spawn, 507.7 Charminar |
| 8 | Water DATA ARRIVED | ✅ (via OHM tiles; see note) |
| 9 | Overture judged with OSM health | ⚠️ NOT SERVING even when armed — real finding, §5 |
| 10 | OHM OFF BY SWITCH | ✅ printed as designed |

## 1. VIDA late — FIXED

- `COLD WARMUP [17.4347,78.5015]: 7 sources prefetching`
  (17:48:50.717) now includes VIDA:
  `VIDA prefetch IND_4308537468510011392@1743,7850: shard
  fetch starting now (no cold-start delay)` at 17:48:50.696 —
  during terrain generation, before any structure placement.
- Shard landed 17:49:06.942: `ranged-fetch: 15354 buildings
  retained` (~16 s after the prefetch fired; batch 1 took
  ~35–60 s from first ask). Further regions followed at
  17:50:40 (15,915) and 17:51:24 (16,831).
- First `vida>0` placement 17:49:09.222
  (`osm=104 vida=9`), ~18 s after generation began; by the
  first `/terracheck` VIDA already read READY
  (buildingsHere=26, 15,354 cached). Batch 1/2 needed a
  minute or more to reach this state.
- Honest nuance: chunks born in the first ~15 s are still
  OSM-only (the shard cannot arrive instantly), and the spawn
  tile's refill later completed with `vida=0` BY DESIGN —
  `needsVidaGapFill` judged OSM coverage dense (1,647
  structures) and skipped the VIDA tier. Where OSM is
  sparser the refill did accept VIDA: tile [17.4300,78.4900]
  completed with `vida=4005` (§2). So "early chunks get VIDA
  later" is now answered precisely: the road works, and the
  dense-coverage rule decides per tile.

## 2. Refill road — FIXED (the batch-2 contradiction is dead)

Timeline for the spawn tile [17.4300,78.5000], queued
17:48:54.114:

- 17:48:56.115 `OSM retry success … (1647 structures,
  attempt 1)` — stage 2 done, as in batch 2.
- Then, unlike batch 2, at 17:50:56.262:
  `[TerraStruct] AsyncStruct tile [17.4300,78.5000]:
  Microsoft not ready after 6 tries — completing WITHOUT
  Microsoft (OSM/VIDA still placed)` — the batch-3 give-up
  fired exactly as designed, ~2 min after queueing.
- 17:50:56.274 completion: `osm=1647 vida=0 microsoft=0
  combined=1647 waiting=243 queued=243 skipped=0
  localRefs=6334` — 243 waiting chunks fed to the placement
  queue. Tick stats 17:51:02.549: `placed=126c/9690b`.
- Check 3 (17:52:10): `Async refill road: 0 pending,
  2 done, 11 retries, 2 successes`, `Placement queue:
  … 243 placed`, `Async-road placements ONLY: 243 chunks,
  21111 blocks`, `REFILL VERDICT: WORKS`,
  `completedWithoutMsft=1 completedWithoutVida=0`.
- Later tiles completed WITH Microsoft once it landed:
  [17.4300,78.4900] at 17:52:08 (`osm=1757 vida=4005
  microsoft=7 combined=5769`), Charminar tile
  [17.3600,78.4700] at 17:52:47 (`osm=1238 microsoft=73
  combined=1311 waiting=9 queued=9`), [17.3500,78.4700] at
  17:53:32 (`microsoft=178 … queued=2`).
- Shutdown: `AsyncStructureManager shutdown (retries=13,
  successes=4)` — batch 2's shutdown said successes=0.

Many tick lines read `Placed 0 blocks … (dual-path re-place;
expected during refresh)`: the refill re-offers the full
combined list and placement skips what generation already
built; some chunks gained real blocks (100, 348, …). The
road completing, queueing and placing is the proven point.

## 3. Microsoft — FIXED (first delivery in three sessions)

- Index downloaded 17:49:05.219 (25,606 quadkeys); download
  of quadkey 123301331 started 17:49:05.273.
- Progress lines, rising steadily: 100,000 lines/30 s →
  200,000/52 s → 300,000/83 s → 400,000/105 s →
  500,000/131 s → 600,000/160 s → **complete 17:51:51.537:
  `MSFT quadkey 123301331: 540 retained, 540 of 628630 lines
  parsed (166s)`**, then the disk pre-seed scan
  (`628630 lines scanned, 26289 JSON-parsed … 35 region keys
  pre-seeded`).
- Immediately served: `MICROSOFT FETCHED [17.4275,78.5075]
  buildings=329 [MSFT:329]` and many more; first
  generation placement with `microsoft>0` at 17:51:52.597.
- Counters: check 3 `cacheTiles=17 msftHits=17
  totalBuildings=2168`, verdict `BUILDINGS HERE (5)`;
  Charminar check `cacheTiles=51 msftHits=51
  totalBuildings=8309`, `buildingsHere=20`. At the
  Charminar, where VIDA had not landed yet, Microsoft
  gap-filled heavily (`microsoft=5..23` per chunk, e.g.
  `osm=26 microsoft=22`).
- The download state line worked as designed:
  `state=DOWNLOADING quadkey=123301331 lines=403456
  ageSec=108 sinceProgressSec=0` mid-flight, and
  `state=idle (quadkey 123301331: 628630 lines in 166s)`
  after.
- So the old failure mode is explained by contrast: the
  streamed download needed 166 s of steady progress; the
  old in-memory version in batch 1/2 never showed a
  completion inside shorter sessions and logged nothing.
  Cost honesty: a cold quadkey still costs ~166 s on this
  connection; it is one-off per region and now visible.
  `queries` remains an inflated counter (4,231 by the end —
  fail-fast attempts while the one download runs); harmless
  now that the download state line tells the truth.

## 4. All-sources roll call

- **OSM structures**: healthy all session via api.osm.org
  (tiles in 1–2 s). Overpass probe: NO ANSWER all 4 runs
  (3 timeouts, one HTTP 502) — the structures road does not
  depend on it.
- **ESA WorldCover**: READY at all 4 checks,
  `classes(5 samples)={BUILT_UP=5}` at spawn and Charminar;
  header N15E078 served the player area. One blemish:
  neighbour tile N12E081 returned 404 and was suppressed
  (17:48:51.178) — a fully-ocean ESA tile that appears not
  to be published; harmless here. First-seconds surface
  lines showed `wc=UNKNOWN` fallback before the tile
  landed, then Built-up took over.
- **Elevation**: READY; 537.8–539.8 m at spawn, 507.7 m at
  the Charminar (tpll independently reported 508.0 m,
  surface Y 571).
- **Water**: DATA ARRIVED at all checks, driven by OHM
  water tiles (cached 9→12→15; feature lines throughout).
  OSM-water's Overpass host returned 502 repeatedly, but
  the mail.ru fallback served (`OSM [17.3500,78.5500]:
  53 features … (mail.ru)`); the bench's `osmTilesCached`
  counter stayed 0, so the verdict rests on the OHM tiles.
  Placement in the world remains unchecked (eyes/log
  question, as the bench itself says).
- **OHM structures**: `OFF BY SWITCH` at all checks, as
  designed while timejump is disabled.
- **Charminar**: `CHARMINAR osmId=238186115: FOUND ->
  type=BUILDING height=19.0 resolvedBlocks=19
  colour=#FFE4B5 material=limestone roof=flat` as item #1
  of its chunk (also Char Kaman 21, Bhagyalaxmi Temple 8).
  The game itself warned the player: `A structure is
  emerging here ("Charminar")` (17:52:54) and
  `"Charminar" will appear in 25s!` — whether it STANDS
  correctly is still the owner's eyes; the log cannot say.

## 5. The one remaining failure: Overture appetizer

`OVERTURE header FAIL theme=buildings` and
`theme=transportation` at 17:48:54, `0/N tiles resident …
nothing to serve` at every check, bench verdict NOT
SERVING all 4 times — same signature as batch 2. At the
Charminar the appetizer was even ARMED twice
(`WARMUP: OSM struggling — Overture appetizer armed`,
17:52:43 and 17:53:24, 18 tiles dispatched) and still
served nothing. So this is not dormancy: the backup
source's tile header read fails outright. Impact today is
low — api.osm.org carried every chunk — but the safety
net is dead: if OSM's main door fails in a fresh area,
the appetizer will not cover. This is now the only FAILED
source row. (Candidate causes — wrong release path
`2026-08-19.0`, PMTiles header change — need code reading
before any fix; not attempted in batch 3.)

## 6. Side evidence

- Two `Can't keep up!` spikes: 6,096 ms (17:51:09, refill
  burst + generation) and 29,292 ms (17:51:53, right at the
  Microsoft disk parse + placement burst). The integrated
  server shares CPU with 16 generation workers; batch 3 did
  not make this worse than the known Finding-1 pattern,
  but the refill burst's arrival is visible in tick lag.
- Cache growth: 14.1 MB → 168.8 → 266.2 → 490.4 MB.
- VIDA alignment still calibrated in-game
  (`dLat=+0.60m dLon=+2.33m from 24 pairs`; Microsoft
  `dLat=-1.73m dLon=+2.51m from 96 pairs`).
- Bridges alive again (`BRIDGE=2`, layer-4 BRIDGE
  CROSSING, two BRIDGE AREA BEAM lines).

## Still open

- Charminar **standing** — owner's eyes (asked again with
  this log; the game announced it emerging at 17:52:54).
- Overture appetizer repair (FAILED row; fix only on
  owner's direction).
- Warm-cache structure test (this was again a fresh
  world/cache).
- Water/vegetation/bridge *appearance* — log proves data,
  eyes prove looks.
- Timejump-ON systems (OHM structures, refresh) — untested
  while the switch is off, by owner's own setting.
