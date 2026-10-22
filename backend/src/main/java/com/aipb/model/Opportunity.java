package com.aipb.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** A revenue opportunity (deal / mandate) across the commercial banking LOBs. */
@Data
@NoArgsConstructor
@Document("opportunities")
public class Opportunity {
    public static final List<String> OPEN_STAGES =
            List.of("Discovery", "Proposal", "Structuring", "Credit Approval", "Documentation", "Closing");

    @Id
    private String id;
    @Indexed
    private String clientId;
    private String clientName;
    private String name;
    @Indexed
    private String lob;
    private String product;
    @Indexed
    private String stage;               // OPEN_STAGES + Closed Won | Closed Lost
    private double dealSize;            // facility / notional
    private double expectedRevenue;     // fees + first-year NII -> pipeline value
    private int probability;
    private String forecastCategory;    // Commit | Best Case | Pipeline | Closed
    private LocalDate closeDate;
    private LocalDate originalCloseDate;
    @Indexed
    private String marketId;
    private String region;
    private String owner;
    private String riskLevel;           // Low | Medium | High
    private int daysInStage;
    private String nextStep;
    private List<Blocker> blockers = new ArrayList<>();

    public boolean isOpen() {
        return OPEN_STAGES.contains(stage);
    }

    public boolean isSlipped() {
        return isOpen() && closeDate != null && closeDate.isBefore(LocalDate.now());
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Blocker {
        private String area;    // Credit | Legal | KYC | Pricing | Procurement
        private String status;  // Blocked | In Progress | Not Started | Cleared
    }
}
