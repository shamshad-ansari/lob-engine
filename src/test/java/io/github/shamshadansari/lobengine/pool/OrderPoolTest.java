package io.github.shamshadansari.lobengine.pool;

import io.github.shamshadansari.lobengine.domain.Order;
import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderStatus;
import io.github.shamshadansari.lobengine.util.DoublyLinkedList;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderPoolTest {

    @Test
    void constructor_prewarmsCapacity() {
        OrderPool pool = new OrderPool(3);

        assertThat(pool.available()).isEqualTo(3);
        assertThat(pool.misses()).isZero();
    }

    @Test
    void acquire_returnsResetOrder() {
        OrderPool pool = new OrderPool(1);

        Order order = pool.acquire();

        assertThat(order.orderId).isZero();
        assertThat(order.clientOrderId).isNull();
        assertThat(order.side).isNull();
        assertThat(order.priceTicks).isZero();
        assertThat(order.originalQty).isZero();
        assertThat(order.remainingQty).isZero();
        assertThat(order.status).isEqualTo(OrderStatus.PENDING);
        assertThat(order.timestampNanos).isZero();
        assertThat(order.owningNode).isNull();
    }

    @Test
    void acquire_onEmptyPoolReturnsFreshOrderAndTracksMiss() {
        OrderPool pool = new OrderPool(0);

        Order order = pool.acquire();

        assertThat(order).isNotNull();
        assertThat(order.status).isEqualTo(OrderStatus.PENDING);
        assertThat(order.owningNode).isNull();
        assertThat(pool.misses()).isEqualTo(1);
    }

    @Test
    void release_returnsObjectForReuse() {
        OrderPool pool = new OrderPool(1);
        Order order = pool.acquire();

        pool.release(order);
        Order reused = pool.acquire();

        assertThat(reused).isSameAs(order);
    }

    @Test
    void release_resetsOrderBeforePooling() {
        OrderPool pool = new OrderPool(1);
        Order order = pool.acquire();
        order.orderId = 42;
        order.clientOrderId = "client-42";
        order.side = OrderSide.BID;
        order.remainingQty = 100;
        order.status = OrderStatus.FILLED;
        order.owningNode = new DoublyLinkedList<Order>().addLast(order);

        pool.release(order);
        Order reused = pool.acquire();

        assertThat(reused.orderId).isZero();
        assertThat(reused.clientOrderId).isNull();
        assertThat(reused.side).isNull();
        assertThat(reused.remainingQty).isZero();
        assertThat(reused.status).isEqualTo(OrderStatus.PENDING);
        assertThat(reused.owningNode).isNull();
    }

    @Test
    void release_beyondCapacityDoesNotGrowPool() {
        OrderPool pool = new OrderPool(1);

        pool.release(new Order());
        pool.release(new Order());

        assertThat(pool.available()).isEqualTo(1);
    }
}
