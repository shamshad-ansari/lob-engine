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
 * Verifies modify-order behavior for in-place updates, cancel-and-resubmit paths,
 * and sentinel values used by validated modify commands.
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

    // Test data helpers.

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

    // Quantity reduction is applied in place and preserves FIFO priority.

    @Test
    void M01_qtyDown_inPlace_queuePositionPreserved() {
        Order first  = ask(5000, 100);
        Order second = ask(5000, 100);
        long firstOrderId = first.orderId;
        book.addToBook(first);
        book.addToBook(second);

        Order result = book.processModify(first.orderId, 5000, 40);

        assertThat(result).isSameAs(first);
        assertThat(first.remainingQty).isEqualTo(40L);
        assertThat(first.owningNode).isNotNull();

        List<Fill> fills = book.processLimit(bid(5000, 40));
        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).sellOrderId).isEqualTo(firstOrderId);
    }

    // Quantity reduction updates the price level's aggregate volume.

    @Test
    void M02_qtyDown_levelVolumeUpdated() {
        book.addToBook(ask(5000, 200));

        book.processModify(nextId - 1, 5000, 80);

        var snap = book.getSnapshot();
        assertThat(snap.asks).hasSize(1);
        assertThat(snap.asks.get(0).totalVolume).isEqualTo(80L);
    }

    // Repricing an order removes the original queue entry and submits a replacement.

    @Test
    void M03_priceChange_cancelAndResubmit_oldOwningNodeNull_newOrderAtNewPrice() {
        Order original = ask(5000, 100);
        book.addToBook(original);

        Order replacement = book.processModify(original.orderId, 5001, 100);

        assertThat(replacement).isNotSameAs(original);
        assertThat(original.owningNode).isNull();

        assertThat(replacement.owningNode).isNotNull();
        assertThat(replacement.priceTicks).isEqualTo(5001L);

        assertThat(book.bestAsk()).isEqualTo(5001L);
        assertThat(book.hasAsks()).isTrue();
    }

    // Quantity increases lose priority through cancel-and-resubmit semantics.

    @Test
    void M04_qtyUp_cancelAndResubmit_losesQueuePriority() {
        Order first  = ask(5000, 100);
        Order second = ask(5000, 100);
        long secondOrderId = second.orderId;
        book.addToBook(first);
        book.addToBook(second);

        Order replacement = book.processModify(first.orderId, 5000, 200);

        assertThat(replacement).isNotSameAs(first);
        assertThat(first.owningNode).isNull();
        assertThat(replacement.owningNode).isNotNull();
        assertThat(replacement.remainingQty).isEqualTo(200L);

        List<Fill> fills = book.processLimit(bid(5000, 100));
        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).sellOrderId).isEqualTo(secondOrderId);
    }

    // Missing order IDs are treated as modify misses.

    @Test
    void M05_modifyNonExistentOrder_returnsNull() {
        Order result = book.processModify(99_999L, 5000, 100);

        assertThat(result).isNull();
        assertThat(book.hasBids()).isFalse();
        assertThat(book.hasAsks()).isFalse();
    }

    // Unchanged price and quantity produce a no-op.

    @Test
    void M06_samePriceSameQty_isNoOp_queuePositionAndVolumeUnchanged() {
        Order first  = ask(5000, 100);
        Order second = ask(5000, 100);
        long firstOrderId = first.orderId;
        book.addToBook(first);
        book.addToBook(second);

        Order result = book.processModify(first.orderId, 5000, 100);

        assertThat(result).isSameAs(first);
        assertThat(first.remainingQty).isEqualTo(100L);
        assertThat(first.owningNode).isNotNull();

        var snap = book.getSnapshot();
        assertThat(snap.asks.get(0).totalVolume).isEqualTo(200L);

        List<Fill> fills = book.processLimit(bid(5000, 100));
        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).sellOrderId).isEqualTo(firstOrderId);
    }

    // A price sentinel keeps the original price for quantity-only reductions.

    @Test
    void M07_priceSentinelQtyDown_preservesPriceAndQueuePosition() {
        Order first  = ask(5000, 100);
        Order second = ask(5000, 100);
        long firstOrderId = first.orderId;
        book.addToBook(first);
        book.addToBook(second);

        Order result = book.processModify(first.orderId, -1L, 40);

        assertThat(result).isSameAs(first);
        assertThat(first.priceTicks).isEqualTo(5000L);
        assertThat(first.remainingQty).isEqualTo(40L);

        var snap = book.getSnapshot();
        assertThat(snap.asks).hasSize(1);
        assertThat(snap.asks.get(0).priceTicks).isEqualTo(5000L);
        assertThat(snap.asks.get(0).totalVolume).isEqualTo(140L);

        List<Fill> fills = book.processLimit(bid(5000, 40));
        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).sellOrderId).isEqualTo(firstOrderId);
    }

    // A price sentinel keeps the original price when quantity-up resubmits.

    @Test
    void M08_priceSentinelQtyUp_cancelAndResubmitAtOriginalPrice() {
        Order first  = ask(5000, 100);
        Order second = ask(5000, 100);
        long secondOrderId = second.orderId;
        book.addToBook(first);
        book.addToBook(second);

        Order replacement = book.processModify(first.orderId, -1L, 200);

        assertThat(replacement).isNotSameAs(first);
        assertThat(first.owningNode).isNull();
        assertThat(replacement.priceTicks).isEqualTo(5000L);
        assertThat(replacement.remainingQty).isEqualTo(200L);
        assertThat(book.bestAsk()).isEqualTo(5000L);

        List<Fill> fills = book.processLimit(bid(5000, 100));
        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).sellOrderId).isEqualTo(secondOrderId);
    }

    // A quantity sentinel keeps the original quantity when the price changes.

    @Test
    void M09_qtySentinelPriceChange_cancelAndResubmitWithOriginalQuantity() {
        Order original = ask(5000, 100);
        book.addToBook(original);

        Order replacement = book.processModify(original.orderId, 5001, -1L);

        assertThat(replacement).isNotSameAs(original);
        assertThat(original.owningNode).isNull();
        assertThat(replacement.priceTicks).isEqualTo(5001L);
        assertThat(replacement.remainingQty).isEqualTo(100L);
        assertThat(replacement.originalQty).isEqualTo(100L);

        var snap = book.getSnapshot();
        assertThat(snap.asks).hasSize(1);
        assertThat(snap.asks.get(0).priceTicks).isEqualTo(5001L);
        assertThat(snap.asks.get(0).totalVolume).isEqualTo(100L);
    }

    // Dual sentinels are a no-op at the book layer.

    @Test
    void M10_priceAndQtySentinels_isNoOp() {
        Order first  = ask(5000, 100);
        Order second = ask(5000, 100);
        long firstOrderId = first.orderId;
        book.addToBook(first);
        book.addToBook(second);

        Order result = book.processModify(first.orderId, -1L, -1L);

        assertThat(result).isSameAs(first);
        assertThat(first.priceTicks).isEqualTo(5000L);
        assertThat(first.remainingQty).isEqualTo(100L);
        assertThat(first.owningNode).isNotNull();

        var snap = book.getSnapshot();
        assertThat(snap.asks).hasSize(1);
        assertThat(snap.asks.get(0).totalVolume).isEqualTo(200L);

        List<Fill> fills = book.processLimit(bid(5000, 100));
        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).sellOrderId).isEqualTo(firstOrderId);
    }
}
