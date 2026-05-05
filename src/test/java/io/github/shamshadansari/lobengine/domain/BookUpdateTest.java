package io.github.shamshadansari.lobengine.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BookUpdateTest {

    @Test
    void reset_returns_all_fields_to_defaults() {
        BookUpdate update = new BookUpdate();

        // Populate every field
        update.updateType       = BookUpdate.Type.TRADE;
        update.instrumentId     = 3L;
        update.side             = OrderSide.ASK;
        update.priceTicks       = 18949L;
        update.newVolumeAtLevel = 500L;
        update.sequenceNumber   = 12L;
        update.timestampNanos   = 1_000_000_000L;

        update.reset();

        assertNull(update.updateType);
        assertEquals(0L,  update.instrumentId);
        assertNull(update.side);
        assertEquals(0L,  update.priceTicks);
        assertEquals(-1L, update.newVolumeAtLevel, "newVolumeAtLevel sentinel must be -1");
        assertEquals(0L,  update.sequenceNumber);
        assertEquals(0L,  update.timestampNanos);
    }

    @Test
    void reset_on_fresh_instance_is_idempotent() {
        BookUpdate update = new BookUpdate();
        update.reset();

        assertNull(update.updateType);
        assertEquals(0L,  update.instrumentId);
        assertNull(update.side);
        assertEquals(0L,  update.priceTicks);
        assertEquals(-1L, update.newVolumeAtLevel);
        assertEquals(0L,  update.sequenceNumber);
        assertEquals(0L,  update.timestampNanos);
    }
}
