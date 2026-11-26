package dev.mounir.ledgerpilot.ledger;

import java.util.List;
import java.util.Set;

public final class LedgerInvariants {

    public static final int MIN_POSTINGS = 2;
    public static final int MAX_POSTINGS = 8;

    private LedgerInvariants() {
    }

    public static void check(PostTransactionCommand command, Set<String> knownCodes) {
        List<PostingLine> postings = command.postings() == null ? List.of() : command.postings();
        if (postings.size() < MIN_POSTINGS || postings.size() > MAX_POSTINGS) {
            throw new LedgerRejectedException(
                    LedgerRejectedException.POSTING_COUNT,
                    "a transaction must have 2 to 8 postings");
        }
        for (PostingLine line : postings) {
            if (line.amountMinor() < 1) {
                throw new LedgerRejectedException(
                        LedgerRejectedException.NON_POSITIVE_AMOUNT,
                        "amountMinor must be positive");
            }
        }
        for (PostingLine line : postings) {
            if (!knownCodes.contains(line.accountCode())) {
                throw new LedgerRejectedException(
                        LedgerRejectedException.UNKNOWN_ACCOUNT,
                        "unknown account");
            }
        }
        String currency = postings.get(0).currency();
        for (int i = 1; i < postings.size(); i++) {
            if (!currency.equals(postings.get(i).currency())) {
                throw new LedgerRejectedException(
                        LedgerRejectedException.MIXED_CURRENCY,
                        "a transaction must use one currency");
            }
        }
        long debits = 0;
        long credits = 0;
        try {
            for (PostingLine line : postings) {
                if (line.side() == Side.DEBIT) {
                    debits = Math.addExact(debits, line.amountMinor());
                } else {
                    credits = Math.addExact(credits, line.amountMinor());
                }
            }
        } catch (ArithmeticException ex) {
            throw new LedgerRejectedException(
                    LedgerRejectedException.AMOUNT_OVERFLOW,
                    "posting amounts overflow");
        }
        if (debits != credits) {
            throw new LedgerRejectedException(
                    LedgerRejectedException.UNBALANCED,
                    "debits must equal credits");
        }
    }
}
