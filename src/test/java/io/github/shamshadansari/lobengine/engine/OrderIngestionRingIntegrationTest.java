package io.github.shamshadansari.lobengine.engine;

import io.github.shamshadansari.lobengine.BookSnapshot;
import io.github.shamshadansari.lobengine.domain.EngineEvent;
import io.github.shamshadansari.lobengine.domain.Instrument;
import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderType;
import io.github.shamshadansari.lobengine.metrics.EngineMetrics;
import io.github.shamshadansari.lobengine.validation.OrderSubmission;
import io.github.shamshadansari.lobengine.validation.OrderValidator;
import io.github.shamshadansari.lobengine.validation.ValidationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class OrderIngestionRingIntegrationTest {

    private static final long INSTRUMENT_ID = 1L;

    private final Set<Long> activeOrderIds = new HashSet<>();

    private EngineMetrics metrics;
    private MatchingEngine engine;
    private OrderValidator validator;
    private OrderIngestionRing ring;

    @BeforeEach
    void setUp() {
        metrics = new EngineMetrics();
        engine = new MatchingEngine(INSTRUMENT_ID, metrics);
        validator = new OrderValidator(
            INSTRUMENT_ID,
            Instrument.DEFAULT,
            () -> 5_000L,
            activeOrderIds::contains
        );
        ring = new OrderIngestionRing(engine, 64);
        ring.start();
    }

    @AfterEach
    void tearDown() {
        ring.shutdown();
    }

    @Test
    void publishValidatedNewEventsRestInBook() {
        publishAcceptedNew(1L, OrderSide.ASK, OrderType.LIMIT, 50.00, 100L);
        long sequence = publishAcceptedNew(2L, OrderSide.ASK, OrderType.LIMIT, 50.01, 200L);

        awaitProcessed(sequence);

        BookSnapshot snapshot = engine.snapshot();
        assertThat(snapshot.asks).hasSize(2);
        assertThat(snapshot.asks.get(0).priceTicks).isEqualTo(5_000L);
        assertThat(snapshot.asks.get(0).totalVolume).isEqualTo(100L);
        assertThat(snapshot.asks.get(1).priceTicks).isEqualTo(5_001L);
        assertThat(snapshot.asks.get(1).totalVolume).isEqualTo(200L);
    }

    @Test
    void publishValidatedModifyWithPriceSentinelAppliesQuantityOnlyChange() {
        long newSequence = publishAcceptedNew(1L, OrderSide.ASK, OrderType.LIMIT, 50.00, 100L);
        awaitProcessed(newSequence);

        ValidationResult result = validator.validateModify(modifyEvent(1L, -1L, 40L));

        assertThat(result.ok).isTrue();
        long modifySequence = ring.publishValidated(result.command);
        awaitProcessed(modifySequence);

        BookSnapshot snapshot = engine.snapshot();
        assertThat(snapshot.asks).hasSize(1);
        assertThat(snapshot.asks.get(0).priceTicks).isEqualTo(5_000L);
        assertThat(snapshot.asks.get(0).totalVolume).isEqualTo(40L);
    }

    @Test
    void publishValidatedCancelRemovesTargetOrder() {
        long newSequence = publishAcceptedNew(1L, OrderSide.ASK, OrderType.LIMIT, 50.00, 100L);
        awaitProcessed(newSequence);

        ValidationResult result = validator.validateCancel(cancelEvent(1L));

        assertThat(result.ok).isTrue();
        long cancelSequence = ring.publishValidated(result.command);
        activeOrderIds.remove(1L);
        awaitProcessed(cancelSequence);
        assertThat(engine.snapshot().hasAsks()).isFalse();
    }

    @Test
    void phaseZeroCrossingScenarioRunsThroughRing() {
        publishAcceptedNew(1L, OrderSide.ASK, OrderType.LIMIT, 50.00, 100L);
        long sequence = publishAcceptedNew(2L, OrderSide.BID, OrderType.LIMIT, 51.00, 100L);

        awaitProcessed(sequence);

        BookSnapshot snapshot = engine.snapshot();
        assertThat(snapshot.hasBids()).isFalse();
        assertThat(snapshot.hasAsks()).isFalse();
        assertThat(metrics.fillsGenerated()).isEqualTo(1L);
    }

    @Test
    void phaseZeroMultiLevelSweepRunsThroughRing() {
        publishAcceptedNew(1L, OrderSide.ASK, OrderType.LIMIT, 50.00, 100L);
        publishAcceptedNew(2L, OrderSide.ASK, OrderType.LIMIT, 50.01, 100L);
        publishAcceptedNew(3L, OrderSide.ASK, OrderType.LIMIT, 50.02, 100L);
        long sequence = publishAcceptedNew(4L, OrderSide.BID, OrderType.LIMIT, 50.02, 250L);

        awaitProcessed(sequence);

        BookSnapshot snapshot = engine.snapshot();
        assertThat(metrics.fillsGenerated()).isEqualTo(3L);
        assertThat(snapshot.hasBids()).isFalse();
        assertThat(snapshot.asks).hasSize(1);
        assertThat(snapshot.asks.get(0).priceTicks).isEqualTo(5_002L);
        assertThat(snapshot.asks.get(0).totalVolume).isEqualTo(50L);
    }

    private long publishAcceptedNew(long orderId, OrderSide side, OrderType type, double price, long qty) {
        ValidationResult result = validator.validateNew(submission(orderId, side, type, price, qty));
        assertThat(result.ok).isTrue();
        activeOrderIds.add(orderId);
        return ring.publishValidated(result.command);
    }

    private OrderSubmission submission(long orderId, OrderSide side, OrderType type, double price, long qty) {
        return new OrderSubmission(
            orderId,
            "client-" + orderId,
            INSTRUMENT_ID,
            side,
            type,
            price,
            qty,
            1_000L + orderId
        );
    }

    private void awaitProcessed(long sequence) {
        awaitUntil(() -> ring.hasProcessed(sequence));
    }

    private EngineEvent cancelEvent(long targetOrderId) {
        EngineEvent event = new EngineEvent();
        event.reset();
        event.type = EngineEvent.Type.CANCEL_ORDER;
        event.instrumentId = INSTRUMENT_ID;
        event.timestampNanos = 2_000L;
        event.targetOrderId = targetOrderId;
        return event;
    }

    private EngineEvent modifyEvent(long targetOrderId, long newPriceTicks, long newQty) {
        EngineEvent event = new EngineEvent();
        event.reset();
        event.type = EngineEvent.Type.MODIFY_ORDER;
        event.instrumentId = INSTRUMENT_ID;
        event.timestampNanos = 3_000L;
        event.targetOrderId = targetOrderId;
        event.newPriceTicks = newPriceTicks;
        event.newQty = newQty;
        return event;
    }

    private void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.yield();
        }
        throw new AssertionError("Timed out waiting for condition");
    }
}
