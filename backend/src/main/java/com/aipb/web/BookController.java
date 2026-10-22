package com.aipb.web;

import com.aipb.model.*;
import com.aipb.repo.*;
import com.aipb.service.Filter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/** Read APIs behind Client 360: the client book, one client's relationship bundle and book KPIs. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BookController {

    private final ClientRepository clients;
    private final OpportunityRepository opps;
    private final SignalRepository signals;

    private static Filter f(String lob, String region, String marketId) { return Filter.of(lob, region, marketId); }

    // ------------------------------------------------------------------ book KPIs
    @GetMapping("/kpis")
    public List<Map<String, Object>> kpis(@RequestParam(required = false) String lob, @RequestParam(required = false) String region,
                                          @RequestParam(required = false) String marketId) {
        List<Client> cl = clients.findAll().stream().filter(f(lob, region, marketId)::test).toList();
        double exposure = cl.stream().mapToDouble(Client::getTotalExposure).sum();
        double limit = cl.stream().mapToDouble(Client::getCommittedLimit).sum();
        double ytd = cl.stream().mapToDouble(Client::getRevenueYtd).sum();
        double py = cl.stream().mapToDouble(Client::getRevenuePriorYtd).sum();
        return List.of(
                kpi("CLIENTS", "Clients in coverage", cl.size(), "number", cl.stream().filter(c -> "Strategic".equals(c.getTier())).count() + " strategic", null, null),
                kpi("CREDIT", "Total exposure", exposure, "money", pct(limit == 0 ? 0 : exposure / limit) + " utilization", null, null),
                kpi("DEPOSITS", "Deposits", cl.stream().mapToDouble(Client::getDeposits).sum(), "money", "operating + liquidity", null, null),
                kpi("REVENUE", "Revenue YTD", ytd, "money", "NII + fees", py == 0 ? 0 : (ytd - py) / py * 100, "vs PY"),
                kpi("HEALTH", "Avg relationship health", cl.stream().mapToInt(Client::getHealthScore).average().orElse(0), "number",
                        cl.stream().filter(c -> c.getHealthScore() < 45).count() + " at risk", null, null));
    }

    private static Map<String, Object> kpi(String tag, String label, double value, String format, String sub, Double delta, String deltaLabel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", tag); m.put("label", label); m.put("value", value); m.put("format", format); m.put("sub", sub);
        if (delta != null) { m.put("delta", delta); m.put("deltaLabel", deltaLabel); }
        return m;
    }

    private static String pct(double v) { return Math.round(v * 1000) / 10.0 + "%"; }

    // ------------------------------------------------------------------ clients / Client 360
    @GetMapping("/clients")
    public List<Map<String, Object>> clients(@RequestParam(required = false) String lob, @RequestParam(required = false) String region,
                                             @RequestParam(required = false) String marketId, @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "revenue") String sort, @RequestParam(defaultValue = "200") int limit) {
        Filter flt = f(lob, region, marketId);
        List<Opportunity> open = opps.findAll().stream().filter(Opportunity::isOpen).toList();
        List<Signal> sg = signals.findAll().stream().filter(s -> !"Resolved".equals(s.getStage())).toList();
        Comparator<Map<String, Object>> cmp = switch (sort) {
            case "exposure" -> Comparator.comparingDouble(m -> (double) m.get("totalExposure"));
            case "health" -> Comparator.comparingDouble(m -> -((Integer) m.get("healthScore")));
            case "walletGap" -> Comparator.comparingDouble(m -> (double) m.get("walletGap"));
            case "pipeline" -> Comparator.comparingDouble(m -> (double) m.get("pipeline"));
            default -> Comparator.comparingDouble(m -> (double) m.get("revenueYtd"));
        };
        return clients.findAll().stream().filter(flt::test)
                .filter(c -> q == null || q.isBlank() || c.getName().toLowerCase().contains(q.toLowerCase()) || c.getIndustry().toLowerCase().contains(q.toLowerCase()))
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId()); m.put("name", c.getName()); m.put("segment", c.getSegment()); m.put("industry", c.getIndustry());
                    m.put("tier", c.getTier()); m.put("city", c.getCity()); m.put("region", c.getRegion()); m.put("marketId", c.getMarketId());
                    m.put("relationshipManager", c.getRelationshipManager()); m.put("healthScore", c.getHealthScore());
                    m.put("internalRating", c.getInternalRating()); m.put("externalRating", c.getExternalRating());
                    m.put("revenueYtd", c.getRevenueYtd()); m.put("revenuePriorYtd", c.getRevenuePriorYtd());
                    m.put("walletSize", c.getWalletSize()); m.put("shareOfWallet", c.getShareOfWallet());
                    double annualised = c.getRevenueYtd() * 12.0 / LocalDate.now().getMonthValue();
                    m.put("walletGap", Math.max(0, c.getWalletSize() - annualised));
                    m.put("totalExposure", c.getTotalExposure()); m.put("utilization", c.getUtilization()); m.put("deposits", c.getDeposits());
                    m.put("pipeline", open.stream().filter(o -> o.getClientId().equals(c.getId())).mapToDouble(Opportunity::getExpectedRevenue).sum());
                    m.put("signals", sg.stream().filter(s -> s.getClientId().equals(c.getId())).count());
                    m.put("revenueTrend", c.getRevenueTrend());
                    m.put("kycStatus", c.getKycStatus());
                    return m;
                })
                .sorted(cmp.reversed()).limit(limit).toList();
    }

    @GetMapping("/clients/{id}")
    public Map<String, Object> client360(@PathVariable String id) {
        Client c = clients.findById(id).orElseThrow(() -> new NoSuchElementException("client " + id));
        List<Opportunity> all = opps.findByClientId(id);
        List<Signal> sg = signals.findByClientIdOrderByDetectedAtDesc(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("client", c);
        m.put("opportunities", all.stream().sorted(Comparator.comparing(Opportunity::isOpen).reversed()
                .thenComparing(Comparator.comparingDouble(Opportunity::getExpectedRevenue).reversed())).toList());
        m.put("signals", sg);
        m.put("pipeline", all.stream().filter(Opportunity::isOpen).mapToDouble(Opportunity::getExpectedRevenue).sum());
        m.put("wonYtd", all.stream().filter(o -> "Closed Won".equals(o.getStage())).mapToDouble(Opportunity::getExpectedRevenue).sum());
        Map<String, Double> byLob = new LinkedHashMap<>();
        for (String lob : List.of("Corporate Banking", "Commercial Lending", "Capital Markets", "Global Markets"))
            byLob.put(lob, c.getProducts().stream().filter(p -> p.getLob().equals(lob)).mapToDouble(Client.Product::getRevenue).sum());
        m.put("revenueByLob", byLob);
        List<Client> peers = clients.findAll().stream().filter(p -> p.getIndustry().equals(c.getIndustry())).toList();
        m.put("peerCount", peers.size());
        m.put("peerAvgHealth", peers.stream().mapToInt(Client::getHealthScore).average().orElse(0));
        m.put("peerAvgShareOfWallet", peers.stream().mapToDouble(Client::getShareOfWallet).average().orElse(0));
        return m;
    }
}
