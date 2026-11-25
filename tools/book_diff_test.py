#!/usr/bin/env python3
"""Book-difference / seam test (Step 2b, offline).

If Mapterhorn were preferred tile-by-tile with Terrarium
filling gaps, how big is the step where the books meet?
Align both books on one grid per box and measure pixel diffs.
Also reports effective ground pixel size at Charminar.
"""
import importlib.util
import numpy as np
from PIL import Image

spec = importlib.util.spec_from_file_location("hr", "/home/user/B/terra3/tools/height_race.py")
hr = importlib.util.module_from_spec(spec); spec.loader.exec_module(hr)

def aligned(box, zmap):
    t, st = hr.raster(box, 14, hr.TER, "terrarium")
    if t is None:
        return None, st, None
    m, sm = hr.raster(box, zmap, hr.MAP, "mapterhorn")
    if m is None:
        return t, st, sm
    # resample Mapterhorn onto the Terrarium grid (bilinear)
    mi = Image.fromarray(m.astype(np.float32), mode="F")
    mi = mi.resize((t.shape[1], t.shape[0]), Image.BILINEAR)
    return t, np.asarray(mi, dtype=np.float64), (st, sm)

BOXES = [
    ("Amsterdam (flat, books should differ most visibly)", (4.895, 52.360, 4.915, 52.375), 13),
    ("Hyderabad Charminar (Mapterhorn coarse z12 here)", (78.4697, 17.3567, 78.4797, 17.3667), 12),
    ("Dead Sea water", (35.515, 31.48, 35.555, 31.52), 13),
]

for name, box, zm in BOXES:
    t, m, st = aligned(box, zm)
    if m is None or isinstance(m, str):
        print(f"### {name}: second book missing ({st})"); continue
    d = m - t
    ad = np.abs(d)
    print(f"### {name}  (Mapterhorn z{zm} vs Terrarium z14, px={t.size})")
    print(f"  signed median diff (book offset): {np.median(d):+.1f} m")
    print(f"  mean|diff|={ad.mean():.1f} m  p95|diff|={np.percentile(ad,95):.1f} m  "
          f"max|diff|={ad.max():.1f} m")
    print(f"  pixels differing by >5 m: {(ad>5).mean()*100:.1f}%   >15 m: {(ad>15).mean()*100:.1f}%")
