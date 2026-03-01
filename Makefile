COMPOSE_FILE := docker-compose-full.yml
COMPOSE := USER_UID=$$(id -u) USER_GID=$$(id -g) docker compose -f $(COMPOSE_FILE)

DATA_DIRS := ./data/genesis/root ./data/genesis-root \
	./data/mongodb_shard1_data_1 ./data/mongodb_shard1_data_2 ./data/mongodb_shard1_data_3 \
	./data/mongodb_shard2_data_1 ./data/mongodb_shard2_data_2 ./data/mongodb_shard2_data_3 \
	./data/mongodb_root_data_1 ./data/mongodb_root_data_2 ./data/mongodb_root_data_3 \
	./data/redis_data

LOG_DIRS := ./logs/shard1-1 ./logs/shard1-2 ./logs/shard2-1 ./logs/shard2-2 ./logs/root-1

.PHONY: start stop restart clean-start clean logs status help test-e2e test-e2e-remote

## Start all services (creates dirs if needed)
start:
	@mkdir -p $(DATA_DIRS) && chmod -R 777 ./data
	@mkdir -p $(LOG_DIRS) && chmod -R 777 ./logs
	$(COMPOSE) up -d
	@echo "All services started. Proxy available at http://localhost:$${PROXY_PORT:-8080}"

## Stop all services (preserves data)
stop:
	$(COMPOSE) down
	@echo "All services stopped. Data preserved."

## Restart all services (preserves data)
restart:
	$(COMPOSE) down
	@mkdir -p $(DATA_DIRS) && chmod -R 777 ./data
	@mkdir -p $(LOG_DIRS) && chmod -R 777 ./logs
	$(COMPOSE) up -d
	@echo "All services restarted. Proxy available at http://localhost:$${PROXY_PORT:-8080}"

## Clean start: wipe mongo/redis data but keep BFT genesis, then start
clean-start:
	$(COMPOSE) down
	@rm -rf ./data/mongodb_shard1_data_1 ./data/mongodb_shard1_data_2 ./data/mongodb_shard1_data_3 \
		./data/mongodb_shard2_data_1 ./data/mongodb_shard2_data_2 ./data/mongodb_shard2_data_3 \
		./data/mongodb_root_data_1 ./data/mongodb_root_data_2 ./data/mongodb_root_data_3 \
		./data/redis_data
	@mkdir -p $(DATA_DIRS) && chmod -R 777 ./data
	@mkdir -p $(LOG_DIRS) && chmod -R 777 ./logs
	$(COMPOSE) up --force-recreate -d
	@echo "Clean start complete. Proxy available at http://localhost:$${PROXY_PORT:-8080}"

## Nuke everything: remove all data, volumes, and containers
clean:
	$(COMPOSE) down -v
	@rm -rf ./data ./logs
	@echo "All data, volumes, and logs removed."

## Tail logs (all services, or specify SERVICE=<name>)
logs:
	$(COMPOSE) logs -f $(SERVICE)

## Show status of all services
status:
	$(COMPOSE) ps

## Run e2e tests (auto-starts local docker stack)
test-e2e:
	./gradlew e2eTest

## Run e2e tests against a remote aggregator (no local stack)
test-e2e-remote:
	./gradlew e2eTest -PaggregatorUrl=$(AGGREGATOR_URL)

## Show this help
help:
	@echo "Usage: make <target>"
	@echo ""
	@echo "Targets:"
	@echo "  start            Start all services (creates data dirs if needed)"
	@echo "  stop             Stop all services (preserves data)"
	@echo "  restart          Restart all services (preserves data)"
	@echo "  clean-start      Wipe mongo/redis data (keep BFT genesis) and start fresh"
	@echo "  clean            Remove everything: containers, volumes, data, logs"
	@echo "  logs             Tail logs (use SERVICE=proxy to filter)"
	@echo "  status           Show service status"
	@echo "  test-e2e         Run e2e tests (auto-starts local docker stack)"
	@echo "  test-e2e-remote  Run e2e tests against remote (set AGGREGATOR_URL)"
	@echo ""
	@echo "Environment variables:"
	@echo "  PROXY_PORT      Proxy port on host (default: 8080)"
	@echo "  ADMIN_PASSWORD  Admin UI password (default: admin)"
	@echo "  LOG_LEVEL       Log level (default: INFO)"
