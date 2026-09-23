CREATE TABLE seats (
  id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id               UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  section                VARCHAR(64) NOT NULL,
  row                    VARCHAR(16) NOT NULL,
  seat_number            VARCHAR(16) NOT NULL,
  base_price_amount      BIGINT NOT NULL CHECK (base_price_amount >= 0),
  base_price_currency    VARCHAR(3) NOT NULL DEFAULT 'INR',
  created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  CONSTRAINT seats_unique_per_event UNIQUE (event_id, section, row, seat_number)
);

CREATE INDEX seats_event_id_idx ON seats(event_id);