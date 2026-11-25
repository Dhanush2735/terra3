# JUHU COAST SPEED FIX — hedged endpoint race + shared coast wait

Date: 2026-11-21. Trigger: owner, NW(26) — "still slow download near Juhu".
This is the coastline speed fix that was pending its own go-ahead
(the Y-rule build did not touch it). Parse-clean under JDK 17
(only expected missing-classpath symbols; zero syntax errors).

## What was slow (code-traced, matches NW(24)/NW(26) logs)

The coastline/water download (`OsmWaterIndex`) asked its public
Overpass endpoints **one at a time, in series**. Each attempt could
burn a full 20 s read timeout (45 s for the 3×3-tile batch) before
the next endpoint was even tried, so one dead/slow endpoint cost
20–45 s before help arrived. NW(24) measured real Juhu batches at
39–66 s. Meanwhile chunk generation waited only **3 s** (COAST WAIT)
for that data, so shores were drawn from the fallback and baked
wrong (NW(26) shore audit: 175 fresh-ocean columns, only 89 wet).

## The fix (3 parts)

1. **Hedged endpoint racing** — `OsmWaterIndex.raceQuery()` /
   `attemptEndpoint()`. The first endpoint launches immediately;
   if it has produced nothing after **6 s**, the same query launches
   on the next endpoint **in parallel**. First HTTP 200 wins; the
   losers' connections are disconnected immediately. An endpoint
   that fails fast launches the next one instantly (no waiting out
   the hedge). Applies to both the cold-start batch
   (`executeWaterQuery`) and single-tile fetch (`fetchTile`).
   - Attempts run on a dedicated daemon pool (`Terra++OSM-Race`),
     never on the 4-thread download pool, so racing can't starve
     the fetchers that launched it.
   - Per-endpoint politeness unchanged: every attempt still takes
     that endpoint's shared rate-limiter token (1 req/s, burst 2)
     and single in-flight permit; 429/504 penalties and the 5-minute
     4xx quarantine are preserved.
   - The winner is remembered (`endpointIdx`), so the next query
     starts on the endpoint that answered fastest last time.
   - If every endpoint fails, the existing official-OSM-API
     coastline fallback door still runs, unchanged.

2. **A 4th endpoint: Taiwan (NCHC)** —
   `https://overpass.nchc.org.tw/api/interpreter`. Global coverage
   (OSM wiki, Overpass API instances) and geographically the
   closest public instance to India. Rotation order:
   overpass-api.de → kumi.systems → nchc.org.tw → maps.mail.ru.
   (`overpass.osm.ch` was tested and REJECTED: it answers fast but
   with empty results outside Switzerland.)

3. **Longer but SHARED coast wait** — `TerraChunkGenerator` COAST
   WAIT now calls the new `OsmWaterIndex.getTileBlockingShared(
   lat, lon, 12000)`. The 12 s budget belongs to the **tile, not
   the chunk**: the first coastal chunk may wait out the window,
   later chunks on the same tile only wait what is left, then fail
   fast while the shared fetch finishes in the background and
   AsyncWaterManager heals the chunk. This avoids the NW(23) jam
   (10 s *per chunk* parked every worker) while giving the now
   raced fetch a fair chance to land before the shore is drawn.

Worst case is still bounded: if all endpoints are truly dead, the
window closes after 12 s per tile and generation proceeds exactly
as before. Nothing about heights/biomes changed.

## Not covered (separate systems, unchanged)

- Structure downloads (`OsmStructureIndex`) still rotate endpoints
  sequentially — they share the same rate limiters, so building
  downloads near Juhu can still be slow even when the coast is
  fast. Racing them the same way is the obvious follow-up if
  buildings are the slow part in the next test.
- Server lag (NW(26): 98.5 s behind during Juhu generation) is a
  load pile-up issue, not download; untouched here.

## Sandbox note

Endpoint timings could NOT be measured from the sandbox: its
egress is filtered (overpass-api.de TLS killed, nchc DNS refused,
kumi hung to timeout). The design therefore doesn't depend on any
one endpoint being fast — it depends on not waiting for a dead
one in series, which the logs already proved was happening.

## Owner test (fresh jar)

1. Rebuild, then go somewhere coastal you have NOT visited in the
   last 14 days (the `osm_water` disk cache would otherwise serve
   Juhu instantly and prove nothing) — or delete the osm_water
   cache folder first. Juhu itself is fine after cache deletion.
2. `/tpll` to Juhu and watch the log:
   - Expect: `WATER BATCH [...]: N tiles in one request` arriving
     noticeably sooner after arrival than the old 39–66 s.
   - Expect: few or no `COAST WAIT [...]: coastline still not
     ready after shared 12s window` lines (a few on the very first
     chunks are OK).
   - Expect: `COAST SRC [...]: coastData=true` on coastal chunks.
3. Walk the waterline: the shore should follow the real coastline
   (compare NW(26): 89 wet / 86 dry). `/terracheck` on a sea chunk
   re-runs the shore audit.
4. If buildings/roads are still slow while the coast is fast,
   say so — that's OsmStructureIndex, next in line for the same
   racing treatment.
