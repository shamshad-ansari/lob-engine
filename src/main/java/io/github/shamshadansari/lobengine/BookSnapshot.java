package io.github.shamshadansari.lobengine;

import io.github.shamshadansari.lobengine.domain.BookLevel;
import io.github.shamshadansari.lobengine.domain.Instrument;

import java.util.List;

public final class BookSnapshot {

    public final long            instrumentId;
    public final long            snapshotTimestampNanos;
    public final long            sequenceNumber;
    public final List<BookLevel> bids;   // descending by price
    public final List<BookLevel> asks;   // ascending by price

    public BookSnapshot(long instrumentId, long timestampNanos,
                        long sequenceNumber,
                        List<BookLevel> bids, List<BookLevel> asks) {
        this.instrumentId           = instrumentId;
        this.snapshotTimestampNanos = timestampNanos;
        this.sequenceNumber         = sequenceNumber;
        this.bids                   = List.copyOf(bids);
        this.asks                   = List.copyOf(asks);
    }

    // Long.MIN_VALUE when bids are empty — consistent with OrderBook sentinel
    public long bestBid() { return bids.isEmpty() ? Long.MIN_VALUE : bids.get(0).priceTicks; }

    // Long.MAX_VALUE when asks are empty — consistent with OrderBook sentinel
    public long bestAsk() { return asks.isEmpty() ? Long.MAX_VALUE : asks.get(0).priceTicks; }

    public boolean hasBids() { return !bids.isEmpty(); }
    public boolean hasAsks() { return !asks.isEmpty(); }

    public long spread() {
        return (hasBids() && hasAsks()) ? bestAsk() - bestBid() : 0;
    }

    public String toDisplayString(Instrument inst) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Snapshot [inst=%d seq=%d] ===\n",
                instrumentId, sequenceNumber));
        sb.append("ASKS (low to high):\n");
        for (BookLevel a : asks)
            sb.append(String.format("  %.4f  vol=%d  orders=%d\n",
                    inst.fromTicks(a.priceTicks), a.totalVolume, a.orderCount));
        sb.append("BIDS (high to low):\n");
        for (BookLevel b : bids)
            sb.append(String.format("  %.4f  vol=%d  orders=%d\n",
                    inst.fromTicks(b.priceTicks), b.totalVolume, b.orderCount));
        return sb.toString();
    }
}
