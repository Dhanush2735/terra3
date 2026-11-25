#!/usr/bin/env python3
"""Spike-guard v3: zoom-anchor guard.

Diagnosis first: cluster shapes + is the coarse zoom stack
clean above the Caspian patch? Then the guard:
  A) iterated 3x3 neighbour-median (thresh 60) for singles,
  B) zoom anchor: where the COARSE (z10) neighbourhood is
     flat (3x3 range <25 m), fine pixels >40 m from the
     coarse value are replaced by it. Patch-size-proof;
     mountains switch B off automatically.
"""
import importlib.util, math
import numpy as np
from PIL import Image

spec = importlib.util.spec_from_file_location("hr", "/home/user/B/terra3/tools/height_race.py")
hr = importlib.util.module_from_spec(spec); spec.loader.exec_module(hr)
TER = hr.TER

# ---- diagnosis: zoom stack at the Caspian patch centre ----
def point_height(lat, lon, z):
    n = 2 ** z
    fx, fy = (lon + 180) / 360 * n, (1 - math.log(math.tan(math.radians(lat)) +
           1 / math.cos(math.radians(lat))) / math.pi) / 2 * n
    x, y = int(fx), int(fy)
    import urllib.request, io
    raw = urllib.request.urlopen(urllib.request.Request(
        TER.format(z=z, x=x, y=y), headers={"User-Agent": "terra-guard"}), timeout=45).read()
    im = Image.open(io.BytesIO(raw)).convert("RGB")
    px = im.load(); S = im.size[0]
    r, g, b = px[min(S - 1, int((fx - x) * S)), min(S - 1, int((fy - y) * S))]
    return r * 256 + g + b / 256 - 32768

# Caspian box (50.45..50.55, 41.95..42.05): find crazy centroid first
box_c = (50.45, 41.95, 50.55, 42.05)
h, _ = hr.raster(box_c, 14, TER, "terrarium")
med = np.median(h)
crazy = np.abs(h - med) > 100
ys, xs = np.argwhere(crazy)[:, 0], np.argwhere(crazy)[:, 1]
print(f"Caspian crazy patch: n={crazy.sum()} rows {ys.min()}..{ys.max()} "
      f"cols {xs.min()}..{xs.max()} patch values min={h[crazy].min():.1f} "
      f"median={np.median(h[crazy]):.1f} max={h[crazy].max():.1f}")
clat = box_c[3] - (ys.mean() / h.shape[0]) * (box_c[3] - box_c[1])
clon = box_c[0] + (xs.mean() / h.shape[1]) * (box_c[2] - box_c[0])
print(f"patch centre ~ lat {clat:.4f} lon {clon:.4f}; zoom stack there:")
for z in (8, 10, 12, 14, 15):
    try:
        print(f"  z{z}: {point_height(clat, clon, z):.1f} m")
    except Exception as e:
        print(f"  z{z}: ERR {e}")

# ---- guard v3 ----
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

def zoom_anchor(fine, coarse, thresh=40.0, flat_range=25.0):
    ch, cw = coarse.shape
    up = np.asarray(Image.fromarray(coarse.astype(np.float32), mode="F")
                    .resize((fine.shape[1], fine.shape[0]), Image.BILINEAR), dtype=np.float64)
    # coarse local relief (3x3 range), upsampled nearest
    pad = np.pad(coarse, 1, mode="edge")
    locmax = np.maximum.reduce([pad[i:i + ch, j:j + cw] for i in range(3) for j in range(3)])
    locmin = np.minimum.reduce([pad[i:i + ch, j:j + cw] for i in range(3) for j in range(3)])
    relief_up = np.asarray(Image.fromarray((locmax - locmin).astype(np.float32), mode="F")
                           .resize((fine.shape[1], fine.shape[0]), Image.NEAREST), dtype=np.float64)
    mask = (relief_up < flat_range) & (np.abs(fine - up) > thresh)
    out = fine.copy()
    out[mask] = up[mask]
    return out, int(mask.sum())

BOXES = [
    ("Dead Sea water (disease)", (35.515, 31.48, 35.555, 31.52)),
    ("Caspian Sea water (disease)", box_c),
    ("Tokyo (pit pixel)", (139.74, 35.675, 139.76, 35.690)),
    ("Grand Canyon (must not touch)", (-112.13, 36.095, -112.09, 36.120)),
    ("Zermatt Alps (must not touch)", (7.72, 45.970, 7.76, 46.000)),
    ("Everest (summit must survive)", (86.9150, 27.9781, 86.9350, 27.9981)),
    ("Manhattan (city must not touch)", (-73.995, 40.750, -73.975, 40.765)),
    ("Amsterdam (flat city, guard safety)", (4.895, 52.360, 4.915, 52.375)),
    ("Flevoland polder (flat land, safety)", (5.44, 52.40, 5.50, 52.46)),
]
print()
for name, box in BOXES:
    fine, st1 = hr.raster(box, 14, TER, "terrarium")
    coarse, st2 = hr.raster(box, 10, TER, "terrarium")
    if fine is None or coarse is None:
        print(f"### {name}: fetch issue {st1} {st2}"); continue
    g, nB = zoom_anchor(fine, coarse)
    g, nA = med8_iter(g)
    def stats(a):
        p2, p98 = np.percentile(a, [2, 98])
        return f"min={a.min():.1f} max={a.max():.1f} median={np.median(a):.1f} p98-p2={p98-p2:.1f}"
    print(f"### {name} px={fine.size} anchorChanged={nB} med8Changed={nA}")
    print(f"  before: {stats(fine)}")
    print(f"  after : {stats(g)}")
