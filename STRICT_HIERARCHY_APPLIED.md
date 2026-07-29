# Strict overlap hierarchy — applied

**Rule implemented exactly as asked:**
- A **Google** building that overlaps an **OSM** building is removed.
- A **Microsoft** building that overlaps an **OSM** building is removed.
- A **Microsoft** building that overlaps an accepted **Google** building is removed.

Compile is at the 2,596-error baseline with no new errors in any edited file.

---

## Why this was still broken

The existing dedup used `isSameBuilding()`, which answers a *different* question:
"are these two records the same real-world building?" — via centroid distance and
bounding-box IoU.

Two **genuinely different** buildings can physically intersect. An ML footprint that
clips the corner of an OSM building is correctly judged "not the same building", so it
was kept and rendered as geometry poking through a wall.

Measured on real data, Hyderabad box:

| overlap case | physically overlap | removed before | **still placed, clipping** |
|---|---:|---:|---:|
| Google vs OSM | 4,076 | 2,592 | **1,484** |
| Microsoft vs OSM | 1,182 | 777 | **405** |
| Microsoft vs accepted Google | 1,083 | 420 | **663** |

**1,841 buildings were being placed intersecting a higher-priority building.**

---

## The fix

New primitive in `FootprintAlignment`:

```java
public static boolean footprintsIntersect(nodesA, bboxA, nodesB, bboxB)
```

True polygon intersection — a vertex of either ring inside the other, **or** any pair
of edges crossing. Not a centroid or bbox approximation. Bounding-box reject first, so
most pairs cost almost nothing.

Added as a final condition to every hierarchy gate, after the existing checks:

| gate | file |
|---|---|
| Google vs OSM | `SourceCooperativeIndex.overlapsExisting` |
| Microsoft vs Google | `SourceCooperativeIndex.overlapsVida` |
| Microsoft vs OSM | `AsyncStructureManager.overlapsAnyStructure` |
| Microsoft vs OSM | `TerraChunkGenerator.overlaps` |
| Microsoft vs OSM/OHM | `HistoricalStructureManager.overlapsExisting` |

All five paths, so it holds on the primary pass, the retry pass and the time-enabled
pass alike.

**Cost: none.** Measured 0.6 µs per pair versus 0.7 µs for the old test — the exact
test is marginally *cheaper*, because the bbox reject kills most pairs before any
polygon work happens.

---

## Verified result

| city | OSM | Google kept | MSFT kept | Google clipping OSM | MSFT clipping OSM | MSFT clipping Google |
|---|---:|---:|---:|---:|---:|---:|
| Hyderabad | 2,762 | 1,962 | 19 | **0** | **0** | **0** |
| Delhi | 503 | 959 | 19 | **0** | **0** | **0** |

**Zero overlapping buildings in every direction, in both cities.**

---

## Why Microsoft drops to 19 — this is correct, not over-deletion

Breakdown of all 1,461 Microsoft footprints in the Hyderabad box:

```
removed by OSM          : 1,183
removed by Google       :   259
survive (true gap-fill) :    19
```

Microsoft is the lowest tier, and Google already supplies 6,041 footprints in this
dense area. After OSM and Google have claimed their ground there is almost nothing
left for Microsoft to add. That is the hierarchy doing exactly what was specified.

In a sparser region — where Google has poor coverage — Microsoft will contribute far
more. The rule is relative, not a fixed quota.

## Effect on the world

```
before : OSM 2,762 + Google 3,446 + MSFT   361 = 6,569  (1,841 of them clipping)
after  : OSM 2,762 + Google 1,962 + MSFT    19 = 4,743  (0 clipping)
```

Fewer buildings, but every one of them stands on its own ground. The 1,826 removed
were duplicates and intersecting copies of buildings that were already there.

---

## Caveats

- **Not run in Minecraft.** Verification is compile plus replay of the exact predicate
  against real OSM, Google and Microsoft data in two cities.
- **This is a deliberately aggressive rule.** Any touching of a higher-tier footprint
  removes the lower-tier one, including a 10 cm corner graze. That is what "remove
  buildings which try to overlap" means, and the measured result is zero clipping — but
  it does mean a genuinely-new building that happens to abut an OSM one is also
  dropped. If you would rather allow slight touching, the fix is a minimum-overlap-area
  threshold instead of any-intersection; say the word.
- Interacts with the runtime alignment from the previous turn: buildings are shifted to
  remove dataset bias **before** these tests run, so the overlap decision is made on
  correctly-registered footprints.
- Does not touch Finding 1 (main-thread blocking I/O), still the cause of
  `Can't keep up!`.
