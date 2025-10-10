-- Add request_id and completion request tracking to payment_sessions
-- This enables:
-- 1. Early commit of payment requests before blockchain processing
-- 2. Prevention of double-spending the same token across multiple sessions
-- 3. Audit trail for manual recovery if needed

ALTER TABLE payment_sessions
    ADD COLUMN request_id VARCHAR(255),
    ADD COLUMN completion_request_json TEXT,
    ADD COLUMN completion_timestamp TIMESTAMP;

-- Create unique index to prevent the same request_id being used for multiple payments
-- This prevents double-spending: one token can only pay for one session
CREATE UNIQUE INDEX idx_payment_sessions_request_id
    ON payment_sessions(request_id)
    WHERE request_id IS NOT NULL;

-- Index for querying completion attempts
CREATE INDEX idx_payment_sessions_completion_timestamp
    ON payment_sessions(completion_timestamp)
    WHERE completion_timestamp IS NOT NULL;

COMMENT ON COLUMN payment_sessions.request_id IS 'Blockchain request ID from the transfer commitment - ensures one token can only pay once';
COMMENT ON COLUMN payment_sessions.completion_request_json IS 'Full payment completion request JSON stored early for recovery';
COMMENT ON COLUMN payment_sessions.completion_timestamp IS 'When the completion request was first received';
