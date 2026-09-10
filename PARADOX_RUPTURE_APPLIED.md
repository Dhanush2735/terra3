# Paradox rupture — universes born from broken paradoxes

## The idea (user's)

> "An alternative universe is created when a paradox is broken."

This completes the world-line cosmology. Before, the two axes had
asymmetric creation stories: timelines emerged naturally (change the past),
universes only by deliberate forging. Now both are natural:

```
consistent change of the past  ->  TIMELINE BRANCH   (axis 5)
paradox — a contradiction      ->  UNIVERSE RUPTURE  (axis 6)
```

A timeline can absorb any *consistent* alternative ("Rome never fell" —
reality reconciles it and diverges). A **paradox** is a contradiction the
same physics cannot reconcile: you stand in year 1700 and destroy a
structure whose explicit `start_date` documents it standing continuously
into the present. 326 years of recorded history now both happened and
didn't. Reality can't hold both — it ruptures, and the contradiction is
expelled into a **newborn universe whose physics are warped by the
violence of the rupture**.

## Mechanics

### What counts as a paradox (`ParadoxMath.isParadox`)

Breaking blocks of a structure that is ALL of:
- **explicitly dated** (OHM/OSM `start_date` — the "fixed points" that
  timeline branches are already forbidden to alter; undated structures
  have estimated histories, so changing them is mere timeline divergence)
- **already built** at the actor's simulation year
- **still standing at the present** (2026) per the record — if history
  already records its demolition, early removal is inconsistency, not
  contradiction
- and the actor is **in the past** (`year < 2026`, time travel enabled)

Building/building-part/tower/bridge types qualify (footprint containment
or ≤6 m for bridges). The lookup is memory-cache-only — the block-break
hook never waits on network.

### Temporal stress

Each violation adds `typeWeight × √(yearsContradicted) × 2` stress:
- deeper past = worse (√-damped: a Roman-era hit ≈ 46× a 1-year hit)
- cathedrals/castles/monuments 3×, churches/mosques/temples 2.2×,
  stations/universities 1.6×, generic 1×; **named** structures ×1.5
- stress **decays 1/sec** — reality heals small wounds; only sustained
  vandalism of history ruptures it

Warning bands with escalating feedback (chat + end-portal sound):
35% "reality trembles", 70% "cracks spread". Tuned so one block of a named
ancient landmark ≈ 83% (always a warning first), the second block ruptures.
`/terra worldline paradox` shows current stress.

### The rupture (`ParadoxEngine.rupture`)

At ≥500 stress:
1. **Deterministic newborn physics** from `hash(osmId, year, stress)`:
   sea offset ±(120 × violence) m, relief scale 1 ± 0.5×violence,
   inversion forced at extreme violence (≥2.5×), occasional mirroring.
   The same paradox always births the same universe.
2. The first unforged universe slot (`custom1..5`) is **forged
   automatically**, named e.g. `"Rupture of Charminar (1700 CE)"` (all
   slots taken → the rupture overwrites scar tissue deterministically).
3. A **timeline branch** `paradox_<hex>` records the causal story on
   axis 5 — the WHY survives even though the consequence lives on axis 6.
4. Server-wide broadcast, and the offender is **cast into the newborn
   universe** at the same world-line x/z ("you fall through the crack you
   made"). Their stress resets — the debt was paid in reality.

## Cosmology after this change

| act | consequence |
|---|---|
| change undated history in the past | timeline branch (axis 5) |
| prevent an event (Eternal Engine) | timeline branch (axis 5) |
| destroy a fixed point in the past | **temporal stress → universe rupture (axis 6)** |
| deliberate forging (`universe forge`) | universe (axis 6) — the "safe", controlled way |

## Verification

- **25/25 unit tests pass** (`ParadoxMath` standalone): all seven
  violation/non-violation rules, √ scaling, type/name weights, decay,
  threshold, band ordering, warning-before-rupture balance, seed and
  physics determinism, bounds (sea ≤ ±360 m, |scale| ≥ 0.3), forced
  inversion at extreme violence.
- Full-project compile: **0 non-baseline errors** in all new/touched
  files (ParadoxMath, ParadoxEngine, TerraWorldLineCommand,
  TerraChunkGenerator accessors).

## Files

- NEW `generator/historical/ParadoxMath.java` — pure mechanics (no MC
  imports, fully unit-tested)
- NEW `generator/historical/ParadoxEngine.java` — Forge `BlockEvent
  .BreakEvent` hook, stress ledger, feedback, rupture orchestration
- `generator/historical/TerraWorldLineCommand.java` — `/terra worldline
  paradox` status
- `generator/TerraChunkGenerator.java` — `toGeoPublic`,
  `getStructureIndexPublic` accessors

## Future hooks

- **Explosions/fire**: only player block-breaks count today; TNT-ing a
  cathedral escapes the ledger (`ExplosionEvent.Detonate` is the hook).
- **Restoring the fixed point** (rebuilding the structure) could refund
  stress — "repairing the timeline".
- **Eternal Engine**: its event system could declare event-level fixed
  points (assassinations, battles) whose prevention-by-force is also a
  paradox, feeding the same stress ledger.
