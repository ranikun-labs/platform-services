BEGIN;

INSERT INTO identity.platform_accounts (id, status, primary_email, created_at, updated_at)
VALUES (
    '00000000-0000-4000-8000-000000000024',
    'ACTIVE',
    'issue24-e2e@example.test',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO identity.password_credentials (
    account_id,
    login_id,
    password_hash,
    created_at,
    updated_at
)
VALUES (
    '00000000-0000-4000-8000-000000000024',
    'issue24-e2e-user',
    '$2b$12$AHH4H52EJRT6SStucoc1uek9jFBsXg/7fCKY4NtqQF3mP0CFD0Eim',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (account_id) DO NOTHING;

COMMIT;
