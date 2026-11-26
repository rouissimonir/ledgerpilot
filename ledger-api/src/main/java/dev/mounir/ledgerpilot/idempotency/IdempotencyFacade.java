package dev.mounir.ledgerpilot.idempotency;

import java.sql.SQLException;

import dev.mounir.ledgerpilot.ledger.LedgerService;
import dev.mounir.ledgerpilot.ledger.PostTransactionCommand;
import dev.mounir.ledgerpilot.ledger.PostingLine;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Idempotency lives outside the posting transaction. A unique-key failure is caught
 * here, after that transaction has rolled back, and the winning row is read again.
 */
@Service
public class IdempotencyFacade {

    private final IdempotencyRepository idempotency;
    private final LedgerService ledger;

    public IdempotencyFacade(IdempotencyRepository idempotency, LedgerService ledger) {
        this.idempotency = idempotency;
        this.ledger = ledger;
    }

    public StoredResponse post(String key, PostTransactionCommand command) {
        if (key == null || key.isEmpty() || key.length() > 255) {
            throw new MalformedRequestException();
        }
        requireShape(command);
        String hash = RequestHasher.sha256(command);
        return idempotency.findByKey(key)
                .map(existing -> decide(existing, hash))
                .orElseGet(() -> insertOrReplay(key, hash, command));
    }

    private StoredResponse insertOrReplay(String key, String hash, PostTransactionCommand command) {
        try {
            return ledger.post(key, hash, command);
        } catch (DataIntegrityViolationException ex) {
            if (!isUniqueViolation(ex)) {
                throw ex;
            }
            return idempotency.findByKey(key)
                    .map(existing -> decide(existing, hash))
                    .orElseThrow(() -> ex);
        }
    }

    private static StoredResponse decide(IdempotencyRecord existing, String hash) {
        if (existing.requestHash().equals(hash)) {
            return new StoredResponse(existing.responseCode(), existing.responseBody());
        }
        throw new IdempotencyConflictException();
    }

    private static boolean isUniqueViolation(DataIntegrityViolationException ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static void requireShape(PostTransactionCommand command) {
        if (command == null
                || command.description() == null
                || command.occurredAt() == null
                || command.postings() == null) {
            throw new MalformedRequestException();
        }
        for (PostingLine line : command.postings()) {
            if (line == null || line.accountCode() == null || line.side() == null || line.currency() == null) {
                throw new MalformedRequestException();
            }
        }
    }
}
