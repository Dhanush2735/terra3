# INLAND FAKE SEA — root cause + fix (2026-11-19)

## Symptom (owner screenshots, Gandikota 14.8250, 78.2802)
In-game: a flat teal "Warm Ocean" sea at Y63 inside the gorge
(~250 m up), coral-decorated sand/gravel floor, checkerboard
sand/stone cliffs, biome reads Warm Ocean at Y318. Owner:
"completely different" from the smooth Mapterhorn preview.

Screenshot coords (X 10196758, Z -1756808) fall inside NW(18)
region cluster r.19914..16 / r.-3431..33 — so NW(18) cluster 1
WAS Gandikota, and the scan's "healthy coastal coral sea"
(48,640 columns with water top Y63; floors sand/gravel/all 5
corals) was this fake sea. Earlier report corrected: those
numbers were the bug, not a success.

## Root cause (proven in workspace, code + data)
classify() has three doors to OCEAN: OSM coastline, ESA water
witness, elevation fallback. The ESA door (STEP 1C) fired on
the Penna river:

1. ESA WorldCover class at the exact point = **80 "Permanent
   water bodies"** (mod's own S3 tile N12E078, windowed read).
   River footprint up to **1,530 m wide** in a row; 8.3% of a
   3.3 km window is class 80. Plateau 450 m north = class 20.
2. STEP 1C: `ew.isWater(...)` → `classifyAsOcean(elev, ...)`
   with NO elevation condition.
3. classifyAsOcean: `oe = elev >= -0.5 ? -5.0 : elev` → floor
   Y58, water to SL Y63, biome from temperature (warm ocean).
   At elev = 191 m the gorge was flattened into a sea-level
   coral sea. The height book is never consulted for the
   verdict — **Terrarium vs Mapterhorn cannot change this.**

Secondary findings (same window, `test_gandikota_heights.py`):
- Terrarium z15 raw: 412 px < -1 m, 399 px < -100 m, worst
  **-3800 m** → can flip the elevation fallback + jagged
  heights where the guard path misses. Mapterhorn: **0** such
  pixels (min 190.5 m).
- Steep-rock trigger (5x5 range >= 6 m): Terrarium 23.1%,
  Mapterhorn 21.8% — the gorge is genuinely steep in both
  books, so the checkerboard cliffs are the Step-5 steep
  painter's patch alternation (stone/sandstone), NOT mainly
  a book problem. Visual refinement, separate task.

## Fix (TerraDensityFunctions.classify, STEP 1C)
ESA water becomes sea only when `elev <= ESA_SEA_MAX_ELEV`
(= 5.0 m). Higher → falls through to the elevation check →
LAND, and the river engine paints the real river at its true
height (already proven in the same save: water at Y259-265).
Real coasts unaffected: mapped-sea pixels read ~0 m in both
books, and the OSM coastline door still fires first there.
Proof log line (first 15): `ESA WATER INLAND: lat=... lon=...
elev=...m -> not sea (river/lake)`.

Parse-checked (JDK 17, Forge noise only). Old chunks keep
their fake sea — retest in FRESH chunks at Gandikota.

## OWNER VERDICT (2026-11-19, same day): "its working no
## fake sea" — FIX PROVEN IN-GAME at Gandikota.
New open item from the same test: "the spawn chunk is
elevated ~10 high" — a ~10-block step at spawn, cause not
yet identified (tile-source seam? cold-start fallback?
coast gate edge?). Awaiting owner's place/screenshot/log.

## NW(20) MEASUREMENT (2026-11-20): the raised chunk
Owner teleported to 14.82331, 78.28100 and sent the world.
Scanned all 1,489 chunks (scripts /home/user/nw20/). Exactly
ONE true raised chunk: 637299,-109801 — the player's own
landing chunk. Borders vs neighbours: W +6.8, E +6.1,
N +8.9, S +4.8; interior a tight +7.3 vs the neighbours'
fitted surface → a uniform lid, shape preserved. It is also
painted differently: 100% terracotta family on top vs
grass/coarse-dirt on both neighbours. LastUpdate 1189 vs
2462 (west neighbour) → written much earlier than its
surroundings. (Two other +8/+6 flags were jungle-canopy
false alarms.) Verdict: this chunk was generated under a
different data state than its neighbours — an earlier
visit/build fossil or a cold-start first-seconds chunk —
NOT the fake-sea fix and NOT a smoothing fault. Books at
the point differ by only ~+5 m (Terrarium higher), right
order for the +7 lid. Remedy offered: delete that single
chunk from the save (with backup) and let the game rebuild
it. 3D renders: /home/user/nw20_views/ (terra_3d_view.py;
the 192-block box silently fails in render_iso, 128 works).
Still open: is NW(20) new or continued? ELEV SRC lines?

## WHY IT ELEVATED (answered 2026-11-20, workspace tests)
The chunk is a fossil from an earlier build/session:
1. PAINT PROOF: current mesa painter (TerraBiomeProvider
   classifyBare, code 2) requires ESA BARE + precip<500 +
   **elev 500..2600 m**. The lid sits at ~269 m — the
   current build cannot paint terracotta there at all.
   An older rule-set did.
2. HEIGHT: Terrarium z15 reads +4.6 m above Mapterhorn
   z15 at the point (269.4 vs 264.8) — old-book chunk
   among new-book chunks ≈ the +7 lid. Parent-fallback
   theory TESTED AND DEAD: Terrarium z14 = +0.4 m only.
3. TIME: LastUpdate 1189 vs neighbours 2462.
Old chunks never regenerate → one old red chunk stands
+7 among new grass chunks. Remedy: delete the chunk
(save kept at /home/user/nw20/save, backup first) and let
the game rebuild it. Awaiting owner consent + whether
NW(20) was a continued world (would confirm the story).

## CHUNK REMOVED (2026-11-20, owner: "did you solve that
## one chunk problem?")
Zeroed the header entry of chunk 637299,-109801 (local
19,23) in region/r.19915.-3432.mca of the owner's NW(20)
copy; entities entry was already empty. Backup:
nw20/backup/r.19915.-3432.mca.orig. Verified: chunk reads
None, all 4 neighbours intact (254 sections each).
Deliverable: /home/user/newworld20_fixed.zip (5.4 MB,
unzip -t OK). The game will regenerate the chunk fresh
on next visit — expect new-book heights + shrubland
paint matching the neighbours. If it STILL comes out
raised/red, the current build itself is implicated and
the log (ELEV SRC / ESA / classify lines) becomes
mandatory. Awaiting owner's in-game verdict.

## Still open
- Whether the owner's NW(18) build had Mapterhorn active at
  all (needs `ELEV SRC` lines from latest.log).
- Checkerboard steep-painter refinement (strata banding
  instead of hash patches) — owner decision.
- Below-sea lakes (e.g. Dead Sea) still take the ocean door —
  pre-existing, unchanged by this fix.
