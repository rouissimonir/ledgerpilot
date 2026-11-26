package dev.mounir.ledgerpilot.idempotency;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> findByKey(String key) {
        List<IdempotencyRecord> rows = jdbc.query("""
                SELECT request_hash, transaction_id, response_code, response_body::text AS response_body
                FROM idempotency_record
                WHERE key = ?
                """,
                (rs, row) -> new IdempotencyRecord(
                        rs.getString("request_hash").trim(),
                        rs.getObject("transaction_id", UUID.class),
                        rs.getInt("response_code"),
                        rs.getString("response_body")),
                key);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String insert(String key, String requestHash, UUID transactionId, int responseCode, String responseBody) {
        return jdbc.queryForObject("""
                INSERT INTO idempotency_record (key, request_hash, transaction_id, response_code, response_body)
                VALUES (?, ?, ?, ?, ?::jsonb)
                RETURNING response_body::text
                """,
                (rs, row) -> rs.getString(1),
                key,
                requestHash,
                transactionId,
                responseCode,
                responseBody);
    }
}
