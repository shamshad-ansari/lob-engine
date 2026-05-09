package io.github.shamshadansari.lobengine.validation;

import io.github.shamshadansari.lobengine.domain.EngineEvent;
import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderType;

public final class ValidatedOrderCommand {

    public final EngineEvent.Type type;
    public final long             instrumentId;
    public final long             timestampNanos;

    public final long             orderId;
    public final String           clientOrderId;
    public final OrderSide        side;
    public final OrderType        orderType;
    public final long             priceTicks;
    public final long             qty;

    public final long             targetOrderId;
    public final long             newPriceTicks;
    public final long             newQty;

    public static ValidatedOrderCommand newOrder(OrderSubmission submission, long priceTicks) {
        return new ValidatedOrderCommand(
            EngineEvent.Type.NEW_ORDER,
            submission.instrumentId,
            submission.timestampNanos,
            submission.orderId,
            submission.clientOrderId,
            submission.side,
            submission.type,
            priceTicks,
            submission.qty,
            0L,
            -1L,
            -1L
        );
    }

    public static ValidatedOrderCommand cancel(EngineEvent event) {
        return new ValidatedOrderCommand(
            EngineEvent.Type.CANCEL_ORDER,
            event.instrumentId,
            event.timestampNanos,
            0L,
            null,
            null,
            null,
            0L,
            0L,
            event.targetOrderId,
            -1L,
            -1L
        );
    }

    public static ValidatedOrderCommand modify(EngineEvent event) {
        return new ValidatedOrderCommand(
            EngineEvent.Type.MODIFY_ORDER,
            event.instrumentId,
            event.timestampNanos,
            0L,
            null,
            null,
            null,
            0L,
            0L,
            event.targetOrderId,
            event.newPriceTicks,
            event.newQty
        );
    }

    private ValidatedOrderCommand(EngineEvent.Type type,
                                  long instrumentId,
                                  long timestampNanos,
                                  long orderId,
                                  String clientOrderId,
                                  OrderSide side,
                                  OrderType orderType,
                                  long priceTicks,
                                  long qty,
                                  long targetOrderId,
                                  long newPriceTicks,
                                  long newQty) {
        this.type           = type;
        this.instrumentId   = instrumentId;
        this.timestampNanos = timestampNanos;
        this.orderId        = orderId;
        this.clientOrderId  = clientOrderId;
        this.side           = side;
        this.orderType      = orderType;
        this.priceTicks     = priceTicks;
        this.qty            = qty;
        this.targetOrderId  = targetOrderId;
        this.newPriceTicks  = newPriceTicks;
        this.newQty         = newQty;
    }
}
