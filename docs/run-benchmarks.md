# Running Optimized Benchmarks

Use this runbook to collect benchmark numbers in the intended order. Run on AC power, close heavy background apps, and avoid using the laptop while benchmarks are running.

## 1. Build And Verify

```bash
./gradlew clean test jmhJar
```

If Gradle cannot access your global cache, use a workspace-local cache:

```bash
GRADLE_USER_HOME="$PWD/.gradle" ./gradlew clean test jmhJar
```

## 2. Baseline Current Code

Run this first. These benchmarks intentionally keep the current per-call
`ArrayList<Fill>` allocation overhead.

```bash
java -XX:+UseG1GC -Xms4g -Xmx4g \
  '-Xlog:gc*,safepoint:file=docs/profiling/optimized-baseline-gc-safepoint.log:tags,uptime,time,level' \
  -jar build/libs/lob-matching-engine-1.0-SNAPSHOT-jmh.jar \
  "OrderBookThroughputBenchmark.orderBookCurrentAllocating|OrderBookThroughputBenchmark.engineCurrentAllocating" \
  -wi 5 -w 2 -i 8 -r 3 -f 3
```

Record the output in `docs/benchmarks.md`.

## 3. JFR Baseline Profile

Use an in-process JMH run for JFR so all benchmark samples go into one readable
recording. Do not use this run for headline throughput numbers.

```bash
java -XX:+UseG1GC -Xms4g -Xmx4g \
  "-XX:StartFlightRecording=filename=docs/profiling/optimized-baseline-inprocess.jfr,settings=profile,dumponexit=true" \
  -jar build/libs/lob-matching-engine-1.0-SNAPSHOT-jmh.jar \
  "OrderBookThroughputBenchmark.orderBookCurrentAllocating|OrderBookThroughputBenchmark.engineCurrentAllocating" \
  -wi 5 -w 2 -i 8 -r 3 -f 0
```

Extract readable summaries:

```bash
jfr summary docs/profiling/optimized-baseline-inprocess.jfr > docs/profiling/optimized-baseline-inprocess-summary.txt
jfr print --events "jdk.ExecutionSample,jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB,jdk.GarbageCollection,jdk.SafepointBegin,jdk.SafepointEnd" \
  docs/profiling/optimized-baseline-inprocess.jfr > docs/profiling/optimized-baseline-inprocess-events.txt
```

## 4. Metrics Isolation

This compares current `AtomicLong` metrics against primitive and no-op metrics.
Use this before considering `@Contended`.

```bash
java -XX:+UseG1GC -Xms4g -Xmx4g \
  -jar build/libs/lob-matching-engine-1.0-SNAPSHOT-jmh.jar \
  "MetricsIsolationBenchmark" \
  -wi 5 -w 2 -i 8 -r 3 -f 3
```

## 5. Reusable Fill List Comparison

Run this only after the baseline has been recorded.

```bash
java -XX:+UseG1GC -Xms4g -Xmx4g \
  '-Xlog:gc*,safepoint:file=docs/profiling/optimized-reusable-fills-gc-safepoint.log:tags,uptime,time,level' \
  -jar build/libs/lob-matching-engine-1.0-SNAPSHOT-jmh.jar \
  "OrderBookThroughputBenchmark.orderBookReusableFillList|OrderBookThroughputBenchmark.engineReusableFillList" \
  -wi 5 -w 2 -i 8 -r 3 -f 3
```

Compare against the baseline methods, not against older Phase 4 numbers.

## 6. Latency Distribution

```bash
java -XX:+UseG1GC -Xms4g -Xmx4g \
  '-Xlog:gc*,safepoint:file=docs/profiling/optimized-latency-gc-safepoint.log:tags,uptime,time,level' \
  -jar build/libs/lob-matching-engine-1.0-SNAPSHOT-jmh.jar \
  "LatencyDistributionBenchmark" \
  -wi 5 -w 2 -i 8 -r 3 -f 3
```

Copy the printed HdrHistogram percentile lines into
`docs/latency-distribution.md`.

## 7. Quick Smoke Run

Use this when you only want to verify the benchmarks start. Do not use these numbers in documentation.

```bash
java -XX:+UseG1GC -Xms2g -Xmx2g \
  -jar build/libs/lob-matching-engine-1.0-SNAPSHOT-jmh.jar \
  "OrderBookThroughputBenchmark.orderBookCurrentAllocating" \
  -wi 1 -w 1 -i 1 -r 1 -f 1
```

## Recording Checklist

- Record hardware, OS, Java version, JVM flags, and whether the laptop was on AC
  power.
- Keep `OrderBook` and `MatchingEngine` numbers separate.
- Record cancel hit rate, cancel miss rate, fill rate, and final live order
  count from the simulator summary if you add it to benchmark output.
- Do not attribute tail latency to safepoints until GC/safepoint logs or JFR
  events support that claim.
