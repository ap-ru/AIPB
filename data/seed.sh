#!/bin/bash
# Starts Neo4j writable in the background (the image's own entrypoint: auth, settings from NEO4J_* variables), loads
# the bank graph through it, then stops it cleanly. Exits with the loader's status.
#
# The `neo4j` service starts only after this has succeeded, so when it is already running the graph is loaded: skip
# (a reload needs it stopped first - it holds the store - so BANK_RESET=true with it running is an error).
set -uo pipefail
if node -e "const s=require('net').connect(7687,'${SEED_MAIN_HOST:-neo4j}');s.setTimeout(2000);s.on('connect',()=>process.exit(0));s.on('error',()=>process.exit(1));s.on('timeout',()=>process.exit(1))"; then
  if [ "${BANK_RESET:-false}" = "true" ]; then
    echo "the neo4j service is running and holds the graph's store: docker compose stop neo4j, then run the seed with BANK_RESET=true"
    exit 1
  fi
  echo "the neo4j service is running, so the graph is already loaded - skipping. To reload: docker compose stop neo4j, then BANK_RESET=true."
  exit 0
fi
unset NEO4J_server_databases_read__only
/startup/docker-entrypoint.sh neo4j > /tmp/neo4j.log 2>&1 &
server=$!
node /seed/build-bank.js /tmp/bank.json && node /seed/load-graph.js /tmp/bank.json
status=$?
kill -TERM "$server" 2>/dev/null
wait "$server"
[ "$status" -eq 0 ] || tail -20 /tmp/neo4j.log
exit "$status"
