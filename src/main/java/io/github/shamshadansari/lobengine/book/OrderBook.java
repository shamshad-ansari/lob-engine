package io.github.shamshadansari.lobengine.book;

import io.github.shamshadansari.lobengine.BookSnapshot;
import io.github.shamshadansari.lobengine.domain.BookLevel;
import io.github.shamshadansari.lobengine.domain.Fill;
import io.github.shamshadansari.lobengine.domain.Order;
import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderStatus;
import io.github.shamshadansari.lobengine.metrics.EngineMetrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;

public final class OrderBook {

    private final long                      instrumentId;
    private final TreeMap<Long, PriceLevel> bids;       // descending: best bid first
    private final TreeMap<Long, PriceLevel> asks;       // ascending:  best ask first
    private final HashMap<Long, Order>      orderIndex;
    private final EngineMetrics             metrics;

    private long bestBidTicks = Long.MIN_VALUE;
    private long bestAskTicks = Long.MAX_VALUE;

    private long snapshotSequence = 0;

    public OrderBook(long instrumentId, EngineMetrics metrics) {
        this.instrumentId = instrumentId;
        this.bids         = new TreeMap<>(Comparator.reverseOrder());
        this.asks         = new TreeMap<>();
        this.orderIndex   = new HashMap<>(500_000);
        this.metrics      = metrics;
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
        level.enqueue(order);                   // sets order.owningNode
        orderIndex.put(order.orderId, order);
        updateCacheForSide(order.side);
    }

    // O(1) lookup via HashMap + O(1) queue removal via owningNode splice-out.
    // O(log N) only when the level empties and must be evicted from the TreeMap.
    public boolean cancelOrder(long orderId) {
        Order order = orderIndex.remove(orderId);
        if (order == null) {
            metrics.recordCancelMiss();
            return false;
        }

        PriceLevel level = sideMap(order.side).get(order.priceTicks);
        if (level != null) {
            level.removeOrder(order);           // O(1) via owningNode; nulls owningNode
            if (level.isEmpty()) {
                sideMap(order.side).remove(order.priceTicks);
                updateCacheForSide(order.side);
            }
        }

        order.status = OrderStatus.CANCELLED;
        metrics.recordCancel();
        return true;
    }

    // -------------------------------------------------------------------------
    // Matching stubs — implemented in Phase 3
    // -------------------------------------------------------------------------

    public List<Fill> processLimit(Order incoming)  { return Collections.emptyList(); }
    public List<Fill> processMarket(Order incoming) { return Collections.emptyList(); }
    public List<Fill> processIOC(Order incoming)    { return Collections.emptyList(); }

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
