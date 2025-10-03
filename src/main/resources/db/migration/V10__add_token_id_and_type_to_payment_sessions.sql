-- Add token_id and token_type columns to payment_sessions table
ALTER TABLE payment_sessions
    ADD COLUMN token_id BYTEA,
    ADD COLUMN token_type BYTEA;

-- Update existing rows with null values (if any)
-- These columns will be required for new sessions going forward