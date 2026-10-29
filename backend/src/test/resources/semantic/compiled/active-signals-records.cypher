// Signals records (Signal)
MATCH (n:Signal)
WHERE n.clientId = $p0 AND (n.stage IS NULL OR n.stage <> $p1)
RETURN n.title AS title, (n.category + ' · ' + n.type + ' · ' + n.stage) AS subtitle, n.severity AS severity, n.detectedAt AS detectedAt
ORDER BY severity IS NULL DESC, severity, detectedAt IS NULL, detectedAt DESC, n.signalId
LIMIT $limit
// params: {"p0":"CL-0001","p1":"Resolved","limit":11}

