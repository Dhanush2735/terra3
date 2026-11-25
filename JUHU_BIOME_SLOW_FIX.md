# JUHU SLOW GENERATION — OWNER SUSPECTS BIOMES (2026-11-21)

Owner: "juhu slow generation may caused by biomes and ground blocks"

## Verdict: HALF RIGHT — and the right half is big

- **Biome NAMES (`assignBiomes`): YES, guilty.** Proven in code,
  sized by bench. Fixed (see below).
- **Ground blocks (`pLand`/`gbt` painter): mostly innocent.**
  256 columns per chunk, all lookups cached (climate per ~1 km,
  ESA per pixel, elevation per ~11 m). Small next to the biome
  loop. No change made.
- Not covered by this fix: buildings/structures at Mumbai
  (`OsmStructureIndex` still tries endpoints one at a time) and
  the terrain grid itself. If Juhu is still slow after this,
  those are next — in that order.

## The bug (in `TerraChunkGenerator.assignBiomes`)

The world is 4,064 blocks tall (−2032..2032, `earth_type.json`)
= **254 chunk sections**. A chunk is only **4×4 biome quarts**
wide, and a quart's biome depends only on its (x,z) column.

The old loop was:

    for each of 254 sections
      for each of 16 quarts
        classify(column)            // projection + elevation
                                    // + coastline scan
        isShoreCellPublic(column)   // 17 MORE classify() calls
        getBiomeForLocation(...)

= 254 × 16 × ~18 ≈ **73,000 `classify()` calls per chunk**,
all repeating the same 16 answers 254 times.

Each `classify()` runs `OsmWaterIndex.classifyCoastline`:
9 tile lookups + a scan of every coastline segment in memory.
At Juhu the NW(27) log counted **1,771 segments** around, and
the "near" filter passes anything within 0.5° (~55 km), so
nearly all of them get a signed-distance (cos + sqrt) per call.

## Bench (sandbox, replicated segment maths, JDK 17)

- One coastline scan: **~78 µs**
- Old biome loop: 73,152 scans → **~5.7 s per chunk** on
  coastline maths *alone* (before projection + elevation cost
  per classify). Even if the real world is 3–5× kinder than the
  bench, this is ~1 s+ per coastal chunk vs ~0.3 s for the
  *entire* terrain height grid (4,096 scans).
- After the fix: 16 quarts × 18 scans = 288 → **~0.02 s**.

This also explains why the coast is the worst place for it:
inland chunks have no coastline segments loaded, so the scan
is cheap there. Juhu pays the maximum on every chunk.

## The fix (`TerraChunkGenerator.java`, JDK17 parse-clean)

1. `assignBiomes`: the 16 quart columns are classified **once**
   (biome, shore, ESA class, cave-zone flag, surface Y stored),
   then all 254 sections are only *painted* from that table.
   Cave biome bands still vary by Y exactly as before.
   Results are identical per column; one side benefit is one
   consistent snapshot per chunk (a data tile landing mid-paint
   can no longer stripe biomes vertically).
2. New throttled log `BIOME TIME [x,z]: Nms for 254 sections
   (16 columns classified once)` — prints only when a chunk's
   biome step still takes >100 ms (max 50 lines), so the owner
   log can convict or clear this step after the fix.

Untouched on purpose: ESA WAIT (5 s, ground) + ESA BIOME WAIT
(6 s) bounded cold-tile waits — they prevent wrong-biome
fossils (NW(23) badlands chunk) and return instantly when warm.

## Problem recreated outside the game (2026-11-21)

Owner asked "can you recreate the problem?" — done:
`repro_biome_problem/` runs the OLD vs NEW biome loop with the
mod's own compiled projection + coastline code and Juhu's real
segment count (1,771). Old: 0.5–4.5 s of biome work per chunk
(worst: inland chunk just east of the shore, 73,152 classify
calls); new: 2–19 ms. ~240–270× on this step. See
`repro_biome_problem/RESULTS.md`. In-game proof still rules.

## Owner test

1. Rebuild (`gradle jarJar`), verify the new jar is in mods.
2. Fresh world (or fresh, never-generated coast), go to Juhu
   19.0968, 72.8265. Feel: do chunks arrive faster?
3. Send `latest.log`. What to look for:
   - `BIOME TIME` lines should be rare and small. (Before the
     fix there was no such line — compare by feel + the
     "Can't keep up / running behind" warnings: NW(27) hit
     127.7 s behind.)
   - Biomes must look the SAME as before (beach at the shore,
     same names on land). This fix changes speed, not choices.
4. PASS = Juhu coast generates clearly faster AND biomes look
   unchanged. FAIL = still slow → send the log; next suspect is
   Mumbai buildings (`OsmStructureIndex`), then the terrain
   grid's own classify cost.
