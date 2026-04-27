package io.github.shamshadansari.lobengine.domain;

public final class Instrument {
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
