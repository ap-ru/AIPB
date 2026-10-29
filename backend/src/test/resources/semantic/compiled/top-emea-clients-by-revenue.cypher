// Revenue: revenueUsd over last 12 months (RevenueMonth)
MATCH (n:RevenueMonth)
WHERE n.month >= datetime($start) AND n.month < datetime($end)
OPTIONAL MATCH (n)-[:FOR_CLIENT]->(client:Client)
WITH n, client
WHERE client.region = $p0
RETURN n.clientId AS client, client.name AS clientName, sum(n.totalUsd) AS m0
// params: {"p0":"EMEA","start":"2025-10-01T00:00:00Z","end":"2026-10-01T00:00:00Z"}

