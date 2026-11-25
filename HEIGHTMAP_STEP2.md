# Heightmap Step 2 — workshop prototypes (2026-11-18)

**Verdict: BOTH prototypes pass. The spike guard v3c is
surgical — it removed every monster pixel and touched
literally zero pixels anywhere else. Java opens
Mapterhorn's WebP door with pure-Java code, no natives.**

## Step 2a — spike guard

Disease anatomy (found by v1/v2 failures — the reason we
prototype): the monsters are NOT single pixels. Dead Sea:
21 crazy pixels in 3-px clusters at the shoreline. Caspian
Sea: one full garbage ROW of 1,165 px (values −5,535.3,
some −32,768 = raw encoding zero) along a tile seam.
A simple neighbour-median guard (v1) repaired only 17/21
and eroded patch edges without killing patches.

**Guard v3c (the winner)** — zoom-anchor with gates. A
fine (z14) pixel is replaced by its coarse (z10) value
only when ALL hold:
- coarse 3×3 neighbourhood is flat (range < 25 m), AND
- the pixel is a monster-scale PIT (fine < coarse − 150 m)
  — spikes additionally require the area-agreement gate
  (16×16 fine block median within 15 m of coarse),
then an iterated 3×3 neighbour-median pass (60 m) for
singles.

Measured (tools/spike_guard_v3c.py):

| Box | Before | Anchor+med8 changed | After |
|---|---|---|---|
| Dead Sea water | min −5,226.9 | **21 (= exactly the 21 crazy px)** | min −416.5, median −412.0 |
| Caspian Sea water | min −5,535.3 | 1,165 (= the seam row) | **perfectly flat −29.0** (1.8 M px) |
| Grand Canyon | — | **0** | byte-identical |
| the Alps | — | **0** | byte-identical |
| Everest | max 8,749.2 | **0** | summit intact |
| Manhattan | median 16.9 | **0** | byte-identical |
| Amsterdam / Flevoland / Tokyo | — | **0** | byte-identical |

Why the gates matter (v3 without them REGRESSED
Manhattan: Terrarium's own coarse zoom carries rooftops
there — median 61 m — and naive anchoring imported them
into the fine layer, max 24.3 → 72.9). Gate design notes
for Step 3:
- Cross-tile neighbours: the Caspian disease is a seam
  ROW, so the game-side guard must see across tile
  borders (decode with a 1-px halo, or guard where the
  sampler stitches tiles). Per-tile-isolated guarding
  would miss exactly the worst case.
- The game already fetches coarse zooms (bathymetry
  fallback), so the anchor data is on the premises.
- Tokyo-class shallow pits (−12.9 m, ~35 m deep, single
  px) are intentionally untouched: below monster scale;
  bilinear sampling + existing outlier repair soften them.
- Guard should log repairs ([TerraStruct]-style counter)
  so /terracheck can report "guard repaired N px".

## Step 2b — Mapterhorn door (Java)

Harness `/tmp/webpharness/MapterhornDoor.java`, JDK 17,
TwelveMonkeys ImageIO WebP **3.15.3** (imageio-webp +
core/metadata/common — **pure Java, no native libs**):

```
ImageIO readers: [..., webp, WEBP, ...]  WEBP reader present: true
fetched 34244 bytes from https://tiles.mapterhorn.com/12/2452/1670.webp
decoded 512x512 in 111 ms  min=-432.0 median=-432.0 max=284.0
DOOR: OPEN - heights match Python truth (flat -432 Dead Sea)
```

Byte-exact vs the Python decode. Step-3 integration
notes if the hybrid is ever approved: add the jars as
jarJar dependencies (the mod already builds jarJar),
register the ImageIO plugin at mod init, reuse the
existing tile disk cache. Seam measurements from the
race step: book offset Amsterdam −4.6 m median (p95
|diff| 8.9) → a ~16 px cross-fade band at book boundaries
would be needed; Hyderabad offset only −0.2 m; India is
served by Mapterhorn's coarse z12 layer (507.5 m ✓).

## Recommendation into Step 3

> **OWNER DIRECTIVE (2026-11-18): Terrarium is NEVER
> removed.** The guard cleans numbers in memory at decode
> time only — cached tile files on disk stay pristine —
> and sits behind an on/off switch (`/terra heights
> guard on|off`, same family as the building-source
> switch). Guard OFF = 100 % original Terrarium behaviour,
> instantly. Mapterhorn, if ever approved later, is an
> optional layer ON TOP; Terrarium remains the base and
> the ocean book regardless. Owner can revert at any time
> if not satisfied.

1. **Guard v3c into the Terrarium load path** (disease
   cure; zero measured collateral). Behind the existing
   style of switch/logging; oceans unchanged.
2. Mapterhorn hybrid: OPTIONAL, later, separate owner
   decision — quality layer, not a cure.
3. Copernicus: stays the offline truth-checker
   (statistics API), not the game's book.
