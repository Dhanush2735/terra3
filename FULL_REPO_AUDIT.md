# Full repository audit — terra3

Read every file: 224 total, 149 Java sources, plus `mc/` vanilla reference
dumps, resources, gradle config. Findings ordered by severity.

---

## FINDING 1 — Blocking network I/O on the server main thread

**This is the root cause of the `Can't keep up!` stalls.**

Six turns of work blamed VIDA, Microsoft, dedup and retention. All wrong. The
call chain:

```
ServerTickEvent  ← the thread that ticks the entire world
  └─ StructureTickHandler.onServerTick          (line 219)
       └─ TerraChunkGenerator.drainRefreshQueue (line 242)
            ├─ PASS 1 — up to 10 chunks/tick, 64 in burst
            │    └─ fetchStructuresForChunk(..., 8000 ms)
            │         └─ getStructuresForChunkBlocking()   ← BLOCKS
            └─ PASS 2 — up to 2 chunks/tick
                 └─ refreshStructuresForChunk
                      └─ fetchStructuresForChunk(..., 8000 ms)
                           └─ getStructuresForChunkBlocking()  ← BLOCKS
```

`STRUCTURE_FETCH_TIMEOUT_MS = 8000`. A tick has a 50 ms budget.

| pass | chunks/tick | worst-case block |
|---|---|---|
| 1 normal | 10 | 80 s |
| 1 burst (`postJumpRescanCountdown > 0`) | 64 | **512 s** |
| 2 | 2 | 16 s |

A single tick can block for minutes. Your log:

```
Can't keep up! Running 232101ms or 4642 ticks behind
Can't keep up! Running 174494ms or 3489 ticks behind
```

232 seconds of tick debt is consistent with main-thread network blocking and
with nothing else in this codebase.

**The infrastructure to avoid this already exists and is well built.**
`AsyncStructureManager` → `StructurePlacementQueue` → `StructureTickHandler`.
`StructurePlacementQueue`'s own Javadoc documents the threading contract:
*"enqueue may be called from any background thread; poll/requeue/markPlaced are
called from the server tick thread."* That is exactly the correct design. The
refresh path simply does not use it — it blocks inline instead.

Your log even shows the async path outperforming the blocking one it falls back
from: `BLOCKING TIMEOUT ... 4000ms` at 01:09:05, then `Queued retry` → `OSM
retry success` at 01:09:08. **2.3 s async vs 4 s blocking-and-failing.**

**Fix:** `drainRefreshQueue` must never call a `*Blocking` method. On a cache
miss, enqueue to `AsyncStructureManager` and move on. Chunks refresh a few
seconds later — which is what the two-pass system was built to do.

---

## FINDING 2 — The heightmap rebuild is a no-op

`syncHistoricalWater`, after draining water columns:

```java
var heightmap = ch.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);
for (int lx = 0; lx < 16; lx++)
    for (int lz = 0; lz < 16; lz++)
        heightmap.update(lx, 0, lz, STONE);   // y = 0
```

Vanilla `Heightmap.update` (confirmed in your own `mc/Heightmap.txt`, line 77):

```java
int i = this.getFirstAvailable(x, z);
if (y <= i - 2) return false;    // early exit
```

Terrain here sits near y=587, so `getFirstAvailable` returns ~588. With `y = 0`:
`0 <= 586` → **returns false immediately, every call.** The heightmap is never
rebuilt.

The code's own comment states why this matters:

> *"Without this, ch.getHeight() still returns the old water surface Y and
> placeBuiltUpProgression skips the column entirely."*

So the documented failure is exactly what still happens. Drained water columns
keep a stale surface Y, and the built-up progression skips them.

**Fix:** `Heightmap.primeHeightmaps(...)`, or re-run `update` descending from
the actual top of each column rather than from y=0.

---

## FINDING 3 — `sendChunkToClients` sends thousands of packets per chunk

The file header claims:

> *"Client chunk resync via ClientboundLevelChunkWithLightPacket"*

The implementation does something entirely different:

```java
for (lx 0..15) for (lz 0..15)
    for (y = globalMinY; y <= globalMaxY; y++) {
        if (st.isAir()) continue;
        level.sendBlockUpdated(pos, st, st, 3);   // ONE PACKET PER BLOCK
    }
```

`globalMaxY = max(surfaceY) + 60`, so the span is ≥62 blocks:

| y-span | `getBlockState` calls | potential packets |
|---|---|---|
| 62 | 15,872 | 15,872 |
| 120 | 30,720 | 30,720 |

Pass 2 runs 2 chunks/tick → up to **31,744 block updates per tick**, on the main
thread. The intended single-packet resync would be one per chunk.

**Fix:** send `ClientboundLevelChunkWithLightPacket` once, as the header says.

---

## FINDING 4 — Cache consumed on a path that can fail

```java
// PASS 1
structs = fetchStructuresForChunk(...);        // blocks up to 8 s
if (structs != null && !structs.isEmpty())
    refreshStructureCache.put(k, structs);

// PASS 2
all = refreshStructureCache.remove(k);          // REMOVE, not get
if (all == null) {
    all = fetchStructuresForChunk(...);         // blocks AGAIN
    if (all == null) return false;              // chunk not marked refreshed
}
```

If `generateStructuresFromList` fails, the chunk is never marked refreshed, so
`needsRefresh` stays true and `onChunkLoad` re-queues it — but the cache entry
was already removed. Every retry pays another blocking fetch.

**Fix:** `get` rather than `remove`, and only evict after a confirmed successful
placement.

---

## FINDING 5 — Dead reflection in the time-jump path

`PersistentChunkRefreshIndex` and `TerraTimeCommand` both call:

```java
chunkMap.getClass().getDeclaredMethod("getChunks")
```

`ChunkMap.getChunks()` is package-private and `mapping_channel=official`, so at
runtime the name is SRG (`m_140416_`-style). It can never resolve. Your log,
every jump:

```
ChunkMap reflection failed: ...getChunks() — using fallback
POST_JUMP_RESCAN chunkMap failed: ... — using snapshot fallback
```

The fallback works, so this is wasted work plus a misleading warning. There is
already an `accesstransformer.cfg` — either widen access there or delete the
reflective path.

---

## FINDING 6 — `TerraBiomeProvider` uses unbounded `.join()`

Lines 258–260 call `.join()` on three dataset futures with **no timeout**,
unlike every other blocking call in the codebase. Mitigated by a `dataCache`
keyed to 0.01° and by these being builtin datasets rather than network, so this
is low severity — but it is an unbounded wait on a generation path.

---

## What is actually well built

Worth stating plainly, because the problems above are concentrated in one file:

- **`StructurePlacementQueue`** — clean threading contract, bounded queues,
  attempt limits, defensive copies. Genuinely good code.
- **`PersistentChunkRefreshIndex`** — the region format (32×32 chunks, `BitSet`
  + `short[]`, ~2.2 KB/region) is compact and correct; `short` cannot collide
  with `UNSET_YEAR`. The `onChunkLoad` deadlock fix (queue, never mutate during
  load) is exactly right.
- **`terraminusminus` projections** — self-contained, well-factored, documented
  contracts on `toGeo`/`fromGeo`.
- **`OverpassRateLimiter`**, multi-endpoint failover, disk caching — all sound.

The mod's architecture is not the problem. `TerraChunkGenerator` is 4,479 lines
carrying terrain, water, biomes, vegetation, structures, cleanup, refresh queues
and client sync, and that is where every finding above lives.

---

## Priority

1. **Finding 1** — remove blocking calls from `drainRefreshQueue`. This is the
   stall. Everything else is secondary.
2. **Finding 3** — one packet per chunk instead of thousands.
3. **Finding 2** — fix the heightmap prime so drained water renders correctly.
4. **Finding 4** — cache `get` instead of `remove`.
5. **Findings 5–6** — cleanup.

Verify with the tick-debt number in the log, not with a benchmark. If tick debt
does not drop after Finding 1, the diagnosis is wrong.

I have not applied any of these. Say which you want and I will do them one at a
time, with the reasoning visible for each.
