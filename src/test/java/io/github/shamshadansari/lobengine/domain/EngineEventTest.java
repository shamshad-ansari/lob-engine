package io.github.shamshadansari.lobengine.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EngineEventTest {

    @Test
    void reset_returns_all_fields_to_defaults() {
        EngineEvent event = new EngineEvent();

        // Populate every field
        event.type           = EngineEvent.Type.MODIFY_ORDER;
        event.instrumentId   = 7L;
        event.timestampNanos = 1_000_000_000L;
        event.orderId        = 42L;
        event.clientOrderId  = "CLIENT-001";
        event.side           = OrderSide.BID;
        event.orderType      = OrderType.LIMIT;
        event.priceTicks     = 18949L;
        event.qty            = 100L;
        event.targetOrderId  = 99L;
        event.newPriceTicks  = 19000L;
        event.newQty         = 50L;

        event.reset();

        assertNull(event.type);
        assertEquals(0L,   event.instrumentId);
        assertEquals(0L,   event.timestampNanos);
        assertEquals(0L,   event.orderId);
        assertNull(event.clientOrderId);
        assertNull(event.side);
        assertNull(event.orderType);
        assertEquals(0L,   event.priceTicks);
        assertEquals(0L,   event.qty);
        assertEquals(0L,   event.targetOrderId);
        assertEquals(-1L,  event.newPriceTicks, "newPriceTicks sentinel must be -1");
        assertEquals(-1L,  event.newQty,        "newQty sentinel must be -1");
    }

    @Test
    void reset_on_fresh_instance_is_idempotent() {
        EngineEvent event = new EngineEvent();
        event.reset();

        assertNull(event.type);
        assertEquals(0L,  event.instrumentId);
        assertEquals(0L,  event.timestampNanos);
        assertEquals(0L,  event.orderId);
        assertNull(event.clientOrderId);
        assertNull(event.side);
        assertNull(event.orderType);
        assertEquals(0L,  event.priceTicks);
        assertEquals(0L,  event.qty);
        assertEquals(0L,  event.targetOrderId);
        assertEquals(-1L, event.newPriceTicks);
        assertEquals(-1L, event.newQty);
    }
}
