# TerraCheck batch 2 — owner's log, New World (4), 2026-11-18 17:08–17:12

Owner typed only `/terracheck`, three times, exactly as
asked: spawn 17:09:19, spawn again 17:10:21, Charminar
17:11:18 after `/tpll 17.3617 78.4747`. Fresh world; cache
started FRESH and grew 4.4 MB → 142.1 MB during the session.
The batch-2 jar compiled and ran.

## The bench fixes — all proven in this log

1. **502 lie fixed.** `OSM (Overpass): NO ANSWER (HTTP 502 —
   the server replied but refused/failed, 484 ms)` at
   17:10:22 and again at 17:11:21. First run it timed out
   (8.5 s) and also printed NO ANSWER. The real door,
   api.openstreetmap.org, answered HTTP 200 all three runs.
2. **Honest counter label printed:**
   `Async-road placements ONLY (not world generation):
   0 chunks, 0 blocks` — while generation placed thousands
   of structures around it. The label now tells the truth
   about what the number means.
3. **Buildings first.** At the Charminar the very first OSM
   item is the monument itself (see below); at spawn the
   first 25 items are all buildings.

## What the new tests proved

### TC-HEIGHTS — where building heights come from

| Chunk | Buildings/parts | Real `levels` tag | Real `height` tag only | No tag — game must guess |
|---|---|---|---|---|
| Spawn 1 [629379,-126959] | 77 | 0 | 0 | **77** |
| Spawn 2 [629390,-126965] | 107 | 0 | 0 | **107** |
| Charminar [629472,-126410] | 39 | 0 | 9 | 30 |

- In Secunderabad the map gives **no height at all** for
  ordinary buildings — 184 of 184 checked. Every ordinary
  building's height in game is Terra3's own guess, made at
  build time (the parser list carries `resolvedBlocks=-1`,
  unresolved, for every one of them).
- `building:levels` was never seen in any chunk (0 total).
  Height tags exist almost only on monuments: Charminar
  19, Char Kaman 21, Bhagyalaxmi Temple 8 (pyramidal roof).
- Charminar-chunk tallest = 53 blocks (a tagged part) —
  prediction was ≥ 19. Passed.

### Microsoft — asked thousands of times, never answered

| Check | queries | tiles in cache | msft hits | disk hits | empty tiles | Verdict printed |
|---|---|---|---|---|---|---|
| Spawn 1 | 0 | 0 | 0 | 0 | 0 | NEVER ASKED |
| Spawn 2 | 440 | 0 | 0 | 0 | 0 | ASKED, STILL WAITING |
| Charminar | 4445 | 0 | 0 | 0 | 0 | ASKED, STILL WAITING |

- The index downloaded at 17:10:13 (25,606 quadkeys) and
  the quadkey `123301331` download started — the same as
  batch 1. **No completion line ever followed**, in either
  session.
- Generation asked Microsoft 4,445 times in ~2.5 minutes
  and every single `[TerraStruct] PLACED` line in the
  session says `microsoft=0`. Not one tile ever landed in
  cache (hits 0, disk hits 0, not even an empty-tile mark).
- Verdict: the Microsoft road is switched on, is asked
  constantly, starts downloading, and **never delivers**.
  This is now a FAILED row, not an unknown (two sessions,
  same story). Why the download never finishes is the next
  question — the log has no Microsoft error line at all,
  which itself is a clue (it fails silently or never ends).

### TC-REFILL — the test exposed a contradiction

Every check printed: `completedTiles=0`, `successes=0`,
`refillStructuresForThisChunk=NONE YET`, shutdown also said
`successes=0`. Yet the same log contains repeated
`OSM retry success [17.4300,78.5000] (1647 structures,
attempt 1)` lines — the spawn tile itself succeeded at
17:09:06, thirteen seconds before the first `/terracheck`
already reported successes=0.

So one of these is true, and this log cannot tell which:
(a) TerraCheck reads the tick handler's async manager while
the successes happen in the *generator's* async manager
(the startup reflection list shows `TerraChunkGenerator`
has its own `getAsyncManager()`), or (b) the counters are
simply never incremented on the success path. **Batch 3
must ask both managers in the same line and compare.**

What is NOT in doubt: early chunks were placed OSM-only
(`vida=0 microsoft=0`) and later chunks got VIDA
(`vida=5..53`) once the shard landed at 17:09:22. No log
line shows an already-placed chunk being topped up.

## Side evidence in this log (not TerraCheck tests)

- **VIDA alignment is measured in-game:** `Calibrated vida
  vs OSM ... dLat=+0.86m dLon=+2.36m from 42 pairs`, later
  `dLon=+4.14m from 9 pairs` — VIDA footprints sit ~2–4 m
  east of OSM here and the game corrects for it.
- **Overture road also unhealthy:** `OVERTURE header FAIL
  theme=buildings` + `0/2 tiles resident ... nothing to
  serve` at every cold start. (Config merges it with
  Microsoft: `microsoft/overture=true`.)
- **Water fetch works, placement unchecked:** `WATER BATCH
  ... 9 tiles in one request (221 features total)`, plus
  one Overpass 502 warning.
- **Bridges alive in parser:** byType BRIDGE=2 at spawn;
  `BRIDGE CROSSING: 1188687061 (layer=4) and 1188687060
  (layer=4) share a column — decks mutually protected`;
  two `BRIDGE AREA ... structure=BEAM` lines.
- Timejump off confirmed again (year 2026, OHM structures
  disabled); Charminar classification re-proven
  (`type=BUILDING height=19.0 resolvedBlocks=19
  colour=#FFE4B5 material=limestone roof=flat`), now as
  item #1 of its chunk.
- VIDA at the Charminar was still cold at check time
  (30 s after teleport) — all Charminar placements this
  session are vida=0.

## Still open

- Charminar **standing in the world** — owner's eyes only.
  (Asked twice now; the log can never answer this.)
- Microsoft download: why it never finishes (batch 3:
  watch hits/queries trend + cache growth per run).
- Refill contradiction (batch 3: both async managers).
- Warm-cache test still pending (this was a fresh world).
