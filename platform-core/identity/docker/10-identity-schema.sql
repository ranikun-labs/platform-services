-- Local Compose bootstrap only. Production schema and role provisioning remain owner-controlled.
CREATE SCHEMA IF NOT EXISTS identity AUTHORIZATION CURRENT_USER;
