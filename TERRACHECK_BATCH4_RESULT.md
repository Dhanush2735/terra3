# Batch-4 Result — owner's log, 2026-11-18 19:14-19:20

**Verdict: 6.5 of 8 predictions correct. Overture is PROVEN
in-game. Sea-level handling is PROVEN in-game. Microsoft's
parallel download was NOT exercised (see §3) — everything
else in the batch behaved as designed.**

Session shape: 5× /terracheck — spawn (×2), Charminar (×2),
rural /tpll 17.9 79.6 (×1). Important honesty note: the log's
own status line says **Cache: warm (921 MB)** — the instance
cache from earlier sessions survived, so this doubled as the
long-pending WARM-CACHE test (cache-served behaviour, few
fetches, matching values) rather than a pure cold test.

## 1. Overture — FIXED, PROVEN IN-GAME ✅

- `OVERTURE release: discovered newest 2026-10-21.0 from
  bucket listing` — discovery works in the game, not only
  in the sandbox.
- `header self-check PASS` for buildings (3501 entries) and
  transportation; **zero `OVERTURE header FAIL` in the
  whole log** (batch 2+3: every check).
- Run 2 (spawn): `VERDICT: SERVING — 3 structures resident`.
  Run 4 (Charminar): `VERDICT: SERVING — 7 structures`,
  SOURCE STACK overture=2 buildings. The generator's own
  road also served: `OVERTURE area [17.4346,78.5015]: 15
  structures from 4/4 tiles in 3ms`.
- Runs 1/3/5 honestly show NOT SERVING at the moment of
  asking (fresh tiles still in flight inside the bench's
  1.5 s budget) — the next check serves. That is the
  designed appetizer timing, now visible instead of a
  permanent black hole.

## 2. Sea-level batch — ALL PASS ✅

- **EXTREMES: PASS** all 5 runs, all 9 points loaded on the
  FIRST run: Everest 8728.9 m → Y2022; Hyderabad control
  508.0 m → Y571 (matches the proven in-world value to the
  block); Dead Sea W shore −412.0 → dry Y21; Death Valley
  −80.8 → Y54; Flevoland −5.2 → Y61; Caspian −29.0 → Y59;
  Mariana −10273.7 → floor Y−2023; Bay of Bengal −2730.6.
- **SEAM: PASS** — 1000 m join step exactly 3 blocks;
  8848 m → Y2032 (world top, exact); −430 m → Y19;
  −11034 m → Y−2028 (world floor, exact).
- **SENTINEL SCAN: SAFE 0/5** in all 5 chunks (spawn,
  Charminar, rural) — no fake-ocean exposure where tested.
- **DEAD SEA GRID: SOURCE DATA DIRTY** as predicted —
  loaded 25/25, min −412.1 m, max +1180.5 m (Moab-side
  hills at the grid edge), 10/25 positive. The DEM dirt is
  now a measured fact in the owner's own log; how the
  repaired terrain LOOKS awaits the owner's eyes.

## 3. Microsoft — Step 1 proven; Step 2 not exercised ⚠️

- Step 1 (fire-and-forget warmup): PROVEN —
  `[TerraStruct] MSFT prefetch: N tiles dispatched (quadkey
  download starts now, in parallel with generation)` ×10.
- Step 2 (parallel download): NOT EXERCISED in-game. The
  53 MB Hyderabad file came from the previous sessions'
  decompressed disk cache (`from disk: 305 in window, 36
  region keys pre-seeded`); the one NEW file, rural quadkey
  123310221, is only **2.2 MB** — below the 8 MB parallel
  threshold, so it single-streamed by design (30,242 lines
  in 26 s on the owner's line). Sandbox proof stands
  (53.3 MB in ~1 s, byte-identical stitching); in-game
  proof needs one teleport to a far, uncached, big-file
  city (e.g. /tpll 28.6139 77.2090 Delhi).
- Small honesty gap found: when the parallel path is
  skipped (small file / no ranges), nothing is logged, so
  the log cannot say WHY. One-line log fix queued.
- Everywhere Microsoft was asked, it answered: Charminar
  buildingsHere=20/12 from cache, spawn 4/6, rural tiles
  genuinely empty (emptyTiles=17, verdict honest).

## 4. Source stack — works; rural chunk genuinely bare ✅/⚠️

Charminar: osm=41, microsoft=20, overture=2, vida=−1
(shard still in flight at that minute; it had served 26–35
at spawn). Rural chunk: osm=0, microsoft=0, overture/vida
not resident yet → honest "NOTHING RESIDENT YET". The
chunk is cropland (ESA `CROPLAND=5` — correct!): 2 roads
in OSM, no buildings in Microsoft's tiles. Neighbouring
rural chunks later show `PLACED ... vida=1` — VIDA did
carry scattered rural buildings once its shards landed.
The step-3 question (can fillers replace direct Microsoft)
gets its real evidence in cities (Charminar: fillers
20+2+shard on top of OSM's 41) — retiring Microsoft is
still NOT justified by this log alone.

## 5. Doors (5 runs)

OSM tiles ANSWERS ×5 (444–762 ms); Wikipedia ANSWERS ×5;
OHM ANSWERS ×5; Microsoft ANSWERS ×5 (HTTP 206);
VIDA ANSWERS ×5 (30–65 ms); Overpass NO ANSWER ×5
(502/timeouts — the usual; structures don't depend on it);
**Wikidata: 4× SocketTimeout at ~8.1 s, 1× ANSWERS
(HTTP 206, 158 ms)** — the SPARQL door is slow to wake;
its real lookups stay UNTESTED (they matter only with
timejump on).

## 6. Session health

One `Can't keep up!` of 2,128 ms in ~7 minutes (batch 3:
6 s + 29 s spikes). 2,901 chunks PLACED. Cache grew
921 MB → 1,580 MB (the EXTREMES/Dead-Sea probes pulled
world elevation tiles into cache — expected, one-off).
VIDA prefetch fired at warmup, shards landed; refill
WORKS in every run (rural tile still pending at its
first check — normal cold behaviour). Charminar FOUND
again (19 blocks, limestone, #FFE4B5; heights at the
Charminar chunk: 9 height-tagged, tallest 53). Game
announced `A structure is emerging here ("Charminar")` —
standing remains the owner's eyes, as always.

## Prediction scorecard

| # | Prediction | Result |
|---|---|---|
| 1 | EXTREMES all sane | ✅ exact |
| 2 | SEAM step 2–3, caps exact | ✅ exact (3) |
| 3 | SENTINEL 0/5 | ✅ all runs |
| 4 | DEAD SEA DIRTY | ✅ exact |
| 5 | SOURCE STACK osm leads, fillers present | ✅ cities; rural honestly empty |
| 6 | Wiki doors ANSWERS | ⚠️ Wikipedia ✅, Wikidata 1/5 |
| 7 | Overture SERVING, PASS headers | ✅ runs 2/4 |
| 8 | Microsoft parallel < 166 s | ⚠️ not exercised (cache + small file) |

## Still open

- Microsoft parallel download IN-GAME: one far-city
  teleport test (Delhi/Chennai) — or accept sandbox proof.
- One-line log when parallel is skipped (small bench fix).
- Charminar / water / Dead Sea LOOKS — owner's eyes.
- Timejump-ON systems (OHM structures, wiki lookups,
  refresh) — by owner's switch.
- Sentinel FAKE-OCEAN fix — only on owner direction.
