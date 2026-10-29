package com.aipb.web;

import com.aipb.source.SourceReader;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The CM&A workspace's New Issue page: the deal calendar per product tab and one deal's transaction details, read
 * straight from the bank graph's high-yield labels (HyDeal, HyTranche, ...). A static page - no widgets, no assistant.
 */
@RestController
@RequestMapping("/api/new-issue")
@RequiredArgsConstructor
public class NewIssueController {

    private final SourceReader source;

    /** Every deal in a product tab (High Yield only has data so far), in market first, then newest priced. */
    @GetMapping("/deals")
    public Map<String, Object> deals(@RequestParam(defaultValue = "High Yield") String market) {
        JsonNode deals = source.find("HyDeal", Map.of("market", market), null, sort(), null).documents();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("market", market);
        m.put("asOfDate", source.asOfDate());
        m.put("deals", deals);
        return m;
    }

    /** One deal with its issuer, tranches and the transaction-detail sections. */
    @GetMapping("/deals/{dealId}")
    public Map<String, Object> deal(@PathVariable String dealId) {
        JsonNode deal = source.find("HyDeal", Map.of("_id", dealId), null, null, 1).documents();
        if (deal.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no deal " + dealId);
        Map<String, Object> byDeal = Map.of("dealId", dealId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deal", deal.get(0));
        m.put("issuer", first(source.find("HyIssuer", Map.of("_id", deal.get(0).path("issuerId").asText()), null, null, 1).documents()));
        m.put("tranches", source.find("HyTranche", byDeal, null, Map.of("_id", 1), null).documents());
        m.put("capitalStructure", source.find("HyCapitalLayer", byDeal, null, Map.of("rank", 1), null).documents());
        m.put("covenants", source.find("HyCovenant", byDeal, null, Map.of("_id", 1), null).documents());
        m.put("allocations", source.find("HyAllocation", byDeal, null, Map.of("_id", 1), null).documents());
        m.put("fees", first(source.find("HyFeeEconomics", Map.of("_id", dealId), null, null, 1).documents()));
        return m;
    }

    private static Map<String, Object> sort() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("status", 1);        // "In Market" sorts before "Priced"
        s.put("pricedDate", -1);
        s.put("_id", 1);
        return s;
    }

    private static JsonNode first(JsonNode docs) { return docs.isEmpty() ? null : docs.get(0); }
}
