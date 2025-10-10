-- Rename completion_timestamp to completion_request_timestamp for clarity
-- The timestamp indicates when the completion request was received, not when payment completed

ALTER TABLE payment_sessions
    RENAME COLUMN completion_timestamp TO completion_request_timestamp;

-- Rename the index as well
DROP INDEX IF EXISTS idx_payment_sessions_completion_timestamp;
CREATE INDEX idx_payment_sessions_completion_request_timestamp
    ON payment_sessions(completion_request_timestamp)
    WHERE completion_request_timestamp IS NOT NULL;

COMMENT ON COLUMN payment_sessions.completion_request_timestamp IS 'When the completion request was first received (not when payment completed)';
