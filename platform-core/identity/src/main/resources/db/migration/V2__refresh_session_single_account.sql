-- A Platform account owns at most one active refresh session.
ALTER TABLE refresh_token
    ADD CONSTRAINT refresh_token_account_id_key UNIQUE (account_id);
