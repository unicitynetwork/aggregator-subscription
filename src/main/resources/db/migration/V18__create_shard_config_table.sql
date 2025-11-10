CREATE TABLE shard_config (
    id SERIAL PRIMARY KEY,
    config_json JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255) NOT NULL
);

-- Insert default test config
INSERT INTO shard_config (config_json, created_by) VALUES (
    '{"version": 1, "shards": [{"id": "0", "suffix": "1", "url": "https://aggregator-test.unicity.network"}]}',
    'system'
);
