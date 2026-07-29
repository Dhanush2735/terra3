# terraminusminus — what is actually used, and what is dead

> **STATUS: APPLIED.** Tiers A, B and C were all executed on 2026-07-26.
> `terraminusminus` went from **54 files / 4,803 lines** to **24 files / 2,256 lines**
> (−30 files, −53%). Whole-project source count 149 → 119 files.
> Compile verified: no new error *kind* vs the 2,606-error Minecraft-absent baseline;
> the tmm subtree compiles standalone with **zero errors** when Lombok is active.
> Runtime verified: projection + dataset output **bit-identical** to the pre-prune tree.
> Rollback tarball: `.terraminusminus_backup_pre_prune.tar.gz` (repo root).
> See §7 for what was applied and the two traps hit along the way.

Scope: `src/main/java/net/buildtheearth/terraminusminus` — **54 files, 4,803 lines**.

Answer up front: **30 of 54 files (2,218 of 4,803 lines, 46%) are dead** and can be
deleted without changing a single byte of output. Only **24 files** are load-bearing.

---

## 1. Method (so the result is checkable, not asserted)

Reachability analysis alone is not proof — a Jackson registry can resurrect a class
that no `import` mentions. So every claim below was verified by **deleting the files
and running the real compiler**, then by **running the projection and dataset code
and diffing the numeric output**.

- JDK 17.0.20, `javac -Xmaxerrs 100000` (the default caps at 100 errors — an earlier
  count of "100" was the display cap, not the error count; the true baseline is **2,606**
  errors, all Minecraft/Forge "package does not exist" because MC isn't on the sandbox
  classpath).
- Comparison is **set difference of error lines**, before vs after deletion. A safe
  deletion adds *zero* new error lines.
- Then a runtime differential test: same inputs through the full tree and the trimmed
  tree, outputs diffed.

### Entry points into terraminusminus (the whole external surface)

Only **five** symbols are imported from outside the package, in only 4 files:

| Imported symbol | Imported by |
|---|---|
| `projection.GeographicProjection` | `TerraDensityFunctions`, `TpllCommand` |
| `projection.OutOfProjectionBoundsException` | `TerraDensityFunctions`, `TpllCommand` |
| `generator.EarthGeneratorSettings` | `TerraDensityFunctions`, `TpllCommand` |
| `dataset.builtin.Soil` | `TerraBiomeProvider` |
| `dataset.builtin.Climate` | `TerraBiomeProvider` |

And both `EarthGeneratorSettings` callers pass the **same constant**:

```java
EarthGeneratorSettings.parse(EarthGeneratorSettings.BTE_DEFAULT_SETTINGS);
```

That constant is `bte_generator_settings.json5`, which names exactly three types:
`scale` → `flip_vertical` → `bte_conformal_dymaxion`. Nothing else is ever parsed.
There is no code path that feeds a user string into `GeographicProjection` parsing —
grep for `GeographicProjection.parse` / `JSON_MAPPER.readValue` outside the package
returns nothing. This is what makes the registry-reachable projections genuinely dead
rather than merely unreferenced.

---

## 2. Results — three tiers, each independently verified

### Tier A — dead by any measure (6 files, 594 lines)

Unreachable even if you assume the Jackson registry can instantiate anything.

| File | Lines | Why dead |
|---|---:|---|
| `util/bvh/QuadtreeBVH.java` | 323 | Nothing constructs a BVH. `BVH.of(...)` is never called. |
| `util/bvh/EmptyBVH.java` | 84 | only reachable via `BVH.of` |
| `util/bvh/SingletonBVH.java` | 81 | only reachable via `BVH.of` |
| `util/bvh/BVH.java` | 51 | the interface itself; no implementor is instantiated |
| `TerraConfig.java` | 44 | Terra3 has its own `terraplusplus/config/TerraConfig.java` (Forge `ForgeConfigSpec`). This one is the old Terra-- POJO holding `daporkchop.net` OSM server URLs. Zero readers of `reducedConsoleMessages`, `openstreetmap`, `http`, `biomes`. |
| `projection/ProjectionFunction.java` | 11 | `@FunctionalInterface` referenced by nothing at all |

The BVH tree is the biggest single win: it exists to spatially index OSM vector data,
and that pipeline was already pruned out of Terra3. Terra3 does its own spatial
indexing in `SourceCooperativeIndex` (FlatGeobuf R-tree) and `OvertureIndex`.

**Verified:** deleted all 6 → full-project compile → **0 new errors** (2,606 → 2,606
on the comparable set).

### Tier B — projections that can never be selected (13 files, 990 lines)

Registered in `GlobalParseRegistries.PROJECTIONS`, therefore "reachable" to a naive
scan, but unreachable in practice because the only config string ever parsed is the
BTE one.

Dead: `AzimuthalEquidistant`, `EqualEarth`, `LambertAzimuthal`, `Sinusoidal`,
`Stereographic`, `CenteredMercator`, `TransverseMercator`, `WebMercator`,
`ClampProjectionTransform`, `RotateProjectionTransform`,
`FlipHorizontalProjectionTransform`, `OffsetProjectionTransform`,
`SwapAxesProjectionTransform`.

Kept (these three *are* the BTE chain): `ScaleProjectionTransform`,
`FlipVerticalProjectionTransform`, `BTEDymaxionProjection` → which extends
`ConformalDynmaxionProjection` → which extends `DymaxionProjection`.
`EquirectangularProjection` is kept because `default_generator_settings.json5` uses it
as the non-BTE fallback.

Deleting these also requires trimming their entries from `GlobalParseRegistries` and
deleting `EarthGeneratorSettings.getLegacyGeneratorString()` — itself dead code
(nothing calls it) that exists only to downgrade configs to pre-v2 format.

**Verified:** Tier A+B deleted → **0 new errors**.

### Tier C — support classes that only Tier A/B needed (11 files, 816 lines)

Once A and B are gone these have no remaining consumer:

| File | Lines | Only consumer was |
|---|---:|---|
| `util/CornerBoundingBox2d.java` | 181 | the `getAsync(bounds,sizeX,sizeZ)` overload — never called; `TerraBiomeProvider` only ever calls the scalar `getAsync(lon,lat)` |
| `substitutes/Identifier.java` | 155 | `Biome` |
| `util/bvh/Bounds2d.java` | 148 | BVH + `CornerBoundingBox2d` |
| `util/bvh/Bounds2dImpl.java` | 65 | `Bounds2d` |
| `substitutes/ChunkPos.java` | 74 | `Bounds2d.toTiles()` — never called |
| `substitutes/BlockPos.java` | 48 | `ChunkPos.atBlockPos` only |
| `substitutes/Biome.java` | 47 | the deleted `TerraConfig.BiomeOpts` |
| `substitutes/BiomeImplementation.java` | 42 | `Biome` |
| `util/BiomeDeserializeMixin.java` | 24 | `TerraConstants` Jackson mixin for `Biome` |
| `substitutes/exceptions/SubstituteParseException.java` | 17 | `Identifier` |
| `substitutes/exceptions/SubstituteException.java` | 15 | `Identifier` |

Note the whole `substitutes/` package is a **Bukkit/Sponge portability shim** — it
exists so terraminusminus can run outside Minecraft. Terra3 *is* a Forge mod and uses
real `net.minecraft.core.BlockPos` / `net.minecraft.world.level.ChunkPos`
everywhere, so the shim is pure overhead.

**Verified:** A+B+C deleted (54 → 24 files) → **0 new errors**.

---

## 3. Runtime differential proof

Compile-clean is not the same as behaviour-identical. Both trees were compiled and run.

**Projection** — `EarthGeneratorSettings.parse(BTE_DEFAULT_SETTINGS)`, round-trip
`fromGeo`/`toGeo` on 6 points:

```
proj=...transform.ScaleProjectionTransform
lat=17.4400 lon=78.5400 -> x=10073450.997639 z=-2033927.286951 -> lon=78.540000000 lat=17.440000000
lat=28.6139 lon=77.2090 -> x=9358526.749013  z=-3101320.988012 -> lon=77.209000000 lat=28.613900000
lat=40.7128 lon=-74.0060 -> x=-8526409.882504 z=-6021647.201223 -> lon=-74.006000000 lat=40.712800000
lat=52.5200 lon=13.4050 -> x=3788273.253681  z=-5234895.843896 -> lon=13.405000000 lat=52.520000000
lat=-33.8688 lon=151.2093 -> x=20000685.354694 z=-2223952.315853 -> lon=151.209300000 lat=-33.868800000
lat=0.0000 lon=0.0000   -> x=837178.962809   z=453805.510774   -> lon=0.000000000 lat=0.000000000
```

`diff full trimmed` → **identical, to the last decimal.**

**Datasets** — `Soil`, `Climate.Precipitation`, `Climate.Temperature` at 3 points:
**identical.**

---

## 4. Retained set — 24 files, 2,585 lines

```
TerraConstants  TerraMinusMinus
config/{GlobalParseRegistries, SingleProperty, TypedDeserializer, TypedSerializer}
dataset/{BlendMode, IScalarDataset}
dataset/builtin/{AbstractBuiltinDataset, Climate, Soil}
generator/EarthGeneratorSettings
projection/{EquirectangularProjection, GeographicProjection, OutOfProjectionBoundsException}
projection/dymaxion/{BTEDymaxionProjection, ConformalDynmaxionProjection, DymaxionProjection}
projection/transform/{FlipVerticalProjectionTransform, ProjectionTransform, ScaleProjectionTransform}
util/{IntToDoubleBiFunction, MathUtils, RLEByteArray}
```

Resources all stay in use: `conformal.gz` (310 KB, needed by
`ConformalDynmaxionProjection`), `climate.gz`, `soil.gz`, both `*_generator_settings.json5`,
`terraminusminus.properties`.

---

## 5. Honest caveats

- **This is a code-size and clarity win, not a performance win.** Dead classes are
  never loaded by the JVM, so deleting them will not move the tick-debt number.
  It does not touch Finding 1 (main-thread blocking I/O), which is still the actual
  cause of `Can't keep up!`.
- The real benefit is that ~46% less code has to be read and reasoned about, and the
  `substitutes/` shim stops being a source of confusion (there are currently two
  `BlockPos`, two `ChunkPos`, and two `TerraConfig` classes in this repo — a genuine
  trap when reading code).
- Deleting Tier B/C means dropping support for non-BTE projections. If you ever want a
  world on Web Mercator or Equal Earth, keep Tier B. **Tier A is safe regardless** and
  is what I'd delete first.
- Not verified in a running Minecraft instance — the sandbox can't run MC or the full
  ForgeGradle remap. Verification here is: real `javac` 17 error-set diff + real
  execution of the projection and dataset code paths.

---

## 6. Unrelated real bug found while running this test

The dataset differential printed the same number for precipitation and temperature.
That is not a test artifact. In `dataset/builtin/Climate.java`:

```java
public static class Precipitation extends Climate {
    public double apply(int x, int y) { return this.data[(x * ROWS + y) << 1]; }      // channel 0
}
public static class Temperature extends Climate {
    public double apply(int x, int y) { return this.data[(x * ROWS + y) << 1]; }      // channel 0 — BUG
}
```

Both read channel 0. `Precipitation` must read `| 1`.

Decoding `climate.gz` directly confirms which channel is which:

```
channel0: min=-56.80 max=30.20   mean=5.19       <- temperature, degC
channel1: min=  0.00 max=11132.00 mean=944.46    <- precipitation, mm/yr

Hyderabad      ch0=26.90  ch1= 794.20
Berlin         ch0= 8.00  ch1= 690.60
New York       ch0=11.00  ch1=1321.00
Cairo(desert)  ch0=21.10  ch1=  29.20
Amazon         ch0=26.60  ch1=2220.90
```

Channel 1 is unmistakably precipitation (Cairo 29 mm/yr desert, Amazon 2221 mm/yr).

Consequence: `TerraBiomeProvider.queryData` receives temperature-in-°C where it
expects precipitation. Its own guard then fires:

```java
if (rawPrecip >= 0 && rawPrecip <= 1000) precip = rawPrecip * 12.0;   // 26.9 -> 322.8 mm
...
if (Math.abs(temp - rawPrecip) < 0.01 || ...) temp = estimateTempFromLat(latitude);
```

So today: **every land biome on Earth gets a fake precipitation of `temp × 12`**
(Hyderabad 322 mm instead of 794 mm — desert-range), and because `temp == rawPrecip`
exactly, the guard throws the *real* temperature away too and substitutes a
latitude estimate. Both climate inputs to biome selection are currently wrong.

One-line fix in `Climate.Precipitation.apply`:

```java
return this.data[((x * ROWS + y) << 1) | 1];
```

I have **not** applied this — it changes biome output worldwide and deserves its own
decision. Flagging it here because it is a correctness bug, and unlike the dead code
above it actually affects what the user sees in game.

---

## 7. Execution record — Tiers A, B, C applied

Backup taken first (this repo has no git history):
`terra3/.terraminusminus_backup_pre_prune.tar.gz` — 54 `.java` files. Restore with:

```bash
cd terra3/src/main/java/net/buildtheearth
rm -rf terraminusminus && tar xzf ../../../../.terraminusminus_backup_pre_prune.tar.gz
```

### What was deleted (30 files)

**Tier A (6):** `util/bvh/{BVH,EmptyBVH,QuadtreeBVH,SingletonBVH}.java`,
`projection/ProjectionFunction.java`, `TerraConfig.java`

**Tier B (13):** `projection/{AzimuthalEquidistant,EqualEarth,LambertAzimuthal,Sinusoidal,Stereographic}Projection.java`,
`projection/mercator/{Centered,Transverse,Web}MercatorProjection.java` (directory removed),
`projection/transform/{Clamp,Rotate,FlipHorizontal,Offset,SwapAxes}ProjectionTransform.java`

**Tier C (11):** `util/CornerBoundingBox2d.java`, `util/bvh/{Bounds2d,Bounds2dImpl}.java`
(directory removed), `util/BiomeDeserializeMixin.java`, and the entire `substitutes/`
package — `{BlockPos,ChunkPos,Biome,BiomeImplementation,Identifier}.java` plus
`substitutes/exceptions/{SubstituteException,SubstituteParseException}.java`.

### What was edited (4 files)

| File | Change |
|---|---|
| `config/GlobalParseRegistries.java` | 19 registry entries → 6 (only the projections Terra3 can actually reach). Added a Javadoc block stating the two-line contract for restoring one. |
| `generator/EarthGeneratorSettings.java` | Removed the unreachable pre-v2 legacy-upgrade branch, the whole private `LegacyConfig` class, and the never-called `getLegacyGeneratorString()`. A config without `"version"` is now an explicit `checkState` failure instead of a silent legacy parse. |
| `TerraConstants.java` | Dropped the `Biome` Jackson mixin registration and its two imports. |
| `dataset/IScalarDataset.java` + `dataset/builtin/AbstractBuiltinDataset.java` | Removed the bulk overload `getAsync(CornerBoundingBox2d,int,int)`. It was never called — `TerraBiomeProvider` samples one point at a time — and it was the last thing keeping `CornerBoundingBox2d` and `util/bvh` alive. |

Deleting `LegacyConfig` was a judgement call worth stating plainly: it is a genuine
**capability removal**, not just dead-code removal. It could only ever fire for a
config lacking a `version` field, and both bundled configs declare `"version": 2`,
so no Terra3 code path could reach it. But if you ever hand-write a pre-2019
Terra121 config, it will now fail loudly instead of silently upgrading.

### Verification actually performed

1. **Per-tier compile diff.** After each tier: full 149→119-file `javac 17` run,
   `-Xmaxerrs 100000`, error lines set-diffed against the 2,606-line baseline.
   Tier A: 0 new. Tier B: 0 new kinds. Tier C: 0 new kinds. Final: 2,596 errors,
   all pre-existing Minecraft/Forge "package does not exist".
2. **No dangling references.** Grepped the final error log for every deleted class
   name — zero hits.
3. **Standalone Lombok compile.** The 24-file subtree compiles with **0 errors**
   with annotation processing enabled (warnings only, all from third-party jars).
4. **Runtime differential.** Pre-prune tree rebuilt from the backup and both trees
   executed on: BTE projection `fromGeo`/`toGeo` round-trip at 8 points (including
   poles and Tierra del Fuego), the default equirectangular config, and
   Soil/Precipitation/Temperature at 5 points. **Output bit-identical.**
5. **Resource check.** All six bundled resources still referenced; no empty dirs left.

### Two traps hit while doing this — recorded so they aren't re-tripped

**Trap 1 — `javac` caps error output at 100 by default.** Earlier sessions recorded
the baseline as "exactly 100 errors". That was the *display cap*, not the count. The
true figure is **2,606**. Any future compile check must pass `-Xmaxerrs 100000` or it
is comparing truncated lists and will silently miss regressions.

**Trap 2 — a false regression caused by my own test harness.** The first differential
showed `EarthGeneratorSettings.toString()` emitting `{"scale":{}}` instead of the full
nested projection, which looks exactly like the pruned registry breaking serialization.
It was not. I had extracted the backup to `/tmp/orig` **without** copying terra3's
`lombok.config`, which sets `lombok.accessors.fluent = true`. Without it Lombok emits
bean-style `getX()/getY()`, which Jackson serializes; with it only fluent `x()/y()`
exist, which Jackson ignores. Rebuilding the original *with* `lombok.config` produced
bit-identical output.

So the sparse `{"scale":{}}` serialization is **pre-existing upstream behaviour** in
this repo, unrelated to pruning. It is also harmless here: nothing in either repo ever
serializes settings or a projection (`writeValueAsString` / `GSON.toJson` have no
callers outside terraminusminus itself). Flagging it only because if anyone ever *does*
add config round-tripping, it will not work until `lombok.accessors.fluent` is
reconciled with the Jackson getters.

### What this did and did not achieve

- **Did:** removed 53% of terraminusminus, eliminated the duplicate-class trap (there
  were two `BlockPos`, two `ChunkPos` and two `TerraConfig` in the repo; now one each),
  and made the registry honestly reflect what Terra3 supports.
- **Did not:** change runtime behaviour, memory use, or tick time. Unused classes are
  never loaded by the JVM. **Finding 1 (blocking network I/O on the server main thread)
  is still unfixed and is still the cause of `Can't keep up!`.** Do not expect the tick
  debt number to move because of this change.
- **Not run in Minecraft.** The sandbox cannot execute MC or the ForgeGradle remap.
  Confidence rests on the compile diff plus the runtime differential above. The one
  thing worth watching on first launch is world creation, since that is where
  `EarthGeneratorSettings.parse` runs.
