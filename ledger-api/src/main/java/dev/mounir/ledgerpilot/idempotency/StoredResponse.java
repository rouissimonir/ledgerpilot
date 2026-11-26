package dev.mounir.ledgerpilot.idempotency;

public record StoredResponse(int status, String body) {
}
