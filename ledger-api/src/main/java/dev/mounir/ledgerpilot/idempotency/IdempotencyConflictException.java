package dev.mounir.ledgerpilot.idempotency;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("idempotency key reused with a different request");
    }
}
