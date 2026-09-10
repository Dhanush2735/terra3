# Bridge System — Full Pass

All bridge defects found via real Hyderabad Metro OSM data (runs 6-7 +
way-level reports), solved as one system in `OsmStructureGenerator.java`.
Every rule self-checks with a machine-parseable log line.

## The evidence doctrine

OSM tags are stacking/drawing hints, not physics. A bridge's real geometry
comes from what it TOUCHES and what CROSSES it:

| situation | height source | log line |
|---|---|---|
| bridge with `layer` | layer × 8 | — |
| unlayered bridge, ends share nodes with elevated bridges | deck interpolated between anchor rises | `CONNECTOR BRIDGE: <id> (no layer) anchored 16b -> 32b …` |
| segment anchored at BOTH ends (split viaduct) | full rise end-to-end, no dip, even <10 m segments | `BRIDGE CONTINUATION: <id> anchored at both ends — no mid-viaduct dip` |
| segment anchored at ONE end | ramp only at the free end | `BRIDGE CONTINUATION: <id> anchored at start/end …` |
| genuine free end | ramp to ground (unchanged) | — |

Anchor = endpoint node matching (±0.2 m) ANY node of another bridge in the
area. Layered neighbours anchor at layer×8; unlayered at default 8 — so
ordinary road bridges split into segments stay level too. Cached per way.

## Crossing interactions (who may delete what)

1. **Corridor pre-clear** (`placeBridge`): skips other bridges' deck bands
   (rise ±2); with a higher bridge crossing, STONE_BRICKS (its pillars)
   are also spared. Log: `BRIDGE CROSSING: <a> (layer=x) and <b> (layer=y)
   share a column — decks mutually protected`.
2. **`clearAboveDeck`**: headroom carving skips guarded bands — the deck
   overhead is the other bridge, not obstruction.
3. **Pillars** (`isPillarBlocked`): a pillar column is refused when a LOWER
   deck crosses it — the pier shifts to the next spacing slot, like real
   interchange engineering. (Guard bands for unlayered crossers use their
   interpolated local rise, not a phantom +8.)
4. Endpoint merges: pre-existing `LAYER_END_SEPARATION_M` ramp adjustment
   retained, now applied only to unanchored ends.

## Verified against real ways

- 611277293 / 588113196 (Line 3 blue, layer=2, split segments): level deck
  across segment joints, protected under Line 2.
- 1188687060 / 611277268 (Line 2 green, layer=4): crosses blue intact.
- 850869297 (siding, NO layer): first node in 588113196's way, last node
  in 611277268's way — verified in raw API data; deck climbs 16b→32b.
- 635428057 / 747389070 (station boxes): snap to their viaduct's deck
  (STATION ALIGN, previous pass).

## What to expect in the log

`CONNECTOR BRIDGE`, `BRIDGE CONTINUATION`, `BRIDGE CROSSING`,
`STATION ALIGN`, `GROUND ALIGN` — each fires once per structure/pair.

Known residuals (cosmetic, report if seen): upper-deck pillar may stand
directly beside (not on) a lower deck; ramp gradient on connectors is
linear, not eased.
