-- Table to track payment sessions for API key upgrades
CREATE TABLE payment_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_key VARCHAR(255) NOT NULL,
    payment_address VARCHAR(255) NOT NULL,  -- Server's masked address for receiving payment
    receiver_nonce BYTEA NOT NULL,          -- Nonce used to generate the payment address
    status VARCHAR(50) NOT NULL DEFAULT 'pending',  -- pending, completed, failed, expired
    target_plan_id INT NOT NULL,            -- The plan they're upgrading to
    amount_required BIGINT NOT NULL,        -- Expected payment amount in wei
    token_received TEXT,                    -- Full token JSON after successful payment
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,          -- Session expiry (15 minutes from creation)
    FOREIGN KEY (api_key) REFERENCES api_keys(api_key),
    FOREIGN KEY (target_plan_id) REFERENCES pricing_plans(id)
);

-- Indexes for efficient querying
CREATE INDEX idx_payment_sessions_api_key ON payment_sessions(api_key);
CREATE INDEX idx_payment_sessions_status ON payment_sessions(status);
CREATE INDEX idx_payment_sessions_expires ON payment_sessions(expires_at);

-- Ensure only one pending payment session per API key at a time
CREATE UNIQUE INDEX idx_one_pending_payment_per_key
    ON payment_sessions(api_key)
    WHERE status = 'pending';