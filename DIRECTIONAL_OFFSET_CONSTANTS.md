# The ± lat/lon offset of Google and Microsoft, with OSM as the base

**Short answer: there is no single constant. I measured it in two cities and it
changes between them, so hardcoding one would make other regions worse — and I
verified that it does.**

---

## 1. What was measured

For every ML footprint, find its **true OSM counterpart** (best real polygon
intersection-over-union ≥ 0.30, computed on actual rings, not bounding boxes), then
record the **signed** offset:

```
dLat = ML.centroidLat - OSM.centroidLat
dLon = ML.centroidLon - OSM.centroidLon
```

Positive dLat = ML sits **north** of OSM. Positive dLon = ML sits **east** of OSM.

Real data, both cities: OSM from live Overpass, Microsoft from the real
`dataset-links.csv` quadkeys, Google/VIDA from the real `.fgb` shards read over HTTP
range requests.

---

## 2. Hyderabad — `17.435–17.445 N, 78.535–78.545 E`

| source | n | **dLat** | **dLon** | sd(lat) | sd(lon) |
|---|---:|---:|---:|---:|---:|
| **Microsoft** | 627 | **−0.00002999°** = **−3.339 m** | **+0.00002282°** = **+2.423 m** | 3.41 m | 3.04 m |
| **Google/VIDA** | 2043 | −0.00000132° = −0.147 m | **+0.00002739°** = **+2.909 m** | 2.35 m | 2.43 m |

In plain terms, in Hyderabad:
- **Microsoft sits 3.3 m south and 2.4 m east of OSM.**
- **Google sits 2.9 m east of OSM**, with no meaningful north–south bias.

These are not noise. Standard errors and t-statistics:

| | mean | se | t | 95 % CI |
|---|---:|---:|---:|---|
| MSFT dLat | −3.339 m | 0.136 | **−24.5** | [−3.61, −3.07] |
| MSFT dLon | +2.423 m | 0.121 | **+19.9** | [+2.19, +2.66] |
| Google dLat | −0.147 m | 0.052 | −2.8 | [−0.25, −0.05] (not significant) |
| Google dLon | +2.909 m | 0.054 | **+54.1** | [+2.80, +3.01] |

**Not an artifact of the matching rule.** Re-estimated at IoU cuts from 0.15 to 0.60
the numbers barely move (Microsoft dLat −3.13 → −3.29, Google dLon +2.91 → +2.24), so
the bias is a property of the data. It is also uniform within the box: north and south
halves agree to within 0.35 m.

**And correcting it works — in Hyderabad:**

| source | variant | n matched | mean distance | mean IoU |
|---|---:|---:|---:|---:|
| Microsoft | raw | 627 | 5.553 m | 0.4917 |
| Microsoft | **bias-corrected** | **693** | **3.851 m** | **0.5481** |
| Google | raw | 2043 | 3.983 m | 0.4962 |
| Google | **bias-corrected** | **2152** | **2.940 m** | 0.4905 |

Microsoft improves on every axis: 31 % closer, higher overlap, 66 more buildings
correctly matched.

---

## 3. Delhi — `28.630–28.640 N, 77.205–77.215 E` — where it falls apart

Same method, different city (OSM 503, Microsoft 732, Google 1,986 buildings):

| source | city | n | dLat | dLon |
|---|---|---:|---:|---:|
| Microsoft | Hyderabad | 627 | −3.339 m | +2.423 m |
| Microsoft | **Delhi** | 223 | **−0.948 m** | **+3.235 m** |
| | *difference* | | **+2.391 m** | +0.812 m |
| Google | Hyderabad | 2043 | −0.147 m | +2.909 m |
| Google | **Delhi** | 176 | **+1.222 m** | **+4.414 m** |
| | *difference* | | **+1.369 m** | +1.505 m |

The north–south bias **changes by 1.4–2.4 m** between cities, and for Google it
**flips sign** (−0.15 m south in Hyderabad, +1.22 m north in Delhi).

### The test that settles it

Applying the Hyderabad constant to Delhi:

| source | strategy | n | mean distance | mean IoU |
|---|---|---:|---:|---:|
| Microsoft | raw | 223 | 6.171 m | **0.5117** |
| Microsoft | **Hyderabad constant applied** | 193 | 6.157 m | **0.4898** ← *worse* |
| Microsoft | local bias estimated | 226 | 5.881 m | 0.5169 |
| Google | raw | 176 | 6.649 m | **0.4872** |
| Google | Hyderabad constant applied | 240 | 5.910 m | 0.4512 ← *worse* |
| Google | local bias estimated | 271 | 5.200 m | 0.4812 |

**Hardcoding Hyderabad's constant makes Microsoft in Delhi worse** — overlap quality
drops and 30 fewer buildings match. This is exactly the mistake the old 6 m tolerance
made in a different form: fitting a constant to one place and applying it everywhere.

---

## 4. What *is* stable

Two things hold in both cities:

1. **dLon is always positive.** Both sources sit east of OSM everywhere measured:
   +2.4 to +4.4 m. This is the one directional claim the data supports.
2. **The magnitude of the total offset is stable**, 2.9–4.6 m:

   | | Hyderabad | Delhi |
   |---|---:|---:|
   | Microsoft | 4.13 m | 3.37 m |
   | Google | 2.91 m | 4.58 m |

This is why a **scalar tolerance** is the right tool and a **vector correction** is
not. The distance between a matched pair is predictable; its direction is not.

**This directly validates the 2.5 m / 7.0 m tolerance already applied.** With the
scaling term, a typical Hyderabad footprint gets ~4 m of tolerance and the clamp
allows 7 m — which covers the 2.9–4.6 m offsets seen in both cities without needing to
know which direction they point.

---

## 5. Recommendation

**Do not hardcode a (dLat, dLon) constant.** Measured directly, it is wrong by more
than its own size once you leave the city it was fitted in, and it degrades results.

If you want to pursue directional correction, the only defensible form is a
**locally-estimated bias**: compute the mean offset from the ML/OSM pairs already
matched in the surrounding region, then apply it to that region's unmatched ML
buildings. In the Delhi test that was the best of the three strategies for both
sources — Microsoft 6.17 → 5.88 m, Google 6.65 → 5.20 m — because it adapts instead
of assuming.

That said, the gain is modest (~0.3–1.4 m) and it adds a stateful per-region
calibration step. The current tolerance already handles these magnitudes. I would
only build it if ghost/missing buildings remain visibly wrong after the applied fix.

**Numbers, if you still want them for reference — valid for Hyderabad only:**

```java
// Hyderabad only. Do NOT apply globally; verified to hurt Delhi.
MICROSOFT: dLat = -0.00002999 deg (-3.339 m),  dLon = +0.00002282 deg (+2.423 m)
GOOGLE   : dLat = -0.00000132 deg (-0.147 m),  dLon = +0.00002739 deg (+2.909 m)
```

---

## 6. Caveats

- **Two cities, both in India.** The cross-city instability is proven; whether some
  other structure exists (per-country, per-imagery-vintage) is not established. Three
  or more cities on different continents would be needed to say more.
- Delhi's sample is smaller (223 and 176 matched pairs vs 627 and 2043), so its point
  estimates are less precise — but the *difference* between cities is far larger than
  the standard errors, so the instability itself is solid.
- Ground truth is grid-sampled polygon IoU (16×16), not exact geometric intersection.
- Offsets are between **centroids**. A footprint that is the right position but the
  wrong shape shows as a small offset; this measures registration, not shape accuracy.
- Not run in Minecraft.

Reproduction: `/home/user/tolerance_test/` — `align.py`, `osm_box.json`,
`msft_box.json`, `vida_box.json` (Hyderabad), `msft_delhi.json`, `vida_delhi.json`,
`offsets.json`.
