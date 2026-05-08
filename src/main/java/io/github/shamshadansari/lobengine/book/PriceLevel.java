package io.github.shamshadansari.lobengine.book;

import io.github.shamshadansari.lobengine.domain.Order;
import io.github.shamshadansari.lobengine.util.DoublyLinkedList;

public final class PriceLevel {

    public final long                     priceTicks;
    private final DoublyLinkedList<Order> queue;
    private long                          totalVolume;

    public PriceLevel(long priceTicks) {
        this.priceTicks  = priceTicks;
        this.queue       = new DoublyLinkedList<>();
        this.totalVolume = 0;
    }

    public void enqueue(Order o) {
        o.owningNode  = queue.addLast(o);   // caller stores the node on the order
        totalVolume  += o.remainingQty;
    }

    public Order peekFront() {
        return queue.peekFirst();
    }

    // Used during matching — nulls owningNode to satisfy the invariant
    public Order dequeueFront() {
        Order o = queue.pollFirst();
        if (o != null) {
            totalVolume  -= o.remainingQty;
            o.owningNode  = null;           // prevents stale-node cancel
        }
        return o;
    }

    // Used during cancellation — O(1) via the stored node reference
    public boolean removeOrder(Order o) {
        if (o.owningNode == null) return false;
        queue.removeNode(o.owningNode);
        totalVolume  -= o.remainingQty;
        o.owningNode  = null;               // null after splice-out
        return true;
    }

    public boolean isEmpty()     { return queue.isEmpty(); }
    public int     size()        { return queue.size(); }
    public long    totalVolume() { return totalVolume; }

    // Called by the matching engine when it partially fills a resting order
    // so that the level's cached volume stays consistent with remainingQty.
    public void adjustVolume(long delta) { totalVolume += delta; }

    // For snapshot construction
    public Iterable<Order> queueView() { return queue; }
}
