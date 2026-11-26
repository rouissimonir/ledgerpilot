package dev.mounir.ledgerpilot.idempotency;

import java.util.UUID;

public record IdempotencyRecord(String requestHash, UUID transactionId, int responseCode, String responseBody) {
}
