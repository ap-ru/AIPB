// Signals records (Signal)
MATCH (n:Signal)
WHERE n.clientId = $p0 AND (n.stage IS NULL OR n.stage <> $p1) AND n.category = $p2
OPTIONAL MATCH (n)-[:CONCERNS]->(covenant:Covenant)
OPTIONAL MATCH (n)-[:CONCERNS]->(:Covenant)<-[:HAS_COVENANT]-(covenantFacility:Holding)
OPTIONAL MATCH (n)-[:CONCERNS]->(holding:Holding)
OPTIONAL MATCH (n)-[:CONCERNS]->(deal:Opportunity)
OPTIONAL MATCH (n)-[:CONCERNS]->(:Holding)-[:OF_PRODUCT]->(holdingProduct:Product)
OPTIONAL MATCH (n)-[:CONCERNS]->(:Covenant)<-[:HAS_COVENANT]-(:Holding)-[:OF_PRODUCT]->(covenantProduct:Product)
OPTIONAL MATCH (n)-[:CONCERNS]->(:Opportunity)-[:FOR_PRODUCT]->(dealProduct:Product)
RETURN n.title AS title, (CASE WHEN covenant.name IS NOT NULL THEN 'Covenant: ' + covenant.name + ' on ' + covenantFacility.productName WHEN holding.productName IS NOT NULL THEN 'Holding: ' + holding.productName WHEN deal.name IS NOT NULL THEN 'Deal: ' + deal.name END) AS concerns, (coalesce(holdingProduct.name, covenantProduct.name, dealProduct.name)) AS trailProduct, covenant.headroomPct AS covenantHeadroomPct, n.severity AS severity, n.detectedAt AS detectedAt
ORDER BY severity IS NULL DESC, severity, detectedAt IS NULL, detectedAt DESC, n.signalId
LIMIT $limit
// params: {"p0":"CL-0001","p1":"Resolved","p2":"Risk","limit":11}

