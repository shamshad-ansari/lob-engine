package io.github.shamshadansari.lobengine.book;

import io.github.shamshadansari.lobengine.BookSnapshot;
import io.github.shamshadansari.lobengine.domain.BookLevel;
import io.github.shamshadansari.lobengine.domain.BookUpdate;
import io.github.shamshadansari.lobengine.domain.Fill;
import io.github.shamshadansari.lobengine.domain.Order;
import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderStatus;
import io.github.shamshadansari.lobengine.domain.OrderType;
import io.github.shamshadansari.lobengine.marketdata.MarketDataPublisher;
import io.github.shamshadansari.lobengine.metrics.EngineMetrics;
import io.github.shamshadansari.lobengine.pool.FillPool;
import io.github.shamshadansari.lobengine.pool.OrderPool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

public final class OrderBook {

    private final long                      instrumentId;
    private final TreeMap<Long, PriceLevel> bids;       // descending: best bid first
    private final TreeMap<Long, PriceLevel> asks;       // ascending:  best ask first
    private final HashMap<Long, Order>      orderIndex;
    private final EngineMetrics             metrics;
    private final OrderPool                 orderPool;
    private final FillPool                  fillPool;
    private final MarketDataPublisher       publisher;

    private long bestBidTicks = Long.MIN_VALUE;
    private long bestAskTicks = Long.MAX_VALUE;

    private long snapshotSequence = 0;

    private long nextFillId     = 1;
    // TODO: Replace with a dedicated ID allocator to prevent namespace collision with client order IDs.
    private long nextInternalId = 1_000_000_000L;

    public OrderBook(long instrumentId, EngineMetrics metrics) {
        this(instrumentId, metrics, new MarketDataPublisher());
    }

    public OrderBook(long instrumentId, EngineMetrics metrics, MarketDataPublisher publisher) {
        this(instrumentId, metrics, new OrderPool(10_000), new FillPool(50_000), publisher);
    }

    public OrderBook(long instrumentId, EngineMetrics metrics, OrderPool orderPool, FillPool fillPool) {
        this(instrumentId, metrics, orderPool, fillPool, new MarketDataPublisher());
    }

    public OrderBook(long instrumentId,
                     EngineMetrics metrics,
                     OrderPool orderPool,
                     FillPool fillPool,
                     MarketDataPublisher publisher) {
        this.instrumentId = instrumentId;
        this.orderPool    = Objects.requireNonNull(orderPool, "orderPool");
        this.fillPool     = Objects.requireNonNull(fillPool, "fillPool");
        this.bids         = new TreeMap<>(Comparator.reverseOrder());
        this.asks         = new TreeMap<>();
        this.orderIndex   = new HashMap<>(500_000);
        this.metrics      = Objects.requireNonNull(metrics, "metrics");
        this.publisher    = Objects.requireNonNull(publisher, "publisher");
    }

    // -------------------------------------------------------------------------
    // Best-price accessors
    // -------------------------------------------------------------------------

    public long    bestBid()  { return bestBidTicks; }
    public long    bestAsk()  { return bestAskTicks; }
    public boolean hasBids()  { return bestBidTicks != Long.MIN_VALUE; }
    public boolean hasAsks()  { return bestAskTicks != Long.MAX_VALUE; }

    // -------------------------------------------------------------------------
    // Structural mutations
    // -------------------------------------------------------------------------

    public void addToBook(Order order) {
        TreeMap<Long, PriceLevel> side = sideMap(order.side);
        PriceLevel level = side.computeIfAbsent(order.priceTicks, PriceLevel::new);
        level.enqueue(order);                   // Sets order.owningNode for O(1) cancel via node reference.
        orderIndex.put(order.orderId, order);
        updateCacheForSide(order.side);
        publishBookUpdate(BookUpdate.Type.ADD, order.side, order.priceTicks, level.totalVolume());
    }

    // O(1) HashMap lookup, O(log N) TreeMap lookup for the PriceLevel, O(1) DLL splice-out via owningNode.
    // The O(log N) TreeMap.remove() fires only when the level empties. To eliminate the per-cancel
    // O(log N) PriceLevel lookup entirely, store a PriceLevel reference on Order alongside owningNode.
    public boolean cancelOrder(long orderId) {
        Order order = orderIndex.remove(orderId);
        if (order == null) {
            metrics.recordCancelMiss();
            return false;
        }

        PriceLevel level = sideMap(order.side).get(order.priceTicks);
        if (level != null) {
            level.removeOrder(order);           // O(1) via owningNode; nulls owningNode
            long newVolumeAtLevel = level.isEmpty() ? -1L : level.totalVolume();
            if (level.isEmpty()) {
                sideMap(order.side).remove(order.priceTicks);
                updateCacheForSide(order.side);
            }
            publishBookUpdate(BookUpdate.Type.CANCEL, order.side, order.priceTicks, newVolumeAtLevel);
        }

        order.status = OrderStatus.CANCELLED;
        orderPool.release(order);
        metrics.recordCancel();
        return true;
    }

    // -------------------------------------------------------------------------
    // Matching
    // -------------------------------------------------------------------------

    public List<Fill> processLimit(Order incoming) {
        List<Fill> fills = new ArrayList<>();
        metrics.recordOrderProcessed();
        aggressiveMatch(incoming, fills);
        if (incoming.remainingQty > 0) addToBook(incoming);
        return fills;
    }

    public List<Fill> processMarket(Order incoming) {
        List<Fill> fills = new ArrayList<>();
        metrics.recordOrderProcessed();

        while (incoming.remainingQty > 0) {
            long bestPrice = (incoming.side == OrderSide.BID) ? bestAskTicks : bestBidTicks;
            boolean hasLiquidity = (incoming.side == OrderSide.BID)
                ? bestPrice != Long.MAX_VALUE
                : bestPrice != Long.MIN_VALUE;
            if (!hasLiquidity) break;

            TreeMap<Long, PriceLevel> opposite = (incoming.side == OrderSide.BID) ? asks : bids;
            PriceLevel level = Objects.requireNonNull(opposite.get(bestPrice),
                    "cache desync: no level at " + bestPrice);
            matchAgainstLevel(incoming, level, fills);

            if (level.isEmpty()) {
                opposite.remove(bestPrice);
                if (incoming.side == OrderSide.BID) updateBestAskCache();
                else                                 updateBestBidCache();
                OrderSide restingSide = incoming.side == OrderSide.BID ? OrderSide.ASK : OrderSide.BID;
                publishBookUpdate(BookUpdate.Type.TRADE, restingSide, bestPrice, -1L);
            }
        }

        if (incoming.remainingQty > 0) {
            incoming.status = fills.isEmpty() ? OrderStatus.CANCELLED : OrderStatus.PARTIALLY_FILLED;
        }
        return fills;
    }

    public List<Fill> processIOC(Order incoming) {
        List<Fill> fills = new ArrayList<>();
        metrics.recordOrderProcessed();
        aggressiveMatch(incoming, fills);
        if (incoming.remainingQty > 0) {
            incoming.status = fills.isEmpty() ? OrderStatus.CANCELLED : OrderStatus.PARTIALLY_FILLED;
        }
        return fills;
    }

    /**
     * Modify an existing resting order.
     * <ul>
     *   <li>Same price, qty-down or qty-unchanged: mutated in place; queue position preserved.</li>
     *   <li>Price change or qty-up: cancel-and-resubmit (loses queue priority).</li>
     * </ul>
     *
     * <p><strong>Identity contract:</strong> If cancel-and-resubmit is triggered, the original
     * {@code orderId} is removed from the book. The returned replacement {@link Order} carries a new
     * internal {@code orderId}; callers must use that ID for all subsequent operations (cancel, modify).
     * </p>
     *
     * @return the active {@link Order} after modification (same object for in-place, new object
     *         for cancel-and-resubmit), or {@code null} if {@code orderId} is not in the book.
     */
    public Order processModify(long orderId, long newPriceTicks, long newQty) {
        metrics.recordOrderProcessed();

        Order existing = orderIndex.get(orderId);
        if (existing == null) return null;

        long effectivePriceTicks = newPriceTicks == -1L ? existing.priceTicks : newPriceTicks;
        long effectiveQty        = newQty == -1L ? existing.remainingQty : newQty;
        boolean priceUnchanged   = (effectivePriceTicks == existing.priceTicks);

        if (priceUnchanged && effectiveQty <= existing.remainingQty) {
            // Same price, qty unchanged or reduced: mutate in place to preserve queue priority.
            if (effectiveQty < existing.remainingQty) {
                long delta = effectiveQty - existing.remainingQty;  // negative
                PriceLevel level = sideMap(existing.side).get(existing.priceTicks);
                if (level != null) {
                    level.adjustVolume(delta);
                    publishBookUpdate(BookUpdate.Type.MODIFY,
                                      existing.side,
                                      existing.priceTicks,
                                      level.totalVolume());
                }
                existing.remainingQty = effectiveQty;
            }
            return existing;
        }

        // Cancel-and-resubmit: price change or qty increase
        String    savedClientOrderId = existing.clientOrderId;
        OrderSide savedSide          = existing.side;
        OrderType savedType          = existing.type;
        Order     replacement        = orderPool.acquire();
        cancelOrder(orderId);

        replacement.orderId        = nextInternalId++;
        replacement.clientOrderId  = savedClientOrderId;
        replacement.side           = savedSide;
        replacement.type           = savedType;
        replacement.priceTicks     = effectivePriceTicks;
        replacement.originalQty    = effectiveQty;
        replacement.remainingQty   = effectiveQty;
        replacement.status         = OrderStatus.PENDING;
        replacement.timestampNanos = System.nanoTime();
        addToBook(replacement);
        return replacement;
    }

    // -------------------------------------------------------------------------
    // Snapshot
    // -------------------------------------------------------------------------

    public BookSnapshot getSnapshot() {
        List<BookLevel> bidLevels = new ArrayList<>(bids.size());
        for (PriceLevel p : bids.values())
            bidLevels.add(new BookLevel(p.priceTicks, p.totalVolume(), p.size()));

        List<BookLevel> askLevels = new ArrayList<>(asks.size());
        for (PriceLevel p : asks.values())
            askLevels.add(new BookLevel(p.priceTicks, p.totalVolume(), p.size()));

        return new BookSnapshot(instrumentId, System.nanoTime(),
                                ++snapshotSequence, bidLevels, askLevels);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Shared aggressive-match loop used by {@link #processLimit} and {@link #processIOC}.
     * Walks the opposite side in price-time priority order until the incoming order is fully
     * filled or it no longer crosses the spread.
     */
    private void aggressiveMatch(Order incoming, List<Fill> fills) {
        while (incoming.remainingQty > 0) {
            long bestOpposite = (incoming.side == OrderSide.BID) ? bestAskTicks : bestBidTicks;
            boolean crosses = (incoming.side == OrderSide.BID)
                ? (bestOpposite != Long.MAX_VALUE && incoming.priceTicks >= bestOpposite)
                : (bestOpposite != Long.MIN_VALUE && incoming.priceTicks <= bestOpposite);
            if (!crosses) break;

            TreeMap<Long, PriceLevel> opposite = (incoming.side == OrderSide.BID) ? asks : bids;
            PriceLevel level = Objects.requireNonNull(opposite.get(bestOpposite),
                    "cache desync: no level at " + bestOpposite);
            matchAgainstLevel(incoming, level, fills);

            if (level.isEmpty()) {
                opposite.remove(bestOpposite);
                if (incoming.side == OrderSide.BID) updateBestAskCache();
                else                                 updateBestBidCache();
                OrderSide restingSide = incoming.side == OrderSide.BID ? OrderSide.ASK : OrderSide.BID;
                publishBookUpdate(BookUpdate.Type.TRADE, restingSide, bestOpposite, -1L);
            }
        }
    }

    private void matchAgainstLevel(Order incoming, PriceLevel level, List<Fill> fills) {
        while (incoming.remainingQty > 0 && !level.isEmpty()) {
            Order resting   = level.peekFront();
            long  fillQty   = Math.min(incoming.remainingQty, resting.remainingQty);
            long  fillPrice = resting.priceTicks;
            OrderSide restingSide = resting.side;

            Fill fill = fillPool.acquire();
            fill.fillId          = nextFillId++;
            fill.instrumentId    = instrumentId;
            fill.fillPriceTicks  = fillPrice;
            fill.fillQty         = fillQty;
            fill.timestampNanos  = System.nanoTime();
            fill.buyWasAggressor = (incoming.side == OrderSide.BID);
            fill.buyOrderId      = (incoming.side == OrderSide.BID) ? incoming.orderId : resting.orderId;
            fill.sellOrderId     = (incoming.side == OrderSide.ASK) ? incoming.orderId : resting.orderId;

            incoming.remainingQty -= fillQty;
            resting.remainingQty  -= fillQty;
            level.adjustVolume(-fillQty);   // Volume must be decremented before dequeueFront; dequeueFront performs structural removal only.

            if (resting.remainingQty == 0) {
                level.dequeueFront();               // nulls resting.owningNode; does NOT adjust volume
                orderIndex.remove(resting.orderId);
                resting.status = OrderStatus.FILLED;
                orderPool.release(resting);
            } else {
                resting.status = OrderStatus.PARTIALLY_FILLED;
            }

            incoming.status = (incoming.remainingQty == 0)
                ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;

            fills.add(fill);
            publisher.publishFill(fill);
            if (!level.isEmpty()) {
                publishBookUpdate(BookUpdate.Type.TRADE, restingSide, fillPrice, level.totalVolume());
            }
            metrics.recordFills(1);
        }
    }

    private void publishBookUpdate(BookUpdate.Type type,
                                   OrderSide side,
                                   long priceTicks,
                                   long newVolumeAtLevel) {
        publisher.publishBookUpdate(type, instrumentId, side, priceTicks, newVolumeAtLevel);
    }

    private TreeMap<Long, PriceLevel> sideMap(OrderSide side) {
        return side == OrderSide.BID ? bids : asks;
    }

    private void updateCacheForSide(OrderSide side) {
        if (side == OrderSide.BID) updateBestBidCache();
        else                        updateBestAskCache();
    }

    private void updateBestBidCache() {
        bestBidTicks = bids.isEmpty() ? Long.MIN_VALUE : bids.firstKey();
    }

    private void updateBestAskCache() {
        bestAskTicks = asks.isEmpty() ? Long.MAX_VALUE : asks.firstKey();
    }
}
