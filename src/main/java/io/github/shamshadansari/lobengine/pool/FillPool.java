package io.github.shamshadansari.lobengine.pool;

import io.github.shamshadansari.lobengine.domain.Fill;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

public final class FillPool {

    private final ArrayDeque<Fill> pool;
    private final int              capacity;
    private final AtomicLong       misses = new AtomicLong(0);

    public FillPool(int capacity) {
        this.capacity = capacity;
        this.pool     = new ArrayDeque<>(capacity);
        for (int i = 0; i < capacity; i++) {
            Fill fill = new Fill();
            fill.reset();
            pool.push(fill);
        }
    }

    public Fill acquire() {
        Fill fill = pool.poll();
        if (fill == null) {
            misses.incrementAndGet();
            Fill fresh = new Fill();
            fresh.reset();
            return fresh;
        }
        return fill;
    }

    public void release(Fill fill) {
        fill.reset();
        if (pool.size() < capacity) {
            pool.push(fill);
        }
    }

    public int available() {
        return pool.size();
    }

    public long misses() {
        return misses.get();
    }
}
