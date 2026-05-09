package io.github.shamshadansari.lobengine.validation;

import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderType;

public final class OrderSubmission {

    public final long      orderId;
    public final String    clientOrderId;
    public final long      instrumentId;
    public final OrderSide side;
    public final OrderType type;
    public final double    price;
    public final long      qty;
    public final long      timestampNanos;

    public OrderSubmission(long orderId,
                           String clientOrderId,
                           long instrumentId,
                           OrderSide side,
                           OrderType type,
                           double price,
                           long qty,
                           long timestampNanos) {
        this.orderId        = orderId;
        this.clientOrderId  = clientOrderId;
        this.instrumentId   = instrumentId;
        this.side           = side;
        this.type           = type;
        this.price          = price;
        this.qty            = qty;
        this.timestampNanos = timestampNanos;
    }
}
