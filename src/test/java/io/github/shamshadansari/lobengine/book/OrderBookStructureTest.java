package io.github.shamshadansari.lobengine.book;

import io.github.shamshadansari.lobengine.BookSnapshot;
import io.github.shamshadansari.lobengine.domain.BookLevel;
import io.github.shamshadansari.lobengine.domain.Order;
import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderStatus;
import io.github.shamshadansari.lobengine.domain.OrderType;
import io.github.shamshadansari.lobengine.metrics.EngineMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBookStructureTest {

    private static final long INSTRUMENT_ID = 1L;

    private EngineMetrics metrics;
    private OrderBook     book;

    @BeforeEach
    void setUp() {
        metrics = new EngineMetrics();
        book    = new OrderBook(INSTRUMENT_ID, metrics);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static long nextId = 1;

    private Order bid(long priceTicks, long qty) {
        return order(OrderSide.BID, priceTicks, qty);
    }

    private Order ask(long priceTicks, long qty) {
        return order(OrderSide.ASK, priceTicks, qty);
    }

    private Order order(OrderSide side, long priceTicks, long qty) {
        Order o = new Order();
        o.orderId      = nextId++;
        o.side         = side;
        o.type         = OrderType.LIMIT;
        o.priceTicks   = priceTicks;
        o.originalQty  = qty;
        o.remainingQty = qty;
        o.status       = OrderStatus.PENDING;
        return o;
    }

    // =========================================================================
    // Test 1 — Empty book has sentinel best prices
    // =========================================================================

    @Test
    void emptyBook_hasSentinelBestPrices() {
        assertThat(book.bestBid()).isEqualTo(Long.MIN_VALUE);
        assertThat(book.bestAsk()).isEqualTo(Long.MAX_VALUE);
        assertThat(book.hasBids()).isFalse();
        assertThat(book.hasAsks()).isFalse();
    }

    // =========================================================================
    // Test 2 — Single bid added updates bestBid
    // =========================================================================

    @Test
    void singleBid_updatesBestBid() {
        book.addToBook(bid(5000, 10));

        assertThat(book.bestBid()).isEqualTo(5000);
        assertThat(book.hasBids()).isTrue();
    }

    // =========================================================================
    // Test 3 — Higher bid updates bestBid
    // =========================================================================

    @Test
    void higherBid_updatesBestBid() {
        book.addToBook(bid(5000, 10));
        book.addToBook(bid(5001, 10));

        assertThat(book.bestBid()).isEqualTo(5001);
    }

    // =========================================================================
    // Test 4 — Cancel at best bid updates cache
    // =========================================================================

    @Test
    void cancelBestBid_cacheUpdatesToNextBestBid() {
        Order best = bid(5001, 10);
        book.addToBook(best);
        book.addToBook(bid(5000, 10));

        book.cancelOrder(best.orderId);

        assertThat(book.bestBid()).isEqualTo(5000);
    }

    // =========================================================================
    // Test 5 — Cancel last order at level removes level
    // =========================================================================

    @Test
    void cancelLastOrderAtLevel_removesLevelAndResetsSentinel() {
        Order o = bid(5001, 10);
        book.addToBook(o);

        book.cancelOrder(o.orderId);

        assertThat(book.bestBid()).isEqualTo(Long.MIN_VALUE);
        assertThat(book.hasBids()).isFalse();
    }

    // =========================================================================
    // Test 6 — Cancel non-existent order returns false and records a miss
    // =========================================================================

    @Test
    void cancelUnknownOrder_returnsFalseAndRecordsMiss() {
        boolean result = book.cancelOrder(99_999L);

        assertThat(result).isFalse();
        assertThat(metrics.cancelMisses()).isEqualTo(1);
    }

    // =========================================================================
    // Test 7 — owningNode is set after addToBook
    // =========================================================================

    @Test
    void addToBook_setsOwningNodeOnOrder() {
        Order o = bid(5000, 10);
        book.addToBook(o);

        assertThat(o.owningNode).isNotNull();
    }

    // =========================================================================
    // Test 8 — owningNode is nulled after cancelOrder
    // =========================================================================

    @Test
    void cancelOrder_nullsOwningNode() {
        Order o = bid(5000, 10);
        book.addToBook(o);

        book.cancelOrder(o.orderId);

        assertThat(o.owningNode).isNull();
    }

    // =========================================================================
    // Test 9 — owningNode is nulled after PriceLevel.dequeueFront
    // =========================================================================

    @Test
    void dequeueFront_nullsOwningNode() {
        Order o = bid(5000, 10);
        PriceLevel level = new PriceLevel(5000);
        level.enqueue(o);

        Order dequeued = level.dequeueFront();

        assertThat(dequeued).isSameAs(o);
        assertThat(o.owningNode).isNull();
    }

    // =========================================================================
    // Test 10 — Snapshot instrumentId matches constructor argument
    // =========================================================================

    @Test
    void snapshot_instrumentIdMatchesConstructorArg() {
        BookSnapshot snap = book.getSnapshot();

        assertThat(snap.instrumentId).isEqualTo(INSTRUMENT_ID);
    }

    // =========================================================================
    // Test 11 — Snapshot is immutable: adding order doesn't change prior snapshot
    // =========================================================================

    @Test
    void snapshot_isImmutableAfterConstruction() {
        book.addToBook(bid(5000, 10));
        BookSnapshot before = book.getSnapshot();

        book.addToBook(bid(4999, 20));   // add to book after snapshot

        assertThat(before.bids).hasSize(1);
        assertThat(before.bestBid()).isEqualTo(5000);
    }

    // =========================================================================
    // Test 12 — Ask side is symmetric (tests 2-5 repeated for asks)
    // =========================================================================

    @Nested
    class AskSideSymmetry {

        @Test
        void singleAsk_updatesBestAsk() {
            book.addToBook(ask(5001, 10));

            assertThat(book.bestAsk()).isEqualTo(5001);
            assertThat(book.hasAsks()).isTrue();
        }

        @Test
        void lowerAsk_updatesBestAsk() {
            book.addToBook(ask(5002, 10));
            book.addToBook(ask(5001, 10));

            assertThat(book.bestAsk()).isEqualTo(5001);
        }

        @Test
        void cancelBestAsk_cacheUpdatesToNextBestAsk() {
            Order best = ask(5001, 10);
            book.addToBook(best);
            book.addToBook(ask(5002, 10));

            book.cancelOrder(best.orderId);

            assertThat(book.bestAsk()).isEqualTo(5002);
        }

        @Test
        void cancelLastAskAtLevel_removesLevelAndResetsSentinel() {
            Order o = ask(5001, 10);
            book.addToBook(o);

            book.cancelOrder(o.orderId);

            assertThat(book.bestAsk()).isEqualTo(Long.MAX_VALUE);
            assertThat(book.hasAsks()).isFalse();
        }
    }

    // =========================================================================
    // Bonus: snapshot sentinels on empty book
    // =========================================================================

    @Test
    void snapshot_emptyBook_bestBidIsMinValue() {
        assertThat(book.getSnapshot().bestBid()).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void snapshot_emptyBook_bestAskIsMaxValue() {
        assertThat(book.getSnapshot().bestAsk()).isEqualTo(Long.MAX_VALUE);
    }

    // =========================================================================
    // Bonus: stubs return empty lists without error
    // =========================================================================

    @Test
    void processLimit_stubReturnsEmptyList() {
        assertThat(book.processLimit(bid(5000, 10))).isEmpty();
    }

    @Test
    void processMarket_stubReturnsEmptyList() {
        assertThat(book.processMarket(bid(5000, 10))).isEmpty();
    }

    @Test
    void processIOC_stubReturnsEmptyList() {
        assertThat(book.processIOC(bid(5000, 10))).isEmpty();
    }
}
