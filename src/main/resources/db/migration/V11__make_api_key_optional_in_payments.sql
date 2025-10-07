-- Make api_key nullable in payment_sessions to support creating new keys during payment
ALTER TABLE payment_sessions
    ALTER COLUMN api_key DROP NOT NULL;

-- Add a column to track if this session should create a new API key upon completion
ALTER TABLE payment_sessions
    ADD COLUMN should_create_key BOOLEAN DEFAULT FALSE;

-- Update the foreign key constraint to handle NULL api_keys
ALTER TABLE payment_sessions
    DROP CONSTRAINT payment_sessions_api_key_fkey,
    ADD CONSTRAINT payment_sessions_api_key_fkey
    FOREIGN KEY (api_key) REFERENCES api_keys(api_key) ON DELETE CASCADE;