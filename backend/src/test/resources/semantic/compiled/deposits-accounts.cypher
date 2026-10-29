// Holdings: depositsUsd, depositAccounts (Holding)
MATCH (n:Holding)
WHERE n.clientId = $p0 AND n.productType = $p1
RETURN sum(n.balanceUsd) AS m0, count(*) AS m1
// params: {"p0":"CL-0001","p1":"deposit"}

