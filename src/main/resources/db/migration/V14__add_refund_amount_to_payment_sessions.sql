-- Add refund_amount column to track pro-rated refunds for plan changes
ALTER TABLE payment_sessions ADD COLUMN refund_amount NUMERIC(78, 0);

-- Update existing rows to have 0 refund (they were all new purchases or renewals)
UPDATE payment_sessions SET refund_amount = 0 WHERE refund_amount IS NULL;

-- Make the column NOT NULL after setting default values
ALTER TABLE payment_sessions ALTER COLUMN refund_amount SET NOT NULL;

-- Add comment to document the column
COMMENT ON COLUMN payment_sessions.refund_amount IS 'Pro-rated refund amount calculated from unused portion of previous plan';