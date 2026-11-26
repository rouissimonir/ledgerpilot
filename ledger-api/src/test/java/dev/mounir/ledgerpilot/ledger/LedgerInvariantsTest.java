package dev.mounir.ledgerpilot.ledger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class LedgerInvariantsTest {

    private static final Set<String> KNOWN = Set.of("1000", "1100", "2000", "3000", "4000", "5000", "9999");

    private static final Instant WHEN = Instant.parse("2026-11-24T08:00:00Z");

    @Test
    void acceptsBalancedInvoice() {
        assertDoesNotThrow(() -> LedgerInvariants.check(invoice(150000, 150000), KNOWN));
    }

    @Test
    void rejectsPostingCount() {
        PostTransactionCommand one = command(List.of(line("1100", Side.DEBIT, 150000, "USD")));
        assertEquals(LedgerRejectedException.POSTING_COUNT, reject(one).code());

        List<PostingLine> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(line("1100", Side.DEBIT, 1, "USD"));
        }
        assertEquals(LedgerRejectedException.POSTING_COUNT, reject(command(nine)).code());
    }

    @Test
    void rejectsNonPositiveAmount() {
        PostTransactionCommand command = command(List.of(
                line("1100", Side.DEBIT, 0, "USD"),
                line("4000", Side.CREDIT, 150000, "USD")));
        assertEquals(LedgerRejectedException.NON_POSITIVE_AMOUNT, reject(command).code());
    }

    @Test
    void rejectsUnknownAccount() {
        PostTransactionCommand command = command(List.of(
                line("1234", Side.DEBIT, 150000, "USD"),
                line("4000", Side.CREDIT, 150000, "USD")));
        assertEquals(LedgerRejectedException.UNKNOWN_ACCOUNT, reject(command).code());
    }

    @Test
    void rejectsMixedCurrency() {
        PostTransactionCommand command = command(List.of(
                line("1100", Side.DEBIT, 150000, "USD"),
                line("4000", Side.CREDIT, 150000, "EUR")));
        assertEquals(LedgerRejectedException.MIXED_CURRENCY, reject(command).code());
    }

    @Test
    void rejectsUnbalanced() {
        assertEquals(LedgerRejectedException.UNBALANCED, reject(invoice(150000, 150001)).code());
    }

    @Test
    void rejectsAmountOverflow() {
        PostTransactionCommand command = command(List.of(
                line("1100", Side.DEBIT, Long.MAX_VALUE, "USD"),
                line("5000", Side.DEBIT, 1, "USD"),
                line("4000", Side.CREDIT, Long.MAX_VALUE, "USD"),
                line("3000", Side.CREDIT, 1, "USD")));
        assertEquals(LedgerRejectedException.AMOUNT_OVERFLOW, reject(command).code());
    }

    private static LedgerRejectedException reject(PostTransactionCommand command) {
        return assertThrows(LedgerRejectedException.class, () -> LedgerInvariants.check(command, KNOWN));
    }

    private static PostTransactionCommand invoice(long debit, long credit) {
        return command(List.of(
                line("1100", Side.DEBIT, debit, "USD"),
                line("4000", Side.CREDIT, credit, "USD")));
    }

    private static PostTransactionCommand command(List<PostingLine> postings) {
        return new PostTransactionCommand("INV-1042", "Invoice 1042", WHEN, postings);
    }

    private static PostingLine line(String accountCode, Side side, long amountMinor, String currency) {
        return new PostingLine(accountCode, side, amountMinor, currency);
    }
}
