// Revenue: revenueUsd over last 12 months (RevenueMonth)
MATCH (n:RevenueMonth)
WHERE n.clientId = $p0 AND n.month >= datetime($start) AND n.month < datetime($end)
RETURN sum(n.totalUsd) AS m0
// params: {"p0":"CL-0001","start":"2025-10-01T00:00:00Z","end":"2026-10-01T00:00:00Z"}

// Clients: walletSizeUsd (Client)
MATCH (n:Client)
WHERE n.clientId = $p0
RETURN sum(n.walletSizeUsd) AS m0
// params: {"p0":"CL-0001"}

