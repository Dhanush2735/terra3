# The 6-axis world-line system — applied

Goal: full six-dimensional travel —

| axis | coordinate | mechanism | state |
|---|---|---|---|
| 1-3 | x, y, z | vanilla movement / `/tp` | already worked |
| 4 | year | `/terra timejump` + refresh machinery | already worked (fixed last pass) |
| 5 | **timeline branch** | `/terra worldline branch …` — in-place morph | **NEW** |
| 6 | **universe** | `/terra worldline universe …` — separate dimensions | **NEW** |

A player's full position is now `(x, y, z, year, branch, universe)`.

Design per user decisions: **hybrid model** (branches morph the prime world
in place; universes are separate dimensions with their own chunk storage),
**exotic/rule-breaking universes**, **event-driven branches** (Eternal Engine
socket) with a seeded default so travel is visible before Eternal is wired.

---

## Axis 5 — alternative timelines (`WorldLineRegistry`)

A branch = same Earth, different history. Implementation:

**Chunk staleness fingerprint.** `PersistentChunkRefreshIndex` stores a short
per chunk to detect "generated under a different year". That short is now
`WorldLineRegistry.fingerprintShort(yearShort)` — year ⊕ branch salt. On the
main timeline it's the identity (old region files stay valid); on a branch
every chunk reads stale, so **switching branch drives the exact same in-place
morph as a time jump** (`advanceRefreshGeneration → scheduleLoadedChunkRefresh`,
nearest-player-first, ~10-64 chunks/tick). No new refresh machinery.

**What changes on a branch:**
- *Estimated* structure dates shift via `adjustEstimatedYear` — hooked into
  `StructureDateEstimator.estimate()` AFTER gravity/population, BEFORE the
  floors (population + technology floors still bind: no branch can create
  railways before 1825). Explicitly dated structures (OHM/OSM `start_date`)
  are history's fixed points and are never touched.
- *Existence*: `structureAllowed(osmId, lat, lon)` gates all four visibility
  paths in `HistoricalStructureManager` (OHM, OSM, VIDA, Microsoft) — a
  provider can declare "this building was never built here".

**Two effect sources:**
1. **Seeded default** (active immediately): branch id → deterministic salt →
   smooth ±~110-year development offset varying over ~2 km cells. Same branch
   id = same alternative world on every server, forever.
2. **Event-driven provider** (the Eternal Engine socket):
   `WorldLineRegistry.setBranchEffects(branchId, BranchEffects)` overrides the
   default with real divergence consequences. Eternal's
   `AlternativeTimelineEngine` can register a provider derived from its
   prevented-event chains — its `TimelineBranch` maps 1:1 onto ours
   (id, parent, divergenceYear, cause).

**Persistence:** `terra_worldline.dat` in the world root (branch list +
active branch), loaded before the first staleness check via
`PersistentChunkRefreshIndex.onLevelLoad`.

## Axis 6 — alternative universes (`UniverseTransforms` + dimensions)

A universe = physically different Earth in its **own dimension** (own chunk
storage — builds persist per-universe; return travel finds them intact).

Each universe is a pure deterministic transform applied at the two chokepoints
ALL terrain facts flow through (`TerraDensityFunctions.toGeo/fromGeo/classify`
and `getElevationInterpolated`). Coastlines, rivers, biome choice, structure
ground-snapping — everything downstream inherits the transform automatically.

| id | rule broken | effect |
|---|---|---|
| `prime` | none | our Earth |
| `drowned` | sea +60 m | coastal cities underwater (structures still generate → drowned metropolises) |
| `glacial` | sea −120 m | ice-age land bridges (Doggerland, Bering, Sunda) walkable |
| `inverted` | elevation × −1 | Everest is an abyss, Mariana Trench an 11 km peak |
| `mirror` | longitude × −1 | Earth mirror-imaged; OSM buildings flip too (fromGeo applies the inverse, so structure fetch stays consistent) |
| `amplified` | relief × 2.5 | exaggerated mountains/trenches |

**Wiring:** `TerraChunkGenerator.CODEC` gained an optional `"universe"` field;
five datapack dimension JSONs (`data/terraplusplus/dimension/universe_*.json`)
instantiate the earth generator with each transform. Data-driven dimensions
auto-load at world start in 1.20.1 — no dynamic registration code needed.
The prime generator keeps ownership of the `activeInstance` singleton so
exotic dimensions can't hijack the refresh queues.

Adding a universe = one entry in `UniverseTransforms` + one JSON file.

## The travel interface

```
/terra worldline                          — show your 6-axis coordinate
/terra worldline branch list
/terra worldline branch create no_rome -390 Rome sacked permanently
/terra worldline branch switch no_rome    — world re-forms around you
/terra worldline universe list
/terra worldline universe travel drowned  — teleport, same x/z, surface-snapped y
/terra worldline universe travel prime    — home
```

Universe travel preserves x/z (same world-line position, different reality)
and snaps y to the destination's surface — with a fall-back that never buries
the player when the destination chunk hasn't generated yet.

Time (`/terra timejump`) and branches work *inside* any universe: each
dimension has its own chunk storage, and the historical pipeline runs
wherever the generator runs. Full 6-axis addressing.

## Verification

- **27/27 unit tests pass** (standalone, real registry classes + log4j):
  fingerprint identity on main / divergence on branch, never-UNSET sentinel,
  deterministic + bounded + place-varying offsets, provider override + erase,
  persistence round-trip, salt stability, all six elevation/geo transforms,
  mirror inverse round-trip, unknown-id fallback.
- **Compile:** no new error kinds vs the Minecraft-absent baseline (all
  remaining errors are missing-Minecraft-classes noise present before).
- Every `StructureType` switch remains exhaustive (no repeat of the earlier
  Gradle failure).

## Files

- NEW `generator/historical/WorldLineRegistry.java` — axis 5 registry, salts,
  effects SPI, persistence
- NEW `generator/UniverseTransforms.java` — axis 6 transform registry
- NEW `generator/historical/TerraWorldLineCommand.java` — travel commands
- NEW `data/terraplusplus/dimension/universe_{drowned,glacial,inverted,mirror,amplified}.json`
- `PersistentChunkRefreshIndex` — fingerprint in `currentYearShort()`, registry
  load on level load
- `StructureDateEstimator` — branch adjustment between gravity and floors
- `HistoricalStructureManager` — 4 visibility sites gated by `structureAllowed`
- `TerraDensityFunctions` — universe transform at all 5 chokepoints
- `TerraChunkGenerator` — `universe` codec field, ctor overload, `getDf()` pass-through

## Next steps (not yet done)

1. **Eternal Engine bridge**: implement `BranchEffects` inside Eternal's
   `AlternativeTimelineEngine` (reflection or soft-dependency) so branches
   carry real event consequences ("Rome never fell" → Roman-era architecture
   persists across Europe).
2. **Portals**: physical structures (ruined-portal-style) that execute
   `worldline universe travel` on contact, instead of commands.
3. **Per-branch block edits**: player builds currently survive branch morphs
   only where chunks aren't refreshed; a branch-aware ownership ledger could
   snapshot/restore player edits per branch.
4. **More universes**: `archipelago` (only peaks above +2000 m), `pangea`
   (longitude compression), `hollow` (inverted inside a shell)…
