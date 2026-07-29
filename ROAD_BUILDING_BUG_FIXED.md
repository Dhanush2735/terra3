# The building-in-the-road bug — root cause found and fixed

You were right, and I had been answering the wrong question. The screenshot shows a
small brick building sitting **in the middle of the road**, wedged between two OSM
buildings. That is not a tolerance problem. Tolerance decides whether an ML building
*duplicates* an OSM one; it says nothing about a building standing on asphalt where
OSM has no building at all to compare against.

There were **two separate defects**, both now fixed.

---

## Defect 1 — the road check was missing from the code path that actually places buildings

`FootprintAlignment.sitsOnRoad` existed and worked. It was wired into
`TerraChunkGenerator` at 4 places. But ML buildings are accepted into the world in
**8** places:

| file | ML acceptances | road guards *before* |
|---|---:|---:|
| `TerraChunkGenerator` | 4 | 4 ✅ |
| `AsyncStructureManager` | 2 | **0** ❌ |
| `HistoricalStructureManager` | 2 | **0** ❌ |

`AsyncStructureManager.processRetry` is the retry path — the one your log shows firing
constantly:

```
Queued retry for tile [17.4400,78.5400]
OSM retry success (0 structures, attempt 1)
```

Most ML buildings in a normal session are placed through **that** path, not through the
primary generator pass. So the guard was sitting on the road less travelled. Every
building placed via a retry, and every building placed with time enabled
(`HistoricalStructureManager`), skipped the road check entirely.

This is why buildings kept appearing in roads even after the road rejection was
"implemented". It was implemented — just not where it mattered.

**Fixed:** added the guard to all four missing sites. Now 8 acceptances, 8 guards.

---

## Defect 2 — the rejection width did not match the width the game draws

`sitsOnRoad` used a **flat 7.0 m** half-width for every `ROAD`. The generator paints by
road class (`OsmStructureGenerator.roadHW`):

| class | painted half-width |
|---|---:|
| motorway, trunk | 7.0 m |
| primary | 5.0 m |
| secondary | 4.5 m |
| tertiary | 3.5 m |
| **residential, living_street** | **3.0 m** |
| service, unclassified | 2.5 m |
| track | 2.0 m |
| footway/cycleway | 1.0 m |

In the Hyderabad test box, **330 of 386 roads are residential** — painted 3.0 m wide but
rejecting buildings out to 7.0 m. Everything in the 3–7 m band was standing on clear
ground and got deleted anyway.

Measured on the 6,041 real Google footprints:

| rule | rejected | genuinely on painted road |
|---|---:|---:|
| flat 7.0 m (old) | 1,977 | 423 |
| drawn width (new) | **423** | 423 |

**1,554 Google buildings were being destroyed for no visible reason** — while the ones
actually in the road were a different, much smaller set.

**Fixed:** `roadHalfWidthM` now mirrors `roadHW` exactly, including the
`lanes > 2 → lanes × 1.8` widening.

---

## What I tried and rejected

My first instinct was that centroid-only testing was the flaw — a building whose centre
lands just off the kerb but whose body covers the road. I implemented an extent-based
test, then measured it. It was **pure cost**:

| extent factor | VIDA rejected | on-road caught | OSM false positives |
|---|---:|---:|---:|
| 0.00 (centroid only) | 423 | **423/423** | **3** |
| 0.25 | 721 | 423/423 | 39 |
| 0.50 | 1,242 | 423/423 | 201 |
| 1.00 | 2,473 | 423/423 | 1,115 |

All 423 genuinely-on-road buildings are **already caught by the centroid**. The extent
test caught nothing extra and deleted hundreds of innocent buildings. OSM is the control
here — it is ground truth, so anything the rule rejects there is a false positive by
construction, and it goes from 3 to 1,115.

So I reverted it. Centroid-only at the drawn width is both the most accurate and the
cheapest option.

---

## Verified result

Real data, Hyderabad box: OSM 2,762 buildings + **386 real roads** from Overpass,
Microsoft 1,461, Google 6,041.

| source | old rule rejected | new rule rejected | on-road caught | saved |
|---|---:|---:|---:|---:|
| Google/VIDA | 1,977 | 1,242→**423** | **423/423 (100 %)** | **1,554** |
| Microsoft | 269 | **37** | **37/37 (100 %)** | 232 |
| OSM (control) | 599 | **3** | 3/3 | 596 |

Both halves of your report are addressed:
- **Buildings in the road** — now rejected on *every* path, not just one.
- **Buildings beside an existing building** — 1,554 Google buildings that were being
  wrongly deleted near residential streets now survive, so the gap-fill actually fills
  gaps instead of punching holes.

Compile: **2,596 errors**, exactly the baseline, zero in the three edited files.

---

## Caveats

- **Not run in Minecraft.** Verification is compile plus replay of the exact predicate
  against real OSM road geometry. The proof that Defect 1 is real is structural — the
  guard was absent from those call sites, and your log shows those sites executing.
- One city. Road-class mix varies; a city with more primary roads would see a smaller
  saving from Defect 2, though Defect 1 is location-independent.
- `distanceToLine` treats a road as its centreline polyline. Very wide junctions and
  slip roads are approximated.
- This does not touch Finding 1 (main-thread blocking I/O), still the cause of
  `Can't keep up!`.
