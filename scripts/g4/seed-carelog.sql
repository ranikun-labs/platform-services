-- Synthetic DB fixtures only, in a disposable Carelog PostgreSQL container.
-- Independent Identity DB seed is scripts/e2e/seed.sql.
BEGIN;
INSERT INTO platform_accounts(id, status, primary_email, created_at, updated_at)
VALUES ('00000000-0000-4000-8000-000000000024', 'ACTIVE', 'g4-local@example.test', now(), now());
INSERT INTO users(created_at, updated_at, organization_id, public_id, user_id, email,
                  password, name, role, manager_type, account_id)
VALUES (now(), now(), '00000000-0000-4000-8000-000000000025',
        '00000000-0000-4000-8000-000000000024', 'issue24-e2e-user',
        'g4-local@example.test', 'synthetic-no-product-password-auth', 'Synthetic Manager A',
        'MANAGER', 'PHYSICAL_THERAPIST', '00000000-0000-4000-8000-000000000024');
COMMIT;
