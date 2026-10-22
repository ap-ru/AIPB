package com.aipb.seed;

import com.aipb.model.*;
import com.aipb.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.temporal.IsoFields;
import java.util.*;

/**
 * Seeds MongoDB with a deterministic, realistic commercial-banking book:
 * ~60 clients, ~500 opportunities, ~100 signals and the default layout.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final ClientRepository clients;
    private final OpportunityRepository opps;
    private final SignalRepository signals;
    private final LayoutRepository layouts;

    @Value("${aipb.seed.enabled:true}")
    private boolean enabled;
    @Value("${aipb.seed.reset-on-start:false}")
    private boolean reset;

    private final Random rnd = new Random(42);

    public static final List<String> LOBS = List.of("Corporate Banking", "Commercial Lending", "Capital Markets", "Global Markets");

    static final Map<String, List<String>> PRODUCTS = Map.of(
            "Corporate Banking", List.of("Treasury Management", "Cash Pooling", "Trade Finance", "Supply Chain Finance", "Commercial Card", "Liquidity & Deposits"),
            "Commercial Lending", List.of("Revolving Credit Facility", "Term Loan A", "CRE Loan", "Equipment Finance", "ABL Facility", "Acquisition Financing"),
            "Capital Markets", List.of("DCM Bond Issuance", "Syndicated Loan", "ECM Follow-on", "Leveraged Finance", "Private Placement", "M&A Advisory"),
            "Global Markets", List.of("FX Hedging Program", "Interest Rate Swaps", "Commodity Hedging", "Cross-Currency Swap", "Structured Notes"));

    // id, city, country, region, lat, lon, weight
    static final Object[][] MARKETS = {
            {"SFO", "San Francisco", "USA", "AMER", 37.77, -122.42, 9},
            {"NYC", "New York", "USA", "AMER", 40.71, -74.01, 8},
            {"AUS", "Austin", "USA", "AMER", 30.27, -97.74, 7},
            {"LON", "London", "UK", "EMEA", 51.51, -0.13, 6},
            {"TYO", "Tokyo", "Japan", "APAC", 35.68, 139.69, 5},
            {"FRA", "Frankfurt", "Germany", "EMEA", 50.11, 8.68, 5},
            {"SYD", "Sydney", "Australia", "APAC", -33.87, 151.21, 4},
            {"PAR", "Paris", "France", "EMEA", 48.86, 2.35, 4},
            {"TOR", "Toronto", "Canada", "AMER", 43.65, -79.38, 4},
            {"CHI", "Chicago", "USA", "AMER", 41.88, -87.63, 4},
            {"SIN", "Singapore", "Singapore", "APAC", 1.35, 103.82, 3},
            {"HKG", "Hong Kong", "China", "APAC", 22.32, 114.17, 3},
            {"DAL", "Dallas", "USA", "AMER", 32.78, -96.80, 3},
            {"ZRH", "Zurich", "Switzerland", "EMEA", 47.38, 8.54, 2},
            {"DXB", "Dubai", "UAE", "EMEA", 25.20, 55.27, 2},
            {"SAO", "Sao Paulo", "Brazil", "LATAM", -23.55, -46.63, 2},
            {"MEX", "Mexico City", "Mexico", "LATAM", 19.43, -99.13, 2},
            {"MUM", "Mumbai", "India", "APAC", 19.08, 72.88, 2},
            {"AMS", "Amsterdam", "Netherlands", "EMEA", 52.37, 4.90, 1},
            {"BOG", "Bogota", "Colombia", "LATAM", 4.71, -74.07, 1},
    };

    // name, industry, market
    static final String[][] CLIENTS = {
            {"Vantage Energy Corp", "Energy", "SFO"}, {"Northstar Communities", "Real Estate", "SFO"},
            {"Cobalt Semiconductors", "Technology", "SFO"}, {"Quantum Data Centers", "Technology", "SFO"},
            {"Redwood Agritech", "Agriculture", "SFO"}, {"Pacific Rim Ports", "Transportation", "SFO"},
            {"Gotham Payments", "Financial Services", "NYC"}, {"Hudson Asset Holdings", "Financial Services", "NYC"},
            {"Orion Media Group", "Media", "NYC"}, {"Crescent Pharma", "Healthcare", "NYC"},
            {"Liberty Freight", "Transportation", "NYC"}, {"Lone Star Petrochem", "Energy", "AUS"},
            {"Desert Sun Solar", "Energy", "AUS"}, {"Apex Construction", "Industrials", "AUS"},
            {"Nova Mobility", "Automotive", "AUS"}, {"Thames Property Group", "Real Estate", "LON"},
            {"Beacon Insurance Group", "Financial Services", "LON"}, {"Silverline Telecom", "Telecom", "LON"},
            {"Kansai Digital Grid", "Utilities", "TYO"}, {"Sakura Robotics", "Industrials", "TYO"},
            {"Rheinland Energie AG", "Utilities", "FRA"}, {"Alpine Precision AG", "Industrials", "FRA"},
            {"Sterling Chemicals", "Materials", "FRA"}, {"Outback Resources", "Mining", "SYD"},
            {"Harbourline Logistics", "Transportation", "SYD"}, {"Seine Luxe Group", "Consumer", "PAR"},
            {"Parisian Mobility SA", "Automotive", "PAR"}, {"Maple Leaf Timber", "Materials", "TOR"},
            {"Northern Grid Utilities", "Utilities", "TOR"}, {"Windy City Steel", "Materials", "CHI"},
            {"Atlas Manufacturing", "Industrials", "CHI"}, {"Pinnacle Foods Group", "Consumer", "CHI"},
            {"Marina Bay Holdings", "Real Estate", "SIN"}, {"Straits Shipping Co", "Transportation", "SIN"},
            {"Kowloon Trading Co", "Consumer", "HKG"}, {"Jade Harbour Finance", "Financial Services", "HKG"},
            {"Titan Infrastructure", "Industrials", "DAL"}, {"Falcon Defense Systems", "Aerospace & Defense", "DAL"},
            {"Helvetia Biotech AG", "Healthcare", "ZRH"}, {"Gulf Horizon Hospitality", "Hospitality", "DXB"},
            {"Rio Grande Minerals", "Mining", "SAO"}, {"Amazonia Agro SA", "Agriculture", "SAO"},
            {"Aztec Cement", "Materials", "MEX"}, {"Bombay Textiles Ltd", "Consumer", "MUM"},
            {"Amstel Foods BV", "Consumer", "AMS"}, {"Andes Coffee Exports", "Agriculture", "BOG"},
            {"Summit Aerospace", "Aerospace & Defense", "SFO"}, {"Evergreen REIT", "Real Estate", "NYC"},
            {"Lumen Biotech", "Healthcare", "AUS"}, {"Ironclad Industrial", "Industrials", "LON"},
            {"Horizon Hospitality", "Hospitality", "TYO"}, {"Cascade Water Utilities", "Utilities", "SFO"},
            {"Keystone Materials", "Materials", "NYC"}, {"Bluewater Shipping", "Transportation", "LON"},
            {"Aurora Retail Holdings", "Consumer", "TOR"}, {"Granite Capital Partners", "Financial Services", "CHI"},
            {"Solace Renewables", "Energy", "FRA"}, {"Meridian Logistics", "Transportation", "SYD"},
            {"Harborview Healthcare", "Healthcare", "SFO"}, {"Bay State Medical", "Healthcare", "NYC"},
    };

    static final String[] RMS = {"Maya Chen", "David Okafor", "Priya Raman", "James Whitfield", "Sofia Alvarez",
            "Kenji Watanabe", "Hannah Becker", "Lucas Moreau", "Aisha Rahman", "Tom Brennan"};
    static final String[] FIRST = {"Sarah", "Michael", "Elena", "Robert", "Grace", "Daniel", "Olivia", "Arjun", "Mei", "Carlos", "Ingrid", "Yusuf"};
    static final String[] LAST = {"Thompson", "Nakamura", "Rossi", "Schmidt", "Patel", "Dubois", "Kim", "Silva", "Novak", "Hughes", "Lindqvist", "Adeyemi"};

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (reset) wipe();
        if (clients.count() > 0) {
            log.info("AIPB data already present - skipping seed");
            ensureDefaults();
            return;
        }
        seedAll();
    }

    public void wipe() {
        clients.deleteAll(); opps.deleteAll(); signals.deleteAll(); layouts.deleteAll();
    }

    public void seedAll() {
        log.info("Seeding AIPB demo book...");
        Map<String, Market> marketMap = seedMarkets();
        List<Client> clientList = seedClients(marketMap);
        List<Opportunity> oppList = seedOpportunities(clientList, marketMap);
        seedSignals(clientList, oppList);
        ensureDefaults();
        log.info("Seed complete: {} clients, {} opportunities, {} signals", clients.count(), opps.count(), signals.count());
    }

    private void ensureDefaults() {
        if (!layouts.existsById("default")) layouts.save(Defaults.defaultLayout());
    }

    // ------------------------------------------------------------------ markets
    /** A coverage market: only used to give each client and deal its city and region. */
    private record Market(String id, String city, String region) {}

    private Map<String, Market> seedMarkets() {
        Map<String, Market> map = new LinkedHashMap<>();
        for (Object[] m : MARKETS) map.put((String) m[0], new Market((String) m[0], (String) m[1], (String) m[3]));
        return map;
    }

    // ------------------------------------------------------------------ clients
    private List<Client> seedClients(Map<String, Market> marketMap) {
        List<Client> list = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < CLIENTS.length; i++) {
            String[] c = CLIENTS[i];
            Market mk = marketMap.get(c[2]);
            Client cl = new Client();
            cl.setId("C" + String.format("%03d", i + 1));
            cl.setName(c[0]);
            cl.setIndustry(c[1]);
            cl.setMarketId(mk.id());
            cl.setCity(mk.city());
            cl.setRegion(mk.region());
            cl.setSegment(segmentFor(c[1], i));
            double scale = 0.4 + rnd.nextDouble() * 2.2;
            cl.setTier(scale > 1.7 ? "Strategic" : scale > 0.9 ? "Core" : "Growth");
            if (i < 2) { cl.setTier("Strategic"); scale = 2.3 - i * 0.4; }
            cl.setRelationshipManager(RMS[i % RMS.length]);
            cl.setCoverageTeam(List.of(RMS[(i + 3) % RMS.length] + " (Treasury)", RMS[(i + 5) % RMS.length] + " (Credit)",
                    RMS[(i + 7) % RMS.length] + " (Markets)"));
            cl.setHealthScore(clamp((int) (55 + rnd.nextGaussian() * 18), 18, 98));
            if (i == 0) cl.setHealthScore(86);
            if (i == 1) cl.setHealthScore(66);
            int ir = clamp((int) (4 + rnd.nextGaussian() * 1.5), 1, 9);
            cl.setInternalRating(ir + " - " + ratingLabel(ir));
            cl.setExternalRating(extRating(ir));
            cl.setAnnualRevenue(round(scale * (800 + rnd.nextDouble() * 4000) * 1e6));
            cl.setEmployees((int) (scale * (1500 + rnd.nextInt(20000))));
            cl.setEsgScore(clamp((int) (60 + rnd.nextGaussian() * 15), 20, 95));
            cl.setWalletSize(round(scale * (6 + rnd.nextDouble() * 14) * 1e6));
            double sow = 0.18 + rnd.nextDouble() * 0.5;
            double ytdFactor = today.getMonthValue() / 12.0;
            cl.setRevenueYtd(round(cl.getWalletSize() * sow * ytdFactor));
            cl.setRevenuePriorYtd(round(cl.getRevenueYtd() * (0.8 + rnd.nextDouble() * 0.35)));
            cl.setNetInterestIncome(round(cl.getRevenueYtd() * (0.45 + rnd.nextDouble() * 0.2)));
            cl.setFeeIncome(round(cl.getRevenueYtd() - cl.getNetInterestIncome()));
            cl.setCommittedLimit(round(scale * (150 + rnd.nextDouble() * 600) * 1e6));
            cl.setTotalExposure(round(cl.getCommittedLimit() * (0.35 + rnd.nextDouble() * 0.55)));
            cl.setDeposits(round(scale * (40 + rnd.nextDouble() * 300) * 1e6));
            List<Double> trend = new ArrayList<>();
            double q = cl.getWalletSize() * sow / 4 * 0.8;
            for (int k = 0; k < 8; k++) { q *= 0.97 + rnd.nextDouble() * 0.09; trend.add(round(q)); }
            cl.setRevenueTrend(trend);
            cl.setKycStatus(rnd.nextDouble() < 0.12 ? "Overdue" : rnd.nextDouble() < 0.25 ? "Refresh Due" : "Current");
            cl.setNextReviewDate(today.plusDays(rnd.nextInt(200) - 20));
            cl.setRelationshipSince(today.minusYears(2 + rnd.nextInt(25)));
            cl.setProducts(products(cl, scale));
            cl.setContacts(contacts(today));
            cl.setCovenants(covenants());
            cl.setActivities(activities(cl, today));
            cl.setCrossSell(crossSell(cl));
            list.add(cl);
        }
        return clients.saveAll(list);
    }

    private String segmentFor(String industry, int i) {
        return switch (industry) {
            case "Financial Services" -> "Global Markets";
            case "Real Estate" -> "Commercial Lending";
            case "Technology", "Healthcare" -> i % 2 == 0 ? "Capital Markets" : "Corporate Banking";
            case "Energy", "Utilities", "Mining" -> i % 3 == 0 ? "Global Markets" : "Commercial Lending";
            default -> LOBS.get(i % LOBS.size());
        };
    }

    private List<Client.Product> products(Client cl, double scale) {
        List<Client.Product> ps = new ArrayList<>();
        for (String lob : LOBS) {
            List<String> names = PRODUCTS.get(lob);
            int n = lob.equals(cl.getSegment()) ? 2 + rnd.nextInt(2) : rnd.nextInt(2);
            Set<String> used = new HashSet<>();
            for (int k = 0; k < n; k++) {
                String name = names.get(rnd.nextInt(names.size()));
                if (!used.add(name)) continue;
                double bal = round(scale * (10 + rnd.nextDouble() * 250) * 1e6);
                ps.add(new Client.Product(name, lob, bal, round(bal * (0.004 + rnd.nextDouble() * 0.012)),
                        rnd.nextDouble() < 0.1 ? "Maturing" : rnd.nextDouble() < 0.1 ? "Pending" : "Active"));
            }
        }
        return ps;
    }

    private List<Client.Contact> contacts(LocalDate today) {
        String[][] roles = {{"Chief Financial Officer", "Decision Maker"}, {"Treasurer", "Treasury"},
                {"VP Corporate Development", "Influencer"}, {"Head of Procurement", "Influencer"}, {"Controller", "Finance"}};
        List<Client.Contact> cs = new ArrayList<>();
        int n = 3 + rnd.nextInt(3);
        for (int k = 0; k < n; k++) {
            cs.add(new Client.Contact(FIRST[rnd.nextInt(FIRST.length)] + " " + LAST[rnd.nextInt(LAST.length)],
                    roles[k][0], roles[k][1], today.minusDays(rnd.nextInt(120))));
        }
        return cs;
    }

    private List<Client.Covenant> covenants() {
        List<Client.Covenant> cv = new ArrayList<>();
        double lev = 2.0 + rnd.nextDouble() * 2.2;
        double levHead = (4.0 - lev) / 4.0;
        cv.add(new Client.Covenant("Max Net Leverage", "<= 4.00x", String.format("%.2fx", lev), round2(levHead * 100), covStatus(levHead)));
        double icr = 2.2 + rnd.nextDouble() * 5;
        double icrHead = (icr - 2.5) / icr;
        cv.add(new Client.Covenant("Min Interest Coverage", ">= 2.50x", String.format("%.2fx", icr), round2(icrHead * 100), covStatus(icrHead)));
        double liq = 40 + rnd.nextDouble() * 200;
        double liqHead = (liq - 50) / liq;
        cv.add(new Client.Covenant("Min Liquidity", ">= $50M", String.format("$%.0fM", liq), round2(liqHead * 100), covStatus(liqHead)));
        return cv;
    }

    private String covStatus(double headroom) {
        return headroom < 0 ? "Breach" : headroom < 0.1 ? "Watch" : "Compliant";
    }

    private List<Client.Activity> activities(Client cl, LocalDate today) {
        String[][] acts = {
                {"Meeting", "Quarterly business review with CFO - discussed capex plan and FX exposure"},
                {"Call", "Treasury walkthrough of real-time payments and virtual accounts"},
                {"Credit Review", "Annual credit review completed - rating affirmed"},
                {"Email", "Sent refinancing indications for maturing term loan"},
                {"Meeting", "Site visit with operations team - supply chain finance pilot scoped"},
                {"Call", "Hedging strategy discussion ahead of rate decision"},
        };
        List<Client.Activity> list = new ArrayList<>();
        int n = 3 + rnd.nextInt(3);
        for (int k = 0; k < n; k++) {
            String[] a = acts[rnd.nextInt(acts.length)];
            list.add(new Client.Activity(today.minusDays(k * 9L + rnd.nextInt(8)), a[0], a[1], cl.getRelationshipManager()));
        }
        return list;
    }

    private List<String> crossSell(Client cl) {
        List<String> out = new ArrayList<>();
        Set<String> held = new HashSet<>();
        cl.getProducts().forEach(p -> held.add(p.getName()));
        for (String lob : LOBS) {
            for (String p : PRODUCTS.get(lob)) {
                if (!held.contains(p) && rnd.nextDouble() < 0.12) out.add(p);
            }
        }
        return out.size() > 4 ? out.subList(0, 4) : out;
    }

    // ------------------------------------------------------------ opportunities
    private List<Opportunity> seedOpportunities(List<Client> clientList, Map<String, Market> marketMap) {
        List<Opportunity> list = new ArrayList<>();
        LocalDate today = LocalDate.now();
        LocalDate qStart = today.with(IsoFields.DAY_OF_QUARTER, 1);
        LocalDate qEnd = qStart.plusMonths(3).minusDays(1);
        int seq = 1;
        Map<String, Integer> weight = new HashMap<>();
        for (Object[] m : MARKETS) weight.put((String) m[0], (Integer) m[6]);

        for (Client cl : clientList) {
            double tierMul = switch (cl.getTier()) { case "Strategic" -> 1.6; case "Core" -> 1.0; default -> 0.6; };
            int w = weight.get(cl.getMarketId());
            int nOpen = 3 + w / 2 + rnd.nextInt(4);
            int nClosed = 2 + rnd.nextInt(4);
            for (int k = 0; k < nOpen + nClosed; k++) {
                boolean open = k < nOpen;
                String lob = rnd.nextDouble() < 0.45 ? cl.getSegment() : LOBS.get(rnd.nextInt(LOBS.size()));
                String product = pick(PRODUCTS.get(lob));
                Opportunity o = new Opportunity();
                o.setId("O" + String.format("%04d", seq++));
                o.setClientId(cl.getId());
                o.setClientName(cl.getName());
                o.setLob(lob);
                o.setProduct(product);
                o.setName(shortName(cl.getName()) + " - " + product + " FY" + (today.getYear() % 100 + 1));
                o.setMarketId(cl.getMarketId());
                o.setRegion(cl.getRegion());
                o.setOwner(cl.getRelationshipManager());
                double rev = tierMul * (0.25 + Math.pow(rnd.nextDouble(), 2) * 4.2) * 1e6 * (0.7 + w * 0.06);
                o.setExpectedRevenue(round(rev));
                o.setDealSize(round(rev * (40 + rnd.nextDouble() * 120)));
                if (open) {
                    int si = rnd.nextInt(Opportunity.OPEN_STAGES.size());
                    o.setStage(Opportunity.OPEN_STAGES.get(si));
                    o.setProbability(new int[]{10, 25, 40, 60, 80, 90}[si]);
                    o.setForecastCategory(si >= 4 ? "Commit" : si >= 2 ? "Best Case" : "Pipeline");
                    LocalDate close = si >= 3
                            ? (rnd.nextDouble() < 0.14 ? today.minusDays(1 + rnd.nextInt(20)) : today.plusDays(1 + rnd.nextInt(Math.max(10, (int) (qEnd.toEpochDay() - today.toEpochDay()) + 45))))
                            : today.plusDays(20 + rnd.nextInt(200));
                    o.setOriginalCloseDate(close);
                    o.setCloseDate(close);
                    o.setDaysInStage(3 + rnd.nextInt(70));
                    o.setRiskLevel(o.getDaysInStage() > 45 || close.isBefore(today) ? "High" : o.getDaysInStage() > 25 ? "Medium" : "Low");
                    o.setBlockers(blockers(si));
                    o.setNextStep(nextStep(o.getStage()));
                } else {
                    boolean won = rnd.nextDouble() < 0.66;
                    o.setStage(won ? "Closed Won" : "Closed Lost");
                    o.setProbability(won ? 100 : 0);
                    o.setForecastCategory("Closed");
                    LocalDate close = qStart.plusDays(rnd.nextInt((int) Math.max(1, today.toEpochDay() - qStart.toEpochDay())));
                    o.setCloseDate(close);
                    o.setOriginalCloseDate(close);
                    o.setRiskLevel("Low");
                }
                list.add(o);
            }
        }
        // Hero deal from the reference: Northstar Communities, Negotiation @90%, 10 days past close date
        Opportunity hero = list.stream().filter(o -> o.getClientId().equals("C002") && o.isOpen()).findFirst().orElseThrow();
        hero.setName("Northstar - Connected Neighborhoods FY" + (today.getYear() % 100 + 1));
        hero.setProduct("Acquisition Financing"); hero.setLob("Commercial Lending");
        hero.setStage("Closing"); hero.setProbability(90); hero.setForecastCategory("Commit");
        hero.setExpectedRevenue(4.8e6); hero.setDealSize(420e6);
        hero.setOriginalCloseDate(today.minusDays(10)); hero.setCloseDate(today.minusDays(10));
        hero.setRiskLevel("High"); hero.setDaysInStage(38);
        hero.setBlockers(new ArrayList<>(List.of(new Opportunity.Blocker("Credit", "Blocked"),
                new Opportunity.Blocker("Legal", "In Progress"), new Opportunity.Blocker("Procurement", "Not Started"))));
        hero.setNextStep("Confirm credit committee slot and reset close date before forecast lock");
        return opps.saveAll(list);
    }

    private List<Opportunity.Blocker> blockers(int stageIdx) {
        List<Opportunity.Blocker> b = new ArrayList<>();
        String[] areas = {"Credit", "Legal", "KYC", "Pricing", "Procurement"};
        String[] st = {"Blocked", "In Progress", "Not Started", "Cleared"};
        int n = rnd.nextInt(3);
        for (int i = 0; i < n; i++) {
            b.add(new Opportunity.Blocker(areas[rnd.nextInt(areas.length)], st[stageIdx >= 4 ? 1 + rnd.nextInt(3) : rnd.nextInt(4)]));
        }
        return b;
    }

    private String nextStep(String stage) {
        return switch (stage) {
            case "Discovery" -> "Schedule needs assessment with Treasurer";
            case "Proposal" -> "Send indicative term sheet";
            case "Structuring" -> "Finalize pricing grid with product partner";
            case "Credit Approval" -> "Present to credit committee";
            case "Documentation" -> "Turn legal comments on facility agreement";
            default -> "Obtain signatures and fund";
        };
    }

    // ------------------------------------------------------------------ signals
    // category, type, severity, title, source, lob, impact multiplier, actions
    static final String[][] SIGNAL_TEMPLATES = {
            {"Risk", "Covenant", "P1", "Covenant headroom under 10% on Max Net Leverage", "Credit monitoring", "Commercial Lending",
                    "Request updated compliance certificate|Model amendment & waiver pricing|Brief credit officer before next draw"},
            {"Risk", "Liquidity", "P1", "Operating account outflows up 42% WoW", "Payments flows", "Corporate Banking",
                    "Call Treasurer to understand cash usage|Check for vendor concentration in outflows|Review revolver availability"},
            {"Risk", "Rating", "P2", "External rating outlook revised to Negative", "News", "Commercial Lending",
                    "Refresh internal rating model|Review pricing grid step-ups|Update watchlist committee"},
            {"Risk", "Deposit Attrition", "P2", "Deposit balances down 18% in 30 days", "Payments flows", "Corporate Banking",
                    "Benchmark deposit pricing vs competitors|Offer liquidity sweep structure|Schedule relationship check-in"},
            {"Risk", "Management Change", "P2", "CFO departure announced", "News", "Corporate Banking",
                    "Map new finance leadership|Send introduction from senior coverage banker|Reconfirm pending approvals"},
            {"Opportunity", "M&A", "P1", "Acquisition announced - financing need est. $400M", "News", "Capital Markets",
                    "Pitch bridge-to-bond financing|Engage leveraged finance desk|Offer acquisition FX hedging"},
            {"Opportunity", "FX Exposure", "P2", "EUR receivables up 60% - unhedged FX exposure", "Payments flows", "Global Markets",
                    "Propose layered FX forward program|Share hedge-effectiveness analysis|Set up FX e-trading access"},
            {"Opportunity", "Refinancing", "P2", "$250M notes maturing in 9 months", "Market data", "Capital Markets",
                    "Prepare refinancing options deck|Run DCM pricing indications|Offer pre-hedge on rates"},
            {"Opportunity", "Excess Liquidity", "P3", "$85M idle balances in operating accounts", "Payments flows", "Corporate Banking",
                    "Propose money market sweep|Introduce structured deposit options|Review cash forecasting"},
            {"Opportunity", "Capex", "P3", "Capex plan announced - equipment finance opportunity", "News", "Commercial Lending",
                    "Offer equipment finance line|Bundle with trade finance for imports|Align with vendor finance team"},
            {"Opportunity", "Rate Hedging", "P2", "Floating-rate debt 80% unhedged ahead of rate decision", "Market data", "Global Markets",
                    "Run swap scenario analysis|Propose collar structure|Schedule markets strategist call"},
            {"Service", "Escalation", "P1", "P1 escalation - wire failures on treasury platform", "Service desk", "Corporate Banking",
                    "Engage Tier 3 payments ops|Proactive update to client Treasurer|Post-incident review within 48h"},
            {"Service", "Onboarding", "P3", "KYC refresh overdue - onboarding at risk", "CRM", "Corporate Banking",
                    "Request outstanding KYC documents|Escalate to onboarding lead|Block new facilities until cleared"},
            {"Service", "CSAT", "P2", "CSAT dropped 14 pts after lockbox migration", "Service desk", "Corporate Banking",
                    "Schedule service recovery call|Assign dedicated implementation manager|Track remediation plan"},
            {"Market", "Sector Stress", "P2", "Sector stress: office CRE vacancies up to 19%", "Market data", "Commercial Lending",
                    "Re-underwrite CRE exposures|Stress test DSCR at +200bps|Review concentration limits"},
            {"Market", "Commodity", "P3", "Oil +8% in a week - commodity hedging demand", "Market data", "Global Markets",
                    "Reach out on commodity hedging|Share desk commentary|Review margin requirements"},
            {"Opportunity", "Renewal", "P3", "Treasury services renewal signed", "CRM", "Corporate Banking",
                    "Log renewal in revenue forecast|Identify upsell into virtual accounts|Thank-you note from coverage head"},
            {"Opportunity", "Executive", "P3", "Executive sponsor meeting logged", "CRM", "Capital Markets",
                    "Share meeting notes with deal team|Align on next best action|Update relationship plan"},
    };

    private void seedSignals(List<Client> clientList, List<Opportunity> oppList) {
        List<Signal> list = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < 110; i++) {
            Client cl = i < 6 ? clientList.get(i % 2) : clientList.get(rnd.nextInt(clientList.size()));
            String[] t = SIGNAL_TEMPLATES[rnd.nextInt(SIGNAL_TEMPLATES.length)];
            Signal s = new Signal();
            s.setId("S" + String.format("%04d", i + 1));
            s.setClientId(cl.getId());
            s.setClientName(cl.getName());
            s.setMarketId(cl.getMarketId());
            s.setRegion(cl.getRegion());
            s.setCategory(t[0]); s.setType(t[1]); s.setSeverity(t[2]); s.setTitle(t[3]); s.setSource(t[4]); s.setLob(t[5]);
            s.setSummary(summary(cl, t));
            Instant detected = now.minus(Duration.ofMinutes((long) (Math.pow(rnd.nextDouble(), 2) * 7 * 24 * 60)));
            s.setDetectedAt(detected);
            double r = rnd.nextDouble();
            String stage = r < 0.3 ? "New" : r < 0.52 ? "Triaged" : r < 0.72 ? "Engaged" : r < 0.87 ? "Actioned" : "Resolved";
            s.setStage(stage);
            s.setConfidence(55 + rnd.nextInt(44));
            s.setImpactUsd(round((0.2 + rnd.nextDouble() * 6) * 1e6 * ("P1".equals(t[2]) ? 2 : 1)));
            s.setOwner(cl.getRelationshipManager());
            oppList.stream().filter(o -> o.getClientId().equals(cl.getId()) && o.isOpen()).findAny()
                    .ifPresent(o -> s.setRelatedOpportunityId(o.getId()));
            s.setEvidence(evidence(t[1], cl));
            s.setRecommendedActions(new ArrayList<>(Arrays.asList(t[6].split("\\|"))));
            List<Signal.Event> tl = new ArrayList<>();
            tl.add(new Signal.Event(detected, "AIPB Signal Engine", "Detected from " + t[4] + " (confidence " + s.getConfidence() + "%)"));
            int idx = Signal.STAGES.indexOf(stage);
            for (int k = 1; k <= idx; k++) {
                tl.add(new Signal.Event(detected.plus(Duration.ofMinutes(30L * k + rnd.nextInt(60))), cl.getRelationshipManager(),
                        "Moved to " + Signal.STAGES.get(k)));
            }
            s.setTimeline(tl);
            list.add(s);
        }
        signals.saveAll(list);
    }

    private String summary(Client cl, String[] t) {
        return switch (t[1]) {
            case "Covenant" -> cl.getName() + " reported trailing leverage approaching the 4.00x covenant. Headroom has compressed for two consecutive quarters as EBITDA softened.";
            case "Liquidity" -> "Outgoing payments from " + cl.getName() + "'s operating accounts spiked week-over-week, concentrated in three new counterparties.";
            case "M&A" -> cl.getName() + " announced a strategic acquisition. Public filings imply external financing need; no bank mandate announced yet.";
            case "FX Exposure" -> "Inbound EUR collections for " + cl.getName() + " rose sharply this quarter while no forward cover is booked with us.";
            case "Escalation" -> "Multiple failed high-value wires for " + cl.getName() + " in the last 24h. Tier 3 payments operations engaged.";
            default -> t[3] + " for " + cl.getName() + ". Detected by the AIPB signal engine and routed to the coverage team.";
        };
    }

    private List<Signal.Evidence> evidence(String type, Client cl) {
        List<Signal.Evidence> ev = new ArrayList<>();
        ev.add(new Signal.Evidence("Total exposure", money(cl.getTotalExposure())));
        ev.add(new Signal.Evidence("Utilization", Math.round(cl.getUtilization() * 100) + "%"));
        switch (type) {
            case "Covenant" -> ev.add(new Signal.Evidence("Net leverage", cl.getCovenants().get(0).getActual() + " vs 4.00x"));
            case "Liquidity", "Deposit Attrition" -> ev.add(new Signal.Evidence("Deposits", money(cl.getDeposits())));
            case "FX Exposure" -> ev.add(new Signal.Evidence("Unhedged EUR flows (90d)", money(cl.getAnnualRevenue() * 0.04)));
            default -> ev.add(new Signal.Evidence("Wallet size", money(cl.getWalletSize())));
        }
        ev.add(new Signal.Evidence("Relationship health", cl.getHealthScore() + "/100"));
        return ev;
    }

    // ------------------------------------------------------------------ helpers
    private <T> T pick(List<T> l) { return l.get(rnd.nextInt(l.size())); }
    static double round(double v) { return Math.round(v / 1000.0) * 1000.0; }
    static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    static String shortName(String n) { return n.split(" ")[0]; }
    static String money(double v) {
        if (v >= 1e9) return String.format("$%.2fB", v / 1e9);
        if (v >= 1e6) return String.format("$%.1fM", v / 1e6);
        return String.format("$%.0fK", v / 1e3);
    }
    static String ratingLabel(int r) {
        return switch (r) { case 1, 2 -> "Strong"; case 3, 4 -> "Satisfactory"; case 5, 6 -> "Acceptable"; case 7 -> "Watch"; default -> "Substandard"; };
    }
    static String extRating(int r) {
        String[] map = {"AA", "AA-", "A", "A-", "BBB+", "BBB", "BBB-", "BB+", "BB"};
        return map[Math.min(map.length - 1, r - 1)];
    }
}
