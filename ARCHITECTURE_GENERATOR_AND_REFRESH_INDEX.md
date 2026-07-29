# Architecture: TerraChunkGenerator & PersistentChunkRefreshIndex

Two classes carry most of the mod's complexity. `TerraChunkGenerator` (4,455
lines) decides what blocks exist; `PersistentChunkRefreshIndex` (1,065 lines)
remembers which chunks are out of date and need redoing. They are coupled: the
index queues work, the generator performs it and reports back.

---

## Part 1 — TerraChunkGenerator

### What it is

A subclass of vanilla `ChunkGenerator`, registered through a `Codec` so it can be
stored in `level.dat` and rebuilt on world load. The codec persists four things:
biome source, projection name (`bte`), zoom (15), and the whole `TerraWorldConfig`.

Vertical range is `Y = [-2032, 2032]` (`GEN_DEPTH = 4064`), far beyond vanilla —
this is why the embedded TerraCubes/CubicChunks code exists.

### It deliberately does almost nothing vanilla

```java
@Override public void applyCarvers(...)     {}   // no caves
@Override public void buildSurface(...)     {}   // no vanilla surface rules
@Override public void spawnOriginalMobs(...) {}  // no mob spawning
```

Real-world terrain has no use for vanilla carvers or surface builders, so all
three are stubbed. Everything happens in `fillFromNoise` and
`applyBiomeDecoration`.

### The generation pipeline

**Stage 1 — `fillFromNoise`** (async, on the worker pool)

Runs `getChunkPresence(ch)` first, which samples 16 positions in a 4×4 grid and
inspects blocks to classify the chunk as `EMPTY`, `TERRAIN`, or `DECORATED`. This
is a recovery mechanism: it infers state by *looking at the world* rather than
trusting a flag, so a chunk that was already built is not rebuilt after a restart
or a crash. If presence is high enough it only assigns biomes and returns.
Otherwise `generateTerrain(ch)` runs, then `assignBiomes`, then
`markTerrainPlaced`.

`isStructBlock` backs this up with a hardcoded list of ~30 block types (concretes,
glass panes, doors, quartz, rails, lanterns, bricks…). Presence detection is
heuristic by nature: a player building with white concrete in an empty chunk can
make it look `DECORATED`.

**Stage 2 — `applyBiomeDecoration`** (main thread)

Resolves config, retries water if needed, then branches on `worldConfig.timeEnabled`:

- historical → `HistoricalStructureManager.getStructures` (OHM → OSM → VIDA → Microsoft)
- non-historical → inline chain (OSM → VIDA → Microsoft), gated by `activeSource`

Results go to `OsmStructureGenerator.generateStructuresFromList`. On failure the
chunk is handed to `AsyncStructureManager.queueRetry`.

### The two-pass refresh system

This is the part most people miss. Chunks already in memory cannot be regenerated
through the vanilla pipeline, so there is a parallel path with **two separate
queues** drained every tick by `drainRefreshQueue`:

| | queue | rate | work |
|---|---|---|---|
| Pass 1 | `refreshQueue` | 10/tick (burst higher after a jump) | vegetation only; clears trees inside structure footprints |
| Pass 2 | `structureRefreshQueue` | 2/tick | all structures — roads *and* buildings together |

The header comment records why they are split: roads used to be placed
"instantly" in the refresh path while buildings went through the normal
generator, so loaded and unloaded chunks diverged visually. Now both go through
`generateStructuresFromList`, and a `ClientboundLevelChunkWithLightPacket` resync
is sent afterwards so nothing stays invisible client-side.

`refreshStructureCache` lets pass 1 and pass 2 share a single fetch per chunk. It
is cleared wholesale above 2048 entries — crude, but bounded.

Pass 2's rate is the throughput ceiling. At 2 chunks/tick a 1,216-chunk jump
needs ~608 ticks (~30 s) *if nothing blocks* — and in the crash log it was far
worse because each chunk hit blocking Overpass fetches.

### Statics worth knowing

```java
public static volatile TerraChunkGenerator activeInstance;   // reflection target
private static volatile String activeSource = "both";        // osm | vida | overture | both
public static volatile int postJumpRescanCountdown;          // tick countdown after a jump
```

`activeInstance` is how outside code (including Eternal Engine's `TerraBridge`)
finds the live generator. `postJumpRescanCountdown` is a plain `int` counted down
in another class's tick handler — cross-class mutable static coupling that works
but is fragile.

### Lazy initialisation

Every heavy dependency is created on first use behind double-checked locking:
`getDf`, `getStructureIndex`, `getOvertureIndex`, `getVidaIndex`,
`getStructureGenerator`, `getPlacementQueue`, `getAsyncManager`,
`getHistoricalManager`. This keeps world creation fast and avoids network work
for features a world never uses. It is also where the duplicate-instance bugs
lived — `OvertureIndex` and `SourceCooperativeIndex` were each constructed here
*and* inside `HistoricalStructureManager` until both were made singletons.

---

## Part 2 — PersistentChunkRefreshIndex

### The problem it solves

After `/terratime jump 2026`, every chunk generated at 3000 BCE is wrong. There
may be tens of thousands, most not loaded. The index answers one question
cheaply, for any chunk, across restarts:

> has this chunk been generated, and if so for which year?

### Data model

A Forge `@Mod.EventBusSubscriber` holding entirely static state, organised into
**regions of 32×32 chunks** (`REGION_BITS = 5`), i.e. 512×512 blocks — and since
Terra++ is 1 block = 1 metre, each region is a half-kilometre square.

```java
final BitSet visited = new BitSet(1024);      //  128 B — has this chunk ever generated?
final short[] chunkYears = new short[1024];   // 2048 B — which year was it built for?
```

About 2.2 KB per region. `localIndex` is `(lz << 5) | lx`, row-major into both
arrays. Regions are cached in a `ConcurrentHashMap`, dirty ones tracked in a set,
flushed every 200 ticks and on server stop, into
`saves/<world>/data/terra_chunk_index/r.<rx>.<rz>.bin` with magic numbers and a
version field (v1 legacy, v2 current).

`short` is the right choice: the year range `[-3000, 9999]` fits comfortably, and
`UNSET_YEAR = Short.MIN_VALUE` (-32768) cannot collide with a real year.

### The staleness rule

```java
if (!region.visited.get(idx)) return false;   // never generated -> nothing to refresh
short stored = region.chunkYears[idx];
if (stored == UNSET_YEAR) return true;        // seen but never year-stamped
return stored != currentYearShort();
```

The first line matters: a chunk that has never existed is *not* stale. Only
already-built chunks need redoing.

### Lifecycle

- **`onLevelLoad`** — initialise paths, load meta (`globalGeneration`, `lastJumpYear`)
- **`onChunkLoad`** — `recordChunkSeen`, and if stale, queue a refresh
- **`onChunkUnload`** — drop from the loaded set
- **`onServerTick`** — flush dirty regions (200), stale scan (100, max 50/tick), post-jump rescan
- **`onServerStopping`** — final flush

The header comment documents a real deadlock that was fixed here: `onChunkLoad`
used to call `syncHistoricalWater()` inline, but the chunk lock is held during
load and `setBlock()` tries to re-acquire it. Now the handler only *queues*.
That is the central discipline of this class — observe and record during events,
mutate later on the tick thread.

### Time-jump flow

```
/terratime jump 2026
  ├─ TerraTimeManager.forceRecalculate      recompute year, log the jump
  ├─ advanceRefreshGeneration(level, 2026)  globalGeneration++, save meta
  ├─ water sync over loaded chunks
  ├─ scheduleRefresh -> both queues         1216 chunks queued
  └─ postJumpRescanCountdown = 200          semi-loaded rescan in 10 s
```

Then `drainRefreshQueue` works it down at 10/tick and 2/tick, and
`markChunkRefreshed` stamps each finished chunk with the new year. Unloaded
chunks are caught later by `onChunkLoad`, which is why the design survives a
jump affecting far more chunks than are resident.

---

## How they interact

```
PersistentChunkRefreshIndex          TerraChunkGenerator
  onChunkLoad
    needsRefresh? ────────────────►  scheduleSingleChunkRefresh
                                       refreshQueue / structureRefreshQueue
                                     drainRefreshQueue  (every tick)
                                       pass 1 vegetation
                                       pass 2 structures
  markChunkRefreshed  ◄────────────    on success
```

The index never touches blocks; the generator never persists state. That
separation is the strongest part of the design.

---

## Known weaknesses

**The reflection in the jump path is broken on your setup.** Both
`PersistentChunkRefreshIndex` and `TerraTimeCommand` call
`chunkMap.getClass().getDeclaredMethod("getChunks")`. `ChunkMap.getChunks()` is
package-private, and with `mapping_channel=official` the runtime name is SRG
(`m_140416_`-style), not `getChunks`. Your log confirms it:

```
ChunkMap reflection failed: net.minecraft.server.level.ChunkMap.getChunks() — using fallback
POST_JUMP_RESCAN chunkMap failed: ... — using snapshot fallback
```

It never succeeds in production; the fallback always runs. The fallback works, so
this is wasted work and a misleading warning rather than a bug — but the reflective
path should either be fixed via an access transformer (there is already an
`accesstransformer.cfg`) or deleted.

**`TerraChunkGenerator` is 4,455 lines** spanning terrain, water, biomes,
vegetation, structures, cleanup, refresh queues, and client sync. The section
banners are doing the work a class boundary should. Water sync alone
(~lines 773–1095) would be a clean extraction.

**Presence detection is heuristic.** Inferring "already generated" from block
samples is clever recovery, but a player building in concrete can fool it.

**Pass 2 at 2 chunks/tick** is the refresh ceiling, and each chunk may block on
network fetches — the mechanism behind the `Can't keep up!` entries.

**Cross-class mutable statics** (`postJumpRescanCountdown`, `activeSource`,
`activeInstance`) work but make control flow hard to follow.
