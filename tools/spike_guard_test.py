#!/usr/bin/env python3
"""Spike-guard prototype (Step 2a, offline).

Guard rule: a pixel that disagrees with the median of its 8
neighbours by >60 m is replaced by that neighbour median.
Must: kill Dead Sea / Caspian monster pixels and the Tokyo
pit; must NOT touch Grand Canyon, Alps or Everest relief.
"""
import importlib.util, json
import numpy as np

spec = importlib.util.spec_from_file_location("hr", "/home/user/B/terra3/tools/height_race.py")
hr = importlib.util.module_from_spec(spec); spec.loader.exec_module(hr)

def guard(h, thresh=60.0):
    out = h.copy()
    c = h[1:-1, 1:-1]
    neigh = np.stack([h[:-2, :-2], h[:-2, 1:-1], h[:-2, 2:], h[1:-1, :-2],
                      h[1:-1, 2:], h[2:, :-2], h[2:, 1:-1], h[2:, 2:]])
    med8 = np.median(neigh, axis=0)
    mask = np.abs(c - med8) > thresh
    inner = out[1:-1, 1:-1]
    inner[mask] = med8[mask]
    return out, int(mask.sum())

BOXES = [
    ("Dead Sea water (disease)", (35.515, 31.48, 35.555, 31.52)),
    ("Caspian Sea water (disease)", (50.45, 41.95, 50.55, 42.05)),
    ("Tokyo (pit pixel)", (139.74, 35.675, 139.76, 35.690)),
    ("Grand Canyon (must not touch)", (-112.13, 36.095, -112.09, 36.120)),
    ("Zermatt Alps (must not touch)", (7.72, 45.970, 7.76, 46.000)),
    ("Everest (summit must survive)", (86.9150, 27.9781, 86.9350, 27.9981)),
]

for name, box in BOXES:
    h, status = hr.raster(box, 14, hr.TER, "terrarium")
    if h is None:
        print(f"### {name}: {status}"); continue
    g, changed = guard(h)
    def stats(a):
        p2, p98 = np.percentile(a, [2, 98])
        return (f"min={a.min():.1f} max={a.max():.1f} median={np.median(a):.1f} "
                f"p98-p2={p98-p2:.1f}")
    print(f"### {name}  px={h.size} changed={changed} ({100*changed/h.size:.4f}%)")
    print(f"  before: {stats(h)}")
    print(f"  after : {stats(g)}")
    if changed:
        diff = np.abs(g - h)
        print(f"  biggest repair: {diff.max():.1f} m at one pixel")
