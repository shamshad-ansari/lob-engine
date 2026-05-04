# LOB Engine — Benchmark Log

Results recorded here as each phase is completed.
Each entry includes hardware, JVM flags, workload description, and raw output.

---

## Naive Baseline

**Hardware:** Apple M4, MacBook Air, 16 GB RAM
**JVM:** Java 21.0.5 (HotSpot 64-bit), `-XX:-RestrictContended -XX:+UseG1GC -Xmx4g`
**Workload:** 60 % limit orders (prices ±10 ticks around mid 5000), 20 % market orders, 20 % cancel operations, 1 M pre-generated orders cycled per iteration
**Benchmark config:** `@Fork(1)`, `@Warmup(3 × 2 s)`, `@Measurement(5 × 3 s)`

**Result:** ~71,464 ops/sec

```
Benchmark                               Mode  Cnt      Score       Error  Units
NaiveThroughputBenchmark.naiveProcess  thrpt    5  71464.275 ± 35906.108  ops/s
```

The wide confidence interval reflects the book's growing depth across iterations
(more resting orders → deeper linear scans → decreasing throughput within a run),
which is itself a symptom of the O(N) matching cost that Phase 3 will eliminate.

### JFR Hot Methods

Recorded via:
```bash
java -XX:-RestrictContended -XX:+UseG1GC -Xmx4g \
  "-XX:StartFlightRecording=filename=naive.jfr,settings=profile,dumponexit=true" \
  -jar build/libs/lob-matching-engine-1.0-SNAPSHOT-jmh.jar \
  NaiveThroughputBenchmark -wi 3 -w 2 -i 5 -r 3 -f 0
```

772 CPU samples from the benchmark worker thread:

| Rank | Method | Top-of-stack CPU % | Role |
|------|--------|--------------------|------|
| 1 | `java.util.TimSort.countRunAndMakeAscending` | 57.6 % | Called by `List.sort()` after every order insertion to maintain price-time order — O(N log N) per insert |
| 2 | `NaiveOrderBook.removeById` | 18.8 % | Linear scan through the bid/ask list for cancel operations — O(N) per cancel |
| 3 | `java.util.Collections$ReverseComparator2.compare` | 18.3 % | Comparator invoked repeatedly by TimSort for the descending-bid ordering |

**Conclusion:** 76 % of CPU time is consumed by `TimSort` and its comparator,
triggered by `List.sort()` after every new limit order is added to the book.
A further 19 % is `removeById`'s O(N) linear scan for cancel operations.
Together these account for ~95 % of all on-CPU work. The root cause is using
an unordered `ArrayList` and re-sorting it on every mutation instead of
maintaining a pre-ordered data structure.

**Optimization hypothesis:** A `TreeMap<Long, ArrayDeque<NaiveOrder>>` keyed
by `priceTicks` gives O(log N) level lookup and O(1) FIFO access with no
sort required. A `HashMap<Long, NaiveOrder>` by `orderId` gives O(1)
cancellation lookup. Expected improvement: ~40×.