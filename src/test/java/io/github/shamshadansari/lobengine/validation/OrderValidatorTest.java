package io.github.shamshadansari.lobengine.validation;

import io.github.shamshadansari.lobengine.domain.EngineEvent;
import io.github.shamshadansari.lobengine.domain.Instrument;
import io.github.shamshadansari.lobengine.domain.OrderSide;
import io.github.shamshadansari.lobengine.domain.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static io.github.shamshadansari.lobengine.validation.ValidationResult.RejectionReason.DUPLICATE_ORDER_ID;
import static io.github.shamshadansari.lobengine.validation.ValidationResult.RejectionReason.INVALID_MODIFY;
import static io.github.shamshadansari.lobengine.validation.ValidationResult.RejectionReason.INVALID_ORDER_ID;
import static io.github.shamshadansari.lobengine.validation.ValidationResult.RejectionReason.INVALID_ORDER_SIDE;
import static io.github.shamshadansari.lobengine.validation.ValidationResult.RejectionReason.INVALID_ORDER_TYPE;
import static io.github.shamshadansari.lobengine.validation.ValidationResult.RejectionReason.INVALID_PRICE;
import static io.github.shamshadansari.lobengine.validation.ValidationResult.RejectionReason.INVALID_QUANTITY;
import static io.github.shamshadansari.lobengine.validation.ValidationResult.RejectionReason.LOT_SIZE_VIOLATION;
import static io.github.shamshadansari.lobengine.validation.ValidationResult.RejectionReason.MARKET_ORDER_WITH_PRICE;
import static io.github.shamshadansari.lobengine.validation.ValidationResult.RejectionReason.MISSING_PRICE_FOR_LIMIT;
import static io.github.shamshadansari.lobengine.validation.ValidationResult.RejectionReason.PRICE_BAND_VIOLATION;
import static io.github.shamshadansari.lobengine.validation.ValidationResult.RejectionReason.UNKNOWN_INSTRUMENT;
import static io.github.shamshadansari.lobengine.validation.ValidationResult.RejectionReason.UNKNOWN_TARGET_ORDER;
import static org.assertj.core.api.Assertions.assertThat;

class OrderValidatorTest {

    private static final long INSTRUMENT_ID = 1L;

    private final Set<Long> activeOrderIds = new HashSet<>();
    private final long[]    referencePrice = { 10_000L }; // $100.00 with $0.01 ticks

    private Instrument     instrument;
    private OrderValidator validator;

    @BeforeEach
    void setUp() {
        instrument = new Instrument("TEST", 10_000L, 100L, 500L);
        validator = new OrderValidator(
            INSTRUMENT_ID,
            instrument,
            () -> referencePrice[0],
            activeOrderIds::contains
        );
    }

    @Test
    void validateNew_accepts_limit_orders_and_returns_canonical_ticks() {
        ValidationResult result = validator.validateNew(submission(1L, OrderSide.BID, OrderType.LIMIT, 100.00, 100L));

        assertThat(result.ok).isTrue();
        assertThat(result.reason).isNull();
        assertThat(result.command.type).isEqualTo(EngineEvent.Type.NEW_ORDER);
        assertThat(result.command.priceTicks).isEqualTo(10_000L);
        assertThat(result.command.qty).isEqualTo(100L);
    }

    @Test
    void validateNew_accepts_market_orders_with_zero_price() {
        ValidationResult result = validator.validateNew(submission(1L, OrderSide.BID, OrderType.MARKET, 0.0, 100L));

        assertThat(result.ok).isTrue();
        assertThat(result.command.priceTicks).isZero();
    }

    @Test
    void validateNew_accepts_ioc_orders_as_priced_non_resting_limits() {
        ValidationResult result = validator.validateNew(submission(1L, OrderSide.ASK, OrderType.IOC, 100.00, 100L));

        assertThat(result.ok).isTrue();
        assertThat(result.command.priceTicks).isEqualTo(10_000L);
    }

    @Test
    void validateNew_rejects_zero_quantity() {
        assertRejects(validator.validateNew(submission(1L, OrderSide.BID, OrderType.LIMIT, 100.00, 0L)), INVALID_QUANTITY);
    }

    @Test
    void validateNew_rejects_lot_size_violation() {
        assertRejects(validator.validateNew(submission(1L, OrderSide.BID, OrderType.LIMIT, 100.00, 50L)), LOT_SIZE_VIOLATION);
    }

    @Test
    void validateNew_rejects_missing_price_for_limit_and_ioc() {
        assertRejects(validator.validateNew(submission(1L, OrderSide.BID, OrderType.LIMIT, 0.0, 100L)), MISSING_PRICE_FOR_LIMIT);
        assertRejects(validator.validateNew(submission(2L, OrderSide.BID, OrderType.IOC, 0.0, 100L)), MISSING_PRICE_FOR_LIMIT);
    }

    @Test
    void validateNew_rejects_market_orders_with_price() {
        assertRejects(validator.validateNew(submission(1L, OrderSide.BID, OrderType.MARKET, 100.00, 100L)), MARKET_ORDER_WITH_PRICE);
    }

    @Test
    void validateNew_rejects_non_finite_prices() {
        assertRejects(validator.validateNew(submission(1L, OrderSide.BID, OrderType.LIMIT, Double.NaN, 100L)), INVALID_PRICE);
        assertRejects(validator.validateNew(submission(2L, OrderSide.BID, OrderType.MARKET, Double.POSITIVE_INFINITY, 100L)), INVALID_PRICE);
    }

    @Test
    void validateNew_rejects_off_tick_prices() {
        assertRejects(validator.validateNew(submission(1L, OrderSide.BID, OrderType.LIMIT, 100.005, 100L)), INVALID_PRICE);
    }

    @Test
    void validateNew_rejects_price_band_violations() {
        assertRejects(validator.validateNew(submission(1L, OrderSide.BID, OrderType.LIMIT, 106.00, 100L)), PRICE_BAND_VIOLATION);
        assertRejects(validator.validateNew(submission(2L, OrderSide.ASK, OrderType.LIMIT, 94.00, 100L)), PRICE_BAND_VIOLATION);
    }

    @Test
    void validateNew_accepts_exact_price_band_boundary_and_skips_zero_reference() {
        assertThat(validator.validateNew(submission(1L, OrderSide.BID, OrderType.LIMIT, 105.00, 100L)).ok).isTrue();
        assertThat(validator.validateNew(submission(2L, OrderSide.ASK, OrderType.LIMIT, 95.00, 100L)).ok).isTrue();

        referencePrice[0] = 0L;
        assertThat(validator.validateNew(submission(3L, OrderSide.BID, OrderType.LIMIT, 500.00, 100L)).ok).isTrue();
    }

    @Test
    void validateNew_rejects_duplicate_order_ids() {
        activeOrderIds.add(42L);

        assertRejects(validator.validateNew(submission(42L, OrderSide.BID, OrderType.LIMIT, 100.00, 100L)), DUPLICATE_ORDER_ID);
    }

    @Test
    void validateNew_rejects_unknown_instrument() {
        OrderSubmission submission = new OrderSubmission(1L, "client-1", 2L, OrderSide.BID, OrderType.LIMIT, 100.00, 100L, 1L);

        assertRejects(validator.validateNew(submission), UNKNOWN_INSTRUMENT);
    }

    @Test
    void validateNew_rejects_invalid_order_id() {
        assertRejects(validator.validateNew(submission(0L, OrderSide.BID, OrderType.LIMIT, 100.00, 100L)), INVALID_ORDER_ID);
    }

    @Test
    void validateNew_rejects_missing_side() {
        assertRejects(validator.validateNew(submission(1L, null, OrderType.LIMIT, 100.00, 100L)), INVALID_ORDER_SIDE);
    }

    @Test
    void validateNew_rejects_missing_order_type() {
        assertRejects(validator.validateNew(submission(1L, OrderSide.BID, null, 100.00, 100L)), INVALID_ORDER_TYPE);
    }

    @Test
    void validateCancel_accepts_known_targets() {
        activeOrderIds.add(10L);

        ValidationResult result = validator.validateCancel(cancelEvent(10L));

        assertThat(result.ok).isTrue();
        assertThat(result.command.type).isEqualTo(EngineEvent.Type.CANCEL_ORDER);
        assertThat(result.command.targetOrderId).isEqualTo(10L);
    }

    @Test
    void validateCancel_rejects_invalid_target_order_id() {
        assertRejects(validator.validateCancel(cancelEvent(0L)), INVALID_ORDER_ID);
    }

    @Test
    void validateCancel_rejects_unknown_targets() {
        assertRejects(validator.validateCancel(cancelEvent(99L)), UNKNOWN_TARGET_ORDER);
    }

    @Test
    void validateModify_accepts_qty_change_with_price_sentinel() {
        activeOrderIds.add(10L);
        EngineEvent event = modifyEvent(10L, -1L, 200L);

        ValidationResult result = validator.validateModify(event);

        assertThat(result.ok).isTrue();
        assertThat(result.command.type).isEqualTo(EngineEvent.Type.MODIFY_ORDER);
        assertThat(result.command.newPriceTicks).isEqualTo(-1L);
        assertThat(result.command.newQty).isEqualTo(200L);
    }

    @Test
    void validateModify_accepts_price_change_with_qty_sentinel() {
        activeOrderIds.add(10L);

        ValidationResult result = validator.validateModify(modifyEvent(10L, 10_100L, -1L));

        assertThat(result.ok).isTrue();
        assertThat(result.command.newPriceTicks).isEqualTo(10_100L);
        assertThat(result.command.newQty).isEqualTo(-1L);
    }

    @Test
    void validateModify_rejects_unknown_targets() {
        assertRejects(validator.validateModify(modifyEvent(99L, -1L, 200L)), UNKNOWN_TARGET_ORDER);
    }

    @Test
    void validateModify_rejects_noop_sentinel_only_events() {
        activeOrderIds.add(10L);

        assertRejects(validator.validateModify(modifyEvent(10L, -1L, -1L)), INVALID_MODIFY);
    }

    @Test
    void validateModify_rejects_values_below_sentinel() {
        activeOrderIds.add(10L);

        assertRejects(validator.validateModify(modifyEvent(10L, -2L, 100L)), INVALID_MODIFY);
    }

    @Test
    void validateModify_rejects_zero_price_and_zero_quantity_changes() {
        activeOrderIds.add(10L);

        assertRejects(validator.validateModify(modifyEvent(10L, 0L, -1L)), INVALID_PRICE);
        assertRejects(validator.validateModify(modifyEvent(10L, -1L, 0L)), INVALID_QUANTITY);
    }

    @Test
    void validateModify_rejects_lot_size_and_price_band_violations() {
        activeOrderIds.add(10L);

        assertRejects(validator.validateModify(modifyEvent(10L, -1L, 50L)), LOT_SIZE_VIOLATION);
        assertRejects(validator.validateModify(modifyEvent(10L, 10_600L, -1L)), PRICE_BAND_VIOLATION);
    }

    private OrderSubmission submission(long orderId, OrderSide side, OrderType type, double price, long qty) {
        return new OrderSubmission(orderId, "client-" + orderId, INSTRUMENT_ID, side, type, price, qty, 1L);
    }

    private EngineEvent cancelEvent(long targetOrderId) {
        EngineEvent event = new EngineEvent();
        event.reset();
        event.type          = EngineEvent.Type.CANCEL_ORDER;
        event.instrumentId  = INSTRUMENT_ID;
        event.targetOrderId = targetOrderId;
        return event;
    }

    private EngineEvent modifyEvent(long targetOrderId, long newPriceTicks, long newQty) {
        EngineEvent event = new EngineEvent();
        event.reset();
        event.type          = EngineEvent.Type.MODIFY_ORDER;
        event.instrumentId  = INSTRUMENT_ID;
        event.targetOrderId = targetOrderId;
        event.newPriceTicks = newPriceTicks;
        event.newQty        = newQty;
        return event;
    }

    private void assertRejects(ValidationResult result, ValidationResult.RejectionReason reason) {
        assertThat(result.ok).isFalse();
        assertThat(result.reason).isEqualTo(reason);
        assertThat(result.command).isNull();
    }
}
