package io.github.shamshadansari.lobengine.metrics;

import java.util.concurrent.atomic.AtomicLong;

public class EngineMetrics {

    private final AtomicLong ordersProcessed = new AtomicLong();
    private final AtomicLong fillsGenerated  = new AtomicLong();
    private final AtomicLong cancellations   = new AtomicLong();
    private final AtomicLong cancelMisses    = new AtomicLong();

    public void recordOrderProcessed()  { ordersProcessed.incrementAndGet(); }
    public void recordFills(int n)      { fillsGenerated.addAndGet(n); }
    public void recordCancel()          { cancellations.incrementAndGet(); }
    public void recordCancelMiss()      { cancelMisses.incrementAndGet(); }

    public long ordersProcessed() { return ordersProcessed.get(); }
    public long fillsGenerated()  { return fillsGenerated.get(); }
    public long cancellations()   { return cancellations.get(); }
    public long cancelMisses()    { return cancelMisses.get(); }

    public void reset() {
        ordersProcessed.set(0);
        fillsGenerated.set(0);
        cancellations.set(0);
        cancelMisses.set(0);
    }
}
