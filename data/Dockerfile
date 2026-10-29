# The one-shot graph seed. It runs its own Neo4j on the graph's volume, writable, while it generates the bank data
# (build-bank.js) and loads it (load-graph.js), then shuts it down; the `neo4j` service only ever opens the database
# read-only (server.databases.read_only), after this has finished.
FROM node:20.18.1-bookworm-slim AS build
WORKDIR /seed
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund
COPY *.js *.json seed.sh ./

FROM neo4j:5.26.32-community
COPY --from=build /usr/local/bin/node /usr/local/bin/node
COPY --from=build /seed /seed
WORKDIR /seed
ENTRYPOINT ["/seed/seed.sh"]
