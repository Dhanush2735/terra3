# Biome/Ground Step 3 — stop cold-start ground fossilisation

Status: IMPLEMENTED in source 2026-11-19, JDK 17 parse-clean.
NOT yet proven in-game. Owner proof = rebuild + fresh land area
+ log lines below. (Steps 1+2 are PROVEN — see
BIOME_GROUND_FIX_STEP1_2.md. Plan: BIOMES_GROUND_AUDIT.md.)

## The problem (audit P1)

`EsaWorldCoverIndex.getClass` is non-blocking: on a cold tile it
returns UNKNOWN and starts an async fetch. Terrain generation
prefetched only COG *headers*, then painted immediately — so a
first-visit chunk got fallback (generic grass-like) ground, and
the wrong surface was saved forever. Later vegetation used the
real class, but the top block never repainted.

## The fix (audit road A — bounded wait, same pattern as COAST WAIT)

In `TerraChunkGenerator.generateTerrain`, just before the
painting loop: if the classified grid contains any LAND /
RIVER_BANK / LAKE_SHORE / COASTLINE column, sample the chunk
centre + 4 corners with the existing
`EsaWorldCoverIndex.getClassBlocking(lat, lon, remainingMs)`
under a shared 5 s deadline (checked between samples; a single
in-flight header/tile fetch may run past it while finishing).
Pure-ocean chunks never wait. Painting runs on worker threads,
so no player-facing stall. Warm tiles return instantly because
header + internal tile are already cached.

Log proof (throttled to 50, only when waited >100 ms or failed):
`ESA WAIT [x,z]: waited=Nms loaded=true (Step 3: real WorldCover ground, no fossil fallback)`
`loaded=false` means the network was too slow and that chunk
still painted fallback — report it if seen often.

Side benefit: `assignBiomes` runs right after `generateTerrain`,
so biome assignment now also sees warm ESA.

## What Step 3 does NOT do

- Does not heal already-saved chunks (they keep their ground;
  audit road B repaint was not chosen).
- Does not change the palette (Step 5), beaches (Step 4),
  TUFF blanket (Step 6) or snowline (Step 7).

## Owner test

1. `gradle jarJar`, new world (or fly far to never-visited land).
2. Log should show `ESA WAIT ... loaded=true` lines at first,
   then stop as tiles warm up.
3. First `SURFACE [...]` log lines should show a real class
   (`wc=FOREST`, `wc=CROPLAND`, ...) — not `wc=UNKNOWN`.
4. Ground in a fresh distinct area (forest, farmland, desert)
   should match the real land cover, not generic grass.
