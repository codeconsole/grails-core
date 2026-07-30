# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Final, Sequence


MARKER: Final = "<!-- grails-jmh-benchmark -->"
DASH: Final = "—"
RULER_PACKAGE: Final = "org.apache.grails.benchmarks.ruler."
ALLOCATION_METRIC: Final = "gc.alloc.rate.norm"
BENCHMARK_PACKAGE: Final = "org.apache.grails.benchmarks."
VERDICT_ORDER: Final = {"REGRESSED": 0, "IMPROVED": 1}


@dataclass(frozen=True, slots=True)
class Benchmark:
    identity: str
    score: float | None
    error: float | None
    confidence: tuple[float, float] | None
    unit: str
    mode: str
    allocation: float | None


@dataclass(frozen=True, slots=True)
class ComparisonRow:
    identity: str
    base: Benchmark
    head: Benchmark
    speedup: float | None
    verdict: str
    allocation_delta: float | None
    allocation_percent: float | None
    allocation_candidate: bool


@dataclass(frozen=True, slots=True)
class RulerDeviation:
    shard: str
    identity: str
    speedup: float


@dataclass(frozen=True, slots=True)
class Comparison:
    rows: tuple[ComparisonRow, ...]
    only_head: tuple[str, ...]
    only_base: tuple[str, ...]
    malformed: int
    group_speedups: dict[str, tuple[float, int]]
    ruler_speedups: tuple[tuple[str, float], ...]
    dropped: tuple[str, ...] = ()
    ruler_deviations: tuple[RulerDeviation, ...] = ()
    ruler_incomplete: tuple[str, ...] = ()
    missing_shards: tuple[str, ...] = ()
    paired_shards: tuple[str, ...] = ()


def finite_number(value: float | str | int | None) -> float | None:
    try:
        number = float(value) if value is not None else None
    except (TypeError, ValueError):
        return None
    return number if number is not None and math.isfinite(number) else None


def confidence_interval(metric: dict) -> tuple[float, float] | None:
    confidence = metric.get("scoreConfidence")
    if isinstance(confidence, (list, tuple)) and len(confidence) == 2:
        lower, upper = finite_number(confidence[0]), finite_number(confidence[1])
        if lower is not None and upper is not None:
            return min(lower, upper), max(lower, upper)
    score, error = finite_number(metric.get("score")), finite_number(metric.get("scoreError"))
    if score is None or error is None or error < 0:
        return None
    return score - error, score + error


def benchmark_identity(name: str, params: dict | None) -> str:
    if not params:
        return name
    rendered = ",".join(f"{key}={params[key]}" for key in sorted(params, key=str))
    return f"{name}[{rendered}]"


def parse_entries(entries: list) -> dict[str, Benchmark]:
    benchmarks: dict[str, Benchmark] = {}
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        name, metric = entry.get("benchmark"), entry.get("primaryMetric")
        if not isinstance(name, str) or not isinstance(metric, dict):
            continue
        params = entry.get("params") if isinstance(entry.get("params"), dict) else None
        secondary = entry.get("secondaryMetrics")
        allocation_metric = secondary.get(ALLOCATION_METRIC) if isinstance(secondary, dict) else None
        allocation = None
        if isinstance(allocation_metric, dict) and allocation_metric.get("scoreUnit") == "B/op":
            allocation = finite_number(allocation_metric.get("score"))
        identity = benchmark_identity(name, params)
        mode = entry.get("mode")
        benchmarks[identity] = Benchmark(
            identity=identity,
            score=finite_number(metric.get("score")),
            error=finite_number(metric.get("scoreError")),
            confidence=confidence_interval(metric),
            unit=str(metric.get("scoreUnit", "")),
            mode=mode.strip() if isinstance(mode, str) else "",
            allocation=allocation,
        )
    return benchmarks


def mean_of(values: Sequence[float | None]) -> float | None:
    present = [value for value in values if value is not None]
    return math.fsum(present) / len(present) if present else None


# Pooling spans the observed intervals instead of narrowing them, so shards that disagree widen
# the uncertainty and make a verdict harder to reach, never easier.
def pool_benchmarks(samples: Sequence[Benchmark]) -> Benchmark:
    if len(samples) == 1:
        return samples[0]
    first = samples[0]
    if any(sample.unit != first.unit or sample.mode != first.mode for sample in samples):
        return Benchmark(first.identity, None, None, None, first.unit, first.mode, None)
    intervals = [sample.confidence for sample in samples if sample.confidence is not None]
    confidence = (
        (min(interval[0] for interval in intervals), max(interval[1] for interval in intervals))
        if len(intervals) == len(samples)
        else None
    )
    return Benchmark(
        identity=first.identity,
        score=mean_of([sample.score for sample in samples]),
        error=mean_of([sample.error for sample in samples]),
        confidence=confidence,
        unit=first.unit,
        mode=first.mode,
        allocation=mean_of([sample.allocation for sample in samples]),
    )


def result_paths(location: str) -> list[tuple[str, Path]]:
    path = Path(location)
    return [(result_path.name, result_path) for result_path in sorted(path.glob("*.json"))] if path.is_dir() else [("anonymous shard", path)]


def read_shards(location: str) -> dict[str, dict[str, Benchmark]]:
    shards: dict[str, dict[str, Benchmark]] = {}
    for shard, result_path in result_paths(location):
        with result_path.open(encoding="utf-8") as source:
            parsed = json.load(source)
        if not isinstance(parsed, list):
            raise ValueError(f"JMH JSON must be an array: {result_path}")
        shards[shard] = parse_entries(parsed)
    return shards


def pool_shards(shards: dict[str, dict[str, Benchmark]]) -> dict[str, Benchmark]:
    collected: dict[str, list[Benchmark]] = {}
    for shard in shards.values():
        for identity, benchmark in shard.items():
            collected.setdefault(identity, []).append(benchmark)
    return {identity: pool_benchmarks(samples) for identity, samples in collected.items()}


def read_results(location: str) -> dict[str, Benchmark]:
    return pool_shards(read_shards(location))


def comparable_units(head: Benchmark, base: Benchmark) -> bool:
    return (
        bool(head.mode)
        and bool(base.mode)
        and head.mode.lower() == base.mode.lower()
        and head.unit.strip().lower() == base.unit.strip().lower()
    )


# A revision that changes or omits a benchmark mode, or changes its output unit, makes the scores
# incommensurable, so dividing them would manufacture a speedup out of unrelated measurements.
def speedup_for(head: Benchmark, base: Benchmark) -> float | None:
    if head.score is None or base.score is None or head.score <= 0 or base.score <= 0:
        return None
    if not comparable_units(head, base):
        return None
    return head.score / base.score if head.unit.lower().startswith("ops") else base.score / head.score


def intervals_disjoint(head: Benchmark, base: Benchmark) -> bool:
    if head.confidence is None or base.confidence is None:
        return False
    return head.confidence[1] < base.confidence[0] or base.confidence[1] < head.confidence[0]


def group_for(identity: str) -> str:
    name = identity.split("[", 1)[0]
    parts = name.split(".")
    class_name = parts[-2] if len(parts) >= 2 else parts[-1]
    return parts[-3] if len(parts) >= 3 else class_name


def geometric_mean(values: Sequence[float]) -> float | None:
    if not values or any(value <= 0 or not math.isfinite(value) for value in values):
        return None
    return math.exp(math.fsum(math.log(value) for value in values) / len(values))


def compare_benchmarks(
    head: dict[str, Benchmark], base: dict[str, Benchmark], threshold: float
) -> Comparison:
    common = sorted(head.keys() & base.keys())
    rows: list[ComparisonRow] = []
    malformed = 0
    groups: dict[str, list[float]] = {}
    rulers: list[tuple[str, float]] = []
    regression_limit = 1 / (1 + threshold)
    for identity in common:
        head_benchmark, base_benchmark = head[identity], base[identity]
        speedup = speedup_for(head_benchmark, base_benchmark)
        if speedup is None or not math.isfinite(speedup) or speedup <= 0:
            malformed += 1
            continue
        is_ruler = identity.split("[", 1)[0].startswith(RULER_PACKAGE)
        allocation_delta, allocation_percent = allocation_change(head_benchmark, base_benchmark)
        allocation_candidate = (
            allocation_delta is not None
            and allocation_percent is not None
            and allocation_delta > 16
            and allocation_percent > 0.05
        )
        if is_ruler:
            verdict = "ruler - excluded"
            rulers.append((identity, speedup))
        elif head_benchmark.confidence is None or base_benchmark.confidence is None:
            verdict = "insufficient data"
        elif speedup <= regression_limit and intervals_disjoint(head_benchmark, base_benchmark):
            verdict = "REGRESSED"
        elif speedup >= 1 + threshold and intervals_disjoint(head_benchmark, base_benchmark):
            verdict = "IMPROVED"
        else:
            verdict = "no clear change"
        if not is_ruler:
            groups.setdefault(group_for(identity), []).append(speedup)
        rows.append(
            ComparisonRow(
                identity, base_benchmark, head_benchmark, speedup, verdict,
                allocation_delta, allocation_percent, allocation_candidate,
            )
        )
    grouped = {
        group: (mean, len(values))
        for group, values in sorted(groups.items())
        if (mean := geometric_mean(values)) is not None
    }
    return Comparison(
        tuple(rows), tuple(sorted(head.keys() - base.keys())), tuple(sorted(base.keys() - head.keys())),
        malformed, grouped, tuple(sorted(rulers)),
    )


def ruler_deviation(speedup: float) -> float:
    return max(speedup, 1 / speedup) - 1


# A shard that failed outright uploads no result file at all, so it cannot be discovered from the
# inputs. Without the expected set such a shard vanishes silently and a half-strength comparison
# reads as healthy, so callers pass the shard names the CI matrix was supposed to produce.
def compare_shards(
    head_shards: dict[str, dict[str, Benchmark]], base_shards: dict[str, dict[str, Benchmark]], threshold: float,
    expected_shards: Sequence[str] = (),
) -> Comparison:
    head_samples: dict[str, list[Benchmark]] = {}
    base_samples: dict[str, list[Benchmark]] = {}
    dropped: set[str] = set()
    expected_rulers: set[str] = set()
    paired_shards = sorted(head_shards.keys() & base_shards.keys())
    for shard in paired_shards:
        head, base = head_shards[shard], base_shards[shard]
        common = head.keys() & base.keys()
        dropped.update(head.keys() ^ base.keys())
        expected_rulers.update(
            identity for identity in head.keys() | base.keys()
            if identity.split("[", 1)[0].startswith(RULER_PACKAGE)
        )
        for identity in common:
            head_samples.setdefault(identity, []).append(head[identity])
            base_samples.setdefault(identity, []).append(base[identity])
    for shards in (head_shards, base_shards):
        for shard in shards.keys() - set(paired_shards):
            dropped.update(shards[shard])
    deviations: list[RulerDeviation] = []
    incomplete: list[str] = []
    for shard in paired_shards:
        head, base = head_shards[shard], base_shards[shard]
        for identity in sorted(expected_rulers):
            head_benchmark, base_benchmark = head.get(identity), base.get(identity)
            speedup = (
                speedup_for(head_benchmark, base_benchmark)
                if head_benchmark is not None and base_benchmark is not None
                else None
            )
            if speedup is None or not math.isfinite(speedup) or speedup <= 0:
                incomplete.append(f"{shard}: {identity}")
            else:
                deviations.append(RulerDeviation(shard, identity, speedup))
    comparison = compare_benchmarks(
        {identity: pool_benchmarks(samples) for identity, samples in head_samples.items()},
        {identity: pool_benchmarks(samples) for identity, samples in base_samples.items()},
        threshold,
    )
    missing = {shard for shard in expected_shards if shard not in paired_shards}
    missing.update(
        shard for shard in head_shards.keys() ^ base_shards.keys() if shard not in paired_shards
    )
    return replace(
        comparison,
        dropped=tuple(sorted(dropped)),
        ruler_deviations=tuple(sorted(deviations, key=lambda item: (item.shard, item.identity))),
        ruler_incomplete=tuple(sorted(incomplete)),
        missing_shards=tuple(sorted(missing)),
        paired_shards=tuple(paired_shards),
    )


def allocation_change(head: Benchmark, base: Benchmark) -> tuple[float | None, float | None]:
    if head.allocation is None or base.allocation is None or base.allocation <= 0:
        return None, None
    delta = head.allocation - base.allocation
    return delta, delta / base.allocation


def safe_markdown(value: str) -> str:
    return re.sub(r"[`|<>\[\]\(\)!\r\n]", "", value)


def format_number(value: float | None) -> str:
    return f"{value:.3g}" if value is not None and math.isfinite(value) else DASH


def format_speedup(value: float | None) -> str:
    return f"{value:.2f}x" if value is not None and math.isfinite(value) else DASH


def display_identity(identity: str) -> str:
    return safe_markdown(identity.removeprefix(BENCHMARK_PACKAGE))


def format_score(benchmark: Benchmark) -> str:
    score = format_number(benchmark.score)
    return f"{score} {safe_markdown(benchmark.unit)}" if score != DASH else DASH


# Sub-byte deltas are allocation-counter noise, not a real change in bytes allocated.
def format_allocation(row: ComparisonRow) -> str:
    if row.allocation_delta is None or row.allocation_percent is None:
        return DASH
    if abs(row.allocation_delta) < 1:
        return "~0 B/op"
    candidate = " **candidate**" if row.allocation_candidate else ""
    return f"{row.allocation_delta:+,.0f} B/op ({row.allocation_percent * 100:+.1f}%){candidate}"


def render_report(comparison: Comparison) -> str:
    regressions = sum(row.verdict == "REGRESSED" for row in comparison.rows)
    improvements = sum(row.verdict == "IMPROVED" for row in comparison.rows)
    deviations = comparison.ruler_deviations or tuple(
        RulerDeviation("", identity, speedup) for identity, speedup in comparison.ruler_speedups
    )
    worst_ruler = max(deviations, key=lambda item: ruler_deviation(item.speedup), default=None)
    if comparison.ruler_incomplete:
        health = "INCOMPLETE/unreliable (missing or non-finite ruler measurements)"
    elif worst_ruler is None:
        health = "not measured"
    else:
        location = f" in {safe_markdown(worst_ruler.shard)}" if worst_ruler.shard else ""
        ruler = safe_markdown(worst_ruler.identity.removeprefix(RULER_PACKAGE))
        health = f"worst ruler deviation: {ruler_deviation(worst_ruler.speedup):.1%}{location} ({ruler})"
    lines = [
        "### JMH Benchmark Report",
        "",
        f"**Regressions:** {regressions}",
        f"**Improvements:** {improvements}",
        f"**Runner health:** {health}",
        "Ruler benchmarks are excluded from verdicts and group summaries; runner health is a stability check, not a calibration factor.",
    ]
    if comparison.missing_shards:
        lines.extend([
            "",
            "> **Warning:** No usable base/head pair was produced by "
            + ", ".join(safe_markdown(shard) for shard in comparison.missing_shards)
            + f". This comparison rests on {len(comparison.paired_shards)} shard pair(s) instead of the expected "
            + f"{len(comparison.paired_shards) + len(comparison.missing_shards)}, so the alternating measurement "
            + "order did not fully cancel and the result is weaker than a normal run.",
        ])
    if deviations:
        lines.append(
            "**Ruler movements:** " + ", ".join(
                (f"{safe_markdown(deviation.shard)}: " if deviation.shard else "")
                + f"{safe_markdown(deviation.identity.removeprefix(RULER_PACKAGE))}: "
                + format_speedup(deviation.speedup)
                for deviation in deviations
            )
        )
    if comparison.ruler_incomplete:
        lines.extend([
            "",
            "> **Warning:** Runner health is INCOMPLETE because expected ruler measurements were "
            "missing or non-finite. Treat results as unreliable.",
            "",
            "**Incomplete ruler measurements:** " + ", ".join(
                safe_markdown(issue) for issue in comparison.ruler_incomplete
            ),
        ])
    if any(max(deviation.speedup, 1 / deviation.speedup) > 1.05 for deviation in deviations):
        lines.extend([
            "",
            "> **Warning:** The runner was unstable BETWEEN the two halves of the A/B run. "
            "Treat results as unreliable. Runner health is a stability check, not a calibration factor.",
        ])
    lines.extend(["", "Group geometric means are descriptive only, not verdicts.", ""])
    if comparison.group_speedups:
        lines.extend(["| Group | Descriptive geomean speedup | n |", "| --- | ---: | ---: |"])
        lines.extend(
            f"| {safe_markdown(group)} | {format_speedup(speedup)} | {count} |"
            for group, (speedup, count) in comparison.group_speedups.items()
        )
    else:
        lines.append("No comparable non-ruler benchmarks were available for group summaries.")
    lines.extend(["", "<details>", "<summary>Per-benchmark results</summary>", ""])
    lines.extend([
        "| Benchmark | Base score | Head score | Speedup | Verdict | Allocation delta (ADVISORY) |",
        "| --- | ---: | ---: | ---: | --- | ---: |",
    ])
    lines.extend(
        f"| {display_identity(row.identity)} | {format_score(row.base)} | {format_score(row.head)} | "
        f"{format_speedup(row.speedup)} | {row.verdict} | {format_allocation(row)} |"
        for row in sorted(comparison.rows, key=lambda item: (VERDICT_ORDER.get(item.verdict, 2), item.identity))
    )
    lines.extend(["", "</details>"])
    if comparison.only_head:
        lines.extend(["", "**Only in head:** " + ", ".join(safe_markdown(name) for name in comparison.only_head)])
    if comparison.only_base:
        lines.extend(["", "**Only in base:** " + ", ".join(safe_markdown(name) for name in comparison.only_base)])
    if comparison.dropped:
        lines.extend([
            "",
            f"**Dropped unpaired shard samples ({len(comparison.dropped)}):** "
            + ", ".join(safe_markdown(name) for name in comparison.dropped),
        ])
    if comparison.malformed:
        lines.extend(["", f"**Malformed comparisons skipped:** {comparison.malformed}"])
    lines.extend(["", MARKER])
    return "\n".join(lines)


def render_head_only(head: dict[str, Benchmark]) -> str:
    lines = [
        "### JMH Benchmark Report",
        "",
        "**Regressions:** 0 (no base)",
        "**Improvements:** 0 (no base)",
        "**Runner health:** not measured (no base)",
        "",
        "No comparison was possible because the base revision has no benchmark harness.",
        "",
        "| Benchmark | Head score | Error | Unit |",
        "| --- | ---: | ---: | --- |",
    ]
    lines.extend(
        f"| {display_identity(benchmark.identity)} | {format_number(benchmark.score)} | "
        f"{format_number(benchmark.error)} | {safe_markdown(benchmark.unit)} |"
        for benchmark in sorted(head.values(), key=lambda item: item.identity)
    )
    lines.extend(["", MARKER])
    return "\n".join(lines)


def github_request(url: str, token: str, method: str, body: str | None = None) -> list | dict:
    data = body.encode("utf-8") if body is not None else None
    request = urllib.request.Request(
        url, data=data, method=method,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.loads(response.read().decode("utf-8"))


def post_comment(report: str, repo: str, pr_number: str, token: str) -> None:
    try:
        comment_id: int | None = None
        for page in range(1, 101):
            response = github_request(
                f"https://api.github.com/repos/{repo}/issues/{pr_number}/comments?per_page=100&page={page}",
                token, "GET",
            )
            if not isinstance(response, list):
                break
            for comment in response:
                if isinstance(comment, dict) and isinstance(comment.get("body"), str) and MARKER in comment["body"]:
                    identifier = comment.get("id")
                    if isinstance(identifier, int):
                        comment_id = identifier
                        break
            if comment_id is not None or len(response) < 100:
                break
        payload = json.dumps({"body": report})
        if comment_id is None:
            github_request(f"https://api.github.com/repos/{repo}/issues/{pr_number}/comments", token, "POST", payload)
        else:
            github_request(f"https://api.github.com/repos/{repo}/issues/comments/{comment_id}", token, "PATCH", payload)
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError, ValueError, json.JSONDecodeError) as error:
        print(f"warning: unable to post JMH report: {error}", file=sys.stderr)


def arguments(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Compare JMH JSON results for a pull request.")
    parser.add_argument("--head", required=True, help="JMH JSON file or directory for the PR head")
    parser.add_argument("--base", help="JMH JSON file or directory for the PR base")
    parser.add_argument("--threshold", type=float, default=0.10, help="Effect threshold, default: 0.10")
    parser.add_argument("--repo", help="GitHub repository as owner/name")
    parser.add_argument("--pr-number", help="Pull request number for optional comment posting")
    parser.add_argument(
        "--expected-shards",
        default="",
        help="Comma-separated shard result file names the CI matrix should have produced, "
             "so a shard that failed without uploading anything is reported rather than ignored",
    )
    parser.add_argument(
        "--fail-on-regression", action="store_true",
        help="Reserved for future use; regressions currently never fail the build",
    )
    parsed = parser.parse_args(argv)
    if parsed.threshold <= 0:
        parser.error("--threshold must be greater than zero")
    return parsed


def main(argv: Sequence[str] | None = None) -> int:
    parsed = arguments(argv)
    head_shards = read_shards(parsed.head)
    head = pool_shards(head_shards)
    base_exists = parsed.base is not None and Path(parsed.base).exists()
    report = (
        render_report(compare_shards(
            head_shards,
            read_shards(parsed.base),
            parsed.threshold,
            [shard for shard in parsed.expected_shards.split(",") if shard.strip()],
        ))
        if base_exists
        else render_head_only(head)
    )
    sys.stdout.write(report + "\n")
    pr_number = (parsed.pr_number or "").strip()
    token = os.getenv("GITHUB_TOKEN")
    if pr_number and pr_number != "null" and parsed.repo and token:
        post_comment(report, parsed.repo, pr_number, token)
    return 0


if __name__ == "__main__":
    sys.exit(main())
