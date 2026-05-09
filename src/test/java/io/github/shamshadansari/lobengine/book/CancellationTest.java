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

/**
 * T-024 — Cancellation tests covering every cancel path and the owningNode invariant.
 */
class CancellationTest {

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
    // Cancel front order — FIFO shifts correctly, owningNode null on cancelled
    // =========================================================================

    @Test
    void cancelFrontOrder_fifoShiftsToNext_owningNodeNull() {
        Order front = ask(5000, 100);
        Order back  = ask(5000, 100);
        long backOrderId = back.orderId;
        book.addToBook(front);
        book.addToBook(back);

        boolean result = book.cancelOrder(front.orderId);

        assertThat(result).isTrue();
        assertThat(front.owningNode).isNull();

        // After cancellation, the remaining back order advances to the front of the queue.
        List<Fill> fills = book.processLimit(bid(5000, 100));
        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).sellOrderId).isEqualTo(backOrderId);
    }

    // =========================================================================
    // Cancel middle order — front and back unaffected, owningNode null on cancelled
    // =========================================================================

    @Test
    void cancelMiddleOrder_frontAndBackUnaffected_owningNodeNull() {
        Order front  = ask(5000, 100);
        Order middle = ask(5000, 100);
        Order back   = ask(5000, 100);
        long frontOrderId  = front.orderId;
        long middleOrderId = middle.orderId;
        long backOrderId   = back.orderId;
        book.addToBook(front);
        book.addToBook(middle);
        book.addToBook(back);

        boolean result = book.cancelOrder(middle.orderId);

        assertThat(result).isTrue();
        assertThat(middle.owningNode).isNull();

        // Front and back orders remain queued and fill in FIFO order.
        List<Fill> fills = book.processLimit(bid(5000, 200));
        assertThat(fills).hasSize(2);
        assertThat(fills.get(0).sellOrderId).isEqualTo(frontOrderId);
        assertThat(fills.get(1).sellOrderId).isEqualTo(backOrderId);
        // The cancelled middle order must not appear in any fill.
        assertThat(fills).noneMatch(f -> f.sellOrderId == middleOrderId);
    }

    // =========================================================================
    // Cancel last order at a level — level removed from TreeMap, cache updated
    // =========================================================================

    @Test
    void cancelLastOrderAtLevel_levelRemovedCacheUpdated() {
        Order only  = ask(5001, 100);
        Order other = ask(5002, 100);
        book.addToBook(only);
        book.addToBook(other);

        // Cancelling the sole order at 5001 removes that price level; bestAsk must advance to 5002.
        book.cancelOrder(only.orderId);

        assertThat(book.bestAsk()).isEqualTo(5002L);
        assertThat(book.hasAsks()).isTrue();

        BookSnapshot snap = book.getSnapshot();
        assertThat(snap.asks).hasSize(1);
        assertThat(snap.asks.get(0).priceTicks).isEqualTo(5002L);
    }

    // =========================================================================
    // Cancel after partial fill — remainingQty is the updated value at cancel time
    // =========================================================================

    @Test
    void cancelAfterPartialFill_remainingQtyIsUpdatedValue() {
        Order ask = ask(5000, 200);
        book.addToBook(ask);

        // Partial fill of 80; 120 remains as the resting quantity.
        book.processLimit(bid(5000, 80));
        assertThat(ask.remainingQty).isEqualTo(120L);
        assertThat(ask.status).isEqualTo(OrderStatus.PARTIALLY_FILLED);

        boolean result = book.cancelOrder(ask.orderId);

        assertThat(result).isTrue();
        assertThat(ask.owningNode).isNull();
        assertThat(book.hasAsks()).isFalse();
    }

    // =========================================================================
    // Sequential cancels of 3 orders at same level — level removed after third
    // =========================================================================

    @Test
    void sequentialCancelsAtSameLevel_levelRemovedAfterThird() {
        Order a = ask(5000, 100);
        Order b = ask(5000, 100);
        Order c = ask(5000, 100);
        book.addToBook(a);
        book.addToBook(b);
        book.addToBook(c);

        book.cancelOrder(a.orderId);
        assertThat(book.hasAsks()).isTrue();

        book.cancelOrder(b.orderId);
        assertThat(book.hasAsks()).isTrue();

        book.cancelOrder(c.orderId);
        assertThat(book.hasAsks()).isFalse();
        assertThat(book.bestAsk()).isEqualTo(Long.MAX_VALUE);
    }

    // =========================================================================
    // owningNode invariant — null after full fill via matching
    // =========================================================================

    @Test
    void owningNode_nullAfterFullFillViaMatching() {
        Order resting = ask(5000, 100);
        book.addToBook(resting);
        assertThat(resting.owningNode).isNotNull();

        book.processLimit(bid(5000, 100));

        assertThat(resting.owningNode).isNull();
    }

    // =========================================================================
    // owningNode invariant — null after cancel
    // =========================================================================

    @Test
    void owningNode_nullAfterCancel() {
        Order o = bid(5000, 100);
        book.addToBook(o);
        assertThat(o.owningNode).isNotNull();

        book.cancelOrder(o.orderId);

        assertThat(o.owningNode).isNull();
    }

    // =========================================================================
    // cancelOrder on already-matched order returns false — no exception, no mutation
    // =========================================================================

    @Test
    void cancelAlreadyMatchedOrder_returnsFalseNoException() {
        Order ask = ask(5000, 100);
        book.addToBook(ask);
        book.processLimit(bid(5000, 100));  // fully fills ask

        boolean result = book.cancelOrder(ask.orderId);

        assertThat(result).isFalse();
        assertThat(metrics.cancelMisses()).isEqualTo(1L);
    }

    // =========================================================================
    // cancelOrder non-existent ID returns false, no mutation
    // =========================================================================

    @Test
    void cancelNonExistentId_returnsFalseNoMutation() {
        book.addToBook(ask(5000, 100));

        boolean result = book.cancelOrder(99_999L);

        assertThat(result).isFalse();
        assertThat(book.hasAsks()).isTrue();
        assertThat(metrics.cancelMisses()).isEqualTo(1L);
    }

    // =========================================================================
    // Cancel one order from a multi-order level — volume reduced, level survives
    // =========================================================================

    @Test
    void cancelOneOf_multipleOrdersAtSameLevel_volumeReducedLevelSurvives() {
        Order a = ask(5000, 100);
        Order b = ask(5000, 200);
        Order c = ask(5000, 150);
        book.addToBook(a);
        book.addToBook(b);
        book.addToBook(c);

        book.cancelOrder(b.orderId);

        // Price level remains with two orders totaling 250.
        assertThat(book.hasAsks()).isTrue();
        assertThat(book.bestAsk()).isEqualTo(5000L);

        BookSnapshot snap = book.getSnapshot();
        assertThat(snap.asks).hasSize(1);
        assertThat(snap.asks.get(0).totalVolume).isEqualTo(250L);   // 100 + 150
        assertThat(snap.asks.get(0).orderCount).isEqualTo(2);
    }
}
