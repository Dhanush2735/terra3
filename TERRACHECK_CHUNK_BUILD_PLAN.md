# /terracheck CHUNK BUILD CHECK — how was this chunk built? (owner idea, 2026-11-20)

Owner idea: "use terracheck to check chunks how it is being build."

## The hard truth this design is built on
A saved Minecraft chunk does **not** remember how it was built.
No source, no timestamp, no "coastline was late" flag is stored in
the chunk. Today's /terracheck can only say how the world *would*
build the player's position **now** (biome rules, elevation tile in
memory) — not how the chunk under your feet **was** built. That gap
is exactly why NW(20)/NW(23) needed offline Python save audits.

So the check needs two halves:

## Part A — FORENSIC check (works on ANY chunk, incl. old worlds)
Re-derive the chunk's birth certificate from its body, the same math
that was proven offline in the NW(22)/NW(23) audits:
1. **Height fingerprint:** sample the chunk's saved surface
   (WORLD_SURFACE heightmap, stride-sampled, water/structure columns
   handled carefully) and compare against what the CURRENT rules
   produce for the same columns (`classify().surfaceY`).
   Report median offset + spread. Verdicts:
   - matches current book (offset ≈ 0) → built by this build;
   - offset ≈ +2.5…+5 m with the wrong-book shape (NW(23) Juhu
     clusters +2.5…+3.0, Gandikota lid +5.3 vs Mapterhorn but
     +0.35 vs Terrarium) → **OLD-BOOK / OLD-JAR CHUNK**;
   - matches neither → fallback/fossil chunk (suspect).
2. **Lid / seam test:** chunk median surface vs its 4 neighbours'
   medians. A chunk sitting metres above all neighbours with flat
   interior = the NW(20)/NW(23) "teleported chunk elevated" lid.
3. **Shore audit (coastal chunks):** coastline classification NOW
   (OCEAN/LAND) vs the water actually in the world. Chunk near sea
   level whose shore follows the ~5-block DEM-pixel fingerprint
   instead of the smooth coastline, or map says OCEAN but world is
   dry → **GUESSED-SHORE SUSPECT** (the Juhu NW(24) failure mode:
   COAST WAIT expired before the coastline batch landed).
4. **Biome/ground census:** all 256 columns — saved biome vs fresh
   rules, top block vs expected ground family. Catches the Juhu
   inland-desert chunk (bare+wet → desert bug) and NW(22)-class
   fossil biomes as a per-chunk mismatch count, not just one point.
Only already-loaded chunks are read (`getChunkNow`, never
force-load); runs async like the elevation-source report; detail
in the log under [TerraCheck], verdict line in chat.

## Part B — BUILD LEDGER (exact truth, chunks built after it ships)
Forensics is inference; a ledger is memory. While a chunk is being
generated, record one line per chunk:
- chunk pos + time + build id;
- elevation tiles used and their sources (live / disk / parent
  fallback), whether the elevation readiness gate expired
  (`ELEV NOT READY`);
- coastline: tile ready or COAST WAIT expired, segment count,
  shore witness used (coastline line vs ESA/elevation guess);
- ESA/biome: loaded in time or BIOME WAIT expired;
- ocean depth source per the sea/ocean plan (grid vs Terrarium).
Kept in memory AND appended to a sidecar file in the world folder
(`terra_chunk_builds.csv`) so it survives restarts. /terracheck
reads the ledger first ("this chunk was built at …, coastline
arrived late, shore guessed") and falls back to Part A forensics
when no ledger entry exists (older chunks/worlds).

## Where it lives
Plain `/terracheck` only (owner rule: no subcommands). New
"CHUNK BUILD" section after ELEVATION SOURCE: verdict for the
player's chunk + 3×3 neighbourhood lid summary in chat, full
per-chunk table in the log.

## What it would have caught automatically
| Past problem | Which part catches it |
|---|---|
| NW(23) Gandikota lid (+5 m, Terrarium-born in a Mapterhorn world) | A1 fingerprint + A2 lid test |
| NW(23) Juhu Terrarium-born cluster | A1 (offset +2.5…+3.0) |
| NW(24) Juhu guessed-shore chunks | A3 (and B exactly, if ledger existed) |
| Juhu inland desert (bare+wet) | A4 biome census |
| NW(20) raised fossil chunk | A1 + A2 |
| Old jar running (NW(23) root cause) | B ledger build id + A1 |

## Honest limits
- Part A is strong inference, not memory: heavy terraforming by
  structures/roads, or a chunk the player edited, can blur the
  fingerprint. Verdicts will say SUSPECT, not guilty.
- Part B can only tell the truth about chunks built after it ships.
- Ocean-floor provenance under the sea/ocean Terrarium plan is
  covered by the ledger's depth-source field; Part A cannot split
  grid-vs-Terrarium floors reliably from heights alone.

## Status
**Part A IMPLEMENTED 2026-11-20** (owner: "implement /terracheck
chunk build check") in `commands/TerraCheckCommand.java`:
`chunkBuildHere` + `analyseChunkBuild`, wired into plain
`/terracheck` after the elevation-source section. Snapshot
(player chunk + 8 neighbours: WORLD_SURFACE / OCEAN_FLOOR
heightmaps, water, saved biome, ground block) is taken on the
server thread via `getChunkNow` (never force-loads); the
classification (fresh `classify()` per column, biome census via
`getBiomeForLocation`) runs async. Reports per-chunk log lines
`CHUNK BUILD [cx,cz]: landSamples=… medianDiff=… within3=…%
savedMedianY=… freshOceanCols=… wet=… dry=…`, a shore-witness
line (coastline tile LOADED / segments / centre side), BIOME DIFF
examples, and chat verdicts (fingerprint + lid + shore/biome +
final `=> CHUNK BUILD VERDICT: OK / CHECK — …`). JDK17 parse
clean (only expected missing-classpath errors); every API called
was grep-verified against the real signatures. NOT yet in-game
proven — owner must `gradle jarJar`, verify the new jar is in
mods, then run plain `/terracheck` in: (1) a normal inland chunk
(expect OK), (2) the NW(23) lid chunk area if that world still
exists (expect RAISED + LID SUSPECT), (3) Juhu beach (expect the
shore/biome lines to speak), and send latest.log.
Part B (build ledger) NOT implemented — it belongs with the next
generator change (coastline fix + sea/ocean Terrarium plan).

## IN-GAME PROOF — NW(25), 2026-11-20 (owner log analysed)
Owner built the jar, made a FRESH world "New World (25)"
(Gandikota spawn), /tpll to Juhu, ran plain /terracheck 3 times
(Juhu chunks 588948,-119470 twice, 588947,-119469 once).
- **Chunk build check WORKS in-game:** CHUNK BUILD lines for all
  9 chunks per run, shore-witness line, and
  `=> CHUNK BUILD VERDICT: OK` in chat+log, zero errors.
- Calibration learned: healthy chunks read **medianDiff = −1.0 m**
  uniformly (saved ground sits exactly 1 below classify
  surfaceY), 82–100% within 3 m, saved median == neighbours
  (+0.0 m). The +3 m RAISED threshold is safely above this bias.
- Elevation source verdict OK again (Mapterhorn only, parent
  fallback 0, no Terrarium traffic).
- **Limitation proven live (important):** the checked Juhu chunk
  is biome desert 256/256, top sand 250/256 — yet the biome
  census says "match fresh rules 256/256", because fresh rules
  THEMSELVES still produce desert at 2,111 mm/yr (the known
  rain-gate bug). The census detects old/mismatched chunks; it
  cannot detect a wrong rule. The point biome check still warns
  ("desert but rain is 2111 mm/yr"). After the desert rain-gate
  fix, chunks like these SHOULD flip to FOSSIL SUSPECT — that
  flip will be the fix's proof.
- NOT yet exercised: RAISED/LID verdicts (needs an old-book chunk,
  e.g. the NW(23) lid area) and the shore audit's wet/dry ocean
  columns (all checked NW(25) chunks had freshOceanCols=0 —
  owner stood on land/beach chunks; test standing in the sea).
