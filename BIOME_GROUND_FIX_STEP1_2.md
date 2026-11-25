# Biome/ground fix — Step 1 + Step 2 implemented

Status: **Step 1 PROVEN IN-GAME; Step 2 bench PROVEN IN-GAME**
(owner log, New World (10), Juhu Beach, 2026-11-19 00:21–00:23).
This implements the first two steps from `BIOMES_GROUND_AUDIT.md`.
It does **not** yet change the ground palette, beach rule, BUILT_UP/TUFF
rule, or cold-start ground repaint. Those are later steps, now measurable.

## Step 1 — climate truth fixed

### `Climate.java`
`Climate.Precipitation.apply` now reads channel 1 (`| 1`) of the
interleaved `climate.gz` cells. Channel 0 is temperature °C; channel 1 is
precipitation mm/year. Before this, precipitation and temperature both
read channel 0.

### `TerraBiomeProvider.java`
- `queryData()` now treats dataset precipitation as **annual mm/year**.
  The old `rawPrecip * 12` scaling was removed: it would turn Berlin
  (~691 mm/year) into ~8287 mm/year after the channel fix.
- Temperature is no longer discarded just because it equalled the old
  fake precipitation value. Only impossible temperatures (< −89 or
  > 60 °C) fall back to the latitude estimate.
- Added public bench helpers: `getClimateSample(lat, lon)` and
  `describeAridity(annualPrecipMm)`.

Expected climate samples after the fix (dataset interpolation may move
the exact numbers a little):

| Place | Expected reading |
|---|---|
| Hyderabad | ~800 mm/year, ~27 °C, SUB_HUMID |
| Cairo | ~29 mm/year, ~21 °C, HYPER_ARID |
| Amazon | ~2221 mm/year, ~27 °C, WET |
| Berlin | ~691 mm/year, ~8 °C, SUB_HUMID |

## Step 2 — `/terracheck` BIOME/GROUND proof added

Plain `/terracheck` now also runs a BIOME/GROUND audit for the player's
chunk. It snapshots actual top blocks and biome palette on the server
thread, then analyses off-thread.

New log lines to search for:

- `TEST here CLIMATE Hyderabad ... OK`
- `TEST here CLIMATE Cairo ... OK`
- `TEST here CLIMATE Amazon ... OK`
- `TEST here CLIMATE Berlin ... OK`
- `TEST here CLIMATE VERDICT: PASS ...`
- `TEST here CLIMATE HERE: ...` — climate at the player's chunk centre
- `TEST here BIOME/GROUND chunk=[x,z] ESA(256)=...`
- `TEST here BIOME/GROUND BIOME(256)=...`
- `TEST here BIOME/GROUND TOP(256)=...`
- `TEST here BIOME/GROUND CODES raw=... effective=...`
- `TEST here BIOME/GROUND SUMMARY: esaUnknown=... naturalEvaluated=... mismatch=...`
- `TEST here BIOME/GROUND MISMATCH [x,z] ...` (first 8 only)
- `TEST here BIOME/GROUND VERDICT: ...`

`TerraChunkGenerator` gained three public bench accessors:
`getBiomeProviderPublic()`, `getSurfaceTypeCodePublic(bx, bz)`, and
`getRoughnessPublic(bx, bz)`.

## What this proves / does not prove

Proves, from the owner's next log:

1. Whether the climate channel/unit fix is really in the built jar
   (the four known-place samples must print OK).
2. What ESA thinks the player's chunk is, column by column.
3. What biome the game actually assigned, column by column.
4. What top block is actually on the ground, column by column.
5. How many natural columns disagree with the current expected-ground
   rules, with the first mismatches named.

Does not prove yet:

- Beaches, savanna palette, BARE red-sand, wetland mud, BUILT_UP/TUFF,
  and cold-start repaint are **not fixed** in this step. Expect
  BIOME/GROUND to report CHECK at places where those known problems
  live. That CHECK is the measurement working, not a new failure.
- Already-saved chunks do not change ground because of the climate fix.
  Biome/ground changes apply when chunks are generated or repainted.

## Build round 1 (owner `gradle jarJar`)

First build failed with one error:
`TerraCheckCommand.java:1704: cannot find symbol BlockState`.
Cause: the new Minecraft imports for the BIOME/GROUND section
(BlockState, BlockPos, BuiltInRegistries, ServerLevel, Heightmap)
plus the TerraBiomeProvider import had not persisted in the file
(same-file parallel-edit rollback). All six imports are now added
and marker-grep verified. Rebuild required.

## Owner result (New World (10), Juhu Beach, 2026-11-19)

World spawned at Juhu (19.0968, 72.8265, elev 4.0 m). Two
`/terracheck` runs, chunks [588948,-119467] and [588949,-119468].

**Step 1 — CLIMATE: PASS, in-game proven.**
`Hyderabad 796 mm/yr 27.1C SUB_HUMID OK` ·
`Cairo 28 mm/yr 21.5C HYPER_ARID OK` ·
`Amazon 2236 mm/yr 26.6C WET OK` ·
`Berlin 664 mm/yr 8.6C SUB_HUMID OK` ·
`CLIMATE VERDICT: PASS`. At Juhu itself:
`precip=2111 mm/yr temp=26.4C aridity=WET` — Mumbai reads wet,
as it should. The old temperature-as-rainfall bug is dead.

**Step 2 — bench works, and it caught the beach disease in
numbers.** Run 1 chunk, all 256 columns:
`ESA={BARE=256}`, `BIOME={minecraft:mangrove_swamp=256}`,
`TOP={minecraft:grass_block=256}`, codes `7-bare-hot`,
`mismatch=256/256`, expected red_sand vs actual grass_block.
Three layers, three different answers — none of them sand.

Cause chain, visible in the same log: at generation the
WorldCover tile had not landed (`SURFACE ... wc=UNKNOWN -> 4`),
so the soil fallback (soil=98 = wetland group) painted
mangrove_swamp biome + code-4 grass ground. ESA arrived seconds
later saying BARE. The wrong ground fossilised — the exact
cold-start problem from the audit. Run 2 (one chunk east):
`TOP={grass_block=42, sand=214}` — the OSM `natural=beach`
polygon (Juhu Beach, osmId 151253238) painted real sand over
most of that chunk. So today the beach is rescued by OSM where
mapped, while the base terrain under it is grass.

Also in this log: EXTREMES/SEAM PASS, SENTINEL SAFE, height
guard ON (session repairs grew to 1,039 as the EXTREMES probes
pulled far tiles), Microsoft parallel again (31.6 MB in 12 s),
VIDA/Overture serving, refill WORKS by run 2. No OOM.

**Next (Step 3):** shore/BARE ground rules — ESA BARE on a wet
low coast must become beach sand (biome BEACH, code 9), BARE
must stop defaulting to red_sand, and the UNKNOWN-WorldCover
fallback must not be able to stamp mangrove+grass onto a beach
before ESA lands (cold-start ground repaint or ready-gate).

## Owner run

1. `gradle jarJar`
2. Start a fresh world (or teleport somewhere never generated).
3. Type only `/terracheck`.
4. Send `latest.log`.

First place to run: anywhere. The four CLIMATE sample lines are global
and must PASS everywhere. Then, if time permits, `/tpll 19.0968 72.8265`
(Juhu Beach), wait ~1 minute, and run `/terracheck` again — that gives
the beach measurement for the next step.
