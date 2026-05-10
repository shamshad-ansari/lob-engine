package io.github.shamshadansari.lobengine.benchmarks;

import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class MarketMicrostructureSimulator {

    public static final long DEFAULT_INSTRUMENT_ID = 1L;
    public static final long DEFAULT_MID_TICKS = 5_000L;

    private MarketMicrostructureSimulator() {
    }

    public static Workload generate(int events, long seed) {
        Random rng = new Random(seed);
        ArrayList<LiveOrder> liveOrders = new ArrayList<>(events / 2);
        EventSpec[] specs = new EventSpec[events];
        Summary summary = new Summary(seed);
        long nextOrderId = 1L;

        for (int i = 0; i < events; i++) {
            EventSpec spec;
            int roll = rng.nextInt(100);

            if (liveOrders.size() < 1_000 || roll < 55) {
                spec = newRestingOrder(nextOrderId++, i, rng);
                liveOrders.add(new LiveOrder(spec.orderId, spec.side, spec.priceTicks, spec.qty));
                summary.newOrders++;
                summary.restingAdds++;
            } else if (roll < 70) {
                spec = newAggressiveOrder(nextOrderId++, i, rng, liveOrders);
                consumeOneOppositeOrder(spec.side, liveOrders, summary);
                summary.newOrders++;
                summary.aggressiveOrders++;
            } else if (roll < 90) {
                spec = cancelHit(i, rng, liveOrders);
                summary.cancelHits++;
            } else if (roll < 95) {
                spec = modifyInPlace(i, rng, liveOrders);
                summary.modifyInPlace++;
            } else {
                spec = cancelMiss(i, nextOrderId + i);
                summary.cancelMisses++;
            }

            specs[i] = spec;
        }

        summary.finalLiveOrders = liveOrders.size();
        return new Workload(specs, summary);
    }

    private static EventSpec newRestingOrder(long orderId, int sequence, Random rng) {
        OrderSide side = rng.nextBoolean() ? OrderSide.BID : OrderSide.ASK;
        long passiveOffset = 5L + rng.nextLong(10L);
        long priceTicks = side == OrderSide.BID
            ? DEFAULT_MID_TICKS - passiveOffset
            : DEFAULT_MID_TICKS + passiveOffset;

        EventSpec spec = new EventSpec();
        spec.kind = EventSpec.Kind.NEW;
        spec.orderId = orderId;
        spec.side = side;
        spec.type = OrderType.LIMIT;
        spec.priceTicks = priceTicks;
        spec.qty = 100L;
        spec.timestampNanos = sequence;
        return spec;
    }

    private static EventSpec newAggressiveOrder(long orderId,
                                                int sequence,
                                                Random rng,
                                                List<LiveOrder> liveOrders) {
        OrderSide side = chooseSideWithOppositeLiquidity(rng, liveOrders);
        boolean market = rng.nextBoolean();

        EventSpec spec = new EventSpec();
        spec.kind = EventSpec.Kind.NEW;
        spec.orderId = orderId;
        spec.side = side;
        spec.type = market ? OrderType.MARKET : OrderType.LIMIT;
        spec.priceTicks = side == OrderSide.BID ? DEFAULT_MID_TICKS + 100L : DEFAULT_MID_TICKS - 100L;
        spec.qty = 100L;
        spec.timestampNanos = sequence;
        return spec;
    }

    private static OrderSide chooseSideWithOppositeLiquidity(Random rng, List<LiveOrder> liveOrders) {
        boolean hasBid = false;
        boolean hasAsk = false;
        for (LiveOrder order : liveOrders) {
            if (order.side == OrderSide.BID) {
                hasBid = true;
            } else {
                hasAsk = true;
            }
            if (hasBid && hasAsk) {
                break;
            }
        }
        if (hasAsk && hasBid) {
            return rng.nextBoolean() ? OrderSide.BID : OrderSide.ASK;
        }
        if (hasAsk) {
            return OrderSide.BID;
        }
        return OrderSide.ASK;
    }

    private static void consumeOneOppositeOrder(OrderSide aggressorSide,
                                                ArrayList<LiveOrder> liveOrders,
                                                Summary summary) {
        OrderSide restingSide = aggressorSide == OrderSide.BID ? OrderSide.ASK : OrderSide.BID;
        int bestIndex = -1;
        long bestPrice = restingSide == OrderSide.ASK ? Long.MAX_VALUE : Long.MIN_VALUE;

        for (int i = 0; i < liveOrders.size(); i++) {
            LiveOrder order = liveOrders.get(i);
            if (order.side != restingSide) {
                continue;
            }
            boolean better = restingSide == OrderSide.ASK
                ? order.priceTicks < bestPrice
                : order.priceTicks > bestPrice;
            if (better) {
                bestPrice = order.priceTicks;
                bestIndex = i;
            }
        }

        if (bestIndex >= 0) {
            removeAt(liveOrders, bestIndex);
            summary.expectedFills++;
        }
    }

    private static EventSpec cancelHit(int sequence, Random rng, ArrayList<LiveOrder> liveOrders) {
        int index = rng.nextInt(liveOrders.size());
        LiveOrder target = removeAt(liveOrders, index);

        EventSpec spec = new EventSpec();
        spec.kind = EventSpec.Kind.CANCEL;
        spec.targetOrderId = target.orderId;
        spec.timestampNanos = sequence;
        return spec;
    }

    private static EventSpec modifyInPlace(int sequence, Random rng, ArrayList<LiveOrder> liveOrders) {
        LiveOrder target = liveOrders.get(rng.nextInt(liveOrders.size()));
        long newQty = Math.max(1L, target.qty - (1L + rng.nextLong(25L)));
        target.qty = newQty;

        EventSpec spec = new EventSpec();
        spec.kind = EventSpec.Kind.MODIFY;
        spec.targetOrderId = target.orderId;
        spec.newPriceTicks = -1L;
        spec.newQty = newQty;
        spec.timestampNanos = sequence;
        return spec;
    }

    private static EventSpec cancelMiss(int sequence, long missingOrderId) {
        EventSpec spec = new EventSpec();
        spec.kind = EventSpec.Kind.CANCEL;
        spec.targetOrderId = missingOrderId;
        spec.timestampNanos = sequence;
        return spec;
    }

    private static LiveOrder removeAt(ArrayList<LiveOrder> liveOrders, int index) {
        LiveOrder removed = liveOrders.get(index);
        int last = liveOrders.size() - 1;
        if (index != last) {
            liveOrders.set(index, liveOrders.get(last));
        }
        liveOrders.remove(last);
        return removed;
    }

    public static final class EventSpec {
        public enum Kind { NEW, CANCEL, MODIFY }

        public Kind      kind;
        public long      instrumentId = DEFAULT_INSTRUMENT_ID;
        public long      orderId;
        public long      targetOrderId;
        public OrderSide side;
        public OrderType type;
        public long      priceTicks;
        public long      qty;
        public long      timestampNanos;
        public long      newPriceTicks = -1L;
        public long      newQty = -1L;
    }

    public record Workload(EventSpec[] events, Summary summary) {
    }

    public static final class Summary {
        public final long seed;
        public long newOrders;
        public long restingAdds;
        public long aggressiveOrders;
        public long cancelHits;
        public long cancelMisses;
        public long modifyInPlace;
        public long expectedFills;
        public long finalLiveOrders;

        private Summary(long seed) {
            this.seed = seed;
        }

        public String compact() {
            return "seed=" + seed
                + ", newOrders=" + newOrders
                + ", restingAdds=" + restingAdds
                + ", aggressiveOrders=" + aggressiveOrders
                + ", cancelHits=" + cancelHits
                + ", cancelMisses=" + cancelMisses
                + ", modifyInPlace=" + modifyInPlace
                + ", expectedFills=" + expectedFills
                + ", finalLiveOrders=" + finalLiveOrders;
        }
    }

    private static final class LiveOrder {
        private final long orderId;
        private final OrderSide side;
        private final long priceTicks;
        private long qty;

        private LiveOrder(long orderId, OrderSide side, long priceTicks, long qty) {
            this.orderId = orderId;
            this.side = side;
            this.priceTicks = priceTicks;
            this.qty = qty;
        }
    }
}
