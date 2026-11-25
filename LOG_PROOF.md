# Log proof for OSM / Microsoft / VIDA (owner directive)

Every fetch and every placement is now written to the game log.
Search `latest.log` for **[TerraStruct]**.

## Lines you will see

Fetches (data arrived):
- `[TerraStruct] OSM FETCHED [lat,lon] features=N (api.osm.org)`
- `[TerraStruct] OSM FETCHED [lat,lon] features=N (overpass)`
- `[TerraStruct] OSM DISK [lat,lon] features=N` — served from
  the disk cache instead of the internet
- `[TerraStruct] OSM FAILED [lat,lon] all endpoints failed`
- `[TerraStruct] MICROSOFT FETCHED [lat,lon] buildings=N`
  (N=0 is written too — "Microsoft has nothing here" is proven,
  not silent)
- `VIDA shard ... buildings retained ...` — VIDA already logged
  every shard fetch; unchanged

Placements (buildings put into the world):
- `[TerraStruct] PLACED chunk [x,z] osm=A vida=B microsoft=C
  structures=N` — generation road, per-source counts
- `[TerraStruct] AsyncStruct tile [lat,lon]: osm=A vida=B
  microsoft=C combined=N ... queued=Q` — retry road
- `[TerraStruct] PLACING chunk [x,z] structures=N` — every
  actual painting of a chunk; all roads end here

## What changed

- The old fetch logs stopped after the first 20 (OSM) / 30
  (Microsoft) lines ever. Caps removed; every fetch logs now.
- Per-source placement counts did not exist; added at both
  merge points (generation + async retry).
- All lines share the `[TerraStruct]` prefix for easy search.

## How to read the result

- A source whose FETCHED lines never appear = it is never
  fetched (switch/index problem).
- FETCHED with N=0 everywhere = the source has no data there.
- PLACED with `vida=0 microsoft=0` in an area where OSM is
  sparse = the gap-fillers fetched but everything was dropped
  (overlap/road/rectangle checker) — that is problem 3's test.

Source-only, not compiled. Build with `gradle jarJar`.
