-- Add a CHECK constraint to ensure only valid payment statuses are used
-- Valid statuses: pending, completed, failed, expired

-- Drop existing constraint if any (for idempotency)
ALTER TABLE payment_sessions DROP CONSTRAINT IF EXISTS chk_payment_status;

-- Add the CHECK constraint
ALTER TABLE payment_sessions
ADD CONSTRAINT chk_payment_status
CHECK (status IN ('pending', 'completed', 'failed', 'expired'));

-- Add a comment to document the constraint
COMMENT ON COLUMN payment_sessions.status IS 'Payment session status. Must be one of: pending, completed, failed, expired';