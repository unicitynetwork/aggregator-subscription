@echo off

REM Build the project if needed
if not exist "build\classes" (
    echo Building project...
    gradlew.bat build
)

REM Run with Gradle (includes all dependencies)
echo Starting Aggregator Subscription Proxy...
gradlew.bat run --args="%*"