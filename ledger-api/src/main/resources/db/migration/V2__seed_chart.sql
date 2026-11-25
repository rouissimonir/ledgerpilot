INSERT INTO account (code, name, type, normal_side) VALUES
    ('1000', 'Cash', 'ASSET', 'DEBIT'),
    ('1100', 'Accounts receivable', 'ASSET', 'DEBIT'),
    ('2000', 'Accounts payable', 'LIABILITY', 'CREDIT'),
    ('3000', 'Owner equity', 'EQUITY', 'CREDIT'),
    ('4000', 'Sales revenue', 'INCOME', 'CREDIT'),
    ('5000', 'Operating expense', 'EXPENSE', 'DEBIT'),
    ('9999', 'Suspense', 'ASSET', 'DEBIT');

INSERT INTO account_balance (account_id)
SELECT id FROM account;
