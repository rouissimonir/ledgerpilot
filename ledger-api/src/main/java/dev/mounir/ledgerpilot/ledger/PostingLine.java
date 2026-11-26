package dev.mounir.ledgerpilot.ledger;

public record PostingLine(String accountCode, Side side, long amountMinor, String currency) {
}
