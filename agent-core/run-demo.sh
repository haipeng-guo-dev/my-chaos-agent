#!/bin/bash
# Run Chaos Demo App with agent attached
# Usage: ./run-demo.sh [port]

PORT=${1:-8090}

echo "Starting Chaos Agent on port $PORT..."
echo

java -javaagent:target/agent-core-0.1.0-SNAPSHOT.jar=port=$PORT,memoryPressureEnabled=true,memoryPressureMb=100,cpuBackpressureEnabled=true,cpuBackpressureIntensity=50 \
     -cp "target/agent-core-0.1.0-SNAPSHOT.jar:target/test-classes" \
     com.chaosagent.agent.ChaosDemoApp