CREATE TABLE account (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL,
    type        TEXT NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE')),
    normal_side TEXT NOT NULL CHECK (normal_side IN ('DEBIT', 'CREDIT'))
);

CREATE TABLE transaction (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key     TEXT NOT NULL UNIQUE,
    external_ref        TEXT,
    description         TEXT NOT NULL,
    occurred_at         TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    corrects_finding_id UUID
);

CREATE INDEX transaction_external_ref_idx ON transaction (external_ref);

CREATE TABLE posting (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transaction (id),
    account_id     UUID NOT NULL REFERENCES account (id),
    side           TEXT NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount_minor   BIGINT NOT NULL CHECK (amount_minor > 0),
    currency       CHAR(3) NOT NULL
);

CREATE INDEX posting_transaction_idx ON posting (transaction_id);
CREATE INDEX posting_account_idx ON posting (account_id);

CREATE TABLE account_balance (
    account_id   UUID PRIMARY KEY REFERENCES account (id),
    debit_minor  BIGINT NOT NULL DEFAULT 0 CHECK (debit_minor >= 0),
    credit_minor BIGINT NOT NULL DEFAULT 0 CHECK (credit_minor >= 0)
);

CREATE TABLE idempotency_record (
    key              TEXT PRIMARY KEY,
    request_hash     CHAR(64) NOT NULL,
    transaction_id   UUID NOT NULL REFERENCES transaction (id),
    response_code    INT NOT NULL,
    response_body    JSONB NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    topic        TEXT NOT NULL,
    aggregate_id TEXT NOT NULL,
    payload      JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX outbox_unpublished_idx ON outbox (created_at) WHERE published_at IS NULL;
