@echo off
REM Run Chaos Demo App with agent attached
REM Usage: run-demo.bat [port]

set PORT=8090
if not "%1"=="" set PORT=%1

echo Starting Chaos Agent on port %PORT%...
echo.

java -javaagent:target/agent-core-0.1.0-SNAPSHOT.jar=port=%PORT%,memoryPressureEnabled=true,memoryPressureMb=100,cpuBackpressureEnabled=true,cpuBackpressureIntensity=50 ^
     -cp target/agent-core-0.1.0-SNAPSHOT.jar;target/test-classes ^
     com.chaosagent.agent.ChaosDemoApp %PORT%
