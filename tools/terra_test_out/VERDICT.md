# New World — what my 3D viewer found (2026-11-17)

World sent by the owner as `newworld.zip`. Read offline only;
nothing installed or run.

## The short answer
The world is real Terra generation and it is healthy. There is a
built town about 500 x 500 blocks, centred almost exactly on the
spot where the player logged out (player x 10071576, z -2022496;
town centre x 10071575, z -2022505). The first render box missed
it — it landed in the bare patch just east of the town. That was
my aim, not a world fault.

## Facts (all from the files, not guessed)
- Save format: normal `.mca` regions (4 files), empty `cubes/`
  folders, plus `data/terra_chunk_index/`. **No `.cc3` files** —
  so a real game-made `.cc3` is still unproven; the synthetic
  `.cc3` test remains the only proof for that path.
- Only ~278k of ~1M possible columns exist: the world has only
  generated where the player has been. Normal.
- Built patch: x 10071328..10071823, z -2022752..-2022257
  (68,540 built-surface columns over the 4 region files).
- Rendered crop 192x192 at the densest part: 2,315,238 blocks,
  y 503..594. Ground ~565-575; tallest roofs 594 (buildings up
  to ~8 storeys).
- Materials seen: stone-brick / brick / terracotta / plank walls,
  dark-oak and grey flat roofs, 5,554 glass panes, 1,666 lanterns,
  gray-concrete roads joining everything, dirt paths, trees.
- Ground surface is tuff (grey-green rock), not grass. That is
  what Terra puts down here; it is not damage.
- Entities in the whole save near the town: 9 dropped items and
  1 spider. **No NPCs or villagers** — this save is Terra-only;
  Eternal Engine people are not in it.
- The 2 FAIL lines in `report_3d.txt` are the two 0-byte files in
  `entities/` (`r.19670.-3950.mca`, `r.19671.-3950.mca`). The game
  made empty entity files; nothing is corrupt.
- The grey rock hanging under the town in the side views is the
  64-layer slice I kept, not caves or floating buildings.

## Viewer changes made for this world
- Loader rewritten two-pass (column tops first, then only the
  top 64 layers per column). The old loader tried to hold whole
  solid-earth columns and was OOM-killed (exit 137). New run:
  2,315,238 blocks in ~2 min 20 s, ~0.5 GB, no kill.
- Big scenes now draw face-culled; small scenes unchanged
  (demo still 15/15, cube test 3/3).
- True colours added for tuff, coarse dirt, dark oak, terracottas,
  glass pane, jungle leaves, lantern and friends. Legend now
  0.0% auto-coloured (was showing dark-oak roofs bright yellow).

## Still open (honest list)
- Real `.cc3` file: still never seen. If a world is ever saved
  with cubic chunks on, send it and I will prove that path too.
- `/terracheck` and the new Terra jar: still need the owner's PC
  run (`gradle jarJar`, then Run 1 Terra-only, Run 2 with EE).
- Flat colours prove shape and materials, never looks. The
  owner's in-game eyes remain the beauty gate.
