#!/usr/bin/env python3
"""Spike-guard v3b: agreement-gated monster killer.

Anchor may only fire where ALL hold:
  - coarse (z10) neighbourhood is flat (3x3 range < 25 m)
  - the two books AGREE on the area level (16x16 fine block
    median within 15 m of coarse)  -> protects Manhattan,
    where Terrarium's own coarse zoom carries rooftops
  - the pixel deviates monster-scale (>150 m) from coarse
Then iterated 3x3 neighbour-median (60 m) for singles.
"""
import importlib.util
import numpy as np
from PIL import Image

spec = importlib.util.spec_from_file_location("hr", "/home/user/B/terra3/tools/height_race.py")
hr = importlib.util.module_from_spec(spec); spec.loader.exec_module(hr)
TER = hr.TER

def up(a, shape, mode):
    return np.asarray(Image.fromarray(a.astype(np.float32), mode="F")
                      .resize((shape[1], shape[0]), mode), dtype=np.float64)

def med8_iter(h, thresh=60.0):
    out = h.copy(); total = 0
    for _ in range(8):
        c = out[1:-1, 1:-1]
        neigh = np.stack([out[:-2, :-2], out[:-2, 1:-1], out[:-2, 2:],
                          out[1:-1, :-2], out[1:-1, 2:], out[2:, :-2],
                          out[2:, 1:-1], out[2:, 2:]])
        m8 = np.median(neigh, axis=0)
        mask = np.abs(c - m8) > thresh
        if not mask.any():
            break
        inner = out[1:-1, 1:-1]
        inner[mask] = m8[mask]
        total += int(mask.sum())
    return out, total

def guard_v3b(fine, coarse):
    shape = fine.shape
    upc = up(coarse, shape, Image.BILINEAR)
    ch, cw = coarse.shape
    pad = np.pad(coarse, 1, mode="edge")
    locmax = np.maximum.reduce([pad[i:i + ch, j:j + cw] for i in range(3) for j in range(3)])
    locmin = np.minimum.reduce([pad[i:i + ch, j:j + cw] for i in range(3) for j in range(3)])
    relief = up(locmax - locmin, shape, Image.NEAREST)
    # fine block medians (16x16), upsampled
    b = 16
    hc, wc = (shape[0] // b) * b, (shape[1] // b) * b
    blocks = fine[:hc, :wc].reshape(hc // b, b, wc // b, b)
    bmed = np.median(blocks, axis=(1, 3))
    bmed_full = up(np.pad(bmed, ((0, shape[0] // b + 1), (0, shape[1] // b + 1)), mode="edge")[: shape[0] // b + (shape[0] % b > 0), : shape[1] // b + (shape[1] % b > 0)] if False else bmed, shape, Image.NEAREST)
    agree = np.abs(bmed_full - upc) < 15
    pit = fine < upc - 150
    spike = (fine > upc + 150) & agree
    mask = (relief < 25) & (pit | spike)
    out = fine.copy()
    out[mask] = upc[mask]
    return out, int(mask.sum())

BOXES = [
    ("Dead Sea water (disease)", (35.515, 31.48, 35.555, 31.52)),
    ("Caspian Sea water (disease)", (50.45, 41.95, 50.55, 42.05)),
    ("Tokyo (pit pixel)", (139.74, 35.675, 139.76, 35.690)),
    ("Grand Canyon (must not touch)", (-112.13, 36.095, -112.09, 36.120)),
    ("Zermatt Alps (must not touch)", (7.72, 45.970, 7.76, 46.000)),
    ("Everest (summit must survive)", (86.9150, 27.9781, 86.9350, 27.9981)),
    ("Manhattan (anchor must stay OFF)", (-73.995, 40.750, -73.975, 40.765)),
    ("Amsterdam (flat city, safety)", (4.895, 52.360, 4.915, 52.375)),
    ("Flevoland polder (flat land, safety)", (5.44, 52.40, 5.50, 52.46)),
]
for name, box in BOXES:
    fine, _ = hr.raster(box, 14, TER, "terrarium")
    coarse, _ = hr.raster(box, 10, TER, "terrarium")
    if fine is None or coarse is None:
        print(f"### {name}: fetch issue"); continue
    g, nB = guard_v3b(fine, coarse)
    g, nA = med8_iter(g)
    def stats(a):
        p2, p98 = np.percentile(a, [2, 98])
        return f"min={a.min():.1f} max={a.max():.1f} median={np.median(a):.1f} p98-p2={p98-p2:.1f}"
    print(f"### {name} px={fine.size} anchor={nB} med8={nA}")
    print(f"  before: {stats(fine)}")
    print(f"  after : {stats(g)}")
