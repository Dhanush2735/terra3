# The real problem, and why I kept missing it

## Part 1 — You are right

Six turns, six fixes, each aimed at whatever symptom was newest:

| turn | your symptom | my fix | outcome |
|---|---|---|---|
| 1 | slow after Google | grid bucketing | never verified against a running game |
| 2 | OutOfMemory | singleton + retain window | OOM gone, but window mis-sized |
| 3 | one teleport per cold start | R-tree query | correct, but returned 0 buildings |
| 4 | 0 buildings *(my bug)* | real shard index | worked, then retained 2.8M objects |
| 5 | ghost buildings | near-miss dedup | partial |
| 6 | building in the road | road rejection | untested in game |

Turns 3 and 4 are the tell: I introduced a bug, then spent the next turn fixing
my own bug and called it progress. I was doing **symptom chasing** — treat what
is visible, ship, wait for the next report. The loop only ends when the user
runs out of patience, which is roughly where we are.

What I never did: ask which single defect produces *all* of these symptoms.

## Part 2 — The real problem

**Chunk generation performs blocking network I/O on Minecraft's worker threads.**

Seven call sites in `TerraChunkGenerator` do this:

```
Minecraft worker thread
  └─ applyBiomeDecoration()               ← chunk generation
       └─ getStructuresForChunkBlocking(..., 4000 ms)
            └─ parks the thread on an Overpass HTTP request
```

`Worker-Main-*` threads are the pool Minecraft uses to generate chunks. Parking
them on a remote API means chunk generation stops for as long as the API is
slow. Overpass is frequently slow, and the mod's own logs show it returning
HTTP 400 from three endpoints in the same session.

From your last log, one tile:

```
T+20.5 s  BLOCKING TIMEOUT: 0/1 tiles in 4000ms
T+25.1 s  BLOCKING TIMEOUT: 0/1 tiles in 4000ms
   ... 11 timeouts, 4 s each ...
T+54.0 s  *** Can't keep up! 808 ticks behind ***
T+66.1 s  OSM STRUCT arrives: 1779 features
```

**46 seconds for one tile. 44 thread-seconds burned waiting.** The 808-tick
stall lands squarely inside that window — before VIDA or Microsoft do anything.

### The part that makes it unambiguous

A fully asynchronous pipeline **already exists in this codebase and works**:

```
AsyncStructureManager.queueRetry(lat, lon, cx, cz)
   → dedicated retry pool fetches without touching chunk gen
   → StructurePlacementQueue.enqueue(...)
   → StructureTickHandler places 2 chunks/tick on the main thread
```

Your log proves it is faster than the blocking path it falls back from:

```
01:09:05.769  BLOCKING TIMEOUT: 0/1 tiles in 4000ms — null
01:09:05.769  Queued retry for tile [17.4400,78.5400]
01:09:08.113  OSM retry success (attempt 1)
```

**2.3 seconds** on the async path, after the blocking path burned 4 seconds
failing. The generator blocks first, times out, and *then* uses the mechanism
that should have been used from the start.

### Why every one of my fixes was doomed to disappoint

Each one optimised work that happens *after* the stall. The stall is upstream.
Making VIDA 1700× faster cannot help when the thread is parked on an HTTP
request that hasn't returned yet. That is why the tick-debt numbers barely
moved across six turns: 4642 → 3489 → 808. The residue is all Overpass.

### The fix

Make `applyBiomeDecoration` never block. On a cache miss: enqueue and return.
The chunk generates with terrain and no structures; the async path fills them
in a few seconds later — which is exactly what the two-pass refresh system was
built to do.

This is a real change to the generator's contract, not a one-line patch, and I
am not going to make it silently at the end of a long session. It should be its
own piece of work with your sign-off.

## Part 3 — Methods that find real problems

The honest answer to "what methods do you use": on this project I mostly used
one, and it was the weakest one.

**What I did use, and where it works**

*Verify against ground truth instead of assuming.* This was genuinely useful.
Downloading the real `.fgb` header proved the India shard covered
lon 76.46–77.93 while Hyderabad sits at 78.54. That found a real bug no amount
of code reading would have. Same with running actual `javac` instead of trusting
a Java-8 parser that choked on `record`.

*Measure before optimising.* The 200k-feature benchmark, the heap arithmetic,
the metre-vs-degree tolerance tests. Necessary — but I measured the thing I had
already decided to fix.

**What I should have used**

*Follow the timeline, not the newest error.* Symptoms in a log are ordered.
The first bad event is upstream of everything after it. I did build a timeline
in turn 5 and it immediately showed the first stall preceding VIDA by 111
seconds — I noted it and then went back to VIDA anyway.

*Attribute cost before assigning blame.* Ask "where does the wall-clock time
actually go?" before touching code. Eleven 4-second timeouts is 44 seconds; the
VIDA parse I obsessed over was 90 seconds once, then cached forever. I never
compared them.

*Ask what the code already does.* `AsyncStructureManager` existed the entire
time. Six turns of performance work without noticing the codebase already
contained the correct pattern is a research failure, not a coding one.

*Distinguish "made a number better" from "fixed the user's problem."* I reported
1700× and 61× speedups. You still had stalls. A benchmark improving is not
evidence the complaint is resolved.

*Say when a fix is unverified.* I cannot run Minecraft here. Several fixes were
reasoned-correct and never observed working. I should have led with that every
time instead of burying it.

## Part 4 — Concretely, next

1. Make chunk generation non-blocking (the item above). One change, one test.
2. Confirm with the tick-debt number, not with a benchmark.
3. Only then revisit anything to do with buildings.

If the tick debt does not drop after step 1, my diagnosis is wrong and I should
say so rather than move to the next symptom.
