-- Add index to optimize shard config polling queries
-- The query pattern is: ORDER BY created_at DESC, id DESC LIMIT 1
-- Since id is SERIAL (monotonically increasing), it's highly correlated with created_at
-- However, we create a composite index to match the exact query order for optimal performance

CREATE INDEX IF NOT EXISTS idx_shard_config_latest
    ON shard_config (created_at DESC, id DESC);

-- Alternative (simpler but requires query change): CREATE INDEX IF NOT EXISTS idx_shard_config_id_desc ON shard_config (id DESC);
-- This would be slightly faster and smaller, but requires changing the query to: ORDER BY id DESC LIMIT 1
