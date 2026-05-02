package io.github.shamshadansari.lobengine.naive;

import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderStatus;
import io.github.shamshadansari.lobengine.domain.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;


class NaiveOrderBookTest {

    private NaiveOrderBook book;
    private final AtomicLong idSeq    = new AtomicLong(1);
    private final AtomicLong timeSeq  = new AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() {
        book = new NaiveOrderBook();
        idSeq.set(1);
        timeSeq.set(1_000_000L);
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private NaiveOrder limit(OrderSide side, long priceTicks, long qty) {
        NaiveOrder o = new NaiveOrder();
        o.orderId        = idSeq.getAndIncrement();
        o.side           = side;
        o.type           = OrderType.LIMIT;
        o.priceTicks     = priceTicks;
        o.originalQty    = qty;
        o.remainingQty   = qty;
        o.timestampNanos = timeSeq.getAndIncrement();  // guaranteed monotonic ordering
        return o;
    }

    private NaiveOrder market(OrderSide side, long qty) {
        NaiveOrder o = new NaiveOrder();
        o.orderId        = idSeq.getAndIncrement();
        o.side           = side;
        o.type           = OrderType.MARKET;
        o.priceTicks     = 0;
        o.originalQty    = qty;
        o.remainingQty   = qty;
        o.timestampNanos = timeSeq.getAndIncrement();
        return o;
    }

    private NaiveOrder ioc(OrderSide side, long priceTicks, long qty) {
        NaiveOrder o = new NaiveOrder();
        o.orderId        = idSeq.getAndIncrement();
        o.side           = side;
        o.type           = OrderType.IOC;
        o.priceTicks     = priceTicks;
        o.originalQty    = qty;
        o.remainingQty   = qty;
        o.timestampNanos = timeSeq.getAndIncrement();
        return o;
    }

    // ---------------------------------------------------------------------------
    // Limit order — resting (no cross)
    // ---------------------------------------------------------------------------

    @Nested
    class LimitOrderResting {

        @Test
        void bid_below_ask_produces_no_fill_and_rests() {
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            List<NaiveFill> fills = book.processLimit(limit(OrderSide.BID, 4999, 100));

            assertTrue(fills.isEmpty(), "Bid below ask must not match");
            assertEquals(1, book.getBids().size(), "Bid must rest in book");
            assertEquals(1, book.getAsks().size(), "Ask must remain in book");
        }

        @Test
        void ask_above_bid_produces_no_fill_and_rests() {
            book.processLimit(limit(OrderSide.BID, 5000, 100));
            List<NaiveFill> fills = book.processLimit(limit(OrderSide.ASK, 5001, 100));

            assertTrue(fills.isEmpty(), "Ask above bid must not match");
            assertEquals(1, book.getBids().size());
            assertEquals(1, book.getAsks().size());
        }

        @Test
        void resting_bid_status_is_pending_when_no_fills_occurred() {
            NaiveOrder bid = limit(OrderSide.BID, 4999, 100);
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            book.processLimit(bid);

            assertEquals(OrderStatus.PENDING, bid.status);
        }

        @Test
        void resting_ask_status_is_pending_when_no_fills_occurred() {
            NaiveOrder ask = limit(OrderSide.ASK, 5001, 100);
            book.processLimit(limit(OrderSide.BID, 5000, 100));
            book.processLimit(ask);

            assertEquals(OrderStatus.PENDING, ask.status);
        }

        @Test
        void original_qty_is_unchanged_on_resting_order() {
            NaiveOrder bid = limit(OrderSide.BID, 4999, 150);
            book.processLimit(bid);

            assertEquals(150L, bid.originalQty);
            assertEquals(150L, bid.remainingQty);
        }
    }

    // ---------------------------------------------------------------------------
    // Limit order — immediate fill
    // ---------------------------------------------------------------------------

    @Nested
    class LimitOrderMatching {

        @Test
        void bid_at_ask_price_produces_exactly_one_fill() {
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            List<NaiveFill> fills = book.processLimit(limit(OrderSide.BID, 5000, 100));

            assertEquals(1, fills.size());
        }

        @Test
        void bid_above_ask_price_still_fills_at_ask_price() {
            // Price improvement: buyer offers 5100, seller wants 5000.
            // Fill must execute at 5000 (passive side sets price).
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            List<NaiveFill> fills = book.processLimit(limit(OrderSide.BID, 5100, 100));

            assertEquals(1, fills.size());
            assertEquals(5000L, fills.get(0).priceTicks,
                    "Fill price must be the resting ask's price, not the aggressor's price");
        }

        @Test
        void ask_below_bid_price_fills_at_bid_price() {
            book.processLimit(limit(OrderSide.BID, 5000, 100));
            List<NaiveFill> fills = book.processLimit(limit(OrderSide.ASK, 4900, 100));

            assertEquals(5000L, fills.get(0).priceTicks,
                    "Fill price must be the resting bid's price");
        }

        @Test
        void fill_aggressor_id_is_the_incoming_order() {
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            NaiveOrder bid = limit(OrderSide.BID, 5000, 100);
            List<NaiveFill> fills = book.processLimit(bid);

            assertEquals(bid.orderId, fills.get(0).aggressorOrderId);
        }

        @Test
        void fill_passive_id_is_the_resting_order() {
            NaiveOrder ask = limit(OrderSide.ASK, 5000, 100);
            book.processLimit(ask);
            List<NaiveFill> fills = book.processLimit(limit(OrderSide.BID, 5000, 100));

            assertEquals(ask.orderId, fills.get(0).passiveOrderId);
        }

        @Test
        void fill_qty_is_min_of_incoming_and_resting() {
            book.processLimit(limit(OrderSide.ASK, 5000, 60));
            List<NaiveFill> fills = book.processLimit(limit(OrderSide.BID, 5000, 100));

            assertEquals(60L, fills.get(0).qty);
        }

        @Test
        void fully_matched_resting_ask_is_removed_from_book() {
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            book.processLimit(limit(OrderSide.BID, 5000, 100));

            assertTrue(book.getAsks().isEmpty(), "Fully filled ask must not remain in book");
        }

        @Test
        void fully_matched_resting_bid_is_removed_from_book() {
            book.processLimit(limit(OrderSide.BID, 5000, 100));
            book.processLimit(limit(OrderSide.ASK, 5000, 100));

            assertTrue(book.getBids().isEmpty(), "Fully filled bid must not remain in book");
        }

        @Test
        void incoming_order_status_is_filled_after_full_match() {
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            NaiveOrder bid = limit(OrderSide.BID, 5000, 100);
            book.processLimit(bid);

            assertEquals(OrderStatus.FILLED, bid.status);
        }

        @Test
        void resting_order_status_is_filled_after_full_match() {
            NaiveOrder ask = limit(OrderSide.ASK, 5000, 100);
            book.processLimit(ask);
            book.processLimit(limit(OrderSide.BID, 5000, 100));

            assertEquals(OrderStatus.FILLED, ask.status);
        }
    }

    // ---------------------------------------------------------------------------
    // Partial fills
    // ---------------------------------------------------------------------------

    @Nested
    class PartialFills {

        @Test
        void incoming_larger_than_resting_leaves_incoming_in_book() {
            // Ask 60, Bid 100 → 60 filled, 40 remains as a resting bid
            book.processLimit(limit(OrderSide.ASK, 5000, 60));
            NaiveOrder bid = limit(OrderSide.BID, 5000, 100);
            book.processLimit(bid);

            assertEquals(1, book.getBids().size(), "Remainder of incoming bid must rest");
            assertEquals(40L, book.getBids().get(0).remainingQty);
        }

        @Test
        void resting_larger_than_incoming_leaves_resting_in_book() {
            // Ask 200, Bid 80 → 80 filled, ask stays with 120 remaining
            book.processLimit(limit(OrderSide.ASK, 5000, 200));
            book.processLimit(limit(OrderSide.BID, 5000, 80));

            assertEquals(1, book.getAsks().size());
            assertEquals(120L, book.getAsks().get(0).remainingQty);
        }

        @Test
        void resting_order_status_is_partially_filled_after_partial_match() {
            NaiveOrder ask = limit(OrderSide.ASK, 5000, 200);
            book.processLimit(ask);
            book.processLimit(limit(OrderSide.BID, 5000, 80));

            assertEquals(OrderStatus.PARTIALLY_FILLED, ask.status);
        }

        @Test
        void incoming_order_status_is_partially_filled_when_it_rests_after_partial_match() {
            // Incoming bid partially fills, remainder rests — status must be PARTIALLY_FILLED not PENDING
            book.processLimit(limit(OrderSide.ASK, 5000, 60));
            NaiveOrder bid = limit(OrderSide.BID, 5000, 100);
            book.processLimit(bid);

            assertEquals(OrderStatus.PARTIALLY_FILLED, bid.status,
                    "Order that partially filled and then rested must be PARTIALLY_FILLED, not PENDING");
        }

        @Test
        void original_qty_is_never_mutated_by_matching() {
            NaiveOrder ask = limit(OrderSide.ASK, 5000, 200);
            book.processLimit(ask);
            book.processLimit(limit(OrderSide.BID, 5000, 80));

            assertEquals(200L, ask.originalQty, "originalQty must never change");
        }

        @Test
        void two_sequential_partial_fills_drain_resting_order_completely() {
            NaiveOrder ask = limit(OrderSide.ASK, 5000, 200);
            book.processLimit(ask);

            book.processLimit(limit(OrderSide.BID, 5000, 100));
            assertEquals(100L, ask.remainingQty);

            book.processLimit(limit(OrderSide.BID, 5000, 100));
            assertEquals(0L, ask.remainingQty);
            assertEquals(OrderStatus.FILLED, ask.status);
            assertTrue(book.getAsks().isEmpty());
        }
    }

    // ---------------------------------------------------------------------------
    // Price priority
    // ---------------------------------------------------------------------------

    @Nested
    class PricePriority {

        @Test
        void lowest_ask_fills_before_higher_ask_regardless_of_arrival_time() {
            // Ask at 5001 arrives first, ask at 5000 arrives second.
            // A bid must fill 5000 first (better price).
            NaiveOrder expensiveAsk = limit(OrderSide.ASK, 5001, 100);
            NaiveOrder cheapAsk     = limit(OrderSide.ASK, 5000, 100);
            book.processLimit(expensiveAsk);
            book.processLimit(cheapAsk);

            List<NaiveFill> fills = book.processLimit(limit(OrderSide.BID, 5001, 100));

            assertEquals(1, fills.size());
            assertEquals(5000L, fills.get(0).priceTicks,
                    "Cheapest ask must fill first — price priority over time priority");
            assertEquals(cheapAsk.orderId, fills.get(0).passiveOrderId);
        }

        @Test
        void highest_bid_fills_before_lower_bid_regardless_of_arrival_time() {
            NaiveOrder lowBid  = limit(OrderSide.BID, 4999, 100);
            NaiveOrder highBid = limit(OrderSide.BID, 5000, 100);
            book.processLimit(lowBid);
            book.processLimit(highBid);

            List<NaiveFill> fills = book.processLimit(limit(OrderSide.ASK, 4999, 100));

            assertEquals(5000L, fills.get(0).priceTicks,
                    "Highest bid must fill first");
            assertEquals(highBid.orderId, fills.get(0).passiveOrderId);
        }
    }

    // ---------------------------------------------------------------------------
    // Time priority (FIFO at same price level)
    // ---------------------------------------------------------------------------

    @Nested
    class TimePriority {

        @Test
        void earliest_ask_at_same_price_fills_before_later_ask() {
            NaiveOrder first  = limit(OrderSide.ASK, 5000, 100);
            NaiveOrder second = limit(OrderSide.ASK, 5000, 100);
            NaiveOrder third  = limit(OrderSide.ASK, 5000, 100);
            book.processLimit(first);
            book.processLimit(second);
            book.processLimit(third);

            // Bid fills exactly one order's worth
            List<NaiveFill> fills = book.processLimit(limit(OrderSide.BID, 5000, 100));

            assertEquals(1, fills.size());
            assertEquals(first.orderId, fills.get(0).passiveOrderId,
                    "First ask submitted must fill first — FIFO");
        }

        @Test
        void earliest_bid_at_same_price_fills_before_later_bid() {
            NaiveOrder first  = limit(OrderSide.BID, 5000, 100);
            NaiveOrder second = limit(OrderSide.BID, 5000, 100);
            book.processLimit(first);
            book.processLimit(second);

            List<NaiveFill> fills = book.processLimit(limit(OrderSide.ASK, 5000, 100));

            assertEquals(first.orderId, fills.get(0).passiveOrderId,
                    "First bid submitted must fill first — FIFO");
        }
    }

    // ---------------------------------------------------------------------------
    // Multi-level walk (sweep)
    // ---------------------------------------------------------------------------

    @Nested
    class MultiLevelWalk {

        @Test
        void bid_sweeps_multiple_ask_price_levels_in_price_order() {
            NaiveOrder ask1 = limit(OrderSide.ASK, 5000, 100);
            NaiveOrder ask2 = limit(OrderSide.ASK, 5001, 100);
            NaiveOrder ask3 = limit(OrderSide.ASK, 5002, 100);
            book.processLimit(ask1);
            book.processLimit(ask2);
            book.processLimit(ask3);

            // Buy 250 — should fill 100@5000, 100@5001, 50@5002
            List<NaiveFill> fills = book.processLimit(limit(OrderSide.BID, 5002, 250));

            assertEquals(3, fills.size(), "Must generate one fill per price level touched");
            assertEquals(5000L, fills.get(0).priceTicks);
            assertEquals(100L,  fills.get(0).qty);
            assertEquals(5001L, fills.get(1).priceTicks);
            assertEquals(100L,  fills.get(1).qty);
            assertEquals(5002L, fills.get(2).priceTicks);
            assertEquals(50L,   fills.get(2).qty);
        }

        @Test
        void partial_sweep_leaves_correct_residual_at_touched_level() {
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            book.processLimit(limit(OrderSide.ASK, 5001, 100));
            book.processLimit(limit(OrderSide.ASK, 5002, 100));

            book.processLimit(limit(OrderSide.BID, 5002, 250));

            // 50 of the 5002 ask must remain
            assertEquals(1, book.getAsks().size());
            assertEquals(5002L, book.getAsks().get(0).priceTicks);
            assertEquals(50L,   book.getAsks().get(0).remainingQty);
        }

        @Test
        void bid_does_not_cross_levels_beyond_its_limit_price() {
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            book.processLimit(limit(OrderSide.ASK, 5001, 100));

            // Bid is only willing to pay up to 5000 — must not touch 5001
            List<NaiveFill> fills = book.processLimit(limit(OrderSide.BID, 5000, 200));

            assertEquals(1, fills.size(), "Bid must not fill at a price above its limit");
            assertEquals(5000L, fills.get(0).priceTicks);
        }

        @Test
        void ask_sweeps_multiple_bid_price_levels_in_price_order() {
            book.processLimit(limit(OrderSide.BID, 5002, 100));
            book.processLimit(limit(OrderSide.BID, 5001, 100));
            book.processLimit(limit(OrderSide.BID, 5000, 100));

            List<NaiveFill> fills = book.processLimit(limit(OrderSide.ASK, 5000, 250));

            assertEquals(3, fills.size());
            assertEquals(5002L, fills.get(0).priceTicks);
            assertEquals(5001L, fills.get(1).priceTicks);
            assertEquals(5000L, fills.get(2).priceTicks);
        }
    }

    // ---------------------------------------------------------------------------
    // Fill ID integrity
    // ---------------------------------------------------------------------------

    @Nested
    class FillIdIntegrity {

        @Test
        void fill_ids_are_unique_across_successive_matches() {
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            book.processLimit(limit(OrderSide.ASK, 5001, 100));

            List<NaiveFill> fills = book.processLimit(limit(OrderSide.BID, 5001, 200));

            assertEquals(2, fills.size());
            assertNotEquals(fills.get(0).fillId, fills.get(1).fillId,
                    "Each fill must have a unique ID");
        }

        @Test
        void fill_ids_increment_monotonically() {
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            book.processLimit(limit(OrderSide.ASK, 5001, 100));

            List<NaiveFill> fills = book.processLimit(limit(OrderSide.BID, 5001, 200));

            assertTrue(fills.get(1).fillId > fills.get(0).fillId,
                    "Fill IDs must increment — later fills have higher IDs");
        }
    }

    // ---------------------------------------------------------------------------
    // Market orders
    // ---------------------------------------------------------------------------

    @Nested
    class MarketOrders {

        @Test
        void market_buy_fills_all_available_asks_ignoring_price() {
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            book.processLimit(limit(OrderSide.ASK, 9999, 100));  // extremely high ask

            List<NaiveFill> fills = book.processMarket(market(OrderSide.BID, 200));

            assertEquals(2, fills.size(), "Market order must cross any price");
            assertTrue(book.getAsks().isEmpty());
        }

        @Test
        void market_sell_fills_all_available_bids_ignoring_price() {
            book.processLimit(limit(OrderSide.BID, 5000, 100));
            book.processLimit(limit(OrderSide.BID, 1,    100));  // extremely low bid

            List<NaiveFill> fills = book.processMarket(market(OrderSide.ASK, 200));

            assertEquals(2, fills.size());
            assertTrue(book.getBids().isEmpty());
        }

        @Test
        void market_order_with_no_liquidity_is_cancelled_and_never_rests() {
            NaiveOrder m = market(OrderSide.BID, 100);
            book.processMarket(m);

            assertEquals(OrderStatus.CANCELLED, m.status);
            assertTrue(book.getBids().isEmpty(), "Market order must never rest in the book");
        }

        @Test
        void market_order_partial_liquidity_fills_what_it_can_then_cancels_remainder() {
            book.processLimit(limit(OrderSide.ASK, 5000, 50));
            NaiveOrder m = market(OrderSide.BID, 100);
            List<NaiveFill> fills = book.processMarket(m);

            assertEquals(1, fills.size());
            assertEquals(50L, fills.get(0).qty);
            assertEquals(50L, m.remainingQty);
            assertEquals(OrderStatus.CANCELLED, m.status);
            assertTrue(book.getBids().isEmpty());
        }

        @Test
        void market_order_status_is_filled_when_fully_matched() {
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            NaiveOrder m = market(OrderSide.BID, 100);
            book.processMarket(m);

            assertEquals(OrderStatus.FILLED, m.status);
        }
    }

    // ---------------------------------------------------------------------------
    // IOC orders
    // ---------------------------------------------------------------------------

    @Nested
    class IOCOrders {

        @Test
        void ioc_with_no_matching_liquidity_is_cancelled_not_rested() {
            NaiveOrder o = ioc(OrderSide.BID, 5000, 100);
            book.processIOC(o);

            assertEquals(OrderStatus.CANCELLED, o.status);
            assertTrue(book.getBids().isEmpty(), "IOC must never rest in the book");
        }

        @Test
        void ioc_partial_fill_cancels_remainder_and_does_not_rest() {
            book.processLimit(limit(OrderSide.ASK, 5000, 50));
            NaiveOrder o = ioc(OrderSide.BID, 5000, 100);
            List<NaiveFill> fills = book.processIOC(o);

            assertEquals(1, fills.size());
            assertEquals(50L, fills.get(0).qty);
            assertEquals(OrderStatus.CANCELLED, o.status);
            assertTrue(book.getBids().isEmpty(), "IOC remainder must not rest");
        }

        @Test
        void ioc_full_fill_results_in_filled_status() {
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            NaiveOrder o = ioc(OrderSide.BID, 5000, 100);
            book.processIOC(o);

            assertEquals(OrderStatus.FILLED, o.status);
        }

        @Test
        void ioc_respects_its_price_limit_unlike_market_order() {
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            book.processLimit(limit(OrderSide.ASK, 5001, 100));

            // IOC at 5000 must not touch the 5001 ask
            NaiveOrder o = ioc(OrderSide.BID, 5000, 200);
            List<NaiveFill> fills = book.processIOC(o);

            assertEquals(1, fills.size(), "IOC must not cross its price limit");
            assertEquals(5000L, fills.get(0).priceTicks);
        }

        @Test
        void ioc_sweeps_multiple_levels_then_cancels_unfilled_remainder() {
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            book.processLimit(limit(OrderSide.ASK, 5001, 100));

            // IOC bid willing to pay up to 5002, but only 200 available across both levels
            NaiveOrder o = ioc(OrderSide.BID, 5002, 250);
            List<NaiveFill> fills = book.processIOC(o);

            assertEquals(2, fills.size(), "IOC must sweep both levels");
            assertEquals(5000L, fills.get(0).priceTicks);
            assertEquals(100L,  fills.get(0).qty);
            assertEquals(5001L, fills.get(1).priceTicks);
            assertEquals(100L,  fills.get(1).qty);
            assertEquals(50L,   o.remainingQty, "Unfilled 50 must remain as remainingQty");
            assertEquals(OrderStatus.CANCELLED, o.status, "Remainder must be cancelled, not rested");
            assertTrue(book.getBids().isEmpty(), "IOC must never rest in the book");
            assertTrue(book.getAsks().isEmpty(), "Both ask levels must be fully consumed");
        }
    }

    // ---------------------------------------------------------------------------
    // Cancel
    // ---------------------------------------------------------------------------

    @Nested
    class CancelBehavior {

        @Test
        void cancel_bid_removes_it_and_returns_true() {
            NaiveOrder bid = limit(OrderSide.BID, 5000, 100);
            book.processLimit(bid);

            assertTrue(book.cancel(bid.orderId));
            assertTrue(book.getBids().isEmpty());
        }

        @Test
        void cancel_ask_removes_it_and_returns_true() {
            NaiveOrder ask = limit(OrderSide.ASK, 5000, 100);
            book.processLimit(ask);

            assertTrue(book.cancel(ask.orderId));
            assertTrue(book.getAsks().isEmpty());
        }

        @Test
        void cancel_unknown_id_returns_false_without_side_effects() {
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            int bookSizeBefore = book.getAsks().size();

            assertFalse(book.cancel(99999L));
            assertEquals(bookSizeBefore, book.getAsks().size(), "Book must not change");
        }

        @Test
        void cancel_already_filled_order_returns_false() {
            NaiveOrder ask = limit(OrderSide.ASK, 5000, 100);
            book.processLimit(ask);
            book.processLimit(limit(OrderSide.BID, 5000, 100));  // fully fills ask

            assertFalse(book.cancel(ask.orderId),
                    "Filled order is no longer in the book — cancel must return false");
        }

        @Test
        void double_cancel_second_attempt_returns_false() {
            NaiveOrder ask = limit(OrderSide.ASK, 5000, 100);
            book.processLimit(ask);

            assertTrue(book.cancel(ask.orderId));
            assertFalse(book.cancel(ask.orderId), "Second cancel of same ID must return false");
        }

        @Test
        void cancelled_order_mid_queue_is_skipped_by_subsequent_fills() {
            // Asks: A (100), B (100), C (100) all at same price
            NaiveOrder a = limit(OrderSide.ASK, 5000, 100);
            NaiveOrder b = limit(OrderSide.ASK, 5000, 100);
            NaiveOrder c = limit(OrderSide.ASK, 5000, 100);
            book.processLimit(a);
            book.processLimit(b);
            book.processLimit(c);

            book.cancel(b.orderId);

            // Bid for 200 must fill A and C, skipping B entirely
            List<NaiveFill> fills = book.processLimit(limit(OrderSide.BID, 5000, 200));

            assertEquals(2, fills.size());
            assertTrue(fills.stream().noneMatch(f -> f.passiveOrderId == b.orderId),
                    "Cancelled order B must not appear in fills");
            assertEquals(a.orderId, fills.get(0).passiveOrderId);
            assertEquals(c.orderId, fills.get(1).passiveOrderId);
        }

        @Test
        void cancel_partially_filled_resting_order_removes_it_and_returns_true() {
            NaiveOrder ask = limit(OrderSide.ASK, 5000, 200);
            book.processLimit(ask);
            book.processLimit(limit(OrderSide.BID, 5000, 80)); // partial fill, 120 remains

            assertEquals(OrderStatus.PARTIALLY_FILLED, ask.status);
            assertTrue(book.cancel(ask.orderId));
            assertTrue(book.getAsks().isEmpty());
        }
    }

    // ---------------------------------------------------------------------------
    // Book state integrity
    // ---------------------------------------------------------------------------

    @Nested
    class BookStateIntegrity {

        @Test
        void bids_are_always_sorted_highest_price_first() {
            book.processLimit(limit(OrderSide.BID, 4998, 100));
            book.processLimit(limit(OrderSide.BID, 5000, 100));
            book.processLimit(limit(OrderSide.BID, 4999, 100));

            List<NaiveOrder> bids = book.getBids();
            assertEquals(5000L, bids.get(0).priceTicks, "Best bid must be at index 0");
            assertEquals(4999L, bids.get(1).priceTicks);
            assertEquals(4998L, bids.get(2).priceTicks);
        }

        @Test
        void asks_are_always_sorted_lowest_price_first() {
            book.processLimit(limit(OrderSide.ASK, 5002, 100));
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            book.processLimit(limit(OrderSide.ASK, 5001, 100));

            List<NaiveOrder> asks = book.getAsks();
            assertEquals(5000L, asks.get(0).priceTicks, "Best ask must be at index 0");
            assertEquals(5001L, asks.get(1).priceTicks);
            assertEquals(5002L, asks.get(2).priceTicks);
        }

        @Test
        void book_is_empty_after_all_resting_orders_are_fully_filled() {
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            book.processLimit(limit(OrderSide.BID, 5000, 100));

            assertTrue(book.getBids().isEmpty());
            assertTrue(book.getAsks().isEmpty());
        }

        @Test
        void get_bids_returns_defensive_copy_external_mutation_does_not_corrupt_book() {
            book.processLimit(limit(OrderSide.BID, 5000, 100));
            List<NaiveOrder> snapshot = book.getBids();

            // Attempting to mutate the returned list must not affect the book
            assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
            assertEquals(1, book.getBids().size(), "Internal bids list must be unaffected");
        }

        @Test
        void get_asks_returns_defensive_copy_external_mutation_does_not_corrupt_book() {
            book.processLimit(limit(OrderSide.ASK, 5000, 100));
            List<NaiveOrder> snapshot = book.getAsks();

            assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
            assertEquals(1, book.getAsks().size());
        }

        @Test
        void book_handles_empty_state_operations_without_throwing() {
            assertDoesNotThrow(() -> book.processLimit(limit(OrderSide.BID, 5000, 100)));
            assertDoesNotThrow(() -> book.processMarket(market(OrderSide.BID, 100)));
            assertDoesNotThrow(() -> book.processIOC(ioc(OrderSide.BID, 5000, 100)));
            assertDoesNotThrow(() -> book.cancel(1L));
        }
    }
}
