package dev.mounir.ledgerpilot.ledger;

import java.time.Instant;
import java.util.List;

public record PostTransactionCommand(
        String externalRef,
        String description,
        Instant occurredAt,
        List<PostingLine> postings) {

    @Override
    public String toString() {
        return "PostTransactionCommand[externalRef=" + externalRef
                + ", occurredAt=" + occurredAt
                + ", postings=" + postings + "]";
    }
}
