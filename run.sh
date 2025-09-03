#!/bin/bash

# Build the project if needed
if [ ! -d "build/classes" ]; then
    echo "Building project..."
    ./gradlew build
fi

# Run with Gradle (includes all dependencies)
echo "Starting Aggregator Subscription Proxy..."
./gradlew run --args="$*"