# Heightmap Championship — Result (2026-11-18, offline, no game code touched)

**Verdict: NO single winner. The current book (Terrarium)
is far better on land than feared — its land levels match
Copernicus to ~0.1–2 m almost everywhere. Its one real
disease is rare monster pixels on flat water. Prescription:
cure the disease in code (spike guard), keep Terrarium as
base + ocean book, treat Mapterhorn as an optional quality
layer. Copernicus as sole book: REJECTED by numbers.**

Method: 16 places × 3 books, same boxes. Script
`tools/height_race.py`, raw numbers
`tools/height_race_results.json` (Mapterhorn z13, with z12
fill-ins where z13 doesn't exist).

## Scorecard

| Category | Terrarium (current) | Mapterhorn | Copernicus GLO-30 | Winner |
|---|---|---|---|---|
| Land accuracy (Hyderabad median) | 507.8 m | 507.5 (z12) | 507.8 | **TIE** |
| Peaks (Kilimanjaro max / Everest max) | 5886.9 / 8749.2 | 5884.5 / 8735.5 | 5886.8 / 8737.8 | **TIE** |
| Flat water (Dead Sea spikes >60 m) | **0.007 %**, worst pixel **−5,226.9 m** | 0.000 % | 0.000 % (std 0.08) | Challengers |
| Flat water (Caspian Sea) | min **−5,535.3 m** monster pixel | perfectly flat −28.0 | perfectly flat −28.0 | Challengers |
| City ground (Manhattan median) | 16.9 m (bare) | 16.8 m (bare) | **61.4 m, max 186.3 — rooftops!** | Terrarium/Mapterhorn |
| City ground (Amsterdam median/spread) | 5.9 / 9.6 | **1.0 / 2.2** (NL ground model) | 6.3 / 17.4, max 25.4 | Mapterhorn |
| City ground (Tokyo spread p98−p2) | 42.6, min −12.9 (pit pixel) | **27.8** | 38.4 | Mapterhorn |
| Steep terrain (Canyon/Alps spikes) | 0.000 % | 0.000 % | agrees ≤2 m | **TIE** |
| Amazon canopy | 46.5 (tree tops) | 50.5 | 47.7 | TIE (all read canopy) |
| Oceans (Mariana/Bengal) | z8: **−10,389.7 / −2,729.2** ✓ | no tiles | no items | **Terrarium only** |
| Coverage | everywhere, fine zoom | global at z12; finer only in data-rich countries (India = coarse layer) | all land | Terrarium |
| Freshness (Dead Sea level) | −412 (oldest) | −432 | −427 | Challengers (real ≈ −439) |

## What the race proved

1. **The "old book" is not inaccurate on land.** Hyderabad
   507.8 = 507.8 vs Copernicus; Kilimanjaro medians within
   2 m; Everest max actually highest in Terrarium. A book
   swap would NOT move your ground in most places.
2. **The dirt is narrow and specific:** monster pixels on
   flat inland seas (Dead Sea −5,227 m, Caspian −5,535 m —
   0.007–0.064 % of pixels) plus rare pit pixels (Tokyo
   −12.9). Steep terrain is clean in ALL books (0.000 %
   spikes in the Grand Canyon and the Alps) — so a spike
   guard cannot damage real mountains.
3. **Copernicus alone would make tower cities WORSE:**
   Manhattan's median ground becomes 61 m of rooftops
   (Terrarium/Mapterhorn: ~17 m bare). Only FABDEM fixes
   that, and its licence (CC BY-NC-SA) blocks it.
4. **Mapterhorn is genuinely better ground where it has
   national data** (Amsterdam 1.0 m median, flattest
   everywhere it competed) — but India rides its coarse
   z12 layer (Hyderabad 507.5 ✓ correct, coarser pixels),
   it's WebP (Java needs a decoder), community CDN, and
   mixing books tile-by-tile risks ~5 m boundary steps
   (Amsterdam book delta measured).
5. **Nobody else has oceans.** Terrarium's coarse-zoom
   bathymetry (−10,389.7 m Mariana, −2,729.2 m Bengal vs
   the game's −2,730.6) stays the ocean book in every
   future.

## Prescription (revised plan)

- **Step 2a (workshop): spike-guard prototype** — at tile
  load, any pixel >60 m from its neighbours' median is
  replaced by that median; prove on today's rasters that
  Dead Sea/Caspian monsters die and Canyon/Alps relief is
  byte-identical. This cures the measured disease with
  the current book, zero coverage risk.
- **Step 2b (workshop): Mapterhorn door test** — Java
  WebP decode harness (like the Overture/Microsoft
  proofs), Charminar z12 detail check, tile-boundary
  seam measurement. Only if clean does it become a
  candidate "preferred layer, Terrarium fills gaps".
- **Step 3 (game, only on owner go):** guard in
  `TerrariumHeightmapDataset` load path (+ the −50
  sentinel armour if separately approved); optional
  Mapterhorn layer behind a switch; oceans unchanged.
- Copernicus: kept as reference/verification source
  (its statistics API is a free truth-checker), not as
  the game's book.
