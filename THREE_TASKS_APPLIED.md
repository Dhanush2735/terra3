# Three tasks — applied and tested

All three changes are **applied to the source**. Compile is at the 2,596-error
post-prune baseline with **zero errors in either edited file**.

Files changed:
- `generator/osm/FootprintAlignment.java` (task 1)
- `generator/osm/SourceCooperativeIndex.java` (tasks 2 and 3)

---

## Task 1 — the ± building placement tolerance constant

### The constant

```java
BASE_TOLERANCE_M = 2.5   // was 6.0
MAX_TOLERANCE_M  = 7.0   // was 14.0
```

### How it was derived

Calibrated against real data, not assumption: OSM (live Overpass, 2,762 buildings),
Microsoft (quadkey `123301331`, 1,461) and Google/VIDA (shard
`4308537468510011392`, 6,041) over the same 1.1 km box in Hyderabad.

The old 6.0 m came from the file's own claim that ML footprints are "commonly 2–5 m
off". Measured naive nearest-neighbour offset is 6.4 m (Microsoft) / 6.7 m (Google) —
about double. But widening was the wrong response. Measuring only pairs **confirmed to
be the same building** (true polygon IoU ≥ 0.30) gives a much tighter distribution:

| pair | median | p90 |
|---|---:|---:|
| OSM ↔ Microsoft | 5.07 m | 8.74 m |
| OSM ↔ Google | 3.82 m | 6.52 m |
| Google ↔ Microsoft | 4.37 m | 8.33 m |

The large naive median is inflated by ML buildings that have **no OSM counterpart at
all** — new buildings that must be kept. Calibrating on those is exactly what made 6 m
delete real buildings. In this area the median OSM footprint is 149 m² (~12 m across),
so 6 m is half a building width and the nearest *neighbour* falls inside tolerance.

### Sweep (wrong = real buildings deleted, FN = ghosts placed)

| base / max | wrong | FN |
|---|---:|---:|
| 6.0 / 14.0 (old) | 380 | 16 |
| 4.0 / 10.0 | 125 | 20 |
| 3.0 / 8.0 | 61 | 24 |
| **2.5 / 7.0 (chosen)** | **40** | **27** |
| 2.0 / 6.0 | 36 | 30 |
| 1.0 / 4.0 | 18 | 36 |

2.5 m is the knee: 89 % of wrong deletions removed for 11 extra ghosts. Below it the
curve flattens and ghosts rise faster than deletions fall.

### Hierarchy

A per-source-pair tolerance was tested (Google 2.0, Microsoft 3.0, matching their
different measured error). It scored **no better** than the single flat constant —
43 wrong / 26 FN vs 40 / 27 — so the flat value is used and the hierarchy stays
enforced by **order of application**, which is unchanged and verified still present in
all four paths:

```
OHM -> OSM -> VIDA(Google) -> Microsoft
  TerraChunkGenerator:599/630, :2091/2159
  AsyncStructureManager:265/283/285
  HistoricalStructureManager:314/373/380
```
VIDA is tested against OSM; Microsoft is tested against OSM **and** VIDA. Gap-fill only,
never overriding a higher source.

### Secondary fix in the same file

`Print.of()` computed area inconsistently: VIDA passed true polygon area while OSM and
Microsoft fell back to bbox area. Since tolerance scales with `sqrt(area)`, the same
building got a different tolerance depending on its source (bbox is a median 1.38× the
true area, p90 2.01×, worth up to 1.84 m). All three now use bbox area — the only
measure all three can supply identically.

### Measured result

| | before | after |
|---|---:|---:|
| real buildings wrongly deleted (IoU < 0.10) | 380 | **42** |
| deleted with *zero* overlap | 52 | **4** |
| ghosts placed (FN) | 16 | 27 |
| precision MSFT / VIDA | 63.5 % / 60.6 % | **78.5 % / 78.1 %** |
| recall MSFT / VIDA | 98.9 % / 99.6 % | 97.9 % / 99.3 % |

**89 % fewer real buildings destroyed**, recall essentially unchanged.

---

## Task 2 — Google buildings now use the same floors/roof methods as OSM and Microsoft

### The bug

`VidaBuilding.toOsmStructure()` invented its own values instead of using the shared
estimator:

```java
floors = round(((area/65) + rand*1.5) / 4);  if (area<300) floors = min(2,floors);
roof   = area<220 ? "pyramidal" : area<550 ? "gabled" : "flat";
height = floors * 3.5;
```

OSM and Microsoft pass `0` / `null` for anything the dataset does not know, and
`OsmStructureGenerator` fills it in via `estimateLevels()` and `resolveRoofType()`.
Google bypassed both.

Measured across the 6,041 real Google footprints, the two methods **disagree on 83 %
of buildings**:

| | Google's own formula | canonical `estimateLevels()` |
|---|---|---|
| floors | 1:5986 2:44 3:8 4:1 5:1 8:1 — **99.1 % single-storey** | 1:1026 2:3043 3:1900 4:70 5:2 |
| roof | pyramidal 5841, gabled 185, flat 15 — **96.7 % pyramidal** | flat 4028, gabled 2013 |

So Google buildings rendered as a field of single-storey pyramid huts while OSM and
Microsoft buildings of identical footprint next door got 2–3 flat-roofed storeys.
`height = floors*3.5` also contradicted the generator's `FLOOR_HEIGHT = 5`.

### The fix

```java
return new OsmStructureIndex.OsmStructure(
    BUILDING, "yes", null, nodes, true, uniqueId,
    null,   // material  - generator picks by subtype/hash
    0,      // levels    - generator uses estimateLevels()
    0,      // height    - generator uses levels * FLOOR_HEIGHT
    null,   // roofShape - generator uses resolveRoofType()
    null, null, 0, false);
```

Identical to the contract Microsoft's `OvertureBuilding.toOsmStructure()` already
follows. Verified output after the change:

```
floors:        {1:1026, 2:3043, 3:1900, 4:70, 5:2}
wall heights:  {5:1026, 10:3043, 15:1900, 20:70, 25:2}   (levels * FLOOR_HEIGHT)
```

Exactly what an OSM or Microsoft building of the same footprint receives.

---

## Task 3 — cold-start performance (no cache files)

### The bottleneck

`fetchShard` downloaded the **entire shard** before the R-tree could be used. The
Hyderabad shard is **1,217 MB** (not 574 MB as previously recorded). The R-tree
optimisation added earlier only helped *after* that download finished, so a cold start
paid full price.

### Two changes

**(a) HTTP range cold-start path — `fetchViaHttpRange()`**

`data.source.coop` advertises `accept-ranges: bytes` and returns `206` (verified
live). FlatGeobuf's layout — `magic(8) | headerSize(4) | header | rtree | features` —
makes partial reads possible, and the R-tree is a breadth-first array of 40-byte nodes,
so each level is locatable arithmetically without reading the levels below.

The key property that makes this cheap: FlatGeobuf orders nodes by **Hilbert curve**,
so the frontier matching a spatial box is nearly contiguous. Measured:

| level | frontier | contiguous span | whole level |
|---|---:|---:|---:|
| L0–L4 | 1 … 272 | ≤ 0.011 MB | ≤ 0.75 MB |
| L5 | 3,584 | 0.162 MB | 11.93 MB |
| L6 | 51,328 | 2.587 MB | 190.87 MB |

So **one request per level** spanning min→max index costs at most 2.6 MB. An earlier
version fetched each contiguous run separately (79 requests, 11.7 s); one-per-level cut
it to **10 requests, 1.9 s**.

Falls back to the full download on any non-206, unexpected layout, or degenerate span.
Never throws for a data reason.

**(b) `RETAIN_HALF_DEG` 0.05 → 0.02**

Cost grows with the square of the window and it dominated the payload:

| half-deg | box | leaf hits | tree | features | total |
|---|---|---:|---:|---:|---:|
| 0.05 | 11.1 km | 314,920 | 13.63 MB | 138.34 MB | 151.98 MB |
| **0.02** | **4.5 km** | **49,582** | **2.21 MB** | **13.81 MB** | **16.02 MB** |
| 0.01 | 2.2 km | 15,880 | 0.73 MB | 10.28 MB | 11.01 MB |

4.5 km is still ~11× the visible radius at view distance 12 (~192 m).

### Measured cold-start result

Verified against the live server, simulating the exact Java logic:

```
FINAL: 10 requests, 16.57 MB, 1.9s
       49582 leaves, 49582 features decodable  (100%)
```

| | before | after |
|---|---:|---:|
| data before first building | 1,217.04 MB | **16.57 MB** (−98.6 %) |
| requests | 1 (huge) | 10 |
| time @ 49 MB/s (sandbox) | ~24.7 s | **~1.9 s** |
| time @ 25 Mbit/s (typical home) | **~6.5 min** | **~7 s** |

The walk is latency-bound rather than bandwidth-bound, so the saving grows on slower
connections.

---

## Verification performed

- **Compile:** whole project, `javac 17`, `-Xmaxerrs 100000` → **2,596 errors**, identical
  to the post-prune baseline, all pre-existing Minecraft/Forge "package does not exist".
  **Zero** errors in either edited file (checked separately, excluding dependency-only
  errors).
- **Task 1:** re-scored the ported predicate against the real three-source dataset with
  the new constants and the bbox-area change. 380 → 42 wrong deletions.
- **Task 2:** ran the canonical `estimateLevels()` over all 6,041 real Google footprints
  and confirmed the resulting distribution matches the OSM/Microsoft path.
- **Task 3:** simulated the exact new logic against the live shard — 10 requests,
  16.57 MB, 1.9 s, all 49,582 features decodable.

## Caveats

- **Not run in Minecraft.** The sandbox cannot execute MC or the ForgeGradle remap.
  Verification is compile + real-data replay of the changed logic.
- **One city.** Hyderabad's dense small footprints are close to worst-case for a
  distance-based test. The direction of the tolerance result held at every truth
  threshold from 0.10 to 0.50, but exact magnitudes are specific to this tile.
- **Task 3 depends on the CDN.** If `data.source.coop` stops honouring range requests
  the code silently falls back to the full download — correct, just slow again.
- **The tolerance trade is real:** ghosts went 16 → 27 in the test box. I judged 338
  fewer destroyed buildings worth 11 more duplicates; if you prefer the opposite,
  3.0/8.0 gives 61 wrong / 24 FN.
- **None of this touches Finding 1** (blocking network I/O on the server main thread),
  which is still the cause of `Can't keep up!`. Task 3 reduces cold-start *download*
  time, which is a different problem from tick debt.

Reproduction data: `/home/user/tolerance_test/` (`align.py`, three `*_box.json`,
`cache.pkl`). Toolchain cached at `/home/user/.toolchain/`.
