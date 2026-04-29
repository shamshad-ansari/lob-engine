package io.github.shamshadansari.lobengine.naive;

import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public class NaiveOrderBook {

    private final List<NaiveOrder> bids = new ArrayList<>();
    private final List<NaiveOrder> asks = new ArrayList<>();
    private long nextFillId = 1;

    // Bids: highest price first, then earliest time first at the same price
    private static final Comparator<NaiveOrder> BID_ORDER =
            Comparator.comparingLong((NaiveOrder o) -> o.priceTicks).reversed()
                      .thenComparingLong(o -> o.timestampNanos);

    // Asks: lowest price first, then earliest time first at the same price
    private static final Comparator<NaiveOrder> ASK_ORDER =
            Comparator.comparingLong((NaiveOrder o) -> o.priceTicks)
                      .thenComparingLong(o -> o.timestampNanos);

    public List<NaiveFill> processLimit(NaiveOrder incoming) {
        List<NaiveFill> fills = new ArrayList<>();

        if (incoming.side == OrderSide.BID) {
            matchAgainst(incoming, asks, fills, true);
            if (incoming.remainingQty > 0) {
                if (fills.isEmpty()) {
                 incoming.status = OrderStatus.PENDING; 
                }
                bids.add(incoming);
                bids.sort(BID_ORDER);
            }
        } else {
            matchAgainst(incoming, bids, fills, true);
            if (incoming.remainingQty > 0) {
                if (fills.isEmpty()) {
                    incoming.status = OrderStatus.PENDING;
                }
                asks.add(incoming);
                asks.sort(ASK_ORDER);
            }
        }

        return fills;
    }

    public List<NaiveFill> processMarket(NaiveOrder incoming) {
        List<NaiveFill> fills = new ArrayList<>();

        if (incoming.side == OrderSide.BID) {
            matchAgainst(incoming, asks, fills, false);
        } else {
            matchAgainst(incoming, bids, fills, false);
        }

        if (incoming.remainingQty > 0) {
            incoming.status = OrderStatus.CANCELLED;
        }

        return fills;
    }

    public List<NaiveFill> processIOC(NaiveOrder incoming) {
        List<NaiveFill> fills = new ArrayList<>();

        if (incoming.side == OrderSide.BID) {
            matchAgainst(incoming, asks, fills, true);
        } else {
            matchAgainst(incoming, bids, fills, true);
        }

        if (incoming.remainingQty > 0) {
            incoming.status = OrderStatus.CANCELLED;
        }

        return fills;
    }

    public boolean cancel(long orderId) {
        if (removeById(bids, orderId)) return true;
        if (removeById(asks, orderId)) return true;
        return false;
    }

    // ---------------------------------------------------------------------------
    // Shared matching loop used by limit, market, and IOC
    // ---------------------------------------------------------------------------

    private void matchAgainst(NaiveOrder incoming,
                               List<NaiveOrder> opposite,
                               List<NaiveFill> fills,
                               boolean priceCheck) {

        Iterator<NaiveOrder> it = opposite.iterator();

        while (it.hasNext() && incoming.remainingQty > 0) {
            NaiveOrder resting = it.next();

            if (priceCheck && !crosses(incoming, resting)) {
                // Because the list is sorted best-first, no later order can cross either
                break;
            }

            long tradedQty = Math.min(incoming.remainingQty, resting.remainingQty);

            NaiveFill fill = new NaiveFill();
            fill.fillId          = nextFillId++;
            fill.aggressorOrderId = incoming.orderId;
            fill.passiveOrderId  = resting.orderId;
            fill.priceTicks      = resting.priceTicks;   // passive side sets the price
            fill.qty             = tradedQty;
            fills.add(fill);

            incoming.remainingQty -= tradedQty;
            resting.remainingQty  -= tradedQty;

            if (incoming.remainingQty == 0) {
                incoming.status = OrderStatus.FILLED;
            } else {
                incoming.status = OrderStatus.PARTIALLY_FILLED;
            }

            if (resting.remainingQty == 0) {
                resting.status = OrderStatus.FILLED;
                it.remove();
            } else {
                resting.status = OrderStatus.PARTIALLY_FILLED;
            }
        }
    }

    // Returns true when the incoming order's price is willing to trade with the resting order
    private boolean crosses(NaiveOrder incoming, NaiveOrder resting) {
        if (incoming.side == OrderSide.BID) {
            return resting.priceTicks <= incoming.priceTicks;
        } else {
            return resting.priceTicks >= incoming.priceTicks;
        }
    }

    private boolean removeById(List<NaiveOrder> list, long orderId) {
        Iterator<NaiveOrder> it = list.iterator();
        while (it.hasNext()) {
            if (it.next().orderId == orderId) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------
    // Read-only accessors for tests
    // ---------------------------------------------------------------------------

    public List<NaiveOrder> getBids() {
        return List.copyOf(bids);
    }

    public List<NaiveOrder> getAsks() {
        return List.copyOf(asks);
    }
}
