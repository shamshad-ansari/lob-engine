package io.github.shamshadansari.lobengine.book;

import io.github.shamshadansari.lobengine.domain.Order;
import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderStatus;
import io.github.shamshadansari.lobengine.domain.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PriceLevelTest {

    private static final long PRICE = 5000L;

    private PriceLevel level;

    @BeforeEach
    void setUp() {
        level = new PriceLevel(PRICE);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Order order(long id, long qty) {
        Order o = new Order();
        o.orderId       = id;
        o.side          = OrderSide.BID;
        o.type          = OrderType.LIMIT;
        o.priceTicks    = PRICE;
        o.originalQty   = qty;
        o.remainingQty  = qty;
        o.status        = OrderStatus.PENDING;
        return o;
    }

    // -------------------------------------------------------------------------
    // enqueue
    // -------------------------------------------------------------------------

    @Test
    void enqueue_setsOwningNodeOnOrder() {
        Order o = order(1, 10);
        level.enqueue(o);

        assertThat(o.owningNode).isNotNull();
        assertThat(o.owningNode.value).isSameAs(o);
    }

    @Test
    void enqueue_updatesTotalVolume() {
        level.enqueue(order(1, 10));
        level.enqueue(order(2, 20));

        assertThat(level.totalVolume()).isEqualTo(30);
        assertThat(level.size()).isEqualTo(2);
    }

    @Test
    void enqueue_maintainsFifoOrder() {
        Order a = order(1, 5);
        Order b = order(2, 5);
        Order c = order(3, 5);
        level.enqueue(a);
        level.enqueue(b);
        level.enqueue(c);

        assertThat(level.dequeueFront()).isSameAs(a);
        assertThat(level.dequeueFront()).isSameAs(b);
        assertThat(level.dequeueFront()).isSameAs(c);
    }

    // -------------------------------------------------------------------------
    // peekFront
    // -------------------------------------------------------------------------

    @Test
    void peekFront_returnsNullOnEmptyLevel() {
        assertThat(level.peekFront()).isNull();
    }

    @Test
    void peekFront_doesNotRemoveOrder() {
        Order o = order(1, 10);
        level.enqueue(o);

        assertThat(level.peekFront()).isSameAs(o);
        assertThat(level.size()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // dequeueFront
    // -------------------------------------------------------------------------

    @Test
    void dequeueFront_returnsNullOnEmptyLevel() {
        assertThat(level.dequeueFront()).isNull();
    }

    @Test
    void dequeueFront_nullsOwningNode() {
        Order o = order(1, 10);
        level.enqueue(o);

        Order removed = level.dequeueFront();

        assertThat(removed).isSameAs(o);
        assertThat(o.owningNode).isNull();
    }

    @Test
    void dequeueFront_decrementsTotalVolume() {
        level.enqueue(order(1, 10));
        level.enqueue(order(2, 20));

        level.dequeueFront();

        assertThat(level.totalVolume()).isEqualTo(20);
        assertThat(level.size()).isEqualTo(1);
    }

    @Test
    void dequeueFront_emptyLevelAfterAllRemoved() {
        level.enqueue(order(1, 5));
        level.dequeueFront();

        assertThat(level.isEmpty()).isTrue();
        assertThat(level.totalVolume()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // removeOrder
    // -------------------------------------------------------------------------

    @Test
    void removeOrder_returnsFalseWhenOwningNodeIsNull() {
        Order o = order(1, 10);
        // owningNode is null — never enqueued
        assertThat(level.removeOrder(o)).isFalse();
    }

    @Test
    void removeOrder_returnsFalseForAlreadyRemovedOrder() {
        Order o = order(1, 10);
        level.enqueue(o);
        level.dequeueFront();           // owningNode nulled by dequeueFront

        assertThat(level.removeOrder(o)).isFalse();
    }

    @Test
    void removeOrder_splicesOutMidQueueOrder() {
        Order a = order(1, 5);
        Order b = order(2, 5);
        Order c = order(3, 5);
        level.enqueue(a);
        level.enqueue(b);
        level.enqueue(c);

        boolean result = level.removeOrder(b);

        assertThat(result).isTrue();
        assertThat(b.owningNode).isNull();

        // Remaining FIFO order should be A then C
        List<Order> remaining = new ArrayList<>();
        for (Order o : level.queueView()) remaining.add(o);
        assertThat(remaining).containsExactly(a, c);
    }

    @Test
    void removeOrder_nullsOwningNodeAfterSpliceOut() {
        Order o = order(1, 10);
        level.enqueue(o);

        level.removeOrder(o);

        assertThat(o.owningNode).isNull();
    }

    @Test
    void removeOrder_decrementsTotalVolume() {
        level.enqueue(order(1, 10));
        Order b = order(2, 20);
        level.enqueue(b);

        level.removeOrder(b);

        assertThat(level.totalVolume()).isEqualTo(10);
        assertThat(level.size()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // totalVolume consistency
    // -------------------------------------------------------------------------

    @Test
    void totalVolume_staysConsistentAcrossMixedOperations() {
        Order a = order(1, 10);
        Order b = order(2, 20);
        Order c = order(3, 30);

        level.enqueue(a);   // vol = 10
        level.enqueue(b);   // vol = 30
        level.enqueue(c);   // vol = 60

        level.dequeueFront();   // removes a, vol = 50
        level.removeOrder(c);   // removes c, vol = 20

        assertThat(level.totalVolume()).isEqualTo(20);
        assertThat(level.size()).isEqualTo(1);
        assertThat(level.peekFront()).isSameAs(b);
    }

    // -------------------------------------------------------------------------
    // adjustVolume
    // -------------------------------------------------------------------------

    @Test
    void adjustVolume_appliesDelta() {
        level.enqueue(order(1, 100));
        level.adjustVolume(-40);

        assertThat(level.totalVolume()).isEqualTo(60);
    }

    // -------------------------------------------------------------------------
    // queueView
    // -------------------------------------------------------------------------

    @Test
    void queueView_iteratesInFifoOrder() {
        Order a = order(1, 5);
        Order b = order(2, 5);
        Order c = order(3, 5);
        level.enqueue(a);
        level.enqueue(b);
        level.enqueue(c);

        List<Order> view = new ArrayList<>();
        for (Order o : level.queueView()) view.add(o);

        assertThat(view).containsExactly(a, b, c);
    }

    // -------------------------------------------------------------------------
    // isEmpty / size
    // -------------------------------------------------------------------------

    @Test
    void isEmpty_trueOnFreshLevel() {
        assertThat(level.isEmpty()).isTrue();
        assertThat(level.size()).isEqualTo(0);
    }

    @Test
    void isEmpty_falseAfterEnqueue() {
        level.enqueue(order(1, 10));
        assertThat(level.isEmpty()).isFalse();
    }
}
