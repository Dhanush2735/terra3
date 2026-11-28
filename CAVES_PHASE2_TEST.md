# Phase 2 in-game acceptance test

Use the rebuilt Terra++ JAR in a fresh test world. Do not reuse already-generated chunks. Leave `realCaves` enabled. Test the Phase 2 changes before importing any blueprint or tunnel data.

| Site | Command | What to check |
|---|---|---|
| Mammoth Cave | `/tpll 37.1869 -86.1034` | At the entrance X/Z from the log, verify the shaft joins the first passage. Follow the passage across a chunk border using F3+G. |
| Krubera Cave | `/tpll 43.4097 40.3622` | Check entrance-to-passage connection and multiple descending sections. Compare the logged planned depth with the recorded depth; do not treat either as an in-game measurement. |
| Mid-Sahara control | `/tpll 23.37 25.63` | In fresh chunks, inspect below the surface for unintended cave carving. A mapped feature, if logged, is not a valid negative control. |

Capture the `REALCAVE` lines and note the first blocked or disconnected position (X/Y/Z), if any. For steep natural caves, use spectator mode to inspect continuity before trying the route in survival. Separately confirm that a mapped mine and bunker can be entered and exited using the generated ladder. A log alone is not a pass.

## NW35 regression

Mammoth's mapped entrance starts at X -9521304, Z -5410957 (the 2×2 extends east and south). NW35 had three capped columns at Y 279–280 and one open column. Retest in fresh chunks and inspect all four columns; none should retain a natural cap above the access passage. Krubera's 2×2 at X 5737951, Z -3921095 was open in NW35; recheck that it remains open. The Sahara region contained no saved chunks, so the control still needs to be run.

## NW36 save review & world-file handling

Owner instruction: inspect supplied world archives read-only in temporary storage; delete the archive and temporary inspection files after each review. Keep only findings, not copies of the world.

NW36: all five exact test locations had fully saved chunks. Their sampled land columns retained the legacy lava blanket at Y -1850, -1900 and -2031, above bedrock at -2032. No Mammoth rock stack, Hyderabad granite, Hekla basalt, or Delta subsurface sediment pattern was found at the expected spots. No ore palettes or paired fluid-pocket markers in the 405 nearby chunks sampled. The archive contained no Terra++ common config or latest.log; cannot distinguish disabled settings from a different build or previously generated terrain.

Mammoth at (-9521304,-5410957): all four columns have air from Y 276 through Y 259, but dark oak leaves cap three near Y 277–280. Krubera at (5737951,-3921095): all four columns are open from surrounding surface down to the Y 1369–1370 floor; the saved player position is at Y -396 about 627 horizontal blocks away, evidence of a visit underground, not proof of a continuous route or maximum depth. No release pass.

### NW36-R1 retest

The shaft preflight now permits non-waterlogged leaves in a narrow surface band; normal tree generation keeps new tree crowns away from mapped mouths. Logs, fluid and other protected blocks are still refused. On fresh terrain generation look for `TERRA UNDERGROUND build=NW36-R1 realisticUnderground=true undergroundExtras=true realCaves=true` in `latest.log` before evaluating the geology. No defaults changed and old chunks are not repaired by this pass.

## NW37 save review

Mammoth: all four mouth columns clear down through Y 259; NW36 leaf cap absent. A bounded six-neighbor air scan starting at the shaft base (48 blocks horizontal, 135 down, one up) reached Y 258 and extended 10 blocks horizontally into a second chunk. Krubera: open entrance; the same bounded scan reached Y 1336 and the 48-block horizontal search boundary, crossing nine chunks. Full descents and survival traversal not proven.

Mammoth, Krubera and Delta still have lava at Y -1850, -1900 and -2031 above bedrock at -2032. The Mammoth profile and Delta clay/gravel layer were absent at checked columns. No ore or paired sealed-pocket signatures in the three 11×11 samples (363 full chunks). Hyderabad and Hekla target chunks not present. ZIP lacks latest.log and the Terra++ common config. Verify the NW36-R1 startup marker and actual enabled config before judging geology; no source changes from this review. World download and all scratch data deleted.

## NW38 save review

Mammoth 2x2 stayed open to Y259 without a leaf cap; bounded air scan crosses two chunks, reaches Y258 and 10 blocks sideways. Krubera 2x2 open to Y1369–1370 floor; bounded air scan reaches Y1336 and hits 48-block horizontal search boundary across nine chunks. No timing evidence: no latest.log. Mammoth, Krubera and the Delta still use the legacy lava fill at Y -1850, -1900 and -2031 above bedrock at -2032. Delta subsurface showed dirt over stone, without expected gravel/clay. No ore or sealed-pocket palette markers across 363 full chunks in the three 11x11 samples. Hyderabad and Hekla target chunks absent. No Terra++ common config in the ZIP. Verify build/config before doing another geology save test. Download and scratch files removed per standing order.

## NW39 save review

Delta at X -9941083, Z -4948679: deepslate at Y -1850, -1900 and -2031; bedrock at -2032. Surface grass/dirt at Y 100–97; clay Y 92–90 and gravel Y 89–84. In 121 full surrounding chunks, actual block palettes include iron, copper, coal, gold, redstone and diamond variants. Nine water pockets passed an exact block-by-block check of their water core and terracotta lining; example center (-9941160,37,-4948696). This is saved-block proof for the lowland rock/deposit/ore/water pass, not the volcanic or cave regression. Mammoth, Krubera, Hyderabad and Hekla target chunks absent. No latest.log. World download and all temporary review files removed per standing instruction.
