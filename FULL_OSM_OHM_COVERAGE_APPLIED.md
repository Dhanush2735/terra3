# Full OSM/OHM coverage — applied and tested

Goal: use OSM and OHM to their practical limit (all renderable way/node/relation
families) with **no performance loss**, including cold starts with an empty
`terra_cache`. Compile is at the 3,202-error Minecraft-absent baseline with
**zero new error kinds**; the OsmStructureIndex subtree compiles standalone with
zero errors and was exercised against live Overpass.

---

## 1. What is now fetched (previously ignored)

`OsmStructureIndex` query, per 0.01° tile — new families in **bold**:

| tag family | values fetched | new? |
|---|---|---|
| `building`, `building:part` | all (way + relation) | — |
| `highway` | all ways + `street_lamp` nodes | — |
| `railway` | rail, light_rail, subway, tram, **narrow_gauge, funicular, monorail, platform** | partly |
| `bridge` | all (way + relation) | — |
| `barrier` | wall, fence, hedge, **retaining_wall, city_wall, guard_rail, handrail** | partly |
| `landuse` | + **allotments, cemetery, quarry, recreation_ground, village_green, plant_nursery, flowerbed, farmyard** | partly |
| `leisure` | + **golf_course, recreation_ground, dog_park, swimming_pool, track, nature_reserve** (+ relations) | partly |
| **`natural`** | **wood, scrub, heath, grassland, beach, sand, bare_rock, scree, shingle, tree_row (ways+relations), tree (nodes)** | ✅ all new |
| **`man_made`** | **pier, breakwater, groyne, embankment, dyke, tower, chimney, water_tower, storage_tank, silo, lighthouse, mast, flagpole, communications_tower** | ✅ all new |
| **`aeroway`** | **runway, taxiway, apron, helipad** | ✅ all new |
| **`power`** | **tower, pole (nodes)** | ✅ all new |
| **`amenity`** | **parking, marketplace, grave_yard** | ✅ all new |
| **`waterway`** | **dam, weir** (structures; rivers/lakes were already in OsmWaterIndex) | ✅ new |

The biggest single win is `natural=wood` — the majority of real-world forests
are mapped this way, not as `landuse=forest`, and were previously invisible.

The OHM query (`OhmStructureIndex`) was widened identically (minus point
clutter that OHM rarely has), so historical worlds gain the same coverage.

## 2. New structure types and rendering

11 new `StructureType` values, all rendered by `OsmStructureGenerator` in both
the chunk path (`processNonBuilding`) and the instant single-block path
(`placeSingleBlock`):

| type | rendering |
|---|---|
| SAND | sand surface |
| ROCK | stone/cobble surface (quarries, scree, bare rock) |
| TREE_ROW | trees every ~5 columns along the line |
| TREE | single mapped tree at the node |
| PIER | plank deck over water with oak piles; breakwater/dam/embankment = solid stone fill to the bed |
| TOWER | typed column: comms tower 40 blocks w/ beacon, chimney 25 (brick), lighthouse 18 (white, beacon), water tower 14, tank/silo r=4 cylinder; honours `height=` tag |
| PYLON | 22-block iron lattice (pole: 8) |
| RUNWAY | 44 m paved strip with centreline markings (taxiway 22 m); honours `width=` |
| APRON | paved area (also helipads) |
| POOL | water with quartz rim and floor |
| PLATFORM | raised smooth-stone platform (area or 4 m strip) |

All existing overlap protections apply (`hasBuildingAbove`, bridge zones,
water checks). Config gating: TOWER→buildings, PIER→bridges, PLATFORM→railways,
POOL→gardens, PYLON→streetLights, the rest→landuse. `isEnabled` switches
updated in `OsmStructureIndex` and `StructureTickHandler` (both exhaustive).

## 3. Accuracy fixes shipped alongside

- **`distanceToLine` cos(lat) bug fixed.** Point-to-segment distance was
  computed in raw degrees without longitude shrink; N-S roads rendered up to
  ~36% too narrow at London's latitude. Now scaled per-call.
- **`width=` tag honoured.** New `explicitWidth` field (post-construction, like
  `innerRings`, so no constructor churn). Used by road placement, bridges,
  runways, piers, platforms, and mirrored in `FootprintAlignment` so ML-building
  road-exclusion matches the painted width. Capped at 60 m against tag typos.
- ML buildings can no longer be accepted on runways (`FootprintAlignment`).

## 4. Performance: why wider ≠ slower

Measured tonight against live public Overpass (a bad night: overpass-api.de
returning 504, kumi timing out — worst-case conditions):

| lever | effect |
|---|---|
| **gzip (`Accept-Encoding: gzip`)** in all four indexes (OSM/OHM × structures/water) | Overpass JSON compresses ~7–12×; a 1.1 MB tile moves as ~90–150 KB. On the wire the widened query is **smaller than the old narrow query uncompressed** |
| **Latency-aware endpoint ordering** | tiles no longer round-robin blindly into a dead endpoint; the endpoint whose limiter is ready soonest is tried first, tie-broken to the last endpoint that succeeded |
| **Failure penalties** (8–10 s cooldown on connect failure / bad status) | one dead endpoint no longer costs every subsequent tile a connect timeout |
| **Connect timeout 25 s → 6 s** (read timeout unchanged) | dead endpoints fail fast on cold start; slow-but-alive responses keep the full window |
| **Cold-start burst 1 → 2 tokens/endpoint** | the first screenful of tiles downloads in parallel across endpoints; steady-state rate (1 req/s/endpoint) unchanged |
| **Cache dir v2** (`osm_structures_v2`, `ohm_structures_v2`) | old cached responses from the narrow query can't serve feature-less tiles for 30 days after upgrading |

Live results (standalone harness, real Overpass, empty cache):

```
Hyderabad dense tile  cold: 3.1 s → warm mem: 1 ms → warm disk: 44–374 ms
Juhu Beach            n=55  {SAND, POOL, PARKING, ...}
Begumpet Airport      n=152 {RUNWAY=12, APRON=2, ...}
Marine Drive          n=265 {PLATFORM=3, RAILWAY=19, BRIDGE=11, ...}
KBR Park              n=19  {FOREST=4 (natural=wood), PARK, ...}
element count old vs new query, same tile: 1,888 → 1,904 (+0.8%)
```

Element counts barely move because buildings+highways dominate any urban tile;
the new families are the long tail. Payload +32% raw, **−85% after gzip**.
Parse cost is one extra tag lookup per element on an already-linear parser.

## 5. Files changed

- `generator/osm/OsmStructureIndex.java` — query, 11 enum values, parser
  families, gzip, endpoint ordering, penalties, cos(lat) fix, `explicitWidth`,
  cache v2, point-feature node rule
- `generator/osm/OsmStructureGenerator.java` — 11 placement methods, both
  dispatch switches, new block constants, `roadHWFor`, bridge width honour
- `generator/osm/StructureTickHandler.java` — isEnabled exhaustive switch
- `generator/osm/FootprintAlignment.java` — explicitWidth + runway exclusion
- `generator/historical/OhmStructureIndex.java` — widened query + parser,
  gzip, cache v2, point-feature rule
- `generator/water/OsmWaterIndex.java`, `generator/water/OhmWaterIndex.java` — gzip
- `generator/util/OverpassRateLimiter.java` — cold-start burst 2

## 6. Deliberately not fetched

`landuse=residential|industrial|commercial` (huge polygons, no meaningful
block rendering), `boundary=*`, `route=*` relations, `power=line` ways
(catenary rendering between pylons is a separate feature), `amenity` POI nodes
(restaurants etc. — nothing to build). These are excluded by whitelist design,
not by accident, to keep tiles small; adding any of them later is a one-line
query change plus a placement method.
