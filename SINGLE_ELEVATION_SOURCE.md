# Single elevation source — Terrarium removed completely (2026-11-20)

Owner report: "because of 2 elevation sources there are problems:
1. teleported chunk elevated, 2. floating water at rivers,
3. coastline taking more time to load and many more."
Owner decision (after simple-words explanation of the tradeoff):
remove Terrarium completely; Mapterhorn becomes the only source.

## Was the diagnosis right?
Mostly yes. The two books were mixed at different zooms:
- terrain z15: Mapterhorn-first, Terrarium fallback per tile;
- parent fallback z14, spike-guard anchor z11, bathymetry
  z8/7/6/5/3: all Terrarium;
- teleport prefetch downloaded TERRARIUM tiles directly
  (downloadTile) for z15/z11/z8 — and getTile checks the
  memory cache first, so prefetch could poison the terrain
  zoom with old-book tiles before Mapterhorn ever ran.
Consequences match the report: neighbouring tiles from
different DEMs = elevation seams ("teleported chunk
elevated"); river/coast water levels reasoned from coarse
Terrarium while ground came from Mapterhorn (floating
water); coasts fetched 2 sources x up to 7 zooms (slow).
(Honest note: the NW(20) raised chunk was PROVEN an
older-build fossil, not source mixing. Source mixing is a
separate, real seam risk that this switch eliminates.)

## Changes
- TerraDensityFunctions: getTile serves EVERY zoom from
  Mapterhorn (terrain z15, parent z14, anchors z11/z10).
  downloadTile (Terrarium HTTP) deleted; downloadTileSources-
  First deleted; fetchMapterhornTile(key) is the only live
  fetch. Failure = SENTINEL_FAILED -> parent/bathymetry/
  -50 fallbacks, never a silent second book.
- Prefetch (teleport warm-up) now warms {z15, z11} through
  fetchMapterhornTile — the poisoning path is gone.
- Disk cache: Mapterhorn tiles now cached under
  tiles/mapterhorn-v1/ (validated PNGs). The old folder of
  archived Terrarium PNGs is never read again.
- Bathymetry: bundled grid. GMRT (GEBCO-filled global
  topo) 20 km tiles resampled to a 0.5-deg 720x360 float
  grid, diffusion-filled, packed as resources/.../
  bathy.gz (835 KB) + generator/BathyData.java (bilinear,
  fail-soft NaN). fetchBathymetry reads the grid — no
  downloads, same answer every restart. Script:
  /home/user/bathy_build/build_bathy_grid.py.
- MapterhornTiles.fetchTile256 extended below z12: z11/z10
  stitched from planet z12 tiles + downsampled (guard
  anchors are now the same book as the terrain).
- TerraConfig: terrainDataUrl + mapterhornElevation
  REMOVED (old config keys ignored).
- Legacy dataset framework: TerrariumHeightmapDataset +
  HeightmapDataset DELETED; DatasetManager is an honest
  shell (legacy API samples NaN; nothing in world
  generation used it).
- Text: /terra heights guard wording, HeightSpikeGuard
  docs, TerrainDiagnostic endpoint (Mapterhorn planet).
- Kept deliberately: the spike guard (cheap insurance,
  anchors now same-source), and "Terrarium" as the name of
  the height-in-RGB pixel ENCODING (a format, not a source;
  Mapterhorn tiles use it too).

## Sandbox proof
- JDK17 parse: all touched files clean (only expected
  no-classpath errors).
- BathyData harness: Mariana -9196 m, Mid-Atlantic
  -2978 m, Juhu offshore -13.7 m, lon wrap consistent.
- MapterhornTiles live harness (real TwelveMonkeys jars):
  Gandikota z15 267.5 m, z14 266.3 m, z11 282.3 m,
  z10 263.3 m (same book; coarse = smoother), Juhu
  offshore z11 = 0.0 m.

## Owner test (after gradle jarJar, FRESH world ideally)
1. /tpll to a NEW place: watch log for "ELEV SRC ...:
   mapterhorn" and NO elevation-tile downloads from
   s3.amazonaws.com (any source). Terrain should load at
   least as fast; coasts faster than NW22.
2. Teleport chunk test: /tpll 14.82325 78.28101
   (Gandikota) repeatedly in fresh areas — no raised
   chunk seams at tile borders.
3. River test: stand by a river (Gandikota Penna river,
   Grand Canyon Colorado river) — water sits IN the
   channel, not floating.
4. Coast test: /tpll 19.0968 72.8265 (Juhu Mumbai) —
   coastline loads noticeably faster; sea depth still
   varies (bundled grid).
5. Old worlds still open fine (old chunk heights are
   already baked in; only NEW chunks use the one book).
