# The ± tolerance problem — tested against real OSM / Microsoft / Google data

**Verdict: the tolerance is too LOOSE, not too tight. It is deleting real buildings.**

At the current settings, **380 buildings** in a single 1.1 km × 1.1 km test box are
deleted despite having almost no overlap with the OSM building they were matched
against. 52 of them have *literally zero* overlap — they are separate buildings that
OSM does not have, and they are silently lost.

---

## 1. What was tested, and against what

No synthetic data. Three real datasets over the identical box around Hyderabad
(`17.435–17.445 N, 78.535–78.545 E` — the same tile as the user's logs):

| Source | How obtained | Count in box |
|---|---|---:|
| **OSM** | live Overpass API, `way["building"]`, `out geom` | 2,762 |
| **Microsoft** | real `dataset-links.csv` → quadkey `123301331` (53 MB gz, 628,630 lines — matches the log exactly) | 1,461 |
| **Google/VIDA** | shard `4308537468510011392.fgb`, 4,771,676 features (matches the log exactly), R-tree walked over HTTP range requests | 6,041 |

The VIDA fetch is worth noting: rather than pull the 574 MB shard, I walked the
FlatGeobuf Hilbert R-tree with HTTP `Range` requests — 290 KB of tree reads to find
6,131 candidate leaves, then one 9.2 MB contiguous read. This is the same index the
mod's `readViaRTree` uses, so it also independently confirms that code path works.

`FootprintAlignment.isSameBuilding` was ported line-for-line to Python
(`/home/user/tolerance_test/align.py`) so the exact production logic could be swept.

### Ground truth

"Same building" is defined as **true polygon intersection-over-union ≥ 0.30**,
computed by grid sampling the actual footprint rings — not bounding boxes. This is
the physical question the code is trying to answer.

---

## 2. Measured positional error between sources

Nearest-neighbour centroid distance, ML footprint → closest OSM building:

| | min | p10 | p25 | **median** | p75 | p90 | max |
|---|---:|---:|---:|---:|---:|---:|---:|
| Microsoft | 0.04 | 2.99 | 4.33 | **6.40** | 10.53 | 17.18 | 56.94 |
| Google/VIDA | 0.08 | 2.44 | 4.02 | **6.74** | 11.95 | 18.12 | 61.88 |

The file's docstring claims Microsoft is "commonly 2–5 m off". **Measured median is
6.4 m** — the real error is roughly double the documented assumption, and the p90 is
17–18 m, above even the 14 m clamp.

This matters because of the building sizes involved. Hyderabad is dense: median OSM
bbox footprint is **149 m²**, i.e. about 12 m on a side. A 6 m tolerance is half a
building width, and neighbouring structures are often closer than that.

---

## 3. Accuracy of the current code

Current constants: `BASE_TOLERANCE_M = 6.0`, `MAX_TOLERANCE_M = 14.0`,
`IOU_THRESHOLD = 0.30` (bbox).

| | true duplicates | code says duplicate | TP | FN | **FP** | precision | recall |
|---|---:|---:|---:|---:|---:|---:|---:|
| Microsoft | 624 | 972 | 617 | 7 | **355** | **63.5 %** | 98.9 % |
| VIDA | 2,041 | 3,351 | 2,032 | 9 | **1,319** | **60.6 %** | 99.6 % |

Recall is excellent — ghost/doubled buildings are essentially solved. But **precision
is ~62 %**: about four in ten deletions are wrong.

**Which test causes it:**

| | centroid test fired | bboxIoU test fired | FP from centroid | FP from bboxIoU |
|---|---:|---:|---:|---:|
| Microsoft | 895 | 77 | **330** | 25 |
| VIDA | 3,241 | 110 | **1,259** | 60 |

**93 % of the wrong deletions come from the centroid-distance test.**

### Being fair to the code

Most false positives are partial overlaps, which are arguably the same building
mapped differently. Breaking them down by *true* overlap:

| true polygon IoU | Microsoft | VIDA |
|---|---:|---:|
| 0.20–0.30 (borderline, probably same) | 167 (47 %) | 591 (45 %) |
| 0.10–0.20 (doubtful) | 119 (34 %) | 417 (32 %) |
| 0.00–0.10 (almost certainly different) | 62 (18 %) | 266 (20 %) |
| **exactly 0.00 (definitely different)** | **7** | **45** |

So the honest number is not "1,674 buildings wrongly deleted". It is:

- **380 deletions with < 0.10 true overlap** — almost certainly real buildings, now missing.
- **52 deletions with exactly zero overlap** — provably separate buildings, definitely lost.

A worked example straight out of the data:

```
VIDA building at (17.444921, 78.536133)  area =  60 m²
OSM  building at (17.444921, 78.536190)  area =  58 m²
centroid distance   6.05 m  <=  tolerance 7.14 m   => DELETED
true polygon overlap 0.000        bbox IoU 0.000
```

Two neighbouring 60 m² houses, 6 m apart, not touching at all. The code deletes the
Google one because their centroids are close. This is the failure mode, and in dense
row housing it is systematic rather than rare.

---

## 4. Tolerance sweep

| base | max | bbox IoU | MSFT FP | MSFT prec | MSFT rec | VIDA FP | VIDA prec | VIDA rec |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **6.0** | **14.0** | **0.30** | **355** | **63.5 %** | **98.9 %** | **1319** | **60.6 %** | **99.6 %** |
| 5.0 | 12.0 | 0.30 | 302 | 67.1 % | 98.7 % | 1058 | 65.7 % | 99.5 % |
| 4.0 | 10.0 | 0.30 | 246 | 71.4 % | 98.6 % | 813 | 71.4 % | 99.5 % |
| 3.0 | 8.0 | 0.30 | 199 | 75.5 % | 98.4 % | 629 | 76.3 % | 99.3 % |
| 2.0 | 6.0 | 0.30 | 146 | 80.7 % | 97.8 % | 487 | 80.6 % | 99.2 % |
| 0 (off) | – | 0.30 | 66 | 90.2 % | 97.1 % | 344 | 85.4 % | 98.6 % |

Tightening from 6/14 → 2/6 removes **59 %** of the wrong deletions and costs only
**0.8 pp** of recall. The centroid test earns very little: turning it off entirely
still keeps 97–99 % recall.

**The sensitivity check that matters:** the conclusion is not an artifact of choosing
IoU ≥ 0.30 as truth. Re-scored at every threshold, current-settings precision is
92.9 % / 80.7 % / 63.5 % / 45.6 % / 29.1 % at truth thresholds 0.10 / 0.20 / 0.30 /
0.40 / 0.50. It degrades monotonically — the code is loose at *every* definition of
"same building".

---

## 5. The actual fix: test overlap, not centroids

The centroid test is a proxy for "do these occupy the same ground". The real question
is directly computable, and the objection to computing it — cost — does not survive
measurement.

| approach | MSFT FP | prec | rec | VIDA FP | prec | rec | wrongly-deleted (<0.10) | zero-overlap deleted |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| CURRENT centroid 6/14 + bboxIoU | 355 | 63.5 % | 98.9 % | 1319 | 60.6 % | 99.6 % | 380 | 52 |
| TIGHT centroid 2/6 + bboxIoU | 146 | 80.7 % | 97.8 % | 487 | 80.6 % | 99.2 % | 36 | 4 |
| **polygon IoU ≥ 0.30 + 2 m fallback** | **55** | **91.8 %** | **99.2 %** | **143** | **93.4 %** | **99.2 %** | **6** | **0** |

Polygon overlap is better on *both* axes simultaneously — precision 63 % → 92 %,
recall unchanged at 99.2 %, and zero provably-wrong deletions.

### Cost — measured, not guessed

- Grid-neighbour pairs: 374,861. Surviving the existing bbox pre-reject: **45,420 (12.1 %)**.
- So only **7.5 pairs per candidate** ever reach the expensive test.
- At Hyderabad density a 16×16 MC chunk holds ~1.3 VIDA candidates → **~9.6 polygon tests per chunk**.
- Measured 26.6 µs per test *in Python*. Java is typically 20–50× faster → ~0.9 µs.
- **≈ 0.009 ms per chunk.** Tick budget is 50 ms.

The polygon test is roughly four thousand times cheaper than the tick budget. There is
no performance argument for the centroid approximation.

---

## 6. Two secondary defects found

**(a) `Print.of()` computes area inconsistently across sources.**

```java
Print.of(OsmStructure)     -> area 0  => falls back to BBOX area
Print.of(OvertureBuilding) -> area 0  => falls back to BBOX area
Print.of(VidaBuilding)     -> b.area  => TRUE POLYGON area
```

Since tolerance scales with `sqrt(area)`, the same building gets a different tolerance
depending on which dataset it came from. Measured on real VIDA polygons, bbox area is
a median of **1.38×** the true area (p90 = 2.01×, max 3.98×), worth up to **1.84 m** of
tolerance difference. Not the main bug, but it means the tolerance is quietly wider for
OSM/Microsoft than for Google, for no stated reason.

**(b) The docstring's error model is wrong.** It says Microsoft is "commonly 2–5 m off";
measured median is 6.4 m with p90 at 17.2 m. Any constant derived from that assumption
was derived from a number that is about half the real value.

**Checked and found fine:** `isSameBuilding` is symmetric — 271,107 real pairs tested,
zero asymmetries, despite the bbox pre-reject padding using only the candidate's
latitude.

---

## 7. Net effect on what you see in game

In the test box (OSM contributes 2,762 buildings, all kept):

| | VIDA adds | Microsoft adds |
|---|---:|---:|
| CURRENT 6/14 | 2,690 | 489 |
| TIGHT 2/6 | 3,529 | 705 |

Tightening restores ~840 Google buildings and ~215 Microsoft ones in one square
kilometre. Those are buildings that exist in reality, are absent from OSM, and are
currently being discarded — which is the opposite of what the gap-fill hierarchy is for.

---

## 8. Recommendation

1. **Replace the centroid test with a real polygon-overlap test** (`IoU ≥ 0.30`), keeping
   a small ~2 m centroid fallback for degenerate/tiny footprints. Best measured accuracy,
   and the cost is negligible.
2. If you want a one-line change instead: **`BASE_TOLERANCE_M 6.0 → 2.0`,
   `MAX_TOLERANCE_M 14.0 → 6.0`**. Recovers 59 % of the wrong deletions for 0.8 pp of recall.
3. Fix `Print.of` to use true polygon area for all three sources.
4. Correct the docstring's error model to the measured 6.4 m median / 17 m p90.

I have **not applied any of these** — this turn was the diagnosis you asked for, and
option 1 changes a geometric contract used at six call sites. Say which and I'll do it.

---

## 9. Caveats

- **One 1.1 km × 1.1 km box in one city.** Hyderabad's dense small-footprint housing is
  close to the worst case for a distance-based test; a low-density suburb would show
  fewer false positives. The direction of the result is robust across every truth
  threshold tested, but the magnitudes are specific to this tile.
- **Ground truth is grid-sampled polygon IoU** (24×24 for scoring, 8×8 for the cost
  benchmark), not exact geometric intersection. Error is well under the 0.30 threshold
  at these footprint sizes, but it is an approximation.
- **Not run in Minecraft.** This tests the geometric predicate in isolation. It says
  nothing about `sitsOnRoad`, placement, or the main-thread blocking issue (Finding 1),
  which remains the cause of `Can't keep up!`.
- Timing is extrapolated from Python to Java by a 20–50× factor. Even at 1× the
  polygon test would be affordable, so the conclusion does not depend on that factor.

**Reproduce:** `/home/user/tolerance_test/align.py` plus the three captured datasets
(`osm_box.json`, `msft_box.json`, `vida_box.json`) in the same directory.
