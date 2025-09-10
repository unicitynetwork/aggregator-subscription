CREATE TYPE api_key_status AS ENUM ('active', 'revoked');

CREATE TABLE api_keys (
    api_key VARCHAR(255) PRIMARY KEY,
    pricing_plan_id INT NOT NULL,
    status api_key_status NOT NULL DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (pricing_plan_id) REFERENCES pricing_plans(id)
);

CREATE INDEX idx_api_keys_status ON api_keys(status);

-- Insert default API keys for testing
INSERT INTO api_keys (api_key, pricing_plan_id, status) VALUES
    ('supersecret', (SELECT id FROM pricing_plans WHERE name = 'standard'), 'active'),
    ('test-key-123', (SELECT id FROM pricing_plans WHERE name = 'standard'), 'active'),
    ('premium-key-abc', (SELECT id FROM pricing_plans WHERE name = 'premium'), 'active'),
    ('basic-key-xyz', (SELECT id FROM pricing_plans WHERE name = 'basic'), 'active'),
    ('dev-key-456', (SELECT id FROM pricing_plans WHERE name = 'standard'), 'active');