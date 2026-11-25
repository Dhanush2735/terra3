# Fix test — owner's log, New World (2), 2026-11-17 21:43–21:46

Question: does the floating-duplicate fix (FLOATING_BUILDING_FIX.md)
hold in a fresh world at the same place?

## What the log proves

- Fresh start, same place: spawn lat 17.43463113, lon 78.50142347
  -> block 10070047, 603, −2031327. Cache FRESH.
- Timejump still off: "timejump disabled (temporary); year
  pinned to 2026". OHM structures still off ("enabled=false").
- All three sources fetched and placed again: OSM tiles
  [17.4300,78.5000]=1647 and [17.4300,78.4900]=3665;
  VIDA shard retained 15,406 buildings (chunks after it landed
  show vida>0, incl. the tower chunk); Microsoft index 25,606
  quadkeys, tiles fetched incl. one empty tile, chunks show
  microsoft=0..4 (dense-OSM area, as before).
- The tower chunk [629372,−126958] was placed ONCE by the
  generation road at 21:43:41 (osm=108 vida=9 microsoft=0),
  with the two layer=4 metro ways crossing there
  (BRIDGE CROSSING 1188687061/1188687060).
- The async road later re-checked that chunk (21:45:18,
  PLACEMENT_TEST: 19 repeats of 19 features) — this is the
  exact moment the old bug stacked the second copy. The
  async numbers visible in the log are small (0 blocks for
  most re-checked chunks; gap-fill-sized numbers like
  70–170 blocks in a few) — a stacked tower would be
  thousands of blocks in one chunk. No [629372,−126958]
  block-write burst appears.
- No STATION ALIGN fired for any building in the tower area.
  The nearby layer=1 building 357060333 got GROUND ALIGN
  (correctly kept on the ground).
- The one STATION ALIGN this session (building 635428057,
  deck offset 16, chunk [629400,−126975]) is the intentional
  station snap, a different place.
- No errors from the fix; no Struct [x,z] failures. The
  "de returned 502" line is one water tile fetch, unrelated.

## What the log cannot prove

- It cannot show the tower itself. Only the owner's eyes
  (already given — see below) or a world file can prove
  "one building, both tracks at one level".

## Still needed

Owner's in-game verdict (or the world file): look west of
spawn — one tower on the ground, both metro tracks together
at deck level, no floating copy, no rail on any roof.
