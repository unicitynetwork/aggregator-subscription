CREATE TABLE pricing_plans (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    requests_per_second INT NOT NULL,
    requests_per_day INT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO pricing_plans (name, requests_per_second, requests_per_day) VALUES
    ('basic', 5, 50000),
    ('standard', 10, 100000),
    ('premium', 20, 500000),
    ('enterprise', 50, 1000000);