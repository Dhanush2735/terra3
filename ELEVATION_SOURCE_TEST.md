# Elevation source test: Terrarium (AWS) vs Mapterhorn (2026-11-19)

Workspace test requested by owner. Downloaded real tiles for
Gandikota (14.8233,78.2810), Grand Canyon (36.0990,-112.1125),
Sahara (23.4,12.6), Mumbai-Juhu (19.0968,72.8265) and compared.
Scripts + raw results: /home/user/downloads/elevtest/
(compare_elev.py, results.json; method: Terrarium decode
h = R*256+G+B/256-32768 for both — Mapterhorn is Terrarium-encoded;
windows ~1-2.5 km around each point; "noise" via 3x3-median
deviation, spikes = |h - med3| > 25 m.)

Terrarium = what the mod uses (DEFAULT_TILE_URL, zoom 15).
Mapterhorn best available per place (planet.pmtiles z12
Copernicus GLO-30; regional archives 6-x-y.pmtiles for hi-res).

## Availability (live-probed)
- Grand Canyon: regional archive exists, maxzoom 17, z15 tile
  FOUND (133 KB WebP). US high-res data real.
- Gandikota / Sahara / Juhu: regional archives 404 -> planet
  z12 only (18-19 m/px sampling of a 30 m DEM), exactly the
  documented coverage model.

## Numbers (point / window min-max / spikes)
Gandikota:
- Terr z15 point 271.4 m; tile min -1060.4 (!), 73 px < -100 m,
  33 px < -500 m, 7 px < -1000 m: deep-hole monster pixels in
  the raw tile near the gorge (the HeightSpikeGuard's prey;
  in-game these are cleaned in memory, proven v3c).
- Terr z12 min 186 / max 335. Mapt z12 point 269.5, min 190.5,
  max 331.5, 0 spikes. Points agree within 2 m.

Grand Canyon (same z15 tile both, head-to-head):
- Point: Terr 753.9 m vs Mapt 753.8 m (river level, identical).
- Window min/max: 730/1175 vs 731/1175. RMS difference between
  sources on the same tile = 4.19 m; 10.2% px differ >5 m;
  0.30% >25 m. Fine detail (mean |gradient|): 4.55 vs
  4.47 m/px — a tie; both carry the same detail class here.
- Terr z12 (coarse control): 40 spikes >25 m — coarse zooms
  are the dirty ones (matches zoom-anchor guard design).

Sahara:
- Point: Terr 804.3/803.0 vs Mapt 789.5 (14 m apart = dune
  crest sampling). Window relief: Terr 89 m vs Mapt 21 m;
  std 12.7 vs 3.6. Terrarium shows dune relief; Copernicus
  (TanDEM-X) irons sand seas (known low-coherence smoothing).
  For game feel Terrarium's dunes are arguably more real here;
  Mapterhorn is cleaner but flatter. No spikes either at z15.

Mumbai-Juhu (shore-relevant):
- Point: Terr 4.0 m vs Mapt 1.5 m.
- Sea pixels: Terr mean -0.11 m, std 0.36, range -2.0..+0.5
  (noisy zero — the elevation-fallback poison). Mapt mean
  0.00, std 0.04, min 0.0 (flat zero sea — land DEM clipped).
- h>0 mask sign-changes/row: 1.00 vs 1.02 (equal; in-game
  jaggedness comes from classification mixing, not the mask).
- Terr z12 window min -7.0 (bathymetry present); Mapt ocean
  is 0.0 everywhere = NO bathymetry (deep-sea zones would
  flatten; our OceanZone variety needs Terrarium depths).

## Verdict
- Land data quality where Mapterhorn has high-res (Canyon,
  Europe): equal detail, cleaner provenance — latest wins.
- Everywhere else tested (India, Sahara): Mapterhorn is
  cleaner (no holes/spikes, flat sea) but only z12 and
  30 m-class, no ocean depths; Terrarium is coarser-edited
  but z15 everywhere, has bathymetry, and its worst defects
  are already cleaned in-game by the proven guard.
- Recommendation unchanged: Terrarium stays the base;
  Mapterhorn as a first-choice overlay where it has real
  high-res tiles (hybrid) is the only switch worth making.
  NOT approved by owner yet; no code changed by this test.

## SWITCH IMPLEMENTED (2026-11-19, owner: "once switch to
## Mapterhorn")

Owner approved the switch after the test. Implemented:
- New `generator/MapterhornTiles.java`: minimal PMTiles v3
  range-request client (header/root/leaf dirs, Hilbert
  tile ids), WebP decode via TwelveMonkeys, renders any
  terrain-zoom tile at 256x256 Terrarium encoding —
  regional z13+ archive when present, else the planet
  z12 parent cropped+upscaled ("planet-derived").
- `TerraDensityFunctions.getTile`: when zl == terrain
  zoom and config `mapterhornElevation` (default true,
  TerraConfig) is on, tiles come from
  downloadTileSourcesFirst: Mapterhorn -> Terrarium disk
  archive -> live Terrarium download. Coarser zooms
  (guard anchors, parent fallback, bathymetry chain)
  keep the classic path — ocean depths and the spike
  guard are untouched. Mapterhorn tiles get the same
  in-memory guard cleaning but are NEVER written into
  the raw-Terrarium disk archive (owner directive).
- build.gradle: TwelveMonkeys imageio-webp + core/
  metadata/common modules 3.15.3 as minecraftLibrary +
  jarJar pins (version verified on Maven Central).
  MapterhornTiles also registers the WebP reader
  explicitly (jar-in-jar SPI scan can miss providers);
  if the plugin is absent, decode fails soft to Terrarium.
- Log proof line: `ELEV SRC 15/x/y: mapterhorn
  (regional z15 | planet-derived z15)` (first 10).

Sandbox proof (full javac compile + live run, JDK 17 +
the real TwelveMonkeys jars): all 14 PMTiles tile-id
test vectors match the reference implementation;
Grand Canyon regional z15 point = 754.6 m (reference
753.8); Gandikota planet-derived = 267.5 m (ref 269.5);
Juhu point 1.2 m, offshore pixel flat 0.00 m. First
fetch per archive ~3 s (header+dir+tile), then 1 range
GET per tile — same cold-fetch class as Terrarium.

Watch-outs for the trial: (1) new chunks near old ones
can seam by a few metres where the two books disagree
(RMS 4.2 m at the Canyon) — judge the switch in a FRESH
world/area. (2) Revert any time: config
mapterhornElevation=false (pure Terrarium, zero code).
(3) If ELEV SRC lines never appear, WebP SPI failed —
report the log; fallback means the world still works.
