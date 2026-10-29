// Holdings: exposureUsd, committedUsd (Holding)
MATCH (n:Holding)
WHERE n.clientId = $p0 AND n.productType = $p1
RETURN sum(n.drawnUsd) AS m0, sum(n.committedUsd) AS m1
// params: {"p0":"CL-0001","p1":"credit"}

