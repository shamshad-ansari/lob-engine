package io.github.shamshadansari.lobengine.domain;

public final class BookLevel {
    public final long priceTicks;
    public final long totalVolume;
    public final int  orderCount;

    public BookLevel(long priceTicks, long totalVolume, int orderCount) {
        this.priceTicks = priceTicks;
        this.totalVolume = totalVolume;
        this.orderCount = orderCount;
    }
}