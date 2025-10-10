-- Remove token_id and token_type columns from payment_sessions table
-- These are no longer needed as tokenType is hardcoded and tokenId is extracted from the token during completion

ALTER TABLE payment_sessions DROP COLUMN IF EXISTS token_id;
ALTER TABLE payment_sessions DROP COLUMN IF EXISTS token_type;