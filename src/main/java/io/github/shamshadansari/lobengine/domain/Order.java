package io.github.shamshadansari.lobengine.domain;

import io.github.shamshadansari.lobengine.util.DoublyLinkedList;

public final class Order {
    public long                         orderId;
    public String                       clientOrderId;
    public OrderSide                    side;
    public OrderType                    type;
    public long                         priceTicks;
    public long                         originalQty;
    public long                         remainingQty;
    public OrderStatus                  status;
    public long                         timestampNanos;
    public DoublyLinkedList.Node<Order> owningNode;     // back-reference for O(1) cancel

    public void reset() {
        orderId        = 0;
        clientOrderId  = null;
        side           = null;
        type           = null;
        priceTicks     = 0;
        originalQty    = 0;
        remainingQty   = 0;
        status         = OrderStatus.PENDING;
        timestampNanos = 0;
        owningNode     = null;
    }
}