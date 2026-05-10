# Optimized Profiling Commands

Use these commands after building the JMH jar. Keep baseline recordings before removing allocation overhead so later optimization claims have a clean reference.

```bash
./gradlew clean jmhJar
```

## Baseline Throughput With GC And Safepoints

```bash
java -XX:+UseG1GC -Xms4g -Xmx4g \
  '-Xlog:gc*,safepoint:file=docs/profiling/optimized-baseline-gc-safepoint.log:tags,uptime,time,level' \
  -jar build/libs/lob-matching-engine-1.0-SNAPSHOT-jmh.jar \
  "OrderBookThroughputBenchmark.orderBookCurrentAllocating|OrderBookThroughputBenchmark.engineCurrentAllocating" \
  -wi 5 -w 2 -i 8 -r 3 -f 3
```

## Allocation And Hot Method JFR

```bash
java -XX:+UseG1GC -Xms4g -Xmx4g \
  "-XX:StartFlightRecording=filename=docs/profiling/optimized-baseline-inprocess.jfr,settings=profile,dumponexit=true" \
  -jar build/libs/lob-matching-engine-1.0-SNAPSHOT-jmh.jar \
  "OrderBookThroughputBenchmark.orderBookCurrentAllocating|OrderBookThroughputBenchmark.engineCurrentAllocating" \
  -wi 5 -w 2 -i 8 -r 3 -f 0
```

```bash
jfr summary docs/profiling/optimized-baseline-inprocess.jfr > docs/profiling/optimized-baseline-inprocess-summary.txt
jfr print --events "jdk.ExecutionSample,jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB,jdk.GarbageCollection,jdk.SafepointBegin,jdk.SafepointEnd" \
  docs/profiling/optimized-baseline-inprocess.jfr > docs/profiling/optimized-baseline-inprocess-events.txt
```

## Metrics Isolation

```bash
java -XX:+UseG1GC -Xms4g -Xmx4g \
  -jar build/libs/lob-matching-engine-1.0-SNAPSHOT-jmh.jar \
  "MetricsIsolationBenchmark" \
  -wi 5 -w 2 -i 8 -r 3 -f 3
```

## Reusable Fill List Comparison

```bash
java -XX:+UseG1GC -Xms4g -Xmx4g \
  '-Xlog:gc*,safepoint:file=docs/profiling/optimized-reusable-fills-gc-safepoint.log:tags,uptime,time,level' \
  -jar build/libs/lob-matching-engine-1.0-SNAPSHOT-jmh.jar \
  "OrderBookThroughputBenchmark.orderBookReusableFillList|OrderBookThroughputBenchmark.engineReusableFillList" \
  -wi 5 -w 2 -i 8 -r 3 -f 3
```

## Latency Histogram

```bash
java -XX:+UseG1GC -Xms4g -Xmx4g \
  '-Xlog:gc*,safepoint:file=docs/profiling/optimized-latency-gc-safepoint.log:tags,uptime,time,level' \
  -jar build/libs/lob-matching-engine-1.0-SNAPSHOT-jmh.jar \
  "LatencyDistributionBenchmark" \
  -wi 5 -w 2 -i 8 -r 3 -f 3
```

Do not attribute P99.9+ latency to safepoints unless the latency window lines up
with `optimized-latency-gc-safepoint.log` or the JFR safepoint events.
