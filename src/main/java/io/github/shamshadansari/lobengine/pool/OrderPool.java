package io.github.shamshadansari.lobengine.pool;

import io.github.shamshadansari.lobengine.domain.Order;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

public final class OrderPool {

    private final ArrayDeque<Order> pool;
    private final int               capacity;
    private final AtomicLong        misses = new AtomicLong(0);

    public OrderPool(int capacity) {
        this.capacity = capacity;
        this.pool     = new ArrayDeque<>(capacity);
        for (int i = 0; i < capacity; i++) {
            Order order = new Order();
            order.reset();
            pool.push(order);
        }
    }

    /** Returns a reset Order with owningNode guaranteed to be null. */
    public Order acquire() {
        Order order = pool.poll();
        if (order == null) {
            misses.incrementAndGet();
            Order fresh = new Order();
            fresh.reset();
            return fresh;
        }
        return order;
    }

    /**
     * Returns an Order to the pool. The order must already be removed from the
     * book's order index before release; otherwise, the index can retain a reset object.
     */
    public void release(Order order) {
        order.reset();
        if (pool.size() < capacity) {
            pool.push(order);
        }
    }

    public int available() {
        return pool.size();
    }

    public long misses() {
        return misses.get();
    }
}
