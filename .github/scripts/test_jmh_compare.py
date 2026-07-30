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

import contextlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import jmh_compare


def fixture(
    name: str,
    score: float,
    confidence: list[float] | None = None,
    error: float | str | None = 1.0,
    unit: str = "ns/op",
    allocation: float | None = None,
    params: dict[str, str] | None = None,
    mode: str | None = "avgt",
) -> dict:
    metric = {"score": score, "scoreUnit": unit}
    if confidence is not None:
        metric["scoreConfidence"] = confidence
    if error is not None:
        metric["scoreError"] = error
    result = {"benchmark": name, "primaryMetric": metric}
    if mode is not None:
        result["mode"] = mode
    if params is not None:
        result["params"] = params
    if allocation is not None:
        result["secondaryMetrics"] = {
            "gc.alloc.rate.norm": {"score": allocation, "scoreUnit": "B/op"}
        }
    return result


def compared(head: list[dict], base: list[dict]) -> jmh_compare.Comparison:
    return jmh_compare.compare_benchmarks(
        jmh_compare.parse_entries(head), jmh_compare.parse_entries(base), 0.10
    )


def compared_shards(
    head: dict[str, list[dict]], base: dict[str, list[dict]], expected: list[str] | None = None
) -> jmh_compare.Comparison:
    return jmh_compare.compare_shards(
        {name: jmh_compare.parse_entries(entries) for name, entries in head.items()},
        {name: jmh_compare.parse_entries(entries) for name, entries in base.items()},
        0.10,
        expected or [],
    )


class JmhComparisonTest(unittest.TestCase):
    def test_speedup_direction_for_throughput_and_latency(self) -> None:
        result = compared(
            [
                fixture("sample.Throughput.run", 120, [119, 121], unit="ops/s"),
                fixture("sample.Latency.run", 100, [99, 101]),
            ],
            [
                fixture("sample.Throughput.run", 100, [99, 101], unit="ops/s"),
                fixture("sample.Latency.run", 120, [119, 121]),
            ],
        )

        self.assertEqual([row.speedup for row in result.rows], [1.2, 1.2])

    def test_clear_regression_requires_large_effect_and_disjoint_intervals(self) -> None:
        result = compared(
            [fixture("sample.Regression.run", 120, [119, 121])],
            [fixture("sample.Regression.run", 100, [99, 101])],
        )

        self.assertEqual(result.rows[0].verdict, "REGRESSED")

    def test_large_effect_with_overlapping_intervals_has_no_clear_change(self) -> None:
        result = compared(
            [fixture("sample.Overlap.run", 120, [95, 125])],
            [fixture("sample.Overlap.run", 100, [90, 121])],
        )

        self.assertEqual(result.rows[0].verdict, "no clear change")

    def test_small_effect_with_disjoint_intervals_has_no_clear_change(self) -> None:
        result = compared(
            [fixture("sample.Small.run", 105, [104, 106])],
            [fixture("sample.Small.run", 100, [99, 101])],
        )

        self.assertEqual(result.rows[0].verdict, "no clear change")

    def test_score_error_is_used_when_confidence_is_missing(self) -> None:
        result = compared(
            [fixture("sample.Fallback.run", 120, error=1)],
            [fixture("sample.Fallback.run", 100, error=1)],
        )

        self.assertEqual(result.rows[0].verdict, "REGRESSED")

    def test_missing_interval_and_error_is_insufficient_not_regressed(self) -> None:
        result = compared(
            [fixture("sample.Insufficient.run", 120, error=None)],
            [fixture("sample.Insufficient.run", 100, error=None)],
        )

        self.assertEqual(result.rows[0].verdict, "insufficient data")

    def test_nan_score_error_is_treated_as_unavailable(self) -> None:
        result = compared(
            [fixture("sample.NonFinite.run", 120, error="NaN")],
            [fixture("sample.NonFinite.run", 100, error="NaN")],
        )

        self.assertEqual(result.rows[0].verdict, "insufficient data")
        self.assertNotIn("nan", jmh_compare.render_report(result).lower())

    def test_benchmarks_only_on_one_side_are_excluded(self) -> None:
        result = compared(
            [fixture("sample.Shared.run", 100), fixture("sample.HeadOnly.run", 100)],
            [fixture("sample.Shared.run", 100), fixture("sample.BaseOnly.run", 100)],
        )

        self.assertEqual(len(result.rows), 1)
        self.assertEqual(result.only_head, ("sample.HeadOnly.run",))
        self.assertEqual(result.only_base, ("sample.BaseOnly.run",))

    def test_identity_sorts_parameters_and_invalid_scores_are_malformed(self) -> None:
        head = jmh_compare.parse_entries(
            [fixture("sample.Param.run", 0, params={"z": "2", "a": "1"})]
        )
        base = jmh_compare.parse_entries(
            [fixture("sample.Param.run", 100, params={"a": "1", "z": "2"})]
        )
        result = jmh_compare.compare_benchmarks(head, base, 0.10)

        self.assertEqual(list(head), ["sample.Param.run[a=1,z=2]"])
        self.assertEqual(result.malformed, 1)

    def test_rulers_are_excluded_from_groups_and_warn_when_unstable(self) -> None:
        result = compared(
            [
                fixture("sample.feature.Subject.run", 100, [99, 101]),
                fixture("org.apache.grails.benchmarks.ruler.CpuRulerBenchmark.run", 120, [119, 121]),
            ],
            [
                fixture("sample.feature.Subject.run", 100, [99, 101]),
                fixture("org.apache.grails.benchmarks.ruler.CpuRulerBenchmark.run", 100, [99, 101]),
            ],
        )
        report = jmh_compare.render_report(result)

        self.assertEqual(result.group_speedups, {"feature": (1.0, 1)})
        self.assertEqual(
            result.ruler_speedups,
            (("org.apache.grails.benchmarks.ruler.CpuRulerBenchmark.run", 1 / 1.2),),
        )
        self.assertIn("runner was unstable BETWEEN", report)
        self.assertEqual(
            next(row.verdict for row in result.rows if "Ruler" in row.identity),
            "ruler - excluded",
        )

    def test_opposite_ruler_movements_warn_without_geomean_cancellation(self) -> None:
        result = compared(
            [
                fixture("org.apache.grails.benchmarks.ruler.FastRuler.run", 80, [79, 81]),
                fixture("org.apache.grails.benchmarks.ruler.SlowRuler.run", 125, [124, 126]),
            ],
            [
                fixture("org.apache.grails.benchmarks.ruler.FastRuler.run", 100, [99, 101]),
                fixture("org.apache.grails.benchmarks.ruler.SlowRuler.run", 100, [99, 101]),
            ],
        )
        report = jmh_compare.render_report(result)

        self.assertIn("runner was unstable BETWEEN", report)
        self.assertIn("FastRuler.run: 1.25x", report)
        self.assertIn("SlowRuler.run: 0.80x", report)
        self.assertNotIn("**Runner health:** 1x", report)

    def test_single_ruler_outside_stability_threshold_warns(self) -> None:
        result = compared(
            [fixture("org.apache.grails.benchmarks.ruler.CpuRuler.run", 100, [99, 101])],
            [fixture("org.apache.grails.benchmarks.ruler.CpuRuler.run", 90, [89, 91])],
        )
        report = jmh_compare.render_report(result)

        self.assertIn("runner was unstable BETWEEN", report)
        self.assertIn("CpuRuler.run: 0.90x", report)

    def test_rulers_within_stability_threshold_do_not_warn(self) -> None:
        result = compared(
            [
                fixture("org.apache.grails.benchmarks.ruler.FirstRuler.run", 103, [102, 104]),
                fixture("org.apache.grails.benchmarks.ruler.SecondRuler.run", 96, [95, 97]),
            ],
            [
                fixture("org.apache.grails.benchmarks.ruler.FirstRuler.run", 100, [99, 101]),
                fixture("org.apache.grails.benchmarks.ruler.SecondRuler.run", 100, [99, 101]),
            ],
        )
        report = jmh_compare.render_report(result)

        self.assertNotIn("runner was unstable BETWEEN", report)
        self.assertIn("FirstRuler.run: 0.97x", report)
        self.assertIn("SecondRuler.run: 1.04x", report)

    def test_allocation_needs_both_percentage_and_absolute_thresholds(self) -> None:
        result = compared(
            [
                fixture("sample.Allocation.flag", 100, allocation=117),
                fixture("sample.Allocation.small", 100, allocation=115),
                fixture("sample.Allocation.percent", 100, allocation=1010),
            ],
            [
                fixture("sample.Allocation.flag", 100, allocation=100),
                fixture("sample.Allocation.small", 100, allocation=100),
                fixture("sample.Allocation.percent", 100, allocation=1000),
            ],
        )

        self.assertTrue(result.rows[0].allocation_candidate)
        self.assertFalse(result.rows[1].allocation_candidate)
        self.assertFalse(result.rows[2].allocation_candidate)

    def test_head_only_mode_renders_results_and_exits_zero(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            head_path = Path(directory) / "head.json"
            head_path.write_text(json.dumps([fixture("sample.New.run", 42)]), encoding="utf-8")
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                exit_code = jmh_compare.main(["--head", str(head_path)])

        self.assertEqual(exit_code, 0)
        self.assertIn("no comparison was possible", output.getvalue().lower())
        self.assertIn("sample.New.run", output.getvalue())

    def test_missing_base_file_uses_head_only_mode(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            head_path = Path(directory) / "head.json"
            head_path.write_text(json.dumps([fixture("sample.New.run", 42)]), encoding="utf-8")
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                exit_code = jmh_compare.main(
                    ["--head", str(head_path), "--base", str(Path(directory) / "absent.json")]
                )

        self.assertEqual(exit_code, 0)
        self.assertIn("no comparison was possible", output.getvalue().lower())

    def test_directory_input_merges_direct_json_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "first.json").write_text(json.dumps([fixture("sample.First.run", 1)]), encoding="utf-8")
            (root / "second.json").write_text(json.dumps([fixture("sample.Second.run", 2)]), encoding="utf-8")

            entries = jmh_compare.read_results(directory)

        self.assertEqual(set(entries), {"sample.First.run", "sample.Second.run"})

    def test_repeated_benchmark_across_shards_is_pooled_not_overwritten(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "shard-a.json").write_text(
                json.dumps([fixture("sample.Paired.run", 100, [90, 110], allocation=200)]), encoding="utf-8"
            )
            (root / "shard-b.json").write_text(
                json.dumps([fixture("sample.Paired.run", 200, [190, 210], allocation=400)]), encoding="utf-8"
            )

            entries = jmh_compare.read_results(directory)

        pooled = entries["sample.Paired.run"]
        self.assertEqual(pooled.score, 150)
        self.assertEqual(pooled.allocation, 300)
        self.assertEqual(pooled.confidence, (90, 210))

    def test_cross_shard_unpaired_benchmark_is_dropped(self) -> None:
        name = "sample.CrossShard.run"
        result = compared_shards(
            {
                "shard-a.json": [],
                "shard-b.json": [fixture(name, 100, [99, 101])],
            },
            {
                "shard-a.json": [fixture(name, 100, [99, 101])],
                "shard-b.json": [],
            },
        )

        self.assertEqual(result.rows, ())
        self.assertEqual(result.dropped, (name,))
        self.assertIn("Dropped unpaired shard samples (1):** sample.CrossShard.run", jmh_compare.render_report(result))

    def test_shard_that_produced_no_files_is_reported_as_missing(self) -> None:
        name = "sample.Only.run"
        result = compared_shards(
            {"shard-a.json": [fixture(name, 100, [99, 101])]},
            {"shard-a.json": [fixture(name, 100, [99, 101])]},
            expected=["shard-a.json", "shard-b.json"],
        )

        self.assertEqual(result.missing_shards, ("shard-b.json",))
        self.assertEqual(result.paired_shards, ("shard-a.json",))
        report = jmh_compare.render_report(result)
        self.assertIn("shard-b.json", report)
        self.assertIn("1 shard pair(s) instead of the expected 2", report)

    def test_all_expected_shards_present_reports_no_missing_warning(self) -> None:
        name = "sample.Both.run"
        result = compared_shards(
            {shard: [fixture(name, 100, [99, 101])] for shard in ("shard-a.json", "shard-b.json")},
            {shard: [fixture(name, 100, [99, 101])] for shard in ("shard-a.json", "shard-b.json")},
            expected=["shard-a.json", "shard-b.json"],
        )

        self.assertEqual(result.missing_shards, ())
        self.assertNotIn("instead of the expected", jmh_compare.render_report(result))

    def test_benchmark_paired_within_a_shard_is_compared(self) -> None:
        result = compared_shards(
            {"shard-a.json": [fixture("sample.Paired.run", 120, [119, 121])]},
            {"shard-a.json": [fixture("sample.Paired.run", 100, [99, 101])]},
        )

        self.assertEqual(result.rows[0].verdict, "REGRESSED")

    def test_opposite_ruler_movements_in_separate_shards_warn(self) -> None:
        name = "org.apache.grails.benchmarks.ruler.CpuRuler.run"
        result = compared_shards(
            {
                "shard-a.json": [fixture(name, 100 / 1.12, [88, 90])],
                "shard-b.json": [fixture(name, 112, [111, 113])],
            },
            {
                "shard-a.json": [fixture(name, 100, [99, 101])],
                "shard-b.json": [fixture(name, 100, [99, 101])],
            },
        )
        report = jmh_compare.render_report(result)

        self.assertIn("runner was unstable BETWEEN", report)
        self.assertIn("shard-a.json: CpuRuler.run", report)
        self.assertIn("shard-b.json: CpuRuler.run", report)

    def test_ruler_stability_boundary_is_strict_in_both_directions(self) -> None:
        name = "org.apache.grails.benchmarks.ruler.CpuRuler.run"
        exact_faster = compared_shards(
            {"shard.json": [fixture(name, 100, [99, 101])]},
            {"shard.json": [fixture(name, 105, [104, 106])]},
        )
        exact_slower = compared_shards(
            {"shard.json": [fixture(name, 105, [104, 106])]},
            {"shard.json": [fixture(name, 100, [99, 101])]},
        )
        faster = compared_shards(
            {"shard.json": [fixture(name, 100, [99, 101])]},
            {"shard.json": [fixture(name, 106, [105, 107])]},
        )
        slower = compared_shards(
            {"shard.json": [fixture(name, 106, [105, 107])]},
            {"shard.json": [fixture(name, 100, [99, 101])]},
        )

        self.assertNotIn("runner was unstable BETWEEN", jmh_compare.render_report(exact_faster))
        self.assertNotIn("runner was unstable BETWEEN", jmh_compare.render_report(exact_slower))
        self.assertIn("runner was unstable BETWEEN", jmh_compare.render_report(faster))
        self.assertIn("runner was unstable BETWEEN", jmh_compare.render_report(slower))

    def test_missing_or_nonfinite_ruler_marks_runner_health_incomplete(self) -> None:
        name = "org.apache.grails.benchmarks.ruler.CpuRuler.run"
        missing = compared_shards(
            {"shard.json": []},
            {"shard.json": [fixture(name, 100, [99, 101])]},
        )
        nonfinite = compared_shards(
            {"shard.json": [fixture(name, float("nan"), [99, 101])]},
            {"shard.json": [fixture(name, 100, [99, 101])]},
        )

        self.assertTrue(missing.ruler_incomplete)
        self.assertTrue(nonfinite.ruler_incomplete)
        self.assertIn("**Runner health:** INCOMPLETE", jmh_compare.render_report(missing))
        self.assertIn("**Runner health:** INCOMPLETE", jmh_compare.render_report(nonfinite))

    def test_pooled_interval_spans_shards_so_disagreement_suppresses_a_verdict(self) -> None:
        name = "sample.Noisy.run"
        shards = [
            jmh_compare.parse_entries([fixture(name, 200, [199, 201])])[name],
            jmh_compare.parse_entries([fixture(name, 90, [89, 91])])[name],
        ]
        base = jmh_compare.parse_entries([fixture(name, 100, [99, 101])])

        result = jmh_compare.compare_benchmarks(
            {name: jmh_compare.pool_benchmarks(shards)}, base, 0.10
        )

        self.assertEqual(result.rows[0].verdict, "no clear change")

    def test_pooling_incompatible_units_yields_no_score(self) -> None:
        pooled = jmh_compare.pool_benchmarks(
            [
                jmh_compare.parse_entries([fixture("sample.Mixed.run", 10, [9, 11])])["sample.Mixed.run"],
                jmh_compare.parse_entries(
                    [fixture("sample.Mixed.run", 10, [9, 11], unit="ops/s")]
                )["sample.Mixed.run"],
            ]
        )
        self.assertIsNone(pooled.score)

    def test_unit_change_between_revisions_is_not_comparable(self) -> None:
        result = compared(
            [fixture("sample.Switched.run", 5000, [4900, 5100], unit="ops/s")],
            [fixture("sample.Switched.run", 200, [199, 201], unit="ns/op")],
        )

        self.assertEqual(result.rows, ())
        self.assertEqual(result.malformed, 1)

    def test_mode_change_with_the_same_unit_is_not_comparable(self) -> None:
        result = compared(
            [fixture("sample.Switched.run", 100, [99, 101], mode="sample")],
            [fixture("sample.Switched.run", 100, [99, 101], mode="avgt")],
        )

        self.assertEqual(result.rows, ())
        self.assertEqual(result.malformed, 1)

    def test_matching_mode_and_unit_are_comparable(self) -> None:
        result = compared(
            [fixture("sample.SameMode.run", 120, [119, 121], mode="avgt")],
            [fixture("sample.SameMode.run", 100, [99, 101], mode="avgt")],
        )

        self.assertEqual(result.rows[0].verdict, "REGRESSED")

    def test_summary_file_is_owned_by_the_workflow(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            head_path = root / "head.json"
            summary_path = root / "summary.md"
            head_path.write_text(json.dumps([fixture("sample.New.run", 42)]), encoding="utf-8")
            with mock.patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": str(summary_path)}):
                with contextlib.redirect_stdout(io.StringIO()):
                    jmh_compare.main(["--head", str(head_path)])

        self.assertFalse(summary_path.exists())

    def test_fail_on_regression_remains_zero_exit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            head_path, base_path = root / "head.json", root / "base.json"
            head_path.write_text(json.dumps([fixture("sample.Run.run", 120)]), encoding="utf-8")
            base_path.write_text(json.dumps([fixture("sample.Run.run", 100)]), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()):
                exit_code = jmh_compare.main(
                    ["--head", str(head_path), "--base", str(base_path), "--fail-on-regression"]
                )

        self.assertEqual(exit_code, 0)

    def test_null_pr_number_skips_comment_posting(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            head_path = Path(directory) / "head.json"
            head_path.write_text(json.dumps([fixture("sample.New.run", 42)]), encoding="utf-8")
            with mock.patch.dict(os.environ, {"GITHUB_TOKEN": "token"}):
                with mock.patch.object(jmh_compare, "post_comment") as post_comment:
                    with contextlib.redirect_stdout(io.StringIO()):
                        jmh_compare.main(
                            ["--head", str(head_path), "--repo", "apache/grails-core", "--pr-number", "null"]
                        )

        post_comment.assert_not_called()

    def test_markdown_injection_in_benchmark_name_is_neutralised(self) -> None:
        name = "sample.<b>|`injected`.run"
        result = compared([fixture(name, 100)], [fixture(name, 100)])
        report = jmh_compare.render_report(result)

        self.assertNotIn("<b>", report)
        self.assertNotIn("|`", report)
        self.assertIn("sample.binjected.run", report)

    def test_markdown_link_in_benchmark_name_is_neutralised(self) -> None:
        name = "sample.Evil.run[label=[x](http://example.com)]"
        report = jmh_compare.render_report(compared([fixture(name, 100)], [fixture(name, 100)]))

        self.assertNotIn("](", report)
        self.assertNotRegex(report, r"\[[^]]+\]\([^)]*\)")

    def test_markdown_image_in_benchmark_name_is_neutralised(self) -> None:
        name = "sample.Evil.run![x](y)"
        report = jmh_compare.render_report(compared([fixture(name, 100)], [fixture(name, 100)]))

        self.assertNotIn("![", report)
        self.assertNotIn("](", report)

    def test_ruler_text_in_parameter_value_is_not_excluded(self) -> None:
        name = "org.apache.grails.benchmarks.feature.Subject.run"
        result = compared(
            [fixture(name, 80, [79, 81], params={"label": "Ruler"})],
            [fixture(name, 100, [99, 101], params={"label": "Ruler"})],
        )

        self.assertEqual(result.rows[0].verdict, "IMPROVED")
        self.assertEqual(result.group_speedups, {"feature": (1.25, 1)})
        self.assertEqual(result.ruler_speedups, ())

    def test_ruler_package_benchmark_is_excluded_from_groups_and_verdicts(self) -> None:
        name = "org.apache.grails.benchmarks.ruler.CpuRulerBenchmark.run"
        result = compared(
            [fixture(name, 80, [79, 81])],
            [fixture(name, 100, [99, 101])],
        )

        self.assertEqual(result.rows[0].verdict, "ruler - excluded")
        self.assertEqual(result.group_speedups, {})
        self.assertEqual(result.ruler_speedups, ((name, 1.25),))

    def test_geomean_is_correct_for_known_speedups(self) -> None:
        result = compared(
            [
                fixture("sample.group.First.run", 200),
                fixture("sample.group.Second.run", 50),
            ],
            [
                fixture("sample.group.First.run", 100),
                fixture("sample.group.Second.run", 100),
            ],
        )

        self.assertEqual(result.group_speedups, {"group": (1.0, 2)})


if __name__ == "__main__":
    unittest.main()
