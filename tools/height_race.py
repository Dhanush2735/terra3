#!/usr/bin/env python3
"""Heightmap championship (offline, no game code touched).

Three sources, same boxes:
  TERRARIUM   - current book: AWS terrain tiles, Terrarium PNG z14
  MAPTERHORN  - community tiles, Terrarium-encoded WebP z13
  COPERNICUS  - GLO-30 COGs via Planetary Computer statistics API

Scores per place: level (median), peak (max), flatness
(% pixels >10 m from median), roughness (median |v - neighbour
median|), spikes (% |v - neighbour median| > 60 m), ocean depth,
coverage. Results -> height_race_results.json
"""
import io, json, math, sys, urllib.request

try:
    import numpy as np
except ImportError:
    np = None
from PIL import Image

UA = {"User-Agent": "terra-height-race"}
OUT = {"tests": []}

def fetch(url, data=None, ctype=None, timeout=60):
    req = urllib.request.Request(url, data=data, headers=UA)
    if ctype:
        req.add_header("Content-Type", ctype)
    return urllib.request.urlopen(req, timeout=timeout).read()

# ---------- raster sources (Terrarium decode) ----------
def tile_range(box, z):
    lon0, lat0, lon1, lat1 = box
    n = 2 ** z
    def tx(lon): return int((lon + 180) / 360 * n)
    def ty(lat):
        return int((1 - math.log(math.tan(math.radians(lat)) +
                    1 / math.cos(math.radians(lat))) / math.pi) / 2 * n)
    return tx(lon0), ty(lat1), tx(lon1), ty(lat0), n

def raster(box, z, template, fmt_note):
    """Stitch tiles, crop to box, return numpy array of metres."""
    lon0, lat0, lon1, lat1 = box
    x0, y0, x1, y1, n = tile_range(box, z)
    tiles = {}
    size = None
    misses = 0
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            url = template.format(z=z, x=x, y=y)
            try:
                raw = fetch(url, timeout=45)
                im = Image.open(io.BytesIO(raw)).convert("RGB")
                size = im.size[0]
                tiles[(x, y)] = np.asarray(im, dtype=np.float64)
            except Exception:
                misses += 1
    if not tiles:
        return None, f"NO TILES ({fmt_note}) misses={misses}"
    S = size
    W = (x1 - x0 + 1) * S
    H = (y1 - y0 + 1) * S
    full = np.zeros((H, W, 3))
    for (x, y), arr in tiles.items():
        full[(y - y0) * S:(y - y0 + 1) * S, (x - x0) * S:(x - x0 + 1) * S] = arr
    def gx(lon): return int(((lon + 180) / 360 * n - x0) * S)
    def gy(lat):
        gyv = (1 - math.log(math.tan(math.radians(lat)) +
               1 / math.cos(math.radians(lat))) / math.pi) / 2 * n
        return int((gyv - y0) * S)
    cx0, cx1 = max(0, gx(lon0)), min(W, gx(lon1))
    cy0, cy1 = max(0, gy(lat1)), min(H, gy(lat0))
    crop = full[cy0:cy1, cx0:cx1]
    if crop.size == 0:
        return None, "empty crop"
    h = crop[:, :, 0] * 256 + crop[:, :, 1] + crop[:, :, 2] / 256 - 32768
    return h, f"ok tiles={len(tiles)} misses={misses} px={h.size}"

def raster_report(box, z, template, note):
    if np is None:
        return {"status": "numpy missing"}
    h, status = raster(box, z, template, note)
    if h is None:
        return {"status": status}
    med = float(np.median(h))
    p2, p98 = [float(v) for v in np.percentile(h, [2, 98])]
    rep = {"status": status, "median": round(med, 1),
           "min": round(float(h.min()), 1), "max": round(float(h.max()), 1),
           "p98-p2": round(p98 - p2, 1),
           "flat_out_pct(>10m from median)":
               round(float((np.abs(h - med) > 10).mean() * 100), 3)}
    # neighbour-median roughness / spikes
    c = h[1:-1, 1:-1]
    neigh = np.stack([h[:-2, :-2], h[:-2, 1:-1], h[:-2, 2:], h[1:-1, :-2],
                      h[1:-1, 2:], h[2:, :-2], h[2:, 1:-1], h[2:, 2:]])
    med8 = np.median(neigh, axis=0)
    d = np.abs(c - med8)
    rep["rough_median_m"] = round(float(np.median(d)), 2)
    rep["spike_pct(>60m)"] = round(float((d > 60).mean() * 100), 3)
    return rep

# ---------- Copernicus via Planetary Computer ----------
_item_cache = {}
def cop_item(lat, lon):
    key = (int(math.floor(lat)), int(math.floor(lon)))
    if key in _item_cache:
        return _item_cache[key]
    q = {"collections": ["cop-dem-glo-30"],
         "bbox": [lon - 0.01, lat - 0.01, lon + 0.01, lat + 0.01], "limit": 1}
    item = None
    try:
        d = json.loads(fetch(
            "https://planetarycomputer.microsoft.com/api/stac/v1/search",
            data=json.dumps(q).encode(), ctype="application/json"))
        if d["features"]:
            item = d["features"][0]["id"]
    except Exception as e:
        item = f"ERR {e!r}"
    _item_cache[key] = item
    return item

def cop_report(box):
    lon0, lat0, lon1, lat1 = box
    item = cop_item((lat0 + lat1) / 2, (lon0 + lon1) / 2)
    if not item or str(item).startswith("ERR"):
        return {"status": f"NO ITEM ({item})"}
    poly = {"type": "Feature",
            "geometry": {"type": "Polygon", "coordinates": [[
                [lon0, lat0], [lon1, lat0], [lon1, lat1], [lon0, lat1],
                [lon0, lat0]]]},
            "properties": {}}
    try:
        d = json.loads(fetch(
            "https://planetarycomputer.microsoft.com/api/data/v1/item/"
            f"statistics?collection=cop-dem-glo-30&item={item}&assets=data",
            data=json.dumps(poly).encode(), ctype="application/json",
            timeout=120))
        s = list(d["properties"]["statistics"].values())[0]
        rep = {"status": f"ok item={item}", "median": round(s["median"], 1),
               "min": round(s["min"], 1), "max": round(s["max"], 1)}
        if "percentile_2" in s and "percentile_98" in s:
            rep["p98-p2"] = round(s["percentile_98"] - s["percentile_2"], 1)
        if "std" in s:
            rep["std"] = round(s["std"], 2)
        return rep
    except Exception as e:
        return {"status": f"ERR {e!r}"}

TER = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
MAP = "https://tiles.mapterhorn.com/{z}/{x}/{y}.webp"

TESTS = [
    # name, box (lon0,lat0,lon1,lat1), kind, truth note
    ("Hyderabad Charminar (city anchor)", (78.4697, 17.3567, 78.4797, 17.3667), "anchor", "game-proven 508 m"),
    ("Everest (peak anchor)", (86.9150, 27.9781, 86.9350, 27.9981), "peak", "true 8848 m; game read 8728.9"),
    ("Kilimanjaro (peak anchor)", (37.3433, -3.0858, 37.3633, -3.0658), "peak", "true 5895 m; game 5865.7"),
    ("Dead Sea mid water (flat)", (35.515, 31.48, 35.555, 31.52), "flat", "one flat level ~-412..-439"),
    ("Caspian Sea water (flat)", (50.45, 41.95, 50.55, 42.05), "flat", "surface ~-29 m"),
    ("Flevoland polder (flat, below sea)", (5.44, 52.40, 5.50, 52.46), "flat", "farmland ~-5 m"),
    ("Death Valley (below-sea anchor)", (-116.7777, 36.2198, -116.7577, 36.2398), "anchor", "true -86 m; game -80.8"),
    ("Manhattan (city bumps)", (-73.995, 40.750, -73.975, 40.765), "city", "dense towers; ground should be low+flat"),
    ("Amsterdam old town (city bumps)", (4.895, 52.360, 4.915, 52.375), "city", "canals; NL ground model exists"),
    ("Tokyo (city bumps)", (139.74, 35.675, 139.76, 35.690), "city", "JP national data exists"),
    ("Grand Canyon (steep spikes)", (-112.13, 36.095, -112.09, 36.120), "steep", "gorge; radar-shadow family"),
    ("Zermatt Alps (steep spikes)", (7.72, 45.970, 7.76, 46.000), "steep", "high Alps gorge/valley"),
    ("Amazon canopy (bumps)", (-60.02, -3.12, -59.98, -3.08), "city", "forest: surface books read tree tops"),
    ("Mariana Trench (ocean depth)", (142.54, 11.32, 142.64, 11.42), "ocean", "true ~-10900 m; game z8 -10389"),
    ("Bay of Bengal (ocean depth)", (86.95, 15.95, 87.05, 16.05), "ocean", "game -2730.6 m"),
    ("Singapore (low island city)", (103.8098, 1.3421, 103.8298, 1.3621), "anchor", "low flat island ~5-15 m"),
]

def main():
    for name, box, kind, truth in TESTS:
        print(f"### {name} [{kind}] truth: {truth}", flush=True)
        row = {"name": name, "kind": kind, "truth": truth, "box": box}
        row["terrarium"] = raster_report(box, 14, TER, "terrarium")
        print("  TERRARIUM ", json.dumps(row["terrarium"]), flush=True)
        row["mapterhorn"] = raster_report(box, 13, MAP, "mapterhorn")
        print("  MAPTERHORN", json.dumps(row["mapterhorn"]), flush=True)
        if kind == "ocean":
            row["terrarium_z8_ocean"] = raster_report(box, 8, TER, "terr z8")
            print("  TERR-Z8   ", json.dumps(row["terrarium_z8_ocean"]), flush=True)
        row["copernicus"] = cop_report(box)
        print("  COPERNICUS", json.dumps(row["copernicus"]), flush=True)
        OUT["tests"].append(row)
    dest = "/home/user/B/terra3/tools/height_race_results.json"
    with open(dest, "w") as f:
        json.dump(OUT, f, indent=1)
    print("WROTE", dest)

if __name__ == "__main__":
    main()
