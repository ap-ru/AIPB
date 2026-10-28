package com.aipb.skills;

import com.aipb.seed.Defaults;
import com.aipb.widget.WidgetDefinition;
import com.aipb.widget.WidgetPrimitives;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The shipped Skills Studio templates load and reference real things; a bad template stops startup with the reason. */
class SkillTemplatesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WidgetPrimitives primitives = new WidgetPrimitives(mapper);

    /** A widget kind with its stages and nothing else - enough for the registry's checks. */
    static final SkillKind WIDGET = new SkillKind() {
        @Override public String id() { return WidgetDefinition.WIDGET; }
        @Override public Map<String, String> stageLabels() { return Map.of("build", "", "review", "", "present", ""); }
        @Override public void proceed(SkillRun run, JsonNode out, String text, StageOps ops) { }
        @Override public void verify(SkillRun run, StageOps ops) { }
        @Override public ObjectNode reuseProposal(WidgetDefinition d, Map<String, String> params) { return null; }
        @Override public ObjectNode prepare(SkillRun run) { return null; }
    };

    private SkillTemplateRegistry shipped() { return new SkillTemplateRegistry(List.of(WIDGET), primitives, mapper); }

    @Test
    void theShippedTemplatesLoadAndCustomWidgetIsSilentOnEveryStage() {
        SkillTemplateRegistry r = shipped();
        assertThat(r.all()).extracting(SkillTemplate::id).contains("custom-widget", "exposure-vs-limit", "revenue-trend");
        SkillTemplate custom = r.get(SkillTemplate.CUSTOM_WIDGET);
        for (String stage : List.of("intake", "build", "review", "present")) assertThat(custom.silentOn(stage)).isTrue();
        assertThat(r.forSession(null)).isSameAs(custom); // sessions from before templates
        assertThatThrownBy(() -> r.get("nope")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void templatesAreOfferedOnlyOnPagesTheyFit() {
        SkillTemplateRegistry r = shipped();
        var client = Defaults.defaultLayout().page("client");
        var book = Defaults.defaultLayout().page("command-center");
        assertThat(r.forPage(client)).extracting(SkillTemplate::id).contains("custom-widget", "exposure-vs-limit", "revenue-trend");
        assertThat(r.forPage(book)).extracting(SkillTemplate::id).contains("custom-widget", "revenue-trend").doesNotContain("exposure-vs-limit");
    }

    @Test
    void everyCollectionATemplateNamesIsInTheDataDictionary() throws Exception {
        Set<String> collections = new HashSet<>();
        for (JsonNode c : mapper.readTree(Path.of("../data/catalog.json").toFile())) collections.add(c.path("_id").asText());
        for (SkillTemplate t : shipped().all())
            assertThat(collections).as(t.id() + " collections").containsAll(t.constraints().collections());
    }

    @Test
    void inputsAreCheckedAgainstTheTemplate() {
        SkillTemplateRegistry r = shipped();
        SkillTemplate exposure = r.get("exposure-vs-limit");
        assertThat(r.checkInputs(exposure, Map.of("breakdown", "By facility"))).containsExactly(Map.entry("breakdown", "By facility"));
        assertThat(r.checkInputs(exposure, Map.of("breakdown", " "))).isEmpty();
        assertThat(r.checkInputs(exposure, null)).isEmpty();
        assertThatThrownBy(() -> r.checkInputs(exposure, Map.of("breakdown", "By desk"))).hasMessageContaining("must be one of");
        assertThatThrownBy(() -> r.checkInputs(exposure, Map.of("colour", "red"))).hasMessageContaining("has no input 'colour'");
    }

    @Test
    void aBadTemplateStopsStartupWithEveryReason(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("custom-widget.json"), """
                {"id":"custom-widget","version":1,"kind":"widget","title":"Custom","summary":"s","icon":"spark","category":"General","pageScopes":["client","book"]}""");
        Files.writeString(dir.resolve("broken.json"), """
                {"id":"broken","version":1,"kind":"widget","title":"Broken","summary":"s","icon":"x","category":"c","pageScopes":["book"],
                 "guidance":{"narrate":"not a widget stage"},
                 "constraints":{"primitives":["pie"],"handlers":["create-task"],"scope":"client"},
                 "examples":{"build":["no-such-example"]},
                 "inputs":[{"name":"a","label":"A","type":"enum"},{"name":"a","label":"A again","type":"text"}]}""");
        Files.writeString(dir.resolve("misnamed.json"), """
                {"id":"other-name","version":1,"kind":"gadget","title":"T","summary":"s","icon":"x","category":"c","pageScopes":["book"]}""");
        Files.writeString(dir.resolve("schema.json"), """
                {"id":"schema","version":0,"kind":"widget"}""");

        assertThatThrownBy(() -> new SkillTemplateRegistry(List.of(WIDGET), primitives, mapper, "file:" + dir + "/*.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("guidance for 'narrate'")
                .hasMessageContaining("unknown primitive 'pie'")
                .hasMessageContaining("a widget has no action handlers")
                .hasMessageContaining("scope 'client' is not one of its pageScopes")
                .hasMessageContaining("unknown worked example 'no-such-example'")
                .hasMessageContaining("duplicate input 'a'")
                .hasMessageContaining("input 'a' is an enum without options")
                .hasMessageContaining("misnamed.json: the file name must be the template id")
                .hasMessageContaining("unknown kind 'gadget'")
                .hasMessageContaining("schema.json: ");
    }

    @Test
    void customWidgetIsRequired(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("only.json"), """
                {"id":"only","version":1,"kind":"widget","title":"Only","summary":"s","icon":"x","category":"c","pageScopes":["book"]}""");
        assertThatThrownBy(() -> new SkillTemplateRegistry(List.of(WIDGET), primitives, mapper, "file:" + dir + "/*.json"))
                .hasMessageContaining("the custom-widget template is required");
    }
}
