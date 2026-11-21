-- Password changes invalidate old registration replay even when made outside JPA.
ALTER TABLE password_credentials ADD COLUMN credential_revision bigint NOT NULL DEFAULT 0;

CREATE FUNCTION bump_credential_revision_on_password_change() RETURNS trigger AS $$
BEGIN
    IF NEW.password_hash IS DISTINCT FROM OLD.password_hash THEN
        NEW.credential_revision := OLD.credential_revision + 1;
    ELSIF NEW.credential_revision < OLD.credential_revision THEN
        RAISE EXCEPTION 'credential revision cannot decrease';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER password_credential_revision_trigger
    BEFORE UPDATE ON password_credentials FOR EACH ROW
    EXECUTE FUNCTION bump_credential_revision_on_password_change();

-- Internal registration operation. This migration does not enable a public route.
CREATE TABLE carelog_registration_operation (
    operation_key uuid PRIMARY KEY,
    account_id uuid NOT NULL UNIQUE REFERENCES platform_accounts(id),
    display_name text NOT NULL,
    original_login_id varchar(255) NOT NULL,
    original_email varchar(255) NOT NULL,
    original_credential_revision bigint NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('PENDING', 'COMPLETED', 'CONFLICT')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
