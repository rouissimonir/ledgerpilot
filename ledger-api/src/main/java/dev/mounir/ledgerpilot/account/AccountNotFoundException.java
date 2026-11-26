package dev.mounir.ledgerpilot.account;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException() {
        super("unknown account");
    }
}
