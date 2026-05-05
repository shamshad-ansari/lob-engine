package io.github.shamshadansari.lobengine.domain;

/**
 * A mutable, reusable command submitted to the matching engine.
 *
 * <p>Only the fields relevant to a given {@link Type} carry meaningful values;
 * all other fields are zero or {@code null} and must not be read.
 *
 * <pre>
 * NEW_ORDER    — instrumentId, orderId, clientOrderId, side, orderType,
 *                priceTicks, qty, timestampNanos
 * CANCEL_ORDER — instrumentId, targetOrderId, timestampNanos
 * MODIFY_ORDER — instrumentId, targetOrderId, newPriceTicks, newQty,
 *                timestampNanos  (-1 signals "no change" for price/qty)
 * </pre>
 */
public final class EngineEvent {
    public enum Type { NEW_ORDER, CANCEL_ORDER, MODIFY_ORDER }

    public Type       type;

    // ---- fields common to all event types ----
    public long       instrumentId;     // identifies the target order book
    public long       timestampNanos;

    // ---- NEW_ORDER ----
    public long       orderId;
    public String     clientOrderId;
    public OrderSide  side;
    public OrderType  orderType;
    public long       priceTicks;
    public long       qty;

    // ---- CANCEL_ORDER / MODIFY_ORDER ----
    public long       targetOrderId;

    // ---- MODIFY_ORDER ----
    public long       newPriceTicks;    // -1 = no price change requested
    public long       newQty;           // -1 = no quantity change requested

    /**
     * Resets all fields to their zero/null defaults so the instance can be
     * safely reused from an object pool. Modify sentinels are restored to -1.
     */
    public void reset() {
        type = null;
        instrumentId = 0;
        timestampNanos = 0;
        orderId = 0;
        clientOrderId = null;
        side = null;
        orderType = null;
        priceTicks = 0;
        qty = 0;
        targetOrderId = 0;
        newPriceTicks = -1L;
        newQty = -1L;
    }
}
