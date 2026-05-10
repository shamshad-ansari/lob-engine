package io.github.shamshadansari.lobengine.benchmarks;

import io.github.shamshadansari.lobengine.book.OrderBook;
import io.github.shamshadansari.lobengine.domain.Fill;
import io.github.shamshadansari.lobengine.domain.Order;
import io.github.shamshadansari.lobengine.domain.OrderStatus;
import io.github.shamshadansari.lobengine.metrics.EngineMetrics;
import io.github.shamshadansari.lobengine.pool.FillPool;
import io.github.shamshadansari.lobengine.pool.OrderPool;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(3)
public class MetricsIsolationBenchmark {

    private static final int WORKLOAD_SIZE = 1_000_000;
    private static final long ORDER_ID_STRIDE = 10_000_000_000L;

    private MarketMicrostructureSimulator.EventSpec[] events;
    private BenchBook atomicMetricsBook;
    private BenchBook primitiveMetricsBook;
    private BenchBook noOpMetricsBook;
    private long idx;

    @Setup(Level.Trial)
    public void setupTrial() {
        events = MarketMicrostructureSimulator.generate(WORKLOAD_SIZE, 42L).events();
    }

    // Reset book/pool state every iteration so each iteration measures steady-state on a
    // fresh book. Without this, the three books accumulate resting orders across iterations
    // and the per-iteration throughput collapses, making the metrics-overhead comparison
    // measure GC pressure instead of counter overhead.
    @Setup(Level.Iteration)
    public void setupIteration() {
        idx = 0;
        atomicMetricsBook = new BenchBook(new EngineMetrics());
        primitiveMetricsBook = new BenchBook(new PrimitiveEngineMetrics());
        noOpMetricsBook = new BenchBook(new NoOpEngineMetrics());
    }

    @Benchmark
    public void atomicLongMetrics(Blackhole bh) {
        long sequence = idx++;
        atomicMetricsBook.process(events[(int) (sequence % events.length)], sequence / events.length, bh);
    }

    @Benchmark
    public void primitiveLongMetrics(Blackhole bh) {
        long sequence = idx++;
        primitiveMetricsBook.process(events[(int) (sequence % events.length)], sequence / events.length, bh);
    }

    @Benchmark
    public void noOpMetrics(Blackhole bh) {
        long sequence = idx++;
        noOpMetricsBook.process(events[(int) (sequence % events.length)], sequence / events.length, bh);
    }

    private static final class BenchBook {
        private final OrderPool orderPool = new OrderPool(2_000_000);
        private final FillPool fillPool = new FillPool(2_000_000);
        private final OrderBook book;

        private BenchBook(EngineMetrics metrics) {
            this.book = new OrderBook(MarketMicrostructureSimulator.DEFAULT_INSTRUMENT_ID,
                                      metrics,
                                      orderPool,
                                      fillPool);
        }

        private void process(MarketMicrostructureSimulator.EventSpec spec, long cycle, Blackhole bh) {
            long idOffset = cycle * ORDER_ID_STRIDE;
            switch (spec.kind) {
                case NEW -> processNew(spec, idOffset, bh);
                case CANCEL -> bh.consume(book.cancelOrder(spec.targetOrderId + idOffset));
                case MODIFY -> bh.consume(book.processModify(spec.targetOrderId + idOffset, spec.newPriceTicks, spec.newQty));
            }
        }

        private void processNew(MarketMicrostructureSimulator.EventSpec spec, long idOffset, Blackhole bh) {
            Order order = orderPool.acquire();
            order.orderId = spec.orderId + idOffset;
            order.side = spec.side;
            order.type = spec.type;
            order.priceTicks = spec.priceTicks;
            order.originalQty = spec.qty;
            order.remainingQty = spec.qty;
            order.status = OrderStatus.PENDING;
            order.timestampNanos = spec.timestampNanos;

            List<Fill> fills = switch (spec.type) {
                case LIMIT -> book.processLimit(order);
                case MARKET -> book.processMarket(order);
                case IOC -> book.processIOC(order);
            };

            bh.consume(order.status);
            bh.consume(fills.size());
            for (int i = 0, n = fills.size(); i < n; i++) {
                Fill fill = fills.get(i);
                bh.consume(fill.fillId);
                fillPool.release(fill);
            }
            if (order.owningNode == null) {
                orderPool.release(order);
            }
        }
    }

    public static final class PrimitiveEngineMetrics extends EngineMetrics {
        private long ordersProcessed;
        private long fillsGenerated;
        private long cancellations;
        private long cancelMisses;

        @Override public void recordOrderProcessed() { ordersProcessed++; }
        @Override public void recordFills(int n)     { fillsGenerated += n; }
        @Override public void recordCancel()         { cancellations++; }
        @Override public void recordCancelMiss()     { cancelMisses++; }

        @Override public long ordersProcessed() { return ordersProcessed; }
        @Override public long fillsGenerated()  { return fillsGenerated; }
        @Override public long cancellations()   { return cancellations; }
        @Override public long cancelMisses()    { return cancelMisses; }

        @Override
        public void reset() {
            ordersProcessed = 0;
            fillsGenerated = 0;
            cancellations = 0;
            cancelMisses = 0;
        }
    }

    public static final class NoOpEngineMetrics extends EngineMetrics {
        @Override public void recordOrderProcessed() { }
        @Override public void recordFills(int n)     { }
        @Override public void recordCancel()         { }
        @Override public void recordCancelMiss()     { }

        @Override public long ordersProcessed() { return 0; }
        @Override public long fillsGenerated()  { return 0; }
        @Override public long cancellations()   { return 0; }
        @Override public long cancelMisses()    { return 0; }
        @Override public void reset()           { }
    }
}
