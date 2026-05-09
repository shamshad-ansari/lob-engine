package io.github.shamshadansari.lobengine.engine;

import io.github.shamshadansari.lobengine.BookSnapshot;
import io.github.shamshadansari.lobengine.domain.EngineEvent;
import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderType;
import io.github.shamshadansari.lobengine.metrics.EngineMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineEventHandlerTest {

    private static final long INSTRUMENT_ID = 1L;

    private MatchingEngine engine;
    private EngineEventHandler handler;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine(INSTRUMENT_ID, new EngineMetrics());
        handler = new EngineEventHandler(engine);
    }

    @Test
    void handlerProcessesEventAndResetsSlot() {
        EngineEvent event = newLimitOrder(101L, 5000, 100);

        handler.onEvent(event, 0L, true);

        BookSnapshot snapshot = engine.snapshot();
        assertThat(snapshot.asks).hasSize(1);
        assertThat(snapshot.asks.get(0).priceTicks).isEqualTo(5000L);
        assertThat(snapshot.asks.get(0).totalVolume).isEqualTo(100L);

        assertReset(event);
    }

    @Test
    void handlerResetRestoresModifySentinels() {
        EngineEvent event = new EngineEvent();
        event.type = EngineEvent.Type.MODIFY_ORDER;
        event.instrumentId = INSTRUMENT_ID;
        event.timestampNanos = 1_000L;
        event.targetOrderId = 101L;
        event.newPriceTicks = 5001L;
        event.newQty = 20L;

        handler.onEvent(event, 1L, true);

        assertThat(event.newPriceTicks).isEqualTo(-1L);
        assertThat(event.newQty).isEqualTo(-1L);
    }

    @Test
    void handlerResetsSlotWhenProcessingFails() {
        EngineEvent event = newLimitOrder(101L, 5000, 100);
        event.instrumentId = INSTRUMENT_ID + 1;

        assertThatThrownBy(() -> handler.onEvent(event, 2L, true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("instrumentId");

        assertReset(event);
    }

    private EngineEvent newLimitOrder(long orderId, long priceTicks, long qty) {
        EngineEvent event = new EngineEvent();
        event.type = EngineEvent.Type.NEW_ORDER;
        event.instrumentId = INSTRUMENT_ID;
        event.timestampNanos = 1_000L;
        event.orderId = orderId;
        event.clientOrderId = "client-" + orderId;
        event.side = OrderSide.ASK;
        event.orderType = OrderType.LIMIT;
        event.priceTicks = priceTicks;
        event.qty = qty;
        event.newPriceTicks = -1L;
        event.newQty = -1L;
        return event;
    }

    private void assertReset(EngineEvent event) {
        assertThat(event.type).isNull();
        assertThat(event.instrumentId).isZero();
        assertThat(event.timestampNanos).isZero();
        assertThat(event.orderId).isZero();
        assertThat(event.clientOrderId).isNull();
        assertThat(event.side).isNull();
        assertThat(event.orderType).isNull();
        assertThat(event.priceTicks).isZero();
        assertThat(event.qty).isZero();
        assertThat(event.targetOrderId).isZero();
        assertThat(event.newPriceTicks).isEqualTo(-1L);
        assertThat(event.newQty).isEqualTo(-1L);
    }
}
