package io.github.shamshadansari.lobengine.marketdata;

import io.github.shamshadansari.lobengine.domain.BookUpdate;

/**
 * Receives book update events emitted by the matching engine hot path.
 *
 * <p>Implementations must not block, allocate, or retain the mutable {@link BookUpdate}
 * instance beyond the callback. Copy primitive fields if the event must outlive
 * this method invocation.</p>
 */
@FunctionalInterface
public interface BookUpdateListener {

    void onBookUpdate(BookUpdate update);
}
