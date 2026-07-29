# Terra++ Data Source Hierarchy & Time-Jump Behaviour

Reference for how OHM, OSM and Microsoft ML footprints are prioritised, and what
actually happens to buildings and roads when the simulation year changes. Derived
by reading the generator source, not from the design docs.

---

## 1. The two different pipelines

There are **two separate structure paths**, and they resolve sources differently.
Which one runs depends on `worldConfig.timeEnabled`.

| | Non-historical path | Historical path |
|---|---|---|
| Entry | `TerraChunkGenerator` (~line 2002) | `HistoricalStructureManager.getStructures` |
| Sources | OSM, Microsoft | OHM, OSM, Microsoft |
| Dedup | geometric overlap | osmId + geometric overlap |
| Dates | ignored | full resolution chain |

A third, largely separate path exists in `AsyncStructureManager.processRetry`,
used only for tiles that failed their first fetch. Its dedup logic differs from
both of the above — see §5.

---

## 2. Priority order — which source wins

### Non-historical (`timeEnabled = false`)

Controlled by `activeSource`, a static field defaulting to `"both"`
(`TerraChunkGenerator:191`). Valid values: `"osm"`, `"overture"`, `"both"`.

With `"both"`:

1. **OSM is fetched first** and everything it returns is added unconditionally.
2. **Microsoft is fetched second**, and each MS building is tested with
   `overlaps(building, osmBlockers)`. If it overlaps an existing OSM building or
   bridge, it is **dropped**.

So the rule is: **OSM is authoritative; Microsoft only fills gaps where OSM has
no building.** This is the correct choice — OSM footprints are human-mapped with
real tags, MS footprints are ML-derived with no metadata.

`overlaps()` is a genuine geometric test, not a bounding-box approximation. It
checks three things: MS centroid inside the OSM polygon, any MS polygon vertex
inside the OSM polygon, and the OSM centroid inside the MS polygon. That third
check catches the donut case where a small MS building sits inside a large OSM
courtyard. For bridges it uses point-to-segment distance with a 0.00009° tolerance
(roughly 10 m).

Microsoft is additionally gated on `worldConfig.overtureBuildings` (defaults true)
and `OvertureIndex.isEnabled()`.

### Historical (`timeEnabled = true`)

Three tiers, applied in strict order in `HistoricalStructureManager`:

**Step 1 — OHM (OpenHistoricalMap).** Highest authority. This is the only source
with real historical dates. Every OHM structure with a known `startYear` also
becomes a **gravity anchor** used to date nearby undated buildings. If OHM times
out, the whole chunk returns `null` and is retried — OHM is never silently skipped.

**Step 2 — OSM.** Skipped if `s.osmId` is already in the OHM set (exact-ID dedup —
reliable here because OHM and OSM share the ID space). Dated OSM structures also
become anchors, explicitly "regardless of whether OHM had data", since an explicit
`start_date` tag is trustworthy.

**Step 3 — Microsoft.** Lowest authority, and dropped if it overlaps anything
already visible from OHM or OSM.

Note the anchor subtlety: OHM anchors are only used for gravity estimation if OHM
actually returned data for that chunk (`ohmHasDataHere`); otherwise the code falls
back to `anchorsOsmOnly(anchors)`. This prevents a handful of distant OHM points
from skewing dates across a region where OHM has no coverage.

---

## 3. How a building gets its date

For anything without an explicit date, the chain is:

1. **Explicit `start_date`** (OHM or OSM tag) — used directly, no inference.
2. **WikiData lookup by name** — only if the structure has a name. Most MS
   buildings are unnamed, so this usually short-circuits immediately.
3. **Gravity estimation** from nearby dated anchors, weighted by structure type
   (`StructureDateEstimator`, e.g. `SIG_ROAD = 1.2`).
4. **Population-model fallback** when there are no usable anchors.

Microsoft buildings always skip step 1 entirely — the ML dataset has no dates.

---

## 4. Time-jump behaviour

### What triggers a refresh

`TerraTimeManager.forceRecalculate(level)` recomputes the year from world day and
logs `Time jump: X → Y`. Then `PersistentChunkRefreshIndex.advanceRefreshGeneration`
bumps a global generation counter and records `lastJumpYear`.

Staleness is per chunk. `needsRefresh(level, cx, cz)` returns true when the chunk's
stored year differs from the current year:

```
if (!region.visited.get(idx)) return false;   // never generated -> nothing to refresh
short stored = region.chunkYears[idx];
if (stored == UNSET_YEAR) return true;
return stored != currentYearShort();
```

Years are stored as `short` per chunk, clamped to `MIN_YEAR = -3000` /
`MAX_YEAR = 9999`. That range fits comfortably in a short, and `UNSET_YEAR`
(`Short.MIN_VALUE = -32768`) cannot collide with a real year. This is sound.

### Buildings

Buildings are **fully year-aware in both directions**. Visibility is decided by
`HistoricalDateParser.isVisibleAt(start, end, currentYear, false)`, which respects
both construction and demolition years. Jump backward and a building that hasn't
been built yet is excluded from `visible`; jump forward past its `endYear` and it
is excluded again.

There is also a look-ahead: structures appearing within `FOREST_CLEAR_LEAD_YEARS`
go into an `upcoming` list, used to pre-clear vegetation so a building doesn't
suddenly erupt through a forest.

Refresh is not instant across the map. Only chunks that are both **in memory** and
**stale** get requeued, within `RESCAN_RADIUS = 20` chunks of a player, on a
countdown after the jump (`postJumpRescanCountdown`). Distant chunks refresh lazily
when next loaded. So after a big jump you should expect the world near you to
update over several seconds, not in a single tick.

### Roads — the important asymmetry

Roads are **year-gated for existence but not for appearance**.

`HistoricalStructureManager.getRoadMaterialKey(year)` exists and maps eras to
materials (dirt path → cobblestone → gravel → smooth stone → cobblestone →
gray concrete → black concrete). It is **never called anywhere in the codebase** —
verified by grep across all 147 source files; the only hit is its own definition.

Actual road material comes from `OsmStructureGenerator`, selected purely from OSM
surface tags and highway type (`asphalt`, `concrete`, `gravel`, …, defaulting to
`ROAD_CONCRETE`). No year parameter is involved.

**Consequence:** a Roman road in 200 BC renders in black concrete if OSM tags the
modern road as asphalt. Roads correctly appear and disappear with the timeline, but
they never change *material* with the era. Wiring `getRoadMaterialKey` into the
road block selection is a small, high-visual-impact fix.

---

## 4b. VIDA tier (added)

`SourceCooperativeIndex` (VIDA / Source Cooperative Google+Microsoft+OSM open
buildings) is now wired in as a tier between OSM and Microsoft, giving:

```
OHM  ->  OSM  ->  VIDA (Google)  ->  Microsoft
```

OSM stays authoritative. VIDA fills gaps OSM leaves; Microsoft fills gaps both
leave. Enforced in **all four** structure paths:

| Path | Purpose |
|---|---|
| `TerraChunkGenerator` primary pass | normal chunk decoration |
| `TerraChunkGenerator.fetchStructuresForChunk` | pass-2 refresh, used on time jumps |
| `HistoricalStructureManager` steps 1-4 | historical mode |
| `AsyncStructureManager.processRetry` | tiles that failed first fetch |

Dedup is **geometric, never ID-based**, because VIDA footprints carry no OSM IDs
(the same reason the Microsoft ID check in §5 never worked). Two helpers on
`SourceCooperativeIndex` do the work: `overlapsExisting(vida, accepted)` drops a
VIDA building that collides with an OHM/OSM footprint, and
`overlapsVida(microsoft, acceptedVida)` drops a Microsoft building that collides
with an accepted VIDA one. Both test centroid-in-polygon in both directions after
a fast bbox reject, so courtyard/donut cases are caught.

`VidaBuilding` gained a bounding box (`minLat`/`maxLat`/`minLon`/`maxLon`,
computed after the 0.5 m inset) and a `containsPoint` method to support this.

In historical mode VIDA carries no dates, so it uses the same
gravity → population estimator as Microsoft, skipping the WikiData step since VIDA
footprints are unnamed. A `null` return from the index means the S2 shard is still
downloading and the chunk is retried rather than generated without buildings.

Controlled by `TerraWorldConfig.vidaBuildings` (default true) and the
`activeSource` value `"vida"` (or `"both"`). A 20-argument constructor overload
carries the new flag; the existing 19-argument constructor defaults it to true, so
all 21 existing call sites are unchanged.

## 4c. Why VIDA caused a severe performance loss (FIXED)

OSM and Microsoft were fine while VIDA tanked generation, and the reason is
structural rather than anything to do with the data volume itself.

**Every other source is spatially tiled; VIDA was not.** `OsmStructureIndex`
buckets by `TILE_DEG = 0.01`, `OvertureIndex` by `TILE_DEG = 0.005`, and both cap
memory (`MEM_CACHE_MAX` 256 / 1024) with an eviction pass. A chunk query therefore
touches one small tile and inspects a few hundred buildings.

VIDA cached one flat `List<VidaBuilding>` per S2 shard, and a shard is an entire
country region — commonly 0.5-3 million buildings. `filterBuildings` then ran a
linear scan over that whole list **on every chunk query**, comparing four
coordinates per building. That is roughly a 1000-3000x larger working set per
chunk than any other source.

Measured on a 1M-building shard, 500 chunk queries:

| | per chunk | 8 chunks/tick |
|---|---|---|
| flat scan (old) | 7.54 ms | 60.3 ms = **121% of the 50 ms tick budget** |
| grid lookup (new) | 0.006 ms | 0.05 ms = 0.1% |

So VIDA alone could exceed the entire server tick budget, which is exactly the
stall that was observed. Two secondary problems compounded it: the shard cache had
**no eviction** (a 3M-building shard pins ~1-1.8 GB of heap permanently, driving GC
pressure), and `failedShards` was written but never read, so a shard that failed
re-triggered a download attempt on every subsequent chunk.

**Fix.** Buildings are bucketed into a `GRID_DEG = 0.01` spatial grid once at parse
time, matching the OSM tile size. Queries visit only the 1-4 cells they overlap.
An LRU eviction keeps at most `MAX_SHARDS = 2` resident, and `failedShards` is now
checked on entry. Benchmarked at **1250-1700x faster with byte-identical results**.

Two robustness fixes went in alongside: a read timeout (a stalled server could
previously pin one of only two download threads indefinitely) and download-to-temp
plus atomic rename (an interrupted download previously left a truncated `.fgb`
that `Files.exists` trusted forever).

## 4d. VIDA OutOfMemoryError (FIXED)

A live run produced `java.lang.OutOfMemoryError: Java heap space` with `-Xmx4096m`.
The log line that explains it:

```
VIDA shard IND_4307974518556590080 parsed: 2248048 buildings in 20124 grid cells
```

The grid fix in 4c made lookups fast but did nothing about *retention* — the whole
country shard still became live objects. Two compounding causes:

**Three separate index instances.** `TerraChunkGenerator`,
`HistoricalStructureManager` and `AsyncStructureManager` each did
`new SourceCooperativeIndex()`, so each held its own copy of the same parsed
shard. Roughly 0.8 GB each, ~2.4 GB total, against a 4 GB heap shared with
Minecraft itself. `SourceCooperativeIndex` is now a singleton via
`getInstance()`, with the constructor made private so this cannot regress.

**Whole-country retention.** 2.25M `VidaBuilding` objects were materialised to
serve chunks within a few hundred metres of the player. Parsing now discards
buildings outside a `RETAIN_HALF_DEG = 0.35` window (~78 x 78 km) around the
requesting chunk, and rejects on the *first vertex* before allocating any
`OsmNode` or `VidaBuilding` — the filter has to precede allocation or it saves
nothing. `MAX_SHARDS` dropped from 2 to 1.

| | buildings live | heap |
|---|---|---|
| before, one instance | 2,248,048 | 0.79 GB |
| before, three instances | 6,744,144 | 2.37 GB |
| after | ~547,000 | 0.19 GB |

Each `Shard` records the window it covers. If the player travels outside it the
shard is dropped and re-parsed centred on the new position, so correctness is
preserved at the cost of an occasional re-parse from the already-cached `.fgb`.

Note the log also shows repeated `Downloading Microsoft buildings for quadkey
123301331 ...` lines. That is `OvertureIndex`, which is constructed twice
(generator + historical manager) and has the same duplicate-instance smell; worth
giving the same singleton treatment.

## 4e. Full-log timeline: VIDA was not the only problem

Reconstructing the crash run from timestamps changes the diagnosis in one
important way.

```
T+  0.0 s   world created
T+ 75.7 s   spawn prepared (28.1 s)
T+103.0 s   *** Can't keep up: 14,068 ms behind ***
T+125.2 s   time jump -3000 -> 2026, 1216 chunks queued
T+214.3 s   VIDA shard parsed: 2,248,048 buildings
T+282.8 s   "Placed 213 blocks" - ONE chunk, the only such line in ~5 min
T+324.2 s   *** Can't keep up: 206,177 ms behind ***
T+339.7 s   *** OutOfMemoryError ***
T+902.4 s   next log line (9.4 min of GC thrash)
```

**The first 14-second stall happens at T+103 s, but VIDA does not load until
T+214 s.** So VIDA cannot be its cause. There are at least two independent
performance bugs, and fixing VIDA alone would not have produced a smooth run.

### Cause A - blocking network fetches on chunk worker threads

`OsmStructureIndex.getStructuresForChunkBlocking` is called from the generator
and parks the calling thread until data arrives or the timeout expires. The log
shows this firing repeatedly:

```
BLOCKING TIMEOUT: 0/1 tiles in 4000ms - null
```

on `Worker-Main-5, -6, -9, -11, -14` - five distinct Minecraft worker threads,
4 s each. Those are the same threads that generate chunks. With Overpass slow or
rate-limited (the log also shows `de returned 400`, `kumi returned 400`,
`mail.ru returned 400`), the pool spends its time parked rather than generating.
That is the T+103 s stall, entirely independent of VIDA.

The same tile is then re-fetched and re-parsed repeatedly -
`OSM STRUCT [17.4500,78.5400]: 2807 features` appears **six times** for one
tile, roughly 16,800 structure objects built and discarded.

### Cause B - VIDA heap exhaustion (see 4d)

Lands at T+214 s and finishes the job 2 minutes later.

### Also fixed this pass

**WorldCover 404 storm.** `EsaWorldCoverIndex` returned `null` on HTTP 404 but
never recorded it, so a nonexistent tile was re-requested on every chunk query.
`N12E081` alone logged several hundred identical warnings, each a real HTTP
round-trip. A `missingTiles` set now records 404s permanently and
`triggerHeaderFetch` short-circuits on them.

**Duplicate OvertureIndex.** `OvertureIndex initialized` and
`Microsoft index downloaded: 25606 quadkeys` each appear twice - the generator
and the historical manager built separate instances with separate caches and
download pools. Now a singleton, matching the VIDA fix.

**OSM water log spam.** `Disabling OSM water after 5 failures` printed ~75 times
within a single millisecond because every already-queued task hit the branch. Now
guarded by an `AtomicBoolean` so it logs once.

### Still outstanding

`Downloading Microsoft buildings for quadkey 123301331 ...` appears **seven
times and never completes**, while quadkey `123301332` downloads and reports
`15344 buildings` normally. Something about that specific quadkey hangs or fails
silently and is retried forever. Worth adding a per-quadkey failure cap plus a
read timeout on the Overture download path.

## 4f. Cold-start: only one teleport per session (FIXED)

Symptom: before VIDA, a cold start (no cache files) allowed teleporting to
several locations in the same year. After VIDA, only the first location worked.

Two causes, both now fixed.

### The .fgb spatial index was being skipped

Every FlatGeobuf file embeds a packed Hilbert R-tree between the header and the
feature data. The parser stepped **over** it:

```java
if (iSize > 0) { ... in.skipBytes((int)(numNodes * 40L)); }   // index discarded
while (true) { ...decode every feature... }                    // 2.25M of them
```

So each new location decoded all 2,248,048 features to keep the few thousand
nearby. `readViaRTree` now queries the index instead, seeking only to matching
features; the linear scan remains as a fallback.

Benchmarked on a purpose-built 200k-feature / 34 MB .fgb, querying a 0.70°
box: **110 ms linear vs 17 ms R-tree, byte-identical results** (113 buildings
both ways). Linear cost scales with feature count while R-tree cost is
log-depth plus hits, so on the real 2.25M-feature shard the gap widens to
roughly 1.2 s → 0.02 s per location.

### The cache was keyed per shard, so city B evicted city A

`shardCache` was keyed `IND_4307974518556590080`. A second teleport inside India
missed the window check and did `shardCache.remove(key)`, throwing away the first
city and re-parsing from scratch — every hop, forever. Keys are now
`iso_shard@gridLat,gridLon` (rounded to `RETAIN_HALF_DEG`), `MAX_SHARDS` is 6,
and a lookup that misses its own region still scans resident regions before
giving up, so standing on a boundary doesn't force a re-parse.

Net effect on a cold start with five teleports: ~6 s of parsing plus five full
re-parses becomes ~0.1 s total, with all five regions resident.

### Guava note

`PackedRTree.search` reaches Guava's `LittleEndianDataInputStream`. Guava 31.1-jre
ships with Minecraft and `terraminusminus` already imports `com.google.common`,
so it is declared `compileOnly` and deliberately **not** jarJar'd — shading a
second copy would risk a classpath conflict. `readViaRTree` catches `Throwable`
(not just `Exception`) so a `NoClassDefFoundError` degrades to the linear scan
rather than breaking generation, warning once.

## 4g. VIDA returned 0 buildings everywhere (FIXED)

The R-tree work in 4f made queries instant, and the run log confirms it — the
query and the parse complete in the *same millisecond*, where the linear scan
took ~90 s:

```
00:06:51.918  VIDA R-tree query returned 0 of 2248048 features
00:06:51.918  VIDA shard IND_4307974518556590080@49,224 parsed: 0 of 0 retained
```

But zero buildings, in central Hyderabad, where Microsoft found 330/420/409/297
in the same chunks. The speed fix was correct; the shard being queried was wrong.

**Root cause: one hard-coded S2 cell per country.**

```java
if ("IND".equals(iso)) return "4307974518556590080";   // one cell for all of India
```

Verified against the live dataset rather than inferred:

- the ISO box for IND accepts lat 5–38, lon 65–100 — roughly 3,700 × 3,900 km
- that shard ID is a **level-6 S2 cell, about 143 km across**
- reading the real `.fgb` header, it covers **lat 16.101–17.673, lon 76.456–77.929**
- Hyderabad is at lon **78.54** — about 68 km outside it

So VIDA was attempted across an entire country but could only ever return data
for ~0.14% of it. Everywhere else silently produced nothing, which is exactly the
"VIDA does nothing" symptom.

Listing the bucket shows VIDA actually splits countries into many shards:
**198 for India, 72 for the USA, 11 for Germany** — 281 in total, all level 6.

**Fix.** `vida_shards.csv` (in `resources/data/terraplusplus/`) now records the
true bounding box of every shard, extracted from the `.fgb` headers themselves,
and lookup is an exact containment test. Verified:

| query | resolved shard |
|---|---|
| Hyderabad | `IND 4308537468510011392` |
| Delhi | `IND 4110942034859130880` |
| Mumbai | `IND 4316418767857909760` |
| Bengaluru | `IND 4300656169162113024` |
| New York | `USA 9930437178351943680` |
| Berlin | `DEU 5164502872687116288` |
| Tokyo | no shard — VIDA cleanly skipped |

`readViaRTree` also checks the header envelope up front and logs an explicit
error naming both boxes if a shard cannot cover the request, so this class of
mismatch can never again fail silently.

Regenerating the CSV when VIDA publishes new data means re-reading the shard
headers; the listing endpoint is
`https://data.source.coop/vida?list-type=2&prefix=google-microsoft-osm-open-buildings/flatgeobuf/by_country_s2/country_iso%3D<ISO>/`.

Note the India shards total ~528 million buildings, so the correct shard for a
dense city is large (Hyderabad's is 4.77M features). The R-tree query plus the
`RETAIN_HALF_DEG` window keep the resident cost bounded, but the first download
per shard is still hundreds of MB.

## 4h. Positional disagreement between sources — ghost buildings (FIXED)

Symptom: Google/VIDA places a building *beside* the OSM one instead of being
recognised as the same building, so cities render with doubled footprints.

**Why the old dedup could not catch it.** Every check was a strict geometric
test — centroid-in-polygon, vertex-in-polygon, bbox intersection. Those only
fire when the two footprints actually touch. But the sources have independent
positional error:

| source | typical accuracy |
|---|---|
| OSM / OHM | hand-traced, ~1–2 m |
| Microsoft ML | ~2–5 m, sometimes more |
| Google / VIDA | similar magnitude, *different* bias direction |

A footprint shifted 4 m does not overlap a 10 m house, so it passed every test
and was accepted as a separate building.

**Why "snap ML onto OSM" is the wrong fix.** If OSM already has the building,
the ML copy adds no information — snapping just stacks two near-identical
shapes on the same spot. The useful operation is *rejection*, not alignment,
and the hard part is deciding the two are the same building.

**The fix** — `FootprintAlignment` adds a near-miss test after the strict ones:

1. **Centroid distance in metres**, with tolerance scaled to building size:
   `6 m + 0.15 × √area`, clamped to 14 m. A shed needs a tight tolerance
   because neighbours are close; a warehouse tolerates more because its
   centroid sits far from its edges. Tolerance uses the **smaller** of the two
   buildings so a warehouse cannot swallow an adjacent shed.
2. **Bounding-box IoU ≥ 0.30**, catching drifted centroids that still clearly
   cover the same ground.

Everything is computed in **metres, not degrees**. A degree of longitude is
111 km at the equator but only 106 km at Hyderabad and ~85 km at 40° N, so a
fixed degree tolerance silently changes meaning with latitude.

Verified behaviour:

| case | result |
|---|---|
| 10 m house, ML offset 1–6 m | deduped |
| two 10 m houses 9 / 12 / 15 / 25 m apart | kept separate |
| 60 m warehouse, ML copy offset 10 m | deduped |
| 4 m shed 12 m from a 60 m warehouse | kept separate |

Applied to all four comparison paths: VIDA↔OSM, Microsoft↔VIDA,
Microsoft↔OSM (retry path) and `TerraChunkGenerator.overlaps` (primary path).

Tuning: raise `BASE_TOLERANCE_M` if ghosts persist, lower it if legitimately
close buildings vanish. `MAX_TOLERANCE_M` is the safety clamp for dense rows.

## 4i. Retention window resized after the shard fix (FIXED)

The corrected shard lookup exposed a sizing bug. With the wrong shard VIDA
returned 0 buildings; with the right one Hyderabad returned:

```
VIDA shard IND_4308537468510011392@49,224 parsed:
  2,856,906 of 2,856,906 buildings retained (4395 grid cells)
Can't keep up! Running 232101ms or 4642 ticks behind
```

`RETAIN_HALF_DEG = 0.35` is a 78 × 78 km box — roughly 1 GB of live objects in
a dense city. At view distance 12 a player sees ~192 m, so the window was about
400× larger than anything rendered. Reduced to **0.05** (~11 km box), still ~28×
the visible radius but a small fraction of the objects.

## 4j. Buildings standing in the road (FIXED)

Screenshots showed a small ML building sitting in the middle of a carriageway,
surrounded by road on all sides.

This is a *different* failure from 4h. The near-miss test in 4h compares an ML
footprint against **other buildings**. When an ML footprint lands on a road,
OSM has no building there to compare against, so every building-vs-building
test passes it and it renders as a hut in the traffic lane.

It is decidable without any building data: OSM road geometry is reliable, and a
building centroid inside the carriageway is provably wrong whichever dataset
produced it. `FootprintAlignment.sitsOnRoad` rejects on distance to the road
centreline, using `OsmStructure.distanceToLine` (already returns metres):

| way type | half-width |
|---|---|
| ROAD | 7.0 m (~2 lanes) |
| RAILWAY | 5.0 m |
| FOOTWAY / CYCLEWAY | 2.0 m |

Deliberately conservative — roughly one lane either side rather than the full
right-of-way, because buildings legitimately abut narrow streets and deleting
real terraces would be worse than the occasional survivor. Verified: a centroid
0–7 m from the centreline is removed, 8 m+ is kept.

Only the **centroid** is tested. Testing polygon vertices would reject anything
merely touching the kerb, which is most of a real city.

Applied at all four ML acceptance points (VIDA and Microsoft, primary and
pass-2 refresh paths).

## 4k. Overture downloaded and parsed the same quadkey 3× (FIXED)

```
01:09:08  Downloading quadkey 123301331
01:11:19  Downloading quadkey 123301331
01:11:31  Downloading quadkey 123301331
01:12:17  MSFT quadkey 123301331: 628630 buildings from 628630 lines
01:12:24  MSFT quadkey 123301331: 628630 buildings from 628630 lines
01:12:32  MSFT quadkey 123301331: 628630 buildings from 628630 lines
```

`fetchMicrosoftBuildings` checked the cache, and on a miss went straight to
download — with no record that a download was already running. Concurrent chunks
all missed and all did the full job: three downloads and three parses of a
628,630-line file, allocating ~1.9M building objects to keep a few hundred.

Fixed with an `msftInFlight` set. The first thread claims the quadkey; the rest
return `null`, which callers already treat as "not ready, retry". The claim is
released in a `finally` so a failure cannot block the quadkey permanently, and
the cache is re-checked after claiming in case another thread just finished.

## 5. Bug: the retry path dedups Microsoft against OSM by ID (FIXED)

In `AsyncStructureManager.processRetry` (~line 242):

```java
for (OvertureIndex.OvertureBuilding ob : task.overtureResult.buildings) {
    if (ob.osmId != 0 && osmIds.contains(ob.osmId)) continue;
    combined.add(ob.toOsmStructure());
}
```

This is **ID-based dedup against Microsoft data**. But `osmId` is only ever
populated from an `osm_id` property in the source GeoJSON
(`OvertureIndex:1136`), and the Microsoft Global ML Building Footprints dataset
does not carry OSM IDs. In practice `ob.osmId == 0` for essentially every MS
building, the condition is always false, and **nothing is skipped**.

The result is duplicate overlapping buildings — MS footprints stacked on top of
OSM ones — but only in chunks that went through the retry path. The primary path
at `TerraChunkGenerator:2077` does this correctly with the geometric `overlaps()`
test, which is why the bug is intermittent and location-dependent.

**Fixed** as part of the VIDA work. `processRetry` now uses a geometric
`overlapsAnyStructure(microsoft, combined)` helper mirroring
`TerraChunkGenerator.overlaps` (bbox reject, then centroid-in-polygon both ways,
plus point-to-segment for bridges), and additionally checks `overlapsVida`. The
retry path and the primary path now agree on which Microsoft buildings to drop.

Note that `OvertureIndex.getBuildingsInRange` does have working dedup via
`stableHash` on `overtureId` or centroid — but that is Microsoft-vs-Microsoft
only, and does not help against OSM.

---

## 6. Summary

Priority, highest to lowest: **OHM → OSM → VIDA (Google) → Microsoft**, with VIDA
and Microsoft both gap-fill only. In non-historical mode OHM drops out and it is
OSM → VIDA → Microsoft.

Buildings respond correctly to time jumps in both directions, refreshing near
players first and lazily elsewhere. Roads appear and disappear correctly but are
stuck with modern materials because the era-material mapping is dead code.

The one genuine correctness bug is the ID-based Microsoft dedup in the retry path,
which silently does nothing and produces duplicate buildings.
