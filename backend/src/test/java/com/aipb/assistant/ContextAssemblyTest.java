package com.aipb.assistant;

import com.aipb.assistant.llm.LlmRequest;
import com.aipb.assistant.stages.*;
import com.aipb.seed.Defaults;
import com.aipb.service.LayoutService;
import com.aipb.skills.SkillTemplate;
import com.aipb.skills.SkillTemplateRegistry;
import com.aipb.skills.InsightSkill;
import com.aipb.skills.WidgetSkill;
import com.aipb.source.SourceData;
import com.aipb.widget.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * What each stage of the assistant is given - rendered from the real data dictionary (data/catalog.json), design
 * catalog and starter specs. Checks the static context is deterministic (so it caches), complete and within budget,
 * that the dynamic context carries what the stage needs, and writes every stage's full request to
 * target/assistant-context/ for people to read.
 */
class ContextAssemblyTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode catalog;
    private ContextAssembler context;
    private AssistantTools tools;

    @BeforeEach
    void setUp() throws Exception {
        catalog = mapper.readTree(Path.of("../data/catalog.json").toFile());
        SourceData source = mock(SourceData.class);
        when(source.catalog()).thenReturn(catalog);
        when(source.asOfDate()).thenReturn("2026-10-16T00:00:00.000Z");
        LayoutService layouts = mock(LayoutService.class);
        when(layouts.get(anyString())).thenReturn(Defaults.defaultLayout());
        WidgetDefinitionRepository library = mock(WidgetDefinitionRepository.class);
        List<WidgetDefinition> defs = starterDefinitions();
        WidgetDefinition personal = new WidgetDefinition(); // someone else's personal widget: not offered for reuse
        personal.setId("reviewer-private-widget");
        personal.setTitle("Private");
        personal.setPrimitive("metric");
        personal.setOwner("demo-reviewer");
        personal.setScope(WidgetDefinition.PERSONAL);
        defs.add(personal);
        when(library.findAll()).thenReturn(defs);
        WidgetDataService widgetData = mock(WidgetDataService.class);
        when(widgetData.options(any())).thenReturn(List.of(Map.of("value", "CL-0002", "label", "Northstar Communities")));
        context = new ContextAssembler(source, new WidgetPrimitives(mapper), library, widgetData, layouts, mapper);
        tools = new AssistantTools(source, mapper);
    }

    /**
     * The Custom widget system blocks of every stage, pinned by hash (src/test/resources/assistant/system-blocks.sha256):
     * they must stay byte-identical to the eval baseline's (docs/eval-baseline.md) and to the cached prefix. A deliberate
     * change regenerates the file with -Daipb.pin.update=true - and invalidates the baseline.
     */
    @Test
    void customWidgetSystemBlocksStayPinned() throws Exception {
        Map<String, List<ContextAssembler.Block>> stages = new LinkedHashMap<>();
        stages.put("intake", context.intakeSystem());
        stages.put("build", context.buildSystem());
        stages.put("review", context.reviewSystem());
        stages.put("present", context.presentSystem());
        StringBuilder sb = new StringBuilder();
        stages.forEach((stage, blocks) -> blocks.forEach(b -> sb.append(stage).append(' ').append(b.name()).append(' ')
                .append(sha256(b.text())).append('\n')));
        Path pin = Path.of("src/test/resources/assistant/system-blocks.sha256");
        if (Boolean.getBoolean("aipb.pin.update") || !Files.exists(pin)) {
            Files.createDirectories(pin.getParent());
            Files.writeString(pin, sb.toString());
        }
        assertThat(sb.toString()).isEqualTo(Files.readString(pin));
    }

    private static String sha256(String text) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void staticContextIsDeterministicSoItCaches() {
        assertThat(ContextAssembler.texts(context.buildSystem())).isEqualTo(ContextAssembler.texts(context.buildSystem()));
        assertThat(ContextAssembler.texts(context.intakeSystem())).isEqualTo(ContextAssembler.texts(context.intakeSystem()));
        assertThat(ContextAssembler.texts(context.presentSystem())).isEqualTo(ContextAssembler.texts(context.presentSystem()));
        // the design catalog teaches the intents and every primitive, including meter and composition
        String design = String.join("\n", ContextAssembler.texts(context.presentSystem()));
        assertThat(design).contains("## Design intents", "- polarity:", "## meter", "## composition", "with x: 2 to 36");
        // the dynamic parts must never leak into the cached system prompt
        String system = String.join("\n", ContextAssembler.texts(context.buildSystem()));
        assertThat(system).doesNotContain("2026-10-16").doesNotContain("CL-0002").doesNotContain("Northstar");
        // tool lists are sorted by name
        assertThat(tools.definitions("submit_data", "run_query", "sample_documents", "distinct_values"))
                .extracting(LlmRequest.ToolDef::name).containsExactly("distinct_values", "run_query", "sample_documents", "submit_data");
    }

    @Test
    void theCustomWidgetTemplateLeavesEveryStagePromptExactlyAsBefore() {
        SkillTemplate custom = templates().get(SkillTemplate.CUSTOM_WIDGET);
        assertThat(ContextAssembler.texts(context.intakeSystem(custom))).isEqualTo(ContextAssembler.texts(context.intakeSystem()));
        assertThat(ContextAssembler.texts(context.buildSystem(custom))).isEqualTo(ContextAssembler.texts(context.buildSystem()));
        assertThat(ContextAssembler.texts(context.reviewSystem(custom))).isEqualTo(ContextAssembler.texts(context.reviewSystem()));
        assertThat(ContextAssembler.texts(context.presentSystem(custom))).isEqualTo(ContextAssembler.texts(context.presentSystem()));
    }

    @Test
    void aTemplateAppendsOneGuidanceBlockAfterTheSharedPrefixAndCanSwapTheExamples() {
        SkillTemplate exposure = templates().get("exposure-vs-limit");
        List<ContextAssembler.Block> plain = context.intakeSystem();
        List<ContextAssembler.Block> guided = context.intakeSystem(exposure);
        assertThat(guided).hasSize(plain.size() + 1);
        assertThat(guided.subList(0, plain.size())).isEqualTo(plain); // the cached prefix is shared across templates
        ContextAssembler.Block last = guided.get(guided.size() - 1);
        assertThat(last.name()).isEqualTo("template-guidance");
        assertThat(last.source()).isEqualTo("skills/templates/exposure-vs-limit.json@v1");
        assertThat(last.text()).contains("# Skill template: Exposure vs. limit", "## For this step", "scope: client only",
                "source collections: holdings", "display primitives: meter, bar, metric", "at most 25 result rows");
        // the template's worked examples replace the defaults
        String build = String.join("\n", ContextAssembler.texts(context.buildSystem(exposure)));
        assertThat(context.buildSystem(exposure).get(4).source()).isEqualTo("assistant/examples [client-exposure, client-covenants]");
        assertThat(build).contains("## Credit exposure").doesNotContain("## Revenue YTD");
        // and the manifest records which template version the model saw
        assertThat(context.manifest(guided, null)).extracting(ContextAssembler.ManifestEntry::source).contains("skills/templates/exposure-vs-limit.json@v1");
    }

    private SkillTemplateRegistry templates() {
        WidgetSkill widget = mock(WidgetSkill.class);
        when(widget.id()).thenReturn("widget");
        when(widget.stageLabels()).thenReturn(Map.of("build", "", "review", "", "present", ""));
        InsightSkill insight = mock(InsightSkill.class);
        when(insight.id()).thenReturn("insight");
        when(insight.stageLabels()).thenReturn(Map.of("build", "", "review", "", "narrate", ""));
        return new SkillTemplateRegistry(List.of(widget, insight), new WidgetPrimitives(mapper), mapper);
    }

    @Test
    void theDictionaryCoversEveryCollectionFieldAndDerivedMetric() {
        String dict = CatalogRenderer.dictionary(catalog);
        String index = CatalogRenderer.index(catalog);
        for (JsonNode e : catalog) {
            String coll = e.path("_id").asText();
            if (coll.startsWith("_")) continue;
            assertThat(dict).contains("## " + coll);
            assertThat(index).contains("## " + coll);
            e.path("fields").forEach(f -> { if (!f.path("name").asText().equals("_id")) assertThat(dict).contains("- " + f.path("name").asText() + " ("); });
            e.path("derivedMetrics").forEach(d -> assertThat(dict).contains(d.path("name").asText() + ": " + d.path("definition").asText()));
        }
        assertThat(CatalogRenderer.overview(catalog)).contains("asOfDate").contains("clients.clientId");
        assertThat(context.referenceNames().get("holdings")).contains("drawnUsd", "utilization");
        assertThat(context.referenceNames().get("clients")).contains("niiYtdUsd", "feeYtdUsd", "coverageTeam.role");
    }

    @Test
    void dynamicContextCarriesThePageTheClientTheDateAndTheLibrary() {
        String page = context.page("default", "client", Map.of("clientId", "CL-0002"));
        assertThat(page).contains("Client 360").contains("clientId").contains("Northstar Communities (CL-0002)").contains("client-revenue-ytd");
        assertThat(context.page("default", "command-center", Map.of())).contains("parameters: none");
        assertThat(context.dataToday()).contains("2026-10-16").contains("2026-09");
        assertThat(context.library("demo-builder")).doesNotContain("reviewer-private-widget");
        assertThat(context.library("demo-reviewer")).contains("reviewer-private-widget");
        assertThat(context.library("demo-builder")).contains("client-covenants").contains("needs clientId").contains("book-revenue-by-lob-monthly").contains("book-wide")
                .contains("client-exposure: \"Credit exposure\" (meter; the selected client, or the whole book on a page without one)");
    }

    @Test
    void everyStageFitsItsBudgetAndIsWrittenOutForReview() throws Exception {
        Path dir = Path.of("target/assistant-context");
        Files.createDirectories(dir);
        JsonNode brief = mapper.readTree("""
                {"question":"How has this client's revenue moved quarter by quarter over the last two years?","titleHint":"Quarterly revenue",
                 "scope":"client","measures":[{"name":"Revenue","definition":"NII plus fees booked per quarter","unit":"USD"}],
                 "dimensions":["quarter"],"time":{"window":"last 8 closed quarters","grain":"quarter"},"filters":[],"sort":"oldest first",
                 "limit":null,"display":"any","assumptions":["Last 8 closed quarters (default for a quarterly trend)"]}""");
        JsonNode starter = mapper.readTree(new ClassPathResource("assistant/examples/client-revenue-trend.json").getInputStream());
        ObjectNode artifact = mapper.createObjectNode();
        artifact.set("params", starter.path("params"));
        artifact.set("collection", starter.path("data").path("collection"));
        artifact.set("pipeline", starter.path("data").path("pipeline"));
        artifact.set("columns", starter.path("data").path("columns"));
        artifact.set("measures", mapper.readTree("[{\"column\":\"revenueUsd\",\"definition\":\"Sum of totalUsd per quarter\",\"catalogReference\":\"revenue_monthly.totalUsd\"}]"));
        artifact.set("preview", mapper.readTree("{\"count\":8,\"rows\":[{\"quarter\":\"2024 Q4\",\"revenueUsd\":2550979},{\"quarter\":\"2025 Q1\",\"revenueUsd\":2640918}]}"));
        artifact.set("warnings", mapper.createArrayNode());
        AssistantTools.Context ctx = new AssistantTools.Context("default", "client", Map.of("clientId", "CL-0002"));

        AssistantSession s = new AssistantSession();
        s.setLayoutId("default");
        s.setPageId("client");
        s.setParams(Map.of("clientId", "CL-0002"));
        s.getTurns().add(new AssistantSession.Turn("user", "Show me revenue by quarter", "message", Instant.now()));

        IntakeStage intake = new IntakeStage(null, context, mock(WidgetDefinitionRepository.class), mock(LayoutService.class), null, mapper);
        BuildStage build = new BuildStage(null, context, tools, null, null);
        PresentStage present = new PresentStage(null, context, null, null, new AssistantProperties(), mapper);
        ReviewStage review = new ReviewStage(null, context, tools, null);

        Map<String, Integer> budgets = Map.of("intake", 6_000, "build", 13_000, "present", 5_000, "review", 11_000);
        record Stage(String name, List<ContextAssembler.Block> system, List<LlmRequest.ToolDef> tools, String user) {}
        List<Stage> stages = List.of(
                new Stage("intake", context.intakeSystem(), List.of(), intakeContext(intake, s, "Show me revenue by quarter")),
                new Stage("build", context.buildSystem(), tools.definitions("distinct_values", "run_query", "sample_documents", "submit_data"),
                        buildContext(build, brief, ctx)),
                new Stage("present", context.presentSystem(), List.of(), presentContext(present, brief, artifact, ctx)),
                new Stage("review", context.reviewSystem(), tools.definitions("distinct_values", "run_query", "sample_documents", "submit_review"),
                        reviewContext(review, brief, artifact, ctx)));
        for (Stage st : stages) {
            int staticChars = st.system().stream().mapToInt(b -> b.text().length()).sum()
                    + st.tools().stream().mapToInt(t -> t.description().length() + t.inputSchema().toString().length()).sum();
            int staticTokens = staticChars / 4;
            assertThat(staticTokens).as(st.name() + " static tokens").isLessThan(budgets.get(st.name()));
            StringBuilder doc = new StringBuilder("# What the " + st.name() + " stage sees\n\nStatic (cached): ~" + staticTokens
                    + " tokens. Dynamic: ~" + st.user().length() / 4 + " tokens.\n\n## Manifest\n\n| block | source | chars | sha256 |\n|---|---|---|---|\n");
            context.manifest(st.system(), st.user()).forEach(m -> doc.append("| ").append(m.block()).append(" | ").append(m.source())
                    .append(" | ").append(m.chars()).append(" | ").append(m.sha256()).append(" |\n"));
            st.system().forEach(b -> doc.append("\n---\n## system: ").append(b.name()).append("\n\n").append(b.text()).append('\n'));
            st.tools().forEach(t -> doc.append("\n---\n## tool: ").append(t.name()).append(t.strict() ? " (strict)" : "").append("\n\n")
                    .append(t.description()).append("\n\n```json\n").append(t.inputSchema().toPrettyString()).append("\n```\n"));
            doc.append("\n---\n## user message (dynamic)\n\n```\n").append(st.user()).append("\n```\n");
            Files.writeString(dir.resolve(st.name() + ".md"), doc.toString());
        }
    }

    // ------------------------------------------------------------------ helpers
    private String intakeContext(IntakeStage stage, AssistantSession s, String msg) throws Exception {
        var m = IntakeStage.class.getDeclaredMethod("userContext", AssistantSession.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(stage, s, msg);
    }

    private String buildContext(BuildStage stage, JsonNode brief, AssistantTools.Context ctx) throws Exception {
        var m = BuildStage.class.getDeclaredMethod("userContext", JsonNode.class, BuildStage.Revision.class, AssistantTools.Context.class);
        m.setAccessible(true);
        return (String) m.invoke(stage, brief, null, ctx);
    }

    private String presentContext(PresentStage stage, JsonNode brief, JsonNode artifact, AssistantTools.Context ctx) throws Exception {
        var m = PresentStage.class.getDeclaredMethod("userContext", JsonNode.class, JsonNode.class, String.class, AssistantTools.Context.class);
        m.setAccessible(true);
        return (String) m.invoke(stage, brief, artifact, null, ctx);
    }

    private String reviewContext(ReviewStage stage, JsonNode brief, JsonNode artifact, AssistantTools.Context ctx) throws Exception {
        var m = ReviewStage.class.getDeclaredMethod("userContext", JsonNode.class, JsonNode.class, AssistantTools.Context.class);
        m.setAccessible(true);
        return (String) m.invoke(stage, brief, artifact, ctx);
    }

    private List<WidgetDefinition> starterDefinitions() throws Exception {
        List<WidgetDefinition> out = new ArrayList<>();
        for (var r : new PathMatchingResourcePatternResolver().getResources("classpath:widgets/starter/*.json")) {
            try (InputStream in = r.getInputStream()) {
                JsonNode s = mapper.readTree(in);
                WidgetDefinition d = new WidgetDefinition();
                d.setId(s.path("id").asText());
                d.setTitle(s.path("title").asText());
                d.setDescription(s.path("description").asText());
                d.setPrimitive(s.path("view").path("primitive").asText());
                // what the library records for each definition: a query shown for one client needs clientId
                List<String> params = new ArrayList<>();
                s.path("params").forEach(p -> { if (!p.path("name").asText().equals("asOfDate")) params.add(p.path("name").asText()); });
                if ("entity".equals(s.path("data").path("query").path("scope").asText())) params.add("clientId");
                d.setParams(params);
                d.setSpec(s.toString());
                out.add(d);
            }
        }
        return out;
    }
}
