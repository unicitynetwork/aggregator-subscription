-- Remove well-known seeded API keys that were inserted by V2 migration.
-- These keys are a security risk in production deployments.
DELETE FROM api_keys WHERE api_key IN
  ('supersecret', 'test-key-123', 'premium-key-abc', 'basic-key-xyz', 'dev-key-456');
