// Revenue: revenueUsd over calendar year to date (RevenueMonth)
MATCH (n:RevenueMonth)
WHERE n.clientId = $p0 AND n.month >= datetime($start) AND n.month < datetime($end)
RETURN sum(n.totalUsd) AS m0
// params: {"p0":"CL-0001","start":"2026-01-01T00:00:00Z","end":"2026-10-01T00:00:00Z"}

// Revenue: revenueUsd over calendar year to date - a year earlier (RevenueMonth)
MATCH (n:RevenueMonth)
WHERE n.clientId = $p0 AND n.month >= datetime($start) AND n.month < datetime($end)
RETURN sum(n.totalUsd) AS m0
// params: {"p0":"CL-0001","start":"2025-01-01T00:00:00Z","end":"2025-10-01T00:00:00Z"}

