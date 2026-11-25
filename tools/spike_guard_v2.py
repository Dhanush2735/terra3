#!/usr/bin/env python3
"""Spike-guard v2: diagnose monster clusters, then stronger guard.

Diagnosis: where are the crazy pixels (clusters? rims? patches?)
Guard v2 = A) iterated 3x3 neighbour-median repair (thresh 60),
           B) flat-block patch killer: in 16x16 blocks whose own
              relief is <40 m, pixels >60 m from the block median
              are replaced by it. Mountains are never flat blocks,
              so B cannot touch real relief.
Also: threshold scan 25/40/60 on legit steep terrain.
"""
import importlib.util
import numpy as np

spec = importlib.util.spec_from_file_location("hr", "/home/user/B/terra3/tools/height_race.py")
hr = importlib.util.module_from_spec(spec); spec.loader.exec_module(hr)

def med8_pass(h, thresh=60.0):
    out = h.copy(); changed_total = 0
    for _ in range(8):
        c = out[1:-1, 1:-1]
        neigh = np.stack([out[:-2, :-2], out[:-2, 1:-1], out[:-2, 2:],
                          out[1:-1, :-2], out[1:-1, 2:], out[2:, :-2],
                          out[2:, 1:-1], out[2:, 2:]])
        med8 = np.median(neigh, axis=0)
        mask = np.abs(c - med8) > thresh
        n = int(mask.sum())
        if n == 0:
            break
        inner = out[1:-1, 1:-1]
        inner[mask] = med8[mask]
        changed_total += n
    return out, changed_total

def flat_block_pass(h, thresh=60.0, block=16, flat_relief=40.0):
    out = h.copy(); total = 0
    for _ in range(2):
        H, W = out.shape
        hc, wc = (H // block) * block, (W // block) * block
        crop = out[:hc, :wc]
        blocks = crop.reshape(hc // block, block, wc // block, block)
        bmed = np.median(blocks, axis=(1, 3))
        brelief = blocks.max(axis=(1, 3)) - blocks.min(axis=(1, 3))
        up_med = np.repeat(np.repeat(bmed, block, axis=0), block, axis=1)
        up_flat = np.repeat(np.repeat(brelief < flat_relief, block, axis=0), block, axis=1)
        region = out[:hc, :wc]
        mask = (np.abs(region - up_med) > thresh) & up_flat
        n = int(mask.sum())
        if n == 0:
            break
        region[mask] = up_med[mask]
        total += n
    return out, total

def clusters(mask):
    """tiny connected-components over True pixels."""
    seen = np.zeros_like(mask, dtype=bool)
    sizes = []
    pts = np.argwhere(mask)
    ptset = set(map(tuple, pts))
    for p in map(tuple, pts):
        if p in ptset:
            stack = [p]; ptset.discard(p); size = 0
            while stack:
                y, x = stack.pop(); size += 1
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        q = (y + dy, x + dx)
                        if q in ptset:
                            ptset.discard(q); stack.append(q)
            sizes.append(size)
    return sorted(sizes, reverse=True)

BOXES = [
    ("Dead Sea water (disease)", (35.515, 31.48, 35.555, 31.52)),
    ("Caspian Sea water (disease)", (50.45, 41.95, 50.55, 42.05)),
    ("Tokyo (pit pixel)", (139.74, 35.675, 139.76, 35.690)),
    ("Grand Canyon (must not touch)", (-112.13, 36.095, -112.09, 36.120)),
    ("Zermatt Alps (must not touch)", (7.72, 45.970, 7.76, 46.000)),
    ("Everest (summit must survive)", (86.9150, 27.9781, 86.9350, 27.9981)),
    ("Manhattan (city must not touch)", (-73.995, 40.750, -73.975, 40.765)),
    ("Amsterdam (flat city, guard safety)", (4.895, 52.360, 4.915, 52.375)),
]

for name, box in BOXES:
    h, status = hr.raster(box, 14, hr.TER, "terrarium")
    if h is None:
        print(f"### {name}: {status}"); continue
    med = float(np.median(h))
    # threshold scan on raw local deviation
    c = h[1:-1, 1:-1]
    neigh = np.stack([h[:-2, :-2], h[:-2, 1:-1], h[:-2, 2:], h[1:-1, :-2],
                      h[1:-1, 2:], h[2:, :-2], h[2:, 1:-1], h[2:, 2:]])
    d = np.abs(c - np.median(neigh, axis=0))
    scan = " ".join(f">{t}m:{(d > t).mean()*100:.4f}%" for t in (25, 40, 60))
    print(f"### {name} px={h.size} thresh-scan[{scan}]")
    if "disease" in name or "Tokyo" in name:
        crazy = np.abs(h - med) > 100
        cl = clusters(crazy)
        print(f"  crazy(>100m from median)={int(crazy.sum())} clusters(top5)={cl[:5]}")
    g, nA = med8_pass(h)
    g, nB = flat_block_pass(g)
    def stats(a):
        p2, p98 = np.percentile(a, [2, 98])
        return f"min={a.min():.1f} max={a.max():.1f} median={np.median(a):.1f} p98-p2={p98-p2:.1f}"
    print(f"  guardA changed={nA} guardB changed={nB}")
    print(f"  before: {stats(h)}")
    print(f"  after : {stats(g)}")
