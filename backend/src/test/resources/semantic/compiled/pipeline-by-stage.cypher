// Opportunities: pipelineUsd, openDeals, weightedPipelineUsd (Opportunity)
MATCH (n:Opportunity)
WHERE n.clientId = $p0 AND (n.stage IS NULL OR NOT n.stage IN $p1)
RETURN n.stage AS dealStage, sum(n.expectedRevenueUsd) AS m0, count(*) AS m1, sum((n.expectedRevenueUsd * n.probability / 100.0)) AS m2
// params: {"p0":"CL-0001","p1":["Closed Won","Closed Lost"]}

