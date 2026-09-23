CREATE TABLE holds (
  id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id                 UUID NOT NULL REFERENCES events(id),
  seat_id                  UUID NOT NULL REFERENCES seats(id),
  user_id                  UUID NOT NULL REFERENCES users(id),
  status                   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                             CHECK (status IN ('ACTIVE', 'EXPIRED', 'CONFIRMED')),
  price_at_hold_amount     BIGINT NOT NULL CHECK (price_at_hold_amount >= 0),
  price_at_hold_currency   VARCHAR(3) NOT NULL,
  expires_at               TIMESTAMPTZ NOT NULL,
  created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- The critical constraint from ADR-006: only one ACTIVE hold per seat.
CREATE UNIQUE INDEX one_active_hold_per_seat
  ON holds (seat_id)
  WHERE status = 'ACTIVE';

CREATE INDEX holds_user_id_idx ON holds(user_id);
CREATE INDEX holds_expires_at_idx ON holds(expires_at) WHERE status = 'ACTIVE';

CREATE TRIGGER holds_set_updated_at
  BEFORE UPDATE ON holds
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();