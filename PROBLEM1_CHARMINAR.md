# Problem 1 — OSM/OHM structures "not satisfying": the Charminar case

Owner's example, confirmed: in the New World (made at
`/tpll 17.3617 78.4747`), the Charminar is missing. Viewer scan
of the file: a ~160 x 160 block patch of bare ground centred on
the landing point, inside an otherwise built town. No monument,
no roads, nothing.

## Proven in code + live data (2026-11-17)

1. **The Charminar tag trap.** Real OSM way 238186115, fetched
   live from openstreetmap.org, carries BOTH `building=yes` and
   `building:part=yes` (plus height=19, building:material=
   limestone, building:colour=#FFE4B5, historic=monument).
   The parser in `OsmStructureIndex.java` checked
   `building:part` FIRST, so the monument was classified as a
   BUILDING_PART — an orphan part, never a building.
   (As a part it could still have been drawn, so this alone
   does not explain the bare patch; it does explain wrong
   treatment of every famous building tagged this way.)
   **FIXED:** a real `building` outline tag (not `building=no`)
   now wins over `building:part`.

2. **The buried hole.** In `TerraChunkGenerator`'s decoration
   method, the catch block did `markDecorationPlaced(ch)` on
   ANY exception. A chunk whose placement threw once was marked
   decorated and never retried by generation — a permanent hole
   from one bad structure. The failure is visible in game logs
   as `Struct [x,z]: ...`.
   **FIXED:** the catch now enqueues an async retry
   (`queueRetry`) and never marks the chunk decorated.

Both edits verified by re-reading the code and brace balance.
**Not compiled** — sandbox cannot build Forge; owner builds
with `gradle jarJar`.

## Not yet proven

Which mechanism emptied the ~160 m patch in the owner's actual
world: the exception path (fix 2), a lost async retry, or a
failed tile fetch at first generation. The game's `latest.log`
from that session discriminates (`Struct [` lines). World file
also still to be re-sent (the /tmp copy was wiped) so the hole
can be re-measured and, later, the healed world compared.

## Pass mark (owner's PC, after `gradle jarJar`)

New world, `/tpll 17.3617 78.4747`, stand still ~1 minute:
a cream-coloured building about 31 x 33 blocks at your feet,
main roof ~19 blocks up (OSM `height=19`). The body standing
there = pass. (Minaret towers depend on further part data in
OSM; not promised in this step.)

## Next in problem 1

- Fix 3: heal already-saved bare chunks on chunk load (today the
  load repair is year-based; a bare chunk stamped at the current
  year is invisible to it).
- Why didn't the first async retry heal the patch? Read the
  load/queue path before writing fix 3.
