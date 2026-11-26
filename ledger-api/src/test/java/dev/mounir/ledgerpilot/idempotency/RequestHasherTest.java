package dev.mounir.ledgerpilot.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import dev.mounir.ledgerpilot.ledger.PostTransactionCommand;
import dev.mounir.ledgerpilot.ledger.PostingLine;
import dev.mounir.ledgerpilot.ledger.Side;

import org.junit.jupiter.api.Test;

class RequestHasherTest {

    private static final String SAMPLE_DIGEST =
            "fefbe1a985afdd87f09a33b3990a19711ec9e95e471888953c62dc023d63c5a7";

    private static final String NULL_REF_DIGEST =
            "219b9ff93254e0cf98c0a7a14d0bca806f5be033e65102a694f1e81081b27cdc";

    @Test
    void pinsSampleInvoiceDigest() {
        assertEquals(SAMPLE_DIGEST, RequestHasher.sha256(sample(Instant.parse("2026-11-24T08:00:00Z"), "INV-1042")));
    }

    @Test
    void reversedPostingsHashTheSame() {
        PostTransactionCommand reversed = new PostTransactionCommand(
                "INV-1042",
                "Invoice 1042",
                Instant.parse("2026-11-24T08:00:00Z"),
                List.of(creditSales(), debitReceivable()));
        assertEquals(SAMPLE_DIGEST, RequestHasher.sha256(reversed));
    }

    @Test
    void offsetTimestampMatchesUtc() {
        Instant zulu = Instant.parse("2026-11-24T08:00:00Z");
        Instant plusOne = OffsetDateTime.parse("2026-11-24T09:00:00+01:00").toInstant();
        assertEquals(RequestHasher.sha256(sample(zulu, "INV-1042")), RequestHasher.sha256(sample(plusOne, "INV-1042")));
        assertEquals(SAMPLE_DIGEST, RequestHasher.sha256(sample(plusOne, "INV-1042")));
    }

    @Test
    void nullExternalRef() {
        String hash = RequestHasher.sha256(sample(Instant.parse("2026-11-24T08:00:00Z"), null));
        assertEquals(NULL_REF_DIGEST, hash);
        assertNotEquals(SAMPLE_DIGEST, hash);
        assertEquals(hash, RequestHasher.sha256(sample(Instant.parse("2026-11-24T08:00:00Z"), null)));
    }

    private static PostTransactionCommand sample(Instant occurredAt, String externalRef) {
        return new PostTransactionCommand(externalRef, "Invoice 1042", occurredAt, List.of(debitReceivable(), creditSales()));
    }

    private static PostingLine debitReceivable() {
        return new PostingLine("1100", Side.DEBIT, 150000, "USD");
    }

    private static PostingLine creditSales() {
        return new PostingLine("4000", Side.CREDIT, 150000, "USD");
    }
}
