# Overpass cures 1 + 2 — implemented 2026-11-21 (owner chose both)

Problem (from juhu_download_test/RESULTS.md): the slow door is
Overpass *server think* — 22–34 s before the first byte at Juhu —
not download speed. The cure is to never sit waiting on one
server. Owner picked Cure 1 + Cure 2; Cure 3 (smaller queries)
held in reserve.

## Cure 1 — buildings race their Overpass endpoints
`generator/osm/OsmStructureIndex.java` (JDK17 parse-clean)

- New `raceStructQuery()` + `attemptStructEndpoint()`: hedged
  race mirroring the water index's proven race. Candidates
  ordered soonest-token first (last-good wins ties, as before);
  first launches immediately, next joins after 6 s of silence,
  fast failure advances at once, first HTTP 200 wins, losers
  disconnected. Attempts run on a new `STRUCT_RACE_POOL`
  (never dlPool). Winner remembered in `endpointIdx`.
- Both sequential loops replaced: single-tile `fetchTile`
  (was up to 3 × 25 s in series) and the batch fallback
  (was up to 3 × 75 s in series).
- Rules preserved: api.osm.org still tried FIRST (unchanged);
  limiter tokens/in-flight per endpoint; 429→30 s, 504→10 s,
  other codes→8 s, failure→10 s penalties; a 200 with an
  unparseable body is still a busy page (winner penalised
  15 s, survivors re-raced); failure/caching tails unchanged.
- Log marker: `[TerraStruct] OSM FETCHED [...] (overpass race)`.

What it cannot do: beat a healthy server's own think time
(Mumbai buildings ≈30 s on the answering server). It removes
the dead-endpoint burn (sandbox: Kumi silent 45 s+) and always
takes the fastest healthy answer.

## Cure 2 — the fast OSM API door races Overpass for the coast
`generator/water/OsmWaterIndex.java` (JDK17 parse-clean)

- `fetchTile` now runs two racers: the Overpass race on
  RACE_POOL, and — after a 6 s hedge (healthy-fast Overpass is
  never shadowed) — the official-OSM-API coastline fetch.
  First usable tile wins; overall deadline 45 s.
- API win → coastline-only tile cached *immediately*
  (`OSM API coastline [...]: ... (raced Overpass and won)`),
  so the shared 12 s COAST WAIT sees it; the still-running
  Overpass race is watched by
  `upgradeTileWhenOverpassLands` and swaps in the full tile
  (rivers/lakes) when it lands (`OSM API coastline upgraded
  [...]`), including disk-cache overwrite.
- Before: the API door was only tried after Overpass had
  fully failed, stacking ~4 s on top of a 23–33 s wait.
- Cancelled race losers never count as API failures (no
  false `osmApiDownUntil` trips); all old penalties/pauses,
  the 300 ms API gate, and failure accounting unchanged.

## Owner test
1. Rebuild (`gradle jarJar`), verify the jar in mods.
2. Fresh coast at Juhu (delete the `osm_water`/`osm_structures`
   disk cache or use an unvisited stretch for a cold test).
3. Watch the log for the three markers above; shore should be
   right from the first chunks, and building data should stop
   showing the one-server-at-a-time stall pattern.
4. FAIL = still slow in the same way → send `latest.log`;
   next lever is Cure 3 (split tiles into smaller queries).
