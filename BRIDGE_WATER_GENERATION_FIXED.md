# Over-water bridges/piers now generate

## The bug

`generateStructuresFromList` scanned each column for water above solid
ground and set `validCell = false` **before** the bridge loop.

That guard is correct for buildings, roads and landuse (do not pave a
riverbed). It is fatal for the structures whose job is to occupy those
columns. Every OSM way with `bridge=*` over a river/lake — Durgam
Cheruvu, Musi crossings, harbour piers — was skipped entirely. Land
viaducts (Hyderabad Metro) still appeared, which hid the hole.

`placeSingleBlock` never had this guard, so the instant-placement path
could theoretically draw a span the chunk path refused. The two paths
disagreed.

## The fix

1. Record water columns in a separate `waterCell` flag. Do **not**
   wipe `validCell` yet.
2. Place **bridges and piers** on every geo-valid cell, including water.
   Deck height still uses `bridgeYs` (water surface); that is the
   correct datum.
3. *Then* mark water cells invalid so buildings/roads/landuse stay dry.
4. Pillars now walk down through water to the first solid block
   (`pillarFootingY`) instead of sitting on the water surface.

## What to check in game

```
/tpll 17.4316 78.3897     Durgam Cheruvu cable-stayed / suspension
```

- Deck spans the lake, not missing mid-water.
- Piers/pillars reach the lake bed, not floating on the surface.
- Shore buildings and roads still stop at the waterline.

Existing land viaducts (Parade Ground, Ameerpet) must be unchanged.

## Files

- `generator/osm/OsmStructureGenerator.java`
  `generateStructuresFromList`, `placeThickPillar`, `pillarFootingY`
