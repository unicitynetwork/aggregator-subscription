# Aggregator Subscription Proxy

A lightweight HTTP proxy service that forwards all requests to the aggregator service. Uses authentication with API keys in HTTP headers and applies rate limits specific to API keys.

## Features

- **Transparent HTTP proxying** - Preserves headers, request bodies, and response data
- **Virtual threads support** - Uses Java 21's virtual threads for efficient concurrency
- **Command-line configuration** - Easy to configure via CLI arguments
- **Comprehensive logging** - Structured logging with configurable levels
- **Lightweight** - Minimal dependencies using Jetty server

## Prerequisites

- Java 21 or later
- Gradle 8.x (wrapper included)
- Aggregator service running (default: http://localhost:3000)

## Quick Start

### Build and Run

```bash
# Build the project
./gradlew build

# Run with default settings (proxy on port 8080 -> localhost:3000)
./gradlew run

# Or use the convenience script
./run.sh

# Run with custom settings
./run.sh --port 9090 --target http://localhost:3000
```

### Command-Line Options

Usage: ```aggregator-subscription [options]```

To display all options, use the option ```--help```.

## Usage Examples

### Test with curl

```bash
# Test JSON-RPC endpoint
% curl -X POST http://localhost:8080/ \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer supersecret" \
  -d '{
    "jsonrpc": "2.0",
    "method": "get_inclusion_proof",
    "params": {
      "requestId": "0000981012b1c865f65d3d5523819cb34fa2c6827e792efd4579b4927144eb243122"
    },
    "id": 1
  }'
```

## Development

```bash
# Run tests
./gradlew test

```
To run within an IDE, use the main class ```com.unicity.proxy.Main```.

