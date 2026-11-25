# Cold-start research — how the wider world solves it (2026-11-21)

Question: how do map systems, game streaming, and large web services
make the FIRST visit to a place fast? Findings mapped onto Terra3.

## Verdict in one line

Everyone solves cold start the same four ways: **(1) ship static,
partially-readable data instead of asking a live query server,
(2) pre-warm/seed before the data is needed, (3) coalesce duplicate
requests and serve stale while refreshing, (4) mask the remaining
tail latency (hedging, good-enough first answers).** Nobody solves
it by waiting longer inside the request path.

## Why cold start hurts us (the maths)

A chunk waits on many tiles and sources at once. Google's "Tail at
Scale" shows why that is brutal: if each backend is slow only 1% of
the time, a request touching 100 backends is slow 63% of the time —
the user sees the *worst* source, not the average one [1](https://www.barroso.org/publications/TheTailAtScale.pdf).
A cold Juhu arrival touches elevation, ESA, coast, water, buildings
and Microsoft tiles across ~10 endpoints: the slowest door decides.

## What the research says, pattern by pattern

### 1. Static files with partial reads beat live queries
- PMTiles is designed so a client fetches only the tile/metadata it
  needs from one static archive over HTTP Range requests, with the
  layout minimising overhead requests while panning/zooming [1](https://docs.protomaps.com/pmtiles/).
- COGs work the same way for rasters: internal tiling + overviews +
  HTTP range requests let clients read just the relevant part of a
  file [1](https://docs.ogc.org/per/21-025.html). ESA WorldCover is
  exactly this: 3°×3° COGs, 1024-pixel internal tiles, overviews at
  2/4/8/16/32/64 [2](https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/docs/WorldCover_PUM_V2.0.pdf).
  → Our PMTiles/COG range reading is the right shape. Keep it;
  cache headers/directories/offsets permanently.
- Overture publishes monthly cloud-native GeoParquet + PMTiles on
  S3/Azure that you "read in place, with no server or API in
  between", indexed by a STAC catalogue [1](https://docs.overturemaps.org/getting-data/cloud-sources/).
  Its files are spatially sorted and bbox-filtered reads fetch only
  the covering row groups — one Bristol query pulled 145k buildings
  from a 2.5-billion-building theme in 3.4 s [1](https://www.technicalgis.com/tutorials/geoparquet-duckdb-overture-maps/).
  → Buildings/water can come from static extracts, not Overpass.

### 2. Precompute extracts offline; don't query the planet live
- Geofabrik serves pre-cut regional OSM extracts that are normally
  updated every day [1](https://download.geofabrik.de/), explicitly
  so people can self-host/analyse instead of hitting live APIs
  [2](https://www.geofabrik.de/data/download.html).
- Ready-made coastline water polygons are built from the OSM planet
  with osmcoastline (assembled, repaired, split): ~942 MB full
  resolution, ~58 MB simplified [1](https://osmdata.mapki.com/).
  → An offline builder (planet/extract → our tile formats) can
  produce regional "Terra Data Packs"; the mod downloads files,
  Overpass becomes fallback/freshness check only.

### 3. Seeding/warming is a named, standard practice
MapProxy "creates all tiles on demand" by default, and its seed tool
exists precisely to pre-generate tiles for commonly requested areas
and refresh old ones ahead of time [1](https://mapproxy.github.io/mapproxy/latest/seed.html).
→ This is the `/terrawarm` idea, with a warning from practice:
tile counts explode ×4 per zoom level, so seed by coverage and
priority (nearest/critical first), never the whole pyramid blindly.

### 4. Coalesce, serve stale, refresh in background
- Stampede control in production systems combines request
  coalescing/single-flight, stale-while-revalidate, jittered TTLs
  and background refresh — "no single tactic is enough" [3](https://softwarepatternslexicon.com/caching-patterns-and-invalidation/consistency-and-stampede-control/stampede-prevention/).
- RFC 5861 standardises serving a stale response immediately while
  revalidating in the background, and serving stale on origin error
  [2](https://www.rfc-editor.org/rfc/rfc5861.txt).
→ One download per tile key shared by all chunks (we largely do
this); stale tiles should generate instantly and refresh behind,
with jittered expiries so whole regions don't expire together.

### 5. Hedge the tail — carefully
Dean & Barroso: send a second request only after the first has
been outstanding past ~the 95th percentile, cancel the rest; in
their BigTable case p99.9 fell 1,800 ms → 74 ms for ~2% extra
requests, and they warn naive always-duplicate implementations
add unacceptable load [1](https://www.barroso.org/publications/TheTailAtScale.pdf).
→ Keep our 6 s hedges (water + structures races, Cure 1/2), keep
cancellation, and don't hedge everything from t=0.

### 6. A local source is the heavy hammer
A self-hosted Overpass instance is initialised from a planet file
or regional extract (e.g. a Geofabrik .osm.pbf) and then kept
current with diff updates [1](https://github.com/wiktorn/Overpass-API).
A full-planet import is days of work [5](https://hub.docker.com/r/b1tw153/overpass-api),
a region is practical. → Sensible for a dedicated server or the
offline pack builder; overkill for a single-player client.

### 7. Games stream predictively, under a budget
Chunk-streaming systems prefetch in the movement direction and cap
loads per frame, with separate load/unload radii [3](https://esengine.cn/en/modules/world-streaming/).
→ Warm ahead along velocity/camera, budgeted — don't just warm a
static circle when flying.

## Recommended Terra3 architecture (priority order)

1. **Readiness registry + `/terrawarm`** (MapProxy-seed pattern):
   one state machine per tile key; spawn/`/tpll` warming in rings;
   critical (elevation/coast/ESA) before decorative (buildings).
2. **Stale-while-revalidate everywhere + build ledger**: stale =
   generate now; missing = provisional chunk recorded by missing
   source, targeted re-run of only the dependent pass when ready.
   (SWR is only safe because the ledger guarantees the repair.)
3. **Static-first sources**: coast/water and structures move to
   precomputed regional packs (Geofabrik/Overture/Microsoft →
   offline builder → versioned files on static hosting); PMTiles/
   COG range reading stays for elevation/ESA; live Overpass and
   the OSM API remain as fallback and freshness checks.
4. **Predictive warming** along movement once 1–3 are stable.

Per source today: elevation (PMTiles ranges) and ESA (COG ranges)
are already the researched shape — optimise caching of headers/
offsets. Microsoft quadkey files are already the static-pack
pattern (33 MB in ~5 s in our test). The two Overpass doors are
the only true cold-start offenders (22–34 s server think), which
is why Cures 1+2 (racing) help but only packs remove them.

## Risks the research flags

- Hedging/duplication adds origin load; hedge late (p95-style),
  cancel losers, respect endpoint limits.
- Seeding high zooms over big areas explodes storage (×4/zoom).
- Stale/provisional generation without a guaranteed refresh is
  how fossil chunks happen (our NW(23) lesson).
- Static packs go stale: version them (release date in the
  manifest), refresh in background, attribute OSM/Overture/
  Microsoft per their licences.
- A local Overpass is only worth it region-sized.

## How to judge success (bench protocol)

Cold JVM + empty cache, cold region, then warm repeat, at Juhu
(coast), Gandikota (inland), open ocean: time-to-first-chunk,
time until critical sources ready in view distance, HTTP request
count (target: 1 per tile key), MB moved, chunks blocked >100 ms
(target: 0), provisional chunks created and time-to-heal.
