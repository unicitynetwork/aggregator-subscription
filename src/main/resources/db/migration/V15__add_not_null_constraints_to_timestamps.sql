-- Add NOT NULL constraints to timestamp fields that should never be null
-- These fields all have DEFAULT CURRENT_TIMESTAMP so existing rows will have values

-- For api_keys table: add NOT NULL to created_at and updated_at
-- Note: pricing_plan_id must remain nullable as it's used for keys without active plans
ALTER TABLE api_keys
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL;

-- For payment_sessions table: add NOT NULL to created_at
ALTER TABLE payment_sessions
    ALTER COLUMN created_at SET NOT NULL;

-- For pricing_plans table: add NOT NULL to created_at and updated_at
ALTER TABLE pricing_plans
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET NOT NULL;

-- Add comments to document the constraints
COMMENT ON COLUMN api_keys.created_at IS 'Timestamp when the API key was created. Never null.';
COMMENT ON COLUMN api_keys.updated_at IS 'Timestamp when the API key was last updated. Never null.';
COMMENT ON COLUMN payment_sessions.created_at IS 'Timestamp when the payment session was created. Never null.';
COMMENT ON COLUMN pricing_plans.created_at IS 'Timestamp when the pricing plan was created. Never null.';
COMMENT ON COLUMN pricing_plans.updated_at IS 'Timestamp when the pricing plan was last updated. Never null.';