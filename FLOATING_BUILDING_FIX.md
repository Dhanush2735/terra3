# Fix — floating duplicate building (newworld1 tower)

Status: source-only, NOT compiled here. Verdict = owner's local
`gradle jarJar` + in-game test.

## The bug (verified from the world file)

newworld1, Secunderabad Station Road area. One stone tower
(x 10069948..10069960, z −2031339..−2031320) existed twice:
ground copy y 599–628, metro deck through it at y 629–630,
second copy of the same building y 631–661 floating on the
deck, and rail stamped on the copy's roof at y 663 (other
track of the same line stays at y 630). Full evidence:
`/home/user/incoming/newworld1_READING.md`,
picture `/home/user/incoming/nw1_zoom/view_from_SW.png`.

## Cause (code-supported; session log not supplied)

The async placement road (`StructureTickHandler` →
`OsmStructureGenerator.placeSingleBlock`) re-runs on chunks
the generator already built. Two weaknesses let it stack a
second copy:

1. `findSolidGround` climbed any solid stack above the
   terrain. Finished building + metro deck are one unbroken
   stack, so "ground" became the deck/roof, and ground
   structures (building, rail) were placed up there.
2. Nothing asked "does a building already stand in this
   column?" before placing another building — so a second
   placement (retry, another source's copy, or a station copy
   floated to deck height) stacked instead of skipping.

## The fix (one file: generator/osm/OsmStructureGenerator.java)

1. `findSolidGround`: the upward climb now stops at placed
   structure blocks (`isBuildingBlock || isRoadBlock ||
   isBridgeDeckBlock`). Natural terrain tops are still
   climbed exactly as before. This also protects the
   generation road if it is ever re-run on a built chunk.
2. `placeSingleBlock`, BUILDING case only: skip the column
   when `hasBuildingAbove(...)` finds a building already
   standing on that column's terrain ground. BUILDING_PART
   deliberately NOT guarded — parts are meant to stack on
   their base building.

Nothing else changed. Bridge heights (layer×8), the metro
deck, source order OSM → VIDA → Microsoft, and the
generation road's placement are untouched.

## Owner test (pass mark)

1. Build: `gradle jarJar` in B/terra3; put the jar in mods.
2. Make a NEW world (the bad tower is already saved as
   blocks in newworld1; a code fix does not rebuild saved
   chunks, so the old world keeps it).
3. Go to the same area (world spawn is fine — the tower is
   ~90 blocks west of spawn), wait ~2 minutes, fly the metro
   line west of spawn.
4. PASS: the stone tower is ONE building on the ground; the
   metro line passes at one level (deck ~y 630) with BOTH
   tracks together; no building floating over the line, no
   track on any roof.
5. Also glance at neighbouring buildings: they must still
   appear as before (the guard must not eat normal
   buildings — they are placed by the generation road,
   which is not guarded).

If a duplicate still appears, send the game log — lines to
grep: `STATION ALIGN`, `GROUND ALIGN`, `[TerraStruct]`,
`Placed ... blocks in` — they will show which road placed it.
