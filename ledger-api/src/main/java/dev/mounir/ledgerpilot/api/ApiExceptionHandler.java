package dev.mounir.ledgerpilot.api;

import dev.mounir.ledgerpilot.account.AccountNotFoundException;
import dev.mounir.ledgerpilot.idempotency.IdempotencyConflictException;
import dev.mounir.ledgerpilot.idempotency.MalformedRequestException;
import dev.mounir.ledgerpilot.ledger.LedgerRejectedException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(LedgerRejectedException.class)
    public ResponseEntity<ApiError> rejected(LedgerRejectedException ex) {
        return ResponseEntity.unprocessableEntity().body(new ApiError(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> conflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("IDEMPOTENCY_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ApiError> unknownAccount(AccountNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            MalformedRequestException.class
    })
    public ResponseEntity<ApiError> malformed(Exception ignored) {
        return ResponseEntity.badRequest().body(new ApiError("MALFORMED_REQUEST", "malformed transaction request"));
    }
}
