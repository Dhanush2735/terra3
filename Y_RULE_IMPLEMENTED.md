# Owner Y rule — IMPLEMENTED 2026-11-20 (owner: "start implementing")

Owner decision (supersedes the same-day "Terrarium only for sea/ocean"
map-gate rule, which was never implemented): **new book (Mapterhorn)
for Y65 and above, old book (Terrarium) for Y64 and below, with a
transition gap around the line.** The owner confirmed the line stays
("the line is in the correct place") after the assistant's objection,
accepting that beaches/low land at Y63–64 are built from the old
book / the mix.

## The rule as coded (TerraDensityFunctions)
Sea level Y63, so in metres (Y = 63 + elev for dry land here):
- new-book elev **>= +5 m** (Y68+): 100% new book. The old book is
  not even fetched — high/inland land never touches it.
- new-book elev **<= −1 m** (Y62−): 100% old book.
- **−1..+5 m gap**: smoothstep mix driven by the NEW book's elevation
  only (one driver, so the ratio can never jump). The owner's 64/65
  line (+1/+2 m) is the middle of the gap: 26% new at +1 m, 50% at
  +2 m. Inside the gap the old book may raise a column at most
  +10 m over the new book (beach protection); deepening is uncapped.

## Old book sampler (getOldElevation)
- Terrain zoom sample outside −1..+1 m → that surface (low land, or
  below-sea surface like Dead Sea shore).
- Terrain zoom reads flat ~0 over sea → depth from the Terrarium
  coarse ladder {8,7,6,5,3}, first sample < −1 m wins.
- Flat ~0 and no depth → NaN ("no opinion"): generation keeps the
  new book alone and may still use the bundled bathy grid. The old
  book is NOT a fallback for missing new-book tiles (NaN stays on
  the old ladder: parent zoom / grid / −50).

## Plumbing
- `fetchOldTile`: HTTPS GET from
  `s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png`
  (lowercase "terrarium" — capital 404s, caught by the live harness).
- Tiles validated by the same HeightSpikeGuard as new-book tiles;
  for fine zooms the guard's zoom-anchor anchors old pixels to the
  NEW book's coarse parent (kills Terrarium flat-water monsters,
  Dead Sea −5,227 / Caspian Sea −5,535, before they enter the blend).
- Separate memory caches + disk folder `tiles/Terrarium-y-v1/` —
  books can only meet inside the blend, never inside a cache
  (NW23 disk-poison lesson). Legacy archives still never read.
- Warm-up: `/tpll` prefetch also fires old terrain+z8 tiles for the
  destination; the generation readiness gate (`awaitTilesForChunk`,
  same shared 15 s deadline) also joins old fetches, but only for
  chunks whose centre reads below +5 m.
- Log lines: `ELEV SRC OLD {z/x/y}: Terrarium (low zone, Y rule)`
  (first 10), `ELEV LOW [...]` (first 5 full-old samples).
- `/terracheck` ELEVATION SOURCE now reports both books + low-zone
  sample counters; verdict = "books split by height as designed";
  unknown-source tiles are still the red flag. Legacy-tile counter
  excludes the active Terrarium-y-v1 folder.

## Sandbox proof
- JDK17 parse-clean on TerraDensityFunctions, TerraCheckCommand,
  TerraConfig (only expected missing-classpath errors).
- Live harness against the real archive (same math as the code):
  Juhu beach land old z15 = +4.0 m (new book +1.2 m — the known
  +2.5–3 m disagreement the gap must spread); Juhu offshore 1 km:
  no depth opinion (matches "below −1 m only ~1.6 km out");
  offshore 5 km: −3.6 m; Mariana z8: −9,789.7 m; Dead Sea: −405.3 m.
  Blend checks: new +1 / old +3.5 → +2.85 m; new 0 / old −10,390 →
  −9,620 m; midpoint +2 m → exactly 50/50.

## Owner test (after gradle jarJar, FRESH world)
1. s3.amazonaws.com elevation traffic is now EXPECTED (old book,
   low zone only) — it is no longer proof of an old jar.
2. Juhu (fresh chunks): beach should sit a little higher/smoother
   than NW25 (blend pulls beach toward old-book +4 m), sea floor
   deepens away from shore; no cliff at the waterline. Run
   `/terracheck`: ELEVATION SOURCE verdict should say "books split
   by height as designed", with old-book tiles counted and blended
   samples > 0. CHUNK BUILD verdict should still be OK on chunks
   born under this jar.
3. Gandikota (264 m): expect ZERO old-book samples in /terracheck
   low-zone counters from local generation (centre is far above
   +5 m; some prefetch downloads at /tpll are by design).
4. Deep-sea proof: stand in deep ocean (e.g. /tpll toward Mariana
   11.35,142.59) — sea floor far below the old flat −5 m.
5. Send latest.log.

## IN-GAME PROOF — NW(26), 2026-11-21 (owner log analysed)
New jar confirmed running (new log lines present). World "New World
(26)", Gandikota spawn, /tpll Juhu, /terracheck x3 (Gandikota spawn
chunk, Juhu land chunk 588947,-119469, Juhu water chunk
588945,-119466 — the first water chunk ever checked).
- Y RULE LIVE: `ELEV SRC OLD ...: Terrarium (low zone, Y rule)`
  lines; at Juhu the ELEVATION SOURCE report reads old book live
  24→34 tiles, failed 0, **blended samples 3.14M → 5.31M**, fully-old
  samples 12→22 (Dead Sea probes via ELEV LOW: old book −412.0 m,
  −400.6 m, −190.9 m — the full-old branch works in-game).
- Gandikota behaves as designed: old tiles are *downloaded* by the
  /tpll prefetch (10 tiles) but **samples = 0 old / 0 blended** at
  267 m — high land downloads a little, uses nothing. (Minor
  accepted waste; gating prefetch by centre height is a possible
  future trim, not a bug.)
- Heights healthy everywhere: every CHUNK BUILD line medianDiff
  −1.0 m, 100% within 3 m, saved median == neighbours (+0.0 m) —
  no raised chunk, no lid, no waterline step at Juhu. Verdicts:
  ELEVATION SOURCE "books split by height as designed" at all 3
  checks; CHUNK BUILD OK at both Juhu chunks.
- Juhu beach now reads elev 3.1 m (was 1.1 m new-book-only in
  NW24/25 — the blend pulling toward old-book +4 m, as intended);
  saved beach median Y65, top sand 256/256 on the land chunk.
- First shore-audit exercise: water chunk freshOceanCols=175,
  wet 89 / dry 86, ocean floor median diff 0.0/−1.0 m — verdict OK
  but **one column below the alarm line** (dry >= half of 175
  would flag). Half the map-ocean columns are dry in the saved
  world; SHORE test on the same chunk: beachBiome 66/81, shoreTop
  60/81 (CHECK). Owner's eyes on the actual waterline are the
  deciding test here.
- Still open, unchanged: desert bug live at Juhu (biome desert
  254/256, rain 2,111 mm/yr — rain-gate fix still pending);
  Gandikota spawn chunks are biome fossils (ESA BIOME WAIT expired
  loaded=false at spawn → saved badlands vs fresh savanna 241/256;
  heights fine — the known ESA-late spawn weakness, not elevation);
  server fell 98.5 s behind during Juhu generation; EXTREMES
  probes still NOT LOADED for land points and Mariana still reads
  the bundled grid (−7,930 m) because that probe samples the grid
  directly, bypassing the Y-rule sampler (in-world deep-sea floor
  still untested by eye).

## Not done / unchanged
- Juhu coastline-timing fix (Overpass parallel race + longer shared
  coast wait) and desert rain-gate fix: still awaiting owner
  go-ahead. This Y rule does not wait for the coastline map, so
  early-chunk shore guessing is reduced, not eliminated (classify
  still uses coastline/ESA/elevation witnesses as before).
- Part B chunk-build ledger: still pending.
