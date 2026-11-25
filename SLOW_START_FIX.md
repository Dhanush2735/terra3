# Slow starts — diagnosis + cure (2026-11-19)

## Owner suspicion
World/gameplay feels slow at start, smoother later.

## Reasons (confirmed in code, ranked)
1. Cold caches: every map tile (height, coastline/water,
   ESA ground, buildings x3 sources) is a live internet
   fetch on first visit. Disk cache makes revisits fast —
   hence "slow at start, smooth later".
2. Fetches failing = stampede (THE fixable bug, below).
3. Deliberate correctness waits: COAST WAIT up to 10 s
   (TerraChunkGenerator ~5149), ESA WAIT shared 5 s
   (~5509). Paid only while data is genuinely in flight;
   warm chunks skip instantly.
4. Structure decoration historically blocked up to 4 s per
   tile (cold log: 17 tiles = 68 s of stalled workers);
   already mitigated by prefetching during terrain gen.
5. Overpass behaviour: 20 s HTTP timeouts, rate limiters,
   60 s source pause after 5 consecutive failures.
6. Few Minecraft worker threads; while they wait on the
   network, spawn fills slowly.

Checked and NOT the problem: tile size TILE_DEG=0.05 deg
(3x3 prefetch already warms ~16 km), ESA index (already
has missing-tile negative cache + deduped futures),
TILE_WAIT_MS=500 on query paths.

## The stampede (root fixable cause)
In OsmWaterIndex (and its sibling OhmWaterIndex):
- Fetch failures were remembered NOWHERE. Each following
  chunk launched (or waited on) a fresh multi-endpoint
  fetch round for the same dead tile.
- getTileBlocking's timeout handler DELETED the pending
  entry even while the fetch was still running, so the
  next chunk started a duplicate download of the same
  tile. Waiters multiplied downloads instead of sharing.

During a server outage: 10 s waits x every coastal chunk,
plus parallel duplicate fetch rounds hammering a dead
endpoint through its rate limiter.

## Cure (implemented, parse-clean)
Both OsmWaterIndex and OhmWaterIndex:
- `failedAt` stamp per tile + FAIL_TTL_MS = 30 s:
  submitFetch records the outcome of every fetch;
  getTileBlocking (and Osm getTile) fail fast inside the
  window — no wait, no new fetch round.
- Batched water fetches (fetchWaterBatch) stamp their
  failed tiles too.
- Timeout no longer drops a still-running pending entry:
  one download per tile, shared by all waiters.
Effect: in an outage, coastal chunks stop paying 10 s
each almost immediately (first failure stamps the tile);
the COAST WAIT still waits properly when a fetch is
genuinely in flight and data is coming. The shore
smoother (smoothShoreMask) covers the painted result
when data truly never arrives.

## NOT changed (deliberately)
- The correctness waits themselves: lowering them trades
  away shore/ground correctness (NW12-NW16 lessons).
- Thread counts, rate limiters, Microsoft/structure
  paths (already fixed in earlier sessions).

## Owner proof wanted
Rebuild; fresh world or brand-new area; watch spawn fill
+ log: starts should reach playable smoothness notably
sooner, and during Overpass failures the log should NOT
show repeated long stalls per chunk. Compare roughly
against NW16 start if remembered. Old worlds unaffected
(disk caches already warm there).
