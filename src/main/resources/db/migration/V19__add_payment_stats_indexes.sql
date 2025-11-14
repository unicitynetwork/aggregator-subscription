-- Add indexes for payment statistics queries
-- These indexes optimize queries that filter by status and created_at

-- Index for completed payment queries with created_at filtering
CREATE INDEX idx_payment_sessions_status_created_at
    ON payment_sessions(status, created_at DESC);

-- Index for transaction count queries with created_at filtering
CREATE INDEX idx_payment_sessions_created_at_desc
    ON payment_sessions(created_at DESC);

-- Optional: Covering index for completed payments to avoid table lookups
CREATE INDEX idx_payment_sessions_completed_amount
    ON payment_sessions(status, created_at DESC)
    INCLUDE (amount_required)
    WHERE status = 'COMPLETED';
