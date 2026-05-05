package io.github.shamshadansari.lobengine.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FillTest {

    @Test
    void reset_returns_all_fields_to_defaults() {
        Fill fill = new Fill();

        fill.fillId = 99L;
        fill.buyOrderId = 1L;
        fill.sellOrderId = 2L;
        fill.fillPriceTicks = 18949L;
        fill.fillQty = 50L;
        fill.timestampNanos = 1_000_000_000L;
        fill.buyWasAggressor = true;

        fill.reset();

        assertEquals(0L, fill.fillId);
        assertEquals(0L, fill.buyOrderId);
        assertEquals(0L, fill.sellOrderId);
        assertEquals(0L, fill.fillPriceTicks);
        assertEquals(0L, fill.fillQty);
        assertEquals(0L, fill.timestampNanos);
        assertFalse(fill.buyWasAggressor);
    }

    @Test
    void fillPriceDisplay_converts_ticks_to_human_price() {
        Fill fill = new Fill();
        fill.fillPriceTicks = 18949L;

        assertEquals(189.49, fill.fillPriceDisplay(Instrument.DEFAULT), 1e-9);
    }
}
