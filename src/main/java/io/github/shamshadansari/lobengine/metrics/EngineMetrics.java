package io.github.shamshadansari.lobengine.metrics;

public class EngineMetrics {

    private long ordersProcessed;
    private long fillsGenerated;
    private long cancellations;
    private long cancelMisses;

    public void recordOrderProcessed()  { ordersProcessed++; }
    public void recordFills(int n)      { fillsGenerated += n; }
    public void recordCancel()          { cancellations++; }
    public void recordCancelMiss()      { cancelMisses++; }

    public long ordersProcessed() { return ordersProcessed; }
    public long fillsGenerated()  { return fillsGenerated; }
    public long cancellations()   { return cancellations; }
    public long cancelMisses()    { return cancelMisses; }

    public void reset() {
        ordersProcessed = 0;
        fillsGenerated  = 0;
        cancellations   = 0;
        cancelMisses    = 0;
    }
}
