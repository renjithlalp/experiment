@echo off
rem Builds if needed, then starts the experiment configuration API on http://localhost:8090
rem   set PORT=9000           to change the port
rem   set TICK_SECONDS=5      to check start/end times more often
setlocal
cd /d "%~dp0"
if not exist target\experiment-config-0.1.0.jar call mvn -q package -DskipTests || exit /b 1
rem Keeps NIO's Unix-domain wake-up sockets out of a possibly unusable Windows temp folder
java -Djdk.net.unixdomain.tmpdir=target -jar target\experiment-config-0.1.0.jar
