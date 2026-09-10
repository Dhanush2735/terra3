#!/usr/bin/env python3
# ============================================================
# terra_world_reader.py — parse a Minecraft world save offline
# and render it into images + reports an AI (or human) can read.
#
# Self-checking design: EVERY stage logs
#   "WORLD CHECK <stage>: PASS/WARN/FAIL — details"
# and the run ends with a self-test score. `--selftest` builds a
# synthetic world in memory and verifies the whole pipeline
# against known ground truth before any real file is trusted.
#
# Usage:
#   python terra_world_reader.py --selftest
#   python terra_world_reader.py <world_dir_or_region_dir> [out_dir]
#   python terra_world_reader.py saves/New World --census --ascii
#
# Outputs (in out_dir, default ./world_report):
#   map_r.X.Z.png     top-down render per region (block colors)
#   census.txt        block counts per region (era detection)
#   report.txt        all WORLD CHECK lines + summary
#
# Pure stdlib + optional Pillow (PNG). NBT/Anvil parsing is
# implemented here directly — no external MC libraries.
# ============================================================
import sys, os, io, zlib, gzip, struct, argparse, time
from collections import Counter, defaultdict

LOG = []
SCORE = Counter()

def check(stage, verdict, msg):
    line = f"WORLD CHECK {stage}: {verdict} — {msg}"
    LOG.append(line)
    SCORE[verdict] += 1
    print(line, flush=True)

# ══════════════════════════════════════════════════════════════
# NBT PARSER (self-contained)
# ══════════════════════════════════════════════════════════════
TAG_END, TAG_BYTE, TAG_SHORT, TAG_INT, TAG_LONG = 0, 1, 2, 3, 4
TAG_FLOAT, TAG_DOUBLE, TAG_BYTE_ARRAY, TAG_STRING = 5, 6, 7, 8
TAG_LIST, TAG_COMPOUND, TAG_INT_ARRAY, TAG_LONG_ARRAY = 9, 10, 11, 12

class NBT:
    """Minimal, strict NBT reader. Returns python dict/list/scalars."""
    def __init__(self, data: bytes):
        self.d = data; self.p = 0
    def u1(self):
        v = self.d[self.p]; self.p += 1; return v
    def i1(self):
        v = struct.unpack_from(">b", self.d, self.p)[0]; self.p += 1; return v
    def i2(self):
        v = struct.unpack_from(">h", self.d, self.p)[0]; self.p += 2; return v
    def i4(self):
        v = struct.unpack_from(">i", self.d, self.p)[0]; self.p += 4; return v
    def i8(self):
        v = struct.unpack_from(">q", self.d, self.p)[0]; self.p += 8; return v
    def f4(self):
        v = struct.unpack_from(">f", self.d, self.p)[0]; self.p += 4; return v
    def f8(self):
        v = struct.unpack_from(">d", self.d, self.p)[0]; self.p += 8; return v
    def s(self):
        n = struct.unpack_from(">H", self.d, self.p)[0]; self.p += 2
        v = self.d[self.p:self.p+n].decode("utf-8", "replace"); self.p += n
        return v
    def payload(self, t):
        if t == TAG_BYTE:   return self.i1()
        if t == TAG_SHORT:  return self.i2()
        if t == TAG_INT:    return self.i4()
        if t == TAG_LONG:   return self.i8()
        if t == TAG_FLOAT:  return self.f4()
        if t == TAG_DOUBLE: return self.f8()
        if t == TAG_STRING: return self.s()
        if t == TAG_BYTE_ARRAY:
            n = self.i4(); v = self.d[self.p:self.p+n]; self.p += n; return v
        if t == TAG_INT_ARRAY:
            n = self.i4()
            v = list(struct.unpack_from(f">{n}i", self.d, self.p))
            self.p += 4*n; return v
        if t == TAG_LONG_ARRAY:
            n = self.i4()
            v = list(struct.unpack_from(f">{n}q", self.d, self.p))
            self.p += 8*n; return v
        if t == TAG_LIST:
            et = self.u1(); n = self.i4()
            return [self.payload(et) for _ in range(n)]
        if t == TAG_COMPOUND:
            out = {}
            while True:
                tt = self.u1()
                if tt == TAG_END: return out
                name = self.s()
                out[name] = self.payload(tt)
        raise ValueError(f"bad tag {t} @ {self.p}")
    @staticmethod
    def parse(data: bytes):
        r = NBT(data)
        t = r.u1()
        if t != TAG_COMPOUND:
            raise ValueError(f"root tag {t} != compound")
        r.s()  # root name
        return r.payload(TAG_COMPOUND)

def nbt_write(obj) -> bytes:
    """Minimal writer for the self-test (compound/list/scalars)."""
    out = io.BytesIO()
    def ws(s):
        b = s.encode(); out.write(struct.pack(">H", len(b))); out.write(b)
    def wp(t, v):
        if t == TAG_BYTE: out.write(struct.pack(">b", v))
        elif t == TAG_INT: out.write(struct.pack(">i", v))
        elif t == TAG_LONG: out.write(struct.pack(">q", v))
        elif t == TAG_STRING: ws(v)
        elif t == TAG_LONG_ARRAY:
            out.write(struct.pack(">i", len(v)))
            for x in v: out.write(struct.pack(">q", x))
        elif t == TAG_LIST:
            et, items = v
            out.write(struct.pack(">b", et))
            out.write(struct.pack(">i", len(items)))
            for it in items: wp(et, it)
        elif t == TAG_COMPOUND:
            for name, (tt, vv) in v.items():
                out.write(struct.pack(">b", tt)); ws(name); wp(tt, vv)
            out.write(b"\x00")
        else: raise ValueError(t)
    out.write(b"\x0a"); ws("")
    wp(TAG_COMPOUND, obj)
    return out.getvalue()

# ══════════════════════════════════════════════════════════════
# ANVIL REGION PARSER
# ══════════════════════════════════════════════════════════════
def read_region(path):
    """Yield (cx, cz, nbt_dict) for every chunk present."""
    with open(path, "rb") as f:
        raw = f.read()
    if len(raw) < 8192:
        check("region", "FAIL", f"{os.path.basename(path)}: "
              f"only {len(raw)} bytes (header needs 8192)")
        return
    n_ok = n_bad = 0
    for idx in range(1024):
        entry = struct.unpack_from(">I", raw, idx * 4)[0]
        offset = (entry >> 8) * 4096
        sectors = entry & 0xFF
        if offset == 0 or sectors == 0:
            continue
        cx, cz = idx % 32, idx // 32
        try:
            ln = struct.unpack_from(">I", raw, offset)[0]
            comp = raw[offset + 4]
            body = raw[offset + 5: offset + 4 + ln]
            if comp == 2:   data = zlib.decompress(body)
            elif comp == 1: data = gzip.decompress(body)
            elif comp == 3: data = body
            else:
                n_bad += 1; continue
            yield cx, cz, NBT.parse(data)
            n_ok += 1
        except Exception as e:
            n_bad += 1
    total = n_ok + n_bad
    v = "PASS" if n_bad == 0 else ("WARN" if n_bad < total // 10 else "FAIL")
    check("region", v, f"{os.path.basename(path)}: "
          f"{n_ok} chunks parsed, {n_bad} failed")

# ══════════════════════════════════════════════════════════════
# CHUNK → SURFACE  (1.18+ 'sections' format)
# ══════════════════════════════════════════════════════════════
def chunk_surface(nbtc):
    """Return 16x16 arrays (block_id, y) of the top non-air block."""
    sections = nbtc.get("sections") or nbtc.get("Sections")
    if not sections:
        return None, None
    top_id = [["" for _ in range(16)] for _ in range(16)]
    top_y = [[None for _ in range(16)] for _ in range(16)]
    filled = 0
    for sec in sorted(sections,
                      key=lambda s2: s2.get("Y", 0), reverse=True):
        if filled == 256:
            break
        bs = sec.get("block_states")
        if not bs:
            continue
        pal = bs.get("palette")
        if not pal:
            continue
        names = [p.get("Name", "?") for p in pal]
        data = bs.get("data")
        sy = sec.get("Y", 0) * 16
        if data is None:
            # whole section is palette[0]
            if names[0] == "minecraft:air":
                continue
            for lz in range(16):
                for lx in range(16):
                    if top_y[lz][lx] is None:
                        top_y[lz][lx] = sy + 15
                        top_id[lz][lx] = names[0]
                        filled += 1
            continue
        bits = max(4, (len(names) - 1).bit_length())
        per_long = 64 // bits
        mask = (1 << bits) - 1
        for y in range(15, -1, -1):
            layer_base = y * 256
            for lz in range(16):
                if all(top_y[lz][lx] is not None for lx in range(16)):
                    continue
                for lx in range(16):
                    if top_y[lz][lx] is not None:
                        continue
                    i = layer_base + lz * 16 + lx
                    li, si = divmod(i, per_long)
                    if li >= len(data):
                        continue
                    v = (data[li] >> (si * bits)) & mask
                    if v >= len(names):
                        continue
                    nm = names[v]
                    if nm != "minecraft:air":
                        top_y[lz][lx] = sy + y
                        top_id[lz][lx] = nm
                        filled += 1
    return top_id, top_y

# ══════════════════════════════════════════════════════════════
# COLORS / SYMBOLS  (map legend)
# ══════════════════════════════════════════════════════════════
COLOR = {
    "minecraft:water": (63, 118, 228), "minecraft:ice": (160, 200, 255),
    "minecraft:grass_block": (95, 159, 53), "minecraft:grass": (110, 170, 60),
    "minecraft:sand": (219, 207, 163), "minecraft:sandstone": (216, 202, 155),
    "minecraft:stone": (125, 125, 125), "minecraft:dirt": (134, 96, 67),
    "minecraft:gravel": (136, 126, 126), "minecraft:dirt_path": (148, 121, 65),
    "minecraft:black_concrete": (25, 25, 25),
    "minecraft:gray_concrete": (85, 85, 85),
    "minecraft:cobblestone": (110, 110, 110),
    "minecraft:smooth_stone": (158, 158, 158),
    "minecraft:bricks": (150, 97, 83),
    "minecraft:stone_bricks": (122, 122, 122),
    "minecraft:white_concrete": (207, 213, 214),
    "minecraft:oak_leaves": (60, 120, 35),
    "minecraft:spruce_leaves": (50, 90, 50),
    "minecraft:birch_leaves": (100, 140, 70),
    "minecraft:oak_log": (102, 81, 50),
    "minecraft:oak_planks": (162, 130, 78),
    "minecraft:farmland": (110, 80, 50),
    "minecraft:snow": (240, 240, 250),
    "minecraft:sea_lantern": (200, 230, 220),
}
DEFAULT_COLOR = (170, 60, 170)  # magenta = unknown (visible!)

SYM = {
    "minecraft:water": "~", "minecraft:black_concrete": "=",
    "minecraft:gray_concrete": "=", "minecraft:cobblestone": "=",
    "minecraft:gravel": "-", "minecraft:dirt_path": "-",
    "minecraft:smooth_stone": "B", "minecraft:grass_block": ",",
    "minecraft:sand": "s", "minecraft:oak_leaves": "T",
    "minecraft:bricks": "#", "minecraft:stone_bricks": "#",
    "minecraft:white_concrete": "#", "minecraft:oak_planks": "#",
}

ERA_HINTS = {
    "modern":   ["minecraft:black_concrete"],
    "1900s":    ["minecraft:gray_concrete"],
    "victorian/roman": ["minecraft:cobblestone"],
    "medieval": ["minecraft:gravel"],
}

# ══════════════════════════════════════════════════════════════
# RENDER + CENSUS
# ══════════════════════════════════════════════════════════════
def process_region(path, out_dir, ascii_map=False):
    t0 = time.time()
    name = os.path.basename(path)
    grid_id = {}
    grid_y = {}
    census = Counter()
    chunks = 0
    for cx, cz, nbtc in read_region(path):
        ids, ys = chunk_surface(nbtc)
        if ids is None:
            continue
        chunks += 1
        for lz in range(16):
            for lx in range(16):
                bid = ids[lz][lx]
                if not bid:
                    continue
                census[bid] += 1
                grid_id[(cx*16+lx, cz*16+lz)] = bid
                grid_y[(cx*16+lx, cz*16+lz)] = ys[lz][lx]
    if chunks == 0:
        check("surface", "FAIL", f"{name}: no chunks with sections")
        return None
    cov = 100.0 * len(grid_id) / (chunks * 256)
    check("surface", "PASS" if cov > 95 else "WARN",
          f"{name}: {chunks} chunks, surface coverage "
          f"{cov:.0f}% ({len(grid_id)} columns)")

    # unknown-color rate (unknown-block self-check)
    unknown = sum(n for b, n in census.items() if b not in COLOR)
    total = sum(census.values())
    upct = 100.0 * unknown / max(1, total)
    top_unknown = [f"{b}x{n}" for b, n in census.most_common(40)
                   if b not in COLOR][:3]
    check("colors", "PASS" if upct < 15 else "WARN",
          f"{name}: {upct:.0f}% surface blocks without palette "
          f"color (render magenta) top:{' '.join(top_unknown)}")

    # PNG render
    png_path = None
    try:
        from PIL import Image
        img = Image.new("RGB", (512, 512), (10, 10, 14))
        px = img.load()
        ymin = min((y for y in grid_y.values() if y is not None),
                   default=0)
        ymax = max((y for y in grid_y.values() if y is not None),
                   default=1)
        for (x, z), bid in grid_id.items():
            r, g, b = COLOR.get(bid, DEFAULT_COLOR)
            y = grid_y.get((x, z))
            if y is not None and ymax > ymin:
                shade = 0.65 + 0.35 * (y - ymin) / (ymax - ymin)
                r, g, b = int(r*shade), int(g*shade), int(b*shade)
            px[x, z] = (r, g, b)
        png_path = os.path.join(out_dir,
            f"map_{name.replace('.mca','')}.png")
        img.save(png_path)
        check("render", "PASS",
              f"{name}: {png_path} ({img.size[0]}x{img.size[1]}, "
              f"y {ymin}..{ymax})")
    except Exception as e:
        check("render", "FAIL", f"{name}: PNG failed: {e}")

    # era census
    era_counts = {era: sum(census.get(b, 0) for b in blocks)
                  for era, blocks in ERA_HINTS.items()}
    era_line = "  ".join(f"{k}={v}" for k, v in era_counts.items())
    dominant = max(era_counts, key=era_counts.get) \
        if any(era_counts.values()) else "none-detected"
    check("era", "PASS",
          f"{name}: road-material era census: {era_line} "
          f"-> dominant: {dominant}")

    if ascii_map:
        step = max(1, 512 // 96)
        lines = []
        for z in range(0, 512, step):
            row = []
            for x in range(0, 512, step):
                bid = grid_id.get((x, z))
                row.append(SYM.get(bid, "." if bid else " "))
            lines.append("".join(row))
        with open(os.path.join(out_dir,
                f"ascii_{name.replace('.mca','')}.txt"),
                "w") as f:
            f.write("\n".join(lines))

    check("timing", "PASS",
          f"{name}: processed in {time.time()-t0:.1f}s")
    return census

# ══════════════════════════════════════════════════════════════
# SELF-TEST — build a synthetic region, verify round-trip
# ══════════════════════════════════════════════════════════════
def build_test_chunk(cx, cz, surface_block, y_surface):
    """Construct a real 1.20-format chunk NBT with one flat layer."""
    sec_y = y_surface // 16
    local_y = y_surface % 16
    palette = [
        {"Name": (TAG_STRING, "minecraft:air")},
        {"Name": (TAG_STRING, "minecraft:stone")},
        {"Name": (TAG_STRING, surface_block)},
    ]
    bits = 4
    per_long = 64 // bits
    vals = []
    for y in range(16):
        for i in range(256):
            vals.append(2 if y == local_y else (1 if y < local_y else 0))
    longs = []
    for i in range(0, len(vals), per_long):
        v = 0
        for j, x in enumerate(vals[i:i+per_long]):
            v |= (x & 0xF) << (j * bits)
        if v >= 1 << 63:
            v -= 1 << 64
        longs.append(v)
    pal_c = [( "palette_entry", p) for p in palette]
    section = {
        "Y": (TAG_BYTE, sec_y),
        "block_states": (TAG_COMPOUND, {
            "palette": (TAG_LIST, (TAG_COMPOUND, [
                {"Name": p["Name"]} for p in palette])),
            "data": (TAG_LONG_ARRAY, longs),
        }),
    }
    return {
        "xPos": (TAG_INT, cx), "zPos": (TAG_INT, cz),
        "Status": (TAG_STRING, "minecraft:full"),
        "sections": (TAG_LIST, (TAG_COMPOUND, [section])),
    }

def selftest(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    print("=== SELF-TEST: synthetic region round-trip ===")
    # Region: chunk(0,0)=grass@y64, chunk(1,0)=water-ish blue block,
    # chunk(0,1)=black_concrete road@70, chunk(1,1)=cobblestone@70
    spec = {
        (0, 0): ("minecraft:grass_block", 64),
        (1, 0): ("minecraft:water", 62),
        (0, 1): ("minecraft:black_concrete", 70),
        (1, 1): ("minecraft:cobblestone", 70),
    }
    # build .mca
    payloads = {}
    for (cx, cz), (blk, y) in spec.items():
        payloads[(cx, cz)] = zlib.compress(
            nbt_write(build_test_chunk(cx, cz, blk, y)))
    header = bytearray(8192)
    body = bytearray()
    sector = 2
    for (cx, cz), comp in payloads.items():
        idx = cx + cz * 32
        chunk_data = struct.pack(">IB", len(comp) + 1, 2) + comp
        pad = (-len(chunk_data)) % 4096
        chunk_data += b"\x00" * pad
        sectors = len(chunk_data) // 4096
        struct.pack_into(">I", header, idx * 4,
                         (sector << 8) | sectors)
        body += chunk_data
        sector += sectors
    mca = os.path.join(out_dir, "r.0.0.mca")
    with open(mca, "wb") as f:
        f.write(bytes(header) + bytes(body))
    check("selftest-build", "PASS",
          f"synthetic region written ({len(header)+len(body)} bytes, "
          f"{len(spec)} chunks)")

    # parse it back and verify EVERY expectation
    got = {}
    for cx, cz, nbtc in read_region(mca):
        ids, ys = chunk_surface(nbtc)
        got[(cx, cz)] = (ids[0][0], ys[0][0])  # corner column
    ok = True
    for k, (blk, y) in spec.items():
        g = got.get(k)
        if g is None:
            check("selftest-verify", "FAIL", f"chunk {k} missing"); ok = False
        elif g[0] != blk or g[1] != y:
            check("selftest-verify", "FAIL",
                  f"chunk {k}: got {g}, want ({blk},{y})"); ok = False
    if ok:
        check("selftest-verify", "PASS",
              f"all {len(spec)} chunks: surface block AND height "
              f"exactly match ground truth")
    # full pipeline incl. PNG + census + era
    census = process_region(mca, out_dir, ascii_map=True)
    if census:
        want = {("minecraft:grass_block"): 256,
                ("minecraft:water"): 256,
                ("minecraft:black_concrete"): 256,
                ("minecraft:cobblestone"): 256}
        bad = [b for b, n in want.items() if census.get(b) != n]
        check("selftest-census",
              "PASS" if not bad else "FAIL",
              "all 4 block counts == 256" if not bad
              else f"wrong counts for {bad}: "
                   f"{ {b: census.get(b) for b in bad} }")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("world", nargs="?",
                    help="world dir, region dir, or single .mca")
    ap.add_argument("out", nargs="?", default="world_report")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--ascii", action="store_true")
    ap.add_argument("--census", action="store_true")
    a = ap.parse_args()

    os.makedirs(a.out, exist_ok=True)
    if a.selftest:
        selftest(a.out)
    elif a.world:
        # find region files
        cands = []
        if a.world.endswith(".mca"):
            cands = [a.world]
        else:
            for sub in ("region", "."):
                d = os.path.join(a.world, sub)
                if os.path.isdir(d):
                    cands += [os.path.join(d, f)
                              for f in sorted(os.listdir(d))
                              if f.endswith(".mca")]
                    if cands:
                        break
        check("discover", "PASS" if cands else "FAIL",
              f"{len(cands)} region file(s) found")
        total_census = Counter()
        for p in cands[:16]:  # cap: 16 regions per run
            cen = process_region(p, a.out, ascii_map=a.ascii)
            if cen:
                total_census.update(cen)
        if a.census and total_census:
            with open(os.path.join(a.out, "census.txt"), "w") as f:
                for b, n in total_census.most_common():
                    f.write(f"{n:>10}  {b}\n")
            check("census", "PASS",
                  f"census.txt: {len(total_census)} distinct "
                  f"surface blocks, {sum(total_census.values())} columns")
    else:
        ap.print_help(); return 2

    with open(os.path.join(a.out, "report.txt"), "w") as f:
        f.write("\n".join(LOG) + "\n")
    print(f"\nself-test: {SCORE['PASS']} pass, "
          f"{SCORE['WARN']} warn, {SCORE['FAIL']} fail")
    print(f"report: {os.path.join(a.out, 'report.txt')}")
    return 1 if SCORE["FAIL"] else 0

if __name__ == "__main__":
    sys.exit(main())
