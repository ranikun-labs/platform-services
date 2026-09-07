ALTER TABLE refresh_token
    ADD CONSTRAINT refresh_token_account_id_key UNIQUE (account_id);
