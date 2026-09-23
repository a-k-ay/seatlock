CREATE TABLE bookings (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id       UUID NOT NULL REFERENCES events(id),
  seat_id        UUID NOT NULL REFERENCES seats(id),
  user_id        UUID NOT NULL REFERENCES users(id),
  hold_id        UUID NOT NULL REFERENCES holds(id),
  status         VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED'
                   CHECK (status IN ('CONFIRMED', 'CANCELLED', 'REFUNDED')),
  total_amount   BIGINT NOT NULL CHECK (total_amount >= 0),
  currency       VARCHAR(3) NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  CONSTRAINT bookings_hold_unique UNIQUE (hold_id)
);

CREATE INDEX bookings_user_id_idx ON bookings(user_id);
CREATE INDEX bookings_event_id_idx ON bookings(event_id);

-- Prevent two CONFIRMED bookings on the same seat (belt-and-braces alongside holds).
CREATE UNIQUE INDEX one_confirmed_booking_per_seat
  ON bookings (seat_id)
  WHERE status = 'CONFIRMED';

CREATE TRIGGER bookings_set_updated_at
  BEFORE UPDATE ON bookings
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();