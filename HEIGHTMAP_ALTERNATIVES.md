# Heightmap — is Terrarium perfect? Alternatives measured 2026-11-18

**Verdict: NOT perfect. Working and proven in-game, but the
source data is an ageing composite with real dirt. Two
credible upgrade candidates measured live today; no code
changed. Switch = owner decision (ground changes under
saved worlds).**

## Today's live three-way test — same Dead Sea water box

Box lon 35.515–35.555, lat 31.48–31.52 (open water; true
surface ≈ −412 m in 2000, ≈ −439 m today):

| Source | Result over the water |
|---|---|
| **Terrarium (current)** z14 PNG | median −412.0 m — but **21 crazy pixels / 254,436**, worst **−5,226.9 m** (a pit to nowhere in mid-sea) |
| **Copernicus GLO-30** (Planetary Computer COG stats) | min −427.0, max −416.5, median −427.0 — dead flat, zero spikes |
| **Mapterhorn** z12 tile (Terrarium-encoded WebP) | min −432.0, median −432.0 — dead flat; max 284 m = real east-shore foothills |

Supporting facts: raw SRTM30 (OpenTopoData) reads **+200 m**
at (31.1, 35.55) over the south-basin ponds — the dirt the
current composite inherits. The game's own batch-4 DEAD SEA
GRID agreed (SOURCE DATA DIRTY). The game's outlier repair
hides most single pixels, but it is patching a dirty source.

## Known flaws of the current heightmap (Terrarium)

1. **Spike/pit pixels** in radar-shadow terrain (Dead Sea
   rift measured; steep coasts/gorges are the same family).
2. **Ageing composite** — sources from ~2000–2016; Dead Sea
   level frozen at −412 m vs ≈ −439 m real today (Copernicus
   2011–15 says −427, Mapterhorn −432). Shorelines drift.
3. **Surface, not bare earth** — in places it measures
   rooftops/canopy, so game buildings can sit on invisible
   bumps (the building sources then add their own height).
4. **Ocean = 0 at fine zoom** (game works around via coarse
   bathymetry fallback — proven good: Mariana −10,389 m).
5. **Nothing beyond ±85°** (web-mercator limit; poles are
   ocean by game design, unchanged by any tile source).

What is NOT the source's fault: the 1:1→squash curves and
the −50 failure sentinel are game code (batch-4: curves
PASS; sentinel measured SAFE, fix not yet authorized).

## Candidates

### A. Copernicus DEM GLO-30 (ESA/Airbus TanDEM-X, 2011–15)
- Free, attribution licence; 30 m global COGs. Exists for
  Hyderabad (`Copernicus_DSM_COG_10_N17_00_E078_00_DEM`)
  and the Dead Sea on Microsoft Planetary Computer — free
  STAC + signed COG reads, no account (AWS registry:
  registry.opendata.aws/copernicus-dem/).
- Game already reads ESA COGs by S3 range requests →
  same engineering family as the working ESA reader.
- Caveats: still a *surface* model (rooftops included);
  "Public" edition withholds a few countries' tiles
  (spot-checks passed; full list must be scanned);
  1° COG tiles ≠ PNG tiles → new reader + cache re-warm.

### B. FABDEM (GLO-30 with forests+buildings erased)
- The only true **bare-earth** global 30 m product —
  ground exactly where buildings should sit.
- Caveats: bulk 10° zip downloads (not live-fetch
  friendly), licence **CC BY-NC-SA 4.0** (non-commercial +
  share-alike — awkward for a redistributed mod/cache),
  v1-0 vintage. Pre-baking tiles = heavy offline pipeline.

### C. Mapterhorn (community terrain tiles, measured today)
- `https://tiles.mapterhorn.com/{z}/{x}/{y}.webp`,
  **Terrarium-encoded** (proven by decode: flat −432 m) —
  the game's existing decoder maths would work unchanged.
  Global 30 m (Copernicus-based) + true ground models
  where countries publish them (Netherlands 5 m, Germany
  1 m, USA 10 m, Japan, UK… per mapterhorn.com).
- Caveats: **WebP only** (Java needs a WebP decoder —
  ImageIO has none built in); max zoom ≈13 (fine: source
  detail is ~30 m anyway); community CDN — fair-use and
  reliability for a game must be respected/verified;
  ocean handling unverified (game fallback may still
  carry oceans).

### D. Ocean-only upgrade: GEBCO / ETOPO 2022
- Best bathymetry grids if oceans ever need sharpening;
  current coarse fallback already measures well.

### E. National LiDAR patchwork
- Metre-class where published (Netherlands AHN, USGS
  3DEP…) — Mapterhorn already blends many of these;
  rolling our own patchwork is not realistic globally.

## Recommendation

1. **Do not rip out a proven engine on one test.** Run an
   offline "championship" first (no game code): Terrarium
   vs Copernicus vs Mapterhorn at ~15 places — spike/pit
   counts, city-ground flatness, Everest/Hyderabad anchors,
   withheld-country scan for places the owner cares about.
2. Owner decides with numbers in hand. If switched:
   expect border cliffs between old-saved chunks and new
   chunks (heights differ by metres in places) → fresh
   world advised, same warning as every terrain change.
3. FABDEM stays a paper candidate unless licensing and
   the offline bake are acceptable.
