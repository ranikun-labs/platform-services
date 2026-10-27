\getenv carelog_e2e_account_a CARELOG_E2E_ACCOUNT_A
\getenv carelog_e2e_password_a CARELOG_E2E_PASSWORD_A
\getenv g4_e2e_password_b G4_E2E_PASSWORD_B
CREATE EXTENSION IF NOT EXISTS pgcrypto;
BEGIN;

INSERT INTO identity.platform_accounts (id, status, primary_email, created_at, updated_at)
VALUES (
    '00000000-0000-4000-8000-000000000024',
    'ACTIVE',
    :'carelog_e2e_account_a',
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
    :'carelog_e2e_account_a',
    crypt(:'carelog_e2e_password_a', gen_salt('bf', 10)),
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (account_id) DO NOTHING;

INSERT INTO identity.platform_accounts (id, status, primary_email, created_at, updated_at)
VALUES (
    '00000000-0000-4000-8000-000000000026',
    'ACTIVE',
    'issue24-e2e-b@example.test',
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
    '00000000-0000-4000-8000-000000000026',
    'issue24-e2e-user-b',
    crypt(:'g4_e2e_password_b', gen_salt('bf', 10)),
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (account_id) DO NOTHING;

COMMIT;
