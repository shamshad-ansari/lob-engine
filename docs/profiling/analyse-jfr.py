#!/usr/bin/env python3
"""
Parses a jdk.ExecutionSample text dump and writes a ranked hot-method report.

    # 1. Dump execution samples from the recording
    jfr print --events "jdk.ExecutionSample" docs/profiling/naive.jfr > docs/profiling/jfr_samples.txt

    # 2. Run this script (from any directory)
    python3 docs/profiling/analyse-jfr.py

Output: docs/profiling/jfr-analysis-report.txt
"""

import re
import sys
import os
from collections import Counter
from datetime import datetime

HERE          = os.path.dirname(os.path.abspath(__file__))
JFR_TEXT_FILE = os.path.join(HERE, "jfr_samples.txt")
REPORT_FILE   = os.path.join(HERE, "jfr-analysis-report.txt")

BENCHMARK_THREAD = "NaiveThroughputBenchmark.naiveProcess-jmh-worker"
TOP_N            = 15

if not os.path.exists(JFR_TEXT_FILE):
    print(f"ERROR: {JFR_TEXT_FILE} not found.")
    print("       Run: jfr print --events \"jdk.ExecutionSample\" docs/profiling/naive.jfr > docs/profiling/jfr_samples.txt")
    sys.exit(1)

with open(JFR_TEXT_FILE) as f:
    text = f.read()

blocks        = text.split("jdk.ExecutionSample {")[1:]
counts_top    = Counter()
counts_inc    = Counter()
total_samples = 0

# Branch counters
sort_samples           = 0
cancel_samples         = 0
sort_and_cancel_both   = 0

for block in blocks:
    if BENCHMARK_THREAD not in block:
        continue

    frames = re.findall(r'^\s{4}([a-zA-Z_$][\w.$]+\.[<\w>]+)\(', block, re.MULTILINE)
    if not frames:
        continue

    total_samples += 1
    frame_set = set(frames)

    counts_top[frames[0]] += 1

    # set() ensures a method recurring at multiple depths in one stack
    # (e.g. recursive calls) is counted at most once per sample, so the
    # denominator (total_samples) remains the correct normaliser.
    for frame in frame_set:
        counts_inc[frame] += 1

    has_sort = any(
        "TimSort." in f or f.endswith(".sort") or "Arrays.sort" in f
        for f in frame_set
    )
    has_cancel = any(
        "NaiveOrderBook.cancel" in f or "NaiveOrderBook.removeById" in f
        for f in frame_set
    )
    if has_sort:
        sort_samples += 1
    if has_cancel:
        cancel_samples += 1
    if has_sort and has_cancel:
        sort_and_cancel_both += 1

if total_samples == 0:
    print(f"ERROR: No benchmark-thread samples found in {JFR_TEXT_FILE}.")
    sys.exit(1)

# Denominator is total_samples for both metrics so both answer the same
# question: "in what fraction of sampled stacks was this method present?"
# Using the total frame-row count instead would understate methods that
# appear high in shallow stacks relative to those deep in long ones.
def inc_pct(cnt: int) -> float:
    return cnt * 100.0 / total_samples

def exc_pct(cnt: int) -> float:
    return cnt * 100.0 / total_samples

def short_name(full: str) -> str:
    parts = full.split(".")
    return f"{parts[-2]}.{parts[-1]}" if len(parts) >= 2 else full


SEP  = "=" * 72
THIN = "-" * 72

lines = []

lines.append(SEP)
lines.append("  NaiveOrderBook — JFR Hot Method Analysis")
lines.append(f"  Generated : {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
lines.append(f"  Input     : {JFR_TEXT_FILE}")
lines.append(f"  Samples   : {total_samples} (benchmark worker thread)")
lines.append(SEP)

# ------------------------------------------------------------------
# Exclusive
# ------------------------------------------------------------------
lines.append("")
lines.append("EXCLUSIVE SAMPLE SHARE")
lines.append("  Method was at the top of the stack when the JVM sample was taken.")
lines.append("  Percentage = top-of-stack occurrences / total samples.")
lines.append(f"  {'Rank':<5} {'Method':<52} {'Samples':>7}  {'% of total':>10}")
lines.append("  " + THIN)
for rank, (method, cnt) in enumerate(counts_top.most_common(TOP_N), start=1):
    lines.append(f"  {rank:<5} {short_name(method):<52} {cnt:>7}  {exc_pct(cnt):>9.1f}%")

# ------------------------------------------------------------------
# Inclusive
# ------------------------------------------------------------------
lines.append("")
lines.append("INCLUSIVE SAMPLE SHARE")
lines.append("  Method appeared anywhere in the sampled stack.")
lines.append("  Percentage = samples containing method / total samples.")
lines.append("  A method with high inclusive but low exclusive share delegates")
lines.append("  most of its work to a callee.")
lines.append(f"  {'Rank':<5} {'Method':<52} {'Samples':>7}  {'% of total':>10}")
lines.append("  " + THIN)
for rank, (method, cnt) in enumerate(counts_inc.most_common(TOP_N), start=1):
    lines.append(f"  {rank:<5} {short_name(method):<52} {cnt:>7}  {inc_pct(cnt):>9.1f}%")

# ------------------------------------------------------------------
# Branch summary
# ------------------------------------------------------------------
combined      = sort_samples + cancel_samples - sort_and_cancel_both
combined_pct  = combined * 100.0 / total_samples

lines.append("")
lines.append("BRANCH SUMMARY")
lines.append("  Samples where each execution path was active.")
lines.append("  Combined % uses inclusion-exclusion to avoid double-counting overlap.")
lines.append(f"  {'Branch':<40} {'Samples':>7}  {'% of total':>10}")
lines.append("  " + THIN)
lines.append(f"  {'Sort  (TimSort / Arrays.sort / ArrayList.sort)':<40} {sort_samples:>7}  {sort_samples * 100.0 / total_samples:>9.1f}%")
lines.append(f"  {'Cancel  (NaiveOrderBook.cancel / removeById)':<40} {cancel_samples:>7}  {cancel_samples * 100.0 / total_samples:>9.1f}%")
lines.append(f"  {'Overlap  (both in same sample)':<40} {sort_and_cancel_both:>7}  {sort_and_cancel_both * 100.0 / total_samples:>9.1f}%")
lines.append(f"  {'Combined  (sort + cancel - overlap)':<40} {combined:>7}  {combined_pct:>9.1f}%")

# ------------------------------------------------------------------
# Notes
# ------------------------------------------------------------------
lines.append("")
lines.append(SEP)
lines.append("  Notes")
lines.append(SEP)
lines.append("""
  These are sampled-stack percentages, not exact wall-clock timings.
  At JFR default rate (~10 ms intervals), 750+ samples across a 21 s run
  provides a statistically representative picture of where CPU time was spent.

  See docs/benchmarks.md for the full interpretation of these results.
""")

with open(REPORT_FILE, "w") as f:
    f.write("\n".join(lines))

print(f"Report written to: {REPORT_FILE}")
