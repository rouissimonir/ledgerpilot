package dev.mounir.ledgerpilot.ledger;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.mounir.ledgerpilot.account.AccountRepository;
import dev.mounir.ledgerpilot.idempotency.IdempotencyRepository;
import dev.mounir.ledgerpilot.idempotency.StoredResponse;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final IdempotencyRepository idempotency;

    public LedgerService(AccountRepository accounts, LedgerRepository ledger, IdempotencyRepository idempotency) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
    }

    @Transactional
    public StoredResponse post(String key, String hash, PostTransactionCommand command) {
        List<String> codes = command.postings().stream().map(PostingLine::accountCode).distinct().toList();
        Map<String, UUID> accountIds = accounts.findIdsByCodes(codes);
        LedgerInvariants.check(command, accountIds.keySet());

        UUID transactionId = ledger.insertTransaction(
                key, command.externalRef(), command.description(), command.occurredAt());
        for (PostingLine line : command.postings()) {
            ledger.insertPosting(
                    transactionId,
                    accountIds.get(line.accountCode()),
                    line.side(),
                    line.amountMinor(),
                    line.currency());
        }

        Map<UUID, long[]> deltas = balanceDeltas(command, accountIds);
        ledger.lockBalances(deltas.keySet());
        for (Map.Entry<UUID, long[]> entry : deltas.entrySet()) {
            long[] delta = entry.getValue();
            ledger.addToBalance(entry.getKey(), delta[0], delta[1]);
        }

        String body = idempotency.insert(key, hash, transactionId, 201, transactionJson(transactionId, command));
        return new StoredResponse(201, body);
    }

    private static Map<UUID, long[]> balanceDeltas(PostTransactionCommand command, Map<String, UUID> accountIds) {
        Map<UUID, long[]> deltas = new LinkedHashMap<>();
        for (PostingLine line : command.postings()) {
            long[] delta = deltas.computeIfAbsent(accountIds.get(line.accountCode()), ignored -> new long[2]);
            if (line.side() == Side.DEBIT) {
                delta[0] = Math.addExact(delta[0], line.amountMinor());
            } else {
                delta[1] = Math.addExact(delta[1], line.amountMinor());
            }
        }
        return deltas;
    }

    private static String transactionJson(UUID transactionId, PostTransactionCommand command) {
        StringBuilder json = new StringBuilder();
        json.append("{\"transactionId\":").append(JsonText.quote(transactionId.toString()));
        json.append(",\"externalRef\":");
        if (command.externalRef() == null) {
            json.append("null");
        } else {
            json.append(JsonText.quote(command.externalRef()));
        }
        json.append(",\"occurredAt\":").append(JsonText.quote(DateTimeFormatter.ISO_INSTANT.format(command.occurredAt())));
        json.append(",\"postings\":[");
        List<PostingLine> postings = command.postings();
        for (int i = 0; i < postings.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            PostingLine line = postings.get(i);
            json.append("{\"accountCode\":").append(JsonText.quote(line.accountCode()));
            json.append(",\"side\":").append(JsonText.quote(line.side().name()));
            json.append(",\"amountMinor\":").append(line.amountMinor());
            json.append(",\"currency\":").append(JsonText.quote(line.currency()));
            json.append('}');
        }
        json.append("]}");
        return json.toString();
    }
}
