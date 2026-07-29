# "Can't keep up!" — root cause fixed

This is Finding 1, which I identified several turns ago and kept deferring. It is now
fixed. Compile is at the 2,596-error baseline with no new errors.

---

## What the log proves

```
00:02:34.913  TIMEJUMP_START  loaded=920 refreshQueueSize=32
00:02:35.063  TIMEJUMP_DONE   pass1Queued=1216 rescanIn=200ticks
00:02:54.228  POST_JUMP_RESCAN queued=450 skipped=248
00:02:56.202  Can't keep up! Running 146677ms or 2933 ticks behind
```

**21 seconds of wall clock, +106 seconds of new debt.** The server was spending
roughly 6× realtime inside tick work. The debt appears immediately after the time jump
queues 1,216 chunks — not during terrain generation, not during the Microsoft parse.

## The cause, traced to the line

```
ServerTickEvent                                   [Server thread]
 └─ StructureTickHandler.onServerTick        (line 242)
     └─ TerraChunkGenerator.drainRefreshQueue
         ├─ PASS 1  up to 64 chunks/tick (burst after a jump)
         │   └─ fetchStructuresForChunk(..., STRUCTURE_FETCH_TIMEOUT_MS = 8000)
         │       └─ OsmStructureIndex.getStructuresForChunkBlocking
         │           └─ CompletableFuture.allOf(...).get(8000, MILLISECONDS)   ← BLOCKS
         └─ PASS 2  2 chunks/tick
             └─ refreshStructuresForChunk → same blocking call
```

A single cache miss froze the entire server for up to **8 seconds = 160 ticks**. The
post-jump burst allows 64 chunks per tick, so the theoretical worst case is
**64 × 8 s = 512 s in one tick**. The tick budget is 50 ms.

This is why every previous optimisation failed to move the number: they all made work
*downstream* of the stall faster. The stall itself was the tick thread sitting in
`Future.get()`.

**Important distinction.** Blocking on `Worker-Main-N` (vanilla chunk generation, where
`applyBiomeDecoration` runs — the log shows `[Worker-Main-14] Terra++Biome: QUERY ...`)
is fine: it delays one chunk. Blocking on `[Server thread]` freezes the world. Only the
latter was wrong, and only the latter was changed.

---

## Fix 1 — the tick thread never blocks

New method `fetchStructuresCachedOnly(level, chunk)`:

- `areTilesLoaded(...)` — a pure map lookup, no network — decides whether data is resident.
- **Resident:** return it via `getStructuresForChunk` (the non-`Blocking` variant, memory only).
- **Not resident:** hand the tile to `AsyncStructureManager.queueRetry(...)` and return `null`.

Both passes now use it. On `null` the chunk is put back on the queue and the tick moves
on. Worst case per chunk goes from **8,000 ms to microseconds**.

The async machinery already existed and already worked — your own log shows it:

```
01:09:05.769  BLOCKING TIMEOUT: 0/1 tiles in 4000ms — null
01:09:05.769  Queued retry for tile [17.4400,78.5400]
01:09:08.113  OSM retry success (0 structures, attempt 1)
```

2.3 s asynchronously versus 4 s of blocking *and still failing*. The refresh path simply
never used it.

**Behaviour change:** after a time jump, chunks fill in over a few seconds instead of
the server freezing while they load. That is exactly what the two-pass refresh design
was for.

## Fix 2 — one packet per chunk instead of ~15,000

`sendChunkToClients` claimed in the file header to use
`ClientboundLevelChunkWithLightPacket`. It did not. It looped
`16 × 16 × (surfaceY−2 … surfaceY+60)` and called `level.sendBlockUpdated()` for every
non-air block:

```
16 × 16 × 62  = 15,872 block reads per chunk
× 2 chunks/tick = up to 31,744 sendBlockUpdated calls per tick
```

Each of those also triggers lighting and neighbour-shape updates on the server thread,
so the true cost is well above a raw packet write.

Replaced with a single `ClientboundLevelChunkWithLightPacket` sent to players tracking
that chunk. **~15,000 packets per chunk → 1.** The now-pointless 16×16 heightmap scan
that computed the Y window was deleted with it.

## Fix 3 — stop discarding the cache on failure

`refreshStructuresForChunk` did `refreshStructureCache.remove(k)` at the *start*. If
placement then failed, the chunk was re-queued with its cached structures already
thrown away, forcing another network fetch for data it had just held.

Now it peeks with `get(k)` and only removes after `generateStructuresFromList` succeeds.

---

## Verification

- Whole project compiles: **2,596 errors**, identical to baseline, all pre-existing
  Minecraft-absent errors. Zero non-dependency errors in `TerraChunkGenerator`.
- Confirmed by inspection that **no** `*Blocking` call or `STRUCTURE_FETCH_TIMEOUT_MS`
  reference remains reachable from `drainRefreshQueue` (passes 1 and 2) or from
  `refreshStructuresForChunk`.
- The five remaining `STRUCTURE_FETCH_TIMEOUT_MS` uses are all inside
  `applyBiomeDecoration`, which runs on `Worker-Main-N` — correct place to block.

## What to expect, and how to judge it

Watch the **tick debt number**, not the frame rate:

```
Can't keep up! Running <N>ms or <M> ticks behind
```

Across your sessions it went 4642 → 3489 → 2933 ticks while I optimised downstream
work. If this fix is right, the post-time-jump spike should largely disappear, because
the tick thread can no longer wait on the network.

**If the number does not drop, my diagnosis is wrong** and I will say so rather than
moving to the next symptom. The specific thing to look for: a `Can't keep up!` in the
20 seconds following a `TIMEJUMP_DONE`.

## Caveats

- **Not run in Minecraft.** I cannot execute MC or the ForgeGradle remap here.
  Verification is compile plus static proof that the blocking calls are gone from the
  tick path.
- `getViewDistance()` is read per player per chunk send; trivial, but it could be
  hoisted if profiling ever shows it.
- The client resync now sends a full chunk packet, which is more bytes per chunk but
  vastly fewer packets and no server-side lighting churn. On a local integrated server
  this is unambiguously better; over a real network it is still better, because the
  15,000 individual updates were each a packet too.
- Two known issues remain unfixed and are *not* performance-related: the `ChunkMap`
  reflection failure (`getChunks()` cannot resolve under `official` mappings — the
  fallback works, so it is cosmetic), and the `Climate.Precipitation` channel bug.
