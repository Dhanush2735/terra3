# Terra³ Testing Playbook — in order

Run phases IN ORDER; each phase gates the next. After every phase run:

```
python tools/terra_log_analyzer.py run/logs/latest.log --phase <phase>
```

(and `--phase all` at the end of a session). If a phase FAILs, stop and
paste the log — fixing forward from a broken base wastes time.

Machine notes: Java 17, 6–8 GB free RAM for ForgeGradle. First launches
are slow (MCP remap). All commands need op (permission level 2).

---

## PHASE 0 — Build

```
cd terra3
gradlew clean build --no-daemon
```

PASS = `BUILD SUCCESSFUL`, jar in `build/libs/`.
If compile errors: paste the FIRST error block only (later ones cascade).

## PHASE 1 — Boot (5 min)

```
gradlew runClient        (or install jar into a 1.20.1 Forge 47.2.0 setup)
```

1. Create world → world type / preset: **Terra++ Earth**.
2. Let spawn load. Then in chat:

```
/terra worldline                → must print all 6 axes
```

PASS = world loads, no crash, command responds.
ANALYZER: `--phase boot` — checks projection load, generator inits, crash-free.

## PHASE 2 — Cold start (10 min) ★ the big one

Close the game. Delete the caches to force a true cold start:

```
delete  run/terra_cache/        (or .minecraft/terra_cache/)
```

Relaunch, same world, then teleport somewhere dense you have NOT visited:

```
/tpll 17.3850 78.4867           (Charminar, Hyderabad)
```

Start a stopwatch. Watch for buildings appearing.

| measure | target |
|---|---|
| first buildings appear | < 20 s (was 60–100 s pre-batching) |
| log shows `BATCH fetch [...]: N tiles in one request` | YES — this proves batching engaged |
| `BLOCKING TIMEOUT` lines | 0–2 acceptable, 10+ = FAIL |
| `Can't keep up!` during load | 0 |

Then a second location, same test (proves batching isn't spawn-only):

```
/tpll 18.9220 72.8347           (Gateway of India, Mumbai)
```

ANALYZER: `--phase cold`.

## PHASE 3 — Structures (10 min)

At Charminar, walk/fly around ~300 m and check against reality:

- [ ] Charminar building present, roads along real alignments
- [ ] no buildings sitting in the middle of roads
- [ ] trees/parks where maps show greenery
- [ ] `?` cells only at the scan edge (unloaded), not inside

Then new coverage families — go somewhere with each and confirm:
- [ ] Begumpet Airport (`/tpll 17.4531 78.4676`): RUNWAY strips render
- [ ] Juhu Beach (`/tpll 19.0968 72.8265`): sand
- [ ] KBR Park (`/tpll 17.4200 78.4200`): forest (natural=wood!)

ANALYZER: `--phase structures`.

## PHASE 4 — Time travel (15 min)

Stay at Charminar (built 1591 — perfect test subject):

```
/terra timejump 1500       → Charminar must VANISH (not built yet)
                             roads become gravel/dirt, no lamps, no markings
/terra timejump 1700       → Charminar appears; sett/cobble era roads
/terra timejump 1885       → gas lamps (oak post + lantern)
/terra timejump 2026       → modern: asphalt, markings, electric lamps
```

After each jump wait ~30 s for refresh (nearest chunks first), then
check visually — road surfaces, lamps and markings must respect the year.

- [ ] each jump completes: log pair TIMEJUMP_START/TIMEJUMP_DONE
- [ ] world actually morphs (blocks change, not just chat)
- [ ] no `Can't keep up!` during refresh
- [ ] jump BACK also works (2026 → 1500 → 2026)

ANALYZER: `--phase time`.

## PHASE 5 — World-line: branches & universes (15 min)

```
/terra worldline branch create no_mughals 1500 Mughal empire never formed
/terra worldline branch switch no_mughals
      → world re-forms around you (est. dates shift; dated structures stay)
/terra worldline branch switch main
      → morphs back

/terra worldline universe list
/terra worldline universe travel drowned
      → same x/z, sea +60 m — coastal areas underwater
/terra worldline universe travel prime

/terra worldline universe forge 1 -120 1 false false Ice Age
/terra worldline universe travel custom1
      → land bridges walkable (try /tpll 51.0 1.5 — Dover Strait dry)
```

- [ ] branch switch triggers visible refresh both directions
- [ ] each universe dimension loads and has DIFFERENT terrain at same x/z
- [ ] `/terra worldline` always reports the correct 6-axis coordinate
- [ ] quit + relaunch → branches/forges persist (terra_worldline.dat etc.)

ANALYZER: `--phase worldline`.

## PHASE 6 — Paradox (10 min)

```
/terra timejump 1700        (Charminar exists, started 1591, stands today)
/terra worldline paradox    → 0%, stable
```

Break blocks of Charminar itself:

- [ ] after a few blocks: purple "[Paradox] Reality trembles…" (~35%)
- [ ] continuing: "Cracks spread…" + portal sound (~70%)
- [ ] continuing: RUPTURE — server broadcast, a customN slot forged
      "Rupture of …", you are teleported into the newborn universe
- [ ] `/terra worldline` there shows the rupture universe
- [ ] control: break an UNDATED village house → stress stays 0

ANALYZER: `--phase paradox`.

## PHASE 7 — Soak (30+ min, background)

Leave the game running and every ~5 min do one of: fly 500 m, timejump,
branch switch, universe hop. After 30+ min:

- [ ] memory stable (F3 heap sawtooths but doesn't ramp)
- [ ] no accumulated `Can't keep up!`

ANALYZER: `--phase soak`, then `--phase all` for the final scorecard.

---

## Reporting results back

For ANY failure, paste:
1. the analyzer output (whole scorecard),
2. the relevant log excerpt (~50 lines around the first error).

That pair is enough to reproduce and fix almost anything remotely.
