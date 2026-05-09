package io.github.shamshadansari.lobengine.book;

import io.github.shamshadansari.lobengine.domain.BookUpdate;
import io.github.shamshadansari.lobengine.domain.Fill;
import io.github.shamshadansari.lobengine.domain.Order;
import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderStatus;
import io.github.shamshadansari.lobengine.domain.OrderType;
import io.github.shamshadansari.lobengine.marketdata.MarketDataPublisher;
import io.github.shamshadansari.lobengine.metrics.EngineMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBookFeedIntegrationTest {

    private static final long INSTRUMENT_ID = 42L;

    private MarketDataPublisher publisher;
    private OrderBook           book;
    private List<UpdateRecord>  updates;
    private List<FillRecord>    fills;
    private long                nextOrderId;

    @BeforeEach
    void setUp() {
        publisher   = new MarketDataPublisher();
        book        = new OrderBook(INSTRUMENT_ID, new EngineMetrics(), publisher);
        updates     = new ArrayList<>();
        fills       = new ArrayList<>();
        nextOrderId = 1L;

        publisher.addBookUpdateListener(update -> updates.add(UpdateRecord.copy(update)));
        publisher.addFillListener(fill -> fills.add(FillRecord.copy(fill)));
    }

    @Test
    void addToBookPublishesAddWithInstrumentIdAndLiveVolume() {
        book.addToBook(ask(5000L, 100L));

        assertThat(updates).containsExactly(new UpdateRecord(
            BookUpdate.Type.ADD, INSTRUMENT_ID, OrderSide.ASK, 5000L, 100L, 0L));
    }

    @Test
    void cancelOrderPublishesLiveVolumeWhenLevelRemains() {
        Order front = ask(5000L, 100L);
        Order back = ask(5000L, 50L);
        book.addToBook(front);
        book.addToBook(back);
        updates.clear();

        assertThat(book.cancelOrder(front.orderId)).isTrue();

        assertThat(updates).containsExactly(new UpdateRecord(
            BookUpdate.Type.CANCEL, INSTRUMENT_ID, OrderSide.ASK, 5000L, 50L, 2L));
    }

    @Test
    void cancelOrderPublishesMinusOneWhenLevelRemoved() {
        Order only = ask(5000L, 100L);
        book.addToBook(only);
        updates.clear();

        assertThat(book.cancelOrder(only.orderId)).isTrue();

        assertThat(updates).containsExactly(new UpdateRecord(
            BookUpdate.Type.CANCEL, INSTRUMENT_ID, OrderSide.ASK, 5000L, -1L, 1L));
    }

    @Test
    void cancelMissPublishesNoUpdate() {
        assertThat(book.cancelOrder(99_999L)).isFalse();

        assertThat(updates).isEmpty();
    }

    @Test
    void partialTradePublishesFillAndLiveLevelUpdate() {
        book.processLimit(ask(5000L, 200L));
        updates.clear();

        book.processLimit(bid(5000L, 80L));

        assertThat(fills).containsExactly(new FillRecord(INSTRUMENT_ID, 5000L, 80L, true));
        assertThat(updates).containsExactly(new UpdateRecord(
            BookUpdate.Type.TRADE, INSTRUMENT_ID, OrderSide.ASK, 5000L, 120L, 1L));
    }

    @Test
    void fullTradePublishesMinusOneWhenLevelRemoved() {
        book.processLimit(ask(5000L, 100L));
        updates.clear();

        book.processLimit(bid(5000L, 100L));

        assertThat(updates).containsExactly(new UpdateRecord(
            BookUpdate.Type.TRADE, INSTRUMENT_ID, OrderSide.ASK, 5000L, -1L, 1L));
    }

    @Test
    void inPlaceQuantityReductionPublishesModifyWithLiveVolume() {
        Order resting = ask(5000L, 100L);
        book.addToBook(resting);
        updates.clear();

        Order result = book.processModify(resting.orderId, -1L, 40L);

        assertThat(result).isSameAs(resting);
        assertThat(updates).containsExactly(new UpdateRecord(
            BookUpdate.Type.MODIFY, INSTRUMENT_ID, OrderSide.ASK, 5000L, 40L, 1L));
    }

    @Test
    void noOpModifyPublishesNoUpdate() {
        Order resting = ask(5000L, 100L);
        book.addToBook(resting);
        updates.clear();

        Order result = book.processModify(resting.orderId, -1L, -1L);

        assertThat(result).isSameAs(resting);
        assertThat(updates).isEmpty();
    }

    @Test
    void cancelAndResubmitModifyPublishesCancelThenAdd() {
        Order resting = ask(5000L, 100L);
        book.addToBook(resting);
        updates.clear();

        Order replacement = book.processModify(resting.orderId, 5001L, 100L);

        assertThat(replacement).isNotSameAs(resting);
        assertThat(updates).containsExactly(
            new UpdateRecord(BookUpdate.Type.CANCEL, INSTRUMENT_ID, OrderSide.ASK, 5000L, -1L, 1L),
            new UpdateRecord(BookUpdate.Type.ADD, INSTRUMENT_ID, OrderSide.ASK, 5001L, 100L, 2L)
        );
    }

    @Test
    void marketOrderUnfilledRemainderDoesNotPublishAdd() {
        book.processMarket(bid(5000L, 100L));

        assertThat(updates).isEmpty();
        assertThat(fills).isEmpty();
    }

    private Order ask(long priceTicks, long qty) {
        return order(OrderSide.ASK, priceTicks, qty);
    }

    private Order bid(long priceTicks, long qty) {
        return order(OrderSide.BID, priceTicks, qty);
    }

    private Order order(OrderSide side, long priceTicks, long qty) {
        Order order = new Order();
        order.orderId      = nextOrderId++;
        order.side         = side;
        order.type         = OrderType.LIMIT;
        order.priceTicks   = priceTicks;
        order.originalQty  = qty;
        order.remainingQty = qty;
        order.status       = OrderStatus.PENDING;
        return order;
    }

    private record UpdateRecord(BookUpdate.Type type,
                                long instrumentId,
                                OrderSide side,
                                long priceTicks,
                                long newVolumeAtLevel,
                                long sequenceNumber) {

        private static UpdateRecord copy(BookUpdate update) {
            return new UpdateRecord(update.updateType,
                                    update.instrumentId,
                                    update.side,
                                    update.priceTicks,
                                    update.newVolumeAtLevel,
                                    update.sequenceNumber);
        }
    }

    private record FillRecord(long instrumentId,
                              long fillPriceTicks,
                              long fillQty,
                              boolean buyWasAggressor) {

        private static FillRecord copy(Fill fill) {
            return new FillRecord(fill.instrumentId,
                                  fill.fillPriceTicks,
                                  fill.fillQty,
                                  fill.buyWasAggressor);
        }
    }
}
