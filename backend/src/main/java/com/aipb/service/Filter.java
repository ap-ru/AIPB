package com.aipb.service;

import com.aipb.model.Client;
import com.aipb.model.Opportunity;
import com.aipb.model.Signal;

import java.util.Arrays;
import java.util.List;

/** Common slice-and-dice filter shared by every widget query. */
public record Filter(String lob, String region, String marketId, String clientId,
                     String category, String severity, String stage) {

    public static Filter of(String lob, String region, String marketId) {
        return new Filter(lob, region, marketId, null, null, null, null);
    }

    private static boolean blank(String s) { return s == null || s.isBlank() || "all".equalsIgnoreCase(s); }

    private static boolean in(String csv, String v) {
        if (blank(csv)) return true;
        List<String> parts = Arrays.stream(csv.split(",")).map(String::trim).toList();
        return v != null && parts.stream().anyMatch(p -> p.equalsIgnoreCase(v));
    }

    public boolean test(Opportunity o) {
        return in(lob, o.getLob()) && in(region, o.getRegion()) && in(marketId, o.getMarketId()) && in(clientId, o.getClientId())
                && in(stage, o.getStage());
    }

    public boolean test(Client c) {
        return in(region, c.getRegion()) && in(marketId, c.getMarketId()) && in(clientId, c.getId())
                && (blank(lob) || in(lob, c.getSegment()) || c.getProducts().stream().anyMatch(p -> in(lob, p.getLob())));
    }

    public boolean test(Signal s) {
        return in(lob, s.getLob()) && in(region, s.getRegion()) && in(marketId, s.getMarketId()) && in(clientId, s.getClientId())
                && in(category, s.getCategory()) && in(severity, s.getSeverity()) && in(stage, s.getStage());
    }
}
