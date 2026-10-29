// Covenants records (Covenant)
MATCH (n:Covenant)
WHERE n.clientId = $p0
OPTIONAL MATCH (n)<-[:HAS_COVENANT]-(holding:Holding)
RETURN n.name AS name, holding.productName AS facility, n.actual AS actual, n.threshold AS threshold, n.headroomPct AS headroomPct, n.status AS status, n.unit AS unit
ORDER BY headroomPct IS NULL DESC, headroomPct, n.covenantId
LIMIT $limit
// params: {"p0":"CL-0001","limit":51}

