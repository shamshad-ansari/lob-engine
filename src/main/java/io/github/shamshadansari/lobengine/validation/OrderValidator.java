package io.github.shamshadansari.lobengine.validation;

import io.github.shamshadansari.lobengine.domain.EngineEvent;
import io.github.shamshadansari.lobengine.domain.Instrument;
import io.github.shamshadansari.lobengine.domain.OrderType;

import java.math.BigInteger;
import java.util.OptionalLong;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;

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

public final class OrderValidator {

    private static final BigInteger TEN_THOUSAND = BigInteger.valueOf(10_000L);

    private final LongFunction<Instrument> instrumentLookup;
    private final LongUnaryOperator        referencePriceSupplier;
    private final LongPredicate            orderIdExistsCheck;

    public OrderValidator(long instrumentId,
                          Instrument instrument,
                          LongSupplier referencePriceSupplier,
                          LongPredicate orderIdExistsCheck) {
        this(
            id -> id == instrumentId ? instrument : null,
            ignored -> referencePriceSupplier.getAsLong(),
            orderIdExistsCheck
        );
    }

    public OrderValidator(LongFunction<Instrument> instrumentLookup,
                          LongUnaryOperator referencePriceSupplier,
                          LongPredicate orderIdExistsCheck) {
        this.instrumentLookup       = instrumentLookup;
        this.referencePriceSupplier = referencePriceSupplier;
        this.orderIdExistsCheck     = orderIdExistsCheck;
    }

    public ValidationResult validateNew(OrderSubmission submission) {
        if (submission == null) {
            return ValidationResult.reject(INVALID_ORDER_TYPE);
        }

        Instrument instrument = instrumentLookup.apply(submission.instrumentId);
        if (instrument == null) {
            return ValidationResult.reject(UNKNOWN_INSTRUMENT);
        }
        if (submission.orderId <= 0) {
            return ValidationResult.reject(INVALID_ORDER_ID);
        }
        if (submission.side == null) {
            return ValidationResult.reject(INVALID_ORDER_SIDE);
        }
        if (submission.type == null) {
            return ValidationResult.reject(INVALID_ORDER_TYPE);
        }
        if (submission.qty <= 0) {
            return ValidationResult.reject(INVALID_QUANTITY);
        }
        if (violatesLotSize(submission.qty, instrument)) {
            return ValidationResult.reject(LOT_SIZE_VIOLATION);
        }

        long priceTicks = 0L;
        if (submission.type == OrderType.LIMIT || submission.type == OrderType.IOC) {
            if (!Double.isFinite(submission.price)) {
                return ValidationResult.reject(INVALID_PRICE);
            }
            if (submission.price <= 0.0) {
                return ValidationResult.reject(MISSING_PRICE_FOR_LIMIT);
            }

            OptionalLong validatedTicks = instrument.tryToTicksExact(submission.price);
            if (validatedTicks.isEmpty()) {
                return ValidationResult.reject(INVALID_PRICE);
            }

            priceTicks = validatedTicks.getAsLong();
            if (violatesPriceBand(submission.instrumentId, instrument, priceTicks)) {
                return ValidationResult.reject(PRICE_BAND_VIOLATION);
            }
        } else if (submission.type == OrderType.MARKET) {
            if (!Double.isFinite(submission.price)) {
                return ValidationResult.reject(INVALID_PRICE);
            }
            if (submission.price != 0.0) {
                return ValidationResult.reject(MARKET_ORDER_WITH_PRICE);
            }
        }

        if (orderIdExistsCheck.test(submission.orderId)) {
            return ValidationResult.reject(DUPLICATE_ORDER_ID);
        }

        return ValidationResult.ok(ValidatedOrderCommand.newOrder(submission, priceTicks));
    }

    public ValidationResult validateCancel(EngineEvent event) {
        if (event == null || event.type != EngineEvent.Type.CANCEL_ORDER) {
            return ValidationResult.reject(INVALID_ORDER_TYPE);
        }

        Instrument instrument = instrumentLookup.apply(event.instrumentId);
        if (instrument == null) {
            return ValidationResult.reject(UNKNOWN_INSTRUMENT);
        }
        if (event.targetOrderId <= 0) {
            return ValidationResult.reject(INVALID_ORDER_ID);
        }
        if (!orderIdExistsCheck.test(event.targetOrderId)) {
            return ValidationResult.reject(UNKNOWN_TARGET_ORDER);
        }

        return ValidationResult.ok(ValidatedOrderCommand.cancel(event));
    }

    public ValidationResult validateModify(EngineEvent event) {
        if (event == null || event.type != EngineEvent.Type.MODIFY_ORDER) {
            return ValidationResult.reject(INVALID_MODIFY);
        }

        Instrument instrument = instrumentLookup.apply(event.instrumentId);
        if (instrument == null) {
            return ValidationResult.reject(UNKNOWN_INSTRUMENT);
        }
        if (event.targetOrderId <= 0) {
            return ValidationResult.reject(INVALID_ORDER_ID);
        }
        if (!orderIdExistsCheck.test(event.targetOrderId)) {
            return ValidationResult.reject(UNKNOWN_TARGET_ORDER);
        }
        if (event.newPriceTicks == -1L && event.newQty == -1L) {
            return ValidationResult.reject(INVALID_MODIFY);
        }
        if (event.newPriceTicks < -1L || event.newQty < -1L) {
            return ValidationResult.reject(INVALID_MODIFY);
        }
        if (event.newPriceTicks == 0L) {
            return ValidationResult.reject(INVALID_PRICE);
        }
        if (event.newPriceTicks > 0L && violatesPriceBand(event.instrumentId, instrument, event.newPriceTicks)) {
            return ValidationResult.reject(PRICE_BAND_VIOLATION);
        }
        if (event.newQty == 0L) {
            return ValidationResult.reject(INVALID_QUANTITY);
        }
        if (event.newQty > 0L && violatesLotSize(event.newQty, instrument)) {
            return ValidationResult.reject(LOT_SIZE_VIOLATION);
        }

        return ValidationResult.ok(ValidatedOrderCommand.modify(event));
    }

    private boolean violatesLotSize(long qty, Instrument instrument) {
        return instrument.lotSize <= 0 || qty % instrument.lotSize != 0;
    }

    private boolean violatesPriceBand(long instrumentId, Instrument instrument, long priceTicks) {
        long referencePrice = referencePriceSupplier.applyAsLong(instrumentId);
        if (referencePrice <= 0L) {
            return false;
        }

        BigInteger absDiff = BigInteger.valueOf(priceTicks)
            .subtract(BigInteger.valueOf(referencePrice))
            .abs();
        BigInteger scaledDiff = absDiff.multiply(TEN_THOUSAND);
        BigInteger allowedDiff = BigInteger.valueOf(referencePrice)
            .multiply(BigInteger.valueOf(instrument.priceBandBps));

        return scaledDiff.compareTo(allowedDiff) > 0;
    }
}
