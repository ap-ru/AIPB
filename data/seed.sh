#!/bin/bash
# Starts Neo4j writable in the background (the image's own entrypoint: auth, settings from NEO4J_* variables), loads
# the bank graph through it, then stops it cleanly. Exits with the loader's status.
set -uo pipefail
unset NEO4J_server_databases_read__only
/startup/docker-entrypoint.sh neo4j > /tmp/neo4j.log 2>&1 &
server=$!
node /seed/build-bank.js /tmp/bank.json && node /seed/load-graph.js /tmp/bank.json
status=$?
kill -TERM "$server" 2>/dev/null
wait "$server"
[ "$status" -eq 0 ] || tail -20 /tmp/neo4j.log
exit "$status"
