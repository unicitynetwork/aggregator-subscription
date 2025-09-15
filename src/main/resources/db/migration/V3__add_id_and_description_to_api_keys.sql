-- Add id column as primary key and description column to api_keys table

-- First, drop the existing primary key constraint
ALTER TABLE api_keys DROP CONSTRAINT api_keys_pkey;

-- Add id column with auto-increment
ALTER TABLE api_keys ADD COLUMN id BIGSERIAL;

-- Add description column
ALTER TABLE api_keys ADD COLUMN description TEXT;

-- Make id the new primary key
ALTER TABLE api_keys ADD PRIMARY KEY (id);

-- Create unique index on api_key since it's no longer the primary key
CREATE UNIQUE INDEX idx_api_keys_api_key ON api_keys(api_key);

-- Update the foreign key constraint name for clarity (optional)
-- The existing constraint should still work as is