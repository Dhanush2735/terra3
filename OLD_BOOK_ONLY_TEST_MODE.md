# OLD BOOK ONLY — test mode (owner: "once try running with old book only")

Date: 2026-11-21. Implemented + parse-clean (JDK 17, zero syntax
errors). Default is OFF: a normal launch is the owner Y rule,
exactly as NW(26) proved it. Nothing about the Y rule changed.

## How to run the test

1. Rebuild the jar as usual.
2. Launch with this JVM argument added:
   `-Dterra.oldBookOnly=true`
   (Minecraft Launcher → Installations → your profile → Edit →
   More Options → JVM Arguments, append it at the end. A server
   start script can instead set the environment variable
   `TERRA_OLD_BOOK_ONLY=true`.)
3. Use a **fresh world** (or fly to never-generated land): saved
   chunks keep the heights they were born with; this mode only
   decides how NEW chunks get their heights.
4. To go back: remove the argument and restart. No cleanup, no
   leftover state — the flag is read once at startup.

You cannot miss whether it is on: the log prints a loud banner
(`ELEVATION MODE: OLD BOOK ONLY (TEST)`), samples log as
`Elev (OLD BOOK ONLY): ...`, and `/terracheck` prints the mode,
the old-book sample count, and a TEST MODE verdict.

## What "old book only" means in this build

- Every height sample = the old book's terrain-zoom surface,
  accepted as-is (including its flat ~0 m sea), with the old
  coarse bathymetry ladder {8,7,6,5,3} behind it for real depth
  — the same arrangement as the pre-single-source hybrid era.
- The new book is not consulted for heights at all: not sampled,
  not prefetched, not waited on in the chunk readiness gate,
  not used as parent fallback.
- Old tiles are still spike-guarded (monster pixels are real —
  Dead Sea −5,227 m, Caspian −5,535 m in the head-to-head), but
  the guard's zoom-anchor now anchors old tiles to the OLD
  book's own coarse tiles (`coarseOldPixelsForAnchor`). Without
  this, "old book only" would still have quietly mixed new-book
  pixels into repaired spikes. This anchor change applies in
  Y-rule runs too, keeping the blend honest.
- Last resorts where the old book has no answer at all: the
  bundled bathy grid (< −1 m), else −50 — same as the Y rule's
  low zone today.

## Live proof the old book can carry a whole world (sandbox, 2026-11-21)

Terrarium z15 tiles fetched and decoded directly:

| Place | Old book z15 | Note |
|---|---|---|
| Gandikota | **273.5 m** | new book says ~267 m — expect ground a few metres higher |
| Juhu beach | **4.0 m** | same value the Y-rule blend pulls toward |
| Everest | **8,729.7 m** | full mountain range works, not just coasts |
| Dead Sea | **−412.0 m** | below-sea land works |

## What to look at during the test run

- **Gandikota**: heights should sit a few metres ABOVE the
  NW(26) run (old ~273 m vs new ~267 m). `/terracheck` CHUNK
  BUILD should still read healthy (−1 m bias, ~100% within
  3 m) — it compares saved chunks against fresh samples from
  the same (old) book.
- **Juhu**: beach ~4 m from the start (no blend needed), and
  the deep-sea floor is the old book's real depth — this is
  also the first true in-world test of old-book bathymetry
  (Mariana-class depth ~−9.8 km at z8 instead of the bundled
  grid's smoothed −9.2 km).
- **Anything tall/famous** (canyon rims, Everest region):
  pure old-book DEM, spike-guarded against itself.

Known expectations, not bugs: old-book land is Copernicus-like
but not identical to the new book (±5 m class disagreements,
e.g. the Gandikota lid difference from NW(23)); near-shore old
terrain-zoom reads flat 0 until the coarse ladder's depth kicks
in ~1–2 km offshore; desert at Juhu and other biome behaviour
are untouched by this mode.

## Code touch points

- `TerraDensityFunctions`: `OLD_BOOK_ONLY` flag (system property
  / env), early old-only branch in `getElevationInterpolatedRaw`,
  `getOldElevationOnly`, `coarseOldPixelsForAnchor` +
  `validateAndRepairTile(image, key, oldBook)`, old-only skips in
  `prefetchArea` (new-book warm-up) and `awaitTilesForChunk`
  (new-book wait; old wait now unconditional), `oldOnlySamples()`,
  startup banner.
- `TerraCheckCommand`: mode line + sample count + TEST MODE
  verdict branch in the elevation-source section.

## NW(27) attempt (2026-11-21) — flag did NOT take effect

The owner ran a fresh world (Gandikota spawn, /tpll Juhu,
/terracheck both places) intending old-book-only. The log proves
the mode was OFF and the normal Y rule ran instead:

- No `ELEVATION MODE: OLD BOOK ONLY (TEST)` banner at startup.
- No `Elev (OLD BOOK ONLY)` sample lines anywhere.
- `ELEV SRC ...: mapterhorn` all over both areas; /terracheck at
  Juhu: "your height tile 15/23012/14612: mapterhorn", session
  71 live Mapterhorn tiles, old book 30 live / 0 failed, samples
  2 fully old + 3,976,875 blended — textbook Y rule, and the
  verdict printed was the Y-rule verdict, not TEST MODE.
- At Gandikota: 10 live Mapterhorn tiles, old samples 0/0
  (old tiles 11 downloaded by prefetch, unused — as designed).

So either the jar was not rebuilt after the old-only change, or
`-Dterra.oldBookOnly=true` was not added where PrismLauncher
passes JVM arguments (instance Edit -> Settings -> Java -> JVM
arguments). Both failures produce this identical log. Next
attempt: rebuild, add the flag, and BEFORE making a world,
search latest.log for `OLD BOOK ONLY` — the banner appears when
TerraDensityFunctions is constructed (world load). If the banner
is absent with the flag set, the jar is stale.

The run was still useful as another Y-rule/coast-fix data point:
Gandikota CHUNK BUILD all -1.0 m / 100% within 3 m, biomes
savanna 256/256 (the NW(26) fossil did not recur); Juhu beach
3.0 m, saved median Y65, CHUNK BUILD OK, coastline present
(1,771 segs, coastData=true) with ZERO COAST WAIT expiry lines.
Server still fell 127.7 s behind at Juhu (worse than NW(26)'s
98.5 s) — that lag, not download, is now the Juhu slowness.
