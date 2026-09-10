#!/usr/bin/env python3
# ============================================================
# terra_log_analyzer.py — scores a Terra++ test run from logs.
#
# Usage:
#   python terra_log_analyzer.py path/to/latest.log
#   python terra_log_analyzer.py logs/latest.log --phase cold
#
# Reads a Minecraft/Forge latest.log (or debug.log) and produces
# a scorecard: health issues, performance stats, per-phase
# pass/fail against the TESTING_PLAYBOOK. Pure stdlib, works on
# Windows/Linux/Mac, no dependencies.
# ============================================================
import re
import sys
import argparse
from collections import Counter, defaultdict

# ── PATTERNS ─────────────────────────────────────────────────
PAT = {
    # health
    "cant_keep_up":     re.compile(r"Can't keep up!.*?(\d+)ms or (\d+) ticks"),
    "exception":        re.compile(r"(?:Exception|Error)(?::|\b)(?!.*(?:Ignored|expected))", re.I),
    "stacktrace":       re.compile(r"^\s+at [\w.$]+\("),
    "oom":              re.compile(r"OutOfMemoryError"),
    "watchdog":         re.compile(r"single tick took|Considering it to be crashed"),
    # network / cold start
    "blocking_timeout": re.compile(r"BLOCKING TIMEOUT.*?(\d+)/(\d+) tiles in (\d+)ms"),
    # appetizer protocol (cold-start progressive serve)
    "appetizer_served": re.compile(r"APPETIZER served chunk \[(-?\d+),(-?\d+)\] \((\d+) fast-source features"),
    "main_course":      re.compile(r"MAIN COURSE arrived for tile \[(.*?)\]: (\d+) chunks to swap, (\d+) deferred"),
    "appetizer_full":   re.compile(r"APPETIZER ledger full"),
    # Overture appetizer kitchen (partial-serving rewrite)
    "overture_area":    re.compile(r"OVERTURE area \[.*?\]: (\d+) structures from (\d+)/(\d+) tiles in (\d+)ms( \(partial serve\))?"),
    "overture_starved": re.compile(r"OVERTURE area \[.*?\]: 0/(\d+) tiles resident after (\d+)ms"),
    "overture_prefetch": re.compile(r"OVERTURE prefetch \[.*?\]: (\d+) tiles dispatched"),
    "overture_tile_fail": re.compile(r"OVERTURE tile \S+ FAIL"),
    # NOTE: a COLD WARMUP near lat~0..3,lon~-6..0 at boot is the
    # world spawn (projection origin, Gulf of Guinea) — expected on
    # every cold start, NOT a wrong-location warmup bug.
    "warmup_spawn":     re.compile(r"COLD WARMUP \[(-?\d\.\d+),(-?[0-5]\.\d+)\]"),
    # loading-screen fix: spawn ocean chunks skip all data sources
    "ocean_fastpath":   re.compile(r"OCEAN FASTPATH \[(-?\d+),(-?\d+)\]"),
    # elevation prefetch (teleport/terrain fix) + timejump burst drain
    "elev_prefetch":    re.compile(r"ELEV PREFETCH \[.*?\]: (\d+) height tiles"),
    "engine_burst":     re.compile(r"REFRESH_ENGINE burst: budget (\d+)ms"),
    # cold-start root cause: absolute cache root (must appear once per
    # session; if the path differs between sessions, THAT is the cache
    # vanishing mystery solved)
    "cache_root":       re.compile(r"CACHE ROOT: (.+?) \(via (.+?), cwd was (.+?)\)"),
    # phase-2 fast-download: whole batch in one api.osm.org GET
    "batch_via_api":    re.compile(r"BATCH via api\.osm\.org \[.*?\]: one request"),
    # run-16 forensics: does the cache disk survive between sessions?
    "cache_survivor":   re.compile(r"CACHE MARKER: SURVIVOR"),
    "cache_fresh":      re.compile(r"CACHE MARKER: FRESH"),
    "cache_census":     re.compile(r"CACHE CENSUS: (\S+) = (\d+) files, (\d+) KB"),
    # Overture demotion: appetizer armed only when OSM struggles
    "overture_armed":   re.compile(r"WARMUP: OSM struggling — Overture appetizer armed"),
    "batch_fetch":      re.compile(r"BATCH fetch \[.*?\]: (\d+) tiles in one request"),
    "osm_struct_tile":  re.compile(r"OSM STRUCT \[.*?\]: (\d+) features"),
    "endpoint_fail":    re.compile(r"Endpoint (\d+) returned (\d+)|Struct fetch failed|Batch fetch failed"),
    "osm_pause":        re.compile(r"Pausing OSM struct for (\d+)s"),
    "rate_penalize":    re.compile(r"(429|504)"),
    "gzip_missing":     re.compile(r"content-encoding.*identity", re.I),
    # generation
    # loading-screen metric: vanilla spawn-prep wall time.
    # >20000ms = MixinSpawnPrep did NOT apply (mapping mismatch);
    # ~1000-4000ms = applied.
    "spawn_prep_ms":    re.compile(r"Time elapsed: (\d+) ms"),
    # api.osm.org primary source (fast-download fix)
    "osm_via_api":      re.compile(r"OSM STRUCT \[.*?\]: (\d+) features \(api\.osm\.org\)"),
    "gen_init":         re.compile(r"TerraChunkGenerator: proj=(\S+) zoom=(\d+).*?universe=(\S+)"),
    "df_init":          re.compile(r"TerraDensityFunctions: zoom=(\d+) projection=(\w+).*?universe=(\S+)"),
    "projection_fail":  re.compile(r"BTE projection FAILED"),
    "elev_fallback":    re.compile(r"Elevation fallback at"),
    # time travel
    "timejump_start":   re.compile(r"TIMEJUMP_START targetYear=(-?\d+)"),
    "timejump_done":    re.compile(r"TIMEJUMP_DONE actualYear=(-?\d+).*?pass1Queued=(\d+)"),
    "year_advance":     re.compile(r"Year advanced: (.+?) → (.+?) \("),
    "struct_retry":     re.compile(r"STRUCT_RETRY"),
    "tick_stats":       re.compile(r"Tick stats: q=(\d+) def=(\d+).*?placed=(\d+)c/(\d+)b"),
    # world-line (axes 5/6)
    "branch_create":    re.compile(r"Branch created: (\S+) \(diverged (-?\d+)"),
    "branch_switch":    re.compile(r"WORLDLINE branch switch: (\S+) -> (\S+)"),
    "universe_travel":  re.compile(r"UNIVERSE travel: (\S+) -> (\S+)"),
    "worldline_load":   re.compile(r"worldline loaded: (\d+) branches, active=(\S+)"),
    # paradox
    "paradox_violation":re.compile(r"PARADOX violation by (\S+): .*?stress=(\d+)"),
    "rupture":          re.compile(r"REALITY RUPTURE by (\S+): '(.+?)' year (-?\d+) -> (\S+) forged"),
    "ohm_parse_fail":   re.compile(r"OHM parse failed"),
    "msft_reparse":     re.compile(r"MSFT quadkey \d+ from disk"),
    "audit_stall":      re.compile(r"TIMEJUMP_UPDATE_AUDIT.*?complete=(\d+) queued=(\d+)"),
    "engine_tick":      re.compile(r"REFRESH_ENGINE tick: spent=(\d+)ms.*?depth=(\d+) done=(\d+) failed=(\d+)"),
    "engine_giveup":    re.compile(r"REFRESH_ENGINE gave up"),
    # boot
    "mod_load":         re.compile(r"terraplusplus", re.I),
    "server_done":      re.compile(r'Done \(([\d.]+)s\)! For help'),
}

def analyze(path):
    counters = Counter()
    detail = defaultdict(list)
    lineno = 0
    try:
        f = open(path, encoding="utf-8", errors="replace")
    except OSError as e:
        print(f"cannot open {path}: {e}")
        sys.exit(2)
    with f:
        for line in f:
            lineno += 1
            for key, pat in PAT.items():
                m = pat.search(line)
                if not m:
                    continue
                counters[key] += 1
                if key in ("cant_keep_up", "blocking_timeout", "oom",
                           "timejump_start", "timejump_done", "rupture",
                           "branch_switch", "universe_travel", "gen_init",
                           "df_init", "osm_pause", "server_done",
                           "worldline_load", "paradox_violation",
                           "batch_fetch", "watchdog", "tick_stats",
                           "audit_stall",
                           "engine_tick", "main_course"):
                    detail[key].append((lineno, line.strip()[:200], m.groups()))
    return counters, detail, lineno

def fmt_section(title):
    print("\n" + "=" * 60)
    print(title)
    print("=" * 60)

def verdict(ok, warn=False):
    return "PASS" if ok else ("WARN" if warn else "FAIL")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("log", help="path to latest.log / debug.log")
    ap.add_argument("--phase", default="all",
        choices=["all", "boot", "cold", "structures", "time",
                 "worldline", "paradox", "soak"])
    args = ap.parse_args()

    c, d, total_lines = analyze(args.log)
    score = {"PASS": 0, "WARN": 0, "FAIL": 0}

    def report(name, v, msg):
        score[v] += 1
        mark = {"PASS": "[PASS]", "WARN": "[WARN]", "FAIL": "[FAIL]"}[v]
        print(f"  {mark} {name}: {msg}")

    print(f"terra_log_analyzer — {args.log} ({total_lines} lines)")

    # ── PHASE 1: BOOT ──
    if args.phase in ("all", "boot"):
        fmt_section("PHASE 1 — BOOT")
        report("mod loaded", verdict(c["mod_load"] > 0),
               f"{c['mod_load']} terraplusplus mentions")
        if c["server_done"]:
            t = float(d["server_done"][0][2][0])
            report("server started", verdict(True), f"in {t:.1f}s"
                   + (" (slow)" if t > 120 else ""))
        else:
            report("server started", "WARN", "no 'Done' line found")
        report("projection", verdict(c["projection_fail"] == 0),
               "FAILED to load" if c["projection_fail"]
               else "BTE projection OK")
        gens = {g[2][2] for g in d.get("gen_init", [])}
        report("generators", verdict(c["gen_init"] > 0),
               f"{c['gen_init']} inits, universes: {sorted(gens) or 'none seen'}")
        report("crashes", verdict(c["oom"] == 0 and c["watchdog"] == 0),
               f"OOM={c['oom']} watchdog={c['watchdog']}")

    # ── PHASE 2: COLD START / NETWORK ──
    if args.phase in ("all", "cold"):
        fmt_section("PHASE 2 — COLD START & NETWORK")
        report("batched fetches used",
               verdict(c["batch_fetch"] > 0, warn=True),
               f"{c['batch_fetch']} batch requests "
               f"({sum(int(g[2][0]) for g in d.get('batch_fetch', []))} tiles)")
        bt = c["blocking_timeout"]
        report("blocking timeouts", verdict(bt == 0, warn=bt <= 5),
               f"{bt} (each = a stalled chunk retry)")
        report("endpoint failures", verdict(c["endpoint_fail"] < 10,
               warn=c["endpoint_fail"] < 50),
               f"{c['endpoint_fail']} failures, {c['osm_pause']} full pauses")
        report("tiles fetched", verdict(True),
               f"{c['osm_struct_tile']} single-tile responses")
        # appetizer protocol: served count should be matched by
        # main-course swaps before session end (small residue OK —
        # it heals via the deferred ledger / refresh index)
        served = c["appetizer_served"]
        swaps = sum(int(g[2][1]) for g in d.get("main_course", []))
        if served or swaps:
            report("appetizer -> main course",
                   verdict(swaps >= served * 0.7 or served < 5,
                           warn=True),
                   f"{served} chunks served provisionally, "
                   f"{swaps} swapped to OSM "
                   f"({c['main_course']} tile arrivals)")
        report("appetizer ledger overflow",
               verdict(c["appetizer_full"] == 0, warn=True),
               f"{c['appetizer_full']}")
        # Overture appetizer kitchen health
        ov_ok = c["overture_area"]
        ov_starve = c["overture_starved"]
        ov_fail = c["overture_tile_fail"]
        if ov_ok or ov_starve or ov_fail:
            partial = sum(1 for g in d.get("overture_area", [])
                          if g[2][4])
            report("overture appetizer",
                   verdict(ov_ok > 0 and ov_starve == 0,
                           warn=ov_ok > 0),
                   f"{ov_ok} serves ({partial} partial), "
                   f"{ov_starve} starved, "
                   f"{ov_fail} tile failures")
        if c["overture_prefetch"]:
            report("overture prefetch", verdict(True),
                   f"{c['overture_prefetch']} dispatch bursts "
                   f"(3x3 neighborhood)")

    # ── PHASE 3: STRUCTURES ──
    if args.phase in ("all", "structures"):
        fmt_section("PHASE 3 — STRUCTURES")
        placed = [g[2] for g in d.get("tick_stats", [])]
        if placed:
            last = placed[-1]
            report("placement progress", verdict(int(last[3]) > 0),
                   f"final tick-stats: {last[2]} chunks / {last[3]} blocks placed")
        else:
            report("placement progress", "WARN",
                   "no 'Tick stats' lines (short run?)")
        report("struct retries (water sync)",
               verdict(c["struct_retry"] < 100, warn=True),
               f"{c['struct_retry']} STRUCT_RETRY lines")

    # ── PHASE 4: TIME TRAVEL ──
    if args.phase in ("all", "time"):
        fmt_section("PHASE 4 — TIME TRAVEL")
        starts, dones = c["timejump_start"], c["timejump_done"]
        report("time jumps completed", verdict(starts == dones,
               warn=dones > 0),
               f"{starts} started / {dones} completed")
        for _, _, g in d.get("timejump_done", []):
            print(f"        jump -> year {g[0]}, {g[1]} chunks queued")
        audits = [g[2] for g in d.get("audit_stall", [])]
        if len(audits) >= 3:
            tail = audits[-3:]
            stalled = all(int(a[0]) == 0 and int(a[1]) > 0
                          for a in tail)
            report("refresh queue draining",
                   verdict(not stalled),
                   ("STALLED — last 3 audits complete=0 with "
                    + tail[-1][1] + " queued") if stalled
                   else f"last audit complete={audits[-1][0]} "
                        f"queued={audits[-1][1]}")
        ticks = [g[2] for g in d.get("engine_tick", [])]
        if ticks:
            worst = max(int(t[0]) for t in ticks)
            lastd = ticks[-1]
            report("refresh engine",
                   verdict(worst <= 12, warn=worst <= 25),
                   f"{len(ticks)} active ticks, worst spent={worst}ms, "
                   f"final depth={lastd[1]} done={lastd[2]} failed={lastd[3]}")
            report("engine give-ups",
                   verdict(c["engine_giveup"] == 0, warn=True),
                   f"{c['engine_giveup']}")
        report("OHM parse failures",
               verdict(c["ohm_parse_fail"] == 0, warn=True),
               f"{c['ohm_parse_fail']} (bad Overpass body — "
               "retryable after fix)")

    # ── PHASE 5: WORLD-LINE ──
    if args.phase in ("all", "worldline"):
        fmt_section("PHASE 5 — WORLD-LINE (axes 5+6)")
        report("registry loaded", verdict(True),
               f"{c['worldline_load']} loads "
               + str([g[2] for g in d.get('worldline_load', [])][:1]))
        report("branch switches", verdict(True),
               f"{c['branch_switch']}: "
               + ", ".join(f"{g[2][0]}->{g[2][1]}"
                           for g in d.get("branch_switch", [])[:5]))
        report("universe travels", verdict(True),
               f"{c['universe_travel']}: "
               + ", ".join(f"{g[2][0]}->{g[2][1]}"
                           for g in d.get("universe_travel", [])[:5]))

    # ── PHASE 6: PARADOX ──
    if args.phase in ("all", "paradox"):
        fmt_section("PHASE 6 — PARADOX")
        report("violations recorded", verdict(True),
               f"{c['paradox_violation']}")
        report("ruptures", verdict(True), f"{c['rupture']}")
        for _, _, g in d.get("rupture", []):
            print(f"        rupture: {g[0]} broke '{g[1]}' "
                  f"@ {g[2]} -> {g[3]}")

    # ── PHASE 7: SOAK / HEALTH ──
    if args.phase in ("all", "soak"):
        fmt_section("PHASE 7 — HEALTH / SOAK")
        cku = c["cant_keep_up"]
        worst = max((int(g[2][0]) for g in d.get("cant_keep_up", [])),
                    default=0)
        report("tick health", verdict(cku == 0, warn=worst < 5000),
               f"{cku} 'Can't keep up' events, worst {worst}ms behind")
        report("exceptions", verdict(c["exception"] < 5,
               warn=c["exception"] < 50),
               f"{c['exception']} exception lines, "
               f"{c['stacktrace']} stack frames")
        report("memory", verdict(c["oom"] == 0),
               "OOM!" if c["oom"] else "no OutOfMemoryError")

    # ── SCORE ──
    fmt_section("SCORECARD")
    print(f"  PASS={score['PASS']}  WARN={score['WARN']}  FAIL={score['FAIL']}")
    if score["FAIL"]:
        print("  ACTION: paste the FAIL lines + surrounding log context "
              "back for a fix pass.")
    elif score["WARN"]:
        print("  Mostly healthy — WARNs are worth a look but not blockers.")
    else:
        print("  Clean run.")
    sys.exit(1 if score["FAIL"] else 0)

if __name__ == "__main__":
    main()
