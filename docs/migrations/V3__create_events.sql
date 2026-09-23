CREATE TABLE events (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name         VARCHAR(255) NOT NULL,
  venue        VARCHAR(255) NOT NULL,
  start_time   TIMESTAMPTZ NOT NULL,
  end_time     TIMESTAMPTZ NOT NULL,
  status       VARCHAR(20) NOT NULL DEFAULT 'UPCOMING'
                 CHECK (status IN ('UPCOMING', 'ON_SALE', 'SOLD_OUT', 'ENDED', 'CANCELLED')),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  CONSTRAINT events_time_order CHECK (end_time > start_time)
);

CREATE INDEX events_status_idx ON events(status);
CREATE INDEX events_start_time_idx ON events(start_time);

CREATE TRIGGER events_set_updated_at
  BEFORE UPDATE ON events
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();