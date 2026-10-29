// Holdings records over next 90 days (by maturity date) (Holding)
MATCH (n:Holding)
WHERE n.maturityDate >= datetime($start) AND n.maturityDate < datetime($end)
RETURN n.productName AS productName, (coalesce(n.drawnUsd, n.balanceUsd, n.notionalUsd)) AS amountUsd, n.maturityDate AS maturityDate
ORDER BY maturityDate IS NULL DESC, maturityDate, n.holdingId
LIMIT $limit
// params: {"start":"2026-10-16T00:00:00Z","end":"2027-01-14T00:00:00Z","limit":51}

