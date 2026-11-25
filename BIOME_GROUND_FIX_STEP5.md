# Biome/Ground Fix — Step 5: clean palette (2026-11-19)

Status: IMPLEMENTED in source, parse-clean. Awaiting owner
proof in fresh chunks. (Steps 1+2 proven; Step 3 in code
awaiting proof; Step 4 name+shape implemented, name proven
in NW16, shape awaiting retest.)

## What was wrong (audit findings, measured earlier)
- Savanna AND shrubland ground = 100% COARSE_DIRT wall to
  wall (generator getSurfaceBlock cases 5/6 hardcoded).
- BARE hot (>=12 C) = RED_SAND everywhere -> Sahara red.
- Mesa/terracotta painter (code 2) unreachable from BARE:
  the BARE case returned before any mesa routing, so
  badlands-named country got flat sand/red sand.
- ERODED_BADLANDS never selected; WOODED_BADLANDS ~never;
  WINDSWEPT_SAVANNA not even registered.
- Wetland/mangrove ground (code 4) = plain GRASS.
- Sea floor = 100% GRAVEL top (NW14 census: sand 79 cols).
- Steep ground (gorge walls) wore flat-land palettes.

## Changes

### TerraBiomeProvider
- getBiomeTypeCode BARE split (uses queryData
  {soil, precip mm, temp C} + elevation):
  - arid (precip<500) + temp>=8 + elev 500..2600 m
    -> code 2 (mesa painter, terracotta bands)
  - hot + precip<400 + elev>=250 -> code 7 RED_SAND
    (arid plateau sands)
  - other hot -> code 1 SAND (Sahara low desert yellow)
  - cold -> code 1 (DF: sandstone above 1500 m)
- classifyBare highland: tierElev>=1200 + not
  semi-arid/sub-humid -> ERODED_BADLANDS; SEMI_ARID ->
  WOODED_BADLANDS; else BADLANDS (as before).
- WINDSWEPT_SAVANNA holder registered (Biomes.
  WINDSWEPT_SAVANNA); classifyShrubland: temp>=18 +
  tierElev>=1000 -> WINDSWEPT_SAVANNA. isSavanna()
  includes it (ground code 5).

### TerraChunkGenerator
- getSurfaceBlock(bt, el, rg, x, z) — now takes coords.
  Patch mixes via patchHash(x,z) = blockHash(x>>3,z>>3)
  (8-block patches, no per-block confetti):
  - code 5 savanna: 70% GRASS / 30% COARSE_DIRT
    (>=1500 m: 40/60; >=2500 m STONE)
  - code 6 shrubland: 38 GRASS / 34 COARSE_DIRT / 12 DIRT
    / 10 RED_SAND / 6 GRAVEL (>=2500 m STONE)
  - code 4 wetland: 70% MUD / 20 GRASS / 10 DIRT
    (>=1500 m COARSE_DIRT)
- Steep-rock rule: paint loop computes rk = 5x5 height
  range of the terraced ht grid; pLand(..., rk): if
  rk>=6 and bt in {0,1,4,5,6,7} and no shore override ->
  top+sub = STONE (SANDSTONE for bt 1, RED_SANDSTONE for
  bt 7). Shore rule keeps precedence; mesa/snow/beach/
  path untouched.
- pUW sea floor: dp<60 m: 50% SAND patches / GRAVEL;
  <250 m: 25% SAND; deeper: 20% DIRT / GRAVEL. Coral +
  seagrass logic (pUWD) untouched — proven NW14 behaviour
  preserved (floor stone stays 0; sand/gravel are safe
  under cleanArtifacts).

### TerraCheckCommand (bench kept honest)
- expectedGroundBlock -> expectedGroundSet: codes 4/5/6
  expect their palette sets; codes 0/1/4/5/6/7 also accept
  stone/sandstone/red_sandstone (rock override), so the
  bench VERDICT stays meaningful after the palette change.

## Proof places (fresh chunks only)
- Gandikota 14.82325, 78.28101: savanna name; ground no
  longer all coarse dirt — grass/coarse/dirt patches;
  gorge walls stone/rock. Target 5/10 -> 8-9/10.
- Grand Canyon 36.09901, -112.11254: badlands family
  names (eroded at rim heights), terracotta band ground
  in the canyon, river at bottom.
- Sahara (any low hot desert, e.g. 23.4, 12.6): yellow
  SAND, not red.
- Mumbai mangrove creeks / any wetland: mud tops.
- Any warm shallow sea: sand patches on the floor with
  corals still present, stone still 0.
- /terracheck here BIOME/GROUND VERDICT still PASS-ish
  (sets, not single blocks).

## NW17 save proof (2026-11-19, 5,828 chunks, 3 regions)

Owner visited 3 areas (no Mumbai this time): spawn region
x~10.196M (badlands/savanna — Grand Canyon trip), a far
region x~-11.87M, and a small inland region (water=0).
Measured from saved blocks (regions17.py / shore17.py):

- Mesa REACHED: badlands-family surface columns (255,824)
  = ~48% terracotta family (red 37,128 / orange 34,347 /
  light_gray 19,353 / plain 16,966 / brown 15,395) +
  red_sand 19% + grass 12% scrub. All pre-Step-5 worlds
  had ~0 terracotta tops.
- Savanna family (217,536 cols): grass_block 39% /
  coarse_dirt 25% (audit baseline: 100% coarse dirt) +
  dirt 6 / red_sand 5 / gravel 4 / stone 4 (rock rule
  visible) + dead_bush decoration tops.
- Desert tops: sand 39% / sandstone 27% (steep+high) —
  yellow, red_sand not dominant. Sahara direction fixed.
- Sea floor: sand 22,488 (NW14: 79) / gravel 25,829,
  corals ~1,200 x5 alive, STONE 0. Variety proven, the
  coral fix intact.
- Windswept hills tops 55% stone — rocky ground reads.
- NOT seen in visited areas: eroded_badlands,
  wooded_badlands, windswept_savanna, savanna_plateau,
  swamp/mangrove, beach. Owner asked "where did it go
  wrong?" — measured answer (hist17*.py, since deleted):
  C1 max surface Y 368 (~300 m, low savanna/desert);
  C2 badlands at ~737-1090 m; C3 a pure badlands plateau
  at ~737-830 m (190,720 cols). NO visited badlands
  column reached the eroded gate of 1200 m — the gate,
  not the pipeline, was wrong for canyon-bench country.
  FIXED: eroded gate 1200 -> 1000 m (parse-clean).
  Wooded needs SEMI_ARID rain class (C3 is drier — code
  path is direct, would have fired); windswept_savanna
  needs hot (>=18 C) shrubland at >=1000 m (none visited;
  high visited shrubland was cooler). Test places:
  eroded/wooded — Moab/Bryce Utah (1500-2400 m) or
  South Dakota Badlands 43.75,-102.50 (semi-arid ->
  wooded); windswept_savanna — Kenya highlands
  ~1.29S,36.82E. Beach/swamp simply not visited.
- Shore SHAPE (smoothShoreMask): NOT tested by this save
  — no sea coast sampled (C1's measured "edge" is the
  canyon river; wiggle there is geography). Still awaiting
  a fresh-coast test + COAST SRC/SHORE SMOOTH log lines.

## Known limits
- Patch scale 8 blocks; borders follow the ESA 10 m
  classes, so palette edges can look blocky at ESA tile
  scale (Step 3/wait territory, unchanged).
- Red-sand-vs-sand split is climate+elevation only; no
  soil-colour dataset (real red deserts: Australia,
  Arizona) approximated by arid+plateau rule.
- Rock override uses post-terrace heights; extremely
  smooth-but-steep slopes (1/block everywhere) read
  rk up to 8 and will rock over — intended.

## SAHARA MESA FIX (2026-11-20, owner report)

Owner at 23.40, 12.60 (Tibesti, Sahara): "mesa and grass blocks
and many more". Diagnosis (all data checked in sandbox):
climate 23.8 C / 12 mm (correct), elevation ~804 m, ESA 100%
class 60 BARE in 6x6 km. Chain: ELEV_HIGHLAND >= 500 m puts the
point in classifyBare's highland branch, which returned
BADLANDS with NO aridity floor -> biome mesa + Step-5 mesa
paint (terracotta family + ~12% grass patches, measured NW17).
Fix: in classifyBare highland branch, HYPER_ARID (< 50 mm/yr)
with temp >= 8 C now returns DESERT. Badlands family requires
>= ARID (US Southwest ~150-300 mm, Australian ranges qualify;
Sahara/Arabia/Atacama hyper-arid uplands become desert).
Parse-clean JDK17. Awaiting owner in-game proof at the same
coordinates in fresh chunks (old chunks never change).
