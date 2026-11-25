# Terrarium (old book) — SEA AND OCEAN ONLY (owner rule, 2026-11-20)

## OWNER RULE — DO NOT FORGET, DO NOT DILUTE
> "only use old book for sea and ocean only"

- Old book = **Terrarium** (`s3.amazonaws.com/elevation-tiles-prod/terrarium`).
- New book = **Mapterhorn** — the elevation source for **everything else**.
- Terrarium may be used **only** where the boundary says **SEA or OCEAN**.
- Terrarium must **never** be used for: land, coast/beach cells, rivers,
  streams, canals, lakes, ponds, reservoirs, wetlands, inland water of
  any kind, or as a general fallback when another source fails.
- If the sea/ocean boundary answer is **UNKNOWN** (boundary data not
  loaded yet), Terrarium must **NOT** be used — fall back to the
  current safe behaviour (Mapterhorn + bundled `bathy.gz` grid).
  Guessing "probably ocean" and using Terrarium is forbidden.

This reverses the 2026-11-20 single-source removal ONLY inside
sea/ocean water. On land the single-source rule still stands:
one book (Mapterhorn), no mixing, no per-tile fallback to Terrarium.

## Why this is safe (and why the old hybrid was not)
The old hybrid mixed books **on land, tile by tile** (Mapterhorn-first,
Terrarium fallback) — that caused elevated/teleported chunks, floating
river water, and slow double-downloaded coasts (NW22/NW23 evidence).
Terrarium's real strength was always the oceans: it is the only book
with true bathymetry (Mariana ≈ −10,390 m at z8 in the heightmap race),
while Mapterhorn sea is flat 0.0 m and the bundled `bathy.gz` grid is
0.5°-coarse (~55 km cells — smooth, detail-free seafloor).
Restricting Terrarium to sea/ocean keeps its one strength and removes
every proven land disease.

## Boundary source — important correction to the first idea
Owner reference: https://wiki.openstreetmap.org/wiki/OpenStreetMap_Carto/Areas
("select only sea and ocean").

That wiki page is a **rendering legend**, not boundary data. Checking it:
- It lists "Body of water (ocean, sea, pond, river)" under the tags
  `natural=water` / `waterway=riverbank` / `landuse=reservoir` etc.
  Using those tags raw would include ponds, rivers and lakes — which
  the owner rule **forbids**.
- In OSM itself, oceans/seas are **not** stored as closed
  `natural=water` polygons. `place=sea` / `place=ocean` are mostly
  label nodes, not usable boundary polygons. Querying Overpass for
  them would not give a reliable sea mask.
- What OSM Carto actually paints as ocean is derived from
  **`natural=coastline`** ways (processed coastline / water polygons).

**The mod already has exactly that boundary:** `OsmWaterIndex`
downloads `natural=coastline` ways and `classifyCoastline(lat,lon)`
returns `LAND / COASTLINE / OCEAN / UNKNOWN` per point
(signed-distance side test, verified correct against real coastline
way 1459343480: offshore 300 m → OCEAN, inland 300 m → LAND).
So the boundary for this plan = `CoastSide.OCEAN` from the existing
coastline index. No new boundary download is needed; lakes/rivers
stay with the existing river/lake engine and never see Terrarium.

## Proposed implementation (NOT yet applied — awaiting owner go-ahead)
1. In `TerraDensityFunctions.fetchBathymetry(lat, lon)` (today it only
   reads the bundled `bathy.gz` grid): first ask
   `OsmWaterIndex.classifyCoastlineMemOnly` (memory-only, never blocks).
   - `OCEAN` → sample Terrarium bathymetry (coarse zooms, where its
     ocean depths actually live — z15 sea pixels are noisy 0; the old
     chain used z8/z7/z6/z5/z3) and use it as the ocean depth.
   - anything else (`LAND`, `COASTLINE`, `UNKNOWN`, index disabled)
     → exactly today's behaviour (bundled grid only).
2. Hard safety clamps on every Terrarium ocean value:
   - accept only depths `< −1.0 m` (a Terrarium value ≥ −1 can never
     raise land or lift the seabed above the current answer);
   - run it through the existing spike-guard logic — Terrarium's one
     known disease is rare monster pixels on flat water
     (Dead Sea −5,227 m, Caspian −5,535 m in the heightmap race);
   - blend across a narrow band near the coastline so the
     Mapterhorn-0 → Terrarium-depth transition cannot cliff.
3. Keep Terrarium tiles in a **separate disk cache** (never the
   Mapterhorn cache, never readable as terrain tiles), fetched only
   for ocean points, and prefetchable at /tpll warm-up like other tiles.
4. `/terracheck` elevation-source report must say so honestly:
   land tile = mapterhorn; ocean depth = terrarium (sea/ocean only)
   is OK by design, not a red flag.

## Does this solve the Juhu beach problem? NO (owner asked 2026-11-20)
The Juhu problem is **not** a depth problem. From NW(24): the coastline
data itself arrived ~66 s after /tpll (Overpass de 502, 5× COAST WAIT
3 s expiries, SHORE SMOOTH fallback) — early chunks classified their
shore from a *guessed* shoreline, and that guess is baked into the
saved chunks. This plan makes ocean floor deeper/more detailed
**behind** that same coastline boundary; where the boundary is late
or UNKNOWN, the gate (correctly) keeps Terrarium out, so Terrarium
can do nothing for the beach. Terrarium near-shore values at Juhu
are also unusable for deciding the shore (z15 offshore = 0.00 m;
coarse zooms +1.9…+26 m land-contaminated, first below −1 m only
~1.6 km offshore — COASTLINE_MISSING_SEA.md). What actually fixes
Juhu is the separate, already-proposed coastline fix (race all
Overpass endpoints in parallel + one longer shared bounded coast
wait for near-sea-level chunks, so no chunk classifies on a guessed
shoreline) plus the desert rain-gate fix for the inland biome.
Order matters: fix the coastline first; this plan then improves
the sea floor in front of the fixed beach (fresh chunks only).

## Open points for the owner
- Inland seas (Caspian, Dead Sea): they are `natural=water` lakes in
  OSM, **not** coastline ocean — under the strict rule they stay on
  the bundled grid (no Terrarium). Owner to confirm this is wanted.
- Proof targets after implementation (fresh chunks only — depths are
  baked into saved chunks): Mariana Trench (deep, was grid-smoothed),
  Juhu offshore (must stay a smooth shore; beach unchanged), any
  mid-Atlantic ridge point; plus a land control (Gandikota) proving
  zero Terrarium traffic on land.

## ASSISTANT VERDICT (2026-11-20, asked by owner): APPROVE — with the safeguards in this plan
Short version: this is the one job the old book is genuinely better
at, and the sea/ocean-only boundary removes every disease the old
hybrid caused on land. Do it, but only behind the gate and clamps
listed above (OCEAN-only, UNKNOWN = no Terrarium, accept only
< −1 m, spike-guard, blend band, coarse zooms not z15, separate
cache). Strictly better than today's bundled-grid-only seafloor;
not a return to the old hybrid. Full reasoning was given to the
owner in chat on 2026-11-20.

## Owner variant proposed 2026-11-20: switch by Y level instead of by map
Owner idea: "use new book for Y level 65 and more, use old book for
Y level 64 and less." Evaluated, NOT approved as written:
- Sea level is Y=63, so Y64 = +1 m and Y65 = +2 m. The switch line
  therefore runs through the middle of every beach — Juhu's own
  sand is measured Y63–64 (NW24), so the beach itself would be
  built from the old book. That breaks the owner's own rule
  (beach/coast = new book only) at exactly the old book's weakest
  place (near-shore Terrarium reads 0.0 m in the sea and jumps
  metres just offshore; < −1 m only ~1.6 km out).
- Y alone cannot tell sea from low land: land at Y<=64 includes
  beaches, polders below sea level (Flevoland), deltas, tidal
  flats, and dry below-sea basins (Death Valley, Dead Sea shore).
  All of that land would wrongly go to the old book.
- The two books disagree by metres where they meet (proven:
  +2.5–3.0 m at Juhu, +5.3 m Gandikota lid), so a hard switch at
  the +1/+2 m contour would draw a visible step/cliff along the
  waterline of every coast — the worst possible place for a seam.
- What the idea gets right: it needs no coastline map, so no
  waiting, and deep ocean would get real old-book depths.
Kept alternative (same spirit, safe): new book decides first;
the old book may only deepen a point when the coastline map says
OCEAN (the gate already in this plan). If the owner wants a
Y-only rule with no map at all, the safe version is: old book
only where the new book reads flat 0 AND the bundled grid says
deep (< −10 m) — i.e. confirmed deep ocean far from any shore —
never at Y63–64 beach level. Awaiting owner choice. No code.

## Owner decision 2026-11-20: line STANDS, add a transition gap
Owner: "the line is in the correct place. there should some
transition gap." Accepted — with a blend zone the Y rule becomes
workable. Agreed design (assistant proposal built on owner's
line; owner to confirm gap width / say "start"):
- Owner's 64/65 line (elev +1/+2 m, sea level Y=63) becomes the
  MIDDLE of the transition gap, not a hard cut.
- Gap: elev −1 m to +5 m (Y62–Y68). Below −1 m: 100% old book.
  Above +5 m: 100% new book. Inside the gap: smooth mix
  (smoothstep on the NEW book's elevation only, so the mix ratio
  itself can never jump — one driver, no circle).
- Worked example (Juhu, books disagree +2.5–3 m): columns climb
  gradually through the gap instead of stepping 64 -> 67 at one
  contour; the waterline step the hard cut would draw is spread
  over the whole 6 m band.
- Guards that stay mandatory: old-book sample spike-guarded and
  its pull inside the gap capped (a bad old pixel can only nudge,
  never teleport, a beach column); old fetch only fires when the
  new book reads below +5 m, so inland/high land never touches
  the old book at all (fast, and land stays new-book pure almost
  everywhere).
- Honest cost the owner has accepted by keeping the line:
  beaches and low land (Y63–64 sand, polders, deltas) are built
  from the old book / the mix, not pure new book. Deep sea gets
  real old-book depth with no coastline-map waiting — the Y rule
  needs no map, which was the idea's original strength.
Status: design agreed in principle, gap width (6 m band above)
and "start" still to be confirmed by owner. No code yet.

## SUPERSEDED 2026-11-20 — owner chose the Y rule and said "start"
The map-gate design in this file was NOT implemented. The owner
instead ordered the Y rule (new book >= Y65, old book <= Y64,
transition gap -1..+5 m), which also supersedes the "Terrarium
only for sea and ocean" gate recorded at the top of this file:
under the Y rule the boundary is height, not the coastline map,
and beaches/low land at Y63-64 deliberately come from the old
book/mix. Implemented in TerraDensityFunctions (+ /terracheck
reporting); record: Y_RULE_IMPLEMENTED.md.

## Status
PLAN ONLY. No code changed by this document.
Related pending items (unchanged): Juhu coastline speed fix and the
desert rain-gate fix are still awaiting owner go-ahead.
