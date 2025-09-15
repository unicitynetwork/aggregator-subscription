-- Add price column to pricing_plans table

ALTER TABLE pricing_plans ADD COLUMN price DECIMAL(10, 2) DEFAULT 0.00;