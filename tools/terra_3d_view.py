#!/usr/bin/env python3
# ============================================================
# terra_3d_view.py — full-3D views of Minecraft world data.
#
# Companion to terra_world_reader.py (which only keeps the
# surface of each chunk). This tool decodes EVERY block in a
# chunk and draws small areas as flat-colour models:
#   view_top.png       top-down (map view)
#   view_from_NW.png   isometric from the north-west corner
#   view_from_NE.png   isometric from the north-east corner
#   view_from_SE.png   isometric from the south-east corner
#   view_from_SW.png   isometric from the south-west corner
# (+x = east, +z = south, -z = north, as in Minecraft.)
#
# Modes:
#   terra_3d_view.py --demo [out_dir]
#       Builds a small KNOWN test town (house, tower, road
#       cross, pond, tree) in code, packs it into a real
#       .mca region file, reads it back, checks every block
#       matches, then renders the 5 views. Proves the whole
#       file -> 3D pipeline before any real world is trusted.
#   terra_3d_view.py --cubetest [out_dir]
#       Writes a synthetic TerraCubes world (.cc3 files, the
#       layout the mod's CubeStorage writes) and reads it
#       back. Proves the cube decoder on known data.
#   terra_3d_view.py <world> [out_dir] [--box x0 z0 x1 z1]
#       <world> may be: one region .mca, one cube .cc3, a
#       folder of region files, or a folder with cubes/*.cc3.
#       Without --box, the area of everything built is found
#       automatically. Scenes wider than 400 blocks in x+z
#       are refused (render a smaller crop).
#       --slices          one floor plan per built level
#       --profile X0 Z0 X1 Z1   surface heights along a line
#       --entities <dir>  living things (entities/*.mca) as
#                         dots on the top view + entities.txt
#       legend.txt (always): every block, count, drawn colour,
#       and whether that colour is true or an auto stand-in.
#
# Pure stdlib + Pillow. Reuses the reader's NBT/Anvil code.
#
# Also reads TerraCubes worlds: cubes/r.X.Y.Z.cc3 files
# (format read from the mod's own CubeStorage.java + Cube.java:
# 32768-int offset header, per cube: int length + gzip NBT with
# a vanilla-style "block_states" palette container).
# ============================================================
import sys, os, io, zlib, struct, argparse, math
import colorsys, gzip, json, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import terra_world_reader as twr
from terra_world_reader import check, LOG, SCORE, COLOR, DEFAULT_COLOR
from PIL import Image, ImageDraw

AIR = "minecraft:air"

# extra colours this tool needs beyond the reader's map legend
COLOR.update({
    "minecraft:bricks": (150, 97, 83),
    "minecraft:stone_bricks": (122, 122, 122),
    "minecraft:oak_log": (102, 81, 50),
    "minecraft:oak_planks": (162, 130, 78),
    "minecraft:oak_leaves": (60, 120, 35),
    "minecraft:dirt": (134, 96, 67),
    "minecraft:water": (63, 118, 228),
    "minecraft:bedrock": (85, 85, 85),
    # 1.20 building blocks (adventure-map worlds use these a lot)
    "minecraft:mud_bricks": (146, 106, 80),
    "minecraft:mud_brick_stairs": (146, 106, 80),
    "minecraft:packed_mud": (143, 119, 88),
    "minecraft:prismarine_bricks": (99, 175, 165),
    # redstone / command machinery of adventure maps
    "minecraft:command_block": (200, 130, 60),
    "minecraft:chain_command_block": (101, 175, 149),
    "minecraft:repeating_command_block": (140, 110, 190),
    "minecraft:repeater": (160, 60, 50),
    "minecraft:redstone_block": (200, 40, 30),
    "minecraft:redstone_wire": (200, 40, 30),
    "minecraft:oak_wall_sign": (162, 130, 78),
    # invisible in game; drawn pale so the machinery can be seen
    "minecraft:light": (245, 240, 200),
    "minecraft:barrier": (190, 215, 230),
    # Terra-earth ground + modern town materials (real Terra
    # worlds are mostly these; hash stand-ins mislead badly —
    # e.g. dark oak roofs came out bright yellow)
    "minecraft:tuff": (108, 112, 100),
    "minecraft:coarse_dirt": (119, 85, 59),
    "minecraft:jungle_leaves": (58, 118, 40),
    "minecraft:glass_pane": (205, 232, 236),
    "minecraft:dark_oak_planks": (66, 43, 20),
    "minecraft:spruce_planks": (106, 78, 47),
    "minecraft:jungle_log": (85, 68, 42),
    "minecraft:stripped_oak_log": (177, 148, 96),
    "minecraft:oak_door": (171, 136, 83),
    "minecraft:brown_terracotta": (150, 97, 72),
    "minecraft:white_terracotta": (209, 178, 161),
    "minecraft:light_gray_terracotta": (214, 202, 191),
    "minecraft:cyan_terracotta": (86, 109, 109),
    "minecraft:polished_andesite": (173, 178, 178),
    "minecraft:light_gray_concrete": (166, 166, 161),
    "minecraft:yellow_concrete": (241, 195, 34),
    "minecraft:quartz_pillar": (236, 232, 224),
    "minecraft:lantern": (226, 183, 95),
    "minecraft:poppy": (205, 45, 32),
    "minecraft:allium": (196, 128, 224),
    "minecraft:azure_bluet": (224, 224, 214),
    "minecraft:cornflower": (96, 118, 224),
})


def auto_color(name):
    """Deterministic stand-in colour for blocks with no true
    colour in the table (never magenta, stable per block)."""
    hue = (zlib.crc32(name.encode()) % 360) / 360.0
    r, g, b = colorsys.hsv_to_rgb(hue, 0.55, 0.85)
    return (int(r * 255), int(g * 255), int(b * 255))


def colour_for(name):
    return COLOR.get(name) or auto_color(name)


def write_legend(vox, out_dir):
    """legend.txt: every block, its count, its drawn colour,
    and whether that colour is a true material colour or an
    auto stand-in. PASS when auto-coloured blocks are <=10%."""
    from collections import Counter
    counts = Counter(vox.values())
    total = sum(counts.values())
    auto_n = sum(n for b, n in counts.items() if b not in COLOR)
    lines = []
    for b, n in counts.most_common():
        kind = "true" if b in COLOR else "AUTO"
        lines.append(f"{n:>8}  {b:<42} rgb{colour_for(b)}  {kind}")
    with open(os.path.join(out_dir, "legend.txt"), "w") as f:
        f.write("\n".join(lines) + "\n")
    pct = 100.0 * auto_n / max(1, total)
    check("legend", "PASS" if pct <= 10 else "WARN",
          f"legend.txt: {len(counts)} blocks, {pct:.1f}% "
          f"auto-coloured (want <=10%)")

# ══════════════════════════════════════════════════════════════
# FULL 3D CHUNK DECODE  (1.18+ 'sections' format, mirrors the
# packing rules the reader's decoder uses)
# ══════════════════════════════════════════════════════════════
def chunk_voxels(nbtc):
    """Return {(lx, y, lz): block_name} for every non-air block."""
    out = {}
    sections = nbtc.get("sections") or nbtc.get("Sections")
    if not sections:
        return out
    for sec in sections:
        bs = sec.get("block_states")
        if not bs:
            continue
        pal = bs.get("palette")
        if not pal:
            continue
        names = [p.get("Name", "?") for p in pal]
        sy = sec.get("Y", 0) * 16
        data = bs.get("data")
        if data is None:
            if names[0] == AIR:
                continue
            for y in range(16):
                for lz in range(16):
                    for lx in range(16):
                        out[(lx, sy + y, lz)] = names[0]
            continue
        bits = max(4, (len(names) - 1).bit_length())
        per_long = 64 // bits
        mask = (1 << bits) - 1
        for i in range(4096):
            li, si = divmod(i, per_long)
            if li >= len(data):
                break
            v = ((data[li] & 0xFFFFFFFFFFFFFFFF) >> (si * bits)) & mask
            if v >= len(names):
                continue
            nm = names[v]
            if nm == AIR:
                continue
            y, rem = divmod(i, 256)
            lz, lx = divmod(rem, 16)
            out[(lx, sy + y, lz)] = nm
    return out


def region_voxels(path, ocx=0, ocz=0):
    """All non-air voxels of one region file, in world coords.
    (ocx, ocz) = the region's chunk offset in the world, so
    several region files can be merged into one scene."""
    vox = {}
    for cx, cz, nbtc in twr.read_region(path):
        for (lx, y, lz), nm in chunk_voxels(nbtc).items():
            vox[((ocx + cx) * 16 + lx, y, (ocz + cz) * 16 + lz)] = nm
    return vox


def region_offset(path):
    """'r.-1.-2.mca' -> chunk offset (-32, -64). Other names -> (0,0)."""
    parts = os.path.basename(path).replace(".mca", "").split(".")
    try:
        return int(parts[1]) * 32, int(parts[2]) * 32
    except (IndexError, ValueError):
        return 0, 0


def decode_states(bs):
    """{(lx, y, lz): name} from one vanilla PalettedContainer
    dict (a chunk section's or a cube's "block_states")."""
    out = {}
    pal = bs.get("palette")
    if not pal:
        return out
    names = [p.get("Name", "?") for p in pal]
    data = bs.get("data")
    if data is None:
        if names[0] != AIR:
            for y in range(16):
                for lz in range(16):
                    for lx in range(16):
                        out[(lx, y, lz)] = names[0]
        return out
    bits = max(4, (len(names) - 1).bit_length())
    per_long = 64 // bits
    mask = (1 << bits) - 1
    for i in range(4096):
        li, si = divmod(i, per_long)
        if li >= len(data):
            break
        v = ((data[li] & 0xFFFFFFFFFFFFFFFF) >> (si * bits)) & mask
        if v >= len(names):
            continue
        nm = names[v]
        if nm == AIR:
            continue
        y, rem = divmod(i, 256)
        lz, lx = divmod(rem, 16)
        out[(lx, y, lz)] = nm
    return out


CC3_RE = re.compile(r"r\.(-?\d+)\.(-?\d+)\.(-?\d+)\.cc3$")


def iter_cubes(path):
    """Yield (cx, cy, cz, block_states) for every non-empty cube
    in a TerraCubes r.X.Y.Z.cc3 file. Format (from
    CubeStorage.java): 32768 big-endian int offsets (0 = absent);
    at each offset an int length + gzip NBT whose "block_states"
    is a vanilla PalettedContainer. Cube index = localY*1024 +
    localZ*32 + localX, region = cubePos >> 5."""
    m = CC3_RE.search(os.path.basename(path))
    if not m:
        check("cubes", "FAIL", f"{os.path.basename(path)}: "
              f"name is not r.X.Y.Z.cc3")
        return
    rx, ry, rz = (int(m.group(i)) for i in (1, 2, 3))
    with open(path, "rb") as f:
        raw = f.read()
    if len(raw) < 32768 * 4:
        check("cubes", "FAIL", f"{os.path.basename(path)}: "
              f"header truncated ({len(raw)} bytes)")
        return
    for idx in range(32768):
        off = struct.unpack_from(">i", raw, idx * 4)[0]
        if off <= 0 or off + 4 > len(raw):
            continue
        ln = struct.unpack_from(">i", raw, off)[0]
        body = raw[off + 4: off + 4 + ln]
        try:
            data = gzip.decompress(body)
        except Exception:
            try:
                data = zlib.decompress(body)
            except Exception:
                continue
        try:
            root = twr.NBT.parse(data)
        except Exception:
            continue
        bs = root.get("block_states")
        if not bs:
            continue  # all-air cube: block_states is not written
        yield (rx * 32 + (idx % 32), ry * 32 + (idx // 1024),
               rz * 32 + ((idx // 32) % 32), bs)


def tops_of_file(path):
    """{(x, z): (topY, topName)} — the cheap first pass."""
    tops = {}
    if path.endswith(".cc3"):
        for cx, cy, cz, bs in iter_cubes(path):
            for (lx, y, lz), nm in decode_states(bs).items():
                k = (cx * 16 + lx, cz * 16 + lz)
                gy = cy * 16 + y
                if k not in tops or gy > tops[k][0]:
                    tops[k] = (gy, nm)
        return tops
    ocx, ocz = region_offset(path)
    for cx, cz, nbtc in twr.read_region(path):
        ids, ys = twr.chunk_surface(nbtc)
        if ids is None:
            continue
        for lz in range(16):
            for lx in range(16):
                y = ys[lz][lx]
                if y is not None:
                    tops[((ocx + cx) * 16 + lx,
                          (ocz + cz) * 16 + lz)] = (y, ids[lz][lx])
    return tops


def band_of_file(path, tops, depth, box):
    """Second pass: keep only each column's top `depth` layers
    inside box. A real Terra column is solid earth hundreds of
    blocks deep — holding whole columns in memory is not
    possible on a small machine, and the deep earth is not
    structure. (Raise --depth for tall buildings.)"""
    x0, z0, x1, z1 = box
    vox = {}

    def keep(gx, gy, gz, nm):
        if x0 <= gx <= x1 and z0 <= gz <= z1:
            t = tops.get((gx, gz))
            if t is None or gy >= t[0] - depth:
                vox[(gx, gy, gz)] = nm

    if path.endswith(".cc3"):
        for cx, cy, cz, bs in iter_cubes(path):
            if (cx * 16 + 15 < x0 or cx * 16 > x1
                    or cz * 16 + 15 < z0 or cz * 16 > z1):
                continue
            for (lx, y, lz), nm in decode_states(bs).items():
                keep(cx * 16 + lx, cy * 16 + y, cz * 16 + lz, nm)
        return vox
    ocx, ocz = region_offset(path)
    for cx, cz, nbtc in twr.read_region(path):
        gx0, gz0 = (ocx + cx) * 16, (ocz + cz) * 16
        if (gx0 + 15 < x0 or gx0 > x1
                or gz0 + 15 < z0 or gz0 > z1):
            continue
        for sec in nbtc.get("sections") or nbtc.get("Sections") or []:
            bs = sec.get("block_states")
            if not bs:
                continue
            sy = sec.get("Y", 0) * 16
            for (lx, y, lz), nm in decode_states(bs).items():
                keep(gx0 + lx, sy + y, gz0 + lz, nm)
    return vox


# tops that are vegetation, not buildings (for the auto-area)
PLANT_TOPS = {"minecraft:grass", "minecraft:short_grass",
              "minecraft:tall_grass", "minecraft:fern",
              "minecraft:large_fern", "minecraft:dead_bush"}


def world_files(world):
    """Resolve the input to a list of region/cube files."""
    if os.path.isdir(world):
        cube_dir = os.path.join(world, "cubes")
        scan = cube_dir if os.path.isdir(cube_dir) else world
        cc3 = sorted(f for f in os.listdir(scan)
                     if f.endswith(".cc3"))
        if cc3:
            return [os.path.join(scan, f) for f in cc3]
        return [os.path.join(world, f)
                for f in sorted(os.listdir(world))
                if f.endswith(".mca")]
    return [world]


def load_voxels(world, box=None, depth=64):
    """Two-pass load of a .mca/.cc3 file or a folder of them.
    Pass 1 finds every column's top (cheap); pass 2 keeps the
    top `depth` layers per column inside the box. Without a
    box, the box is fitted to everything built (auto-area)."""
    files = world_files(world)
    tops = {}
    for f in files:
        tops.update(tops_of_file(f))
    if not tops:
        return {}
    if box is None:
        pts = [(x, z) for (x, z), (y, nm) in tops.items()
               if nm not in NATURAL and not nm.endswith("_leaves")
               and nm not in PLANT_TOPS]
        if not pts:
            pts = list(tops.keys())
        xs = [p[0] for p in pts]
        zs = [p[1] for p in pts]
        box = (min(xs) - 12, min(zs) - 12,
               max(xs) + 12, max(zs) + 12)
        check("scene", "PASS", f"auto-area: everything built "
              f"fits in x {box[0]}..{box[2]}, z {box[1]}..{box[3]} "
              f"(+12 block margin)")
    vox = {}
    for f in files:
        sub = band_of_file(f, tops, depth, box)
        vox.update(sub)
        check("load", "PASS", f"{os.path.basename(f)}: kept "
              f"{len(sub)} blocks (top {depth} layers per column)")
    return vox


# blocks that are just "the ground itself" — everything else is built
NATURAL = {"minecraft:grass_block", "minecraft:dirt",
           "minecraft:bedrock", "minecraft:stone", "minecraft:water",
           "minecraft:sand", "minecraft:gravel", "minecraft:clay"}




# ══════════════════════════════════════════════════════════════
# ENCODE: voxel dict -> real 1.20-format region (.mca) bytes
# (same conventions as the reader's self-test builder)
# ══════════════════════════════════════════════════════════════
def _pack_block_states(cells):
    """{(lx,y,lz): name} of one 16x16x16 cube -> the (tag, value)
    block_states compound the NBT writer understands."""
    names = [AIR] + sorted(set(cells.values()))
    idx_of = {n: i for i, n in enumerate(names)}
    bits = max(4, (len(names) - 1).bit_length())
    per_long = 64 // bits
    vals = [0] * 4096
    for (lx, ly, lz), nm in cells.items():
        vals[ly * 256 + lz * 16 + lx] = idx_of[nm]
    longs = []
    for i in range(0, 4096, per_long):
        v = 0
        for j, x in enumerate(vals[i:i + per_long]):
            v |= (x & ((1 << bits) - 1)) << (j * bits)
        if v >= 1 << 63:
            v -= 1 << 64
        longs.append(v)
    return {
        "palette": (twr.TAG_LIST, (twr.TAG_COMPOUND,
                    [{"Name": (twr.TAG_STRING, n)} for n in names])),
        "data": (twr.TAG_LONG_ARRAY, longs),
    }


def pack_chunk(vox, cx, cz):
    """vox: {(lx,y,lz): name} local to this chunk -> chunk NBT."""
    by_sec = {}
    for (lx, y, lz), nm in vox.items():
        by_sec.setdefault(y // 16, {})[(lx, y % 16, lz)] = nm
    sections = []
    for sec_y in sorted(by_sec):
        sections.append({
            "Y": (twr.TAG_BYTE, sec_y),
            "block_states": (twr.TAG_COMPOUND,
                             _pack_block_states(by_sec[sec_y])),
        })
    return {
        "xPos": (twr.TAG_INT, cx), "zPos": (twr.TAG_INT, cz),
        "Status": (twr.TAG_STRING, "minecraft:full"),
        "sections": (twr.TAG_LIST, (twr.TAG_COMPOUND, sections)),
    }


def write_region(vox, path):
    """vox in world coords -> .mca file (one region, 32x32 chunks)."""
    by_chunk = {}
    for (x, y, z), nm in vox.items():
        cx, cz = x // 16, z // 16
        assert 0 <= cx < 32 and 0 <= cz < 32, "demo scene must fit one region"
        by_chunk.setdefault((cx, cz), {})[(x % 16, y, z % 16)] = nm
    header = bytearray(8192)
    body = bytearray()
    sector = 2
    for (cx, cz), cells in sorted(by_chunk.items()):
        comp = zlib.compress(twr.nbt_write(pack_chunk(cells, cx, cz)))
        chunk_data = struct.pack(">IB", len(comp) + 1, 2) + comp
        chunk_data += b"\x00" * ((-len(chunk_data)) % 4096)
        sectors = len(chunk_data) // 4096
        struct.pack_into(">I", header, (cx + cz * 32) * 4,
                         (sector << 8) | sectors)
        body += chunk_data
        sector += sectors
    with open(path, "wb") as f:
        f.write(bytes(header) + bytes(body))
    return len(header) + len(body)


def write_cc3(cubes_cells, path):
    """{(cx,cy,cz): {(lx,y,lz): name}} all inside ONE cube region
    -> a TerraCubes .cc3 file (same layout CubeStorage writes)."""
    regs = {(cx >> 5, cy >> 5, cz >> 5) for (cx, cy, cz) in cubes_cells}
    assert len(regs) == 1, "test cubes must share one region"
    header = bytearray(32768 * 4)
    body = bytearray()
    base = 32768 * 4
    for (cx, cy, cz), cells in sorted(cubes_cells.items()):
        root = {"Status": (twr.TAG_STRING, "minecraft:full"),
                "block_states": (twr.TAG_COMPOUND,
                                 _pack_block_states(cells))}
        comp = gzip.compress(twr.nbt_write(root))
        idx = ((cy & 31) * 1024) + ((cz & 31) * 32) + (cx & 31)
        struct.pack_into(">i", header, idx * 4, base + len(body))
        body += struct.pack(">i", len(comp)) + comp
    with open(path, "wb") as f:
        f.write(bytes(header) + bytes(body))
    return len(header) + len(body)


def cubetest(out_dir):
    """Synthetic TerraCubes world: known cubes in two regions
    (incl. negative coords), written in the mod's .cc3 format
    and read back. Proves the cube decoder before any real
    Terra world file is trusted."""
    os.makedirs(out_dir, exist_ok=True)
    A = {}
    for lx in range(16):
        for lz in range(16):
            A[(lx, 0, lz)] = "minecraft:stone"
    A[(3, 1, 3)] = "minecraft:bricks"
    A[(3, 2, 3)] = "minecraft:bricks"
    C = {(8, 0, 8): "minecraft:oak_log"}
    B = {(0, 0, 0): "minecraft:water",
         (15, 15, 15): "minecraft:bricks"}
    write_cc3({(0, 4, 0): A, (0, 5, 0): C},
              os.path.join(out_dir, "r.0.0.0.cc3"))
    write_cc3({(-1, 4, -1): B},
              os.path.join(out_dir, "r.-1.0.-1.cc3"))
    got = load_voxels(out_dir, box=(-32, -32, 47, 47))
    want = {}
    for (lx, y, lz), nm in A.items():
        want[(lx, 64 + y, lz)] = nm
    for (lx, y, lz), nm in C.items():
        want[(lx, 80 + y, lz)] = nm
    for (lx, y, lz), nm in B.items():
        want[(-16 + lx, 64 + y, -16 + lz)] = nm
    diff = [k for k in set(want) | set(got)
            if want.get(k) != got.get(k)]
    check("cubetest", "PASS" if not diff else "FAIL",
          f"synthetic .cc3 world: {len(got)} blocks read back, "
          f"{len(diff)} differences (want 0); two regions, "
          f"negative cube coords included")

# ══════════════════════════════════════════════════════════════
# RENDERER — flat-colour isometric (2:1), painter's algorithm.
# Every block's three camera faces are drawn; nearer blocks
# (drawn later) paint over farther ones.
# ══════════════════════════════════════════════════════════════
def shade(rgb, f):
    return tuple(max(0, min(255, int(c * f))) for c in rgb)


def scene_frame(vox):
    xs = [p[0] for p in vox]; ys = [p[1] for p in vox]; zs = [p[2] for p in vox]
    return (min(xs), min(ys), min(zs)), (max(xs), max(ys), max(zs))


ROT = {  # (rx, rz) from local (lx, lz); dims swap on odd turns
    0: lambda lx, lz, W, D: (lx, lz),
    1: lambda lx, lz, W, D: (lz, W - 1 - lx),
    2: lambda lx, lz, W, D: (W - 1 - lx, D - 1 - lz),
    3: lambda lx, lz, W, D: (D - 1 - lz, lx),
}
VIEW_NAME = {0: "NW", 1: "NE", 2: "SE", 3: "SW"}  # camera corner


def render_iso(vox, rot, path, s=None):
    (x0, y0, z0), (x1, y1, z1) = scene_frame(vox)
    W, D = x1 - x0 + 1, z1 - z0 + 1
    if s is None:
        s = max(3, min(12, 1000 // max(1, W + D)))
    h = s / 2.0
    rvox = {}
    for (x, y, z), nm in vox.items():
        rx, rz = ROT[rot](x - x0, z - z0, W, D)
        rvox[(rx, y - y0, rz)] = nm
    # canvas size from the 8 projected corners
    pts = []
    for rx in (0, (D if rot % 2 else W) - 1):
        for rz in (0, (W if rot % 2 else D) - 1):
            for yy in (0, y1 - y0):
                pts.append(((rx - rz) * s, (rx + rz) * h - yy * s))
    minx = min(p[0] for p in pts) - s - 4
    maxx = max(p[0] for p in pts) + s + 4
    miny = min(p[1] for p in pts) - h - 4
    maxy = max(p[1] for p in pts) + h + s + 4
    img = Image.new("RGB", (int(maxx - minx), int(maxy - miny)), (16, 18, 24))
    dr = ImageDraw.Draw(img)

    def proj(rx, yy, rz):
        return ((rx - rz) * s - minx, (rx + rz) * h - yy * s - miny)

    order = sorted(rvox.items(), key=lambda kv: (kv[0][0] + kv[0][2], kv[0][1]))
    # Big scenes (real terrain) can only be drawn face-culled —
    # millions of hidden faces would take minutes and gigabytes.
    # Small scenes draw every face (nearer paint over farther),
    # which also avoids dark sawteeth at wall feet.
    cull = len(rvox) > 400_000
    for (rx, yy, rz), nm in order:
        base = colour_for(nm)
        sx, sy = proj(rx, yy, rz)
        if not cull or (rx, yy, rz - 1) not in rvox:
            c = shade(base, 0.55)
            dr.polygon([(sx, sy + h), (sx + s, sy), (sx + s, sy + s),
                        (sx, sy + h + s)], fill=c, outline=c, width=2)
        if not cull or (rx - 1, yy, rz) not in rvox:
            c = shade(base, 0.75)
            dr.polygon([(sx - s, sy), (sx, sy + h), (sx, sy + h + s),
                        (sx - s, sy + s)], fill=c, outline=c, width=2)
        if not cull or (rx, yy + 1, rz) not in rvox:
            c = shade(base, 1.0)
            dr.polygon([(sx, sy - h), (sx + s, sy), (sx, sy + h),
                        (sx - s, sy)], fill=c, outline=c, width=2)
    img.save(path)
    return img.size, s


ENT_COLOR = {"player": (255, 255, 0), "villager": (0, 255, 255),
             "monster": (255, 60, 60), "animal": (120, 255, 120),
             "object": (255, 255, 255)}


def ent_category(eid):
    if eid == "minecraft:player":
        return "player"
    if any(k in eid for k in ("armor_stand", "painting", "item_frame",
                              "item", "falling_block", "experience_orb",
                              "firework", "arrow", "trident", "snowball",
                              "egg", "potion", "boat", "minecart",
                              "tnt", "crystal", "area_effect", "marker",
                              "interaction", "display", "leash")):
        return "object"
    if "villager" in eid or eid.endswith("_npc") or "npc" in eid:
        return "villager"
    if any(k in eid for k in ("zombie", "skeleton", "creeper",
                              "spider", "witch", "pillager", "phantom",
                              "drowned", "husk", "stray", "blaze",
                              "ghast", "slime", "magma", "enderman",
                              "warden", "ravager", "vex", "evoker",
                              "vindicator", "shulker", "guardian",
                              "hoglin", "zoglin", "piglin", "wither")):
        return "monster"
    return "animal"


def render_top(vox, path, px=8, ents=None):
    (x0, y0, z0), (x1, y1, z1) = scene_frame(vox)
    W, D = x1 - x0 + 1, z1 - z0 + 1
    top = {}
    for (x, y, z), nm in vox.items():
        k = (x, z)
        if k not in top or y > top[k][0]:
            top[k] = (y, nm)
    img = Image.new("RGB", (W * px, D * px), (16, 18, 24))
    dr = ImageDraw.Draw(img)
    for (x, z), (y, nm) in top.items():
        f = 0.65 + 0.35 * (y - y0) / max(1, y1 - y0)
        c = shade(colour_for(nm), f)
        X, Z = (x - x0) * px, (z - z0) * px
        dr.rectangle([X, Z, X + px - 1, Z + px - 1], fill=c)
    if ents:  # living things as dots (only those inside the picture)
        r = max(2, px // 2)
        for e in ents:
            ex, ez = e["pos"][0], e["pos"][2]
            if x0 <= ex <= x1 + 1 and z0 <= ez <= z1 + 1:
                cxp = int((ex - x0) * px)
                czp = int((ez - z0) * px)
                c = ENT_COLOR[ent_category(e["id"])]
                dr.ellipse([cxp - r, czp - r, cxp + r, czp + r],
                           fill=c, outline=(0, 0, 0))
    img.save(path)
    return img.size


# ══════════════════════════════════════════════════════════════
# FLOOR SLICES — one plan per built level: full colour = a
# block AT this height (walls), faint = the block below (floor)
# ══════════════════════════════════════════════════════════════
def render_slices(vox, out_dir, max_slices=32):
    (x0, y0, z0), (x1, y1, z1) = scene_frame(vox)
    W, D = x1 - x0 + 1, z1 - z0 + 1
    px = max(2, min(8, 1200 // max(1, max(W, D))))
    levels = sorted({y for (x, y, z), n in vox.items()
                     if n not in NATURAL})
    if len(levels) > max_slices:
        step = len(levels) / float(max_slices)
        picked = sorted({levels[int(i * step)]
                         for i in range(max_slices)})
        check("slices", "WARN", f"{len(levels)} built levels; "
              f"drawing {len(picked)} of them")
        levels = picked
    for y in levels:
        img = Image.new("RGB", (W * px, D * px), (16, 18, 24))
        dr = ImageDraw.Draw(img)
        for (x, yy, z), nm in vox.items():
            X, Z = (x - x0) * px, (z - z0) * px
            if yy == y - 1:
                dr.rectangle([X, Z, X + px - 1, Z + px - 1],
                             fill=shade(colour_for(nm), 0.4))
        for (x, yy, z), nm in vox.items():
            if yy == y:
                X, Z = (x - x0) * px, (z - z0) * px
                dr.rectangle([X, Z, X + px - 1, Z + px - 1],
                             fill=colour_for(nm))
        img.save(os.path.join(out_dir, f"slice_y{y}.png"))
    check("slices", "PASS", f"{len(levels)} floor slices written "
          f"(levels {levels[:3]}...{levels[-3:] if levels else []})")
    return levels


# ══════════════════════════════════════════════════════════════
# STREET PROFILE — surface height along a straight line
# ══════════════════════════════════════════════════════════════
def render_profile(vox, x0, z0, x1, z1, path):
    n = max(abs(x1 - x0), abs(z1 - z0)) + 1
    top = {}
    for (x, y, z), nm in vox.items():
        k = (x, z)
        if k not in top or y > top[k][0]:
            top[k] = (y, nm)
    pts = []
    for i in range(n):
        t = i / max(1, n - 1)
        x = round(x0 + (x1 - x0) * t)
        z = round(z0 + (z1 - z0) * t)
        pts.append((x, z) + top.get((x, z), (None, None)))
    ys = [p[2] for p in pts if p[2] is not None]
    if not ys:
        check("profile", "FAIL", "no ground along the line")
        return pts
    ymin, ymax = min(ys) - 2, max(ys) + 2
    pxw, pxh = 4, 4
    img = Image.new("RGB", (n * pxw, (ymax - ymin + 1) * pxh),
                    (16, 18, 24))
    dr = ImageDraw.Draw(img)
    for i, (x, z, y, nm) in enumerate(pts):
        if y is None:
            continue
        c = colour_for(nm)
        X = i * pxw
        ytop = (ymax - y) * pxh
        dr.rectangle([X, ytop, X + pxw - 1, img.height - 1],
                     fill=shade(c, 0.55))
        dr.rectangle([X, ytop, X + pxw - 1, ytop + pxh - 1], fill=c)
    img.save(path)
    runs = []
    for p in pts:
        if runs and runs[-1][0] == p[2]:
            runs[-1][1] += 1
        else:
            runs.append([p[2], 1])
    desc = ", ".join(f"y={r[0]} x{r[1]}" if r[0] is not None
                     else f"gap x{r[1]}" for r in runs)
    check("profile", "PASS", f"({x0},{z0})->({x1},{z1}): {desc}")
    return pts


# ══════════════════════════════════════════════════════════════
# ENTITIES — living things from entities/*.mca region files
# ══════════════════════════════════════════════════════════════
def load_entities(world):
    if os.path.isdir(world):
        sub = os.path.join(world, "entities")
        scan = sub if os.path.isdir(sub) else world
        files = [os.path.join(scan, f)
                 for f in sorted(os.listdir(scan))
                 if f.endswith(".mca")]
    else:
        files = [world]
    ents = []
    for p in files:
        try:
            chunks = list(twr.read_region(p))
        except Exception:
            continue
        for cx, cz, nbtc in chunks:
            for e in nbtc.get("Entities") or []:
                pos = e.get("Pos")
                if not pos or len(pos) < 3:
                    continue
                name = e.get("CustomName")
                if isinstance(name, str) and name.startswith("{"):
                    try:
                        name = json.loads(name).get("text", name)
                    except Exception:
                        pass
                ents.append({"id": e.get("id", "?"),
                             "pos": [float(pos[0]), float(pos[1]),
                                     float(pos[2])],
                             "name": name})
    return ents


def write_entities(ents, out_dir):
    from collections import Counter
    counts = Counter(e["id"] for e in ents)
    with open(os.path.join(out_dir, "entities.txt"), "w") as f:
        for e in sorted(ents, key=lambda e: (e["id"], e["pos"])):
            x, y, z = e["pos"]
            f.write(f"{e['id']:<36} {x:>9.1f} {y:>6.1f} "
                    f"{z:>9.1f}  {e.get('name') or ''}\n")
    top = ", ".join(f"{k}x{v}" for k, v in counts.most_common(5))
    check("entities", "PASS" if ents else "WARN",
          f"{len(ents)} entities ({top}) -> entities.txt")

# ══════════════════════════════════════════════════════════════
# THE DEMO TOWN — every number below is ground truth the
# checks then verify.
#   plot 48x48 (x,z in 0..47); grass surface y=63
#   road cross: black concrete at y=63, x in 21..25 / z in 21..25
#   house: x 6..16, z 6..14, walls y64..67, door on SOUTH wall
#          (z=14) at x=11; gabled roof, ridge y=72 at z=10
#   tower: x 36..40, z 6..10, stone bricks y64..83 (20 tall)
#   pond:  x 28..38, z 30..40, water surface y=62
#   tree:  at (30,18) trunk y64..68, leaves y69..71
# ══════════════════════════════════════════════════════════════
def build_demo_scene():
    v = {}
    def put(x, y, z, nm):
        v[(x, y, z)] = nm
    for x in range(48):
        for z in range(48):
            put(x, 63, z, "minecraft:grass_block")
    for x in range(48):
        for z in range(21, 26):
            put(x, 63, z, "minecraft:black_concrete")
    for z in range(48):
        for x in range(21, 26):
            put(x, 63, z, "minecraft:black_concrete")
    # pond (overwrites grass/road corner if overlapping — it does not)
    for x in range(28, 39):
        for z in range(30, 41):
            put(x, 60, z, "minecraft:dirt")
            del v[(x, 63, z)]
            put(x, 61, z, "minecraft:water")
            put(x, 62, z, "minecraft:water")
    # house walls (oak planks), door gap south (z=14) x=11, windows y=66
    for x in range(6, 17):
        for z in range(6, 15):
            if x in (6, 16) or z in (6, 14):
                for y in range(64, 68):
                    if z == 14 and x == 11 and y in (64, 65):
                        continue  # door
                    if y == 66 and ((z in (6, 14) and x in (8, 14))
                                    or (x in (6, 16) and z == 10)):
                        continue  # windows
                    put(x, y, z, "minecraft:oak_planks")
    for x in (6, 16):  # corner posts
        for z in (6, 14):
            for y in range(64, 68):
                put(x, y, z, "minecraft:oak_log")
    # gabled roof (bricks): ridge along x at z=10, y=72; 1-block overhang
    for x in range(5, 18):
        for z in range(5, 16):
            y = 72 - abs(z - 10)
            if y >= 68:
                put(x, y, z, "minecraft:bricks")
    for x in (6, 16):  # gable ends (planks triangle under roof line)
        for z in range(7, 14):
            for y in range(68, 72 - abs(z - 10)):
                put(x, y, z, "minecraft:oak_planks")
    # tower: 5x5 stone-brick shell, y64..83, slab top, door south x=38
    for x in range(36, 41):
        for z in range(6, 11):
            for y in range(64, 84):
                shell = x in (36, 40) or z in (6, 10)
                if y == 83:
                    put(x, y, z, "minecraft:stone_bricks")  # roof slab
                elif shell:
                    if z == 10 and x == 38 and y in (64, 65):
                        continue  # door
                    if y == 75 and ((z in (6, 10) and x == 38)
                                    or (x in (36, 40) and z == 8)):
                        continue  # window slits
                    put(x, y, z, "minecraft:stone_bricks")
    # tree at (30,18): trunk y64..68, leaves 69..71
    for y in range(64, 69):
        put(30, y, 18, "minecraft:oak_log")
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            for y in (69, 70):
                if (dx, dz) != (0, 0):
                    put(30 + dx, y, 18 + dz, "minecraft:oak_leaves")
    put(30, 71, 18, "minecraft:oak_leaves")
    return v


def demo(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    scene = build_demo_scene()
    check("demo-scene", "PASS", f"{len(scene)} non-air blocks built in code")
    # ground-truth facts of the scene (numbers first, rendering second)
    tower_top = max(y for (x, y, z) in scene if 36 <= x <= 40 and 6 <= z <= 10)
    ridge_top = max(y for (x, y, z) in scene if 5 <= x <= 17 and 5 <= z <= 15)
    water_top = max(y for (x, y, z), n in scene.items() if n == "minecraft:water")
    check("demo-truth", "PASS" if (tower_top == 83 and ridge_top == 72
                                   and water_top == 62) else "FAIL",
          f"tower top y={tower_top} (want 83 = 20 above ground 63), "
          f"roof ridge y={ridge_top} (want 72), water surface y={water_top} "
          f"(want 62)")

    mca = os.path.join(out_dir, "demo_r.0.0.mca")
    nbytes = write_region(scene, mca)
    check("demo-file", "PASS", f"packed into a real .mca region file "
          f"({nbytes} bytes)")

    back = region_voxels(mca)
    diff = [k for k in set(scene) | set(back)
            if scene.get(k) != back.get(k)]
    check("roundtrip", "PASS" if not diff else "FAIL",
          f"file read back: {len(back)} blocks, {len(diff)} differences "
          f"from the town that was built (want 0)")

    size = render_top(back, os.path.join(out_dir, "view_top.png"))
    check("render", "PASS", f"view_top.png {size[0]}x{size[1]}")
    for rot in range(4):
        p = os.path.join(out_dir, f"view_from_{VIEW_NAME[rot]}.png")
        size, s = render_iso(back, rot, p)
        check("render", "PASS", f"view_from_{VIEW_NAME[rot]}.png "
              f"{size[0]}x{size[1]} (block = {s}px)")
    write_legend(back, out_dir)

    # floor slices + the interior truth checks
    levels = render_slices(back, out_dir)
    slices_ok = (
        back.get((11, 64, 14)) is None            # door gap, y64
        and back.get((10, 64, 14)) == "minecraft:oak_planks"
        and back.get((11, 66, 14)) == "minecraft:oak_planks"
        and back.get((8, 66, 14)) is None         # window gap, y66
        and back.get((10, 72, 10)) == "minecraft:bricks"  # ridge
        and 64 in levels and 72 in levels)
    check("demo-slices", "PASS" if slices_ok else "FAIL",
          "slice data: door gap at y64, wall above door at y66, "
          "window gap at y66, roof ridge at y72")

    # street profile along z=35, west to east: flat grass/road
    # y=63, dipping to water y=62 across the pond (x28..38)
    pts = render_profile(back, 0, 35, 47, 35,
                         os.path.join(out_dir, "profile_road.png"))
    prof_ok = all(
        (y == 63 if (x <= 27 or x >= 39) else y == 62)
        for (x, z, y, nm) in pts if y is not None)
    check("demo-profile", "PASS" if prof_ok else "FAIL",
          "profile z=35: y=63 flat, y=62 across the pond (x28..38)")

# ══════════════════════════════════════════════════════════════
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("world", nargs="?")
    ap.add_argument("out", nargs="?", default="3d_report")
    ap.add_argument("--demo", action="store_true")
    ap.add_argument("--cubetest", action="store_true")
    ap.add_argument("--box", nargs=4, type=int,
                    metavar=("X0", "Z0", "X1", "Z1"))
    ap.add_argument("--slices", action="store_true",
                    help="also draw one floor plan per built level")
    ap.add_argument("--profile", nargs=4, type=int,
                    metavar=("X0", "Z0", "X1", "Z1"),
                    help="also draw the surface profile along a line")
    ap.add_argument("--entities",
                    help="entities folder (or world folder); "
                         "draws living things on the top view")
    ap.add_argument("--depth", type=int, default=64,
                    help="keep this many layers below each "
                         "column's top (default 64; raise for "
                         "tall buildings)")
    a = ap.parse_args()
    if (a.demo or a.cubetest) and a.world \
            and not a.world.endswith(".mca"):
        a.out = a.world  # convenience: `--demo DIR`, `--cubetest DIR`
    os.makedirs(a.out, exist_ok=True)

    if a.cubetest:
        cubetest(a.out)
    elif a.demo:
        demo(a.out)
    elif a.world:
        vox = load_voxels(a.world,
                          box=tuple(a.box) if a.box else None,
                          depth=a.depth)
        if not vox:
            check("scene", "FAIL", "no non-air blocks decoded")
        else:
            (ax, ay, az), (bx, by, bz) = scene_frame(vox)
            W, D = bx - ax + 1, bz - az + 1
            check("scene", "PASS", f"{len(vox)} blocks, "
                  f"x {ax}..{bx}, y {ay}..{by}, z {az}..{bz}")
            if W + D > 400:
                check("scene", "FAIL", f"scene too wide (x+z={W + D} > 400); "
                      f"re-run with --box to crop")
            else:
                ents = None
                if a.entities:
                    ents = load_entities(a.entities)
                    write_entities(ents, a.out)
                render_top(vox, os.path.join(a.out, "view_top.png"),
                           ents=ents)
                for rot in range(4):
                    render_iso(vox, rot,
                               os.path.join(a.out,
                                            f"view_from_{VIEW_NAME[rot]}.png"))
                check("render", "PASS", "5 views written")
                write_legend(vox, a.out)
                if a.slices:
                    render_slices(vox, a.out)
                if a.profile:
                    render_profile(vox, *a.profile,
                                   os.path.join(a.out, "profile.png"))
    else:
        ap.print_help()
        return 2

    with open(os.path.join(a.out, "report_3d.txt"), "w") as f:
        f.write("\n".join(LOG) + "\n")
    print(f"\n3D view: {SCORE['PASS']} pass, {SCORE['WARN']} warn, "
          f"{SCORE['FAIL']} fail")
    return 1 if SCORE["FAIL"] else 0


if __name__ == "__main__":
    sys.exit(main())
