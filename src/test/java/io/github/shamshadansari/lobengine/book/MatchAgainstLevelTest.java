package io.github.shamshadansari.lobengine.book;

import io.github.shamshadansari.lobengine.domain.Fill;
import io.github.shamshadansari.lobengine.domain.Order;
import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderStatus;
import io.github.shamshadansari.lobengine.domain.OrderType;
import io.github.shamshadansari.lobengine.metrics.EngineMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-019 — exercises matchAgainstLevel via the public processLimit / processIOC API.
 * Each test sets up one or more resting orders then drives a single incoming
 * order through matching, verifying fills, statuses, owningNode, fillId, and buyWasAggressor.
 */
class MatchAgainstLevelTest {

    private static final long INSTRUMENT_ID = 42L;

    private EngineMetrics metrics;
    private OrderBook     book;
    private long          nextId = 1;

    @BeforeEach
    void setUp() {
        metrics = new EngineMetrics();
        book    = new OrderBook(INSTRUMENT_ID, metrics);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Order ask(long priceTicks, long qty) {
        return order(OrderSide.ASK, priceTicks, qty);
    }

    private Order bid(long priceTicks, long qty) {
        return order(OrderSide.BID, priceTicks, qty);
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
    // Exact match: incoming 100 vs resting 100. Both FILLED, level empty.
    // =========================================================================

    @Test
    void exactMatch_bothFilledLevelEmptyOwningNodeNull() {
        Order resting = ask(5000, 100);
        book.addToBook(resting);

        Order incoming = bid(5000, 100);
        List<Fill> fills = book.processLimit(incoming);

        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).fillId).isEqualTo(1L);
        assertThat(fills.get(0).buyWasAggressor).isTrue();     // BID order was the aggressor.
        assertThat(incoming.status).isEqualTo(OrderStatus.FILLED);
        assertThat(book.hasAsks()).isFalse();
        assertThat(resting.owningNode).isNull();                // Node is cleared by dequeueFront on a full fill.
    }

    // =========================================================================
    // Resting larger than incoming: incoming FILLED, resting PARTIALLY_FILLED.
    // =========================================================================

    @Test
    void incomingSmallerThanResting_incomingFilledRestingPartial() {
        Order resting = ask(5000, 200);
        book.addToBook(resting);

        Order incoming = bid(5000, 100);
        List<Fill> fills = book.processLimit(incoming);

        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).fillQty).isEqualTo(100);
        assertThat(fills.get(0).buyWasAggressor).isTrue();
        assertThat(incoming.status).isEqualTo(OrderStatus.FILLED);
        assertThat(resting.status).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(resting.remainingQty).isEqualTo(100);
        assertThat(resting.owningNode).isNotNull();             // Partially filled order remains in the queue.
    }

    // =========================================================================
    // Incoming sweeps two restings, incoming PARTIALLY_FILLED, remainder rests.
    // =========================================================================

    @Test
    void incomingSweepesTwoRestings_twoFillsIncomingPartialThenRests() {
        Order r1 = ask(5000, 100);
        Order r2 = ask(5000, 100);
        long r1OrderId = r1.orderId;
        long r2OrderId = r2.orderId;
        book.addToBook(r1);
        book.addToBook(r2);

        Order incoming = bid(5000, 300);
        List<Fill> fills = book.processLimit(incoming);

        assertThat(fills).hasSize(2);
        assertThat(fills.get(0).buyWasAggressor).isTrue();
        assertThat(fills.get(1).buyWasAggressor).isTrue();
        assertThat(incoming.remainingQty).isEqualTo(100);   // 300 original − 200 filled = 100 remaining.
        assertThat(incoming.status).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(fills.get(0).sellOrderId).isEqualTo(r1OrderId);
        assertThat(r1.owningNode).isNull();
        assertThat(fills.get(1).sellOrderId).isEqualTo(r2OrderId);
        assertThat(r2.owningNode).isNull();
        // The unfilled remainder must be rested on the bid side.
        assertThat(book.hasBids()).isTrue();
        assertThat(book.bestBid()).isEqualTo(5000L);
    }

    // =========================================================================
    // Fill price is always the resting order's price, not the incoming order's price.
    // =========================================================================

    @Test
    void fillPrice_equalsRestingPriceRegardlessOfIncomingPrice() {
        Order resting = ask(5000, 100);
        book.addToBook(resting);

        // Incoming bid at 5100; buyer receives price improvement, fill executes at the resting ask price.
        Order incoming = bid(5100, 100);
        List<Fill> fills = book.processLimit(incoming);

        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).fillPriceTicks).isEqualTo(5000L);
    }

    // =========================================================================
    // fill.instrumentId matches the OrderBook's instrumentId.
    // =========================================================================

    @Test
    void fill_instrumentIdMatchesBookInstrumentId() {
        book.addToBook(ask(5000, 100));
        List<Fill> fills = book.processLimit(bid(5000, 100));

        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).instrumentId).isEqualTo(INSTRUMENT_ID);
    }

    // =========================================================================
    // fillId is sequential starting from 1 across multiple fills.
    // =========================================================================

    @Test
    void fillIds_areSequentialStartingFromOne() {
        book.addToBook(ask(5000, 100));
        book.addToBook(ask(5000, 100));
        book.addToBook(ask(5000, 100));

        List<Fill> fills = book.processLimit(bid(5000, 300));

        assertThat(fills).hasSize(3);
        assertThat(fills.get(0).fillId).isEqualTo(1L);
        assertThat(fills.get(1).fillId).isEqualTo(2L);
        assertThat(fills.get(2).fillId).isEqualTo(3L);
    }

    // =========================================================================
    // buyWasAggressor is false when an ASK is the aggressor.
    // =========================================================================

    @Test
    void buyWasAggressor_falseWhenAskIsAggressor() {
        // Resting bid; incoming ASK crosses the spread.
        book.addToBook(bid(5000, 100));

        Order incoming = order(OrderSide.ASK, 4900, 100);
        List<Fill> fills = book.processLimit(incoming);

        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).buyWasAggressor).isFalse();    // ASK order was the aggressor.
        assertThat(fills.get(0).sellOrderId).isEqualTo(incoming.orderId);
    }
}
