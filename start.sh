#!/usr/bin/env bash
# Brings up Neo4j (the bank graph, seeded once), MongoDB (app state), the Spring Boot API and the React UI.
set -euo pipefail
cd "$(dirname "$0")"
[ -f .env ] || cp .env.example .env
docker compose up -d --build
echo ""
echo "AIPB is starting..."
echo "  UI       http://localhost:3080"
echo "  API      http://localhost:8080/api/status"
echo "  Neo4j    http://localhost:7474 (bank graph, read-only)"
echo "  MongoDB  mongodb://localhost:27017/aipb"
echo ""
echo "Logs:  docker compose logs -f backend   ·   bank graph: docker compose logs graph-seed"
