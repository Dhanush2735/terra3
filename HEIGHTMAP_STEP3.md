# Heightmap Step 3 — spike guard in the game (2026-11-18)

**Implemented, sandbox-proven, awaiting owner build + log.
Terrarium is untouched as a source; the guard cleans
decoded pixels in memory only, behind
`/terra heights guard on|off` (default ON).**

## Discovery that reshaped the step

The codebase already had a tile repair inside
`TerraDensityFunctions.validateAndRepairTile` (neighbour
median, radius 2, >500 m deviation, spread gate, 8 passes,
quarantine >5%). Measured on real z15 tiles with the REAL
new Java guard class (harness, modes median-only vs full):

| Tile (z15) | Raw book | Old repair (median) | New FULL (median+anchor) |
|---|---|---|---|
| Dead Sea worst tile 19620/13359 | min **−5,397.1** | min −415.3 (294 px) — already fixed | identical (anchor 0) |
| Caspian Sea 20980/12164 | min **−9,935.2** | **repairs 0 — min stays −9,935.2** | **min −29.0, anchor repairs 512** |
| Grand Canyon / Manhattan / Charminar / Everest | sane | 0 changed | 0 changed (summit 8,750.3 intact) |

So the old the game is wide *smooth/garbage patches*
whose neighbourhood agrees with the lie (the Caspian
garbage rows defeat every local test). The new zoom-anchor
stage (fine vs zoom-11 parent, flat/agreement/monster
gates from workshop v3c) is exactly the missing layer.

## What changed (4 files)

1. **NEW `generator/HeightSpikeGuard.java`** — dependency-
   free guard: stage 1 = the historical median repair
   (semantics ported 1:1), stage 2 = zoom-anchor; static
   `enabled` switch + `TOTAL_REPAIRED` / `ANCHOR_REPAIRED`
   / `TILES_GUARDED` counters. Compiles clean standalone.
2. **`generator/TerraDensityFunctions.java`**
   - `validateAndRepairTile` now delegates to the guard
     (skipped entirely when the guard is off — pure
     Terrarium; malformed tiles still rejected) and fetches
     the zoom-4 parent via `coarsePixelsForAnchor`
     (synchronous, no executor futures → no deadlock;
     parents at zoom ≤12 never anchor → no recursion).
     Quarantine rules (>5% impossible / >5% repaired)
     unchanged.
   - **Disk purity (owner directive):** downloads now save
     the RAW tile to `terra_cache` before repair;
     `loadFromDisk` repairs in memory only and never writes
     back (older caches may already hold repaired pixels —
     re-repair is idempotent). Guard OFF therefore restores
     the original book 1:1 for every newly loaded tile.
   - `prefetchArea` also warms the zoom-4 anchor parent.
3. **`commands/TpllCommand.java`** — `/terra heights guard
   on|off|status` (same family as `/terra source`).
   Switching applies to tiles loaded afterwards; restart
   for a 100% clean A/B (reply text says so).
4. **`commands/TerraCheckCommand.java`** — status line
   shows guard state + repaired count; ELEVATION line adds
   `heightGuard/guardRepairs/anchor`; new bench probe
   **CASPIAN MONSTER-POINT (42.0002, 50.4987)** — a point
   whose four bilinear corners sit inside the garbage
   rows: raw book −9,935.2 m, anchor should serve ≈ −29 m.

Parse check: JDK 17 javac over all four files — zero
errors other than the expected missing Forge/Minecraft
dependencies. Sandbox cannot build the full mod; the
owner's `gradle jarJar` + log remains final proof, as
always.

## Predictions (written BEFORE the owner's run)

1. `/terracheck` status: `Height spike guard: ON`.
2. After EXTREMES loads the Caspian tile (may need the
   second run): `guardRepairs ≥ 512 (anchor=512)`.
3. CASPIAN MONSTER-POINT reads **≈ −29 m** (guard ON).
4. EXTREMES/SEAM/SENTINEL verdicts unchanged (PASS/SAFE);
   Hyderabad centreElev ≈ 507–508 m as before.
5. A/B proof: `/terra heights guard off` + restart +
   `/terracheck` → monster-point reads **thousands of
   metres deep** and repairs stay 0; `on` + restart
   restores ≈ −29 m. (Mid-session toggles can't unrepaired
   already-cached tiles — by design, and the command
   reply warns about it.)
6. No new lag: guard cost is one pass over tiles at load
   (they are cached afterwards); coarse parents are
   prefetched on teleport.

## Build round 1 (owner, 2026-11-18 21:06)

FAILED with 6 errors, all in TpllCommand: `cannot find
symbol HeightSpikeGuard`. Cause: the
`import ...generator.HeightSpikeGuard;` edit was lost to
the parallel-edit rollback (the methods landed, the
import did not), and the sandbox parse check filtered
"cannot find symbol" as expected-Forge-noise, masking it.
The owner's compiler was the real check and caught it.
FIX: one import line added at TpllCommand.java:10;
re-verified — zero HeightSpikeGuard errors, zero other
non-dependency errors. Only TpllCommand.java changed;
rebuild.

## Result (owner's log, 2026-11-18 21:17-21:19) — PASS

Fresh world (New World (7)), two /terracheck runs. Every
scored prediction hit:

| # | Prediction | Log evidence |
|---|---|---|
| 1 | Guard ON in status | `Height spike guard: ON repaired 0 px` → run 2: `repaired 730 px` |
| 2 | Anchor repairs ≥ 512 | `Height guard repaired 512 suspect elevation samples in tile 15/20980/12164 (512 by zoom-anchor)` — the exact Caspian garbage tile from the workshop; bench total `guardRepairs=730 (anchor=714)` |
| 3 | Monster-point ≈ −29 m | `CASPIAN MONSTER-POINT (42.0002, 50.4987): elev=-29.0 m` (both runs; raw book: −9,935.2) |
| 4 | EXTREMES/SEAM/SENTINEL unchanged | EXTREMES PASS (Everest 8728.9→Y2022, Hyderabad 508.0→Y571, Caspian −29.0), SEAM PASS, SENTINEL SAFE 0/5 |
| 5 | A/B off/on proof | not attempted (optional) — switch + restart still available any time |
| 6 | No new lag | spawn prep 4.9 s, chunks placed continuously, no "Can't keep up" in the log |

The guard also fired opportunistically on real Dead Sea
basin tiles during the run (15/19615/13402: 1 anchor;
15/19619/13371: 7 anchor; 15/19610/13339: 210 repaired,
194 by anchor) — total 730 = 512+1+7+210, anchor 714.
Arithmetic in the bench line matches the four repair
log lines exactly. Overture SERVING again (release
2026-10-21.0), Microsoft disk-served, DEAD SEA GRID
verdict unchanged (SOURCE DATA DIRTY — the guard kills
monster pixels, not the DEM's vintage; appearance
remains the owner's eyes).

One neutral note: the run's REFILL line read "structure
pipeline not initialised yet" because /terracheck was
pressed seconds after joining; placements flowed right
after. Unrelated to the guard (elevation-only change).

## Second run confirmation (owner log 2026-11-18 21:29–21:31)

Repeatability proven in another fresh world (fresh
cache): session total **1,039 repairs (anchor 736)** —
arithmetic again matches the five repair lines exactly
(spawn-area tile 15/23528/14772: 6 anchor; Caspian
15/20980/12164: 512 anchor; Dead Sea basin
15/19615/13402: 1, 15/19619/13371: 22 (7 anchor),
15/19610/13339: 498 (210 anchor)). CASPIAN
MONSTER-POINT −29.0 m again; EXTREMES/SEAM/SENTINEL
PASS again; Hyderabad control 508.0 m unchanged. This
run also carried the Microsoft parallel-download
in-game proof (see MICROSOFT_SPEED_FIX.md).

## Everest owner test (owner log 2026-11-18 21:38–21:40)

World spawned AT the summit (lat 27.9881, lon 86.9250):
spawn classify `8729.1m`, player login Y=2023, world
spawn Y=2024. `/terracheck` EXTREMES: Everest
`elev=8731.5 m dryLandY=2023` — PASS; SENTINEL samples
8718–8746 m; Caspian monster-point still −29.0 m
(session repairs 1,545, anchor 730). Note: the median
stage repaired 256 px in each of the two Everest-area
tiles 15/24295/13727 and 15/24296/13727 (0 by anchor) —
implausible/seam pixels inside steep tiles; the peak
itself was untouched (all summit readings 8.63–8.75k).
One unrelated warning: a single OutOfMemoryError in a
lighting worker (21:39:13) during mass chunk generation;
the game continued and saved/quit cleanly.

## Owner run sheet

1. `gradle jarJar`; NEW world.
2. `/terracheck`, wait ~1 min, `/terracheck` again.
3. (Optional A/B) `/terra heights guard off`, restart
   game, `/terracheck`; then `on`, restart, `/terracheck`.
4. (Optional eyes) `/tpll 31.5 35.55` — Dead Sea look.
5. Send `latest.log`.
