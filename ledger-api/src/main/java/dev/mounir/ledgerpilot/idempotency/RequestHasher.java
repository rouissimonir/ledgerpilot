package dev.mounir.ledgerpilot.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import dev.mounir.ledgerpilot.ledger.JsonText;
import dev.mounir.ledgerpilot.ledger.PostTransactionCommand;
import dev.mounir.ledgerpilot.ledger.PostingLine;

public final class RequestHasher {

    private RequestHasher() {
    }

    public static String sha256(PostTransactionCommand command) {
        byte[] digest = sha256(canonicalJson(command).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static String canonicalJson(PostTransactionCommand command) {
        List<PostingLine> postings = command.postings();
        List<Integer> order = new ArrayList<>(postings.size());
        for (int i = 0; i < postings.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator
                .comparing((Integer i) -> postings.get(i).accountCode())
                .thenComparing(i -> postings.get(i).side().name())
                .thenComparingInt(i -> i));

        StringBuilder json = new StringBuilder();
        json.append("{\"description\":").append(JsonText.quote(command.description()));
        json.append(",\"externalRef\":");
        if (command.externalRef() == null) {
            json.append("null");
        } else {
            json.append(JsonText.quote(command.externalRef()));
        }
        json.append(",\"occurredAt\":")
                .append(JsonText.quote(DateTimeFormatter.ISO_INSTANT.format(command.occurredAt())));
        json.append(",\"postings\":[");
        for (int n = 0; n < order.size(); n++) {
            if (n > 0) {
                json.append(',');
            }
            appendPosting(json, postings.get(order.get(n)));
        }
        json.append("]}");
        return json.toString();
    }

    private static void appendPosting(StringBuilder json, PostingLine line) {
        json.append("{\"accountCode\":").append(JsonText.quote(line.accountCode()));
        json.append(",\"amountMinor\":").append(line.amountMinor());
        json.append(",\"currency\":").append(JsonText.quote(line.currency()));
        json.append(",\"side\":").append(JsonText.quote(line.side().name()));
        json.append('}');
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
