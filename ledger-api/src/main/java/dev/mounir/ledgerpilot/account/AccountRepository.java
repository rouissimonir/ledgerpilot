package dev.mounir.ledgerpilot.account;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, UUID> findIdsByCodes(Collection<String> codes) {
        List<String> distinct = codes.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(distinct.size(), "?"));
        Map<String, UUID> ids = new HashMap<>();
        jdbc.query(
                "SELECT id, code FROM account WHERE code IN (" + placeholders + ")",
                rs -> {
                    ids.put(rs.getString("code"), rs.getObject("id", UUID.class));
                },
                distinct.toArray());
        return ids;
    }

    public Optional<AccountBalance> findBalance(String code) {
        List<AccountBalance> rows = jdbc.query("""
                SELECT a.code, b.debit_minor, b.credit_minor
                FROM account a
                JOIN account_balance b ON b.account_id = a.id
                WHERE a.code = ?
                """,
                (rs, row) -> new AccountBalance(
                        rs.getString("code"),
                        rs.getLong("debit_minor"),
                        rs.getLong("credit_minor")),
                code);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0));
    }
}
