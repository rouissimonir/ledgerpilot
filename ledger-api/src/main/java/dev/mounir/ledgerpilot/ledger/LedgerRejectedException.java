package dev.mounir.ledgerpilot.ledger;

public class LedgerRejectedException extends RuntimeException {

    public static final String POSTING_COUNT = "POSTING_COUNT";
    public static final String NON_POSITIVE_AMOUNT = "NON_POSITIVE_AMOUNT";
    public static final String UNKNOWN_ACCOUNT = "UNKNOWN_ACCOUNT";
    public static final String MIXED_CURRENCY = "MIXED_CURRENCY";
    public static final String UNBALANCED = "UNBALANCED";
    public static final String AMOUNT_OVERFLOW = "AMOUNT_OVERFLOW";

    private final String code;

    public LedgerRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
