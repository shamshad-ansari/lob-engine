package io.github.shamshadansari.lobengine.engine;

import io.github.shamshadansari.lobengine.BookSnapshot;
import io.github.shamshadansari.lobengine.domain.Instrument;
import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderType;
import io.github.shamshadansari.lobengine.metrics.EngineMetrics;
import io.github.shamshadansari.lobengine.validation.OrderSubmission;
import io.github.shamshadansari.lobengine.validation.OrderValidator;
import io.github.shamshadansari.lobengine.validation.ValidationResult;
import org.junit.jupiter.api.RepeatedTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class OrderIngestionRingConcurrentProducerTest {

    private static final long INSTRUMENT_ID = 1L;

    @RepeatedTest(10)
    void fourProducersPublishDeterministicNonCrossingLimitOrders() throws Exception {
        EngineMetrics metrics = new EngineMetrics();
        MatchingEngine engine = new MatchingEngine(INSTRUMENT_ID, metrics);
        Set<Long> activeOrderIds = ConcurrentHashMap.newKeySet();
        OrderValidator validator = new OrderValidator(
            INSTRUMENT_ID,
            Instrument.DEFAULT,
            () -> 0L,
            activeOrderIds::contains
        );

        OrderIngestionRing ring = new OrderIngestionRing(engine, 1024);
        ExecutorService producers = Executors.newFixedThreadPool(4);

        try {
            ring.start();
            CountDownLatch startSignal = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int producerId = 0; producerId < 4; producerId++) {
                int capturedProducerId = producerId;
                futures.add(producers.submit(
                    () -> publishProducerOrders(capturedProducerId, validator, activeOrderIds, ring, startSignal)
                ));
            }

            startSignal.countDown();
            producers.shutdown();
            assertThat(producers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            for (Future<?> future : futures) {
                future.get();
            }

            awaitUntil(() -> metrics.ordersProcessed() == 400L);

            BookSnapshot snapshot = engine.snapshot();
            assertThat(snapshot.hasBids()).isFalse();
            assertThat(snapshot.asks).hasSize(400);
            assertThat(snapshot.asks.stream().mapToLong(level -> level.totalVolume).sum()).isEqualTo(40_000L);
        } finally {
            producers.shutdownNow();
            ring.shutdown();
        }
    }

    private void publishProducerOrders(int producerId,
                                       OrderValidator validator,
                                       Set<Long> activeOrderIds,
                                       OrderIngestionRing ring,
                                       CountDownLatch startSignal) {
        awaitStart(startSignal);

        for (int sequence = 0; sequence < 100; sequence++) {
            long orderId = producerId * 1_000L + sequence + 1L;
            double price = 50.00 + producerId + (sequence / 100.0);
            ValidationResult result = validator.validateNew(submission(orderId, price));
            assertThat(result.ok).isTrue();
            activeOrderIds.add(orderId);
            ring.publishValidated(result.command);
        }
    }

    private OrderSubmission submission(long orderId, double price) {
        return new OrderSubmission(
            orderId,
            "client-" + orderId,
            INSTRUMENT_ID,
            OrderSide.ASK,
            OrderType.LIMIT,
            price,
            100L,
            1_000L + orderId
        );
    }

    private void awaitStart(CountDownLatch startSignal) {
        try {
            assertThat(startSignal.await(2, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting to start producers", e);
        }
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
