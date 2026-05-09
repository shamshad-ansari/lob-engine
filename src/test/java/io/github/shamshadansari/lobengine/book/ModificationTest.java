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
 * T-025 — processModify: FIFO-preserving qty-down semantics and cancel-and-resubmit paths.
 */
class ModificationTest {

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
    // M-01 — Qty-down in-place: queue position preserved, level volume updated
    // =========================================================================

    @Test
    void M01_qtyDown_inPlace_queuePositionPreserved() {
        Order first  = ask(5000, 100);
        Order second = ask(5000, 100);
        long firstOrderId = first.orderId;
        book.addToBook(first);
        book.addToBook(second);

        // Reducing first's quantity from 100 to 40 in place preserves its queue position ahead of second.
        Order result = book.processModify(first.orderId, 5000, 40);

        assertThat(result).isSameAs(first);
        assertThat(first.remainingQty).isEqualTo(40L);
        assertThat(first.owningNode).isNotNull();   // Order remains in the queue.

        // An incoming bid for 40 must fill the in-place modified order, not second.
        List<Fill> fills = book.processLimit(bid(5000, 40));
        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).sellOrderId).isEqualTo(firstOrderId);
    }

    // =========================================================================
    // M-02 — Qty-down in-place: level totalVolume updated correctly
    // =========================================================================

    @Test
    void M02_qtyDown_levelVolumeUpdated() {
        book.addToBook(ask(5000, 200));

        book.processModify(nextId - 1, 5000, 80);

        // The snapshot must reflect the reduced level volume after the in-place modification.
        var snap = book.getSnapshot();
        assertThat(snap.asks).hasSize(1);
        assertThat(snap.asks.get(0).totalVolume).isEqualTo(80L);
    }

    // =========================================================================
    // M-03 — Price change: cancel-and-resubmit, old order owningNode null, new at new price
    // =========================================================================

    @Test
    void M03_priceChange_cancelAndResubmit_oldOwningNodeNull_newOrderAtNewPrice() {
        Order original = ask(5000, 100);
        book.addToBook(original);

        // Repricing from 5000 to 5001 triggers cancel-and-resubmit semantics.
        Order replacement = book.processModify(original.orderId, 5001, 100);

        assertThat(replacement).isNotSameAs(original);
        assertThat(original.owningNode).isNull();           // Original order has been removed from the 5000 queue.

        assertThat(replacement.owningNode).isNotNull();     // Replacement order is placed in the 5001 queue.
        assertThat(replacement.priceTicks).isEqualTo(5001L);

        assertThat(book.bestAsk()).isEqualTo(5001L);
        assertThat(book.hasAsks()).isTrue();
    }

    // =========================================================================
    // M-04 — Qty-up: cancel-and-resubmit (loses queue priority)
    // =========================================================================

    @Test
    void M04_qtyUp_cancelAndResubmit_losesQueuePriority() {
        Order first  = ask(5000, 100);
        Order second = ask(5000, 100);
        long secondOrderId = second.orderId;
        book.addToBook(first);
        book.addToBook(second);

        // Increasing quantity triggers cancel-and-resubmit, moving the order to the back of the queue.
        Order replacement = book.processModify(first.orderId, 5000, 200);

        assertThat(replacement).isNotSameAs(first);
        assertThat(first.owningNode).isNull();
        assertThat(replacement.owningNode).isNotNull();
        assertThat(replacement.remainingQty).isEqualTo(200L);

        // The second order fills before the replacement because it retains queue priority after resubmission.
        List<Fill> fills = book.processLimit(bid(5000, 100));
        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).sellOrderId).isEqualTo(secondOrderId);
    }

    // =========================================================================
    // M-05 — Modify non-existent orderId: returns null
    // =========================================================================

    @Test
    void M05_modifyNonExistentOrder_returnsNull() {
        Order result = book.processModify(99_999L, 5000, 100);

        assertThat(result).isNull();
        assertThat(book.hasBids()).isFalse();
        assertThat(book.hasAsks()).isFalse();
    }

    // =========================================================================
    // M-06 — Same price, same qty: no-op; queue position and volume unchanged
    // =========================================================================

    @Test
    void M06_samePriceSameQty_isNoOp_queuePositionAndVolumeUnchanged() {
        Order first  = ask(5000, 100);
        Order second = ask(5000, 100);
        long firstOrderId = first.orderId;
        book.addToBook(first);
        book.addToBook(second);

        // Modifying with identical price and quantity must be a no-op.
        Order result = book.processModify(first.orderId, 5000, 100);

        assertThat(result).isSameAs(first);
        assertThat(first.remainingQty).isEqualTo(100L);     // Quantity is unchanged.
        assertThat(first.owningNode).isNotNull();           // Order remains in the queue after a no-op modification.

        // Level volume must be unchanged at 100 + 100 = 200.
        var snap = book.getSnapshot();
        assertThat(snap.asks.get(0).totalVolume).isEqualTo(200L);

        // Queue position is preserved; first fills before second.
        List<Fill> fills = book.processLimit(bid(5000, 100));
        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).sellOrderId).isEqualTo(firstOrderId);
    }
}
