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