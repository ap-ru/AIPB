#!/usr/bin/env bash
# Brings up MongoDB, the Spring Boot API and the React UI.
set -euo pipefail
cd "$(dirname "$0")"
[ -f .env ] || cp .env.example .env
docker compose up -d --build
echo ""
echo "AIPB is starting..."
echo "  UI       http://localhost:3080"
echo "  API      http://localhost:8080/api/status"
echo "  MongoDB  mongodb://localhost:27017/aipb"
echo ""
echo "Logs:  docker compose logs -f backend   ·   bank data: docker compose logs bank-seed"
