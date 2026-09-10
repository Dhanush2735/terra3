# Universe forging — creating alternative universes in-game

## The question this answers

> "If I change anything in the past it creates an alternative timeline.
> How do I create an alternative UNIVERSE?"

## The design answer: you cannot get a universe by changing the past

The two axes are deliberately different in kind:

| | axis 5 — timeline | axis 6 — universe |
|---|---|---|
| what differs | **choices** (history) | **physical law** (geography/physics) |
| created by | changing the past — preventing an event, `/terra worldline branch create` | **forging** — deliberately defining new physics |
| where it lives | same world, morphs in place | its own dimension, own chunk storage |
| example | "Rome never fell" | "the oceans are 60 m higher" |

No historical decision — however large — moves a coastline or inverts a
mountain. Changing the past therefore *always* produces a timeline, never a
universe. Universes come from outside history: they are **forged**.

(This mirrors the physics framing: many-worlds branches share physical
constants; different *universes* in a multiverse differ in the constants
themselves.)

## What was implemented

Previously the 6 universes (prime, drowned, glacial, inverted, mirror,
amplified) were hardcoded. Now players can create their own at runtime:

### 5 forgeable universe slots

`universe_custom1 … universe_custom5` — pre-registered dimensions (1.20.1
datapack dimensions are fixed at world start, so slots exist up front;
forging assigns their physics). Parameters are read live by the generator,
so a forge takes effect immediately — no restart.

### The forge command

```
/terra worldline universe forge <slot 1-5> <sea_offset_m> <elev_scale> <invert> <mirror> [name]
```

Physics pipeline per column: `elevation → (invert?) → ×scale → −seaOffset`,
plus optional east-west mirroring of the whole planet (with correct inverse
for structure fetch).

Examples:

```
/terra worldline universe forge 1 60 1 false false Waterworld
    — sea +60 m: drowned coastal cities

/terra worldline universe forge 2 -120 1 false false Ice Age
    — walk Doggerland and the Bering land bridge

/terra worldline universe forge 3 0 2.5 true false Hell Earth
    — inverted AND amplified: 22 km Mariana peak

/terra worldline universe forge 4 30 1.5 false true Backwards Flood
    — mirrored Earth, mild flood, exaggerated relief

/terra worldline universe travel custom3
```

### Safety rails

- sea offset clamped ±2000 m, scale clamped ±10 (0 coerced to 1)
- fixed universes (`drowned`, `prime`, …) cannot be re-forged
- forged params persist per-world (`terra_universes.dat`), loaded at level
  load before any custom-slot dimension generates
- re-forging a slot whose chunks were already generated changes only future
  generation; the command warns that old region files keep old physics
  until deleted (Minecraft chunks are immutable once written)

## Verification

- 15/15 standalone unit tests pass: slot registration, identity before
  forge, pipeline order (100×2−30=170), invert, mirror + inverse
  round-trip, clamping, invalid-slot & fixed-universe rejection, live
  re-forge, persistence round-trip incl. name.
- Full-project compile: zero non-baseline errors in all touched files.

## Files

- `generator/UniverseTransforms.java` — ForgeParams, SlotUniverse (live
  params), forge()/getForge(), save/loadForged
- `generator/historical/TerraWorldLineCommand.java` — forge subcommand
- `generator/historical/PersistentChunkRefreshIndex.java` — loadForged at
  level load
- `data/terraplusplus/dimension/universe_custom{1..5}.json` — slot dims

## Possible future work

- **In-world trigger**: consume a "Reality Anvil" item / ritual structure
  instead of a command, so forging feels earned in survival.
- **Timeline↔universe promotion**: archive a timeline branch's divergent
  history INTO a forged slot (copy branch registry + assign physics), for
  "this alternate history became its own world" narratives.
- More forge parameters: biome palette shift, gravity-like terrain
  smoothing, latitude compression (pangea-style).
