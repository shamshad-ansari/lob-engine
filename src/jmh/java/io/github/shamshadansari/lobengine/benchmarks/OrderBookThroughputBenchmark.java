package io.github.shamshadansari.lobengine.benchmarks;

import io.github.shamshadansari.lobengine.book.OrderBook;
import io.github.shamshadansari.lobengine.domain.Fill;
import io.github.shamshadansari.lobengine.domain.Order;
import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderStatus;
import io.github.shamshadansari.lobengine.domain.OrderType;
import io.github.shamshadansari.lobengine.metrics.EngineMetrics;
import io.github.shamshadansari.lobengine.pool.FillPool;
import io.github.shamshadansari.lobengine.pool.OrderPool;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class OrderBookThroughputBenchmark {

    private static final int OP_LIMIT  = 0;
    private static final int OP_MARKET = 1;
    private static final int OP_CANCEL = 2;

    private OrderBook book;
    private OrderPool orderPool;
    private FillPool  fillPool;

    private int[]       ops;
    private OrderSide[] sides;
    private long[]      prices;
    private long[]      cancelOrderIds;
    private int         idx;

    @Setup
    public void setup() {
        orderPool = new OrderPool(2_000_000);
        fillPool  = new FillPool(2_000_000);
        book      = new OrderBook(1L, new EngineMetrics(), orderPool, fillPool);

        int n = 1_000_000;
        ops            = new int[n];
        sides          = new OrderSide[n];
        prices         = new long[n];
        cancelOrderIds = new long[n];
        generateMixedWorkload(n, 42L);
    }

    @Benchmark
    public void optimizedProcess(Blackhole bh) {
        int i = idx++ % ops.length;
        switch (ops[i]) {
            case OP_LIMIT -> processLimit(i, bh);
            case OP_MARKET -> processMarket(i, bh);
            case OP_CANCEL -> bh.consume(book.cancelOrder(cancelOrderIds[i]));
            default -> throw new IllegalStateException("Unknown operation: " + ops[i]);
        }
    }

    private void processLimit(int i, Blackhole bh) {
        Order order = acquireOrder(i, OrderType.LIMIT);
        order.priceTicks = prices[i];

        List<Fill> fills = book.processLimit(order);
        bh.consume(order.status);
        bh.consume(fills.size());
        releaseFills(fills, bh);

        if (order.owningNode == null) {
            orderPool.release(order);
        }
    }

    private void processMarket(int i, Blackhole bh) {
        Order order = acquireOrder(i, OrderType.MARKET);

        List<Fill> fills = book.processMarket(order);
        bh.consume(order.status);
        bh.consume(fills.size());
        releaseFills(fills, bh);
        orderPool.release(order);
    }

    private Order acquireOrder(int i, OrderType type) {
        Order order = orderPool.acquire();
        order.orderId        = i + 1L;
        order.side           = sides[i];
        order.type           = type;
        order.originalQty    = 100L;
        order.remainingQty   = 100L;
        order.status         = OrderStatus.PENDING;
        order.timestampNanos = i;
        return order;
    }

    private void releaseFills(List<Fill> fills, Blackhole bh) {
        for (Fill fill : fills) {
            bh.consume(fill.fillId);
            fillPool.release(fill);
        }
    }

    private void generateMixedWorkload(int n, long seed) {
        Random rng    = new Random(seed);
        long   mid    = 5_000L;
        long   spread = 10L;

        for (int i = 0; i < n; i++) {
            int roll = rng.nextInt(10);

            if (roll < 6) {
                ops[i]    = OP_LIMIT;
                sides[i]  = rng.nextBoolean() ? OrderSide.BID : OrderSide.ASK;
                prices[i] = mid + (rng.nextLong(spread * 2 + 1) - spread);
            } else if (roll < 8) {
                ops[i]   = OP_MARKET;
                sides[i] = rng.nextBoolean() ? OrderSide.BID : OrderSide.ASK;
            } else {
                ops[i]            = OP_CANCEL;
                cancelOrderIds[i] = (i > 0) ? (rng.nextLong(i) + 1) : 1L;
            }
        }
    }
}
