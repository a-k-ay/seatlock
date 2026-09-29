ALTER TABLE holds DROP CONSTRAINT holds_status_check;

ALTER TABLE holds ADD CONSTRAINT holds_status_check
    CHECK (status IN ('ACTIVE', 'EXPIRED', 'CONFIRMED', 'CANCELLED'));