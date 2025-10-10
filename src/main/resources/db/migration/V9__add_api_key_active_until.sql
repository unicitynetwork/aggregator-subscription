ALTER TABLE api_keys
ADD COLUMN active_until TIMESTAMP;

-- Set existing paid keys to be active for 30 days from now (for migration purposes)
-- NULL means the key has never been paid for
UPDATE api_keys
SET active_until = CURRENT_TIMESTAMP + INTERVAL '30 days'
WHERE pricing_plan_id IS NOT NULL;