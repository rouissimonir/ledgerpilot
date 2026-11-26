package dev.mounir.ledgerpilot.api;

import dev.mounir.ledgerpilot.account.AccountBalance;
import dev.mounir.ledgerpilot.account.AccountNotFoundException;
import dev.mounir.ledgerpilot.account.AccountRepository;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {

    private final AccountRepository accounts;

    public AccountController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @GetMapping(path = "/accounts/{code}/balance", produces = MediaType.APPLICATION_JSON_VALUE)
    public AccountBalance balance(@PathVariable("code") String code) {
        return accounts.findBalance(code).orElseThrow(AccountNotFoundException::new);
    }
}
