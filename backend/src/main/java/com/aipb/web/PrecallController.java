package com.aipb.web;

import com.aipb.source.SourceReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pre-call brief: everything about one client, read straight from the bank graph (Client, Holding, Activity ...
 * and the pre-call labels). A static page - no widgets, no assistant; the page arranges it.
 */
@RestController
@RequestMapping("/api/precall")
@RequiredArgsConstructor
public class PrecallController {

    private final SourceReader source;

    /** Every client, for the client list, with the date of its latest logged interaction (lastInteractionDate). */
    @GetMapping("/clients")
    public JsonNode clients() {
        JsonNode clients = source.find("Client", null, List.of("clientId", "name", "industry", "segment", "tier", "region", "city", "relationshipManager"),
                Map.of("name", 1), null).documents();
        Map<String, JsonNode> last = new HashMap<>();
        source.cypher("MATCH (a:Activity)-[:FOR_CLIENT]->(c:Client) RETURN c.clientId AS clientId, max(a.date) AS last", Map.of())
                .documents().forEach(d -> last.put(d.path("clientId").asText(), d.get("last")));
        clients.forEach(c -> ((ObjectNode) c).set("lastInteractionDate", last.get(c.path("clientId").asText())));
        return clients;
    }

    @GetMapping("/{clientId}")
    public Map<String, Object> brief(@PathVariable String clientId) {
        JsonNode client = first(source.find("Client", Map.of("_id", clientId), null, null, 1).documents());
        if (client == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no client " + clientId);
        Map<String, Object> byClient = Map.of("clientId", clientId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOfDate", source.asOfDate());
        m.put("client", client);
        m.put("profile", first(source.find("CompanyProfile", byClient, null, null, 1).documents()));
        m.put("ownership", find("OwnershipStake", byClient, Map.of("ownershipPct", -1)));
        m.put("holdings", find("Holding", byClient, sorted("lob", 1, "productName", 1)));
        m.put("products", find("CitizensProduct", byClient, Map.of("_id", 1)));
        m.put("covenants", find("Covenant", byClient, Map.of("headroomPct", 1)));
        m.put("balanceHistory", find("BalanceSnapshot", byClient, Map.of("asOfDate", 1)));
        m.put("payments", find("PaymentFlow", byClient, Map.of("amountUsd", -1)));
        m.put("revenue", find("RevenueMonth", byClient, Map.of("month", 1)));
        m.put("opportunities", find("Opportunity", byClient, Map.of("closeDate", -1)));
        m.put("signals", find("Signal", Map.of("clientId", clientId, "stage", Map.of("$ne", "Resolved")), Map.of("detectedAt", -1)));
        m.put("contacts", find("Contact", byClient, sorted("isPrimary", -1, "name", 1)));
        m.put("sourceContacts", find("CompanyContact", byClient, Map.of("_id", 1)));
        m.put("meetings", find("Meeting", byClient, Map.of("meetingDate", 1)));
        m.put("activities", find("Activity", byClient, Map.of("date", -1)));
        m.put("events", find("CompanyEvent", byClient, Map.of("eventDate", -1)));
        m.put("ratings", find("RiskRating", byClient, Map.of("measurementPeriod", 1)));
        m.put("financialKpis", find("FinancialKpi", byClient, Map.of("statementDate", 1)));
        m.put("relationshipKpis", find("RelationshipKpi", byClient, Map.of("asOfDate", 1)));
        m.put("coverage", find("CoverageBanker", byClient, Map.of("_id", 1)));
        String industry = client.path("industry").asText();
        m.put("industry", first(source.find("IndustryContext", Map.of("_id", industry), null, null, 1).documents()));
        m.put("peers", source.find("Client", Map.of("industry", industry),
                List.of("clientId", "name", "annualRevenueUsd", "externalRating", "internalRatingGrade", "healthScore", "esgScore"),
                Map.of("annualRevenueUsd", -1), null).documents());
        // a client that has issued high-yield bonds: its latest deal's pro forma capital structure
        JsonNode issuer = first(source.find("HyIssuer", byClient, null, null, 1).documents());
        JsonNode deal = issuer == null ? null : first(source.find("HyDeal", Map.of("issuerId", issuer.path("issuerId").asText()), null,
                Map.of("launchDate", -1), 1).documents());
        m.put("bondDeal", deal);
        m.put("bondCapitalStructure", deal == null ? List.of() : find("HyCapitalLayer", Map.of("dealId", deal.path("dealId").asText()), Map.of("rank", 1)));
        return m;
    }

    private JsonNode find(String label, Map<String, Object> filter, Map<String, Object> sort) {
        return source.find(label, filter, null, sort, null).documents();
    }

    /** A sort spec with its keys in the order given (Map.of has no order). */
    private static Map<String, Object> sorted(Object... kv) {
        Map<String, Object> s = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) s.put((String) kv[i], kv[i + 1]);
        return s;
    }

    private static JsonNode first(JsonNode docs) { return docs.isEmpty() ? null : docs.get(0); }
}
