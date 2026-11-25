# TEMPORARY: timejump feature switched off (owner directive)

Structures must come only from OSM + Microsoft + VIDA
(present day). One switch controls everything:

`TerraWorldConfig.TIMEJUMP_FEATURE_ENABLED = false`
(`config/TerraWorldConfig.java`, line ~155)

## What was changed (nothing deleted)

1. `TerraWorldConfig` — while the switch is off, every world
   config (new, saved, or from the world-creation screen) is
   forced to `timeEnabled=false` and `ohmEnabled=false` in the
   constructor. The generator already had a complete non-time
   path: OSM fetch, then VIDA / Microsoft gap-fill merges, no
   year filtering. That path is now the only path.
2. `TerraTimeManager` — `initialize(...)`, `setTimeEnabled(...)`
   and `load(...)` all respect the switch. An old
   `terra_time.dat` save cannot resurrect the feature. While
   off, the year is pinned to the present year
   (`java.time.Year.now()`), so roads/vegetation behave as
   today instead of year -3000.
3. `TerraTimeCommand` — `/terra timejump` and
   `/terra timescale` answer "temporarily disabled" and do
   nothing.

## To restore the time feature

Set `TIMEJUMP_FEATURE_ENABLED = true` in
`config/TerraWorldConfig.java`. That is the only change
needed; all historical code (OHM, year filters, refresh
engine) is untouched and waiting.

## Honest limits

- Source-only, not compiled; owner builds with `gradle jarJar`.
- The world-creation screen may still show the time option;
  it cannot take effect while the switch is off.
- Existing worlds keep whatever is already built; the switch
  changes what is placed from now on.
