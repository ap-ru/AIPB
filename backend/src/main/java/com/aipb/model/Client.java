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

/** Commercial banking client - the anchor of the Client 360 view. */
@Data
@NoArgsConstructor
@Document("clients")
public class Client {
    @Id
    private String id;
    @Indexed
    private String name;
    private String segment;          // primary LOB: Corporate Banking | Commercial Lending | Capital Markets | Global Markets
    private String industry;
    private String tier;             // Strategic | Core | Growth
    @Indexed
    private String marketId;
    private String city;
    private String region;
    private String relationshipManager;
    private List<String> coverageTeam = new ArrayList<>();

    private int healthScore;         // 0-100 relationship health
    private String internalRating;   // e.g. "4 - Satisfactory"
    private String externalRating;   // e.g. "BBB+"
    private double annualRevenue;    // client's own revenue
    private int employees;
    private int esgScore;

    // Bank economics
    private double walletSize;       // est. annual banking wallet
    private double revenueYtd;
    private double revenuePriorYtd;
    private double netInterestIncome;
    private double feeIncome;
    private double totalExposure;
    private double committedLimit;
    private double deposits;
    private List<Double> revenueTrend = new ArrayList<>(); // last 8 quarters

    private String kycStatus;        // Current | Refresh Due | Overdue
    private LocalDate nextReviewDate;
    private LocalDate relationshipSince;

    private List<Product> products = new ArrayList<>();
    private List<Contact> contacts = new ArrayList<>();
    private List<Covenant> covenants = new ArrayList<>();
    private List<Activity> activities = new ArrayList<>();
    private List<String> crossSell = new ArrayList<>();

    public double getShareOfWallet() {
        return walletSize == 0 ? 0 : Math.min(1.0, (revenueYtd * 12.0 / Math.max(1, LocalDate.now().getMonthValue())) / walletSize);
    }

    public double getUtilization() {
        return committedLimit == 0 ? 0 : totalExposure / committedLimit;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Product {
        private String name;
        private String lob;
        private double balance;
        private double revenue;
        private String status;   // Active | Pending | Maturing
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Contact {
        private String name;
        private String title;
        private String role;     // Decision Maker | Influencer | Treasury | Finance
        private LocalDate lastContact;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Covenant {
        private String name;
        private String threshold;
        private String actual;
        private double headroomPct;
        private String status;   // Compliant | Watch | Breach
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Activity {
        private LocalDate date;
        private String type;     // Meeting | Call | Email | Credit Review | Agent
        private String summary;
        private String owner;
    }
}
