# CAVES — Phase 2: real caves at true locations (2026-11-20)

Implements the owner's two-type rule on top of the world cave map
(`cave_map/caves_world.csv` → packed `caves.bin.gz`, 143,672
points; see `cave_map/CAVES_MAP.md` for sources and status).

## What was built

- **`CaveData.java`** — loads `caves.bin.gz` (2.6 MB) into arrays +
  a 0.05° cell index; `inBox(lat/lon)` queries. Fail-soft: missing
  file ⇒ no real caves, Phase 1 unaffected. Verified in a stub
  harness: Mammoth Cave System found (depth 124 m, length
  643.7 km), Arabika box has 2 caves > 2,000 m, France 1°×1° box
  returns 2,892 points, empty ocean returns 0.
- **`TerraChunkGenerator.carveRealCaves`** — called from the same
  carving stage as Phase 1, in ANY geology (volcanic caves, mines
  and bunkers are not karst-bound). For each chunk it takes the
  points within ~6 km, converts entrance lat/lon to blocks with
  the projection's `fromGeo`, and simulates each cave from its
  true entrance with a position-seeded worm — identical math in
  every chunk, so systems cross chunk borders. Per chunk at most
  24 caves are simulated (entrances inside the chunk first).
- **Type 2 (described caves)** — the worm dives steering to the
  cave's REAL depth (pitch servo toward `surface − depth`,
  world-floor clamped), then wanders; passage budget follows the
  REAL length (steps = length/1.4). Entrance: a 2×2 shaft from
  the true surface point down to the system (refused if the
  entrance column is water). Chambers every ~110–230 steps.
- **Dots (no details)** — same machine with hash-chosen modest
  depth (25–110 m) and 90–320 steps. No invented facts.
- **Mine shaft** — 2×2 vertical shaft to real (or typical) depth
  plus two crossing galleries at the bottom. **Mine adit** —
  near-horizontal tunnel from the entrance point. **Bunker** —
  one 12×5×12 chamber 9 m down with a shaft.
- **Protections** — sensitive points (source-degraded coords)
  skipped; entrances on water-classified columns skipped (no
  draining lakes/rivers); Phase 1 ceiling guard reused (no
  surface perforation); kill switch is the same `realCaves`
  config. Log: `REALCAVE <name> kind= depth= len=
  entrancesBuilt=` (first 25, then every 250th).

## Honest caps & limits (deliberate)

- **Passage length cap 2,200 steps ≈ 3.5 km of tunnel**, whatever
  the real length. A 676 km system (Mammoth) cannot be simulated
  per-chunk; the player gets its true entrance, true depth and
  the first kilometres of a system in its character. Raising the
  cap costs generation time linearly in cave-dense regions.
- Type 2 caves match depth/length/entrance, **not the true
  passage plan** — that is Type 1 (blueprints), still to come:
  needs per-cave survey import (GrottoCenter documents / Karst3D
  / published plans); the `has_blueprint` flag exists in the
  data and no cave is flagged yet.
- Entrance shafts are placed from the estimated surface
  (classify elevation); on very steep ground a shaft may start
  slightly buried or floating by a block or two — the scan-down
  to first solid ground keeps this rare.
- Only fresh chunks get real caves; old chunks never change.
- OSM is still crawling (fixed query); when it finishes, rerun
  merge + pack to add its points (mostly Americas/Asia + more
  mines/bunkers). No code change needed.

## NW(21) SAVE EVIDENCE (2026-11-20, owner save — accepted proof)

Scanned `newworld21.zip` (2,968 chunks, one explored area,
player Y 273): **minecraft:lush_caves, dripstone_caves and
deep_dark each appear in 1,330 chunks** — in every previous
save (NW14–NW20) these biomes appeared in ZERO chunks. Chunks
carry up to ~5,200 underground air cells below the surface
(top chunk -595068,-338180); horizontal slices at surface−80 m
show discrete air pockets (tunnel/chamber cross-sections) in
solid rock (rendered: /home/user/nw21_views/). Depth-band
painting + carving are therefore both active in-game.
Attribution Phase 1 vs Phase 2 within the same chunk is not
separable from blocks alone (both expected at the test site);
no surface perforation observed in slices. Owner's visual
verdict still pending (shafts are 2×2 stubs ~3–7 deep before
the dive — by design).

## Owner test (when ready)

1. `gradle jarJar`, new world (or fresh area), config
   `realCaves=true` (default).
2. `/tpll 37.1869 -86.1034` (Mammoth Cave entrance, Kentucky):
   expect a 2×2 shaft at the surface going down into a system
   that dives toward ~124 m depth; log shows
   `REALCAVE Mammoth Cave System ...`.
3. `/tpll 43.4097 40.3622` (Krubera) — system should dive
   toward ~2,197 m (world floor clamp −1900s; expect very deep).
4. Control: dig anywhere remote from known caves (mid-Sahara):
   only Phase 1 rules apply there (no karst ⇒ solid rock).
5. Watch for: generation slowdown in cave-dense France
   (`/tpll 46.1 6.78`, Jean-Bernard area, 24-cave sim cap),
   surface holes (should be none), flooded entrances.
