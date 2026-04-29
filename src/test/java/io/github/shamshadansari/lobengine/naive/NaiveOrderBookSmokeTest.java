package io.github.shamshadansari.lobengine.naive;

import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderStatus;
import io.github.shamshadansari.lobengine.domain.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NaiveOrderBookSmokeTest {

    private NaiveOrderBook book;
    private long idCounter = 1;

    @BeforeEach
    void setUp() {
        book = new NaiveOrderBook();
        idCounter = 1;
    }

    // ------------------------------------------------------------------------------------------
    // Helpers to build limit, market, and IOC orders without repeating boilerplate in every test
    // ------------------------------------------------------------------------------------------

    private NaiveOrder limitOrder(OrderSide side, long priceTicks, long qty) {
        NaiveOrder o = new NaiveOrder();
        o.orderId       = idCounter++;
        o.side          = side;
        o.type          = OrderType.LIMIT;
        o.priceTicks    = priceTicks;
        o.originalQty   = qty;
        o.remainingQty  = qty;
        o.timestampNanos = System.nanoTime();
        return o;
    }

    private NaiveOrder marketOrder(OrderSide side, long qty) {
        NaiveOrder o = new NaiveOrder();
        o.orderId       = idCounter++;
        o.side          = side;
        o.type          = OrderType.MARKET;
        o.priceTicks    = 0;
        o.originalQty   = qty;
        o.remainingQty  = qty;
        o.timestampNanos = System.nanoTime();
        return o;
    }

    private NaiveOrder iocOrder(OrderSide side, long priceTicks, long qty) {
        NaiveOrder o = new NaiveOrder();
        o.orderId       = idCounter++;
        o.side          = side;
        o.type          = OrderType.IOC;
        o.priceTicks    = priceTicks;
        o.originalQty   = qty;
        o.remainingQty  = qty;
        o.timestampNanos = System.nanoTime();
        return o;
    }

    // ---------------------------------------------------------------------------
    // Smoke tests
    // ---------------------------------------------------------------------------

    @Test
    void limit_buy_below_best_ask_rests_in_book_without_filling() {
        book.processLimit(limitOrder(OrderSide.ASK, 5000, 100));

        List<NaiveFill> fills = book.processLimit(limitOrder(OrderSide.BID, 4999, 100));

        assertTrue(fills.isEmpty());
        assertEquals(1, book.getBids().size());
        assertEquals(1, book.getAsks().size());
    }

    @Test
    void limit_buy_at_ask_price_produces_a_fill() {
        book.processLimit(limitOrder(OrderSide.ASK, 5000, 100));

        List<NaiveFill> fills = book.processLimit(limitOrder(OrderSide.BID, 5000, 100));

        assertEquals(1, fills.size());
    }

    @Test
    void fill_price_is_the_resting_ask_price_not_the_bid_price() {
        book.processLimit(limitOrder(OrderSide.ASK, 5000, 100));

        // Bid offers more than the ask — fill must execute at the ask's price (5000)
        List<NaiveFill> fills = book.processLimit(limitOrder(OrderSide.BID, 5100, 100));

        assertEquals(5000L, fills.get(0).priceTicks);
    }

    @Test
    void fill_qty_is_min_of_incoming_and_resting_qty() {
        book.processLimit(limitOrder(OrderSide.ASK, 5000, 60));

        List<NaiveFill> fills = book.processLimit(limitOrder(OrderSide.BID, 5000, 100));

        assertEquals(60L, fills.get(0).qty);
    }

    @Test
    void fully_filled_resting_order_is_removed_from_book() {
        book.processLimit(limitOrder(OrderSide.ASK, 5000, 100));
        book.processLimit(limitOrder(OrderSide.BID, 5000, 100));

        assertTrue(book.getAsks().isEmpty());
    }

    @Test
    void partially_filled_resting_order_stays_with_reduced_qty() {
        book.processLimit(limitOrder(OrderSide.ASK, 5000, 200));
        book.processLimit(limitOrder(OrderSide.BID, 5000, 80));

        assertEquals(1, book.getAsks().size());
        assertEquals(120L, book.getAsks().get(0).remainingQty);
    }

    @Test
    void market_order_fills_against_available_liquidity() {
        book.processLimit(limitOrder(OrderSide.ASK, 5000, 100));

        List<NaiveFill> fills = book.processMarket(marketOrder(OrderSide.BID, 100));

        assertEquals(1, fills.size());
        assertEquals(100L, fills.get(0).qty);
        assertTrue(book.getAsks().isEmpty());
    }

    @Test
    void ioc_order_does_not_rest_when_no_liquidity() {
        NaiveOrder ioc = iocOrder(OrderSide.BID, 5000, 100);
        List<NaiveFill> fills = book.processIOC(ioc);

        assertTrue(fills.isEmpty());
        assertTrue(book.getBids().isEmpty());
        assertEquals(OrderStatus.CANCELLED, ioc.status);
    }

    @Test
    void cancel_removes_resting_order_and_returns_true() {
        NaiveOrder ask = limitOrder(OrderSide.ASK, 5000, 100);
        book.processLimit(ask);

        boolean removed = book.cancel(ask.orderId);

        assertTrue(removed);
        assertTrue(book.getAsks().isEmpty());
    }

    @Test
    void cancel_returns_false_for_unknown_order_id() {
        boolean removed = book.cancel(99999L);

        assertFalse(removed);
    }
}
