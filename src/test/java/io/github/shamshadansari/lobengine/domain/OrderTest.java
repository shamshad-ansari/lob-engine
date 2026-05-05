package io.github.shamshadansari.lobengine.domain;

import io.github.shamshadansari.lobengine.util.DoublyLinkedList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderTest {

    @Test
    void reset_returns_all_fields_to_defaults() {
        Order order = new Order();

        // Populate every field
        order.orderId        = 42L;
        order.clientOrderId  = "CLIENT-001";
        order.side           = OrderSide.BID;
        order.type           = OrderType.LIMIT;
        order.priceTicks     = 18949L;
        order.originalQty    = 100L;
        order.remainingQty   = 75L;
        order.status         = OrderStatus.PARTIALLY_FILLED;
        order.timestampNanos = 1_000_000_000L;

        DoublyLinkedList<Order> list = new DoublyLinkedList<>();
        order.owningNode = list.addLast(order);

        order.reset();

        assertEquals(0L, order.orderId);
        assertNull(order.clientOrderId);
        assertNull(order.side);
        assertNull(order.type);
        assertEquals(0L, order.priceTicks);
        assertEquals(0L, order.originalQty);
        assertEquals(0L, order.remainingQty);
        assertEquals(OrderStatus.PENDING, order.status);
        assertEquals(0L, order.timestampNanos);
        assertNull(order.owningNode);
    }
}