-- Add CANCELLED status to the payment_sessions status enum check constraint
ALTER TABLE payment_sessions DROP CONSTRAINT IF EXISTS chk_payment_status;
ALTER TABLE payment_sessions ADD CONSTRAINT chk_payment_status
    CHECK (status IN ('pending', 'completed', 'failed', 'expired', 'cancelled'));

-- Add cancelled_at timestamp column
ALTER TABLE payment_sessions ADD COLUMN cancelled_at TIMESTAMP;