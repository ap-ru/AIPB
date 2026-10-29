// Revenue: revenueUsd over last 24 months (RevenueMonth)
MATCH (n:RevenueMonth)
WHERE n.month >= datetime($start) AND n.month < datetime($end)
RETURN datetime.truncate('month', n.month) AS month, n.lob AS lob, sum(n.totalUsd) AS m0
// params: {"start":"2024-10-01T00:00:00Z","end":"2026-10-01T00:00:00Z"}

