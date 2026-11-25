# Test 1 — COLD start (owner's log, 2026-11-17 19:03-19:06)

World "New World", spawn area 17.4346 N, 78.5015 E (NOT
Charminar — no /tpll this session; ~100 s of play).
Cache marker: FRESH. New log lines all present and working.

## Verdict per source (from [TerraStruct] lines)

- **OSM — fetched AND placed.** Tiles [17.43,78.50] 1647
  features, [17.43,78.49] 3665 features (api.osm.org).
  Placed osm=60..330 structures per chunk. No OSM FAILED.
- **VIDA — fetched AND placed.** Shard IND_4308537468510011392
  ranged fetch: 15,355 buildings retained. Before it landed
  (19:04:43) chunks show vida=0; after, vida=1..62 per chunk.
  Calibration vs OSM ran (+1.04 m / +4.70 m).
- **Microsoft — fetched AND placed, but very little placed
  here.** Index 25,606 quadkeys; quadkey 123301331 parsed
  (628,630 lines, 24,391 retained); ~30 tiles fetched,
  ~4,900 buildings total. Placed microsoft=0 in most chunks,
  1-5 in some (max 5). Expected in this area: it is the last
  gap-filler and OSM covers nearly everything here, so almost
  every Microsoft footprint is dropped as a duplicate.
- Async tile [17.43,78.50]: osm=1647 vida=0 microsoft=41
  combined=1688, queued=133. VIDA skipped on the retry road
  for this tile ("OSM coverage dense", by design).

## Confirmed working as ordered

- `TerraTimeManager: timejump disabled (temporary); year
  pinned to 2026`
- `OhmStructureIndex init (enabled=false)` — no OHM
  structures. (OHM *water* still fetches — separate system,
  not part of the structures order.)
- No `Struct [x,z]` error lines: the buried-hole trigger did
  not fire this session.

## Honest catches

1. Chunks born in the first ~20 s (before VIDA/Microsoft
   downloads landed) got OSM only. The retry road re-checks
   them, but for this tile VIDA was skipped as "dense", so
   they are not topped up. Cold-start inconsistency, minor.
2. Same OSM tile fetched twice (19:04:25 + 19:04:26) —
   small waste, harmless.
3. The Charminar question is NOT answered by this test —
   the player never teleported there.

## Awaiting

Test 2 (warm, same world/place): expect OSM DISK lines,
no internet fetches, same placement behaviour from cache.
