// Opportunities: wonDeals, lostDeals (Opportunity)
MATCH (n:Opportunity)
RETURN sum(CASE WHEN n.stage = $p0 THEN 1 ELSE 0 END) AS m0, sum(CASE WHEN n.stage = $p1 THEN 1 ELSE 0 END) AS m1
// params: {"p0":"Closed Won","p1":"Closed Lost"}

// Holdings: productsHeld (Holding)
MATCH (n:Holding)
RETURN count(DISTINCT n.productId) AS m0
// params: {}

