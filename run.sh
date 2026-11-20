#!/usr/bin/env sh
# Builds if needed, then starts the experiment configuration API on http://localhost:8090
#   PORT=9000 ./run.sh          to change the port
#   TICK_SECONDS=5 ./run.sh     to check start/end times more often
set -e
cd "$(dirname "$0")"
[ -f target/experiment-config-0.1.0.jar ] || mvn -q package -DskipTests
exec java -Djdk.net.unixdomain.tmpdir=target -jar target/experiment-config-0.1.0.jar
