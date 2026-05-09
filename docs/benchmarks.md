# LOB Engine — Benchmark Log

Results recorded here as each phase is completed.
Each entry includes hardware, JVM flags, workload description, and raw output.

---

## Naive Baseline

**Hardware:** Apple M4, MacBook Air, 16 GB RAM

**JVM:** Java 21.0.5 (HotSpot 64-bit), `-XX:-RestrictContended -XX:+UseG1GC -Xmx4g`

**Workload:** 60% limit orders (prices ±10 ticks around mid 5000), 20% market orders, 20% cancel operations, 1M pre-generated orders cycled per iteration

**Benchmark config:** `@Fork(1)`, `@Warmup(3 × 2 s)`, `@Measurement(5 × 3 s)`

**Result:** ~71,464 ops/sec

```
Benchmark                               Mode  Cnt      Score       Error  Units
NaiveThroughputBenchmark.naiveProcess  thrpt    5  71464.275 ± 35906.108  ops/s
```

The wide confidence interval (±35,906 ops/sec, roughly 50% of the mean) is not
measurement noise. It is the O(N) cost of the naive implementation revealing
itself over time. The book accumulates resting orders across iterations because
`@Setup(Level.Iteration)` resets only the array cursor, not the book state.
As depth grows, each insertion triggers a more expensive `ArrayList.sort()` over
an ever-longer list, so later iterations are measurably slower than earlier ones.

---

### JFR Hot Methods

```bash
# Record
java -XX:-RestrictContended -XX:+UseG1GC -Xmx4g \
  "-XX:StartFlightRecording=filename=docs/profiling/naive.jfr,settings=profile,dumponexit=true" \
  -jar build/libs/lob-matching-engine-1.0-SNAPSHOT-jmh.jar \
  NaiveThroughputBenchmark -wi 3 -w 2 -i 5 -r 3 -f 0

# Dump samples and analyse
jfr print --events "jdk.ExecutionSample" docs/profiling/naive.jfr > docs/profiling/jfr_samples.txt
python3 docs/profiling/analyse-jfr.py
# Output: docs/profiling/jfr-analysis-report.txt
```

**Flamegraph (JDK Mission Control):**

![Naive JFR flamegraph](profiling/assets/naive-jfr-flamegraph.png)

JMC renders this bottom-up: `Thread.run()` is the root at the bottom and the
leaf methods consuming CPU are at the top. Reading upward from
`NaiveThroughputBenchmark.naiveProcess()`, the frame splits at
`NaiveOrderBook.processLimit()` into two branches:

- **Wide left branch (~81% of stacks):** `ArrayList.sort()` calls `Arrays.sort()`
  calls `TimSort.sort()` calls `TimSort.countRunAndMakeAscending()`, which then
  enters the comparator lambda chain at the top. `TimSort.sort` appeared in
  609 of 756 samples (80.6% inclusive). `TimSort.countRunAndMakeAscending` was
  the top-of-stack leaf in 446 samples (59.0% exclusive), meaning it was the
  method directly consuming CPU more than half the time.

- **Narrow right branch (~18% of stacks):** `cancel()` calls `removeById()`,
  appearing in 139 of 756 samples (18.4%). The overlap between the two branches
  is zero — no sample contained both a sort frame and a cancel frame, so the
  percentages can be added directly.

**Exclusive sample share (top-of-stack — where CPU was directly executing):**

| Rank | Method | Exclusive % | Role |
|------|--------|-------------|------|
| 1 | `TimSort.countRunAndMakeAscending` | 59.0% | Leaf of `List.sort()`, called after every limit order insertion — O(N log N) per insert |
| 2 | `NaiveOrderBook.removeById` | 18.4% | Linear scan of the bid/ask list on every cancel — O(N) per cancel |
| 3 | `Collections$ReverseComparator2.compare` | 16.5% | Comparator invoked thousands of times per sort for the descending-bid ordering |
| 4 | `TimSort.sort` | 4.9% | Sort orchestration overhead |

**Branch summary (inclusive — samples where each path was active):**

| Branch | Samples | % of total |
|--------|---------|------------|
| Sort (TimSort / Arrays.sort / ArrayList.sort) | 611 | 80.8% |
| Cancel (NaiveOrderBook.cancel / removeById) | 139 | 18.4% |
| Overlap (both in same sample) | 0 | 0.0% |
| Combined | 750 | 99.2% |

**Conclusion:** 99.2% of sampled CPU stacks trace to exactly two root causes
with zero overlap between them. The sort branch (80.8%) originates from the
single line `bids.sort(BID_ORDER)` / `asks.sort(ASK_ORDER)` in
`NaiveOrderBook.processLimit()`: every new resting limit order triggers a full
re-sort of the entire list. The cancel branch (18.4%) originates from
`removeById()` scanning the full list linearly for every cancel operation. Both
root causes share the same underlying problem: using an unordered `ArrayList`
and imposing order by repeated full-list mutation instead of choosing a data
structure that maintains order structurally.

**Optimization hypothesis:** A `TreeMap<Long, ArrayDeque<NaiveOrder>>` keyed by
`priceTicks` eliminates the sort entirely. The tree maintains price order
structurally at O(log L) insertion cost, where L is the number of distinct price
levels (at most 21 in this workload's ±10-tick window). A
`HashMap<Long, NaiveOrder>` by `orderId` reduces cancel lookup from O(N) to
O(1). Given that 80.8% of sampled stacks pass through the sorting path and the
replacement reduces that cost from O(N log N) over all resting orders to O(log L)
over price levels, the expected improvement is 20-40x, scaling with book depth
at measurement time.

---

## Phase 4 — Object Pool

**Hardware:** Apple M4, MacBook Air, 16 GB RAM

**JVM:** Java 21.0.5 (HotSpot 64-bit), `-XX:-RestrictContended -XX:+UseG1GC -Xmx4g`

**Workload:** 60% limit orders, 20% market orders, 20% cancel operations, prices
clustered within +/-10 ticks around mid 5000. The optimized benchmark uses
primitive operation templates, acquires incoming orders from `OrderPool`, releases
caller-owned incoming orders after processing, and releases returned fills back to
`FillPool` after consumption.

**Benchmark config:** `@Fork(1)`, `@Warmup(3 x 2 s)`, `@Measurement(5 x 3 s)`

**Result:** ~13,192,062 ops/sec

```text
Benchmark                                       Mode  Cnt         Score        Error  Units
NaiveThroughputBenchmark.naiveProcess          thrpt    5     71877.823 +/-  44466.012  ops/s
OrderBookThroughputBenchmark.optimizedProcess  thrpt    5  13192062.173 +/- 523903.834  ops/s
```

The optimized `OrderBook` is roughly 183x faster than the naive baseline in this
run. The main improvement still comes from the earlier structural changes
(`TreeMap` price levels, `HashMap` order index, O(1) queue removal), while Phase 4
removes `Order` and `Fill` allocation from the matching hot path.

**GC check:** direct JMH jar run with `-verbose:gc`, shortened to
`-wi 1 -w 1 -i 3 -r 1 -f 1` so the GC output is readable:

```bash
java -XX:-RestrictContended -XX:+UseG1GC -Xmx4g -verbose:gc \
  -jar build/libs/lob-matching-engine-1.0-SNAPSHOT-jmh.jar \
  "OrderBookThroughputBenchmark.optimizedProcess" \
  -wi 1 -w 1 -i 3 -r 1 -f 1
```

```text
# VM options: -XX:-RestrictContended -XX:+UseG1GC -Xmx4g -verbose:gc
# Warmup: 1 iterations, 1 s each
# Measurement: 3 iterations, 1 s each

Iteration   1:
[1.622s][info][gc] GC(10) Pause Young (Normal) (G1 Evacuation Pause) 1607M->348M(2104M) 2.447ms
[2.173s][info][gc] GC(11) Pause Young (Normal) (G1 Evacuation Pause) 1606M->348M(2104M) 2.259ms
13442580.195 ops/s
Iteration   2:
[2.716s][info][gc] GC(12) Pause Young (Normal) (G1 Evacuation Pause) 1606M->348M(2104M) 2.673ms
13793986.647 ops/s
Iteration   3:
[3.272s][info][gc] GC(13) Pause Young (Normal) (G1 Evacuation Pause) 1606M->349M(2104M) 3.271ms
[3.826s][info][gc] GC(14) Pause Young (Normal) (G1 Evacuation Pause) 1607M->349M(2104M) 3.250ms
13698354.391 ops/s

Benchmark                                       Mode  Cnt         Score         Error  Units
OrderBookThroughputBenchmark.optimizedProcess  thrpt    3  13644973.744 +/- 3314575.833  ops/s
```

Young GCs are still visible during measurement, but they are no longer caused by
`new Order()` or `new Fill()` in `OrderBook`'s matching path. The Phase 4 grep
gate is clean for `src/main/java/io/github/shamshadansari/lobengine/book`.
Construction-time `new Order()` and `new Fill()` remain only inside the pool
package, where they prewarm or refill exhausted pools.

Remaining allocation sources are expected and deferred by the Phase 4 spec:
`new ArrayList<>()` for each returned fills list, JMH benchmark scaffolding,
occasional `HashMap`/`TreeMap` internal growth, and snapshot allocations
(`BookSnapshot`/`BookLevel`) for snapshot-heavy workloads.
