package io.github.shamshadansari.lobengine.book;

import io.github.shamshadansari.lobengine.BookSnapshot;
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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * T-024 — Edge-case and invariant tests:
 * empty-book operations, exact cross-level sweeps, TreeMap integrity,
 * and snapshot consistency.
 */
class EdgeCaseTest {

    private static final long INSTRUMENT_ID = 1L;

    private EngineMetrics metrics;
    private OrderBook     book;
    private long          nextId = 1;

    @BeforeEach
    void setUp() {
        metrics = new EngineMetrics();
        book    = new OrderBook(INSTRUMENT_ID, metrics);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Order ask(long priceTicks, long qty) {
        return order(OrderSide.ASK, priceTicks, qty);
    }

    private Order bid(long priceTicks, long qty) {
        return order(OrderSide.BID, priceTicks, qty);
    }

    private Order market(OrderSide side, long qty) {
        Order o = new Order();
        o.orderId      = nextId++;
        o.side         = side;
        o.type         = OrderType.MARKET;
        o.priceTicks   = 0;
        o.originalQty  = qty;
        o.remainingQty = qty;
        o.status       = OrderStatus.PENDING;
        return o;
    }

    private Order order(OrderSide side, long priceTicks, long qty) {
        Order o = new Order();
        o.orderId      = nextId++;
        o.side         = side;
        o.type         = OrderType.LIMIT;
        o.priceTicks   = priceTicks;
        o.originalQty  = qty;
        o.remainingQty = qty;
        o.status       = OrderStatus.PENDING;
        return o;
    }

    // =========================================================================
    // Empty book market buy — zero fills, no exception
    // =========================================================================

    @Test
    void emptyBook_marketBuy_zeroFillsNoException() {
        Order m = market(OrderSide.BID, 100);
        List<Fill> fills = assertDoesNotThrow(() -> book.processMarket(m));

        assertThat(fills).isEmpty();
        assertThat(m.status).isEqualTo(OrderStatus.CANCELLED);
        assertThat(book.hasBids()).isFalse();
    }

    // =========================================================================
    // Empty book limit sell — added to asks, bestAsk updated from MAX_VALUE
    // =========================================================================

    @Test
    void emptyBook_limitSell_addedToAsks_bestAskUpdated() {
        assertThat(book.bestAsk()).isEqualTo(Long.MAX_VALUE);

        book.processLimit(ask(5001, 100));

        assertThat(book.hasAsks()).isTrue();
        assertThat(book.bestAsk()).isEqualTo(5001L);
    }

    // =========================================================================
    // Exact fill across two levels — both levels removed, both caches updated
    // =========================================================================

    @Test
    void exactFillAcrossTwoLevels_bothLevelsRemoved_cachesUpdated() {
        book.processLimit(ask(5000, 100));
        book.processLimit(ask(5001, 100));

        // Incoming bid for 200 sweeps both levels entirely.
        List<Fill> fills = book.processLimit(bid(5001, 200));

        assertThat(fills).hasSize(2);
        assertThat(book.hasAsks()).isFalse();
        assertThat(book.bestAsk()).isEqualTo(Long.MAX_VALUE);
    }

    // =========================================================================
    // cancelOrder returns false for already-cancelled ID (not in orderIndex)
    // =========================================================================

    @Test
    void cancelAlreadyCancelledId_returnsFalse() {
        Order o = ask(5000, 100);
        book.addToBook(o);

        assertThat(book.cancelOrder(o.orderId)).isTrue();
        assertThat(book.cancelOrder(o.orderId)).isFalse();  // Repeated cancellation attempt.
        assertThat(metrics.cancelMisses()).isEqualTo(1L);
    }

    // =========================================================================
    // TreeMap never contains an empty PriceLevel after matching consumes a level
    // =========================================================================

    @Test
    void treeMap_neverContainsEmptyPriceLevel_afterMatchingConsumesLevel() {
        book.processLimit(ask(5000, 100));
        book.processLimit(ask(5001, 100));

        // Consuming the 5000 level entirely removes it from the book.
        book.processLimit(bid(5000, 100));

        // After exhausting the 5000 level, bestAsk must reflect the next available price.
        assertThat(book.bestAsk()).isEqualTo(5001L);

        BookSnapshot snap = book.getSnapshot();
        assertThat(snap.asks).hasSize(1);
        assertThat(snap.asks.get(0).priceTicks).isEqualTo(5001L);
    }

    // =========================================================================
    // Snapshot reflects correct state after a fill
    // =========================================================================

    @Test
    void snapshot_correctStateAfterFill() {
        book.processLimit(ask(5000, 200));
        book.processLimit(bid(5000, 80));  // Partial fill of 80; 120 remains on the ask side.

        BookSnapshot snap = book.getSnapshot();

        assertThat(snap.asks).hasSize(1);
        assertThat(snap.asks.get(0).priceTicks).isEqualTo(5000L);
        assertThat(snap.asks.get(0).totalVolume).isEqualTo(120L);
        assertThat(snap.bids).isEmpty();
    }

    // =========================================================================
    // IOC partial fill: remainder discarded, not in book, status PARTIALLY_FILLED
    // =========================================================================

    @Test
    void ioc_partialFill_remainderDiscarded_statusPartiallyFilled() {
        book.processLimit(ask(5000, 50));

        Order ioc = order(OrderSide.BID, 5000, 100);
        ioc.type = OrderType.IOC;
        List<Fill> fills = book.processIOC(ioc);

        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).fillQty).isEqualTo(50L);
        assertThat(ioc.status).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(book.hasBids()).isFalse();  // IOC remainder is discarded, not rested.
    }

    // =========================================================================
    // IOC no liquidity: CANCELLED, not in book
    // =========================================================================

    @Test
    void ioc_noLiquidity_cancelledNotInBook() {
        Order ioc = order(OrderSide.BID, 5000, 100);
        ioc.type = OrderType.IOC;
        List<Fill> fills = book.processIOC(ioc);

        assertThat(fills).isEmpty();
        assertThat(ioc.status).isEqualTo(OrderStatus.CANCELLED);
        assertThat(book.hasBids()).isFalse();
    }

    // =========================================================================
    // Market partial liquidity: fills what it can, status PARTIALLY_FILLED
    // =========================================================================

    @Test
    void market_partialLiquidity_statusPartiallyFilled_neverRests() {
        book.processLimit(ask(5000, 50));
        Order m = market(OrderSide.BID, 100);
        List<Fill> fills = book.processMarket(m);

        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).fillQty).isEqualTo(50L);
        assertThat(m.remainingQty).isEqualTo(50L);
        assertThat(m.status).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(book.hasBids()).isFalse();
    }

    // =========================================================================
    // Market SELL — full liquidity: FILLED, bid side drained
    // =========================================================================

    @Test
    void market_sell_fullLiquidity_filled_bidSideDrained() {
        book.processLimit(bid(5000, 100));
        book.processLimit(bid(4999, 100));

        Order m = market(OrderSide.ASK, 200);
        List<Fill> fills = book.processMarket(m);

        assertThat(fills).hasSize(2);
        assertThat(fills.get(0).fillPriceTicks).isEqualTo(5000L);   // Best bid fills first.
        assertThat(fills.get(1).fillPriceTicks).isEqualTo(4999L);
        assertThat(m.status).isEqualTo(OrderStatus.FILLED);
        assertThat(book.hasBids()).isFalse();
        assertThat(book.bestBid()).isEqualTo(Long.MIN_VALUE);
    }

    // =========================================================================
    // Market SELL — no bids: CANCELLED, zero fills
    // =========================================================================

    @Test
    void market_sell_noBids_cancelledZeroFills() {
        Order m = market(OrderSide.ASK, 100);
        List<Fill> fills = assertDoesNotThrow(() -> book.processMarket(m));

        assertThat(fills).isEmpty();
        assertThat(m.status).isEqualTo(OrderStatus.CANCELLED);
        assertThat(book.hasAsks()).isFalse();
    }

    // =========================================================================
    // Market SELL — partial liquidity: PARTIALLY_FILLED, never rests
    // =========================================================================

    @Test
    void market_sell_partialLiquidity_partiallyFilled_neverRests() {
        book.processLimit(bid(5000, 50));

        Order m = market(OrderSide.ASK, 100);
        List<Fill> fills = book.processMarket(m);

        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).fillQty).isEqualTo(50L);
        assertThat(m.remainingQty).isEqualTo(50L);
        assertThat(m.status).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(book.hasAsks()).isFalse();   // Market order remainder is never rested.
    }

    // =========================================================================
    // IOC ASK side — partial fill: remainder discarded, not in book
    // =========================================================================

    @Test
    void ioc_askSide_partialFill_remainderDiscarded_statusPartiallyFilled() {
        book.processLimit(bid(5000, 50));

        Order ioc = order(OrderSide.ASK, 4900, 100);
        ioc.type = OrderType.IOC;
        List<Fill> fills = book.processIOC(ioc);

        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).fillQty).isEqualTo(50L);
        assertThat(ioc.status).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(book.hasAsks()).isFalse();   // IOC remainder is discarded, not rested.
    }

    // =========================================================================
    // IOC ASK side — no liquidity: CANCELLED, not in book
    // =========================================================================

    @Test
    void ioc_askSide_noLiquidity_cancelledNotInBook() {
        Order ioc = order(OrderSide.ASK, 5000, 100);
        ioc.type = OrderType.IOC;
        List<Fill> fills = book.processIOC(ioc);

        assertThat(fills).isEmpty();
        assertThat(ioc.status).isEqualTo(OrderStatus.CANCELLED);
        assertThat(book.hasAsks()).isFalse();
    }
}
