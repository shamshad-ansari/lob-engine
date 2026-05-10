# lob-engine

A high-performance limit order book matching engine written in Java 21. Built as a systems programming capstone, it explores the data-structure and GC trade-offs that separate a naive implementation from one capable of millions of matched operations per second.

## What it does

The engine accepts order events (new, cancel, modify), matches them against a price-time priority order book, and produces fills and market data updates. It supports three order types:

- **Limit** — rests in the book at a specified price; matches aggressively on entry if the spread is crossed
- **Market** — consumes opposite-side liquidity at any price until filled or the book is exhausted
- **IOC (Immediate-or-Cancel)** — matches aggressively like a limit order but cancels any unfilled remainder instead of resting

## Architecture

```
OrderSubmission ──► OrderIngestionRing ──► MatchingEngine ──► OrderBook
   (validated)       (LMAX Disruptor)       (event dispatch)    (price-time priority)
                                                                      │
                                                              MarketDataPublisher
                                                           (fills + book updates)
```

### OrderBook

The core data structure. Bids and asks are each stored in a `TreeMap<Long, PriceLevel>` keyed by price tick, giving O(log L) insertion where L is the number of distinct price levels. Within each level, orders queue in a doubly-linked list (FIFO). A `HashMap<Long, Order>` order index enables O(1) cancel lookup; the intrusive `owningNode` pointer on each `Order` enables O(1) splice-out without scanning the level.

### MatchingEngine

A thin facade over `OrderBook` that owns the `OrderPool` and `FillPool`, performs event dispatch, and copies event fields into pooled `Order` objects to avoid per-event allocation on the matching hot path.

### OrderIngestionRing

A multi-producer Disruptor ring buffer that validates incoming order submissions before publishing `EngineEvent` entries to the single-consumer matching thread. The buffer size must be a power of two.

### Object pools

`OrderPool` and `FillPool` pre-allocate `Order` and `Fill` objects at construction time. The matching path acquires from and releases back to these pools, keeping allocation off the hot path entirely.

## Performance

Measured on Apple M4, MacBook Air, 16 GB RAM, Java 21.0.5 HotSpot, `-XX:+UseG1GC -Xms4g -Xmx4g`. Workload: 60% limit orders, 20% market orders, 20% cancels, prices within ±10 ticks of mid.

| Surface | Throughput |
|---|---|
| `OrderBook` (current allocating) | ~7,849,000 ops/s |
| `MatchingEngine` (current allocating) | ~7,681,000 ops/s |
| `OrderBook` (reusable fill list) | ~7,974,000 ops/s |
| `MatchingEngine` (reusable fill list) | ~7,578,000 ops/s |

**Latency percentiles (HdrHistogram, matching path):**

| Benchmark | P50 | P90 | P99 | P99.9 |
|---|---|---|---|---|
| `orderBook` / `engine` | 83 ns | 292–333 ns | 500 ns | 750–916 ns |

Tail latency (P99.9+) is GC-attributed. Young GC pauses average ~30 ms (P50) in these runs; no full GCs in throughput runs.

**Naive baseline (ArrayList + TimSort):** ~71,500 ops/s — roughly 110× slower, with a ±50% confidence interval caused by O(N log N) re-sort cost growing as book depth accumulates across iterations.

## Building

Requires Java 21 and Gradle (wrapper included).

```bash
# Compile, run tests, build JMH jar
./gradlew clean test jmhJar
```

If Gradle cannot reach its global cache:

```bash
GRADLE_USER_HOME="$PWD/.gradle" ./gradlew clean test jmhJar
```

## Running benchmarks

See [docs/run-benchmarks.md](docs/run-benchmarks.md) for the full runbook. Quick start:

```bash
# Throughput — baseline
java -XX:+UseG1GC -Xms4g -Xmx4g \
  -jar build/libs/lob-matching-engine-1.0-SNAPSHOT-jmh.jar \
  "OrderBookThroughputBenchmark.orderBookCurrentAllocating" \
  -wi 5 -w 2 -i 8 -r 3 -f 3

# Smoke test (fast, not for recording)
java -XX:+UseG1GC -Xms2g -Xmx2g \
  -jar build/libs/lob-matching-engine-1.0-SNAPSHOT-jmh.jar \
  "OrderBookThroughputBenchmark.orderBookCurrentAllocating" \
  -wi 1 -w 1 -i 1 -r 1 -f 1
```

Run benchmarks on AC power with background applications closed. Benchmark results are recorded in [docs/benchmarks.md](docs/benchmarks.md).

## Dependencies

| Library | Purpose |
|---|---|
| [LMAX Disruptor 3.4.4](https://github.com/LMAX-Exchange/disruptor) | Lock-free ring buffer for order ingestion |
| [HdrHistogram 2.1.12](https://github.com/HdrHistogram/HdrHistogram) | Latency distribution measurement |
| SLF4J + Logback | Logging |
| JUnit 5 + AssertJ | Unit and integration tests |
| JMH (via Gradle plugin) | Micro-benchmarks |

## Project layout

```
src/
  main/java/.../lobengine/
    book/         OrderBook, PriceLevel
    domain/       Order, Fill, EngineEvent, BookUpdate, ...
    engine/       MatchingEngine, OrderIngestionRing, EngineEventHandler
    marketdata/   MarketDataPublisher, BookUpdateListener, FillListener
    metrics/      EngineMetrics
    naive/        NaiveOrderBook (ArrayList + sort baseline)
    pool/         OrderPool, FillPool
    util/         DoublyLinkedList
    validation/   OrderValidator, OrderSubmission
  jmh/java/.../benchmarks/
    NaiveThroughputBenchmark
    OrderBookThroughputBenchmark
    LatencyDistributionBenchmark
    MetricsIsolationBenchmark
    MarketMicrostructureSimulator
docs/
  benchmarks.md          Full benchmark log with all runs
  run-benchmarks.md      Benchmark runbook
  latency-distribution.md Latency percentile records
  profiling/             JFR recordings, GC logs, allocation samples
```