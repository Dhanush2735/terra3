# Cold-start performance — batched area fetch

## The real bottleneck

On a cold start (empty `terra_cache`) the cost is not CPU, parsing, or
threads — it is **request count × rate limit**. Every 0.01° structure tile
costs one Overpass round-trip, and public Overpass etiquette caps us at
~1 request/sec/endpoint. A spawn area needs ~25 tiles:

```
25 tiles ÷ (3 endpoints × 1 req/s) ≈ 8–25 s of pure rate-limit waiting
+ 25 × (HTTP latency + server query time, 1–10 s each on a bad night)
```

No amount of threading fixes that — the fix is **fewer, bigger requests**.

## What was applied (OsmStructureIndex)

**One area request replaces up to 30 tile requests.**

- `buildQuery(bbox, timeout)` — the full-coverage query extracted so the
  same tag families serve any bbox (single tile or batch).
- `fetchBatch(tLatMin..tLatMax, tLonMin..tLonMax)` — fetches a rectangle of
  tiles with ONE Overpass request (60 s server budget, 75 s read timeout,
  gzip). The response is split into per-tile `StructureTile`s: each element
  is assigned to every tile its bbox overlaps (+`OVERLAP_MARGIN` — the same
  test `collectFromKeys` applies at read time, so results are identical to
  per-tile fetches). Empty tiles are cached as `EMPTY` (valid), so ocean and
  rural areas never refetch. Each tile also gets a per-tile Overpass-shaped
  JSON subset written to the disk cache, so `loadDisk()` replays batched
  tiles identically on later starts.
- `fetchMissingAsBatches(keys)` — groups missing tiles into ≤30-tile blocks
  (5×6) and fires one request per block.

**Wired into both cold-start entry points:**

1. `getStructuresForChunkBlocking` — ≥2 missing tiles → batch first, then
   fall back to single-tile fetches only for stragglers.
2. `prefetchTiles` (called during terrain stage-1 for the spawn area) —
   registers a pending future per tile (concurrent callers wait on the
   batch instead of firing duplicates), then runs one batch on the pool;
   unresolved tiles fall back to the single-tile path.

Rate-limiter integration is unchanged: a batch costs one token, honours
endpoint ordering (soonest-available first), and applies the same 429/504
penalties.

## Measured (live Overpass, dense central Hyderabad)

Simulated spawn: prefetch + 9 chunk-decoration queries, empty cache.

| | requests | wall time |
|---|---|---|
| tile-by-tile (previous behaviour, same night) | ~25–30 | 74–103 s (earlier session measurements) |
| **batched (this change)** | **2** | **9.4 s** |
| warm disk cache (unchanged) | 0 | 1.7 s |

First chunk query returns in 8.8 s (the batch itself); the remaining 8 are
served in 0–185 ms from the freshly filled memory cache. The 5×5-tile probe
that motivated the design: 1 request, ~6 MB effective payload, 57,948
elements in 11.6 s — where 25 sequential requests would have taken minutes.

## Equivalence proof

Same tile fetched via batch path and via single-tile path, live data:

```
batch:  406 structures, 406 osmIds
single: 406 structures, 406 osmIds
only-in-batch: 0   only-in-single: 0   → EQUIVALENT
```

## Why this is safe for Overpass servers

One 0.05° query is *cheaper* for the server than 25 × 0.01° queries: the
per-request overhead (parse, planning, connection) dominates small queries,
and total data transferred is the same. Batch size is capped (≤30 tiles,
~0.05°×0.06°) so a single request can never balloon; 429/504 still penalize
the endpoint and back off.

## Existing cold-start levers this stacks on

- gzip on all four indexes (~7–12× fewer bytes)
- latency-aware endpoint ordering + failure cooldowns (no dead-endpoint tax)
- 6 s connect timeout, burst-2 tokens
- disk check before pool submission (cache hits are free)
- stage-1 prefetch ahead of stage-2 decoration

## Not yet batched (candidates, lower value)

- `OsmWaterIndex` / `OhmWaterIndex` — already use 0.05° tiles (25× the
  area), so one water tile ≈ one structure batch already.
- `OhmStructureIndex` — OHM's single endpoint is fragile; batching would
  help historical mode cold starts and can reuse the same pattern.
- `EsaWorldCoverIndex` / elevation tiles — different protocol (COG/XYZ),
  already tile-ranged.
