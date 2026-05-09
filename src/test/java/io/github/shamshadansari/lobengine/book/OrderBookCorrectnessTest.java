package io.github.shamshadansari.lobengine.book;

import io.github.shamshadansari.lobengine.domain.Fill;
import io.github.shamshadansari.lobengine.domain.Order;
import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderStatus;
import io.github.shamshadansari.lobengine.domain.OrderType;
import io.github.shamshadansari.lobengine.metrics.EngineMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-023 — Phase 0 correctness suite re-run on the optimized OrderBook.
 *
 * These nine tests are a direct adaptation of the reference NaiveOrderBookTest
 * correctness scenarios. They prove that the fast book and the naive book
 * produce identical semantic results.
 */
class OrderBookCorrectnessTest {

    private static final long INSTRUMENT_ID = 1L;

    private EngineMetrics metrics;
    private OrderBook     book;
    private long          nextId   = 1;
    private long          nextTime = 1_000_000L;

    @BeforeEach
    void setUp() {
        metrics  = new EngineMetrics();
        book     = new OrderBook(INSTRUMENT_ID, metrics);
        nextId   = 1;
        nextTime = 1_000_000L;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Order limit(OrderSide side, long priceTicks, long qty) {
        Order o = new Order();
        o.orderId        = nextId++;
        o.side           = side;
        o.type           = OrderType.LIMIT;
        o.priceTicks     = priceTicks;
        o.originalQty    = qty;
        o.remainingQty   = qty;
        o.status         = OrderStatus.PENDING;
        o.timestampNanos = nextTime++;
        return o;
    }

    private Order market(OrderSide side, long qty) {
        Order o = new Order();
        o.orderId        = nextId++;
        o.side           = side;
        o.type           = OrderType.MARKET;
        o.priceTicks     = 0;
        o.originalQty    = qty;
        o.remainingQty   = qty;
        o.status         = OrderStatus.PENDING;
        o.timestampNanos = nextTime++;
        return o;
    }

    // =========================================================================
    // C-01 — Bid below spread rests in book, produces no fill
    // =========================================================================

    @Test
    void C01_bidBelowSpread_restsNoCross() {
        book.processLimit(limit(OrderSide.ASK, 5000, 100));
        List<Fill> fills = book.processLimit(limit(OrderSide.BID, 4999, 100));

        assertThat(fills).isEmpty();
        assertThat(book.hasBids()).isTrue();
        assertThat(book.hasAsks()).isTrue();
        assertThat(book.bestBid()).isEqualTo(4999L);
    }

    // =========================================================================
    // C-02 — Fill at resting price: buyer offers above ask, fills at ask price
    // =========================================================================

    @Test
    void C02_bidAboveAsk_fillsAtAskPrice() {
        book.processLimit(limit(OrderSide.ASK, 5000, 100));
        List<Fill> fills = book.processLimit(limit(OrderSide.BID, 5100, 100));

        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).fillPriceTicks).isEqualTo(5000L);
    }

    // =========================================================================
    // C-03 — Symmetric: ask below bid fills at bid price
    // =========================================================================

    @Test
    void C03_askBelowBid_fillsAtBidPrice() {
        book.processLimit(limit(OrderSide.BID, 5000, 100));
        List<Fill> fills = book.processLimit(limit(OrderSide.ASK, 4900, 100));

        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).fillPriceTicks).isEqualTo(5000L);
    }

    // =========================================================================
    // C-04 — Incoming larger than resting: remainder rests in book
    // =========================================================================

    @Test
    void C04_incomingLargerThanResting_remainderRests() {
        book.processLimit(limit(OrderSide.ASK, 5000, 60));
        Order bid = limit(OrderSide.BID, 5000, 100);
        book.processLimit(bid);

        assertThat(book.hasBids()).isTrue();
        assertThat(book.bestBid()).isEqualTo(5000L);
        assertThat(bid.remainingQty).isEqualTo(40L);
        assertThat(bid.status).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    }

    // =========================================================================
    // C-05 — Resting larger than incoming: resting stays with reduced qty
    // =========================================================================

    @Test
    void C05_restingLargerThanIncoming_restingStaysPartiallyFilled() {
        Order ask = limit(OrderSide.ASK, 5000, 200);
        book.processLimit(ask);
        book.processLimit(limit(OrderSide.BID, 5000, 80));

        assertThat(book.hasAsks()).isTrue();
        assertThat(ask.remainingQty).isEqualTo(120L);
        assertThat(ask.status).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(ask.originalQty).isEqualTo(200L);  // originalQty is preserved; only remainingQty changes.
    }

    // =========================================================================
    // C-06 — FIFO: earliest order at same price level fills first
    // =========================================================================

    @Test
    void C06_fifo_earliestAskAtSamePriceFilledFirst() {
        Order first  = limit(OrderSide.ASK, 5000, 100);
        Order second = limit(OrderSide.ASK, 5000, 100);
        Order third  = limit(OrderSide.ASK, 5000, 100);
        long firstOrderId = first.orderId;
        book.processLimit(first);
        book.processLimit(second);
        book.processLimit(third);

        List<Fill> fills = book.processLimit(limit(OrderSide.BID, 5000, 100));

        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).sellOrderId).isEqualTo(firstOrderId);
        assertThat(second.status).isEqualTo(OrderStatus.PENDING);
        assertThat(third.status).isEqualTo(OrderStatus.PENDING);
    }

    // =========================================================================
    // C-07 — Price priority: lowest ask fills before higher ask
    // =========================================================================

    @Test
    void C07_pricePriority_cheapestAskFillsFirst() {
        Order expensive = limit(OrderSide.ASK, 5001, 100);
        Order cheap     = limit(OrderSide.ASK, 5000, 100);
        long cheapOrderId = cheap.orderId;
        book.processLimit(expensive);
        book.processLimit(cheap);

        List<Fill> fills = book.processLimit(limit(OrderSide.BID, 5001, 100));

        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).fillPriceTicks).isEqualTo(5000L);
        assertThat(fills.get(0).sellOrderId).isEqualTo(cheapOrderId);
    }

    // =========================================================================
    // C-08 — Multi-level sweep: bid drains three ask levels in price order
    // =========================================================================

    @Test
    void C08_multiLevelSweep_bidDrainsLevelsInPriceOrder() {
        book.processLimit(limit(OrderSide.ASK, 5000, 100));
        book.processLimit(limit(OrderSide.ASK, 5001, 100));
        book.processLimit(limit(OrderSide.ASK, 5002, 100));

        List<Fill> fills = book.processLimit(limit(OrderSide.BID, 5002, 250));

        assertThat(fills).hasSize(3);
        assertThat(fills.get(0).fillPriceTicks).isEqualTo(5000L);
        assertThat(fills.get(0).fillQty).isEqualTo(100L);
        assertThat(fills.get(1).fillPriceTicks).isEqualTo(5001L);
        assertThat(fills.get(1).fillQty).isEqualTo(100L);
        assertThat(fills.get(2).fillPriceTicks).isEqualTo(5002L);
        assertThat(fills.get(2).fillQty).isEqualTo(50L);
        // A partial remainder at the 5002 level persists after the sweep.
        assertThat(book.hasAsks()).isTrue();
        assertThat(book.bestAsk()).isEqualTo(5002L);
    }

    // =========================================================================
    // C-09 — Market order with no liquidity: CANCELLED, never rests
    // =========================================================================

    @Test
    void C09_marketOrderNoLiquidity_cancelledNeverRests() {
        Order m = market(OrderSide.BID, 100);
        List<Fill> fills = book.processMarket(m);

        assertThat(fills).isEmpty();
        assertThat(m.status).isEqualTo(OrderStatus.CANCELLED);
        assertThat(book.hasBids()).isFalse();
    }
}
