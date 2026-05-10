package io.github.shamshadansari.lobengine.benchmarks;

import io.github.shamshadansari.lobengine.book.OrderBook;
import io.github.shamshadansari.lobengine.domain.EngineEvent;
import io.github.shamshadansari.lobengine.domain.Fill;
import io.github.shamshadansari.lobengine.domain.Order;
import io.github.shamshadansari.lobengine.domain.OrderStatus;
import io.github.shamshadansari.lobengine.engine.MatchingEngine;
import io.github.shamshadansari.lobengine.metrics.EngineMetrics;
import io.github.shamshadansari.lobengine.pool.FillPool;
import io.github.shamshadansari.lobengine.pool.OrderPool;
import org.HdrHistogram.Histogram;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(3)
public class LatencyDistributionBenchmark {

    private static final int WORKLOAD_SIZE = 1_000_000;
    private static final long ORDER_ID_STRIDE = 10_000_000_000L;
    private static final int POOL_SIZE = 2_000_000;

    private MarketMicrostructureSimulator.EventSpec[] events;
    private OrderBook book;
    private MatchingEngine engine;
    private OrderPool orderPool;
    private FillPool fillPool;
    private FillPool engineFillPool;
    private EngineEvent reusableEvent;
    private ArrayList<Fill> reusableFills;
    private Histogram orderBookCurrentHistogram;
    private Histogram orderBookReusableHistogram;
    private Histogram engineCurrentHistogram;
    private Histogram engineReusableHistogram;
    private long idx;

    @Setup(Level.Trial)
    public void setupTrial() {
        events = MarketMicrostructureSimulator.generate(WORKLOAD_SIZE, 42L).events();
        reusableEvent = new EngineEvent();
        reusableFills = new ArrayList<>(8);
        orderBookCurrentHistogram = new Histogram(TimeUnit.SECONDS.toNanos(1), 3);
        orderBookReusableHistogram = new Histogram(TimeUnit.SECONDS.toNanos(1), 3);
        engineCurrentHistogram = new Histogram(TimeUnit.SECONDS.toNanos(1), 3);
        engineReusableHistogram = new Histogram(TimeUnit.SECONDS.toNanos(1), 3);
    }

    // Reset book/pool state every iteration so each iteration measures steady-state on a
    // fresh book instead of a heap that grows across the whole run. Histograms accumulate
    // across iterations to keep enough samples for tail percentiles.
    @Setup(Level.Iteration)
    public void setupIteration() {
        idx = 0;
        orderPool = new OrderPool(POOL_SIZE);
        fillPool = new FillPool(POOL_SIZE);
        book = new OrderBook(MarketMicrostructureSimulator.DEFAULT_INSTRUMENT_ID,
                             new EngineMetrics(),
                             orderPool,
                             fillPool);

        OrderPool engineOrderPool = new OrderPool(POOL_SIZE);
        engineFillPool = new FillPool(POOL_SIZE);
        engine = new MatchingEngine(MarketMicrostructureSimulator.DEFAULT_INSTRUMENT_ID,
                                    new EngineMetrics(),
                                    engineOrderPool,
                                    engineFillPool);
    }

    @Benchmark
    public void orderBookCurrentAllocatingLatency(Blackhole bh) {
        long sequence = idx++;
        MarketMicrostructureSimulator.EventSpec spec = events[(int) (sequence % events.length)];
        long start = System.nanoTime();
        processOrderBook(spec, sequence / events.length, true, bh);
        orderBookCurrentHistogram.recordValue(System.nanoTime() - start);
    }

    @Benchmark
    public void orderBookReusableFillListLatency(Blackhole bh) {
        long sequence = idx++;
        MarketMicrostructureSimulator.EventSpec spec = events[(int) (sequence % events.length)];
        long start = System.nanoTime();
        processOrderBook(spec, sequence / events.length, false, bh);
        orderBookReusableHistogram.recordValue(System.nanoTime() - start);
    }

    @Benchmark
    public void engineCurrentAllocatingLatency(Blackhole bh) {
        long sequence = idx++;
        MarketMicrostructureSimulator.EventSpec spec = events[(int) (sequence % events.length)];
        long start = System.nanoTime();
        List<Fill> fills = engine.processEvent(toEngineEvent(spec, sequence / events.length, reusableEvent));
        engineCurrentHistogram.recordValue(System.nanoTime() - start);
        bh.consume(fills.size());
        releaseFills(fills, engineFillPool, bh);
    }

    @Benchmark
    public void engineReusableFillListLatency(Blackhole bh) {
        long sequence = idx++;
        MarketMicrostructureSimulator.EventSpec spec = events[(int) (sequence % events.length)];
        long start = System.nanoTime();
        List<Fill> fills = engine.processEvent(toEngineEvent(spec, sequence / events.length, reusableEvent), reusableFills);
        engineReusableHistogram.recordValue(System.nanoTime() - start);
        bh.consume(fills.size());
        releaseFills(fills, engineFillPool, bh);
        reusableFills.clear();
    }

    @TearDown(Level.Trial)
    public void printHistograms() {
        printHistogram("orderBookCurrentAllocating", orderBookCurrentHistogram);
        printHistogram("orderBookReusableFillList", orderBookReusableHistogram);
        printHistogram("engineCurrentAllocating", engineCurrentHistogram);
        printHistogram("engineReusableFillList", engineReusableHistogram);
    }

    private void processOrderBook(MarketMicrostructureSimulator.EventSpec spec,
                                  long cycle,
                                  boolean allocating,
                                  Blackhole bh) {
        long idOffset = cycle * ORDER_ID_STRIDE;
        switch (spec.kind) {
            case NEW -> processNew(spec, idOffset, allocating, bh);
            case CANCEL -> bh.consume(book.cancelOrder(spec.targetOrderId + idOffset));
            case MODIFY -> bh.consume(book.processModify(spec.targetOrderId + idOffset, spec.newPriceTicks, spec.newQty));
        }
    }

    private void processNew(MarketMicrostructureSimulator.EventSpec spec,
                            long idOffset,
                            boolean allocating,
                            Blackhole bh) {
        Order order = orderPool.acquire();
        order.orderId = spec.orderId + idOffset;
        order.side = spec.side;
        order.type = spec.type;
        order.priceTicks = spec.priceTicks;
        order.originalQty = spec.qty;
        order.remainingQty = spec.qty;
        order.status = OrderStatus.PENDING;
        order.timestampNanos = spec.timestampNanos;

        List<Fill> fills;
        if (allocating) {
            fills = switch (spec.type) {
                case LIMIT -> book.processLimit(order);
                case MARKET -> book.processMarket(order);
                case IOC -> book.processIOC(order);
            };
        } else {
            reusableFills.clear();
            switch (spec.type) {
                case LIMIT -> book.processLimit(order, reusableFills);
                case MARKET -> book.processMarket(order, reusableFills);
                case IOC -> book.processIOC(order, reusableFills);
            }
            fills = reusableFills;
        }

        bh.consume(order.status);
        bh.consume(fills.size());
        releaseFills(fills, fillPool, bh);
        if (order.owningNode == null) {
            orderPool.release(order);
        }
        if (!allocating) {
            reusableFills.clear();
        }
    }

    private void releaseFills(List<Fill> fills, FillPool sourcePool, Blackhole bh) {
        for (int i = 0, n = fills.size(); i < n; i++) {
            Fill fill = fills.get(i);
            bh.consume(fill.fillId);
            sourcePool.release(fill);
        }
    }

    private EngineEvent toEngineEvent(MarketMicrostructureSimulator.EventSpec spec, long cycle, EngineEvent event) {
        long idOffset = cycle * ORDER_ID_STRIDE;
        event.reset();
        event.instrumentId = spec.instrumentId;
        event.timestampNanos = spec.timestampNanos;
        switch (spec.kind) {
            case NEW -> {
                event.type = EngineEvent.Type.NEW_ORDER;
                event.orderId = spec.orderId + idOffset;
                event.side = spec.side;
                event.orderType = spec.type;
                event.priceTicks = spec.priceTicks;
                event.qty = spec.qty;
            }
            case CANCEL -> {
                event.type = EngineEvent.Type.CANCEL_ORDER;
                event.targetOrderId = spec.targetOrderId + idOffset;
            }
            case MODIFY -> {
                event.type = EngineEvent.Type.MODIFY_ORDER;
                event.targetOrderId = spec.targetOrderId + idOffset;
                event.newPriceTicks = spec.newPriceTicks;
                event.newQty = spec.newQty;
            }
        }
        return event;
    }

    private void printHistogram(String name, Histogram histogram) {
        System.out.printf(
            "%s latency nanos: samples=%d p50=%d p90=%d p99=%d p99.9=%d max=%d%n",
            name,
            histogram.getTotalCount(),
            histogram.getValueAtPercentile(50.0),
            histogram.getValueAtPercentile(90.0),
            histogram.getValueAtPercentile(99.0),
            histogram.getValueAtPercentile(99.9),
            histogram.getMaxValue());
    }
}
