package io.github.shamshadansari.lobengine.domain;

import java.util.OptionalLong;

public final class Instrument {
    private static final double PRICE_SCALE          = 1_000_000.0;
    private static final double TICK_GRID_TOLERANCE = 1.0e-9;
    private static final double LONG_MAX_EXCLUSIVE  = 0x1.0p63;

    public final String symbol;
    public final long tickSize; // micro-dollars per tick
    public final long lotSize; // minimum qty increment
    public final long priceBandBps; // max deviation from reference in basis points

    public Instrument(String symbol, long tickSize, long lotSize, long priceBandBps){
        this.symbol = symbol;
        this.tickSize = tickSize;
        this.lotSize = lotSize;
        this.priceBandBps = priceBandBps;
    }

    public long toTicks(double humanPrice){
        return Math.round(humanPrice * 1_000_000.0 / tickSize);
    }

    public OptionalLong tryToTicksExact(double humanPrice) {
        if (!Double.isFinite(humanPrice) || humanPrice <= 0.0 || tickSize <= 0) {
            return OptionalLong.empty();
        }

        double rawTicks = humanPrice * PRICE_SCALE / tickSize;
        if (!Double.isFinite(rawTicks) || rawTicks <= 0.0 || rawTicks >= LONG_MAX_EXCLUSIVE) {
            return OptionalLong.empty();
        }

        double nearestTick = Math.rint(rawTicks);
        if (Math.abs(rawTicks - nearestTick) > TICK_GRID_TOLERANCE) {
            return OptionalLong.empty();
        }

        long ticks = (long) nearestTick;
        if (ticks <= 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(ticks);
    }

    public double fromTicks(long ticks) {
        return ticks * (tickSize / 1_000_000.0);
    }

    // Standard US equity: $0.01 tick, 1 share lot, 5% price band
    public static final Instrument DEFAULT = new Instrument(
            "EQUITY",
            10_000L,
            1L,
            500L
    );
}
