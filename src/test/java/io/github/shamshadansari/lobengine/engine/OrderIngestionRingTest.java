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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderIngestionRingTest {

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
        ring = new OrderIngestionRing(engine, 16);
    }

    @AfterEach
    void tearDown() {
        ring.shutdown();
    }

    @Test
    void publishValidatedRequiresStartedRing() {
        ValidationResult result = validator.validateNew(submission(1L, OrderType.LIMIT, 50.00, 100L));

        assertThatThrownBy(() -> ring.publishValidated(result.command))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("started");
    }

    @Test
    void publishValidatedCopiesCommandIntoRingSlot() {
        ring.start();
        ValidationResult result = validator.validateNew(submission(1L, OrderType.LIMIT, 50.00, 100L));

        ring.publishValidated(result.command);

        awaitUntil(() -> metrics.ordersProcessed() == 1L);
        BookSnapshot snapshot = engine.snapshot();
        assertThat(snapshot.asks).hasSize(1);
        assertThat(snapshot.asks.get(0).priceTicks).isEqualTo(5_000L);
        assertThat(snapshot.asks.get(0).totalVolume).isEqualTo(100L);
    }

    @Test
    void publishNewValidatesBeforePublishing() {
        ring.start();

        ValidationResult result = ring.publishNew(submission(1L, OrderType.LIMIT, 50.00, 0L), validator);

        assertThat(result.ok).isFalse();
        assertThat(metrics.ordersProcessed()).isZero();
        assertThat(engine.snapshot().hasAsks()).isFalse();
    }

    @Test
    void publishModifyPreservesPriceSentinel() {
        ring.start();
        ValidationResult newResult = validator.validateNew(submission(1L, OrderType.LIMIT, 50.00, 100L));
        ring.publishValidated(newResult.command);
        activeOrderIds.add(1L);
        awaitUntil(() -> metrics.ordersProcessed() == 1L);

        ValidationResult modifyResult = ring.publishModify(modifyEvent(1L, -1L, 40L), validator);

        assertThat(modifyResult.ok).isTrue();
        awaitUntil(() -> metrics.ordersProcessed() == 2L);

        BookSnapshot snapshot = engine.snapshot();
        assertThat(snapshot.asks).hasSize(1);
        assertThat(snapshot.asks.get(0).priceTicks).isEqualTo(5_000L);
        assertThat(snapshot.asks.get(0).totalVolume).isEqualTo(40L);
    }

    @Test
    void shutdownAllowsCleanLifecycleCompletion() {
        ring.start();
        ring.publishValidated(validator.validateNew(submission(1L, OrderType.LIMIT, 50.00, 100L)).command);
        awaitUntil(() -> metrics.ordersProcessed() == 1L);

        ring.shutdown();

        assertThatThrownBy(() -> ring.publishValidated(validator.validateNew(submission(2L, OrderType.LIMIT, 50.00, 100L)).command))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("started");
    }

    private OrderSubmission submission(long orderId, OrderType type, double price, long qty) {
        return new OrderSubmission(
            orderId,
            "client-" + orderId,
            INSTRUMENT_ID,
            OrderSide.ASK,
            type,
            price,
            qty,
            1_000L + orderId
        );
    }

    private EngineEvent modifyEvent(long targetOrderId, long newPriceTicks, long newQty) {
        EngineEvent event = new EngineEvent();
        event.reset();
        event.type = EngineEvent.Type.MODIFY_ORDER;
        event.instrumentId = INSTRUMENT_ID;
        event.timestampNanos = 2_000L;
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
