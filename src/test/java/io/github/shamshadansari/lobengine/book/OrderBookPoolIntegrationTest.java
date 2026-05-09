package io.github.shamshadansari.lobengine.book;

import io.github.shamshadansari.lobengine.domain.Fill;
import io.github.shamshadansari.lobengine.domain.Order;
import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderStatus;
import io.github.shamshadansari.lobengine.domain.OrderType;
import io.github.shamshadansari.lobengine.metrics.EngineMetrics;
import io.github.shamshadansari.lobengine.pool.FillPool;
import io.github.shamshadansari.lobengine.pool.OrderPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBookPoolIntegrationTest {

    private static final long INSTRUMENT_ID = 1L;

    private EngineMetrics metrics;
    private OrderPool     orderPool;
    private FillPool      fillPool;
    private OrderBook     book;
    private long          nextId;

    @BeforeEach
    void setUp() {
        metrics   = new EngineMetrics();
        orderPool = new OrderPool(10_000);
        fillPool  = new FillPool(10_000);
        book      = new OrderBook(INSTRUMENT_ID, metrics, orderPool, fillPool);
        nextId    = 1;
    }

    @Test
    void pooledLimitMarketAndCancelWorkloadHasNoMisses() {
        for (int i = 0; i < 1_000; i++) {
            Order ask = limit(OrderSide.ASK, 5000 + (i % 10), 100);
            book.processLimit(ask);

            if ((i & 1) == 0) {
                assertThat(book.cancelOrder(ask.orderId)).isTrue();
            } else {
                Order marketBid = market(OrderSide.BID, 100);
                List<Fill> fills = book.processMarket(marketBid);

                assertThat(fills).hasSize(1);
                orderPool.release(marketBid);
                releaseFills(fills);
            }
        }

        assertThat(orderPool.misses()).isZero();
        assertThat(fillPool.misses()).isZero();
    }

    @Test
    void fullFillReleasesRestingOrderWithNullOwningNode() {
        Order ask = limit(OrderSide.ASK, 5000, 100);
        book.processLimit(ask);

        Order marketBid = market(OrderSide.BID, 100);
        List<Fill> fills = book.processMarket(marketBid);

        Order reacquired = orderPool.acquire();
        assertThat(reacquired).isSameAs(ask);
        assertThat(reacquired.owningNode).isNull();
        assertThat(reacquired.status).isEqualTo(OrderStatus.PENDING);

        orderPool.release(reacquired);
        orderPool.release(marketBid);
        releaseFills(fills);
    }

    @Test
    void processModifyCancelAndResubmitPreservesFields() {
        Order original = limit(OrderSide.ASK, 5000, 100);
        original.clientOrderId = "client-1";
        book.processLimit(original);

        Order replacement = book.processModify(original.orderId, 5001, 100);

        assertThat(replacement).isNotSameAs(original);
        assertThat(replacement.clientOrderId).isEqualTo("client-1");
        assertThat(replacement.side).isEqualTo(OrderSide.ASK);
        assertThat(replacement.type).isEqualTo(OrderType.LIMIT);
        assertThat(replacement.owningNode).isNotNull();
        assertThat(original.owningNode).isNull();
    }

    @Test
    void callerReleaseOfIncomingReturnsOrderToPool() {
        Order ask = limit(OrderSide.ASK, 5000, 100);
        book.processLimit(ask);

        Order marketBid = market(OrderSide.BID, 100);
        List<Fill> fills = book.processMarket(marketBid);
        int availableBeforeRelease = orderPool.available();

        orderPool.release(marketBid);

        assertThat(orderPool.available()).isEqualTo(availableBeforeRelease + 1);
        releaseFills(fills);
    }

    private Order limit(OrderSide side, long priceTicks, long qty) {
        Order order = orderPool.acquire();
        order.orderId        = nextId++;
        order.side           = side;
        order.type           = OrderType.LIMIT;
        order.priceTicks     = priceTicks;
        order.originalQty    = qty;
        order.remainingQty   = qty;
        order.status         = OrderStatus.PENDING;
        order.timestampNanos = nextId;
        return order;
    }

    private Order market(OrderSide side, long qty) {
        Order order = orderPool.acquire();
        order.orderId        = nextId++;
        order.side           = side;
        order.type           = OrderType.MARKET;
        order.originalQty    = qty;
        order.remainingQty   = qty;
        order.status         = OrderStatus.PENDING;
        order.timestampNanos = nextId;
        return order;
    }

    private void releaseFills(List<Fill> fills) {
        for (Fill fill : fills) {
            fillPool.release(fill);
        }
    }
}
