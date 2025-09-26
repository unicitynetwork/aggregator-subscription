-- Allow NULL pricing_plan_id for API keys that haven't paid yet
-- These keys will not be able to make any requests until they pay for a plan

-- Remove the NOT NULL constraint from pricing_plan_id
ALTER TABLE api_keys
ALTER COLUMN pricing_plan_id DROP NOT NULL;

-- Update the foreign key constraint to allow NULL values (should already allow by default)
-- No action needed as foreign keys allow NULL by default

-- Add a comment to document this behavior
COMMENT ON COLUMN api_keys.pricing_plan_id IS 'Pricing plan ID. NULL means the key has no active plan and cannot make requests until payment is completed.';