# NW(22) SAVE AUDIT — read against the 20-place explore list (2026-11-20)

Owner: "according the list you given to me. there are more than usual
problems". Save read in sandbox: 18,982 chunks, 12 clusters, matched to
list places by an exact port of the world Dymaxion projection
(player position converts to Yakutsk 62.0355,129.6755 — the last
place stood). Per cluster: true surface biome census (biome stored
at the surface cell), top-block census, surface-Y stats, water,
raised-chunk flags.

## Visited (11 of 20): Tibesti x2, Atacama, Gandikota, Juhu,
## Mammoth Cave, Grand Canyon, Monument Valley, Erg Chebbi,
## Amazon, Everest, Yakutsk.
## NOT in save (no chunks): Krubera, Guilin, Everglades, Uluru,
## Iceland, Hyderabad, Giza, Santorini, Berlin — untested.

## PROBLEMS FOUND + FIXED

### F1 (worst) Cave biome bands leak onto the surface above 1000 m
Monument Valley surface biome = lush_caves in 100% of sampled
columns (raw chunk sections at ground level contain ONLY
lush_caves). Root cause: four cave-code sites computed surface
height as SL+round(real elevation metres), but world Y uses
scaleElevation() compression above 1000 m. The cave code
believed the ground was hundreds of blocks higher than it is:
- assignBiomes cave-band depth (TerraChunkGenerator:5093)
- Phase 1 karst worm surface guard + worm start (:3281, :3309)
- Phase 2 real-cave entrance surfY (:3438)
FIX: all four now use TerrainColumn.surfaceY (already scaled).
Consequences beyond MV: high-altitude karst caves (Caucasus,
Alps) started worms/entrances in the sky; entrances at real
caves above 1000 m were misplaced. Parse-clean JDK17.

### F2 Hot dry highlands still became mesa/stony, ground included
NW22 was generated without the (biome-only) Sahara fix being
effective, and the ground painter had NO dryness floor at all
(getBiomeTypeCode BARE: temp>=8, precip<500, elev 500-2600
-> mesa code 2). Data (climate grid):
Tibesti 23.8C/12mm, Erg Chebbi 20.8C/91mm, Atacama 12.5C/20mm
vs Monument Valley 11.7C/236mm, Grand Canyon 11.2C/500mm.
FIX: mesa paint + badlands family now require >= 150 mm/yr
(PRECIP_ARID) when temp>=8:
- getBiomeTypeCode BARE mesa gate adds precip >= PRECIP_ARID.
- classifyBare highland gate widened HYPER_ARID -> HYPER_ARID|ARID.
- classifyBare mountain branch + classifyMountainFallback:
  temp>=8 + (HYPER_ARID|ARID) -> DESERT (Atacama rule).
Expected: Tibesti/Erg Chebbi -> desert biome, red-sand family
ground, no terracotta bands, no grass patches. MV/GC keep mesa.

### F3 City TUFF blanket (Step 6, was planned-not-started)
Yakutsk: tuff = 30% of ALL tops (48,666 cols); San Pedro de
Atacama 27,382 tops. Source: decoration "ERA 8 MODERN 1900+"
converted every ESA BUILT_UP pixel top to TUFF.
FIX: patch mix — paved patches (patchHash<55) become TUFF,
open patches keep their natural top (lots/yards/parks).
Buildings/roads still draw over. Parse-clean.

## PLACE-BY-PLACE VERDICTS (save evidence)

- Tibesti (812 chunks): BADLANDS 100% surface; tops red_sand
  41%, grass_block 24%, coarse_dirt 17%, terracotta family
  ~15%. -> F2. Second Tibesti cluster (378 chunks) is only
  Status=structure_starts (generation unfinished at save
  time; sections air/plains placeholders). NOT corruption —
  completes when revisited.
- Atacama (1517): surface stony_peaks/windswept/grove, no
  desert; medY 1385 (2400 m real, compressed by design);
  town tuff tops. -> F2 + F3.
- Gandikota (2945): GOOD. savanna/river/desert surface,
  water 35.6% = the real Penna river (y63 fake-sea cols 0),
  cave biomes underground in 1217 chunks. Raised flags =
  gorge relief.
- Juhu (1051): GOOD. beach 420 + desert/savanna/warm_ocean
  samples, sand tops, sea water at Y63 is real sea.
- Mammoth Cave (2396): GOOD. forest/plains surface, cave
  biomes in 949 chunks, visitor-centre buildings/roads.
- Grand Canyon (1627): MOSTLY GOOD. badlands+river surface,
  terracotta/stone tops; some forest samples (rim vegetation).
- Monument Valley (1932): WORST. surface biome lush_caves
  100%, grass+tree tops. -> F1. (236 mm/yr, so mesa paint on
  bare ground legitimately stays.)
- Erg Chebbi (1846): BAD. badlands 100%, terracotta family
  tops ~117k vs red_sand 17.7k — a dune field painted as
  mesa. -> F2.
- Amazon (2198): GOOD. jungle/sparse_jungle 100%, canopy
  tops. Raised flags = tree-canopy median noise, not fossils.
- Everest (1655): BY DESIGN + minor. frozen_peaks 100%,
  snow tops; Y ~2004-2029 = scaleElevation compression to
  the 2032 ceiling (8848 m cannot fit 1:1). Minor: ~1.5%
  grass_block tops + a few flowers near summit (palette mix).
- Yakutsk (625): biome GOOD (snowy_taiga/snowy_plains/
  ice_spikes), ground BAD (tuff). -> F3.

Raised-chunk flags overall (229 Amazon, 147 MV, 100 GC...)
are steep-terrain/canopy median noise; NO NW(20)-style
uniform fossil lid found (no flat single-family cap offset).

## Owner retest (after gradle jarJar, FRESH chunks)
1. /tpll 36.9980 -110.0985 Monument Valley — F3 biome line
   must NOT say lush_caves; no cave biome on surface.
2. /tpll 23.399981551844657 12.600037396082294 Tibesti and
   /tpll 31.1485 -3.9766 Erg Chebbi — desert, sand ground,
   no terracotta bands, no grass patches.
3. /tpll -22.9098 -68.2019 Atacama — desert around the town.
4. /tpll 62.0355 129.6755 Yakutsk city — tuff only in paved
   patches; grass/dirt lots between.
5. Still-unvisited list places: Krubera 43.4097 40.3622,
   Guilin 24.90 110.49, Everglades 25.2866 -80.8987,
   Uluru -25.3444 131.0369, Iceland 64.75 -18.62,
   Hyderabad 17.3616 78.4747, Giza 29.9792 31.1342,
   Santorini 36.4618 25.3760, Berlin 52.5200 13.4050.
