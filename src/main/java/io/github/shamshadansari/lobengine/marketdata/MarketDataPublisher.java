package io.github.shamshadansari.lobengine.marketdata;

import io.github.shamshadansari.lobengine.domain.BookUpdate;
import io.github.shamshadansari.lobengine.domain.Fill;
import io.github.shamshadansari.lobengine.domain.OrderSide;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MarketDataPublisher {

    private final List<FillListener>       fillListeners;
    private final List<BookUpdateListener> bookUpdateListeners;
    private final BookUpdate               reusableUpdate;

    private long nextSequenceNumber;

    public MarketDataPublisher() {
        this.fillListeners       = new ArrayList<>(8);
        this.bookUpdateListeners = new ArrayList<>(8);
        this.reusableUpdate      = new BookUpdate();
    }

    public void addFillListener(FillListener listener) {
        fillListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void addBookUpdateListener(BookUpdateListener listener) {
        bookUpdateListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void publishFill(Fill fill) {
        int n = fillListeners.size();
        for (int i = 0; i < n; i++) {
            fillListeners.get(i).onFill(fill);
        }
    }

    public void publishBookUpdate(BookUpdate.Type type,
                                  long instrumentId,
                                  OrderSide side,
                                  long priceTicks,
                                  long newVolumeAtLevel) {
        if (bookUpdateListeners.isEmpty()) {
            return;
        }

        reusableUpdate.updateType       = type;
        reusableUpdate.instrumentId     = instrumentId;
        reusableUpdate.side             = side;
        reusableUpdate.priceTicks       = priceTicks;
        reusableUpdate.newVolumeAtLevel = newVolumeAtLevel;
        reusableUpdate.sequenceNumber   = nextSequenceNumber++;
        reusableUpdate.timestampNanos   = System.nanoTime();

        int n = bookUpdateListeners.size();
        for (int i = 0; i < n; i++) {
            bookUpdateListeners.get(i).onBookUpdate(reusableUpdate);
        }
    }
}
