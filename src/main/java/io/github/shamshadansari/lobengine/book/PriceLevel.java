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
        o.owningNode  = queue.addLast(o);   // Retains the node reference for O(1) cancellation.
        totalVolume  += o.remainingQty;
    }

    public Order peekFront() {
        return queue.peekFirst();
    }

    /**
     * Pure structural removal used during matching. The caller must invoke
     * {@link #adjustVolume} before calling this method to keep {@code totalVolume} consistent.
     */
    public Order dequeueFront() {
        Order o = queue.pollFirst();
        if (o != null) {
            o.owningNode = null;            // Clears the stale node reference after removal.
        }
        return o;
    }

    /**
     * Cancels a resting order in O(1) using its stored node reference, then decrements
     * {@code totalVolume} by the order's remaining quantity.
     */
    public boolean removeOrder(Order o) {
        if (o.owningNode == null) return false;
        queue.removeNode(o.owningNode);
        totalVolume  -= o.remainingQty;
        o.owningNode  = null;               // Clears the node reference after removal.
        return true;
    }

    public boolean isEmpty()     { return queue.isEmpty(); }
    public int     size()        { return queue.size(); }
    public long    totalVolume() { return totalVolume; }

    /**
     * Adjusts the cached total volume by {@code delta}. Must be called by the matching engine
     * whenever a resting order is partially or fully filled to keep {@code totalVolume} consistent
     * with the sum of all remaining quantities.
     */
    public void adjustVolume(long delta) { totalVolume += delta; }

    /** Returns an iterable view of the queue in FIFO order for snapshot construction. */
    public Iterable<Order> queueView() { return queue; }
}
