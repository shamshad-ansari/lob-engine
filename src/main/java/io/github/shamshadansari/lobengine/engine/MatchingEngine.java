package io.github.shamshadansari.lobengine.engine;

import io.github.shamshadansari.lobengine.BookSnapshot;
import io.github.shamshadansari.lobengine.book.OrderBook;
import io.github.shamshadansari.lobengine.domain.EngineEvent;
import io.github.shamshadansari.lobengine.domain.Fill;
import io.github.shamshadansari.lobengine.domain.Order;
import io.github.shamshadansari.lobengine.domain.OrderStatus;
import io.github.shamshadansari.lobengine.marketdata.MarketDataPublisher;
import io.github.shamshadansari.lobengine.metrics.EngineMetrics;
import io.github.shamshadansari.lobengine.pool.FillPool;
import io.github.shamshadansari.lobengine.pool.OrderPool;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class MatchingEngine {

    private final long      instrumentId;
    private final OrderPool orderPool;
    private final OrderBook orderBook;

    public MatchingEngine(long instrumentId, EngineMetrics metrics) {
        this(instrumentId, metrics, new OrderPool(10_000), new FillPool(50_000));
    }

    public MatchingEngine(long instrumentId, EngineMetrics metrics, MarketDataPublisher publisher) {
        this(instrumentId, metrics, new OrderPool(10_000), new FillPool(50_000), publisher);
    }

    public MatchingEngine(long instrumentId, EngineMetrics metrics, OrderPool orderPool, FillPool fillPool) {
        this(instrumentId, metrics, orderPool, fillPool, new MarketDataPublisher());
    }

    public MatchingEngine(long instrumentId,
                          EngineMetrics metrics,
                          OrderPool orderPool,
                          FillPool fillPool,
                          MarketDataPublisher publisher) {
        this(instrumentId,
             orderPool,
             new OrderBook(instrumentId,
                           Objects.requireNonNull(metrics, "metrics"),
                           Objects.requireNonNull(orderPool, "orderPool"),
                           Objects.requireNonNull(fillPool, "fillPool"),
                           Objects.requireNonNull(publisher, "publisher")));
    }

    public MatchingEngine(long instrumentId, OrderPool orderPool, OrderBook orderBook) {
        this.instrumentId = instrumentId;
        this.orderPool    = Objects.requireNonNull(orderPool, "orderPool");
        this.orderBook    = Objects.requireNonNull(orderBook, "orderBook");
    }

    public List<Fill> processEvent(EngineEvent event) {
        Objects.requireNonNull(event, "event");
        assertInstrument(event.instrumentId);

        return switch (Objects.requireNonNull(event.type, "event.type")) {
            case NEW_ORDER -> processNewOrder(event);
            case CANCEL_ORDER -> {
                orderBook.cancelOrder(event.targetOrderId);
                yield Collections.emptyList();
            }
            case MODIFY_ORDER -> {
                orderBook.processModify(event.targetOrderId, event.newPriceTicks, event.newQty);
                yield Collections.emptyList();
            }
        };
    }

    public BookSnapshot snapshot() {
        return orderBook.getSnapshot();
    }

    OrderBook orderBook() {
        return orderBook;
    }

    private List<Fill> processNewOrder(EngineEvent event) {
        Order order = orderPool.acquire();
        copyNewOrderFields(event, order);

        List<Fill> fills = switch (Objects.requireNonNull(order.type, "event.orderType")) {
            case LIMIT -> orderBook.processLimit(order);
            case MARKET -> orderBook.processMarket(order);
            case IOC -> orderBook.processIOC(order);
        };

        if (order.owningNode == null) {
            orderPool.release(order);
        }

        return fills;
    }

    private void copyNewOrderFields(EngineEvent event, Order order) {
        order.orderId        = event.orderId;
        order.clientOrderId  = event.clientOrderId;
        order.side           = event.side;
        order.type           = event.orderType;
        order.priceTicks     = event.priceTicks;
        order.originalQty    = event.qty;
        order.remainingQty   = event.qty;
        order.status         = OrderStatus.PENDING;
        order.timestampNanos = event.timestampNanos;
    }

    private void assertInstrument(long eventInstrumentId) {
        if (eventInstrumentId != instrumentId) {
            throw new IllegalStateException("Engine event instrumentId does not match the configured instrumentId");
        }
    }
}
