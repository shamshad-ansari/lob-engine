package io.github.shamshadansari.lobengine.marketdata;

import io.github.shamshadansari.lobengine.domain.BookUpdate;
import io.github.shamshadansari.lobengine.domain.Fill;
import io.github.shamshadansari.lobengine.domain.OrderSide;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class MarketDataPublisherTest {

    @Test
    void publishFill_withNoListenersDoesNotThrow() {
        MarketDataPublisher publisher = new MarketDataPublisher();

        assertDoesNotThrow(() -> publisher.publishFill(new Fill()));
    }

    @Test
    void publishFill_notifiesRegisteredListener() {
        MarketDataPublisher publisher = new MarketDataPublisher();
        Fill fill = fill(7L);
        List<Long> receivedFillIds = new ArrayList<>();

        publisher.addFillListener(received -> receivedFillIds.add(received.fillId));
        publisher.publishFill(fill);

        assertThat(receivedFillIds).containsExactly(7L);
    }

    @Test
    void publishFill_notifiesAllRegisteredListeners() {
        MarketDataPublisher publisher = new MarketDataPublisher();
        Fill fill = fill(11L);
        List<Long> receivedFillIds = new ArrayList<>();

        publisher.addFillListener(received -> receivedFillIds.add(received.fillId));
        publisher.addFillListener(received -> receivedFillIds.add(received.fillId + 100L));
        publisher.publishFill(fill);

        assertThat(receivedFillIds).containsExactly(11L, 111L);
    }

    @Test
    void publishBookUpdate_sequenceNumbersAreMonotonicAndGapless() {
        MarketDataPublisher publisher = new MarketDataPublisher();
        List<Long> sequenceNumbers = new ArrayList<>();
        publisher.addBookUpdateListener(update -> sequenceNumbers.add(update.sequenceNumber));

        for (int i = 0; i < 10; i++) {
            publisher.publishBookUpdate(BookUpdate.Type.ADD, 42L, OrderSide.BID, 5000L + i, 100L);
        }

        assertThat(sequenceNumbers).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
    }

    @Test
    void publishBookUpdate_propagatesInstrumentId() {
        MarketDataPublisher publisher = new MarketDataPublisher();
        List<Long> instrumentIds = new ArrayList<>();
        publisher.addBookUpdateListener(update -> instrumentIds.add(update.instrumentId));

        publisher.publishBookUpdate(BookUpdate.Type.TRADE, 99L, OrderSide.ASK, 5001L, 25L);

        assertThat(instrumentIds).containsExactly(99L);
    }

    @Test
    void publishBookUpdate_levelRemovedUsesMinusOneSentinel() {
        MarketDataPublisher publisher = new MarketDataPublisher();
        List<Long> volumes = new ArrayList<>();
        publisher.addBookUpdateListener(update -> volumes.add(update.newVolumeAtLevel));

        publisher.publishBookUpdate(BookUpdate.Type.CANCEL, 42L, OrderSide.ASK, 5000L, -1L);

        assertThat(volumes).containsExactly(-1L);
    }

    @Test
    void publishBookUpdate_liveLevelUsesNonNegativeVolume() {
        MarketDataPublisher publisher = new MarketDataPublisher();
        List<Long> volumes = new ArrayList<>();
        publisher.addBookUpdateListener(update -> volumes.add(update.newVolumeAtLevel));

        publisher.publishBookUpdate(BookUpdate.Type.ADD, 42L, OrderSide.BID, 5000L, 0L);
        publisher.publishBookUpdate(BookUpdate.Type.ADD, 42L, OrderSide.BID, 5000L, 100L);

        assertThat(volumes).containsExactly(0L, 100L);
    }

    @Test
    void publishBookUpdate_withNoListenersDoesNotConsumeSequenceNumber() {
        MarketDataPublisher publisher = new MarketDataPublisher();

        publisher.publishBookUpdate(BookUpdate.Type.ADD, 42L, OrderSide.BID, 5000L, 100L);

        List<Long> sequenceNumbers = new ArrayList<>();
        publisher.addBookUpdateListener(update -> sequenceNumbers.add(update.sequenceNumber));
        publisher.publishBookUpdate(BookUpdate.Type.ADD, 42L, OrderSide.BID, 5001L, 200L);

        assertThat(sequenceNumbers).containsExactly(0L);
    }

    @Test
    void publishBookUpdate_reusesUpdateInstanceAcrossCallbacks() {
        MarketDataPublisher publisher = new MarketDataPublisher();
        List<Integer> updateIdentities = new ArrayList<>();
        publisher.addBookUpdateListener(update -> updateIdentities.add(System.identityHashCode(update)));

        publisher.publishBookUpdate(BookUpdate.Type.ADD, 42L, OrderSide.BID, 5000L, 100L);
        publisher.publishBookUpdate(BookUpdate.Type.CANCEL, 42L, OrderSide.BID, 5000L, -1L);

        assertThat(updateIdentities).hasSize(2);
        assertThat(updateIdentities.get(0)).isEqualTo(updateIdentities.get(1));
    }

    private static Fill fill(long fillId) {
        Fill fill = new Fill();
        fill.fillId = fillId;
        return fill;
    }
}
