package io.github.shamshadansari.lobengine.domain;

public final class Fill {
    public long    fillId;
    public long    buyOrderId;
    public long    sellOrderId;
    public long    fillPriceTicks;
    public long    fillQty;
    public long    timestampNanos;
    public boolean buyWasAggressor;

    public double fillPriceDisplay(Instrument inst) {
        return inst.fromTicks(fillPriceTicks);
    }

    public void reset() {
        fillId = 0;
        buyOrderId = 0;
        sellOrderId = 0;
        fillPriceTicks = 0;
        fillQty = 0;
        timestampNanos = 0;
        buyWasAggressor = false;
    }
}
