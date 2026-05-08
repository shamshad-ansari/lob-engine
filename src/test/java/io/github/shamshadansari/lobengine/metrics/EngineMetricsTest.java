package io.github.shamshadansari.lobengine.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineMetricsTest {

    private EngineMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new EngineMetrics();
    }

    // -------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------

    @Test
    void allCountersStartAtZero() {
        assertThat(metrics.ordersProcessed()).isZero();
        assertThat(metrics.fillsGenerated()).isZero();
        assertThat(metrics.cancellations()).isZero();
        assertThat(metrics.cancelMisses()).isZero();
    }

    // -------------------------------------------------------------------------
    // Individual counters
    // -------------------------------------------------------------------------

    @Test
    void recordOrderProcessed_incrementsByOne() {
        metrics.recordOrderProcessed();
        metrics.recordOrderProcessed();

        assertThat(metrics.ordersProcessed()).isEqualTo(2);
    }

    @Test
    void recordFills_addsByN() {
        metrics.recordFills(3);
        metrics.recordFills(2);

        assertThat(metrics.fillsGenerated()).isEqualTo(5);
    }

    @Test
    void recordFills_zeroNIsNoOp() {
        metrics.recordFills(0);

        assertThat(metrics.fillsGenerated()).isZero();
    }

    @Test
    void recordCancel_incrementsByOne() {
        metrics.recordCancel();

        assertThat(metrics.cancellations()).isEqualTo(1);
    }

    @Test
    void recordCancelMiss_incrementsByOne() {
        metrics.recordCancelMiss();
        metrics.recordCancelMiss();

        assertThat(metrics.cancelMisses()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // reset
    // -------------------------------------------------------------------------

    @Test
    void reset_zeroesAllCounters() {
        metrics.recordOrderProcessed();
        metrics.recordFills(5);
        metrics.recordCancel();
        metrics.recordCancelMiss();

        metrics.reset();

        assertThat(metrics.ordersProcessed()).isZero();
        assertThat(metrics.fillsGenerated()).isZero();
        assertThat(metrics.cancellations()).isZero();
        assertThat(metrics.cancelMisses()).isZero();
    }

    @Test
    void reset_allowsCountersToIncrementAgain() {
        metrics.recordOrderProcessed();
        metrics.reset();
        metrics.recordOrderProcessed();

        assertThat(metrics.ordersProcessed()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Independence of counters
    // -------------------------------------------------------------------------

    @Test
    void countersAreIndependentOfEachOther() {
        metrics.recordOrderProcessed();
        metrics.recordFills(10);

        assertThat(metrics.cancellations()).isZero();
        assertThat(metrics.cancelMisses()).isZero();
        assertThat(metrics.ordersProcessed()).isEqualTo(1);
        assertThat(metrics.fillsGenerated()).isEqualTo(10);
    }
}
