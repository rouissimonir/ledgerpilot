package dev.mounir.ledgerpilot.ledger;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insertTransaction(String idempotencyKey, String externalRef, String description, Instant occurredAt) {
        return jdbc.queryForObject("""
                INSERT INTO transaction (idempotency_key, external_ref, description, occurred_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                (rs, row) -> rs.getObject(1, UUID.class),
                idempotencyKey,
                new SqlParameterValue(Types.VARCHAR, externalRef),
                description,
                OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
    }

    public void insertPosting(UUID transactionId, UUID accountId, Side side, long amountMinor, String currency) {
        jdbc.update("""
                INSERT INTO posting (transaction_id, account_id, side, amount_minor, currency)
                VALUES (?, ?, ?, ?, ?)
                """,
                transactionId,
                accountId,
                side.name(),
                amountMinor,
                currency);
    }

    public void lockBalances(Collection<UUID> accountIds) {
        List<UUID> ids = List.copyOf(accountIds);
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        List<UUID> locked = jdbc.query(
                "SELECT account_id FROM account_balance WHERE account_id IN (" + placeholders
                        + ") ORDER BY account_id FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class),
                ids.toArray());
        if (locked.size() != ids.size()) {
            throw new IllegalStateException("missing account balance");
        }
    }

    public void addToBalance(UUID accountId, long debitMinor, long creditMinor) {
        int updated = jdbc.update("""
                UPDATE account_balance
                SET debit_minor = debit_minor + ?, credit_minor = credit_minor + ?
                WHERE account_id = ?
                """,
                debitMinor,
                creditMinor,
                accountId);
        if (updated != 1) {
            throw new IllegalStateException("balance row not updated");
        }
    }
}
