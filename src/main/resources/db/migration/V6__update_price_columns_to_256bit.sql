-- Update pricing_plans.price column
ALTER TABLE pricing_plans
ALTER COLUMN price TYPE DECIMAL(78, 0);

-- Update payment_sessions.amount_required column
ALTER TABLE payment_sessions
ALTER COLUMN amount_required TYPE DECIMAL(78, 0);

-- These are example values - adjust based on your token economics
UPDATE pricing_plans SET price = 1000000 WHERE name = 'basic';
UPDATE pricing_plans SET price = 5000000 WHERE name = 'standard';
UPDATE pricing_plans SET price = 10000000 WHERE name = 'premium';
UPDATE pricing_plans SET price = 50000000 WHERE name = 'enterprise';

-- Add NOT NULL constraint to price column now that all rows have values
ALTER TABLE pricing_plans
ALTER COLUMN price SET NOT NULL;