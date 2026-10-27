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
 * The pre-call brief: everything about one client, read straight from the source collections (clients, holdings,
 * activities ... and the precall_* collections). A static page - no widgets, no assistant; the page arranges it.
 */
@RestController
@RequestMapping("/api/precall")
@RequiredArgsConstructor
public class PrecallController {

    private final SourceReader source;

    /** Every client, for the client list, with the date of its latest logged interaction (lastInteractionDate). */
    @GetMapping("/clients")
    public JsonNode clients() {
        JsonNode clients = source.find("clients", null, Map.of("clientId", 1, "name", 1, "industry", 1, "segment", 1, "tier", 1, "region", 1, "city", 1, "relationshipManager", 1), Map.of("name", 1), null).documents();
        Map<String, JsonNode> last = new HashMap<>();
        source.aggregate("activities", List.of(Map.of("$group", Map.of("_id", "$clientId", "last", Map.of("$max", "$date")))))
                .documents().forEach(d -> last.put(d.path("_id").asText(), d.get("last")));
        clients.forEach(c -> ((ObjectNode) c).set("lastInteractionDate", last.get(c.path("clientId").asText())));
        return clients;
    }

    @GetMapping("/{clientId}")
    public Map<String, Object> brief(@PathVariable String clientId) {
        JsonNode client = first(source.find("clients", Map.of("_id", clientId), null, null, 1).documents());
        if (client == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no client " + clientId);
        Map<String, Object> byClient = Map.of("clientId", clientId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOfDate", source.asOfDate());
        m.put("client", client);
        m.put("profile", first(source.find("precall_company_profiles", byClient, null, null, 1).documents()));
        m.put("ownership", find("precall_ownership", byClient, Map.of("ownershipPct", -1)));
        m.put("holdings", find("holdings", byClient, sorted("lob", 1, "productName", 1)));
        m.put("products", find("precall_citizens_products", byClient, Map.of("_id", 1)));
        m.put("covenants", find("covenants", byClient, Map.of("headroomPct", 1)));
        m.put("balanceHistory", find("precall_balance_history", byClient, Map.of("asOfDate", 1)));
        m.put("payments", find("precall_payment_flows", byClient, Map.of("amountUsd", -1)));
        m.put("revenue", find("revenue_monthly", byClient, Map.of("month", 1)));
        m.put("opportunities", find("opportunities", byClient, Map.of("closeDate", -1)));
        m.put("signals", find("signals", Map.of("clientId", clientId, "stage", Map.of("$ne", "Resolved")), Map.of("detectedAt", -1)));
        m.put("contacts", find("contacts", byClient, sorted("isPrimary", -1, "name", 1)));
        m.put("sourceContacts", find("precall_company_contacts", byClient, Map.of("_id", 1)));
        m.put("meetings", find("precall_upcoming_meetings", byClient, Map.of("meetingDate", 1)));
        m.put("activities", find("activities", byClient, Map.of("date", -1)));
        m.put("events", find("precall_company_event_history", byClient, Map.of("eventDate", -1)));
        m.put("ratings", find("precall_company_risk_rating_history", byClient, Map.of("measurementPeriod", 1)));
        m.put("financialKpis", find("precall_company_financial_kpis", byClient, Map.of("statementDate", 1)));
        m.put("relationshipKpis", find("precall_citizens_relationship_kpis", byClient, Map.of("asOfDate", 1)));
        m.put("coverage", find("precall_citizens_coverage", byClient, Map.of("_id", 1)));
        String industry = client.path("industry").asText();
        m.put("industry", first(source.find("precall_industry_context", Map.of("_id", industry), null, null, 1).documents()));
        m.put("peers", source.find("clients", Map.of("industry", industry),
                Map.of("clientId", 1, "name", 1, "annualRevenueUsd", 1, "externalRating", 1, "internalRatingGrade", 1, "healthScore", 1, "esgScore", 1),
                Map.of("annualRevenueUsd", -1), null).documents());
        // a client that has issued high-yield bonds: its latest deal's pro forma capital structure
        JsonNode issuer = first(source.find("hy_issuers", byClient, null, null, 1).documents());
        JsonNode deal = issuer == null ? null : first(source.find("hy_deals", Map.of("issuerId", issuer.path("issuerId").asText()), null,
                Map.of("launchDate", -1), 1).documents());
        m.put("bondDeal", deal);
        m.put("bondCapitalStructure", deal == null ? List.of() : find("hy_capital_structure", Map.of("dealId", deal.path("dealId").asText()), Map.of("rank", 1)));
        return m;
    }

    private JsonNode find(String collection, Map<String, Object> filter, Map<String, Object> sort) {
        return source.find(collection, filter, null, sort, null).documents();
    }

    /** A sort spec with its keys in the order given (Map.of has no order). */
    private static Map<String, Object> sorted(Object... kv) {
        Map<String, Object> s = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) s.put((String) kv[i], kv[i + 1]);
        return s;
    }

    private static JsonNode first(JsonNode docs) { return docs.isEmpty() ? null : docs.get(0); }
}
