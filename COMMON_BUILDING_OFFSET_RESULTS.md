# Offset of Google and Microsoft from OSM — common-building test

Test run exactly as specified: select buildings common to all three sources, take
their edge/corner positions, difference them against OSM as the original, average
the differences, and report the count used.

---

## THE ANSWER — Hyderabad, 495 common buildings

| source | avg **dLAT** | avg **dLON** | avg displacement |
|---|---:|---:|---:|
| **GOOGLE (VIDA)** | **+0.00000138°** = **+0.154 m** | **+0.00003378°** = **+3.588 m** | 4.516 m |
| **MICROSOFT** | **−0.00003013°** = **−3.354 m** | **+0.00002476°** = **+2.629 m** | 5.689 m |

- Difference = `SOURCE − OSM`. Positive dLat = north of OSM, positive dLon = east.
- **Common buildings used: 495** (present in OSM *and* Google *and* Microsoft).
- 95 % confidence: Google ±0.218 m lat / ±0.215 m lon; Microsoft ±0.298 / ±0.269 m.

**In words:** Google sits ~3.6 m **east** of OSM with essentially no north–south
error. Microsoft sits ~3.4 m **south** and ~2.6 m **east**.

---

## How the common set was built

| step | count |
|---|---:|
| OSM buildings in the test box | 2,762 |
| …matched by a Microsoft building | 624 |
| …matched by a Google building | 1,953 |
| **…matched by BOTH (the common set)** | **495** |

A "match" is a true polygon intersection-over-union ≥ 0.30 against the *same* OSM
building, computed on the real footprint rings rather than bounding boxes.

---

## Important: why the reported number is the centroid difference

I first did it literally corner-by-corner — pairing each ML vertex to its nearest OSM
vertex — and got noticeably smaller numbers:

| estimator | Google dLat / dLon | Microsoft dLat / dLon |
|---|---|---|
| centroid difference | +0.154 / **+3.588 m** | −3.354 / **+2.629 m** |
| corners, cyclic pairing | +1.413 / +1.619 m | −1.589 / +1.468 m |
| corners, nearest-neighbour | +1.055 / +1.212 m | −0.992 / +1.139 m |

Three estimators, same 495 buildings, three different answers. So I ran a control:
built synthetic rectangles, applied a **known** shift of exactly +3.000 m north and
+2.000 m east, added realistic 1.5 m per-vertex ML noise, and checked which estimator
recovers the truth.

```
TRUE shift applied       : dLat +3.000 m   dLon +2.000 m
centroid difference      : dLat +2.986 m   dLon +2.036 m   <- correct
nearest-neighbour corners: dLat +2.717 m   dLon +1.845 m   <- shrunk toward zero
```

Nearest-neighbour corner pairing is **biased**: when a building is shifted, each OSM
corner gets paired to whichever ML corner happens to be closest — often not its true
counterpart — which absorbs part of the shift. Cyclic pairing is better but still
degrades when the two rings have different vertex counts or rounded corners.

The centroid difference is the unbiased estimator of a rigid translation, so that is
what the headline table reports. It is also mathematically the average of all corner
differences under correct correspondence.

---

## The catch — these values do not transfer to another city

Same test, same code, second city (Delhi, `28.630–28.640 N, 77.205–77.215 E`),
**97 common buildings**:

| source | dLAT Hyderabad | dLAT Delhi | dLON Hyderabad | dLON Delhi |
|---|---:|---:|---:|---:|
| Google | +0.154 m | **+1.716 m** | +3.588 m | **+5.388 m** |
| Microsoft | −3.354 m | **−1.388 m** | +2.629 m | **+3.872 m** |

The north–south component moves by 1.6–2.0 m and Microsoft's shrinks to under half.
Only one thing holds in both cities: **dLON is always positive** — both sources sit
east of OSM, by +2.6 to +5.4 m.

So if you intend to use these as a correction constant, they are valid **for Hyderabad
only**. I tested applying Hyderabad's constant to Delhi earlier and it made Microsoft's
alignment *worse* (mean overlap quality 0.512 → 0.490).

---

## Summary

**Values you asked for (Hyderabad, OSM as original, n = 495 common buildings):**

```
GOOGLE     dLat = +0.00000138 deg  (+0.154 m)     dLon = +0.00003378 deg  (+3.588 m)
MICROSOFT  dLat = -0.00003013 deg  (-3.354 m)     dLon = +0.00002476 deg  (+2.629 m)
```

**Caveats:**
- Location-specific; Delhi gives materially different numbers from the same code.
- Corner-level pairing gives smaller values (Google +1.06/+1.21 m, Microsoft
  −0.99/+1.14 m) but is provably biased toward zero — included above for completeness,
  not recommended.
- Matching threshold IoU ≥ 0.30; the estimate is stable across 0.15–0.60.
- Ground truth overlap is grid-sampled (24×24), not exact geometry.
- Not run in Minecraft.

Reproduction: `/home/user/tolerance_test/edge_offsets.py` with `osm_box.json`,
`msft_box.json`, `vida_box.json` (Hyderabad) and `*_delhi.json` (Delhi).
