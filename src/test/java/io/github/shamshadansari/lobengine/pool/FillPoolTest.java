package io.github.shamshadansari.lobengine.pool;

import io.github.shamshadansari.lobengine.domain.Fill;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FillPoolTest {

    @Test
    void constructor_prewarmsCapacity() {
        FillPool pool = new FillPool(3);

        assertThat(pool.available()).isEqualTo(3);
        assertThat(pool.misses()).isZero();
    }

    @Test
    void acquire_returnsResetFill() {
        FillPool pool = new FillPool(1);

        Fill fill = pool.acquire();

        assertThat(fill.fillId).isZero();
        assertThat(fill.instrumentId).isZero();
        assertThat(fill.buyOrderId).isZero();
        assertThat(fill.sellOrderId).isZero();
        assertThat(fill.fillPriceTicks).isZero();
        assertThat(fill.fillQty).isZero();
        assertThat(fill.timestampNanos).isZero();
        assertThat(fill.buyWasAggressor).isFalse();
    }

    @Test
    void acquire_onEmptyPoolReturnsFreshFillAndTracksMiss() {
        FillPool pool = new FillPool(0);

        Fill fill = pool.acquire();

        assertThat(fill).isNotNull();
        assertThat(fill.instrumentId).isZero();
        assertThat(pool.misses()).isEqualTo(1);
    }

    @Test
    void release_returnsObjectForReuse() {
        FillPool pool = new FillPool(1);
        Fill fill = pool.acquire();

        pool.release(fill);
        Fill reused = pool.acquire();

        assertThat(reused).isSameAs(fill);
    }

    @Test
    void release_resetsFillBeforePooling() {
        FillPool pool = new FillPool(1);
        Fill fill = pool.acquire();
        fill.fillId = 42;
        fill.instrumentId = 1;
        fill.buyOrderId = 10;
        fill.sellOrderId = 20;
        fill.fillPriceTicks = 5000;
        fill.fillQty = 100;
        fill.timestampNanos = 123_456;
        fill.buyWasAggressor = true;

        pool.release(fill);
        Fill reused = pool.acquire();

        assertThat(reused.fillId).isZero();
        assertThat(reused.instrumentId).isZero();
        assertThat(reused.buyOrderId).isZero();
        assertThat(reused.sellOrderId).isZero();
        assertThat(reused.fillPriceTicks).isZero();
        assertThat(reused.fillQty).isZero();
        assertThat(reused.timestampNanos).isZero();
        assertThat(reused.buyWasAggressor).isFalse();
    }

    @Test
    void release_beyondCapacityDoesNotGrowPool() {
        FillPool pool = new FillPool(1);

        pool.release(new Fill());
        pool.release(new Fill());

        assertThat(pool.available()).isEqualTo(1);
    }
}
