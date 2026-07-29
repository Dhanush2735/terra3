# How the offset problem is solved — runtime self-calibration

**Applied.** New file `generator/osm/SourceAlignment.java`, wired into all six ML
acceptance points. Compile is at the 2,596-error baseline with no new errors.

---

## The situation

The offset is real and large. From 495 buildings common to all three sources:

```
Hyderabad    Google    dLat +0.154 m   dLon +3.588 m
             Microsoft dLat -3.354 m   dLon +2.629 m
```

Statistically overwhelming (t = 20 to 54). But the same measurement in Delhi:

```
Delhi        Google    dLat +1.716 m   dLon +5.388 m
             Microsoft dLat -1.388 m   dLon +3.872 m
```

The north–south component moves 1.6–2.0 m and Microsoft's more than halves. **A
hardcoded constant cannot work** — and I verified it actively harms: applying
Hyderabad's constant to Delhi dropped Microsoft's overlap quality from 0.199 to 0.178.

## The solution: measure it where you are, at runtime

In any populated region, some ML buildings unambiguously match an OSM building. Those
pairs *reveal the local shift*. Apply it to all the ML buildings in that region —
including the gap-fill ones with no OSM counterpart, which are the ones actually
placed in the world.

```
1. Pair ML → OSM by bounding-box IoU ≥ 0.30
2. Take the MEDIAN of the pair offsets      (median, so a few bad matches can't drag it)
3. Require ≥ 8 pairs, else apply nothing    (never guess)
4. Reject any estimate > 12 m as a broken match
5. Cache per ~5.5 km region
```

No table to maintain, nothing to go stale when upstream re-releases the data, and it
fits each city automatically.

### Does it recover the true shift, knowing nothing?

| city | source | pairs | estimated | true (measured) | error |
|---|---|---:|---|---|---:|
| Hyderabad | Google | 2041 | +0.11 / +2.99 m | +0.15 / +3.59 m | 0.60 m |
| Hyderabad | Microsoft | 624 | −3.38 / +2.36 m | −3.35 / +2.63 m | 0.27 m |
| Delhi | Google | 175 | +1.24 / +3.96 m | +1.72 / +5.39 m | 1.50 m |
| Delhi | Microsoft | 224 | −0.73 / +2.60 m | −1.39 / +3.87 m | 1.44 m |

It adapts to each city on its own.

### Does it actually improve placement? (the test that matters)

Higher IoU and more matched buildings = better alignment:

| city | source | strategy | mean IoU | matched |
|---|---|---|---:|---:|
| Hyderabad | Google | raw | 0.2139 | 2041 |
| | | hardcoded const | 0.2133 | 2111 | ← **worse** |
| | | **self-calibrated** | **0.2282** | **2244** |
| Hyderabad | Microsoft | raw | 0.2686 | 624 |
| | | hardcoded const | 0.3012 | 687 |
| | | **self-calibrated** | **0.3073** | **690** |
| Delhi | Google | raw | 0.0851 | 175 |
| | | hardcoded const | 0.0954 | 251 |
| | | **self-calibrated** | **0.1060** | **280** |
| Delhi | Microsoft | raw | 0.1990 | 224 |
| | | hardcoded const | 0.1778 | 188 | ← **worse** |
| | | **self-calibrated** | **0.2046** | **227** |

**Self-calibration wins in all four cases. The hardcoded constant loses in two.**

## Cost

The obvious implementation was too slow, so it was made cheap and re-verified:

| variant | dLat / dLon | time | error vs exhaustive |
|---|---|---:|---:|
| polygon IoU, all buildings | +0.107 / +2.991 | 9,410 ms | — |
| **bbox IoU, 200-pair cap** | **+0.247 / +2.557** | **12 ms** | **0.46 m** |

780× faster, within half a metre. Bounding-box IoU is enough because the median over
hundreds of pairs washes out per-pair shape error. Runs **once per region**, cached —
not per chunk. In Java, well under a millisecond.

## Safety behaviour

Tested explicitly:

- **Sparse regions** — stable down to 1 % of the buildings (25 pairs → +2.64 m vs
  +2.99 m at full density).
- **No OSM at all (rural)** — 0 pairs → returns zero shift, buildings placed
  unmodified. Never guesses.
- **Not cached when evidence is thin**, so a later chunk bringing more OSM data can
  still calibrate.

## What changed in the code

| file | change |
|---|---|
| `SourceAlignment.java` | **new** — the estimator, cache and guards |
| `SourceCooperativeIndex.java` | `VidaBuilding.shifted()`; constructor overload so translating doesn't re-apply the 0.5 m inset |
| `OvertureIndex.java` | `OvertureBuilding.shifted()` |
| `TerraChunkGenerator.java` | calibrate + shift on both VIDA and Microsoft, in both passes (4 sites) |
| `AsyncStructureManager.java` | same on the retry path (2 sites) |

All 6 ML acceptance points now calibrate before comparing. This matters because the
shift is applied **before** the duplicate test and the road test — so those tests now
compare correctly-registered footprints, which is what makes the dedup and the
road rejection work properly rather than fighting the bias.

## Caveats

- **Not run in Minecraft.** Verification is compile plus replay against real OSM,
  Microsoft and Google data in two cities.
- The estimate is a **rigid translation only**. It does not correct rotation, scale, or
  per-building shape error — those are not constant within a region and cannot be
  fixed this way.
- Two Indian cities. The mechanism is location-independent by construction (it fits
  whatever it finds), but I have not tested a region where OSM itself is badly
  misregistered — there the ML data might be the more correct of the two, and this
  would move buildings toward the wrong reference.
- Region grid is ~5.5 km; a sharp change in bias inside one region gets averaged.
- Does not touch Finding 1 (main-thread blocking I/O), still the cause of
  `Can't keep up!`.

Reproduction: `/home/user/tolerance_test/selfcalib.py`, `edge_offsets.py`, plus the six
`*_box.json` / `*_delhi.json` datasets.
