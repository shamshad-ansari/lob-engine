# LOB Engine — Future Work

This document outlines potential performance improvements and architectural
extensions, prioritised by impact and implementation cost. All allocation figures
are drawn from the JFR sampling run recorded in `docs/benchmarks.md` (May 2026,
Apple M4, Java 21, G1GC).

---

## 1. Allocation Reduction (High Impact)

The current JFR allocation ranking reveals that the dominant sources of GC
pressure are structural — rooted in the choice of boxed-key Java collections
rather than per-event business logic:

| Rank | Type | Sampled |
|------|------|---------|
| 1 | `java.lang.Long` | 28,632 MB |
| 2 | `java.util.HashMap$Node` | 10,599 MB |
| 3 | `DoublyLinkedList$Node` | 9,029 MB |
| 4 | `io.github.…Order` | 5,514 MB |
| 5 | `java.util.ArrayList` | 4,899 MB |
| 7 | `io.github.…Fill` | 2,204 MB |
| 8 | `java.util.TreeMap$Entry` | 1,531 MB |

### 1.1 Primitive-key collections

`HashMap<Long, Order>` and `TreeMap<Long, PriceLevel>` box every key on every
`put` and `get`. Together, `Long` keys and `HashMap$Node` entries account for
roughly **39 GB** of sampled allocation — the single largest optimisation
target. Replacing them with a primitive-long-keyed open-addressing map
(e.g. Eclipse Collections `LongObjectHashMap`, or Agrona `Long2ObjectHashMap`)
would eliminate boxing entirely on the order-index and price-level paths.

`TreeMap` additionally allocates a `TreeMap$Entry` per price level (1,531 MB).
An array-indexed bucket structure keyed by tick offset would avoid both the
entry allocation and the O(log N) tree traversal per access, at the cost of
requiring a bounded tick range.

### 1.2 Intrusive list or pooled nodes

Every resting limit order allocates a `DoublyLinkedList$Node` (9,029 MB
sampled). Two remediation strategies exist:

- **Node pooling.** Extend the existing `OrderPool`/`FillPool` pattern to
  `Node` objects. Low risk; same design as current pooling.
- **Intrusive linked list.** Embed `prev`/`next` pointers directly on
  `Order`, eliminating the separate `Node` object entirely. Higher invasiveness
  but removes the allocation site permanently.

### 1.3 Order and Fill pool sizing

`Order` (5,514 MB) and `Fill` (2,204 MB) appear in the ranking despite active
pooling, indicating pool misses under sustained load. The current pool size of
2 M objects is fixed and silent on overflow. Two improvements:

- Expose pool miss rate via `EngineMetrics` so miss pressure is observable
  without a JFR run.
- Size pools against a measured steady-state depth rather than a static
  constant.

### 1.4 Remaining ArrayList allocation sites

`ArrayList` (4,899 MB) is the fifth-largest allocation source. The primary
remaining site is `OrderBook.getSnapshot()`, which allocates two fresh lists on
every call. Passing caller-provided lists as a snapshot sink, following the same
pattern as the `processLimit(Order, List<Fill>)` overload, would eliminate this.

---

## 2. Hot-Path CPU Optimisations (Medium Impact)

### 2.1 O(1) best-bid/ask maintenance on the add path

`updateBestBidCache()` and `updateBestAskCache()` call `TreeMap.firstKey()` on
every order add, which walks the red-black tree from the root on each invocation.
For the add path this is unnecessary: if the incoming price is better than the
current cached best, it becomes the new best by inspection — no tree traversal
required. Level-empty events on the cancel and match paths still require a
`firstKey()` call, but these are less frequent in a workload dominated by resting
adds.

### 2.2 Timestamp acquisition

`System.nanoTime()` is called once per fill inside `matchAgainstLevel` and once
per book-update event inside `MarketDataPublisher`. Capturing a single timestamp
at event ingress and propagating it through the call chain would reduce syscall
overhead on fills-heavy workloads without changing the observable semantics for
downstream consumers.

### 2.3 processModify resource ordering

`processModify` acquires a replacement `Order` from the pool before releasing the
cancelled one. Reversing the order — release first, then acquire — allows the
pool to recycle the same slot, reducing peak pool utilisation during
cancel-and-resubmit.

---

## 3. Known Correctness Issues

These are not performance items but should be addressed before production use.

### 3.1 Cancel counter inflation

`OrderBook.cancelOrder` calls `metrics.recordCancel()` unconditionally. Because
`processModify` invokes `cancelOrder` as part of its internal cancel-and-resubmit
path, every price-change modify inflates the cancellation counter by one. The
counter should distinguish between client-initiated cancels and engine-internal
ones.

### 3.2 Internal order ID collision

`nextInternalId` is initialised at `1_000_000_000L` to generate replacement IDs
during modify. A client that submits order IDs at or above this threshold will
collide with engine-generated IDs. A dedicated ID namespace or a globally
monotonic allocator with explicit range partitioning would remove the ambiguity.

---

## 4. Ingestion Pipeline

### 4.1 Wait strategy tuning

The current `BlockingWaitStrategy` uses a `ReentrantLock` in the consumer idle
path, which introduces an OS scheduling round-trip on each wakeup. For
latency-sensitive deployments, `YieldingWaitStrategy` or `BusySpinWaitStrategy`
would reduce wakeup latency at the cost of a dedicated CPU core.

### 4.2 Producer type

`ProducerType.MULTI` was chosen conservatively but the current architecture has
a single ingestion path. Switching to `ProducerType.SINGLE` removes the CAS on
the producer cursor and reduces contention on the ring-buffer claim sequence.

---

## 5. Architectural Extensions

### 5.1 Multi-instrument support

The engine is scoped to a single instrument. A natural extension is a
`MatchingEngineRouter` that maintains one `OrderBook` per instrument and
dispatches incoming events by `instrumentId`. Thread-safety options include one
engine thread per instrument (full isolation) or a shared single-writer thread
with a dispatch table.

### 5.2 Persistence and replay

There is currently no mechanism to persist the order stream or reconstruct book
state after a restart. A sequenced event log (append-only, fsync-per-batch)
written before events enter the ring buffer would enable crash recovery and
deterministic replay for testing.

### 5.3 FIX / binary protocol gateway

`OrderIngestionRing` accepts pre-validated `EngineEvent` objects. A protocol
gateway layer — parsing FIX 4.4 or a binary protocol such as ITCH/OUCH — would
make the engine reachable from standard trading infrastructure without coupling
the core to a wire format.

---

## 6. Benchmarking Gaps

- **Coordinated omission.** The current `LatencyDistributionBenchmark` does not
  account for coordinated omission: when a slow iteration delays the next
  measurement, the benchmark under-reports tail latency. Gil Tene's
  `wrk2`-style correction or JMH's `Mode.SampleTime` with an external load
  generator would produce more realistic percentile estimates.
- **Naive baseline currency.** The NaiveOrderBook baseline was measured under a
  different workload definition than the current `MarketMicrostructureSimulator`.
  Re-running it under the same simulator would give a directly comparable
  improvement ratio.
- **Multi-fork statistical validation.** Increasing `@Fork` from 3 to 5 and
  cross-checking with `async-profiler` flame graphs would strengthen the claim
  that measured throughput reflects steady-state JIT performance rather than
  JVM-startup variance.
