-- Postgres built-in UUID generator (Postgres 13+ has gen_random_uuid natively)
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Shared trigger function to auto-update updated_at on any row change.
-- Attached to individual tables in later migrations.
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;