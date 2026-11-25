# Overture Fix — the backup source lives again

Date: 2026-11-18 · Status: **implemented + proven in sandbox
with the real Java file; awaiting owner in-game log**
(`gradle jarJar` → run → `/terracheck`).

## Why Overture was dead (two bugs, both proven)

1. **The pinned release was deleted by Overture.**
   The code asked S3 for `tiles/2026-08-19.0/*.pmtiles`.
   Verified live from the sandbox: that key answers
   **HTTP 404 NoSuchKey**. The bucket today holds only
   `2026-09-23.0`, `2026-09-23.1`, `2026-10-21.0` — Overture
   keeps only the newest few releases and deletes the rest.
   Any hard-pinned release rots the same way.
2. **One failure poisoned the theme forever, silently.**
   `rootFor()` set `st.failed = true` on the first header
   failure and never cleared it; `httpRange()` swallowed the
   HTTP status/exception into a bare `null`. So a 404 looked
   exactly like a corrupt header, and the theme stayed dead
   all session — batch 3's `OVERTURE header FAIL` at every
   check, 0 tiles served even when the appetizer was armed.

## The fix (all in `generator/osm/OvertureOsmIndex.java`)

- **Release is discovered, not pinned.** First use lists the
  bucket (`?list-type=2&prefix=tiles/&delimiter=/`), newest
  `tiles/<date>.<n>/` wins, result cached for the session
  and stamped to `terra_cache/overture_osm/release.txt`.
  Fallbacks, in order: disk-stamped release, then
  `LAST_KNOWN_RELEASE = "2026-10-21.0"` (verified live).
- **Failures heal.** Theme failure records time + reason and
  retries after 60 s (`ROOT_RETRY_MS`); the other theme is
  unaffected. The warn line keeps the familiar shape:
  `OVERTURE header FAIL theme=...: <reason> (retry in 60s...)`.
- **Failures explain themselves.** `httpRange()` now logs
  HTTP status / exception (first 10), e.g.
  `OVERTURE http FAIL theme=buildings bytes=0-126 HTTP 404`.
- **Header really validated.** `PMTiles` magic + version 3,
  plus tile-type/compression fields read from the header;
  MVT + none/gzip required, root/leaf/tile bytes decompressed
  per the header's own compression bytes (was: gzip assumed).
- **Rotation-proof disk cache.** Cache dir is now the fixed
  `terra_cache/overture_osm/`; tile files are named
  `<release>_<theme>_<x>_<y>.mvt`, so a future release
  rotation invalidates old tiles by name — no stale serving,
  no manual cache clearing. (Old `overture_osm_2026_08_19_0/`
  folder is orphaned; safe to delete.)
- **Bench visibility.** New `getDiagnostics()` (release,
  resident tiles, per-theme ROOT OK / FAILED-ago + reason);
  `/terracheck` prints it as `TEST here OVERTURE STATE: ...`.

## Sandbox proof (real Java, live server, 2026-11-18)

The edited `OvertureOsmIndex.java` was compiled with JDK 17
against minimal stubs and run against the real S3 bucket:

```
OVERTURE release: discovered newest 2026-10-21.0 from bucket listing
OVERTURE root dir theme=buildings: 3501 entries — header self-check PASS (maxzoom=14, tileComp=2)
OVERTURE root dir theme=transportation: 3501 entries — header self-check PASS
RESULT Charminar: 4330 structures in ~1-2s  {BUILDING=3694, ROAD=628, RAILWAY=2, FOOTWAY=6} named=122 charminarNamed=true
RESULT secunderabad: 3648 structures in ~0.5-0.9s {BUILDING=2834, ROAD=654, RAILWAY=84, FOOTWAY=76}
DIAG-AFTER: release=2026-10-21.0 cacheTiles=6 buildings=ROOT OK transportation=ROOT OK
```

Upstream data also eyeballed directly: Charminar z14
buildings tile = 17,479 building polygons; `building_part`
even carries `Charminar, height=19` (parts stay skipped by
design — OSM supplies the real Charminar).

## What the owner should see in the next game log

After `gradle jarJar`, new world (or the same one):

- `OVERTURE release: discovered newest 2026-10-21.0 ...`
  (or a newer date — that is the discovery working)
- `OVERTURE root dir theme=buildings: ... header self-check PASS`
- `/terracheck` → `TEST here OVERTURE STATE: release=... buildings=ROOT OK ...`
  and verdict `SERVING — N structures resident for this chunk`
  whenever the appetizer has been armed or the bench asked.
- No more `OVERTURE header FAIL` (unless the network truly
  fails — then the line names the HTTP status and it retries
  after 60 s instead of dying).

Note: with healthy OSM the appetizer stays dormant by
design (zero bandwidth); it serves when OSM struggles or
when `/terracheck` asks directly. In-game appearance of
Overture-served buildings remains subject to the swap logic
(provisional serving) — the log lines above are the proof
this fix is responsible for.
