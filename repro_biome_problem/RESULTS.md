# Recreating the Juhu biome-slowness problem (sandbox, no Minecraft)

Owner asked: "can you recreate the problem?" — yes, here.

## What this is

`RecreateBiomeProblem.java` rebuilds the biome step outside the
game, using the mod's OWN compiled code from the project jar
(`B/eternal/libs/terraplusplus-2.0.0-alpha.jar`):

- real BTE Dymaxion projection (timed separately: 0.38 µs/call)
- real `OsmWaterIndex.CoastlineSegment` distance maths
- the same 9-tile coastline scan loop as `classifyCoastline`
- an elevation stand-in (512×512 decoded tile, bilinear; sampled
  twice per classify like the Y rule does at Juhu's ~3 m)

Then it runs the two versions of `assignBiomes` (world height
4,064 = 254 sections, 16 quarts per chunk):

- OLD: classify + shore probes re-run for every section
- NEW: the 16 quarts classified once (the fix now in the source)

The coastline shape is synthetic (the sandbox cannot reach
Overpass), matched to the real load from the NW(27) log:
**1,771 segments** in the 3×3 tiles around Juhu, sea to the
west. Segment count is what costs time, not the exact shape.

Run it again:

```
CP="/home/user/B/eternal/libs/terraplusplus-2.0.0-alpha.jar:/tmp/repro/META-INF/jarjar/*"
javac -encoding UTF-8 -cp "$CP" -d /tmp/repro/classes RecreateBiomeProblem.java
java -cp "/tmp/repro/classes:$CP" RecreateBiomeProblem
```

(jarjar deps must be extracted once from the jar's
`META-INF/jarjar/` into `/tmp/repro/` — `jar xf` does it.)

## Result (this sandbox, 2026-11-21 — full output in results.txt)

| chunk kind | OLD classify calls | OLD time/chunk | NEW classify calls | NEW time/chunk |
|---|---|---|---|---|
| Beach (Juhu shore) | 10,668 | **678 ms** | 42 | 2.5 ms |
| Inland 60 m east (worst) | 73,152 | **4,482 ms** | 288 | 18.7 ms |
| Ocean 60 m west | 8,128 | **501 ms** | 32 | 2.0 ms |

Speed-up ≈ **240–270×** on the biome step. Scaled: 100 inland
coastal chunks = **7.5 minutes of biome work (old, 1 thread)**
vs **1.9 seconds (new)**. The game spreads chunks over several
worker threads, but it also does far more per chunk than this
step — the pile-up direction is the same, and it matches the
"server running 127.7 s behind" in NW(27).

Per `classify()` ≈ 61 µs here, ~all of it the coastline scan
(1,771 signed distances). For scale, the whole terrain height
grid (4,096 classify calls) works out to ~0.25 s/chunk at the
coast — the old biome step was up to **18× the entire terrain
grid**, spent repeating answers it already had.

## Honest limits

- Not the game: no chunk painting, structures, lighting, or
  network waits are in this bench. It recreates the biome
  step's CPU cost, nothing else.
- Projection toGeo is excluded from the loop totals (adds
  <1%: ~28 ms worst case to OLD, ~0.1 ms to NEW).
- Numbers will differ on the owner's machine; the 254×
  repetition is exact code structure, not a machine property.
- Final proof is still the owner's log after rebuild:
  fewer/smaller "running behind" warnings at Juhu, and no
  large `BIOME TIME` lines.
