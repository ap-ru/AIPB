package com.aipb.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A detected client / market signal moving through the signals pipeline. */
@Data
@NoArgsConstructor
@Document("signals")
public class Signal {
    public static final List<String> STAGES = List.of("New", "Triaged", "Engaged", "Actioned", "Resolved");

    @Id
    private String id;
    @Indexed
    private String clientId;
    private String clientName;
    private String marketId;
    private String region;
    private String lob;
    private String category;     // Risk | Opportunity | Service | Market
    private String type;         // e.g. Covenant, Liquidity, M&A, FX Exposure
    private String severity;     // P1 | P2 | P3
    private String title;
    private String summary;
    private String source;       // Payments flows | Credit monitoring | News | Market data | CRM | Service desk
    @Indexed
    private Instant detectedAt;
    @Indexed
    private String stage;
    private int confidence;      // 0-100
    private double impactUsd;
    private String owner;
    private String relatedOpportunityId;
    private List<Evidence> evidence = new ArrayList<>();
    private List<String> recommendedActions = new ArrayList<>();
    private List<Event> timeline = new ArrayList<>();

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Evidence {
        private String label;
        private String value;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Event {
        private Instant at;
        private String actor;
        private String event;
    }
}
