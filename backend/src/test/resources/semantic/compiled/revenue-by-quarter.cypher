// Revenue: revenueUsd over last 8 closed quarters (RevenueMonth)
MATCH (n:RevenueMonth)
WHERE n.clientId = $p0 AND n.month >= datetime($start) AND n.month < datetime($end)
RETURN datetime.truncate('quarter', n.month) AS quarter, sum(n.totalUsd) AS m0
// params: {"p0":"CL-0001","start":"2024-10-01T00:00:00Z","end":"2026-10-01T00:00:00Z"}

