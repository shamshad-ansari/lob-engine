package io.github.shamshadansari.lobengine.engine;

import io.github.shamshadansari.lobengine.BookSnapshot;
import io.github.shamshadansari.lobengine.domain.BookUpdate;
import io.github.shamshadansari.lobengine.domain.EngineEvent;
import io.github.shamshadansari.lobengine.domain.Fill;
import io.github.shamshadansari.lobengine.domain.Order;
import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderType;
import io.github.shamshadansari.lobengine.marketdata.MarketDataPublisher;
import io.github.shamshadansari.lobengine.metrics.EngineMetrics;
import io.github.shamshadansari.lobengine.pool.FillPool;
import io.github.shamshadansari.lobengine.pool.OrderPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchingEngineTest {

    private static final long INSTRUMENT_ID = 1L;

    private EngineMetrics metrics;
    private MatchingEngine engine;
    private long nextOrderId;
    private long nextTimestampNanos;

    @BeforeEach
    void setUp() {
        metrics = new EngineMetrics();
        engine = new MatchingEngine(INSTRUMENT_ID, metrics);
        nextOrderId = 1L;
        nextTimestampNanos = 1_000_000L;
    }

    @Test
    void newLimitOrderCopiesClientOrderIdReferenceToRestingOrder() {
        String clientOrderId = "client-1";
        EngineEvent event = newOrder(OrderSide.ASK, OrderType.LIMIT, 5000, 100);
        event.clientOrderId = clientOrderId;

        engine.processEvent(event);

        Order resting = engine.orderBook().processModify(event.orderId, event.priceTicks, event.qty);
        assertThat(resting).isNotNull();
        assertThat(resting.clientOrderId).isSameAs(clientOrderId);
    }

    @Test
    void newMarketOrderDispatchesToMarketPath() {
        engine.processEvent(newOrder(OrderSide.ASK, OrderType.LIMIT, 5000, 100));

        List<Fill> fills = engine.processEvent(newOrder(OrderSide.BID, OrderType.MARKET, 0, 40));

        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).fillPriceTicks).isEqualTo(5000L);

        BookSnapshot snapshot = engine.snapshot();
        assertThat(snapshot.asks).hasSize(1);
        assertThat(snapshot.asks.get(0).totalVolume).isEqualTo(60L);
        assertThat(snapshot.hasBids()).isFalse();
    }

    @Test
    void newIocOrderDispatchesToIocPathWithoutRestingRemainder() {
        engine.processEvent(newOrder(OrderSide.ASK, OrderType.LIMIT, 5000, 100));

        List<Fill> fills = engine.processEvent(newOrder(OrderSide.BID, OrderType.IOC, 4999, 40));

        assertThat(fills).isEmpty();

        BookSnapshot snapshot = engine.snapshot();
        assertThat(snapshot.hasBids()).isFalse();
        assertThat(snapshot.asks).hasSize(1);
        assertThat(snapshot.asks.get(0).totalVolume).isEqualTo(100L);
    }

    @Test
    void cancelOrderDispatchesToBookCancel() {
        EngineEvent resting = newOrder(OrderSide.ASK, OrderType.LIMIT, 5000, 100);
        engine.processEvent(resting);

        engine.processEvent(cancel(resting.orderId));

        BookSnapshot snapshot = engine.snapshot();
        assertThat(snapshot.hasAsks()).isFalse();
        assertThat(metrics.cancellations()).isEqualTo(1L);
    }

    @Test
    void modifyOrderPreservesPriceWhenPriceSentinelIsUsed() {
        EngineEvent first = newOrder(OrderSide.ASK, OrderType.LIMIT, 5000, 100);
        EngineEvent second = newOrder(OrderSide.ASK, OrderType.LIMIT, 5000, 100);
        engine.processEvent(first);
        engine.processEvent(second);

        engine.processEvent(modify(first.orderId, -1L, 40));

        BookSnapshot snapshot = engine.snapshot();
        assertThat(snapshot.asks).hasSize(1);
        assertThat(snapshot.asks.get(0).priceTicks).isEqualTo(5000L);
        assertThat(snapshot.asks.get(0).totalVolume).isEqualTo(140L);

        List<Fill> fills = engine.processEvent(newOrder(OrderSide.BID, OrderType.LIMIT, 5000, 40));
        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).sellOrderId).isEqualTo(first.orderId);
    }

    @Test
    void instrumentMismatchFailsAsEngineInvariant() {
        EngineEvent event = newOrder(OrderSide.ASK, OrderType.LIMIT, 5000, 100);
        event.instrumentId = INSTRUMENT_ID + 1;

        assertThatThrownBy(() -> engine.processEvent(event))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("instrumentId");
    }

    @Test
    void constructedBookReleasesCancelledOrdersToSharedPool() {
        OrderPool orderPool = new OrderPool(1);
        FillPool fillPool = new FillPool(1);
        MatchingEngine pooledEngine = new MatchingEngine(INSTRUMENT_ID, metrics, orderPool, fillPool);
        EngineEvent resting = newOrder(OrderSide.ASK, OrderType.LIMIT, 5000, 100);

        pooledEngine.processEvent(resting);
        assertThat(orderPool.available()).isZero();

        pooledEngine.processEvent(cancel(resting.orderId));
        assertThat(orderPool.available()).isEqualTo(1);
    }

    @Test
    void injectedPublisherReceivesFeedEventsThroughProcessEvent() {
        MarketDataPublisher publisher = new MarketDataPublisher();
        MatchingEngine feedEngine = new MatchingEngine(INSTRUMENT_ID, metrics, publisher);
        List<BookUpdate.Type> updateTypes = new ArrayList<>();
        List<Long> updateVolumes = new ArrayList<>();
        List<Long> fillInstrumentIds = new ArrayList<>();

        publisher.addBookUpdateListener(update -> {
            updateTypes.add(update.updateType);
            updateVolumes.add(update.newVolumeAtLevel);
        });
        publisher.addFillListener(fill -> fillInstrumentIds.add(fill.instrumentId));

        feedEngine.processEvent(newOrder(OrderSide.ASK, OrderType.LIMIT, 5000, 100));
        List<Fill> fills = feedEngine.processEvent(newOrder(OrderSide.BID, OrderType.LIMIT, 5000, 100));

        assertThat(fills).hasSize(1);
        assertThat(fillInstrumentIds).containsExactly(INSTRUMENT_ID);
        assertThat(updateTypes).containsExactly(BookUpdate.Type.ADD, BookUpdate.Type.TRADE);
        assertThat(updateVolumes).containsExactly(100L, -1L);
    }

    private EngineEvent newOrder(OrderSide side, OrderType orderType, long priceTicks, long qty) {
        EngineEvent event = new EngineEvent();
        event.type = EngineEvent.Type.NEW_ORDER;
        event.instrumentId = INSTRUMENT_ID;
        event.timestampNanos = nextTimestampNanos++;
        event.orderId = nextOrderId++;
        event.clientOrderId = "client-" + event.orderId;
        event.side = side;
        event.orderType = orderType;
        event.priceTicks = priceTicks;
        event.qty = qty;
        event.newPriceTicks = -1L;
        event.newQty = -1L;
        return event;
    }

    private EngineEvent cancel(long targetOrderId) {
        EngineEvent event = new EngineEvent();
        event.type = EngineEvent.Type.CANCEL_ORDER;
        event.instrumentId = INSTRUMENT_ID;
        event.timestampNanos = nextTimestampNanos++;
        event.targetOrderId = targetOrderId;
        event.newPriceTicks = -1L;
        event.newQty = -1L;
        return event;
    }

    private EngineEvent modify(long targetOrderId, long newPriceTicks, long newQty) {
        EngineEvent event = new EngineEvent();
        event.type = EngineEvent.Type.MODIFY_ORDER;
        event.instrumentId = INSTRUMENT_ID;
        event.timestampNanos = nextTimestampNanos++;
        event.targetOrderId = targetOrderId;
        event.newPriceTicks = newPriceTicks;
        event.newQty = newQty;
        return event;
    }
}
