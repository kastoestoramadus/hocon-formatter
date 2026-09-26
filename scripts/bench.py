#!/usr/bin/env python3
"""Runs the formatter benchmarks and keeps their history per commit, in git notes.

  scripts/bench.py run       time every phase on every platform, plus CLI start-up, and attach
                             the results to HEAD
  scripts/bench.py report    medians over the last commits, flagging the ones that slowed down

Results live in `refs/notes/benchmarks`: attached to commits without changing them, shared with
`git push origin refs/notes/benchmarks`, and kept only for the last few commits and runs. Timings
depend on the machine, so each run records a fingerprint and only same-machine runs are compared.
To keep notes across rebase and amend: `git config notes.rewriteRef refs/notes/benchmarks`.
"""

import argparse
import json
import os
import platform
import statistics
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NOTES = "refs/notes/benchmarks"
TYPICAL = """# Service settings
include "defaults.conf"
service {
    name = "billing"
  port = 8080
}
db.url = "jdbc:postgresql://localhost/billing"
"""


def git(*args: str, check: bool = True) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, check=check).stdout


def machine() -> str:
    """Stable across runs on one machine; CI sets BENCH_MACHINE, since its host names are not."""
    if "BENCH_MACHINE" in os.environ:
        return os.environ["BENCH_MACHINE"]
    cpu = platform.processor()
    cpuinfo = Path("/proc/cpuinfo")
    if cpuinfo.exists():
        cpu = next((l.split(":", 1)[1].strip() for l in cpuinfo.read_text().splitlines() if l.startswith("model name")), cpu)
    return f"{platform.node()}/{cpu}/{os.cpu_count()} cores"


def sbt(*commands: str) -> str:
    return subprocess.run(["sbt", "-batch", *commands], cwd=ROOT, capture_output=True, text=True, check=True).stdout


def phase_results() -> list:
    out = sbt("benchJVM/run", "benchJS/run", "benchNative/run")
    lines = (l.removeprefix("[info] ").strip() for l in out.splitlines())
    return [json.loads(l) for l in lines if l.startswith('{"platform"')]


def startup_results(runs: int = 20) -> list:
    """What a pre-commit hook or a shell user waits for: one process, one small file."""
    sbt("cliNative/nativeLink", "cliJS/fullLinkJS")
    classpath = sbt("export cliJVM/Runtime/fullClasspath").strip().splitlines()[-1]
    commands = {
        "native": [str(ROOT / "cli/.native/target/scala-3.8.2/hocon-formatter")],
        "js": ["node", str(ROOT / "cli/.js/target/scala-3.8.2/hocon-formatter-cli-opt/main.js")],
        "jvm": ["java", "-cp", classpath, "ww86.hocon_fmt.CmdApi"],
    }
    results = []
    with tempfile.TemporaryDirectory() as tmp:
        config = Path(tmp) / "application.conf"
        config.write_text(TYPICAL)
        for name, command in commands.items():
            samples = []
            for _ in range(runs):
                start = time.perf_counter_ns()
                subprocess.run([*command, "--check", str(config)], capture_output=True)
                samples.append((time.perf_counter_ns() - start) / 1000)
            samples.sort()
            results.append({"platform": name, "scenario": "typical", "phase": "cli-check", "samples": runs,
                            "min_us": samples[0], "median_us": statistics.median(samples),
                            "p90_us": samples[int(0.9 * runs)]})
    return results


def notes_for(commit: str) -> list:
    return [json.loads(l) for l in git("notes", "--ref", NOTES, "show", commit, check=False).splitlines() if l]


def store(record: dict, keep_runs: int, keep_commits: int) -> None:
    commit = record["commit"]
    runs = (notes_for(commit) + [record])[-keep_runs:]
    git("notes", "--ref", NOTES, "add", "-f", "-m", "\n".join(json.dumps(r) for r in runs), commit)
    recent = set(git("rev-list", f"--max-count={keep_commits}", "HEAD").split())
    for line in git("notes", "--ref", NOTES, "list", check=False).splitlines():
        annotated = line.split()[1]
        if annotated not in recent:
            git("notes", "--ref", NOTES, "remove", annotated)


def run(args) -> None:
    if git("status", "--porcelain", "--untracked-files=no").strip():
        print("warning: uncommitted changes; the results describe the working tree, not HEAD", file=sys.stderr)
    record = {
        "commit": git("rev-parse", "HEAD").strip(),
        "date": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "machine": machine(),
        "results": phase_results() + startup_results(),
    }
    if args.store:
        store(record, args.keep_runs, args.keep_commits)
    print(json.dumps(record) if args.json else f"{len(record['results'])} timings recorded for {record['commit'][:10]}")


def medians(commit: str, host: str) -> dict:
    """Per (platform, scenario, phase): the median over this commit's runs on this machine."""
    per_key: dict = {}
    for run_record in notes_for(commit):
        if run_record["machine"] == host:
            for r in run_record["results"]:
                per_key.setdefault((r["platform"], r["scenario"], r["phase"]), []).append(r["median_us"])
    return {k: statistics.median(v) for k, v in per_key.items()}


def report(args) -> None:
    host = machine()
    commits = [c for c in git("rev-list", f"--max-count={args.commits}", "HEAD").split() if notes_for(c)]
    if not commits:
        sys.exit(f"no benchmark notes on the last {args.commits} commits; run `scripts/bench.py run`")
    history = [(c, medians(c, host)) for c in reversed(commits)]
    keys = sorted({k for _, m in history for k in m if k[2] in args.phases})

    print(f"median ms, machine {host}; change against the previous measured commit, "
          f"! over {args.threshold:.0%} and {args.min_ms} ms")
    print("".ljust(30) + "".join(f"{c[:8]:>18}" for c, _ in history))
    regressions = []
    for key in keys:
        cells, before = [], None
        for commit, values in history:
            value = values.get(key)
            if value is None:
                cells.append("-".rjust(18))
                continue
            change = "" if before is None else f" {value / before - 1:+.0%}"
            # Relative change alone flags noise on phases that take microseconds.
            slower = before is not None and value > before * (1 + args.threshold) and value - before > args.min_ms * 1000
            if slower:
                regressions.append((commit, key, before, value))
            cells.append(f"{value / 1000:.2f}{change}{' !' if slower else '  '}".rjust(18))
            before = value
        print("/".join(key).ljust(30) + "".join(cells))
    print()
    for commit, _ in history:
        print(git("log", "-1", "--format=%h  %s", commit).strip())
    for commit, key, before, after in regressions:
        print(f"slower: {'/'.join(key)} {before / 1000:.2f} -> {after / 1000:.2f} ms at {commit[:8]}")
    if regressions and args.fail:
        sys.exit(1)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(required=True)
    run_parser = commands.add_parser("run", help="measure and attach the results to HEAD")
    run_parser.add_argument("--no-store", dest="store", action="store_false")
    run_parser.add_argument("--json", action="store_true", help="print the whole record")
    run_parser.add_argument("--keep-runs", type=int, default=3, help="runs kept per commit")
    run_parser.add_argument("--keep-commits", type=int, default=30, help="commits kept")
    run_parser.set_defaults(action=run)
    report_parser = commands.add_parser("report", help="compare the last commits")
    report_parser.add_argument("--commits", type=int, default=10)
    report_parser.add_argument("--phases", nargs="+", default=["format", "cli-check"])
    report_parser.add_argument("--threshold", type=float, default=0.15)
    report_parser.add_argument("--min-ms", type=float, default=0.05, help="ignore smaller slowdowns")
    report_parser.add_argument("--fail", action="store_true", help="exit 1 on a slowdown")
    report_parser.set_defaults(action=report)
    args = parser.parse_args()
    args.action(args)


if __name__ == "__main__":
    main()
