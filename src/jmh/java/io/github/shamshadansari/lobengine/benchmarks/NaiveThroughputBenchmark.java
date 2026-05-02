package io.github.shamshadansari.lobengine.benchmarks;

import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderStatus;
import io.github.shamshadansari.lobengine.domain.OrderType;
import io.github.shamshadansari.lobengine.naive.NaiveOrder;
import io.github.shamshadansari.lobengine.naive.NaiveOrderBook;
import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class NaiveThroughputBenchmark {

    // Operation type codes stored separately so NaiveOrder fields stay mutation-safe
    private static final int OP_LIMIT  = 0;
    private static final int OP_MARKET = 1;
    private static final int OP_CANCEL = 2;

    private NaiveOrderBook book;
    private NaiveOrder[]   orders;
    private int[]          ops;       // parallel op-type array
    private int            idx;

    @Setup(Level.Trial)
    public void setup() {
        book   = new NaiveOrderBook();
        int n  = 1_000_000;
        orders = new NaiveOrder[n];
        ops    = new int[n];
        generateMixedWorkload(n, 42L);
    }

    @Setup(Level.Iteration)
    public void resetIndex() {
        // Restart from the beginning each iteration so every iteration sees
        // a fresh sequence.  The book accumulates state across iterations,
        // which is intentional: a real book always has open interest.
        idx = 0;
    }

    @Benchmark
    public void naiveProcess() {
        int i = idx++ % orders.length;
        switch (ops[i]) {
            case OP_LIMIT  -> {
                NaiveOrder o = orders[i];
                o.remainingQty = o.originalQty;
                o.status       = OrderStatus.PENDING;
                book.processLimit(o);
            }
            case OP_MARKET -> {
                NaiveOrder o = orders[i];
                o.remainingQty = o.originalQty;
                o.status       = OrderStatus.PENDING;
                book.processMarket(o);
            }
            case OP_CANCEL -> book.cancel(orders[i].orderId);
        }
    }

    // -----------------------------------------------------------------------
    // Workload generator
    //
    // Distribution: 60 % limit orders, 20 % market orders, 20 % cancels.
    // Limit prices are clustered within ±10 ticks of a fixed mid (5000) so
    // that roughly half of all limit orders cross the spread and produce fills,
    // keeping the book from growing without bound.
    // Cancel operations reference random order IDs in the pre-generated range;
    // most will be cache-misses (return false), exercising the linear scan cost.
    // -----------------------------------------------------------------------

    private void generateMixedWorkload(int n, long seed) {
        Random rng    = new Random(seed);
        long   mid    = 5_000L;
        long   spread = 10L;         // ±10 ticks around mid

        for (int i = 0; i < n; i++) {
            int roll = rng.nextInt(10);

            NaiveOrder o = new NaiveOrder();
            o.orderId        = i + 1L;
            o.timestampNanos = i;

            if (roll < 6) {                          // 60 % limit
                ops[i]          = OP_LIMIT;
                o.type          = OrderType.LIMIT;
                o.side          = rng.nextBoolean() ? OrderSide.BID : OrderSide.ASK;
                o.priceTicks    = mid + (rng.nextLong(spread * 2 + 1) - spread);
                o.originalQty   = 100L;
                o.remainingQty  = 100L;
                o.status        = OrderStatus.PENDING;

            } else if (roll < 8) {                   // 20 % market
                ops[i]          = OP_MARKET;
                o.type          = OrderType.MARKET;
                o.side          = rng.nextBoolean() ? OrderSide.BID : OrderSide.ASK;
                o.priceTicks    = 0L;
                o.originalQty   = 100L;
                o.remainingQty  = 100L;
                o.status        = OrderStatus.PENDING;

            } else {                                  // 20 % cancel
                ops[i]          = OP_CANCEL;
                o.type          = OrderType.LIMIT;   // type unused for cancel ops
                // Target a random previously-generated order ID
                o.orderId       = (n > 1) ? (rng.nextLong(i + 1) + 1) : 1L;
                o.status        = OrderStatus.PENDING;
            }

            orders[i] = o;
        }
    }
}
