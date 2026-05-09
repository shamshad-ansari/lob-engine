package io.github.shamshadansari.lobengine.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstrumentTest {

    @Test
    void toTicks_converts_decimal_prices_to_fixed_point_ticks() {
        assertEquals(18949L, Instrument.DEFAULT.toTicks(189.49));
        assertEquals(1L, Instrument.DEFAULT.toTicks(0.01));
        assertEquals(5000L, Instrument.DEFAULT.toTicks(50.00));
    }

    @Test
    void raw_doubles_show_why_fixed_point_is_needed() {
        double sum = 0.10 + 0.20;

        assertNotEquals(0.30, sum);
    }

    @Test
    void ticks_make_decimal_addition_exact() {
        long sum = Instrument.DEFAULT.toTicks(0.10) + Instrument.DEFAULT.toTicks(0.20);

        assertEquals(Instrument.DEFAULT.toTicks(0.30), sum);
    }

    @Test
    void tryToTicksExact_accepts_prices_on_tick_grid() {
        var ticks = Instrument.DEFAULT.tryToTicksExact(189.49);

        assertTrue(ticks.isPresent());
        assertEquals(18949L, ticks.getAsLong());
    }

    @Test
    void tryToTicksExact_rejects_off_tick_prices() {
        assertFalse(Instrument.DEFAULT.tryToTicksExact(100.005).isPresent());
    }

    @Test
    void tryToTicksExact_rejects_non_finite_prices() {
        assertFalse(Instrument.DEFAULT.tryToTicksExact(Double.NaN).isPresent());
        assertFalse(Instrument.DEFAULT.tryToTicksExact(Double.POSITIVE_INFINITY).isPresent());
        assertFalse(Instrument.DEFAULT.tryToTicksExact(Double.NEGATIVE_INFINITY).isPresent());
    }

    @Test
    void tryToTicksExact_rejects_non_positive_prices() {
        assertFalse(Instrument.DEFAULT.tryToTicksExact(0.0).isPresent());
        assertFalse(Instrument.DEFAULT.tryToTicksExact(-0.01).isPresent());
    }
}