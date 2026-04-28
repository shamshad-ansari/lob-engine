package io.github.shamshadansari.lobengine.naive;

import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderStatus;
import io.github.shamshadansari.lobengine.domain.OrderType;

public class NaiveOrder {
    public long orderId;
    public OrderSide side;
    public OrderType type;
    public long priceTicks;
    public long originalQty;
    public long remainingQty;
    public OrderStatus status;
    public long timestampNanos;
}
