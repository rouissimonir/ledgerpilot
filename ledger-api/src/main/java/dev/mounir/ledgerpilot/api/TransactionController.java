package dev.mounir.ledgerpilot.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import dev.mounir.ledgerpilot.idempotency.IdempotencyFacade;
import dev.mounir.ledgerpilot.idempotency.StoredResponse;
import dev.mounir.ledgerpilot.ledger.PostTransactionCommand;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransactionController {

    private final IdempotencyFacade idempotency;

    public TransactionController(IdempotencyFacade idempotency) {
        this.idempotency = idempotency;
    }

    @PostMapping(path = "/transactions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void post(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PostTransactionCommand command,
            HttpServletResponse response) throws IOException {
        StoredResponse stored = idempotency.post(idempotencyKey, command);
        byte[] body = stored.body().getBytes(StandardCharsets.UTF_8);
        response.setStatus(stored.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
