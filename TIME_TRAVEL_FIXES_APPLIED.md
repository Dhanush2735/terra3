# Time travel — remaining blockers fixed

The time-travel system (TerraTimeManager → PersistentChunkRefreshIndex →
HistoricalStructureManager → OHM/OSM/VIDA/Microsoft tiers) was already ~90%
built and its worst bugs (main-thread blocking, VIDA heap, shard lookup) were
fixed in earlier passes. This pass fixes the four remaining defects that made
time jumps visibly wrong, all from FULL_REPO_AUDIT.md.

---

## 1. FINDING 2 — heightmap rebuild was a no-op (FIXED)

`syncHistoricalWater` called `heightmap.update(lx, 0, lz, STONE)` per column.
Vanilla `Heightmap.update` early-exits when `y <= firstAvailable - 2`; terrain
sits hundreds of blocks above y=0, so **every call returned false** and the
heightmap was never rebuilt. After a time-jump water drain, `ch.getHeight()`
kept returning the old water surface Y and `placeBuiltUpProgression` skipped
every drained column — the documented symptom in the code's own comment.

**Fix** (`TerraChunkGenerator` ~line 1378): replaced with
`Heightmap.primeHeightmaps(ch, EnumSet.of(WORLD_SURFACE, MOTION_BLOCKING,
MOTION_BLOCKING_NO_LEAVES, OCEAN_FLOOR))` — a real top-down rescan, exactly
what the comment promised. All four gameplay-relevant maps are rebuilt because
water→land conversion changes all of them.

## 2. FINDING 3 — second per-block packet storm (FIXED)

`TerraChunkGenerator.sendChunkToClients` was already fixed to send ONE
`ClientboundLevelChunkWithLightPacket`. But **`StructureTickHandler` had its
own forgotten copy** still looping 16×16×(surface span) and calling
`level.sendBlockUpdated` per non-air block — up to ~15,000 packets per chunk,
2 chunks/tick, on the main thread, after every refresh placement.

**Fix**: replaced with the identical single-packet resync (view-distance
filtered per player), mirroring the generator's version.

## 3. Roads frozen in the modern era (FIXED — the audit's "dead code" item)

`HistoricalStructureManager.getRoadMaterialKey(year)` mapped eras to materials
but was never called anywhere; a Roman road in 200 BC rendered in black
concrete. Now wired into actual block selection:

- `OsmStructureGenerator.roadSurf()` applies an **era cap** in historical mode:
  - year < −500 → dirt · −500..500 → cobblestone (Roman) · 500..1500 → gravel
  - 1500..1800 → smooth stone · 1800..1900 → cobblestone (sett) ·
    1900..1950 → gray concrete · ≥1950 → modern tags verbatim
- Cap, not replace: a `surface=dirt` track stays dirt in 1700; an asphalt tag
  is *demoted* to the era's best technology. Rank order:
  dirt/sand < gravel < cobble < smooth < concrete < asphalt.
- Verified by unit test: 13/13 cases pass (asphalt@−200→cobble,
  dirt@1700→dirt, asphalt@2026→asphalt, time-off→verbatim, …).
- Because `roadSurf` is the single material source used by roads, bridge decks
  and the instant-placement path, all three render consistently.
- **Road markings** (invented ~1918) suppressed before 1920 at all three
  painting sites (roads, bridge decks, parking bays).
- **Street lamps** era-gated: none before 1800, gas lamp (oak post + lantern)
  1800–1880, electric (iron pole + sea lantern) after 1880.

## 4. Anachronistic date estimation (FIXED)

`StructureDateEstimator.estimate()` clamped to a population floor but had no
concept of technology: gravity anchors near a medieval cathedral could date a
railway to 1300 and it would render in the Middle Ages.

**Fix**: new `getTechnologyFloor(type, subType)` applied as a second hard
minimum: railways 1825 (subway 1863, tram 1881, monorail/light rail 1901),
motorways 1930, trunk 1920, cycleways 1885, runways/aprons 1909, pylons 1880,
street lamps 1800, parking 1900, suspension bridges 1820, cable-stayed 1950.
Plain roads and bridges have no floor — they are ancient technology.
Explicit `start_date` tags are unaffected (floor applies only to *estimates*).

## FINDING 5 — dead reflection: already fixed

Verified by grep: no `getDeclaredMethod("getChunks")` remains;
`PersistentChunkRefreshIndex` uses the event-maintained chunk snapshot
(`scanMethod = "eventSnapshot"`). Nothing to do.

## FINDING 4 — cache consumed on failure: already fixed

Pass 2 now PEEKs (`refreshStructureCache.get`) and only removes after
`GeneratedBlockOwnershipIndex.commit` succeeds. Nothing to do.

---

## State of time travel after this pass

| subsystem | state |
|---|---|
| Year progression (`terra_time.dat`, scales 1/10/25/50×) | working |
| `/tt year <y>` jumps, generation counter, per-chunk stale years | working |
| Building visibility both directions (start/end year) | working |
| Date resolution: explicit → WikiData → gravity → population floor | working, now + technology floor |
| OHM → OSM → VIDA → Microsoft tiering with geometric dedup | working |
| Water drain/place on jump | working, **now with real heightmap rebuild** |
| Client resync after refresh | **now 1 packet/chunk in both paths** |
| Era road materials / markings / lamps | **now implemented** |
| Vegetation pre-clear before upcoming buildings | working (FOREST_CLEAR_LEAD_YEARS) |

Compile: no new error kinds vs the Minecraft-absent baseline; era logic
verified by 13 standalone unit tests (all pass).

## Suggested next steps (not yet done)

1. **Era building materials** — building walls/roofs are era-blind; medieval
   towns render with modern concrete styles. Same cap approach would work.
2. **Railway track gating** — rails now date correctly via the technology
   floor, but track *blocks* (gravel + rail) could downgrade to nothing before
   1825 even when OSM lacks dates entirely (belt-and-braces).
3. `/tt speed` live-rescan radius could use the deferred ledger instead of the
   20-chunk cap for very fast time scales.
