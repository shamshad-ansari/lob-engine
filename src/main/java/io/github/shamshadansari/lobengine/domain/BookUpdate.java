package io.github.shamshadansari.lobengine.domain;

public final class BookUpdate {
    public enum Type {
        ADD, CANCEL, MODIFY, TRADE,
        BEST_BID_CHANGE, BEST_ASK_CHANGE
    }

    public Type      updateType;
    public long      instrumentId;
    public OrderSide side;
    public long      priceTicks;
    public long      newVolumeAtLevel;  // -1 = level removed
    public long      sequenceNumber;
    public long      timestampNanos;

    public void reset() {
        updateType       = null;
        instrumentId     = 0;
        side             = null;
        priceTicks       = 0;
        newVolumeAtLevel = -1L;  // sentinel: level removed
        sequenceNumber   = 0;
        timestampNanos   = 0;
    }
}
