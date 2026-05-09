package io.github.shamshadansari.lobengine.validation;

public final class ValidationResult {

    public enum RejectionReason {
        INVALID_QUANTITY,
        INVALID_PRICE,
        PRICE_BAND_VIOLATION,
        DUPLICATE_ORDER_ID,
        LOT_SIZE_VIOLATION,
        MARKET_ORDER_WITH_PRICE,
        MISSING_PRICE_FOR_LIMIT,
        UNKNOWN_INSTRUMENT,
        INVALID_ORDER_ID,
        INVALID_ORDER_SIDE,
        INVALID_ORDER_TYPE,
        UNKNOWN_TARGET_ORDER,
        INVALID_MODIFY
    }

    private static final ValidationResult OK = new ValidationResult(true, null, null);

    public final boolean               ok;
    public final RejectionReason       reason;
    public final ValidatedOrderCommand command;

    public static ValidationResult ok() {
        return OK;
    }

    public static ValidationResult ok(ValidatedOrderCommand command) {
        return new ValidationResult(true, null, command);
    }

    public static ValidationResult reject(RejectionReason reason) {
        return new ValidationResult(false, reason, null);
    }

    private ValidationResult(boolean ok, RejectionReason reason, ValidatedOrderCommand command) {
        this.ok      = ok;
        this.reason  = reason;
        this.command = command;
    }
}
