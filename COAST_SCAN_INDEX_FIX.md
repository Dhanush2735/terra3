# Coast scan index fix (NW29) — 22 Nov 2026

## The proven problem (NW28, in-game at Juhu)

Beach/coastline chunks were slow because of GENERATING, not
fetching. `OsmWaterIndex.classifyCoastline` decided land/sea for
every point by scanning **every coastline segment in the 3x3 tile
neighbourhood** (~1,771 segments at Juhu). Its only filter,
`mightBeNear(lat, lon, 0.5)`, was 0.5 **degrees** (~55 km) wide, so
it filtered out almost nothing. Measured in game: scan 31.9 µs of
the 32.8 µs classify, **136.6 ms of classification per coastal
chunk**, re-paid by every chunk along a shore. Inland and open-sea
chunks carry no segment field, so only beaches paid.

## The fix (OsmWaterIndex.java only)

Each `WaterTile` now lazily builds a **CoastGrid** the first time
it is classified, and keeps it for the tile's lifetime:

- Every coastline segment is filed into the grid cells its
  bounding box touches (cell ~0.001°, ~111 m; grid capped at
  ~48×48 cells per tile however large the stored geometry is).
- Segments whose bounding box touches >1024 cells (very long
  straight ways) go to a small always-measured "big" list.
- A query measures only the segments in the cells overlapped by
  a small box around the point (first box ±0.0016°, ~178 m),
  growing the box ×4 until either the tile's whole coastline
  extent is covered, or the best distance found is smaller than
  anything outside the box could possibly be (box half-size
  converted with the most poleward latitude in play = a safe
  lower bound). **The nearest segment found is exactly the one
  the old full scan would have found — answers do not change.**
- Candidates whose bounding box is already farther than the
  current best are rejected with a few comparisons, skipping
  the expensive trig distance.
- Tiles are visited centre-first; once a good nearest distance
  exists, neighbour tiles whose whole coastline bounding box is
  farther away are skipped with one check.
- Per-segment "seen" stamps (unique mark per query) make each
  segment measure at most once per query; the stamp counter is
  atomic, so concurrent chunk-generation threads cannot make
  each other skip segments.

Nothing else changed: same coastline data, same fetch path, same
LAND/OCEAN/COASTLINE/UNKNOWN rules, same 5 m coastline band.
`OhmWaterIndex` has a copy of the old scan but no generation-path
caller (present-day classification all goes through
`OsmWaterIndex`), so it was left alone.

## Proof (sandbox, real file compiled standalone)

The real `OsmWaterIndex.java` was compiled with JDK 17 (real
Gson jar; tiny stubs for Log4j / OverpassRateLimiter /
TerraCachePaths / HttpKeepAlive) and driven with a synthetic
Juhu-like shore: 1,517 segments in the 3x3 tiles around the test
point, including two very long segments to exercise the "big"
list. The old algorithm was reimplemented faithfully as the
reference.

- **Correctness: 30,000 random points — 0 answer mismatches.**
- Far points (nearest segment tens of km away): 0 mismatches.
- No-data area: UNKNOWN = UNKNOWN. On-the-line point:
  COASTLINE = COASTLINE.
- Concurrency: 8 threads × 20,000 points — 0 mismatches.
- **Speed: 63.5 µs → 2.16 µs per classification (~29×);
  a 64×64 paint grid drops from ~260 ms to ~8.8 ms.**

Sandbox cannot run the full Gradle/Forge build; the owner's
`gradle jarJar` remains the compile proof for the whole mod.

## Owner test

1. Rebuild (`gradle jarJar`), confirm jar in mods.
2. Juhu beach (19.0968, 72.8265), chunk with both sand and sea.
3. Run plain `/terracheck` and read the `TEST coastperf` lines:
   - `scanUs` should fall from **31.9** to roughly **1–3**.
   - `paintGridMs` should fall from **136.6** to the low tens
     or less.
   - The VERDICT should no longer name the coastline scan.
4. Eyeball the shore: land/sea/beach must look exactly as
   before (same answers by construction — report any change).
