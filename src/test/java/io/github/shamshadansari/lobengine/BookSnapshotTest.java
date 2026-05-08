package io.github.shamshadansari.lobengine;

import io.github.shamshadansari.lobengine.domain.BookLevel;
import io.github.shamshadansari.lobengine.domain.Instrument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class BookSnapshotTest {

    private static final long INSTRUMENT_ID = 42L;
    private static final long SEQ           = 7L;
    private static final long TS            = 123_000_000L;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private BookLevel level(long price, long vol, int orders) {
        return new BookLevel(price, vol, orders);
    }

    private BookSnapshot emptySnapshot() {
        return new BookSnapshot(INSTRUMENT_ID, TS, SEQ, List.of(), List.of());
    }

    // -------------------------------------------------------------------------
    // instrumentId
    // -------------------------------------------------------------------------

    @Test
    void instrumentId_storedCorrectly() {
        BookSnapshot snap = emptySnapshot();

        assertThat(snap.instrumentId).isEqualTo(INSTRUMENT_ID);
    }

    // -------------------------------------------------------------------------
    // bestBid / bestAsk sentinels on empty book
    // -------------------------------------------------------------------------

    @Test
    void bestBid_returnsMinValueWhenBidsEmpty() {
        assertThat(emptySnapshot().bestBid()).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void bestAsk_returnsMaxValueWhenAsksEmpty() {
        assertThat(emptySnapshot().bestAsk()).isEqualTo(Long.MAX_VALUE);
    }

    // -------------------------------------------------------------------------
    // bestBid / bestAsk with data
    // -------------------------------------------------------------------------

    @Test
    void bestBid_returnsFirstElementPrice() {
        // bids list is descending — first element is the best (highest) bid
        List<BookLevel> bids = List.of(level(5001, 100, 1), level(5000, 200, 2));
        BookSnapshot snap = new BookSnapshot(INSTRUMENT_ID, TS, SEQ, bids, List.of());

        assertThat(snap.bestBid()).isEqualTo(5001);
    }

    @Test
    void bestAsk_returnsFirstElementPrice() {
        // asks list is ascending — first element is the best (lowest) ask
        List<BookLevel> asks = List.of(level(5002, 50, 1), level(5003, 75, 1));
        BookSnapshot snap = new BookSnapshot(INSTRUMENT_ID, TS, SEQ, List.of(), asks);

        assertThat(snap.bestAsk()).isEqualTo(5002);
    }

    // -------------------------------------------------------------------------
    // hasBids / hasAsks
    // -------------------------------------------------------------------------

    @Test
    void hasBids_falseWhenBidsEmpty() {
        assertThat(emptySnapshot().hasBids()).isFalse();
    }

    @Test
    void hasAsks_falseWhenAsksEmpty() {
        assertThat(emptySnapshot().hasAsks()).isFalse();
    }

    @Test
    void hasBids_trueWhenBidsPresent() {
        BookSnapshot snap = new BookSnapshot(INSTRUMENT_ID, TS, SEQ,
                List.of(level(5000, 100, 1)), List.of());

        assertThat(snap.hasBids()).isTrue();
    }

    @Test
    void hasAsks_trueWhenAsksPresent() {
        BookSnapshot snap = new BookSnapshot(INSTRUMENT_ID, TS, SEQ,
                List.of(), List.of(level(5001, 50, 1)));

        assertThat(snap.hasAsks()).isTrue();
    }

    // -------------------------------------------------------------------------
    // spread
    // -------------------------------------------------------------------------

    @Test
    void spread_returnsZeroWhenEitherSideEmpty() {
        assertThat(emptySnapshot().spread()).isZero();

        BookSnapshot bidsOnly = new BookSnapshot(INSTRUMENT_ID, TS, SEQ,
                List.of(level(5000, 100, 1)), List.of());
        assertThat(bidsOnly.spread()).isZero();

        BookSnapshot asksOnly = new BookSnapshot(INSTRUMENT_ID, TS, SEQ,
                List.of(), List.of(level(5001, 50, 1)));
        assertThat(asksOnly.spread()).isZero();
    }

    @Test
    void spread_returnsAskMinusBid() {
        BookSnapshot snap = new BookSnapshot(INSTRUMENT_ID, TS, SEQ,
                List.of(level(5000, 100, 1)),
                List.of(level(5002, 50, 1)));

        assertThat(snap.spread()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // Immutability (List.copyOf)
    // -------------------------------------------------------------------------

    @Test
    void bids_areImmutableAfterConstruction() {
        BookSnapshot snap = new BookSnapshot(INSTRUMENT_ID, TS, SEQ,
                List.of(level(5000, 100, 1)), List.of());

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> snap.bids.add(level(4999, 50, 1)));
    }

    @Test
    void asks_areImmutableAfterConstruction() {
        BookSnapshot snap = new BookSnapshot(INSTRUMENT_ID, TS, SEQ,
                List.of(), List.of(level(5001, 50, 1)));

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> snap.asks.add(level(5002, 25, 1)));
    }

    @Test
    void snapshot_isUnaffectedByMutationOfSourceList() {
        java.util.ArrayList<BookLevel> source = new java.util.ArrayList<>();
        source.add(level(5000, 100, 1));
        BookSnapshot snap = new BookSnapshot(INSTRUMENT_ID, TS, SEQ, source, List.of());

        source.clear();   // mutate the original list

        assertThat(snap.bids).hasSize(1);
        assertThat(snap.bestBid()).isEqualTo(5000);
    }

    // -------------------------------------------------------------------------
    // toDisplayString
    // -------------------------------------------------------------------------

    @Test
    void toDisplayString_containsInstrumentIdAndSequenceNumber() {
        BookSnapshot snap = new BookSnapshot(42L, TS, 3L,
                List.of(level(5000, 100, 2)),
                List.of(level(5001, 50, 1)));

        String display = snap.toDisplayString(Instrument.DEFAULT);

        assertThat(display).contains("inst=42");
        assertThat(display).contains("seq=3");
    }

    @Test
    void toDisplayString_showsBothSides() {
        BookSnapshot snap = new BookSnapshot(INSTRUMENT_ID, TS, SEQ,
                List.of(level(5000, 100, 1)),
                List.of(level(5001, 50, 1)));

        String display = snap.toDisplayString(Instrument.DEFAULT);

        assertThat(display).contains("BIDS");
        assertThat(display).contains("ASKS");
    }
}
