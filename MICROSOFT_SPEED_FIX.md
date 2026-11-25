# Microsoft Speed Fix — Steps 1 + 2

Date: 2026-11-18 · Status: **implemented + proven in sandbox
with the real Java file** (parallel download + full query
path run live against the real 53 MB Hyderabad file);
awaiting owner in-game timing (`gradle jarJar` → run →
`/terracheck`, compare against batch-3's 166 s).

## The problem (owner's batch-3 log)

- One cold quadkey = 53.3 MB served over ONE connection:
  628,630 lines, **166 s** end-to-end (~0.32 MB/s).
- Refill tiles waited on Microsoft (give-up after 6 tries
  was at ~2 min), and a 29 s `Can't keep up!` spike hit
  when the data landed.

## Step 1 — start at warmup (already half-present, made real)

`TerraChunkGenerator.warmDestination` already called
`MicrosoftIndex.prefetchTiles(...)` — but `prefetchTiles`
internally called `query()`, which **waits 500 ms per tile on
the caller's thread** and starts the download only as a side
effect. Rewritten to pure fire-and-forget `triggerFetch(...)`:
the quadkey download now genuinely starts at warmup, in
parallel with terrain generation, and dispatch never stalls
the caller. New log line:
`[TerraStruct] MSFT prefetch: N tiles dispatched (quadkey download starts now, in parallel with generation)`.

## Step 2 — parallel byte-range download

New helpers in `MicrosoftIndex.java`
(`probeQuadkeySize`, `downloadRangePart`,
`downloadQuadkeyGzParallel`):

1. Probe the file size with a 1-byte Range request
   (`Content-Range: bytes 0-0/55838057` — verified live).
2. If ≥ 8 MB, fetch 6 byte slices in parallel to part files,
   stitch in order, verify byte count.
3. The existing batch-3 line-stream parser then reads from
   that local file (durable caches unchanged: decompressed
   `.geojsonl` + region sidecars; the `.gz` vehicle is
   deleted after parsing).
4. ANY failure (no Range support, slice failure, size
   mismatch) returns null and falls back to the proven
   single-connection stream — never worse than batch 3.
5. `/terracheck` now shows the byte phase:
   `DOWNLOADING (parallel x6) quadkey=... 12.3/53.3 MB ageSec=N`.

## Sandbox proof (real Java, live server, 2026-11-18)

- Parallel download of quadkey 123301331: **53.3 MB in
  ~0.7–1 s** (sandbox pipe is fast; stitching verified
  byte-identical to the sequential download beforehand).
- Full query path with disk cache: parallel download +
  628,630-line parse + Charminar answer in **4.5 s**
  (274 in window, 120 in the chunk bbox).
- Fallback path (disk disabled): single stream, 628,630
  lines in 6 s, same 120 buildings — old road intact.
- Owner-side expectation: the gain depends on whether the
  0.32 MB/s was per-connection throttling (common for
  single-flow cross-continent transfers) — worst case it
  stays ~166 s; expected case 2–4× faster, and the warmup
  start hides most of it regardless.

## In-game proof (owner log 2026-11-18 21:29–21:31)

Fresh world, cache marker FRESH (17.5 MB at first check),
spawn Hyderabad:

- 21:30:24 `/terracheck`: `MICROSOFT VERDICT:
  DOWNLOADING NOW — DOWNLOADING (parallel x6)
  quadkey=123301331 0.3/53.3 MB ageSec=1`.
- 21:30:46: `MICROSOFT parallel download quadkey
  123301331: 53.3 MB in 24s (2.22 MB/s, 6 connections)
  — now parsing`; parse done 628,630 lines in 26 s
  (old single-stream road: 166 s for the same file).
- 21:30:56 second `/terracheck`: `MICROSOFT VERDICT:
  BUILDINGS HERE (10)`, tilesLoaded=true,
  totalBuildings=2257; SOURCE STACK osm=79 vida=29
  microsoft=10.

Verdict: Step 2 parallel download is PROVEN in-game,
not just in the sandbox. The 24 s is the owner's real
network (2.22 MB/s aggregate); the mechanism works and
the old 166 s wait is gone.

## Not done here (deliberately)

- The 29 s landing spike belongs to the known Finding-1
  pattern (blocking fetches on chunk threads), not to the
  download; pacing that is a separate, riskier change.
- Step 3 (taking Microsoft buildings via VIDA/Overture and
  retiring the direct door) needs the coverage comparison
  first — VIDA's Microsoft copy is older than the direct
  2026 feed, so this stays a test-then-decide step.
