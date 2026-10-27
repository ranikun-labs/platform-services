\getenv carelog_e2e_account_a CARELOG_E2E_ACCOUNT_A
\getenv carelog_e2e_password_a CARELOG_E2E_PASSWORD_A
\getenv g4_e2e_password_b G4_E2E_PASSWORD_B
CREATE EXTENSION IF NOT EXISTS pgcrypto;
-- Synthetic DB fixtures only, in a disposable Carelog PostgreSQL container.
-- Independent Identity DB seed is scripts/e2e/seed.sql.
BEGIN;
INSERT INTO platform_accounts(id, status, primary_email, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000024', 'ACTIVE', :'carelog_e2e_account_a', now(), now());
INSERT INTO users(created_at, updated_at, organization_id, public_id, user_id, email,
                  password, name, role, manager_type, account_id)
VALUES (now(), now(), '00000000-0000-4000-8000-000000000025',
        '00000000-0000-4000-8000-000000000024', 'issue24-e2e-user',
        :'carelog_e2e_account_a', crypt(:'carelog_e2e_password_a', gen_salt('bf', 10)), 'Synthetic Manager A',
        'MANAGER', 'PHYSICAL_THERAPIST', '00000000-0000-4000-8000-000000000024');
INSERT INTO platform_accounts(id, status, primary_email, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000026', 'ACTIVE', 'g4-local-b@example.test', now(), now());
INSERT INTO users(created_at, updated_at, organization_id, public_id, user_id, email,
                  password, name, role, manager_type, account_id)
VALUES (now(), now(), '00000000-0000-4000-8000-000000000027',
        '00000000-0000-4000-8000-000000000026', 'issue24-e2e-user-b',
        'g4-local-b@example.test', crypt(:'g4_e2e_password_b', gen_salt('bf', 10)), 'Synthetic Manager B',
        'MANAGER', 'PHYSICAL_THERAPIST', '00000000-0000-4000-8000-000000000026');
COMMIT;
